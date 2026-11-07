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
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.semantics
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
import kotlin.math.roundToInt

/**
 * 助手正文排版：标题、段落、列表、引用、代码、表格各自成块。
 *
 * 移动端取舍：
 * - 行内代码不再做成 badge，而是等宽 + 淡底，跟着句子自然换行；
 * - 行内代码里的文件路径换成可点击 chip，点一下直接拉电脑端文件预览（与桌面端同源）；
 * - Markdown 表格不横向铺开，改成按行分组的记录卡，窄屏也能读。
 */
private val LocalFilePreview = staticCompositionLocalOf<(String) -> Unit> { {} }

@Composable
fun ProvideFilePreview(onOpenFile: (String) -> Unit, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalFilePreview provides onOpenFile, content = content)
}

@Composable
fun ProferRichText(
    text: String,
    modifier: Modifier = Modifier,
) {
    val blocks = remember(text) { cachedRichBlocks(text) }
    SelectionContainer { RichBlocks(blocks = blocks, modifier = modifier) }
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
    val cursorAlpha: State<Float>? = if (showCursor) {
        val transition = rememberInfiniteTransition(label = "rich-cursor")
        val raw = transition.animateFloat(
            initialValue = 0.2f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 720),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "rich-cursor-alpha",
        )
        // 这里只建立派生值、不读取 raw：读取点下沉到光标所在的 Text，
        // 否则整条消息会跟着 720ms 的呼吸动画每帧重组。
        // 量化到 1/10 档，视觉上仍是呼吸感，重组次数从每帧降到每档一次。
        remember { derivedStateOf { (raw.value * 10f).roundToInt() / 10f } }
    } else {
        null
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
                    if (isLast && cursorAlpha != null) {
                        StreamingCursorParagraph(
                            text = block.text,
                            codeBackground = codeBackground,
                            cursorAlpha = cursorAlpha,
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
private fun StreamingCursorParagraph(
    text: String,
    codeBackground: Color,
    cursorAlpha: State<Float>,
) {
    val cursorColor = MaterialTheme.colorScheme.onSurface
    val runs = remember(text, codeBackground) { inlineRuns(text, codeBackground) }
    InlineRuns(
        runs = runs,
        style = MaterialTheme.typography.bodyLarge,
        color = cursorColor,
        // 光标作为同一行的最后一小段：动画值只在这个 Text 里读，重组不牵动整段正文。
        trailing = {
            val alpha = cursorAlpha.value
            Text(
                text = "▏",
                style = MaterialTheme.typography.bodyLarge,
                color = cursorColor.copy(alpha = alpha),
                modifier = Modifier.padding(start = 1.dp),
            )
        },
    )
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
 * 文件路径（包括裸文件名）额外打上 `file` 注解，由上层换成 chip。
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
                append(raw.substring(codeStart))
                break
            }
            val code = raw.substring(codeStart + 1, codeEnd)
            val isFile = isFileRef(code)
            val start = length
            withStyle(
                SpanStyle(
                    fontFamily = FontFamily.Monospace,
                    background = codeBackground,
                    color = Color.Unspecified,
                ),
            ) {
                append(code)
            }
            if (isFile) addStringAnnotation("file", code, start, length)
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

/**
 * 一段富文本的排版单元：普通文字片段，或一个文件 chip。
 *
 * 移动端不能像桌面那样直接在行内塞一个 <code> 组件，所以先把段落拆成小块，
 * 再用 [FlowRow] 按行重排：文字块自己换行，chip 跟句子同行走。
 */
internal data class InlineRun(
    val text: AnnotatedString? = null,
    val path: String? = null,
    val display: String = "",
)

/** 段落 → 排版单元。文件路径（行内代码里的绝对/相对路径）单独成 chip。 */
internal fun inlineRuns(raw: String, codeBackground: Color): List<InlineRun> {
    val annotated = inlineMarkdown(raw, codeBackground)
    val files = annotated.getStringAnnotations("file", 0, annotated.length)
    val runs = ArrayList<InlineRun>()
    if (files.isEmpty()) {
        tokenizeInline(annotated).forEach { runs += InlineRun(text = it) }
        return runs
    }
    var cursor = 0
    files.forEach { file ->
        if (file.start > cursor) {
            tokenizeInline(annotated.subSequence(cursor, file.start)).forEach { runs += InlineRun(text = it) }
        }
        runs += InlineRun(path = file.item, display = fileDisplayName(file.item))
        cursor = file.end
    }
    if (cursor < annotated.length) {
        tokenizeInline(annotated.subSequence(cursor, annotated.length)).forEach { runs += InlineRun(text = it) }
    }
    return runs
}

/** 中日韩标点：在它们后面断开，行尾不会只剩一个标点。 */
private const val CJK_BREAK = "，。、；：！？）》」』】”’"

/** 没有空格的长串也允许在这长度后断开，避免一个超长单词把整行顶出去。 */
private const val InlineChunkLimit = 14

/**
 * 把一段带样式的文本切成可重排的小块。
 *
 * 规则：空格后、中文标点后断开；中文串满 [InlineChunkLimit] 个字再断一次；
 * 拉丁长串给到 40 字符才兜底断，避免把驼峰标识符从中间切断。
 */
private fun tokenizeInline(text: AnnotatedString): List<AnnotatedString> {
    if (text.isEmpty()) return emptyList()
    val out = ArrayList<AnnotatedString>()
    var start = 0
    var chunkStart = 0
    var hasCjk = false
    var index = 0
    while (index < text.length) {
        val char = text[index]
        val cut = when {
            char.isWhitespace() || char in CJK_BREAK -> true
            else -> {
                if (char.code in 0x2E80..0x9FFF) hasCjk = true
                val size = index - chunkStart + 1
                (hasCjk && size >= InlineChunkLimit) || size >= 40
            }
        }
        if (cut) {
            out += text.subSequence(start, index + 1)
            start = index + 1
            chunkStart = start
            hasCjk = false
        }
        index++
    }
    if (start < text.length) out += text.subSequence(start, text.length)
    return out
}

/**
 * 单段富文本：文字与文件 chip 混排。
 *
 * 读屏会把整段合并成一个节点（chip 的文本也包含在内），不会读成一堆碎片。
 */
@Composable
internal fun RichInlineText(
    raw: String,
    style: TextStyle,
    color: Color,
    codeBackground: Color,
    modifier: Modifier = Modifier,
) {
    // 长会话里可见消息会因流式 token / 输入框高度反复重组：
    // 每次都重建 AnnotatedString 会重新分句、重新排版，滚动时直接掉帧。
    val runs = remember(raw, codeBackground) { inlineRuns(raw, codeBackground) }
    InlineRuns(runs = runs, style = style, color = color, modifier = modifier)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun InlineRuns(
    runs: List<InlineRun>,
    style: TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
) {
    val openFile = LocalFilePreview.current
    FlowRow(
        modifier = modifier.semantics(mergeDescendants = true) {},
        horizontalArrangement = Arrangement.Start,
    ) {
        runs.forEach { run ->
            val path = run.path
            val text = run.text
            when {
                path != null -> Box(
                    modifier = Modifier
                        .padding(start = 2.dp, end = 2.dp)
                        // chip 比正文行高矮：往上推一点，视觉上仍落在同一条基线带里。
                        .padding(top = 1.dp),
                ) {
                    FileRefChip(display = run.display, onClick = { openFile(path) })
                }

                text != null -> Text(text = text, style = style, color = color)
            }
        }
        trailing?.invoke()
    }
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

/**
 * 已完成消息的解析缓存。
 *
 * 解析是纯函数（只依赖正文，与主题无关），按正文缓存。流式中间态**不进**这张表：
 * partial 帧每帧一个新正文，进表等于把几十份半成品正文连同块列表一起留到最后，
 * 还会把历史消息的缓存挤出去（见 [streamingRichBlocks]）。
 */
private val richBlocksCache = object : LinkedHashMap<String, List<ContentBlock>>(64, 0.75f, true) {}
private const val RICH_BLOCKS_CACHE_LIMIT = 256

fun cachedRichBlocks(raw: String): List<ContentBlock> {
    synchronized(richBlocksCache) { richBlocksCache[raw]?.let { return it } }
    // 解析不持锁：单条正文重算在毫秒级，持锁会把主线程的调用一起堵住。
    val parsed = parseRichBlocks(raw)
    synchronized(richBlocksCache) {
        // 按访问顺序淘汰最久未用的一条：整表清空会让长会话滚动时集中重解析。
        while (richBlocksCache.size >= RICH_BLOCKS_CACHE_LIMIT) {
            val eldest = richBlocksCache.keys.firstOrNull() ?: break
            richBlocksCache.remove(eldest)
        }
        richBlocksCache[raw] = parsed
    }
    return parsed
}

/** 已完成缓存的当前条数（单测用：流式帧不该往里写）。 */
internal fun richBlocksCacheSize(): Int = synchronized(richBlocksCache) { richBlocksCache.size }

/** 清空已完成缓存（单测用）。 */
internal fun clearRichBlocksCache() {
    synchronized(richBlocksCache) { richBlocksCache.clear() }
}

/** 解析结果：块 + 每块在原文中的起始偏移（行首），供流式增量解析从尾块重算。 */
internal class RichParse(
    val blocks: List<ContentBlock>,
    val starts: IntArray,
)

/** Markdown 文本 → 块。解析范围有意保持窄，但覆盖模型实际高频用法。 */
fun parseRichBlocks(raw: String): List<ContentBlock> = parseRichBlocksFrom(raw, 0).blocks

/**
 * 从 [from]（必须是行首）开始解析。
 *
 * 块的边界处所有累加器都是空的，所以从任意一块的行首起算，与整段解析的结果一致——
 * 流式增量就是靠这条性质只重算尾块。
 */
internal fun parseRichBlocksFrom(raw: String, from: Int): RichParse {
    val blocks = ArrayList<ContentBlock>()
    val starts = ArrayList<Int>()
    val paragraph = StringBuilder()
    val bullets = mutableListOf<String>()
    val ordered = mutableListOf<String>()
    val tableLines = mutableListOf<String>()
    var inCode = false
    var codeLanguage = ""
    val code = StringBuilder()

    var paragraphStart = -1
    var bulletsStart = -1
    var orderedStart = -1
    var tableStart = -1
    var codeStart = -1

    fun emit(block: ContentBlock, start: Int) {
        blocks += block
        starts += start.coerceAtLeast(0)
    }

    fun flushParagraph() {
        if (paragraph.isNotBlank()) {
            emit(ContentBlock.Paragraph(paragraph.toString().trim()), paragraphStart)
        }
        paragraph.clear()
        paragraphStart = -1
    }

    fun flushBullets() {
        if (bullets.isNotEmpty()) {
            emit(ContentBlock.Bullets(bullets.toList()), bulletsStart)
            bullets.clear()
        }
        bulletsStart = -1
    }

    fun flushOrdered() {
        if (ordered.isNotEmpty()) {
            emit(ContentBlock.Ordered(ordered.toList()), orderedStart)
            ordered.clear()
        }
        orderedStart = -1
    }

    fun flushTable() {
        if (tableLines.size >= 2) {
            val cells = tableLines.map(::splitTableRow)
            val header = cells.first()
            val rows = if (isSeparatorRow(cells[1])) cells.drop(2) else cells.drop(1)
            if (header.any { it.isNotBlank() } && rows.isNotEmpty()) {
                emit(ContentBlock.Table(headers = header, rows = rows), tableStart)
            } else {
                emit(ContentBlock.Paragraph(tableLines.joinToString("\n")), tableStart)
            }
        } else if (tableLines.isNotEmpty()) {
            emit(ContentBlock.Paragraph(tableLines.joinToString("\n")), tableStart)
        }
        tableLines.clear()
        tableStart = -1
    }

    fun flushInlineBlocks() {
        flushParagraph()
        flushBullets()
        flushOrdered()
    }

    fun handleLine(offset: Int, line: String) {
        val trimmed = line.trim()
        if (trimmed.startsWith("```")) {
            if (!inCode) {
                flushInlineBlocks()
                flushTable()
                inCode = true
                codeStart = offset
                codeLanguage = trimmed.removePrefix("```").trim()
            } else {
                emit(ContentBlock.Code(codeLanguage, code.toString()), codeStart)
                code.clear()
                inCode = false
                codeStart = -1
            }
            return
        }
        if (inCode) {
            code.appendLine(line)
            return
        }
        if (isTableLine(trimmed)) {
            flushInlineBlocks()
            if (tableLines.isEmpty()) tableStart = offset
            tableLines += trimmed
            return
        }
        if (tableLines.isNotEmpty()) flushTable()

        when {
            trimmed.isEmpty() -> flushInlineBlocks()

            trimmed.startsWith("### ") -> {
                flushInlineBlocks()
                emit(ContentBlock.Heading(trimmed.removePrefix("### ")), offset)
            }

            trimmed.startsWith("## ") || trimmed.startsWith("# ") -> {
                flushInlineBlocks()
                emit(ContentBlock.Heading(trimmed.removePrefix("## ").removePrefix("# ")), offset)
            }

            trimmed.startsWith("- ") || trimmed.startsWith("* ") -> {
                flushParagraph()
                flushOrdered()
                if (bullets.isEmpty()) bulletsStart = offset
                bullets += trimmed.substring(2)
            }

            ORDERED_LINE.matches(trimmed) -> {
                flushParagraph()
                flushBullets()
                if (ordered.isEmpty()) orderedStart = offset
                ordered += ORDERED_LINE.matchEntire(trimmed)!!.groupValues[2]
            }

            trimmed.startsWith("> ") -> {
                flushInlineBlocks()
                emit(ContentBlock.Quote(trimmed.removePrefix("> ")), offset)
            }

            else -> {
                flushBullets()
                flushOrdered()
                if (paragraph.isEmpty()) paragraphStart = offset
                if (paragraph.isNotEmpty()) paragraph.append(' ')
                paragraph.append(trimmed)
            }
        }
    }

    // 行切分与 String.lines() 同口径：\r\n / \n / \r，结尾换行不额外产生空行。
    var lineStart = from
    while (lineStart <= raw.length) {
        var lineEnd = lineStart
        while (lineEnd < raw.length && raw[lineEnd] != '\n' && raw[lineEnd] != '\r') lineEnd++
        handleLine(lineStart, raw.substring(lineStart, lineEnd))
        if (lineEnd >= raw.length) break
        lineStart = if (raw[lineEnd] == '\r' && lineEnd + 1 < raw.length && raw[lineEnd + 1] == '\n') {
            lineEnd + 2
        } else {
            lineEnd + 1
        }
    }

    if (inCode && code.isNotEmpty()) {
        emit(ContentBlock.Code(codeLanguage, code.toString()), codeStart)
    }
    flushTable()
    flushInlineBlocks()
    return RichParse(blocks, starts.toIntArray())
}

/**
 * 流式正文的分块：稳定前缀直接复用，只重算尾块。
 *
 * partial 帧带的是累计全文，整条重解析的代价随正文线性增长；而追加文本只会让最后一块
 * 继续长、或从它后面起新块，前面已经收口的块不会再变。所以按消息记住上一次的解析结果，
 * 从尾块行首重算，前缀原样接上。
 *
 * 只保留最近几条流式消息的中间态（正常只有一个会话在流），且不进 [cachedRichBlocks]，
 * 避免把几十份半成品正文留在缓存里。
 */
private class StreamingParse(
    val text: String,
    val parse: RichParse,
)

private const val STREAMING_PARSE_LIMIT = 4

private val streamingParses = object : LinkedHashMap<String, StreamingParse>(8, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, StreamingParse>): Boolean =
        size > STREAMING_PARSE_LIMIT
}

fun streamingRichBlocks(messageId: String, text: String): List<ContentBlock> {
    synchronized(streamingParses) {
        val previous = streamingParses[messageId]
        val parse = when {
            previous == null -> parseRichBlocksFrom(text, 0)
            previous.text == text -> previous.parse
            // 重写/重新生成会让正文不再以旧文开头：退回整条解析。
            !text.startsWith(previous.text) -> parseRichBlocksFrom(text, 0)
            else -> extendRichParse(previous.parse, text)
        }
        streamingParses[messageId] = StreamingParse(text, parse)
        return parse.blocks
    }
}

/**
 * 流式结束：把尾帧的解析结果转正进 [cachedRichBlocks]，省掉收尾时的一次整条重算。
 * 正文对不上（重写、乱序）时返回 null，由调用方走普通缓存。
 */
fun settleStreamingRichBlocks(messageId: String, text: String): List<ContentBlock>? {
    val parse = synchronized(streamingParses) {
        val previous = streamingParses.remove(messageId) ?: return null
        if (previous.text != text) return null
        previous.parse
    }
    synchronized(richBlocksCache) {
        while (richBlocksCache.size >= RICH_BLOCKS_CACHE_LIMIT) {
            val eldest = richBlocksCache.keys.firstOrNull() ?: break
            richBlocksCache.remove(eldest)
        }
        richBlocksCache[text] = parse.blocks
    }
    return parse.blocks
}

/**
 * 前缀复用：稳定块原样保留，从尾块行首重算。
 *
 * 只有一条要额外小心：最后一行的换行还没到，它现在可能被当成普通段落（如 `- `、`|`、`1.`），
 * 再长一个字就变成列表/表格/有序列表，而那种变化会把上一块也带进来重新归类。
 * 所以尾行可能长成标记行时，稳定前缀再退一块。
 */
internal fun extendRichParse(previous: RichParse, newText: String): RichParse {
    if (previous.blocks.isEmpty() || previous.starts.isEmpty()) return parseRichBlocksFrom(newText, 0)
    var keep = previous.blocks.size - 1
    var restart = previous.starts[keep]
    // 越界说明正文被截短过，退回整条解析。
    if (restart >= newText.length) return parseRichBlocksFrom(newText, 0)

    val lastLineStart = newText.lastIndexOf('\n').let { if (it < 0) 0 else it + 1 }
    val lineOpen = !newText.endsWith("\n") && !newText.endsWith("\r")
    if (keep > 0 && lineOpen && restart >= lastLineStart &&
        mayBecomeMarkerLine(newText.substring(lastLineStart))
    ) {
        keep -= 1
        restart = previous.starts[keep]
    }

    val tail = parseRichBlocksFrom(newText, restart)
    if (keep <= 0) return tail
    val blocks = ArrayList<ContentBlock>(keep + tail.blocks.size)
    blocks.addAll(previous.blocks.subList(0, keep))
    blocks.addAll(tail.blocks)
    val starts = IntArray(keep + tail.starts.size)
    previous.starts.copyInto(starts, 0, 0, keep)
    tail.starts.copyInto(starts, keep)
    return RichParse(blocks, starts)
}

/** 尾行是否还可能长成标记行（列表/表格/围栏/标题/引用）——是的话前一块也可能被它带走。 */
private fun mayBecomeMarkerLine(line: String): Boolean {
    val head = line.trimStart().firstOrNull() ?: return false
    return when (head) {
        '-', '*', '|', '`', '>', '#' -> true
        in '0'..'9' -> true
        else -> false
    }
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
    // 列名只在卡片顶部出现一次：每行重复一遍「文件」会把记录卡变成一叠标签。
    val schemaLabel = headers
        .filterIndexed { column, header -> header.isNotBlank() && column != indexColumn && column != titleColumn }
        .joinToString(" · ") { it.trim() }

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
            if (schemaLabel.isNotEmpty()) {
                Text(
                    text = "${headers.getOrNull(titleColumn).orEmpty().trim().ifEmpty { "记录" }} · $schemaLabel",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.68f),
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                )
                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 12.dp),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f),
                )
            }
            rows.forEachIndexed { rowIndex, row ->
                Column(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        if (indexColumn >= 0) {
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
                        val title = row.getOrNull(titleColumn).orEmpty()
                        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            RichInlineText(
                                raw = title,
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                                color = MaterialTheme.colorScheme.onSurface,
                                codeBackground = codeBackground,
                            )
                        }
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
