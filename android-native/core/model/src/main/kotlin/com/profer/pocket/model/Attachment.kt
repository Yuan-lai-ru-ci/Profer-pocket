package com.profer.pocket.model

/**
 * 已保存到电脑端会话目录的附件。
 *
 * 与桌面 `<attached_files>` 协议一致：消息正文前拼
 * `<attached_files>\n- filename: path\n</attached_files>`，
 * Agent 通过 Read 工具读取（图片也走同一条链路）。
 */
data class AgentAttachment(
    val filename: String,
    val path: String,
)
