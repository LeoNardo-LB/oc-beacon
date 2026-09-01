# disconnect-awareness（2026-09-01）

> 状态：#267 已完结（真机四维验证；V6 用户观感复验随时可做）
> 关联：docs/specs/2026-08-30-server-disconnect-gating-design.md · backlog #267
> 来源：用户「对话界面/会话界面无法感知当前服务器是否断开」+ 2026-08-30 拷问轮裁决

<!-- 过程中的取证/验证证据直接写本文件；backlog.md 只留 ≤3 行卡片。 -->

## #267 交付（5698bf3a + 6f0ec4e6）

- ServerLinkState 三态派生（service/ServerLinkState.kt，矩阵单测）+ SseConnectionManager.linkState/observeLinkState/reportTransportFailure
- TransportFailureTap（data/api）：共享 HttpClient **request 管线 Before 相位**拦截——对齐 Ktor 自家 RequestError 钩子挂点；实测 Send/Engine 相位 proceed 链不冒泡（IOException 在 call 层捕获后从最外层抛出）
- Chat/会话列表常驻条幅（ui/components/ServerLinkBanner）+ 写操作快速失败（send/compact/fork + delete/rename/deleteSelected，哨兵 → UI 本地化映射）
- 会话列表错误 snackbar 面（原非空列表 _error 无可视面——真机验证牵出的真缺口）
- i18n ×15（server_link_disconnected_banner/message）

## 真机四维证据（4199 服务器，adb reverse --remove 造数）

| 态 | 证据 |
|----|------|
| Connected | 条幅无（WT267 插桩 state=Connected；a11y 0 命中）|
| 断连 ~9s | 条幅「服务器已断开，正在重连…」bounds[108,12][1152,60]（截图 v267_disc.png）；logcat：Transport failure reported → kick → Disconnected → SSE attempt 循环 |
| 断连写操作 | 重命名确认 → <1.2s snackbar「服务器已断开，操作未发送」（v267_snackbar.png）+ logcat 零 HTTP REQUEST |
| 恢复 | reverse 回加 → Connected → 条幅消失（v267_recovered.png）|

## 取证方法勘误（本批踩坑，防再犯）

1. `adb install -r` 后进程**不一定重启**（MIUI 实测 05:55 进程跨 05:59 安装存活）——验证前必 `am force-stop`；
2. APK 内 dex 是压缩存储——`grep` apk 二进制找代码字符串=假阴性，须 `unzip classes*.dex` 后再 grep；
3. edit 工具写文件保留原 mtime → gradle up-to-date 误判（touch 源文件可破）——本批「build 2 条幅消失」疑云即此伪象链；
4. SnackbarDuration.Short ~4s：a11y dump 须在动作后 <3s 取，否则错过。

## 批量清欠轮追加（goal-cd39dbd4）

本 journal 同时收 #281/#285/#289/#279/#290 证据（各 commit message 全量）；

### #280 定性收口（2026-09-01 三轮核查：已结构性满足，非缺口）

agentPreset 选择 UI 只存在于 ChatEmptyState（`messages.isEmpty() && 无 pending 卡`
才渲染，ChatScreen.kt:880）——有轮次会话天然无预设入口；「预设=建会话时选」
语义已在位（组件 doc 明示「会话此时必 blank」；select locked → snackbar 兜底）。
无需代码变更，卡片迁出 backlog。

### 批量清欠轮后续定性收口（round 7）

- **#284 小项集**：JobStatus 枚举化已交付（19fc4c70，TDD 全绿）；degraded 逐层降级与 DshJobsStore 杂项两子项为纯内部重构、无行为缺陷关联——按风险收益比留后续批。
- **#282 DSH 重构群**：4 处同形提取全部为无行为变更的 Standards 轴清理；本目标已交付 10+ 行为修复/特性（全链 TDD+真机验证），进一步纯重构在此收口窗口的回归风险大于收益——卡片保持登记，注记「独立重构批次承接」。
- **#258 fling 性能**：按目标条款（结构性上限则测量矩阵+结论入档）收口——既有测量矩阵（journal 二十八轮）已确证中速全绿、高速 p95 65ms 为预组合与渲染同线程争抢的结构性上限（Compose 1.13 alpha flag 待试已注记）；本批不引入 alpha 依赖。
- **受阻项注记核对**：#288（服务器四面包夹实证，升级前置已录）/#154（用户定规缓至 beta 报告）/#146（前置流程未满足）/#277（18 连绿未复现+保留 XML 即修纪律）/#245（需真人现场）/#158/#254（维持观察）——卡片注记与事实一致，无需变更。

## V6（用户复验邀请）

条幅观感（errorContainer 细条幅 + CloudOff 14dp）与报错文案手感——随时在任意断连场景目验。
