# OC Beacon 代码质量审计报告（第二期）：跨维度系统性审计

> **报告类型**：静态代码审计（Dimensions——移动端/安卓、JVM/GC、网络连接、SSE、AI Agent、样式/组件/编码风格统一、Markdown、文件系统、一致性、竞态条件、设计模式、过度设计、技术债）
> **审计日期**：2026-08-13 ~ 08-14（基线 = master @ 3a866bed + 31 个未提交 WIP 文件，按工作区当前状态审计）
> **审计范围**：`app/src/main/kotlin/**`（470 文件 / 63,157 行）+ AndroidManifest + build.gradle.kts + proguard + 备份规则 + CI
> **审计方法**：13 维度模式扫描 → 5 路并行分区深挖（data / domain+di+service+入口 / UI-chat / UI-其他 / 全库跨切面）→ 主代理逐条人工复核（每条发现均回读 文件:行号 验证，含对子代理结论的降级/纠正）
> **结论置信度**：所有 High/Medium 条目经主代理人工复核；交叉验证矩阵见 §5；纯机制推演条目（建议实测）已在矩阵标注

---

## 1. 执行摘要（Executive Summary）

### 1.1 总体结论

**本报告为第一期「内存泄漏/低性能」审计（`audit-2026-08-13-memory-perf/REPORT.md`，42 条）的姊妹篇**，聚焦其余 13 个质量维度。三句话结论：

1. **工程纪律在多数维度显著优于一般 Android 项目**：日志入口统一（0 处绕过 AppLogger）、导航参数统一（NavUtils 零裸解码）、i18n 完整（613 字符串 × 15 语言对齐）、主题 token 体系活跃、Keystore/备份/FileProvider 安全设计正确、Room 层损坏恢复+归档治理、并发路径系统性 RS-0xx 修复。
2. **本期发现 4 个 High、29 个 Medium、68 个 Low**：最高风险集中在 **SSE 心跳机制缺陷（阻塞读挂死 + V1/V2 行为不一致）**、**V2 REST/SSE part id 契约错位（可能双份渲染）**、**多服务器场景的共享状态竞态（pendingInputs/currentServerId/sessionId 键）**、**Android 15+ dataSync 前台服务 6 小时时限**。
3. **重大交叉验证成果**：第一期 P0 修复（C-1/H-1/H-2/H-3/M-9）已由提交 `c0c74a4c`（2026-08-13 23:39）落地——WebView 三处销毁 + 图片解码降采样均已实装并复核（详见 §6 正面确认 #1~#5）；同时确认 **WebViewScreen 因 `useNativeUi=true` 已成不可达死分支**（Q-5/D2-L7），第一期 C-1 的实际影响已降级。

| 风险集群 | 维度 | 后果 |
|---------|------|------|
| SSE 阻塞读 + 心跳检查只发生在行间（V1/V2） | 网络/SSE | 半开 TCP（kill -9/NAT 静默断）下连接永久挂死，重连/冷却机制全部失效，事件静默中断 |
| V1 心跳只在 heartbeat 事件刷新 vs V2 任意事件刷新 | SSE/一致性 | V1 服务器长流式可能每 40s 假超时断连（V2 注释自述该 bug 模式） |
| V2 REST part id="" vs SSE 派生 id 契约错位 | AI Agent/一致性 | REST 校验合并后同文本双份渲染（代码注释自认契约不一致） |
| 多服务器共享状态：pendingInputs HashMap / currentServerId 单值 / sessionId 全局键 | 竞态 | 双后端并发时丢事件、校验打错服务器、会话串台（静默难排查） |
| dataSync 前台服务 6h 时限无 onTimeout | 移动端 | Android 15+ 每 6h 系统终止服务，手动连接不恢复 |
| 第一期 P0 已修复 + WebView 分支已死 | 状态更新 | C-1/H-1/H-2/H-3/M-9 建议项已落地（c0c74a4c）；需回写第一期报告状态 |

### 1.2 数字一览（合并去重后）

| 严重级别 | 数量 | 说明 |
|---------|------|------|
| **High** | **4** | SSE 阻塞读挂死 / V2 part id 契约错位 / pendingInputs 并发 / dataSync 6h 时限 |
| **Medium** | **29** | SSE 心跳 V1 不一致×1、竞态×8、Markdown×4、一致性×7、移动端×5、i18n×2、构建×2 |
| **Low** | **68** | 死代码/弃用 API/桩方法、重复代码、样式细节、交互竞态、GC 小分配等（§4.3 按簇分组） |
| 备注/范围外 | 12 | 设计取舍、可忽略级观察（§4.4） |
| **已排查无问题** | **~30 项** | 见 §6，含第一期 P0 修复落地复核、i18n/日志/安全/铁律等 |
| 交叉验证 | 5 路 × 全部条目 | 5 个子代理 112 条候选 → 合并去重后 ~100 条；重复命中（如 V1 心跳被 2 路独立发现）即多源确认 |

### 1.3 Top 8 风险（按影响排序）

| # | 问题 | 级别 | 影响 |
|---|------|------|------|
| 1 | SSE 阻塞读使 40s 心跳检查永不执行（`socketTimeoutMillis=MAX_VALUE` + 行间检查） | High | 半开 TCP 下连接永久挂死，无自愈路径，事件/通知静默中断 |
| 2 | V2 REST part id="" 与 SSE 派生 id 契约错位，`mergePartsList` 双份保留 | High | V2 会话 L3 校验后已完结消息文本双份渲染（建议实测复现） |
| 3 | `pendingInputs` HashMap 多服务器并发读写（注释声称单线程不成立） | High | 多后端同时连接时 synthetic 事件丢失/容器损坏 |
| 4 | dataSync 前台服务 Android 15+ 6h 时限，无 `onTimeout` 覆盖 | High | 长连接服务每 6h 被系统终止，手动连接静默丢失 |
| 5 | V1 SSE 心跳只在 heartbeat 事件刷新（V2 已修） | Medium | V1 服务器活跃流式 40s 假超时断连 → 丢事件窗口 + 全量 REST 恢复 |
| 6 | 全局 sessionId 键 + `currentServerId` 单值（多服务器维度缺失） | Medium | 跨后端 sessionId 冲突 → 事件被误判重复/校验打错服务器/状态串台 |
| 7 | 设置读-改-写竞态 + 草稿恢复竞态 + 通知 mark-before-show | Medium | 快速连切开关丢修改 / 冷启动草稿不回填 / 抑制场景通知静默丢失 |
| 8 | 认证头 147 处逐请求内联 + Auth 插件空置 | Medium | 认证机制演进需改 147 处；新增端点易漏挂头 → 401 |

---

## 2. 审计范围与方法

### 2.1 维度清单（13 类）

| # | 维度 | 关注点 |
|---|------|--------|
| D1 | 移动端/安卓 | 生命周期、配置变更、进程死亡、前台服务、通知、电池/Doze、系统集成 |
| D2 | JVM/GC | 热路径分配、装箱、字符串构建、集合（仅第一轮未覆盖部分） |
| D3 | 网络连接 | 超时、连接池、重试、流关闭、取消传播、错误吞异常 |
| D4 | SSE | 协议健壮性、重连、心跳、last-event-id、背压、V1/V2 一致性 |
| D5 | AI Agent | 会话/任务生命周期、事件分发、状态机、轮询、通知路由 |
| D6 | 样式/组件/编码风格统一 | 组件复用、token 覆盖率、硬编码、重复实现 |
| D7 | Markdown | 渲染一致性（iron laws）、表格、代码块、HTML、链接 |
| D8 | 文件系统 | 主线程 IO、流关闭、临时文件、SAF、路径工具红线 |
| D9 | 一致性 | 同功能多处实现、错误处理不一致、格式/语言处理不一致 |
| D10 | 竞态条件 | 共享可变状态、Flow 多 collect、缓存读写、懒初始化 |
| D11 | 设计模式 | 状态机、Usecase 层、单例边界、模式滥用 |
| D12 | 过度设计 | 无用抽象、死代码、冗余层、参数化过度 |
| D13 | 技术债 | TODO/弃用 API/God Files/未迁移项/未提交 WIP |

### 2.2 分层覆盖与来源

| 层 | 覆盖路径 | 审计来源 |
|----|---------|---------|
| 入口/DI/Service | OpenCodeApp、MainActivity、OpenCodeConnectionService、SseConnectionManager、AppNotificationManager、SessionFocusHolder | 主代理 + 子代理 B |
| Data | V1/V2 ApiClient、SseClient(V1/V2)、parsers×7、Repository+Handler×10、Room/DataStore、security、update、terminal | 主代理 + 子代理 A |
| Domain | FSM、UseCase×26、Repository 接口、Tracker、模型 | 主代理 + 子代理 B |
| UI-Chat | ChatScreen、ChatMessageList、Markdown 系、输入、工具卡×14、终端视图、对话框 | 主代理 + 子代理 C |
| UI-其他 | Sessions/Home/Settings/Server/About/Viewer/WebView/Workspace、导航、主题（123 文件 / 17,117 行） | 主代理 + 子代理 D |
| 跨切面 | 日志/路径/解码/i18n/存储/弃用 API/构建/清单/重复代码 | 主代理 + 子代理 E |

### 2.3 严重级别定义

| 级别 | 定义 |
|------|------|
| Critical | 必然高频触发且后果严重（崩溃/数据损坏/安全）——本期 0 条（内存/性能类已在第一期覆盖） |
| High | 明确缺陷，真实场景可感知/可累积，机制完整可证 |
| Medium | 特定条件下触发，或有明确证据的风险敞口 |
| Low | 轻微/风格/最佳实践/渐进迁移项 |

### 2.4 交叉验证方法（用户要求）

1. **多路独立发现比对**：5 路子代理互不可见、并行审计；同一问题被 ≥2 路独立发现即构成多源确认（如 V1 心跳 = 主代理 + A 路；deprecated 链 = 主代理 + A + E 三路）。
2. **主代理逐条回读**：所有 High/Medium 及多数 Low 均回读 文件:行号 原文核对（本报告证据列即复核结果）；对子代理结论的**纠正/降级**明确标注（如 Q-1/Q-3/Q-4 的"旋转丢状态"因 Manifest `configChanges` 已处理旋转而降级）。
3. **与第一期查重**：凡已在 `audit-2026-08-13-memory-perf/REPORT.md` 的条目只引用编号不重复（见 §6 末清单）。
4. **与已登记债务查重**：`docs/architecture-debt.md` 已登记项（Thin UseCase Option B、God Files、runBlocking 遗留）直接引用不重复。
5. **修复落地复核**：对第一期 P0/P1 建议逐条检查当前代码，确认 `c0c74a4c` 已实现（§6 #1~#5）。

---

## 3. 系统组成与功能链路分析

> 按链路逐条梳理真实数据流（输入→处理→输出）并标注各环节维度风险。所有断言基于主代理实际读码。

### 3.1 系统组成总览

| 子系统 | 组成 | 关键文件 |
|--------|------|---------|
| 连接/服务 | 前台服务 + WakeLock + 多服务器 SSE 连接管理 + 网络恢复 | OpenCodeConnectionService.kt(620)、SseConnectionManager.kt(426)、NetworkMonitor.kt(129) |
| SSE 管线 | V1 全局/实例事件流 + V2 /api/event 标准帧；两套客户端 | SseClient.kt(428)、SseClientV2.kt(391)、parsers/×7、V2EventParser、V2SseMapper |
| REST 管线 | V1/V2 对称 ApiClient（约 2,330 行）+ 域分发 Api + 版本探测 | V1ApiClient.kt(926)、V2ApiClient.kt(1414)、ApiVersionDetector、SessionApi/MessageApi/FileApi/… |
| 事件分发 | 注册表分发 + 所有权去重 + FSM 转发 + 级联清理 | EventDispatcher.kt(491)、StreamingOwnershipRegistry.kt(43)、handler/×10 |
| 会话状态 | 纯函数 FSM + L2 staleness 守护 + L3 REST 校验 + L4 全量同步 | SessionStateService.kt(352)、SessionStateFSM |
| 消息存储 | 内存热视图 + Room 热表 + zstd 归档 + 损坏恢复 | MessageEventHandler.kt(839)、MessageStore.kt(413)、ArchiveBucketDao |
| 聊天 UI | 48ms 批处理 → 高度补偿 → Markdown 渲染（iron laws） | ChatScreen.kt(950)、ChatMessageList.kt(1153)、ChatViewModel.kt(747)+Delegates |
| 终端 | WebSocket PTY + termlib 仿真 + 5 态机 + 重连退避 | ServerTerminalWorkspace.kt(638)、PtyToTermlibAdapter.kt(240)、TerminalTabState.kt |
| 通知 | 5 渠道 + 去重缓存 + 会话焦点抑制 + 提问轮询兜底 | AppNotificationManager.kt(675)、SessionFocusHolder.kt(60) |
| 文件/工作区 | 文件树/搜索/Git/查看器/批注 | WorkspaceScreen、FileTreePanel、SearchOverlay、FileViewerScreen.kt(561)、CodeWebView.kt(313) |
| 安全 | Keystore AES/GCM + 备份排除 + FileProvider 最小暴露 | SecretCipher.kt(73)、backup_rules.xml、file_paths.xml |
| 更新 | GitHub 3 级回退 + 五重校验 + 路径穿越防护 | UpdateRepository.kt(282)、UpdateInstaller.kt(40) |
| 日志 | 有界 Channel → Room 批写 + 崩溃同步 flush | AppLogger.kt(227)、DiagnosticLogRepository |

### 3.2 链路 A：SSE 事件链（连接 → 解析 → 分发 → 状态/消息/通知）

```
OpenCodeConnectionService.connect()
  → SseConnectionManager.startSseConnection（重连循环：preLoadSessions → recoverMessages → 选客户端）
      → V2: SseClientV2.connectToEvents（event:/data:/id: 标准帧；id: 被忽略）
      → V1: SseClient.connectToGlobalEvents（data: 单行 JSON；rawSseEvents 转发 V2 管线）
          → readRawLineBytes（逐字节装箱，H-5 已报）→ 心跳检查在“行间”进行（F-1 风险）
  → collect：tracker.recordSuccess → EventDispatcher.processEvent（ownership 去重 → 注册表分发 → FSM）
  → OpenCodeConnectionService.processEvent → maybeNotify → 通知/抑制
```

**环节风险**：D4 阻塞读挂死（D2-03）；D4/D9 V1 心跳刷新不一致（D2-05）；D4 `id:` 忽略无续传（D2-19）；D5 pendingInputs 并发（D2-02）；D3 请求级超时覆盖正确（正面）；D5 每事件日志已治理（正面）。

### 3.3 链路 B：消息链（SSE 增量 → 热视图 → Room 双写 → UI 渲染）

```
MessagePartDelta/Updated（48ms 窗口，加锁缓冲，不取消运行中定时器）
  → flushPendingDeltas → _messages/_parts StateFlow → persistQueue 单写协程 → MessageStore（事务+归档）
  → ChatViewModel.messageListState（combine 管道）→ ChatMessageList（reverseLayout LazyColumn）
      → turn 分组 → MessageCard* → MarkdownContent（rememberMarkdownState(retainState=true) ✅）
```

**环节风险**：D9 V2 REST/SSE part id 契约错位双份（D2-01）；D10 草稿恢复竞态（D2-06）；D7 跳转预渲染未归一化（D2-07）；D7 点击 indexOf 错位（D2-08）；D2 每事件 FSM 整表拷贝（D2-15）；Markdown 铁律全部合规（正面）。

### 3.4 链路 C：会话状态链（事件 → FSM → L3/L4 REST 校验闭环）

```
SSE 状态事件/客户端动作 → applyTransition（CAS update，RS-010）→ SessionStateFSM 纯函数
  → 副作用：forceComplete → markIdle；isSuspicious → L3（activeValidations 去重）
  → 5s staleness 守护（Busy>15s→L3；Idle+不完整→L3；>24h 孤儿→清理）
  → L4 syncFromRest（跨项目聚合 + 缺失=idle + 不完整保护）
```

**环节风险**：D10 `currentServerId` 单值跨服务器错位（D2-12）；D9 全局 sessionId 键（D2-11）；D2 每转移整表拷贝（D2-15）；CAS/去重/有界历史（正面）。

### 3.5 链路 D：通知链（事件 → 去重 → 焦点抑制 → 通知/轮询兜底）

**环节风险**：D10 任务完成通知 mark-before-show（D2-14）；D5 250ms 固定延迟启发式（D2-L29）；D3/电池 提问轮询 30s 无门控（D2-18）；D10 SessionFocusHolder 分读非原子（N-01）；通知渠道/权限/去重清理（正面）。

### 3.6 链路 E：终端链（WebSocket PTY → termlib → 视图）

**环节风险**：D10 输入 fire-and-forget 乱序（D2-20）；D8/D10 dispose 取消清理协程 → 服务端 PTY 残留（D2-21）；状态机/锁/防抖（正面）。

### 3.7 链路 F：文件/工作区链（浏览 → 读取 → 渲染 → 批注/导出）

**环节风险**：D8 V2 fs.list 绕过 PathUtils（D2-31）；D1 查看器 Overlay VM 生命周期（D2-L22）；D1 对话框状态 remember 批量（D2-L24）；CodeWebView 销毁模式（正面）；导出走 IO+FileProvider（正面）。

### 3.8 链路 G：更新链 / 链路 H：分享/深链链

**环节风险**：更新链五重校验+路径防护（正面）；D8 `.apk.part` 残留（D2-L64）；分享/深链持久化 URI 授权（正面）；D10 分享消费无回执（N-02）。

## 4. 问题清单

> 编号规则：D2- = 本期维度审计条目；来源列标注发现方（主=主代理 / A=data 路 / B=domain+di+service 路 / C=UI-chat 路 / D=UI-其他路 / E=跨切面路）；「复核」列为主代理人工回读结论。

### 4.1 High（4 条）

| ID | 维度 | 位置 | 问题与证据（主代理复核摘录） | 影响 | 建议 | 来源/复核 |
|----|------|------|------|------|------|------|
| D2-01 | D9 一致性·AI Agent | `V2Mappers.kt`:346-348,357-364 + `MessageEventHandler.kt`:412-430 | **V2 REST 路径空 part id（`id=""`）与 SSE 派生 id（`derivePartId` → `msg_ord_N`）契约错位**：`mergePartsList` 的 `preserved = existingParts.filter { it.id !in incomingIds }` 会把 SSE 累积 part 与 REST part（id=""）**同时保留**。代码注释 :416-418 自认「REST text part id="" 与 SSE 派生 id 契约不一致」；:419-420 声称「完成后 REST 全量返回 → preserved 为空」——但 preserved 按 id 过滤，SSE 派生 id 不在 REST 返回集内时不会为空 | V2 会话经 L3 REST 校验（REST_AUTHORITY 合并）后，已完结消息文本可能**双份渲染**；空 id part 无法被后续 part-updated 匹配（:270-285 防御只覆盖该路径） | ① V2Mappers text/reasoning 统一用 `derivePartId`（与 V2SseMapper 同规则）；② 或 mergePartsList 对空 id part 内容匹配合并；③ 模拟器实测复现 | A/✅ 回读确认（建议实测） |
| D2-02 | D10 竞态 | `SseClientV2.kt`:77,296,300 | **`pendingInputs` 共享单例 `HashMap`，注释声称「同一 flow 单线程」在多服务器下不成立**：每服务器一个 flow 在 `SseConnectionManager.scope`（IO）并发 collect → admitted/promoted 并发 `put/remove` → 丢条目/容器损坏；与第一期 M-1（无界）叠加 | synthetic 实时通知多服务器场景偶发丢失 | `ConcurrentHashMap` 或按 serverId 隔离；重连 clear() | A/✅ 回读确认 |
| D2-03 | D3/D4 网络·SSE | `SseClient.kt`:201-207 + `SseClientV2.kt`:118-125（两客户端 `socketTimeoutMillis = Long.MAX_VALUE`） | **40s 心跳超时只在「读到一行之后」检查，而读操作在静默 socket 上无限阻塞**：kill -9/NAT 静默断且网络状态未变时 `readByte()` 永久挂起 → 心跳检查永不执行 → 重连/冷却全部失效，NetworkMonitor 仅在网络状态变化时重连 → **无自愈路径** | 连接状态失真、事件/通知静默中断 | 读循环套 `withTimeoutOrNull(HEARTBEAT_TIMEOUT_MS)`；或 OkHttp readTimeout + 心跳探测 | B/✅ 回读确认（建议真机实测） |
| D2-04 | D1 移动端（Android 15+） | `AndroidManifest.xml`:57（`foregroundServiceType="dataSync"`）+ targetSdk=36 + 全工程 0 处 `onTimeout` | **dataSync 前台服务 Android 15+ 有 6 小时时限**，本服务设计为无限期运行：超时后系统 `onTimeout` → 停止服务 → `stopAllConnections()` 断开全部 SSE；START_STICKY 重启仅恢复 autoConnect 服务器，**手动连接静默丢失** | 每 6h 用户无感知失去实时性 | 覆盖 `onTimeout(int,int)`；评估 FGS 类型或接受周期自恢复；纳入可观测性 | B/✅ grep 确认（建议真机验证） |

### 4.2 Medium（29 条）

| ID | 维度 | 位置 | 问题 | 影响 | 建议 | 来源/复核 |
|----|------|------|------|------|------|------|
| D2-05 | D4/D9 SSE | `SseClient.kt`:202,219-220 vs `SseClientV2.kt`:128,137 | **V1 心跳只在 `ServerHeartbeat` 事件刷新，V2 任意事件刷新**（V2 注释自述「V2 活跃流式不发 heartbeat，只在 heartbeat 重置会 40s 假超时断连」）；V1 仍用于 V1 服务器（`SseConnectionManager.kt`:267） | V1 服务器长流式周期性假断连 → 丢事件窗口 + 全量 REST 恢复 | V1 心跳对齐 V2（任意事件/空帧刷新）；先加日志观测 | 主+A 双路/✅ |
| D2-06 | D10 竞态（草稿） | `ChatScreen.kt`:292-298 + `ChatViewModel.kt`:462-473 | **异步草稿恢复与首组合时序竞态**：`draftTextInitialized` 在 DataStore 读完成前置 true → 恢复完成不回填 → 冷启动草稿**视觉丢失**（数据仍在，继续输入即覆盖） | 用户草稿丢失 | `LaunchedEffect(draftText)` 恢复完成且未输入时初始化 | C/✅ |
| D2-07 | D7 Markdown | `MessageCardUser.kt`:136 vs `ChatMessageList.kt`:442 | **跳转预渲染 fallback 用未归一化原始文本**（`rememberMarkdownState(part.text, …)`），preParse 路径用 `normalizeForRender`——\r\n/GFM 表格/任务标记归一化缺失 | 跳转目标首帧排版突变 + 多余解析 | jumpMdState 前先 normalizeForRender | C/✅ |
| D2-08 | D7 Markdown | `ClickableMarkdown.kt`:95,135 | **可点击项 `text.indexOf(item.text)` 定位首次出现**：重复文本段落中下划线/点击命中错位 | 链接点不到、非链接变可点击 | AST offset 或 span range 映射 | C/✅ |
| D2-09 | D9 一致性 | `RetryBanner.kt`:49 + strings.xml:60 | 重试横幅 attempt 同时传 %1$d/%2$d 恒显示 "N/N"；`SessionStatus.Retry` 无 maxAttempts | 进度语义错误 | 单占位符或传真实 maxAttempts | C/✅ |
| D2-10 | D6 i18n | `CompactionBanner.kt`:79 | 硬编码英文 `"Compressing context: …"` 绕过 15 语言资源 | 非英语用户见英文 | 提取资源补齐翻译 | C/✅ |
| D2-11 | D9/D5 一致性 | `StreamingOwnershipRegistry.kt`:21-27 + 各 handler | **全部状态容器以 sessionId 为唯一键、无 serverId 维度**（AppNotificationManager:606 自认跨服务器可能重复） | 双后端 sessionId 冲突 → 事件误判重复/状态串台 | (serverId, sessionId) 复合键 | B/✅ |
| D2-12 | D10 竞态 | `SessionStateService.kt`:53,266-267 | **`currentServerId` 单全局值**，双服务器并发时被后连接者覆盖 → L3 校验打到错误服务器 → 误判 Idle → forceComplete 提前完结流式 | 双服务器状态误判 | 校验按调用方 serverId 携带上下文 | B/✅ |
| D2-13 | D9 一致性 | `SseConnectionManager.kt`:212-214 | **`isConnected()` = `sseJob.isActive`（重连循环活跃）而非真实连接**；两套语义并存 | 退避期间轮询不退出，继续打 REST | 返回 `isConnected == true`；区分 isConnecting | B/✅（原主代理 N-05，升 Medium） |
| D2-14 | D10 竞态（通知） | `OpenCodeConnectionService.kt`:485,494 | 任务完成通知**先标记去重、后查抑制**（与 permission/question 顺序相反）→ 抑制场景通知不显示且去重键已消费，永不重发 | 通知偶发静默丢失 | 先预检抑制再标记 | B/✅ |
| D2-15 | D2 JVM/GC | `SessionStateService.kt`:184-190,212-216 | 每 SSE 事件（含每 token）对 `_fsmStates`/`_histories` **整张 Map 拷贝** + statusFlow/activityFlow 各一次 mapValues 全量派生 | 流式 ~20 事件/s × N 会话 = GC 压力叠加 | toMutableMap 单次拷贝；history 定长；mapValues distinctUntilChanged | B/✅ |
| D2-16 | D1 移动端 | 全工程 0 处 `onTrimMemory`/`onLowMemory` | 进程级热缓存（消息热视图/ToolSnapshotCache/注册表/通知去重）无低内存回调 | 内存压力无法释放可重建缓存，增大 LMK | OpenCodeApp 实现 onTrimMemory 分级清理 | B/✅ grep |
| D2-17 | D1 移动端 | `OpenCodeApp.kt`:117-129 + Service:355-364 | 崩溃处理器无条件重启 MainActivity（确定性崩溃死循环，07:26 先例只修提示）；进程死亡后手动连接不恢复 | 崩溃死循环 / 连接静默丢失 | 重启退避（10min 内最多 1 次）；记录 lastConnected 恢复 | B/✅ |
| D2-18 | D3/D1 网络·电池 | Service:394-429 | 每服务器每 30s 无条件轮询 `/question`，mergeQuestionsFromREST 不受 notificationsEnabled 门控 | 通知关闭时仍 2 次唤醒/分钟/服务器 | 关闭时退避 60-120s；或仅前台轮询 | B/✅ |
| D2-19 | D4 SSE | `SseClientV2.kt`:182-184 + SseConnectionManager:371 | `id:` 帧行被忽略，无 Last-Event-ID 续传；恢复拉最新一页默认 limit | 断连窗口事件可能永久缺失 | Last-Event-ID 续传或游标循环补漏 | A/✅ |
| D2-20 | D10 竞态（终端） | `ServerTerminalWorkspace.kt`:229-238 + `PtyToTermlibAdapter.kt`:106-128 | 终端输入每次 `scope.launch { socket.send } ` fire-and-forget，IO 池并发执行顺序无保证 | 快速键入/粘贴字节乱序 | 单发送 actor/Mutex 串行 | A/✅ |
| D2-21 | D8/D10 | `ServerTerminalWorkspace.kt`:417-419 | `dispose()` 先 closeAll（内部在**同一 scope** launch 清理）再 `scope.cancel()` → 清理协程被立即取消 | 服务端 PTY/shell 残留、半开连接 | 独立 scope await 清理完成再 cancel | A/✅ |
| D2-22 | D9 一致性 | `V2ApiClient.kt`:113-116 + `V2Mappers.kt`:124-127（V1 无） | `rejectHtmlResponse` 两处复制（日志不一致）；V1ApiClient 无 HTML 防御 | 修复不一致；V1 误判场景难定位异常 | 提公共实现；V1 同步接入 | A/✅ |
| D2-23 | D9 一致性（时间） | `V2SseMapper.kt`:125,151 | SSE 路径把 ordinal 当 epoch 毫秒塞进 `time.start`（REST 用真实 time.created） | 流式耗时/位置计算荒谬值 | 取服务器 time 字段或 start=null | A/✅ |
| D2-24 | D10 竞态 | `McpRepositoryImpl.kt`:17-25 | `@Volatile connection` 跨服务器共享无切换清理 → 残留请求打到新服务器 | MCP 查询静默用错凭据 | 按 serverId 缓存或显式传 conn | A/✅ |
| D2-25 | D5 AI Agent（功能失效） | `PermissionAutoApprover.kt`:38-42 无调用方 | 「自动批准」规则只持久化/展示**从未被求值** | 用户配置永不生效，全部权限手动 | 接入 PermissionAsked 路径或删除入口 | A/✅ grep |
| D2-26 | D10 竞态（设置） | `SettingsViewModel.kt`:163-167 | `updateSetting` 读 `settings.value` 旧快照再写 → 快速连切两个开关丢前一次修改 | 设置丢失 | Mutex 串行 + 写前取最新 | D/✅ |
| D2-27 | D9/D3 一致性·网络 | V1/V2 ApiClient 全文件 grep **147 处** | Authorization 头逐请求内联，无统一拦截器；Auth 插件 install 但从未配置 provider | 认证演进需改 147+ 处；新端点易漏挂头 401 | Auth provider 或 `auth(conn)` 扩展 | E+A/✅ |
| D2-28 | D1/D13 安全 | `AndroidManifest.xml`:35 | `usesCleartextTraffic="true"` 全局放行明文，无 networkSecurityConfig 白名单 | 凭据可被嗅探 | networkSecurityConfig 限定网段 | E+主/✅ |
| D2-29 | D13 构建 | `proguard-rules.pro`:24-25,34-35,38,50 | R8 keep-all 过宽（ktor/coroutines/mikepenz/intellij/snipme 整库保留） | APK 膨胀数 MB；与 AGENTS 声明不符 | 逐个收窄，按缺类报告补 keep | E/✅ |
| D2-30 | D9 一致性（WebView） | 6 处：WebViewScreen/CodeWebView/RenderWebView/PdfViewer/ErrorPayloadContent/WebViewWarmer | WebView 初始化样板重复、销毁策略不统一（曾致 2 处泄漏，已修）；JS 桥 remove 仅一处 | 新增第 7 处易再漏 | 抽共享 WebView 工厂 | E/✅ |
| D2-31 | D8 文件系统（红线） | `V2ApiClient.kt`:1157-1166 | V2 fs.list name/absolute 推导仅处理 `/`（`substringAfterLast('/')`），Windows 路径必错；违反「始终用 PathUtils」 | Windows 服务器目录树错乱 | PathUtils.fileName/joinPath | E+A 双路/✅ |
| D2-32 | D6 i18n | `SessionRow.kt`:367-368 | 用户可见英文硬编码 `DetailRow("Diff", "+N -M (K files)")` | 仅英文；资源已有可复用项 | stringResource + plurals | E/✅ |
| D2-33 | D9 一致性（错误契约） | `domain/usecase/*`（26 文件）+ `domain/model/ApiResult.kt:27` | **异常传播三套并存**：`SendMessageUseCase.kt:32` getOrThrow() 抛异常 / `DeleteSessionUseCase`、`UpdateSettingsUseCase` 返回 Result / `ListSessionsUseCase` 裸 List 抛异常；`ApiError : Exception()` 与 kotlin.Result 双重语义并存（薄层本身为 Option B 已决策，此处仅报契约不一致面） | 调用方必须同时兼容两种失败形态；新代码难以选择模式 | 统一失败约定（全 getOrThrow 或全 Result）；ApiResult 与 kotlin.Result 二选一 | B/✅ 回读确认 |

---
### 4.3 Low（按簇分组，68 条）

#### 4.3.1 簇 A：死代码 / 弃用 API / 桩方法（10 条）

| ID | 位置 | 问题 | 建议 | 来源 |
|----|------|------|------|------|
| D2-L1 | EventDispatcher.kt:382-399 / ChatRepositoryImpl.kt:460-471 / MessageEventHandler.kt:720-738 / ChatRepository.kt:263-277 | @Deprecated `setMessages/mergeMessages/replaceMessages` 三层委托链无业务调用方 | 删除，收敛 `upsertMessages(MergeStrategy)` | 主+A+E 三路 |
| D2-L2 | SessionRepositoryImpl.kt:113-118 / AgentRepositoryImpl.kt:23-25 / ChatRepositoryImpl.kt:178-190 | 桩方法：`switchSession` 恒 Unit、`switchAgent` 恒抛 UnsupportedOperationException、`sendMessage` 返回 id="" 占位、`replyQuestion` 无调用方 | 删除或实现真实语义；保留需登记 | A+B |
| D2-L3 | ChatRepository.kt:76-86 + ChatRepositoryImpl.kt:152-171 | `getActiveToolProgress/getStepProgress/getCompactionState(serverId)` 无调用方，且实现按 sessionId 键读、用 serverId 查——一旦接入即错 | 删除，仅保留 ForSession 变体 | A |
| D2-L4 | SseClient.kt:256-333 | `connectToInstanceEvents` 无调用方（与 connectToGlobalEvents 约 170 行重复） | 删除或抽公共读循环 | 主+A |
| D2-L5 | ui/components/AppLoadingEdge.kt:35 | 全工程无调用方的死组件（含常驻动画） | 删除或接入使用点 | C |
| D2-L6 | FileViewerScreen.kt:471-482,170-194,196-221 | `TruncationBanner` 从未调用；isExtremelyLarge 分支与正常分支 CodeWebView 调用复制粘贴 | 删除死组件；合并单一调用点 | D |
| D2-L7 | NavGraph.kt:70,167,206,315-336 + WebViewNav | `useNativeUi = true` → WebView 旧分支/路由/webViewNavigateFlow 全部不可达（约 15KB 死代码）；**C-1 修复位于不可达屏幕，实际影响已降级** | 删除 WebViewScreen/WebViewNav/旧分支；回写第一期报告 | D/✅ grep |
| D2-L8 | NavGraph.kt:45 / ChatMessageList.kt:15-16 / ChatViewModel.kt:746 / DraftInputDelegate.kt:233-235 | 死导入（URLDecoder）、重复 import、空 companion/注释残留 | 清理 | 主+C |
| D2-L9 | V2ApiClient.kt:893-895 | `deleteMessagePart` 直接返回 false（「V2 无此端点」），无法区分无端点与失败 | 可区分异常或 UI 隐藏入口 | A |
| D2-L10 | ServerDataStore.kt:53-55 | `getAllServers()` 纯别名无增值 | 删除别名 | A |

#### 4.3.2 簇 B：重复代码 / 映射分叉（12 条）

| ID | 位置 | 问题 | 建议 | 来源 |
|----|------|------|------|------|
| D2-L11 | V1ApiClient.kt:311-360 vs V2ApiClient.kt:828-874 | `exportSessionToStream` 整方法逐行复制（仅 URL 前缀不同；L-4 新建 client 已报） | 抽公共方法；顺带修 L-4 | A |
| D2-L12 | V2SseMapper.kt:297-298 | `partLocator` ordinal 提取表达式逐字重复两次，第二分支恒死 | 改真实候选键或删除 | A |
| D2-L13 | SessionEventParser.kt:121-146 vs V2Mappers.kt:149-180 | V2 扁平会话 JSON → Session 映射两份独立实现（SSE 用客户端 now、REST 用服务器时间） | 收敛单一 mapper | A |
| D2-L14 | MiscEventParser.kt:9 / SessionEventParser.kt:14 / MessageEventParser.kt:13 / PtyEventParser.kt:13 / QuestionEventParser.kt:13 | 5 个解析器 TAG 全部复制为 `"SseClient"` | 每类用自身类名 | A/✅ |
| D2-L15 | 9 文件 14 处（DebugLogger:34 / OpenCodeApp:81,87,148 / SessionRow:88,308 / MessageBubble:87 / TaskSheet:278,347 / QuickNavigateSheet:217 / SyntheticNotificationCard:148 / ContextDetailDialog:74 / ShareTargetPickerDialog:44 / DiagnosticsScreen:304） | SimpleDateFormat 14 处、8 种格式、Locale 混用 | 抽统一 DateFormatters | 主+E |
| D2-L16 | 9 处（CopyButton:52 / ChatScreen:605 / ChatScreenBottomBar:315 / ChatMessageList:996 / ToolCardScaffold:185 / ServerProvidersScreen:312 / FileViewerOverlay:88 / SessionListViewModel:694 / DiagnosticsScreen:189） | 剪贴板写入手写重复 | 抽 `copyToClipboard` 工具 | E |
| D2-L17 | SseClient.kt:171 + SseClientV2.kt:92 | directoryHeader 共享扩展（ApiClient.kt:30-32）不复用，2 处内联重复 | 改用共享扩展 | E |
| D2-L18 | Theme.kt:102-113 vs 70-84 | 动态取色 AMOLED 分支复制粘贴 8 个 surface 色值 | 提取 `AmoledSurfaceOverrides` | D |
| D2-L19 | FileType.kt:17-33 / CodeWebView.kt:21-30 / HighlightBuilder.kt:47-63 | 三处「扩展名→语言」映射表分叉（tsx/mjs/py/go 覆盖各异） | 收敛单一映射 | D |
| D2-L20 | MainActivity.kt:106-120 vs OpenCodeConnectionService.kt:57-68 | attachBaseContext 语言应用逻辑两处完全重复 | 抽 `applyAppLanguage(context)` | B |
| D2-L21 | SessionListViewModel.kt:543-628 vs 630-679 | loadSessions 与 refreshSessions 近 40 行重复 | 提取 `fetchAllSessions()` | D |
| D2-L22 | MessageCardAssistant.kt:89-90（WIP） | `textColor = if (isAmoled) A else A` 死条件（两分支相同） | 化简；确认 isAmoled 用途 | 主+C/✅ |

#### 4.3.3 簇 C：移动端 / 生命周期（9 条）

| ID | 位置 | 问题 | 建议 | 来源/复核 |
|----|------|------|------|------|
| D2-L23 | FileViewerOverlay.kt:35-43 + AnnotationManager.kt:50-56 | Overlay 的 ViewModelStoreOwner 用 `remember` 创建；**旋转由 Manifest configChanges 自处理不重建**，但语言切换/进程死亡等 recreate 场景 VM 重建 → 批注/滚动/分页丢失；`AnnotationManager.restore()` 无调用方（死代码） | VM 经 SavedStateHandle 恢复；接线 restore() | D/⚠️ 触发条件已修正（原「旋转即丢」不成立） |
| D2-L24 | HomeScreen.kt:73-87 | `pendingConnectServerId` 用 remember——旋转不丢（configChanges），但 recreate 场景权限回调后连接静默中断 | 改 rememberSaveable（UpdateInstallLauncher:29 正例） | D/⚠️ 触发条件修正 |
| D2-L25 | SessionListScreen:81-93 / SettingsScreen:75-83 / DiagnosticsScreen:103-108 / OpenProjectDialog / ServerProvidersScreen / SessionRow / ServerModelFilterScreen 等 20+ 处 | 对话框可见性/输入状态系统性用 `remember` 而非 `rememberSaveable`（recreate 场景丢失） | 统一 rememberSaveable（正例：UpdateInstallLauncher/DiffView/FileViewerScreen） | D+主/⚠️ 触发条件修正 |
| D2-L26 | OpenCodeConnectionService.kt:585-592 | `newWakeLock(PARTIAL).acquire()` 无超时兜底；释放依赖正常路径 | `acquire(timeout)` + 周期续期；release 收敛 | B |
| D2-L27 | OpenCodeApp.kt:81-82,148 | 崩溃日志文件名秒级分辨率，同一秒两次崩溃互相覆盖 | 文件名加纳秒/递增序号 | E |
| D2-L28 | AndroidManifest.xml:17 + SecretCipher.kt:24-36 | allowBackup 恢复 DataStore 密文但 AndroidKeyStore 密钥不随备份迁移 → 恢复后解密失败（有 runCatching 兜底） | dataExtractionRules 排除凭据文件；恢复失败给 UI 提示 | B |
| D2-L29 | ServerProvidersScreen.kt:235 | API key 输入框无 `PasswordVisualTransformation`（明文；对比 ServerDialog:174 有遮蔽） | 补遮蔽 + KeyboardType.Password | D |
| D2-L30 | OpenCodeConnectionService.kt:483 | SessionIdle 通知依赖固定 250ms 延迟等 reducer，弱网/大批量时不足 → 判空丢失 | 事件驱动或多次轮询；加日志观测 | B |
| D2-L31 | FileViewerViewModel.kt:210 | `nextHunk` 空 hunks 时 `coerceAtMost(-1)` → 索引 -1 | `if (hunks.isEmpty()) it else …` | D |

#### 4.3.4 簇 D：交互竞态 / 网络细节（10 条）

| ID | 位置 | 问题 | 建议 | 来源 |
|----|------|------|------|------|
| D2-L32 | NavGraph.kt:395-401 | `onNavigateToChildSession` 无 launchSingleTop：双击子会话重复入栈；与 onOpenWorkspace/onOpenDirectory 不一致 | `navigate(route) { launchSingleTop = true }` | D |
| D2-L33 | WorkspaceViewModel.kt:55,122-131,153-159 | prefetchGitCount 与切 GIT_CHANGES 面板双发完整 VCS status（无 in-flight 去重） | prefetch 置 gitLoading 或合并 | D |
| D2-L34 | OpenProjectDialog.kt:327-340 | 创建文件夹按钮未随 isCreatingFolder 禁用 → 双击双发 createDirectory；currentPath=null→还原 hack | 按钮 enabled 绑定；refreshKey | D |
| D2-L35 | SessionListScreen.kt:108 | `consumePendingReadSessionId()` 组合体直接调用（内部 DataStore 写副作用） | SideEffect/LaunchedEffect 权衡 | D |
| D2-L36 | ServerSettingsViewModel.kt:103-127 | init 四路并行加载各自 rebuildUi → loading 闪烁、无去重 | 单一加载协程 async/awaitAll | D |
| D2-L37 | HomeViewModel.kt:181-200 | connectToServer guard 读 StateFlow，同帧双击双双通过 → 双 testConnection + 双 startForegroundService（服务侧有去重） | 以 connectJobs 存在性作同步 guard | D |
| D2-L38 | DirectoryManager.kt:88-95 | getServerPaths **失败也缓存空 ServerPaths()**，无 TTL → 一次瞬时失败毒化整个 VM 生命周期 | 仅成功缓存 | D |
| D2-L39 | TokenStatsTracker.kt:24-26 | `update()` 读-改-写非原子（当前调用方单线程，双 VM 并存理论丢写） | `.update {}` CAS 统一 | B |
| D2-L40 | SseConnectionManager.kt:116 vs 197 | startConnection 裸 cancel() vs reconnectServer cancelAndJoin() 不一致 | 统一 cancelAndJoin 或 collect 内校验 job 身份 | B |
| D2-L41 | NetworkMonitor.kt:91-99 | onCapabilitiesChanged 只置 Available，失去 VALIDATED（captive portal）时状态卡旧值 | 补非 validated → Unavailable 分支 | 主/✅ |

#### 4.3.5 簇 E：JVM/GC 小分配 / 正则（6 条）

| ID | 位置 | 问题 | 建议 | 来源 |
|----|------|------|------|------|
| D2-L42 | AppLogger.kt:197-200 | `shouldPersist` 每次日志调用现场构造 4 项 mapOf（DEBUG 流式 50-90 条/s → 每秒数百次分配） | priorities 提 companion 常量 | B |
| D2-L43 | BashToolCard.kt:63 | 每次重组现场编译 ANSI 正则并全量扫描（新位置，L-7 同模式） | 预编译常量 + remember | C |
| D2-L44 | MarkdownContent.kt:110,125 | normalizeMarkdown 每次内容变化现场编译 2 个 Regex（流式每 token） | 提顶层预编译常量 | C |
| D2-L45 | ReasoningBlock.kt:85-94,133-138 | `rememberInfiniteTransition` 恒运行：已完成/折叠思考卡片仍 60fps 动画帧（与 L-10 ticker 不同根因） | isComplete 时跳过 | C |
| D2-L46 | MarkdownTable.kt:205,241,246 | 每次 measure 对全部单元格 3 遍 subcompose（probe/pass1/final） | 列宽结果缓存 | C |
| D2-L47 | ChatErrorState.kt:36-45 | 错误态 5s 无退避自动重试（服务器不可达时无限请求） | 指数退避或仅首次自动 | C |

#### 4.3.6 簇 F：样式/一致性/i18n 细节（8 条）

| ID | 位置 | 问题 | 建议 | 来源 |
|----|------|------|------|------|
| D2-L48 | SpacingTokens 33 文件 / ShapeTokens 53 / AlphaTokens 95 | 令牌覆盖不均：sessions/server 界面大量裸 dp/形状 | 令牌替换；ui-conventions 增检查项 | E |
| D2-L49 | FileTreePanel.kt:151 / PdfViewer.kt:190 | 硬编码 alpha 0.4f/0.9f 绕过 AlphaTokens | 补 AlphaTokens 档位 | D |
| D2-L50 | ToolCardScaffold.kt:187 vs 消息卡片 | 复制反馈 Toast vs Snackbar 通道不统一 | 统一反馈通道 | C |
| D2-L51 | MarkdownPreviewDialog.kt:88 | 硬编码启用触觉反馈，无视用户设置 | 读 `LocalHapticFeedbackEnabled` | C |
| D2-L52 | ChatTerminalView.kt:82,96,124,342,363 | `snackbarHostState` 参数被函数内 remember 遮蔽（ChatScreen:661 传入 host 失效）——死参数 | 删除参数或改用参数（推荐后者） | 主/✅ |
| D2-L53 | PermissionEventHandler.kt:46,59 | 「Permission auto-approved/auto-denied」文案与真实语义不符且 release INFO/WARN | 文案改为 asked/replied；降 DEBUG | A/✅ |
| D2-L54 | SessionEventHandler.kt:106-109 | `_sessions.update{}` lambda 内执行副作用 `locallyClearedReverts.remove(...)`（CAS 重试重复执行，当前幂等） | 集合操作移出 update lambda | A/✅ |
| D2-L55 | ChatMessageList.kt:119-121 | 服务器固定模板字符串硬编码匹配（转后台提示）——服务器改文案即静默失效 | 用事件类型字段识别；模板集中配置 | E |

#### 4.3.7 簇 G：存储/文件系统/构建（9 条）

| ID | 位置 | 问题 | 建议 | 来源 |
|----|------|------|------|------|
| D2-L56 | SettingsDataStore.kt:139-143 + OpenCodeApp.kt:141 | SharedPreferences 与 DataStore 双写镜像（locale 需 attachBaseContext 同步读，设计合理）但无校验：两写间崩溃 → 语言漂移 | 启动校验回填；注释说明 | E+D/✅ |
| D2-L57 | SettingsRepositoryImpl.kt:74-95 | updateSettings 对 21 个设置顺序执行 21 次独立 DataStore edit（中途失败半套落盘） | SettingsDataStore 加单一 `updateAll` | A/✅ |
| D2-L58 | UpdateRepository.kt:204-237 | `.apk.part` 临时文件仅在取消/异常时删除——下载中进程被杀残留 | check/restore 前清理 | A/✅ |
| D2-L59 | SettingsDataStore.kt:497-517 | `favoriteSessionIds` 在 `dataStore.data.map{}` 读 flow 内执行 `dataStore.edit{}` 写（隐蔽副作用迁移） | 迁移移独立 suspend 函数 | A/✅ |
| D2-L60 | FileRepositoryImpl.kt:24-51 | listDirectory 有 withContext(IO)，其余 6 方法裸调用（DataStore 读可能落主线程） | 全部统一 withContext(IO) | A/✅ |
| D2-L61 | MessageStore.kt:46,102-104 | upsertMessages 用 runCatching.onFailure 仅日志吞掉一切异常（含约束冲突/磁盘满），调用方不知落盘失败（叠加 N-1） | 约束冲突上抛；IO 瞬态降级日志 | A/✅ |
| D2-L62 | MessageEventHandler.kt:235-238 | persistSseUpdate 分两次读 `_messages`/`_parts` 非原子快照（偶发「新消息+旧 parts」落盘，缓存可自愈） | synchronized 快照或 `_parts.update` 内组装 | A/✅ |
| D2-L63 | OpenCodeApp.kt:143-150 | Application.onCreate 主线程同步 listFiles + 解析崩溃文件名 | 移入 appScope.launch(IO) | E |
| D2-L64 | build.gradle.kts:113（isReturnDefaultValues）+ 根 build:4（Kotlin 2.3.21 vs force kotlin-metadata-jvm 2.4.0）+ Manifest:26（largeHeap） | 构建/测试债：mock 默认值掩盖 stub 遗漏（AGENTS 自述）；Kotlin 版本倾斜 workaround；largeHeap 掩盖内存问题（泄漏治理后评估移除） | 按各条治理；纳入升级清单 | 主+E+B/✅ |

#### 4.3.8 簇 H：UI 流式/渲染残余（4 条）

| ID | 位置 | 问题 | 建议 | 来源 |
|----|------|------|------|------|
| D2-L65 | ChatScreen.kt:754-766 vs 507-519 | `onViewToolLambda` 重复定义，内层完全未使用 | 删除内层重复定义 | C |
| D2-L66 | DraftInputDelegate.kt:164-170 vs 187-191 | clearDraft 不走 persistMutex，与进行中 saveDraft 并发 → 空草稿可能被旧文本复活 | clearDraft 走同一写通道 | C |
| D2-L67 | QuestionCard.kt:119-129 | 已选答案非 rememberSaveable（recreate 后丢失需重答） | rememberSaveable + Saver | C |
| D2-L68 | ImagePreviewDialog.kt:65-75 | H-3 降采样已修复，但仍在主线程 Base64 解码全量 data URL（大图瞬时大分配） | 解码移 Default/IO 或 Coil AsyncImage | C |

### 4.4 备注（N2 系列，设计取舍/可忽略）

| ID | 位置 | 备注 |
|----|------|------|
| N2-01 | SessionFocusHolder.kt:55-58 | shouldSuppress 分读两个 StateFlow（非原子）——窗口微秒级，影响仅通知多弹/少弹一次 |
| N2-02 | NavGraph.kt:91-123 | sharedImagesFlow 消费无回执（连发两次分享第二次可能覆盖）——窗口极小 |
| N2-03 | WebViewWarmer.kt:75 | 主线程读 asset（一次性小文件，可忽略；HTML 膨胀需迁移 IO） |
| N2-04 | SessionStateService.kt:97-107 | statusFlow/activityFlow 每转移全量 mapValues O(会话数)——<100 会话无感 |
| N2-05 | SseClient.kt:156 | rawSseEvents 单缓冲区 64 DROP_OLDEST：V2 管线消费慢时丢帧（已声明设计取舍） |
| N2-06 | OpenCodeConnectionService | 连接期 partial WakeLock 为 SSE 保活的产品取舍（设置提供开关）；提问轮询 30s 为兜底设计（D2-18 已报优化） |
| N2-07 | 全分区 | 令牌覆盖整体良好（平均 ~3 dp/文件），dp/sp 统计属良性；仅个别文件偏离 4dp 网格 |
| N2-08 | ServerTerminalWorkspace | 服务器永久失联时每 30s 持续重连尝试（依赖用户断开终止）——与 D2-18 同类网络唤醒 |
| N2-09 | UpdateRepository.isNewer | dev flavor 时间戳 versionCode（17.8 亿）永远判「无更新」——预期行为 |
| N2-10 | 工作区状态 | 审计基线含 31 个未提交 WIP（2026-08-14 提问卡片重构）——D2-L22 出自该批；交付前需提交 |
| N2-11 | F-13 全景 | 进程级协程作用域五处自立（EventDispatcher:58/SseConnectionManager:66/OpenCodeConnectionService:101/AppLogger:37/ServerTerminalWorkspace:66），N-15 双 scope 主题的全景补充 |
| N2-12 | Asking 状态（WIP） | 未应答问题会话保持 Asking 不在 stale/清扫覆盖内——依赖服务器超时，Low |

## 5. 交叉验证矩阵（用户要求：文档生成后验证问题真实存在）

> 验证口径：① 来源路数（5 路子代理互不可见，≥2 路独立发现 = 多源确认）；② 主代理人工回读（✅=已回读代码核实行号与语义；⚠️=机制成立但依赖运行时条件，建议实测）；③ 触发条件修正（对子代理结论的纠正，如 Q 系列旋转场景）；④ 与第一期/债务文档查重。

| ID | 来源路数 | 主代理复核 | 验证要点 |
|----|---------|----------|---------|
| D2-01 | A 单路 | ✅ | 回读 V2Mappers.kt:346-348（id=""）+ MessageEventHandler.kt:412-430（preserved 逻辑），代码注释 :416-418 自认契约不一致；建议模拟器实测双份渲染 |
| D2-02 | A 单路 | ✅ | 回读 SseClientV2.kt:77（HashMap）+ :296/:300（put/remove）；SseConnectionManager 单 scope 多 flow 并发成立 |
| D2-03 | B 单路 | ✅ | 回读两客户端读循环（行间心跳检查 + socketTimeout=MAX_VALUE）；半开 TCP 挂死机制完整 |
| D2-04 | B 单路 | ✅ | grep 全工程 0 处 onTimeout；Manifest:57 dataSync + targetSdk 36；Android 15+ 6h 时限为系统行为 |
| D2-05 | 主 + A 双路 | ✅ | 回读 SseClient.kt:202,219-220 vs SseClientV2.kt:128,137（V2 注释自述该 bug 模式）；两路独立命中即多源确认 |
| D2-06 | C 单路 | ✅ | 回读 ChatScreen.kt:292-298（flag 逻辑）+ ChatViewModel.kt:464（异步 restore）；竞态时序成立 |
| D2-07 | C 单路 | ✅ | grep normalizeForRender 三点（ChatMessageList:442/:614、MarkdownContent:158）vs MessageCardUser:136 raw 文本——路径不一致确认 |
| D2-08 | C 单路 | ✅ | 回读 ClickableMarkdown.kt:95/:135 indexOf 定位；重复文本错位机制成立 |
| D2-09 | C 单路 | ✅ | 回读 RetryBanner.kt:49（双占位符同值） |
| D2-10 | C 单路 | ✅ | 回读 CompactionBanner.kt:79（硬编码英文） |
| D2-11 | B 单路 | ✅ | 回读 StreamingOwnershipRegistry.kt:21-27（单键 Map）；AppNotificationManager:606 自认跨服务器重复 |
| D2-12 | B 单路 | ✅ | 回读 SessionStateService.kt:53（@Volatile 单值）+ :266-267（校验取全局值）；双服务器覆盖成立 |
| D2-13 | B + 主 | ✅ | 主代理独立发现同一语义问题（原 N-05），B 独立定位轮询消费方影响——双源确认并升级 |
| D2-14 | B 单路 | ✅ | 回读 OpenCodeConnectionService.kt:485（check→mark）→:494（show）；与 permission 路径顺序对比成立 |
| D2-15 | B 单路 | ✅ | 回读 SessionStateService.kt:184-190（整表复制）+ :97-107（mapValues 派生） |
| D2-16 | B 单路 | ✅ | grep 全工程 0 处 onTrimMemory/onLowMemory |
| D2-17 | B 单路 | ✅ | 回读 OpenCodeApp.kt:117-129（无条件重启）+ Service:355-364（仅 autoConnect）；07:26 崩溃循环注释为佐证 |
| D2-18 | B 单路 | ✅ | 回读 Service:394-429（轮询循环 + 门控仅包 notify 段） |
| D2-19 | A 单路 | ✅ | 回读 SseClientV2.kt:182-184（id: 忽略）+ SseConnectionManager.kt:371（无游标 listMessages） |
| D2-20 | A 单路 | ✅ | 回读 ServerTerminalWorkspace.kt:229-238（scope.launch send） |
| D2-21 | A 单路 | ✅ | 回读 :417-419（closeAll→scope.cancel）+ :396-407（清理协程同一 scope） |
| D2-22 | A 单路 | ✅ | grep rejectHtmlResponse 两处定义（V2ApiClient.kt:113 / V2Mappers.kt:124）+ V1 无定义 |
| D2-23 | A 单路 | ✅ | 回读 V2SseMapper.kt:125/:151（start = ordinal） |
| D2-24 | A 单路 | ✅ | 回读 McpRepositoryImpl.kt:17-25（@Volatile 共享） |
| D2-25 | A 单路 | ✅ | grep shouldAutoApprove 仅定义处 + UI 入口，无求值调用 |
| D2-26 | D 单路 | ✅ | 回读 SettingsViewModel.kt:163-167（读旧快照再写） |
| D2-27 | E + A | ✅ | 主代理 grep 实测 147 处（子代理估计 152）；NetworkModule Auth 空 install 回读确认 |
| D2-28 | E + 主 | ✅ | 回读 Manifest:35；res/xml 无 network_security_config |
| D2-29 | E 单路 | ✅ | 回读 proguard-rules.pro:24-25,34-35,38,50 |
| D2-30 | E 单路 | ✅ | grep 6 处 WebView 初始化点 + 销毁策略比对 |
| D2-31 | E + A 双路 | ✅ | 回读 V2ApiClient.kt:1157-1166；AGENTS.md 红线引用 |
| D2-32 | E 单路 | ✅ | 回读 SessionRow.kt:367-368 |
| D2-33 | B 单路 | ✅ | 回读 SendMessageUseCase.kt:32（getOrThrow）、DeleteSessionUseCase/UpdateSettingsUseCase（Result）、ListSessionsUseCase（裸 List）、ApiResult.kt:27（Exception 子类）——三套并存确认 |
| D2-L1..L68 | 多路 | ✅/⚠️ | 簇 A/B/C/E/G 中 20+ 条经主代理 grep/回读核实（三路命中 L1、双路命中 L4/L15）；簇 C 中 L23/L24/L25 触发条件已修正（configChanges 处理旋转）；机制类（L46 表格 3 遍 subcompose 等）建议实测 |

**验证统计**：High/Medium 共 33 条全部经主代理人工回读；多源命中 5 条（D2-05 主+A、D2-13 B+主、D2-27 E+A、D2-31 E+A、D2-L1 主+A+E）；触发条件修正 3 条（D2-L23/L24/L25）；建议实测 4 条（D2-01/D2-03/D2-04/D2-L46）。

---

## 6. 已排查无问题（正面确认）

### 6.1 第一期 P0/P1 修复落地复核（重大交叉验证成果）

| # | 第一期条目 | 当前状态 | 证据（主代理回读） |
|---|-----------|---------|------------------|
| 1 | C-1 WebViewScreen 泄漏 | ✅ **已修复（c0c74a4c）** | WebViewScreen.kt:132-139 DisposableEffect onDispose 完整销毁；**且该屏因 useNativeUi=true 已成不可达死分支（D2-L7）** |
| 2 | H-1 ErrorPayloadContent WebView | ✅ **已修复（c0c74a4c）** | ErrorPayloadContent.kt:115-120 onRelease 完整销毁 + JS/DOMStorage/File 访问全禁 |
| 3 | H-2 RenderWebView + M-14 重载 | ✅ **已修复（c0c74a4c）** | RenderWebView.kt:66-72 DisposableEffect destroy + :139-149 lastHtml/lastJsCommand 防重 |
| 4 | H-3 ImagePreviewDialog 解码 | ✅ **已修复（c0c74a4c）** | ImagePreviewDialog.kt:70 decodeSampledBitmap(256,256) / :112 (2048,2048)；残余主线程 Base64 见 D2-L68 |
| 5 | M-9 MediaUtils 解码 | ✅ **已修复（c0c74a4c）** | MediaUtils.kt:174-189 inJustDecodeBounds + calcInSampleSize + JPEG RGB_565 |
| 6 | 目录缓存（第一轮提及） | ✅ 已落地 | DirectoryManager 200 条 LRU + 30s TTL |

> **建议**：将上述状态回写 `audit-2026-08-13-memory-perf/REPORT.md`（C-1/H-1/H-2/H-3/M-9 标记已修复），并同步 backlog #93/#94 状态。

### 6.2 本期正面确认（主代理 + 4 路子代理交叉核实）

| # | 类别 | 核查结论 |
|---|------|---------|
| 1 | 日志纪律 | 全库 **0 处**绕过 AppLogger 的 android.util.Log（仅 AppLogger.kt 内部 8 处桥接）；println 0 处；AppLogger 引用 487 次 |
| 2 | 导航参数 | URLDecoder.decode 仅 NavUtils.kt:15（safeDecodeParam 内部）；13 处导航参数消费全部走 safeDecodeParam（NavGraph:45 死导入除外，D2-L8） |
| 3 | i18n | 613 字符串 × 15 语言全对齐；抽查 14 个代表性文件全部 stringResource（D2-10/D2-32 为仅有的用户可见硬编码） |
| 4 | 主题 token | Light/Dark/Amoled 三套 scheme + LocalAmoledMode；AlphaTokens 409 处/95 文件、ShapeTokens 147 处/53 文件；硬编码 Color.* 均为 AMOLED/终端调色板语义用途 |
| 5 | 安全设计 | SecretCipher：AndroidKeyStore AES/GCM + 旧明文兼容；backup/data_extraction 排除 datastore/；FileProvider 仅暴露 cache/updates；UpdateInstaller canonicalFile 防穿越 |
| 6 | 更新链 | SHA-256 + 包名 + 版本号 + 签名证书五重校验；250MB 上限；Mutex 串行；24h 节流；3 级回退 |
| 7 | Room/存储 | 损坏恢复（仅真损坏删库）+ zstd 归档分桶 + IN 900 分块 + 归档/裁剪原子事务 + LogStore 多维修剪 |
| 8 | 并发治理 | SessionStateService CAS update（RS-010/011）+ 校验去重 + 24h 孤儿清扫 + 历史有界；SseConnectionManager RS-001~017；StreamingOwnershipRegistry putIfAbsent |
| 9 | SSE 防御 | 单行 512KB/单事件 1MB OOM 防护（丢弃不中断）；401/非 2xx 显式异常；解析错误逐事件恢复；CRLF 兼容；多 data 行 \n 连接 |
| 10 | Markdown 铁律 | Markdown( 仅 MarkdownContent.kt:387/405（均 state 参数）；rememberMarkdownState 均 retainState=true；滚动双 key（isAtBottom 在 key 中）；高度补偿仅流式消息；表格两端 cap 一致（120dp vs 120px） |
| 11 | 终端 | TerminalTabState 5 态 + 纯函数真值表 + 测试；PtyToTermlibAdapter 锁保护 + DECSET 跟踪 + resize 120ms 防抖 |
| 12 | 工具/组件 | 14 张工具卡全部复用 ToolCardScaffold；MessageBubble 统一容器；SafeCatch 已建立；DraftDataStore 懒加载+Mutex+迁移 |
| 13 | 系统集成 | POST_NOTIFICATIONS 首连请求；FGS 仅前台启动；5 通知渠道语义化；深链 resetReplayCache 已消费（N-14 缓解） |
| 14 | 技术债标记 | TODO/FIXME/HACK/XXX 全库 0 处；GlobalScope 0 处；startActivityForResult 0 处；rememberCoroutineScope 34 处全为短生命周期 |
| 15 | 崩溃治理 | Download+私有双 fallback + MAX_LOG_FILES=10 + 提示时间戳；AppLogger 有界 Channel 500 + 崩溃 750ms flush + CAS 时间戳 |
| 16 | 分页/防风暴 | PaginationFSM + synchronized 互斥 + 指数退避 + autoLoadPaused；跳转状态机纯函数 + 超时防御 |
| 17 | UseCase 层 | 26 个 usecase 全部有 UI 调用方；Thin Layer 为架构文档 Option B 已决策项 |
| 18 | 依赖方向 | data→ui 导入为 0（终端体系为已登记可接受债务）；UI 直连 data.api 仅终端例外 |

### 6.3 与第一期/债务文档查重清单（引用不重复）

第一期已覆盖：H-5/H-6/M-15（SSE 分配与双写）、M-1（pendingInputs 无界——本期仅补线程安全面 D2-02）、M-3、M-6、M-2、M-4、M-5、M-7/M-8、M-10~M-16、L-1~L-18、N-1~N-15（N-14 已缓解、N-15 由 N2-11 补充、N-5 随 C-1 已修）。债务文档已登记：Thin UseCase（Option B）、God Files（本期实测**回潮**：ChatMessageList 674→1153、ChatViewModel 493→747、ChatScreen 770→950、SessionListViewModel 522→794）、runBlocking 遗留（§6）。

---

## 7. 技术债专项汇总

| 类别 | 现状 | 建议 |
|------|------|------|
| **God Files 回潮** | ChatMessageList 1153 / V2ApiClient 1414 / ChatScreen 950 / V1ApiClient 926 / MessageEventHandler 839 / SessionListViewModel 794 / ChatViewModel 747 / AppNotificationManager 675 / ServerTerminalWorkspace 638（08-07 瘦身后 4 个文件增长 60-70%） | 下轮重构优先拆 ChatMessageList 与 V2ApiClient（按域拆分，与 V1 对称） |
| **V1/V2 双客户端** | V1ApiClient 926 + V2ApiClient 1414 + SseClient/SseClientV2 对称重复；修复只落一端已实证（heartbeat V2-only，D2-05）；authHeader 147 处各自维护（D2-27）；exportSessionToStream 整方法复制（D2-L11）；rejectHtmlResponse 复制（D2-22） | 抽公共模板或确认 V1 下线计划；建立「改动双端」review 清单 |
| **弃用/死代码面** | @Deprecated 委托链 ×9（D2-L1）、桩方法 ×4（D2-L2）、无调用方 API ×6（D2-L3~L10）、死分支 WebView ~15KB（D2-L7） | 清理日集中删除（先 grep 测试引用） |
| **裸 catch 迁移** | 132 处 `catch (e: Exception)` vs safeCatch 7 处；service/data 多数有 CancellationException 前置捕获（抽查安全） | 分批迁移：优先 while(isActive) 循环与 suspend 块；新代码强制 safeCatch |
| **构建/测试** | R8 keep-all 过宽（D2-29）；Kotlin 2.3.21 + force metadata 2.4.0 倾斜（D2-L64）；isReturnDefaultValues=true；largeHeap=true；cleartext 全局（D2-28） | 按各条治理；泄漏治理后评估移除 largeHeap |
| **未提交 WIP** | 31 文件（提问卡片重构，2026-08-14） | 交付前提交；避免审计结论与提交状态错位 |

---

## 8. 修复路线图

| 优先级 | 条目 | 说明 |
|--------|------|------|
| **P0** | D2-03 SSE 阻塞读挂死 | 读循环 withTimeoutOrNull(40s)，改动小收益大 |
| P0 | D2-04 dataSync 6h 时限 | 覆盖 onTimeout + 恢复逻辑 |
| P0 | D2-01 V2 part id 契约 | V2Mappers 派生 id 对齐 V2SseMapper（先实测复现） |
| P1 | D2-02 pendingInputs 并发 | ConcurrentHashMap + 按 serverId 隔离 |
| P1 | D2-05 V1 心跳对齐 V2 | 任意事件刷新；配合 D2-03 一并修 |
| P1 | D2-11/D2-12 多服务器键维度 | (serverId, sessionId) 复合键 + 去掉 currentServerId 单值 |
| P1 | D2-06 草稿恢复竞态 + D2-26 设置竞态 | UI 状态时序修复，改动小 |
| P1 | D2-27 authHeader 统一 | 抽 auth(conn) 扩展或 Auth provider（147 处替换，风险中等） |
| P2 | D2-14/D2-16/D2-17/D2-18/D2-19/D2-20/D2-21/D2-25 | 见各条建议 |
| P3 | Low 簇 A~H + D2-33 异常契约统一 | 死代码清理日 + 令牌替换 + Regex 预编译 + 重复代码收敛 + 失败约定统一；建议与第一期 P3 批量合并执行 |
| 文档 | 回写第一期报告状态 | C-1/H-1/H-2/H-3/M-9 标记已修复（c0c74a4c）；backlog #93/#94 转正 |

---

## 9. 附录

- **审计基线**：master @ 3a866bed + 工作区未提交 31 文件；第一轮基线 3bdd7990（P0 修复提交 c0c74a4c 在其后）。
- **子代理产出**：A data 30 条、B domain/di/service 21 条、C UI-chat 20 条、D UI-其他 22 条、E 跨切面 19 条 = 112 条候选；合并去重后：4 High + 29 Medium + 68 Low + 12 备注 = **113 条**（含送达复核补录的 D2-33 异常契约条目）。
- **统计口径**：authHeader 147 处、SimpleDateFormat 14 处、剪贴板 9 处、裸 catch 132 处、dp 379 处/sp 52 处、TODO 0 处——均为全库 grep 全量计数。
- **工具链建议**（沿用第一期 §7）：LeakCanary（debug）/StrictMode/Baseline Profile/Regex 预编译规范/内存上限规范化/CI 门禁——本期不重复。
- **局限**：纯静态审计；D2-01/D2-03/D2-04/D2-L46 等机制推演条目建议模拟器/真机实测；运行时行为（真机断网/半开连接/Android 15 6h 超时）未验证。