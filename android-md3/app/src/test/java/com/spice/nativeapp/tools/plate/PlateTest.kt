package com.spice.nativeapp.tools.plate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONObject

/**
 * T4 plate designer algorithm-layer parity tests.
 *
 * Reference values were produced by running the ORIGINAL TypeScript sources
 * (types.ts / assign.ts / random.ts inlined verbatim) under Node.js; the
 * Kotlin port must reproduce them exactly (deterministic mulberry32 seed
 * pipeline, Fisher-Yates shuffles, first-fit candidate search, edge-first aux
 * placement, reserved edge wells, validation issue catalogue).
 */
class PlateTest {

    // ------------------------------------------------- deterministic helpers

    @Test
    fun mulberry32_matches_reference_sequence() {
        val r = mulberry32(20261102)
        val expected = listOf(
            0.07848557480610907, 0.4848272386007011, 0.20832762983627617,
            0.39460715488530695, 0.2319970102980733, 0.6316126976162195,
            0.27175123430788517, 0.5048325820825994,
        )
        expected.forEach { assertEquals(it, r(), 0.0) }
        val r2 = mulberry32(1)
        val expected2 = listOf(
            0.6270739405881613, 0.002735721180215478, 0.5274470399599522,
            0.9810509674716741, 0.9683778982143849,
        )
        expected2.forEach { assertEquals(it, r2(), 0.0) }
    }

    @Test
    fun shuffle_fisherYates_matches_reference() {
        val items = (0..9).toMutableList()
        shuffleInPlace(items, mulberry32(42))
        assertEquals(listOf(0, 7, 3, 5, 2, 1, 8, 9, 4, 6), items)
    }

    @Test
    fun hashText_and_groupColor_match_reference() {
        // Reference hashes produced by the original hashText (FNV-1a, `>>> 0`).
        // All five reference values fit in a positive Int32, so the Kotlin Int
        // result compares directly.
        assertEquals(1386144670, hashText("control"))
        assertEquals(1852775873, hashText("blank"))
        assertEquals(3708723434L, hashText("样品 A").toLong() and 0xffffffffL)
        assertEquals(596269666L, hashText("Group β").toLong() and 0xffffffffL)
        assertEquals(627919935L, hashText("未分组").toLong() and 0xffffffffL)
        // groupColor picks (hash of lowercased key % 14).
        assertEquals("#a3d977", groupColor("control"))
        assertEquals("#ffcf9f", groupColor("blank"))
        assertEquals("#ff9f1c", groupColor("样品 A"))
        assertEquals("#7ce0ff", groupColor("Group β"))
        assertEquals("#f2e06b", groupColor("未分组"))
        // Empty group keeps the muted grey.
        assertEquals("#8b96b5", groupColor("  "))
    }

    @Test
    fun textColor_forDarkFill_isLight_andViceVersa() {
        assertEquals("#eef3ff", textColorFor("#0e1220"))
        assertEquals("#0b0e14", textColorFor("#ffd23f"))
        // 3-digit shorthand normalizes first.
        assertEquals("#0b0e14", textColorFor("#fff"))
    }

    @Test
    fun baseCellFor_matches_original() {
        assertEquals(64, baseCellFor(6))
        assertEquals(54, baseCellFor(12))
        assertEquals(44, baseCellFor(24))
        assertEquals(36, baseCellFor(48))
        assertEquals(27, baseCellFor(96))
        assertEquals(15, baseCellFor(384))
    }

    // ------------------------------------------------------------- geometry

    @Test
    fun geometry_table_matches_original() {
        assertEquals(PlateGeometry(6, 2, 3), formatGeometry(6))
        assertEquals(PlateGeometry(12, 3, 4), formatGeometry(12))
        assertEquals(PlateGeometry(24, 4, 6), formatGeometry(24))
        assertEquals(PlateGeometry(48, 6, 8), formatGeometry(48))
        assertEquals(PlateGeometry(96, 8, 12), formatGeometry(96))
        assertEquals(PlateGeometry(384, 16, 24), formatGeometry(384))
        assertEquals(6, formatGeometry(6).total)
        assertEquals(listOf(6, 12, 24, 48, 96, 384), PLATE_FORMATS)
    }

    @Test
    fun rowLabels_match_original() {
        assertEquals("A", rowLabel(0))
        assertEquals("H", rowLabel(7))
        assertEquals("Z", rowLabel(25))
        assertEquals("AA", rowLabel(26))
        assertEquals("AB", rowLabel(27))
        assertEquals("P", rowLabel(15))
        assertEquals("A1", wellId(0, 0))
        assertEquals("AB12", wellId(27, 11))
        assertEquals("P24", wellId(15, 23))
    }

    @Test
    fun edgeWells_match_original() {
        val g = formatGeometry(96)
        val cells = buildWellCells(g)
        assertEquals(96, cells.size)
        assertTrue(cells[0].edge)
        assertTrue(cells[11].edge)
        assertEquals(false, cells[13].edge)
        assertTrue(cells[95].edge)
        assertEquals(36, cells.count { it.edge })
    }

    // ---------------------------------------------------------- assignPlate

    private fun smp(id: String, name: String, group: String = "", replicates: Int? = null) =
        SampleRow(id, name, group, "", replicates)

    /** Compares type|sampleName|replicate per well against the JS reference. */
    private fun checkAgainst(map: Map<String, List<String>>, result: AssignResult) {
        val expected = map.mapValues { "${it.value[0]}|${it.value[1]}|${it.value[2]}" }.toSortedMap()
        val actual = result.assignments.entries.associate {
            it.key to "${it.value.type}|${it.value.sampleName}|${it.value.replicate}"
        }.toSortedMap()
        assertEquals(expected, actual)
    }

    private fun rulesOf(mutate: (PlateRules) -> Unit = {}): PlateRules {
        val rules = defaultRules()
        mutate(rules)
        return rules
    }

    @Test
    fun assignPlate_96_defaultRules_matches_reference() {
        val result = assignPlate(
            96,
            listOf(smp("a", "A", "G1"), smp("b", "B", "G1"), smp("c", "C", "G2"), smp("d", "D", "G2")),
            rulesOf(),
        )
        checkAgainst(
            mapOf(
                "B2" to listOf("sample", "C", "1"), "B3" to listOf("sample", "C", "2"), "B4" to listOf("sample", "C", "3"),
                "B5" to listOf("sample", "D", "1"), "B6" to listOf("sample", "D", "2"), "B7" to listOf("sample", "D", "3"),
                "B8" to listOf("sample", "B", "1"), "B9" to listOf("sample", "B", "2"), "B10" to listOf("sample", "B", "3"),
                "C2" to listOf("sample", "A", "1"), "C3" to listOf("sample", "A", "2"), "C4" to listOf("sample", "A", "3"),
                "E12" to listOf("control", "CTRL-1", "1"), "A11" to listOf("control", "CTRL-2", "2"),
                "A5" to listOf("blank", "BLANK-1", "1"), "G12" to listOf("blank", "BLANK-2", "2"),
            ),
            result,
        )
        assertEquals(16, result.assigned)
        assertEquals(12, result.sampleWells)
        assertEquals(2, result.controlWells)
        assertEquals(2, result.blankWells)
        assertEquals(0, result.reservedCount)
    }

    @Test
    fun assignPlate_24_reserveEdge_matches_reference() {
        val result = assignPlate(
            24,
            listOf(smp("x", "X", "GX"), smp("y", "Y"), smp("z", "Z", "GZ", 1)),
            rulesOf {
                it.replicates = 2; it.reserveEdge = true; it.controls = 2; it.blanks = 3; it.seed = 7
            },
        )
        checkAgainst(
            mapOf(
                "B2" to listOf("sample", "Y", "1"), "B3" to listOf("sample", "Y", "2"), "B4" to listOf("sample", "Z", "1"),
                "C2" to listOf("sample", "X", "1"), "C3" to listOf("sample", "X", "2"),
                "C6" to listOf("control", "CTRL-1", "1"), "A4" to listOf("control", "CTRL-2", "2"),
                "A6" to listOf("blank", "BLANK-1", "1"), "B1" to listOf("blank", "BLANK-2", "2"), "A1" to listOf("blank", "BLANK-3", "3"),
                "A2" to listOf("reserved", "", "0"), "A3" to listOf("reserved", "", "0"), "A5" to listOf("reserved", "", "0"),
                "B6" to listOf("reserved", "", "0"), "C1" to listOf("reserved", "", "0"), "D1" to listOf("reserved", "", "0"),
                "D2" to listOf("reserved", "", "0"), "D3" to listOf("reserved", "", "0"), "D4" to listOf("reserved", "", "0"),
                "D5" to listOf("reserved", "", "0"), "D6" to listOf("reserved", "", "0"),
            ),
            result,
        )
        assertEquals(11, result.reservedCount)
    }

    @Test
    fun assignPlate_6_overCapacity_reports_unplaced() {
        val result = assignPlate(
            6,
            listOf(smp("p", "P"), smp("q", "Q")),
            rulesOf { it.seed = 99; it.replicates = 4; it.controls = 0; it.blanks = 0 },
        )
        checkAgainst(
            mapOf(
                "B1" to listOf("sample", "Q", "1"), "A2" to listOf("sample", "Q", "2"), "A1" to listOf("sample", "Q", "3"),
                "B3" to listOf("sample", "Q", "4"), "B2" to listOf("sample", "P", "1"), "A3" to listOf("sample", "P", "2"),
            ),
            result,
        )
        // P requested 4, placed 2 → one unplaced entry with missing 2.
        assertEquals(1, result.unplaced.size)
        assertEquals("p", result.unplaced[0].sampleId)
        assertEquals(2, result.unplaced[0].missing)
        // Capacity + replicate-missing errors present, sorted before warns.
        assertTrue(result.issues.any { it.key == "plateIssueCapacity" && it.severity == "error" })
        assertTrue(result.issues.any { it.key == "plateIssueReplicatesMissing" && it.severity == "error" })
        val severities = result.issues.map { it.severity }
        assertEquals(
            severities.sortedBy { listOf("error", "warn", "info").indexOf(it) },
            severities,
        )
    }

    @Test
    fun assignPlate_48_scatter_matches_reference() {
        val result = assignPlate(
            48,
            listOf(smp("m", "M"), smp("n", "N")),
            rulesOf { it.keepTogether = false; it.seed = 1234 },
        )
        checkAgainst(
            mapOf(
                "B3" to listOf("sample", "N", "1"), "C3" to listOf("sample", "N", "2"), "D7" to listOf("sample", "N", "3"),
                "B4" to listOf("sample", "M", "1"), "D4" to listOf("sample", "M", "2"), "E7" to listOf("sample", "M", "3"),
                "F3" to listOf("control", "CTRL-1", "1"), "C8" to listOf("control", "CTRL-2", "2"),
                "B8" to listOf("blank", "BLANK-1", "1"), "F6" to listOf("blank", "BLANK-2", "2"),
            ),
            result,
        )
    }

    @Test
    fun assignPlate_96_blockLayout_matches_reference() {
        val result = assignPlate(
            96,
            listOf(smp("s1", "S1"), smp("s2", "S2"), smp("s3", "S3")),
            rulesOf {
                it.groupLayout = "block"; it.groupColumns = 2; it.groupRows = 2; it.seed = 555
                it.controls = 1; it.blanks = 1
            },
        )
        checkAgainst(
            mapOf(
                "B2" to listOf("sample", "S3", "1"), "B3" to listOf("sample", "S3", "2"), "C2" to listOf("sample", "S3", "3"),
                "B4" to listOf("sample", "S1", "1"), "B5" to listOf("sample", "S1", "2"), "C4" to listOf("sample", "S1", "3"),
                "B6" to listOf("sample", "S2", "1"), "B7" to listOf("sample", "S2", "2"), "C6" to listOf("sample", "S2", "3"),
                "H5" to listOf("control", "CTRL-1", "1"), "B12" to listOf("blank", "BLANK-1", "1"),
            ),
            result,
        )
    }

    @Test
    fun assignPlate_48_columnLayout_matches_reference() {
        val result = assignPlate(
            48,
            listOf(smp("c1", "C1"), smp("c2", "C2")),
            rulesOf {
                it.groupLayout = "column"; it.seed = 777; it.replicates = 6; it.controls = 0; it.blanks = 0
            },
        )
        checkAgainst(
            mapOf(
                "E5" to listOf("sample", "C1", "1"), "B5" to listOf("sample", "C1", "2"), "D5" to listOf("sample", "C1", "3"),
                "E3" to listOf("sample", "C1", "4"), "B7" to listOf("sample", "C1", "5"), "D7" to listOf("sample", "C1", "6"),
                "C3" to listOf("sample", "C2", "1"), "E6" to listOf("sample", "C2", "2"), "C6" to listOf("sample", "C2", "3"),
                "B4" to listOf("sample", "C2", "4"), "B3" to listOf("sample", "C2", "5"), "E7" to listOf("sample", "C2", "6"),
            ),
            result,
        )
    }

    // ------------------------------------------------------------ validate

    @Test
    fun validate_reports_duplicate_names_and_capacity() {
        val issues = validateLayout(
            6,
            listOf(smp("a", "same"), smp("b", "SAME"), smp("c", "")),
            rulesOf(),
            emptyMap(),
        ).issues
        assertTrue(issues.any { it.key == "plateSampleNameRequired" && it.severity == "warn" })
        assertTrue(issues.any { it.key == "plateDuplicateName" && it.params["name"] == "same" })
        // 3 samples × 3 reps (8 total rules.replicates=3 → need 9+2+2) > 6 wells.
        assertTrue(issues.any { it.key == "plateIssueCapacity" && it.severity == "error" })
        assertTrue(issues.any { it.key == "plateIssueControlsMissing" })
        assertTrue(issues.any { it.key == "plateIssueBlanksMissing" })
    }

    @Test
    fun validate_keepTogether_reports_groupSplit_and_edgeUse() {
        val geometry = formatGeometry(6)
        val assignments = mapOf(
            "A1" to manualSampleAssignment(geometry, smp("a", "A"), 0, 0, 1),
            "C3" to manualSampleAssignment(geometry, smp("a", "A"), 2, 2, 2),
        )
        val result = validateLayout(6, listOf(smp("a", "A")), rulesOf(), assignments)
        assertTrue(result.issues.any { it.key == "plateIssueGroupSplit" })
        assertTrue(result.issues.any { it.key == "plateIssueEdgeUsed" })
        assertTrue(result.issues.any { it.key == "plateIssueControlsMissing" })
        // Requested 3 (default rules), placed 2 → one unplaced entry.
        assertEquals(1, result.unplaced.size)
        assertEquals(1, result.unplaced[0].missing)
        assertTrue(result.issues.any { it.key == "plateIssueReplicatesMissing" && it.severity == "error" })
    }

    @Test
    fun validate_duplicateWell_reports_error() {
        val geometry = formatGeometry(24)
        val a1 = manualAuxAssignment(geometry, "blank", 0, 0, 1)
        val issues = validateLayout(
            24, listOf(smp("a", "A")), rulesOf(),
            mapOf("A1" to a1, "A2" to a1),
        ).issues
        assertTrue(issues.any { it.key == "plateIssueDuplicate" && it.severity == "error" })
    }

    // ------------------------------------------------- normalize / JSON

    @Test
    fun normalizeProject_dedupes_drops_and_counts() {
        val raw = JSONObject(
            """
            {
              "format": 96,
              "samples": [{"id":"s1","name":"A","group":"g","replicates":2},
                          {"id":"s1","name":"A2"},
                          {"name":"NoId"}],
              "rules": {"seed": 5, "replicates": 3},
              "assignments": [
                {"well":"a1","type":"sample","sampleId":"s1","replicate":1},
                {"well":"ZZ99","type":"sample"},
                {"well":"A1","type":"sample","sampleId":"s1","replicate":9},
                {"well":"B2","type":"weird"},
                {"well":"B3","type":"blank","color":"#abc"}
              ],
              "colorBy": "sample",
              "showLabels": false
            }
            """.trimIndent()
        )
        val result = normalizeProject(raw)
        val project = result.project!!
        assertEquals(96, project.format)
        // duplicate id s1 gets _1 suffix; third sample gets generated id.
        assertEquals(listOf("s1", "s1_1", "smp_import_3"), project.samples.map { it.id })
        // ZZ99 invalid well → dropped; "weird" type → dropped; second A1 → duplicate.
        assertEquals(2, result.droppedAssignments)
        assertEquals(1, result.duplicateWells)
        // a1 normalizes to A1 and survives; B3 blank survives with #aabbcc.
        assertEquals(2, project.assignments.size)
        assertEquals("A1", project.assignments[0].well)
        assertEquals("B3", project.assignments[1].well)
        assertEquals("#aabbcc", project.assignments[1].color)
        // assignments sorted by row/col.
        assertTrue(project.assignments[0].row <= project.assignments[1].row)
        // colorBy accepts only "sample"/"group".
        assertEquals("sample", project.colorBy)
        assertEquals(false, project.showLabels)
        assertEquals(5, project.rules.seed)
    }

    @Test
    fun normalizeProject_accepts_assignment_object_map() {
        val raw = JSONObject(
            """
            {
              "format": 6,
              "assignments": {"c1": {"well": "A1", "type": "control"}, "r1": {"well": "B2", "type": "reserved"}}
            }
            """.trimIndent()
        )
        val project = normalizeProject(raw).project!!
        assertEquals(2, project.assignments.size)
        // 384 defaults showLabels off; 6-well defaults it on.
        assertTrue(project.showLabels)
    }

    @Test
    fun normalizeProject_showLabels_defaults_off_for_384() {
        val raw = JSONObject("{\"format\": 384}")
        assertEquals(false, normalizeProject(raw).project!!.showLabels)
    }

    @Test
    fun project_json_roundTrip() {
        val rules = rulesOf { it.seed = 42 }
        val samples = mutableListOf(
            SampleRow("s1", "样本 A", "G1", "#ff5d5d", 2),
            SampleRow("s2", "B", "", "", null),
        )
        val assignments = assignPlate(96, samples, rules).assignments.values
            .sortedWith(compareBy({ it.row }, { it.col })).toMutableList()
        val project = PlateProjectFile(1, "spice-plate", "My plate", 96, samples, rules, assignments, "group", true, nowIso())
        val parsed = normalizeProject(JSONObject(project.toJson().toString()))
        assertEquals("My plate", parsed.project!!.title)
        assertEquals(42, parsed.project!!.rules.seed)
        assertEquals(2, parsed.project!!.samples.size)
        assertEquals(assignments.size, parsed.project!!.assignments.size)
        assertEquals("blank", parsed.project!!.assignments[0].type)
        assertEquals(0, parsed.project!!.assignments[0].row)
        assertTrue(parsed.project!!.assignments.any { it.type == "sample" && it.sampleId == "s1" })
        assertEquals(true, parsed.project!!.showLabels)
    }

    @Test
    fun signatureOf_ignoresNamesAndColors() {
        val a = SampleRow("s1", "A", "G1", "#ff0000", 2)
        val b = SampleRow("s1", "Renamed", "Other", "#00ff00", 2)
        assertEquals(
            signatureOf(96, listOf(a), rulesOf()),
            signatureOf(96, listOf(b), rulesOf()),
        )
        // Different id or replicates changes the signature.
        val c = SampleRow("s1", "A", "G1", "", 3)
        assertTrue(signatureOf(96, listOf(a), rulesOf()) != signatureOf(96, listOf(c), rulesOf()))
        val d = SampleRow("s2", "A", "G1", "", 2)
        assertTrue(signatureOf(96, listOf(a), rulesOf()) != signatureOf(96, listOf(d), rulesOf()))
    }

    // ------------------------------------------------------------ CSV parse

    @Test
    fun parseSampleTable_header_and_delimiters() {
        val tsv = "sample\tgroup\tcolor\treplicates\nA\tG1\t#ff0000\t2\nB\tG2\t\t"
        val parsed = parseSampleTable(tsv)
        assertTrue(parsed.hadHeader)
        assertEquals("\t", parsed.delimiter)
        assertEquals(2, parsed.rows.size)
        assertEquals("A", parsed.rows[0].name)
        assertEquals("G1", parsed.rows[0].group)
        assertEquals("#ff0000", parsed.rows[0].color)
        assertEquals(2, parsed.rows[0].replicates)
        assertNull(parsed.rows[1].replicates)

        val csvNoHeader = "Alice,Cond1\n,skipped\nBob,Cond2"
        val parsed2 = parseSampleTable(csvNoHeader)
        assertTrue(!parsed2.hadHeader)
        assertEquals(2, parsed2.rows.size)
        assertEquals(1, parsed2.skipped)
        assertEquals("Alice", parsed2.rows[0].name)
        assertEquals("Cond1", parsed2.rows[0].group)

        val semicolon = "name;group\nX;Y"
        assertEquals(";", parseSampleTable(semicolon).delimiter)
        // Comments (#) and BOM are ignored.
        val commented = "# a comment\nA,B"
        assertEquals(1, parseSampleTable(commented).rows.size)
    }

    @Test
    fun parseSampleTable_replicates_clamped() {
        val table = "sample,replicates\nA,99\nB,0\nC,2.7\nD,abc"
        val rows = parseSampleTable(table).rows
        assertEquals(12, rows[0].replicates)
        assertEquals(1, rows[1].replicates)
        assertEquals(3, rows[2].replicates)
        assertNull(rows[3].replicates)
    }

    @Test
    fun plateRows_csv_tsv_output() {
        val geometry = formatGeometry(6)
        val assignments = mapOf(
            "A1" to manualAuxAssignment(geometry, "control", 0, 0, 1),
            "B3" to manualSampleAssignment(geometry, smp("s1", "S", "G"), 1, 2, 1),
        )
        val rows = buildPlateRows(6, assignments)
        assertEquals(6, rows.size)
        assertEquals("empty", rows[1].type)
        assertEquals("A1", rows[0].well)
        // sample falls back to sampleId when name empty
        assertEquals("CTRL-1", rows[0].sample)
        val csv = plateRowsToCsv(rows)
        assertTrue(csv.startsWith("well,sample,group,type,replicate"))
        assertTrue(csv.contains("A1,CTRL-1,Control,control,1"))
        assertTrue(csv.contains("B3,S,G,sample,1"))
        assertTrue(csv.contains("A2,,,empty,"))
        val tsv = plateRowsToTsv(rows)
        assertTrue(tsv.startsWith("well\tsample\tgroup\ttype\treplicate"))
        assertTrue(tsv.contains("A2\t\t\tempty\t"))
    }

    @Test
    fun plateFileBaseName_matches_original() {
        assertEquals("my-plate", plateFileBaseName("My Plate!"))
        assertEquals("plate-layout", plateFileBaseName("中文标题"))
        assertEquals("a-b-c", plateFileBaseName("---a b c---"))
        assertEquals("x".repeat(60), plateFileBaseName("x".repeat(80)))
    }

    // ------------------------------------------------------------ sample sheet

    @Test
    fun sampleSheet_roundTrip_and_csv() {
        val sheet = SampleSheet(
            name = "Demo",
            samples = listOf(
                SampleSheetEntry("id1", "A", "G1", "#ff0000", 3, null),
                SampleSheetEntry(null, "B", "", null, null, "note"),
            ),
            updatedAt = "2026-01-01T00:00:00.000Z",
        )
        val json = sampleSheetToJson(sheet)
        val parsed = parseSampleSheet(json)!!
        assertEquals("Demo", parsed.name)
        assertEquals(2, parsed.samples.size)
        assertEquals("A", parsed.samples[0].name)
        assertEquals(3, parsed.samples[0].replicates)
        assertEquals("id1", parsed.samples[0].id)
        assertEquals("note", parsed.samples[1].notes)
        val csv = sampleSheetToCsv(sheet)
        assertTrue(csv.startsWith("sample,group,color,replicates,notes"))
        assertTrue(csv.contains("A,G1,#ff0000,3,"))
        assertTrue(csv.contains("B,,,,note"))
        // normalizeSampleSheet tolerates raw scalars and rejects garbage.
        assertNull(parseSampleSheet("{\"samples\": 3}"))
        assertEquals("S", normalizeSampleSheet(JSONObject("{\"samples\":[{\"sample\":\"S\",\"n\":2}]}"))!!.samples[0].name)
        assertEquals(2, normalizeSampleSheet(JSONObject("{\"samples\":[{\"sample\":\"S\",\"n\":2}]}"))!!.samples[0].replicates)
    }

    // --------------------------------------------------------------- SVG

    @Test
    fun plateSvg_layout_numbers() {
        val svg = buildPlateSvg(
            PlateSvgOptions(
                title = "T",
                rows = 8,
                cols = 12,
                cells = emptyList(),
                legend = listOf(PlateSvgLegendItem("对照", "#b48cff", 2)),
                legendTitle = "图例",
                note = "note",
            )
        )
        // 96-well: cellSize 38 → width = max(560, 44+456+28) = 560
        // height = 84 + 304 + 18 + (26 + (0+1)*19) + 10 = 461
        assertEquals(560, svg.width)
        assertEquals(461, svg.height)
        assertTrue(svg.svg.startsWith("<svg "))
        assertTrue(svg.svg.contains("<rect x=\"44\" y=\"84\" width=\"456\" height=\"304\""))
        assertTrue(svg.svg.contains("aria-label=\"T\""))
        assertTrue(svg.svg.contains("对照 (2)"))
    }

    @Test
    fun plateSvg_legend_wraps_and_grows_height() {
        val legend = (1..12).map { PlateSvgLegendItem("条目$it", "#4cd6ff", it) }
        val svg = buildPlateSvg(
            PlateSvgOptions(title = "T", rows = 2, cols = 3, cells = emptyList(), legend = legend, legendTitle = "L")
        )
        // 6-well: cellSize 70 → width = max(560, 44+210+28) = 560, contentWidth = 488
        assertEquals(560, svg.width)
        // legend wraps to ≥2 lines → height = 84+140+18+26+2*19+10 > 297
        assertTrue(svg.height > 297)
    }

    @Test
    fun platePngWithDpi_inserts_chunks() {
        val png = byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
        ) + ByteArray(25) { it.toByte() } + ByteArray(20) { (it + 1).toByte() }
        val out = platePngWithDpi(png, 300, "desc")
        // pHYs (9B data → 21) + sRGB (1B → 13) + iTXt ("Description"+4+desc → 31)
        assertEquals(png.size + 21 + 13 + 31, out.size)
        // pHYs pixelsPerMetre = round(300/0.0254) = 11811
        val physAt = 8 + 25 + 8
        assertEquals(
            11811,
            ((out[physAt].toInt() and 0xff) shl 24) or
                ((out[physAt + 1].toInt() and 0xff) shl 16) or
                ((out[physAt + 2].toInt() and 0xff) shl 8) or
                (out[physAt + 3].toInt() and 0xff),
        )
    }

    @Test
    fun serialize_project_is_pretty_printed() {
        val project = PlateProjectFile(1, "spice-plate", "t", 96, mutableListOf(), defaultRules(), mutableListOf(), "group", true, "2026-01-01T00:00:00.000Z")
        val text = serializePlateProject(project)
        assertTrue(text.contains("\"title\": \"t\""))
        assertTrue(text.contains("\"kind\": \"spice-plate\""))
        assertTrue(text.contains("\n"))
    }

    @Test
    fun nowIso_is_utc_iso8601() {
        val iso = nowIso()
        assertTrue(Regex("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}Z$").matches(iso))
    }
}
