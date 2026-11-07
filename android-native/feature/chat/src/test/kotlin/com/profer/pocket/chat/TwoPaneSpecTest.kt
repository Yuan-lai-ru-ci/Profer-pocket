package com.profer.pocket.chat

import androidx.compose.ui.unit.dp
import com.profer.pocket.designsystem.adaptive.ProferHinge
import com.profer.pocket.designsystem.adaptive.ProferPaneGap
import com.profer.pocket.designsystem.adaptive.ProferTwoPaneMinWidth
import com.profer.pocket.designsystem.adaptive.ProferWindowSize
import com.profer.pocket.designsystem.adaptive.ProferWindowWidth
import com.profer.pocket.designsystem.adaptive.landscapeDrawerWidth
import com.profer.pocket.designsystem.adaptive.windowWidthClass
import androidx.compose.ui.unit.Dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TwoPaneSpecTest {

    private fun window(widthDp: Float, heightDp: Float = 900f) = ProferWindowSize(
        width = widthDp.dp,
        height = heightDp.dp,
        widthClass = windowWidthClass(widthDp.dp),
    )

    /** 比例算出来的宽度带小数（740 × 0.28 = 207.2），比较时给半像素容差。 */
    private fun assertDp(expected: Float, actual: Dp) =
        assertEquals(expected.toDouble(), actual.value.toDouble(), 0.6)

    @Test fun phone_stays_single_pane() {
        assertEquals(ProferWindowWidth.Compact, window(400f).widthClass)
    }

    @Test fun narrow_landscape_phone_stays_single_pane() {
        // 640x360 的小屏横屏：按 M3 断点会分栏，但分完内容区只剩 345dp，比竖屏还窄。
        val narrow = window(640f, 360f)
        assertEquals(ProferWindowWidth.Medium, narrow.widthClass)
        assertFalse(narrow.isTwoPane)
        assertTrue(narrow.isShort)
        assertTrue(narrow.isLandscape)
    }

    @Test fun two_pane_starts_when_conversation_keeps_420dp() {
        assertFalse(window(ProferTwoPaneMinWidth.value - 1f).isTwoPane)
        assertTrue(window(ProferTwoPaneMinWidth.value).isTwoPane)
    }

    @Test fun conversation_keeps_two_thirds_to_four_fifths() {
        // 从双栏门槛到平板横屏：对话区始终占 2/3–4/5，侧栏永远是少数派。
        var width = ProferTwoPaneMinWidth.value
        while (width <= 1600f) {
            val w = window(width)
            val conversation = width - w.listPaneWidth.value - ProferPaneGap.value
            val share = conversation / width
            assertTrue("$width dp 对话区只剩 $share", share >= 2f / 3f - 0.001f)
            assertTrue("$width dp 对话区占 $share", share <= 4f / 5f + 0.001f)
            width += 7f
        }
    }

    @Test fun tablet_keeps_conversation_at_three_quarters() {
        // 800dp：侧栏按 28% 取 224dp，对话区 568dp（71%）。
        val spec = resolveTwoPaneSpec(window(800f), null)
        assertDp(224f, spec.listWidth)
        assertEquals(0.dp, spec.hingeGap)
    }

    @Test fun unfolded_fold_gives_conversation_the_majority() {
        // 折叠屏展开 740dp：侧栏 207dp，对话区 525dp（71%），不再平分。
        assertEquals(ProferWindowWidth.Medium, window(740f).widthClass)
        assertDp(207.2f, window(740f).listPaneWidth)
        assertDp(207.2f, resolveTwoPaneSpec(window(740f), null).listWidth)
    }

    @Test fun narrow_two_pane_gives_content_the_full_share() {
        // 720dp：侧栏 202dp，对话区 510dp（71%）。
        assertDp(201.6f, window(720f).listPaneWidth)
        assertTrue(window(720f).isTwoPane)
    }

    @Test fun vertical_hinge_splits_at_the_fold() {
        val hinge = ProferHinge(vertical = true, isSeparating = true, size = 16.dp, start = 336.dp)
        val spec = resolveTwoPaneSpec(window(740f), hinge)
        assertEquals(336.dp, spec.listWidth)
        assertEquals(16.dp, spec.hingeGap)
    }

    @Test fun hinge_near_the_edge_is_ignored() {
        // 铰链在 180dp（< 窗宽 25%）：跟着铰链走列表会窄到摆不下一行图标。
        val hinge = ProferHinge(vertical = true, isSeparating = true, size = 16.dp, start = 180.dp)
        val spec = resolveTwoPaneSpec(window(740f), hinge)
        assertDp(207.2f, spec.listWidth)
        assertEquals(0.dp, spec.hingeGap)
    }

    @Test fun horizontal_hinge_does_not_split_panes() {
        // 横向半折叠走单栏（TwoPaneLayout 不会被调用），纯函数也不应把列表压窄。
        val hinge = ProferHinge(vertical = false, isSeparating = true, size = 16.dp, start = 400.dp)
        val spec = resolveTwoPaneSpec(window(740f), hinge)
        assertDp(207.2f, spec.listWidth)
        assertEquals(0.dp, spec.hingeGap)
    }

    @Test fun expanded_window_caps_the_list_at_desktop_width() {
        // 平板横屏 1280dp 上限 320dp：对话区 952dp（74%），再宽也不给列表。
        val spec = resolveTwoPaneSpec(window(1280f), null)
        assertDp(320f, spec.listWidth)
    }

    @Test fun collapsed_list_column_takes_no_width() {
        // 收起后必须一个像素都不占：残留的 1dp 分隔线在宽屏上就是一条悬浮竖线。
        val spec = resolveTwoPaneSpec(window(800f), null)
        assertEquals(0.dp, resolveTwoPaneListColumn(spec, collapsed = true))
    }

    @Test fun expanded_list_column_includes_hinge_and_pane_gap() {
        val hinge = ProferHinge(vertical = true, isSeparating = true, size = 16.dp, start = 336.dp)
        val spec = resolveTwoPaneSpec(window(740f), hinge)
        assertEquals(
            spec.listWidth + spec.hingeGap + ProferPaneGap,
            resolveTwoPaneListColumn(spec, collapsed = false),
        )
    }

    @Test fun landscape_drawer_is_a_fixed_sidebar() {
        // 横屏不再「整屏推卡」：640dp 宽给 213dp（≤ 1/3）；480dp 兜底 200dp。
        assertDp(213.3f, landscapeDrawerWidth(640.dp))
        assertDp(200f, landscapeDrawerWidth(480.dp))
    }
}
