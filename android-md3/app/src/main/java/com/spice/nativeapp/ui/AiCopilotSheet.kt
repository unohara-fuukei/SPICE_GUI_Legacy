package com.spice.nativeapp.ui

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.spice.nativeapp.core.AiAgent
import com.spice.nativeapp.core.AiAudit
import com.spice.nativeapp.core.AiBlocks
import com.spice.nativeapp.core.AiClient
import com.spice.nativeapp.core.AiContext
import com.spice.nativeapp.core.AiMemory
import com.spice.nativeapp.core.AiRetrieval
import com.spice.nativeapp.core.AiStream
import com.spice.nativeapp.core.AiTools
import com.spice.nativeapp.core.DEFAULT_AI_SYSTEM_PROMPT
import com.spice.nativeapp.core.AGENT_SYSTEM_RULES
import com.spice.nativeapp.data.ProjectStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** AI 工作区（对齐 main aiState 的 currentWorkspace） */
enum class AiWorkspace(val label: String) { GENE("基因"), PROTEIN("蛋白") }

/** 对话消息（对齐 main ChatMessage：错误注记不回放为历史，toolNotes 记录本条回复用过的工具） */
data class ChatMsg(
    val id: Long,
    val role: String,          // user | assistant | system
    val text: String,
    val ts: String,
    val error: Boolean = false,
    val toolNotes: List<String>? = null
)

/**
 * AI 会话全局状态（对齐 main src/lib/ui/aiState.svelte.ts）：
 * - 抽屉收回（composable 离开组合域）历史不丢；
 * - **跨会话持久化**（对齐 main localStorage spice_ai_messages）：最近 120 条落盘
 *   SharedPreferences，应用冷启动恢复；错误注记与「正在思考中...」占位不落盘（main 同规则）。
 * - agentEnabled 持久化（对齐 main spice_ai_agent_enabled）。
 */
object AiChatStore {
    // zh.json copilotWelcomeIntro 原文
    const val WELCOME =
        "你好！我是 SPICE AI 智能助手。我可以帮你进行密码子优化、智能突变库设计、物理模拟数据解读（如 pH/温度相图分析），以及推荐湿实验方案。请问有什么我可以帮你的？"

    private const val PREFS = "spice_ai_messages_store"
    private const val KEY_MESSAGES = "spice_ai_messages"
    private const val MAX_SAVED = 120
    private const val KEY_AGENT = "spice_ai_agent_enabled"

    var entries by mutableStateOf(listOf(ChatMsg(1L, "assistant", WELCOME, stamp())))
    var input by mutableStateOf("")
    var tab by mutableStateOf(0)
    var isGenerating by mutableStateOf(false)
    var agentEnabled by mutableStateOf(true)
    /** Agent 记忆 / 审计（对齐 main aiState.memory / aiState.audit） */
    var memory by mutableStateOf<List<AiMemory.MemoryEntry>>(emptyList())
    var audit by mutableStateOf<List<AiAudit.AuditEntry>>(emptyList())
    private var loaded = false
    @Volatile var cancelRequested = false

    fun stamp(): String = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())

    /** 冷启动恢复（在首个组合点调用一次） */
    fun ensureLoaded(ctx: Context) {
        if (loaded) return
        loaded = true
        memory = AiMemory.load(ctx)
        audit = AiAudit.load(ctx)
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        agentEnabled = prefs.getBoolean(KEY_AGENT, true)
        runCatching {
            val saved = prefs.getString(KEY_MESSAGES, null) ?: return@runCatching
            val arr = JSONArray(saved)
            val restored = mutableListOf<ChatMsg>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val id = o.optLong("id", 0L)
                val role = o.optString("role", "")
                val text = o.optString("text", "")
                val ts = o.optString("ts", "")
                if (id !in 1 until Long.MAX_VALUE || role !in listOf("user", "assistant", "system") || text.isEmpty()) continue
                val notes = o.optJSONArray("toolNotes")?.let { a -> List(a.length()) { a.optString(it) }.filter { it.isNotEmpty() } }
                restored += ChatMsg(id, role, text, ts, o.optBoolean("error", false), notes)
            }
            if (restored.isNotEmpty()) entries = restored.takeLast(MAX_SAVED)
        }
    }

    /** 落盘（main localStorage.setItem('spice_ai_messages')：≤120 条，错误注记/占位不入历史） */
    fun persist(ctx: Context) {
        runCatching {
            val arr = JSONArray()
            entries.filter { !it.error && it.text != "正在思考中..." }.takeLast(MAX_SAVED).forEach { m ->
                arr.put(JSONObject().apply {
                    put("id", m.id)
                    put("role", m.role)
                    put("text", m.text)
                    put("ts", m.ts)
                    m.toolNotes?.let { put("toolNotes", JSONArray(it)) }
                })
            }
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY_MESSAGES, arr.toString())
                .putBoolean(KEY_AGENT, agentEnabled)
                .apply()
        }
    }

    fun setAgentEnabled(ctx: Context, enabled: Boolean) {
        agentEnabled = enabled
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_AGENT, enabled).apply()
    }

    fun rememberFact(ctx: Context, text: String, tags: List<String>): List<AiMemory.MemoryEntry> {
        memory = AiMemory.add(ctx, memory, text, tags)
        return memory
    }

    fun removeMemory(ctx: Context, id: String) {
        memory = AiMemory.remove(ctx, memory, id)
    }

    fun recordAudit(
        ctx: Context, kind: AiAudit.Kind, tool: String, permission: AiTools.Permission,
        approved: Boolean?, ok: Boolean, summary: String, args: Any?
    ) {
        audit = AiAudit.append(ctx, audit, kind, tool, permission.name.lowercase(), approved, ok, summary, args)
    }

    fun clearAudit(ctx: Context) {
        audit = AiAudit.save(ctx, emptyList())
    }

    /** 清空历史（对齐 main clearHistory → copilotHistoryClearedIntro） */
    fun clearHistory(ctx: Context) {
        entries = listOf(ChatMsg(System.currentTimeMillis(), "assistant",
            com.spice.nativeapp.core.I18n.t("已清除对话历史。你可以输入任何有关蛋白质设计、突变、克隆或模拟的问题。"), stamp()))
        input = ""
        persist(ctx)
    }

    /** 按 id 更新最后一条 assistant 消息文本（流式打字机逐 delta 更新） */
    fun updateLast(id: Long, text: String, toolNotes: List<String>? = null) {
        entries = entries.map { if (it.id == id) it.copy(text = text, toolNotes = toolNotes ?: it.toolNotes) else it }
    }
}

// ───────────────────────── 上下文注入（main AiCopilot.svelte，文案 zh.json 原文） ─────────────────────────

/**
 * 当前会话上下文提示词（对齐 main buildContextPrompt）。
 * 文案逐字取自 main project.inlang/messages/zh.json copilotCtx* 键。
 */
internal fun buildContextPrompt(
    workspace: AiWorkspace,
    sequence: String,
    sequenceLabel: String,
    plasmidName: String = "",
    gcPercent: Double? = null,
    envPh: Double? = null,
    envTempK: Double? = null,
    envIonic: Double? = null,
    metricsText: String? = null
): String {
    val sb = StringBuilder(com.spice.nativeapp.core.I18n.t("【当前 GUI 会话上下文（实时状态）】\n"))
    sb.append("- 当前活动页面/工作区: ${if (workspace == AiWorkspace.PROTEIN) "蛋白验证" else "基因设计编辑器 (Gene Designer)"}\n")
    sb.append(com.spice.nativeapp.core.I18n.t("工作区专属限制指令】:\n\n"))
    if (workspace == AiWorkspace.PROTEIN && sequence.isNotEmpty()) {
        val head = if (sequence.length > 50) sequence.take(50) + "..." else sequence
        sb.append(com.spice.nativeapp.core.I18n.tf("- 当前研究蛋白质序列: {v1} (长度: {v2} aa)\n", head, sequence.length))
        if (envPh != null && envTempK != null) {
            sb.append(com.spice.nativeapp.core.I18n.tf("- 当前物理环境: pH={v1}, Temp={v2} K ({v3}°C), 离子强度={v4} M\n", "%.1f".format(envPh), envTempK.toInt(), "%.1f".format(envTempK - 273.15), envIonic ?: 0.0))
        }
        if (!metricsText.isNullOrEmpty()) sb.append(com.spice.nativeapp.core.I18n.tf("- 当前物理指标: {v1}\n", metricsText))
    }
    if (workspace == AiWorkspace.GENE && sequence.isNotEmpty()) {
        sb.append(com.spice.nativeapp.core.I18n.tf("- 当前质粒 DNA 名称: {v1}\n", plasmidName.ifEmpty { "未命名" }))
        val gc = gcPercent ?: runCatching { sequence.count { it in "GCgc" }.toDouble() / sequence.length * 100 }.getOrDefault(0.0)
        sb.append(com.spice.nativeapp.core.I18n.tf("- 当前 DNA 序列长度: {v1} bp, GC含量: {v2}%\n", sequence.length, "%.1f".format(gc)))
        sb.append(com.spice.nativeapp.core.I18n.t("- 当前优化宿主偏好: 大肠杆菌 (E. coli)\n"))
    }
    return sb.toString()
}

/** main buildSearchDocuments：项目文档 + ELN 页 + 附件 + 活动序列 + 记忆（本地检索语料） */
internal fun buildSearchDocuments(
    ctx: Context,
    workspace: AiWorkspace,
    sequence: String,
    plasmidName: String
): List<AiRetrieval.SearchDocument> {
    fun clip(text: String, max: Int = 12_000) = if (text.length > max) text.take(max) + "…" else text
    val documents = mutableListOf<AiRetrieval.SearchDocument>()
    runCatching {
        for (project in ProjectStore.load(ctx)) {
            for (doc in project.docs) {
                documents += AiRetrieval.SearchDocument("doc:${project.id}-${doc.title}", "doc", doc.title, clip(doc.body))
            }
            for (file in project.attachments) {
                documents += AiRetrieval.SearchDocument("file:${project.id}-${file.name}", "file", file.name, "${file.size} bytes")
            }
        }
        for (page in ElnStore.load(ctx)) {
            documents += AiRetrieval.SearchDocument(
                "journal:${page.id}", "journal",
                page.title.ifEmpty { page.category.ifEmpty { "Journal" } },
                clip(page.content)
            )
        }
    }
    if (sequence.isNotEmpty()) {
        if (workspace == AiWorkspace.GENE) {
            documents += AiRetrieval.SearchDocument(
                "active:gene", "gene",
                plasmidName.ifEmpty { "Active gene" }, clip(sequence)
            )
        } else {
            documents += AiRetrieval.SearchDocument("active:protein", "protein", "Active protein", clip(sequence))
        }
    }
    for (entry in AiChatStore.memory) {
        documents += AiRetrieval.SearchDocument(
            "memory:${entry.id}", "memory",
            if (entry.tags.isNotEmpty()) "Memory (${entry.tags.joinToString(", ")})" else "Memory",
            entry.text
        )
    }
    return documents
}

/**
 * SPICE AI 智能助手（对齐 main 全局 Co-Pilot 侧栏 AiCopilot.svelte）：
 * 右侧侧拉抽屉 + 流式打字机（SSE，main streamChat/onToken）+ 跨会话历史 +
 * Agent 模式（main runAgentTurn：工具调用/审批门/工具痕迹/记忆/审计）。
 */
@Composable
fun AiCopilotSheet(
    workspace: AiWorkspace,
    sequence: String,
    sequenceLabel: String,
    plasmidName: String = "",
    gcPercent: Double? = null,
    envPh: Double? = null,
    envTempK: Double? = null,
    envIonic: Double? = null,
    metricsText: String? = null,
    onImportDna: ((String) -> Unit)? = null,
    onImportMutations: ((List<Triple<Int, Char, Char>>) -> Unit)? = null,
    onApplyDna: ((String) -> Unit)? = null,
    onApplyProtein: ((String) -> Unit)? = null,
    onDismiss: () -> Unit
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    AiChatStore.ensureLoaded(ctx)
    val prefs = remember { ctx.getSharedPreferences("spice_settings", android.content.Context.MODE_PRIVATE) }

    // ── 配置（持久化，对齐 main spice_ai_config） ──
    var endpoint by remember { mutableStateOf(prefs.getString("ai_endpoint", "https://apihub.agnes-ai.com/v1/") ?: "") }
    var model by remember { mutableStateOf(prefs.getString("ai_model", "agnes-3.0-flash") ?: "") }
    var apiKey by remember { mutableStateOf(prefs.getString("ai_apikey", "sk-AM9V7FPao8RxrfFp8Ng7ZfsLbEu6PFjSi1gQ14r1OUtSQ6mm") ?: "") }
    var temperature by remember { mutableStateOf(prefs.getString("ai_temperature", "0.7") ?: "0.7") }
    var maxTokens by remember { mutableStateOf(prefs.getString("ai_maxtokens", "2048") ?: "2048") }
    var systemPrompt by remember { mutableStateOf(prefs.getString("ai_system_prompt", DEFAULT_AI_SYSTEM_PROMPT) ?: DEFAULT_AI_SYSTEM_PROMPT) }
    var testResult by remember { mutableStateOf<String?>(null) }
    fun saveConfig() {
        prefs.edit()
            .putString("ai_endpoint", endpoint).putString("ai_model", model)
            .putString("ai_apikey", apiKey).putString("ai_temperature", temperature)
            .putString("ai_maxtokens", maxTokens)
            .putString("ai_system_prompt", systemPrompt)
            .apply()
    }

    var errMsg by remember { mutableStateOf<String?>(null) }
    val listState = rememberLazyListState()

    // ── 审批门（main requestApproval/resolveApproval：同一时刻仅一个待审批） ──
    var approvalRequest by remember { mutableStateOf<AiAgent.ApprovalRequest?>(null) }
    var approvalResolver by remember { mutableStateOf<CompletableDeferred<Boolean>?>(null) }
    fun resolveApproval(approved: Boolean) {
        approvalResolver?.complete(approved)
        approvalResolver = null
        approvalRequest = null
    }

    fun config() = AiClient.Config(
        endpoint = endpoint,
        model = model,
        apiKey = apiKey,
        temperature = temperature.toDoubleOrNull() ?: 0.7,
        maxTokens = maxTokens.toIntOrNull() ?: 0
    )

    // main describeAiError → zh.json copilotError* 原文
    fun describeError(e: AiClient.AiRequestException): String = when (e.kind) {
        AiClient.Kind.AUTH -> com.spice.nativeapp.core.I18n.t("认证失败，请检查 API 密钥。")
        AiClient.Kind.RATE_LIMIT -> com.spice.nativeapp.core.I18n.t("请求过于频繁，请稍后再试。")
        AiClient.Kind.SERVER -> com.spice.nativeapp.core.I18n.t("AI 服务返回错误。")
        AiClient.Kind.TIMEOUT -> com.spice.nativeapp.core.I18n.t("AI 请求超时。")
        AiClient.Kind.NETWORK -> com.spice.nativeapp.core.I18n.t("无法连接 AI 服务，请检查端点是否正在运行。")
        else -> e.message ?: com.spice.nativeapp.core.I18n.t("未知错误")
    }

    // main toolNote：`toolname · running/ok/failed/rejected`
    fun toolNote(event: AiAgent.ToolEvent, done: Boolean = false): String {
        val label = event.tool.name + (if (event.approved == false) " · rejected" else "")
        if (!done) return "$label · running"
        return "$label · " + (if (event.result?.ok == true) "ok" else "failed")
    }

    fun send() {
        val q = AiChatStore.input.trim()
        if (q.isEmpty() || AiChatStore.isGenerating) return
        AiChatStore.input = ""
        errMsg = null
        val now = System.currentTimeMillis()
        AiChatStore.entries = AiChatStore.entries + ChatMsg(now, "user", q, AiChatStore.stamp())
        AiChatStore.isGenerating = true
        AiChatStore.cancelRequested = false
        val assistantId = now + 1
        AiChatStore.entries = AiChatStore.entries + ChatMsg(assistantId, "assistant", com.spice.nativeapp.core.I18n.t("正在思考中..."), AiChatStore.stamp())

        // 历史组装（对齐 main sendMessage）：排除欢迎条、错误注记与占位；取最近 40 条
        val history = AiChatStore.entries
            .filter { it.id != 1L && !it.error && !(it.role == "assistant" && it.text == "正在思考中...") }
            .dropLast(2)
            .takeLast(40)
            .map { AiStream.ChatMessage(it.role, it.text) }

        // 本地检索 grounding（main retrieve + formatRetrievalContext：显式标注为数据非指令）
        val documents = buildSearchDocuments(ctx, workspace, sequence, plasmidName)
        val retrieved = AiRetrieval.formatRetrievalContext(AiRetrieval.retrieve(q, documents, 4))
        val contextPrompt = buildContextPrompt(workspace, sequence, sequenceLabel, plasmidName, gcPercent, envPh, envTempK, envIonic, metricsText) +
            (if (retrieved.isNotEmpty()) "\n$retrieved" else "")
        // zh.json aiPromptWithContextWrap 原文
        val userContent = com.spice.nativeapp.core.I18n.tf("{v1}\n用户输入: {v2}", contextPrompt, q)

        val payload = AiContext.buildChatPayload(
            systemPrompt = if (AiChatStore.agentEnabled) "$systemPrompt\n\n$AGENT_SYSTEM_RULES" else systemPrompt,
            history = history,
            userContent = userContent,
            reserveTokens = maxTokens.toIntOrNull() ?: 2048
        )

        scope.launch {
            try {
                if (AiChatStore.agentEnabled) {
                    // ── Agent 模式（main runAgentTurn）──
                    val workspaceCtx = AiTools.WorkspaceContext().apply {
                        this.workspace = if (workspace == AiWorkspace.PROTEIN) "protein" else "gene"
                        proteinSequence = if (workspace == AiWorkspace.PROTEIN) sequence else ""
                        if (envPh != null && envTempK != null) {
                            proteinEnv = AiTools.ProteinEnv(envPh, envTempK, 1.0, envIonic ?: 0.15)
                        }
                        geneDnaSequence = if (workspace == AiWorkspace.GENE) sequence else ""
                        genePlasmidName = if (workspace == AiWorkspace.GENE) plasmidName else ""
                        gcPercent?.let { geneGcContent = it }
                        this.documents = buildSearchDocuments(ctx, workspace, sequence, plasmidName)
                        this.memory = AiChatStore.memory
                        this.actions = AiTools.AgentActions().apply {
                            onApplyDna?.let { cb -> applyDnaSequence = cb }
                            onApplyProtein?.let { cb -> applyProteinSequence = cb }
                            onImportMutations?.let { cb ->
                                importMutations = { muts -> cb(muts.map { Triple(it.pos, it.from.firstOrNull() ?: 'X', it.to.firstOrNull() ?: 'X') }) }
                            }
                            rememberFact = { text, tags -> AiChatStore.rememberFact(ctx, text, tags) }
                        }
                    }
                    val tools = AiTools.buildAgentTools(workspaceCtx)
                    var stepPrefix = ""
                    val result = withContext(Dispatchers.IO) {
                        AiAgent.runAgentTurn(
                            AiAgent.RunOptions(
                                config = config(),
                                messages = payload.messages,
                                tools = tools,
                                context = workspaceCtx,
                                maxSteps = 6,
                                contextTokens = AiContext.DEFAULT_CONTEXT_TOKENS,
                                reserveTokens = maxTokens.toIntOrNull() ?: 2048,
                                isCancelled = { AiChatStore.cancelRequested },
                                requestApproval = { request ->
                                    withContext(Dispatchers.Main) {
                                        val deferred = CompletableDeferred<Boolean>()
                                        approvalResolver = deferred
                                        approvalRequest = request
                                        deferred.await()
                                    }
                                },
                                onStepStart = { step ->
                                    val current = AiChatStore.entries.lastOrNull { it.id == assistantId }?.text ?: ""
                                    stepPrefix = if (step > 0 && current.isNotEmpty() && current != "正在思考中...") "$current\n\n" else ""
                                },
                                onToken = { _, full -> AiChatStore.updateLast(assistantId, stepPrefix + full) },
                                onToolStart = { event ->
                                    val notes = (AiChatStore.entries.lastOrNull { it.id == assistantId }?.toolNotes ?: emptyList()) + toolNote(event)
                                    AiChatStore.updateLast(assistantId, stepPrefix + (AiChatStore.entries.lastOrNull { it.id == assistantId }?.text?.takeIf { it != "正在思考中..." } ?: ""), notes)
                                },
                                onToolEnd = { event ->
                                    val msg = AiChatStore.entries.lastOrNull { it.id == assistantId }
                                    val notes = (msg?.toolNotes ?: emptyList()).toMutableList()
                                    if (notes.isNotEmpty()) notes[notes.size - 1] = toolNote(event, done = true)
                                    AiChatStore.updateLast(assistantId, msg?.text ?: "", notes)
                                },
                                audit = { record ->
                                    AiChatStore.recordAudit(ctx, record.kind, record.tool, record.permission, record.approved, record.ok, record.summary, record.args)
                                }
                            )
                        )
                    }
                    // main：max-steps 且无文本 → copilotMaxSteps + error 注记
                    if (result.content.isBlank() && result.stoppedReason == "max-steps") {
                        AiChatStore.entries = AiChatStore.entries.map {
                            if (it.id == assistantId) it.copy(text = stepPrefix + com.spice.nativeapp.core.I18n.t("Agent 在给出最终答案前达到了工具调用上限。请重新提问或把任务拆成更小的步骤。"), error = true) else it
                        }
                    } else {
                        AiChatStore.updateLast(assistantId, (stepPrefix + result.content).trim())
                    }
                } else {
                    // ── 普通对话（main streamChat）──
                    val result = withContext(Dispatchers.IO) {
                        AiStream.streamChatCompletion(
                            config = config(),
                            messages = payload.messages,
                            onToken = { _, full -> AiChatStore.updateLast(assistantId, full) },
                            isCancelled = { AiChatStore.cancelRequested }
                        )
                    }
                    AiChatStore.updateLast(assistantId, result.content)
                }
            } catch (e: AiStream.CancelledException) {
                // 对齐 main：已收到的部分保留 + 「已取消生成。」注记
                val partial = AiChatStore.entries.lastOrNull { it.id == assistantId }?.text ?: ""
                val text = if (partial.isNotEmpty() && partial != "正在思考中...") com.spice.nativeapp.core.I18n.tf("{v1}\n\n_已取消生成。_", partial) else com.spice.nativeapp.core.I18n.t("已取消生成。")
                AiChatStore.entries = AiChatStore.entries.map { if (it.id == assistantId) it.copy(text = text, error = true) else it }
            } catch (e: AiClient.AiRequestException) {
                errMsg = com.spice.nativeapp.core.I18n.tf("AI 请求失败：{v1}", describeError(e))
                AiChatStore.entries = AiChatStore.entries.filter { it.id != assistantId } +
                    ChatMsg(assistantId, "assistant", com.spice.nativeapp.core.I18n.tf("AI 请求失败：{v1}", describeError(e)), AiChatStore.stamp(), error = true)
            } finally {
                resolveApproval(false)   // main cancelGeneration：残留审批一律拒绝
                AiChatStore.isGenerating = false
                AiChatStore.persist(ctx)
            }
        }
    }

    val drawerState = rememberDrawerState(initialValue = androidx.compose.material3.DrawerValue.Open)
    LaunchedEffect(drawerState.targetValue) {
        if (drawerState.targetValue == androidx.compose.material3.DrawerValue.Closed) onDismiss()
    }
    // 返回手势：收起 AI 侧栏而非退出页面（对齐 main Esc 关闭 Co-Pilot）
    androidx.activity.compose.BackHandler(enabled = drawerState.targetValue == androidx.compose.material3.DrawerValue.Open) {
        scope.launch { drawerState.close() }
    }
    // 生成中自动滚动到底
    LaunchedEffect(AiChatStore.entries.size, AiChatStore.entries.lastOrNull()?.text?.length) {
        if (AiChatStore.entries.isNotEmpty()) listState.animateScrollToItem(AiChatStore.entries.size - 1)
    }

    ModalNavigationDrawer(
        drawerContent = {
        ModalDrawerSheet(
            drawerState = drawerState,
            modifier = Modifier.width(380.dp)
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .fillMaxHeight()
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .imePadding()
                    .padding(horizontal = 12.dp)
            ) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(com.spice.nativeapp.core.I18n.tf("SPICE AI 智能助手 · {v1}工作区", workspace.label), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    TextButton(onClick = { AiChatStore.clearHistory(ctx) }, enabled = !AiChatStore.isGenerating) {
                        Text(com.spice.nativeapp.core.I18n.t("清空"), fontSize = 11.sp)
                    }
                }
                Text(
                    com.spice.nativeapp.core.I18n.t("回复中的 ```json 突变块与 ```dna 序列块可一键导入当前工作区"),
                    fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                TabRow(selectedTabIndex = AiChatStore.tab) {
                    Tab(selected = AiChatStore.tab == 0, onClick = { AiChatStore.tab = 0 }, text = { Text(com.spice.nativeapp.core.I18n.t("聊天"), fontSize = 13.sp) })
                    Tab(selected = AiChatStore.tab == 1, onClick = { AiChatStore.tab = 1; saveConfig(); testResult = null }, text = { Text(com.spice.nativeapp.core.I18n.t("设置"), fontSize = 13.sp) })
                }
                when (AiChatStore.tab) {
                    0 -> {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.fillMaxWidth().weight(1f),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            items(AiChatStore.entries) { e ->
                                val isUser = e.role == "user"
                                Card(
                                    colors = CardDefaults.cardColors(
                                        containerColor = if (isUser) MaterialTheme.colorScheme.surfaceContainerHigh
                                        else if (e.error) MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f)
                                        else MaterialTheme.colorScheme.surfaceContainer
                                    )
                                ) {
                                    Column(Modifier.fillMaxWidth().padding(10.dp)) {
                                        Text(if (isUser) com.spice.nativeapp.core.I18n.t("你") else "AI", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                        Text(e.text, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                                        // 工具调用痕迹（对齐 main toolNotes）
                                        e.toolNotes?.takeIf { it.isNotEmpty() }?.let { notes ->
                                            Text(com.spice.nativeapp.core.I18n.t("工具调用：") + notes.joinToString("、"), fontSize = 9.sp, color = MaterialTheme.colorScheme.primary)
                                        }
                                        if (!isUser && !e.error) {
                                            // 结构化块一键导入（对齐 main 的 parsedMutations/parsedSequence）
                                            val muts = remember(e.text) { AiBlocks.parseMutationBlocks(e.text) }
                                            val dna = remember(e.text) { AiBlocks.parseDnaBlocks(e.text) }
                                            val validated = remember(e.text, sequence) {
                                                if (muts.isEmpty()) null else AiBlocks.validateMutations(muts, sequence)
                                            }
                                            if (onImportMutations != null && validated != null && validated.valid.isNotEmpty()) {
                                                TextButton(onClick = {
                                                    onImportMutations(validated.valid.map { Triple(it.pos, it.from.first(), it.to.first()) })
                                                }) { Text(com.spice.nativeapp.core.I18n.tf("导入 {v1} 个突变到当前工作区", validated.valid.size), fontSize = 11.sp) }
                                                if (validated.errors.isNotEmpty()) {
                                                    Text(com.spice.nativeapp.core.I18n.tf("已跳过 {v1} 行：{v2}", validated.errors.size, validated.errors.first()), fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                                }
                                            }
                                            if (dna != null && onImportDna != null) {
                                                val (clean, err) = remember(dna) { AiBlocks.validateDnaSequence(dna) }
                                                if (err == null) {
                                                    TextButton(onClick = { onImportDna(clean) }) { Text(com.spice.nativeapp.core.I18n.tf("应用 DNA 序列（{v1} bp）", clean.length), fontSize = 11.sp) }
                                                } else {
                                                    Text(com.spice.nativeapp.core.I18n.tf("DNA 块无效：{v1}", err), fontSize = 9.sp, color = MaterialTheme.colorScheme.error)
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        errMsg?.let { Text(it, fontSize = 11.sp, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(vertical = 4.dp)) }
                        Row(verticalAlignment = androidx.compose.ui.Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = AiChatStore.input,
                                onValueChange = { AiChatStore.input = it },
                                label = { Text(com.spice.nativeapp.core.I18n.t("询问 SPICE AI…")) },
                                modifier = Modifier.weight(1f),
                                maxLines = 4
                            )
                            // 生成中发送按钮变「停止」（对齐 main send-btn loading → cancelGeneration）
                            Button(
                                onClick = {
                                    if (AiChatStore.isGenerating) {
                                        AiChatStore.cancelRequested = true
                                        resolveApproval(false)   // main cancelGeneration 同语义
                                    } else send()
                                },
                                modifier = Modifier.heightIn(min = 48.dp)
                            ) {
                                Text(if (AiChatStore.isGenerating) com.spice.nativeapp.core.I18n.t("停止") else com.spice.nativeapp.core.I18n.t("发送"), fontSize = 12.sp)
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                    }
                    1 -> {
                        Column(
                            Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedTextField(
                                value = endpoint, onValueChange = { endpoint = it; saveConfig() },
                                label = { Text(com.spice.nativeapp.core.I18n.t("API 地址（base URL 或完整 chat/completions）")) },
                                modifier = Modifier.fillMaxWidth(), singleLine = true
                            )
                            Text(
                                com.spice.nativeapp.core.I18n.t("兼容 OpenAI 格式端点；填 base URL（如 https://apihub.agnes-ai.com/v1/）会自动补全 /chat/completions"),
                                fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            OutlinedTextField(
                                value = model, onValueChange = { model = it; saveConfig() },
                                label = { Text(com.spice.nativeapp.core.I18n.t("模型")) },
                                modifier = Modifier.fillMaxWidth(), singleLine = true
                            )
                            OutlinedTextField(
                                value = apiKey, onValueChange = { apiKey = it; saveConfig() },
                                label = { Text(com.spice.nativeapp.core.I18n.t("API Key（可选，本地服务可留空）")) },
                                modifier = Modifier.fillMaxWidth(), singleLine = true,
                                visualTransformation = PasswordVisualTransformation()
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedTextField(
                                    value = temperature, onValueChange = { temperature = it; saveConfig() },
                                    label = { Text("temperature") }, modifier = Modifier.weight(1f), singleLine = true
                                )
                                OutlinedTextField(
                                    value = maxTokens, onValueChange = { maxTokens = it; saveConfig() },
                                    label = { Text("max_tokens") }, modifier = Modifier.weight(1f), singleLine = true
                                )
                            }
                            // 系统提示词多行编辑框（对齐 main AiCopilot.svelte textarea，默认预填充原版全文）
                            OutlinedTextField(
                                value = systemPrompt,
                                onValueChange = { systemPrompt = it; saveConfig() },
                                label = { Text(com.spice.nativeapp.core.I18n.t("系统提示词")) },
                                modifier = Modifier.fillMaxWidth().heightIn(min = 150.dp),
                                minLines = 6
                            )
                            Text(
                                com.spice.nativeapp.core.I18n.t("随每次请求作为 system 消息注入；清空则不注入"),
                                fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            // Agent 模式开关（main agent-toggle：aiAgentMode + aiAgentModeDesc）
                            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                                Switch(
                                    checked = AiChatStore.agentEnabled,
                                    onCheckedChange = { AiChatStore.setAgentEnabled(ctx, it) }
                                )
                                Text(com.spice.nativeapp.core.I18n.t("Agent 模式"), fontSize = 13.sp, modifier = Modifier.padding(start = 8.dp))
                            }
                            Text(
                                com.spice.nativeapp.core.I18n.t("允许 Co-Pilot 读取工作区数据并执行需要审批的工具；关闭后仅进行普通对话。"),
                                fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            // ── Agent 记忆面板（main memory-list：aiMemory + aiMemoryPlaceholder）──
                            var newMemory by remember { mutableStateOf("") }
                            Text(com.spice.nativeapp.core.I18n.t("Agent 记忆"), fontSize = 13.sp, fontWeight = FontWeight.Bold)
                            if (AiChatStore.memory.isEmpty()) {
                                Text(com.spice.nativeapp.core.I18n.t("（暂无记忆条目）"), fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            } else {
                                AiChatStore.memory.take(20).forEach { entry ->
                                    Row(verticalAlignment = androidx.compose.ui.Alignment.Top) {
                                        Text(
                                            entry.text,
                                            fontSize = 11.sp,
                                            modifier = Modifier.weight(1f)
                                        )
                                        TextButton(onClick = { AiChatStore.removeMemory(ctx, entry.id) }) { Text(com.spice.nativeapp.core.I18n.t("删除"), fontSize = 10.sp) }
                                    }
                                }
                            }
                            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                OutlinedTextField(
                                    value = newMemory, onValueChange = { newMemory = it },
                                    placeholder = { Text(com.spice.nativeapp.core.I18n.t("添加一条需要记住的事实…"), fontSize = 11.sp) },
                                    modifier = Modifier.weight(1f), singleLine = true
                                )
                                OutlinedButton(onClick = {
                                    if (newMemory.isNotBlank()) {
                                        AiChatStore.rememberFact(ctx, newMemory.trim(), emptyList())
                                        newMemory = ""
                                    }
                                }) { Text(com.spice.nativeapp.core.I18n.t("记住"), fontSize = 11.sp) }
                            }
                            // ── Agent 活动审计面板（main audit-list：aiAudit + aiAuditClear/aiAuditEmpty）──
                            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                                Text(com.spice.nativeapp.core.I18n.t("Agent 活动"), fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                                TextButton(onClick = { AiChatStore.clearAudit(ctx) }, enabled = AiChatStore.audit.isNotEmpty()) {
                                    Text(com.spice.nativeapp.core.I18n.t("清空日志"), fontSize = 10.sp)
                                }
                            }
                            if (AiChatStore.audit.isEmpty()) {
                                Text(com.spice.nativeapp.core.I18n.t("暂无工具调用。"), fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            } else {
                                AiChatStore.audit.take(30).forEach { entry ->
                                    Text(
                                        "[${entry.kind.name.lowercase()}] ${entry.tool} · ${entry.permission}" +
                                            (entry.approved?.let { if (it) " · approved" else " · rejected" } ?: "") +
                                            " · ${if (entry.ok) "ok" else "failed"} — ${entry.summary.take(120)}",
                                        fontSize = 10.sp, fontFamily = FontFamily.Monospace,
                                        color = if (entry.ok) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error
                                    )
                                }
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(
                                    onClick = {
                                        saveConfig(); testResult = com.spice.nativeapp.core.I18n.t("连接中…")
                                        scope.launch {
                                            val r = withContext(Dispatchers.IO) { AiClient.testConnection(config()) }
                                            testResult = if (r.ok) {
                                                if (r.models.isEmpty()) com.spice.nativeapp.core.I18n.t("连接成功（该端点未提供模型列表）")
                                                else com.spice.nativeapp.core.I18n.tf("连接成功，可用模型：{v1}", r.models.take(12).joinToString("、"))
                                            } else com.spice.nativeapp.core.I18n.tf("连接失败：{v1}", r.error)
                                        }
                                    },
                                    modifier = Modifier.heightIn(min = 48.dp)
                                ) { Text(com.spice.nativeapp.core.I18n.t("测试连接"), fontSize = 12.sp) }
                                OutlinedButton(
                                    onClick = { scope.launch { drawerState.close() } },
                                    modifier = Modifier.heightIn(min = 48.dp)
                                ) { Text(com.spice.nativeapp.core.I18n.t("收起侧栏"), fontSize = 12.sp) }
                            }
                            testResult?.let { Text(it, fontSize = 11.sp, fontFamily = FontFamily.Monospace) }
                        }
                    }
                }
            }
        }
        },
        drawerState = drawerState,
        gesturesEnabled = true,
        modifier = Modifier.fillMaxSize()
    ) {
        // content 槽位为空：侧栏作为覆盖层叠加在当前页面上（由调用方置于根 Box 末尾）
    }

    // ── 审批对话框（main approval-modal：aiApprovalTitle/Approve/Reject/Args）──
    approvalRequest?.let { request ->
        AlertDialog(
            onDismissRequest = { resolveApproval(false) },
            title = { Text(com.spice.nativeapp.core.I18n.t("批准 Agent 操作")) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(request.title, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                        Text(request.preview, fontSize = 12.sp, fontFamily = FontFamily.Monospace,
                            modifier = Modifier.padding(8.dp))
                    }
                    Text(com.spice.nativeapp.core.I18n.t("原始参数"), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    Text(
                        request.args.toString(2).take(3000),
                        fontSize = 10.sp, fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = { Button(onClick = { resolveApproval(true) }) { Text(com.spice.nativeapp.core.I18n.t("批准")) } },
            dismissButton = { OutlinedButton(onClick = { resolveApproval(false) }) { Text(com.spice.nativeapp.core.I18n.t("拒绝")) } }
        )
    }
}
