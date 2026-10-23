package com.profer.pocket.chat

import com.profer.pocket.designsystem.component.ToolStatus

/**
 * 设计原型数据：形状按真实会话组织，文字取自 Profer 的真实工作场景。
 * 这里不接连接层，只用于验证排版、密度与状态表达。
 */
object ChatPreviewData {

    val connected: ChatUiState = ChatUiState(
        title = "会话恢复丢消息排查",
        project = "profer-main · main",
        modelLabel = "Opus 4.6",
        modeLabel = "执行",
        streaming = true,
        offline = false,
        pendingPermission = PermissionRequest(
            id = "perm-1",
            title = "需要执行许可",
            description = "Bash 想运行下面这条命令。它只读取测试文件并执行测试，不会改动工作树。",
            command = "bun test --isolate src/renderer/pocket/ws-client.test.ts",
            contextLabel = "来自 profer-main · Bash · 预计 1.2s",
        ),
        messages = listOf(
            ChatMessage.User(
                id = "u1",
                text = "重连之后 Pocket 会丢最后几条消息，帮我定位一下。",
                meta = "22:14 · 已送达",
            ),
            ChatMessage.Assistant(
                id = "a1",
                thinking = ThinkingBlock(
                    durationLabel = "18 秒",
                    body = "丢消息通常来自两个位置：重连后没有按游标拉增量，或者断线时把游标提前推进了。先看重连分支，再确认服务端是否保留未确认消息。",
                ),
                tools = listOf(
                    ToolBlock(
                        id = "t1",
                        kind = ToolKind.Read,
                        title = "读取 ws-client.ts",
                        subtitle = "src/renderer/pocket/ws-client.ts",
                        status = ToolStatus.Success,
                        detail = "private handleClose() {\n  this.cursor = this.lastMessage?.id\n  this.scheduleReconnect()\n}",
                    ),
                    ToolBlock(
                        id = "t2",
                        kind = ToolKind.Search,
                        title = "搜索 resumeCursor",
                        subtitle = "3 处匹配 · 2 个文件",
                        status = ToolStatus.Success,
                    ),
                    ToolBlock(
                        id = "t3",
                        kind = ToolKind.Bash,
                        title = "运行 ws-client 测试",
                        subtitle = "bun test ws-client.test.ts",
                        status = ToolStatus.Success,
                        detail = "12 pass, 0 fail\nRan 12 tests across 1 file. [1.20s]",
                    ),
                ),
                blocks = listOf(
                    ContentBlock.Heading("结论"),
                    ContentBlock.Paragraph(
                        "重连时 `session-cursor` 在 onclose 里被写成当前最后一条，但服务端并不知道客户端收到了哪些消息。" +
                            "**游标前移早于确认**，恢复时就会跳过这段窗口。",
                    ),
                    ContentBlock.Bullets(
                        listOf(
                            "把游标推进移到 agent_end 之后",
                            "重连携带 lastAckedMessageId，由服务端回放增量",
                            "首次恢复只拉最近 50 条，避免全量重放",
                        ),
                    ),
                    ContentBlock.Code(
                        language = "typescript",
                        code = "const cursor = session.lastAckedMessageId\nawait client.resume({ sessionId, cursor, limit: 50 })",
                    ),
                ),
            ),
            ChatMessage.User(
                id = "u2",
                text = "先补增量拉取，别动桌面端。",
                meta = "22:21 · 已送达",
            ),
            ChatMessage.Assistant(
                id = "a2",
                tools = listOf(
                    ToolBlock(
                        id = "t4",
                        kind = ToolKind.Edit,
                        title = "改写重连分支",
                        subtitle = "ws-client.ts · +18 −4",
                        status = ToolStatus.Running,
                        detail = "await client.resume({ sessionId, cursor: lastAckedMessageId })",
                    ),
                ),
                blocks = listOf(
                    ContentBlock.Paragraph(
                        "正在改 connect() 的重连分支：断线后不再推进游标，重连成功后再按 lastAckedMessageId 拉增量。桌面端链路保持不动。",
                    ),
                ),
                streaming = true,
            ),
        ),
    )

    val offline: ChatUiState = connected.copy(
        offline = true,
        streaming = false,
        pendingPermission = null,
    )

    /**
     * 每个会话各自的内容与状态。切换会话时 UI 不应只换标题。
     */
    fun sessionStates(): Map<String, ChatUiState> = mapOf(
        "s1" to connected,
        "s2" to connected.copy(
            title = "移动端输入区键盘遮挡",
            messages = listOf(
                ChatMessage.User(
                    id = "u-s2-1",
                    text = "键盘弹起后输入框被挡住，看看 windowSoftInputMode。",
                    meta = "21:40 · 已送达",
                ),
                ChatMessage.Assistant(
                    id = "a-s2-1",
                    tools = listOf(
                        ToolBlock(
                            id = "s2-t1",
                            kind = ToolKind.Read,
                            title = "读取 AndroidManifest",
                            subtitle = "pocket-app/.../AndroidManifest.xml",
                            status = ToolStatus.Success,
                        ),
                    ),
                    blocks = listOf(
                        ContentBlock.Paragraph(
                            "Manifest 里是 adjustResize，但 WebView 外层又手动加了一层 keyboard padding，" +
                                "**两者叠加**导致输入框被顶两次。",
                        ),
                        ContentBlock.Quote("建议只保留系统 resize，删掉手动 padding。"),
                    ),
                ),
            ),
            streaming = false,
            pendingPermission = null,
        ),
        "s3" to connected.copy(
            title = "整理 Pocket 发布清单",
            modelLabel = "Sonnet 4.5",
            messages = listOf(
                ChatMessage.User(
                    id = "u-s3-1",
                    text = "把这周的修复整理成发布清单。",
                    meta = "昨天 · 已送达",
                ),
                ChatMessage.Assistant(
                    id = "a-s3-1",
                    blocks = listOf(
                        ContentBlock.Heading("本周发布"),
                        ContentBlock.Bullets(
                            listOf(
                                "重连后按游标拉增量",
                                "模型名与上下文占用回到工具栏",
                                "Android 原生版 UI 基线",
                            ),
                        ),
                        ContentBlock.Paragraph("版本号建议 0.16.10，先发内部 debug 包。"),
                    ),
                ),
            ),
            streaming = false,
            pendingPermission = null,
        ),
        "s4" to connected.copy(
            title = "官网定价页文案",
            modelLabel = "Sonnet 4.5",
            messages = emptyList(),
            streaming = false,
            pendingPermission = null,
        ),
        "s5" to connected.copy(
            title = "移动端截图替换",
            modelLabel = "Sonnet 4.5",
            messages = emptyList(),
            streaming = false,
            pendingPermission = null,
        ),
        "s6" to connected.copy(
            title = "Android 原生版设计基线",
            messages = emptyList(),
            streaming = false,
            pendingPermission = null,
        ),
    )

    val empty: ChatUiState = ChatUiState(
        title = "新会话",
        project = "profer-main",
        modelLabel = "Opus 4.6",
        modeLabel = "执行",
        messages = emptyList(),
        streaming = false,
        offline = false,
        pendingPermission = null,
    )
}
