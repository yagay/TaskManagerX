package com.rk.taskmanager.ui

import android.graphics.drawable.Drawable
import android.widget.Toast
import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import com.rk.taskmanager.MainViewModel
import com.rk.taskmanager.model.CpuCoreInfo
import com.rk.taskmanager.model.NetworkEntry
import com.rk.taskmanager.model.ProcessEntry
import com.rk.taskmanager.model.ProcessKind
import com.rk.taskmanager.model.ProcessSort
import com.rk.taskmanager.model.TaskManagerUiState
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max

enum class HomePage { PROCESSES, RESOURCES, NETWORK }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaskManagerApp(viewModel: MainViewModel) {
    val state by viewModel.state.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    var selected by remember { mutableStateOf<ProcessEntry?>(null) }
    var showSettings by remember { mutableStateOf(false) }
    var pendingKill by remember { mutableStateOf<Pair<ProcessEntry, Boolean>?>(null) }
    var page by remember { mutableStateOf(HomePage.PROCESSES) }

    LaunchedEffect(state.error) {
        state.error?.let {
            snackbar.showSnackbar(it)
            viewModel.clearError()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("TaskManagerX")
                        Text(
                            when (page) {
                                HomePage.PROCESSES -> "${state.processCount} processes • ${state.threadCount} threads"
                                HomePage.RESOURCES -> "CPU • RAM • GPU"
                                HomePage.NETWORK -> "↓ ${formatSpeed(state.network.totalRxBytesPerSecond)}  ↑ ${formatSpeed(state.network.totalTxBytesPerSecond)}"
                            },
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                },
                actions = {
                    IconButton(onClick = viewModel::refresh) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                    }
                    IconButton(onClick = { showSettings = true }) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize(),
        ) {
            PageSelector(page) { page = it }
            when (page) {
                HomePage.PROCESSES -> ProcessPage(
                    state = state,
                    viewModel = viewModel,
                    onSelect = { process ->
                        viewModel.loadProcessDetails(process) { selected = it }
                    },
                )
                HomePage.RESOURCES -> ResourcePage(state)
                HomePage.NETWORK -> NetworkPage(state)
            }
        }
    }

    selected?.let { process ->
        ProcessDialog(
            process = process,
            parent = state.processes.firstOrNull { it.pid == process.ppid },
            onDismiss = { selected = null },
            onOpenParent = { parent ->
                viewModel.loadProcessDetails(parent) { selected = it }
            },
            onPin = { viewModel.togglePin(process) },
            onKill = {
                if (state.confirmKill) pendingKill = process to false
                else {
                    selected = null
                    viewModel.killProcess(process)
                }
            },
            onForceStop = {
                if (state.confirmKill) pendingKill = process to true
                else {
                    selected = null
                    viewModel.forceStop(process)
                }
            },
        )
    }

    pendingKill?.let { (process, forceStop) ->
        AlertDialog(
            onDismissRequest = { pendingKill = null },
            title = { Text(if (forceStop) "Force stop app?" else "Kill process?") },
            text = { Text(process.displayName) },
            confirmButton = {
                Button(onClick = {
                    pendingKill = null
                    selected = null
                    if (forceStop) viewModel.forceStop(process) else viewModel.killProcess(process)
                }) { Text("Confirm") }
            },
            dismissButton = {
                TextButton(onClick = { pendingKill = null }) { Text("Cancel") }
            },
        )
    }

    if (showSettings) {
        SettingsDialog(
            state = state,
            onDismiss = { showSettings = false },
            onAutoRefresh = viewModel::setAutoRefresh,
            onRefreshInterval = viewModel::setRefreshInterval,
            onConfirmKill = viewModel::setConfirmKill,
        )
    }
}

@Composable
private fun PageSelector(page: HomePage, onPage: (HomePage) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        HomePage.entries.forEach { item ->
            FilterChip(
                selected = page == item,
                onClick = { onPage(item) },
                label = {
                    Text(
                        when (item) {
                            HomePage.PROCESSES -> "Processes"
                            HomePage.RESOURCES -> "Resources"
                            HomePage.NETWORK -> "Network"
                        }
                    )
                },
            )
        }
    }
}

@Composable
private fun ProcessPage(
    state: TaskManagerUiState,
    viewModel: MainViewModel,
    onSelect: (ProcessEntry) -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        StatusSection(state)
        FilterSection(
            state = state,
            onQuery = viewModel::setQuery,
            onSort = viewModel::setSort,
            onUser = viewModel::setShowUserApps,
            onSystem = viewModel::setShowSystemApps,
            onLinux = viewModel::setShowLinuxProcesses,
        )
        if (state.loading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else {
            ProcessList(state, onSelect)
        }
    }
}

@Composable
private fun StatusSection(state: TaskManagerUiState) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        AssistChip(onClick = {}, label = { Text(if (state.root.granted) "Root: OK" else "Root: unavailable") })
        AssistChip(onClick = {}, label = { Text(if (state.framework.detected) "LSPosed: detected" else "LSPosed: not detected") })
        if (state.network.backend != "Not sampled") {
            AssistChip(onClick = {}, label = { Text(state.network.backend) })
        }
    }
}

@Composable
private fun FilterSection(
    state: TaskManagerUiState,
    onQuery: (String) -> Unit,
    onSort: (ProcessSort) -> Unit,
    onUser: (Boolean) -> Unit,
    onSystem: (Boolean) -> Unit,
    onLinux: (Boolean) -> Unit,
) {
    Column(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
        OutlinedTextField(
            value = state.query,
            onValueChange = onQuery,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            trailingIcon = {
                if (state.query.isNotEmpty()) {
                    IconButton(onClick = { onQuery("") }) {
                        Icon(Icons.Default.Close, contentDescription = "Clear")
                    }
                }
            },
            placeholder = { Text("Process, app, package, PID, UID") },
        )
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(state.showUserApps, { onUser(!state.showUserApps) }, label = { Text("User") })
            FilterChip(state.showSystemApps, { onSystem(!state.showSystemApps) }, label = { Text("System") })
            FilterChip(state.showLinuxProcesses, { onLinux(!state.showLinuxProcesses) }, label = { Text("Linux") })
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ProcessSort.entries.forEach { item ->
                FilterChip(
                    selected = state.sort == item,
                    onClick = { onSort(item) },
                    label = { Text(sortLabel(item)) },
                )
            }
        }
    }
}

@Composable
private fun ProcessList(state: TaskManagerUiState, onClick: (ProcessEntry) -> Unit) {
    val filtered = remember(
        state.processes,
        state.query,
        state.sort,
        state.showUserApps,
        state.showSystemApps,
        state.showLinuxProcesses,
    ) {
        state.processes
            .asSequence()
            .filter {
                when (it.kind) {
                    ProcessKind.USER_APP -> state.showUserApps
                    ProcessKind.SYSTEM_APP -> state.showSystemApps
                    ProcessKind.LINUX -> state.showLinuxProcesses
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
            .let { seq ->
                when (state.sort) {
                    ProcessSort.CPU -> seq.sortedByDescending { it.cpuPercent }
                    ProcessSort.MEMORY -> seq.sortedByDescending { it.rssKb }
                    ProcessSort.NETWORK -> seq.sortedByDescending { it.totalNetworkBytesPerSecond }
                    ProcessSort.NAME -> seq.sortedBy { it.displayName.lowercase() }
                    ProcessSort.PID -> seq.sortedBy { it.pid }
                }
            }
            .sortedByDescending { it.isPinned }
            .toList()
    }

    LazyColumn(Modifier.fillMaxSize()) {
        items(filtered, key = { it.pid }) { process ->
            ProcessRow(process, onClick)
            HorizontalDivider()
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ProcessRow(process: ProcessEntry, onClick: (ProcessEntry) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = { onClick(process) })
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
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
                    modifier = Modifier.weight(1f),
                )
                if (process.isPinned) {
                    Icon(Icons.Default.PushPin, contentDescription = "Pinned", modifier = Modifier.size(15.dp))
                }
                if (process.isForeground) Text(" FG", style = MaterialTheme.typography.labelSmall)
            }
            Text(
                "PID ${process.pid}  ${process.userName}  ${kindLabel(process.kind)}  ${process.threads}T",
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (process.totalNetworkBytesPerSecond > 0L) {
                Text(
                    "↓ ${formatSpeed(process.rxBytesPerSecond)}    ↑ ${formatSpeed(process.txBytesPerSecond)}",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(String.format(Locale.getDefault(), "%.1f%%", process.cpuPercent))
            Text(formatBytes(process.rssKb * 1024L), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun ResourcePage(state: TaskManagerUiState) {
    val s = state.system
    val g = state.gpu
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            SectionCard("CPU") {
                MetricLine("Usage", String.format(Locale.getDefault(), "%.1f%%", s.cpuPercent))
                HistoryChart(state.cpuHistory)
            }
        }
        item {
            SectionCard("RAM") {
                MetricLine("Used", "${formatBytes(s.ramUsedBytes)} / ${formatBytes(s.ramTotalBytes)}")
                MetricLine("Available", formatBytes(s.ramAvailableBytes))
                MetricLine("Cached", formatBytes(s.cachedBytes))
                MetricLine("Buffers", formatBytes(s.buffersBytes))
                HistoryChart(state.ramHistory)
            }
        }
        item {
            SectionCard("SWAP") {
                MetricLine("Used", "${formatBytes(s.swapUsedBytes)} / ${formatBytes(s.swapTotalBytes)}")
                HistoryChart(state.swapHistory)
            }
        }
        item {
            SectionCard("Processor information") {
                MetricLine("SoC", s.soc.ifBlank { "Unknown" })
                MetricLine("Architecture", s.architecture.ifBlank { "Unknown" })
                MetricLine("ABI", s.abi.ifBlank { "Unknown" })
                MetricLine("CPU cores", s.cpuCoreCount.toString())
                MetricLine("Governor", s.governor.ifBlank { "Unknown" })
                MetricLine("Temperature", s.cpuTemperatureC?.let { String.format(Locale.US, "%.1f °C", it) } ?: "No data")
                MetricLine("Uptime", formatDuration(s.uptimeMillis))
                MetricLine("Load", String.format(Locale.US, "%.2f", s.load1))
                MetricLine("Processes", state.processCount.toString())
                MetricLine("Threads", state.threadCount.toString())
            }
        }
        item {
            SectionCard("CPU frequencies") {
                if (s.cpuCores.isEmpty()) Text("No cpufreq data", style = MaterialTheme.typography.bodySmall)
                else s.cpuCores.forEach { CpuCoreRow(it) }
            }
        }
        item {
            SectionCard("GPU") {
                MetricLine("Usage", g.usagePercent?.let { String.format(Locale.US, "%.1f%%", it) } ?: "No data")
                HistoryChart(state.gpuHistory)
                MetricLine("Vendor", g.vendor ?: "No data")
                MetricLine("Renderer", g.renderer ?: "No data")
                MetricLine("OpenGL", g.openGlVersion ?: "No data")
                MetricLine("GLSL", g.glslVersion ?: "No data")
                MetricLine("Vulkan", if (g.vulkanSupported) "Supported" else "Not supported")
                MetricLine("Vulkan API", g.vulkanApiVersion ?: "No data")
                MetricLine("GPU current", formatHz(g.currentHz))
                MetricLine("GPU min", formatHz(g.minHz))
                MetricLine("GPU max", formatHz(g.maxHz))
            }
        }
        item { Spacer(Modifier.height(12.dp)) }
    }
}

@Composable
private fun NetworkPage(state: TaskManagerUiState) {
    val network = state.network
    Column(Modifier.fillMaxSize()) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp),
        ) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Live app network", style = MaterialTheme.typography.titleMedium)
                Text(
                    "↓ ${formatSpeed(network.totalRxBytesPerSecond)}    ↑ ${formatSpeed(network.totalTxBytesPerSecond)}",
                    fontWeight = FontWeight.SemiBold,
                )
                Text("Backend: ${network.backend}", style = MaterialTheme.typography.bodySmall)
                Text("Network sampling is independent from process refresh.", style = MaterialTheme.typography.bodySmall)
            }
        }
        if (network.entries.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No active app traffic yet")
            }
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                items(network.entries, key = { it.uid }) { entry ->
                    NetworkRow(entry)
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun NetworkRow(entry: NetworkEntry) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppIcon(entry.icon)
        Spacer(Modifier.size(10.dp))
        Column(Modifier.weight(1f)) {
            Text(entry.label, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                buildString {
                    append("UID ${entry.uid}")
                    if (entry.system) append(" • System")
                    if (entry.packageNames.isNotEmpty()) {
                        append(" • ")
                        append(entry.packageNames.joinToString(", "))
                    }
                },
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            Text("↓ ${formatSpeed(entry.rxBytesPerSecond)}")
            Text("↑ ${formatSpeed(entry.txBytesPerSecond)}", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable Column.() -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
private fun MetricLine(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top,
    ) {
        Text(label, style = MaterialTheme.typography.bodySmall)
        Text(value, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun CpuCoreRow(core: CpuCoreInfo) {
    Column(Modifier.fillMaxWidth()) {
        Text("CPU ${core.core}", style = MaterialTheme.typography.labelMedium)
        Text(
            "${formatKHz(core.minKHz)} / ${formatKHz(core.currentKHz)} / ${formatKHz(core.maxKHz)}   min/current/max",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun HistoryChart(values: List<Float>) {
    val lineColor = MaterialTheme.colorScheme.primary
    val guideColor = MaterialTheme.colorScheme.outlineVariant
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(72.dp),
    ) {
        drawRect(guideColor, style = Stroke(width = 1f))
        if (values.size < 2) return@Canvas
        val step = size.width / (values.size - 1).coerceAtLeast(1)
        var previousX = 0f
        var previousY = size.height * (1f - (values.first().coerceIn(0f, 100f) / 100f))
        values.drop(1).forEachIndexed { index, value ->
            val x = step * (index + 1)
            val y = size.height * (1f - (value.coerceIn(0f, 100f) / 100f))
            drawLine(lineColor, start = androidx.compose.ui.geometry.Offset(previousX, previousY), end = androidx.compose.ui.geometry.Offset(x, y), strokeWidth = 3f)
            previousX = x
            previousY = y
        }
    }
}

@Composable
private fun AppIcon(drawable: Drawable?) {
    if (drawable == null) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(10.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Default.Memory, contentDescription = null)
        }
        return
    }
    val density = LocalDensity.current
    val px = with(density) { 40.dp.roundToPx() }
    val bitmap = remember(drawable, px) {
        drawable.toBitmap(max(1, px), max(1, px)).asImageBitmap()
    }
    Image(
        bitmap = bitmap,
        contentDescription = null,
        modifier = Modifier
            .size(40.dp)
            .clip(RoundedCornerShape(10.dp)),
    )
}

@Composable
private fun ProcessDialog(
    process: ProcessEntry,
    parent: ProcessEntry?,
    onDismiss: () -> Unit,
    onOpenParent: (ProcessEntry) -> Unit,
    onPin: () -> Unit,
    onKill: () -> Unit,
    onForceStop: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(process.displayName) },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                item { CopyDetail("PID", process.pid.toString()) }
                if (process.ppid != 0) item { ParentDetail(process.ppid, parent, onOpenParent) }
                item { CopyDetail("UID", process.uid.toString()) }
                item { CopyDetail("User", process.userName) }
                item { CopyDetail("CPU Usage", String.format(Locale.getDefault(), "%.1f%%", process.cpuPercent)) }
                item { CopyDetail("RAM Usage", formatBytes(process.rssKb * 1024L)) }
                item { CopyDetail("Download", formatSpeed(process.rxBytesPerSecond)) }
                item { CopyDetail("Upload", formatSpeed(process.txBytesPerSecond)) }
                if (process.virtualMemoryKb > 0L) item { CopyDetail("Virtual Memory", formatBytes(process.virtualMemoryKb * 1024L)) }
                item { CopyDetail("Foreground", if (process.isForeground) "Yes" else "No") }
                item { CopyDetail("Threads", process.threads.toString()) }
                item { CopyDetail("Nice Value", process.nice.toString()) }
                item { CopyDetail("Status", process.state) }
                item { CopyDetail("Start Time", formatStartTime(process.startTimeMillis)) }
                item { CopyDetail("Elapsed Time", formatDuration(process.elapsedTimeMillis)) }
                process.executablePath?.let { value -> item { CopyDetail("Executable Path", value) } }
                process.cgroup?.let { value -> item { CopyDetail("Cgroup", value) } }
                if (process.packageNames.isNotEmpty()) item { CopyDetail("Package", process.packageNames.joinToString("\n")) }
                item { CopyDetail("Command", process.command) }
                process.oomScoreAdj?.let { value -> item { CopyDetail("OOM score adj", value.toString()) } }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TextButton(onClick = onPin) { Text(if (process.isPinned) "Unpin" else "Pin") }
                if (process.packageName != null) Button(onClick = onForceStop) { Text("Force stop") }
                Button(onClick = onKill) { Text("Kill PID") }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CopyDetail(label: String, value: String) {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = {},
                onLongClick = {
                    clipboard.setText(AnnotatedString(value))
                    Toast.makeText(context, "$label copied", Toast.LENGTH_SHORT).show()
                },
            )
            .padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodySmall)
        Text(value, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ParentDetail(ppid: Int, parent: ProcessEntry?, onOpenParent: (ProcessEntry) -> Unit) {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = { parent?.let(onOpenParent) },
                onLongClick = {
                    clipboard.setText(AnnotatedString(ppid.toString()))
                    Toast.makeText(context, "Parent PID copied", Toast.LENGTH_SHORT).show()
                },
            )
            .padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text("Parent PID", style = MaterialTheme.typography.bodySmall)
        Text(if (parent != null) "$ppid ›" else "$ppid (not found)", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun SettingsDialog(
    state: TaskManagerUiState,
    onDismiss: () -> Unit,
    onAutoRefresh: (Boolean) -> Unit,
    onRefreshInterval: (Long) -> Unit,
    onConfirmKill: (Boolean) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Process settings") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                ToggleRow("Auto refresh", state.autoRefresh, onAutoRefresh)
                ToggleRow("Confirm before kill", state.confirmKill, onConfirmKill)
                Text("Refresh interval", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(500L, 800L, 1000L, 2000L).forEach { value ->
                        FilterChip(
                            selected = state.refreshIntervalMs == value,
                            onClick = { onRefreshInterval(value) },
                            label = { Text("${value}ms") },
                        )
                    }
                }
                Text("Network is sampled independently every 1 second.", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label)
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

private fun sortLabel(sort: ProcessSort): String = when (sort) {
    ProcessSort.MEMORY -> "RAM"
    ProcessSort.CPU -> "CPU"
    ProcessSort.NETWORK -> "NET"
    ProcessSort.NAME -> "A-Z"
    ProcessSort.PID -> "PID"
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
    if (bytes < 1024L) return "$bytes B"
    val units = arrayOf("KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var unit = -1
    while (value >= 1024.0 && unit < units.lastIndex) {
        value /= 1024.0
        unit++
    }
    return String.format(Locale.getDefault(), "%.1f %s", value, units[unit])
}

private fun formatSpeed(bytesPerSecond: Long): String = "${formatBytes(bytesPerSecond)}/s"

private fun formatKHz(khz: Long?): String =
    if (khz == null || khz <= 0L) "—" else String.format(Locale.US, "%.2f GHz", khz / 1_000_000.0)

private fun formatHz(hz: Long?): String =
    if (hz == null || hz <= 0L) "—" else String.format(Locale.US, "%.0f MHz", hz / 1_000_000.0)
