package com.spice.nativeapp.tools

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 96 孔板的四种内容类型 */
enum class WellKind(val label: String, val color: Color) {
    EMPTY("空", Color(0xFF212B38)),
    SAMPLE("样品", Color(0xFF7FE7D6)),
    CONTROL("对照", Color(0xFFFFC56E)),
    BLANK("空白", Color(0xFF9FA8FF))
}

@Composable
fun PlateScreen(modifier: Modifier = Modifier) {
    var plate by remember { mutableStateOf(List(96) { WellKind.EMPTY }) }
    var brush by remember { mutableStateOf(WellKind.SAMPLE) }
    val counts = remember(plate) { plate.groupingBy { it }.eachCount() }

    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("96 孔板布局", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text("点选笔刷后点击孔位填充，可统计样品/对照/空白数量。", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            WellKind.values().forEach { k ->
                FilterChip(selected = brush == k, onClick = { brush = k }, label = { Text(k.label, fontSize = 11.sp) })
            }
            FilterChip(selected = false, onClick = { plate = List(96) { WellKind.EMPTY } }, label = { Text("清空", fontSize = 11.sp) })
        }

        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))) {
            Column(Modifier.fillMaxWidth().padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                // 列号
                Row(Modifier.fillMaxWidth()) {
                    Box(Modifier.weight(0.5f))
                    (1..12).forEach { c ->
                        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                            Text("$c", fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                (0 until 8).forEach { r ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.weight(0.5f), contentAlignment = Alignment.Center) {
                            Text(('A' + r).toString(), fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        (0 until 12).forEach { c ->
                            val idx = r * 12 + c
                            Box(
                                Modifier
                                    .weight(1f)
                                    .aspectRatio(1f)
                                    .padding(2.dp)
                                    .clip(CircleShape)
                                    .background(plate[idx].color)
                                    .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.35f), CircleShape)
                                    .clickable { plate = plate.toMutableList().also { it[idx] = brush } }
                            )
                        }
                    }
                }
            }
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            countCard("样品", counts[WellKind.SAMPLE] ?: 0, WellKind.SAMPLE.color, Modifier.weight(1f))
            countCard("对照", counts[WellKind.CONTROL] ?: 0, WellKind.CONTROL.color, Modifier.weight(1f))
            countCard("空白", counts[WellKind.BLANK] ?: 0, WellKind.BLANK.color, Modifier.weight(1f))
            countCard("空", counts[WellKind.EMPTY] ?: 0, WellKind.EMPTY.color, Modifier.weight(1f))
        }
    }
}

@Composable
private fun countCard(label: String, n: Int, color: Color, modifier: Modifier = Modifier) {
    Card(modifier, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))) {
        Column(Modifier.fillMaxWidth().padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.clip(RoundedCornerShape(3.dp)).background(color).fillMaxWidth().padding(2.dp)) {}
            Text("$n", fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Text(label, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
