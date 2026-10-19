package com.profer.pocket.data.protocol

import com.profer.pocket.model.SessionSummary
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * 协议编解码：与桌面 remote-service 的 WS 帧一一对应。
 *
 * 解析一律宽松：字段缺失、类型不符、坏 JSON 都返回 null 或降级值，绝不抛出到连接循环。
 */
object FrameCodec {

    // ===== 入站 =====

    fun parseInbound(raw: String): InboundFrame? {
        val json = try {
            JSONObject(raw)
        } catch (_: JSONException) {
            return null
        }
        return try {
            when (val kind = json.optString("kind", "")) {
                "hello" -> InboundFrame.Hello(
                    serverTime = json.optLong("serverTime", 0L),
                    serverInstanceId = json.optStringOrNull("serverInstanceId"),
                    latestEventId = json.optLongOrNull("latestAgentEventId"),
                    oldestEventId = json.optLongOrNull("oldestAgentEventId"),
                )

                "pong" -> InboundFrame.Pong

                "agent_event" -> {
                    val sessionId = json.optString("sessionId", "")
                    if (sessionId.isBlank()) {
                        InboundFrame.Unknown(kind)
                    } else {
                        InboundFrame.AgentEvent(
                            eventId = json.optLongOrNull("eventId"),
                            sessionId = sessionId,
                            payload = parsePayload(json.optJSONObject("payload")),
                        )
                    }
                }

                "agent_events_resumed" -> InboundFrame.AgentEventsResumed(
                    requestId = json.optStringOrNull("requestId"),
                    replayed = json.optInt("replayed", 0),
                    complete = json.optBoolean("complete", false),
                    requiresSnapshot = json.optBoolean("requiresSnapshot", false),
                    latestEventId = json.optLongOrNull("latestEventId"),
                    oldestEventId = json.optLongOrNull("oldestEventId"),
                )

                "chat_event" -> InboundFrame.ChatEvent(
                    conversationId = json.optString("conversationId", ""),
                    channel = json.optString("channel", ""),
                    payload = json.optJSONObject("payload"),
                )

                "command_result" -> {
                    val data: Any? = json.opt("data")
                    InboundFrame.CommandResult(
                        requestId = json.optStringOrNull("requestId"),
                        commandId = json.optStringOrNull("commandId"),
                        ok = json.optBoolean("ok", false),
                        error = json.optStringOrNull("error"),
                        conflictCode = json.optJSONObject("conflict")?.optString("code")?.takeIf { it.isNotBlank() },
                        data = data,
                        raw = json,
                    )
                }

                else -> InboundFrame.Unknown(kind)
            }
        } catch (_: JSONException) {
            null
        }
    }

    fun parsePayload(payload: JSONObject?): AgentPayload {
        if (payload == null) return AgentPayload.Ignored
        return try {
            when (val kind = payload.optString("kind", "")) {
                "sdk_message" -> AgentPayload.SdkMessage(parseSdkMessage(payload.optJSONObject("message")))
                "profer_event" -> AgentPayload.ProferEvent(parseProferEvent(payload.optJSONObject("event")))
                "run_error" -> AgentPayload.RunError(payload.optStringOrNull("error"))
                "session_projection" -> parseProjection(payload)
                else -> AgentPayload.Ignored
            }
        } catch (_: JSONException) {
            AgentPayload.Ignored
        }
    }

    fun parseSdkMessage(message: JSONObject?): SdkMessageView {
        if (message == null) {
            return SdkMessageView(
                type = "",
                uuid = null,
                createdAt = null,
                partial = false,
                text = "",
                toolUses = emptyList(),
                toolResults = emptyList(),
                subtype = null,
                model = null,
                isError = false,
                errorMessage = null,
            )
        }
        val inner = message.optJSONObject("message")
        val content = inner?.opt("content") ?: message.opt("content")
        val text = StringBuilder()
        val tools = mutableListOf<ToolUseView>()
        val toolResults = mutableListOf<ToolResultView>()
        when (content) {
            is String -> if (content.isNotBlank()) text.append(content)
            is JSONArray -> {
                for (index in 0 until content.length()) {
                    val block = content.optJSONObject(index) ?: continue
                    when (block.optString("type")) {
                        "text" -> text.append(block.optString("text", ""))
                        "tool_use" -> tools += ToolUseView(
                            id = block.optStringOrNull("id"),
                            name = block.optString("name", "").ifBlank { "tool" },
                            filePath = block.optJSONObject("input")
                                ?.optStringOrNull("file_path")
                                ?.takeIf { it.isNotBlank() },
                        )
                        "tool_result" -> block.optStringOrNull("tool_use_id")?.let { id ->
                            toolResults += ToolResultView(
                                toolUseId = id,
                                isError = block.optBoolean("is_error", false),
                            )
                        }
                    }
                }
            }
        }
        val errorObject = message.optJSONObject("error")
        val usageObject = inner?.optJSONObject("usage") ?: message.optJSONObject("usage")
        return SdkMessageView(
            type = message.optString("type", ""),
            uuid = message.optStringOrNull("uuid"),
            createdAt = if (message.has("_createdAt")) message.optLong("_createdAt").takeIf { it > 0 } else null,
            partial = message.optBoolean("_partial", false),
            text = text.toString(),
            toolUses = tools,
            toolResults = toolResults,
            subtype = message.optStringOrNull("subtype"),
            model = inner?.optStringOrNull("model") ?: message.optStringOrNull("model"),
            isError = message.optBoolean("is_error", false) || inner?.optBoolean("is_error", false) == true,
            errorMessage = errorObject?.optStringOrNull("message"),
            usage = usageObject?.let {
                TokenUsage(
                    inputTokens = it.optInt("input_tokens", 0),
                    outputTokens = it.optInt("output_tokens", 0),
                    cacheReadInputTokens = it.optInt("cache_read_input_tokens", 0),
                    cacheCreationInputTokens = it.optInt("cache_creation_input_tokens", 0),
                )
            }?.takeIf { it.usedTokens > 0 },
            modelUsage = message.optJSONObject("modelUsage")?.let { map ->
                buildMap {
                    for (key in map.keys()) {
                        val window = map.optJSONObject(key)?.optInt("contextWindow", 0) ?: 0
                        if (window > 0) put(key, window)
                    }
                }
            }.orEmpty(),
        )
    }

    fun parseProferEvent(event: JSONObject?): ProferEventView {
        if (event == null) return ProferEventView.Ignored
        val type = event.optString("type", "")
        return try {
            when (type) {
                "permission_request" -> {
                    val request = event.optJSONObject("request") ?: return ProferEventView.Ignored
                    ProferEventView.PermissionRequest(
                        requestId = request.optString("requestId", ""),
                        sessionId = request.optStringOrNull("sessionId"),
                        toolName = request.optString("toolName", ""),
                        description = request.optString("description", ""),
                        command = request.optStringOrNull("command"),
                        dangerLevel = request.optStringOrNull("dangerLevel"),
                    )
                }

                "permission_resolved" -> ProferEventView.PermissionResolved(
                    requestId = event.optString("requestId", ""),
                    behavior = event.optStringOrNull("behavior"),
                )

                "ask_user", "ask_user_request" -> {
                    val request = event.optJSONObject("request") ?: return ProferEventView.Ignored
                    ProferEventView.AskUserRequest(
                        requestId = request.optString("requestId", event.optString("requestId", "")),
                        sessionId = request.optStringOrNull("sessionId"),
                        questions = parseAskQuestions(request),
                    )
                }

                "ask_user_resolved" -> ProferEventView.AskUserResolved(event.optString("requestId", ""))

                "exit_plan_mode", "exit_plan_mode_request" -> {
                    val request = event.optJSONObject("request") ?: return ProferEventView.Ignored
                    val input = request.optJSONObject("toolInput")
                    ProferEventView.ExitPlanModeRequest(
                        requestId = request.optString("requestId", event.optString("requestId", "")),
                        sessionId = request.optStringOrNull("sessionId"),
                        summary = input?.optStringOrNull("plan")
                            ?: input?.optStringOrNull("summary")
                            ?: request.optStringOrNull("summary"),
                    )
                }

                "exit_plan_mode_resolved" -> ProferEventView.ExitPlanModeResolved(event.optString("requestId", ""))

                "run_completed" -> ProferEventView.RunCompleted(
                    sessionId = event.optStringOrNull("sessionId"),
                    stoppedByUser = event.optBoolean("stoppedByUser", false),
                    backgroundTasksPending = event.optBoolean("backgroundTasksPending", false),
                    resultErrors = event.optJSONArray("resultErrors")?.toStringList().orEmpty(),
                    endReason = event.optStringOrNull("endReason"),
                )

                "run_idle" -> ProferEventView.RunIdle(event.optStringOrNull("sessionId"))

                "title_updated" -> ProferEventView.TitleUpdated(event.optString("title", ""))

                "session_updated" -> event.optJSONObject("session")?.let {
                    ProferEventView.SessionUpdated(parseSessionSummary(it))
                } ?: ProferEventView.Ignored

                "session_deleted" -> ProferEventView.SessionDeleted(event.optString("sessionId", ""))

                "permission_mode_changed" -> ProferEventView.PermissionModeChanged(event.optString("mode", ""))

                "model_resolved" -> ProferEventView.ModelResolved(event.optString("model", ""))

                "context_window" -> ProferEventView.ContextWindow(event.optInt("contextWindow", 0))

                "external_run_started", "run_resumed" -> ProferEventView.ExternalRunStarted(
                    sessionId = event.optStringOrNull("sessionId"),
                    title = event.optStringOrNull("title"),
                )

                else -> ProferEventView.Ignored
            }
        } catch (_: JSONException) {
            ProferEventView.Ignored
        }
    }

    fun parseAskQuestions(request: JSONObject?): List<com.profer.pocket.model.AskQuestion> {
        val array = request?.optJSONArray("questions") ?: return emptyList()
        return buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val question = item.optString("question", "").trim()
                if (question.isEmpty()) continue
                val options = item.optJSONArray("options")?.let { optionArray ->
                    buildList {
                        for (optionIndex in 0 until optionArray.length()) {
                            val option = optionArray.optJSONObject(optionIndex) ?: continue
                            val label = option.optString("label", "").trim()
                            if (label.isEmpty()) continue
                            add(
                                com.profer.pocket.model.AskOption(
                                    label = label,
                                    description = option.optStringOrNull("description"),
                                ),
                            )
                        }
                    }
                }.orEmpty()
                add(
                    com.profer.pocket.model.AskQuestion(
                        question = question,
                        header = item.optStringOrNull("header"),
                        options = options,
                        multiSelect = item.optBoolean("multiSelect", false),
                    ),
                )
            }
        }
    }

    fun parseSessionSummary(session: JSONObject): SessionSummary = SessionSummary(
        id = session.optString("id", ""),
        title = session.optString("title", ""),
        workspaceId = session.optStringOrNull("workspaceId"),
        channelId = session.optStringOrNull("channelId"),
        modelId = session.optStringOrNull("modelId"),
        presetId = session.optStringOrNull("presetId"),
        permissionMode = session.optStringOrNull("permissionMode"),
        createdAt = session.optLong("createdAt", 0L),
        updatedAt = session.optLong("updatedAt", 0L),
        revision = session.optInt("revision", 0),
        active = session.optBooleanOrNull("active"),
        // 未读的事实源是桌面 completedButUnconfirmed；旧服务端才用 unread 字面字段。
        unread = session.optBooleanOrNull("completedButUnconfirmed")
            ?: session.optBooleanOrNull("unread"),
        archived = session.optBooleanOrNull("archived"),
        pinned = session.optBooleanOrNull("pinned"),
        draft = session.optBooleanOrNull("draft"),
    )

    fun parseSessionList(data: Any?): List<SessionSummary> {
        val array = data as? JSONArray ?: return emptyList()
        return buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val summary = parseSessionSummary(item)
                if (summary.id.isNotBlank()) add(summary)
            }
        }
    }

    /**
     * 单条会话响应（rename/pin/archive/move 返回 buildSessionItem）；
     * 兼容 create_session 的扁平返回与带 `session` 子对象的包装。
     */
    fun parseSessionItem(data: Any?): SessionSummary? {
        val json = data as? JSONObject ?: return null
        val payload = json.optJSONObject("session") ?: json
        val summary = parseSessionSummary(payload)
        return if (summary.id.isBlank()) null else summary
    }

    /** 新建会话响应：兼容 {sessionId,title} 与扁平/包装的完整 item。 */
    fun parseCreatedSession(data: Any?): SessionSummary? {
        val json = data as? JSONObject ?: return null
        val item = (json.optJSONObject("session") ?: json).let { parseSessionSummary(it) }
        val id = item.id.takeIf { it.isNotBlank() } ?: json.optString("sessionId", "")
        if (id.isBlank()) return null
        return item.copy(
            id = id,
            title = item.title.ifBlank { json.optString("title", "") },
        )
    }

    fun parseChannelList(data: Any?): List<com.profer.pocket.model.ChannelSummary> {
        val array = data as? JSONArray ?: return emptyList()
        return buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val id = item.optString("id", "")
                if (id.isBlank()) continue
                add(
                    com.profer.pocket.model.ChannelSummary(
                        id = id,
                        name = item.optString("name", ""),
                        provider = item.optStringOrNull("provider"),
                        enabled = item.optBoolean("enabled", true),
                        models = parseChannelModels(item.optJSONArray("models")),
                    ),
                )
            }
        }
    }

    private fun parseChannelModels(array: JSONArray?): List<com.profer.pocket.model.ChannelModel> {
        if (array == null) return emptyList()
        return buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val id = item.optString("id", "")
                if (id.isBlank()) continue
                add(
                    com.profer.pocket.model.ChannelModel(
                        id = id,
                        name = item.optString("name", ""),
                    ),
                )
            }
        }
    }

    fun parsePresetList(data: Any?): List<com.profer.pocket.model.AgentPresetSummary> {
        val array = data as? JSONArray ?: return emptyList()
        return buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val id = item.optString("id", "")
                if (id.isBlank()) continue
                add(
                    com.profer.pocket.model.AgentPresetSummary(
                        id = id,
                        name = item.optString("name", ""),
                        description = item.optString("description", ""),
                        scope = item.optStringOrNull("scope"),
                        isBuiltin = item.optBoolean("isBuiltin", false),
                        enabledInWorkspace = item.optBooleanOrNull("enabledInWorkspace"),
                        effort = item.optStringOrNull("effort"),
                        permissionMode = item.optStringOrNull("permissionMode"),
                    ),
                )
            }
        }
    }

    fun parseChatToolList(data: Any?): List<com.profer.pocket.model.ChatToolSummary> {
        val array = data as? JSONArray ?: return emptyList()
        return buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val id = item.optString("id", "")
                if (id.isBlank()) continue
                add(
                    com.profer.pocket.model.ChatToolSummary(
                        id = id,
                        name = item.optString("name", ""),
                        description = item.optString("description", ""),
                        icon = item.optStringOrNull("icon"),
                        category = item.optString("category", "builtin"),
                        enabled = item.optBoolean("enabled", false),
                        available = item.optBoolean("available", true),
                    ),
                )
            }
        }
    }

    fun parseWorkspaceList(data: Any?): List<com.profer.pocket.model.WorkspaceSummary> {
        val array = data as? JSONArray ?: return emptyList()
        return buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val id = item.optString("id", "")
                if (id.isBlank()) continue
                add(
                    com.profer.pocket.model.WorkspaceSummary(
                        id = id,
                        name = item.optString("name", ""),
                        slug = item.optStringOrNull("slug"),
                        type = item.optStringOrNull("type"),
                        createdAt = item.optLong("createdAt", 0L),
                        updatedAt = item.optLong("updatedAt", 0L),
                    ),
                )
            }
        }
    }

    /** 单条工作区响应（create_workspace 返回扁平对象）。 */
    fun parseWorkspaceItem(data: Any?): com.profer.pocket.model.WorkspaceSummary? {
        val json = data as? JSONObject ?: return null
        val item = json.optJSONObject("workspace") ?: json
        val id = item.optString("id", "")
        if (id.isBlank()) return null
        return com.profer.pocket.model.WorkspaceSummary(
            id = id,
            name = item.optString("name", ""),
            slug = item.optStringOrNull("slug"),
            type = item.optStringOrNull("type"),
            archived = item.optBooleanOrNull("archived") == true,
            createdAt = item.optLong("createdAt", 0L),
            updatedAt = item.optLong("updatedAt", 0L),
        )
    }

    /** get_sdk_messages 返回数组或分页结构 { messages: [...] }。 */
    fun sdkMessagesFrom(data: Any?): List<SdkMessageView> {
        val array = when (data) {
            is JSONArray -> data
            is JSONObject -> data.optJSONArray("messages")
            else -> null
        } ?: return emptyList()
        return buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                add(parseSdkMessage(item))
            }
        }
    }

    /** session_detail 返回 { meta: {...}, messages: [...] }。 */
    fun sessionMetaFrom(data: Any?): SessionSummary? {
        val json = data as? JSONObject ?: return null
        val meta = json.optJSONObject("meta") ?: return null
        val summary = parseSessionSummary(meta)
        return if (summary.id.isBlank()) null else summary
    }

    // ===== 出站 =====

    fun command(type: String, cmdId: String, build: JSONObject.() -> Unit = {}): String {
        val json = JSONObject()
        json.put("type", type)
        json.put("_cmdId", cmdId)
        json.build()
        return json.toString()
    }
    fun ping(): String = JSONObject().put("type", "ping").toString()

    fun resume(cmdId: String, cursor: Long?): String = JSONObject().apply {
        put("type", "resume_agent_events")
        put("_cmdId", cmdId)
        if (cursor != null && cursor > 0) put("cursor", cursor) else put("cursor", JSONObject.NULL)
    }.toString()

    fun sendMessage(
        cmdId: String,
        clientMessageId: String,
        sessionId: String,
        userMessage: String,
        channelId: String,
        modelId: String?,
        workspaceId: String?,
        uuid: String?,
        startedAt: Long,
    ): String = JSONObject().apply {
        put("type", "send_message")
        put("_cmdId", cmdId)
        put("sessionId", sessionId)
        put("userMessage", userMessage)
        put("channelId", channelId)
        if (modelId != null) put("modelId", modelId)
        if (workspaceId != null) put("workspaceId", workspaceId)
        if (uuid != null) put("uuid", uuid)
        put("startedAt", startedAt)
        put("clientMessageId", clientMessageId)
    }.toString()

    private fun parseProjection(payload: JSONObject): AgentPayload {
        val operation = payload.optString("operation", "")
        val session = payload.optJSONObject("session")?.let { parseSessionSummary(it) }
        return AgentPayload.SessionProjection(
            operation = operation,
            session = session,
            sessionId = payload.optStringOrNull("sessionId"),
            revision = maxOf(
                payload.optInt("revision", 0),
                session?.revision ?: 0,
            ),
        )
    }
}

internal fun JSONObject.optStringOrNull(key: String): String? =
    if (!has(key) || isNull(key)) null else optString(key).takeIf { it.isNotBlank() }

internal fun JSONObject.optLongOrNull(key: String): Long? =
    if (!has(key) || isNull(key)) null else optLong(key, 0L)

internal fun JSONObject.optBooleanOrNull(key: String): Boolean? =
    if (!has(key) || isNull(key)) null else optBoolean(key, false)

private inline fun JSONObject.build(block: JSONObject.() -> Unit = {}) {
    block()
}
