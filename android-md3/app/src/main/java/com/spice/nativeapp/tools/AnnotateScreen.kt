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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun AnnotateScreen(modifier: Modifier = Modifier) {
    var panels by remember { mutableIntStateOf(4) }
    var scaleBar by remember { mutableStateOf(true) }
    var scaleUm by remember { mutableFloatStateOf(50f) }
    var caption by remember { mutableStateOf("图 1. 各处理组的代表性显微视野（比例尺 50 µm）") }

    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(com.spice.nativeapp.core.I18n.t("图像标注（多面板排版）"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(com.spice.nativeapp.core.I18n.t("原生版不含位图导入，提供排版预览：面板字母、标题与比例尺位置。"), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(1, 2, 4, 6).forEach { n ->
                FilterChip(selected = panels == n, onClick = { panels = n }, label = { Text(com.spice.nativeapp.core.I18n.tf("{v1} 面板", n), fontSize = 11.sp) })
            }
        }
        Canvas(Modifier.fillMaxWidth().height(300.dp)) {
            val cols = when (panels) {
                1 -> 1
                2 -> 2
                6 -> 3
                else -> 2
            }
            val rows = when (panels) {
                1 -> 1
                2 -> 1
                6 -> 2
                else -> 2
            }
            val gap = 8.dp.toPx()
            val cw = (size.width - gap * (cols + 1)) / cols
            val ch = (size.height - gap * (rows + 1)) / rows
            var idx = 0
            for (r in 0 until rows) for (c in 0 until cols) {
                if (idx >= panels) break
                val x = gap + c * (cw + gap)
                val y = gap + r * (ch + gap)
                drawRect(Color(0xFF16202B), Offset(x, y), Size(cw, ch))
                drawRect(Color(0xFF2E3E50), Offset(x, y), Size(cw, ch), style = Stroke(1.dp.toPx()))
                val letter = ('A' + idx).toString()
                drawRect(Color(0xFF7FE7D6), Offset(x + 6.dp.toPx(), y + 6.dp.toPx()), Size(14.dp.toPx(), 14.dp.toPx()))
                if (scaleBar) {
                    val barW = cw * 0.28f
                    drawLine(
                        Color.White,
                        Offset(x + cw - barW - 8.dp.toPx(), y + ch - 14.dp.toPx()),
                        Offset(x + cw - 8.dp.toPx(), y + ch - 14.dp.toPx()),
                        3.dp.toPx()
                    )
                }
                idx++
            }
        }
        Text(com.spice.nativeapp.core.I18n.tf("面板字母 A–{v1}；比例尺 {v2} µm", ('A' + panels - 1), scaleUm.toInt()), fontSize = 11.sp)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(com.spice.nativeapp.core.I18n.t("显示比例尺"), fontSize = 12.sp)
            Switch(checked = scaleBar, onCheckedChange = { scaleBar = it })
        }
        Text(com.spice.nativeapp.core.I18n.tf("比例尺长度（µm）：{v1}", scaleUm.toInt()), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Slider(value = scaleUm, onValueChange = { scaleUm = it }, valueRange = 10f..500f)
        OutlinedTextField(
            value = caption,
            onValueChange = { caption = it },
            label = { Text(com.spice.nativeapp.core.I18n.t("图注"), fontSize = 12.sp) },
            modifier = Modifier.fillMaxWidth(),
            minLines = 2
        )
        AssistChip(onClick = {}, label = { Text(com.spice.nativeapp.core.I18n.tf("面板数 {v1} · 比例尺 {v2} µm", panels, scaleUm.toInt()), fontSize = 11.sp) })
    }
}
