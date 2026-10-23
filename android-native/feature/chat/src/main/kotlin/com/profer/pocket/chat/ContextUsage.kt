package com.profer.pocket.chat

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.profer.pocket.designsystem.theme.ProferHeadingSerif
import com.profer.pocket.designsystem.theme.ProferSpacing
import com.profer.pocket.designsystem.theme.ProferThemeExtras
import com.profer.pocket.model.ContextUsage
import kotlin.math.roundToInt

/** 桌面 ContextUsageBadge 同口径：窗口 77.5% 触发自动压缩。 */
private const val COMPACT_THRESHOLD_RATIO = 0.775f

/** 压缩阈值的 80% 进入预警色。 */
private const val WARNING_RATIO = 0.80f

/** 顶栏常驻指示器：20dp 压力环 + 百分比。 */
@Composable
fun ContextUsageIndicator(
    usage: ContextUsage?,
    window: Int?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val used = usage?.usedTokens?.takeIf { it > 0 }
    val percent = contextPercent(used, window)
    val warning = contextIsWarning(used, window)
    val ringColor = if (warning) {
        ProferThemeExtras.semantic.warning
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    val label = if (percent != null && used != null && window != null) {
        "上下文用量 $percent%，${formatContextTokens(used)} / ${formatContextTokens(window)}"
    } else if (used != null) {
        "上下文用量 ${formatContextTokens(used)}，窗口未知"
    } else {
        "上下文用量，暂无数据"
    }

    Row(
        modifier = modifier
            .height(44.dp)
            .clip(RoundedCornerShape(22.dp))
            .clickable(onClickLabel = "查看上下文详情", onClick = onClick)
            .padding(horizontal = 6.dp)
            .semantics(mergeDescendants = true) { contentDescription = label },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        ContextUsageRing(
            ratio = if (used != null && window != null && window > 0) {
                (used.toFloat() / window).coerceIn(0f, 1f)
            } else {
                0f
            },
            color = ringColor,
            hasData = used != null,
            modifier = Modifier.size(20.dp),
        )
        val valueLabel = percent?.let { "$it%" } ?: used?.let { formatContextTokens(it) }
        if (valueLabel != null) {
            Text(
                text = valueLabel,
                style = tabular(MaterialTheme.typography.labelSmall),
                color = if (warning) ringColor else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f),
                maxLines = 1,
            )
        }
    }
}

/** 环本身不带文字；无数据时只留低对比轨道，避免顶栏在首轮消息前后跳动。 */
@Composable
private fun ContextUsageRing(
    ratio: Float,
    color: Color,
    hasData: Boolean,
    modifier: Modifier = Modifier,
) {
    val track = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (hasData) 0.30f else 0.38f)
    Canvas(modifier = modifier) {
        val strokeWidth = 2.dp.toPx()
        val inset = strokeWidth / 2
        val arcSize = Size(size.width - strokeWidth, size.height - strokeWidth)
        drawArc(
            color = track,
            startAngle = 0f,
            sweepAngle = 360f,
            useCenter = false,
            topLeft = Offset(inset, inset),
            size = arcSize,
            style = Stroke(width = strokeWidth),
        )
        if (hasData && ratio > 0f) {
            drawArc(
                color = color,
                startAngle = -90f,
                sweepAngle = 360f * ratio,
                useCenter = false,
                topLeft = Offset(inset, inset),
                size = arcSize,
                style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
            )
        }
    }
}

/** 底部弹层：明细 + 自动压缩阈值 + 手动压缩。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContextUsageSheet(
    usage: ContextUsage?,
    window: Int?,
    streaming: Boolean,
    onCompact: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val used = usage?.usedTokens?.takeIf { it > 0 }
    val percent = contextPercent(used, window)
    val warning = contextIsWarning(used, window)
    val accent = if (warning) ProferThemeExtras.semantic.warning else MaterialTheme.colorScheme.onSurface

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = ProferSpacing.xl)
                .padding(bottom = ProferSpacing.xl),
            verticalArrangement = Arrangement.spacedBy(ProferSpacing.lg),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "上下文",
                    style = ProferHeadingSerif,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = contextAgeLabel(usage?.updatedAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                )
            }

            if (usage == null) {
                Text(
                    text = "当前渠道还没有返回 token 用量。部分渠道（如 GLM）不上报 usage，桌面端同样看不到这项数据。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.Bottom,
                ) {
                    Text(
                        text = percent?.let { "$it%" } ?: formatContextTokens(usage.usedTokens),
                        style = tabular(
                            MaterialTheme.typography.displaySmall.copy(
                                fontWeight = FontWeight.Normal,
                            ),
                        ),
                        color = accent,
                    )
                    Spacer(modifier = Modifier.width(ProferSpacing.sm))
                    Text(
                        text = if (window != null && window > 0) {
                            "${formatContextTokens(usage.usedTokens)} / ${formatContextTokens(window)}"
                        } else {
                            "窗口未知"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 5.dp),
                    )
                }

                ContextSegmentBar(usage = usage)
                ContextLegend(usage = usage)

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                ContextDetailRow(
                    label = "自动压缩",
                    value = contextThresholdLabel(usage.usedTokens, window),
                    emphasized = warning,
                )
                if (usage.outputTokens > 0) {
                    ContextDetailRow(
                        label = "本轮输出",
                        value = formatContextTokens(usage.outputTokens),
                    )
                }
            }

            Button(
                onClick = onCompact,
                enabled = !streaming,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                shape = RoundedCornerShape(14.dp),
            ) {
                Text(
                    text = if (streaming) "运行中无法压缩" else "立即压缩",
                    style = MaterialTheme.typography.labelLarge,
                )
            }

            Text(
                text = "占用 = 输入 + 缓存读 + 缓存写，输出不计入。压缩会把早期对话摘要化，之后仍可继续。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f),
            )
        }
    }
}

/** 分段条：输入 / 缓存写入 / 缓存读取，按占用比例堆叠。 */
@Composable
private fun ContextSegmentBar(usage: ContextUsage) {
    val total = usage.usedTokens.coerceAtLeast(0)
    val track = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)
    val inputColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.78f)
    val writeColor = MaterialTheme.colorScheme.tertiary
    val readColor = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.45f)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(8.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(track),
    ) {
        if (total > 0) {
            if (usage.pureInputTokens > 0) {
                Box(
                    modifier = Modifier
                        .weight(usage.pureInputTokens.toFloat())
                        .fillMaxHeight()
                        .background(inputColor),
                )
            }
            if (usage.cacheCreationInputTokens > 0) {
                Box(
                    modifier = Modifier
                        .weight(usage.cacheCreationInputTokens.toFloat())
                        .fillMaxHeight()
                        .background(writeColor),
                )
            }
            if (usage.cacheReadInputTokens > 0) {
                Box(
                    modifier = Modifier
                        .weight(usage.cacheReadInputTokens.toFloat())
                        .fillMaxHeight()
                        .background(readColor),
                )
            }
        }
    }
}

@Composable
private fun ContextLegend(usage: ContextUsage) {
    val inputColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.78f)
    val writeColor = MaterialTheme.colorScheme.tertiary
    val readColor = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.45f)

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (usage.pureInputTokens > 0) {
            LegendRow("输入", usage.pureInputTokens, inputColor)
        }
        if (usage.cacheCreationInputTokens > 0) {
            LegendRow("缓存写入", usage.cacheCreationInputTokens, writeColor)
        }
        if (usage.cacheReadInputTokens > 0) {
            LegendRow("缓存读取", usage.cacheReadInputTokens, readColor)
        }
    }
}

@Composable
private fun LegendRow(label: String, tokens: Int, color: Color) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(7.dp)
                .clip(CircleShape)
                .background(color),
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = formatContextTokens(tokens),
            style = tabular(MaterialTheme.typography.bodySmall),
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun ContextDetailRow(label: String, value: String, emphasized: Boolean = false) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            style = tabular(MaterialTheme.typography.bodySmall),
            color = if (emphasized) {
                ProferThemeExtras.semantic.warning
            } else {
                MaterialTheme.colorScheme.onSurface
            },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

internal fun contextPercent(used: Int?, window: Int?): Int? {
    if (used == null || used <= 0 || window == null || window <= 0) return null
    return ((used.toFloat() / window) * 100).roundToInt().coerceIn(0, 100)
}

internal fun contextIsWarning(used: Int?, window: Int?): Boolean {
    if (used == null || used <= 0 || window == null || window <= 0) return false
    val compactAt = window * COMPACT_THRESHOLD_RATIO
    return used / compactAt >= WARNING_RATIO
}

private fun contextThresholdLabel(used: Int, window: Int?): String {
    if (window == null || window <= 0) return "窗口未知"
    val compactAt = (window * COMPACT_THRESHOLD_RATIO).roundToInt()
    val percent = compactThresholdPercent()
    return if (used >= compactAt) {
        "$percent% · 已超过阈值"
    } else {
        "$percent% · 还差 ${formatContextTokens(compactAt - used)}"
    }
}

private fun compactThresholdPercent(): String {
    val tenths = (COMPACT_THRESHOLD_RATIO * 1000).roundToInt()
    return if (tenths % 10 == 0) "${tenths / 10}" else "${tenths / 10}.${tenths % 10}"
}

private fun contextAgeLabel(updatedAt: Long?): String {
    if (updatedAt == null || updatedAt <= 0) return "暂无数据"
    val ageMs = (System.currentTimeMillis() - updatedAt).coerceAtLeast(0)
    return when {
        ageMs < 5_000 -> "刚刚更新"
        ageMs < 60_000 -> "${(ageMs / 1000).coerceAtLeast(1)} 秒前更新"
        ageMs < 3_600_000 -> "${ageMs / 60_000} 分钟前更新"
        else -> "${ageMs / 3_600_000} 小时前更新"
    }
}

internal fun formatContextTokens(value: Int): String = when {
    value >= 1_000_000 -> "${(value / 100_000) / 10.0}M"
    value >= 1_000 -> "${(value / 100) / 10.0}k"
    else -> value.toString()
}

/** token 数字用等宽数字，避免百分比变化时整行左右跳动。 */
private fun tabular(style: TextStyle): TextStyle = style.copy(fontFeatureSettings = "tnum")
