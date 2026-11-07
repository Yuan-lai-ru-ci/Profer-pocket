package com.profer.pocket.chat

import androidx.compose.runtime.Immutable
import com.profer.pocket.model.ChatImage

/**
 * 过程组里的一项，按发生顺序排列。
 *
 * 思考与工具必须放在同一个有序列表里：一轮七八步时，先铺全部思考再铺全部工具，
 * 展开后就看不出「这段思考是为了这次调用」的对应关系。
 */
@Immutable
sealed interface TurnItem {
    val key: String

    /** 第几步产生的（用于「只铺最近几步」的截断）。 */
    val step: Int

    data class Thought(
        override val key: String,
        override val step: Int,
        val block: ThinkingBlock,
    ) : TurnItem

    data class Tool(
        override val key: String,
        override val step: Int,
        val tool: ToolBlock,
    ) : TurnItem

    /** 中间步骤的正文：给工具看的说明，不是本轮答案，跟着过程组一起折。 */
    data class Text(
        override val key: String,
        override val step: Int,
        val blocks: List<ContentBlock>,
    ) : TurnItem
}

/** 一个回合拆成「过程组里的东西」与「组外的答案」。 */
@Immutable
data class TurnLayout(
    val items: List<TurnItem>,
    val tools: List<ToolBlock>,
    val thinkingCount: Int,
    /** 留在过程组外的正文；空表示这一轮还没有答案（还在跑，或只产出过程）。 */
    val tailBlocks: List<ContentBlock>,
    val images: List<ChatImage>,
    val stepCount: Int,
) {
    val hasProcess: Boolean get() = items.isNotEmpty()
    /** 思考是这一轮唯一的产出：折起来会只剩一行摘要，所以提到正文位置并展开。 */
    val thinkingOnly: Boolean
        get() = tools.isEmpty() && tailBlocks.isEmpty() && items.isNotEmpty() &&
            items.all { it is TurnItem.Thought }
}

/**
 * 把回合内的助手步骤拆成过程项与尾部正文。
 *
 * 组外正文的判定：最后一段**有正文**的步骤。手机上没有块级顺序（正文与工具调用在归约时
 * 被拆成两个字段），无法分辨正文在工具调用之前还是之后，所以保守地只把最后一步的正文外置：
 * 单步回合保持原来的渲染完全不变，多步回合里只有中间步骤的说明跟着折。
 */
fun layoutTurn(steps: List<ChatMessage.Assistant>): TurnLayout {
    val tailIndex = steps.indexOfLast { it.blocks.isNotEmpty() }
        // 最后一段正文之后还有工具调用：那段正文是给工具看的，留在过程里。
        .takeIf { index -> index >= 0 && steps.drop(index + 1).none { it.tools.isNotEmpty() } }

    val items = ArrayList<TurnItem>(steps.size * 2)
    val tools = ArrayList<ToolBlock>()
    var thinkingCount = 0
    steps.forEachIndexed { index, step ->
        step.thinking?.let { block ->
            thinkingCount += 1
            items += TurnItem.Thought("${step.id}:thinking", index, block)
        }
        if (index != tailIndex && step.blocks.isNotEmpty()) {
            items += TurnItem.Text("${step.id}:text", index, step.blocks)
        }
        step.tools.forEach { tool ->
            tools += tool
            items += TurnItem.Tool(tool.id, index, tool)
        }
    }

    return TurnLayout(
        items = items,
        tools = tools,
        thinkingCount = thinkingCount,
        tailBlocks = tailIndex?.let { steps[it].blocks }.orEmpty(),
        images = steps.flatMap { it.images }.distinctBy { it.path },
        stepCount = steps.size,
    )
}
