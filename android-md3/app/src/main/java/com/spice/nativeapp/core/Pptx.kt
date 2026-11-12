package com.spice.nativeapp.core

import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** 待写入 PPTX 的形状（坐标均为 0..1 的归一化值，相对 16:9 画布） */
sealed class PptxShape {
    data class Box(val label: String, val x: Float, val y: Float, val w: Float, val h: Float, val hex: String) : PptxShape()
    data class Label(val text: String, val x: Float, val y: Float) : PptxShape()
    data class Arrow(val x1: Float, val y1: Float, val x2: Float, val y2: Float) : PptxShape()
    data class Image(
        val png: ByteArray, val x: Float, val y: Float, val w: Float, val h: Float
    ) : PptxShape() {
        override fun equals(other: Any?) = this === other
        override fun hashCode() = System.identityHashCode(this)
    }
}

/**
 * 最小合法 OOXML PPTX 打包：单张 16:9 幻灯片，盒子/标签/箭头为原生对象。
 * 不引入第三方 pptx 库，输出可被 PowerPoint / WPS / LibreOffice 打开。
 */
object PptxWriter {

    private const val EMU_IN = 914400
    private const val SLIDE_W = 12192000L   // 13.333 in
    private const val SLIDE_H = 6858000L    // 7.5 in

    private fun emu(norm: Float, total: Long): Long = (norm.coerceIn(0f, 1f) * total).toLong()

    private fun esc(s: String): String = s
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("\"", "&quot;")

    fun write(shapes: List<PptxShape>, out: OutputStream) {
        ZipOutputStream(out).use { zip ->
            fun entry(name: String, body: String) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(body.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
            entry("[Content_Types].xml", contentTypes())
            entry("_rels/.rels", rootRels())
            entry("ppt/presentation.xml", presentation())
            entry("ppt/_rels/presentation.xml.rels", presentationRels())
            entry("ppt/slideMasters/slideMaster1.xml", slideMaster())
            entry("ppt/slideMasters/_rels/slideMaster1.xml.rels", masterRels())
            entry("ppt/slideLayouts/slideLayout1.xml", slideLayout())
            entry("ppt/slideLayouts/_rels/slideLayout1.xml.rels", layoutRels())
            val images = shapes.filterIsInstance<PptxShape.Image>()
            entry("ppt/slides/slide1.xml", slide(shapes))
            entry("ppt/slides/_rels/slide1.xml.rels", slideRels(images.size))
            images.forEachIndexed { i, img ->
                zip.putNextEntry(ZipEntry("ppt/media/image${i + 1}.png"))
                zip.write(img.png)
                zip.closeEntry()
            }
            entry("ppt/theme/theme1.xml", theme())
        }
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

    private fun presentation() = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<p:presentation xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships" xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main">
  <p:sldMasterIdLst><p:sldMasterId id="2147483648" r:id="rId1"/></p:sldMasterIdLst>
  <p:sldIdLst><p:sldId id="256" r:id="rId2"/></p:sldIdLst>
  <p:sldSz cx="$SLIDE_W" cy="$SLIDE_H" type="screen16x9"/>
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

    private fun slideRels(imageCount: Int): String {
        val extra = StringBuilder()
        for (i in 1..imageCount) {
            extra.append("""  <Relationship Id="rId${i + 1}" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/image" Target="../media/image$i.png"/>
""")
        }
        return """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
  <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/slideLayout" Target="../slideLayouts/slideLayout1.xml"/>
$extra</Relationships>"""
    }

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

    private fun slide(shapes: List<PptxShape>): String {
        val sb = StringBuilder()
        sb.append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<p:sld xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships" xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main">
  <p:cSld><p:spTree>
    <p:nvGrpSpPr><p:cNvPr id="1" name=""/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr>
    <p:grpSpPr><a:xfrm><a:off x="0" y="0"/><a:ext cx="0" cy="0"/><a:chOff x="0" y="0"/><a:chExt cx="0" cy="0"/></a:xfrm></p:grpSpPr>""")
        var id = 2
        var imageSeq = 0
        shapes.forEach { shape ->
            when (shape) {
                is PptxShape.Box -> {
                    val x = emu(shape.x, SLIDE_W); val y = emu(shape.y, SLIDE_H)
                    val cx = emu(shape.w, SLIDE_W); val cy = emu(shape.h, SLIDE_H)
                    sb.append("""
    <p:sp><p:nvSpPr><p:cNvPr id="$id" name="Box"/><p:cNvSpPr/><p:nvPr/></p:nvSpPr>
      <p:spPr><a:xfrm><a:off x="$x" y="$y"/><a:ext cx="$cx" cy="$cy"/></a:xfrm>
        <a:prstGeom prst="rect"><a:avLst/></a:prstGeom>
        <a:solidFill><a:srgbClr val="${shape.hex}"/></a:solidFill>
        <a:ln w="12700"><a:solidFill><a:srgbClr val="FFFFFF"/></a:solidFill></a:ln></p:spPr>
      <p:txBody><a:bodyPr/><a:lstStyle/><a:p><a:pPr algn="ctr"/><a:r><a:rPr lang="zh-CN" sz="1200"/><a:t>${esc(shape.label)}</a:t></a:r></a:p></p:txBody>
    </p:sp>""")
                }
                is PptxShape.Label -> {
                    val x = emu(shape.x, SLIDE_W); val y = emu(shape.y, SLIDE_H)
                    sb.append("""
    <p:sp><p:nvSpPr><p:cNvPr id="$id" name="Label"/><p:cNvSpPr txBox="1"/><p:nvPr/></p:nvSpPr>
      <p:spPr><a:xfrm><a:off x="$x" y="$y"/><a:ext cx="4572000" cy="685800"/></a:xfrm>
        <a:prstGeom prst="rect"><a:avLst/></a:prstGeom><a:noFill/></p:spPr>
      <p:txBody><a:bodyPr/><a:lstStyle/><a:p><a:r><a:rPr lang="zh-CN" sz="1400" b="1"><a:solidFill><a:srgbClr val="16202B"/></a:solidFill></a:rPr><a:t>${esc(shape.text)}</a:t></a:r></a:p></p:txBody>
    </p:sp>""")
                }
                is PptxShape.Image -> {
                    imageSeq++
                    val x = emu(shape.x, SLIDE_W); val y = emu(shape.y, SLIDE_H)
                    val cx = emu(shape.w, SLIDE_W).coerceAtLeast(1L)
                    val cy = emu(shape.h, SLIDE_H).coerceAtLeast(1L)
                    val rid = "rId${imageSeq + 1}"
                    sb.append("""
    <p:pic><p:nvPicPr><p:cNvPr id="$id" name="Picture $imageSeq"/><p:cNvPicPr><a:picLocks noChangeAspect="1"/></p:cNvPicPr><p:nvPr/></p:nvPicPr>
      <p:blipFill><a:blip r:embed="$rid"/><a:stretch><a:fillRect/></a:stretch></p:blipFill>
      <p:spPr><a:xfrm><a:off x="$x" y="$y"/><a:ext cx="$cx" cy="$cy"/></a:xfrm><a:prstGeom prst="rect"><a:avLst/></a:prstGeom></p:spPr>
    </p:pic>""")
                }
                is PptxShape.Arrow -> {
                    val x = emu(minOf(shape.x1, shape.x2), SLIDE_W); val y = emu(minOf(shape.y1, shape.y2), SLIDE_H)
                    val cx = (emu(maxOf(shape.x1, shape.x2), SLIDE_W) - x).coerceAtLeast(1L)
                    val cy = (emu(maxOf(shape.y1, shape.y2), SLIDE_H) - y).coerceAtLeast(1L)
                    sb.append("""
    <p:cxnSp><p:nvCxnSpPr><p:cNvPr id="$id" name="Arrow"/><p:cNvCxnSpPr/><p:nvPr/></p:nvCxnSpPr>
      <p:spPr><a:xfrm><a:off x="$x" y="$y"/><a:ext cx="$cx" cy="$cy"/></a:xfrm>
        <a:prstGeom prst="line"><a:avLst/></a:prstGeom>
        <a:ln w="19050"><a:solidFill><a:srgbClr val="9FA8FF"/></a:solidFill><a:tailEnd type="triangle"/></a:ln></p:spPr>
      <p:txBody><a:bodyPr/><a:lstStyle/><a:p/></p:txBody>
    </p:cxnSp>""")
                }
            }
            id++
        }
        sb.append("""
  </p:spTree></p:cSld>
  <p:clrMapOvr><a:masterClrMapping/></p:clrMapOvr>
</p:sld>""")
        return sb.toString()
    }
}
