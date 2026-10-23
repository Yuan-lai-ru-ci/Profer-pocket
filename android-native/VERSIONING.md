# Pocket Android 版本号规则

本文件是 `android-native` 的版本号唯一说明。改版本、打包、上传前先读一遍；不要凭记忆改数字。

## 1. 两条版本线，不要混用

| 构建链 | 版本线示例 | 说明 |
|---|---|---|
| 旧 Capacitor `pocket-app/` | `0.1.19-internal.4` / `11904` | 沿用 `release.config.json`，四处版本同步 |
| 原生 `android-native/` | `0.1.20-native.3` / `12003` | 本文件的范围；不使用 `internal.x` 序号 |

原生版另起版本线，是为了避免和旧壳的 `internal.x` 序号互相占用。只有用户明确要求统一时才混用。

## 2. 版本名格式

```text
<major>.<minor>.<patch>-<channel>.<build>
```

- `major/minor/patch`：产品版本基线，取自当前正式基线；每段 `0..99`。
- `channel`：原生测试线固定用 `native`；旧壳用 `internal`。渠道名不参与 versionCode 计算。
- `build`：同一基线下的构建序号，从 `1` 开始，每次打包 +1，`0..99`。

示例：

```text
0.1.20-native.1
0.1.20-native.2
0.1.20-native.3
```

## 3. versionCode 计算规则

```text
versionCode = (major * 10000 + minor * 100 + patch) * 100 + build
```

等价写法：把 `major.minor.patch` 压成三位数（如 `0.1.20` → `120`），再乘 100 加 build。

| versionName | 计算 | versionCode |
|---|---|---|
| `0.1.19-internal.4` | `(0*10000 + 1*100 + 19) * 100 + 4` | `11904` |
| `0.1.20-native.1` | `(0*10000 + 1*100 + 20) * 100 + 1` | `12001` |
| `0.1.20-native.2` | `(0*10000 + 1*100 + 20) * 100 + 2` | `12002` |
| `0.1.20-native.3` | `(0*10000 + 1*100 + 20) * 100 + 3` | `12003` |

`android-native/app/build.gradle.kts` 已内置该公式：只改 `pocketVersionName`，`versionCode` 自动计算；格式不合法会直接让 Gradle 配置失败。

## 4. 递增规则

1. **每次打包必须递增 `build`**，`versionCode` 随之严格递增；不能只改展示版本。
2. **不要复用已用过的 versionCode**，包括未上传但已安装到设备的包。
3. 产品基线升级时（如 `0.1.20` → `0.1.21`），`build` 重置为 `1`；新 versionCode 仍然更大。
4. 历史版本不回改；已发布的 versionName/versionCode 保持不变。
5. 同一 versionName 只能对应一个 APK；重新打包必须加 build 序号。

## 5. 修改位置

只改这一处：

```kotlin
// android-native/app/build.gradle.kts
val pocketVersionName = "0.1.20-native.3"
val pocketVersionCode = pocketVersionCodeOf(pocketVersionName)
```

不要手写 `versionCode = 12003`。`defaultConfig` 已改为读取上面两个变量，公式校验在配置阶段执行。

## 6. 打包与发布检查

1. `versionName` / `versionCode` 严格高于设备已安装版本。
2. `applicationId = com.profer.pocket`，使用正式 release 签名；debug 包只能并装手测，不能当更新源。
3. APK 文件名必须是 `Profer-Pocket-<versionName>.apk`。
4. `pocket-update.json` 的 `versionCode`、`versionName`、`apkAssetName`、`sha256` 必须与 APK 完全一致。
5. APK 与 manifest 成对上传 `/profer-pocket-dev/`，上传后回下载校验 SHA-256。
6. 构建用的临时 `android-native/app/signing.properties` 构建后删除，不提交。

## 7. 历史版本

| versionName | versionCode | 说明 |
|---|---|---|
| `0.1.19-internal.4` | `11904` | 旧 Capacitor 正式签名包 |
| `0.1.20-native.1` | `12001` | 原生远程扫码首个测试包 |
| `0.1.20-native.2` | `12002` | 已连接/失败态补扫码入口，真实版本号 |
| `0.1.20-native.3` | `12003` | 关于页只保留版本与协议；版本号由 Gradle 公式生成 |

## 8. 参考

- 打包/上传流程：工作区 Skill `profer-pocket-dev-release`。
- 旧壳版本规则：`pocket-app/release.config.json` 及其四处版本同步。
