package com.spice.nativeapp.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.spice.nativeapp.core.Bio
import com.spice.nativeapp.core.ProteinCore

@Composable
fun ProteinScreen(modifier: Modifier = Modifier) {
    var input by remember {
        mutableStateOf("MKTAYIAKQRQISFVKSHFSRQLEERLGLIEVQANAGAPGDRVLLLAGLEKLLRQGGSVEPVLAALRDELAAELGAPDPAARLRAALEEAGGEP")
    }
    val seq = remember(input) { ProteinCore.clean(input) }
    val props = remember(seq) { if (seq.isNotEmpty()) ProteinCore.props(seq) else null }
    val orf = remember(seq) { if (seq.isNotEmpty()) Bio.longestOrf(seq, 10) else null }
    val seqLen = seq.length
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var menuOpen by remember { mutableStateOf<String?>(null) }
    var info by remember { mutableStateOf("") }
    var showMut by remember { mutableStateOf(false) }
    var mutPos by remember { mutableStateOf("1") }
    var mutAa by remember { mutableStateOf("A") }
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    val exportLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("text/plain")
    ) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        runCatching {
            ctx.contentResolver.openOutputStream(uri)?.use { out ->
                val body = ">protein\n" + seq.chunked(60).joinToString("\n") + "\n"
                out.write(body.toByteArray())
            }
        }
    }

    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // 原版蛋白工作台菜单：文件 / 建模 / 突变 / 窗口
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(0.dp)
        ) {
            Box {
                TextButton(onClick = { menuOpen = if (menuOpen == "file") null else "file" }) { Text("文件", fontSize = 13.sp) }
                DropdownMenu(expanded = menuOpen == "file", onDismissRequest = { menuOpen = null }) {
                    DropdownMenuItem(
                        text = { Text("导出蛋白序列 (FASTA)…", fontSize = 13.sp) },
                        onClick = { menuOpen = null; exportLauncher.launch("protein.fasta") }
                    )
                    DropdownMenuItem(
                        text = { Text("导出性质报告…", fontSize = 13.sp) },
                        onClick = {
                            menuOpen = null
                            info = props?.let {
                                "Mw ${"%.1f".format(it.molecularWeight)} Da\npI ${"%.2f".format(it.pI)}\nGRAVY ${"%.3f".format(it.gravy)}\nA280 ${"%.3f".format(it.a280)}"
                            } ?: "无序列"
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("打开 PDB / mmCIF…", fontSize = 13.sp) },
                        onClick = { menuOpen = null; info = "PDB/mmCIF 解析（结构可视化）需在原版桌面端完成；原生版可粘贴序列做性质与骨架预览。" }
                    )
                }
            }
            Box {
                TextButton(onClick = { menuOpen = if (menuOpen == "model") null else "model" }) { Text("建模", fontSize = 13.sp) }
                DropdownMenu(expanded = menuOpen == "model", onDismissRequest = { menuOpen = null }) {
                    DropdownMenuItem(
                        text = { Text("运行折叠推理", fontSize = 13.sp) },
                        onClick = {
                            menuOpen = null
                            info = "折叠推理依赖 spice_infer_fixed.onnx（原版权重由本地脚本导出，无公开下载地址），原生版暂未移植；可先用 3D 骨架页做螺旋/β 预览。"
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("运行 MD 验证", fontSize = 13.sp) },
                        onClick = { menuOpen = null; info = "MD 验证依赖 SPICE Engine（Rust crate）与力场数据，Android 端未移植。" }
                    )
                }
            }
            Box {
                TextButton(onClick = { menuOpen = if (menuOpen == "mut") null else "mut" }) { Text("突变", fontSize = 13.sp) }
                DropdownMenu(expanded = menuOpen == "mut", onDismissRequest = { menuOpen = null }) {
                    DropdownMenuItem(
                        text = { Text("扫描突变位点", fontSize = 13.sp) },
                        onClick = {
                            menuOpen = null
                            val hydro = seq.mapIndexedNotNull { i, c ->
                                val h = mapOf('A' to 1.8, 'V' to 4.2, 'L' to 3.8, 'I' to 4.5, 'F' to 2.8, 'W' to -0.9, 'M' to 1.9, 'C' to 2.5, 'K' to -3.9, 'R' to -4.5, 'D' to -3.5, 'E' to -3.5, 'N' to -3.5, 'Q' to -3.5, 'S' to -0.8, 'T' to -0.7, 'H' to -3.2, 'Y' to -1.3, 'P' to -1.6, 'G' to -0.4)[c]
                                if (h != null && h > 3.5) "${i + 1}$c" else null
                            }
                            info = "高疏水残基（可优先考虑替换以改善溶解性）：\n" + hydro.take(40).joinToString("  ")
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("单点突变设计", fontSize = 13.sp) },
                        onClick = { menuOpen = null; showMut = true }
                    )
                    DropdownMenuItem(
                        text = { Text("批量筛选（导入位点列表）", fontSize = 13.sp) },
                        onClick = { menuOpen = null; info = "批量筛选需折叠结构打分（ΔΔG），依赖推理引擎，原生版暂未移植。" }
                    )
                }
            }
            Box {
                TextButton(onClick = { menuOpen = if (menuOpen == "win") null else "win" }) { Text("窗口", fontSize = 13.sp) }
                DropdownMenu(expanded = menuOpen == "win", onDismissRequest = { menuOpen = null }) {
                    listOf("折叠面板", "MD 面板", "突变面板", "Phase 面板", "批量面板").forEach { name ->
                        DropdownMenuItem(
                            text = { Text(name, fontSize = 13.sp) },
                            onClick = { menuOpen = null; info = "桌面端可切换 $name；移动端以纵向单列呈现，对应内容见下方各区块。" }
                        )
                    }
                }
            }
            Text("$seqLen aa", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 4.dp))
        }

        if (info.isNotBlank()) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))) {
                Column(Modifier.padding(10.dp)) {
                    Text(info, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                    TextButton(onClick = { clipboard.setText(androidx.compose.ui.text.AnnotatedString(info)) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("复制", fontSize = 11.sp) }
                }
            }
        }

        if (showMut) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))) {
                Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("单点突变设计", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(value = mutPos, onValueChange = { mutPos = it }, label = { Text("位点", fontSize = 11.sp) }, modifier = Modifier.weight(1f), singleLine = true)
                        OutlinedTextField(value = mutAa, onValueChange = { mutAa = it.uppercase() }, label = { Text("目标 AA", fontSize = 11.sp) }, modifier = Modifier.weight(1f), singleLine = true)
                    }
                    val pos = mutPos.toIntOrNull() ?: 0
                    val from = seq.getOrNull(pos - 1)
                    val to = mutAa.firstOrNull()
                    if (pos in 1..seq.length && to != null) {
                        val opt = com.spice.nativeapp.core.CloningPlus.codons[to]?.firstOrNull()
                        Text("$from$pos$to  ·  推荐密码子 ${opt ?: "—"}（E. coli 偏好）", fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                        Text("疏水性变化：${mapOf('A' to 1.8, 'V' to 4.2, 'L' to 3.8, 'K' to -3.9, 'D' to -3.5, 'E' to -3.5, 'R' to -4.5).let { m -> "%+.1f".format((m[to] ?: 0.0) - (m[from] ?: 0.0)) }}", fontSize = 11.sp)
                    } else {
                        Text("输入有效位点（1..${seq.length}）与单字母氨基酸", fontSize = 11.sp, color = MaterialTheme.colorScheme.tertiary)
                    }
                    TextButton(onClick = { showMut = false }, modifier = Modifier.heightIn(min = 48.dp)) { Text("关闭") }
                }
            }
        }

        OutlinedTextField(
            value = input,
            onValueChange = { input = it },
            label = { Text("氨基酸序列（1 字母码）") },
            modifier = Modifier.fillMaxWidth(),
            minLines = 3,
            maxLines = 6,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
            textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
        )

        if (props != null) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))) {
                Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("理化性质", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    PropRow("残基数", "${props.length} aa")
                    PropRow("分子量", ProteinCore.formatMass(props.molecularWeight))
                    PropRow("等电点 pI", "%.2f".format(props.pI))
                    PropRow("pH 7 净电荷", "%.2f".format(props.chargedAtPh7))
                    PropRow("GRAVY 疏水指数", "%.3f".format(props.gravy))
                    PropRow("A280 消光系数", "%.0f M⁻¹cm⁻¹".format(props.a280))
                }
            }

            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))) {
                Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("疏水区段扫描（KD 窗口 9）", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    val windows = remember(seq) {
                        if (seq.length < 9) emptyList()
                        else (0..seq.length - 9).map { i ->
                            i + 1 to seq.substring(i, i + 9).sumOf { (kdc(it)).toDouble() } / 9.0
                        }.sortedByDescending { it.second }.take(5)
                    }
                    if (windows.isEmpty()) Text("序列过短", fontSize = 12.sp)
                    windows.forEach { (pos, score) ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("第 $pos 位起 9 aa", fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                            Text("KD %.2f".format(score), fontSize = 12.sp, color = if (score > 1.5f) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }

        if (orf != null) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))) {
                Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("最长 ORF（核苷酸序列）", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    Text("${orf.first}–${orf.second}，${orf.third} aa", fontSize = 12.sp)
                }
            }
        }

        OutlinedButton(
            onClick = { input = "" },
            modifier = Modifier.fillMaxWidth()
        ) { Text("清空") }

        Text(
            "说明：原生版为纯 Kotlin 计算，不含 ONNX 结构预测；3D 折叠请在 WebView 版使用。",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

private fun kdc(c: Char): Double = when (c) {
    'A' -> 1.8; 'R' -> -4.5; 'N' -> -3.5; 'D' -> -3.5; 'C' -> 2.5
    'E' -> -3.5; 'Q' -> -3.5; 'G' -> -0.4; 'H' -> -3.2; 'I' -> 4.5
    'L' -> 3.8; 'K' -> -3.9; 'M' -> 1.9; 'F' -> 2.8; 'P' -> -1.6
    'S' -> -0.8; 'T' -> -0.7; 'W' -> -0.9; 'Y' -> -1.3; 'V' -> 4.2
    else -> 0.0
}

@Composable
private fun PropRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontSize = 12.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium)
    }
}
