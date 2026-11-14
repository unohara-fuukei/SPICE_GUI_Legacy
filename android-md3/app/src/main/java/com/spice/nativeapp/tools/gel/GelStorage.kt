package com.spice.nativeapp.tools.gel

/**
 * Local persistence: autosave snapshot, recent projects and style templates.
 * 1:1 移植自原版 src/lib/tools/gel/storage.ts（localStorage → SharedPreferences）。
 *
 * Everything stays on this device; a .gel.json file remains the authoritative
 * backup because app data can be cleared.
 */
import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

object GelStorage {

    private const val PREFS = "spice_gel"
    private const val SNAPSHOT_KEY = "spice_gel_project_v2"
    private const val LEGACY_KEY = "spice_gel_project_v1"
    private const val RECENT_INDEX = "spice_gel_recent_index_v2"
    private const val RECENT_PREFIX = "spice_gel_recent_v2_"
    private const val STYLES_KEY = "spice_gel_styles_v1"
    private const val LIBRARY_KEY = "spice_gel_ladder_library_v1"
    private const val DETECT_DEFAULTS_KEY = "spice_gel_detect_defaults_v1"
    private const val MAX_RECENT = 6

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun loadSnapshot(ctx: Context): GelProject? {
        return try {
            val raw = prefs(ctx).getString(SNAPSHOT_KEY, null)
            if (raw != null) {
                val parsed = JSONObject(raw)
                if (GelTypes.isGelProject(parsed)) return GelProjectDoc.normalizeProject(GelTypes.projectFromJson(parsed))
            }
            val legacy = prefs(ctx).getString(LEGACY_KEY, null)
            if (legacy != null) {
                val parsedLegacy = JSONObject(legacy)
                if (parsedLegacy.optInt("version", 0) == 1 && parsedLegacy.optString("imageDataUrl").isNotEmpty()) {
                    return GelTypes.migrateV1(parsedLegacy)
                }
            }
            null
        } catch (e: Exception) {
            /* corrupt snapshot: start clean */
            null
        }
    }

    fun saveSnapshot(ctx: Context, project: GelProject): Boolean {
        return try {
            prefs(ctx).edit().putString(SNAPSHOT_KEY, GelTypes.forStorageJson(project).toString()).commit()
        } catch (e: Exception) {
            false
        }
    }

    fun clearSnapshot(ctx: Context) {
        try {
            prefs(ctx).edit().remove(SNAPSHOT_KEY).remove(LEGACY_KEY).apply()
        } catch (e: Exception) {
            /* ignore */
        }
    }

    class RecentEntry(val id: String, val name: String, val updatedAt: String)

    fun listRecent(ctx: Context): List<RecentEntry> {
        return try {
            val raw = prefs(ctx).getString(RECENT_INDEX, null)
            val arr = if (raw != null) JSONArray(raw) else JSONArray()
            val out = ArrayList<RecentEntry>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                out.add(RecentEntry(o.optString("id"), o.optString("name"), o.optString("updatedAt")))
            }
            out.sortedByDescending { it.updatedAt }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun writeIndex(ctx: Context, entries: List<RecentEntry>) {
        val arr = JSONArray()
        entries.take(MAX_RECENT).forEach { e ->
            arr.put(JSONObject().apply {
                put("id", e.id)
                put("name", e.name)
                put("updatedAt", e.updatedAt)
            })
        }
        prefs(ctx).edit().putString(RECENT_INDEX, arr.toString()).apply()
    }

    /** Cache a project; returns false when the device storage is full. */
    fun saveRecent(ctx: Context, project: GelProject): Boolean {
        val id = project.createdAt
        val entry = RecentEntry(
            id,
            project.imageName.ifEmpty { project.notes.ifEmpty { id } },
            java.time.OffsetDateTime.now().toString(),
        )
        return try {
            val edit = prefs(ctx).edit().putString(RECENT_PREFIX + id, GelTypes.forStorageJson(project).toString())
            edit.commit()
            val rest = listRecent(ctx).filter { it.id != id }
            val next = (listOf(entry) + rest).take(MAX_RECENT)
            // Evict the payloads that no longer fit in the index.
            for (old in rest.drop(MAX_RECENT - 1)) prefs(ctx).edit().remove(RECENT_PREFIX + old.id).apply()
            writeIndex(ctx, next)
            true
        } catch (e: Exception) {
            false
        }
    }

    fun loadRecent(ctx: Context, id: String): GelProject? {
        return try {
            val raw = prefs(ctx).getString(RECENT_PREFIX + id, null) ?: return null
            val parsed = JSONObject(raw)
            if (GelTypes.isGelProject(parsed)) GelProjectDoc.normalizeProject(GelTypes.projectFromJson(parsed)) else null
        } catch (e: Exception) {
            null
        }
    }

    fun renameRecent(ctx: Context, id: String, name: String) {
        writeIndex(ctx, listRecent(ctx).map { if (it.id == id) RecentEntry(it.id, name, it.updatedAt) else it })
        try {
            val raw = prefs(ctx).getString(RECENT_PREFIX + id, null)
            if (raw != null) {
                val p = JSONObject(raw)
                p.put("imageName", name)
                prefs(ctx).edit().putString(RECENT_PREFIX + id, p.toString()).apply()
            }
        } catch (e: Exception) {
            /* ignore */
        }
    }

    fun removeRecent(ctx: Context, id: String) {
        try {
            prefs(ctx).edit().remove(RECENT_PREFIX + id).apply()
        } catch (e: Exception) {
            /* ignore */
        }
        writeIndex(ctx, listRecent(ctx).filter { it.id != id })
    }

    class GelStyle(
        val id: String,
        val name: String,
        val layout: GelLayoutSettings,
        val customLadders: List<LadderDef>,
        val favorites: List<String>,
        val gelType: String,
        val createdAt: String,
    )

    private fun styleToJson(s: GelStyle): JSONObject = JSONObject().apply {
        put("id", s.id)
        put("name", s.name)
        put("layout", JSONObject().apply {
            put("fontFamily", s.layout.fontFamily)
            put("nameFontSize", s.layout.nameFontSize)
            put("labelFontSize", s.layout.labelFontSize)
            put("nameAngle", s.layout.nameAngle)
            put("red", s.layout.red)
            put("conditionsNearestGel", s.layout.conditionsNearestGel)
            put("figureWidthMm", s.layout.figureWidthMm)
            put("groupLabelFit", s.layout.groupLabelFit)
        })
        put("customLadders", JSONArray().apply { s.customLadders.forEach { put(GelLadders.toJson(it)) } })
        put("favorites", JSONArray(s.favorites))
        put("gelType", s.gelType)
        put("createdAt", s.createdAt)
    }

    private fun styleFromJson(o: JSONObject): GelStyle? {
        val id = o.optString("id")
        if (id.isEmpty()) return null
        val layout = GelLayoutSettings(
            fontFamily = o.optJSONObject("layout")?.optString("fontFamily") ?: "Arial",
            nameFontSize = o.optJSONObject("layout")?.optDouble("nameFontSize", 9.0) ?: 9.0,
            labelFontSize = o.optJSONObject("layout")?.optDouble("labelFontSize", 8.0) ?: 8.0,
            nameAngle = o.optJSONObject("layout")?.optDouble("nameAngle", 0.0) ?: 0.0,
            red = o.optJSONObject("layout")?.optBoolean("red", false) ?: false,
            conditionsNearestGel = o.optJSONObject("layout")?.optBoolean("conditionsNearestGel", false) ?: false,
            figureWidthMm = o.optJSONObject("layout")?.optDouble("figureWidthMm", 120.0) ?: 120.0,
            groupLabelFit = o.optJSONObject("layout")?.optString("groupLabelFit") ?: "wrap",
        )
        val custom = ArrayList<LadderDef>()
        o.optJSONArray("customLadders")?.let { arr ->
            for (i in 0 until arr.length()) {
                arr.optJSONObject(i)?.let { GelLadders.fromJson(it).takeIf { it.id.isNotEmpty() && it.sizes.size >= 2 }?.let { l -> custom.add(l) } }
            }
        }
        val favorites = ArrayList<String>()
        o.optJSONArray("favorites")?.let { arr ->
            for (i in 0 until arr.length()) {
                val v = arr.opt(i)
                if (v is String) favorites.add(v)
            }
        }
        return GelStyle(id, o.optString("name"), layout, custom, favorites, o.optString("gelType"), o.optString("createdAt"))
    }

    fun listStyles(ctx: Context): List<GelStyle> {
        return try {
            val raw = prefs(ctx).getString(STYLES_KEY, null) ?: return emptyList()
            val arr = JSONArray(raw)
            val out = ArrayList<GelStyle>()
            for (i in 0 until arr.length()) {
                arr.optJSONObject(i)?.let { styleFromJson(it)?.let { s -> out.add(s) } }
            }
            out
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun saveStyle(ctx: Context, style: GelStyle): Boolean {
        return try {
            val rest = listStyles(ctx).filter { it.id != style.id }
            val arr = JSONArray()
            (listOf(style) + rest).take(20).forEach { arr.put(styleToJson(it)) }
            prefs(ctx).edit().putString(STYLES_KEY, arr.toString()).apply()
            true
        } catch (e: Exception) {
            false
        }
    }

    fun removeStyle(ctx: Context, id: String) {
        try {
            val arr = JSONArray()
            listStyles(ctx).filter { it.id != id }.forEach { arr.put(styleToJson(it)) }
            prefs(ctx).edit().putString(STYLES_KEY, arr.toString()).apply()
        } catch (e: Exception) {
            /* ignore */
        }
    }

    /** Detection configuration used for new imports and batch runs. Built-in
     *  presets stay read-only; this stores the user's last "save as default". */
    fun loadDetectDefault(ctx: Context): GelDetectConfig? {
        return try {
            val raw = prefs(ctx).getString(DETECT_DEFAULTS_KEY, null) ?: return null
            val parsed = JSONObject(raw)
            if (parsed.optInt("version", 0) != 1) return null
            GelPresets.sanitizeDetectConfig(parsed.optJSONObject("config"))
        } catch (e: Exception) {
            null
        }
    }

    fun saveDetectDefault(ctx: Context, config: GelDetectConfig): Boolean {
        return try {
            val o = JSONObject().apply {
                put("version", 1)
                put("config", GelPresets.toJson(config))
            }
            prefs(ctx).edit().putString(DETECT_DEFAULTS_KEY, o.toString()).apply()
            true
        } catch (e: Exception) {
            false
        }
    }

    fun clearDetectDefault(ctx: Context) {
        try {
            prefs(ctx).edit().remove(DETECT_DEFAULTS_KEY).apply()
        } catch (e: Exception) {
            /* ignore */
        }
    }

    /** Fresh imports inherit the saved default; anything else starts neutral. */
    fun detectConfigForNewImport(ctx: Context): GelDetectConfig {
        return loadDetectDefault(ctx) ?: GelPresets.defaultDetectConfig()
    }

    class LadderLibrary(
        val custom: List<LadderDef>,
        val favorites: List<String>,
    )

    fun loadLadderLibrary(ctx: Context): LadderLibrary {
        return try {
            val raw = prefs(ctx).getString(LIBRARY_KEY, null) ?: return LadderLibrary(emptyList(), emptyList())
            val parsed = JSONObject(raw)
            if (parsed.optInt("version", 0) != 1) return LadderLibrary(emptyList(), emptyList())
            val custom = ArrayList<LadderDef>()
            parsed.optJSONArray("custom")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    if (o.optString("id").isNotEmpty() && o.optJSONArray("sizes")?.length() ?: 0 >= 2) {
                        custom.add(GelLadders.fromJson(o))
                    }
                }
            }
            val favorites = ArrayList<String>()
            parsed.optJSONArray("favorites")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val v = arr.opt(i)
                    if (v is String) favorites.add(v)
                }
            }
            LadderLibrary(custom, favorites)
        } catch (e: Exception) {
            LadderLibrary(emptyList(), emptyList())
        }
    }

    fun saveLadderLibrary(ctx: Context, custom: List<LadderDef>, favorites: List<String>): Boolean {
        return try {
            val o = JSONObject().apply {
                put("version", 1)
                put("custom", JSONArray().apply { custom.forEach { put(GelLadders.toJson(it)) } })
                put("favorites", JSONArray(favorites))
            }
            prefs(ctx).edit().putString(LIBRARY_KEY, o.toString()).apply()
            true
        } catch (e: Exception) {
            false
        }
    }
}
