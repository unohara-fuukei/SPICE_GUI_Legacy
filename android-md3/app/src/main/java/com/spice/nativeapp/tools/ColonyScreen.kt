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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun ColonyScreen(modifier: Modifier = Modifier) {
    var cells by remember { mutableStateOf(List(96) { false }) }
    var dilution by remember { mutableStateOf("1e-6") }
    var platedUl by remember { mutableStateOf("100") }
    val clipboard = LocalClipboardManager.current

    val counted = cells.count { it }
    val dil = dilution.toDoubleOrNull() ?: 1e-6
    val ul = platedUl.toDoubleOrNull() ?: 100.0
    val cfuPerMl = if (ul > 0) counted / dil / (ul / 1000.0) else 0.0

    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("菌落计数", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text("点选已计数的菌落格；按稀释度与涂布体积换算 CFU/mL。", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)

        Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.28f)).padding(6.dp)) {
            (0 until 8).forEach { r ->
                Row(Modifier.fillMaxWidth()) {
                    (0 until 12).forEach { c ->
                        val i = r * 12 + c
                        Box(
                            Modifier
                                .weight(1f)
                                .aspectRatio(1f)
                                .padding(2.dp)
                                .clip(CircleShape)
                                .background(if (cells[i]) Color(0xFF7FE7D6) else Color(0xFF1B2432))
                                .border(1.dp, Color(0xFF2E3E50), CircleShape)
                                .clickable {
                                    cells = cells.toMutableList().also { it[i] = !it[i] }
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            if (cells[i]) Text("•", fontSize = 10.sp, color = Color(0xFF00382F))
                        }
                    }
                }
            }
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = dilution,
                onValueChange = { dilution = it },
                label = { Text("稀释度", fontSize = 11.sp) },
                modifier = Modifier.weight(1f),
                singleLine = true
            )
            OutlinedTextField(
                value = platedUl,
                onValueChange = { platedUl = it },
                label = { Text("涂布体积 µL", fontSize = 11.sp) },
                modifier = Modifier.weight(1f),
                singleLine = true
            )
        }

        Text("计数：$counted / 96", fontSize = 13.sp, fontWeight = FontWeight.Bold)
        Text("估算浓度：%.3e CFU/mL".format(cfuPerMl), fontSize = 13.sp, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
        Text(
            if (counted in 30..300) "计数区间合规（30–300），数据可用于统计。" else "提示：菌落数宜落在 30–300 之间，否则调整稀释度重做。",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.tertiary
        )

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    val header = "row,col,counted"
                    val rows = (0 until 96).filter { cells[it] }.joinToString("\n") { i ->
                        "${('A' + i / 12)},${i % 12 + 1},1"
                    }
                    clipboard.setText(AnnotatedString("$header\n$rows"))
                },
                modifier = Modifier.weight(1f)
            ) { Text("复制 CSV") }
            Button(onClick = { cells = List(96) { false } }, modifier = Modifier.weight(1f)) { Text("清零") }
        }
    }
}
