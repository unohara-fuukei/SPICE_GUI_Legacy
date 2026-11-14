package com.spice.nativeapp.core

// ───────────────────────── Presenter 幻灯片解析器 ─────────────────────────
// 1:1 移植 main src/lib/project/slides.ts（iA-Presenter 风格的 journal markdown 适配）：
//   - `---` 水平线 与 `#`/`##` 一二级标题 → 分页；
//   - 段落 / 列表（嵌套 + 任务）/ 表格（:-- --: :-: 对齐）/ 围栏代码 / 图片 → 上屏内容；
//   - `> ...` 引用块 → 演讲备注（观众不可见）；
//   - 行内：**粗** *斜* ~~删~~ ==高亮== `代码` [链接](url) 上标 ^2^ /^(a+b)^ 下标 ~2~ 引用式链接 [t][ref]；
//   - [^id] 脚注引用 + `[^id]: note` 定义（按页编号、页底分组）；行内形式 `[^自由文本]` 同样支持；
//   - 行首 `//` 为 presenter 专用注释（永不渲染）。
// 差异：main 产出 HTML 字符串（Svelte {@html} 注入）；本端口产出结构化块树，由 Compose 原生渲染
//（零 WebView 约束），语义（分页/分层/编号/防碰撞顺序）与 slides.ts 完全一致。

/** 行内 span 样式（对应 slides.ts renderInline 产出的各 HTML 标签） */
enum class SpKind { PLAIN, BOLD, ITALIC, STRIKE, HIGHLIGHT, CODE, SUP, SUB, LINK, IMG, FN_REF, FN_MARK }

data class SpTxt(
    val text: String,
    val kind: SpKind = SpKind.PLAIN,
    val href: String? = null,   // LINK 目标 / IMG src
    val fnId: String? = null    // FN_REF：短 id（有定义）或 null（行内自由文本）
)

/** 幻灯片块（对应 slides.ts 产出的 body/notes HTML 块） */
sealed class SpBlock {
    data class P(val spans: List<SpTxt>) : SpBlock()
    data class H3(val spans: List<SpTxt>) : SpBlock()
    data class Code(val text: String) : SpBlock()
    data class Lst(val ordered: Boolean, val items: List<Li>) : SpBlock()
    data class Tbl(
        val aligns: List<String>,
        val head: List<List<SpTxt>>,
        val rows: List<List<List<SpTxt>>>
    ) : SpBlock()
    /** 脚注分组块（对应 sp-fnbox）：n → 备注内容 */
    data class FnBox(val notes: List<Pair<Int, List<SpTxt>>>) : SpBlock()
}

data class Li(
    val spans: List<SpTxt>,
    val sub: SpBlock.Lst? = null,
    val taskChecked: Boolean? = null  // null = 非任务项
)

data class Slide(
    val level: Int,                    // 0 / 1 / 2
    val title: String?,                // 标题为纯文本（main h1 同样不做行内渲染）
    val body: List<SpBlock>,
    val notes: List<List<SpTxt>>       // 每元素一段（sp-np）
)

object Slides {

    // ---- 与 slides.ts 相同的正则集（逐条对齐）----
    private val HR_RE = Regex("^\\s*(---+|\\*\\*\\*+|___+)\\s*$")
    private val H_RE = Regex("^(#{1,6})\\s+(.*)$")
    private val QUOTE_RE = Regex("^\\s*>\\s?(.*)$")
    private val UL_RE = Regex("^(\\s*)[-*+]\\s+(.*)$")
    private val OL_RE = Regex("^(\\s*)\\d+[.)]\\s+(.*)$")
    private val FENCE_RE = Regex("^\\s*```")
    private val TABLE_SEP_RE = Regex("^\\s*\\|?\\s*:?-{2,}.*$")
    private val FN_DEF_RE = Regex("^\\s*\\[\\^([^\\]]+)\\]:\\s*(.*)$")
    private val REF_DEF_RE = Regex("^\\s*\\[([^\\]^]+)\\]:\\s*(\\S+)(\\s+[\"'(].*)?$")
    private val COMMENT_RE = Regex("^\\s*//")

    private val CODE_SPAN_RE = Regex("`([^`]+)`")
    private val IMG_RE = Regex("!\\[([^\\]]*)\\]\\(([^)\\s]+)\\)")
    private val REFLINK_RE = Regex("\\[([^\\]]+)\\]\\[([^\\]]*)\\]")
    private val LINK_RE = Regex("\\[([^\\]]+)\\]\\(([^)\\s]+)\\)")
    private val BOLD_RE = Regex("\\*\\*([^*]+)\\*\\*")
    private val EM_RE = Regex("(^|[^*])\\*([^*\\n]+)\\*")
    private val DEL_RE = Regex("~~([^~]+)~~")
    private val MARK_RE = Regex("==([^=]+)==")
    // 上标：^(a+b)^ 或 ^2^（简单形式无空格）—— slides.ts 原式 verbatim
    private val SUP_RE = Regex("\\^(\\((?:[^()]|\\([^()]*\\))*\\)|[^\\s^()]+)\\^")
    private val SUB_RE = Regex("(^|[^~])~([^~\\s]+)~")
    private val FN_REF_RE = Regex("\\[\\^([^\\]]+)\\]")
    private val FN_ID_RE = Regex("^[A-Za-z0-9_.\\-]{1,24}$")
    private val TASK_RE = Regex("^\\[( |x|X)\\]\\s*(.*)$")

    // ---- renderInline ----

    /** 单个 pass：对所有可处理 span 的文本施正则，未命中段保留原 kind（与 TS 扁平字符串替换同构，
     *  后续 pass 仍可进入已标记 span 的文本 → 与 main 的嵌套行为一致） */
    private fun pass(
        spans: List<SpTxt>, re: Regex,
        wrap: (MatchResult) -> List<SpTxt>,
        skip: Set<SpKind>
    ): List<SpTxt> = spans.flatMap { s ->
        if (s.kind in skip || !re.containsMatchIn(s.text)) listOf(s)
        else {
            val out = mutableListOf<SpTxt>()
            var prev = 0
            for (m in re.findAll(s.text)) {
                if (m.range.first > prev) out.add(s.copy(text = s.text.substring(prev, m.range.first)))
                out.addAll(wrap(m))
                prev = m.range.last + 1
            }
            if (prev < s.text.length) out.add(s.copy(text = s.text.substring(prev)))
            out
        }
    }

    private class Ctx {
        val fnRefs = mutableListOf<SpTxt>()           // FN_REF 占位 span（顺序即 @@FN i@@ 下标）
        val refLinks = mutableMapOf<String, String>()
        val fnDefs = mutableMapOf<String, String>()
    }

    private fun renderInline(text: String, ctx: Ctx): List<SpTxt> {
        var spans = listOf(SpTxt(text))
        val protected_ = setOf(SpKind.CODE, SpKind.IMG)  // 与 @C@ 占位/属性文本同理：后续 pass 不得进入
        // code spans first（slides.ts：先占位，防强调类正则吃掉内容）
        spans = pass(spans, CODE_SPAN_RE, { m -> listOf(SpTxt(m.groupValues[1], SpKind.CODE)) }, emptySet())
        // images before links（同语法 + 前导 !）
        spans = pass(spans, IMG_RE, { m -> listOf(SpTxt(m.groupValues[1], SpKind.IMG, href = m.groupValues[2])) }, protected_)
        // reference links [text][ref]（def 块级收集）
        spans = pass(spans, REFLINK_RE, { m ->
            val label = m.groupValues[1]
            val key = (if (m.groupValues[2].isEmpty()) label else m.groupValues[2]).lowercase()
            val href = ctx.refLinks[key]
            if (href == null) listOf(SpTxt(m.value)) else listOf(SpTxt(label, SpKind.LINK, href = href))
        }, protected_)
        // inline links [label](href)
        spans = pass(spans, LINK_RE, { m ->
            listOf(SpTxt(m.groupValues[1], SpKind.LINK, href = m.groupValues[2]))
        }, protected_)
        spans = pass(spans, BOLD_RE, { m -> listOf(SpTxt(m.groupValues[1], SpKind.BOLD)) }, protected_)
        spans = pass(spans, EM_RE, { m ->
            listOf(SpTxt(m.groupValues[1], SpKind.PLAIN), SpTxt(m.groupValues[2], SpKind.ITALIC))
        }, protected_)
        spans = pass(spans, DEL_RE, { m -> listOf(SpTxt(m.groupValues[1], SpKind.STRIKE)) }, protected_)
        spans = pass(spans, MARK_RE, { m -> listOf(SpTxt(m.groupValues[1], SpKind.HIGHLIGHT)) }, protected_)
        spans = pass(spans, SUP_RE, { m ->
            val c = m.groupValues[1]
            listOf(SpTxt(if (c.startsWith("(")) c.substring(1, c.length - 1) else c, SpKind.SUP))
        }, protected_)
        spans = pass(spans, SUB_RE, { m ->
            listOf(SpTxt(m.groupValues[1], SpKind.PLAIN), SpTxt(m.groupValues[2], SpKind.SUB))
        }, protected_)
        // footnotes：[^id]（他处定义）或 [^行内文本]
        spans = pass(spans, FN_REF_RE, { m ->
            val inner = m.groupValues[1]
            val asDef = inner.trim()
            val span = SpTxt(asDef, SpKind.FN_REF, fnId = if (FN_ID_RE.matches(asDef)) asDef.lowercase() else null)
            ctx.fnRefs.add(span)
            listOf(span)
        }, protected_)
        return spans
    }

    // ---- 块级解析 ----

    private class LiItem(val indent: Int, val ordered: Boolean, val content: String)

    private class Draft {
        var level = 0
        var title: String? = null
        val body = mutableListOf<SpBlock>()
        val notes = mutableListOf<List<SpTxt>>()
        val ctx = Ctx()
    }

    private fun listIndent(line: String): Int =
        Regex("^[\\t ]*").find(line)!!.value.replace("\t", "    ").length

    /** 递归渲染一段连续列表行（缩进嵌套）—— 对应 slides.ts renderListRun */
    private fun renderListRun(items: List<LiItem>, ctx: Ctx): SpBlock.Lst {
        var pos = 0
        fun parseLevel(indent: Int): SpBlock.Lst {
            val ordered = items[pos].ordered
            val lis = mutableListOf<Li>()
            while (pos < items.size && items[pos].indent >= indent) {
                if (items[pos].indent > indent) {
                    val sub = parseLevel(items[pos].indent)
                    val last = lis.removeAt(lis.size - 1)
                    lis.add(last.copy(sub = sub))
                } else {
                    val t = TASK_RE.find(items[pos].content)
                    val checked = t?.groupValues?.get(1)?.lowercase() == "x"
                    val inner = if (t != null) renderInline(t.groupValues[2], ctx) else renderInline(items[pos].content, ctx)
                    lis.add(Li(inner, null, if (t != null) checked else null))
                    pos++
                }
            }
            return SpBlock.Lst(ordered, lis)
        }
        return parseLevel(items[0].indent)
    }

    /** 脚注定稿：按首次使用顺序编号，跨块替换 FN_REF → FN_MARK，页底追加 FnBox（finalizeFootnotes verbatim 语义） */
    private fun finalizeFootnotes(d: Draft) {
        val numbered = mutableMapOf<String, Int>()   // id → n（重复引用复用 n）
        val entries = mutableListOf<Pair<Int, List<SpTxt>>>()
        var counter = 0
        fun refNumber(span: SpTxt): SpTxt {
            val n: Int
            val id = span.fnId
            if (id != null) {
                val existing = numbered[id]
                if (existing != null) {
                    n = existing
                } else {
                    counter += 1; n = counter
                    numbered[id] = n
                    entries.add(n to renderInline(d.ctx.fnDefs[id] ?: "", d.ctx))
                }
            } else {
                counter += 1; n = counter
                entries.add(n to renderInline(span.text, d.ctx))
            }
            return span.copy(text = "$n", kind = SpKind.FN_MARK)
        }
        val map = { s: SpTxt -> if (s.kind == SpKind.FN_REF) refNumber(s) else s }
        val newBody = d.body.map { b ->
            when (b) {
                is SpBlock.P -> b.copy(spans = b.spans.map(map))
                is SpBlock.H3 -> b.copy(spans = b.spans.map(map))
                is SpBlock.Lst -> mapLi(b, map)
                is SpBlock.Tbl -> b.copy(head = b.head.map { it.map(map) }, rows = b.rows.map { r -> r.map { it.map(map) } })
                else -> b
            }
        }
        d.body.clear()
        d.body.addAll(newBody)
        if (entries.isNotEmpty()) {
            d.body.add(SpBlock.FnBox(entries.sortedBy { it.first }))
        }
    }

    private fun mapLi(l: SpBlock.Lst, map: (SpTxt) -> SpTxt): SpBlock.Lst =
        l.copy(items = l.items.map { it.copy(spans = it.spans.map(map), sub = it.sub?.let { s -> mapLi(s, map) }) })

    fun parseSlides(md: String): List<Slide> {
        val lines = (md ?: "").replace(Regex("\r\n?"), "\n").split('\n')
        val drafts = mutableListOf<Draft>()

        // 文档级预扫描：脚注定义与引用式链接可出现在使用之后（fence 内不收集）
        val docRefLinks = mutableMapOf<String, String>()
        val docFnDefs = mutableMapOf<String, String>()
        run {
            var fence = false
            for (line in lines) {
                if (FENCE_RE.containsMatchIn(line)) { fence = !fence; continue }
                if (fence) continue
                val fnd = FN_DEF_RE.find(line)
                if (fnd != null) {
                    docFnDefs[fnd.groupValues[1].trim().lowercase()] = fnd.groupValues[2].trim()
                    continue
                }
                val rfd = REF_DEF_RE.find(line)
                if (rfd != null) docRefLinks[rfd.groupValues[1].trim().lowercase()] = rfd.groupValues[2].trim()
            }
        }

        fun newDraft(): Draft {
            val d = Draft()
            d.ctx.refLinks.putAll(docRefLinks)
            d.ctx.fnDefs.putAll(docFnDefs)
            return d
        }
        var cur = newDraft()
        var para = mutableListOf<String>()
        var notePara = mutableListOf<String>()
        var inFence = false
        var fenceBuf = mutableListOf<String>()

        fun flushParagraph() {
            if (para.isEmpty()) return
            cur.body.add(SpBlock.P(renderInline(para.joinToString(" "), cur.ctx)))
            para = mutableListOf()
        }
        fun flushNote() {
            if (notePara.isEmpty()) return
            cur.notes.add(renderInline(notePara.joinToString(" "), cur.ctx))
            notePara = mutableListOf()
        }
        fun closeSlide() {
            flushParagraph(); flushNote()
            finalizeFootnotes(cur)
            if (cur.title != null || cur.body.isNotEmpty() || cur.notes.isNotEmpty()) drafts.add(cur)
            cur = newDraft()
        }

        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            if (inFence) {
                if (FENCE_RE.containsMatchIn(line)) {
                    cur.body.add(SpBlock.Code(fenceBuf.joinToString("\n")))
                    fenceBuf = mutableListOf()
                    inFence = false
                } else {
                    fenceBuf.add(line)
                }
                i++; continue
            }
            if (COMMENT_RE.containsMatchIn(line)) { i++; continue }        // presenter 专用注释
            val fnd = FN_DEF_RE.find(line)
            if (fnd != null) {
                cur.ctx.fnDefs[fnd.groupValues[1].trim().lowercase()] = fnd.groupValues[2].trim()
                i++; continue
            }
            val rfd = REF_DEF_RE.find(line)
            if (rfd != null) {
                cur.ctx.refLinks[rfd.groupValues[1].trim().lowercase()] = rfd.groupValues[2].trim()
                i++; continue
            }
            if (FENCE_RE.containsMatchIn(line)) {
                flushParagraph(); inFence = true; i++; continue
            }
            if (HR_RE.containsMatchIn(line)) { closeSlide(); i++; continue }
            val h = H_RE.find(line)
            if (h != null) {
                val depth = h.groupValues[1].length
                if (depth == 1 || depth == 2) {
                    closeSlide()
                    cur.level = depth
                    cur.title = h.groupValues[2].trim()
                } else {
                    flushParagraph(); flushNote()
                    cur.body.add(SpBlock.H3(renderInline(h.groupValues[2].trim(), cur.ctx)))
                }
                i++; continue
            }
            val q = QUOTE_RE.find(line)
            if (q != null) {
                flushParagraph()
                if (q.groupValues[1].isBlank()) flushNote()
                else notePara.add(q.groupValues[1].trim())
                i++; continue
            }
            val ul = UL_RE.find(line)
            val ol = if (ul != null) null else OL_RE.find(line)
            if (ul != null || ol != null) {
                flushParagraph()
                val items = mutableListOf<LiItem>()
                var j = i
                while (j < lines.size) {
                    val mu = UL_RE.find(lines[j])
                    val mo = if (mu != null) null else OL_RE.find(lines[j])
                    if (mu == null && mo == null) break
                    val indent = listIndent((mu ?: mo!!).value)
                    items.add(LiItem(indent, mo != null, (mu ?: mo!!).groupValues[2].trim()))
                    j++
                }
                cur.body.add(renderListRun(items, cur.ctx))
                i = j; continue
            }
            // 表格：当前行含 | 且下一行是分隔行
            if (line.contains('|') && i + 1 < lines.size && TABLE_SEP_RE.containsMatchIn(lines[i + 1]) && lines[i + 1].contains('-')) {
                flushParagraph()
                val sepCells = lines[i + 1].replace(Regex("^\\s*\\|"), "").replace(Regex("\\|\\s*$"), "").split('|')
                val aligns = sepCells.map { c ->
                    val s = c.trim()
                    when {
                        s.startsWith(":") && s.endsWith(":") -> "center"
                        s.endsWith(":") -> "right"
                        else -> "left"
                    }
                }
                fun parseRow(row: String): List<List<SpTxt>> =
                    row.replace(Regex("^\\s*\\|"), "").replace(Regex("\\|\\s*$"), "").split('|')
                        .map { renderInline(it.trim(), cur.ctx) }
                val head = parseRow(line)
                val rows = mutableListOf<List<List<SpTxt>>>()
                var k = i + 2
                while (k < lines.size && lines[k].contains('|')) {
                    rows.add(parseRow(lines[k]))
                    k++
                }
                cur.body.add(SpBlock.Tbl(aligns, head, rows))
                i = k; continue
            }
            if (line.isBlank()) { flushParagraph(); flushNote(); i++; continue }
            para.add(line.trim())
            i++
        }
        if (inFence && fenceBuf.isNotEmpty()) {
            cur.body.add(SpBlock.Code(fenceBuf.joinToString("\n")))
        }
        closeSlide()

        return drafts
            .filter { it.title != null || it.body.isNotEmpty() || it.notes.isNotEmpty() }
            .map { Slide(it.level, it.title, it.body.toList(), it.notes.toList()) }
    }

    fun hasSlides(md: String): Boolean =
        parseSlides(md).any { it.title != null || it.body.isNotEmpty() }
}
