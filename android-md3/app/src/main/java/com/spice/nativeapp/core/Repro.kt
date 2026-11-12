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
}
