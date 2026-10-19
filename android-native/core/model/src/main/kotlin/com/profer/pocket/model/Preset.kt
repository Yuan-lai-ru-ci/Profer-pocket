package com.profer.pocket.model

/**
 * Agent 预设（岗位）：模型之外的另一层工作配置。
 *
 * 移动端只消费展示与切换需要的字段：名称、说明、来源、当前工作区是否生效。
 * 提示词段、技能/MCP 白名单等配置留在电脑端，不下发。
 */
data class AgentPresetSummary(
    val id: String,
    val name: String,
    val description: String = "",
    /** builtin-meta / user-global / workspace；旧服务端可能缺省。 */
    val scope: String? = null,
    val isBuiltin: Boolean = false,
    /** 工作区页面使用：该预设当前是否在目标工作区生效；null 视为生效（旧服务端）。 */
    val enabledInWorkspace: Boolean? = null,
    val effort: String? = null,
    val permissionMode: String? = null,
) {
    val displayName: String get() = name.ifBlank { id }

    /** 明确停用的预设不出现在选择器里（与桌面 selectableAgentPresets 同口径）。 */
    val isSelectable: Boolean get() = enabledInWorkspace != false

    /** 选择器分组标题：内置 / 全局 / 当前工作区。 */
    val groupLabel: String
        get() = when {
            isBuiltin || scope == "builtin-meta" -> "内置"
            scope == "user-global" -> "全局"
            else -> "当前工作区"
        }
}

/** Chat 模式的可开关工具（记忆、联网搜索、生图等）。开关与电脑端共享。 */
data class ChatToolSummary(
    val id: String,
    val name: String,
    val description: String = "",
    val icon: String? = null,
    val category: String = "builtin",
    val enabled: Boolean = false,
    /** 凭据是否已配置；不可用的工具即使打开也不会真正执行。 */
    val available: Boolean = true,
) {
    val displayName: String get() = name.ifBlank { id }
    val canToggle: Boolean get() = available
}
