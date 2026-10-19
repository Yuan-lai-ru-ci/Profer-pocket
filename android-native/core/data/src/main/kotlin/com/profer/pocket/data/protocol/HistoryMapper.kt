package com.profer.pocket.data.protocol

import com.profer.pocket.model.ChatEntry
import com.profer.pocket.model.ToolTally
import com.profer.pocket.model.TurnFile

/**
 * 历史消息 → 移动端会话条目。
 *
 * 只保留用户正文、助手正文与工具次数；与实时归约共用同一套去重规则，
 * 避免“打开会话时工具次数翻倍”。
 */
object HistoryMapper {

    data class History(
        val entries: List<ChatEntry>,
        val tools: ToolTally = ToolTally(),
        val countedToolIds: Set<String> = emptySet(),
        val files: List<String> = emptyList(),
    )

    fun map(messages: List<SdkMessageView>, now: Long): History {
        val entries = mutableListOf<ChatEntry>()
        var tools = ToolTally()
        val counted = mutableSetOf<String>()
        var turnFiles = mutableListOf<TurnFile>()
        val failed = mutableSetOf<String>()

        messages.forEachIndexed { index, message ->
            if (message.partial) return@forEachIndexed
            when (message.type) {
                "user" -> {
                    // tool_result 消息只有失败标记：先记账，等回合汇总时剔除对应文件。
                    message.toolResults.filter { it.isError }.forEach { failed += it.toolUseId }
                    // 历史里按用户回合分段，只保留最后一个回合的工具次数与文件。
                    val text = message.text.trim()
                    if (text.isNotEmpty()) {
                        tools = ToolTally()
                        turnFiles = mutableListOf()
                        failed.clear()
                        entries += ChatEntry.User(
                            id = message.uuid?.takeIf { it.isNotBlank() } ?: "history-user-$index",
                            text = text,
                            // 历史消息优先用落盘时间：用加载时刻会把每条旧消息都显示成“刚刚”。
                            at = message.createdAt ?: now,
                        )
                    }
                }

                "assistant" -> {
                    message.toolUses.forEachIndexed { toolIndex, tool ->
                        val key = tool.id?.takeIf { it.isNotBlank() } ?: "history-$index-$toolIndex-${tool.name}"
                        if (counted.add(key)) tools = tools.record(tool.name)
                        val path = tool.filePath
                        if (path != null && tool.name.lowercase() in MUTATING_TOOLS &&
                            turnFiles.none { it.toolId == key }
                        ) {
                            turnFiles += TurnFile(key, path)
                        }
                    }
                    val text = message.text.trim()
                    if (text.isNotEmpty()) {
                        entries += ChatEntry.Assistant(
                            id = message.uuid?.takeIf { it.isNotBlank() } ?: "history-assistant-$index",
                            text = text,
                            streaming = false,
                            at = message.createdAt ?: now,
                        )
                    }
                }
            }
        }

        return History(
            entries = entries,
            tools = tools,
            countedToolIds = counted,
            files = turnFiles
                .filterNot { it.toolId in failed }
                .distinctBy { it.path }
                .map { it.path },
        )
    }
}

private val MUTATING_TOOLS = setOf("edit", "write", "multiedit", "notebookedit")
