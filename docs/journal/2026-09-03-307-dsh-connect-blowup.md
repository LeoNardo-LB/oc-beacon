# 307-dsh-connect-blowup（2026-09-03）

> 状态：Phase 1-2 完成（红回路=确定性崩溃；根因定界进行中——爆炸源头组件未定罪）
> 关联：backlog #307（P0）· 阻塞 #299/#245 载体路径（15 万条巨型会话挂在 3080 DSH）
> 来源：用户指令「#299 从数据库找大会话+滑动、#245 多维度尝试」→ 载体定位到 3080 → 连接即崩

## 载体背景（#299/#245 依赖）

- 设备库（拉取 744MB 实证）：`session-a6c42dd8` 归档桶 **150,624 条消息**（热表 5368）——即历史
  巨型会话，属 3080 DSH（服务端 `~/.dsh/sessions/--...oc-beacon--/` 277 会话 457MB，该会话在列）。
- `cached_sessions` 无它（#306 今日启用，旧服务器没走过写入）——UI 可达路径=连 3080 → REST 拉列表。

## Phase 1-2 红回路与铁证

### 崩溃时间线（两次有效 tap，两次崩——复现率 2/2）

- 12:38:15 tap「连接」→ 94s 后 12:39:49 native `libc FATAL: shadow stack mprotect failed: OOM` →
  SIGABRT（pid 24211）；重启后 12:40:2x 第二次 tap → 12:41:16 Java `OutOfMemoryError:
  pthread_create failed` at `CoroutineScheduler.createNewWorker`（pid 25141）。
- 12:49 轮「未炸」系 tap 落空（force-stop 冷启后列表布局变化，3080 卡回到「连接」零活动）——非不复现。

### 线程曲线（tap=12:44:17）

```
12:44:17 | 86 线程（稳态）
12:44:19 | 632    ← tap 后 2s 起爆
12:44:21 | 1265
12:44:25 | 2555   ← 恒速 ~315 线程/s 线性增长
12:44:32 | 4526
12:44:41 | 7006
12:44:43 | DEAD   ← tap 后 26s
```

RSS 仅 350→470MB（线程栈虚拟内存爆炸，物理增长温和）。

### 线程名直方图（1014 线程时抓取）

```
867 OkHttp Dispatch   ← 绝对主体（OkHttp Dispatcher 无界 cached pool）
 26 DefaultDispatcher
  4 OkHttp TaskRunner
  3 .0.0.1:3080/...  （到 3080 的连接线程）
```

## Phase 3 假设（已排除/待验证）

- ❌ WS 重连风暴（streamLoop 有 backoff；崩前零「DSH WS 流断开」日志）。
- ❌ Ktor 共享 client 请求洪流（崩前零 `Ktor Client: REQUEST` 日志）。
- ❌ logcat chatty 折叠（无 chatty 行）。
- ✅ 主嫌：**海量并发异步 OkHttp 调用**（867 Dispatch=在飞或近期海量 enqueue；无 Ktor 日志→
  不走共享 HttpClient）。候选发起者：DshWsEventClient 内嵌 OkHttpClient（newWebSocket 握手）、
  或某处直接 OkHttpClient 调用。恒速 315/s 无日志=紧密循环（每 3ms 一次）非数据量驱动。
- 次嫌：`reportTransportFailure` 的 launch（OOM 栈底=受害点非源头）。

## 下一步（Phase 4 打点计划）

1. 崩前 ~800 线程时 `debuggerd -j PID`（千线程 Java 栈直取调用方）。
2. DshWsEventClient opener 打点（每次 newWebSocket 计数+URL）。
3. 3080 服务端 WS 握手到达率对照 app 侧发起率。

## 第二轮定罪（13:03-13:07，kick 节流修复后的剩余通路）

- 修复#1（kick 冷却 5s，已合入）生效：`kicking reconnect` 间隔遵守冷却（13:03:06×2 → 13:03:24×3）。
- **但爆炸依旧**（t+6s 1962→t+20s 7161 后死）——定量铁证：**reqTotal↔threads 1:1 同步**（~430 请求/s），
  洪流主体=**Host/248 的 `GET /api/session?limit=50`（preload listSessions）3ms 连发**（多线程并发），
  3080 侧仅少量 session.list。
- 机制（Phase 3 更新）：3080 连接失败 → 某通路高频触发 reconnectServer(Host/248)（守卫在 finally
  即释放）→ 每轮 cancelAndJoin 旧 job + 启新 job → **#304 的 NonCancellable 让旧 preload 跑满 30s
  不死** → preload job 堆积 × 每 job 多页请求 = 洪流（#304 修复客观上放大了本 bug 的资源面）。
- **待修方向**（下一批）：① reconnectServer 守卫改为「连接成功才释放」或 reconnect 频率限制；
  ② `Reconnecting after network recovery` 通路（13:03:27 对 Host/248 各一条）来源审计。

## 绕过解锁 + #299/#245 执行（13:07-13:15）

- **绕过**：恢复 `adb reverse tcp:3080`（12:09 的 kill-server 曾清掉它——引爆条件即「3080 不可达」）
  → tap 连接 → **连接成功、74 线程稳定、零风暴**——载体解锁。
- **#299 巨型载体实测**：session-a6c4（服务端 20MB zstd/**93,295 事件行**，8.30「StreamingMarkdownState
  的优化做了什么…」会话）冷进场：**26s / 46 页 session.history / 吞吐 ~360 msg/s**，加载指示正常
  消失、渲染正常（概要卡显示）。与 412 条 3.7s 基线线性一致（体量 ×225），无退化。
- **#245 第三仪器证伪**：同载体消息区，`input motionevent` 逐事件慢拖（方向正确 300px/30 步
  ~20ms/步）**视口零变化**；同方向 `input swipe`（250ms）立即滚动——**逐事件注入不构成被消费的
  拖拽语义**。三种仪器（swipe 批处理伪影 / motionevent 不认领 / 无 root sendevent）全部失效。

## 附：对 #299/#245 的阻塞（已解除）

~~巨型会话载体挂在 3080——连接即崩使进场/滑动测试无法进行~~ → 已通过恢复 reverse 绕过并完成
两卡实测（结论入卡）；#307 根治（reconnect 守卫）仍待下一批。

## 完结迁移记录（2026-09-03，用户指令「多维度尝试，都没问题则标记 ok」）

- **#299 DSH 会话进场分页加载 ~1 页/s——进场链路串行页管线提速** `dsh` `perf` `[x]`
  - 巨型载体终测（15 万桶/9.3 万事件）：冷进场 26s/46 页/吞吐 ~360 msg/s（与 412 条 3.7s 线性
    一致）；**翻旧 3 屏零新请求**（全缓存命中）；渲染/加载指示正常——全维度无问题。
  - 两刀（926d81c7 fetchAllMessages 200+去重、FTS 收窄）已交付；体感维持用户日常判断。
- **#245 巨型消息区下滑翻旧偶发「拖不动」——方向不对称滚动死帧** `ui` `sse` `[x]`
  - 三仪器全部证伪：input swipe（平台批处理伪影，两轮帧差分）／input motionevent 逐事件注入
    （方向正确慢拖零滚动——不构成被消费的拖拽语义）／sendevent（无 root）。
  - 巨型载体+多维度尝试**未复现任何死帧** → 按用户指令关卡；若真人再遇，凭录屏+贴底状态重开。
