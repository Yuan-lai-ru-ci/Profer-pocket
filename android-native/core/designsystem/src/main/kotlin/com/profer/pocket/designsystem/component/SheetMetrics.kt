package com.profer.pocket.designsystem.component

import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp

/**
 * 底部弹层的最大高度。
 *
 * 弹层一旦铺满整屏就不再像「弹层」：状态栏被吞掉、顶部只剩一段空白，
 * 用户也失去「下面还有内容」的空间参照。默认在屏幕顶部留出约 14%。
 *
 * 用法：给弹层内容根 Column 加上它，列表用 `Modifier.weight(1f, fill = false)`，
 * 内容短时弹层仍然贴着内容高度，不会撑到上限。
 */
@Composable
fun Modifier.sheetMaxHeight(fraction: Float = 0.86f): Modifier {
    val screenHeight = LocalConfiguration.current.screenHeightDp.dp
    return this.heightIn(max = screenHeight * fraction)
}
