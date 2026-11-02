package com.profer.pocket.app

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.profer.pocket.designsystem.theme.ProferDarkColors
import com.profer.pocket.designsystem.theme.ProferLightColors
import org.junit.Assert.assertTrue
import org.junit.Test

/** 设计角色可读性是契约，不因页面换色或换主题而退化。 */
class UiDesignTokensTest {
    @Test fun light_text_and_actions_meet_aa() = verify(ProferLightColors)
    @Test fun dark_text_and_actions_meet_aa() = verify(ProferDarkColors)

    private fun verify(colors: ColorScheme) {
        listOf(
            colors.onSurface to colors.background,
            colors.onSurface to colors.surfaceContainerLow,
            colors.onSurfaceVariant to colors.surface,
            colors.onSurfaceVariant to colors.surfaceContainerHigh,
            colors.onPrimary to colors.primary,
            colors.onPrimaryContainer to colors.primaryContainer,
            colors.onErrorContainer to colors.errorContainer,
        ).forEach { (foreground, background) ->
            val ratio = contrast(foreground, background)
            assertTrue("对比度 $ratio 低于 AA 正文阈值 4.5", ratio >= 4.5f)
        }
    }

    private fun contrast(a: Color, b: Color): Float {
        val first = a.luminance()
        val second = b.luminance()
        return (maxOf(first, second) + 0.05f) / (minOf(first, second) + 0.05f)
    }
}
