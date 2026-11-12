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
import androidx.compose.foundation.layout.height
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
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext
import com.spice.nativeapp.core.SeqRecord
import com.spice.nativeapp.data.AttachmentStore
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
    onUpdate: (ProjectItem) -> Unit,
    modifier: Modifier = Modifier
) {
    var showSave by remember { mutableStateOf(false) }
    var draftName by remember { mutableStateOf(current.name) }
    var detail by remember { mutableStateOf<ProjectItem?>(null) }
    var noteDraft by remember { mutableStateOf("") }
    val ctx = LocalContext.current
    val pickAttachment = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val proj = detail ?: return@rememberLauncherForActivityResult
        if (uri == null) return@rememberLauncherForActivityResult
        val name = uri.lastPathSegment?.substringAfterLast('/') ?: "attachment"
        val att = AttachmentStore.add(ctx, proj.id, uri, name) ?: return@rememberLauncherForActivityResult
        onUpdate(proj.copy(attachments = proj.attachments + att, updatedAt = System.currentTimeMillis(),
            history = proj.history + "${System.currentTimeMillis()}|添加附件 ${att.name}"))
        detail = detail?.copy(attachments = detail!!.attachments + att)
    }

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
                            TextButton(onClick = { detail = p; noteDraft = p.noteDoc }) { Text("详情", fontSize = 12.sp) }
                            TextButton(onClick = { onDelete(p) }) { Text("删除", fontSize = 12.sp) }
                        }
                    }
                }
            }
        }
    }

    val d = detail
    if (d != null) {
        AlertDialog(
            onDismissRequest = { detail = null },
            title = { Text(d.name, fontSize = 14.sp) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("${d.sequence.length} bp · ${if (d.circular) "环状" else "线性"} · ${d.features.size} 特征", fontSize = 11.sp)
                    OutlinedTextField(
                        value = noteDraft,
                        onValueChange = { noteDraft = it },
                        label = { Text("实验备注", fontSize = 11.sp) },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 3
                    )
                    Text("附件（${d.attachments.size}）", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    d.attachments.forEach { a ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text(a.name, fontSize = 11.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f))
                            TextButton(onClick = {
                                val uri = AttachmentStore.shareUri(ctx, a)
                                val view = Intent(Intent.ACTION_VIEW).apply {
                                    setDataAndType(uri, ctx.contentResolver.getType(uri) ?: "*/*")
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                ctx.startActivity(Intent.createChooser(view, "打开附件"))
                            }) { Text("打开", fontSize = 10.sp) }
                            TextButton(onClick = {
                                AttachmentStore.delete(ctx, a)
                                onUpdate(d.copy(attachments = d.attachments - a, history = d.history + "${System.currentTimeMillis()}|删除附件 ${a.name}"))
                                detail = detail?.copy(attachments = detail!!.attachments - a)
                            }) { Text("删除", fontSize = 10.sp) }
                        }
                    }
                    OutlinedButton(onClick = { pickAttachment.launch(arrayOf("*/*")) }, modifier = Modifier.fillMaxWidth()) { Text("添加附件") }
                    Text("操作历史（${d.history.size}）", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    Column(Modifier.height(120.dp).verticalScroll(rememberScrollState())) {
                        d.history.reversed().forEach { h ->
                            val t = h.substringBefore("|").toLongOrNull()
                            val body = h.substringAfter("|")
                            Text(
                                (t?.let { SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(Date(it)) } ?: "") + "  " + body,
                                fontSize = 10.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    onUpdate(
                        d.copy(
                            noteDoc = noteDraft,
                            updatedAt = System.currentTimeMillis(),
                            history = d.history + "${System.currentTimeMillis()}|更新备注"
                        )
                    )
                    detail = null
                }) { Text("保存备注") }
            },
            dismissButton = {
                TextButton(onClick = { onOpen(d); detail = null }) { Text("打开此项目") }
            }
        )
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
    demoMode: Boolean,
    onDemoModeChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("外观", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        SettingSwitch("深色主题", "实验室仪器风格深色配色", darkTheme, onDarkThemeChange)
        SettingSwitch("屏幕旋转跟随传感器", "竖屏/横屏自动重排布局", autoRotate, onAutoRotateChange)
        SettingSwitch("演示模式", "全屏只读浏览当前序列，适合投屏讲解", demoMode, onDemoModeChange)

        Text("关于", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))) {
            Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("SPICE 原生版 0.6.0 (MD3)", fontSize = 13.sp, fontWeight = FontWeight.Medium)
                Text("Kotlin + Jetpack Compose + Material 3，纯原生渲染，无 WebView。", fontSize = 12.sp)
                Text("已实现：图谱/序列/酶切、蛋白性质、克隆工具、项目本地存储、照片分析、AI 对话、PDF/PNG 导出、附件与演示模式。", fontSize = 12.sp)
                Text("未实现：ONNX 权重推理、PPTX 导出、blot 多膜内参校准。", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
