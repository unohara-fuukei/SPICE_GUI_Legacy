package com.spice.nativeapp.tools.gel

/**
 * Marker ladder presets. 1:1 移植自原版 src/lib/tools/gel/ladders.ts。
 *
 * Band sizes are taken from the manufacturers' product pages and manuals
 * (catalogue numbers in the names). DNA ladders are in bp, protein ladders in
 * kDa (apparent molecular weight of the prestained bands); `ref` lists the
 * brighter indicator bands the datasheet marks.
 *
 * Prestained protein sizes are calibrated in a Tris-glycine (Laemmli) system
 * and shift with gel percentage and buffer, so sizes read from them are
 * estimates. Mark the tube actually used as a favourite in the editor.
 */
data class LadderDef(
    val id: String,
    val name: String,
    val kind: String,            // "dna" | "protein"
    /** Largest to smallest. */
    val sizes: List<Double>,
    /** Indicator bands, a subset of [sizes]. */
    val ref: List<Double>,
    /** True for user-defined ladders stored in the project. */
    val custom: Boolean = false,
)

object GelLadders {

    val BUILTIN_LADDERS: List<LadderDef> = listOf(
        LadderDef("gr1kb", "Thermo GeneRuler 1 kb (SM0311)", "dna", listOf(10000.0, 8000.0, 6000.0, 5000.0, 4000.0, 3500.0, 3000.0, 2500.0, 2000.0, 1500.0, 1000.0, 750.0, 500.0, 250.0), listOf(6000.0, 3000.0, 1000.0)),
        LadderDef("gr1kbplus", "Thermo GeneRuler 1 kb Plus (SM1331)", "dna", listOf(20000.0, 10000.0, 7000.0, 5000.0, 4000.0, 3000.0, 2000.0, 1500.0, 1000.0, 700.0, 500.0, 400.0, 300.0, 200.0, 75.0), listOf(5000.0, 1500.0, 500.0)),
        LadderDef("gr100plus", "Thermo GeneRuler 100 bp Plus (SM0321)", "dna", listOf(3000.0, 2000.0, 1500.0, 1200.0, 1000.0, 900.0, 800.0, 700.0, 600.0, 500.0, 400.0, 300.0, 200.0, 100.0), listOf(1000.0, 500.0)),
        LadderDef("gr100", "Thermo GeneRuler 100 bp (SM0241)", "dna", listOf(1000.0, 900.0, 800.0, 700.0, 600.0, 500.0, 400.0, 300.0, 200.0, 100.0), listOf(500.0)),
        LadderDef("gr50", "Thermo GeneRuler 50 bp (SM0371)", "dna", listOf(1000.0, 900.0, 800.0, 700.0, 600.0, 500.0, 400.0, 300.0, 250.0, 200.0, 150.0, 100.0, 50.0), listOf(500.0, 250.0)),
        LadderDef("grmix", "Thermo GeneRuler DNA Ladder Mix (SM0331)", "dna", listOf(10000.0, 8000.0, 6000.0, 5000.0, 4000.0, 3500.0, 3000.0, 2500.0, 2000.0, 1500.0, 1200.0, 1000.0, 900.0, 800.0, 700.0, 600.0, 500.0, 400.0, 300.0, 200.0, 100.0), listOf(3000.0, 1000.0, 500.0)),
        LadderDef("grlow", "Thermo GeneRuler Low Range (SM1191)", "dna", listOf(700.0, 500.0, 400.0, 300.0, 200.0, 150.0, 100.0, 75.0, 50.0, 25.0), listOf(300.0, 100.0)),
        LadderDef("grulow", "Thermo GeneRuler Ultra Low Range (SM1211)", "dna", listOf(300.0, 200.0, 150.0, 100.0, 75.0, 50.0, 35.0, 25.0, 20.0, 15.0, 10.0), listOf(50.0)),
        LadderDef("grexpress", "Thermo GeneRuler Express (SM1553)", "dna", listOf(5000.0, 3000.0, 2000.0, 1500.0, 1000.0, 750.0, 500.0, 300.0, 100.0), listOf(1500.0, 500.0)),
        LadderDef("neb1kb", "NEB 1 kb DNA Ladder (N3232)", "dna", listOf(10000.0, 8000.0, 6000.0, 5000.0, 4000.0, 3000.0, 2000.0, 1500.0, 1000.0, 500.0), listOf(3000.0)),
        LadderDef("neb100", "NEB 100 bp DNA Ladder (N3231)", "dna", listOf(1517.0, 1200.0, 1000.0, 900.0, 800.0, 700.0, 600.0, 500.0, 400.0, 300.0, 200.0, 100.0), listOf(1000.0, 500.0)),
        LadderDef("neb1kbplus", "NEB 1 kb Plus DNA Ladder (N3200)", "dna", listOf(10000.0, 8000.0, 6000.0, 5000.0, 4000.0, 3000.0, 2000.0, 1500.0, 1200.0, 1000.0, 900.0, 800.0, 700.0, 600.0, 500.0, 400.0, 300.0, 200.0, 100.0), listOf(3000.0, 1000.0, 500.0)),
        LadderDef("dl2000", "Takara DL2000 (3427A)", "dna", listOf(2000.0, 1000.0, 750.0, 500.0, 250.0, 100.0), listOf(750.0)),
        LadderDef("dl5000", "Takara DL5000 (3428A)", "dna", listOf(5000.0, 3000.0, 2000.0, 1500.0, 1000.0, 750.0, 500.0, 250.0, 100.0), listOf(1000.0)),
        LadderDef("dl15000", "Takara DL15000 (3582A)", "dna", listOf(15000.0, 10000.0, 7500.0, 5000.0, 2500.0, 1000.0, 250.0), emptyList()),
        LadderDef("trans2k", "TransGen Trans2K (BM101)", "dna", listOf(2000.0, 1000.0, 750.0, 500.0, 250.0, 100.0), listOf(750.0)),
        LadderDef("trans2kplus", "TransGen Trans2K Plus (BM111)", "dna", listOf(5000.0, 3000.0, 2000.0, 1000.0, 750.0, 500.0, 250.0, 100.0), listOf(750.0)),
        LadderDef("trans2kplus2", "TransGen Trans2K Plus II (BM121)", "dna", listOf(8000.0, 5000.0, 3000.0, 2000.0, 1000.0, 750.0, 500.0, 250.0, 100.0), listOf(750.0)),
        LadderDef("trans5k", "TransGen Trans5K", "dna", listOf(5000.0, 3000.0, 2000.0, 1500.0, 1000.0, 800.0, 500.0, 300.0), listOf(1500.0)),
        LadderDef("trans15k", "TransGen Trans15K (BM161)", "dna", listOf(15000.0, 10000.0, 7500.0, 5000.0, 3000.0, 1500.0, 1000.0, 500.0), listOf(5000.0)),
        LadderDef("tiangen2000", "Tiangen Marker D2000 (MD114)", "dna", listOf(2000.0, 1000.0, 750.0, 500.0, 250.0, 100.0), listOf(750.0)),
        LadderDef("yeasen1kb", "Yeasen GoldBand 1 kb (10510ES)", "dna", listOf(12000.0, 8000.0, 6000.0, 5000.0, 4000.0, 3000.0, 2500.0, 2000.0, 1500.0, 1000.0, 750.0, 500.0, 250.0), listOf(4000.0, 1500.0)),
        LadderDef("yeasen100plus", "Yeasen GoldBand 100 bp Plus (10516ES)", "dna", listOf(3000.0, 2000.0, 1500.0, 1200.0, 1000.0, 900.0, 800.0, 700.0, 600.0, 500.0, 400.0, 300.0, 200.0, 100.0), listOf(1200.0, 500.0)),
        LadderDef("biorad_dual", "Bio-Rad Precision Plus Dual Color (161-0374)", "protein", listOf(250.0, 150.0, 100.0, 75.0, 50.0, 37.0, 25.0, 20.0, 15.0, 10.0), listOf(75.0, 25.0)),
        LadderDef("biorad_allblue", "Bio-Rad Precision Plus All Blue (161-0373)", "protein", listOf(250.0, 150.0, 100.0, 75.0, 50.0, 37.0, 25.0, 20.0, 15.0, 10.0), listOf(75.0, 50.0, 25.0)),
        LadderDef("biorad_kaleido", "Bio-Rad Precision Plus Kaleidoscope (161-0375)", "protein", listOf(250.0, 150.0, 100.0, 75.0, 50.0, 37.0, 25.0, 20.0, 15.0, 10.0), listOf(150.0, 75.0, 37.0, 25.0, 10.0)),
        LadderDef("biorad_unstained", "Bio-Rad Precision Plus Unstained (161-0363)", "protein", listOf(250.0, 150.0, 100.0, 75.0, 50.0, 37.0, 25.0, 20.0, 15.0, 10.0), listOf(75.0, 50.0, 25.0)),
        LadderDef("biorad_dualxtra", "Bio-Rad Precision Plus Dual Xtra (161-0377)", "protein", listOf(250.0, 150.0, 100.0, 75.0, 50.0, 37.0, 25.0, 20.0, 15.0, 10.0, 5.0, 2.0), listOf(75.0, 25.0, 2.0)),
        LadderDef("thermo_26616", "Thermo PageRuler Prestained 10–180 kDa (26616)", "protein", listOf(180.0, 130.0, 100.0, 70.0, 55.0, 40.0, 35.0, 25.0, 15.0, 10.0), listOf(70.0, 25.0, 10.0)),
        LadderDef("thermo_26619", "Thermo PageRuler Plus Prestained 10–250 kDa (26619)", "protein", listOf(250.0, 130.0, 100.0, 70.0, 55.0, 35.0, 25.0, 15.0, 10.0), listOf(70.0, 25.0, 10.0)),
        LadderDef("thermo_26614", "Thermo PageRuler Unstained 10–200 kDa (26614)", "protein", listOf(200.0, 150.0, 120.0, 100.0, 85.0, 70.0, 60.0, 50.0, 40.0, 30.0, 25.0, 20.0, 15.0, 10.0), listOf(50.0)),
        LadderDef("thermo_26610", "Thermo Pierce Unstained Protein MW Marker 14.4–116 kDa (26610)", "protein", listOf(116.0, 66.2, 45.0, 35.0, 25.0, 18.4, 14.4), emptyList()),
        LadderDef("thermo_26632", "Thermo PageRuler Unstained Low Range 3.4–100 kDa (26632, Tricine)", "protein", listOf(100.0, 30.0, 25.0, 20.0, 15.0, 10.0, 5.0, 3.4), listOf(25.0)),
        LadderDef("thermo_26634", "Thermo Spectra Multicolor Broad Range (26634)", "protein", listOf(260.0, 140.0, 100.0, 70.0, 50.0, 40.0, 35.0, 25.0, 15.0, 10.0), listOf(260.0, 70.0, 50.0, 40.0, 10.0)),
        LadderDef("thermo_26625", "Thermo Spectra Multicolor High Range (26625)", "protein", listOf(300.0, 250.0, 180.0, 130.0, 100.0, 70.0, 50.0, 40.0), listOf(300.0, 70.0, 50.0)),
        LadderDef("thermo_26628", "Thermo Spectra Multicolor Low Range (26628, Tricine)", "protein", listOf(40.0, 25.0, 15.0, 10.0, 4.6, 1.7), listOf(40.0, 10.0)),
        LadderDef("novex_lc5800", "Invitrogen Novex Sharp Pre-stained (LC5800)", "protein", listOf(260.0, 160.0, 110.0, 80.0, 60.0, 50.0, 40.0, 30.0, 20.0, 15.0, 10.0, 3.5), emptyList()),
        LadderDef("seeblue_lc5925", "Invitrogen SeeBlue Plus2 (LC5925, Tris-glycine values)", "protein", listOf(250.0, 148.0, 98.0, 64.0, 50.0, 36.0, 22.0, 16.0, 6.0, 4.0), emptyList()),
        LadderDef("neb_p7719", "NEB Color Prestained Broad Range (P7719)", "protein", listOf(250.0, 180.0, 130.0, 95.0, 72.0, 55.0, 43.0, 34.0, 26.0, 17.0, 10.0), listOf(72.0, 26.0)),
        LadderDef("neb_p7717", "NEB Unstained Broad Range (P7717)", "protein", listOf(200.0, 150.0, 100.0, 85.0, 70.0, 60.0, 50.0, 40.0, 30.0, 25.0, 20.0, 15.0, 10.0), emptyList()),
        LadderDef("abcam_ab116028", "Abcam Prestained Broad Range (ab116028, Tris-glycine values)", "protein", listOf(245.0, 180.0, 135.0, 100.0, 75.0, 63.0, 48.0, 35.0, 25.0, 20.0, 17.0, 11.0), listOf(75.0, 25.0)),
        LadderDef("genscript_m00624", "GenScript Broad Multi Color (M00624)", "protein", listOf(270.0, 175.0, 130.0, 95.0, 65.0, 50.0, 35.0, 30.0, 15.0, 10.0), listOf(270.0, 50.0, 30.0)),
        LadderDef("yeasen_p180", "Yeasen GoldBand Plus 3-color 10–180 kDa (26616ES)", "protein", listOf(180.0, 130.0, 100.0, 70.0, 55.0, 40.0, 35.0, 25.0, 15.0, 10.0), listOf(70.0, 25.0, 10.0)),
        LadderDef("epizyme_wj101", "Epizyme 15–150 kDa (WJ101)", "protein", listOf(150.0, 100.0, 70.0, 50.0, 40.0, 35.0, 25.0, 20.0, 15.0), listOf(70.0, 25.0)),
        LadderDef("epizyme_wj102", "Epizyme Two-color 10–250 kDa (WJ102)", "protein", listOf(250.0, 150.0, 100.0, 70.0, 50.0, 40.0, 35.0, 25.0, 20.0, 15.0, 10.0), listOf(70.0, 25.0)),
        LadderDef("epizyme_wj103", "Epizyme Three-color 10–250 kDa (WJ103)", "protein", listOf(250.0, 150.0, 100.0, 70.0, 50.0, 40.0, 35.0, 25.0, 20.0, 15.0, 10.0), listOf(70.0, 25.0)),
        LadderDef("epizyme_wj106", "Epizyme NIR 10–250 kDa (WJ106)", "protein", listOf(250.0, 150.0, 100.0, 70.0, 50.0, 40.0, 35.0, 25.0, 20.0, 15.0, 10.0), listOf(70.0, 25.0)),
        LadderDef("epizyme_wj107", "Epizyme Three-color 10–180 kDa (WJ107)", "protein", listOf(180.0, 130.0, 100.0, 70.0, 55.0, 40.0, 35.0, 25.0, 15.0, 10.0), listOf(70.0, 10.0)),
        LadderDef("beyotime_p0068", "Beyotime Prestained 10–180 kDa (P0068/P0069)", "protein", listOf(180.0, 130.0, 95.0, 72.0, 55.0, 43.0, 34.0, 26.0, 17.0, 10.0), listOf(72.0, 10.0)),
        LadderDef("beyotime_p0071", "Beyotime BeyoColor 6.5–270 kDa (P0071/P0072)", "protein", listOf(270.0, 175.0, 130.0, 95.0, 66.0, 52.0, 37.0, 30.0, 16.0, 6.5), listOf(270.0, 52.0, 30.0)),
        LadderDef("beyotime_p0075", "Beyotime BeyoColor 10–170 kDa (P0075/P0076/P0077)", "protein", listOf(170.0, 130.0, 100.0, 70.0, 55.0, 45.0, 35.0, 25.0, 15.0, 10.0), listOf(70.0, 15.0)),
        LadderDef("beyotime_p0776", "Beyotime BeyoColor 10–250 kDa (P0776)", "protein", listOf(250.0, 130.0, 100.0, 70.0, 50.0, 40.0, 30.0, 25.0, 20.0, 15.0, 10.0), listOf(70.0, 25.0)),
        LadderDef("beyotime_p0773", "Beyotime BeyoColor 8–180 kDa (P0773)", "protein", listOf(180.0, 130.0, 100.0, 70.0, 55.0, 43.0, 33.0, 25.0, 17.0, 8.0), listOf(70.0, 8.0)),
        LadderDef("beyotime_p0779", "Beyotime BeyoColor 25–300 kDa (P0779)", "protein", listOf(300.0, 250.0, 200.0, 160.0, 130.0, 100.0, 70.0, 45.0, 25.0), listOf(70.0, 25.0)),
        LadderDef("beyotime_p0771", "Beyotime BeyoColor 2.7–40 kDa (P0771, Tricine)", "protein", listOf(40.0, 30.0, 25.0, 20.0, 15.0, 10.0, 6.5, 4.2, 2.7), listOf(40.0, 10.0)),
        LadderDef("beyotime_p0060", "Beyotime Unstained 10–150 kDa (P0060)", "protein", listOf(150.0, 95.0, 80.0, 66.0, 52.0, 42.0, 35.0, 25.0, 15.0, 10.0), emptyList()),
        LadderDef("beyotime_p0063", "Beyotime Unstained 10–200 kDa (P0063)", "protein", listOf(200.0, 150.0, 120.0, 100.0, 85.0, 70.0, 60.0, 50.0, 40.0, 30.0, 25.0, 20.0, 15.0, 10.0), listOf(50.0)),
        LadderDef("transgen_dm111", "TransGen Blue Plus II (DM111)", "protein", listOf(120.0, 100.0, 70.0, 50.0, 40.0, 30.0, 25.0, 14.0), listOf(120.0, 50.0, 14.0)),
        LadderDef("transgen_dm141", "TransGen Blue Plus V (DM141)", "protein", listOf(190.0, 140.0, 95.0, 70.0, 55.0, 43.0, 33.0, 26.0, 20.0, 15.0, 10.0), listOf(70.0, 20.0)),
        LadderDef("transgen_dm151", "TransGen Blue Plus VI (DM151)", "protein", listOf(250.0, 130.0, 100.0, 72.0, 50.0, 40.0, 30.0, 25.0, 20.0, 15.0, 10.0), listOf(72.0, 25.0)),
        LadderDef("transgen_dr201", "TransGen ProteinRuler II unstained (DR201)", "protein", listOf(120.0, 100.0, 80.0, 60.0, 50.0, 40.0, 30.0, 20.0, 12.0), listOf(50.0)),
        LadderDef("solarbio_pr1910", "Solarbio Rainbow 180 (PR1910)", "protein", listOf(180.0, 135.0, 100.0, 75.0, 63.0, 48.0, 35.0, 25.0, 17.0, 11.0), listOf(75.0, 25.0)),
        LadderDef("solarbio_pr1920", "Solarbio Rainbow 245 (PR1920)", "protein", listOf(245.0, 180.0, 135.0, 100.0, 75.0, 63.0, 48.0, 35.0, 25.0, 20.0, 17.0, 11.0), listOf(75.0, 25.0)),
        LadderDef("sangon_c610110", "Sangon RealBand Marker II (C610110)", "protein", listOf(180.0, 130.0, 95.0, 72.0, 52.0, 41.0, 31.0, 25.0, 17.0, 10.0), listOf(72.0, 10.0)),
        LadderDef("sangon_c610210", "Sangon RealBand Two-color 10–250 kDa (C610210)", "protein", listOf(250.0, 150.0, 100.0, 70.0, 50.0, 40.0, 35.0, 25.0, 20.0, 15.0, 10.0), listOf(70.0, 25.0)),
    )

    private val BY_ID: Map<String, LadderDef> = BUILTIN_LADDERS.associateBy { it.id }

    fun allLadders(custom: List<LadderDef> = emptyList()): List<LadderDef> = BUILTIN_LADDERS + custom

    fun ladderById(id: String, custom: List<LadderDef> = emptyList()): LadderDef? =
        BY_ID[id] ?: custom.firstOrNull { it.id == id }

    fun laddersFor(kind: String, custom: List<LadderDef> = emptyList()): List<LadderDef> =
        allLadders(custom).filter { it.kind == kind }

    fun unitForKind(kind: String): String = if (kind == "protein") "kDa" else "bp"

    /** Sort a ladder's sizes largest to smallest, de-duplicated. */
    fun normalizeSizes(sizes: List<Double>): List<Double> =
        sizes.filter { it.isFinite() && it > 0 }.distinct().sortedDescending()

    // ---------------------------------------------------------------- JSON

    fun toJson(def: LadderDef): org.json.JSONObject = org.json.JSONObject().apply {
        put("id", def.id)
        put("name", def.name)
        put("kind", def.kind)
        put("sizes", org.json.JSONArray().apply { def.sizes.forEach { put(numberForJson(it)) } })
        put("ref", org.json.JSONArray().apply { def.ref.forEach { put(numberForJson(it)) } })
        if (def.custom) put("custom", true)
    }

    fun fromJson(o: org.json.JSONObject): LadderDef {
        val sizes = ArrayList<Double>()
        val sj = o.optJSONArray("sizes")
        if (sj != null) for (i in 0 until sj.length()) sizes.add(sj.optDouble(i, 0.0))
        val ref = ArrayList<Double>()
        val rj = o.optJSONArray("ref")
        if (rj != null) for (i in 0 until rj.length()) ref.add(rj.optDouble(i, 0.0))
        return LadderDef(
            id = o.optString("id"),
            name = o.optString("name"),
            kind = if (o.optString("kind") == "protein") "protein" else "dna",
            sizes = sizes,
            ref = ref,
            custom = o.optBoolean("custom", false),
        )
    }

    /** JSON 数字写出：整数不带小数点，与原版 JSON.stringify 完全一致。 */
    internal fun numberForJson(v: Double): Any =
        if (v == Math.floor(v) && !v.isInfinite() && Math.abs(v) < 9.007199254740992e15) v.toLong() else v
}
