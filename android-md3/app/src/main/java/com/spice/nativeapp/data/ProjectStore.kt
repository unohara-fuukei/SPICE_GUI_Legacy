package com.spice.nativeapp.data

import android.content.Context
import com.spice.nativeapp.core.Feature
import com.spice.nativeapp.core.SeqRecord
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** 项目内文档（实验记录/方案等纯文本） */
data class DocEntry(val title: String, val body: String)

/** 项目附件（已复制到应用私有目录） */
data class Attachment(val name: String, val relPath: String, val size: Long)

/** 项目（一条序列 + 元信息） */
data class ProjectItem(
    val id: Long,
    val name: String,
    val note: String,
    val sequence: String,
    val circular: Boolean,
    val features: List<Feature>,
    val updatedAt: Long,
    val noteDoc: String = "",
    val history: List<String> = emptyList(),
    val attachments: List<Attachment> = emptyList(),
    val docs: List<DocEntry> = emptyList()
)

object ProjectStore {

    private const val FILE_NAME = "projects.json"

    private fun file(ctx: Context) = File(ctx.filesDir, FILE_NAME)

    fun load(ctx: Context): List<ProjectItem> {
        val f = file(ctx)
        if (!f.exists()) return emptyList()
        return runCatching {
            val arr = JSONArray(f.readText())
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                val feats = mutableListOf<Feature>()
                val fa = o.optJSONArray("features") ?: JSONArray()
                for (k in 0 until fa.length()) {
                    val fo = fa.getJSONObject(k)
                    feats.add(
                        Feature(
                            name = fo.getString("name"),
                            type = fo.optString("type", "misc"),
                            start = fo.getInt("start"),
                            end = fo.getInt("end"),
                            strand = fo.optInt("strand", 1)
                        )
                    )
                }
                val hist = mutableListOf<String>()
                val ha = o.optJSONArray("history") ?: JSONArray()
                for (k in 0 until ha.length()) hist.add(ha.getString(k))
                val docs = mutableListOf<DocEntry>()
                val da = o.optJSONArray("docs") ?: JSONArray()
                for (k in 0 until da.length()) {
                    val dobj = da.getJSONObject(k)
                    docs.add(DocEntry(dobj.optString("title", "文档"), dobj.optString("body", "")))
                }
                val atts = mutableListOf<Attachment>()
                val aa = o.optJSONArray("attachments") ?: JSONArray()
                for (k in 0 until aa.length()) {
                    val ao = aa.getJSONObject(k)
                    atts.add(Attachment(ao.getString("name"), ao.getString("path"), ao.optLong("size", 0L)))
                }
                ProjectItem(
                    id = o.getLong("id"),
                    name = o.getString("name"),
                    note = o.optString("note", ""),
                    sequence = o.getString("sequence"),
                    circular = o.optBoolean("circular", true),
                    features = feats,
                    updatedAt = o.optLong("updatedAt", 0L),
                    noteDoc = o.optString("noteDoc", ""),
                    history = hist,
                    attachments = atts,
                    docs = docs
                )
            }
        }.getOrDefault(emptyList())
    }

    fun save(ctx: Context, items: List<ProjectItem>) {
        val arr = JSONArray()
        items.forEach { p ->
            val o = JSONObject()
            o.put("id", p.id)
            o.put("name", p.name)
            o.put("note", p.note)
            o.put("sequence", p.sequence)
            o.put("circular", p.circular)
            o.put("updatedAt", p.updatedAt)
            val fa = JSONArray()
            p.features.forEach { f ->
                val fo = JSONObject()
                fo.put("name", f.name)
                fo.put("type", f.type)
                fo.put("start", f.start)
                fo.put("end", f.end)
                fo.put("strand", f.strand)
                fa.put(fo)
            }
            o.put("features", fa)
            o.put("noteDoc", p.noteDoc)
            val ha = JSONArray()
            p.history.forEach { ha.put(it) }
            o.put("history", ha)
            val attArr = JSONArray()
            p.attachments.forEach { a ->
                val ao = JSONObject()
                ao.put("name", a.name); ao.put("path", a.relPath); ao.put("size", a.size)
                attArr.put(ao)
            }
            o.put("attachments", attArr)
            val docArr = JSONArray()
            p.docs.forEach { dg ->
                val dobj = JSONObject()
                dobj.put("title", dg.title); dobj.put("body", dg.body)
                docArr.put(dobj)
            }
            o.put("docs", docArr)
            arr.put(o)
        }
        file(ctx).writeText(arr.toString())
    }

    fun toRecord(p: ProjectItem): SeqRecord = SeqRecord(
        name = p.name,
        sequence = p.sequence,
        circular = p.circular,
        features = p.features,
        description = p.note
    )

    fun fromRecord(rec: SeqRecord, note: String = ""): ProjectItem = ProjectItem(
        id = System.currentTimeMillis(),
        name = rec.name,
        note = note.ifBlank { rec.description },
        sequence = rec.sequence,
        circular = rec.circular,
        features = rec.features,
        updatedAt = System.currentTimeMillis(),
        noteDoc = note.ifBlank { rec.description },
        history = listOf("${System.currentTimeMillis()}|创建项目：${rec.name}（${rec.length} bp）")
    )
}
