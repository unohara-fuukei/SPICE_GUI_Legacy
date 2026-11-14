package com.spice.nativeapp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FileDownload
import com.spice.nativeapp.core.BatchEngine
import com.spice.nativeapp.core.I18n
import com.spice.nativeapp.core.CollectionIO

/**
 * 项目集合工作空间（CollectionsBatchPanel.svelte 492 行 1:1 移植）。
 *
 * main 形态：文件菜单 menuFileCollections toggle 浮动面板（right:310 top:120 w:565）；
 * 表格行=多选 checkbox（双击载入，触屏另有行内载入按钮）；
 * 批处理 = DpnI 批量消化 + 高通量引物生成；ZIP 导入导出走后端（Android 用
 * java.util.zip 按 Rust 语义实现，README 披露）。
 * toast → 面板内 status 行（块 2 起惯例）。
 */
data class ColItem(
    val id: String,
    val name: String,
    val size: Int,
    val format: String,
    val tags: List<String>,
    val copyNumber: String,
    val resistance: String,
    val seq: String
)

@Composable
fun CollectionsBatchPanel(
    items: List<ColItem>,
    onItemsChange: (List<ColItem>) -> Unit,
    onLoadToWorkspace: (seq: String, name: String) -> Unit,
    onLog: (List<String>) -> Unit,
    onPickSeqFile: () -> Unit,
    onPickZip: () -> Unit,
    onExportZip: (entries: List<Pair<String, String>>) -> Unit,
    onDismiss: () -> Unit
) {
    val t: (String) -> String = { I18n.t(it) }
    var colSearch by remember { mutableStateOf("") }
    var colSelectedTag by remember { mutableStateOf("All") }
    var selectedFiles by remember { mutableStateOf(listOf<String>()) }
    var batchDpnIResults by remember { mutableStateOf(listOf<Triple<String, Int, String>>()) }
    var batchPrimerResults by remember { mutableStateOf(listOf<List<String>>()) }
    var batchResultsType by remember { mutableStateOf("none") } // none | dpni | primer
    var status by remember { mutableStateOf<String?>(null) }
    var statusGood by remember { mutableStateOf(true) }
    val clipboard = LocalClipboardManager.current

    fun toast(typeOk: Boolean, title: String, text: String = "") {
        statusGood = typeOk
        status = title + if (text.isNotEmpty()) "：$text" else ""
    }

    val colAllTags = remember(items) {
        val tags = linkedSetOf<String>()
        items.forEach { it.tags.forEach { tag -> tags.add(tag) } }
        listOf("All") + tags
    }
    val filteredCollections = remember(items, colSearch, colSelectedTag) {
        items.filter { item ->
            val q = colSearch.lowercase()
            val matchSearch = item.name.lowercase().contains(q) ||
                item.copyNumber.lowercase().contains(q) ||
                item.resistance.lowercase().contains(q)
            val matchTag = colSelectedTag == "All" || item.tags.contains(colSelectedTag)
            matchSearch && matchTag
        }
    }

    fun toggleSelectFile(name: String) {
        selectedFiles = if (selectedFiles.contains(name)) selectedFiles.filter { it != name } else selectedFiles + name
    }

    fun loadToWorkspace(item: ColItem) {
        onLoadToWorkspace(item.seq, item.name.substringBefore('.'))
        onLog(listOf(I18n.tf("[Collection] 双击载入序列: {v1} ({v2} bp)", item.name, item.size)))
        toast(true, t("载入成功"), item.name)
    }

    fun deleteCollectionFile(id: String) {
        val item = items.find { it.id == id } ?: return
        onItemsChange(items.filter { it.id != id })
        selectedFiles = selectedFiles.filter { it != item.name }
        onLog(listOf(I18n.tf("[Collection] 已从集合工作区中删除文件: {v1}", item.name)))
        toast(true, t("删除成功"), I18n.t("已删除 {name}").replace("{name}", item.name))
    }

    fun handleBatchDpnI() {
        if (selectedFiles.isEmpty()) {
            toast(false, t("批处理操作"), t("请选择至少一个文件进行 DpnI 消化"))
            return
        }
        onLog(listOf(I18n.tf("[Batch] 正在对 {v1} 个质粒文件运行 DpnI 酶切自适应扫描...", selectedFiles.size)))
        val selectedSeqs = items.filter { it.name in selectedFiles }
            .map { BatchEngine.BatchSeq(sequence = it.seq, name = it.name, circular = true) }
        val results = CollectionIO.batchDigest(selectedSeqs, listOf("DpnI"))
        batchDpnIResults = results.map { res ->
            Triple(
                res.sequenceName,
                res.cutsitesCount,
                res.cutsites.joinToString(", ") { (it.topSnipPosition + 1).toString() }.ifEmpty { t("无切点") }
            )
        }
        batchResultsType = "dpni"
        onLog(results.map { I18n.tf("质粒 {v1} 消化结果: 找到 {v2} 个 DpnI (GATC) 甲基化切点！", it.sequenceName, it.cutsitesCount) })
        toast(true, t("批量 DpnI 消化完成"), I18n.tf("成功分析了 {v1} 个质粒", selectedFiles.size))
    }

    fun handleBatchPrimerDesign() {
        if (selectedFiles.isEmpty()) {
            toast(false, t("批处理操作"), t("请选择至少一个文件设计引物"))
            return
        }
        onLog(listOf(I18n.tf("[Batch] 正在对 {v1} 个模板运行高通量 PCR 引物自动生成...", selectedFiles.size)))
        val selectedSeqs = items.filter { it.name in selectedFiles }
            .map { BatchEngine.BatchSeq(sequence = it.seq, name = it.name, circular = true) }
        val results = CollectionIO.batchDesignPrimers(selectedSeqs, 0, 100, targetTm = 60.0)
        batchPrimerResults = results.map { (name, p) ->
            // 主版 productLength 字段不存在 → 恒走 || 估算（fwd.len + rev.len）
            listOf(name, p.forward.sequence, jsFixed1(p.forward.tm), p.reverse.sequence, jsFixed1(p.reverse.tm))
        }
        batchResultsType = "primer"
        onLog(results.map { (name, p) ->
            I18n.t("[Batch Result] 序列 {sequenceName}: 成功设计正向引物 ({sequence}) Tm = {toFixed}°C, 反向引物 ({arg0}) Tm = {arg1}°C")
                .replace("{sequenceName}", name).replace("{sequence}", p.forward.sequence)
                .replace("{toFixed}", jsFixed1(p.forward.tm)).replace("{arg0}", p.reverse.sequence)
                .replace("{arg1}", jsFixed1(p.reverse.tm))
        })
        toast(true, t("批量引物设计成功"), I18n.tf("已为 {v1} 个模板文件自动输出高品质引物！", selectedFiles.size))
    }

    fun copyReport() {
        val text = if (batchResultsType == "dpni") {
            listOf(t("质粒名"), t("切点数"), t("切割位置 (bp)")).joinToString("\t") + "\n" +
                batchDpnIResults.joinToString("\n") { "${it.first}\t${it.second}\t${it.third}" }
        } else {
            listOf(t("模板名"), t("正向引物 (Fwd)"), "Fwd Tm", t("反向引物 (Rev)"), "Rev Tm").joinToString("\t") + "\n" +
                batchPrimerResults.joinToString("\n") { "${it[0]}\t${it[1]}\t${it[2]}°C\t${it[3]}\t${it[4]}°C" }
        }
        clipboard.setText(AnnotatedString(text))
        toast(true, t("复制成功"), t("已复制制表符分隔报告"))
    }

    FloatingPanel(
        title = t("项目集合工作空间"),
        onDismiss = onDismiss,
        initialX = 90.dp,
        initialY = 110.dp,
        width = 470.dp,
        maxHeight = 640.dp
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 10.dp, vertical = 6.dp)
        ) {
            Text(t("双击序列行载入主编辑器，支持多选批量运行"), fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)

            // Import / Export Toolbar
            Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedButton(onClick = onPickSeqFile, modifier = Modifier.weight(1f)) {
                    Text(t("导入序列文件"), fontSize = 8.5.sp, maxLines = 1)
                }
                OutlinedButton(onClick = onPickZip, modifier = Modifier.weight(1f)) {
                    Text(t("导入 ZIP 包"), fontSize = 8.5.sp, maxLines = 1)
                }
                OutlinedButton(
                    onClick = {
                        if (items.isEmpty()) {
                            toast(false, t("ZIP 导出"), t("集合工作空间为空，无可导出文件"))
                            return@OutlinedButton
                        }
                        onLog(listOf(I18n.tf("[Collection] 正在对当前 {v1} 个文件进行打包并构建原生 ZIP 归档...", items.size)))
                        onExportZip(items.map { it.name to it.seq })
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Text(t("导出 ZIP 包"), fontSize = 8.5.sp, maxLines = 1)
                }
            }

            // Search + tag filter（select → FilterChip 行，块 2 惯例）
            OutlinedTextField(
                value = colSearch,
                onValueChange = { colSearch = it },
                placeholder = { Text(t("高级属性检索 (e.g. AmpR, pcDNA, High)..."), fontSize = 8.5.sp, maxLines = 1) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp).height(40.dp)
            )
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                colAllTags.forEach { tag ->
                    FilterChip(
                        selected = colSelectedTag == tag,
                        onClick = { colSelectedTag = tag },
                        label = { Text(if (tag == "All") t("-- 标签分类过滤 --") else tag, fontSize = 8.5.sp) }
                    )
                }
            }

            // 集合表格（max-height 150 滚动区）
            Surface(
                shape = RoundedCornerShape(4.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLowest,
                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp)
            ) {
                Column(Modifier.heightIn(max = 150.dp).verticalScroll(rememberScrollState())) {
                    filteredCollections.forEach { item ->
                        val checked = item.name in selectedFiles
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(
                                    if (checked) MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)
                                    else androidx.compose.ui.graphics.Color.Transparent
                                )
                                .clickable { toggleSelectFile(item.name) }
                                .padding(horizontal = 6.dp, vertical = 3.dp)
                        ) {
                            Checkbox(checked = checked, onCheckedChange = { toggleSelectFile(item.name) })
                            Column(Modifier.weight(1.6f)) {
                                Text(item.name, fontSize = 9.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                                Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                                    item.tags.forEach { tag ->
                                        Text(
                                            tag,
                                            fontSize = 7.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier
                                                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(2.dp))
                                                .padding(horizontal = 3.dp)
                                        )
                                    }
                                }
                            }
                            Text("${item.size} bp", fontSize = 9.sp, modifier = Modifier.weight(0.7f))
                            Text(item.format, fontSize = 9.sp, color = Color(0xFF4CD6FF), modifier = Modifier.weight(0.7f))
                            Text(item.copyNumber.ifEmpty { "N/A" }, fontSize = 9.sp, color = Color(0xFFFF9F1C), modifier = Modifier.weight(0.7f), maxLines = 1)
                            Text(item.resistance.ifEmpty { "None" }, fontSize = 9.sp, color = Color(0xFFB48CFF), fontWeight = FontWeight.Bold, modifier = Modifier.weight(0.8f), maxLines = 1)
                            Row(Modifier.weight(0.4f)) {
                                IconButton(onClick = { loadToWorkspace(item) }, modifier = Modifier.height(24.dp).width(24.dp)) {
                                    Icon(
                                        Icons.Filled.FileDownload,
                                        contentDescription = t("载入工作台"),
                                        modifier = Modifier.height(12.dp).width(12.dp),
                                        tint = Color(0xFF4CD6FF)
                                    )
                                }
                                IconButton(onClick = { deleteCollectionFile(item.id) }, modifier = Modifier.height(24.dp).width(24.dp)) {
                                    Icon(
                                        Icons.Filled.Delete,
                                        contentDescription = t("删除"),
                                        modifier = Modifier.height(12.dp).width(12.dp),
                                        tint = Color(0xFFFF5D5D)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // 批处理按钮
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedButton(onClick = { handleBatchDpnI() }, modifier = Modifier.weight(1f)) {
                    Text(t("批量 DpnI 酶切"), fontSize = 9.5.sp, maxLines = 1)
                }
                OutlinedButton(onClick = { handleBatchPrimerDesign() }, modifier = Modifier.weight(1f)) {
                    Text(t("批量引物生成"), fontSize = 9.5.sp, maxLines = 1)
                }
            }

            // 结果可视化块
            if (batchResultsType != "none") {
                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLowest,
                    border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                ) {
                    Column(Modifier.padding(6.dp)) {
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                if (batchResultsType == "dpni") t("DpnI 批量消化报告") else t("高通量批量引物报告"),
                                fontSize = 9.5.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFFFFD23F)
                            )
                            Row {
                                TextButton(onClick = { copyReport() }, contentPadding = androidx.compose.foundation.layout.PaddingValues(2.dp)) {
                                    Text(t("复制"), fontSize = 8.5.sp, color = Color(0xFF4CD6FF))
                                }
                                TextButton(onClick = { batchResultsType = "none" }, contentPadding = androidx.compose.foundation.layout.PaddingValues(2.dp)) {
                                    Text(t("清除"), fontSize = 8.5.sp, color = Color(0xFFFF5D5D))
                                }
                            }
                        }
                        Column(Modifier.heightIn(max = 120.dp).verticalScroll(rememberScrollState())) {
                            if (batchResultsType == "dpni") {
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text(t("质粒名"), fontSize = 8.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text(t("切点数"), fontSize = 8.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                batchDpnIResults.forEach { r ->
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                        Text(r.first, fontSize = 8.5.sp, fontWeight = FontWeight.Bold, maxLines = 1, modifier = Modifier.weight(1f))
                                        Text(
                                            "${r.second}", fontSize = 8.5.sp, fontWeight = FontWeight.Bold,
                                            color = if (r.second > 0) Color(0xFF53D769) else Color(0xFFFF5D5D)
                                        )
                                        Text(r.third, fontSize = 8.5.sp, color = Color(0xFF4CD6FF), fontFamily = FontFamily.Monospace, maxLines = 1, modifier = Modifier.weight(1.4f))
                                    }
                                }
                            } else {
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text(t("模板名"), fontSize = 8.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text(t("正向引物 (Fwd)"), fontSize = 8.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text(t("反向引物 (Rev)"), fontSize = 8.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                batchPrimerResults.forEach { r ->
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                        Text(r[0], fontSize = 8.5.sp, fontWeight = FontWeight.Bold, maxLines = 1, modifier = Modifier.weight(0.9f))
                                        Column(Modifier.weight(1f)) {
                                            Text(r[1], fontSize = 8.5.sp, fontFamily = FontFamily.Monospace, color = Color(0xFF53D769), maxLines = 1)
                                            Text("Tm: ${r[2]}°C", fontSize = 7.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                        Column(Modifier.weight(1f)) {
                                            Text(r[3], fontSize = 8.5.sp, fontFamily = FontFamily.Monospace, color = Color(0xFF6EC1FF), maxLines = 1)
                                            Text("Tm: ${r[4]}°C", fontSize = 7.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                    }
                                }
                            }
                        }
                        Text(
                            t("* 提示：您也可以在下方对话中输入“导出 Excel”指令，让我为您将此报告一键导出为标准的 Excel/Word 文件下载。"),
                            fontSize = 7.5.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
                        )
                    }
                }
            }

            // status 行（toast 惯例）
            status?.let {
                Text(
                    (if (statusGood) "✓ " else "✗ ") + it,
                    fontSize = 9.sp,
                    color = if (statusGood) Color(0xFF53D769) else Color(0xFFFF5D5D),
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp)
                )
            }
            Spacer(Modifier.height(6.dp))
        }
    }
}

/** JS toFixed(1)：BigDecimal HALF_UP（块 2 起惯例） */
internal fun jsFixed1(d: Double): String =
    java.math.BigDecimal(d).setScale(1, java.math.RoundingMode.HALF_UP).toPlainString()
