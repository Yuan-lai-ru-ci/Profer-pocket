package com.profer.pocket.chat

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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.profer.pocket.designsystem.component.ProferActivityLine
import com.profer.pocket.designsystem.component.ProferComposer
import com.profer.pocket.designsystem.component.ProferConnectionBanner
import com.profer.pocket.designsystem.component.ProferEmptyState
import com.profer.pocket.designsystem.component.ProferIconAction
import com.profer.pocket.designsystem.component.ProferNotice
import com.profer.pocket.designsystem.component.ProferThinkingCard
import com.profer.pocket.designsystem.component.ProferUserMessage
import com.profer.pocket.designsystem.theme.ProferSizes
import com.profer.pocket.designsystem.theme.ProferSpacing
import kotlinx.coroutines.launch

/**
 * Chat（普通对话）页：与 Agent 对话页共用抽屉，但不显示工具、权限与模型档位。
 * 消息只渲染正文；推理折叠；流式用行内光标。复制、智能跟随、回到最新、离线条沿用 Agent 页的做法。
 */
@Composable
fun ConversationScreen(
    state: ConversationUiState,
    drawerContent: @Composable (onCloseDrawer: () -> Unit) -> Unit,
    onInputChange: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onNewConversation: () -> Unit,
    modelOptions: List<ModelOption> = emptyList(),
    onSelectModel: (ModelOption) -> Unit = {},
    modifier: Modifier = Modifier,
    onRetryConnection: () -> Unit = {},
    drawerState: PocketDrawerState = rememberPocketDrawerState(),
    chatTools: List<com.profer.pocket.model.ChatToolSummary> = emptyList(),
    onToggleTool: (String, Boolean) -> Unit = { _, _ -> },
) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val clipboard = LocalClipboardManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val density = LocalDensity.current
    val imeBottom = WindowInsets.ime.getBottom(density)
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
    LaunchedEffect(state.messages.size, state.streaming, state.draft, imeBottom, state.error) {
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

    PocketDrawerLayout(
        modifier = modifier,
        drawerState = drawerState,
        drawerContent = {
            if (drawerState.isOpen) {
                drawerContent { drawerState.close() }
            }
        },
    ) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            snackbarHost = {
                SnackbarHost(
                    hostState = snackbarHostState,
                    snackbar = { data ->
                        Snackbar(
                            snackbarData = data,
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                            contentColor = MaterialTheme.colorScheme.onSurface,
                            shape = RoundedCornerShape(12.dp),
                        )
                    },
                )
            },
            topBar = {
                ConversationTopBar(
                    title = state.title.ifBlank { "Chat" },
                    onOpenDrawer = { drawerState.open() },
                    onNewConversation = onNewConversation,
                )
            },
            bottomBar = {
                ProferComposer(
                    value = state.draft,
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
            },
        ) { padding ->
            Column(modifier = Modifier.padding(padding)) {
                state.offlineLabel?.let { label ->
                    ProferConnectionBanner(
                        text = label,
                        actionLabel = "重连",
                        onAction = onRetryConnection,
                    )
                }
                if (state.conversationId == null) {
                    ProferEmptyState(
                        title = "选择一段对话",
                        description = "Chat 是电脑上的普通对话；从左侧列表选一段，或新建。",
                        primaryLabel = "新建对话",
                        onPrimary = onNewConversation,
                        modifier = Modifier.fillMaxSize(),
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

                        if (!atBottom && state.messages.isNotEmpty() && imeBottom == 0) {
                            Box(
                                modifier = Modifier
                                    .align(Alignment.BottomEnd)
                                    .padding(end = ProferSpacing.sm, bottom = ProferSpacing.sm)
                                    .size(ProferSizes.minTouchTarget)
                                    .clip(CircleShape)
                                    .clickable {
                                        scope.launch {
                                            listState.scrollToBottom()
                                            unseenCount = 0
                                        }
                                    },
                                contentAlignment = Alignment.Center,
                            ) {
                                Surface(
                                    modifier = Modifier.height(36.dp),
                                    shape = CircleShape,
                                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                                    shadowElevation = 1.dp,
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 10.dp),
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
                        }

                        // 滚动裁切处做一层很浅的渐隐，避免半行文字硬切在顶栏或输入区边上。
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopCenter)
                                .fillMaxWidth()
                                .height(18.dp)
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
                                .height(14.dp)
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
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .heightIn(min = ProferSizes.minTouchTarget)
            .padding(start = 2.dp, end = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ProferIconAction(
            icon = Icons.Outlined.Menu,
            contentDescription = "打开对话列表",
            onClick = onOpenDrawer,
        )
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 4.dp),
        )
        ProferIconAction(
            icon = Icons.Outlined.Add,
            contentDescription = "新建对话",
            onClick = onNewConversation,
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
    LazyColumn(
        state = listState,
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = ProferSpacing.lg, vertical = ProferSpacing.md),
        verticalArrangement = Arrangement.spacedBy(ProferSpacing.xl),
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
        items(state.messages, key = { it.id }) { message ->
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
                    if (state.streamReasoning.isNotBlank()) {
                        var expanded by remember { mutableStateOf(false) }
                        ProferThinkingCard(
                            durationLabel = "思考过程",
                            body = state.streamReasoning,
                            expanded = expanded,
                            onToggle = { expanded = !expanded },
                            labelPrefix = "",
                        )
                    }
                    if (state.streamText.isBlank()) {
                        ProferActivityLine(text = "Profer 正在生成…")
                    } else {
                        ProferRichText(
                            blocks = parseRichBlocks(state.streamText),
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
        message.reasoning?.takeIf { it.isNotBlank() }?.let { reasoning ->
            var expanded by rememberSaveable(message.id) { mutableStateOf(false) }
            ProferThinkingCard(
                durationLabel = "思考过程",
                body = reasoning,
                expanded = expanded,
                onToggle = { expanded = !expanded },
                labelPrefix = "",
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
