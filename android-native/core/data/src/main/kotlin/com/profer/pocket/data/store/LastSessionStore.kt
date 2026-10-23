package com.profer.pocket.data.store

import android.content.Context

/**
 * 上次打开的会话：冷启动恢复阅读位置，而不是永远落到目录第一条。
 *
 * Agent 会话与 Chat 对话分开记录；id 可能已被电脑端删除，
 * 恢复时由调用方校验存在性后回退。
 */
interface LastSessionStore {
    data class State(
        val agentSessionId: String? = null,
        val conversationId: String? = null,
    )

    fun load(): State

    fun save(state: State)
}

class AndroidLastSessionStore(context: Context) : LastSessionStore {
    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun load(): LastSessionStore.State = LastSessionStore.State(
        agentSessionId = prefs.getString(KEY_AGENT_SESSION, null)?.takeIf { it.isNotBlank() },
        conversationId = prefs.getString(KEY_CONVERSATION, null)?.takeIf { it.isNotBlank() },
    )

    override fun save(state: LastSessionStore.State) {
        val editor = prefs.edit()
        if (state.agentSessionId.isNullOrBlank()) {
            editor.remove(KEY_AGENT_SESSION)
        } else {
            editor.putString(KEY_AGENT_SESSION, state.agentSessionId)
        }
        if (state.conversationId.isNullOrBlank()) {
            editor.remove(KEY_CONVERSATION)
        } else {
            editor.putString(KEY_CONVERSATION, state.conversationId)
        }
        editor.apply()
    }

    private companion object {
        const val PREFS_NAME = "profer_pocket_last_session"
        const val KEY_AGENT_SESSION = "agent_session_id"
        const val KEY_CONVERSATION = "chat_conversation_id"
    }
}
