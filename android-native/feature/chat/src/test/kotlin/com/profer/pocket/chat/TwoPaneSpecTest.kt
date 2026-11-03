package com.profer.pocket.chat

import androidx.compose.ui.unit.dp
import com.profer.pocket.designsystem.adaptive.ProferHinge
import com.profer.pocket.designsystem.adaptive.ProferWindowSize
import com.profer.pocket.designsystem.adaptive.ProferWindowWidth
import com.profer.pocket.designsystem.adaptive.windowWidthClass
import org.junit.Assert.assertEquals
import org.junit.Test

class TwoPaneSpecTest {

    private fun window(widthDp: Float) = ProferWindowSize(
        width = widthDp.dp,
        height = 900.dp,
        widthClass = windowWidthClass(widthDp.dp),
    )

    @Test fun phone_stays_single_pane() {
        assertEquals(ProferWindowWidth.Compact, window(400f).widthClass)
    }

    @Test fun tablet_without_hinge_uses_default_list_width() {
        val spec = resolveTwoPaneSpec(window(800f), null)
        assertEquals(320.dp, spec.listWidth)
        assertEquals(0.dp, spec.hingeGap)
    }

    @Test fun unfolded_fold_gets_full_320dp_list() {
        // 折叠屏展开约 740dp：320dp 是「品牌 + 模式 + 搜索 + 新建」一行放得下的下限。
        assertEquals(ProferWindowWidth.Medium, window(740f).widthClass)
        assertEquals(320.dp, resolveTwoPaneSpec(window(740f), null).listWidth)
    }

    @Test fun vertical_hinge_splits_at_the_fold() {
        val hinge = ProferHinge(vertical = true, isSeparating = true, size = 16.dp, start = 336.dp)
        val spec = resolveTwoPaneSpec(window(740f), hinge)
        assertEquals(336.dp, spec.listWidth)
        assertEquals(16.dp, spec.hingeGap)
    }

    @Test fun hinge_near_the_edge_is_ignored() {
        val hinge = ProferHinge(vertical = true, isSeparating = true, size = 16.dp, start = 80.dp)
        val spec = resolveTwoPaneSpec(window(740f), hinge)
        assertEquals(320.dp, spec.listWidth)
        assertEquals(0.dp, spec.hingeGap)
    }

    @Test fun horizontal_hinge_does_not_split_panes() {
        // 横向半折叠走单栏（TwoPaneLayout 不会被调用），纯函数也不应把列表压窄。
        val hinge = ProferHinge(vertical = false, isSeparating = true, size = 16.dp, start = 400.dp)
        val spec = resolveTwoPaneSpec(window(740f), hinge)
        assertEquals(320.dp, spec.listWidth)
        assertEquals(0.dp, spec.hingeGap)
    }

    @Test fun expanded_window_uses_wider_list() {
        val spec = resolveTwoPaneSpec(window(1280f), null)
        assertEquals(360.dp, spec.listWidth)
    }

    @Test fun collapsed_list_column_takes_no_width() {
        // 收起后必须一个像素都不占：残留的 1dp 分隔线在宽屏上就是一条悬浮竖线。
        val spec = resolveTwoPaneSpec(window(800f), null)
        assertEquals(0.dp, resolveTwoPaneListColumn(spec, collapsed = true))
    }

    @Test fun expanded_list_column_includes_divider_and_hinge() {
        val hinge = ProferHinge(vertical = true, isSeparating = true, size = 16.dp, start = 336.dp)
        val spec = resolveTwoPaneSpec(window(740f), hinge)
        assertEquals(
            spec.listWidth + com.profer.pocket.designsystem.theme.ProferSizes.hairline + spec.hingeGap,
            resolveTwoPaneListColumn(spec, collapsed = false),
        )
    }
}
