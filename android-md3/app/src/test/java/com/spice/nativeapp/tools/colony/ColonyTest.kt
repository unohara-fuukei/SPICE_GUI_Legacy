package com.spice.nativeapp.tools.colony

/*
 * T6 colony tool algorithm-layer parity tests.
 *
 * Reference values were produced by running the ORIGINAL TypeScript sources
 * (SPICE_main src/lib/tools/colony/{types,segment,markers,color,projectDoc}.ts)
 * under Node.js (tsx); see /tmp/colony_gen.ts. projectJson/projectBaseName
 * were taken verbatim from export.ts with the clock stubbed.
 *
 * Float-width note: the original gray planes are Float32Array while the
 * Kotlin port keeps DoubleArray; blur/segment numbers are compared with a
 * small tolerance (~1e-3) instead of bit equality — the algorithm itself is
 * line-for-line identical.
 */
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.PI
import kotlin.math.sqrt

class ColonyTest {

    // ------------------------------------------------------------- fixtures

    /** 与生成器一致的 80×60 合成平板：斑驳背景 + 4 个亮斑。 */
    private fun plateGray(): DoubleArray {
        val w = 80
        val h = 60
        val gray = DoubleArray(w * h)
        for (i in gray.indices) gray[i] = (26 + ((i * 37) % 13)).toDouble()
        fun blob(cx: Int, cy: Int, r: Double, value: Double) {
            for (y in 0 until h) for (x in 0 until w) {
                val d = sqrt(((x - cx) * (x - cx) + (y - cy) * (y - cy)).toDouble())
                if (d <= r) gray[y * w + x] = value - (d / r) * 10
            }
        }
        blob(20, 15, 5.0, 225.0)
        blob(50, 20, 4.0, 205.0)
        blob(55, 25, 4.0, 215.0)
        blob(30, 45, 1.2, 240.0)
        return gray
    }

    private fun ramp(): DoubleArray =
        DoubleArray(16 * 16) { ((it * 17) % 256).toDouble() }

    private fun mk(id: Int, x: Double, y: Double, r: Double): ColonyMarker = ColonyMarker(
        id = id, x = x, y = y, radius = r, area = PI * r * r,
        meanIntensity = 180.0 + id, circularity = 0.7 + id * 0.05,
        included = id % 2 == 1, source = if (id > 4) SOURCE_MANUAL else SOURCE_AUTO,
        meanR = 0.0, meanG = 0.0, meanB = 0.0, hue = -1.0, saturation = 0.0,
        colonyClass = CLASS_UNCLASSIFIED, classManual = false,
    )

    private val markers = listOf(
        mk(1, 12.0, 8.0, 3.1), mk(2, 40.0, 5.0, 2.4), mk(3, 20.0, 30.0, 4.0),
        mk(4, 55.0, 25.0, 1.8), mk(5, 8.0, 44.0, 2.9), mk(6, 70.0, 50.0, 3.3),
    )

    private fun project(): ColonyProject = ColonyProject(
        version = 1, savedAt = "2026-01-01T00:00:00.000Z",
        imageDataUrl = "data:image/png;base64,QUJD", imageName = "plate 01.png",
        imageWidth = 800, imageHeight = 600, workWidth = 800, workHeight = 600,
        rotate = 90, crop = ColonyCrop(0.1, 0.0, 0.0, 0.2),
        adjust = defaultAdjust(), params = defaultParams(),
        markers = markers, dpi = 300, dilutionFactor = 1000000.0, platedVolume = 0.1,
    )

    // -------------------------------------------------- segment primitives

    @Test
    fun rampOtsu_and_background_match_reference() {
        val ramp = ramp()
        assertEquals(127, otsuThreshold(ramp))
        assertEquals(121, estimateBackground(ramp, 16, 16))
        assertEquals("bright", resolvePolarity(POLARITY_AUTO, 30, 160))
        assertEquals("dark", resolvePolarity(POLARITY_AUTO, 220, 120))
        assertEquals("dark", resolvePolarity(POLARITY_DARK, 220, 120))
        assertEquals("bright", resolvePolarity(POLARITY_BRIGHT, 30, 160))
    }

    @Test
    fun gaussianBlur_matches_reference_within_float32_tolerance() {
        val ramp = ramp()
        val blurred = gaussianBlur(ramp, 16, 16, 1.5)
        val head = listOf(
            8.2219820022583, 21.244810104370117, 37.98641586303711, 54.98641586303711,
            71.98641204833984, 88.98641204833984, 105.98641204833984, 122.98641204833984,
            139.98641967773438, 156.98641967773438, 173.98641967773438, 190.98641967773438,
            207.92727661132812, 223.2248992919922, 225.64585876464844, 208.01901245117188,
        )
        val tail = listOf(
            193.5181121826172, 89.8828353881836, 33.076515197753906, 32.77509689331055,
            48.07272720336914, 65.01358032226562, 82.01358032226562, 99.01358032226562,
            116.01358032226562, 133.01358032226562, 150.01358032226562, 167.01358032226562,
            184.01358032226562, 201.01358032226562, 217.75518798828125, 230.77801513671875,
        )
        for (i in 0..15) {
            assertEquals(head[i], blurred[i], 1e-3)
            assertEquals(tail[i], blurred[240 + i], 1e-3)
        }
    }

    @Test
    fun buildMask_head_matches_reference() {
        val mask = buildMask(ramp(), 128, RESOLVED_BRIGHT)
        for (i in 0..7) assertEquals(0, mask[i].toInt())
    }

    // ----------------------------------------------------------- segmentation

    @Test
    fun segmentDefault_matches_reference() {
        val gray = plateGray()
        val result = segmentGray(gray, 80, 60, SegmentRequest(
            threshold = null, polarity = POLARITY_AUTO, blurRadius = 1.0,
            minArea = 30.0, maxArea = 0.0, minCircularity = 0.0, splitTouching = true,
        ))
        assertEquals(97, result.otsu)
        assertEquals(97, result.threshold)
        assertEquals(32, result.background)
        assertEquals(RESOLVED_BRIGHT, result.polarity)
        assertEquals(80, result.width)
        assertEquals(60, result.height)
        assertEquals(183, result.mask.sum())
        // 孤立亮斑 + 粘连对（分水岭鞍部不足未分开，与原版一致）
        assertEquals(2, result.colonies.size)
        val c1 = result.colonies[0]
        assertEquals(20.5, c1.x, 1e-6)
        assertEquals(15.5, c1.y, 1e-6)
        assertEquals(81.0, c1.area, 1e-6)
        assertEquals(5.077706251929807, c1.radius, 1e-6)
        assertEquals(208.27928670247397, c1.meanIntensity, 1e-3)
        assertEquals(0.525762406902424, c1.circularity, 1e-3)
        val c2 = result.colonies[1]
        assertEquals(52.98453608247423, c2.x, 1e-4)
        assertEquals(23.015463917525775, c2.y, 1e-4)
        assertEquals(97.0, c2.area, 1e-6)
        assertEquals(5.556622981616415, c2.radius, 1e-5)
        assertEquals(191.70046816167144, c2.meanIntensity, 1e-3)
        assertEquals(0.3385938748868999, c2.circularity, 1e-3)
    }

    @Test
    fun segmentNoSplit_matches_reference() {
        val gray = plateGray()
        val result = segmentGray(gray, 80, 60, SegmentRequest(
            threshold = null, polarity = POLARITY_AUTO, blurRadius = 1.0,
            minArea = 30.0, maxArea = 0.0, minCircularity = 0.0, splitTouching = false,
        ))
        assertEquals(2, result.colonies.size)
        assertEquals(81.0, result.colonies[0].area, 1e-6)
        assertEquals(97.0, result.colonies[1].area, 1e-6)
    }

    @Test
    fun segmentManual_matches_reference() {
        val gray = plateGray()
        val result = segmentGray(gray, 80, 60, SegmentRequest(
            threshold = 100, polarity = POLARITY_BRIGHT, blurRadius = 0.6,
            minArea = 20.0, maxArea = 500.0, minCircularity = 0.3, splitTouching = true,
        ))
        assertEquals(97, result.otsu)
        assertEquals(100, result.threshold)
        assertEquals(32, result.background)
        assertEquals(RESOLVED_BRIGHT, result.polarity)
        assertEquals(1, result.colonies.size)
        val c = result.colonies[0]
        assertEquals(20.5, c.x, 1e-6)
        assertEquals(15.5, c.y, 1e-6)
        assertEquals(81.0, c.area, 1e-6)
        assertEquals(5.077706251929807, c.radius, 1e-6)
        assertEquals(208.27928670247397, c.meanIntensity, 1e-3)
        assertEquals(0.525762406902424, c.circularity, 1e-3)
    }

    // ------------------------------------------------------ markers helpers

    @Test
    fun numberMarkers_uses_row_major_order() {
        val numbering = numberMarkers(markers)
        assertEquals(mapOf(1 to 1, 3 to 2, 5 to 3), numbering.labels)
    }

    @Test
    fun computeStats_matches_reference() {
        val stats = computeStats(markers)
        assertEquals(6, stats.total)
        assertEquals(3, stats.included)
        assertEquals(3, stats.excluded)
        assertEquals(2, stats.manual)
        assertEquals(35.625660691708255, stats.meanArea, 1e-9)
        assertEquals(30.190705400997913, stats.medianArea, 1e-9)
        assertEquals(183.0, stats.meanIntensity, 1e-9)
        assertEquals(0.85, stats.meanCircularity, 1e-9)
    }

    @Test
    fun countClasses_matches_reference() {
        val counts = countClasses(markers)
        assertEquals(0, counts.blue)
        assertEquals(0, counts.white)
        assertEquals(3, counts.unclassified)
    }

    @Test
    fun buildHistogram_matches_reference() {
        val areas = markers.filter { it.included }.map { it.area }
        val hist = buildHistogram(areas)
        assertEquals(listOf(2, 0, 0, 0, 0, 1), hist.counts)
        assertEquals(26.420794216690158, hist.min, 1e-9)
        assertEquals(50.26548245743669, hist.max, 1e-9)
        assertEquals(3.9741147067910885, hist.binWidth, 1e-9)
        assertEquals(2, hist.peak)
        assertEquals(3, hist.total)
    }

    @Test
    fun markersCsv_matches_reference_byte_for_byte() {
        val csv = markersToCsv(markers, numberMarkers(markers))
        val expected = """
            id,number,x,y,area_px2,radius_px,mean_intensity,circularity,included,source,class,mean_r,mean_g,mean_b,hue,saturation,class_manual
            1,1,12.00,8.00,30.19,3.10,181.00,0.7500,1,auto,unclassified,0.0,0.0,0.0,-1.0,0.000,0
            2,,40.00,5.00,18.10,2.40,182.00,0.8000,0,auto,unclassified,0.0,0.0,0.0,-1.0,0.000,0
            3,2,20.00,30.00,50.27,4.00,183.00,0.8500,1,auto,unclassified,0.0,0.0,0.0,-1.0,0.000,0
            4,,55.00,25.00,10.18,1.80,184.00,0.9000,0,auto,unclassified,0.0,0.0,0.0,-1.0,0.000,0
            5,3,8.00,44.00,26.42,2.90,185.00,0.9500,1,manual,unclassified,0.0,0.0,0.0,-1.0,0.000,0
            6,,70.00,50.00,34.21,3.30,186.00,1.0000,0,manual,unclassified,0.0,0.0,0.0,-1.0,0.000,0
        """.trimIndent()
        assertEquals(expected, csv)
    }

    @Test
    fun computeCfu_matches_reference() {
        assertEquals(5.0E7, computeCfu(5, 1e6, 0.1)!!, 0.0)
        assertEquals(0.0, computeCfu(0, 1e6, 0.1)!!, 0.0)
        assertNull(computeCfu(5, 0.0, 0.1))
        assertNull(computeCfu(5, 1e6, Double.NaN))
    }

    @Test
    fun poissonCi95_matches_reference() {
        val expected = listOf(
            0.0 to 3.688879454113933,
            0.025317807984289876 to 5.571643390938895,
            0.24220927854396523 to 7.224687667723957,
            1.6234863901184453 to 11.66833207932267,
            12.21651958541819 to 30.888377902645153,
            37.11096373760556 to 65.91876666412307,
            81.36399125131379 to 121.62679379191596,
        )
        listOf(0, 1, 2, 5, 20, 50, 100).forEachIndexed { i, count ->
            val ci = poissonCi95(count)
            assertEquals(expected[i].first, ci.low, 1e-9)
            assertEquals(expected[i].second, ci.high, 1e-9)
        }
    }

    @Test
    fun cfuCi95_and_formatInterval_match_reference() {
        val ci = cfuCi95(5, 1e6, 0.1)!!
        assertEquals(16234863.901184453, ci.low, 1e-6)
        assertEquals(116683320.7932267, ci.high, 1e-6)
        assertEquals("1.62 × 10^0 – 1.17 × 10^1", formatInterval(poissonCi95(5)))
        assertEquals("—", formatInterval(null))
        assertEquals("1.62 × 10^7 – 1.17 × 10^8", formatInterval(cfuCi95(5, 1e6, 0.1)))
    }

    @Test
    fun statisticsCsv_matches_reference() {
        val countCi = poissonCi95(5)
        val cfuInterval = cfuCi95(5, 1e6, 0.1)!!
        val row = StatisticsRow(
            label = "plate A", count = 5, countLow = countCi.low, countHigh = countCi.high,
            blue = 2, white = 1, unclassified = 2,
            cfu = computeCfu(5, 1e6, 0.1), cfuLow = cfuInterval.low, cfuHigh = cfuInterval.high,
        )
        val expected = """
            label,count,count_ci95_low,count_ci95_high,blue,white,unclassified,cfu_per_ml,cfu_ci95_low,cfu_ci95_high
            plate A,5,1.62,11.67,2,1,2,50000000.0000,16234863.9012,116683320.7932
        """.trimIndent()
        assertEquals(expected, statisticsToCsv(listOf(row)))
    }

    @Test
    fun formatFunctions_match_reference() {
        assertEquals("0", formatScientific(0.0))
        assertEquals("1.23 × 10^3", formatScientific(1234.5))
        assertEquals("6.02 × 10^23", formatScientific(6.02e23))
        assertEquals("4.56 × 10^-4", formatScientific(4.56e-4))
        assertEquals("—", formatScientific(null))
        assertEquals("-3.10 × 10^-3", formatScientific(-0.0031))
        assertEquals("0", formatArea(0.0))
        assertEquals("12.3", formatArea(12.34))
        assertEquals("123", formatArea(123.456))
        assertEquals("2346", formatArea(2345.6))
        assertEquals("56,789", formatArea(56789.4))
    }

    @Test
    fun median_matches_reference() {
        assertEquals(0.0, median(emptyList()), 0.0)
        assertEquals(1.0, median(listOf(1.0)), 0.0)
        assertEquals(1.5, median(listOf(1.0, 2.0)), 0.0)
        assertEquals(2.5, median(listOf(1.0, 2.0, 3.0, 4.0)), 0.0)
        assertEquals(3.0, median(listOf(3.0, 1.0, 4.0, 1.0, 5.0)), 0.0)
    }

    @Test
    fun nextMarkerId_sequence_matches_reference() {
        assertEquals(1, nextMarkerId(emptyList()))
        assertEquals(2, nextMarkerId(emptyList()))
        assertEquals(3, nextMarkerId(markers.subList(0, 1)))
        assertEquals(4, nextMarkerId(markers.subList(0, 2)))
        assertEquals(5, nextMarkerId(emptyList()))
    }

    // ---------------------------------------------------------------- color

    private fun colorSource(): PixelSource {
        val cw = 8
        val ch = 8
        val rgba = IntArray(cw * ch * 4)
        for (i in 0 until cw * ch) {
            rgba[i * 4] = 34; rgba[i * 4 + 1] = 33; rgba[i * 4 + 2] = 35; rgba[i * 4 + 3] = 255
        }
        for (y in 1..3) for (x in 1..3) {
            val i = (y * cw + x) * 4
            rgba[i] = 40; rgba[i + 1] = 60; rgba[i + 2] = 225
        }
        for (y in 4..6) for (x in 4..6) {
            val i = (y * cw + x) * 4
            rgba[i] = 225; rgba[i + 1] = 222; rgba[i + 2] = 218
        }
        return PixelSource(rgba, cw, ch)
    }

    @Test
    fun color_sampling_and_classification_match_reference() {
        val source = colorSource()
        assertTrue(canvasHasColor(source))
        // 原版对灰度化像素（v*0.5+20 后仍残留 1-2 级色度抖动）同样判为彩色，
        // Kotlin 1:1 复现该判定（色度阈值内的像素视为彩色）。
        val grayRgba = source.data.mapIndexed { i, v -> if (i % 4 == 3) 255 else (v * 0.5 + 20).toInt() }.toIntArray()
        assertTrue(canvasHasColor(PixelSource(grayRgba, source.width, source.height)))
        val blue = mk(9, 2.0, 2.0, 1.2)
        val white = mk(10, 5.0, 5.0, 1.2)
        val bg = mk(11, 0.0, 7.0, 1.2)
        val samples = listOf(blue, white, bg).map { sampleMarkerColor(source, it) }
        assertEquals(40.0, samples[0].r, 1e-9)
        assertEquals(60.0, samples[0].g, 1e-9)
        assertEquals(225.0, samples[0].b, 1e-9)
        assertEquals(233.51351351351352, samples[0].hue, 1e-9)
        assertEquals(0.8222222222222222, samples[0].saturation, 1e-9)
        assertEquals(0.8823529411764706, samples[0].value, 1e-9)
        assertEquals(225.0, samples[1].r, 1e-9)
        assertEquals(34.285714285714285, samples[1].hue, 1e-9)
        assertEquals(0.03111111111111111, samples[1].saturation, 1e-9)
        assertEquals(34.0, samples[2].r, 1e-9)
        assertEquals(270.0, samples[2].hue, 1e-9)
        assertEquals(0.05714285714285714, samples[2].saturation, 1e-9)
        assertEquals(0.13725490196078433, samples[2].value, 1e-9)
        assertEquals(CLASS_BLUE, classifyColor(samples[0], true))
        assertEquals(CLASS_WHITE, classifyColor(samples[1], true))
        assertEquals(CLASS_UNCLASSIFIED, classifyColor(samples[2], true))
        assertEquals(CLASS_UNCLASSIFIED, classifyColor(samples[0], false))
    }

    @Test
    fun classifyMarkers_updates_classes_like_reference() {
        val source = colorSource()
        val input = listOf(
            mk(9, 2.0, 2.0, 1.2), mk(10, 5.0, 5.0, 1.2), mk(11, 0.0, 7.0, 1.2),
        )
        val (result, classified) = classifyMarkers(source, input)
        assertTrue(result.imageHasColor)
        assertEquals(
            listOf(CLASS_BLUE, CLASS_WHITE, CLASS_UNCLASSIFIED),
            classified.map { it.colonyClass },
        )
        // 手动覆盖的类别不被自动分类改写（原版 classManual 分支）
        val manual = listOf(
            mk(9, 2.0, 2.0, 1.2).copy(colonyClass = CLASS_WHITE, classManual = true),
        )
        val (manualResult, manualOut) = classifyMarkers(source, manual)
        assertTrue(manualResult.imageHasColor)
        assertEquals(CLASS_WHITE, manualOut[0].colonyClass)
        // 清除分类重置类别与手动标记
        val cleared = clearClassification(classified)
        assertEquals(List(3) { CLASS_UNCLASSIFIED }, cleared.map { it.colonyClass })
        assertTrue(cleared.none { it.classManual })
    }

    // ---------------------------------------------------------- project I/O

    @Test
    fun projectBaseName_matches_reference() {
        assertEquals("plate", projectBaseName("plate.jpg"))
        assertEquals("a_b_c_d_e_f_g_", projectBaseName("a\\b:c*d?e<f>g|.png"))
        assertEquals("colony-plate", projectBaseName(""))
        assertEquals("colony-plate.tar", projectBaseName("colony-plate.tar.gz"))
        assertEquals("x.png", projectBaseName("x.png.png"))
        assertEquals(".", projectBaseName("..png"))
    }

    @Test
    fun isColonyProject_validates_like_reference() {
        val json = project().toJson()
        assertTrue(isColonyProject(json))
        assertFalse(isColonyProject(JSONObject(json.toString()).put("version", 2)))
        assertFalse(isColonyProject(JSONObject(json.toString()).put("markers", "x")))
        assertFalse(isColonyProject(null))
        assertFalse(isColonyProject("not an object"))
    }

    @Test
    fun normalizeProject_fills_marker_fallbacks_like_reference() {
        // 原版：radius 非有限 → sqrt(area/π)；source 非法 → auto；
        //       颜色字段非有限 → 0/-1/0；class 非法 → unclassified。
        val broken = ColonyMarker(
            id = 1, x = 2.0, y = 3.0, radius = Double.NaN, area = 12.566,
            meanIntensity = 100.0, circularity = 0.8,
            included = false, source = "weird",
            meanR = Double.NaN, meanG = Double.NaN, meanB = Double.NaN,
            hue = Double.NaN, saturation = Double.NaN,
            colonyClass = "junk", classManual = false,
        )
        val normalized = normalizeProject(
            project().copy(
                params = defaultParams().copy(colorMode = "junk"),
                markers = listOf(broken),
            ),
        )
        assertEquals(1, normalized.version)
        assertEquals(1, normalized.markers.size)
        val marker = normalized.markers[0]
        assertEquals(1.9999705072289227, marker.radius, 1e-9)
        assertEquals(SOURCE_AUTO, marker.source)
        assertEquals(0.0, marker.meanR, 0.0)
        assertEquals(0.0, marker.meanG, 0.0)
        assertEquals(0.0, marker.meanB, 0.0)
        assertEquals(-1.0, marker.hue, 0.0)
        assertEquals(0.0, marker.saturation, 0.0)
        assertEquals(CLASS_UNCLASSIFIED, marker.colonyClass)
        assertFalse(marker.classManual)
        assertEquals(COLOR_MODE_OFF, normalized.params.colorMode)
        // adjust/crop/params 缺省由默认值补齐（原版 {...base, ...project} 语义）
        assertEquals(defaultAdjust(), normalized.adjust)
        assertEquals(ColonyCrop(0.1, 0.0, 0.0, 0.2), normalized.crop)
        assertEquals(defaultParams(), normalized.params)
    }

    @Test
    fun projectJson_roundTrips_through_parseProjectJson() {
        val json = projectJson(project())
        // projectJson 刷新 savedAt；其余字段须逐项一致
        val parsed = parseProjectJson(json)!!
        assertEquals("2026-01-01T00:00:00.000Z".length > 0, parsed.savedAt.isNotEmpty())
        assertEquals("data:image/png;base64,QUJD", parsed.imageDataUrl)
        assertEquals("plate 01.png", parsed.imageName)
        assertEquals(800, parsed.imageWidth)
        assertEquals(600, parsed.imageHeight)
        assertEquals(800, parsed.workWidth)
        assertEquals(600, parsed.workHeight)
        assertEquals(90, parsed.rotate)
        assertEquals(ColonyCrop(0.1, 0.0, 0.0, 0.2), parsed.crop)
        assertEquals(defaultAdjust(), parsed.adjust)
        assertEquals(defaultParams(), parsed.params)
        assertEquals(6, parsed.markers.size)
        assertEquals(300, parsed.dpi)
        assertEquals(1000000.0, parsed.dilutionFactor, 0.0)
        assertEquals(0.1, parsed.platedVolume, 0.0)
        parsed.markers.forEachIndexed { i, marker ->
            val expected = markers[i]
            assertEquals(expected.id, marker.id)
            assertEquals(expected.x, marker.x, 1e-9)
            assertEquals(expected.y, marker.y, 1e-9)
            assertEquals(expected.area, marker.area, 1e-9)
            assertEquals(expected.radius, marker.radius, 1e-9)
            assertEquals(expected.meanIntensity, marker.meanIntensity, 1e-9)
            assertEquals(expected.included, marker.included)
            assertEquals(expected.source, marker.source)
        }
        // 非法文本 → null（原版抛 Invalid colony project）
        assertNull(parseProjectJson("not json"))
        assertNull(parseProjectJson("{\"nope\":1}"))
    }

    @Test
    fun projectDoc_helpers_match_reference() {
        assertEquals("colony", COLONY_DOC_KIND)
        val payload = project().toJson()
        val normalized = normalizeColonyPayload(payload)
        assertEquals("plate 01.png", normalized!!.imageName)
        assertNull(normalizeColonyPayload(JSONObject("{\"nope\":1}")))
        assertEquals("plate 01", colonyProjectName(project(), "fallback"))
        // 原版：".png" 去扩展名后为空串 → 回退 fallback
        assertEquals("fallback", colonyProjectName(project().copy(imageName = ".png"), "fallback"))
        val counts = colonyProjectCounts(project())
        assertEquals(3, counts.included)
        assertEquals(3, counts.excluded)
        assertEquals(0, counts.blue)
        assertEquals(0, counts.white)
        val legacy = colonyProjectCounts(project().copy(markers = listOf(
            mk(1, 1.0, 1.0, 1.0).copy(included = true, colonyClass = CLASS_BLUE),
        )))
        assertEquals(1, legacy.included)
        assertEquals(0, legacy.excluded)
        assertEquals(1, legacy.blue)
        assertEquals(0, legacy.white)
    }

    @Test
    fun toJson_isColonyPayload_equivalent() {
        // toJson 输出与生成器 projectJson（除 savedAt 刷新外）语义一致
        val json = project().toJson()
        assertEquals(1, json.getInt("version"))
        assertEquals("plate 01.png", json.getString("imageName"))
        assertEquals(90, json.getInt("rotate"))
        assertTrue(json.getJSONArray("markers").length() == 6)
        val marker = json.getJSONArray("markers").getJSONObject(0)
        assertEquals(1, marker.getInt("id"))
        assertEquals(12.0, marker.getDouble("x"), 0.0)
        assertEquals(8.0, marker.getDouble("y"), 0.0)
    }
}
