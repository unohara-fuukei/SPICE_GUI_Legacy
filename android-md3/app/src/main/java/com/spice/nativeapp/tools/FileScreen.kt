package com.spice.nativeapp.tools

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.spice.nativeapp.core.SeqIO
import com.spice.nativeapp.core.SeqRecord
import java.io.File

@Composable
fun FileScreen(record: SeqRecord, onImport: (SeqRecord) -> Unit, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var paste by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("支持 FASTA / GenBank / 纯序列文本") }

    val openLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            runCatching {
                ctx.contentResolver.openInputStream(uri)?.use { input ->
                    val text = input.bufferedReader().readText()
                    val rec = SeqIO.parse(text)
                    if (rec != null) {
                        onImport(rec)
                        status = com.spice.nativeapp.core.I18n.tf("已导入 {v1}（{v2} bp，{v3} 特征）", rec.name, rec.length, rec.features.size)
                    } else {
                        status = com.spice.nativeapp.core.I18n.t("文件不含可识别序列")
                    }
                }
            }.onFailure { status = com.spice.nativeapp.core.I18n.tf("读取失败：{v1}", it.message) }
        }
    }

    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(com.spice.nativeapp.core.I18n.t("序列导入 / 导出"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(status, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { openLauncher.launch(arrayOf("*/*")) }, modifier = Modifier.weight(1f)) { Text(com.spice.nativeapp.core.I18n.t("打开文件")) }
        }

        OutlinedTextField(
            value = paste,
            onValueChange = { paste = it },
            label = { Text(com.spice.nativeapp.core.I18n.t("粘贴 FASTA / GenBank / 纯序列"), fontSize = 12.sp) },
            modifier = Modifier.fillMaxWidth(),
            minLines = 5,
            textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
        )
        Button(
            onClick = {
                val rec = SeqIO.parse(paste)
                if (rec != null) {
                    onImport(rec)
                    status = com.spice.nativeapp.core.I18n.tf("已导入 {v1}（{v2} bp）", rec.name, rec.length)
                } else status = com.spice.nativeapp.core.I18n.t("无法解析，请检查格式")
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text(com.spice.nativeapp.core.I18n.t("解析并载入")) }

        Text(com.spice.nativeapp.core.I18n.tf("导出当前序列：{v1}（{v2} bp）", record.name, record.length), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = {
                    val s = SeqIO.toFasta(record)
                    clipboard.setText(AnnotatedString(s))
                    status = com.spice.nativeapp.core.I18n.tf("FASTA 已复制到剪贴板（{v1} 字符）", s.length)
                },
                modifier = Modifier.weight(1f)
            ) { Text(com.spice.nativeapp.core.I18n.t("复制 FASTA")) }
            OutlinedButton(
                onClick = {
                    val s = SeqIO.toGenBank(record)
                    clipboard.setText(AnnotatedString(s))
                    status = com.spice.nativeapp.core.I18n.tf("GenBank 已复制到剪贴板（{v1} 字符）", s.length)
                },
                modifier = Modifier.weight(1f)
            ) { Text(com.spice.nativeapp.core.I18n.t("复制 GenBank")) }
        }
        OutlinedButton(
            onClick = {
                runCatching {
                    val dir = File(ctx.filesDir, "exports").apply { mkdirs() }
                    val f = File(dir, "${record.name}.gb")
                    f.writeText(SeqIO.toGenBank(record))
                    val share = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, SeqIO.toFasta(record))
                        putExtra(Intent.EXTRA_SUBJECT, record.name)
                    }
                    ctx.startActivity(Intent.createChooser(share, com.spice.nativeapp.core.I18n.t("分享序列")))
                    status = com.spice.nativeapp.core.I18n.tf("已写入 {v1}，并已调起分享", f.absolutePath)
                }.onFailure { status = com.spice.nativeapp.core.I18n.tf("导出失败：{v1}", it.message) }
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text(com.spice.nativeapp.core.I18n.t("保存到本地并分享")) }

        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))) {
            Text(
                com.spice.nativeapp.core.I18n.t("说明：targetSdk 29 下直接读外部文件走 SAF，无需存储权限；导出文件保存在应用私有目录，通过系统分享面板发送。"),
                fontSize = 11.sp,
                modifier = Modifier.padding(12.dp)
            )
        }
    }
}
