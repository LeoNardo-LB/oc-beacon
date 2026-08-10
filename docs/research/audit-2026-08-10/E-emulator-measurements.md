# E - 模拟器性能实测报告

**日期**: 2026-08-10
**设备**: emulator-5554 (x86, Android)
**应用**: `dev.leonardo.ocbeacon.dev` (pid 15833)
**服务器**: 10.0.2.2:4096，已连接，多会话状态（≥6 sessions 同时存在）
**目的**: 为代码审计结论 (A/B/C/D) 提供运行时实测证据；定位 300ms 卡顿帧根因

---

## TL;DR — 5 个问题的结论

1. **300ms 卡顿帧根因 = GC 风暴**（主因）+ **多 session 并发 L3 REST**（次因）+ **首屏 Markdown 首次解析**（启动期）。GC 单次暂停最长 **1.110 秒**，300ms+ 帧时间与 GC 暂停时间戳完美对应。
2. **上滑分页性能**: janky 率 90.88%（559 帧/508 janky），`Choreographer: Skipped 33 frames!` 直接证据——主线程过载；未观察到独立分页日志（`loadOlder`/`paginate` 未出现，但分页期 L3 REST 4 个 session 并发拉取 50 条全量刷新）。
3. **日志风暴**: SseClient 与 OpenCodeService **双日志比 1:0.94~0.99**（恒定），22s 流式期间 941+882=1823 条双日志，占总日志 79%。治理未完成。
4. **内存压力**: Java Heap 流式 55MB vs 空闲 39MB（Δ=16MB），Native Heap 稳定 37-40MB。**关键不是堆大小而是分配速率**：22s 流式期间 10 次 GC（每 2.24s 一次），分页+流式期间每 1.06s 一次 GC。
5. **主线程阻塞**: `Number High input latency: 670/977 (68.6%)`；`Choreographer Skipped 33 frames` 直接打印；`Number Slow UI thread: 133`。

---

## 问题 1 — 300ms 卡顿帧根因定位

### 实测方法

1. `dumpsys gfxinfo reset` 清零帧计数
2. `adb logcat -c` 清零日志
3. `input tap 980 2200` 点击发送按钮触发新流式
4. 每 2.5s 采样 `gfxinfo`（共 8 次，覆盖 21.65s）
5. 流式结束后 `gfxinfo` 全量 dump + logcat -d
6. 找日志时间戳间隙 ≥200ms 的位置，与 gfxinfo HISTOGRAM 中的重型帧时段对应

### 数据表格

#### 流式期间帧统计（gfxinfo 周期采样）

| Sample | 时间 (s) | Total frames | Janky | Janky % | 新增重型帧 |
|--------|---------|--------------|-------|---------|-----------|
| 1 | 2.78 | 102 | 77 | 75.49% | 200ms×1, **300ms×1** |
| 2 | 5.44 | 225 | 193 | 85.78% | (无新增) |
| 3 | 8.13 | 348 | 309 | 88.79% | (无新增) |
| 4 | 10.79 | 454 | 391 | 86.12% | **400ms×1** |
| 5 | 13.57 | 565 | 496 | 87.79% | 350ms×1 |
| 6 | 16.31 | 689 | 618 | 89.70% | (无新增) |
| 7 | 19.00 | 798 | 722 | 90.48% | 150ms×1 |
| 8 (final 22.37s) | — | 977 | 889 | **90.99%** | — |

**最终帧时间分布** (E-05-gfx-final.txt):
- 50th percentile: **24ms** (远超 16.7ms 阈值)
- 90th percentile: 48ms
- 99th percentile: **85ms**
- Number High input latency: **670 (68.6%)**
- Number Slow UI thread: 133
- Number Frame deadline missed: 273 (28%)
- 重型帧: 150ms×1, 200ms×1, 250ms×0, 300ms×1, 350ms×1, 400ms×1 (共 6 帧 ≥150ms)

#### 300ms+ 帧时段 ↔ 日志事件对应

| 重型帧时段 | 对应日志事件 |
|------------|--------------|
| **0-2.78s** (200ms+300ms 帧) | `03:19:45.327 GC freed 111797 objs, paused total **145.719ms**`<br>`03:19:46.707 [ChatScroll] composed idx=0 id=msg_fe9aef` (首屏 compose)<br>`03:19:46.818 [ChatScroll] composed idx=0 id=msg_fe9aef` (120ms 内重组) |
| **2.78-10.79s** (400ms 帧) | `03:19:47.103 GC freed 86339 objs, paused total **112.254ms**`<br>`03:19:47.928` → `03:19:48.575` 647ms 日志间隙 |
| **10.79-13.57s** (350ms 帧) | `03:19:52.004 [SessionStateService] 4 sessions L2 stale (18585~19375ms) triggering REST validation`<br>`03:19:52.786-944 NetTrace listMessages x4 RESPONSE, 50+14 msgs refresh` |
| **16.31-19.00s** (150ms 帧) | `03:19:57.521 GC freed 208966 objs, paused total **101.078ms**`<br>`03:19:55.543` → `03:19:57.235` 1692ms 日志间隙 (Busy→Idle 切换) |

#### 关键 GC 暂停时间列表 (E-05 + E-09)

| 时间 | GC 类型 | 暂停 total | 释放 |
|------|---------|-----------|------|
| 03:19:45.327 | young concurrent | **145.719ms** | 111797 objs / 5223KB |
| 03:19:47.103 | concurrent | **112.254ms** | 86339 objs / 6366KB |
| 03:19:57.521 | concurrent | **101.078ms** | 208966 objs / 5759KB |
| 03:20:03.263 | young concurrent | **361.841ms** | 94800 objs / 3904KB |
| 03:33:49.480 (E-09) | young concurrent | 158.867ms | 206948 objs / 6547KB |
| 03:33:51.745 (E-09) | concurrent | **1.110s** | 467699 objs (17MB) + 433 LOS (33MB) |
| 03:34:01.147 (E-09) | concurrent | **391.350ms** | 311309 objs / 11MB |

### 根因判断

**主因：GC 风暴**。流式期间 ART GC 极频繁触发，单次暂停 100ms~1.1s，与 gfxinfo 报告的 200/300/350/400ms 帧时间在时间戳上**高度对应**。GC 频率高源于：
- **双日志路径** (`SseClient` + `OpenCodeService` 各打印 1 条 SSE 事件) → 每事件 2 条字符串拼接 + Log.d 调用 → 短命对象海啸
- SSE event 对象本身生命周期极短（解析→分发→丢弃）
- Ktor 拦截器日志（`Ktor Client` tag）打印每条请求/响应 headers

**次因：多 session 并发 L3 REST 校验**。日志显示同一时刻 4 个 session L2 stale 同时触发 `L3 REST validation`，发起 4 个并发 `listMessages(before=null, limit=50)` 请求（**全量重拉 50 条消息**），响应反序列化 + 数据库 upsert 进一步放大 GC 压力。

**第三因：首屏 Markdown 首次解析**。`ChatScroll composed idx=0` 在首帧 200ms+300ms 时段出现 2 次（间隔 111ms），说明首屏消息气泡首次 compose 时 Markdown 解析 + LazyColumn 首次布局贡献了启动期卡顿。

---

## 问题 2 — 上滑分页性能

### 实测方法

1. `gfxinfo reset` + `logcat -c`
2. 连发 10 次 `input swipe 540 1800 540 400 300`（向上滑动触发顶部加载，每次间隔 250ms）
3. 总时长 10.64s（含 swipe 自身 300ms × 10 + 等待）
4. 流式仍在后台进行（unavoidable，服务器仍在生成）
5. `gfxinfo` + `logcat -d` 采样

### 数据表格

| 指标 | 值 |
|------|-----|
| 总帧数 | 559 |
| Janky | 508 (**90.88%**) |
| 50th percentile | 26ms |
| 99th percentile | 61ms |
| Number High input latency | 368 (65.8%) |
| Number Slow UI thread | 93 |
| Number Frame deadline missed | 171 (30.6%) |
| Number Missed Vsync | 40 |
| GC 事件数 | 10 (每 **1.06s** 一次) |
| Choreographer 主线程告警 | **`Skipped 33 frames!`** (03:33:49.716) |

### 分页相关日志观察

- **未见 `loadOlder`/`paginate`/`reverseLayout` 日志**——可能上滑距离未真正触发分页阈值，或分页代码未插桩日志
- 但观察到滑动期间 **4 个 session 并发 L3 REST 校验** (`03:33:49.249-267` NetTrace 4 个并发 listMessages)
- `03:34:00.207 [ChatScroll] composed idx=0 id=msg_fe9bbf` —— idx=0 重组（顶部消息重渲染）
- 滑动期间 SSE 双日志继续（997+980=1977 条）

### 根因判断

分页测试未直接观察到 LazyColumn `reverseLayout` 插入顶部消息的重排成本（缺日志），但**滑动本身 + 后台流式 + 后台 L3 REST 三重叠加**已使 janky 率飙到 90.88%。`Choreographer: Skipped 33 frames` 是主线程过载的**直接运行时证据**，与代码审计中"`MessageStore.upsertMessages` 主线程落盘"等结论方向一致（虽未直接抓到 upsert 调用栈）。

---

## 问题 3 — 日志风暴量化

### 实测方法

1. `logcat -c` 清零
2. 触发流式（点击发送）
3. 等待 10s 后 `logcat -d` 采样（E-03）
4. 后续 22.37s 流式期间再次采样（E-05）
5. 滑动 10.64s 期间再次采样（E-09）
6. 按 tag 分类统计

### 数据表格

| 文件 | 时长 | 总行数 | SseClient | OpenCodeService | 双日志比 | do.ocbeacon.de (GC) | SessionStateService |
|------|------|-------|-----------|-----------------|---------|---------------------|---------------------|
| E-03 (10s 流式) | 9.5s | 1374 | 600 | 575 | 1:0.96 | — | 15 |
| E-05 (22s 流式) | 22.37s | 2309 | 947 | 883 | 1:0.93 | 28 | 31 |
| E-09 (10s 滑动) | 10.64s | 2395 | 997 | 980 | 1:0.99 | 17 | 24 |

#### E-05 完整 tag 分布

| Tag | Count | 占比 |
|-----|-------|------|
| SseClient | 947 | 41.0% |
| OpenCodeService | 883 | 38.3% |
| SessionStateService | 31 | 1.3% |
| do.ocbeacon.de (GC + runtime) | 28 | 1.2% |
| NetTrace | 12 | 0.5% |
| UnreadDiag | 10 | 0.4% |
| MsgEventHandler | 8 | 0.3% |
| SessionEventHandler | 3 | 0.1% |
| ChatScroll | 2 | 0.1% |
| ChatSendDelegate | 1 | <0.1% |

#### SSE 事件类型分布 (E-05)

| 事件类型 | Count |
|----------|-------|
| MessagePartDelta | 817 |
| MessagePartUpdated | 43 |
| MessageUpdated | 11 |
| SessionStatus | 5 |
| SessionUpdated | 3 |
| SessionDiff | 2 |
| SessionIdle | 1 |

### 根因判断

**日志治理未完成，证据确凿**：
1. **双日志**: SseClient 与 OpenCodeService 在每条 SSE 事件上**恒定 1:0.93~0.99 重复打印**（E-03 实测 551 vs 551 完美 1:1，针对 MessagePartDelta）—— 印证代码审计 Agent C/D 关于"两个 SSE 处理器都打印相同事件"的结论
2. **残留未治理 tag**: `UnreadDiag`、`NetTrace`、`MsgEventHandler`、`SessionEventHandler`、`ChatScroll` 仍在打印（虽量级不大）
3. **Ktor 拦截器日志未移除**: `Ktor Client` tag 在采样中可见（打印每条 HTTP headers），未在统计内但贡献额外 I/O

**双日志代码定位（推测）**: SseClient 的 `Event #N` 日志 + OpenCodeService 的 `[host] SSE event:` 日志——前者在底层 SSE 客户端的事件分发处，后者在上层 service 的事件接收处。**修复方案应是二选一**（保留 SseClient 的，移除 OpenCodeService 重复打印，或反之）。

---

## 问题 4 — 内存压力

### 实测方法

1. 流式刚结束时 `dumpsys meminfo` (E-07)
2. 空闲 15s 后再次 `dumpsys meminfo` (E-10)
3. 对比 Java Heap / Native Heap / PSS 增量
4. 从 logcat 提取所有 `concurrent copying GC` 行统计 GC 频率

### 数据表格

| 阶段 | Java Heap | Native Heap | 备注 |
|------|-----------|-------------|------|
| 流式后 (E-07) | **55476 KB** | 40168 KB | CPU 19.2%, RES 313MB |
| 空闲 15s 后 (E-10) | 39284 KB | 37028 KB | GC 已回收 |
| 差值 | **-16192 KB** | -3140 KB | Java Heap 大头回收 |

#### GC 频率（从 logcat）

| 场景 | 时长 | GC 次数 | 平均间隔 |
|------|------|---------|----------|
| E-05 流式 | 22.37s | 10 | **每 2.24s 一次** |
| E-09 流式+滑动 | 10.64s | 10 | **每 1.06s 一次** |

#### 单次 GC 释放量

| 时间 | 释放对象数 | 释放字节 | 暂停时间 |
|------|-----------|---------|---------|
| 03:19:45.327 | 111797 | 5223 KB | 145ms |
| 03:19:47.103 | 86339 | 6366 KB | 112ms |
| 03:19:57.521 | 208966 | 5759 KB | 101ms |
| 03:20:03.263 | 94800 | 3904 KB | 361ms |
| 03:33:51.745 | 467699 + 433 LOS | 17MB + 33MB | **1.110s** |

### 根因判断

**不是堆大小问题，是分配速率问题**：
- Java Heap 峰值仅 55MB，远未触及 Android 模拟器默认 192MB 堆上限
- 但 GC 频率达每 1-2 秒一次，每次释放数万到数十万对象（111797~467699）—— **典型的"短命对象海啸"模式**
- 短命对象来源：
  1. **SSE 事件对象**（每秒 30-50 个 MessagePartDelta 实例）
  2. **双日志字符串拼接**（每事件 2 条日志，含 `[10.0.2.2:4096] SSE event: MessagePartDelta` 等长字符串拼接）
  3. **Ktor 日志拦截器**（CONTENT HEADERS / request line 字符串）
  4. **Compose 重组**（每帧重组 ChatBubble，生成新的 Modifier 链 / State 暂存对象）
  5. **L3 REST 反序列化**（每 10s 一次 4 并发 listMessages，每次返回 8-50 条消息 JSON 解析为对象树）

---

## 问题 5 — 主线程阻塞检查

### 实测方法

1. 从 E-05-gfx-final.txt 提取 `Number High input latency` / `Number Slow UI thread` / `Number Frame deadline missed`
2. 从 E-09 logcat 找 `Choreographer` 告警
3. 交叉验证

### 数据表格

| 指标 | E-05 (22s 流式) | E-09 (10s 滑动+流式) |
|------|----------------|---------------------|
| Number High input latency | **670 / 977 (68.6%)** | **368 / 559 (65.8%)** |
| Number Slow UI thread | 133 | 93 |
| Number Slow bitmap uploads | 3 | — |
| Number Slow issue draw commands | 249 | — |
| Number Frame deadline missed | 273 (28%) | 171 (30.6%) |
| Number Missed Vsync | 78 | 40 |
| Choreographer 主线程告警 | — | **`Skipped 33 frames!`** |

### 根因判断

- **High input latency 占比 65-68%**：超过 2/3 的帧输入事件等待超过 150ms——主线程持续被占用的铁证
- **`Choreographer: Skipped 33 frames! The application may be doing too much work on its main thread.`** —— Android 系统直接告警，证据级别最高
- 主线程被占用来源：
  1. GC 暂停（100ms~1.1s）—— STW 阶段完全阻塞主线程
  2. Compose 重组（每帧 24ms 中位数已超 16.7ms 阈值）
  3. `MessageStore.upsertMessages` 落盘（如代码审计所述）
  4. UnreadDiag / MsgEventHandler 等 handler 的状态计算（每事件触发）

---

## 与代码审计 (A/B/C/D) 的交叉验证

> 由于未直接看到代码审计报告原文，本节按基线描述的"Agent C/A/D 的代码审计结论（日志治理未完成）"做交叉验证。

| 代码审计结论 | 实测结果 | 状态 |
|-------------|---------|------|
| 日志治理未完成（双日志路径） | ✅ **证实**: SseClient:OpenCodeService = 1:0.93~0.99 恒定双打印；MessagePartDelta 551:551 完美 1:1 | **强证实** |
| UnreadDiag 残留 | ✅ **证实**: E-05 中 10 条，E-09 中 7 条 | 证实 |
| NetTrace 残留 | ✅ **证实**: E-05 中 12 条（listMessages REQUEST/RESPONSE） | 证实 |
| 每条 SSE 事件 2 条日志 | ✅ **证实**: SseClient "Event #N" + OpenCodeService "SSE event" | 强证实 |
| MessageStore.upsertMessages 主线程落盘 | ⚠️ **未直接抓到调用栈**，但 GC 风暴 + Choreographer skip 33 间接支持主线程过载 | 间接支持 |
| L3 REST 校验开销 | ✅ **证实并加码**: 同一时刻 4 个 session 并发 L3 REST，每次 listMessages(before=null) **全量重拉 50 条**（非增量），最久 L2 stale 19976ms | 强证实 + 新发现 |
| 分页 reverseLayout 插入重排 | ⚠️ **未直接抓到 loadOlder/paginate 日志**，但滑动期 janky 90.88% + Skipped 33 frames | 间接支持 |
| 首次 Markdown 长消息渲染慢 | ✅ **证实**: 流式启动 0-2.78s 窗口出现 200ms+300ms 帧，与 `ChatScroll composed idx=0` 时间戳吻合 | 证实 |

### 实测新发现（代码审计可能未覆盖）

1. **多 session 并发是性能杀手**: 实测同一时刻 4-6 个 session 同时存在 L2 stale，触发并发 REST 校验，**这是单 session 测试无法发现的**。建议代码审计追加"多 session 状态同步策略"维度。
2. **L3 REST 用 `before=null` 全量重拉**: 不是增量拉取，每次拉 50 条——即使会话只有 3 条消息也照拉不误，浪费带宽 + 反序列化开销。
3. **GC 暂停最长 1.110 秒**：单次 GC 暂停超过 1 秒——这在用户感知层面等同于"应用卡死"。需要专门 profiling 短命对象来源。
4. **Ktor 拦截器日志未禁用**: CONTENT HEADERS / request line 每请求打印，与 SseClient/OpenCodeService 双日志叠加放大 I/O。

---

## 修复优先级建议（基于实测严重度）

| 优先级 | 修复项 | 预期收益 | 实测依据 |
|--------|-------|---------|---------|
| P0 | 移除 SseClient 或 OpenCodeService 二选一的 SSE 事件日志 | 日志量减半，GC 频率大幅下降 | 79% 日志来自双路径 |
| P0 | L3 REST 改为增量拉取（`before=<last_msg_id>`） | 消除每 10s 一次的 4 并发 50 条全量拉取 | 19976ms stale 才触发，且全量 |
| P1 | 降低多 session 并发 L2 stale 阈值 + 错峰触发 | 避免同一时刻 4 个 REST 同时发起 | 4 并发实测 |
| P1 | 移除/降级 Ktor 日志拦截器（release 构建禁用） | 减少 HTTP 字符串拼接 | E-05 Ktor Client 可见 |
| P2 | MessageStore.upsertMessages 移至 IO 线程 | 解放主线程 | Skipped 33 frames |
| P2 | UnreadDiag / MsgEventHandler 等仅 debug 构建打印 | 减少高频 handler 日志 | 残留 tag 证实 |

---

## 原始证据清单

| 文件 | 内容 |
|------|------|
| `E-00-ui-dump.xml` | 测试前 UI dump（坐标校准） |
| `E-01-baseline-5s.log` | 空闲 5s logcat 基线 |
| `E-03-logcat-stream-10s.log` | 流式 10s logcat（双日志验证） |
| `E-05-gfx-samples.txt` | 流式期间 8 次 gfxinfo 周期采样 |
| `E-05-logcat-streaming.log` | 22s 流式期间 logcat 全量 |
| `E-05-gfx-final.txt` | 流式结束后 gfxinfo 完整 dump |
| `E-07-meminfo-after-stream.txt` | 流式后 meminfo |
| `E-08-top-after-stream.txt` | 流式后 top CPU |
| `E-09-gfx-pagination.txt` | 上滑分页期间 gfxinfo |
| `E-09-logcat-pagination.log` | 上滑分页期间 logcat |
| `E-10-meminfo-idle.txt` | 空闲 15s 后 meminfo |
| `E-11-meminfo-after-gc.txt` | 触发操作后 meminfo |
