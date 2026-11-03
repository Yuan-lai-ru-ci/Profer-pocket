package com.profer.pocket.chat

import androidx.activity.compose.BackHandler
import androidx.activity.compose.PredictiveBackHandler
import android.content.Context
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.profer.pocket.designsystem.adaptive.LocalProferWindowSize
import com.profer.pocket.designsystem.adaptive.ProferHinge
import com.profer.pocket.designsystem.adaptive.ProferWindowSize
import com.profer.pocket.designsystem.adaptive.rememberProferHinge
import com.profer.pocket.designsystem.adaptive.windowWidthClass
import com.profer.pocket.designsystem.theme.ProferSizes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 抽屉吸附动画：弹簧 + 松手速度接力。
 *
 * 原来是 300ms 固定 cubic-bezier（0.32,0.72,0,1）：曲线前段太陡、后段太快收，
 * 松手瞬间进度会「跳一下」再匀速滑到位，看起来机械。改成临界阻尼弹簧：
 * 把松手时的速度当初始速度交给动画，快甩就顺着甩势落位、慢放就轻推一下，
 * 时长也随行程自适应（近处小行程不再硬等 300ms）。
 */
private const val DrawerSpringDamping = 0.85f
private const val DrawerSpringStiffness = 500f

/** 接力速度上限（progress/秒）：手速换算成进度速度后可能很大，封顶避免瞬移。 */
private const val DrawerMaxHandoffVelocity = 6f

/**
 * 没触发吸附时的回弹时长。
 *
 * 这类手势通常只拉开几十像素就松手：用弹簧会「急停 + 立刻消失」，看起来像闪了一下。
 * 改成两端都收的缓动曲线，回弹慢一点、尾巴软一点，和「没拉开」的语义一致。
 */
private const val DrawerCancelMs = 240

/** 抽屉内容的淡入行程：前 20% 行程里淡入，避免列表「突然出现又突然消失」。 */
private const val DrawerContentFadeSpan = 0.2f

/** 进度超过这个值才真正挂载抽屉内容（避免几十像素的小扯动就组合整棵列表）。 */
private const val DrawerComposeAtProgress = 0.02f

/** 内容透明度：跟随拉开进度淡入（纯函数，便于单测）。
 *
 * 量化到 1/4 档。`alpha < 1` 会让整层走离屏合成，逐帧连续的 alpha 等于整个淡入行程每帧
 * 重建一次图层；4 档在视觉上看不出台阶，但离屏只发生在档位切换的那几帧。
 */
internal fun drawerContentAlpha(progress: Float): Float {
    val raw = (progress / DrawerContentFadeSpan).coerceIn(0f, 1f)
    return (raw * 4f).roundToInt() / 4f
}
private val DrawerCorner = 28.dp

/**
 * 圆角按整 dp 预建。
 *
 * 以前在 `graphicsLayer` 里逐帧 `RoundedCornerShape((DrawerCorner * progress).dp)`：
 * 每帧新建 shape 并更新图层轮廓，侧滑时正文区每次都要重建裁剪/重绘，
 * 实测 debug 包抽屉开合 p99 77ms、p95 19ms。量化到 1dp 后一次动画最多变 29 次，
 * 视觉上看不出差别（半径变化小于 1dp），但图层轮廓不再逐帧失效。
 */
private const val DrawerCornerSteps = 28
private val DrawerCardShapes: Array<RoundedCornerShape> =
    Array(DrawerCornerSteps + 1) { RoundedCornerShape(it.dp) }
private val DrawerEdge = 30.dp
private val DrawerGap = 4.dp

/**
 * 松手吸附的位移阈值。
 *
 * 按屏宽比例算阈值（曾经的 progress >= 0.5）在 360dp 机器上要横拖 180dp 才切换，
 * 手指根本拖不了那么远；这里对齐 M3 Swipeable 的位置阈值（56dp），
 * 拉开一个拇指节就吸附，剩下的交给动画。
 */
private val DrawerToggleDistance = 56.dp

/**
 * 横向手势的接管判定（纯函数，便于单测）：越早锁定横向意图，越不容易被列表的竖向滚动抢走。
 *
 * 旧规则是「横移 > touchSlop 且 横移 > 竖移 × 1.2」。问题是列表自己的竖向滚动也在
 * touchSlop 处消费事件：斜着划时竖移往往先过线，抽屉就再也抢不回来——表现出来就是
 * 「想划抽屉却滚了列表」。现在分两档：
 * - 超过标准 slop：只要横向占优即可（1.0，不再要求 1.2）；
 * - 未到标准 slop：允许提前锁，但必须明显横向（0.65×slop 且 1.5 倍占优）。
 * 纯上下滑时 |netX| 始终远小于 |netY|，两档都不会命中。
 */
internal fun claimsHorizontalDrag(netX: Float, netY: Float, touchSlop: Float): Boolean {
    val ax = abs(netX)
    val ay = abs(netY)
    if (ax > touchSlop && ax >= ay) return true
    return ax > touchSlop * HorizontalClaimSlopFactor && ax > ay * HorizontalClaimRatio
}

private const val HorizontalClaimSlopFactor = 0.65f
private const val HorizontalClaimRatio = 1.5f

/**
 * 手指速度 → 抽屉位移增益。
 *
 * 1:1 跟随时「一甩而过」手感发滞：手指已经划到尽头，抽屉还没跟上。
 * 所以快划时给一点放大（慢拖 1.0 → 快划 1.5），位移仍以手指为准，只把抽屉的跟随速度提上去。
 * 速度按 [DragGainMinSpeed]（150px/s，≈ 慢拖）→ [DragGainMaxSpeed]（1200px/s，≈ 正常快划）线性映射，
 * 超出就封顶；真机刷一次约 2000–5000px/s，基本都在上限区。
 */
internal fun drawerDragGain(speedPxPerSec: Float): Float {
    val t = ((abs(speedPxPerSec) - DragGainMinSpeed) / (DragGainMaxSpeed - DragGainMinSpeed))
        .coerceIn(0f, 1f)
    return DragGainMin + (DragGainMax - DragGainMin) * t
}

/** 低于这个速度按 1:1 跟随（慢拖求精确）。 */
private const val DragGainMinSpeed = 150f

/** 达到这个速度就封顶到 [DragGainMax]。 */
private const val DragGainMaxSpeed = 1_200f

private const val DragGainMin = 1f
private const val DragGainMax = 1.5f

/** 速度平滑系数：单帧速度噪声大，滞波后再算增益，避免抽屉跟着采样抖动。 */
private const val DragSpeedSmoothing = 0.25f

/**
 * 进度到这个比例才震（≈ 动画最后一帧，距停住不到 20ms）：和抽屉「停住」的瞬间对齐。
 * 0.96 试过：虽然只剩最后 4% 行程，但抽屉的缓动是强 ease-out，视觉上还在明显推进（约 -110ms），
 * 手感上仍早半拍，所以压到 0.99。
 */
private const val DrawerHapticAtProgress = 0.99f

/** 收回到这个比例以下才重新武装，避免一次拉开反复震。 */
private const val DrawerHapticRearmBelow = 0.4f

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

    /**
     * 抽屉被手势吸附到打开态时的触感反馈。
     * 由 UI 层在组合时注入（状态类不持有 Android 上下文），没注入就不震。
     */
    internal var hapticOnOpen: (() -> Unit)? = null

    /** 本次拉开是否还没给过震动：到「几乎完全展开」才置位，收回后才重新武装。 */
    private var hapticArmed = true

    fun open() {
        animateTo(1f)
    }

    fun close() {
        animateTo(0f, 0f, returnSpec())
    }

    internal fun beginDrag() {
        animationJob?.cancel()
        dragStartProgress = progress
    }

    internal fun drag(deltaPx: Float, widthPx: Float, speedPxPerSec: Float = 0f) {
        if (widthPx <= 0f) return
        // 快划时抽屉跟得更快（最多 1.5 倍），慢拖保持 1:1；判据是手指瞬时速度。
        updateProgress(progress + deltaPx * drawerDragGain(speedPxPerSec) / widthPx)
        // 真的拉开才挂载抽屉内容：几十像素的小扯动不组合整棵会话列表（否则会闪一下）。
        if (progress > DrawerComposeAtProgress) isOpen = true
    }

    /**
     * 统一改进度的入口：到 [DrawerHapticAtProgress] 才震一下。
     *
     * 原来在松手瞬间（endDrag）就震，震动和抽屉动画完全不同步，感觉是「手指被蚊子弹了一下」；
     * 现在等它快贴到位（≥0.96）才震，才对得上「哐一下停住」的观感。
     * 慢拖到位后不再有动画，也是在拖动期间到 0.96 就震，不会漏。
     */
    private fun updateProgress(value: Float) {
        progress = value.coerceIn(0f, 1f)
        if (progress >= DrawerHapticAtProgress) {
            if (hapticArmed) {
                hapticArmed = false
                hapticOnOpen?.invoke()
            }
        } else if (progress <= DrawerHapticRearmBelow) {
            hapticArmed = true
        }
    }

    internal fun endDrag(
        velocityX: Float,
        distancePx: Float,
        toggleDistancePx: Float,
        flingVelocityPx: Float,
        widthPx: Float,
    ) {
        val shouldOpen = resolveDrawerTarget(
            velocityX = velocityX,
            distancePx = distancePx,
            toggleDistancePx = toggleDistancePx,
            flingVelocityPx = flingVelocityPx,
            startProgress = dragStartProgress,
        )
        // 抖动不在这里发：等动画快贴到位时再发（见 updateProgress）。
        // 把手指速度换算成「进度/秒」接力给弹簧：快甩顺着惯性落位，慢放轻推一下。
        val handoff = if (widthPx > 1f) {
            (velocityX / widthPx).coerceIn(-DrawerMaxHandoffVelocity, DrawerMaxHandoffVelocity)
        } else {
            0f
        }
        if (shouldOpen) {
            animateTo(1f, handoff)
        } else {
            animateTo(0f, 0f, returnSpec())
        }
    }

    /** 吸附到位用弹簧（带松手速度接力）。 */
    private fun latchSpec(): AnimationSpec<Float> = spring(
        dampingRatio = DrawerSpringDamping,
        stiffness = DrawerSpringStiffness,
        // 弹簧默认在离目标 1% 时就停（progress 0.99 ≈ 卡片离到位还差 10px，
        // 肉眼能看出没贴边）；压到 0.1% 才真正贴住，也保证 0.99 的触感点一定能到达。
        visibilityThreshold = 0.001f,
    )

    /** 没拉开 / 收起：两端都收的缓动，别用弹簧急停。 */
    private fun returnSpec(): AnimationSpec<Float> = tween(
        durationMillis = DrawerCancelMs,
        easing = FastOutSlowInEasing,
    )

    private fun animateTo(
        target: Float,
        initialVelocity: Float = 0f,
        spec: AnimationSpec<Float> = latchSpec(),
    ) {
        animationJob?.cancel()
        // 展开动画一开始就要有内容，否则动画前几帧是空抽屉。
        if (target > 0f) isOpen = true
        animationJob = scope.launch {
            animate(
                initialValue = progress,
                targetValue = target,
                animationSpec = spec,
                initialVelocity = initialVelocity,
            ) { value, _ ->
                updateProgress(value)
            }
            // 收回时等动画走完再摘掉内容：进度为 0 时 alpha 也是 0，摘除不可见。
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
        updateProgress(value)
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
    val context = LocalContext.current
    val state = remember(scope) { PocketDrawerState(scope) }
    SideEffect { state.hapticOnOpen = { performDrawerLatchHaptic(context) } }
    return state
}

/**
 * 抽屉吸附时的轻触反馈。
 *
 * 之前用 `View.performHapticFeedback(CONTEXT_CLICK)`：系统预置效果在这台机上是
 * 43–59ms、强度 2400（满档），对「抽屉停住」这种高频反馈偏重。现在改成单次短振 +
 * 六成幅度；按系统能力降级：不支持幅度控制的设备退化成最短时长，
 * 系统「触感反馈」关掉时完全不震。
 */
private fun performDrawerLatchHaptic(context: Context) {
    val resolver = context.contentResolver
    val enabled = Settings.System.getInt(resolver, Settings.System.HAPTIC_FEEDBACK_ENABLED, 1) == 1
    if (!enabled) return
    val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        context.getSystemService(VibratorManager::class.java)?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Vibrator::class.java)
    } ?: return
    if (!vibrator.hasVibrator()) return
    val effect = VibrationEffect.createOneShot(DrawerHapticDurationMs, DrawerHapticAmplitude)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        vibrator.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_TOUCH))
    } else {
        @Suppress("DEPRECATION")
        vibrator.vibrate(effect)
    }
}

/**
 * 吸附震动的时长与幅度。
 *
 * 不能用 `performHapticFeedback`：这台 ColorOS 把所有 haptic 常量都映射成
 * 满强度预置效果（实测 mEffectStrength=2400），改常量只能改长短，改不了力度。
 * 单次短振的 amplitude 是会被 OEM 换算成强度的（实测振幅 40/80 → 800，110 → 1035，
 * 140 → 1317，170 → 1600，满档 2400），所以用振幅来「降两档」。
 * 当前取值：8ms + 振幅 80 → 强度 800（满档的 1/3，比原来的 CONTEXT_CLICK 轻两档）。
 */
private const val DrawerHapticDurationMs = 8L

/** 吸附震动幅度（1..255）：110 在本机换算成强度 1035（满档 2400 的 43%）。 */
private const val DrawerHapticAmplitude = 110

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
    /**
     * 双栏时列表栏是否收起。单栏（手机）忽略：那边列表本来就是抽屉，没有「常驻侧栏」可收。
     * 状态由宿主持有，Agent / Chat 切换时不重置。
     */
    listPaneCollapsed: Boolean = false,
    drawerContent: @Composable () -> Unit,
    content: @Composable () -> Unit,
) {
    val hinge = rememberProferHinge()

    // 只测量，不挂手势：双栏时手势要彻底关掉，否则会抢内容区里的横向滚动。
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val window = ProferWindowSize(
            width = maxWidth,
            height = maxHeight,
            widthClass = windowWidthClass(maxWidth),
        )
        // 半折叠（帐篷/桌面模式）时可用高度只剩一半，双栏没有意义，退回单栏并把内容放到铰链下方。
        val halfOpenHorizontal = hinge?.let { !it.vertical && it.isSeparating } == true

        CompositionLocalProvider(LocalProferWindowSize provides window) {
            if (window.isTwoPane && !halfOpenHorizontal) {
                TwoPaneLayout(
                    window = window,
                    hinge = hinge,
                    drawerState = drawerState,
                    listCollapsed = listPaneCollapsed,
                    drawerContent = drawerContent,
                    content = content,
                    modifier = Modifier.fillMaxSize(),
                )
                return@CompositionLocalProvider
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clipToBounds()
                    // 手势挂在静止的外层：挂在会跟着手势位移的内容层上，坐标系会跟着动，
                    // 手指拖 1px 只算出不到 1px 的位移，抽屉跟手变“滞”，阈值也就变得很远。
                    .drawerDrag(drawerState)
                    // 与抽屉同色：卡片缩小时露出的缝隙不能比抽屉更深，否则会显脏。
                    .background(MaterialTheme.colorScheme.surfaceContainerLow),
            ) {
            val density = LocalDensity.current
            val drawerWidth = window.width - (DrawerEdge + DrawerGap)
            val pushDistance = window.width - DrawerEdge
            val drawerWidthPx = with(density) { drawerWidth.toPx() }
            val pushPx = with(density) { pushDistance.toPx() }
            // 横向半折叠：整块内容停到铰链下方，不让正文压在折痕上。
            val hingeTopPadding = hinge
                ?.takeIf { !it.vertical && it.isSeparating }
                ?.let { it.start + it.size }
                ?: 0.dp
            // 抽屉在内容下层：关闭时整体移到屏幕左侧之外。
            Box(
                modifier = Modifier
                    .width(drawerWidth)
                    .fillMaxHeight()
                    .offset { IntOffset((drawerWidthPx * (drawerState.progress - 1f)).roundToInt(), 0) }
                    .padding(top = hingeTopPadding)
                    // 跟着拉开进度淡入：只拉几十像素就松手时，列表不会「闪一下」。
                    .graphicsLayer { alpha = drawerContentAlpha(drawerState.progress) }
                    // 抽屉底色比内容卡片深：卡片缩小时四周的留白才看得出层次。
                    .background(MaterialTheme.colorScheme.surfaceContainerLow),
            ) {
                drawerContent()
            }

            // 内容层：关闭时全屏；抽屉打开时缩成一张悬浮卡片（上下留白），推向右只留 DrawerEdge 宽的圆角边缘。
            // 圆角只在整个 dp 变化时换实例，不在每帧新建 shape（见 DrawerCardShapes）。
            val cornerHolder = remember(drawerState) { intArrayOf(-1) }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .offset { IntOffset((pushPx * drawerState.progress).roundToInt(), 0) }
                    .padding(top = hingeTopPadding)
                    .graphicsLayer {
                        val progress = drawerState.progress
                        // 收起态全屏；抽屉打开时内容区缩成一张悬浮卡片（上下留白）。
                        val scale = 1f - 0.03f * progress
                        scaleX = scale
                        scaleY = scale
                        // 以左边缘为锚点：卡片左边缘不因缩放右移，和抽屉之间只留 DrawerGap。
                        transformOrigin = TransformOrigin(0f, 0.5f)
                        // 半径量化到整 dp：同一实例复用，图层轮廓不再逐帧重建。
                        val radius = (DrawerCorner.value * progress).roundToInt()
                            .coerceIn(0, DrawerCornerSteps)
                        if (cornerHolder[0] != radius) {
                            cornerHolder[0] = radius
                            shape = DrawerCardShapes[radius]
                        }
                        clip = true
                    }
                    .background(MaterialTheme.colorScheme.surfaceContainerLow),
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
    }
}

/**
 * 双栏：左侧列表常驻，右侧内容。垂直铰链时把分栏线对到铰链左侧，内容不跨折痕。
 *
 * 列表栏可收起（[listCollapsed]）：收起后内容区占满整宽，平板竖屏看长文/代码时不被目录挤行。
 */
@Composable
private fun TwoPaneLayout(
    window: ProferWindowSize,
    hinge: ProferHinge?,
    drawerState: PocketDrawerState,
    listCollapsed: Boolean,
    drawerContent: @Composable () -> Unit,
    content: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    // 从单栏转过来时可能还留着一个打开的抽屉：列表已经常驻，状态必须归零。
    LaunchedEffect(drawerState) {
        if (drawerState.isOpen) drawerState.close()
    }

    val spec = resolveTwoPaneSpec(window, hinge)
    // 宽度而不是平移：内容区要真的变宽（正文行宽、表格列数都跟着变），只推出去一块空白没有意义。
    val columnWidth by animateDpAsState(
        targetValue = resolveTwoPaneListColumn(spec, listCollapsed),
        animationSpec = tween(durationMillis = ListPaneToggleMs, easing = FastOutSlowInEasing),
        label = "twoPaneListColumn",
    )

    Row(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        // 整栏（列表 + 分隔线 + 铰链留白）一起被裁掉：内层按固定宽度排版，
        // 动画期间会话列表不重新换行，看起来是「滑出去」而不是「被挤扁」。
        Box(
            Modifier
                .width(columnWidth)
                .fillMaxHeight()
                .clipToBounds(),
        ) {
            // 完全收起后不再组合几百条会话行：宽度为 0 时没有可见内容，没必要留着测量。
            if (columnWidth > 0.dp) {
                Row(Modifier.fillMaxHeight()) {
                    Box(Modifier.width(spec.listWidth).fillMaxHeight()) { drawerContent() }
                    // 一条 hairline 分开目录与内容：双栏靠色阶已经能分，但列表行滚动时会贴到正文上。
                    Box(
                        Modifier
                            .width(ProferSizes.hairline)
                            .fillMaxHeight()
                            .background(MaterialTheme.colorScheme.outlineVariant),
                    )
                    if (spec.hingeGap > 0.dp) {
                        Spacer(Modifier.width(spec.hingeGap))
                    }
                }
            }
        }
        Box(Modifier.weight(1f).fillMaxHeight()) { content() }
    }
}

/** 列表栏收起/展开的动画时长：和抽屉回弹同一量级，快得不像「转场」，慢得看得清去向。 */
private const val ListPaneToggleMs = 240

/**
 * 列表栏占用的总宽度（列表 + 分隔线 + 铰链留白）。收起时为 0。
 * 纯函数，便于单测：三个分量都必须随栏一起消失，否则收起后左侧会残留一条 1dp 竖线或 16dp 空白。
 */
internal fun resolveTwoPaneListColumn(spec: TwoPaneSpec, collapsed: Boolean): Dp =
    if (collapsed) 0.dp else spec.listWidth + ProferSizes.hairline + spec.hingeGap

/** 双栏几何：列表宽度与铰链留白。纯函数，便于单测折叠屏的各种铰链位置。 */
internal data class TwoPaneSpec(val listWidth: androidx.compose.ui.unit.Dp, val hingeGap: androidx.compose.ui.unit.Dp)

internal fun resolveTwoPaneSpec(window: ProferWindowSize, hinge: ProferHinge?): TwoPaneSpec {
    val verticalHinge = hinge?.takeIf { it.vertical && it.isSeparating }
    // 铰链太靠边时按普通双栏宽度，否则列表栏会被压成一条缝；太靠中间时也不对齐，
    // 否则内容栏会被挤到不足 240dp。
    val hingeListWidth = verticalHinge
        ?.takeIf { it.start >= window.width * 0.25f && it.start <= window.width * 0.6f }
        ?.start
    return TwoPaneSpec(
        listWidth = hingeListWidth ?: window.listPaneWidth,
        hingeGap = if (hingeListWidth != null) verticalHinge!!.size else 0.dp,
    )
}

/**
 * 水平拖拽：只有在确认横向主导后才消费事件；确认是纵向滚动后立即退出，
 * 让列表的垂直滚动完全不受影响。横向判定见 [claimsHorizontalDrag]。
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
        // 手指速度的滑动平均：单帧速度抖动很大，直接用来算增益会让抽屉一抽一抽。
        var smoothedSpeed = 0f
        while (true) {
            val event = awaitPointerEvent()
            val change = event.changes.firstOrNull { it.id == down.id } ?: break
            tracker.addPosition(change.uptimeMillis, change.position)
            val frameSpeed = abs(tracker.calculateVelocity().x)
            smoothedSpeed += (frameSpeed - smoothedSpeed) * DragSpeedSmoothing
            // 用「相对按下点的净位移」而不是逐帧 delta 相加：抬手事件不带位移，
            // 极快的一甩可能整段只来一个 down + up，按 delta 累加会算成 0。
            val netX = change.position.x - down.position.x
            val netY = change.position.y - down.position.y
            val up = change.changedToUpIgnoreConsumed()
            if (!dragging) {
                // isConsumed：横向内容（代码块、横向列表）自己滑时不能抢过来。
                // 只看移动事件：抬手被子组件当点击消费不算「它接管了手势」。
                if (!up && change.isConsumed) childOwnsGesture = true
                val horizontal = !change.isConsumed && claimsHorizontalDrag(netX, netY, viewConfiguration.touchSlop)
                if (horizontal) {
                    drawerState.beginDrag()
                    dragging = true
                } else if (up) {
                    // 一甩到底、中间没有 move 采样（掉帧时 down/up 会并到同一帧）：
                    // 抬手时按总位移与速度再判一次。这里不能看 up 的 isConsumed——
                    // 子组件的 clickable 会把这个 up 当成一次点击消费掉。
                    if (!childOwnsGesture && claimsHorizontalDrag(netX, netY, viewConfiguration.touchSlop)) {
                        change.consume()
                        drawerState.beginDrag()
                        drawerState.endDrag(tracker.calculateVelocity().x, netX, togglePx, flingPx, widthPx)
                    }
                    break
                } else if (abs(netY) > viewConfiguration.touchSlop) {
                    // 纵向滚动：不消费、不吸附，把事件完整留给列表。
                    break
                }
            }
            if (up) {
                if (dragging) {
                    drawerState.endDrag(tracker.calculateVelocity().x, netX, togglePx, flingPx, widthPx)
                }
                break
            }
            if (dragging) {
                change.consume()
                drawerState.drag(netX - appliedX, widthPx, smoothedSpeed)
                appliedX = netX
            }
        }
    }
}
