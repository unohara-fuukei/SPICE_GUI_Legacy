package com.spice.nativeapp.ui

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DragIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

/**
 * 可拖拽浮动窗口（对齐 main 的 `.floating-panel.pix-panel` + `use:drag={'.panel-header'}`）：
 * - 仅 panel-header 可拖动整窗（main 语义一致）
 * - 标题右侧 [X] 关闭按钮（main 同款红色 pix-btn-reset）
 * - absolute 悬浮于当前页面之上，带阴影与描边
 *
 * 需在 Box 作用域内使用。
 */
@Composable
fun FloatingPanel(
    title: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    initialX: Dp = 16.dp,
    initialY: Dp = 72.dp,
    width: Dp = 330.dp,
    maxHeight: Dp = 480.dp,
    content: @Composable ColumnScope.() -> Unit
) {
    val density = LocalDensity.current
    var xPx by remember { mutableFloatStateOf(with(density) { initialX.toPx() }) }
    var yPx by remember { mutableFloatStateOf(with(density) { initialY.toPx() }) }

    // 返回手势优先关闭浮动窗口（对齐 main 桌面端 [X]/Esc 的层级语义）
    androidx.activity.compose.BackHandler { onDismiss() }

    Surface(
        modifier = modifier
            .offset { IntOffset(xPx.roundToInt(), yPx.roundToInt()) }
            .sizeIn(maxWidth = width, maxHeight = maxHeight)
            .graphicsLayer(shadowElevation = 12f),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        tonalElevation = 6.dp,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(Modifier.fillMaxWidth()) {
            // panel-header（对齐 main：拖拽手柄 + 标题 + [X]）
            Row(
                Modifier
                    .fillMaxWidth()
                    .pointerInput(Unit) {
                        detectDragGestures { change, dragAmount ->
                            change.consume()
                            xPx = (xPx + dragAmount.x).coerceAtLeast(0f)
                            yPx = (yPx + dragAmount.y).coerceAtLeast(0f)
                        }
                    }
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Filled.DragIndicator, null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(end = 4.dp)
                )
                Text(
                    title,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f)
                )
                TextButton(
                    onClick = onDismiss,
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                ) {
                    Text("[X]", fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
                }
            }
            androidx.compose.material3.HorizontalDivider()
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp)
                    .verticalScroll(rememberScrollState()),
                content = content
            )
        }
    }
}
