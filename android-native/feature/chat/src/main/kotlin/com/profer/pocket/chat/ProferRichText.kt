package com.profer.pocket.chat

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.profer.pocket.designsystem.component.ProferCodeBlock
import com.profer.pocket.designsystem.theme.ProferSpacing

/**
 * 助手正文排版：标题、段落、列表、引用、代码、表格各自成块。
 *
 * 移动端取舍：
 * - 行内代码不再做成 badge，而是等宽 + 淡底，跟着句子自然换行；
 * - 文件路径也不在句子里插 chip，避免把一句话切成几块；
 * - Markdown 表格不横向铺开，改成按行分组的记录卡，窄屏也能读。
 */
@Composable
fun ProferRichText(
    text: String,
    modifier: Modifier = Modifier,
) {
    // 自由选词：整段正文交给系统选择，可跨段落复制。
    SelectionContainer { RichBlocks(blocks = parseRichBlocks(text), modifier = modifier) }
}

@Composable
fun ProferRichText(
    blocks: List<ContentBlock>,
    modifier: Modifier = Modifier,
    showCursor: Boolean = false,
) {
    SelectionContainer { RichBlocks(blocks = blocks, modifier = modifier, showCursor = showCursor) }
}

@Composable
private fun RichBlocks(
    blocks: List<ContentBlock>,
    modifier: Modifier = Modifier,
    showCursor: Boolean = false,
) {
    val cursorAlpha = if (showCursor) {
        val transition = rememberInfiniteTransition(label = "rich-cursor")
        val animated by transition.animateFloat(
            initialValue = 0.2f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 720),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "rich-cursor-alpha",
        )
        animated
    } else {
        1f
    }
    val codeBackground = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.055f)

    val effectiveBlocks = if (showCursor && blocks.lastOrNull() !is ContentBlock.Paragraph) {
        blocks + ContentBlock.Paragraph("")
    } else {
        blocks
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(ProferSpacing.sm),
    ) {
        effectiveBlocks.forEachIndexed { index, block ->
            when (block) {
                is ContentBlock.Heading -> Text(
                    text = block.text,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(top = ProferSpacing.xs),
                )

                is ContentBlock.Paragraph -> {
                    val isLast = index == effectiveBlocks.lastIndex
                    if (isLast && showCursor) {
                        val content = buildAnnotatedString {
                            append(inlineMarkdown(block.text, codeBackground))
                            if (block.text.isNotEmpty()) append(" ")
                            withStyle(
                                SpanStyle(
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = cursorAlpha),
                                ),
                            ) {
                                append("▏")
                            }
                        }
                        Text(
                            text = content,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    } else {
                        RichInlineText(
                            raw = block.text,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                            codeBackground = codeBackground,
                        )
                    }
                }

                is ContentBlock.Bullets -> Column(
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    block.items.forEach { item ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            BoxBullet()
                            RichInlineText(
                                raw = item,
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurface,
                                codeBackground = codeBackground,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }

                is ContentBlock.Ordered -> Column(
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    block.items.forEachIndexed { itemIndex, item ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                text = "${itemIndex + 1}.",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.widthIn(min = 18.dp),
                            )
                            RichInlineText(
                                raw = item,
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurface,
                                codeBackground = codeBackground,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }

                is ContentBlock.Quote -> Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .padding(top = 3.dp, bottom = 3.dp)
                            .width(2.dp)
                            .height(18.dp)
                            .background(
                                MaterialTheme.colorScheme.outline,
                                RoundedCornerShape(1.dp),
                            ),
                    )
                    RichInlineText(
                        raw = block.text,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        codeBackground = codeBackground,
                        modifier = Modifier.weight(1f),
                    )
                }

                is ContentBlock.Code -> ProferCodeBlock(
                    code = block.code,
                    language = block.language,
                    wrap = true,
                )

                is ContentBlock.Table -> ProferTable(
                    headers = block.headers,
                    rows = block.rows,
                    codeBackground = codeBackground,
                )
            }
        }
    }
}

@Composable
private fun BoxBullet() {
    Text(
        text = "•",
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 1.dp),
    )
}

/**
 * 行内 Markdown：加粗 + 行内代码。
 * 代码用等宽字体和一层很淡的底色，不改变字号，避免中文行高被撑乱。
 */
internal fun inlineMarkdown(raw: String, codeBackground: Color): AnnotatedString = buildAnnotatedString {
    var index = 0
    while (index < raw.length) {
        val codeStart = raw.indexOf('`', index)
        val boldStart = raw.indexOf("**", index)
        val next = listOf(codeStart, boldStart).filter { it >= 0 }.minOrNull()
        if (next == null) {
            append(raw.substring(index))
            break
        }
        if (next > index) append(raw.substring(index, next))
        if (next == codeStart) {
            val codeEnd = raw.indexOf('`', codeStart + 1)
            if (codeEnd < 0) {
                // 没闭合的反引号不该吞掉后半段正文。
                append(raw.substring(codeStart))
                break
            }
            withStyle(
                SpanStyle(
                    fontFamily = FontFamily.Monospace,
                    background = codeBackground,
                ),
            ) {
                append(raw.substring(codeStart + 1, codeEnd))
            }
            index = codeEnd + 1
        } else {
            val boldEnd = raw.indexOf("**", boldStart + 2)
            if (boldEnd < 0) {
                append(raw.substring(boldStart))
                break
            }
            withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) {
                append(raw.substring(boldStart + 2, boldEnd))
            }
            index = boldEnd + 2
        }
    }
}

/** 单段富文本：整段是一个 Text，文件路径/行内代码不会把句子切成多个块。 */
@Composable
internal fun RichInlineText(
    raw: String,
    style: TextStyle,
    color: Color,
    codeBackground: Color,
    modifier: Modifier = Modifier,
) {
    Text(
        text = inlineMarkdown(raw, codeBackground),
        style = style,
        color = color,
        modifier = modifier,
    )
}

private val TABLE_SEPARATOR = Regex("""^:?-{3,}:?$""")

private val ORDERED_LINE = Regex("""^(\d{1,3})[.)]\s+(.+)$""")

private fun isTableLine(line: String): Boolean =
    line.length >= 3 && line.startsWith("|") && line.endsWith("|")

private fun splitTableRow(line: String): List<String> {
    val body = line.trim().removePrefix("|").removeSuffix("|")
    val cells = mutableListOf<String>()
    val current = StringBuilder()
    var index = 0
    var inCode = false
    while (index < body.length) {
        val char = body[index]
        when {
            char == '\\' && index + 1 < body.length && body[index + 1] == '|' -> {
                current.append('|')
                index++
            }
            char == '`' -> {
                inCode = !inCode
                current.append(char)
            }
            char == '|' && !inCode -> {
                cells += current.toString().trim()
                current.clear()
            }
            else -> current.append(char)
        }
        index++
    }
    cells += current.toString().trim()
    return cells
}

private fun isSeparatorRow(cells: List<String>): Boolean =
    cells.isNotEmpty() && cells.all { TABLE_SEPARATOR.matches(it.trim()) }

/** Markdown 文本 → 块。解析范围有意保持窄，但覆盖模型实际高频用法。 */
fun parseRichBlocks(raw: String): List<ContentBlock> {
    val blocks = mutableListOf<ContentBlock>()
    val paragraph = StringBuilder()
    val bullets = mutableListOf<String>()
    val ordered = mutableListOf<String>()
    val tableLines = mutableListOf<String>()
    var inCode = false
    var codeLanguage = ""
    val code = StringBuilder()

    fun flushParagraph() {
        if (paragraph.isNotBlank()) {
            blocks += ContentBlock.Paragraph(paragraph.toString().trim())
        }
        paragraph.clear()
    }

    fun flushBullets() {
        if (bullets.isNotEmpty()) {
            blocks += ContentBlock.Bullets(bullets.toList())
            bullets.clear()
        }
    }

    fun flushOrdered() {
        if (ordered.isNotEmpty()) {
            blocks += ContentBlock.Ordered(ordered.toList())
            ordered.clear()
        }
    }

    fun flushTable() {
        if (tableLines.size >= 2) {
            val cells = tableLines.map(::splitTableRow)
            val header = cells.first()
            val rows = if (isSeparatorRow(cells[1])) cells.drop(2) else cells.drop(1)
            if (header.any { it.isNotBlank() } && rows.isNotEmpty()) {
                blocks += ContentBlock.Table(headers = header, rows = rows)
            } else {
                blocks += ContentBlock.Paragraph(tableLines.joinToString("\n"))
            }
        } else if (tableLines.isNotEmpty()) {
            blocks += ContentBlock.Paragraph(tableLines.joinToString("\n"))
        }
        tableLines.clear()
    }

    fun flushInlineBlocks() {
        flushParagraph()
        flushBullets()
        flushOrdered()
    }

    for (line in raw.lines()) {
        val trimmed = line.trim()
        if (trimmed.startsWith("```")) {
            if (!inCode) {
                flushInlineBlocks()
                flushTable()
                inCode = true
                codeLanguage = trimmed.removePrefix("```").trim()
            } else {
                blocks += ContentBlock.Code(codeLanguage, code.toString())
                code.clear()
                inCode = false
            }
            continue
        }
        if (inCode) {
            code.appendLine(line)
            continue
        }
        if (isTableLine(trimmed)) {
            flushInlineBlocks()
            tableLines += trimmed
            continue
        }
        if (tableLines.isNotEmpty()) flushTable()

        when {
            trimmed.isEmpty() -> flushInlineBlocks()

            trimmed.startsWith("### ") -> {
                flushInlineBlocks()
                blocks += ContentBlock.Heading(trimmed.removePrefix("### "))
            }

            trimmed.startsWith("## ") || trimmed.startsWith("# ") -> {
                flushInlineBlocks()
                blocks += ContentBlock.Heading(trimmed.removePrefix("## ").removePrefix("# "))
            }

            trimmed.startsWith("- ") || trimmed.startsWith("* ") -> {
                flushParagraph()
                flushOrdered()
                bullets += trimmed.substring(2)
            }

            ORDERED_LINE.matches(trimmed) -> {
                flushParagraph()
                flushBullets()
                ordered += ORDERED_LINE.matchEntire(trimmed)!!.groupValues[2]
            }

            trimmed.startsWith("> ") -> {
                flushInlineBlocks()
                blocks += ContentBlock.Quote(trimmed.removePrefix("> "))
            }

            else -> {
                flushBullets()
                flushOrdered()
                if (paragraph.isNotEmpty()) paragraph.append(' ')
                paragraph.append(trimmed)
            }
        }
    }
    if (inCode && code.isNotEmpty()) {
        blocks += ContentBlock.Code(codeLanguage, code.toString())
    }
    flushTable()
    flushInlineBlocks()
    return blocks
}

private val INDEX_HEADERS = setOf("#", "序号", "no", "no.", "id", "index")

/**
 * 表格卡：每条记录一张卡，第一列做标题，其余列做「列名 + 值」。
 * 4 列长文本在 320dp 屏上横向铺开必然不可读，所以主动放弃横向表格。
 */
@Composable
private fun ProferTable(
    headers: List<String>,
    rows: List<List<String>>,
    codeBackground: Color,
) {
    val columnCount = maxOf(headers.size, rows.maxOfOrNull { it.size } ?: 0)
    val indexColumn = headers.indexOfFirst { it.trim().lowercase() in INDEX_HEADERS }
    val titleColumn = (0 until columnCount).firstOrNull { it != indexColumn } ?: 0
    val detailColumns = (0 until columnCount).filter { it != indexColumn && it != titleColumn }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.8f),
        ),
    ) {
        Column {
            rows.forEachIndexed { rowIndex, row ->
                Column(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        if (indexColumn != null) {
                            val index = row.getOrNull(indexColumn).orEmpty().trim()
                            if (index.isNotBlank()) {
                                Surface(
                                    shape = CircleShape,
                                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                ) {
                                    Text(
                                        text = index.removePrefix("#"),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                    )
                                }
                            }
                        }
                        RichInlineText(
                            raw = row.getOrNull(titleColumn).orEmpty(),
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontWeight = FontWeight.Medium,
                            ),
                            color = MaterialTheme.colorScheme.onSurface,
                            codeBackground = codeBackground,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    detailColumns.forEach { column ->
                        val value = row.getOrNull(column).orEmpty().trim()
                        if (value.isNotEmpty()) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                verticalAlignment = Alignment.Top,
                            ) {
                                val label = headers.getOrNull(column).orEmpty().trim()
                                if (label.isNotEmpty()) {
                                    Text(
                                        text = label,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.68f),
                                        modifier = Modifier
                                            .padding(top = 2.dp)
                                            .widthIn(min = 36.dp, max = 54.dp),
                                    )
                                }
                                RichInlineText(
                                    raw = value,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.86f),
                                    codeBackground = codeBackground,
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }
                    }
                }
                if (rowIndex != rows.lastIndex) {
                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 12.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f),
                    )
                }
            }
        }
    }
}
