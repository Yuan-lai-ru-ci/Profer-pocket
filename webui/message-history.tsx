/**
 * 历史对话（本会话的消息跳转表 + 全量搜索）。
 *
 * 解决的问题：手机上一轮长会话会累积几十上百个回合，正文只能靠往上翻，翻到底还找不到
 * 「刚才那条结论」。这个面板把已加载的回合按时间正序列出来（最新在最下），
 * 打开即贴底，往上翻就是更早的消息；点一条直接滚到那条消息。
 *
 * 搜索有两档：
 * - 1 个字符 / 服务端不支持时：只在**已加载**的回合里按全文筛选；
 * - ≥2 个字符：走服务端 `search_session_messages` 搜**整个会话**，命中项带行号，
 *   由页面取回对应窗口再跳过去（见 main.tsx 的 jumpToSearchMatch）。
 *
 * 边界说明（刻意如此）：
 * - 「加载更早」放在列表**顶部**而不是底部：更早的消息在时间序里就在上面，跟正文一致。
 * - 搜索按**全文**匹配（`HistoryEntry.text`），不是按列表里那行截断后的摘要 ——
 *   否则关键词在后半段就搜不到，用户只会觉得搜索坏了。
 * - 运行中禁止跳转到旧窗口：换窗口会与实时流、自动贴底打架；等这一轮结束再跳。
 */
import * as React from 'react'
import { useOverlay } from './overlay'
import { filterHistoryEntries, type HistoryEntry } from './history-list'
import {
  MIN_SESSION_SEARCH_LENGTH,
  SESSION_SEARCH_LIMIT,
  SESSION_SEARCH_ROLE_LABELS,
  splitSearchSnippet,
  type SessionSearchMatch,
} from './session-search'

/** 贴底判定阈值：几十 px 的误差来自行高与圆角，不必精确 */
const BOTTOM_EPSILON = 24
/** 输入防抖：全量搜索走磁盘流式扫描，不能每敲一个字就查一遍 */
const SEARCH_DEBOUNCE_MS = 300

interface RemoteSearchState {
  query: string
  matches: SessionSearchMatch[]
  loading: boolean
  error: string
}

export interface MessageHistoryProps {
  entries: HistoryEntry[]
  /** 是否还有更早的回合没加载 */
  hasOlder: boolean
  loadingOlder: boolean
  onLoadOlder: () => void
  onJump: (turnIndex: number) => void
  /** 全量搜索：返回整个会话的命中（旧服务端不提供时省略，退化为本地筛选） */
  onRemoteSearch?: (query: string) => Promise<SessionSearchMatch[]>
  onJumpMatch?: (match: SessionSearchMatch) => void
  /** 运行中禁止换窗口（实时流与自动贴底会与历史窗口打架） */
  jumpDisabled?: boolean
  /** 正在取命中所在的消息窗口 */
  jumping?: boolean
  onClose: () => void
}

export function MessageHistory({
  entries, hasOlder, loadingOlder, onLoadOlder, onJump,
  onRemoteSearch, onJumpMatch, jumpDisabled = false, jumping = false, onClose,
}: MessageHistoryProps): React.ReactElement {
  // 这个面板是只读列表，加载更早的途中也允许直接关掉（不像保存表单会被打断成未知状态）
  const sheetRef = useOverlay(onClose)
  const listRef = React.useRef<HTMLDivElement | null>(null)
  /** 上一次的 scrollHeight：用来在「顶部插入更早消息」时把视口钉住 */
  const lastHeightRef = React.useRef(0)
  const [query, setQuery] = React.useState('')
  const lastQueryRef = React.useRef(query)
  /**
   * 顶部是否还有更早的内容（决定要不要给渐隐）。
   *
   * 列表是时间正序、打开即贴底，用户在底部看不出"上面还有东西"。顶部渐隐是滚动方向
   * 最便宜的暗示：内容被"吃"进上边缘，就会去往上滑。到顶后必须撤掉，否则第一条
   * 会一直蒙着一层灰，看着像被裁掉了。
   */
  const [fadeTop, setFadeTop] = React.useState(false)
  const trimmedQuery = query.trim()
  const localVisible = React.useMemo(() => filterHistoryEntries(entries, query), [entries, query])

  const [remote, setRemote] = React.useState<RemoteSearchState | null>(null)
  const searchRequestRef = React.useRef(0)
  /** 远程搜索 harness：查询变化 → 防抖 → 只认最后一次请求的响应 */
  React.useEffect(() => {
    const text = query.trim()
    if (!onRemoteSearch || text.length < MIN_SESSION_SEARCH_LENGTH) {
      searchRequestRef.current += 1
      setRemote(null)
      return
    }
    const requestId = searchRequestRef.current + 1
    searchRequestRef.current = requestId
    setRemote({ query: text, matches: [], loading: true, error: '' })
    const timer = window.setTimeout(() => {
      onRemoteSearch(text)
        .then((matches) => {
          if (searchRequestRef.current !== requestId) return
          setRemote({ query: text, matches, loading: false, error: '' })
        })
        .catch((error: unknown) => {
          if (searchRequestRef.current !== requestId) return
          setRemote({
            query: text,
            matches: [],
            loading: false,
            error: error instanceof Error ? error.message : String(error),
          })
        })
    }, SEARCH_DEBOUNCE_MS)
    return () => window.clearTimeout(timer)
  }, [query, onRemoteSearch])

  const remoteActive = Boolean(
    onRemoteSearch && remote && trimmedQuery.length >= MIN_SESSION_SEARCH_LENGTH && remote.query === trimmedQuery,
  )
  const remoteMatches = remoteActive ? remote!.matches : []

  /**
   * 打开即贴底（最新在最下），之后只在三种情况下动滚动：
   * - 换了关键词：结果集变了，重新贴底（列表通常很短，直接到底最省事）；
   * - 用户本来就在底部（新回合到达 / 首次渲染）→ 继续贴底；
   * - 否则是「加载更早」在顶部插入了内容 → 把增高的那一段补进 scrollTop，
   *   视口里正看着的那条消息不动，新内容出现在它上方。
   * 不做这一步的话，插入后视口会莫名往下跳一屏。
   *
   * 全量搜索结果不贴底：从第一条命中开始看。
   */
  /** 只有「能滚」且「不在顶」时才需要渐隐 */
  const syncFadeTop = React.useCallback((node: HTMLDivElement | null): void => {
    if (!node) return
    const next = node.scrollHeight - node.clientHeight > 4 && node.scrollTop > 4
    setFadeTop((previous) => (previous === next ? previous : next))
  }, [])

  const listSignature = remoteActive
    ? `remote:${remote!.query}:${remoteMatches.length}`
    : `local:${query}:${localVisible.length}`

  React.useLayoutEffect(() => {
    const node = listRef.current
    if (!node) return
    if (remoteActive) {
      lastHeightRef.current = 0
      node.scrollTop = 0
      syncFadeTop(node)
      return
    }
    // 没有结果时列表整块不渲染：把基准清零，等结果回来重新贴底，
    // 否则会拿着上一次的高度算增量，视口落到半空中
    if (localVisible.length === 0) {
      lastHeightRef.current = 0
      return
    }
    if (lastQueryRef.current !== query) {
      lastQueryRef.current = query
      lastHeightRef.current = 0
    }
    const previousHeight = lastHeightRef.current
    lastHeightRef.current = node.scrollHeight
    if (previousHeight === 0) {
      node.scrollTop = node.scrollHeight
      syncFadeTop(node)
      return
    }
    const atBottom = node.scrollHeight - node.scrollTop - node.clientHeight < BOTTOM_EPSILON
    if (atBottom) {
      node.scrollTop = node.scrollHeight
      syncFadeTop(node)
      return
    }
    const delta = node.scrollHeight - previousHeight
    if (delta > 0) node.scrollTop += delta
    syncFadeTop(node)
  }, [listSignature, query, localVisible.length, remoteActive, syncFadeTop])

  const hasAny = entries.length > 0
  const countText = remoteActive
    ? remote!.loading ? '搜索中…' : `${remoteMatches.length} 条命中`
    : trimmedQuery && hasAny
      ? `${localVisible.length} / ${entries.length} 条`
      : `${entries.length} 条`

  const jumpHint = jumpDisabled ? '运行中不能跳到旧位置，等这一轮结束再试' : ''

  return (
    <>
      {/* 宽屏（≥1024px）下面板变成左侧栏：遮罩隐藏，正文让位（见 styles.css 宽屏层） */}
      <div className="sheet-scrim history-scrim" onClick={onClose} />
      <div className="sheet history-sheet" role="dialog" aria-modal="true" aria-label="历史对话" ref={sheetRef} tabIndex={-1}>
        <div className="history-head">
          <strong>历史对话</strong>
          <span className="history-count">{countText}</span>
          <button className="history-close" onClick={onClose} aria-label="关闭历史对话">×</button>
        </div>
        {hasAny && (
          <input
            className="history-search"
            type="search"
            value={query}
            onChange={(event) => setQuery(event.target.value)}
            placeholder="搜索整个会话…"
            aria-label="搜索历史消息"
            enterKeyHint="search"
          />
        )}
        {!hasAny ? (
          <div className="history-empty">这个会话还没有可跳转的消息。</div>
        ) : remoteActive ? (
          remote!.loading ? (
            <div className="history-empty">正在搜索整个会话…</div>
          ) : remote!.error ? (
            <div className="history-empty">全量搜索失败：{remote!.error}</div>
          ) : remoteMatches.length === 0 ? (
            <div className="history-empty">整个会话里没有匹配「{trimmedQuery}」的消息。</div>
          ) : (
            <>
              {jumpHint && <div className="history-hint">{jumpHint}</div>}
              <div
                className="history-list"
                ref={listRef}
                data-fade-top={fadeTop ? 'true' : undefined}
                onScroll={(event) => syncFadeTop(event.currentTarget)}
              >
                {remoteMatches.map((match) => (
                  <button
                    className="history-item history-hit"
                    key={`${match.lineIndex}:${match.messageId}`}
                    disabled={jumpDisabled || jumping}
                    onClick={() => onJumpMatch?.(match)}
                  >
                    <span className="history-item-meta">
                      <span className={`history-role ${match.role}`}>{SESSION_SEARCH_ROLE_LABELS[match.role]}</span>
                      {jumping && <span>正在定位…</span>}
                    </span>
                    <span className="history-preview">
                      {(() => {
                        const parts = splitSearchSnippet(match.snippet, match.matchStart, trimmedQuery.length)
                        return (
                          <>
                            {parts.before}
                            <mark className="history-hit-mark">{parts.hit}</mark>
                            {parts.after}
                          </>
                        )
                      })()}
                    </span>
                  </button>
                ))}
                <div className="history-hint">
                  搜索范围：整个会话 · 最多显示 {SESSION_SEARCH_LIMIT} 条
                </div>
              </div>
            </>
          )
        ) : localVisible.length === 0 ? (
          <div className="history-empty">没有匹配「{trimmedQuery}」的消息。</div>
        ) : (
          <div
            className="history-list"
            ref={listRef}
            data-fade-top={fadeTop ? 'true' : undefined}
            onScroll={(event) => syncFadeTop(event.currentTarget)}
          >
            {/* 加载入口在顶部：更早的消息在时间序里就在上面 */}
            <div className="history-list-top">
              {hasOlder ? (
                <button className="load-older-btn" disabled={loadingOlder} onClick={onLoadOlder}>
                  {loadingOlder ? '加载中…' : '加载更早的消息'}
                </button>
              ) : (
                <span className="history-end">已经到最早一条了</span>
              )}
            </div>
            {localVisible.map((entry) => (
              <button
                className="history-item"
                key={entry.turnIndex}
                onClick={() => onJump(entry.turnIndex)}
              >
                <span className="history-item-meta">
                  <span className={`history-role ${entry.role}`}>{entry.role === 'user' ? '你' : 'Profer'}</span>
                  {entry.clock && <span>{entry.clock}</span>}
                </span>
                <span className="history-preview">{entry.preview}</span>
              </button>
            ))}
          </div>
        )}
      </div>
    </>
  )
}