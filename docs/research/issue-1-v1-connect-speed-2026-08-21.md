# Issue #1 遗留问题调研：V1 连接速度慢于 0.3.0-beta.4"V2"

> 调研日期：2026-08-21 · 来源：GitHub issue #1（ISuuuu）评论区 + git 考古（beta.4/beta.9 源码）+ 本机双服务器对照实测（opencode 1.18.18 vs opencode2 0.0.0-beta-17728）
> 关联：backlog #150（修复方向登记）、#83/#121（版本探测交叉验证修复 = 报错部分已修）、docs/v1-v2-differences.md

## 0. 问题定义

Issue #1 报告人 ISuuuu（2026-08-13）：

- ✅ 原始报错（截图：`Unexpected JSON token ... JSON input: <!doctype html>`）**已修复**——beta.8 的 `4c2b6d8a`（版本探测交叉验证 + HTML 防御，backlog #83）。报告人已确认"新版本报错问题已修复"。
- ⚠️ **遗留问题（本次调研对象）**："连接方式 v1 连接速度没有 0.3.0-beta.4 的 v2 快"。
- 环境：OpenCode **1.18.16 / 1.18.18**（V1），Windows 批处理 `opencode serve --hostname :: --port 4096`，小米 12SP（Android 15）/ muMu 模拟器，WiFi 局域网。

## 1. 结论摘要

**用户对比的"beta.4 的 v2 快"是一个错误状态下的假象快——不是 V2 协议更快，而是 beta.4 把 V1 1.18.18 误判为 V2 后，整个会话预加载（preLoadSessions）因 HTML 解析快速失败而被整体跳过，连接路径从 6 步串行缩为 3 步。**

但这个假象暴露了真实问题：**当前正确的 V1 连接路径存在串行预加载 + 串行双探测的结构性开销**，即使判级正确也比理论最优慢一截。本机回环实测：正确 V1 路径 ≈165ms vs beta.4 误判路径 ≈37ms（**4.5 倍**）；用户 WiFi + Windows PC 环境下每步 RTT 与服务器处理时延会被显著放大（首连感知可达数秒）。

修复方向已登记 backlog #150：探测结果复用 + 预加载与 SSE 并行化。**不应**追求复现 beta.4 的误判行为。

## 2. 证据链

### 2.1 beta.4 为什么把 V1 1.18.18 判成 V2

beta.4（`d34fed29` 引入）的 `ApiVersionDetector.tryV2`：

- 探测 `GET /api/health`，HTTP 200 + JSON 即认为 V2（`healthy ?: true` 默认真），**无交叉验证**。
- V1 1.18.18 是**过渡形态**：同时暴露 `/api/health`（实测返回 `{"healthy":true}`，无 version/pid）→ 一次探测即误判 V2。
- 本机实测（1.18.18，端口 4198）：`/api/health` → 200 JSON 16B；`/api/event` → V2 风格 SSE（`data: {"id":...,"type":"server.connected","data":{}}`）；`/api/session` → V2 风格 `{"data":[],"cursor":{...}}`；`/api/project` → **SPA fallback HTML**（200，`<!doctype html>`）。
- beta.8 `4c2b6d8a` 修复：version 交叉验证（2.x 或 pid 特征才判 V2）+ content-type 校验 → 1.18.18 正确判 V1。

### 2.2 两条连接路径的完整序列对比

连接链路：`HomeViewModel.connectToServer` → `serverRepository.testConnection`（= `ServerDataStore.checkHealth` → `versionDetector.detect`）→ 启动 `OpenCodeConnectionService` → `SseConnectionManager.startConnection` → `runSseConnectionLoop`：**串行执行** `preLoadSessions`（`/project` → 每项目 `/session` → `syncFromRest` 每目录 `/session/status`）**然后**才建 SSE；UI"已连接"翻转等**首个 SSE 事件**（`server.connected`，两版本服务器均在握手后立即推送）。

| 步骤 | 当前版本正确 V1 路径 | beta.4 误判 V2 路径（同一台 1.18.18） |
|------|---------------------|--------------------------------------|
| 版本探测 | ① `/api/health`（白跑，交叉验证拒绝）→ ② `/global/health`（2 次串行 RTT） | ① `/api/health`（1 次即"成功"） |
| 预加载 | ③ `/project` → ④ 每项目 `/session?limit=50`（roots=true）→ ⑤ `syncFromRest`：每目录 `/session/status`（全串行） | ② `/api/project` 返回 HTML → `.body<List<Project>>()` 抛转换异常 → 外层 catch → **整个 preLoadSessions + syncFromRest 跳过**（1 次 RTT 快速失败） |
| SSE | ⑥ `/global/event`（payload 包装格式） | ③ `/api/event`（过渡端点真实存在，V2 格式） |
| 会话列表数据 | Service 预加载填充 EventDispatcher | preload 跳过 → `SessionListViewModel.loadSessions` 独立 REST 拉取兜底（列表照样能用） |

**注**：beta.4 误判后之所以"能用"：1.18.18 的 `/api/event`、`/api/session`、`/api/session/{id}/message` 等过渡端点真实存在且返回 V2 风格 JSON——这正是 issue #1 报错的根源（`/api/project` 等无过渡端点的路径返回 SPA HTML，进会话时 JSON 解析崩溃），也是"误判快"的前提。

### 2.3 本机对照实测（2026-08-21）

环境：V1 = opencode 1.18.18（isolated XDG，127.0.0.1:4198）；V2 = opencode2 0.0.0-beta-17728（:4199）。回环网络，各 3 轮取代表值：

| 端点 | V1 1.18.18 | V2 beta-17728 |
|------|-----------|---------------|
| `/api/health` | 1.4–4.7ms（16B JSON） | 0.8–1.6ms（56B） |
| `/global/health` | 1.7–5.3ms | HTML（V2 无此端点） |
| `/project` / `/api/project` | 7–214ms（**首次冷调用 214ms**，热 7–9ms） | 2.3–4.1ms |
| `/session?limit=50` | 8–32ms | 6.7–10.5ms |
| `/session/status`（每目录） | ~94ms（冷） | —（V2 无等价端点，走 activeSessions） |
| SSE 首事件 `server.connected` | ~11ms（握手后立即） | ~8ms（握手后立即） |

按 App 实际请求序列模拟（python 计时，含每步 RTT）：

- **[模拟A] 当前正确 V1 路径**：双探测 + /project + /session + /session/status + SSE = **≈165ms**（若项目多则按 N 项目线性放大；首连还有 Windows 侧冷启动惩罚——/project 首调实测 214ms）
- **[模拟B] beta.4 误判路径**：单探测 + /api/project HTML 快速失败 + SSE = **≈37ms**

用户环境（手机 WiFi → Windows PC，RTT 数 ms~数十 ms + Windows 防火墙/Nagle + 服务器冷启动）每步都会放大；多项目 worktree 的用户预加载串行次数更多。

### 2.4 排除项（已验证不是差异来源）

- **SSE 首事件延迟**：两版本服务器均在握手后立即推 `server.connected`（实测 V1 11ms / V2 8ms）；两客户端均过滤 `ServerHeartbeat` 不影响翻转（beta.4 起如此，git 考古 `3a684bfa`）。
- **心跳节奏**：V1 每 10s（首 tick 被 drop 避免与 connected 重叠），不阻塞首连。
- **payload 包装解析**：V1 `SseClient.parseEvent`（SseClient.kt:269）正确解包 `payload` 层，`server.connected` 可正常解析。
- **初始消息分页**：V1 `limit/before` + `X-Next-Cursor`，V2 cursor——首屏都对等（带 limit），非差异源。
- **认证方式**：两版本同为 HTTP Basic。

## 3. 根因定性

1. **主因（结构性）**：`runSseConnectionLoop` 把 `preLoadSessions`（`/project` + N×`/session` + N×`/session/status` + `syncFromRest`）**串行放在 SSE 建立之前**——"已连接"翻转被整个预加载时长阻塞。beta.4 误判时这一整块被意外跳过，才显得快。
2. **次因（累积）**：`ApiVersionDetector.detect` V2-first 串行双探——对已知 V1 的服务器每次手动连接都白付一次 `/api/health` RTT（HomeViewModel 每次连接都走 testConnection → checkHealth → detect，探测结果虽持久化但**每次连接仍重新探测**）。
3. **V1 特有放大器**：V1 的 `syncFromRest` 走 `/session/status`（V2 无此端点，用 activeSessions）——V1 比 V2 多一串每目录一次的 REST 往返；且 Windows 上 `/project` 首次冷调用明显偏慢（本机实测 214ms vs 热 8ms）。

**定性**：不是 V1 协议慢，是客户端连接编排（串行）+ 探测策略（不复用）的开销；beta.4 的对照数字是误判产物，不可作为优化目标基线。

## 4. 修复方向（登记于 backlog #150，未实现）

按收益排序：

1. **探测结果复用 + 后台重探**：`ServerConfig.apiVersion` 已持久化且探测后回写——手动连接时若已有非 UNKNOWN 版本，直接用它发起后续流程，探测降级为后台异步刷新（保留 #132 的"UNKNOWN 保留原值"语义）。省 1–2 次 RTT/连接。
2. **preLoadSessions 与 SSE 并行**：SSE 先行（首事件立即翻转"已连接"），预加载并发补数据（EventDispatcher 本就是增量合并语义，setSessions 幂等）。注意点：① 保留"preload 失败回退无 directory 拉取"路径；② `syncFromRest` 的 RestValidation FSM 事件顺序不变；③ 会话列表 UI 在 preload 未完成时的空窗由 SessionListViewModel 独立拉取兜底（现状已如此）。
3. **预加载内部并行**：项目间 `/session` + `/session/status` 并发（受控并发数 2–4），多项目用户收益线性。
4. **不建议**：恢复 V2-first 单探不带交叉验证（#83 回归）；或为速度跳过预加载（beta.4 假象，会话状态/红点等功能依赖它）。

## 5. 复现环境与方法

- V1 隔离启动：`XDG_DATA_HOME=/tmp/v1srv/data XDG_CONFIG_HOME=/tmp/v1srv/config HOME=/tmp/v1srv OPENCODE_SERVER_PASSWORD=... opencode serve --hostname 127.0.0.1 --port 4198`（不隔离会撞现有 DB 报 "Database is not empty and has no session table"）。
- 时延测量：`curl -w 'ttfb=%{time_starttransfer} total=%{time_total}'` 各 3 轮；SSE 首事件 `timeout 4 curl -sN ... | head`。
- 序列模拟：python subprocess 按 App 实际请求顺序计时（脚本见本报告 2.3，逻辑等价于 `SseConnectionManager.runSseConnectionLoop` + `ApiVersionDetector.detect` 的调用序）。
- git 考古：`git show v0.3.0-beta.4:...`（探测器无交叉验证）、`git show 4c2b6d8a`（修复）、`git log -S ServerHeartbeat`（心跳过滤史）。
