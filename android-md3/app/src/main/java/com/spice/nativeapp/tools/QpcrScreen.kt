package com.spice.nativeapp.tools

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext
import com.spice.nativeapp.core.XlsxReader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.pow

data class DdctResult(
    val dCtControl: Double, val dCtTreat: Double, val ddCt: Double, val fold: Double, val efficiency: Double
)

fun ddct(ctTargetControl: Double, ctRefControl: Double, ctTargetTreat: Double, ctRefTreat: Double, eff: Double = 1.0): DdctResult {
    val d1 = ctTargetControl - ctRefControl
    val d2 = ctTargetTreat - ctRefTreat
    val dd = d2 - d1
    val fold = (1.0 + eff).pow(-dd)
    return DdctResult(d1, d2, dd, fold, eff)
}

private data class QpcrRow(val name: String, val group: String, val target: Double, val ref: Double)

private fun parseQpcrCsv(text: String): List<QpcrRow> = text.lines().mapNotNull { line ->
    val parts = line.split(",").map { it.trim() }
    if (parts.size < 4) return@mapNotNull null
    val target = parts[2].toDoubleOrNull() ?: return@mapNotNull null
    val ref = parts[3].toDoubleOrNull() ?: return@mapNotNull null
    QpcrRow(parts[0], parts[1].lowercase(), target, ref)
}

@Composable
fun QpcrScreen(modifier: Modifier = Modifier) {
    var ctTC by remember { mutableStateOf("22.0") }
    var ctRC by remember { mutableStateOf("20.5") }
    var ctTT by remember { mutableStateOf("19.0") }
    var ctRT by remember { mutableStateOf("20.7") }
    var effText by remember { mutableStateOf("1.00") }
    var csv by remember { mutableStateOf("sample1,control,22.0,20.5\nsample2,control,22.4,20.7\nsample3,treat,19.0,20.7\nsample4,treat,18.6,20.5") }

    val nums = listOf(ctTC, ctRC, ctTT, ctRT).mapNotNull { it.toDoubleOrNull() }
    val eff = effText.toDoubleOrNull() ?: 1.0
    val result = if (nums.size == 4) ddct(nums[0], nums[1], nums[2], nums[3], eff) else null

    val rows = remember(csv) { parseQpcrCsv(csv) }
    val batch = remember(rows, eff) {
        if (rows.isEmpty()) null else {
            val ctrl = rows.filter { it.group.startsWith("c") }
            val treat = rows.filter { !it.group.startsWith("c") }
            if (ctrl.isEmpty() || treat.isEmpty()) null
            else {
                val ctrlD = ctrl.map { it.target - it.ref }.average()
                val out = rows.map { r ->
                    val d = r.target - r.ref
                    val dd = d - ctrlD
                    val fold = (1.0 + eff).pow(-dd)
                    Triple(r.name, d, fold)
                }
                ctrlD to out
            }
        }
    }

    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("qPCR ΔΔCt 相对定量", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)

        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))) {
            Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("单样本计算", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                CtField("对照组 靶标 Ct", ctTC) { ctTC = it }
                CtField("对照组 内参 Ct", ctRC) { ctRC = it }
                CtField("处理组 靶标 Ct", ctTT) { ctTT = it }
                CtField("处理组 内参 Ct", ctRT) { ctRT = it }
                CtField("扩增效率 E（1.00 = 100%）", effText) { effText = it }
                if (result != null) {
                    Line("ΔCt 对照", "%.2f".format(result.dCtControl))
                    Line("ΔCt 处理", "%.2f".format(result.dCtTreat))
                    Line("ΔΔCt", "%.2f".format(result.ddCt))
                    Line("相对表达量", "%.2f×".format(result.fold))
                    Line("处理组 = 对照组的", "%.1f%%".format(result.fold * 100))
                }
            }
        }

        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))) {
            Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("板图批量导入（CSV）", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Text("格式：样本名,组别(control/treat),靶标Ct,内参Ct；也可直接导入仪器导出的 .xlsx 板图", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                val ctx = LocalContext.current
                var xlsxMsg by remember { mutableStateOf("") }
                val pickXlsx = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
                    if (uri == null) return@rememberLauncherForActivityResult
                    val rows = XlsxReader.readRows(ctx, uri)
                    if (rows.isNullOrEmpty()) { xlsxMsg = "解析失败：不是有效的 XLSX"; return@rememberLauncherForActivityResult }
                    // 按表头关键词定位列；找不到就按前 4 列
                    val header = rows.firstOrNull { r -> r.any { it.isNotBlank() } } ?: emptyList()
                    fun find(vararg keys: String): Int {
                        val lower = header.map { it.lowercase() }
                        return keys.firstNotNullOfOrNull { k ->
                            lower.indexOfFirst { it.contains(k) }.takeIf { it >= 0 }
                        } ?: -1
                    }
                    val colSample = find("样本", "sample", "well", "孔")
                    val colGroup = find("组别", "group", "条件", "condition")
                    val colTarget = find("靶标", "target", "gene", "基因")
                    val colRef = find("内参", "ref", "housekeeping", "actin", "gapdh")
                    val dataRows = rows.drop(if (colSample >= 0 || colTarget >= 0) 1 else 0)
                    val lines = dataRows.mapNotNull { r ->
                        val sample = if (colSample >= 0) r.getOrElse(colSample) { "" } else r.getOrElse(0) { "" }
                        val group = if (colGroup >= 0) r.getOrElse(colGroup) { "" } else r.getOrElse(1) { "" }
                        val target = if (colTarget >= 0) r.getOrElse(colTarget) { "" } else r.getOrElse(2) { "" }
                        val ref = if (colRef >= 0) r.getOrElse(colRef) { "" } else r.getOrElse(3) { "" }
                        if (sample.isBlank() || target.toDoubleOrNull() == null || ref.toDoubleOrNull() == null) null
                        else "$sample,${group.ifBlank { "control" }},$target,$ref"
                    }
                    if (lines.isEmpty()) xlsxMsg = "未识别到有效行（需含样本、组别、靶标Ct、内参Ct）"
                    else { csv = lines.joinToString("\n"); xlsxMsg = "已导入 ${lines.size} 行（表头列：样本${colSample} 组别${colGroup} 靶标${colTarget} 内参${colRef}）" }
                }
                OutlinedButton(onClick = { pickXlsx.launch(arrayOf("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "application/octet-stream", "*/*")) }, modifier = Modifier.fillMaxWidth()) { Text("导入仪器 XLSX 板图") }
                if (xlsxMsg.isNotBlank()) Text(xlsxMsg, fontSize = 10.sp, color = MaterialTheme.colorScheme.primary)
                OutlinedTextField(
                    value = csv,
                    onValueChange = { csv = it },
                    label = { Text("粘贴 CSV", fontSize = 11.sp) },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 4,
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
                )
                if (batch != null) {
                    Line("对照组平均 ΔCt", "%.2f".format(batch.first))
                    batch.second.forEach { (name, d, fold) ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(name, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                            Text("ΔCt %.2f · %.2f×".format(d, fold), fontSize = 11.sp)
                        }
                    }
                } else if (rows.isNotEmpty()) {
                    Text("需要同时包含 control 与 treat 两组", fontSize = 11.sp, color = MaterialTheme.colorScheme.tertiary)
                }
            }
        }

        Text(
            "引物效率自检：斜率 -3.32 ↔ E=1.00；|斜率| >3.6 或 <3.1 先优化引物与退火温度。",
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace
        )
    }
}

@Composable
private fun CtField(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label, fontSize = 12.sp) },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        keyboardOptions = KeyboardType.Decimal.let { KeyboardOptions(keyboardType = it) }
    )
}

@Composable
private fun Line(k: String, v: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(k, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(v, fontSize = 12.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium)
    }
}
