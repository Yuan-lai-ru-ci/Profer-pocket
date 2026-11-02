package com.profer.pocket.sessions

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AccountTree
import androidx.compose.material.icons.outlined.CallSplit
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.DriveFileMove
import androidx.compose.material.icons.outlined.DriveFileRenameOutline
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.MarkEmailRead
import androidx.compose.material.icons.outlined.MarkEmailUnread
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.profer.pocket.designsystem.component.ProferChrome
import com.profer.pocket.designsystem.component.ProferIconAction
import com.profer.pocket.designsystem.component.ProferEmptyState
import com.profer.pocket.designsystem.component.ProferMark
import com.profer.pocket.designsystem.component.ProferStatusDot
import com.profer.pocket.designsystem.component.rememberLongPressFeedback
import com.profer.pocket.designsystem.theme.ProferSizes
import com.profer.pocket.designsystem.theme.ProferSpacing
import com.profer.pocket.designsystem.theme.ProferThemeExtras

/**
 * 会话列表内容：抽屉与全屏页共用。
 *
 * 品牌与主操作、模式切换、项目目录分层排布；列表不逐行描边。
 * 标题与辅助信息纵向排布，让大字号仍能读完整的会话上下文。
 */
private val ClearEase = CubicBezierEasing(0.3f, 0.9f, 0.2f, 1f)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun SessionListContent(
    state: SessionListState,
    onQueryChange: (String) -> Unit,
    onSelectSession: (String) -> Unit,
    onNewSession: () -> Unit,
    onOpenConnection: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
    showHeader: Boolean = true,
    mode: DrawerMode = DrawerMode.Agent,
    onModeChange: (DrawerMode) -> Unit = {},
    conversations: ConversationListState = ConversationListState(),
    onSelectConversation: (String) -> Unit = {},
    onNewConversation: () -> Unit = {},
    onConversationQueryChange: (String) -> Unit = {},
    onRefreshConversations: () -> Unit = {},
    onDeleteConversation: (String) -> Unit = {},
    onNewSessionInProject: (String) -> Unit = {},
    onRefresh: () -> Unit = {},
    onRenameSession: (SessionItem) -> Unit = {},
    onTogglePin: (SessionItem) -> Unit = {},
    onToggleRead: (SessionItem) -> Unit = {},
    onMoveSession: (SessionItem) -> Unit = {},
    onDeleteSession: (SessionItem) -> Unit = {},
    onToggleProjectPin: (String) -> Unit = {},
) {
    var searchOpen by rememberSaveable { mutableStateOf(false) }
    // 展开状态要跟着配置变化/进程重建活下来：抽屉最烦的就是每次回来项目全收起。
    var expandedProjects by rememberSaveable { mutableStateOf(emptyList<String>()) }
    // 子会话默认收起：委派一多，抽屉不能被十条子行占满。
    // 显式开/关各记一份：否则「当前打开的是子会话」会一直把展开态顶回去，点收起没反应。
    var expandedSubSessions by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var collapsedSubSessions by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var defaultsApplied by rememberSaveable { mutableStateOf(false) }
    val forceExpand = state.query.isNotBlank()

    // 首屏必须有会话可点：默认展开当前会话所属项目、置顶项目、最近活动的项目与「置顶」组。
    // 只在第一次拿到项目目录时铺默认值，之后尊重用户手动收起。
    LaunchedEffect(state.projects, state.currentSessionId) {
        if (defaultsApplied || state.projects.isEmpty()) return@LaunchedEffect
        defaultsApplied = true
        val currentSession = state.projects.asSequence()
            .flatMap { it.sessions.asSequence() }
            .firstOrNull { it.id == state.currentSessionId }
        // 置顶会话的 group.workspaceId 是 null，要用会话自己的 workspaceId 找回项目。
        val currentProjectId = currentSession?.workspaceId
            ?: state.projects.firstOrNull { group ->
                group.kind == SessionGroupKind.Project &&
                    group.sessions.any { it.id == state.currentSessionId }
            }?.id
        val pinnedGroupId = state.projects.firstOrNull { it.kind == SessionGroupKind.Pinned }?.id
        val pinnedProjectIds = state.projects
            .filter { it.kind == SessionGroupKind.Project && it.pinned }
            .map { it.id }
        // projects 已按「置顶项目 → 最近活动」排好，第一个 Project 就是最近在用的项目。
        val recentProjectId = state.projects.firstOrNull { it.kind == SessionGroupKind.Project }?.id
        expandedProjects = (
            expandedProjects +
                listOfNotNull(currentProjectId, pinnedGroupId, recentProjectId) +
                pinnedProjectIds
            ).distinct()
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            // 侧边栏与承载对话界面的圆角容器必须是同一个底色，不能再叠渐变。
            .background(MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        if (showHeader) {
            SessionListHeader(
                searchOpen = searchOpen,
                mode = mode,
                onModeChange = onModeChange,
                onToggleSearch = {
                    searchOpen = !searchOpen
                    if (!searchOpen) {
                        if (mode == DrawerMode.Chat) onConversationQueryChange("") else onQueryChange("")
                    }
                },
                onNewSession = if (mode == DrawerMode.Chat) onNewConversation else onNewSession,
            )
        }

        if (searchOpen) {
            SearchField(
                query = if (mode == DrawerMode.Chat) conversations.query else state.query,
                onQueryChange = if (mode == DrawerMode.Chat) onConversationQueryChange else onQueryChange,
                onClose = {
                    searchOpen = false
                    if (mode == DrawerMode.Chat) onConversationQueryChange("") else onQueryChange("")
                },
            )
        }

        if (mode == DrawerMode.Chat) {
            ConversationBody(
                state = conversations,
                onSelect = onSelectConversation,
                onRefresh = onRefreshConversations,
                onNewConversation = onNewConversation,
                onDelete = { item -> onDeleteConversation(item.id) },
                modifier = Modifier.weight(1f),
            )
        } else {
        PullToRefreshBox(
            isRefreshing = state.refreshing,
            onRefresh = onRefresh,
            modifier = Modifier.weight(1f),
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = ProferSpacing.sm,
                    end = ProferSpacing.sm,
                    top = 0.dp,
                    bottom = ProferSpacing.md,
                ),
            ) {
                if (state.projects.isEmpty() && state.refreshing) {
                    item(key = "loading", contentType = "loading") { SessionListSkeleton() }
                }
                if (state.projects.isEmpty() && !state.refreshing) {
                    item(key = "empty") {
                        ProferEmptyState(
                            title = if (state.query.isBlank()) "还没有会话" else "没有匹配的会话",
                            description = if (state.query.isBlank()) {
                                "在 Mac 上新建一个会话，或直接从手机发起。"
                            } else {
                                "换个关键词试试，搜索会匹配会话标题和项目名。"
                            },
                            icon = Icons.Outlined.Search,
                            primaryLabel = if (state.query.isBlank()) "新建会话" else null,
                            onPrimary = if (state.query.isBlank()) onNewSession else null,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = ProferSpacing.xxl),
                        )
                    }
                }

                state.projects.forEach { group ->
                    val expanded = forceExpand || group.id in expandedProjects
                    stickyHeader(key = "group-${group.id}", contentType = "group") {
                        ProjectHeader(
                            group = group,
                            expanded = expanded,
                            onToggle = {
                                expandedProjects = if (group.id in expandedProjects) {
                                    expandedProjects - group.id
                                } else {
                                    expandedProjects + group.id
                                }
                            },
                            onCreateSession = if (group.kind == SessionGroupKind.Project) {
                                { onNewSessionInProject(group.workspaceId ?: group.id) }
                            } else {
                                null
                            },
                            onTogglePin = if (group.kind == SessionGroupKind.Project) {
                                { onToggleProjectPin(group.workspaceId ?: group.id) }
                            } else {
                                null
                            },
                        )
                    }
                    if (expanded && group.isEmptyProject) {
                        item(key = "empty-${group.id}", contentType = "empty-project") {
                            EmptyProjectRow(
                                onCreateSession = {
                                    onNewSessionInProject(group.workspaceId ?: group.id)
                                },
                            )
                        }
                    }
                    if (expanded) {
                        itemsIndexed(
                            items = group.sessions,
                            key = { _, session -> session.id },
                            contentType = { _, _ -> "session" },
                        ) { _, session ->
                            // 当前打开的会话是子会话时默认展开；用户手动收起后不再抢回来。
                            val childrenExpanded = if (forceExpand) {
                                true
                            } else if (session.id in collapsedSubSessions) {
                                false
                            } else {
                                session.id in expandedSubSessions ||
                                    session.children.any { it.id == state.currentSessionId }
                            }
                            Box(
                                modifier = Modifier.animateItem(
                                    fadeInSpec = tween(160, easing = ClearEase),
                                    placementSpec = tween(240, easing = ClearEase),
                                    fadeOutSpec = tween(100),
                                ),
                            ) {
                                Column {
                                    SessionRow(
                                        session = session,
                                        selected = session.id == state.currentSessionId,
                                        // 项目组内不重复项目名；置顶/未归入/搜索是跨项目列表，用 chip 标出来源。
                                        projectName = if (
                                            group.kind == SessionGroupKind.Project &&
                                            state.query.isBlank()
                                        ) {
                                            null
                                        } else {
                                            session.projectName
                                        },
                                        onClick = { onSelectSession(session.id) },
                                        onRename = { onRenameSession(session) },
                                        onTogglePin = { onTogglePin(session) },
                                        onToggleRead = { onToggleRead(session) },
                                        onMove = { onMoveSession(session) },
                                        onDelete = { onDeleteSession(session) },
                                        // 搜索/筛选只影响可见分组，移动目标必须看完整项目目录。
                                        canMove = state.hasProjects,
                                        childrenExpanded = childrenExpanded,
                                        onToggleChildren = if (session.children.isEmpty()) {
                                            null
                                        } else {
                                            {
                                                if (childrenExpanded) {
                                                    expandedSubSessions = expandedSubSessions - session.id
                                                    collapsedSubSessions = collapsedSubSessions + session.id
                                                } else {
                                                    collapsedSubSessions = collapsedSubSessions - session.id
                                                    expandedSubSessions = expandedSubSessions + session.id
                                                }
                                            }
                                        },
                                    )
                                    if (session.children.isNotEmpty() && childrenExpanded) {
                                        RelatedChildren(
                                            children = session.children,
                                            currentSessionId = state.currentSessionId,
                                            onSelectSession = onSelectSession,
                                            onRenameSession = onRenameSession,
                                            onTogglePin = onTogglePin,
                                            onToggleRead = onToggleRead,
                                            onMoveSession = onMoveSession,
                                            onDeleteSession = onDeleteSession,
                                            canMove = state.hasProjects,
                                        )
                                    }
                                }
                            }
                        }
                    }
                    item(key = "gap-${group.id}", contentType = "gap") {
                        Spacer(Modifier.height(ProferSpacing.sm))
                    }
                }
            }
        }
        }

        SessionListDock(
            state = state,
            onOpenConnection = onOpenConnection,
            onOpenSettings = onOpenSettings,
        )
    }
}

@Composable
private fun SessionListHeader(
    searchOpen: Boolean,
    mode: DrawerMode,
    onModeChange: (DrawerMode) -> Unit,
    onToggleSearch: () -> Unit,
    onNewSession: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = ProferSpacing.lg, vertical = ProferSpacing.sm),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = ProferSizes.minTouchTarget),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ProferSpacing.sm),
        ) {
            // 1.3× 起标题就会和模式胶囊抢空间（实测 1.3× 时「Profer」被切掉一半），
            // 到这一档就把胶囊挪到第二行，标题保住整词。
            val largeFont = LocalDensity.current.fontScale >= 1.3f
            ProferMark(size = ProferSizes.icon)
            Text(
                text = "Profer",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            // 大字号下模式按钮让到下一行，避免把品牌字挤成竖排。
            if (!largeFont) {
                DrawerModeButton(
                    chatSelected = mode == DrawerMode.Chat,
                    onToggle = {
                        onModeChange(if (mode == DrawerMode.Chat) DrawerMode.Agent else DrawerMode.Chat)
                    },
                )
            }
            HeaderIconButton(
                icon = Icons.Outlined.Search,
                contentDescription = if (searchOpen) "关闭搜索" else "搜索会话",
                active = searchOpen,
                onClick = onToggleSearch,
            )
            NewSessionButton(
                onClick = onNewSession,
                contentDescription = if (mode == DrawerMode.Chat) "新建对话" else "新建会话",
            )
        }
        if (LocalDensity.current.fontScale >= 1.3f) {
            Row(modifier = Modifier.fillMaxWidth().padding(top = ProferSpacing.xs)) {
                DrawerModeButton(
                    chatSelected = mode == DrawerMode.Chat,
                    onToggle = {
                        onModeChange(if (mode == DrawerMode.Chat) DrawerMode.Agent else DrawerMode.Chat)
                    },
                )
            }
        }
    }
}

@Composable
private fun HeaderIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    active: Boolean = false,
) {
    Box(
        modifier = Modifier
            .size(ProferSizes.minTouchTarget)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        // 可见圆与「新建」按钮同为 40dp，48dp 只做热区。
        ProferChrome(
            modifier = Modifier.size(ProferSizes.actionVisual).clip(CircleShape),
            pill = true,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = icon,
                    contentDescription = contentDescription,
                    tint = if (active) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.size(ProferSizes.icon),
                )
            }
        }
    }
}

@Composable
private fun NewSessionButton(onClick: () -> Unit, contentDescription: String) {
    Box(
        modifier = Modifier
            .size(ProferSizes.minTouchTarget)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(ProferSizes.actionVisual),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = Icons.Outlined.Add,
                    contentDescription = contentDescription,
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(ProferSizes.icon),
                )
            }
        }
    }
}

@Composable
private fun SearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    onClose: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = ProferSpacing.lg, vertical = ProferSpacing.xs),
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
    ) {
        Row(
            modifier = Modifier
                .heightIn(min = ProferSizes.minTouchTarget)
                .padding(start = ProferSpacing.lg, end = ProferSpacing.xs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector = Icons.Outlined.Search,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(17.dp),
            )
            Box(modifier = Modifier.weight(1f)) {
                if (query.isEmpty()) {
                    Text(
                        text = "搜索会话或项目",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                BasicTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(
                        color = MaterialTheme.colorScheme.onSurface,
                    ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = ProferSpacing.sm)
                        .semantics { contentDescription = "搜索会话或项目" },
                )
            }
            Box(
                modifier = Modifier
                    .size(ProferSizes.minTouchTarget)
                    .clip(CircleShape)
                    .clickable(onClick = onClose),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Outlined.Close,
                    contentDescription = "关闭搜索",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

/** 一级项目行：整行可点开合；标题只比会话多半档字重，靠留白建立层级。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ProjectHeader(
    group: ProjectGroup,
    expanded: Boolean,
    onToggle: () -> Unit,
    onCreateSession: (() -> Unit)?,
    onTogglePin: (() -> Unit)?,
) {
    // 菜单开合不参与状态恢复：进程重建后不应该自己弹出一个操作菜单。
    var menuOpen by remember(group.id) { mutableStateOf(false) }
    val longPressFeedback = rememberLongPressFeedback()
    val rotation by animateFloatAsState(
        targetValue = if (expanded) 0f else -90f,
        animationSpec = tween(durationMillis = 220, easing = ClearEase),
        label = "group-chevron",
    )
    Box {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                // sticky 项目头会盖在会话行上，底色必须不透明。
                .background(MaterialTheme.colorScheme.surfaceContainerLow)
                .padding(top = ProferSpacing.xs)
                .padding(horizontal = ProferSpacing.xs)
                .clip(MaterialTheme.shapes.medium)
                .combinedClickable(
                    onClick = onToggle,
                    onLongClick = {
                        if (onCreateSession != null || onTogglePin != null) {
                            longPressFeedback()
                            menuOpen = true
                        }
                    },
                )
                .heightIn(min = ProferSizes.minTouchTarget)
                .padding(horizontal = ProferSpacing.xs, vertical = ProferSpacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier.size(20.dp),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Outlined.ExpandMore,
                    contentDescription = if (expanded) "收起 ${group.name}" else "展开 ${group.name}",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .size(15.dp)
                        .graphicsLayer { rotationZ = rotation },
                )
            }
            // 项目名与置顶标记同一组：图标不再被推到行尾、悬在一片空白里。
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = group.name,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (group.pinned) {
                    Icon(
                        imageVector = Icons.Outlined.PushPin,
                        contentDescription = "已置顶项目",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .padding(start = 6.dp)
                            .size(14.dp),
                    )
                }
            }
            if (group.kind == SessionGroupKind.Project && group.runningCount > 0) {
                Spacer(Modifier.width(8.dp))
                Surface(
                    shape = CircleShape,
                    color = ProferThemeExtras.semantic.success.copy(alpha = 0.12f),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        ProferStatusDot(
                            color = ProferThemeExtras.semantic.success,
                            pulsing = true,
                            size = 5.dp,
                        )
                        Text(
                            text = "${group.runningCount}",
                            style = MaterialTheme.typography.labelSmall,
                            color = ProferThemeExtras.semantic.success,
                        )
                    }
                }
            }
        }
        DropdownMenu(
            expanded = menuOpen,
            onDismissRequest = { menuOpen = false },
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ) {
            if (onCreateSession != null) {
                SessionMenuItem(
                    label = "在此新建会话",
                    icon = Icons.Outlined.Add,
                    onClick = { menuOpen = false; onCreateSession() },
                )
            }
            if (onTogglePin != null) {
                SessionMenuItem(
                    label = if (group.pinned) "取消置顶项目" else "置顶项目",
                    icon = Icons.Outlined.PushPin,
                    onClick = { menuOpen = false; onTogglePin() },
                )
            }
        }
    }
}

@Composable
private fun EmptyProjectRow(onCreateSession: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = ProferSpacing.xs)
            .clip(MaterialTheme.shapes.small)
            .clickable(onClick = onCreateSession)
            .heightIn(min = ProferSizes.minTouchTarget)
            .padding(start = ProferSpacing.xl, end = ProferSpacing.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = "还没有会话",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        // 空项目行与二级会话同缩进，保持目录的垂直线。
        Text(
            text = "在此新建",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * 母行右侧的关联会话开关：`1/2 子会话` + 旋转箭头。
 *
 * 对齐桌面 `session-items.tsx` 的 delegationSummary —— 摘要长在母行上，不再单独占一整行，
 * 所以项目里有多少母会话就还是多少行。
 */
@Composable
private fun RelatedSummaryButton(
    summary: RelatedSummary,
    expanded: Boolean,
    onToggle: () -> Unit,
    compact: Boolean = false,
) {
    val rotation by animateFloatAsState(
        targetValue = if (expanded) 0f else -90f,
        animationSpec = tween(durationMillis = 150, easing = ClearEase),
        label = "related-summary-chevron",
    )
    val tint = MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        modifier = Modifier
            .clip(CircleShape)
            .clickable(onClick = onToggle)
            .heightIn(min = 32.dp)
            .widthIn(min = 32.dp)
            .padding(horizontal = ProferSpacing.xxs)
            // 与 ProferIconAction 同一套写法：标签与点击动作落在同一个语义节点上。
            .semantics {
                contentDescription = if (expanded) "收起${summary.label}" else "展开${summary.label}"
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Outlined.ExpandMore,
            contentDescription = null,
            tint = tint,
            modifier = Modifier
                .size(13.dp)
                .graphicsLayer { rotationZ = rotation },
        )
        if (summary.showCount) {
            // 大字号下省掉「子会话」三个字，把宽度留给标题；完整语义在 contentDescription 里。
            val compact = compact || LocalDensity.current.fontScale >= 1.5f
            Text(
                text = if (compact) {
                    "${summary.completed}/${summary.total}"
                } else {
                    "${summary.completed}/${summary.total} ${summary.label}"
                },
                style = MaterialTheme.typography.labelSmall,
                color = tint,
                maxLines = 1,
                softWrap = false,
            )
        }
    }
}

/**
 * 子会话区：左侧一条 1dp 引导线 + 子行，对齐桌面 `ml-3 border-l pl-2`。
 * 引导线画在整段高度上，行数变化时不会断成几截。
 */
@Composable
private fun RelatedChildren(
    children: List<SessionItem>,
    currentSessionId: String?,
    onSelectSession: (String) -> Unit,
    onRenameSession: (SessionItem) -> Unit,
    onTogglePin: (SessionItem) -> Unit,
    onToggleRead: (SessionItem) -> Unit,
    onMoveSession: (SessionItem) -> Unit,
    onDeleteSession: (SessionItem) -> Unit,
    canMove: Boolean,
) {
    val guide = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .drawBehind {
                val x = 37.dp.toPx()
                drawLine(
                    color = guide,
                    start = Offset(x, 0f),
                    end = Offset(x, size.height),
                    strokeWidth = 1.dp.toPx(),
                )
            },
    ) {
        children.forEach { child ->
            SessionRow(
                session = child,
                selected = child.id == currentSessionId,
                projectName = null,
                depth = 1,
                onClick = { onSelectSession(child.id) },
                onRename = { onRenameSession(child) },
                onTogglePin = { onTogglePin(child) },
                onToggleRead = { onToggleRead(child) },
                onMove = { onMoveSession(child) },
                onDelete = { onDeleteSession(child) },
                canMove = canMove,
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SessionRow(
    session: SessionItem,
    selected: Boolean,
    projectName: String?,
    onClick: () -> Unit,
    onRename: () -> Unit,
    onTogglePin: () -> Unit,
    onToggleRead: () -> Unit,
    onMove: () -> Unit,
    onDelete: () -> Unit,
    canMove: Boolean,
    /** 0 = 会话根行；1 = 子会话行。层级用几何分开，不只靠字形。 */
    depth: Int = 0,
    /** 子会话是否展开（只有带子会话的母行用得到）。 */
    childrenExpanded: Boolean = false,
    /** 母行右侧的「x/y 子会话」开关；无子会话时为 null。 */
    onToggleChildren: (() -> Unit)? = null,
) {
    // 菜单开合不参与状态恢复：进程重建后不应该自己弹出一个操作菜单。
    var menuOpen by remember(session.id) { mutableStateOf(false) }
    val longPressFeedback = rememberLongPressFeedback()
    // 大字号下标题会换行，子会话开关必须让到下一行，否则会把标题挤成两截。
    val largeFont = LocalDensity.current.fontScale >= 1.5f
    Box {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = ProferSpacing.xs, vertical = ProferSpacing.xxs)
                .clip(MaterialTheme.shapes.medium)
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = {
                        longPressFeedback()
                        menuOpen = true
                    },
                )
                .background(
                    if (selected) MaterialTheme.colorScheme.surfaceContainer
                    else Color.Transparent,
                )
                .heightIn(min = ProferSizes.minTouchTarget)
                .padding(
                    start = ProferSpacing.xl + (if (depth > 0) ProferSpacing.lg else 0.dp),
                    end = ProferSpacing.sm,
                    top = ProferSpacing.xs,
                    bottom = ProferSpacing.xs,
                ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ProferSpacing.sm),
        ) {
            if (depth > 0) {
                Icon(
                    imageVector = if (session.isExploration) Icons.Outlined.CallSplit else Icons.Outlined.AccountTree,
                    contentDescription = session.roleLabel,
                    tint = when (session.delegationStatus) {
                        "running" -> ProferThemeExtras.semantic.success
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.size(ProferSizes.iconSmall),
                )
            }
            if (!largeFont) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(ProferSpacing.sm),
                ) {
                    // 定时任务创建/复用的会话：与桌面侧栏同口径，标题前用钟表标记。
                    if (session.sourcedByAutomation) {
                        Icon(
                            imageVector = Icons.Outlined.Schedule,
                            contentDescription = "定时任务会话",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(ProferSizes.iconSmall),
                        )
                    }
                    Text(
                        text = session.title,
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
                        ),
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    if (!projectName.isNullOrBlank()) {
                        Text(
                            text = projectName,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.widthIn(max = 84.dp),
                        )
                    }
                    if (session.running || session.unread) {
                        ProferStatusDot(
                            color = if (session.running) ProferThemeExtras.semantic.success else MaterialTheme.colorScheme.tertiary,
                            pulsing = session.running,
                            size = 6.dp,
                            modifier = Modifier.semantics {
                                contentDescription = if (session.running) "运行中" else "未读"
                            },
                        )
                    }
                    Text(
                        text = session.timeLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                    val related = session.relatedSummary
                    if (related != null && onToggleChildren != null) {
                        RelatedSummaryButton(
                            summary = related,
                            expanded = childrenExpanded,
                            onToggle = onToggleChildren,
                            compact = true,
                        )
                    }
                }
            } else {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(ProferSpacing.xxs),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (session.sourcedByAutomation) {
                            Icon(
                                imageVector = Icons.Outlined.Schedule,
                                contentDescription = "定时任务会话",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier
                                    .size(ProferSizes.iconSmall)
                                    .padding(end = ProferSpacing.xxs),
                            )
                        }
                        Text(
                            text = session.title,
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
                            ),
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    if (!projectName.isNullOrBlank()) {
                        Text(
                            text = projectName,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(ProferSpacing.sm),
                    ) {
                        Text(
                            text = session.timeLabel,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (session.running || session.unread) {
                            ProferStatusDot(
                                color = if (session.running) ProferThemeExtras.semantic.success else MaterialTheme.colorScheme.tertiary,
                                pulsing = session.running,
                                size = 6.dp,
                                modifier = Modifier.semantics {
                                    contentDescription = if (session.running) "运行中" else "未读"
                                },
                            )
                        }
                        val related = session.relatedSummary
                        if (related != null && onToggleChildren != null) {
                            RelatedSummaryButton(
                                summary = related,
                                expanded = childrenExpanded,
                                onToggle = onToggleChildren,
                            )
                        }
                    }
                }
            }
        }

        DropdownMenu(
            expanded = menuOpen,
            onDismissRequest = { menuOpen = false },
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ) {
            SessionMenuItem(
                label = "重命名",
                icon = Icons.Outlined.DriveFileRenameOutline,
                onClick = { menuOpen = false; onRename() },
            )
            // 子会话归并在母会话行下，位置由母会话决定，不提供置顶入口。
            if (depth == 0 && !session.isSubSession) {
                SessionMenuItem(
                    label = if (session.pinned) "取消置顶" else "置顶会话",
                    icon = Icons.Outlined.PushPin,
                    onClick = { menuOpen = false; onTogglePin() },
                )
            }
            SessionMenuItem(
                label = if (session.unread) "标记为已读" else "标记为未读",
                icon = if (session.unread) Icons.Outlined.MarkEmailRead else Icons.Outlined.MarkEmailUnread,
                onClick = { menuOpen = false; onToggleRead() },
            )
            if (canMove) {
                SessionMenuItem(
                    label = "移动到项目",
                    icon = Icons.Outlined.DriveFileMove,
                    onClick = { menuOpen = false; onMove() },
                )
            }
            HorizontalDivider(
                modifier = Modifier.padding(vertical = 4.dp),
                color = MaterialTheme.colorScheme.outlineVariant,
            )
            SessionMenuItem(
                label = "删除会话",
                icon = Icons.Outlined.DeleteOutline,
                tone = MaterialTheme.colorScheme.error,
                onClick = { menuOpen = false; onDelete() },
            )
        }
    }
}

@Composable
private fun SessionMenuItem(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit,
    tone: Color = MaterialTheme.colorScheme.onSurface,
) {
    DropdownMenuItem(
        text = {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = tone,
            )
        },
        leadingIcon = {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = tone,
                modifier = Modifier.size(18.dp),
            )
        },
        onClick = onClick,
    )
}

/**
 * 底部工具区：一条「书脚」而不是一张浮卡。
 * 连接状态、地址、设置入口收在 48dp 单行里，把垂直空间还给目录。
 */
@Composable
private fun SessionListDock(
    state: SessionListState,
    onOpenConnection: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val statusColor = if (state.connected) {
        ProferThemeExtras.semantic.success
    } else {
        ProferThemeExtras.semantic.warning
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = ProferSpacing.lg, vertical = ProferSpacing.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ProferSpacing.sm),
    ) {
        Box(
            modifier = Modifier
                .heightIn(min = ProferSizes.minTouchTarget)
                .clip(CircleShape)
                .clickable(onClick = onOpenConnection),
            contentAlignment = Alignment.Center,
        ) {
            ProferChrome(
                modifier = Modifier.height(ProferSizes.actionVisual),
                pill = true,
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = ProferSpacing.md),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(ProferSpacing.sm),
                ) {
                    ProferStatusDot(color = statusColor, pulsing = !state.connected, size = 6.dp)
                    Text(
                        text = if (state.connected) {
                            "已连接 · " + state.deviceLabel.substringBefore(" · ").ifBlank { "本机" }
                        } else {
                            "未连接"
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        Spacer(modifier = Modifier.weight(1f))
        ProferIconAction(
            icon = Icons.Outlined.Settings,
            contentDescription = "设置",
            onClick = onOpenSettings,
        )
    }
}

/** 初次读取时保留目录的行高与节奏，避免空态闪现。 */
@Composable
internal fun SessionListSkeleton() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = ProferSpacing.lg, vertical = ProferSpacing.md)
            .clearAndSetSemantics { contentDescription = "正在读取会话" },
        verticalArrangement = Arrangement.spacedBy(ProferSpacing.md),
    ) {
        repeat(5) { index ->
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = ProferSizes.minTouchTarget)
                    .padding(vertical = ProferSpacing.sm),
                verticalArrangement = Arrangement.spacedBy(ProferSpacing.sm),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(if (index % 2 == 0) 0.8f else 0.6f)
                        .height(ProferSpacing.lg)
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest, MaterialTheme.shapes.small),
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.3f)
                        .height(ProferSpacing.sm)
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest, MaterialTheme.shapes.small),
                )
            }
        }
    }
}
