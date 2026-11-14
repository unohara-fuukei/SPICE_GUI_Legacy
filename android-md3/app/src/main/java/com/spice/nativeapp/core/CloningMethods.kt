package com.spice.nativeapp.core

/**
 * 克隆方法模拟，对应原版 genome/cloningMethods.ts：
 * 限制酶 / TA / TOPO / Gateway / In-Fusion / NEBuilder HiFi / 重叠延伸 PCR / GC 克隆。
 * 只做设计层面的可行性判定与产物构建，不虚构酶学效率。
 */
object CloningMethods {

    enum class Method(val label: String) {
        RESTRICTION("限制酶克隆"),
        TA("TA 克隆"),
        TOPO("TOPO 克隆"),
        GATEWAY("Gateway 重组"),
        INFUSION("In-Fusion"),
        NEBUILDER("NEBuilder HiFi"),
        OVERLAP_PCR("重叠延伸 PCR"),
        GC("GC 克隆（同源重组）")
    }

    data class Result(
        val method: Method,
        val productLength: Int,
        val product: String?,
        val requiredOverlap: Int,
        val steps: List<String>,
        val warnings: List<String>,
        val verified: Boolean
    )

    private const val ATT_B1 = "ACAAGTTTGTACAAAAAAGCAGGCT"
    private const val ATT_B2 = "ACCCAGCTTTCTTGTACAAAGTGGT"

    fun simulate(
        method: Method,
        vector: SeqRecord,
        insert: SeqRecord,
        enzyme1: String? = null,
        enzyme2: String? = null,
        overlapLength: Int? = null,
        directional: Boolean = true
    ): Result {
        val v = vector.sequence.uppercase()
        val ins = insert.sequence.uppercase()
        val steps = mutableListOf<String>()
        val warnings = mutableListOf<String>()

        val cut = when (method) {
            Method.RESTRICTION -> {
                val e1 = enzyme1 ?: "EcoRI"
                val e2 = enzyme2 ?: "BamHI"
                val enz1 = Enzymes.byName(e1)
                val enz2 = Enzymes.byName(e2)
                if (enz1 == null || enz2 == null) {
                    warnings += com.spice.nativeapp.core.I18n.tf("酶名无法识别：{v1} / {v2}", e1, e2)
                    0
                } else {
                    val c1 = Bio.cutSites(v, enz1, vector.circular).sorted()
                    val c2 = Bio.cutSites(v, enz2, vector.circular).sorted()
                    steps += com.spice.nativeapp.core.I18n.tf("双酶切：{v1} {v2} 个位点、{v3} {v4} 个位点", e1, c1.size, e2, c2.size)
                    if (c1.size != 1 || c2.size != 1) warnings += com.spice.nativeapp.core.I18n.t("建议选在载体上各只有 1 个位点的酶，否则需凝胶回收目标片段")
                    if (c1.isNotEmpty()) c1.first() - 1 else 0
                }
            }
            Method.TA -> {
                steps += com.spice.nativeapp.core.I18n.tf("Taq 产物 3′ 端加 A：插入片段末位 {v1}", ins.lastOrNull())
                if (ins.lastOrNull() != 'A') warnings += com.spice.nativeapp.core.I18n.t("插入片段末位不是 A，需补 A 尾或用 TA 专用引物")
                if (v.isEmpty()) 0 else v.length / 2
            }
            Method.TOPO -> {
                if (directional) {
                    steps += com.spice.nativeapp.core.I18n.t("方向性 TOPO：插入片段 5′ 需 CACC 突出端")
                    if (!ins.startsWith("CACC")) warnings += "插入片段缺少 5′ CACC，方向性克隆会失败"
                } else steps += com.spice.nativeapp.core.I18n.t("平端 TOPO：两端均可连接（无方向性）")
                v.length / 2
            }
            Method.GATEWAY -> {
                val b1 = v.indexOf(ATT_B1)
                val b2 = v.indexOf(ATT_B2)
                steps += "载体 attB1/attB2：${if (b1 >= 0) "找到" else "未找到"} / ${if (b2 >= 0) "找到" else "未找到"}"
                if (b1 < 0 || b2 < 0) warnings += com.spice.nativeapp.core.I18n.t("载体缺少 attB1 或 attB2，Gateway LR/BP 反应无法定向")
                if (b1 >= 0 && b2 > b1) warnings += com.spice.nativeapp.core.I18n.tf("载体上 attB1→attB2 之间 {v1} bp 将被插入片段替换", b2 - b1)
                if (b1 >= 0) b1 else 0
            }
            Method.INFUSION, Method.NEBUILDER, Method.GC -> {
                val need = overlapLength ?: when (method) {
                    Method.INFUSION -> 15
                    Method.NEBUILDER -> 20
                    else -> 25
                }
                val vHead = v.take(need)
                val vTail = v.takeLast(need)
                val iHead = ins.take(need)
                val iTail = ins.takeLast(need)
                steps += com.spice.nativeapp.core.I18n.tf("要求同源臂 ≥ {v1} bp", need)
                if (iHead != vTail) warnings += com.spice.nativeapp.core.I18n.tf("插入片段 5′ 端与载体 3′ 端不匹配（前 {v1} nt 应相同）", need)
                if (iTail != vHead) warnings += com.spice.nativeapp.core.I18n.t("插入片段 3′ 端与载体 5′ 端不匹配（应相同）")
                if (need < 15) warnings += com.spice.nativeapp.core.I18n.t("同源臂偏短（<15 bp），重组效率会下降")
                v.length / 2
            }
            Method.OVERLAP_PCR -> {
                steps += com.spice.nativeapp.core.I18n.t("重叠延伸 PCR：需要 2 条含 15–25 nt 重叠区的引物")
                if (ins.length < 60) warnings += com.spice.nativeapp.core.I18n.t("重叠区太靠近片段末端，建议插入片段 > 60 bp")
                v.length / 2
            }
        }

        val product = assemble(v, ins, cut)
        // 校验：方向、读框、内部终止
        val stops = countStops(product, frame = 1)
        if (stops > 0) warnings += com.spice.nativeapp.core.I18n.tf("产物 +1 框内有 {v1} 个终止密码子（若需表达请核对读框）", stops)
        if (product.length % 3 != 0) warnings += com.spice.nativeapp.core.I18n.tf("产物长度 {v1} bp 非 3 的整数倍（表达框需补齐）", product.length)
        steps += com.spice.nativeapp.core.I18n.tf("产物长度 {v1} bp（载体 {v2} + 插入 {v3}）", product.length, v.length, ins.length)
        val verified = warnings.none { it.contains("失败") || it.contains("缺少") || it.contains("不匹配") }
        return Result(method, product.length, product, overlapLength ?: when (method) {
            Method.INFUSION -> 15
            Method.NEBUILDER -> 20
            Method.GC -> 25
            else -> 0
        }, steps, warnings, verified)
    }

    /** 在 cut 位置切开载体并插入片段（环状载体按环化处理） */
    private fun assemble(vector: String, insert: String, cut: Int): String {
        if (vector.isEmpty()) return insert
        val pos = cut.coerceIn(0, vector.length)
        return vector.substring(0, pos) + insert + vector.substring(pos)
    }

    private fun countStops(seq: String, frame: Int): Int {
        val offset = ((frame - 1) % 3).coerceAtLeast(0)
        var n = 0
        var i = offset
        while (i + 3 <= seq.length) {
            if (seq.substring(i, i + 3) in setOf("TAA", "TAG", "TGA")) n++
            i += 3
        }
        return n
    }

    /** 对当前载体可用的克隆方法给出推荐（依载体特征） */
    fun recommend(vector: SeqRecord, insert: SeqRecord): List<Method> {
        val v = vector.sequence.uppercase()
        val list = mutableListOf<Method>()
        if (v.contains(ATT_B1) && v.contains(ATT_B2)) list += Method.GATEWAY
        if (!v.contains("GAATTC").not()) list += Method.RESTRICTION
        list += Method.NEBUILDER
        list += Method.INFUSION
        list += Method.TA
        list += Method.TOPO
        if (vector.circular) list += Method.GC
        list += Method.OVERLAP_PCR
        return list.distinct()
    }
}
