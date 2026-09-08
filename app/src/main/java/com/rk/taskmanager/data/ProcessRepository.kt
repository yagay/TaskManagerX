package com.rk.taskmanager.data

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import com.rk.taskmanager.model.ProcessEntry
import com.rk.taskmanager.root.RootShell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ProcessRepository(
    context: Context,
    private val shell: RootShell,
) {
    private val packageManager = context.packageManager
    private var packageCache: Map<Int, PackageIdentity> = emptyMap()
    private var cacheTime = 0L

    suspend fun listProcesses(): List<ProcessEntry> = withContext(Dispatchers.IO) {
        refreshPackageCacheIfNeeded()

        val command = """
            if command -v toybox >/dev/null 2>&1; then
              toybox ps -A -o PID,PPID,UID,NI,STAT,RSS,%CPU,NAME,ARGS 2>/dev/null || \
              toybox ps -A -o PID,PPID,UID,NI,STAT,RSS,NAME,ARGS
            else
              ps -A
            fi
        """.trimIndent()

        val raw = shell.execute(command, 10_000).stdout
        parsePs(raw)
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

    private fun parsePs(text: String): List<ProcessEntry> {
        val lines = text.lineSequence().filter { it.isNotBlank() }.toList()
        if (lines.size <= 1) return emptyList()

        val header = lines.first().trim().split(Regex("\\s+"))
        val hasCpu = header.any { it.equals("%CPU", true) }
        val expectedMinColumns = if (hasCpu) 9 else 8

        return lines.drop(1).mapNotNull { line ->
            val parts = line.trim().split(Regex("\\s+"), limit = expectedMinColumns)
            if (parts.size < expectedMinColumns - 1) return@mapNotNull null

            runCatching {
                var i = 0
                val pid = parts[i++].toInt()
                val ppid = parts[i++].toInt()
                val uid = parseUid(parts[i++])
                val nice = parts[i++].toIntOrNull() ?: 0
                val state = parts[i++]
                val rssKb = parts[i++].toLongOrNull() ?: 0L
                val cpu = if (hasCpu) {
                    parts[i++].removeSuffix("%").toFloatOrNull() ?: 0f
                } else {
                    0f
                }
                val name = parts.getOrElse(i++) { "pid-$pid" }
                val command = parts.getOrElse(i) { name }

                val pkg = packageCache[uid]
                ProcessEntry(
                    pid = pid,
                    ppid = ppid,
                    uid = uid,
                    nice = nice,
                    state = state,
                    rssKb = rssKb,
                    cpuPercent = cpu,
                    name = name,
                    command = command,
                    packageName = pkg?.packageName,
                    appLabel = pkg?.label,
                    icon = pkg?.icon,
                )
            }.getOrNull()
        }
    }

    private fun parseUid(value: String): Int {
        value.toIntOrNull()?.let { return it }

        Regex("""u(\d+)_a(\d+)""").matchEntire(value)?.let { match ->
            val userId = match.groupValues[1].toIntOrNull() ?: return@let
            val appIndex = match.groupValues[2].toIntOrNull() ?: return@let
            return userId * 100000 + 10000 + appIndex
        }

        Regex("""u(\d+)_i(\d+)""").matchEntire(value)?.let { match ->
            val userId = match.groupValues[1].toIntOrNull() ?: return@let
            val isolatedIndex = match.groupValues[2].toIntOrNull() ?: return@let
            return userId * 100000 + 99000 + isolatedIndex
        }

        return when (value) {
            "root" -> 0
            "system" -> 1000
            "radio" -> 1001
            "bluetooth" -> 1002
            "graphics" -> 1003
            "input" -> 1004
            "audio" -> 1005
            "camera" -> 1006
            "log" -> 1007
            "mount" -> 1009
            "wifi" -> 1010
            "adb" -> 1011
            "install" -> 1012
            "media" -> 1013
            "dhcp" -> 1014
            "vpn" -> 1016
            "keystore" -> 1017
            "usb" -> 1018
            "drm" -> 1019
            "gps" -> 1021
            "media_rw" -> 1023
            "nfc" -> 1027
            "shell" -> 2000
            else -> -1
        }
    }

    private fun refreshPackageCacheIfNeeded() {
        val now = System.currentTimeMillis()
        if (packageCache.isNotEmpty() && now - cacheTime < 60_000) return

        @Suppress("DEPRECATION")
        val apps = packageManager.getInstalledApplications(PackageManager.GET_META_DATA)
        val map = HashMap<Int, PackageIdentity>()

        for (app in apps) {
            if (!map.containsKey(app.uid)) {
                map[app.uid] = PackageIdentity(
                    packageName = app.packageName,
                    label = runCatching { packageManager.getApplicationLabel(app).toString() }
                        .getOrDefault(app.packageName),
                    icon = runCatching { packageManager.getApplicationIcon(app) }.getOrNull(),
                    system = (app.flags and ApplicationInfo.FLAG_SYSTEM) != 0,
                )
            }
        }

        packageCache = map
        cacheTime = now
    }

    private data class PackageIdentity(
        val packageName: String,
        val label: String,
        val icon: android.graphics.drawable.Drawable?,
        val system: Boolean,
    )
}
