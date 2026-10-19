package com.profer.pocket.data

import com.profer.pocket.data.protocol.HistoryMapper
import com.profer.pocket.data.protocol.SdkMessageView
import com.profer.pocket.model.ChatEntry
import org.junit.Assert.assertEquals
import org.junit.Test

class HistoryMapperTest {

    private fun message(
        type: String,
        uuid: String,
        text: String,
        createdAt: Long? = null,
    ) = SdkMessageView(
        type = type,
        uuid = uuid,
        createdAt = createdAt,
        partial = false,
        text = text,
        toolUses = emptyList(),
        subtype = null,
        model = null,
        isError = false,
        errorMessage = null,
    )

    @Test
    fun `历史消息使用落盘时间而不是加载时间`() {
        val history = HistoryMapper.map(
            listOf(
                message("user", "u1", "电脑上发的指令", createdAt = 1_000L),
                message("assistant", "a1", "收到", createdAt = 2_000L),
            ),
            now = 9_999L,
        )

        assertEquals(2, history.entries.size)
        assertEquals("电脑上发的指令", (history.entries[0] as ChatEntry.User).text)
        assertEquals(1_000L, history.entries[0].at)
        assertEquals(2_000L, history.entries[1].at)
    }

    @Test
    fun `缺少落盘时间时退回加载时间`() {
        val history = HistoryMapper.map(
            listOf(message("user", "u1", "旧消息")),
            now = 9_999L,
        )

        assertEquals(9_999L, history.entries.single().at)
    }
}
