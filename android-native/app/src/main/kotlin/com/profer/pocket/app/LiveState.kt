package com.profer.pocket.app

import com.profer.pocket.model.PendingInteraction
import com.profer.pocket.model.SessionChat

/** 实时活动胶囊当前该显示什么。 */
internal sealed interface LiveKind {
    /** 正常跑：有工具名就显示工具，否则是普通回复中。 */
    data class Working(val toolName: String?) : LiveKind

    data object WaitingPermission : LiveKind
    data object WaitingAnswer : LiveKind
    data object WaitingPlan : LiveKind

    /** 任务刚结束：胶囊先停一下告诉用户结果，再收起。 */
    data object Completed : LiveKind

    /** 结束了但有错误。 */
    data object Failed : LiveKind
}

internal data class LiveState(
    val sessionId: String,
    val title: String,
    val kind: LiveKind,
    /** 除当前会话外，还有几个会话在跑或等人。 */
    val others: Int,
)

/**
 * 任务结束时该显示「已完成」还是「有错误」。
 *
 * `lastError` 来自会话的最后一次 run；用户主动停止也走这个分支，
 * 但它与正常结束在胶囊上不必区分（都是「不再跑了」），用错误与否区分更有信息量。
 */
internal fun completionKind(lastError: String?): LiveKind =
    if (lastError.isNullOrBlank()) LiveKind.Completed else LiveKind.Failed

/** 最近一条消息的时间；没有消息时算 0，让新会话排在后面。 */
private fun SessionChat.lastActivityAt(): Long = messages.lastOrNull()?.at ?: 0L

/**
 * 挑一个会话挂到状态栏。
 *
 * 优先级：等人 > 在跑。追问（权限确认 / 提问 / 计划审批）时会话的 `running` 仍是 true
 * （桌面端在等回答），只按 running 挑会一直显示「回复中」，看起来像还在干活。
 * 所以先挑有 `pendingInteraction` 的会话，再退回到运行中的会话。
 *
 * 传入顺序不再是唯一依据：同一优先级里取「最近有消息」的会话，
 * 避免一个卡住的旧会话永远占着胶囊。
 */
internal fun selectLiveState(chats: Collection<SessionChat>): LiveState? {
    if (chats.isEmpty()) return null
    val waiting = chats.filter { it.pendingInteraction != null }
    val running = chats.filter { it.running }
    val target = waiting.maxByOrNull { it.lastActivityAt() }
        ?: running.maxByOrNull { it.lastActivityAt() }
        ?: return null
    val kind = when (val pending = target.pendingInteraction) {
        is PendingInteraction.Permission -> LiveKind.WaitingPermission
        is PendingInteraction.Question -> LiveKind.WaitingAnswer
        is PendingInteraction.PlanApproval -> LiveKind.WaitingPlan
        null -> LiveKind.Working(target.toolCalls.lastOrNull()?.name)
    }
    // 同一会话可能既在 waiting 又在 running，按 sessionId 去重再减掉自己。
    val others = (waiting + running).distinctBy { it.sessionId }.size - 1
    return LiveState(
        sessionId = target.sessionId,
        title = target.title.ifBlank { "会话" },
        kind = kind,
        others = others.coerceAtLeast(0),
    )
}
