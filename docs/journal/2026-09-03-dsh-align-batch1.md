# dsh-align-batch1（2026-09-03）

> 状态：进行中（项①② 已实现+单测绿；项③④⑤ 待做）
> 关联：backlog **#309** · 路线 `docs/research/2026-09-01-dsh-web-vs-android-gap.md` §11.4 批 1 · 挂点 `docs/research/dsh-gap-2026-09-01/implementability-ui.md`
> 来源：用户「设置目标继续」→ 目标第二项

## 项① goal.complete 第四钮（§11.4 #6：全场最便宜，S/2h 级）

- 数据面零增量：`DshApiClient.goalComplete`(:373)/`ChatRepositoryImpl.completeGoal`(:407) 全链早已在位，纯缺 UI 入口。
- 实现：GoalSheet 动作行第四钮（非 complete 相位常驻：active/paused/blocked 均可标记完成；完成后 phase=complete → 面板回创建表单，Web 语义对位）；ChatViewModel.completeGoal（pause/resume 同款 ref+reportGoalFailure 形状）；ChatScreen 单行接线（协议文件，Read→编辑→编译→commit 循环满足）。
- i18n：`goal_action_complete` 15 语言（en Mark complete / zh 标记完成 / ja 完了にする / ko 완료로 표시 / de Es Fr It PtRu Ru Tr Uk Pl Ar Id 全量）。

## 项② 压缩事件接线（§11.4 #11：UI≈0 纯接线）

- 服务端载荷定音（dsh-compaction-basic/lib/index.js，2026-09-03 源码）：`compaction/start={compactionId,sourceCommandId?,turn}`(:437)、`compaction/summary={compactionId,…,summary,shadowedRange,…}`(:589)、`compaction/end` 失败分支带 `error`(:463)。
- mapper 改动（DshEventMapper Tier2 区）：
  - start → `SessionNext(CompactionStarted)`（DSH 无 V2 message id/reason → 置空）；
  - summary → `SessionNext(CompactionDelta)`（单帧全文一次累积——CompactionCard 展开区实时渲染 deltaText）；
  - end 失败（error 非空）加发 `SessionNext(CompactionEnded(error))`——对位 #219 失败 snackbar 通道；banner 终结仍走 SessionCompacted → dispatcher endCompaction（既有）；
  - prune 维持 Ignored。
- 历史重放安全：DshHistoryFolder 与实况共用 mapSessionEvent——start→end 序列净零（banner 起→落），无跨重启残留。
- 事件复用既有 SseEvent 类（SessionNext/SessionCompacted 已在 dispatcher 注册表）→ **SseEvent 三步铁律无需新增 bind**。
- 测试：DshEventMapperTest 更新具名忽略目录（start/summary 移出）+ 新增 4 用例（start 映射/summary 全文映射/summary 缺文本维持忽略/end+error 双发）。

## 验证记录

- `compileDevDebugKotlin` 通过；DshEventMapperTest 全绿（BUILD SUCCESSFUL）。
- 真机 E2E（待项③④⑤ 完成后统一执行）：goal 面板第四钮点击 → 投影回创建表单；/compact 命令 → 进行中分割线 + 摘要展开 + 完成 snackbar。

## 踩坑记录

（暂无——本轮 Kotlin 嵌套注释坑已在 fix-308 journal 记录）
