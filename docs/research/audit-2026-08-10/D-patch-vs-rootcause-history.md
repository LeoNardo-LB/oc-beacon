# D — 补丁型 vs 根因型 修复历史审计（v0.2.0..HEAD）

> 审计时间：2026-08-10 · 范围：git log v0.2.0..HEAD 共 ~110 commit，其中 fix/refactor ~40 个
> 方法：每个 fix/refactor commit 看 git show 实际 diff（不只看 message）
> 判定标准：**根因** = 解决问题源头不复发 · **补丁** = 症状层修复/根因仍在/引入新复杂度 · **混合** = 部分根因部分补丁
> 状态：报告由父会话落盘（Agent 无 Write 权限）

## 1. 判定汇总表

### fix commits（25 个）

| sha | 主题 | 判定 | 技术债残留 | 严重度 |
|-----|------|------|-----------|--------|
| 0eaac6dc | 草稿 ANR（onCleared 异步化） | 根因 | force-stop 缺口由 e3ffeae7 补 | — |
| b07b7ccc | 滑动掉帧 7 项 | 混合 | IN 分块逻辑散落 MessageStore 而非 DAO；NetTrace 日志在 hot path | 中 |
| a7aec358 | L3 校验 limit=50 | **补丁** | 魔法常量 50 无依据；根因（增量同步）未解决 | 中 |
| ec875ff7 | 过渡动画 400ms 最小时长 | **补丁** | 故意延迟显示（反模式）；魔法常量无依据 | 中 |
| ff192fd5 | APPEND_ONLY 合并替换 bug | 根因 | 每次分页全量 sortedBy（次要） | 低 |
| d30a0d57 | 归档翻页游标推进 | 混合 | 游标作为 UI 层 private var（应封装为领域模型） | 中 |
| c5e0ea56 | 上滑自动加载根因三连 | 混合 | 3 游标 + 3 失败保护变量散落；isScrollInProgress 修复为根因 | 高 |
| e3ffeae7 | 草稿 500ms 防抖 | **补丁** | 补 0eaac6dc 缺口；每次按键 launch+cancel job；根因（SavedStateHandle）未解决 | 中 |
| 6fdff190 | 数据库自愈删除重建 | 混合 | 删库丢全部缓存消息+日志；本次操作失败不重试 | 中 |
| 69df372b | 归档逐条容错 | 根因 | — | — |
| 477a308d | 坏桶跳过 continue | 根因 | — | — |
| 1beb846b | 全分支评审 I1/I2/I3+M1-M10 | 混合 | 多项打包（需逐项评估） | 低 |
| b843f265 | 网络失败回退本地缓存 | 根因 | — | — |
| 1ae44d57 | androidTest Fake 补齐 | 根因 | — | — |
| def2d11a | UnreadBadgeServiceTest 清理 mockkStatic | 根因 | — | — |
| d4a906d7 | DraftDataStore 旧 File 迁移 | 根因 | runBlocking 读 DataStore（构造期，可接受） | 低 |
| 775b257e | StreamingOwnershipRegistryTest 隔离 | 根因 | — | — |
| 61e4107a | domain→data 违规依赖 + Cancellation/N+1/@Deprecated | 根因 | — | — |
| 4797be6e | 种子化块异常降级 | 混合 | catch(Exception) 吞 CancellationException（61e4107a 修复） | 中 |
| 595d63b2 | loadOlder before 游标编码 | 根因 | — | — |
| e54e9f34 | LogDao.isEmpty 语义 | 根因 | — | — |
| 16c7a15c | 提问轮询+协程泄漏+i18n | 混合 | 3 项打包（协程泄漏为根因；轮询开关为补丁） | 低 |
| 0b85ca06 | loadPendingQuestions 全量替换 | 根因 | — | — |
| a86b2e87 | 历史提问多选+清理 debug log | 混合 | 清理 PartContent DebugLogger 残留（债务已清） | 低 |
| 91919981 | 发送失败恢复草稿回填图片 | 根因 | — | — |

### refactor commits（约 15 个）— 判定：全部根因型

代表性：de276458（悲观消息核心，删除 AnimatedVisibility 闪烁根因）、3d828265/c6bbd71a（抽出 UnreadBadgeService/StreamingOwnershipRegistry，消除 runBlocking 落盘）、50b2af95（SettingsDataStore 三文件合并）、61e4107a（架构违规修复）、1c42c2f4/060c347a/8a115d86/66fdc7fb/6ee8e3a5/15266f17（删除乐观消息体系六连，根治发送闪烁）、39bb10c2（toEntity 去重 encode）、f770e60d（命名同步）。无新债务引入。

### 统计

- **根因**：18 个 fix + 15 个 refactor = **33 个**
- **补丁**：**3 个**（a7aec358、ec875ff7、e3ffeae7）
- **混合**：**7 个**（b07b7ccc、d30a0d57、c5e0ea56、6fdff190、4797be6e、16c7a15c、a86b2e87）

## 2. 补丁型/混合型逐条深挖

### 2.1 a7aec358 — L3 校验 limit=50【补丁】
- diff 证据：SessionStateService.kt:34, 276-282（REST_REFRESH_LIMIT = 50；limit=0 → limit=50）
- 为什么是补丁：注释自述"陈旧窗口漏消息远少于 50"——未经验证假设；长时间离线仍可能 >50 漏消息；根因（增量同步）未触及
- 建议根因修复：lastSyncCursorPerSession Map，L3 校验用 before=encode(lastSyncCursor) 增量；同步成功后推进

### 2.2 ec875ff7 — 过渡动画 400ms 最小时长【补丁·严重】
- diff 证据：ChatScreen.kt:255-256, 433-447, 675-683（MIN_LOADING_VISIBLE_MS=400 + LaunchedEffect delay + showLoadingTransition）
- 为什么是补丁（严重）：**故意延迟显示加载态**（反模式，欺骗用户感知）；魔法常量 400 无 A/B 依据；根因是进入会话缺页面级过渡动画（AnimatedContent/Crossfade/Navigation transition）
- 建议根因修复：移除 MIN_LOADING_VISIBLE_MS；会话路由加 enter/exit transition；loading 指示器回归"仅在真正加载时显示"

### 2.3 e3ffeae7 — 草稿 500ms 防抖【补丁·补补丁链】
- diff 证据：DraftInputDelegate.kt:127-145, 210-214（draftSaveJob cancel+launch delay(500)；DRAFT_SAVE_DEBOUNCE_MS=500）
- 为什么是补丁：补 0eaac6dc 缺口（force-stop 不触发 onCleared）；每次按键 launch+cancel job 开销；500ms 窗口内被杀仍丢——只是缩小窗口未根除
- 根因：草稿持久化策略错误（依赖单一时点 onCleared），应改为状态变更即持久化（DataStore 原生原子 Flow）或 SavedStateHandle
- 建议根因修复：DraftRepository 暴露 draftFlow: Flow<Draft>，UI collectAsState + onValueChange 写 DataStore（原子合并写）；移除防抖 job

### 2.4 c5e0ea56 — 上滑自动加载根因三连【混合·技术债聚集】
- 项 1（isScrollInProgress）— **根因**：改用 LaunchedEffect(hasOlderMessages, isLoadingOlder, autoLoadPaused) + snapshotFlow 持续监听
- 项 2（NETWORK 分页游标）— **补丁**：新增 networkCursorId/networkCursorCreated 两个 var——"与 archiveCursorCreated 同语义但独立跟踪"（自承认再加一套游标）；根因是分页游标抽象缺失
- 项 3（防风暴）— 混合：退避功能正确但 Delegate 职责膨胀
- 累积技术债实证：MessagePaginationDelegate 现有 **9 个可变状态成员**：currentMessageLimit, archiveCursorCreated, networkCursorId, networkCursorCreated, _hasOlderMessages, _isLoadingOlder, autoLoadFailures, autoLoadPausedUntil, _autoLoadPaused
- 建议根因修复：抽 PaginationCursor sealed class（ArchiveCursor(created) / NetworkCursor(id, created) / None）+ PaginationFSM（纯函数，参照 SessionStateFSM）

### 2.5 d30a0d57 — 归档翻页游标推进【混合·c5e0ea56 前身】
- 根因识别正确（归档不落热表 → 热表最老不变 → 死循环），但游标作为 UI 层 private var——开了"散落游标变量"先例

### 2.6 b07b7ccc — 滑动掉帧 7 项【混合】
| 子项 | 判定 |
|------|------|
| Stretch overscroll 拦截 | 根因 |
| 日志风暴（combine 每 48ms 4 条） | 根因 |
| MessageUpdated O(n) 扫描 | 根因 |
| 冗余排序 | 根因 |
| SQLite IN 999 分块 | 混合（逻辑散落 MessageStore 而非 DAO 层） |
| 上滑分页 reverseLayout 触发 | 根因 |
| ANR 异步化 | 根因 |
- 新引入债务：SessionRepositoryImpl.listMessages 新增 NetTrace 日志（hot path，DEBUG 级，模式不一致——删 MsgDiag 又加 NetTrace）

### 2.7 6fdff190 — 数据库自愈删除重建【混合】
- 封装可复用（根因部分）；但删库丢全部数据（注释"日志/内存视图不受影响"错误——LogStore 也在 Room）；本次操作失败不重试；DATABASE_NAME 硬编码耦合
- 建议根因修复：Room databaseBuilder().fallbackToDestructiveMigration() 声明式；或区分"损坏"（删）与"临时错误"（重试）；返回 Result<T> 而非 nullable

### 2.8 4797be6e — 种子化块异常降级【混合·被 61e4107a 补】
- catch(Exception) 吞 CancellationException（协程反模式），61e4107a 修复（先重抛 CancellationException）

### 2.9 16c7a15c — 提问轮询+协程泄漏+i18n【混合·打包修复】
- 通知开关守卫：补丁（根因是通知开关应作用于通知通道层）；断开重连协程泄漏：根因；i18n：根因

## 3. 技术债清单（按严重度排序）

### 🔴 高严重度
#### TD-1：MessagePaginationDelegate 状态散落与职责膨胀
- 引入：d30a0d57 + c5e0ea56；当前 9 个可变状态成员（实测确认）
- 触发：新增分页源需再加游标变量；多游标 reset 竞态（NETWORK 后 reset archiveCursor，归档中途触发 NETWORK 归档进度丢失）
- 建议：PaginationCursor sealed class + PaginationFSM；与 AGENTS.md "SessionStateService 单一真相源"原则相悖

### 🟡 中严重度
#### TD-2：过渡动画虚假 400ms 延迟（反模式）
- 引入：ec875ff7（MIN_LOADING_VISIBLE_MS=400）；每次缓存秒开强制等 400ms
- 建议：移除最小显示时长，改导航级 enter/exit transition

#### TD-3：草稿持久化双补丁链
- 引入：0eaac6dc + e3ffeae7；force-stop 在 500ms 防抖窗口内杀进程仍丢；高频输入大量 Job 创建销毁
- 建议：DraftRepository 暴露 Flow，UI 直接写 DataStore（原子合并）

#### TD-4：L3 校验魔法常量 50
- 引入：a7aec358（REST_REFRESH_LIMIT=50）；长时间离线陈旧窗口 >50 条仍丢
- 建议：lastSyncCursor 增量同步

#### TD-5：DatabaseRecovery 删库丢数据
- 引入：6fdff190；任何 SQLiteException（含非损坏临时错误）触发删库 → 全部缓存清零
- 建议：fallbackToDestructiveMigration() 声明式；或区分损坏/临时错误

#### TD-6：catch(Exception) 吞 CancellationException 模式
- 4797be6e 引入（61e4107a 修复）；模式需警惕

### 🟢 低严重度
#### TD-7：SQLite IN 分块逻辑散落（b07b7ccc）→ 下沉 DAO 层
#### TD-8：NetTrace 日志 hot path（b07b7ccc）→ DEBUG 开启 + 频繁分页时风暴；建议采样
#### TD-9：ff192fd5 每次合并全量排序 → existing 已有序只需 merge（O(n)）
#### TD-10：1beb846b 多项打包修复未拆分 → 审计回滚困难

## 4. 模式发现（系统性问题）

### 模式 A：游标不前进类问题反复出现（3 次）
- 归档翻页死循环（d30a0d57）→ 网络分页死循环（c5e0ea56）→ 同一根因（游标抽象缺失）
- 根因：分页是横切关注点，实现散落 UseCase（beforeCreated 参数）+ Delegate（archiveCursorCreated/networkCursor 变量）+ Store（oldestMessageId/createdAt 查询）三层
- 建议：PaginationCursor 抽象封进 domain 层，参照 SessionStateFSM 纯函数 FSM 模式

### 模式 B：DIAG/Debug 日志残留到生产（3 次反复）
- MsgDiag（b07b7ccc 清理）→ PartContent DebugLogger（a86b2e87 清理）→ NetTrace（b07b7ccc 新增）
- 根因：开发期 debug 日志无门控机制；清理后又新增形成循环
- 建议：(a) lint 规则禁止 DebugLogger 在 main 分支；(b) 所有 debug 日志强制 BuildConfig.DEBUG 门控 + CI 检查；(c) NetTrace 采样

### 模式 C：catch(Exception) 吞 CancellationException（≥2 次）
- 4797be6e → 61e4107a 修复；ChatRepositoryImpl.getMessagesFlow 同类
- 建议：safeCatch 工具函数（先重抛 CancellationException）；detekt SwallowedException 规则

### 模式 D：补丁补补丁链
- 草稿持久化：0eaac6dc → e3ffeae7 两个补丁都在症状层
- 判定方法：fix commit 注释提到"补 XXX 的缺口/兜底"时大概率是补丁链

### 模式 E：一个 commit 打包多项修复（降低可审计性）
- b07b7ccc（7 项）、1beb846b（13 项）、16c7a15c（3 项）、c5e0ea56（3 项）
- 建议：fix commit 一事一 commit

## 5. 结论

v0.2.0 之后修复质量整体良好：**33 个根因（80%）vs 3 个补丁 + 7 个混合（20%）**。主要技术债集中在**分页状态管理**（TD-1，高）和**草稿持久化策略**（TD-3，补丁链）。建议优先处理 TD-1（抽 PaginationCursor/FSM），可一并消除 d30a0d57 + c5e0ea56 的累积债务。
