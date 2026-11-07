package com.profer.pocket.chat

import com.profer.pocket.designsystem.component.ProferHeaderRow
import com.profer.pocket.designsystem.component.proferBackdrop
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
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.material.icons.automirrored.outlined.MenuOpen
import androidx.compose.material.icons.automirrored.outlined.ViewSidebar
import androidx.compose.material.icons.outlined.ArrowDownward
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
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.SmartToy
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
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.profer.pocket.designsystem.adaptive.rememberProferWindowSize
import com.profer.pocket.designsystem.component.ProferActivityLine
import com.profer.pocket.designsystem.component.ProferComposer
import com.profer.pocket.designsystem.component.ProferConnectionBanner
import com.profer.pocket.designsystem.component.ProferDivider
import com.profer.pocket.designsystem.component.ProferIconAction
import com.profer.pocket.designsystem.component.ProferNotice
import com.profer.pocket.designsystem.component.ProferPermissionPromptBar
import com.profer.pocket.designsystem.component.ProferPermissionSheet
import com.profer.pocket.designsystem.component.ProferProcessGroup
import com.profer.pocket.designsystem.component.ProferStatusDot
import com.profer.pocket.designsystem.component.ProferThinkingCard
import com.profer.pocket.designsystem.component.ProferToolCallCard
import com.profer.pocket.designsystem.component.ProferUserMessage
import com.profer.pocket.designsystem.component.ToolStatus
import com.profer.pocket.designsystem.component.proferFloatingSurface
import com.profer.pocket.designsystem.theme.ProferThemeExtras
import com.profer.pocket.model.SendStatus
import com.profer.pocket.designsystem.theme.ProferSizes
import com.profer.pocket.designsystem.theme.ProferSpacing
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    state: ChatUiState,
    /** 当前草稿：由宿主从同步草稿流传入，不走 state 的后台映射，避免输入回声滞后。 */
    draft: String,
    drawerContent: @Composable (onCloseDrawer: () -> Unit) -> Unit,
    modifier: Modifier = Modifier,
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
    onOpenConnection: () -> Unit = onOpenSettings,
    onNewSession: (() -> Unit)? = null,
    /** 子会话顶栏面包屑：点一下回母会话。 */
    onOpenParentSession: () -> Unit = {},
    /** Agent 预设（岗位）：Chat 模式不传。 */
    presetLabel: String? = null,
    presets: List<com.profer.pocket.model.AgentPresetSummary> = emptyList(),
    onSelectPreset: (String) -> Unit = {},
    onRefreshPresets: () -> Unit = {},
    /** 切换思考强度：null = 恢复「跟随预设」。 */
    onSelectThinking: (String?) -> Unit = {},
    /** 打开思考强度弹层时补拉一次模型档位能力。 */
    onRefreshThinking: () -> Unit = {},
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
    onOpenFilePath: (String) -> Unit = {},
    /** 待发送图片附件（已保存到电脑端会话目录）。 */
    attachments: List<com.profer.pocket.model.AgentAttachment> = emptyList(),
    onAttach: (() -> Unit)? = null,
    onRemoveAttachment: (String) -> Unit = {},
    onRetryConnection: () -> Unit = {},
    onResendText: (String) -> Unit = {},
    message: String? = null,
    onMessageShown: () -> Unit = {},
    onRenameSession: () -> Unit = {},
    onTogglePin: () -> Unit = {},
    onDeleteSession: () -> Unit = {},
    /** 抽屉状态由首页持有：Agent / Chat 切换时抽屉不跟着关。 */
    drawerState: PocketDrawerState = rememberPocketDrawerState(),
    /** 双栏列表栏是否收起：由首页持有，Agent / Chat 切换时保持同一姿态。 */
    listPaneCollapsed: Boolean = false,
    onToggleListPane: () -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    // 横屏短屏：消息间距与顶部留白收紧一点，360dp 高里多出一行正文。
    val window = rememberProferWindowSize()
    val shortScreen = window.isShort
    val density = LocalDensity.current
    // 不要直接在组合里读 getBottom：IME 动画的每一帧都会让整页重组。
    // 只把「键盘是否弹起」这一位交给 UI，滚动/避让用布尔值就够。
    val ime = WindowInsets.ime
    val imeVisible by remember(ime) { derivedStateOf { ime.getBottom(density) > 0 } }
    // 输入框只在换行数变化时改变高度；按字符变化重启滚动副作用会白白抖动。
    val draftLines = remember(draft) { draft.count { it == '\n' } }
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
    var thinkingSheetOpen by remember { mutableStateOf(false) }
    var contextSheetOpen by remember { mutableStateOf(false) }
    // 修改重发：同一时刻只编辑一条。输入文本由编辑气泡自己持有——
    // 之前把 editingText 提到这里并传给每一条 MessageRow，打一个字整屏可见项全部重组。
    var editingMessageId by remember { mutableStateOf<String?>(null) }

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
    // 工具卡挂在助手消息里，新工具到达时消息数不一定变，所以把工具总数也当作跟随信号。
    // 一轮合成一个回合之后，新的一步既不增加消息条数，也不一定立刻带来新工具，
    // 所以步数同样要进跟随信号，否则第二、第三步的思考会落在屏幕外面。
    // 只在消息表变化时重算：输入框每敲一个字都会重组这一页，不能每次都扫整张表。
    val followSignal = remember(state.messages) {
        var tools = 0
        var steps = 0
        state.messages.forEach { message ->
            when (message) {
                is ChatMessage.Assistant -> {
                    tools += message.tools.size
                    steps += 1
                }

                is ChatMessage.Turn -> {
                    message.steps.forEach { tools += it.tools.size }
                    steps += message.steps.size
                }

                else -> Unit
            }
        }
        tools to steps
    }
    LaunchedEffect(state.messages.size, followSignal, state.streaming, draftLines, imeVisible) {
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

    // 键盘弹起时输入条和列表视口一起变高，上面的副作用拿到的是旧视口高度，
    // 结果最后一行被输入条盖住。等 IME 动画结束后再锚一次底部。
    LaunchedEffect(imeVisible) {
        if (!imeVisible || state.messages.isEmpty()) return@LaunchedEffect
        delay(260)
        listState.scrollToBottom()
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

    ProvideFilePreview(onOpenFile = onOpenFilePath) {
        PocketDrawerLayout(
        modifier = modifier,
        drawerState = drawerState,
        listPaneCollapsed = listPaneCollapsed,
        drawerContent = {
            // 关闭状态下不组合几百条会话目录：否则冷启动会把整张抽屉树
            // （项目分组 + 会话行 + 弹层宿主）一起组合/测量，主线程首帧被拖到十几秒。
            // 双栏时列表常驻，必须组合；这里在 PocketDrawerLayout 的 Provider 内部读取窗口尺寸。
            // 列表栏收起后宽度为 0，同样不必留着这棵树。
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
                    if (shortScreen || window.isTwoPane) {
                        Modifier.background(MaterialTheme.colorScheme.background)
                    } else {
                        Modifier.proferBackdrop()
                    },
                ),
            contentAlignment = Alignment.TopCenter,
        ) {
        Scaffold(
            modifier = Modifier
                .widthIn(max = ProferSizes.contentMaxWidth)
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
                // 双栏时顶栏左侧键变成「收起/展开列表栏」：列表不再是一个需要滑出来的抽屉，
                // 而是一个可以暂时让位的常驻区。单栏仍然是打开抽屉。
                val twoPane = rememberProferWindowSize().isTwoPane
                ChatTopBar(
                    title = state.title,
                    subSessionLabel = state.subSessionLabel,
                    onOpenParent = onOpenParentSession,
                    contextUsage = state.contextUsage,
                    contextWindow = state.contextWindow,
                    onContextClick = { contextSheetOpen = true },
                    hasSession = state.sessionId != null,
                    pinned = state.pinned,
                    listPaneCollapsed = listPaneCollapsed,
                    onOpenDrawer = {
                        if (twoPane) {
                            onToggleListPane()
                        } else {
                            keyboard?.hide()
                            drawerState.open()
                        }
                    },
                    onOpenSettings = onOpenSettings,
                    onRenameSession = onRenameSession,
                    onTogglePin = onTogglePin,
                    onDeleteSession = onDeleteSession,
                )
            },
            bottomBar = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    // 「回到最新」放在输入条上方的一条独立行：横屏消息区只有 420–500dp 宽，
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
                        value = draft,
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
                        thinkingLabel = state.thinkingLabel.takeIf { it.isNotBlank() },
                        onThinkingClick = {
                            thinkingSheetOpen = true
                            onRefreshThinking()
                        },
                        onAttach = onAttach,
                        focusRequester = composerFocus,
                        placeholder = if (state.offline) "离线 · 消息会在重连后发送" else "给 Profer 发消息…",
                    )
                }
            },
        ) { innerPadding ->
            Column(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
                state.offlineLabel?.takeIf { state.messages.isNotEmpty() }?.let { label ->
                    ProferConnectionBanner(
                        text = label,
                        actionLabel = "重连",
                        onAction = onRetryConnection,
                    )
                }

                if (state.messages.isEmpty()) {
                    ChatWelcomeState(
                        offline = state.offline,
                        onConnect = onOpenConnection,
                        hasDraftTarget = state.sessionId != null,
                        onStartConversation = {
                            if (onNewSession != null) onNewSession() else {
                                scope.launch { drawerState.open() }
                            }
                        },
                        onSuggestion = { prompt ->
                            onInputChange(prompt)
                            composerFocus.requestFocus()
                        },
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
                            // 双栏时内容区有余量，左右留到 24dp（桌面正文栏的留白），不再挤在卡片边上。
                            start = if (window.isTwoPane) ProferSpacing.xl else ProferSpacing.lg,
                            end = if (window.isTwoPane) ProferSpacing.xl else ProferSpacing.lg,
                            top = if (shortScreen) ProferSpacing.sm else ProferSpacing.md,
                            bottom = ProferSpacing.sm,
                        ),
                        verticalArrangement = Arrangement.spacedBy(
                            if (shortScreen) ProferSpacing.lg else ProferSpacing.xl,
                        ),
                    ) {
                        itemsIndexed(
                            items = state.messages,
                            key = { _, item -> item.id },
                            // 同一类型复用组合槽位：长会话滚动时新进屏的项少一次类型判断与重建。
                            contentType = { _, item -> item::class },
                        ) { index, message ->
                            if (dividerIndex != null && index == dividerIndex + 1) {
                                NewMessagesDivider()
                            }
                            MessageRow(
                                message = message,
                                editing = editingMessageId == message.id,
                                initialEditingText = (message as? ChatMessage.User)?.text.orEmpty(),
                                onStartEdit = {
                                    if (message is ChatMessage.User) {
                                        editingMessageId = message.id
                                    }
                                },
                                onCancelEdit = { editingMessageId = null },
                                onSubmitEdit = { text ->
                                    val target = editingMessageId
                                    editingMessageId = null
                                    if (target != null) onRewriteMessage(target, text)
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
                            )
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
    if (thinkingSheetOpen) {
        ThinkingPickerSheet(
            options = state.thinkingOptions,
            selectedValue = state.thinkingSelectedValue,
            sourceLabel = state.thinkingSourceLabel,
            loading = state.thinkingLoading,
            streaming = state.streaming,
            onSelect = { value ->
                thinkingSheetOpen = false
                onSelectThinking(value)
            },
            onDismiss = { thinkingSheetOpen = false },
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
    editing: Boolean = false,
    /** 进入编辑时的初始文本；之后的每次按键只留在气泡内部，不再广播给整个列表。 */
    initialEditingText: String = "",
    onStartEdit: () -> Unit = {},
    onCancelEdit: () -> Unit = {},
    onSubmitEdit: (String) -> Unit = {},
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
                initialText = initialEditingText,
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
                    // 菜单是浮起面，取卡片色（桌面 --popover）；surfaceContainer 在本主题里是分段轨道/选中行。
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
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

        is ChatMessage.Assistant -> AssistantRow(
            steps = listOf(message),
            onCopy = onCopy,
            onOpenImage = onOpenImage,
            imageLoader = imageLoader,
            sessionStreaming = sessionStreaming,
        )

        // 一轮七八步合成一个回合：整轮只有一行「执行过程」，不再每步一行。
        is ChatMessage.Turn -> AssistantRow(
            steps = message.steps,
            onCopy = onCopy,
            onOpenImage = onOpenImage,
            imageLoader = imageLoader,
            sessionStreaming = sessionStreaming,
        )

        is ChatMessage.Source -> SourceCard(message)
    }
}

/** 修改重发：原地编辑用户消息，视觉复用气泡（圆角/底色/排版不变）。 */
@Composable
private fun UserEditBubble(
    initialText: String,
    onCancel: () -> Unit,
    onSubmit: (String) -> Unit,
) {
    // 输入状态留在这个气泡里：打字只会重组它自己。
    var text by remember(initialText) { mutableStateOf(initialText) }
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
                onValueChange = { text = it },
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
            TextButton(onClick = { onSubmit(text) }) { Text("发送") }
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

/** 复制出来的正文：只取读得到的答案文本，不把工具标题混进来。 */
private fun blocksPlainText(blocks: List<ContentBlock>): String = buildString {
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

/** 助手输出的外壳：消息体 + 整轮结束后才出现的复制按钮。 */
@Composable
private fun AssistantRow(
    steps: List<ChatMessage.Assistant>,
    onCopy: (String) -> Unit,
    onOpenImage: (com.profer.pocket.model.ChatImage) -> Unit,
    imageLoader: suspend (String) -> androidx.compose.ui.graphics.ImageBitmap?,
    sessionStreaming: Boolean,
) {
    val layout = remember(steps) { layoutTurn(steps) }
    Column(modifier = Modifier.fillMaxWidth()) {
        AssistantMessage(
            steps = steps,
            layout = layout,
            onOpenImage = onOpenImage,
            imageLoader = imageLoader,
        )
        // 整轮结束后出现复制按钮；工具卡已经在过程组里逐条渲染，不再挤到操作行。
        if (!steps.any { it.streaming } && !sessionStreaming) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 2.dp),
                horizontalArrangement = Arrangement.Start,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // 纯工具回合没有正文，复制按钮会变成一个复制空串的死图标。
                if (layout.tailBlocks.isNotEmpty()) {
                    MessageActionIcon(Icons.Outlined.ContentCopy, "复制回复") {
                        onCopy(blocksPlainText(layout.tailBlocks))
                    }
                }
            }
        }
    }
}

/** 思考卡展开态按步骤 id 记：过程组收起再展开时，用户点开过的思考不会被夹回去。 */
private val ExpandedThoughtsSaver = listSaver<MutableState<Set<String>>, String>(
    save = { it.value.toList() },
    restore = { mutableStateOf(it.toSet()) },
)

/**
 * 一个回合的助手输出。
 *
 * 一轮可能有七八步（每步一段思考 + 几次工具调用）。桌面把整轮的过程收进同一个组，
 * 这里也一样：整轮只有一行「执行过程：N 次工具调用，M 段思考」，展开后按发生顺序
 * 铺开每步的思考、工具卡与中间说明；组外只留最后一步的正文。
 */
@Composable
private fun AssistantMessage(
    steps: List<ChatMessage.Assistant>,
    layout: TurnLayout,
    modifier: Modifier = Modifier,
    onOpenImage: (com.profer.pocket.model.ChatImage) -> Unit = {},
    imageLoader: suspend (String) -> androidx.compose.ui.graphics.ImageBitmap? = { null },
) {
    val stepId = steps.first().id
    val streaming = steps.any { it.streaming }
    // 过程组折叠态：流式中展开，回合结束后收起。用户手动点过之后不再自动切换，
    // 避免刚展开看完就被折叠走。
    var processExpanded by rememberSaveable(stepId) { mutableStateOf(streaming) }
    var processTouched by rememberSaveable(stepId) { mutableStateOf(false) }
    LaunchedEffect(streaming) {
        if (streaming) {
            if (!processTouched) processExpanded = true
            return@LaunchedEffect
        }
        if (processTouched || !processExpanded) return@LaunchedEffect
        // 留一点时间读完最后一条工具结果再收起，与桌面「结束后自动折叠」同节奏。
        delay(ProcessAutoCollapseDelayMillis)
        processExpanded = false
    }
    // 展开后的过程只铺最近几步：一轮十几步全铺开会让一屏变成长卷，滚动也难定位。
    var stepsExpanded by rememberSaveable(stepId) { mutableStateOf(false) }
    // 思考是本轮唯一产出时直接铺在正文位置并展开，所以挂载时就把它的展开态预置好。
    var expandedThoughts by rememberSaveable(stepId, saver = ExpandedThoughtsSaver) {
        mutableStateOf(
            if (layout.thinkingOnly) {
                layout.items.filterIsInstance<TurnItem.Thought>().map { it.key }.toSet()
            } else {
                emptySet()
            },
        )
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            // 读屏能听到「正在生成」，不用靠光标闪烁自己猜。
            .semantics { if (streaming) stateDescription = "正在生成" },
        verticalArrangement = Arrangement.spacedBy(ProferSpacing.sm),
    ) {
        if (layout.thinkingOnly) {
            // 思考是本轮唯一产出：折成一行摘要会只剩「执行过程：1 段思考」。
            layout.items.filterIsInstance<TurnItem.Thought>().forEach { thought ->
                ThinkingRow(
                    thinking = thought.block,
                    expanded = thought.key in expandedThoughts,
                    onToggle = { expandedThoughts = expandedThoughts.toggle(thought.key) },
                )
            }
        } else if (layout.hasProcess) {
            // 思考与工具同属一轮的执行过程：收成一行「执行过程：…」，
            // 和桌面一样默认折叠，展开后才看到思考卡与逐条工具卡。
            val kinds = remember(layout.tools) { layout.tools.map { it.kind }.distinct() }
            val shownKinds = kinds.take(ProcessGroupIconLimit)
            ProferProcessGroup(
                summary = processGroupSummary(
                    toolCount = layout.tools.size,
                    thinkingCount = layout.thinkingCount,
                ),
                expanded = processExpanded,
                onToggle = {
                    processTouched = true
                    processExpanded = !processExpanded
                },
                streaming = streaming,
                icons = shownKinds.map { it.icon() },
                hiddenIconCount = (kinds.size - shownKinds.size).coerceAtLeast(0),
            ) {
                ProcessItems(
                    layout = layout,
                    expanded = stepsExpanded,
                    onToggleSteps = { stepsExpanded = !stepsExpanded },
                    expandedThoughts = expandedThoughts,
                    onToggleThought = { key -> expandedThoughts = expandedThoughts.toggle(key) },
                )
            }
        }

        if (layout.tailBlocks.isNotEmpty()) {
            ProferRichText(
                blocks = layout.tailBlocks,
                showCursor = streaming,
            )
        }

        // 图片放在正文之后：结论先读，图作为佐证。
        if (layout.images.isNotEmpty()) {
            MessageImages(images = layout.images, load = imageLoader, onOpen = onOpenImage)
        }
    }
}

private fun Set<String>.toggle(key: String): Set<String> =
    if (key in this) this - key else this + key

/** 过程组展开后的内容：按发生顺序铺，思考、工具与中间说明都不拆散。 */
@Composable
private fun ProcessItems(
    layout: TurnLayout,
    expanded: Boolean,
    onToggleSteps: () -> Unit,
    expandedThoughts: Set<String>,
    onToggleThought: (String) -> Unit,
) {
    val collapsed = layout.stepCount > ProcessStepPreviewCount
    // 过程组收起时整块不组合，所以这里的截断只影响「已展开」这一种情况。
    val firstVisible = if (expanded || !collapsed) 0 else layout.stepCount - ProcessStepPreviewCount
    layout.items.forEach { item ->
        if (item.step < firstVisible) return@forEach
        when (item) {
            is TurnItem.Thought -> ThinkingRow(
                thinking = item.block,
                expanded = item.key in expandedThoughts,
                onToggle = { onToggleThought(item.key) },
            )

            is TurnItem.Tool -> ToolCallCard(item.tool)

            is TurnItem.Text -> ProferRichText(blocks = item.blocks, showCursor = false)
        }
    }
    if (collapsed) {
        ProcessStepsToggle(
            hiddenCount = firstVisible,
            expanded = expanded,
            onToggle = onToggleSteps,
        )
    }
}

@Composable
private fun ThinkingRow(
    thinking: ThinkingBlock,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    // 折叠语义与桌面一致：标题行 + 虚线框正文，超过 4 行才出现「展开摘要」。
    ProferThinkingCard(
        body = thinking.body,
        expanded = expanded,
        onToggle = onToggle,
    )
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

    val shape = MaterialTheme.shapes.medium
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

/** 过程组展开后默认铺开的步骤数：再多就折起来，避免一轮十几步把一屏拉成长卷。 */
private const val ProcessStepPreviewCount = 3

/** 过程折叠行最多显示的工具类型图标数，与桌面 MAX_PROCESS_GROUP_ICONS 一致。 */
private const val ProcessGroupIconLimit = 4

/** 回合结束后多久自动收起过程组：留出读完最后一条工具结果的时间。 */
private const val ProcessAutoCollapseDelayMillis = 2_000L

/** 单条工具调用：图标由工具类型决定，状态由 tool_result 驱动。 */

@Composable
private fun ProcessStepsToggle(
    hiddenCount: Int,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = ProferSizes.minTouchTarget)
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onToggle)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(
            imageVector = if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(16.dp),
        )
        Text(
            text = if (expanded) "收起前面的步骤" else "还有 $hiddenCount 步过程",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

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
        input = tool.input,
        output = tool.output,
        error = tool.error,
    )
}

/**
 * 任务来源卡：定时任务/Agent 交接/外部请求触发的输入。
 * 只显示可读作者与来源，不回显内部 ID；正文照常可读。
 */
@Composable
private fun SourceCard(message: ChatMessage.Source) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = ProferSpacing.md, vertical = ProferSpacing.sm),
            verticalArrangement = Arrangement.spacedBy(ProferSpacing.xs),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(ProferSpacing.xs),
            ) {
                Icon(
                    imageVector = if (message.scheduled) Icons.Outlined.Schedule else Icons.Outlined.SmartToy,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(ProferSizes.iconSmall),
                )
                Text(
                    text = message.authorLabel,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (message.timeLabel.isNotBlank()) {
                    androidx.compose.foundation.layout.Spacer(modifier = Modifier.weight(1f))
                    Text(
                        text = message.timeLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            message.sourceLabel?.takeIf { it.isNotBlank() }?.let { label ->
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (message.text.isNotBlank()) {
                ProferRichText(
                    blocks = cachedRichBlocks(message.text),
                    showCursor = false,
                )
            }
        }
    }
}

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
    /** 子会话面包屑；普通会话为 null。 */
    subSessionLabel: String? = null,
    onOpenParent: () -> Unit = {},
    /** 双栏列表栏已收起：左侧键从「收起」变成「展开」。单栏恒为 false。 */
    listPaneCollapsed: Boolean = false,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val window = rememberProferWindowSize()
    val twoPane = window.isTwoPane
    // 横屏短屏：顶栏只占一条，标题不再包浮层胶囊（桌面版会话头也是一条平铺行）。
    val compact = window.isShort

    ProferHeaderRow(compact = compact) {
        ProferIconAction(
            // 双栏用「面板」语义的图标：MenuOpen 是「把列表收起来」，ViewSidebar 是「把列表叫回来」；
            // 单栏仍然是汉堡键（抽屉）。
            icon = when {
                !twoPane -> Icons.Outlined.Menu
                listPaneCollapsed -> Icons.AutoMirrored.Outlined.ViewSidebar
                else -> Icons.AutoMirrored.Outlined.MenuOpen
            },
            contentDescription = when {
                !twoPane -> "打开会话列表"
                listPaneCollapsed -> "展开会话列表"
                else -> "收起会话列表"
            },
            onClick = onOpenDrawer,
            // 紧凑顶栏去掉圆键底，只留图标，跟桌面版顶栏一条平铺行。
            chrome = !compact,
        )
        // 顶栏只留会话标题：项目名与连接点属于副信息，不再占第二行。
        // 子会话例外：它必须能看出「挂在哪个母会话下面」，并能一键回去。
        // 中间整块占满，胶囊自己按内容收窄：宽屏下标题不会被拉成一条长条，
        // 右侧「更多」仍贴边。
        Box(modifier = Modifier.weight(1f)) {
        Column(
            modifier = if (compact) {
                Modifier
            } else {
                Modifier
                    .clip(MaterialTheme.shapes.extraLarge)
                    // 深色下 surfaceContainerLowest 比画布更暗，直接当浮层会像「挖洞」。
                    .background(proferFloatingSurface())
                    .padding(horizontal = ProferSpacing.md, vertical = ProferSpacing.sm)
            },
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subSessionLabel != null) {
                Text(
                    text = subSessionLabel,
                    style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.1.sp),
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.85f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .clip(MaterialTheme.shapes.small)
                        .clickable(onClick = onOpenParent)
                        .padding(vertical = 1.dp),
                )
            }
        }
        }
        // 上下文用量是会话级状态：环负责压力感，数字与动作在底部弹层。
        // 还没有会话时不画空环，避免顶栏出现一个没有含义的灰圈。
        // 新会话还没有 usage 时不画空环：顶栏不能出现一个没有含义的灰圈。
        if (hasSession && (contextUsage?.usedTokens != null || contextWindow != null)) {
            ContextUsageIndicator(
                usage = contextUsage,
                window = contextWindow,
                onClick = onContextClick,
            )
        }
        ProferIconAction(
            icon = Icons.Outlined.MoreVert,
            contentDescription = "更多操作",
            onClick = { menuOpen = true },
            chrome = !compact,
        )
        DropdownMenu(
            expanded = menuOpen,
            onDismissRequest = { menuOpen = false },
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
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
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f)),
            ) {
                Row(
                    modifier = Modifier
                        .heightIn(min = ProferSizes.minTouchTarget)
                        .padding(start = 8.dp),
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
                    // 视觉 18dp、热区 48dp：移除是破坏性操作，不能靠 18dp 的命中区。
                    Box(
                        modifier = Modifier
                            .size(ProferSizes.minTouchTarget)
                            .clip(CircleShape)
                            .clickable(role = Role.Button) { onRemove(file.path) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Close,
                            contentDescription = "移除 ${file.filename}",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
                            modifier = Modifier.size(18.dp),
                        )
                    }
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
                shape = MaterialTheme.shapes.small,
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
