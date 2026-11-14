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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.spice.nativeapp.core.AiBlocks
import com.spice.nativeapp.core.AiClient
import com.spice.nativeapp.core.AiContext
import com.spice.nativeapp.core.AiStream
import com.spice.nativeapp.core.DEFAULT_AI_SYSTEM_PROMPT
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

/** 对话消息（对齐 main ChatMessage：错误注记不回放为历史，toolNotes 记录本条回复用过的工具名） */
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
    private var loaded = false
    @Volatile var cancelRequested = false

    fun stamp(): String = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())

    /** 冷启动恢复（在首个组合点调用一次） */
    fun ensureLoaded(ctx: Context) {
        if (loaded) return
        loaded = true
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

    /** 清空历史（对齐 main clearHistory → copilotHistoryClearedIntro） */
    fun clearHistory(ctx: Context) {
        entries = listOf(ChatMsg(System.currentTimeMillis(), "assistant",
            "已清除对话历史。你可以输入任何有关蛋白质设计、突变、克隆或模拟的问题。", stamp()))
        input = ""
        persist(ctx)
    }

    /** 按 id 更新最后一条 assistant 消息文本（流式打字机逐 delta 更新） */
    fun updateLast(id: Long, text: String, toolNotes: List<String>? = null) {
        entries = entries.map { if (it.id == id) it.copy(text = text, toolNotes = toolNotes ?: it.toolNotes) else it }
    }
}

// ───────────────────────── 上下文注入（main AiCopilot.svelte buildContextPrompt，文案 zh.json 原文） ─────────────────────────

/**
 * 当前会话上下文提示词（对齐 main buildContextPrompt）。
 * 文案逐字取自 main project.inlang/messages/zh.json：
 * copilotCtxHeader / copilotCtxActivePage / copilotCtxWorkspaceRules /
 * copilotCtxProteinSeq / copilotCtxPhysicalEnv / copilotCtxPlasmidName /
 * copilotCtxDnaLengthGc / copilotCtxCodonHostPref / copilotCtxUnnamed / copilotCtxHostEcoli
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
    val sb = StringBuilder("【当前 GUI 会话上下文（实时状态）】\n")
    sb.append("- 当前活动页面/工作区: ${if (workspace == AiWorkspace.PROTEIN) "蛋白验证" else "基因设计编辑器 (Gene Designer)"}\n")
    sb.append("工作区专属限制指令】:\n\n")
    if (workspace == AiWorkspace.PROTEIN && sequence.isNotEmpty()) {
        val head = if (sequence.length > 50) sequence.take(50) + "..." else sequence
        sb.append("- 当前研究蛋白质序列: $head (长度: ${sequence.length} aa)\n")
        if (envPh != null && envTempK != null) {
            sb.append("- 当前物理环境: pH=${"%.1f".format(envPh)}, Temp=${envTempK.toInt()} K (${"%.1f".format(envTempK - 273.15)}°C), 离子强度=${envIonic ?: 0.0} M\n")
        }
        if (!metricsText.isNullOrEmpty()) sb.append("- 当前物理指标: $metricsText\n")
    }
    if (workspace == AiWorkspace.GENE && sequence.isNotEmpty()) {
        sb.append("- 当前质粒 DNA 名称: ${plasmidName.ifEmpty { "未命名" }}\n")
        val gc = gcPercent ?: runCatching { sequence.count { it in "GCgc" }.toDouble() / sequence.length * 100 }.getOrDefault(0.0)
        sb.append("- 当前 DNA 序列长度: ${sequence.length} bp, GC含量: ${"%.1f".format(gc)}%\n")
        sb.append("- 当前优化宿主偏好: 大肠杆菌 (E. coli)\n")
    }
    return sb.toString()
}

/**
 * SPICE AI 智能助手（对齐 main 全局 Co-Pilot 侧栏 AiCopilot.svelte）：
 * 右侧侧拉抽屉 + 流式打字机（SSE stream:true，对齐 main streamChat/onToken）+
 * 跨会话历史（main spice_ai_messages）+ 突变/DNA 块一键导入。
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

    fun config() = AiClient.Config(
        endpoint = endpoint,
        model = model,
        apiKey = apiKey,
        temperature = temperature.toDoubleOrNull() ?: 0.7,
        maxTokens = maxTokens.toIntOrNull() ?: 0
    )

    // main describeAiError → zh.json copilotError* 原文
    fun describeError(e: AiClient.AiRequestException): String = when (e.kind) {
        AiClient.Kind.AUTH -> "认证失败，请检查 API 密钥。"
        AiClient.Kind.RATE_LIMIT -> "请求过于频繁，请稍后再试。"
        AiClient.Kind.SERVER -> "AI 服务返回错误。"
        AiClient.Kind.TIMEOUT -> "AI 请求超时。"
        AiClient.Kind.NETWORK -> "无法连接 AI 服务，请检查端点是否正在运行。"
        else -> e.message ?: "未知错误"
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
        // 「正在思考中...」占位（zh copilotThinking），流式 delta 到达后逐字替换
        val assistantId = now + 1
        AiChatStore.entries = AiChatStore.entries + ChatMsg(assistantId, "assistant", "正在思考中...", AiChatStore.stamp())

        // 历史组装（对齐 main sendMessage）：排除欢迎条、错误注记与占位；取最近 40 条
        val history = AiChatStore.entries
            .filter { it.id != 1L && !it.error && !(it.role == "assistant" && it.text == "正在思考中...") }
            .dropLast(2) // 去掉本轮 user + 占位
            .takeLast(40)
            .map { AiStream.ChatMessage(it.role, it.text) }

        val contextPrompt = buildContextPrompt(workspace, sequence, sequenceLabel, plasmidName, gcPercent, envPh, envTempK, envIonic, metricsText)
        // zh.json aiPromptWithContextWrap 原文
        val userContent = "$contextPrompt\n用户输入: $q"

        val payload = AiContext.buildChatPayload(
            systemPrompt = systemPrompt,
            history = history,
            userContent = userContent,
            reserveTokens = maxTokens.toIntOrNull() ?: 2048
        )

        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    AiStream.streamChatCompletion(
                        config = config(),
                        messages = payload.messages,
                        onToken = { _, full -> AiChatStore.updateLast(assistantId, full) },
                        isCancelled = { AiChatStore.cancelRequested }
                    )
                }
                AiChatStore.updateLast(assistantId, result.content)
            } catch (e: AiStream.CancelledException) {
                // 对齐 main：已收到的部分保留 + 「已取消生成。」注记
                val partial = AiChatStore.entries.lastOrNull { it.id == assistantId }?.text ?: ""
                val text = if (partial.isNotEmpty() && partial != "正在思考中...") "$partial\n\n_已取消生成。_" else "已取消生成。"
                AiChatStore.entries = AiChatStore.entries.map { if (it.id == assistantId) it.copy(text = text, error = true) else it }
            } catch (e: AiClient.AiRequestException) {
                errMsg = "AI 请求失败：${describeError(e)}"
                AiChatStore.entries = AiChatStore.entries.filter { it.id != assistantId } +
                    ChatMsg(assistantId, "assistant", "AI 请求失败：${describeError(e)}", AiChatStore.stamp(), error = true)
            } finally {
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
    // 生成中自动滚动到底（main 每 12 个 token 滚一次；此处每 delta 请求滚动）
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
                    .imePadding()   // 键盘弹出时抽屉随输入法上移，输入框与发送按钮保持可见
                    .padding(horizontal = 12.dp)
            ) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("SPICE AI 智能助手 · ${workspace.label}工作区", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    TextButton(onClick = { AiChatStore.clearHistory(ctx) }, enabled = !AiChatStore.isGenerating) {
                        Text("清空", fontSize = 11.sp)
                    }
                }
                Text(
                    "回复中的 ```json 突变块与 ```dna 序列块可一键导入当前工作区",
                    fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                TabRow(selectedTabIndex = AiChatStore.tab) {
                    Tab(selected = AiChatStore.tab == 0, onClick = { AiChatStore.tab = 0 }, text = { Text("聊天", fontSize = 13.sp) })
                    Tab(selected = AiChatStore.tab == 1, onClick = { AiChatStore.tab = 1; saveConfig(); testResult = null }, text = { Text("设置", fontSize = 13.sp) })
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
                                        Text(if (isUser) "你" else "AI", fontSize = 10.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                                        Text(e.text, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                                        // 工具调用痕迹（对齐 main toolNotes）
                                        e.toolNotes?.takeIf { it.isNotEmpty() }?.let { notes ->
                                            Text("工具调用：" + notes.joinToString("、"), fontSize = 9.sp, color = MaterialTheme.colorScheme.primary)
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
                                                }) { Text("导入 ${validated.valid.size} 个突变到当前工作区", fontSize = 11.sp) }
                                                if (validated.errors.isNotEmpty()) {
                                                    Text("已跳过 ${validated.errors.size} 行：${validated.errors.first()}", fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                                }
                                            }
                                            if (dna != null && onImportDna != null) {
                                                val (clean, err) = remember(dna) { AiBlocks.validateDnaSequence(dna) }
                                                if (err == null) {
                                                    TextButton(onClick = { onImportDna(clean) }) { Text("应用 DNA 序列（${clean.length} bp）", fontSize = 11.sp) }
                                                } else {
                                                    Text("DNA 块无效：$err", fontSize = 9.sp, color = MaterialTheme.colorScheme.error)
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
                                label = { Text("询问 SPICE AI…") },
                                modifier = Modifier.weight(1f),
                                maxLines = 4
                            )
                            // 生成中发送按钮变「取消」（对齐 main send-btn loading → cancelGeneration）
                            Button(
                                onClick = {
                                    if (AiChatStore.isGenerating) AiChatStore.cancelRequested = true else send()
                                },
                                modifier = Modifier.heightIn(min = 48.dp)
                            ) {
                                Text(if (AiChatStore.isGenerating) "停止" else "发送", fontSize = 12.sp)
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
                                label = { Text("API 地址（base URL 或完整 chat/completions）") },
                                modifier = Modifier.fillMaxWidth(), singleLine = true
                            )
                            Text(
                                "兼容 OpenAI 格式端点；填 base URL（如 https://apihub.agnes-ai.com/v1/）会自动补全 /chat/completions",
                                fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            OutlinedTextField(
                                value = model, onValueChange = { model = it; saveConfig() },
                                label = { Text("模型") },
                                modifier = Modifier.fillMaxWidth(), singleLine = true
                            )
                            OutlinedTextField(
                                value = apiKey, onValueChange = { apiKey = it; saveConfig() },
                                label = { Text("API Key（可选，本地服务可留空）") },
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
                                label = { Text("系统提示词") },
                                modifier = Modifier.fillMaxWidth().heightIn(min = 150.dp),
                                minLines = 6
                            )
                            Text(
                                "随每次请求作为 system 消息注入；清空则不注入",
                                fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(
                                    onClick = {
                                        saveConfig(); testResult = "连接中…"
                                        scope.launch {
                                            val r = withContext(Dispatchers.IO) { AiClient.testConnection(config()) }
                                            testResult = if (r.ok) {
                                                if (r.models.isEmpty()) "连接成功（该端点未提供模型列表）"
                                                else "连接成功，可用模型：${r.models.take(12).joinToString("、")}"
                                            } else "连接失败：${r.error}"
                                        }
                                    },
                                    modifier = Modifier.heightIn(min = 48.dp)
                                ) { Text("测试连接", fontSize = 12.sp) }
                                OutlinedButton(
                                    onClick = { scope.launch { drawerState.close() } },
                                    modifier = Modifier.heightIn(min = 48.dp)
                                ) { Text("收起侧栏", fontSize = 12.sp) }
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
}
