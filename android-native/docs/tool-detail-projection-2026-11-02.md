# 工具详情投影契约（移动端 ↔ 桌面端）

> 状态：**提案，未实施**。移动端 UI 已就位，桌面端投影尚未下发字段。
> 日期：2026-11-02

## 1. 为什么需要

移动端工具卡从「整卡 + 边框 + 单段 detail」改成轻量行 + 展开分区：

- 折叠：`图标 + 标题 · 目标 + 状态`
- 展开：`参数`（入参 JSON）、`结果`（输出摘要）、`错误`（失败原因）

UI 侧 `ToolBlock` 已有 `input` / `output` / `error` 三个字段，但投影目前只透传 `file_path`，
三者恒为 `null`，卡片因此不可展开。要真正用起来，需要桌面端在 pocket 投影里补字段。

## 2. 提议字段

挂在已有的 `tool_use` / `tool_result` 事件上，随 `session_chat` 投影下发：

| 字段 | 位置 | 类型 | 上限 | 说明 |
|---|---|---|---|---|
| `inputSummary` | `tool_use` | string | 1200 字符 | 入参 JSON 的压缩单行文本；已是字符串，移动端不再解析 |
| `outputSummary` | `tool_result` | string \| null | 600 字符 | 成功时的输出摘要；可为空 |
| `errorSummary` | `tool_result` | string \| null | 800 字符 | 仅 `is_error=true` 时下发 |

约束：

- 三个字段都是**已截断、已脱敏的展示文本**，移动端不再做二次截断（只做换行与省略）。
- 截断统一在尾部加 `…`；不要从中间截断 JSON 后再拼 `}`，宁可整段放弃返回 `null`。
- 缺失或为 `null` 时移动端退回当前行为（不可展开），旧桌面端不受影响。
- 不下发完整 stdout、diff 全文、图片字节；这些仍走原有按需拉取。

## 3. 脱敏规则

1. 绝对路径：家目录前缀替换为 `~`，仓库内路径保留相对形式。
2. 疑似凭据：`token` / `password` / `secret` / `api_key` / `authorization` 的键值对，值替换为 `***`。
3. 命令行：只保留前 200 字符，管道后的长输出不进入 `inputSummary`。
4. 不拼接 URL query 中的敏感参数；`?token=...` 这类片段整段替换为 `?…`。

## 4. 移动端消费点

| 位置 | 用途 |
|---|---|
| `core/data` ChatReducer | 把 `inputSummary` / `errorSummary` 挂到对应 `ToolCall`，按 `tool_use.id` 关联 |
| `app/UiMapping.toolBlock` | 映射到 `ToolBlock.input` / `output` / `error` |
| `core/designsystem/ProferToolCallCard` | 展开分区渲染，空字段不占位 |

`failedToolIds` 继续只存 id，用于剔除失败工具留下的文件汇总；错误文案不参与该判断。

## 5. 验收

- 桌面端单测：脱敏函数对 `token=` / 家目录 / 超长 JSON 的输出稳定；字段缺失时不报错。
- 移动端：fixture 展开态已在真机核对（浅色 / 深色 / 大字号）；接真数据后补一次真实会话联调。
- 不把「字段已下发」当成「UI 已验收」；两侧分别记录。
