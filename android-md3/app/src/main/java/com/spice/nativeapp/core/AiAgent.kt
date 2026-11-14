package com.spice.nativeapp.core

import kotlinx.coroutines.CancellationException
import org.json.JSONObject

/**
 * 有界工具调用循环（1:1 移植 main src/lib/ai/agent.ts runAgentTurn）。
 *
 * 循环刻意保持小而可审计：每个模型步一次 chat completion，工具顺序执行，
 * write 工具必须经显式审批，每步前裁剪对话窗口。
 */
object AiAgent {

    /** main AgentApprovalRequest */
    class ApprovalRequest(
        val tool: AiTools.AgentTool,
        val title: String,
        val permission: AiTools.Permission,
        val args: JSONObject,
        val preview: String
    )

    /** main AgentToolEvent */
    class ToolEvent(
        val tool: AiTools.AgentTool,
        val approved: Boolean? = null,
        val result: AiTools.ToolResult? = null
    )

    /** main AgentToolRecord */
    data class ToolRecord(
        val name: String,
        val args: JSONObject,
        val permission: AiTools.Permission,
        val approved: Boolean?,
        val ok: Boolean,
        val summary: String,
        val undoAvailable: Boolean
    )

    /** main AgentAuditRecord（audit 回调载荷） */
    class AuditRecord(
        val kind: AiAudit.Kind,
        val tool: String,
        val permission: AiTools.Permission,
        val approved: Boolean?,
        val ok: Boolean,
        val summary: String,
        val args: Any?
    )

    class RunOptions(
        val config: AiClient.Config,
        val messages: List<AiStream.ChatMessage>,
        val tools: List<AiTools.AgentTool>,
        val context: AiTools.WorkspaceContext,
        val maxSteps: Int = 6,
        val contextTokens: Int = AiContext.DEFAULT_CONTEXT_TOKENS,
        val reserveTokens: Int = 1024,
        val isCancelled: () -> Boolean = { false },
        val requestApproval: (suspend (ApprovalRequest) -> Boolean)? = null,
        val onStepStart: ((step: Int) -> Unit)? = null,
        val onToken: ((delta: String, full: String) -> Unit)? = null,
        val onToolStart: ((ToolEvent) -> Unit)? = null,
        val onToolEnd: ((ToolEvent) -> Unit)? = null,
        val audit: ((AuditRecord) -> Unit)? = null
    )

    class RunResult(
        val content: String,
        val steps: Int,
        val toolCalls: List<ToolRecord>,
        val toolEvents: List<ToolEvent>,
        val messages: List<AiStream.ChatMessage>,
        val stoppedReason: String   // "complete" | "max-steps"
    )

    /** main parseArguments：参数必须是 JSON 对象（或空串→空对象） */
    internal fun parseArguments(raw: String): JSONObject? {
        val text = raw.trim()
        if (text.isEmpty()) return JSONObject()
        return try {
            val parsed = JSONObject(text)
            parsed
        } catch (e: org.json.JSONException) {
            null
        }
    }

    /** main toolResultPayload：{ok,summary,data} 截 8000 字符回注 tool 消息 */
    internal fun toolResultPayload(result: AiTools.ToolResult): String {
        val json = JSONObject().apply {
            put("ok", result.ok)
            put("summary", result.summary)
            put("data", result.data ?: JSONObject.NULL)
        }
        val text = json.toString()
        return if (text.length <= 8000) text else text.substring(0, 8000)
    }

    /** main unknownTool：未知工具名 → 恒失败占位工具 */
    private fun unknownTool(name: String): AiTools.AgentTool = AiTools.AgentTool(
        name = name,
        description = "Unknown tool",
        parameters = JSONObject(),
        permission = AiTools.Permission.READ,
        execute = { _, _ -> AiTools.ToolResult(false, "Unknown tool: $name") }
    )

    /** 执行一个用户轮：最多 maxSteps 次模型调用（suspend，网络在调用方 IO 上下文） */
    suspend fun runAgentTurn(options: RunOptions): RunResult {
        val maxSteps = maxOf(1, options.maxSteps)
        val conversation = options.messages.toMutableList()
        val records = mutableListOf<ToolRecord>()
        val toolEvents = mutableListOf<ToolEvent>()
        val definitions = options.tools.map { it.toDefinition() }
        // 工具 schema 随每次请求传输，同样占用上下文预算
        val toolTokens = definitions.sumOf { AiContext.estimateTokens(it.parameters.toString() + it.description + it.name) + 8 }
        var lastContent = ""

        for (step in 1..maxSteps) {
            if (options.isCancelled()) {
                throw AiClient.AiRequestException(AiClient.Kind.NETWORK, "request cancelled")
            }

            options.onStepStart?.invoke(step - 1)

            val trimmed = AiContext.trimAgentConversation(
                conversation,
                options.contextTokens,
                (options.reserveTokens) + toolTokens
            )
            val result = AiStream.streamChatCompletion(
                config = options.config,
                messages = trimmed,
                tools = definitions,
                toolChoice = "auto",
                onToken = options.onToken,
                isCancelled = options.isCancelled
            )
            lastContent = result.content

            if (result.toolCalls.isEmpty()) {
                return RunResult(result.content, step, records, toolEvents, conversation.toList(), "complete")
            }

            conversation += AiStream.ChatMessage(
                role = "assistant",
                content = result.content,
                toolCalls = result.toolCalls
            )

            for (call in result.toolCalls) {
                val name = call.name
                val tool = options.tools.firstOrNull { it.name == name } ?: unknownTool(name)
                val args = parseArguments(call.arguments)
                val permission = tool.permission

                fun finish(approved: Boolean?, toolResult: AiTools.ToolResult) {
                    records += ToolRecord(name, args ?: JSONObject(), permission, approved, toolResult.ok, toolResult.summary, toolResult.undoAvailable)
                    val event = ToolEvent(tool, approved, toolResult)
                    toolEvents += event
                    options.onToolEnd?.invoke(event)
                    if (permission == AiTools.Permission.WRITE) {
                        if (approved != true) {
                            options.audit?.invoke(AuditRecord(AiAudit.Kind.APPROVAL, name, permission, false, false, toolResult.summary, AiAudit.redactForAudit(args ?: JSONObject())))
                        } else {
                            options.audit?.invoke(AuditRecord(AiAudit.Kind.APPROVAL, name, permission, true, true, "Approved by user", AiAudit.redactForAudit(args ?: JSONObject())))
                            options.audit?.invoke(AuditRecord(AiAudit.Kind.APPLIED, name, permission, true, toolResult.ok, toolResult.summary, AiAudit.redactForAudit(args ?: JSONObject())))
                        }
                    } else {
                        options.audit?.invoke(AuditRecord(AiAudit.Kind.TOOL, name, permission, approved, toolResult.ok, toolResult.summary, AiAudit.redactForAudit(args ?: JSONObject())))
                    }
                    conversation += AiStream.ChatMessage(
                        role = "tool",
                        toolCallId = call.id,
                        name = name,
                        content = toolResultPayload(toolResult)
                    )
                }

                options.onToolStart?.invoke(ToolEvent(tool))

                if (args == null) {
                    finish(false, AiTools.ToolResult(false, "Invalid arguments: tool arguments must be a JSON object"))
                    continue
                }

                var approved: Boolean? = null
                if (permission == AiTools.Permission.WRITE) {
                    approved = options.requestApproval?.invoke(
                        ApprovalRequest(
                            tool = tool,
                            title = "Approve $name?",
                            permission = permission,
                            args = args,
                            preview = tool.preview?.invoke(args, options.context) ?: args.toString(2)
                        )
                    ) ?: false
                    if (approved != true) {
                        finish(false, AiTools.ToolResult(false, "The user rejected this action."))
                        continue
                    }
                }

                val toolResult = try {
                    tool.execute(args, options.context)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    AiTools.ToolResult(false, e.message ?: e.toString())
                }
                finish(approved, toolResult)
            }
        }

        return RunResult(lastContent, maxSteps, records, toolEvents, conversation.toList(), "max-steps")
    }
}
