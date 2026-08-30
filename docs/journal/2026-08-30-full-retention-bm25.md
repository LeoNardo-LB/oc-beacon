# full-retention-bm25（2026-08-30）

> 状态：**完结**（2026-08-31 Q6c 综合实机 E2E 收官 + 主会话交叉验证通过；验收策略=自动化/实机证据即验收，用户 2026-08-31 授权）
> 关联：docs/specs/2026-08-30-full-retention-bm25-search-design.md · backlog #271/#272
> 来源：用户指令「开始吧，看看安卓端主流 BM25 技术栈选一个合适的」+ 拷问轮 12 题裁决

<!-- 过程中的取证/验证证据直接写本文件；backlog.md 只留 ≤3 行卡片。 -->

## 零轮 · BM25 技术选型（用户要求调研后选型）

- 候选对比：SQLite FTS5（bm25 内建）/ Room @Fts4（无内建 bm25）/ requery sqlite-android（全 API FTS5 但 ~7MB native）/ Lucene-android（重）/ 自研（否）
- **选型：FTS5 虚拟表 + 内建 bm25() + unicode61 单字分词**。约束：系统 SQLite 仅 API 30+ 编译 FTS5 模块（minSdk 26）→ 运行时 `CREATE VIRTUAL TABLE IF NOT EXISTS` 探测，失败标记不可用 → **LIKE 降级检索**（个人 app 实际设备均为现代小米，降级仅安全网）
- 索引维护走 **MessageStore 应用层**（非 DB 触发器）：热表 prune 不得误删 FTS 行（冷数据保持可搜）；删会话路径显式级联清理

## 一轮 · #272 FTS 后端（ContentSearch.kt）

- `MessageFtsIndex` @Singleton：ensureAvailable（探测+幂等建表）/ indexTextParts（按 partId 先删后插）/ clearSession / search（FTS5 bm25+snippet + 动态 WHERE 过滤会话/角色/时间）
- MessageStore 三写路径钩子：upsertMessages / upsertInTransaction（REST_AUTHORITY 替换）/ clearSession（级联 FTS）；appendPartTexts（delta 路径）暂不入索引——完结 upsert 全量覆盖时补齐（流式期搜索瞬态陈旧，接受）
- 多词语义：逐词短语包裹隐式 AND（防 FTS5 语法注入）
- 编译 ✅

## 二轮 · #271 保留策略（三改动）

- 冷存桶 LRU 淘汰移除：enforceArchiveLimit 函数+调用+ARCHIVE_BUCKET_LIMIT 常量+DAO leastAccessed 删除（全量保留；占用统计+手动清理兜底）
- reasoning 落库截断取消：MessageStore when 分支删除 + truncateReasoningIfNeeded 函数删除 + 其 2 测试删除
- 工具输出维持 500 预览（用户裁决 c：DB 体积大头 + 不入索引 + 服务器重拉兜底）
- 顺带清偿：MessageStoreTest 匿名 DAO 缺 #266 新增的 insertMessagesIfAbsent 成员——增量编译长期掩盖，同包变动触发全量重编译才暴露，补 override

## 三轮 · #271 同步基建

- session_sync_state v5 表（SessionSyncEntity/SessionSyncDao/MIGRATION_4_5 注册 DatabaseModule）
- HistorySyncManager @Singleton：状态机 none/syncing/synced/failed + 单会话单 flight + 全局顺序 + 页间 150ms 限速 + busy 让位（上限 10min）+ onSessionDeleted 级联（取消+清状态行）
- drain 触发接线：ChatViewModel init loadSession 后 requestSync（注入构造 + 6 个 ChatViewModel*Test 构造点同步补参——由被中断代理完成）
- 删会话级联：EventDispatcher SessionDeleted 分支经 Provider<HistorySyncManager> 调 onSessionDeleted（热表/冷存/FTS 清 + 状态行清）
- 编译 ✅

## 四轮 · UI 与剩余实现（拆分实施：中断代理产物保留 + 主会话/后续代理补齐）

- 长按菜单同步详情区（SessionRow SessionDetailsDialog + SessionTreeList 透传 + 21 个英文 key）——由被中断代理完成，编译 ✅，已保留
- 剩余：搜索框改造（SessionListViewModel contentHits + SessionListScreen 聚合区）·设置页存储统计+手动清理·会话删除 sync 级联·i18n 14 语言·HistorySyncManager 单测——委派代理补齐中（报告后本 journal 补证据）

## 五轮 · UI 与剩余实现完成（代理 a9da8275 + 主会话补齐）

- 被中断代理已完成：长按菜单同步详情区（SessionRow SessionDetailsDialog + SessionTreeList 透传 + 21 个英文 key）、drain 触发接线（ChatViewModel 注入 + 6 个 ChatViewModel*Test 构造补参）——编译 ✅ 已保留
- 主会话补齐：搜索框改造（SessionListViewModel 注入 MessageFtsIndex + setSearchQuery 防抖触发 FTS + contentHits StateFlow；SessionListScreen 搜索词非空时「内容命中」聚合区——按会话分组+条数+snippet，点击跳转会话）
- 删会话级联：EventDispatcher SessionDeleted 分支经 Provider<HistorySyncManager> 调 onSessionDeleted（取消 drain + messageStore.clearSession[含 FTS] + 状态行清），Provider 破环
- 设置页「存储占用」区（代理 a9da8275）：StorageSection 统计卡（桶数/条数/大小 formatArchiveBytes）+ 清理按钮 + ClearArchiveConfirmDialog + snackbar；SettingsViewModel 注入 ArchiveBucketDao（触发器刷新模式）
- i18n（代理 a9da8275）：26 keys × 14 语言（占位符保留、fr/it 单引号转义）；i18n-check PASSED（698 keys × 14 languages）
- 交叉验证（主会话亲跑）：全量 2184 用例 0 失败（含 HistorySyncManager 4 例：synced 幂等/正常 drain/失败标记/取消回退）+ assembleDevDebug + pm install Success

## V3 实机走查（进行中，代理 0aedd62c）

走查项：drain 状态转换（长按详情）/BM25 内容搜索命中与跳转/标题内容聚合/reasoning 全文完整性/存储统计展示/0 FATAL 冒烟。结果回报后补记。

## 七轮 · V3 复验与两轮修复循环

**V3 首验（代理 0aedd62c，构建 54cbc555）**：6 项中 4 过（BM25 搜索限 LIKE 降级/聚合/reasoning 全文/存储统计空态/冒烟），2 失败：①长按菜单同步状态恒「未同步」+手动按钮 no-op——根因=被中断代理只做了 SessionRow/TreeList 侧，SessionListScreen→TreeList 透传与 ViewModel 注入断链；②FTS5 建表静默失败（`no such module: fts5`）——**小米 HyperOS 系统 SQLite 未编译 fts5 模块**（SDK 36 亦然，「API 30+ 即有」预设被推翻），LIKE 降级成为永久路径。

**修复 round-A（同步接线）**：SessionListViewModel 注入 HistorySyncManager 并暴露 syncStates/requestHistorySync/cancelHistorySync；SessionListScreen collect 后透传 SessionTreeList。复验通过：自动 drain→对话框「已同步+上次同步 00:35:06」（秒级吻合）；手动触发五险一金 170 msgs/5 pages→已同步（dialog-sync-fix.png/dialog-sync-manual-fix.png）。

**修复 round-B（FTS5 真路径）**：DatabaseModule openHelperFactory 换捆绑 SQLite（JitPack com.github.requery:sqlite-android:3.45.0，自带 fts5 模块，APK +6.7MB 用户裁决接受）+ 建表后一次性回填（cached_parts text part 全量→FTS 1083 行，从未打开会话亦在索引）+ ensureAvailable 诊断日志。复验：logcat「FTS5 virtual table ready (backfilled)」、0 条 unavailable、DB message_fts+5 影子表存在。

**修复 round-C（BM25 真路径暴露的第三 bug）**：ContentSearch SELECT `bm25(message_fts)` 未起 `AS score` 别名而 ORDER BY score 引用之 → `no such column: score` 被 runCatching 吞 → 内容命中区消失（上构建 LIKE 尚能出 5+4 条）。修复：`AS score` 别名 + 检索失败日志（VM onFailure + 0 命中 debug 日志）。宿主机同库验证：esbuild 命中 5 条、snippet [esbuild] 形态、bm25 -7.70→-2.90 升序。装机终验中。

**附登记**：会话列表滚 ~15 行后 loadMore 不续页（约 35 会话不可达，V3 代理观察，疑似存量问题与本期无关）——待登记卡。
## 八轮 · 终验通过（构建 vc1788107988）

- **BM25 真路径全通**：esbuild「消息匹配」20 条（snippet [esbuild] FTS 方括号形态，rank 升序 -7.87→-2.99）；dedup 双分组 16+10 条（rank -9.44→-4.30）；「content search failed」0 条
- 计数较复验轮升高（5→20/16+10）系捆绑 SQLite 首启全量回填更全（message_fts 4468 行 vs 1083），UI 与 DB 逐行一致，非异常
- 三轮总账：首轮（UI 断线失败 + FTS5 失败走 LIKE + 四项通过）→ 复验（接线修复确认，暴露 ORDER BY score 无别名第三 bug）→ 终验（别名修复，BM25 真路径全面生效）
- 证据：/tmp/v3-retention-search/FINDINGS-FINAL.md + search-bm25-fixed(.dedup).png + fv-search-*.xml

**剩余**：V6 人工验收清单交付用户（卡片保持 [~] 待验收）；#273 loadMore 观察已另立卡

## 九轮 · Q6c 搜索过滤 UI（commit 211f2f0e，22 文件）

- ContentSearchFilterValues 稳定 token（user/assistant/7d/30d）UI↔过滤共享；VM searchRole/searchTimeRange StateFlow + 切换即时重查（复用 contentSearchJob）+ buildContentSearchFilter（timeFrom=now-7d/30d）
- ContentSearchFilterChips（新组件）：角色三枚+时间三枚 FilterChip 单选两行横向滚；**过滤激活 0 命中保留内容区**（否则切不回「全部」）+ search_content_no_hits 空态
- i18n 7 keys×15 语言（705 keys 全一致）；顺带修复 3 个 VM 测试缺失构造参（基线编译红）+ 新增过滤单测 6 例

## 十轮 · 综合实机 E2E 十项（代理 0c14b09c，真机 e69a99d8，vc1788109238；证据 /tmp/v3-retention-search/q6c-e2e/，60 截图）

| # | 项 | 判定 | 要点 |
|---|---|---|---|
| 1 | drain 自动 | ✅ | DB 无 sync 行→logcat `synced 36 msgs in 2 pages`（与服务器 API 计数一致）→synced+lastSyncAt |
| 2 | drain 手动 | ✅ | 对话框 未同步→已同步+01:12:08 |
| 3 | 取消 | ⚠️有限 | 剩余会话 drain 均 <1s 三次竞态未赢；取消语义由单测「取消后状态回未同步」覆盖 |
| 4 | BM25 搜索 | ✅ | [dsh] snippet + 2 会话分组（50/45 条）+ 点击跳转正文 |
| 5 | 过滤 | ✅角色/⏸时间 | 用户 13 条 vs AI 83 条（snippet 角色正确）；时间 7d/30d 命中集不变——DB 实证缓存全部 ≤5.4 天旧，非缺陷；timeFrom 计算有单测 |
| 6 | 双区聚合 | ✅ | 标题区+内容区共存 |
| 7 | 删会话级联 | ✅ | 新会话发消息→命中→删除→再搜无命中→DB fts/sync/hot/桶全 0 |
| 8 | 存储统计 | ✅空态 | 「暂无归档」+bucketCount=0；三行统计仅 >0 桶渲染（设计内） |
| 9 | 清理按钮 | ⛔设计隐藏 | 0 桶入口隐藏（StorageSection.kt:78），零点击零数据损失 |
| 10 | 0 FATAL | ✅ | ~67k 行 5 段 logcat 全程 0 崩溃 |

**主会话交叉验证（2026-08-31）**：testDevDebugUnitTest --rerun 独立重跑 BUILD SUCCESSFUL（1m13s）；i18n 14 locale key 集与 base 逐一致（701 string keys）；树净（两代理提交 22abc52a/4f8aa53f/211f2f0e 均在）。

## 十一轮 · #273 loadMore 诊断结论（只读，另立修复范围）

- **走查初判颠覆**：「~35 会话不可达」非分页 bug——35 个全为 parentID 非空 V2 子会话，SessionListStateBuilder.kt:41 按设计仅渲染顶级（设备枚举 15/50 与 parentID 统计吻合）。
- **真实潜伏 bug（>50 顶级会话爆发）**：V2 opaque cursor 被 V2ApiClient.listSessions 丢弃（`val (items, _)`）→VM 伪造 `sessions.last().id` 当 cursor→服务器 400 InvalidCursorError（curl+服务器日志+设备 Ktor 三重实证）→400 体无 data 被 unwrapList 静默解析空→hasMorePages=false 永久静默死亡。
- 处置：#273 卡片改写为 cursor 修复卡（诊断全文 /tmp/v3-retention-search/q6c-e2e/273-loadmore-diagnosis.md）。

## 结册 · #271/#272 关单（2026-08-31）

- **#271 本地全量保留策略**：✅ 冷存桶无上限+统计/清理 UI、reasoning 全量、工具输出 500 预览、HistorySyncManager（自动/手动/取消/级联）、同步状态唯一展示面=长按菜单。E2E #1/2/3/7/8/9/10 覆盖。
- **#272 BM25 对话内容检索**：✅ FTS5+bm25 真路径（捆绑 SQLite）+LIKE 降级安全网、snippet/分组/跳转、角色/时间过滤、双区聚合。E2E #4/5/6 覆盖。
- 验收依据：用户 2026-08-31 授权「能自动化/实机验证的全由 Agent 自动验证并作为验收依据，人工验收压到最低」——上述自动化+实机证据即验收，卡片关单迁册（本节即迁册记录）。
- 人工残余（不阻塞关单，用户后续随手可验）：时间过滤在 >30 天数据环境下的实际收窄效果；>1s drain 场景的取消按钮肉眼体验。
