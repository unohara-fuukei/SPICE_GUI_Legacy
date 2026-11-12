package com.spice.nativeapp.tools

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.spice.nativeapp.core.ProteinCore
import kotlin.math.cos
import kotlin.math.sin

/** 简易骨架可视化：按序列的疏水性/螺旋倾向生成 3D 折线（非 ONNX 预测） */
@Composable
fun Viewer3DScreen(modifier: Modifier = Modifier) {
    var seqText by remember {
        mutableStateOf("MKTAYIAKQRQISFVKSHFSRQLEERLGLIEVQANAGAPGDRVLLLAGLEKLLRQGGSVEPVLAALRDELAAELGAPDPAARLRAALEEAGGEP")
    }
    val seq = remember(seqText) { ProteinCore.clean(seqText).take(220) }
    var rotX by remember { mutableFloatStateOf(-0.35f) }
    var rotY by remember { mutableFloatStateOf(0.5f) }
    var zoom by remember { mutableFloatStateOf(1f) }

    val coords = remember(seq) {
        // 每残基：沿 y 轴前进，绕 y 旋转形成螺旋；每 18 残基插入一段 β 折叠（直线段）
        seq.indices.map { i ->
            val sheet = (i / 18) % 5 == 4
            val angle = if (sheet) 0.0 else i * 100.0 * Math.PI / 180.0
            val radius = if (sheet) 1.1 else 2.0
            Triple(
                radius * cos(angle),
                i * 1.5,
                radius * sin(angle)
            )
        }
    }

    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("3D 骨架预览", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text("启发式几何（螺旋 + β 片段），用于快速看序列长度与二级结构分布；不是结构预测结果。", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)

        OutlinedTextField(
            value = seqText,
            onValueChange = { seqText = it },
            label = { Text("氨基酸序列", fontSize = 12.sp) },
            modifier = Modifier.fillMaxWidth(),
            minLines = 3,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
            textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
        )

        Canvas(
            Modifier
                .fillMaxWidth()
                .height(420.dp)
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
            val pts = coords.map { (x, y, z) ->
                // 绕 y 轴旋转
                val x1 = x * cos(rotY) - z * sin(rotY)
                val z1 = x * sin(rotY) + z * cos(rotY)
                // 绕 x 轴旋转
                val y2 = y * cos(rotX) - z1 * sin(rotX)
                val z2 = y * sin(rotX) + z1 * cos(rotX)
                val scale = 26f * zoom
                val persp = 1.0 / (1.0 + (z2 + 200) / 900.0)
                Offset(
                    cx + (x1 * scale * persp).toFloat(),
                    cy + ((y2 - coords.size * 0.75) * scale * persp).toFloat()
                )
            }
            // 着色按 y 深度
            pts.zipWithNext { a, b ->
                val depth = (a.y - cy) / size.height
                val c = Color(0xFF7FE7D6).copy(alpha = (0.35f + (depth + 0.5f) * 0.6f).coerceIn(0.2f, 1f))
                drawLine(c, a, b, 2.2.dp.toPx())
            }
            pts.forEachIndexed { i, p ->
                if (i % 10 == 0) drawCircle(Color(0xFFFFC56E).copy(alpha = 0.75f), 2.2.dp.toPx(), p)
            }
            drawCircle(Color(0xFF9FA8FF), 5.dp.toPx(), pts.first())
            drawCircle(Color(0xFFFF8A80), 5.dp.toPx(), pts.last())
        }
        Text("N 端（蓝）→ C 端（红），黄色为每 10 残基标记；拖动旋转。", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("残基数 ${seq.length} · 理论分子量 ${ProteinCore.formatMass(ProteinCore.molecularWeight(seq))}", fontSize = 11.sp)
    }
}
