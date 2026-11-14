package com.spice.nativeapp.core

/**
 * T7 块7：main src/lib/genome/biophysics/synBio.ts 1:1 移植（合成生物学 CAD 模块 Feature G）。
 * 遗传线路逻辑门拓扑提取 / BioBrick RFC 10 合规 / MoClo 级别组装检查 /
 * 转化效率估算 / SBOL 2.0 RDF-XML 导出 / 质粒拷贝数与不相容群 / 离线生物安全与 CARD AMR 筛查。
 * 词条引用（zh 原文见 zh.json）：sbGateLacActive、sbGateCascade、sbMoclo 系、sbPuc 系、sbCole1 系、sbPbr322 系、sbPsc101 系、sbP15a 系、sbDefault 系、sbAgent 系。
 */
object SynBio {

    // ───────────────────────── GeneticCircuitGate（synBio.ts:10-62）─────────────────────────

    data class GeneticCircuitGate(
        val id: String,
        val gateType: String,          // AND/OR/NOT/NAND/NOR/XOR/INPUT
        val promoters: List<String>,
        val outputCdsName: String,
        val inputs: List<String>,
        val status: String
    )

    private data class FeatLite(val name: String, val type: String, val start: Int, val end: Int)

    /**
     * main analyzeGeneticCircuits：评估 promoter-CDS 组合识别逻辑门拓扑。
     * 注意 quirk：两条规则独立执行——lac 启动子 → TetR CDS 的 NOT 门与
     * 一般诱导系统 INPUT 门（<200bp 内最近 CDS）可能为同一 promoter 各自入列。
     */
    fun analyzeGeneticCircuits(features: List<GeneFeatureLite>): List<GeneticCircuitGate> {
        val sorted = features.sortedBy { it.start }
        val gates = mutableListOf<GeneticCircuitGate>()
        var gateIdx = 1

        for (i in 0 until sorted.size - 1) {
            val feat = sorted[i]

            // LacI repressor -> TetR logic
            if (feat.type == "promoter" && feat.name.lowercase().contains("lac")) {
                val nextCds = sorted.find { f -> f.type == "cds" && f.start > feat.end && f.name.lowercase().contains("tetr") }
                if (nextCds != null) {
                    gates += GeneticCircuitGate(
                        id = "gate_${gateIdx++}",
                        gateType = "NOT",
                        promoters = listOf(feat.name),
                        outputCdsName = nextCds.name,
                        inputs = listOf("IPTG"),
                        status = I18n.t("活跃 (Repressed by LacI, Inducible by IPTG)")
                    )
                }
            }

            // General Inducible systems
            if (feat.type == "promoter") {
                val nextCds = sorted.find { f -> f.type == "cds" && f.start > feat.end && f.start - feat.end < 200 }
                if (nextCds != null) {
                    gates += GeneticCircuitGate(
                        id = "gate_${gateIdx++}",
                        gateType = "INPUT",
                        promoters = listOf(feat.name),
                        outputCdsName = nextCds.name,
                        inputs = listOf("RNAPol"),
                        status = I18n.tf("基因级联表达元件 ({v1} ➔ {v2})", feat.name, nextCds.name)
                    )
                }
            }
        }
        return gates
    }

    // ───────────────────────── BioBrick RFC 10（synBio.ts:68-98）─────────────────────────

    data class BrickSite(val enzyme: String, val pos: Int, val sequence: String)

    data class BioBrickResult(
        val compatible: Boolean,
        val matchingSites: List<BrickSite>
    )

    /** main checkBioBrickCompatibility：EcoRI/XbaI/SpeI/PstI 内部位点扫描（清洗 [^ATCG] 后 indexOf 全列）。 */
    fun checkBioBrickCompatibility(dnaSeq: String): BioBrickResult {
        val seq = dnaSeq.uppercase().filter { it in "ATCG" }
        val biobricks = listOf(
            "EcoRI" to "GAATTC",
            "XbaI" to "TCTAGA",
            "SpeI" to "ACTAGA",
            "PstI" to "CTGCAG"
        )
        val matchingSites = mutableListOf<BrickSite>()
        for ((enzyme, pattern) in biobricks) {
            var pos = seq.indexOf(pattern)
            while (pos != -1) {
                matchingSites += BrickSite(enzyme, pos, pattern)
                pos = seq.indexOf(pattern, pos + 1)
            }
        }
        return BioBrickResult(matchingSites.isEmpty(), matchingSites)
    }

    // ───────────────────────── MoClo（synBio.ts:103-141）─────────────────────────

    data class MoCloResult(val compatible: Boolean, val level: String, val errors: List<String>)

    /** main verifyMoCloAssembly：Golden Gate MoClo 突出端标准组装校验（4bp 大写比对 + 5 种标准级别判定）。 */
    fun verifyMoCloAssembly(
        vectorLeftOverhang: String, vectorRightOverhang: String,
        insertLeftOverhang: String, insertRightOverhang: String
    ): MoCloResult {
        val vl = vectorLeftOverhang.uppercase()
        val vr = vectorRightOverhang.uppercase()
        val il = insertLeftOverhang.uppercase()
        val ir = insertRightOverhang.uppercase()

        val errors = mutableListOf<String>()
        if (vl != il) errors += I18n.tf("左侧融合突起不匹配！载体: {v1} | 插入片段: {v2}", vl, il)
        if (vr != ir) errors += I18n.tf("右侧融合突起不匹配！载体: {v1} | 插入片段: {v2}", vr, ir)

        var level = I18n.t("自定义 Golden Gate")
        if (vl == "GGAG" && vr == "CGCT") level = I18n.t("Alpha 级 (Tu 单元)")
        else if (vl == "GGAG" && vr == "AATG") level = I18n.t("Level 0 启动子")
        else if (vl == "AATG" && vr == "GCTT") level = I18n.t("Level 0 CDS 元件")
        else if (vl == "GCTT" && vr == "CGCT") level = I18n.t("Level 0 终止子")
        else if (vl == "TGCC" && vr == "GCAA") level = I18n.t("Beta 级 (多基因回路)")

        return MoCloResult(errors.isEmpty(), level, errors)
    }

    // ───────────────────────── 转化效率（synBio.ts:146-167）─────────────────────────

    data class TransformationEstimate(val efficiency: Double, val colonyEstimate: Double)

    /**
     * main estimateTransformationEfficiency：电转 1e9 / 化学 1e7 基准；
     * 连接产物 ×0.01；sizeFactor = exp(-0.25 × max(0, kb − 3))；colony = eff × 0.01。
     * JS Math.round 返回值经 Number 显示；Kotlin 侧保持 Double 数值语义一致。
     */
    fun estimateTransformationEfficiency(
        method: String,     // "chemical" | "electroporation"
        dnaSizeBp: Int,
        quality: String     // "supercoiled" | "ligation_product"
    ): TransformationEstimate {
        var baseEff = if (method == "electroporation") 1e9 else 1e7
        if (quality == "ligation_product") baseEff *= 0.01

        val sizeKb = dnaSizeBp / 1000.0
        val sizeFactor = Math.exp(-0.25 * maxOf(0.0, sizeKb - 3))
        val finalEfficiency = Math.round(baseEff * sizeFactor)
        val colonyEstimate = Math.round(finalEfficiency * 0.01)  // 10ng load
        return TransformationEstimate(finalEfficiency.toDouble(), colonyEstimate.toDouble())
    }

    // ───────────────────────── SBOL 2.0 导出（synBio.ts:172-238）─────────────────────────

    /**
     * main exportToSBOL：SBOL 2.0 RDF/XML 导出（ComponentDefinition + SequenceAnnotation × N + Sequence）。
     * 与 main 完全逐行一致（含 feature start+1/end+1 的 1-based 偏移与元素全小写序列）。
     */
    fun exportToSBOL(
        plasmidName: String,
        seq: String,
        features: List<GeneFeatureLite>
    ): String {
        val cleanName = plasmidName.replace(Regex("\\s+"), "_")
        val sbolArr = mutableListOf(
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>",
            "<rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\" xmlns:sbol=\"http://sbols.org/v2#\" xmlns:dcterms=\"http://purl.org/dc/terms/\">",
            "  <sbol:ComponentDefinition rdf:about=\"https://spice.sbol/ComponentDefinition/${cleanName}/1\">",
            "    <dcterms:title>${plasmidName}</dcterms:title>",
            "    <sbol:type rdf:resource=\"http://www.biopax.org/release/biopax-level3.owl#DnaRegion\"/>",
            "    <sbol:role rdf:resource=\"http://identifiers.org/so/SO:0000988\"/> <!-- circular plasmid -->",
            "    <sbol:sequence rdf:resource=\"https://spice.sbol/Sequence/${cleanName}_seq/1\"/>"
        )

        features.forEachIndexed { idx, feat ->
            val fName = feat.name.replace(Regex("\\s+"), "_")
            val fTypeUri = getSbolRoleUri(feat.type)
            sbolArr += listOf(
                "    <sbol:sequenceAnnotation>",
                "      <sbol:SequenceAnnotation rdf:about=\"https://spice.sbol/ComponentDefinition/${cleanName}/annotation_${idx}/1\">",
                "        <dcterms:title>${feat.name}</dcterms:title>",
                "        <sbol:location>",
                "          <sbol:Range rdf:about=\"https://spice.sbol/ComponentDefinition/${cleanName}/annotation_${idx}/range/1\">",
                "            <sbol:start>${feat.start + 1}</sbol:start>",
                "            <sbol:end>${feat.end + 1}</sbol:end>",
                "            <sbol:orientation rdf:resource=\"http://sbols.org/v2#inline\"/>",
                "          </sbol:Range>",
                "        </sbol:location>",
                "        <sbol:component>",
                "          <sbol:Component rdf:about=\"https://spice.sbol/ComponentDefinition/${cleanName}/sub_component_${idx}/1\">",
                "            <sbol:definition>",
                "              <sbol:ComponentDefinition rdf:about=\"https://spice.sbol/ComponentDefinition/${fName}/1\">",
                "                <dcterms:title>${feat.name}</dcterms:title>",
                "                <sbol:type rdf:resource=\"http://www.biopax.org/release/biopax-level3.owl#DnaRegion\"/>",
                "                <sbol:role rdf:resource=\"${fTypeUri}\"/>",
                "              </sbol:ComponentDefinition>",
                "            </sbol:definition>",
                "          </sbol:Component>",
                "        </sbol:component>",
                "      </sbol:SequenceAnnotation>",
                "    </sbol:sequenceAnnotation>"
            )
        }

        sbolArr += listOf(
            "  </sbol:ComponentDefinition>",
            "  <sbol:Sequence rdf:about=\"https://spice.sbol/Sequence/${cleanName}_seq/1\">",
            "    <sbol:elements>${seq.lowercase()}</sbol:elements>",
            "    <sbol:encoding rdf:resource=\"http://www.chem.qmul.ac.uk/iupac/AminoAcid/\"/>",
            "  </sbol:Sequence>",
            "</rdf:RDF>"
        )
        return sbolArr.joinToString("\n")
    }

    private fun getSbolRoleUri(type: String): String {
        val t = type.lowercase()
        if (t == "promoter") return "http://identifiers.org/so/SO:0000167"
        if (t == "cds") return "http://identifiers.org/so/SO:0000316"
        if (t == "terminator") return "http://identifiers.org/so/SO:0000141"
        if (t == "ori") return "http://identifiers.org/so/SO:0000296"
        return "http://identifiers.org/so/SO:0000110"
    }

    // ───────────────────────── 拷贝数与不相容群（synBio.ts:253-307）─────────────────────────

    data class CopyNumberEstimate(
        val oriName: String,
        val copyNumberRange: String,
        val expressionProfile: String,
        val incompatibilityGroup: String
    )

    /**
     * main estimateCopyNumberAndIncompatibility：扫描特征列表识别复制子。
     * quirk：匹配条件是 fType=="ori" || fName 含 "ori"/"origin"——name 含 ori 的非 ori 类型特征也会命中。
     */
    fun estimateCopyNumberAndIncompatibility(features: List<GeneFeatureLite>): CopyNumberEstimate {
        for (f in features) {
            val fName = f.name.lowercase()
            val fType = f.type.lowercase()
            if (fType == "ori" || fName.contains("ori") || fName.contains("origin")) {
                if (fName.contains("puc")) {
                    return CopyNumberEstimate(
                        "pUC Origin (Mutation in RNA II)",
                        I18n.t("极高拷贝 (500-700 copies/cell)"),
                        I18n.t("极强：适用于重组蛋白超表达、质粒大量提取及报告基因转录检测。"),
                        I18n.t("ColE1 不相容群 (与 pcDNA3.1 / pET 系列质粒不相容)")
                    )
                } else if (fName.contains("pmb1") || fName.contains("cole1")) {
                    return CopyNumberEstimate(
                        "ColE1 / pMB1 Origin",
                        I18n.t("中高拷贝 (30-40 copies/cell)"),
                        I18n.t("中强：经典真核/原核表达载体（如 pcDNA3.1 / pET28a），表达量与细胞毒性平衡极佳。"),
                        I18n.t("ColE1 不相容群 (无法与 pUC 系列质粒在同一 E. coli 细胞中稳定共表达)")
                    )
                } else if (fName.contains("pbr322")) {
                    return CopyNumberEstimate(
                        "pBR322 Origin (Rop-regulated ColE1)",
                        I18n.t("中低拷贝 (15-20 copies/cell)"),
                        I18n.t("中等：适合表达有轻微细胞毒性的异源靶标或构建低背景杂交株。"),
                        I18n.t("ColE1 / pMB1 不相容群")
                    )
                } else if (fName.contains("psc101")) {
                    return CopyNumberEstimate(
                        "pSC101 Origin (RepA-mediated partition)",
                        I18n.t("极低拷贝 (~5 copies/cell)"),
                        I18n.t("极弱：严紧型低拷贝质粒，最适于需要极低背景泄露表达的合成遗传毒性回路（Toxicity circuits）。"),
                        I18n.t("pSC101 不相容群")
                    )
                } else if (fName.contains("p15a")) {
                    return CopyNumberEstimate(
                        "p15A Origin",
                        I18n.t("低拷贝 (10-15 copies/cell)"),
                        I18n.t("中等：经典的第二共表达质粒复制子（如 pACYC 系列），适合双质粒共表达体系。"),
                        I18n.t("p15A 不相容群 (与 ColE1/pUC 100% 兼容，可在同细胞内稳定并存)")
                    )
                }
            }
        }
        // Default fallback if no recognized ori
        return CopyNumberEstimate(
            I18n.t("ColE1 / pMB1 类似复制子 (默认推定)"),
            I18n.t("中高拷贝 (30-40 copies/cell)"),
            I18n.t("标准表达谱：适合大部分克隆工作流。"),
            I18n.t("ColE1 不相容群")
        )
    }

    // ───────────────────────── 生物安全筛查（synBio.ts:327-377）─────────────────────────

    data class BiosecurityAgent(val agent: String, val pattern: String, val description: String)

    data class BiosecurityAuditResult(
        val passed: Boolean,
        val securityLevel: String,   // LOW / HIGH_RISK
        val flags: List<BiosecurityFlag>
    )

    data class BiosecurityFlag(val agent: String, val matches: String, val description: String)

    /** main auditBiosecurityScreening：离线高危管制病原序列特征库（4 agent）扫描；database 非空时覆盖默认库。 */
    fun auditBiosecurityScreening(dnaSeq: String, database: List<BiosecurityAgent>? = null): BiosecurityAuditResult {
        val seq = dnaSeq.uppercase().filter { it in "ATCG" }
        val flags = mutableListOf<BiosecurityFlag>()

        val safetyBlackList = listOf(
            BiosecurityAgent(
                I18n.t("蓖麻毒素 (Ricin A-Chain Activity Center)"),
                "TCTGGAGCGCATGATT",
                I18n.t("受控毒素 (Ricin A-chain rRNA N-glycosidase active site)。具有极其致命的核糖体灭活细胞毒性。")
            ),
            BiosecurityAgent(
                I18n.t("肉毒毒素 (Botulinum Neurotoxin Core)"),
                "TTTGGATCCGGATAC",
                I18n.t("受控毒素 (Botulinum neurotoxin light chain zinc-metalloprotease active loop)。抑制乙酰胆碱释放，导致致死性肌肉麻痹。")
            ),
            BiosecurityAgent(
                I18n.t("炭疽毒素致死因子 (Anthrax Lethal Factor)"),
                "ATGAAACACGAAAA",
                I18n.t("一类致病因 (Bacillus anthracis lethal factor zinc protease domain)。降解 MAPKK，造成急性细胞凋亡。")
            ),
            BiosecurityAgent(
                I18n.t("天花病毒血凝素 (Variola Major Envelope)"),
                "TATAATGAGTCACA",
                I18n.t("国家一类管制烈性病毒 (Poxvirus Variola hemagglutinin envelope gene segment)。高致死性呼吸道及全身传染病。")
            )
        )

        val activeList = if (!database.isNullOrEmpty()) database else safetyBlackList
        for (b in activeList) {
            if (seq.contains(b.pattern)) {
                flags += BiosecurityFlag(b.agent, b.pattern, b.description)
            }
        }
        val passed = flags.isEmpty()
        return BiosecurityAuditResult(passed, if (passed) "LOW" else "HIGH_RISK", flags)
    }

    // ───────────────────────── CARD AMR 筛查（synBio.ts:395-426）─────────────────────────

    data class CardAmrAgent(val gene: String, val resistanceTo: String, val pattern: String)

    data class CardAmrFlag(val gene: String, val resistanceTo: String, val matches: String)

    data class CardAmrAuditResult(val passed: Boolean, val flags: List<CardAmrFlag>)

    /** main auditCardAmrScreening：CARD 抗生素耐药基因特征库（5 基因）扫描；pattern 大写后 contains。 */
    fun auditCardAmrScreening(dnaSeq: String, database: List<CardAmrAgent>? = null): CardAmrAuditResult {
        val seq = dnaSeq.uppercase().filter { it in "ATCG" }
        val flags = mutableListOf<CardAmrFlag>()

        val defaultAmrList = listOf(
            CardAmrAgent("blaTEM-1", "Beta-lactam (Penicillins)", "ATCAGTCAACC"),
            CardAmrAgent("kanR (aph(3')-Ia)", "Aminoglycoside (Kanamycin)", "ATGAGCCATATTCAACGGG"),
            CardAmrAgent("tet(A)", "Tetracycline", "GTGATCCTGGG"),
            CardAmrAgent("catA1 (CmR)", "Phenicol (Chloramphenicol)", "ATGGAGAAAAAAATCACT"),
            CardAmrAgent("aac(6')-Ib-cr", "Aminoglycoside, Fluoroquinolone", "CCGCTCGTGT")
        )

        val activeList = if (!database.isNullOrEmpty()) database else defaultAmrList
        for (b in activeList) {
            if (seq.contains(b.pattern.uppercase())) {
                flags += CardAmrFlag(b.gene, b.resistanceTo, b.pattern)
            }
        }
        return CardAmrAuditResult(flags.isEmpty(), flags)
    }
}

/** 面板层特征轻量投影（main 面板传 geneFeatures: {name,type,start,end}[]） */
data class GeneFeatureLite(val name: String, val type: String, val start: Int, val end: Int)
