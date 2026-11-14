package com.spice.nativeapp.tools.qpcr

/*
 * Deterministic example plate. 1:1 port of main's src/lib/tools/qpcr/example.ts:
 * six samples, three targets, technical triplicates and two standard curves.
 * One missing Ct, one high Ct, one outlier and one duplicated well name make
 * every QC flag visible.
 */

import kotlin.math.pow

private data class SampleSeed(
    val name: String,
    val group: String,
    val plateRow: String,
    val gapdh: Triple<String, String, String>,
    val il6: Triple<String, String, String>,
    val tnf: Triple<String, String, String>,
)

private val SAMPLES = listOf(
    SampleSeed("C1", "Control", "A",
        Triple("17.95", "18.08", "18.01"),
        Triple("25.92", "26.04", "25.97"),
        Triple("27.44", "27.53", "27.49")),
    SampleSeed("C2", "Control", "B",
        Triple("18.02", "18.14", "18.06"),
        Triple("26.03", "27.10", "26.08"),
        Triple("27.51", "27.61", "27.55")),
    SampleSeed("C3", "Control", "C",
        Triple("17.98", "18.09", "18.03"),
        Triple("25.88", "26.01", "25.94"),
        Triple("27.39", "27.48", "27.43")),
    SampleSeed("T1", "Treated", "D",
        Triple("18.05", "18.18", "18.10"),
        Triple("23.96", "24.08", "24.01"),
        Triple("25.47", "25.58", "25.52")),
    SampleSeed("T2", "Treated", "E",
        Triple("18.01", "18.12", "18.07"),
        Triple("24.03", "24.14", "24.08"),
        Triple("25.52", "25.63", "36.20")),
    SampleSeed("T3", "Treated", "F",
        Triple("18.08", "18.19", "18.12"),
        Triple("24.00", "24.12", "Undetermined"),
        Triple("25.44", "25.55", "25.49")),
)

private data class TargetSeed(
    val name: String,
    val offset: Int,
    val efficiency: String,
    val tm: String,
)

private val TARGETS = listOf(
    TargetSeed("GAPDH", 0, "98", "81.2"),
    TargetSeed("IL6", 3, "95", "79.4"),
    TargetSeed("TNF", 6, "102", "80.1"),
)

private data class StandardSeed(
    val target: String,
    val row: String,
    val cts: List<String>,
    val efficiency: String,
    val tm: String,
)

private val STANDARDS = listOf(
    StandardSeed("IL6", "G", listOf("20.24", "23.38", "26.51", "29.66", "32.82"), "95", "79.4"),
    StandardSeed("TNF", "H", listOf("21.02", "24.15", "27.31", "30.44", "33.58"), "102", "80.1"),
)

fun buildExampleTsv(): String {
    val lines = ArrayList<String>()
    lines.add(listOf("Well", "Sample", "Target", "Ct", "Group", "Replicate", "Efficiency", "Tm", "Quantity").joinToString("\t"))

    for (sample in SAMPLES) {
        val series = mapOf(
            "GAPDH" to sample.gapdh,
            "IL6" to sample.il6,
            "TNF" to sample.tnf,
        )
        for (target in TARGETS) {
            val values = series[target.name]!!
            values.toList().forEachIndexed { index, ct ->
                var well = "${sample.plateRow}${target.offset + index + 1}"
                // Duplicated well name on purpose, to exercise the duplicate flag.
                if (sample.name == "C3" && target.name == "TNF" && index == 1) well = "C4"
                lines.add(
                    listOf(
                        well, sample.name, target.name, ct, sample.group,
                        (index + 1).toString(), target.efficiency, target.tm, "",
                    ).joinToString("\t")
                )
            }
        }
    }

    for (standard in STANDARDS) {
        standard.cts.forEachIndexed { index, ct ->
            val quantity = 10.0.pow(6 - index).let {
                if (it == kotlin.math.floor(it) && !it.toString().contains('E')) it.toLong().toString() else it.toString()
            }
            lines.add(
                listOf(
                    "${standard.row}${index + 1}",
                    "Std 1e${6 - index}",
                    standard.target, ct, "Standard", "1",
                    standard.efficiency, standard.tm, quantity,
                ).joinToString("\t")
            )
        }
    }

    return lines.joinToString("\n") + "\n"
}

val EXAMPLE_TSV: String = buildExampleTsv()
