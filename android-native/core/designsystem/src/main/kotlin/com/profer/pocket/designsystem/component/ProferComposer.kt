package com.profer.pocket.designsystem.component

import android.content.res.Configuration
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.WorkOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import com.profer.pocket.designsystem.component.sheetMaxHeight
import com.profer.pocket.designsystem.theme.ProferHeadingSerif
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material3.HorizontalDivider

/**
 * 输入区：一条浮起的胶囊输入条，上下文入口（模型 / 预设 / 权限）收进左侧「+」菜单。
 *
 * 之前模型、预设、权限三颗 chip 单独浮在输入条上方：既多占一行，又和胶囊脱节。
 * 现在输入条保持 dsh-go 的 56dp 单行几何，点「+」弹出菜单，附件与三个选择器都在里面。
 *
 * 视觉按「浮起的一条」而不是「描边的一个框」做：浅色用最亮面 + 2dp 投影，
 * 深色不投影（投影在深色只会发脏），靠 surfaceContainer 色阶分层。
 * 圆角走 MaterialTheme.shapes.extraLarge（28dp），组件里不写魔法数字。
 *
 * 流式时发送键原位变成停止键，既不新增行高，也不会误发。
 *
 * 尺寸：单行 8 + 40 + 8 = 56dp，两端圆键 40dp、四周内缩 8dp，圆角 28 = 键半径 20 + 8。
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
            modelLabel = modelLabel,
            modelIconRes = modelIconRes,
            onModelClick = onModelClick,
            presetLabel = presetLabel,
            onPresetClick = onPresetClick,
            modeLabel = modeLabel,
            onModeClick = onModeClick,
            showSelectors = showSelectors,
            showModeSelector = showModeSelector,
        )
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
    modelLabel: String,
    modelIconRes: Int?,
    onModelClick: () -> Unit,
    presetLabel: String?,
    onPresetClick: (() -> Unit)?,
    modeLabel: String,
    onModeClick: () -> Unit,
    showSelectors: Boolean,
    showModeSelector: Boolean,
) {
    val canSend = value.isNotBlank()
    val active = streaming || canSend

    // dsh-go 的输入条几何：40dp 圆键做胶囊两端，四周 8dp 内缩；
    // 单行时 8 + 40 + 8 = 56，圆角 28 = 键半径 20 + 8，键与胶囊两端同心。
    val keySize = 40.dp
    val keyGap = 8.dp

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        color = proferFloatingSurface(),
        // 浮起靠软投影 + 极淡边线；深色不投影，靠色阶分层。
        shadowElevation = if (dark) 0.dp else 2.dp,
        border = androidx.compose.foundation.BorderStroke(
            ProferSizes.hairline,
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f),
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(keyGap),
            verticalAlignment = Alignment.Bottom,
        ) {
            ComposerMenuButton(
                modelLabel = modelLabel,
                modelIconRes = modelIconRes,
                onModelClick = onModelClick,
                presetLabel = presetLabel,
                onPresetClick = onPresetClick,
                modeLabel = modeLabel,
                onModeClick = onModeClick,
                showSelectors = showSelectors,
                showModeSelector = showModeSelector,
                onAttach = onAttach,
                onToolsClick = onToolsClick,
                toolsActive = toolsActive,
            )
            Spacer(Modifier.width(keyGap))
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
                    .padding(horizontal = 4.dp),
                textStyle = MaterialTheme.typography.bodyLarge.copy(
                    color = MaterialTheme.colorScheme.onSurface,
                ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                maxLines = 6,
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Sentences,
                    keyboardType = KeyboardType.Text,
                    imeAction = ImeAction.Default,
                ),
                decorationBox = { innerTextField ->
                    // 文字和两端圆键共用 40dp 的中线：单行时容器正好 40dp，
                    // 多行时容器随内容长高，Row 底部对齐让圆键继续贴底。
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = keySize),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        if (value.isEmpty()) {
                            Text(
                                text = placeholder,
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                // 光标画在文本起点；不错开 2dp 会和占位符首字叠在一起。
                                modifier = Modifier.padding(start = 2.dp),
                            )
                        }
                        innerTextField()
                    }
                },
            )
            Spacer(Modifier.width(keyGap))
            SendAction(
                streaming = streaming,
                active = active,
                onSend = onSend,
                onStop = onStop,
            )
        }
    }
}

/**
 * 左侧「+」：附件、工具与三个上下文选择器都收进这个菜单。
 *
 * 选择器原来单独占输入条上方一行，既多一层浮块，又和胶囊脱节；
 * 收进菜单后输入条回到单行 56dp，模型/预设/权限仍是一次点击可达。
 */
@Composable
private fun ComposerMenuButton(
    modelLabel: String,
    modelIconRes: Int?,
    onModelClick: () -> Unit,
    presetLabel: String?,
    onPresetClick: (() -> Unit)?,
    modeLabel: String,
    onModeClick: () -> Unit,
    showSelectors: Boolean,
    showModeSelector: Boolean,
    onAttach: (() -> Unit)?,
    onToolsClick: (() -> Unit)?,
    toolsActive: Boolean,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        ComposerKey(
            icon = Icons.Outlined.Add,
            contentDescription = "更多选项",
            onClick = { open = true },
        )
        if (open) {
            ComposerOptionsSheet(
                modelLabel = modelLabel,
                modelIconRes = modelIconRes,
                onModelClick = { open = false; onModelClick() },
                presetLabel = presetLabel,
                onPresetClick = onPresetClick?.let { click -> { open = false; click() } },
                modeLabel = modeLabel,
                onModeClick = { open = false; onModeClick() },
                showSelectors = showSelectors,
                showModeSelector = showModeSelector,
                onAttach = onAttach?.let { attach -> { open = false; attach() } },
                onToolsClick = onToolsClick?.let { tools -> { open = false; tools() } },
                toolsActive = toolsActive,
                onDismiss = { open = false },
            )
        }
    }
}

/**
 * 「+」弹出的底部弹层：附件与三个上下文选择器。
 *
 * 样式跟「选择预设 / 选择模型」一致（衬线标题 + 一行说明 + 分组行），
 * 不再用贴着「+」的小下拉——下拉在屏幕底部既容易挡住输入区，也不成体系。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ComposerOptionsSheet(
    modelLabel: String,
    modelIconRes: Int?,
    onModelClick: () -> Unit,
    presetLabel: String?,
    onPresetClick: (() -> Unit)?,
    modeLabel: String,
    onModeClick: () -> Unit,
    showSelectors: Boolean,
    showModeSelector: Boolean,
    onAttach: (() -> Unit)?,
    onToolsClick: (() -> Unit)?,
    toolsActive: Boolean,
    onDismiss: () -> Unit,
) {
    ProferAdaptiveSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .sheetMaxHeight()
                .navigationBarsPadding()
                .padding(bottom = ProferSpacing.md),
        ) {
            // 不放标题和说明：这一层只是动作与当前值，多说一句都是噪音。
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = ProferSpacing.xl, vertical = ProferSpacing.md),
                horizontalArrangement = Arrangement.spacedBy(ProferSpacing.lg),
            ) {
                if (onAttach != null) {
                    ComposerActionTile(
                        icon = Icons.Outlined.PhotoLibrary,
                        label = "相册",
                        container = MaterialTheme.colorScheme.primaryContainer,
                        content = MaterialTheme.colorScheme.onPrimaryContainer,
                        onClick = onAttach,
                    )
                }
                if (onToolsClick != null) {
                    ComposerActionTile(
                        icon = Icons.Outlined.Build,
                        label = "工具",
                        container = MaterialTheme.colorScheme.secondaryContainer,
                        content = MaterialTheme.colorScheme.onSecondaryContainer,
                        onClick = onToolsClick,
                        badge = if (toolsActive) "已启用" else null,
                    )
                }
            }

            if (showSelectors) {
                ComposerSheetSection("本轮")
                Surface(
                    shape = MaterialTheme.shapes.extraLarge,
                    // 深色里 surfaceContainerLowest 比弹层更暗，会看成被挖掉一块；跟着主题取浮层色。
                    color = proferFloatingSurface(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = ProferSpacing.lg),
                ) {
                    Column {
                        ComposerSheetRow(Icons.Outlined.Bolt, "模型", modelLabel, onModelClick, iconRes = modelIconRes)
                        if (presetLabel != null && onPresetClick != null) {
                            ComposerSheetRow(Icons.Outlined.WorkOutline, "预设", presetLabel, onPresetClick, divider = true)
                        }
                        if (showModeSelector) {
                            ComposerSheetRow(Icons.Outlined.Tune, "权限", modeLabel, onModeClick, divider = true)
                        }
                    }
                }
            }
        }
    }
}

/** 动作方块：软色底 + 图标，标签在下方；和参考稿的相册/拍照/文件同一种形体。 */
@Composable
private fun ComposerActionTile(
    icon: ImageVector,
    label: String,
    container: Color,
    content: Color,
    onClick: () -> Unit,
    badge: String? = null,
) {
    Column(
        modifier = Modifier
            .width(76.dp)
            .clip(MaterialTheme.shapes.large)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(vertical = ProferSpacing.xs),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(ProferSpacing.xs),
    ) {
        Box(
            modifier = Modifier
                .size(60.dp)
                .clip(MaterialTheme.shapes.large)
                .background(container),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = content,
                modifier = Modifier.size(26.dp),
            )
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
        )
        if (badge != null) {
            Text(
                text = badge,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
            )
        }
    }
}

/** 分组标题，和「选择预设」里的分组标签同一套。 */
@Composable
private fun ComposerSheetSection(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                start = ProferSpacing.xl,
                end = ProferSpacing.xl,
                top = ProferSpacing.md,
                bottom = 4.dp,
            ),
    )
}

/** 弹层行：图标 + 标签 + 当前值 + chevron，整行 56dp 可点。 */
@Composable
private fun ComposerSheetRow(
    icon: ImageVector,
    label: String,
    value: String?,
    onClick: () -> Unit,
    iconRes: Int? = null,
    chevron: Boolean = true,
    divider: Boolean = false,
) {
    Column {
        if (divider) {
            HorizontalDivider(
                modifier = Modifier.padding(start = 52.dp),
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
            )
        }
        Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick)
            .heightIn(min = 56.dp)
            .padding(horizontal = ProferSpacing.md, vertical = ProferSpacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ProferSpacing.md),
    ) {
        if (iconRes != null) {
            Image(
                painter = painterResource(iconRes),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(20.dp)
                    .clip(RoundedCornerShape(5.dp)),
            )
        } else {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        }
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            modifier = Modifier.weight(1f),
        )
        if (value != null) {
            Text(
                text = value,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 168.dp),
            )
        }
        if (chevron) {
            Icon(
                imageVector = Icons.Outlined.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                modifier = Modifier.size(18.dp),
            )
        }
        }
    }
}

/** 端键：40dp 圆，淡底，图标 22dp。和发送键同一尺寸、同一位置。 */
@Composable
private fun ComposerKey(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f))
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(22.dp),
        )
    }
}

/**
 * 发送 / 停止：40dp 圆始终存在。
 * 空输入是淡底 + 38% 箭头，有字或运行中变实心主色——位置不变，用户不用找。
 */
@Composable
private fun SendAction(
    streaming: Boolean,
    active: Boolean,
    onSend: () -> Unit,
    onStop: () -> Unit,
) {
    val container = if (active) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
    }
    val content = if (active) {
        MaterialTheme.colorScheme.onPrimary
    } else {
        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
    }
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(container)
            .clickable(enabled = active, role = Role.Button) {
                if (streaming) onStop() else onSend()
            }
            .semantics { contentDescription = if (streaming) "停止" else "发送" },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = if (streaming) Icons.Outlined.Stop else Icons.Outlined.ArrowUpward,
            contentDescription = null,
            tint = content,
            modifier = Modifier.size(if (streaming) 20.dp else 22.dp),
        )
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
