# Profer Android 原生版 · 会话与项目系统已接真实链路

> 状态：**可运行初版（会话/项目闭环）**。界面沿用 UI 基线；已接入 WebSocket、会话事件流、重连/心跳、前台服务与通知、本地快照与加密凭据。
> 会话与项目不再是只读展示：项目是一级列表（可展开/收起），会话是二级单行（名称 + 时间）；
> 项目内草稿会话、新建项目、重命名/置顶/标记未读/移动项目/删除都走桌面 `remote-service` 的真实命令，
> 并消费 `session_projection` 保持与电脑端同帧一致。归档会话不向移动端下发：归档是电脑端的整理动作，
> 手机不背负一份永远用不到的归档目录。
> 回合结束后，思考与工具调用和桌面一样折成一行「执行过程：N 次工具调用，M 段思考」，
> 展开后才是逐条工具卡（显示工具名、目标与状态）；入参、结果与错误文本待桌面投影下发，字段契约见
> [`docs/tool-detail-projection-2026-11-02.md`](./docs/tool-detail-projection-2026-11-02.md)。
> 落点：`Profer-pocket/android-native/`；旧 Capacitor 壳 `pocket-app/` 保留为回退与协议来源。
> 并行开发分支与交接说明见 [`HANDOFF.md`](./HANDOFF.md)。
> 版本号规则与打包检查见 [`VERSIONING.md`](./VERSIONING.md)，改版本前先读。

## 1. 这一版想解决什么

桌面 Profer 是完整工作台，手机不是缩小版工作台。手机上只有三件事：

1. **看到** Mac 上的会话在做什么（消息、工具、进度）；
2. **决定** 需要人确认的事（权限、计划、选项）；
3. **发起** 新任务，并在离开时接收结果。

因此界面结构是：**一个对话页 + 一个从左侧滑出的会话抽屉 + 连接页 + 设置页**，没有底部导航。移动端底部导航适合并列的独立目的地（首页/搜索/我的），而 Profer 的主路径始终是「进入一个会话 → 对话」，会话切换属于导航抽屉，不属于底部 Tab。

## 2. 设计基线（2026-10-30 系统化重构）

参考 dsh-go 的信息层级、浮动控件和状态表达，但保持 Kotlin/Compose 原生实现、Profer 陶土品牌及现有业务契约；不复制其 Flutter 源码、图片或图标。完整规则见 [DESIGN_SYSTEM.md](./DESIGN_SYSTEM.md)。

### 2.1 色彩与材质

- 单一陶土主色；暖中性画布、内容面、浮动操作面三层。正文、状态和文件徽标均有亮暗语义角色。
- 浅色画布 `#F7F5F2`、内容面 `#FCFAF7`、浮层 `#FFFDFC`、正文 `#322F2A`、主操作 `#846558`。
- 深色画布 `#191715`、内容面 `#211F1C`、正文 `#ECEBE9`、主操作 `#B4998E`。
- `ProferBackdrop` 提供轻微同源色画布；`ProferChrome` 负责圆键/胶囊浮层。使用不透明材质，不做伪背景模糊。
- 所有角色取自 `core/designsystem/theme`。`UiDesignTokensTest` 锁定正文、次要文字和品牌操作的 AA 对比度。

### 2.2 排版、形状与触控

- 统一无衬线 M3 type scale，正文 15sp/24sp，辅助最小 12sp；代码 12.5sp 等宽且横向滚动。
- `ProferSerif` 等旧符号仅为兼容入口，字族已统一，不再混入独立衬线标题。
- 圆角使用 `MaterialTheme.shapes` 的 8/12/16/20/28dp 角色；胶囊及圆键用 CircleShape。
- 页面水平边距 16dp，组间 24dp，同组 8–12dp。触控目标至少 48dp，圆键视觉 40dp、图标 22dp。
- 大字号允许信息行变高、设置选项纵向排列；模型/预设入口保留单行省略，安全档位保留可辨认文本。

### 2.3 组件与状态

| 组件 | 统一规则 |
|---|---|
| 顶栏 | `ProferHeaderRow` 消费状态栏 inset，菜单/返回/操作使用同一圆键；Agent、Chat 保留各自业务槽位 |
| 消息 | 用户气泡 20dp 圆角；助手无气泡；思考、工具过程按需展开，长文优先 |
| 输入框 | 28dp 浮动胶囊，1–6 行；附件与发送键贴底且对齐；输入法/导航栏 inset 由输入组件拥有 |
| 发送/停止 | 无草稿时禁用发送；运行时原位停止，动作回调不因换皮改变 |
| 欢迎页 | 有会话时建议只填草稿、不自动发送；无会话时先新建；未连接时只有明确的连接恢复操作 |
| 会话抽屉 | 品牌与主操作一行，Agent/Chat 单独分段；标题和辅助信息纵向排布，保留项目/子会话结构 |
| 设置 | 分类 label + 分组内容面；主题纵向单选、开关整行可点、长版本信息可换行 |
| 连接 | 已连接详情、扫码/手动入口、真实连接骨架、错误原因和恢复动作；不编造连接步骤进度 |
| 审批 | 正文可滚动、底部决定固定，大字号仍可见全部决定 |

### 2.4 真实渲染与回归

- `@Preview` 覆盖浅色、深色及 2× 字号。
- debug 专用 `UiGalleryActivity` 提供不读取凭据、不发起连接、不写用户数据的真实 Compose 样本；release 不包含。
- 本轮截图、已验路径及未验范围见 [`docs/ui-acceptance-2026-10-30.md`](./docs/ui-acceptance-2026-10-30.md)。
- 无新增依赖；网络、协议、Repository、ViewModel、存储和前台服务未改。

## 3. 页面清单

| 路由 | 内容 | 状态覆盖 |
|---|---|---|
| `chat` | Agent：顶栏、消息流、审批条与弹层、输入区、离线条、抽屉、回到最新；Chat：对话列表、对话页、输入区、复制、回到最新、离线条 | 正常 / 离线 / 空态 / 流式 / 审批中 / 键盘 / 会话切换 / Agent·Chat 切换 |
| `connect` | 已连接设备卡、手动地址、扫码入口、连接骨架、失败重试 | 已连接 / 未连接 / 连接中 / 失败 |
| `settings` | 外观（跟随系统/浅色/深色）、通知、连接、关于、开发者（仅 debug） | 深浅即时切换 |
| `stress` | 240 条会话滚动检查（仅 debug） | 长列表 |

## 3.1 交互逻辑（已接真实链路）

- **抽屉机制（对齐 web 版 Pocket）**：对话区整块可水平拖动，右滑跟手拉出抽屉、左滑收起；
  抽屉宽 = 屏宽 − 38dp，展开时对话区被推到右侧、只留 30dp 的 28dp 圆角卡片边缘（带阴影），
  动画 300ms、曲线 `cubic-bezier(0.32, 0.72, 0, 1)` 与 web 的 `.sidebar-collapse-ease` 相同；
  关闭方式：点会话、左滑、返回键、顶部菜单按钮。
- **抽屉手势阈值（2026-10-28）**：松手吸附看「位移 64dp」或「甩动速度 400dp/s」，不再看进度过半——
  旧实现按 `progress ≥ 0.5` 换算到 360dp 屏上要横拖 180dp 才切换，手感上「滑很远才出侧边栏、滑很远才回主界面」。
  位移不够时回到拖动前的状态（关着就还是关着），不会因为「差一点」被反向吸走；
  手势挂在静止的外层容器上（挂内容层会让坐标系跟着位移，跟手变滞），
  移动事件被子组件消费（代码块横向滚动、列表纵向滚动）时不抢手势。
- **Agent / Chat 切换**：抽屉头部下至少 48dp 触控的两段胶囊（Agent / Chat），切换时抽屉保持打开、只换目录；
  顶栏只显示当前标题，不再显示项目名。Chat 列表平铺对话，不按项目分组。
- **模型选择**：按渠道分卡片，组头带渠道图标与模型数；模型行缩进并带品牌图标；当前项整行浅底 + 勾选，
  当前渠道置顶。图标沿用桌面 `assets/models`，深色使用对应的 `_dark` 版本。
- **层级**：抽屉是一级项目列表（chevron + 项目名 + 会话数 + 运行数 + 新建），点项目展开二级会话；
  首屏只展开当前会话所在项目，其余收起；项目内先铺预览（置顶/运行中/等人/未读/当前会话常显，
  其余补足到 5 条），超出的收进「显示更多（N）」；搜索时自动展开所有匹配项目并全部铺开。
- **项目顺序**：按最近活动倒序——取项目内非草稿、非归档会话的最大 `updatedAt`，空项目回退项目自身 `updatedAt`；
  手机端置顶的项目始终最前（标题旁带图钉徽标），「未归入项目」始终最后。桌面侧栏的「默认/最近/名称」排序是桌面本地偏好，不下发移动端。
- **会话状态（2026-11-02）**：不再用行尾小点，改成行首 3dp 竖条 + 行底淡色：
  等人确认（warning 琥珀）> 运行中（info 蓝）> 未读/已完成（success 绿），与桌面 `SessionIndicatorStatus` 同序；
  子会话行不加竖条，左侧分支图标按同一状态上色，状态同时写进无障碍 `stateDescription`。
- **会话行**：标题与短时间纵向排布，行高随字号增长；标题相对项目名缩进，保留子会话开关和长按操作。
- **子会话（2026-10-29）**：委派子会话（`parentSessionId + sourceDelegationId`）与探索分支
  （`explorationParentSessionId + explorationSourceMessageId`）挂在母会话下，不再作为项目里的平铺行。
  排布照桌面 `session-items.tsx`：开关长在母行上（标题后「⌄ 1/2 子会话」，48dp 热区、默认收起、
  当前打开的子会话自动展开、手动收起后不再抢回），子行统一收在一条 1dp 引导线下，
  行首用分支/分叉图标 + 委派状态色（运行中绿、已完成灰）表达血缘，不再挂文字胶囊。
  大字号下摘要退化为「1/2」，把宽度留给标题。子会话顶栏给出「角色 · 来自 母会话标题」面包屑，点一下回母会话。
  建树规则与桌面 `left-sidebar/session-tree.ts` 对齐：母会话不在可见集合（搜索只命中子会话）时子会话升为根行，
  跨项目不归并，单侧血缘字段的脏数据不归并。
- **抽屉密度**：不使用逐行分割线；项目、会话与模式切换都保留至少 48dp 热区。辅助信息采用 12sp，不再靠缩小点击区压密度。
- **归档边界**：`list_sessions` 不返回归档会话，移动端也没有归档入口；归档/恢复只在电脑端做。
  桌面归档某个会话时，`session_projection(archived=true)` 会把它从手机目录移出；取消归档且 revision 更新时恢复。
  归档项目同理：`list_workspaces` 不下发 `archived` 项目，项目里的会话也不会掉进「未归入项目」。
- **文件引用 chip**：助手正文里的文件路径（`remote-service.ts`、`src/main/app.kt:12`、绝对路径）
  渲染成与桌面 `FilePathChip` 同构的胶囊：文件类型徽标（TS/JS/PY/…）+ 文件名 + 行号；
  版本号、普通单词不会误判。文件路径内的代码块保持原样。
- **回合文件汇总**：本轮 `Edit / Write / MultiEdit / NotebookEdit` 触及的文件聚合成一排 chip，
  放在回复末尾（对应桌面 `TurnFileChangesSummary`）；失败工具的文件自动剔除，新用户回合清空。
  服务端 `sdk_message` 投影只透传 tool_use 的 `file_path`，不下发完整工具入参。
- **项目名 chip**：未归入项目、搜索结果这些跨项目列表，会话行在标题后显示项目名胶囊
  （与 web 版 `workspace-badge` 一致）；项目组内不重复显示。
- **会话切换**：切换时打开对应会话，按 `session_detail + get_sdk_messages` 水合历史；草稿按会话保存在内存。
- **发送**：乐观气泡立即上屏，`send_message` 带 `clientMessageId` 发出；断线时入队，重连后原样重放。运行中的「停止」调用 `stop_agent`。
- **流式**：增量 token 在数据层以 60ms 窗口合并后推送，避免每个 token 触发一次重组；工具调用按条渲染为卡片（名称 · 目标 · 状态）。
- **长按消息**：触觉反馈 + 菜单（复制 / 用户消息可重新发送），结果用主题化 Snackbar 提示。
- **设置开关**：主题、通知、权限提醒、自动重连、后台连接均持久化；关闭自动重连后断线直接进入 Offline。
- **连接页**：地址与 Token 分开输入；也支持把 `token` 放在地址 query 里，保存时会抽出 Token、地址中不保留凭据。
- **离线**：顶部横幅提供重连；已发送未确认的消息在内存队列中等待重放。
- **审批**：权限/计划请求到达时自动展开一次；允许 → `respond_permission`，批准计划 → `respond_exit_plan_mode`；提问类事件提示到电脑端回答。
- **通知**：后台时权限/计划/提问/任务完成发系统通知，点击落到对应会话。

## 4. 工程结构

```text
android-native/
  app/                    # Application、依赖容器、ViewModel、前台服务、通知、UI 映射
  core/model/             # 连接 / 会话 / 对话 / 设置领域模型（纯 Kotlin）
  core/data/              # 协议编解码、事件归约、WS 状态机、仓库、Keystore 凭据与本地快照
  core/designsystem/      # 色彩、排版、形状、Logo、聊天基础组件
  feature/chat/           # 对话页、Markdown 排版、预览数据
  feature/sessions/       # 会话抽屉/列表、空态、长列表压测页
  feature/connect/        # 连接页与四态
  feature/settings/       # 设置页
```

- feature 之间不互相依赖；跨页动作由 `:app` 组装，UI 只消费 `core:model` 映射后的既有模型。
- 所有页面都有 `@Preview`（浅色 / 深色 / 1.3× / 空态）。
- 预览数据只服务 `@Preview` 与 debug 压测页，不再进入运行期。

## 4.1 通讯链路

### 单一连接与状态

连接只有一份：`core/data` 的 `PocketConnection` 暴露 `StateFlow<ConnectionStatus>`，页面只消费。
状态是 sealed：`Idle / Connecting / Connected / Reconnecting / Offline`，`Offline` 再区分
`UserDisconnected / Unauthorized / MissingCredentials / Unreachable`。

`PocketRepository` 是数据层唯一入口：持有连接、归约会话事件、维护会话列表与本地快照，
向 UI 暴露 `connectionStatus / sessions / workspaces / chats / settings / notifications`。

### 协议（与桌面 `remote-service` 对齐）

| 方向 | 帧 | 说明 |
|---|---|---|
| 服务端 → 客户端 | `hello` | `serverInstanceId` + 事件游标范围；据此决定 resume 或重置游标 |
| 服务端 → 客户端 | `pong` | 心跳应答 |
| 服务端 → 客户端 | `agent_event` | `eventId + sessionId + payload`；payload.kind 分流 `sdk_message` / `profer_event` |
| 服务端 → 客户端 | `agent_events_resumed` | 补发结果；`requiresSnapshot` 时客户端重新拉元数据与历史 |
| 服务端 → 客户端 | `command_result` | `requestId/commandId + ok + data/error/conflict` |
| 客户端 → 服务端 | `ping` / `resume_agent_events` | 心跳与游标补发 |
| 客户端 → 服务端 | `list_sessions` / `list_workspaces` / `list_channels` | 会话抽屉与新建会话所需目录（`list_sessions` 不含归档） |
| 客户端 → 服务端 | `session_detail` / `get_sdk_messages` / `get_pending_interactions` | 打开会话时水合 |
| 客户端 → 服务端 | `send_message` / `stop_agent` | 发消息（带 `clientMessageId`）与停止 |
| 客户端 → 服务端 | `respond_permission` / `respond_exit_plan_mode` | 审批回应 |
| 客户端 → 服务端 | `rename_session` / `toggle_session_pin` | 会话标题与置顶（返回权威 item；归档只在电脑端） |
| 客户端 → 服务端 | `mark_session_read` / `mark_session_unread` | 已读/未读（`explicit=true` 才在 manual 策略下生效） |
| 客户端 → 服务端 | `move_session_to_workspace` / `delete_session` | 移动项目 / 删除会话 |
| 客户端 → 服务端 | `create_session` / `ensure_project_draft_session` / `create_workspace` | 全局新建、项目草稿复用、新建项目 |
| 客户端 → 服务端 | `list_conversations` / `get_conversation_messages` / `create_conversation` / `update_conversation_model` | Chat 列表、历史、新建、切换对话模型 |
| 客户端 → 服务端 | `chat_send_message` / `chat_stop_generation` / `delete_conversation` | Chat 发送、停止、删除 |
| 服务端 → 客户端 | `chat_event` | Chat 流式事件：`chat:stream:chunk / reasoning / tool-activity / complete / error` |

### 会话元数据字段

- `list_sessions`：`revision / createdAt / updatedAt / workspaceId / pinned / draft / completedButUnconfirmed(未读) / active`，
  只返回未归档会话；`archived` 字段仍保留在 item 形状里，供 projection 表达「刚被归档」。
- `session_projection`：桌面会话元数据的唯一变更出口；`operation=upsert|delete`，携带 `revision`。
  移动端按 `revision` 合并（旧投影不回退），删除写墓碑（迟到 upsert 不复活会话）。
- `session_detail`：`meta` 同样带 `workspaceId / pinned / archived / draft / completedButUnconfirmed / revision`。
- 血缘字段（仅用于树形导航，不下发委派内容）：`parentSessionId / sourceDelegationId / delegationRole /
  delegationStatus / delegationDepth / explorationParentSessionId / explorationSourceMessageId /
  explorationSourceLabel`。移动端只用来把子会话挂回母会话与渲染角色胶囊；离线快照同样落盘这些字段。
- 创建类命令返回完整会话 item（同时保留 `sessionId / title / draft` 兼容旧客户端），移动端无需为新会话再拉全量列表。

移动端已接入 Chat（非 Agent）的**列表 / 打开 / 发送 / 流式 / 停止 / 新建 / 删除**；
附件上传、文件预览与扫码配对仍未实现。Chat 消息渲染正文、折叠的推理与逐条工具活动（与桌面同序：
工具行 → 思考 → 正文，工具行点开看参数/结果）；新对话以当前 Agent 模型为初始值，
每段对话绑定自己的 channel/model，输入区左侧的模型胶囊可随时切换并写回电脑端（生成中禁用）。
对话页沿用 Agent 页的阅读细节：贴底时跟随新消息、上滑阅读不抢滚动、右下角「回到最新」带未读数、
消息可复制、点击空白收起键盘；断线时顶部出现离线条并可重连。

### 重连与心跳

- 心跳每 25s 发 `{"type":"ping"}`；75s 无任何入站帧判定假死，主动断开重连；连上时立刻发一次 ping，
  ping/pong 往返即连接页显示的真实延迟（没测到就是「在线」，不编造毫秒数）。
- 断线后 2s 起指数退避，上限 60s；`close(4001)` 是 token 失效，停止重连并提示重新输入。
- **网络状态感知（2026-10-20）**：宿主 `ConnectivityManager` 回调推给连接层，
  断网立即关链路并进入「手机网络已断开」，不再等 75s 假死；网络恢复立刻重连并清零退避，
  没网期间只留 60s 兜底重试，不按 2s/4s/8s 空转。关闭「自动重连」时网络恢复也不会自动连。
- **回前台强制刷新**：回前台时若链路还在但超过 75s 没有入站帧（Doze / 后台常见），
  直接重建 socket，不再等下一个心跳周期。
- 连接建立后先收 `hello`，再按本地游标发 `resume_agent_events`；服务端实例变化或
  `latestEventId < 本地游标` 时游标归零并重新拉快照。
- `eventId` 严格递增去重，重复帧直接丢弃。
- `send_message` 断线入队，重连后按原 `cmdId + clientMessageId` 重放；服务端按
  `clientMessageId` 幂等。**队列已落盘**（`files/outgoing-queue.json`，按 endpoint 归属）：
  进程被系统回收后重启仍会恢复并重放；换到另一台电脑（endpoint 变化）时旧队列丢弃，不会投错机器。
- 离线条文案区分「正在连接 / 手机网络已断开 / 等待 Ns 重试 / Token 失效 / 还没有配置」，
  并带「N 条待发送」。

### 流式合并与工具统计

- 助手 `_partial` 是累计全文：数据层每个会话只保留最后一条，60ms 窗口后统一推送。
- 工具调用按 `tool_use.id` 去重后聚合为 `ToolTally(total, byName)`，用于回合摘要与抽屉；
  逐条工具卡读助手消息自己的调用列表，展示「名称 · 目标 · 状态」，入参与错误文本等投影下发后再接。
- 思考与工具调用同属一轮执行过程：流式中展开，回合结束后自动收起为一行「执行过程」，
  用户手动展开/收起后不再自动切换（见 `docs/process-group-2026-11-04.md`）。

### 长会话内存与滚动

- **消息投影缓存**：`UiMapping.chatState` / `conversationUiState` 以「entry 引用 + 在线/运行/工具状态」命中缓存，
  流式 token 只重建变化的那条；表没变时连 `List` 实例都复用，`StateFlow` 去重与 `remember(messages)` 都不白跑。
- **会话正文只留最近 5 条**（`MAX_LOADED_SESSIONS`）：在跑 / 等人 / 后台有任务 / 有待发消息 / 当前打开的会话不回收，
  其余下次打开重新水合。
- **图片**：解码前按 1440px 降采样，缓存换成字节上限的 `LruCache`（`min(堆/8, 32MB)`）；
  附件大图预览也在后台线程解码（上限 2048px）。
- 细节、基准数字与真机采样方法见 `docs/memory-scroll-2026-11-03.md`。

### 本地存储与凭据

- 地址明文保存；Token 用 Android Keystore 的 AES/GCM 加密后写入应用私有 SharedPreferences，
  解密失败按「无凭据」处理，绝不回退明文。Token 不进入日志与对话内容。
- 会话列表、工作区、事件游标、主题与开关各自持久化；冷启动先展示上次的会话快照。
  会话快照写在应用私有文件 `files/catalog-snapshot.json`（旧版 SharedPreferences 数据首次读取时自动迁移）：
  875 条会话的 JSON 不再让每次启动解析几百 KB 的 XML；快照读取在后台调度器执行，1s 防抖落盘。
- 连接 Token 只经 WS query 传输（与桌面 `checkToken` 对齐）；`usesCleartextTraffic=true`
  仅为局域网 `ws://`，远程隧道应使用 `wss://`。
- 待发队列从 `files/outgoing-queue.json` 恢复，只保存「还没发出去」的消息；
  已收到 `command_result` 的消息不会写入。文件读写都是小 JSON，写入做 150ms 防抖。
- Keystore 解密结果在内存缓存：连接页每次重组、每个连接状态变化都会问「有没有保存过 token」，
  不能每次都进 Keystore；`save` / `clear` 同步更新缓存。

### 前台服务与通知

- `PocketConnectionService` 是 `dataSync` 前台服务，只负责保活与后台通知；连接本身属于
  Application 持有的仓库，服务重建不会产生第二条链路。
- 两个通知渠道：`profer_keepalive`（低优先级常驻）与 `profer_messages`（高优先级事件）。
- 前台时抑制系统通知（界面直接渲染）；后台时权限/计划/提问/任务完成发通知。
  `run_completed` 与 `run_idle` 在 3s 窗口内同会话只提醒一次。
- 通知点击带 `sessionId`，`MainActivity` 单 Top 打开并跳到对应会话。
- 只在「已配置连接 + 用户开启后台连接 + 非凭据失效」时启动；关闭开关或 token 失效会停止服务。
- **启动顺序（2026-10-20）**：服务启动一律放到后台调度器，且要求「进程在前台 + 首帧已画出」；
  前台用 `startService`（不用 `startForegroundService`），服务在自己的 `onCreate` 里立刻
  `startForeground`。冷启动 10s+ 的设备上，`startForegroundService` 的 5s 硬约束会被主线程
  首次组合拖爆（`ForegroundServiceDidNotStartInTimeException`，已经实测复现并修掉）。
  服务状态做去重，连接状态每变一次不再重复跨进程请求。

## 5. 构建与验证

### 5.1 Debug 命令接口（仅 debug 构建）

不靠点按 UI 驱动链路。`app/src/debug/` 下有一个 exported receiver，release 不会打包：

```bash
# 1) 把 token 写进应用私有目录：只走 stdin，不把 token 放进 adb 参数/日志
python3 -c "import json,os;print(json.load(open(os.path.expanduser('~/.profer/remote-token.json')))['token'],end='')" \
  | adb shell "run-as com.profer.pocket.native sh -c 'cat > files/debug-token'"

# 2) 连接（5.0 模拟器访问宿主机用 10.0.2.2；真机用桌面显示的局域网 IP）
adb shell am broadcast -n com.profer.pocket.native/com.profer.pocket.app.debug.DebugCommandReceiver \
  -a com.profer.pocket.DEBUG --es cmd connect \
  --es endpoint 'http://10.0.2.2:7789' --es token_file debug-token

# 3) 读状态 / 列会话 / 打开会话 / 发消息 / 审批 / 断开
adb shell am broadcast -n com.profer.pocket.native/com.profer.pocket.app.debug.DebugCommandReceiver \
  -a com.profer.pocket.DEBUG --es cmd state --es session_id <id> --ez include_text true
adb shell am broadcast -n com.profer.pocket.native/com.profer.pocket.app.debug.DebugCommandReceiver \
  -a com.profer.pocket.DEBUG --es cmd sessions
adb shell am broadcast -n com.profer.pocket.native/com.profer.pocket.app.debug.DebugCommandReceiver \
  -a com.profer.pocket.DEBUG --es cmd open --es session_id <id>
adb shell am broadcast -n com.profer.pocket.native/com.profer.pocket.app.debug.DebugCommandReceiver \
  -a com.profer.pocket.DEBUG --es cmd send --es session_id <id> --es text 'ping'
adb shell am broadcast -n com.profer.pocket.native/com.profer.pocket.app.debug.DebugCommandReceiver \
  -a com.profer.pocket.DEBUG --es cmd disconnect

# 结果统一在 logcat，tag=ProferDebug，一行 JSON，不含 token / 消息正文（include_text 仅显式开启）
adb logcat -s ProferDebug
```

注意：必须用 `-n` 指定组件；Android 8+ 对 manifest 隐式广播不投递。该 receiver 是 exported 且能发消息/审批，**只装在开发机**，不要拿 debug 包当日常包。调试完成后删除明文 token 文件：

```bash
adb shell "run-as com.profer.pocket.native rm -f files/debug-token"
```

### 5.2 构建

```bash
cd /Users/mac/profer/Profer-pocket/android-native
export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home

# 数据层单测（协议、归约、退避/心跳、流合并）
./gradlew :core:data:testDebugUnitTest

# Debug 包：包名带 .debug 后缀（com.profer.pocket.debug），可与正式版共存，
# 两者签名不同，同包名会装不上。
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.profer.pocket.debug/com.profer.pocket.app.MainActivity
adb exec-out screencap -p > screenshots/xxx.png

# Release 包：当前未配置签名，产物是 unsigned（fail-closed，不可直接安装）
./gradlew :app:assembleRelease
```

本机事实：SDK 只有 platform 35；系统无独立 JDK，Homebrew `openjdk@21` 可用；AVD `Profer_Pocket_API_35`（720×1280 @320dpi = 360×640dp）。`local.properties` 指向 `/Users/mac/Library/Android/sdk`，不入库。

联调记录（2026-10-18，AVD + 本地 mock WS 服务端）：
连接/hello/resume/会话列表/历史水合、发送与流式合并、工具次数、权限弹层与回应、
后台权限通知与点击跳转、服务端重启后自动重连、断网 75s 假死检测与恢复后重连均已实测。

真实桌面联调（2026-10-18，AVD → 宿主机 Profer remote-service，端口 7789）：
- `connect` 后 hello 返回 `serverInstanceId`，会话列表从快照的 1 条刷新为桌面 873 条；
- 打开真实会话拉到 120 条消息（115 条 assistant），历史与工具次数正常；
- 新建测试会话后发送一条消息，收到 assistant 回复，随后 `running=false`、`toolTotal=0`；
- 测试会话 `5eff7b25-8b78-4180-bdc3-54ef7516fe44` 留在桌面，未自动删除；
- 明文 token 文件已从应用私有目录删除，凭据仍由 Keystore 加密保存。

本轮会话/项目联调（2026-10-18，AVD → 宿主机 Profer remote-service，端口 7789）：
- 抽屉展示真实项目分组（esp32/CDUTAI/…）与真实会话；空项目显示「还没有会话 / 在此新建」；
- 项目内新建草稿会话：桌面 `agent-sessions.json` 出现 `draft=true` 且 `workspaceId` 指向该项目，渠道/模型已带上；
- 新建项目：桌面 `agent-workspaces.json` 真实新增；测试后已清理（测试会话与项目均未留在用户数据里）；
- 重命名/置顶/取消置顶/标记未读/已读/移动项目/删除均回写桌面 index 并即时反映在列表；
- 第二条 WS 连接修改标题后，移动端通过 `session_projection` 无需刷新即显示新标题；
- `list_sessions` 改为只返回未归档会话（本轮桌面进程需重启后生效）；移动端目录从 875 条降到约 190 条；
- 抽屉改为项目一级 + 会话二级：项目行显示会话数/运行数/新建入口，点开才显示单行会话；搜索自动展开匹配项目。
- 本轮新增单测：会话目录分组/排序/空项目/搜索、归档投影移出与取消归档恢复、projection revision 合并与删除墓碑、
  目录空列表信任、置顶/移动/标记已读/创建项目/项目草稿命令回填（`core:data` 61 项全通过）。

真机（熄屏/切后台保活、通知冷启动、Android 13+ 通知权限、`wss://` 隧道）仍未实测。

本轮（2026-10-20）连接层优化实测（AVD + 本机最小 WS 桩服务，端口 7790，脚本不随仓库提供）：
- 连接页显示 ping/pong 往返（实测 25ms），不再是固定「在线」；
- 关闭 wifi + 移动数据：顶部立刻变成「手机网络已断开」，不等 75s 心跳假死；
- 断网状态发送：离线条显示「手机网络已断开 · 1 条待发送」，队列写入 `files/outgoing-queue.json`；
- 强杀进程后重启（仍断网）：恢复为「手机网络已断开 · 1 条待发送」；
- 恢复网络：自动重连并重放，桩服务收到同一 `clientMessageId` 的 `send_message`，队列文件清空；
- 恢复网络后的重试从 2s 重新起算，不再沿用断网前的 32s 退避；
- 冷启动连续 3 次：无 `ForegroundServiceDidNotStartInTime` / `Bringing down service`（修复前可复现）；
- 单测：全模块 226 项通过（`core:data` 91 项，其中连接层 19 项）。

另修：`feature/chat` 的 `model_deepgemini.png` 实际是 JPEG 内容，release 资源编译（AAPT）直接失败；
已转成真正的 PNG（256 色，9KB），`assembleRelease` 恢复可用（产物仍是 unsigned）。

UI 轮（2026-10-20，移动版 UI 岗位）实测：
- 2.0× 字号：输入区三个选择器由单行改 `FlowRow`，不再出现「deepse…」「执…」；
  审批弹层正文自行滚动、三个动作固定在底部（「拒绝」始终可见），按钮改 `heightIn(min = 48.dp)` 不裁字；
  空态主按钮去掉 44dp 固定高度（2.0× 会切掉字形）；
- 触控：抽屉齿轮 44→48dp，选择器胶囊视觉不变但热区 48dp，对话/对话页顶栏图标 44→48dp（环形指示器 40×48，宽度受视觉约束）；
- 截图：浅色 1.0×、深色 1.0×、浅色 2.0×（空态 / 对话 / 审批弹层）均已实拍，见会话 `.context/shots/`。

## 5.3 弹层高度（2026-10-20）

- 所有底部弹层（模型 / 预设 / Chat 工具 / Agent 提问 / 新建会话 / 移动到项目）最高到屏幕 86%：
  内容短时仍贴内容高度，长列表最多到 86% 后自己滚动。弹层不再铺满整屏、不再吞掉状态栏。
- 弹层顶部的状态栏内边距也一并去掉（`contentWindowInsets = 0`），底部由内容自己的
  `navigationBarsPadding()` 负责；拖到最高时把手与标题之间不再多出一段空白。
- 理由：铺满整屏后弹层看起来像「换了一页」，状态栏被压在弹层上，顶部只剩空白；
  留出 14% 的遮罩既是空间参照，也保住「这是浮层」的语义。

## 6. 已知限制

- **未接扫码配对**：连接页扫码按钮仍是占位；首版靠手动输入地址与 Token。
- **Chat（非 Agent）断线不排队**：`chat_send_message` 走请求/响应，断线时直接报「发送失败」，
  不会像 Agent 会话那样落盘重放；Agent 队列不覆盖 Chat。
- **通知时机**：前台时抑制系统通知；若审批在前台到达、用户随后才切后台，不会补发通知。
- **项目只能创建/进入，不能重命名或删除**：桌面 `remote-service` 没有对应命令；移动端不提供假入口，项目整理仍在电脑端完成。
- **新建会话的模型不可选**：移动端默认取第一个启用渠道及其首个模型（可在电脑端换模型）；模型选择器是下一步。
- **Release 签名未配置**：`assembleRelease` 产出 unsigned APK，保持 fail-closed；发布前必须接正式签名与 `applicationId` 迁移方案。
- **归档只在电脑端管理**：移动端不接收归档会话，也没有归档/恢复入口；需要恢复请回电脑端。
- **子会话只有导航，没有委派面板**：母会话下的「N 个子会话」折叠行、角色胶囊与顶栏面包屑已接；
  但 `delegation_blocked`（子会话要审批/提问）与委派进度条尚未渲染——这类阻塞目前只能在电脑端处理。
  子会话默认收起，不会把抽屉刷满；需要看某个子会话就跑一次折叠行。
- **启动耗时未达移动端标准**：本机 AVD（720×1280，宿主负载 6–11）冷启动仍在 10–16s，主要成本是 debug 包首次组合
  + 模拟器 dex 校验；已把「前台服务启动/凭据读取/草稿与阅读位置读盘」移出首帧路径（原先 `startForegroundService`
  会在主线程阻塞并直接触发 5s FGS 超时崩溃），但**仍需在真机与空闲模拟器复测**，不能据此宣称启动已达标。
- **上下文环需要桌面 ≥ 0.16.11**：桌面 0.16.10 的 Pocket 投影只发 assistant 的 role/content/model/stop_reason，
  **把 usage 丢在出口**（会话 JSONL 里有，WS 上没有），移动端拿不到分子 → 顶栏环是空的。
  0.16.11 的 `projectUsageForPocket` 已补上；移动端同时修了「历史水合不带 usage/window」的缺口，
  两个都到位后重开会话即可看到占用。**不能用 result.usage 兜底**：那是整轮累计求和（本机实测一轮 22 万+
  cache_read），会把进度环顶到虚高。
- **连接页延迟来自 ping RTT**：只在心跳/握手时测，未做持续探测；断开后清零，不展示旧数字。
- **2.0× 大字号**：主路径已过（输入区选择器换行、审批弹层动作固定在底部、空态按钮不再裁字）；
  仍有少量次要控件按 1.0× 设计（如抽屉 40/44dp 行高），大字号下只保证不截断，不再保证同样紧凑。
- **预测性返回**：已开启 `android:enableOnBackInvokedCallback` 并使用 Navigation Compose 2.8；模拟器无法用 `adb input swipe` 注入系统边缘手势，需真机确认动画。
- **TalkBack**：已给图标补 `contentDescription`、状态不单靠颜色，但未走完整读屏路径。
- **长按消息会叠加系统文本选择浮层**：模拟器上长按消息时，Android 自身还会弹出文本选择工具栏，与自定义菜单同时出现。后续需要在消息内容层消费长按事件或禁用选择（未修）。
- **前台也常驻保活通知**：服务策略仍是「连接存在就保活」，应用在前台时通知也在；若要去掉，需要改成只在后台启动
  （Android 12+ 后台启动前台服务有限制，需单独验证）。
