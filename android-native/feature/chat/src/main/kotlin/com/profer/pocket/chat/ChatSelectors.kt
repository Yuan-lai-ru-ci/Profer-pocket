package com.profer.pocket.chat

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.profer.pocket.designsystem.theme.ProferHeadingSerif
import com.profer.pocket.designsystem.component.sheetMaxHeight
import com.profer.pocket.designsystem.theme.ProferSpacing
import com.profer.pocket.designsystem.theme.ProferThemeExtras

/**
 * 模型选择：只列电脑端已启用渠道里的模型。
 * 运行中的会话服务端不允许换模型，这里直接把选项禁用并说明原因。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ModelPickerSheet(
    options: List<ModelOption>,
    currentChannelId: String?,
    currentModelId: String?,
    currentModelLabel: String? = null,
    streaming: Boolean,
    onSelect: (ModelOption) -> Unit,
    onDismiss: () -> Unit,
) {
    // 会话里的 channelId 可能指向已停用/重建过的渠道；以 options 里真实存在的渠道为准。
    val effectiveChannelId = currentChannelId?.takeIf { id ->
        options.any { it.channelId == id && it.modelId == currentModelId }
    } ?: options.firstOrNull {
        it.modelId == currentModelId &&
            (currentModelLabel == null ||
                it.label == currentModelLabel ||
                it.rawLabel == currentModelLabel)
    }?.channelId ?: currentChannelId

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
            SheetHeading(
                title = "选择模型",
                subtitle = "由电脑端的渠道提供；模型与渠道一起切换",
            )
            if (streaming) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = ProferSpacing.xl, vertical = ProferSpacing.xs),
                    shape = MaterialTheme.shapes.small,
                    color = ProferThemeExtras.semantic.warningContainer,
                    border = BorderStroke(
                        1.dp,
                        ProferThemeExtras.semantic.warning.copy(alpha = 0.25f),
                    ),
                ) {
                    Text(
                        text = "正在生成，本轮结束后才能切换模型。",
                        style = MaterialTheme.typography.labelMedium,
                        color = ProferThemeExtras.semantic.onWarningContainer,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
                    )
                }
            }
            if (options.isEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = ProferSpacing.xl, vertical = ProferSpacing.xxl),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(ProferSpacing.xs),
                ) {
                    Text(
                        text = "还没有可用模型",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = "先在电脑端配置并启用一个渠道。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                // 当前渠道排在最前，打开弹层就能看到勾选项，不用先滚动找。
                val orderedGroups = options.groupBy { it.channelId }.entries
                    .sortedByDescending { it.key == effectiveChannelId }
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = false),
                    contentPadding = PaddingValues(
                        start = ProferSpacing.lg,
                        end = ProferSpacing.lg,
                        top = ProferSpacing.xs,
                        bottom = ProferSpacing.xs,
                    ),
                    verticalArrangement = Arrangement.spacedBy(ProferSpacing.sm),
                ) {
                    // 每个渠道是一张卡：组头、模型行和左侧引导线共同表达「这些模型属于这个渠道」。
                    orderedGroups.forEach { (channelId, channelOptions) ->
                        item(key = "channel-$channelId") {
                            ChannelGroupCard(
                                channelLabel = channelOptions.first().channelLabel,
                                options = channelOptions,
                                currentChannelId = effectiveChannelId,
                                currentModelId = currentModelId,
                                currentModelLabel = currentModelLabel,
                                streaming = streaming,
                                onSelect = onSelect,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ChannelGroupCard(
    channelLabel: String,
    options: List<ModelOption>,
    currentChannelId: String?,
    currentModelId: String?,
    currentModelLabel: String?,
    streaming: Boolean,
    onSelect: (ModelOption) -> Unit,
) {
    val shape = RoundedCornerShape(16.dp)
    val railColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.42f)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.9f),
                shape = shape,
            ),
    ) {
        ChannelHeader(
            label = channelLabel,
            count = options.size,
            option = options.first(),
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .drawWithContent {
                    drawContent()
                    // 渠道下的模型用一条左侧引导线串起来，避免组头和模型行看起来是同级。
                    drawLine(
                        color = railColor,
                        start = Offset(x = 25.dp.toPx(), y = 0f),
                        end = Offset(x = 25.dp.toPx(), y = size.height),
                        strokeWidth = 2.dp.toPx(),
                    )
                },
        ) {
            options.forEachIndexed { index, option ->
                if (index > 0) {
                    HorizontalDivider(
                        modifier = Modifier.padding(start = 42.dp, end = 14.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f),
                    )
                }
                ModelRow(
                    option = option,
                    selected = option.isCurrent(
                        currentChannelId = currentChannelId,
                        currentModelId = currentModelId,
                        currentModelLabel = currentModelLabel,
                    ),
                    enabled = !streaming,
                    onClick = { if (!streaming) onSelect(option) },
                )
            }
        }
    }
}

@Composable
private fun ModelIcon(option: ModelOption, size: Dp) {
    // 有深色专用图标时直接铺满；没有时给透明字形一个浅底，避免在黑底上消失。
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val darkRes = if (dark) ModelLogos.darkVariantFor(option) else null
    Box(
        modifier = Modifier
            .size(size)
            .clip(RoundedCornerShape(if (size >= 26.dp) 8.dp else 7.dp))
            .background(
                if (dark && darkRes == null) Color(0xFFF2EEE6) else Color.Transparent,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            painter = painterResource(darkRes ?: ModelLogos.drawableFor(option, false)),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(size - 2.dp)
                .clip(RoundedCornerShape(6.dp)),
        )
    }
}

@Composable
private fun ChannelHeader(label: String, count: Int, option: ModelOption) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 14.dp, end = 14.dp, top = 10.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        ModelIcon(option = option, size = 26.dp)
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = "$count 个模型",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
        )
    }
}

/**
 * 当前模型判定：渠道 id 缺失时（部分 Agent 会话只带 modelId）退回短名匹配，
 * 避免胶囊显示着模型、弹层里却没有勾选。
 */
private fun ModelOption.isCurrent(
    currentChannelId: String?,
    currentModelId: String?,
    currentModelLabel: String?,
): Boolean {
    if (currentChannelId != null) {
        return channelId == currentChannelId && modelId == currentModelId
    }
    // 部分 Agent 会话只带展示名/短 id，渠道和完整 modelId 都对不上；此时退回短名匹配。
    if (currentModelId != null && modelId == currentModelId) return true
    if (currentModelLabel == null) return false
    val shortId = modelId.substringAfterLast('/').substringAfterLast(':')
    return label == currentModelLabel || rawLabel == currentModelLabel || shortId == currentModelLabel
}

@Composable
private fun ModelRow(
    option: ModelOption,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                if (selected) {
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.055f)
                } else {
                    androidx.compose.ui.graphics.Color.Transparent
                },
            )
            .clickable(enabled = enabled, onClick = onClick)
            .heightIn(min = 50.dp)
            .padding(start = 42.dp, top = 8.dp, end = 14.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ProferSpacing.md),
    ) {
        ModelIcon(option = option, size = 24.dp)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = option.label,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                ),
                color = MaterialTheme.colorScheme.onSurface.copy(
                    alpha = if (enabled) 1f else 0.5f,
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            // 组内不再重复渠道名；只有短名去重后与原始 id 不同时，才补一行原始名。
            val rawLabel = option.rawLabel.takeIf { it.isNotBlank() && it != option.label }
            if (rawLabel != null) {
                Text(
                    text = rawLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(
                        alpha = if (enabled) 0.72f else 0.4f,
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (selected) {
            Icon(
                imageVector = Icons.Outlined.Check,
                contentDescription = "当前模型",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

private data class PermissionModeOption(
    val value: String,
    val label: String,
    val description: String,
)

private val PermissionModeOptions = listOf(
    PermissionModeOption(
        value = "auto",
        label = "自动",
        description = "常规操作自动执行，关键步骤再确认",
    ),
    PermissionModeOption(
        value = "plan",
        label = "计划",
        description = "先给出计划，确认后再动手",
    ),
    PermissionModeOption(
        value = "bypassPermissions",
        label = "放行",
        description = "不再逐条确认，适合可信任务",
    ),
)

/**
 * 权限模式选择用居中对话框，而不是底部弹层：
 * 弹层从底部升起时，打开它的那一下可能落在「放行」行上，造成误选。
 */
@Composable
internal fun PermissionModeDialog(
    currentMode: String?,
    currentLabel: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = MaterialTheme.shapes.large,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        title = {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = "权限模式",
                    style = ProferHeadingSerif,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = "当前：$currentLabel",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                )
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                PermissionModeOptions.forEach { option ->
                    val selected = option.value == currentMode
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(MaterialTheme.shapes.small)
                            .clickable { onSelect(option.value) }
                            .heightIn(min = 56.dp)
                            .padding(horizontal = 8.dp, vertical = ProferSpacing.sm),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(ProferSpacing.md),
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = option.label,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                text = option.description,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (selected) {
                            Icon(
                                imageVector = Icons.Outlined.Check,
                                contentDescription = "当前模式",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                }
                Text(
                    text = if (currentMode == null) {
                        "当前是会话默认（执行）；选择后会在电脑端同步。"
                    } else {
                        "切换会同步到电脑端；运行中也可以改。"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    modifier = Modifier.padding(start = 8.dp, top = ProferSpacing.xs),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        },
    )
}

@Composable
private fun SheetHeading(
    title: String,
    subtitle: String,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = ProferSpacing.xl, vertical = ProferSpacing.sm),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = title,
            style = ProferHeadingSerif,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = subtitle,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
        )
    }
}
