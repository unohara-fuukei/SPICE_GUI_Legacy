package com.spice.nativeapp.tools

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Western blot 示意：每条泳道一条目标带 + 可选内参带 */
data class BlotLane(val label: String, val target: Float, val loading: Float)

@Composable
fun BlotScreen(modifier: Modifier = Modifier) {
    var lanes by remember {
        mutableStateOf(
            listOf(
                BlotLane("Ladder", 0.55f, 0.35f),
                BlotLane("Ctrl", 0.75f, 0.9f),
                BlotLane("T1", 0.55f, 0.88f),
                BlotLane("T2", 0.35f, 0.85f),
                BlotLane("T3", 0.2f, 0.86f)
            )
        )
    }
    var selected by remember { mutableStateOf(0) }

    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Western blot 强度示意", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text("白色 = 信号强；内参带用于归一化。选中泳道后拖动滑块调节。", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)

        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))) {
            Canvas(Modifier.fillMaxWidth().padding(6.dp).height(230.dp)) {
                val laneW = size.width / lanes.size
                val top = 10.dp.toPx()
                drawRect(Color(0xFF05080C), Offset(0f, top), Size(size.width, size.height - top))
                lanes.forEachIndexed { i, l ->
                    val cx = laneW * i + laneW / 2f
                    if (i > 0) drawLine(Color(0xFF16202B), Offset(laneW * i, top), Offset(laneW * i, size.height), 1.dp.toPx())
                    fun band(yFrac: Float, intensity: Float, w: Float) {
                        val y = top + (size.height - top) * yFrac
                        drawRect(
                            Color.White.copy(alpha = 0.08f + intensity * 0.9f),
                            Offset(cx - laneW * w / 2f, y - 4.dp.toPx()),
                            Size(laneW * w, 8.dp.toPx())
                        )
                    }
                    band(0.42f, l.target, 0.66f)
                    band(0.78f, l.loading, 0.58f)
                }
            }
        }

        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            lanes.forEachIndexed { i, l ->
                androidx.compose.material3.FilterChip(
                    selected = selected == i,
                    onClick = { selected = i },
                    label = { Text(l.label, fontSize = 11.sp) }
                )
            }
        }

        Text("泳道 ${lanes[selected].label}", fontSize = 12.sp, fontWeight = FontWeight.Medium)
        Text("目标带强度：%.0f%%".format(lanes[selected].target * 100), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Slider(
            value = lanes[selected].target,
            onValueChange = { v -> lanes = lanes.toMutableList().also { it[selected] = it[selected].copy(target = v) } },
            valueRange = 0f..1f
        )
        Text("内参强度：%.0f%%".format(lanes[selected].loading * 100), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Slider(
            value = lanes[selected].loading,
            onValueChange = { v -> lanes = lanes.toMutableList().also { it[selected] = it[selected].copy(loading = v) } },
            valueRange = 0f..1f
        )

        val ctrl = lanes.firstOrNull { it.label.startsWith("Ctrl") }
        if (ctrl != null) {
            Text("相对 Ctrl 的归一化信号", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            lanes.drop(1).forEach { l ->
                val norm = (l.target / l.loading.coerceAtLeast(0.01f)) / (ctrl.target / ctrl.loading.coerceAtLeast(0.01f))
                Text("${l.label}: %.2f×".format(norm), fontSize = 11.sp)
            }
        }
    }
}
