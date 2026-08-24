# Pocket GitHub Releases APK 自动更新 — 设计文档

> 最后更新：2026-08-23 ｜设计状态：已完成，待分阶段实施

## 架构概览

```text
GitHub Releases API（latest 正式 Release）
    ↓ 解析 assets
PocketUpdateService（TS：Release / manifest / version 比较）
    ↓
PocketUpdater Capacitor Plugin（Android：下载、SHA-256、状态持久化、FileProvider 安装 Intent）
    ↓
Pocket update store（Jotai：运行态、启动周期“稍后”抑制、actions）
    ↓
PocketUpdateToast（右上角无 backdrop 卡片）
PocketUpdateSettings（设置/关于页完整状态与操作）
```

### 模块边界

| 层 | 职责 | 不得承担 |
|---|---|---|
| `PocketUpdateService` | GitHub API 请求、asset 查找、`pocket-update.json` 解析、版本比较、可复制下载 URL | 文件下载、Android 安装 Intent、Electron IPC |
| Android Capacitor Plugin | APK 下载、进度事件、SHA-256、持久化下载记录、缓存清理、FileProvider / 安装 Intent | GitHub API 格式推断、React 状态渲染 |
| Jotai update store | 合并 service/plugin 状态、启动检查、手动操作、启动周期抑制 | 直接访问 Android 文件或 DOM 组件 |
| `PocketUpdateToast` | 右上角简要提示、当前状态操作 | 全屏 modal、逻辑持久化 |
| 设置界面 | 版本详情、完整更新日志与所有可达操作 | 自动反复提示或直接管理原生文件 |

## 更新契约

客户端请求 GitHub 最新正式 Release：

```text
GET https://api.github.com/repos/Yuan-lai-ru-ci/Profer-pocket/releases/latest
```

客户端从 Release assets 中定位 `pocket-update.json`，读取它后定位 `apkAssetName` 对应 asset。客户端使用 asset 的 `browser_download_url` 作为下载和复制来源。

```json
{
  "versionCode": 9,
  "versionName": "0.1.10",
  "apkAssetName": "Profer-Pocket-0.1.10.apk",
  "sha256": "lowercase-hex-sha256",
  "releaseNotes": "…",
  "mandatory": false
}
```

解析规则：

1. 必填字段缺失、类型不符、`versionCode` 非正整数、SHA-256 不为 64 位十六进制、asset 不存在时，视为不可用更新并记录可读错误。
2. 仅当 `manifest.versionCode > currentNativeVersionCode` 时显示更新。
3. 只允许 HTTPS `browser_download_url`。
4. `releaseNotes` 优先使用 manifest；若策略允许可在后续版本回退到 GitHub Release body，本期不做隐式回退。

## 状态与持久化

前端公开状态：

```ts
type PocketUpdateStatus =
  | { status: 'idle' }
  | { status: 'checking' }
  | { status: 'not-available' }
  | { status: 'available'; update: PocketReleaseUpdate }
  | { status: 'downloading'; update: PocketReleaseUpdate; progress: DownloadProgress }
  | { status: 'downloaded'; update: PocketReleaseUpdate; apkPath: string }
  | { status: 'error'; phase: 'check' | 'download' | 'verify' | 'install'; message: string; update?: PocketReleaseUpdate }
```

持久化记录只存经 SHA-256 验证成功的 APK 元数据：路径、versionCode、versionName、sha256、URL。启动时 Plugin 验证文件仍存在且哈希相符；否则删除记录/文件并通知前端回到 `available` 或 `idle`。

`deferredForCurrentLaunch` 仅是前端内存标志，不持久化：点击“稍后”或“下次再说”设为 true。本次隐藏自动 toast；下次启动重新检查与提示。设置页永远不被该标记阻止。

## Android 原生桥接

建议新增 `PocketUpdaterPlugin`，并显式定义这些方法与事件：

```ts
getCurrentVersion(): Promise<{ versionCode: number; versionName: string }>
getDownloadedUpdate(): Promise<DownloadedUpdate | null>
downloadUpdate(options: { url: string; sha256: string; versionCode: number; versionName: string }): Promise<void>
installDownloadedUpdate(): Promise<void>
clearDownloadedUpdate(): Promise<void>

// event: updateDownloadProgress { percent, transferred, total }
```

实现要求：

- APK 下载到 app-specific cache/files 路径，并以 `.part` 临时文件落地；成功校验后原子改名。
- 使用 `MessageDigest` 流式计算 SHA-256，不将 APK 全量读入内存。
- 使用 `SharedPreferences` 保存已验证的元数据。
- 通过 manifest 注册的 `androidx.core.content.FileProvider` 创建 `content://` URI，并附带可读 URI 权限，使用 `ACTION_VIEW` + `application/vnd.android.package-archive` 拉起系统安装器。
- 检测未知来源安装授权不可用时，返回可操作错误，不绕过 Android 系统权限。
- 安装取消不会删除已校验 APK；明确清理或缓存失效才删除。

## UI 设计

### 右上角卡片

- 固定右上角，避开系统安全区；窄尺寸、圆角、轻阴影。
- 不使用 `Dialog` overlay、backdrop、focus trap 或全屏 blur。
- `available`：版本、可选的一行说明、`立即下载`、`稍后`。
- `downloading`：版本、进度条和百分比；可最小化/关闭视觉卡片，不取消后台下载。
- `downloaded`：版本、`立即安装`、`下次再说`。
- `error`：简短错误、`重试`、`复制下载地址`；仅有已解析 update URL 时才展示复制操作。

### 设置页

复用桌面更新页面的信息组织，不直接复用 Electron updater 调用：

- 当前原生版本；
- 检查更新；
- 最新/可下载/下载中/已就绪/失败状态；
- 更新日志；
- 下载、重试、复制下载地址、立即安装；
- 启动周期中用户延后提醒时仍完整显示。

## 发布与版本控制

正式 release 构建必须先解决 stable production keystore。构建工作流/脚本需要：

1. 从单一版本配置读出 `versionName` 和递增的 `versionCode`；
2. 构建并检查 release-signed APK；
3. 从 APK 读取并断言 versionName/versionCode；
4. 计算 SHA-256；
5. 生成 `pocket-update.json`；
6. 建立 GitHub Release 并上传这两个 asset；
7. 任何不一致时失败，禁止发布。

## 实施拆分与验证矩阵

本功能跨原生层、发布链路和 renderer，按复杂任务规则拆分为独立会话：

| 会话/阶段 | 交付边界 | 依赖 | 最小验证 |
|---|---|---|---|
| A. 发布基础 | production keystore 策略、单一版本、release 产物/manifest、GitHub Release 自动化 | 无 | APK 元数据/manifest 一致性、workflow dry-run 或脚本测试 |
| B. Android Plugin | 下载、哈希、缓存状态、FileProvider 与安装 Intent | A 的签名/版本契约 | Android 编译、原生测试可行时执行、真机安装路径 |
| C. Pocket 状态层与 UI | service、atoms、toast、设置页、复制地址、桌面 UI 复用抽取 | B 的 TS API 契约 | TS 单测、typecheck、Vite build、浏览器/Capacitor 联调 |
| D. 集成验收 | GitHub Release 到真机覆盖升级 | A+B+C | 需求文档中的 8 项验证矩阵 |

## 接口兼容性与降级

- Pocket 不依赖 Electron 的 `window.electronAPI.updater`；现有桌面 updater 文件如被共享引用，需隔离或以 Pocket 专用 API 替代。
- GitHub 请求失败：保留已有已校验 APK 可安装状态；若无本地包则显示重试与复制 URL，不切换更新源。
- 因网络、权限、空间或用户取消安装失败，业务主界面继续可用。
- 动态 Web bundle、Google Play 与备用镜像均不加入本期实现。
