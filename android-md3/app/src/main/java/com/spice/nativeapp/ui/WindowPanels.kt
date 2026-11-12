package com.spice.nativeapp.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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

/** 原版「窗口」菜单条目 */
enum class WindowPanel(val title: String, val hint: String) {
    HISTORY("编辑历史树", "回滚到之前的编辑状态"),
    LOGGER("操作日志", "本次会话的操作记录"),
    SETTINGS("设置面板", "主题、旋转、演示模式"),
    RESET("重置布局", "恢复默认视图与选择状态"),
    THEME("配色主题", "碱基/特征配色方案")
}

/** 与 SnapGene / Benchling / Sanger 等视觉风格对应的特征配色 */
enum class MapTheme(val label: String) {
    ATCG("ATCG（默认）"),
    MONO("单色"),
    SNAPGENE("SnapGene 风格"),
    BENCHLING("Benchling 风格"),
    SANGER("Sanger 风格"),
    PURINE("嘌呤/嘧啶")
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
                    if (log.isEmpty()) Text("本次会话暂无操作记录", fontSize = 12.sp)
                    else LazyColumn(Modifier.fillMaxWidth().heightIn(max = 360.dp)) {
                        items(log) { line ->
                            Text(line, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                        }
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
                WindowPanel.THEME -> {
                    Text("当前：${MapPalette.theme.label}", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    MapTheme.values().forEach { t ->
                        TextButton(
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                            onClick = { MapPalette.theme = t }
                        ) {
                            Text(
                                (if (t == MapPalette.theme) "✓ " else "   ") + t.label,
                                fontSize = 12.sp,
                                color = MapPalette.colorFor("CDS")
                            )
                        }
                    }
                }
            }
        }
    }
}
