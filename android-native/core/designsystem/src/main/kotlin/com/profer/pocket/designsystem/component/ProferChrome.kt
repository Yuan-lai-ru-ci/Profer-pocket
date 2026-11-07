package com.profer.pocket.designsystem.component

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.luminance
import com.profer.pocket.designsystem.theme.ProferSizes
import com.profer.pocket.designsystem.theme.ProferSpacing

/**
 * 顶栏紧凑档的最小触控尺寸。默认 48dp（红线）；手机横屏的顶栏用 40dp——
 * 状态栏 + 48dp 顶栏已经吃掉 360dp 高的 20%，用户要求再往上收，只能动这一档。
 * 只包在顶栏里，不影响正文里的任何按钮。
 */
val LocalProferMinTouchTarget = compositionLocalOf { ProferSizes.minTouchTarget }

/** 画布、内容面、浮动操作面三层；材质不依赖实时模糊，低端设备同样可读。 */
@Composable
fun Modifier.proferBackdrop(): Modifier {
    val colors = MaterialTheme.colorScheme
    val top = lerp(colors.background, colors.primaryContainer, 0.24f)
    val bottom = lerp(colors.background, colors.tertiaryContainer, 0.1f)
    return drawBehind {
        drawRect(Brush.verticalGradient(listOf(top, colors.background, bottom)))
    }
}

@Composable
fun ProferBackdrop(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(modifier = modifier.fillMaxSize().proferBackdrop(), content = content)
}

/** 两种聊天页共用的顶栏几何，内容保持各自的业务槽位。 */
@Composable
fun ProferHeaderRow(
    modifier: Modifier = Modifier,
    /** 短屏（手机横屏、分屏）：上下留白从 8 收到 4，标题不再用浮层胶囊。 */
    compact: Boolean = false,
    content: @Composable RowScope.() -> Unit,
) {
    val row = @Composable { modifier: Modifier ->
        Row(
            modifier = modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = if (compact) ProferSpacing.xs else ProferSpacing.sm)
                .padding(vertical = if (compact) 0.dp else ProferSpacing.sm)
                .heightIn(min = if (compact) 40.dp else ProferSizes.minTouchTarget),
            horizontalArrangement = Arrangement.spacedBy(ProferSpacing.xs),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            content = content,
        )
    }
    if (compact) {
        CompositionLocalProvider(LocalProferMinTouchTarget provides 40.dp) { row(modifier) }
    } else {
        row(modifier)
    }
}

/**
 * 浮层色：浅色取最亮面，深色取更亮一档。
 * 深色下 surfaceContainerLowest 比画布更暗，直接用作浮层会像"挖洞"。
 */
@Composable
fun proferFloatingSurface(): Color {
    val colors = MaterialTheme.colorScheme
    return if (colors.background.luminance() < 0.5f) {
        colors.surfaceContainerHigh
    } else {
        colors.surfaceContainerLowest
    }
}

/** 选中态：浅色最亮面，深色再亮一档，保证与轨道拉开。 */
@Composable
fun proferSelectedSurface(): Color {
    val colors = MaterialTheme.colorScheme
    return if (colors.background.luminance() < 0.5f) {
        colors.surfaceContainerHighest
    } else {
        colors.surfaceContainerLowest
    }
}

/** 胶囊和圆键使用同一浮层色及边线，不再由页面各自拼透明度。 */
@Composable
fun ProferChrome(
    modifier: Modifier = Modifier,
    pill: Boolean = false,
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = modifier,
        shape = if (pill) CircleShape else MaterialTheme.shapes.extraLarge,
        color = proferFloatingSurface(),
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(ProferSizes.hairline, MaterialTheme.colorScheme.outlineVariant),
        shadowElevation = ProferSizes.floatingElevation,
        content = content,
    )
}
