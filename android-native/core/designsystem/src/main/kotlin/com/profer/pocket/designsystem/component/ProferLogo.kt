package com.profer.pocket.designsystem.component

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Profer 标记：一块圆角底色，内部是「米白方块 + 被挖掉的黑块 + 灰块」。
 * 形状来自品牌 icon，不在深色主题里换构图，只换底色。
 */
@Composable
fun ProferMark(
    modifier: Modifier = Modifier,
    size: Dp = 28.dp,
    // 品牌标记始终用墨色（深色主题反之），不跟着主操作色走：
    // 主色是砖红，品牌标记如果也变红，就分不清「品牌」和「可点的按钮」。
    containerColor: Color = MaterialTheme.colorScheme.onSurface,
    markColor: Color = MaterialTheme.colorScheme.surface,
) {
    Canvas(modifier.size(size)) {
        val s = this.size.minDimension
        drawRoundRect(
            color = containerColor,
            cornerRadius = CornerRadius(s * 0.28f, s * 0.28f),
        )
        drawRoundRect(
            color = markColor,
            topLeft = Offset(s * 0.19f, s * 0.19f),
            size = Size(s * 0.62f, s * 0.62f),
            cornerRadius = CornerRadius(s * 0.115f, s * 0.115f),
        )
        drawRect(
            color = containerColor,
            topLeft = Offset(s * 0.52f, s * 0.35f),
            size = Size(s * 0.30f, s * 0.47f),
        )
        drawRect(
            color = Color(0xFF8F877C),
            topLeft = Offset(s * 0.66f, s * 0.48f),
            size = Size(s * 0.16f, s * 0.34f),
        )
    }
}

@Composable
fun ProferWordmark(
    modifier: Modifier = Modifier,
    markSize: Dp = 26.dp,
    textColor: Color = MaterialTheme.colorScheme.onSurface,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        ProferMark(size = markSize)
        Text(
            text = "Profer",
            color = textColor,
            fontSize = 19.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.sp,
        )
    }
}
