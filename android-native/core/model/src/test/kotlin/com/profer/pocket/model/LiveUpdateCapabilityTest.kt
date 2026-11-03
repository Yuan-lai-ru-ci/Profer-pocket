package com.profer.pocket.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveUpdateCapabilityTest {

    @Test
    fun `Android 15 及以下不支持`() {
        val capability = LiveUpdateCapabilities.resolve(
            sdkInt = 35,
            manufacturer = "OPPO",
            canPostPromoted = true,
        )
        assertFalse(capability.supported)
        assertFalse(capability.requiresUserSwitch)
        assertEquals(LiveUpdateSurface.FluidCloud, capability.surface)
    }

    @Test
    fun `Android 16 且系统允许时直接可用`() {
        val capability = LiveUpdateCapabilities.resolve(
            sdkInt = 36,
            manufacturer = "OPPO",
            canPostPromoted = true,
        )
        assertTrue(capability.supported)
        assertFalse(capability.requiresUserSwitch)
    }

    @Test
    fun `系统明确不允许时需要用户去开开关`() {
        val capability = LiveUpdateCapabilities.resolve(
            sdkInt = 37,
            manufacturer = "OPPO",
            canPostPromoted = false,
        )
        assertTrue(capability.supported)
        assertTrue(capability.requiresUserSwitch)
    }

    @Test
    fun `探测失败时不催用户去设置`() {
        val capability = LiveUpdateCapabilities.resolve(
            sdkInt = 36,
            manufacturer = "samsung",
            canPostPromoted = null,
        )
        assertTrue(capability.supported)
        assertFalse(capability.requiresUserSwitch)
    }

    @Test
    fun `厂商别名映射到各自的展示形态`() {
        fun surface(maker: String) = LiveUpdateCapabilities.resolve(36, maker, true).surface

        assertEquals(LiveUpdateSurface.FluidCloud, surface("OPPO"))
        assertEquals(LiveUpdateSurface.FluidCloud, surface("OnePlus"))
        assertEquals(LiveUpdateSurface.FluidCloud, surface("realme"))
        assertEquals(LiveUpdateSurface.HyperIsland, surface("Xiaomi"))
        assertEquals(LiveUpdateSurface.HyperIsland, surface("Redmi"))
        assertEquals(LiveUpdateSurface.HyperIsland, surface("POCO"))
        assertEquals(LiveUpdateSurface.AtomIsland, surface("vivo"))
        assertEquals(LiveUpdateSurface.AtomIsland, surface("iQOO"))
        assertEquals(LiveUpdateSurface.MagicCapsule, surface("HONOR"))
        assertEquals(LiveUpdateSurface.NowBar, surface("samsung"))
        assertEquals(LiveUpdateSurface.LiveUpdate, surface("Google"))
    }

    @Test
    fun `厂商名大小写与空格不影响判断`() {
        assertEquals(
            LiveUpdateSurface.FluidCloud,
            LiveUpdateCapabilities.resolve(36, " oppo ", true).surface,
        )
        assertEquals(
            LiveUpdateSurface.MagicCapsule,
            LiveUpdateCapabilities.resolve(36, "Honor", true).surface,
        )
    }

    @Test
    fun `各家叫法只用于文案`() {
        assertEquals("流体云", LiveUpdateSurface.FluidCloud.label)
        assertEquals("超级岛", LiveUpdateSurface.HyperIsland.label)
        assertEquals("原子岛", LiveUpdateSurface.AtomIsland.label)
        assertEquals("灵动胶囊", LiveUpdateSurface.MagicCapsule.label)
        assertEquals("实时通知", LiveUpdateSurface.NowBar.label)
        assertEquals("实时活动", LiveUpdateSurface.LiveUpdate.label)
    }
}
