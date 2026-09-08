# docs-consolidation（2026-09-09）

> 状态：进行中
> 关联：（spec 路径，若有）·（issue 编号，若有）
> 来源：用户反馈 / grilling / E2E / 顺带发现

<!-- 过程中的取证/验证证据直接写本文件；backlog.md 只留 ≤3 行卡片。 -->

## §一 #382 执行记录（2026-09-09）

**合并产出**（三并行子代理，忠实性程序化/逐字抽查通过，冲突取舍均带合并注）：
- `docs/verification.md`（553L）← verification-requirements(318)+qa-methodology(234)：V1-V6 权威+方法论章；四处源间冲突按「最新日期裁决优先」取舍（时间性现象归属以 2026-09-07 勘误为准、D1-D5↔D0-D4 对应式删除、Layer 清单 V1-V9 自用序号改检查项命名+维度标注、双铁律句互补保留）；7 处带日期裁决逐字保留。
- `docs/probing.md`（326L）← observability(169)+ui-probing(84)+simulator-perf(78)：三层路由+决策树开篇，仪器目录十二章；IME/tap 失活纪律确认由 device-testing「E2E 操作纪律」承载并加指路。
- `docs/device-testing.md`（225L）← real-device-testing(168 主体逐字)+e2e-testing-workflow(118 环境节)：真机优先双章结构；滞后事实（4096/环境变量口径）带日期勘误修正。
- `simulator-walkthrough-v1v2` → `docs/research/2026-08-13-simulator-walkthrough-v1v2.md` 归档+注记。

**防失实迁移**：D3-3「UI 语言锁定」裁决原仅存于 e2e-testing-workflow 头部 → 迁 dialogue-e2e-test-plan 头部（device-testing 代理预警，tombstone 前完成）。

**Tombstone×7**：verification-requirements/qa-methodology/verification-simulator-perf/observability-verification-guide/android-ui-probing-guide/e2e-testing-workflow/real-device-testing——各留一行指向新址；journal/acceptance/research/terminology 历史引用不断链。

**改链清单**：AGENTS.md 索引（5 旧行→3 新行，MUST 行换 verification.md）+内联×3；release-workflow×2；ai-acceptance-workflow×7；regression-guide×8；dialogue-plan×1(+D3-3)；numbering-charter §四注记×3；type.sh；CompactionNormalizer.kt；maestro/README（含 2c→V4 charter 映射）；agents-file-design MUST 示例；backlog #345 路径；device-testing:174 自指 probing。

**豁免决定**：docs/terminology/* 带日期审计记录不改写（含旧行号引用）——tombstone 可解析即达成不断链目标。

**验证**：全仓残留扫描=0 真残留（命中项均为「#382 整合自 …」来源注记）；backlog-check 通过。

## §二 独立审计与收尾（2026-09-09）

**独立审计**（纯净审查代理，脚本化：81 条规范行提取→逐字/4-gram 双通道匹配→逐条判读）：
- 64 逐字命中 + 14 合法改写（重编号带合并注/结构自指失效/等价改写在场）+ 3 边缘项——集中于 e2e-testing-workflow 的用例/执行域条款（触发类型清单/委派 prompt 四要素/禁止 global 聚合目录），活文档零承接。
- tombstone×7 指向正确；归档 R097 diff 恰 +2 行注记；D3-3 逐字迁入 dialogue-plan:9。
- 结论：**有条件通过**。

**条件解除（补录路径）**：三条款以「E2E 执行规范」小节回补至 device-testing.md 测试用例矩阵节下（原文 git 79da4e8f^ 逐字迁入，带 #382 审计回补来源注）。

**机械校验**：全仓链接存在性（正确相对基准解析）通过；AGENTS.md 陈旧 spec 行（error-report 已归档 docs/archive/specs/，1940d8ff）顺手移除。

**验收**：用户授权自行评估（2026-09-09「你自行评估验收吧」）——审计有条件通过+条件解除+机械校验全绿 → 卡片 migrate 关闭。

## 已完结卡片迁入（2026-09-09）

### **#382 质量保证文档按工作流合并（14→9）——verification 框架权威化(V1-V6)/probing 观测探测手册/device-testing 真机+模拟器环境 runbook；simulator-walkthrough 归档 research；被并文档留 tombstone** `refactor`
  - 2026-09-09 用户裁决采纳四点：目标结构/维度统一 V1-V6/walkthrough 归档/tombstone 保留
  - live 引用改链+AGENTS.md 索引收敛；journal-acceptance 历史引用靠 tombstone 不断链
  - **已实施(2026-09-09)**：三合并+tombstone×7+全仓改链+AGENTS 索引收敛（5 旧行→3 新行）——journal §一；验证=残留扫描 0+backlog-check 通过；待用户抽验合并文档内容后关闭
  - 迁入依据：用户授权自行评估验收（2026-09-09）；独立审计有条件通过+三条款补录解除+机械校验全绿（backlog.sh migrate 2026-09-09）
