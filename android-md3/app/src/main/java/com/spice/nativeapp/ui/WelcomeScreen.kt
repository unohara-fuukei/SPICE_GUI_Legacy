package com.spice.nativeapp.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 对应原版根页 src/routes/+page.svelte 的欢迎工作台：
 * 标题 + 引擎说明 + 五个入口（基因 / 蛋白 / 项目 / 工具 / 设置）。
 */
@Composable
fun WelcomeScreen(
    versionLabel: String,
    onOpenGene: () -> Unit,
    onOpenProtein: () -> Unit,
    onOpenProjects: () -> Unit,
    onOpenTools: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("SPICE", fontSize = 34.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
        Text("序列设计与克隆工作台", fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
            Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("引擎：Kotlin · Jetpack Compose · Material 3（原生渲染，无 WebView）", fontSize = 12.sp)
                Text(versionLabel, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("数据保存在本机 filesDir；SPD 凭据与 API Token 仅存本机。", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        Text("开始", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        EntryCard("基因工作区", "打开序列、查看图谱、酶切、编辑与克隆工具", onOpenGene)
        EntryCard("蛋白工作区", "翻译、性质、突变扫描与结构视图", onOpenProtein)
        EntryCard("项目集合", "多文档项目、附件与导出", onOpenProjects)
        EntryCard("工具集", "凝胶、qPCR、blot、孔板、系统树、图形工作室、报告", onOpenTools)
        EntryCard("设置", "外观、SPD 身份、AI 模型与酶库", onOpenSettings)

        Button(
            onClick = onOpenGene,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
        ) { Text("进入基因工作区", fontSize = 14.sp) }
        OutlinedButton(onClick = onOpenSettings, modifier = Modifier.fillMaxWidth()) {
            Text("先配置偏好与凭据", fontSize = 13.sp)
        }
    }
}

@Composable
private fun EntryCard(title: String, desc: String, onClick: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.28f)),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)
    ) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            Text(desc, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
