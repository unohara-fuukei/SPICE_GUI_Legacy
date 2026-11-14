package com.spice.nativeapp.tools

import android.content.ContentValues
import android.content.Intent
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.os.Environment
import android.provider.MediaStore
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.spice.nativeapp.core.Enzymes
import com.spice.nativeapp.core.ProteinCore
import com.spice.nativeapp.core.SeqRecord
import java.io.File

/** 生成 PDF 报告：序列摘要 + 特征表 + 酶切位点 + 翻译预览 */
object Report {

    fun build(record: SeqRecord): PdfDocument {
        val doc = PdfDocument()
        val page = doc.startPage(PdfDocument.PageInfo.Builder(595, 842, 1).create())
        val c = page.canvas
        val title = Paint().apply { textSize = 18f; isFakeBoldText = true; color = android.graphics.Color.BLACK }
        val h2 = Paint().apply { textSize = 13f; isFakeBoldText = true }
        val body = Paint().apply { textSize = 10f }
        val mono = Paint().apply { textSize = 9f; typeface = android.graphics.Typeface.MONOSPACE }

        var y = 52f
        c.drawText(com.spice.nativeapp.core.I18n.t("SPICE 序列报告"), 40f, y, title); y += 26f
        c.drawText("${record.name} · ${record.length} bp · ${if (record.circular) "环状" else "线性"} · GC ${"%.1f".format(record.gc)}%", 40f, y, body); y += 24f

        c.drawText(com.spice.nativeapp.core.I18n.t("特征注释"), 40f, y, h2); y += 16f
        record.features.take(18).forEach { f ->
            c.drawText("${f.name}  ${f.start}–${f.end}  ${f.type}${if (f.strand < 0) " (−)" else ""}", 48f, y, body)
            y += 14f
        }
        if (record.features.size > 18) { c.drawText(com.spice.nativeapp.core.I18n.tf("… 其余 {v1} 条见应用内", record.features.size - 18), 48f, y, body); y += 14f }
        y += 10f

        c.drawText(com.spice.nativeapp.core.I18n.t("常用酶切位点"), 40f, y, h2); y += 16f
        Enzymes.common.sorted().forEach { name ->
            val e = Enzymes.byName(name) ?: return@forEach
            val sites = com.spice.nativeapp.core.Bio.cutSites(record.sequence, e, record.circular)
            c.drawText("$name (${e.site})：${if (sites.isEmpty()) "无切点" else sites.take(8).joinToString(", ") + if (sites.size > 8) "…" else ""}", 48f, y, body)
            y += 13f
        }
        y += 10f

        c.drawText(com.spice.nativeapp.core.I18n.t("翻译预览（+1 框，前 20 aa）"), 40f, y, h2); y += 16f
        val aa = com.spice.nativeapp.core.Bio.translate(record.sequence, 0).take(20)
        c.drawText(aa, 48f, y, mono); y += 18f
        val mw = ProteinCore.molecularWeight(aa)
        c.drawText(com.spice.nativeapp.core.I18n.t("（示意翻译，非注释 CDS）"), 48f, y, body); y += 20f

        c.drawText(com.spice.nativeapp.core.I18n.t("由 SPICE 原生版生成 · 数据仅供实验记录参考"), 40f, 800f, body.also { it.color = android.graphics.Color.GRAY })
        doc.finishPage(page)
        return doc
    }

    /** 写入 MediaStore Downloads（API29）或应用目录，返回可分享的 URI */
    fun save(ctx: android.content.Context, record: SeqRecord, doc: PdfDocument): Pair<File, android.net.Uri?> {
        val name = "${record.name.replace(Regex("[^\\w\\u4e00-\\u9fa5-]"), "_")}_report.pdf"
        val tmp = File(ctx.filesDir, name)
        tmp.outputStream().use { doc.writeTo(it) }
        doc.close()
        if (android.os.Build.VERSION.SDK_INT < 29) return tmp to null
        return runCatching {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, "application/pdf")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/SPICE")
            }
            val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            if (uri != null) {
                ctx.contentResolver.openOutputStream(uri)?.use { out -> tmp.inputStream().use { it.copyTo(out) } }
                tmp to uri
            } else tmp to null
        }.getOrDefault(tmp to null)
    }
}

@Composable
fun ReportScreen(record: SeqRecord, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    var status by remember { mutableStateOf("导出内容：序列摘要、特征表、常用酶切位点、翻译预览") }

    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(com.spice.nativeapp.core.I18n.t("PDF 报告导出"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(status, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(com.spice.nativeapp.core.I18n.tf("{v1} · {v2} bp · {v3} 个特征", record.name, record.length, record.features.size), fontSize = 12.sp)

        Button(
            onClick = {
                runCatching {
                    val doc = Report.build(record)
                    val (file, uri) = Report.save(ctx, record, doc)
                    status = com.spice.nativeapp.core.I18n.tf("已生成：{v1}（{v2} KB）", file.name, file.length() / 1024)
                    if (uri != null) {
                        val share = Intent(Intent.ACTION_SEND).apply {
                            type = "application/pdf"
                            putExtra(Intent.EXTRA_STREAM, uri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        ctx.startActivity(Intent.createChooser(share, com.spice.nativeapp.core.I18n.t("分享报告")))
                    }
                }.onFailure { status = com.spice.nativeapp.core.I18n.tf("导出失败：{v1}", it.message) }
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text(com.spice.nativeapp.core.I18n.t("生成并分享 PDF")) }

        OutlinedButton(
            onClick = {
                runCatching {
                    val doc = Report.build(record)
                    val (file, _) = Report.save(ctx, record, doc)
                    status = com.spice.nativeapp.core.I18n.tf("已保存：{v1}", file.absolutePath)
                }.onFailure { status = com.spice.nativeapp.core.I18n.tf("失败：{v1}", it.message) }
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text(com.spice.nativeapp.core.I18n.t("仅保存到应用目录")) }

        Text(
            com.spice.nativeapp.core.I18n.t("说明：原生版用 android.graphics.pdf 直接排版，不依赖 WebView 的 PDF 引擎；版式为 A4 单页，超出内容自动截断。"),
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
