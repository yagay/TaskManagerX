package com.rk.taskmanager.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.rk.taskmanager.MainViewModel
import com.rk.taskmanager.model.CpuCoreInfo
import java.util.Locale

@Composable
fun SystemInfoOverlay(viewModel: MainViewModel) {
    val state by viewModel.state.collectAsState()
    var open by remember { mutableStateOf(false) }

    ExtendedFloatingActionButton(
        onClick = { open = true },
        icon = { androidx.compose.material3.Icon(Icons.Default.Info, contentDescription = null) },
        text = { Text("System") }
    )

    if (!open) return
    val s = state.system
    AlertDialog(
        onDismissRequest = { open = false },
        title = { Text("System information") },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item { Section("CPU usage") }
                item { HistoryChart(state.cpuHistory) }
                item { Info("Current", String.format(Locale.US, "%.1f%%", s.cpuPercent)) }

                item { HorizontalDivider(Modifier.padding(vertical = 4.dp)) }
                item { Section("RAM usage") }
                item { HistoryChart(state.ramHistory) }
                item { Info("RAM", "${formatBytes2(s.ramUsedBytes)} / ${formatBytes2(s.ramTotalBytes)} (${percent(s.ramUsedBytes, s.ramTotalBytes)}%)") }

                item { HorizontalDivider(Modifier.padding(vertical = 4.dp)) }
                item { Section("SWAP usage") }
                item { HistoryChart(state.swapHistory) }
                item { Info("SWAP", "${formatBytes2(s.swapUsedBytes)} / ${formatBytes2(s.swapTotalBytes)} (${percent(s.swapUsedBytes, s.swapTotalBytes)}%)") }

                item { HorizontalDivider(Modifier.padding(vertical = 4.dp)) }
                item { Section("Processor") }
                item { Info("SoC", s.soc.ifBlank { "Unknown" }) }
                item { Info("Architecture", s.architecture.ifBlank { "Unknown" }) }
                item { Info("ABI", s.abi.ifBlank { "Unknown" }) }
                item { Info("CPU cores", s.cpuCoreCount.toString()) }
                item { Info("Governor", s.governor.ifBlank { "Unknown" }) }
                item { Info("CPU temperature", s.cpuTemperatureC?.let { String.format(Locale.US, "%.1f °C", it) } ?: "No data") }
                item { Info("Uptime", formatDuration2(s.uptimeMillis)) }
                item { Info("Load average", String.format(Locale.US, "%.2f", s.load1)) }

                item { HorizontalDivider(Modifier.padding(vertical = 4.dp)) }
                item { Section("Memory details") }
                item { Info("Available RAM", formatBytes2(s.ramAvailableBytes)) }
                item { Info("Cached", formatBytes2(s.cachedBytes)) }
                item { Info("Buffers", formatBytes2(s.buffersBytes)) }

                item { HorizontalDivider(Modifier.padding(vertical = 4.dp)) }
                item { Section("CPU frequencies") }
                if (s.cpuCores.isEmpty()) {
                    item { Text("No cpufreq data", style = MaterialTheme.typography.bodySmall) }
                } else {
                    items(s.cpuCores, key = { it.core }) { core -> CoreRow(core) }
                }
            }
        },
        confirmButton = { TextButton(onClick = { open = false }) { Text("Close") } }
    )
}

@Composable
private fun Section(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall)
}

@Composable
private fun Info(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodySmall)
        Text(value, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun CoreRow(core: CpuCoreInfo) {
    Column(Modifier.fillMaxWidth()) {
        Text("CPU ${core.core}", style = MaterialTheme.typography.labelMedium)
        Text(
            "${freq(core.minKHz)} / ${freq(core.currentKHz)} / ${freq(core.maxKHz)}   min/current/max",
            style = MaterialTheme.typography.bodySmall
        )
    }
}

private fun freq(khz: Long?): String = if (khz == null || khz <= 0) "—" else String.format(Locale.US, "%.2f GHz", khz / 1_000_000.0)
private fun percent(used: Long, total: Long): Int = if (total > 0) ((used * 100.0 / total).toInt()).coerceIn(0, 100) else 0
private fun formatBytes2(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var index = 0
    while (value >= 1024.0 && index < units.lastIndex) { value /= 1024.0; index++ }
    return String.format(Locale.US, "%.1f %s", value, units[index])
}
private fun formatDuration2(ms: Long): String {
    var seconds = (ms / 1000L).coerceAtLeast(0)
    val days = seconds / 86400; seconds %= 86400
    val hours = seconds / 3600; seconds %= 3600
    val minutes = seconds / 60; val secs = seconds % 60
    return if (days > 0) "${days}d ${hours}h ${minutes}m" else String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, secs)
}
