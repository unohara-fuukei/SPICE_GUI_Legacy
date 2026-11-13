package com.spice.nativeapp.ui

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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.spice.nativeapp.core.Cloning
import com.spice.nativeapp.core.Enzymes
import com.spice.nativeapp.core.SeqRecord

@Composable
fun CloningScreen(record: SeqRecord, modifier: Modifier = Modifier) {
    var start by remember(record.name) { mutableIntStateOf(1) }
    var end by remember(record.name) { mutableIntStateOf(minOf(record.length, 800)) }
    var primerLen by remember { mutableIntStateOf(20) }
    var armLen by remember { mutableFloatStateOf(20f) }

    val pair = remember(record.sequence, start, end, primerLen) {
        Cloning.designPrimers(record.sequence, start, end, primerLen)
    }
    val pairs = remember(record.sequence, record.name) {
        Enzymes.doubleDigestSuggest(
            record.sequence,
            Enzymes.common,
            record.circular
        ).take(6)
    }

    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text("PCR 引物设计", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = start.toString(),
                onValueChange = { v -> v.toIntOrNull()?.let { start = it.coerceIn(1, record.length) } },
                label = { Text("起点") },
                modifier = Modifier.weight(1f),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
            )
            OutlinedTextField(
                value = end.toString(),
                onValueChange = { v -> v.toIntOrNull()?.let { end = it.coerceIn(start, record.length) } },
                label = { Text("终点") },
                modifier = Modifier.weight(1f),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
            )
        }

        Text("引物长度：$primerLen nt", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Slider(
            value = primerLen.toFloat(),
            onValueChange = { primerLen = it.toInt() },
            valueRange = 17f..30f,
            steps = 12
        )

        if (pair != null) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    MonoLine("正向 F", pair.forward, "Tm %.1f ℃ · GC %.0f%%".format(pair.tmF, pair.gcF))
                    MonoLine("反向 R", pair.reverse, "Tm %.1f ℃ · GC %.0f%%".format(pair.tmR, pair.gcR))
                    Text("产物长度 ${pair.productLength} bp", fontSize = 12.sp, fontWeight = FontWeight.Medium)
                    if (pair.warnings.isEmpty()) {
                        Text("✓ 引物参数在推荐范围内", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
                    } else {
                        pair.warnings.forEach { Text("! $it", fontSize = 12.sp, color = MaterialTheme.colorScheme.tertiary) }
                    }
                }
            }
        }

        Text("Gibson 装配同源臂", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        Text("同源臂长度：${armLen.toInt()} bp", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Slider(value = armLen, onValueChange = { armLen = it }, valueRange = 15f..40f, steps = 24)
        val arms = remember(record.sequence, armLen.toInt()) {
            Cloning.gibsonHomology(record.sequence, "", armLen.toInt())
        }
        Text(
            "上游臂 ${arms.first}\n下游臂 ${arms.second}",
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurface
        )

        Text("双酶切推荐（缓冲液兼容）", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        if (pairs.isEmpty()) {
            Text("当前酶组合无兼容缓冲液配对", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        pairs.forEach { (a, b) ->
            FilterChip(selected = false, onClick = {}, label = { Text("$a + $b", fontSize = 12.sp) })
        }

        Text("Golden Gate（BsaI）", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        val gg = remember(record.sequence) { Cloning.goldenGateSites(record.sequence, "BsaI") }
        Text(
            if (gg.isEmpty()) "模板无 BsaI 位点，可直接用于一级装配" else "检出 ${gg.size} 个 BsaI 位点：${gg.joinToString()}",
            fontSize = 12.sp
        )
    }
}

@Composable
private fun MonoLine(tag: String, seq: String, meta: String) {
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(tag, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(meta, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(seq, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
    }
}
