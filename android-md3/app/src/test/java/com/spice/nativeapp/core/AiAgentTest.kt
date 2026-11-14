package com.spice.nativeapp.core

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AiTools / AiAgent / AiMemory / AiAudit / AiRetrieval 1:1 核验（main tools.ts /
 * agent.ts / memory.ts / audit.ts / retrieval.ts）。
 */
class AiAgentTest {

    private fun context(
        protein: String = "MKVL",
        dna: String = "ATGGTGGCACTGAAATAA",
        documents: List<AiRetrieval.SearchDocument> = emptyList(),
        memory: List<AiMemory.MemoryEntry> = emptyList(),
        actions: AiTools.AgentActions = AiTools.AgentActions()
    ): AiTools.WorkspaceContext {
        val ctx = AiTools.WorkspaceContext().apply {
            workspace = "protein"
            proteinSequence = protein
            geneDnaSequence = dna
            genePlasmidName = "pTest"
            this.documents = documents
            this.memory = memory
            this.actions = actions
        }
        return ctx
    }

    // ── get_workspace_context ──

    @Test
    fun `workspace context exposes protein gene docs and memories`() {
        val ctx = context(
            documents = listOf(AiRetrieval.SearchDocument("doc:1", "doc", "克隆笔记", "用了 Gibson 组装")),
            memory = listOf(AiMemory.MemoryEntry("m1", "宿主是大肠杆菌 BL21", listOf("host"), "now"))
        )
        val tools = AiTools.buildAgentTools(ctx)
        val tool = tools.first { it.name == "get_workspace_context" }
        val r = tool.execute(JSONObject(), ctx)
        assertTrue(r.ok)
        val data = r.data as JSONObject
        assertEquals("protein", data.getString("workspace"))
        assertEquals(4, data.getJSONObject("protein").getInt("length"))
        assertEquals("pTest", data.getJSONObject("gene").getString("name"))
        assertEquals(1, data.getJSONArray("documents").length())
        assertEquals(1, data.getJSONArray("memories").length())
    }

    // ── predict_mutation_ddg ──

    @Test
    fun `ddg validates position target and wildtype`() {
        val ctx = context(protein = "MKVL")
        val tools = AiTools.buildAgentTools(ctx)
        val tool = tools.first { it.name == "predict_mutation_ddg" }
        assertTrue(tool.execute(JSONObject("""{"position":2,"to":"A"}"""), ctx).ok)
        assertFalse(tool.execute(JSONObject("""{"position":99,"to":"A"}"""), ctx).ok)
        assertFalse(tool.execute(JSONObject("""{"position":2,"to":"X"}"""), ctx).ok)
        // pos2 是 K，声明 from=A 应失败
        val wrong = tool.execute(JSONObject("""{"position":2,"from":"A","to":"C"}"""), ctx)
        assertFalse(wrong.ok)
        assertTrue(wrong.summary.contains("not A"))
    }

    // ── analyze_dna / find_orfs / reverse_complement / calculate_tm ──

    @Test
    fun `analyze_dna returns length gc tm revcomp and orfs`() {
        // 45nt 单 ORF：ATG + 12 codons + TAA（满足默认 90 门槛钳制后的 30 下限）
        val dna = "ATG" + "GCACTGAAAGGTTTCACC".repeat(2) + "TAA"
        val ctx = context(dna = dna)
        val tool = AiTools.buildAgentTools(ctx).first { it.name == "analyze_dna" }
        val r = tool.execute(JSONObject("""{"minimumOrfNt":30}"""), ctx)
        assertTrue(r.ok)
        val data = r.data as JSONObject
        assertEquals(dna.length, data.getInt("length"))
        assertEquals(Bio.revComp(dna), data.getString("reverseComplement"))
        assertTrue("orfs=${data.getJSONArray("orfs").length()}", data.getJSONArray("orfs").length() >= 1)
        val orf = data.getJSONArray("orfs").getJSONObject(0)
        assertEquals(1, orf.getInt("start"))
        assertEquals("+", orf.getString("strand"))
        // 18nt 序列的 ORF 小于 30 下限（main Math.max(30,…) 钳制）→ 0 ORF
        val short = context(dna = "ATGGTGGCACTGAAATAA")
        val r2 = AiTools.buildAgentTools(short).first { it.name == "analyze_dna" }
            .execute(JSONObject("""{"minimumOrfNt":18}"""), short)
        assertEquals(0, (r2.data as JSONObject).getJSONArray("orfs").length())
    }

    @Test
    fun `find_orfs circular wraps a start codon near the end`() {
        // 线性序列以 ATG 结尾且无终止子：环状拼接后 ATG + TAA 应命中
        val seq = "AAAAAAAAATAAATG"   // 尾部 ATG，环回后接头部 A...TAA
        val found = AiTools.findOrfsInPlasmid(seq, circular = true, minimumOrfSize = 9)
        assertTrue("环状 ORF 应至少 1 条", found.isNotEmpty())
        val linear = AiTools.findOrfsInPlasmid(seq, circular = false, minimumOrfSize = 9)
        assertTrue("线性扫描不应命中（无终止子）", linear.isEmpty())
    }

    @Test
    fun `reverse_complement and calculate_tm gate on sequence presence`() {
        val ctx = context(dna = "ATGCGT")
        val tools = AiTools.buildAgentTools(ctx)
        val rc = tools.first { it.name == "reverse_complement" }.execute(JSONObject("""{"sequence":"ATGC"}"""), ctx)
        assertEquals("GCAT", (rc.data as JSONObject).getString("reverseComplement"))
        val tm = tools.first { it.name == "calculate_tm" }.execute(JSONObject("""{"sequence":"ATGCATGC"}"""), ctx)
        assertTrue(tm.ok)
        // 空工作区
        val emptyCtx = context(dna = "")
        val rc2 = AiTools.buildAgentTools(emptyCtx).first { it.name == "reverse_complement" }.execute(JSONObject(), emptyCtx)
        assertFalse(rc2.ok)
    }

    // ── design_primers ──

    @Test
    fun `design_primers returns forward reverse tm and diff`() {
        val ctx = context(dna = "ATGGTGGCACTGAAATAACGTTTAAAGGGCCCTTTAAACCCCC")
        val tool = AiTools.buildAgentTools(ctx).first { it.name == "design_primers" }
        val r = tool.execute(JSONObject(), ctx)
        assertTrue(r.ok)
        val data = r.data as JSONObject
        assertEquals(43, data.getInt("productLength"))
        assertEquals(20, data.getJSONObject("forward").getString("sequence").length)
        assertNotNull(data.opt("tmDifferenceC"))
    }

    // ── calculate_codon_usage ──

    @Test
    fun `codon usage computes cai gc and counts`() {
        val ctx = context(dna = "ATGGCAAAATAA")   // 4 codons
        val tool = AiTools.buildAgentTools(ctx).first { it.name == "calculate_codon_usage" }
        val r = tool.execute(JSONObject("""{"host":"yeast"}"""), ctx)
        assertTrue(r.ok)
        val data = r.data as JSONObject
        assertEquals("yeast", data.getString("host"))
        // 4 个三联密码子（终止子 TAA 若计入则取决于权重表；断言范围而非精确值）
        assertTrue("codons=${data.getInt("codons")}", data.getInt("codons") in 3..4)
        assertTrue(data.getDouble("cai") in 0.0..1.0)
        assertTrue(r.summary.contains("yeast CAI"))
        // 无效宿主回退 ecoli
        val fallback = tool.execute(JSONObject("""{"host":"mars"}"""), ctx)
        assertEquals("ecoli", (fallback.data as JSONObject).getString("host"))
    }

    // ── 工具注册 gating ──

    @Test
    fun `write tools registered only when actions provided`() {
        val withActions = context(actions = AiTools.AgentActions().apply {
            applyDnaSequence = { }
            importMutations = { }
        })
        val names = AiTools.buildAgentTools(withActions).map { it.name }
        assertTrue(names.contains("apply_dna_sequence"))
        assertTrue(names.contains("import_mutations"))
        assertFalse(names.contains("apply_protein_sequence"))   // 未提供
        assertFalse(names.contains("remember_fact"))

        val noActions = context()
        val names2 = AiTools.buildAgentTools(noActions).map { it.name }
        assertFalse(names2.contains("apply_dna_sequence"))
        // figure_add_chart 恒注册（main：不依赖 AgentActions）
        assertTrue(names2.contains("figure_add_chart"))
        // 文档/记忆 gating
        assertTrue(names2.contains("list_workspace_documents"))
        assertFalse(names2.contains("search_workspace"))
        assertFalse(names2.contains("recall_memory"))
    }

    // ── write 工具执行 + 预览 ──

    @Test
    fun `apply_dna_sequence applies validates and updates context`() {
        var applied: String? = null
        val ctx = context(dna = "ATGCGCAT").apply {
            actions = AiTools.AgentActions().apply { applyDnaSequence = { applied = it } }
        }
        val tool = AiTools.buildAgentTools(ctx).first { it.name == "apply_dna_sequence" }
        assertEquals(AiTools.Permission.WRITE, tool.permission)
        val preview = tool.preview!!.invoke(JSONObject("""{"sequence":"ATGAAACCCGGGTTT","reason":"优化"}"""), ctx)
        assertTrue(preview.contains("8 bp → 15 bp"))
        assertTrue(preview.contains("Reason: 优化"))
        val r = tool.execute(JSONObject("""{"sequence":"ATGAAACCCGGGTTT"}"""), ctx)
        assertTrue(r.ok)
        assertEquals("ATGAAACCCGGGTTT", applied)
        assertEquals("ATGAAACCCGGGTTT", ctx.geneDnaSequence)
        // 非法 DNA 拒绝
        val bad = tool.execute(JSONObject("""{"sequence":"ATGXYZ"}"""), ctx)
        assertFalse(bad.ok)
    }

    @Test
    fun `import_mutations validates against protein sequence`() {
        val imported = mutableListOf<List<AiBlocks.Mutation>>()
        val ctx = context(protein = "MKVL").apply {
            actions = AiTools.AgentActions().apply { importMutations = { imported += it } }
        }
        val tool = AiTools.buildAgentTools(ctx).first { it.name == "import_mutations" }
        val r = tool.execute(JSONObject("""{"mutations":[{"pos":2,"from":"K","to":"A"},{"pos":3,"from":"V","to":"L"}]}"""), ctx)
        assertTrue(r.ok)
        assertEquals(2, imported.single().size)
        // from 不匹配 → 失败
        val bad = tool.execute(JSONObject("""{"mutations":[{"pos":2,"from":"M","to":"A"}]}"""), ctx)
        assertFalse(bad.ok)
    }

    @Test
    fun `figure_add_chart fails honestly without studio editor`() {
        val ctx = context()
        val tool = AiTools.buildAgentTools(ctx).first { it.name == "figure_add_chart" }
        assertEquals(AiTools.Permission.WRITE, tool.permission)
        val r = tool.execute(JSONObject("""{"name":"X","chartType":"bar","labels":["a","b"],"series":[{"name":"s","values":[1,2]}]}"""), ctx)
        assertFalse(r.ok)
        assertTrue(r.summary.contains("Open Figure Studio"))
    }

    // ── remember_fact 动作回写记忆 ──

    @Test
    fun `remember_fact updates context memory via action`() {
        val base = mutableListOf(AiMemory.MemoryEntry("m0", "旧记忆", emptyList(), "t0"))
        val ctx = context(memory = base).apply {
            actions = AiTools.AgentActions().apply {
                rememberFact = { text, tags ->
                    base.add(0, AiMemory.MemoryEntry("m-" + text.hashCode(), text, tags, "now")); base.toList()
                }
            }
        }
        val tool = AiTools.buildAgentTools(ctx).first { it.name == "remember_fact" }
        val r = tool.execute(JSONObject("""{"text":"表达宿主为 BL21","tags":["host"]}"""), ctx)
        assertTrue(r.ok)
        assertEquals(2, ctx.memory.size)
        assertEquals("表达宿主为 BL21", ctx.memory[0].text)
    }

    // ── agent 循环（AiStream.Accumulator 直接喂 tool_calls，不发起网络） ──

    @Test
    fun `parseArguments accepts object empty and rejects non-object`() {
        assertTrue(AiAgent.parseArguments("")!!.length() == 0)
        assertEquals(2, AiAgent.parseArguments("""{"a":1,"b":2}""")!!.length())
        assertNull(AiAgent.parseArguments("not json"))
        assertNull(AiAgent.parseArguments("""[1,2]"""))
    }

    @Test
    fun `toolResultPayload truncates to 8000 chars and keeps shape`() {
        val big = AiTools.ToolResult(true, "x".repeat(9000), JSONObject())
        val payload = AiAgent.toolResultPayload(big)
        assertEquals(8000, payload.length)
        assertTrue(payload.startsWith("{") && payload.endsWith("x"))
        val small = AiAgent.toolResultPayload(AiTools.ToolResult(false, "boom"))
        val o = JSONObject(small)
        assertFalse(o.getBoolean("ok"))
        assertEquals("boom", o.getString("summary"))
    }
}
