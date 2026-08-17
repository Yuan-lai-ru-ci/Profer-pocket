/**
 * PocketNotificationSettings — 平板「通知」设置页
 *
 * 两个开关：
 *  - 「完成提醒音」：Agent 回合在电脑端完成时，若本设备正打开着其他会话（或未在查看该会话），
 *    播放短促提示音。Web Audio API 合成提示音（零插件依赖，浏览器与 Capacitor WebView 通用）。
 *  - 「后台消息通知」：开启后启动原生前台服务保活，切到其他应用/熄屏仍保持与电脑的消息连接，
 *    权限确认/提问/计划审批/运行完成等重要事件走安卓系统通知渠道提醒。
 *    Android 13+ 首次开启会请求通知权限；被拒也继续（退化为仅保活、无系统通知）。
 *
 * 说明：浏览器环境（非 Capacitor）下 keepalive 调用为安全 no-op，开关仅持久化本地状态。
 */

import * as React from 'react'
import { useAtom } from 'jotai'
import { Bell } from 'lucide-react'
import { toast } from 'sonner'
import { SettingsSection, SettingsCard, SettingsToggle } from './primitives'
import { pocketNotifyCompleteAtom, pocketBackgroundMessagingAtom } from '@/atoms/pocket-settings'
import { startPocketKeepalive, stopPocketKeepalive, requestPocketNotificationPermission, normalizeWsUrl, getPocketKeepaliveStatus } from '@/lib/pocket-keepalive'

/** 是否原生 Capacitor App 环境（浏览器联调时为 false） */
function isNativeApp(): boolean {
  return typeof window !== 'undefined' && !!((window as unknown as { Capacitor?: { isNativePlatform?: () => boolean } }).Capacitor?.isNativePlatform?.())
}

export function PocketNotificationSettings(): React.ReactElement {
  const [notifyComplete, setNotifyComplete] = useAtom(pocketNotifyCompleteAtom)
  const [backgroundMessaging, setBackgroundMessaging] = useAtom(pocketBackgroundMessagingAtom)

  /** 后台消息通知开关：开启→请求权限（被拒也继续，退化保活）→启动原生服务；关闭→停止服务 */
  const handleBackgroundToggle = React.useCallback(async (next: boolean): Promise<void> => {
    if (next) {
      // url/token 取本地持久化绑定值；未连接时开关可点但提示先连接，不启动服务
      const token = localStorage.getItem('profer-remote-token') || ''
      const url = normalizeWsUrl(localStorage.getItem('profer-remote-server') || '')
      if (!url || !token) {
        toast.warning('请先在「连接」页连接电脑端，再开启后台消息通知')
        return
      }
      // Android 13+ 首次开启弹系统通知权限框；被拒不阻断，服务照常启动（仅无系统通知）
      const permission = await requestPocketNotificationPermission()
      if (isNativeApp() && permission === 'denied') {
        toast.warning('通知权限被拒绝，仍将保持后台连接，但不推送系统通知')
      }
      await startPocketKeepalive(url, token)
      setBackgroundMessaging(true)
    } else {
      await stopPocketKeepalive()
      setBackgroundMessaging(false)
    }
  }, [setBackgroundMessaging])

  // 后台通道诊断（排查用）：原生环境下每 5s 读取连接状态/事件数/最近错误
  const [diag, setDiag] = React.useState<string | null>(null)
  React.useEffect(() => {
    if (!isNativeApp()) return
    let alive = true
    const refresh = async (): Promise<void> => {
      const s = await getPocketKeepaliveStatus()
      if (alive) setDiag(s)
    }
    void refresh()
    const timer = setInterval(() => void refresh(), 5000)
    return () => { alive = false; clearInterval(timer) }
  }, [])

  return (
    <div className="space-y-6">
      <SettingsSection title="通知" description="Agent 在电脑端运行时的提醒方式">
        <SettingsCard>
          <SettingsToggle
            label="完成提醒音"
            description="Agent 回合完成时播放短促提示音（仅在其他会话完成时提醒，不打扰正在查看的会话）"
            checked={notifyComplete}
            onCheckedChange={setNotifyComplete}
          />
        </SettingsCard>
        <SettingsCard divided={false}>
          <SettingsToggle
            label="后台消息通知"
            description="切到其他应用或熄屏后仍保持与电脑的消息连接，权限确认、提问、计划审批、运行完成等重要事件走系统通知提醒"
            checked={backgroundMessaging}
            onCheckedChange={(next) => void handleBackgroundToggle(next)}
          />
          {diag != null && (
            <div className="mt-2 px-3 pb-2.5 text-[11px] font-mono leading-4 text-muted-foreground/80">
              后台通道诊断：{diag}
            </div>
          )}
        </SettingsCard>
      </SettingsSection>

      <div className="flex items-start gap-2 rounded-lg bg-muted/50 px-3 py-2.5 text-xs leading-5 text-muted-foreground">
        <Bell className="mt-0.5 size-4 shrink-0" />
        <span>
          {backgroundMessaging
            ? '「后台消息通知」已开启：切换应用或熄屏后仍保持与电脑的消息连接，重要事件通过系统通知栏提醒，可关闭常驻通知与消息渠道在系统设置中单独管理。'
            : '「后台消息通知」未开启时，完成提醒音仅在本设备保持前台时生效；App 进入后台后系统会冻结网页进程并断开连接，无法收到完成事件。'}
        </span>
      </div>
    </div>
  )
}
