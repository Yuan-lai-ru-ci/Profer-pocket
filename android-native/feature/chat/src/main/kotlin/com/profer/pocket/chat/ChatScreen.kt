package com.profer.pocket.chat

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Replay
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DriveFileRenameOutline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.snapshotFlow
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.profer.pocket.designsystem.component.ProferActivityLine
import com.profer.pocket.designsystem.component.ProferComposer
import com.profer.pocket.designsystem.component.ProferConnectionBanner
import com.profer.pocket.designsystem.component.ProferDivider
import com.profer.pocket.designsystem.component.ProferEmptyState
import com.profer.pocket.designsystem.component.ProferIconAction
import com.profer.pocket.designsystem.component.ProferNotice
import com.profer.pocket.designsystem.component.ProferPermissionPromptBar
import com.profer.pocket.designsystem.component.ProferPermissionSheet
import com.profer.pocket.designsystem.component.ProferStatusDot
import com.profer.pocket.designsystem.component.ProferThinkingCard
import com.profer.pocket.designsystem.component.ProferToolCallCard
import com.profer.pocket.designsystem.component.ProferUserMessage
import com.profer.pocket.designsystem.component.ToolStatus
import com.profer.pocket.designsystem.theme.ProferThemeExtras
import com.profer.pocket.model.SendStatus
import com.profer.pocket.designsystem.theme.ProferSizes
import com.profer.pocket.designsystem.theme.ProferSpacing
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    state: ChatUiState,
    drawerContent: @Composable (onCloseDrawer: () -> Unit) -> Unit,
    modelOptions: List<ModelOption> = emptyList(),
    onSelectModel: (ModelOption) -> Unit = {},
    onSelectMode: (String) -> Unit = {},
    onInputChange: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    /** 手动压缩上下文：由宿主发送 /compact。 */
    onCompactContext: () -> Unit = {},
    onAllowPermission: () -> Unit,
    onAlwaysAllowPermission: () -> Unit = onAllowPermission,
    onDenyPermission: () -> Unit,
    onOpenSettings: () -> Unit,
    /** Agent 预设（岗位）：Chat 模式不传。 */
    presetLabel: String? = null,
    presets: List<com.profer.pocket.model.AgentPresetSummary> = emptyList(),
    onSelectPreset: (String) -> Unit = {},
    onRefreshPresets: () -> Unit = {},
    /** 修改重发：回退到这条用户消息之前并重发新内容。 */
    onRewriteMessage: (String, String) -> Unit = { _, _ -> },
    /** 模型产出的图片：点开看大图，由宿主打开预览。 */
    onOpenImage: (com.profer.pocket.model.ChatImage) -> Unit = {},
    /** 图片字节加载器：按路径向电脑端拉取缩略图，失败返回 null。 */
    imageLoader: suspend (String) -> androidx.compose.ui.graphics.ImageBitmap? = { null },
    /** 发送失败后的单条重试。 */
    onRetrySend: (String) -> Unit = {},
    /** 本机最后已读消息 id：用来插入「新消息」分隔并定位。 */
    lastReadMessageId: String? = null,
    onReadLatest: (String) -> Unit = {},
    /** 回答 Agent 追问：key 为问题原文，value 为选项 label。 */
    onAnswerQuestion: (Map<String, String>) -> Unit = {},
    /** 点开附件预览（图片/文本由宿主加载）。 */
    onOpenAttachment: (com.profer.pocket.model.AgentAttachment) -> Unit = {},
    /** 待发送图片附件（已保存到电脑端会话目录）。 */
    attachments: List<com.profer.pocket.model.AgentAttachment> = emptyList(),
    onAttach: (() -> Unit)? = null,
    onRemoveAttachment: (String) -> Unit = {},
    modifier: Modifier = Modifier,
    onRetryConnection: () -> Unit = {},
    onResendText: (String) -> Unit = {},
    message: String? = null,
    onMessageShown: () -> Unit = {},
    onRenameSession: () -> Unit = {},
    onTogglePin: () -> Unit = {},
    onDeleteSession: () -> Unit = {},
    /** 抽屉状态由首页持有：Agent / Chat 切换时抽屉不跟着关。 */
    drawerState: PocketDrawerState = rememberPocketDrawerState(),
) {
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val density = LocalDensity.current
    val imeBottom = WindowInsets.ime.getBottom(density)
    val composerFocus = remember { FocusRequester() }
    val snackbarHostState = remember { SnackbarHostState() }
    val clipboard = LocalClipboardManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val atBottom by remember { derivedStateOf { !listState.canScrollForward } }
    // 审批到达时展开一次；用户收起后，返回本页不会再次弹出。
    val permissionId = state.pendingPermission?.id
    var permissionSheetOpen by rememberSaveable(permissionId) {
        mutableStateOf(permissionId != null)
    }
    val questionId = state.pendingQuestionId
    var questionSheetOpen by rememberSaveable(questionId) {
        mutableStateOf(questionId != null)
    }
    var modelSheetOpen by remember { mutableStateOf(false) }
    var modeSheetOpen by remember { mutableStateOf(false) }
    var presetSheetOpen by remember { mutableStateOf(false) }
    var contextSheetOpen by remember { mutableStateOf(false) }
    // 修改重发：同一时刻只编辑一条；文本单独存，取消时丢弃。
    var editingMessageId by remember { mutableStateOf<String?>(null) }
    var editingText by remember { mutableStateOf("") }

    // 新消息分隔：只在本机阅读位置之后插入；没有更早位置或已在末尾时不显示。
    val dividerIndex = remember(state.messages, lastReadMessageId) {
        lastReadMessageId?.let { id ->
            state.messages.indexOfFirst { it.id == id }
                .takeIf { it >= 0 && it < state.messages.lastIndex }
        }
    }
    var positionedSession by remember { mutableStateOf<String?>(null) }
    var lastSeenCount by remember { mutableIntStateOf(0) }
    var unseenCount by remember { mutableIntStateOf(0) }

    // 会话操作结果统一从宿主传入，在对话页用同一条 Snackbar 反馈。
    LaunchedEffect(message) {
        if (message != null) {
            snackbarHostState.showSnackbar(message)
            onMessageShown()
        }
    }

    // 进入会话：有本机阅读位置就停在第一条新消息，否则到底；只定位一次。
    LaunchedEffect(state.sessionId, state.messages.size) {
        val sessionId = state.sessionId ?: return@LaunchedEffect
        if (state.messages.isEmpty() || positionedSession == sessionId) return@LaunchedEffect
        withFrameNanos { }
        if (dividerIndex != null) {
            listState.scrollToItem(dividerIndex + 1)
        } else {
            listState.scrollToBottom()
        }
        positionedSession = sessionId
        lastSeenCount = state.messages.size
        unseenCount = 0
    }

    // 滚到底部才记为已读；停在历史里不标记。
    LaunchedEffect(atBottom, state.messages.lastOrNull()?.id, positionedSession) {
        if (!atBottom || positionedSession != state.sessionId) return@LaunchedEffect
        state.messages.lastOrNull()?.id?.let(onReadLatest)
    }

    // 离开底部后到达的新消息计数；回到底部清零。
    LaunchedEffect(state.messages.size, atBottom, positionedSession) {
        if (positionedSession != state.sessionId) return@LaunchedEffect
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
        if (state.messages.lastOrNull() is ChatMessage.User) {
            withFrameNanos { }
            listState.scrollToBottom()
        }
    }

    // 新消息只在用户接近底部时跟随；用户上滑阅读时不抢滚动。
    // 草稿变长（输入框增高）或键盘顶起时，同样把列表重新锚到底部。
    // 工具卡是新消息之外的末层内容，它增加时也要跟随，否则新工具会出现在屏幕外。
    LaunchedEffect(state.messages.size, state.toolCalls.size, state.streaming, state.draft, imeBottom) {
        val lastIndex = state.messages.lastIndex
        if (lastIndex >= 0) {
            val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
            if (lastVisible >= lastIndex - 2 || lastVisible == -1) {
                // 新消息刚进入列表时 layoutInfo 还是旧的，等一帧再滚，滚完再校正一次。
                withFrameNanos { }
                listState.scrollToBottom()
            }
        }
    }

    val currentModelOption = modelOptions.firstOrNull {
        it.channelId == state.channelId && it.modelId == state.modelId
    } ?: modelOptions.firstOrNull {
        it.modelId == state.modelId &&
            (it.label == state.modelLabel || it.rawLabel == state.modelLabel)
    }
    val darkTheme = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val modelIconRes = currentModelOption?.let { ModelLogos.drawableFor(it, darkTheme) }
        ?: ModelLogos.drawableFor(state.modelId, state.modelLabel, darkTheme)

    PocketDrawerLayout(
        modifier = modifier,
        drawerState = drawerState,
        drawerContent = {
            // 关闭状态下不组合几百条会话目录：否则冷启动会把整张抽屉树
            // （项目分组 + 会话行 + 弹层宿主）一起组合/测量，主线程首帧被拖到十几秒。
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
                ChatTopBar(
                    title = state.title,
                    contextUsage = state.contextUsage,
                    contextWindow = state.contextWindow,
                    onContextClick = { contextSheetOpen = true },
                    hasSession = state.sessionId != null,
                    pinned = state.pinned,
                    onOpenDrawer = {
                        keyboard?.hide()
                        drawerState.open()
                    },
                    onOpenSettings = onOpenSettings,
                    onRenameSession = onRenameSession,
                    onTogglePin = onTogglePin,
                    onDeleteSession = onDeleteSession,
                )
            },
            bottomBar = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    // 审批常驻但只占一条：完整内容在弹层里，消息区不被挤掉。
                    state.pendingPermission?.let { permission ->
                        ProferDivider()
                        ProferPermissionPromptBar(
                            title = permission.title,
                            summary = permission.command ?: permission.description,
                            actionLabel = "查看",
                            onAction = { permissionSheetOpen = true },
                        )
                    }
                    if (state.pendingQuestions.isNotEmpty()) {
                        ProferDivider()
                        ProferPermissionPromptBar(
                            title = "Agent 需要你的输入",
                            summary = state.pendingQuestions.first().question,
                            actionLabel = "回答",
                            onAction = { questionSheetOpen = true },
                        )
                    }
                    if (attachments.isNotEmpty()) {
                        AttachmentStrip(
                            attachments = attachments,
                            onRemove = onRemoveAttachment,
                        )
                    }
                    ProferComposer(
                        value = state.draft,
                        onValueChange = onInputChange,
                        onSend = {
                            onSend()
                            composerFocus.requestFocus()
                        },
                        onStop = onStop,
                        streaming = state.streaming,
                        modelLabel = state.modelLabel,
                        modelIconRes = modelIconRes,
                        modeLabel = state.modeLabel,
                        onModelClick = { modelSheetOpen = true },
                        onModeClick = { modeSheetOpen = true },
                        presetLabel = presetLabel,
                        onPresetClick = {
                            presetSheetOpen = true
                            onRefreshPresets()
                        },
                        onAttach = onAttach,
                        focusRequester = composerFocus,
                        placeholder = if (state.offline) "离线 · 消息会在重连后发送" else "给 Profer 发消息…",
                    )
                }
            },
        ) { innerPadding ->
            Column(modifier = Modifier.padding(innerPadding)) {
                state.offlineLabel?.let { label ->
                    ProferConnectionBanner(
                        text = label,
                        actionLabel = "重连",
                        onAction = onRetryConnection,
                    )
                }

                if (state.messages.isEmpty()) {
                    ProferEmptyState(
                        title = "开始一个会话",
                        description = "任务在 Mac 上执行，你在这里确认关键步骤。",
                        primaryLabel = "开始输入",
                        onPrimary = { composerFocus.requestFocus() },
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
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(
                            start = ProferSpacing.lg,
                            end = ProferSpacing.lg,
                            top = ProferSpacing.md,
                            bottom = ProferSpacing.sm,
                        ),
                        verticalArrangement = Arrangement.spacedBy(ProferSpacing.xl),
                    ) {
                        itemsIndexed(state.messages, key = { _, item -> item.id }) { index, message ->
                            if (dividerIndex != null && index == dividerIndex + 1) {
                                NewMessagesDivider()
                            }
                            MessageRow(
                                message = message,
                                editing = editingMessageId == message.id,
                                editingText = editingText,
                                onEditingTextChange = { editingText = it },
                                onStartEdit = {
                                    if (message is ChatMessage.User) {
                                        editingMessageId = message.id
                                        editingText = message.text
                                    }
                                },
                                onCancelEdit = { editingMessageId = null },
                                onSubmitEdit = {
                                    val target = editingMessageId
                                    editingMessageId = null
                                    if (target != null) onRewriteMessage(target, editingText)
                                },
                                onCopy = { text ->
                                    clipboard.setText(AnnotatedString(text))
                                    scope.launch { snackbarHostState.showSnackbar("已复制") }
                                },
                                onResend = onResendText,
                                onRetrySend = onRetrySend,
                                onOpenAttachment = onOpenAttachment,
                                onOpenImage = onOpenImage,
                                imageLoader = imageLoader,
                                sessionStreaming = state.streaming,
                                // 工具图标贴在最后一条助手消息的操作行里，避免单独占一行、拉开间距。
                                toolCalls = if (index == state.messages.lastIndex && message is ChatMessage.Assistant) {
                                    state.toolCalls
                                } else {
                                    emptyList()
                                },
                            )
                        }

                        // 最后一个条目不是助手消息时（极少数纯工具回合），工具条仍要能显示。
                        if (state.toolCalls.isNotEmpty() && state.messages.lastOrNull() !is ChatMessage.Assistant) {
                            item(key = "tool-calls") {
                                ToolIconStrip(tools = state.toolCalls)
                            }
                        }

                        if (state.turnFiles.isNotEmpty()) {
                            item(key = "turn-files") {
                                TurnFileChanges(
                                    files = state.turnFiles,
                                    onOpenFile = { path ->
                                        onOpenAttachment(
                                            com.profer.pocket.model.AgentAttachment(
                                                filename = path.substringAfterLast('/'),
                                                path = path,
                                            ),
                                        )
                                    },
                                )
                            }
                        }

                        if (state.streaming && state.pendingPermission == null) {
                            item(key = "activity") {
                                ProferActivityLine(text = "Profer 正在执行…")
                            }
                        }

                        // 末尾留 1dp：scrollToItem 对齐的是项顶部，靠它才能真正贴到底。
                        item(key = "bottom-anchor") {
                            androidx.compose.foundation.layout.Spacer(
                                modifier = Modifier.height(1.dp),
                            )
                        }
                    }

                    if (!atBottom && state.messages.isNotEmpty() && imeBottom == 0) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .padding(end = ProferSpacing.sm, bottom = ProferSpacing.sm)
                                .size(ProferSizes.minTouchTarget)
                                .clip(CircleShape)
                                .clickable {
                                    scope.launch {
                                        val last = (listState.layoutInfo.totalItemsCount - 1).coerceAtLeast(0)
                                        listState.animateScrollToItem(last)
                                    }
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            Surface(
                                modifier = Modifier.heightIn(min = 36.dp),
                                shape = CircleShape,
                                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                                shadowElevation = 1.dp,
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

    if (contextSheetOpen) {
        ContextUsageSheet(
            usage = state.contextUsage,
            window = state.contextWindow,
            streaming = state.streaming,
            onCompact = {
                contextSheetOpen = false
                onCompactContext()
            },
            onDismiss = { contextSheetOpen = false },
        )
    }
    if (modelSheetOpen) {
        ModelPickerSheet(
            options = modelOptions,
            currentChannelId = state.channelId,
            currentModelId = state.modelId,
            currentModelLabel = state.modelLabel,
            streaming = state.streaming,
            onSelect = { option ->
                modelSheetOpen = false
                onSelectModel(option)
            },
            onDismiss = { modelSheetOpen = false },
        )
    }
    if (state.pendingQuestions.isNotEmpty() && questionSheetOpen) {
        AskUserSheet(
            questions = state.pendingQuestions,
            onSubmit = { answers ->
                questionSheetOpen = false
                onAnswerQuestion(answers)
            },
            onDismiss = { questionSheetOpen = false },
        )
    }
    if (presetSheetOpen) {
        PresetPickerSheet(
            presets = presets,
            currentPresetId = state.presetId,
            onSelect = { preset ->
                presetSheetOpen = false
                onSelectPreset(preset.id)
            },
            onDismiss = { presetSheetOpen = false },
        )
    }
    if (modeSheetOpen) {
        PermissionModeDialog(
            currentMode = state.permissionMode,
            currentLabel = state.modeLabel,
            onSelect = { mode ->
                modeSheetOpen = false
                onSelectMode(mode)
            },
            onDismiss = { modeSheetOpen = false },
        )
    }

    val permission = state.pendingPermission
    if (permissionSheetOpen && permission != null) {
        ProferPermissionSheet(
            title = permission.title,
            description = permission.description,
            command = permission.command,
            contextLabel = permission.contextLabel,
            onAllow = {
                permissionSheetOpen = false
                onAllowPermission()
            },
            onAlwaysAllow = {
                permissionSheetOpen = false
                onAlwaysAllowPermission()
            },
            onDeny = {
                permissionSheetOpen = false
                onDenyPermission()
            },
            onDismiss = { permissionSheetOpen = false },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
/**
 * 贴到底部：先对齐最后一项，再按实际溢出量补一次。
 * 不能直接用大数值 scrollBy，某些内容组合下会触发布局风暴。
 */
internal suspend fun androidx.compose.foundation.lazy.LazyListState.scrollToBottom() {
    val count = layoutInfo.totalItemsCount
    if (count == 0) return
    scrollToItem(count - 1)
    withFrameNanos { }
    val last = layoutInfo.visibleItemsInfo.lastOrNull() ?: return
    val overflow = (last.offset + last.size) - layoutInfo.viewportEndOffset
    if (overflow > 0) scrollBy(overflow.toFloat())
}

@Composable
private fun MessageRow(
    message: ChatMessage,
    onCopy: (String) -> Unit,
    onResend: (String) -> Unit,
    onRetrySend: (String) -> Unit = {},
    onOpenAttachment: (com.profer.pocket.model.AgentAttachment) -> Unit = {},
    onOpenImage: (com.profer.pocket.model.ChatImage) -> Unit = {},
    imageLoader: suspend (String) -> androidx.compose.ui.graphics.ImageBitmap? = { null },
    /** 整轮是否仍在执行：流式期间不显示任何消息操作。 */
    sessionStreaming: Boolean = false,
    /** 本轮工具图标；只挂在最后一条助手消息上，与复制按钮同一行。 */
    toolCalls: List<ToolBlock> = emptyList(),
    editing: Boolean = false,
    editingText: String = "",
    onEditingTextChange: (String) -> Unit = {},
    onStartEdit: () -> Unit = {},
    onCancelEdit: () -> Unit = {},
    onSubmitEdit: () -> Unit = {},
) {
    when (message) {
        is ChatMessage.Notice -> ProferNotice(
            text = message.text,
            icon = when (message.tone) {
                NoticeTone.Info -> Icons.Outlined.Info
                NoticeTone.Success -> Icons.Outlined.Check
                NoticeTone.Warning -> Icons.Outlined.ErrorOutline
            },
            tint = when (message.tone) {
                NoticeTone.Info -> MaterialTheme.colorScheme.onSurfaceVariant
                NoticeTone.Success -> ProferThemeExtras.semantic.success
                NoticeTone.Warning -> ProferThemeExtras.semantic.warning
            },
        )

        is ChatMessage.User -> if (editing) {
            UserEditBubble(
                text = editingText,
                onTextChange = onEditingTextChange,
                onCancel = onCancelEdit,
                onSubmit = onSubmitEdit,
            )
        } else {
            var menuOpen by remember { mutableStateOf(false) }
            Box {
                ProferUserMessage(
                    text = message.text,
                    meta = if (message.status == SendStatus.Failed) "发送失败 · 点重试" else message.meta,
                    footer = if (message.attachments.isEmpty()) {
                        null
                    } else {
                        { AttachmentCards(files = message.attachments, onOpen = onOpenAttachment) }
                    },
                    actions = {
                        if (message.status == SendStatus.Failed) {
                            TextButton(onClick = { onRetrySend(message.id) }) { Text("重试") }
                        }
                        MessageActionIcon(Icons.Outlined.MoreVert, "消息操作") { menuOpen = true }
                    },
                )
                DropdownMenu(
                    expanded = menuOpen,
                    onDismissRequest = { menuOpen = false },
                ) {
                    DropdownMenuItem(
                        text = { Text("复制") },
                        leadingIcon = { Icon(Icons.Outlined.ContentCopy, contentDescription = null) },
                        onClick = {
                            menuOpen = false
                            onCopy(message.text)
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("重新发送") },
                        leadingIcon = { Icon(Icons.Outlined.Replay, contentDescription = null) },
                        onClick = {
                            menuOpen = false
                            onResend(message.text)
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("修改重发") },
                        leadingIcon = { Icon(Icons.Outlined.Edit, contentDescription = null) },
                        onClick = {
                            menuOpen = false
                            onStartEdit()
                        },
                    )
                }
            }
        }

        is ChatMessage.Assistant -> Column(modifier = Modifier.fillMaxWidth()) {
            AssistantMessage(message = message, onOpenImage = onOpenImage, imageLoader = imageLoader)
            // 工具图标与复制按钮同行：整轮结束后一起出现在正文下方，
            // 流式期间没有复制按钮，图标条单独占一行但高度只有 22dp。
            if (toolCalls.isNotEmpty() || (!message.streaming && !sessionStreaming)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 2.dp),
                    horizontalArrangement = Arrangement.Start,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (!message.streaming && !sessionStreaming) {
                        MessageActionIcon(Icons.Outlined.ContentCopy, "复制回复") {
                            onCopy(message.plainText())
                        }
                    }
                    if (toolCalls.isNotEmpty()) {
                        ToolIconStrip(
                            tools = toolCalls,
                            modifier = Modifier.padding(start = 2.dp),
                        )
                    }
                }
            }
        }
    }
}

/** 修改重发：原地编辑用户消息，视觉复用气泡（圆角/底色/排版不变）。 */
@Composable
private fun UserEditBubble(
    text: String,
    onTextChange: (String) -> Unit,
    onCancel: () -> Unit,
    onSubmit: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.End,
    ) {
        Surface(
            shape = RoundedCornerShape(
                topStart = 20.dp,
                topEnd = 20.dp,
                bottomEnd = 6.dp,
                bottomStart = 20.dp,
            ),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.widthIn(max = 320.dp),
        ) {
            BasicTextField(
                value = text,
                onValueChange = onTextChange,
                textStyle = MaterialTheme.typography.bodyLarge.copy(
                    color = MaterialTheme.colorScheme.onSurface,
                ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                modifier = Modifier
                    .heightIn(min = 72.dp)
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            )
        }
        Row(
            modifier = Modifier.padding(top = 2.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            TextButton(onClick = onCancel) { Text("取消") }
            TextButton(onClick = onSubmit) { Text("发送") }
        }
    }
}

/** 消息操作：小图标、默认低对比；不占正文视线。 */
@Composable
internal fun MessageActionIcon(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(ProferSizes.minTouchTarget)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = description,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f),
            modifier = Modifier.size(15.dp),
        )
    }
}

private fun ChatMessage.Assistant.plainText(): String = buildString {
    tools.forEach { appendLine("${it.title} · ${it.subtitle}") }
    blocks.forEach { block ->
        when (block) {
            is ContentBlock.Paragraph -> appendLine(block.text)
            is ContentBlock.Heading -> appendLine(block.text)
            is ContentBlock.Bullets -> block.items.forEach { appendLine("- $it") }
            is ContentBlock.Ordered -> block.items.forEachIndexed { index, item ->
                appendLine("${index + 1}. $item")
            }
            is ContentBlock.Quote -> appendLine(block.text)
            is ContentBlock.Code -> appendLine(block.code)
            is ContentBlock.Table -> {
                appendLine(block.headers.joinToString(" | "))
                block.rows.forEach { row -> appendLine(row.joinToString(" | ")) }
            }
        }
    }
}.trim()

/** 折叠态只展示思考尾部一段：两行以内，避免长思考把消息区顶开。 */
private fun ThinkingBlock.preview(maxChars: Int = 160): String {
    val text = body.trim()
    if (text.length <= maxChars) return text
    return "…" + text.takeLast(maxChars)
}

@Composable
private fun AssistantMessage(
    message: ChatMessage.Assistant,
    modifier: Modifier = Modifier,
    onOpenImage: (com.profer.pocket.model.ChatImage) -> Unit = {},
    imageLoader: suspend (String) -> androidx.compose.ui.graphics.ImageBitmap? = { null },
) {
    // 正文还没开始、思考还在增长：卡片保持折叠，但用两行尾部预览展示「一直在想」，
    // 不把整段思考铺开、也不抢正文的阅读位置。
    val thinkingLive = message.streaming && message.blocks.isEmpty()
    var thinkingExpanded by rememberSaveable(message.id) { mutableStateOf(false) }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(ProferSpacing.sm),
    ) {
        message.thinking?.let { thinking ->
            ProferThinkingCard(
                durationLabel = if (thinkingLive) "思考中…" else "思考过程",
                body = thinking.body,
                expanded = thinkingExpanded,
                onToggle = { thinkingExpanded = !thinkingExpanded },
                labelPrefix = "",
                // 折叠时只留两行尾部预览；展开后由 body 接管，避免重复。
                preview = if (thinkingLive) thinking.preview() else null,
            )
        }

        if (message.blocks.isNotEmpty()) {
            ProferRichText(
                blocks = message.blocks,
                showCursor = message.streaming,
            )
        }

        // 图片放在正文之后、工具卡之前：结论先读，图作为佐证。
        if (message.images.isNotEmpty()) {
            MessageImages(images = message.images, load = imageLoader, onOpen = onOpenImage)
        }

        // 工具卡放在正文下面：先读结论，再看为了得到结论做了什么。
        message.tools.forEach { tool ->
            ToolCallCard(tool)
        }
    }
}

/** 模型/工具产出的图片：单张铺满行宽，多张排成缩略图；点开由宿主打开大图预览。 */
@Composable
private fun MessageImages(
    images: List<com.profer.pocket.model.ChatImage>,
    load: suspend (String) -> androidx.compose.ui.graphics.ImageBitmap?,
    onOpen: (com.profer.pocket.model.ChatImage) -> Unit,
) {
    if (images.isEmpty()) return
    if (images.size == 1) {
        MessageImageCard(image = images.first(), load = load, onOpen = onOpen, single = true)
    } else {
        Row(horizontalArrangement = Arrangement.spacedBy(ProferSpacing.sm)) {
            images.forEach { image ->
                MessageImageCard(image = image, load = load, onOpen = onOpen, single = false)
            }
        }
    }
}

@Composable
private fun MessageImageCard(
    image: com.profer.pocket.model.ChatImage,
    load: suspend (String) -> androidx.compose.ui.graphics.ImageBitmap?,
    onOpen: (com.profer.pocket.model.ChatImage) -> Unit,
    single: Boolean,
) {
    var bitmap by remember(image.path) { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
    var failed by remember(image.path) { mutableStateOf(false) }
    LaunchedEffect(image.path) {
        val loaded = runCatching { load(image.path) }.getOrNull()
        bitmap = loaded
        failed = loaded == null
    }

    val shape = RoundedCornerShape(14.dp)
    // 单张按行宽铺开，高度由图片比例决定（上限 280dp）；多张固定 96dp 方格。
    val surfaceModifier = if (single) Modifier.fillMaxWidth() else Modifier.size(96.dp)
    val imageModifier = if (single) {
        Modifier.fillMaxWidth().heightIn(max = 280.dp)
    } else {
        Modifier.fillMaxSize()
    }

    Surface(
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = surfaceModifier
            .clip(shape)
            .clickable { onOpen(image) },
    ) {
        Box(
            modifier = if (single) Modifier.fillMaxWidth() else Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            val current = bitmap
            when {
                current != null -> Image(
                    bitmap = current,
                    contentDescription = image.filename,
                    contentScale = if (single) ContentScale.FillWidth else ContentScale.Crop,
                    modifier = imageModifier,
                )

                failed -> Text(
                    text = "图片加载失败",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                else -> CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                )
            }
        }
    }
}

/** 单条工具调用：图标由工具类型决定，状态由 tool_result 驱动。 */
@Composable
private fun ToolCallCard(tool: ToolBlock) {
    var expanded by rememberSaveable(tool.id) { mutableStateOf(false) }
    ProferToolCallCard(
        icon = tool.kind.icon(),
        title = tool.title,
        subtitle = tool.subtitle,
        status = tool.status,
        expanded = expanded,
        onToggle = { expanded = !expanded },
        detail = tool.detail,
    )
}

/**
 * 工具调用紧凑条：同一类型只出一个图标，最多 4 个；多出来的调用以 +N 表示。
 * 只占一行，不把正文挤下去；状态用颜色区分（运行中/失败）。
 */
@Composable
private fun ToolIconStrip(
    tools: List<ToolBlock>,
    modifier: Modifier = Modifier,
) {
    val groups = tools.groupBy { it.kind }
    val shown = groups.entries.take(MAX_STRIP_ICONS)
    val hiddenCalls = groups.entries.drop(MAX_STRIP_ICONS).sumOf { it.value.size }

    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Row(
            modifier = Modifier
                .height(22.dp)
                .padding(horizontal = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            shown.forEach { (kind, calls) ->
                val status = when {
                    calls.any { it.status == ToolStatus.Error } -> ToolStatus.Error
                    calls.any { it.status == ToolStatus.Running } -> ToolStatus.Running
                    else -> ToolStatus.Success
                }
                Icon(
                    imageVector = kind.icon(),
                    contentDescription = "${calls.first().title} ×${calls.size}",
                    tint = when (status) {
                        ToolStatus.Running -> MaterialTheme.colorScheme.tertiary
                        ToolStatus.Error -> MaterialTheme.colorScheme.error
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.size(14.dp),
                )
            }
            if (hiddenCalls > 0) {
                Text(
                    text = "+$hiddenCalls",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun ToolKind.icon(): androidx.compose.ui.graphics.vector.ImageVector = when (this) {
    ToolKind.Read -> Icons.Outlined.Description
    ToolKind.Search -> Icons.Outlined.Search
    ToolKind.Bash -> Icons.Outlined.Terminal
    ToolKind.Edit -> Icons.Outlined.Edit
    ToolKind.Other -> Icons.Outlined.Build
}

private const val MAX_STRIP_ICONS = 4

private fun ContentBlock.toMarkdown(): String = when (this) {
    is ContentBlock.Paragraph -> text
    is ContentBlock.Heading -> "## $text"
    is ContentBlock.Bullets -> items.joinToString("\n") { "- $it" }
    is ContentBlock.Ordered -> items.mapIndexed { index, item -> "${index + 1}. $item" }.joinToString("\n")
    is ContentBlock.Quote -> "> $text"
    is ContentBlock.Code -> "```$language\n$code```"
    is ContentBlock.Table -> buildString {
        appendLine(headers.joinToString(" | "))
        rows.forEach { row -> appendLine(row.joinToString(" | ")) }
    }
}

@Composable
private fun ChatTopBar(
    title: String,
    contextUsage: com.profer.pocket.model.ContextUsage?,
    contextWindow: Int?,
    onContextClick: () -> Unit,
    hasSession: Boolean,
    pinned: Boolean,
    onOpenDrawer: () -> Unit,
    onOpenSettings: () -> Unit,
    onRenameSession: () -> Unit,
    onTogglePin: () -> Unit,
    onDeleteSession: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }

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
            contentDescription = "打开会话列表",
            onClick = onOpenDrawer,
        )
        // 顶栏只留会话标题：项目名与连接点属于副信息，不再占第二行。
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge.copy(
                fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
            ),
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 4.dp),
        )
        // 上下文用量是会话级状态：环负责压力感，数字与动作在底部弹层。
        ContextUsageIndicator(
            usage = contextUsage,
            window = contextWindow,
            onClick = onContextClick,
        )
        ProferIconAction(
            icon = Icons.Outlined.MoreVert,
            contentDescription = "更多操作",
            onClick = { menuOpen = true },
        )
        DropdownMenu(
            expanded = menuOpen,
            onDismissRequest = { menuOpen = false },
        ) {
            DropdownMenuItem(
                text = { Text("重命名会话") },
                leadingIcon = { Icon(Icons.Outlined.DriveFileRenameOutline, contentDescription = null) },
                enabled = hasSession,
                onClick = {
                    menuOpen = false
                    onRenameSession()
                },
            )
            DropdownMenuItem(
                text = { Text(if (pinned) "取消置顶" else "置顶会话") },
                leadingIcon = { Icon(Icons.Outlined.PushPin, contentDescription = null) },
                enabled = hasSession,
                onClick = {
                    menuOpen = false
                    onTogglePin()
                },
            )
            DropdownMenuItem(
                text = { Text("删除会话") },
                leadingIcon = {
                    Icon(
                        Icons.Outlined.DeleteOutline,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                    )
                },
                enabled = hasSession,
                onClick = {
                    menuOpen = false
                    onDeleteSession()
                },
            )
            DropdownMenuItem(
                text = { Text("设置") },
                leadingIcon = { Icon(Icons.Outlined.Settings, contentDescription = null) },
                onClick = {
                    menuOpen = false
                    onOpenSettings()
                },
            )
        }
    }
}

/** 待发送附件条：文件名 + 移除；图片与普通文件同一套展示。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AttachmentStrip(
    attachments: List<com.profer.pocket.model.AgentAttachment>,
    onRemove: (String) -> Unit,
) {
    FlowRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = ProferSpacing.md, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        attachments.forEach { file ->
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f)),
            ) {
                Row(
                    modifier = Modifier.padding(start = 8.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Description,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        modifier = Modifier.size(12.dp),
                    )
                    Text(
                        text = file.filename,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = 150.dp),
                    )
                    Icon(
                        imageVector = Icons.Outlined.Close,
                        contentDescription = "移除 ${file.filename}",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
                        modifier = Modifier
                            .size(18.dp)
                            .clickable { onRemove(file.path) }
                            .padding(2.dp),
                    )
                }
            }
        }
    }
}

/** 已发送消息里的附件卡：图片/文件同一套，点开由宿主预览。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AttachmentCards(
    files: List<com.profer.pocket.model.AgentAttachment>,
    onOpen: (com.profer.pocket.model.AgentAttachment) -> Unit,
) {
    FlowRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp, end = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.End),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        files.forEach { file ->
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f)),
                modifier = Modifier.clickable { onOpen(file) },
            ) {
                Row(
                    modifier = Modifier.padding(start = 8.dp, end = 10.dp, top = 5.dp, bottom = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    Icon(
                        imageVector = if (isImageFile(file.filename)) Icons.Outlined.Image else Icons.Outlined.Description,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        modifier = Modifier.size(13.dp),
                    )
                    Text(
                        text = file.filename,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = 170.dp),
                    )
                }
            }
        }
    }
}

private fun isImageFile(filename: String): Boolean {
    val ext = filename.substringAfterLast('.', "").lowercase()
    return ext in setOf("png", "jpg", "jpeg", "gif", "webp", "bmp")
}

/** 本机阅读位置之后的第一条消息上方：一条很轻的「新消息」界栏。 */
@Composable
private fun NewMessagesDivider() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = ProferSpacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ProferSpacing.sm),
    ) {
        HorizontalDivider(
            modifier = Modifier.weight(1f),
            color = MaterialTheme.colorScheme.outlineVariant,
        )
        Text(
            text = "新消息",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
        )
        HorizontalDivider(
            modifier = Modifier.weight(1f),
            color = MaterialTheme.colorScheme.outlineVariant,
        )
    }
}

/** 本轮修改文件汇总：与桌面 TurnFileChangesSummary 一样，放在回复末尾。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TurnFileChanges(
    files: List<String>,
    onOpenFile: (String) -> Unit = {},
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = ProferSpacing.xs),
        verticalArrangement = Arrangement.spacedBy(ProferSpacing.sm),
    ) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            files.forEach { path ->
                FileRefChip(
                    display = path.substringAfterLast('/'),
                    onClick = { onOpenFile(path) },
                )
            }
        }
    }
}
