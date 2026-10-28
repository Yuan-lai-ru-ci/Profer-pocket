package com.profer.pocket.sessions

import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.profer.pocket.designsystem.component.ProferEmptyState
import com.profer.pocket.designsystem.component.rememberLongPressFeedback
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

/**
 * Agent / Chat 模式切换：与品牌字同排，紧凑胶囊。
 * 目录层的主切换就在标题行，不再单独占一行。
 */
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
            .then(if (compact) Modifier.width(96.dp) else Modifier.fillMaxWidth())
            .heightIn(min = 28.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(horizontal = if (compact) 2.dp else ProferSpacing.lg, vertical = 2.dp),
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
            .heightIn(min = 24.dp)
            .padding(horizontal = 2.dp, vertical = 2.dp)
            .clip(CircleShape)
            .background(
                if (selected) MaterialTheme.colorScheme.surfaceContainerLowest else Color.Transparent,
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall.copy(
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                letterSpacing = 0.3.sp,
            ),
            color = if (selected) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
            },
        )
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
                top = 2.dp,
                bottom = ProferSpacing.md,
            ),
        ) {
            if (state.items.isEmpty() && state.refreshing) {
                item(key = "loading") {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = ProferSpacing.xxl),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "正在读取对话…",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        )
                    }
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
                            .padding(top = ProferSpacing.xxl),
                    )
                }
            }
            items(state.items, key = { it.id }) { item ->
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
            .padding(horizontal = 2.dp)
            .clip(MaterialTheme.shapes.small)
            .combinedClickable(
                onClick = onClick,
                onLongClick = {
                    longPressFeedback()
                    menuOpen = true
                },
            )
            .background(
                if (item.selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.045f) else Color.Transparent,
            )
            .heightIn(min = 48.dp)
            .padding(start = 18.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .width(2.dp)
                .height(15.dp)
                .background(
                    color = if (item.selected) MaterialTheme.colorScheme.primary else Color.Transparent,
                    shape = MaterialTheme.shapes.extraSmall,
                ),
        )
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.title,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontWeight = if (item.selected) FontWeight.Medium else FontWeight.Normal,
                ),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (item.preview.isNotBlank()) {
                Text(
                    text = item.preview,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (item.pinned) {
            Spacer(Modifier.width(6.dp))
            Box(
                modifier = Modifier
                    .width(5.dp)
                    .height(5.dp)
                    .background(MaterialTheme.colorScheme.tertiary, CircleShape),
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(
            text = item.timeLabel,
            style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.1.sp),
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.62f),
            maxLines = 1,
            modifier = Modifier.widthIn(min = 34.dp),
        )
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
