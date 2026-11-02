package com.profer.pocket.designsystem.adaptive

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 窗口宽度档位，沿用 M3 的 600 / 840dp 断点：
 * - Compact：手机竖屏、折叠屏折叠态，单栏 + 抽屉；
 * - Medium：平板竖屏、折叠屏展开态，双栏（列表 320dp + 内容）；
 * - Expanded：平板横屏、桌面窗口，双栏（列表 360dp + 内容）。
 */
enum class ProferWindowWidth { Compact, Medium, Expanded }

@Immutable
data class ProferWindowSize(
    val width: Dp,
    val height: Dp,
    val widthClass: ProferWindowWidth,
) {
    val isTwoPane: Boolean get() = widthClass != ProferWindowWidth.Compact

    /**
     * 双栏时列表栏宽度。320dp 是「品牌 + 模式 + 搜索 + 新建」一行放得下的下限；
     * 同时不超过屏宽 46%，分屏/小窗口里列表不能把内容挤到不可用。
     * 折叠屏展开（约 740dp）落在 320dp，600dp 分屏退回 276dp，内容仍有 324dp。
     */
    val listPaneWidth: Dp
        get() = when (widthClass) {
            ProferWindowWidth.Expanded -> minOf(360.dp, width * 0.46f)
            ProferWindowWidth.Medium -> minOf(320.dp, width * 0.46f)
            ProferWindowWidth.Compact -> width
        }
}

fun windowWidthClass(width: Dp): ProferWindowWidth = when {
    width < 600.dp -> ProferWindowWidth.Compact
    width < 840.dp -> ProferWindowWidth.Medium
    else -> ProferWindowWidth.Expanded
}

/**
 * 当前窗口尺寸。由 [com.profer.pocket.chat.PocketDrawerLayout] 在根部用 `BoxWithConstraints` 测量后注入：
 * 分屏、自由窗口、折叠屏展开都会改父约束，比 `LocalConfiguration.screenWidthDp` 更贴近真实可用宽度。
 */
val LocalProferWindowSize = compositionLocalOf {
    ProferWindowSize(width = 360.dp, height = 640.dp, widthClass = ProferWindowWidth.Compact)
}

@Composable
fun rememberProferWindowSize(): ProferWindowSize = LocalProferWindowSize.current

/**
 * 在应用根部测量一次窗口尺寸并注入。
 *
 * 设置、连接这些独立路由页不在 `PocketDrawerLayout` 内，读不到它局部的 Provider；
 * 没有根部这一层，它们会永远拿到默认的 Compact，平板适配直接失效。
 */
@Composable
fun ProferWindowSizeProvider(content: @Composable () -> Unit) {
    androidx.compose.foundation.layout.BoxWithConstraints(
        modifier = androidx.compose.ui.Modifier.fillMaxSize(),
    ) {
        androidx.compose.runtime.CompositionLocalProvider(
            LocalProferWindowSize provides ProferWindowSize(
                width = maxWidth,
                height = maxHeight,
                widthClass = windowWidthClass(maxWidth),
            ),
            content = content,
        )
    }
}

/**
 * 折叠屏铰链。`isSeparating` 为 true 时内容不能跨过去（半折叠、书本式）；
 * 为 false 时是柔性屏的折痕，可以跨但阅读位置要避开。
 */
@Immutable
data class ProferHinge(
    val vertical: Boolean,
    val isSeparating: Boolean,
    /** 铰链占用的宽度（垂直）或高度（水平）。 */
    val size: Dp,
    /** 铰链在窗口内的起点：垂直取 left，水平取 top。 */
    val start: Dp,
)

val LocalProferHinge = compositionLocalOf<ProferHinge?> { null }
