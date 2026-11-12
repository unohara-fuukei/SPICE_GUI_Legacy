package com.spice.nativeapp.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.spice.nativeapp.core.SeqIO
import com.spice.nativeapp.core.SeqRecord
import java.net.HttpURLConnection
import java.net.URL

/** 原版「从 NCBI / Ensembl / UniProt 导入」的联网取序列（阻塞调用，请放在 IO 线程） */
object SeqFetch {

    enum class Source(val label: String) { NCBI("NCBI"), ENSEMBL("Ensembl"), UNIPROT("UniProt") }

    fun urlFor(source: Source, acc: String): String = when (source) {
        Source.NCBI ->
            "https://eutils.ncbi.nlm.nih.gov/entrez/eutils/efetch.fcgi?db=nuccore&id=$acc&rettype=fasta&retmode=text"
        Source.ENSEMBL ->
            "https://rest.ensembl.org/sequence/id/$acc?content-type=text/x-fasta;type=genomic"
        Source.UNIPROT -> "https://rest.uniprot.org/uniprotkb/$acc.fasta"
    }

    fun fetch(source: Source, acc: String): SeqRecord? {
        val conn = (URL(urlFor(source, acc)).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15000
            readTimeout = 20000
            setRequestProperty("Accept", "text/plain")
            setRequestProperty("User-Agent", "SPICE-GUI-Native/1.0")
        }
        return runCatching {
            if (conn.responseCode !in 200..299) return null
            val text = conn.inputStream.use { it.readBytes().decodeToString() }
            if (text.isBlank()) null
            else SeqIO.parse(text)?.copy(name = "$acc")
        }.also { conn.disconnect() }.getOrNull()
    }
}

/** 数据库导入对话框：输入 accession，取回后写入编辑器 */
@Composable
fun SeqImportDialog(
    source: SeqFetch.Source,
    onDismiss: () -> Unit,
    onSubmit: (String) -> Unit
) {
    var acc by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("从 ${source.label} 导入", style = MaterialTheme.typography.titleMedium) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    when (source) {
                        SeqFetch.Source.NCBI -> "输入核苷酸登录号（如 NC001422.6 / NM_000546.6）"
                        SeqFetch.Source.ENSEMBL -> "输入 Ensembl gene/transcript ID（如 ENSG00000139618）"
                        SeqFetch.Source.UNIPROT -> "输入 UniProt 登录号（如 P69905）"
                    },
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = acc,
                    onValueChange = { acc = it.trim() },
                    label = { Text("Accession", fontSize = 12.sp) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace)
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = acc.isNotBlank(),
                onClick = { onSubmit(acc) },
                modifier = Modifier.heightIn(min = 48.dp)
            ) { Text("获取序列") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text("取消") }
        }
    )
}

/** 添加特征注释对话框（原版「添加特征注释…」） */
@Composable
fun AddFeatureDialog(
    record: SeqRecord,
    onDismiss: () -> Unit,
    onAdd: (String, IntRange, String) -> Unit
) {
    var name by remember { mutableStateOf("新特征") }
    var start by remember { mutableStateOf("1") }
    var end by remember { mutableStateOf(record.length.toString()) }
    var type by remember { mutableStateOf("misc") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("添加特征注释", style = MaterialTheme.typography.titleMedium) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("名称", fontSize = 12.sp) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = start, onValueChange = { start = it }, label = { Text("起点", fontSize = 12.sp) }, singleLine = true, modifier = Modifier.weight(1f))
                    OutlinedTextField(value = end, onValueChange = { end = it }, label = { Text("终点", fontSize = 12.sp) }, singleLine = true, modifier = Modifier.weight(1f))
                }
                OutlinedTextField(
                    value = type,
                    onValueChange = { type = it },
                    label = { Text("类型（CDS / promoter / primer / misc）", fontSize = 12.sp) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                modifier = Modifier.heightIn(min = 48.dp),
                onClick = {
                    val s = start.toIntOrNull() ?: 1
                    val e = end.toIntOrNull() ?: record.length
                    if (e > s) onAdd(name.ifBlank { "特征" }, s..minOf(e, record.length), type.ifBlank { "misc" })
                }
            ) { Text("添加") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text("取消") }
        }
    )
}

/** 序列搜索对话框（原版「序列搜索」） */
@Composable
fun SequenceSearchDialog(
    onDismiss: () -> Unit,
    onSearch: (String) -> Unit
) {
    var q by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("序列搜索", style = MaterialTheme.typography.titleMedium) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = q,
                    onValueChange = { q = it.uppercase().filter { c -> c.isLetter() } },
                    label = { Text("模体 / 子序列", fontSize = 12.sp) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace)
                )
                Text("同时搜索反向互补链", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = {
            TextButton(
                enabled = q.length >= 3,
                onClick = { onSearch(q) },
                modifier = Modifier.heightIn(min = 48.dp)
            ) { Text("搜索") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text("取消") }
        }
    )
}
