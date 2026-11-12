package com.spice.nativeapp.tools

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

/** ΔΔCt 相对定量 */
data class DdctResult(
    val dCtControl: Double,
    val dCtTreat: Double,
    val ddCt: Double,
    val fold: Double,
    val efficiency: Double
)

fun ddct(ctTargetControl: Double, ctRefControl: Double, ctTargetTreat: Double, ctRefTreat: Double, eff: Double = 1.0): DdctResult {
    val d1 = ctTargetControl - ctRefControl
    val d2 = ctTargetTreat - ctRefTreat
    val dd = d2 - d1
    val base = 1.0 + eff
    val fold = base.pow(-dd)
    return DdctResult(d1, d2, dd, fold, eff)
}

@Composable
fun QpcrScreen(modifier: Modifier = Modifier) {
    var ctTC by remember { mutableStateOf("22.0") }
    var ctRC by remember { mutableStateOf("20.5") }
    var ctTT by remember { mutableStateOf("19.0") }
    var ctRT by remember { mutableStateOf("20.7") }
    var effText by remember { mutableStateOf("1.00") }

    val nums = listOf(ctTC, ctRC, ctTT, ctRT).mapNotNull { it.toDoubleOrNull() }
    val eff = effText.toDoubleOrNull() ?: 1.0
    val result = if (nums.size == 4) ddct(nums[0], nums[1], nums[2], nums[3], eff) else null

    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("qPCR ΔΔCt 相对定量", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text("输入对照组 / 处理组的靶标与内参 Ct 值，按 2^(-ΔΔCt) 归一化到对照组 = 1。", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)

        CtField("对照组 靶标 Ct", ctTC) { ctTC = it }
        CtField("对照组 内参 Ct", ctRC) { ctRC = it }
        CtField("处理组 靶标 Ct", ctTT) { ctTT = it }
        CtField("处理组 内参 Ct", ctRT) { ctRT = it }
        CtField("扩增效率 E（1.00 = 100%）", effText) { effText = it }

        if (result != null) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))) {
                Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text("结果", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    Line("ΔCt 对照", "%.2f".format(result.dCtControl))
                    Line("ΔCt 处理", "%.2f".format(result.dCtTreat))
                    Line("ΔΔCt", "%.2f".format(result.ddCt))
                    Line("相对表达量", "%.2f×".format(result.fold))
                    Line("处理组 = 对照组的", "%.1f%%".format(result.fold * 100))
                    Text(
                        if (result.fold > 2) "结论：处理组显著上调" else if (result.fold < 0.5) "结论：处理组显著下调" else "结论：变化幅度在 2 倍以内",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }

        Text("引物效率自检", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        Text(
            "斜率法：E = 10^(-1/斜率) - 1。标准曲线斜率 -3.32 对应 E=1.00；-3.6 对应约 0.90；-3.1 对应约 1.10。\n" +
                "若 |斜率| > 3.6 或 < 3.1，先优化引物浓度与退火温度再比较组间差异。",
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
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
    )
}

@Composable
private fun Line(k: String, v: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(k, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(v, fontSize = 12.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium)
    }
}
