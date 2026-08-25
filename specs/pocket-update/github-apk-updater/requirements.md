# Pocket GitHub Releases APK 自动更新 — 需求文档

> 最后更新：2026-08-23 ｜状态：planning ｜需求已验收：是

## 背景

Pocket 是基于 Capacitor 的 Android 客户端。目前正式 APK 需要用户自行获取和安装，且没有应用内更新检查、下载或安装引导。桌面端虽已有 Electron 自动更新，但其 `electron-updater`、IPC 和 Windows 安装格式不能直接用于 Android。

本功能将 GitHub Releases 作为 Pocket **唯一**正式更新源，为侧载 APK 提供受用户控制的检查、下载、校验和系统安装引导。AList、其他镜像、Google Play In-App Updates 和 Web bundle Live Update 不属于本期范围。

## 已确认边界

- 每次 Pocket 启动完成初始化后自动检查一次；不阻塞正常使用。
- 设置页提供“检查更新”手动入口；同一启动周期不进行额外自动轮询。
- 发现新版时不自动下载。先在右上角显示精简卡片，由用户选择下载或稍后。
- 浮层不居中、不全屏、不使用 backdrop 或背景模糊，避免误触和遮挡工作区。
- 用户选择“稍后”后，本次运行不再主动提醒；设置页仍保留可见状态和操作。下次启动再次提醒。
- 下载完成后不自动安装；设置页显示“立即安装”。下次启动以右上角卡片再次提示。选择“下次再说”后同样仅在下次启动再次提示。
- 仅使用 GitHub Releases。GitHub API 或下载失败时显示“重试”及“复制下载地址”；不得切换 AList、镜像或其他备用源。
- 应用下载的 APK 必须经 SHA-256 校验；校验失败的临时文件必须删除，禁止安装。
- 点击“立即安装”仅拉起 Android 系统安装器；最终安装确认由 Android 系统负责，应用不得尝试静默安装。

## 需求

### 1.1 GitHub Release 更新契约

As a Pocket 用户, I want 应用只从 GitHub Releases 识别正式更新 so that 我能确认下载来源且不会在不同镜像间得到不一致版本。

- 检查 `Yuan-lai-ru-ci/Profer-pocket` 的最新正式 Release。
- 忽略 draft 与 prerelease。
- 每个可供自动更新的 Release 必须包含：
  - `Profer-Pocket-<version>.apk`；
  - `pocket-update.json`。
- `pocket-update.json` 至少包含 `versionCode`、`versionName`、`apkAssetName`、`sha256`、`releaseNotes`、`mandatory`。
- 通过 `versionCode`（不是展示用 `versionName`）比较本机与远程版本；只有远程值更大才视为更新。
- 复制下载地址时，复制对应 APK asset 的 GitHub 直链，而非 Release 网页地址。

### 1.2 启动检查与延后语义

As a Pocket 用户, I want 每次启动最多被询问一次是否更新 so that 不会被更新通知反复打扰。

- 根入口在本次启动初始化完成后触发一次检查；失败不得阻塞连接、聊天或其他 UI。
- 手动检查可覆盖本次的检查结果并重新请求 GitHub。
- 新版已发现而未下载时显示右上角紧凑卡片，操作为“立即下载”和“稍后”。
- “稍后”必须记录到内存中的启动周期状态；本次运行不再自动弹出，但设置页仍显示更新可用。
- App 重启后清除该启动周期抑制状态，若远程版本仍较新则再次提醒。

### 1.3 下载、校验与恢复

As a Pocket 用户, I want 看到下载进度并在重启后继续使用已校验的安装包 so that 我可自行决定合适的安装时机。

- 用户确认后由 Android 原生桥接下载 APK 到应用私有目录，前端获得检查、下载进度、已下载、失败等状态。
- 完成下载后计算 SHA-256，必须与 Release manifest 完全一致。
- 校验通过后持久化下载文件路径、远程 `versionCode` / `versionName`、SHA-256 和下载 URL。
- 启动时恢复持久化状态，并重新确认文件存在与哈希匹配；失效或损坏的缓存必须清理并回到可下载状态。
- 下载失败、网络不可用、GitHub 限流、存储空间不足或校验失败必须有用户可读错误信息；失败卡片及设置页都提供“重试”和“复制下载地址”。

### 1.4 Android 安装引导

As a Pocket 用户, I want 在确认后通过系统安装器覆盖安装已下载 APK so that 更新符合 Android 的安全机制。

- “立即安装”仅在有已校验、版本更新的 APK 时可用。
- 原生桥接使用安全 content URI（FileProvider）将 APK 交给系统 Package Installer。
- 用户取消系统安装后，已校验 APK 保留，可在设置页或下次启动再次安装。
- 正式 APK 必须由稳定的生产 keystore 签名；新旧版本签名必须一致。此项是正式上线的阻塞前置条件。

### 1.5 移动端更新界面

As a Pocket 用户, I want 紧凑、不遮挡的更新提示和设置入口 so that 更新流程不会干扰我正在进行的工作。

- 新建 Pocket 专用右上角更新卡片，不复用桌面端居中 `UpdateDialog`。
- 卡片无全屏遮罩、无背景模糊，限宽、可关闭，文案和按钮随状态变化。
- 设置/关于页显示本机版本、远程版本、更新日志、检查状态和对应操作：检查、下载、重试、复制地址、立即安装。
- 可复用桌面端的状态模型、进度显示、Markdown 更新日志和状态机原则；不得引入 Electron API、`electron-updater` 或 `window.electronAPI.updater` 依赖。

### 1.6 发布与版本一致性

As a Pocket 发布者, I want 一次正式发布同时产出一致的 APK 和更新清单 so that 客户端不会下载到版本或哈希不匹配的包。

- 建立单一版本来源或构建时一致性校验，覆盖 Web 展示版本、Android `versionName` 与 Android `versionCode`。
- 每次正式发布的 Android `versionCode` 必须严格递增。
- 发布过程计算 APK SHA-256，生成 `pocket-update.json`，并将 APK 与 JSON 上传到同一个 GitHub Release。
- 发布前校验 asset 名称、APK 内版本、manifest 版本和 SHA-256 一致；不满足则阻断发布。

## 非功能需求

- 安全：只接受 HTTPS 的 GitHub API 与 GitHub Release asset；校验 APK SHA-256；不记录或暴露访问令牌。
- 隐私：检查请求不附带用户工作区、Token、会话内容或设备标识。
- 兼容性：支持当前 Capacitor Android 壳；Android 系统安装确认与未知来源安装权限状态应给出可理解的提示。
- 可恢复性：异常退出、下载中断、旧缓存损坏不能造成卡死或误安装。
- 可测试性：Release 解析/版本比较/manifest 校验在 TypeScript 层可单测；原生文件、哈希和安装 Intent 需有 Android 构建与真机验证。

## 本期不处理

- Google Play In-App Updates。
- AList 或任何备用更新镜像。
- 静默安装、企业设备管理或 Root 安装。
- Capacitor Live Update / 动态 Web bundle 更新。
- 未经用户明确授权的开发 APK 构建、真机安装或 GitHub Release 发布。
