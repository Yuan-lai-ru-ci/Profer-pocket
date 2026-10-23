package com.profer.pocket.designsystem.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/*
 * 配色来源：桌面端「云朵舞者 · 晨光」皮肤（slate-pingfang-dawn）的 token 表。
 *
 * 桌面 token → 移动端角色的换算：
 *   --background #F7F6F3 → background / surface（主画布）
 *   --card       #FFFFFF → surfaceContainerLow（卡片、输入区、弹层，靠描边浮起）
 *   --input      #F0EDE7 → surfaceContainer（表单底、选中行）
 *   --sidebar    #EEEBE6 → surfaceContainerHigh（抽屉、用户气泡）
 *   --muted      #E6E1D9 → surfaceContainerHighest（按下/更深的分层）
 *   --foreground #26231F → onSurface；--muted-foreground #6B665F → onSurfaceVariant
 *   --primary    #9C5A45 → primary（砖红：发送、允许、主按钮、开关）
 *
 * 深色不是反色：沿用同一组暖色相，只把明度翻到 8%–22%，保持纸感。
 * 主题色只表达品牌与主操作；成功/警告仍各自独立，不参与品牌表达。
 */

private val Paper = Color(0xFFF7F6F3)
private val PaperLowest = Color(0xFFFFFFFF)
private val PaperLow = Color(0xFFFFFFFF)
private val PaperContainer = Color(0xFFF0EDE7)
private val PaperContainerHigh = Color(0xFFEEEBE6)
private val PaperContainerHighest = Color(0xFFE6E1D9)
private val PaperDim = Color(0xFFE6E1D9)
private val Ink = Color(0xFF26231F)
private val InkMuted = Color(0xFF6B665F)
private val Outline = Color(0xFFD5CFC6)
private val OutlineVariant = Color(0xFFE2DDD5)
private val Clay = Color(0xFF9C5A45)
private val ClayDeep = Color(0xFF4A2A20)
private val ClayContainer = Color(0xFFF0DDD5)

private val Night = Color(0xFF1A1815)
private val NightLowest = Color(0xFF12100E)
private val NightLow = Color(0xFF211E1A)
private val NightContainer = Color(0xFF26221D)
private val NightContainerHigh = Color(0xFF2E2923)
private val NightContainerHighest = Color(0xFF38322B)
private val NightInk = Color(0xFFF0ECE5)
private val NightMuted = Color(0xFFADA69C)
private val NightOutline = Color(0xFF4C463E)
private val NightOutlineVariant = Color(0xFF322D27)
private val NightClay = Color(0xFFCA8E74)
private val NightClayDeep = Color(0xFF241410)
private val NightClayContainer = Color(0xFF4A2E25)

val ProferLightColors = lightColorScheme(
    primary = Clay,
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = ClayContainer,
    onPrimaryContainer = ClayDeep,
    inversePrimary = NightClay,
    secondary = Color(0xFFEAE6DF),
    onSecondary = Color(0xFF2F2B26),
    secondaryContainer = PaperContainerHighest,
    onSecondaryContainer = Color(0xFF4A443C),
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
    onPrimaryContainer = Color(0xFFF4DCD0),
    inversePrimary = Clay,
    secondary = Color(0xFFC9C2B8),
    onSecondary = Color(0xFF2A2620),
    secondaryContainer = NightContainerHigh,
    onSecondaryContainer = NightInk,
    tertiary = NightClay,
    onTertiary = NightClayDeep,
    tertiaryContainer = NightClayContainer,
    onTertiaryContainer = Color(0xFFF4DCD0),
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
    surfaceBright = NightContainerHigh,
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
)

val ProferLightSemantics = ProferSemanticColors(
    success = Color(0xFF3A6F4E),
    successContainer = Color(0xFFDFEDE4),
    onSuccessContainer = Color(0xFF17301F),
    warning = Color(0xFF9A6F2C),
    warningContainer = Color(0xFFF4E7CF),
    onWarningContainer = Color(0xFF3F2A08),
    // 代码块沿用云朵舞者的深暖棕，而不是中性黑：亮暗两套都保持纸感。
    codeBackground = Color(0xFF2E2823),
    codeForeground = Color(0xFFEFEAE2),
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
)

val LocalProferSemanticColors = staticCompositionLocalOf { ProferLightSemantics }
