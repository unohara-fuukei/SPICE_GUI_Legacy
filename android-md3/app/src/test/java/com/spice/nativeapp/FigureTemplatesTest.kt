package com.spice.nativeapp

import com.spice.nativeapp.core.FigureTemplates
import com.spice.nativeapp.core.FigureTemplates.Kind
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * FigureTemplatesTest —— templates.ts 数据层 1:1 校验。
 *
 * 节点数/分组/坐标（mm→px）/缩放语义逐模板抽查，期望值按 main 源码手算。
 */
class FigureTemplatesTest {

    private val MM = 96.0 / 25.4

    @Test
    fun registry_has_eight_templates_in_main_order() {
        val ids = FigureTemplates.FIGURE_TEMPLATES.map { it.id }
        assertEquals(
            listOf("single-column", "double-column", "slide", "graphical-abstract",
                "mechanism", "workflow", "comparison", "timeline"),
            ids
        )
        assertEquals("单栏", FigureTemplates.FIGURE_TEMPLATES[0].zhName)
        assertEquals("水平里程碑，标签交替排列", FigureTemplates.FIGURE_TEMPLATES[7].zhDesc)
    }

    @Test
    fun single_column_layout_matches_main() {
        val doc = FigureTemplates.applyFigureTemplate("single-column")
        assertEquals("Single column figure", doc.name)
        assertEquals(90.0, doc.widthMm, 1e-9)
        assertEquals(110.0, doc.heightMm, 1e-9)
        assertEquals(13, doc.nodes.size)
        assertEquals(listOf("Panel A"), doc.groups.map { it.name })
        val title = doc.nodes[0]
        assertEquals(Kind.TEXT, title.kind)
        assertEquals("Title", title.name)
        assertEquals(5.0 * MM, title.x, 1e-9)
        assertEquals(80.0 * MM, title.w, 1e-9)
        assertEquals(16.0, title.fontSize, 1e-9)
        // Panel A 组的 4 个成员（heroPanel/heroLabel/heroMotif/heroText）带 groupId
        val groupMembers = doc.nodes.filter { it.groupId == 1 }
        assertEquals(4, groupMembers.size)
        assertEquals(Kind.ELLIPSE, groupMembers[2].kind)
        assertEquals("dashed", groupMembers[2].dash)
        // 描边 0.45mm → max(0.4, px(0.45)) = 1.7
        assertEquals(0.45 * MM, groupMembers[0].strokeWidth, 1e-9)
    }

    @Test
    fun double_column_and_slide_layouts_match_main() {
        val double = FigureTemplates.applyFigureTemplate("double-column")
        assertEquals(19, double.nodes.size)
        assertEquals(listOf("Panel A", "Panel B"), double.groups.map { it.name })
        // 标题 18pt；细节条 x 序列 8/49.5/91/132.5
        assertEquals(18.0, double.nodes[0].fontSize, 1e-9)
        val detailX = double.nodes.filter { it.name.startsWith("Detail") && it.kind == Kind.RECT }
            .map { it.x }
        assertEquals((8.0 * MM), detailX[0], 1e-9)
        assertEquals((49.5 * MM), detailX[1], 1e-9)

        val slide = FigureTemplates.applyFigureTemplate("slide")
        assertEquals(25, slide.nodes.size)
        assertEquals(3, slide.groups.size)
        // 幻灯片标题字号 24pt；标题栏 stroke 'none'，strokeWidth 0 → strokePx 下限 0.4（main 同值）
        assertEquals(24.0, slide.nodes[1].fontSize, 1e-9)
        assertEquals("none", slide.nodes[0].stroke)
        assertEquals(0.4, slide.nodes[0].strokeWidth, 1e-9)
    }

    @Test
    fun graphical_abstract_mechanism_workflow_match_main() {
        val ga = FigureTemplates.applyFigureTemplate("graphical-abstract")
        assertEquals(18, ga.nodes.size)
        assertEquals(listOf("Input", "Mechanism", "Output"), ga.groups.map { it.name })
        val arrows = ga.nodes.filter { it.kind == Kind.ARROW }
        assertEquals(2, arrows.size)
        assertEquals("Input to mechanism", arrows[0].name)
        assertEquals(2.6 * MM, arrows[0].headSize, 1e-9)

        val mech = FigureTemplates.applyFigureTemplate("mechanism")
        assertEquals(30, mech.nodes.size)
        assertEquals(4, mech.groups.size)
        assertEquals(listOf("Recognition", "Activation", "Amplification", "Readout"), mech.groups.map { it.name })

        val wf = FigureTemplates.applyFigureTemplate("workflow")
        assertEquals(32, wf.nodes.size)
        assertEquals(4, wf.groups.size)
        // cardWidth = (164-18)/4 = 36.5
        val card = wf.nodes.first { it.name == "Prepare card" }
        assertEquals(36.5 * MM, card.w, 1e-9)
    }

    @Test
    fun comparison_and_timeline_match_main() {
        val cmp = FigureTemplates.applyFigureTemplate("comparison")
        assertEquals(19, cmp.nodes.size)
        assertEquals(listOf("Condition A", "Condition B", "Versus"), cmp.groups.map { it.name })
        val versus = cmp.nodes.first { it.name == "Versus marker" }
        assertEquals(82.0 * MM, versus.x, 1e-9)
        assertEquals(16.0 * MM, versus.w, 1e-9)

        val tl = FigureTemplates.applyFigureTemplate("timeline")
        assertEquals(20, tl.nodes.size)
        assertEquals(listOf("Planning", "Data collection", "Analysis", "Submission"), tl.groups.map { it.name })
        val axis = tl.nodes.first { it.name == "Timeline axis" }
        assertEquals(Kind.LINE, axis.kind)
        assertEquals(14.0 * MM, axis.x, 1e-9)
        assertEquals(0.6 * MM, axis.strokeWidth, 1e-9)
        // 交替：Q1/Q3 标签在轴上方（date 在 label 前），Q2/Q4 在下方
        val q1 = tl.nodes.filter { it.groupId == 1 }
        assertEquals("Q1", q1[2].text)   // marker, connector, date, label
        assertEquals("Q2", tl.nodes.filter { it.groupId == 2 }[3].text) // marker, connector, label, date
    }

    @Test
    fun width_scaling_clamps_and_scales_typography() {
        val doc = FigureTemplates.applyFigureTemplate("single-column", 180.0)
        assertEquals(180.0, doc.widthMm, 1e-9)
        assertEquals(220.0, doc.heightMm, 1e-9)
        assertEquals(32.0, doc.nodes[0].fontSize, 1e-9)   // 16pt × scale 2
        // 首个 rect 是 heroPanel：描边 0.45mm → px(0.9)（strokePx 下限 0.4）
        assertEquals(0.45 * 2 * MM, doc.nodes.first { it.kind == Kind.RECT }.strokeWidth, 1e-9)
        // 超出 clamp：2000 → 1200（scale = 1200/90）
        val big = FigureTemplates.applyFigureTemplate("single-column", 2000.0)
        assertEquals(1200.0, big.widthMm, 1e-9)
    }

    @Test
    fun unknown_id_falls_back_to_single_column() {
        val doc = FigureTemplates.applyFigureTemplate("no-such-template")
        assertEquals("Single column figure", doc.name)
    }
}
