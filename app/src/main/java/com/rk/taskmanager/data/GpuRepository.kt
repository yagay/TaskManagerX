package com.rk.taskmanager.data

import android.app.Application
import android.content.pm.PackageManager
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.GLES20
import com.rk.taskmanager.model.GpuSnapshot
import com.rk.taskmanager.root.RootShell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class GpuRepository(
    private val application: Application,
    private val shell: RootShell,
) {
    @Volatile private var staticInfo: StaticGpuInfo? = null

    suspend fun read(): GpuSnapshot = withContext(Dispatchers.IO) {
        val info = staticInfo ?: loadStaticInfo().also { staticInfo = it }
        val dynamic = readDynamicInfo()
        GpuSnapshot(
            vendor = info.vendor,
            renderer = info.renderer,
            openGlVersion = info.openGlVersion,
            glslVersion = info.glslVersion,
            vulkanSupported = info.vulkanSupported,
            vulkanApiVersion = info.vulkanApiVersion,
            usagePercent = dynamic.usagePercent,
            currentHz = dynamic.currentHz,
            minHz = dynamic.minHz,
            maxHz = dynamic.maxHz,
        )
    }

    private fun loadStaticInfo(): StaticGpuInfo {
        val gl = runCatching { readOpenGlInfo() }.getOrNull()
        val pm = application.packageManager
        var vulkanApi: String? = null
        pm.systemAvailableFeatures.forEach { feature ->
            if (feature.name == PackageManager.FEATURE_VULKAN_HARDWARE_VERSION) {
                val value = feature.version
                val major = value ushr 22
                val minor = (value ushr 12) and 0x3ff
                val patch = value and 0xfff
                vulkanApi = "$major.$minor.$patch"
            }
        }
        return StaticGpuInfo(
            vendor = gl?.vendor,
            renderer = gl?.renderer,
            openGlVersion = gl?.openGlVersion,
            glslVersion = gl?.glslVersion,
            vulkanSupported = pm.hasSystemFeature(PackageManager.FEATURE_VULKAN_HARDWARE_LEVEL) ||
                pm.hasSystemFeature(PackageManager.FEATURE_VULKAN_HARDWARE_VERSION),
            vulkanApiVersion = vulkanApi,
        )
    }

    private fun readOpenGlInfo(): StaticGpuInfo {
        val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val versions = IntArray(2)
        check(EGL14.eglInitialize(display, versions, 0, versions, 1))
        val configs = arrayOfNulls<EGLConfig>(1)
        val count = IntArray(1)
        val configAttrs = intArrayOf(EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT, EGL14.EGL_NONE)
        check(EGL14.eglChooseConfig(display, configAttrs, 0, configs, 0, 1, count, 0))
        val config = configs.firstOrNull() ?: error("No EGL config")
        val context = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
        val surface = EGL14.eglCreatePbufferSurface(display, config,
            intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0)
        check(EGL14.eglMakeCurrent(display, surface, surface, context))
        val result = StaticGpuInfo(
            vendor = GLES20.glGetString(GLES20.GL_VENDOR),
            renderer = GLES20.glGetString(GLES20.GL_RENDERER),
            openGlVersion = GLES20.glGetString(GLES20.GL_VERSION),
            glslVersion = GLES20.glGetString(GLES20.GL_SHADING_LANGUAGE_VERSION),
            vulkanSupported = false,
            vulkanApiVersion = null,
        )
        EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
        EGL14.eglDestroySurface(display, surface)
        EGL14.eglDestroyContext(display, context)
        EGL14.eglTerminate(display)
        return result
    }

    private suspend fun readDynamicInfo(): DynamicGpuInfo {
        val out = runCatching {
            shell.execute(
                """
                busy=""
                if [ -r /sys/class/kgsl/kgsl-3d0/gpubusy ]; then
                  busy=$(cat /sys/class/kgsl/kgsl-3d0/gpubusy 2>/dev/null)
                else
                  for f in /sys/class/devfreq/*gpu*/load /sys/class/devfreq/*mali*/load; do
                    [ -r "${'$'}f" ] && { busy=$(cat "${'$'}f" 2>/dev/null); break; }
                  done
                fi
                cur=""; min=""; max=""
                for f in /sys/class/kgsl/kgsl-3d0/devfreq/cur_freq /sys/class/devfreq/*gpu*/cur_freq /sys/class/devfreq/*mali*/cur_freq; do
                  [ -r "${'$'}f" ] && { cur=$(cat "${'$'}f" 2>/dev/null); break; }
                done
                for f in /sys/class/kgsl/kgsl-3d0/devfreq/min_freq /sys/class/devfreq/*gpu*/min_freq /sys/class/devfreq/*mali*/min_freq; do
                  [ -r "${'$'}f" ] && { min=$(cat "${'$'}f" 2>/dev/null); break; }
                done
                for f in /sys/class/kgsl/kgsl-3d0/devfreq/max_freq /sys/class/devfreq/*gpu*/max_freq /sys/class/devfreq/*mali*/max_freq; do
                  [ -r "${'$'}f" ] && { max=$(cat "${'$'}f" 2>/dev/null); break; }
                done
                echo "BUSY|${'$'}busy"
                echo "FREQ|${'$'}cur|${'$'}min|${'$'}max"
                """.trimIndent(),
                4_000,
            ).stdout
        }.getOrDefault("")

        val busyRaw = out.lineSequence().firstOrNull { it.startsWith("BUSY|") }
            ?.substringAfter("BUSY|")?.trim().orEmpty()
        val freq = out.lineSequence().firstOrNull { it.startsWith("FREQ|") }?.split('|').orEmpty()
        return DynamicGpuInfo(
            usagePercent = parseUsage(busyRaw),
            currentHz = normalizeFrequency(freq.getOrNull(1)?.toLongOrNull()),
            minHz = normalizeFrequency(freq.getOrNull(2)?.toLongOrNull()),
            maxHz = normalizeFrequency(freq.getOrNull(3)?.toLongOrNull()),
        )
    }

    private fun parseUsage(raw: String): Float? {
        val numbers = Regex("\\d+").findAll(raw).mapNotNull { it.value.toLongOrNull() }.toList()
        if (numbers.size >= 2 && numbers[1] > 0L) {
            return (numbers[0] * 100.0 / numbers[1]).toFloat().coerceIn(0f, 100f)
        }
        val value = numbers.firstOrNull()?.toFloat() ?: return null
        return when {
            value <= 100f -> value
            value <= 1000f -> value / 10f
            else -> null
        }?.coerceIn(0f, 100f)
    }

    private fun normalizeFrequency(value: Long?): Long? {
        value ?: return null
        if (value <= 0L) return null
        return when {
            value < 10_000L -> value * 1_000_000L
            value < 10_000_000L -> value * 1_000L
            else -> value
        }
    }

    private data class StaticGpuInfo(
        val vendor: String?, val renderer: String?, val openGlVersion: String?, val glslVersion: String?,
        val vulkanSupported: Boolean, val vulkanApiVersion: String?,
    )
    private data class DynamicGpuInfo(
        val usagePercent: Float?, val currentHz: Long?, val minHz: Long?, val maxHz: Long?,
    )
}
