package com.spice.nativeapp.core

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream

/**
 * TreeTool.kt（main src/lib/tools/tree/ 1:1）算法语义测试。
 */
@RunWith(RobolectricTestRunner::class)
class TreeToolTest {

    // ── newick: 解析 ──

    @Test fun parseBasicTreeWithLengthsAndSupport() {
        val t = parseNewick("((A:0.021,B:0.024)100:0.041,C:0.068);")
        assertTrue(t.rooted)
        assertEquals(3, collectLeafNodes(t.root).size)
        val internal = t.root.children[0]
        assertEquals(2, internal.children.size)
        assertEquals(100.0, internal.support!!, 0.0)
        assertEquals(0.041, internal.length!!, 1e-12)
        assertEquals("A", internal.children[0].name)
        assertEquals("B", internal.children[1].name)
        assertEquals(0.021, internal.children[0].length!!, 1e-12)
        assertEquals(0.024, internal.children[1].length!!, 1e-12)
    }

    @Test fun parseQuotedLabelsWithSpaces() {
        val t = parseNewick("('Homo sapiens':1,'Pan troglodytes':2)90;")
        val names = collectLeafNodes(t.root).map { it.name }
        assertTrue(names.contains("Homo sapiens"))
        assertTrue(names.contains("Pan troglodytes"))
    }

    @Test fun parseCommentSupportAndRootedFlags() {
        // [&R] 指定有根；裸数字注释=支持度
        val r = parseNewick("[&R]((A,B[95],C)[80]);")
        assertTrue(r.rooted)
        val u = parseNewick("[&U](A,B,C);")
        assertTrue(!u.rooted)
        // 三分根默认无根
        val d = parseNewick("(A,B,C);")
        assertTrue(!d.rooted)
        // 二分根默认有根
        val two = parseNewick("(A,B);")
        assertTrue(two.rooted)
    }

    @Test fun parseErrorCodes() {
        try { parseNewick("   "); fail("EMPTY") } catch (e: NewickParseError) { assertEquals(NewickErrorCode.EMPTY, e.code) }
        try { parseNewick("((A,B)"); fail("UNCLOSED") } catch (e: NewickParseError) { assertEquals(NewickErrorCode.UNCLOSED, e.code) }
        try { parseNewick("(A:xx);"); fail("BAD_LENGTH") } catch (e: NewickParseError) { assertEquals(NewickErrorCode.BAD_LENGTH, e.code) }
        try { parseNewick("'open quoted"); fail("BAD_QUOTED") } catch (e: NewickParseError) { assertEquals(NewickErrorCode.BAD_QUOTED, e.code) }
        try { parseNewick(";"); fail("NO_ROOT") } catch (e: NewickParseError) { assertEquals(NewickErrorCode.NO_ROOT, e.code) }
        try { parseNewick("(A,B)garbage@#;(C,D);"); fail("UNEXPECTED") } catch (e: NewickParseError) { assertEquals(NewickErrorCode.UNEXPECTED, e.code) }
    }

    // ── newick: 序列化 ──

    @Test fun toNewickRoundTrip() {
        val src = "((A:0.021,B:0.024)100:0.041,C:0.068);"
        val t = parseNewick(src)
        val out = toNewick(t.root)
        val re = parseNewick(out)
        assertEquals(collectLeafNodes(t.root).map { it.name }, collectLeafNodes(re.root).map { it.name })
        assertEquals(nodeCount(t.root), nodeCount(re.root))
        // 未折叠时不带 [collapsed=...] 标记
        assertTrue(!out.contains("[collapsed"))
    }

    @Test fun toNewickEscapesAndCollapse() {
        val t = parseNewick("(A B,(C,D)90);")
        val out = toNewick(t.root)
        assertTrue(out.contains("'A B'"))
        assertEquals("('A B',(C,D)90);", out)
        // 折叠：内部节点序列化为叶形态，子代省略（main newick.ts 同构）
        val internalId = t.root.children[1].id
        val collapsedText = toNewick(t.root, SerializeOptions(collapsed = setOf(internalId)))
        val re = parseNewick(collapsedText)
        val collapsedNode = re.root.children.find { it.id == internalId }
        assertNotNull(collapsedNode)
        assertTrue(collapsedNode!!.children.isEmpty())
        assertEquals(3, nodeCount(re.root)) // 根 + 叶 A B + 折叠为叶形态的内部节点
        // 折叠后无名字节点以支持度作标签（main labelPart 语义），重解析为叶
        assertEquals("90", collapsedNode.name)
        assertNull(collapsedNode.support)
    }

    // ── treeops ──

    @Test fun treeOpsBasics() {
        val t = parseNewick("(((A,B)x,(C,D)y)z,E);")
        val root = t.root
        assertEquals(9, nodeCount(root)) // 含根 1 + 内部 3 + 叶 5
        val parent = buildParentMap(root)
        val a = collectLeafNodes(root)[0]
        assertEquals("x", parent[a.id]?.let { findNode(root, it)?.name })
        val xOpt = findNode(root, parent[a.id]!!)
        assertNotNull(xOpt)
        val x = xOpt!!
        assertTrue(isDescendantOf(x, a.id))
        // 路径 A→x→z→root（根名空串）
        assertEquals(listOf("A", "x", "z", ""),
            ancestorPath(root, a.id).map { id -> findNode(root, id)?.name ?: "" })
    }

    @Test fun ladderizeAndSortAndRotate() {
        fun leafNamesOf(node: TreeNode): List<String> =
            if (node.children.isEmpty()) listOf(node.name) else node.children.flatMap { leafNamesOf(it) }
        val t = parseNewick("((C,(A,B)ab)cd,D);")
        assertEquals(listOf("C", "A", "B", "D"), leafNamesOf(t.root))
        // 阶梯化（升序）：叶集合不变、节点数不变
        val laddered = ladderizeTree(t.root, false)
        assertEquals(leafNamesOf(t.root).sorted(), leafNamesOf(laddered).sorted())
        assertEquals(nodeCount(t.root), nodeCount(laddered))
        // 字母排序：深度优先叶序 A→D
        val sorted = sortTreeAlphabetically(t.root)
        assertEquals(listOf("A", "B", "C", "D"), leafNamesOf(sorted))
        // 旋转根的两个孩子：根子代交换 → 叶序反转
        val rotated = rotateChildren(t.root, t.root.id)
        assertEquals(listOf("D", "C", "A", "B"), leafNamesOf(rotated))
    }

    @Test fun midpointRootSplitsLongestEdge() {
        // A--B 长 10，中点定根后 A,B 各 5
        val t = parseNewick("(A:10,B:1);")
        val rootedOpt = midpointRoot(t.root)
        assertNotNull(rootedOpt)
        val rooted = rootedOpt!!
        assertEquals(2, rooted.children.size)
        val lengths = rooted.children.mapNotNull { it.length }.sorted()
        assertEquals(listOf(5.0, 5.0), lengths)
        // 无枝长 → null
        val noLen = parseNewick("(A,B);")
        assertNull(midpointRoot(noLen.root))
    }

    @Test fun expandAncestorsRemovesCollapsedOnPath() {
        val t = parseNewick("(((A,B)ab,(C,D)cd)top,E);")
        val ab = t.root.children[0].children[0].id
        val top = t.root.children[0].id
        // 含 ab/top 的折叠集：点击叶 A → 沿祖先链全部移除（id 需用节点真实 id）
        val aId = collectLeafNodes(t.root)[0].id
        val expanded = expandAncestors(t.root, aId, setOf(ab, top))
        assertTrue(ab !in expanded)
        assertTrue(top !in expanded)
        assertEquals(0, expanded.size)
    }

    // ── metadata ──

    @Test fun parseMetadataHeaderAliasesAndTsv() {
        val csv = "leaf,group,color\nA,Primates,#ff0000\nB,Primates\nC,Rodents,#00ff00"
        val entries = parseMetadataTable(csv)
        assertEquals(3, entries.size)
        assertEquals(MetaEntry("A", "Primates", "#ff0000"), entries[0])
        assertNull(entries[1].color)
        val tsv = "name\tclade\nA\tX\nB\tX"
        val tsvEntries = parseMetadataTable(tsv)
        assertEquals(listOf(MetaEntry("A", "X", null), MetaEntry("B", "X", null)), tsvEntries)
        // 无表头两列
        val noHeader = parseMetadataTable("A,X\nB,Y")
        assertEquals(listOf("X", "Y"), noHeader.map { it.group })
    }

    @Test fun parseMetadataDuplicateOverrideAndRowError() {
        val dup = "leaf,group\nA,G1\nA,G2\nB,G1"
        val entries = parseMetadataTable(dup)
        assertEquals(2, entries.size)
        assertEquals("G2", entries[0].group)
        try {
            parseMetadataTable("leaf,group\nA")
            fail("row error expected")
        } catch (e: MetadataParseError) {
            assertEquals(2, e.row)
        }
    }

    @Test fun resolveMetadataPaletteAndExplicitColors() {
        val meta = TreeMetadata(
            listOf(MetaEntry("A", "G1", null), MetaEntry("B", "G2", "#123456"), MetaEntry("C", "G3", "not-a-color")),
            PaletteId.DEFAULT
        )
        val resolved = resolveMetadata(meta)
        assertEquals(listOf("G1", "G2", "G3"), resolved.groupOrder)
        assertEquals(PALETTES[PaletteId.DEFAULT]!![0], resolved.groupColors["G1"])
        assertEquals("#123456", resolved.groupColors["G2"])
        // 非法色被丢弃，改用色板
        assertEquals(PALETTES[PaletteId.DEFAULT]!![2], resolved.groupColors["G3"])
        assertEquals(0, resolved.ungrouped) // ungrouped = entries 中 group 为空的行数
    }

    // ── support ──

    @Test fun supportSummaryBinsAndThreshold() {
        val t = parseNewick("((A,B)100,(C,D)50,(E,F)30)80;")
        val s = supportSummary(t.root, 0.0, 10)
        assertEquals(4, s.internal) // 3 个内部+根? 根含 support 80
        assertEquals(4, s.withSupport)
        assertEquals(30.0, s.min!!, 0.0)
        assertEquals(100.0, s.max!!, 0.0)
        assertEquals(65.0, s.mean!!, 1e-9)
        val filtered = supportSummary(t.root, 60.0, 10)
        assertEquals(2, filtered.shown)
        assertEquals(2, filtered.filtered)
        // 分箱：0-30 → bin 3（30/10=3 → index 3），100 → index 10-1=9...（width=10）
        assertEquals(1, filtered.bins[9].total)
    }

    // ── compare ──

    @Test fun compareTreesIdenticalAndDiffering() {
        val a = parseNewick("((A,B),(C,D));")
        val same = parseNewick("((A,B),(C,D));")
        val identical = compareTrees(a.root, same.root)
        assertTrue(identical.identical)
        assertEquals(0, identical.rf)
        val b = parseNewick("((A,C),(B,D));")
        val diff = compareTrees(a.root, b.root)
        assertTrue(!diff.identical)
        // 标准 unrooted RF：A|BCD 为共享 split；AB|CD 与 AC|BD 互为唯一 → rf=2? 实际 1+1
        assertEquals(1, diff.uniqueA)
        assertEquals(1, diff.uniqueB)
        assertEquals(2, diff.rf)
        assertEquals(4, diff.sharedLeaves.size)
        assertEquals(2, diff.maxRf)
        assertEquals(1.0, diff.normalizedRf, 1e-9)
    }

    // ── project json ──

    @Test fun projectRoundTripAndErrors() {
        val t = parseNewick("((A:1,B:2)90:3,C:4);")
        val project = TreeProject(
            format = "spice-tree", version = 1,
            originalNewick = "((A:1,B:2)90:3,C:4);",
            tree = nodeToSerialized(t.root),
            rooted = true, sourceName = "x.nwk",
            collapsedIds = listOf(t.root.children[0].id),
            settings = defaultSettings().copy(fontSize = 18.0),
            metadata = TreeMetadata(listOf(MetaEntry("A", "G", "#4cd6ff")), PaletteId.WARM),
            title = "标题", caption = "图注", savedAt = isoNow()
        )
        val text = serializeProject(project)
        val back = parseProjectJson(text)
        assertEquals("x.nwk", back.sourceName)
        assertEquals(18.0, back.settings.fontSize, 0.0)
        assertEquals(PaletteId.WARM, back.metadata.paletteId)
        assertEquals(1, back.collapsedIds.size)
        assertEquals("标题", back.title)
        // 手写 pretty 序列化：两空格缩进 + 键序保持
        assertTrue(text.contains("\"format\": \"spice-tree\""))
        assertTrue(text.indexOf("\"format\"") < text.indexOf("\"version\""))
        try { parseProjectJson("not json"); fail("not JSON") } catch (e: TreeProjectError) { assertEquals("not JSON", e.message) }
        try { parseProjectJson("{\"format\":\"other\",\"version\":1}"); fail("unknown format") } catch (e: TreeProjectError) { assertEquals("unknown format", e.message) }
        try { parseProjectJson("{\"format\":\"spice-tree\",\"version\":9}"); fail("unknown version") } catch (e: TreeProjectError) { assertEquals("unknown version", e.message) }
        try { parseProjectJson("{\"format\":\"spice-tree\",\"version\":1}"); fail("missing Newick") } catch (e: TreeProjectError) { assertEquals("missing Newick", e.message) }
    }

    @Test fun normalizeSettingsClamps() {
        val raw = JSONObject(
            "{\"mode\":\"circular\",\"fontSize\":999,\"branchWidth\":0.1,\"labelAngle\":123," +
                "\"supportThreshold\":-5,\"showSupport\":false,\"theme\":\"light\"}"
        )
        val s = normalizeSettings(raw)
        assertEquals(LayoutMode.CIRCULAR, s.mode)
        assertEquals(36.0, s.fontSize, 0.0)
        assertEquals(0.5, s.branchWidth, 0.0)
        assertEquals(90.0, s.labelAngle, 0.0)
        assertEquals(0.0, s.supportThreshold, 0.0)
        assertTrue(!s.showSupport)
        assertEquals(TreeTheme.LIGHT, s.theme)
    }

    // ── layout + render ──

    @Test fun computeLayoutPhylogramBasics() {
        val t = parseNewick("((A:0.02,B:0.03)90:0.05,C:0.07);")
        val layout = computeLayout(LayoutInput(root = t.root, mode = LayoutMode.PHYLOGRAM,
            fontSize = 11.0, labelAngle = 0.0, showBranchLengths = true, showScaleBar = true,
            title = "", caption = "", collapsed = emptySet(),
            metadata = resolveMetadata(emptyMetadata())))
        assertEquals(3, layout.leafCount)
        assertEquals(5, layout.nodeCount) // 含根：根 1 + 内部 1 + 叶 3
        assertTrue(!layout.fallbackCladogram)
        assertNotNull(layout.scaleBar)
        assertTrue(layout.scaleBar!!.unit > 0)
        // 终端索引 0..2
        val terminals = layout.terminals.mapNotNull { it.terminalIndex }.sorted()
        assertEquals(listOf(0, 1, 2), terminals)
    }

    @Test fun computeLayoutCladogramFallbackAndCircular() {
        val noLen = parseNewick("((A,B),(C,D));")
        val phy = computeLayout(LayoutInput(root = noLen.root, mode = LayoutMode.PHYLOGRAM,
            fontSize = 11.0, labelAngle = 0.0, showBranchLengths = false, showScaleBar = false,
            title = "", caption = "", collapsed = emptySet(),
            metadata = resolveMetadata(emptyMetadata())))
        assertTrue(phy.fallbackCladogram)
        val circ = computeLayout(LayoutInput(root = noLen.root, mode = LayoutMode.CIRCULAR,
            fontSize = 11.0, labelAngle = 0.0, showBranchLengths = false, showScaleBar = false,
            title = "", caption = "", collapsed = emptySet(),
            metadata = resolveMetadata(emptyMetadata())))
        // 环形首端角度 = -π/2 + 0.5·step
        val first = circ.terminals.minByOrNull { it.terminalIndex ?: 0 }!!
        val step = Math.PI * 2 / circ.leafCount
        assertEquals(-Math.PI / 2 + 0.5 * step, first.angle, 1e-9)
    }

    @Test fun renderTreeSvgStructure() {
        val t = parseNewick("((A:0.02,B:0.03)90:0.05,C:0.07);")
        val layout = computeLayout(LayoutInput(root = t.root, mode = LayoutMode.PHYLOGRAM,
            fontSize = 11.0, labelAngle = 0.0, showBranchLengths = true, showScaleBar = true,
            title = "T", caption = "C", collapsed = emptySet(),
            metadata = resolveMetadata(emptyMetadata())))
        val svg = renderTreeSvg(layout, defaultSettings(), RenderState(
            collapseLabel = "折叠分支", expandLabel = "展开分支",
            unitLabel = "替换/位点", unnamedLabel = "未命名"), "T", "C")
        assertTrue(svg.startsWith("<svg"))
        assertTrue(svg.contains("class=\"branch\""))
        assertTrue(svg.contains("branch-hit"))
        assertTrue(svg.contains("data-node-id="))
        // 真实 text 节点（叶名 + 标题）
        assertTrue(svg.contains(">A<"))
        assertTrue(svg.contains(">T<"))
        // CSS 块 + dim 透明度
        assertTrue(svg.contains(".dim{opacity:.26}"))
    }

    @Test fun renderTreePngProducesValidPngWithDpi() {
        val t = parseNewick("((A:0.02,B:0.03)90:0.05,C:0.07);")
        val layout = computeLayout(LayoutInput(root = t.root, mode = LayoutMode.PHYLOGRAM,
            fontSize = 11.0, labelAngle = 0.0, showBranchLengths = true, showScaleBar = true,
            title = "", caption = "", collapsed = emptySet(),
            metadata = resolveMetadata(emptyMetadata())))
        val png = renderTreePng(layout, defaultSettings(), RenderState.export(), "", "", 300)
        // PNG 签名
        assertEquals(0x89.toByte(), png.bytes[0])
        assertEquals('P'.code.toByte(), png.bytes[1])
        assertEquals(300, png.dpi)
        assertTrue(!png.reduced)
        assertTrue(png.bytes.size > 1000)
        // pHYs chunk（dpi≈300 → 11811 像素/米）
        val hex = png.bytes.joinToString("") { "%02x".format(it) }
        assertTrue(hex.contains("70485973")) // pHYs
    }

    // ── repro bundle ──

    @Test fun reproBundleStructureAndManifest() {
        val t = parseNewick("(A:1,B:1);")
        val project = TreeProject(
            format = "spice-tree", version = 1, originalNewick = "(A:1,B:1);",
            tree = nodeToSerialized(t.root), rooted = true, sourceName = null,
            collapsedIds = emptyList(), settings = defaultSettings(),
            metadata = emptyMetadata(), title = "Test Tree", caption = "",
            savedAt = isoNow()
        )
        val files = listOf(ReproFile("tree.svg", "image/svg+xml", "<svg/>".toByteArray()))
        val (zip, name) = buildReproBundle(
            tool = "tree", title = treeDocName(project), files = files,
            parameters = treeParameters(project), methods = treeMethods(project),
            projectJson = serializeProject(project), softwareVersion = "1.7.17"
        )
        assertTrue(name.endsWith("-repro.zip"))
        val entries = mutableListOf<Pair<String, ByteArray>>()
        ZipInputStream(ByteArrayInputStream(zip)).use { zis ->
            var e = zis.nextEntry
            while (e != null) {
                entries.add(e.name to zis.readBytes())
                assertEquals(java.util.zip.ZipEntry.STORED, e.method)
                e = zis.nextEntry
            }
        }
        val names = entries.map { it.first }
        assertTrue(names.contains("tree.svg"))
        assertTrue(names.contains("project.json"))
        assertTrue(names.contains("parameters.json"))
        assertTrue(names.contains("methods.md"))
        assertTrue(names.contains("software.json"))
        assertTrue(names.contains("README.md"))
        assertTrue(names.contains("manifest.sha256.json"))
        val manifest = JSONObject(entries.first { it.first == "manifest.sha256.json" }.second.decodeToString())
        assertEquals("SPICE_REPRO", manifest.getString("format"))
        assertEquals(1, manifest.getInt("version"))
        assertEquals("tree", manifest.getString("tool"))
        // methods.md 含标题与 software 行
        val methods = entries.first { it.first == "methods.md" }.second.decodeToString()
        assertTrue(methods.contains("# Test Tree"))
        assertTrue(methods.contains("Software: SPICE GUI 1.7.17"))
    }

    // ── example + utils ──

    @Test fun exampleStringsParse() {
        val t = parseNewick(EXAMPLE_NEWICK)
        assertEquals(11, collectLeafNodes(t.root).size)
        assertTrue(t.rooted) // 二分根
        // 示例元数据 12 行全部匹配叶
        val entries = parseMetadataTable(EXAMPLE_METADATA)
        assertEquals(11, entries.size)
        val leafNames = collectLeafNodes(t.root).map { it.name }.toSet()
        assertEquals(11, entries.count { it.leaf in leafNames })
        // 分组五类
        val groups = entries.map { it.group }.distinct()
        assertEquals(listOf("Primates", "Rodents", "Carnivores", "Artiodactyla", "Perissodactyla"), groups)
    }

    @Test fun jsNumberFormatting() {
        assertEquals("0.1", jsNum(0.1))
        assertEquals("300", jsNum(300.0))
        assertEquals("1e-7", jsNum(1e-7))
        assertEquals("0.021", jsNum(0.021))
        assertEquals("1e+21", jsNum(1e21))
        assertEquals("null", jsNum(Double.NaN))
        assertEquals("0.5", jsNum(0.5))
    }

    @Test fun normalizedSupportAndFormatNumber() {
        assertEquals(50.0, normalizedSupport(0.5)!!, 0.0)
        assertEquals(85.0, normalizedSupport(85.0)!!, 0.0)
        assertNull(normalizedSupport(null))
        assertEquals("0.021", formatNumber(0.021))
        assertEquals("100", formatNumber(100.0))
    }
}
