package com.rk.taskmanager.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

@Composable
fun HistoryChart(
    values: List<Float>,
    modifier: Modifier = Modifier,
    maxValue: Float = 100f,
) {
    val lineColor = MaterialTheme.colorScheme.primary
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    Canvas(modifier = modifier.fillMaxWidth().height(120.dp)) {
        if (size.width <= 0f || size.height <= 0f) return@Canvas

        repeat(5) { i ->
            val y = size.height * i / 4f
            drawLine(
                color = gridColor,
                start = Offset(0f, y),
                end = Offset(size.width, y),
                strokeWidth = 1f,
            )
        }

        if (values.size < 2) return@Canvas
        val capped = values.takeLast(60)
        val stepX = size.width / (capped.size - 1).coerceAtLeast(1)
        val path = Path()
        capped.forEachIndexed { index, raw ->
            val value = raw.coerceIn(0f, maxValue)
            val x = index * stepX
            val y = size.height - (value / maxValue) * size.height
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(
            path = path,
            color = lineColor,
            style = Stroke(width = 3f, cap = StrokeCap.Round),
        )
    }
}
