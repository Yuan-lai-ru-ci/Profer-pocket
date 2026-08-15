/**
 * ConnectionGate.tsx — 连接页（移动端瘦客户端）
 *
 * 职责：token + 服务器地址输入 + connect 提交；未连接（含自动连接/重连中）始终显示
 * 连接页，连接成功（open）才进入主界面。
 *
 * 状态机（交互逻辑，2026-08-15 对齐用户需求）：
 *  - idle / error / unauthorized：连接页 + 输入框解锁 + 「连接」按钮（token 为空时锁定）
 *  - connecting / reconnecting：连接页 + 「正在连接」字样 + 输入框锁定 + 「取消连接」按钮
 *  - open：主界面
 *
 * 用户可随时打断自动连接：取消后回到待输入态重新填 token/服务器地址。
 */

import * as React from 'react'
import { useAtomValue } from 'jotai'
import { tokenAtom, serverUrlAtom } from '@/atoms/connection'
import { useConnection } from '@/hooks/useConnection'
import { getStoredToken, getStoredServerUrl } from '@/lib/storage'

export interface ConnectionGateProps {
  children: React.ReactNode
}

/** 把 ws://192.168.1.10:7788/ws 转成可读的 192.168.1.10:7788，连接中展示实际连接地址 */
function friendlyServerUrl(url: string): string {
  if (!url) return ''
  return url.replace(/^wss?:\/\//i, '').replace(/^https?:\/\//i, '').replace(/\/ws$/, '')
}

export function ConnectionGate({ children }: ConnectionGateProps): React.ReactElement {
  const { status, notice, connect, disconnect, connectedUrl, lastCloseInfo } = useConnection()

  const storedToken = useAtomValue(tokenAtom)
  const storedServer = useAtomValue(serverUrlAtom)

  const [tokenInput, setTokenInput] = React.useState(getStoredToken())
  const [serverInput, setServerInput] = React.useState(getStoredServerUrl())

  // 已绑定 token 持久化到 atom 变化时同步输入框（解绑后清空）
  React.useEffect(() => {
    setTokenInput(storedToken)
    setServerInput(storedServer)
  }, [storedToken, storedServer])

  const submit = (): void => {
    connect(tokenInput.trim(), serverInput.trim() || undefined)
  }

  // 取消连接：若用户未填过服务器地址，把自动连接期间展示的实际地址固化到输入框，
  // 避免取消后「正在连接」时自动填的 IP 被清空（用户自己填的 serverInput 本就保留）。
  const handleCancel = (): void => {
    if (!serverInput.trim() && connectedUrl) {
      setServerInput(friendlyServerUrl(connectedUrl))
    }
    disconnect()
  }

  // 未连接（idle/unauthorized/connecting/reconnecting/error）始终显示连接页；
  // 只有 open 才进入主界面。自动连接/重连中也能在连接页取消，避免被无限重连锁死。
  const showGate = status !== 'open'

  if (!showGate) {
    return <>{children}</>
  }

  // 连接中：输入框锁定 + 「取消连接」按钮 + 「正在连接」字样
  const busy = status === 'connecting' || status === 'reconnecting'

  return (
    <div className="flex h-full items-center justify-center bg-background p-4">
      <div className="flex w-full max-w-[420px] flex-col gap-4 rounded-2xl border border-border bg-card p-6 shadow-sm">
        <h1 className="text-xl font-bold">Profer 平板端</h1>
        <p className="-mt-2 text-[13px] text-muted-foreground">连接电脑端 Profer，同步使用 Agent 会话</p>

        <label className="flex flex-col gap-1.5">
          <span className="text-[13px] text-muted-foreground">访问令牌</span>
          <input
            type="password"
            className="rounded-lg border border-border bg-muted px-3.5 py-2.5 text-sm text-foreground outline-none focus:border-ring focus:ring-1 focus:ring-ring/40 disabled:opacity-60"
            value={tokenInput}
            placeholder="粘贴电脑端启动日志中的 Token"
            onChange={(e) => setTokenInput(e.target.value)}
            disabled={busy}
          />
        </label>

        <label className="flex flex-col gap-1.5">
          <span className="text-[13px] text-muted-foreground">服务器地址（可选）</span>
          <input
            type="text"
            className="rounded-lg border border-border bg-muted px-3.5 py-2.5 text-sm text-foreground outline-none focus:border-ring focus:ring-1 focus:ring-ring/40 disabled:opacity-60"
            value={serverInput || (busy ? friendlyServerUrl(connectedUrl) : '')}
            placeholder="如 192.168.1.10:7788，留空自动推导"
            onChange={(e) => setServerInput(e.target.value)}
            disabled={busy}
          />
        </label>

        {status === 'unauthorized' && (
          <div className="text-[13px] text-destructive">{notice ?? '访问令牌无效或已失效'}</div>
        )}
        {busy && (
          <div className="text-[13px] text-muted-foreground">正在连接…</div>
        )}
        {!busy && status === 'idle' && (
          <div className="text-[13px] text-muted-foreground">请输入访问令牌和服务器地址后连接</div>
        )}
        {!busy && status === 'error' && notice && (
          <div className="text-[13px] text-muted-foreground">{notice}</div>
        )}
        {lastCloseInfo && (
          <div className="text-[12px] text-muted-foreground/70">上次断开原因：{lastCloseInfo}</div>
        )}

        <button
          type="button"
          className="rounded-lg bg-primary px-3 py-3 text-[15px] font-semibold text-primary-foreground transition-colors hover:bg-primary/90 disabled:opacity-50"
          onClick={busy ? handleCancel : submit}
          disabled={!busy && !tokenInput.trim()}
        >
          {busy ? '取消连接' : '连接'}
        </button>
      </div>
    </div>
  )
}
