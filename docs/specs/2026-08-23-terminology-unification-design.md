# 术语统一批次设计（#193）

> 状态：**已定案待实施** · 关联：[ADR-0001](../adr/0001-terminology-authority.md) · [CONTEXT.md](../../CONTEXT.md)（46 词条，权威）· journal：`docs/journal/2026-08-23-batch.md`
> 来源：用户需求「全项目统一术语 + 据实修注释」；990 文件盘点 → 四轮裁决（G/M/D3/T+P）→ A 类深调研 4 路 → B 类同步再分析。

## 目标

以 CONTEXT.md 46 词条为唯一规范，完成四层落地：①代码注释（统一中文+术语对齐+失实注释修订）②项目文档（勘误+违逆修复+编号 charter）③UI 文案（EN 源显示词+14 语言+288 key 补缺）④标识符 Tier A+B 重命名。全程 coverage 对账零遗漏。

## 权威输入（实施 agent 必读）

1. `CONTEXT.md`（根）——46 词条 + 总则三豁免（Avoid=名称性使用；标识符/键名豁免；证据引用原样）
2. `docs/terminology/`——8 路盘点（inventory/01-08）、冲突台账 v8（95 条+14 代码事实+直接修订清单）、对照表、四轮裁决日志、重命名评估（Tier A/B/C）、深调研 4 路（survey/）
3. 修订 SOP：`docs/terminology/survey/A3-revision-dryrun.md` §7（八步：前置输入→grep 复定位→命中扫描→四类落格→改写顺序→不动清单→自查）——**台账行号不可信，一律 grep 复定位**

## 范围

**In**：main/test/androidTest 全部 .kt 注释 · docs/ 活跃文档 · AGENTS.md/README/CHANGELOG（含历史段，T6 裁决）· RELEASE_NOTES · maestro flows 文案引用 · strings.xml 15 文件 · 标识符 Tier A+B（评估文件全列）· numbering charter。
**Out（豁免）**：docs/journal、docs/archive、已废弃 spec（fab spec 4 行修或迁 archive）、docs/learning（个人专区）、历史 commit message、wire 层（@SerialName/端点路径）、Tier C 五项（#201-#205 独立卡，2026-08-23 合并顺移 +5）。

## 实施票（12 张，声明阻塞边）

| 票 | 内容 | 阻塞于 | 特殊纪律 |
|---|---|---|---|
| KT1 | ui-chat 注释（158 文件；含 ChatScreen.kt） | spec | **ChatScreen.kt 编辑协议**：Read→编辑→编译→commit 循环，禁并行 |
| KT2 | data 层注释（114 文件）+ D3-1 预告注释（⚠️ 格式只挂接口声明，P10） | spec | — |
| KT3 | domain 层注释（91 文件） | spec | — |
| KT4 | ui-rest + main-misc 注释（226 文件） | spec | — |
| KT5 | tests 注释（248 文件；测试名保持英文 C61） | spec | — |
| KT6 | docs 勘误票：AGENTS.md「流式消息」违逆（grep 定位）· OC Remote 残留 · README 已删功能 · 1.x 示例 ×4 · dialogue-e2e 整册会话化 · **numbering-charter.md 新建**+前瞻文档编号迁移（D3-5） | spec；charter 先于 KT12 | — |
| KT7 | Tier A 重命名（评估 Tier A 表+V1/V2 domain 方法统一 4 项） | spec | 一符号一 commit；gradle 串行 |
| KT8 | Tier B 重命名（sessionStateService 变量族/jumpLockActive/maxCompleted 族/collapseTools 语义反转） | spec | **collapseTools 走 TDD 红绿**；行为反转需真机验证清单 |
| KT9 | E2E 英文化（maestro 3 处中文会话名+perf flow+约定写入 e2e-testing-workflow.md） | spec | — |
| KT10 | i18n 票：EN 源显示词全家桶（Assistant→Agent/sub-agent→subagent×6/Task→Turn completed/folder→directory）+ zh「排队消息」4 键→堆积 + 通知频道全 turn 化（旧频道作废重建说明）+ 14 语言连锁 + **补缺 288 key**（ja27/ar55/ru103/uk103）+ i18n 检查脚本 | spec | 15 语言全量；CI 检查兜底 |
| KT11 | 写作规范票：release-notes 模板规则 9+示例 · release-workflow §4.5 润色术语核对行 · backlog 术语句 · journal 三原则（落 backlog §Journal）· AGENTS.md commit 前缀纪律（feat:/fix: 强制） | KT6（charter 引用） | — |
| KT12 | 终审：code-review（Standards=CONTEXT.md 46 词条）+ coverage 对账（990 文件台账 vs 实改清单）+ backlog-check + 词汇表收尾 | **KT1-KT11 全部** | 验证维度 4+1 |

**全局纪律**：gradle 禁并发（同一 checkout）——各票编译验证排他执行；≤3 票并行；每票独立 commit；chat 系文件遵守编辑协议。

## 验证

- 每票：`:app:compileDevDebugKotlin`（120s）+ 触面相关单测（`testDevDebugUnitTest --rerun` 180s）
- KT8 collapseTools：红绿单测 + 真机 houji 人工清单（维度 5）
- KT10：i18n 完整性检查脚本（CI 同款）
- KT12：双轴 code-review + 台账对账（每冲突条目出现点核销）

## 风险与红线

- **行为零变更**（除 KT8 collapseTools 显式反转与 KT10 文案）：不触 SSE 铁律/Material 约定/导航参数
- 重命名全程编译器保护；发现编译外触面（反射/序列化）立即停手上报
- 历史豁免边界争议时按「T6：CHANGELOG 历史段不豁免；journal/archive/废弃 spec 豁免」执行
