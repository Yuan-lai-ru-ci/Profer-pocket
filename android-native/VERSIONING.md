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
| `0.1.21-internal.1` | `(0*10000 + 1*100 + 21) * 100 + 1` | `12101` |
| `0.1.21` | `(0*10000 + 1*100 + 21) * 100 + 99` | `12199` |
| `0.1.22-internal.1` | `(0*10000 + 1*100 + 22) * 100 + 1` | `12201` |

`android-native/app/build.gradle.kts` 已内置该公式：只改 `pocketVersionName`，`versionCode` 自动计算；格式不合法会直接让 Gradle 配置失败。

## 4. 递增与渠道规则

1. **正式版取该基线最大号（build 99）**，保证它大于同基线所有测试包：装过 `0.1.21-internal.N` 的设备能直接覆盖安装 `0.1.21`。
2. 测试包 build 从 `1` 开始递增，上限 `98`；同一基线超过 98 个测试包就升 patch。
3. **versionCode 不复用**，包括未发布但已装机的包。Android 系统会拒绝安装比当前设备版本更低的包（防降级）。
4. 历史版本不回改；已发布的 versionName/versionCode 保持不变。
5. 同一 versionName 只能对应一个 APK；测试包重打必须加 build，正式包重打只能升 patch（如 `0.1.22`）。

## 5. 修改位置

只改这一处：

```kotlin
// android-native/app/build.gradle.kts
val pocketVersionName = "0.1.21"
val pocketVersionCode = pocketVersionCodeOf(pocketVersionName)
```

不要手写 `versionCode`。`defaultConfig` 已读取上面两个变量，公式校验在配置阶段执行。

## 6. 更新通道

| 通道 | 入口 | 数据源 |
|---|---|---|
| 正式版 | 设置 → 检查更新 | GitHub 最新 Release，`Profer-Pocket-<versionName>.apk` + `pocket-update.json` 作为 Release 资产 |
| 测试版 | 设置 → 关于 → 连点版本号 10 次 | 自建 AList 开发者源，APK + `pocket-update.json` 成对放置 |

两条通道都校验 `pocket-update.json` 的 `versionCode`、`apkAssetName`、`sha256`；`versionCode` 不高于本机时不提示。

测试通道地址不进仓库、不进 GitHub Release 的 APK，只在本地构建时注入：

```properties
# android-native/local.properties（已被 .gitignore 忽略，不提交）
proferInternalUpdateBaseUrl=http://<host>/<dir>/
```

也可以构建时临时传 `-PproferInternalUpdateBaseUrl=...`。

**正式包构建必须加 `-PpocketOfficialBuild=true`**：该参数忽略本地配置，APK 内地址为空，连点 10 次只提示“测试通道未配置”。发布前用 `grep -a` 扫 APK 确认不含服务器地址。

## 7. 打包与发布检查

1. `versionName` / `versionCode` 严格高于设备已安装版本。
2. `applicationId = com.profer.pocket`，使用正式 release 签名；debug 包只能并装手测，不能当更新源。
3. APK 文件名必须是 `Profer-Pocket-<versionName>.apk`。
4. `pocket-update.json` 的四个字段必须与 APK 完全一致。
5. 正式版发 GitHub Release（tag `v<versionName>`）；测试版传 AList，两者不混用。
6. 构建用的临时 `android-native/app/signing.properties` 构建后删除，不提交。

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

## 9. 参考

- 打包/上传流程：`workspace-files/.context/memory-archive/profer-pocket-packaging.md`。
- 旧壳版本规则：`pocket-app/release.config.json` 及其四处版本同步。
