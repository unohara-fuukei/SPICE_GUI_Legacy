package com.spice.nativeapp.tools.plate

// Deterministic plate assignment engine.
// 1:1 port of src/lib/tools/plate/assign.ts.
//
// The engine places controls, blanks, reserved edge wells and sample
// replicates on a fixed plate geometry. It never silently drops a constraint:
// every shortfall is returned as a localized-key issue and as an unplaced
// entry so the UI can show exactly what happened.

const val CONTROL_GROUP = "Control"
const val BLANK_GROUP = "Blank"

data class AssignResult(
    val assignments: MutableMap<String, WellAssignment>,
    val issues: List<LayoutIssue>,
    val unplaced: List<UnplacedSample>,
    val seed: Int,
    val reservedCount: Int,
    val assigned: Int,
    val sampleWells: Int,
    val controlWells: Int,
    val blankWells: Int,
)

data class ValidationResult(
    val issues: List<LayoutIssue>,
    val unplaced: List<UnplacedSample>,
)

private fun pushIssue(
    list: MutableList<LayoutIssue>,
    key: String,
    severity: String,
    params: Map<String, Any> = emptyMap(),
) {
    list.add(LayoutIssue(key, severity, params))
}

private fun issueToken(issue: LayoutIssue): String =
    issue.key + "|" + issue.params.entries.joinToString(",") { "${it.key}=${it.value}" }

private fun dedupeIssues(list: List<LayoutIssue>): List<LayoutIssue> {
    val seen = HashSet<String>()
    val out = ArrayList<LayoutIssue>()
    for (issue in list) {
        val token = issueToken(issue)
        if (token in seen) continue
        seen.add(token)
        out.add(issue)
    }
    val weight = mapOf("error" to 0, "warn" to 1, "info" to 2)
    // JS Array.sort is stable; sortBy keeps equal-key order.
    return out.sortedBy { weight[it.severity] ?: 3 }
}

/** Number of orthogonally connected components in a set of wells. */
private fun connectedGroups(cells: List<WellAssignment>): Int {
    if (cells.size < 2) return cells.size
    val keys = cells.map { "${it.row},${it.col}" }.toHashSet()
    val seen = HashSet<String>()
    var groups = 0
    for (key in keys) {
        if (key in seen) continue
        groups++
        seen.add(key)
        val stack = ArrayDeque<String>()
        stack.addLast(key)
        while (stack.isNotEmpty()) {
            val current = stack.removeLast()
            val (row, col) = current.split(",").map { it.toInt() }
            val neighbours = listOf("${row + 1},$col", "${row - 1},$col", "$row,${col + 1}", "$row,${col - 1}")
            for (next in neighbours) {
                if (next in keys && next !in seen) {
                    seen.add(next)
                    stack.addLast(next)
                }
            }
        }
    }
    return groups
}

fun validateLayout(
    format: PlateFormat,
    samples: List<SampleRow>,
    rules: PlateRules,
    assignments: Map<String, WellAssignment>,
): ValidationResult {
    val geometry = formatGeometry(format)
    val list = assignments.values.toList()
    val issues = ArrayList<LayoutIssue>()
    val unplaced = ArrayList<UnplacedSample>()

    // Duplicate guard. The internal map cannot hold two values for one well,
    // but imported or hand-built data can still be checked through this path.
    val seenWells = HashSet<String>()
    for (assignment in list) {
        if (assignment.well in seenWells) {
            pushIssue(issues, "plateIssueDuplicate", "error", mapOf("well" to assignment.well))
        }
        seenWells.add(assignment.well)
    }

    if (samples.isEmpty()) {
        pushIssue(issues, "plateIssueNoSamples", "info")
    }

    // Sample-list sanity checks.
    var blankNameReported = false
    val nameCount = LinkedHashMap<String, Pair<String, Int>>()
    for (sample in samples) {
        val name = sample.name.trim()
        if (name.isEmpty()) {
            if (!blankNameReported) {
                pushIssue(issues, "plateSampleNameRequired", "warn")
                blankNameReported = true
            }
            continue
        }
        val key = name.lowercase()
        val entry = nameCount[key]
        if (entry != null) nameCount[key] = entry.first to entry.second + 1
        else nameCount[key] = name to 1
    }
    for ((_, entry) in nameCount) {
        if (entry.second > 1) {
            pushIssue(issues, "plateDuplicateName", "warn", mapOf("name" to entry.first))
        }
    }

    // Capacity: reserved edge wells are unavailable to samples. Controls and
    // blanks may still use them, so the hard limit is checked separately.
    val sampleNeed = samples.sumOf { requiredReplicates(it, rules).toDouble() }.toInt()
    val auxNeed = maxOf(0, rules.controls) + maxOf(0, rules.blanks)
    val edgeCount = buildWellCells(geometry).count { it.edge }
    val interiorCount = geometry.total - edgeCount
    if (rules.reserveEdge && sampleNeed > interiorCount) {
        pushIssue(issues, "plateIssueCapacity", "error", mapOf("needed" to sampleNeed, "available" to interiorCount))
    } else if (sampleNeed + auxNeed > geometry.total) {
        pushIssue(issues, "plateIssueCapacity", "error", mapOf("needed" to sampleNeed + auxNeed, "available" to geometry.total))
    }

    // Sample placement checks.
    for (sample in samples) {
        val requested = requiredReplicates(sample, rules)
        val cells = list
            .filter { it.type == "sample" && it.sampleId == sample.id }
            .sortedWith(compareBy({ it.row }, { it.col }))
        val placed = cells.size
        val missing = maxOf(0, requested - placed)
        if (missing > 0) {
            pushIssue(
                issues, "plateIssueReplicatesMissing", "error",
                mapOf("name" to (sample.name.ifEmpty { sample.id }), "placed" to placed, "requested" to requested),
            )
            unplaced.add(
                UnplacedSample(sample.id, sample.name.ifEmpty { sample.id }, requested, placed, missing)
            )
        }
        if (cells.size > 1 && rules.keepTogether && connectedGroups(cells) > 1) {
            pushIssue(issues, "plateIssueGroupSplit", "warn", mapOf("name" to (sample.name.ifEmpty { sample.id })))
        }
        if ((rules.avoidEdge || rules.reserveEdge) &&
            cells.any { isEdgeWell(it.row, it.col, geometry.rows, geometry.cols) }
        ) {
            pushIssue(issues, "plateIssueEdgeUsed", "warn", mapOf("name" to (sample.name.ifEmpty { sample.id })))
        }
    }

    // Controls and blanks.
    val controlPlaced = list.count { it.type == "control" }
    val blankPlaced = list.count { it.type == "blank" }
    if (controlPlaced < rules.controls) {
        pushIssue(issues, "plateIssueControlsMissing", "warn", mapOf("placed" to controlPlaced, "requested" to rules.controls))
    }
    if (blankPlaced < rules.blanks) {
        pushIssue(issues, "plateIssueBlanksMissing", "warn", mapOf("placed" to blankPlaced, "requested" to rules.blanks))
    }

    return ValidationResult(dedupeIssues(issues), unplaced)
}

private fun sampleAssignment(cell: WellCell, sample: SampleRow, replicate: Int): WellAssignment =
    WellAssignment(
        well = cell.id,
        row = cell.row,
        col = cell.col,
        type = "sample",
        sampleId = sample.id,
        sampleName = sample.name,
        group = sample.group,
        replicate = replicate,
        color = sample.color,
        manual = false,
    )

fun assignPlate(format: PlateFormat, samples: List<SampleRow>, rules: PlateRules): AssignResult {
    val geometry = formatGeometry(format)
    val allCells = buildWellCells(geometry)
    val assignments = LinkedHashMap<String, WellAssignment>()
    val used = HashSet<String>()
    val internalIssues = ArrayList<LayoutIssue>()
    val random = mulberry32(rules.seed)
    val cols = geometry.cols
    fun cellAt(row: Int, col: Int): WellCell = allCells[row * cols + col]
    fun sampleEligible(cell: WellCell): Boolean =
        if (rules.reserveEdge || rules.avoidEdge) !cell.edge else true
    fun usable(cell: WellCell): Boolean = cell.id !in used && sampleEligible(cell)

    fun put(cell: WellCell, value: WellAssignment): Boolean {
        if (assignments.containsKey(cell.id)) {
            pushIssue(internalIssues, "plateIssueDuplicate", "error", mapOf("well" to cell.id))
            return false
        }
        assignments[cell.id] = value
        used.add(cell.id)
        return true
    }

    /** Controls and blanks prefer the edge ring, then the interior. */
    fun takeAuxCells(count: Int): List<WellCell> {
        val wanted = clampInt(count.toDouble(), 0, geometry.total, 0)
        if (wanted == 0) return emptyList()
        val pools = listOf(
            allCells.filter { it.edge },
            allCells.filter { !it.edge },
        )
        val picked = ArrayList<WellCell>()
        for (pool in pools) {
            if (picked.size >= wanted) break
            val available = pool.filter { it.id !in used }.toMutableList()
            if (rules.randomize) shuffleInPlace(available, random)
            for (cell in available.take(wanted - picked.size)) {
                used.add(cell.id)
                picked.add(cell)
            }
        }
        return picked
    }

    fun takeFrom(pool: List<WellCell>, count: Int): List<WellCell> {
        val available = pool.filter { it.id !in used }.toMutableList()
        if (rules.randomize) shuffleInPlace(available, random)
        val picked = available.take(count)
        for (cell in picked) used.add(cell.id)
        return picked
    }

    fun placeScattered(count: Int): List<WellCell> {
        val pool = allCells.filter { usable(it) }
        val picked = takeFrom(pool, count).toMutableList()
        // Edge avoidance is best effort: when the interior is exhausted the
        // remaining replicates go to edge wells and validation reports it.
        if (picked.size < count && rules.avoidEdge && !rules.reserveEdge) {
            picked.addAll(takeFrom(allCells.filter { it.edge }, count - picked.size))
        }
        return picked
    }

    fun rowCandidates(count: Int): List<List<WellCell>> {
        if (count > cols) return emptyList()
        val out = ArrayList<List<WellCell>>()
        for (row in 0 until geometry.rows) {
            for (col in 0..cols - count) {
                val cells = ArrayList<WellCell>()
                var ok = true
                for (step in 0 until count) {
                    val cell = cellAt(row, col + step)
                    if (!usable(cell)) {
                        ok = false
                        break
                    }
                    cells.add(cell)
                }
                if (ok) out.add(cells)
            }
        }
        return out
    }

    fun columnCandidates(count: Int): List<List<WellCell>> {
        if (count > geometry.rows) return emptyList()
        val out = ArrayList<List<WellCell>>()
        for (col in 0 until cols) {
            for (row in 0..geometry.rows - count) {
                val cells = ArrayList<WellCell>()
                var ok = true
                for (step in 0 until count) {
                    val cell = cellAt(row + step, col)
                    if (!usable(cell)) {
                        ok = false
                        break
                    }
                    cells.add(cell)
                }
                if (ok) out.add(cells)
            }
        }
        return out
    }

    fun blockDimensions(count: Int): Pair<Int, Int> {
        var blockCols = if (rules.groupColumns > 0) minOf(rules.groupColumns, cols) else 0
        var blockRows = if (rules.groupRows > 0) minOf(rules.groupRows, geometry.rows) else 0
        if (blockCols > 0 && blockRows > 0) {
            while (blockCols * blockRows < count) {
                if (blockCols < cols) blockCols++
                else if (blockRows < geometry.rows) blockRows++
                else break
            }
            return blockRows to blockCols
        }
        if (blockCols > 0) {
            return minOf(geometry.rows, Math.ceil(count.toDouble() / blockCols).toInt()) to blockCols
        }
        if (blockRows > 0) {
            return blockRows to minOf(cols, Math.ceil(count.toDouble() / blockRows).toInt())
        }
        var bestRows = minOf(geometry.rows, count)
        var bestCols = minOf(cols, Math.ceil(count.toDouble() / minOf(geometry.rows, count)).toInt())
        var bestScore = Double.POSITIVE_INFINITY
        for (width in 1..minOf(count, cols)) {
            val height = Math.ceil(count.toDouble() / width).toInt()
            if (height > geometry.rows) continue
            val waste = width * height - count
            val score = (waste * 20 + Math.abs(width - height)).toDouble()
            if (score < bestScore) {
                bestScore = score
                bestRows = height
                bestCols = width
            }
        }
        return bestRows to bestCols
    }

    fun blockCandidates(count: Int): List<List<WellCell>> {
        val dims = blockDimensions(count)
        val (dimsRows, dimsCols) = dims
        if (dimsRows < 1 || dimsCols < 1) return emptyList()
        if (dimsRows > geometry.rows || dimsCols > cols) return emptyList()
        val out = ArrayList<List<WellCell>>()
        for (row in 0..geometry.rows - dimsRows) {
            for (col in 0..cols - dimsCols) {
                val cells = ArrayList<WellCell>()
                var ok = true
                var r = 0
                while (r < dimsRows && ok) {
                    for (c in 0 until dimsCols) {
                        val cell = cellAt(row + r, col + c)
                        if (!usable(cell)) {
                            ok = false
                            break
                        }
                        if (cells.size < count) cells.add(cell)
                    }
                    r++
                }
                if (ok) out.add(cells)
            }
        }
        return out
    }

    fun chooseCandidate(candidates: List<List<WellCell>>): List<WellCell>? {
        if (candidates.isEmpty()) return null
        // First-fit packing keeps groups adjacent instead of scattering them
        // into holes. Randomization still applies to the sample order and to
        // scattered wells, so a different seed gives a different layout.
        return candidates[0]
    }

    fun placeGrouped(count: Int): List<WellCell> {
        val layout = rules.groupLayout
        val order: List<String> = if (layout == "auto") listOf("row", "column", "block") else listOf(layout)
        for (strategy in order) {
            val candidates = when (strategy) {
                "row" -> rowCandidates(count)
                "column" -> columnCandidates(count)
                else -> blockCandidates(count)
            }
            val choice = chooseCandidate(candidates)
            if (choice != null && choice.size == count) {
                for (cell in choice) used.add(cell.id)
                return choice
            }
        }
        // Grouping was requested but impossible: place scattered wells and let
        // validation report the split instead of pretending success.
        return placeScattered(count)
    }

    val orderedSamples = if (rules.randomize) shuffled(samples, random) else samples
    for (sample in orderedSamples) {
        val count = requiredReplicates(sample, rules)
        val cells = if (rules.keepTogether) placeGrouped(count) else placeScattered(count)
        cells.forEachIndexed { index, cell ->
            put(cell, sampleAssignment(cell, sample, index + 1))
        }
    }

    // Controls and blanks are placed after the samples so that grouping and
    // edge avoidance are not fragmented by auxiliary wells.
    val controlCells = takeAuxCells(rules.controls)
    controlCells.forEachIndexed { index, cell ->
        put(
            cell,
            WellAssignment(
                well = cell.id, row = cell.row, col = cell.col, type = "control",
                sampleId = "", sampleName = "CTRL-${index + 1}", group = CONTROL_GROUP,
                replicate = index + 1, color = "#b48cff", manual = false,
            ),
        )
    }

    val blankCells = takeAuxCells(rules.blanks)
    blankCells.forEachIndexed { index, cell ->
        put(
            cell,
            WellAssignment(
                well = cell.id, row = cell.row, col = cell.col, type = "blank",
                sampleId = "", sampleName = "BLANK-${index + 1}", group = BLANK_GROUP,
                replicate = index + 1, color = "#7f8ba8", manual = false,
            ),
        )
    }

    // Remaining edge wells become explicit reserved wells when requested.
    if (rules.reserveEdge) {
        for (cell in allCells) {
            if (!cell.edge || cell.id in used) continue
            put(
                cell,
                WellAssignment(
                    well = cell.id, row = cell.row, col = cell.col, type = "reserved",
                    sampleId = "", sampleName = "", group = "",
                    replicate = 0, color = "#26304a", manual = false,
                ),
            )
        }
    }

    val validation = validateLayout(format, samples, rules, assignments)
    val issues = dedupeIssues(internalIssues + validation.issues)
    val reservedCount = assignments.values.count { it.type == "reserved" }
    val sampleWells = assignments.values.count { it.type == "sample" }
    val controlWells = assignments.values.count { it.type == "control" }
    val blankWells = assignments.values.count { it.type == "blank" }

    return AssignResult(
        assignments = assignments,
        issues = issues,
        unplaced = validation.unplaced,
        seed = rules.seed,
        reservedCount = reservedCount,
        assigned = assignments.size,
        sampleWells = sampleWells,
        controlWells = controlWells,
        blankWells = blankWells,
    )
}

/** Creates a manual sample assignment for the paint tool. */
fun manualSampleAssignment(
    geometry: PlateGeometry,
    sample: SampleRow,
    row: Int,
    col: Int,
    replicate: Int,
): WellAssignment =
    WellAssignment(
        well = wellId(row, col),
        row = row,
        col = col,
        type = "sample",
        sampleId = sample.id,
        sampleName = sample.name,
        group = sample.group,
        replicate = replicate,
        color = sample.color,
        manual = true,
    )

fun manualAuxAssignment(
    geometry: PlateGeometry,
    type: String,
    row: Int,
    col: Int,
    replicate: Int,
): WellAssignment =
    WellAssignment(
        well = wellId(row, col),
        row = row,
        col = col,
        type = type,
        sampleId = "",
        sampleName = if (type == "control") "CTRL-$replicate" else "BLANK-$replicate",
        group = if (type == "control") CONTROL_GROUP else BLANK_GROUP,
        replicate = replicate,
        color = if (type == "control") "#b48cff" else "#7f8ba8",
        manual = true,
    )
