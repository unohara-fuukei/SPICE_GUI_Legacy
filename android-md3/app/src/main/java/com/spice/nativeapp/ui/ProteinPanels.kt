package com.spice.nativeapp.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.spice.nativeapp.core.ProteinBiophysics
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * 蛋白工作台面板（对齐 main 分支蛋白页右侧六个分析 Tab：
 * fold / md / pocket / phase / mut / batch），纯 Canvas 自绘。
 */

@Composable
private fun PanelCard(title: String, content: @Composable () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            content()
        }
    }
}

@Composable
private fun KV(label: String, value: String, warn: Boolean = false) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            value, fontSize = 12.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium,
            color = if (warn) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurface
        )
    }
}

// ───────────────────────── 折叠面板（含 3D 骨架） ─────────────────────────

@Composable
fun FoldPanel(fold: ProteinBiophysics.FoldOutput, sequence: String) {
    var rotX by remember { mutableFloatStateOf(-0.35f) }
    var rotY by remember { mutableFloatStateOf(0.5f) }
    var zoom by remember { mutableFloatStateOf(1f) }
    PanelCard("结构折叠面板") {
        val n = fold.length
        val helix = fold.secondary.count { it.second == 'H' }
        val sheet = fold.secondary.count { it.second == 'E' }
        val coil = n - helix - sheet
        val contacts = fold.contactMap.sumOf { it.toInt() } / 2

        Canvas(
            Modifier
                .fillMaxWidth()
                .height(300.dp)
                .clipToBounds()
                .pointerInput(Unit) {
                    detectDragGestures { change, drag ->
                        change.consume()
                        rotY += drag.x / 120f
                        rotX += drag.y / 160f
                    }
                }
        ) {
            val cx = size.width / 2f
            val cy = size.height / 2f
            val coords = fold.coords
            var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE; var minZ = Float.MAX_VALUE
            var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE; var maxZ = -Float.MAX_VALUE
            for (i in 0 until n) {
                minX = min(minX, coords[i * 3]); maxX = max(maxX, coords[i * 3])
                minY = min(minY, coords[i * 3 + 1]); maxY = max(maxY, coords[i * 3 + 1])
                minZ = min(minZ, coords[i * 3 + 2]); maxZ = max(maxZ, coords[i * 3 + 2])
            }
            val ctrX = (minX + maxX) / 2f; val ctrY = (minY + maxY) / 2f; val ctrZ = (minZ + maxZ) / 2f
            val maxR = max(max(maxX - minX, maxY - minY), maxZ - minZ).coerceAtLeast(1f)
            val scale = min(size.width, size.height) * 0.42f / maxR

            val pts = (0 until n).map { i ->
                val x = coords[i * 3] - ctrX
                val y = coords[i * 3 + 1] - ctrY
                val z = coords[i * 3 + 2] - ctrZ
                val x1 = x * cos(rotY.toDouble()).toFloat() - z * sin(rotY.toDouble()).toFloat()
                val z1 = x * sin(rotY.toDouble()).toFloat() + z * cos(rotY.toDouble()).toFloat()
                val y2 = y * cos(rotX.toDouble()).toFloat() - z1 * sin(rotX.toDouble()).toFloat()
                val z2 = y * sin(rotX.toDouble()).toFloat() + z1 * cos(rotX.toDouble()).toFloat()
                val persp = 1f / (1f + z2 / (maxR + 40f))
                Offset(cx + x1 * scale * zoom, cy + y2 * scale * zoom * persp)
            }
            pts.zipWithNext { a, b ->
                drawLine(
                    Color(0xFF7FE7D6).copy(alpha = 0.85f), a, b,
                    if (n > 250) 1.2.dp.toPx() else 2.2.dp.toPx()
                )
            }
            if (pts.isNotEmpty()) {
                drawCircle(Color(0xFF9FA8FF), 5.dp.toPx(), pts.first())
                drawCircle(Color(0xFFFF8A80), 5.dp.toPx(), pts.last())
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { zoom = (zoom - 0.2f).coerceAtLeast(0.4f) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("缩小", fontSize = 11.sp) }
            OutlinedButton(onClick = { zoom = (zoom + 0.2f).coerceAtMost(3f) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("放大", fontSize = 11.sp) }
        }
        KV("残基数", "$n aa")
        KV("二级结构", "α $helix · β $sheet · 卷曲 $coil（Chou-Fasman）")
        KV("接触对 (d<8Å)", "$contacts")
        KV("预测置信度", "%.0f%%".format(fold.conf * 100), warn = fold.conf < 0.5)
        Text(
            "Cα 骨架由二级结构感知几何构建（α-螺旋 3.6 res/turn；环境偏离 pH7/310K 时置信度下降）。N（蓝）→ C（红）。",
            fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

// ───────────────────────── MD 轨迹面板 ─────────────────────────

@Composable
fun MdPanel(md: ProteinBiophysics.MdStep) {
    PanelCard("动力学轨迹面板（MD）") {
        Canvas(Modifier.fillMaxWidth().height(160.dp)) {
            val h = md.uHist
            if (h.size < 2) return@Canvas
            val minU = h.min(); val maxU = h.max()
            val range = (maxU - minU).coerceAtLeast(1e-6)
            val dx = size.width / (h.size - 1)
            val pts = h.mapIndexed { i, u ->
                Offset(i * dx, size.height * 0.9f - ((u - minU) / range).toFloat() * size.height * 0.8f)
            }
            // 轴线
            drawLine(Color.Gray.copy(alpha = 0.4f), Offset(0f, size.height * 0.9f), Offset(size.width, size.height * 0.9f), 1.dp.toPx())
            pts.zipWithNext { a, b -> drawLine(Color(0xFF7FE7D6), a, b, 1.6.dp.toPx()) }
        }
        KV("步数 / 模拟时长", "${md.stepCount} 步 · %.2f ps".format(md.timePs))
        KV("势能 U", "%.0f kcal/mol".format(md.metrics.uTKcal))
        KV("回转半径 Rg", "%.2f Å".format(md.metrics.rg))
        KV("二级结构保持", "${md.metrics.nSsKept}/${md.metrics.nSsRef}")
        KV("表面带电残基", "${md.metrics.nSurfaceCharged}")
        Text("能量历史（U vs 步数）；启发式轨迹，用于稳定性趋势研判。", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// ───────────────────────── 温-pH 相图面板 ─────────────────────────

@Composable
fun PhasePanel(points: List<ProteinBiophysics.ScanPoint>) {
    PanelCard("环境相图面板（温-pH 稳定性扫描）") {
        val temps = points.map { it.t }.distinct().sorted()
        val phs = points.map { it.ph }.distinct().sorted()
        Canvas(Modifier.fillMaxWidth().height(200.dp)) {
            if (temps.isEmpty() || phs.isEmpty()) return@Canvas
            val left = 34f; val top = 10f
            val cellW = (size.width - left - 6f) / phs.size
            val cellH = (size.height - top - 26f) / temps.size
            for (i in temps.indices) for (j in phs.indices) {
                val p = points.firstOrNull { it.t == temps[i] && it.ph == phs[j] } ?: continue
                val color = when {
                    p.stable -> Color(0xFF4CAF50)
                    p.reason != null -> Color(0xFFF44336)
                    else -> Color(0xFFFFA726)
                }
                drawRect(
                    color.copy(alpha = 0.85f),
                    topLeft = Offset(left + j * cellW, top + i * cellH),
                    size = androidx.compose.ui.geometry.Size(cellW - 2f, cellH - 2f)
                )
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("温度 ${temps.minOrNull()?.toInt()}–${temps.maxOrNull()?.toInt()} K（纵）", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("pH ${phs.minOrNull()}–${phs.maxOrNull()}（横）", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        val stable = points.count { it.stable }
        val crashed = points.count { it.reason != null }
        KV("稳定 / 失稳 / 崩溃", "$stable / ${points.size - stable - crashed} / $crashed")
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Row { androidx.compose.foundation.Canvas(Modifier.width(10.dp).height(10.dp)) { drawRect(Color(0xFF4CAF50)) }; Text(" 稳定", fontSize = 10.sp) }
            Row { androidx.compose.foundation.Canvas(Modifier.width(10.dp).height(10.dp)) { drawRect(Color(0xFFFFA726)) }; Text(" 失稳", fontSize = 10.sp) }
            Row { androidx.compose.foundation.Canvas(Modifier.width(10.dp).height(10.dp)) { drawRect(Color(0xFFF44336)) }; Text(" 崩溃", fontSize = 10.sp) }
        }
        Text("以 (310 K, pH 7) 为稳定中心的扫描；绿色域为可保持折叠的环境窗口。", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// ───────────────────────── 活性口袋面板 ─────────────────────────

@Composable
fun PocketPanel(pockets: List<ProteinBiophysics.Pocket>) {
    PanelCard("活性结合口袋监测") {
        if (pockets.isEmpty()) {
            Text("未检测到埋藏口袋（序列过短或全为卷曲）", fontSize = 12.sp)
            return@PanelCard
        }
        pockets.forEach { p ->
            Column(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                KV("口袋 #${p.id}", "体积 %.1f Å³ · 成药性 %.2f".format(p.volume, p.druggability), warn = p.druggability > 0.7)
                Text(
                    "中心 (${p.center.first.toInt()}, ${p.center.second.toInt()}, ${p.center.third.toInt()}) · 表面残基 ${p.surfaceResidues.joinToString(",")}",
                    fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Text("基于接触数聚簇的几何检测；成药性 > 0.7 的口袋优先关注。", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// ───────────────────────── 突变购物车面板 ─────────────────────────

@Composable
fun MutationCartPanel(
    cart: List<Triple<Int, Char, Char>>,
    sequence: String,
    onRemove: (Int) -> Unit,
    onApply: (String) -> Unit,
    onClear: () -> Unit
) {
    PanelCard("突变分析面板（购物车 ${cart.size} 项）") {
        if (cart.isEmpty()) {
            Text("暂无突变。用「单点突变设计」或序列扫描加入突变。", fontSize = 12.sp)
            return@PanelCard
        }
        cart.forEachIndexed { idx, (pos, from, to) ->
            val ddg = ProteinBiophysics.mutationalStabilityDdg(from, pos, to)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    "$from$pos$to",
                    fontSize = 12.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium
                )
                Text(
                    "ΔΔG %+.2f kcal/mol · ${ddg.classificationZh}".format(ddg.ddG),
                    fontSize = 12.sp, fontFamily = FontFamily.Monospace,
                    color = if (ddg.ddG > 1.0) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant
                )
                TextButton(onClick = { onRemove(idx) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("移除", fontSize = 11.sp) }
            }
        }
        val mutated = ProteinBiophysics.applyMutations(sequence, cart)
        if (mutated != sequence) {
            val diff = ProteinBiophysics.comparePmf(sequence, mutated)
            val shifted = diff.count { it.type == "shifted" }
            val newPeaks = diff.count { it.type == "mut_only" }
            KV("PMF 差分", "移位峰 $shifted · 新峰 $newPeaks（MALDI 鉴定用）")
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { onApply(mutated) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("应用到序列", fontSize = 11.sp) }
            TextButton(onClick = onClear, modifier = Modifier.heightIn(min = 48.dp)) { Text("清空", fontSize = 11.sp) }
        }
    }
}

// ───────────────────────── 批量筛选面板 ─────────────────────────

@Composable
fun BatchPanel(results: List<ProteinBiophysics.MutationAnalysis>) {
    PanelCard("批量设计筛选面板（${results.size} 个突变体）") {
        if (results.isEmpty()) {
            Text("导入突变库 CSV（每行：ID, V15A + I32L）后评估。", fontSize = 12.sp)
            return@PanelCard
        }
        results.sortedByDescending { it.thermostabilityScore }.take(30).forEach { r ->
            Column(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("${r.id}  ${r.mutations}", fontSize = 12.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium)
                    Text(
                        "稳定性 ${r.thermostabilityScore}/100",
                        fontSize = 12.sp, fontFamily = FontFamily.Monospace,
                        color = if (r.thermostabilityScore >= 70) Color(0xFF4CAF50)
                        else if (r.thermostabilityScore >= 50) Color(0xFFFFA726)
                        else MaterialTheme.colorScheme.tertiary
                    )
                }
                Text(
                    "ΔpI %+.2f · GRAVY %.3f · pH7 电荷 %+.1f · 螺旋 %d%% · 无序 %.3f".format(
                        r.pIDelta, r.gravy, r.chargeAtPh7, r.helixPercent, r.disorderScore
                    ),
                    fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
