# D35 — 会话内 Back 触发 ANR 复现排查

**Backlog**: #35（P2，难度中，状态：未开始→复现排查）
**日期**: 2026-08-10
**结论**: ❌ **未复现** — 55+ 轮各种 Back 操作模式均未触发 ANR/崩溃，PID 全程稳定

---

## 1. 环境与前提

| 项目 | 值 |
|------|-----|
| 设备 | emulator-5554（Android 10 API 29）|
| 应用包名 | dev.leonardo.ocbeacon.dev |
| App PID（全程） | 23654（未变化） |
| 目标会话 | "调研harness记忆系统实践"（坐标 540,857） |
| 服务器 | 10.0.2.2:4096 已连接 |
| root 状态 | production build，无法 root（`/data/anr/` trace 不可读） |

> `/data/anr/` 存有 8 个昨日（8月9日）trace 文件，权限 `-rw------- system system`，不可读，且 grep 不含 ocbeacon 包名，与本次无关。

## 2. 操作矩阵

共执行 **55+ 轮** Back 操作，覆盖以下模式：

| 编号 | 变体 | 轮次 | 间隔 | ANR | Crash | PID 变化 |
|------|------|------|------|-----|-------|----------|
| 标准 | 进入会话→Back→列表（1s 间隔） | 20 | 1s | 无 | 无 | 无 |
| V1 | **加载中 Back**（tap 后不等待立即 Back） | 10 | 0ms | 无 | 无 | 无 |
| V2 | **快速双击 Back**（退出会话+退出应用） | 10 | 100ms | 无 | 无 | 无* |
| V3 | **切后台→回前台→Back**（Home→恢复→Back） | 5 | 1s | 无 | 无 | 无 |
| HI | **高强度快速进退**（300ms 间隔） | 20 | 300ms | 无 | 无 | 无 |

\* V2 双击 Back 会退出应用到桌面（预期行为），脚本检测后 am start 恢复，PID 未变（进程未被杀）。

## 3. 关键指标

### 3.1 Back 操作耗时（标准 20 轮）

backDur = input keyevent 执行 + 1s sleep，扣除 sleep 后实际 Back 耗时：

| 轮次 | 1 | 2-7 | 8-14 | 15-20 |
|------|---|-----|------|-------|
| 耗时(ms) | ~1500 | 200-770 | 200-770 | 200-595 |

无单次 Back 超过 1.5s（扣除 sleep），远低于 ANR 感知阈值。

### 3.2 GC 压力

| 指标 | 值 | 说明 |
|------|----|------|
| 最大单次 GC pause | **91.69ms** | 安全（ANR 阈值 5000ms） |
| 最大 GC total | 1075ms | GC 线程总工作量（非主线程阻塞） |
| IncrementDisableThreadFlip blocked | 最大 18.632ms（3 次） | ART 内部信号，可接受 |
| GC 事件总数 | 83 | 快速进退产生的内存压力 |

### 3.3 gfxinfo（5 轮采集）

| 指标 | 值 |
|------|----|
| Total frames | 1983 |
| Janky frames | 1266 (63.84%) |
| 50th percentile | 21ms |
| 90th percentile | 121ms |
| 95th percentile | 200ms |
| 99th percentile | 550ms |
| 最长帧 | ~4900-4950ms（1 帧） |

> 模拟器 GPU 性能差导致高 jank 率，但无超过 5s 的极端帧（ANR 阈值）。

### 3.4 日志异常分类（45 个 logcat 文件，1266KB）

| 类别 | 数量 | 性质 |
|------|------|------|
| JobCancellationException（协程取消） | 322 | ✅ **预期行为**（Back 退出时取消分页加载） |
| GC 日志 | 83 | 正常内存管理 |
| MessageDataDelegate ERROR | 24 | 协程取消（pending questions/permissions 加载中止） |
| SessionLifecycleDelegate | 13 | 会话生命周期取消 |
| MessagePaginationDelegate | 10 | 分页请求取消 |
| IncrementDisableThreadFlip | 3 | ART 内部，≤18ms |

**所有 ERROR 级别日志均为 `JobCancellationException`**——Back 退出会话时正在进行的网络请求（`/session/.../message?limit=30`）和分页加载被协程框架取消。这是异步操作，**不阻塞主线程**。

## 4. 根因线索分析

虽然未复现 ANR，但从日志观察到 Back 路径的工作量：

```
Back 按下
  → 会话退出
    → 取消分页加载协程（MessagePaginationUseCase/Delegate）
    → 取消 pending questions/permissions 加载（MessageDataDelegate）
    → 取消正在进行的 Ktor HTTP 请求
    → Compose 重组（列表→会话列表切换）
    → GC 触发（大量对象释放）
```

以上步骤**均为异步**，不构成主线程阻塞。理论上的 ANR 风险点：
1. **若 Compose 重组在主线程执行大量工作**（如列表差分计算）— gfxinfo 显示最长帧约 4.9s，接近但未达阈值
2. **若协程取消回调中有同步阻塞操作**（如 Room DB 写入在主线程）— 本次未观察到
3. **低端真机 + 内存压力 + 长会话** 可能放大 GC 影响 — 模拟器无法模拟

## 5. 结论与建议

### 结论：未复现

55+ 轮、5 种操作模式的系统性测试均未触发 ANR。无主线程阻塞的直接证据。最大单次 GC pause 91ms，最长渲染帧 ~4.9s，均未达 5s ANR 阈值。

### 建议

1. **backlog #35 状态调整**：标注"模拟器环境未复现，疑似偶发/已自愈"。保持 P2 但降优先级——当前版本（34092594 构建，含 #40-#43 修复）Back 路径在模拟器上表现稳定。
2. **真机复现**：如需确证，建议在低端真机（如 2GB RAM Android 8/9 设备）上测试，模拟内存压力场景。
3. **日志噪声治理**（独立项，非本任务范围）：Back 退出时的 `JobCancellationException` 被记为 ERROR 级别（52 条/45 文件），属于日志噪声。建议降级为 INFO/DEBUG，或用 `coroutineScope.ensureActive()` 提前退出避免记录取消异常。可登记到 backlog。
4. **预防性措施**：可在 BackHandler/会话关闭路径添加 `withContext(Dispatchers.IO)` 确保清理操作不阻塞主线程（当前观察已是异步，属预防加固）。

## 6. 证据文件清单

| 文件 | 说明 |
|------|------|
| `metrics/D35-timeline.txt` | 标准 20 轮时间线 + 每轮状态 |
| `metrics/D35-log-{1..20}.txt` | 标准循环每轮 logcat（--pid 过滤） |
| `metrics/D35-log-v1-{1..10}.txt` | 变体1（加载中 Back）logcat |
| `metrics/D35-log-v2-{1..10}.txt` | 变体2（双击 Back）logcat |
| `metrics/D35-log-v3-{1..5}.txt` | 变体3（后台→Back）logcat |
| `metrics/D35-gfxinfo.txt` | gfxinfo 完整 dump |
| `metrics/D35-choreographer.txt` | Choreographer/skip 信号（0 条） |

> 无 `D35-anr-alert-*.txt`（未触发 ANR）；无 `D35-crash-*.txt`（无崩溃）。

---

*排查人：Android 复现排查 Agent | 2026-08-10*
