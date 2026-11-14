package com.spice.nativeapp.core

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * 多语言运行时（1:1 对齐 main project.inlang：zh 为源语言，7 语言
 * zh/en/ja/ko/de/nl/sv，词条搬运自 main 的 paraglide 词典 + 8 个工具
 * messages.json，android 分支自写文案以人工补译入库）。
 *
 * 查找语义与 main 一致：zh 原文为键。main 里 paraglide 是编译期消息函数、
 * 工具页是 `m['zh'][key]` 直查；Android 端统一为运行时 Map：
 *  - assets/i18n/core/<lang>.json    main 词典逐字搬运（冲突时 paraglide 优先）
 *  - assets/i18n/extras/<lang>.json   android 独有 UI 文案补译
 *  - zh 恒为原文直返（sourceLanguageTag）
 *
 * 切换即时生效（Compose state 驱动重组）；持久化 spice_settings/app_language。
 * 插值串：main 用 {v1}{v2}… 占位符；对应 tf("{v1} 个命中", n)。
 */
object I18n {

    /** main 语言标签 verbatim（zh.json langZh…langSv） */
    val LANGUAGES: List<Pair<String, String>> = listOf(
        "zh" to "简体中文", "en" to "English", "ja" to "日本語", "ko" to "한국어",
        "de" to "Deutsch", "nl" to "Nederlands", "sv" to "Svenska"
    )

    var lang by mutableStateOf("zh")

    private const val PREFS = "spice_settings"
    private const val KEY = "app_language"

    /** 当前语言的合并词典（zh→译文）；zh 语言为空表（恒直返） */
    private var dict: Map<String, String> = emptyMap()

    /** 启动时恢复持久化语言并装载词典（MainActivity onCreate 调用） */
    fun init(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        setLang(prefs.getString(KEY, "zh") ?: "zh", context)
    }

    /** 切换语言：装载词典 + 持久化；Compose state 变更触发全 UI 重组 */
    fun setLang(tag: String, context: Context) {
        val target = if (LANGUAGES.any { it.first == tag }) tag else "zh"
        lang = target
        dict = if (target == "zh") emptyMap() else loadDict(context, target)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, target).apply()
    }

    private fun loadDict(context: Context, tag: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (path in listOf("i18n/core/$tag.json", "i18n/extras/$tag.json")) {
            runCatching {
                val text = context.assets.open(path).bufferedReader().use { it.readText() }
                val obj = org.json.JSONObject(text)
                obj.keys().forEach { key -> out[key] = obj.optString(key) }
            }
        }
        return out
    }

    /** 以中文原文为键查译；zh 或未收录词条原样返回（渐进抽取，未收录=显示原文） */
    fun t(zh: String): String = if (lang == "zh") zh else dict[zh] ?: zh

    /** 插值词条（main {v1}{v2}… 占位符）；args 按序替换 {v1}..{v9} */
    fun tf(template: String, vararg args: Any?): String {
        val text = if (lang == "zh") template else dict[template] ?: template
        var out = text
        args.forEachIndexed { i, arg ->
            out = out.replace("{v${i + 1}}", arg?.toString() ?: "")
        }
        return out
    }

    /** 测试探针：当前词典规模 */
    fun loadedCount(): Int = dict.size
}
