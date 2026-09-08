package com.rk.taskmanager.data

import android.content.Context
import com.rk.taskmanager.model.ProcessSort

class SettingsRepository(context: Context) {
    private val prefs = context.getSharedPreferences("taskmanager_settings", Context.MODE_PRIVATE)

    var autoRefresh: Boolean
        get() = prefs.getBoolean("proc_auto_refresh", true)
        set(value) = prefs.edit().putBoolean("proc_auto_refresh", value).apply()

    var refreshIntervalMs: Long
        get() = prefs.getLong("update_frequency", 800L).coerceIn(250L, 10_000L)
        set(value) = prefs.edit().putLong("update_frequency", value.coerceIn(250L, 10_000L)).apply()

    var showSystemApps: Boolean
        get() = prefs.getBoolean("show_system_apps", true)
        set(value) = prefs.edit().putBoolean("show_system_apps", value).apply()

    var showUserApps: Boolean
        get() = prefs.getBoolean("show_user_apps", true)
        set(value) = prefs.edit().putBoolean("show_user_apps", value).apply()

    var showLinuxProcesses: Boolean
        get() = prefs.getBoolean("show_linux_process", false)
        set(value) = prefs.edit().putBoolean("show_linux_process", value).apply()

    var confirmKill: Boolean
        get() = prefs.getBoolean("confirm_kill", true)
        set(value) = prefs.edit().putBoolean("confirm_kill", value).apply()

    var sort: ProcessSort
        get() = runCatching {
            ProcessSort.valueOf(prefs.getString("sort_by", ProcessSort.MEMORY.name)!!)
        }.getOrDefault(ProcessSort.MEMORY)
        set(value) = prefs.edit().putString("sort_by", value.name).apply()

    var pinnedProcesses: Set<String>
        get() = prefs.getStringSet("pinned_processes", emptySet())?.toSet().orEmpty()
        set(value) = prefs.edit().putStringSet("pinned_processes", value.toSet()).apply()

    fun togglePinned(key: String): Boolean {
        val current = pinnedProcesses.toMutableSet()
        val nowPinned = if (key in current) {
            current.remove(key)
            false
        } else {
            current.add(key)
            true
        }
        pinnedProcesses = current
        return nowPinned
    }
}
