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
 * I18n 资产驱动运行时校验（#54）：
 * - zh 恒为原文直返（sourceLanguageTag，对齐 main paraglide）
 * - 6 语言词典装载规模（core 3917 + extras 822，extras 键与 core 不重叠）
 * - t() 查译命中 / 未收录词条回落 zh 原文
 * - tf() 翻译 + {v1}..{vN} 占位替换
 * - 切换持久化与启动恢复（spice_settings/app_language）
 * - LANGUAGES 标签与 main zh.json langZh…langSv verbatim
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class I18nTest {

    private val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()

    @After
    fun resetLang() {
        I18n.setLang("zh", ctx)
    }

    @Test
    fun `zh 直返原文`() {
        I18n.setLang("zh", ctx)
        assertEquals("zh", I18n.lang)
        assertEquals(0, I18n.loadedCount())
        assertEquals("设计", I18n.t("设计"))
        // tf 占位替换与语言无关；zh 模式下模板原文 + {vN} 替换
        assertEquals("泳道 3 · 强度 强", I18n.tf("泳道 {v1} · 强度 {v2}", 3, "强"))
    }

    @Test
    fun `6 语言词典全量装载`() {
        // core 3917 + extras 822 = 4739（extras 822 键均已从 core 命中中排除）
        for (tag in listOf("en", "ja", "ko", "de", "nl", "sv")) {
            I18n.setLang(tag, ctx)
            assertEquals(tag, I18n.lang)
            assertEquals("tag=$tag", 3917 + 822, I18n.loadedCount())
        }
    }

    @Test
    fun `t() 核心词典命中（main 词条）`() {
        I18n.setLang("en", ctx)
        assertEquals("Settings", I18n.t("设置"))
        I18n.setLang("ja", ctx)
        assertEquals("設定", I18n.t("设置"))
    }

    @Test
    fun `t() extras 补译命中`() {
        I18n.setLang("en", ctx)
        assertEquals("Fetch structure from RCSB PDB", I18n.t("从 RCSB PDB 拉取结构"))
        I18n.setLang("ja", ctx)
        assertEquals("RCSB PDB から構造を取得", I18n.t("从 RCSB PDB 拉取结构"))
    }

    @Test
    fun `t() 未收录词条回落 zh 原文`() {
        I18n.setLang("de", ctx)
        assertEquals("不存在的词条XYZ", I18n.t("不存在的词条XYZ"))
    }

    @Test
    fun `tf() 翻译并替换占位符`() {
        I18n.setLang("en", ctx)
        assertEquals(
            "Phase scan complete: 120 points (6 temperatures × 5 pH values), 90 stable",
            I18n.tf(
                "相图扫描完成：{v1} 点（{v2} 温度 × {v3} pH），稳定 {v4} 点",
                120, 6, 5, 90
            )
        )
        // null 参数替换为空串（与 t() 层约定一致）
        assertEquals("Mutant: ", I18n.tf("突变体：{v1}", null))
    }

    @Test
    fun `切换持久化并在 init 时恢复`() {
        I18n.setLang("ko", ctx)
        I18n.init(ctx) // 模拟重启
        assertEquals("ko", I18n.lang)
        assertEquals(3917 + 822, I18n.loadedCount())
    }

    @Test
    fun `未知语言标签回落 zh`() {
        I18n.setLang("fr", ctx)
        assertEquals("zh", I18n.lang)
    }

    @Test
    fun `语言标签与 main verbatim`() {
        val labels = I18n.LANGUAGES.toMap()
        assertEquals(7, labels.size)
        assertEquals("简体中文", labels["zh"])
        assertEquals("English", labels["en"])
        assertEquals("日本語", labels["ja"])
        assertEquals("한국어", labels["ko"])
        assertEquals("Deutsch", labels["de"])
        assertEquals("Nederlands", labels["nl"])
        assertEquals("Svenska", labels["sv"])
        assertTrue(I18n.LANGUAGES.map { it.first } == listOf("zh", "en", "ja", "ko", "de", "nl", "sv"))
    }
}
