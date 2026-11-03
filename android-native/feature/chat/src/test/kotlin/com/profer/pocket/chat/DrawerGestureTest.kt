package com.profer.pocket.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 抽屉横向手势的接管判定。
 *
 * 目标是「斜着划也能出抽屉，但纯上下滑永远不抢列表」：
 * 旧规则要求横移比竖移大 1.2 倍，45° 斜划时永远抢不过列表的竖向滚动，
 * 手感上就是「想开抽屉却滚了列表」。
 */
class DrawerGestureTest {

    /** Pixel 7 / 480dpi 下 ViewConfiguration.touchSlop ≈ 24px。 */
    private val slop = 24f

    @Test
    fun `纯横向立即接管`() {
        assertTrue(claimsHorizontalDrag(netX = 60f, netY = 0f, touchSlop = slop))
        assertTrue(claimsHorizontalDrag(netX = -60f, netY = 0f, touchSlop = slop))
    }

    @Test
    fun `四十五度斜划也能接管`() {
        assertTrue(claimsHorizontalDrag(netX = 40f, netY = 40f, touchSlop = slop))
        assertTrue(claimsHorizontalDrag(netX = 40f, netY = -40f, touchSlop = slop))
    }

    @Test
    fun `竖移略大于横移时不抢，留给列表`() {
        assertFalse(claimsHorizontalDrag(netX = 30f, netY = 45f, touchSlop = slop))
        assertFalse(claimsHorizontalDrag(netX = 10f, netY = 120f, touchSlop = slop))
    }

    @Test
    fun `未到标准 slop 时明显横向可提前锁定`() {
        // 18 > 24*0.65=15.6，且 18 > 8*1.5=12：提前锁，避免列表先过线。
        assertTrue(claimsHorizontalDrag(netX = 18f, netY = 8f, touchSlop = slop))
    }

    @Test
    fun `轻微抖动不触发接管`() {
        assertFalse(claimsHorizontalDrag(netX = 12f, netY = 3f, touchSlop = slop))
        assertFalse(claimsHorizontalDrag(netX = 0f, netY = 0f, touchSlop = slop))
    }

    @Test
    fun `旧规则会漏掉的边界在新规则下接管`() {
        // 横移 30、竖移 25：旧规则 30 > 25*1.2=30 不成立；新规则 30 >= 25 成立。
        assertTrue(claimsHorizontalDrag(netX = 30f, netY = 25f, touchSlop = slop))
    }

    @Test
    fun `抽屉内容随进度淡入，避免闪一下`() {
        assertEquals(0f, drawerContentAlpha(0f), 0.001f)
        assertEquals(0f, drawerContentAlpha(-0.2f), 0.001f)
        assertEquals(0.5f, drawerContentAlpha(0.1f), 0.001f)
        assertEquals(1f, drawerContentAlpha(0.2f), 0.001f)
        assertEquals(1f, drawerContentAlpha(1f), 0.001f)
        // 只拉一点点时几乎不可见：这正是「小扯动不闪现」的关键。
        assertTrue(drawerContentAlpha(0.02f) < 0.2f)
    }

    @Test
    fun `慢拖按一比一跟随`() {
        assertEquals(1f, drawerDragGain(0f), 0.001f)
        assertEquals(1f, drawerDragGain(150f), 0.001f)
        assertEquals(1f, drawerDragGain(-150f), 0.001f)
    }

    @Test
    fun `快划放大到上限`() {
        assertEquals(1.5f, drawerDragGain(1200f), 0.001f)
        assertEquals(1.5f, drawerDragGain(9000f), 0.001f)
    }

    @Test
    fun `中间速度线性过渡`() {
        assertEquals(1.25f, drawerDragGain(675f), 0.001f)
        assertTrue(drawerDragGain(900f) > drawerDragGain(400f))
    }
}
