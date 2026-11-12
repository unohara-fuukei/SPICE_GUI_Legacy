package com.spice.nativeapp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.spice.nativeapp.core.SeqRecord
import com.spice.nativeapp.data.ProjectItem
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun ProjectScreen(
    projects: List<ProjectItem>,
    current: SeqRecord,
    onSaveCurrent: (String) -> Unit,
    onOpen: (ProjectItem) -> Unit,
    onDelete: (ProjectItem) -> Unit,
    modifier: Modifier = Modifier
) {
    var showSave by remember { mutableStateOf(false) }
    var draftName by remember { mutableStateOf(current.name) }

    Column(modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("项目（${projects.size}）", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Button(onClick = { draftName = current.name; showSave = true }) { Text("保存当前") }
        }
        Text(
            "${current.name} · ${current.length} bp · ${current.circular.let { if (it) "环状" else "线性" }}",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        if (projects.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("暂无项目，点右上「保存当前」把当前序列存入本地", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(projects, key = { it.id }) { p ->
                    Card(
                        Modifier.fillMaxWidth().clickable { onOpen(p) },
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
                    ) {
                        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                Modifier
                                    .size(36.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)),
                                contentAlignment = Alignment.Center
                            ) { Text(if (p.circular) "○" else "—", fontSize = 16.sp, color = MaterialTheme.colorScheme.primary) }
                            Column(Modifier.weight(1f).padding(start = 10.dp)) {
                                Text(p.name, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                                Text(
                                    "${p.sequence.length} bp · ${SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).format(Date(p.updatedAt))}",
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                if (p.note.isNotBlank()) Text(p.note, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            TextButton(onClick = { onDelete(p) }) { Text("删除", fontSize = 12.sp) }
                        }
                    }
                }
            }
        }
    }

    if (showSave) {
        AlertDialog(
            onDismissRequest = { showSave = false },
            title = { Text("保存为项目") },
            text = {
                OutlinedTextField(
                    value = draftName,
                    onValueChange = { draftName = it },
                    label = { Text("项目名称") },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(onClick = { onSaveCurrent(draftName); showSave = false }) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { showSave = false }) { Text("取消") } }
        )
    }
}

@Composable
fun SettingsScreen(
    darkTheme: Boolean,
    onDarkThemeChange: (Boolean) -> Unit,
    autoRotate: Boolean,
    onAutoRotateChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("外观", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        SettingSwitch("深色主题", "实验室仪器风格深色配色", darkTheme, onDarkThemeChange)
        SettingSwitch("屏幕旋转跟随传感器", "竖屏/横屏自动重排布局", autoRotate, onAutoRotateChange)

        Text("关于", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))) {
            Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("SPICE 原生版 0.1.0 (MD3)", fontSize = 13.sp, fontWeight = FontWeight.Medium)
                Text("Kotlin + Jetpack Compose + Material 3，纯原生渲染，无 WebView。", fontSize = 12.sp)
                Text("已实现：环状/线性质粒图谱、序列视图与酶切位点、蛋白理化性质、PCR 引物与 Gibson/双酶切计算、项目本地存储。", fontSize = 12.sp)
                Text("未实现：ONNX 蛋白结构预测、AI Copilot、图像类工具（图形工作室/标注/凝胶成像）。", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        FilledTonalButton(onClick = {}, modifier = Modifier.fillMaxWidth()) {
            Text("数据全部保存在本机 filesDir，无网络依赖")
        }
    }
}

@Composable
private fun SettingSwitch(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))) {
        Row(
            Modifier.fillMaxWidth().padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                Text(subtitle, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = checked, onCheckedChange = onChange)
        }
    }
}
