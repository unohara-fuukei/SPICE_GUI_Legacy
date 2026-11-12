package com.spice.nativeapp.tools

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.spice.nativeapp.core.ImageTools

data class BlotLane(val label: String, val target: Float, val loading: Float)

/** 一张膜：自带一套泳道与内参，膜内相对 Ctrl 归一 */
data class Membrane(val name: String, val lanes: List<BlotLane>, val ctrlIndex: Int = 0)

private fun defaultMembranes() = listOf(
    Membrane(
        "膜 1", listOf(
            BlotLane("Ctrl", 0.75f, 0.9f), BlotLane("T1", 0.55f, 0.88f),
            BlotLane("T2", 0.35f, 0.85f), BlotLane("T3", 0.2f, 0.86f), BlotLane("T4", 0.48f, 0.84f)
        )
    ),
    Membrane(
        "膜 2", listOf(
            BlotLane("Ctrl", 0.7f, 0.86f), BlotLane("T1", 0.42f, 0.82f),
            BlotLane("T2", 0.28f, 0.88f), BlotLane("T3", 0.15f, 0.8f), BlotLane("T4", 0.36f, 0.85f)
        )
    )
)

private fun foldOf(m: Membrane, lane: BlotLane): Float {
    val ctrl = m.lanes.getOrElse(m.ctrlIndex) { m.lanes.first() }
    val ctrlRatio = ctrl.target / ctrl.loading.coerceAtLeast(0.01f)
    return (lane.target / lane.loading.coerceAtLeast(0.01f)) / ctrlRatio.coerceAtLeast(0.0001f)
}

@Composable
fun BlotScreen(modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var membranes by remember { mutableStateOf(defaultMembranes()) }
    var mIdx by remember { mutableStateOf(0) }
    var selected by remember { mutableStateOf(0) }
    var status by remember { mutableStateOf("可手工调节，或分别导入目标带/内参照片按泳道自动读数（多膜独立归一）") }

    fun updateMembrane(index: Int, block: (Membrane) -> Membrane) {
        membranes = membranes.toMutableList().also { it[index] = block(it[index]) }
    }

    val pickTarget = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching {
            val bmp = ImageTools.loadScaled(ctx, uri) ?: return@runCatching
            val values = ImageTools.analyzeBlot(ImageTools.toGray(bmp), membranes[mIdx].lanes.size)
            updateMembrane(mIdx) { m ->
                m.copy(lanes = m.lanes.mapIndexed { i, l -> l.copy(target = values.getOrElse(i) { l.target }.coerceIn(0.02f, 1f)) })
            }
            status = "已按 ${membranes[mIdx].lanes.size} 泳道读取目标带灰度（${membranes[mIdx].name}）"
        }.onFailure { status = "目标带读取失败：${it.message}" }
    }

    val pickLoading = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching {
            val bmp = ImageTools.loadScaled(ctx, uri) ?: return@runCatching
            val values = ImageTools.analyzeBlot(ImageTools.toGray(bmp), membranes[mIdx].lanes.size)
            updateMembrane(mIdx) { m ->
                m.copy(lanes = m.lanes.mapIndexed { i, l -> l.copy(loading = values.getOrElse(i) { l.loading }.coerceIn(0.02f, 1f)) })
            }
            status = "已按 ${membranes[mIdx].lanes.size} 泳道读取内参灰度（${membranes[mIdx].name}），膜内归一已自动更新"
        }.onFailure { status = "内参读取失败：${it.message}" }
    }

    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Western blot 定量（多膜）", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(status, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)

        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            membranes.forEachIndexed { i, m ->
                FilterChip(selected = mIdx == i, onClick = { mIdx = i; selected = 0 }, label = { Text(m.name, fontSize = 11.sp) })
            }
            TextButton(onClick = {
                membranes = membranes + Membrane("膜 ${membranes.size + 1}", membranes[mIdx].lanes.map { it.copy() })
                mIdx = membranes.size - 1
                selected = 0
            }) { Text("+ 新增膜", fontSize = 11.sp) }
            if (membranes.size > 1) TextButton(onClick = {
                membranes = membranes.filterIndexed { i, _ -> i != mIdx }
                mIdx = 0; selected = 0
            }) { Text("删除当前膜", fontSize = 11.sp) }
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { pickTarget.launch(arrayOf("image/*")) }, modifier = Modifier.weight(1f)) { Text("导入目标带", fontSize = 11.sp) }
            OutlinedButton(onClick = { pickLoading.launch(arrayOf("image/*")) }, modifier = Modifier.weight(1f)) { Text("导入内参带", fontSize = 11.sp) }
        }

        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))) {
            val lanes = membranes[mIdx].lanes
            Canvas(Modifier.fillMaxWidth().padding(6.dp).height(200.dp)) {
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
                    band(0.78f, l.loading, 0.5f)
                }
            }
        }

        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            membranes[mIdx].lanes.forEachIndexed { i, l ->
                FilterChip(selected = selected == i, onClick = { selected = i }, label = { Text(l.label, fontSize = 11.sp) })
            }
            TextButton(onClick = {
                val m = membranes[mIdx]
                val newLabel = "T${m.lanes.count { it.label.startsWith("T") } + 1}"
                updateMembrane(mIdx) { it.copy(lanes = it.lanes + BlotLane(newLabel, 0.5f, 0.85f)) }
                selected = membranes[mIdx].lanes.size - 1
            }) { Text("+ 泳道", fontSize = 11.sp) }
        }

        val lane = membranes[mIdx].lanes[selected]
        val maxLanes = membranes[mIdx].lanes.size
        Text("${membranes[mIdx].name} · 泳道 ${lane.label}（作为内参基准请选 Ctrl）", fontSize = 12.sp, fontWeight = FontWeight.Medium)
        Text("目标带：%.0f%%".format(lane.target * 100), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Slider(
            value = lane.target,
            onValueChange = { v -> updateMembrane(mIdx) { m -> m.copy(lanes = m.lanes.toMutableList().also { it[selected] = it[selected].copy(target = v) }) } },
            valueRange = 0f..1f
        )
        Text("内参带：%.0f%%".format(lane.loading * 100), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Slider(
            value = lane.loading,
            onValueChange = { v -> updateMembrane(mIdx) { m -> m.copy(lanes = m.lanes.toMutableList().also { it[selected] = it[selected].copy(loading = v) }) } },
            valueRange = 0f..1f
        )
        OutlinedButton(
            onClick = {
                updateMembrane(mIdx) { it.copy(ctrlIndex = selected) }
                status = "已将 ${membranes[mIdx].lanes[selected].label} 设为 ${membranes[mIdx].name} 的内参基准"
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text("把当前泳道设为该膜 Ctrl") }

        Text("膜内归一（相对本膜 Ctrl）", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        val m = membranes[mIdx]
        m.lanes.forEach { l ->
            val fold = foldOf(m, l)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(l.label, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                Text(
                    "目标 %.2f · 内参 %.2f · %.2f×".format(l.target, l.loading, fold),
                    fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                    color = if (l.label == m.lanes.getOrElse(m.ctrlIndex) { m.lanes.first() }.label)
                        MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface
                )
            }
        }

        if (membranes.size > 1) {
            Text("跨膜汇总（各膜独立归一后并列，需再做膜间标定系数）", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            val names = membranes.flatMap { it.lanes.map { l -> l.label } }.distinct()
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("泳道", fontSize = 10.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.padding(end = 6.dp))
                membranes.forEach { mm -> Text(mm.name, fontSize = 10.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.padding(end = 8.dp)) }
            }
            names.forEach { label ->
                Row(Modifier.fillMaxWidth()) {
                    Text(label, fontSize = 10.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.padding(end = 12.dp))
                    membranes.forEach { mm ->
                        val l = mm.lanes.firstOrNull { it.label == label }
                        Text(
                            l?.let { "%.2f×".format(foldOf(mm, it)) } ?: "—",
                            fontSize = 10.sp, fontFamily = FontFamily.Monospace,
                            modifier = Modifier.padding(end = 12.dp)
                        )
                    }
                }
            }
        }

        OutlinedButton(
            onClick = {
                val sb = StringBuilder("membrane,lane,target,loading,fold\n")
                membranes.forEach { mm ->
                    mm.lanes.forEach { l -> sb.append("${mm.name},${l.label},${"%.3f".format(l.target)},${"%.3f".format(l.loading)},${"%.3f".format(foldOf(mm, l))}\n") }
                }
                clipboard.setText(AnnotatedString(sb.toString()))
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text("复制全部分组 CSV") }
        Text("说明：膜 1/膜 2 各自用本膜 Ctrl 归一，跨膜直接比较需要额外的膜间加载系数；本工具不臆造该系数。", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
