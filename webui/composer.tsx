/**
 * 输入条：附件条 + 胶囊输入框 + 主动作按钮。
 *
 * 「一个位置一个主动作」：能发就是发送，运行中且没内容才是停止。
 * 触屏与桌面的 Enter 语义不同，判定放在这里（`PREFER_SEND_BUTTON`），
 * 调用方不需要知道当前是什么指针设备。
 *
 * 提及语义与桌面端一致：`/` 触发 Skill、`@` 引用文件，两种 token 都留在正文里。
 *
 * 布局：加号在**左下角**（拇指自然落点），发送键在右下角，正文居中偏右。
 */
import * as React from 'react'
import { formatBytes } from './attachments'
import { FileTypeIcon } from './file-icon'
import type { StagedAttachment } from './use-attachments'
import { isDoubleEnterToSend } from './composer-keys'
import {
  buildFileGroups, encodeFileTokenPath, filterFiles, filterSkills, flattenFileTree,
  isCommittedMentionQuery, resolveFileQuery, resolveSkillQuery,
  type FileOption, type FileTreeNode, type MentionQuery, type SkillOption,
} from './mentions'

/**
 * 触屏设备上没有 Shift 键：若 Enter 仍当发送，就永远没办法在消息里换行。
 * 按指针类型分流：
 * - 粗指针（手机/平板）：Enter 换行，**连按两次**才发送（见 composer-keys.ts），另外保留右侧圆键；
 * - 细指针（桌面浏览器）：Enter 发送、Shift+Enter 换行（老习惯）。
 */
export const PREFER_SEND_BUTTON = typeof window.matchMedia === 'function'
  && window.matchMedia('(pointer: coarse)').matches

/** 输入框自增高上限（px）：再高就把消息区挤没了 */
const MAX_INPUT_HEIGHT = 168

export interface ComposerProps {
  input: string
  onInputChange: (value: string) => void
  onSend: () => void
  onStop: () => void
  /** 发送请求在飞（按钮显示 …） */
  busy: boolean
  canSend: boolean
  /** 这一轮正在跑：无内容时按钮变成停止 */
  sessionActive: boolean
  /** 有内容且这一轮在跑 → 点发送是「加入队列」而不是直发 */
  willQueue: boolean
  /** 有待处理的确认卡片：输入框锁住 */
  hasPending: boolean
  connected: boolean
  attachments: StagedAttachment[]
  readyCount: number
  uploading: boolean
  onPickFiles: (files: FileList | null) => void
  onRemoveAttachment: (key: string) => void
  fileInputRef: React.MutableRefObject<HTMLInputElement | null>
  inputRef: React.MutableRefObject<HTMLTextAreaElement | null>
  /** 可 / 引用的技能（list_skills 下发；空列表时不弹建议） */
  skills: SkillOption[]
  /** 可 @ 引用的文件（list_workspace_files 下发；空列表时不弹建议） */
  files: FileOption[]
  /** @ 查询串变化时按需向主进程重新拉候选（深层文件也能被引用） */
  onFileQuery: (query: string) => void
}

export function Composer({
  input, onInputChange, onSend, onStop, busy, canSend, sessionActive, willQueue, hasPending, connected,
  attachments, readyCount, uploading, onPickFiles, onRemoveAttachment, fileInputRef, inputRef, skills, files, onFileQuery,
}: ComposerProps): React.ReactElement {
  /** 上一次回车的时间：只用来判定「连按两次」，不参与渲染 */
  const lastEnterAtRef = React.useRef<number | null>(null)
  /** `/` 技能上下文与 `@` 文件上下文：同一时刻只会有一个弹层 */
  const [skillMention, setSkillMention] = React.useState<MentionQuery | null>(null)
  const [fileMention, setFileMention] = React.useState<MentionQuery | null>(null)
  const [skillIndex, setSkillIndex] = React.useState(0)
  const [fileIndex, setFileIndex] = React.useState(0)
  const skillOptions = React.useMemo(
    () => (skillMention ? filterSkills(skills, skillMention.query) : []),
    [skillMention, skills],
  )
  const fileOptions = React.useMemo(
    () => (fileMention ? filterFiles(files, fileMention.query) : []),
    [fileMention, files],
  )
  /** 两组各自建树 + 各自展开集合 → 一张带分组标记的可见行表（会话文件在前、工作区文件在后） */
  const fileGroups = React.useMemo(() => buildFileGroups(fileOptions), [fileOptions])
  const [expandedDirs, setExpandedDirs] = React.useState<ReadonlySet<string>>(() => new Set())
  type FlatFileRow = { node: FileTreeNode; group: 'session' | 'workspace' }
  const flatFiles = React.useMemo<FlatFileRow[]>(() => {
    const rows: FlatFileRow[] = []
    for (const group of ['session', 'workspace'] as const) {
      for (const node of flattenFileTree(fileGroups[group], expandedDirs)) rows.push({ node, group })
    }
    return rows
  }, [fileGroups, expandedDirs])

  const toggleDir = React.useCallback((node: FileTreeNode): void => {
    const key = `${node.source}:${node.path}`
    setExpandedDirs((previous) => {
      const next = new Set(previous)
      if (next.has(key)) next.delete(key)
      else next.add(key)
      return next
    })
  }, [])
  const isExpanded = React.useCallback((node: FileTreeNode): boolean =>
    expandedDirs.has(`${node.source}:${node.path}`), [expandedDirs])

  // 候选内容变化时，把两组的顶层目录默认展开（收到新 query 也能立刻看到里面的文件）
  const fileTreeKey = fileOptions.map((item) => item.path).join('|')
  React.useEffect(() => {
    const all: FileTreeNode[] = [...fileGroups.session, ...fileGroups.workspace]
    setExpandedDirs(new Set(
      all.filter((node) => node.type === 'dir').map((node) => `${node.source}:${node.path}`),
    ))
    // 依赖用内容 key，避免每次渲染因新数组引用重复展开、把用户的折叠状态冲掉
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [fileTreeKey])

  const clearMentions = React.useCallback((): void => {
    setSkillMention(null)
    setFileMention(null)
    setSkillIndex(0)
    setFileIndex(0)
  }, [])

  /** @ 查询的防抖定时器与上一次已请求的 query（避免每敲一个字都发命令） */
  const fileQueryTimerRef = React.useRef<ReturnType<typeof setTimeout> | null>(null)
  const lastFileQueryRef = React.useRef<string | null>(null)

  /** 光标处同时命中两种触发符时，取更靠后的那个（后输入者优先） */
  const syncMentions = (value: string, caret: number): void => {
    const skillContext = resolveSkillQuery(value, caret)
    const fileContext = resolveFileQuery(value, caret)
    // 光标落在已确认的 token 内（@file:… / /skill:…）时不是查询，而是用户在改正文：
    // 点回输入框恢复焦点时不能把候选面板重新弹出来。
    const skill = skillContext && !isCommittedMentionQuery(skillContext.query) ? skillContext : null
    const file = fileContext && !isCommittedMentionQuery(fileContext.query) ? fileContext : null
    if (file && (!skill || file.start >= skill.start)) {
      setFileMention(file)
      setSkillMention(null)
    } else if (skill) {
      setSkillMention(skill)
      setFileMention(null)
    } else {
      setSkillMention(null)
      setFileMention(null)
    }
    setSkillIndex(0)
    setFileIndex(0)
    // 文件查询串变了才按需重拉；防抖 180ms，避免每个按键一次命令
    if (file && lastFileQueryRef.current !== file.query) {
      lastFileQueryRef.current = file.query
      if (fileQueryTimerRef.current) clearTimeout(fileQueryTimerRef.current)
      fileQueryTimerRef.current = setTimeout(() => onFileQuery(file.query), 180)
    } else if (!file) {
      lastFileQueryRef.current = null
      if (fileQueryTimerRef.current) { clearTimeout(fileQueryTimerRef.current); fileQueryTimerRef.current = null }
    }
  }

  React.useEffect(() => () => {
    if (fileQueryTimerRef.current) clearTimeout(fileQueryTimerRef.current)
  }, [])

  /** 把技能候选写回：替换 `/查询串` 为 `/skill:<slug>` token（正文里保留 token） */
  const applySkill = (skill: SkillOption): void => {
    const node = inputRef.current
    if (!node || !skillMention) return
    const caret = node.selectionStart ?? node.value.length
    const start = skillMention.start
    const token = `/skill:${skill.slug} `
    onInputChange(`${node.value.slice(0, start)}${token}${node.value.slice(node.selectionEnd ?? caret)}`)
    clearMentions()
    // 选完把光标放到 token 之后：继续打字不会插进 token 中间
    requestAnimationFrame(() => {
      node.focus()
      const pos = start + token.length
      node.setSelectionRange(pos, pos)
    })
  }

  /** 把文件/目录候选写回：替换 `@查询串` 为 `@file:<引用路径>` token */
  const applyFile = (file: { refPath: string }): void => {
    const node = inputRef.current
    if (!node || !fileMention) return
    const caret = node.selectionStart ?? node.value.length
    const start = fileMention.start
    // 路径编码：空格/中文转 %XX，保证消息正文里的 token 能被 \S+ 完整解析
    const token = `@file:${encodeFileTokenPath(file.refPath)} `
    onInputChange(`${node.value.slice(0, start)}${token}${node.value.slice(node.selectionEnd ?? caret)}`)
    clearMentions()
    requestAnimationFrame(() => {
      node.focus()
      const pos = start + token.length
      node.setSelectionRange(pos, pos)
    })
  }

  /** 输入框自增长。先归 auto 再取 scrollHeight，否则删字后高度不会回缩。 */
  React.useEffect(() => {
    const node = inputRef.current
    if (!node) return
    node.style.height = 'auto'
    node.style.height = `${Math.min(node.scrollHeight, MAX_INPUT_HEIGHT)}px`
  }, [input, inputRef])

  /**
   * 发送后收起软键盘（触屏）。
   *
   * 必须在**用户手势的同步栈里** blur：iOS Safari 只在手势内才允许收起键盘，
   * 放到发送成功的 await 之后再 blur 是无效的（键盘会一直挂着，挡住刚发出的消息）。
   * 代价是发送失败时输入框已经失焦 —— 文本仍在，点一下就能继续编辑，可接受。
   * 桌面（细指针）不做：Enter 发完还能接着敲下一条，失焦反而碍事。
   */
  const exitInputMode = (): void => {
    if (!PREFER_SEND_BUTTON) return
    inputRef.current?.blur()
  }

  const submit = (): void => {
    exitInputMode()
    onSend()
  }

  /** 候选面板打开时优先消费方向键 / Enter / Tab / Esc；返回 true 表示已处理 */
  const handleMentionKeys = (event: React.KeyboardEvent<HTMLTextAreaElement>): boolean => {
    const isSkill = skillMention !== null
    if (!isSkill && fileMention === null) return false
    const options = isSkill ? skillOptions : flatFiles
    const index = isSkill ? skillIndex : fileIndex
    const setIndex = isSkill ? setSkillIndex : setFileIndex
    if (event.key === 'Escape') {
      event.preventDefault()
      clearMentions()
      return true
    }
    if (options.length === 0) return false
    if (event.key === 'ArrowDown') {
      event.preventDefault()
      setIndex((current) => Math.min(current + 1, options.length - 1))
      return true
    }
    if (event.key === 'ArrowUp') {
      event.preventDefault()
      setIndex((current) => Math.max(current - 1, 0))
      return true
    }
    // 目录：右键展开、左键折叠（与桌面端 FileMentionList 的方向键语义一致）
    if (!isSkill) {
      const current = (flatFiles[index] as FlatFileRow | undefined)?.node
      if (current?.type === 'dir') {
        if (event.key === 'ArrowRight' && !isExpanded(current)) {
          event.preventDefault()
          toggleDir(current)
          return true
        }
        if (event.key === 'ArrowLeft' && isExpanded(current)) {
          event.preventDefault()
          toggleDir(current)
          return true
        }
      }
    }
    if (event.key === 'Enter' || event.key === 'Tab') {
      event.preventDefault()
      const picked = options[index] ?? options[0]
      if (picked) {
        if (isSkill) applySkill(picked as SkillOption)
        else {
          const node = (picked as FlatFileRow).node
          // Enter 落在目录上：展开/折叠，让用户继续往下挑；Tab 或直接引用用右侧按钮
          if (node.type === 'dir' && event.key === 'Enter') toggleDir(node)
          else applyFile(node)
        }
      }
      return true
    }
    return false
  }

  const mentionOpen = skillMention !== null || fileMention !== null
  const mentionIsSkill = skillMention !== null
  const activeIndex = mentionIsSkill ? skillIndex : fileIndex
  const mentionPopRef = React.useRef<HTMLDivElement | null>(null)

  /**
   * 候选面板没有遮罩：点面板与输入框之外的任何地方都收起。
   * 手机上 Esc 不可用，这是关闭建议面板的唯一手势；点输入框本身不收，
   * 由 onFocus/onClick 按新光标位置重新判定，保证「点回来又能出现」。
   */
  React.useEffect(() => {
    if (!mentionOpen) return
    const onPointerDown = (event: PointerEvent): void => {
      const target = event.target
      if (!(target instanceof Node)) return
      if (mentionPopRef.current?.contains(target)) return
      if (inputRef.current?.contains(target)) return
      clearMentions()
    }
    // 捕获阶段：别的浮层若在冒泡里 stopPropagation 也照样收
    document.addEventListener('pointerdown', onPointerDown, true)
    return () => document.removeEventListener('pointerdown', onPointerDown, true)
  }, [mentionOpen, clearMentions])

  /** 点回输入框：按当前光标恢复候选（外部点按收起后靠这一步重新出现）。 */
  const resumeMentions = (node: HTMLTextAreaElement): void => {
    syncMentions(node.value, node.selectionStart ?? node.value.length)
  }

  return (
    <div className="composer">
      {/* 附件条在输入框之上：它是「这一条消息的组成」，不是输入框的一部分 */}
      {attachments.length > 0 && (
        <div className="attach-bar">
          {attachments.map((item) => (
            <div className={`attach-chip ${item.status}`} key={item.key} title={item.error ?? item.filename}>
              <span className="attach-name">{item.filename}</span>
              <span className="attach-size">
                {item.status === 'uploading' ? '上传中…' : item.status === 'error' ? item.error : formatBytes(item.size)}
              </span>
              <button
                className="attach-remove"
                onClick={() => onRemoveAttachment(item.key)}
                aria-label={`移除附件 ${item.filename}`}
              >
                ×
              </button>
            </div>
          ))}
        </div>
      )}
      <input
        ref={fileInputRef}
        className="attach-input"
        type="file"
        multiple
        onChange={(event) => {
          onPickFiles(event.target.files)
          // 清空 value：同一个文件连选两次也要能触发 change
          event.target.value = ''
        }}
      />
      <div className={`composer-pill ${hasPending ? 'blocked' : ''}`}>
        <button
          className="attach-btn"
          onClick={() => fileInputRef.current?.click()}
          disabled={hasPending || !connected}
          title="添加附件"
          aria-label="添加附件"
        >
          +
        </button>
        {mentionOpen && (mentionIsSkill ? skills.length > 0 : files.length > 0) && (
          <div className="mention-pop" ref={mentionPopRef} role="listbox" aria-label={mentionIsSkill ? '技能建议' : '文件引用'}>
            <div className="mention-group-label">{mentionIsSkill ? '技能 · /skill:' : '文件 · @file:'}</div>
            {mentionIsSkill ? (
              skillOptions.length === 0 ? (
                <div className="mention-empty">没有匹配的技能</div>
              ) : (
                skillOptions.map((skill, index) => (
                  <button
                    type="button"
                    key={skill.slug}
                    role="option"
                    aria-selected={index === activeIndex}
                    className={`mention-item${index === activeIndex ? ' active' : ''}`}
                    onMouseDown={(event) => { event.preventDefault(); applySkill(skill) }}
                  >
                    <span className="mention-name">{skill.name}</span>
                    <span className="mention-slug">/skill:{skill.slug}</span>
                  </button>
                ))
              )
            ) : flatFiles.length === 0 ? (
              <div className="mention-empty">没有匹配的文件</div>
            ) : (
              flatFiles.map((row, index) => {
                const { node, group } = row
                const isDir = node.type === 'dir'
                const open = isExpanded(node)
                const prevGroup = index > 0 ? (flatFiles[index - 1] as FlatFileRow).group : undefined
                const showGroupLabel = group !== prevGroup
                return (
                  <React.Fragment key={`${group}:${node.type}:${node.path}`}>
                    {showGroupLabel && (
                      <div className="mention-subgroup">
                        {group === 'session' ? '会话文件' : '工作区文件'}
                      </div>
                    )}
                    <button
                      type="button"
                      role="option"
                      aria-selected={index === activeIndex}
                      aria-expanded={isDir ? open : undefined}
                      className={`mention-item mention-file${isDir ? ' is-dir' : ''}${index === activeIndex ? ' active' : ''}`}
                      style={{ paddingLeft: `${10 + node.depth * 12}px` }}
                      // mousedown preventDefault：选完不打断输入焦点（手机键盘不收起）
                      onMouseDown={(event) => {
                        event.preventDefault()
                        // 目录行点主体=展开/折叠；旁边「引用」小按钮负责插入目录 token
                        if (isDir) toggleDir(node)
                        else applyFile(node)
                      }}
                      title={isDir ? `${node.refPath}（点按展开，或点右侧「引用」）` : node.refPath}
                    >
                      <span className="mention-tree-icon" aria-hidden="true">{isDir ? (open ? '▾' : '▸') : ''}</span>
                      <FileTypeIcon name={node.name} isDirectory={isDir} isOpen={open} />
                      <span className="mention-name">{node.name}</span>
                      {isDir && (
                        <span
                          className="mention-dir-ref"
                          role="button"
                          aria-label={`引用目录 ${node.refPath}`}
                          onMouseDown={(event) => { event.stopPropagation(); event.preventDefault(); applyFile(node) }}
                        >
                          引用
                        </span>
                      )}
                    </button>
                  </React.Fragment>
                )
              })
            )}
          </div>
        )}
        <textarea
          ref={inputRef}
          rows={1}
          value={input}
          disabled={hasPending}
          placeholder={hasPending ? '先处理上方的确认卡片' : '发送消息…'}
          title={PREFER_SEND_BUTTON ? undefined : 'Enter 发送，Shift+Enter 换行，/ 技能，@ 引用文件'}
          enterKeyHint={PREFER_SEND_BUTTON ? 'enter' : 'send'}
          onChange={(event) => {
            onInputChange(event.target.value)
            const node = event.currentTarget
            syncMentions(node.value, node.selectionStart ?? node.value.length)
          }}
          // 点回输入框（焦点回来或被再次点按）：按光标所在位置恢复候选
          onFocus={(event) => {
            resumeMentions(event.currentTarget)
          }}
          onClick={(event) => {
            resumeMentions(event.currentTarget)
          }}
          onPaste={(event) => {
            // 截图/文件直接粘贴：桌面端最顺手的上传方式，不接反而显得「网页版少一块」
            const files = event.clipboardData?.files
            if (!files || files.length === 0) return
            event.preventDefault()
            onPickFiles(files)
          }}
          onKeyDown={(event) => {
            // 候选面板打开时：方向键选择、Enter/Tab 确认、Esc 关闭 —— 优先于发送/换行
            if (handleMentionKeys(event)) return
            if (event.key !== 'Enter' || event.shiftKey) return
            // 中文输入法用 Enter 上屏，此时不能当成发送；isComposing 就是这道闸。
            if (event.nativeEvent.isComposing) return
            if (PREFER_SEND_BUTTON) {
              const value = event.currentTarget.value
              // 光标不在末尾 = 在中间插行，交给浏览器；顺带清掉时间戳，避免下次误判
              if (event.currentTarget.selectionStart !== value.length) {
                lastEnterAtRef.current = null
                return
              }
              const now = Date.now()
              // canSend 为假时不当作发送：否则会白白收起键盘（例如附件还在上传）
              if (canSend && isDoubleEnterToSend({ now, lastEnterAt: lastEnterAtRef.current, value })) {
                event.preventDefault()
                lastEnterAtRef.current = null
                submit()
                return
              }
              lastEnterAtRef.current = now
              return
            }
            event.preventDefault()
            submit()
          }}
        />
        {/* 一个位置一个主动作：能发就是发送，运行中且没内容才是停止 */}
        {sessionActive && !input.trim() && readyCount === 0 ? (
          <button className="send-btn run" onClick={onStop} title="停止本轮运行">■</button>
        ) : (
          <button
            className={`send-btn ${canSend ? 'ready' : ''}`}
            disabled={!canSend}
            onClick={submit}
            title={willQueue ? '加入队列（本轮结束后自动发送）' : uploading ? '附件上传中' : '发送（Enter）'}
          >
            {busy ? '…' : '↑'}
          </button>
        )}
      </div>
    </div>
  )
}
