package com.spice.nativeapp.ui

// ───────────────────────── Presenter 模式（iA-Presenter 风格全屏演示） ─────────────────────────
// 1:1 移植 main src/lib/project/PresenterView.svelte：
//  - 观众视图 = 当前页；演讲者视图（P）= 当前页 + 下一页预览 + 演讲备注（引用块文本）+ 计时器 + 计数；
//  - 标题叙事色随进度渐变：cyan（冷开场）→ 紫 → 红 → 橙 → 金（PROG_STOPS verbatim）；
//  - 点按舞台前进（main：点击容器 go(1)）；底部 3px 进度条；空态为 projectPresenterEmpty 提示。
// 触屏适配差异（披露）：无键盘 P/F/Esc → 控制条按钮替代（演讲备注开关 / ‹ › 翻页 / 退出）；
// 系统返回 = Esc 关闭；全屏按钮省略（Dialog 本身即全屏）。

import androidx.activity.compose.BackHandler
import androidx.compose.ui.draw.drawBehind
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Slideshow
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ViewAgenda
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Density
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.spice.nativeapp.core.Slide
import com.spice.nativeapp.core.Slides
import com.spice.nativeapp.core.SpBlock
import com.spice.nativeapp.core.SpKind
import com.spice.nativeapp.core.SpTxt
import kotlinx.coroutines.delay

// main app.css 色板 verbatim
private val PIX_FG = Color(0xFFD8E1F2)
private val PIX_FG_DIM = Color(0xFF8B96B5)
private val PIX_CYAN = Color(0xFF4CD6FF)
private val PIX_RED = Color(0xFFFF5D5D)
private val PIX_ACCENT = Color(0xFFFF9F1C)
private val PIX_ACCENT2 = Color(0xFFFFD23F)
private val PIX_BG2 = Color(0xFF121624)
private val PIX_BG3 = Color(0xFF1A2030)
private val PIX_BORDER = Color(0xFF2A3450)
private val PIX_BORDER_HI = Color(0xFF4A5A82)
private val PIX_OVERLAY_BG = Color(0xFF0B0E14)

/** main PROG_STOPS verbatim：cyan → 紫 → 红 → 橙 → 金 */
private val PROG_STOPS = listOf(
    intArrayOf(76, 214, 255),
    intArrayOf(166, 76, 255),
    intArrayOf(255, 93, 93),
    intArrayOf(255, 159, 28),
    intArrayOf(226, 180, 72)
)

private fun progColor(t: Float): Color {
    val x = t.coerceIn(0f, 1f) * (PROG_STOPS.size - 1)
    val i = minOf(PROG_STOPS.size - 2, x.toInt())
    val f = x - i
    val a = PROG_STOPS[i]
    val b = PROG_STOPS[i + 1]
    return Color(
        (a[0] + (b[0] - a[0]) * f).toInt(),
        (a[1] + (b[1] - a[1]) * f).toInt(),
        (a[2] + (b[2] - a[2]) * f).toInt()
    )
}

private fun fmtTime(s: Int): String =
    "%02d:%02d".format(s / 60, s % 60)

@Composable
fun PresenterView(title: String, markdown: String, onClose: () -> Unit) {
    val slides = remember(markdown) { Slides.parseSlides(markdown) }
    var idx by remember { mutableIntStateOf(0) }
    var presenter by remember { mutableStateOf(false) }
    var elapsed by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { while (true) { delay(1000); elapsed++ } }   // main onMount setInterval 1s

    fun go(delta: Int) {
        if (slides.isEmpty()) return
        idx = (idx + delta).coerceIn(0, slides.size - 1)
    }
    BackHandler(onBack = onClose)   // Esc → 退出（触屏差异：系统返回替代）

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = PIX_OVERLAY_BG) {
            if (slides.isEmpty()) {
                // ps-empty（main 文案 verbatim）
                Column(
                    Modifier.fillMaxSize().padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(Icons.Filled.Slideshow, null, tint = PIX_FG_DIM, modifier = Modifier.size(40.dp))
                    Spacer(Modifier.height(14.dp))
                    Text(com.spice.nativeapp.core.I18n.t("当前页暂无可演示内容"), color = PIX_FG_DIM)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        com.spice.nativeapp.core.I18n.t("用法：--- 或一二级标题分页；段落、列表(可嵌套/任务)、表格(可 :-- --: :-: 对齐)、代码、图片直接上屏；引用块 (>) 为演讲备注；支持脚注 [^id] 与 [^id]: 定义、上下标 ^2^ ~2~、==高亮~~删除~~、引用式链接 [文字][id]、行首 // 为注释。"),
                        fontSize = 11.sp, color = PIX_FG_DIM, lineHeight = 19.sp,
                        modifier = Modifier.width(300.dp), textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.height(14.dp))
                    OutlinedButton(onClick = onClose) { Text(com.spice.nativeapp.core.I18n.t("退出")) }
                }
                return@Surface
            }

            val cur = slides[idx.coerceIn(0, slides.size - 1)]
            val next = slides[(idx + 1).coerceIn(0, slides.size - 1)]
            val titleColor = if (slides.size > 1) progColor(idx.toFloat() / (slides.size - 1)) else PIX_CYAN

            Box(Modifier.fillMaxSize()) {
                // ps-main：演讲者模式 = 舞台 + 右侧栏
                Row(Modifier.fillMaxSize()) {
                    // ps-stage：点按舞台前进（main onclick 容器 go(1)）
                    Box(
                        Modifier.weight(1f).fillMaxHeight()
                            .pointerInput(slides.size) { detectTapGestures { go(1) } },
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = if (cur.level == 2) Alignment.Start else Alignment.CenterHorizontally
                        ) {
                            if (cur.title != null) {
                                val glowPx = with(androidx.compose.ui.platform.LocalDensity.current) { 18.dp.toPx() }
                                Text(
                                    cur.title,
                                    color = titleColor,
                                    fontSize = 40.sp,          // clamp(28px,5.2vw,60px)
                                    fontWeight = FontWeight.Black,
                                    letterSpacing = 1.sp,
                                    lineHeight = 46.sp,       // 1.15
                                    textAlign = if (cur.level == 2) TextAlign.Start else TextAlign.Center,
                                    style = androidx.compose.ui.text.TextStyle(
                                        shadow = Shadow(color = titleColor, blurRadius = glowPx)
                                    ),
                                    modifier = Modifier.padding(bottom = 24.dp)
                                )
                            }
                            SlideBody(cur)
                        }
                    }

                    if (presenter) {
                        // ps-side（340px → 320dp 触屏适配）
                        Column(
                            Modifier.width(320.dp).fillMaxHeight()
                                .background(PIX_BG2).padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(fmtTime(elapsed), color = PIX_ACCENT, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                                Text("${idx + 1} / ${slides.size}", color = PIX_FG_DIM, fontWeight = FontWeight.Bold)
                            }
                            SideLabel(com.spice.nativeapp.core.I18n.t("下一页预览"))
                            Box(Modifier.fillMaxWidth().height(120.dp).border(2.dp, PIX_BORDER).background(PIX_BG3).padding(8.dp)) {
                                if (idx < slides.size - 1) {
                                    Column(Modifier.verticalScroll(rememberScrollState())) {
                                        if (next.title != null) {
                                            Text(
                                                next.title, fontSize = 13.sp, fontWeight = FontWeight.Black,
                                                color = when (next.level) { 1 -> PIX_CYAN; 2 -> PIX_ACCENT; else -> PIX_FG },
                                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                                                modifier = Modifier.padding(bottom = 4.dp)
                                            )
                                        }
                                        Text(
                                            SlidePreviewText(next),
                                            fontSize = 11.sp, color = PIX_FG_DIM, maxLines = 6, overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                } else {
                                    Text("—", color = PIX_FG_DIM, modifier = Modifier.align(Alignment.Center))
                                }
                            }
                            SideLabel(com.spice.nativeapp.core.I18n.t("演讲备注"))
                            Box(
                                Modifier.weight(1f).fillMaxWidth()
                                    .border(2.dp, PIX_BORDER_HI).background(PIX_BG3).padding(10.dp)
                            ) {
                                Column(Modifier.verticalScroll(rememberScrollState())) {
                                    if (cur.notes.isEmpty()) {
                                        Text("—", color = PIX_FG_DIM)
                                    } else {
                                        cur.notes.forEach { n ->
                                            Text(
                                                SlideSpans(n, TextStyle(fontSize = 13.sp, color = PIX_FG)),
                                                modifier = Modifier.padding(bottom = 10.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // ps-progress：底部 3px 进度条（track bg-3 / fill cyan）
                Box(
                    Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(3.dp)
                        .background(PIX_BG3)
                ) {
                    Box(
                        Modifier.fillMaxWidth(((idx + 1f) / slides.size)).fillMaxHeight()
                            .background(PIX_CYAN)
                    )
                }

                // ps-controls：右上控制条（触屏适配：按钮替代键盘 P/F/Esc；F 省略——Dialog 已全屏）
                Row(
                    Modifier.align(Alignment.TopEnd).padding(top = 10.dp, end = 12.dp)
                        .background(PIX_OVERLAY_BG.copy(alpha = 0.72f)).border(2.dp, PIX_BORDER)
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    IconToggleButton(Icons.Filled.ViewAgenda, com.spice.nativeapp.core.I18n.t("演讲备注 · P"), if (presenter) PIX_CYAN else PIX_FG_DIM) { presenter = !presenter }
                    IconToggleButton(Icons.Filled.ChevronLeft, "‹", PIX_FG_DIM) { go(-1) }
                    IconToggleButton(Icons.Filled.ChevronRight, "›", PIX_FG_DIM) { go(1) }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.clickable(onClick = onClose).padding(horizontal = 6.dp)
                    ) {
                        Icon(Icons.Filled.Close, null, tint = PIX_RED, modifier = Modifier.size(14.dp))
                        Text(com.spice.nativeapp.core.I18n.t("退出"), fontSize = 11.sp, color = PIX_RED, fontWeight = FontWeight.Bold)
                    }
                    Text(com.spice.nativeapp.core.I18n.t("点按翻页 · P"), fontSize = 9.sp, color = PIX_FG_DIM, letterSpacing = 1.sp)
                }
            }
        }
    }
}

@Composable
private fun SideLabel(text: String) {
    Column {
        Text(text, fontSize = 10.sp, letterSpacing = 1.sp, color = PIX_FG_DIM)
        Spacer(Modifier.fillMaxWidth().height(2.dp))
    }
}

@Composable
private fun IconToggleButton(icon: androidx.compose.ui.graphics.vector.ImageVector, desc: String, tint: Color, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(28.dp)) {
        Icon(icon, desc, tint = tint, modifier = Modifier.size(14.dp))
    }
}

/** 下一页预览纯文本（title 之外正文的扁平文本投影） */
private fun SlidePreviewText(s: Slide): String =
    s.body.joinToString(" ") { b ->
        when (b) {
            is SpBlock.P -> b.spans.joinToString("") { it.text }
            is SpBlock.H3 -> b.spans.joinToString("") { it.text }
            is SpBlock.Code -> b.text
            is SpBlock.Lst -> b.items.joinToString(" ") { it.spans.joinToString("") { it.text } }
            is SpBlock.Tbl -> b.head.joinToString(" ") { it.joinToString("") { t -> t.text } }
            is SpBlock.FnBox -> ""
        }
    }

// ── 幻灯片正文渲染（main {@html bodyHtml} 的 Compose 原生对应；零 WebView）──

@Composable
private fun SlideBody(s: Slide) {
    Column(horizontalAlignment = if (s.level == 2) Alignment.Start else Alignment.CenterHorizontally) {
        s.body.forEach { b ->
            when (b) {
                is SpBlock.P -> SlideParagraph(b)
                is SpBlock.H3 -> Text(
                    SlideSpans(b.spans, TextStyle(fontSize = 24.sp, color = PIX_CYAN)),
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                is SpBlock.Code -> SlideCodeBlock(b.text)
                is SpBlock.Lst -> SlideList(b)
                is SpBlock.Tbl -> SlideTable(b)
                is SpBlock.FnBox -> SlideFnBox(b)
            }
        }
    }
}

@Composable
private fun SlideParagraph(b: SpBlock.P) {
    // 单独成段的图片（main 行内 <img>；Compose 以图片块呈现，本地路径 BitmapFactory 解码）
    val singleImg = if (b.spans.size == 1 && b.spans[0].kind == SpKind.IMG) b.spans[0] else null
    if (singleImg != null) {
        SlideImage(singleImg.href ?: "", singleImg.text)
    } else {
        Text(
            SlideSpans(b.spans, TextStyle(fontSize = 20.sp, color = PIX_FG)),
            modifier = Modifier.padding(bottom = 14.dp),   // 0.8em
            textAlign = TextAlign.Start
        )
    }
}

@Composable
private fun SlideImage(src: String, alt: String) {
    val bmp = remember(src) {
        if (!src.startsWith("http")) runCatching { android.graphics.BitmapFactory.decodeFile(src) }.getOrNull() else null
    }
    if (bmp != null) {
        androidx.compose.foundation.Image(
            bitmap = bmp.asImageBitmap(),
            contentDescription = alt,
            modifier = Modifier
                .width(280.dp)
                .padding(bottom = 14.dp)
        )
    } else {
        // 占位（披露：远程 URL / 不可解码路径不加载——Android 无 HTML <img> 网络加载层）
        Column(
            Modifier.fillMaxWidth().padding(bottom = 14.dp).border(1.dp, PIX_BORDER, RoundedCornerShape(4.dp)).padding(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(alt.ifBlank { com.spice.nativeapp.core.I18n.t("图片") }, fontSize = 12.sp, color = PIX_CYAN)
            Text(src, fontSize = 10.sp, color = PIX_FG_DIM, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun SlideCodeBlock(text: String) {
    Column(
        Modifier.fillMaxWidth().padding(bottom = 14.dp)
            .horizontalScroll(rememberScrollState())
            .border(2.dp, PIX_BORDER)
            .background(PIX_BG2)
            .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        Text(text, fontFamily = FontFamily.Monospace, fontSize = 16.sp, color = PIX_FG)
    }
}

@Composable
private fun SlideList(b: SpBlock.Lst) {
    // main CSS：list-style none，li::before '▸ '（accent 色）；任务项 ☑/☐（cyan 粗体）
    Column(Modifier.padding(bottom = 14.dp)) {
        b.items.forEach { li ->
            Row {
                if (li.taskChecked == null) Text("▸ ", color = PIX_ACCENT, fontSize = 20.sp)
                Text(
                    if (li.taskChecked == null) SlideSpans(li.spans, TextStyle(fontSize = 20.sp, color = PIX_FG))
                    else buildAnnotatedString {
                        pushStyle(SpanStyle(color = PIX_CYAN, fontWeight = FontWeight.Black))
                        append(if (li.taskChecked == true) "☑ " else "☐ ")
                        pop()
                        append(SlideSpans(li.spans, TextStyle(fontSize = 20.sp, color = PIX_FG)))
                    },
                    modifier = Modifier.padding(bottom = 2.dp)
                )
            }
            li.sub?.let { sub -> SlideList(sub) }
        }
    }
}

@Composable
private fun SlideTable(b: SpBlock.Tbl) {
    Column(Modifier.padding(bottom = 14.dp)) {
        // thead：th cyan + border-bottom 2px（border-hi）
        Row(Modifier.fillMaxWidth()) {
            b.head.forEachIndexed { ci, cell ->
                Text(
                    SlideSpans(cell, TextStyle(fontSize = 18.sp, color = PIX_CYAN)),
                    textAlign = when (b.aligns.getOrElse(ci) { "left" }) { "center" -> TextAlign.Center; "right" -> TextAlign.End; else -> TextAlign.Start },
                    modifier = Modifier.weight(1f).padding(horizontal = 12.dp, vertical = 4.dp)
                )
            }
        }
        Row(Modifier.fillMaxWidth().height(2.dp).background(PIX_BORDER_HI)) {}
        // tbody：td 底 1px 虚线（border）
        b.rows.forEach { row ->
            Row(Modifier.fillMaxWidth().drawBottomDashed()) {
                row.forEachIndexed { ci, cell ->
                    Text(
                        SlideSpans(cell, TextStyle(fontSize = 18.sp, color = PIX_FG)),
                        textAlign = when (b.aligns.getOrElse(ci) { "left" }) { "center" -> TextAlign.Center; "right" -> TextAlign.End; else -> TextAlign.Start },
                        modifier = Modifier.weight(1f).padding(horizontal = 12.dp, vertical = 4.dp)
                    )
                }
            }
        }
    }
}

private fun Modifier.drawBottomDashed(): Modifier = drawBehind {
    drawLine(
        color = PIX_BORDER,
        start = androidx.compose.ui.geometry.Offset(0f, size.height),
        end = androidx.compose.ui.geometry.Offset(size.width, size.height),
        strokeWidth = 1f,
        pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f))
    )
}

@Composable
private fun SlideFnBox(b: SpBlock.FnBox) {
    Column(
        Modifier.fillMaxWidth().padding(top = 16.dp).drawTopDashed().padding(top = 8.dp)
    ) {
        b.notes.forEach { (n, spans) ->
            Text(
                buildAnnotatedString {
                    pushStyle(SpanStyle(color = PIX_ACCENT2, fontWeight = FontWeight.Black, fontSize = 12.sp))
                    append("$n ")
                    pop()
                    append(SlideSpans(spans, TextStyle(fontSize = 12.sp, color = PIX_FG_DIM)))
                },
                fontSize = 12.sp, lineHeight = 19.sp
            )
        }
    }
}

private fun Modifier.drawTopDashed(): Modifier = drawBehind {
    drawLine(
        color = PIX_BORDER_HI,
        start = androidx.compose.ui.geometry.Offset(0f, 0f),
        end = androidx.compose.ui.geometry.Offset(size.width, 0f),
        strokeWidth = 1f,
        pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f))
    )
}

/** 行内 span → AnnotatedString（BOLD/ITALIC/STRIKE/MARK/CODE/SUP/SUB/LINK/FN 与 main 标签一一对应） */
@Composable
private fun SlideSpans(spans: List<SpTxt>, base: TextStyle): AnnotatedString = buildAnnotatedString {
    spans.forEach { s ->
        when (s.kind) {
            SpKind.PLAIN -> append(s.text)
            SpKind.BOLD -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(s.text) }
            SpKind.ITALIC -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(s.text) }
            SpKind.STRIKE -> withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) { append(s.text) }
            SpKind.HIGHLIGHT -> withStyle(
                SpanStyle(background = PIX_ACCENT2.copy(alpha = 0.35f), color = base.color ?: PIX_FG)
            ) { append(s.text) }
            SpKind.CODE -> withStyle(
                SpanStyle(fontFamily = FontFamily.Monospace, background = PIX_BG3)
            ) { append(s.text) }
            SpKind.SUP -> withStyle(
                SpanStyle(fontSize = 13.sp, baselineShift = BaselineShift.Superscript)
            ) { append(s.text) }
            SpKind.SUB -> withStyle(
                SpanStyle(fontSize = 13.sp, baselineShift = BaselineShift.Subscript)
            ) { append(s.text) }
            SpKind.LINK -> withStyle(SpanStyle(color = PIX_CYAN, textDecoration = TextDecoration.Underline)) { append(s.text) }
            SpKind.FN_MARK -> withStyle(
                SpanStyle(color = PIX_ACCENT2, fontWeight = FontWeight.Black, baselineShift = BaselineShift.Superscript)
            ) { append(s.text) }
            SpKind.IMG -> append(s.text.ifBlank { "🖼" })   // 行内混排图片降级为 alt 文本（披露）
            SpKind.FN_REF -> append(s.text)                  // finalize 后不应残留
        }
    }
}
