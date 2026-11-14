package com.spice.nativeapp.core

/**
 * 克隆方法仿真引擎 — 1:1 复刻 main `genome/cloningMethods.ts`（575 行）：
 * restriction / gibson / golden_gate / in_fusion / nebuilder_hifi / gateway /
 * topo(+directional) / ta / gc 九类方法 + simulateCloning 分发 + makeError。
 *
 * - 切点计算依赖 EnzymesVe（main enzymes.ts 的 VE 引擎），复用 core/EnzymeDb（与 main 酶库同源）。
 * - tidyUpSequenceData（main model.ts）对已是纯 ATGC 大写的 DNA 输入在 sequence/features 字段上
 *   为恒等变换；此处实现为字段直拷 + 缺省补齐（id 分配等展示性字段不在本块断言范围）。
 * - main 的 `x || "Vector"` 空串回退语义用 ifEmpty 复刻。
 */
object CloningSim {

    const val M_RESTRICTION = "restriction"
    const val M_GIBSON = "gibson"
    const val M_GOLDEN_GATE = "golden_gate"
    const val M_INFUSION = "in_fusion"
    const val M_NEBUILDER_HIFI = "nebuilder_hifi"
    const val M_GATEWAY = "gateway"
    const val M_TOPO = "topo"
    const val M_TOPO_DIRECTIONAL = "topo_directional"
    const val M_TA = "ta"
    const val M_GC = "gc"

    /** 特征注解（main SequenceData.features 元素的子集） */
    data class SimFeature(
        val id: Long? = null,
        val name: String = "",
        val type: String = "",
        val start: Int = 0,
        val end: Int = 0,
        val forward: Boolean = true
    )

    /** main SequenceData（仿真所用字段子集） */
    data class SimSequenceData(
        val name: String = "",
        val sequence: String = "",
        val circular: Boolean = false,
        val features: List<SimFeature> = emptyList()
    )

    data class CloningInput(
        val vector: SimSequenceData,
        val insert: SimSequenceData,
        val method: String,
        val leftEnzyme: String? = null,
        val rightEnzyme: String? = null,
        val overlapLength: Int? = null,
        val goldenGateEnzyme: String? = null,
        val attL1: Boolean? = null,
        val directional: Boolean? = null,
        val vectorPhosphorLeft: Boolean? = null,
        val vectorPhosphorRight: Boolean? = null,
        val insertPhosphorLeft: Boolean? = null,
        val insertPhosphorRight: Boolean? = null
    )

    data class CloningHistoryNode(
        val method: String,
        val productName: String,
        val parentVector: String,
        val insertName: String,
        val timestamp: String,
        val details: Map<String, String> = emptyMap()
    )

    data class CloningOutput(
        val product: SimSequenceData,
        val productName: String,
        val insertStart: Int,
        val insertEnd: Int,
        val historyNode: CloningHistoryNode,
        val warnings: List<String>
    )

    private fun nowIso(): String = java.time.Instant.now().toString()

    /** tidyUpSequenceData（本块观测子集：sequence/features/name/circular 直拷） */
    private fun tidyUp(base: SimSequenceData, sequence: String, features: List<SimFeature>, name: String, circular: Boolean): SimSequenceData =
        SimSequenceData(name = name, sequence = sequence, circular = circular, features = features)

    // ───────────────── Restriction ─────────────────

    fun simulateRestrictionCloning(input: CloningInput): CloningOutput {
        val warnings = mutableListOf<String>()

        // T4 连接酶 5'-磷酸要求（main cmT4LigaseDephosWarning 原文）
        val isVectorPhos = input.vectorPhosphorLeft != false && input.vectorPhosphorRight != false
        val isInsertPhos = input.insertPhosphorLeft != false && input.insertPhosphorRight != false
        if (!isVectorPhos && !isInsertPhos) {
            warnings += I18n.t("T4 连接酶警报: 载体与插入片段两端均处于去磷酸化状态！由于缺少 5' 磷酸基团，T4 DNA 连接酶将无法形成任何共价磷酸二酯键，克隆连接效率为 0%！建议使用 T4 PNK 激酶对插入片段进行磷酸化，或对载体进行磷酸化修饰。")
        }
        val vSeq = input.vector.sequence.uppercase()
        val iSeq = input.insert.sequence.uppercase()
        val leftEnz = input.leftEnzyme ?: "EcoRI"
        val rightEnz = input.rightEnzyme ?: "BamHI"

        // main resolveEnzyme：getEnzymeByName ?? enzymeFromSite(name,'',0) —— 后者恒返回定义
        // （site 为空 → 切点引擎 error → 0 切点 → 走 sites missing 错误路径；null 分支在 main 为死代码）
        val leftE: EnzymesVe.VeEnzyme? = EnzymesVe.getEnzymeByName(leftEnz) ?: EnzymesVe.enzymeFromSite(leftEnz, "", 0)
        val rightE: EnzymesVe.VeEnzyme? = EnzymesVe.getEnzymeByName(rightEnz) ?: EnzymesVe.enzymeFromSite(rightEnz, "", 0)
        if (leftE == null || rightE == null) {
            return makeError(input, "Unknown enzyme: ${if (leftE == null) leftEnz else rightEnz}", warnings)
        }

        val vCutsL = EnzymesVe.cutSequenceByRestrictionEnzyme(vSeq, input.vector.circular, leftE)
        val vCutsR = EnzymesVe.cutSequenceByRestrictionEnzyme(vSeq, input.vector.circular, rightE)
        val iCutsL = EnzymesVe.cutSequenceByRestrictionEnzyme(iSeq, input.insert.circular, leftE)
        val iCutsR = EnzymesVe.cutSequenceByRestrictionEnzyme(iSeq, input.insert.circular, rightE)

        if (vCutsL.isEmpty()) warnings += "Left enzyme $leftEnz not found in vector"
        if (vCutsR.isEmpty()) warnings += "Right enzyme $rightEnz not found in vector"
        if (iCutsL.isEmpty()) warnings += "Left enzyme $leftEnz not found in insert"
        if (iCutsR.isEmpty()) warnings += "Right enzyme $rightEnz not found in insert"

        if (vCutsL.isEmpty() || vCutsR.isEmpty() || iCutsL.isEmpty() || iCutsR.isEmpty()) {
            return makeError(input, "Enzyme sites missing in vector or insert", warnings)
        }

        val vLeft = vCutsL[0]
        val vRight = vCutsR[0]
        val iLeft = iCutsL[0]
        val iRight = iCutsR[0]

        val vLeftClip = vLeft.topSnipPosition
        val vRightClip = vRight.topSnipPosition
        val iLeftClip = iLeft.topSnipPosition
        val iRightClip = iRight.topSnipPosition

        val vecLeft = vSeq.substring(0, minOf(vLeftClip, vRightClip))
        val vecRight = vSeq.substring(maxOf(vLeftClip, vRightClip))
        val insFrag = iSeq.substring(minOf(iLeftClip, iRightClip), maxOf(iLeftClip, iRightClip))

        val productSeq = vecLeft + insFrag + vecRight
        val productName = "${input.vector.name.ifEmpty { "Vector" }}_cloned_${input.insert.name.ifEmpty { "Insert" }}"
        val insertStart = vecLeft.length
        val insertEnd = insertStart + insFrag.length - 1

        val vMin = minOf(vLeftClip, vRightClip)
        val vMax = maxOf(vLeftClip, vRightClip)
        // main：保留与被替换区段相交外的载体特征，start/end >= vMax 的整体重映射到插入后坐标
        val shift = { x: Int -> if (x >= vMax) x - vMax + vecLeft.length + insFrag.length else x }
        val features = input.vector.features.filter { f ->
            f.start < vMin || f.end > vMax
        }.map { f -> f.copy(start = shift(f.start), end = shift(f.end)) } + SimFeature(
            id = System.currentTimeMillis(),
            name = input.insert.name.ifEmpty { "Insert" },
            type = "CDS",
            start = insertStart,
            end = insertEnd,
            forward = true
        )

        val product = tidyUp(input.vector, productSeq, features, productName, input.vector.circular)

        return CloningOutput(
            product, productName, insertStart, insertEnd,
            CloningHistoryNode(
                M_RESTRICTION, productName,
                input.vector.name.ifEmpty { "Vector" },
                input.insert.name.ifEmpty { "Insert" },
                nowIso(),
                mapOf("leftEnzyme" to leftEnz, "rightEnzyme" to rightEnz, "insertSize" to insFrag.length.toString())
            ),
            warnings
        )
    }

    // ───────────────── Gibson / In-Fusion / NEBuilder（同源重叠组装） ─────────────────

    fun simulateGibsonAssembly(input: CloningInput): CloningOutput =
        simulateOverlapAssembly(input, input.overlapLength ?: 25, M_GIBSON)

    fun simulateInFusionCloning(input: CloningInput): CloningOutput =
        simulateOverlapAssembly(input, input.overlapLength ?: 15, M_INFUSION)

    fun simulateNEBuilderHiFi(input: CloningInput): CloningOutput =
        simulateOverlapAssembly(input, input.overlapLength ?: 20, M_NEBUILDER_HIFI)

    private fun simulateOverlapAssembly(input: CloningInput, overlapLen: Int, method: String): CloningOutput {
        val warnings = mutableListOf<String>()
        val vSeq = input.vector.sequence.uppercase()
        val iSeq = input.insert.sequence.uppercase()

        val vEndOverlap = vSeq.takeLast(overlapLen)
        val vStartOverlap = vSeq.take(overlapLen)

        val insertStartMatch = iSeq.startsWith(vEndOverlap)
        val insertEndMatch = iSeq.takeLast(overlapLen) == vStartOverlap ||
            Bio.revComp(iSeq.takeLast(overlapLen)) == vStartOverlap

        if (!insertStartMatch) {
            warnings += "Insert 5' end does not share ${overlapLen}bp homology with vector 3' end"
        }
        if (!insertEndMatch) {
            warnings += "Insert 3' end does not share ${overlapLen}bp homology with vector 5' start"
        }

        val vecBody = vSeq.substring(overlapLen, vSeq.length - overlapLen).ifEmpty { vSeq }
        var insertBody = iSeq
        if (insertStartMatch) insertBody = insertBody.substring(overlapLen)
        if (insertEndMatch) insertBody = insertBody.dropLast(overlapLen)

        val productSeq = vStartOverlap + vecBody + insertBody + vEndOverlap
        val productName = "${input.vector.name.ifEmpty { "Vector" }}_gibson_${input.insert.name.ifEmpty { "Insert" }}"
        val insertStart = vStartOverlap.length + vecBody.length
        val insertEnd = insertStart + insertBody.length - 1

        val features = input.vector.features.map { it } + SimFeature(
            id = System.currentTimeMillis(),
            name = input.insert.name.ifEmpty { "Insert" },
            type = "CDS",
            start = insertStart,
            end = insertEnd,
            forward = true
        )

        val product = tidyUp(input.vector, productSeq, features, productName, true)

        return CloningOutput(
            product, productName, insertStart, insertEnd,
            CloningHistoryNode(
                method, productName,
                input.vector.name.ifEmpty { "Vector" },
                input.insert.name.ifEmpty { "Insert" },
                nowIso(),
                mapOf("overlapLength" to overlapLen.toString(), "insertSize" to insertBody.length.toString())
            ),
            warnings
        )
    }

    // ───────────────── Golden Gate ─────────────────

    fun simulateGoldenGateAssembly(input: CloningInput): CloningOutput {
        val warnings = mutableListOf<String>()
        val enzymeName = input.goldenGateEnzyme ?: "BsaI"
        // main resolveEnzyme 同上：null 分支为死代码，site 空时经 0 切点走 Insufficient sites 错误路径
        val enzyme: EnzymesVe.VeEnzyme? = EnzymesVe.getEnzymeByName(enzymeName) ?: EnzymesVe.enzymeFromSite(enzymeName, "", 0)
        if (enzyme == null) {
            return makeError(input, "Unknown Type IIS enzyme: $enzymeName", warnings)
        }

        val vSeq = input.vector.sequence.uppercase()
        val iSeq = input.insert.sequence.uppercase()

        val vCuts = EnzymesVe.cutSequenceByRestrictionEnzyme(vSeq, input.vector.circular, enzyme)
        val iCuts = EnzymesVe.cutSequenceByRestrictionEnzyme(iSeq, input.insert.circular, enzyme)

        if (vCuts.size < 2) warnings += "Vector has ${vCuts.size} $enzymeName sites (need >= 2)"
        if (iCuts.size < 2) warnings += "Insert has ${iCuts.size} $enzymeName sites (need >= 2)"

        if (vCuts.size < 2 || iCuts.size < 2) {
            return makeError(input, "Insufficient $enzymeName sites", warnings)
        }

        val vCut1 = vCuts[0]
        val vCut2 = vCuts[1]
        val iCut1 = iCuts[0]
        val iCut2 = iCuts[1]

        val vStart = minOf(vCut1.topSnipPosition, vCut2.topSnipPosition)
        val vEnd = maxOf(vCut1.topSnipPosition, vCut2.topSnipPosition)
        val iStart = minOf(iCut1.topSnipPosition, iCut2.topSnipPosition)
        val iEnd = maxOf(iCut1.topSnipPosition, iCut2.topSnipPosition)

        val vecBackbone = vSeq.substring(0, vStart) + vSeq.substring(vEnd)
        val insertFrag = iSeq.substring(iStart, iEnd)

        val productSeq = vecBackbone.substring(0, vStart) + insertFrag + vecBackbone.substring(vStart)
        val productName = "${input.vector.name.ifEmpty { "Vector" }}_goldengate_${input.insert.name.ifEmpty { "Insert" }}"
        val insertStart = vStart
        val insertEnd = insertStart + insertFrag.length - 1

        val features = input.vector.features.map { it } + SimFeature(
            id = System.currentTimeMillis(),
            name = input.insert.name.ifEmpty { "Insert" },
            type = "CDS",
            start = insertStart,
            end = insertEnd,
            forward = true
        )

        val product = tidyUp(input.vector, productSeq, features, productName, input.vector.circular)

        return CloningOutput(
            product, productName, insertStart, insertEnd,
            CloningHistoryNode(
                M_GOLDEN_GATE, productName,
                input.vector.name.ifEmpty { "Vector" },
                input.insert.name.ifEmpty { "Insert" },
                nowIso(),
                mapOf("enzyme" to enzymeName, "insertSize" to insertFrag.length.toString())
            ),
            warnings
        )
    }

    // ───────────────── Gateway ─────────────────

    private const val ATT_B1 = "GCTTTTTTGTACAAAAAAGCAGGCT"
    private const val ATT_B2 = "ACCCACTTTGTACAAGAAAGCTGGG"
    private const val ATT_P1 = "AAAATAACGCGGCCCAGTC"
    private const val ATT_P2 = "TACCCACTTTGTACAAGAAAGCTG"

    fun simulateGatewayCloning(input: CloningInput): CloningOutput {
        val warnings = mutableListOf<String>()
        val vSeq = input.vector.sequence.uppercase()
        val iSeq = input.insert.sequence.uppercase()

        val vB1Idx = if (vSeq.indexOf(ATT_B1) >= 0) vSeq.indexOf(ATT_B1) else vSeq.indexOf(ATT_P1)
        val vB2Idx = if (vSeq.indexOf(ATT_B2) >= 0) vSeq.indexOf(ATT_B2) else vSeq.indexOf(ATT_P2)
        val iB1Idx = if (iSeq.indexOf(ATT_B1) >= 0) iSeq.indexOf(ATT_B1) else iSeq.indexOf(ATT_P1)
        val iB2Idx = if (iSeq.indexOf(ATT_B2) >= 0) iSeq.indexOf(ATT_B2) else iSeq.indexOf(ATT_P2)

        if (vB1Idx < 0) warnings += "attB1/attP1 not found in vector"
        if (vB2Idx < 0) warnings += "attB2/attP2 not found in vector"
        if (iB1Idx < 0) warnings += "attB1/attP1 not found in insert"
        if (iB2Idx < 0) warnings += "attB2/attP2 not found in insert"

        if (vB1Idx < 0 || vB2Idx < 0 || iB1Idx < 0 || iB2Idx < 0) {
            // 回退：attL 接头直接拼接
            val attL1 = "CAACTTGTATATAAAGTTG"
            val attL2 = "ACCCAGCTTTCTTGTACAAAGTGG"
            val productSeq = vSeq + attL1 + iSeq + attL2
            val productName = "${input.vector.name.ifEmpty { "Vector" }}_gateway_${input.insert.name.ifEmpty { "Insert" }}"
            val insertStart = vSeq.length + attL1.length
            val insertEnd = insertStart + iSeq.length - 1
            val features = input.vector.features.map { it } + SimFeature(
                id = System.currentTimeMillis(),
                name = input.insert.name.ifEmpty { "Insert" }, type = "CDS",
                start = insertStart, end = insertEnd, forward = true
            )
            val product = tidyUp(input.vector, productSeq, features, productName, input.vector.circular)
            return CloningOutput(
                product, productName, insertStart, insertEnd,
                CloningHistoryNode(
                    M_GATEWAY, productName,
                    input.vector.name.ifEmpty { "Vector" },
                    input.insert.name.ifEmpty { "Insert" },
                    nowIso(),
                    mapOf("bpClonase" to "true", "fallback" to "true")
                ),
                warnings
            )
        }

        val vStart = minOf(vB1Idx, vB2Idx)
        val vEnd = maxOf(vB1Idx, vB2Idx) + 25 // att 位点长度
        val iStart = minOf(iB1Idx, iB2Idx)
        val iEnd = maxOf(iB1Idx, iB2Idx)

        val vecLeft = vSeq.substring(0, vStart)
        val vecRight = vSeq.substring(vEnd)
        val insertFrag = iSeq.substring(iStart, iEnd)

        val productSeq = vecLeft + insertFrag + vecRight
        val productName = "${input.vector.name.ifEmpty { "Vector" }}_gateway_${input.insert.name.ifEmpty { "Insert" }}"
        val insertStart = vecLeft.length
        val insertEnd = insertStart + insertFrag.length - 1

        val features = input.vector.features.map { it } + SimFeature(
            id = System.currentTimeMillis(),
            name = input.insert.name.ifEmpty { "Insert" }, type = "CDS",
            start = insertStart, end = insertEnd, forward = true
        )
        val product = tidyUp(input.vector, productSeq, features, productName, input.vector.circular)

        return CloningOutput(
            product, productName, insertStart, insertEnd,
            CloningHistoryNode(
                M_GATEWAY, productName,
                input.vector.name.ifEmpty { "Vector" },
                input.insert.name.ifEmpty { "Insert" },
                nowIso(),
                mapOf("bpClonase" to "true")
            ),
            warnings
        )
    }

    // ───────────────── TOPO / TA / GC ─────────────────

    fun simulateTOPOCloning(input: CloningInput): CloningOutput {
        val warnings = mutableListOf<String>()
        val vSeq = input.vector.sequence.uppercase()
        val iSeq = input.insert.sequence.uppercase()
        val directional = input.directional ?: true

        if (directional && !iSeq.startsWith("CACC")) {
            warnings += "Directional TOPO requires 5' CACC overhang on insert"
        }

        val topoSite = if (directional) "GTGG" else "GTTT"
        var topoIdx = vSeq.indexOf(topoSite)
        if (topoIdx < 0) {
            topoIdx = vSeq.length
            warnings += "TOPO site ($topoSite) not found in vector, appending insert"
        }

        val productSeq = vSeq.substring(0, topoIdx) + iSeq + vSeq.substring(topoIdx)
        val productName = "${input.vector.name.ifEmpty { "Vector" }}_topo_${input.insert.name.ifEmpty { "Insert" }}"
        val insertStart = topoIdx
        val insertEnd = insertStart + iSeq.length - 1

        val features = input.vector.features.map { it } + SimFeature(
            id = System.currentTimeMillis(),
            name = input.insert.name.ifEmpty { "Insert" }, type = "CDS",
            start = insertStart, end = insertEnd, forward = true
        )
        val product = tidyUp(input.vector, productSeq, features, productName, input.vector.circular)

        return CloningOutput(
            product, productName, insertStart, insertEnd,
            CloningHistoryNode(
                if (directional) M_TOPO_DIRECTIONAL else M_TOPO, productName,
                input.vector.name.ifEmpty { "Vector" },
                input.insert.name.ifEmpty { "Insert" },
                nowIso(),
                mapOf("directional" to directional.toString(), "insertSize" to iSeq.length.toString())
            ),
            warnings
        )
    }

    fun simulateTACloning(input: CloningInput): CloningOutput {
        val warnings = mutableListOf<String>()
        val vSeq = input.vector.sequence.uppercase()
        val iSeq = input.insert.sequence.uppercase()

        val productSeq = vSeq + iSeq
        val productName = "${input.vector.name.ifEmpty { "Vector" }}_TA_${input.insert.name.ifEmpty { "Insert" }}"
        val insertStart = vSeq.length
        val insertEnd = insertStart + iSeq.length - 1

        val features = input.vector.features.map { it } + SimFeature(
            id = System.currentTimeMillis(),
            name = input.insert.name.ifEmpty { "Insert" }, type = "CDS",
            start = insertStart, end = insertEnd, forward = true
        )
        val product = tidyUp(input.vector, productSeq, features, productName, input.vector.circular)

        return CloningOutput(
            product, productName, insertStart, insertEnd,
            CloningHistoryNode(
                M_TA, productName,
                input.vector.name.ifEmpty { "Vector" },
                input.insert.name.ifEmpty { "Insert" },
                nowIso(),
                mapOf("insertSize" to iSeq.length.toString())
            ),
            warnings
        )
    }

    fun simulateGCCloning(input: CloningInput): CloningOutput =
        simulateTACloning(input.copy(method = M_GC))

    // ───────────────── 分发 / 错误输出（1:1） ─────────────────

    fun simulateCloning(input: CloningInput): CloningOutput = when (input.method) {
        M_RESTRICTION -> simulateRestrictionCloning(input)
        M_GIBSON -> simulateGibsonAssembly(input)
        M_GOLDEN_GATE -> simulateGoldenGateAssembly(input)
        M_INFUSION -> simulateInFusionCloning(input)
        M_NEBUILDER_HIFI -> simulateNEBuilderHiFi(input)
        M_GATEWAY -> simulateGatewayCloning(input)
        M_TOPO -> simulateTOPOCloning(input.copy(directional = false))
        M_TOPO_DIRECTIONAL -> simulateTOPOCloning(input.copy(directional = true))
        M_TA -> simulateTACloning(input)
        M_GC -> simulateGCCloning(input)
        else -> makeError(input, "Unknown method: ${input.method}", emptyList())
    }

    private fun makeError(input: CloningInput, msg: String, warnings: List<String>): CloningOutput {
        return CloningOutput(
            product = SimSequenceData(
                name = input.vector.name,
                sequence = input.vector.sequence,
                circular = input.vector.circular,
                features = input.vector.features
            ),
            productName = input.vector.name.ifEmpty { "Error" },
            insertStart = 0,
            insertEnd = 0,
            historyNode = CloningHistoryNode(
                input.method, "Error",
                input.vector.name.ifEmpty { "Vector" },
                input.insert.name.ifEmpty { "Insert" },
                nowIso(),
                mapOf("error" to msg)
            ),
            warnings = warnings + msg
        )
    }
}
