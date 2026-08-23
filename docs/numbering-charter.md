# 编号体系统一 charter（V/R/A/P/S/F 前缀体系）

> 依据 D3-5（用户裁决：统一重构）+ 总则三豁免（标识符豁免）。本草案已按 verification-requirements.md / regression-guide.md / maestro/README.md 实测事实修正 D3-5 原设计的三处偏差。

## 一、事实修正（相对 D3-5 原设计）

1. 验证维度实为 **1 / 2 / 2b / 2c / 3 / 5**（有子维度、4 缺失）——不是干净 1-5。charter 重排为 V1-V6 干净序列。
2. regression-guide 的 **D0-D4 是回归档位**（场景矩阵轴），与审计 D1-D13 是两套——D3-5 未区分。charter 单列 R 系。
3. maestro **l1-l5 是文件名前缀**（l1-app-launch.yaml 等 34 文件）——按总则「标识符豁免」**不改文件名**，仅文档叙述用 F 系对照（D3-5 原设计隐含改文件名，修正为豁免）。

## 二、新体系（六前缀 + #N）

| 前缀 | 含义 | 吸收旧编号 | 迁移映射 |
|---|---|---|---|
| **V1-V6** | 验证维度 | 维度1→V1 · 维度2→V2 · 2b→V3 · 2c→V4 · 维度3→V5 · 维度5→V6 | V3=原 2b 实机走查铁律 · V6=原 5 人工验证 |
| **R0-R4** | 回归档位（场景矩阵轴） | regression D0-D4 | 表头与正文全迁 |
| **A1-A13** | 审计维度 | 审计 D1-D13 | audit 报告与引用 |
| **P0-P3** | 优先级（不变） | P0-P3 | 无迁移 |
| **S0-S3** | 严重度 | Critical/High/Medium/Low + C/H/M/L/N 缩写 | S0=Critical · S1=High · S2=Medium · S3=Low |
| **F1-F5** | flow 层级（文档叙述） | l1-l5（maestro README/文档引用） | **文件名 l1- 前缀豁免（标识符域）**；文档写「F1 基础层（文件名 l1-*）」 |
| **#N** | backlog 全局条目 | 不变 | D2-L54 式评审编号退役（历史引用靠 charter 映射回溯） |

**D 前缀全局退役**（三义消解：维度 D→V · 档位 D→R · 审计 D→A）。

## 三、局部标签规则

代码/测试内局部标签（RS-0xx 竞态 · T1-T10/C1-C10 用例 · L2-L4 兜底层级）保留可追溯；**不得与全局前缀 V/R/A/P/S/F/# 冲突**（现无冲突，成文防新增）。

## 四、迁移范围（KT6 执行清单）

| 文件 | 动作 |
|---|---|
| docs/numbering-charter.md | 新建（本草案定稿版 + 新旧映射表 + 局部标签规则） |
| AGENTS.md | 「4+1 维验证」表述 → 「V1-V6 验证（4+1 框架）」对照注 |
| docs/verification-requirements.md | 全文维度 1/2/2b/2c/3/5 → V1-V6（首现标注旧名） |
| docs/qa-methodology.md | 维度交叉引用迁移 |
| docs/regression-guide.md | D0-D4 → R0-R4（表头+正文）；§D1 单测分层 → R1 |
| docs/e2e-testing-workflow.md | l1-l5 叙述 → F1-F5（文件名不动） |
| maestro/README.md | 层级表加 F 系对照列（文件名不动） |
| docs/dialogue-e2e-*.md | 随整册会话化一并迁移编号 |
| **历史豁免** | docs/journal/archive/旧 audit 报告不重写——charter 映射表回溯 |

## 五、验证

迁移后全仓 grep 断言：活跃文档中裸「维度 2b」「D2-L」「审计 D1」零残留（豁免区除外）；charter 自身含完整映射表。
