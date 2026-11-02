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
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import com.profer.pocket.designsystem.theme.ProferSizes
import com.profer.pocket.designsystem.theme.ProferSpacing

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
    content: @Composable RowScope.() -> Unit,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = ProferSpacing.sm, vertical = ProferSpacing.sm)
            .heightIn(min = ProferSizes.minTouchTarget),
        horizontalArrangement = Arrangement.spacedBy(ProferSpacing.xs),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        content = content,
    )
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
