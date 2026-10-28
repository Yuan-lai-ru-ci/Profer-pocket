# Pocket Android 版本号规则

本文件是 `android-native` 的版本号唯一说明。改版本、打包、发布前先读一遍；不要凭记忆改数字。

## 1. 构建链

| 构建链 | 版本线示例 | 说明 |
|---|---|---|
| 原生 `android-native/` | `0.1.21` / `12199` | **当前唯一维护线** |
| 旧 Capacitor `pocket-app/` | `0.1.19-internal.4` / `11904` | 历史线，沿用 `release.config.json` |

## 2. 版本名格式

```text
正式版  <major>.<minor>.<patch>                  例：0.1.21
测试版  <major>.<minor>.<patch>-<channel>.<build>  例：0.1.21-internal.1
```

- `major/minor/patch`：产品基线，每段 `0..99`。
- `channel`：测试渠道，固定用 `internal`；不参与 versionCode 计算。
- `build`：测试包序号，`1..98`。

## 3. versionCode 计算规则

```text
versionCode = (major * 10000 + minor * 100 + patch) * 100 + build
正式版 build = 99（该基线保留的最大号）
测试版 build = 1..98
```

等价写法：把 `major.minor.patch` 压成三位数（如 `0.1.21` → `121`），再乘 100 加 build。

| versionName | 计算 | versionCode |
|---|---|---|
| `0.1.22-internal.1` | `(0*10000 + 1*100 + 22) * 100 + 1` | `12201` |
| `0.1.22` | `(0*10000 + 1*100 + 22) * 100 + 99` | `12299` |
| `0.1.23-internal.1` | `(0*10000 + 1*100 + 23) * 100 + 1` | `12301` |

`android-native/app/build.gradle.kts` 已内置该公式：只改 `pocketVersionName`（或构建时覆盖），`versionCode` 自动计算；格式不合法会直接让 Gradle 配置失败。

## 4. 递增与渠道规则

1. **正式版取该基线最大号（build 99）**，保证它大于同基线所有测试包：装过 `0.1.21-internal.N` 的设备能直接覆盖安装 `0.1.21`。
2. 测试包 build 从 `1` 开始递增，上限 `98`；同一基线超过 98 个测试包就升 patch。
3. **versionCode 不复用**，包括未发布但已装机的包。Android 系统会拒绝安装比当前设备版本更低的包（防降级）。
4. 历史版本不回改；已发布的 versionName/versionCode 保持不变。
5. 同一 versionName 只能对应一个 APK；测试包重打必须加 build，正式包重打只能升 patch（如 `0.1.22`）。
6. **测试包必须基于下一个 patch 基线**：正式 `0.1.22`（12299）之后，测试包是 `0.1.23-internal.1`（12301）。同基线内的测试包号更小，正式版连点 10 次也发现不了。
7. 装过测试包后回不到同基线正式版（12301 > 12299），要等下一个正式版（12399）或卸载重装。

## 5. 修改位置

正式版默认值写在文件里；测试包构建时用 Gradle 属性覆盖，不改仓库文件：

```kotlin
// android-native/app/build.gradle.kts
val pocketVersionName = providers.gradleProperty("pocketVersionName").orNull ?: "0.1.22"
val pocketVersionCode = pocketVersionCodeOf(pocketVersionName)
```

```bash
./gradlew.bat assembleRelease -PpocketVersionName=0.1.23-internal.1
```

不要手写 `versionCode`。`defaultConfig` 已读取上面两个变量，公式校验在配置阶段执行。

## 6. 更新通道

| 通道 | 入口 | 数据源 |
|---|---|---|
| 正式版 | 设置 → 检查更新 | GitHub `/releases/latest`，非预发布的 Release |
| 测试版 | 设置 → 关于 → 连点版本号 10 次 | GitHub `/releases` 列表里 `prerelease=true` 的最新一个 |

两条通道都读 Release 资产里的 `Profer-Pocket-<versionName>.apk` 与 `pocket-update.json`，校验 `versionCode`、`apkAssetName`、`sha256`；`versionCode` 不高于本机时不提示。

测试包以 GitHub 预发布（pre-release）发布：普通用户检查更新看不到，但仓库公开时 Releases 页面可见、可下载。不需要额外服务器，代码里也没有任何服务器地址。

## 7. 打包与发布检查

1. `versionName` / `versionCode` 严格高于设备已安装版本。
2. `applicationId = com.profer.pocket`，使用正式 release 签名；debug 包只能并装手测，不能当更新源。
3. APK 文件名必须是 `Profer-Pocket-<versionName>.apk`。
4. `pocket-update.json` 的四个字段必须与 APK 完全一致。
5. 正式版发 GitHub Release（tag `v<versionName>`，非预发布）；测试版发预发布（`--prerelease`，tag `v<versionName>`）。
6. 构建用的临时 `android-native/app/signing.properties` 构建后删除，不提交。
7. 测试包构建用 `-PpocketVersionName=...`，不改仓库文件；构建后 `git status` 应保持干净。

```bash
# 测试版
gh release create v0.1.23-internal.1 --prerelease --notes "..." \
  Profer-Pocket-0.1.23-internal.1.apk pocket-update.json

# 正式版
gh release create v0.1.22 --notes "..." Profer-Pocket-0.1.22.apk pocket-update.json
```

## 8. 历史版本

| versionName | versionCode | 说明 |
|---|---|---|
| `0.1.19-internal.4` | `11904` | 旧 Capacitor 正式签名包 |
| `0.1.20-native.1` | `12001` | 原生远程扫码首个测试包 |
| `0.1.20-native.2` | `12002` | 已连接/失败态补扫码入口，真实版本号 |
| `0.1.20-native.3` | `12003` | 关于页只保留版本与协议；版本号由 Gradle 公式生成 |
| `0.1.20-native.4` | `12004` | 本地测试包，未归档 |
| `0.1.20-native.5` | `12005` | 首个走 AList 测试通道的原生包 |
| `0.1.21` | `12199` | 首个原生正式版，GitHub Release 分发 |
| `0.1.22` | `12299` | 测试通道改走 GitHub 预发布，正式版连点 10 次可检查测试版 |
| `0.1.23-internal.1` | `12301` | 首版预发布测试包，已删除并由 .2 替换 |
| `0.1.23-internal.2` | `12302` | 预发布测试包；更新检查连接超时 30 秒 |
| `0.1.23-internal.3` | `12303` | 预发布测试包；支持公网地址扫码（v3 精简码）与手动填写地址/端口/指纹 |
| `0.1.23` | `12399` | 原生正式版；公网地址扫码配对与手动填写连接正式可用 |
| `0.1.24` | `12499` | 原生正式版；会话继承桌面默认模型、项目置顶、手动地址连接，备份与无障碍规范补强 |

## 9. 参考

- 打包/上传流程：`workspace-files/.context/memory-archive/profer-pocket-packaging.md`。
- 旧壳版本规则：`pocket-app/release.config.json` 及其四处版本同步。
