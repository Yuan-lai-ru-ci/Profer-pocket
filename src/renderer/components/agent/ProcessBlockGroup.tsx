import * as React from 'react'
import { ChevronRight } from 'lucide-react'
import { cn } from '@/lib/utils'
import { getToolDisplayName, getToolIcon } from './tool-utils'
import type {
  SDKContentBlock,
  SDKMessage,
  SDKToolResultBlock,
  SDKToolUseBlock,
  SDKUserMessage,
} from '@profer/shared'

interface ProcessBlockGroupProps {
  blocks: SDKContentBlock[]
  isStreaming?: boolean
  keepExpandedAfterComplete: boolean
  /**
   * R10：强制刷新后，若整轮内容都被归入本分组（无外置最终回复块），首次挂载即展开，
   * 保证用户刷新后立刻能看到最终输出。仅影响初始状态，用户手动收起后不再自动展开。
   */
  defaultExpanded?: boolean
  // 该过程组是否为整条消息的末尾项：是则流式中保留最后一段为正常显示，
  // 否则（最终答案已作为后续兄弟块外置）整组统一弱化。
  isMessageTail?: boolean
  children: React.ReactNode
}

const MAX_PROCESS_GROUP_ICONS = 4
const PROCESS_GROUP_COLLAPSE_DURATION_MS = 500
const PROCESS_GROUP_AUTO_COLLAPSE_SOUND_DELAY_MS = 900
const PROCESS_GROUP_AUTO_COLLAPSE_COUNTDOWN_SECONDS = 3

interface IndexedContentBlock {
  block: SDKContentBlock
  index: number
}

export type AssistantTurnRenderItem =
  | { type: 'block'; item: IndexedContentBlock }
  | { type: 'process-group'; items: IndexedContentBlock[] }

/**
 * R10：强制刷新（重新加载会话）后，某个过程分组是否应**默认展开**。
 *
 * 仅在「本轮内容全部被归入过程分组、没有任何外置输出块」时展开——这正是用户反馈的
 * 「桌面已显示但移动端输出全部缩在『执行过程』里、点不出来」的形状；
 * 若最终回复已作为兄弟块外置（常态），展开过程分组只会把工具调用噪声顶到用户眼前，
 * 因此不展开，保持与桌面一致的默认观感。
 */
export function shouldDefaultExpandProcessGroup(
  items: readonly AssistantTurnRenderItem[],
  itemIndex: number,
  options: { forceReload?: boolean } = {},
): boolean {
  if (!options.forceReload) return false
  if (!Number.isInteger(itemIndex) || itemIndex !== items.length - 1) return false
  if (items[itemIndex]?.type !== 'process-group') return false
  return !items.some((item) => item.type === 'block')
}

interface BuildAssistantTurnRenderItemsOptions {
  isStreaming?: boolean
  completedToolResultIds?: Set<string>
}

export function buildCompletedToolResultIds(turnMessages: SDKMessage[]): Set<string> {
  const ids = new Set<string>()
  for (const msg of turnMessages) {
    if (msg.type !== 'user') continue
    const userMsg = msg as SDKUserMessage
    const blocks = userMsg.message?.content
    if (!Array.isArray(blocks)) continue
    for (const b of blocks) {
      if (b.type !== 'tool_result') continue
      const rb = b as SDKToolResultBlock
      ids.add(rb.tool_use_id)
    }
  }
  return ids
}

interface TrailingOutputSplit {
  /** 最终正文（最后一段连续 text）在原数组中的起始下标 */
  textStartIndex: number
  /** 最终正文结束下标（含） */
  textEndIndex: number
}

/**
 * 定位「最终正文」区间：数组里最后一段连续的 text 块。
 *
 * 旧实现要求数组最后一个块必须是 text，否则整轮（含最终回复）会被整体折叠进「执行过程」。
 * 但真实流式数据里存在 text 之后又追加 thinking 的形态：
 *  - 同一条 assistant 消息里 reasoning 晚于正文到达（`[text, thinking]`）；
 *  - 正文之后紧跟一条只含 thinking 的收尾消息（turn 内聚合后同样以 thinking 结尾）。
 * 这两种情况下正文才是应当直接可见的交付内容，必须外置；末尾 thinking 归入过程组。
 * 若正文之后还跟着 tool_use 等块，说明这段 text 更可能是给工具看的中间说明，保持整组折叠。
 *
 * 注意：桌面自 3cdc9fb3 起按 runtime 关闭 thinking 渲染（showThinking={agentRuntime !== 'pi'}）；
 * pocket 尚未接该通道（已登记为窗口外遗留差异），但结论相同：正文一旦被一起折叠，
 * 这一轮就只剩「执行过程：N 条消息」折叠头，正文彻底看不见。
 */
function getTrailingOutputSplit(blocks: SDKContentBlock[]): TrailingOutputSplit | null {
  let textEndIndex = -1
  for (let index = blocks.length - 1; index >= 0; index--) {
    if (blocks[index]?.type === 'text') {
      textEndIndex = index
      break
    }
  }
  if (textEndIndex < 0) return null

  for (let index = textEndIndex + 1; index < blocks.length; index++) {
    if (blocks[index]?.type !== 'thinking') return null
  }

  let textStartIndex = textEndIndex
  while (textStartIndex > 0 && blocks[textStartIndex - 1]?.type === 'text') {
    textStartIndex -= 1
  }
  return { textStartIndex, textEndIndex }
}

function areToolsBeforeIndexCompleted(
  blocks: SDKContentBlock[],
  endIndex: number,
  completedToolResultIds: Set<string> | undefined,
): boolean {
  if (!completedToolResultIds) return false

  // 末尾 text 前的所有 tool_use 索引（用于区分「单工具」与「多工具收尾」场景）
  const toolIndices: number[] = []
  for (let index = 0; index < endIndex; index++) {
    const block = blocks[index]
    if (block?.type !== 'tool_use') continue
    toolIndices.push(index)
  }
  if (toolIndices.length === 0) {
    // 没有 tool_use 时不认为"工具已完成"——避免流式中只有 thinking + 尾部 text
    // 时把还可能变成中间过程的 text 提前外置。
    return false
  }

  // 多工具长序列：允许「最后一个工具的 result 还在路上」时也把尾部 text 外置——
  // 此时 text 几乎可以确定是最终回复（Agent 已开始收尾输出），
  // 不应因最后一个工具结果晚到而把整段回复折叠进执行过程。
  // 单工具场景保持保守：仅一个 tool_use 且结果未到，text 仍可能是给工具看的中间说明。
  for (let i = 0; i < toolIndices.length; i++) {
    const isLastTool = i === toolIndices.length - 1
    if (toolIndices.length > 1 && isLastTool) continue
    const toolBlock = blocks[toolIndices[i]!] as SDKToolUseBlock
    if (!completedToolResultIds.has(toolBlock.id)) return false
  }

  return true
}

export function buildAssistantTurnRenderItems(
  blocks: SDKContentBlock[],
  options: BuildAssistantTurnRenderItemsOptions = {},
): AssistantTurnRenderItem[] {
  if (blocks.length === 0) return []

  // 流式阶段最后的 text 还不稳定，后续工具调用可能会把它变成中间过程。
  // 只有当前面所有工具都有结果时，才把尾部 text 视作交付输出提前外置，降低完成瞬间的跳动。
  const hasProcessBlock = blocks.some((block) => block.type === 'tool_use' || block.type === 'thinking')
  const outputSplit = getTrailingOutputSplit(blocks)
  const canSplitStreamingFinalOutput = options.isStreaming
    && hasProcessBlock
    && outputSplit !== null
    && outputSplit.textStartIndex > 0
    && areToolsBeforeIndexCompleted(blocks, outputSplit.textStartIndex, options.completedToolResultIds)

  if (options.isStreaming && hasProcessBlock && !canSplitStreamingFinalOutput) {
    return [{
      type: 'process-group',
      items: blocks.map((block, index) => ({ block, index })),
    }]
  }

  if (outputSplit === null) {
    return [{
      type: 'process-group',
      items: blocks.map((block, index) => ({ block, index })),
    }]
  }

  const { textStartIndex, textEndIndex } = outputSplit
  // 正文之前的步骤 + 正文之后仅剩的 thinking（收尾思考）统一归入过程组，
  // 保证最终正文始终作为过程组之外可见的交付内容渲染。
  const processItems: IndexedContentBlock[] = []
  for (let index = 0; index < textStartIndex; index++) {
    const block = blocks[index]
    if (!block) continue
    processItems.push({ block, index })
  }
  for (let index = textEndIndex + 1; index < blocks.length; index++) {
    const block = blocks[index]
    if (!block) continue
    processItems.push({ block, index })
  }

  const items: AssistantTurnRenderItem[] = []
  if (processItems.length > 0) {
    items.push({ type: 'process-group', items: processItems })
  }

  for (let index = textStartIndex; index <= textEndIndex; index++) {
    const block = blocks[index]
    if (!block) continue
    items.push({ type: 'block', item: { block, index } })
  }

  return items
}

function buildProcessGroupSummary(blocks: SDKContentBlock[]): string {
  let toolCount = 0
  let messageCount = 0

  for (const block of blocks) {
    if (block.type === 'tool_use') {
      toolCount += 1
    } else if (block.type === 'thinking' || block.type === 'text') {
      messageCount += 1
    }
  }

  const parts: string[] = []
  if (toolCount > 0) parts.push(`${toolCount} 次工具调用`)
  if (messageCount > 0) parts.push(`${messageCount} 条消息`)
  const summary = parts.join('，') || '过程'
  return `执行过程：${summary}`
}

export function buildProcessGroupToolNames(blocks: SDKContentBlock[]): string[] {
  const toolNames: string[] = []
  const seen = new Set<string>()

  for (const block of blocks) {
    if (block.type !== 'tool_use') continue
    const toolBlock = block as SDKToolUseBlock
    if (seen.has(toolBlock.name)) continue
    seen.add(toolBlock.name)
    toolNames.push(toolBlock.name)
  }

  return toolNames
}

/**
 * 过程组自动折叠的决策状态（对应组件内的四个 ref）。
 *
 * pocket 平台适配：这些状态在组件里由 useRef 承载，effect 的分支判断抽成本文件下方的纯函数
 * `resolveProcessGroupCollapse()`。pocket 仓库没有 DOM 测试设施（无 happy-dom / @testing-library），
 * 把分支判断抽成纯函数才能让「1.7.2 折叠自锁」与「R10 默认展开」的协同真正被单测锁定。
 * 分支顺序与语义逐条对应桌面 `ProcessBlockGroup.tsx` 的 useEffect（仅额外保留 R10 分支）。
 */
export interface ProcessGroupCollapseState {
  /** 用户是否手动切换过：手动后不再被自动展开/折叠覆盖 */
  userToggled: boolean
  /** 上一轮是否处于 streaming：用于识别「刚刚结束」→ 触发自动折叠 */
  wasStreaming: boolean
  /** 1.7.2 自锁：上一轮是否已排好自动折叠倒计时 */
  collapseScheduled: boolean
  /** 1.7.2 自锁：是否已完成一次自动折叠 */
  collapseDone: boolean
}

export interface ProcessGroupCollapseInput extends ProcessGroupCollapseState {
  isStreaming: boolean
  keepExpandedAfterComplete: boolean
  /** R10：强制刷新后整轮都落在过程组内时的**首次挂载**默认态（不是持续约束） */
  defaultExpanded: boolean
}

/**
 * 决策结果，与桌面 useEffect 的分支一一对应：
 * - `expand`：新一轮 streaming 开始 / 需保持展开 → 清定时器与倒计时并展开
 *   （例外：上一轮已排定折叠时走 `hold-scheduled`，既不取消也不重新展开）
 * - `default-expanded`：pocket R10 平台分支 —— 首次挂载且未排定折叠时默认展开
 * - `collapse-now`：无倒计时直接收起（用户手动干预时不覆盖其选择）
 * - `schedule-collapse`：排定「900ms 后显示 3 秒倒计时 → 折叠」，并置自锁
 * - `hold-scheduled`：上一轮已排定折叠，新一轮 streaming 不得撤销它（自锁核心）
 * - `already-scheduled`：折叠已在调度中，勿重复排定、勿改展开态
 * - `skip-collapse-done`：已完成一次自动折叠，此后不参与 streaming 驱动的展开/折叠
 */
export type ProcessGroupCollapseOutcome =
  | 'expand'
  | 'default-expanded'
  | 'collapse-now'
  | 'schedule-collapse'
  | 'hold-scheduled'
  | 'already-scheduled'
  | 'skip-collapse-done'

export interface ProcessGroupCollapsePlan {
  outcome: ProcessGroupCollapseOutcome
  /** 是否调用 clearAutoCollapseTimers() 取消已排定的定时器 */
  clearTimers: boolean
  /** 是否 setCollapseCountdown(null) 清零倒计时显示 */
  resetCountdown: boolean
  /** 展开态目标；undefined = 不改动当前展开态（不覆盖用户的手动选择） */
  targetExpanded?: boolean
  /** 下个 effect 周期应写回的 ref 状态 */
  next: ProcessGroupCollapseState
}

/**
 * 决定过程组本次 effect 该做什么（纯函数，无副作用）。
 *
 * 桌面语义（权威）+ pocket R10 平台语义合二为一：
 *  - 自锁：已排定折叠不会被新一轮 streaming 取消；折叠完成后不再被 streaming 重新展开；
 *    用户手动切换时由组件侧复位两把锁（见两处按钮 onClick）。
 *  - R10：`defaultExpanded` 只在「首次挂载 + 用户未干预 + 未处于流式接力」时生效，
 *    且不得撤销上一轮已排定的折叠（防御分支见 `already-scheduled`）。
 */
export function resolveProcessGroupCollapse(input: ProcessGroupCollapseInput): ProcessGroupCollapsePlan {
  const refs = (override: Partial<ProcessGroupCollapseState> = {}): ProcessGroupCollapseState => ({
    userToggled: input.userToggled,
    wasStreaming: input.wasStreaming,
    collapseScheduled: input.collapseScheduled,
    collapseDone: input.collapseDone,
    ...override,
  })
  const noop = (
    outcome: ProcessGroupCollapseOutcome,
    next: ProcessGroupCollapseState,
  ): ProcessGroupCollapsePlan => ({
    outcome,
    clearTimers: false,
    resetCountdown: false,
    targetExpanded: undefined,
    next,
  })

  // 1.7.2：已完成一次自动折叠——此后不参与 streaming 驱动的展开/折叠（除非用户手动展开）
  if (input.collapseDone) return noop('skip-collapse-done', refs())

  if (input.isStreaming || input.keepExpandedAfterComplete) {
    // 新一轮 streaming 开始：若上一轮已调度折叠，不取消它、也不重新展开
    if (input.collapseScheduled) return noop('hold-scheduled', refs({ wasStreaming: true }))

    const userToggled = input.isStreaming && !input.wasStreaming ? false : input.userToggled
    return {
      outcome: 'expand',
      clearTimers: true,
      resetCountdown: true,
      targetExpanded: userToggled ? undefined : true,
      next: refs({ userToggled, wasStreaming: input.isStreaming }),
    }
  }

  // R10：defaultExpanded 是**首次挂载**的默认态，不是持续约束
  // （故意不列入 effect deps：只有挂载时的展开语义，用户手动收起后不得被重新弹开）。
  if (input.defaultExpanded && !input.userToggled && !input.wasStreaming) {
    // 防御分支：已排定的折叠倒计时优先于默认展开，绝不撤销上一轮排定的折叠。
    // （实测不可达——进入调度后 wasStreaming 会在 hold-scheduled / already-scheduled 之间往返，
    //  不会出现「非流式 + 未接力 + 已排定」的组合；此处仅锁住不变式。）
    if (input.collapseScheduled) return noop('already-scheduled', refs({ wasStreaming: false }))
    return {
      outcome: 'default-expanded',
      clearTimers: true,
      resetCountdown: true,
      targetExpanded: true,
      next: refs(),
    }
  }

  const shouldAutoCollapseAfterCompletion = input.wasStreaming && !input.userToggled

  if (!shouldAutoCollapseAfterCompletion) {
    return {
      outcome: 'collapse-now',
      clearTimers: false,
      resetCountdown: false,
      targetExpanded: input.userToggled ? undefined : false,
      next: refs({ wasStreaming: false }),
    }
  }
  if (input.collapseScheduled) return noop('already-scheduled', refs({ wasStreaming: false }))  // 已在调度，勿重复

  return {
    outcome: 'schedule-collapse',
    clearTimers: false,
    resetCountdown: false,
    targetExpanded: undefined,
    next: refs({ wasStreaming: false, collapseScheduled: true }),
  }
}

export function ProcessBlockGroup({ blocks, isStreaming, keepExpandedAfterComplete, defaultExpanded = false, isMessageTail = false, children }: ProcessBlockGroupProps): React.ReactElement {
  const shouldExpandByDefault = !!isStreaming || keepExpandedAfterComplete || defaultExpanded
  const [expanded, setExpanded] = React.useState(shouldExpandByDefault)
  const [shouldRenderContent, setShouldRenderContent] = React.useState(shouldExpandByDefault)
  const [collapseCountdown, setCollapseCountdown] = React.useState<number | null>(null)
  const userToggledRef = React.useRef(false)
  const wasStreamingRef = React.useRef(!!isStreaming)
  const autoCollapseTimersRef = React.useRef<number[]>([])
  // 1.7.2 折叠自锁：上一轮结束已调度折叠倒计时；折叠完成后不再被 streaming 分支重新展开。
  // 队列无缝衔接时 streaming false 窗口极短，新一轮 true 分支会 clearAutoCollapseTimers() + 重新展开，
  // 导致上一轮折叠被取消——用这两个 ref 锁住「已调度的折叠」不被撤销。
  const collapseScheduledRef = React.useRef(false)
  const collapseDoneRef = React.useRef(false)
  const contentRef = React.useRef<HTMLDivElement>(null)
  const [measuredHeight, setMeasuredHeight] = React.useState<number | undefined>(undefined)

  const clearAutoCollapseTimers = React.useCallback(() => {
    for (const timer of autoCollapseTimersRef.current) window.clearTimeout(timer)
    autoCollapseTimersRef.current = []
  }, [])

  React.useEffect(() => {
    // 分支判断全部来自纯函数 resolveProcessGroupCollapse()（自锁 + R10 协同见其文档），
    // 这里只按决策执行副作用。deps 与桌面一致：defaultExpanded 故意不列入（R10 只认挂载态）。
    const plan = resolveProcessGroupCollapse({
      isStreaming: !!isStreaming,
      keepExpandedAfterComplete,
      defaultExpanded,
      userToggled: userToggledRef.current,
      wasStreaming: wasStreamingRef.current,
      collapseScheduled: collapseScheduledRef.current,
      collapseDone: collapseDoneRef.current,
    })

    userToggledRef.current = plan.next.userToggled
    wasStreamingRef.current = plan.next.wasStreaming
    collapseScheduledRef.current = plan.next.collapseScheduled
    collapseDoneRef.current = plan.next.collapseDone

    if (plan.clearTimers) clearAutoCollapseTimers()
    if (plan.resetCountdown) setCollapseCountdown(null)
    if (plan.targetExpanded !== undefined) setExpanded(plan.targetExpanded)

    if (plan.outcome !== 'schedule-collapse') return

    const soundDelayTimer = window.setTimeout(() => {
      setCollapseCountdown(PROCESS_GROUP_AUTO_COLLAPSE_COUNTDOWN_SECONDS)

      for (let second = PROCESS_GROUP_AUTO_COLLAPSE_COUNTDOWN_SECONDS - 1; second >= 1; second--) {
        const elapsed = (PROCESS_GROUP_AUTO_COLLAPSE_COUNTDOWN_SECONDS - second) * 1000
        autoCollapseTimersRef.current.push(window.setTimeout(() => setCollapseCountdown(second), elapsed))
      }

      autoCollapseTimersRef.current.push(window.setTimeout(() => {
        setCollapseCountdown(null)
        setExpanded(false)
        // 折叠完成：释放调度锁、置完成锁，后续不再被 streaming 分支重新展开
        collapseScheduledRef.current = false
        collapseDoneRef.current = true
      }, PROCESS_GROUP_AUTO_COLLAPSE_COUNTDOWN_SECONDS * 1000))
    }, PROCESS_GROUP_AUTO_COLLAPSE_SOUND_DELAY_MS)
    autoCollapseTimersRef.current.push(soundDelayTimer)
  }, [clearAutoCollapseTimers, isStreaming, keepExpandedAfterComplete])

  // 组件卸载时清理全部折叠定时器。effect 内不再注册 cleanup，
  // 避免新一轮 streaming 重跑 effect 时把上一轮已调度的折叠倒计时误清掉。
  React.useEffect(() => {
    return clearAutoCollapseTimers
  }, [clearAutoCollapseTimers])

  // 折叠前测量实际高度，用于丝滑的 height 过渡（子元素不 reflow，只裁剪边界）
  React.useEffect(() => {
    if (expanded) {
      setShouldRenderContent(true)
      setMeasuredHeight(undefined)
      return
    }

    // 折叠时：先测量当前高度，触发 height 过渡动画，动画结束后卸载 DOM
    const el = contentRef.current
    if (el) {
      const h = el.scrollHeight
      setMeasuredHeight(h)
      // 强制浏览器在下一帧开始从 h → 0 的过渡
      requestAnimationFrame(() => setMeasuredHeight(0))
    }

    const timer = window.setTimeout(() => setShouldRenderContent(false), PROCESS_GROUP_COLLAPSE_DURATION_MS)
    return () => window.clearTimeout(timer)
  }, [expanded])

  const summary = React.useMemo(
    () => buildProcessGroupSummary(blocks),
    [blocks],
  )
  const toolNames = React.useMemo(() => buildProcessGroupToolNames(blocks), [blocks])
  const visibleToolNames = toolNames.slice(0, MAX_PROCESS_GROUP_ICONS)
  const hiddenToolCount = Math.max(0, toolNames.length - visibleToolNames.length)

  // 内容区子项渲染策略：
  // - 流式中：每个新块有入场动画，最新一段（消息末尾过程组的最后一个 child）保持正常显示，
  //   其余步骤轻微弱化以引导视觉重心到最下方。
  // - 流式结束后用户展开：所有内容以正常颜色显示，无动画。
  const childArray = React.Children.toArray(children)
  const renderContentChildren = (): React.ReactNode =>
    childArray.map((child, i) => {
      const isLast = i === childArray.length - 1
      const dimmed = isStreaming && !(isMessageTail && isLast)
      return (
        <div
          key={i}
          className={cn(
            dimmed && 'opacity-80',
            isStreaming && 'animate-in fade-in slide-in-from-top-1 duration-200',
          )}
        >
          {child}
        </div>
      )
    })

  return (
    <div className="space-y-1.5">
      <button
        type="button"
        className={cn(
          'flex max-w-full items-center gap-2 py-0.5 text-left transition-opacity group',
          'hover:opacity-70',
        )}
        onClick={() => {
          userToggledRef.current = true
          // 1.7.2：手动展开/收起后重置自锁，后续按 streaming 规则重新参与折叠
          collapseScheduledRef.current = false
          collapseDoneRef.current = false
          clearAutoCollapseTimers()
          setCollapseCountdown(null)
          setExpanded((prev) => !prev)
        }}
      >
        <ChevronRight
          className={cn(
            'size-3 shrink-0 text-muted-foreground/40 transition-transform duration-150',
            expanded && 'rotate-90',
          )}
        />
        <span className="min-w-0 truncate text-[14px] text-muted-foreground">{summary}</span>
        {collapseCountdown !== null && (
          <span className="shrink-0 text-[12px] tabular-nums text-muted-foreground/50">
            （{collapseCountdown}）
          </span>
        )}
        {visibleToolNames.length > 0 && (
          <span className="flex shrink-0 items-center gap-1 text-muted-foreground/60">
            {visibleToolNames.map((toolName) => {
              const ToolIcon = getToolIcon(toolName)
              return (
                <ToolIcon
                  key={toolName}
                  className="size-3.5"
                  aria-label={getToolDisplayName(toolName)}
                />
              )
            })}
            {hiddenToolCount > 0 && (
              <span className="text-[11px] tabular-nums text-muted-foreground/60">
                +{hiddenToolCount}
              </span>
            )}
          </span>
        )}
      </button>

      {shouldRenderContent && (
        <div
          ref={contentRef}
          className="overflow-hidden"
          style={{
            height: measuredHeight !== undefined ? `${measuredHeight}px` : 'auto',
            opacity: expanded ? 1 : 0,
            transition: measuredHeight !== undefined
              ? `height ${PROCESS_GROUP_COLLAPSE_DURATION_MS}ms ease-in-out, opacity ${PROCESS_GROUP_COLLAPSE_DURATION_MS}ms ease-in-out`
              : `opacity ${PROCESS_GROUP_COLLAPSE_DURATION_MS}ms ease-in-out`,
          }}
        >
          <div className="space-y-2">
            {renderContentChildren()}
            <button
                type="button"
                className="flex items-center gap-1 text-xs text-foreground/40 hover:text-foreground/70 transition-colors"
                onClick={() => {
                  userToggledRef.current = true
                  // 1.7.2：手动收起后重置自锁，后续按 streaming 规则重新参与折叠
                  collapseScheduledRef.current = false
                  collapseDoneRef.current = false
                  clearAutoCollapseTimers()
                  setCollapseCountdown(null)
                  setExpanded(false)
                }}
              >
                <ChevronRight className="size-3 -rotate-90" />
                <span>收起</span>
              </button>
            </div>
          </div>
        )}
      </div>
  )
}
