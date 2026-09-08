package com.rk.taskmanager.ui

import android.graphics.drawable.Drawable
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import com.rk.taskmanager.MainViewModel
import com.rk.taskmanager.model.ProcessEntry
import com.rk.taskmanager.model.ProcessFilter
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

    LaunchedEffect(state.error) {
        state.error?.let {
            snackbar.showSnackbar(it)
            viewModel.clearError()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("TaskManagerX") },
                actions = {
                    IconButton(onClick = viewModel::refresh) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbar) }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
        ) {
            StatusSection(state)
            SystemSection(state)
            FilterSection(
                query = state.query,
                sort = state.sort,
                filter = state.filter,
                onQuery = viewModel::setQuery,
                onSort = viewModel::setSort,
                onFilter = viewModel::setFilter,
            )

            if (state.loading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else {
                ProcessList(state = state, onClick = { selected = it })
            }
        }
    }

    selected?.let { process ->
        ProcessDialog(
            process = process,
            onDismiss = { selected = null },
            onKill = {
                selected = null
                viewModel.killProcess(process)
            },
            onForceStop = {
                selected = null
                viewModel.forceStop(process)
            }
        )
    }
}

@Composable
private fun StatusSection(state: TaskManagerUiState) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        AssistChip(onClick = {}, label = {
            Text(if (state.root.granted) "Root: OK" else "Root: unavailable")
        })
        AssistChip(onClick = {}, label = {
            Text(if (state.framework.detected) "LSPosed: detected" else "LSPosed: not detected")
        })
    }
}

@Composable
private fun SystemSection(state: TaskManagerUiState) {
    val s = state.system
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        MetricCard(
            modifier = Modifier.weight(1f),
            title = "CPU",
            value = String.format(Locale.getDefault(), "%.0f%%", s.cpuPercent),
            progress = s.cpuPercent / 100f
        )
        MetricCard(
            modifier = Modifier.weight(1f),
            title = "RAM",
            value = "${formatBytes(s.ramUsedBytes)} / ${formatBytes(s.ramTotalBytes)}",
            progress = if (s.ramTotalBytes > 0) {
                s.ramUsedBytes.toFloat() / s.ramTotalBytes.toFloat()
            } else 0f
        )
    }
    if (s.swapTotalBytes > 0L) {
        Text(
            "SWAP ${formatBytes(s.swapUsedBytes)} / ${formatBytes(s.swapTotalBytes)}  •  Load ${String.format(Locale.getDefault(), "%.2f", s.load1)}",
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp),
            style = MaterialTheme.typography.bodySmall
        )
    }
}

@Composable
private fun MetricCard(modifier: Modifier, title: String, value: String, progress: Float) {
    Card(modifier) {
        Column(Modifier.padding(12.dp)) {
            Text(title, style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.height(2.dp))
            Text(value, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = { progress.coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun FilterSection(
    query: String,
    sort: ProcessSort,
    filter: ProcessFilter,
    onQuery: (String) -> Unit,
    onSort: (ProcessSort) -> Unit,
    onFilter: (ProcessFilter) -> Unit,
) {
    Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
        OutlinedTextField(
            value = query,
            onValueChange = onQuery,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { onQuery("") }) {
                        Icon(Icons.Default.Close, contentDescription = "Clear")
                    }
                }
            },
            placeholder = { Text("Process, app, package, PID, UID") }
        )
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ProcessFilter.entries.forEach { item ->
                FilterChip(
                    selected = filter == item,
                    onClick = { onFilter(item) },
                    label = { Text(filterLabel(item)) }
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ProcessSort.entries.forEach { item ->
                FilterChip(
                    selected = sort == item,
                    onClick = { onSort(item) },
                    label = { Text(item.name) }
                )
            }
        }
    }
}

@Composable
private fun ProcessList(state: TaskManagerUiState, onClick: (ProcessEntry) -> Unit) {
    val filtered = remember(state.processes, state.query, state.sort, state.filter) {
        state.processes
            .asSequence()
            .filter {
                when (state.filter) {
                    ProcessFilter.ALL -> true
                    ProcessFilter.USER_APPS -> it.kind == ProcessKind.USER_APP
                    ProcessFilter.SYSTEM_APPS -> it.kind == ProcessKind.SYSTEM_APP
                    ProcessFilter.LINUX -> it.kind == ProcessKind.LINUX
                }
            }
            .filter {
                val q = state.query.trim()
                q.isEmpty() ||
                    it.displayName.contains(q, true) ||
                    it.packageNames.any { pkg -> pkg.contains(q, true) } ||
                    it.command.contains(q, true) ||
                    it.userName.contains(q, true) ||
                    it.pid.toString() == q ||
                    it.uid.toString() == q
            }
            .let { sequence ->
                when (state.sort) {
                    ProcessSort.CPU -> sequence.sortedByDescending { it.cpuPercent }
                    ProcessSort.MEMORY -> sequence.sortedByDescending { it.rssKb }
                    ProcessSort.NAME -> sequence.sortedBy { it.displayName.lowercase() }
                    ProcessSort.PID -> sequence.sortedBy { it.pid }
                }
            }
            .toList()
    }

    LazyColumn(Modifier.fillMaxSize()) {
        items(filtered, key = { it.pid }) { process ->
            ProcessRow(process, onClick)
            HorizontalDivider()
        }
    }
}

@Composable
private fun ProcessRow(process: ProcessEntry, onClick: (ProcessEntry) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick(process) }
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AppIcon(process.icon)
        Spacer(Modifier.size(10.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    process.displayName,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                if (process.isForeground) {
                    Text("FG", style = MaterialTheme.typography.labelSmall)
                }
            }
            Text(
                "PID ${process.pid}  ${process.userName}  ${kindLabel(process.kind)}  ${process.threads}T",
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(
                String.format(Locale.getDefault(), "%.1f%%", process.cpuPercent),
                style = MaterialTheme.typography.bodyMedium
            )
            Text(formatBytes(process.rssKb * 1024L), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun AppIcon(drawable: Drawable?) {
    if (drawable == null) {
        Box(
            modifier = Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Default.Memory, contentDescription = null)
        }
        return
    }

    val density = LocalDensity.current
    val px = with(density) { 40.dp.roundToPx() }
    val bitmap = remember(drawable, px) {
        drawable.toBitmap(width = max(1, px), height = max(1, px)).asImageBitmap()
    }
    Image(
        bitmap = bitmap,
        contentDescription = null,
        modifier = Modifier.size(40.dp).clip(RoundedCornerShape(10.dp))
    )
}

@Composable
private fun ProcessDialog(
    process: ProcessEntry,
    onDismiss: () -> Unit,
    onKill: () -> Unit,
    onForceStop: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(process.displayName) },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                item { Detail("PID", process.pid.toString()) }
                item { Detail("Parent PID", process.ppid.toString()) }
                item { Detail("UID", process.uid.toString()) }
                item { Detail("User", process.userName) }
                item { Detail("CPU Usage", String.format(Locale.getDefault(), "%.1f%%", process.cpuPercent)) }
                item { Detail("Actual RAM (RSS)", formatBytes(process.rssKb * 1024L)) }
                item { Detail("Foreground", if (process.isForeground) "Yes" else "No") }
                item { Detail("Threads", process.threads.toString()) }
                item { Detail("Nice Value", process.nice.toString()) }
                item { Detail("Status", process.state) }
                item { Detail("Start Time", formatStartTime(process.startTimeMillis)) }
                item { Detail("Elapsed Time", formatDuration(process.elapsedTimeMillis)) }
                process.executablePath?.let { path -> item { Detail("Executable Path", path) } }
                if (process.packageNames.isNotEmpty()) {
                    item { Detail("Package", process.packageNames.joinToString("\n")) }
                }
                item { Detail("Command", process.command) }
                process.oomScoreAdj?.let { value -> item { Detail("OOM score adj", value.toString()) } }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (process.packageName != null) {
                    Button(onClick = onForceStop) { Text("Force stop") }
                }
                Button(onClick = onKill) { Text("Kill PID") }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        }
    )
}

@Composable
private fun Detail(label: String, value: String) {
    Text("$label: $value", style = MaterialTheme.typography.bodyMedium)
}

private fun filterLabel(filter: ProcessFilter): String = when (filter) {
    ProcessFilter.ALL -> "All"
    ProcessFilter.USER_APPS -> "User"
    ProcessFilter.SYSTEM_APPS -> "System"
    ProcessFilter.LINUX -> "Linux"
}

private fun kindLabel(kind: ProcessKind): String = when (kind) {
    ProcessKind.USER_APP -> "User"
    ProcessKind.SYSTEM_APP -> "System"
    ProcessKind.LINUX -> "Linux"
}

private fun formatStartTime(timeMs: Long): String {
    if (timeMs <= 0L) return "Unknown"
    return DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM).format(Date(timeMs))
}

private fun formatDuration(ms: Long): String {
    var seconds = (ms / 1000L).coerceAtLeast(0L)
    val days = seconds / 86400L
    seconds %= 86400L
    val hours = seconds / 3600L
    seconds %= 3600L
    val minutes = seconds / 60L
    val secs = seconds % 60L
    return buildString {
        if (days > 0) append("${days}d ")
        if (hours > 0 || days > 0) append("${hours}h ")
        if (minutes > 0 || hours > 0 || days > 0) append("${minutes}m ")
        append("${secs}s")
    }
}

private fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val units = arrayOf("KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var unit = -1
    while (value >= 1024 && unit < units.lastIndex) {
        value /= 1024.0
        unit++
    }
    return String.format(Locale.getDefault(), "%.1f %s", value, units[unit])
}
