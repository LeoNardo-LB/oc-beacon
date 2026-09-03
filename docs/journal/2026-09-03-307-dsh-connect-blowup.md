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

## 附：对 #299/#245 的阻塞

巨型会话载体挂在 3080——连接即崩使进场/滑动测试无法进行；#307 修复（或临时绕过）是两卡前置。
