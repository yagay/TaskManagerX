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
    val swapUsedBytes: Long = 0,
    val swapTotalBytes: Long = 0,
    val load1: Float = 0f,
    val timestampMs: Long = 0L,
)

data class ProcessEntry(
    val pid: Int,
    val ppid: Int,
    val uid: Int,
    val nice: Int,
    val state: String,
    val rssKb: Long,
    val cpuPercent: Float,
    val name: String,
    val command: String,
    val packageName: String? = null,
    val appLabel: String? = null,
    val icon: Drawable? = null,
) {
    val displayName: String
        get() = appLabel?.takeIf { it.isNotBlank() }
            ?: packageName?.takeIf { it.isNotBlank() }
            ?: name
}

enum class ProcessSort {
    CPU, MEMORY, NAME, PID
}

data class TaskManagerUiState(
    val root: RootState = RootState(),
    val framework: FrameworkState = FrameworkState(),
    val system: SystemSnapshot = SystemSnapshot(),
    val processes: List<ProcessEntry> = emptyList(),
    val loading: Boolean = true,
    val query: String = "",
    val sort: ProcessSort = ProcessSort.CPU,
    val error: String? = null,
)
