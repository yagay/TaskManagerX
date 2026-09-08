package com.rk.taskmanager.model

import android.graphics.drawable.Drawable

data class RootState(
    val checked: Boolean = false,
    val granted: Boolean = false,
    val uid: Int? = null,
    val message: String = "Not checked",
)

data class FrameworkState(
    val detected: Boolean = false,
    val detail: String = "Not checked",
)

data class SystemSnapshot(
    val cpuPercent: Float = 0f,
    val ramUsedBytes: Long = 0,
    val ramTotalBytes: Long = 0,
    val ramAvailableBytes: Long = 0,
    val cachedBytes: Long = 0,
    val buffersBytes: Long = 0,
    val swapUsedBytes: Long = 0,
    val swapTotalBytes: Long = 0,
    val load1: Float = 0f,
    val uptimeMillis: Long = 0L,
    val soc: String = "",
    val architecture: String = "",
    val abi: String = "",
    val cpuCoreCount: Int = 0,
    val governor: String = "",
    val cpuTemperatureC: Float? = null,
    val cpuCores: List<CpuCoreInfo> = emptyList(),
    val timestampMs: Long = 0L,
)

data class GpuSnapshot(
    val vendor: String? = null,
    val renderer: String? = null,
    val openGlVersion: String? = null,
    val glslVersion: String? = null,
    val vulkanSupported: Boolean = false,
    val vulkanApiVersion: String? = null,
    val usagePercent: Float? = null,
    val currentHz: Long? = null,
    val minHz: Long? = null,
    val maxHz: Long? = null,
)

data class NetworkEntry(
    val uid: Int,
    val label: String,
    val packageNames: List<String> = emptyList(),
    val icon: Drawable? = null,
    val system: Boolean = false,
    val rxBytesPerSecond: Long = 0,
    val txBytesPerSecond: Long = 0,
) {
    val totalBytesPerSecond: Long get() = rxBytesPerSecond + txBytesPerSecond
}

data class NetworkSnapshot(
    val entries: List<NetworkEntry> = emptyList(),
    val totalRxBytesPerSecond: Long = 0,
    val totalTxBytesPerSecond: Long = 0,
    val backend: String = "Not sampled",
    val timestampMs: Long = 0L,
)

enum class ProcessKind {
    USER_APP, SYSTEM_APP, LINUX
}

data class ProcessEntry(
    val pid: Int,
    val ppid: Int,
    val uid: Int,
    val userName: String,
    val nice: Int,
    val state: String,
    val rssKb: Long,
    val virtualMemoryKb: Long = 0L,
    val cpuPercent: Float,
    val name: String,
    val command: String,
    val executablePath: String? = null,
    val cgroup: String? = null,
    val threads: Int = 0,
    val startTimeMillis: Long = 0L,
    val elapsedTimeMillis: Long = 0L,
    val oomScoreAdj: Int? = null,
    val isForeground: Boolean = false,
    val packageNames: List<String> = emptyList(),
    val packageName: String? = null,
    val appLabel: String? = null,
    val icon: Drawable? = null,
    val kind: ProcessKind = ProcessKind.LINUX,
    val isPinned: Boolean = false,
    val rxBytesPerSecond: Long = 0L,
    val txBytesPerSecond: Long = 0L,
) {
    val displayName: String
        get() = appLabel?.takeIf { it.isNotBlank() }
            ?: packageName?.takeIf { it.isNotBlank() }
            ?: name

    val totalNetworkBytesPerSecond: Long
        get() = rxBytesPerSecond + txBytesPerSecond
}

enum class ProcessSort {
    MEMORY, CPU, NETWORK, NAME, PID
}

data class TaskManagerUiState(
    val root: RootState = RootState(),
    val framework: FrameworkState = FrameworkState(),
    val system: SystemSnapshot = SystemSnapshot(),
    val gpu: GpuSnapshot = GpuSnapshot(),
    val network: NetworkSnapshot = NetworkSnapshot(),
    val processes: List<ProcessEntry> = emptyList(),
    val loading: Boolean = true,
    val query: String = "",
    val sort: ProcessSort = ProcessSort.MEMORY,
    val showUserApps: Boolean = true,
    val showSystemApps: Boolean = true,
    val showLinuxProcesses: Boolean = false,
    val autoRefresh: Boolean = true,
    val refreshIntervalMs: Long = 800L,
    val confirmKill: Boolean = true,
    val processCount: Int = 0,
    val threadCount: Int = 0,
    val cpuHistory: List<Float> = emptyList(),
    val ramHistory: List<Float> = emptyList(),
    val swapHistory: List<Float> = emptyList(),
    val gpuHistory: List<Float> = emptyList(),
    val error: String? = null,
)
