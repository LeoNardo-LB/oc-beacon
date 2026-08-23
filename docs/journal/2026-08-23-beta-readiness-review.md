# beta-readiness-review（2026-08-23）

> 状态：进行中
> 关联：#191（实现+真机验证）· #151 后续（issue 标题派生）· beta 发版评估
> 来源：用户指令「全面系统性检查（代码质量/功能/遗留项/backlog）+ beta 发版评估与执行」

<!-- 过程中的取证/验证证据直接写本文件；backlog.md 只留 ≤3 行卡片。 -->

## 一、全面走查结论

- **CI/测试/i18n 基线**：dev.22 CI success；全量单测 exit 0；i18n-check 675 keys × 14 langs 绿。
- **TODO/FIXME 扫描**：26 处命中全部为 TODO 面板功能命名与已登记契约备注（V2ApiClient:480 嵌套契约实证），无死代码/临时探针残留（TEMP-PROBE 清理已验证）。
- **backlog 余量**：P0 空；P1 两项（#154 blocked-by-design 等待 #151 稳定观察、#146 上游外部依赖）；P2/P3 全部为显式缓办/观察/won't-do（#158/#161/#166/#168/#184/#185）。**唯一可快速实现的高价值项 = #191**（本次实现，见下）。
- **beta 阻塞项评估**：无硬阻塞。V1 分页降级（V1 服务器 API 限制）已知且优雅处理；会话进入一次性 NPE（08-23，未再现）按观察处理。

## 二、#151 后续：issue 标题派生（810f3416）

用户指出上报 issue 标题写死「错误上报」让人看不懂。核实：`DiagnosticsViewModel:185` 硬编码，
偏离 spec §报告内容格式（`[user-report] <错误摘要>`）。

- `ErrorReportService.issueTitleForError(category, message)` 纯函数：与指纹同源条目派生
  `category: message` 单行折叠、100 字符截断；message 已过脱敏管道不重复处理。
- `Preview(title, body, fingerprint)` 贯穿；预览对话框新增只读标题行（`report_issue_title_label`，15 语言，i18n 675 keys 绿）。
- 单测 +1（派生/截断/空退化），ErrorReportServiceTest 9/9 绿。

## 三、#191 方案 B 实现（5693ddb6）

设计定案见 `2026-08-23-issue-cleanup-triage.md` §四。本批落地：

| 接线点 | 位置 | 行为 |
|---|---|---|
| 常量 | `WAITING_CONFIRM_WINDOW_MS = 60s` | 抑制窗口（internal 供测试） |
| 打标 | `triggerRestValidation` Busy 分支 | 提升 pending/children 查询至 quietMs 判定前；等待态打标，非等待清标 |
| 抑制 | `checkStaleness` L2 分支 | 窗口内跳过 WARN+REST；窗口过后带 `(waiting re-confirm)` 后缀复核 |
| 清标×2 | `onSseEvent`（映射非空）/ `onRestValidation`（非 Busy） | 会话苏醒或等待结束即失效 |
| 防漏 | 孤儿 sweep | `expired.forEach(waitingConfirmedAt::remove)` 防 map 无界增长 |

FSM 语义/zombie 禁用/E2E-G 决策/RestValidation 不刷 lastEventAt 全部不动。
单测：SessionStateServiceTest 19→24 例全绿（打标 pending/children、非等待清标、SSE 清标、非 Busy 清标）。

### 真机验证（进行中）

（待补：挂机等待态会话 logcat 计数）

## 四、beta 发版评估

- 脚本机械推导：`0.3.2-beta.1`（仅分析 v0.3.1-dev.22..HEAD 两个 fix → patch）。
- 内容事实：自上个公开 beta（v0.3.0-beta.3）以来 **99 feat / 837 commits**（Termux 终端栈、
  ModelPicker 重做、堆积队列管线、会话内音效、GitHub 错误上报、FAB v6 等）——按 §2.2 feat→MINOR
  应为 `0.4.0-beta.1`。**待用户拍板**（--force-bump=minor）。

（发版执行记录待补）