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
