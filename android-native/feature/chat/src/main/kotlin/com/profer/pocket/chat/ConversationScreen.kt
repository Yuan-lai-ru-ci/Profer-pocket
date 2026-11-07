package com.profer.pocket.chat

import com.profer.pocket.designsystem.component.ProferHeaderRow
import com.profer.pocket.designsystem.component.proferBackdrop
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.MenuOpen
import androidx.compose.material.icons.automirrored.outlined.ViewSidebar
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.profer.pocket.designsystem.component.ProferActivityLine
import com.profer.pocket.designsystem.component.ProferComposer
import com.profer.pocket.designsystem.component.ProferConnectionBanner
import com.profer.pocket.designsystem.component.ProferEmptyState
import com.profer.pocket.designsystem.adaptive.rememberProferWindowSize
import com.profer.pocket.designsystem.component.ProferIconAction
import com.profer.pocket.designsystem.component.proferFloatingSurface
import com.profer.pocket.designsystem.component.ProferNotice
import com.profer.pocket.designsystem.component.ProferThinkingCard
import com.profer.pocket.designsystem.component.ProferToolCallCard
import com.profer.pocket.designsystem.component.ProferUserMessage
import com.profer.pocket.designsystem.theme.ProferSizes
import com.profer.pocket.designsystem.theme.ProferSpacing
import kotlinx.coroutines.launch

/**
 * Chat（普通对话）页：与 Agent 对话页共用抽屉。
 * 消息渲染正文、折叠的思考与逐条工具活动；权限与模型档位不在本页。
 * 流式用行内光标。复制、智能跟随、回到最新、离线条沿用 Agent 页的做法。
 */
@Composable
fun ConversationScreen(
    state: ConversationUiState,
    /** 当前草稿：由宿主从同步草稿流传入，不走 state 的后台映射，避免输入回声滞后。 */
    draft: String,
    drawerContent: @Composable (onCloseDrawer: () -> Unit) -> Unit,
    onInputChange: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onNewConversation: () -> Unit,
    modifier: Modifier = Modifier,
    modelOptions: List<ModelOption> = emptyList(),
    onSelectModel: (ModelOption) -> Unit = {},
    onRetryConnection: () -> Unit = {},
    onOpenConnection: () -> Unit = onRetryConnection,
    drawerState: PocketDrawerState = rememberPocketDrawerState(),
    /** 双栏列表栏是否收起：与 Agent 对话共用同一姿态（由首页持有）。 */
    listPaneCollapsed: Boolean = false,
    onToggleListPane: () -> Unit = {},
    chatTools: List<com.profer.pocket.model.ChatToolSummary> = emptyList(),
    onToggleTool: (String, Boolean) -> Unit = { _, _ -> },
    onOpenFilePath: (String) -> Unit = {},
) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val clipboard = LocalClipboardManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val density = LocalDensity.current
    // 同 ChatScreen：IME 高度不进组合，只保留「是否弹起」。
    val ime = WindowInsets.ime
    val imeVisible by remember(ime) { derivedStateOf { ime.getBottom(density) > 0 } }
    val draftLines = remember(draft) { draft.count { it == '\n' } }
    val atBottom by remember { derivedStateOf { !listState.canScrollForward } }
    var toolSheetOpen by remember { mutableStateOf(false) }
    var modelSheetOpen by remember { mutableStateOf(false) }
    // 对话绑定的模型从电脑端 meta 回显；跨渠道同名模型靠 label 去重后的短名展示。
    val currentModel = modelOptions.firstOrNull {
        it.channelId == state.channelId && it.modelId == state.modelId
    }
    val modelLabel = currentModel?.label
        ?: state.modelId?.substringAfterLast('/')?.substringAfterLast(':')?.takeIf { it.isNotBlank() }
        ?: "选择模型"
    val darkTheme = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val modelIconRes = currentModel?.let { ModelLogos.drawableFor(it, darkTheme) }
        ?: ModelLogos.drawableFor(state.modelId, modelLabel, darkTheme)
    // 进入对话/首个消息到达时定位到底；之后只在贴近底部时跟随，不抢用户上滑阅读。
    var positionedConversation by remember { mutableStateOf<String?>(null) }
    var lastSeenCount by remember { mutableIntStateOf(0) }
    var unseenCount by remember { mutableIntStateOf(0) }

    LaunchedEffect(state.conversationId, state.messages.size) {
        val conversationId = state.conversationId ?: return@LaunchedEffect
        if (state.messages.isEmpty() || positionedConversation == conversationId) return@LaunchedEffect
        withFrameNanos { }
        listState.scrollToBottom()
        positionedConversation = conversationId
        lastSeenCount = state.messages.size
        unseenCount = 0
    }

    // 横屏短屏：渐隐加宽、消息间距收紧（与 Agent 页同一档）。
    val shortScreen = rememberProferWindowSize().isShort

    // 滚到底部才算看过；停在历史里时只累计新消息数，用于「回到最新」角标。
    LaunchedEffect(atBottom, state.messages.size, positionedConversation) {
        if (positionedConversation != state.conversationId) return@LaunchedEffect
        if (atBottom) {
            unseenCount = 0
            lastSeenCount = state.messages.size
        } else if (state.messages.size > lastSeenCount) {
            unseenCount += state.messages.size - lastSeenCount
            lastSeenCount = state.messages.size
        }
    }

    // 自己发出的消息必须可见：无论此前滚到哪里，都回到最新。
    LaunchedEffect(state.messages.lastOrNull()?.id) {
        if (state.messages.lastOrNull() is ConversationMessage.User) {
            withFrameNanos { }
            listState.scrollToBottom()
        }
    }

    // 新消息、开始生成、输入框增高或键盘顶起时，只在贴近底部时把列表重新锚到底部。
    LaunchedEffect(state.messages.size, state.streaming, draftLines, imeVisible, state.error) {
        val lastIndex = state.messages.lastIndex
        if (lastIndex < 0) return@LaunchedEffect
        val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
        if (lastVisible >= lastIndex - 2 || lastVisible == -1) {
            withFrameNanos { }
            listState.scrollToBottom()
        }
    }

    val copy: (String) -> Unit = { text ->
        clipboard.setText(AnnotatedString(text))
        scope.launch { snackbarHostState.showSnackbar("已复制") }
    }

    ProvideFilePreview(onOpenFile = onOpenFilePath) {
        PocketDrawerLayout(
        modifier = modifier,
        drawerState = drawerState,
        listPaneCollapsed = listPaneCollapsed,
        drawerContent = {
            // 双栏时列表常驻，必须组合；单栏仍在打开后才挂载。列表栏收起后同样不必留着这棵树。
            if (drawerState.isOpen || (rememberProferWindowSize().isTwoPane && !listPaneCollapsed)) {
                drawerContent { drawerState.close() }
            }
        },
    ) {
        // 平板/桌面下内容区可能很宽：正文与输入条限制在 760dp 内居中，行长不失控。
        // 背景铺满内容区，阅读栏限制 760dp 居中：宽屏下不出现一整条被拉长的正文。
        Box(
            modifier = Modifier
                .fillMaxSize()
                // 横屏短屏与双栏都不叠纸面渐变：渐变底部那条色带会整块落在输入条下方，
                // 在 360dp 高里非常显眼；单栏竖屏仍保留。
                .then(
                    if (shortScreen || rememberProferWindowSize().isTwoPane) {
                        Modifier.background(MaterialTheme.colorScheme.background)
                    } else {
                        Modifier.proferBackdrop()
                    },
                ),
            contentAlignment = Alignment.TopCenter,
        ) {
        Scaffold(
            modifier = Modifier
                .widthIn(max = com.profer.pocket.designsystem.theme.ProferSizes.contentMaxWidth)
                .fillMaxHeight(),
            containerColor = Color.Transparent,
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            snackbarHost = {
                SnackbarHost(
                    hostState = snackbarHostState,
                    snackbar = { data ->
                        Snackbar(
                            snackbarData = data,
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                            contentColor = MaterialTheme.colorScheme.onSurface,
                            shape = MaterialTheme.shapes.medium,
                        )
                    },
                )
            },
            topBar = {
                // 双栏下这个键不再开抽屉，而是收起/展开常驻列表栏；单栏保持原语义。
                val twoPane = rememberProferWindowSize().isTwoPane
                ConversationTopBar(
                    title = state.title.ifBlank { "Chat" },
                    onOpenDrawer = {
                        if (twoPane) {
                            onToggleListPane()
                        } else {
                            drawerState.open()
                        }
                    },
                    onNewConversation = onNewConversation,
                    listPaneCollapsed = listPaneCollapsed,
                )
            },
            bottomBar = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    ProferComposer(
                    value = draft,
                    onValueChange = onInputChange,
                    onSend = onSend,
                    onStop = onStop,
                    streaming = state.streaming,
                    modelLabel = modelLabel,
                    modelIconRes = modelIconRes,
                    modeLabel = "",
                    onModelClick = {
                        if (state.conversationId != null) modelSheetOpen = true
                    },
                    onModeClick = {},
                    showSelectors = state.conversationId != null,
                    showModeSelector = false,
                    onToolsClick = { toolSheetOpen = true },
                    toolsActive = chatTools.any { it.enabled && it.available },
                    placeholder = if (state.offline) "离线 · 等待重连" else "给 Profer 发消息…",
                )
                }
            },
        ) { padding ->
            Column(modifier = Modifier.padding(padding)) {
                state.offlineLabel?.takeIf { state.conversationId != null }?.let { label ->
                    ProferConnectionBanner(
                        text = label,
                        actionLabel = "重连",
                        onAction = onRetryConnection,
                    )
                }
                if (state.conversationId == null) {
                    ChatWelcomeState(
                        offline = state.offline,
                        onConnect = onOpenConnection,
                        onSuggestion = {},
                        onStartConversation = onNewConversation,
                        agent = false,
                        hasDraftTarget = false,
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .pointerInput(Unit) {
                                detectTapGestures(onTap = { keyboard?.hide() })
                            },
                    ) {
                        ConversationMessages(
                            state = state,
                            listState = listState,
                            streaming = state.streaming,
                            onCopy = copy,
                            modifier = Modifier.fillMaxSize(),
                        )

                        // 悬浮在消息区底部：不进 bottomBar，不占布局高度，也不压住输入条。
                        if (!atBottom && state.messages.isNotEmpty() && !imeVisible) {
                            Surface(
                                modifier = Modifier
                                    .align(Alignment.BottomCenter)
                                    .padding(bottom = ProferSpacing.sm)
                                    .heightIn(min = 32.dp)
                                    .clip(CircleShape)
                                    .clickable {
                                        scope.launch {
                                            val last = (listState.layoutInfo.totalItemsCount - 1).coerceAtLeast(0)
                                            listState.animateScrollToItem(last)
                                        }
                                    },
                                shape = CircleShape,
                                color = proferFloatingSurface(),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                                shadowElevation = 2.dp,
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    Icon(
                                        imageVector = Icons.Outlined.ArrowDownward,
                                        contentDescription = "回到最新",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(17.dp),
                                    )
                                    if (unseenCount > 0) {
                                        Text(
                                            text = "$unseenCount",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }
                        }

                        // 滚动裁切处做一层很浅的渐隐，避免半行文字硬切在顶栏或输入区边上。
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopCenter)
                                .fillMaxWidth()
                                // 短屏消息更少，半行裁切更扎眼：渐隐加宽到 22dp。
                                .height(if (shortScreen) 12.dp else 18.dp)
                                .background(
                                    Brush.verticalGradient(
                                        listOf(
                                            MaterialTheme.colorScheme.background,
                                            MaterialTheme.colorScheme.background.copy(alpha = 0f),
                                        ),
                                    ),
                                ),
                        )
                        Box(
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .fillMaxWidth()
                                .height(if (shortScreen) 8.dp else 14.dp)
                                .background(
                                    Brush.verticalGradient(
                                        listOf(
                                            MaterialTheme.colorScheme.background.copy(alpha = 0f),
                                            MaterialTheme.colorScheme.background,
                                        ),
                                    ),
                                ),
                        )
                    }

                }
            }
        }
    }
    }
    }
    if (toolSheetOpen) {
        ChatToolSheet(
            tools = chatTools,
            onToggle = onToggleTool,
            onDismiss = { toolSheetOpen = false },
        )
    }
    if (modelSheetOpen) {
        ModelPickerSheet(
            options = modelOptions,
            currentChannelId = state.channelId,
            currentModelId = state.modelId,
            currentModelLabel = modelLabel,
            streaming = state.streaming,
            onSelect = { option ->
                modelSheetOpen = false
                onSelectModel(option)
            },
            onDismiss = { modelSheetOpen = false },
        )
        }
}

@Composable
private fun ConversationTopBar(
    title: String,
    onOpenDrawer: () -> Unit,
    onNewConversation: () -> Unit,
    /** 双栏列表栏已收起：左侧键从「收起」变成「展开」。单栏恒为 false。 */
    listPaneCollapsed: Boolean = false,
) {
    val window = rememberProferWindowSize()
    val twoPane = window.isTwoPane
    val compact = window.isShort
    ProferHeaderRow(compact = compact) {
        ProferIconAction(
            // 与 Agent 对话同构：双栏用面板图标（收起/展开），单栏用汉堡键（抽屉）。
            icon = when {
                !twoPane -> Icons.Outlined.Menu
                listPaneCollapsed -> Icons.AutoMirrored.Outlined.ViewSidebar
                else -> Icons.AutoMirrored.Outlined.MenuOpen
            },
            contentDescription = when {
                !twoPane -> "打开对话列表"
                listPaneCollapsed -> "展开对话列表"
                else -> "收起对话列表"
            },
            onClick = onOpenDrawer,
            chrome = !compact,
        )
        // 与 Agent 对话同构：标题进浮起胶囊，字号与内边距走同一套 token。
        // 外层占满中间，胶囊按内容收窄，右侧新建键仍贴边。
        Box(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                // 横屏短屏不再用浮层胶囊：桌面版会话头是一条平铺标题，省下的高度留给正文。
                modifier = if (compact) {
                    Modifier
                } else {
                    Modifier
                        .clip(MaterialTheme.shapes.extraLarge)
                        .background(proferFloatingSurface())
                        .padding(horizontal = ProferSpacing.md, vertical = ProferSpacing.sm)
                },
            )
        }
        ProferIconAction(
            icon = Icons.Outlined.Add,
            contentDescription = "新建对话",
            onClick = onNewConversation,
            chrome = !compact,
        )
    }
}

@Composable
private fun ConversationMessages(
    state: ConversationUiState,
    listState: androidx.compose.foundation.lazy.LazyListState,
    streaming: Boolean,
    onCopy: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val window = rememberProferWindowSize()
    val shortScreen = window.isShort
    LazyColumn(
        state = listState,
        modifier = modifier,
        contentPadding = PaddingValues(
            horizontal = if (window.isTwoPane) ProferSpacing.xl else ProferSpacing.lg,
            vertical = if (shortScreen) ProferSpacing.sm else ProferSpacing.md,
        ),
        verticalArrangement = Arrangement.spacedBy(
            if (shortScreen) ProferSpacing.lg else ProferSpacing.xl,
        ),
    ) {
        if (state.messages.isEmpty() && !state.streaming) {
            item(key = "empty") {
                ProferEmptyState(
                    title = "开始一段新对话",
                    description = "这里的对话和电脑上的 Chat 同步，不会创建 Agent 会话。",
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = ProferSpacing.xxl),
                )
            }
        }
        items(
            items = state.messages,
            key = { it.id },
            contentType = { it::class },
        ) { message ->
            when (message) {
                is ConversationMessage.User -> ProferUserMessage(
                    text = message.text,
                    meta = message.meta,
                    actions = {
                        MessageActionIcon(Icons.Outlined.ContentCopy, "复制消息") {
                            onCopy(message.text)
                        }
                    },
                )

                is ConversationMessage.Assistant -> Column(
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    AssistantMessage(message)
                    // 整轮结束后才出现，左对齐贴正文，不与阅读视线抢位置。
                    if (!streaming && message.text.isNotBlank()) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.Start,
                        ) {
                            MessageActionIcon(Icons.Outlined.ContentCopy, "复制回复") {
                                onCopy(message.text)
                            }
                        }
                    }
                }
            }
        }
        if (state.streaming) {
            item(key = "stream") {
                Column(verticalArrangement = Arrangement.spacedBy(ProferSpacing.sm)) {
                    // 与桌面 Chat 同序：工具活动 → 思考 → 正文。
                    state.streamTools.forEach { tool -> ChatToolRow(tool) }
                    if (state.streamReasoning.isNotBlank()) {
                        var expanded by remember { mutableStateOf(false) }
                        ProferThinkingCard(
                            body = state.streamReasoning,
                            expanded = expanded,
                            onToggle = { expanded = !expanded },
                        )
                    }
                    if (state.streamText.isBlank()) {
                        ProferActivityLine(text = "Profer 正在生成…")
                    } else {
                        // 输入框每敲一个字都会重组这一页，不能跟着重新分块 Markdown。
                        // 流式正文只重算尾块，中间态不进全局缓存。
                        val blocks = remember(state.conversationId, state.streamText) {
                            streamingRichBlocks("conversation:" + state.conversationId, state.streamText)
                        }
                         ProferRichText(
                             blocks = blocks,
                             showCursor = true,
                         )
                    }
                }
            }
        }
        state.error?.let { error ->
            item(key = "error") {
                ProferNotice(
                    text = error,
                    tint = MaterialTheme.colorScheme.error,
                )
            }
        }

        // 末尾留 1dp：scrollToItem 对齐的是项顶部，靠它才能真正贴到底。
        item(key = "bottom-anchor") {
            androidx.compose.foundation.layout.Spacer(
                modifier = Modifier.height(1.dp),
            )
        }
    }
}

@Composable
private fun AssistantMessage(message: ConversationMessage.Assistant) {
    Column(verticalArrangement = Arrangement.spacedBy(ProferSpacing.sm)) {
        // 与桌面 Chat 同序：工具活动 → 思考 → 正文；工具行逐条折叠，点开看参数与结果。
        message.tools.forEach { tool -> ChatToolRow(tool) }
        message.reasoning?.takeIf { it.isNotBlank() }?.let { reasoning ->
            var expanded by rememberSaveable(message.id) { mutableStateOf(false) }
            ProferThinkingCard(
                body = reasoning,
                expanded = expanded,
                onToggle = { expanded = !expanded },
            )
        }
        ProferRichText(text = message.text)
        if (message.stopped) {
            Text(
                text = "已停止",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        message.error?.takeIf { it.isNotBlank() }?.let { error ->
            ProferNotice(text = error, tint = MaterialTheme.colorScheme.error)
        }
    }
}

/** Chat 模式的一条工具活动：图标 + 标题 · 目标 + 状态，点开看参数/结果。 */
@Composable
private fun ChatToolRow(tool: ToolBlock) {
    var expanded by rememberSaveable(tool.id) { mutableStateOf(false) }
    ProferToolCallCard(
        icon = tool.kind.icon(),
        title = tool.title,
        subtitle = tool.subtitle,
        status = tool.status,
        expanded = expanded,
        onToggle = { expanded = !expanded },
        input = tool.input,
        output = tool.output,
        error = tool.error,
    )
}
