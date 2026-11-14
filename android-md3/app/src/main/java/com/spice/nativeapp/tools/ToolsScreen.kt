package com.spice.nativeapp.tools

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.spice.nativeapp.core.SeqRecord

private enum class Tool(val title: String, val desc: String) {
    GEL("凝胶电泳", "酶切片段迁移模拟"),
    QPCR("qPCR", "ΔΔCt 相对定量"),
    BLOT("Western blot", "泳道强度与归一化"),
    PLATE("96 孔板", "布板与计数"),
    TREE("系统发育树工具", "解析 Newick 文件，美化并绘制矩形或环形系统发育树，按分组着色并导出出版级图片"),
    VIEW3D("3D 骨架", "螺旋/β 片段预览"),
    FIGURE("图形工作室", "示意图拼版"),
    ANNOTATE("图像标注", "面板与标尺"),
    COLONY("菌落计数", "网格计数与统计"),
    PHOTO("照片分析", "凝胶/菌落自动识别"),
    REPORT("PDF 报告", "序列报告导出"),
    FILE("文件", "FASTA/GenBank 导入导出"),
    AI("实验助手", "本地规则建议")
}

@Composable
fun ToolsScreen(record: SeqRecord, onImport: (SeqRecord) -> Unit, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf<Tool?>(null) }

    Column(modifier.fillMaxSize()) {
        val current = open
        if (current == null) {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("工具", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text("与原版同名工具的本地实现；图像识别类依赖相片的模块提供手工录入/示意版本。", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Tool.values().toList().chunked(2).forEach { pair ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        pair.forEach { t ->
                            Card(
                                Modifier.weight(1f).clickable { open = t },
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
                            ) {
                                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                    Text(t.title, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                    Text(t.desc, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                        if (pair.size == 1) Column(Modifier.weight(1f)) {}
                    }
                }
            }
        } else {
            Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                TextButton(onClick = { open = null }) {
                    Icon(Icons.Filled.ArrowBack, contentDescription = "返回", modifier = Modifier.padding(end = 4.dp))
                    Text("工具")
                }
                Text(current.title, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            when (current) {
                Tool.GEL -> GelScreen(record)
                Tool.QPCR -> QpcrScreen()
                Tool.BLOT -> BlotScreen()
                Tool.PLATE -> PlateScreen()
                Tool.TREE -> TreeScreen()
                Tool.VIEW3D -> Viewer3DScreen()
                Tool.PHOTO -> PhotoScreen()
                Tool.REPORT -> ReportScreen(record)
                Tool.FILE -> FileScreen(record, onImport)
                Tool.AI -> AiScreen(record)
                Tool.FIGURE -> FigureScreen()
                Tool.ANNOTATE -> AnnotateScreen()
                Tool.COLONY -> ColonyScreen()
            }
        }
    }
}
