package com.profer.pocket.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.profer.pocket.designsystem.adaptive.rememberProferWindowSize
import com.profer.pocket.designsystem.theme.ProferHeadingSerif
import com.profer.pocket.designsystem.theme.ProferSpacing

/**
 * 空态：一句人话解释为什么空 + 一个明确的下一步。
 */
@Composable
fun ProferEmptyState(
    title: String,
    description: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    primaryLabel: String? = null,
    onPrimary: (() -> Unit)? = null,
    secondaryLabel: String? = null,
    onSecondary: (() -> Unit)? = null,
) {
    // 短屏（横屏 360dp 高）与窄屏（<380dp）用紧凑档：图标 56 + 标题 28sp + 两行描述
    // 会把空态撑得比可视区还高，第二行直接从卡片边缘裁掉。
    val window = rememberProferWindowSize()
    val compact = window.isShort || window.width < 380.dp
    Column(
        modifier = modifier.padding(horizontal = if (compact) ProferSpacing.md else ProferSpacing.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        if (icon != null) {
            Box(
                modifier = Modifier
                    .size(if (compact) 36.dp else 56.dp)
                    .background(
                        MaterialTheme.colorScheme.surfaceContainer,
                        MaterialTheme.shapes.large,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(if (compact) 18.dp else 26.dp),
                )
            }
        } else {
            ProferMark(size = if (compact) 28.dp else 52.dp)
        }
        Text(
            text = title,
            style = if (compact) MaterialTheme.typography.titleMedium else MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(top = if (compact) ProferSpacing.xs else ProferSpacing.lg),
        )
        Text(
            text = description,
            style = if (compact) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .padding(top = if (compact) ProferSpacing.xxs else ProferSpacing.sm)
                .widthIn(max = 300.dp),
        )
        if (primaryLabel != null && onPrimary != null) {
            Box(modifier = Modifier.padding(top = if (compact) ProferSpacing.sm else ProferSpacing.lg)) {
                androidx.compose.material3.Button(
                    onClick = onPrimary,
                    // 固定高度会在 2.0× 字号下把字裁掉；给最小高度 + 内边距让它自己长。
                    modifier = Modifier.heightIn(min = 48.dp),
                    shape = MaterialTheme.shapes.extraLarge,
                    contentPadding = PaddingValues(horizontal = 24.dp, vertical = 6.dp),
                ) {
                    Text(text = primaryLabel)
                }
            }
        }
        if (secondaryLabel != null && onSecondary != null) {
            TextButton(onClick = onSecondary) {
                Text(text = secondaryLabel)
            }
        }
    }
}
