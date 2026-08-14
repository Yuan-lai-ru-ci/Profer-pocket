/**
 * useConnection — 连接管理 Hook（移动端独立客户端 apps/tablet-client）
 *
 * 封装 WsClient 实例化、心跳/假死检测（WsClient 内部已实现）、
 * visibilitychange 前后台重连、token/服务器地址持久化。
 *
 * 与桌面 tablet 版 main.tsx 里的 connect/unbind/前后台 effect 语义对齐，但提炼为
 * 可复用 Hook。移动端为纯指令转发瘦客户端，仍连电脑端 remote-service，WS 协议冻结。
 *
 * 依赖：
 *  - WsClient：脚手架子代理从桌面表格端搬运的 ws-client（本目录同层 ws-client.ts，协议冻结不改动）
 *  - 数据加载（list_channels / list_sessions / list_workspaces 等）由 Hook 内的 effect
 *    在连接就绪后拉取，写回 atoms；属于本轨道的“会话列表 refresh”职责。
 */

import { useCallback, useEffect, useRef } from 'react'
import { useAtom, useAtomValue, useSetAtom } from 'jotai'
// ws-client 落地位于 src/client/ws-client.ts（脚手架子代理统一搬运，协议冻结不改动）
import { WsClient, defaultWsUrl } from '@/client/ws-client'
import type { AgentWorkflowEvent, ChatWorkflowEvent } from '@/client/ws-client'
import {
  connectionStatusAtom,
  connectionNoticeAtom,
  tokenAtom,
  serverUrlAtom,
  hasBindingAtom,
  type ConnectionStatus,
} from '@/atoms/connection'
import {
  sessionsAtom,
  sessionsLoadedAtom,
  workspacesAtom,
  workspacesLoadedAtom,
  channelsAtom,
  channelsLoadedAtom,
  channelIdAtom,
  modelIdAtom,
  channelIdsAtom,
  type ChannelInfo,
} from '@/atoms/session'
import { storeToken, storeServerUrl, getStoredToken, getStoredServerUrl } from '@/lib/storage'
import type { AgentSessionMeta, AgentWorkspace } from '@profer/shared'

/** 服务器地址规范化：http(s)://host[:port] / ws(s)://host[:port][/ws] / host[:port] → ws(s)://host[:port]/ws */
function normalizeWsUrl(raw: string): string | null {
  const s = raw.trim().replace(/\/+$/, '')
  if (!s) return null
  if (/^https?:\/\//i.test(s)) {
    const proto = /^https:/i.test(s) ? 'wss:' : 'ws:'
    return `${proto}${s.replace(/^https?:/i, '')}/ws`
  }
  if (/^wss?:\/\//i.test(s)) {
    return s.endsWith('/ws') ? s : `${s}/ws`
  }
  return `ws://${s}/ws`
}

export interface UseConnectionOptions {
  /** Agent 事件回调（转发给上层 event 桥） */
  onAgentEvent?: (evt: AgentWorkflowEvent) => void
  /** Chat 事件回调 */
  onChatEvent?: (evt: ChatWorkflowEvent) => void
}

export interface UseConnectionResult {
  /** 当前连接状态 */
  status: ConnectionStatus
  /** 断线提示文案 */
  notice: string | undefined
  /** 是否已绑定 */
  hasBinding: boolean
  /** 主动连接（提交 token/服务器地址后调用）。token 与 server 同时持久化。 */
  connect: (token: string, server?: string) => void
  /** 断开并清理解绑（回到连接页） */
  disconnect: () => void
  /** 解绑：断开 + 清除本地 token/server 持久化 */
  unbind: () => void
  /** 手动重连（前台恢复时调用；连接仍 OPEN/CONNECTING 时内部 no-op） */
  reconnectNow: () => void
  /** 底层 WsClient 引用（供上层直接调用便捷方法） */
  client: WsClient | null
}

// 模块级共享 client 单例：无论 useConnection 被调用几次（App 与 ConnectionGate 各调一次），
// 都复用同一个 WsClient 实例，避免「双实例 + StrictMode 双重挂载」导致连接刚建立就被
// 另一个实例的 cleanup disconnect 掉（表现为「一直连接中」）。
let sharedClient: WsClient | null = null

// 模块级共享事件回调：确保无论哪个 hook 实例建连，都能带上最新的 agent/chat 事件处理器。
let sharedAgentEventHandler: ((evt: AgentWorkflowEvent) => void) | null = null
let sharedChatEventHandler: ((evt: ChatWorkflowEvent) => void) | null = null

export function useConnection(options: UseConnectionOptions = {}): UseConnectionResult {
  const { onAgentEvent, onChatEvent } = options

  // 用模块级单例替代 useRef，确保所有调用方共享同一连接。
  const clientRef = { current: sharedClient }

  const [status, setStatus] = useAtom(connectionStatusAtom)
  const [notice, setNotice] = useAtom(connectionNoticeAtom)
  const hasBinding = useAtomValue(hasBindingAtom)

  const setSessions = useSetAtom(sessionsAtom)
  const setSessionsLoaded = useSetAtom(sessionsLoadedAtom)
  const setWorkspaces = useSetAtom(workspacesAtom)
  const setWorkspacesLoaded = useSetAtom(workspacesLoadedAtom)
  const setChannels = useSetAtom(channelsAtom)
  const setChannelsLoaded = useSetAtom(channelsLoadedAtom)
  const setChannelId = useSetAtom(channelIdAtom)
  const setModelId = useSetAtom(modelIdAtom)
  const setChannelIds = useSetAtom(channelIdsAtom)

  // 保持 onAgentEvent/onChatEvent 最新引用，避免 connect 闭包捕获旧回调。
  // 同时用模块级共享引用，确保「无论哪个 hook 实例建连」，都能带上最新的 agent 事件回调
  // （否则 ConnectionGate 建连时没传 onAgentEvent，会导致 handleAgentEvent 不被转发）。
  const agentEventRef = useRef(onAgentEvent)
  const chatEventRef = useRef(onChatEvent)
  agentEventRef.current = onAgentEvent
  chatEventRef.current = onChatEvent
  sharedAgentEventHandler = onAgentEvent ?? null
  sharedChatEventHandler = onChatEvent ?? null

  // ===== 数据加载（连接就绪后填充 atoms） =====
  const loadChannels = useCallback(
    async (client: WsClient) => {
      try {
        const data = (await client.listChannels()) as ChannelInfo[]
        const ch = (Array.isArray(data) ? data : []).map((c) => ({
          ...c,
          // WS 返回的都是可切换渠道：补全 enabled 语义，否则模型选择器的
          // hasAvailableModel 判定（channel.enabled && models[].enabled）恒为 false。
          enabled: true,
          models: (c.models || []).map((m) => ({ ...m, enabled: true })),
        }))
        setChannels(ch)
        setChannelsLoaded(true)
        // 全量纳入所有已启用渠道（runtime/protocol 兼容性由 UI 层处理）
        setChannelIds(ch.map((c) => c.id))
        if (ch.length > 0) {
          // 默认渠道取第一个可用；避免“请先设置 Agent 供应商”的提示出现在平板上
          setChannelId((prev) => prev || ch[0]!.id)
          setModelId((prev) => prev || ch[0]!.models?.[0]?.id || '')
        }
      } catch (e) {
        console.error('[tablet-client] 拉取渠道失败', e)
      }
    },
    [setChannels, setChannelsLoaded, setChannelIds, setChannelId, setModelId],
  )

  const loadSessions = useCallback(
    async (client: WsClient) => {
      const clientRefLocal = client
      try {
        const data = (await clientRefLocal.listSessions()) as AgentSessionMeta[]
        if (!Array.isArray(data)) return

        // 字段兜底：旧版服务端缺 createdAt/updatedAt 时补默认值
        const normalized = data.map((s) => ({
          ...s,
          createdAt: s.createdAt ?? 0,
          updatedAt: s.updatedAt ?? s.createdAt ?? 0,
          pinned: s.pinned ?? false,
          archived: s.archived ?? false,
          draft: s.draft ?? false,
        }))

        // 工作区：优先服务端 list_workspaces（带真实项目名），失败回退从会话归纳
        let wsList: AgentWorkspace[] = []
        try {
          const raw = (await clientRefLocal.listWorkspaces()) as AgentWorkspace[] | undefined
          if (Array.isArray(raw)) wsList = raw
        } catch {
          /* 服务端不支持则走会话归纳回退 */
        }

        setSessions(normalized)
        setSessionsLoaded(true)

        const realWorkspaces = new Map<string, AgentWorkspace>()
        for (const w of wsList) realWorkspaces.set(w.id, w)

        const workspaceIds = [
          ...new Set(normalized.map((s) => s.workspaceId).filter((id): id is string => Boolean(id))),
        ]
        const ids =
          workspaceIds.length > 0
            ? workspaceIds
            : realWorkspaces.size > 0
              ? [...realWorkspaces.keys()]
              : ['default']

        const workspaces = ids
          .map((id) => {
            const real = realWorkspaces.get(id)
            if (real) return real
            if (id === 'default')
              return { id, name: '默认工作区', slug: 'default', type: 'personal' as const, createdAt: 0, updatedAt: 0 }
            return null
          })
          .filter((w): w is AgentWorkspace => w != null)

        const fallback: AgentWorkspace =
          workspaces[0] ?? { id: 'default', name: '默认工作区', slug: 'default', type: 'personal', createdAt: 0, updatedAt: 0 }
        setWorkspaces(workspaces.length > 0 ? workspaces : [fallback])
        setWorkspacesLoaded(true)
      } catch (e) {
        console.error('[tablet-client] 拉取会话失败', e)
      }
    },
    [setSessions, setSessionsLoaded, setWorkspaces, setWorkspacesLoaded],
  )

  // ===== 主动连接 =====
  const connect = useCallback(
    (token: string, server?: string) => {
      const client = clientRef.current
      if (client) client.disconnect()

      const url = normalizeWsUrl(server ?? getStoredServerUrl()) ?? defaultWsUrl()
      const ws = new WsClient({
        url,
        token,
        onStatusChange: (s, info) => {
          if (s === 'open') {
            setStatus('open')
            setNotice(undefined)
            void loadChannels(ws)
            void loadSessions(ws)
          } else if (s === 'unauthorized') {
            // token 被服务端拒绝（4001）：WsClient 已停止自动重连，停留登录页
            setStatus('unauthorized')
            setNotice('访问令牌无效或已失效，请查看电脑端启动日志中的 Token 后重新输入')
          } else if (s === 'closed') {
            // 断线（后台冻结/网络变化）：保持主界面，横幅提示自动重连，不回登录页
            setStatus('reconnecting')
            setNotice('连接已断开，正在重连…')
          } else if (s === 'error') {
            setStatus('error')
            setNotice('连接失败，正在自动重连…')
          } else {
            setStatus('connecting')
          }
        },
        onAgentEvent: (evt) => {
          // 优先用模块级共享 handler（App 注册的 handleAgentEvent），确保无论谁建连都不丢失
          const h = sharedAgentEventHandler ?? agentEventRef.current
          h?.(evt)
        },
        onChatEvent: (evt) => {
          const h = sharedChatEventHandler ?? chatEventRef.current
          h?.(evt)
        },
      })

      clientRef.current = ws
      sharedClient = ws
      ws.connect()
    },
    [loadChannels, loadSessions, setStatus, setNotice],
  )

  /** 提交 token（持久化 token/server，再发起连接） */
  const submit = useCallback(
    (token: string, server?: string) => {
      const t = token.trim()
      if (!t) {
        setNotice('请输入访问令牌')
        return
      }
      storeToken(t)
      storeServerUrl(server ?? '')
      setNotice(undefined)
      setStatus('connecting')
      // 延迟到下一个宏任务，确保状态先落定再建连
      setTimeout(() => connect(t, server), 0)
    },
    [connect, setNotice, setStatus],
  )

  /** 断开连接（保留绑定信息，不清理持久化） */
  const disconnect = useCallback(() => {
    clientRef.current?.disconnect()
    clientRef.current = null
    sharedClient = null
    setStatus('idle')
  }, [setStatus])

  /** 解绑：断开 + 清除本地 token/server 持久化 + 清空会话态 */
  const unbind = useCallback(() => {
    clientRef.current?.disconnect()
    clientRef.current = null
    sharedClient = null
    storeToken('')
    storeServerUrl('')
    setSessions([])
    setWorkspaces([])
    setStatus('idle')
    setNotice(undefined)
  }, [setStatus, setNotice, setSessions, setWorkspaces])

  /** 手动/前台重连：连接仍 OPEN 或 CONNECTING 时内部 no-op */
  const reconnectNow = useCallback(() => {
    clientRef.current?.reconnectNow()
  }, [])

  // ===== 初次挂载：已绑定则自动连接（登录状态留存，对齐桌面 tablet main.tsx） =====
  // 有持久化 token 就自动 connect，刷新/冷启动不用重新输 token。
  // 幂等保护：用模块级 sharedClient 是否存在判断是否已在建连，避免 StrictMode 双挂载重复建连；
  // 不在 cleanup 里 disconnect（否则 StrictMode 第一次挂载的 cleanup 会把连接断开，复现「一直连接中」）。
  useEffect(() => {
    const token = getStoredToken()
    if (!token) return
    // 已有共享连接在建连/已连，跳过（StrictMode 第二次挂载命中此分支）
    if (sharedClient) return
    setStatus('connecting')
    const t = setTimeout(() => connect(token, getStoredServerUrl()), 0)
    // 仅清理定时器；不 disconnect，避免 StrictMode cleanup 误断共享连接
    return () => {
      clearTimeout(t)
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  // ===== 前后台切换：恢复前台时立即检测/重连 =====
  // Android 后台可能冻结 WebView / 网络休眠导致 WS 失效；恢复前台主动检查，
  // 已断则立即重连（不等 2s 定时器），仍连则 reconnectNow 内部 no-op，无感。
  useEffect(() => {
    const onVisibility = (): void => {
      if (document.hidden) return
      clientRef.current?.reconnectNow()
    }
    document.addEventListener('visibilitychange', onVisibility)

    // Capacitor App 原生 resume 事件兜底（若已安装 @capacitor/app）
    const appPlugin = (
      globalThis as unknown as {
        Capacitor?: {
          Plugins?: {
            App?: { addListener: (event: string, cb: () => void) => Promise<{ remove: () => void }> }
          }
        }
      }
    )?.Capacitor?.Plugins?.App
    let resumeHandle: { remove: () => void } | null = null
    if (appPlugin?.addListener) {
      void appPlugin.addListener('resume', () => clientRef.current?.reconnectNow()).then((h) => {
        resumeHandle = h
      })
    }

    return () => {
      document.removeEventListener('visibilitychange', onVisibility)
      resumeHandle?.remove()
    }
  }, [])

  return {
    status,
    notice,
    hasBinding,
    connect: submit,
    disconnect,
    unbind,
    reconnectNow,
    client: clientRef.current,
  }
}
