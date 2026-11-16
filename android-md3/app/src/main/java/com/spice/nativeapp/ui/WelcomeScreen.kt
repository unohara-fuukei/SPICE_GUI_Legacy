package com.spice.nativeapp.ui

import androidx.compose.foundation.background
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// 入口卡片强调色（对齐 main +page.svelte 五卡配色：cyan/orange/green/yellow/purple）
private val PixCyan = Color(0xFF4CD6FF)
private val PixOrange = Color(0xFFFF9F1C)
private val PixGreen = Color(0xFF53D769)
private val PixYellow = Color(0xFFFFD23F)
private val PixPurple = Color(0xFFB48CFF)

/**
 * 对应原版根页 src/routes/+page.svelte 的欢迎工作台：
 * 标题 + 引擎说明 + 五个入口（基因 / 蛋白 / 项目 / 工具 / 设置）。
 * 入口卡片采用两列网格（2+2+1），避免单列纵排浪费纵向空间。
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
        modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)  // 统一主题背景：不露出系统窗口底色
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // 品牌化对齐（1.7.3 应用改名「生信工具箱」）：欢迎页与顶栏/桌面图标一致
        Text(
            com.spice.nativeapp.core.I18n.t("生信工具箱"),
            fontSize = 34.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground
        )
        Text(
            com.spice.nativeapp.core.I18n.t("SPICE · 序列设计与克隆工作台"),
            fontSize = 15.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
            Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    com.spice.nativeapp.core.I18n.t("引擎：Kotlin · Jetpack Compose · Material 3（原生渲染，无 WebView）"),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(versionLabel, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    com.spice.nativeapp.core.I18n.t("数据保存在本机 filesDir；SPD 凭据与 API Token 仅存本机。"),
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Text(
            com.spice.nativeapp.core.I18n.t("开始"),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground
        )

        // 两列：基因 / 蛋白
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            EntryCard(
                com.spice.nativeapp.core.I18n.t("基因工作区"),
                com.spice.nativeapp.core.I18n.t("打开序列、查看图谱、酶切、编辑与克隆工具"),
                PixCyan, onOpenGene, Modifier.weight(1f)
            )
            EntryCard(
                com.spice.nativeapp.core.I18n.t("蛋白工作区"),
                com.spice.nativeapp.core.I18n.t("翻译、性质、突变扫描与结构视图"),
                PixOrange, onOpenProtein, Modifier.weight(1f)
            )
        }
        // 两列：项目集合 / 工具集
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            EntryCard(
                com.spice.nativeapp.core.I18n.t("项目集合"),
                com.spice.nativeapp.core.I18n.t("多文档项目、附件与导出"),
                PixGreen, onOpenProjects, Modifier.weight(1f)
            )
            EntryCard(
                com.spice.nativeapp.core.I18n.t("工具集"),
                com.spice.nativeapp.core.I18n.t("凝胶、qPCR、blot、孔板、系统树、图形工作室、报告"),
                PixYellow, onOpenTools, Modifier.weight(1f)
            )
        }
        // 第五张整行（2+2+1）
        EntryCard(
            com.spice.nativeapp.core.I18n.t("设置"),
            com.spice.nativeapp.core.I18n.t("外观、SPD 身份、AI 模型与酶库"),
            PixPurple, onOpenSettings, Modifier.fillMaxWidth()
        )

        Button(
            onClick = onOpenGene,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
        ) { Text(com.spice.nativeapp.core.I18n.t("进入基因工作区"), fontSize = 14.sp) }
        OutlinedButton(onClick = onOpenSettings, modifier = Modifier.fillMaxWidth()) {
            Text(com.spice.nativeapp.core.I18n.t("先配置偏好与凭据"), fontSize = 13.sp)
        }
    }
}

@Composable
private fun EntryCard(
    title: String,
    desc: String,
    accent: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.28f),
            // 显式前景色：避免依赖 Card 隐式 contentColor，深色 AMOLED 下标题不可见
            contentColor = MaterialTheme.colorScheme.onSurface
        ),
        modifier = modifier.clickable(onClick = onClick)
    ) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = accent)
            Text(desc, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
