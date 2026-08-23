# 术语统一批次 · 裁决日志（全部四轮 + 深调研终局轮）

## 第四轮（终局拷问，2026-08-23，A 类 4 路深调研后）

**批 1（T1-T8，六题）**：
- T1 中断/中止：**单轨全「中断」**——废除 mapping「本地=中止」；abortSession→interruptSession（D3-1）后注释统一「中断」；「中止」全面退役
- T2 子会话：**全量换「子智能体会话」**（84 处 M）；mapping share 行同步修正
- T3 通知频道：**全 turn 化**——频道名+描述+EN "Turn completed"（接受频道迁移，旧「任务通知」频道作废重建）
- T4+T5 i18n 全家桶：**全同步**——Assistant→Agent、sub-agent→subagent 6 键、Task completed→Turn completed、folder→directory、zh QUEUED 徽章保留英文、zh「排队消息」4 键改「堆积消息」；14 语言连锁 + 288 key 补缺一并做
- T6 历史豁免：**CHANGELOG 历史段也改**（比推荐更彻底）；journal/archive/旧 spec 仍豁免
- T8 turn 写法：**一律「轮次」**——CHANGELOG/RN/UI 文案统一「轮次（turn）」体系；首现标注；「第 N 轮=设计迭代轮」豁免注记；G5 早前「turn 完成」随之更新为「轮次完成」（EN 显示词 Turn completed 不变）

**批 2（T7/T9/T10/T11 + 打包，五题）**：
- T7 dialogue-e2e：**整册改「会话」**（两文档 + AGENTS.md 索引行 + 文档自述）
- T9 回退分域：**五域分词表**——撤销/改回/向后跳/退化/降级，「回退」全局退役（历史 commit 豁免）
- T10 术语锚点：**实态优先+先落后发**——术语批次先落，发版时实态=规范名
- T11 commit 前缀：**恢复强制 type 前缀**（feat:/fix: 用户可见变更必须；AGENTS.md 明文）
- 打包 14 项全收：P1 drain=发送中 · P4 isStreamingMsg 桥接 · P6 role Assistant 豁免 · P7 端点双标 · P8 发送动词表 · P9 「任务」限通知域 · P10 预告注释只挂接口 · 游标首现限定 · 压缩语序变体 Avoid · CONTEXT 补 7 词条 · 总则成文化 · 跳转 undo/redo 定名 · A4 写作规范 5 组 · A1 官方修正包

**A1 官方对照关键事实**：question.v2.asked 是官方事件（C18 修正）；msg_ 非 ULID（去 ULID 化措辞）；V2 是官方概念；about_opencode_url 结案（15/15 = anomalyco）。


## 第三轮（2026-08-23，总原则：最彻底 + 一致）

**元原则**（用户明令）：所有遗留决策取"最彻底且一致"的方案；与既有"全面、完整、彻底、不遗漏"总方针一致。彻底性边界 = 服务器契约（wire 层说协议语言，客户端词汇全部统一 V2）。

### D3-1 V1/V2 接口标识符统一（C18-C21 标识符半边）
- **裁决：domain/impl/UI 全部统一 V2 词标识符**（编译器保护，触面实测）：
  - abortSession → interruptSession（19 处）
  - updateSession(title) → renameSession（28 处）
  - summarizeSession 并入 compactSession 单入口（7+13 处，版本分流内化到 impl）
  - removeProviderAuth → removeProviderCredential（~3 处）；getProviderAuthMethods 保留（"认证方式"≠"凭据"，语义独立）
- **wire 层不动**：@SerialName/端点路径是 V1/V2 服务器契约，两种协议都要说——这不是妥协，是一致性的正确边界（客户端词汇 vs 协议词汇分层）。词汇表已收录 V1 词为"历史对照"。
- 归入 Tier A 扩展（+1-1.5 天）。

### D3-2 重命名范围维持 Tier A+B（用户复确认）
- 维持"A+B 并入本批次"；D3-1 并入后重命名工作流合计 ≈ 4.5-5.5 天。

### D3-3 E2E 语言策略（C95）——最彻底=全英文锁定
- 34 条 flow 全部锁英文（英文源=15 语言之源，本就最一致）；修 3 处中文硬编码会话名 + perf flow 中文选择器；约定写入 docs/e2e-testing-workflow.md。

### D3-4 flavor vs channel（C85）——最彻底=全仓唯一词 flavor
- gradle dimension 标识符 "channel" → "flavor"（build.gradle.kts 4 行协同改，任务名不含 dimension 名，零功能影响）；CI/scripts/AGENTS.md/注释已主流 flavor，补齐尾巴。

### D3-5 编号体系统一重构（C88，用户选激进方案）
- **新体系（四前缀同构数字制）**：
  - V1–V5 = 验证维度（吸收 维度1-5 / D1-D5 / D0-D4）
  - A1–A13 = 审计维度（原审计 D1-D13）
  - P0–P3 = 优先级（保持 backlog 全局编号）
  - S0–S3 = 严重度（Critical/High/Medium/Low 数字同构化）
  - F1–F5 = maestro flow 层级（原 l1-l5，避混淆）
  - #N = backlog 全局条目（D2-L54 式评审编号退役）
  - 字母 D 前缀全局退役（三义消解）
- **局部标签规则**：代码/测试内场景标签（RS-0xx/T1-T10/C1-C10）保留为局部标识，但不得与全局前缀 V/A/P/S/F/# 冲突（charter 明文）。
- **落地**：docs/numbering-charter.md 新建（含新旧映射表）；前瞻文档（AGENTS.md/verification-requirements/qa-methodology/regression-guide/e2e 两篇）迁移；历史 journal/archive 不重写，靠 charter 映射表回溯。
- 独立票入 spec（估 1 天）。

## 第二轮（M1-M5）/ 第一轮（G1-G9、C61）——见 RESUME-PLAN.md 与 conflicts-master.md
全部已闭合并落 CONTEXT.md（38 词条）。