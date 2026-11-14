package com.spice.nativeapp.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import com.spice.nativeapp.core.SpBlock
import com.spice.nativeapp.core.SpKind

/**
 * Slides.kt（main src/lib/project/slides.ts 1:1）解析语义测试。
 */
class SlidesTest {

    private fun slides(md: String) = Slides.parseSlides(md)

    @Test fun hrAndH12SplitSlides() {
        val s = slides("# Title A\nbody a\n---\n## Title B\nbody b")
        assertEquals(2, s.size)
        assertEquals(1, s[0].level)
        assertEquals("Title A", s[0].title)
        assertEquals(2, s[1].level)
        assertEquals("Title B", s[1].title)
        // 无标题首块 level=0
        val s2 = slides("para only\n---\n# T")
        assertEquals(0, s2[0].level)
    }

    @Test fun h3StaysOnSlide() {
        val s = slides("# T\n### Deep\nafter")
        assertEquals(1, s.size)
        assertTrue(s[0].body[0] is SpBlock.H3)
    }

    @Test fun blockquoteBecomesSpeakerNotes() {
        val s = slides("# T\n> keep this secret\nslide text\n> second note line\n\nmore text")
        assertEquals(1, s.size)
        // 备注段落独立于正文（观众不可见）
        val noteText = s[0].notes.joinToString(" ") { n -> n.joinToString("") { it.text } }
        assertTrue(noteText.contains("keep this secret"))
        assertTrue(noteText.contains("second note line"))
        assertFalse(s[0].body.any { b -> b is SpBlock.P && b.spans.any { it.text.contains("secret") } })
    }

    @Test fun nestedAndTaskLists() {
        val md = """
            # T
            - a
              - nested
            - [x] done thing
            - [ ] todo thing
        """.trimIndent()
        val s = slides(md)
        val lst = s[0].body.filterIsInstance<SpBlock.Lst>().first()
        assertEquals(3, lst.items.size)
        assertEquals(null, lst.items[0].taskChecked) // 非任务项
        assertEquals(1, lst.items[0].sub!!.items.size)
        assertEquals("nested", lst.items[0].sub!!.items[0].spans[0].text)
        assertEquals(true, lst.items[1].taskChecked)
        assertEquals(false, lst.items[2].taskChecked)
    }

    @Test fun tableAlignmentParsing() {
        val md = """
            # T
            | L | C | R |
            |---|:-:|--:|
            | a | b | c |
        """.trimIndent()
        val s = slides(md)
        val tbl = s[0].body.filterIsInstance<SpBlock.Tbl>().first()
        assertEquals(listOf("left", "center", "right"), tbl.aligns)
        assertEquals(3, tbl.head.size)
        assertEquals("R", tbl.head[2][0].text)
        assertEquals(1, tbl.rows.size)
    }

    @Test fun fencedCodeBlock() {
        val s = slides("# T\n```\nlet x = 1;\n```\nafter")
        val code = s[0].body.filterIsInstance<SpBlock.Code>().first()
        assertEquals("let x = 1;", code.text)
    }

    @Test fun presenterCommentsAndFnDefsNeverRender() {
        val s = slides("# T\n// hidden comment\n[^note1]: the note\nvisible")
        assertEquals(listOf("T"), s.map { it.title })
        val bodyText = s[0].body.joinToString { b -> if (b is SpBlock.P) b.spans.joinToString("") { it.text } else "?" }
        assertFalse(bodyText.contains("hidden comment"))
        assertFalse(bodyText.contains("the note"))
    }

    @Test fun footnoteRefsNumberedAndGroupedAtSlideBottom() {
        val s = slides("# T\nalpha[^note1] and beta[^free text note] then again[^note1]\n[^note1]: defined note")
        assertEquals(1, s.size)
        val fnBox = s[0].body.filterIsInstance<SpBlock.FnBox>().first()
        // 重复引用复用编号 → 2 个条目（note1=1，自由文本=2）
        assertEquals(2, fnBox.notes.size)
        assertEquals(1, fnBox.notes[0].first)
        assertEquals("defined note", fnBox.notes[0].second.joinToString("") { it.text })
        assertEquals(2, fnBox.notes[1].first)
        assertEquals("free text note", fnBox.notes[1].second.joinToString("") { it.text })
        // 引用处替换为编号上标
        val marks = s[0].body.filterIsInstance<SpBlock.P>().flatMap { p -> p.spans.filter { it.kind == SpKind.FN_MARK } }
        assertEquals(listOf("1", "2", "1"), marks.map { it.text })
    }

    @Test fun inlineStylesKinds() {
        val s = slides("# T\n**b** *i* ~~s~~ ==h== `c` [l](https://x.y) ^2^ ~3~")
        val spans = s[0].body.filterIsInstance<SpBlock.P>().first().spans
        val byKind = spans.associateBy({ it.kind }, { it.text })
        assertEquals("b", byKind[SpKind.BOLD])
        assertEquals("i", byKind[SpKind.ITALIC])
        assertEquals("s", byKind[SpKind.STRIKE])
        assertEquals("h", byKind[SpKind.HIGHLIGHT])
        assertEquals("c", byKind[SpKind.CODE])
        assertEquals("l", byKind[SpKind.LINK])
        assertEquals("2", byKind[SpKind.SUP])
        assertEquals("3", byKind[SpKind.SUB])
    }

    @Test fun supParenthesisFormUnwrapped() {
        val s = slides("# T\nx^(a+b)^")
        val sup = s[0].body.filterIsInstance<SpBlock.P>().first().spans.first { it.kind == SpKind.SUP }
        assertEquals("a+b", sup.text)
    }

    @Test fun referenceLinksResolveFromDocLevelDefs() {
        val md = "see [text][ref1]\n\n[ref1]: https://example.com"
        val s = slides(md)
        val link = s[0].body.filterIsInstance<SpBlock.P>().first().spans.first { it.kind == SpKind.LINK }
        assertEquals("text", link.text)
        assertEquals("https://example.com", link.href)
    }

    @Test fun codeSpansProtectedFromEmphasis() {
        val s = slides("# T\n`a *b* c`")
        val code = s[0].body.filterIsInstance<SpBlock.P>().first().spans.first { it.kind == SpKind.CODE }
        assertEquals("a *b* c", code.text)
        assertFalse(s[0].body.any { b -> b is SpBlock.P && b.spans.any { it.kind == SpKind.ITALIC } })
    }

    @Test fun imageSpanCaptured() {
        val s = slides("# T\n![alt text](/tmp/pic.png)")
        val img = s[0].body.filterIsInstance<SpBlock.P>().first().spans.first { it.kind == SpKind.IMG }
        assertEquals("alt text", img.text)
        assertEquals("/tmp/pic.png", img.href)
    }

    @Test fun hasSlidesGate() {
        // 纯注释/备注页不算可演示（main hasSlides：title 或 body 有内容）
        assertFalse(Slides.hasSlides("// only comment\n> only notes"))
        assertFalse(Slides.hasSlides(""))
        assertTrue(Slides.hasSlides("# T"))
        assertTrue(Slides.hasSlides("plain paragraph"))
    }

    @Test fun emptyDraftsWithOnlyTitleDroppedFalse() {
        // 只有备注的草稿仍保留（main: title || body || notes）
        val s = slides("> only a note")
        assertEquals(1, s.size)
        assertEquals(1, s[0].notes.size)
    }

    @Test fun crlfNormalized() {
        val s = slides("# T\r\nbody\r\n---\r\n# U")
        assertEquals(2, s.size)
    }
}
