package com.profer.pocket.designsystem.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * 文件类型徽标的配色。
 *
 * 这些颜色来自各语言的品牌色，但品牌色是给白底设计的：直接放到深色画布上，
 * 蓝/紫/深红的对比度会掉到 2:1 以下，几乎看不清。所以亮暗各一套，
 * 深色版本保持同一色相、把明度提上来，在 10% 主色底上仍能读到 4.5:1 以上。
 */
@Immutable
data class ProferFileBadgeColors(
    val typescript: Color,
    val javascript: Color,
    val python: Color,
    val json: Color,
    val markdown: Color,
    val kotlin: Color,
    val stylesheet: Color,
    val html: Color,
    val shell: Color,
    val image: Color,
    val document: Color,
    val fallback: Color,
)

val ProferLightFileBadges = ProferFileBadgeColors(
    typescript = Color(0xFF2C6CB0),
    javascript = Color(0xFF8A7A00),
    python = Color(0xFF2F6795),
    json = Color(0xFF6F6200),
    markdown = Color(0xFF33688C),
    kotlin = Color(0xFF7040D8),
    stylesheet = Color(0xFF6B3F8A),
    html = Color(0xFFA93A0B),
    shell = Color(0xFF3F7A2B),
    image = Color(0xFF6B2FD0),
    document = Color(0xFF9E1717),
    fallback = Color(0xFF5A6068),
)

val ProferDarkFileBadges = ProferFileBadgeColors(
    typescript = Color(0xFF7FB3E8),
    javascript = Color(0xFFD8C758),
    python = Color(0xFF7FB4DC),
    json = Color(0xFFCBBB5E),
    markdown = Color(0xFF8CBEDA),
    kotlin = Color(0xFFB79BF7),
    stylesheet = Color(0xFFC6A2E0),
    html = Color(0xFFF0996F),
    shell = Color(0xFF9BCE7C),
    image = Color(0xFFB79BF7),
    document = Color(0xFFF0968F),
    fallback = Color(0xFFAEB4BC),
)

val LocalProferFileBadges = staticCompositionLocalOf { ProferLightFileBadges }
