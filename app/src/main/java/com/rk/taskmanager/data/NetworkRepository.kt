package com.rk.taskmanager.data

import android.app.Application
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.TrafficStats
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

    suspend fun read(knownUids: Set<Int>): NetworkSnapshot = withContext(Dispatchers.IO) {
        refreshPackageCacheIfNeeded()
        val nowNanos = SystemClock.elapsedRealtimeNanos()
        val rootCounters = readRootQtaguid()
        val allUids = buildSet {
            addAll(packageCache.keys)
            addAll(knownUids.filter { it >= 0 })
            addAll(rootCounters.keys)
        }

        val backend: String
        val current: Map<Int, Counter>
        if (rootCounters.isNotEmpty()) {
            backend = "Root qtaguid"
            current = rootCounters
        } else {
            backend = "TrafficStats"
            current = allUids.mapNotNull { uid ->
                val rx = TrafficStats.getUidRxBytes(uid)
                val tx = TrafficStats.getUidTxBytes(uid)
                if (rx < 0L || tx < 0L) null else uid to Counter(rx, tx)
            }.toMap()
        }

        val elapsedSeconds = if (previousNanos > 0L) {
            (nowNanos - previousNanos).coerceAtLeast(1L) / 1_000_000_000.0
        } else 0.0

        val entries = if (elapsedSeconds <= 0.0) {
            emptyList()
        } else {
            current.mapNotNull { (uid, value) ->
                val old = previous[uid] ?: return@mapNotNull null
                val rx = max(0L, ((value.rx - old.rx) / elapsedSeconds).toLong())
                val tx = max(0L, ((value.tx - old.tx) / elapsedSeconds).toLong())
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
            }.sortedByDescending { it.totalBytesPerSecond }
        }

        previous = current
        previousNanos = nowNanos
        NetworkSnapshot(
            entries = entries,
            totalRxBytesPerSecond = entries.sumOf { it.rxBytesPerSecond },
            totalTxBytesPerSecond = entries.sumOf { it.txBytesPerSecond },
            backend = backend,
            timestampMs = System.currentTimeMillis(),
        )
    }

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

    private data class Counter(val rx: Long, val tx: Long)

    private data class PackageIdentity(
        val packageName: String,
        val label: String,
        val icon: android.graphics.drawable.Drawable?,
        val system: Boolean,
    )
}
