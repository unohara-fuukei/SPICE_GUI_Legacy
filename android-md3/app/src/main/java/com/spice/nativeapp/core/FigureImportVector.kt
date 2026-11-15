package com.spice.nativeapp.core

// FigureImportVector.kt —— main src/lib/tools/figure/importVector.ts 1:1 移植。
//
// main 的导入器运行在浏览器（getComputedStyle/getBBox/getCTM 解析真实样式与几何），
// 但其几何与样式助手在缺少这些 API 的环境（jsdom）下有一套纯数学回退路径
// （attributeBBox + parseTransform 链 + style/attribute 直读）。安卓无浏览器 DOM，
// 本移植固定走该回退路径，与 main 的 jsdom 单测路径行为一致（README 披露）。

import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.ByteArrayInputStream
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.tan
import kotlin.math.PI

object FigureImportVector {

    // ------------------------------------------------------------------ model

    /** main FigureNode 的导入子集（rect/ellipse/line|path/text/image）。 */
    data class VNode(
        val id: String,
        val type: String, // rect | ellipse | path | text | image
        var name: String,
        var x: Double,
        var y: Double,
        var w: Double,
        var h: Double,
        var rotation: Double = 0.0,
        var opacity: Double = 1.0,
        var fill: String = "#e8f1f8",
        var stroke: String = "#1f6f8b",
        var strokeWidth: Double = 1.5,
        var dash: String = "solid",
        var cornerRadius: Double = 0.0,
        var groupId: String? = null,
        // text
        var text: String? = null,
        var fontFamily: String? = null,
        var fontSize: Double? = null,
        var fontWeight: Int? = null,
        var italic: Boolean? = null,
        var align: String? = null,
        var lineHeight: Double? = null,
        var color: String? = null,
        // image
        var dataUrl: String? = null,
        var mime: String? = null,
        var fit: String? = null,
        var padding: Double? = null,
        var thickness: Double? = null,
        var background: String? = null,
        // path / line
        var points: List<VPoint>? = null,
        var closed: Boolean? = null,
        var headStart: Boolean? = null,
        var headEnd: Boolean? = null,
        var headSize: Double? = null,
    )

    data class VPoint(val x: Double, val y: Double)

    data class VGroup(val id: String, val name: String)

    data class VectorImportResult(
        val nodes: List<VNode>,
        val groups: List<VGroup>,
        val width: Double,
        val height: Double,
        val warnings: List<String>,
    )

    private data class Point(val x: Double, val y: Double)

    private data class Matrix(val a: Double, val b: Double, val c: Double, val d: Double, val e: Double, val f: Double)

    private data class BBox(val x: Double, val y: Double, val width: Double, val height: Double)

    private class ConvertContext(val root: Element, val warnings: MutableList<String>) {
        val warned = HashSet<String>()
    }

    private val IDENTITY = Matrix(1.0, 0.0, 0.0, 1.0, 0.0, 0.0)

    private val DRAWABLE = setOf(
        "rect", "circle", "ellipse", "line", "polyline", "polygon", "path", "text", "image",
    )

    // 标签名比较前先 toLowerCase()，SVG camelCase 名小写。
    private val UNSUPPORTED = setOf(
        "use", "foreignobject", "clippath", "mask", "marker", "pattern", "symbol", "filter", "script",
    )

    // 样式经 computed style 消费，故 <style> 不在"跳过"清单里。
    private val IGNORED = setOf("defs", "title", "desc", "metadata", "style")

    private val INHERITED_STYLES = setOf(
        "fill", "stroke", "stroke-width", "stroke-dasharray", "fill-opacity", "stroke-opacity",
        "font-family", "font-size", "font-weight", "font-style", "text-anchor", "color",
        "visibility", "marker-start", "marker-mid", "marker-end",
    )

    // ------------------------------------------------------------------ ids

    fun createFigureId(prefix: String): String =
        "${prefix}_${System.currentTimeMillis().toString(36)}_" +
            List(6) { "0123456789abcdefghijklmnopqrstuvwxyz"[(0..35).random()] }.joinToString("")

    // ------------------------------------------------------------------ matrix

    private fun multiply(a: Matrix, b: Matrix): Matrix = Matrix(
        a.a * b.a + a.c * b.b,
        a.b * b.a + a.d * b.b,
        a.a * b.c + a.c * b.d,
        a.b * b.c + a.d * b.d,
        a.a * b.e + a.c * b.f + a.e,
        a.b * b.e + a.d * b.f + a.f,
    )

    private fun translate(x: Double, y: Double): Matrix = Matrix(1.0, 0.0, 0.0, 1.0, x, y)

    private fun scaleMatrix(x: Double, y: Double): Matrix = Matrix(x, 0.0, 0.0, y, 0.0, 0.0)

    private fun rotateMatrix(degrees: Double): Matrix {
        val rad = degrees * PI / 180.0
        val cos = cos(rad)
        val sin = sin(rad)
        return Matrix(cos, sin, -sin, cos, 0.0, 0.0)
    }

    private fun applyMatrix(m: Matrix, x: Double, y: Double): Point =
        Point(m.a * x + m.c * y + m.e, m.b * x + m.d * y + m.f)

    private val NUMBER_REGEX = Regex("[-+]?(?:\\d*\\.\\d+|\\d+\\.?)(?:[eE][-+]?\\d+)?")

    private fun numbersIn(value: String): List<Double> =
        NUMBER_REGEX.findAll(value).mapNotNull { it.value.toDoubleOrNull() }.filter { it.isFinite() }.toList()

    /** 解析单个 transform 属性为矩阵。 */
    private fun parseTransform(value: String?): Matrix {
        if (value == null) return IDENTITY
        var matrix = IDENTITY
        val expression = Regex("([a-zA-Z]+)\\s*\\(([^)]*)\\)")
        for (match in expression.findAll(value)) {
            val name = match.groupValues[1].lowercase()
            val args = numbersIn(match.groupValues[2])
            var current = IDENTITY
            when (name) {
                "matrix" -> if (args.size >= 6) {
                    current = Matrix(args[0], args[1], args[2], args[3], args[4], args[5])
                }
                "translate" -> current = translate(args.getOrElse(0) { 0.0 }, args.getOrElse(1) { 0.0 })
                "scale" -> {
                    val sx = args.getOrElse(0) { 1.0 }
                    current = scaleMatrix(sx, args.getOrElse(1) { sx })
                }
                "rotate" -> {
                    val angle = args.getOrElse(0) { 0.0 }
                    val rotation = rotateMatrix(angle)
                    current = if (args.size >= 3) {
                        multiply(multiply(translate(args[1], args[2]), rotation), translate(-args[1], -args[2]))
                    } else rotation
                }
                "skewx" -> current = IDENTITY.copy(c = tan(args.getOrElse(0) { 0.0 } * PI / 180.0))
                "skewy" -> current = IDENTITY.copy(b = tan(args.getOrElse(0) { 0.0 } * PI / 180.0))
            }
            matrix = multiply(matrix, current)
        }
        return matrix
    }

    private fun isUsableMatrix(m: Matrix): Boolean =
        listOf(m.a, m.b, m.c, m.d, m.e, m.f).all { it.isFinite() }

    /**
     * 元素局部用户空间 → 导入坐标空间的变换。浏览器提供 getCTM()；
     * 回退路径（本移植固定走）组合 transform 属性与根 viewBox 偏移。
     */
    private fun elementMatrix(element: Element, root: Element, rootMatrix: Matrix): Matrix {
        val chain = mutableListOf<Element>()
        var current: Node? = element
        while (current is Element && current !== root) {
            chain.add(current)
            current = current.parentNode
        }
        var matrix = rootMatrix
        for (index in chain.indices.reversed()) {
            matrix = multiply(matrix, parseTransform(chain[index].getAttribute("transform")))
        }
        return matrix
    }

    private data class DecomposedMatrix(val scaleX: Double, val scaleY: Double, val rotation: Double)

    /** 分解缩放/旋转；skew 由列长度近似。 */
    private fun decomposeMatrix(m: Matrix): DecomposedMatrix {
        val scaleX = hypot(m.a, m.b)
        val determinant = m.a * m.d - m.b * m.c
        val scaleY = if (scaleX > 1e-9) abs(determinant) / scaleX else hypot(m.c, m.d)
        val rotation = atan2(m.b, m.a) * 180.0 / PI
        return DecomposedMatrix(
            max(scaleX, 1e-9),
            max(scaleY, 1e-9),
            if (rotation.isFinite()) rotation else 0.0,
        )
    }

    private fun uniformScale(d: DecomposedMatrix): Double =
        (kotlin.math.sqrt(d.scaleX * d.scaleY)).takeIf { it != 0.0 } ?: max(d.scaleX, d.scaleY)

    // ------------------------------------------------------------------ units

    private fun clamp(value: Double, min: Double, max: Double): Double = min(max, max(min, value))

    /** DOM getAttribute 对缺失属性返回空串；此助手把它折叠为"缺席"语义。 */
    private fun nonEmptyOr(primary: String, fallback: String): String =
        if (primary.isNotEmpty()) primary else fallback

    fun parseLength(value: String?): Double? {
        if (value == null) return null
        val match = Regex("^\\s*(-?(?:\\d*\\.\\d+|\\d+\\.?)(?:[eE][-+]?\\d+)?)\\s*([a-zA-Z%]*)\\s*$").find(value)
            ?: return null
        val amount = match.groupValues[1].toDoubleOrNull() ?: return null
        if (!amount.isFinite()) return null
        return when (match.groupValues[2].lowercase()) {
            "", "px" -> amount
            "pt" -> amount * 96.0 / 72.0
            "pc" -> amount * 16.0
            "mm" -> amount * 96.0 / 25.4
            "cm" -> amount * 96.0 / 2.54
            "in" -> amount * 96.0
            "%" -> null
            else -> amount
        }
    }

    private fun numberAttribute(element: Element, name: String, fallback: Double = 0.0): Double =
        parseLength(element.getAttribute(name)) ?: fallback

    private fun parseViewBox(value: String?): BBox? {
        if (value == null) return null
        val parts = numbersIn(value)
        if (parts.size < 4) return null
        val (x, y, width, height) = listOf(parts[0], parts[1], parts[2], parts[3])
        if (!(width > 0) || !(height > 0)) return null
        return BBox(x, y, width, height)
    }

    // ------------------------------------------------------------------ styles

    private fun rawStyleValue(element: Element, property: String): String {
        val raw = element.getAttribute("style")
        if (raw.isNullOrEmpty()) return ""
        val wanted = property.lowercase()
        for (declaration in raw.split(';')) {
            val separator = declaration.indexOf(':')
            if (separator < 0) continue
            if (declaration.substring(0, separator).trim().lowercase() == wanted) {
                return declaration.substring(separator + 1).trim()
            }
        }
        return ""
    }

    private fun ownStyleValue(element: Element, property: String): String {
        // 无 getComputedStyle（jsdom 回退路径）：inline style → presentation attribute。
        val inline = rawStyleValue(element, property)
        if (inline.isNotEmpty() && inline != "auto") return inline
        val attribute = element.getAttribute(property)
        if (!attribute.isNullOrEmpty() && attribute.trim().isNotEmpty() && attribute.trim() != "auto") return attribute.trim()
        return ""
    }

    /** 读取样式，沿祖先继承 SVG presentation 值。 */
    private fun styleValue(element: Element, property: String): String {
        var current: Node? = element
        while (current is Element) {
            val value = ownStyleValue(current, property)
            if (value.isNotEmpty()) return value
            if (property !in INHERITED_STYLES) return ""
            current = current.parentNode
        }
        return ""
    }

    private fun ownOpacity(element: Element): Double {
        val raw = ownStyleValue(element, "opacity")
        if (raw.isEmpty()) return 1.0
        val value = raw.toDoubleOrNull() ?: return 1.0
        return if (value.isFinite()) clamp(value, 0.0, 1.0) else 1.0
    }

    private fun effectiveOpacity(element: Element): Double {
        var value = ownOpacity(element)
        var current = element.parentNode
        while (current is Element) {
            value *= ownOpacity(current)
            current = current.parentNode
        }
        return clamp(value, 0.0, 1.0)
    }

    private fun numberInUnitRange(value: String): Double {
        if (value.isEmpty()) return 1.0
        val parsed = value.toDoubleOrNull() ?: return 1.0
        return if (parsed.isFinite()) clamp(parsed, 0.0, 1.0) else 1.0
    }

    private fun findById(root: Element, id: String): Element? {
        if (root.getAttribute("id") == id) return root
        return allElements(root).firstOrNull { it.getAttribute("id") == id }
    }

    private fun allElements(root: Element): List<Element> {
        val out = mutableListOf<Element>()
        fun visit(e: Element) {
            var child = e.firstChild
            while (child != null) {
                if (child is Element) {
                    out.add(child)
                    visit(child)
                }
                child = child.nextSibling
            }
        }
        visit(root)
        return out
    }

    private fun rgbToColor(value: String): String? {
        val match = Regex(
            "rgba?\\(\\s*([\\d.]+)\\s*[,\\s]\\s*([\\d.]+)\\s*[,\\s]\\s*([\\d.]+)(?:\\s*[,/]\\s*([\\d.]+)(%?))?\\s*\\)",
            RegexOption.IGNORE_CASE,
        ).find(value) ?: return null
        fun component(text: String): Int = clamp((text.toDoubleOrNull() ?: 0.0).let { kotlin.math.round(it) }, 0.0, 255.0).toInt()
        val red = component(match.groupValues[1])
        val green = component(match.groupValues[2])
        val blue = component(match.groupValues[3])
        val alphaText = match.groupValues[4]
        if (alphaText.isNotEmpty()) {
            val alpha = if (match.groupValues[5] == "%") (alphaText.toDoubleOrNull() ?: 1.0) / 100.0 else alphaText.toDoubleOrNull() ?: 1.0
            if (alpha.isFinite()) {
                if (alpha <= 0.001) return "none"
                if (alpha < 0.999) {
                    val rounded = kotlin.math.round(alpha * 1000) / 1000.0
                    return "rgba($red, $green, $blue, " + trimDouble(rounded) + ")"
                }
            }
        }
        return "#%02x%02x%02x".format(red, green, blue)
    }

    private fun trimDouble(v: Double): String {
        val s = v.toString()
        return if (s.endsWith(".0")) s.dropLast(2) else s
    }

    private fun firstGradientStop(root: Element, id: String): String {
        val gradient = findById(root, id) ?: return ""
        val stops = gradient.getElementsByTagName("stop")
        for (index in 0 until stops.length) {
            val stop = stops.item(index) as Element
            val color = styleValue(stop, "stop-color")
            if (color.isEmpty() || color == "none" || Regex("^url\\(", RegexOption.IGNORE_CASE).containsMatchIn(color)) continue
            val opacity = (styleValue(stop, "stop-opacity").ifEmpty { "1" }).toDoubleOrNull() ?: 1.0
            if (opacity.isFinite() && opacity <= 0.001) continue
            return color
        }
        return ""
    }

    private fun normalizeColor(value: String, element: Element, root: Element): String {
        val text = value.trim()
        if (text.isEmpty()) return ""
        val lower = text.lowercase()
        if (lower == "none" || lower == "transparent") return "none"
        if (lower == "currentcolor") {
            val inherited = styleValue(element, "color")
            return if (inherited.isNotEmpty()) normalizeColor(inherited, element, root) else "#000000"
        }
        val gradient = Regex("url\\(\\s*['\"]?#([^'\")\\s]+)['\"]?\\s*\\)", RegexOption.IGNORE_CASE).find(text)
        if (gradient != null) {
            val stop = firstGradientStop(root, gradient.groupValues[1])
            return if (stop.isNotEmpty()) normalizeColor(stop, element, root) else "none"
        }
        if (Regex("^rgba?\\(", RegexOption.IGNORE_CASE).containsMatchIn(text)) return rgbToColor(text) ?: text
        return text
    }

    private fun resolvePaint(element: Element, root: Element, property: String): String {
        val raw = styleValue(element, property)
        // SVG 默认填充黑色；描边默认缺席。
        if (raw.isEmpty()) return if (property == "fill") "#000000" else "none"
        return normalizeColor(raw, element, root).ifEmpty { if (property == "fill") "#000000" else "none" }
    }

    private fun resolveStrokeWidth(element: Element, scale: Double): Double {
        val raw = styleValue(element, "stroke-width")
        val parsed = parseLength(raw)
        return max(0.0, (parsed ?: 1.0) * scale)
    }

    private fun resolveDash(element: Element, strokeWidth: Double): String {
        val raw = styleValue(element, "stroke-dasharray").lowercase()
        if (raw.isEmpty() || raw == "none" || raw == "solid") return "solid"
        val values = numbersIn(raw).filter { it > 0 }
        if (values.isEmpty()) return "solid"
        return if (values[0] <= strokeWidth * 1.5 + 0.5) "dotted" else "dashed"
    }

    private fun parseFontSize(element: Element): Double {
        val raw = styleValue(element, "font-size")
        val parsed = parseLength(raw)
        return if (parsed != null && parsed > 0) parsed else 16.0
    }

    private fun parseFontFamily(element: Element): String {
        val raw = styleValue(element, "font-family")
        if (raw.isEmpty()) return DEFAULT_FIGURE_FONT
        val first = raw.split(',')[0].trim().trim('\'', '"')
        return first.ifEmpty { DEFAULT_FIGURE_FONT }
    }

    private fun parseFontWeight(element: Element): Int {
        val raw = styleValue(element, "font-weight").lowercase()
        if (raw.isEmpty()) return 400
        if (raw == "normal") return 400
        if (raw == "bold" || raw == "bolder") return 700
        val numeric = raw.toIntOrNull() ?: return 400
        if (numeric <= 400) return 400
        if (numeric <= 500) return 500
        if (numeric <= 600) return 600
        if (numeric <= 700) return 700
        return 800
    }

    private fun parseTextAlign(element: Element): String {
        return when (styleValue(element, "text-anchor").lowercase()) {
            "middle" -> "center"
            "end" -> "right"
            else -> "left"
        }
    }

    private fun isItalic(element: Element): Boolean =
        Regex("italic|oblique", RegexOption.IGNORE_CASE).containsMatchIn(styleValue(element, "font-style"))

    /** SVG 箭头即 marker；节点模型存三角箭头。 */
    private fun hasMarker(element: Element, property: String): Boolean {
        val value = styleValue(element, property).trim().lowercase()
        return value.isNotEmpty() && value != "none"
    }

    // ------------------------------------------------------------------ geometry

    private fun bboxOf(element: Element): BBox? {
        // 无 getBBox（jsdom 回退路径）：属性几何。
        return attributeBBox(element)
    }

    private fun attributeBBox(element: Element): BBox? {
        return when (element.tagName.lowercase()) {
            "rect", "image", "foreignobject" -> {
                val x = numberAttribute(element, "x")
                val y = numberAttribute(element, "y")
                tlwh(x, y, max(0.0, numberAttribute(element, "width")), max(0.0, numberAttribute(element, "height")))
            }
            "circle" -> {
                val cx = numberAttribute(element, "cx")
                val cy = numberAttribute(element, "cy")
                val r = max(0.0, numberAttribute(element, "r"))
                tlwh(cx - r, cy - r, r * 2, r * 2)
            }
            "ellipse" -> {
                val cx = numberAttribute(element, "cx")
                val cy = numberAttribute(element, "cy")
                val rx = max(0.0, numberAttribute(element, "rx"))
                val ry = max(0.0, numberAttribute(element, "ry"))
                tlwh(cx - rx, cy - ry, rx * 2, ry * 2)
            }
            "line" -> {
                val x1 = numberAttribute(element, "x1")
                val y1 = numberAttribute(element, "y1")
                val x2 = numberAttribute(element, "x2")
                val y2 = numberAttribute(element, "y2")
                tlwh(min(x1, x2), min(y1, y2), abs(x2 - x1), abs(y2 - y1))
            }
            "polyline", "polygon" -> pointsBBox(parsePointsAttribute(element.getAttribute("points") ?: ""))
            "text" -> {
                val lines = elementText(element).split('\n')
                val fontSize = parseFontSize(element)
                val width = max(1.0, (lines.maxOfOrNull { it.length } ?: 0) * fontSize * 0.6)
                val height = max(fontSize, lines.size * fontSize * 1.2)
                var x = numberAttribute(element, "x")
                val y = numberAttribute(element, "y")
                when (styleValue(element, "text-anchor").lowercase()) {
                    "middle" -> x -= width / 2
                    "end" -> x -= width
                }
                tlwh(x, y - fontSize * 0.8, width, height)
            }
            "path" -> pointsBBox(parsePathData(element.getAttribute("d") ?: "").first)
            else -> null
        }
    }

    private fun tlwh(x: Double, y: Double, width: Double, height: Double) = BBox(x, y, width, height)

    private fun pointsBBox(points: List<Point>): BBox? {
        if (points.isEmpty()) return null
        var minX = Double.POSITIVE_INFINITY
        var minY = Double.POSITIVE_INFINITY
        var maxX = Double.NEGATIVE_INFINITY
        var maxY = Double.NEGATIVE_INFINITY
        for (point in points) {
            minX = min(minX, point.x)
            minY = min(minY, point.y)
            maxX = max(maxX, point.x)
            maxY = max(maxY, point.y)
        }
        if (!minX.isFinite() || !minY.isFinite()) return null
        return tlwh(minX, minY, max(0.0, maxX - minX), max(0.0, maxY - minY))
    }

    private fun parsePointsAttribute(value: String): List<Point> {
        val numbers = numbersIn(value)
        val points = mutableListOf<Point>()
        var index = 0
        while (index + 1 < numbers.size) {
            points.add(Point(numbers[index], numbers[index + 1]))
            index += 2
        }
        return points
    }

    // ---------------------------------------------------------------- path parser

    private fun tokenizePathData(data: String): List<String> =
        Regex("[a-zA-Z]|[-+]?(?:\\d*\\.\\d+|\\d+\\.?)(?:[eE][-+]?\\d+)?")
            .findAll(data).map { it.value }.filter { it.isNotEmpty() }.toList()

    private fun cubicAt(start: Point, control1: Point, control2: Point, end: Point, t: Double): Point {
        val mt = 1 - t
        val a = mt * mt * mt
        val b = 3 * mt * mt * t
        val c = 3 * mt * t * t
        val d = t * t * t
        return Point(
            a * start.x + b * control1.x + c * control2.x + d * end.x,
            a * start.y + b * control1.y + c * control2.y + d * end.y,
        )
    }

    private fun quadraticAt(start: Point, control: Point, end: Point, t: Double): Point {
        val mt = 1 - t
        return Point(
            mt * mt * start.x + 2 * mt * t * control.x + t * t * end.x,
            mt * mt * start.y + 2 * mt * t * control.y + t * t * end.y,
        )
    }

    /**
     * getTotalLength() 不可用时的回退 path 展平器。曲线采样，弧以端点近似。
     * 多个子路径展平为一条折线（节点模型只存一条）。
     */
    private fun parsePathData(data: String): Pair<List<Point>, Boolean> {
        val tokens = tokenizePathData(data)
        val points = mutableListOf<Point>()
        var index = 0
        var command = ""
        var current = Point(0.0, 0.0)
        var start = Point(0.0, 0.0)
        var previousControl: Point? = null
        var closed = false

        fun push(point: Point) {
            points.add(point)
            current = point
        }

        fun read(): Double {
            val value = tokens.getOrNull(index)?.toDoubleOrNull()
            index += 1
            return if (value != null && value.isFinite()) value else 0.0
        }

        fun absolute(x: Double, y: Double, relative: Boolean): Point =
            if (relative) Point(current.x + x, current.y + y) else Point(x, y)

        while (index < tokens.size) {
            val token = tokens[index]
            if (token.length == 1 && token[0].isLetter()) {
                command = token
                index += 1
                if (command.lowercase() == "z") {
                    closed = true
                    push(start.copy())
                    previousControl = null
                }
                continue
            }
            if (command.isEmpty()) {
                index += 1
                continue
            }

            val relative = command == command.lowercase()
            val upper = command.uppercase()

            when (upper) {
                "M" -> {
                    val point = absolute(read(), read(), relative)
                    start = point
                    push(point)
                    // moveto 之后的坐标对按 lineto 处理。
                    command = if (relative) "l" else "L"
                    previousControl = null
                }
                "L" -> {
                    push(absolute(read(), read(), relative))
                    previousControl = null
                }
                "H" -> {
                    val x = read()
                    push(Point(if (relative) current.x + x else x, current.y))
                    previousControl = null
                }
                "V" -> {
                    val y = read()
                    push(Point(current.x, if (relative) current.y + y else y))
                    previousControl = null
                }
                "C" -> {
                    val origin = current
                    val control1 = absolute(read(), read(), relative)
                    val control2 = absolute(read(), read(), relative)
                    val end = absolute(read(), read(), relative)
                    for (step in 1..8) push(cubicAt(origin, control1, control2, end, step / 8.0))
                    previousControl = control2
                }
                "S" -> {
                    val origin = current
                    val control1 = previousControl?.let { Point(2 * origin.x - it.x, 2 * origin.y - it.y) } ?: origin
                    val control2 = absolute(read(), read(), relative)
                    val end = absolute(read(), read(), relative)
                    for (step in 1..8) push(cubicAt(origin, control1, control2, end, step / 8.0))
                    previousControl = control2
                }
                "Q" -> {
                    val origin = current
                    val control = absolute(read(), read(), relative)
                    val end = absolute(read(), read(), relative)
                    for (step in 1..8) push(quadraticAt(origin, control, end, step / 8.0))
                    previousControl = control
                }
                "T" -> {
                    val origin = current
                    val control = previousControl?.let { Point(2 * origin.x - it.x, 2 * origin.y - it.y) } ?: origin
                    val end = absolute(read(), read(), relative)
                    for (step in 1..8) push(quadraticAt(origin, control, end, step / 8.0))
                    previousControl = control
                }
                "A" -> {
                    // 弧标志位跳过；端点保持图形连通。
                    repeat(5) { read() }
                    push(absolute(read(), read(), relative))
                    previousControl = null
                }
                else -> index += 1
            }
        }

        return Pair(points, closed)
    }

    private fun dedupePoints(points: List<Point>, closed: Boolean): List<Point> {
        val output = mutableListOf<Point>()
        for (point in points) {
            val previous = output.lastOrNull()
            if (previous != null && hypot(point.x - previous.x, point.y - previous.y) < 1e-9) continue
            output.add(point)
        }
        if (closed && output.size > 1) {
            val first = output.first()
            val last = output.last()
            if (hypot(first.x - last.x, first.y - last.y) < 1e-9) output.removeAt(output.size - 1)
        }
        return output
    }

    private fun resample(points: List<Point>, closed: Boolean, count: Int): List<Point> {
        val source = if (closed && points.size > 1) points + points.first() else points
        if (source.size < 2) return points.toList()
        var total = 0.0
        val lengths = mutableListOf(0.0)
        for (index in 1 until source.size) {
            total += hypot(source[index].x - source[index - 1].x, source[index].y - source[index - 1].y)
            lengths.add(total)
        }
        if (total <= 1e-9) return points.toList()
        val output = mutableListOf<Point>()
        for (sample in 0 until count) {
            val target = total * sample / (count - 1)
            var segment = 1
            while (segment < lengths.size - 1 && lengths[segment] < target) segment += 1
            val span = lengths[segment] - lengths[segment - 1].let { if (it == 0.0) 1.0 else it }
            val t = clamp((target - lengths[segment - 1]) / span, 0.0, 1.0)
            output.add(
                Point(
                    source[segment - 1].x + (source[segment].x - source[segment - 1].x) * t,
                    source[segment - 1].y + (source[segment].y - source[segment - 1].y) * t,
                ),
            )
        }
        return dedupePoints(output, closed)
    }

    private fun pathPoints(element: Element): Triple<List<Point>, Boolean, List<Point>>? {
        val data = element.getAttribute("d") ?: ""
        if (data.trim().isEmpty()) return null
        val closed = Regex("z", RegexOption.IGNORE_CASE).containsMatchIn(data)
        // 无 native 采样（getTotalLength 缺失）：始终走 parsePathData + resample。
        val parsed = parsePathData(data)
        // 保留原始顶点做包围盒：均匀采样可能削掉尖角。
        val bounds = if (parsed.first.isNotEmpty()) parsed.first else emptyList()
        val count = clamp(parsed.first.size * 2.0, 4.0, 200.0).toInt()
        return Triple(
            resample(parsed.first, parsed.second || closed, count),
            parsed.second || closed,
            if (bounds.isNotEmpty()) bounds else parsed.first,
        )
    }

    // ----------------------------------------------------------------------- text

    private fun normalizeText(value: String): String =
        value.replace(" ", " ").replace(Regex("[ \\t\\r\\x0c\\x0b]+"), " ").trim()

    private fun elementText(element: Element): String {
        val tspans = element.getElementsByTagName("tspan")
        if (tspans.length > 0) {
            val leaves = (0 until tspans.length).map { tspans.item(it) as Element }
                .filter { it.getElementsByTagName("tspan").length == 0 }
            var lines = leaves.map { normalizeText(it.textContent ?: "") }
            // 去掉首尾空行，保留中间有意的空行。
            while (lines.isNotEmpty() && lines.first().isEmpty()) lines = lines.drop(1)
            while (lines.isNotEmpty() && lines.last().isEmpty()) lines = lines.dropLast(1)
            if (lines.isNotEmpty()) return lines.joinToString("\n")
        }
        return normalizeText(element.textContent ?: "")
    }

    // ------------------------------------------------------------------ traversal

    private fun groupIdentity(element: Element): Boolean =
        element.hasAttribute("data-name") || element.hasAttribute("data-label") ||
            element.hasAttribute("aria-label") || element.hasAttribute("inkscape:label") ||
            element.hasAttribute("id")

    private fun isNodeGroup(element: Element): Boolean =
        Regex("(^|\\s)node(\\s|$)").containsMatchIn(" ${element.getAttribute("class") ?: ""} ")

    private fun isClusterGroup(element: Element): Boolean =
        Regex("(^|\\s)cluster(\\s|$)").containsMatchIn(" ${element.getAttribute("class") ?: ""} ")

    private fun groupName(element: Element): String {
        for (attribute in listOf("data-name", "aria-label", "inkscape:label", "data-label")) {
            val value = normalizeText(element.getAttribute(attribute) ?: "")
            if (value.isNotEmpty()) return value.take(60)
        }
        val label = elementText(element).split('\n').firstOrNull() ?: ""
        if (label.isNotEmpty()) return label.take(60)
        val id = element.getAttribute("id") ?: ""
        return if (id.isNotEmpty()) id.take(60) else "Group"
    }

    private fun isDisplayNone(element: Element): Boolean =
        styleValue(element, "display").lowercase() == "none"

    private fun isVisibilityHidden(element: Element): Boolean =
        styleValue(element, "visibility").lowercase() == "hidden"

    private fun warnOnce(context: ConvertContext, key: String, message: String) {
        if (context.warned.contains(key)) return
        context.warned.add(key)
        context.warnings.add(message)
    }

    // ------------------------------------------------------------------ factories

    private const val DEFAULT_FIGURE_FONT = "Arial"

    private fun baseNode(type: String, x: Double, y: Double, w: Double, h: Double): VNode = VNode(
        id = createFigureId("node"),
        type = type,
        name = type,
        x = x, y = y, w = w, h = h,
        fill = "#e8f1f8",
        stroke = "#1f6f8b",
        strokeWidth = 1.5,
    )

    private fun createRectNode(x: Double, y: Double): VNode =
        baseNode("rect", x, y, 160.0, 90.0).copy(name = "Rectangle")

    private fun createEllipseNode(x: Double, y: Double): VNode =
        baseNode("ellipse", x, y, 120.0, 80.0).copy(name = "Ellipse", fill = "#fdf0e6", stroke = "#c46a1b")

    private fun createTextNode(x: Double, y: Double, text: String): VNode =
        baseNode("text", x, y, 180.0, 48.0).copy(
            name = text.split('\n').first().take(28).ifEmpty { "Text" },
            fill = "none", stroke = "none", strokeWidth = 0.0,
            text = text,
            fontFamily = DEFAULT_FIGURE_FONT,
            fontSize = 12.0,
            fontWeight = 400,
            italic = false,
            align = "left",
            lineHeight = 1.25,
            padding = 4.0,
            color = "#111827",
            background = "none",
        )

    private fun createImageNode(dataUrl: String, mime: String, x: Double, y: Double): VNode =
        baseNode("image", x, y, 200.0, 150.0).copy(
            name = "Image",
            fill = "none", stroke = "none", strokeWidth = 0.0,
            dataUrl = dataUrl,
            mime = mime,
            fit = "contain",
        )

    private fun createPathNode(x: Double, y: Double, w: Double, h: Double, closed: Boolean): VNode =
        baseNode("path", x, y, w, h).copy(
            name = "Path",
            fill = if (closed) "#e8f1f8" else "none",
            stroke = "#263238",
            strokeWidth = 2.0,
            points = listOf(VPoint(0.0, 0.0), VPoint(1.0, 1.0)),
            closed = closed,
            headStart = false,
            headEnd = !closed,
            headSize = 12.0,
        )

    /** 旋转节点的轴对齐包围盒（figure 坐标）。 */
    private fun nodeBounds(node: VNode): BBox {
        if (node.rotation == 0.0) return BBox(node.x, node.y, node.w, node.h)
        val cx = node.x + node.w / 2
        val cy = node.y + node.h / 2
        val rad = node.rotation * PI / 180.0
        val cos = abs(cos(rad))
        val sin = abs(sin(rad))
        val w = node.w * cos + node.h * sin
        val h = node.w * sin + node.h * cos
        return BBox(cx - w / 2, cy - h / 2, w, h)
    }

    // ------------------------------------------------------------------ converters

    private fun applyCommon(node: VNode, element: Element, context: ConvertContext, groupId: String?, scale: Double) {
        node.fill = resolvePaint(element, context.root, "fill")
        node.stroke = resolvePaint(element, context.root, "stroke")
        node.strokeWidth = resolveStrokeWidth(element, scale)
        node.dash = resolveDash(element, node.strokeWidth)
        node.opacity = clamp(
            effectiveOpacity(element) * paintOpacity(element, node.fill, node.stroke),
            0.0, 1.0,
        )
        if (groupId != null) node.groupId = groupId
    }

    private fun paintOpacity(element: Element, fill: String, stroke: String): Double {
        val fillOpacity = numberInUnitRange(styleValue(element, "fill-opacity"))
        val strokeOpacity = numberInUnitRange(styleValue(element, "stroke-opacity"))
        if (fill != "none" && stroke != "none") return max(fillOpacity, strokeOpacity)
        if (fill != "none") return fillOpacity
        if (stroke != "none") return strokeOpacity
        return 1.0
    }

    private fun convertRect(element: Element, context: ConvertContext, groupId: String?, matrix: Matrix): VNode? {
        val bbox = bboxOf(element) ?: return null
        val decomposed = decomposeMatrix(matrix)
        val scale = uniformScale(decomposed)
        val center = applyMatrix(matrix, bbox.x + bbox.width / 2, bbox.y + bbox.height / 2)
        val width = max(0.01, bbox.width * decomposed.scaleX)
        val height = max(0.01, bbox.height * decomposed.scaleY)
        val node = createRectNode(center.x - width / 2, center.y - height / 2)
        node.w = width
        node.h = height
        node.rotation = decomposed.rotation
        val radius = parseLength(element.getAttribute("rx")) ?: parseLength(element.getAttribute("ry")) ?: 0.0
        node.cornerRadius = max(0.0, radius * decomposed.scaleX)
        applyCommon(node, element, context, groupId, scale)
        val id = element.getAttribute("id")
        if (id != null && id.isNotEmpty()) node.name = id
        return node
    }

    private fun convertEllipse(element: Element, context: ConvertContext, groupId: String?, matrix: Matrix): VNode? {
        val bbox = bboxOf(element) ?: return null
        val decomposed = decomposeMatrix(matrix)
        val scale = uniformScale(decomposed)
        val center = applyMatrix(matrix, bbox.x + bbox.width / 2, bbox.y + bbox.height / 2)
        val width = max(0.01, bbox.width * decomposed.scaleX)
        val height = max(0.01, bbox.height * decomposed.scaleY)
        val node = createEllipseNode(center.x - width / 2, center.y - height / 2)
        node.w = width
        node.h = height
        node.rotation = decomposed.rotation
        applyCommon(node, element, context, groupId, scale)
        val id = element.getAttribute("id")
        if (id != null && id.isNotEmpty()) node.name = id
        return node
    }

    private fun convertText(element: Element, context: ConvertContext, groupId: String?, matrix: Matrix): VNode? {
        val content = elementText(element)
        if (content.isEmpty()) return null
        val bbox = bboxOf(element) ?: return null
        val decomposed = decomposeMatrix(matrix)
        val scale = uniformScale(decomposed)
        val center = applyMatrix(matrix, bbox.x + bbox.width / 2, bbox.y + bbox.height / 2)
        val width = max(1.0, bbox.width * decomposed.scaleX)
        val height = max(1.0, bbox.height * decomposed.scaleY)
        val node = createTextNode(center.x - width / 2, center.y - height / 2, content)
        node.w = width
        node.h = height
        node.rotation = decomposed.rotation
        node.name = content.split('\n').first().take(28).ifEmpty { "Text" }
        node.fontFamily = parseFontFamily(element)
        // SVG 坐标空间是 96 dpi 的 CSS 像素；模型存 pt。
        node.fontSize = max(1.0, parseFontSize(element) * scale * 0.75)
        node.fontWeight = parseFontWeight(element)
        node.italic = isItalic(element)
        node.align = parseTextAlign(element)
        node.lineHeight = 1.2
        node.padding = 0.0
        val fill = resolvePaint(element, context.root, "fill")
        node.color = if (fill == "none") "#000000" else fill
        node.fill = "none"
        node.stroke = "none"
        node.strokeWidth = 0.0
        node.opacity = clamp(
            effectiveOpacity(element) * numberInUnitRange(styleValue(element, "fill-opacity")),
            0.0, 1.0,
        )
        if (groupId != null) node.groupId = groupId
        return node
    }

    private fun convertImage(element: Element, context: ConvertContext, groupId: String?, matrix: Matrix): VNode? {
        val href = nonEmptyOr(
            element.getAttribute("href"),
            nonEmptyOr(element.getAttributeNS("http://www.w3.org/1999/xlink", "href"),
                element.getAttribute("xlink:href")),
        )
        if (!Regex("^data:image/", RegexOption.IGNORE_CASE).containsMatchIn(href)) {
            warnOnce(
                context,
                "image-external",
                "Skipped an image that is not embedded as a data: URL. Export it as a data URL first.",
            )
            return null
        }
        val bbox = bboxOf(element) ?: return null
        val decomposed = decomposeMatrix(matrix)
        val scale = uniformScale(decomposed)
        val width = max(1.0, bbox.width * decomposed.scaleX)
        val height = max(1.0, bbox.height * decomposed.scaleY)
        val center = applyMatrix(matrix, bbox.x + bbox.width / 2, bbox.y + bbox.height / 2)
        val mime = Regex("^data:([^;,]+)", RegexOption.IGNORE_CASE).find(href)?.groupValues?.get(1) ?: "image/png"
        val node = createImageNode(href, mime, center.x - width / 2, center.y - height / 2)
        node.w = width
        node.h = height
        val preserve = (element.getAttribute("preserveAspectRatio") ?: "").lowercase()
        node.fit = when {
            preserve.contains("none") -> "stretch"
            preserve.contains("slice") -> "cover"
            else -> "contain"
        }
        node.rotation = decomposed.rotation
        node.fill = "none"
        node.stroke = resolvePaint(element, context.root, "stroke")
        node.strokeWidth = resolveStrokeWidth(element, scale)
        node.opacity = effectiveOpacity(element)
        if (groupId != null) node.groupId = groupId
        return node
    }

    private fun convertPath(element: Element, context: ConvertContext, groupId: String?, matrix: Matrix): VNode? {
        val tag = element.tagName.lowercase()
        var localPoints: List<Point>
        var boundsPoints: List<Point>
        var closed: Boolean

        if (tag == "line") {
            localPoints = listOf(
                Point(numberAttribute(element, "x1"), numberAttribute(element, "y1")),
                Point(numberAttribute(element, "x2"), numberAttribute(element, "y2")),
            )
            boundsPoints = localPoints
            closed = false
        } else if (tag == "polyline" || tag == "polygon") {
            localPoints = parsePointsAttribute(element.getAttribute("points") ?: "")
            boundsPoints = localPoints
            closed = tag == "polygon"
        } else {
            val data = element.getAttribute("d") ?: ""
            if (Regex("[Mm]").findAll(data).count() > 1) {
                warnOnce(
                    context,
                    "path:subpaths",
                    "Flattened a path with multiple subpaths into a single editable outline.",
                )
            }
            val sampled = pathPoints(element) ?: return null
            localPoints = sampled.first
            boundsPoints = sampled.third
            closed = sampled.second
        }

        if (localPoints.size < 2) return null
        val points = localPoints.map { applyMatrix(matrix, it.x, it.y) }
        val bounds = boundsPoints.map { applyMatrix(matrix, it.x, it.y) }
        val bbox = pointsBBox(points + bounds) ?: pointsBBox(points) ?: return null
        val width = max(1.0, bbox.width)
        val height = max(1.0, bbox.height)
        val normalized: List<VPoint> = points.map {
            VPoint(
                if (bbox.width > 1e-9) clamp((it.x - bbox.x) / bbox.width, 0.0, 1.0) else 0.0,
                if (bbox.height > 1e-9) clamp((it.y - bbox.y) / bbox.height, 0.0, 1.0) else 0.0,
            )
        }
        val decomposed = decomposeMatrix(matrix)
        val scale = uniformScale(decomposed)
        val node = createPathNode(bbox.x, bbox.y, width, height, closed)
        node.points = normalized
        node.name = when (tag) {
            "line" -> "Line"
            "polyline" -> "Polyline"
            "polygon" -> "Polygon"
            else -> "Path"
        }
        applyCommon(node, element, context, groupId, scale)
        // 保留 Mermaid 风格边：marker 变 path 节点上的三角箭头。
        node.headStart = hasMarker(element, "marker-start")
        node.headEnd = hasMarker(element, "marker-end")
        node.headSize = max(6.0, node.strokeWidth * 4)
        return node
    }

    private fun convertElement(element: Element, context: ConvertContext, groupId: String?, rootMatrix: Matrix): VNode? {
        val tag = element.tagName.lowercase()
        if (isVisibilityHidden(element)) return null
        if (effectiveOpacity(element) <= 0.001) return null
        val matrix = elementMatrix(element, context.root, rootMatrix)
        return when (tag) {
            "rect" -> convertRect(element, context, groupId, matrix)
            "circle", "ellipse" -> convertEllipse(element, context, groupId, matrix)
            "line", "polyline", "polygon", "path" -> convertPath(element, context, groupId, matrix)
            "text" -> convertText(element, context, groupId, matrix)
            "image" -> convertImage(element, context, groupId, matrix)
            else -> null
        }
    }

    private fun walk(
        element: Element,
        context: ConvertContext,
        rootMatrix: Matrix,
        groupId: String?,
        nodes: MutableList<VNode>,
        groups: MutableList<VGroup>,
    ) {
        val tag = element.tagName.lowercase()

        if (tag in IGNORED) return
        if (tag != "svg" && tag != "g" && tag != "a") {
            if (tag in UNSUPPORTED) {
                warnOnce(context, "tag:$tag", "Skipped unsupported <$tag> element.")
                return
            }
            if (tag in DRAWABLE) {
                if (isDisplayNone(element)) return
                val node = convertElement(element, context, groupId, rootMatrix)
                if (node != null) nodes.add(node)
                // 文本子元素由 convertText 消费。
                return
            }
            // 未知包装（如 <switch>）；递归保留其可绘制子元素。
        }

        if (isDisplayNone(element)) return

        var nextGroup = groupId
        if (tag == "g" || tag == "a") {
            val nodeGroup = isNodeGroup(element)
            val clusterGroup = isClusterGroup(element)
            val generic = !nodeGroup && !clusterGroup
            // 通用身份组只在分支顶端创建，避免 Mermaid 标签包装从组里偷走节点的形状。
            val allowed = !generic || groupId == null
            if (allowed && (nodeGroup || clusterGroup || groupIdentity(element))) {
                val id = createFigureId("group")
                groups.add(VGroup(id, groupName(element)))
                nextGroup = id
            }
        }

        var child = element.firstChild
        while (child != null) {
            if (child is Element) walk(child, context, rootMatrix, nextGroup, nodes, groups)
            child = child.nextSibling
        }
    }

    // -------------------------------------------------------------- entry points

    private fun sanitizeClone(root: Element, warnings: MutableList<String>): Element {
        val foreignObjects = mutableListOf<Element>()
        for (e in allElements(root)) if (e.tagName.lowercase() == "foreignobject") foreignObjects.add(e)
        if (foreignObjects.isNotEmpty()) {
            warnings.add(
                "Skipped ${foreignObjects.size} <foreignObject> element" +
                    (if (foreignObjects.size == 1) "" else "s") + ".",
            )
        }
        for (e in allElements(root)) {
            if (e.tagName.lowercase() == "script" || e.tagName.lowercase() == "foreignobject") {
                e.parentNode?.removeChild(e)
            }
        }
        for (element in listOf(root) + allElements(root)) {
            val attributes = (0 until element.attributes.length).map { element.attributes.item(it) }
            for (attribute in attributes) {
                val name = attribute.nodeName.lowercase()
                if (name.startsWith("on")) {
                    element.removeAttribute(attribute.nodeName)
                } else if ((name == "href" || name == "xlink:href") &&
                    Regex("^\\s*javascript:", RegexOption.IGNORE_CASE).containsMatchIn(attribute.nodeValue ?: "")
                ) {
                    element.removeAttribute(attribute.nodeName)
                }
            }
        }
        return root
    }

    class SvgImportException(message: String) : Exception(message)

    /**
     * 解析 SVG 标记并把支持的可绘制元素转换为 figure 节点。
     * 返回节点归一到局部 (0, 0) 原点；结果携带局部宽高供 placeVectorImport() 适配页面。
     */
    fun importSvgMarkup(svgText: String): VectorImportResult {
        if (svgText.isBlank()) throw SvgImportException("SVG markup is empty.")

        val factory = DocumentBuilderFactory.newInstance()
        runCatching { factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        factory.isNamespaceAware = true
        val builder = factory.newDocumentBuilder()
        val parsed: Document = try {
            builder.parse(ByteArrayInputStream(svgText.toByteArray(Charsets.UTF_8)))
        } catch (e: Exception) {
            throw SvgImportException("Could not parse the SVG markup. Check that the file is valid SVG XML.")
        }
        val sourceRoot = parsed.documentElement
            ?: throw SvgImportException("The selected file is not an SVG document.")
        if (sourceRoot.tagName.lowercase() != "svg") {
            throw SvgImportException("The selected file is not an SVG document.")
        }

        val viewBox = parseViewBox(sourceRoot.getAttribute("viewBox"))
        val viewportWidth = viewBox?.width ?: parseLength(sourceRoot.getAttribute("width")) ?: 800.0
        val viewportHeight = viewBox?.height ?: parseLength(sourceRoot.getAttribute("height")) ?: 600.0
        if (!(viewportWidth > 0) || !(viewportHeight > 0)) {
            throw SvgImportException("The SVG has no usable width or height.")
        }

        val warnings = mutableListOf<String>()
        val root = sanitizeClone(sourceRoot, warnings)
        // 用 viewBox 用户单位做导入坐标空间；根 width/height 与 viewBox 不同只会加一个
        // 归一化时消掉的均匀缩放。
        root.setAttribute("width", trimDouble(viewportWidth))
        root.setAttribute("height", trimDouble(viewportHeight))

        val rootMatrix = if (viewBox != null) translate(-viewBox.x, -viewBox.y) else IDENTITY
        val context = ConvertContext(root, warnings)
        val nodes = mutableListOf<VNode>()
        val groups = mutableListOf<VGroup>()

        var child = root.firstChild
        while (child != null) {
            if (child is Element) walk(child, context, rootMatrix, null, nodes, groups)
            child = child.nextSibling
        }

        if (nodes.isEmpty()) {
            throw SvgImportException("No supported vector elements were found in this SVG.")
        }

        // 归一到局部原点并推导导入包围盒。
        var minX = Double.POSITIVE_INFINITY
        var minY = Double.POSITIVE_INFINITY
        var maxX = Double.NEGATIVE_INFINITY
        var maxY = Double.NEGATIVE_INFINITY
        for (node in nodes) {
            val bounds = nodeBounds(node)
            minX = min(minX, bounds.x)
            minY = min(minY, bounds.y)
            maxX = max(maxX, bounds.x + bounds.width)
            maxY = max(maxY, bounds.y + bounds.height)
        }
        for (node in nodes) {
            node.x -= minX
            node.y -= minY
        }

        // 丢弃空组，图层面板不显示看不见的容器。
        val usedGroups = nodes.mapNotNull { it.groupId }.toHashSet()
        val keptGroups = groups.filter { usedGroups.contains(it.id) }
        val keptIds = keptGroups.map { it.id }.toHashSet()
        for (node in nodes) {
            if (node.groupId != null && !keptIds.contains(node.groupId)) node.groupId = null
        }

        return VectorImportResult(
            nodes.toList(),
            keptGroups,
            max(1.0, maxX - minX),
            max(1.0, maxY - minY),
            warnings,
        )
    }

    /**
     * 把导入的矢量图适配到页面上。
     *
     * 导入器产出 CSS 像素几何（96 dpi）。统一缩放 s 作用于位置、尺寸、描边宽、圆角、
     * 箭头/头尺寸与 pt 字号，使图形放大/缩小时保持视觉比例。组 id 直通；
     * 插入既有文档时的 id 去重由调用方负责。
     */
    fun placeVectorImport(
        result: VectorImportResult,
        pageWidth: Double,
        pageHeight: Double,
        marginRatio: Double = 0.08,
    ): Pair<List<VNode>, List<VGroup>> {
        val margin = clamp(marginRatio, 0.0, 0.45)
        val innerWidth = max(1.0, pageWidth * (1 - margin * 2))
        val innerHeight = max(1.0, pageHeight * (1 - margin * 2))
        val sourceWidth = max(1.0, result.width)
        val sourceHeight = max(1.0, result.height)
        val scale = min(innerWidth / sourceWidth, innerHeight / sourceHeight)
        val offsetX = (pageWidth - sourceWidth * scale) / 2
        val offsetY = (pageHeight - sourceHeight * scale) / 2

        val nodes = result.nodes.map { node ->
            val copy = node.copy()
            copy.x = offsetX + copy.x * scale
            copy.y = offsetY + copy.y * scale
            copy.w = copy.w * scale
            copy.h = copy.h * scale
            copy.strokeWidth = copy.strokeWidth * scale
            copy.cornerRadius = copy.cornerRadius * scale
            if (copy.type == "text") {
                copy.fontSize = copy.fontSize!! * scale
                copy.padding = copy.padding!! * scale
            }
            if (copy.type == "path" || copy.type == "line" || copy.type == "arrow") {
                copy.headSize = copy.headSize!! * scale
            }
            if (copy.type == "scalebar") {
                copy.thickness = copy.thickness?.times(scale)
                copy.fontSize = copy.fontSize?.times(scale)
            }
            if (copy.type == "chart") {
                copy.fontSize = copy.fontSize!! * scale
            }
            copy
        }

        return Pair(nodes, result.groups.map { it.copy() })
    }
}
