package com.rk.taskmanager

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.rk.taskmanager.data.FrameworkRepository
import com.rk.taskmanager.data.ProcessRepository
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

    private val _state = MutableStateFlow(TaskManagerUiState())
    val state: StateFlow<TaskManagerUiState> = _state.asStateFlow()

    private var monitorJob: Job? = null

    init {
        start()
    }

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

            while (isActive) {
                refreshInternal()
                delay(1_000)
            }
        }
    }

    fun refresh() {
        viewModelScope.launch {
            if (_state.value.root.granted) refreshInternal()
            else start()
        }
    }

    fun setQuery(query: String) {
        _state.value = _state.value.copy(query = query)
    }

    fun setSort(sort: ProcessSort) {
        _state.value = _state.value.copy(sort = sort)
    }

    fun killProcess(process: ProcessEntry) {
        viewModelScope.launch {
            val ok = processRepository.killProcess(process.pid)
            if (!ok) {
                _state.value = _state.value.copy(error = "Failed to kill PID ${process.pid}")
            }
            refreshInternal()
        }
    }

    fun forceStop(process: ProcessEntry) {
        val pkg = process.packageName ?: return
        viewModelScope.launch {
            val ok = processRepository.forceStop(pkg)
            if (!ok) {
                _state.value = _state.value.copy(error = "Failed to force-stop $pkg")
            }
            refreshInternal()
        }
    }

    fun clearError() {
        _state.value = _state.value.copy(error = null)
    }

    private suspend fun refreshInternal() {
        runCatching {
            val system = statsRepository.read()
            val processes = processRepository.listProcesses()
            _state.value = _state.value.copy(
                system = system,
                processes = processes,
                loading = false,
                error = null
            )
        }.onFailure {
            _state.value = _state.value.copy(
                loading = false,
                error = it.message ?: it.javaClass.simpleName
            )
        }
    }

    override fun onCleared() {
        monitorJob?.cancel()
        shell.close()
        super.onCleared()
    }
}
