# Profer Pocket iPhone 试用

## 当前状态

Pocket 已加入 Capacitor iOS 工程：

- 工程：`pocket-app/ios/App/App.xcworkspace`
- Bundle ID：`com.profer.pocket.dev`
- 最低 iOS：14.0
- Web 前端：复用根目录 `dist/`，与 Android Pocket 共用一套 React 客户端
- 局域网连接：已声明本地网络访问说明，并允许连接开发阶段的 `ws://` / `http://` 服务

## 本机准备

需要安装完整 Xcode（不是只有 Command Line Tools），并在 Xcode 中完成一次：

1. 打开 `pocket-app/ios/App/App.xcworkspace`，不要打开 `.xcodeproj`。
2. 在 Xcode 的 `App` target → **Signing & Capabilities** 选择自己的 Apple Team。
3. 如需真机调试，在 iPhone 上信任开发者证书并开启 Developer Mode。
4. 用数据线连接 iPhone，选择设备后点击 Run。

免费 Apple ID 通常可用于个人真机调试，但签名有效期和设备数量受 Apple 账号规则限制；正式分发仍需要自己的开发者配置。

## 命令

在 `pocket-app/` 目录：

```bash
npm install
npm run build:ios
npm run open:ios
```

`build:ios` 会先在仓库根目录生成最新 Web 产物，再同步到 iOS 工程。之后在 Xcode 中选择 Team、设备并运行即可。

## 连接电脑端 Profer

首次启动后，在 Pocket 的连接设置中填写电脑端 Profer Remote Service 的地址和 Token。手机与电脑需要在同一局域网；地址使用电脑局域网 IP，不要填写 `127.0.0.1` 或 `localhost`，例如：

```text
ws://192.168.1.20:7788
```

当前 iOS 工程已预留局域网访问权限，但电脑端服务仍需监听局域网地址并允许防火墙/网络访问。

## 当前验证边界

已验证：TypeScript、Vite 生产构建、Pocket 发布脚本测试、iOS Capacitor 工程生成与 Web 资源复制。

尚未在本机完成：Xcode 原生编译、Apple 签名、iPhone 真机安装和真实 WebSocket 联调。本机当前只有 Command Line Tools，没有完整 Xcode，因此这些步骤需要安装 Xcode 后执行。
