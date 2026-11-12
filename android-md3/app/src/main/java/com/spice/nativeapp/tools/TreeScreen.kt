package com.spice.nativeapp.tools

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

data class Node(val name: String, val height: Float, val left: Node?, val right: Node?, val leaves: List<String>)

/** k-mer 相似度距离 = 1 - 共享 4-mer 比例 */
fun kmerDistance(a: String, b: String, k: Int = 4): Double {
    val sa = a.windowed(k, 1, false).toSet()
    val sb = b.windowed(k, 1, false).toSet()
    if (sa.isEmpty() || sb.isEmpty()) return 1.0
    val inter = sa.intersect(sb).size.toDouble()
    val union = sa.union(sb).size.toDouble()
    return 1.0 - inter / union
}

/** UPGMA 聚类 */
fun upgma(names: List<String>, seqs: List<String>): Node? {
    if (names.isEmpty()) return null
    var clusters = names.mapIndexed { i, n -> Node(n, 0f, null, null, listOf(n)) }.toMutableList()
    val dist = Array(names.size) { i -> DoubleArray(names.size) { j -> if (i == j) 0.0 else kmerDistance(seqs[i], seqs[j]) } }
    val members = names.indices.map { mutableListOf(it) }.toMutableList()
    while (clusters.size > 1) {
        var bi = 0; var bj = 1; var best = Double.MAX_VALUE
        for (i in clusters.indices) for (j in i + 1 until clusters.size) {
            val d = members[i].map { a -> members[j].map { b -> dist[a][b] }.average() }.average()
            if (d < best) { best = d; bi = i; bj = j }
        }
        val left = clusters[bi]
        val right = clusters[bj]
        val h = (best / 2.0).toFloat()
        val merged = Node(left.name + "+" + right.name, h, left, right, left.leaves + right.leaves)
        clusters[bi] = merged
        members[bi] = (members[bi] + members[bj]).toMutableList()
        clusters.removeAt(bj)
        members.removeAt(bj)
    }
    return clusters.first()
}

private fun drawTree(node: Node, y0: Float, y1: Float, xScale: Float, maxH: Float, onSeg: (Offset, Offset, Boolean) -> Unit): Float {
    return 0f
}

@Composable
fun TreeScreen(modifier: Modifier = Modifier) {
    var input by remember {
        mutableStateOf(
            ">seqA\nATGCGTACGTAGCTAGCTAGCATCGATCGTAGCTAGCTA\n" +
                ">seqB\nATGCGTACGTAGCTAGCTAGCATCGATCGTAGCTAGCTT\n" +
                ">seqC\nTTGCGTACGAAGCTAGCTAGCATCGATCGTAGCTAGCTA\n" +
                ">seqD\nGGGGGGGGGGCCCCCCCCCAAAAAAAAAATTTTTTTTTT"
        )
    }
    var tree by remember { mutableStateOf<Node?>(null) }

    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("系统发生树（UPGMA）", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text("距离 = 1 − 共享 4-mer 比例（Jaccard），聚合式层次聚类。适合快速比较近缘序列。", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)

        OutlinedTextField(
            value = input,
            onValueChange = { input = it },
            label = { Text("多序列 FASTA", fontSize = 12.sp) },
            modifier = Modifier.fillMaxWidth(),
            minLines = 5,
            textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
        )

        Button(onClick = {
            val entries = parseMultiFasta(input)
            tree = if (entries.size >= 2) upgma(entries.map { it.first }, entries.map { it.second }) else null
        }, modifier = Modifier.fillMaxWidth()) { Text("构建树") }

        val t = tree
        if (t != null) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))) {
                Canvas(Modifier.fillMaxWidth().height(320.dp).padding(8.dp)) {
                    val leaves = t.leaves
                    val maxH = maxOf(1f, t.height)
                    val leafGap = (size.height - 30.dp.toPx()) / maxOf(1, leaves.size)
                    val primary = Color(0xFF7FE7D6)
                    fun leafY(name: String) = 10.dp.toPx() + leaves.indexOf(name) * leafGap + leafGap / 2
                    fun draw(n: Node): Float {
                        val x = 20.dp.toPx() + (n.height / maxH) * (size.width - 60.dp.toPx())
                        if (n.left == null || n.right == null) return leafY(n.name)
                        val yl = draw(n.left)
                        val yr = draw(n.right)
                        val ym = (yl + yr) / 2f
                        val xLeft = 20.dp.toPx() + (n.left.height / maxH) * (size.width - 60.dp.toPx())
                        val xRight = 20.dp.toPx() + (n.right.height / maxH) * (size.width - 60.dp.toPx())
                        drawLine(primary, Offset(x, yl), Offset(xLeft, yl), 1.5.dp.toPx())
                        drawLine(primary, Offset(x, yr), Offset(xRight, yr), 1.5.dp.toPx())
                        drawLine(primary, Offset(x, yl), Offset(x, yr), 1.5.dp.toPx())
                        return ym
                    }
                    draw(t)
                }
            }
            Text("聚类顺序：" + t.leaves.joinToString(" / "), fontSize = 11.sp)
            Text("根节点高度 %.3f".format(t.height), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** 极简多序列 FASTA 解析 */
fun parseMultiFasta(text: String): List<Pair<String, String>> {
    val out = mutableListOf<Pair<String, String>>()
    var name = ""
    val sb = StringBuilder()
    fun flush() { if (name.isNotBlank() && sb.isNotEmpty()) out.add(name to sb.toString().uppercase()); sb.clear() }
    text.lines().forEach { line ->
        val l = line.trim()
        when {
            l.startsWith(">") -> { flush(); name = l.removePrefix(">").trim().ifBlank { "seq${out.size + 1}" } }
            l.isNotEmpty() -> sb.append(l.filter { it.isLetter() })
        }
    }
    flush()
    return out
}
