package com.profer.pocket.chat

/**
 * 思考折叠行的一句话摘要。
 *
 * 取首个非空行、压平空白后截断：折叠行只有一行，长思考不能把它撑成两行。
 * 结束后用首行——它通常就是这一步的意图，比尾部更像结论。
 */
internal fun thinkingHeadline(body: String, maxChars: Int = 72): String {
    val first = body.lineSequence()
        .map { it.trim() }
        .firstOrNull { it.isNotEmpty() }
        ?: return ""
    val flat = first.replace(Whitespace, " ")
    return if (flat.length <= maxChars) flat else flat.take(maxChars).trimEnd() + "…"
}

/**
 * 流式思考的摘要：只露最后一段。
 * 思考还在增长时，尾部才是「当前在想什么」；首行会一直停在同一个句子上。
 */
internal fun thinkingTail(body: String, maxChars: Int = 60): String {
    val flat = body.trim().replace(Whitespace, " ")
    if (flat.isEmpty()) return ""
    return if (flat.length <= maxChars) flat else "…" + flat.takeLast(maxChars)
}

private val Whitespace = Regex("\\s+")
