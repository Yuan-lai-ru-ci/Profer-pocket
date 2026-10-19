# Profer Android 原生版 · 会话与项目系统已接真实链路

> 状态：**可运行初版（会话/项目闭环）**。界面沿用 UI 基线；已接入 WebSocket、会话事件流、重连/心跳、前台服务与通知、本地快照与加密凭据。
> 会话与项目不再是只读展示：项目是一级列表（可展开/收起），会话是二级单行（名称 + 时间）；
> 项目内草稿会话、新建项目、重命名/置顶/标记未读/移动项目/删除都走桌面 `remote-service` 的真实命令，
> 并消费 `session_projection` 保持与电脑端同帧一致。归档会话不向移动端下发：归档是电脑端的整理动作，
> 手机不背负一份永远用不到的归档目录。
> 移动端不搬运工具入参与结果，工具调用只保留次数统计。
> 落点：`Profer-pocket/android-native/`；旧 Capacitor 壳 `pocket-app/` 保留为回退与协议来源。

## 1. 这一版想解决什么

桌面 Profer 是完整工作台，手机不是缩小版工作台。手机上只有三件事：

1. **看到** Mac 上的会话在做什么（消息、工具、进度）；
2. **决定** 需要人确认的事（权限、计划、选项）；
3. **发起** 新任务，并在离开时接收结果。

因此界面结构是：**一个对话页 + 一个从左侧滑出的会话抽屉 + 连接页 + 设置页**，没有底部导航。移动端底部导航适合并列的独立目的地（首页/搜索/我的），而 Profer 的主路径始终是「进入一个会话 → 对话」，会话切换属于导航抽屉，不属于底部 Tab。

## 2. 设计基线

### 2.1 色彩

品牌来自桌面 icon：黑底 + 米白几何块 + 中灰。移动端延续「暖纸 + 石墨」而不是 M3 默认紫。

| 角色 | 浅色 | 深色 |
|---|---|---|
| 背景 / surface | `#F7F4EF` | `#0B0D0E` |
| 卡片（低） | `#FCF9F3` | `#121415` |
| 卡片（高，选中） | `#ECE6DA` | `#1D2021` |
| 正文 | `#191816` | `#EDEAE3` |
| 次要文字 | `#6C6860` | `#9C9890` |
| 分割线 | `#E7E1D6` | `#24282A` |
| 主操作（墨色） | `#191816` / 文字 `#FFFDFA` | `#EDEAE3` / 文字 `#15161A` |
| 状态蓝灰（开关、进度） | `#4F5B68` | `#93A9BA` |
| 成功 / 警告 | `#3D7A57` / `#A9772C` | `#7FBF9A` / `#D8AE6A` |

规则：

- **主操作只有一支墨色**。发送键、允许、连接、新建都是墨色胶囊；其他按钮一律低对比容器。
- 开关等系统控件用蓝灰（tertiary），不跟主操作抢注意力。
- 状态色只表达状态，不参与品牌表达；错误使用 `errorContainer`，不拿墨色顶替。
- 不启用动态取色（dynamic color）：品牌一致性优先。

### 2.2 排版

全部映射到 M3 type scale，不另造字号：

- 正文 15sp / 行高 24sp（中文 1.6）；辅助最小 11sp（仅用于元信息）。
- 对话正文与用户气泡同一套 15sp/24sp，切换上下文不改变阅读节奏。
- 代码 12.5sp 等宽、横向滚动，不折行。
- 标题靠字号 + 颜色分层，不叠加重字重。
- **题名用衬线**（`ProferSerif` / `ProferTitleSerif` / `ProferHeadingSerif`）：品牌字、项目名、空态与弹层标题；
  正文与操作仍用无衬线。中文衬线不进入 14sp 以下的小字；深色下用 Regular，加粗会膨胀（2026-10-18）。

### 2.3 间距与形状

- 4dp 栅格：4 / 8 / 12 / 16 / 24 / 32。
- 页面左右 16dp；消息之间 24dp；同一消息内元素 8dp。
- 圆角只用四档：10（工具卡）、12（列表项）、14（卡片）、20/26（气泡与输入区）。
- 触控目标 ≥48dp（主流程：图标视觉 22dp，热区 48dp；发送键视觉 40dp，M3 触控区 48dp）；
  抽屉列表行 44dp（导航类，密度优先）。
- 抽屉宽度 = 屏宽 − 38dp（右侧留 30dp 卡片边缘 + 8dp 间隙）。

### 2.4 关键组件决策

| 组件 | 决策 | 原因 |
|---|---|---|
| 用户消息 | 右对齐气泡，20/20/6/20 圆角，`surfaceContainerHigh` | 与助手长文形成明确边界，但不使用高饱和品牌色块 |
| 助手消息 | 无气泡、通栏 15sp | 长文可读性优先；气泡会压缩行宽并增加视觉噪声 |
| 思考过程 | 默认收起，一行「思考了 N 秒」+ 展开正文 | 思考是过程信息，不抢占主答案 |
| 工具调用 | 折叠卡片：图标 + 名称 + 路径/统计 + 状态 | 运行中只让状态转，不整卡重排 |
| 权限请求 | 输入区上方一条紧凑提示 + `ModalBottomSheet` | 阻断性决策始终可点，但不常驻吃掉消息区；键盘弹起时仍完整可见 |
| 输入区 | 1–5 行自适应；发送键始终只负责发送，运行中的「停止」独立放在模型胶囊右侧 | 停止不该抢走发送入口，否则用户输入完发不出去 |
| 连接状态 | 只在断开时出现顶部细条；已连接不常驻 | 连接正常不是信息 |
| 消息流光标 | 行内细竖条，呼吸 720ms | 不做逐字弹跳，避免阅读抖动 |
| 抽屉头部 | 品牌字与目录规模同一行，图标热区 44dp | 规模是副信息，不单独占一行（省 31dp） |
| 底部工具区 | 发丝线 + 48dp 单行「书脚」，不做浮卡 | 浮卡常驻吃掉 100dp 以上，且与「书页」语言冲突 |
| 列表题名 | 项目名宋体 15sp Regular，会话名黑体 14sp | 一级/二级靠字形与缩进分层，不靠逐行分割线 |
| 上下文用量 | 顶栏 20dp 压力环 + 百分比；点开底部弹层（明细 / 77.5% 阈值 / 立即压缩） | 环表达压力，数字放弹层；输入区已有三颗胶囊，不再加第四颗 |

## 3. 页面清单

| 路由 | 内容 | 状态覆盖 |
|---|---|---|
| `chat` | Agent：顶栏、消息流、审批条与弹层、输入区、离线条、抽屉、回到最新；Chat：对话列表、对话页、输入区、复制、回到最新、离线条 | 正常 / 离线 / 空态 / 流式 / 审批中 / 键盘 / 会话切换 / Agent·Chat 切换 |
| `connect` | 已连接设备卡、手动地址、扫码入口、连接三步骤、失败重试 | 已连接 / 未连接 / 连接中 / 失败 |
| `settings` | 外观（跟随系统/浅色/深色）、通知、连接、关于、开发者（仅 debug） | 深浅即时切换 |
| `stress` | 240 条会话滚动检查（仅 debug） | 长列表 |

## 3.1 交互逻辑（已接真实链路）

- **抽屉机制（对齐 web 版 Pocket）**：对话区整块可水平拖动，右滑跟手拉出抽屉、左滑收起；
  抽屉宽 = 屏宽 − 38dp，展开时对话区被推到右侧、只留 30dp 的 28dp 圆角卡片边缘（带阴影），
  动画 300ms、曲线 `cubic-bezier(0.32, 0.72, 0, 1)` 与 web 的 `.sidebar-collapse-ease` 相同；
  关闭方式：点会话、左滑、返回键、顶部菜单按钮。
- **Agent / Chat 切换**：抽屉头部下 28dp 两段胶囊（Agent / Chat），切换时抽屉保持打开、只换目录；
  顶栏只显示当前标题，不再显示项目名。Chat 列表平铺对话，不按项目分组。
- **模型选择**：按渠道分卡片，组头带渠道图标与模型数；模型行缩进并带品牌图标；当前项整行浅底 + 勾选，
  当前渠道置顶。图标沿用桌面 `assets/models`，深色使用对应的 `_dark` 版本。
- **层级**：抽屉是一级项目列表（chevron + 项目名 + 会话数 + 运行数 + 新建），点项目展开二级会话；
  默认只展开当前会话所在项目与置顶组，其余收起；搜索时自动展开所有匹配项目。
- **项目顺序**：按最近活动倒序——取项目内非草稿、非归档会话的最大 `updatedAt`，空项目回退项目自身 `updatedAt`；
  置顶组始终最前，「未归入项目」始终最后。桌面侧栏的「默认/最近/名称」排序是桌面本地偏好，不下发移动端。
- **会话行**：单行 44dp，只显示名称与短时间（刚刚 / N 分 / N 小时 / N 天 / M月d日），
  不再为每行塞模型和第二行元信息；会话标题相对项目名缩进，层级一眼可见。
- **抽屉密度（2026-10-18）**：项目行 40dp、会话行 44dp，删除逐行分割线，列表左右边距 8dp；
  品牌行合并目录规模，底部改为单行书脚。360×640dp 机器上列表视口从 384dp 提到 503dp（+31%）。
  行高低于 M3 的 48dp 建议，只用于抽屉这种导航场景，聊天与审批主流程仍是 48dp。
- **归档边界**：`list_sessions` 不返回归档会话，移动端也没有归档入口；归档/恢复只在电脑端做。
  桌面归档某个会话时，`session_projection(archived=true)` 会把它从手机目录移出；取消归档且 revision 更新时恢复。
  归档项目同理：`list_workspaces` 不下发 `archived` 项目，项目里的会话也不会掉进「未归入项目」。
- **文件引用 chip**：助手正文里的文件路径（`remote-service.ts`、`src/main/app.kt:12`、绝对路径）
  渲染成与桌面 `FilePathChip` 同构的胶囊：文件类型徽标（TS/JS/PY/…）+ 文件名 + 行号；
  版本号、普通单词不会误判。文件路径内的代码块保持原样。
- **回合文件汇总**：本轮 `Edit / Write / MultiEdit / NotebookEdit` 触及的文件聚合成一排 chip，
  放在回复末尾（对应桌面 `TurnFileChangesSummary`）；失败工具的文件自动剔除，新用户回合清空。
  服务端 `sdk_message` 投影只透传 tool_use 的 `file_path`，不下发完整工具入参。
- **项目名 chip**：置顶组、未归入项目、搜索结果这些跨项目列表，会话行在标题后显示项目名胶囊
  （与 web 版 `workspace-badge` 一致）；项目组内不重复显示。
- **会话切换**：切换时打开对应会话，按 `session_detail + get_sdk_messages` 水合历史；草稿按会话保存在内存。
- **发送**：乐观气泡立即上屏，`send_message` 带 `clientMessageId` 发出；断线时入队，重连后原样重放。运行中的「停止」调用 `stop_agent`。
- **流式**：增量 token 在数据层以 60ms 窗口合并后推送，避免每个 token 触发一次重组；工具调用只显示「工具调用 N 次 + 分类次数」。
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
| 服务端 → 客户端 | `chat_event` | Chat 流式事件：`chat:stream:chunk / reasoning / complete / error` |

### 会话元数据字段

- `list_sessions`：`revision / createdAt / updatedAt / workspaceId / pinned / draft / completedButUnconfirmed(未读) / active`，
  只返回未归档会话；`archived` 字段仍保留在 item 形状里，供 projection 表达「刚被归档」。
- `session_projection`：桌面会话元数据的唯一变更出口；`operation=upsert|delete`，携带 `revision`。
  移动端按 `revision` 合并（旧投影不回退），删除写墓碑（迟到 upsert 不复活会话）。
- `session_detail`：`meta` 同样带 `workspaceId / pinned / archived / draft / completedButUnconfirmed / revision`。
- 创建类命令返回完整会话 item（同时保留 `sessionId / title / draft` 兼容旧客户端），移动端无需为新会话再拉全量列表。

移动端已接入 Chat（非 Agent）的**列表 / 打开 / 发送 / 流式 / 停止 / 新建 / 删除**；
附件上传、文件预览与扫码配对仍未实现。Chat 消息只渲染正文与推理；新对话以当前 Agent 模型为初始值，
每段对话绑定自己的 channel/model，输入区左侧的模型胶囊可随时切换并写回电脑端（生成中禁用）。
对话页沿用 Agent 页的阅读细节：贴底时跟随新消息、上滑阅读不抢滚动、右下角「回到最新」带未读数、
消息可复制、点击空白收起键盘；断线时顶部出现离线条并可重连。

### 重连与心跳

- 心跳每 25s 发 `{"type":"ping"}`；75s 无任何入站帧判定假死，主动断开重连。
- 断线后 2s 起指数退避，上限 60s；`close(4001)` 是 token 失效，停止重连并提示重新输入。
- 连接建立后先收 `hello`，再按本地游标发 `resume_agent_events`；服务端实例变化或
  `latestEventId < 本地游标` 时游标归零并重新拉快照。
- `eventId` 严格递增去重，重复帧直接丢弃。
- `send_message` 断线入队，重连后按原 `cmdId + clientMessageId` 重放；服务端按
  `clientMessageId` 幂等。队列只在内存中，进程被杀后不跨进程恢复（服务端幂等仍能防重复执行）。

### 流式合并与工具统计

- 助手 `_partial` 是累计全文：数据层每个会话只保留最后一条，60ms 窗口后统一推送。
- 工具调用按 `tool_use.id` 去重后聚合为 `ToolTally(total, byName)`；每个用户回合重新计数，
  UI 只显示「工具调用 N 次 / Read ×2 · Bash ×1」，不搬运入参与结果。

### 本地存储与凭据

- 地址明文保存；Token 用 Android Keystore 的 AES/GCM 加密后写入应用私有 SharedPreferences，
  解密失败按「无凭据」处理，绝不回退明文。Token 不进入日志与对话内容。
- 会话列表、工作区、事件游标、主题与开关各自持久化；冷启动先展示上次的会话快照。
  会话快照写在应用私有文件 `files/catalog-snapshot.json`（旧版 SharedPreferences 数据首次读取时自动迁移）：
  875 条会话的 JSON 不再让每次启动解析几百 KB 的 XML；快照读取在后台调度器执行，1s 防抖落盘。
- 连接 Token 只经 WS query 传输（与桌面 `checkToken` 对齐）；`usesCleartextTraffic=true`
  仅为局域网 `ws://`，远程隧道应使用 `wss://`。

### 前台服务与通知

- `PocketConnectionService` 是 `dataSync` 前台服务，只负责保活与后台通知；连接本身属于
  Application 持有的仓库，服务重建不会产生第二条链路。
- 两个通知渠道：`profer_keepalive`（低优先级常驻）与 `profer_messages`（高优先级事件）。
- 前台时抑制系统通知（界面直接渲染）；后台时权限/计划/提问/任务完成发通知。
  `run_completed` 与 `run_idle` 在 3s 窗口内同会话只提醒一次。
- 通知点击带 `sessionId`，`MainActivity` 单 Top 打开并跳到对应会话。
- 只在「已配置连接 + 用户开启后台连接 + 非凭据失效」时启动；关闭开关或 token 失效会停止服务。

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

# Debug 包
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.profer.pocket.native/com.profer.pocket.app.MainActivity
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

## 6. 已知限制

- **提问类事件不能在移动端回答**：`ask_user_request` 会进入待处理并显示提示，但回答仍需在电脑端完成（界面尚无多选项表单）。
- **未接扫码配对**：连接页扫码按钮仍是占位；首版靠手动输入地址与 Token。
- **发送队列不跨进程**：断线期间的待发消息只在内存中；进程被杀后需要用户重发（服务端 `clientMessageId` 幂等仍防重复执行）。
- **通知时机**：前台时抑制系统通知；若审批在前台到达、用户随后才切后台，不会补发通知。
- **项目只能创建/进入，不能重命名或删除**：桌面 `remote-service` 没有对应命令；移动端不提供假入口，项目整理仍在电脑端完成。
- **新建会话的模型不可选**：移动端默认取第一个启用渠道及其首个模型（可在电脑端换模型）；模型选择器是下一步。
- **Release 签名未配置**：`assembleRelease` 产出 unsigned APK，保持 fail-closed；发布前必须接正式签名与 `applicationId` 迁移方案。
- **归档只在电脑端管理**：移动端不接收归档会话，也没有归档/恢复入口；需要恢复请回电脑端。
- **启动耗时未达移动端标准**：本机 AVD（720×1280，负载较高）冷启动约 15–21s，主线程卡顿集中在 Compose 首次组合；
  已把目录映射移出主线程、快照改为文件异步读取、关闭态不组合会话抽屉，但**仍需在真机与空闲模拟器复测**，不能据此宣称启动已达标。
- **延迟未测量**：连接页显示「在线」而不是伪造毫秒数。
- **2.0× 大字号**：审批弹层内容自身需要滚动才能看到「拒绝」。
- **预测性返回**：已开启 `android:enableOnBackInvokedCallback` 并使用 Navigation Compose 2.8；模拟器无法用 `adb input swipe` 注入系统边缘手势，需真机确认动画。
- **TalkBack**：已给图标补 `contentDescription`、状态不单靠颜色，但未走完整读屏路径。
- **长按消息会叠加系统文本选择浮层**：模拟器上长按消息时，Android 自身还会弹出文本选择工具栏，与自定义菜单同时出现。后续需要在消息内容层消费长按事件或禁用选择。
