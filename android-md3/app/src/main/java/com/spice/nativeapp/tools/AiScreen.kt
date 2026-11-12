package com.spice.nativeapp.tools

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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.spice.nativeapp.core.Bio
import com.spice.nativeapp.core.Enzymes
import com.spice.nativeapp.core.SeqRecord

/** 本地启发式建议（无需网络，等价于原版 copilot 的离线规则层） */
fun localAdvice(rec: SeqRecord): List<Pair<String, String>> {
    val out = mutableListOf<Pair<String, String>>()
    val gc = rec.gc
    out.add("序列概览" to "${rec.name}：${rec.length} bp，GC ${"%.1f".format(gc)}%，${rec.features.size} 个特征，${if (rec.circular) "环状" else "线性"}")
    when {
        gc < 35 -> out.add("GC 提示" to "GC 偏低（<35%），PCR 可能需要提高 Mg²⁺ 或使用 GC 缓冲液；测序时留意 poly-A 区。")
        gc > 65 -> out.add("GC 提示" to "GC 偏高（>65%），建议加 DMSO/甜菜碱或提高退火温度，避免二级结构干扰。")
        else -> out.add("GC 提示" to "GC 含量处于常规可扩增区间（35–65%）。")
    }
    // 同聚物
    val runs = Regex("(A{8,}|T{8,}|G{8,}|C{8,})").findAll(rec.sequence).map { it.value }.toList()
    if (runs.isNotEmpty()) {
        out.add("同聚物风险" to "检出 ${runs.size} 处 ≥8 nt 同聚物（最长 ${runs.maxOf { it.length }} nt），Sanger 测序易出现滑移，必要时改用反向引物或克隆后测序。")
    }
    // 酶位点
    val unique = Enzymes.catalog.filter { Bio.cutSites(rec.sequence, it, rec.circular).size == 1 }
        .map { it.name }
    val zero = Enzymes.catalog.filter { Bio.cutSites(rec.sequence, it, rec.circular).isEmpty() }.map { it.name }
    if (unique.isNotEmpty()) out.add("单酶切候选" to "唯一切点酶：" + unique.take(8).joinToString("、") + "（可用于线性化或单酶切验证）")
    if (zero.isNotEmpty()) out.add("无切点酶" to "不切酶（可用于克隆位点保留）：" + zero.take(8).joinToString("、"))
    // ORF
    val orf = Bio.longestOrf(rec.sequence, 100)
    if (orf != null) out.add("ORF" to "最长 ORF：${orf.first}–${orf.second}（${orf.third} aa），可检查是否与注释 CDS 一致。")
    else out.add("ORF" to "未检出 ≥100 aa 的 ORF，若为表达载体请确认起始密码子与读框。")
    // MCS 建议
    out.add("下一步建议" to "① 用「克隆」页按目标区域设计引物并核对 Tm；② 用「凝胶」页核对酶切图谱；③ 项目页保存当前版本留档。")
    return out
}

@Composable
fun AiScreen(record: SeqRecord, modifier: Modifier = Modifier) {
    var question by remember { mutableStateOf("这条质粒做双酶切克隆要注意什么？") }
    var answer by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }

    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("实验助手（本地启发式）", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(
            "原生版不含云端模型，用规则引擎基于当前序列给出可执行建议；联网 AI 请在 WebView 版使用。",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        OutlinedTextField(
            value = question,
            onValueChange = { question = it },
            label = { Text("问题", fontSize = 12.sp) },
            modifier = Modifier.fillMaxWidth(),
            minLines = 2
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { answer = localAdvice(record) }, modifier = Modifier.weight(1f)) { Text("分析当前序列") }
            Button(onClick = { answer = emptyList() }, modifier = Modifier.weight(1f)) { Text("清空") }
        }

        answer.forEach { (title, body) ->
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))) {
                Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(title, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    Text(body, fontSize = 12.sp)
                }
            }
        }

        if (answer.isEmpty()) {
            Text("点击「分析当前序列」生成建议清单。", fontSize = 11.sp, fontFamily = FontFamily.Monospace)
        }
    }
}
