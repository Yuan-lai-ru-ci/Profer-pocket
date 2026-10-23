package com.profer.pocket.data

import com.profer.pocket.data.protocol.ChatCodec
import com.profer.pocket.data.protocol.FrameCodec
import com.profer.pocket.data.protocol.InboundFrame
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatCodecTest {

    @Test
    fun `解析对话索引并保留 activePath`() {
        val data = JSONArray().apply {
            put(
                JSONObject()
                    .put("id", "c1")
                    .put("title", "屏幕缩放")
                    .put("channelId", "ch1")
                    .put("modelId", "m1")
                    .put("pinned", true)
                    .put("archived", false)
                    .put("updatedAt", 120)
                    .put("activePath", JSONArray().put("m-a").put("m-b")),
            )
            put(JSONObject().put("title", "缺 id 的脏数据"))
        }
        val list = ChatCodec.conversations(data)
        assertEquals(1, list.size)
        val item = list.first()
        assertEquals("c1", item.id)
        assertEquals("屏幕缩放", item.title)
        assertEquals("ch1", item.channelId)
        assertEquals("m1", item.modelId)
        assertTrue(item.pinned)
        assertEquals(listOf("m-a", "m-b"), item.activePath)
    }

    @Test
    fun `解析消息正文与推理`() {
        val data = JSONArray().apply {
            put(
                JSONObject()
                    .put("id", "m1")
                    .put("role", "user")
                    .put("content", "你好")
                    .put("createdAt", 10),
            )
            put(
                JSONObject()
                    .put("id", "m2")
                    .put("role", "assistant")
                    .put("content", "在的")
                    .put("reasoning", "先打招呼")
                    .put("createdAt", 20),
            )
        }
        val messages = ChatCodec.messages(data)
        assertEquals(listOf("m1", "m2"), messages.map { it.id })
        assertTrue(messages[0].isUser)
        assertEquals("先打招呼", messages[1].reasoning)
    }

    @Test
    fun `激活分支只保留 activePath 内的消息`() {
        val messages = ChatCodec.messages(
            JSONArray().apply {
                put(JSONObject().put("id", "a").put("role", "user").put("content", "旧"))
                put(JSONObject().put("id", "b").put("role", "assistant").put("content", "旧回复"))
                put(JSONObject().put("id", "c").put("role", "user").put("content", "新"))
                put(JSONObject().put("id", "d").put("role", "assistant").put("content", "新回复"))
            },
        )
        val branch = ChatCodec.activeBranch(messages, listOf("a", "c", "d"))
        assertEquals(listOf("a", "c", "d"), branch.map { it.id })
        // activePath 为空时退回全部消息，兼容旧数据。
        assertEquals(4, ChatCodec.activeBranch(messages, emptyList()).size)
    }

    @Test
    fun `chat_event 帧保留 payload`() {
        val frame = FrameCodec.parseInbound(
            """{"kind":"chat_event","conversationId":"c1","channel":"chat:stream:chunk","payload":{"delta":"Pong"}}""",
        )
        assertTrue(frame is InboundFrame.ChatEvent)
        val event = frame as InboundFrame.ChatEvent
        assertEquals("c1", event.conversationId)
        assertEquals("chat:stream:chunk", event.channel)
        assertEquals("Pong", event.payload?.optString("delta"))
    }
}
