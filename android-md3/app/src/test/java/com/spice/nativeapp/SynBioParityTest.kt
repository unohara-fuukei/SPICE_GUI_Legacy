package com.spice.nativeapp

import com.spice.nativeapp.core.GeneFeatureLite
import com.spice.nativeapp.core.SynBio
import org.junit.Assert.*
import org.junit.Test

/**
 * T7 块7 parity：对照 /tmp/sb_ref.json（main src/lib/genome/biophysics/synBio.ts 经 npx tsx 直跑）。
 * 覆盖：BioBrick RFC 10 / MoClo 7 组合 / 转化效率 5 组 / 遗传线路（含 pLac NOT+INPUT 双门 quirk）/
 * 拷贝数 8 组（含 coriA 名含 ori 落默认分支 quirk）/ 生物安全 4 组 / CARD AMR 4 组 / SBOL 全文比对。
 */
class SynBioParityTest {

    private fun feat(name: String, type: String, start: Int, end: Int) = GeneFeatureLite(name, type, start, end)

    @Test
    fun synBioParity() {
        // brick1（EcoRI/XbaI/SpeI/PstI 全命中）
        run {
            val res = SynBio.checkBioBrickCompatibility("GAATTCTCTAGAACTAGACTGCAGTT")
            assertEquals(false, res.compatible)
            assertEquals(4, res.matchingSites.size)
            assertEquals("EcoRI", res.matchingSites[0].enzyme)
            assertEquals(0, res.matchingSites[0].pos)
            assertEquals("GAATTC", res.matchingSites[0].sequence)
            assertEquals("XbaI", res.matchingSites[1].enzyme)
            assertEquals(6, res.matchingSites[1].pos)
            assertEquals("TCTAGA", res.matchingSites[1].sequence)
            assertEquals("SpeI", res.matchingSites[2].enzyme)
            assertEquals(12, res.matchingSites[2].pos)
            assertEquals("ACTAGA", res.matchingSites[2].sequence)
            assertEquals("PstI", res.matchingSites[3].enzyme)
            assertEquals(18, res.matchingSites[3].pos)
            assertEquals("CTGCAG", res.matchingSites[3].sequence)
        }
        // brick2（EcoRI/XbaI/SpeI/PstI 无命中）
        run {
            val res = SynBio.checkBioBrickCompatibility("ATGCGTAAACCCGGGTTTAAACCCGGGTTT")
            assertEquals(true, res.compatible)
            assertEquals(0, res.matchingSites.size)
        }
        // brick3（EcoRI/XbaI/SpeI/PstI 小写+重复 EcoRI）
        run {
            val res = SynBio.checkBioBrickCompatibility("gaattcNNgaattc")
            assertEquals(false, res.compatible)
            assertEquals(2, res.matchingSites.size)
            assertEquals("EcoRI", res.matchingSites[0].enzyme)
            assertEquals(0, res.matchingSites[0].pos)
            assertEquals("GAATTC", res.matchingSites[0].sequence)
            assertEquals("EcoRI", res.matchingSites[1].enzyme)
            assertEquals(6, res.matchingSites[1].pos)
            assertEquals("GAATTC", res.matchingSites[1].sequence)
        }

        // MoClo 7 组合（标准级别判定 + 左右突起失配 + 小写归一）
        run { // moclo[0]
            val res = SynBio.verifyMoCloAssembly("GGAG","CGCT","GGAG","CGCT")
            assertEquals(true, res.compatible)
            assertEquals("Alpha 级 (Tu 单元)", res.level)
            assertEquals(0, res.errors.size)
        }
        run { // moclo[1]
            val res = SynBio.verifyMoCloAssembly("GGAG","CGCT","GGAG","AATG")
            assertEquals(false, res.compatible)
            assertEquals("Alpha 级 (Tu 单元)", res.level)
            assertEquals(1, res.errors.size)
            assertEquals("右侧融合突起不匹配！载体: CGCT | 插入片段: AATG", res.errors[0])
        }
        run { // moclo[2]
            val res = SynBio.verifyMoCloAssembly("GGAG","AATG","ggag","aatg")
            assertEquals(true, res.compatible)
            assertEquals("Level 0 启动子", res.level)
            assertEquals(0, res.errors.size)
        }
        run { // moclo[3]
            val res = SynBio.verifyMoCloAssembly("AATG","GCTT","AATG","GCTT")
            assertEquals(true, res.compatible)
            assertEquals("Level 0 CDS 元件", res.level)
            assertEquals(0, res.errors.size)
        }
        run { // moclo[4]
            val res = SynBio.verifyMoCloAssembly("GCTT","CGCT","GCTT","CGCT")
            assertEquals(true, res.compatible)
            assertEquals("Level 0 终止子", res.level)
            assertEquals(0, res.errors.size)
        }
        run { // moclo[5]
            val res = SynBio.verifyMoCloAssembly("TGCC","GCAA","TGCC","GCAA")
            assertEquals(true, res.compatible)
            assertEquals("Beta 级 (多基因回路)", res.level)
            assertEquals(0, res.errors.size)
        }
        run { // moclo[6]
            val res = SynBio.verifyMoCloAssembly("AAAA","CCCC","TTTT","GGGG")
            assertEquals(false, res.compatible)
            assertEquals("自定义 Golden Gate", res.level)
            assertEquals(2, res.errors.size)
            assertEquals("左侧融合突起不匹配！载体: AAAA | 插入片段: TTTT", res.errors[0])
            assertEquals("右侧融合突起不匹配！载体: CCCC | 插入片段: GGGG", res.errors[1])
        }

        // 转化效率 5 组（exp(-0.25×max(0,kb−3)) 尺寸因子）
        run { // trans[0]
            val res = SynBio.estimateTransformationEfficiency("chemical", 3000, "supercoiled")
            assertEquals(10000000.0, res.efficiency, 1e-6)
            assertEquals(100000.0, res.colonyEstimate, 1e-6)
        }
        run { // trans[1]
            val res = SynBio.estimateTransformationEfficiency("chemical", 8000, "ligation_product")
            assertEquals(28650.0, res.efficiency, 1e-6)
            assertEquals(287.0, res.colonyEstimate, 1e-6)
        }
        run { // trans[2]
            val res = SynBio.estimateTransformationEfficiency("electroporation", 8000, "supercoiled")
            assertEquals(286504797.0, res.efficiency, 1e-6)
            assertEquals(2865048.0, res.colonyEstimate, 1e-6)
        }
        run { // trans[3]
            val res = SynBio.estimateTransformationEfficiency("electroporation", 12000, "ligation_product")
            assertEquals(1053992.0, res.efficiency, 1e-6)
            assertEquals(10540.0, res.colonyEstimate, 1e-6)
        }
        run { // trans[4]
            val res = SynBio.estimateTransformationEfficiency("chemical", 12000, "supercoiled")
            assertEquals(1053992.0, res.efficiency, 1e-6)
            assertEquals(10540.0, res.colonyEstimate, 1e-6)
        }

        // 遗传线路：pLac→TetR NOT 门 + INPUT 门（同 promoter 双规则 quirk 各自入列）
        run {
            val gates = SynBio.analyzeGeneticCircuits(listOf(
                feat("pLac", "promoter", 0, 50), feat("TetR", "cds", 100, 400),
                feat("GFP", "cds", 1000, 1700), feat("T1", "terminator", 1800, 1850)
            ))
            assertEquals(2, gates.size)
            assertEquals("gate_1", gates[0].id)
            assertEquals("NOT", gates[0].gateType)
            assertEquals("pLac", gates[0].promoters[0])
            assertEquals("TetR", gates[0].outputCdsName)
            assertEquals("IPTG", gates[0].inputs[0])
            assertEquals("活跃 (Repressed by LacI, Inducible by IPTG)", gates[0].status)
            assertEquals("gate_2", gates[1].id)
            assertEquals("INPUT", gates[1].gateType)
            assertEquals("pLac", gates[1].promoters[0])
            assertEquals("TetR", gates[1].outputCdsName)
            assertEquals("RNAPol", gates[1].inputs[0])
            assertEquals("基因级联表达元件 (pLac ➔ TetR)", gates[1].status)
        }
        // pTac→mCherry 距离 460bp > 200：无门
        run {
            val gates = SynBio.analyzeGeneticCircuits(listOf(
                feat("pTac", "promoter", 0, 40), feat("mCherry", "cds", 500, 1100)
            ))
            assertEquals(0, gates.size)
        }
        // 空特征
        assertEquals(0, SynBio.analyzeGeneticCircuits(emptyList()).size)

        // 拷贝数与不相容群 8 组（含 coriA 名含 ori 但无子匹配 → 默认分支 quirk）
        run { // copynum[0]
            val res = SynBio.estimateCopyNumberAndIncompatibility(listOf(feat("pUC ori", "ori", 0, 100)))
            assertEquals("pUC Origin (Mutation in RNA II)", res.oriName)
            assertEquals("极高拷贝 (500-700 copies/cell)", res.copyNumberRange)
            assertEquals("极强：适用于重组蛋白超表达、质粒大量提取及报告基因转录检测。", res.expressionProfile)
            assertEquals("ColE1 不相容群 (与 pcDNA3.1 / pET 系列质粒不相容)", res.incompatibilityGroup)
        }
        run { // copynum[1]
            val res = SynBio.estimateCopyNumberAndIncompatibility(listOf(feat("ColE1 origin", "ori", 0, 100)))
            assertEquals("ColE1 / pMB1 Origin", res.oriName)
            assertEquals("中高拷贝 (30-40 copies/cell)", res.copyNumberRange)
            assertEquals("中强：经典真核/原核表达载体（如 pcDNA3.1 / pET28a），表达量与细胞毒性平衡极佳。", res.expressionProfile)
            assertEquals("ColE1 不相容群 (无法与 pUC 系列质粒在同一 E. coli 细胞中稳定共表达)", res.incompatibilityGroup)
        }
        run { // copynum[2]
            val res = SynBio.estimateCopyNumberAndIncompatibility(listOf(feat("pBR322", "ori", 0, 100)))
            assertEquals("pBR322 Origin (Rop-regulated ColE1)", res.oriName)
            assertEquals("中低拷贝 (15-20 copies/cell)", res.copyNumberRange)
            assertEquals("中等：适合表达有轻微细胞毒性的异源靶标或构建低背景杂交株。", res.expressionProfile)
            assertEquals("ColE1 / pMB1 不相容群", res.incompatibilityGroup)
        }
        run { // copynum[3]
            val res = SynBio.estimateCopyNumberAndIncompatibility(listOf(feat("pSC101 ori", "ori", 0, 100)))
            assertEquals("pSC101 Origin (RepA-mediated partition)", res.oriName)
            assertEquals("极低拷贝 (~5 copies/cell)", res.copyNumberRange)
            assertEquals("极弱：严紧型低拷贝质粒，最适于需要极低背景泄露表达的合成遗传毒性回路（Toxicity circuits）。", res.expressionProfile)
            assertEquals("pSC101 不相容群", res.incompatibilityGroup)
        }
        run { // copynum[4]
            val res = SynBio.estimateCopyNumberAndIncompatibility(listOf(feat("p15A ori", "ori", 0, 100)))
            assertEquals("p15A Origin", res.oriName)
            assertEquals("低拷贝 (10-15 copies/cell)", res.copyNumberRange)
            assertEquals("中等：经典的第二共表达质粒复制子（如 pACYC 系列），适合双质粒共表达体系。", res.expressionProfile)
            assertEquals("p15A 不相容群 (与 ColE1/pUC 100% 兼容，可在同细胞内稳定并存)", res.incompatibilityGroup)
        }
        run { // copynum[5]
            val res = SynBio.estimateCopyNumberAndIncompatibility(listOf(feat("KanR", "cds", 0, 100)))
            assertEquals("ColE1 / pMB1 类似复制子 (默认推定)", res.oriName)
            assertEquals("中高拷贝 (30-40 copies/cell)", res.copyNumberRange)
            assertEquals("标准表达谱：适合大部分克隆工作流。", res.expressionProfile)
            assertEquals("ColE1 不相容群", res.incompatibilityGroup)
        }
        run { // copynum[6]
            val res = SynBio.estimateCopyNumberAndIncompatibility(emptyList())
            assertEquals("ColE1 / pMB1 类似复制子 (默认推定)", res.oriName)
            assertEquals("中高拷贝 (30-40 copies/cell)", res.copyNumberRange)
            assertEquals("标准表达谱：适合大部分克隆工作流。", res.expressionProfile)
            assertEquals("ColE1 不相容群", res.incompatibilityGroup)
        }
        run { // copynum[7]
            val res = SynBio.estimateCopyNumberAndIncompatibility(listOf(feat("coriA marker", "cds", 0, 100)))
            assertEquals("ColE1 / pMB1 类似复制子 (默认推定)", res.oriName)
            assertEquals("中高拷贝 (30-40 copies/cell)", res.copyNumberRange)
            assertEquals("标准表达谱：适合大部分克隆工作流。", res.expressionProfile)
            assertEquals("ColE1 不相容群", res.incompatibilityGroup)
        }

        // 生物安全筛查 4 组（蓖麻/肉毒+炭疽+天花三连/干净/自定义库覆盖）
        run { // biosec[0]
            val res = SynBio.auditBiosecurityScreening("AAAATCTGGAGCGCATGATTTTTGGGG", null)
            assertEquals(false, res.passed)
            assertEquals("HIGH_RISK", res.securityLevel)
            assertEquals(1, res.flags.size)
            assertEquals("蓖麻毒素 (Ricin A-Chain Activity Center)", res.flags[0].agent)
            assertEquals("TCTGGAGCGCATGATT", res.flags[0].matches)
            assertEquals("受控毒素 (Ricin A-chain rRNA N-glycosidase active site)。具有极其致命的核糖体灭活细胞毒性。", res.flags[0].description)
        }
        run { // biosec[1]
            val res = SynBio.auditBiosecurityScreening("TTTGGATCCGGATAC" + "ATGAAACACGAAAA" + "TATAATGAGTCACA", null)
            assertEquals(false, res.passed)
            assertEquals("HIGH_RISK", res.securityLevel)
            assertEquals(3, res.flags.size)
            assertEquals("肉毒毒素 (Botulinum Neurotoxin Core)", res.flags[0].agent)
            assertEquals("TTTGGATCCGGATAC", res.flags[0].matches)
            assertEquals("受控毒素 (Botulinum neurotoxin light chain zinc-metalloprotease active loop)。抑制乙酰胆碱释放，导致致死性肌肉麻痹。", res.flags[0].description)
            assertEquals("炭疽毒素致死因子 (Anthrax Lethal Factor)", res.flags[1].agent)
            assertEquals("ATGAAACACGAAAA", res.flags[1].matches)
            assertEquals("一类致病因 (Bacillus anthracis lethal factor zinc protease domain)。降解 MAPKK，造成急性细胞凋亡。", res.flags[1].description)
            assertEquals("天花病毒血凝素 (Variola Major Envelope)", res.flags[2].agent)
            assertEquals("TATAATGAGTCACA", res.flags[2].matches)
            assertEquals("国家一类管制烈性病毒 (Poxvirus Variola hemagglutinin envelope gene segment)。高致死性呼吸道及全身传染病。", res.flags[2].description)
        }
        run { // biosec[2]
            val res = SynBio.auditBiosecurityScreening("ATGCGTAAACCCGGGTTT", null)
            assertEquals(true, res.passed)
            assertEquals("LOW", res.securityLevel)
            assertEquals(0, res.flags.size)
        }
        run { // biosec[3]
            val res = SynBio.auditBiosecurityScreening("CCCCCCAAAA", listOf(SynBio.BiosecurityAgent("TestAgent", "CCCAAA", "test desc")))
            assertEquals(false, res.passed)
            assertEquals("HIGH_RISK", res.securityLevel)
            assertEquals(1, res.flags.size)
            assertEquals("TestAgent", res.flags[0].agent)
            assertEquals("CCCAAA", res.flags[0].matches)
            assertEquals("test desc", res.flags[0].description)
        }
        // CARD AMR 筛查 4 组（blaTEM+kanR 双命中/单命中/干净/空库→默认库 quirk）
        run { // amr[0]
            val res = SynBio.auditCardAmrScreening("ATCAGTCAACCGGGATGAGCCATATTCAACGGG", null)
            assertEquals(false, res.passed)
            assertEquals(2, res.flags.size)
            assertEquals("blaTEM-1", res.flags[0].gene)
            assertEquals("Beta-lactam (Penicillins)", res.flags[0].resistanceTo)
            assertEquals("ATCAGTCAACC", res.flags[0].matches)
            assertEquals("kanR (aph(3')-Ia)", res.flags[1].gene)
            assertEquals("Aminoglycoside (Kanamycin)", res.flags[1].resistanceTo)
            assertEquals("ATGAGCCATATTCAACGGG", res.flags[1].matches)
        }
        run { // amr[1]
            val res = SynBio.auditCardAmrScreening("GTGATCCTGGG", null)
            assertEquals(false, res.passed)
            assertEquals(1, res.flags.size)
            assertEquals("tet(A)", res.flags[0].gene)
            assertEquals("Tetracycline", res.flags[0].resistanceTo)
            assertEquals("GTGATCCTGGG", res.flags[0].matches)
        }
        run { // amr[2]
            val res = SynBio.auditCardAmrScreening("ATGCGTAAACCCGGG", null)
            assertEquals(true, res.passed)
            assertEquals(0, res.flags.size)
        }
        run { // amr[3]
            val res = SynBio.auditCardAmrScreening("ttaggatcc", emptyList())
            assertEquals(true, res.passed)
            assertEquals(0, res.flags.size)
        }
        // SBOL 2.0 全文比对（含空格名替换、start+1 偏移、序列小写）
        run {
            val xml = SynBio.exportToSBOL(
                "pTest 1", "ATGCGTAAATAA", listOf(
                    feat("pLac promoter", "promoter", 0, 30),
                    feat("GFP", "cds", 31, 730),
                    feat("T1", "terminator", 731, 780),
                    feat("ori", "ori", 800, 1400),
                    feat("misc binding", "other", 1500, 1600)
                )
            )
            assertEquals("""<?xml version="1.0" encoding="UTF-8"?>
<rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#" xmlns:sbol="http://sbols.org/v2#" xmlns:dcterms="http://purl.org/dc/terms/">
  <sbol:ComponentDefinition rdf:about="https://spice.sbol/ComponentDefinition/pTest_1/1">
    <dcterms:title>pTest 1</dcterms:title>
    <sbol:type rdf:resource="http://www.biopax.org/release/biopax-level3.owl#DnaRegion"/>
    <sbol:role rdf:resource="http://identifiers.org/so/SO:0000988"/> <!-- circular plasmid -->
    <sbol:sequence rdf:resource="https://spice.sbol/Sequence/pTest_1_seq/1"/>
    <sbol:sequenceAnnotation>
      <sbol:SequenceAnnotation rdf:about="https://spice.sbol/ComponentDefinition/pTest_1/annotation_0/1">
        <dcterms:title>pLac promoter</dcterms:title>
        <sbol:location>
          <sbol:Range rdf:about="https://spice.sbol/ComponentDefinition/pTest_1/annotation_0/range/1">
            <sbol:start>1</sbol:start>
            <sbol:end>31</sbol:end>
            <sbol:orientation rdf:resource="http://sbols.org/v2#inline"/>
          </sbol:Range>
        </sbol:location>
        <sbol:component>
          <sbol:Component rdf:about="https://spice.sbol/ComponentDefinition/pTest_1/sub_component_0/1">
            <sbol:definition>
              <sbol:ComponentDefinition rdf:about="https://spice.sbol/ComponentDefinition/pLac_promoter/1">
                <dcterms:title>pLac promoter</dcterms:title>
                <sbol:type rdf:resource="http://www.biopax.org/release/biopax-level3.owl#DnaRegion"/>
                <sbol:role rdf:resource="http://identifiers.org/so/SO:0000167"/>
              </sbol:ComponentDefinition>
            </sbol:definition>
          </sbol:Component>
        </sbol:component>
      </sbol:SequenceAnnotation>
    </sbol:sequenceAnnotation>
    <sbol:sequenceAnnotation>
      <sbol:SequenceAnnotation rdf:about="https://spice.sbol/ComponentDefinition/pTest_1/annotation_1/1">
        <dcterms:title>GFP</dcterms:title>
        <sbol:location>
          <sbol:Range rdf:about="https://spice.sbol/ComponentDefinition/pTest_1/annotation_1/range/1">
            <sbol:start>32</sbol:start>
            <sbol:end>731</sbol:end>
            <sbol:orientation rdf:resource="http://sbols.org/v2#inline"/>
          </sbol:Range>
        </sbol:location>
        <sbol:component>
          <sbol:Component rdf:about="https://spice.sbol/ComponentDefinition/pTest_1/sub_component_1/1">
            <sbol:definition>
              <sbol:ComponentDefinition rdf:about="https://spice.sbol/ComponentDefinition/GFP/1">
                <dcterms:title>GFP</dcterms:title>
                <sbol:type rdf:resource="http://www.biopax.org/release/biopax-level3.owl#DnaRegion"/>
                <sbol:role rdf:resource="http://identifiers.org/so/SO:0000316"/>
              </sbol:ComponentDefinition>
            </sbol:definition>
          </sbol:Component>
        </sbol:component>
      </sbol:SequenceAnnotation>
    </sbol:sequenceAnnotation>
    <sbol:sequenceAnnotation>
      <sbol:SequenceAnnotation rdf:about="https://spice.sbol/ComponentDefinition/pTest_1/annotation_2/1">
        <dcterms:title>T1</dcterms:title>
        <sbol:location>
          <sbol:Range rdf:about="https://spice.sbol/ComponentDefinition/pTest_1/annotation_2/range/1">
            <sbol:start>732</sbol:start>
            <sbol:end>781</sbol:end>
            <sbol:orientation rdf:resource="http://sbols.org/v2#inline"/>
          </sbol:Range>
        </sbol:location>
        <sbol:component>
          <sbol:Component rdf:about="https://spice.sbol/ComponentDefinition/pTest_1/sub_component_2/1">
            <sbol:definition>
              <sbol:ComponentDefinition rdf:about="https://spice.sbol/ComponentDefinition/T1/1">
                <dcterms:title>T1</dcterms:title>
                <sbol:type rdf:resource="http://www.biopax.org/release/biopax-level3.owl#DnaRegion"/>
                <sbol:role rdf:resource="http://identifiers.org/so/SO:0000141"/>
              </sbol:ComponentDefinition>
            </sbol:definition>
          </sbol:Component>
        </sbol:component>
      </sbol:SequenceAnnotation>
    </sbol:sequenceAnnotation>
    <sbol:sequenceAnnotation>
      <sbol:SequenceAnnotation rdf:about="https://spice.sbol/ComponentDefinition/pTest_1/annotation_3/1">
        <dcterms:title>ori</dcterms:title>
        <sbol:location>
          <sbol:Range rdf:about="https://spice.sbol/ComponentDefinition/pTest_1/annotation_3/range/1">
            <sbol:start>801</sbol:start>
            <sbol:end>1401</sbol:end>
            <sbol:orientation rdf:resource="http://sbols.org/v2#inline"/>
          </sbol:Range>
        </sbol:location>
        <sbol:component>
          <sbol:Component rdf:about="https://spice.sbol/ComponentDefinition/pTest_1/sub_component_3/1">
            <sbol:definition>
              <sbol:ComponentDefinition rdf:about="https://spice.sbol/ComponentDefinition/ori/1">
                <dcterms:title>ori</dcterms:title>
                <sbol:type rdf:resource="http://www.biopax.org/release/biopax-level3.owl#DnaRegion"/>
                <sbol:role rdf:resource="http://identifiers.org/so/SO:0000296"/>
              </sbol:ComponentDefinition>
            </sbol:definition>
          </sbol:Component>
        </sbol:component>
      </sbol:SequenceAnnotation>
    </sbol:sequenceAnnotation>
    <sbol:sequenceAnnotation>
      <sbol:SequenceAnnotation rdf:about="https://spice.sbol/ComponentDefinition/pTest_1/annotation_4/1">
        <dcterms:title>misc binding</dcterms:title>
        <sbol:location>
          <sbol:Range rdf:about="https://spice.sbol/ComponentDefinition/pTest_1/annotation_4/range/1">
            <sbol:start>1501</sbol:start>
            <sbol:end>1601</sbol:end>
            <sbol:orientation rdf:resource="http://sbols.org/v2#inline"/>
          </sbol:Range>
        </sbol:location>
        <sbol:component>
          <sbol:Component rdf:about="https://spice.sbol/ComponentDefinition/pTest_1/sub_component_4/1">
            <sbol:definition>
              <sbol:ComponentDefinition rdf:about="https://spice.sbol/ComponentDefinition/misc_binding/1">
                <dcterms:title>misc binding</dcterms:title>
                <sbol:type rdf:resource="http://www.biopax.org/release/biopax-level3.owl#DnaRegion"/>
                <sbol:role rdf:resource="http://identifiers.org/so/SO:0000110"/>
              </sbol:ComponentDefinition>
            </sbol:definition>
          </sbol:Component>
        </sbol:component>
      </sbol:SequenceAnnotation>
    </sbol:sequenceAnnotation>
  </sbol:ComponentDefinition>
  <sbol:Sequence rdf:about="https://spice.sbol/Sequence/pTest_1_seq/1">
    <sbol:elements>atgcgtaaataa</sbol:elements>
    <sbol:encoding rdf:resource="http://www.chem.qmul.ac.uk/iupac/AminoAcid/"/>
  </sbol:Sequence>
</rdf:RDF>""", xml)
        }
    }
}
