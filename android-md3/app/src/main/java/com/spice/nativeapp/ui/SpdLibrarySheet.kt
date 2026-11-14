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
                        msg = if (genes.isEmpty()) "远程库为空" else "载入 ${genes.size} 条基因"
                    } else {
                        @Suppress("UNCHECKED_CAST")
                        vectors = result.value as? List<SpdLibrary.VectorRow> ?: emptyList()
                        msg = if (vectors.isEmpty()) "远程库为空" else "载入 ${vectors.size} 条载体"
                    }
                }
            }
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("SPD 基因库", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(
                "当前序列：${record.name}（${record.length} bp）。凭据取自「设置 → SPD 身份与凭据」。",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("基因库", "载体库").forEach { m ->
                    if (m == mode) Button(onClick = { mode = m; refresh() }, modifier = Modifier.heightIn(min = 48.dp)) { Text(m, fontSize = 12.sp) }
                    else OutlinedButton(onClick = { mode = m; refresh() }, modifier = Modifier.heightIn(min = 48.dp)) { Text(m, fontSize = 12.sp) }
                }
            }
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("搜索名称 / 关键词") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { refresh() }, modifier = Modifier.heightIn(min = 48.dp)) { Text(if (busy) "请求中…" else "刷新列表", fontSize = 12.sp) }
                OutlinedButton(
                    onClick = {
                        busy = true; msg = null
                        scope.launch {
                            val r = withContext(Dispatchers.IO) { SpdLibrary.publishGene(ctx, record) }
                            busy = false
                            msg = if (r.value != null) "已发布，基因 id：${r.value}" else "发布失败：${r.error}"
                        }
                    },
                    modifier = Modifier.heightIn(min = 48.dp)
                ) { Text("发布当前序列", fontSize = 12.sp) }
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
                                        } else msg = "载入失败：${r.error}"
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
                                    if (r.value != null) { onLoad(r.value.record); onDismiss() } else msg = "载入失败：${r.error}"
                                }
                            }, modifier = Modifier.heightIn(min = 48.dp)) { Text("载入", fontSize = 12.sp) }
                        }
                    }
                }
                if (genes.isEmpty()) Text("点「刷新列表」从 SPD 拉取基因。", fontSize = 11.sp)
            } else {
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 320.dp)) {
                    items(vectors) { v ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable {
                                    scope.launch {
                                        val r = withContext(Dispatchers.IO) { SpdLibrary.loadVector(ctx, v.id) }
                                        if (r.value != null) { onLoad(r.value); onDismiss() } else msg = "载入失败：${r.error}"
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
                                    if (r.value != null) { onLoad(r.value); onDismiss() } else msg = "载入失败：${r.error}"
                                }
                            }, modifier = Modifier.heightIn(min = 48.dp)) { Text("载入", fontSize = 12.sp) }
                        }
                    }
                }
                if (vectors.isEmpty()) Text("点「刷新列表」从 SPD 拉取载体模板。", fontSize = 11.sp)
            }
        }
    }
}
