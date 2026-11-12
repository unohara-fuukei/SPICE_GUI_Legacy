package com.spice.nativeapp.tools

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.spice.nativeapp.core.Enzymes
import com.spice.nativeapp.core.SeqRecord
import kotlin.math.log10

/** 内置 DNA ladder */
data class Ladder(val id: String, val name: String, val sizes: List<Int>)

val LADDERS = listOf(
    Ladder("gr100plus", "GeneRuler 100 bp Plus", listOf(3000, 2000, 1500, 1200, 1000, 900, 800, 700, 600, 500, 400, 300, 200, 100)),
    Ladder("neb1kb", "NEB 1 kb Plus", listOf(10000, 8000, 6000, 5000, 4000, 3000, 2000, 1500, 1000, 700, 500, 400, 300, 200, 100)),
    Ladder("gr100", "GeneRuler 100 bp", listOf(1000, 900, 800, 700, 600, 500, 400, 300, 200, 100))
)

/** log10 迁移：100 bp 在底部，10 kb 在顶部 */
fun bpToFraction(bp: Int): Float {
    val lo = log10(100.0)
    val hi = log10(10000.0)
    val t = ((log10(bp.toDouble().coerceAtLeast(1.0)) - lo) / (hi - lo)).coerceIn(0.0, 1.0)
    return t.toFloat()
}

@Composable
fun GelScreen(record: SeqRecord, modifier: Modifier = Modifier) {
    var ladderId by remember { mutableStateOf("neb1kb") }
    var enzymes by remember { mutableStateOf(setOf("EcoRI", "BamHI", "HindIII")) }
    var allCommon by remember { mutableStateOf(true) }

    val ladder = LADDERS.first { it.id == ladderId }
    val lanes = remember(record.sequence, enzymes, allCommon, ladderId) {
        val out = mutableListOf<Pair<String, List<Int>>>()
        out.add("Ladder" to ladder.sizes)
        val set = if (allCommon) Enzymes.common else enzymes
        set.sorted().forEach { name ->
            val frags = Enzymes.fragments(record.sequence, setOf(name), record.circular)
            if (frags.isNotEmpty()) out.add(name to frags.sortedDescending())
        }
        out.add("全酶切" to Enzymes.fragments(record.sequence, set, record.circular).sortedDescending())
        out
    }

    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("琼脂糖凝胶迁移模拟", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text("按 log10(bp) 线性迁移，100 bp 位于底部、10 kb 位于顶部；条带亮度 ∝ 质量（1500/bp 归一）。", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)

        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            LADDERS.forEach { l ->
                FilterChip(selected = ladderId == l.id, onClick = { ladderId = l.id }, label = { Text(l.name, fontSize = 11.sp) })
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(selected = allCommon, onClick = { allCommon = !allCommon }, label = { Text("常用酶全部泳道", fontSize = 11.sp) })
        }

        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))) {
            Canvas(Modifier.fillMaxWidth().padding(6.dp).height(360.dp)) {
                val padTop = 8.dp.toPx()
                val padBottom = 28.dp.toPx()
                val laneW = size.width / lanes.size
                val top = padTop
                val bottom = size.height - padBottom
                // 胶体
                drawRect(Color(0xFF0E1620), topLeft = Offset(0f, top), size = Size(size.width, bottom - top))
                lanes.forEachIndexed { li, (_, bands) ->
                    val cx = laneW * li + laneW / 2f
                    // 泳道分隔
                    if (li > 0) drawLine(Color(0xFF1E2A38), Offset(laneW * li, top), Offset(laneW * li, bottom), 1.dp.toPx())
                    bands.forEach { bp ->
                        val frac = bpToFraction(bp)
                        val y = bottom - frac * (bottom - top)
                        val intensity = (1500.0 / bp).coerceIn(0.22, 1.0).toFloat()
                        val w = laneW * 0.62f
                        val h = (1.2f + intensity * 1.8f).dp.toPx()
                        drawRect(
                            Color(0xFFFFE082).copy(alpha = 0.45f + intensity * 0.5f),
                            topLeft = Offset(cx - w / 2f, y - h / 2f),
                            size = Size(w, h)
                        )
                    }
                }
                // 底部标签用 Canvas 原生文字较麻烦，交给下方表
            }
        }

        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            lanes.forEach { (label, bands) ->
                Column {
                    Text(label, fontSize = 10.sp, fontWeight = FontWeight.Medium)
                    Text("${bands.size} 带", fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        Text("片段长度（bp）", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        lanes.forEach { (label, bands) ->
            Text(
                "$label: " + bands.take(12).joinToString(", ") + if (bands.size > 12) "…" else "",
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace
            )
        }
    }
}
