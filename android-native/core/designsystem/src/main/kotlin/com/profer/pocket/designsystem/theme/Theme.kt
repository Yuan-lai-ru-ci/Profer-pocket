package com.profer.pocket.designsystem.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable

@Composable
fun ProferTheme(
    darkTheme: Boolean,
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) ProferDarkColors else ProferLightColors,
        typography = ProferTypography,
        shapes = ProferShapes,
    ) {
        CompositionLocalProvider(
            LocalProferSemanticColors provides if (darkTheme) ProferDarkSemantics else ProferLightSemantics,
            LocalProferFileBadges provides if (darkTheme) ProferDarkFileBadges else ProferLightFileBadges,
            content = content,
        )
    }
}

object ProferThemeExtras {
    val semantic: ProferSemanticColors
        @Composable @ReadOnlyComposable get() = LocalProferSemanticColors.current

    /** 文件类型徽标配色，亮暗各一套。 */
    val fileBadges: ProferFileBadgeColors
        @Composable @ReadOnlyComposable get() = LocalProferFileBadges.current
}
