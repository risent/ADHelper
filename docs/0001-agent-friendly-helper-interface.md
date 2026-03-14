# ADR 0001: Make The Android Helper Agent-Friendly

## Status

Accepted

## Context

Claude Code、Codex 这类代理并不适合依赖脆弱的坐标点击和隐式页面跳转。它们更适合操作一个可观察、可恢复、可重试的接口。

当前 helper 已经具备基础动作能力，但要让代理稳定地自动化复杂 App，还需要把接口收敛成明确的状态机原语，而不是零散命令。

## Decision

我们将 helper 的设计目标定为“先观测，再动作，再验证”。

为此新增并强化以下接口：

- `current_app`: 获取当前前台应用包名，用于检测是否外跳。
- `snapshot`: 一次性返回节点树、可点击节点和页面指纹，减少多次 round trip。
- `click_node`: 使用 `list_clickables` 暴露的稳定 `nodeId` 点击节点，优先替代坐标点击。
- `wait_for_stable_tree`: 轮询当前页面，直到页面指纹稳定，避免代理在转场期误操作。
- `list_clickables`: 为每个候选节点提供 `nodeId`、`path`、`centerX/centerY` 等结构化字段。

## Rationale

这样设计有几个直接收益：

- 代理可以做幂等重试，而不是盲目连点。
- 代理可以先比对 `packageName` 和 `fingerprint`，再决定是否继续。
- `click_node` 让点击目标从“模糊文本/坐标”提升到“稳定结构化节点”。
- `wait_for_stable_tree` 让页面转场处理变成显式协议，而不是脚本里散落的 `sleep`。

## Consequences

正面影响：

- 更适合构建遍历器、回归脚本和多步代理流程。
- 更容易对外跳、超时、页面未稳定等情况做自动恢复。
- 更容易在桌面端和代理端共享同一套调用模式。

代价：

- `snapshot` 和 `wait_for_stable_tree` 会增加树遍历和轮询开销。
- `nodeId` 基于当前树结构，不保证跨页面或跨状态长期稳定。
- 复杂页面上的树抓取仍然需要控制超时与主线程开销。

## Guidance

推荐代理调用顺序：

1. `health`
2. `current_app`
3. `snapshot`
4. `wait_for_stable_tree`
5. `list_clickables`
6. `click_node`
7. 再次 `snapshot` 或 `current_app`

推荐原则：

- 优先 `click_node`，其次 `click_text`，最后才用 `click_point`
- 发现离开目标包名时，先恢复到目标 App，不要连续盲目 `back`
- 对分享、购买、广告、系统弹窗等节点做黑名单过滤
