package com.profer.pocket.chat

import com.profer.pocket.model.AgentAttachment

private val ATTACHED_BLOCK = Regex("<attached_files>\\s*([\\s\\S]*?)</attached_files>")
private val ATTACHED_LINE = Regex("^-\\s*(.+?):\\s*(/.+)$")

/**
 * 解析桌面协议里的附件块：
 * `<attached_files>\n- filename: /abs/path\n</attached_files>`
 *
 * 只认绝对路径，避免把正文里的普通「- 说明: 内容」误判成附件。
 */
fun parseAttachedFiles(text: String): List<AgentAttachment> {
    val block = ATTACHED_BLOCK.find(text)?.groupValues?.getOrNull(1) ?: return emptyList()
    return block.lineSequence().mapNotNull { line ->
        val match = ATTACHED_LINE.find(line.trim()) ?: return@mapNotNull null
        val filename = match.groupValues[1].trim()
        val path = match.groupValues[2].trim()
        if (filename.isEmpty() || !path.startsWith("/")) null else AgentAttachment(filename, path)
    }.toList()
}

/** 气泡正文不显示协议块本身。 */
fun stripAttachedFiles(text: String): String =
    text.replace(ATTACHED_BLOCK, "").trim()
