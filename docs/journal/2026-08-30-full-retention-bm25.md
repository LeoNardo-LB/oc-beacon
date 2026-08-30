# full-retention-bm25（2026-08-30）

> 状态：功能实现完毕，V3 实机走查进行中（交叉验证：全量 2184/0 · i18n-check PASSED · 装机 Success）
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

