package com.profer.pocket.sessions

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.profer.pocket.designsystem.adaptive.rememberProferWindowSize
import com.profer.pocket.designsystem.component.ProferEmptyState
import com.profer.pocket.designsystem.component.proferSelectedSurface
import com.profer.pocket.designsystem.component.rememberLongPressFeedback
import com.profer.pocket.designsystem.theme.ProferSizes
import com.profer.pocket.designsystem.theme.ProferSpacing

/** 抽屉的两种模式；feature 层不依赖 core:model。 */
enum class DrawerMode { Agent, Chat }

/** Chat（普通对话）列表项：只保留标题、时间与置顶态。 */
@Immutable
data class ConversationItem(
    val id: String,
    val title: String,
    val timeLabel: String,
    val pinned: Boolean = false,
    val selected: Boolean = false,
    val preview: String = "",
)

@Immutable
data class ConversationListState(
    val items: List<ConversationItem> = emptyList(),
    val query: String = "",
    val refreshing: Boolean = false,
)

/** Agent / Chat 的轻量分段；每段独立拥有至少 48dp 的触控区域。 */
@Composable
fun DrawerModeSwitch(
    chatSelected: Boolean,
    onSelectAgent: () -> Unit,
    onSelectChat: () -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    Row(
        modifier = modifier
            .then(if (compact) Modifier.widthIn(min = 160.dp) else Modifier.fillMaxWidth())
            .heightIn(min = ProferSizes.minTouchTarget)
            .clip(MaterialTheme.shapes.extraLarge)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .selectableGroup(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ModeSegment(
            label = "Agent",
            selected = !chatSelected,
            onClick = onSelectAgent,
            modifier = Modifier.weight(1f),
        )
        ModeSegment(
            label = "Chat",
            selected = chatSelected,
            onClick = onSelectChat,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun ModeSegment(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .heightIn(min = ProferSizes.minTouchTarget)
            .clip(MaterialTheme.shapes.extraLarge)
            .then(
                if (selected) {
                    Modifier
                        .background(proferSelectedSurface())
                        .border(
                            ProferSizes.hairline,
                            MaterialTheme.colorScheme.outlineVariant,
                            MaterialTheme.shapes.extraLarge,
                        )
                } else {
                    Modifier
                },
            )
            .selectable(selected = selected, role = Role.Tab, onClick = onClick)
            .padding(horizontal = ProferSpacing.sm),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            maxLines = 1,
            softWrap = false,
            style = MaterialTheme.typography.labelMedium.copy(
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            ),
            color = if (selected) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
    }
}


/**
 * 单按钮模式切换：只显示当前模式，点按在 Agent / Chat 之间切换。
 * 比两段控件省一整行高度；语义里写明当前模式与目标模式。
 */
@Composable
fun DrawerModeButton(
    chatSelected: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    /** 窄侧栏（约 200–260dp）：去掉箭头图标、收内边距，只留文字，一行才放得下三颗按钮。 */
    compact: Boolean = false,
) {
    val label = if (chatSelected) "Chat" else "Agent"
    Box(
        modifier = modifier
            .heightIn(min = ProferSizes.minTouchTarget)
            .clip(MaterialTheme.shapes.extraLarge)
            .clickable(role = Role.Button, onClick = onToggle)
            .semantics { contentDescription = "当前 $label 模式，点按切换" }
            .padding(horizontal = ProferSpacing.xxs),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceContainer,
            // 与右侧搜索 / 新建的 40dp 圆键同高，顶栏才是一条齐平的带子。
            modifier = Modifier.height(ProferSizes.actionVisual),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = if (compact) ProferSpacing.sm else ProferSpacing.md),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(ProferSpacing.xs),
            ) {
                if (!compact) {
                    Icon(
                        imageVector = Icons.Outlined.SwapHoriz,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(14.dp),
                    )
                }
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                )
            }
        }
    }
}

/** Chat 模式的目录：平铺对话，不按项目分组。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversationBody(
    state: ConversationListState,
    onSelect: (String) -> Unit,
    onRefresh: () -> Unit,
    onNewConversation: () -> Unit,
    modifier: Modifier = Modifier,
    onDelete: (ConversationItem) -> Unit = {},
) {
    val shortScreen = rememberProferWindowSize().isShort
    PullToRefreshBox(
        isRefreshing = state.refreshing,
        onRefresh = onRefresh,
        modifier = modifier.fillMaxSize(),
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = ProferSpacing.sm,
                end = ProferSpacing.sm,
                top = ProferSpacing.sm,
                bottom = ProferSpacing.md,
            ),
        ) {
            if (state.items.isEmpty() && state.refreshing) {
                item(key = "loading") {
                    SessionListSkeleton()
                }
            }
            if (state.items.isEmpty() && !state.refreshing) {
                item(key = "empty") {
                    ProferEmptyState(
                        title = if (state.query.isBlank()) "还没有 Chat 对话" else "没有匹配的对话",
                        description = if (state.query.isBlank()) {
                            "Chat 是电脑上的普通对话，不和 Agent 会话混在一起。"
                        } else {
                            "换个关键词试试，搜索只匹配对话标题。"
                        },
                        icon = Icons.Outlined.Forum,
                        primaryLabel = if (state.query.isBlank()) "新建对话" else null,
                        onPrimary = if (state.query.isBlank()) onNewConversation else null,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = if (shortScreen) 0.dp else ProferSpacing.xxl),
                    )
                }
            }
            items(state.items, key = { it.id }, contentType = { "conversation" }) { item ->
                ConversationRow(
                    item = item,
                    onClick = { onSelect(item.id) },
                    onDelete = { onDelete(item) },
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ConversationRow(
    item: ConversationItem,
    onClick: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val longPressFeedback = rememberLongPressFeedback()
    Box {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = ProferSpacing.xs, vertical = ProferSpacing.xxs)
                .clip(MaterialTheme.shapes.large)
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = {
                        longPressFeedback()
                        menuOpen = true
                    },
                )
                .background(
                    if (item.selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
                    else Color.Transparent,
                )
                .heightIn(min = ProferSizes.listItemHeight)
                .padding(horizontal = ProferSpacing.lg, vertical = ProferSpacing.md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ProferSpacing.sm),
        ) {
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .height(ProferSpacing.lg)
                    .background(
                        if (item.selected) MaterialTheme.colorScheme.primary else Color.Transparent,
                        MaterialTheme.shapes.extraSmall,
                    ),
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(ProferSpacing.xs),
            ) {
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontWeight = if (item.selected) FontWeight.Medium else FontWeight.Normal,
                    ),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                if (item.preview.isNotBlank()) {
                    Text(
                        text = item.preview,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(ProferSpacing.sm),
                ) {
                    if (item.pinned) {
                        Icon(
                            imageVector = Icons.Outlined.PushPin,
                            contentDescription = "已置顶",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(ProferSizes.iconSmall),
                        )
                    }
                    Text(
                        text = item.timeLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        DropdownMenu(
            expanded = menuOpen,
            onDismissRequest = { menuOpen = false },
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ) {
            DropdownMenuItem(
                text = { Text("删除对话", style = MaterialTheme.typography.bodyMedium) },
                leadingIcon = {
                    Icon(
                        Icons.Outlined.DeleteOutline,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(18.dp),
                    )
                },
                onClick = {
                    menuOpen = false
                    onDelete()
                },
            )
        }
    }
}
