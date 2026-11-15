package com.spice.nativeapp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import com.spice.nativeapp.core.I18n

/**
 * 上次运行原生崩溃诊断浮层（1:1 对齐 main `+layout.svelte:90-98` 的
 * `.crash-report-overlay`）：fixed 于底部、左右/底 8px、max-height 45vh，
 * 深红底 #2a1015、红边 #ff5555；头部 #3a151c + 红底边；标题 #ff8888；
 * 堆栈 #ffb0b0、等宽、前 1500 字符、可滚动；右侧 `[X]` 关闭。
 */
@Composable
fun CrashReportOverlay(
    report: String,
    onDismiss: () -> Unit
) {
    val maxHeight = (LocalConfiguration.current.screenHeightDp * 0.45).dp
    Column(
        Modifier
            .fillMaxWidth()
            .heightIn(max = maxHeight)
            .clip(RoundedCornerShape(6.dp))
            .background(Color(0xFF2a1015))
            .border(2.dp, Color(0xFFff5555), RoundedCornerShape(6.dp))
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .background(Color(0xFF3a151c))
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                I18n.t("SPICE 检测到上次运行的原生崩溃"),
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFFff8888),
                modifier = Modifier.weight(1f)
            )
            TextButton(
                onClick = onDismiss,
                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)
            ) {
                Text("[X]", fontSize = 11.sp, color = Color(0xFFff8888))
            }
        }
        HorizontalDivider(thickness = 1.dp, color = Color(0xFFff5555))
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 8.dp, vertical = 6.dp)
        ) {
            Text(
                report.take(1500),
                fontSize = 10.sp,
                lineHeight = 14.sp,
                color = Color(0xFFffb0b0),
                fontFamily = FontFamily.Monospace
            )
        }
    }
}
