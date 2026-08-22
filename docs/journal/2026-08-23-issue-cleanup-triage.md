# issue-cleanup-triage（2026-08-23）

> 状态：进行中（#191 已登记待实现；GitHub 清理与报错甄别当场完结）
> 关联：（无 spec——#191 实现前如需留档再补）· GitHub Issues #2/#3/#4（已关）
> 来源：用户指令「清理下github上的issue，然后看看本地的报错是否需要修复，是否可以根治」

<!-- 过程中的取证/验证证据直接写本文件；backlog.md 只留 ≤3 行卡片。 -->

## 一、GitHub issue 清理（完结）

#2/#3/#4 均为 #151 上报管道 E2E 验证产物（dev.20，2026-08-22）。全部关闭并留说明评论：
所含错误均已在后续版本修复——search 端点 POST→GET（ea339721）、指纹冒号归一化（4e71f79e）、
日志模板字面量（678317b4）。Issue #4 的去重追加评论作为管道行为证据存档于
docs/journal/2026-08-22-ui-batch.md。清理后 repo 0 open issue。

## 二、本地报错甄别（真机 pid 29634，dev.21，08-23 02:00–02:14 全量 logcat + 空 crash buffer）

| 类 | 内容 | 判定 |
|----|------|------|
| A | MIUI 系统噪音（SettingTrigger/InsetsSource/ContentCatcher/Zygote/ashmem/libc 等 50+ 行） | 不修（平台内部，非我方代码） |
| B | SLF4J No providers → NOP（启动一次性 ×3） | 不修（cosmetic；加 binding 零收益） |
| C | 服务器离线启动突发（ModelConfigDelegate×4、MessageDataDelegate Connect×6 等） | 不修（预期降级路径，有 cache fallback，正是排障线索） |
| D | **SessionStateService L2 stale 无限循环** | **需要修复，可根治** → 登记 #191 |

FATAL/AndroidRuntime 41 行全部属于此前 E2E 的 uiautomator launcher 进程（pid 11965/11146 等），与本应用无关。

## 三、#191 根因分析（D 类详解）

### 实测表现

ses_fed8bacabffe3Nbx8duMopTCIn 等待提问期间：每 5s 两条 WARN（L2 stale + skip zombie interrupt）
+ 1 次 REST 校验请求，无限持续（实测 02:00:31→02:09:46 连续，staleness 19.8s→224.7s 单调递增）。
量化：每等待会话 24 行 WARN/分钟 + 12 次 REST/分钟；挂机一夜 ≈ 1.1 万行日志 + 5760 次请求。
错误上报信噪比被污染（#2/#3/#4 正文大半被这两行刷屏占满，真 404 被淹没）。

### 机制链（设备构建 dev.21 = 代码 HEAD，非旧版本残留）

```
会话有未答 question → 服务器合法 running（等待输入，无 SSE 事件）
→ checkStaleness: Busy + 15s 无事件 = "L2 stale"（WARN①）→ REST 校验
→ 服务器确认 Busy + 静默 >3min → 僵尸判定启动
→ hasPendingUserInput=true → guard 跳过 interrupt，保持 Busy（WARN②，2026-08-18 E2E-G 决策）
→ RestValidation 故意不刷新 lastEventAt（2026-08-14 设计）
→ 5s 后下一轮 …… 无终止条件
```

状态本身是对的（保持 Busy 正确）；错的是观测节奏。与 BusyIndicatorSmoother 头注释记载的
drain 循环不同：那个 3min 内被 zombie 判定终结；本变体 zombie 永不触发、lastEventAt 永不刷新，
只要 question 未答 + 服务器 running 就无限持续。active-children guard 分支同构。

### 版本矩阵（用户提醒后双版本验证）

| 环节 | V1（GET /session/status） | V2（GET /api/session/active） |
|------|------|------|
| 提问/权限等待期服务器报什么 | **busy**（V1 二进制 grep 实证：question execute 阻塞在 run 内部 awaiting answer；状态 map busy 由 runner 生命周期维护 onBusy:set(busy)，run 结束才 delete+set(idle)） | **busy**（真机实测 + drain 语义） |
| 等待期 SSE 事件 | 无（question 事件不映射 FSM） | 无（同） |
| idle 会话在端点中 | 缺失（map 只含非 idle） | 缺失 |
| session.status SSE | 有（run 结束发 idle → lastEventAt 刷新，循环断） | 无（靠 message delta 事件刷新） |

**结论：等待期间两版本都在无限刷**（机制同构）。V1 唯一优势是答题后服务器主动发
session.status(idle) 自愈；V2 靠 message 事件。已知的其余 V1/V2 差异均已被现有代码处理
（V1 active 恒空 → reconcile 早退；V1 abort 级联 → 自动 interrupt 已全局禁用）。

### Ground truth 实证（V2，本机 4199）

- 循环期间 GET /api/session/active → {data:{ses_fed8…:running}}（服务器确认等待中）
- 用户答题后 turn 跑完：time.idle=1787422195794（≈02:09:55），active 归空，循环随之终止
  （logcat 02:09:46 后无新 L2 stale）——证明触发源确为 pending-input 等待态，非死锁

## 四、#191 修复设计定案（方案 B：等待确认自适应降频）

落在 SessionStateService（版本无关层），V1/V2 通吃，不动 FSM 语义/E2E-G 决策/
zombie interrupt 禁用/RestValidation 不刷 lastEventAt 设计：

1. **打标**：REST 确认 Busy + (pendingInput || activeChildren) → 记 waitingConfirmedAt
   （两版本该场景都返回 busy，无需版本分支）
2. **抑制**：checkStaleness 对 60s 内已确认等待的会话跳过 L2 触发（其余会话行为不变）
3. **清标**：该会话任何真实 SSE 事件（lastEventAt 变化）即清——V1 session.status/delta、
   V2 MessagePartDelta/Updated 两条路都已接 mapSseEventToFsm，版本安全
4. 代价：SSE 死亡 + 用户在他端答题的最坏发现延迟 60s（换日志/请求量降 ~92%）

备选（已否）：A 仅对 pending 会话拉长阈值（盖不住 active-children 分支）；C 仅日志节流
（REST 风暴仍在）。可选叠加：同类日志 60s 节流。

### 实现注意

- 单文件 SessionStateService.kt + 单测（StubCollaborator 基建现成；含 V1/V2 两种触发路径用例
  ——JVM 层用 fetchSessionStatuses stub 模拟两版本响应形状）
- 开放验证点（低风险）：hasPendingUserInput 读内存 map，若本地残留已答 question 会误判——
  当前服务器持续返回 running 与真实等待吻合
- 相关测试参考：SessionStateServiceTest「zombie Busy with pending user input skips interrupt」（:257）
  需适配新打标行为断言
