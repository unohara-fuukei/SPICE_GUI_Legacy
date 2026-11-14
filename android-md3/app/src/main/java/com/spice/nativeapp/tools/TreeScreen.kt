package com.spice.nativeapp.tools

// Newick 系统发育树工具（对应 main src/lib/tools/tree/TreeTool.svelte 的 1:1 移植）。
// 布局/解析/元数据/比较/导出全部走 core/TreeTool.kt；文案取自 main zh.json tree* 原文。
// 平台差异（README Known gaps 披露）：hover 悬停高亮/提示改为点击选择面板查看；
// “发送到 Figure Studio”依赖 figureBridge 图片层（#55 待办）暂缺；拖拽导入不适用触屏。

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.spice.nativeapp.core.EXAMPLE_METADATA
import com.spice.nativeapp.core.EXAMPLE_NEWICK
import com.spice.nativeapp.core.LayoutMode
import com.spice.nativeapp.core.MetadataParseError
import com.spice.nativeapp.core.NewickErrorCode
import com.spice.nativeapp.core.NewickParseError
import com.spice.nativeapp.core.PaletteId
import com.spice.nativeapp.core.ParsedTree
import com.spice.nativeapp.core.RenderState
import com.spice.nativeapp.core.ReproFile
import com.spice.nativeapp.core.THEME_COLORS
import com.spice.nativeapp.core.TreeComparison
import com.spice.nativeapp.core.TreeDoc
import com.spice.nativeapp.core.TreeLayout
import com.spice.nativeapp.core.TreeMetadata
import com.spice.nativeapp.core.TreeNode
import com.spice.nativeapp.core.TreeProject
import com.spice.nativeapp.core.TreeTheme
import com.spice.nativeapp.core.buildReproBundle
import com.spice.nativeapp.core.compareTrees
import com.spice.nativeapp.core.drawTreeToCanvas
import com.spice.nativeapp.core.emptyMetadata
import com.spice.nativeapp.core.expandAncestors
import com.spice.nativeapp.core.formatNumber
import com.spice.nativeapp.core.isoNow
import com.spice.nativeapp.core.ladderizeTree
import com.spice.nativeapp.core.metadataToCsv
import com.spice.nativeapp.core.midpointRoot
import com.spice.nativeapp.core.nodeToSerialized
import com.spice.nativeapp.core.parseMetadataTable
import com.spice.nativeapp.core.parseNewick
import com.spice.nativeapp.core.parseProjectJson
import com.spice.nativeapp.core.renderTreePng
import com.spice.nativeapp.core.renderTreeSvg
import com.spice.nativeapp.core.rotateChildren
import com.spice.nativeapp.core.serializeProject
import com.spice.nativeapp.core.serializedToTree
import com.spice.nativeapp.core.sortTreeAlphabetically
import com.spice.nativeapp.core.sortTreeByGroup
import com.spice.nativeapp.core.supportSummary
import com.spice.nativeapp.core.toNewick
import com.spice.nativeapp.core.treeDocName
import com.spice.nativeapp.core.treeMethods
import com.spice.nativeapp.core.treeParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

private const val MIN_ZOOM = 0.08
private const val MAX_ZOOM = 24.0
private const val SESSION_KEY = "spice_tree_viewer_v1"
private const val MAX_FILE_BYTES = 20L * 1024 * 1024

private class FileTooLargeException : Exception()

@Throws(IOException::class, FileTooLargeException::class)
private fun readTreeFile(ctx: Context, uri: Uri): String {
    return ctx.contentResolver.openInputStream(uri)?.use { ins ->
        val baos = java.io.ByteArrayOutputStream()
        val buf = ByteArray(1 shl 16)
        var total = 0L
        while (true) {
            val n = ins.read(buf)
            if (n < 0) break
            total += n
            if (total > MAX_FILE_BYTES) throw FileTooLargeException()
            baos.write(buf, 0, n)
        }
        baos.toString("UTF-8")
    } ?: throw IOException("cannot open")
}

private fun uriDisplayName(ctx: Context, uri: Uri): String? = runCatching {
    ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
        val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
    }
}.getOrNull()

private fun treeIndexOf(doc: TreeDoc): Triple<LinkedHashMap<String, TreeNode>, LinkedHashMap<String, String?>, MutableList<TreeNode>> {
    val byId = LinkedHashMap<String, TreeNode>()
    val parent = LinkedHashMap<String, String?>()
    val leaves = mutableListOf<TreeNode>()
    fun visit(node: TreeNode, parentId: String?) {
        byId[node.id] = node
        parent[node.id] = parentId
        if (node.children.isEmpty()) leaves.add(node)
        for (child in node.children) visit(child, node.id)
    }
    visit(doc.root, null)
    return Triple(byId, parent, leaves)
}

private fun distToSegment(px: Double, py: Double, x1: Double, y1: Double, x2: Double, y2: Double): Double {
    val dx = x2 - x1
    val dy = y2 - y1
    val lengthSq = dx * dx + dy * dy
    val t = if (lengthSq == 0.0) 0.0 else ((px - x1) * dx + (py - y1) * dy) / lengthSq
    val clamped = t.coerceIn(0.0, 1.0)
    return hypot(px - (x1 + clamped * dx), py - (y1 + clamped * dy))
}

@Composable
fun TreeScreen(modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val density = LocalDensity.current
    val densityValue = density.density
    val scope = rememberCoroutineScope()
    val prefs = remember { ctx.getSharedPreferences("spice_tree_prefs", Context.MODE_PRIVATE) }

    // ------------------------------------------------------------ state
    var inputText by remember { mutableStateOf("") }
    var doc by remember { mutableStateOf<TreeDoc?>(null) }
    var settings by remember { mutableStateOf(com.spice.nativeapp.core.defaultSettings()) }
    var metadata by remember { mutableStateOf(emptyMetadata()) }
    var collapsed by remember { mutableStateOf(setOf<String>()) }
    var selectedId by remember { mutableStateOf<String?>(null) }
    var searchQuery by remember { mutableStateOf("") }
    var searchPos by remember { mutableStateOf(0) }
    var focusGroup by remember { mutableStateOf<String?>(null) }
    var title by remember { mutableStateOf("") }
    var caption by remember { mutableStateOf("") }
    var zoom by remember { mutableStateOf(1.0) }
    var panX by remember { mutableStateOf(0.0) }
    var panY by remember { mutableStateOf(0.0) }
    var metaText by remember { mutableStateOf("") }
    var metaError by remember { mutableStateOf<Int?>(null) }
    var parseError by remember { mutableStateOf<NewickParseError?>(null) }
    var compareText by remember { mutableStateOf("") }
    var compareName by remember { mutableStateOf("") }
    var compareError by remember { mutableStateOf<String?>(null) }
    var comparison by remember { mutableStateOf<TreeComparison?>(null) }
    var autosaveAt by remember { mutableStateOf<Long?>(null) }
    var ready by remember { mutableStateOf(false) }
    var pendingFit by remember { mutableStateOf(false) }
    var pendingFocus by remember { mutableStateOf<String?>(null) }
    var launchComparePicker by remember { mutableStateOf(false) }
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }

    fun toast(msg: String, long: Boolean = false) {
        Toast.makeText(ctx, msg, if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()
    }

    // ------------------------------------------------------------ derived

    val resolvedMeta = remember(metadata) { com.spice.nativeapp.core.resolveMetadata(metadata) }

    val treeIndex = remember(doc) { if (doc != null) treeIndexOf(doc!!) else null }
    val indexById = treeIndex?.first
    val indexParent = treeIndex?.second
    val indexLeaves = treeIndex?.third ?: emptyList<TreeNode>()

    val layout: TreeLayout? = remember(doc, settings, title, caption, collapsed, resolvedMeta) {
        doc?.let { d ->
            com.spice.nativeapp.core.computeLayout(
                com.spice.nativeapp.core.LayoutInput(
                    root = d.root,
                    mode = settings.mode,
                    fontSize = settings.fontSize,
                    labelAngle = settings.labelAngle,
                    showBranchLengths = settings.showBranchLengths,
                    showScaleBar = settings.showScaleBar,
                    title = title,
                    caption = caption,
                    collapsed = collapsed,
                    metadata = resolvedMeta
                )
            )
        }
    }

    val searchMatches = remember(searchQuery, treeIndex) {
        val q = searchQuery.trim().lowercase()
        if (q.isEmpty() || treeIndex == null) emptyList()
        else indexLeaves.filter { it.name.lowercase().contains(q) || (it.comment ?: "").lowercase().contains(q) }
    }

    val searchStatus = when {
        searchQuery.isBlank() -> ""
        searchMatches.isEmpty() -> "无匹配"
        else -> {
            val i = min(searchPos, searchMatches.size - 1)
            "第 ${i + 1}/${searchMatches.size} 个"
        }
    }

    val pathIds = remember(selectedId, treeIndex) {
        val ids = mutableSetOf<String>()
        var cursor = selectedId
        while (cursor != null) {
            ids.add(cursor)
            cursor = indexParent?.get(cursor)
        }
        ids
    }

    val selectedLayoutNode = selectedId?.let { layout?.byId[it] }
    val selectedTreeNode = selectedId?.let { indexById?.get(it) }
    val selectedIsInternal = (selectedTreeNode?.children?.size ?: 0) > 0
    val selectedCollapsed = selectedId?.let { it in collapsed } ?: false

    val legend = remember(resolvedMeta, treeIndex) {
        val counts = LinkedHashMap<String, Int>()
        for (leaf in indexLeaves) {
            val group = resolvedMeta.byLeaf[leaf.name]?.group
            if (group.isNullOrEmpty()) continue
            counts[group] = (counts[group] ?: 0) + 1
        }
        resolvedMeta.groupOrder.filter { it in counts }.map { g ->
            Triple(g, resolvedMeta.groupColors[g] ?: "#8b96b5", counts[g] ?: 0)
        }
    }
    val ungroupedCount = indexLeaves.count {
        resolvedMeta.byLeaf[it.name]?.group.isNullOrEmpty()
    }

    val totalNodes = indexById?.size ?: 0
    val totalLeaves = indexLeaves.size

    val support = remember(doc, settings.supportThreshold) {
        doc?.let { supportSummary(it.root, settings.supportThreshold, 10) }
            ?: com.spice.nativeapp.core.emptySupportSummary(10)
    }
    val supportBinMax = max(1, support.bins.maxOf { it.total })

    val errorSnippet = remember(parseError, inputText) {
        val e = parseError ?: return@remember null
        val position = max(0, min(inputText.length, e.position))
        val start = max(0, position - 26)
        val end = min(inputText.length, position + 26)
        Triple(
            inputText.substring(start, position),
            if (position < inputText.length) inputText[position].toString() else "",
            inputText.substring(min(position + 1, inputText.length), end)
        )
    }

    val exportMarkup = remember(layout, settings, selectedId, pathIds, searchMatches, focusGroup, title, caption) {
        layout?.let { l ->
            renderTreeSvg(
                l, settings,
                RenderState(
                    selectedId = selectedId,
                    hoveredId = null,
                    pathIds = pathIds,
                    hoverPathIds = emptySet(),
                    searchMatches = searchMatches.map { it.id }.toSet(),
                    focusGroup = focusGroup,
                    collapseLabel = "折叠分支",
                    expandLabel = "展开分支",
                    unitLabel = "替换/位点",
                    unnamedLabel = "未命名"
                ),
                title, caption
            )
        }
    }

    // ------------------------------------------------------------ helpers

    fun buildProject(): TreeProject? {
        val d = doc ?: return null
        return TreeProject(
            format = "spice-tree",
            version = 1,
            originalNewick = d.originalNewick,
            tree = nodeToSerialized(d.root),
            rooted = d.rooted,
            sourceName = d.sourceName,
            collapsedIds = collapsed.toList(),
            settings = settings,
            metadata = TreeMetadata(metadata.entries, metadata.paletteId),
            title = title,
            caption = caption,
            savedAt = isoNow()
        )
    }

    fun errorMessage(error: NewickParseError): String = when (error.code) {
        NewickErrorCode.EMPTY -> "输入为空，请提供 Newick 字符串"
        NewickErrorCode.UNCLOSED -> "缺少右括号"
        NewickErrorCode.BAD_LENGTH -> "枝长格式无效"
        NewickErrorCode.UNEXPECTED_COMMA -> "逗号位置无效"
        NewickErrorCode.UNEXPECTED_END -> "输入意外结束"
        NewickErrorCode.BAD_QUOTED -> "引号标签未闭合"
        NewickErrorCode.BAD_COMMENT -> "注释未闭合"
        NewickErrorCode.NO_ROOT -> "未找到树根"
        else -> "遇到意外字符"
    }

    fun clearSessionStorage() {
        prefs.edit().remove(SESSION_KEY).apply()
    }

    fun fmtLength(value: Double?): String = value?.let { formatNumber(it) } ?: "缺失"

    fun applyProject(project: TreeProject) {
        doc = TreeDoc(
            root = serializedToTree(project.tree),
            originalNewick = project.originalNewick,
            rooted = project.rooted,
            sourceName = project.sourceName
        )
        settings = project.settings
        metadata = project.metadata
        collapsed = project.collapsedIds.toSet()
        title = project.title
        caption = project.caption
        inputText = project.originalNewick
        metaText = if (project.metadata.entries.isNotEmpty()) metadataToCsv(project.metadata) else ""
        selectedId = null
        focusGroup = null
        parseError = null
        metaError = null
        autosaveAt = null
        comparison = null
        compareError = null
        pendingFit = true
    }

    fun loadNewick(text: String, sourceName: String? = null, notify: Boolean = true): Boolean {
        val clean = text.trim()
        if (clean.isEmpty()) {
            parseError = NewickParseError(NewickErrorCode.EMPTY, 0, "")
            if (notify) toast("无法解析 Newick：输入为空，请提供 Newick 字符串")
            return false
        }
        inputText = clean
        try {
            val parsed: ParsedTree = parseNewick(clean)
            doc = TreeDoc(parsed.root, clean, parsed.rooted, sourceName)
            collapsed = emptySet()
            selectedId = null
            focusGroup = null
            parseError = null
            autosaveAt = null
            comparison = null
            compareError = null
            pendingFit = true
            if (notify) {
                toast(if (sourceName != null) "树已加载：$sourceName" else "树已加载")
            }
            return true
        } catch (e: NewickParseError) {
            parseError = e
            if (notify) toast("无法解析 Newick：${errorMessage(e)} · 位置 ${e.position + 1}")
            return false
        } catch (e: Exception) {
            if (notify) toast("无法解析 Newick：${e.message ?: e}")
            return false
        }
    }

    fun applyMetadataText(text: String, notify: Boolean = true): Boolean {
        return try {
            val entries = parseMetadataTable(text)
            val leaves = doc?.let { treeIndexOf(it).third.map { n -> n.name }.toSet() } ?: emptySet()
            val matched = entries.count { it.leaf in leaves }
            metadata = TreeMetadata(entries, metadata.paletteId)
            metaError = null
            focusGroup = null
            if (notify) {
                if (matched == 0) toast("未找到匹配的叶")
                else toast("已为 $matched 个叶应用元数据")
            }
            true
        } catch (e: MetadataParseError) {
            metaError = e.row
            if (notify) toast("元数据表无效：第 ${e.row} 行：至少需要两列。")
            false
        } catch (e: Exception) {
            if (notify) toast("元数据表无效")
            false
        }
    }
    fun loadExample() {
        if (!loadNewick(EXAMPLE_NEWICK, "example.nwk", false)) return
        metaText = EXAMPLE_METADATA
        applyMetadataText(EXAMPLE_METADATA, false)
        toast("树已加载：example.nwk")
    }

    fun toggleCollapse(id: String) {
        val d = doc ?: return
        val index = treeIndexOf(d)
        val node = index.first[id] ?: return
        if (node.children.isEmpty() || node.id == d.root.id) return
        val next = collapsed.toMutableSet()
        if (id in next) {
            next.remove(id)
        } else {
            next.add(id)
            val sel = selectedId
            if (sel != null && sel != id) {
                var cursor: String? = index.second[sel]
                var under = false
                while (cursor != null) {
                    if (cursor == id) { under = true; break }
                    cursor = index.second[cursor]
                }
                if (under) selectedId = id
            }
        }
        collapsed = next
    }

    fun runComparison(text: String?) {
        val d = doc
        if (d == null) {
            compareError = "请先加载一棵树。"
            comparison = null
            return
        }
        val source = (text ?: compareText).trim()
        if (source.isEmpty()) {
            launchComparePicker = true
            return
        }
        try {
            val parsed = parseNewick(source)
            comparison = compareTrees(d.root, parsed.root)
            compareError = null
            if (comparison!!.identical) toast("两棵树在共享叶集上完全一致。")
        } catch (e: Exception) {
            comparison = null
            compareError = "无法解析比较树。"
        }
    }

    fun fileBase(): String {
        val cleaned = title.trim()
            .replace(Regex("[^\\p{L}\\p{N}._-]+"), "_")
            .replace(Regex("^_+|_+$"), "")
            .take(60)
        return cleaned.ifEmpty { "tree_figure" }
    }

    fun exportFile(name: String, bytes: ByteArray) {
        scope.launch(Dispatchers.IO) {
            val result = runCatching {
                val dir = File(ctx.getExternalFilesDir(null), "exports").apply { mkdirs() }
                val f = File(dir, name)
                f.writeBytes(bytes)
                f.absolutePath
            }
            withContext(Dispatchers.Main) {
                result.fold(
                    onSuccess = { path -> toast("已导出 $path", long = true) },
                    onFailure = { toast("导出失败") }
                )
            }
        }
    }

    fun exportSvg(markup: String) {
        exportFile("${fileBase()}.svg", markup.toByteArray())
    }

    fun exportPng(dpi: Int) {
        val l = layout ?: return
        scope.launch(Dispatchers.IO) {
            val result = runCatching {
                val png = renderTreePng(l, settings, RenderState.export(), title, caption, dpi)
                if (png.reduced) {
                    withContext(Dispatchers.Main) { toast("图像在 $dpi dpi 下过大，已降低比例导出。") }
                }
                png.bytes
            }
            withContext(Dispatchers.Main) {
                result.fold(
                    onSuccess = { bytes -> exportFile("${fileBase()}_${dpi}dpi.png", bytes) },
                    onFailure = { toast("导出失败") }
                )
            }
        }
    }

    fun exportNewick(original: Boolean) {
        val d = doc ?: return
        val text = if (original) d.originalNewick
        else toNewick(d.root, com.spice.nativeapp.core.SerializeOptions(collapsed))
        val suffix = if (original) "original" else "edited"
        exportFile("${fileBase()}_$suffix.nwk", text.toByteArray())
    }

    fun exportProject() {
        val project = buildProject() ?: return
        exportFile("${fileBase()}.tree.json", serializeProject(project).toByteArray())
    }

    fun exportBundle(exportMarkup: String) {
        val project = buildProject() ?: return
        val d = doc ?: return
        val l = layout ?: return
        scope.launch(Dispatchers.IO) {
            val result = runCatching {
                val png = renderTreePng(l, settings, RenderState.export(), title, caption, 300)
                val stem = fileBase()
                val files = listOf(
                    ReproFile("$stem.svg", "image/svg+xml", exportMarkup.toByteArray()),
                    ReproFile("${stem}_300dpi.png", "image/png", png.bytes),
                    ReproFile("${stem}_edited.nwk", "text/plain",
                        toNewick(d.root, com.spice.nativeapp.core.SerializeOptions(collapsed)).toByteArray()),
                    ReproFile("${stem}_original.nwk", "text/plain", d.originalNewick.toByteArray())
                )
                val params = treeParameters(project).put("comparison",
                    comparison?.let { c ->
                        com.spice.nativeapp.core.JsonObj()
                            .put("rf", c.rf)
                            .put("maxRf", c.maxRf)
                            .put("normalizedRf", kotlin.math.round(c.normalizedRf * 10000.0) / 10000.0)
                            .put("sharedSplits", c.sharedSplits)
                            .put("uniqueCurrent", c.uniqueA)
                            .put("uniqueOther", c.uniqueB)
                            .put("sharedLeaves", c.sharedLeaves.size)
                            .put("onlyCurrentLeaves", com.spice.nativeapp.core.JsonArr(c.leavesOnlyA))
                            .put("onlyOtherLeaves", com.spice.nativeapp.core.JsonArr(c.leavesOnlyB))
                    })
                val version = runCatching {
                    @Suppress("DEPRECATION")
                    ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName
                }.getOrNull() ?: "1.7.18"
                buildReproBundle(
                    tool = "tree",
                    title = treeDocName(project),
                    files = files,
                    parameters = params,
                    methods = treeMethods(project),
                    projectJson = serializeProject(project),
                    softwareVersion = version ?: "1.7.18"
                )
            }
            withContext(Dispatchers.Main) {
                result.fold(
                    onSuccess = { (bytes, fileName) -> exportFile(fileName, bytes) },
                    onFailure = { toast("可复现包导出失败。") }
                )
            }
        }
    }

    fun forgetSession() {
        clearSessionStorage()
        doc = null
        collapsed = emptySet()
        selectedId = null
        focusGroup = null
        metadata = emptyMetadata()
        metaText = ""
        metaError = null
        title = ""
        caption = ""
        inputText = ""
        parseError = null
        autosaveAt = null
        comparison = null
        compareError = null
        compareName = ""
        toast("会话已清除")
    }

    // ------------------------------------------------------------ pickers

    val uploadPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching { readTreeFile(ctx, uri) }
            .onSuccess { text -> loadNewick(text, uriDisplayName(ctx, uri)) }
            .onFailure { if (it is FileTooLargeException) toast("文件过大（上限 20 MB）") else toast("无法读取文件") }
    }
    val metaPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching { readTreeFile(ctx, uri) }
            .onSuccess { text ->
                metaText = text
                applyMetadataText(text)
            }
            .onFailure { if (it is FileTooLargeException) toast("文件过大（上限 20 MB）") else toast("无法读取文件") }
    }
    val projectPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching { readTreeFile(ctx, uri) }
            .onSuccess { text ->
                try {
                    applyProject(parseProjectJson(text))
                    toast("树已加载：${uriDisplayName(ctx, uri) ?: ""}")
                } catch (e: Exception) {
                    toast("无法导入项目：项目文件无效")
                }
            }
            .onFailure { if (it is FileTooLargeException) toast("文件过大（上限 20 MB）") else toast("无法读取文件") }
    }
    val comparePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching { readTreeFile(ctx, uri) }
            .onSuccess { text ->
                compareText = text
                compareName = uriDisplayName(ctx, uri) ?: ""
                runComparison(text)
            }
            .onFailure {
                compareError = "无法解析比较树。"
                comparison = null
            }
    }
    LaunchedEffect(launchComparePicker) {
        if (launchComparePicker) {
            comparePicker.launch(arrayOf("*/*"))
            launchComparePicker = false
        }
    }

    // ------------------------------------------------------------ view helpers

    fun fitView(l: TreeLayout) {
        val target = 0.92
        zoom = target
        panX = l.width * (1 - target) / 2
        panY = l.height * (1 - target) / 2
    }

    fun focusNode(l: TreeLayout, id: String, targetZoom: Double = 2.6) {
        val node = l.byId[id] ?: return
        zoom = targetZoom.coerceIn(MIN_ZOOM.toDouble(), MAX_ZOOM)
        panX = l.width / 2 - zoom * node.x
        panY = l.height / 2 - zoom * node.y
    }

    fun zoomCenter(factor: Double) {
        if (canvasSize == IntSize.Zero) return
        val w = canvasSize.width / densityValue
        val h = canvasSize.height / densityValue
        val ux = (w / 2 - panX) / zoom
        val uy = (h / 2 - panY) / zoom
        val next = (zoom * factor).coerceIn(MIN_ZOOM.toDouble(), MAX_ZOOM)
        if (abs(next - zoom) < 0.00001) return
        val ratio = next / zoom
        panX = ux - (ux - panX) * ratio
        panY = uy - (uy - panY) * ratio
        zoom = next
    }

    fun gotoMatch(delta: Int) {
        val d = doc ?: return
        if (searchMatches.isEmpty()) return
        searchPos = ((searchPos + delta) % searchMatches.size + searchMatches.size) % searchMatches.size
        val leaf = searchMatches[searchPos]
        selectedId = leaf.id
        collapsed = expandAncestors(d.root, leaf.id, collapsed)
        pendingFocus = leaf.id
    }

    fun hitTest(l: TreeLayout, ux: Double, uy: Double) {
        val measurePaint = android.graphics.Paint().apply {
            textSize = settings.fontSize.toFloat()
            typeface = android.graphics.Typeface.MONOSPACE
        }
        // 1) 标签（select）
        for (node in l.nodes.asReversed()) {
            val showName = node.isLeaf || node.isCollapsed || node.name.isNotEmpty()
            if (!showName) continue
            var display = node.name.ifEmpty { if (node.isCollapsed) "" else "未命名" }
            if (node.isCollapsed) {
                val badge = "[${node.leafCount}]"
                display = if (display.isNotEmpty()) "$display $badge" else badge
            }
            if (display.isEmpty()) continue
            val tx = node.x + 6
            val w = measurePaint.measureText(display).toDouble()
            val h = settings.fontSize.toDouble()
            if (ux in tx - 2..tx + w + 2 && uy in node.y - h / 2 - 2..node.y + h / 2 + 2) {
                selectedId = node.id
                return
            }
        }
        // 2) 节点圆点 / 折叠楔（内部节点 = 折叠/展开切换；叶 = select）
        for (node in l.nodes.asReversed()) {
            val radius = if (node.isLeaf || node.isCollapsed) {
                max(1.8, settings.branchWidth * 0.8 + 1.1)
            } else {
                max(2.6, settings.branchWidth + 1.6)
            }
            val hitR = if (node.isCollapsed) max(radius + 6.0, 14.0) else radius + 8.0
            if (hypot(ux - node.x, uy - node.y) <= hitR) {
                val tn = indexById?.get(node.id)
                if (tn != null && tn.children.isNotEmpty() && node.id != doc?.root?.id) {
                    toggleCollapse(node.id)
                } else {
                    selectedId = node.id
                }
                return
            }
        }
        // 3) 分支（select；命中阈值与 SVG branch-hit stroke-width 一致）
        val circular = l.mode == LayoutMode.CIRCULAR
        for (node in l.nodes) {
            val pid = node.parentId ?: continue
            val parent = l.byId[pid] ?: continue
            val width = settings.branchWidth + (if (node.id in pathIds) 1.1 else 0.0)
            val threshold = max(10.0, width + 8) / 2
            val hit = if (circular) {
                val sx = l.centerX + node.parentRadius * kotlin.math.cos(node.angle)
                val sy = l.centerY + node.parentRadius * kotlin.math.sin(node.angle)
                distToSegment(ux, uy, sx, sy, node.x, node.y) <= threshold
            } else {
                distToSegment(ux, uy, parent.x, parent.y, node.x, parent.y) <= threshold ||
                    distToSegment(ux, uy, node.x, parent.y, node.x, node.y) <= threshold
            }
            if (hit) {
                selectedId = node.id
                return
            }
        }
        // 4) 空白：清除选择
        selectedId = null
    }

    // ------------------------------------------------------------ effects

    // 会话恢复（对应 main onMount loadSession）
    LaunchedEffect(Unit) {
        val raw = prefs.getString(SESSION_KEY, null)
        if (raw != null) {
            try {
                applyProject(parseProjectJson(raw))
                toast("已恢复上次的树会话")
            } catch (e: Exception) {
                toast("无法恢复会话")
            }
        }
        ready = true
    }

    // 视图适配 / 节点聚焦（layout 就绪后生效）
    LaunchedEffect(layout, pendingFit, pendingFocus) {
        val l = layout ?: return@LaunchedEffect
        val focus = pendingFocus
        if (focus != null && l.byId.containsKey(focus)) {
            focusNode(l, focus, 2.8)
            pendingFocus = null
            pendingFit = false
            return@LaunchedEffect
        }
        if (pendingFit) {
            fitView(l)
            pendingFit = false
        }
    }

    // 自动保存（对应 main 700ms debounce saveSession）
    LaunchedEffect(doc, settings, metadata, collapsed, title, caption, ready) {
        if (!ready) return@LaunchedEffect
        val project = buildProject()
        if (project == null) {
            clearSessionStorage()
            return@LaunchedEffect
        }
        delay(700)
        prefs.edit().putString(SESSION_KEY, serializeProject(project)).apply()
        autosaveAt = System.currentTimeMillis()
    }

    // ------------------------------------------------------------ UI

    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // ============================== 树输入 ==============================
        SectionCard("树输入") {
            OutlinedTextField(
                value = inputText,
                onValueChange = {
                    inputText = it
                    if (parseError != null) parseError = null
                },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("粘贴 Newick 文本，或拖入 .nwk 文件...", fontSize = 11.sp) },
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                minLines = 3
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { loadNewick(inputText, null, true) }, enabled = inputText.isNotBlank()) {
                    Text("加载树", fontSize = 11.sp)
                }
                OutlinedButton(onClick = { uploadPicker.launch(arrayOf("*/*")) }) {
                    Text("上传文件", fontSize = 11.sp)
                }
                OutlinedButton(onClick = { loadExample() }) {
                    Text("加载示例", fontSize = 11.sp)
                }
            }
            Hint("支持引号标签、枝长、支持度与 [注释]。")
            if (parseError != null && errorSnippet != null) {
                val (before, at, after) = errorSnippet
                Column(
                    Modifier
                        .fillMaxWidth()
                        .border(1.dp, Color(0xFFFF5D5D), RoundedCornerShape(4.dp))
                        .padding(8.dp)
                ) {
                    Text("无法解析 Newick", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color(0xFFFF5D5D))
                    Text(
                        buildAnnotatedString {
                            append(errorMessage(parseError!!))
                            withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append("  位置 ${parseError!!.position + 1}") }
                        },
                        fontSize = 10.sp
                    )
                    Text(
                        buildAnnotatedString {
                            withStyle(SpanStyle(fontFamily = FontFamily.Monospace)) { append(before) }
                            withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = Color(0xFFFF5D5D), color = Color(0xFF0B0E14))) {
                                append(if (at.isEmpty()) " " else at)
                            }
                            withStyle(SpanStyle(fontFamily = FontFamily.Monospace)) { append(after) }
                        },
                        fontSize = 10.sp
                    )
                }
            }
        }

        // ============================== 设置 ==============================
        SectionCard("设置") {
            FieldLabel("布局")
            SegRow(
                options = listOf("枝长树", "分支图", "环形"),
                selected = when (settings.mode) {
                    LayoutMode.PHYLOGRAM -> 0; LayoutMode.CLADOGRAM -> 1; LayoutMode.CIRCULAR -> 2
                },
                onSelect = { i ->
                    settings = settings.copy(
                        mode = when (i) {
                            0 -> LayoutMode.PHYLOGRAM; 1 -> LayoutMode.CLADOGRAM; else -> LayoutMode.CIRCULAR
                        }
                    )
                    pendingFit = true
                }
            )
            if (layout?.fallbackCladogram == true && settings.mode != LayoutMode.CLADOGRAM) {
                WarnNote("没有可用的枝长，已按等枝长分支图绘制。")
            }
            SliderRow("字号", settings.fontSize, 6.0..24.0, 1.0) { settings = settings.copy(fontSize = it) }
            SliderRow("枝宽", settings.branchWidth, 0.5..5.0, 0.5) { settings = settings.copy(branchWidth = it) }
            SliderRow(
                "标签角度", settings.labelAngle, 0.0..90.0, 5.0,
                enabled = settings.mode != LayoutMode.CIRCULAR,
                suffix = "°"
            ) { settings = settings.copy(labelAngle = it) }
            if (settings.mode == LayoutMode.CIRCULAR) Hint("仅矩形布局")
            SliderRow("支持度阈值", settings.supportThreshold, 0.0..100.0, 1.0) { settings = settings.copy(supportThreshold = it) }
            if (settings.supportThreshold > 0) Hint("已隐藏低于阈值的支持度")
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                CheckRowItem("显示枝长", settings.showBranchLengths) { settings = settings.copy(showBranchLengths = it) }
                CheckRowItem("显示支持度", settings.showSupport) { settings = settings.copy(showSupport = it) }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                CheckRowItem("显示节点", settings.showNodeDots) { settings = settings.copy(showNodeDots = it) }
                CheckRowItem("显示比例尺", settings.showScaleBar) { settings = settings.copy(showScaleBar = it) }
            }
            FieldLabel("主题")
            SegRow(
                options = listOf("深色", "浅色"),
                selected = if (settings.theme == TreeTheme.DARK) 0 else 1,
                onSelect = {
                    settings = settings.copy(theme = if (it == 0) TreeTheme.DARK else TreeTheme.LIGHT)
                }
            )
            OutlinedButton(onClick = {
                settings = com.spice.nativeapp.core.defaultSettings()
                pendingFit = true
            }) { Text("重置设置", fontSize = 11.sp) }
        }

        // ============================== 美化 ==============================
        SectionCard("美化") {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    doc?.let { d -> doc = d.copy(root = ladderizeTree(d.root, false)); toast("已完成阶梯化") }
                }, enabled = doc != null, modifier = Modifier.weight(1f)) { Text("阶梯化（升序）", fontSize = 10.sp) }
                OutlinedButton(onClick = {
                    doc?.let { d -> doc = d.copy(root = ladderizeTree(d.root, true)); toast("已完成阶梯化") }
                }, enabled = doc != null, modifier = Modifier.weight(1f)) { Text("阶梯化（降序）", fontSize = 10.sp) }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    doc?.let { d -> doc = d.copy(root = sortTreeAlphabetically(d.root)); toast("已重排叶顺序") }
                }, enabled = doc != null, modifier = Modifier.weight(1f)) { Text("叶标签 A 到 Z", fontSize = 10.sp) }
                OutlinedButton(onClick = {
                    doc?.let { d -> doc = d.copy(root = sortTreeByGroup(d.root, resolvedMeta)); toast("已重排叶顺序") }
                }, enabled = doc != null, modifier = Modifier.weight(1f)) { Text("按分组排序", fontSize = 10.sp) }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    val d = doc ?: return@OutlinedButton
                    val rooted = midpointRoot(d.root)
                    if (rooted == null) {
                        toast("中点定根至少需要一条正枝长。")
                    } else {
                        doc = d.copy(root = rooted, rooted = true)
                        selectedId = null
                        toast("已应用中点定根")
                        pendingFit = true
                    }
                }, enabled = doc != null, modifier = Modifier.weight(1f)) { Text("中点定根", fontSize = 10.sp) }
                OutlinedButton(onClick = {
                    doc?.let { d -> loadNewick(d.originalNewick, d.sourceName) }
                }, enabled = doc != null, modifier = Modifier.weight(1f)) { Text("恢复到解析树", fontSize = 10.sp) }
            }
            if (doc != null) {
                Text(
                    if (doc!!.rooted) "有根" else "无根",
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .border(1.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(8.dp))
                        .padding(horizontal = 8.dp, vertical = 2.dp)
                )
            }
        }

        // ============================== 图与图注 ==============================
        SectionCard("图与图注") {
            FieldLabel("图标题")
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("树标题", fontSize = 11.sp) },
                textStyle = MaterialTheme.typography.bodySmall
            )
            FieldLabel("图注")
            OutlinedTextField(
                value = caption,
                onValueChange = { caption = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("可选图注", fontSize = 11.sp) },
                textStyle = MaterialTheme.typography.bodySmall,
                minLines = 2
            )
        }

        // ============================== 导出 ==============================
        SectionCard("导出") {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { exportMarkup?.let { exportSvg(it) } },
                    enabled = doc != null, modifier = Modifier.weight(1f)
                ) { Text("SVG", fontSize = 10.sp) }
                OutlinedButton(onClick = { exportPng(300) }, enabled = doc != null, modifier = Modifier.weight(1f)) { Text("PNG 300 dpi", fontSize = 10.sp) }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { exportPng(600) }, enabled = doc != null, modifier = Modifier.weight(1f)) { Text("PNG 600 dpi", fontSize = 10.sp) }
                OutlinedButton(onClick = { exportNewick(false) }, enabled = doc != null, modifier = Modifier.weight(1f)) { Text("Newick（已编辑）", fontSize = 10.sp) }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { exportNewick(true) }, enabled = doc != null, modifier = Modifier.weight(1f)) { Text("Newick（原始）", fontSize = 10.sp) }
                OutlinedButton(onClick = { exportProject() }, enabled = doc != null, modifier = Modifier.weight(1f)) { Text("项目 JSON", fontSize = 10.sp) }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { exportMarkup?.let { exportBundle(it) } },
                    enabled = doc != null, modifier = Modifier.weight(1f)
                ) { Text("可复现包", fontSize = 10.sp) }
                OutlinedButton(onClick = { projectPicker.launch(arrayOf("*/*")) }, modifier = Modifier.weight(1f)) { Text("导入项目", fontSize = 10.sp) }
            }
            OutlinedButton(onClick = { forgetSession() }) { Text("清除会话", fontSize = 10.sp) }
            Hint("导出为包含真实文本的矢量 SVG。")
        }

        // ============================== 画布 ==============================
        SectionCard(null) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = {
                        searchQuery = it
                        searchPos = 0
                    },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("查找叶...", fontSize = 11.sp) },
                    textStyle = MaterialTheme.typography.bodySmall
                )
                if (searchQuery.isNotBlank()) {
                    Text(searchStatus, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton(
                        onClick = { gotoMatch(-1) },
                        enabled = searchMatches.isNotEmpty()
                    ) { Text("‹", fontSize = 16.sp) }
                    TextButton(
                        onClick = { gotoMatch(1) },
                        enabled = searchMatches.isNotEmpty()
                    ) { Text("›", fontSize = 16.sp) }
                }
            }
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (doc != null) {
                    Text(
                        if (doc!!.rooted) "有根" else "无根",
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .border(1.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(8.dp))
                            .padding(horizontal = 6.dp, vertical = 1.dp)
                    )
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { layout?.let { fitView(it) } }) { Text("适应视图", fontSize = 10.sp) }
                TextButton(onClick = { zoomCenter(1 / 1.25) }) { Text("缩小", fontSize = 10.sp) }
                Text("缩放 ${(zoom * 100).toInt()}%", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = { zoomCenter(1.25) }) { Text("放大", fontSize = 10.sp) }
                TextButton(onClick = { layout?.let { fitView(it) } }) { Text("重置缩放", fontSize = 10.sp) }
            }

            val l = layout
            if (l != null) {
                val renderState = RenderState(
                    selectedId = selectedId,
                    hoveredId = null,
                    pathIds = pathIds,
                    hoverPathIds = emptySet(),
                    searchMatches = searchMatches.map { it.id }.toSet(),
                    focusGroup = focusGroup,
                    collapseLabel = "折叠分支",
                    expandLabel = "展开分支",
                    unitLabel = "替换/位点",
                    unnamedLabel = "未命名"
                )
                val themeBg = Color(android.graphics.Color.parseColor(THEME_COLORS[settings.theme]!!.background))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(360.dp)
                        .clipToBounds()
                        .background(themeBg)
                        .onSizeChanged { canvasSize = it }
                        .pointerInput(Unit) {
                            awaitEachGesture {
                                awaitFirstDown(requireUnconsumed = false)
                                do {
                                    val event = awaitPointerEvent()
                                    val zoomChange = event.calculateZoom()
                                    val panChange = event.calculatePan()
                                    val centroid = event.calculateCentroid()
                                    if (zoomChange != 1f || panChange != androidx.compose.ui.geometry.Offset.Zero) {
                                        val cx = (centroid.x / densityValue - panX) / zoom
                                        val cy = (centroid.y / densityValue - panY) / zoom
                                        val next = (zoom * zoomChange).coerceIn(MIN_ZOOM.toDouble(), MAX_ZOOM)
                                        val ratio = next / zoom
                                        panX = cx - (cx - panX) * ratio
                                        panY = cy - (cy - panY) * ratio
                                        zoom = next
                                        panX += panChange.x / densityValue / zoom
                                        panY += panChange.y / densityValue / zoom
                                    }
                                } while (event.changes.any { it.pressed })
                            }
                        }
                        .pointerInput(l, settings) {
                            detectTapGestures { offset ->
                                val ux = (offset.x / densityValue - panX) / zoom
                                val uy = (offset.y / densityValue - panY) / zoom
                                hitTest(l, ux, uy)
                            }
                        }
                ) {
                    Canvas(Modifier.fillMaxSize()) {
                        val c = drawContext.canvas.nativeCanvas
                        c.save()
                        c.scale(densityValue, densityValue)
                        c.translate(panX.toFloat(), panY.toFloat())
                        c.scale(zoom.toFloat(), zoom.toFloat())
                        drawTreeToCanvas(c, l, settings, renderState, title, caption)
                        c.restore()
                    }
                }
                // 状态栏
                val statusText = buildList {
                    add("${l.leafCount} 个叶")
                    add("$totalNodes 个节点")
                    add("深度 ${formatNumber(l.maxDepth)}")
                    if (collapsed.isNotEmpty()) add("${collapsed.size} 个折叠")
                    if (l.fallbackCladogram && settings.mode != LayoutMode.CLADOGRAM) {
                        add("没有可用的枝长，已按等枝长分支图绘制。")
                    }
                }.joinToString(" · ")
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(statusText, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (autosaveAt != null) {
                        val cal = java.util.Calendar.getInstance().apply { timeInMillis = autosaveAt!! }
                        Text(
                            "已自动保存 %02d:%02d".format(
                                cal.get(java.util.Calendar.HOUR_OF_DAY),
                                cal.get(java.util.Calendar.MINUTE)
                            ),
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Hint("点击节点圆点可折叠或展开；点击枝可选择。")
            } else {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .height(220.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("尚未加载树", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    Text(
                        "粘贴 Newick 文本、上传 .nwk 文件，或从示例树开始。",
                        fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                        Button(onClick = { uploadPicker.launch(arrayOf("*/*")) }) { Text("上传文件", fontSize = 11.sp) }
                        OutlinedButton(onClick = { loadExample() }) { Text("加载示例", fontSize = 11.sp) }
                    }
                    Text(
                        "拖入 Newick 文件以加载",
                        fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                }
            }
        }

        // ============================== 元数据 ==============================
        SectionCard("元数据") {
            OutlinedTextField(
                value = metaText,
                onValueChange = {
                    metaText = it
                    if (metaError != null) metaError = null
                },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("leaf,group,color\nHomo_sapiens,Primates,#4cd6ff", fontSize = 11.sp) },
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                minLines = 3
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { applyMetadataText(metaText) }) { Text("应用元数据", fontSize = 11.sp) }
                OutlinedButton(onClick = { metaPicker.launch(arrayOf("*/*")) }) { Text("导入 CSV/TSV", fontSize = 11.sp) }
                OutlinedButton(onClick = {
                    metadata = emptyMetadata()
                    metaText = ""
                    metaError = null
                    focusGroup = null
                }) { Text("清除元数据", fontSize = 11.sp) }
            }
            if (metaError != null) {
                WarnNote("第 $metaError 行：至少需要两列。")
            }
            FieldLabel("调色板")
            SegRow(
                options = listOf("默认", "暖色", "冷色", "色盲友好"),
                selected = com.spice.nativeapp.core.PALETTE_IDS.indexOf(metadata.paletteId),
                onSelect = { i ->
                    metadata = TreeMetadata(
                        metadata.entries,
                        com.spice.nativeapp.core.PALETTE_IDS[i]
                    )
                }
            )
            Hint("表头可包含 leaf、group 与可选的 color 列。")
        }

        // ============================== 图例 ==============================
        SectionCard("图例") {
            if (focusGroup != null) {
                OutlinedButton(onClick = { focusGroup = null }) { Text("显示全部分组", fontSize = 10.sp) }
                Text("聚焦：$focusGroup", fontSize = 10.sp, color = MaterialTheme.colorScheme.primary)
            }
            if (legend.isNotEmpty() || ungroupedCount > 0) {
                legend.forEach { (group, color, count) ->
                    val active = focusGroup == group
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .then(
                                if (active) Modifier.border(
                                    1.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(4.dp)
                                ) else Modifier
                            )
                            .clickable { focusGroup = if (active) null else group }
                            .padding(horizontal = 4.dp, vertical = 2.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            Modifier
                                .size(10.dp)
                                .background(Color(android.graphics.Color.parseColor(color)))
                        )
                        Text(group, fontSize = 11.sp, modifier = Modifier.weight(1f))
                        Text("$count", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (ungroupedCount > 0) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 4.dp, vertical = 2.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(Modifier.size(10.dp).border(1.dp, MaterialTheme.colorScheme.outline))
                        Text("未分组", fontSize = 11.sp, modifier = Modifier.weight(1f))
                        Text("$ungroupedCount", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Hint("点击图例分组以聚焦其叶")
            } else {
                Hint("未加载元数据")
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("$totalLeaves 个叶", fontSize = 10.sp)
                Text("${legend.size} 个分组", fontSize = 10.sp)
            }
        }

        // ============================== 支持度分布 ==============================
        SectionCard("支持度分布") {
            if (support.withSupport > 0) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .height(64.dp)
                        .border(1.dp, MaterialTheme.colorScheme.outline)
                        .padding(3.dp),
                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    support.bins.forEach { bin ->
                        Column(
                            Modifier.weight(1f).fillMaxHeight(),
                            verticalArrangement = Arrangement.Bottom,
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            if (bin.filtered > 0) {
                                Box(
                                    Modifier
                                        .fillMaxWidth()
                                        .height(((bin.filtered * 1.0 / supportBinMax) * 44).dp)
                                        .background(Color(0x748B96B5))
                                )
                            }
                            if (bin.shown > 0) {
                                Box(
                                    Modifier
                                        .fillMaxWidth()
                                        .height(((bin.shown * 1.0 / supportBinMax) * 44).dp)
                                        .background(Color(0xFF4CD6FF))
                                )
                            }
                            Text("${bin.from.toInt()}", fontSize = 7.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                Hint(
                    "显示 ${support.shown}/${support.withSupport} 个内部节点（阈值 ${
                        com.spice.nativeapp.core.jsNum(support.threshold)
                    }%）"
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(8.dp).background(Color(0xFF4CD6FF)))
                        Text("显示", fontSize = 8.sp)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(8.dp).background(Color(0x748B96B5)))
                        Text("已筛选", fontSize = 8.sp)
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("最小 / 最大 / 平均", fontSize = 10.sp)
                    Text(
                        listOf(support.min, support.mean, support.max).joinToString(" / ") {
                            it?.let { v -> formatNumber(v) } ?: "无"
                        },
                        fontSize = 10.sp
                    )
                }
                Hint("内部节点支持度的 0-100 分箱，反映当前筛选阈值。")
            } else {
                Hint("没有内部支持度数值。")
            }
        }

        // ============================== 选择 ==============================
        SectionCard("选择") {
            if (selectedLayoutNode != null && selectedTreeNode != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (selectedIsInternal) "分支" else "叶",
                        fontSize = 10.sp, fontWeight = FontWeight.Bold
                    )
                    if (selectedLayoutNode.isCollapsed) {
                        Text(
                            "已折叠 ${selectedLayoutNode.leafCount} 个叶",
                            fontSize = 10.sp,
                            modifier = Modifier
                                .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp))
                                .padding(horizontal = 6.dp, vertical = 1.dp)
                        )
                    }
                }
                KV("名称", selectedLayoutNode.name.ifEmpty { "未命名" })
                KV("枝长", fmtLength(selectedLayoutNode.length))
                KV("支持度", selectedLayoutNode.support?.let { formatNumber(it) } ?: "无")
                if (selectedLayoutNode.group != null) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("分组", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                Modifier.size(10.dp).background(
                                    Color(
                                        android.graphics.Color.parseColor(
                                            selectedLayoutNode.color ?: "#8b96b5"
                                        )
                                    )
                                )
                            )
                            Text(selectedLayoutNode.group!!, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
                if (selectedIsInternal) {
                    KV("子节点", "${selectedTreeNode!!.children.size}")
                }
                KV("叶", "${selectedLayoutNode.leafCount}")
                if (selectedLayoutNode.comment != null) {
                    KV("注释", selectedLayoutNode.comment!!)
                }
                Hint("已高亮到根的路径")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (selectedIsInternal) {
                        OutlinedButton(onClick = {
                            selectedId?.let { toggleCollapse(it) }
                        }) {
                            Text(if (selectedCollapsed) "展开分支" else "折叠分支", fontSize = 10.sp)
                        }
                        OutlinedButton(
                            onClick = {
                                doc?.let { d -> doc = d.copy(root = rotateChildren(d.root, selectedId!!)) }
                            },
                            enabled = (selectedTreeNode?.children?.size ?: 0) >= 2
                        ) { Text("旋转子节点", fontSize = 10.sp) }
                    }
                    OutlinedButton(onClick = { selectedId = null }) { Text("清除选择", fontSize = 10.sp) }
                }
            } else {
                Hint("点击叶、枝或节点以查看信息。")
                Hint("点击节点圆点可折叠或展开；点击枝可选择。")
            }
        }

        // ============================== 树比较 ==============================
        SectionCard("树比较") {
            OutlinedTextField(
                value = compareText,
                onValueChange = {
                    compareText = it
                    if (compareError != null) compareError = null
                    if (compareName.isNotEmpty()) compareName = ""
                },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("在此粘贴第二棵 Newick 树", fontSize = 11.sp) },
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                minLines = 2
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { runComparison(null) }) { Text("比较树", fontSize = 11.sp) }
                OutlinedButton(onClick = { comparePicker.launch(arrayOf("*/*")) }) { Text("加载文件", fontSize = 11.sp) }
                if (comparison != null || compareText.isNotEmpty()) {
                    OutlinedButton(onClick = {
                        compareText = ""
                        compareName = ""
                        compareError = null
                        comparison = null
                    }) { Text("清除", fontSize = 11.sp) }
                }
            }
            if (compareError != null) WarnNote(compareError!!)
            val cmp = comparison
            if (cmp != null) {
                if (compareName.isNotEmpty()) Hint(compareName)
                FieldLabel("比较结果")
                if (cmp.identical) OkNote("两棵树在共享叶集上完全一致。")
                KV("Robinson-Foulds 距离", "${cmp.rf}")
                KV("归一化 RF", String.format("%.1f%%", cmp.normalizedRf * 100))
                KV("共享分裂", "${cmp.sharedSplits}")
                KV("当前树独有", "${cmp.uniqueA}")
                KV("比较树独有", "${cmp.uniqueB}")
                KV("共享叶", "${cmp.sharedLeaves.size}")
                KV("仅当前树", "${cmp.leavesOnlyA.size}")
                KV("仅比较树", "${cmp.leavesOnlyB.size}")
                Hint("当前树 ${cmp.splitsA} 个分裂，比较树 ${cmp.splitsB} 个分裂")
            } else {
                Hint("加载第二棵 Newick 树，在当前叶集上比较分裂。")
            }
        }

        // ============================== 键盘快捷键 ==============================
        SectionCard("键盘快捷键") {
            ShortcutRow("F", "适应视图")
            ShortcutRow("+ / -", "缩放")
            ShortcutRow("/", "查找")
            ShortcutRow("Esc", "清除选择")
            ShortcutRow("R", "旋转选中分支")
            ShortcutRow("C", "折叠或展开选中分支")
            ShortcutRow("L", "阶梯化")
        }
    }
}

// ------------------------------------------------------------ 小组件

@Composable
private fun SectionCard(title: String?, content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (title != null) {
                Text(
                    title,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 1.2.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            content()
        }
    }
}

@Composable
private fun Hint(text: String) {
    Text(text, fontSize = 9.sp, lineHeight = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun FieldLabel(text: String) {
    Text(text, fontSize = 10.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun WarnNote(text: String) {
    Text(
        text,
        fontSize = 10.sp,
        color = Color(0xFFFF9F1C),
        modifier = Modifier
            .border(1.dp, Color(0xFFFF9F1C), RoundedCornerShape(2.dp))
            .padding(horizontal = 6.dp, vertical = 4.dp)
    )
}

@Composable
private fun OkNote(text: String) {
    Text(
        text,
        fontSize = 10.sp,
        color = Color(0xFF53D769),
        modifier = Modifier
            .border(1.dp, Color(0xFF53D769), RoundedCornerShape(2.dp))
            .padding(horizontal = 6.dp, vertical = 4.dp)
    )
}

@Composable
private fun KV(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 12.dp))
    }
}

@Composable
private fun SegRow(options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        options.forEachIndexed { i, opt ->
            FilterChip(
                selected = i == selected,
                onClick = { onSelect(i) },
                label = { Text(opt, fontSize = 10.sp) }
            )
        }
    }
}

@Composable
private fun SliderRow(
    label: String,
    value: Double,
    range: ClosedFloatingPointRange<Double>,
    step: Double,
    enabled: Boolean = true,
    suffix: String = "",
    onChange: (Double) -> Unit
) {
    val steps = if (step > 0) (((range.endInclusive - range.start) / step).toInt() - 1).coerceAtLeast(0) else 0
    val shown = if (value == value.toInt().toDouble()) "${value.toInt()}" else "$value"
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("$shown$suffix", fontSize = 10.sp, fontWeight = FontWeight.Bold)
        }
        Slider(
            value = value.toFloat(),
            onValueChange = { onChange(it.toDouble()) },
            valueRange = range.start.toFloat()..range.endInclusive.toFloat(),
            steps = steps,
            enabled = enabled
        )
    }
}

@Composable
private fun CheckRowItem(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = checked, onCheckedChange = onChange)
        Text(label, fontSize = 10.sp)
    }
}

@Composable
private fun ShortcutRow(key: String, desc: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            key,
            fontSize = 9.sp,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier
                .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(3.dp))
                .padding(horizontal = 5.dp, vertical = 1.dp)
        )
        Text(desc, fontSize = 10.sp)
    }
}
