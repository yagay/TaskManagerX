package com.rk.taskmanager.data

import com.rk.taskmanager.model.CpuCoreInfo
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
            echo '__UPTIME__'
            cut -d' ' -f1 /proc/uptime
            echo '__SOC__'
            getprop ro.soc.model 2>/dev/null
            echo '__ARCH__'
            uname -m 2>/dev/null
            echo '__ABI__'
            getprop ro.product.cpu.abi 2>/dev/null
            echo '__GOV__'
            cat /sys/devices/system/cpu/cpu0/cpufreq/scaling_governor 2>/dev/null
            echo '__TEMP__'
            for z in /sys/class/thermal/thermal_zone*; do
              [ -r "${'$'}z/temp" ] || continue
              type=$(cat "${'$'}z/type" 2>/dev/null)
              case "${'$'}type" in
                *cpu*|*CPU*|*soc*|*SOC*|*ap*|*AP*)
                  echo "${'$'}type|$(cat "${'$'}z/temp" 2>/dev/null)"
                  break
                  ;;
              esac
            done
            echo '__CORES__'
            for c in /sys/devices/system/cpu/cpu[0-9]*; do
              n=${'$'}{c##*cpu}
              min=$(cat "${'$'}c/cpufreq/scaling_min_freq" 2>/dev/null)
              cur=$(cat "${'$'}c/cpufreq/scaling_cur_freq" 2>/dev/null)
              max=$(cat "${'$'}c/cpufreq/scaling_max_freq" 2>/dev/null)
              echo "${'$'}n|${'$'}min|${'$'}cur|${'$'}max"
            done
        """.trimIndent()

        val out = shell.execute(script, 10_000).stdout
        val stat = section(out, "__STAT__", "__MEM__").lineSequence().firstOrNull().orEmpty()
        val mem = section(out, "__MEM__", "__LOAD__")
        val load = section(out, "__LOAD__", "__UPTIME__").trim()
        val uptime = section(out, "__UPTIME__", "__SOC__").trim().toDoubleOrNull() ?: 0.0
        val soc = section(out, "__SOC__", "__ARCH__").trim()
        val arch = section(out, "__ARCH__", "__ABI__").trim()
        val abi = section(out, "__ABI__", "__GOV__").trim()
        val governor = section(out, "__GOV__", "__TEMP__").trim()
        val tempRaw = section(out, "__TEMP__", "__CORES__").lineSequence().firstOrNull().orEmpty()
        val coresRaw = out.substringAfter("__CORES__", "").trim()

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

        val cpuCores = coresRaw.lineSequence().mapNotNull { line ->
            val p = line.split('|')
            val core = p.getOrNull(0)?.toIntOrNull() ?: return@mapNotNull null
            CpuCoreInfo(
                core = core,
                minKHz = p.getOrNull(1)?.toLongOrNull(),
                currentKHz = p.getOrNull(2)?.toLongOrNull(),
                maxKHz = p.getOrNull(3)?.toLongOrNull(),
            )
        }.sortedBy { it.core }.toList()

        return SystemSnapshot(
            cpuPercent = cpuPercent,
            ramUsedBytes = max(0L, totalKb - availableKb) * 1024L,
            ramTotalBytes = totalKb * 1024L,
            ramAvailableBytes = availableKb * 1024L,
            cachedBytes = (memValues["Cached"] ?: 0L) * 1024L,
            buffersBytes = (memValues["Buffers"] ?: 0L) * 1024L,
            swapUsedBytes = max(0L, swapTotalKb - swapFreeKb) * 1024L,
            swapTotalBytes = swapTotalKb * 1024L,
            load1 = load.substringBefore(' ').toFloatOrNull() ?: 0f,
            uptimeMillis = (uptime * 1000.0).toLong(),
            soc = soc,
            architecture = arch,
            abi = abi,
            cpuCoreCount = cpuCores.size.takeIf { it > 0 } ?: Runtime.getRuntime().availableProcessors(),
            governor = governor,
            cpuTemperatureC = parseTemperature(tempRaw),
            cpuCores = cpuCores,
            timestampMs = System.currentTimeMillis(),
        )
    }

    private fun section(text: String, start: String, end: String): String =
        text.substringAfter(start, "").substringBefore(end, "").trim()

    private fun parseTemperature(line: String): Float? {
        val raw = line.substringAfter('|', line).trim().toFloatOrNull() ?: return null
        val c = when {
            raw > 10000f -> raw / 1000f
            raw > 1000f -> raw / 100f
            raw > 200f -> raw / 10f
            else -> raw
        }
        return c.takeIf { it in -20f..150f }
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
