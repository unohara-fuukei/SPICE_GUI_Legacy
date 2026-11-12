package com.spice.nativeapp.core

import android.content.Context
import android.net.Uri
import java.io.InputStream
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

/**
 * 轻量 XLSX 读取：只解析第一个工作表与共享字符串表，够用于 qPCR 仪器导出的板图。
 * 依赖 Android 内置的 ZipInputStream + DOM（无第三方库）。
 */
object XlsxReader {

    private const val NS_MAIN = "http://schemas.openxmlformats.org/spreadsheetml/2006/main"

    fun readRows(ctx: Context, uri: Uri): List<List<String>>? = runCatching {
        ctx.contentResolver.openInputStream(uri)?.use { parse(it) }
    }.getOrNull()

    fun parse(input: InputStream): List<List<String>> {
        var sharedStrings: List<String> = emptyList()
        var sheetXml: String? = null
        ZipInputStream(input).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val name = entry.name
                if (name == "xl/sharedStrings.xml") sharedStrings = parseSharedStrings(zip)
                else if (name.startsWith("xl/worksheets/sheet") && name.endsWith(".xml") && sheetXml == null) {
                    sheetXml = zip.readBytes().toString(Charsets.UTF_8)
                }
                zip.closeEntry()
            }
        }
        val xml = sheetXml ?: return emptyList()
        return parseSheet(xml, sharedStrings)
    }

    private fun parseSharedStrings(input: InputStream): List<String> {
        val doc = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder().parse(input)
        val items = doc.getElementsByTagNameNS(NS_MAIN, "si")
        val out = mutableListOf<String>()
        for (i in 0 until items.length) {
            val si = items.item(i) as Element
            val ts = si.getElementsByTagNameNS(NS_MAIN, "t")
            val sb = StringBuilder()
            for (j in 0 until ts.length) sb.append(ts.item(j).textContent ?: "")
            out.add(sb.toString())
        }
        return out
    }

    private fun parseSheet(xml: String, shared: List<String>): List<List<String>> {
        val doc = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder().parse(xml.byteInputStream())
        val rowNodes = doc.getElementsByTagNameNS(NS_MAIN, "row")
        val rows = mutableListOf<List<String>>()
        for (i in 0 until rowNodes.length) {
            val row = rowNodes.item(i) as Element
            val cells = row.getElementsByTagNameNS(NS_MAIN, "c")
            val values = mutableListOf<String>()
            for (j in 0 until cells.length) {
                val cell = cells.item(j) as Element
                val ref = cell.getAttribute("r")
                val col = columnIndex(ref)
                while (values.size < col) values.add("")
                val type = cell.getAttribute("t")
                val vNode = cell.getElementsByTagNameNS(NS_MAIN, "v")
                val raw = if (vNode.length > 0) vNode.item(0).textContent else ""
                val text = when (type) {
                    "s" -> shared.getOrNull(raw.toIntOrNull() ?: -1) ?: ""
                    else -> raw
                }
                values.add(text)
            }
            rows.add(values)
        }
        return rows
    }

    /** "B12" → 1（0 基列号） */
    private fun columnIndex(ref: String): Int {
        var idx = 0
        for (ch in ref) {
            if (ch !in 'A'..'Z') break
            idx = idx * 26 + (ch - 'A' + 1)
        }
        return (idx - 1).coerceAtLeast(0)
    }
}
