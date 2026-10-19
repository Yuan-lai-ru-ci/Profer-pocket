package com.profer.pocket.app.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.profer.pocket.app.AppForeground
import com.profer.pocket.app.BuildConfig
import com.profer.pocket.app.ProferApplication
import com.profer.pocket.data.PocketRepository
import com.profer.pocket.model.ChatEntry
import com.profer.pocket.model.ConnectionStatus
import com.profer.pocket.model.PendingInteraction
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import java.io.File

/**
 * 仅 debug 构建存在的 adb 调试入口。
 *
 * 用法（token 只从应用私有目录文件读取，不接受命令行明文 token，避免进日志）：
 *   adb shell am broadcast -a com.profer.pocket.DEBUG \
 *     --es cmd connect --es endpoint http://10.0.2.2:7789 --es token_file debug-token
 *   adb shell am broadcast -a com.profer.pocket.DEBUG --es cmd state
 *   adb shell am broadcast -a com.profer.pocket.DEBUG --es cmd open --es session_id <id>
 *   adb shell am broadcast -a com.profer.pocket.DEBUG --es cmd send --es session_id <id> --es text "ping"
 *   adb shell am broadcast -a com.profer.pocket.DEBUG --es cmd permission --es session_id <id> --es decision allow
 *   adb shell am broadcast -a com.profer.pocket.DEBUG --es cmd disconnect
 *
 * 结果统一打到 logcat：tag=ProferDebug，一条 JSON，不含 token / 消息正文。
 */
class DebugCommandReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (!BuildConfig.DEBUG) return
        val app = context.applicationContext as? ProferApplication ?: return
        val pending = goAsync()
        val requestId = intent.getStringExtra("request_id").orEmpty()
        val cmd = intent.getStringExtra("cmd").orEmpty()
        CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
            val result = JSONObject()
                .put("requestId", requestId)
                .put("cmd", cmd)
            try {
                val repo = app.container.repository
                when (cmd) {
                    "connect" -> {
                        val endpoint = intent.getStringExtra("endpoint").orEmpty()
                        val token = readTokenFile(context, intent)
                        if (token.isNullOrBlank()) {
                            result.put("ok", false).put("error", "token_file missing or empty")
                        } else {
                            result.put("ok", repo.connect(endpoint, token))
                            app.container.syncBackgroundService()
                            withTimeoutOrNull(12_000) {
                                repo.connectionStatus.first { status ->
                                    status is ConnectionStatus.Offline ||
                                        (status is ConnectionStatus.Connected && !status.serverInstanceId.isNullOrBlank())
                                }
                            }
                            // 等 hello 后的首次会话/渠道刷新落库，避免把快照数量当成桌面数据。
                            kotlinx.coroutines.delay(1_200)
                        }
                    }

                    "disconnect" -> {
                        repo.disconnect()
                        app.container.syncBackgroundService()
                        result.put("ok", true)
                    }

                    "state" -> {
                        result.put("ok", true)
                        intent.getStringExtra("session_id")?.takeIf { it.isNotBlank() }?.let {
                            result.put("sessionId", it)
                        }
                    }

                    "sessions" -> {
                        repo.refreshSessions()
                        result.put("ok", true)
                    }

                    "open" -> {
                        val sessionId = intent.getStringExtra("session_id").orEmpty()
                        repo.openSession(sessionId)
                        result.put("ok", true).put("sessionId", sessionId)
                    }

                    "send" -> {
                        val sessionId = intent.getStringExtra("session_id").orEmpty()
                        val text = intent.getStringExtra("text").orEmpty()
                        repo.sendMessage(sessionId, text)
                        result.put("ok", true).put("sessionId", sessionId)
                    }

                    "stop" -> {
                        val sessionId = intent.getStringExtra("session_id").orEmpty()
                        repo.stopSession(sessionId)
                        result.put("ok", true).put("sessionId", sessionId)
                    }

                    "create" -> {
                        val workspaceId = intent.getStringExtra("workspace_id")
                        val created = repo.createSession(workspaceId)
                        result.put("ok", created.isSuccess)
                        result.put("sessionId", created.getOrNull()?.id.orEmpty())
                        created.exceptionOrNull()?.let { result.put("error", it.message.orEmpty()) }
                    }

                    "rename" -> {
                        val sessionId = intent.getStringExtra("session_id").orEmpty()
                        val title = intent.getStringExtra("title").orEmpty()
                        val renamed = repo.renameSession(sessionId, title)
                        result.put("ok", renamed.isSuccess)
                        result.put("title", renamed.getOrNull()?.title.orEmpty())
                        renamed.exceptionOrNull()?.let { result.put("error", it.message.orEmpty()) }
                    }

                    "pin" -> {
                        val sessionId = intent.getStringExtra("session_id").orEmpty()
                        val pinned = repo.toggleSessionPin(sessionId)
                        result.put("ok", pinned.isSuccess)
                        result.put("pinned", pinned.getOrNull()?.isPinned ?: false)
                        pinned.exceptionOrNull()?.let { result.put("error", it.message.orEmpty()) }
                    }

                    "delete" -> {
                        val sessionId = intent.getStringExtra("session_id").orEmpty()
                        val deleted = repo.deleteSession(sessionId)
                        result.put("ok", deleted.isSuccess)
                        deleted.exceptionOrNull()?.let { result.put("error", it.message.orEmpty()) }
                    }

                    "permission" -> {
                        val sessionId = intent.getStringExtra("session_id").orEmpty()
                        val decision = intent.getStringExtra("decision").orEmpty()
                        respond(repo, sessionId, decision)
                        result.put("ok", true).put("sessionId", sessionId).put("decision", decision)
                    }

                    else -> result.put("ok", false).put("error", "unknown cmd: $cmd")
                }
                result.put("foreground", AppForeground.isForeground)
                appendState(repo, result, intent.getBooleanExtra("include_text", false))
            } catch (error: Exception) {
                result.put("ok", false).put("error", error.message ?: error.javaClass.simpleName)
                Log.e(TAG, result.toString(), error)
            } finally {
                if (result.optBoolean("ok")) {
                    Log.i(TAG, result.toString())
                } else if (!result.has("error")) {
                    Log.w(TAG, result.toString())
                }
                pending.finish()
            }
        }
    }

    private suspend fun respond(repo: PocketRepository, sessionId: String, decision: String) {
        val chat = repo.chats.value[sessionId]
        val pending = chat?.pendingInteraction
        val requestId = pending?.id
        when (pending) {
            is PendingInteraction.Permission -> repo.respondPermission(
                sessionId = sessionId,
                requestId = requestId ?: return,
                allow = decision != "deny",
                alwaysAllow = decision == "always",
            )

            is PendingInteraction.PlanApproval -> repo.respondPlan(
                sessionId = sessionId,
                requestId = requestId ?: return,
                approve = decision != "deny",
                alwaysAllow = decision == "always",
            )

            else -> error("no pending interaction for $sessionId")
        }
    }

    private fun appendState(repo: PocketRepository, result: JSONObject, includeText: Boolean) {
        val status = repo.connectionStatus.value
        result.put("status", status.toString())
        result.put("connected", status is ConnectionStatus.Connected)
        result.put("sessionCount", repo.sessions.value.size)
        val sessionId = result.optString("sessionId").takeIf { it.isNotBlank() }
            ?: repo.sessions.value.firstOrNull()?.id
        if (!sessionId.isNullOrBlank()) {
            result.put("currentSessionId", sessionId)
            val chat = repo.chats.value[sessionId]
            if (chat != null) {
                result.put("loaded", chat.loaded)
                result.put("running", chat.running)
                result.put("messageCount", chat.messages.size)
                result.put("assistantCount", chat.messages.count { it is ChatEntry.Assistant })
                result.put("userCount", chat.messages.count { it is ChatEntry.User })
                result.put(
                    "messageKinds",
                    chat.messages.joinToString(",") {
                        when (it) {
                            is ChatEntry.User -> "u"
                            is ChatEntry.Assistant -> "a"
                            is ChatEntry.Notice -> "n"
                        }
                    },
                )
                result.put("toolTotal", chat.tools.total)
                result.put("toolNames", chat.tools.byName.keys.joinToString(","))
                result.put(
                    "pending",
                    when (val p = chat.pendingInteraction) {
                        is PendingInteraction.Permission -> "permission:${p.id}"
                        is PendingInteraction.PlanApproval -> "plan:${p.id}"
                        is PendingInteraction.Question -> "question:${p.id}"
                        null -> ""
                    },
                )
                val lastAssistant = chat.messages.filterIsInstance<ChatEntry.Assistant>().lastOrNull()
                if (lastAssistant != null) {
                    result.put("lastAssistantAt", lastAssistant.at)
                    result.put("lastAssistantStreaming", lastAssistant.streaming)
                    if (includeText) {
                        result.put("lastAssistantText", lastAssistant.text.take(200))
                    }
                }
            }
        }
        val lastError = (status as? ConnectionStatus.Offline)?.message
        if (!lastError.isNullOrBlank()) result.put("offlineReason", lastError)
    }

    private fun readTokenFile(context: Context, intent: Intent): String? {
        val raw = intent.getStringExtra("token_file")?.trim().orEmpty()
        if (raw.isEmpty()) return null
        val file = File(raw).let { if (it.isAbsolute) it else File(context.filesDir, raw) }
        return file.takeIf { it.isFile }?.readText()?.trim()
    }

    private companion object {
        const val TAG = "ProferDebug"
    }
}
