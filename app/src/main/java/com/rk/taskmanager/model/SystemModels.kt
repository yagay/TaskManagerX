package com.rk.taskmanager.model

data class CpuCoreInfo(
    val core: Int,
    val minKHz: Long? = null,
    val currentKHz: Long? = null,
    val maxKHz: Long? = null,
)
