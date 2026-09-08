package com.rk.taskmanager.data

import com.rk.taskmanager.model.SystemSnapshot
import com.rk.taskmanager.root.RootShell
import kotlin.math.max

class SystemStatsRepository(
    private val shell: RootShell,
) {
    private var previousCpu: CpuTimes? = null

    suspend fun read(): SystemSnapshot {
        val script = """
            echo '__STAT__'
            head -n 1 /proc/stat
            echo '__MEM__'
            cat /proc/meminfo
            echo '__LOAD__'
            cat /proc/loadavg
        """.trimIndent()

        val out = shell.execute(script).stdout
        val stat = out.substringAfter("__STAT__", "")
            .substringBefore("__MEM__", "")
            .trim()
            .lineSequence()
            .firstOrNull()
            .orEmpty()
        val mem = out.substringAfter("__MEM__", "")
            .substringBefore("__LOAD__", "")
        val load = out.substringAfter("__LOAD__", "").trim()

        val currentCpu = parseCpu(stat)
        val cpuPercent = calculateCpu(previousCpu, currentCpu)
        previousCpu = currentCpu

        val memValues = parseMemInfo(mem)
        val totalKb = memValues["MemTotal"] ?: 0L
        val availableKb = memValues["MemAvailable"]
            ?: ((memValues["MemFree"] ?: 0L) +
                (memValues["Buffers"] ?: 0L) +
                (memValues["Cached"] ?: 0L))
        val swapTotalKb = memValues["SwapTotal"] ?: 0L
        val swapFreeKb = memValues["SwapFree"] ?: 0L

        return SystemSnapshot(
            cpuPercent = cpuPercent,
            ramUsedBytes = max(0L, totalKb - availableKb) * 1024L,
            ramTotalBytes = totalKb * 1024L,
            swapUsedBytes = max(0L, swapTotalKb - swapFreeKb) * 1024L,
            swapTotalBytes = swapTotalKb * 1024L,
            load1 = load.substringBefore(' ').toFloatOrNull() ?: 0f,
            timestampMs = System.currentTimeMillis(),
        )
    }

    private fun parseMemInfo(text: String): Map<String, Long> = buildMap {
        text.lineSequence().forEach { line ->
            val key = line.substringBefore(':').trim()
            val value = line.substringAfter(':', "")
                .trim()
                .substringBefore(' ')
                .toLongOrNull()
            if (key.isNotEmpty() && value != null) put(key, value)
        }
    }

    private fun parseCpu(line: String): CpuTimes? {
        if (!line.startsWith("cpu ")) return null
        val values = line.trim().split(Regex("\\s+")).drop(1).mapNotNull { it.toLongOrNull() }
        if (values.size < 4) return null
        val idle = values.getOrElse(3) { 0L } + values.getOrElse(4) { 0L }
        val total = values.sum()
        return CpuTimes(total, idle)
    }

    private fun calculateCpu(old: CpuTimes?, new: CpuTimes?): Float {
        if (old == null || new == null) return 0f
        val totalDelta = new.total - old.total
        val idleDelta = new.idle - old.idle
        if (totalDelta <= 0) return 0f
        return (((totalDelta - idleDelta).toDouble() / totalDelta.toDouble()) * 100.0)
            .toFloat()
            .coerceIn(0f, 100f)
    }

    private data class CpuTimes(val total: Long, val idle: Long)
}
