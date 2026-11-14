package com.spice.nativeapp.core

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * main src/lib/tools/figure/{charts.ts, figureAgent.ts, types.ts(chart 部分)}
 * 与 tools.ts figure_add_chart 校验链 1:1 核验。
 */
class FigureChartsTest {

    // ── CSV 解析（main parseChartCsv / splitCsvLine / detectDelimiter）──

    @Test
    fun `parse example csv merges err columns into previous series`() {
        val data = parseChartCsv(CHART_CSV_EXAMPLE)
        assertNotNull(data)
        assertEquals(listOf("0 h", "24 h"), data!!.labels)
        assertEquals(2, data.series.size)
        assertEquals("Control", data.series[0].name)
        assertEquals(listOf(1.0, 1.2), data.series[0].values)
        assertEquals(listOf(0.10, 0.12), data.series[0].errors)
        assertEquals("Treatment", data.series[1].name)
        assertEquals(listOf(1.8, 2.1), data.series[1].values)
    }

    @Test
    fun `parse csv detects tab delimiter and quoted cells`() {
        // 表头无逗号 → tab 探测胜出；数据行引号内逗号保留
        val data = parseChartCsv("Growth phase\tSignal\n\"T1, early\"\t2.5\nT2\t3.5")
        assertNotNull(data)
        assertEquals(listOf("T1, early", "T2"), data!!.labels)
        assertEquals(listOf(2.5, 3.5), data.series[0].values)
    }

    @Test
    fun `parse csv skips comment lines and rejects unusable tables`() {
        // "# " 注释行被剔除后仅剩一行 → 不可用
        assertNull(parseChartCsv("# just a header\nA,B"))
        // 首个（也是唯一）数值列是 err 表头：无有效 series
        assertNull(parseChartCsv("A,err\n1,2"))
        // 单列（只有标签列）
        assertNull(parseChartCsv("A\nB"))
        // 注释行 + 合法两列 → 解析成功
        assertNotNull(parseChartCsv("# notes\nA,B\n1,2\n3,4"))
    }

    @Test
    fun `chartCsv roundtrips labels series and errors`() {
        val node = createChartNode(0.0, 0.0)
        val text = chartCsv(node)
        val data = parseChartCsv(text)
        assertNotNull(data)
        assertEquals(node.labels, data!!.labels)
        assertEquals(node.series[0].name, data.series[0].name)
        assertEquals(node.series[0].values, data.series[0].values)
        assertEquals(node.series[0].errors, data.series[0].errors)
    }

    @Test
    fun `applyChartCsv keeps colors of same-name series`() {
        val node = createChartNode(0.0, 0.0)
        val data = parseChartCsv("Cat,A,B\nx,1,2\ny,3,4")!!
        val applied = applyChartCsv(node, data)
        assertEquals(listOf("x", "y"), applied.labels)
        assertEquals(2, applied.series.size)
        assertEquals(CHART_PALETTE[0], applied.series[0].color)
        assertEquals(CHART_PALETTE[1], applied.series[1].color)
        val data2 = parseChartCsv("Cat,A\nx,1\ny,3")!!
        val applied2 = applyChartCsv(applied, data2)
        assertEquals(CHART_PALETTE[0], applied2.series[0].color)
    }

    // ── chartScale（main niceStep 1/2/2.5/5/10、bar 含 0、yMin/yMax 覆盖）──

    @Test
    fun `scale includes zero for bar and honors user range`() {
        val node = createChartNode(0.0, 0.0) // bar 1..1.8
        val scale = chartScale(node)
        assertTrue(scale.min <= 0.0)
        assertTrue(scale.max >= 1.8)
        assertEquals(0.0, scale.ticks.first(), 1e-9)

        val overridden = node.copy(yMin = 10.0, yMax = 20.0)
        val scale2 = chartScale(overridden)
        assertEquals(10.0, scale2.ticks.min(), 1e-9)
        assertTrue(scale2.ticks.max() >= 20.0)
    }

    @Test
    fun `nice steps land on 1 2 2_5 5 10 multiples`() {
        val node = createChartNode(0.0, 0.0).copy(series = listOf(nodeSeries("s", listOf(0.0, 7.0))))
        val scale = chartScale(node)
        val step = if (scale.ticks.size > 1) scale.ticks[1] - scale.ticks[0] else 1.0
        assertTrue(step in listOf(1.0, 2.0, 2.5, 5.0, 10.0))
    }

    private fun nodeSeries(name: String, values: List<Double>, errors: List<Double> = emptyList()) =
        ChartSeries(createFigureId("series"), name, CHART_PALETTE[0], values, errors)

    // ── SVG 渲染（main renderChartNode 结构）──

    @Test
    fun `render emits axes grid bars errors and default significance`() {
        val render = renderChartNode(createChartNode(0.0, 0.0))
        val svg = render.svg
        assertTrue(svg.contains("stroke=\"#e2e8f0\""))                      // 网格
        assertTrue(svg.contains("<polyline") || svg.contains("<rect"))       // bar 主体（rect）
        assertTrue(svg.contains("font-weight=\"600\""))                      // 显著性标签
        assertTrue(svg.contains("transform=\"rotate(-90"))                   // Y 轴标题
        assertTrue(svg.contains("Relative signal"))
        assertTrue(svg.contains("Control"))
    }

    @Test
    fun `render line chart emits polyline circles and error caps`() {
        val node = createChartNode(0.0, 0.0).copy(chartType = ChartKind.LINE)
        val svg = renderChartNode(node).svg
        assertTrue(svg.contains("<polyline"))
        assertTrue(svg.contains("<circle"))
        assertTrue(Regex("<line [^/]*stroke-width=\"1\"/>").containsMatchIn(svg))
    }

    @Test
    fun `chartSummary matches main format`() {
        val node = createChartNode(0.0, 0.0)
        assertEquals("Bar · 1 series · 3 categories", chartSummary(node))
        assertEquals("Scatter · 1 series · 3 categories", chartSummary(node.copy(chartType = ChartKind.SCATTER)))
    }

    // ── figureAgent（main figureAgent.ts 排队/重放/active 语义）──

    @Test
    fun `dispatch without handler queues and returns false then replays on register`() {
        val spec = FigureChartSpec("Test", ChartKind.BAR, listOf("a", "b"),
            listOf(FigureChartSpecSeries("s", listOf(1.0, 2.0))))
        val before = FigureAgent.figureToolActive()
        if (!before) {
            assertFalse(FigureAgent.dispatchFigureCommand(FigureAgentCommand.AddChart(spec)))
        }
        val seen = mutableListOf<FigureAgentCommand>()
        val unregister = FigureAgent.registerFigureCommandHandler { seen.add(it) }
        try {
            assertTrue(FigureAgent.figureToolActive())
            if (!before) assertEquals(1, seen.size)
            // 挂载中直接派发
            assertTrue(FigureAgent.dispatchFigureCommand(FigureAgentCommand.AddChart(spec)))
            assertEquals(if (before) 1 else 2, seen.size)
        } finally {
            unregister()
        }
        assertFalse(FigureAgent.figureToolActive())
    }

    @Test
    fun `register replace swaps handler and unregister deactivates`() {
        val seenA = mutableListOf<String>()
        val seenB = mutableListOf<String>()
        val unregisterA = FigureAgent.registerFigureCommandHandler { seenA.add("a") }
        val unregisterB = FigureAgent.registerFigureCommandHandler { seenB.add("b") }
        val spec = FigureChartSpec("T", ChartKind.LINE, listOf("x", "y"), listOf(FigureChartSpecSeries("s", listOf(1.0, 1.0))))
        try {
            FigureAgent.dispatchFigureCommand(FigureAgentCommand.AddChart(spec))
            // 后注册的 handler 替换前者
            assertTrue(seenB.isNotEmpty())
            assertTrue(seenA.isEmpty())
        } finally {
            unregisterB()
            unregisterA()
        }
        assertFalse(FigureAgent.figureToolActive())
    }

    // ── figure_add_chart 工具校验链（main tools.ts execute 顺序）──

    private val ctx = AiTools.WorkspaceContext()

    private fun tool() = AiTools.buildAgentTools(ctx).first { it.name == "figure_add_chart" }

    @Test
    fun `tool rejects bad chartType labels and series shapes`() {
        val t = tool()
        assertFalse(t.execute(JSONObject("""{"name":"X","chartType":"pie","labels":["a","b"],"series":[{"name":"s","values":[1,2]}]}"""), ctx).ok)
        assertFalse(t.execute(JSONObject("""{"name":"X","chartType":"bar","series":[{"name":"s","values":[1,2]}]}"""), ctx).ok)
        assertFalse(t.execute(JSONObject("""{"name":"X","chartType":"bar","labels":["a"],"series":[{"name":"s","values":[1,2]}]}"""), ctx).ok)
        assertFalse(t.execute(JSONObject("""{"name":"X","chartType":"bar","labels":["a",""],"series":[{"name":"s","values":[1,2]}]}"""), ctx).ok)
        assertFalse(t.execute(JSONObject("""{"name":"X","chartType":"bar","labels":["a","b"],"series":[]}"""), ctx).ok)
    }

    @Test
    fun `tool fits series to category count and defaults colors`() {
        val t = tool()
        assertFalse(t.execute(JSONObject("""{"name":"X","chartType":"bar","labels":["a","b","c"],"series":[{"name":"s","values":[1]}]}"""), ctx).ok) // 无编辑器挂载仍失败
        val parsed = AiTools.finiteNumbersArg(org.json.JSONArray("[1,2,3]"))
        assertEquals(listOf(1.0, 2.0, 3.0), parsed)
        assertNull(AiTools.finiteNumbersArg(org.json.JSONArray("[1,\"x\"]")))
        val fitted = AiTools.fitSeriesLength(listOf(1.0, 2.0, 3.0), 5)
        assertEquals(listOf(1.0, 2.0, 3.0, 0.0, 0.0), fitted)
        val series = AiTools.figureSeriesArg(
            org.json.JSONObject("""{"name":"","values":[1,-2],"errors":[-0.5,0.5]}"""), 3, 2)
        assertNotNull(series as? FigureChartSpecSeries)
        val s = series as FigureChartSpecSeries
        assertEquals("Series 4", s.name)
        assertEquals(listOf(1.0, -2.0), s.values)
        assertEquals(listOf(0.5, 0.5), s.errors)
        assertEquals(CHART_PALETTE[3], s.color)
    }

    @Test
    fun `tool dispatches to mounted handler and reports chart summary`() {
        val received = mutableListOf<FigureChartSpec>()
        val unregister = FigureAgent.registerFigureCommandHandler { cmd ->
            if (cmd is FigureAgentCommand.AddChart) received.add(cmd.spec)
        }
        try {
            val t = tool()
            val r = t.execute(JSONObject("""{"name":"表达谱","chartType":"line","labels":["0 h","24 h","48 h"],"series":[{"name":"GFP","values":[1,2,3.5],"errors":[0.1,0.2,0.3]},{"name":"RFP","values":[2,1,0.5]}],"yLabel":"Fluorescence"}"""), ctx)
            assertTrue(r.ok)
            assertEquals(1, received.size)
            assertEquals("表达谱", received[0].name)
            assertEquals(ChartKind.LINE, received[0].chartType)
            assertEquals("Fluorescence", received[0].yLabel)
            assertNull(received[0].xLabel)
            assertEquals(2, received[0].series.size)
            assertEquals(listOf(1.0, 2.0, 3.5), received[0].series[0].values)
            assertEquals(listOf(0.1, 0.2, 0.3), received[0].series[0].errors)
        } finally {
            unregister()
        }
        assertFalse(FigureAgent.figureToolActive())
    }
}
