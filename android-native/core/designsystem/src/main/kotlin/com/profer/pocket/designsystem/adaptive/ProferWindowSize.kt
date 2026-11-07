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
 * - Medium：平板竖屏、折叠屏展开态，够宽才双栏；
 * - Expanded：平板横屏、桌面窗口，双栏。
 */
enum class ProferWindowWidth { Compact, Medium, Expanded }

/**
 * 对话区最小可用宽。与桌面 `panel-layout.ts` 的 `CONVERSATION_MIN_WIDTH = 420` 同值：
 * 再窄正文就只能一行放三四个词，分栏反而比单栏更难读——桌面版在窗口不足时也是先保对话区。
 */
val ProferContentMinWidth = 420.dp

/**
 * 列表栏最小宽。比例化之后手机上会落到 200–260dp：这个宽度下滑列表头换成紧凑档
 * （收品牌字、收内边距、模式胶囊只留文字），再窄就摆不下一行图标。
 */
val ProferListMinWidth = 200.dp

/**
 * 列表栏最大宽。桌面版是 54 rail + 246 列表 = 300；手机没有 rail，320 已经比桌面更宽，
 * 不再随屏宽继续放大，宽出来的像素留给正文。
 */
val ProferListMaxWidth = 320.dp

/**
 * 对话区占窗宽的目标比例区间：至少 2/3，最多 4/5，剩下的才是侧栏。
 * 手机横屏 781dp 时侧栏 219dp、对话 562dp（72%）；平板 1280dp 时侧栏封顶 320dp、对话 960dp（75%）。
 */
private const val ListPaneShare = 0.28f
private const val ConversationMinShare = 2f / 3f
private const val ConversationMaxShare = 4f / 5f

/** 双栏两张卡片之间的缝（与桌面 AppShell 的栏间距同量级）。 */
val ProferPaneGap = 8.dp

/**
 * 双栏所需的最小窗口宽：列表下限 + 缝 + 对话区下限。
 * 688dp 以下不再分栏——手机横屏（约 640–780dp）里，640dp 这一档分栏后内容只剩 345dp，
 * 比竖屏 360dp 还窄，是「伪桌面」。
 */
val ProferTwoPaneMinWidth = 688.dp

@Immutable
data class ProferWindowSize(
    val width: Dp,
    val height: Dp,
    val widthClass: ProferWindowWidth,
) {
    /** 短屏：手机横屏、半折叠、分屏下半。垂直空间只够一屏半消息，顶栏与空态要收着放。 */
    val isShort: Boolean get() = height < 480.dp

    /** 横屏（含平板横屏、分屏的横向窗口）。 */
    val isLandscape: Boolean get() = width > height

    /**
     * 是否常驻双栏：宽度必须同时放得下列表和 420dp 的对话区。
     * 只看 M3 断点会在 600dp 分屏和 640dp 小屏横屏上强行分栏，内容区被挤到 345dp。
     */
    val isTwoPane: Boolean
        get() = widthClass != ProferWindowWidth.Compact && width >= ProferTwoPaneMinWidth

    /**
     * 双栏时列表栏宽度：按窗宽比例给（目标 28%），同时夹在「对话区 2/3–4/5」之间。
     * 781dp 手机横屏 219dp（对话 72%），1280dp 平板封顶 320dp（对话 75%），
     * 688dp 双栏下限 200dp（对话 70%）。对话区永远比侧栏宽一倍以上。
     */
    val listPaneWidth: Dp
        get() = when (widthClass) {
            ProferWindowWidth.Compact -> width
            else -> {
                val usable = width - ProferPaneGap
                val minList = maxOf(usable * (1f - ConversationMaxShare), ProferListMinWidth)
                val maxList = minOf(usable * (1f - ConversationMinShare), ProferListMaxWidth)
                (width * ListPaneShare).coerceIn(minList, maxOf(minList, maxList))
            }
        }
}

fun windowWidthClass(width: Dp): ProferWindowWidth = when {
    width < 600.dp -> ProferWindowWidth.Compact
    width < 840.dp -> ProferWindowWidth.Medium
    else -> ProferWindowWidth.Expanded
}

/**
 * 横屏单栏（窄横屏、分屏）的抽屉宽度：固定一列侧栏，不再「整屏推卡」。
 *
 * 竖屏抽屉 = 屏宽 - 38，抽屉几乎盖满屏幕，靠推出内容卡片来表达层级；
 * 横屏屏宽 640dp 时同一公式会推出 610dp，内容卡片整块移出屏幕，只剩一条空白。
 * 桌面版在这种宽度下是「一列侧栏浮在主区上」，这里对齐：320dp 定宽覆盖，内容不动。
 */
fun landscapeDrawerWidth(width: Dp): Dp =
    minOf(ProferListMaxWidth, width * (1f - ConversationMinShare)).coerceAtLeast(ProferListMinWidth)

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

/**
 * 双栏时侧栏卡自己已经把状态栏让开了：常驻列表头不要再叠一次 `statusBarsPadding`，
 * 否则侧栏的按钮行会比对话顶栏低一整个状态栏，顶部就错位了。
 * 单栏抽屉 / 独立路由页仍是 false，由各自页面处理。
 */
val LocalProferSidebarHandlesInsets = compositionLocalOf { false }
