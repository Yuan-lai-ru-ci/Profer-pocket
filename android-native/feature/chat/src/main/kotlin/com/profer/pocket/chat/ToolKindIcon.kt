package com.profer.pocket.chat

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.ui.graphics.vector.ImageVector

/** 工具类型 → 卡片图标；Agent 与 Chat 两条消息链路共用。 */
internal fun ToolKind.icon(): ImageVector = when (this) {
    ToolKind.Read -> Icons.Outlined.Description
    ToolKind.Search -> Icons.Outlined.Search
    ToolKind.Bash -> Icons.Outlined.Terminal
    ToolKind.Edit -> Icons.Outlined.Edit
    ToolKind.Other -> Icons.Outlined.Build
}
