package com.profer.pocket.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FileRefTextTest {

    @Test
    fun `正文里的文件路径被切成 chip`() {
        val files = splitFileRefs("改了 remote-service.ts 和 src/main/app.kt:12，其他没动")
            .filter { it.isFile }
        assertEquals(listOf("remote-service.ts", "app.kt:12"), files.map { it.display })
    }

    @Test
    fun `版本号和普通文字不会被当成文件`() {
        assertTrue(splitFileRefs("升级到 1.2.3 版本").none { it.isFile })
        assertTrue(splitFileRefs("看一下 README 和说明").none { it.isFile })
    }

    @Test
    fun `绝对路径只显示文件名并保留行号`() {
        val files = splitFileRefs("见 /Users/mac/profer/app.ts:42").filter { it.isFile }
        assertEquals("app.ts:42", files.single().display)
        assertEquals("/Users/mac/profer/app.ts", files.single().path)
    }
}
