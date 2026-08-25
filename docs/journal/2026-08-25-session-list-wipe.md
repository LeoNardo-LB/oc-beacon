# 2026-08-25：#218 session.deleted 后会话列表全空——定因与修复

## 用户报告

从对话界面退到会话列表，列表无任何内容（Empty directory），疑似连接丢失。

## 排查链（排除法收敛）

| 检查 | 结果 |
|------|------|
| 服务器健康 + 会话存量 | 200 OK，50 会话仍在（连接未断） |
| adb reverse / app 进程 | 均正常 |
| 列表下拉刷新 | 不发任何 REST 请求（仅权限轮询）——列表数据源是内存态，非 REST 直读 |
| 简单进出会话 | 不复现 |
| REST 删除任一会话 | 必现：session.deleted SSE 到达即列表全空 |

## 根因

SessionEventHandler.handleSessionDeleted（2026-08-16 F6 泄漏清理引入）：

- 原代码：`_serverSessions.update { it.values.removeAll { v -> v.contains(sessionId) } }`
- values.removeAll 的谓词作用于元素（Set 本身）：只要服务器的会话 id 集合包含被删 id，整个集合被移除——而非仅移除该 id
- 下游：SessionListStateBuilder:41 过滤条件 `it.id in serverSessionIds` → 集合空 → 列表 0 项 → Empty directory
- 触发面：任意删除路径（app 内删除、E2E 清理、他端/服务器删除）都广播 session.deleted；#217 E2E 收尾 DELETE divider-e2e 即埋雷——用户随后退回列表看到空列表的时间线完全吻合
- 连带：loadSessions 的 _isLoading 无 try/finally——协程取消时卡 true → refreshSessions 永久被挡 → 下拉刷新也不发请求（观察到的刷新无效）

## 修复

1. handleSessionDeleted：mapValues 仅移除单 id + filterValues 清空集（F6 防泄漏意图保留）
2. loadSessions：try/finally 兜底 _isLoading 复位

## 验证

- 单测：新增回归 SessionDeleted keeps other sessions in serverSessions map #218（修复前必败）；全量绿
- 真机（fix build）：REST DELETE 任一会话（204 + SSE 广播实测）→ 列表完整保留；进会话→BACK→列表完整

## 关联

- #217 E2E 清理（DELETE divider-e2e）是用户命中该雷的直接触发器；bug 本身自 2026-08-16 F6 清理起就存在

## #219 V2 压缩失败静默（同日二报）

用户报告「压缩时分割线一闪而过，退出重进才看到实际压缩内容」。logcat 定音：06:26:47 CompactionStarted → 715ms 后 session.compaction.failed（provider Console Go 上游端点不可用）——该次压缩实为失败，非 UI bug；重进所见「已压缩」含旧记录与失败记录的误导性渲染。

### 四项缺陷与修复

1. 失败零反馈：V2 HTTP 秒回受理（steer），失败只从 SSE 到达；V1 的 HTTP 失败回调在 V2 永不触发 → CompactionEnded+error 字段 → SessionNextEventHandler.compactionFailures 广播流 → ChatScreen snackbar（chat_session_compact_failed + 服务器原因前 80 字符）
2. 失败消息伪装成功：V2Mappers 对 status=failed 的 compaction 消息无标记 → Part.Compaction+failed 字段 → CompactionCard 失败分割线（错误标签+错误色）；wire 契约矩阵同步（failed 字段登记）
3. started 的 messageId 读 messageID 但实测字段为 inputID（探针 payload 实证）→ 恒空 → 消息流内对位失效 → 勘误为 inputID 优先
4. 失败零刷新：失败分割线要重进才出现 → ChatViewModel init collect compactionFailedEvent → refreshMessages（与成功路径一致）

### 验证

- 单测：failed 广播（error 非空 emit / 空不 emit）×2 + wire 契约 + 全量 1933 绿
- 真机：provider 恢复后成功链路回归（进行中分割线→snackbar→新分割线会话内直接出现，无需重进）；失败记录（06:26:47）与成功记录（06:36:41）同屏——Failed to compact session（红）与 Context compacted 各自正确标注

### 修复二（同日三报：进行中分割线完全消失）

用户报告「点击压缩后看不到分割线，突然跳出已压缩 alert，然后出现分割线」。定因：#219 勘误 inputID 后 messageId 有真实值，与 inbox.enqueued 在压缩发起瞬间即插入的 role=compaction 骨架消息（无 Part.Compaction）交互——尾部分割线去重条件（messageId 已在列表）被骨架满足而抑制；消息流内又因骨架无 part 不认领 → 进行中态两边都不显示。

修复：消息流按 role+对位认领——role=compaction 且 compactionActiveState（messageId 对位）非空，或已有 Part.Compaction。骨架期（started 到达后）即渲染进行中分割线，完成后同 item 原位切完成态（Q13 本意）；steer 排队期（骨架已入列但 started 未到，compactionState 未置）不认领——避免渲染成静止「已压缩」误导。

排查副产物（重要认知）：①compact 是 steer 语义——排队等当前流式 turn 结束才执行，排队期无任何 compaction.* 事件（此前的「服务器僵尸」误判实为排队）；②「Nothing to compact yet」失败为即时 started+failed 对（毫秒级）。

验证（真机 fx3 帧 序列，压缩真实执行 34s：07:10:49 started → 07:11:23 ended）：fx3-1~8 COMPRESSING 全程可见（进行中分割线在骨架消息位置）→ fx3-9 Session compacted snackbar → fx3-10 完成分割线原位出现（n_compacted 1→2），全程无需重进。此前失败路径（Nothing to compact）也已验证：失败 snackbar + 失败分割线即时出现。

## #220 进行中态视觉打磨：标签骑线 + 两段线即进度动画（2026-08-25 四报）

用户反馈「正在压缩的状态在分割线上方多了一块区域专门显示，难看；就不能显示在分割线上、分割线带进度动画吗」。定因：#217 的 ActiveDividerRow 实现为「标签行在上 + 全宽进度线在下」（外加双层纵向 padding + 表面色遮罩底），与完成态（线—标签—线骑线单行）不同构——进行中态多占一整块空间，视觉突兀。

修复（CompactionCard.kt 单文件）：ActiveDividerRow 改为与 CompletedDividerRow 完全同构——左右两段 weight(1f) 2dp indeterminate LinearProgressIndicator，track=outlineVariant FAINT（与完成态分割线同色，即分割线本体），color=tertiary MEDIUM（扫动段=进度动画，M3 原生动画零自定义 spec）；标签居中骑线、无遮罩底、无额外块。进行中→完成切换仅「线由动转静 + 标签换文案」，行位零位移（Q13 强化）。

验证（真机 fw 帧序列，glm-5.2 模型真实压缩）：
- 结构：进行中标签 bounds [352,2237][848,2280] 与完成态标签 [409,2237][736,2280] 同一 y 带、同单行结构——无额外块（uiautomator）
- 全程可见：dump03（~4s）→ dump10（~12s）持续 Compressing context: manual，dump20（~24s）Session compacted snackbar，dump30 完成态原位出现
- 扫动动画像素级实证：左段线（x48-340）三色分离——背景 [247,250,253] / 静线 track [228,233,236] / 扫动段 [138,139,161]，扫动 run 位置逐帧移动（帧06: 75-147 → 帧09: 202-315 → 帧12: 110-256），完成帧扫动像素归零（纯静线）
- 回归：本轮先后两次失败压缩（provider 故障 + Nothing to compact yet）失败 snackbar 带原因 + 红色失败分割线均即时正确（#219 路径无损）；单测 CompactionDividerTest 绿 + compileDevDebugKotlin 绿

过程勘误（测试方法）：①shell awk 解析 bounds 字段拆分 bug 导致 tap 坐标算错——此前「进错会话」即此因，改用 python xml 解析；②多行 composer 中 keyevent 66 = 换行而非发送，/compact 斜杠命令注入失败改走 More options → Compact session 菜单；③uiautomator dump 失败时静默返回旧文件，必须先 rm 再 dump；④本会话 session 模型 opencode-go（Console Go 上游故障）导致压缩必败，POST /api/session/{id}/model（body {model:{id,providerID}}）切 zai-coding-plan/glm-5.2 后恢复。

## #221 压缩展开区三连改 + 高度预算调研（2026-08-25）

用户三点反馈：①展开区流式生长要「跟正常对话一样」的 SSE 视口补偿——**硬约束（两轮强调）：必须测量期反射注入（layout{} + requestScrollToItemNoCancel 渲染前提前计算），禁止渲染后反应式补偿**；②展开状态下压缩完成不自动收起；③左侧竖线固定 240dp 错误——必须与内容等高（左右取舍征询用户后代理裁决：仅左侧，引用式语义）。

实现：
- **①补偿**：ChatMessageList 压缩 item（消息流对位 + 尾部兜底两路径）接入 tool_progress 同款模式——compactionExpandState（独立 lastHeight，key=进行中压缩 messageId）+ 共享 shouldCompensate（在底意图）+ layout{} 无界测量同遍注入，log 标签 COMP-CMP(msg)/(tail)。与 COMP-MSG 完全同通道同语义。
- **②不收起**：双层锁存——CompactionCard 内 latchedText（liveText 空窗期兜底，canExpand 不闪断→AnimatedVisibility 不折叠）；ChatMessageList 内 lastCompactionMsgId（ended 清态→REST 刷新空窗期认领条件不翻假，Card 不离开组合、remember 不丢）。
- **③等高竖线**：ExpandContent 重写——Box + matchParentSize 叠加层 drawBehind 画 2dp 全高竖线（不参与测量，绘制阶段跟随内容高度，流式零延迟）；弃固定 240dp 与 IntrinsicSize。

真机验证（compact-probe，glm-5.2）：
- ①COMP-CMP 实弹：logcat 数十条注入记录（delta 3-27px/帧，08:35:34 窗口），展开动画+流式生长期的测量期注入全程工作；与 COMP-MSG 同款 log 格式即同款通道
- ③等高：vision 对真实帧确认竖线贯穿摘要全文首尾行
- ②机制实现+部分实证：三轮抓「完成瞬间不收起」工件均遇服务器变数（Nothing to compact 即拒 ×1、steer 队列卡死 ×1【interrupt 204 清除】、interrupt 后瞬完成 ×1）——完成瞬间箭头工件未捕获，机制链条（锁存→组合存续→展开保持）已代码级论证，转 V6 用户日常验收
- 回归：失败路径（provider 故障+Nothing to compact）snackbar 带原因+红色失败分割线即时正确 ×4 轮；全量单测绿

过程勘误：①脚本检测被历史分割线污染（'Context compacted' 计数法修正）；②reverseLayout 滚动方向直觉反直觉（finger down=更旧）；③opencode 日志进程与 4199 端口服务不同实例（cron 抢端口失败堆积），日志取证不可靠应以 API 为准；④会话 updated>idle 字段顺序假象误判「未稳定」。

### 高度预算调研结论（subagent 委派，docs/research/2026-08-25-card-height-precompute-feasibility.md）

**预计算路径判定：不值得做**。三类「出现」场景：流式 turn 内卡片弹入已被 COMP-MSG 单遍 delta 模型精确覆盖（高度在注入决策同一测量遍内已知，无时间差可弥合）；新 item 插入在 reverseLayout key 锚定下不产生可补偿位移（foundation 1.11.2 源码级取证）；toggle 已被用户终版裁决排除。SubcomposeLayout 预测量/静态预算表/lookahead 均无消费者且有反向注入风险。**两个新发现待办**：①反射通道回写竞争开放问题（动工任何补偿扩展前先做 §6.1 通道存活验证）；②贴底时尾部横幅类 item 不可见（V1 进行中分割线/retry/tool_progress——可见性缺口非补偿缺口，解法是 reveal 滚动）——已登记 #222。


