package com.spice.nativeapp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.spice.nativeapp.core.SeqRecord

/** 原版「窗口」菜单条目（配色方案在菜单内直接选择，对应 main 的内联 select） */
enum class WindowPanel(val title: String, val hint: String) {
    HISTORY("质粒编辑历史树", "回滚到之前的编辑状态"),
    LOGGER("CRT 终端活动日志", "系统与操作事件流（终端风格）"),
    SETTINGS("自定义界面风格设置", "主题、旋转、演示模式"),
    RESET("重置分栏宽度", "恢复默认视图与选择状态")
}

/** 与 main 窗口菜单「碱基配色方案」一致的 7 套特征配色 */
enum class MapTheme(val label: String) {
    MONO("标准单色"),
    ATCG("AIO 标准染色"),
    SNAPGENE("SnapGene 经典染色"),
    BENCHLING("Benchling 现代粉彩"),
    SANGER("Sanger 测序通道色"),
    PURINE("嘌呤/嘧啶化学分类色"),
    GC("GC 含量突出")
}

object MapPalette {
    var theme by mutableStateOf(MapTheme.ATCG)

    fun colorFor(type: String): Color {
        val base = when (theme) {
            MapTheme.ATCG -> when (type) {
                "CDS" -> Color(0xFF7ED957)
                "promoter" -> Color(0xFFFFC56E)
                "primer" -> Color(0xFF9FA8FF)
                "rep_origin" -> Color(0xFF7ED957)
                "misc" -> Color(0xFF69C9F0)
                else -> Color(0xFFB0BEC5)
            }
            MapTheme.MONO -> when (type) {
                "CDS" -> Color(0xFFE0E0E0)
                "promoter" -> Color(0xFFBDBDBD)
                "primer" -> Color(0xFF9E9E9E)
                else -> Color(0xFF757575)
            }
            MapTheme.SNAPGENE -> when (type) {
                "CDS" -> Color(0xFF3DA8E5)
                "promoter" -> Color(0xFF59C94B)
                "primer" -> Color(0xFFE8A33D)
                "rep_origin" -> Color(0xFF9B59B6)
                else -> Color(0xFF8E8E8E)
            }
            MapTheme.BENCHLING -> when (type) {
                "CDS" -> Color(0xFF00B3A4)
                "promoter" -> Color(0xFFF2994A)
                "primer" -> Color(0xFF6C5CE7)
                "rep_origin" -> Color(0xFF2D9CDB)
                else -> Color(0xFFB6B6B6)
            }
            MapTheme.SANGER -> when (type) {
                "CDS" -> Color(0xFF4CD964)
                "promoter" -> Color(0xFFFFD60A)
                "primer" -> Color(0xFFFF453A)
                "rep_origin" -> Color(0xFF5AC8FA)
                else -> Color(0xFF98989D)
            }
            MapTheme.PURINE -> when (type) {
                "CDS" -> Color(0xFFFF8A80)   // A/T 暖色
                "promoter" -> Color(0xFFFFC56E)
                "primer" -> Color(0xFF69C9F0) // G/C 冷色
                "rep_origin" -> Color(0xFF7ED957)
                else -> Color(0xFF9E9E9E)
            }
            MapTheme.GC -> when (type) {
                "CDS" -> Color(0xFFFF7043)    // 高 GC 暖橙突出
                "promoter" -> Color(0xFFFFAB91)
                "primer" -> Color(0xFFFFCCBC)
                "rep_origin" -> Color(0xFFE64A19)
                else -> Color(0xFFD7CCC8)
            }
        }
        return base
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WindowPanelSheet(
    panel: WindowPanel,
    undoStack: List<SeqRecord>,
    log: List<String>,
    onDismiss: () -> Unit,
    onRestore: (SeqRecord) -> Unit,
    onOpenSettings: () -> Unit,
    onResetLayout: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
            Text(panel.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(panel.hint, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            when (panel) {
                WindowPanel.HISTORY -> {
                    if (undoStack.isEmpty()) {
                        Text("暂无历史（每次编辑会记录一步，最多 20 步）", fontSize = 12.sp)
                    } else {
                        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 360.dp)) {
                            items(undoStack.reversed()) { rec ->
                                Row(
                                    Modifier.fillMaxWidth().padding(vertical = 2.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text("${rec.name} · ${rec.length} bp", fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                                    TextButton(onClick = { onRestore(rec) }, modifier = Modifier.heightIn(min = 48.dp)) {
                                        Text("回滚", fontSize = 11.sp)
                                    }
                                }
                            }
                        }
                    }
                }
                WindowPanel.LOGGER -> {
                    // CRT 终端风格：黑底荧光绿等宽字 + 光标
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .background(Color(0xFF04160A), RoundedCornerShape(8.dp))
                            .padding(10.dp)
                    ) {
                        Text(
                            "SPICE terminal — session log", fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace, color = Color(0xFF39FF14)
                        )
                        if (log.isEmpty()) {
                            Text(
                                "$ no events yet", fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace, color = Color(0xFF2FBF71)
                            )
                        } else {
                            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 340.dp)) {
                                items(log) { line ->
                                    Text(
                                        "> $line", fontSize = 11.sp,
                                        fontFamily = FontFamily.Monospace, color = Color(0xFF39FF14)
                                    )
                                }
                            }
                        }
                        Text(
                            "█", fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = Color(0xFF39FF14)
                        )
                    }
                }
                WindowPanel.SETTINGS -> {
                    Text("主题、自动旋转、演示模式在「设置」页调整。", fontSize = 12.sp)
                    TextButton(onClick = onOpenSettings, modifier = Modifier.heightIn(min = 48.dp)) { Text("打开设置") }
                }
                WindowPanel.RESET -> {
                    Text("将退出选择模式、清除选区与搜索命中，并回到环状图谱默认视图。", fontSize = 12.sp)
                    TextButton(onClick = onResetLayout, modifier = Modifier.heightIn(min = 48.dp)) { Text("重置布局") }
                }
            }
        }
    }
}
