# 2026-08-08 提问组件改进批次
> 状态：全部完结
> 迁移：2026-08-20 自 backlog.md 原文迁入（spec/journal 分离批次；原文逐字保留，未压缩）

来源：用户口头反馈（2026-08-08）。

- [x] **#26 提问单选/复选控件语义纠正** `ui`
  - **2026-08-13 修复完成 + V1 实测通过 ✅**：单选互斥 bug 根因=isSingle 仅覆盖单问题场景，多问题中单选题目走 toggle 多选分支 → 修复：onOptionClick 按每道题 multiple 判断；头部紧凑化（16dp 图标 + labelLarge + bodyMedium 摘要）。V1 实测：Q1 点 Red 再点 Blue → Red 自动取消（仅 1 选中）；Q2 多选 Apple+Banana 保持双选；无崩溃
  - 原问题：用户反馈"单选框、复选框全部都由单选框组件来承担职责"——多选问题应显示复选框，单选才用单选框
  - 问题：用户反馈"单选框、复选框全部都由单选框组件来承担职责"——多选问题应显示复选框，单选才用单选框
  - 现状（代码已确认）：QuestionOptionRows（QuestionPartContent.kt:259）已按 `question.multiple` 分支渲染 CheckBox/RadioButton 图标；但需验证 `multiple` 字段在 SSE 事件 → QuestionParser → UI 全链路传递是否可靠（服务器未传 / 解析丢失时可能全部退化为单选样式）；历史视图 CollapsibleQuestionPart（QuestionPartContent.kt:135）答案固定用 RadioButtonChecked 图标，多选答案也应显示 CheckBox 样式
  - 调研方向：先确认真实渲染路径（活动提问走 QuestionCard + QuestionPagerView，历史走 CollapsibleQuestionPart / QuestionExpandedOptions），定位 multiple 丢失点；再按 M3 语义修正图标
  - 工时：~2h | 难度：中 | 涉及：QuestionPartContent.kt / QuestionCard.kt / QuestionParser
  - **2026-08-08 代码完成（待人工验证）**：QuestionParser 新增 `ParsedQuestion.isMultiple`（JSON multiple 3 解析点 + 7 测试，commit 04b3fb33）；CollapsibleQuestionPart 历史答案图标按 isMultiple 分支 CheckBox/RadioButtonChecked + PartContent 调试日志清理（commit a86b2e87）；编译 ✅ 全量单测 ✅ i18n ✅；⚠️ 真机验证待用户：活动/历史多选显示复选框

- [x] **#27 多问题提问"下一步/提交"流程** `ui`
  - **2026-08-13 用户验收 ✅**：Next/Submit 切换正确、未答完弹窗正常（"第 X 个问题没有回答"→可继续提交）、单选点选可取消——全部通过
  - 问题：多问题场景（questions.size > 1）右下角直接是提交按钮；应改为非最后一个问题时显示"下一步"，点击跳转到下一个问题，最后一个问题才显示"提交"
  - 现状：QuestionCard.kt:207-232 底部 Row 只有 Dismiss + Submit（`!isSingle` 时显示）；QuestionPagerView 已有 TabRow + HorizontalPager（Q1/Q2/Q3 标签可点击跳转），下一步按钮可复用 `pagerState.animateScrollToPage`
  - 方案：QuestionCard 增加"当前页"状态，底部按钮随页变化：非末页 → "下一步"（跳页），末页 → "提交"（onSubmit）；Dismiss 保持
  - 工时：~1.5h | 难度：中 | 涉及：QuestionCard.kt / QuestionPagerView.kt（可能）+ i18n（下一步文案 15 语言）
  - **2026-08-08 代码完成（待人工验证）**：QuestionCard 三按钮体系（忽略/下一步/提交，末页置灰）+ 未答完提交弹窗（"第 X 个问题没有回答" → 继续提交）+ 单选点选不立即提交可取消选中；QuestionPagerView page-aware 签名；纯函数 `unansweredQuestionIndexes` + 4 测试；i18n 新增 4 键（15 语言，commit 10757799）；编译 ✅ 全量单测 ✅ i18n ✅；⚠️ 真机验证待用户：三按钮流程/弹窗/单选可取消

- [x] **新增 A：会话列表"待回答"标记 + 提问通知 REST 兜底——2026-08-18 模拟器验证：功能有效但发现并修复两个 P1 兜底缺陷；2026-08-19 通知形态代验收 ✅** `ui` `session` `sse`
  - 问题：有提问的会话在列表无任何提示；SSE 不推 question 事件时通知不可达（无兜底链路）
  - **2026-08-08 代码完成（待人工验证）**：SessionRow 增加 HelpOutline 图标 + "Pending answer" 标记（i18n 15 语言，commit a989890e）；OpenCodeConnectionService 新增 30s REST 轮询兜底（`notifyPendingQuestionsFromREST` + `diffNewQuestionIds` 纯函数 + 3 测试，commit 1d1b2a75）；编译 ✅ 全量单测 ✅ i18n ✅；⚠️ 真机验证待用户：列表标记显示/通知弹出
  - **2026-08-18 模拟器验证（SSE 路径 ✅ + 发现 REST 兜底两缺陷已修）**：
    - ✅ SSE 路径：agent 调 question → 列表行 "Pending answer" 标记正常显示（Untitled session 实证）；通知日志 `Question asked for session ses_…` 落库（App logs 表）+ opencode_questions 通知通道（importance=4 声光振动）存在
    - ❌→✅ **缺陷1（轮询永久死亡，P1，已修 32765cf6）**：原 `if (!isConnected) break` 在 connect 后 SSE 握手窗口首轮 tick 即杀死轮询且永不复活——实测启动 12 分钟 form/request **0 次**（对照组 /api/session/active 40 次），服务器端存在 pending form 的会话（E2E-C）列表无标记。修复：轮询生命周期只跟随用户连接意图（disconnect 显式取消兜底），去掉 isConnected 检查
    - ❌→✅ **缺陷2（location 覆盖缺口，P1，已修 32765cf6）**：V2 form/request 按 x-opencode-directory 分 location 返回，不带头只返回 global——项目目录的 pending form 永远查不到（实测 oc-beacon location 的 Favorite Season form）。修复：遍历 global + 全部项目目录（directory 字段缺失回退 canonical——beta-17595 只返回 canonical），10 轮缓存
    - 修复闭环验证：force-stop 后服务器新 form → 冷启动纯 REST 路径 22s 内 form/request 8 次 + 列表 2 行 "Pending answer" 出现；1690 单测全绿
  - **2026-08-19 模拟器代验收（通知形态）✅**：授权 `pm grant POST_NOTIFICATIONS` 后重触发——A1 dumpsys 铁证：`opencode_questions` 通道 mImportance=4 + 声音/闪光/振动全启用；A2 通知栏实拍（ab_11_shade_notif.png + vision）：`OC Beacon Dev` 应用图标 + 标题 **`问题 · 验收测试会话AB`** + 正文 + 时间戳，浅灰圆角卡片形态规范；A3 列表标记 uidump 三行实证（测试会话本行「待回答」bounds [409,605] + 两行 legacy 会话）。注意：首次测试发现模拟器通知权限从未授予（importance=NONE）——环境前置而非 App 缺陷。附带发现（P3 已登记）：通知正文取最后一条用户消息（触发 prompt 原文）而非问题文本本身（AppNotificationManager:379 findLatestUserMessages 优先于 questionText）

- [x] **新增 B：双端同机问题状态同步修复——2026-08-18 模拟器验证 ✅（A 回答 → B 消失闭环）；2026-08-19 代验收 ✅** `data` `session`
  - 问题：设备 A 回答后，设备 B 的 `loadPendingQuestions` 旧合并逻辑（`existingSseQs + newQs`）只增不删 → 已消失问题永久残留
  - **2026-08-08 代码完成（待人工验证）**：新增 `resolvePendingQuestionReplacement` 纯函数，声明 REST GET /question 为全量权威源，`loadPendingQuestions` 全量替换（含空列表清空语义）+ 3 测试（commit 0b85ca06）；全量单测无回归；⚠️ 真机验证待用户：双端同机 A 回答后 B 问题消失
  - **2026-08-18 模拟器验证 ✅**：B 端（App）进 E2E-C 会话 → REST 恢复卡片渲染（`loadPendingQuestions: 1 total pending → Replaced 1 questions (REST authoritative)` 日志）→ 设备 A（curl 直答 form 204）→ B 端 6s 内收到 `form.replied` SSE → 卡片消失转 "Asked" 折叠态——双端同步完整闭环。⚠️ 待用户最终验收
  - **2026-08-19 模拟器代验收 ✅（精确计时）**：curl 答 form（HTTP 204, 16ms）→ SSE `QuestionReplied` 到达 App 并派发（logcat 01:21:56.669）→ 首个观察截图（t0+1.5s）卡片已折叠 "Asked" 态——**端到端 ≤1.5s**；agent 复述「已收到你的回复：Apple（苹果）🍎」确认答案真实送达。三轮问答（fruit/color/animal）同路径全部即时同步（证据 /tmp/verify-acceptance/b_01~b_04）

- [x] **#30 消息本地化批次（方案 C）——Plan 1/2/3 全部完成（代码），待人工验证** `data` `cache` `room`
  - **2026-08-13 验证完成 ✅（用户授权 Agent 代测）**：冷启动打开会话 1 秒内消息渲染（Room 种子化秒开）✅；杀进程重启后消息保留（Room 缓存）✅；db 2.2M（ocbeacon.db 1.82MB + WAL 524KB）✅；覆盖安装保留数据 ✅
  - 问题：消息缓存/日志存储仍用手写 SQLite（DiagnosticLogDatabase 手写 SQL，路径分隔符/大小写敏感性风险）；消息本地化（方案 C）需先落地 Room 基础设施
  - 方案：按 Plan 分阶段——Plan 1 Room 基础设施（依赖 + 数据库骨架 + LogStore + Repository 迁移）；Plan 2/3 消息缓存与本地化落地
  - 工时：Plan 1 ~4h | 难度：中 | 涉及：app/build.gradle.kts / data/local/room/* / LogStore / DiagnosticLogRepository
  - **2026-08-08 Plan 1 完成**：Room 2.8.4 依赖（199bb36f）→ 数据库骨架 cached_messages/cached_parts/logs 三表 + LogDao + 插桩测试（60345b68）→ LogStore 诊断日志 Room 存储（修剪策略等价迁移 + 单测，53562a4b）→ DiagnosticLogRepository 迁移，删除 DiagnosticLogDatabase，手写 SQL 清零（3b206574）；编译 ✅ 全量单测 ✅（--rerun 26s PASS）；androidTest 编译受预存 #29 阻塞（Fake 缺接口方法，与本次无关）；手写 SQL grep 0 引用；⚠️ Plan 1 人工验证待用户：Diagnostics 日志显示/修剪/21 天语义
  - **2026-08-08 Plan 2 完成（代码，待人工验证）**：MessageApi 游标分页（before + X-Next-Cursor，9b3610e5）→ MessagePage 下沉 domain（5e208397）→ MessageStore Room 消息缓存（限量 1000 条/会话，81c2573e）→ 分页管线缓存优先（本地渲染 + REST 增量 + 真游标翻页，b6a6f461）→ 游标编解码 base64url JSON（3d0929dc + 595d63b2 fix）→ upsert 合并策略统一（SSE_PRIORITY/REST_AUTHORITY/APPEND_ONLY）+ SSE 双写 Room（caf8019b）→ 冷启动种子化 getMessagesFlow 从 Room 填充内存热视图（本次 Task 6）；编译 ✅ 全量单测 ✅（--rerun 46s，1305 tests PASS，含新增种子化测试）；⚠️ Plan 2 人工验证待用户（6 项，见 task-6-report）：秒开/离线浏览/翻页边界/SSE 重启保留/1000 条限量/磁盘占用
  - **2026-08-09 Plan 3 完成（代码，待人工验证）**：存储层架构清理四任务——Task 1 UnreadBadgeService 抽出（红点时间源独立，消除 EventDispatcher 的 runBlocking 落盘，3d828265）；Task 2 StreamingOwnershipRegistry 抽出（多服务器 SSE 去重独立化，c6bbd71a + 测试状态隔离 775b257e）；Task 3 SettingsDataStore 三文件合并（ReadTimes/Tags 扩展函数 → 成员方法，50b2af95）+ DraftDataStore 迁移 DataStore（含旧 File 草稿一次性迁移保数据，d4a906d7）；Task 4 DI 模块合并（DataModule 并入 DomainModule，FakeDomainModule 同步 replaces，domain 层无 data 依赖，3d674d10）；编译 ✅ 全量单测 ✅（--rerun 26s，1313 tests PASS）；androidTest 编译受预存 #29 阻塞（Fake 缺接口方法，与本次无关）；⚠️ Plan 3 人工验证待用户（5 项，见 task-4-report）：红点恢复/双服务器流式去重/会话列表红点/草稿恢复/Diagnostics+消息缓存回归
  - **2026-08-09 遗留处理完成（代码，待人工验证）**：#31 本地库损坏自愈（DatabaseRecovery：SQLiteException → 删库重建，6fdff190）；#29 androidTest 编译修复（3 Fake 补 7 接口方法，1ae44d57，androidTest 编译恢复）；网络失败回退本地缓存（有缓存不显示空，b843f265）；P4 清理（测试名/owner 日志/MessageRefresher 命名，f770e60d）；编译 ✅ 全量单测 ✅（1313+ tests PASS）；**一期代码全部完成，待 14 项人工验证**（见下）
  - **2026-08-09 模拟器走查（10 项）**：V1 Diagnostics ✅ / V2 秒开+种子化 ✅（[seed] 有/无缓存双分支命中）/ V3 断网浏览 ⚠️ 部分（断网冷启动受服务器连接入口限制，架构使然；已连接→断网→会话内浏览待真机）/ V4 重启保留 ✅ / V5 真游标翻页 ✅（logcat 实证 before=base64 游标前进）/ V6 红点标签收藏 ✅ / V7 草稿恢复 ❌ **发现 bug 已登记 #33**（saveDraft 仅 onCleared，force-stop 丢失）/ V8 磁盘占用 ✅（ocbeacon.db 1.5MB 合理）/ V9 SSE 流式 ✅（PONG 实测 + MessagePartUpdated 84 事件）/ V10 回归 ✅（无崩溃）
  - **2026-08-09 补充验证（3 项）**：①双服务器去重 ✅ 架构层面保证（同 URL 第二连接被 app 阻止，无双投递可能；"Thought"标记仅 1 次实证；衍生 #34 连接 UX/#35 ANR）；②日志修剪 ✅ db 实证（注入 60 FATAL 22 天 + 30 INFO 3 天 + 30 ERROR 22 天 → 启动即全部清除，时间规则 + FATAL 限量 + 字节预算按源码预期）；③**限量裁剪 ✅ 第三轮实证**：注入 1100 条 → 发送真实消息触发 upsert → `[prune] removed 101 oldest msgs (limit=1000)` → db 1100→1000 数学吻合（关键发现：仅进入会话不触发裁剪——prune 只在真实新消息写入时执行，设计如此）；**一期 14 项验证：13 项通过/部分，仅 #33 草稿缺陷待修**
