package com.spice.nativeapp.tools

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.spice.nativeapp.core.ImageTools

data class BlotLane(val label: String, val target: Float, val loading: Float)

@Composable
fun BlotScreen(modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    var lanes by remember {
        mutableStateOf(
            listOf(
                BlotLane("Ctrl", 0.75f, 0.9f),
                BlotLane("T1", 0.55f, 0.88f),
                BlotLane("T2", 0.35f, 0.85f),
                BlotLane("T3", 0.2f, 0.86f),
                BlotLane("T4", 0.48f, 0.84f)
            )
        )
    }
    var selected by remember { mutableStateOf(0) }
    var photoStatus by remember { mutableStateOf("可手工调节，或导入 Western blot 照片按泳道自动读数") }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching {
            val bmp = ImageTools.loadScaled(ctx, uri) ?: return@runCatching
            val gray = ImageTools.toGray(bmp)
            val values = ImageTools.analyzeBlot(gray, lanes.size)
            lanes = lanes.mapIndexed { i, l ->
                l.copy(target = values.getOrElse(i) { l.target }.coerceIn(0.05f, 1f))
            }
            photoStatus = "已按 ${lanes.size} 泳道读取灰度强度；内参带需另拍内参膜或手工校准"
        }.onFailure { photoStatus = "照片读取失败：${it.message}" }
    }

    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Western blot 定量", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(photoStatus, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { launcher.launch(arrayOf("image/*")) }, modifier = Modifier.weight(1f)) { Text("导入照片读数") }
        }

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
                FilterChip(selected = selected == i, onClick = { selected = i }, label = { Text(l.label, fontSize = 11.sp) })
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

        Text("相对 Ctrl 的归一化信号", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        val ctrl = lanes.firstOrNull { it.label.startsWith("Ctrl") } ?: lanes.first()
        val ctrlRatio = ctrl.target / ctrl.loading.coerceAtLeast(0.01f)
        lanes.forEach { l ->
            val norm = (l.target / l.loading.coerceAtLeast(0.01f)) / ctrlRatio
            Text("${l.label}: %.2f×".format(norm), fontSize = 11.sp)
        }
    }
}
