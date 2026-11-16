/**
 * 会话设置菜单（挂在标题下面那一行）：目标 / 权限模式 / 模型 / 推理强度 / 预设 / 压缩。
 *
 * 一个菜单放这么多，是因为它们都是「改这个会话怎么跑」的开关，语义相邻；
 * 手机上多一层二级页面就多一次往返。分区用细线与小标题隔开。
 *
 * 运行中一律禁用：服务端对 model / effort 的更新都有「运行中拒绝」的校验，
 * 与其让用户点完吃一个错误横幅，不如直接说明。
 */
import * as React from 'react'
import {
  groupModelsByChannel,
  modelLabelFor,
  type ModelOption,
  type StrengthState,
} from './model-selection'
import { presetBadges, type WebPreset } from './preset-model'
import { useOverlay } from './overlay'
import { formatTokens, type ContextUsageInfo } from './context-usage'

export interface SessionSettingsMenuProps {
  models: ModelOption[]
  currentModelId: string | undefined
  strength: StrengthState
  /** 当前工作区可选的预设（已过滤明确停用项） */
  presets: WebPreset[]
  currentPresetId: string | undefined
  /** 会话正在跑：模型与档位都不接受修改 */
  running: boolean
  busy: boolean
  /** 权限模式（原顶栏「自动」按钮，收敛进本菜单保持顶栏干净） */
  permissionMode: string
  permissionModes: ReadonlyArray<{ mode: string; label: string; hint: string }>
  /** 目标（原顶栏左上的「＋」）也收敛于此：低频动作不占常显按钮 */
  hasGoal: boolean
  onOpenGoal: () => void
  /** 压缩上下文（原状态条按钮）收敛于此 */
  onCompact: () => void
  compacting: boolean
  compactDisabled: boolean
  /** 上下文占用：写在压缩项里，让顶栏那个百分比有明确所指 */
  contextUsage?: ContextUsageInfo
  /** 由顶栏百分比点进来：打开后把压缩项滚进视野并短暂高亮 */
  focusCompact?: boolean
  onPickModel: (option: ModelOption) => void
  onPickStrength: (value: string) => void
  onPickPreset: (presetId: string) => void
  onPickPermissionMode: (mode: string) => void
  onManagePresets: () => void
  onClose: () => void
}

export function SessionSettingsMenu({
  models, currentModelId, strength, presets, currentPresetId,
  running, busy, permissionMode, permissionModes, hasGoal, onOpenGoal, onCompact, compacting, compactDisabled,
  contextUsage, focusCompact, onPickModel, onPickStrength, onPickPreset, onPickPermissionMode, onManagePresets, onClose,
}: SessionSettingsMenuProps): React.ReactElement {
  const groups = groupModelsByChannel(models)
  const singleChannel = groups.length <= 1
  const locked = running || busy
  // 覆盖层的键盘行为统一走 overlay.ts：Esc 关闭 + 打开即聚焦
  const overlayRef = useOverlay(onClose)
  /** 菜单是 overflow-y 容器，压缩项在最底部；从顶栏百分比进来时主动滚过去并高亮一下 */
  const compactRef = React.useRef<HTMLButtonElement | null>(null)
  React.useEffect(() => {
    if (!focusCompact) return
    const node = compactRef.current
    if (!node) return
    node.scrollIntoView({ block: 'center' })
    node.focus({ preventScroll: true })
  }, [focusCompact])

  return (
    <>
      <div className="menu-scrim" onClick={onClose} />
      <div className="menu wide" role="menu" aria-label="模型、推理强度与预设" ref={overlayRef} tabIndex={-1}>
        {running && <div className="menu-note">运行中不能切换，等这轮结束再改</div>}

        <button className="menu-item" onClick={onOpenGoal}>
          <span className="menu-item-text">
            <span className="menu-item-label">{hasGoal ? '编辑目标' : '设定目标'}</span>
            <span className="menu-item-hint">{hasGoal ? '改目标、契约与预算' : '让 Profer 持续多轮执行直到完成'}</span>
          </span>
        </button>

        <div className="menu-sep" />
        <div className="menu-label">权限模式</div>
        {permissionModes.map((entry) => (
          <button
            key={entry.mode}
            className={`menu-item ${entry.mode === permissionMode ? 'active' : ''}`}
            role="menuitemradio"
            aria-checked={entry.mode === permissionMode}
            onClick={() => onPickPermissionMode(entry.mode)}
          >
            <span className="menu-item-text">
              <span className="menu-item-label">{entry.label}</span>
              <span className="menu-item-hint">{entry.hint}</span>
            </span>
            {entry.mode === permissionMode && <span className="menu-check" aria-hidden="true">✓</span>}
          </button>
        ))}

        <div className="menu-sep" />
        <div className="menu-label">模型</div>
        {groups.length === 0 && <div className="menu-note">还没有取到可用模型</div>}
        {groups.map((group) => (
          <React.Fragment key={group.channelId}>
            {/* 只有一个渠道时不加分组标题，省一行也少一层噪音 */}
            {!singleChannel && <div className="menu-group">{group.channelName || group.channelId}</div>}
            {group.models.map((option) => {
              const active = option.modelId === currentModelId
              // 倍率是唯一值得占一行的附加信息（决定这条模型贵不贵）
              const hint = option.multiplier ? `${option.multiplier}x` : ''
              return (
                <button
                  key={`${option.channelId}:${option.modelId}`}
                  className={`menu-item ${active ? 'active' : ''}`}
                  role="menuitemradio"
                  aria-checked={active}
                  disabled={locked}
                  onClick={() => onPickModel(option)}
                >
                  <span className="menu-item-text">
                    <span className="menu-item-label">{option.name}</span>
                    {hint && <span className="menu-item-hint">{hint}</span>}
                  </span>
                  {active && <span className="menu-check" aria-hidden="true">✓</span>}
                </button>
              )
            })}
          </React.Fragment>
        ))}

        <div className="menu-sep" />
        <div className="menu-label">推理强度</div>
        {strength.options.map((option) => {
          const active = option.value === strength.value
          return (
            <button
              key={option.value}
              className={`menu-item ${active ? 'active' : ''}`}
              role="menuitemradio"
              aria-checked={active}
              disabled={locked}
              onClick={() => onPickStrength(option.value)}
            >
              <span className="menu-item-text">
                <span className="menu-item-label">{option.label}</span>
                {option.hint && <span className="menu-item-hint">{option.hint}</span>}
              </span>
              {active && <span className="menu-check" aria-hidden="true">✓</span>}
            </button>
          )
        })}

        <div className="menu-sep" />
        <div className="menu-label">预设（岗位）</div>
        {presets.length === 0 && <div className="menu-note">当前工作区暂无可选预设</div>}
        {presets.map((preset) => {
          const active = preset.id === currentPresetId
          const badges = presetBadges(preset)
          return (
            <button
              key={`${preset.scope}:${preset.id}`}
              className={`menu-item ${active ? 'active' : ''}`}
              role="menuitemradio"
              aria-checked={active}
              disabled={locked}
              onClick={() => onPickPreset(preset.id)}
            >
              <span className="menu-item-text">
                <span className="menu-item-label">{preset.name}</span>
                <span className="menu-item-hint">
                  {[preset.description, ...badges].filter(Boolean).join(' · ')}
                </span>
              </span>
              {active && <span className="menu-check" aria-hidden="true">✓</span>}
            </button>
          )
        })}
        <button className="menu-item" onClick={onManagePresets}>
          <span className="menu-item-text">
            <span className="menu-item-label">管理预设…</span>
            <span className="menu-item-hint">新建 / 复制 / 编辑 / 设为默认</span>
          </span>
        </button>

        <div className="menu-sep" />
        <button
          ref={compactRef}
          className={`menu-item${focusCompact ? ' compact-focus' : ''}`}
          disabled={compactDisabled}
          onClick={onCompact}
          title="发送 /compact：把历史压缩成摘要，释放上下文窗口"
        >
          <span className="menu-item-text">
            <span className="menu-item-label">
              {compacting ? '压缩中…' : '压缩上下文'}
              {contextUsage?.percent != null && (
                <span className={`compact-usage${contextUsage.warning ? ' warn' : ''}`}>
                  {contextUsage.percent}%
                </span>
              )}
            </span>
            <span className="menu-item-hint">
              {contextUsage?.percent != null
                ? `已用 ${formatTokens(contextUsage.used)} / ${formatTokens(contextUsage.window)} tokens（${contextUsage.percent}%），压缩可释放`
                : '把历史压缩成摘要，释放上下文窗口'}
            </span>
          </span>
        </button>
      </div>
    </>
  )
}

/** 顶栏那颗 chip 的文案：`预设 · Sonnet 4.5 · 高`；会话没绑预设时提醒先选。 */
export function describeSessionModel(
  currentModelId: string | undefined,
  models: ModelOption[],
  strength: StrengthState,
  preset?: WebPreset | null,
  presetRequired = false,
): string {
  const parts: string[] = []
  if (preset?.name) parts.push(preset.name)
  else if (presetRequired) parts.push('选择预设')
  const name = modelLabelFor(currentModelId, models)
  if (name) parts.push(name)
  if (strength.label) parts.push(strength.label)
  return parts.length > 0 ? parts.join(' · ') : '设置会话'
}
