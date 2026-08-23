# 标识符重命名侵入修改 · 影响面/风险/测试保障评估

> 生成于术语统一批次 Phase 1→2 间隙；应用户要求测算「如果改变量/方法名」的成本。
> 数据来源：盘点台账 95 冦突 + 全仓静态 grep 实测（2026-08-23，基线 477f564a）。
> **本文件只是评估，不执行**；结论将登记 backlog 作后续任务卡片。
>
> **执行状态（2026-08-24）**：Tier A/B 已随 #193/#200 消化；Tier C 五卡（#201–#205）已执行完毕——实改 #204（i18n key）/#202（collapse_tools→auto_expand_tools 迁移），#201/#203/#205 经逐项审计裁决零改名并交付安全网（WireCompatMatrixTest/迁移测试/契约锁）。预估与实况的多处偏差（getIdentifier=0、值语义从未反转、Room 无术语冲突）见 docs/journal/2026-08-24-tier-c-contract-renames.md。

## TL;DR

- **可以做且值得做**：Tier A+B 纯编译器保护的重命名（~15-18 个符号，触面 2-103 处/符号），编译+2019 单测保障下风险低，估 3-5 个工作日（agent 串行批次可压缩）。
- **不要跟术语批次绑定**：Tier C 契约面（@SerialName 149 / DataStore key 50 / i18n key / intent extra / Room 列）——编译器不保护，错一个=线上数据丢失或协议破坏，且测试栈对「迁移正确性」覆盖不足，每项需独立 spec+迁移代码+灰度。
- i18n **文案值**的修订（不改 key）属于本批次 Phase2b，风险独立可控；i18n **key 改名**属 Tier C。

## Tier A 扩展：V1/V2 domain 方法统一（D3-1，第三轮裁决）

| 符号 | 触面 | 说明 | 估时 |
|---|---|---|---|
| abortSession → interruptSession | 19 处 | domain 接口+impl+调用点 | 1h |
| updateSession(title) → renameSession | 28 处 | 同上 | 1h |
| summarizeSession 并入 compactSession | 7+13 处 | 单入口版本分流内化 | 2h |
| removeProviderAuth → removeProviderCredential | ~3 处 | getProviderAuthMethods 保留 | 0.5h |

小计 ≈ 0.5 天。wire 层（@SerialName/端点）不动——协议契约边界。

## Tier A：局部符号，编译器全程保护（推荐首选）

| 符号 | 触面 | 风险点 | 估时 |
|---|---|---|---|
| JumpPrefetchStrategy（名实不符 F13） | 4 处/4 文件 | 无；纯改名 | 0.5h |
| :///drives 哨兵收口单点（F05） | 2 处 | 收口后删重复常量 | 0.5h |
| categoryFilters→tagFilters 族 | 7 处/7 文件 | UI 状态名，配编译 | 1h |
| ManageTerminalUseCase→命名对齐命令执行（C09） | 12 处（main 5/test 7） | 类名+注入点，测试同步 | 1.5h |
| subSessionId/onOpenSubSession 统一 | 19+5 处 | 跨 ui/chat 域 | 1.5h |
| categoryAssignments（C29 残留） | 14 处（main 6/test 8） | 状态字段族 | 1.5h |

小计 ~6.5h 纯改 + 编译/测试循环 ≈ **1-1.5 工作日**。

## Tier B：广触面但仍是编译器保护

| 符号 | 触面 | 风险点 | 估时 |
|---|---|---|---|
| sessionStateService 变量→repository 命名对齐（C65） | **103 处/103 文件**（main 48/test 55） | 机械但 PR 巨大；建议脚本化 + 单 commit | 0.5 天 |
| jumpLockActive（C54 定名后） | 27 处（main 13/test 14） | 语义敏感（三窗口机制之一），改名需配词条同步 | 2h |
| maxCompleted/未读字段族（C52） | 19 处（main 11/test 8） | 红点时钟域承重，须读词条后再动 | 2h |
| collapseTools→autoExpand 族（C76 语义反转） | 21 处（main 13/test 8）+ xml | **最高危**：改名同时取反逻辑，必须配单测红绿 | 0.5 天 |

小计 ≈ **1.5-2 工作日**。纪律：一符号一 commit；collapseTools 独立票走 /tdd。

## Tier C：契约面——编译器不/半保护（专项，禁止随批次做）

| 契约 | 规模 | 破坏后果 | 迁移先例 | 保障缺口 |
|---|---|---|---|---|
| @SerialName（wire 字段） | **149 个** | V1/V2 协议解析失败 | metadata 双写先例（V2Mappers） | 无 wire 兼容自动化测试矩阵 |
| DataStore PreferencesKey | **50 个** | 用户设置全量丢失 | unread v2 迁移先例 | 迁移正确性仅靠单测抽查 |
| Room 实体/列（5 实体 + 10 次迁移史） | ArchiveBucket/CachedPart/PendingMessage/CachedMessage/Log | 升级即崩或数据丢 | 已有 MIGRATION_N 纪律 | 迁移测试覆盖不明，需逐表 migration test |
| i18n key 改名（如 category_name→tag_name） | xml 15 文件 + R.string 903 引用点 | 运行时资源缺失崩溃 | 无 | CI i18n 检查可依赖；但 maestro 34 flows 锁文案——key/值改动须同步 flow 否则 E2E 全红 |
| intent extra（22 处）+ 导航参数（27 处） | debug intent #132 外部配置依赖 extra 名 | 真机调试通道断链 | 无 | 完全无自动化覆盖，只能真机验证 |

结论：Tier C 每项独立立项（spec + 迁移 + 灰度/兼容期），每项估 **1-3 天**，五项并行不推荐。

## 测试保障充足性（按 AGENTS.md 验证框架口径）

- **编译**：`:app:compileDevDebugKotlin`（120s 纪律）每符号循环——Tier A/B 的主保障，充足。
- **单测**：2019 @Test（test 196 文件 + androidTest 52）——密度高，但注意 isReturnDefaultValues=true 可能掩盖行为差异；重命名类改动主要靠编译器，单测是回归网。**充足（A/B）**。
- **E2E**：34 maestro flows——覆盖主路径，选择器锁英文文案（C34：3 处中文硬编码），**i18n 值改动必须同步 flow**；标识符改名不触 flow。中等。
- **契约/迁移**：无系统化 wire-matrix 与 Room migration 测试——**不足（C）**，这是 Tier C 判「专项」的根本原因。
- **维度 5（人工）**：collapseTools 取反、未读字段族需人工 UI 验证清单（真机 houji 优先方针）。

## 建议路线（如后续要做）

1. 本批次（术语+注释+文案）先落地——它为重命名提供**目标名**（术语表即改名说明书）。
2. Tier A(含 V1/V2 扩展)+B 立卡（估 4.5-5.5 天），collapseTools 走 /tdd 红绿；编号重构（D3-5）与 E2E 英文化（D3-3）、flavor 统一（D3-4）各立独立票，按符号切票串行，collapseTools 走 /tdd 红绿。
3. Tier C 五项各立卡，注明「需迁移 spec」，排期在 Tier A+B 验收后。
4. 全程遵守：gradle 禁并发、ChatScreen.kt 编辑协议（若涉及）、每符号 commit + `testDevDebugUnitTest --rerun`。