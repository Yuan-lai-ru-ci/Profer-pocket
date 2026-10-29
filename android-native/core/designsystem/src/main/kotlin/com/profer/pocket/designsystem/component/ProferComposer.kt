package com.profer.pocket.designsystem.component

import android.content.res.Configuration
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.WorkOutline
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
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.profer.pocket.designsystem.theme.ProferSizes
import com.profer.pocket.designsystem.theme.ProferSpacing
import com.profer.pocket.designsystem.theme.ProferTheme

/**
 * 输入区：上方一行上下文入口（模型 / 岗位预设 / 权限档位），下方一条浮起的胶囊输入条。
 *
 * 视觉按「浮起的一条」而不是「描边的一个框」做：画布 #F1F0EE 上再用 1dp 描边分隔，
 * 会和上方入口的描边叠成好几层线框，越看越脏。改成最亮面 + 2dp 投影；
 * 深色里投影只会发脏，所以深色改成纯色调分层（surfaceContainer），不加投影。
 * 圆角走 MaterialTheme.shapes.extraLarge（28dp），组件里不写魔法数字。
 *
 * 底部只留一块实心面：上方三个入口不画底色，按下时用 ripple 反馈。
 * 四块色块叠在一起是底部“重”的主因，靠半透明解决不了（见 README）。
 *
 * 只保留输入与发送/停止：模型、预设、权限交给上方入口，底部不再堆第二排。
 * 流式时发送键原位变成停止键，既不新增行高，也不会误发。
 *
 * 尺寸：整条最小高度 52dp（[ProferSizes.composerMinHeight]），左右图标保持 48dp 热区，
 * 内部可见图形缩到 22dp / 40dp——热区不变大，视觉变轻。
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
    modifier: Modifier = Modifier,
    /** 当前模型品牌图标；为空时回退到闪电图标。 */
    modelIconRes: Int? = null,
    /** Agent 预设（岗位）：Chat 模式传 null 不显示。 */
    presetLabel: String? = null,
    onPresetClick: (() -> Unit)? = null,
    /** Chat 工具选择：仅 Chat 模式传入。 */
    onToolsClick: (() -> Unit)? = null,
    toolsActive: Boolean = false,
    onAttach: (() -> Unit)? = null,
    placeholder: String = "给 Profer 发消息…",
    focusRequester: FocusRequester? = null,
    /** Chat 模式没有权限档位，隐藏模式选择器但保留模型选择。 */
    showSelectors: Boolean = true,
    /** 是否显示权限/档位选择器；Chat 只切换模型时传 false。 */
    showModeSelector: Boolean = true,
) {
    // 深色判断跟着主题走，不用 isSystemInDarkTheme：设置里可以手动指定主题。
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f

    Column(
        modifier = modifier
            .fillMaxWidth()
            .imePadding()
            .navigationBarsPadding()
            // 与消息列表同一档左右留白（16dp），胶囊和气泡左右对齐。
            .padding(horizontal = ProferSpacing.lg)
            .padding(top = ProferSpacing.xs, bottom = ProferSpacing.sm),
        verticalArrangement = Arrangement.spacedBy(ProferSpacing.xxs),
    ) {
        if (showSelectors) {
            SelectorRow(
                modelLabel = modelLabel,
                modelIconRes = modelIconRes,
                onModelClick = onModelClick,
                presetLabel = presetLabel,
                onPresetClick = onPresetClick,
                modeLabel = modeLabel,
                onModeClick = onModeClick,
                showModeSelector = showModeSelector,
            )
        }

        ComposerField(
            value = value,
            onValueChange = onValueChange,
            onSend = onSend,
            onStop = onStop,
            streaming = streaming,
            onAttach = onAttach,
            onToolsClick = onToolsClick,
            toolsActive = toolsActive,
            placeholder = placeholder,
            focusRequester = focusRequester,
            dark = dark,
        )
    }
}

/**
 * 上下文胶囊行：模型、岗位预设、权限档位。
 *
 * 宽度分配用 weight 声明比例，不再按 fontScale 手算像素上限——
 * 放不下就省略号，本来就不该换行；手算反而在大字号下把模型名压得比预设还短。
 * 权限档位不参与收缩：它是安全相关的，必须一直完整可见。
 */
@Composable
private fun SelectorRow(
    modelLabel: String,
    modelIconRes: Int?,
    onModelClick: () -> Unit,
    presetLabel: String?,
    onPresetClick: (() -> Unit)?,
    modeLabel: String,
    onModeClick: () -> Unit,
    showModeSelector: Boolean,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ComposerChip(
            text = modelLabel,
            icon = Icons.Outlined.Bolt,
            iconRes = modelIconRes,
            onClick = onModelClick,
            modifier = Modifier.weight(if (presetLabel != null) 0.9f else 1f, fill = false),
        )
        if (presetLabel != null && onPresetClick != null) {
            ComposerChip(
                text = presetLabel,
                icon = Icons.Outlined.WorkOutline,
                onClick = onPresetClick,
                modifier = Modifier.weight(1.4f, fill = false),
            )
        }
        if (showModeSelector) {
            ComposerChip(
                text = modeLabel,
                icon = Icons.Outlined.Tune,
                onClick = onModeClick,
                modifier = Modifier.widthIn(max = 112.dp),
            )
        }
    }
}

/**
 * 轻量选择器：**不画底色**，直接坐在画布上，只靠图标 + 文字 + 下拉箭头表达可点。
 *
 * 原来三颗胶囊各一块填充色，加上输入条就是四块色块叠在底部，
 * 底部一看就“重”。半透明在背后没内容时等于把颜色调浅（见提交说明），
 * 所以直接去掉底，按下时用 ripple 反馈。
 *
 * 热区仍为 48dp（[ProferSizes.minTouchTarget]），可见内容约 24dp 高：
 * 手指接触面 7–10mm ≈ 45–64dp，热区不能跟着视觉缩。
 */
@Composable
private fun ComposerChip(
    text: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    iconRes: Int? = null,
) {
    Box(
        modifier = modifier
            .height(ProferSizes.minTouchTarget)
            .clip(CircleShape)
            // 标签和点击动作放在同一语义节点：读屏焦点不会落到没有点击动作的文字子节点。
            .semantics { contentDescription = text }
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
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
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                    modifier = Modifier.size(14.dp),
                )
            }
            Text(
                text = text,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Icon(
                imageVector = Icons.Outlined.ExpandMore,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f),
                modifier = Modifier.size(14.dp),
            )
        }
    }
}

/** 输入条本体：浮起的一条，左附件、中文本、右发送/停止。 */
@Composable
private fun ComposerField(
    value: String,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    streaming: Boolean,
    onAttach: (() -> Unit)?,
    onToolsClick: (() -> Unit)?,
    toolsActive: Boolean,
    placeholder: String,
    focusRequester: FocusRequester?,
    dark: Boolean,
) {
    val canSend = value.isNotBlank()
    val actionEnabled = streaming || canSend

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        color = if (dark) {
            MaterialTheme.colorScheme.surfaceContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerLowest
        },
        // 深色下投影不可见，只会让边缘发脏：靠色阶分层，不叠阴影。
        shadowElevation = if (dark) 0.dp else 2.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp),
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
                    .then(
                        if (focusRequester != null) {
                            Modifier.focusRequester(focusRequester)
                        } else {
                            Modifier
                        },
                    )
                    // 上下 14dp + 单行字高 24dp = 52dp，与 composerMinHeight 对齐。
                    .padding(horizontal = 4.dp, vertical = 14.dp),
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
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        innerTextField()
                    }
                },
            )
            SendAction(
                streaming = streaming,
                canSend = canSend,
                enabled = actionEnabled,
                onSend = onSend,
                onStop = onStop,
            )
        }
    }
}

/**
 * 发送 / 停止：48dp 热区里放 40dp 圆。
 * 空输入时退成浅色圆，不消失——位置稳定，用户不用找按钮。
 */
@Composable
private fun SendAction(
    streaming: Boolean,
    canSend: Boolean,
    enabled: Boolean,
    onSend: () -> Unit,
    onStop: () -> Unit,
) {
    val active = streaming || canSend
    Box(
        modifier = Modifier
            .size(ProferSizes.minTouchTarget)
            .clip(CircleShape)
            .clickable(enabled = enabled, role = Role.Button) {
                if (streaming) onStop() else onSend()
            }
            // 标签跟点击动作放同一节点：读屏焦点不会落到 20dp 的图标上。
            .semantics { contentDescription = if (streaming) "停止" else "发送" },
        contentAlignment = Alignment.Center,
    ) {
        // 空输入时不画圆：一块灰圆盘会和胶囊抢焦点，而且看起来像已经能按。
        // 只留一枚淡箭头，位置不变，有字时再浮出实心圆。
        if (!active) {
            Icon(
                imageVector = Icons.Outlined.ArrowUpward,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f),
                modifier = Modifier.size(22.dp),
            )
        } else {
            Surface(
                modifier = Modifier.size(40.dp),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primary,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = if (streaming) {
                            Icons.Outlined.Stop
                        } else {
                            Icons.Outlined.ArrowUpward
                        },
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }
    }
}

// region 预览

@Composable
private fun ComposerPreview(
    value: String,
    streaming: Boolean = false,
    presetLabel: String? = "移动版 UI 开发（Android）",
    showModeSelector: Boolean = true,
) {
    Surface(color = MaterialTheme.colorScheme.background) {
        ProferComposer(
            value = value,
            onValueChange = {},
            onSend = {},
            onStop = {},
            streaming = streaming,
            modelLabel = "Opus 4.5",
            modeLabel = "自动",
            onModelClick = {},
            onModeClick = {},
            presetLabel = presetLabel,
            onPresetClick = {},
            onAttach = {},
            placeholder = "给 Profer 发消息…",
            showModeSelector = showModeSelector,
        )
    }
}

@Preview(name = "输入条 · 浅色 空", showBackground = true, widthDp = 360)
@Composable
private fun ComposerLightEmptyPreview() {
    ProferTheme(darkTheme = false) { ComposerPreview(value = "") }
}

@Preview(name = "输入条 · 浅色 有字", showBackground = true, widthDp = 360)
@Composable
private fun ComposerLightTypedPreview() {
    ProferTheme(darkTheme = false) { ComposerPreview(value = "把输入区重写成浮起的胶囊") }
}

@Preview(
    name = "输入条 · 深色 流式",
    showBackground = true,
    widthDp = 360,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun ComposerDarkStreamingPreview() {
    ProferTheme(darkTheme = true) { ComposerPreview(value = "", streaming = true) }
}

@Preview(
    name = "输入条 · 大字号 2.0",
    showBackground = true,
    widthDp = 360,
    fontScale = 2f,
)
@Composable
private fun ComposerLargeFontPreview() {
    ProferTheme(darkTheme = false) {
        ComposerPreview(
            value = "字号放大到 2.0 倍时，预设名先被省略号截断，模型与权限保持完整",
            presetLabel = "移动版连接开发（Android）",
        )
    }
}

@Preview(name = "输入条 · 多行 5 行", showBackground = true, widthDp = 360)
@Composable
private fun ComposerMultilinePreview() {
    ProferTheme(darkTheme = false) {
        ComposerPreview(
            value = "第一行：检查输入区在五行文本时圆角是否还是胶囊\n" +
                "第二行：检查换行后左右图标是否垂直居中\n" +
                "第三行：检查发送键是否被撑高\n" +
                "第四行：检查占位符是否只在空输入时出现\n" +
                "第五行：检查超出后是否内部滚动",
        )
    }
}

@Preview(name = "输入条 · Chat 模式无预设", showBackground = true, widthDp = 360)
@Composable
private fun ComposerChatModePreview() {
    ProferTheme(darkTheme = false) {
        ComposerPreview(value = "", presetLabel = null, showModeSelector = false)
    }
}

// endregion
