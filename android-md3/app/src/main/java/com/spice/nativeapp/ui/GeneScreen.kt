package com.spice.nativeapp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.spice.nativeapp.core.Bio
import com.spice.nativeapp.core.Enzymes
import com.spice.nativeapp.core.SeqRecord

private enum class GeneView(val label: String) { CIRCULAR("环状图谱"), LINEAR("线性图谱"), SEQUENCE("序列") }

@Composable
fun GeneScreen(
    record: SeqRecord,
    selectedEnzymes: Set<String>,
    onToggleEnzyme: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var view by remember { mutableStateOf(GeneView.CIRCULAR) }
    var showFeatures by remember { mutableStateOf(true) }
    val hits = remember(record.sequence, selectedEnzymes, record.circular) {
        Enzymes.digest(record.sequence, selectedEnzymes, record.circular)
    }

    Column(modifier.fillMaxSize()) {
        // 视图切换
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            GeneView.values().forEach { v ->
                FilterChip(
                    selected = view == v,
                    onClick = { view = v },
                    label = { Text(v.label, fontSize = 13.sp) }
                )
            }
        }

        Box(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(horizontal = 12.dp)
        ) {
            when (view) {
                GeneView.CIRCULAR -> CircularMap(record, selectedEnzymes, Modifier.fillMaxSize())
                GeneView.LINEAR -> LinearMap(record, selectedEnzymes, Modifier.fillMaxSize())
                GeneView.SEQUENCE -> SequenceView(record, hits.map { it.pos }.toSet(), Modifier.fillMaxSize())
            }
        }

        // 概要
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(record.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Text(
                "${record.length} bp · GC ${"%.1f".format(record.gc)}% · ${hits.size} 切点",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        HorizontalDivider()

        // 酶选择
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Enzymes.common.sorted().forEach { name ->
                FilterChip(
                    selected = selectedEnzymes.contains(name),
                    onClick = { onToggleEnzyme(name) },
                    label = { Text(name, fontSize = 12.sp) }
                )
            }
            val e = Enzymes.byName("BsaI")!!
            AssistChip(
                onClick = { onToggleEnzyme(e.name) },
                label = { Text("BsaI（Golden Gate）", fontSize = 12.sp) }
            )
        }

        if (showFeatures) {
            Card(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(bottom = 8.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
            ) {
                Column(Modifier.padding(12.dp).height(150.dp)) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("特征注释（${record.features.size}）", style = MaterialTheme.typography.labelLarge)
                        TextButton(onClick = { showFeatures = false }) { Text("收起") }
                    }
                    LazyColumn(Modifier.fillMaxWidth()) {
                        items(record.features) { f ->
                            Row(
                                Modifier.fillMaxWidth().padding(vertical = 3.dp),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(f.name, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                                Text(
                                    "${f.start}–${f.end} · ${f.type}${if (f.strand < 0) " (−)" else ""}",
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private val baseColors = mapOf(
    'A' to Color(0xFF7ED957),
    'T' to Color(0xFFFF8A80),
    'G' to Color(0xFFFFC56E),
    'C' to Color(0xFF69C9F0),
    'N' to Color(0xFF9E9E9E)
)

@Composable
private fun SequenceView(record: SeqRecord, cutPositions: Set<Int>, modifier: Modifier = Modifier) {
    val rows = remember(record.sequence) {
        val seq = record.sequence
        (0 until (seq.length + 59) / 60).map { i ->
            val start = i * 60
            seq.substring(start, minOf(start + 60, seq.length))
        }
    }
    val mono = FontFamily.Monospace
    LazyColumn(modifier.background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.18f)).padding(8.dp)) {
        itemsIndexed(rows) { index, row ->
            val rowStart = index * 60 + 1
            val annotated: AnnotatedString = buildAnnotatedString {
                withStyle(SpanStyle(color = MaterialTheme.colorScheme.onSurfaceVariant, fontFamily = mono, fontSize = 11.sp)) {
                    append("%5d ".format(rowStart))
                }
                row.forEachIndexed { idx, c ->
                    val pos = rowStart + idx
                    val highlighted = cutPositions.contains(pos)
                    val color = baseColors[c] ?: Color.Unspecified
                    pushStyle(SpanStyle(color = color, fontFamily = mono, fontSize = 12.sp))
                    append(c)
                    pop()
                    if (highlighted) {
                        withStyle(SpanStyle(color = Color(0xFFFF5252), fontFamily = mono, fontSize = 11.sp)) { append("|") }
                    } else if ((idx + 1) % 10 == 0) {
                        append(" ")
                    }
                }
            }
            Text(annotated, modifier = Modifier.padding(vertical = 1.dp))
        }
    }
}
