package com.profer.pocket.designsystem.adaptive

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowLayoutInfo
import androidx.window.layout.WindowMetricsCalculator

/**
 * 折叠屏铰链/折痕。窗口布局变化（展开、半折叠、旋转、分屏）时重新计算。
 *
 * 只关心「有没有一条不能跨的带子、在哪、多宽」，不暴露 FoldingFeature 本身，
 * 免得 feature 模块为了排版去理解窗口库类型。
 */
@Composable
fun rememberProferHinge(): ProferHinge? {
    val context = LocalContext.current
    var hinge by remember(context) { mutableStateOf<ProferHinge?>(null) }
    LaunchedEffect(context) {
        val activity = context.findActivity() ?: return@LaunchedEffect
        val tracker = androidx.window.layout.WindowInfoTracker.getOrCreate(activity)
        tracker.windowLayoutInfo(activity).collect { info ->
            hinge = info.toProferHinge(activity)
        }
    }
    return hinge
}

private fun WindowLayoutInfo.toProferHinge(activity: Activity): ProferHinge? {
    val feature = displayFeatures.filterIsInstance<FoldingFeature>().firstOrNull() ?: return null
    val windowBounds = WindowMetricsCalculator.getOrCreate()
        .computeCurrentWindowMetrics(activity).bounds
    val density = activity.resources.displayMetrics.density.takeIf { it > 0f } ?: 1f
    val bounds = feature.bounds
    val vertical = feature.orientation == FoldingFeature.Orientation.VERTICAL
    val sizePx = if (vertical) bounds.width() else bounds.height()
    val startPx = if (vertical) bounds.left - windowBounds.left else bounds.top - windowBounds.top
    return ProferHinge(
        vertical = vertical,
        isSeparating = feature.isSeparating,
        size = (sizePx / density).dp,
        start = (startPx / density).dp,
    )
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
