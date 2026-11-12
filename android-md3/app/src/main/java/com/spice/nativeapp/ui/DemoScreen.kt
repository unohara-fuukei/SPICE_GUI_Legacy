package com.spice.nativeapp.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.spice.nativeapp.core.SeqRecord
import kotlin.math.min

private val arcColors = listOf(Color(0xFF7DD3FC), Color(0xFF86EFAC), Color(0xFFFCD34D), Color(0xFFF9A8D4), Color(0xFFC4B5FD))

/** 演示模式：全屏只读浏览当前序列（适合投屏/讲解），无编辑入口 */
@Composable
fun DemoScreen(record: SeqRecord, onExit: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Text(record.name, fontSize = 26.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            TextButton(onClick = onExit, modifier = Modifier.align(Alignment.TopEnd)) { Text("退出演示") }
        }
        Text(
            "${record.length} bp · GC ${"%.1f".format(record.gc)}% · ${if (record.circular) "环状" else "线性"} · ${record.features.size} 特征",
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Box(Modifier.fillMaxWidth().height(320.dp)) {
            Canvas(Modifier.fillMaxSize()) {
                val cx = center.x
                val cy = center.y
                val r = min(size.width, size.height) / 2f - 24f
                drawCircle(Color(0xFF16202B), radius = r, center = Offset(cx, cy))
                drawCircle(Color(0xFF334155), radius = r, center = Offset(cx, cy), style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2f))
                record.features.forEachIndexed { i, f ->
                    val startA = (f.start.toDouble() / record.length * 360.0 - 90.0).toFloat()
                    val endA = (f.end.toDouble() / record.length * 360.0 - 90.0).toFloat()
                    val sweep = if (endA >= startA) endA - startA else 360f - startA + endA
                    drawArc(
                        arcColors[i % arcColors.size],
                        startAngle = startA,
                        sweepAngle = sweep,
                        useCenter = false,
                        topLeft = Offset(cx - r, cy - r),
                        size = Size(r * 2, r * 2),
                        style = androidx.compose.ui.graphics.drawscope.Stroke(width = 10f)
                    )
                }
            }
        }
        Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState())) {
            record.features.forEach { f ->
                Text(
                    "• ${f.name}  ${f.start}–${f.end}  ${f.type}${if (f.strand < 0) " (−)" else ""}",
                    fontSize = 13.sp
                )
            }
        }
        Button(onClick = onExit, modifier = Modifier.fillMaxWidth()) { Text("返回应用") }
    }
}
