# 移动端对话渲染内核迁移清单（ui-port-map）

> 从 `apps/electron/src/renderer` 把「对话渲染内核」迁移到 `apps/tablet-client`，实现 1:1 复现原移动版对话 UI。
> 本文档记录：数据形状对齐结论、迁移清单、依赖边界、Electron IPC stub 面、未搬项及原因。

---

## 0. 核心结论：数据形状已天然对齐，无需 adapter

这是本任务的**最关键发现**——原以为要写一层「形状适配器」，测绘后确认不需要：

| 桌面端 | tablet-client | 对齐方式 |
|--------|--------------|---------|
| `@/atoms/agent-atoms.ts` 的 `AgentStreamState` / `ToolActivity` / `ActivityStatus` | `src/atoms/agent.ts` | **逐字段镜像**，已对齐（`running/content/toolActivities/model/usage/retrying/startedAt/stopping/backgroundWaiting` 等语义一致） |
| `getActivityStatus` / `finalizeStreamingActivities` | 同名函数已在 `agent.ts` 定义 | 等价 |
| `SDKMessage` 全家（`SDKAssistantMessage`/`SDKUserMessage`/`SDKSystemMessage`/`SDKResultMessage`/`SDKContentBlock`/`SDKThinkingBlock`/`SDKToolUseBlock`/`SDKToolResultBlock`/`AgentEventUsage`/`RetryAttempt`） | 同样从 `@profer/shared` 直接 import | **同一份类型，零差异** |
| `payloadToLegacyEvents` + `useGlobalAgentListeners`（事件归并） | `src/lib/agentEvents.ts` 的 `payloadToAgentEvents` + `handleAgentEvent` | 已复刻归并核心，产物写入 `liveMessagesMapAtom` + `agentStreamingStatesAtom`，形状一致 |
| `AgentMessages` 消费的 props（`persistedSDKMessages`/`liveMessages`/`streamState`/`streaming`/`stoppedByUser`/`sessionModelId`） | `src/components/agent/AgentView.tsx` 已经按这套 props 组装好往下传 | 一致 |

**结论**：流式状态 → 渲染内核的数据链路，两端已经共享 `@profer/shared` 类型 + 同构的 atom 形状。**不需要额外 adapter 层**。唯一要补的是一小段「移动端无 Electron 时」的兜底（如 `thinkingExpandedAtom`、`userProfileAtom`、`channelsAtom` 等 atom 需要在 tablet-client 本地建极简版）。

---

## 1. 迁移清单总览

### 1.1 纯函数层（原样搬，零/极轻依赖）

| 源文件 | 目标 | 说明 |
|--------|------|------|
| `lib/utils.ts`（cn） | `src/lib/utils.ts` | 依赖 `clsx` + `tailwind-merge`（补依赖） |
| `@profer/ui` 的 `useSmoothStream` | `src/lib/useSmoothStream.ts` | **内联搬**（纯 React hook，无外部依赖，不引整个 `@profer/ui`） |
| `@profer/session-core`（`groupIntoTurns`/`extractUserText`/`isUserInputMessage`/`getGroupPreview`/`normalizeThinkTagsInContentBlocks`/`parseThinkTagsFromText`/`MessageGroup`/`AssistantTurn`） | **直接加 workspace 依赖**，不复制 | `@profer/session-core` 主入口（`.`）全为纯函数、浏览器安全，只依赖 `@profer/shared`。**免去复制 `groupIntoTurns` 200 行 + thinking-tag-parser 的重写**，且保证快照去重与桌面/CLI 同源 |
| `components/agent/thinking-tag-parser.ts` | **不搬** | 由 `@profer/session-core` 的 `parseThinkTagsFromText` / `normalizeThinkTagsInContentBlocks` 替代（同源逻辑） |
| `components/agent/live-group-set.ts` | `src/components/agent/live-group-set.ts` | 纯函数，依赖 `SDKMessageRenderer` 的 `MessageGroup` 类型 |

### 1.2 UI 基建（最小集，补依赖 radix/cva）

| 源 | 目标 | 新依赖 |
|----|------|--------|
| `components/ui/button.tsx` | `src/components/ui/button.tsx` | `@radix-ui/react-slot` + `class-variance-authority` |
| `components/ui/tooltip.tsx` | `src/components/ui/tooltip.tsx` | `@radix-ui/react-tooltip` |
| `components/ui/spinner.tsx` | `src/components/ui/spinner.tsx` | 无 |
| `components/ui/badge.tsx` | `src/components/ui/badge.tsx` | `class-variance-authority` |
| `components/ui/collapsible.tsx` | `src/components/ui/collapsible.tsx` | `@radix-ui/react-collapsible` |

### 1.3 ai-elements（搬，改造 import + 削 IPC）

| 源 | 目标 | 处理 |
|----|------|------|
| `ai-elements/tablet-mode-context.ts` | `src/components/ai-elements/tablet-mode-context.ts` | 原样搬（纯 context） |
| `ai-elements/context-divider.tsx` | `src/components/ai-elements/context-divider.tsx` | 原样搬 |
| `ai-elements/conversation.tsx` | `src/components/ai-elements/conversation.tsx` | 原样搬（补 `use-stick-to-bottom`） |
| `ai-elements/reasoning.tsx` | `src/components/ai-elements/reasoning.tsx` | 原样搬（补 react-markdown 系） |
| `ai-elements/message.tsx` | `src/components/ai-elements/message.tsx` | **改造**：削 `@profer/ui` CodeBlock/MermaidBlock → 内联简化；削 `@profer/core` detectLanguage → 不检测；削 `currentAgentSessionIdAtom`/`useOpenPreview`/`window.electronAPI` → stub |
| `ai-elements/file-path-chip.tsx` | `src/components/ai-elements/file-path-chip.tsx` | **改造**：削 `FileTypeIcon`/`useOpenPreview`/`currentAgentSessionIdAtom`/context-menu → 纯展示 chip |
| `ai-elements/scroll-minimap.tsx` / `sticky-user-message.tsx` | **不搬** | 移动端 tabletMode 不渲染（`!tabletMode &&` 分支），见 §3 |
| `ai-elements/rich-text-input.tsx` / `InputToolbarOverflow.tsx` / `speech-button.tsx` | **不搬** | 输入区保持 tablet-client 现有 Composer（textare），移动端用不到富文本/语音；见 §3 |

### 1.4 对话渲染核心

| 源 | 目标 | 处理 |
|----|------|------|
| `components/agent/tool-utils.ts` | `src/components/agent/tool-utils.ts` | 纯函数（lucide 图标映射），原样搬 |
| `components/agent/tool-phrase.ts` | `src/components/agent/tool-phrase.ts` | 纯函数（computeDiffStats），原样搬 |
| `components/agent/ContentBlock.tsx` | `src/components/agent/ContentBlock.tsx` | **改造**：削 `ImageLightbox`/`thinkingExpandedAtom`(Electron atom)/`preview-open-button`/`task-get-result`/`task-list-result` → 极简工具结果渲染 |
| `components/agent/SDKMessageRenderer.tsx` | `src/components/agent/SDKMessageRenderer.tsx` | **大幅改造**：只保留 `groupIntoTurns`/`MessageGroup`/`MessageGroupRenderer`/`SDKMessageRenderer`/`getGroupId`/`getGroupPreview`/`parseAttachedFiles`/`isImageFile`/`buildHistoricalTaskSubjects`/`CompactingIndicator`，削 TaskProgressCard/ProcessBlockGroup/tool-result 富渲染/❓所有 Electron atom（model-logo/user-profile/channels/planning/automation/settings 等）→ stub |
| `components/agent/AgentMessages.tsx` | `src/components/agent/AgentMessages.tsx` | **改造**：复用搬来的内核，削 WelcomeEmptyState/model-logo/ScrollPositionManager/ScrollMinimap/StickyUserMessage（tabletMode 不渲染）→ stub 空态 + 本地 atom |
| `components/agent/AgentHistorySelectionLayer.tsx` | **不搬** | 桌面历史选择/复制粘贴层，移动端用不到，标记后续 |

### 1.5 接线替换

| 现文件 | 处理 |
|--------|------|
| `src/components/agent/AgentView.tsx` | **保留其数据/WS 逻辑**（消息加载、handleSend/handleStop、交互横幅），只把中间的 `<AgentMessages>`/`<Composer>` 换成搬来的内核，并补 `tabletMode`/`sessionModelId` prop 传递 |
| `src/components/agent/AgentMessages.tsx` | 整文件替换为搬来的内核版 |
| `src/components/agent/Composer.tsx` | **保留**（现有 textarea 实现已满足需求，移动端不接富文本） |

---

## 2. 需要 stub 的 Electron IPC / 桌面专属依赖面

以下依赖点一律以「移动端 no-op / 简化替代」处理，不引 Electron：

| 原来引用 | 用途 | 移动端替代 |
|----------|------|-----------|
| `window.electronAPI.openExternal` / `systemOpenFile` | 打开链接/文件 | 链接不响应点击（`tabletMode` 已返回空） |
| `useOpenPreview`（`@/components/diff/preview-opener`） | 打开文件预览面板 | no-op stub（返回空函数） |
| `@/components/ui/image-lightbox` | 图片大图预览 | 不渲染大图灯箱，仅占位 |
| `@/lib/model-logo`（getModelLogo/resolveModelDisplayName/resolveModelProvider） | 模型图标/名称 | 本地极简版：返回 `Bot` 图标 + 原样返回 modelId |
| `@/components/chat/UserAvatar` / `CopyButton` / `ChatMessageItem`（formatMessageTime） | 头像/复制/时间 | 本地简化版（Bot 图标 / 无复制 / formatMessageTime 内联） |
| `@/atoms/user-profile`（userProfileAtom） | 用户头像 | 本地 `userProfileAtom` 极简（空头像） |
| `@/atoms/chat-atoms`（channelsAtom/requestModelSelectorOpen/thinkingExpandedAtom） | 渠道列表/模型选择/思考折叠 | 本地极简 atom（channelsAtom 空数组、thinkingExpandedAtom 默认 true） |
| `@/atoms/tab-atoms`（tabMinimapCacheAtom/activeSessionIdAtom） | 迷你图缓存 | 本地极简 atom（Set/Map 空） |
| `@/atoms/agent-atoms` 里的一堆（agentSessionsAtom/planning/automation/settings/activeView 等） | 模型选择器/计划/自动化状态 | 本地极简 atom 或直接删引用 |
| `@pierre/diffs`（FileDiff/MultiFileDiff） | Read/Edit/Write 工具结果 diff 渲染 | **不搬**，工具结果用纯文本折叠渲染 |
| `@/components/diff/*` | diff preview | 不搬 |

---

## 3. 明确不搬项及原因（红线约束第 5 条）

| 项 | 原因 |
|----|------|
| `AgentView.tsx` 重耦合外壳（附件上传/知识库选择器/ProjectGraph/语音/diff preview/shortcut） | 全部依赖 Electron IPC，移动端用不到 |
| `ai-elements/scroll-minimap.tsx` / `sticky-user-message.tsx` | 桌面 `!tabletMode` 专属，移动端 tabletMode 恒 true |
| `ai-elements/rich-text-input.tsx` / `InputToolbarOverflow.tsx` / `speech-button.tsx` | 富文本输入/语音依赖 TipTap + 麦克风 IPC，移动端用现有 textarea |
| `@pierre/diffs`（Read/Edit/Write 结果 diff） | 桌面 diff 渲染库，重量级，移动端降级为纯文本 |
| `@profer/ui`（CodeBlock/MermaidBlock）、`@profer/core`（detectLanguage） | `@profer/core` peer 依赖 `@anthropic-ai/claude-agent-sdk`，无法在移动端编译；mermaid/shiki 体积爆炸（>2MB gzip）。改为内联 `useSmoothStream` + 简化 CodeBlock（`<pre>` 深色底，不 Shiki 高亮）+ 不渲染 Mermaid 图（降级为代码块） |
| `ProcessBlockGroup` / `TaskProgressCard` / `TurnFileChangesSummary` / `TaskProgressCard` | 桌面进程组/任务卡片，依赖复杂 + **保留最小工具行即可满足「工具活动虚线框」要求** |
| `AgentHistorySelectionLayer` | 桌面历史选择层，标记后续 |

---

## 4. 体积与构建预期

- 新增 npm 依赖：`react-markdown`、`remark-gfm`、`remark-math`、`rehype-katex`、`katex`、`use-stick-to-bottom`、`@radix-ui/react-slot`、`@radix-ui/react-tooltip`、`@radix-ui/react-collapsible`、`class-variance-authority`、`clsx`、`@profer/session-core`（workspace）。
- **拒绝引入**：`@profer/ui`（mermaid/shiki/beautiful-mermaid）、`@profer/core`（claude-agent-sdk/highlight.js/shiki）、`@pierre/diffs`。
- 体积增长预估：react-markdown + katex 为主（~300KB gzip），属于可接受范围，远小于引入 mermaid/shiki 的爆炸。

---

## 5. 交付记录（已执行）

### 5.1 实际迁移文件清单（apps/tablet-client/src）

| 目标路径 | 来源 | 处理 |
|---------|------|------|
| `lib/utils.ts` | electron lib/utils.ts | 原样搬（cn） |
| `lib/useSmoothStream.ts` | @profer/ui 的 useSmoothStream | 内联搬（去重依赖） |
| `lib/normalize-latex.ts` | electron lib/normalize-latex.ts | 纯函数搬 |
| `components/ui/button.tsx` / `tooltip.tsx` / `spinner.tsx` / `badge.tsx` / `collapsible.tsx` | electron ui/* | 原样搬（补 radix/cva） |
| `components/ui/code-block.tsx` | @profer/ui 的 CodeBlock | **简化重写**：去 shiki/@profer/core 高亮，纯深色 pre |
| `components/ai-elements/message.tsx` | electron ai-elements/message.tsx | 削 @profer/ui/@profer/core/Electron IPC/preview-opener，补 Spinner，Mermaid 降级代码块 |
| `components/ai-elements/conversation.tsx` | electron | 原样搬 |
| `components/ai-elements/reasoning.tsx` | electron | 削 electronAPI.openExternal |
| `components/ai-elements/context-divider.tsx` | electron | 原样搬 |
| `components/ai-elements/file-path-chip.tsx` | electron | **简化重写**：去 FileTypeIcon/preview-opener/ContextMenu，纯展示 chip |
| `components/ai-elements/tablet-mode-context.ts` | electron | 原样搬 |
| `components/agent/tool-utils.ts` / `tool-phrase.ts` | electron | 纯函数原样搬 |
| `components/agent/ContentBlock.tsx` | electron | **改造**：去 ImageLightbox/PreviewOpenButton/tool-result-renderers 富渲染，内联 parseTaskGetResult/parseTaskListResult/getTaskGetStatusLabel/formatDuration |
| `components/agent/SDKMessageRenderer.tsx` | electron | **大幅精简重写**：groupIntoTurns 改从 @profer/session-core 复用；去 model-logo/TaskProgressCard/ProcessBlockGroup/TurnFileChangesSummary/fork-rewind-retry-compact；user 改右对齐品牌色气泡 |
| `components/agent/AgentMessages.tsx` | electron | **精简重写**：去 ScrollMinimap/StickyUserMessage/ScrollPositionMemory/WelcomeEmptyState，内联 EmptyState/AgentRunningIndicator/RetryingNotice |
| `components/agent/live-group-set.ts` | electron | 原样搬 |
| `components/agent/tool-result-renderers/*`（index/collapsible-result/task-get-result/task-list-result） | electron | **精简重写**：去 @pierre/diffs + ImageLightbox，保留纯文本折叠 + 纯解析函数 |
| `atoms/ui-atoms.ts` | 新建 | 移动端极简 atom（thinkingExpanded/channels/userProfile/tabMinimapCache 等默认值） |

### 5.2 关键接线改动

- `components/agent/AgentView.tsx`：仅新增 `sessionModelId={sessionModelId}` 传给 AgentMessages（数据/WS 逻辑未动）。
- `components/agent/Composer.tsx`：**保留不动**（现有 textarea 输入已满足要求）。
- `docs/ui-port-map.md`：本迁移清单。

### 5.3 验证结果

- `bun run typecheck`：**0 错误**（通过）。
- `bun run build`：**通过**（1967 modules transformed）。
- 构建产物体积（含渲染内核 + katex 字体）：
  - JS：`833.20 kB`（gzip `256.06 kB`）
  - CSS：`91.51 kB`（gzip `18.77 kB`）
  - 另有 KaTeX 字体若干（~30-60 kB/个，数学公式渲染用）
- 基线体积无法精确对比（apps/tablet-client 此前无 dist 产物记录），增长主要来自 react-markdown + remark-math + rehype-katex（数学公式渲染），是「1:1 复现 md 渲染」的必要成本；已避开 mermaid/shiki/@pierre/diffs 等爆炸性依赖。

### 5.4 数据形状对齐结论（复述，最重要产出）

见 §0：两端共享 `@profer/shared` 类型 + `@profer/session-core` 的 groupIntoTurns + 同构的 atom 形状，**无需 adapter**。
tablet-client 的 `AgentStreamState`/`ToolActivity`/`applyAgentEvent`/`getActivityStatus`/`finalizeStreamingActivities`（src/atoms/agent.ts）与桌面 agent-atoms.ts 逐字段镜像，`lib/agentEvents.ts` 的 `handleAgentEvent`/`payloadToAgentEvents` 是桌面 payloadToLegacyEvents + useGlobalAgentListeners 的归并复刻，产物写入 `liveMessagesMapAtom`/`agentStreamingStatesAtom` 的形状与渲染内核消费一致。

### 5.5 遗留 & 后续可做（标部分未实现）

- `AgentHistorySelectionLayer`（桌面历史选择/复制粘贴图层）：未搬，移动端用不到。
- `rich-text-input.tsx`（TipTap 富文本输入/语音）：未搬，移动端保留 textarea Composer。
- Mermaid 图：目前降级为普通代码块（不渲染图），后续如需可引入 mermaid 走动态 import 延迟加载。
- 代码语法高亮：目前为单色深色 pre（无 Shiki），后续可引入 highlight.js 做轻量高亮。

---

## 6. 侧边栏迁移清单（LeftSidebar 子系统）

> 把桌面 `apps/electron/src/renderer/components/app-shell/LeftSidebar.tsx`（54 行薄壳）+
> `left-sidebar/` 9 个文件（~4481 行）迁移到移动端瘦客户端，1:1 复现原移动版侧边栏。

### 6.1 核心决策：不搬桌面 atoms，复用 tablet-client 现有 atoms

桌面 `use-left-sidebar.ts` 硬依赖 40+ 桌面专属 atom（`agent-atoms` / `chat-atoms` / `tab-atoms` /
`preview-atoms` / `browser-atoms` / `automation-atoms` / `identity-atoms` / `updater` / `environment` /
`system-prompt-atoms`）与 Electron IPC（`window.electronAPI.listAgentSessions` /
`listAgentWorkspaces` / `createAgentSession` / `deleteAgentSession` / `updateSettings` /
`team.acceptInvitation` / `reorderAgentWorkspaces` 等）及桌面多窗口导航（`navigationController` /
`useOpenSession` / `useSyncActiveTabSideEffects` / `shortcut-registry` / `detectIsMac`）。

**结论**：桌面 `use-left-sidebar.ts` 不适合硬搬（会因悬空 import 编译爆炸）。改用「复用 tablet-client
现有 atoms + 薄适配 hook」方案，桌面 atom 名 → 移动端 atom 名映射：

| 桌面 atom（agent-atoms 等） | tablet-client atom | 位置 | 说明 |
|---------------------------|-------------------|------|------|
| `agentSessionsAtom` | `sessionsAtom` | `src/atoms/session.ts` | 会话列表（上一轮已搬） |
| `agentWorkspacesAtom` | `workspacesAtom` | `src/atoms/session.ts` | 工作区列表 |
| `currentAgentSessionIdAtom` | `currentSessionIdAtom` | `src/atoms/session.ts` | 当前会话 |
| `currentAgentWorkspaceIdAtom` | `currentWorkspaceIdAtom` | `src/atoms/session.ts` | 当前工作区 |
| `agentChannelIdAtom` | `channelIdAtom` | `src/atoms/session.ts` | 默认渠道 |
| `agentModelIdAtom` | `modelIdAtom` | `src/atoms/session.ts` | 默认模型 |
| `userProfileAtom` | `userProfileAtom` | `src/atoms/ui-atoms.ts` | 用户档案（无 Electron 头像） |
| `agentSessionIndicatorMapAtom` | 本地派生 | `use-left-sidebar.ts` 内 `useMemo` | 从 `agentStreamingStatesAtom`（`agent.ts`）归并 running/completed/idle |
| `sidebarViewModeAtom` | 同名新建 | `src/atoms/sidebar-atoms.ts` | 归档视图切换 |
| `workspaceSortModeAtom` | 同名新建 | `src/atoms/sidebar-atoms.ts` | 项目排序 |
| `searchDialogOpenAtom` | 同名新建 | `src/atoms/sidebar-atoms.ts` | 搜索对话框开关 |

**两套 atom 冲突及处理**：
- 原 `ui-atoms.ts` 中已有一个 `channelsAtom`（`ChannelMeta[]`）和 `session.ts` 中的 `channelsAtom`
  （`ChannelInfo[]`）——**二者同名但职责不同**。侧边栏并不消费 `channelsAtom`（渠道列表只在设置/模型
  选择器里用），本次未触碰，避免破坏上一轮对话内核已接通的数据流。
- `userProfileAtom` 在 `ui-atoms.ts` 定义（`{ name/avatar/email }`），侧边栏直接复用，用首字母圆头像
  替代桌面 Electron `UserAvatar`（无真实头像数据源）。

### 6.2 迁移清单（源 → 目标 → 处理方式）

| 源文件（桌面 left-sidebar/） | 目标（tablet-client） | 处理 |
|---------------------------|---------------------|------|
| `sidebar-utils.ts`（123 行） | `src/components/sidebar/sidebar-utils.ts` | 原样搬 + 补 `sortAgentSessionsByUpdatedAtDesc`（从桌面 `lib/agent-session-list.ts` 内联） |
| `session-tree.ts`（137 行） | `src/components/sidebar/session-tree.ts` | 原样搬，把 `@/atoms/agent-atoms` 的 `SessionIndicatorStatus` 改为本地定义 |
| `session-items.tsx`（1369 行） | `src/components/sidebar/session-items.tsx` | **精简重写**：保留 `AgentSessionItem` / `DelegatedChildSessionItem` / `AgentProjectGroupItem` / `SessionItemActions` 视觉与交互；剔除 SessionMiniMapPopover（Electron）、browserStateMapAtom、拖拽排序 |
| `use-left-sidebar.ts`（1688 行） | `src/components/sidebar/use-left-sidebar.ts` | **大幅精简重写**：同上 atoms 复用 + ws-client 动作 |
| `expanded-sidebar.tsx`（578 行） | `src/components/sidebar/expanded-sidebar.tsx` | 精简重写：保留置顶/项目/归档/底部用户栏 visual token |
| `sidebar-dialogs.tsx`（151 行） | `src/components/sidebar/sidebar-dialogs.tsx` | 重写：删除/迁移用 AlertDialog 简化 |
| `LeftSidebar.tsx`（薄壳） | `src/components/sidebar/LeftSidebar.tsx` | 原样薄壳（无折叠 rail，始终展开态） |
| `SearchDialog.tsx`（桌面 app-shell） | `src/components/sidebar/SearchDialog.tsx` | 精简重写：只按标题过滤（无消息内容 IO 搜索） |
| `rail.tsx`（254 行） | **不搬** | 移动端无折叠窄栏（60px rail 无意义，触屏屏幕小） |
| `types.ts`（18 行） | 内联到 `LeftSidebar.tsx` | 简化 props（width / renderSearchDialog） |
| tablet `NativeTabletSidebar`（electron tablet/main.tsx） | `src/components/sidebar/NativeTabletSidebar.tsx` | 对齐抽屉+固定双形态（matchMedia landscape≥1024 判断） |

### 6.3 补搬的 ui 基建

| ui 组件 | 依赖 | 说明 |
|---------|------|------|
| `dialog.tsx` | `@radix-ui/react-dialog` | 原样搬 |
| `dropdown-menu.tsx` | `@radix-ui/react-dropdown-menu` | 原样搬 |
| `context-menu.tsx` | `@radix-ui/react-context-menu` | 原样搬（右键菜单） |
| `alert-dialog.tsx` | `@radix-ui/react-alert-dialog` | 原样搬（依赖已有 buttonVariants） |

### 6.4 stub / 未搬项及原因（红线约束第 5 条）

| 桌面依赖 | 处理 | 原因 |
|---------|------|------|
| `lib/navigation-controller` / `navigation-actions` / `shortcut-registry` | **no-op 不搬** | 桌面多窗口键盘导航 + 快捷键，移动端无键盘 |
| `lib/platform`（detectIsMac） | **不搬** | 移动端无 macOS 标题栏拖拽/红绿灯 |
| `hooks/useOpenSession` / `useCreateSession` / `useSyncActiveTabSideEffects` | **不搬** | 桌面 Tab 打开/副作用同步；移动端直接 `setCurrentSessionId` |
| `components/agent/MoveSessionDialog` / `CollapsedWorkspacePopover` / `diff/DiffTabContent` | **不搬** | 桌面专属弹窗；移动端用 AlertDialog 简化迁移 |
| `components/session-preview/SessionMiniMapPopover` / `chat/UserAvatar` | **不搬** | Electron 依赖；用首字母圆头像替代 |
| `components/app-shell/SidebarBalanceBar` / `ModeSwitcher` | **不搬** | 余额条（代管模式）/ Chat模式切换，移动端无 Chat 数据源 |
| `atoms/preview-atoms` / `browser-atoms` / `automation-atoms` / `identity-atoms` / `updater` / `environment` / `system-prompt-atoms` | **不搬** | 桌面专属数据，移动端侧边栏不展示 planning/skills/浏览器标记 |
| 项目拖拽排序（reorderAgentWorkspaces） | **不搬** | ws-client 无 reorder 命令 + 触屏拖拽体验差 |
| 项目 `updateAgentWorkspace`（重命名） | **stub 为 toast 提示** | ws-client 无 updateWorkspace 命令，留待后续 |

### 6.5 功能覆盖对照（交付物第 1~7 项）

| 要求 | 实现 | 状态 |
|------|------|------|
| 1. 会话列表分组（置顶/最近/归档 + 标题/高亮/时间/置顶图标/归档图标） | `expanded-sidebar.tsx` + `session-items.tsx` | ✅ |
| 2. 项目分组（按 workspaceId + 顶部项目切换） | `AgentProjectGroupItem` + `use-left-sidebar` 的 `agentProjectGroups` | ✅ |
| 3. 会话树形导航 + 右键/长按菜单（重命名/置顶/归档/删除） | `SessionItemActions`（DropdownMenu）+ `ContextMenu` + 委派子树 | ✅ |
| 4. 搜索对话框（按标题过滤） | `SearchDialog.tsx` | ✅ |
| 5. 新建会话按钮 | `handleNewAgentSession` → `client.createSession` | ✅ |
| 6. 底部用户头像 + 设置入口 | `expanded-sidebar.tsx` 底部（首字母圆头像，设置入口留 TODO） | ⚠️ 头像=首字母圆，设置入口占位 |
| 7. 抽屉 + 固定侧栏两形态 | `NativeTabletSidebar.tsx`（landscape≥1024 固定，否则抽屉+Escape 关闭） | ✅ |

### 6.6 验证结果

- `bun install`：新增 4 个 radix 依赖（dialog / dropdown-menu / context-menu / alert-dialog），22 packages。
- `bun run typecheck`：**0 错误**。
- `bun run build`：**通过**。
- 构建产物体积（本次 vs 上一轮基线）：
  - JS：`833.20 kB` → `936.49 kB`（gzip `256.06` → `285.21 kB`），**+103 kB**（radix 4 组件 + 侧边栏子系统）
  - CSS：`91.51 kB` → `102.78 kB`（gzip `18.77` → `20.31 kB`），+11 kB
