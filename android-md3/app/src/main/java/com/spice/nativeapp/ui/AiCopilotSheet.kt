package com.spice.nativeapp.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import com.spice.nativeapp.core.DEFAULT_AI_SYSTEM_PROMPT
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** AI 工作区（对齐 main aiState 的 currentWorkspace） */
enum class AiWorkspace(val label: String) { GENE("基因"), PROTEIN("蛋白") }

data class ChatEntry(val role: String, val text: String)

/**
 * AI 会话全局状态（对齐 main src/lib/state/aiState.svelte.ts 全局 store）：
 * 抽屉收回（composable 离开组合域）历史不丢；基因/蛋白两个工作区共享同一 store，
 * 与 main 的 Co-Pilot 侧栏开关不重置会话一致。
 */
object AiChatStore {
    const val WELCOME = "你好！我是 SPICE AI 智能助手。我可以帮你进行密码子优化、智能突变库设计、物理模拟数据解读（如 pH/温度相图分析），以及推荐湿实验方案。请问有什么我可以帮你的？"
    var entries by mutableStateOf(listOf(ChatEntry("assistant", WELCOME)))
    var input by mutableStateOf("")
    var tab by mutableStateOf(0)
    fun reset() {
        entries = listOf(ChatEntry("assistant", WELCOME))
        AiChatStore.input = ""
    }
}

/**
 * SPICE AI 智能助手（对齐 main 全局 Co-Pilot 侧栏）：
 * 右侧侧拉抽屉形态（对齐 main 的 settings-drawer / Co-Pilot 侧栏，可满高上下滚动查看完整对话），
 * 聊天（工作区上下文注入 + 突变/DNA 块一键导入）+ 连接配置（OpenAI 兼容端点）。
 */
@Composable
fun AiCopilotSheet(
    workspace: AiWorkspace,
    sequence: String,
    sequenceLabel: String,
    onImportDna: ((String) -> Unit)? = null,
    onImportMutations: ((List<Triple<Int, Char, Char>>) -> Unit)? = null,
    onDismiss: () -> Unit
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { ctx.getSharedPreferences("spice_settings", android.content.Context.MODE_PRIVATE) }

    // ── 配置（持久化，对齐 main spice_ai_config） ──
    var endpoint by remember { mutableStateOf(prefs.getString("ai_endpoint", "https://apihub.agnes-ai.com/v1/") ?: "") }
    var model by remember { mutableStateOf(prefs.getString("ai_model", "agnes-3.0-flash") ?: "") }
    var apiKey by remember { mutableStateOf(prefs.getString("ai_apikey", "sk-AM9V7FPao8RxrfFp8Ng7ZfsLbEu6PFjSi1gQ14r1OUtSQ6mm") ?: "") }
    var temperature by remember { mutableStateOf(prefs.getString("ai_temperature", "0.7") ?: "0.7") }
    var maxTokens by remember { mutableStateOf(prefs.getString("ai_maxtokens", "2048") ?: "2048") }
    // 系统提示词（对齐 main aiState config.systemPrompt：默认原版全文，可在设置里编辑并持久化）
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

    var busy by remember { mutableStateOf(false) }
    var errMsg by remember { mutableStateOf<String?>(null) }
    val listState = rememberLazyListState()

    fun config() = AiClient.Config(
        endpoint = endpoint,
        model = model,
        apiKey = apiKey,
        temperature = temperature.toDoubleOrNull() ?: 0.7,
        maxTokens = maxTokens.toIntOrNull() ?: 0
    )

    fun send() {
        val q = AiChatStore.input.trim()
        if (q.isEmpty() || busy) return
        AiChatStore.input = ""
        errMsg = null
        AiChatStore.entries = AiChatStore.entries + ChatEntry("user", q)
        busy = true
        val history = AiChatStore.entries.toList()
        scope.launch {
            val reply = withContext(Dispatchers.IO) {
                val ctxMsgs = buildList {
                    add(AiClient.Message("system", systemPrompt))
                    add(
                        AiClient.Message(
                            "system",
                            "当前工作区：${workspace.label}页。$sequenceLabel：${sequence.take(4000)}" +
                                if (sequence.length > 4000) "（已截断，全长 ${sequence.length}）" else ""
                        )
                    )
                    history.forEach { add(AiClient.Message(it.role, it.text)) }
                }
                AiClient.requestChat(config(), ctxMsgs)
            }
            busy = false
            AiChatStore.entries = AiChatStore.entries + ChatEntry("assistant", reply)
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
                    .padding(horizontal = 12.dp)
            ) {
                Text("SPICE AI 智能助手 · ${workspace.label}工作区", style = MaterialTheme.typography.titleMedium)
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
                                        else MaterialTheme.colorScheme.surfaceContainer
                                    )
                                ) {
                                    Column(Modifier.fillMaxWidth().padding(10.dp)) {
                                        Text(if (isUser) "你" else "AI", fontSize = 10.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                                        Text(e.text, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                                        if (!isUser) {
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
                            Button(onClick = { send() }, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp)) {
                                Text(if (busy) "…" else "发送", fontSize = 12.sp)
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
                            // 系统提示词多行编辑框（对齐 main AiCopilot.svelte:983 textarea rows=6，默认预填充原版全文）
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
