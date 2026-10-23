package com.profer.pocket.chat

import org.junit.Assert.assertEquals
import org.junit.Test

class ModelLogosTest {

    @Test
    fun `常见模型 id 映射到对应品牌图标`() {
        assertEquals(R.drawable.model_deepseek, ModelLogos.drawableFor("global:deepseek-v4.1-flash"))
        assertEquals(R.drawable.model_openai, ModelLogos.drawableFor("gpt-5.6-sol"))
        assertEquals(R.drawable.model_openai, ModelLogos.drawableFor("gpt-image-2"))
        assertEquals(R.drawable.model_claude, ModelLogos.drawableFor("claude-opus-5-5"))
        assertEquals(R.drawable.model_grok, ModelLogos.drawableFor("grok-4.6"))
        assertEquals(R.drawable.model_qwen, ModelLogos.drawableFor("qwen3-max"))
        assertEquals(R.drawable.model_kimi, ModelLogos.drawableFor("kimi-k2"))
    }

    @Test
    fun `更具体的 Gemini 衍生模型优先于通用 gemini`() {
        assertEquals(R.drawable.model_seedgemini, ModelLogos.drawableFor("seedgemini-pro"))
        assertEquals(R.drawable.model_deepgemini, ModelLogos.drawableFor("deepgemini-2"))
        assertEquals(R.drawable.model_gemini, ModelLogos.drawableFor("gemini-2.5-pro"))
    }

    @Test
    fun `chatglm 与 codestral 不被 glm 和 mixtral 规则吞掉`() {
        assertEquals(R.drawable.model_chatglm, ModelLogos.drawableFor("chatglm-4"))
        assertEquals(R.drawable.model_zhipu, ModelLogos.drawableFor("glm-4.5"))
        assertEquals(R.drawable.model_codestral, ModelLogos.drawableFor("codestral-2501"))
        assertEquals(R.drawable.model_mixtral, ModelLogos.drawableFor("mixtral-8x22b"))
    }

    @Test
    fun `未知模型回退默认图标`() {
        assertEquals(R.drawable.model_default, ModelLogos.drawableFor("some-unknown-model"))
        assertEquals(R.drawable.model_default, ModelLogos.drawableFor(null))
    }

    @Test
    fun `深色模式优先返回深色版本`() {
        assertEquals(
            R.drawable.model_deepseek_dark,
            ModelLogos.darkVariantFor(ModelOption("ch", "deepseek-flash", "deepseek-flash", "DeepSeek")),
        )
        assertEquals(null, ModelLogos.darkVariantFor(ModelOption("ch", "kimi-k2", "kimi-k2", "Kimi")))
    }
}
