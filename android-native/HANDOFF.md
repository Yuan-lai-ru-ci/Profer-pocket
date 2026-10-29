# 原生 Android 并行开发交接

> 分支：`android-native/parallel-dev`（基于 `main@25f8c75`）
> 定位：与 `pocket-app/`（Capacitor 壳）**并行**的原生客户端，不替代旧壳，也不改变现有发布链路。
> 目标：先把「一个对话页 + 会话抽屉 + 连接页 + 设置页」跑通，再逐步把设计系统、状态与测试体系化。

## 1. 代码边界

```text
android-native/
  app/                    # 组装、DI、导航、前台服务、通知、图片解码
  core/model/             # 纯 Kotlin 数据模型，无 Android 依赖
  core/data/              # WebSocket、协议编解码、仓库、DataStore/Keystore
  core/designsystem/      # 颜色/排版/形状/间距 token 与通用组件
  feature/chat/           # Agent 对话、Chat 对话、输入区、弹层
  feature/sessions/       # 会话抽屉、项目分组、会话操作
  feature/connect/        # 连接页（手动地址 + Token）
  feature/settings/       # 设置页
```

约束：

- feature 之间不直接依赖；共享逻辑下沉到 `core/*`。
- UI 只消费 `PocketViewModel` 暴露的状态，不在 composable 里发请求、读磁盘或维护连接。
- 协议形状以桌面 `remote-service` 与 `src/renderer/pocket/` 为准；字段改动必须双端同步，不在移动端单方面加字段。
- 旧壳 `pocket-app/` 与桌面 `src/renderer/` 不属于本分支的修改范围。

## 2. 当前可运行范围

- 连接：手动输入地址 + Token，Keystore 加密保存，断线指数退避、心跳、网络恢复重连。
- 会话：项目一级分组、会话二级列表、草稿会话、新建项目/会话、重命名、置顶、未读、移动项目、删除。
- 对话：Agent 事件流、流式正文、思考折叠、工具调用摘要、权限审批、提问选项、图片附件、复制/重发/修改重发。
- Chat：与桌面 Chat 同步的普通对话，不创建 Agent 会话。
- 离线：顶部离线条、待发送队列落盘与恢复（仅 Agent 会话）。
- 通知：任务完成通知与前台服务；前台时抑制系统通知。
- 设置：主题、后台连接、连接入口、压力测试入口。

已知限制的完整列表见 `README.md` §6，交接时不要重复实现已有取舍。

## 3. 验证状态（2026-10-21）

已实际执行：

| 项 | 结果 |
|---|---|
| `:app:compileDebugKotlin` | 通过 |
| `./gradlew test` | 268 项，0 失败 |
| `:app:assembleDebug` | 通过 |
| 模拟器安装 + 冷启动 | 通过（`am start -W` 2.2s，进入已连接会话） |

2026-10-28 规范补强后复验（API 35 AVD，720×1280 / 320dpi）：

| 项 | 结果 |
|---|---|
| `./gradlew test` | 458 个用例（debug+release 双变体），0 失败 |
| `:app:lintDebug`（含 `checkDependencies`） | 0 errors，8 warnings（3 条为导航库 lint jar 版本告警，其余为未用/重复资源） |
| `:app:connectedDebugAndroidTest` | 2/2 通过（输入→发送、图标 48dp 热区） |
| 冷启动 + 抽屉开关 + 返回键 | 通过，返回键关抽屉不退出 App |
| 键盘弹起 | `mInputShown=true`，输入区在键盘上方，无遮挡 |
| 浅色 / 深色 | 截图 `screenshots/2026-10-28-audit/` |
| 2.0× 大字号 | 顶栏、抽屉行、输入区均无截断/重叠 |
| 240 条长列表 | 可滚动，位置稳定 |

未复验（仍受环境限制）：真实会话正文/工具卡/文件徽标的深色渲染——本轮桌面端 token 不可读，
没有可用的配对凭据，只验证到空会话 + 抽屉列表；下次有真机联调时补拍。

预测性返回：`keyevent BACK` 路径已验证（关抽屉、不退出 App）；左边缘手势在模拟器上
用 `adb shell input swipe` 不会触发系统返回手势（无 handler 时同样不退出），
需要在真机上手动确认回弹动画。

未验证：

- Release 签名包安装与升级（当前 release 为 unsigned，fail-closed）。
- 真机预测性返回、手势条/三键导航底部留白、折叠屏与分屏。
- TalkBack 全路径。
- 2.0× 大字号全页面（主路径此前过过，但本轮热区调整后未重拍全部页面）。
- 200 条以上会话滚动与流式长文性能。
- 真实桌面端联调（本轮只做了本机模拟器冒烟）。

### 构建环境注意

Android Studio 自带的 JBR 是 **Java 25.0.3**，当前 Kotlin 2.1.0 无法解析该版本号，会直接报 `25.0.3` 并中止。
本机可用 Homebrew `openjdk@21`：

```bash
cd android-native
JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home \
  ./gradlew :app:compileDebugKotlin test :app:assembleDebug
```

如果接手环境没有 JDK 21，安装 Temurin/Homebrew 的 JDK 17 或 21 即可；不要把 `local.properties` 提交上来。

## 4. 本轮 UI 规范化改动

- 共享可点击组件统一 48dp 热区，视觉尺寸保持紧凑：
  `ProferPill`、`ProferIconAction`、输入区选择器、消息操作、上下文用量、思考展开行、
  连接页断开/取消/重试、权限允许/拒绝、顶栏菜单与新建。
- `ProferIconAction` 对传入的 `size` 做下限保护，避免调用方再写出 44dp 热区。
- 移除 Typography 里的负字距，避免中文与大字号下字面挤压。
- M3 `ProferShapes` 收敛为 8/12/16/20/28；`ProferCardShape` 改为 16dp。
- 抽屉列表行、顶栏、搜索框等导航类控件从 40/44dp 提升到 48dp。

## 4.1 安卓规范补强（2026-10-28）

平台契约：

- **备份规则**：新增 `res/xml/backup_rules.xml`（API 26–30）与 `data_extraction_rules.xml`（API 31+），
  只备份 `profer_pocket_settings`。凭据、设备指纹、会话快照、草稿、待发送队列、已读位置
  既不进云备份也不随换机迁移 —— Keystore 密钥不迁移，搬过去也解不开。
- **前台服务类型**：API 34+ 改用 `remoteMessaging`，API 29–33 回落 `dataSync`。
  targetSdk 35 上 `dataSync` 有「24 小时内累计 6 小时」上限，长连接会被系统掐断；
  `remoteMessaging` 无此上限。清单同时声明两个类型与两个权限。
- **通知文案资源化**：渠道名/描述、常驻通知标题与正文进 `strings.xml`（渠道名常驻系统设置页）。
- **Debug StrictMode**：主线程磁盘/网络访问、未关闭 Closeable 只记日志，不崩溃。

Compose 规范：

- **IME insets 不再进组合**：`WindowInsets.ime.getBottom()` 原本在组合期直接读，键盘动画每一帧
  都会让整页重组；改成 `derivedStateOf { ime.getBottom(density) > 0 }`，只暴露「是否弹起」。
- **滚动副作用按键收敛**：`LaunchedEffect` 的 key 从整段草稿改成换行数，避免每敲一个字重启滚动。
- **预测性返回**：抽屉在 API 34+ 走 `PredictiveBackHandler`，手势进度直接驱动抽屉位移，
  取消回弹、完成收尾；更低版本保留 `BackHandler`（两个回调不能同时注册）。
- **触控热区**：待发送附件的移除按钮从 18dp 点击区改为 48dp 热区（视觉仍 18dp）。
- **无障碍**：`ProferIconAction`/发送键把 `contentDescription` 放在可点击节点上（原先在 22dp 图标子节点上，
  读屏焦点会落到一个没有点击动作的节点）；`ProferPill`/连接条动作补 `Role.Button`；
  `ProferPill` 暴露 `selected` 状态；`ProferSectionLabel` 加 `heading()`；
  输入区选择器在窄屏隐藏文字时仍保留 `contentDescription`；流式消息暴露「正在生成」`stateDescription`。
- **触感**：项目行/会话行长按弹菜单前给 `HapticFeedbackType.LongPress`（`rememberLongPressFeedback`）。
- **Modifier 参数位置**：`ChatScreen`、`ConversationScreen`、`ConversationBody`、`ProferComposer`
  的 `modifier` 提到第一个可选参数位置，消除 `ModifierParameter` 告警。

设计 token：

- 新增 `ProferFileBadgeColors`（亮/暗各一套）与 `ProferThemeExtras.fileBadges`，
  文件类型徽标不再写死品牌色；深色底用提亮版本，避免 2:1 对比度。
- 模型 logo 衬底色进 `ProferSemanticColors.modelLogoPlate`，特性模块不再出现 `Color(0xFF…)`。

构建与测试：

- `kotlinOptions` → `kotlin { compilerOptions { jvmTarget } }`。
- `lint { checkDependencies = true; checkReleaseBuilds = true; abortOnError = true }`，
  特性模块的问题在 app 构建阶段就会失败。
- 新增 `androidTest`：`ComposerInteractionTest` 覆盖「输入 → 发送」与「图标按钮 48dp 热区」。
- 清理 lint 告警：`commit()` → `apply()`、`PinnedTrustManager` 显式说明、
  去掉 minSdk 26 下的死分支、debug 导出 receiver 标注理由。

## 4.2 设置/连接页文案极简（2026-10-28）

原则：页面只留「标签 + 状态 + 操作」，解释性段落一律去掉；不可自明的信息压进 placeholder
或一行短提示。

设置页：

- 删除外观卡片下方整段配色说明；删除通知、自动重连、后台保持连接的副标题；
  删除「从 GitHub 检查正式版」兑底文案（`updateLabel` 有值才显示）；
  删除开发者入口的「240 条会话…」；连接行未连接时不再显示「去连接一台电脑」。
- `SettingsSwitchRow` / `SettingsActionRow` 的 `subtitle` 改为可空，空则不占位；
  行高仍保持 64dp，大字号下不会因为少一行而拥挤。

连接页：

- 删除 `Intro()`（大标题 + 「手机负责发起，执行留在电脑上。」）与底部「同一 Wi-Fi 下…」段落。
- 扫码入口去掉卡片外壳与两段说明，只留全宽主按钮 + 配对状态行。
- 手动填写面板删掉一百多字的说明段，改为一行「自签证书填指纹；无 TLS 的隧道用 ws://」；
  指纹 placeholder 缩为「证书指纹（自签必填）」。
- 「扫码连接另一台电脑」去掉副标题。

顺带：三处 `Icons.Outlined.ArrowBack` 换成 `Icons.AutoMirrored.Outlined.ArrowBack`（RTL 镜像）。

验证：`assembleDebug` / `test` / `lintDebug` 全过（0 error）；截图见
`screenshots/2026-10-28-audit/13–16`（设置页顶/底、连接页默认态、手动填写展开）。

## 4.3 点击反馈形状修正（2026-10-28）

Compose 的 `clickable` 指示层按节点边界绘制：**先 `clickable` 后 `clip`（或压根没 `clip`）时，
ripple 就是矩形**。约定：可点击的圆形/胶囊/文字按钮一律 `clip(形状)` 后再 `clickable`。

已修：

- `ProferIconAction`（顶栏菜单/更多/返回/关闭等 ≈20 处）—— 之前是 48dp 方角 ripple，现为圆形。
- 权限卡的「允许/拒绝」胶囊按钮（`ChatComponents`）—— `Surface` 的 shape 不管 ripple，需自己 clip。
- 连接页「断开连接 / 知道了 / 移除 / 重试」与已绑定设备行。
- 连接状态条的「重连」文字按钮。
- 思考过程折叠行。

保持矩形（符合 Material 列表项行为）：设置页条目、抽屉/弹层列表行、全宽提示条。

验证：`assembleDebug` / `test` / `lintDebug` 全过；按住截图见
`screenshots/2026-10-28-audit/17-ripple-icon.png`（图标按钮圆形）、`18-ripple-banner.png`（文字按钮圆角）。

## 4.4 子会话层级（2026-10-29）

桌面早已在 `buildSessionItem` 里下发血缘字段（注释写明「仅用于树形导航」），但安卓端一个都没解析，
委派子会话与探索分支在抽屉里就是平铺的普通会话。本轮补齐：

- `SessionSummary` 新增 `parentSessionId / sourceDelegationId / delegationRole / delegationStatus /
  delegationDepth / explorationParentSessionId / explorationSourceMessageId / explorationSourceLabel`，
  `merge()` 在字段缺失时保留旧值（一条普通 projection 不会把子会话从母会话下摘掉）；
  `FrameCodec.parseSessionSummary` 与 `SessionSnapshotStore` 同步解析/落盘。
- `SessionCatalog` 建树：`SessionGroup.items: List<SessionTreeItem>`，`sessions` 变成「根 + 子」的展平只读属性，
  现有调用方与计数不受影响。三条守卫对齐桌面 `session-tree.ts`：母会话不在可见集合时子会话升为根行、
  跨项目不归并、自己不能是自己的父会话。搜索时母会话命中会带上子会话。
- UI：母行下一条「N 个子会话」折叠行（48dp 热区、默认收起、当前子会话自动展开、运行中脉冲点），
  子行缩进 14dp + 角色胶囊（探索/调研/实现/审查/协作/探索分支）；置顶/搜索下子会话以根行出现时靠胶囊标出层级。
- 对话页：子会话顶栏多一行「角色 · 来自 母会话标题」面包屑，点击回母会话（`selectSession`，不新开页面）。
- 顺带修 2.0× 大字号下抽屉头部：Agent/Chat 开关固定 96dp 会折成两行，改成随字号缩放并单行不换行；
  ≥1.5× 时隐藏「Profer」品牌字，避免把搜索/新建挤出抽屉。

验证（API 35 AVD，720×1280 / 320dpi，debug 包，离线快照注入真实数据）：

| 项 | 结果 |
|---|---|
| `./gradlew test` | 498 项（debug+release 双变体），0 失败；新增目录建树 8 例、血缘解析 1 例、合并保留 5 例、UI 映射 5 例 |
| `:app:lintDebug` | 0 errors，23 warnings（全为依赖版本/资源重复告警，无新增代码告警） |
| `:app:assembleDebug` | 通过 |
| 浅色抽屉 | `screenshots/2026-10-29-subsession/02-drawer-light.png`（子会话展开、角色胶囊、运行点） |
| 深色抽屉 | `03-drawer-dark.png` |
| 2.0× 大字号 | `04-drawer-largefont.png`（开关单行、搜索/新建仍在、行内标题省略不重叠） |
| 折叠交互 | `05-drawer-expanded-exploration.png`（点「1 个子会话」展开探索分支） |
| 面包屑 | `06-after-tap.png` / `07-breadcrumb-to-parent.png`（点面包屑回到母会话，母会话无面包屑） |
| 280 条长列表 | `08`–`10`：上下各滚 5–8 屏，位置稳定、无空白/跳位 |

未验证：真实桌面联调（本轮没有可用配对凭据，抽屉数据来自本地快照注入，协议路径只由单测覆盖）。

## 5. 建议的系统化顺序

1. **设计 token 收敛**：把散落的 `RoundedCornerShape(10/14/26.dp)`、`size(34/36/40.dp)` 迁到
   `ProferShapes` / `ProferSizes`；建立「视觉尺寸 + 热区尺寸」两层 API，禁止页面直接写 44dp 点击区。
2. **四态组件化**：加载用骨架屏而不是整页转圈；空/错/离线统一走 `ProferEmptyState`、
   `ProferConnectionBanner` 一类组件，页面只提供文案与动作。
3. **测试体系**：当前只有 JVM 单测 + 手工截图。建议引入 Roborazzi/Paparazzi 做浅色/深色/大字号截图回归，
   再用 `createAndroidComposeRule` 覆盖发送、审批、断线重连三条主路径。
4. **无障碍**：TalkBack 全路径、语义合并、状态不只靠颜色；补 `contentDescription` 的回归检查。
5. **自适应布局**：≥600dp 双栏（列表 + 详情），横屏与折叠屏按同一断点处理；2.0× 全页面回归。
6. **发布链路**：release 签名、`applicationId` 与旧壳的迁移/共存方案、版本号策略。
7. **协议与数据层**：补齐 `FrameCodec` / `ChatReducer` / `HistoryMapper` 的边界用例；
   协议字段改动在桌面、原生、旧壳三端写清兼容策略。

## 6. 常用命令

```bash
cd android-native

# 编译
JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home \
  ./gradlew :app:compileDebugKotlin

# 单测
JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home \
  ./gradlew test

# 打包 + 安装 + 启动
JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home \
  ./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -W -n com.profer.pocket.native/com.profer.pocket.app.MainActivity
```

## 7. 交接约定

- 不要提交 `local.properties`、`screenshots/`、`build/`、`.gradle/`（均已在 `.gitignore`）。
- 改动 UI 时同时检查浅色/深色/2.0×；改动协议时同时检查桌面端与旧壳。
- 提交信息用 `feat(android):` / `fix(android):` / `refactor(android):` 前缀，便于和旧壳改动区分。
