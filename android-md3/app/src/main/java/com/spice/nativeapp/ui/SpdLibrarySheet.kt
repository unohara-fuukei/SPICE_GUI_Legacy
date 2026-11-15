package com.spice.nativeapp.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.spice.nativeapp.core.SeqRecord
import com.spice.nativeapp.core.SpdLibrary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 对应原版「文件 → SPD 基因库」与 spd/inspector：基因库 / 载体库浏览、发布与载入 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpdLibrarySheet(
    record: SeqRecord,
    onDismiss: () -> Unit,
    onLoad: (SeqRecord) -> Unit
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var mode by remember { mutableStateOf("基因库") }
    var query by remember { mutableStateOf("") }
    var genes by remember { mutableStateOf<List<SpdLibrary.GeneRow>>(emptyList()) }
    var vectors by remember { mutableStateOf<List<SpdLibrary.VectorRow>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf<String?>(null) }

    fun refresh() {
        busy = true; msg = null
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                if (mode == "基因库") SpdLibrary.listGenes(ctx, query) else SpdLibrary.listVectors(ctx, query)
            }
            busy = false
            when (result) {
                is SpdLibrary.Result<*> -> {
                    val err = result.error
                    if (err != null) msg = err
                    else if (mode == "基因库") {
                        @Suppress("UNCHECKED_CAST")
                        genes = result.value as? List<SpdLibrary.GeneRow> ?: emptyList()
                        msg = if (genes.isEmpty()) com.spice.nativeapp.core.I18n.t("远程库为空") else com.spice.nativeapp.core.I18n.tf("载入 {v1} 条基因", genes.size)
                    } else {
                        @Suppress("UNCHECKED_CAST")
                        vectors = result.value as? List<SpdLibrary.VectorRow> ?: emptyList()
                        msg = if (vectors.isEmpty()) com.spice.nativeapp.core.I18n.t("远程库为空") else com.spice.nativeapp.core.I18n.tf("载入 {v1} 条载体", vectors.size)
                    }
                }
            }
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(com.spice.nativeapp.core.I18n.t("SPD 基因库"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(
                com.spice.nativeapp.core.I18n.tf("当前序列：{v1}（{v2} bp）。凭据取自「设置 → SPD 身份与凭据」。", record.name, record.length),
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(com.spice.nativeapp.core.I18n.t("基因库"), com.spice.nativeapp.core.I18n.t("载体库")).forEach { m ->
                    if (m == mode) Button(onClick = { mode = m; refresh() }, modifier = Modifier.heightIn(min = 48.dp)) { Text(m, fontSize = 12.sp) }
                    else OutlinedButton(onClick = { mode = m; refresh() }, modifier = Modifier.heightIn(min = 48.dp)) { Text(m, fontSize = 12.sp) }
                }
            }
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text(com.spice.nativeapp.core.I18n.t("搜索名称 / 关键词")) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { refresh() }, modifier = Modifier.heightIn(min = 48.dp)) { Text(if (busy) com.spice.nativeapp.core.I18n.t("请求中…") else com.spice.nativeapp.core.I18n.t("刷新列表"), fontSize = 12.sp) }
            }
            // ── SPD 基因发布（对齐 main gene/SpdGenePanel.svelte 发布盒 1:1）──
            var pubName by remember { mutableStateOf(record.name) }
            var pubSymbol by remember { mutableStateOf("") }
            var pubType by remember { mutableStateOf("other") }
            var publishing by remember { mutableStateOf(false) }
            var publishMessage by remember { mutableStateOf<String?>(null) }
            var publishedId by remember { mutableStateOf<String?>(null) }
            var inspectorId by remember { mutableStateOf<String?>(null) }
            OutlinedTextField(value = pubName, onValueChange = { pubName = it }, label = { Text(com.spice.nativeapp.core.I18n.t("记录名称"), fontSize = 11.sp) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = pubSymbol, onValueChange = { pubSymbol = it }, label = { Text(com.spice.nativeapp.core.I18n.t("Symbol（可选）"), fontSize = 11.sp) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text(com.spice.nativeapp.core.I18n.t("基因类型"), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                listOf("protein_coding" to "蛋白编码", "non_coding" to "非编码", "regulatory" to "调控", "other" to "其他").forEach { (v, label) ->
                    androidx.compose.material3.FilterChip(
                        selected = pubType == v, onClick = { pubType = v },
                        label = { Text(com.spice.nativeapp.core.I18n.t(label), fontSize = 10.sp) },
                    )
                }
            }
            Button(
                enabled = !publishing,
                onClick = {
                    if (SpdLibrary.token(ctx).isBlank()) { publishMessage = com.spice.nativeapp.core.I18n.t("请先在 设置 → 公开集成 配置 API Token"); return@Button }
                    if (record.sequence.isBlank()) { publishMessage = com.spice.nativeapp.core.I18n.t("当前工作台没有 DNA 序列"); return@Button }
                    val name = pubName.trim().ifEmpty { record.name.trim() }
                    if (name.isEmpty()) { publishMessage = com.spice.nativeapp.core.I18n.t("请填写记录名称"); return@Button }
                    publishing = true; publishMessage = null; publishedId = null
                    scope.launch {
                        val r = withContext(Dispatchers.IO) { SpdLibrary.publishGene(ctx, record.copy(name = name), symbol = pubSymbol, geneType = pubType) }
                        publishing = false
                        val res = r.value
                        if (res != null) {
                            publishedId = res.geneId
                            // main spdGenePublishOk({id: id.slice(-8), count}) + regionWarn/skipped 追加
                            var m = com.spice.nativeapp.core.I18n.t("已发布 {id}，写入 {count} 个区域")
                                .replace("{id}", res.geneId.takeLast(8)).replace("{count}", res.regionCount.toString())
                            if (res.regionError != null) {
                                m += " · " + com.spice.nativeapp.core.I18n.t("基因已创建，但区域写入失败：{msg}").replace("{msg}", res.regionError)
                            } else if (res.skippedCount > 0) {
                                m += " · " + com.spice.nativeapp.core.I18n.t("已跳过 {count} 个无法映射的 feature").replace("{count}", res.skippedCount.toString())
                            }
                            publishMessage = m
                        } else {
                            publishMessage = r.error
                        }
                    }
                },
                modifier = Modifier.heightIn(min = 48.dp).fillMaxWidth()
            ) { Text(if (publishing) com.spice.nativeapp.core.I18n.t("发布中…") else com.spice.nativeapp.core.I18n.t("发布到 SPD Gene"), fontSize = 12.sp) }
            publishMessage?.let { Text(it, fontSize = 11.sp, fontFamily = FontFamily.Monospace) }
            publishedId?.let { pid ->
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Text(com.spice.nativeapp.core.I18n.t("基因 ID："), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(pid, fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
                    OutlinedButton(onClick = { inspectorId = pid }) { Text(com.spice.nativeapp.core.I18n.t("详情"), fontSize = 10.sp) }
                }
            }
            inspectorId?.let { iid ->
                SpdInspectorDialog(ctx = ctx, kind = "gene", id = iid, onDismiss = { inspectorId = null })
            }
            msg?.let { Text(it, fontSize = 11.sp, fontFamily = FontFamily.Monospace) }

            if (mode == "基因库") {
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 320.dp)) {
                    items(genes) { g ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable {
                                    scope.launch {
                                        val r = withContext(Dispatchers.IO) { SpdLibrary.loadGene(ctx, g.id) }
                                        if (r.value != null) {
                                            onLoad(r.value.record)
                                            onDismiss()
                                        } else msg = com.spice.nativeapp.core.I18n.tf("载入失败：{v1}", r.error)
                                    }
                                }
                                .padding(vertical = 6.dp),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column {
                                Text(g.name, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                                Text(
                                    listOfNotNull(g.organism, g.length?.let { "$it bp" }, "id ${g.id}").joinToString(" · "),
                                    fontSize = 10.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            TextButton(onClick = {
                                scope.launch {
                                    val r = withContext(Dispatchers.IO) { SpdLibrary.loadGene(ctx, g.id) }
                                    if (r.value != null) { onLoad(r.value.record); onDismiss() } else msg = com.spice.nativeapp.core.I18n.tf("载入失败：{v1}", r.error)
                                }
                            }, modifier = Modifier.heightIn(min = 48.dp)) { Text(com.spice.nativeapp.core.I18n.t("载入"), fontSize = 12.sp) }
                        }
                    }
                }
                if (genes.isEmpty()) Text(com.spice.nativeapp.core.I18n.t("点「刷新列表」从 SPD 拉取基因。"), fontSize = 11.sp)
            } else {
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 320.dp)) {
                    items(vectors) { v ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable {
                                    scope.launch {
                                        val r = withContext(Dispatchers.IO) { SpdLibrary.loadVector(ctx, v.id) }
                                        if (r.value != null) { onLoad(r.value); onDismiss() } else msg = com.spice.nativeapp.core.I18n.tf("载入失败：{v1}", r.error)
                                    }
                                }
                                .padding(vertical = 6.dp),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column {
                                Text(v.name, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                                Text(
                                    listOfNotNull(v.kind, v.length?.let { "$it bp" }, "id ${v.id}").joinToString(" · "),
                                    fontSize = 10.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            TextButton(onClick = {
                                scope.launch {
                                    val r = withContext(Dispatchers.IO) { SpdLibrary.loadVector(ctx, v.id) }
                                    if (r.value != null) { onLoad(r.value); onDismiss() } else msg = com.spice.nativeapp.core.I18n.tf("载入失败：{v1}", r.error)
                                }
                            }, modifier = Modifier.heightIn(min = 48.dp)) { Text(com.spice.nativeapp.core.I18n.t("载入"), fontSize = 12.sp) }
                        }
                    }
                }
                if (vectors.isEmpty()) Text(com.spice.nativeapp.core.I18n.t("点「刷新列表」从 SPD 拉取载体模板。"), fontSize = 11.sp)
            }
        }
    }
}
