package com.profer.pocket.chat

/**
 * 模型图标解析：沿用桌面端 `model-logo.ts` 的匹配顺序，
 * 先认具体模型，再认渠道展示名，最后回退到默认图标。
 *
 * 图标资源来自桌面端 `renderer/assets/models`，已复制到本模块 `drawable-nodpi`，
 * 保持原始方形图，不做 tint，避免破坏品牌色。
 */
internal object ModelLogos {

    fun drawableFor(option: ModelOption, dark: Boolean = false): Int = resolve(
        modelId = option.modelId,
        label = option.label,
        channelLabel = option.channelLabel,
    ).let { if (dark) darkVariantOrNull(it) ?: it else it }

    fun drawableFor(modelId: String?, label: String? = null, dark: Boolean = false): Int = resolve(
        modelId = modelId,
        label = label,
        channelLabel = null,
    ).let { if (dark) darkVariantOrNull(it) ?: it else it }

    /** 深色专用图标的资源 id；没有深色版本时返回 null，调用方可用浅色底托住透明字形。 */
    fun darkVariantFor(option: ModelOption): Int? = darkVariantOrNull(resolve(
        modelId = option.modelId,
        label = option.label,
        channelLabel = option.channelLabel,
    ))

    /** 桌面端为深色准备的黑底白字版本；没有对应版本的图标保持原样。 */
    private fun darkVariantOrNull(light: Int): Int? = when (light) {
        R.drawable.model_claude -> R.drawable.model_claude_dark
        R.drawable.model_deepseek -> R.drawable.model_deepseek_dark
        R.drawable.model_gemini -> R.drawable.model_gemini_dark
        R.drawable.model_gemma -> R.drawable.model_gemma_dark
        R.drawable.model_openai -> R.drawable.model_openai_dark
        R.drawable.model_grok -> R.drawable.model_grok_dark
        R.drawable.model_qwen -> R.drawable.model_qwen_dark
        R.drawable.model_doubao -> R.drawable.model_doubao_dark
        R.drawable.model_zhipu -> R.drawable.model_zhipu_dark
        R.drawable.model_chatglm -> R.drawable.model_chatglm_dark
        R.drawable.model_llama -> R.drawable.model_llama_dark
        R.drawable.model_mixtral -> R.drawable.model_mixtral_dark
        R.drawable.model_yi -> R.drawable.model_yi_dark
        R.drawable.model_hunyuan -> R.drawable.model_hunyuan_dark
        R.drawable.model_wenxin -> R.drawable.model_wenxin_dark
        R.drawable.model_sparkdesk -> R.drawable.model_sparkdesk_dark
        R.drawable.model_step -> R.drawable.model_step_dark
        R.drawable.model_cohere -> R.drawable.model_cohere_dark
        else -> null
    }

    private fun resolve(modelId: String?, label: String?, channelLabel: String?): Int {
        val key = listOfNotNull(modelId, label, channelLabel)
            .joinToString(" ")
            .lowercase()
        if (key.isBlank()) return R.drawable.model_default

        return when {
            // Gemini 衍生模型必须排在通用 gemini 之前。
            key.contains("deepgemini") -> R.drawable.model_deepgemini
            key.contains("kimigemini") -> R.drawable.model_kimigemini
            key.contains("qwengemini") -> R.drawable.model_qwengemini
            key.contains("seedgemini") -> R.drawable.model_seedgemini

            // GPT / OpenAI：覆盖 gpt-5.6-sol、gpt-image、o1/o3/o4 等。
            key.contains("gpt-image") ||
                key.contains("gpt-3") ||
                key.contains("gpt-4") ||
                key.contains("gpt-5") ||
                key.contains("gpt-6") ||
                key.contains("gpts") ||
                Regex("(^|[\\s/:])o[134]($|[\\s.:-])").containsMatchIn(key) ->
                R.drawable.model_openai

            key.contains("claude") || key.contains("anthropic") -> R.drawable.model_claude
            key.contains("deepseek") -> R.drawable.model_deepseek

            key.contains("gemini") -> R.drawable.model_gemini
            key.contains("gemma") -> R.drawable.model_gemma
            key.contains("qwen") || key.contains("qwq") || key.contains("qvq") ||
                key.contains("wan-") -> R.drawable.model_qwen
            key.contains("grok") -> R.drawable.model_grok
            key.contains("kimi") -> R.drawable.model_kimi

            // ChatGLM 必须在 zhipu/glm 之前，否则会被 glm 规则吞掉。
            key.contains("chatglm") -> R.drawable.model_chatglm
            key.contains("doubao") || key.contains("ep-202") || key.contains("seed") ->
                R.drawable.model_doubao
            key.contains("zhipu") || key.contains("cogview") || key.contains("glm") ->
                R.drawable.model_zhipu

            key.contains("llama") -> R.drawable.model_llama
            key.contains("codestral") -> R.drawable.model_codestral
            key.contains("mixtral") || key.contains("mistral") ||
                key.contains("ministral") || key.contains("magistral") -> R.drawable.model_mixtral
            Regex("(^|[\\s/:])yi-").containsMatchIn(key) -> R.drawable.model_yi
            Regex("(^|[\\s/:])(ernie|tao)-").containsMatchIn(key) -> R.drawable.model_wenxin
            key.contains("hunyuan") -> R.drawable.model_hunyuan
            key.contains("sparkdesk") || key.contains("generalv") -> R.drawable.model_sparkdesk
            key.contains("step") -> R.drawable.model_step
            key.contains("minimax") -> R.drawable.model_minimax
            key.contains("mimo") -> R.drawable.model_xiaomi
            key.contains("cohere") || key.contains("command") -> R.drawable.model_cohere
            key.contains("embedding") -> R.drawable.model_embedding

            else -> R.drawable.model_default
        }
    }
}
