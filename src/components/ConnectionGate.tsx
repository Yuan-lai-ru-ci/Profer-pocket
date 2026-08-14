/**
 * ConnectionGate.tsx — 连接页（移动端瘦客户端）
 *
 * 职责：token + 服务器地址输入 + connect 提交；未绑定/未连接时显示连接页，
 * 已连接时显示主界面（由 useConnection() 的 status 驱动）。
 *
 * 状态机（对齐 desktop tablet main.tsx 的 connection state）：
 *  - idle / unauthorized：连接页（可输入 token/服务器）
 *  - connecting：连接页 + loading
 *  - open：主界面
 *  - reconnecting / error：已绑定时保持主界面，横幅提示自动重连（这里透传 notice）
 */

import * as React from 'react'
import { useAtomValue } from 'jotai'
import { tokenAtom, serverUrlAtom } from '@/atoms/connection'
import { useConnection } from '@/hooks/useConnection'
import { getStoredToken, getStoredServerUrl } from '@/lib/storage'

export interface ConnectionGateProps {
  children: React.ReactNode
}

export function ConnectionGate({ children }: ConnectionGateProps): React.ReactElement {
  const { status, notice, connect } = useConnection()

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

  // 连接页：idle / unauthorized / connecting（连接未就绪前始终显示连接页 + loading）
  // 只有 open 才进入主界面。不要用「connecting && !tokenInput」这种半中间态判定，
  // 否则用户点连接后 status 变 connecting、tokenInput 有值时，连接页会闪退到主界面空态。
  const showGate = status === 'idle' || status === 'unauthorized' || status === 'connecting' || status === 'error'

  if (!showGate) {
    // 已绑定 / 已连接：主界面
    return <>{children}</>
  }

  const busy = status === 'connecting'

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
            value={serverInput}
            placeholder="如 192.168.1.10:7788，留空自动推导"
            onChange={(e) => setServerInput(e.target.value)}
            disabled={busy}
          />
        </label>

        {notice && status !== 'unauthorized' && (
          <div className="text-[13px] text-muted-foreground">{notice}</div>
        )}
        {status === 'unauthorized' && (
          <div className="text-[13px] text-destructive">{notice ?? '访问令牌无效或已失效'}</div>
        )}

        <button
          type="button"
          className="rounded-lg bg-primary px-3 py-3 text-[15px] font-semibold text-primary-foreground transition-colors hover:bg-primary/90 disabled:opacity-50"
          onClick={submit}
          disabled={busy || !tokenInput.trim()}
        >
          {busy ? '连接中…' : '连接'}
        </button>
      </div>
    </div>
  )
}
