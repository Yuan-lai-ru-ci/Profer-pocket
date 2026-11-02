package com.profer.pocket.designsystem.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Profer 移动端调色板：陶土品牌 + 暖中性纸面，浅深色使用相同语义角色。
 * 画布 < 内容面 < 浮动操作面；高层级不等于更深的填充。
 * 页面不得自建透明文字或主题色，语义状态使用 ProferThemeExtras.semantic。
 * token 数值以此文件为准；不再直接等同于桌面皮肤的 HSL。
 */

private val Paper = Color(0xFFF7F5F2)
private val PaperLowest = Color(0xFFFFFDFC)
private val PaperLow = Color(0xFFFCFAF7)
private val PaperContainer = Color(0xFFEFECE7)
private val PaperContainerHigh = Color(0xFFE8E3DC)
private val PaperContainerHighest = Color(0xFFDFD9D1)
private val PaperDim = Color(0xFFDBDAD6)
private val Ink = Color(0xFF322F2A)
private val InkMuted = Color(0xFF66635C)
private val Outline = Color(0xFFC6BFB5)
private val OutlineVariant = Color(0xFFE7E1D9)
private val Clay = Color(0xFF846558)
private val ClayDeep = Color(0xFF53382D)
private val ClayContainer = Color(0xFFEDE3DE)

private val Night = Color(0xFF191715)
private val NightLowest = Color(0xFF121110)
private val NightLow = Color(0xFF211F1C)
private val NightContainer = Color(0xFF292724)
private val NightContainerHigh = Color(0xFF2E2C28)
private val NightContainerHighest = Color(0xFF37342F)
private val NightInk = Color(0xFFECEBE9)
private val NightMuted = Color(0xFFAAA7A1)
private val NightOutline = Color(0xFF4A4640)
private val NightOutlineVariant = Color(0xFF312F2B)
private val NightClay = Color(0xFFB4998E)
private val NightClayDeep = Color(0xFF241410)
private val NightClayContainer = Color(0xFF493127)

val ProferLightColors = lightColorScheme(
    primary = Clay,
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = ClayContainer,
    onPrimaryContainer = ClayDeep,
    inversePrimary = NightClay,
    secondary = PaperContainer,
    onSecondary = Color(0xFF3D3A34),
    secondaryContainer = PaperContainerHighest,
    onSecondaryContainer = Color(0xFF464239),
    tertiary = Clay,
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = ClayContainer,
    onTertiaryContainer = ClayDeep,
    background = Paper,
    onBackground = Ink,
    surface = Paper,
    onSurface = Ink,
    surfaceVariant = PaperContainer,
    onSurfaceVariant = InkMuted,
    surfaceTint = Clay,
    inverseSurface = Ink,
    inverseOnSurface = Paper,
    outline = Outline,
    outlineVariant = OutlineVariant,
    scrim = Color(0xFF000000),
    surfaceBright = PaperLowest,
    surfaceDim = PaperDim,
    surfaceContainerLowest = PaperLowest,
    surfaceContainerLow = PaperLow,
    surfaceContainer = PaperContainer,
    surfaceContainerHigh = PaperContainerHigh,
    surfaceContainerHighest = PaperContainerHighest,
    error = Color(0xFFB93A32),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFF6DFDB),
    onErrorContainer = Color(0xFF5C1A15),
)

val ProferDarkColors = darkColorScheme(
    primary = NightClay,
    onPrimary = NightClayDeep,
    primaryContainer = NightClayContainer,
    onPrimaryContainer = Color(0xFFF0DCD3),
    inversePrimary = Clay,
    secondary = Color(0xFFC6C2BB),
    onSecondary = Color(0xFF2A2723),
    secondaryContainer = NightContainerHigh,
    onSecondaryContainer = NightInk,
    tertiary = NightClay,
    onTertiary = NightClayDeep,
    tertiaryContainer = NightClayContainer,
    onTertiaryContainer = Color(0xFFF0DCD3),
    background = Night,
    onBackground = NightInk,
    surface = Night,
    onSurface = NightInk,
    surfaceVariant = NightContainer,
    onSurfaceVariant = NightMuted,
    surfaceTint = NightClay,
    inverseSurface = NightInk,
    inverseOnSurface = Night,
    outline = NightOutline,
    outlineVariant = NightOutlineVariant,
    scrim = Color(0xFF000000),
    surfaceBright = NightContainerHighest,
    surfaceDim = NightLowest,
    surfaceContainerLowest = NightLowest,
    surfaceContainerLow = NightLow,
    surfaceContainer = NightContainer,
    surfaceContainerHigh = NightContainerHigh,
    surfaceContainerHighest = NightContainerHighest,
    error = Color(0xFFE89B91),
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
    /** 模型 logo 的衬底：深色底上给透明字形一个浅色盘，避免图标消失。 */
    val modelLogoPlate: Color,
)

val ProferLightSemantics = ProferSemanticColors(
    success = Color(0xFF3A6F4E),
    successContainer = Color(0xFFDFEDE4),
    onSuccessContainer = Color(0xFF17301F),
    warning = Color(0xFF9A6F2C),
    warningContainer = Color(0xFFF4E7CF),
    onWarningContainer = Color(0xFF3F2A08),
    // 代码块沿用云朵舞者的深暖棕，而不是中性黑：亮暗两套都保持纸感。
    codeBackground = Color(0xFF352B27),
    codeForeground = Color(0xFFEFEAE2),
    modelLogoPlate = PaperLowest,
)

val ProferDarkSemantics = ProferSemanticColors(
    success = Color(0xFF86C7A2),
    successContainer = Color(0xFF1E3328),
    onSuccessContainer = Color(0xFFCDEBD9),
    warning = Color(0xFFDDB271),
    warningContainer = Color(0xFF352A17),
    onWarningContainer = Color(0xFFF3E1C2),
    codeBackground = Color(0xFF14110F),
    codeForeground = Color(0xFFE7E2DA),
    modelLogoPlate = Color(0xFFF2EEE6),
)

val LocalProferSemanticColors = staticCompositionLocalOf { ProferLightSemantics }
