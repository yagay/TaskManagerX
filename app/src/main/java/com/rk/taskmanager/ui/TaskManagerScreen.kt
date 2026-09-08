package com.rk.taskmanager.ui

import android.graphics.drawable.Drawable
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import com.rk.taskmanager.MainViewModel
import com.rk.taskmanager.model.ProcessEntry
import com.rk.taskmanager.model.ProcessKind
import com.rk.taskmanager.model.ProcessSort
import com.rk.taskmanager.model.TaskManagerUiState
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaskManagerApp(viewModel: MainViewModel) {
    val state by viewModel.state.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    var selected by remember { mutableStateOf<ProcessEntry?>(null) }
    var showSettings by remember { mutableStateOf(false) }
    var pendingKill by remember { mutableStateOf<Pair<ProcessEntry, Boolean>?>(null) }

    LaunchedEffect(state.error) { state.error?.let { snackbar.showSnackbar(it); viewModel.clearError() } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Column { Text("TaskManagerX"); Text("${state.processCount} processes • ${state.threadCount} threads", style = MaterialTheme.typography.labelSmall) } },
                actions = {
                    IconButton(onClick = viewModel::refresh) { Icon(Icons.Default.Refresh, contentDescription = "Refresh") }
                    IconButton(onClick = { showSettings = true }) { Icon(Icons.Default.Settings, contentDescription = "Settings") }
                }
            )
        },
        floatingActionButton = { SystemInfoOverlay(viewModel) },
        snackbarHost = { SnackbarHost(snackbar) }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            StatusSection(state); SystemSection(state)
            FilterSection(state, viewModel::setQuery, viewModel::setSort, viewModel::setShowUserApps, viewModel::setShowSystemApps, viewModel::setShowLinuxProcesses)
            if (state.loading) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            else ProcessList(state) { selected = it }
        }
    }

    selected?.let { process ->
        ProcessDialog(process, state.processes.firstOrNull { it.pid == process.ppid }, { selected = null }, { selected = it }, { viewModel.togglePin(process) }, {
            if (state.confirmKill) pendingKill = process to false else { selected = null; viewModel.killProcess(process) }
        }, {
            if (state.confirmKill) pendingKill = process to true else { selected = null; viewModel.forceStop(process) }
        })
    }

    pendingKill?.let { (process, forceStop) ->
        AlertDialog(onDismissRequest = { pendingKill = null }, title = { Text(if (forceStop) "Force stop app?" else "Kill process?") }, text = { Text(process.displayName) }, confirmButton = {
            Button(onClick = { pendingKill = null; selected = null; if (forceStop) viewModel.forceStop(process) else viewModel.killProcess(process) }) { Text("Confirm") }
        }, dismissButton = { TextButton(onClick = { pendingKill = null }) { Text("Cancel") } })
    }

    if (showSettings) SettingsDialog(state, { showSettings = false }, viewModel::setAutoRefresh, viewModel::setRefreshInterval, viewModel::setConfirmKill)
}

@Composable private fun StatusSection(state: TaskManagerUiState) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        AssistChip(onClick = {}, label = { Text(if (state.root.granted) "Root: OK" else "Root: unavailable") })
        AssistChip(onClick = {}, label = { Text(if (state.framework.detected) "LSPosed: detected" else "LSPosed: not detected") })
    }
}

@Composable private fun SystemSection(state: TaskManagerUiState) {
    val s = state.system
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        MetricCard(Modifier.weight(1f), "CPU", String.format(Locale.getDefault(), "%.0f%%", s.cpuPercent), s.cpuPercent / 100f)
        MetricCard(Modifier.weight(1f), "RAM", "${formatBytes(s.ramUsedBytes)} / ${formatBytes(s.ramTotalBytes)}", if (s.ramTotalBytes > 0) s.ramUsedBytes.toFloat() / s.ramTotalBytes else 0f)
    }
    Text(buildString { if (s.swapTotalBytes > 0) append("SWAP ${formatBytes(s.swapUsedBytes)} / ${formatBytes(s.swapTotalBytes)} • "); append("Load ${String.format(Locale.getDefault(), "%.2f", s.load1)}") }, Modifier.padding(horizontal = 14.dp, vertical = 4.dp), style = MaterialTheme.typography.bodySmall)
}

@Composable private fun MetricCard(modifier: Modifier, title: String, value: String, progress: Float) { Card(modifier) { Column(Modifier.padding(12.dp)) { Text(title, style = MaterialTheme.typography.labelMedium); Spacer(Modifier.height(2.dp)); Text(value, fontWeight = FontWeight.SemiBold); Spacer(Modifier.height(8.dp)); LinearProgressIndicator(progress = { progress.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth()) } } }

@Composable private fun FilterSection(state: TaskManagerUiState, onQuery: (String) -> Unit, onSort: (ProcessSort) -> Unit, onUser: (Boolean) -> Unit, onSystem: (Boolean) -> Unit, onLinux: (Boolean) -> Unit) {
    Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
        OutlinedTextField(state.query, onQuery, Modifier.fillMaxWidth(), singleLine = true, leadingIcon = { Icon(Icons.Default.Search, null) }, trailingIcon = { if (state.query.isNotEmpty()) IconButton(onClick = { onQuery("") }) { Icon(Icons.Default.Close, "Clear") } }, placeholder = { Text("Process, app, package, PID, UID") })
        Spacer(Modifier.height(6.dp)); Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(state.showUserApps, { onUser(!state.showUserApps) }, label = { Text("User") }); FilterChip(state.showSystemApps, { onSystem(!state.showSystemApps) }, label = { Text("System") }); FilterChip(state.showLinuxProcesses, { onLinux(!state.showLinuxProcesses) }, label = { Text("Linux") })
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { ProcessSort.entries.forEach { item -> FilterChip(state.sort == item, { onSort(item) }, label = { Text(sortLabel(item)) }) } }
    }
}

@Composable private fun ProcessList(state: TaskManagerUiState, onClick: (ProcessEntry) -> Unit) {
    val filtered = remember(state.processes, state.query, state.sort, state.showUserApps, state.showSystemApps, state.showLinuxProcesses) {
        state.processes.asSequence().filter { when (it.kind) { ProcessKind.USER_APP -> state.showUserApps; ProcessKind.SYSTEM_APP -> state.showSystemApps; ProcessKind.LINUX -> state.showLinuxProcesses } }.filter { val q = state.query.trim(); q.isEmpty() || it.displayName.contains(q, true) || it.packageNames.any { p -> p.contains(q, true) } || it.command.contains(q, true) || it.userName.contains(q, true) || it.pid.toString() == q || it.uid.toString() == q }.let { seq -> when (state.sort) { ProcessSort.CPU -> seq.sortedByDescending { it.cpuPercent }; ProcessSort.MEMORY -> seq.sortedByDescending { it.rssKb }; ProcessSort.NAME -> seq.sortedBy { it.displayName.lowercase() }; ProcessSort.PID -> seq.sortedBy { it.pid } } }.sortedByDescending { it.isPinned }.toList()
    }
    LazyColumn(Modifier.fillMaxSize()) { items(filtered, key = { it.pid }) { p -> ProcessRow(p, onClick); HorizontalDivider() } }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable private fun ProcessRow(process: ProcessEntry, onClick: (ProcessEntry) -> Unit) {
    Row(Modifier.fillMaxWidth().combinedClickable(onClick = { onClick(process) }).padding(horizontal = 12.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
        AppIcon(process.icon); Spacer(Modifier.size(10.dp)); Column(Modifier.weight(1f)) { Row(verticalAlignment = Alignment.CenterVertically) { Text(process.displayName, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f)); if (process.isPinned) Icon(Icons.Default.PushPin, "Pinned", Modifier.size(15.dp)); if (process.isForeground) Text(" FG", style = MaterialTheme.typography.labelSmall) }; Text("PID ${process.pid}  ${process.userName}  ${kindLabel(process.kind)}  ${process.threads}T", style = MaterialTheme.typography.bodySmall, maxLines = 1) }; Column(horizontalAlignment = Alignment.End) { Text(String.format(Locale.getDefault(), "%.1f%%", process.cpuPercent)); Text(formatBytes(process.rssKb * 1024L), style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable private fun AppIcon(drawable: Drawable?) { if (drawable == null) { Box(Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) { Icon(Icons.Default.Memory, null) }; return }; val density = LocalDensity.current; val px = with(density) { 40.dp.roundToPx() }; val bitmap = remember(drawable, px) { drawable.toBitmap(max(1, px), max(1, px)).asImageBitmap() }; Image(bitmap, null, Modifier.size(40.dp).clip(RoundedCornerShape(10.dp))) }

@OptIn(ExperimentalFoundationApi::class)
@Composable private fun ProcessDialog(process: ProcessEntry, parent: ProcessEntry?, onDismiss: () -> Unit, onOpenParent: (ProcessEntry) -> Unit, onPin: () -> Unit, onKill: () -> Unit, onForceStop: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text(process.displayName) }, text = { LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        item { CopyDetail("PID", process.pid.toString()) }; if (process.ppid != 0) item { ParentDetail(process.ppid, parent, onOpenParent) }; item { CopyDetail("UID", process.uid.toString()) }; item { CopyDetail("User", process.userName) }; item { CopyDetail("CPU Usage", String.format(Locale.getDefault(), "%.1f%%", process.cpuPercent)) }; item { CopyDetail("RAM Usage", formatBytes(process.rssKb * 1024L)) }; if (process.virtualMemoryKb > 0) item { CopyDetail("Virtual Memory", formatBytes(process.virtualMemoryKb * 1024L)) }; item { CopyDetail("Foreground", if (process.isForeground) "Yes" else "No") }; item { CopyDetail("Threads", process.threads.toString()) }; item { CopyDetail("Nice Value", process.nice.toString()) }; item { CopyDetail("Status", process.state) }; item { CopyDetail("Start Time", formatStartTime(process.startTimeMillis)) }; item { CopyDetail("Elapsed Time", formatDuration(process.elapsedTimeMillis)) }; process.executablePath?.let { item { CopyDetail("Executable Path", it) } }; process.cgroup?.let { item { CopyDetail("Cgroup", it) } }; if (process.packageNames.isNotEmpty()) item { CopyDetail("Package", process.packageNames.joinToString("\n")) }; item { CopyDetail("Command", process.command) }; process.oomScoreAdj?.let { item { CopyDetail("OOM score adj", it.toString()) } }
    } }, confirmButton = { Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { TextButton(onClick = onPin) { Text(if (process.isPinned) "Unpin" else "Pin") }; if (process.packageName != null) Button(onClick = onForceStop) { Text("Force stop") }; Button(onClick = onKill) { Text("Kill PID") } } }, dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } })
}

@OptIn(ExperimentalFoundationApi::class)
@Composable private fun CopyDetail(label: String, value: String) { val clipboard = LocalClipboardManager.current; val context = LocalContext.current; Row(Modifier.fillMaxWidth().combinedClickable(onClick = {}, onLongClick = { clipboard.setText(AnnotatedString(value)); Toast.makeText(context, "$label copied", Toast.LENGTH_SHORT).show() }).padding(vertical = 3.dp), horizontalArrangement = Arrangement.SpaceBetween) { Text(label, style = MaterialTheme.typography.bodySmall); Text(value, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis) } }

@OptIn(ExperimentalFoundationApi::class)
@Composable private fun ParentDetail(ppid: Int, parent: ProcessEntry?, onOpenParent: (ProcessEntry) -> Unit) { val clipboard = LocalClipboardManager.current; val context = LocalContext.current; Row(Modifier.fillMaxWidth().combinedClickable(onClick = { parent?.let(onOpenParent) }, onLongClick = { clipboard.setText(AnnotatedString(ppid.toString())); Toast.makeText(context, "Parent PID copied", Toast.LENGTH_SHORT).show() }).padding(vertical = 3.dp), horizontalArrangement = Arrangement.SpaceBetween) { Text("Parent PID", style = MaterialTheme.typography.bodySmall); Text(if (parent != null) "$ppid ›" else "$ppid (not found)", style = MaterialTheme.typography.bodySmall) } }

@Composable private fun SettingsDialog(state: TaskManagerUiState, onDismiss: () -> Unit, onAutoRefresh: (Boolean) -> Unit, onRefreshInterval: (Long) -> Unit, onConfirmKill: (Boolean) -> Unit) { AlertDialog(onDismissRequest = onDismiss, title = { Text("Process settings") }, text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) { ToggleRow("Auto refresh", state.autoRefresh, onAutoRefresh); ToggleRow("Confirm before kill", state.confirmKill, onConfirmKill); Text("Refresh interval", style = MaterialTheme.typography.labelLarge); Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { listOf(500L, 800L, 1000L, 2000L).forEach { v -> FilterChip(state.refreshIntervalMs == v, { onRefreshInterval(v) }, label = { Text("${v}ms") }) } } } }, confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } }) }
@Composable private fun ToggleRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) { Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) { Text(label); Switch(checked, onCheckedChange) } }
private fun sortLabel(sort: ProcessSort) = when (sort) { ProcessSort.MEMORY -> "RAM"; ProcessSort.CPU -> "CPU"; ProcessSort.NAME -> "A-Z"; ProcessSort.PID -> "PID" }
private fun kindLabel(kind: ProcessKind) = when (kind) { ProcessKind.USER_APP -> "User"; ProcessKind.SYSTEM_APP -> "System"; ProcessKind.LINUX -> "Linux" }
private fun formatStartTime(ms: Long) = if (ms <= 0) "Unknown" else DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM).format(Date(ms))
private fun formatDuration(ms: Long): String { var s = (ms / 1000).coerceAtLeast(0); val d = s / 86400; s %= 86400; val h = s / 3600; s %= 3600; val m = s / 60; val sec = s % 60; return buildString { if (d > 0) append("${d}d "); if (h > 0 || d > 0) append("${h}h "); if (m > 0 || h > 0 || d > 0) append("${m}m "); append("${sec}s") } }
private fun formatBytes(bytes: Long): String { if (bytes < 1024) return "$bytes B"; val u = arrayOf("KB", "MB", "GB", "TB"); var v = bytes.toDouble(); var i = -1; while (v >= 1024 && i < u.lastIndex) { v /= 1024; i++ }; return String.format(Locale.getDefault(), "%.1f %s", v, u[i]) }
