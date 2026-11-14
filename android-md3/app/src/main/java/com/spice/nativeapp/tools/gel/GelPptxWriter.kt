package com.spice.nativeapp.tools.gel

import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Editable gel-figure PPTX：替代原版 pptxgenjs 的 makePptx。
 * 版面尺寸 = layout.width/96 × layout.height/96 英寸（对应 defineLayout GEL_FIGURE）；
 * 凝胶图为裁剪位图（srcRect），所有文字是真实可编辑文本框。
 */
object GelPptxWriter {

    private const val EMU_IN = 914400
    private const val EMU_PT = 12700

    private fun emu(inches: Double): Long = Math.round(inches * EMU_IN)

    private fun esc(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            .replace("\"", "&quot;")

    /** pptxgenjs 的 color.replace('#','')：原样去 #，非 hex 值按字面输出。 */
    private fun hexColor(color: String): String = color.replace("#", "")

    fun write(layout: FigureLayout, gelPng: ByteArray, imgW: Int, imgH: Int): ByteArray {
        val slideW = emu(layout.width / 96)
        val slideH = emu(layout.height / 96)
        val sb = StringBuilder()
        sb.append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<p:sld xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships" xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main">
  <p:cSld><p:bg><a:bgPr><a:solidFill><a:srgbClr val="FFFFFF"/></a:solidFill><a:effectLst/></a:bgPr></p:bg><p:spTree>
    <p:nvGrpSpPr><p:cNvPr id="1" name=""/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr>
    <p:grpSpPr><a:xfrm><a:off x="0" y="0"/><a:ext cx="0" cy="0"/><a:chOff x="0" y="0"/><a:chExt cx="0" cy="0"/></a:xfrm></p:grpSpPr>""")
        var id = 2
        val crop = layout.crop
        val imgWIn = imgW / 96.0
        val imgHIn = imgH / 96.0
        for (item in layout.items) {
            when (item) {
                is FigItem.Image -> {
                    val boxX: Double
                    val boxY: Double
                    val boxW: Double
                    val boxH: Double
                    if (item.source == "gel") {
                        boxX = crop.left * imgWIn
                        boxY = crop.top * imgHIn
                        boxW = (1 - crop.left - crop.right) * imgWIn
                        boxH = (1 - crop.top - crop.bottom) * imgHIn
                    } else {
                        val b = item.box!!
                        boxX = b.x * imgWIn
                        boxY = b.y * imgHIn
                        boxW = b.w * imgWIn
                        boxH = b.h * imgHIn
                    }
                    // srcRect：OOXML 千分之一百分比（100000 = 100%）
                    val l = Math.round(boxX / imgWIn * 100000).toInt()
                    val t = Math.round(boxY / imgHIn * 100000).toInt()
                    val r = Math.round((imgWIn - boxX - boxW) / imgWIn * 100000).toInt()
                    val b2 = Math.round((imgHIn - boxY - boxH) / imgHIn * 100000).toInt()
                    val srcRect = buildString {
                        if (l > 0) append(" l=\"$l\"")
                        if (t > 0) append(" t=\"$t\"")
                        if (r > 0) append(" r=\"$r\"")
                        if (b2 > 0) append(" b=\"$b2\"")
                    }
                    sb.append("""
    <p:pic><p:nvPicPr><p:cNvPr id="$id" name="Picture $id"/><p:cNvPicPr><a:picLocks noChangeAspect="1"/></p:cNvPicPr><p:nvPr/></p:nvPicPr>
      <p:blipFill><a:blip r:embed="rId2"/><a:srcRect$srcRect/><a:stretch><a:fillRect/></a:stretch></p:blipFill>
      <p:spPr><a:xfrm><a:off x="${emu(item.x / 96)}" y="${emu(item.y / 96)}"/><a:ext cx="${emu(item.w / 96).coerceAtLeast(1)}" cy="${emu(item.h / 96).coerceAtLeast(1)}"/></a:xfrm><a:prstGeom prst="rect"><a:avLst/></a:prstGeom></p:spPr>
    </p:pic>""")
                }
                is FigItem.Text -> {
                    val s = item.style
                    val x = item.x / 96 - 0.02
                    val y = item.y / 96 - (if (s.baseline == "middle") s.size / 96 / 2 else s.size / 96)
                    val w = maxOf(0.1, item.bbox.w / 96 + 0.05)
                    val h = s.size / 96 * 1.6
                    val sz = Math.round(s.size * 0.75 * 100).toInt()
                    val algn = if (s.align == "center") "ctr" else if (s.align == "right") "r" else "l"
                    val rot = Math.round((s.angle ?: 0.0) * 60000).toInt()
                    val rotAttr = if (rot != 0) " rot=\"$rot\"" else ""
                    val bAttr = if (s.bold) " b=\"1\"" else ""
                    val iAttr = if (s.italic) " i=\"1\"" else ""
                    sb.append("""
    <p:sp><p:nvSpPr><p:cNvPr id="$id" name="Text $id"/><p:cNvSpPr txBox="1"/><p:nvPr/></p:nvSpPr>
      <p:spPr><a:xfrm$rotAttr><a:off x="${emu(x)}" y="${emu(y)}"/><a:ext cx="${emu(w)}" cy="${emu(h)}"/></a:xfrm>
        <a:prstGeom prst="rect"><a:avLst/></a:prstGeom><a:noFill/></p:spPr>
      <p:txBody><a:bodyPr lIns="0" tIns="0" rIns="0" bIns="0" anchor="ctr"/><a:lstStyle/><a:p><a:pPr algn="$algn"/><a:r><a:rPr lang="zh-CN" sz="$sz"$bAttr$iAttr dirty="0"><a:solidFill><a:srgbClr val="${hexColor(s.color)}"/></a:solidFill><a:latin typeface="${esc(s.family)}"/></a:rPr><a:t>${esc(item.text)}</a:t></a:r></a:p></p:txBody>
    </p:sp>""")
                }
                is FigItem.Line -> {
                    appendLineShape(sb, id, item.x1, item.y1, item.x2, item.y2, item.color, item.width, false)
                }
                is FigItem.Rect -> {
                    sb.append("""
    <p:sp><p:nvSpPr><p:cNvPr id="$id" name="Rect $id"/><p:cNvSpPr/><p:nvPr/></p:nvSpPr>
      <p:spPr><a:xfrm><a:off x="${emu(item.x / 96)}" y="${emu(item.y / 96)}"/><a:ext cx="${emu(item.w / 96).coerceAtLeast(1)}" cy="${emu(item.h / 96).coerceAtLeast(1)}"/></a:xfrm>
        <a:prstGeom prst="rect"><a:avLst/></a:prstGeom>
        <a:solidFill><a:srgbClr val="FFFFFF"><a:alpha val="0"/></a:srgbClr></a:solidFill>
        <a:ln w="${emu(maxOf(0.5, item.width * 0.75) / 72.0)}"><a:solidFill><a:srgbClr val="${hexColor(item.color)}"/></a:solidFill></a:ln></p:spPr>
      <p:txBody><a:bodyPr/><a:lstStyle/><a:p/></p:txBody>
    </p:sp>""")
                }
                is FigItem.Arrow -> {
                    appendLineShape(sb, id, item.x1, item.y1, item.x2, item.y2, item.color, item.width, true)
                }
            }
            id++
        }
        sb.append("""
  </p:spTree></p:cSld>
  <p:clrMapOvr><a:masterClrMapping/></p:clrMapOvr>
</p:sld>""")
        return packageZip(sb.toString(), slideW, slideH, gelPng)
    }

    private fun appendLineShape(
        sb: StringBuilder, id: Int,
        x1: Double, y1: Double, x2: Double, y2: Double,
        color: String, width: Double, arrow: Boolean,
    ) {
        val minX = minOf(x1, x2) / 96
        val minY = minOf(y1, y2) / 96
        val w = Math.abs(x2 - x1) / 96
        val h = Math.abs(y2 - y1) / 96
        val flipH = if (x2 < x1) " flipH=\"1\"" else ""
        val flipV = if (y2 < y1) " flipV=\"1\"" else ""
        val ptW = maxOf(0.5, width * 0.75)
        val tail = if (arrow) "<a:tailEnd type=\"triangle\"/>" else ""
        sb.append("""
    <p:cxnSp><p:nvCxnSpPr><p:cNvPr id="$id" name="Line $id"/><p:cNvCxnSpPr/><p:nvPr/></p:nvCxnSpPr>
      <p:spPr><a:xfrm$flipH$flipV><a:off x="${emu(minX)}" y="${emu(minY)}"/><a:ext cx="${emu(w).coerceAtLeast(1)}" cy="${emu(h).coerceAtLeast(1)}"/></a:xfrm>
        <a:prstGeom prst="line"><a:avLst/></a:prstGeom>
        <a:ln w="${emu(ptW / 72.0)}"><a:solidFill><a:srgbClr val="${hexColor(color)}"/></a:solidFill>$tail</a:ln></p:spPr>
      <p:txBody><a:bodyPr/><a:lstStyle/><a:p/></p:txBody>
    </p:cxnSp>""")
    }

    private fun packageZip(slideXml: String, slideW: Long, slideH: Long, gelPng: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            fun entry(name: String, body: String) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(body.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
            entry("[Content_Types].xml", contentTypes())
            entry("_rels/.rels", rootRels())
            entry("ppt/presentation.xml", presentation(slideW, slideH))
            entry("ppt/_rels/presentation.xml.rels", presentationRels())
            entry("ppt/slideMasters/slideMaster1.xml", slideMaster())
            entry("ppt/slideMasters/_rels/slideMaster1.xml.rels", masterRels())
            entry("ppt/slideLayouts/slideLayout1.xml", slideLayout())
            entry("ppt/slideLayouts/_rels/slideLayout1.xml.rels", layoutRels())
            entry("ppt/slides/slide1.xml", slideXml)
            entry("ppt/slides/_rels/slide1.xml.rels", slideRels())
            zip.putNextEntry(ZipEntry("ppt/media/image1.png"))
            zip.write(gelPng)
            zip.closeEntry()
            entry("ppt/theme/theme1.xml", theme())
        }
        return out.toByteArray()
    }

    private fun contentTypes() = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
  <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
  <Default Extension="xml" ContentType="application/xml"/>
  <Default Extension="png" ContentType="image/png"/>
  <Override PartName="/ppt/presentation.xml" ContentType="application/vnd.openxmlformats-officedocument.presentationml.presentation.main+xml"/>
  <Override PartName="/ppt/slideMasters/slideMaster1.xml" ContentType="application/vnd.openxmlformats-officedocument.presentationml.slideMaster+xml"/>
  <Override PartName="/ppt/slideLayouts/slideLayout1.xml" ContentType="application/vnd.openxmlformats-officedocument.presentationml.slideLayout+xml"/>
  <Override PartName="/ppt/slides/slide1.xml" ContentType="application/vnd.openxmlformats-officedocument.presentationml.slide+xml"/>
  <Override PartName="/ppt/theme/theme1.xml" ContentType="application/vnd.openxmlformats-officedocument.theme+xml"/>
</Types>"""

    private fun rootRels() = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
  <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="ppt/presentation.xml"/>
</Relationships>"""

    private fun presentation(slideW: Long, slideH: Long) = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<p:presentation xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships" xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main">
  <p:sldMasterIdLst><p:sldMasterId id="2147483648" r:id="rId1"/></p:sldMasterIdLst>
  <p:sldIdLst><p:sldId id="256" r:id="rId2"/></p:sldIdLst>
  <p:sldSz cx="$slideW" cy="$slideH"/>
  <p:notesSz cx="6858000" cy="9144000"/>
</p:presentation>"""

    private fun presentationRels() = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
  <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/slideMaster" Target="slideMasters/slideMaster1.xml"/>
  <Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/slide" Target="slides/slide1.xml"/>
</Relationships>"""

    private fun slideMaster() = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<p:sldMaster xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships" xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main">
  <p:cSld><p:bg><a:bgPr><a:solidFill><a:srgbClr val="FFFFFF"/></a:solidFill><a:effectLst/></a:bgPr></p:bg><p:spTree>
    <p:nvGrpSpPr><p:cNvPr id="1" name=""/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr>
    <p:grpSpPr><a:xfrm><a:off x="0" y="0"/><a:ext cx="0" cy="0"/><a:chOff x="0" y="0"/><a:chExt cx="0" cy="0"/></a:xfrm></p:grpSpPr>
  </p:spTree></p:cSld>
  <p:sldLayoutIdLst><p:sldLayoutId id="2147483649" r:id="rId1"/></p:sldLayoutIdLst>
</p:sldMaster>"""

    private fun masterRels() = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
  <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/slideLayout" Target="../slideLayouts/slideLayout1.xml"/>
  <Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/theme" Target="../theme/theme1.xml"/>
</Relationships>"""

    private fun slideLayout() = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<p:sldLayout xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships" xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main" type="blank">
  <p:cSld name="Blank"><p:spTree>
    <p:nvGrpSpPr><p:cNvPr id="1" name=""/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr>
    <p:grpSpPr><a:xfrm><a:off x="0" y="0"/><a:ext cx="0" cy="0"/><a:chOff x="0" y="0"/><a:chExt cx="0" cy="0"/></a:xfrm></p:grpSpPr>
  </p:spTree></p:cSld>
</p:sldLayout>"""

    private fun layoutRels() = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
  <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/slideMaster" Target="../slideMasters/slideMaster1.xml"/>
</Relationships>"""

    private fun slideRels() = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
  <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/slideLayout" Target="../slideLayouts/slideLayout1.xml"/>
  <Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/image" Target="../media/image1.png"/>
</Relationships>"""

    private fun theme() = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<a:theme xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" name="Spice">
  <a:themeElements>
    <a:clrScheme name="Spice">
      <a:dk1><a:sysClr val="windowText" lastClr="000000"/></a:dk1>
      <a:lt1><a:sysClr val="window" lastClr="FFFFFF"/></a:lt1>
      <a:dk2><a:srgbClr val="16202B"/></a:dk2><a:lt2><a:srgbClr val="E2E8F0"/></a:lt2>
      <a:accent1><a:srgbClr val="69C9F0"/></a:accent1><a:accent2><a:srgbClr val="7FE7D6"/></a:accent2>
      <a:accent3><a:srgbClr val="FFC56E"/></a:accent3><a:accent4><a:srgbClr val="9FA8FF"/></a:accent4>
      <a:accent5><a:srgbClr val="F9A8D4"/></a:accent5><a:accent6><a:srgbClr val="86EFAC"/></a:accent6>
      <a:hlink><a:srgbClr val="2563EB"/></a:hlink><a:folHlink><a:srgbClr val="7C3AED"/></a:folHlink>
    </a:clrScheme>
    <a:fontScheme name="Spice">
      <a:majorFont><a:latin typeface="Arial"/><a:ea typeface="Microsoft YaHei"/></a:majorFont>
      <a:minorFont><a:latin typeface="Arial"/><a:ea typeface="Microsoft YaHei"/></a:minorFont>
    </a:fontScheme>
  </a:themeElements>
</a:theme>"""
}
