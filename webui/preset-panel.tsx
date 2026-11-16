/**
 * 预设（岗位）管理面板。
 *
 * 桌面端的 `AgentPresetSettings` 有近两千行（导入导出、Skill/MCP 白名单、跨工作区作用域），
 * 手机上照搬只会得到一张没人愿意用的长表单。这里保留日常会用的那部分：
 * 应用 / 新建 / 复制 / 改名与描述 / 提示段落 / 强度与权限覆盖 / 工具组开关 / 设为默认 / 删除。
 * 技能与 MCP 白名单需要另外两份目录数据，明确提示去电脑端改；不传这些字段时服务端保持原值，
 * 所以在手机上保存不会把它们清空。
 */
import * as React from 'react'
import {
  TOOL_GROUPS,
  buildPresetCreate,
  buildPresetPatch,
  presetBadges,
  presetFormFrom,
  type PresetForm,
  type PresetPatch,
  type WebPreset,
} from './preset-model'
import { useOverlay } from './overlay'
import type { ModelOption } from './model-selection'

type View = { kind: 'list' } | { kind: 'edit'; preset: WebPreset } | { kind: 'new'; preset?: WebPreset }

export interface PresetPanelProps {
  presets: WebPreset[]
  currentId?: string
  defaultPresetId?: string
  models: ModelOption[]
  busy: boolean
  onClose: () => void
  onApply: (presetId: string) => void
  /** id 为 null 表示新建；payload 已经过前端校验。返回 false 时保留表单，让用户能改完重试 */
  onSave: (id: string | null, payload: PresetPatch | Record<string, unknown>) => Promise<boolean>
  /** 返回复制出来的新预设，便于直接打开编辑 */
  onCopy: (fromId: string) => Promise<WebPreset | null>
  onDelete: (presetId: string) => Promise<boolean>
  onSetDefault: (presetId: string) => void
}

const EFFORT_OPTIONS = [
  { value: '', label: '跟随全局' },
  { value: 'low', label: '低' }, { value: 'medium', label: '中' },
  { value: 'high', label: '高' }, { value: 'max', label: '最大' },
]
const PERMISSION_OPTIONS = [
  { value: '', label: '跟随全局' },
  { value: 'auto', label: '自动审批' },
  { value: 'bypassPermissions', label: '完全自动' },
  { value: 'plan', label: '计划模式' },
]

function Editor({ view, models, busy, defaultPresetId, onBack, onSave, onDelete, onSetDefault }: {
  view: Extract<View, { kind: 'edit' | 'new' }>
  models: ModelOption[]
  busy: boolean
  defaultPresetId?: string
  onBack: () => void
  onSave: (id: string | null, payload: PresetPatch | Record<string, unknown>) => Promise<boolean>
  onDelete: () => void
  onSetDefault: (presetId: string) => void
}): React.ReactElement {
  const editing = view.kind === 'edit' ? view.preset : undefined
  const [form, setForm] = React.useState<PresetForm>(() => presetFormFrom(view.preset ?? null))
  const [error, setError] = React.useState('')
  const set = <K extends keyof PresetForm>(key: K, value: PresetForm[K]): void => {
    setForm((previous) => ({ ...previous, [key]: value }))
  }

  const save = async (): Promise<void> => {
    const result = editing ? buildPresetPatch(form) : buildPresetCreate(form)
    // 两个 builder 的返回形状不同（patch / input），用显式收窄取其一
    const payload: PresetPatch | Record<string, unknown> | undefined = 'patch' in result
      ? (result as { patch?: PresetPatch }).patch
      : (result as { input?: Record<string, unknown> }).input
    if (result.error || !payload) {
      setError(result.error ?? '表单不完整')
      return
    }
    setError('')
    const ok = await onSave(editing?.id ?? null, payload)
    if (ok) onBack()
  }

  const toggleGroup = (groupId: string): void => {
    const next = form.disabledToolGroups.includes(groupId)
      ? form.disabledToolGroups.filter((item) => item !== groupId)
      : [...form.disabledToolGroups, groupId]
    set('disabledToolGroups', next)
  }

  return (
    <>
      <div className="preset-head">
        <button className="preset-back" onClick={onBack} aria-label="返回预设列表">‹ 返回</button>
        <strong>{editing ? '编辑预设' : '新建预设'}</strong>
      </div>
      <div className="preset-form">
        <label className="sheet-field">
          <span>名称</span>
          <input value={form.name} maxLength={80} disabled={busy} onChange={(event) => set('name', event.target.value)} />
        </label>
        <label className="sheet-field">
          <span>一句话描述</span>
          <input
            value={form.description} maxLength={240} disabled={busy}
            onChange={(event) => set('description', event.target.value)}
          />
        </label>
        <label className="sheet-field">
          <span>子代理模型</span>
          <select value={form.modelId} disabled={busy} onChange={(event) => set('modelId', event.target.value)}>
            <option value="">不绑定（沿用父会话模型）</option>
            {models.map((option) => (
              <option key={`${option.channelId}:${option.modelId}`} value={option.modelId}>{option.name}</option>
            ))}
          </select>
        </label>
        <div className="sheet-grid">
          <label className="sheet-field">
            <span>推理强度</span>
            <select value={form.effort} disabled={busy} onChange={(event) => set('effort', event.target.value)}>
              {EFFORT_OPTIONS.map((option) => <option key={option.value} value={option.value}>{option.label}</option>)}
            </select>
          </label>
          <label className="sheet-field">
            <span>权限模式</span>
            <select value={form.permissionMode} disabled={busy} onChange={(event) => set('permissionMode', event.target.value)}>
              {PERMISSION_OPTIONS.map((option) => <option key={option.value} value={option.value}>{option.label}</option>)}
            </select>
          </label>
        </div>
        <label className="sheet-field">
          <span>委派子代理</span>
          <select
            value={form.allowSubagents} disabled={busy}
            onChange={(event) => set('allowSubagents', event.target.value as PresetForm['allowSubagents'])}
          >
            <option value="inherit">跟随默认策略</option>
            <option value="allow">允许</option>
            <option value="deny">禁止</option>
          </select>
        </label>
        <div className="preset-groups">
          <span className="preset-groups-label">禁用内置工具组</span>
          <div className="preset-checks">
            {TOOL_GROUPS.map((group) => (
              <label key={group.id} className="preset-check">
                <input
                  type="checkbox"
                  checked={form.disabledToolGroups.includes(group.id)}
                  disabled={busy}
                  onChange={() => toggleGroup(group.id)}
                />
                {group.label}
              </label>
            ))}
          </div>
        </div>
        <label className="sheet-field">
          <span>追加系统提示（一行一段）</span>
          <textarea
            rows={4} value={form.promptSections} disabled={busy}
            placeholder="例如：先列证据再下结论"
            onChange={(event) => set('promptSections', event.target.value)}
          />
        </label>
        <p className="sheet-note">
          技能与 MCP 白名单请在电脑端设置；这里保存不会改动它们。改动在下一轮消息中生效。
        </p>
        {error && <p className="sheet-error" role="alert">{error}</p>}
        {editing && (
          <div className="preset-mini-actions">
            <button disabled={busy} onClick={() => onSetDefault(editing.id)}>
              {defaultPresetId === editing.id ? '已是默认预设' : '设为默认'}
            </button>
            {!editing.isBuiltin && (
              <button className="preset-danger" disabled={busy} onClick={onDelete}>删除预设</button>
            )}
          </div>
        )}
      </div>
      <div className="sheet-actions">
        <button disabled={busy} onClick={onBack}>取消</button>
        <button className="primary" disabled={busy} onClick={() => void save()}>{busy ? '处理中…' : '保存'}</button>
      </div>
    </>
  )
}

export function PresetPanel({
  presets, currentId, defaultPresetId, models, busy,
  onClose, onApply, onSave, onCopy, onDelete, onSetDefault,
}: PresetPanelProps): React.ReactElement {
  const [view, setView] = React.useState<View>({ kind: 'list' })
  const [notice, setNotice] = React.useState('')
  // 提交中（busy）不响应 Esc：避免保存到一半被关掉，状态停在未知处
  const sheetRef = useOverlay(onClose, !busy)

  const copyAndEdit = async (preset: WebPreset): Promise<void> => {
    const created = await onCopy(preset.id)
    if (!created) { setNotice('复制失败，请稍后重试'); return }
    setView({ kind: 'edit', preset: created })
  }

  return (
    <>
      <div className="sheet-scrim" onClick={() => { if (!busy) onClose() }} />
      <div className="sheet preset-sheet" role="dialog" aria-modal="true" aria-label="预设管理" ref={sheetRef} tabIndex={-1}>
        {view.kind === 'list' ? (
          <>
            <div className="preset-head">
              <strong>Agent 预设（岗位）</strong>
              <button className="preset-close" onClick={onClose} aria-label="关闭">×</button>
            </div>
            <div className="preset-list">
              {presets.length === 0 && <div className="preset-empty">当前工作区暂无可用预设</div>}
              {presets.map((preset) => {
                const active = preset.id === currentId
                return (
                  <div className={`preset-item ${active ? 'active' : ''}`} key={`${preset.scope}:${preset.id}`}>
                    <button className="preset-select" disabled={busy} onClick={() => onApply(preset.id)}>
                      <span className="preset-item-head">
                        <span className="preset-checkmark">{active ? '✓' : ''}</span>
                        <span className="preset-name">{preset.name}</span>
                        {presetBadges(preset).map((badge) => <span className="preset-badge" key={badge}>{badge}</span>)}
                      </span>
                      {preset.description && <span className="preset-desc">{preset.description}</span>}
                    </button>
                    <div className="preset-item-actions">
                      {preset.isBuiltin
                        ? <button disabled={busy} onClick={() => void copyAndEdit(preset)}>复制后编辑</button>
                        : <button disabled={busy} onClick={() => setView({ kind: 'edit', preset })}>编辑</button>}
                      {defaultPresetId === preset.id && <span className="preset-default">默认</span>}
                    </div>
                  </div>
                )
              })}
            </div>
            {notice && <p className="sheet-error">{notice}</p>}
            <div className="sheet-actions">
              <button disabled={busy} onClick={() => setView({ kind: 'new' })}>+ 新建预设</button>
              <button disabled={busy} onClick={onClose}>关闭</button>
            </div>
          </>
        ) : (
          <Editor
            key={view.kind === 'edit' ? view.preset.id : 'new'}
            view={view}
            models={models}
            busy={busy}
            defaultPresetId={defaultPresetId}
            onBack={() => setView({ kind: 'list' })}
            onSave={onSave}
            onDelete={() => {
              void onDelete(view.kind === 'edit' ? view.preset.id : '').then((ok) => {
                if (ok) setView({ kind: 'list' })
              })
            }}
            onSetDefault={onSetDefault}
          />
        )}
      </div>
    </>
  )
}
