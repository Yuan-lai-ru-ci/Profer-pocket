package com.profer.pocket.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 思考强度：档位收敛、预设默认级联、两套命名（Pi 档位 / Claude 强度档）。 */
class ReasoningResolverTest {

    private val deepSeek = ReasoningCapability(levels = listOf("off", "high", "max"), defaultLevel = "high")

    @Test
    fun `没有会话覆盖时跟随预设档位并收敛到模型支持的档`() {
        val ui = ReasoningResolver.resolve(
            capability = deepSeek,
            sessionLevel = null,
            sessionEffort = null,
            presetEffort = "medium",
        )

        // medium 不在 DeepSeek 的 off/high/max 里，向上收敛到 high。
        assertEquals("高", ui.label)
        assertEquals("跟随预设「高」", ui.sourceLabel)
        assertFalse(ui.overridden)
        assertNull(ui.value)
        assertNull(ui.selectedValue)
        assertTrue(ui.usesCapability)
        assertEquals(listOf(null, "off", "high", "max"), ui.options.map { it.value })
        assertEquals("高", ui.options.first().hint)
        assertEquals("不产出思考", ui.options.first { it.value == "off" }.hint)
    }

    @Test
    fun `会话覆盖显示本会话覆盖并保留原始值`() {
        val ui = ReasoningResolver.resolve(deepSeek, sessionLevel = "max", sessionEffort = null, presetEffort = "high")

        assertEquals("最高", ui.label)
        assertEquals("本会话覆盖", ui.sourceLabel)
        assertTrue(ui.overridden)
        assertEquals("max", ui.value)
        assertEquals("max", ui.selectedValue)
    }

    @Test
    fun `旧模型的档位切到新模型后先向上再向下收敛`() {
        val narrow = ReasoningCapability(levels = listOf("off", "high"), defaultLevel = "high")
        // xhigh 来自上一个模型：先向上找 max（不支持），再向下找 high。
        assertEquals("high", ThinkingLevels.normalize(narrow, "xhigh"))
        // 未知档位直接落到模型的首档，不会把非法值发给服务端。
        assertEquals("off", ThinkingLevels.normalize(narrow, "weird"))
        assertEquals("high", ThinkingLevels.normalize(narrow, null))
    }

    @Test
    fun `没有模型能力时回退到强度档`() {
        val ui = ReasoningResolver.resolve(
            capability = null,
            sessionLevel = "max",
            sessionEffort = "medium",
            presetEffort = "max",
        )

        assertFalse(ui.usesCapability)
        assertEquals("中", ui.label)
        assertEquals("本会话覆盖", ui.sourceLabel)
        assertEquals("medium", ui.value)
        assertEquals(listOf(null, "low", "medium", "high", "max"), ui.options.map { it.value })
        assertEquals("最大", ui.options.first().hint)
    }

    @Test
    fun `预设没写强度时默认高`() {
        val ui = ReasoningResolver.resolve(null, sessionLevel = null, sessionEffort = null, presetEffort = null)

        assertEquals("高", ui.label)
        assertEquals("跟随预设「高」", ui.sourceLabel)
        assertEquals("高", ui.options.first().hint)
    }

    @Test
    fun `预设给了非法强度时回落到高`() {
        val ui = ReasoningResolver.resolve(null, sessionLevel = null, sessionEffort = null, presetEffort = "xhigh")

        assertEquals("高", ui.label)
    }

    @Test
    fun `档位文案覆盖全部七档且更高与最高可区分`() {
        assertEquals(
            listOf("关闭", "最低", "低", "中", "高", "更高", "最高"),
            ThinkingLevels.extended.map(ThinkingLevels::label),
        )
        assertEquals(
            listOf("低", "中", "高", "最大"),
            ThinkingLevels.efforts.map(ThinkingLevels::effortLabel),
        )
    }
}
