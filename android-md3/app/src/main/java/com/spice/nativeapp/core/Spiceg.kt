package com.spice.nativeapp.core

import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * .spiceg 基因工程文件（对齐 main 分支 SpiceGeneFile schema）：
 * gene 页「保存项目 / 导出当前序列(.spiceg) / 打开序列文件」共用的读写器。
 * schema 字段与 main 的 JSON.stringify(fileData, null, 2) 输出一致：
 * format / version / meta{plasmidName, hostPreference, createdAt} / sequence /
 * features[{name,start,end,type,color,forward}] / selectedEnzymes / selectionNotes / notes / notebookPages
 */
object Spiceg {

    const val FORMAT = "SPICE_GENE"
    const val VERSION = "1.0"

    data class Parsed(
        val record: SeqRecord,
        val host: String,
        val enzymes: List<String>
    )

    /** main 判定：text.includes('"format": "SPICE_GENE"')（另兼容紧凑无空格写法） */
    fun isSpiceg(text: String): Boolean =
        text.contains("\"format\": \"SPICE_GENE\"") || text.contains("\"format\":\"SPICE_GENE\"")

    fun write(rec: SeqRecord, hostPreference: String, selectedEnzymes: List<String>): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        val root = JSONObject()
        root.put("format", FORMAT)
        root.put("version", VERSION)
        val meta = JSONObject()
        meta.put("plasmidName", rec.name)
        meta.put("hostPreference", hostPreference)
        meta.put("createdAt", sdf.format(Date()))
        root.put("meta", meta)
        root.put("sequence", rec.sequence)
        val feats = JSONArray()
        rec.features.forEach { f ->
            feats.put(
                JSONObject()
                    .put("name", f.name)
                    .put("start", f.start)
                    .put("end", f.end)
                    .put("type", f.type)
                    .put("color", "var(--pix-accent-2)")   // main 默认特征色
                    .put("forward", f.strand > 0)
            )
        }
        root.put("features", feats)
        root.put("selectedEnzymes", JSONArray(selectedEnzymes))
        root.put("selectionNotes", JSONArray())   // 注释/选区说明（main 端编辑，native 端暂空）
        root.put("notes", "")                      // 属性页 Markdown 笔记
        root.put("notebookPages", JSONArray())     // 电子实验记录本页面
        return root.toString(2)
    }

    /** 对齐 main loadSpiceg：format 校验失败返回 null */
    fun parseFull(text: String): Parsed? {
        return try {
            val root = JSONObject(text)
            if (root.optString("format") != FORMAT) return null
            val meta = root.optJSONObject("meta") ?: JSONObject()
            val feats = root.optJSONArray("features") ?: JSONArray()
            val features = (0 until feats.length()).map { i ->
                val f = feats.getJSONObject(i)
                Feature(
                    name = f.optString("name", "feature"),
                    type = f.optString("type", "misc"),
                    start = f.optInt("start", 1),
                    end = f.optInt("end", 1),
                    strand = if (f.optBoolean("forward", true)) 1 else -1
                )
            }
            val enzymes = root.optJSONArray("selectedEnzymes")?.let { arr ->
                (0 until arr.length()).map { arr.optString(it) }
            } ?: emptyList()
            Parsed(
                record = SeqRecord(
                    name = meta.optString("plasmidName", "未命名"),
                    sequence = root.optString("sequence", "").uppercase(),
                    circular = true,   // spiceg 为质粒工程文件，main 亦不存储线性标志
                    features = features,
                    description = root.optString("notes", "")
                ),
                host = meta.optString("hostPreference", "ECOLI"),
                enzymes = enzymes
            )
        } catch (_: Exception) {
            null
        }
    }
}
