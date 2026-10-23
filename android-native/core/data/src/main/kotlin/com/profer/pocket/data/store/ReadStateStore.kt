package com.profer.pocket.data.store

import android.content.Context
import org.json.JSONObject

/**
 * 本机阅读位置：每个会话最后一条已读消息 id。
 *
 * 协议只有「未读布尔」，没有跨设备已读锚点；这里记录本机位置，
 * UI 上称「新消息」而不是「未读」，避免冒充服务端状态。
 */
interface ReadStateStore {
    fun load(): Map<String, String>
    fun save(positions: Map<String, String>)
}

class AndroidReadStateStore(context: Context) : ReadStateStore {
    private val prefs = context.getSharedPreferences("profer_pocket_read_state", Context.MODE_PRIVATE)

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

    override fun save(positions: Map<String, String>) {
        val json = JSONObject()
        positions.forEach { (sessionId, messageId) ->
            if (messageId.isNotEmpty()) json.put(sessionId, messageId)
        }
        prefs.edit().putString(KEY, json.toString()).apply()
    }

    private companion object {
        const val KEY = "read_positions_json"
    }
}
