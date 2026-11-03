package com.profer.pocket.designsystem.component

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.profer.pocket.designsystem.theme.ProferCodeTextStyle
import com.profer.pocket.designsystem.theme.ProferSizes
import com.profer.pocket.designsystem.theme.ProferSpacing
import com.profer.pocket.designsystem.theme.ProferThemeExtras
import kotlinx.coroutines.delay

/**
 * 用户消息：右对齐、低饱和容器。气泡只承载内容，不加阴影。
 */
@Composable
fun ProferUserMessage(
    text: String,
    modifier: Modifier = Modifier,
    meta: String? = null,
    /** 气泡下方右侧的轻量操作（复制/重发）；正文本身可自由选词。 */
    actions: (@Composable RowScope.() -> Unit)? = null,
    /** 气泡与 meta 之间的附加内容（附件卡等）。 */
    footer: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.End,
    ) {
        Surface(
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.widthIn(max = 320.dp),
        ) {
            // 自由选词：长按正文直接进入系统文本选择，不再被消息菜单抢占。
            SelectionContainer {
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                )
            }
        }
        if (footer != null) {
            footer()
        }
        if (meta != null || actions != null) {
            Row(
                modifier = Modifier.padding(top = 2.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                if (meta != null) {
                    Text(
                        text = meta,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(end = 2.dp),
                    )
                }
                actions?.invoke(this)
            }
        }
    }
}

/**
 * 思考过程：折叠态是一行「思考 · 摘要」，展开后是一段安静的正文。
 * 摘要取正文首行，让人不展开也知道这一步在想什么；不再显示时长，避免和模型档位混淆。
 */
@Composable
fun ProferThinkingCard(
    body: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    /** 折叠行前缀：常态「思考」，流式「思考中」。 */
    label: String = "思考",
    /** 折叠行摘要；调用方可以按尾部窗口自己算，留空则取正文首行。 */
    summary: String? = null,
    /** 展开后的补充说明（如「思考了 12 秒」）；没有就不占位。 */
    meta: String? = null,
) {
    // 思考正文是长会话里最长的字符串：折叠摘要只需尾部/首行，
    // 每次重组都扫一遍全文（trim + 正则替换）会白白拷贝几十 KB。
    val resolved = remember(summary, body) {
        summary?.takeIf { it.isNotBlank() }
            ?: body.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
    }
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = ProferSizes.minTouchTarget)
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .clickable(onClick = onToggle)
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(22.dp)
                    .background(MaterialTheme.colorScheme.tertiaryContainer, RoundedCornerShape(7.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Outlined.Psychology,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onTertiaryContainer,
                    modifier = Modifier.size(14.dp),
                )
            }
            Text(
                text = if (resolved.isBlank()) label else "$label · $resolved",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Icon(
                imageVector = if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                contentDescription = if (expanded) "收起思考" else "展开思考",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
        }
        AnimatedVisibility(
            visible = expanded,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically(),
        ) {
            Column(
                modifier = Modifier.padding(start = 30.dp, end = 8.dp, top = 6.dp, bottom = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                meta?.takeIf { it.isNotBlank() }?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    )
                }
                Text(
                    text = body,
                    style = MaterialTheme.typography.bodySmall.copy(lineHeight = 21.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

enum class ToolStatus { Running, Success, Error, Waiting }

/**
 * 工具调用：折叠态是一行「图标 + 标题 · 目标 + 状态」，展开后按「参数 / 结果 / 错误」分区。
 * 行本身不加边框，靠图标底与间距分组；失败单独用语义容器色成块，不和正常输出混在一起。
 */
@Composable
fun ProferToolCallCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    status: ToolStatus,
    expanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    /** 入参（调用方已截断成可读 JSON）；空则不显示参数分区。 */
    input: String? = null,
    /** 结果摘要；空则不显示结果分区。 */
    output: String? = null,
    /** 失败原因；空则不显示错误分区。 */
    error: String? = null,
) {
    val semantic = ProferThemeExtras.semantic
    val expandable = input != null || output != null || error != null
    val chevronRotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        label = "tool-chevron",
    )
    Column(
        modifier = modifier
            .fillMaxWidth()
            .animateContentSize(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = ProferSizes.minTouchTarget)
                .clip(MaterialTheme.shapes.small)
                // 没有详情可展开时不做可点击：避免点了没反应的死交互。
                .then(if (expandable) Modifier.clickable(onClick = onToggle) else Modifier)
                .padding(vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(26.dp)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(15.dp),
                )
            }
            Text(
                text = if (subtitle.isBlank()) title else "$title · $subtitle",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            ToolStatusIndicator(status = status, semanticSuccess = semantic.success)
            if (expandable) {
                Icon(
                    imageVector = Icons.Outlined.ExpandMore,
                    contentDescription = if (expanded) "收起工具详情" else "展开工具详情",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .size(16.dp)
                        .rotate(chevronRotation),
                )
            }
        }
        AnimatedVisibility(
            visible = expanded && expandable,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically(),
        ) {
            Column(
                modifier = Modifier.padding(start = 36.dp, bottom = 4.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                input?.takeIf { it.isNotBlank() }?.let {
                    ToolDetailSection(title = "参数", body = it, tone = ToolDetailTone.Code)
                }
                output?.takeIf { it.isNotBlank() }?.let {
                    ToolDetailSection(title = "结果", body = it, tone = ToolDetailTone.Code)
                }
                error?.takeIf { it.isNotBlank() }?.let {
                    ToolDetailSection(title = "错误", body = it, tone = ToolDetailTone.Error)
                }
            }
        }
    }
}

private enum class ToolDetailTone { Code, Error }

/** 工具详情分区：标题在上、正文在下；错误用语义容器色，和正常输出分开读。 */
@Composable
private fun ToolDetailSection(title: String, body: String, tone: ToolDetailTone) {
    val error = tone == ToolDetailTone.Error
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        color = if (error) {
            MaterialTheme.colorScheme.errorContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerLow
        },
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelSmall,
                color = if (error) {
                    MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.75f)
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            Text(
                text = body,
                style = ProferCodeTextStyle,
                color = if (error) {
                    MaterialTheme.colorScheme.onErrorContainer
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            )
        }
    }
}

@Composable
private fun ToolStatusIndicator(status: ToolStatus, semanticSuccess: Color) {
    when (status) {
        ToolStatus.Running -> CircularProgressIndicator(
            modifier = Modifier.size(14.dp),
            strokeWidth = 1.6.dp,
            color = MaterialTheme.colorScheme.tertiary,
        )
        ToolStatus.Success -> Icon(
            imageVector = Icons.Outlined.Check,
            contentDescription = "已完成",
            tint = semanticSuccess,
            modifier = Modifier.size(16.dp),
        )
        ToolStatus.Error -> Icon(
            imageVector = Icons.Outlined.ErrorOutline,
            contentDescription = "失败",
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.size(16.dp),
        )
        ToolStatus.Waiting -> ProferStatusDot(
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            size = 6.dp,
        )
    }
}

/** 代码块统一深底：两套主题都保持代码的独立阅读层。 */
@Composable
fun ProferCodeBlock(
    code: String,
    modifier: Modifier = Modifier,
    language: String? = null,
    wrap: Boolean = false,
) {
    val semantic = ProferThemeExtras.semantic
    val clipboard = LocalClipboardManager.current
    var copied by remember(code) { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(1500)
            copied = false
        }
    }
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = semantic.codeBackground,
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (!language.isNullOrBlank()) {
                    Text(
                        text = language.lowercase(),
                        style = MaterialTheme.typography.labelSmall,
                        color = semantic.codeForeground.copy(alpha = 0.55f),
                    )
                }
                Spacer(modifier = Modifier.weight(1f))
                IconButton(
                    onClick = {
                        clipboard.setText(AnnotatedString(code))
                        copied = true
                    },
                    modifier = Modifier.size(com.profer.pocket.designsystem.theme.ProferSizes.minTouchTarget),
                ) {
                    Icon(
                        imageVector = if (copied) Icons.Outlined.Check else Icons.Outlined.ContentCopy,
                        contentDescription = if (copied) "已复制" else "复制代码",
                        tint = semantic.codeForeground.copy(alpha = 0.65f),
                        modifier = Modifier.size(15.dp),
                    )
                }
            }
            if (wrap) {
                Text(
                    text = code.trim('\n'),
                    style = ProferCodeTextStyle,
                    color = semantic.codeForeground,
                )
            } else {
                Box(modifier = Modifier.horizontalScroll(rememberScrollState())) {
                    Text(
                        text = code.trim('\n'),
                        style = ProferCodeTextStyle,
                        color = semantic.codeForeground,
                    )
                }
            }
        }
    }
}

/** 流式输出的光标：一根呼吸竖条，不做弹跳。 */
@Composable
fun ProferStreamingCursor(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "stream-cursor")
    val alpha by transition.animateFloat(
        initialValue = 1f,
        targetValue = 0.2f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 700),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "stream-cursor-alpha",
    )
    Box(
        modifier = modifier
            .padding(top = 4.dp)
            .size(width = 7.dp, height = 15.dp)
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = alpha), RoundedCornerShape(2.dp)),
    )
}

/** 助手消息不使用气泡：长文可读性优先，段落之间靠间距分层。 */
@Composable
fun ProferAssistantBlock(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalArrangement = Arrangement.spacedBy(ProferSpacing.sm),
    ) {
        content()
    }
}

/**
 * 系统回执：居中、低对比、可被扫读但不打断对话。
 * 用于「已允许执行」「已复制」这类不构成一轮对话的事实。
 */
@Composable
fun ProferNotice(
    text: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
    ) {
        Surface(
            shape = RoundedCornerShape(50),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                if (icon != null) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = tint,
                        modifier = Modifier.size(13.dp),
                    )
                }
                Text(
                    text = text,
                    style = MaterialTheme.typography.labelSmall,
                    color = tint,
                )
            }
        }
    }
}

/** 运行状态说明条：列表底部的一行小字，不抢注意力。 */
@Composable
fun ProferActivityLine(
    text: String,
    modifier: Modifier = Modifier,
    pulsing: Boolean = true,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        ProferStatusDot(
            color = MaterialTheme.colorScheme.tertiary,
            pulsing = pulsing,
            size = 6.dp,
        )
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
fun ProferPermissionCard(
    title: String,
    description: String,
    allowLabel: String,
    denyLabel: String,
    onAllow: () -> Unit,
    onDeny: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                ProferStatusDot(color = ProferThemeExtras.semantic.warning, size = 6.dp)
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Surface(
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = ProferSizes.minTouchTarget)
                        // 先 clip 再 clickable，ripple 才跟胶囊形状走。
                        .clip(CircleShapeCompatible)
                        .clickable(onClick = onAllow),
                    shape = CircleShapeCompatible,
                    color = MaterialTheme.colorScheme.primary,
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            text = allowLabel,
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onPrimary,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
                Surface(
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = ProferSizes.minTouchTarget)
                        .clip(CircleShapeCompatible)
                        .clickable(onClick = onDeny),
                    shape = CircleShapeCompatible,
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            text = denyLabel,
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

private val CircleShapeCompatible = RoundedCornerShape(19.dp)
