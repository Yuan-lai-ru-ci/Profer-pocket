package com.profer.pocket.chat

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InlineRunsTest {

    private val codeBackground = Color(0x11000000)

    @Test
    fun `行内代码里的文件路径单独成 chip`() {
        val runs = inlineRuns("改在 `/Users/mac/profer/app/ws-client.ts:42` 的重连分支", codeBackground)

        val chip = runs.filter { it.path != null }
        assertEquals(1, chip.size)
        assertEquals("/Users/mac/profer/app/ws-client.ts:42", chip.single().path)
        assertEquals("ws-client.ts:42", chip.single().display)
    }

    @Test
    fun `裸文件名行内代码也成 chip`() {
        val runs = inlineRuns("补了 `ws-client.test.ts` 的用例", codeBackground)

        val chip = runs.single { it.path != null }
        assertEquals("ws-client.test.ts", chip.path)
        assertEquals("ws-client.test.ts", chip.display)
    }

    @Test
    fun `普通行内代码不成 chip`() {
        val runs = inlineRuns("游标写成 `lastAckedMessageId`，重连再拉增量", codeBackground)

        assertTrue(runs.none { it.path != null })
    }

    @Test
    fun `切分不丢字也不改字序`() {
        val raw = "重连时 `session-cursor` 在 onclose 里被写成当前最后一条，" +
            "但服务端并不知道客户端收到了哪些消息，恢复时就会跳过这段窗口。"
        val runs = inlineRuns(raw, codeBackground)

        val rebuilt = buildString {
            runs.forEach { run ->
                if (run.path != null) append(run.path) else append(run.text?.text)
            }
        }
        assertEquals(raw.replace("`", ""), rebuilt)
    }

    @Test
    fun `长中文串被切成多个片段以便换行`() {
        val raw = "这是一段没有空格也没有标点的很长的中文句子用来验证切分逻辑是否生效并且不会把整段塞进一个片段"
        val runs = inlineRuns(raw, codeBackground)

        assertTrue(runs.size > 1)
        assertTrue(runs.all { (it.text?.text?.length ?: 0) <= 14 })
    }

    @Test
    fun `拉丁标识符不会在驼峰中间被切断`() {
        val runs = inlineRuns("调用 getUserVisibleHintForStreaming 时", codeBackground)

        val pieces = runs.mapNotNull { it.text?.text }
        assertTrue(pieces.any { it.contains("getUserVisibleHintForStreaming") })
    }
}
