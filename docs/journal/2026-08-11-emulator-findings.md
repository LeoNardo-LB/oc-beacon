# 2026-08-11 模拟器实测批次（#56 联动发现）
> 状态：全部完结
> 迁移：2026-08-20 自 backlog.md 原文迁入（spec/journal 分离批次；原文逐字保留，未压缩）


- [x] **#72 归档桶内分页缺陷（桶级游标 vs 消息级游标，桶内剩余消息永久读不出）** `data` `performance`
  - 问题：2026-08-11 #56 复测（模拟器，归档 88 条/1 桶 + 热表 30 条）发现——`MessageStore.loadArchivedRange`（MessageStore.kt:269-300）按 `bucketEnd < beforeCreated` 查桶（桶级比较），但游标推进到**消息级** created；第 2 次翻页用消息级 created 查桶 → 桶 bucketEnd > 游标 → 判读尽 → **桶内剩余 58 条永久读不出**（数据证据：翻页只释放了 30/88 条归档）
  - 修复：游标推进到**桶边界**（bucketEnd）而非桶内消息 created；或 loadArchivedRange 支持桶内消息级游标（beforeCreated 内再过滤桶内消息）
  - 工时：~0.5d | 难度：中 | 涉及：MessageStore.loadArchivedRange + MessagePaginationDelegate 游标推进（PaginationFSM.Archive）
  - 来源：模拟器实测（#56 复测报告）

- [x] **#73 首次网络翻页 cursor 格式不兼容（CursorCodec {"id","time"} vs 服务器 {"id","order","direction"}）** `data` `sse`
  - 问题：2026-08-11 #56 复测发现——首次网络翻页（无 serverCursor）回落 CursorCodec 格式，V2 服务器**返回 0 条**（非注释预期的"忽略返回最新"）；服务器 195 条消息中更早的 ~77 条未被加载（热表 30 + 归档 88 = 118，服务器 195 → 差 77 条读不到）
  - 修复：进入会话时保存服务器首次响应的 cursor.next（loadMessagesForSession 的 MessagePage.nextCursor）作为首翻游标；或首次网络翻页不带 cursor（拿最新 30 条 + cursor.next）建立边界后再透传
  - 工时：~0.5d | 难度：中 | 涉及：MessagePaginationUseCase + MessagePaginationDelegate
  - 来源：模拟器实测（#56 复测报告）

- [x] **#74 V2 SSE 连接不稳定（Software caused connection abort 反复断连）** `sse` `stability`
  - 问题：2026-08-11 Diagnostics 持久化日志（logs 表）显示 16:21-16:33 期间 3 次 `[TestServer] SSE connection failed: Software caused connection abort` + `SSE stream error`——App SSE 连接反复断连；断连窗口内的 admitted/step 事件丢失 → 用户消息/流式更新延迟（"刷新才显示"的深层关联因素之一）；本次启动（17:03，新 APK）后未复发，但断连重连机制无日志记录断连原因/重连间隔
  - 修复方向：SseConnectionManager 记录断连原因 + 重连间隔日志；区分服务器主动断开（正常）/网络异常；断连期间消息播种兜底（REST 增量）
  - 工时：~0.5d | 难度：中 | 涉及：SseConnectionManager / SseClientV2

- [x] **#75 V2 session.instructions.updated 解析失败（data 为数组的事件类型解析缺口）** `sse` `compat`
  - 问题：2026-08-11 Diagnostics 日志 5 次 `V2 parse error: session.instructions.updated`（15:42-16:07）——parseV2Event 对 `data` 为数组的事件回退顶层字段路径，但 instructions.updated 顶层只有 metadata（无 type 所需字段）→ 后续解析抛异常被记为 ERROR；同时 `session.created` 解析失败（16:03，Kotlin reflection 序列化异常）
  - 修复方向：instructions.updated 显式处理（metadata 提取或忽略）；session.created 序列化调查（Kotlin reflection 异常——可能与 Json 配置/多态有关）
  - 工时：~0.5d | 难度：低 | 涉及：V2SseMapper / SseClientV2.parseV2Event

- [x] **#82 跨页跳转 loadAround 后最新消息丢失（UI 与服务器不同步）** `data` `sse`
  - 问题：2026-08-13 跳转定位全面验证（模拟器）发现——发送消息（11:13 hello，服务器端确认存在：`GET /api/session/{id}/message` 最后一条 assistant 回复 11:15，会话 updated=11:13）后执行跨页跳转（Q5 → loadAround older=30 newer=30）→ 跳转完成后滚回列表最新位置，UI 仅显示 10:55 的消息（hello 及回复消失）——客户端内存/数据库窗口与服务器不一致；SSE 连接正常（V2 event 持续收到，含其他会话事件）
  - 影响：跨页跳转（loadAround 重载窗口）后最新消息可能丢失显示——用户看不到刚发的消息/回复（重启应用或重新进入会话可能恢复）；与 #76 冷启动 seed 顺序问题同属"窗口/归并"类
  - **2026-08-13 修复（根因 + 代码完成）**：与 #76 同类——`loadAroundFromLocal` 的 older（`messagesBefore` 查询 `ORDER BY created DESC`——降序）与 newer（ASC）混合后破坏 `mergeSortedMessages` 升序前提（MessageEventHandlerMergeSortedTest 声明的合法输入约束）→ 归并游标错乱 → 内存热视图丢消息。修复：loadAround 两分支（本地/服务器）合并前统一 `sortedBy { it.info.time.created }` 升序化（commit 3cb55ad8）。**验证状态**：assembleDevDebug 编译通过；单测受 replicant 环境 flavor 歧义限制未本地跑；模拟器（无 DISPLAY）无法行为复测——待环境恢复补跑单测 + 模拟器复现（跨页跳转 → 滚到底 → 最新消息在）
  - 工时：~0.5d | 难度：中 | 涉及：MessagePaginationDelegate（loadAround 两分支）
  - 来源：2026-08-13 跳转定位全面验证（模拟器，dev 最新代码）

- [x] **#76 冷启动 seed 消息顺序降序 vs mergeSortedMessages 升序前提（REST refresh 丢本地独有消息）** `data` `bug`
  - 问题：2026-08-11 synthetic 卡片实测发现——`MessageDao.observeMessages` 返回 `ORDER BY created DESC`（降序），而 `ChatRepositoryImpl.getMessagesFlow` 冷启动 seed 直接喂给 `upsertMessages(APPEND_ONLY)` → `mergeSortedMessages` 两路归并**前提要求升序**（MessageEventHandler.kt:408-410）→ 合并结果乱序/异常；随后 L3 REST refresh（REST_AUTHORITY）再次用降序 existing 归并 → **服务器上不存在的本地独有消息（如本地注入/服务器已删除）被丢弃**（实测：seed 14 条 → REST refresh 后 UI 仅 12 条，2 条注入 synthetic 消失）
  - 影响：低概率但真实——本地缓存与服务器不一致（服务器删除/回滚、本地注入）时消息丢失；日常场景（服务器权威数据）被掩盖
  - 修复：seed 前 `sortedBy { it.time.created }` 升序化（或 MessageDao 提供升序查询）；合并后断言有序
  - 工时：~0.5h | 难度：低 | 涉及：ChatRepositoryImpl.getMessagesFlow（seed 路径）
  - 来源：2026-08-11 synthetic 卡片实测
  - **2026-08-11 完成**：ChatRepositoryImpl.getMessagesFlow seed 前升序化（2e326ff1）；实测数据库 completed 全量持久化、UI 正常
