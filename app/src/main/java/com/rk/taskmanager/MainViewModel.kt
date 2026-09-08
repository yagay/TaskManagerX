package com.rk.taskmanager

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.rk.taskmanager.data.FrameworkRepository
import com.rk.taskmanager.data.ProcessRepository
import com.rk.taskmanager.data.SettingsRepository
import com.rk.taskmanager.data.SystemStatsRepository
import com.rk.taskmanager.model.ProcessEntry
import com.rk.taskmanager.model.ProcessSort
import com.rk.taskmanager.model.RootState
import com.rk.taskmanager.model.TaskManagerUiState
import com.rk.taskmanager.root.RootShell
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val shell = RootShell()
    private val processRepository = ProcessRepository(application, shell)
    private val statsRepository = SystemStatsRepository(shell)
    private val frameworkRepository = FrameworkRepository(shell)
    private val settings = SettingsRepository(application)

    private val _state = MutableStateFlow(
        TaskManagerUiState(
            sort = settings.sort,
            showUserApps = settings.showUserApps,
            showSystemApps = settings.showSystemApps,
            showLinuxProcesses = settings.showLinuxProcesses,
            autoRefresh = settings.autoRefresh,
            refreshIntervalMs = settings.refreshIntervalMs,
            confirmKill = settings.confirmKill,
        )
    )
    val state: StateFlow<TaskManagerUiState> = _state.asStateFlow()

    private var monitorJob: Job? = null

    init { start() }

    fun start() {
        if (monitorJob?.isActive == true) return
        monitorJob = viewModelScope.launch {
            val rootGranted = shell.isRootAvailable()
            _state.value = _state.value.copy(
                root = RootState(
                    checked = true,
                    granted = rootGranted,
                    uid = if (rootGranted) 0 else null,
                    message = if (rootGranted) "Root granted" else "Root unavailable or denied"
                ),
                loading = rootGranted,
                error = if (rootGranted) null else "Root permission is required"
            )
            if (!rootGranted) return@launch

            _state.value = _state.value.copy(framework = frameworkRepository.detect())
            refreshInternal()
            while (isActive) {
                val snapshot = _state.value
                if (snapshot.autoRefresh) {
                    delay(snapshot.refreshIntervalMs)
                    if (isActive && _state.value.autoRefresh) refreshInternal()
                } else delay(250L)
            }
        }
    }

    fun refresh() { viewModelScope.launch { if (_state.value.root.granted) refreshInternal() else start() } }
    fun setQuery(query: String) { _state.value = _state.value.copy(query = query) }
    fun setSort(sort: ProcessSort) { settings.sort = sort; _state.value = _state.value.copy(sort = sort) }
    fun setShowUserApps(value: Boolean) { settings.showUserApps = value; _state.value = _state.value.copy(showUserApps = value) }
    fun setShowSystemApps(value: Boolean) { settings.showSystemApps = value; _state.value = _state.value.copy(showSystemApps = value) }
    fun setShowLinuxProcesses(value: Boolean) { settings.showLinuxProcesses = value; _state.value = _state.value.copy(showLinuxProcesses = value) }
    fun setAutoRefresh(value: Boolean) { settings.autoRefresh = value; _state.value = _state.value.copy(autoRefresh = value) }
    fun setRefreshInterval(ms: Long) { settings.refreshIntervalMs = ms; _state.value = _state.value.copy(refreshIntervalMs = settings.refreshIntervalMs) }
    fun setConfirmKill(value: Boolean) { settings.confirmKill = value; _state.value = _state.value.copy(confirmKill = value) }

    fun togglePin(process: ProcessEntry) {
        val key = pinKey(process)
        val pinned = settings.togglePinned(key)
        _state.value = _state.value.copy(
            processes = _state.value.processes.map { if (it.pid == process.pid) it.copy(isPinned = pinned) else it }
        )
    }

    fun killProcess(process: ProcessEntry) {
        viewModelScope.launch {
            val ok = processRepository.killProcess(process.pid)
            if (!ok) _state.value = _state.value.copy(error = "Failed to kill PID ${process.pid}")
            refreshInternal()
        }
    }

    fun forceStop(process: ProcessEntry) {
        val pkg = process.packageName ?: return
        viewModelScope.launch {
            val ok = processRepository.forceStop(pkg)
            if (!ok) _state.value = _state.value.copy(error = "Failed to force-stop $pkg")
            refreshInternal()
        }
    }

    fun clearError() { _state.value = _state.value.copy(error = null) }

    private suspend fun refreshInternal() {
        runCatching {
            val system = statsRepository.read()
            val pinned = settings.pinnedProcesses
            val processes = processRepository.listProcesses().map { process ->
                process.copy(isPinned = pinKey(process) in pinned)
            }
            val old = _state.value
            val ramPercent = percent(system.ramUsedBytes, system.ramTotalBytes)
            val swapPercent = percent(system.swapUsedBytes, system.swapTotalBytes)
            _state.value = old.copy(
                system = system,
                processes = processes,
                processCount = processes.size,
                threadCount = processes.sumOf { it.threads },
                cpuHistory = appendHistory(old.cpuHistory, system.cpuPercent),
                ramHistory = appendHistory(old.ramHistory, ramPercent),
                swapHistory = appendHistory(old.swapHistory, swapPercent),
                loading = false,
                error = null
            )
        }.onFailure {
            _state.value = _state.value.copy(loading = false, error = it.message ?: it.javaClass.simpleName)
        }
    }

    private fun appendHistory(values: List<Float>, value: Float): List<Float> = (values + value).takeLast(60)
    private fun percent(used: Long, total: Long): Float = if (total > 0L) (used * 100f / total).coerceIn(0f, 100f) else 0f
    private fun pinKey(process: ProcessEntry): String = process.packageName ?: process.command.ifBlank { "pid:${process.pid}" }

    override fun onCleared() {
        monitorJob?.cancel()
        shell.close()
        super.onCleared()
    }
}
