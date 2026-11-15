package com.spice.nativeapp

import com.spice.nativeapp.core.FigureCitations
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** FigureCitationsTest —— main exporters.ts citationMarkdown() 无 asset 分支 1:1。 */
class FigureCitationsTest {
    @Test
    fun no_used_assets_branch_matches_main() {
        val md = FigureCitations.citationMarkdown("My figure", "2026-11-15T00:00:00.000Z")
        assertEquals(
            "# Figure Studio — asset provenance\n\n_My figure — generated 2026-11-15T00:00:00.000Z_\n\nNo third-party assets used.\n",
            md,
        )
    }

    @Test
    fun empty_doc_name_falls_back_to_figure() {
        val md = FigureCitations.citationMarkdown("")
        assertTrue(md.contains("_Figure — generated"))
        assertTrue(md.endsWith("No third-party assets used.\n"))
    }
}
