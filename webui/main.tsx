/**
 * Profer WebUI —— 单会话极简页。
 *
 * 设计：一个 URL 只承载一个会话，不引入 Jotai / 侧栏 / Tab / 文件预览 / 引用跳转。
 * 通信层直接复用同仓 pocket 的 WsClient（token、心跳、重连、命令配对、断线重放都是现成的），
 * 页面与 /ws 同源；会话内容、发消息、交互审批全部经主进程单写者，本页不读写任何会话文件。
 *
 * 交互审批走「取能力不搬文件」：数据结构与命令语义对齐桌面端三张横幅，
 * UI 用自写的 interactions.tsx，不引入 electronapi-stub / atoms / globals.css。
 */
import * as React from 'react'
import { createRoot } from 'react-dom/client'
import 'katex/dist/katex.min.css'
import katexCss from 'katex/dist/katex.min.css?inline'
import { MarkdownWithFiles } from './markdown-with-files'
import { buildTurnFileIndex } from './file-reference'
import { normalizeLatexDelimiters } from './normalize-latex'
import type { AgentSessionMeta, SDKMessage } from '@profer/shared'
import { groupIntoTurns, toTranscript, type MessageGroup } from '@profer/session-core'
import { TurnAttachments, TurnImages, extractTurnExtras, stripContextBlocks, type ImageLoadResult } from './turn-extras'
import { TurnProcess } from './turn-process'
import { TaskProgressCard } from './task-progress-card'
import { buildHistoricalTaskSubjects } from './task-progress'
import { buildAssistantTimeline, isTurnFinished, isTurnSettled, turnHasTrailingPendingTool } from './turn-timeline'
import { DiagOverlay } from './diag'
import {
  SOURCE_LABELS,
  describeUsage,
  describeUsageDetail,
  formatClock,
  resolveTurnModel,
  resolveTurnSource,
  resolveTurnTime,
  sumTurnUsage,
} from './turn-meta'
import { OLDER_PAGE, decodeMessagePage, mergePrepend, mergeReloadPage, tailPageSize, upsertMessage } from './message-page'
import { FOLLOW_DEFAULT, modelLabelFor, normalizeChannelModels, resolveStrengthState, type ModelOption } from './model-selection'
import { SessionSettingsMenu, describeSessionModel } from './session-settings'
import { PresetPanel } from './preset-panel'
import {
  normalizePresets,
  resolveCurrentPreset,
  selectablePresets,
  type PresetPatch,
  type PresetReference,
  type WebPreset,
} from './preset-model'
import { applyPageTitle } from './page-title'
import { isImageAttachment, resolveSendText } from './attachments'
import { GoalBar, GoalEditor, GoalLauncher, type GoalAction } from './goal'
import { formatGoalBudget, goalStatusLabel, goalStatusText, normalizeGoal, normalizeGoalHistory, type GoalContract, type GoalLimits, type GoalState } from './goal-model'
import { parseGoalCommand, type GoalCommand } from './goal-command'
import { describeContextUsage, formatTokens, latestContextTokens, type ContextUsageInfo } from './context-usage'
import { extractMentionedSkills, type FileOption, type SkillOption } from './mentions'
import { normalizeVisualizations, type VisualizationItem } from './visualization'
import { WsClient, defaultWsUrl } from '../src/renderer/pocket/ws-client'
import {
  type AgentEventPayload,
  type DataUrlEnvelope,
  type GoalEnvelope,
  type GoalHistoryEnvelope,
  type PiReasoningCapabilityEnvelope,
  type SessionDetailEnvelope,
  type SessionMetaPayload,
  type VisualizationEnvelope,
} from './protocol'
import { useAutoScroll } from './use-auto-scroll'
import { useDiagnostics } from './use-diagnostics'
import { useAttachments } from './use-attachments'
import { useInteractions } from './use-interactions'
import { WEBUI_BUILD } from './build-info'
import { Topbar } from './topbar'
import { SessionInfo } from './session-info'
import { MessageHistory } from './message-history'
import { buildHistoryEntries } from './history-list'
import { fileNameOf, type BinaryLoadResult, type TextPreviewResult } from './file-preview'
import { openStandalonePreview, type StandalonePreviewContent } from './standalone-preview'
import { renderMarkdownToStaticHtml } from './markdown-static'
import { TurnActions } from './turn-actions'
import { StopAndSend } from './stop-and-send'
import { ConfirmSheet } from './confirm-sheet'
import { RewindPlanSheet } from './rewind-plan-sheet'
import { RunStatus } from './run-status'
import { collectPendingBackgroundTasks } from './background-tasks'
import {
  applyDelegationBlocked,
  applyDelegationSessionUpdate,
  normalizeBlockedEvent,
  normalizeDelegations,
  resolveDelegationBlocked,
  type DelegationsView,
} from './delegations'
import {
  deriveRunOutcomeFromHistory,
  normalizePersistenceEvent,
  normalizeRetryEvent,
  normalizeRunOutcomeEvent,
  runOutcomeTone,
  type PersistenceWarning,
  type RetryInfo,
  type RunOutcomeInfo,
} from './run-outcome'
import { findTurnIndexForMessage, normalizeSessionSearchMatches, type SessionSearchMatch } from './session-search'
import type {
  DelegationSnapshotEnvelope,
  RewindExecuteEnvelope,
  RewindPlanFilePayload,
  RewindPrepareEnvelope,
  RewindPlanPayload,
  SessionSearchEnvelope,
} from './protocol'
import { copyText } from './clipboard'
import { findPreviousAssistantUuid, resolveRetryTurn } from './retry-turn'
import { isQueueUnsupportedError, isRunActiveError } from './run-state'
import { requestNotifyPermission, useCompletionNotify } from './use-completion-notify'
import { Composer } from './composer'
import { InteractionCards, normalizePermissionMode, type SendPermissionMode } from './interactions'
import './styles.css'

interface WebuiBoot {
  sessionId?: string
  token?: string
}

interface SessionMeta {
  title: string
  channelId: string
  modelId?: string
  workspaceId?: string
  permissionMode?: SendPermissionMode
  /** 内核决定用哪套推理档位：claude → effort，pi → 模型自带 levels */
  agentRuntime?: string
  /** 会话级强度覆盖；null/缺省表示跟随预设 */
  agentEffort?: string | null
  openAIThinkingLevel?: string | null
  /** 预设（岗位）：reference 是唯一真源，presetId 兼容旧数据 */
  presetId?: string
  presetReference?: PresetReference | null
  /** 会话所属工作区 slug —— 预设是工作区级配置，所有预设命令都要带它 */
  workspaceSlug?: string
  revision?: number
  /** 最近一次非正常结束（主进程落盘）：刷新后用它恢复结局行 */
  lastInterruptReason?: string
  lastInterruptLabel?: string
  lastInterruptAt?: number
  stoppedByUser?: boolean
}

/**
 * `list_channels` 的渠道条目。
 *
 * 只声明用得上的字段：模型表交给 `normalizeChannelModels` 自己校验，
 * 客户端不在这里二次建模 —— 免得服务端加字段时这里先坏。
 */
interface RemoteChannel {
  id: string
  name?: string
  provider?: string
  baseUrl?: string
  enabled?: boolean
  agentRuntimes?: string[]
  models?: unknown[]
}

declare global {
  interface Window { __PROFER_WEBUI_BOOT__?: WebuiBoot }
}

console.info('[profer-webui] build', WEBUI_BUILD)

/**
 * iOS Safari / 部分安卓 Chrome 的前进后退缓存（bfcache）会**连 JS 堆与 WebSocket
 * 一起冻结恢复**：ws-client 的重连定时器在冻结期间不跑，页面永远停在恢复那一刻的
 * DOM —— 表现为「正文输出到一半卡住、过程组不收、之后什么都不更新」，且用户以为
 * 刷新过了（返回标签页是缓存恢复，不是重新加载）。持久化恢复时直接整体重载：
 * 重新加载页面、重新拉最新 bundle、重建连接与状态。
 */
window.addEventListener('pageshow', (event) => {
  if (event.persisted) window.location.reload()
})

/**
 * 关掉浏览器的滚动位置恢复：刷新时浏览器会把视口拉回刷新前的位置，
 * 而这个恢复发生在 React 贴底 effect 之后，把「自动滚到最新消息」盖掉 ——
 * 表现为刷新后停在上面。页面自己决定滚动位置（首屏贴底、翻页回锚点）。
 */
if ('scrollRestoration' in window.history) window.history.scrollRestoration = 'manual'

/** 空态快捷入口：只填进输入框，不直接发出去，避免误触消耗一轮。 */
const QUICK_PROMPTS = ['总结一下当前进度', '接下来该做什么', '解释一下刚才的改动']

/** 搜索定位时一次取回的消息窗口：覆盖「行号与消息下标略有漂移」的换算误差。 */
const SEARCH_JUMP_WINDOW = 80

/**
 * 三态权限模式，与桌面端那个下拉是同一套东西（auto=自动审批 / bypassPermissions=完全自动 / plan=计划模式）。
 *
 * 早期版本这里是个「自动批准」开关，只替这个网页点允许、不改会话模式 ——
 * 结果两端各有一个长得像同一个意思的开关，实际互不相干：桌面仍按原模式弹卡。
 * 现在统一成一份真实状态，改这里就等于改会话。
 */
const PERMISSION_MODES: ReadonlyArray<{
  mode: SendPermissionMode
  short: string
  label: string
  hint: string
}> = [
  { mode: 'auto', short: '确认', label: '自动审批', hint: '安全操作自动放行，危险操作仍需你确认' },
  { mode: 'bypassPermissions', short: '自动', label: '完全自动', hint: '全部工具直接执行，不再逐项确认' },
  { mode: 'plan', short: '计划', label: '计划模式', hint: '只出方案不动手，你批准后才执行' },
]

/**
 * 凭据的本地副本。这个 key 必须与主进程 `webui-service.ts` 里未授权自愈页的脚本一致
 * （那边硬编码了同名字符串），否则手机上 cookie 被回收后就自愈不回来了。
 */
const TOKEN_STORAGE_KEY = 'profer-webui-token'

/**
 * localStorage 在部分隐私模式/配额为 0 时会**直接抛异常**。
 * 读不到只该失去「刷新自愈」，绝不能让整页在挂载前崩掉（那会表现为白屏 + 连不上）。
 */
function readStoredToken(): string {
  try {
    return localStorage.getItem(TOKEN_STORAGE_KEY) ?? ''
  } catch {
    return ''
  }
}

function storeToken(value: string): void {
  try {
    localStorage.setItem(TOKEN_STORAGE_KEY, value)
  } catch {
    /* 隐私模式下写不进去：仍然可以用当前这次会话，只是刷新后要重带 token */
  }
}

const boot = window.__PROFER_WEBUI_BOOT__
const query = new URLSearchParams(window.location.search)
/** 片段只在本机可见，不会进服务端日志与 Referer —— 适合做「永久链接」那种带凭据的书签。 */
const fragment = new URLSearchParams(window.location.hash.replace(/^#/, ''))
const pathMatch = window.location.pathname.match(/^\/s\/([^/]+)/)
const pathId = pathMatch?.[1] ? decodeURIComponent(pathMatch[1]) : ''
const sessionId = boot?.sessionId ?? query.get('session') ?? fragment.get('session') ?? pathId
const token = boot?.token ?? query.get('token') ?? fragment.get('token') ?? readStoredToken()

// 首屏凭据只用于过一次鉴权：立即从地址栏抹掉，避免留在浏览器历史 / Referer 里。
// 片段里的 token 不动 —— 用户是特意把它留在书签里的。
if (token) storeToken(token)
if (query.has('token')) {
  const clean = new URL(window.location.href)
  clean.searchParams.delete('token')
  window.history.replaceState(null, '', clean.pathname + clean.search)
}

function isPresetReference(value: unknown): value is PresetReference {
  if (!value || typeof value !== 'object' || Array.isArray(value)) return false
  return typeof (value as { presetId?: unknown }).presetId === 'string'
    && (value as { presetId: string }).presetId.length > 0
}

function App(): React.ReactElement {
  const [messages, setMessages] = React.useState<SDKMessage[]>([])
  /**
   * 首屏消息是否已从服务端拉取完成。
   * 刷新时 WS 先连上、事件回放也可能先到，但 get_sdk_messages 还在路上；
   * 这段时间不能渲染空态（会闪一下「开始对话」+ 快捷提问）和回放带出的结局行。
   */
  const [historyLoaded, setHistoryLoaded] = React.useState(false)
  const [connection, setConnection] = React.useState('连接中…')
  const [connected, setConnected] = React.useState(false)
  const [meta, setMeta] = React.useState<SessionMeta | null>(null)
  const [running, setRunning] = React.useState(false)
  /** 给 WS 回调用的实时镜像：回调闭包在 effect 创建时定格，必须用 ref 拿最新值。 */
  const runningRef = React.useRef(running)
  runningRef.current = running
  const messagesRef = React.useRef<SDKMessage[]>([])
  const [input, setInput] = React.useState('')
  const [busy, setBusy] = React.useState(false)
  /** 压缩 run 飞行中：按钮自身显示「压缩中…」，run 结束自动恢复 —— 不用 notice（没人清会残留）。 */
  const [compacting, setCompacting] = React.useState(false)
  /** 目标表单展开：入口在顶栏左上角（三段式导航），表单仍在消息流顶部 */
  const [goalOpen, setGoalOpen] = React.useState(false)
  /** 会话信息条：点标题胶囊（除模型行与历史按钮）显示 */
  const [infoOpen, setInfoOpen] = React.useState(false)
  /** 历史对话面板：本会话已加载回合的跳转表 */
  const [historyOpen, setHistoryOpen] = React.useState(false)
  /** 用户消息操作面板：目标回合下标 */
  const [actionsTurn, setActionsTurn] = React.useState<number | null>(null)
  /**
   * 服务端权威运行态。
   *
   * `running` / `sessionActive` 都是从事件流推断的，刷新、重连、断帧之后必然落后；
   * 落后时会对正在跑的会话发 `send_message`，被 claim 拒绝并弹红字。
   * 这个值来自 `session_detail.active`，发送路径以它为准。
   */
  const [serverActive, setServerActive] = React.useState(false)
  /** 「当前内核不支持追加」时要发送的文本：确认停止本轮后立即发出 */
  const [stopSendText, setStopSendText] = React.useState<string | null>(null)
  /** 压缩上下文前的确认：压缩会占一整轮，误点代价不小 */
  const [compactConfirmOpen, setCompactConfirmOpen] = React.useState(false)
  /** 编辑重发：是否同时还原文件改动（rewind both） */
  const [restoreFiles, setRestoreFiles] = React.useState(true)
  /** 两段式回退：prepare 出的计划等用户确认后执行 */
  const [rewindPlan, setRewindPlan] = React.useState<{
    planId: string
    revision: { session: number; files: number; state: number }
    files: RewindPlanFilePayload[]
    text: string
  } | null>(null)
  /** `/goal <新目标>` 撞上未完成目标时的替换确认 */
  const [pendingGoalStart, setPendingGoalStart] = React.useState<{ goal: string; contract?: GoalContract } | null>(null)
  /**
   * 上下文窗口大小（tokens）：context_window 事件 / 运行态快照下发。
   *
   * 主进程在 run_idle 后会清掉运行态快照（避免向历史会话注入旧窗口），所以
   * 「重新打开一个空闲会话」时快照里没有它 —— 这里用 localStorage 记住最后一次
   * 已知的窗口大小，占用条才不会在刷新后消失。窗口大小由模型决定，基本稳定；
   * 换模型后下一个 context_window 事件会纠正。
   */
  const [contextWindow, setContextWindowState] = React.useState<number | null>(null)
  const contextWindowKey = `profer-webui-ctx-window:${sessionId}`
  React.useEffect(() => {
    try {
      const saved = Number(window.localStorage.getItem(contextWindowKey))
      if (Number.isFinite(saved) && saved > 0) setContextWindowState(saved)
    } catch { /* localStorage 不可用时忽略 */ }
  }, [contextWindowKey])
  const setContextWindow = React.useCallback((value: number): void => {
    setContextWindowState(value)
    try { window.localStorage.setItem(contextWindowKey, String(value)) } catch { /* ignore */ }
  }, [contextWindowKey])
  /** 当前会话可用的技能（/ 提及候选）：list_skills 下发 */
  const [skillOptions, setSkillOptions] = React.useState<SkillOption[]>([])
  /** 当前会话可引用的文件（@ 提及候选）：list_workspace_files 下发 */
  const [fileOptions, setFileOptions] = React.useState<FileOption[]>([])
  /**
   * 技能加载结果：'ok'（N 个）/ 'empty'（主进程返回空 = 工作区没有启用技能）/
   * 'unsupported'（主进程没有 list_skills = 旧主进程，需要重启）/ 'error'。
   * 放进信息条展示，避免「@ 没反应」变成无头案。
   */
  const [skillStatus, setSkillStatus] = React.useState<'idle' | 'ok' | 'empty' | 'unsupported' | 'error'>('idle')
  /** 服务端带回的扫描失败原因（单个技能目录损坏等），显示在信息条里 */
  const [skillWarning, setSkillWarning] = React.useState<string | null>(null)
  const [acting, setActing] = React.useState(false)
  const [notice, setNotice] = React.useState('')
  const [goal, setGoal] = React.useState<GoalState | null>(null)
  /** 归档 Goal：清除/完成后的历史，只在展开时才有内容可看，但仍然随清除动作刷新 */
  const [goalHistory, setGoalHistory] = React.useState<GoalState[]>([])
  const [goalEditorOpen, setGoalEditorOpen] = React.useState(false)
  const [visualizations, setVisualizations] = React.useState<VisualizationItem[]>([])
  const [switchingMode, setSwitchingMode] = React.useState(false)
  /** 模型 + 强度菜单；数据来自 list_channels（模型表）与 session_detail（当前选择） */
  const [settingsMenuOpen, setSettingsMenuOpen] = React.useState(false)
  /** 由顶栏上下文百分比触发：打开菜单后直接定位到压缩项 */
  const [focusCompact, setFocusCompact] = React.useState(false)
  // 菜单一关闭就清掉定位标志：下次从标题普通打开不该还跳到压缩项
  React.useEffect(() => {
    if (!settingsMenuOpen && focusCompact) setFocusCompact(false)
  }, [settingsMenuOpen, focusCompact])
  /** 预设（岗位）：列表、默认值、管理面板。作用域由会话的 workspaceSlug 决定 */
  const [presets, setPresets] = React.useState<WebPreset[]>([])
  const [presetsLoaded, setPresetsLoaded] = React.useState(false)
  const [defaultPresetId, setDefaultPresetId] = React.useState('')
  const [presetPanelOpen, setPresetPanelOpen] = React.useState(false)
  const [channels, setChannels] = React.useState<RemoteChannel[] | null>(null)
  /** Pi 内核下模型自带的推理档位；Claude 内核为 null（走 effort 四档） */
  const [reasoningLevels, setReasoningLevels] = React.useState<string[] | null>(null)
  const [switchingModel, setSwitchingModel] = React.useState(false)
  const clientRef = React.useRef<WsClient | null>(null)
  const inputRef = React.useRef<HTMLTextAreaElement | null>(null)
  const lastCompletedRef = React.useRef(0)
  const [pageStart, setPageStart] = React.useState(0)
  const [hasOlder, setHasOlder] = React.useState(false)
  const [loadingOlder, setLoadingOlder] = React.useState(false)
  /** reload 要按「当前持有多少条」决定取多少尾部，但不能因此把 messages 放进依赖。 */
  const heldRef = React.useRef(0)
  /** 本轮运行结局：只由 run_completed 写入，新消息发出时清空 */
  const [runOutcome, setRunOutcome] = React.useState<RunOutcomeInfo | null>(null)
  /** 正在重试（第 n/m 次）；终态或 cleared 时收起 */
  const [retryInfo, setRetryInfo] = React.useState<RetryInfo | null>(null)
  /** 本轮记录落盘失败的告警（saved / pending 时自动清） */
  const [persistenceWarning, setPersistenceWarning] = React.useState<PersistenceWarning | null>(null)
  /** run_completed.backgroundTasksPending：计数未知时的兜底（真正的任务表从消息里推） */
  const [backgroundPending, setBackgroundPending] = React.useState(false)
  /** 运行中的委派子任务（快照 + 阻塞事件共同维护） */
  const [delegations, setDelegations] = React.useState<DelegationsView>({ running: [], blockedCount: 0 })
  /** 全量搜索仍在定位（服务端行号与消息下标的换算要翻页） */
  const [searchJumping, setSearchJumping] = React.useState(false)
  /** 定位完成后等 DOM 提交再滚：分组下标只对新 messages 有效 */
  const [pendingJumpIndex, setPendingJumpIndex] = React.useState<number | null>(null)

  // session-core 的纯函数负责快照去重与 turn 分组，避免自己重抄一套口径。
  const groups = React.useMemo(() => groupIntoTurns(messages), [messages])
  messagesRef.current = messages
  const turns = React.useMemo(() => toTranscript(groups), [groups])
  const extras = React.useMemo(() => groups.map(extractTurnExtras), [groups])
  /**
   * 最后一条非 system 回合的索引，以及它「末尾工具是否还在飞」。
   * 这是 WebUI 上唯一可靠的「这一轮是否收尾」信号：服务端把顶层 result 过滤掉了，
   * 严格分支要求所有工具结果到齐，会把缺结果（含被中断、结果永不落盘）的轮次正文藏起来。
   * 末尾工具已收齐 → 这一轮就是完成的，正文该显示、过程组该收起。
   */
  const lastNonSystem = React.useMemo(() => {
    let index = -1
    let group: MessageGroup | undefined
    for (let i = groups.length - 1; i >= 0; i--) {
      const candidate = groups[i]
      if (candidate && candidate.type !== 'system') { index = i; group = candidate; break }
    }
    return {
      index,
      pending: group?.type === 'assistant-turn' ? turnHasTrailingPendingTool(group) : false,
    }
  }, [groups])
  /** TaskUpdate 可能出现在 TaskCreate 之后的另一轮：任务名映射按全量消息算一次。 */
  const historicalTaskSubjects = React.useMemo(
    () => buildHistoricalTaskSubjects(messages),
    [messages],
  )
  /** 过程时间线：把每轮拆成「最终正文 + 交织的叙述/工具/思考」。
   * 非末尾回合一律当已收尾（宽松）；末尾回合在「末尾工具还在飞」时才走严格分支，
   * 否则也宽松 —— 这样缺结果/被中断的轮次正文不会被藏起来，手机重连把 running 顶成
   * true 也不影响（settled 不依赖 running）。 */
  const timelines = React.useMemo(
    () => groups.map((group, i) => {
      const isLast = i === lastNonSystem.index
      return buildAssistantTimeline(
        group,
        isLast ? !lastNonSystem.pending : true,
        {
          historicalTaskSubjects,
          // 任务卡上的「进行中」动画跟着轮次是否收尾走：历史轮一律不转圈，
          // 末尾轮以 result / running 为准（与过程组的 live 判据同源）。
          streamEnded: !isLast || !running || isTurnFinished(group),
          stoppedByUser: isLast && runOutcome?.kind === 'stopped',
        },
      )
    }),
    [groups, lastNonSystem, historicalTaskSubjects, running, runOutcome],
  )
  /**
   * 「这一轮还有事在跑」。running 只是传输层的猜测，两个方向都会错：
   * ① 收尾后迟到的 assistant 消息会把它顶回 true；② 3 秒内连发的 run_completed
   * 曾被节流整条吞掉（连 setRunning(false) 都不执行）。
   *
   * 所以对外表现统一叠一道数据判据：最后一轮已收尾，就不再当作运行中。
   * 注意：result 在 WebUI **能看到**（消息馈送走 projectSdkMessageForPocket，
   * 会过滤 result 的 projectSdkMessagesToPocketView 是详情视图那条路 —— 别看混）。
   * isTurnSettled 在 result 之外再兜一层「最终正文已抽出且没有未完成工具」，
   * 覆盖缺结果/被中断的轮次与 running 被迟到消息顶住的场景。
   *
   * 只看「最后一条 user / assistant-turn」：尾部可能挂着 system 消息，它们既不属于
   * 对话也不算收尾；而刚发出新消息时最后一条是 user，必须判定为「在跑」。
   */
  const lastTurnFinished = React.useMemo(() => {
    for (let index = groups.length - 1; index >= 0; index--) {
      const group = groups[index]
      if (!group || group.type === 'system') continue
      return group.type === 'assistant-turn' ? isTurnSettled(group, timelines[index]) : false
    }
    return false
  }, [groups, timelines])
  const sessionActive = running && !lastTurnFinished
  /** 内核决定用哪套推理档位；缺省按 claude 处理（历史会话与旧服务端都是这个默认） */
  const agentRuntime = meta?.agentRuntime ?? 'claude'
  /** 当前内核可切的模型（过滤规则与服务端 update_session_model 同一份） */
  const models = React.useMemo(
    () => normalizeChannelModels(channels, agentRuntime),
    [channels, agentRuntime],
  )
  const strength = React.useMemo(
    () => resolveStrengthState({
      levels: reasoningLevels,
      level: meta?.openAIThinkingLevel,
      effort: meta?.agentEffort,
    }),
    [reasoningLevels, meta?.openAIThinkingLevel, meta?.agentEffort],
  )
  /** 明确停用的预设不进菜单；当前预设优先按带作用域的 reference 匹配 */
  const availablePresets = React.useMemo(() => selectablePresets(presets), [presets])
  const currentPreset = React.useMemo(
    () => resolveCurrentPreset(availablePresets, meta?.presetId, meta?.presetReference),
    [availablePresets, meta?.presetId, meta?.presetReference],
  )
  // 会话绑定的预设被停用/删除时，桌面上会亮角标提醒先选一个；网页端在 chip 上提示
  const presetRequired = presetsLoaded && availablePresets.length > 0 && !currentPreset
  /**
   * 每轮的「模型 / 时间 / 来源 / token」。
   *
   * 一起算进同一个 memo：它们都只依赖分组（模型名再多一个 models 依赖），
   * 拆成几个 memo 只会让每帧多跑几遍 map。时钟字符串在这里定稿
   * （`Date.now()` 只在重算时取一次），避免每条消息各算各的。
   */
  const turnMetas = React.useMemo(() => groups.map((group) => {
    const usage = sumTurnUsage(group)
    const source = resolveTurnSource(group)
    return {
      at: resolveTurnTime(group),
      model: modelLabelFor(resolveTurnModel(group), models),
      clock: formatClock(resolveTurnTime(group)),
      // 「网页」来源不标注（自己从网页发的无需自我说明）；软件/定时/远程/代理才有信息价值
      sourceLabel: source && source !== 'web' ? SOURCE_LABELS[source] : '',
      usageText: usage ? describeUsage(usage) : '',
      usageDetail: usage ? describeUsageDetail(usage) : '',
    }
  }), [groups, models])

  /**
   * 历史对话列表：只取**已加载**的回合。
   *
   * 更早的消息还没取回来（首屏只拉尾部 60 条），列出来也跳不过去；面板底部给
   * 「加载更早的消息」，取回后列表自然变长。想一次列全需要新的分页索引命令，
   * 收益不值这个复杂度。
   */
  const historyEntries = React.useMemo(() => buildHistoryEntries({
    turns,
    headlines: timelines.map((timeline) => timeline.headline),
    summaries: timelines.map((timeline) => timeline.summary),
    clocks: turnMetas.map((meta) => meta.clock),
  }), [turns, timelines, turnMetas])

  /** 完成提醒的正文：最后一轮助手回合的正文/过程摘要 */
  const lastAssistantPreview = React.useMemo(() => {
    for (let index = timelines.length - 1; index >= 0; index--) {
      const timeline = timelines[index]
      if (!timeline) continue
      const line = (timeline.headline || timeline.summary || '').replace(/\s+/g, ' ').trim()
      if (line) return line.slice(0, 80)
    }
    return ''
  }, [timelines])

  /**
   * 已用 tokens：最后一轮顶层 assistant 消息的输入侧用量（口径同桌面 ContextUsageBadge：
   * 未缓存输入 + 缓存读取 + 缓存写入）。子代理消息属于另一份上下文，跳过。
   */
  const contextUsed = React.useMemo(() => latestContextTokens(messages), [messages])

  const contextUsage = React.useMemo<ContextUsageInfo>(
    () => describeContextUsage(contextUsed, contextWindow),
    [contextUsed, contextWindow],
  )

  // 页面在后台时跑完一轮 → 系统通知（拿不到权限就退化成标题打点）
  useCompletionNotify({ active: sessionActive, title: meta?.title ?? '', preview: lastAssistantPreview })

  const permissionMode = meta?.permissionMode ?? 'bypassPermissions'
  /** 图表挂在工具调用行上，靠 toolCallId 关联（消息体里没有图表数据）。 */
  const vizByTool = React.useMemo(() => {
    const map = new Map<string, VisualizationItem>()
    for (const item of visualizations) map.set(item.toolCallId, item)
    return map
  }, [visualizations])

  /**
   * 后台任务只看消息窗口：任务开始/进度/通知都落在系统消息里，刷新后重算即可。
   * `backgroundPending` 只补「窗口里没有 task_started」的计数未知情形。
   */
  const backgroundTasks = React.useMemo(
    () => collectPendingBackgroundTasks(messages, backgroundPending),
    [messages, backgroundPending],
  )
  /**
   * 刷新后的结局兜底。事件给的结局优先；这里只在没有事件结局时补，
   * 并自带「晚于最后一条用户输入」的时效判据，避免把上一轮的中断挂在本轮末尾。
   */
  const historyOutcome = React.useMemo(
    () => (sessionActive ? null : deriveRunOutcomeFromHistory(messages, meta)),
    [messages, meta, sessionActive],
  )
  const effectiveOutcome = runOutcome ?? historyOutcome

  // 标签页名字就是会话名：同时开多个会话页时，光看标签就能认出哪个是哪个。
  // 首屏标题已由服务端按会话名注入，所以会话信息没到之前什么都不做 —— 否则会先冲回兜底文案。
  React.useEffect(() => {
    if (!meta) return
    applyPageTitle(meta.title)
  }, [meta])

  /**
   * 诊断层 / 滚动层 / 附件层各自独立成 hook：
   * 这三块都有「必须在 effect 里跑」的副作用（写 ref、触发 reload、上传竞态），
   * 挤在页面组件里最容易顺手塞进渲染路径，也最难单独验证。
   */
  const { diagEvent, noteReconnect, diagData, staleBundle } = useDiagnostics({
    messages, groups, timelines, lastNonSystem, running, sessionActive, lastTurnFinished, connected,
  })
  const {
    scrollRef,
    atBottomRef,
    prependAnchorRef,
    jumpVisible,
    scrollToLatest,
    handleStreamScroll,
    handleUserEngage,
    capturePrependAnchor,
    markReadingHistory,
  } = useAutoScroll(messages.length, diagEvent)
  const {
    attachments,
    fileInputRef,
    ready: readyAttachments,
    uploading: uploadingAttachments,
    pickFiles,
    removeAttachment,
    clearAttachments,
  } = useAttachments({ clientRef, sessionId, connected, onNotice: setNotice })

  /**
   * 跳到某个回合。
   *
   * 必须先交出滚动控制权：rAF 贴底循环在「内容变高」的那一帧会把人拽回底部，
   * 而图片/图表加载正好发生在滚动之后 —— 不标记就会被立刻拉走（表现为「点了没反应」）。
   */
  const jumpToTurn = React.useCallback((turnIndex: number): void => {
    setHistoryOpen(false)
    const node = scrollRef.current?.querySelector<HTMLElement>(`[data-turn-index="${turnIndex}"]`)
    if (!node) return
    handleUserEngage()
    markReadingHistory()
    node.scrollIntoView({ block: 'start', behavior: 'auto' })
  }, [handleUserEngage, markReadingHistory])

  /** 搜索命中要先换掉消息窗口，回合才存在；等这次提交渲染完再滚。 */
  React.useEffect(() => {
    if (pendingJumpIndex === null) return
    const node = scrollRef.current?.querySelector<HTMLElement>(`[data-turn-index="${pendingJumpIndex}"]`)
    if (!node) return
    handleUserEngage()
    markReadingHistory()
    node.scrollIntoView({ block: 'start', behavior: 'auto' })
    setPendingJumpIndex(null)
  }, [pendingJumpIndex, messages, handleUserEngage, markReadingHistory, scrollRef])

  /**
   * 跳到全量搜索的一条命中。
   *
   * 服务端给的是 JSONL 行号，`get_sdk_messages` 的 `before` 是解析后的消息下标；
   * 两者在有「被丢弃的传输错误消息」时会漂移。所以按窗口取回并校验命中是否真的
   * 落在里面，没落在就沿 `startIndex` 往前回溯（有上限，避免一次点击翻完整本会话）。
   * 运行中不允许跳：流式正文还在变，贴底与滚动会互相抢。
   */
  const jumpToSearchMatch = React.useCallback(async (match: SessionSearchMatch): Promise<void> => {
    if (sessionActive) {
      setNotice('本轮还在运行，等结束后再跳到旧位置')
      return
    }
    const client = clientRef.current
    if (!client) {
      setNotice('连接已断开，请刷新后重试')
      return
    }
    setSearchJumping(true)
    try {
      let current = messagesRef.current
      let before = match.lineIndex + 1
      let nextPageStart = pageStart
      let nextHasOlder = hasOlder
      for (let attempt = 0; attempt < 4; attempt += 1) {
        const page = decodeMessagePage(await client.getSdkMessages(sessionId, {
          before,
          targetMessages: SEARCH_JUMP_WINDOW,
        }))
        if (page.messages.length === 0) break
        current = mergePrepend(page.messages, current)
        nextPageStart = Math.min(nextPageStart, page.startIndex)
        nextHasOlder = nextPageStart > 0 ? (nextHasOlder || page.hasMore) : false
        const turnIndex = findTurnIndexForMessage(groupIntoTurns(current), match.messageId)
        if (turnIndex !== undefined) {
          setMessages(current)
          setPageStart(nextPageStart)
          setHasOlder(nextHasOlder)
          setHistoryOpen(false)
          setPendingJumpIndex(turnIndex)
          return
        }
        if (page.startIndex >= before) break
        before = page.startIndex
      }
      // 没定位到也把取回来的历史留下：用户至少能继续往上翻着找
      setMessages(current)
      setPageStart(nextPageStart)
      setHasOlder(nextHasOlder)
      setNotice('没能在已落盘的记录里定位到这条消息，可能它还在本轮内存中')
    } catch (error) {
      setNotice(`搜索定位失败：${error instanceof Error ? error.message : String(error)}`)
    } finally {
      setSearchJumping(false)
    }
  }, [sessionActive, sessionId, pageStart, hasOlder])

  /** 全量搜索：只读命令，服务端按会话授权校验。失败要让面板显示错误而不是空结果。 */
  const remoteSearch = React.useCallback(async (text: string): Promise<SessionSearchMatch[]> => {
    const client = clientRef.current
    if (!client) throw new Error('连接已断开，请刷新后重试')
    const raw = await client.sendCommand<SessionSearchEnvelope>({
      type: 'search_session_messages',
      sessionId,
      query: text,
      limit: 60,
    })
    return normalizeSessionSearchMatches(raw?.matches)
  }, [sessionId])

  // 让 reload 读到「当前持有条数」，同时避免把它塞进回调依赖 —— 否则每条消息都会重建连接 effect。
  React.useEffect(() => { heldRef.current = messages.length }, [messages])


  /**
   * iPhone Safari 键盘适配：软键盘弹出时不缩 layout viewport，
   * 100%/100dvh 都会把输入条顶到键盘下面。把 visualViewport 的实际高度
   * 注入 --app-h，.app 用它做高度 —— 输入条始终贴在键盘上方。
   */
  React.useEffect(() => {
    const viewport = window.visualViewport
    if (!viewport) return
    const root = document.documentElement
    const apply = (): void => {
      /**
       * 双指缩放（scale > 1）期间不做补偿。
       *
       * iOS 在缩放/平移过程中持续更新 visualViewport，而 fixed 元素仍按布局视口
       * 绘制。这时继续把 .app 钉到 offsetTop/height，浏览器缩放后的画面和 JS
       * 校正后的画面会每帧互相覆盖 —— 打开菜单后双指放大时，表现为两屏来回闪。
       * 缩放期间回到「普通网页」行为，交给浏览器缩放/平移整页；缩回 1 后
       * 下一个 resize/scroll 事件会重新接管键盘补偿。
       */
      if (viewport.scale > 1.01) {
        root.style.removeProperty('--app-h')
        root.style.setProperty('--app-top', '0px')
        return
      }
      root.style.setProperty('--app-h', `${viewport.height}px`)
      /**
       * iOS 聚焦输入框时会**整体上滚布局视口**（visualViewport 出现 offsetTop），
       * 只缩高度不跟这个偏移，固定定位的 .app 就会跑到屏幕上方去：
       * 表现为正文消失、下面留一大片空白（点输入框后的那张截图）。
       *
       * 所以 .app 用 `top: var(--app-top)` 钉在**可见区域**的顶部，并把布局滚动归零；
       * 页面本身不允许滚动（body overflow:hidden），滚动只发生在 .stream 里。
       */
      root.style.setProperty('--app-top', `${Math.max(0, Math.round(viewport.offsetTop))}px`)
      if (window.scrollY !== 0) window.scrollTo(0, 0)
    }
    apply()
    viewport.addEventListener('resize', apply)
    viewport.addEventListener('scroll', apply)
    window.addEventListener('orientationchange', apply)
    return () => {
      viewport.removeEventListener('resize', apply)
      viewport.removeEventListener('scroll', apply)
      window.removeEventListener('orientationchange', apply)
      root.style.removeProperty('--app-h')
      root.style.removeProperty('--app-top')
    }
  }, [])

  /**
   * 重拉权威尾部。`merge` 模式按身份合并而不是整体替换 ——
   * **流式中的 partial 不落盘**，无脑替换会把正在输出的正文冲掉：回前台（wake，
   * 桌面切窗口）与 requiresSnapshot 重连（游标落窗外、漏帧不补发）都发生在流式中，
   * 替换后漏掉的 final 不补发 → 正文消失且不恢复（「除非再发消息或刷新」）。
   * 合并后 JSONL 的 final 按身份覆盖同身份 partial（upsert 有方向保护），
   * 落盘的 final 能恢复，流式中的消息保留在原位。
   * 首屏与 run_completed 后的重拉用 replace：那两种场景没有在飞的消息，
   * 且 replace 能正确反映服务端删除/截断（rewind）。
   */
  const reload = React.useCallback(async (client: WsClient, mode: 'replace' | 'merge' = 'replace'): Promise<SDKMessage[]> => {
    try {
      // 只取尾部：多留 TAIL_HEADROOM 条余量，跑完一轮新消息落进来时窗口不会立刻往前滑动。
      const page = decodeMessagePage(
        await client.getSdkMessages(sessionId, { targetMessages: tailPageSize(heldRef.current) }),
      )
      diagEvent(`reload(${mode}) → ${page.messages.length} 条`)
      if (mode === 'merge') {
        // merge 可能把「页面窗口比当前持有的更早的那几十条」补进头部（尾窗滑动）——
        // 先记距底距离，DOM 更新后由 prependAnchor 的 layoutEffect 还原，
        // 否则视口整体被顶到上面（「刷新后先在底部、等一会儿又跳到上面」的根因）。
        const node = scrollRef.current
        capturePrependAnchor()
        setMessages((previous) => mergeReloadPage(previous, page.messages))
      } else {
        setMessages(page.messages)
      }
      setPageStart(page.startIndex)
      setHasOlder(page.hasMore)
      setNotice((previous) => previous.startsWith('读取会话失败') ? '' : previous)
      return page.messages
    } catch (error) {
      setNotice(`读取会话失败：${error instanceof Error ? error.message : String(error)}`)
      return []
    }
  }, [sessionId])

  /**
   * 按权威消息列表校准 running：最后一轮若已收尾（末尾工具不再在飞），就不该再当作运行中。
   * 手机切后台超过事件日志窗口（10 分钟）后回来，重连走 requiresSnapshot 快照路径，
   * 漏掉的 result / run_completed 不会补发 —— 不在这里兜底，过程组就永远不收起。
   */
  const calibrateRunning = React.useCallback((messages: SDKMessage[]): void => {
    if (!runningRef.current) return
    const grouped = groupIntoTurns(messages)
    let last: MessageGroup | undefined
    for (let i = grouped.length - 1; i >= 0; i--) {
      const candidate = grouped[i]
      if (candidate && candidate.type !== 'system') { last = candidate; break }
    }
    if (last?.type === 'assistant-turn' && !turnHasTrailingPendingTool(last)) setRunning(false)
  }, [])

  /**
   * 上下文窗口水合：`context_window` 事件只在「恰好在线」时到达，
   * 重连 / 刷新 / 事件日志过期后从这里补（快照里带 contextWindow）。
   */
  const loadRuntimeContext = React.useCallback(async (client: WsClient): Promise<void> => {
    try {
      const raw = await client.sendCommand<Array<{ sessionId?: string; contextWindow?: unknown }>>({
        type: 'get_agent_runtime_contexts',
        sessionIds: [sessionId],
      })
      const entry = Array.isArray(raw) ? raw.find((item) => item?.sessionId === sessionId) : undefined
      const value = entry?.contextWindow
      if (typeof value === 'number' && Number.isFinite(value) && value > 0) setContextWindow(value)
    } catch {
      // 拿不到就不显示占用条；context_window 事件到达时会补
    }
  }, [sessionId])

  /**
   * 技能列表：`@` 提及候选的数据源。只取 slug/name/description 三个展示字段。
   * 列表拿不到就不弹 @ 建议 —— 提及是增强能力，不是消息发送的前置条件。
   */
  const loadSkills = React.useCallback(async (client: WsClient): Promise<void> => {
    try {
      const raw = await client.sendCommand<{ skills?: Array<{ slug?: unknown; name?: unknown; description?: unknown }> }>({
        type: 'list_skills',
        sessionId,
      })
      const list = Array.isArray(raw?.skills) ? raw.skills : []
      setSkillOptions(list.flatMap((item) => {
        const slug = typeof item?.slug === 'string' && item.slug ? item.slug : ''
        if (!slug) return []
        const name = typeof item?.name === 'string' && item.name ? item.name : slug
        const description = typeof item?.description === 'string' ? item.description : ''
        return [{ slug, name, description }]
      }))
      setSkillStatus(list.length === 0 ? 'empty' : 'ok')
      setSkillWarning(null)
      diagEvent(`list_skills ok：${list.length} 个技能`)
    } catch (error) {
      setSkillOptions([])
      const message = error instanceof Error ? error.message : String(error)
      // 旧主进程不认识 list_skills，默认分支回「未知指令」——直接告诉用户要重启
      setSkillStatus(message.includes('未知指令') ? 'unsupported' : 'error')
      setSkillWarning(message.slice(0, 80))
      diagEvent(`list_skills 失败：${message}`)
    }
  }, [sessionId, diagEvent])

  /**
   * `@` 文件引用候选：从会话授权根列相对路径。
   * query 变化时按输入重新拉（主进程做递归匹配），让深层文件也能被引用到；
   * 每次结果合并进已有集合，树的上层目录不会因为只返回匹配项而消失。
   */
  const loadFiles = React.useCallback(async (client: WsClient, query = ''): Promise<void> => {
    try {
      type RawItem = { path?: unknown; name?: unknown; type?: unknown }
      const raw = await client.sendCommand<{
        session?: RawItem[]; workspace?: RawItem[]; files?: RawItem[]
      }>({ type: 'list_workspace_files', sessionId, query })
      // 新主进程返回 {session, workspace} 两组；旧版本返回扁平 {files}，降级成会话文件
      const groups: Array<[RawItem[] | undefined, FileOption['source']]> = raw?.session || raw?.workspace
        ? [[raw.session, 'session'], [raw.workspace, 'workspace']]
        : [[raw?.files, 'session']]
      const parsed: FileOption[] = groups.flatMap(([list, source]) =>
        (Array.isArray(list) ? list : []).flatMap((item) => {
          const path = typeof item?.path === 'string' && item.path ? item.path : ''
          if (!path) return []
          const name = typeof item?.name === 'string' && item.name ? item.name : path.split('/').pop() ?? path
          const type: 'dir' | 'file' = item?.type === 'dir' ? 'dir' : 'file'
          return [{ path, name, type, source }]
        }),
      )
      setFileOptions((previous) => {
        if (!query) return parsed // 空查询是全量首屏，直接替换
        const byPath = new Map(previous.map((item) => [item.path, item]))
        for (const item of parsed) byPath.set(item.path, item)
        return [...byPath.values()]
      })
    } catch {
      // 旧主进程不认识 list_workspace_files：@ 提及不可用，不影响发消息
      if (!query) setFileOptions([])
    }
  }, [sessionId])

  /** Goal 状态完全由主进程持有，这里只做快照拉取；实时变化靠 goal_state 事件推送。 */
  const loadGoal = React.useCallback(async (client: WsClient): Promise<void> => {
    try {
      const result = await client.getGoal(sessionId) as GoalEnvelope | null
      setGoal(normalizeGoal(result?.goal))
    } catch {
      // 读取失败不打断会话读取：事件流与下一轮 run 结束都会重新拉取
    }
  }, [sessionId])

  /** 归档 Goal（清除/完成的历史）。只在状态条展开时才有内容，但清除后必须刷新，否则列表是旧的。 */
  const loadGoalHistory = React.useCallback(async (client: WsClient): Promise<void> => {
    try {
      const result = await client.getGoalHistory(sessionId) as GoalHistoryEnvelope | null
      setGoalHistory(normalizeGoalHistory(result?.goals))
    } catch {
      // 历史读不到不影响当前 Goal 的展示
    }
  }, [sessionId])

  /**
   * 会话内可视化记录：图表数据不在消息流里（WS 投影把 tool_use 入参压成 `{ file_path }`），
   * 只能按会话整体列出，再靠 `toolCallId` 挂回对应回合的工具调用行。
   */
  const loadVisualizations = React.useCallback(async (client: WsClient): Promise<void> => {
    try {
      const result = await client.listVisualizations(sessionId) as VisualizationEnvelope | null
      setVisualizations(normalizeVisualizations(result?.records))
    } catch {
      // 旧服务端不认这条命令时也走这里：图表缺失不该拦住消息阅读
    }
  }, [sessionId])

  /** 委派子任务快照：刷新 / 重连后恢复「在跑 / 在等你」提示。 */
  const loadDelegations = React.useCallback(async (client: WsClient): Promise<void> => {
    try {
      const result = await client.sendCommand<DelegationSnapshotEnvelope>({
        type: 'get_session_delegations',
        sessionId,
      })
      setDelegations(normalizeDelegations(result))
    } catch {
      // 旧服务端不认：按没有子任务处理，但不要清掉事件已经建立的提示
    }
  }, [sessionId])

  /**
   * 四类交互（权限 / 问答 / 计划审批 / Goal 提议）的队列与提交。
   * 事件入口是 applyEvent：onAgentEvent 里一条命令就能分辨「这条事件归交互层」。
   */
  const interactions = useInteractions({
    clientRef,
    sessionId,
    acting,
    setActing,
    onNotice: setNotice,
    refreshGoal: React.useCallback(async () => {
      const client = clientRef.current
      if (client) await loadGoal(client)
    }, [loadGoal]),
  })
  const hasPending = interactions.pendingCount > 0
  /** 提议不阻塞运行，所以只影响「是否渲染卡片区」，不锁输入框。 */
  const hasCards = interactions.cardCount > 0

  React.useEffect(() => {
    if (atBottomRef.current) scrollToLatest(false)
  }, [groups, hasPending, sessionActive, scrollToLatest])

  /** 向上续页：只取更早的一页并前置拼接，不重拉尾部。 */  const loadOlder = React.useCallback(async (): Promise<void> => {
    const client = clientRef.current
    if (!client || loadingOlder || !hasOlder || pageStart <= 0) return
    const node = scrollRef.current
    // 记「距底部距离」而不是 scrollTop：插入内容后容器高度变了，scrollTop 会漂。
    capturePrependAnchor()
    setLoadingOlder(true)
    try {
      const page = decodeMessagePage(
        await client.getSdkMessages(sessionId, { before: pageStart, targetMessages: OLDER_PAGE }),
      )
      if (page.messages.length === 0) {
        // 服务端说到头了：收起入口，别让用户对着一个点了没反应的按钮
        setHasOlder(false)
        prependAnchorRef.current = null
        return
      }
      setMessages((previous) => mergePrepend(page.messages, previous))
      setPageStart(page.startIndex)
      setHasOlder(page.hasMore)
      // 正在翻历史，不该被新内容拽回底部
      markReadingHistory()
    } catch (error) {
      prependAnchorRef.current = null
      setNotice(`加载更早消息失败：${error instanceof Error ? error.message : String(error)}`)
    } finally {
      setLoadingOlder(false)
    }
  }, [sessionId, loadingOlder, hasOlder, pageStart])

  /** 图片不在消息体里：send_local_image 只带 path，经 WS 读取会话工作区内的图片文件。 */
  const loadImage = React.useCallback(async (path: string): Promise<ImageLoadResult> => {
    const client = clientRef.current
    // 未连接 ≠ 失败：交给 TurnImage 保持「加载中」，连接就绪后它会被重试。
    if (!client || !connected) return { status: 'pending' }
    try {
      const result = await client.readFileAsDataUrl(path, { sessionId }) as DataUrlEnvelope | null
      const dataUrl = result?.dataUrl
      if (typeof dataUrl === 'string' && dataUrl.startsWith('data:image/')) return { status: 'ok', dataUrl }
      return { status: 'error', message: '服务端返回的不是图片数据，可能该路径不在当前会话授权范围内' }
    } catch (error) {
      // 这里必须原样带出服务端文案（文件不存在 / 越权 / 超限），否则无从排查。
      return { status: 'error', message: error instanceof Error ? error.message : String(error) }
    }
  }, [sessionId, connected])

  /**
   * 任意文件读成 data URL：PDF 内嵌预览、二进制下载共用。
   * 与图片同一条授权链（read_file_as_data_url），服务端非图片返回 application/octet-stream。
   */
  const loadBinary = React.useCallback(async (path: string): Promise<BinaryLoadResult> => {
    const client = clientRef.current
    if (!client || !connected) return { status: 'error', message: '连接尚未就绪，请稍后再试' }
    try {
      const result = await client.readFileAsDataUrl(path, { sessionId }) as DataUrlEnvelope | null
      const dataUrl = result?.dataUrl
      if (typeof dataUrl !== 'string' || !dataUrl.startsWith('data:')) {
        return { status: 'error', message: '服务端没有返回文件内容，可能超出大小限制或不在会话授权范围' }
      }
      // base64 膨胀约 4/3，反推原始大小仅用于展示
      const size = Math.floor((dataUrl.length - dataUrl.indexOf(',') - 1) * 3 / 4)
      return { status: 'ok', dataUrl, size }
    } catch (error) {
      return { status: 'error', message: error instanceof Error ? error.message : String(error) }
    }
  }, [sessionId, connected])

  /**
   * 文本文件预览：与图片同一条授权链，只是返回正文。
   *
   * 走 `sendCommand` 而不是 ws-client 的具名方法：这条命令是 WebUI 专用，
   * 没必要为了它改移动端共享客户端的公开面。
   */
  const loadTextPreview = React.useCallback(async (path: string): Promise<TextPreviewResult> => {
    const client = clientRef.current
    if (!client || !connected) return { status: 'error', message: '连接尚未就绪，请稍后再试' }
    try {
      const raw = await client.sendCommand<{ text?: unknown; size?: unknown; truncated?: unknown; binary?: unknown }>({
        type: 'read_text_file',
        filePath: path,
        access: { sessionId },
      })
      const size = typeof raw?.size === 'number' ? raw.size : 0
      if (raw?.binary === true) return { status: 'binary', size }
      if (typeof raw?.text !== 'string') return { status: 'error', message: '服务端没有返回文件内容' }
      return { status: 'ok', text: raw.text, truncated: raw.truncated === true, size: size || raw.text.length }
    } catch (error) {
      return { status: 'error', message: error instanceof Error ? error.message : String(error) }
    }
  }, [sessionId, connected])

  /**
   * 一次性预览页的加载器：按文件类型走已有的三条授权链，产出自包含内容。
   * 图片/PDF/二进制拿 data URL，文本拿正文；Markdown 在父页渲染成 HTML 一起带过去。
   */
  const loadStandalone = React.useCallback(async (path: string): Promise<StandalonePreviewContent> => {
    const name = fileNameOf(path)
    if (isImageAttachment(name)) {
      const result = await loadImage(path)
      if (result.status === 'ok') return { kind: 'image', dataUrl: result.dataUrl }
      if (result.status === 'error') return { kind: 'error', message: result.message }
      return { kind: 'error', message: '连接尚未就绪，请稍后再打开' }
    }
    if (/\.pdf$/i.test(name)) {
      const result = await loadBinary(path)
      return result.status === 'ok'
        ? { kind: 'pdf', dataUrl: result.dataUrl }
        : { kind: 'error', message: result.message }
    }
    const text = await loadTextPreview(path)
    if (text.status === 'ok') {
      // .md 给「预览 / 原文」双视图；渲染成本在父页一次付清，预览标签页只负责展示
      if (/\.(md|markdown)$/i.test(name)) {
        const html = renderMarkdownToStaticHtml(text.text)
        return {
          kind: 'markdown',
          text: text.text,
          html,
          truncated: text.truncated,
          // 没有公式就不带 20KB+ 的 KaTeX 样式
          katexCss: html.includes('class="katex') ? katexCss : undefined,
        }
      }
      return { kind: 'text', text: text.text, truncated: text.truncated }
    }
    if (text.status === 'binary') {
      const result = await loadBinary(path)
      return result.status === 'ok'
        ? { kind: 'binary', dataUrl: result.dataUrl }
        : { kind: 'error', message: result.message }
    }
    return { kind: 'error', message: text.message }
  }, [loadImage, loadBinary, loadTextPreview])

  /** 点文件路径 / @file 引用：在新标签页打开一次性预览，可同时开多个对照看 */
  const openFile = React.useCallback((path: string): void => {
    if (!openStandalonePreview(path, loadStandalone)) {
      setNotice('浏览器拦截了新标签页，请允许本页面的弹出窗口后重试')
    }
  }, [loadStandalone])

  /**
   * 会话信息（标题 / 模型 / 工作区 / 权限模式）。
   *
   * 标题不是一次性的：新会话的标题由模型在首轮之后自动生成，页面在这一轮之前打开时
   * 拿到的是空标题，所以轮次结束后要重新取一次。
   */
  const loadSessionMeta = React.useCallback(async (client: WsClient): Promise<SessionMetaPayload | null> => {
    try {
      const detail = await client.sessionDetail(sessionId) as SessionDetailEnvelope | null
      const raw = detail?.meta ?? detail?.data?.meta ?? null
      if (!raw) return null
      setMeta({
        // 空标题保持空串，由标题栏与标签页各自决定兜底文案（不要拿 sessionId 当名字）
        title: typeof raw.title === 'string' ? raw.title.trim() : '',
        channelId: typeof raw.channelId === 'string' ? raw.channelId : '',
        modelId: typeof raw.modelId === 'string' ? raw.modelId : undefined,
        workspaceId: typeof raw.workspaceId === 'string' ? raw.workspaceId : undefined,
        permissionMode: normalizePermissionMode(raw.permissionMode),
        agentRuntime: typeof raw.agentRuntime === 'string' ? raw.agentRuntime : undefined,
        agentEffort: typeof raw.agentEffort === 'string' ? raw.agentEffort : null,
        openAIThinkingLevel: typeof raw.openAIThinkingLevel === 'string' ? raw.openAIThinkingLevel : null,
        presetId: typeof raw.presetId === 'string' ? raw.presetId : undefined,
        presetReference: isPresetReference(raw.presetReference) ? raw.presetReference : null,
        workspaceSlug: typeof raw.workspaceSlug === 'string' ? raw.workspaceSlug : undefined,
        revision: typeof raw.revision === 'number' ? raw.revision : undefined,
        // 落盘的中断信息：刷新后据此恢复结局行（事件流里拿不到上一轮结局）
        lastInterruptReason: typeof raw.lastInterruptReason === 'string' ? raw.lastInterruptReason : undefined,
        lastInterruptLabel: typeof raw.lastInterruptLabel === 'string' ? raw.lastInterruptLabel : undefined,
        lastInterruptAt: typeof raw.lastInterruptAt === 'number' ? raw.lastInterruptAt : undefined,
        stoppedByUser: raw.stoppedByUser === true,
      })
      // 服务端知道「这一轮到底还在不在跑」，顺手对齐一次本地推断
      setServerActive(raw.active === true)
      // 返回原始 meta：连接建立这一轮里 state 还没刷新，预设加载要直接用这份 workspaceSlug
      return raw
    } catch (error) {
      setNotice(`读取会话信息失败：${error instanceof Error ? error.message : String(error)}`)
      return null
    }
  }, [sessionId])

  /**
   * 模型表：会话内可切换的模型集合，连接建立时取一次。
   *
   * 失败只降级、不打断：旧服务端没有这条命令时，菜单退化成「只有强度」，
   * 聊天本身不受影响 —— 模型标签则回退成「美化后的 id」。
   */
  const loadChannels = React.useCallback(async (client: WsClient): Promise<void> => {
    try {
      const data = await client.listChannels()
      setChannels(Array.isArray(data) ? data as RemoteChannel[] : null)
    } catch {
      setChannels(null)
    }
  }, [])

  /**
   * 预设列表 + 工作区默认预设。
   *
   * 作用域必须是会话所属工作区的 slug：不传 slug 时服务端只会返回内置默认配置，
   * 用户自己建的岗位会凭空消失。连接建立这一轮 state 还没刷新，所以允许显式传 slug。
   */
  /**
   * 会话所属工作区 slug 的实时镜像。
   *
   * 不能直接把它放进 `loadPresets` 的依赖：连接 effect 依赖 `loadPresets`，而 meta 是在
   * 连接建立之后才由 `loadSessionMeta` 写入的 —— 依赖它等于「一拿到会话信息就重建连接」。
   * 后果不只是浪费：旧的那条初始化链会在已经 disconnect 的 client 上继续发命令，
   * `reload` 必然被拒，于是首屏弹一条「读取会话失败：连接未就绪」的假报错。
   */
  const workspaceSlugRef = React.useRef<string | undefined>(undefined)
  workspaceSlugRef.current = meta?.workspaceSlug
  const loadPresets = React.useCallback(async (client: WsClient, explicitSlug?: string): Promise<void> => {
    const slug = explicitSlug ?? workspaceSlugRef.current
    try {
      const [raw, fallback] = await Promise.all([
        client.listPresets(slug),
        client.getDefaultPreset(slug),
      ])
      setPresets(normalizePresets(raw))
      setDefaultPresetId(typeof fallback === 'string' ? fallback : '')
      setPresetsLoaded(true)
    } catch {
      // 旧服务端或权限不足：只影响预设菜单，聊天照常
      setPresetsLoaded(true)
    }
  }, [])

  /**
   * Pi 内核的模型自带推理档位（off/minimal/low/…/max），Claude 内核只有 effort 四档。
   * 档位由服务端解析（renderer 没有 pi-ai 目录，本地推不出来），所以这里问一次并缓存。
   */
  React.useEffect(() => {
    const client = clientRef.current
    setReasoningLevels(null)
    if (!client || agentRuntime !== 'pi' || !meta?.modelId) return
    const channel = channels?.find((item) => item.id === meta.channelId)
    if (!channel?.provider) return
    let cancelled = false
    void client.getPiReasoningCapability(channel.provider, meta.modelId, channel.baseUrl)
      .then((capability) => {
        if (cancelled) return
        const levels = (capability as PiReasoningCapabilityEnvelope | null)?.levels
        setReasoningLevels(Array.isArray(levels)
          ? levels.filter((level): level is string => typeof level === 'string' && level.length > 0)
          : null)
      })
      .catch(() => {
        // 拿不到能力表就按 Claude 的 effort 档兜底，不给用户一排选不中的选项
        if (!cancelled) setReasoningLevels(null)
      })
    return () => { cancelled = true }
  }, [agentRuntime, meta?.modelId, meta?.channelId, channels])

  React.useEffect(() => {
    if (!sessionId || !token) {
      setConnection('缺少会话 ID 或访问凭据')
      return
    }
    setHistoryLoaded(false)
    // WsClient.sendCommand 在 onopen 之前会直接 reject「连接未就绪」，
    // 因此先备好「首次连接就绪」的 Promise，等 open 后再取会话详情与历史消息。
    let markReady!: () => void
    const ready = new Promise<void>((resolve) => { markReady = resolve })
    let cancelled = false
    const client = new WsClient({
      url: defaultWsUrl(),
      token,
      onStatusChange: (state, info) => {
        if (state === 'open') {
          setConnection('已连接')
          setConnected(true)
          // 重连计数：桌面浏览器冻结后台标签页、回来时 WS 重连 + 事件重放，
          // 重放路径与实时流的帧标记不同（isReplay），是「电脑复现 iPhone 不复现」
          // 的重点嫌疑。诊断浮层会显示它。
          noteReconnect()
          markReady()
        }
        else if (state === 'unauthorized') { setConnection('凭据无效'); setConnected(false) }
        else { setConnection(state === 'connecting' ? '连接中…' : `连接断开${info ? ` · ${info}` : ''}`); setConnected(false) }
      },
      // 重连恢复后的状态收敛。服务端事件日志只保留最近 10 分钟（maxAgeMs），
      // 手机锁屏/切后台超过这个时长再回来，游标必然落在窗口外 → requiresSnapshot=true、
      // complete=false，服务端一个事件都不补发。漏掉的窗口里可能有 result 与 run_completed，
      // 不重拉权威尾部就会永远停在「running=true、最后一轮没 result」——
      // 表现为最后一轮过程组不收起、正文被收回过程组，直到发新消息才复位。
      // 所以两种情况都要收敛：requiresSnapshot 时必然漏事件，重拉 + 校准；
      // complete 时重放会自愈（漏掉的 run_completed 一定在游标之后、一定被补发），
      // 但重放里的 assistant 消息可能先把 running 顶回 true，顺手校准一次不亏。
      onAgentResumeStatus: (status) => {
        diagEvent(`resume requiresSnapshot=${status.requiresSnapshot} complete=${status.complete} replayed=${status.replayed}`)
        if (status.requiresSnapshot) {
          // merge：漏帧不补发，但 final 落盘了就能从页面恢复；在飞的 partial 也保留
          void reload(client, 'merge').then(calibrateRunning)
          return
        }
        calibrateRunning(messagesRef.current)
      },
      onAgentEvent: (event) => {
        if (event.sessionId !== sessionId) return
        const payload = event.payload as AgentEventPayload | null
        if (payload?.kind === 'sdk_message' && payload.message) {
          setMessages((previous) => upsertMessage(previous, payload.message as SDKMessage))
          // 只有真正的内容消息才算「又在跑了」。result / system / prompt_suggestion 这类
          // 收尾消息会在 run_completed 之后陆续到达（prompt_suggestion 甚至是跑完才生成），
          // 无条件 setRunning(true) 会把状态永久顶回运行中 —— 表现就是执行过程组再也不收起，
          // 正文被压在展开的过程组下面，看起来像「没有正式回复」。
          const messageType = (payload.message as { type?: string }).type
          if (messageType === 'assistant' || messageType === 'user') setRunning(true)
          return
        }
        if (payload?.kind !== 'profer_event') return
        const proferEvent = payload.event
        const type = proferEvent?.type
        const request = proferEvent?.request
        // Goal 每次状态变化主进程都会推一条完整状态，这里整体替换 ——
        // 状态机在电脑端，网页不做任何本地推断（重启/重连后也不会漂移）。
        if (type === 'context_window') {
          const value = proferEvent?.contextWindow
          if (typeof value === 'number' && Number.isFinite(value) && value > 0) setContextWindow(value)
          return
        }
        if (type === 'goal_state') {
          const next = normalizeGoal(proferEvent?.goal)
          setGoal(next)
          // 清除/完成会把当前 Goal 归档：顺手刷一次历史，否则展开区里的归档列表还是旧的
          if (!next) void loadGoalHistory(client)
          return
        }
        // 交互类事件交给交互层（四条队列的入队/出队口径集中在那里）。
        if (proferEvent && interactions.applyEvent(proferEvent)) return
        if (type === 'permission_mode_changed') {
          // 批准计划时主进程会把会话切出 plan 模式。不同步 meta 的话，
          // 下一次发送仍带着 permissionMode:'plan' 这个 override，会立刻又进回计划模式。
          const mode = normalizePermissionMode(proferEvent?.mode)
          setMeta((previous) => (previous ? { ...previous, permissionMode: mode } : previous))
        } else if (type === 'visualization_updated' && proferEvent?.record) {
          // 主进程每次写入记录都会推一条完整 record：按 toolCallId 覆盖，不重新拉列表。
          const item = normalizeVisualizations([proferEvent.record])[0]
          if (item) {
            setVisualizations((previous) => previous.some((entry) => entry.id === item.id)
              ? previous.map((entry) => (entry.id === item.id ? item : entry))
              : [...previous, item])
          }
        } else if (type === 'delegation_session_updated' && proferEvent?.session) {
          // 子会话状态变化：终态移出提示，运行中更新标题/角色/目标。
          const session = proferEvent.session as unknown as AgentSessionMeta
          setDelegations((previous) => applyDelegationSessionUpdate(previous, session, sessionId))
        } else if (type === 'delegation_blocked') {
          // 阻塞事件是「子任务在等你」的唯一强信号：快照没刷新也能先把提示亮起来。
          const update = normalizeBlockedEvent(proferEvent)
          if (update) setDelegations((previous) => applyDelegationBlocked(previous, update))
        } else if (type === 'delegation_blocked_resolved') {
          setDelegations((previous) => resolveDelegationBlocked(previous, {
            delegationId: typeof proferEvent?.delegationId === 'string' ? proferEvent.delegationId : undefined,
            blockedEventId: typeof proferEvent?.blockedEventId === 'string' ? proferEvent.blockedEventId : undefined,
            requestId: typeof proferEvent?.requestId === 'string' ? proferEvent.requestId : undefined,
          }))
        }
        if (type === 'retry') {
          const transition = normalizeRetryEvent(proferEvent ?? {})
          if (transition?.action === 'set') setRetryInfo(transition.info)
          else if (transition?.action === 'clear') setRetryInfo(null)
        }
        if (type === 'session_persistence') {
          // 只有落盘失败值得打断阅读；saved / pending 都当作恢复（收起告警）。
          const transition = normalizePersistenceEvent(proferEvent ?? {})
          if (transition?.action === 'warn') setPersistenceWarning(transition.warning)
          else if (transition?.action === 'clear') setPersistenceWarning(null)
        }
        // run_completed 与 run_idle 会在同一轮先后到达，3 秒内只收敛一次「重拉」副作用。
        // 但 setRunning(false) 必须每次都执行：以前它也被节流吞掉，连着两轮时
        // 第二轮的 completion 不生效，过程组就一直保持展开。
        if (type === 'run_completed' || type === 'run_idle') {
          if (type === 'run_completed' && proferEvent) {
            // 结局与后台任务只用真终态更新；run_idle 不带这些字段，别把结局冲掉。
            setRunOutcome(normalizeRunOutcomeEvent(proferEvent))
            setBackgroundPending(proferEvent.backgroundTasksPending === true)
            setRetryInfo(null)
          }
          setRunning(false)
          setServerActive(false)
          setCompacting(false)
          // compacting 是 run 中瞬态（WS 广播有、JSONL 不落盘），合并重拉不会删它 ——
          // run 结束必须清场，否则「正在压缩上下文...」永久残留（两次压缩留两行）。
          // 变矮前记距底距离走 prependAnchor 还原，否则视口被顶上去。
          capturePrependAnchor()
          setMessages((previous) => previous.filter((message) =>
            !(message.type === 'system' && (message as { subtype?: unknown }).subtype === 'compacting')))
          const now = Date.now()
          if (now - lastCompletedRef.current > 3000) {
            lastCompletedRef.current = now
            void reload(client, 'merge')
            void loadGoal(client)
            void loadVisualizations(client)
            // 每轮结束刷新子任务快照：委派记录是轮末才稳定的
            void loadDelegations(client)
            // 首轮之后标题才会被自动生成，结束后重新取一次，标签页才不会一直停在兜底文案
            void loadSessionMeta(client)
          }
        }
      },
    })
    clientRef.current = client
    client.connect()
    /**
     * 手机上切到后台/锁屏后，系统会掐掉 WS，而重连定时器在后台被冻结 ——
     * 回到页面时可能一直停在「连接断开」。恢复前台或网络恢复时立刻补一次重连
     * （reconnectNow 对已连上的连接是 no-op，不会打断会话）。
     *
     * 重连之外还要重拉一次权威消息尾部：后台期间跑完的一轮，其 result / run_completed
     * 可能落在事件日志窗口（仅 10 分钟）之外，重连补发不回来 —— 不重拉的话，
     * 过程组永远不收起、正文被收回过程组，直到发新消息才复位。
     */
    const wake = (): void => {
      if (document.visibilityState !== 'visible') return
      client.reconnectNow()
      diagEvent(`wake visibility=${document.visibilityState}`)
      // merge：切窗口可能切在流式中，替换会把在飞的正文冲掉且不恢复
      void reload(client, 'merge').then(calibrateRunning)
      // 后台期间错过的轮末刷新在这里补一次：子任务快照不重放事件
      void loadDelegations(client)
    }
    document.addEventListener('visibilitychange', wake)
    window.addEventListener('online', wake)
    // 5 秒兜底轮询，覆盖事件丢失与重连；与桌面端 pending-interaction 收敛节奏一致。
    const poll = window.setInterval(() => { void interactions.loadInteractions(client) }, 5000)
    void (async () => {
      const opened = await Promise.race([
        ready.then(() => true),
        new Promise<boolean>((resolve) => setTimeout(() => resolve(false), 10000)),
      ])
      if (cancelled) return
      if (!opened) {
        setNotice('连接超时，请确认 Profer 的 Web 会话已启用后刷新页面重试')
        return
      }
      // 每一步 await 之后都要重新看一眼 cancelled：依赖变化（sessionId / token）会先 cleanup
      // 再重建连接，旧链若继续跑就会在已断开的 client 上发命令，把「连接未就绪」当业务错误弹给用户。
      // 会话信息失败不该拦住消息读取：loadSessionMeta 自己吞掉异常，只留一条 notice
      const rawMeta = await loadSessionMeta(client)
      if (cancelled) return
      await loadChannels(client)
      if (cancelled) return
      await loadPresets(client, typeof rawMeta?.workspaceSlug === 'string' ? rawMeta.workspaceSlug : undefined)
      if (cancelled) return
      await reload(client)
      if (cancelled) return
      setHistoryLoaded(true)
      await interactions.loadInteractions(client)
          void loadRuntimeContext(client)
          void loadSkills(client)
          void loadFiles(client)
      if (cancelled) return
      await loadGoal(client)
      if (cancelled) return
      await loadGoalHistory(client)
      if (cancelled) return
      await loadVisualizations(client)
      // 锦上添花：旧服务端不认识时会在内部吞错，不阻塞首屏
      void loadDelegations(client)
    })()
    return () => {
      cancelled = true
      document.removeEventListener('visibilitychange', wake)
      window.removeEventListener('online', wake)
      window.clearInterval(poll)
      client.disconnect()
      clientRef.current = null
    }
  }, [sessionId, token, reload, calibrateRunning, interactions.loadInteractions, loadRuntimeContext, loadSkills, loadGoal, loadGoalHistory, loadVisualizations, loadDelegations, loadSessionMeta, loadPresets])

  /** Goal 控制：全部转发给主进程状态机，本地只按返回值刷新展示。 */
  const runGoalAction = React.useCallback(async (action: GoalAction): Promise<boolean> => {
    const client = clientRef.current
    if (!client || acting) return false
    setActing(true)
    try {
      if (action === 'pause') await client.pauseGoal(sessionId)
      else if (action === 'resume') await client.resumeGoal(sessionId)
      else if (action === 'stop') await client.stopGoal(sessionId)
      else {
        await client.clearGoal(sessionId)
        setGoal(null)
        // 清除 = 归档，立刻刷历史，展开区不用等下一次事件
        await loadGoalHistory(client)
        return true
      }
      await loadGoal(client)
      return true
    } catch (error) {
      setNotice(`Goal 操作失败：${error instanceof Error ? error.message : String(error)}`)
      return false
    } finally {
      setActing(false)
    }
  }, [acting, loadGoal, loadGoalHistory, sessionId])

  /** 启动 Goal；契约是可选项，只有填了的字段才会带上。 */
  const startGoal = React.useCallback(async (text: string, contract?: GoalContract): Promise<boolean> => {
    const client = clientRef.current
    if (!client || acting) return false
    setActing(true)
    try {
      await client.startGoal({ sessionId, goal: text, ...(contract ? { contract } : {}) })
      await loadGoal(client)
      return true
    } catch (error) {
      setNotice(`设定目标失败：${error instanceof Error ? error.message : String(error)}`)
      return false
    } finally {
      setActing(false)
    }
  }, [acting, loadGoal, sessionId])

  /**
   * `/goal` 命令：与桌面端同一套语法（`parseGoalCommand`），执行走 WS 的 goal 命令。
   *
   * 命令本身不是消息 —— 命中后不再进入发送/排队路径，也不会被当成正文发给模型。
   * 返回「是否成功」，调用方据此决定要不要清空输入框（invalid 时必须留着让用户改）。
   */
  const runGoalCommand = React.useCallback(async (command: GoalCommand): Promise<boolean> => {
    if (command.type === 'not_goal') return false
    if (command.type === 'invalid') {
      setNotice(command.reason)
      return false
    }
    const client = clientRef.current
    if (!client || !connected) {
      setNotice('连接未就绪，稍后再试')
      return false
    }
    if (command.type === 'start') {
      // 服务端不允许在「有未完成 Goal」时直接 start（会报错），所以先让用户确认替换
      if (goal && goal.status !== 'completed') {
        setPendingGoalStart({ goal: command.goal, ...(command.contract ? { contract: command.contract } : {}) })
        return true
      }
      const started = await startGoal(command.goal, command.contract)
      if (started) setNotice('Goal 已启动')
      return started
    }
    if (command.type === 'status') {
      if (!goal) {
        setNotice('当前没有 Goal，用「/goal <目标>」发起一个')
        return true
      }
      const label = goalStatusLabel(
        goal,
        { askUser: interactions.askUsers.length, permission: interactions.permissions.length },
        Date.now(),
      )
      const budget = formatGoalBudget(goal.limits)
      setNotice(`Goal：${label} · 第 ${goal.iteration} 轮${budget ? ` · 预算 ${budget}` : ''}`)
      return true
    }
    const done = await runGoalAction(command.type)
    if (done) {
      setNotice({
        pause: 'Goal 已暂停',
        resume: 'Goal 已恢复',
        stop: 'Goal 已停止',
        clear: 'Goal 已清除并归档',
      }[command.type])
    }
    return done
  }, [connected, goal, interactions.askUsers.length, interactions.permissions.length, runGoalAction, startGoal])

  /**
   * 替换当前 Goal：先 clear（归档旧目标）再 start。
   *
   * 不能复用 `startGoal` 回调：它带 `acting` 守卫，而这里已经置了 acting，
   * 直接调会被静默拒绝（点了没反应）。
   */
  const replaceGoal = React.useCallback(async (): Promise<void> => {
    const client = clientRef.current
    const next = pendingGoalStart
    if (!client || !next) return
    setActing(true)
    try {
      await client.clearGoal(sessionId)
      setGoal(null)
      await loadGoalHistory(client)
      await client.startGoal({ sessionId, goal: next.goal, ...(next.contract ? { contract: next.contract } : {}) })
      await loadGoal(client)
      setPendingGoalStart(null)
      setNotice('已替换目标')
    } catch (error) {
      setNotice(`替换目标失败：${error instanceof Error ? error.message : String(error)}`)
    } finally {
      setActing(false)
    }
  }, [pendingGoalStart, sessionId, loadGoalHistory, loadGoal])

  /**
   * 保存目标/契约/预算；`resume` 为真时接着恢复。
   *
   * 顺序必须是「先保存再恢复」：预算耗尽的 Goal 不先扩容就恢复会被主进程拒绝，
   * 用户会以为改了没用。失败时保留编辑器，让用户能改完重试。
   */
  const saveGoal = React.useCallback(async (
    patch: { goal: string; contract: GoalContract; limits: GoalLimits },
    resume: boolean,
  ): Promise<void> => {
    const client = clientRef.current
    if (!client || acting) return
    setActing(true)
    try {
      await client.updateGoal({
        sessionId,
        goal: patch.goal,
        contract: patch.contract,
        limits: {
          maxIterations: patch.limits.maxIterations ?? null,
          maxDurationMs: patch.limits.maxDurationMs ?? null,
          maxTokens: patch.limits.maxTokens ?? null,
          ...(patch.limits.maxConsecutiveFailures ? { maxConsecutiveFailures: patch.limits.maxConsecutiveFailures } : {}),
        },
      })
      if (resume) await client.resumeGoal(sessionId)
      await loadGoal(client)
      setGoalEditorOpen(false)
      setNotice(resume ? 'Goal 已更新并恢复' : 'Goal 已更新')
    } catch (error) {
      setNotice(`保存 Goal 失败：${error instanceof Error ? error.message : String(error)}`)
    } finally {
      setActing(false)
    }
  }, [acting, loadGoal, sessionId])

  /** 切换预设（岗位）。服务端按会话所属工作区解析作用域，下一轮生效。 */
  const applyPreset = async (presetId: string): Promise<void> => {
    const client = clientRef.current
    if (!client || acting) return
    if (presetId === currentPreset?.id) {
      setSettingsMenuOpen(false)
      setPresetPanelOpen(false)
      return
    }
    setActing(true)
    try {
      await client.updateSessionPreset(sessionId, presetId)
      await loadSessionMeta(client)
      setSettingsMenuOpen(false)
      setPresetPanelOpen(false)
      const name = presets.find((item) => item.id === presetId)?.name ?? presetId
      setNotice(`已切换到「${name}」 · 下一轮消息生效`)
    } catch (error) {
      setNotice(`切换预设失败：${error instanceof Error ? error.message : String(error)}`)
    } finally {
      setActing(false)
    }
  }

  /** 新建 / 编辑预设。失败返回 false，编辑器保留表单让人改完重试。 */
  const savePreset = async (id: string | null, payload: PresetPatch | Record<string, unknown>): Promise<boolean> => {
    const client = clientRef.current
    if (!client || acting) return false
    setActing(true)
    try {
      const updates = payload as unknown as Record<string, unknown>
      if (id) await client.updatePreset(meta?.workspaceSlug, id, updates)
      else await client.createPreset(meta?.workspaceSlug, updates)
      await loadPresets(client)
      setNotice(id ? '预设已更新' : '预设已创建')
      return true
    } catch (error) {
      setNotice(`保存预设失败：${error instanceof Error ? error.message : String(error)}`)
      return false
    } finally {
      setActing(false)
    }
  }

  /** 内置预设不可编辑：复制是唯一能改它的方式，复制完直接打开编辑器。 */
  const copyPreset = async (fromId: string): Promise<WebPreset | null> => {
    const client = clientRef.current
    if (!client || acting) return null
    setActing(true)
    try {
      const created = normalizePresets([await client.copyPreset(meta?.workspaceSlug, fromId)])[0] ?? null
      await loadPresets(client)
      return created
    } catch (error) {
      setNotice(`复制预设失败：${error instanceof Error ? error.message : String(error)}`)
      return null
    } finally {
      setActing(false)
    }
  }

  const deletePreset = async (presetId: string): Promise<boolean> => {
    const client = clientRef.current
    if (!client || acting) return false
    setActing(true)
    try {
      await client.deletePreset(meta?.workspaceSlug, presetId)
      await loadPresets(client)
      if (currentPreset?.id === presetId) await loadSessionMeta(client)
      setNotice('预设已删除')
      return true
    } catch (error) {
      setNotice(`删除预设失败：${error instanceof Error ? error.message : String(error)}`)
      return false
    } finally {
      setActing(false)
    }
  }

  const setPresetDefault = async (presetId: string): Promise<void> => {
    const client = clientRef.current
    if (!client || acting) return
    setActing(true)
    try {
      await client.setDefaultPreset(meta?.workspaceSlug, presetId)
      setDefaultPresetId(presetId)
      setNotice('已设为工作区默认预设')
    } catch (error) {
      setNotice(`设置默认预设失败：${error instanceof Error ? error.message : String(error)}`)
    } finally {
      setActing(false)
    }
  }

  /**
   * 切换会话的权限模式。
   *
   * 这里必须走服务端命令，而不是在页面里「代用户点允许」：
   * 后者只影响这个网页，电脑端仍按原模式弹卡，两边状态会越走越远。
   */
  const switchPermissionMode = React.useCallback(async (mode: SendPermissionMode): Promise<void> => {
    const client = clientRef.current
    if (!client || switchingMode) return
    setSettingsMenuOpen(false)
    if ((meta?.permissionMode ?? 'bypassPermissions') === mode) return
    setSwitchingMode(true)
    try {
      await client.updatePermissionMode(sessionId, mode)
      // 成功后以本地切换为准（服务端也会广播 permission_mode_changed，两条路殊途同归）
      setMeta((previous) => (previous ? { ...previous, permissionMode: mode } : previous))
    } catch (error) {
      setNotice(`切换权限模式失败：${error instanceof Error ? error.message : String(error)}`)
    } finally {
      setSwitchingMode(false)
    }
  }, [sessionId, switchingMode, meta])

  /** 有正文或已就绪附件就能发；上传中不许发（否则会发出一条缺文件的消息）。 */
  const canSend = Boolean(
    (input.trim() || readyAttachments.length > 0) && connected && !busy && !hasPending && !uploadingAttachments,
  )

  /**
   * 真正发出去（空闲路径）：透传会话当前权限模式与附件。
   * 抽成独立函数是因为「停止本轮后重发」也要走它。
   */
  const sendText = React.useCallback(async (
    text: string,
    attachmentsToSend: ReadonlyArray<{ filename: string }>,
  ): Promise<void> => {
    const mentionedSkills = extractMentionedSkills(text)
    const client = clientRef.current
    if (!client) throw new Error('连接已断开')
    if (!meta?.channelId) throw new Error('尚未取到会话渠道信息')
    await client.sendMessage({
      sessionId,
      userMessage: text,
      channelId: meta.channelId,
      modelId: meta.modelId,
      workspaceId: meta.workspaceId,
      // 必须透传会话当前的权限模式：服务端把它作为本轮 override。
      // 写死 'auto' 会把会话的「完全自动」降级成每步都要确认（历史 bug）。
      permissionMode: meta.permissionMode ?? 'bypassPermissions',
      // 让主进程把这条消息标成「网页发的」，桌面端与手机上都能看出处
      clientKind: 'webui',
      // 正文里的 /skill:<slug> token 同步传一份：主进程用它做技能路由（加载技能）
      ...(mentionedSkills.length > 0 ? { mentionedSkills } : {}),
      ...(attachmentsToSend.length > 0 ? { attachments: [...attachmentsToSend] } : {}),
    })
  }, [meta, sessionId])

  /** 新的一轮开始后，上一轮的结局与「正在重试」就不再适用于会话尾部。 */
  const clearPreviousRunStatus = React.useCallback((): void => {
    setRunOutcome(null)
    setRetryInfo(null)
  }, [])

  /** 运行中追加（Claude 支持流式追加；Pi 会回 QUEUE_UNSUPPORTED） */
  const queueText = React.useCallback(async (text: string): Promise<void> => {
    const client = clientRef.current
    if (!client) throw new Error('连接已断开')
    await client.queueMessage({
      sessionId,
      userMessage: text,
      clientKind: 'webui',
      ...(extractMentionedSkills(text).length > 0 ? { mentionedSkills: extractMentionedSkills(text) } : {}),
    })
  }, [sessionId])

  /**
   * 发送入口：先判「是否在跑」，在跑就排队；排队不可用时给「停止并发送」的确认。
   *
   * 为什么不能只按 `sessionActive` 分支：它是事件流推断的结果，刷新/重连/断帧之后会落后，
   * 于是对正在跑的会话发 `send_message`，被 claim 拒绝并弹一条红字（用户看到的
   * 「AGENT_RUN_ALREADY_ACTIVE」）。现在两个方向都兜住：
   * ① `server_detail.active` 对齐运行态；② 真被拒时把运行态置真、改走排队。
   */
  const send = async (): Promise<void> => {
    const client = clientRef.current
    if (busy || !client) return
    // 通知权限只能在用户手势里申请：把申请挂在发送这个动作上，不打扰首次打开
    requestNotifyPermission()
    if (uploadingAttachments) {
      setNotice('附件还在上传，请稍候')
      return
    }
    const text = resolveSendText(input, readyAttachments.length)
    if (!text) return
    /**
     * `/goal` 是**客户端命令**（与桌面端同一语法），不是消息：
     * 命中后不进入发送/排队路径，也不会被当成正文发给模型。
     */
    const goalCommand = parseGoalCommand(text)
    if (goalCommand.type !== 'not_goal') {
      const handled = await runGoalCommand(goalCommand)
      // 失败（例如「目标不能为空」）要保留原文让用户改
      if (handled) setInput('')
      return
    }
    if (!meta?.channelId) {
      setNotice('尚未取到会话渠道信息，请稍候或刷新页面')
      return
    }
    const attachmentsToSend = readyAttachments.map((item) => ({ filename: item.filename }))
    const runningNow = sessionActive || serverActive
    // 排队通道没有附件能力：明说，而不是静默丢掉文件
    if (runningNow && attachmentsToSend.length > 0) {
      setNotice('运行中不能附带文件，请等本轮结束后再发')
      return
    }

    setBusy(true)
    try {
      if (runningNow) {
        await queueText(text)
        setInput('')
        clearPreviousRunStatus()
        setNotice('已加入队列，本轮结束后自动发送')
        return
      }
      try {
        await sendText(text, attachmentsToSend)
      } catch (error) {
        // 客户端的运行态落后于服务端：claim 拒绝就是「其实在跑」
        if (!isRunActiveError(error)) throw error
        setServerActive(true)
        setRunning(true)
        try {
          await queueText(text)
          setInput('')
          clearPreviousRunStatus()
          setNotice('本轮仍在运行，已加入队列')
          return
        } catch (queueError) {
          if (!isQueueUnsupportedError(queueError)) throw queueError
          setStopSendText(text)
          return
        }
      }
      setInput('')
      clearAttachments()
      clearPreviousRunStatus()
      setRunning(true)
      setServerActive(true)
      setNotice('')
    } catch (error) {
      if (isQueueUnsupportedError(error)) {
        setStopSendText(text)
        return
      }
      // 失败保留附件与文案：重试时不该让用户重新挑一遍文件
      setNotice(`发送失败：${error instanceof Error ? error.message : String(error)}`)
    } finally {
      setBusy(false)
    }
  }

  /**
   * 「停止本轮并发送」：当前内核不支持追加时的唯一出路。
   *
   * 停止是异步的：`stop_agent` 返回后 claim 可能还没释放，所以用短轮询重试发送
   * （只在「运行中」这一种错误上重试 —— 那种情况服务端一定没接收这条消息，不会重复发）。
   */
  const stopAndSend = React.useCallback(async (): Promise<void> => {
    const client = clientRef.current
    const text = stopSendText
    if (!client || !text) return
    setActing(true)
    setStopSendText(null)
    try {
      await client.stopAgent(sessionId)
      setRunning(false)
      setServerActive(false)
      let lastError: unknown = null
      for (let attempt = 0; attempt < 20; attempt += 1) {
        await new Promise((resolve) => setTimeout(resolve, 400))
        try {
          await sendText(text, [])
          setInput('')
          clearPreviousRunStatus()
          setNotice('已停止上一轮并发送')
          return
        } catch (error) {
          if (!isRunActiveError(error)) throw error
          lastError = error
        }
      }
      setNotice(`停止后仍显示运行中：${lastError instanceof Error ? lastError.message : '请稍后手动发送'}`)
    } catch (error) {
      setNotice(`停止失败：${error instanceof Error ? error.message : String(error)}`)
    } finally {
      setActing(false)
    }
  }, [stopSendText, sessionId, sendText, clearPreviousRunStatus])

  const stop = async (): Promise<void> => {
    try {
      await clientRef.current?.stopAgent(sessionId)
      setRunning(false)
    } catch (error) {
      setNotice(`停止失败：${error instanceof Error ? error.message : String(error)}`)
    }
  }

  /** 操作面板对应的回合（面板关闭后数据不闪：只在有目标时计算） */
  const actionTarget = React.useMemo(() => {
    if (actionsTurn === null) return null
    const group = groups[actionsTurn]
    const turn = turns.find((entry) => entry.index === actionsTurn)
    if (!group || group.type !== 'user' || !turn) return null
    const uuid = (group.message as { uuid?: unknown }).uuid
    return {
      text: stripContextBlocks(turn.text),
      userUuid: typeof uuid === 'string' && uuid ? uuid : '',
    }
  }, [actionsTurn, groups, turns])

  const copyActionText = React.useCallback(async (): Promise<void> => {
    if (!actionTarget) return
    const ok = await copyText(actionTarget.text)
    setNotice(ok ? '已复制' : '复制失败，请长按选择文字复制')
  }, [actionTarget])

  /** 解析「上一条 assistant 消息」：回退目标指向它之后 = 删掉目标用户消息及之后全部 */
  const resolveAssistantUuid = React.useCallback(
    (turnIndex: number): string | undefined => findPreviousAssistantUuid(groups, turnIndex),
    [groups],
  )

  /** 仅对话回退（legacy rewind_session）：服务端 prepare+execute 一步完成 */
  const rewindLegacy = React.useCallback(async (text: string, assistantUuid?: string): Promise<void> => {
    const client = clientRef.current
    if (!client) return
    setActing(true)
    try {
      await client.rewindSession({ sessionId, ...(assistantUuid ? { assistantMessageUuid: assistantUuid } : {}) })
      setServerActive(false)
      setRunning(false)
      await reload(client, 'replace')
      await interactions.loadInteractions(client)
      clearPreviousRunStatus()
      if (text) setInput(text)
      inputRef.current?.focus()
      setNotice('已回退到这条消息之前，改完可以直接发送')
    } catch (error) {
      setNotice(`回退失败：${error instanceof Error ? error.message : String(error)}`)
    } finally {
      setActing(false)
    }
  }, [sessionId, reload, interactions.loadInteractions])

  /**
   * 「重试上一轮」的目标：最新一条用户消息 + 它之前的助手锚点。
   *
   * 只对 failed / limited 的结局开放（停止是主动行为，不是失败）。
   * 带附件的轮次不给按钮：回退只截对话，重发只带正文，静默丢附件比不给按钮更糟。
   * 首轮失败没有回退锚点，和桌面端一样走 session-start（清空整段对话）后重发。
   */
  const retryTarget = React.useMemo(() => {
    if (effectiveOutcome?.kind !== 'failed' && effectiveOutcome?.kind !== 'limited') return null
    // acting 不参与判定：重试进行中要保留按钮显示「重试中…」，由按钮 disabled 防重入
    if (sessionActive || busy || hasPending || !connected || !meta?.channelId) return null
    return resolveRetryTurn(groups, turns)
  }, [effectiveOutcome, sessionActive, busy, hasPending, connected, meta, groups, turns])

  /** 截断最后一轮并重发同一句；截断成功但发送失败时把原文放回输入框。 */
  const retryLastTurn = React.useCallback(async (): Promise<void> => {
    const client = clientRef.current
    const target = retryTarget
    if (!client || !target || acting) return
    setActing(true)
    let truncated = false
    try {
      await client.rewindSession({ sessionId, ...(target.assistantUuid ? { assistantMessageUuid: target.assistantUuid } : {}) })
      truncated = true
      setServerActive(false)
      setRunning(false)
      await reload(client, 'replace')
      await interactions.loadInteractions(client)
      clearPreviousRunStatus()
      await sendText(target.text, [])
      setRunning(true)
      setServerActive(true)
      setNotice('已重试上一轮')
    } catch (error) {
      if (truncated) setInput(target.text)
      setNotice(`重试失败：${error instanceof Error ? error.message : String(error)}`)
    } finally {
      setActing(false)
    }
  }, [retryTarget, acting, sessionId, reload, interactions.loadInteractions, clearPreviousRunStatus, sendText])

  /**
   * 编辑重发入口：按勾选决定「仅对话」还是「带文件还原」。
   *
   * 带文件还原走两段式 rewind_prepare / rewind_execute：先算计划给用户确认
   * （哪些文件会还原/删除），再执行。prepare 失败（内核不支持 / 工作区历史不可用）
   * 时降级成仅对话，而不是把整条路堵死。
   */
  const startEditRewind = React.useCallback(async (restoreFiles: boolean): Promise<void> => {
    const target = actionTarget
    const turnIndex = actionsTurn
    if (!target || turnIndex === null) return
    if (!target.userUuid) {
      setNotice('这条消息缺少标识，无法回退')
      return
    }
    const assistantUuid = resolveAssistantUuid(turnIndex)
    setActionsTurn(null)
    if (!restoreFiles) {
      await rewindLegacy(target.text, assistantUuid)
      return
    }
    const client = clientRef.current
    if (!client) return
    setActing(true)
    try {
      const raw = await client.sendCommand<RewindPrepareEnvelope>({
        type: 'rewind_prepare',
        sessionId,
        mode: 'both',
        target: assistantUuid
          ? { kind: 'message', messageUuid: assistantUuid }
          : { kind: 'session-start' },
      })
      const plan = raw?.plan
      const files = Array.isArray(plan?.files) ? plan.files : []
      const actionable = files.filter((file) => file.action === 'restore' || file.action === 'delete')
      // 内核不支持文件还原 / 没检测到文件改动：降级成仅对话，不让用户卡死
      if (!plan?.planId || !plan.revision || !plan.capability?.supportsFiles || actionable.length === 0) {
        setNotice('没有可还原的文件改动，已改为仅回退对话')
        setActing(false)
        await rewindLegacy(target.text, assistantUuid)
        return
      }
      setRewindPlan({
        planId: plan.planId,
        revision: {
          session: plan.revision.session ?? 0,
          files: plan.revision.files ?? 0,
          state: plan.revision.state ?? 0,
        },
        files,
        text: target.text,
      })
    } catch (error) {
      setNotice('按文件还原不可用，已改为仅回退对话')
      setActing(false)
      await rewindLegacy(target.text, assistantUuid)
      return
    } finally {
      setActing(false)
    }
  }, [actionTarget, actionsTurn, resolveAssistantUuid, rewindLegacy, sessionId])

  /**
   * 执行回退计划。非 committed（进入恢复流程）时提示去电脑端处理 —— 恢复日志是
   * 持久化的，但网页端没有对账 UI，假装成功更糟。
   */
  const executeRewindPlan = React.useCallback(async (): Promise<void> => {
    const client = clientRef.current
    const flow = rewindPlan
    if (!client || !flow) return
    setActing(true)
    try {
      const raw = await client.sendCommand<RewindExecuteEnvelope>({
        type: 'rewind_execute',
        sessionId,
        planId: flow.planId,
        expectedRevision: flow.revision,
      })
      setRewindPlan(null)
      if (raw?.status && raw.status !== 'committed') {
        setNotice(`回退进入恢复流程（${raw.status}），请在电脑端查看恢复记录`)
        return
      }
      setServerActive(false)
      setRunning(false)
      await reload(client, 'replace')
      await interactions.loadInteractions(client)
      clearPreviousRunStatus()
      setInput(flow.text)
      inputRef.current?.focus()
      const restored = raw?.files?.restored?.length ?? 0
      const deleted = raw?.files?.deleted?.length ?? 0
      setNotice(`已回退：还原 ${restored} 个文件${deleted ? `、删除 ${deleted} 个` : ''}，改完可以直接发送`)
    } catch (error) {
      setNotice(`执行回退失败：${error instanceof Error ? error.message : String(error)}`)
    } finally {
      setActing(false)
    }
  }, [rewindPlan, sessionId, reload, interactions.loadInteractions])


  /**
   * 手动压缩上下文：与桌面端同一语义 —— 发送 /compact 命令。
   * 会话里会留下一条 /compact 用户气泡与压缩分隔（compacting / compact_boundary），
   * 压缩完成由 run_completed 触发 reload 收敛。
   * 守卫对齐桌面端 handleCompact：运行中 / 待确认 / 未连接都不许发，
   * busy 兼任防重入（压缩 run 飞行期间按钮保持禁用）。
   */
  const compactContext = async (): Promise<void> => {
    const client = clientRef.current
    if (!client || busy || !connected || hasPending || sessionActive) {
      // 确认面板打开期间这一轮可能又跑起来了：静默返回会让「开始压缩」像没点中
      setNotice('现在不能压缩：这一轮还在跑，或还有待处理的确认')
      return
    }
    if (!meta?.channelId) {
      setNotice('尚未取到会话渠道信息，请稍候或刷新页面')
      return
    }
    setBusy(true)
    try {
      await client.sendMessage({
        sessionId,
        userMessage: '/compact',
        channelId: meta.channelId,
        modelId: meta.modelId,
        workspaceId: meta.workspaceId,
        permissionMode: meta.permissionMode ?? 'bypassPermissions',
        clientKind: 'webui',
      })
      setRunning(true)
      setCompacting(true)
      clearPreviousRunStatus()
    } catch (error) {
      setCompacting(false)
      setNotice(`压缩失败：${error instanceof Error ? error.message : String(error)}`)
    } finally {
      setBusy(false)
    }
  }

  /**
   * 切换模型。服务端会同时更新全局默认（下次新建会话继承），所以这里不用自己记。
   * 成功后重取会话详情：`modelId`、`channelId` 与能力档位都跟着变。
   */
  const pickModel = async (option: ModelOption): Promise<void> => {
    const client = clientRef.current
    if (!client || switchingModel) return
    if (option.modelId === meta?.modelId && option.channelId === meta?.channelId) {
      setSettingsMenuOpen(false)
      return
    }
    setSwitchingModel(true)
    try {
      await client.updateSessionModel(sessionId, option.channelId, option.modelId)
      await loadSessionMeta(client)
      setSettingsMenuOpen(false)
      setNotice(`已切换到 ${option.name}`)
    } catch (error) {
      setNotice(`切换模型失败：${error instanceof Error ? error.message : String(error)}`)
    } finally {
      setSwitchingModel(false)
    }
  }

  /** 切换推理强度；`FOLLOW_DEFAULT` 翻译成 null = 清除会话覆盖。菜单不关，方便连着调。 */
  const pickStrength = async (value: string): Promise<void> => {
    const client = clientRef.current
    if (!client || switchingModel) return
    if (value === strength.value) return
    const level = value === FOLLOW_DEFAULT ? null : value
    setSwitchingModel(true)
    try {
      if (strength.kind === 'thinking') await client.updateSessionThinkingLevel(sessionId, level)
      else await client.updateSessionAgentEffort(sessionId, level)
      await loadSessionMeta(client)
    } catch (error) {
      setNotice(`切换强度失败：${error instanceof Error ? error.message : String(error)}`)
    } finally {
      setSwitchingModel(false)
    }
  }

  return (
    <div className="app">
      <Topbar
        title={meta?.title || ''}
        sessionId={sessionId}
        connected={connected}
        connection={connection}
        hasNotice={Boolean(notice)}
        staleBundle={staleBundle}
        metaReady={Boolean(meta)}
        modelLabel={describeSessionModel(meta?.modelId, models, strength, currentPreset, presetRequired)}
        settingsMenuOpen={settingsMenuOpen}
        onToggleSettings={() => { setFocusCompact(false); setInfoOpen(false); setSettingsMenuOpen((previous) => !previous) }}
        onOpenCompact={() => { setInfoOpen(false); setHistoryOpen(false); setFocusCompact(true); setSettingsMenuOpen(true) }}
        contextUsage={contextUsage}
        infoOpen={infoOpen}
        onOpenInfo={() => { setSettingsMenuOpen(false); setHistoryOpen(false); setInfoOpen(true) }}
        historyOpen={historyOpen}
        onToggleHistory={() => { setInfoOpen(false); setHistoryOpen((previous) => !previous) }}
        historyAvailable={turns.length > 0}
      />
      {pendingGoalStart && (
        <ConfirmSheet
          title="替换当前目标"
          description={`当前会话还有一个未完成的 Goal（${goal ? goalStatusText(goal.status) : '进行中'}）。替换会把它归档到目标历史。`}
          hint="新目标沿用当前会话上下文与工作历史；旧目标不会自动恢复。"
          confirmLabel="替换并启动"
          busy={acting}
          onConfirm={() => { void replaceGoal() }}
          onClose={() => setPendingGoalStart(null)}
        />
      )}
      {rewindPlan && (
        <RewindPlanSheet
          files={rewindPlan.files}
          busy={acting}
          onExecute={() => { void executeRewindPlan() }}
          onClose={() => setRewindPlan(null)}
        />
      )}
      {compactConfirmOpen && (
        <ConfirmSheet
          title="压缩上下文"
          description="把当前会话的历史压成摘要，释放上下文窗口。"
          hint="压缩会占用一轮，期间不能发送消息；细节以摘要形式保留，完整记录仍在会话文件里。"
          confirmLabel="开始压缩"
          busy={busy}
          onConfirm={() => { setCompactConfirmOpen(false); void compactContext() }}
          onClose={() => setCompactConfirmOpen(false)}
        />
      )}
      {stopSendText !== null && (
        <StopAndSend
          busy={acting}
          onStop={() => { void stopAndSend() }}
          onClose={() => setStopSendText(null)}
        />
      )}
      {actionsTurn !== null && actionTarget && (
        <TurnActions
          text={actionTarget.text}
          canEdit={!sessionActive && Boolean(actionTarget.userUuid)}
          editHint={sessionActive ? '本轮还在运行，结束后再编辑' : '这条消息缺少标识（旧数据），无法回退'}
          busy={acting}
          restoreFiles={restoreFiles}
          onRestoreFilesChange={setRestoreFiles}
          onCopy={() => { void copyActionText() }}
          onEdit={() => { void startEditRewind(restoreFiles) }}
          onClose={() => setActionsTurn(null)}
        />
      )}
      {infoOpen && (
        <SessionInfo
          items={[
            // 模型 / 权限 / 档位在标题下面那一行已经显示了，这里只留「对不上号时用得上」的两项
            { key: '会话', value: sessionId || '未指定' },
            { key: '构建', value: WEBUI_BUILD },
            {
              key: '技能 /',
              value: skillStatus === 'ok'
                ? `${skillOptions.length} 个可用`
                : skillStatus === 'empty'
                  ? (skillWarning ? `扫描失败：${skillWarning}` : '工作区没有启用的技能')
                  : skillStatus === 'unsupported'
                    ? '主进程未更新，需重启 Profer'
                    : skillStatus === 'error'
                      ? `读取失败：${skillWarning ?? '请刷新重试'}`
                      : '加载中…',
            },
            {
              key: '文件 @',
              value: fileOptions.length > 0 ? `${fileOptions.length} 个可引用` : '无可引用文件',
            },
            {
              key: '窗口',
              value: contextWindow ? formatTokens(contextWindow) + ' tokens' : '未知名（跑一轮后可知名）',
            },
          ]}
          onClose={() => setInfoOpen(false)}
        />
      )}
      {historyOpen && (
        <MessageHistory
          entries={historyEntries}
          hasOlder={hasOlder}
          loadingOlder={loadingOlder}
          onLoadOlder={() => void loadOlder()}
          onJump={jumpToTurn}
          onRemoteSearch={remoteSearch}
          onJumpMatch={(match) => { void jumpToSearchMatch(match) }}
          jumpDisabled={sessionActive}
          jumping={searchJumping}
          onClose={() => setHistoryOpen(false)}
        />
      )}
      {settingsMenuOpen && (
        <SessionSettingsMenu
          models={models}
          currentModelId={meta?.modelId}
          strength={strength}
          presets={availablePresets}
          currentPresetId={currentPreset?.id}
          running={sessionActive}
          busy={switchingModel}
          permissionMode={permissionMode ?? 'bypassPermissions'}
          permissionModes={PERMISSION_MODES}
          hasGoal={Boolean(goal)}
          onOpenGoal={() => { setSettingsMenuOpen(false); if (goal) setGoalEditorOpen(true); else setGoalOpen(true) }}
          compacting={compacting}
          compactDisabled={!connected || busy || hasPending || sessionActive || !meta?.channelId}
          contextUsage={contextUsage}
          focusCompact={focusCompact}
          onCompact={() => { setSettingsMenuOpen(false); setCompactConfirmOpen(true) }}
          onPickModel={(option) => void pickModel(option)}
          onPickStrength={(value) => void pickStrength(value)}
          onPickPreset={(id) => void applyPreset(id)}
          onPickPermissionMode={(mode) => void switchPermissionMode(mode as SendPermissionMode)}
          onManagePresets={() => { setSettingsMenuOpen(false); setPresetPanelOpen(true) }}
          onClose={() => { setFocusCompact(false); setSettingsMenuOpen(false) }}
        />
      )}
      {goal
        ? (
          <GoalBar
            goal={goal}
            archives={goalHistory.filter((item) => item.id !== goal.id)}
            busy={acting}
            waits={{ askUser: interactions.askUsers.length, permission: interactions.permissions.length }}
            onAction={(action) => void runGoalAction(action)}
            onEdit={() => setGoalEditorOpen(true)}
          />
        )
        : connected
          ? <GoalLauncher
            busy={acting}
            open={goalOpen}
            onOpenChange={setGoalOpen}
            onStart={(nextGoal, contract) => { setGoalOpen(false); void startGoal(nextGoal, contract) }}
          />
          : null}
      {presetPanelOpen && (
        <PresetPanel
          presets={availablePresets}
          currentId={currentPreset?.id}
          defaultPresetId={defaultPresetId}
          models={models}
          busy={acting}
          onClose={() => setPresetPanelOpen(false)}
          onApply={(id) => void applyPreset(id)}
          onSave={(id, payload) => savePreset(id, payload)}
          onCopy={(id) => copyPreset(id)}
          onDelete={(id) => deletePreset(id)}
          onSetDefault={(id) => void setPresetDefault(id)}
        />
      )}
      {/* 编辑面板做成覆盖层：它比状态条高得多，塞进状态条会把消息区挤到没有 */}
      {goalEditorOpen && goal && (
        <GoalEditor
          goal={goal}
          busy={acting}
          onClose={() => setGoalEditorOpen(false)}
          onSave={(patch, resume) => void saveGoal(patch, resume)}
        />
      )}
      {notice && <div className="notice">{notice}</div>}
      <div className="stage">
        <div
          className="stream"
          ref={scrollRef}
          onScroll={handleStreamScroll}
          onWheel={handleUserEngage}
          onTouchStart={handleUserEngage}
          onKeyDown={handleUserEngage}
        >
          {hasOlder && (
            <div className="load-older">
              <button className="load-older-btn" disabled={loadingOlder} onClick={() => void loadOlder()}>
                {loadingOlder ? '加载中…' : '加载更早的消息'}
              </button>
            </div>
          )}
          {/* 首屏消息未就位前不渲染空态，否则刷新会闪一下「开始对话」；缺凭据要立刻说明情况 */}
          {turns.length === 0 && (historyLoaded || !token) ? (
            <div className="empty">
              <div className="empty-title">{!token ? '需要访问凭据' : connected ? '开始对话' : '正在连接'}</div>
              <div className="empty-desc">
                {!token
                  // 走到这里说明 cookie、URL、localStorage 里都没有凭据了（例如清过站点数据）。
                  // 与其显示「正在连接」让人干等，不如直接说清楚下一步做什么。
                  ? '当前浏览器没有保存这个会话的凭据。请在电脑端 Profer 的「设置 → 远程连接 → Web 会话」里复制链接，重新打开一次。'
                  : connected
                    ? '这个页面只承载当前这一个会话。消息实时推送，并与电脑端保持同一份记录。'
                    : '正在连接 Profer 主进程。若长时间无响应，请确认电脑端的 Web 会话仍处于启用状态。'}
              </div>
              {connected && (
                <div className="empty-chips">
                  {QUICK_PROMPTS.map((prompt) => (
                    <button
                      key={prompt}
                      className="empty-chip"
                      onClick={() => {
                        setInput(prompt)
                        inputRef.current?.focus()
                      }}
                    >
                      {prompt}
                    </button>
                  ))}
                </div>
              )}
            </div>
          ) : turns.map((turn) => {
            /**
             * 系统消息是**分隔条**（上下文压缩边界 / 任务来源 / 权限拒绝 / 中断记录），
             * 不是谁说的话。以前落进了助手气泡分支，读起来就成了「模型说：上下文已压缩」——
             * 正好它下一句正文又以「上下文已压缩。」开头时，看起来就像同一条渲染了两遍。
             */
            if (turn.role === 'system') {
              const label = turn.text.trim()
              if (!label) return null
              return (
                <div className="turn-divider" key={turn.index} data-turn-index={turn.index}>
                  {label}
                </div>
              )
            }
            const extra = extras[turn.index]
            const timeline = timelines[turn.index]
            const isAssistant = turn.role === 'assistant'
            // 过程条目挂在组里，正文只留「最终答复」——两段分开取，避免正文被折叠进执行过程
            // \(...\) / \[...\] 先规范化成 remark-math 认得的 $ / $$，再交给 markdown 渲染
            const body = normalizeLatexDelimiters(
              stripContextBlocks(isAssistant ? (timeline?.headline ?? '') : turn.text),
            )
            // 图表按 toolCallId 挂回回合：tool_use 的 id 在 WS 投影里是保留的，这是唯一可靠的连接键。
            // 只有工具行已加载的回合才拿得到图表；更早的历史图表等对应回合续页加载后自然出现。
            const turnViz = (timeline?.items ?? [])
              .flatMap((item) => (item.kind === 'tool' ? [vizByTool.get(item.tool.id)] : []))
              .filter((item): item is VisualizationItem => Boolean(item))
            // 过程组只在「这一轮还在跑」时自动展开：running 可能被迟到的收尾消息顶住，
            // 所以再叠一道数据判据，跑完的轮次一律收起，正文才能露出来。
            const live = sessionActive && turn.index === turns.length - 1
            const turnMeta = turnMetas[turn.index]
            const hasMeta = Boolean(turnMeta
              && (turnMeta.model || turnMeta.clock || turnMeta.sourceLabel || turnMeta.usageText))
            const metaRow = hasMeta && turnMeta ? (
              <div className="turn-meta">
                {turnMeta.model && <span className="tm-model">{turnMeta.model}</span>}
                {turnMeta.sourceLabel && <span className="tm-src">{turnMeta.sourceLabel}</span>}
                {turnMeta.clock && <span className="tm-clock">{turnMeta.clock}</span>}
                {turnMeta.usageText && (
                  <span className="tm-usage" title={turnMeta.usageDetail}>{turnMeta.usageText}</span>
                )}
              </div>
            ) : null
            /**
             * 正文里裸文件名的定位依据：本轮所有工具调用触及的路径。
             * 同名冲突在 `buildTurnFileIndex` 内剔除；没有索引时裸文件名保持普通代码。
             */
            const fileIndex = isAssistant
              ? buildTurnFileIndex(
                timeline?.items.flatMap((item) => (
                  item.kind === 'tool' && item.tool.filePath ? [item.tool.filePath] : []
                )) ?? [],
              )
              : undefined
            return (
              <div
                key={turn.index}
                className={`turn ${turn.role === 'user' ? 'user' : 'assistant'}`}
                data-turn-index={turn.index}
              >
                {isAssistant && timeline && (
                  <TurnProcess
                    timeline={timeline}
                    live={live}
                    onOpenFile={openFile}
                    {...(fileIndex ? { fileIndex } : {})}
                    visualizations={turnViz}
                  />
                )}
                {/* 任务卡放在过程组外：过程收起后仍能看到「还剩几步 / 现在做到哪」 */}
                {isAssistant && timeline && timeline.tasks.length > 0 && (
                  <TaskProgressCard tasks={timeline.tasks} />
                )}
                {(body || (turn.role === 'user' && (extra?.attachedFiles.length ?? 0) > 0)) && (
                  <div className="body">
                    {turn.role === 'user' && <TurnAttachments files={extra?.attachedFiles ?? []} />}
                    {body && (
                      <MarkdownWithFiles
                        text={body}
                        onOpenFile={openFile}
                        {...(fileIndex ? { fileIndex } : {})}
                      />
                    )}
                  </div>
                )}
                <TurnImages imagePaths={extra?.imagePaths ?? []} loadImage={loadImage} />
                {/* 用户消息的元信息与操作入口收在同一行：⋯ 单独占一行会给每条消息拖一截尾巴 */}
                {turn.role === 'user' ? (
                  <div className="turn-footer">
                    {metaRow}
                    <button
                      className="turn-actions-btn"
                      onClick={() => setActionsTurn(turn.index)}
                      aria-label="消息操作"
                      title="消息操作"
                    >
                      ⋯
                    </button>
                  </div>
                ) : metaRow}
              </div>
            )
          })}
          {/* 结局行只在收尾后出现：运行中已经在跑，不需要再解释上一轮怎么结束的；
              首屏消息未就位前也不显示，事件回放会先把上一轮结局顶出来 */}
          {effectiveOutcome && historyLoaded && !sessionActive && (
            <div
              className={`run-outcome ${runOutcomeTone(effectiveOutcome.kind)}`}
              data-outcome-source={effectiveOutcome.source}
            >
              <span className="run-outcome-label">{effectiveOutcome.label}</span>
              {effectiveOutcome.detail && (
                <span className="run-outcome-detail">
                  {effectiveOutcome.detail}
                  {effectiveOutcome.detailCount !== undefined && effectiveOutcome.detailCount > 1
                    ? ` 等 ${effectiveOutcome.detailCount} 项`
                    : ''}
                </span>
              )}
              {retryTarget && (
                <button
                  type="button"
                  className="run-outcome-retry"
                  disabled={acting}
                  onClick={() => { void retryLastTurn() }}
                >
                  {acting ? '重试中…' : '重试'}
                </button>
              )}
            </div>
          )}
          {sessionActive && (
            <div className="running-hint">Profer 正在处理</div>
          )}
        </div>
        {jumpVisible && (
          <button
            className="jump-latest"
            onClick={() => scrollToLatest(true)}
            title="回到最新"
            aria-label="回到最新"
          >
            ↓
          </button>
        )}
      </div>
      {hasCards && (
        <div className="interactions">
          <InteractionCards
            permissions={interactions.permissions}
            askUsers={interactions.askUsers}
            exitPlans={interactions.exitPlans}
            goalProposals={interactions.goalProposals}
            busy={acting}
            {...interactions.handlers}
          />
        </div>
      )}
      <RunStatus
        retry={retryInfo}
        persistenceWarning={persistenceWarning}
        background={backgroundTasks}
        delegations={delegations}
      />
      <Composer
        input={input}
        onInputChange={setInput}
        onSend={() => { void send() }}
        onStop={() => { void stop() }}
        busy={busy}
        canSend={canSend}
        sessionActive={sessionActive}
        willQueue={sessionActive || serverActive}
        hasPending={hasPending}
        connected={connected}
        attachments={attachments}
        readyCount={readyAttachments.length}
        uploading={uploadingAttachments}
        onPickFiles={pickFiles}
        onRemoveAttachment={removeAttachment}
        fileInputRef={fileInputRef}
        inputRef={inputRef}
        skills={skillOptions}
        files={fileOptions}
        onFileQuery={(query) => {
          const client = clientRef.current
          if (client) void loadFiles(client, query)
        }}
      />
      <DiagOverlay data={diagData} />
    </div>
  )
}

createRoot(document.getElementById('root')!).render(<App />)
