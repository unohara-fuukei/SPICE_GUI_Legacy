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
import androidx.compose.runtime.rememberCoroutineScope
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
import com.spice.nativeapp.core.GBand
import com.spice.nativeapp.core.GelGenie
import com.spice.nativeapp.core.ImageTools
import com.spice.nativeapp.core.ModelStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint

private enum class PhotoMode(val label: String) { GEL("凝胶条带"), COLONY("菌落计数") }

@Composable
fun PhotoScreen(modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    var mode by remember { mutableStateOf(PhotoMode.GEL) }
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    var bands by remember { mutableStateOf<List<ImageTools.Band>>(emptyList()) }
    var colonies by remember { mutableStateOf<List<ImageTools.Colony>>(emptyList()) }
    var status by remember { mutableStateOf("选择一张凝胶或菌落照片开始分析") }

    var ggStatus by remember { mutableStateOf(ModelStore.status(ctx)) }
    var ggBusy by remember { mutableStateOf(false) }
    var ggProgress by remember { mutableStateOf("") }
    var ggBands by remember { mutableStateOf<List<GBand>>(emptyList()) }
    var ggImage by remember { mutableStateOf<Bitmap?>(null) }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        val bmp = ImageTools.loadScaled(ctx, uri)
        if (bmp == null) { status = com.spice.nativeapp.core.I18n.t("图片解码失败"); return@rememberLauncherForActivityResult }
        bitmap = bmp
        bands = emptyList(); colonies = emptyList(); ggBands = emptyList(); ggImage = null
        status = com.spice.nativeapp.core.I18n.tf("已载入 {v1}×{v2}，点「开始分析」", bmp.width, bmp.height)
    }

    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(com.spice.nativeapp.core.I18n.t("照片分析"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(com.spice.nativeapp.core.I18n.t("本地规则算法离线可用；GelGenie U-Net 模型按需下载到本机后做像素级条带分割。"), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PhotoMode.values().forEach { m ->
                FilterChip(selected = mode == m, onClick = { mode = m }, label = { Text(m.label, fontSize = 11.sp) })
            }
        }
        OutlinedButton(onClick = { launcher.launch(arrayOf("image/*")) }, modifier = Modifier.fillMaxWidth()) { Text(com.spice.nativeapp.core.I18n.t("选择照片")) }
        Text(status, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)

        // ONNX 模型区
        if (ggStatus.ok && ggStatus.shaMatch) {
            Text(com.spice.nativeapp.core.I18n.tf("GelGenie 模型已就绪（{v1} MB）", "%.1f".format(ggStatus.sizeBytes / 1048576.0)), fontSize = 11.sp, color = MaterialTheme.colorScheme.primary)
        } else {
            Text(com.spice.nativeapp.core.I18n.tf("AI 模型未下载{v1}，首次使用按需获取", if (ggStatus.sizeBytes > 0) "（校验未通过，需重新下载）" else ""), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (ggProgress.isNotBlank()) Text(ggProgress, fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Button(
            onClick = {
                ggBusy = true
                scope.launch {
                    val res = withContext(Dispatchers.IO) {
                        ModelStore.download(ctx, { d, t ->
                            ggProgress = if (t > 0) com.spice.nativeapp.core.I18n.tf("下载 {v1} / {v2} MB", "%.1f".format(d / 1048576.0), "%.1f".format(t / 1048576.0)) else com.spice.nativeapp.core.I18n.tf("已下载 {v1} MB", "%.1f".format(d / 1048576.0))
                        }, { ggProgress = it })
                    }
                    ggStatus = ModelStore.status(ctx)
                    ggBusy = false
                    ggProgress = if (res.isSuccess) com.spice.nativeapp.core.I18n.t("模型就绪") else com.spice.nativeapp.core.I18n.tf("下载失败：{v1}", res.exceptionOrNull()?.message)
                }
            },
            enabled = !ggBusy,
            modifier = Modifier.fillMaxWidth()
        ) { Text(if (ggBusy) com.spice.nativeapp.core.I18n.t("准备中…") else if (ggStatus.ok) com.spice.nativeapp.core.I18n.t("重新校验 / 下载模型") else com.spice.nativeapp.core.I18n.t("下载 GelGenie 模型")) }

        val bmp = bitmap
        if (bmp != null) {
            Button(
                onClick = {
                    val gray = ImageTools.toGray(bmp)
                    when (mode) {
                        PhotoMode.GEL -> {
                            bands = ImageTools.analyzeGel(gray)
                            status = com.spice.nativeapp.core.I18n.tf("规则算法检出 {v1} 条带（{v2} 条泳道）", bands.size, bands.map { it.lane }.distinct().size)
                        }
                        PhotoMode.COLONY -> {
                            colonies = ImageTools.countColonies(gray).colonies
                            status = com.spice.nativeapp.core.I18n.tf("规则算法检出 {v1} 个菌落", colonies.size)
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text(com.spice.nativeapp.core.I18n.t("规则算法分析")) }

            Button(
                onClick = {
                    scope.launch {
                        ggBusy = true
                        status = com.spice.nativeapp.core.I18n.t("ONNX 推理中…")
                        val res = withContext(Dispatchers.IO) {
                            runCatching {
                                // 推理图压到 512 长边，保护手机内存；归一化框仍映射回原图预览
                                val scale = minOf(1f, 512f / maxOf(bmp.width, bmp.height))
                                val infW = (bmp.width * scale).toInt().coerceAtLeast(16)
                                val infH = (bmp.height * scale).toInt().coerceAtLeast(16)
                                val small = Bitmap.createScaledBitmap(bmp, infW, infH, true)
                                val gray = ImageTools.toGrayBytes(small)
                                GelGenie.segment(ctx, gray, infW, infH)
                            }
                        }
                        ggBusy = false
                        res.onSuccess { r ->
                            ggBands = r.bands
                            val out = bmp.copy(Bitmap.Config.ARGB_8888, true)
                            val canvas = Canvas(out)
                            val paint = Paint().apply {
                                color = Color.rgb(255, 196, 0)
                                style = Paint.Style.STROKE
                                strokeWidth = 3f
                            }
                            r.bands.forEach { b ->
                                val l = b.x * out.width; val t = b.y * out.height
                                canvas.drawRect(l, t, l + b.width * out.width, t + b.height * out.height, paint)
                            }
                            ggImage = out
                            status = com.spice.nativeapp.core.I18n.tf("GelGenie 检出 {v1} 个条带、{v2} 条泳道（{v3} ms）", r.bands.size, r.lanes.size, r.runMs)
                        }.onFailure { status = com.spice.nativeapp.core.I18n.tf("推理失败：{v1}", it.message) }
                    }
                },
                enabled = ggStatus.ok && ggStatus.shaMatch && !ggBusy,
                modifier = Modifier.fillMaxWidth()
            ) { Text(com.spice.nativeapp.core.I18n.t("AI 模型分割（GelGenie）")) }

            if (bands.isNotEmpty() || colonies.isNotEmpty()) {
                Image(
                    bitmap = ImageTools.overlay(bmp, bands, colonies).asImageBitmap(),
                    contentDescription = com.spice.nativeapp.core.I18n.t("规则算法结果"),
                    modifier = Modifier.fillMaxWidth(),
                    contentScale = ContentScale.FillWidth
                )
            }
            ggImage?.let {
                Image(
                    bitmap = it.asImageBitmap(),
                    contentDescription = com.spice.nativeapp.core.I18n.t("AI 分割结果"),
                    modifier = Modifier.fillMaxWidth(),
                    contentScale = ContentScale.FillWidth
                )
            }
        }

        if (ggBands.isNotEmpty()) {
            Text(com.spice.nativeapp.core.I18n.t("AI 分割结果"), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            ggBands.take(40).forEach { b ->
                Text(
                    com.spice.nativeapp.core.I18n.tf("泳道 {v1} · x={v2}% y={v3}% · {v4}×{v5}% · 强度 {v6}", b.lane + 1, "%.0f".format(b.x * 100), "%.0f".format(b.y * 100), "%.0f".format(b.width * 100), "%.0f".format(b.height * 100), "%.2f".format(b.intensity)),
                    fontSize = 11.sp, fontFamily = FontFamily.Monospace
                )
            }
            OutlinedButton(
                onClick = {
                    clipboard.setText(AnnotatedString(
                        "lane,x,y,width,height,intensity\n" + ggBands.joinToString("\n") {
                            "${it.lane + 1},${"%.3f".format(it.x)},${"%.3f".format(it.y)},${"%.3f".format(it.width)},${"%.3f".format(it.height)},${"%.3f".format(it.intensity)}"
                        }
                    ))
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text(com.spice.nativeapp.core.I18n.t("复制 AI 结果 CSV")) }
        }

        if (bands.isNotEmpty()) {
            Text(com.spice.nativeapp.core.I18n.t("规则算法条带"), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            bands.take(30).forEach { b ->
                Text(
                    com.spice.nativeapp.core.I18n.tf("泳道 {v1} · y={v2}% · 强度 {v3} · 估计 {v4} bp", b.lane + 1, "%.0f".format(b.yFraction * 100), "%.2f".format(b.strength), b.estBp),
                    fontSize = 11.sp, fontFamily = FontFamily.Monospace
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
            ) { Text(com.spice.nativeapp.core.I18n.t("复制规则结果 CSV")) }
        }

        if (colonies.isNotEmpty()) {
            Text(com.spice.nativeapp.core.I18n.t("菌落结果"), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Text(com.spice.nativeapp.core.I18n.tf("总数 {v1} · 平均面积 {v2} px²", colonies.size, "%.0f".format(colonies.map { it.area }.average())), fontSize = 12.sp)
            OutlinedButton(
                onClick = {
                    clipboard.setText(AnnotatedString(
                        "index,x,y,area\n" + colonies.mapIndexed { i, c -> "${i + 1},${c.x},${c.y},${c.area}" }.joinToString("\n")
                    ))
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text(com.spice.nativeapp.core.I18n.t("复制 CSV")) }
        }
    }
}
