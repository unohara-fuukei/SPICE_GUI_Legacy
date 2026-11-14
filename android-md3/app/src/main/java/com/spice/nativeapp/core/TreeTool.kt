package com.spice.nativeapp.core

// Newick 系统树工具核心算法层，1:1 移植自 main 分支 src/lib/tools/tree/ 下的：
//   types.ts / newick.ts / treeops.ts / layout.ts / metadata.ts / support.ts /
//   compare.ts / export.ts(JSON 部分) / example.ts / render.ts / projectDoc.ts
// 以及 src/lib/repro/bundle.ts 的可复现包格式。文件内每个区块注释标明对应 TS 源。

import android.graphics.Canvas
import android.graphics.Color as AColor
import android.graphics.Paint
import android.graphics.Typeface
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal
import java.math.MathContext
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

// ============================================================ types.ts ===

enum class LayoutMode { PHYLOGRAM, CLADOGRAM, CIRCULAR;
    companion object {
        fun fromKey(s: String?): LayoutMode? = when (s) {
            "phylogram" -> PHYLOGRAM
            "cladogram" -> CLADOGRAM
            "circular" -> CIRCULAR
            else -> null
        }
        val KEY = mapOf(PHYLOGRAM to "phylogram", CLADOGRAM to "cladogram", CIRCULAR to "circular")
    }
    val key: String get() = Companion.KEY[this]!!
}

enum class TreeTheme { DARK, LIGHT;
    companion object {
        fun fromKey(s: String?): TreeTheme? = when (s) {
            "dark" -> DARK
            "light" -> LIGHT
            else -> null
        }
    }
    val key: String get() = if (this == DARK) "dark" else "light"
}

enum class PaletteId { DEFAULT, WARM, COOL, COLORBLIND;
    companion object {
        fun fromKey(s: String?): PaletteId? = when (s) {
            "default" -> DEFAULT
            "warm" -> WARM
            "cool" -> COOL
            "colorblind" -> COLORBLIND
            else -> null
        }
    }
    val key: String get() = when (this) {
        DEFAULT -> "default"; WARM -> "warm"; COOL -> "cool"; COLORBLIND -> "colorblind"
    }
}

/** 对应 main TreeNode：纯数据、无父指针，可克隆/序列化/重定根。 */
data class TreeNode(
    val id: String,
    val name: String,
    val length: Double?,     // null = 文件未提供枝长
    val support: Double?,    // 内部支持度（bootstrap/posterior）
    val comment: String?,    // Newick 注释原文（不含方括号）
    val children: MutableList<TreeNode>
) {
    val isLeaf: Boolean get() = children.isEmpty()
}

data class TreeDoc(
    val root: TreeNode,
    val originalNewick: String,   // 解析时原文（“原始”导出使用）
    val rooted: Boolean,
    val sourceName: String?
)

data class TreeSettings(
    val mode: LayoutMode = LayoutMode.PHYLOGRAM,
    val fontSize: Double = 11.0,
    val branchWidth: Double = 1.5,
    val labelAngle: Double = 0.0,
    val supportThreshold: Double = 0.0,
    val showBranchLengths: Boolean = false,
    val showSupport: Boolean = true,
    val showNodeDots: Boolean = true,
    val showScaleBar: Boolean = true,
    val theme: TreeTheme = TreeTheme.DARK
)

fun defaultSettings(): TreeSettings = TreeSettings()

data class MetaEntry(val leaf: String, val group: String, val color: String?)

data class TreeMetadata(val entries: List<MetaEntry>, val paletteId: PaletteId)

fun emptyMetadata(): TreeMetadata = TreeMetadata(emptyList(), PaletteId.DEFAULT)

data class LeafMeta(val group: String, val color: String)

data class ResolvedMetadata(
    val byLeaf: Map<String, LeafMeta>,
    val groupOrder: List<String>,
    val groupColors: Map<String, String>,
    val groupCounts: Map<String, Int>,
    val ungrouped: Int
)

/** 对应 main LayoutNode（布局节点，含交互命中所需全部几何量）。 */
class LayoutNode(
    val id: String,
    val name: String,
    val length: Double?,
    val support: Double?,
    val comment: String?,
    val depth: Double,
    val level: Int,
    var parentId: String?,
    var x: Double,
    var y: Double,
    var radius: Double,
    var angle: Double,
    var parentRadius: Double,
    var parentAngle: Double,
    var parentX: Double,
    var parentY: Double,
    val isLeaf: Boolean,
    val isCollapsed: Boolean,
    val leafCount: Int,
    var terminalIndex: Int?,
    val group: String?,
    val color: String?,
    val children: MutableList<LayoutNode>,
    var startAngle: Double,
    var endAngle: Double
)

data class ScaleBar(val x: Double, val y: Double, val lengthPx: Double, val unit: Double)

class TreeLayout(
    val mode: LayoutMode,
    val width: Double,
    val height: Double,
    val centerX: Double,
    val centerY: Double,
    val plotLeft: Double,
    val plotTop: Double,
    val plotWidth: Double,
    val plotHeight: Double,
    val root: LayoutNode,
    val nodes: List<LayoutNode>,
    val byId: Map<String, LayoutNode>,
    val terminals: List<LayoutNode>,
    val leafCount: Int,
    val nodeCount: Int,
    val maxDepth: Double,
    val depthScale: Double,
    val fallbackCladogram: Boolean,
    val titleLines: List<String>,
    val captionLines: List<String>,
    val titleY: Double,
    val captionY: Double,
    val titleFont: Double,
    val captionFont: Double,
    val scaleBar: ScaleBar?
)

data class SerializedNode(
    val id: String,
    val name: String,
    val length: Double?,
    val support: Double?,
    val comment: String?,
    val children: List<SerializedNode>
)

data class TreeProject(
    val format: String,             // 'spice-tree'
    val version: Int,               // 1
    val originalNewick: String,
    val tree: SerializedNode,
    val rooted: Boolean,
    val sourceName: String?,
    val collapsedIds: List<String>,
    val settings: TreeSettings,
    val metadata: TreeMetadata,
    val title: String,
    val caption: String,
    val savedAt: String
)

val PALETTES: Map<PaletteId, List<String>> = mapOf(
    PaletteId.DEFAULT to listOf(
        "#4cd6ff", "#ffd23f", "#53d769", "#ff9f1c", "#b48cff", "#ff5d5d",
        "#2ee6c8", "#f472b6", "#a3e635", "#f59e0b", "#60a5fa", "#e879f9"
    ),
    PaletteId.WARM to listOf(
        "#ff9f1c", "#ff5d5d", "#ffd23f", "#f97316", "#e11d48", "#f43f5e",
        "#fbbf24", "#d97706", "#dc2626", "#fb7185", "#f59e0b", "#b45309"
    ),
    PaletteId.COOL to listOf(
        "#4cd6ff", "#60a5fa", "#2ee6c8", "#818cf8", "#38bdf8", "#a78bfa",
        "#22d3ee", "#3b82f6", "#14b8a6", "#6366f1", "#0ea5e9", "#8b5cf6"
    ),
    PaletteId.COLORBLIND to listOf(
        "#0072b2", "#e69f00", "#009e73", "#cc79a7", "#56b4e9", "#d55e00",
        "#f0e442", "#000000", "#4d4d4d", "#999999", "#117733", "#882255"
    )
)

val PALETTE_IDS = listOf(PaletteId.DEFAULT, PaletteId.WARM, PaletteId.COOL, PaletteId.COLORBLIND)

/** 分数形式的自展值归一到百分空间。 */
fun normalizedSupport(value: Double?): Double? {
    if (value == null || !value.isFinite()) return null
    return if (value in 0.0..1.0) value * 100 else value
}

/** JS Number(x.toPrecision(6)) 后的字符串形态（最短往返表示）。 */
fun formatNumber(value: Double, digits: Int = 6): String {
    if (!value.isFinite()) return ""
    val rounded = BigDecimal(value).round(MathContext(digits))
    return jsNum(rounded.toDouble())
}

/** JS Number → String 语义（最短十进制表示 + 1e-6/1e21 指数阈值）。 */
fun jsNum(d: Double): String {
    if (d.isNaN() || d.isInfinite()) return "null"
    for (p in 1..17) {
        val cand = BigDecimal(d).round(MathContext(p))
        if (cand.toDouble() == d) {
            val st = cand.stripTrailingZeros()
            val a = abs(d)
            return if (a != 0.0 && (a < 1e-6 || a >= 1e21)) st.toString().replace("E", "e")
            else st.toPlainString()
        }
    }
    return d.toString()
}

// ============================================================ newick.ts ===

enum class NewickErrorCode {
    EMPTY, UNEXPECTED, UNCLOSED, BAD_LENGTH, UNEXPECTED_COMMA, UNEXPECTED_END, BAD_QUOTED, BAD_COMMENT, NO_ROOT
}

class NewickParseError(val code: NewickErrorCode, val position: Int, val found: String = "") :
    Exception("$code at $position")

data class ParsedTree(val root: TreeNode, val rooted: Boolean)

private val UNQUOTED_STOP = setOf('(', ')', ',', ':', ';', '[', ']', '\'', '"')
private val NUMBER_RE = Regex("^[+-]?(?:\\d+\\.?\\d*|\\.\\d+)(?:[eE][+-]?\\d+)?")
private val NUMERIC_RE = Regex("[+-]?(?:\\d+\\.?\\d*|\\.\\d+)(?:[eE][+-]?\\d+)?")

private fun isNumericLabel(label: String): Boolean = NUMERIC_RE.matches(label.trim())

private val SUPPORT_FLAGGED_RE = Regex(
    "(?:^|[&;,\\s])(?:support|boot(?:strap)?)\\s*[=:]\\s*([+-]?(?:\\d+\\.?\\d*|\\.\\d+)(?:[eE][+-]?\\d+)?)",
    RegexOption.IGNORE_CASE
)
private val SUPPORT_BARE_RE = Regex(
    "^\\s*&?\\s*([+-]?(?:\\d+\\.?\\d*|\\.\\d+)(?:[eE][+-]?\\d+)?)\\s*$"
)

private fun extractSupport(comment: String): Double? {
    val flagged = SUPPORT_FLAGGED_RE.find(comment)
    if (flagged != null) {
        val value = flagged.groupValues[1].toDoubleOrNull()
        if (value != null && value.isFinite()) return value
    }
    val bare = SUPPORT_BARE_RE.find(comment)
    if (bare != null) {
        val value = bare.groupValues[1].toDoubleOrNull()
        if (value != null && value.isFinite()) return value
    }
    return null
}

private val ROOTED_RE = Regex("(?:^|[&;,\\s])(?:r|rooted)\\s*(?:[=:]\\s*(?:true|yes|1))?\\s*(?:\$|[;,\\s])")
private val UNROOTED_RE = Regex("(?:^|[&;,\\s])(?:u|unrooted)\\s*(?:[=:]\\s*(?:true|yes|1))?\\s*(?:\$|[;,\\s])")
private val UNROOTED_WORD_RE = Regex("\\b(?:u|unrooted)\\b")
private val ROOTED_FALSE_RE = Regex("rooted\\s*[=:]\\s*false")
private val ROOTED_TRUE_RE = Regex("rooted\\s*[=:]\\s*true")

private fun rootFlag(comment: String): Boolean? {
    val lower = comment.lowercase()
    if (ROOTED_RE.containsMatchIn(lower)) {
        if (UNROOTED_WORD_RE.containsMatchIn(lower)) return false
        return true
    }
    if (UNROOTED_RE.containsMatchIn(lower)) return false
    if (ROOTED_FALSE_RE.containsMatchIn(lower)) return false
    if (ROOTED_TRUE_RE.containsMatchIn(lower)) return true
    return null
}

/**
 * 解析器内部使用可变节点（与 TS 字段写完全同构），parse() 出口转为不可变 TreeNode。
 */
private class MNode(val id: String) {
    var name: String = ""
    var length: Double? = null
    var support: Double? = null
    var comment: String? = null
    val children = mutableListOf<MNode>()

    fun toTreeNode(): TreeNode =
        TreeNode(id, name, length, support, comment, children.map { it.toTreeNode() }.toMutableList())
}

private class NewickParser(private val text: String) {
    private var pos = 0
    private var nextId = 1
    private var rootedFlag: Boolean? = null

    fun parse(): ParsedTree {
        skipWhitespace()
        val leadingComments = mutableListOf<String>()
        while (peek() == "[") {
            val comment = readComment()
            val flag = rootFlag(comment)
            if (flag != null) {
                if (rootedFlag == null) rootedFlag = flag
            } else {
                leadingComments.add(comment)
            }
            skipWhitespace()
        }
        if (pos >= text.length) throw NewickParseError(NewickErrorCode.EMPTY, pos, "")
        if (peek() == ";") throw NewickParseError(NewickErrorCode.NO_ROOT, pos, ";")

        val root = parseSubtree()
        attachLeadingComments(root, leadingComments)
        skipWhitespace()
        if (peek() == ";") {
            pos++
            skipWhitespace()
        }
        if (pos < text.length) throw NewickParseError(NewickErrorCode.UNEXPECTED, pos, peek())

        val rooted = rootedFlag ?: (root.children.isEmpty() || root.children.size <= 2)
        return ParsedTree(root.toTreeNode(), rooted)
    }

    private fun makeNode(): MNode = MNode("n${nextId++}")

    private fun parseSubtree(): MNode {
        skipWhitespace()
        val leadingComments = mutableListOf<String>()
        while (peek() == "[") {
            leadingComments.add(readComment())
            skipWhitespace()
        }
        val first = peek()

        if (first == "(") {
            pos++
            val node = makeNode()
            skipWhitespace()
            if (peek() == ")") throw NewickParseError(NewickErrorCode.UNEXPECTED, pos, ")")
            while (true) {
                node.children.add(parseSubtree())
                skipWhitespace()
                val ch = peek()
                if (ch == ",") {
                    pos++
                    continue
                }
                if (ch == ")") {
                    pos++
                    break
                }
                if (ch == "") throw NewickParseError(NewickErrorCode.UNCLOSED, pos, "")
                if (ch == ";") throw NewickParseError(NewickErrorCode.UNCLOSED, pos, ";")
                throw NewickParseError(NewickErrorCode.UNEXPECTED, pos, ch)
            }
            parseSuffix(node, internal = true)
            attachLeadingComments(node, leadingComments)
            return node
        }

        if (first == "") throw NewickParseError(NewickErrorCode.UNEXPECTED_END, pos, "")
        if (first == ")" || first == ";") throw NewickParseError(NewickErrorCode.UNEXPECTED, pos, first)

        val node = makeNode()
        if (first != ":" && first != "[" && first != ",") {
            node.name = readLabel()
        }
        parseSuffix(node, internal = false)
        attachLeadingComments(node, leadingComments)
        return node
    }

    private fun attachLeadingComments(node: MNode, comments: List<String>) {
        if (comments.isEmpty()) return
        for (comment in comments) {
            val support = extractSupport(comment)
            if (support != null && node.support == null) node.support = support
        }
        val combined = comments.joinToString(" ")
        node.comment = if (node.comment != null) "$combined ${node.comment}" else combined
    }

    private fun parseSuffix(node: MNode, internal: Boolean) {
        while (true) {
            skipWhitespace()
            val ch = peek()
            if (ch == "" || ch == "," || ch == ")" || ch == ";") break

            if (ch == ":") {
                pos++
                node.length = readNumber()
                continue
            }
            if (ch == "[") {
                val comment = readComment()
                node.comment = if (node.comment != null) "${node.comment} $comment" else comment
                val support = extractSupport(comment)
                if (support != null && node.support == null) node.support = support
                continue
            }
            if (ch == "(") throw NewickParseError(NewickErrorCode.UNEXPECTED, pos, ch)
            if (ch == "]") throw NewickParseError(NewickErrorCode.UNEXPECTED, pos, ch)

            val label = readLabel()
            if (label.isEmpty()) throw NewickParseError(NewickErrorCode.UNEXPECTED, pos, ch)
            applyLabel(node, label, internal)
        }
    }

    private fun applyLabel(node: MNode, raw: String, internal: Boolean) {
        val label = raw.trim()
        if (label.isEmpty()) return
        if (internal) {
            if (node.name.isEmpty() && isNumericLabel(label)) {
                if (node.support == null) node.support = label.toDouble()
                return
            }
            if (node.name.isEmpty()) {
                node.name = label
            } else if (isNumericLabel(label) && node.support == null) {
                node.support = label.toDouble()
            } else {
                node.name += " $label"
            }
            return
        }
        node.name = if (node.name.isEmpty()) label else "${node.name} $label"
    }

    private fun readLabel(): String {
        val ch = peek()
        if (ch == "'" || ch == "\"") return readQuoted()
        val start = pos
        while (pos < text.length) {
            if (text[pos] in UNQUOTED_STOP) break
            pos++
        }
        return text.substring(start, pos).trim()
    }

    private fun readQuoted(): String {
        val quote = peek()
        val start = pos
        pos++
        val out = StringBuilder()
        while (pos < text.length) {
            val ch = text[pos]
            if (ch.toString() == quote) {
                if (pos + 1 < text.length && text[pos + 1].toString() == quote) {
                    out.append(quote)
                    pos += 2
                    continue
                }
                pos++
                return out.toString()
            }
            out.append(ch)
            pos++
        }
        throw NewickParseError(NewickErrorCode.BAD_QUOTED, start, quote)
    }

    private fun readComment(): String {
        val start = pos
        pos++
        var depth = 1
        val out = StringBuilder()
        while (pos < text.length) {
            val ch = text[pos]
            if (ch == '[') {
                depth++
                out.append(ch)
                pos++
                continue
            }
            if (ch == ']') {
                depth--
                pos++
                if (depth == 0) return out.toString()
                out.append(ch)
                continue
            }
            out.append(ch)
            pos++
        }
        throw NewickParseError(NewickErrorCode.BAD_COMMENT, start, "[")
    }

    private fun readNumber(): Double {
        skipWhitespace()
        val start = pos
        val m = NUMBER_RE.find(text.substring(pos))
            ?: throw NewickParseError(NewickErrorCode.BAD_LENGTH, start, peek())
        pos += m.value.length
        val next = peek()
        if (next.isNotEmpty() && next[0] !in UNQUOTED_STOP && !next[0].isWhitespace()) {
            throw NewickParseError(NewickErrorCode.BAD_LENGTH, pos, next)
        }
        val value = m.value.toDoubleOrNull()
        if (value == null || !value.isFinite()) throw NewickParseError(NewickErrorCode.BAD_LENGTH, start, m.value)
        return value
    }

    private fun skipWhitespace() {
        while (pos < text.length && text[pos].isWhitespace()) pos++
    }

    private fun peek(): String = if (pos < text.length) text[pos].toString() else ""
}

fun parseNewick(text: String): ParsedTree = NewickParser(text).parse()

private val ESCAPE_LABEL_RE = Regex("[\\s(),:;\\[\\]'\"]")

private fun escapeLabel(label: String): String {
    if (label.isEmpty()) return ""
    return if (ESCAPE_LABEL_RE.containsMatchIn(label)) "'${label.replace("'", "''")}'" else label
}

data class SerializeOptions(val collapsed: Set<String> = emptySet(), val includeRootLength: Boolean = true)

fun toNewick(root: TreeNode, options: SerializeOptions = SerializeOptions()): String {
    val collapsed = options.collapsed

    fun serialize(node: TreeNode, isRoot: Boolean): String {
        val isCollapsed = !isRoot && node.id in collapsed && node.children.isNotEmpty()
        val commentSupport = if (node.comment != null) extractSupport(node.comment) else null
        val supportInComment = commentSupport != null && node.support != null && commentSupport == node.support
        val labelPart = node.name.ifEmpty {
            if (node.support != null && !supportInComment) formatNumber(node.support) else ""
        }
        val commentPart = if (node.comment != null) "[${node.comment}]" else ""
        var out: String

        if (node.children.isNotEmpty() && !isCollapsed) {
            out = "(${node.children.joinToString(",") { serialize(it, false) }})"
            if (node.name.isNotEmpty()) {
                out += escapeLabel(node.name)
                if (node.support != null && !supportInComment) out += "[&support=${formatNumber(node.support)}]"
            } else if (node.support != null && !supportInComment) {
                out += formatNumber(node.support)
            }
        } else {
            out = escapeLabel(labelPart)
            if (node.name.isNotEmpty() && node.support != null && !supportInComment) {
                out += "[&support=${formatNumber(node.support)}]"
            }
        }

        out += commentPart
        if ((!isRoot || options.includeRootLength) && node.length != null) {
            out += ":${formatNumber(node.length)}"
        }
        return out
    }

    return "${serialize(root, true)};"
}

// ============================================================ treeops.ts ===

fun cloneTree(node: TreeNode): TreeNode =
    TreeNode(node.id, node.name, node.length, node.support, node.comment, node.children.map { cloneTree(it) }.toMutableList())

fun walkTree(node: TreeNode, parent: TreeNode? = null, fn: (TreeNode, TreeNode?) -> Unit) {
    fn(node, parent)
    for (child in node.children) walkTree(child, node, fn)
}

fun findNode(root: TreeNode, id: String): TreeNode? {
    var found: TreeNode? = null
    walkTree(root) { n, _ -> if (n.id == id) found = n }
    return found
}

fun buildParentMap(root: TreeNode): Map<String, String?> {
    val map = LinkedHashMap<String, String?>()
    walkTree(root) { n, p -> map[n.id] = p?.id }
    return map
}

fun collectLeafNodes(root: TreeNode): List<TreeNode> {
    val out = mutableListOf<TreeNode>()
    walkTree(root) { n, _ -> if (n.children.isEmpty()) out.add(n) }
    return out
}

fun descendantLeafCount(node: TreeNode): Int =
    if (node.children.isEmpty()) 1 else node.children.sumOf { descendantLeafCount(it) }

fun isDescendantOf(ancestor: TreeNode, id: String): Boolean {
    var found = false
    walkTree(ancestor) { n, _ -> if (n.id == id) found = true }
    return found
}

fun ancestorPath(root: TreeNode, id: String): List<String> {
    val path = mutableListOf<String>()
    val parents = buildParentMap(root)
    var cursor: String? = id
    while (cursor != null) {
        path.add(cursor)
        cursor = parents[cursor]
    }
    return path
}

private fun minLeafName(node: TreeNode, cache: MutableMap<String, String>): String {
    cache[node.id]?.let { return it }
    val value: String = if (node.children.isEmpty()) {
        node.name
    } else {
        node.children.map { minLeafName(it, cache) }
            .fold("\uFFFF") { best, current -> if (current < best) current else best }
    }
    cache[node.id] = value
    return value
}

fun ladderizeTree(root: TreeNode, descending: Boolean = false): TreeNode {
    val nameCache = mutableMapOf<String, String>()
    val sizeCache = mutableMapOf<String, Int>()
    fun size(node: TreeNode): Int {
        sizeCache[node.id]?.let { return it }
        val value = if (node.children.isEmpty()) 1 else node.children.sumOf { size(it) }
        sizeCache[node.id] = value
        return value
    }
    val cloned = cloneTree(root)
    fun apply(node: TreeNode) {
        for (child in node.children) apply(child)
        val sorted = node.children.sortedWith(compareBy({ size(it) }, { minLeafName(it, nameCache) }))
        node.children.clear()
        node.children.addAll(if (descending) sorted.reversed() else sorted)
    }
    apply(cloned)
    return cloned
}

fun sortTreeAlphabetically(root: TreeNode): TreeNode {
    val cache = mutableMapOf<String, String>()
    val cloned = cloneTree(root)
    fun apply(node: TreeNode) {
        for (child in node.children) apply(child)
        val sorted = node.children.sortedWith(compareBy { n ->
            if (n.children.isEmpty()) n.name else minLeafName(n, cache)
        })
        node.children.clear()
        node.children.addAll(sorted)
    }
    apply(cloned)
    return cloned
}

private fun groupIndex(group: String?, order: List<String>): Int {
    if (group == null) return order.size
    val index = order.indexOf(group)
    return if (index < 0) order.size else index
}

fun sortTreeByGroup(root: TreeNode, metadata: ResolvedMetadata): TreeNode {
    val cache = mutableMapOf<String, String>()
    val groupCache = mutableMapOf<String, String?>()
    val cloned = cloneTree(root)
    fun groupOf(node: TreeNode): String? {
        groupCache[node.id]?.let { return it }
        var result: String? = null
        if (node.children.isEmpty()) {
            result = metadata.byLeaf[node.name]?.group
        } else {
            var found: String? = null
            var same = true
            for (child in node.children) {
                val g = groupOf(child)
                if (g == null) {
                    same = false
                    break
                }
                if (found == null) found = g else if (found != g) {
                    same = false
                    break
                }
            }
            if (same && found != null) result = found
        }
        groupCache[node.id] = result
        return result
    }
    fun apply(node: TreeNode) {
        for (child in node.children) apply(child)
        val sorted = node.children.sortedWith(compareBy({ groupIndex(groupOf(it), metadata.groupOrder) },
            { minLeafName(it, cache) }))
        node.children.clear()
        node.children.addAll(sorted)
    }
    apply(cloned)
    return cloned
}

fun rotateChildren(root: TreeNode, nodeId: String): TreeNode {
    val cloned = cloneTree(root)
    val target = findNode(cloned, nodeId)
    if (target != null && target.children.size > 1) {
        val first = target.children.removeAt(0)
        target.children.add(first)
    }
    return cloned
}

/** 在最长根-叶路径中点重定根；无正枝长时返回 null。 */
fun midpointRoot(root: TreeNode): TreeNode? {
    data class Edge(val node: TreeNode, val length: Double)

    val adjacency = LinkedHashMap<String, MutableList<Edge>>()
    val byId = LinkedHashMap<String, TreeNode>()
    walkTree(root) { n, _ ->
        byId[n.id] = n
        adjacency.getOrPut(n.id) { mutableListOf() }
    }
    walkTree(root) { n, _ ->
        for (child in n.children) {
            val length = max(0.0, child.length ?: 0.0)
            adjacency[n.id]!!.add(Edge(child, length))
            adjacency[child.id]!!.add(Edge(n, length))
        }
    }

    var farthest: TreeNode? = null
    var farthestDistance = 0.0
    val parents = LinkedHashMap<String, String?>()
    val distances = LinkedHashMap<String, Double>()

    fun visit(node: TreeNode, parentId: String?, distance: Double) {
        parents[node.id] = parentId
        distances[node.id] = distance
        if (node.children.isEmpty()) {
            if (distance > farthestDistance) {
                farthestDistance = distance
                farthest = node
            }
            return
        }
        for (child in node.children) {
            visit(child, node.id, distance + max(0.0, child.length ?: 0.0))
        }
    }
    visit(root, null, 0.0)

    val far = farthest ?: return null
    if (farthestDistance <= 0.0) return null

    val path = mutableListOf<TreeNode>()
    var cursor: String? = far.id
    while (cursor != null) {
        val node = byId[cursor] ?: break
        path.add(node)
        cursor = parents[cursor]
    }
    path.reverse()
    if (path.size < 2) return null

    val target = farthestDistance / 2
    var splitIndex = 0
    for (i in 0 until path.size - 1) {
        val before = distances[path[i].id] ?: 0.0
        val after = distances[path[i + 1].id] ?: 0.0
        if (before <= target && target <= after) {
            splitIndex = i
            break
        }
    }

    val left = path[splitIndex]
    val right = path[splitIndex + 1]
    val before = distances[left.id] ?: 0.0
    val after = distances[right.id] ?: 0.0
    val leftLength = target - before
    val rightLength = after - target

    val usedIds = mutableSetOf<String>()
    walkTree(root) { n, _ -> usedIds.add(n.id) }
    var rootId = "root-midpoint"
    var suffix = 1
    while (rootId in usedIds) {
        rootId = "root-midpoint-${suffix++}"
    }

    fun buildDirected(node: TreeNode, fromId: String?): TreeNode {
        val copy = TreeNode(node.id, node.name, null, node.support, node.comment, mutableListOf())
        for (edge in adjacency[node.id] ?: emptyList()) {
            if (edge.node.id == fromId) continue
            val child = buildDirected(edge.node, node.id)
            // child.length 为 val；构造替换
            val withLength = TreeNode(child.id, child.name, edge.length, child.support, child.comment, child.children)
            copy.children.add(withLength)
        }
        return copy
    }

    val leftSide = buildDirected(left, right.id)
    val rightSide = buildDirected(right, left.id)
    val leftFinal = TreeNode(leftSide.id, leftSide.name, leftLength, leftSide.support, leftSide.comment, leftSide.children)
    val rightFinal = TreeNode(rightSide.id, rightSide.name, rightLength, rightSide.support, rightSide.comment, rightSide.children)

    return TreeNode(rootId, "", null, null, null, mutableListOf(leftFinal, rightFinal))
}

fun expandAncestors(root: TreeNode, id: String, collapsed: Set<String>): Set<String> {
    val next = HashSet(collapsed)
    val parents = buildParentMap(root)
    var cursor: String? = parents[id]
    while (cursor != null) {
        next.remove(cursor)
        cursor = parents[cursor]
    }
    next.remove(id)
    return next
}

fun nodeCount(root: TreeNode): Int {
    var count = 0
    walkTree(root) { _, _ -> count++ }
    return count
}

// ============================================================ layout.ts ===

data class LayoutInput(
    val root: TreeNode,
    val mode: LayoutMode,
    val fontSize: Double,
    val labelAngle: Double,
    val showBranchLengths: Boolean,
    val showScaleBar: Boolean,
    val title: String,
    val caption: String,
    val collapsed: Set<String>,
    val metadata: ResolvedMetadata,
    val rowHeight: Double? = null
)

private fun countLeaves(node: TreeNode, cache: MutableMap<String, Int>): Int {
    cache[node.id]?.let { return it }
    val value = if (node.children.isEmpty()) 1 else node.children.sumOf { countLeaves(it, cache) }
    cache[node.id] = value
    return value
}

private fun maxDistance(node: TreeNode, useLevels: Boolean, cache: MutableMap<String, Double>): Double {
    val key = "${node.id}:${if (useLevels) 'l' else 'b'}"
    cache[key]?.let { return it }
    var value = 0.0
    if (!useLevels) {
        for (child in node.children) {
            val edge = max(0.0, child.length ?: 0.0)
            value = max(value, edge + maxDistance(child, useLevels, cache))
        }
    } else if (node.children.isNotEmpty()) {
        var deepest = 0.0
        for (child in node.children) {
            deepest = max(deepest, maxDistance(child, useLevels, cache))
        }
        value = 1 + deepest
    }
    cache[key] = value
    return value
}

private fun uniformGroup(
    node: TreeNode,
    byLeaf: Map<String, LeafMeta>,
    cache: MutableMap<String, String?>
): String? {
    cache[node.id]?.let { return it }
    var group: String? = null
    if (node.children.isEmpty()) {
        group = byLeaf[node.name]?.group
    } else {
        var first: String? = null
        var firstSet = false
        var same = true
        for (child in node.children) {
            val childGroup = uniformGroup(child, byLeaf, cache)
            if (childGroup == null) {
                same = false
                break
            }
            if (!firstSet) {
                first = childGroup
                firstSet = true
            } else if (first != childGroup) {
                same = false
                break
            }
        }
        group = if (same && firstSet) first else null
    }
    cache[node.id] = group
    return group
}

internal fun wrapText(text: String, maxChars: Int): List<String> {
    val clean = text.replace("\r\n", "\n").trim()
    if (clean.isEmpty()) return emptyList()
    val out = mutableListOf<String>()
    for (paragraph in clean.split('\n')) {
        val words = paragraph.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) continue
        var line = ""
        for (word in words) {
            if (line.isEmpty()) {
                line = word
            } else if (line.length + 1 + word.length <= maxChars) {
                line += " $word"
            } else {
                out.add(line)
                line = word
            }
            // 中西文无空格长 token 硬断行
            while (line.length > maxChars) {
                out.add(line.substring(0, maxChars))
                line = line.substring(maxChars)
            }
        }
        if (line.isNotEmpty()) out.add(line)
    }
    return out
}

private fun niceUnit(target: Double): Double? {
    if (!(target > 0) || !target.isFinite()) return null
    val exp = floor(log10(target)).toInt()
    val base = 10.0.pow(exp)
    val fraction = target / base
    val nice = when {
        fraction <= 1 -> 1
        fraction <= 2 -> 2
        fraction <= 5 -> 5
        else -> 10
    }
    return nice * base
}

fun computeLayout(input: LayoutInput): TreeLayout {
    val leafCounts = mutableMapOf<String, Int>()
    val groupCache = mutableMapOf<String, String?>()
    val distanceCache = mutableMapOf<String, Double>()

    val branchMax = maxDistance(input.root, false, distanceCache)
    val useLevels = input.mode == LayoutMode.CLADOGRAM || branchMax <= 0
    val fallbackCladogram = input.mode != LayoutMode.CLADOGRAM && branchMax <= 0

    val fontSize = input.fontSize
    val rowHeight = input.rowHeight ?: max(fontSize * 2.3, 13.0)
    val titleFont = max(12.0, fontSize * 1.35)
    val captionFont = max(9.0, fontSize * 0.85)
    var marginTop = 16.0
    val marginRight = 24.0
    val marginBottom = 16.0
    val marginLeft = 26.0

    val byId = LinkedHashMap<String, LayoutNode>()
    val nodesOut = mutableListOf<LayoutNode>()
    val terminals = mutableListOf<LayoutNode>()
    var terminalCursor = 0

    fun build(node: TreeNode, depth: Double, level: Int): LayoutNode {
        val isCollapsed = node.children.isNotEmpty() && node.id in input.collapsed
        val childNodes = if (isCollapsed) mutableListOf() else {
            node.children.map { child ->
                build(child, depth + (if (useLevels) 1.0 else max(0.0, child.length ?: 0.0)), level + 1)
            }.toMutableList()
        }

        val group = uniformGroup(node, input.metadata.byLeaf, groupCache)
        val leafMeta = input.metadata.byLeaf[node.name]
        val color = if (node.children.isEmpty()) {
            leafMeta?.color ?: (group?.let { input.metadata.groupColors[it] })
        } else {
            group?.let { input.metadata.groupColors[it] }
        }

        val lnode = LayoutNode(
            id = node.id,
            name = node.name,
            length = node.length,
            support = node.support,
            comment = node.comment,
            depth = depth,
            level = level,
            parentId = null,
            x = 0.0, y = 0.0, radius = 0.0, angle = 0.0,
            parentRadius = 0.0, parentAngle = 0.0, parentX = 0.0, parentY = 0.0,
            isLeaf = node.children.isEmpty(),
            isCollapsed = isCollapsed,
            leafCount = countLeaves(node, leafCounts),
            terminalIndex = null,
            group = group,
            color = color,
            children = childNodes,
            startAngle = 0.0,
            endAngle = 0.0
        )
        for (child in childNodes) child.parentId = lnode.id
        nodesOut.add(lnode)
        byId[lnode.id] = lnode
        if (childNodes.isEmpty()) {
            lnode.terminalIndex = terminalCursor++
            terminals.add(lnode)
        }
        return lnode
    }

    val root = build(input.root, 0.0, 0)
    val terminalCount = max(1, terminalCursor)
    var maxDepth = 0.000001
    for (t in terminals) if (t.depth > maxDepth) maxDepth = t.depth

    var maxNameChars = 1
    for (t in terminals) {
        val name = t.name.ifEmpty { "Unnamed" }
        maxNameChars = max(maxNameChars, name.length + (if (t.isCollapsed) t.leafCount.toString().length + 3 else 0))
    }
    val branchLengthChars = if (input.showBranchLengths) 9 else 0
    val labelWidth = min(430.0, max(52.0, (maxNameChars + branchLengthChars) * fontSize * 0.62 + 12))

    if (input.mode != LayoutMode.CIRCULAR && input.labelAngle > 0) {
        val angleRadians = Math.PI * input.labelAngle / 180
        marginTop += min(180.0, ceil(labelWidth * sin(angleRadians) * 0.8))
    }

    val plotWidth = max(420.0, min(820.0, 420.0 + terminalCount * 4))
    val circularRadius = max(185.0, (terminalCount * rowHeight) / (2 * Math.PI) * 1.12)
    val circularOuter = circularRadius + labelWidth + 18

    val width: Double = if (input.mode == LayoutMode.CIRCULAR) {
        max(520.0, circularOuter * 2 + marginLeft + marginRight)
    } else {
        marginLeft + plotWidth + 14 + labelWidth + marginRight
    }

    val maxTitleChars = max(20, floor((width - marginLeft - marginRight) / max(1.0, titleFont * 0.62)).toInt())
    val titleLines = wrapText(input.title, maxTitleChars)
    val captionLines = wrapText(input.caption, maxTitleChars)
    val titleHeight = if (titleLines.isNotEmpty()) titleLines.size * titleFont * 1.35 + 12 else 0.0
    val captionHeight = if (captionLines.isNotEmpty()) captionLines.size * captionFont * 1.35 + 8 else 0.0
    val showScale = input.showScaleBar && !useLevels
    val scaleHeight = if (showScale) 34.0 else 0.0

    val plotTop = marginTop + titleHeight
    val plotHeight: Double
    val centerX: Double
    val centerY: Double
    val contentBottom: Double
    val depthScale: Double
    val height: Double

    if (input.mode == LayoutMode.CIRCULAR) {
        centerX = width / 2
        centerY = plotTop + circularOuter
        plotHeight = circularRadius * 2
        contentBottom = centerY + circularOuter
        height = contentBottom + captionHeight + scaleHeight + marginBottom
        depthScale = circularRadius / maxDepth
    } else {
        plotHeight = terminalCount * rowHeight
        centerX = marginLeft + plotWidth / 2
        centerY = plotTop + plotHeight / 2
        contentBottom = plotTop + plotHeight
        height = contentBottom + captionHeight + scaleHeight + marginBottom
        depthScale = plotWidth / maxDepth
    }

    // 矩形 y 坐标（环形模式下作为极坐标转换前的槽位中心）
    for (node in nodesOut) {
        val ti = node.terminalIndex
        if (ti != null) {
            node.y = plotTop + (ti + 0.5) * rowHeight
        }
    }
    // nodesOut 为后序（子先于父入列），一遍前向遍历即可平均子代位置
    for (node in nodesOut) {
        if (node.children.isNotEmpty()) {
            node.y = (node.children[0].y + node.children[node.children.size - 1].y) / 2
        }
    }

    if (input.mode == LayoutMode.CIRCULAR) {
        val step = Math.PI * 2 / terminalCount
        val start = -Math.PI / 2
        for (node in nodesOut) {
            val ti = node.terminalIndex
            if (ti != null) {
                node.angle = start + (ti + 0.5) * step
                node.startAngle = node.angle
                node.endAngle = node.angle
            }
        }
        for (node in nodesOut) {
            if (node.children.isNotEmpty()) {
                node.startAngle = node.children[0].startAngle
                node.endAngle = node.children[node.children.size - 1].endAngle
                node.angle = (node.startAngle + node.endAngle) / 2
            }
        }
        for (node in nodesOut) {
            node.radius = node.depth * depthScale
            node.x = centerX + node.radius * cos(node.angle)
            node.y = centerY + node.radius * sin(node.angle)
        }
    } else {
        for (node in nodesOut) {
            node.x = marginLeft + node.depth * depthScale
            node.radius = 0.0
            node.angle = 0.0
        }
    }

    // 分支绘制所需的父坐标
    for (node in nodesOut) {
        val pid = node.parentId ?: continue
        val parent = byId[pid] ?: continue
        if (input.mode == LayoutMode.CIRCULAR) {
            node.parentRadius = parent.radius
            node.parentAngle = parent.angle
            node.parentX = centerX + parent.radius * cos(node.angle)
            node.parentY = centerY + parent.radius * sin(node.angle)
        } else {
            node.parentRadius = 0.0
            node.parentAngle = 0.0
            node.parentX = parent.x
            node.parentY = parent.y
        }
    }

    val titleY = marginTop + titleFont
    val captionY = contentBottom + 10 + captionFont
    var scaleBar: ScaleBar? = null
    if (showScale) {
        val target = 105 / max(0.000001, depthScale)
        val unit = niceUnit(target)
        if (unit != null) {
            scaleBar = ScaleBar(
                x = if (input.mode == LayoutMode.CIRCULAR) marginLeft + 10 else marginLeft + 2,
                y = height - marginBottom - 5,
                lengthPx = unit * depthScale,
                unit = unit
            )
        }
    }

    return TreeLayout(
        mode = input.mode,
        width = width,
        height = height,
        centerX = centerX,
        centerY = centerY,
        plotLeft = marginLeft,
        plotTop = plotTop,
        plotWidth = if (input.mode == LayoutMode.CIRCULAR) circularRadius * 2 else plotWidth,
        plotHeight = plotHeight,
        root = root,
        nodes = nodesOut,
        byId = byId,
        terminals = terminals,
        leafCount = countLeaves(input.root, leafCounts),
        nodeCount = nodesOut.size,
        maxDepth = maxDepth,
        depthScale = depthScale,
        fallbackCladogram = fallbackCladogram,
        titleLines = titleLines,
        captionLines = captionLines,
        titleY = titleY,
        captionY = captionY,
        titleFont = titleFont,
        captionFont = captionFont,
        scaleBar = scaleBar
    )
}

fun collectLeaves(root: TreeNode): List<TreeNode> {
    val out = mutableListOf<TreeNode>()
    walkTree(root) { n, _ -> if (n.children.isEmpty()) out.add(n) }
    return out
}

// ========================================================== metadata.ts ===

class MetadataParseError(val row: Int) : Exception("metadata row $row")

private val NAMED_COLORS = setOf(
    "black", "silver", "gray", "grey", "white", "maroon", "red", "purple",
    "fuchsia", "magenta", "green", "lime", "olive", "yellow", "navy", "blue",
    "teal", "aqua", "cyan", "orange", "gold", "brown", "pink", "indigo",
    "violet", "turquoise", "salmon", "coral", "crimson", "darkgreen",
    "darkblue", "darkred", "steelblue", "seagreen", "tomato", "orchid"
)

/** CSS 命名色 → ARGB（Android parseColor 不覆盖全集，手工映射）。 */
val NAMED_COLOR_HEX = mapOf(
    "black" to "#000000", "silver" to "#C0C0C0", "gray" to "#808080", "grey" to "#808080",
    "white" to "#FFFFFF", "maroon" to "#800000", "red" to "#FF0000", "purple" to "#800080",
    "fuchsia" to "#FF00FF", "magenta" to "#FF00FF", "green" to "#008000", "lime" to "#00FF00",
    "olive" to "#808000", "yellow" to "#FFFF00", "navy" to "#000080", "blue" to "#0000FF",
    "teal" to "#008080", "aqua" to "#00FFFF", "cyan" to "#00FFFF", "orange" to "#FFA500",
    "gold" to "#FFD700", "brown" to "#A52A2A", "pink" to "#FFC0CB", "indigo" to "#4B0082",
    "violet" to "#EE82EE", "turquoise" to "#40E0D0", "salmon" to "#FA8072", "coral" to "#FF7F50",
    "crimson" to "#DC143C", "darkgreen" to "#006400", "darkblue" to "#00008B", "darkred" to "#8B0000",
    "steelblue" to "#4682B4", "seagreen" to "#2E8B57", "tomato" to "#FF6347", "orchid" to "#DA70D6"
)

private val HEX_COLOR_RE = Regex("^#[0-9a-fA-F]{3,8}$")

fun sanitizeColor(raw: String?): String? {
    if (raw == null) return null
    val value = raw.trim()
    if (value.isEmpty()) return null
    if (HEX_COLOR_RE.matches(value)) return value
    if (value.lowercase() in NAMED_COLORS) return value.lowercase()
    return null
}

private fun countDelimiters(line: String, delimiter: Char): Int {
    var quoted = false
    var count = 0
    var i = 0
    while (i < line.length) {
        val ch = line[i]
        if (ch == '"') {
            if (quoted && i + 1 < line.length && line[i + 1] == '"') {
                i++
                i++
                continue
            }
            quoted = !quoted
        } else if (!quoted && ch == delimiter) {
            count++
        }
        i++
    }
    return count
}

private fun detectDelimiter(text: String): Char {
    for (line in text.split(Regex("\r?\n"))) {
        if (line.isBlank() || line.trim().startsWith("#")) continue
        val tabs = countDelimiters(line, '\t')
        val commas = countDelimiters(line, ',')
        return if (tabs > commas) '\t' else ','
    }
    return ','
}

private fun splitRow(line: String, delimiter: Char, row: Int): List<String> {
    val cells = mutableListOf<String>()
    var current = StringBuilder()
    var quoted = false
    var i = 0
    while (i < line.length) {
        val ch = line[i]
        if (quoted) {
            if (ch == '"') {
                if (i + 1 < line.length && line[i + 1] == '"') {
                    current.append('"')
                    i++
                } else {
                    quoted = false
                }
            } else {
                current.append(ch)
            }
            i++
            continue
        }
        if (ch == '"' && current.toString().trim().isEmpty()) {
            current = StringBuilder()
            quoted = true
            i++
            continue
        }
        if (ch == delimiter) {
            cells.add(current.toString().trim())
            current = StringBuilder()
            i++
            continue
        }
        current.append(ch)
        i++
    }
    if (quoted) throw MetadataParseError(row)
    cells.add(current.toString().trim())
    return cells
}

private fun findColumn(header: List<String>, aliases: List<String>): Int {
    for (i in header.indices) {
        if (header[i].trim().lowercase() in aliases) return i
    }
    return -1
}

/** 解析元数据表：表头可选；接受 leaf/name/taxon、group/clade/category、color/colour 列。 */
fun parseMetadataTable(text: String): List<MetaEntry> {
    var clean = text
    if (clean.startsWith("﻿")) clean = clean.removePrefix("﻿")
    clean = clean.replace("\r\n", "\n")
    if (clean.isBlank()) return emptyList()

    val delimiter = detectDelimiter(clean)
    val lines = clean.split('\n')
    var leafIndex = 0
    var groupIndex = 1
    var colorIndex = 2
    var startRow = 0

    var firstDataRow = -1
    for (i in lines.indices) {
        if (lines[i].isNotBlank() && !lines[i].trim().startsWith("#")) {
            firstDataRow = i
            break
        }
    }
    if (firstDataRow < 0) return emptyList()

    val headerCells = splitRow(lines[firstDataRow], delimiter, firstDataRow + 1)
    val header = headerCells.map { it.lowercase() }
    val hasHeader = findColumn(header, listOf("leaf", "name", "taxon", "tip", "label")) >= 0 ||
        findColumn(header, listOf("group", "clade", "category", "family")) >= 0

    if (hasHeader) {
        leafIndex = findColumn(header, listOf("leaf", "name", "taxon", "tip", "label"))
        groupIndex = findColumn(header, listOf("group", "clade", "category", "family"))
        colorIndex = findColumn(header, listOf("color", "colour", "hex"))
        startRow = firstDataRow + 1
        if (leafIndex < 0 || groupIndex < 0) throw MetadataParseError(firstDataRow + 1)
    } else {
        startRow = firstDataRow
    }

    val entries = mutableListOf<MetaEntry>()
    val position = mutableMapOf<String, Int>()

    for (i in startRow until lines.size) {
        val line = lines[i]
        if (line.isBlank() || line.trim().startsWith("#")) continue
        val cells = splitRow(line, delimiter, i + 1)
        if (cells.size < 2) throw MetadataParseError(i + 1)

        val maxIndex = maxOf(leafIndex, groupIndex, colorIndex)
        if (cells.size <= maxIndex && cells.size <= maxOf(leafIndex, groupIndex)) {
            throw MetadataParseError(i + 1)
        }

        val leaf = cells.getOrNull(leafIndex)?.trim() ?: ""
        if (leaf.isEmpty()) continue
        val group = cells.getOrNull(groupIndex)?.trim() ?: ""
        val color = if (colorIndex >= 0) sanitizeColor(cells.getOrNull(colorIndex)) else null

        val entry = MetaEntry(leaf, group, color)
        val existing = position[leaf]
        if (existing != null) entries[existing] = entry
        else {
            position[leaf] = entries.size
            entries.add(entry)
        }
    }
    return entries
}

fun resolveMetadata(metadata: TreeMetadata): ResolvedMetadata {
    val byLeaf = LinkedHashMap<String, LeafMeta>()
    val groupOrder = mutableListOf<String>()
    val groupCounts = LinkedHashMap<String, Int>()
    val explicitGroupColors = LinkedHashMap<String, String>()

    for (entry in metadata.entries) {
        if (entry.leaf.isEmpty()) continue
        val group = entry.group.trim()
        val color = sanitizeColor(entry.color)
        if (group.isNotEmpty()) {
            if (group !in groupCounts) {
                groupOrder.add(group)
                groupCounts[group] = 0
            }
            groupCounts[group] = groupCounts[group]!! + 1
            if (color != null && group !in explicitGroupColors) explicitGroupColors[group] = color
        }
        byLeaf[entry.leaf] = LeafMeta(group, color ?: "")
    }

    val palette = PALETTES[metadata.paletteId] ?: PALETTES[PaletteId.DEFAULT]!!
    val groupColors = LinkedHashMap<String, String>()
    var cursor = 0
    for (group in groupOrder) {
        groupColors[group] = explicitGroupColors[group] ?: palette[cursor % palette.size]
        cursor++
    }

    val fixed = LinkedHashMap<String, LeafMeta>()
    for ((leaf, meta) in byLeaf) {
        fixed[leaf] = if (meta.color.isEmpty()) {
            LeafMeta(meta.group, groupColors[meta.group] ?: "")
        } else meta
    }

    return ResolvedMetadata(
        byLeaf = fixed,
        groupOrder = groupOrder,
        groupColors = groupColors,
        groupCounts = groupCounts,
        ungrouped = metadata.entries.count { it.group.isBlank() }
    )
}

fun metadataToCsv(metadata: TreeMetadata): String {
    val lines = mutableListOf("leaf,group,color")
    for (entry in metadata.entries) {
        fun quote(value: String): String =
            if (Regex("[\",\n]").containsMatchIn(value)) "\"${value.replace("\"", "\"\"")}\"" else value
        lines.add("${quote(entry.leaf)},${quote(entry.group)},${quote(entry.color ?: "")}")
    }
    return lines.joinToString("\n")
}

// =========================================================== support.ts ===

data class SupportBin(
    val index: Int,
    val from: Double,
    val to: Double,
    val shown: Int,
    val filtered: Int,
    val total: Int
)

data class SupportSummary(
    val internal: Int,
    val withSupport: Int,
    val shown: Int,
    val filtered: Int,
    val min: Double?,
    val max: Double?,
    val mean: Double?,
    val threshold: Double,
    val bins: List<SupportBin>
)

fun emptySupportSummary(binCount: Int = 10): SupportSummary {
    val bins = max(1, binCount.toDouble().roundToInt())
    val width = 100.0 / bins
    return SupportSummary(
        internal = 0, withSupport = 0, shown = 0, filtered = 0,
        min = null, max = null, mean = null, threshold = 0.0,
        bins = (0 until bins).map { i ->
            SupportBin(i, i * width, (i + 1) * width, 0, 0, 0)
        }
    )
}

fun supportSummary(root: TreeNode, threshold: Double = 0.0, binCount: Int = 10): SupportSummary {
    val bins = max(1, binCount.toDouble().roundToInt())
    val limit = min(100.0, max(0.0, threshold))
    val width = 100.0 / bins
    val table = (0 until bins).map { i ->
        var shown = 0; var filtered = 0; var total = 0
        SupportBin(i, i * width, (i + 1) * width, shown, filtered, total)
    }.toMutableList()

    var internal = 0
    var withSupport = 0
    var shown = 0
    var filtered = 0
    var minV = Double.POSITIVE_INFINITY
    var maxV = Double.NEGATIVE_INFINITY
    var sum = 0.0

    walkTree(root) { node, _ ->
        if (node.children.isEmpty()) return@walkTree
        internal++
        val support = normalizedSupport(node.support) ?: return@walkTree
        withSupport++
        sum += support
        if (support < minV) minV = support
        if (support > maxV) maxV = support
        val index = min(bins - 1, max(0, floor(support / width).toInt()))
        val bin = table[index]
        table[index] = bin.copy(total = bin.total + 1)
        if (support >= limit) {
            table[index] = table[index].copy(shown = table[index].shown + 1)
            shown++
        } else {
            table[index] = table[index].copy(filtered = table[index].filtered + 1)
            filtered++
        }
    }

    return SupportSummary(
        internal = internal,
        withSupport = withSupport,
        shown = shown,
        filtered = filtered,
        min = if (withSupport > 0) minV else null,
        max = if (withSupport > 0) maxV else null,
        mean = if (withSupport > 0) sum / withSupport else null,
        threshold = limit,
        bins = table
    )
}

// =========================================================== compare.ts ===

data class TreeComparison(
    val sharedLeaves: List<String>,
    val leavesOnlyA: List<String>,
    val leavesOnlyB: List<String>,
    val splitsA: Int,
    val splitsB: Int,
    val sharedSplits: Int,
    val uniqueA: Int,
    val uniqueB: Int,
    val rf: Int,
    val maxRf: Int,
    val normalizedRf: Double,
    val identical: Boolean,
    val sharedSplitKeys: List<String>,
    val uniqueSplitKeysA: List<String>,
    val uniqueSplitKeysB: List<String>
)

/** Robinson-Foulds：共享叶集上的分裂比较（无根约定，平凡分裂剔除）。 */
fun compareTrees(current: TreeNode, other: TreeNode): TreeComparison {
    fun collectLeaves(node: TreeNode, out: MutableSet<String>) {
        if (node.children.isEmpty()) {
            if (node.name.isNotEmpty()) out.add(node.name)
            return
        }
        for (child in node.children) collectLeaves(child, out)
    }

    fun canonicalKey(side: Set<String>, all: Set<String>): String {
        val otherSide = all.filter { it !in side }.sorted()
        val own = side.sorted()
        val ownFirst = own.size < otherSide.size ||
            (own.size == otherSide.size && own.joinToString("") <= otherSide.joinToString(""))
        return (if (ownFirst) own else otherSide).joinToString("")
    }

    val leafSetA = mutableSetOf<String>()
    val leafSetB = mutableSetOf<String>()
    collectLeaves(current, leafSetA)
    collectLeaves(other, leafSetB)

    val sharedLeaves = leafSetA.filter { it in leafSetB }.sorted()
    val leavesOnlyA = leafSetA.filter { it !in leafSetB }.sorted()
    val leavesOnlyB = leafSetB.filter { it !in leafSetA }.sorted()
    val shared = sharedLeaves.toSet()

    fun walk(node: TreeNode, sharedSet: Set<String>): Pair<MutableSet<String>, MutableSet<String>> {
        val names = mutableSetOf<String>()
        val splits = mutableSetOf<String>()
        if (node.children.isEmpty()) {
            if (node.name in sharedSet) names.add(node.name)
            return names to splits
        }
        for (child in node.children) {
            val (childNames, childSplits) = walk(child, sharedSet)
            names.addAll(childNames)
            splits.addAll(childSplits)
        }
        if (names.size >= 2 && sharedSet.size - names.size >= 2) {
            splits.add(canonicalKey(names, sharedSet))
        }
        return names to splits
    }

    val splitsA = walk(current, shared).second
    val splitsB = walk(other, shared).second

    val sharedSplitKeys = mutableListOf<String>()
    val uniqueSplitKeysA = mutableListOf<String>()
    val uniqueSplitKeysB = mutableListOf<String>()
    for (key in splitsA.sorted()) {
        if (key in splitsB) sharedSplitKeys.add(key) else uniqueSplitKeysA.add(key)
    }
    for (key in splitsB.sorted()) {
        if (key !in splitsA) uniqueSplitKeysB.add(key)
    }

    val rf = uniqueSplitKeysA.size + uniqueSplitKeysB.size
    val maxRf = splitsA.size + splitsB.size
    return TreeComparison(
        sharedLeaves = sharedLeaves,
        leavesOnlyA = leavesOnlyA,
        leavesOnlyB = leavesOnlyB,
        splitsA = splitsA.size,
        splitsB = splitsB.size,
        sharedSplits = sharedSplitKeys.size,
        uniqueA = uniqueSplitKeysA.size,
        uniqueB = uniqueSplitKeysB.size,
        rf = rf,
        maxRf = maxRf,
        normalizedRf = if (maxRf > 0) rf.toDouble() / maxRf else 0.0,
        identical = rf == 0 && leavesOnlyA.isEmpty() && leavesOnlyB.isEmpty() && sharedLeaves.isNotEmpty(),
        sharedSplitKeys = sharedSplitKeys,
        uniqueSplitKeysA = uniqueSplitKeysA,
        uniqueSplitKeysB = uniqueSplitKeysB
    )
}

// ==================================================== export.ts（JSON）===

class TreeProjectError(message: String = "invalid project") : Exception(message)

private fun clamp(v: Double, lo: Double, hi: Double): Double = min(hi, max(lo, v))

fun normalizeSettings(raw: JSONObject?): TreeSettings {
    val base = defaultSettings()
    if (raw == null) return base
    var s = base
    LayoutMode.fromKey(raw.optString("mode"))?.let { s = s.copy(mode = it) }
    if (raw.has("fontSize")) raw.optDoubleOrNull("fontSize")?.let {
        if (it.isFinite()) s = s.copy(fontSize = clamp(it, 6.0, 36.0))
    }
    if (raw.has("branchWidth")) raw.optDoubleOrNull("branchWidth")?.let {
        if (it.isFinite()) s = s.copy(branchWidth = clamp(it, 0.5, 8.0))
    }
    if (raw.has("labelAngle")) raw.optDoubleOrNull("labelAngle")?.let {
        if (it.isFinite()) s = s.copy(labelAngle = clamp(it, 0.0, 90.0))
    }
    if (raw.has("supportThreshold")) raw.optDoubleOrNull("supportThreshold")?.let {
        if (it.isFinite()) s = s.copy(supportThreshold = clamp(it, 0.0, 100.0))
    }
    if (raw.has("showBranchLengths") && !raw.isNull("showBranchLengths")) {
        s = s.copy(showBranchLengths = raw.optBoolean("showBranchLengths"))
    }
    if (raw.has("showSupport") && !raw.isNull("showSupport")) {
        s = s.copy(showSupport = raw.optBoolean("showSupport"))
    }
    if (raw.has("showNodeDots") && !raw.isNull("showNodeDots")) {
        s = s.copy(showNodeDots = raw.optBoolean("showNodeDots"))
    }
    if (raw.has("showScaleBar") && !raw.isNull("showScaleBar")) {
        s = s.copy(showScaleBar = raw.optBoolean("showScaleBar"))
    }
    TreeTheme.fromKey(raw.optString("theme"))?.let { s = s.copy(theme = it) }
    return s
}

private fun JSONObject.optJSONObjectOrNull(key: String): JSONObject? =
    if (has(key) && !isNull(key)) optJSONObject(key) else null

private fun JSONObject.optDoubleOrNull(key: String): Double? {
    val v = opt(key)
    return when (v) {
        is Double -> v
        is Int -> v.toDouble()
        is Long -> v.toDouble()
        is java.math.BigDecimal -> v.toDouble()
        else -> null
    }
}

fun normalizeMetadata(raw: JSONObject?): TreeMetadata {
    if (raw == null) return emptyMetadata()
    val entries = mutableListOf<MetaEntry>()
    val arr = raw.optJSONArray("entries")
    if (arr != null) {
        for (i in 0 until arr.length()) {
            val item = arr.optJSONObject(i) ?: continue
            val leaf = item.optString("leaf", "")
            if (leaf.isBlank()) continue
            val group = item.optString("group", "")
            val color = if (item.has("color") && !item.isNull("color")) item.optString("color") else null
            entries.add(MetaEntry(leaf, group, color))
        }
    }
    val pid = PaletteId.fromKey(raw.optString("paletteId")) ?: PaletteId.DEFAULT
    return TreeMetadata(entries, pid)
}

fun nodeToSerialized(node: TreeNode): SerializedNode =
    SerializedNode(node.id, node.name, node.length, node.support, node.comment,
        node.children.map { nodeToSerialized(it) })

private class IdCounter(var value: Int = 1)

private fun normalizeSerialized(raw: Any?, usedIds: MutableSet<String>, counter: IdCounter): SerializedNode {
    if (raw !is JSONObject) throw TreeProjectError("node is not an object")
    var id = if (raw.has("id") && raw.optString("id").isNotEmpty()) raw.optString("id") else ""
    while (id.isEmpty() || id in usedIds) {
        id = "p${counter.value++}"
    }
    usedIds.add(id)
    val name = if (raw.has("name")) raw.optString("name") else ""
    val length = raw.optDoubleOrNull("length")?.takeIf { it.isFinite() }
    val support = raw.optDoubleOrNull("support")?.takeIf { it.isFinite() }
    val comment = if (raw.has("comment") && !raw.isNull("comment")) raw.optString("comment") else null
    val children = mutableListOf<SerializedNode>()
    val arr = raw.optJSONArray("children")
    if (arr != null) {
        for (i in 0 until arr.length()) {
            children.add(normalizeSerialized(arr.get(i), usedIds, counter))
        }
    }
    return SerializedNode(id, name, length, support, comment, children)
}

fun serializedToTree(node: SerializedNode): TreeNode =
    TreeNode(node.id, node.name, node.length, node.support, node.comment,
        node.children.map { serializedToTree(it) }.toMutableList())

fun deserializeTree(raw: Any?): TreeNode {
    val normalized = normalizeSerialized(raw, mutableSetOf(), IdCounter())
    return serializedToTree(normalized)
}

fun parseProjectJson(text: String): TreeProject {
    val raw: JSONObject = try {
        JSONObject(text)
    } catch (e: Exception) {
        throw TreeProjectError("not JSON")
    }
    if (raw.optString("format") != "spice-tree") throw TreeProjectError("unknown format")
    if (raw.optInt("version", -1) != 1) throw TreeProjectError("unknown version")
    if (raw.opt("originalNewick") !is String) throw TreeProjectError("missing Newick")
    val originalNewick = raw.optString("originalNewick")
    if (!raw.has("tree")) throw TreeProjectError("missing tree")

    val tree = normalizeSerialized(raw.opt("tree"), mutableSetOf(), IdCounter())
    val collapsedIds = mutableListOf<String>()
    val arr = raw.optJSONArray("collapsedIds")
    if (arr != null) {
        for (i in 0 until arr.length()) {
            val v = arr.opt(i)
            if (v is String) collapsedIds.add(v)
        }
    }

    return TreeProject(
        format = "spice-tree",
        version = 1,
        originalNewick = originalNewick,
        tree = tree,
        rooted = if (raw.has("rooted") && !raw.isNull("rooted")) raw.optBoolean("rooted") else true,
        sourceName = if (raw.has("sourceName") && !raw.isNull("sourceName")) raw.optString("sourceName") else null,
        collapsedIds = collapsedIds,
        settings = normalizeSettings(raw.optJSONObjectOrNull("settings")),
        metadata = normalizeMetadata(raw.optJSONObjectOrNull("metadata")),
        title = if (raw.has("title")) raw.optString("title") else "",
        caption = if (raw.has("caption")) raw.optString("caption") else "",
        savedAt = if (raw.has("savedAt")) raw.optString("savedAt") else isoNow()
    )
}

fun isoNow(): String {
    val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
    fmt.timeZone = TimeZone.getTimeZone("UTC")
    return fmt.format(Date())
}

// ---- 手写 pretty 序列化：复刻 JSON.stringify(project, null, 2) 的键序与格式 ----

private fun jsonEsc(s: String): String {
    val sb = StringBuilder("\"")
    for (ch in s) {
        when {
            ch == '"' -> sb.append("\\\"")
            ch == '\\' -> sb.append("\\\\")
            ch == '\n' -> sb.append("\\n")
            ch == '\r' -> sb.append("\\r")
            ch == '\t' -> sb.append("\\t")
            ch == '\b' -> sb.append("\\b")
            ch == '\u000C' -> sb.append("\\f")
            ch < ' ' -> sb.append("\\u%04x".format(ch.code))
            else -> sb.append(ch)
        }
    }
    sb.append('"')
    return sb.toString()
}

private fun jsonVal(v: Any?): String = when (v) {
    null -> "null"
    is String -> jsonEsc(v)
    is Double -> jsNum(v)
    is Int, is Long -> v.toString()
    is Boolean -> v.toString()
    is JsonObj -> v.toString()
    is JsonArr -> v.toString()
    else -> "null"
}

/** 保序 JSON 对象 + JSON.stringify(null,2) 风格缩进输出。 */
class JsonObj {
    private val pairs = mutableListOf<Pair<String, Any?>>()
    fun put(key: String, value: Any?): JsonObj {
        pairs.add(key to value); return this
    }
    override fun toString(): String = toString(0)
    fun toString(indent: Int): String {
        val pad = "  ".repeat(indent + 1)
        val close = "  ".repeat(indent)
        if (pairs.isEmpty()) return "{}"
        val body = pairs.joinToString(",\n") { (k, v) ->
            when (v) {
                is JsonObj -> "$pad${jsonEsc(k)}: ${v.toString(indent + 1)}"
                is JsonArr -> "$pad${jsonEsc(k)}: ${v.toString(indent + 1)}"
                else -> "$pad${jsonEsc(k)}: ${jsonVal(v)}"
            }
        }
        return "{\n$body\n$close}"
    }
}

class JsonArr(private val items: List<Any?>) {
    override fun toString(): String = toString(0)
    fun toString(indent: Int): String {
        val pad = "  ".repeat(indent + 1)
        val close = "  ".repeat(indent)
        if (items.isEmpty()) return "[]"
        val body = items.joinToString(",\n") { v ->
            when (v) {
                is JsonObj -> "$pad${v.toString(indent + 1)}"
                is JsonArr -> "$pad${v.toString(indent + 1)}"
                else -> "$pad${jsonVal(v)}"
            }
        }
        return "[\n$body\n$close]"
    }
}

private fun settingsToJson(s: TreeSettings): JsonObj = JsonObj()
    .put("mode", s.mode.key)
    .put("fontSize", s.fontSize)
    .put("branchWidth", s.branchWidth)
    .put("labelAngle", s.labelAngle)
    .put("supportThreshold", s.supportThreshold)
    .put("showBranchLengths", s.showBranchLengths)
    .put("showSupport", s.showSupport)
    .put("showNodeDots", s.showNodeDots)
    .put("showScaleBar", s.showScaleBar)
    .put("theme", s.theme.key)

private fun metadataToJson(m: TreeMetadata): JsonObj = JsonObj()
    .put("entries", JsonArr(m.entries.map { e ->
        JsonObj().put("leaf", e.leaf).put("group", e.group).put("color", e.color)
    }))
    .put("paletteId", m.paletteId.key)

private fun serializedToJson(n: SerializedNode): JsonObj = JsonObj()
    .put("id", n.id)
    .put("name", n.name)
    .put("length", n.length)
    .put("support", n.support)
    .put("comment", n.comment)
    .put("children", JsonArr(n.children.map { serializedToJson(it) }))

fun serializeProject(project: TreeProject): String = JsonObj()
    .put("format", project.format)
    .put("version", project.version)
    .put("originalNewick", project.originalNewick)
    .put("tree", serializedToJson(project.tree))
    .put("rooted", project.rooted)
    .put("sourceName", project.sourceName)
    .put("collapsedIds", JsonArr(project.collapsedIds))
    .put("settings", settingsToJson(project.settings))
    .put("metadata", metadataToJson(project.metadata))
    .put("title", project.title)
    .put("caption", project.caption)
    .put("savedAt", project.savedAt)
    .toString()

// =========================================================== example.ts ===

const val EXAMPLE_NEWICK =
    "((((Homo_sapiens:0.021,Pan_troglodytes:0.024)100:0.041,Gorilla_gorilla:0.068)98:0.052,Pongo_abelii:0.091)100:0.113,((Mus_musculus:0.051,Rattus_norvegicus:0.063)100:0.082,((Canis_lupus:0.071,Felis_catus:0.089)96:0.048,((Bos_taurus:0.061,Sus_scrofa:0.083)94:0.051,Equus_caballus:0.102)89:0.044)92:0.061)99:0.104)100;"

val EXAMPLE_METADATA = listOf(
    "leaf,group,color",
    "Homo_sapiens,Primates,#4cd6ff",
    "Pan_troglodytes,Primates,#4cd6ff",
    "Gorilla_gorilla,Primates,#4cd6ff",
    "Pongo_abelii,Primates,#4cd6ff",
    "Mus_musculus,Rodents,#ffd23f",
    "Rattus_norvegicus,Rodents,#ffd23f",
    "Canis_lupus,Carnivores,#ff9f1c",
    "Felis_catus,Carnivores,#ff9f1c",
    "Bos_taurus,Artiodactyla,#53d769",
    "Sus_scrofa,Artiodactyla,#53d769",
    "Equus_caballus,Perissodactyla,#b48cff"
).joinToString("\n")

// ============================================================ render.ts ===

data class ThemeColors(
    val background: String,
    val foreground: String,
    val dim: String,
    val border: String,
    val accent: String,
    val link: String,
    val highlight: String,
    val support: String,
    val title: String,
    val caption: String
)

val THEME_COLORS = mapOf(
    TreeTheme.DARK to ThemeColors(
        background = "#0b0e14", foreground = "#d8e1f2", dim = "#8b96b5", border = "#2a3450",
        accent = "#ffd23f", link = "#8b96b5", highlight = "#4cd6ff", support = "#ffd23f",
        title = "#ffd23f", caption = "#8b96b5"
    ),
    TreeTheme.LIGHT to ThemeColors(
        background = "#ffffff", foreground = "#1b2333", dim = "#5c677d", border = "#c7cede",
        accent = "#b45309", link = "#5c677d", highlight = "#0369a1", support = "#b45309",
        title = "#0f172a", caption = "#475569"
    )
)

/** 交互态传参（预览与导出共用同一渲染函数；导出时全部置空）。 */
data class RenderState(
    val selectedId: String? = null,
    val hoveredId: String? = null,
    val pathIds: Set<String> = emptySet(),
    val hoverPathIds: Set<String> = emptySet(),
    val searchMatches: Set<String> = emptySet(),
    val focusGroup: String? = null,
    val collapseLabel: String = "",
    val expandLabel: String = "",
    val unitLabel: String = "",
    val unnamedLabel: String = ""
) {
    companion object {
        fun export() = RenderState()
    }
}

private fun esc(value: String): String = value
    .replace("&", "&amp;")
    .replace("<", "&lt;")
    .replace(">", "&gt;")
    .replace("\"", "&quot;")
    .replace("'", "&#39;")

private fun rnd(value: Double): Double = kotlin.math.round(value * 100) / 100

private fun num(value: Double): String = jsNum(rnd(value))

private fun point(cx: Double, cy: Double, radius: Double, angle: Double): Pair<Double, Double> =
    cx + radius * cos(angle) to cy + radius * sin(angle)

private fun flipDegrees(angle: Double): Pair<Double, String> {
    var degrees = angle * 180 / Math.PI
    if (cos(angle) < 0) {
        degrees += 180
        return degrees to "end"
    }
    return degrees to "start"
}

/** 与 render.ts renderTreeSvg 完全一致的 SVG 字符串（含交互命中层，导出与预览同一产物）。 */
fun renderTreeSvg(
    layout: TreeLayout,
    settings: TreeSettings,
    state: RenderState,
    title: String,
    caption: String
): String {
    val theme = THEME_COLORS[settings.theme] ?: THEME_COLORS[TreeTheme.DARK]!!
    val fs = settings.fontSize
    val circular = layout.mode == LayoutMode.CIRCULAR
    val selectedActive = state.selectedId != null

    fun isDimmed(node: LayoutNode): Boolean {
        if (state.focusGroup != null && node.group != state.focusGroup) return true
        if (selectedActive && node.id !in state.pathIds) return true
        return false
    }

    val edgePaths = mutableListOf<String>()
    val arcPaths = mutableListOf<String>()
    val lengthLabels = mutableListOf<String>()
    val wedges = mutableListOf<String>()
    val dots = mutableListOf<String>()
    val labels = mutableListOf<String>()
    val supports = mutableListOf<String>()

    for (node in layout.nodes) {
        val dim = if (isDimmed(node)) " dim" else ""
        val onPath = selectedActive && node.id in state.pathIds
        val onHoverPath = node.id in state.hoverPathIds
        val hovered = state.hoveredId == node.id
        val strokeColor = when {
            onPath -> theme.highlight
            onHoverPath -> theme.accent
            else -> node.color ?: theme.link
        }
        val width = settings.branchWidth +
            (if (onPath) 1.1 else if (onHoverPath) 0.6 else 0.0) +
            (if (hovered) 0.7 else 0.0)

        if (node.parentId != null) {
            var d: String
            if (circular) {
                val (sx, sy) = point(layout.centerX, layout.centerY, node.parentRadius, node.angle)
                d = "M ${num(sx)} ${num(sy)} L ${num(node.x)} ${num(node.y)}"
            } else {
                d = "M ${num(node.parentX)} ${num(node.parentY)} L ${num(node.x)} ${num(node.parentY)} L ${num(node.x)} ${num(node.y)}"
            }
            edgePaths.add(
                "<path class=\"branch$dim\" d=\"$d\" fill=\"none\" stroke=\"$strokeColor\" stroke-width=\"${num(width)}\" stroke-linecap=\"square\" stroke-linejoin=\"miter\"/>" +
                    "<path class=\"branch-hit\" data-node-id=\"${esc(node.id)}\" d=\"$d\" fill=\"none\" stroke=\"transparent\" stroke-width=\"${num(max(10.0, width + 8))}\" pointer-events=\"stroke\"/>"
            )

            if (settings.showBranchLengths && node.length != null && !node.isLeaf && !node.isCollapsed) {
                val text = formatNumber(node.length)
                if (circular) {
                    val middle = (node.parentRadius + node.radius) / 2
                    val (tx, ty) = point(layout.centerX, layout.centerY, middle, node.angle)
                    val (degrees, anchor) = flipDegrees(node.angle)
                    lengthLabels.add(
                        "<text class=\"blen$dim\" x=\"${num(tx)}\" y=\"${num(ty)}\" text-anchor=\"$anchor\" dominant-baseline=\"middle\" transform=\"rotate(${num(degrees)} ${num(tx)} ${num(ty)})\">${esc(text)}</text>"
                    )
                } else if (abs(node.x - node.parentX) > 2) {
                    val tx = (node.parentX + node.x) / 2
                    lengthLabels.add(
                        "<text class=\"blen$dim\" x=\"${num(tx)}\" y=\"${num(node.parentY - 3)}\" text-anchor=\"middle\">${esc(text)}</text>"
                    )
                }
            }
        }

        if (circular && node.children.size >= 2 && node.radius > 0) {
            val (x0, y0) = point(layout.centerX, layout.centerY, node.radius, node.startAngle)
            val (x1, y1) = point(layout.centerX, layout.centerY, node.radius, node.endAngle)
            val large = if (node.endAngle - node.startAngle > Math.PI) 1 else 0
            arcPaths.add(
                "<path class=\"branch-arc$dim\" d=\"M ${num(x0)} ${num(y0)} A ${num(node.radius)} ${num(node.radius)} 0 $large 1 ${num(x1)} ${num(y1)}\" fill=\"none\" stroke=\"$strokeColor\" stroke-width=\"${num(width)}\" stroke-linecap=\"butt\"/>"
            )
        }

        if (node.isCollapsed) {
            val fill = node.color ?: theme.accent
            if (circular) {
                val delta = min(0.16, max(0.05, (Math.PI * 2) / max(4.0, layout.terminals.size.toDouble()) / 2))
                val startRadius = max(3.0, node.parentRadius)
                val (x0, y0) = point(layout.centerX, layout.centerY, startRadius, node.angle - delta)
                val (x1, y1) = point(layout.centerX, layout.centerY, startRadius, node.angle + delta)
                wedges.add(
                    "<path class=\"collapsed-wedge$dim\" data-node-id=\"${esc(node.id)}\" data-toggle-id=\"${esc(node.id)}\" d=\"M ${num(x0)} ${num(y0)} A ${num(startRadius)} ${num(startRadius)} 0 0 1 ${num(x1)} ${num(y1)} L ${num(node.x)} ${num(node.y)} Z\" fill=\"$fill\" fill-opacity=\"0.72\" stroke=\"$fill\" stroke-width=\"1\"><title>${esc(state.expandLabel)}</title></path>"
                )
            } else {
                val half = max(4.0, fs * 0.42)
                val startX = min(node.parentX, node.x - 5)
                wedges.add(
                    "<path class=\"collapsed-wedge$dim\" data-node-id=\"${esc(node.id)}\" data-toggle-id=\"${esc(node.id)}\" d=\"M ${num(startX)} ${num(node.y - half)} L ${num(startX)} ${num(node.y + half)} L ${num(node.x)} ${num(node.y)} Z\" fill=\"$fill\" fill-opacity=\"0.72\" stroke=\"$fill\" stroke-width=\"1\"><title>${esc(state.expandLabel)}</title></path>"
                )
            }
        }

        if (settings.showNodeDots) {
            val radius = if (node.isLeaf || node.isCollapsed) {
                max(1.8, settings.branchWidth * 0.8 + 1.1)
            } else {
                max(2.6, settings.branchWidth + 1.6)
            }
            val fill = if (onPath) theme.highlight else node.color ?: theme.foreground
            val toggle = if (node.children.isNotEmpty()) " node-toggle\" data-toggle-id=\"${esc(node.id)}" else ""
            val tooltip = if (node.children.isNotEmpty()) {
                if (node.isCollapsed) state.expandLabel else state.collapseLabel
            } else {
                node.name.ifEmpty { state.unnamedLabel }
            }
            dots.add(
                "<circle class=\"node-dot$toggle$dim\" data-node-id=\"${esc(node.id)}\" cx=\"${num(node.x)}\" cy=\"${num(node.y)}\" r=\"${num(radius)}\" fill=\"$fill\" stroke=\"${theme.background}\" stroke-width=\"1\"><title>${esc(tooltip)}</title></circle>"
            )
        }

        // 文本标签：末端名与命名的内部节点
        val showName = node.isLeaf || node.isCollapsed || node.name.isNotEmpty()
        if (showName) {
            var display = node.name.ifEmpty { if (node.isCollapsed) "" else state.unnamedLabel }
            if (node.isCollapsed) {
                val badge = "[${node.leafCount}]"
                display = if (display.isNotEmpty()) "$display $badge" else badge
            }
            val match = if (node.id in state.searchMatches) " match" else ""
            val sel = if (selectedActive && node.id in state.pathIds) " sel" else ""
            val fillColor = when {
                selectedActive && node.id in state.pathIds -> theme.highlight
                onHoverPath -> theme.accent
                else -> node.color ?: theme.foreground
            }
            val className = if (node.isLeaf || node.isCollapsed) "leaf-label" else "int-label"
            var text = "<tspan class=\"lbl\">${esc(display)}</tspan>"
            if (settings.showBranchLengths && node.length != null && (node.isLeaf || node.isCollapsed)) {
                text += "<tspan class=\"blen-inline\" dx=\"5\">${esc(formatNumber(node.length))}</tspan>"
            }
            if (circular) {
                val (tx, ty) = point(layout.centerX, layout.centerY, node.radius + 6, node.angle)
                val (degrees, anchor) = flipDegrees(node.angle)
                labels.add(
                    "<text class=\"$className$dim$match$sel\" data-node-id=\"${esc(node.id)}\" x=\"${num(tx)}\" y=\"${num(ty)}\" fill=\"$fillColor\" font-size=\"${num(fs)}\" text-anchor=\"$anchor\" dominant-baseline=\"middle\" transform=\"rotate(${num(degrees)} ${num(tx)} ${num(ty)})\">$text</text>"
                )
            } else {
                val tx = node.x + 6
                val ty = node.y
                val rotation = if (settings.labelAngle != 0.0) {
                    " transform=\"rotate(${num(-settings.labelAngle)} ${num(tx)} ${num(ty)})\""
                } else ""
                labels.add(
                    "<text class=\"$className$dim$match$sel\" data-node-id=\"${esc(node.id)}\" x=\"${num(tx)}\" y=\"${num(ty)}\" fill=\"$fillColor\" font-size=\"${num(fs)}\" text-anchor=\"start\" dominant-baseline=\"middle\"$rotation>$text</text>"
                )
            }
        }

        if (settings.showSupport && node.support != null) {
            val support = normalizedSupport(node.support)
            if (support != null && support >= settings.supportThreshold) {
                val text = formatNumber(node.support)
                if (circular) {
                    val (tx, ty) = point(layout.centerX, layout.centerY, max(2.0, node.radius - 7), node.angle)
                    val (degrees, anchor) = flipDegrees(node.angle)
                    supports.add(
                        "<text class=\"support$dim\" x=\"${num(tx)}\" y=\"${num(ty)}\" font-size=\"${num(fs * 0.78)}\" text-anchor=\"$anchor\" dominant-baseline=\"middle\" transform=\"rotate(${num(degrees)} ${num(tx)} ${num(ty)})\">${esc(text)}</text>"
                    )
                } else {
                    supports.add(
                        "<text class=\"support$dim\" x=\"${num(node.x + 5)}\" y=\"${num(node.y - 6)}\" font-size=\"${num(fs * 0.78)}\" text-anchor=\"start\">${esc(text)}</text>"
                    )
                }
            }
        }
    }

    val titleLines = layout.titleLines.mapIndexed { i, line ->
        "<text class=\"fig-title\" x=\"${num(layout.width / 2)}\" y=\"${num(layout.titleY + i * layout.titleFont * 1.35)}\" font-size=\"${num(layout.titleFont)}\" text-anchor=\"middle\">${esc(line)}</text>"
    }.joinToString("")
    val captionLines = layout.captionLines.mapIndexed { i, line ->
        "<text class=\"fig-caption\" x=\"${num(layout.width / 2)}\" y=\"${num(layout.captionY + i * layout.captionFont * 1.35)}\" font-size=\"${num(layout.captionFont)}\" text-anchor=\"middle\">${esc(line)}</text>"
    }.joinToString("")

    var scaleBar = ""
    if (settings.showScaleBar && layout.scaleBar != null) {
        val bar = layout.scaleBar
        val x1 = bar.x
        val x2 = bar.x + bar.lengthPx
        val y = bar.y
        scaleBar = "<g class=\"scale-bar\">" +
            "<line class=\"scale-line\" x1=\"${num(x1)}\" y1=\"${num(y)}\" x2=\"${num(x2)}\" y2=\"${num(y)}\"/>" +
            "<line class=\"scale-line\" x1=\"${num(x1)}\" y1=\"${num(y - 4)}\" x2=\"${num(x1)}\" y2=\"${num(y + 4)}\"/>" +
            "<line class=\"scale-line\" x1=\"${num(x2)}\" y1=\"${num(y - 4)}\" x2=\"${num(x2)}\" y2=\"${num(y + 4)}\"/>" +
            "<text class=\"scale-label\" x=\"${num(x1)}\" y=\"${num(y - 7)}\" font-size=\"${num(max(8.0, fs * 0.78))}\">${esc(formatNumber(bar.unit))} ${esc(state.unitLabel)}</text>" +
            "</g>"
    }

    val css = """
.tree-bg{fill:${theme.background}}
.branch{pointer-events:none}
.branch-arc{pointer-events:none}
.branch-hit{cursor:pointer}
.node-dot{pointer-events:all}
.node-toggle{cursor:pointer}
.collapsed-wedge{cursor:pointer}
.leaf-label,.int-label{font-family:"SFMono-Regular","JetBrains Mono","Fira Code","Menlo","Consolas","Courier New",monospace;pointer-events:auto;cursor:pointer}
.int-label{font-style:italic;opacity:.92}
.support{font-family:"SFMono-Regular","JetBrains Mono","Fira Code","Menlo","Consolas","Courier New",monospace;font-weight:700;fill:${theme.support};pointer-events:none}
.blen,.blen-inline{font-family:"SFMono-Regular","JetBrains Mono","Fira Code","Menlo","Consolas","Courier New",monospace;fill:${theme.dim};opacity:.9;pointer-events:none}
.blen-inline{font-size:.82em}
.dim{opacity:.26}
.match{text-decoration:underline;text-decoration-thickness:1.5px}
.sel{font-weight:700}
.fig-title{font-family:"SFMono-Regular","JetBrains Mono","Fira Code","Menlo","Consolas","Courier New",monospace;font-weight:700;fill:${theme.title}}
.fig-caption{font-family:"SFMono-Regular","JetBrains Mono","Fira Code","Menlo","Consolas","Courier New",monospace;fill:${theme.caption}}
.scale-line{stroke:${theme.foreground};stroke-width:1.2;stroke-linecap:square}
.scale-label{font-family:"SFMono-Regular","JetBrains Mono","Fira Code","Menlo","Consolas","Courier New",monospace;fill:${theme.dim}}
"""

    val titleText = if (title.isNotEmpty()) title.replace(Regex("\\s+"), " ").trim() else state.unnamedLabel

    return "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"${num(layout.width)}\" height=\"${num(layout.height)}\" viewBox=\"0 0 ${num(layout.width)} ${num(layout.height)}\" class=\"tree-svg\" role=\"img\">" +
        "<title>${esc(titleText)}</title>" +
        "<style>$css</style>" +
        "<rect class=\"tree-bg\" x=\"0\" y=\"0\" width=\"${num(layout.width)}\" height=\"${num(layout.height)}\"/>" +
        "<g class=\"tree-viewport\" transform=\"translate(0, 0) scale(1)\">" +
        titleLines + captionLines +
        "<g class=\"links\">${arcPaths.joinToString("")}${edgePaths.joinToString("")}</g>" +
        "<g class=\"wedges\">${wedges.joinToString("")}</g>" +
        "<g class=\"dots\">${dots.joinToString("")}</g>" +
        "<g class=\"labels\">${labels.joinToString("")}</g>" +
        "<g class=\"supports\">${supports.joinToString("")}</g>" +
        "<g class=\"lengths\">${lengthLabels.joinToString("")}</g>" +
        scaleBar +
        "</g>" +
        "</svg>"
}

// =============================== Android 原生 Canvas 渲染（预览 + PNG 导出共用） ===

private fun colorInt(color: String): Int =
    if (color.startsWith("#")) AColor.parseColor(color)
    else AColor.parseColor(NAMED_COLOR_HEX[color] ?: "#d8e1f2")

fun withAlpha(color: Int, factor: Double): Int =
    (AColor.alpha(color) * factor).toInt() shl 24 or (color and 0x00FFFFFF)

/** 与 renderTreeSvg 同一几何/配色/字体的原生绘制；导出 PNG 时 canvas 已按 dpi 缩放。 */
fun drawTreeToCanvas(
    canvas: Canvas,
    layout: TreeLayout,
    settings: TreeSettings,
    state: RenderState,
    title: String,
    caption: String
) {
    val theme = THEME_COLORS[settings.theme] ?: THEME_COLORS[TreeTheme.DARK]!!
    val fs = settings.fontSize.toFloat()
    val circular = layout.mode == LayoutMode.CIRCULAR
    val selectedActive = state.selectedId != null
    val fgInt = colorInt(theme.foreground)
    val bgInt = colorInt(theme.background)
    val dimInt = colorInt(theme.dim)

    fun isDimmed(node: LayoutNode): Boolean {
        if (state.focusGroup != null && node.group != state.focusGroup) return true
        if (selectedActive && node.id !in state.pathIds) return true
        return false
    }

    val mono = Typeface.MONOSPACE
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = mono
        textAlign = Paint.Align.LEFT
    }

    // 背景
    paint.style = Paint.Style.FILL
    paint.color = bgInt
    canvas.drawRect(0f, 0f, layout.width.toFloat(), layout.height.toFloat(), paint)

    // 标题/图注
    paint.textSize = layout.titleFont.toFloat()
    paint.isFakeBoldText = true
    paint.color = colorInt(theme.title)
    layout.titleLines.forEachIndexed { i, line ->
        canvas.drawText(line, 0, line.length,
            (layout.width / 2 - paint.measureText(line) / 2).toFloat(),
            (layout.titleY + i * layout.titleFont * 1.35).toFloat(), paint)
    }
    paint.textSize = layout.captionFont.toFloat()
    paint.isFakeBoldText = false
    paint.color = colorInt(theme.caption)
    layout.captionLines.forEachIndexed { i, line ->
        canvas.drawText(line, 0, line.length,
            (layout.width / 2 - paint.measureText(line) / 2).toFloat(),
            (layout.captionY + i * layout.captionFont * 1.35).toFloat(), paint)
    }

    fun centeredTextY(p: Paint): Float = -(p.ascent() + p.descent()) / 2f

    // links：环弧 + 分支
    for (node in layout.nodes) {
        val dimmed = isDimmed(node)
        val onPath = selectedActive && node.id in state.pathIds
        val onHoverPath = node.id in state.hoverPathIds
        val hovered = state.hoveredId == node.id
        val strokeColor = when {
            onPath -> theme.highlight
            onHoverPath -> theme.accent
            else -> node.color ?: theme.link
        }
        val width = settings.branchWidth +
            (if (onPath) 1.1 else if (onHoverPath) 0.6 else 0.0) +
            (if (hovered) 0.7 else 0.0)

        if (circular && node.children.size >= 2 && node.radius > 0) {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = width.toFloat()
            paint.strokeCap = Paint.Cap.BUTT
            paint.color = withAlpha(colorInt(strokeColor), if (dimmed) 0.26 else 1.0)
            val oval = android.graphics.RectF(
                (layout.centerX - node.radius).toFloat(), (layout.centerY - node.radius).toFloat(),
                (layout.centerX + node.radius).toFloat(), (layout.centerY + node.radius).toFloat()
            )
            val startDeg = Math.toDegrees(node.startAngle)
            val sweepDeg = Math.toDegrees(node.endAngle - node.startAngle)
            if (sweepDeg >= 360) {
                canvas.drawOval(oval, paint)
            } else {
                canvas.drawArc(oval, startDeg.toFloat(), sweepDeg.toFloat(), false, paint)
            }
        }

        if (node.parentId != null) {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = width.toFloat()
            paint.strokeCap = Paint.Cap.SQUARE
            paint.strokeJoin = Paint.Join.MITER
            paint.color = withAlpha(colorInt(strokeColor), if (dimmed) 0.26 else 1.0)
            val path = android.graphics.Path()
            if (circular) {
                val (sx, sy) = point(layout.centerX, layout.centerY, node.parentRadius, node.angle)
                path.moveTo(sx.toFloat(), sy.toFloat())
                path.lineTo(node.x.toFloat(), node.y.toFloat())
            } else {
                path.moveTo(node.parentX.toFloat(), node.parentY.toFloat())
                path.lineTo(node.x.toFloat(), node.parentY.toFloat())
                path.lineTo(node.x.toFloat(), node.y.toFloat())
            }
            canvas.drawPath(path, paint)

            if (settings.showBranchLengths && node.length != null && !node.isLeaf && !node.isCollapsed) {
                val text = formatNumber(node.length)
                paint.style = Paint.Style.FILL
                paint.textSize = fs
                paint.color = withAlpha(dimInt, if (dimmed) 0.26 else 0.9)
                if (circular) {
                    val middle = (node.parentRadius + node.radius) / 2
                    val (tx, ty) = point(layout.centerX, layout.centerY, middle, node.angle)
                    var degrees = node.angle * 180 / Math.PI
                    var anchorRight = false
                    if (cos(node.angle) < 0) {
                        degrees += 180
                        anchorRight = true
                    }
                    canvas.save()
                    canvas.translate(tx.toFloat(), ty.toFloat())
                    canvas.rotate(degrees.toFloat())
                    val w = paint.measureText(text)
                    canvas.drawText(text, if (anchorRight) -w else 0f, centeredTextY(paint), paint)
                    canvas.restore()
                } else if (abs(node.x - node.parentX) > 2) {
                    val tx = (node.parentX + node.x) / 2
                    val w = paint.measureText(text)
                    canvas.drawText(text,
                        (tx - w / 2).toFloat(),
                        (node.parentY - 3).toFloat(),
                        paint)
                }
            }
        }
    }

    // wedges：折叠三角/弧楔
    for (node in layout.nodes) {
        if (!node.isCollapsed) continue
        val dimmed = isDimmed(node)
        val fill = node.color ?: theme.accent
        val c = withAlpha(colorInt(fill), if (dimmed) 0.26 else 1.0)
        paint.style = Paint.Style.FILL
        paint.color = c
        val path = android.graphics.Path()
        if (circular) {
            val delta = min(0.16, max(0.05, (Math.PI * 2) / max(4.0, layout.terminals.size.toDouble()) / 2))
            val startRadius = max(3.0, node.parentRadius)
            val (x0, y0) = point(layout.centerX, layout.centerY, startRadius, node.angle - delta)
            val (x1, y1) = point(layout.centerX, layout.centerY, startRadius, node.angle + delta)
            val oval = android.graphics.RectF(
                (layout.centerX - startRadius).toFloat(), (layout.centerY - startRadius).toFloat(),
                (layout.centerX + startRadius).toFloat(), (layout.centerY + startRadius).toFloat()
            )
            val sweep = 2 * delta
            path.moveTo(x0.toFloat(), y0.toFloat())
            path.arcTo(oval, Math.toDegrees(node.angle - delta).toFloat(),
                Math.toDegrees(sweep).toFloat())
            path.lineTo(node.x.toFloat(), node.y.toFloat())
            path.close()
            canvas.drawPath(path, paint)
        } else {
            val half = max(4.0, settings.fontSize * 0.42)
            val startX = min(node.parentX, node.x - 5)
            path.moveTo(startX.toFloat(), (node.y - half).toFloat())
            path.lineTo(startX.toFloat(), (node.y + half).toFloat())
            path.lineTo(node.x.toFloat(), node.y.toFloat())
            path.close()
            canvas.drawPath(path, paint)
        }
    }

    // dots：节点圆点
    if (settings.showNodeDots) {
        for (node in layout.nodes) {
            val dimmed = isDimmed(node)
            val onPath = selectedActive && node.id in state.pathIds
            val radius = if (node.isLeaf || node.isCollapsed) {
                max(1.8, settings.branchWidth * 0.8 + 1.1)
            } else {
                max(2.6, settings.branchWidth + 1.6)
            }
            val fill = if (onPath) theme.highlight else node.color ?: theme.foreground
            paint.style = Paint.Style.FILL
            paint.color = withAlpha(colorInt(fill), if (dimmed) 0.26 else 1.0)
            canvas.drawCircle(node.x.toFloat(), node.y.toFloat(), radius.toFloat(), paint)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 1f
            paint.color = bgInt
            canvas.drawCircle(node.x.toFloat(), node.y.toFloat(), radius.toFloat(), paint)
        }
    }

    // labels：叶名/折叠徽标/内部命名节点（等宽字体；内部斜体）
    for (node in layout.nodes) {
        val showName = node.isLeaf || node.isCollapsed || node.name.isNotEmpty()
        if (!showName) continue
        val dimmed = isDimmed(node)
        val onPath = selectedActive && node.id in state.pathIds
        val onHoverPath = node.id in state.hoverPathIds
        var display = node.name.ifEmpty { if (node.isCollapsed) "" else state.unnamedLabel }
        if (node.isCollapsed) {
            val badge = "[${node.leafCount}]"
            display = if (display.isNotEmpty()) "$display $badge" else badge
        }
        if (display.isEmpty()) continue
        val fillColor = when {
            onPath -> theme.highlight
            onHoverPath -> theme.accent
            else -> node.color ?: theme.foreground
        }
        var alpha = if (dimmed) 0.26 else 1.0
        if (!node.isLeaf && !node.isCollapsed) alpha *= 0.92
        paint.style = Paint.Style.FILL
        paint.textSize = fs
        paint.isFakeBoldText = false
        paint.isUnderlineText = node.id in state.searchMatches
        if (onPath) paint.isFakeBoldText = true
        paint.textSkewX = if (node.isLeaf || node.isCollapsed) 0f else -0.25f
        paint.color = withAlpha(colorInt(fillColor), alpha)

        var text = display
        if (settings.showBranchLengths && node.length != null && (node.isLeaf || node.isCollapsed)) {
            // blen-inline 追加（0.82em、dx=5）
            val blen = formatNumber(node.length)
            if (circular) {
                val (tx, ty) = point(layout.centerX, layout.centerY, node.radius + 6, node.angle)
                var degrees = node.angle * 180 / Math.PI
                var anchorRight = false
                if (cos(node.angle) < 0) {
                    degrees += 180
                    anchorRight = true
                }
                canvas.save()
                canvas.translate(tx.toFloat(), ty.toFloat())
                canvas.rotate(degrees.toFloat())
                val w = paint.measureText(text)
                if (anchorRight) {
                    val w2 = paint.measureText(blen)
                    paint.textSize = fs * 0.82f
                    canvas.drawText(blen, -w - 5f - w2, centeredTextY(paint), paint)
                    paint.textSize = fs
                    canvas.drawText(text, -w, centeredTextY(paint), paint)
                } else {
                    canvas.drawText(text, 0f, centeredTextY(paint), paint)
                    val off = 5 + paint.measureText(text)
                    paint.textSize = fs * 0.82f
                    paint.color = withAlpha(dimInt, if (dimmed) 0.26 else 0.9)
                    canvas.drawText(blen, off, centeredTextY(paint), paint)
                }
                canvas.restore()
            } else {
                var tx = node.x + 6
                val ty = node.y
                canvas.save()
                if (settings.labelAngle != 0.0) {
                    canvas.rotate(-settings.labelAngle.toFloat(), tx.toFloat(), ty.toFloat())
                }
                canvas.drawText(text, tx.toFloat(), ty.toFloat() + centeredTextY(paint), paint)
                tx += 5 + paint.measureText(text)
                paint.textSize = fs * 0.82f
                paint.color = withAlpha(dimInt, if (dimmed) 0.26 else 0.9)
                canvas.drawText(blen, tx.toFloat(), ty.toFloat() + centeredTextY(paint), paint)
                canvas.restore()
            }
        } else if (circular) {
            val (tx, ty) = point(layout.centerX, layout.centerY, node.radius + 6, node.angle)
            var degrees = node.angle * 180 / Math.PI
            var anchorRight = false
            if (cos(node.angle) < 0) {
                degrees += 180
                anchorRight = true
            }
            canvas.save()
            canvas.translate(tx.toFloat(), ty.toFloat())
            canvas.rotate(degrees.toFloat())
            val w = paint.measureText(text)
            canvas.drawText(text, if (anchorRight) -w else 0f, centeredTextY(paint), paint)
            canvas.restore()
        } else {
            val tx = node.x + 6
            val ty = node.y
            canvas.save()
            if (settings.labelAngle != 0.0) {
                canvas.rotate(-settings.labelAngle.toFloat(), tx.toFloat(), ty.toFloat())
            }
            canvas.drawText(text, tx.toFloat(), ty.toFloat() + centeredTextY(paint), paint)
            canvas.restore()
        }
        paint.isUnderlineText = false
        paint.textSkewX = 0f
    }

    // supports：内部支持度（加粗、0.78em、阈值过滤）
    if (settings.showSupport) {
        for (node in layout.nodes) {
            if (node.support == null) continue
            val support = normalizedSupport(node.support) ?: continue
            if (support < settings.supportThreshold) continue
            val dimmed = isDimmed(node)
            val text = formatNumber(node.support)
            paint.style = Paint.Style.FILL
            paint.textSize = fs * 0.78f
            paint.isFakeBoldText = true
            paint.color = withAlpha(colorInt(theme.support), if (dimmed) 0.26 else 1.0)
            if (circular) {
                val (tx, ty) = point(layout.centerX, layout.centerY, max(2.0, node.radius - 7), node.angle)
                var degrees = node.angle * 180 / Math.PI
                var anchorRight = false
                if (cos(node.angle) < 0) {
                    degrees += 180
                    anchorRight = true
                }
                canvas.save()
                canvas.translate(tx.toFloat(), ty.toFloat())
                canvas.rotate(degrees.toFloat())
                val w = paint.measureText(text)
                canvas.drawText(text, if (anchorRight) -w else 0f, centeredTextY(paint), paint)
                canvas.restore()
            } else {
                canvas.drawText(text, (node.x + 5).toFloat(), (node.y - 6).toFloat(), paint)
            }
            paint.isFakeBoldText = false
        }
    }

    // scale bar
    if (settings.showScaleBar && layout.scaleBar != null) {
        val bar = layout.scaleBar
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.2f
        paint.strokeCap = Paint.Cap.SQUARE
        paint.color = fgInt
        val y = bar.y.toFloat()
        canvas.drawLine(bar.x.toFloat(), y, (bar.x + bar.lengthPx).toFloat(), y, paint)
        canvas.drawLine(bar.x.toFloat(), y - 4, bar.x.toFloat(), y + 4, paint)
        canvas.drawLine((bar.x + bar.lengthPx).toFloat(), y - 4, (bar.x + bar.lengthPx).toFloat(), y + 4, paint)
        paint.style = Paint.Style.FILL
        paint.textSize = max(8.0, fs * 0.78).toFloat()
        paint.color = dimInt
        canvas.drawText("${formatNumber(bar.unit)} ${state.unitLabel}", bar.x.toFloat(), (bar.y - 7).toFloat(), paint)
    }
}

// ========================================================= projectDoc.ts ===

data class TreeCountStats(
    val nodes: Int,
    val leaves: Int,
    val internal: Int,
    val withSupport: Int,
    val shownSupport: Int,
    val filteredSupport: Int,
    val threshold: Double
)

fun treeStats(project: TreeProject): TreeCountStats {
    val root = serializedToTree(project.tree)
    val summary = supportSummary(root, project.settings.supportThreshold)
    val leaves = collectLeafNodes(root).size
    return TreeCountStats(
        nodes = nodeCount(root),
        leaves = leaves,
        internal = summary.internal,
        withSupport = summary.withSupport,
        shownSupport = summary.shown,
        filteredSupport = summary.filtered,
        threshold = summary.threshold
    )
}

fun treeDocName(project: TreeProject): String {
    val title = project.title.trim()
    if (title.isNotEmpty()) return title
    val source = project.sourceName?.trim()
    if (!source.isNullOrEmpty()) return source
    return "Phylogenetic tree"
}

fun treeSummary(project: TreeProject): String {
    val stats = treeStats(project)
    val collapsed = project.collapsedIds.size
    val parts = mutableListOf(
        "${stats.leaves} leaves",
        "${stats.nodes} nodes",
        "${stats.internal} internal",
        "${stats.withSupport} with support",
        "${stats.shownSupport} shown at threshold ${jsNum(stats.threshold)}",
        project.settings.mode.key
    )
    if (collapsed > 0) parts.add("$collapsed collapsed")
    return parts.joinToString("; ")
}

fun treeParameters(project: TreeProject): JsonObj {
    val stats = treeStats(project)
    val root = serializedToTree(project.tree)
    val support = supportSummary(root, project.settings.supportThreshold)
    return JsonObj()
        .put("settings", settingsToJson(project.settings))
        .put("rooted", project.rooted)
        .put("sourceName", project.sourceName)
        .put("counts", JsonObj()
            .put("nodes", stats.nodes)
            .put("leaves", stats.leaves)
            .put("internal", stats.internal)
            .put("withSupport", stats.withSupport)
            .put("shownSupport", stats.shownSupport)
            .put("filteredSupport", stats.filteredSupport)
            .put("threshold", stats.threshold))
        .put("support", JsonObj()
            .put("min", support.min)
            .put("max", support.max)
            .put("mean", support.mean)
            .put("threshold", support.threshold)
            .put("bins", JsonArr(support.bins.map { b ->
                JsonObj().put("index", b.index).put("from", b.from).put("to", b.to)
                    .put("shown", b.shown).put("filtered", b.filtered).put("total", b.total)
            })))
        .put("collapsedIds", JsonArr(project.collapsedIds))
        .put("metadata", JsonObj()
            .put("entries", project.metadata.entries.size)
            .put("paletteId", project.metadata.paletteId.key))
        .put("title", project.title)
        .put("caption", project.caption)
}

fun treeMethods(project: TreeProject): String {
    val stats = treeStats(project)
    val lines = mutableListOf(
        "A Newick tree with ${stats.leaves} leaves and ${stats.nodes} nodes was parsed in the SPICE tree tool and drawn as a ${project.settings.mode.key} layout.",
        "Branch lengths were used when present; trees without usable lengths fall back to a cladogram geometry."
    )
    if (stats.withSupport > 0) {
        lines.add(
            "Internal support values (${stats.withSupport} nodes) were normalized to percent space and labels were displayed at a threshold of ${jsNum(stats.threshold)}%, corresponding to ${stats.shownSupport} shown nodes and ${stats.filteredSupport} filtered nodes."
        )
    }
    if (project.metadata.entries.isNotEmpty()) {
        lines.add(
            "Leaf groups were colored from an imported metadata table with ${project.metadata.entries.size} rows and the ${project.metadata.paletteId.key} palette."
        )
    }
    lines.add(
        "The figure was exported as an SVG with live text elements and as a 300 dpi PNG rasterized from that same SVG. Edited and original Newick files, the full project JSON and processing parameters accompany the figure in this bundle."
    )
    return lines.joinToString(" ")
}

// ======================================================== repro/bundle.ts ===

data class ReproFile(val name: String, val mime: String, val data: ByteArray)

private fun sha256Hex(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

private fun safeStem(text: String, fallback: String): String {
    val stem = text.trim().lowercase()
        .replace(Regex("[^a-z0-9]+"), "-")
        .replace(Regex("^-+|-+"), "")
        .take(72)
    return stem.ifEmpty { fallback }
}

private fun uniqueName(name: String, used: MutableSet<String>): String {
    val clean = name.replace("\\", "/").replace(Regex("^/+"), "").ifEmpty { "file" }
    if (clean !in used) {
        used.add(clean)
        return clean
    }
    val dot = clean.lastIndexOf('.')
    val base = if (dot > 0) clean.substring(0, dot) else clean
    val ext = if (dot > 0) clean.substring(dot) else ""
    var i = 2
    while ("$base-$i$ext" in used) i++
    val next = "$base-$i$ext"
    used.add(next)
    return next
}

/** SPICE_REPRO v1 可复现包（对应 main repro/bundle.ts buildReproBundle；author/orcid 无本地来源，缺省）。 */
fun buildReproBundle(
    tool: String,
    title: String,
    files: List<ReproFile>,
    parameters: JsonObj,
    methods: String,
    projectJson: String,
    softwareVersion: String
): Pair<ByteArray, String> {
    val generatedAt = isoNow()
    val used = mutableSetOf<String>()
    val out = mutableListOf<ReproFile>()

    for (file in files) out.add(file.copy(name = uniqueName(file.name, used)))
    out.add(ReproFile(uniqueName("project.json", used), "application/json", projectJson.toByteArray()))
    out.add(ReproFile(uniqueName("parameters.json", used), "application/json",
        JsonObj()
            .put("tool", tool)
            .put("title", title)
            .put("generatedAt", generatedAt)
            .put("parameters", parameters)
            .toString().toByteArray()))
    val methodsDoc = buildList {
        add("# $title")
        add("")
        add("Tool: $tool")
        add("Generated: $generatedAt")
        add("Software: SPICE GUI $softwareVersion")
        add("")
        add("## Methods")
        add("")
        add(methods.trim())
        add("")
        add("## Parameters")
        add("")
        add("```json")
        add(parameters.toString())
        add("```")
        add("")
    }.joinToString("\n")
    out.add(ReproFile(uniqueName("methods.md", used), "text/markdown", methodsDoc.toByteArray()))
    out.add(ReproFile(uniqueName("software.json", used), "application/json",
        JsonObj()
            .put("name", "SPICE GUI")
            .put("version", softwareVersion)
            .put("platform", "Android native (Kotlin/Compose)")
            .put("generatedAt", generatedAt)
            .toString().toByteArray()))

    val manifestFiles = mutableListOf<JSONObject>()
    val entries = mutableListOf<Pair<String, ByteArray>>()
    for (file in out) {
        entries.add(file.name to file.data)
        manifestFiles.add(JSONObject()
            .put("name", file.name)
            .put("mime", file.mime)
            .put("bytes", file.data.size)
            .put("sha256", sha256Hex(file.data)))
    }

    val readme = buildList {
        add("# $title — reproducibility bundle")
        add("")
        add("Generated by SPICE GUI $softwareVersion on $generatedAt.")
        add("")
        add("Files:")
        out.forEach { f -> add("- `${f.name}`") }
        add("")
        add("The bundle is self-contained: `project.json` re-opens in the same tool, `parameters.json` records every processing setting, and `methods.md` carries a draft methods paragraph. `manifest.sha256.json` lists the SHA-256 digest of every other file.")
        add("")
        add("Verify integrity with:")
        add("")
        add("```bash")
        add("shasum -a 256 *")
        add("```")
        add("")
    }.joinToString("\n")
    val readmeName = uniqueName("README.md", used)
    entries.add(readmeName to readme.toByteArray())
    manifestFiles.add(JSONObject()
        .put("name", readmeName)
        .put("mime", "text/markdown")
        .put("bytes", readme.toByteArray().size)
        .put("sha256", sha256Hex(readme.toByteArray())))

    val manifest = JSONObject()
        .put("format", "SPICE_REPRO")
        .put("version", 1)
        .put("tool", tool)
        .put("title", title)
        .put("generatedAt", generatedAt)
        .put("software", JSONObject().put("name", "SPICE GUI").put("version", softwareVersion))
        .put("files", JSONArray(manifestFiles))
    entries.add("manifest.sha256.json" to manifest.toString().toByteArray())

    val zip = zipStore(entries)
    return zip to "${safeStem(title, tool)}-repro.zip"
}

/** stored（不压缩）ZIP，对应 repro/zip.ts zipStore。 */
private fun zipStore(entries: List<Pair<String, ByteArray>>): ByteArray {
    val bos = java.io.ByteArrayOutputStream()
    ZipOutputStream(bos).use { zos ->
        zos.setLevel(java.util.zip.Deflater.NO_COMPRESSION)
        for ((name, data) in entries) {
            val ze = ZipEntry(name)
            ze.method = ZipEntry.STORED
            ze.size = data.size.toLong()
            ze.crc = CRC32().apply { update(data) }.value
            zos.putNextEntry(ze)
            zos.write(data)
            zos.closeEntry()
        }
    }
    return bos.toByteArray()
}

// ================================ export.ts svgToPng 的 Android 等价实现 ===

const val MAX_PIXELS_PER_SIDE = 14000
const val MAX_TOTAL_PIXELS = 48_000_000.0

data class PngResult(val bytes: ByteArray, val width: Int, val height: Int, val dpi: Int, val reduced: Boolean)

/** 与 svgToPng 相同的缩放钳制（96dpi 基准），但直接原生绘制而非 SVG 栅格化（README 已披露）。 */
fun renderTreePng(
    layout: TreeLayout,
    settings: TreeSettings,
    state: RenderState,
    title: String,
    caption: String,
    requestedDpi: Int
): PngResult {
    val requestedScale = requestedDpi / 96.0
    var scale = requestedScale
    if (layout.width * scale > MAX_PIXELS_PER_SIDE) scale = MAX_PIXELS_PER_SIDE / layout.width
    if (layout.height * scale > MAX_PIXELS_PER_SIDE) scale = MAX_PIXELS_PER_SIDE / layout.height
    val totalPixels = layout.width * scale * layout.height * scale
    if (totalPixels > MAX_TOTAL_PIXELS) {
        scale *= kotlin.math.sqrt(MAX_TOTAL_PIXELS / totalPixels)
    }
    val outputWidth = max(1.0, kotlin.math.round(layout.width * scale)).toInt()
    val outputHeight = max(1.0, kotlin.math.round(layout.height * scale)).toInt()

    val bmp = android.graphics.Bitmap.createBitmap(outputWidth, outputHeight, android.graphics.Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bmp)
    canvas.scale(scale.toFloat(), scale.toFloat())
    drawTreeToCanvas(canvas, layout, settings, state, title, caption)

    val bos = java.io.ByteArrayOutputStream()
    bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, bos)
    val dpi = (scale * 96).roundToInt()
    return PngResult(withDpi(bos.toByteArray(), dpi), outputWidth, outputHeight, dpi, scale < requestedScale - 0.0001)
}

private val PNG_CRC_TABLE = IntArray(256).apply {
    for (n in 0 until 256) {
        var c = n
        for (k in 0 until 8) c = if (c and 1 != 0) 0xedb88320.toInt() xor (c ushr 1) else c ushr 1
        this[n] = c
    }
}

private fun pngCrc32(bytes: ByteArray, from: Int = 0, to: Int = bytes.size): Int {
    var c = -1
    for (i in from until to) c = PNG_CRC_TABLE[(c xor bytes[i].toInt()) and 0xff] xor (c ushr 8)
    return c xor -1
}

private fun pngChunk(type: String, data: ByteArray): ByteArray {
    val out = ByteArray(12 + data.size)
    val view = java.nio.ByteBuffer.wrap(out).order(java.nio.ByteOrder.BIG_ENDIAN)
    view.putInt(0, data.size)
    for (i in 0 until 4) out[4 + i] = type[i].code.toByte()
    System.arraycopy(data, 0, out, 8, data.size)
    view.putInt(8 + data.size, pngCrc32(out, 4, 8 + data.size))
    return out
}

/** 在 IDAT 前插入 pHYs chunk，使 PNG 携带打印分辨率（与 export.ts withDpi 相同）。 */
fun withDpi(png: ByteArray, dpi: Int): ByteArray {
    if (png.size < 33) return png
    val ppm = Math.round(max(1, dpi) / 0.0254).toInt()
    val phys = ByteArray(9)
    val view = java.nio.ByteBuffer.wrap(phys).order(java.nio.ByteOrder.BIG_ENDIAN)
    view.putInt(0, ppm)
    view.putInt(4, ppm)
    phys[8] = 1
    val chunk = pngChunk("pHYs", phys)
    var offset = 8
    while (offset + 8 <= png.size) {
        val buf = java.nio.ByteBuffer.wrap(png, offset, 4).order(java.nio.ByteOrder.BIG_ENDIAN)
        val length = buf.int
        val type = String(png, offset + 4, 4, Charsets.ISO_8859_1)
        if (type == "IDAT") {
            val out = ByteArray(png.size + chunk.size)
            System.arraycopy(png, 0, out, 0, offset)
            System.arraycopy(chunk, 0, out, offset, chunk.size)
            System.arraycopy(png, offset, out, offset + chunk.size, png.size - offset)
            return out
        }
        offset += 12 + length
    }
    return png
}
