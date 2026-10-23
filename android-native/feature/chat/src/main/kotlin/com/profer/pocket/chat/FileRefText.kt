package com.profer.pocket.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 文件路径识别与文件 chip。
 *
 * 正文不再内联 chip：一句话被 chip 切成几块反而更难读，正文里的路径
 * 走等宽行内代码。chip 只保留给「本轮修改文件」汇总。
 */
private val FILE_EXTENSIONS = setOf(
    "md", "markdown", "json", "jsonc", "json5", "xml", "html", "htm", "txt", "log", "csv",
    "yaml", "yml", "toml", "ini", "env", "lock", "ts", "tsx", "js", "jsx", "mjs", "cjs",
    "py", "go", "rs", "java", "kt", "swift", "c", "h", "cpp", "hpp", "cs", "sh", "bash",
    "zsh", "fish", "css", "scss", "less", "sql", "rb", "php", "diff", "patch",
    "png", "jpg", "jpeg", "gif", "webp", "svg", "bmp", "mp4", "webm", "mov", "pdf", "docx",
)

private val FILE_CANDIDATE = Regex(
    """(?:~|/)?(?:[\w.@+\-]+/)*[\w.@+\-]+\.[A-Za-z0-9]{1,8}(?::\d+(?::\d+)?)?""",
)

private val LINE_COL_SUFFIX = Regex(""":\d+(?::\d+)?$""")

internal data class FileRefSegment(
    val text: String,
    val isFile: Boolean = false,
    val path: String = "",
    val suffix: String = "",
) {
    val display: String get() = path.substringAfterLast('/') + suffix
}

/** 把一段正文切成「普通文本 / 文件路径」片段，供内联 chip 使用。 */
internal fun splitFileRefs(raw: String): List<FileRefSegment> {
    if (raw.isEmpty()) return emptyList()
    val segments = mutableListOf<FileRefSegment>()
    var cursor = 0
    for (match in FILE_CANDIDATE.findAll(raw)) {
        val start = match.range.first
        if (start > 0) {
            val previous = raw[start - 1]
            // 不要从单词、路径或 URL 中间截出一个「文件」
            if (previous.isLetterOrDigit() || previous == '_' || previous == '/' || previous == '\\' || previous == '.') {
                continue
            }
        }
        val rawText = match.value
        val suffixMatch = LINE_COL_SUFFIX.find(rawText)
        val path = if (suffixMatch != null) rawText.removeRange(suffixMatch.range) else rawText
        val suffix = suffixMatch?.value.orEmpty()
        val filename = path.substringAfterLast('/')
        val extension = filename.substringAfterLast('.', "").lowercase()
        if (extension !in FILE_EXTENSIONS) continue
        if (!path.contains('/') && path.length < 3) continue
        if (start > cursor) {
            segments += FileRefSegment(raw.substring(cursor, start))
        }
        segments += FileRefSegment(rawText, isFile = true, path = path, suffix = suffix)
        cursor = match.range.last + 1
    }
    if (cursor < raw.length) {
        segments += FileRefSegment(raw.substring(cursor))
    }
    return segments
}

private fun badgeFor(filename: String): Pair<String, Color> {
    val extension = filename.substringAfterLast('.', "").lowercase()
    return when (extension) {
        "ts", "tsx" -> "TS" to Color(0xFF3178C6)
        "js", "jsx", "mjs", "cjs" -> "JS" to Color(0xFFB8A600)
        "py" -> "PY" to Color(0xFF3776AB)
        "json", "jsonc", "json5" -> "{}" to Color(0xFF8A7A00)
        "md", "markdown" -> "MD" to Color(0xFF3D7EA6)
        "kt", "java" -> "KT" to Color(0xFF8B5CF6)
        "css", "scss", "less" -> "CSS" to Color(0xFF7C4D9E)
        "html", "htm" -> "<>" to Color(0xFFC2410C)
        "sh", "bash", "zsh", "fish" -> "SH" to Color(0xFF4E8F35)
        "png", "jpg", "jpeg", "gif", "webp", "svg", "bmp" -> "IMG" to Color(0xFF7C3AED)
        "pdf", "docx" -> "DOC" to Color(0xFFB91C1C)
        else -> extension.take(2).uppercase().ifBlank { "FILE" } to Color(0xFF6B7280)
    }
}

@Composable
internal fun FileRefChip(display: String, onClick: (() -> Unit)? = null) {
    val primary = MaterialTheme.colorScheme.primary
    val (badge, badgeColor) = badgeFor(display)
    Row(
        modifier = Modifier
            .height(21.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(primary.copy(alpha = 0.10f))
            .border(1.dp, primary.copy(alpha = 0.20f), RoundedCornerShape(6.dp))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = badge,
            style = MaterialTheme.typography.labelSmall.copy(
                fontSize = 8.sp,
                lineHeight = 10.sp,
                fontWeight = FontWeight.SemiBold,
            ),
            color = badgeColor,
            modifier = Modifier
                .clip(RoundedCornerShape(3.dp))
                .background(badgeColor.copy(alpha = 0.16f))
                .padding(horizontal = 3.dp, vertical = 1.dp),
        )
        Text(
            text = display,
            style = MaterialTheme.typography.bodySmall.copy(
                fontSize = 12.sp,
                lineHeight = 16.sp,
            ),
            color = primary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
