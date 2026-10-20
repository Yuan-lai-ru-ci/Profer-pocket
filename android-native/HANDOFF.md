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
