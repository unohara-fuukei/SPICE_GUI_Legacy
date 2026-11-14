package com.spice.nativeapp.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material3.Icon
import androidx.compose.ui.graphics.Color
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
import com.spice.nativeapp.core.Feature
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
        title = { Text(com.spice.nativeapp.core.I18n.tf("从 {v1} 导入", source.label), style = MaterialTheme.typography.titleMedium) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    when (source) {
                        SeqFetch.Source.NCBI -> com.spice.nativeapp.core.I18n.t("输入核苷酸登录号（如 NC001422.6 / NM_000546.6）")
                        SeqFetch.Source.ENSEMBL -> com.spice.nativeapp.core.I18n.t("输入 Ensembl gene/transcript ID（如 ENSG00000139618）")
                        SeqFetch.Source.UNIPROT -> com.spice.nativeapp.core.I18n.t("输入 UniProt 登录号（如 P69905）")
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
            ) { Text(com.spice.nativeapp.core.I18n.t("获取序列")) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text(com.spice.nativeapp.core.I18n.t("取消")) }
        }
    )
}

/** 添加特征注释对话框（原版「添加特征注释…」） */
@Composable
fun AddFeatureDialog(
    record: SeqRecord,
    onDismiss: () -> Unit,
    onAdd: (String, IntRange, String, String?, Boolean) -> Unit,
    initial: Feature? = null
) {
    // ── main +page.svelte:3700-3760 Feature Editor Modal 1:1 移植 ──
    // 字段：Name / Start / End / Type(select+自定义+) / color / 正向 checkbox
    // 校验：名称空 或 start>end → 拒绝（main saveFeature :2553 + toastFeatureNameRangeDesc）
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var start by remember { mutableStateOf(initial?.start?.toString() ?: "1") }
    var end by remember { mutableStateOf(initial?.end?.toString() ?: record.length.toString()) }
    // main allFeatureTypes（+page.svelte:816）顺序 verbatim
    val builtinTypes = listOf("cds", "promoter", "terminator", "ori", "primer_bind", "misc_feature", "gene", "exon", "intron", "rRNA", "tRNA", "mRNA")
    var customTypes by remember { mutableStateOf(listOf<String>()) }
    val allTypes = (builtinTypes + customTypes).distinct()
    var type by remember { mutableStateOf(initial?.type ?: "misc_feature") }
    var typeOpen by remember { mutableStateOf(false) }
    var color by remember { mutableStateOf(initial?.color ?: when (type) { "promoter" -> "#ff5d5d"; "terminator" -> "#53d769"; "ori" -> "#4cd6ff"; "cds" -> "#b48cff"; else -> null }) }
    var forward by remember { mutableStateOf((initial?.strand ?: 1) > 0) }
    var err by remember { mutableStateOf<String?>(null) }
    var showCustomType by remember { mutableStateOf(false) }
    var customTypeInput by remember { mutableStateOf("") }

    fun trySave() {
        val s = start.toIntOrNull()
        val e = end.toIntOrNull()
        if (name.isBlank() || s == null || e == null || s > e) {
            err = com.spice.nativeapp.core.I18n.t("名称不能为空且起始位置需小于结束位置")  // main toastFeatureNameRangeDesc
            return
        }
        onAdd(name.trim(), s..minOf(e, record.length), type.ifBlank { "misc_feature" }, color, forward)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(com.spice.nativeapp.core.I18n.t("特征注释编辑器"), style = MaterialTheme.typography.titleMedium) },  // main featureEditorTitle
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it; err = null }, label = { Text(com.spice.nativeapp.core.I18n.t("名称"), fontSize = 12.sp) }, placeholder = { Text(com.spice.nativeapp.core.I18n.t("特征名称..."), fontSize = 11.sp) }, singleLine = true, isError = err != null, modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = start, onValueChange = { start = it; err = null }, label = { Text(com.spice.nativeapp.core.I18n.t("起点"), fontSize = 12.sp) }, singleLine = true, modifier = Modifier.weight(1f))
                    OutlinedTextField(value = end, onValueChange = { end = it; err = null }, label = { Text(com.spice.nativeapp.core.I18n.t("终点"), fontSize = 12.sp) }, singleLine = true, modifier = Modifier.weight(1f))
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box(Modifier.weight(1f)) {
                        OutlinedTextField(
                            value = type.uppercase(), onValueChange = {}, readOnly = true,
                            label = { Text(com.spice.nativeapp.core.I18n.t("类型"), fontSize = 12.sp) }, singleLine = true,
                            trailingIcon = { Icon(Icons.Filled.ArrowDropDown, null) },
                            modifier = Modifier.fillMaxWidth().clickable { typeOpen = true }
                        )
                        DropdownMenu(expanded = typeOpen, onDismissRequest = { typeOpen = false }) {
                            allTypes.forEach { t ->
                                DropdownMenuItem(text = { Text(t.uppercase(), fontSize = 11.sp) }, onClick = { type = t; typeOpen = false })
                            }
                        }
                    }
                    // main「+」自定义类型按钮（customFeatTypePrompt）
                    OutlinedButton(onClick = { showCustomType = true }, contentPadding = PaddingValues(horizontal = 10.dp), modifier = Modifier.heightIn(min = 48.dp)) {
                        Text("+", fontWeight = FontWeight.Bold)
                    }
                }
                // 颜色：main input type=color → 预设调色板（main FEATURE_COLORS 8 色）
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(com.spice.nativeapp.core.I18n.t("颜色"), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    listOf("#ff5d5d", "#53d769", "#4cd6ff", "#b48cff", "#ffd23f", "#53d7a9", "#ff9f1c", "#5df6ff").forEach { hex ->
                        Box(
                            Modifier
                                .size(if (color == hex) 26.dp else 20.dp)
                                .background(parseHexColor(hex)!!, androidx.compose.foundation.shape.CircleShape)
                                .border(
                                    width = if (color == hex) 2.dp else 1.dp,
                                    color = if (color == hex) MaterialTheme.colorScheme.primary else Color(0xFF556070).copy(alpha = 0.4f),
                                    shape = androidx.compose.foundation.shape.CircleShape
                                )
                                .clickable { color = hex }
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Checkbox(checked = forward, onCheckedChange = { forward = it })
                    Text(com.spice.nativeapp.core.I18n.t("正向"), fontSize = 12.sp)  // main featForwardStrand
                }
                err?.let {
                    Text(it, fontSize = 10.sp, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            TextButton(modifier = Modifier.heightIn(min = 48.dp), onClick = { trySave() }) { Text(if (initial == null) com.spice.nativeapp.core.I18n.t("添加") else com.spice.nativeapp.core.I18n.t("保存")) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text(com.spice.nativeapp.core.I18n.t("取消")) }
        }
    )
    if (showCustomType) {
        AlertDialog(
            onDismissRequest = { showCustomType = false },
            title = { Text(com.spice.nativeapp.core.I18n.t("自定义类型"), style = MaterialTheme.typography.titleSmall) },
            text = {
                OutlinedTextField(
                    value = customTypeInput, onValueChange = { customTypeInput = it },
                    placeholder = { Text(com.spice.nativeapp.core.I18n.t("请输入自定特征类型名称 (e.g. enhancer, tag)"), fontSize = 10.sp) },  // main customFeatTypePrompt
                    singleLine = true, modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = { TextButton(onClick = {
                if (customTypeInput.isNotBlank()) {
                    val newType = customTypeInput.trim().lowercase()
                    customTypes = customTypes + newType
                    type = newType
                }
                customTypeInput = ""; showCustomType = false
            }) { Text(com.spice.nativeapp.core.I18n.t("确定")) } },
            dismissButton = { TextButton(onClick = { customTypeInput = ""; showCustomType = false }) { Text(com.spice.nativeapp.core.I18n.t("取消")) } }
        )
    }
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
        title = { Text(com.spice.nativeapp.core.I18n.t("序列搜索"), style = MaterialTheme.typography.titleMedium) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = q,
                    onValueChange = { q = it.uppercase().filter { c -> c.isLetter() } },
                    label = { Text(com.spice.nativeapp.core.I18n.t("模体 / 子序列"), fontSize = 12.sp) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace)
                )
                Text(com.spice.nativeapp.core.I18n.t("同时搜索反向互补链"), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = {
            TextButton(
                enabled = q.length >= 3,
                onClick = { onSearch(q) },
                modifier = Modifier.heightIn(min = 48.dp)
            ) { Text(com.spice.nativeapp.core.I18n.t("搜索")) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text(com.spice.nativeapp.core.I18n.t("取消")) }
        }
    )
}
