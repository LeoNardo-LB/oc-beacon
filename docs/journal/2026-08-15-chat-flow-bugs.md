# 2026-08-15 主对话流程 bug 批次（用户 V2 真机反馈）
> 状态：部分完结（活跃 #146）
> 迁移：2026-08-20 自 backlog.md 原文迁入（spec/journal 分离批次；原文逐字保留，未压缩）


- [x] **#139 发送成功后输入框偶发不清空** `ui`
  - 现象：点击发送后输入框内容偶发残留（消息已发出）
  - 根因：ChatSendDelegate 的 E8-1 清空快照 = prompt parts 重组文本，而 PromptBuilder.buildPromptParts 对文本 trim() 并拆分 @file 提及 → 快照与输入框原始文本（含尾随空格/换行/@mention）不一致 → ChatScreen 比对失败不清空。干净单行文本恰好匹配 → "偶发"
  - 修复（668d7b0e）：sendMessage(promptParts, attachments, rawText) 增加 rawText，快照直接用输入框原始文本（V1/V2 通用）
  - 验证：E2E 发送尾随文本 → 输入框清空 ✅（V2）+ V1 回归 ✅；1642 单测全绿

- [x] **#140 统计栏有概率丢模型名/耗时只剩圆圈（subagent 高发）** `ui` `sse`
  - 根因（三层）：① V2 session.step.ended 契约不含 modelId/providerId/agent，handleMessageUpdated 整对象替换抹掉 step.started 写入的模型信息（tokens 同事件写入 → "圆圈在、模型无"不对称）；② 耗时 completed 在 V2 SSE 从不携带，且 step.ended 的 created=本地时刻顶替原始时刻 → 单步消息耗时≈0 被 >0 门隐藏；③ MessageFingerprints 不含 modelId → RenderableTurn 缓存陈旧值不恢复。subagent 高发：子会话全程 Busy → L3 校验跳过 + 增量游标拉不回旧消息
  - 修复（3fcc11ae）：mergeAssistantMeta 非空字段合并（incoming 空保留 existing；created 取较早值）+ mergeMessageMeta 采纳 REST 模型元数据兜底 + 指纹纳入 modelId/providerId/agent。V1 message.updated 带全量字段 → incoming 覆盖，行为不变（E2E V1 实证统计栏正常）
  - 验证：V2 E2E deepseek-v4-pro + 5.2s/13.9s 显示 ✅（智谱识图 D3 复核）；V1 3.2m 统计栏正常 ✅

- [x] **#141 转后台/subagent 完成通知误渲染成 user 气泡（"多出大段用户回复"/"assistant 内容进 user 气泡"）** `sse` `ui`
  - 现象：转后台后多出一大段"用户的回复"，且内容是 assistant（subagent）的输出
  - 根因（Room+curl 双实证）：subagent/后台任务完成通知同样经 session.inbox.enqueued 投递（item.type="synthetic"，body=<subagent …>子代理全部输出</subagent>，实测 5KB）；V2SseMapper 播种分支无条件构造 Message.User（role 默认 "user"）→ 通知渲染成 user 气泡
  - 修复（6ca1f357）：播种读 item.type，非 user 类型设置对应 role → 下游走 SyntheticNotificationCard 通知卡片（V1 无 inbox 机制不受影响）
  - 验证：curl 注入 synthetic → logcat "admitted type=synthetic → MessageUpdated role=synthetic"（修复前 role=user）→ UI 渲染 "Sub-agent · E2E 测试任务" 通知卡片 ✅ 无 user 气泡

- [x] **#142 流式期间内容大段缺失、完结/重进后完整（断连窗口）** `sse` `data`
  - 根因（iptables 断连复现实证）：SseConnectionManager 的 attempt 在连接成功后重置为 0 → "曾成功连接→断连→重连"场景 attempt 恒为 1 → `if (attempt > 1) recoverMessages()` 永不触发 → 断连窗口（40s 心跳超时+退避）SSE 事件永久丢失，内容缺失不恢复（直到 text.ended 全量覆盖/重进 REST）
  - 修复（8bbcb216）：独立 hasConnectedOnce 标志——曾成功连接过的重连都执行 recoverMessages（REST_AUTHORITY + mergePartsList 更长文本胜出恢复）
  - 附带修复（同 commit 批次 6ca1f357）：V2SseMapper.partLocator ordinal 缺失 return null 静默丢弃整条 delta（且两行重复为复制粘贴错误）→ 兜底 0
  - 验证：断连 18s 场景 → 修复前无 Recover 日志；修复后 "Recovering messages for 50 sessions → Recovered 50/50" → Room 故事 2505 字 vs 服务器 2623 字一致 ✅
  - 已知边界（非缺陷）：断连窗口若丢 step.ended 事件则该消息无 tokens（圆环不显示）——REST 协议不返回 tokens，属服务器契约限制


- [x] **#144 subagent 调研会话卡死（31.6 分钟/17 分钟无消息输出）——2026-08-21 DB 取证归因关闭（opencode 服务器侧）** `upstream`
  - 现象：2026-08-16 主对话委派的 deep-explore 子会话两个停滞（ses_ff8f1c73affe…卡片内容截断调研 31.6 分钟无消息；ses_ff8e68cacffe…subagent 跳转调研 17 分钟无消息），第三个（回复不可见）正常完成
  - 影响：主对话等待挂起，需人工重派；已重派并带线索缩小范围
  - **2026-08-21 DB 取证完成（opencode.db 直查，归因钉死）**：两个卡死会话最后一条消息均以 `tool part status='running'` 永久挂起——ses_ff8f1c73 卡在 `read /persistent/.../opencode/service.json`（limit 15）；ses_ff8e68c8 卡在 `glob /persistent/.../.config/opencode pattern='*'`。共同点：**I/O 目标全在 `/persistent` 挂载（Tailscale 网络盘）**——网络盘 I/O 无限阻塞 + 服务器工具调用无超时 → 会话永远停在 running（旁证：当时 /api/session/active 全程返回 running，且『31.6 分钟无输出』期间 DB 零新消息落库——非慢，是死等）。父会话当时对同路径的 find 命令秒回，仅子会话工具调用挂起——服务器侧工具执行路径问题，非本 App 缺陷
  - 状态：`[x]` 归因关闭（服务器侧）。上游候选并入 #146 ⑥：deep-explore/task 工具调用对网络挂载路径 I/O 无 watchdog/timeout

- [x] **#145 任务面板 subagent 列表项显示执行时长——结案（5056694b 实现 + 2026-08-19 模拟器 E2E 走时验证 ✅）** `ui`
  - 需求（用户 2026-08-16）：任务面板（TaskSheet）的 subagent 列表项需要看到执行时间，放在 list item 右对齐合适位置
  - 现状：TaskSheet 列表项已显示开始时间（time.created，如 "10:22"）；执行时长 = 子会话完成时间（最后消息 time.completed）- created，运行中则 now - created（需要 1s 级刷新才能看到走时）
  - 注意与 ChatViewModel 侧思考计时（0.1s 间隔）区分——任务面板多个 item 同时走时需评估重组开销，可复用现有计时基础设施
  - 状态：`[ ]` 待实现（随任务面板修复批次一起做）
  - **2026-08-19 E2E 结案验证（用户指示模拟器校验优先）**：实现 5056694b 早已落地（TaskSheet trailing Column：状态图标在上、时长在下右对齐）。模拟器实证：① 运行中子代理「整理30条太空趣味事实」面板显示走时 **31s → 37s**（间隔 3.5s 两次快照，1s tick 前进铁证，截图 /tmp/verify-145-running.png）；② 完成态不显示时长 = **契约内预期**（🟠 V2 session.time.updated 不随活动更新 → 完成态无数据源，仅 updated-created>5s 罕见场景显示，代码注释文档化 + upstream 候选 #146）；③ 列表项结构完整（标题/前台徽章/agent/开始时间/模型/时长）。核心场景（后台任务跑着看跑了多久）验证通过


- [ ] **#146 OpenCode 官方问题清单（issue/PR 候选，2026-08-16 调研批次产出）** `upstream`
  - 提 PR 前提（用户定规）：本地定位官方源码（opencode-src V2 主干 / opencode-v1）→ 对应缺陷修复 → 本地完整测试（含端到端+交叉验证）→ 人工测试完毕 → 才可提交
  - ① **V2 不发 compaction.started 事件**（仅单个 session.compacted）：客户端无法事件驱动显示"压缩进行中"。候选 issue：建议 V2 补发 started/ended 对或文档化契约。本地已用"本地置态"绕行（代码注释标记 🟠）
  - ② **SSE 断线重连后无事件回溯**：错过的 idle/完成事件不补发，客户端通知与状态永久缺失。候选 issue：建议提供重连后事件重放或状态快照端点。客户端 REST 补查仅 best-effort（代码注释标记 🟠）
  - ③ **V2 cursor 参数收到 V1 格式 {id,time} 返回 400**：而非忽略或宽容降级；与官方文档/直觉不符，且错误信息无 cursor 格式提示。候选 issue（文档澄清或宽容处理）。客户端已改 encodeV2 根治
  - ④ **V2 fork 端点 handleRaw 冲突 bug**（任何 body 400）：已知服务器 bug，等官方修复或提 PR（需按前提流程）
  - ⑤ **工具输出保尾截头（30K 字符/2000 行）语义**：设计使然非缺陷；候选 feature request——progress metadata 提前携带 truncated/outputPath 让客户端更早提示
  - **2026-08-21 上游核查完成（子代理调研 + /tmp/opencode-src 主干 clone）**：上游 repo 已迁至 **anomalyco/opencode**（sst/opencode 为旧地址，~199k stars，默认分支 dev）；本机 4199 = `@opencode-ai/cli@0.0.0-beta-17728`（当前最新 beta，08-20 发布）；`0.0.0-<channel>-<N>` 为 2.0 开发线、GitHub Releases 只发 1.x 稳定线。逐候选状态：
    - ① **仍存在·此角度未报告**：事件发射点只在新核心引擎（packages/core/.../compaction.ts:192/222），shipping 旧引擎（opencode2.exe serve）只发粗粒度 session.compacted——『契约有、引擎没接线』。行动：提窄 issue（legacy serve path 补发 started/ended），先评论 #36187/#40494 防重；客户端超时启发式保留
    - ② **上游已两次 not_planned（#25657/#19584），PR #25658 未合并——不再提**。官方方向 = 拉取式 sync：主干已有 POST /sync/history（seq 游标补发 durable 事件）但 17728 未发布；且 session.status/idle 是纯内存 live 事件，回放补不了。客户端已有 REST 对账即正解；关注该端点发布后迁移
    - ③ **仍存在·未报告·值得小 PR**：packages/server/src/handlers/message.ts:38 与 session.ts:30 catch-all 只报 "Invalid cursor"（V2 cursor 实为 base64 JSON {id,order,direction}）。改动小被拒风险低，按完整流程走（源码副本已备）
    - ④ **移动靶——先升级重测再决定**：17728 要求 boundary 字段（{} → 400 Missing key boundary），但主干已改为 ForkInput={sessionID, messageID?} + 空 body 容忍（handlers/session.ts:218-231）——契约一周内刚翻新。等下一 beta 三形态重测
    - ⑤ **暂缓**：截断限制已可配置（PR #23770 merged：tool_output.max_lines/max_bytes）——先服务器配置放宽；progress 事件 shipping 引擎无发射点（仅 schema），FR 等 2.0 引擎 cutover
    - ⑥（新增自 #144）：工具调用对网络挂载路径（/persistent 等）I/O 无超时 → 会话永久 running。候选 issue
  - 状态：`[ ]` 候选池（①③⑥ 可行动；②关闭；④等升级；⑤暂缓）

- [x] **#147 androidTest UI 测试失败（编译断 + touch 注入败）——已随 #149 全量修复结案（136/136 全绿）** `refactor` `test`
  - 现象：2026-08-16 修复 androidTest 编译后首次真跑，全部 UI 测试报
    "No compose hierarchies found in the app"（HiltTestRunner 启动的 Activity
    与 createComposeRule/createAndroidComposeRule<ComponentActivity> 不兼容）
  - **2026-08-18 复验定性**：① 编译再次断裂——主代码接口演进（respondPermission 加 sessionId、SessionStateRepository 加 backfillMissedMessages）后 Fake 未同步（已修 6023bd5f，androidTest 编译恢复）；② 修复编译后 19 用例 **12 通过 / 7 失败**——"全部失败 No compose hierarchies"已不复现（当时问题似乎已随某次修复消散），现存失败移交 #149（5 touch 注入 + 2 节点超时）
  - 修复方向：迁移 androidx.compose.ui.test.junit4.v2 API + HiltAndroidRule
    组合，或为非 Hilt 测试提供独立 TestRunner（gradle 配置多 runner）
  - 已完成的前置：Fake 接口对齐（6 个 + 2026-08-18 再补 2 处）+ FakeMessageCacheRepository +
    Hilt 测试图 MissingBinding 修复
  - **2026-08-19 结案**：#149 修复三类根因（hasScrollAction 多匹配 → testTag 选择器 / 文案漂移 / 断言过时）后 androidTest **136/136 全绿**（含全量）；"No compose hierarchies" 现象未再现，原修复方向（junit4.v2 迁移）不再必要。标题同步改写（原标题"12/19 可过"已过时）

- [x] **#148 任务面板 subagent 点击「无法进入」——2026-08-16 归因关闭（环境问题非 App bug）** `ui`
  - 现象：模拟器点击 TaskSheet 列表项无反应（探针 0 触发、sendevent 原始注入同样失效）
  - 排查：git 回退 TaskSheet+ChatScreen+ChatViewModel 至正常版本仍复现 →
    排除代码；**重启模拟器后恢复正常**（完整跳转链验证 ✅）
  - 结论：模拟器长时间运行（8h+）输入系统劣化——仅 ModalBottomSheet 内
    Compose clickable 失效。E2E 排障守则：长会话后「点击无反应」先重启模拟器
  - 状态：`[x]` 归因关闭（无代码缺陷；App 实际跳转功能正常，dev.11 真机可验）


- [x] **#149 androidTest 剩余 7 个失败——已修复 6f574128（三类根因，136 全绿）** `test`
  - 现象：2026-08-16 androidTest 修复至 129/136 后，剩余 7 个全部
    "Failed to inject touch input"——新模拟器同样失败（非环境劣化）
  - **2026-08-18 复现明细（19 用例 12 过 7 败）**：`Failed to inject touch input` ×5（ChatScrollStabilityTest.userScrollsAway / autoScrollEnabledResets / shouldCompensateResets / completedMessageHeight 四个 SSE 铁律守护 + FileTreePanelTest.filterChipClick，与 08-16 记录一致）+ `ComposeTimeoutException 10s` ×2（ChatInteractionTest.contextUsageBar_shows / questionDialog_appears——非 touch 注入族，节点等待超时，独立定性）
  - 涉及：ChatScrollStabilityTest×4（SSE 铁律守护测试）/ChatInteraction×2/
    FileTreePanelTest.filterChip×1
  - **2026-08-18 修复（6f574128）——与 ScrollListGate 重构无关，三类独立根因**：
    ① touch 注入 ×5：诊断用例实证 hasScrollAction() 在 ChatScreen 树匹配 2 节点
    （消息列表+底部输入栏可滚动）→ onNode 多匹配坐标无效——LazyColumn 加
    testTag(chat-message-list)，选择器改 onNodeWithTag（含 IsolatedTest 同病）；
    ② FileTree chip：断言与资源文案不一致（改版未跟测试）——加 testTag；
    ③ 超时 ×2：question 卡标题改版 Awaiting your reply + contextWindow 兜底被
    0cb68851 删除（改 tokenStats 直供）。验证：136/136 全绿（含全量 androidTest）
  - 状态：[x] 已修复
