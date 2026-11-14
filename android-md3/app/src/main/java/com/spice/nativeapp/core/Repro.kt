package com.spice.nativeapp.core

import android.content.Context
import java.io.File
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 可复现打包，对应原版 repro/bundle.ts + repro/zip.ts。
 * 产物是一个 ZIP：manifest.json（每个文件的 SHA-256）+ 序列 + 项目 JSON + 报告 + 备注。
 */
object Repro {

    data class BundleResult(val file: File, val entries: List<String>, val bytes: Long)

    fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /**
     * @param records 要打包的序列
     * @param projectJson 项目 JSON 文本（可为空）
     * @param notes 额外文本（备注、分析结论）
     */
    fun buildBundle(
        ctx: Context,
        records: List<SeqRecord>,
        projectJson: String?,
        notes: String?,
        extraFiles: List<Pair<String, String>> = emptyList()
    ): BundleResult {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.CHINA).format(Date())
        val dir = File(ctx.filesDir, "repro").apply { mkdirs() }
        val zipFile = File(dir, "spice-repro-$stamp.zip")

        val files = linkedMapOf<String, ByteArray>()
        records.forEachIndexed { i, rec ->
            val safe = rec.name.replace(Regex("[^A-Za-z0-9._-]"), "_").ifEmpty { "record${i + 1}" }
            files["sequences/$safe.fasta"] = SeqIO.toFasta(rec).toByteArray()
            files["sequences/$safe.gb"] = SeqIO.toGenBank(rec).toByteArray()
        }
        projectJson?.let { files["project/project.json"] = it.toByteArray() }
        notes?.takeIf { it.isNotBlank() }?.let { files["notes.md"] = it.toByteArray() }
        extraFiles.forEach { (path, content) -> files[path] = content.toByteArray() }

        val manifest = buildString {
            appendLine("{")
            appendLine("  \"format\": \"spice-repro/1\",")
            appendLine("  \"createdAt\": \"${SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.CHINA).format(Date())}\",")
            appendLine("  \"app\": \"SPICE native (Kotlin/MD3)\",")
            appendLine("  \"recordCount\": ${records.size},")
            appendLine("  \"files\": [")
            files.entries.forEachIndexed { i, (path, bytes) ->
                val comma = if (i == files.size - 1) "" else ","
                appendLine("    { \"path\": \"$path\", \"bytes\": ${bytes.size}, \"sha256\": \"${sha256Hex(bytes)}\" }$comma")
            }
            appendLine("  ]")
            appendLine("}")
        }
        files["manifest.json"] = manifest.toByteArray()

        ZipOutputStream(zipFile.outputStream().buffered()).use { zos ->
            files.forEach { (path, bytes) ->
                zos.putNextEntry(ZipEntry(path))
                zos.write(bytes)
                zos.closeEntry()
            }
        }
        return BundleResult(zipFile, files.keys.toList(), zipFile.length())
    }

    // ---------------------------------------------------------------------------
    // 通用可复现数据包：1:1 移植自原版 src/lib/repro/bundle.ts（buildReproBundle）。
    // 与上面的序列 buildBundle（spice-repro/1）不同，这是全工具共享的
    // SPICE_REPRO v1 格式：figures + data + project + parameters + methods +
    // software 元数据，带 SHA-256 清单，STORE 方式打包为普通 ZIP。
    // ---------------------------------------------------------------------------

    class ReproFile(val name: String, val mime: String, val data: ByteArray) {
        constructor(name: String, mime: String, data: String) : this(name, mime, data.toByteArray(Charsets.UTF_8))
    }

    class ReproBundleInput(
        val tool: String,
        val title: String,
        val files: List<ReproFile>,
        val parameters: org.json.JSONObject? = null,
        val methods: String? = null,
        val notes: String? = null,
        val project: org.json.JSONObject? = null,
        val author: String? = null,
        val orcid: String? = null,
    )

    class ReproBundleResult(val file: File, val fileName: String)

    /** 对应原版 zipStore：STORE（无压缩）条目，UTF-8 文件名。 */
    fun zipStore(entries: List<Pair<String, ByteArray>>, out: File) {
        java.util.zip.CRC32().let { }
        ZipOutputStream(out.outputStream().buffered()).use { zos ->
            zos.setLevel(java.util.zip.Deflater.NO_COMPRESSION)
            entries.forEach { (name, data) ->
                val e = ZipEntry(name.replace("\\", "/").replace(Regex("^/+"), ""))
                val crc = java.util.zip.CRC32().apply { update(data) }
                e.method = ZipEntry.STORED
                e.size = data.size.toLong()
                e.compressedSize = data.size.toLong()
                e.crc = crc.value
                zos.putNextEntry(e)
                zos.write(data)
                zos.closeEntry()
            }
        }
    }

    private fun reproSafeStem(text: String, fallback: String): String {
        val stem = text.trim().lowercase()
            .replace(Regex("[^a-z0-9]+"), "-")
            .replace(Regex("^-+|-+$"), "")
            .take(72)
        return stem.ifEmpty { fallback }
    }

    private fun reproUniqueName(name: String, used: MutableSet<String>): String {
        val clean = name.replace("\\", "/").replace(Regex("^/+"), "").ifEmpty { "file" }
        if (used.add(clean)) return clean
        val dot = clean.lastIndexOf('.')
        val base = if (dot > 0) clean.substring(0, dot) else clean
        val ext = if (dot > 0) clean.substring(dot) else ""
        var i = 2
        while (used.contains("$base-$i$ext")) i++
        val next = "$base-$i$ext"
        used.add(next)
        return next
    }

    private fun appVersion(ctx: Context): String =
        try { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "unknown" } catch (e: Exception) { "unknown" }

    private fun reproMethodsDoc(input: ReproBundleInput, version: String, generatedAt: String): String {
        val lines = mutableListOf(
            "# ${input.title}", "",
            "Tool: ${input.tool}",
            "Generated: $generatedAt",
            "Software: SPICE GUI $version",
        )
        input.author?.let { lines.add("Author: $it") }
        input.orcid?.let { lines.add("ORCID: $it") }
        lines.addAll(
            listOf(
                "", "## Methods", "",
                (input.methods?.trim()
                    ?: "Processing parameters are recorded in parameters.json. Figure and data files were exported from the same in-memory project state, so the bundle can be regenerated from project.json with the recorded software version."),
                "", "## Parameters", "",
                "```json",
                input.parameters?.toString(2) ?: "{}",
                "```", "",
            )
        )
        if (!input.notes.isNullOrBlank()) lines.addAll(listOf("## Notes", "", input.notes.trim(), ""))
        return lines.joinToString("\n")
    }

    private fun reproReadmeDoc(input: ReproBundleInput, version: String, files: List<String>, generatedAt: String): String =
        listOf(
            "# ${input.title} — reproducibility bundle", "",
            "Generated by SPICE GUI $version on $generatedAt.", "",
            "Files:",
            *files.map { "- `$it`" }.toTypedArray(), "",
            "The bundle is self-contained: `project.json` re-opens in the same tool, `parameters.json` records every processing setting, and `methods.md` carries a draft methods paragraph. `manifest.sha256.json` lists the SHA-256 digest of every other file.", "",
            "Verify integrity with:", "",
            "```bash",
            "shasum -a 256 *",
            "```", "",
        ).joinToString("\n")

    /** 原版从 localStorage 读作者/ORCID；安卓侧对应 SharedPreferences("spice_settings")。 */
    private fun reproStored(ctx: Context, key: String): String? {
        val v = ctx.getSharedPreferences("spice_settings", Context.MODE_PRIVATE).getString(key, null)?.trim()
        return if (v.isNullOrEmpty()) null else v
    }

    fun buildReproBundle(ctx: Context, input: ReproBundleInput): ReproBundleResult {
        val version = appVersion(ctx)
        val generatedAt = java.time.Instant.now().toString()
        val author = input.author ?: reproStored(ctx, "spice_author_name")
        val orcid = input.orcid ?: reproStored(ctx, "spice_orcid")
        val used = mutableSetOf<String>()
        val out = ArrayList<ReproFile>()

        for (file in input.files) out.add(ReproFile(reproUniqueName(file.name, used), file.mime, file.data))
        input.project?.let {
            out.add(ReproFile(reproUniqueName("project.json", used), "application/json", it.toString(2)))
        }
        out.add(ReproFile(
            reproUniqueName("parameters.json", used), "application/json",
            org.json.JSONObject().apply {
                put("tool", input.tool)
                put("title", input.title)
                put("generatedAt", generatedAt)
                put("parameters", input.parameters ?: org.json.JSONObject())
            }.toString(2),
        ))
        out.add(ReproFile(reproUniqueName("methods.md", used), "text/markdown", reproMethodsDoc(withAuthor(input, author, orcid), version, generatedAt)))
        out.add(ReproFile(
            reproUniqueName("software.json", used), "application/json",
            org.json.JSONObject().apply {
                put("name", "SPICE GUI")
                put("version", version)
                put("platform", "Android ${android.os.Build.VERSION.RELEASE}")
                put("locale", java.util.Locale.getDefault().toLanguageTag())
                put("generatedAt", generatedAt)
            }.toString(2),
        ))

        val entries = ArrayList<Pair<String, ByteArray>>()
        val manifestFiles = ArrayList<org.json.JSONObject>()
        for (file in out) {
            entries.add(file.name to file.data)
            manifestFiles.add(org.json.JSONObject().apply {
                put("name", file.name)
                put("mime", file.mime)
                put("bytes", file.data.size)
                put("sha256", sha256Hex(file.data))
            })
        }

        val readme = reproReadmeDoc(withAuthor(input, author, orcid), version, out.map { it.name }, generatedAt)
        val readmeName = reproUniqueName("README.md", used)
        val readmeBytes = readme.toByteArray(Charsets.UTF_8)
        entries.add(readmeName to readmeBytes)
        manifestFiles.add(org.json.JSONObject().apply {
            put("name", readmeName)
            put("mime", "text/markdown")
            put("bytes", readmeBytes.size)
            put("sha256", sha256Hex(readmeBytes))
        })

        val manifest = org.json.JSONObject().apply {
            put("format", "SPICE_REPRO")
            put("version", 1)
            put("tool", input.tool)
            put("title", input.title)
            put("generatedAt", generatedAt)
            put("software", org.json.JSONObject().apply {
                put("name", "SPICE GUI")
                put("version", version)
            })
            put("files", org.json.JSONArray().apply { manifestFiles.forEach { put(it) } })
        }
        entries.add("manifest.sha256.json" to manifest.toString(2).toByteArray(Charsets.UTF_8))

        val fileName = "${reproSafeStem(input.title, input.tool)}-repro.zip"
        val dir = File(ctx.filesDir, "repro").apply { mkdirs() }
        val zipFile = File(dir, fileName)
        zipStore(entries, zipFile)
        return ReproBundleResult(zipFile, fileName)
    }

    private fun withAuthor(input: ReproBundleInput, author: String?, orcid: String?): ReproBundleInput =
        ReproBundleInput(input.tool, input.title, input.files, input.parameters, input.methods, input.notes, input.project, author, orcid)
}
