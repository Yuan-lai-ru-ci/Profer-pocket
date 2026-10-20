package com.profer.pocket.designsystem.component

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.WorkOutline
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.profer.pocket.designsystem.theme.ProferSizes
import com.profer.pocket.designsystem.theme.ProferSpacing

/**
 * 输入区：1–5 行自适应，键盘弹起只顶一次（imePadding）。
 *
 * 只保留输入与发送/停止：模型、模式等会话元信息交给顶栏，底部不再堆一行胶囊。
 * 流式时发送键原位变成停止键，既不新增行高，也不会误发。
 */
@Composable
fun ProferComposer(
    value: String,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    streaming: Boolean,
    modelLabel: String,
    modeLabel: String,
    onModelClick: () -> Unit,
    onModeClick: () -> Unit,
    /** 当前模型品牌图标；为空时回退到闪电图标。 */
    modelIconRes: Int? = null,
    /** Agent 预设（岗位）：Chat 模式传 null 不显示。 */
    presetLabel: String? = null,
    onPresetClick: (() -> Unit)? = null,
    /** Chat 工具选择：仅 Chat 模式传入。 */
    onToolsClick: (() -> Unit)? = null,
    toolsActive: Boolean = false,
    modifier: Modifier = Modifier,
    onAttach: (() -> Unit)? = null,
    placeholder: String = "给 Profer 发消息…",
    focusRequester: FocusRequester? = null,
    /** Chat 模式没有权限档位，隐藏模式选择器但保留模型选择。 */
    showSelectors: Boolean = true,
    /** 是否显示权限/档位选择器；Chat 只切换模型时传 false。 */
    showModeSelector: Boolean = true,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .imePadding()
            .navigationBarsPadding()
            .padding(horizontal = ProferSpacing.md)
            .padding(top = ProferSpacing.xxs, bottom = ProferSpacing.xs),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        if (showSelectors) {
            // 永远一行：放不下就省略文字，绝不换行。
            // 预设名可能很长（「移动版连接开发（Android）」），设成弹性项，
            // 先压缩它、再省略；模型与权限胶囊保持完整。
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 2.dp),
            ) {
                // 模型名最多占 45% 行宽；字号放大时同样的字符更宽，所以按 fontScale 折算，
                // 保证预设胶囊始终留得下一块可点区域。
                val fontScale = LocalDensity.current.fontScale.coerceAtLeast(1f)
                val modelLabelMax = if (presetLabel != null) {
                    (maxWidth * 0.45f).coerceAtMost(240.dp) / fontScale
                } else {
                    240.dp
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ComposerSelector(
                        text = modelLabel,
                        icon = Icons.Outlined.Bolt,
                        iconRes = modelIconRes,
                        onClick = onModelClick,
                        labelMaxWidth = modelLabelMax,
                    )
                    if (presetLabel != null && onPresetClick != null) {
                        ComposerSelector(
                            text = presetLabel,
                            icon = Icons.Outlined.WorkOutline,
                            onClick = onPresetClick,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                    }
                    if (showModeSelector) {
                        ComposerSelector(
                            text = modeLabel,
                            icon = Icons.Outlined.Tune,
                            onClick = onModeClick,
                        )
                    }
                }
            }
        }

        Surface(
            shape = RoundedCornerShape(26.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            border = BorderStroke(
                1.dp,
                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.85f),
            ),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (onAttach != null) {
                    ProferIconAction(
                        icon = Icons.Outlined.Add,
                        contentDescription = "添加附件",
                        onClick = onAttach,
                    )
                }
                if (onToolsClick != null) {
                    ProferIconAction(
                        icon = Icons.Outlined.Build,
                        contentDescription = "选择工具",
                        onClick = onToolsClick,
                        tint = if (toolsActive) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 38.dp)
                        .then(
                            if (focusRequester != null) {
                                Modifier.focusRequester(focusRequester)
                            } else {
                                Modifier
                            },
                        )
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    textStyle = MaterialTheme.typography.bodyLarge.copy(
                        color = MaterialTheme.colorScheme.onSurface,
                    ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    maxLines = 5,
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Sentences,
                        keyboardType = KeyboardType.Text,
                        imeAction = ImeAction.Default,
                    ),
                    decorationBox = { innerTextField ->
                        Box {
                            if (value.isEmpty()) {
                                Text(
                                    text = placeholder,
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            innerTextField()
                        }
                    },
                )
                val canSend = value.isNotBlank()
                val actionEnabled = streaming || canSend
                // 48dp 热区，内部 40dp 圆：与输入行等高，避免撑高输入框。
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .clickable(enabled = actionEnabled) {
                            if (streaming) onStop() else onSend()
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Surface(
                        modifier = Modifier.size(40.dp),
                        shape = CircleShape,
                        color = when {
                            streaming -> MaterialTheme.colorScheme.surfaceContainerHigh
                            canSend -> MaterialTheme.colorScheme.primary
                            else -> MaterialTheme.colorScheme.surfaceContainerHigh
                        },
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = if (streaming) {
                                    Icons.Outlined.Stop
                                } else {
                                    Icons.Outlined.ArrowUpward
                                },
                                contentDescription = if (streaming) "停止" else "发送",
                                tint = when {
                                    streaming -> MaterialTheme.colorScheme.onSurface
                                    canSend -> MaterialTheme.colorScheme.onPrimary
                                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                                },
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * 发送栏的轻量选择器：胶囊视觉保持 36dp 左右，热区撑到 48dp。
 * 只显示当前值，点开由 ChatScreen 的底部弹层完成选择。
 */
@Composable
private fun ComposerSelector(
    text: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
    iconRes: Int? = null,
    modifier: Modifier = Modifier,
    labelMaxWidth: Dp = 240.dp,
) {
    BoxWithConstraints(
        modifier = modifier
            .height(ProferSizes.minTouchTarget)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        // 空间被挤到放不下文字时，退化成「图标 + 下拉箭头」的紧凑胶囊，
        // 而不是留一个只剩图标的空壳。
        val compact = maxWidth < 72.dp
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            border = BorderStroke(
                1.dp,
                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f),
            ),
        ) {
            Row(
                modifier = Modifier.padding(start = 8.dp, end = 5.dp, top = 5.dp, bottom = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (iconRes != null) {
                    Image(
                        painter = painterResource(iconRes),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(15.dp)
                            .clip(RoundedCornerShape(4.dp)),
                    )
                } else {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f),
                        modifier = Modifier.size(12.dp),
                    )
                }
                if (!compact) {
                    Text(
                        text = text,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = labelMaxWidth),
                    )
                }
                Icon(
                    imageVector = Icons.Outlined.ExpandMore,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier.size(12.dp),
                )
            }
        }
    }
}
