package com.profer.pocket.chat

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/** 和 web 版 `.sidebar-collapse-ease` 同一条曲线（300ms）。 */
private val DrawerEase = CubicBezierEasing(0.32f, 0.72f, 0f, 1f)
private const val DrawerAnimationMs = 300
private val DrawerCorner = 28.dp
private val DrawerEdge = 30.dp
private val DrawerGap = 4.dp

/**
 * 抽屉状态：0 = 完全关闭，1 = 完全打开。
 *
 * 做成 float 而不是 Material 的三态 DrawerState，是为了让手势能跟手；
 * [isOpen] 只负责「抽屉内容是否参与组合」，关闭动画结束后才置 false，
 * 避免拖动过程中抽屉内容被反复组合/销毁。
 */
@Stable
class PocketDrawerState internal constructor(private val scope: CoroutineScope) {
    var progress by mutableFloatStateOf(0f)
        private set

    var isOpen by mutableStateOf(false)
        private set

    private var animationJob: Job? = null

    fun open() {
        isOpen = true
        animateTo(1f)
    }

    fun close() {
        animateTo(0f)
    }

    internal fun beginDrag() {
        animationJob?.cancel()
        // 手指一碰就把内容挂上，拖动过程才不会出现空抽屉。
        isOpen = true
    }

    internal fun drag(deltaPx: Float, widthPx: Float) {
        if (widthPx <= 0f) return
        progress = (progress + deltaPx / widthPx).coerceIn(0f, 1f)
    }

    internal fun endDrag(velocityX: Float = 0f) {
        // 快速一甩按方向走，慢速拖动看进度；对应 web 版 64dp 即切换的灵敏度。
        val shouldOpen = when {
            velocityX > 700f -> true
            velocityX < -700f -> false
            else -> progress >= 0.5f
        }
        animateTo(if (shouldOpen) 1f else 0f)
    }

    private fun animateTo(target: Float) {
        animationJob?.cancel()
        animationJob = scope.launch {
            animate(
                initialValue = progress,
                targetValue = target,
                animationSpec = tween(DrawerAnimationMs, easing = DrawerEase),
            ) { value, _ ->
                progress = value
            }
            if (target == 0f) isOpen = false
        }
    }
}

@Composable
fun rememberPocketDrawerState(): PocketDrawerState {
    val scope = rememberCoroutineScope()
    return remember(scope) { PocketDrawerState(scope) }
}

/**
 * 对齐 web 版 Pocket 的抽屉机制：
 * - 对话区整块可水平拖动，右滑打开、左滑收起，松手按进度吸附；
 * - 抽屉宽度 = 屏宽 - 38dp（右侧留 30dp 卡片边缘 + 8dp 间隙）；
 * - 打开时对话区被推到右侧，只露出 30dp 的 28dp 圆角卡片边，点它即返回。
 */
@Composable
fun PocketDrawerLayout(
    drawerState: PocketDrawerState,
    modifier: Modifier = Modifier,
    drawerContent: @Composable () -> Unit,
    content: @Composable () -> Unit,
) {
    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .clipToBounds()
            // 与抽屉同色：卡片缩小时露出的缝隙不能比抽屉更深，否则会显脏。
            .background(MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        val density = LocalDensity.current
        val drawerWidth = maxWidth - (DrawerEdge + DrawerGap)
        val pushDistance = maxWidth - DrawerEdge
        val drawerWidthPx = with(density) { drawerWidth.toPx() }
        val pushPx = with(density) { pushDistance.toPx() }
        // 抽屉在内容下层：关闭时整体移到屏幕左侧之外。
        Box(
            modifier = Modifier
                .width(drawerWidth)
                .fillMaxHeight()
                .offset { IntOffset((drawerWidthPx * (drawerState.progress - 1f)).roundToInt(), 0) }
                // 抽屉底色比内容卡片深：卡片缩小时四周的留白才看得出层次。
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .drawerDrag(drawerState),
        ) {
            drawerContent()
        }

        // 内容层：关闭时全屏；抽屉打开时缩成一张悬浮卡片（上下留白），推向右只留 DrawerEdge 宽的圆角边缘。
        Box(
            modifier = Modifier
                .fillMaxSize()
                .offset { IntOffset((pushPx * drawerState.progress).roundToInt(), 0) }
                .graphicsLayer {
                    val progress = drawerState.progress
                    // 收起态全屏；抽屉打开时内容区缩成一张悬浮卡片（上下留白）。
                    val scale = 1f - 0.03f * progress
                    scaleX = scale
                    scaleY = scale
                    // 以左边缘为锚点：卡片左边缘不因缩放右移，和抽屉之间只留 DrawerGap。
                    transformOrigin = TransformOrigin(0f, 0.5f)
                    shape = RoundedCornerShape((DrawerCorner.value * progress).dp)
                    clip = true
                }
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .drawerDrag(drawerState),
        ) {
            content()
        }

        BackHandler(enabled = drawerState.isOpen) { drawerState.close() }
    }
}

/**
 * 水平拖拽：只有在确认横向主导后才消费事件；纵向超过 slop 立即退出，
 * 让列表的垂直滚动完全不受影响。
 */
private fun Modifier.drawerDrag(drawerState: PocketDrawerState): Modifier = pointerInput(drawerState) {
    val widthPx = size.width.toFloat().coerceAtLeast(1f)
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        var dragging = false
        var totalX = 0f
        var totalY = 0f
        while (true) {
            val event = awaitPointerEvent()
            val change = event.changes.firstOrNull { it.id == down.id } ?: break
            val dx = change.positionChange().x
            val dy = change.positionChange().y
            totalX += dx
            totalY += dy
            if (change.changedToUpIgnoreConsumed()) {
                // 快速一甩时 move 采样可能很少，抬手的位移也要能触发。
                if (!dragging &&
                    abs(totalX) > viewConfiguration.touchSlop * 2 &&
                    abs(totalX) > abs(totalY) * 1.2f
                ) {
                    if (totalX > 0) drawerState.open() else drawerState.close()
                }
                break
            }
            if (!dragging) {
                if (abs(totalX) > viewConfiguration.touchSlop &&
                    abs(totalX) > abs(totalY) * 1.2f
                ) {
                    drawerState.beginDrag()
                    dragging = true
                } else if (abs(totalY) > viewConfiguration.touchSlop) {
                    // 纵向滚动：不消费、不吸附，把事件完整留给列表。
                    break
                }
            }
            if (dragging) {
                change.consume()
                drawerState.drag(dx, widthPx)
            }
        }
        if (dragging) drawerState.endDrag(0f)
    }
}
