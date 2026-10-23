package com.profer.pocket.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AttachmentParsingTest {

    @Test
    fun `解析 attached_files 块并从正文剥离`() {
        val text = """
            <attached_files>
            - image-1.jpg: /Users/me/profer/sessions/s1/image-1.jpg
            - report.pdf: /Users/me/profer/sessions/s1/report.pdf
            </attached_files>

            帮我看一下这两份文件
        """.trimIndent()

        val files = parseAttachedFiles(text)
        assertEquals(2, files.size)
        assertEquals("image-1.jpg", files[0].filename)
        assertEquals("/Users/me/profer/sessions/s1/image-1.jpg", files[0].path)
        assertEquals("report.pdf", files[1].filename)
        assertEquals("帮我看一下这两份文件", stripAttachedFiles(text))
    }

    @Test
    fun `普通列表行不会被误判为附件`() {
        val text = """
            注意事项：
            - 说明: 这里不是附件
            - 结论: 也不需要处理
        """.trimIndent()
        assertTrue(parseAttachedFiles(text).isEmpty())
        assertEquals(text, stripAttachedFiles(text))
    }

    @Test
    fun `没有附件块时正文保持原样`() {
        val text = "直接发一条普通消息"
        assertTrue(parseAttachedFiles(text).isEmpty())
        assertEquals(text, stripAttachedFiles(text))
    }
}
