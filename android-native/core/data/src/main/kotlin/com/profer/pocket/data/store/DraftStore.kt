package com.profer.pocket.data.store

import android.content.Context
import org.json.JSONObject

/** 按会话保存的输入草稿；进程被杀后仍能恢复。 */
interface DraftStore {
    fun load(): Map<String, String>
    fun save(drafts: Map<String, String>)
}

class AndroidDraftStore(context: Context) : DraftStore {
    private val prefs = context.getSharedPreferences("profer_pocket_drafts", Context.MODE_PRIVATE)

    override fun load(): Map<String, String> {
        val raw = prefs.getString(KEY, null) ?: return emptyMap()
        return try {
            val json = JSONObject(raw)
            buildMap {
                for (key in json.keys()) {
                    val value = json.optString(key, "")
                    if (value.isNotEmpty()) put(key, value)
                }
            }
        } catch (_: Exception) {
            emptyMap()
        }
    }

    override fun save(drafts: Map<String, String>) {
        val json = JSONObject()
        drafts.forEach { (sessionId, text) ->
            if (text.isNotEmpty()) json.put(sessionId, text)
        }
        prefs.edit().putString(KEY, json.toString()).apply()
    }

    private companion object {
        const val KEY = "drafts_json"
    }
}
