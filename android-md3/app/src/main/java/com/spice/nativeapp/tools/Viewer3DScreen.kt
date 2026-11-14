package com.spice.nativeapp.tools

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.spice.nativeapp.core.ProteinCore
import com.spice.nativeapp.core.Structure
import kotlin.math.cos
import kotlin.math.sin

/**
 * 3D 视图。原版用 MolStar 渲染 PDB/mmCIF；原生版不引 JS 运行时，
 * 改为解析真实结构的 Cα 轨迹后自绘（导入文件即真实坐标），并保留序列启发式预览作为兜底。
 */
@Composable
fun Viewer3DScreen(modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    var seqText by remember {
        mutableStateOf("MKTAYIAKQRQISFVKSHFSRQLEERLGLIEVQANAGAPGDRVLLLAGLEKLLRQGGSVEPVLAALRDELAAELGAPDPAARLRAALEEAGGEP")
    }
    var parsed by remember { mutableStateOf<Structure.Parsed?>(null) }
    var note by remember { mutableStateOf<String?>(null) }
    val seq = remember(seqText) { ProteinCore.clean(seqText).take(400) }
    var rotX by remember { mutableFloatStateOf(-0.35f) }
    var rotY by remember { mutableFloatStateOf(0.5f) }
    var zoom by remember { mutableFloatStateOf(1f) }

    val openLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            val text = runCatching {
                ctx.contentResolver.openInputStream(uri)?.bufferedReader()?.readText() ?: ""
            }.getOrNull() ?: ""
            val name = uri.lastPathSegment ?: "structure"
            val p = Structure.parse(text, name)
            parsed = p
            note = p.note ?: com.spice.nativeapp.core.I18n.tf("已载入 {v1} 个 Cα 原子（{v2}）", p.atoms.size, p.source)
        }
    }

    // 坐标来源：真实结构优先，否则启发式螺旋
    val synthetic = remember(seq) {
        seq.indices.map { i ->
            val sheet = (i / 18) % 5 == 4
            val angle = if (sheet) 0.0 else i * 100.0 * Math.PI / 180.0
            val radius = if (sheet) 1.1 else 2.0
            Triple(radius * cos(angle), i * 1.5, radius * sin(angle))
        }
    }

    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(com.spice.nativeapp.core.I18n.t("3D 结构视图"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(
            if (parsed != null) com.spice.nativeapp.core.I18n.t("已载入真实结构，按 Cα 轨迹绘制；拖动旋转、双指缩放用下方滑杆。")
            else com.spice.nativeapp.core.I18n.t("当前为启发式几何（螺旋 + β 片段），用于快速看序列分布；导入 .pdb/.cif 即切换为真实坐标。"),
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { openLauncher.launch("*/*") }, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(com.spice.nativeapp.core.I18n.t("导入 PDB / mmCIF"), fontSize = 12.sp)
            }
            OutlinedButton(
                onClick = {
                    val coords = parsed?.let { p ->
                        val flat = FloatArray(p.atoms.size * 3)
                        p.atoms.forEachIndexed { i, a -> flat[i * 3] = a.x; flat[i * 3 + 1] = a.y; flat[i * 3 + 2] = a.z }
                        flat
                    }
                    val cif = Structure.buildMmcif(
                        sequence = seq.ifEmpty { "MKTAYIAKQRQISFVKSHFSRQ" },
                        coords = coords,
                        modelName = "spice_native",
                        note = if (coords == null) com.spice.nativeapp.core.I18n.t("Cα 直链占位（未导入结构）") else com.spice.nativeapp.core.I18n.t("来自导入结构")
                    )
                    val f = java.io.File(ctx.filesDir, "exports/model_${System.currentTimeMillis()}.cif")
                    f.parentFile?.mkdirs()
                    f.writeText(cif)
                    note = com.spice.nativeapp.core.I18n.tf("已导出 mmCIF：{v1}（{v2} 行）", f.absolutePath, cif.lines().size)
                },
                modifier = Modifier.heightIn(min = 48.dp)
            ) { Text(com.spice.nativeapp.core.I18n.t("导出 mmCIF"), fontSize = 12.sp) }
        }
        note?.let { Text(it, fontSize = 11.sp, fontFamily = FontFamily.Monospace) }

        OutlinedTextField(
            value = seqText,
            onValueChange = { seqText = it },
            label = { Text(com.spice.nativeapp.core.I18n.t("氨基酸序列（合成预览用）"), fontSize = 12.sp) },
            modifier = Modifier.fillMaxWidth(),
            minLines = 2,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
            textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
        )

        Canvas(
            Modifier
                .fillMaxWidth()
                .height(420.dp)
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

            // 统一坐标：真实结构做居中 + 归一化，合成预览按原比例
            val raw: List<Triple<Float, Float, Float>>
            val scaleBase: Float
            val yShift: Float
            if (parsed != null && parsed!!.atoms.isNotEmpty()) {
                val p = parsed!!
                val c = p.center()
                raw = p.atoms.map { Triple(it.x - c.first.toFloat(), it.y - c.second.toFloat(), it.z - c.third.toFloat()) }
                val maxR = raw.maxOf { kotlin.math.sqrt(it.first.toDouble() * it.first + it.second.toDouble() * it.second + it.third.toDouble() * it.third) }.toFloat()
                scaleBase = (minOf(size.width, size.height) * 0.40f / maxR.coerceAtLeast(1f))
                yShift = 0f
            } else {
                raw = synthetic.map { Triple(it.first.toFloat(), it.second.toFloat(), it.third.toFloat()) }
                scaleBase = (size.height * 0.80f) / (synthetic.size.toFloat() * 1.5f).coerceAtLeast(1f)
                yShift = synthetic.size * 0.75f
            }

            val pts = raw.map { (x, y, z) ->
                val x1 = x * cos(rotY.toDouble()).toFloat() - z * sin(rotY.toDouble()).toFloat()
                val z1 = x * sin(rotY.toDouble()).toFloat() + z * cos(rotY.toDouble()).toFloat()
                val y2 = y * cos(rotX.toDouble()).toFloat() - z1 * sin(rotX.toDouble()).toFloat()
                val z2 = y * sin(rotX.toDouble()).toFloat() + z1 * cos(rotX.toDouble()).toFloat()
                val persp = 1f / (1f + z2 / (raw.maxOf { kotlin.math.abs(it.first) } + 40f))
                Offset(
                    cx + x1 * scaleBase * zoom,
                    cy + (y2 - yShift) * scaleBase * zoom * persp
                )
            }
            pts.zipWithNext { a, b ->
                val depth = (a.y - cy) / size.height
                val c = Color(0xFF7FE7D6).copy(alpha = (0.35f + (depth + 0.5f) * 0.6f).coerceIn(0.2f, 1f))
                drawLine(c, a, b, if (parsed != null) 1.8.dp.toPx() else 2.2.dp.toPx())
            }
            pts.forEachIndexed { i, p ->
                val stride = if (pts.size > 300) 40 else 10
                if (i % stride == 0) drawCircle(Color(0xFFFFC56E).copy(alpha = 0.75f), 2.2.dp.toPx(), p)
            }
            if (pts.isNotEmpty()) {
                drawCircle(Color(0xFF9FA8FF), 5.dp.toPx(), pts.first())
                drawCircle(Color(0xFFFF8A80), 5.dp.toPx(), pts.last())
            }
        }
        Text(com.spice.nativeapp.core.I18n.t("N 端（蓝）→ C 端（红），黄色为残基标记；拖动旋转。"), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { zoom = (zoom - 0.2f).coerceAtLeast(0.4f) }, modifier = Modifier.heightIn(min = 48.dp)) { Text(com.spice.nativeapp.core.I18n.t("缩小")) }
            OutlinedButton(onClick = { zoom = (zoom + 0.2f).coerceAtMost(3f) }, modifier = Modifier.heightIn(min = 48.dp)) { Text(com.spice.nativeapp.core.I18n.t("放大")) }
        }
        Text(
            if (parsed != null) com.spice.nativeapp.core.I18n.tf("残基数 {v1} · 来源 {v2} · 链 {v3}", parsed!!.atoms.size, parsed!!.source, parsed!!.atoms.map { it.chain }.distinct().size)
            else com.spice.nativeapp.core.I18n.tf("残基数 {v1} · 理论分子量 {v2}", seq.length, ProteinCore.formatMass(ProteinCore.molecularWeight(seq))),
            fontSize = 11.sp
        )
    }
}
