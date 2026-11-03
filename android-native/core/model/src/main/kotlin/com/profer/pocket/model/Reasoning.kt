package com.profer.pocket.model

/**
 * 模型可用的思考档位（由电脑端 `resolvePiReasoningCapability` 计算后下发）。
 *
 * 手机不做本地能力推导：档位随模型与渠道协议变化（例如 GLM-5.3 只有「关闭 / 高」，
 * DeepSeek 只有「关闭 / 高 / 最高」），自己猜会给用户一个选了也不生效的档位。
 */
data class ReasoningCapability(
    /** 从低到高的可用档位：off / minimal / low / medium / high / xhigh / max 的子集。 */
    val levels: List<String> = emptyList(),
    val defaultLevel: String = "medium",
) {
    val isUsable: Boolean get() = levels.isNotEmpty()
}

/**
 * 思考强度的两套命名：
 * - 档位（Pi 内核）：off / minimal / low / medium / high / xhigh / max；
 * - 强度档（Claude 内核）：low / medium / high / max。
 *
 * 两套都映射到同一个用户概念「思考强度」，但写入的是不同的会话字段
 * （openAIThinkingLevel / agentEffort），所以文案与顺序都要分开维护。
 */
object ThinkingLevels {
    /** 完整档位顺序，与桌面 `PI_EXTENDED_THINKING_LEVELS` 同序；收敛旧档位时按这个顺序找邻居。 */
    val extended: List<String> = listOf("off", "minimal", "low", "medium", "high", "xhigh", "max")

    /** Claude 内核的强度档；拿不到模型能力时用它兜底。 */
    val efforts: List<String> = listOf("low", "medium", "high", "max")

    fun label(level: String): String = when (level) {
        "off" -> "关闭"
        "minimal" -> "最低"
        "low" -> "低"
        "medium" -> "中"
        "high" -> "高"
        "xhigh" -> "更高"
        "max" -> "最高"
        else -> level
    }

    fun effortLabel(effort: String): String = when (effort) {
        "low" -> "低"
        "medium" -> "中"
        "high" -> "高"
        "max" -> "最大"
        else -> effort
    }

    /**
     * 把请求档位收敛到模型支持的档位：先向上、再向下。
     *
     * 与桌面 `normalizeReasoningCapabilityLevel` 同序。会话档位可能来自上一个模型
     * （如 DeepSeek 的 max），切到只支持 off/high 的模型时必须先收敛，
     * 否则入口会显示一个列表里选不中的档。
     */
    fun normalize(capability: ReasoningCapability, level: String?): String {
        val requested = level ?: capability.defaultLevel
        if (requested in capability.levels) return requested
        val index = extended.indexOf(requested)
        if (index < 0) return capability.levels.first()
        for (i in index until extended.size) {
            val candidate = extended[i]
            if (candidate in capability.levels) return candidate
        }
        for (i in index - 1 downTo 0) {
            val candidate = extended[i]
            if (candidate in capability.levels) return candidate
        }
        return capability.levels.first()
    }
}

/** 弹层里的一个档位；[value] 为 null 表示「跟随预设」（清除会话覆盖）。 */
data class ReasoningOption(
    val value: String?,
    val label: String,
    /** 右侧补充说明，只给语义不直观的档位（如「关闭」）。 */
    val hint: String? = null,
)

/**
 * 思考强度在界面上的完整表述：入口标签 + 来源说明 + 可选档位。
 *
 * 分支与桌面 `resolveReasoningChipState` / `resolveReasoningOptions` 一致：
 * 有模型能力走档位（Pi），没有能力走强度档（Claude 内核）。
 * 没有会话覆盖时默认档来自预设 effort；预设没写就是「高」。
 */
data class ReasoningUi(
    val label: String,
    val sourceLabel: String,
    /** 当前会话覆盖值（null = 跟随预设）；命名空间随 [usesCapability] 变化。 */
    val value: String?,
    /**
     * 弹层里应该勾选的那一项。
     *
     * 覆盖值可能来自上一个模型（DeepSeek 的 max），当前模型只支持 off/high 时会被收敛：
     * 勾选必须落在收敛后的档位上，否则列表里一个勾都没有。没有覆盖时勾「跟随预设」。
     */
    val selectedValue: String?,
    val overridden: Boolean,
    val options: List<ReasoningOption>,
    /** true = 模型档位（off..max）；false = 强度档（low..max）。 */
    val usesCapability: Boolean,
)

object ReasoningResolver {
    const val FOLLOW_PRESET_LABEL = "跟随预设"

    /** 预设/全局都没给强度时的兜底档，与桌面 `fallbackEffort` 同值。 */
    const val FALLBACK_EFFORT = "high"

    fun resolve(
        capability: ReasoningCapability?,
        sessionLevel: String?,
        sessionEffort: String?,
        presetEffort: String?,
    ): ReasoningUi {
        val fallbackEffort = presetEffort?.takeIf { it in ThinkingLevels.efforts } ?: FALLBACK_EFFORT

        if (capability != null && capability.isUsable) {
            val defaultLevel = ThinkingLevels.normalize(capability, fallbackEffort)
            val active = ThinkingLevels.normalize(capability, sessionLevel ?: fallbackEffort)
            return ReasoningUi(
                label = ThinkingLevels.label(active),
                sourceLabel = if (sessionLevel != null) {
                    "本会话覆盖"
                } else {
                    "跟随预设「${ThinkingLevels.label(defaultLevel)}」"
                },
                value = sessionLevel,
                selectedValue = if (sessionLevel != null) active else null,
                overridden = sessionLevel != null,
                usesCapability = true,
                options = buildList {
                    add(ReasoningOption(null, FOLLOW_PRESET_LABEL, ThinkingLevels.label(defaultLevel)))
                    capability.levels.forEach { level ->
                        add(
                            ReasoningOption(
                                value = level,
                                label = ThinkingLevels.label(level),
                                hint = if (level == "off") "不产出思考" else null,
                            ),
                        )
                    }
                },
            )
        }

        return ReasoningUi(
            label = ThinkingLevels.effortLabel(sessionEffort ?: fallbackEffort),
            sourceLabel = if (sessionEffort != null) {
                "本会话覆盖"
            } else {
                "跟随预设「${ThinkingLevels.effortLabel(fallbackEffort)}」"
            },
            value = sessionEffort,
            selectedValue = sessionEffort,
            overridden = sessionEffort != null,
            usesCapability = false,
            options = buildList {
                add(ReasoningOption(null, FOLLOW_PRESET_LABEL, ThinkingLevels.effortLabel(fallbackEffort)))
                ThinkingLevels.efforts.forEach { effort ->
                    add(ReasoningOption(value = effort, label = ThinkingLevels.effortLabel(effort)))
                }
            },
        )
    }
}
