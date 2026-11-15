package com.spice.nativeapp.core

import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Keymap 对齐 main `src/lib/gene/utils/keymap.ts`：
 * - 默认表 20 条，id 唯一；分类 view 7 / edit 5 / tools 8
 * - load 无持久化回退默认；save 自定义 key 后按 id 合并；reset 清空回默认
 * - 名称/描述 zh 原文非空（I18n.lang 默认 zh 直返）
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class KeymapTest {

    private val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()

    @After
    fun clean() {
        Keymap.reset(ctx)
    }

    @Test
    fun defaults_have20UniqueIds() {
        val defs = Keymap.DEFAULTS
        assertEquals(20, defs.size)
        assertEquals(defs.size, defs.map { it.id }.distinct().size)
    }

    @Test
    fun categories_distribution() {
        assertEquals(7, Keymap.byCategory(Keymap.DEFAULTS, "view").size)
        assertEquals(5, Keymap.byCategory(Keymap.DEFAULTS, "edit").size)
        assertEquals(8, Keymap.byCategory(Keymap.DEFAULTS, "tools").size)
    }

    @Test
    fun load_empty_returnsDefaults() {
        val loaded = Keymap.load(ctx)
        assertEquals(20, loaded.size)
        loaded.forEach { s ->
            assertEquals(s.defaultKey, s.key)
            assertTrue(s.name.isNotEmpty())
            assertTrue(s.description.isNotEmpty())
        }
    }

    @Test
    fun save_thenLoad_mergesById() {
        val saved = Keymap.load(ctx).map { s ->
            if (s.id == "undo") s.copy(key = "Mod+Shift+Z") else s
        }
        Keymap.save(ctx, saved)

        val reloaded = Keymap.load(ctx)
        val undo = reloaded.first { it.id == "undo" }
        assertEquals("Mod+Shift+Z", undo.key)
        val others = reloaded.filter { it.id != "undo" }
        others.forEach { assertEquals(it.defaultKey, it.key) }
    }

    @Test
    fun reset_clearsSavedBackToDefaults() {
        val saved = Keymap.load(ctx).map { it.copy(key = "Escape") }
        Keymap.save(ctx, saved)
        Keymap.reset(ctx)

        val after = Keymap.load(ctx)
        after.forEach { assertEquals(it.defaultKey, it.key) }
    }
}
