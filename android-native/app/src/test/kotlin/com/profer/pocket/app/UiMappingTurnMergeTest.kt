package com.profer.pocket.app

import com.profer.pocket.chat.ChatMessage
import com.profer.pocket.model.ChatEntry
import com.profer.pocket.model.ConnectionStatus
import com.profer.pocket.model.SessionChat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 回合合并：一条助手条目是模型的一步，一轮排查常有七八步。
 * 投影层必须把「上一条用户输入之后的连续助手条目」合成一个回合，
 * 否则每步都会铺出一行「执行过程：1 次工具调用，1 段思考」。
 */
class UiMappingTurnMergeTest {

    private val status = ConnectionStatus.Connected("ws://host/ws")

    private fun state(messages: List<ChatEntry>, running: Boolean = false) = UiMapping.chatState(
        "s1",
        SessionChat(sessionId = "s1", messages = messages, running = running, loaded = true),
        emptyList(),
        emptyList(),
        status,
    )

    private fun assistant(id: String, text: String = "", thinking: String? = null, tool: String? = null) =
        ChatEntry.Assistant(
            id = id,
            text = text,
            streaming = false,
            at = 1,
            thinking = thinking,
            toolCalls = tool?.let {
                listOf(com.profer.pocket.model.ToolCall(id = it, name = "Read", path = "src/$it.kt"))
            }.orEmpty(),
        )

    private fun user(id: String) = ChatEntry.User(id = id, text = "问题", at = 0)

    @Test
    fun `连续助手条目合成一个回合`() {
        val messages = state(
            listOf(
                user("u1"),
                assistant("a1", thinking = "先看日志", tool = "t1"),
                assistant("a2", thinking = "再看游标", tool = "t2"),
                assistant("a3", thinking = "跑测试", tool = "t3"),
            ),
        ).messages

        assertEquals(2, messages.size)
        val turn = messages[1]
        assertTrue("连续助手条目应该合成一个回合，实际是 $turn", turn is ChatMessage.Turn)
        turn as ChatMessage.Turn
        // 首步 id 做 key：后续步骤追加时 LazyColumn 的项身份不变，滚动位置不跳。
        assertEquals("a1", turn.id)
        assertEquals(listOf("a1", "a2", "a3"), turn.steps.map { it.id })
        assertEquals(3, turn.steps.sumOf { it.tools.size })
    }

    @Test
    fun `用户消息把回合切断`() {
        val messages = state(
            listOf(
                assistant("a1", thinking = "第一步", tool = "t1"),
                assistant("a2", thinking = "第二步", tool = "t2"),
                user("u1"),
                assistant("a3", thinking = "新一轮", tool = "t3"),
                assistant("a4", text = "答案"),
            ),
        ).messages

        assertEquals(3, messages.size)
        assertEquals("a1", (messages[0] as ChatMessage.Turn).id)
        assertEquals("a3", (messages[2] as ChatMessage.Turn).id)
    }

    @Test
    fun `单条助手条目保持原样`() {
        val messages = state(listOf(user("u1"), assistant("a1", text = "答案"))).messages

        val assistant = messages[1]
        assertTrue("只有一步时不该包一层回合，实际是 $assistant", assistant is ChatMessage.Assistant)
    }

    @Test
    fun `步骤没变时复用同一个回合实例`() {
        val entries = listOf(
            assistant("a1", thinking = "第一步", tool = "t1"),
            assistant("a2", thinking = "第二步", tool = "t2"),
        )
        val first = state(entries).messages.single() as ChatMessage.Turn
        val second = state(entries).messages.single() as ChatMessage.Turn

        assertSame("回合没变却重建，整张消息表会跟着每个 token 重算", first, second)
    }

    @Test
    fun `追加一步后回合重建但身份不变`() {
        val head = listOf(
            assistant("a1", thinking = "第一步", tool = "t1"),
            assistant("a2", thinking = "第二步", tool = "t2"),
        )
        val before = state(head).messages.single() as ChatMessage.Turn
        val after = state(head + assistant("a3", thinking = "第三步", tool = "t3")).messages.single() as ChatMessage.Turn

        assertEquals("a1", after.id)
        assertNotSame(before, after)
        assertEquals(3, after.steps.size)
    }
}
