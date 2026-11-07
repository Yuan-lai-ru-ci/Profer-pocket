package com.profer.pocket.chat

/**
 * 执行过程折叠行的一句话摘要，与桌面 `buildProcessGroupSummary` 同口径：
 * 工具与思考分开计数——笼统写「N 条消息」会让用户在展开后找不到自己期待的思考。
 */
internal fun processGroupSummary(toolCount: Int, thinkingCount: Int): String {
    val parts = buildList {
        if (toolCount > 0) add("$toolCount 次工具调用")
        if (thinkingCount > 0) add("$thinkingCount 段思考")
    }
    return if (parts.isEmpty()) "执行过程" else "执行过程：${parts.joinToString("，")}"
}
