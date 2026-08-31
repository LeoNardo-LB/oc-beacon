# 2026-09-01 DSH 定位跳转·卡片缺口·QueueDock 批次

> 批次模式：委派实现（快速定位/定位卡/EventCard 前向箭头、设置页两新行、卡片缺口四件、QueueDock 排队条）。
> 基线 master@2f63a59e（2467/0）→ 本批 5 commit。

## 证据链（Task 1 根因——DSH id 形态 vs V1）

- 活体取证：test-lab 会话（session-320c5915-…，标题 Test Lab Initialization）history 14546 事件，开头 user/message seq=7..10（[test-lab] init / <system-reminder> 注入）；应用 Room 首条消息为 seq-326（assistant），开头 user 消息被 100 页防呆截断挡在 Room 外；快速定位列表因此缺目标（或目标仅记忆窗口可见）
- DshApiClient.listMessages:488 `before?.toLongOrNull()?.let { put("beforeSeq", it) }`——V1 base64 游标被静默丢弃（与 DshReconciler「beforeSeq 数字排他」契约不符）
- PaginationCursorPolicyFactory 按 `getApiVersion(serverId).isV2` 二分→DSH（ApiVersion=V1 缺省）落 V1CursorPolicy→encode(id,time) 游标→丢
- MessageDao messagesBefore/After 谓词 `id < :beforeId`（ULID 字典序前提）+ ORDER BY created DESC,id DESC——DSH seq-N 字符串序≠数字序（seq-9 > seq-4096）→ older/newer 窗口漏单错位
- DSH 工具卡无 metadata 槽→extractToolSubagentSessionId 恒 null（定位卡/前向箭头目标不可解析）

## Commit 清单与测试数字

| commit | 内容 | 数字 |
|--------|------|------|
| ee51015d | fix: DSH 定位跳转失效根因修复（DshCursorPolicy 数字 beforeSeq + created 窗口 + fetchAllMessages 防呆 400 页/2 万 + extractToolSubagentSessionId input 回退） | PaginationCursorPolicyTest +7（含 Dsh 5）、MessageStoreTest +4、ExtractToolSubagentSessionIdTest +4 |
| f9298c89 | fix: 服务器设置页两新行展开模式对齐既有区块（SettingsSectionHeader+内联 RadioButton；零新增 i18n） | compile 通过 |
| 8e3c577d | feat: 卡片缺口四件（fork 卡复用/DSH workflow 降级卡/file·image 块→Part.File/Shell 卡接 session/jobs） | ResolverTest +3、DshEventMapperTest +12（workflow 5/file 3/queue 略） |
| f2df106c | feat: QueueDock 排队条（session/queue 映射+存储+RPC+ChatScreenBottomBar 上方 UI；i18n 6 key×15 语言） | DshQueueStoreTest +3、mapper queue 4 例、既有 2 例签名更新 |
| 7e4e367e | docs: backlog #287/#288（DSH 附件拉取接线、workflow 阶段卡——Task 3 顺带发现只登记） | backlog-check PASSED |

## 验证

- 全量回归：`./gradlew :app:testDevDebugUnitTest --rerun` = **2495/0/0**（基线 2467 + 28 新增）
- 编译：compileDevDebugKotlin 多次全绿；assembleDevDebug BUILD SUCCESSFUL
- i18n-check：PASSED（765 keys × 14 语言一致，含 6 新 queue_* key）
- backlog-check：通过
- 真机验证（回退 V6 用户清单）：归委派方——QueueDock 增删改/steer 交互、DSH 定位跳转蒙版落位、设置页两新行展开、workflow 降级卡、Shell 卡 jobs 展示