# 模拟器复测报告：backlog #36-#39 修复验证

**日期**: 2026-08-10
**提交**: 37ef2129（审计第一批 P0/P1 四项修复）
**APK**: app-dev-debug.apk（13:53:10 构建，修复后）
**设备**: emulator-5554
**单测基线**: 1364/0 PASS（代码层面已验证）

---

## 总结

| # | 项目 | 结果 | 关键数据 |
|---|------|------|----------|
| #39 | 日志风暴 | ✅ **通过** | 6652→110 条/10s（降 60x），应用诊断日志 5760→20（降 288x） |
| #38 | 主线程阻塞 | ✅ **通过** | 冷启动 3364ms，ANR=0，运行期 0 Skipped frames |
| #37 | 工具进度 UI | ✅ **通过** | 工具卡片 "Read · README.md" 正常显示，output 正确传递 |
| #36 | 数据安全 | ✅ **通过** | App 正常启动读库，0 DB 错误（单测 10/10 已覆盖） |

---

## #39 日志风暴（核心验证）

### 方法
1. 进入会话 → 发送消息触发 SSE 流式
2. 流式期间 `logcat -c` 清零
3. 等 10s → `logcat -d` 统计总量 + 分类
4. 对比修复前基线（E-03-logcat-stream-10s.log: 6652 条）

### 数据

| 指标 | 修复前基线 | 修复后 | 下降倍数 |
|------|-----------|--------|---------|
| **总日志量/10s** | 6652 | **110** | **60x** |
| SseClient（事件日志） | 2924 | **2**（仅 Unhandled event 警告） | 1462x |
| OpenCodeService（processEvent） | 2836 | **0** | ∞ |
| ChatScroll 诊断 | 大量 | **0** | ∞ |
| UnreadDiag 诊断 | 大量 | **0** | ∞ |
| NetTrace（DEBUG 门控） | 大量 | **4**（2 REQUEST + 2 RESPONSE） | — |
| **应用诊断日志合计** | **~5760** | **20** | **288x** |

### 剩余 110 条构成
- **Ktor Client: 90 条** — HTTP 引擎请求/响应日志（REQUEST/METHOD/HEADERS），不在 #39 修复范围（#39 修复的是应用层 SseClient/OpenCodeService/MessageEventHandler/ChatScroll 诊断日志）
- **SessionStateService: 8 条** — 会话状态机 REST 验证逻辑（L2 stale → REST validation），合理业务日志，~1 次/5s
- **NetTrace: 4 条** — listMessages REQUEST/RESPONSE 配对，DEBUG 门控正常
- **SseClient: 2 条** — "Unhandled event: sync" 警告（D 级别），非流式 token 日志
- **其他: 6 条** — GC、system_server 等系统日志

### 结论
✅ **日志风暴根治成功**。修复前的两大风暴源（SseClient 每事件 3 处日志 + OpenCodeService processEvent 双日志）完全消除。应用诊断日志下降 288 倍（2 个数量级以上），符合成功标准。

**证据**: `metrics/R39-stream-10s.log`

---

## #38 主线程阻塞

### 方法
1. `force-stop` + `am start -W` 测量冷启动 TotalTime
2. 全量 logcat 检查 ANR / Skipped frames / StrictMode violations

### 数据

| 指标 | 值 | 判定 |
|------|-----|------|
| 冷启动 TotalTime | **3364ms** | 合理（LaunchState: COLD） |
| ANR | **0** | ✅ 通过 |
| Skipped frames（冷启动期） | 37 + 66 + 36 frames（3 次） | 可接受（初始化开销） |
| Skipped frames（运行期） | **0** | ✅ 通过 |
| StrictMode violations | 17 条 | 全为 ART 内部（JIT/GC ThreadFlip），非应用代码 |

### StrictMode 详情
所有 17 条均为 ART 运行时内部操作：
- `IncrementDisableThreadFlip blocked for Xms`（JIT 编译器，最大 19.7ms）
- `WaitForGcToComplete blocked`（GC 等待，最大 16.5ms）
- `ThreadFlipBegin blocked`（ART 内部）

**无任何应用层主线程阻塞**（无磁盘 I/O、网络操作、数据库查询违规）。#38 修复的核心（消除构造期 runBlocking）验证有效。

### 结论
✅ **通过**。冷启动 3.3s，运行期零丢帧，无 ANR。

**证据**: `metrics/R38-coldstart-logcat.log`, `metrics/R38-full-session.log`

---

## #37 工具进度 UI

### 方法
1. 新建会话（oc-beacon 项目）
2. 切换模型至 DeepSeek V4 Flash（GLM-5V-Turbo 余额不足）
3. 发送 "Read README.md and summarize" 触发 Read 工具
4. 观察 + 截图工具卡片显示

### 数据

| 观察项 | 结果 |
|--------|------|
| 工具卡片显示 | ✅ "Read · README.md" 正常渲染 |
| Thought 指示器 | ✅ "Thought for 1.4s" |
| 工具操作按钮 | ✅ [Open file] + [Copy] |
| 工具 output 传递 | ✅ AI 基于读取内容生成详细摘要（项目特性、技术栈、构建体系） |
| 工具执行→回复生成 | ✅ 流程完整（29.1s） |

### 关键说明
- **修复前**：combine 索引错位 args[8]→args[9] 导致 progress 注入永久失效，工具 output 不显示
- **修复后**：工具卡片正常显示，工具名称/状态/操作按钮完整，output 正确传递给 AI

### 结论
✅ **通过**。工具调用端到端正常，卡片渲染完整。

**证据**: `metrics/R37-deepseek-4s.png`, `metrics/R37-tool-card-deepseek.png`, `metrics/R37-tool-expanded.png`, `metrics/R37-final-result.png`

---

## #36 数据安全

### 方法
运行期难以构造真实 DB 损坏——代码+单测已覆盖（DatabaseRecoveryTest 10/10）。实测确认 App 正常启动读库即可。

### 数据

| 检查项 | 结果 |
|--------|------|
| App 启动读库 | ✅ 会话列表正常加载（多个会话 + 消息历史） |
| 数据库错误日志 | ✅ 0 条（SQLiteDatabaseCorrupt/Recovery/FullLocked/Constraint/DiskIO 均无） |
| 进程存活 | ✅ PID 8916（DB 正常访问） |
| 单测覆盖 | ✅ DatabaseRecoveryTest 10/10 |

### 结论
✅ **通过**（运行期确认 + 单测覆盖）。修复逻辑（仅对 SQLiteDatabaseCorruptException 删库，其他异常原样抛出）已由单测完整验证。

---

## 证据清单

| 文件 | 用途 |
|------|------|
| `metrics/R39-stream-10s.log` | #39 日志风暴 10s 窗口原始 logcat |
| `metrics/R38-coldstart-logcat.log` | #38 冷启动 logcat |
| `metrics/R38-full-session.log` | #38 全量会话 logcat |
| `metrics/R37-deepseek-4s.png` | #37 工具调用 4s 截图 |
| `metrics/R37-tool-card-deepseek.png` | #37 工具卡片截图 |
| `metrics/R37-tool-expanded.png` | #37 工具卡片展开截图 |
| `metrics/R37-final-result.png` | #37 最终回复截图 |
| `metrics/R-ui-*.xml` | 各步骤 UI dump |

---

## 发现的问题（非本次修复范围）

1. **Ktor Client 日志量大（90 条/10s）**：HTTP 引擎默认日志级别较高（REQUEST/METHOD/HEADERS/FROM 等），虽不在 #39 修复范围，但若需进一步降低日志量可考虑调低 Ktor Logger 级别。建议登记 backlog。
2. **GLM-5V-Turbo 模型余额不足**：zhipuai 提供商余额耗尽，持续重试 6/6 失败。这是外部服务问题，非 App bug。切换至 DeepSeek V4 Flash 后正常。
3. **ExifInterface 警告**：截图操作（screencap）产生的图片格式警告，非应用 bug。
