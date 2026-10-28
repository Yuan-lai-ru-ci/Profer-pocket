package com.profer.pocket.designsystem.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/*
 * 配色来源：桌面端「云朵舞者」皮肤（slate-light，即设置里 themeStyle = slate-light 的那套）。
 *
 * 桌面 token → 移动端角色的换算（HSL 原值见 apps/electron/resources/skins/slate-light/skin.css）：
 *   --content-area  40 8% 94%  #F1F0EE → background / surface（对话、设置、连接页画布）
 *   --card          40 8% 96%  #F6F5F4 → surfaceContainerLow（卡片、输入区、弹层，靠描边浮起）
 *   --secondary     40 6% 90%  #E7E6E4 → surfaceContainer（分段轨道、表单底、选中行）
 *   --sidebar       40 6% 88%  #E2E1DF → surfaceContainerHigh（抽屉、用户气泡、工具条）
 *   --item-selected 40 8% 84%  #D9D7D3 → surfaceContainerHighest（更深一层：选中/按下）
 *   --dialog        40 10% 97% #F8F8F7 → surfaceContainerLowest / surfaceBright（弹窗、最亮面）
 *   --muted         40 6% 85%  #DBDAD6 → surfaceDim
 *   --foreground    40 8% 18%  #322F2A → onSurface
 *   --primary       18 20% 43% #846558 → primary（陶土棕：发送、允许、主按钮、开关）
 *   --code-bg       18 15% 18% #352B27 → 代码块底
 *
 * 两处有意偏离桌面原值：
 *   1. --muted-foreground 40 5% 45% (#78756D) 在主画布上只有 4.0:1，低于 AA；
 *      移动端改用同色相的 #66635C（画布 5.3:1 / 抽屉 4.6:1），桌面端字号更大、可读性压力不同。
 *   2. 桌面没有深色版云朵舞者。深色沿用同一组暖色相（H 40 / S 7%），把明度压到 7%–20%，
 *      不是反色，也不是另一套色系。
 *
 * 主题色只表达品牌与主操作；成功/警告仍各自独立，不参与品牌表达。
 */

private val Paper = Color(0xFFF1F0EE)
private val PaperLowest = Color(0xFFF8F8F7)
private val PaperLow = Color(0xFFF6F5F4)
private val PaperContainer = Color(0xFFE7E6E4)
private val PaperContainerHigh = Color(0xFFE2E1DF)
private val PaperContainerHighest = Color(0xFFD9D7D3)
private val PaperDim = Color(0xFFDBDAD6)
private val Ink = Color(0xFF322F2A)
private val InkMuted = Color(0xFF66635C)
private val Outline = Color(0xFFCFCDC9)
private val OutlineVariant = Color(0xFFDDDCD9)
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
