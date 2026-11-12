package com.spice.nativeapp.ui

import androidx.compose.foundation.layout.Arrangement
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

    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
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
