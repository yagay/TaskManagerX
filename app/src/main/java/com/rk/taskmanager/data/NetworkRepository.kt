package com.rk.taskmanager.data

import android.app.Application
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.TrafficStats
import android.os.Process
import android.os.SystemClock
import com.rk.taskmanager.model.NetworkEntry
import com.rk.taskmanager.model.NetworkSnapshot
import com.rk.taskmanager.root.RootShell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max

class NetworkRepository(
    private val application: Application,
    private val shell: RootShell,
) {
    private val packageManager = application.packageManager
    private var packageCache: Map<Int, List<PackageIdentity>> = emptyMap()
    private var packageCacheTime = 0L
    private var previous: Map<Int, Counter> = emptyMap()
    private var previousNanos: Long = 0L
    private var previousBackend: String? = null

    suspend fun read(knownUids: Set<Int>): NetworkSnapshot = withContext(Dispatchers.IO) {
        refreshPackageCacheIfNeeded()
        val nowNanos = SystemClock.elapsedRealtimeNanos()

        val rootSample = readRootUidCounters()
        val backend: String
        val current: Map<Int, Counter>

        if (rootSample.counters.isNotEmpty()) {
            backend = rootSample.backend
            current = rootSample.counters
        } else {
            val qtaguidCounters = readRootQtaguid()
            if (qtaguidCounters.isNotEmpty()) {
                backend = "Root qtaguid"
                current = qtaguidCounters
            } else {
                // Android N+ public TrafficStats cannot provide arbitrary other UIDs.
                // Keep this only as an explicit diagnostic fallback.
                backend = "TrafficStats (own UID only)"
                val uid = Process.myUid()
                val rx = TrafficStats.getUidRxBytes(uid)
                val tx = TrafficStats.getUidTxBytes(uid)
                current = if (rx >= 0L && tx >= 0L) mapOf(uid to Counter(rx, tx)) else emptyMap()
            }
        }

        // Never compare counters from different backends. Their accounting domains can differ,
        // which otherwise produces either huge spikes or a permanent stream of clamped zeroes.
        if (previousBackend != null && previousBackend != backend) {
            previous = emptyMap()
            previousNanos = 0L
        }

        val elapsedSeconds = if (previousNanos > 0L) {
            (nowNanos - previousNanos).coerceAtLeast(1L) / 1_000_000_000.0
        } else 0.0

        val uidFilter = if (knownUids.isEmpty()) null else knownUids
        val entries = if (elapsedSeconds <= 0.0) {
            emptyList()
        } else {
            current.mapNotNull { (uid, value) ->
                if (uidFilter != null && uid !in uidFilter && uid !in packageCache) return@mapNotNull null

                val old = previous[uid] ?: return@mapNotNull null
                val deltaRx = value.rx - old.rx
                val deltaTx = value.tx - old.tx
                // Counter reset/rollover: skip this sample instead of showing a false speed.
                if (deltaRx < 0L || deltaTx < 0L) return@mapNotNull null

                val rx = max(0L, (deltaRx / elapsedSeconds).toLong())
                val tx = max(0L, (deltaTx / elapsedSeconds).toLong())
                if (rx == 0L && tx == 0L) return@mapNotNull null

                val identities = packageCache[uid].orEmpty()
                val primary = identities.firstOrNull()
                NetworkEntry(
                    uid = uid,
                    label = primary?.label ?: primary?.packageName ?: "UID $uid",
                    packageNames = identities.map { it.packageName },
                    icon = primary?.icon,
                    system = identities.isEmpty() || identities.any { it.system },
                    rxBytesPerSecond = rx,
                    txBytesPerSecond = tx,
                )
            }.sortedByDescending { max(it.rxBytesPerSecond, it.txBytesPerSecond) }
        }

        previous = current
        previousNanos = nowNanos
        previousBackend = backend

        NetworkSnapshot(
            entries = entries,
            totalRxBytesPerSecond = entries.sumOf { it.rxBytesPerSecond },
            totalTxBytesPerSecond = entries.sumOf { it.txBytesPerSecond },
            backend = backend,
            timestampMs = System.currentTimeMillis(),
        )
    }

    /**
     * mAppUidStatsMap used to be dumped by netd's TrafficController. Newer Android
     * moved the map dump to NetworkStatsService, so probe the modern location first
     * and keep the old locations for OEM/backward compatibility.
     */
    private suspend fun readRootUidCounters(): RootSample {
        val probes = listOf(
            "Root netstats eBPF" to "dumpsys netstats 2>/dev/null",
            "Root connectivity eBPF" to "dumpsys connectivity trafficcontroller 2>/dev/null",
            "Root netd eBPF" to "dumpsys netd trafficcontroller 2>/dev/null",
        )

        for ((name, command) in probes) {
            val raw = runCatching { shell.execute(command, 7_000).stdout }.getOrDefault("")
            val parsed = parseAppUidStats(raw)
            if (parsed.isNotEmpty()) return RootSample(name, parsed)
        }
        return RootSample("No cross-UID root counters", emptyMap())
    }

    /**
     * Expected map body (indentation and punctuation around the section vary by release/OEM):
     * uid rxBytes rxPackets txBytes txPackets
     * 10234 12345 100 67890 120
     */
    private fun parseAppUidStats(raw: String): Map<Int, Counter> {
        if (raw.isBlank() || !raw.contains("mAppUidStatsMap")) return emptyMap()

        val result = HashMap<Int, Counter>()
        var inMap = false
        var sawData = false

        for (original in raw.lineSequence()) {
            val line = original.trim()

            if (line.contains("mAppUidStatsMap")) {
                inMap = true
                sawData = false
                continue
            }
            if (!inMap) continue

            if (line.isBlank()) {
                if (sawData) break
                continue
            }

            // A new named dump section after data means the app-uid map is finished.
            if (sawData && line.endsWith(":") && line.firstOrNull()?.isLetter() == true) break
            if (line.startsWith("uid ", ignoreCase = true) ||
                line.startsWith("BPF map", ignoreCase = true) ||
                line.contains("status:", ignoreCase = true)
            ) continue

            val parts = line.split(Regex("\\s+"))
            if (parts.size < 5) {
                if (sawData && line.firstOrNull()?.isLetter() == true) break
                continue
            }

            val uid = parts[0].trimEnd(':', ',').toIntOrNull() ?: continue
            val rx = parts[1].trimEnd(',').toLongOrNull() ?: continue
            val tx = parts[3].trimEnd(',').toLongOrNull() ?: continue
            result[uid] = Counter(rx, tx)
            sawData = true
        }
        return result
    }

    /** Legacy fallback for older/vendor kernels that still expose xt_qtaguid. */
    private suspend fun readRootQtaguid(): Map<Int, Counter> {
        val raw = runCatching {
            shell.execute(
                "if [ -r /proc/net/xt_qtaguid/stats ]; then cat /proc/net/xt_qtaguid/stats; fi",
                4_000,
            ).stdout
        }.getOrDefault("")
        if (raw.isBlank() || !raw.contains("uid_tag_int")) return emptyMap()

        val result = HashMap<Int, Counter>()
        raw.lineSequence().drop(1).forEach { line ->
            val parts = line.trim().split(Regex("\\s+"))
            if (parts.size < 8) return@forEach
            if (parts[2] != "0x0") return@forEach
            val uid = parts[3].toIntOrNull() ?: return@forEach
            val rx = parts[5].toLongOrNull() ?: return@forEach
            val tx = parts[7].toLongOrNull() ?: return@forEach
            val old = result[uid]
            result[uid] = Counter(
                rx = (old?.rx ?: 0L) + rx,
                tx = (old?.tx ?: 0L) + tx,
            )
        }
        return result
    }

    private fun refreshPackageCacheIfNeeded() {
        val now = System.currentTimeMillis()
        if (packageCache.isNotEmpty() && now - packageCacheTime < 60_000L) return

        @Suppress("DEPRECATION")
        val apps = packageManager.getInstalledApplications(PackageManager.GET_META_DATA)
        val map = HashMap<Int, MutableList<PackageIdentity>>()
        apps.forEach { app ->
            map.getOrPut(app.uid) { mutableListOf() } += PackageIdentity(
                packageName = app.packageName,
                label = runCatching { packageManager.getApplicationLabel(app).toString() }
                    .getOrDefault(app.packageName),
                icon = runCatching { packageManager.getApplicationIcon(app) }.getOrNull(),
                system = (app.flags and ApplicationInfo.FLAG_SYSTEM) != 0 ||
                    (app.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0,
            )
        }
        packageCache = map
        packageCacheTime = now
    }

    private data class RootSample(val backend: String, val counters: Map<Int, Counter>)
    private data class Counter(val rx: Long, val tx: Long)

    private data class PackageIdentity(
        val packageName: String,
        val label: String,
        val icon: android.graphics.drawable.Drawable?,
        val system: Boolean,
    )
}
