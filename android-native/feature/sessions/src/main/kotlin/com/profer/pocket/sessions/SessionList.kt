package com.profer.pocket.sessions

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.DriveFileMove
import androidx.compose.material.icons.outlined.DriveFileRenameOutline
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.MarkEmailRead
import androidx.compose.material.icons.outlined.MarkEmailUnread
import androidx.compose.material.icons.outlined.PushPin
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.profer.pocket.designsystem.component.ProferEmptyState
import com.profer.pocket.designsystem.component.ProferMark
import com.profer.pocket.designsystem.component.ProferStatusDot
import com.profer.pocket.designsystem.theme.ProferSerif
import com.profer.pocket.designsystem.theme.ProferSizes
import com.profer.pocket.designsystem.theme.ProferSpacing
import com.profer.pocket.designsystem.theme.ProferThemeExtras
import com.profer.pocket.designsystem.theme.ProferTitleSerif

/**
 * 会话列表内容：抽屉与全屏页共用。
 *
 * 视觉基线（2026-10 重做）：不做「一叠填充块」，而是做一份安静的目录。
 * - 一级靠字重、缩进与留白分层，二级靠 1dp 发丝线分隔；
 * - 全列表只有一个主操作（标题栏右上角的实心圆），其余动作降到幽灵态；
 * - 选中态用 4.5% 主色薄雾 + 2dp 短竖线，不做整行色块；
 * - 底部工具区收进一张带描边的卡片，避免三个浮动胶囊抢视线。
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
) {
    var searchOpen by rememberSaveable { mutableStateOf(false) }
    // 项目是一级列表：默认只展开当前会话所在项目与置顶组，其余收起。
    var expandedProjects by remember { mutableStateOf(setOf<String>()) }
    val forceExpand = state.query.isNotBlank()

    LaunchedEffect(state.currentSessionId, state.projects) {
        val currentProject = state.projects.firstOrNull { group ->
            group.sessions.any { it.id == state.currentSessionId }
        }?.id
        val pinned = state.projects.firstOrNull { it.kind == SessionGroupKind.Pinned }?.id
        expandedProjects = expandedProjects + listOfNotNull(currentProject, pinned)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            // 侧边栏与承载对话界面的圆角容器必须是同一个底色，不能再叠渐变。
            .background(MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        if (showHeader) {
            SessionListHeader(
                summary = if (mode == DrawerMode.Chat) {
                    if (conversations.items.isEmpty()) "" else "${conversations.items.size} 段对话"
                } else if (state.projects.isNotEmpty()) {
                    "${state.projects.size} 组 · ${state.totalSessions}"
                } else {
                    ""
                },
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
            Hairline(modifier = Modifier.padding(horizontal = ProferSpacing.lg))
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
                    top = 2.dp,
                    bottom = ProferSpacing.md,
                ),
            ) {
                if (state.projects.isEmpty()) {
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
                    item(key = "group-${group.id}", contentType = "group") {
                        Box(
                            modifier = Modifier.animateItem(
                                fadeInSpec = tween(180, easing = ClearEase),
                                placementSpec = tween(260, easing = ClearEase),
                                fadeOutSpec = tween(120),
                            ),
                        ) {
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
                            )
                        }
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
                            Box(
                                modifier = Modifier.animateItem(
                                    fadeInSpec = tween(160, easing = ClearEase),
                                    placementSpec = tween(240, easing = ClearEase),
                                    fadeOutSpec = tween(100),
                                ),
                            ) {
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
                                )
                            }
                        }
                    }
                    item(key = "gap-${group.id}", contentType = "gap") {
                        Spacer(Modifier.height(ProferSpacing.md))
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
    summary: String,
    searchOpen: Boolean,
    mode: DrawerMode,
    onModeChange: (DrawerMode) -> Unit,
    onToggleSearch: () -> Unit,
    onNewSession: () -> Unit,
) {
    // 大字号下目录规模先让位：它是副信息，不该把主操作挤出抽屉。
    val compactMeta = LocalDensity.current.fontScale < 1.2f
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .heightIn(min = 52.dp)
            .padding(start = 16.dp, end = 6.dp, top = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ProferMark(size = 22.dp)
        Spacer(Modifier.width(8.dp))
        // 品牌字用衬线：它是这张「扉页」上唯一的题名。
        Text(
            text = "Profer",
            // 品牌字是这张「扉页」上唯一的题名，用衬线；其余文字保持无衬线。
            style = MaterialTheme.typography.titleLarge.copy(
                fontFamily = ProferSerif,
                fontSize = 20.sp,
                letterSpacing = 0.2.sp,
            ),
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
        )
        Spacer(Modifier.width(10.dp))
        DrawerModeSwitch(
            chatSelected = mode == DrawerMode.Chat,
            onSelectAgent = { onModeChange(DrawerMode.Agent) },
            onSelectChat = { onModeChange(DrawerMode.Chat) },
            compact = true,
        )
        Spacer(Modifier.weight(1f))
        HeaderIconButton(
            icon = Icons.Outlined.Search,
            contentDescription = if (searchOpen) "关闭搜索" else "搜索会话",
            active = searchOpen,
            onClick = onToggleSearch,
            size = 44.dp,
        )
        NewSessionButton(onClick = onNewSession)
    }
}

@Composable
private fun HeaderIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    active: Boolean = false,
    size: Dp = ProferSizes.minTouchTarget,
) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(
                if (active) MaterialTheme.colorScheme.surfaceContainer else Color.Transparent,
                CircleShape,
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = if (active) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f)
            },
            modifier = Modifier.size(20.dp),
        )
    }
}

@Composable
private fun NewSessionButton(onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(34.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = Icons.Outlined.Add,
                    contentDescription = "新建会话",
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(19.dp),
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
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.85f),
        ),
    ) {
        Row(
            modifier = Modifier
                .heightIn(min = 44.dp)
                .padding(start = 14.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector = Icons.Outlined.Search,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                modifier = Modifier.size(17.dp),
            )
            Box(modifier = Modifier.weight(1f)) {
                if (query.isEmpty()) {
                    Text(
                        text = "搜索会话或项目",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
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
                    modifier = Modifier.fillMaxWidth(),
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
@Composable
private fun ProjectHeader(
    group: ProjectGroup,
    expanded: Boolean,
    onToggle: () -> Unit,
    onCreateSession: (() -> Unit)?,
) {
    val rotation by animateFloatAsState(
        targetValue = if (expanded) 0f else -90f,
        animationSpec = tween(durationMillis = 220, easing = ClearEase),
        label = "group-chevron",
    )
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = ProferSpacing.xs)
            .clip(MaterialTheme.shapes.medium),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggle)
                .heightIn(min = 40.dp)
                .padding(start = 2.dp, end = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier.size(28.dp),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Outlined.ExpandMore,
                    contentDescription = if (expanded) "收起 ${group.name}" else "展开 ${group.name}",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f),
                    modifier = Modifier
                        .size(15.dp)
                        .graphicsLayer { rotationZ = rotation },
                )
            }
            // 一级题名用衬线：项目是「编」，会话是「篇」，层级靠字形分开，不靠分割线。
            Text(
                text = group.name,
                style = ProferTitleSerif,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (group.kind == SessionGroupKind.Project && group.runningCount > 0) {
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
            if (group.sessions.isNotEmpty()) {
                Text(
                    text = "${group.sessions.size}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    textAlign = TextAlign.End,
                    modifier = Modifier
                        .padding(start = 8.dp)
                        .widthIn(min = 14.dp),
                )
            }
            if (onCreateSession != null) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .clickable(onClick = onCreateSession),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Add,
                        contentDescription = "在 ${group.name} 新建会话",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                        modifier = Modifier.size(16.dp),
                    )
                }
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
            .padding(start = 44.dp, end = ProferSpacing.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = "还没有会话",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
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
) {
    // 菜单开合不参与状态恢复：进程重建后不应该自己弹出一个操作菜单。
    var menuOpen by remember(session.id) { mutableStateOf(false) }
    Box {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 2.dp)
                .clip(MaterialTheme.shapes.small)
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = { menuOpen = true },
                )
                .background(
                    if (selected) {
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.045f)
                    } else {
                        Color.Transparent
                    },
                )
                .heightIn(min = 44.dp)
                .padding(start = 18.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 选中标记做成页边的一条短竖线，比整行色块安静。
            Box(
                modifier = Modifier
                    .width(2.dp)
                    .height(15.dp)
                    .background(
                        color = if (selected) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            Color.Transparent
                        },
                        shape = RoundedCornerShape(1.dp),
                    ),
            )
            Spacer(Modifier.width(10.dp))
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
            if (projectName != null) {
                Spacer(Modifier.width(8.dp))
                Surface(
                    shape = CircleShape,
                    color = Color.Transparent,
                    border = BorderStroke(
                        1.dp,
                        MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.9f),
                    ),
                ) {
                    Text(
                        text = projectName,
                        style = MaterialTheme.typography.labelSmall.copy(
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .widthIn(max = 76.dp)
                            .padding(horizontal = 7.dp, vertical = 1.dp),
                    )
                }
            }
            if (session.running) {
                Spacer(Modifier.width(8.dp))
                ProferStatusDot(
                    color = ProferThemeExtras.semantic.success,
                    pulsing = true,
                    size = 6.dp,
                    modifier = Modifier.semantics { contentDescription = "运行中" },
                )
            } else if (session.unread) {
                Spacer(Modifier.width(8.dp))
                ProferStatusDot(
                    color = MaterialTheme.colorScheme.tertiary,
                    size = 6.dp,
                    modifier = Modifier.semantics { contentDescription = "未读" },
                )
            }
            Spacer(Modifier.width(8.dp))
            Text(
                text = session.timeLabel,
                style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.1.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.66f),
                maxLines = 1,
                textAlign = TextAlign.End,
                modifier = Modifier.widthIn(min = 34.dp, max = 64.dp),
            )
        }

        DropdownMenu(
            expanded = menuOpen,
            onDismissRequest = { menuOpen = false },
        ) {
            SessionMenuItem(
                label = "重命名",
                icon = Icons.Outlined.DriveFileRenameOutline,
                onClick = { menuOpen = false; onRename() },
            )
            SessionMenuItem(
                label = if (session.pinned) "取消置顶" else "置顶会话",
                icon = Icons.Outlined.PushPin,
                onClick = { menuOpen = false; onTogglePin() },
            )
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
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(bottom = 2.dp),
    ) {
        Hairline(modifier = Modifier.padding(horizontal = ProferSpacing.lg))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clip(MaterialTheme.shapes.medium)
                    .clickable(onClick = onOpenConnection)
                    .padding(start = 18.dp, end = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier.size(16.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        modifier = Modifier
                            .size(13.dp)
                            .border(1.dp, statusColor.copy(alpha = 0.35f), CircleShape),
                    )
                    ProferStatusDot(
                        color = statusColor,
                        pulsing = !state.connected,
                        size = 5.dp,
                    )
                }
                Spacer(Modifier.width(9.dp))
                Text(
                    text = if (state.connected) "已连接" else "未连接",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                )
                if (state.connected) {
                    Text(
                        text = "· " + state.deviceLabel.substringBefore(" · ").ifBlank { "本机" },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(start = 4.dp),
                    )
                }
            }
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onOpenSettings),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Outlined.Settings,
                    contentDescription = "设置",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f),
                    modifier = Modifier.size(19.dp),
                )
            }
            Spacer(Modifier.width(2.dp))
        }
    }
}

@Composable
private fun Hairline(
    modifier: Modifier = Modifier,
    start: Dp = 0.dp,
    end: Dp = 0.dp,
) {
    HorizontalDivider(
        modifier = modifier.padding(start = start, end = end),
        thickness = 1.dp,
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.8f),
    )
}

