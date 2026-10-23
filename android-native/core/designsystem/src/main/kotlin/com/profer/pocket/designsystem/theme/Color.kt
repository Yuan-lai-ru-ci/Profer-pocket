package com.profer.pocket.designsystem.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

// Profer 的底座不是纯中性灰，而是一层很浅的暖纸色；
// 深色底座取自启动底色 #0B0D0E，避免大面积纯黑。
private val Paper = Color(0xFFF7F4EF)
private val PaperLowest = Color(0xFFFFFDFA)
private val PaperLow = Color(0xFFFCF9F3)
private val PaperContainer = Color(0xFFF2EEE6)
private val PaperContainerHigh = Color(0xFFECE6DA)
private val PaperContainerHighest = Color(0xFFE5DED1)
private val Ink = Color(0xFF191816)
private val InkMuted = Color(0xFF6C6860)
private val Outline = Color(0xFFD6CFC2)
private val OutlineVariant = Color(0xFFE7E1D6)
private val Slate = Color(0xFF4F5B68)

private val Night = Color(0xFF0B0D0E)
private val NightLowest = Color(0xFF070809)
private val NightLow = Color(0xFF141718)
private val NightContainer = Color(0xFF1A1E20)
private val NightContainerHigh = Color(0xFF222729)
private val NightContainerHighest = Color(0xFF2A3032)
private val NightInk = Color(0xFFEDEAE3)
private val NightMuted = Color(0xFF9C9890)
private val NightOutline = Color(0xFF3D4246)
private val NightOutlineVariant = Color(0xFF262B2D)
private val NightSlate = Color(0xFF93A9BA)

val ProferLightColors = lightColorScheme(
    primary = Ink,
    onPrimary = PaperLowest,
    primaryContainer = PaperContainerHigh,
    onPrimaryContainer = Ink,
    inversePrimary = NightInk,
    secondary = Color(0xFF55524B),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = PaperContainer,
    onSecondaryContainer = Color(0xFF2A2824),
    tertiary = Slate,
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFDDE5EA),
    onTertiaryContainer = Color(0xFF1D2830),
    background = Paper,
    onBackground = Ink,
    surface = Paper,
    onSurface = Ink,
    surfaceVariant = PaperContainer,
    onSurfaceVariant = InkMuted,
    surfaceTint = Ink,
    inverseSurface = Ink,
    inverseOnSurface = Paper,
    outline = Outline,
    outlineVariant = OutlineVariant,
    scrim = Color(0xFF000000),
    surfaceBright = PaperLowest,
    surfaceDim = PaperContainerHighest,
    surfaceContainerLowest = PaperLowest,
    surfaceContainerLow = PaperLow,
    surfaceContainer = PaperContainer,
    surfaceContainerHigh = PaperContainerHigh,
    surfaceContainerHighest = PaperContainerHighest,
    error = Color(0xFFB0443C),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFF6E0DC),
    onErrorContainer = Color(0xFF5C1A15),
)

val ProferDarkColors = darkColorScheme(
    primary = NightInk,
    onPrimary = Color(0xFF15161A),
    primaryContainer = NightContainerHighest,
    onPrimaryContainer = NightInk,
    inversePrimary = Ink,
    secondary = Color(0xFFC9C5BC),
    onSecondary = Color(0xFF1A1B1D),
    secondaryContainer = NightContainerHigh,
    onSecondaryContainer = NightInk,
    tertiary = NightSlate,
    onTertiary = Color(0xFF12202A),
    tertiaryContainer = Color(0xFF24343F),
    onTertiaryContainer = Color(0xFFD6E5EF),
    background = Night,
    onBackground = NightInk,
    surface = Night,
    onSurface = NightInk,
    surfaceVariant = NightContainer,
    onSurfaceVariant = NightMuted,
    surfaceTint = NightInk,
    inverseSurface = NightInk,
    inverseOnSurface = Night,
    outline = NightOutline,
    outlineVariant = NightOutlineVariant,
    scrim = Color(0xFF000000),
    surfaceBright = NightContainerHigh,
    surfaceDim = NightLowest,
    surfaceContainerLowest = NightLowest,
    surfaceContainerLow = NightLow,
    surfaceContainer = NightContainer,
    surfaceContainerHigh = NightContainerHigh,
    surfaceContainerHighest = NightContainerHighest,
    error = Color(0xFFE08379),
    onError = Color(0xFF3B0C08),
    errorContainer = Color(0xFF4A211D),
    onErrorContainer = Color(0xFFF6DAD5),
)

/**
 * M3 配色之外，Profer 需要表达「运行 / 成功 / 警告」三种状态。
 * 状态色只用于状态，不参与品牌表达。
 */
@Immutable
data class ProferSemanticColors(
    val success: Color,
    val successContainer: Color,
    val onSuccessContainer: Color,
    val warning: Color,
    val warningContainer: Color,
    val onWarningContainer: Color,
    val codeBackground: Color,
    val codeForeground: Color,
)

val ProferLightSemantics = ProferSemanticColors(
    success = Color(0xFF3D7A57),
    successContainer = Color(0xFFDDEBE1),
    onSuccessContainer = Color(0xFF17301F),
    warning = Color(0xFFA9772C),
    warningContainer = Color(0xFFF4E7CF),
    onWarningContainer = Color(0xFF3F2A08),
    codeBackground = Color(0xFF1E2228),
    codeForeground = Color(0xFFE8E4DC),
)

val ProferDarkSemantics = ProferSemanticColors(
    success = Color(0xFF7FBF9A),
    successContainer = Color(0xFF1E3328),
    onSuccessContainer = Color(0xFFCDEBD9),
    warning = Color(0xFFD8AE6A),
    warningContainer = Color(0xFF352A17),
    onWarningContainer = Color(0xFFF3E1C2),
    codeBackground = Color(0xFF101315),
    codeForeground = Color(0xFFE3DFD7),
)

val LocalProferSemanticColors = staticCompositionLocalOf { ProferLightSemantics }
