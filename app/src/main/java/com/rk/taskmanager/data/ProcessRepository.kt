package com.rk.taskmanager.data

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import com.rk.taskmanager.model.ProcessEntry
import com.rk.taskmanager.model.ProcessKind
import com.rk.taskmanager.root.RootShell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max

class ProcessRepository(
    context: Context,
    private val shell: RootShell,
) {
    private val packageManager = context.packageManager
    private var packageCache: Map<Int, List<PackageIdentity>> = emptyMap()
    private var cacheTime = 0L
    private var previousTicks: Map<Int, Long> = emptyMap()
    private var previousSampleMs: Long = 0L
    private var clockTicksPerSecond: Long = 100L

    suspend fun listProcesses(): List<ProcessEntry> = withContext(Dispatchers.IO) {
        refreshPackageCacheIfNeeded()

        val script = """
            HZ=$(getconf CLK_TCK 2>/dev/null || echo 100)
            UP=$(cut -d' ' -f1 /proc/uptime 2>/dev/null)
            echo "__META__|$HZ|$UP"
            for p in /proc/[0-9]*; do
              pid=${'$'}{p##*/}
              statline=$(cat "${'$'}p/stat" 2>/dev/null) || continue
              uid=$(awk '/^Uid:/{print ${'$'}2; exit}' "${'$'}p/status" 2>/dev/null)
              rss=$(awk '/^VmRSS:/{print ${'$'}2; exit}' "${'$'}p/status" 2>/dev/null)
              threads=$(awk '/^Threads:/{print ${'$'}2; exit}' "${'$'}p/status" 2>/dev/null)
              user=$(stat -c %U "${'$'}p" 2>/dev/null)
              oom=$(cat "${'$'}p/oom_score_adj" 2>/dev/null)
              cmd=$(tr '\000\t\r\n' '    ' < "${'$'}p/cmdline" 2>/dev/null | sed 's/[[:space:]]\+/ /g; s/^ //; s/ ${'$'}//')
              exe=$(readlink "${'$'}p/exe" 2>/dev/null | tr '\t\r\n' '   ')
              printf '__PROC__|%s|%s|%s|%s|%s|%s|%s|%s\n' "${'$'}pid" "${'$'}uid" "${'$'}rss" "${'$'}threads" "${'$'}user" "${'$'}oom" "${'$'}cmd" "${'$'}exe"
              printf '__STAT__|%s\n' "${'$'}statline"
            done
        """.trimIndent()

        val raw = shell.execute(script, 15_000).stdout
        parseProcDump(raw)
    }

    suspend fun killProcess(pid: Int): Boolean {
        if (pid <= 1) return false
        return shell.execute("kill -9 $pid", 4_000).code == 0
    }

    suspend fun forceStop(packageName: String): Boolean {
        if (packageName.isBlank()) return false
        val safe = packageName.replace(Regex("[^A-Za-z0-9._]"), "")
        if (safe != packageName) return false
        return shell.execute("am force-stop $safe", 5_000).code == 0
    }

    private fun parseProcDump(text: String): List<ProcessEntry> {
        val lines = text.lineSequence().toList()
        val meta = lines.firstOrNull { it.startsWith("__META__|") }
            ?.split('|')
        clockTicksPerSecond = meta?.getOrNull(1)?.toLongOrNull()?.coerceAtLeast(1L) ?: 100L
        val uptimeSeconds = meta?.getOrNull(2)?.toDoubleOrNull() ?: 0.0

        val nowMs = System.currentTimeMillis()
        val elapsedSampleMs = if (previousSampleMs > 0L) max(1L, nowMs - previousSampleMs) else 0L
        val newTicks = HashMap<Int, Long>()
        val result = ArrayList<ProcessEntry>()

        var i = 0
        while (i < lines.size) {
            val procLine = lines[i]
            if (!procLine.startsWith("__PROC__|")) {
                i++
                continue
            }
            val statLine = lines.getOrNull(i + 1)
            if (statLine == null || !statLine.startsWith("__STAT__|")) {
                i++
                continue
            }

            val proc = procLine.split('|', limit = 9)
            val pid = proc.getOrNull(1)?.toIntOrNull()
            val stat = parseStat(statLine.removePrefix("__STAT__|"))
            if (pid == null || stat == null || stat.pid != pid) {
                i += 2
                continue
            }

            val uid = proc.getOrNull(2)?.toIntOrNull() ?: -1
            val rssKb = proc.getOrNull(3)?.toLongOrNull() ?: 0L
            val threads = proc.getOrNull(4)?.toIntOrNull() ?: stat.threads
            val userName = proc.getOrNull(5).orEmpty().ifBlank { uid.toString() }
            val oom = proc.getOrNull(6)?.toIntOrNull()
            val command = proc.getOrNull(7).orEmpty().ifBlank { stat.name }
            val exe = proc.getOrNull(8)?.takeIf { it.isNotBlank() }

            val totalTicks = stat.userTicks + stat.systemTicks
            newTicks[pid] = totalTicks
            val oldTicks = previousTicks[pid]
            val cpuPercent = if (oldTicks != null && elapsedSampleMs > 0L) {
                val deltaTicks = max(0L, totalTicks - oldTicks)
                ((deltaTicks.toDouble() / clockTicksPerSecond.toDouble()) /
                    (elapsedSampleMs.toDouble() / 1000.0) * 100.0).toFloat().coerceAtLeast(0f)
            } else 0f

            val elapsedSeconds = max(0.0, uptimeSeconds - stat.startTicks.toDouble() / clockTicksPerSecond)
            val elapsedMs = (elapsedSeconds * 1000.0).toLong()
            val startTimeMillis = nowMs - elapsedMs

            val identities = packageCache[uid].orEmpty()
            val primary = identities.firstOrNull { identity ->
                command == identity.packageName || command.startsWith(identity.packageName + ":")
            } ?: identities.firstOrNull()
            val kind = when {
                identities.isEmpty() -> ProcessKind.LINUX
                identities.any { it.system } -> ProcessKind.SYSTEM_APP
                else -> ProcessKind.USER_APP
            }

            // oom_score_adj <= 0 is a useful low-level approximation for foreground/
            // perceptible importance. The LSPosed system_server layer can later replace
            // this with ActivityManager process-state information for exact Android state.
            val foreground = identities.isNotEmpty() && (oom?.let { it <= 0 } == true)

            result += ProcessEntry(
                pid = pid,
                ppid = stat.ppid,
                uid = uid,
                userName = userName,
                nice = stat.nice,
                state = stat.state,
                rssKb = rssKb,
                cpuPercent = cpuPercent,
                name = stat.name,
                command = command,
                executablePath = exe,
                threads = threads,
                startTimeMillis = startTimeMillis,
                elapsedTimeMillis = elapsedMs,
                oomScoreAdj = oom,
                isForeground = foreground,
                packageNames = identities.map { it.packageName },
                packageName = primary?.packageName,
                appLabel = primary?.label,
                icon = primary?.icon,
                kind = kind,
            )
            i += 2
        }

        previousTicks = newTicks
        previousSampleMs = nowMs
        return result
    }

    private fun parseStat(raw: String): ProcStat? {
        val open = raw.indexOf('(')
        val close = raw.lastIndexOf(')')
        if (open <= 0 || close <= open) return null

        val pid = raw.substring(0, open).trim().toIntOrNull() ?: return null
        val name = raw.substring(open + 1, close)
        val fields = raw.substring(close + 1).trim().split(Regex("\\s+"))
        if (fields.size < 22) return null

        return ProcStat(
            pid = pid,
            name = name,
            state = fields[0],
            ppid = fields[1].toIntOrNull() ?: 0,
            userTicks = fields[11].toLongOrNull() ?: 0L,
            systemTicks = fields[12].toLongOrNull() ?: 0L,
            nice = fields[16].toIntOrNull() ?: 0,
            threads = fields[17].toIntOrNull() ?: 0,
            startTicks = fields[19].toLongOrNull() ?: 0L,
        )
    }

    private fun refreshPackageCacheIfNeeded() {
        val now = System.currentTimeMillis()
        if (packageCache.isNotEmpty() && now - cacheTime < 60_000) return

        @Suppress("DEPRECATION")
        val apps = packageManager.getInstalledApplications(PackageManager.GET_META_DATA)
        val map = HashMap<Int, MutableList<PackageIdentity>>()

        for (app in apps) {
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
        cacheTime = now
    }

    private data class ProcStat(
        val pid: Int,
        val name: String,
        val state: String,
        val ppid: Int,
        val userTicks: Long,
        val systemTicks: Long,
        val nice: Int,
        val threads: Int,
        val startTicks: Long,
    )

    private data class PackageIdentity(
        val packageName: String,
        val label: String,
        val icon: android.graphics.drawable.Drawable?,
        val system: Boolean,
    )
}
