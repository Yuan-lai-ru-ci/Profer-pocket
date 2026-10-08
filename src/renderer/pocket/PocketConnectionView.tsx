import React from 'react'
import { Link } from 'lucide-react'

export interface PocketConnectionViewProps {
  tokenInput: string
  serverInput: string
  errorMessage?: string
  connection: 'idle' | 'connecting' | 'open' | 'reconnecting' | 'error' | 'unauthorized'
  hasStoredBinding: boolean
  storedServerUrl: string
  isNativeApp: boolean
  safeAreaClassName: string
  onTokenChange: (value: string) => void
  onServerChange: (value: string) => void
  onSubmit: () => void
  onRequestUnbind: () => void
}

export function PocketConnectionView({
  tokenInput,
  serverInput,
  errorMessage,
  connection,
  hasStoredBinding,
  storedServerUrl,
  isNativeApp,
  safeAreaClassName,
  onTokenChange,
  onServerChange,
  onSubmit,
  onRequestUnbind,
}: PocketConnectionViewProps): React.ReactElement {
  return (
    <div className={`pocket-login-shell flex h-full w-full items-center justify-center bg-background px-6 py-10 pb-[max(2.5rem,env(safe-area-inset-bottom))] text-foreground ${safeAreaClassName}`}>
      <div className="pocket-login-panel w-full max-w-sm space-y-6 rounded-3xl bg-card/80 p-6 shadow-2xl shadow-primary/5 backdrop-blur-xl sm:p-8">
        <div className="space-y-4">
          <div className="flex items-center gap-3">
            <div className="flex size-11 items-center justify-center rounded-2xl bg-primary text-lg font-semibold text-primary-foreground shadow-lg shadow-primary/20" aria-hidden>P</div>
            <div>
              <div className="text-xl font-semibold tracking-tight">Profer <span className="text-muted-foreground">Pocket</span></div>
              <div className="mt-0.5 text-xs text-muted-foreground">随时连接你的电脑工作台</div>
            </div>
          </div>
          <div className="rounded-2xl bg-muted/45 px-3.5 py-3 text-sm leading-6 text-muted-foreground">
            在电脑上以 <code className="rounded-md bg-background/70 px-1.5 py-0.5 font-mono text-xs text-foreground">--pocket</code> 启动 Profer，然后输入连接信息。
          </div>
        </div>
        <div className="space-y-3">
          <label className="block space-y-1.5 text-xs font-medium text-muted-foreground" htmlFor="pocket-server">
            服务器地址 <span className="font-normal opacity-70">{isNativeApp ? '（App 端必填）' : '（可留空自动发现）'}</span>
            <input
              id="pocket-server"
              value={serverInput}
              onChange={(event) => onServerChange(event.target.value)}
              placeholder="例如 http://192.168.1.10:7788"
              inputMode="url"
              autoCapitalize="none"
              autoCorrect="off"
              spellCheck={false}
              className="w-full rounded-xl border border-border/70 bg-background/75 px-3.5 py-3 text-sm font-normal outline-none transition placeholder:text-muted-foreground/55 focus:border-primary/60 focus:ring-2 focus:ring-primary/20"
            />
          </label>
          <label className="block space-y-1.5 text-xs font-medium text-muted-foreground" htmlFor="pocket-token">
            访问令牌
            <input
              id="pocket-token"
              type="password"
              value={tokenInput}
              onChange={(event) => onTokenChange(event.target.value)}
              placeholder="粘贴电脑端启动日志中的 Token"
              autoFocus
              autoComplete="off"
              autoCapitalize="none"
              autoCorrect="off"
              spellCheck={false}
              className="w-full rounded-xl border border-border/70 bg-background/75 px-3.5 py-3 text-sm font-normal outline-none transition placeholder:text-muted-foreground/55 focus:border-primary/60 focus:ring-2 focus:ring-primary/20"
            />
          </label>
        </div>
        {errorMessage && <div className="rounded-xl bg-destructive/10 px-3.5 py-2.5 text-sm leading-5 text-destructive" role="alert">{errorMessage}</div>}
        <button onClick={onSubmit} className="w-full rounded-xl bg-primary py-3.5 text-sm font-medium text-primary-foreground shadow-lg shadow-primary/15 transition active:scale-[0.98] disabled:opacity-60" disabled={connection === 'connecting'}>
          {connection === 'connecting' ? '正在连接…' : '连接到 Profer'}
        </button>
        {hasStoredBinding && (
          <div className="space-y-2">
            <div className="flex items-center justify-center gap-1.5 text-[12px] text-muted-foreground">
              <span className="size-1.5 shrink-0 rounded-full bg-emerald-500" aria-hidden />
              <span className="truncate">
                已绑定{storedServerUrl ? `：${storedServerUrl}` : isNativeApp ? '（缺少服务器地址）' : '（自动地址）'}
              </span>
            </div>
            <button
              type="button"
              onClick={onRequestUnbind}
              className="flex w-full items-center justify-center gap-1.5 rounded-lg border border-border py-2 text-[12px] text-foreground/70 transition-colors hover:border-destructive/40 hover:text-destructive"
            >
              <Link className="size-3.5" />
              解绑并重新连接
            </button>
          </div>
        )}
      </div>
    </div>
  )
}
