package com.profer.pocket.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.profer.pocket.designsystem.theme.ProferHeadingSerif
import com.profer.pocket.designsystem.component.sheetMaxHeight
import com.profer.pocket.designsystem.theme.ProferSpacing
import com.profer.pocket.model.ChatToolSummary

/**
 * Chat 模式工具开关。
 *
 * 开关是全局配置（与电脑端 ~/.profer/chat-tools.json 共用），不是单次对话的临时选择；
 * 因此这里明确写「与电脑端共享」，避免用户以为只影响手机。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ChatToolSheet(
    tools: List<ChatToolSummary>,
    onToggle: (String, Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        // 弹层最高只到 86%，顶部不再需要状态栏内边距；底部由内容自己 navigationBarsPadding。
        contentWindowInsets = { WindowInsets(0, 0, 0, 0) },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .sheetMaxHeight()
                .navigationBarsPadding()
                .padding(bottom = ProferSpacing.md),
        ) {
            Column(
                modifier = Modifier.padding(horizontal = ProferSpacing.xl, vertical = ProferSpacing.xs),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = "Chat 工具",
                    style = ProferHeadingSerif,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = "开关与电脑端共享；标为「未配置」的工具需要先在电脑端填好凭据。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (tools.isEmpty()) {
                Text(
                    text = "还没有可用的 Chat 工具。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = ProferSpacing.xl, vertical = ProferSpacing.xl),
                )
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = false),
                ) {
                    items(tools, key = { it.id }) { tool ->
                        ChatToolRow(tool = tool, onToggle = onToggle)
                    }
                }
            }
        }
    }
}

@Composable
private fun ChatToolRow(
    tool: ChatToolSummary,
    onToggle: (String, Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .padding(horizontal = ProferSpacing.xl, vertical = ProferSpacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ProferSpacing.md),
    ) {
        Icon(
            imageVector = toolIcon(tool.icon),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            modifier = Modifier.size(18.dp),
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = tool.displayName,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val subtitle = if (tool.available) tool.description else "未配置 · ${tool.description}"
            if (subtitle.isNotBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Switch(
            checked = tool.enabled,
            onCheckedChange = { onToggle(tool.id, it) },
            enabled = tool.available,
            colors = SwitchDefaults.colors(
                checkedThumbColor = MaterialTheme.colorScheme.onTertiary,
                checkedTrackColor = MaterialTheme.colorScheme.tertiary,
                checkedBorderColor = androidx.compose.ui.graphics.Color.Transparent,
                uncheckedThumbColor = MaterialTheme.colorScheme.onSurfaceVariant,
                uncheckedTrackColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                uncheckedBorderColor = MaterialTheme.colorScheme.outline,
            ),
        )
    }
}

private fun toolIcon(name: String?): ImageVector = when (name) {
    "Brain" -> Icons.Outlined.Psychology
    "Globe" -> Icons.Outlined.Language
    "ImagePlus" -> Icons.Outlined.Image
    else -> Icons.Outlined.Build
}
