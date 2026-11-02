# 2026-10-30 安卓 UI 系统化重构 · 实机验收

> 截图保存在本地 `screenshots/2026-10-30-system/`（该目录被 `.gitignore` 排除，不入库）；
> 本文与 `DESIGN_SYSTEM.md` 入库，供后续回归对照。

> 设备：`Profer_Pocket_API_35`（720×1280 / 320dpi，Android 15 模拟器）
> 截图全部来自 `adb exec-out screencap -p`，样本来自 debug 专用 `UiGalleryActivity`
> （不读取凭据、不发起连接、不写用户数据；release 不包含该页面）。

## 截图

| 文件 | 页面 | 状态 |
|---|---|---|
| `chat-light.png` | Agent 对话 | 浅色 1.0× |
| `chat-dark.png` | Agent 对话 | 深色 1.0× |
| `chat-large.png` | Agent 对话 | 浅色 2.0× |
| `chat-keyboard.png` | Agent 对话 | 键盘弹起、输入中 |
| `composer-light.png` | 输入条 | 空态：40dp 附件键 + 淡底发送键 |
| `composer-typed.png` | 输入条 | 有字：发送键变实心主色 |
| `composer-sheet.png` | 输入条 | 「+」弹层：添加 / 本轮两组 |
| `composer-sheet-dark.png` | 输入条 | 深色弹层同结构 |
| `drawer-header.png` | 抽屉头部 | 1×：模式胶囊 / 搜索 / 新建同为 40dp 齐平 |
| `drawer-header-dark.png` | 抽屉头部 | 深色同几何 |
| `drawer-header-130.png` | 抽屉头部 | 1.3×：模式胶囊落到第二行，品牌字完整 |
| `empty-light.png` | 新会话欢迎 | 浅色、有草稿目标、建议胶囊 |
| `offline-light.png` | Agent 对话 | 离线横幅 + 重连入口 |
| `sessions-light.png` | 会话列表（独立页） | 浅色 |
| `sessions-large.png` | 会话列表（独立页） | 浅色 2.0× |
| `drawer-light.png` | 会话抽屉 | 浅色 |
| `drawer-dark.png` | 会话抽屉 | 深色 |
| `drawer-large.png` | 会话抽屉 | 浅色 2.0× |
| `settings-light.png` | 设置 | 浅色 |
| `settings-dark.png` | 设置 | 深色 |
| `settings-large.png` | 设置 | 浅色 2.0× |
| `connection-light.png` | 连接 | 已连接 |
| `connection-dark.png` | 连接 | 深色已连接 |
| `connection-large.png` | 连接 | 浅色 2.0× |
| `failure-light.png` | 连接 | 失败原因 + 重试 |
| `loading-light.png` | 连接 | 连接骨架 + 取消 |
| `permission-large.png` | 审批弹层 | 浅色 2.0×，动作固定底部 |

## 实际走过的路径

| 项 | 结果 |
|---|---|
| `:app:compileDebugKotlin` / `assembleDebug` | 通过 |
| `./gradlew test` | 502 项，0 失败 |
| `:app:lintDebug` | 0 errors（48 warnings，均为既有依赖/资源类提示） |
| `:app:assembleRelease` | 通过（未签名包） |
| `:app:connectedDebugAndroidTest` | 10/10 通过 |
| 冷启动 / 进入对话 | 通过 |
| 键盘弹起 | `mInputShown=true`；输入框与发送键在键盘上方，无遮挡 |
| 键盘返回 | 返回后 `mInputShown=false` |
| 抽屉打开 | 菜单点击后显示会话列表 |
| 边缘返回 | 右侧边缘返回手势关闭抽屉，回到对话 |
| 返回键 | 关闭抽屉，不退出页面 |
| 2.0× 大字号 | 对话、抽屉、设置、连接、审批均无截断；抽屉标题不被子会话开关挤压 |
| 240 条长列表 | 上滑到第 60 条后追加新消息，位置不跳；「回到最新」出现 |
| 浅色/深色 | 主要页面 parity |

## 自动化覆盖

- `UiSystemRegressionTest`：
  - 空草稿发送禁用；
  - 2× 字号多行输入时附件与发送键底边对齐、均 ≥48dp；
  - 欢迎建议只填草稿、不发送；
  - 离线欢迎只有「连接电脑」一个恢复操作且打开连接页；
  - 无会话时先新建，不向不存在的草稿写入；
  - 240 条消息上滑阅读不被新消息抢滚动。
- `UiDesignTokensTest`：浅/深色正文、次要文字、主操作、错误容器对比度 ≥ 4.5:1。
- `ComposerInteractionTest`：输入→发送一次、图标 48dp 热区。

## 未验证

- 真机（非模拟器）的字体渲染、OLED 低亮度观感、TalkBack 全路径。
- 平板/折叠屏分屏：当前只保证内容最大宽度 760dp 居中，未做双栏。
- 真实桌面端联调下的工具卡、图片、附件、审批全链路（本轮样本为本地 fixture）。
- release 包未签名安装（仓库无签名配置），仅验证 `assembleRelease` 构建成功。
- 预测性返回动画的逐帧观感（模拟器默认关闭动画；已用临时开关验证返回关闭抽屉的行为）。
