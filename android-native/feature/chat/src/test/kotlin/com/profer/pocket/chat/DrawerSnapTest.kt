package com.profer.pocket.chat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 抽屉松手吸附的判定。
 *
 * 回归背景：旧实现只看 `progress >= 0.5`（进度 = 位移 / 屏宽），
 * 360dp 宽的手机要横拖 180dp 才切换，用户反馈「要滑很远才出侧边栏、滑很远才回主界面」。
 * 现在改成 64dp 位移阈值 + 400dp/s 甩动速度（模拟器 320dpi 下 1dp = 2px）。
 */
class DrawerSnapTest {

    private val togglePx = 128f // 64dp @ 2x
    private val flingPx = 800f // 400dp/s @ 2x

    private fun snap(
        velocityX: Float = 0f,
        distancePx: Float = 0f,
        startProgress: Float = 0f,
    ) = resolveDrawerTarget(
        velocityX = velocityX,
        distancePx = distancePx,
        toggleDistancePx = togglePx,
        flingVelocityPx = flingPx,
        startProgress = startProgress,
    )

    @Test
    fun `向右拖过 64dp 就打开`() {
        assertTrue(snap(distancePx = 130f))
        assertTrue(snap(distancePx = 260f))
    }

    @Test
    fun `向右拖不到 64dp 时保持关闭`() {
        assertFalse(snap(distancePx = 40f))
        assertFalse(snap(distancePx = 127f))
    }

    @Test
    fun `关闭态下拖不到阈值不会因为拖过屏宽四分之一就打开`() {
        // 旧阈值在 360dp 屏上约 180dp：60dp 的拖动曾经完全没反应，现在也不会误开。
        assertFalse(snap(distancePx = 120f, startProgress = 0f))
    }

    @Test
    fun `打开态向左拖过 64dp 就收起`() {
        assertFalse(snap(distancePx = -130f, startProgress = 1f))
        assertFalse(snap(distancePx = -260f, startProgress = 1f))
    }

    @Test
    fun `打开态向左拖不到 64dp 时回到打开`() {
        assertTrue(snap(distancePx = -40f, startProgress = 1f))
        assertTrue(snap(distancePx = -127f, startProgress = 1f))
    }

    @Test
    fun `快速右甩即使位移很小也打开`() {
        assertTrue(snap(velocityX = 801f, distancePx = 20f))
    }

    @Test
    fun `快速左甩即使位移很小也收起`() {
        assertFalse(snap(velocityX = -801f, distancePx = -20f, startProgress = 1f))
    }

    @Test
    fun `速度不够时仍然按位移判定`() {
        assertFalse(snap(velocityX = 500f, distancePx = 20f))
        assertTrue(snap(velocityX = 500f, distancePx = 200f))
    }

    @Test
    fun `几乎没动的点击不会改变抽屉状态`() {
        assertFalse(snap(distancePx = 2f, startProgress = 0f))
        assertTrue(snap(distancePx = -2f, startProgress = 1f))
    }
}
