package com.profer.pocket.data.store

import android.content.Context
import com.profer.pocket.data.net.OutgoingMessage
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 待发送消息队列落盘。
 *
 * 断线队列原先只在内存里：进程被系统回收后用户刚发出的消息会静默消失，
 * 而服务端本来就按 `clientMessageId` 幂等去重，重放是安全的。
 *
 * 只保存「还没发出去」的消息：已进入 inflight 的消息在断线时回到队列，
 * 已收到 command_result 的消息不会写入。
 */
interface OutgoingQueueStore {
    data class Snapshot(
        val endpoint: String,
        val items: List<OutgoingMessage>,
    )

    fun load(): Snapshot?

    fun save(endpoint: String, items: List<OutgoingMessage>)

    fun clear()
}

class AndroidOutgoingQueueStore(context: Context) : OutgoingQueueStore {

    private val file = File(context.applicationContext.filesDir, FILE_NAME)

    override fun load(): OutgoingQueueStore.Snapshot? {
        val raw = try {
            if (file.isFile) file.readText() else return null
        } catch (_: Exception) {
            return null
        }
        return try {
            val json = JSONObject(raw)
            val endpoint = json.optString("endpoint", "")
            if (endpoint.isBlank()) return null
            val array = json.optJSONArray("items") ?: JSONArray()
            val items = buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val cmdId = item.optString("cmdId", "").takeIf { it.isNotBlank() } ?: continue
                    val clientMessageId = item.optString("clientMessageId", "")
                        .takeIf { it.isNotBlank() } ?: continue
                    val frame = item.optString("frame", "").takeIf { it.isNotBlank() } ?: continue
                    add(OutgoingMessage(cmdId = cmdId, clientMessageId = clientMessageId, frame = frame))
                }
            }
            OutgoingQueueStore.Snapshot(endpoint = endpoint, items = items)
        } catch (_: Exception) {
            null
        }
    }

    override fun save(endpoint: String, items: List<OutgoingMessage>) {
        if (items.isEmpty()) {
            clear()
            return
        }
        val json = JSONObject().apply {
            put("endpoint", endpoint)
            put("items", JSONArray().apply {
                items.forEach { message ->
                    put(JSONObject().apply {
                        put("cmdId", message.cmdId)
                        put("clientMessageId", message.clientMessageId)
                        put("frame", message.frame)
                    })
                }
            })
        }
        try {
            file.parentFile?.mkdirs()
            val temp = File(file.parentFile, "$FILE_NAME.tmp")
            temp.writeText(json.toString())
            if (!temp.renameTo(file)) {
                file.writeText(json.toString())
                temp.delete()
            }
        } catch (_: Exception) {
            // 落盘失败不能影响发送：内存队列仍然有效。
        }
    }

    override fun clear() {
        runCatching { file.delete() }
    }

    private companion object {
        const val FILE_NAME = "outgoing-queue.json"
    }
}
