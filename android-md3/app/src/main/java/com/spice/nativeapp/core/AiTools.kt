package com.spice.nativeapp.core

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Agent 工具注册表（1:1 移植 main src/lib/ai/tools.ts）。
 *
 * 只读计算工具可被模型直接调用；write 工具必须经 UI 审批门（agent 循环内），
 * 并全部记入审计日志。工具输出为纯 JSON 字符串，失败不抛异常由模型自行推理。
 *
 * 已知平台差异（README 披露）：
 * - MCP（stdio 本地命令进程）在 Android 沙箱不可用，mcpServers 恒为空；
 * - figure_add_chart 已接 FigureAgent 命令总线（FigureCharts.kt）；Figure Studio
 *   未挂载时与 main 同语义失败（'Open Figure Studio before asking...'），不排队。
 */
object AiTools {

    enum class Permission { READ, WRITE }

    data class ToolResult(
        val ok: Boolean,
        val summary: String,
        val data: Any? = null,
        /** True：动作已在宿主页面登记撤销快照 */
        val undoAvailable: Boolean = false
    )

    data class ProteinEnv(val ph: Double, val tempK: Double, val pressureBar: Double, val ionicStrengthM: Double)

    /** 工作区上下文（main AgentWorkspaceContext） */
    class WorkspaceContext {
        var workspace: String = "universal"          // gene | protein | universal
        var proteinSequence: String = ""
        var proteinEnv: ProteinEnv = ProteinEnv(7.0, 310.0, 1.0, 0.15)
        var proteinMetrics: Map<String, Double>? = null
        var geneDnaSequence: String = ""
        var genePlasmidName: String = ""
        var geneGcContent: Double = 0.0
        var geneCodonHost: String = "ecoli"
        var documents: List<AiRetrieval.SearchDocument> = emptyList()
        var memory: List<AiMemory.MemoryEntry> = emptyList()
        var actions: AgentActions = AgentActions()
    }

    /** 宿主动作（main AgentActions；未提供的动作用 null，对应工具不注册） */
    class AgentActions {
        var applyDnaSequence: ((String) -> Unit)? = null
        var applyProteinSequence: ((String) -> Unit)? = null
        var importMutations: ((List<AiBlocks.Mutation>) -> Unit)? = null
        var rememberFact: ((text: String, tags: List<String>) -> List<AiMemory.MemoryEntry>?)? = null
    }

    class AgentTool(
        val name: String,
        val description: String,
        val parameters: JSONObject,                  // JSON Schema
        val permission: Permission,
        /** write 工具审批前的人类可读预览（main preview） */
        val preview: ((args: JSONObject, context: WorkspaceContext) -> String)? = null,
        val execute: (args: JSONObject, context: WorkspaceContext) -> ToolResult
    ) {
        fun toDefinition(): AiStream.ToolDefinition =
            AiStream.ToolDefinition(name, description, parameters)
    }

    private const val AMINO_ACIDS = "ACDEFGHIKLMNPQRSTVWY"

    private fun schema(properties: String, required: String = "", additional: Boolean = false): JSONObject {
        val req = if (required.isEmpty()) "" else """"required":[$required],"""
        return JSONObject("""{"type":"object","properties":{$properties},$req"additionalProperties":$additional}""")
    }

    private fun ok(summary: String, data: Any? = null, undoAvailable: Boolean = false) =
        ToolResult(true, summary, data, undoAvailable)

    private fun fail(summary: String) = ToolResult(false, summary)

    private fun str(args: JSONObject, key: String): String =
        if (args.opt(key) is String) args.optString(key).trim() else ""

    private fun num(args: JSONObject, key: String, fallback: Double): Double {
        val v = args.optDouble(key, Double.NaN)
        return if (v.isFinite()) v else fallback
    }

    private fun currentDna(args: JSONObject, context: WorkspaceContext): String =
        (str(args, "sequence").ifEmpty { context.geneDnaSequence })
            .replace(Regex("[\\s\\n\\r\\t]"), "").uppercase()

    private fun dnaLabel(context: WorkspaceContext): String =
        if (context.genePlasmidName.isNotEmpty()) "“${context.genePlasmidName}”" else "the current construct"

    private fun round(value: Double, digits: Int = 2): Double {
        val scale = Math.pow(10.0, digits.toDouble())
        return kotlin.math.round(value * scale) / scale
    }

    /** main truncate：超长序列保留首尾各半 */
    private fun truncate(text: String, max: Int = 4000): String {
        if (text.length <= max) return text
        return text.substring(0, max / 2) + "…(${text.length - max} nt omitted)…" + text.substring(text.length - max / 2)
    }

    private fun gcPercent(seq: String): Double =
        if (seq.isEmpty()) 0.0 else seq.count { it == 'G' || it == 'C' } * 100.0 / seq.length

    /**
     * main findOrfsInPlasmid（clone.ts getOrfsFromSequence）：
     * 环状序列首尾拼接后 ATG 起始、TAA/TAG/TGA 终止、三联读框正反双链；
     * 返回 [start, end, strand]（0-based；环回绕时 end 折回 < start；strand +1/-1）。
     */
    internal fun findOrfsInPlasmid(sequence: String, circular: Boolean, minimumOrfSize: Int): List<IntArray> {
        val stops = setOf("TAA", "TAG", "TGA")
        val out = mutableListOf<IntArray>()
        val originalLength = sequence.length

        fun scanOriented(seq: String, strand: Int) {
            var s = seq
            if (circular) s += s
            for (frame in 0..2) {
                var i = frame
                var startIdx = -1
                while (i + 3 <= s.length) {
                    val cod = s.substring(i, i + 3)
                    if (cod == "ATG" && startIdx < 0) startIdx = i
                    if (cod in stops && startIdx >= 0) {
                        val orfLength = i + 3 - startIdx
                        if (orfLength >= minimumOrfSize) {
                            if (strand > 0) {
                                var end = orfLength + startIdx - 1
                                if (end >= originalLength) end -= originalLength
                                if (startIdx < originalLength) out += intArrayOf(startIdx, end, 1)
                            } else {
                                // 反链 0-based i ↔ 正链 originalLength-1-i
                                var start = originalLength - 1 - (startIdx + orfLength - 1)
                                var end = originalLength - 1 - startIdx
                                if (start < 0) start += originalLength
                                if (end >= originalLength) end -= originalLength
                                if (startIdx < originalLength && start in 0 until originalLength) out += intArrayOf(start, end, -1)
                            }
                        }
                        startIdx = -1
                    }
                    i += 3
                }
            }
        }

        scanOriented(sequence, 1)
        scanOriented(Bio.revComp(sequence), -1)
        return out
    }

    /** main orfPayload：长度降序、前 20 条、1-based 坐标 + 长度 + 链向 */
    private fun orfPayload(sequence: String, minimum: Int, circular: Boolean): JSONObject {
        val orfs = findOrfsInPlasmid(sequence, circular, minimum)
            .sortedByDescending { b: IntArray ->
                val len = if (b[1] >= b[0]) b[1] - b[0] + 1 else sequence.length - b[0] + b[1] + 1
                len
            }
            .take(20)
            .map { range ->
                val length = if (range[1] >= range[0]) range[1] - range[0] + 1 else sequence.length - range[0] + range[1] + 1
                JSONObject().apply {
                    put("start", range[0] + 1)
                    put("end", range[1] + 1)
                    put("lengthNt", length)
                    put("lengthAa", length / 3 - 1)
                    put("strand", if (range[2] == -1) "-" else "+")
                }
            }
        return JSONObject().apply { put("count", orfs.size); put("orfs", JSONArray(orfs)) }
    }

    private fun mutationPreview(mutations: List<AiBlocks.Mutation>, context: WorkspaceContext): String {
        val sequence = context.proteinSequence.uppercase()
        return mutations.joinToString(", ") { mutation ->
            val wildType = sequence.getOrNull(mutation.pos - 1)?.toString() ?: mutation.from
            "${wildType}${mutation.pos}${mutation.to}"
        }
    }

    /** 注册表构建（main buildAgentTools；context gating 与注册顺序逐条对齐） */
    fun buildAgentTools(context: WorkspaceContext): List<AgentTool> {
        val tools = mutableListOf<AgentTool>()

        // 1. get_workspace_context
        tools += AgentTool(
            name = "get_workspace_context",
            description = "Return the active workspace, protein sequence/environment, gene construct, and available project document titles.",
            parameters = schema(""),
            permission = Permission.READ,
            execute = { _, ctx ->
                val data = JSONObject().apply {
                    put("workspace", ctx.workspace)
                    put("protein", if (ctx.proteinSequence.isNotEmpty()) JSONObject().apply {
                        put("length", ctx.proteinSequence.length)
                        put("sequence", truncate(ctx.proteinSequence))
                        put("environment", JSONObject().apply {
                            put("ph", ctx.proteinEnv.ph); put("tempK", ctx.proteinEnv.tempK)
                            put("pressureBar", ctx.proteinEnv.pressureBar)
                            put("ionicStrengthM", ctx.proteinEnv.ionicStrengthM)
                        })
                        put("metrics", ctx.proteinMetrics?.let { JSONObject(it) })
                        put("phaseScanCount", 0)
                    } else JSONObject.NULL)
                    put("gene", if (ctx.geneDnaSequence.isNotEmpty()) JSONObject().apply {
                        put("name", ctx.genePlasmidName.ifEmpty { "(unnamed)" })
                        put("length", ctx.geneDnaSequence.length)
                        put("lengthBp", ctx.geneDnaSequence.length)
                        put("gcPercent", round(if (ctx.geneGcContent > 0) ctx.geneGcContent else gcPercent(ctx.geneDnaSequence), 1))
                        put("codonHost", ctx.geneCodonHost.ifEmpty { "ecoli" })
                        put("sequence", truncate(ctx.geneDnaSequence))
                    } else JSONObject.NULL)
                    put("documents", JSONArray(ctx.documents.map { JSONObject().apply {
                        put("id", it.id); put("kind", it.kind); put("title", it.title)
                    } }))
                    put("memories", JSONArray(ctx.memory.take(20).map { JSONObject().apply {
                        put("text", it.text); put("tags", JSONArray(it.tags))
                    } }))
                }
                ok("Workspace context", data)
            }
        )

        // 2. predict_mutation_ddg（蛋白序列存在时）
        if (context.proteinSequence.isNotEmpty()) {
            tools += AgentTool(
                name = "predict_mutation_ddg",
                description = "Predict the stability change (ddG) of a single point mutation in the active protein using the same empirical predictor as the protein analysis panel. Position is 1-based.",
                parameters = schema(
                    "\"position\":{\"type\":\"integer\",\"minimum\":1,\"description\":\"1-based residue position\"}," +
                        "\"to\":{\"type\":\"string\",\"description\":\"Target amino acid, one letter\"}," +
                        "\"from\":{\"type\":\"string\",\"description\":\"Optional wild-type amino acid; inferred when omitted\"}",
                    required = "\"position\",\"to\""
                ),
                permission = Permission.READ,
                execute = { args, ctx ->
                    val position = num(args, "position", 0.0).toInt()
                    val target = str(args, "to").uppercase()
                    if (position < 1 || position > ctx.proteinSequence.length) {
                        return@AgentTool fail("position must be between 1 and ${ctx.proteinSequence.length}")
                    }
                    if (target.length != 1 || target[0] !in AMINO_ACIDS) {
                        return@AgentTool fail("target must be one standard amino acid")
                    }
                    val wildType = ctx.proteinSequence[position - 1].uppercaseChar()
                    val declared = str(args, "from").uppercase()
                    if (declared.isNotEmpty() && declared != wildType.toString()) {
                        return@AgentTool fail("position $position is $wildType, not $declared")
                    }
                    val prediction = ProteinBiophysics.mutationalStabilityDdg(wildType, position, target[0])
                    ok("${wildType}${position}${target}: ddG ${round(prediction.ddG, 2)} kcal/mol", JSONObject().apply {
                        put("wildType", wildType.toString()); put("from", wildType.toString())
                        put("position", position); put("mutant", target); put("to", target)
                        put("ddG", prediction.ddG)
                        put("classification", prediction.classification)
                        put("classificationZh", prediction.classificationZh)
                    })
                }
            )
        }

        // 3. analyze_dna
        tools += AgentTool(
            name = "analyze_dna",
            description = "Analyze a DNA sequence: length, GC content, melting temperature, reverse complement, and the strongest open reading frames. Defaults to the active gene construct.",
            parameters = schema(
                "\"sequence\":{\"type\":\"string\",\"description\":\"Optional DNA sequence; defaults to the active construct\"}," +
                    "\"circular\":{\"type\":\"boolean\",\"description\":\"Treat the sequence as circular (default true)\"}," +
                    "\"minimumOrfNt\":{\"type\":\"integer\",\"minimum\":30,\"description\":\"Minimum ORF length in nucleotides (default 90)\"}"
            ),
            permission = Permission.READ,
            execute = { args, ctx ->
                val sequence = currentDna(args, ctx)
                if (sequence.isEmpty()) return@AgentTool fail("no DNA sequence in the active gene workspace")
                val (clean, error) = AiBlocks.validateDnaSequence(sequence)
                if (error != null) return@AgentTool fail(error)
                val circular = args.optBoolean("circular", true)
                val tm = if (clean.length <= 200) PrimerAnalysis.tmNearestNeighbor(clean) else null
                val orfs = orfPayload(clean, maxOf(30, num(args, "minimumOrfNt", num(args, "minLength", 90.0)).toInt()), circular)
                ok("DNA ${clean.length} bp, GC ${round(gcPercent(clean), 1)}%", JSONObject().apply {
                    put("length", clean.length); put("lengthBp", clean.length)
                    put("gcPercent", round(gcPercent(clean), 1))
                    put("meltingTemperatureC", tm?.let { round(it, 1) })
                    put("reverseComplement", Bio.revComp(clean))
                    put("circular", circular)
                    put("count", orfs.optInt("count", 0))
                    put("orfs", orfs.optJSONArray("orfs") ?: JSONArray())
                })
            }
        )

        // 4. design_primers
        tools += AgentTool(
            name = "design_primers",
            description = "Design a forward and reverse primer for the ends of a DNA sequence. Returns 20 nt annealing regions, Tm, and GC for each primer.",
            parameters = schema(
                "\"sequence\":{\"type\":\"string\",\"description\":\"Optional DNA sequence; defaults to the active construct\"}," +
                    "\"productEnd\":{\"type\":\"integer\",\"minimum\":2,\"description\":\"Optional 1-based end of the amplicon\"}"
            ),
            permission = Permission.READ,
            execute = { args, ctx ->
                val sequence = currentDna(args, ctx)
                if (sequence.isEmpty()) return@AgentTool fail("no DNA sequence in the active gene workspace")
                val end = minOf(sequence.length, maxOf(2, num(args, "productEnd", sequence.length.toDouble()).toInt()))
                val amplicon = sequence.substring(0, end)
                val forward = amplicon.substring(0, minOf(20, amplicon.length))
                val reverse = Bio.revComp(amplicon.substring(maxOf(0, amplicon.length - 20)))
                fun tmOf(primer: String): Double? =
                    if (primer.length >= 2) round(PrimerAnalysis.tmNearestNeighbor(primer), 1) else null
                val tmF = tmOf(forward); val tmR = tmOf(reverse)
                ok("Primers for $end bp amplicon", JSONObject().apply {
                    put("productLength", end); put("ampliconLength", end)
                    put("forward", JSONObject().apply {
                        put("sequence", forward); put("tmC", tmF); put("gcPercent", round(gcPercent(forward), 1))
                    })
                    put("reverse", JSONObject().apply {
                        put("sequence", reverse); put("tmC", tmR); put("gcPercent", round(gcPercent(reverse), 1))
                    })
                    put("tmDifferenceC", if (tmF != null && tmR != null) round(abs(tmF - tmR), 1) else null)
                })
            }
        )

        // 5+6. calculate_codon_usage / find_orfs（基因序列存在时）
        if (context.geneDnaSequence.isNotEmpty()) {
            tools += AgentTool(
                name = "calculate_codon_usage",
                description = "Calculate codon adaptation index (CAI) and GC content for the active gene in a host organism.",
                parameters = schema("\"host\":{\"type\":\"string\",\"enum\":[\"ecoli\",\"yeast\",\"human\"],\"description\":\"Expression host (default ecoli)\"}"),
                permission = Permission.READ,
                execute = { args, ctx ->
                    val hostStr = str(args, "host").lowercase()
                    val host = if (hostStr in listOf("ecoli", "yeast", "human")) hostStr else "ecoli"
                    val enumHost = when (host) { "yeast" -> CodeSign.Host.YEAST; "human" -> CodeSign.Host.HUMAN; else -> CodeSign.Host.ECOLI }
                    val sequence = ctx.geneDnaSequence.replace(Regex("[^ACGT]"), "").uppercase()
                    if (sequence.length < 3) return@AgentTool fail("no coding DNA sequence in the active gene workspace")
                    val cai = CodeSign.computeCai(sequence, enumHost)
                    // main calculateCai 同时统计 optimizedCount/totalCodons；复算密码子计数与 GC
                    var totalCodons = 0
                    var optimized = 0
                    val weights = CodeSign.codonWeights(enumHost)
                    var i = 0
                    while (i + 3 <= sequence.length) {
                        val codon = sequence.substring(i, i + 3)
                        val aa = CodeSign.codonToAa(codon)
                        if (aa != 'X') {
                            val aaWeights = weights[aa]
                            if (aaWeights != null) {
                                totalCodons++
                                val maxFreq = (aaWeights.values.maxOrNull() ?: 0.0).coerceAtLeast(0.01)
                                val freq = aaWeights[codon] ?: 0.01
                                if (kotlin.math.abs(freq - maxFreq) < 1e-12) optimized++
                            }
                        }
                        i += 3
                    }
                    ok("$host CAI ${round(cai, 2)}, GC ${round(gcPercent(sequence), 1)}%", JSONObject().apply {
                        put("host", host); put("cai", round(cai, 3))
                        put("gcPercent", round(gcPercent(sequence), 1))
                        put("codons", totalCodons); put("optimizedCodons", optimized)
                    })
                }
            )

            tools += AgentTool(
                name = "find_orfs",
                description = "Find open reading frames in the active gene construct (or a supplied DNA sequence).",
                parameters = schema(
                    "\"sequence\":{\"type\":\"string\",\"description\":\"Optional DNA sequence\"}," +
                        "\"minimumOrfNt\":{\"type\":\"integer\",\"minimum\":30,\"description\":\"Minimum ORF length in nucleotides (default 90)\"}," +
                        "\"circular\":{\"type\":\"boolean\",\"description\":\"Treat the sequence as circular (default true)\"}"
                ),
                permission = Permission.READ,
                execute = { args, ctx ->
                    val sequence = currentDna(args, ctx)
                    if (sequence.isEmpty()) return@AgentTool fail("no DNA sequence in the active gene workspace")
                    val minimum = maxOf(30, num(args, "minimumOrfNt", num(args, "minLength", 90.0)).toInt())
                    val circular = args.optBoolean("circular", true)
                    val payload = orfPayload(sequence, minimum, circular)
                    ok("${payload.optInt("count", 0)} ORF(s) at least $minimum nt", payload)
                }
            )
        }

        // 7. reverse_complement
        tools += AgentTool(
            name = "reverse_complement",
            description = "Return the reverse complement of a DNA sequence (defaults to the active gene construct).",
            parameters = schema("\"sequence\":{\"type\":\"string\",\"description\":\"Optional DNA sequence; defaults to the active construct\"}"),
            permission = Permission.READ,
            execute = { args, ctx ->
                val sequence = currentDna(args, ctx)
                if (sequence.isEmpty()) return@AgentTool fail("no DNA sequence in the active gene workspace")
                val (clean, error) = AiBlocks.validateDnaSequence(sequence)
                if (error != null) return@AgentTool fail(error)
                ok("Reverse complement of ${clean.length} bp", JSONObject().apply {
                    put("length", clean.length); put("reverseComplement", Bio.revComp(clean))
                })
            }
        )

        // 8. calculate_tm
        tools += AgentTool(
            name = "calculate_tm",
            description = "Calculate the SantaLucia nearest-neighbor melting temperature of a DNA sequence.",
            parameters = schema("\"sequence\":{\"type\":\"string\",\"description\":\"Optional DNA sequence; defaults to the active construct\"}"),
            permission = Permission.READ,
            execute = { args, ctx ->
                val sequence = currentDna(args, ctx)
                if (sequence.isEmpty()) return@AgentTool fail("no DNA sequence in the active gene workspace")
                if (sequence.length < 2) return@AgentTool fail("sequence must be at least 2 nt")
                val tm = PrimerAnalysis.tmNearestNeighbor(sequence)
                ok("Tm ${round(tm, 1)} °C", JSONObject().apply {
                    put("length", sequence.length)
                    put("gcPercent", round(gcPercent(sequence), 1))
                    put("tmC", round(tm, 1))
                })
            }
        )

        // 9. list_workspace_documents
        tools += AgentTool(
            name = "list_workspace_documents",
            description = "List the documents and journal pages available in the open project.",
            parameters = schema(""),
            permission = Permission.READ,
            execute = { _, ctx ->
                ok("${ctx.documents.size} document(s)", JSONObject().apply {
                    put("documents", JSONArray(ctx.documents.map { JSONObject().apply {
                        put("id", it.id); put("kind", it.kind); put("title", it.title)
                    } }))
                })
            }
        )

        // 10. search_workspace（有文档时）
        if (context.documents.isNotEmpty()) {
            tools += AgentTool(
                name = "search_workspace",
                description = "Search the open project documents, journal pages, and attached notes for relevant text. Use this before answering questions about prior experiments.",
                parameters = schema(
                    "\"query\":{\"type\":\"string\",\"description\":\"Search query\"}," +
                        "\"limit\":{\"type\":\"integer\",\"minimum\":1,\"maximum\":10,\"description\":\"Maximum hits (default 5)\"}",
                    required = "\"query\""
                ),
                permission = Permission.READ,
                execute = { args, ctx ->
                    val query = str(args, "query")
                    if (query.isEmpty()) return@AgentTool fail("query is required")
                    val hits = AiRetrieval.retrieve(query, ctx.documents, minOf(10, num(args, "limit", 5.0).toInt()))
                    ok("${hits.size} matching document(s)", JSONArray(hits.map { JSONObject().apply {
                        put("id", it.document.id); put("kind", it.document.kind)
                        put("title", it.document.title); put("score", it.score); put("snippet", it.snippet)
                    } }))
                }
            )
        }

        // 11. recall_memory（有记忆时）
        if (context.memory.isNotEmpty()) {
            tools += AgentTool(
                name = "recall_memory",
                description = "Recall durable facts the user asked the Co-Pilot to remember. Omit the query to list recent memories.",
                parameters = schema(
                    "\"query\":{\"type\":\"string\",\"description\":\"Optional search query\"}," +
                        "\"limit\":{\"type\":\"integer\",\"minimum\":1,\"maximum\":20,\"description\":\"Maximum entries (default 8)\"}"
                ),
                permission = Permission.READ,
                execute = { args, ctx ->
                    val limit = minOf(20, num(args, "limit", 8.0).toInt())
                    val query = str(args, "query")
                    val entries = if (query.isNotEmpty()) AiMemory.searchMemory(query, ctx.memory, limit)
                    else ctx.memory.take(limit)
                    val plural = if (entries.size == 1) "entry" else "entries"
                    ok("${entries.size} memory $plural", JSONObject().apply {
                        put("memories", JSONArray(entries.map { JSONObject().apply {
                            put("text", it.text); put("tags", JSONArray(it.tags)); put("createdAt", it.createdAt)
                        } }))
                    })
                }
            )
        }

        // 12. apply_dna_sequence（write）
        context.actions.applyDnaSequence?.let { _ ->
            tools += AgentTool(
                name = "apply_dna_sequence",
                description = "Replace the DNA sequence in the gene workbench. This is a destructive edit and requires user approval; the gene editor records an undo snapshot.",
                parameters = schema(
                    "\"sequence\":{\"type\":\"string\",\"description\":\"Full DNA sequence (A/C/G/T plus IUPAC ambiguity codes)\"}," +
                        "\"reason\":{\"type\":\"string\",\"description\":\"Short explanation of why this edit is needed\"}",
                    required = "\"sequence\""
                ),
                permission = Permission.WRITE,
                preview = { args, ctx ->
                    val (clean, error) = AiBlocks.validateDnaSequence(str(args, "sequence"))
                    if (error != null) return@AgentTool "Invalid DNA: $error"
                    val oldLength = ctx.geneDnaSequence.length
                    val oldGc = if (ctx.geneDnaSequence.isNotEmpty()) gcPercent(ctx.geneDnaSequence) else 0.0
                    listOf(
                        "Replace DNA in ${dnaLabel(ctx)}",
                        "Length: $oldLength bp → ${clean.length} bp",
                        "GC: ${round(oldGc, 1)}% → ${round(gcPercent(clean), 1)}%",
                        str(args, "reason").ifEmpty { null }?.let { "Reason: $it" }
                    ).filterNotNull().joinToString("\n")
                },
                execute = { args, ctx ->
                    val (clean, error) = AiBlocks.validateDnaSequence(str(args, "sequence"))
                    if (error != null) return@AgentTool fail(error)
                    val action = ctx.actions.applyDnaSequence ?: return@AgentTool fail("gene editor is not available")
                    action(clean)
                    ctx.geneDnaSequence = clean
                    ctx.geneGcContent = gcPercent(clean)
                    ok("Replaced DNA with ${clean.length} bp", JSONObject().put("lengthBp", clean.length), undoAvailable = true)
                }
            )
        }

        // 13. apply_protein_sequence（write）
        context.actions.applyProteinSequence?.let { _ ->
            tools += AgentTool(
                name = "apply_protein_sequence",
                description = "Replace the protein sequence in the protein workbench. This is a destructive edit and requires user approval; the protein editor records an undo snapshot.",
                parameters = schema(
                    "\"sequence\":{\"type\":\"string\",\"description\":\"Full protein sequence, one-letter amino acids\"}," +
                        "\"reason\":{\"type\":\"string\",\"description\":\"Short explanation of why this edit is needed\"}",
                    required = "\"sequence\""
                ),
                permission = Permission.WRITE,
                preview = { args, ctx ->
                    val sequence = str(args, "sequence").uppercase()
                    if (sequence.isEmpty() || sequence.any { it !in AMINO_ACIDS }) {
                        return@AgentTool "Invalid protein: only the 20 standard amino acids are allowed."
                    }
                    listOf(
                        "Replace protein sequence",
                        "Length: ${ctx.proteinSequence.length} aa → ${sequence.length} aa",
                        str(args, "reason").ifEmpty { null }?.let { "Reason: $it" }
                    ).filterNotNull().joinToString("\n")
                },
                execute = { args, ctx ->
                    val sequence = str(args, "sequence").uppercase()
                    if (sequence.isEmpty() || sequence.any { it !in AMINO_ACIDS }) {
                        return@AgentTool fail("only the 20 standard amino acids are allowed")
                    }
                    val action = ctx.actions.applyProteinSequence ?: return@AgentTool fail("protein workbench is not available")
                    action(sequence)
                    ctx.proteinSequence = sequence
                    ok("Replaced protein sequence with ${sequence.length} aa", JSONObject().put("lengthAa", sequence.length), undoAvailable = true)
                }
            )
        }

        // 14. import_mutations（write）
        context.actions.importMutations?.let { _ ->
            tools += AgentTool(
                name = "import_mutations",
                description = "Import point mutations into the protein mutation cart. Requires user approval. Each mutation is validated against the active protein sequence.",
                parameters = schema(
                    "\"mutations\":{\"type\":\"array\",\"items\":{\"type\":\"object\",\"properties\":{" +
                        "\"pos\":{\"type\":\"integer\",\"minimum\":1}," +
                        "\"from\":{\"type\":\"string\"}," +
                        "\"to\":{\"type\":\"string\"}," +
                        "\"score\":{\"type\":\"number\"}}," +
                        "\"required\":[\"pos\",\"from\",\"to\"]}}",
                    required = "\"mutations\""
                ),
                permission = Permission.WRITE,
                preview = { args, ctx ->
                    val checked = AiBlocks.validateMutations(parseMutationArray(args.optJSONArray("mutations")), ctx.proteinSequence)
                    if (checked.errors.isNotEmpty()) return@AgentTool "Invalid mutation(s): ${checked.errors.take(3).joinToString("; ")}"
                    "Add ${checked.valid.size} mutation(s) to the protein cart:\n${mutationPreview(checked.valid, ctx)}"
                },
                execute = { args, ctx ->
                    val checked = AiBlocks.validateMutations(parseMutationArray(args.optJSONArray("mutations")), ctx.proteinSequence)
                    if (checked.errors.isNotEmpty()) return@AgentTool fail(checked.errors.first())
                    if (checked.valid.isEmpty()) return@AgentTool fail("no valid mutations supplied")
                    val action = ctx.actions.importMutations ?: return@AgentTool fail("protein mutation cart is not available")
                    action(checked.valid)
                    ok("Imported ${checked.valid.size} mutation(s)", JSONObject().put("mutations", JSONArray(checked.valid.map {
                        JSONObject().apply { put("pos", it.pos); put("from", it.from); put("to", it.to); it.score?.let { s -> put("score", s) } }
                    })), undoAvailable = true)
                }
            )
        }

        // 15. remember_fact（write）
        context.actions.rememberFact?.let { _ ->
            tools += AgentTool(
                name = "remember_fact",
                description = "Store a durable fact for future conversations (for example an expression host, a project convention, or a user preference). Requires user approval.",
                parameters = schema(
                    "\"text\":{\"type\":\"string\",\"description\":\"The fact to remember, concise and self-contained\"}," +
                        "\"tags\":{\"type\":\"array\",\"items\":{\"type\":\"string\"},\"description\":\"Optional tags\"}",
                    required = "\"text\""
                ),
                permission = Permission.WRITE,
                preview = { args, _ -> "Remember this fact:\n${str(args, "text")}" },
                execute = { args, ctx ->
                    val text = str(args, "text")
                    if (text.isEmpty()) return@AgentTool fail("text is required")
                    val action = ctx.actions.rememberFact ?: return@AgentTool fail("memory is not available")
                    val tags = args.optJSONArray("tags")?.let { a -> List(a.length()) { a.optString(it) } }?.take(12) ?: emptyList()
                    val updated = action(text, tags)
                    if (updated != null) ctx.memory = updated
                    ok("Remembered: ${text.take(140)}", JSONObject().apply { put("text", text); put("tags", JSONArray(tags)) })
                }
            )
        }

        // 16. figure_add_chart（write；编辑器未挂载时如实失败）
        tools += AgentTool(
            name = "figure_add_chart",
            description = "Create an editable bar, line, or scatter chart in Figure Studio. Series values are plotted against the supplied category labels and can be adjusted afterwards in the inspector. Requires Figure Studio to be open.",
            parameters = schema(
                "\"name\":{\"type\":\"string\",\"description\":\"Chart title and layer name\"}," +
                    "\"chartType\":{\"type\":\"string\",\"enum\":[\"bar\",\"line\",\"scatter\"]}," +
                    "\"labels\":{\"type\":\"array\",\"minItems\":2,\"maxItems\":50,\"items\":{\"type\":\"string\"},\"description\":\"Category labels, one per plotted value\"}," +
                    "\"series\":{\"type\":\"array\",\"minItems\":1,\"maxItems\":12,\"items\":{\"type\":\"object\",\"properties\":{" +
                        "\"name\":{\"type\":\"string\",\"description\":\"Series legend name\"}," +
                        "\"values\":{\"type\":\"array\",\"items\":{\"type\":\"number\"},\"description\":\"Finite numeric values; padded with zeros or truncated to the label count\"}," +
                        "\"errors\":{\"type\":\"array\",\"items\":{\"type\":\"number\"},\"description\":\"Optional error magnitudes; converted to absolute values\"}," +
                        "\"color\":{\"type\":\"string\",\"description\":\"Optional CSS colour; a palette colour is assigned when omitted\"}}," +
                        "\"required\":[\"name\",\"values\"],\"additionalProperties\":false}}," +
                    "\"xLabel\":{\"type\":\"string\",\"description\":\"Optional x-axis title\"}," +
                    "\"yLabel\":{\"type\":\"string\",\"description\":\"Optional y-axis title\"}",
                required = "\"name\",\"chartType\",\"labels\",\"series\""
            ),
            permission = Permission.WRITE,
            preview = { args, _ ->
                val chartType = str(args, "chartType").ifEmpty { "bar" }
                val name = str(args, "name").ifEmpty { "Chart" }
                val labels = args.optJSONArray("labels")?.let { a -> List(a.length()) { a.optString(it) } } ?: emptyList()
                val rawSeries = args.optJSONArray("series")?.let { a -> List(a.length()) { it -> a.optJSONObject(it) } } ?: emptyList()
                val seriesNames = rawSeries.mapIndexed { i, o -> o?.optString("name")?.ifEmpty { null } ?: "Series ${i + 1}" }
                "Add $chartType chart “$name” to Figure Studio\n" +
                    "${rawSeries.size} series: ${seriesNames.joinToString(", ").ifEmpty { "(none)" }}\n" +
                    "${labels.size} categories: ${labels.take(8).joinToString(", ").ifEmpty { "(none)" }}${if (labels.size > 8) ", …" else ""}\n" +
                    "Requires Figure Studio to be open."
            },
            execute = { args, _ ->
                // main tools.ts figure_add_chart execute 1:1（校验顺序与文案同构）
                val chartTypeStr = str(args, "chartType")
                val chartType = chartKindFromJs(chartTypeStr)
                if (chartType == null) return@AgentTool fail("chartType must be bar, line, or scatter")
                val labelsRaw = args.optJSONArray("labels")
                if (labelsRaw == null) return@AgentTool fail("labels must be an array of 2 to 50 category names")
                val labels = (0 until labelsRaw.length()).map { i ->
                    if (labelsRaw.opt(i) is String) labelsRaw.optString(i).trim() else ""
                }
                if (labels.size < 2 || labels.size > 50) return@AgentTool fail("labels must contain between 2 and 50 categories")
                if (labels.any { it.isEmpty() }) return@AgentTool fail("every label must be a non-empty string")
                val seriesRaw = args.optJSONArray("series")
                if (seriesRaw == null || seriesRaw.length() < 1 || seriesRaw.length() > 12) {
                    return@AgentTool fail("series must contain between 1 and 12 entries")
                }
                val series = mutableListOf<FigureChartSpecSeries>()
                for (index in 0 until seriesRaw.length()) {
                    val parsed = figureSeriesArg(seriesRaw.optJSONObject(index), index, labels.size)
                        ?: return@AgentTool fail("series ${index + 1} must be an object")
                    val err = parsed as? String
                    if (err != null) return@AgentTool fail(err)
                    series.add(parsed as FigureChartSpecSeries)
                }
                val xLabel = str(args, "xLabel")
                val yLabel = str(args, "yLabel")
                val spec = FigureChartSpec(
                    name = str(args, "name").ifEmpty { "Chart" },
                    chartType = chartType,
                    labels = labels,
                    series = series,
                    xLabel = xLabel.ifEmpty { null },
                    yLabel = yLabel.ifEmpty { null },
                )
                // main：编辑器未挂载时不排队写操作——工具调用如实失败，
                // 挂载后静默应用会误导。
                if (!FigureAgent.figureToolActive()) {
                    return@AgentTool fail("Open Figure Studio before asking the assistant to add a chart.")
                }
                FigureAgent.dispatchFigureCommand(FigureAgentCommand.AddChart(spec))
                ok("Added a ${chartType.jsName} chart with ${series.size} series to Figure Studio", spec.toJson(), true)
            }
        )

        return tools
    }

    /** main import_mutations args.mutations（宽松 JSON 形状）→ AiBlocks.Mutation */
    internal fun parseMutationArray(raw: JSONArray?): List<AiBlocks.Mutation> {
        if (raw == null) return emptyList()
        val out = mutableListOf<AiBlocks.Mutation>()
        for (i in 0 until raw.length()) {
            val o = raw.optJSONObject(i) ?: continue
            val pos = o.optInt("pos", -1)
            if (pos < 1) continue
            val from = o.optString("from", "")
            val to = o.optString("to", "")
            val score = if (o.has("score") && !o.isNull("score")) o.optDouble("score") else null
            out += AiBlocks.Mutation(pos, from, to, score)
        }
        return out
    }

    /** main finiteNumbers：数组且全部为有限数值，否则 null。 */
    internal fun finiteNumbersArg(value: JSONArray?): List<Double>? {
        if (value == null) return null
        val numbers = mutableListOf<Double>()
        for (i in 0 until value.length()) {
            val entry = value.opt(i)
            if (entry !is Number || !(entry.toDouble()).isFinite()) return null
            numbers.add(entry.toDouble())
        }
        return numbers
    }

    /** main fitSeriesLength：补零或截断到类目数。 */
    internal fun fitSeriesLength(values: List<Double>, length: Int): List<Double> {
        val fitted = values.take(length).toMutableList()
        while (fitted.size < length) fitted.add(0.0)
        return fitted
    }

    /** main figureSeries：单系列解析（严格 finite；errors 取绝对值；色缺省取色板）。 */
    internal fun figureSeriesArg(raw: JSONObject?, index: Int, categoryCount: Int): Any? {
        if (raw == null) return null
        val values = finiteNumbersArg(raw.optJSONArray("values"))
            ?: return "series ${index + 1} values must be finite numbers"
        val rawErrorsOpt = raw.opt("errors")
        val rawErrors = if (rawErrorsOpt == null || rawErrorsOpt == JSONObject.NULL) emptyList()
        else finiteNumbersArg(raw.optJSONArray("errors"))
            ?: return "series ${index + 1} errors must be finite numbers"
        val name = if (raw.opt("name") is String) raw.optString("name").trim() else ""
        val color = if (raw.opt("color") is String) raw.optString("color").trim() else ""
        return FigureChartSpecSeries(
            name = name.ifEmpty { "Series ${index + 1}" },
            values = fitSeriesLength(values, categoryCount),
            errors = fitSeriesLength(rawErrors, categoryCount).map { abs(it) },
            color = color.ifEmpty { CHART_PALETTE[index % CHART_PALETTE.size] },
        )
    }

    /** 供审计展示的工具结果摘要截断 */
    fun summarize(result: ToolResult): String =
        (if (result.ok) "ok" else "failed") + " · " + result.summary.take(200)
}
