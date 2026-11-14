package com.spice.nativeapp.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Label
import androidx.compose.material.icons.filled.Notes
import androidx.compose.material.icons.filled.PanTool
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.spice.nativeapp.core.Bio
import com.spice.nativeapp.core.Feature
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * §3.4/§4.1 组件移植（对照 SPICE_main/src/lib/gene/）：
 *  - SequenceMinimap.svelte:122 → [SequenceMinimap]（22px 画布：特征彩条+选区蓝+视口框+点击跳转）
 *  - SelectionToolkit.svelte:63 → [SelectionToolkitBar]（深底 #0a0a0a 等宽字体横条）
 *  - SequenceToolbar.svelte:142 → [SequenceToolRail]（44px 竖工具轨 8 钮）
 *  - DnaViewer.svelte:1401 选区属性浮卡 → [SelectionPropertiesCard]（4-50nt 显示 Tm）
 *  - HybridizationDialog.svelte:75 → [HybridizationDialog]
 *  - tm.ts:115 calculateNebTm → [nebTm]（verbatim 近邻热力学 + 盐校正）
 */

// ── main src/lib/genome/tm.ts:115 calculateNebTm verbatim ──
private val TM_H = mapOf(
    "AA/TT" to -7.9, "AT/TA" to -7.2, "TA/AT" to -7.2, "CA/GT" to -8.5,
    "GT/CA" to -8.4, "CT/GA" to -7.8, "GA/CT" to -8.2, "CG/GC" to -10.6,
    "GC/CG" to -9.8, "GG/CC" to -8.0, "TT/AA" to -7.9, "TG/AC" to -8.5,
    "AC/TG" to -8.4, "AG/TC" to -7.8, "TC/AG" to -8.2, "CC/GG" to -8.0
)
private val TM_S = mapOf(
    "AA/TT" to -22.2, "AT/TA" to -20.4, "TA/AT" to -21.3, "CA/GT" to -22.7,
    "GT/CA" to -22.4, "CT/GA" to -21.0, "GA/CT" to -22.2, "CG/GC" to -27.2,
    "GC/CG" to -24.4, "GG/CC" to -19.9, "TT/AA" to -22.2, "TG/AC" to -22.7,
    "AC/TG" to -22.4, "AG/TC" to -21.0, "TC/AG" to -22.2, "CC/GG" to -19.9
)

/** NEB Tm（main calculateNebTm：4-50nt 有效；简并碱基/缺表项返回 null） */
fun nebTm(seq: String, monoConc: Double = 0.05, primerConc: Double = 0.0000005): Double? {
    if (seq.length < 4 || seq.length > 50) return null
    val s0 = seq.uppercase()
    if (s0.any { it !in "ATGC" }) return null
    var h = 0.0; var s = 0.0; var hi = 0.0; var si = 0.0
    val r = 1.987
    for (i in s0.indices) {
        val c = s0[i]
        if (i == 0 || i == s0.length - 1) {
            if (c == 'G' || c == 'C') { hi += 0.1; si += -2.8 }
            else { hi += 2.3; si += 4.1 }
        }
        if (i < s0.length - 1) {
            val dimer = s0.substring(i, i + 2)
            val comp = Bio.complement(dimer).uppercase()
            val key = "$dimer/$comp"
            h += TM_H[key] ?: return null
            s += TM_S[key] ?: return null
        }
    }
    val deltaH = h + hi
    val deltaS = s + si
    val meltingTemp = deltaH * 1000.0 / (deltaS + r * ln(primerConc)) - 273.15
    if (monoConc != 0.0) {
        val lnC = ln(monoConc)
        val gc = Bio.gcContent(s0) / 100.0
        val salt = (4.29 * gc - 3.95) * 10.0.pow(-5.0) * lnC + 9.4.pow(-6.0) * lnC * lnC
        return 1.0 / (1.0 / (meltingTemp + 273.15) + salt) - 273.15
    }
    return meltingTemp
}

/** CDS/基因估算分子量（main getFeatureStats：len/3*110/1000，1 位小数） */
fun featureMwKda(f: Feature): Double? {
    val t = f.type.lowercase()
    return if (t == "cds" || t == "gene" || t == "exon")
        kotlin.math.round((f.end - f.start + 1) / 3.0 * 110 / 1000 * 10) / 10.0
    else null
}

// ───────────────────────── SequenceMinimap（main SequenceMinimap.svelte） ─────────────────────────

@Composable
fun SequenceMinimap(
    dnaLen: Int,
    features: List<Feature>,
    selection: IntRange?,
    listState: LazyListState,
    onJump: (Float) -> Unit
) {
    val layout = listState.layoutInfo
    val totalItems = layout.totalItemsCount
    val visible = layout.visibleItemsInfo
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(22.dp)
            .drawBehind { drawLine(Color(0xFF222222), Offset(0f, size.height - 0.5.dp.toPx()), Offset(size.width, size.height - 0.5.dp.toPx()), 1f) }
            .pointerInput(Unit) {
                detectTapGestures { pos -> onJump((pos.x / size.width).coerceIn(0f, 1f)) }
            }
    ) {
        val w = size.width
        val h = size.height
        drawRect(Color(0xFF0A0A0A), size = size)
        if (dnaLen <= 0 || w <= 0) return@Canvas
        // 特征彩条（main: y=3..h-3，最小宽 2px）
        features.forEach { f ->
            val left = (f.start - 1f) / dnaLen * w
            val fw = ((f.end - f.start + 1f) / dnaLen * w).coerceAtLeast(2f)
            val cx = left.coerceIn(0f, (w - 1).coerceAtLeast(0f))
            val cw = (fw.coerceAtMost(w - cx)).coerceAtLeast(0f)
            if (cw > 0) drawRect(featureColorOf(f), topLeft = Offset(cx, 3f), size = Size(cw, h - 6f))
        }
        // 选区蓝（main rgba(10,132,255,0.4)）
        if (selection != null && !(selection.first == 1 && selection.last == 1)) {
            val sl = (selection.first - 1f) / dnaLen * w
            val sw = ((selection.last - selection.first + 1f) / dnaLen * w).coerceAtLeast(1f)
            drawRect(Color(0x660A84FF), topLeft = Offset(sl.coerceIn(0f, w - 1), 0f), size = Size(sw.coerceAtMost(w - sl.coerceIn(0f, w - 1)), h))
        }
        // 视口框（main rgba(255,255,255,0.15) + 0.3 描边；内容塞满时整宽）
        val first = visible.firstOrNull()
        if (first != null && totalItems > visible.size && totalItems > 0) {
            val rowH = first.size.coerceAtLeast(1)
            val scrollTopPx = first.index * rowH - listState.firstVisibleItemScrollOffset
            val totalH = (totalItems * rowH).coerceAtLeast(1)
            val startPct = (scrollTopPx.toFloat() / totalH).coerceIn(0f, 1f)
            val visPct = (visible.size.toFloat() / totalItems).coerceAtMost(1f)
            val vx = startPct * w
            val vw = (visPct * w).coerceAtLeast(12f)
            drawRect(Color(0x26FFFFFF), topLeft = Offset(vx, 0f), size = Size(vw, h))
            drawRect(Color(0x4DFFFFFF), topLeft = Offset(vx, 0f), size = Size(vw, h), style = Stroke(1f))
        } else {
            drawRect(Color(0x26FFFFFF), size = size)
            drawRect(Color(0x4DFFFFFF), size = size, style = Stroke(1f))
        }
    }
}

// ───────────────────────── SelectionToolkit 横条（main SelectionToolkit.svelte） ─────────────────────────

@Composable
fun SelectionToolkitBar(
    sel: IntRange,
    dna: String,
    onCopy: () -> Unit,
    onCopyImage: () -> Unit,
    onRevComp: () -> Unit,
    onDelete: () -> Unit,
    onCancel: () -> Unit
) {
    val seg = dna.substring((sel.first - 1).coerceIn(0, dna.length), sel.last.coerceAtMost(dna.length))
    val gc = Bio.gcContent(seg)
    val tm = nebTm(seg)
    Row(
        Modifier
            .fillMaxWidth()
            .background(Color(0xFF0A0A0A))
            .drawBehind { drawLine(Color(0xFF222222), Offset(0f, 0f), Offset(size.width, 0f), 1.dp.toPx()) }
            .padding(horizontal = 10.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            buildString {
                append("${sel.first}..${sel.last} = ${sel.last - sel.first + 1} bp")
                append(" [${"%.1f".format(gc)}% GC]")
                tm?.let { append(" | Tm = ${it.roundToInt()}°C") }
            },
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            color = Color(0xFFE0E0E0),
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        // main .snap-action-btn：bg #2a2a2a / #b0b0b0 / 11sp / radius 3
        TkBtn(com.spice.nativeapp.core.I18n.t("复制"), onCopy)                                  // title=复制序列与特征
        TkBtn(com.spice.nativeapp.core.I18n.t("复制图像"), onCopyImage, tint = Color(0xFF4CD6FF), bold = true)  // title=将选区复制为高分辨率 PNG
        TkBtn("RevComp", onRevComp)                            // title=互补反转
        TkBtn(com.spice.nativeapp.core.I18n.t("删除"), onDelete, tint = Color(0xFFFF453A))      // danger
        TkBtn(com.spice.nativeapp.core.I18n.t("取消"), onCancel)
    }
}

@Composable
private fun TkBtn(label: String, onClick: () -> Unit, tint: Color = Color(0xFFB0B0B0), bold: Boolean = false) {
    Text(
        label,
        fontSize = 11.sp,
        color = tint,
        fontWeight = if (bold) FontWeight.Bold else null,
        maxLines = 1,
        modifier = Modifier
            .padding(start = 6.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(Color(0xFF2A2A2A))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 2.dp)
    )
}

// ───────────────────────── 序列工具轨（main SequenceToolbar.svelte 44px 竖轨 8 钮） ─────────────────────────

@Composable
fun SequenceToolRail(
    seqTool: String,
    onSeqTool: (String) -> Unit,
    showEnzymes: Boolean,
    showFeatures: Boolean,
    showTranslations: Boolean,
    onToggleEnzymes: () -> Unit,
    onToggleFeatures: () -> Unit,
    onToggleTranslations: () -> Unit,
    onOpenHybridization: () -> Unit,
    onAddNote: () -> Unit
) {
    Column(
        Modifier
            .width(44.dp)
            .fillMaxHeight()
            .background(Color(0xFF1A1A1A))
            .padding(4.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        RailBtn(Icons.Filled.PanTool, com.spice.nativeapp.core.I18n.t("浏览模式"), seqTool == "browse", { onSeqTool("browse") })
        RailBtn(Icons.Filled.Edit, com.spice.nativeapp.core.I18n.t("编辑模式"), seqTool == "edit", { onSeqTool("edit") })
        // main 注释模式按钮常显 accent-2 色（#4cd6ff）
        RailBtn(Icons.Filled.Label, com.spice.nativeapp.core.I18n.t("注释模式"), seqTool == "annotate", { onSeqTool("annotate") }, Color(0xFF4CD6FF))
        HorizontalDivider(color = Color(0xFF333333), modifier = Modifier.width(36.dp).padding(vertical = 2.dp))
        RailBtn(Icons.Filled.ContentCut, com.spice.nativeapp.core.I18n.t("显示酶切位点"), showEnzymes, onToggleEnzymes)
        RailBtn(Icons.Filled.Flag, com.spice.nativeapp.core.I18n.t("显示特征"), showFeatures, onToggleFeatures)
        // main: ArrowRight 旋转 -45°（PCR 引物杂交配置）
        Box(Modifier.size(36.dp).clip(RoundedCornerShape(6.dp)).background(Color(0xFF2A2A2A)).clickable(onClick = onOpenHybridization), contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.ArrowForward, com.spice.nativeapp.core.I18n.t("退火杂交配置"), tint = Color(0xFF8A8A8A), modifier = Modifier.size(18.dp).graphicsLayer { rotationZ = -45f })
        }
        RailBtn(Icons.Filled.Translate, com.spice.nativeapp.core.I18n.t("显示翻译"), showTranslations, onToggleTranslations)
        // main 记批注按钮常显 #4cd6ff
        RailBtn(Icons.Filled.Notes, com.spice.nativeapp.core.I18n.t("对当前选区做实验批注"), false, onAddNote, Color(0xFF4CD6FF))
    }
}

@Composable
private fun RailBtn(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    active: Boolean,
    onClick: () -> Unit = {},
    accent: Color? = null
) {
    Box(
        Modifier
            .size(36.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(if (active) Color(0xFF007A8A) else Color(0xFF2A2A2A))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, label, tint = accent ?: if (active) Color.White else Color(0xFF8A8A8A), modifier = Modifier.size(18.dp))
    }
}

// ───────────────────────── 选区属性浮卡（main DnaViewer.svelte:1401，底部居中 280px） ─────────────────────────

@Composable
fun SelectionPropertiesCard(
    sel: IntRange,
    dna: String,
    onAddNote: () -> Unit,
    onCopy: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val seg = dna.substring((sel.first - 1).coerceIn(0, dna.length), sel.last.coerceAtMost(dna.length))
    val gc = Bio.gcContent(seg)
    val tm = nebTm(seg)
    Column(
        modifier
            .width(280.dp)
            .background(Color(0xFF0C101A), RoundedCornerShape(6.dp))
            .border(1.5.dp, Color(0xFF4CD6FF), RoundedCornerShape(6.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(com.spice.nativeapp.core.I18n.t("选区特性属性 ✥"), fontSize = 10.5.sp, fontWeight = FontWeight.Bold, color = Color(0xFF4CD6FF), modifier = Modifier.weight(1f))
            Text("[X]", fontSize = 10.sp, color = Color(0xFFFF453A), modifier = Modifier.clickable(onClick = onDismiss).padding(2.dp))
        }
        Spacer(Modifier.height(4.dp))
        listOfNotNull(
            com.spice.nativeapp.core.I18n.t("物理区间:") to "${sel.first}..${sel.last} bp",
            com.spice.nativeapp.core.I18n.t("选区长度:") to "${sel.last - sel.first + 1} bp",
            com.spice.nativeapp.core.I18n.t("GC 含量:") to "${"%.1f".format(gc)}%",
            tm?.let { com.spice.nativeapp.core.I18n.t("退火温度 (Tm):") to "${it.roundToInt()} °C" }
        ).forEach { (k, v) ->
            Row(Modifier.fillMaxWidth().padding(vertical = 1.dp)) {
                Text(k, fontSize = 10.sp, color = Color(0xFF8892A6), modifier = Modifier.weight(1f))
                Text(v, fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = if (k.startsWith("GC")) Color(0xFF4CD6FF) else if (k.startsWith("退火")) Color(0xFF53D769) else Color(0xFFE0E8F0))
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(onClick = onAddNote, contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp), modifier = Modifier.weight(1f).heightIn(min = 32.dp)) { Text(com.spice.nativeapp.core.I18n.t("记批注"), fontSize = 9.sp, maxLines = 1) }
            TextButton(onClick = onCopy, contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp), modifier = Modifier.weight(1f).heightIn(min = 32.dp)) { Text(com.spice.nativeapp.core.I18n.t("复制"), fontSize = 9.sp, maxLines = 1) }
        }
    }
}

// ───────────────────────── 退火杂交配置（main HybridizationDialog.svelte） ─────────────────────────

@Composable
fun HybridizationDialog(
    onDismiss: () -> Unit,
    onConfirm: (minBindingBases: Int, requireTm: Int) -> Unit
) {
    var minBinding by remember { mutableStateOf("10") }
    var requireTm by remember { mutableStateOf("40") }
    var allowMismatch by remember { mutableStateOf(true) }
    var showExtra by remember { mutableStateOf(true) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(com.spice.nativeapp.core.I18n.t("退火杂交配置"), style = MaterialTheme.typography.titleSmall) },  // main hybridizationParameters
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(com.spice.nativeapp.core.I18n.t("杂交参数决定多轨道视图中显示多少个结合位点。"), fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(com.spice.nativeapp.core.I18n.t("引物 3' 端最少退火碱基数:"), fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                    OutlinedTextField(value = minBinding, onValueChange = { minBinding = it.filter { c -> c.isDigit() } }, textStyle = androidx.compose.ui.text.TextStyle(fontSize = 10.sp, fontFamily = FontFamily.Monospace), singleLine = true, modifier = Modifier.width(64.dp))
                    Text(" bp", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = allowMismatch, onCheckedChange = { allowMismatch = it })
                    Text(com.spice.nativeapp.core.I18n.t("允许单一位点错配 (Allow a single isolated mismatch)"), fontSize = 9.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(com.spice.nativeapp.core.I18n.t("要求引物退火 Tm 至少为:"), fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                    OutlinedTextField(value = requireTm, onValueChange = { requireTm = it.filter { c -> c.isDigit() } }, textStyle = androidx.compose.ui.text.TextStyle(fontSize = 10.sp, fontFamily = FontFamily.Monospace), singleLine = true, modifier = Modifier.width(64.dp))
                    Text(" °C", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = showExtra, onCheckedChange = { showExtra = it })
                    Text(com.spice.nativeapp.core.I18n.t("显示在引物 3' 端不匹配、但在 5' 端匹配至少 15 碱基的额外位点"), fontSize = 9.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(minBinding.toIntOrNull() ?: 10, requireTm.toIntOrNull() ?: 40); onDismiss() }) { Text("OK") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(com.spice.nativeapp.core.I18n.t("取消")) }
        }
    )
}

/** 「复制图像」（main tkCopyPng：选区高分辨率 PNG）：深底等宽排版选区文本并导出 PNG */
fun exportSelectionPng(ctx: android.content.Context, name: String, sel: IntRange, seg: String): String {
    val scale = 4
    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 16f * scale
        color = 0xFFE2E8F0.toInt()
        typeface = android.graphics.Typeface.MONOSPACE
    }
    val header = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 18f * scale
        color = 0xFF4CD6FF.toInt()
        typeface = android.graphics.Typeface.MONOSPACE
    }
    val textLines = seg.chunked(60)
    val lineH = 22f * scale
    val w = (paint.measureText("A") * 60 + 48f * scale).toInt().coerceAtLeast(200)
    val h = (textLines.size * lineH + 40f * scale).toInt().coerceAtLeast(100)
    val bmp = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bmp)
    canvas.drawColor(0xFF0B0F17.toInt())
    canvas.drawText("$name ${sel.first}..${sel.last} (${seg.length} bp)", 24f * scale, 28f * scale, header)
    textLines.forEachIndexed { i, line ->
        canvas.drawText(line, 24f * scale, 56f * scale + i * lineH, paint)
    }
    val dir = ctx.getExternalFilesDir(android.os.Environment.DIRECTORY_PICTURES)
    val f = java.io.File(dir, "${name.replace(' ', '_')}_sel_${sel.first}_${sel.last}_${System.currentTimeMillis()}.png")
    f.outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    return f.absolutePath
}
