# beta-readiness-review（2026-08-23）

> 状态：已完结（v0.3.1-beta 已发版，CI success）
> 关联：#191（实现+真机验证，待用户验收）· #151 后续（issue 标题派生）· beta 发版 · 版本线模型重构
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

### 真机验证（通过，2026-08-23 17:09–17:15，houji devRelease 5693ddb6 自建包）

构造：V2 服务器 curl 建「#191验证-等待态」会话 + prompt 指示 agent 用 question 工具提问（tool question status=running、
/active 恒 running）→ 冷启动 App 挂机 5.5min 采集 `logcat -s SessionStateService`。

| 指标 | 修复前（journal 取证） | 修复后实测 |
|---|---|---|
| L2 stale WARN | 12 条/min | 6 条 / 5.5min（首触发 15s + 每 65s 一次 re-confirm） |
| zombie-skip WARN | 12 条/min | 3 条 / 5.5min（仅 quietMs>3min 后的 re-confirm 轮触发） |
| REST 校验 | 12 次/min | ~0.9 次/min |
| 降幅 | — | **≈93%**（设计估算 ~92%） |

细节核验：`(waiting re-confirm)` 后缀按设计出现；zombie 误杀防护与 Busy 保持语义不变；答题后事件流恢复
由 SSE 清标路径覆盖（单测）。测试会话已 interrupt 释放。

## 四、beta 发版评估

- 脚本机械推导：`0.3.2-beta.1`（旧逻辑通道切换即 bump——本次重构废除的根因）。
- 内容事实：自上个公开 beta（v0.3.0-beta.9）以来大量 feat/fix（Termux 终端栈、ModelPicker 重做、堆积队列管线、
  会话内音效、GitHub 错误上报、FAB v6 等）。
- **用户拍板：0.3.1 的 beta 即可**（线内晋升语义，与重构后的版本线模型一致）。

## 五、版本线模型重构（913fa11f，用户定规）

用户模型：同一 X.Y.Z 走完 dev → beta → 正式；beta 每线单发无序号（`X.Y.Z-beta`），dev 线内迭代 `dev.N`；
通道切换永不 bump；新线按内容递进（feat→+0.1.0 / fix→+0.0.1）。

落地：`release.sh` 重写版本计算块（通道闭合检测 + 开新线推导基准 + 防回退护栏）；
`ci-determine-flavor.sh/.ps1` 适配无序号 beta 后缀（原 `*-beta.*` 匹配会漏判成 stable）；
`release-workflow.md` §2.1/2.3/3.3/4.5 同步。七场景克隆回归全绿：
A 0.3.1-beta / B 0.3.1 / C dev.23 / D beta 重发→0.3.2-beta / E 正式后 dev→0.3.2-dev.1 / F beta→stable 线内晋升 / G force minor。

## 六、发版执行

- 版本定版：`0.3.1-beta`（code=37，tag v0.3.1-beta，线内晋升无 bump）
- notes 基准 v0.3.0-beta.9（上个公开 beta），按模板人工润色（8 条 Added / 2 Changed / 4 Fixed，用户视角）
- commit 02f3bc30 + tag 推送成功

**CI 结果：success（run 32630556103）。Release `v0.3.1-beta`（prerelease）上线，
资产 oc-beacon-0.3.1-beta.apk（7.5MB）。**

## 七、收尾状态

- #191：实现+单测+真机验证全过，**待用户验收**（backlog [~]）
- issue 标题派生：实现+单测过；E2E 需 GitHub App 凭据（用户侧注册后可验）
- GitHub issue 隐藏调研：报告归档 `docs/research/2026-08-23-github-issue-hiding.md`，
  推荐 P1 专用子仓库方案——**用户定规：否决，报告留主库**（见 §八）

## 八、发版后追加：标题区分度 + GitHub App 注册向导

用户两条指令：① 搞定 GitHub App 凭据注册（解锁 issue 标题改进的完整 E2E）；② issue 不隐藏、
留主库，但**标题必须有区分度**（不同错误一眼可分辨）。

### 标题区分度实现

`issueTitleForError(category, message, fingerprint)` 三手段：
- category 作类目前缀（`SseClient: ...`）
- 超长 message **中段截断**（头 56 + … + 尾 24——异常类在头、细节常在尾，纯头部截断抹掉区分信息）
- 尾缀指纹 SHA-256 前 4 字节签名 `(#xxxxxxxx)`：**不同错误标题必不同（硬保证）**；同错误重复上报
  标题一致（与查重归并语义对齐）；签名同时是正文机器块指纹的关联线索

测试：ErrorReportServiceTest 9→12 例全绿（前缀+签名 / 中段截断 / 空退化 / 异指纹必异签名）。
向导预览界面复用同一 title（只读行），用户提交前即可看到最终标题。

### GitHub App 注册向导（scripts/setup-github-report-app.sh）

/wizard 技能生成，六阶段：注册 App（Device Flow + Issues RW + token 不过期）→ 捕获 Client ID →
生成捕获 Client Secret → 安装到仓库 → **device flow 端点自动验证**（curl POST /login/device/code
检查 device_code 字段）→ 重建+真机验证指引。凭据落点：local.properties（本地构建，键名与
app/build.gradle.kts 一致）+ gh secret APP_GITHUB_CLIENT_ID/SECRET（CI，release.yml 已接线）。

**待办（#193）**：用户跑向导 → 真机 E2E（授权 → 预览见签名标题 → 建 issue → 重复上报归并评论）。
