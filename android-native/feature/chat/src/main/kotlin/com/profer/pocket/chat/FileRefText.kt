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
import com.profer.pocket.designsystem.theme.ProferFileBadgeColors
import com.profer.pocket.designsystem.theme.ProferThemeExtras

/**
 * 文件路径识别与文件 chip。
 *
 * 正文里的文件路径由 [ProferRichText] 拆成 chip 内联渲染（点一下拉预览）；
 * 这里的切分工具同时给「本轮修改文件」汇总卡和正文 chip 复用。
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

/** 正文 chip 的短名：去掉目录，保留 `:行[:列]` 后缀（与桌面端 chip 一致）。 */
internal fun fileDisplayName(raw: String): String {
    val trimmed = raw.trim()
    val suffix = LINE_COL_SUFFIX.find(trimmed)?.value.orEmpty()
    val path = if (suffix.isEmpty()) trimmed else trimmed.dropLast(suffix.length)
    return path.substringAfterLast('/') + suffix
}

/**
 * 单个行内片段是不是文件引用（绝对路径、相对路径、裸文件名都算）。
 *
 * 与桌面端同口径：扩展名得在已知列表里，纯版本号（`1.2.3`）不算文件。
 */
internal fun isFileRef(value: String): Boolean {
    val trimmed = value.trim()
    if (trimmed.isEmpty() || trimmed.contains(' ')) return false
    val path = trimmed.replace(LINE_COL_SUFFIX, "")
    val filename = path.substringAfterLast('/')
    val extension = filename.substringAfterLast('.', "").lowercase()
    if (extension !in FILE_EXTENSIONS) return false
    // 裸文件名至少要有名字部分：`.gitignore` 这类点开头的不当作文件。
    return filename.length > extension.length + 1
}

/** chip 里一行最多显示多少字符（含省略号与大后缀）。 */
private const val ChipLabelLimit = 34

/**
 * 长文件名的中间省略：保留开头 + 扩展名 + `:行:列` 后缀。
 *
 * 移动端一行放不下几十个字符的长名，尾巴上被截成 `…report....` 看不出是什么、在哪一行；
 * 中间省略能把「哪个文件。哪种类型。第几行」三件事同时留住。
 */
internal fun middleEllipsis(text: String, max: Int): String {
    if (text.length <= max || max < 8) return text
    val suffix = LINE_COL_SUFFIX.find(text)?.value.orEmpty()
    val body = if (suffix.isEmpty()) text else text.dropLast(suffix.length)
    val extension = body.substringAfterLast('.', "")
    val tail = (if (extension.isEmpty() || extension.length > 8) "" else ".$extension") + suffix
    if (tail.length >= max - 1) return text
    return body.take(max - tail.length - 1) + "…" + tail
}

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

private fun badgeFor(filename: String, colors: ProferFileBadgeColors): Pair<String, Color> {
    val extension = filename.substringAfterLast('.', "").lowercase()
    return when (extension) {
        "ts", "tsx" -> "TS" to colors.typescript
        "js", "jsx", "mjs", "cjs" -> "JS" to colors.javascript
        "py" -> "PY" to colors.python
        "json", "jsonc", "json5" -> "{}" to colors.json
        "md", "markdown" -> "MD" to colors.markdown
        "kt", "java" -> "KT" to colors.kotlin
        "css", "scss", "less" -> "CSS" to colors.stylesheet
        "html", "htm" -> "<>" to colors.html
        "sh", "bash", "zsh", "fish" -> "SH" to colors.shell
        "png", "jpg", "jpeg", "gif", "webp", "svg", "bmp" -> "IMG" to colors.image
        "pdf", "docx" -> "DOC" to colors.document
        else -> extension.take(2).uppercase().ifBlank { "FILE" } to colors.fallback
    }
}

@Composable
internal fun FileRefChip(display: String, onClick: (() -> Unit)? = null) {
    val primary = MaterialTheme.colorScheme.primary
    val (badge, badgeColor) = badgeFor(display, ProferThemeExtras.fileBadges)
    // 长名先做中间省略，再交给 Text 的 maxLines / ellipsis 兜底。
    val label = middleEllipsis(display, ChipLabelLimit)
    Row(
        modifier = Modifier
            .height(21.dp)
            .clip(MaterialTheme.shapes.extraSmall)
            .background(primary.copy(alpha = 0.10f))
            .border(1.dp, primary.copy(alpha = 0.20f), MaterialTheme.shapes.extraSmall)
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
            text = label,
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
