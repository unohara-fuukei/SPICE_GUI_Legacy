package com.spice.nativeapp.core

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * 快捷键映射（1:1 移植 main `src/lib/gene/utils/keymap.ts`）。
 *
 * - [id]/[category]（view | edit | tools）/[defaultKey]/[key] 与 main DEFAULT_SHORTCUTS 逐字对齐；
 *   [nameZh]/[descZh] 为 main paraglide `keymap_name_*`/`keymap_desc_*` 的 zh 原文，
 *   经 [I18n.t] 取当前语言译文（等价 main localizeShortcut 的 getter 本地化语义）。
 * - 持久化对齐 main localStorage `spice_keymap`：JSON 数组 `{id,key,...}`，
 *   加载时与默认表按 id 合并（新增默认项自动补齐，旧保存缺项回退 defaultKey）。
 *
 * 平台差异（披露）：main 在桌面浏览器用全局 KeyboardEvent 录制 / 绑定 / 触发组合键；
 * 安卓触屏无物理键盘事件通道，设置页仅展示默认映射清单，录制/单条清除/冲突检测与
 * 基因页全局快捷键监听不在本轮挂载（详见 android-md3/README.md §16）。
 */
data class Shortcut(
    val id: String,
    val category: String,
    val defaultKey: String,
    val key: String,
    val nameZh: String,
    val descZh: String
) {
    /** 等价 main localizeShortcut 的 name getter（随语言切换重算） */
    val name: String get() = I18n.t(nameZh)
    /** 等价 main localizeShortcut 的 description getter */
    val description: String get() = I18n.t(descZh)
}

object Keymap {

    /** 对齐 main localStorage 键 `spice_keymap` */
    private const val PREFS = "spice_keymap"
    private const val KEY_SHORTCUTS = "shortcuts"

    val DEFAULTS: List<Shortcut> = listOf(
        Shortcut("toggle_topology", "view", "Alt+M", "Alt+M", "切换拓扑结构", "在环状质粒图谱与线性DNA图谱之间来回切换"),
        Shortcut("switch_circular", "view", "Alt+C", "Alt+C", "切换到环状图谱", "直接切到环状质粒图谱视图"),
        Shortcut("switch_linear", "view", "Alt+L", "Alt+L", "切换到线性图谱", "直接切到线性基因图谱视图"),
        Shortcut("switch_sequence", "view", "Alt+S", "Alt+S", "切换到序列编辑器", "直接切到序列行编辑器视图"),
        Shortcut("switch_features", "view", "Alt+F", "Alt+F", "切换到特征注释列表", "切换到已标注的特征注释数据列表"),
        Shortcut("switch_properties", "view", "Alt+P", "Alt+P", "切换到质粒属性面板", "显示当前载体的总长、GC含量、拓扑等汇总信息"),
        Shortcut("switch_notes", "view", "Alt+N", "Alt+N", "切换到备注面板", "显示并编辑此 DNA 序列的详细科研备注与历史附注"),
        Shortcut("undo", "edit", "Mod+Z", "Mod+Z", "撤销上一步操作", "撤销历史栈中的最近一次编辑或克隆动作"),
        Shortcut("redo", "edit", "Mod+Shift+Z", "Mod+Shift+Z", "重做上一步操作", "恢复被撤销的操作"),
        Shortcut("save_project", "edit", "Mod+S", "Mod+S", "保存当前项目", "以 .spiceg 格式保存质粒编辑器工程到本地"),
        Shortcut("flip_sequence", "edit", "Mod+R", "Mod+R", "反转互补序列", "将整条双链 DNA 序列进行反向互补转换"),
        Shortcut("clear_selection", "edit", "Escape", "Escape", "清除选择区", "重置选择段为单个光标，清除高亮选中区域"),
        Shortcut("toggle_search", "tools", "Mod+F", "Mod+F", "展开/关闭序列搜索栏", "显示或隐藏支持简并碱基/正则表达式的序列快速检索工具"),
        Shortcut("toggle_enzymes", "tools", "Mod+E", "Mod+E", "展开/关闭限制性内切酶切位点面板", "显示/隐藏多限制酶切分析器及酶切位点标注"),
        Shortcut("toggle_codon", "tools", "Mod+O", "Mod+O", "展开/关闭密码子优化器", "一键分析、翻译及面向特定底物/宿主的最优密码子偏好改造"),
        Shortcut("toggle_crispr", "tools", "Mod+G", "Mod+G", "展开/关闭 CRISPR 设计器", "设计 CRISPR-Cas9/Cas12a 等单导向 RNA 靶点"),
        Shortcut("toggle_validator", "tools", "Mod+V", "Mod+V", "展开/关闭克隆方案验证器", "自动对 Golden Gate, Gibson Assembly 等组装连接动作进行验证"),
        Shortcut("toggle_gel", "tools", "Mod+H", "Mod+H", "展开/关闭虚拟电泳跑胶图", "展示由于当前限制酶组消化后的虚拟 Agarose 电泳结果"),
        Shortcut("toggle_logger", "tools", "Mod+J", "Mod+J", "展开/关闭控制台与操作日志", "显示或关闭主编辑器日志监控与 Rust 后端执行输出台"),
        Shortcut("toggle_primers", "tools", "Mod+P", "Mod+P", "展开/关闭引物与 LIMS 协作面板", "用于 PCR 验证引物属性计算及实验室数据关联")
    )

    /** 对齐 main loadKeymap：无保存项返回默认；有则按 id 合并（旧保存缺项回退 defaultKey） */
    fun load(context: Context): List<Shortcut> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_SHORTCUTS, null) ?: return DEFAULTS
        return runCatching {
            val arr = JSONArray(raw)
            val saved = HashMap<String, String>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                saved[o.optString("id")] = o.optString("key")
            }
            DEFAULTS.map { def ->
                def.copy(key = saved[def.id] ?: def.defaultKey)
            }
        }.getOrDefault(DEFAULTS)
    }

    /** 对齐 main saveKeymap：getter 转属性后序列化 id/key（其余字段由默认表派生） */
    fun save(context: Context, shortcuts: List<Shortcut>) {
        val arr = JSONArray()
        shortcuts.forEach { s ->
            arr.put(JSONObject().apply {
                put("id", s.id)
                put("name", s.name)
                put("category", s.category)
                put("description", s.description)
                put("defaultKey", s.defaultKey)
                put("key", s.key)
            })
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_SHORTCUTS, arr.toString()).apply()
    }

    /** 对齐 main resetKeymap：移除本地保存，返回默认映射 */
    fun reset(context: Context): List<Shortcut> {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY_SHORTCUTS).apply()
        return DEFAULTS
    }

    fun byCategory(shortcuts: List<Shortcut>, category: String): List<Shortcut> =
        shortcuts.filter { it.category == category }
}
