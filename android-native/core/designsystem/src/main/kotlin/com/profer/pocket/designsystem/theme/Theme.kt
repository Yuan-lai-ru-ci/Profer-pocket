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
            content = content,
        )
    }
}

object ProferThemeExtras {
    val semantic: ProferSemanticColors
        @Composable @ReadOnlyComposable get() = LocalProferSemanticColors.current
}
