package com.spice.nativeapp.tools

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.spice.nativeapp.core.ImageTools

private enum class PhotoMode(val label: String) { GEL("凝胶条带"), COLONY("菌落计数") }

@Composable
fun PhotoScreen(modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var mode by remember { mutableStateOf(PhotoMode.GEL) }
    var bitmap by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    var bands by remember { mutableStateOf<List<ImageTools.Band>>(emptyList()) }
    var colonies by remember { mutableStateOf<List<ImageTools.Colony>>(emptyList()) }
    var status by remember { mutableStateOf("选择一张凝胶或菌落照片开始分析") }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        val bmp = ImageTools.loadScaled(ctx, uri)
        if (bmp == null) { status = "图片解码失败"; return@rememberLauncherForActivityResult }
        bitmap = bmp
        bands = emptyList(); colonies = emptyList()
        status = "已载入 ${bmp.width}×${bmp.height}，点「开始分析」"
    }

    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("照片分析（本地图像算法）", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text("纯 Kotlin：灰度化 → 泳道切分 → 行投影峰检测 / 连通域计数。不需要联网与额外模型。", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PhotoMode.values().forEach { m ->
                FilterChip(selected = mode == m, onClick = { mode = m }, label = { Text(m.label, fontSize = 11.sp) })
            }
        }
        OutlinedButton(onClick = { launcher.launch(arrayOf("image/*")) }, modifier = Modifier.fillMaxWidth()) {
            Text("选择照片")
        }
        Text(status, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)

        val bmp = bitmap
        if (bmp != null) {
            Button(
                onClick = {
                    val gray = ImageTools.toGray(bmp)
                    when (mode) {
                        PhotoMode.GEL -> {
                            bands = ImageTools.analyzeGel(gray)
                            status = "检出 ${bands.size} 条带（${bands.map { it.lane }.distinct().size} 条泳道）"
                        }
                        PhotoMode.COLONY -> {
                            colonies = ImageTools.countColonies(gray).colonies
                            status = "检出 ${colonies.size} 个菌落"
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("开始分析") }

            if (bands.isNotEmpty() || colonies.isNotEmpty()) {
                Image(
                    bitmap = ImageTools.overlay(bmp, bands, colonies).asImageBitmap(),
                    contentDescription = "识别结果",
                    modifier = Modifier.fillMaxWidth(),
                    contentScale = ContentScale.FillWidth
                )
            }
        }

        if (bands.isNotEmpty()) {
            Text("条带结果", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            bands.take(30).forEach { b ->
                Text(
                    "泳道 ${b.lane + 1} · y=${"%.0f".format(b.yFraction * 100)}%% · 强度 ${"%.2f".format(b.strength)} · 估计 ${b.estBp} bp",
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
            OutlinedButton(
                onClick = {
                    clipboard.setText(AnnotatedString(
                        "lane,y_percent,strength,est_bp\n" + bands.joinToString("\n") {
                            "${it.lane + 1},${"%.1f".format(it.yFraction * 100)},${"%.3f".format(it.strength)},${it.estBp}"
                        }
                    ))
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("复制 CSV") }
            Text("提示：估计 bp 为默认 100 bp–10 kb 标定；精确值需把 ladder 泳道的 y 与已知长度做 log10 线性回归。", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        if (colonies.isNotEmpty()) {
            Text("菌落结果", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Text("总数 ${colonies.size} · 平均面积 ${"%.0f".format(colonies.map { it.area }.average())} px²", fontSize = 12.sp)
            val areaRanges = colonies.groupingBy {
                when {
                    it.area < 80 -> "小（<80px²）"
                    it.area < 300 -> "中（80–300）"
                    else -> "大（>300）"
                }
            }.eachCount()
            areaRanges.forEach { (k, v) -> Text("$k：$v 个", fontSize = 11.sp) }
            OutlinedButton(
                onClick = {
                    clipboard.setText(AnnotatedString(
                        "index,x,y,area\n" + colonies.mapIndexed { i, c -> "${i + 1},${c.x},${c.y},${c.area}" }.joinToString("\n")
                    ))
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("复制 CSV") }
        }
    }
}
