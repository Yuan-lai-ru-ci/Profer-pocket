package com.profer.pocket.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.profer.pocket.designsystem.theme.ProferHeadingSerif
import com.profer.pocket.designsystem.component.sheetMaxHeight
import com.profer.pocket.designsystem.theme.ProferSpacing
import com.profer.pocket.model.AgentPresetSummary

/**
 * Agent 预设（岗位）选择。
 *
 * 预设是工作区级配置：内置三档 + 全局预设 + 当前工作区自定义预设。
 * 手机只负责切换，配置本身仍在电脑端「Agent 技能 → 预设」里管理。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PresetPickerSheet(
    presets: List<AgentPresetSummary>,
    currentPresetId: String?,
    onSelect: (AgentPresetSummary) -> Unit,
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
                    text = "选择预设",
                    style = ProferHeadingSerif,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = "预设决定这一轮的工作方式；下一轮消息生效，与电脑端同步。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            val selectable = presets.filter { it.isSelectable }
            if (selectable.isEmpty()) {
                Text(
                    text = "这个项目还没有可用预设。",
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
                    selectable.groupBy { it.groupLabel }.forEach { (groupLabel, groupPresets) ->
                        item(key = "preset-group-$groupLabel") {
                            Text(
                                text = groupLabel,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(MaterialTheme.colorScheme.surfaceContainerLow)
                                    .padding(
                                        start = ProferSpacing.xl,
                                        end = ProferSpacing.xl,
                                        top = ProferSpacing.md,
                                        bottom = 4.dp,
                                    ),
                            )
                        }
                        items(groupPresets, key = { it.id }) { preset ->
                            PresetRow(
                                preset = preset,
                                selected = preset.id == currentPresetId,
                                onClick = { onSelect(preset) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PresetRow(
    preset: AgentPresetSummary,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .heightIn(min = 56.dp)
            .padding(horizontal = ProferSpacing.xl, vertical = ProferSpacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ProferSpacing.md),
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = preset.displayName,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (preset.description.isNotBlank()) {
                Text(
                    text = preset.description,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            presetTraitLabel(preset)?.let { trait ->
                Text(
                    text = trait,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.62f),
                    maxLines = 1,
                )
            }
        }
        if (selected) {
            Icon(
                imageVector = Icons.Outlined.Check,
                contentDescription = "当前预设",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

/** 预设覆盖的推理强度/权限模式，作为一行轻量说明。 */
private fun presetTraitLabel(preset: AgentPresetSummary): String? {
    val effort = when (preset.effort) {
        "low" -> "低强度"
        "medium" -> "中强度"
        "high" -> "高强度"
        "max" -> "最大强度"
        else -> null
    }
    val permission = when (preset.permissionMode) {
        "auto" -> "自动审批"
        "plan" -> "计划模式"
        "bypassPermissions" -> "完全放行"
        else -> null
    }
    return listOfNotNull(effort, permission).joinToString(" · ").ifBlank { null }
}
