package com.profer.pocket.designsystem.component

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.profer.pocket.designsystem.theme.ProferSizes
import com.profer.pocket.designsystem.theme.ProferSpacing
import com.profer.pocket.designsystem.theme.ProferThemeExtras

/** 小标题：项目分组、设置分组。靠字距与颜色分层，不加粗堆叠。 */
@Composable
fun ProferSectionLabel(
    text: String,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        trailing?.invoke()
    }
}

@Composable
fun ProferPill(
    text: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    selected: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    val container = if (selected) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceContainer
    }
    val content = if (selected) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    Box(
        modifier = modifier.then(
            if (onClick != null) {
                Modifier
                    .heightIn(min = ProferSizes.minTouchTarget)
                    .clip(CircleShape)
                    .clickable(onClick = onClick)
            } else {
                Modifier.heightIn(min = 30.dp)
            },
        ),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            modifier = Modifier.height(30.dp),
            shape = CircleShape,
            color = container,
            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                if (icon != null) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = content,
                        modifier = Modifier.size(14.dp),
                    )
                }
                Text(
                    text = text,
                    style = MaterialTheme.typography.labelMedium,
                    color = content,
                    maxLines = 1,
                )
            }
        }
    }
}

/**
 * 状态点：运行中用呼吸，静态用实心。颜色不是唯一信号时优先配文字。
 */
@Composable
fun ProferStatusDot(
    color: Color,
    modifier: Modifier = Modifier,
    pulsing: Boolean = false,
    size: Dp = 7.dp,
) {
    val alpha = if (pulsing) {
        val transition = rememberInfiniteTransition(label = "status-dot")
        transition.animateFloat(
            initialValue = 0.35f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 900),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "status-dot-alpha",
        ).value
    } else {
        1f
    }
    Box(
        modifier = modifier
            .size(size)
            .background(color.copy(alpha = alpha), CircleShape),
    )
}

/**
 * 连接状态条：只在非「已连接」时出现，不打断操作。
 */
@Composable
fun ProferConnectionBanner(
    text: String,
    modifier: Modifier = Modifier,
    tone: BannerTone = BannerTone.Warning,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    val semantic = ProferThemeExtras.semantic
    val container = when (tone) {
        BannerTone.Warning -> semantic.warningContainer
        BannerTone.Error -> MaterialTheme.colorScheme.errorContainer
    }
    val content = when (tone) {
        BannerTone.Warning -> semantic.onWarningContainer
        BannerTone.Error -> MaterialTheme.colorScheme.onErrorContainer
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(container)
            .padding(horizontal = ProferSpacing.lg, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ProferSpacing.sm),
    ) {
        ProferStatusDot(color = content, size = 6.dp)
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = content,
            modifier = Modifier.weight(1f),
        )
        if (actionLabel != null && onAction != null) {
            Text(
                text = actionLabel,
                style = MaterialTheme.typography.labelMedium,
                color = content,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .heightIn(min = ProferSizes.minTouchTarget)
                    .clickable(onClick = onAction)
                    .padding(horizontal = 8.dp),
            )
        }
    }
}

enum class BannerTone { Warning, Error }

@Composable
fun ProferDivider(modifier: Modifier = Modifier, insetStart: Dp = 0.dp) {
    HorizontalDivider(
        modifier = modifier.padding(start = insetStart),
        thickness = 1.dp,
        color = MaterialTheme.colorScheme.outlineVariant,
    )
}

/** 统一的可点击图标位：视觉 22dp，热区 48dp。 */
@Composable
fun ProferIconAction(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    size: Dp = ProferSizes.minTouchTarget,
) {
    Box(
        modifier = modifier
            .size(size.coerceAtLeast(ProferSizes.minTouchTarget))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = tint,
            modifier = Modifier.size(if (size < ProferSizes.minTouchTarget) 20.dp else 22.dp),
        )
    }
}

/** 内容块圆角：与 M3 形状档位一致，不出现 6/7dp 混用。 */
val ProferCardShape = RoundedCornerShape(16.dp)
val ProferSheetShape = RoundedCornerShape(28.dp)
