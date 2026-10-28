package com.profer.pocket.chat

import androidx.activity.compose.BackHandler
import androidx.activity.compose.PredictiveBackHandler
import android.os.Build
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
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
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
 * 松手吸附的位移阈值。
 *
 * 按屏宽比例算阈值（曾经的 progress >= 0.5）在 360dp 机器上要横拖 180dp 才切换，
 * 手指根本拖不了那么远；这里对齐 M3 Swipeable 的位置阈值（56dp），取 64dp：
 * 拉开一个拇指节就吸附，剩下的交给动画。
 */
private val DrawerToggleDistance = 64.dp

/** 快速一甩的判定速度（dp/s）：超过它按方向切换，不再看位移。 */
private val DrawerFlingVelocity = 400.dp

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

    /** 本次拖动开始时的进度：位移不够时回到它，避免「差一点」被反向吸走。 */
    private var dragStartProgress = 0f

    fun open() {
        animateTo(1f)
    }

    fun close() {
        animateTo(0f)
    }

    internal fun beginDrag() {
        animationJob?.cancel()
        dragStartProgress = progress
    }

    internal fun drag(deltaPx: Float, widthPx: Float) {
        if (widthPx <= 0f) return
        progress = (progress + deltaPx / widthPx).coerceIn(0f, 1f)
        // 真的拉开才挂载抽屉内容：关闭态向左滑不该把整棵会话列表组合出来。
        if (progress > 0f) isOpen = true
    }

    internal fun endDrag(
        velocityX: Float,
        distancePx: Float,
        toggleDistancePx: Float,
        flingVelocityPx: Float,
    ) {
        val shouldOpen = resolveDrawerTarget(
            velocityX = velocityX,
            distancePx = distancePx,
            toggleDistancePx = toggleDistancePx,
            flingVelocityPx = flingVelocityPx,
            startProgress = dragStartProgress,
        )
        animateTo(if (shouldOpen) 1f else 0f)
    }

    private fun animateTo(target: Float) {
        animationJob?.cancel()
        // 展开动画一开始就要有内容，否则动画前几帧是空抽屉。
        if (target > 0f) isOpen = true
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

    /**
     * 预测性返回：手势进行中直接跟随进度，不走动画。
     * 手势取消时由调用方 [open] 回弹，完成时由 [close] 收尾。
     */
    internal fun previewProgress(value: Float) {
        animationJob?.cancel()
        isOpen = true
        progress = value.coerceIn(0f, 1f)
    }
}

/**
 * 松手吸附的判定（纯函数，便于单测）：
 * - 速度够快按方向走（一甩即开、即合）；
 * - 否则看这次拖动的总位移，越过 [DrawerToggleDistance] 就切换；
 * - 都不满足时回到拖动前的状态，不会因为「差一点」被反向吸走。
 */
internal fun resolveDrawerTarget(
    velocityX: Float,
    distancePx: Float,
    toggleDistancePx: Float,
    flingVelocityPx: Float,
    startProgress: Float,
): Boolean = when {
    velocityX > flingVelocityPx -> true
    velocityX < -flingVelocityPx -> false
    distancePx >= toggleDistancePx -> true
    distancePx <= -toggleDistancePx -> false
    else -> startProgress >= 0.5f
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
            // 手势挂在静止的外层：挂在会跟着手势位移的内容层上，坐标系会跟着动，
            // 手指拖 1px 只算出不到 1px 的位移，抽屉跟手变“滞”，阈值也就变得很远。
            .drawerDrag(drawerState)
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
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
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
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
        ) {
            content()
        }

        // 返回手势：API 34+ 走预测性返回，抽屉跟着手指退；更低版本保留普通 BackHandler。
        // 两个回调不能同时注册，否则后注册的那个会吞掉前一个。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            PredictiveBackHandler(enabled = drawerState.isOpen) { progress ->
                try {
                    progress.collect { event ->
                        drawerState.previewProgress(1f - event.progress)
                    }
                    // 手势完成：已经跟到接近 0，直接收尾。
                    drawerState.close()
                } catch (cancel: CancellationException) {
                    // 手势取消：回弹到完全打开。
                    drawerState.open()
                    throw cancel
                }
            }
        } else {
            BackHandler(enabled = drawerState.isOpen) { drawerState.close() }
        }
    }
}

/**
 * 水平拖拽：只有在确认横向主导后才消费事件；纵向超过 slop 立即退出，
 * 让列表的垂直滚动完全不受影响。
 */
private fun Modifier.drawerDrag(drawerState: PocketDrawerState): Modifier = pointerInput(drawerState) {
    val widthPx = size.width.toFloat().coerceAtLeast(1f)
    val togglePx = DrawerToggleDistance.toPx()
    val flingPx = DrawerFlingVelocity.toPx()
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val tracker = VelocityTracker()
        tracker.addPosition(down.uptimeMillis, down.position)
        var dragging = false
        // 移动事件被子组件消费过（横向代码块、列表滚动）：这个手势归它，抬手也不能抢。
        var childOwnsGesture = false
        // 已经交给抽屉的位移：越过 slop 时要把开头那段一次性补上，避免手势「晚一拍」。
        var appliedX = 0f
        while (true) {
            val event = awaitPointerEvent()
            val change = event.changes.firstOrNull { it.id == down.id } ?: break
            tracker.addPosition(change.uptimeMillis, change.position)
            // 用「相对按下点的净位移」而不是逐帧 delta 相加：抬手事件不带位移，
            // 极快的一甩可能整段只来一个 down + up，按 delta 累加会算成 0。
            val netX = change.position.x - down.position.x
            val netY = change.position.y - down.position.y
            val up = change.changedToUpIgnoreConsumed()
            if (!dragging) {
                // isConsumed：横向内容（代码块、横向列表）自己滑时不能抢过来。
                // 只看移动事件：抬手被子组件当点击消费不算「它接管了手势」。
                if (!up && change.isConsumed) childOwnsGesture = true
                val horizontal = !change.isConsumed &&
                    abs(netX) > viewConfiguration.touchSlop &&
                    abs(netX) > abs(netY) * 1.2f
                if (horizontal) {
                    drawerState.beginDrag()
                    dragging = true
                } else if (up) {
                    // 一甩到底、中间没有 move 采样（掉帧时 down/up 会并到同一帧）：
                    // 抬手时按总位移与速度再判一次。这里不能看 up 的 isConsumed——
                    // 子组件的 clickable 会把这个 up 当成一次点击消费掉。
                    if (!childOwnsGesture &&
                        abs(netX) > viewConfiguration.touchSlop &&
                        abs(netX) > abs(netY) * 1.2f
                    ) {
                        change.consume()
                        drawerState.beginDrag()
                        drawerState.endDrag(tracker.calculateVelocity().x, netX, togglePx, flingPx)
                    }
                    break
                } else if (abs(netY) > viewConfiguration.touchSlop) {
                    // 纵向滚动：不消费、不吸附，把事件完整留给列表。
                    break
                }
            }
            if (up) {
                if (dragging) {
                    drawerState.endDrag(tracker.calculateVelocity().x, netX, togglePx, flingPx)
                }
                break
            }
            if (dragging) {
                change.consume()
                drawerState.drag(netX - appliedX, widthPx)
                appliedX = netX
            }
        }
    }
}
