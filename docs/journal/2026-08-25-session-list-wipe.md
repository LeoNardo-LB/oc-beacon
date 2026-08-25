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

## #222 双修：贴底横幅 reveal + 补偿通道回写竞争根治（2026-08-25）

用户指令「两个问题也修了吧」——调研副产物的两个发现：#222 贴底尾部横幅不可见、§6.1 反射通道回写竞争开放问题。

### 修一：贴底横幅 reveal（可见性缺口）

定因（调研 §2.4 源码级）：reverseLayout key 锚定下，贴底时横幅区新 item 插入点在锚之下（P<A）→ 零位移且不被组合 → 不可见；锚 index 抬高还使 isAtBottom 翻 false（⬇ FAB 闪现）。受影响且无自有 reveal 路径的四类：retry（无 error）、tool_progress 聚合卡、step indicator、压缩尾部兜底分割线（V1 唯一路径）。

实现：ChatMessageList 新增 revealBannerCount（四类计数）+ LaunchedEffect 驱动 `requestScrollToItem(0)`——msgCount effect 同款显式锚底语义（fling 等待+重校验防竞态），门控用 autoScroll（在底意图）而非 isAtBottom（后者被插入本身翻假会自我闭锁）。ChatScrollController 暴露 `autoScrollState: State<Boolean>` 传入。revert/question/perm 不计——各有 msgCount/pendingCount 路径。reveal 是显式滚动决策，非补偿，与硬约束无涉。

### 修二：补偿通道回写竞争根治（scrollToBeConsumed 通道复活）

真机活体诊断（14:50 分布式长文流式 + 滚离 1/3 屏）：COMP-MSG 67 次实弹，off 轨迹 785→933→**933**→1093→1163——fire2 注入（933+72=1005）被回写吃掉回到 933，其余存活。**间歇性注入丢失（~30%）→ 阅读历史期视口缓慢上爬**。与 #215 journal 动画场景定因（请求被 updateFromMeasureResult 中途丢弃）同源——静态分析+活体证据双确认。

源码定音（foundation 1.11.2，/tmp/compose-src）：LazyListMeasure.kt:423 把本遍**起始** off 原样回写；中途注入的 request-position 若 poke 再测遍先起跑则存活、否则覆盖丢弃——竞争窗口结构性存在。scrollToBeConsumed 通道（LazyListMeasure.kt:142 遍首**无条件消费**）无此竞争。

实现：`LazyListReflection.requestScrollShift(state, shiftDownPx)` 复活（a4eedab6 封存实现：scrollToBeConsumed 累减 + poke；降级回 request-position 通道）；**四个 COMP 注入点全部切换**（COMP-MSG / COMP-TOOL / COMP-CMP(tail) / COMP-CMP(msg)）。语义等价（生长 delta → 消费时 off += delta）、时序更优（遍首消费先于放置=更严格的渲染前注入，符合用户硬约束）。

### 验证状态（诚实记录）

- 通道诊断证据链完整（活体 off 轨迹 + 源码 + #215 历史三方互证）；修复本身有 #215 时代同通道 dy=0 六格矩阵的历史验证背书。
- **修复后活体 E2E 被服务器阻断**：当日 15:05 起三家 provider（opencode-go/zai/deepseek）对新 prompt 全部静默挂起（prompt 受理+assistant 壳创建后零 delta，多会话多轮次重试含 interrupt 清队），仅存量轮次慢速完成。#222 reveal 与新通道流式稳定性活体验证均转 **V6 用户日常验收**。
- 全量单测绿；compileDevDebugKotlin 绿；最新构建已装真机。

### 验收清单（V6，服务器恢复后）

1. **通道**：任意会话流式长回复期间滚离底部 1/3 屏停住——视口应纹丝不动（修复前：缓慢上爬）。
2. **reveal**：贴底状态发起会触发工具的提问（如「用 bash 执行 sleep 5」）——工具聚合卡应立即可见（修复前：不可见+⬇FAB 闪现）；V1 服务器同理验压缩进行中分割线。


### 测试入口定规（2026-08-25 用户指令「debug 进入会话列表优先级提一级」）

落实三件：①`scripts/debug-entry.sh`——reverse + force-stop 冷启 + logcat 校验（Debug channel activated / NavGraph → SessionList），失败非零退出（实测 OK 路径 1 命令 5s 到列表；坏配置路径 exit=1）；②runbook 新增「标准测试入口（第一优先级）」节置顶 + E2E 纪律条款（force-stop/重装/adb 重启后先跑脚本再继续；禁止 Settings 手工点进列表——坐标错/BACK 退桌面/dump 陈旧三坑均有当日实证）；③AGENTS.md 真机 bullet 内联入口规则（承重规则内联原则，agents-file-design §3）。
### 修二再强化：延迟揭示（真·渲染前，用户六报定音）

用户挑战：「修二的修法是渲染前计算吗？」——**诚实回答：不是严格的渲染前**。requestScrollShift 直注模式仍是「增长已测出的那一遍测量中途注入、下一遍遍首消费」：补救遍多数落同帧（画前生效），但无构造性保证——帧预算紧张或 markdown 迟到解析巨跳（实测 376→51129px 单帧）时，一帧未补偿画面被绘制 → 用户感知「渲染后补偿」跳变。

**延迟揭示（DeferredRevealCompensator，ScrollCompensation.kt）**：把时序改成构造性渲染前——增长遍不向 LazyList 上报新高度（未上报增量被 clipToBounds 裁掉，未补偿几何永不被放置），增量预注入 scrollToBeConsumed；下一遍（poke 加速到同帧）遍首消费先行、再上报「基准+已消费增量」——揭示与锚点位移几何严格对齐。连续增长链式：每遍揭示上一遍增量、递延本遍——最新文本至多晚一遍出现，位置永不跳。高度来源仍是精确测量（非预测），只是把「测量→注入→揭示」排序为消费先于揭示。

四个 COMP 点全部迁移到 `Modifier.deferredRevealCompensation`（COMP-MSG/COMP-TOOL/COMP-CMP(tail)/COMP-CMP(msg)），独立补偿器实例（msgReveal/toolReveal/compactionReveal，remember 键与旧 lastHeight 状态一致），log 标签沿用（defer 语义）。单测 7 例（冷启动/单增两步/连续链式/回底清欠/收缩/复位/稳态 no-op）+ 全量绿；已部署真机。活体验证仍被 provider 全静默阻断（当日 15:05 起持续），V6 清单不变。

## #223 SSE 空 part 增殖 → 进会话主线程冻结（2026-08-25，真机 E2E 意外捕获）

### 发现与定因

#222 延迟揭示的活体验证被一个数据层 bug 挡住：进含流式历史的会话永久转圈（8 分钟+无内容）。取证链：①MIUIScout 报 APP_SCOUT_HANG（主线程 5s+）；②jdb 挂起取栈（runbook §插桩前置 #2 方法）——主线程停在 mergePartsList→dedupOverlappingTextParts→isNewPartId；③Room 直查（run-as cat 拉库）——单消息 4488 part / 4487 空，id 全为 _reasoning_ord_0..N 递增；④服务器 REST 同会话无 >10 part 消息 = 纯客户端残留；⑤raw SSE 抓包定音服务器怪癖——每 reasoning 块发 started（ordinal 递增、空文本）而 delta 恒 ordinal 0。

根因链：空 started part 无限增殖（内存 add + 落盘 INSERT OR REPLACE 不删行）→ REST 权威刷新时 preserved 无限保留 → 每批 upsert 对 N 个 part 跑 O(N²) dedup（含全文前缀比较）→ 主线程饱和。

### 修复（三层，MessageEventHandler.kt）

1. dedup 的 isNewPartId 前置——双侧新版契约 id 直接跳过（原顺序先算昂贵全文比较再查 id，纯浪费）
2. mergePartsList preserved 过滤空 Text/Reasoning 残留（服务器不回带它们 = 无价值；delta 后到有 idx<0 重建兜底）
3. handleMessagePartUpdated 增殖源头掐断：派生契约 id 的空 started 且同 kind 已有空 part → 丢弃。首版对任意空 part 折叠被既有测试（p1/p2 两空 part 合法共存）打回——收紧为仅 _ord_ 契约 id 生效

### 验证

- 冻结会话（验收测试会话AB，111 part 炸弹）：5.4s 进入出内容（修复前 8min+ 转圈）
- 增殖停止：修复后新轮次 assistant 消息 1 part / 0 空（修复前同链路百级空 part）
- 回归测试 ×3（增殖抑制/非空 ended 正常新增/自定义 id 不折叠）+ handler 全套 + 全量 1933+ 绿
- 遗留：DB 存量炸弹行（4488/139/111）不自删（merge 层已滤=inert）；升级 Room schema 清理可后续做

### 根因完备性验证（用户质询「是否根因修复」，2026-08-26）

服务器全库 50 会话角色普查（REST 全量）：assistant 416 条（max 101030）/ **system 35 条（max 11235）**/ synthetic 28 条（max 10060）/ user 155 条（max 3085）/ compaction 7 / agent-switched 30 / model-switched 30（后两者 max_text_len=0）。

逐角色渲染契约核对（非对话角色的完整清单）：
- system → #232 单行通知（Turn + UserChunk 双路径封死，SysMsgDiag 实证）✓
- synthetic → SYNTHETIC 通知卡（滚动上限）+ 转后台变体分割线；`userChunkPlanFor` L117 **既有排除**（非本次遗漏）✓
- compaction → 分割线（#217/#226）✓；agent-switched/model-switched → 空文本（0 字符，全库实测）无墙风险 ✓；shell/skill → 本服务器不存在（理论角色）

结论：症状根因（system 无渲染契约）已根治；同类泛化类（「每个非对话角色都需定义渲染契约」）经数据验证为完备——无第三个未知路径。残留：①分片排除的落点不对称（system 在调用点、synthetic 在 userChunkPlanFor 内——功能等价的卫生问题，非 bug）②11KB 原文仍随每次 prefetch 重下载存储（数据冗余，无害）③服务器插入工具目录系上游行为，不属本客户端修复范围。

### 过程勘误

①REST parts 只在轮次完成后落盘——流式期探活必须看 SSE 事件流或 app logcat，此前两轮「provider 死了」误判实为探针方法错；②服务器「Session not found」偶发（列表有、直查无）——换会话绕过；③uiautomator dump 在大消息渲染期失明（空树），用 vision 截图裁决；④装包后应用回桌面——一律 ./scripts/debug-entry.sh 标准入口重进。

## V1 压缩路径真机 E2E（2026-08-25，用户授权自建 V1 服务器）

环境：本机 opencode 1.18.18 隔离实例 @4198（XDG 隔离 + zhipu-coding 自定义 provider，key 从 V2 凭据库 credential 表提取；auth.json 必须放 data 目录——放 config 目录 401 无 Authorization，实测）。App 经 debug intent 冷启接入（V1-4198，版本探测正确）。配方已固化 runbook「V1 测试服务器快速搭建」。

验证结果（v1c 帧序列，glm-5.3 压缩 4.5K 上下文 5.9s）：
- **#222 reveal（V1 核心缺口）✅**：贴底触发 Compact 后 dump02（~1.4s）即见 `Compressing context…` 分割线在视口底部 [405,2237][794,2280]——调研 §2.4 预测的「贴底 P<A 插入不可见」已被 reveal 修复消除
- **#220 骑线形态 ✅**：vision 确认标签居中骑线单行结构
- **扫动动画 ✅（像素级）**：分割线带扫动段位置逐帧移动（帧02: 48-108+252-339 → 帧04: 48-94），完成后扫动像素归零
- **完成链路 ✅**：dump05 `Session compacted` snackbar + 服务器落盘摘要（assistant agent=compaction，1593 字符）
- **V1 语义差异（记录非缺陷）**：V1 compact 产物是常规 assistant 消息（无 Part.Compaction）→ app 渲染为普通气泡（Next Move/Relevant Files 正文可见），与 V2 完成分割线形态不同——服务器语义使然

至此 #217 遗留的「V1 真机验证留待环境」欠账清偿；#222 验收清单第 2 条（V1 压缩进行中分割线贴底可见）实证通过。

## #226 压缩分割线形态大乱：V1 三元素重叠/气泡流式/完成塌缩 + V2 嵌套 summary 兼容（2026-08-25，用户三报）

用户反馈：「太乱了！总是闪现，动作很不连贯与流畅！」+「之前不是说了吗？压缩的输出不要在气泡中，现在怎么又在气泡中了」。卡片方向（#226 前置指令「统一使用卡片」）被用户当场撤回，回归分割线形态。

### 诊断（diagnosing-bugs 纪律：反馈回路先行）

回路构成：真机录屏（screenrecord 8Mbps）→ ffmpeg 32×32 灰度帧差序列（显著变更聚类定位事件时刻）→ 关键帧视觉裁决（vision）＋ uiautomator 语义树（盲区时用 raw XML 定位 clickable bounds）＋ Room 直查（run-as 拉库）＋ SSE/REST 服务端形态对照。V2 触发配方：REST 平铺契约注入对话轮（{"text":...} → /api/session/:id/prompt）+ {providerID,modelID} → /compact；zhipu 间歇停摆用 interrupt+重发恢复。

**V1 根因（三元素同屏 + 气泡 + 完成塌缩）**：
1. V1 契约（实测 REST）：compact 触发消息 = user + part type=compaction（无文本）；摘要 = 后随 assistant(agent=compaction) 流式消息。本地置态（ChatViewModel L494）CompactionStarted(sid, messageId=空串)——空串永不命中消息流对位判据 → 触发消息被 parts.any{Compaction} 认领渲染成**静止「已压缩」线**（进行中误导为完成态）；
2. 归一化器完结守卫放行未完结 assistant(agent=compaction) → **摘要以普通气泡流式**（违反 #217 裁决）；
3. 空串 messageId 走尾部兜底 → **尾部活跃线**全程在场；
4. 完成瞬间：气泡折叠为 Part.Compaction 后走 msg.isAssistant 分支 → MessageCard → **PartContent 跳过 Compaction（L336）→ 空 turn**；尾部线消失。大高度塌缩 + 双元素消失 = 「闪现/不连贯」。（#224 E2E 声称「展开摘要正常」与代码路径矛盾——当时验证不可靠，本次 Room+分支双重实证推翻。）

**V2 根因（zhipu 构建）**：压缩刚完结瞬间 REST 返回 summary:{body:""} 嵌套对象 → V2Mappers obj[summary].jsonPrimitive 强转抛 IllegalArgumentException 被外层吞 → **parts 整体丢失**（DB 实证 compaction 消息零 parts）；稳定后 REST 变扁平字符串 summary（1511 字符实测）。副作用：完成线展开内容只靠 latchedText 活体，重进会话即丢。

### 修复（commit ff2b78be，「一条压缩 = 一条分割线」）

- **ChatMessageList assistant 分支认领**：agent==compaction → 未完结 = 伪活跃态（deltaText=text parts 拼接）骑线进度 + 可展开流式；完结 = 完成态 + 摘要可达。同 item 原位切换保留 Q13 连续性。COMP-CMP 仅在无 COMP-MSG 外层（isStreamingMsg 假）时自包——防双重注入。撤销长按边界取紧邻前触发消息 id（V1 语义：撤到压缩点之前），找不到退自身。
- **V1 触发消息隐藏**：role==user + Compaction part summary 空白 → 不渲染（item 退化一段 messageSpacing 间隙）。
- **尾部兜底让位**：新增 v1CompactionSummaryInList（displayItems 含 assistant(agent=compaction)）→ tailCompaction/bannerCount/revealBannerCount 三处同步让位，V2 恒 false 零影响。
- **V2Mappers 双读**：(obj[summary] as? JsonObject)?.get(body) 优先、基元回退。

### 验证

- 编译绿；全量单测 **1948 绿**；V2MappersTest +2 例（嵌套 body/基元回退）= 32/32。
- **V2 真机**（flick-repro-A 会话）：完成分割线渲染 + a11y Expand 在场 + 服务器摘要可达（嵌套→扁平两形态皆读出）。
- **V1 真机生命周期**（新会话 REST 注入 Opus 对话轮 + App 菜单触发压缩，录屏 44s 帧差分析）：进行中 = **单条 Compressing context… 活跃线 + chevron，无气泡**（帧差稀疏=无文本墙生长旁证）；完成 = 单条 Context compacted + 可展开；展开 = 摘要要点全文；**重进会话后**（REST 全量→归一化折叠→assistant 分支认领）分割线仍渲染且摘要可达——V1 摘要持久性成立（服务器消息为源，不依赖 latchedText）。
- 测试残留清理：V2 flick-repro-A/compact-probe（服务器自动改名）与 V1 Opus 会话已 DELETE。

### 遗留（登记不修）

- V2 展开期首个 delta 到达时 chevron 弹入引起标签行重排（帧差 536 实测）——次要，待用户主诉再议。
- 老长会话（45K 字消息）初始锚点空白段 + uiautomator 盲区——既有行为，与本卡无关。


## #228 V2 会话加载巨慢 + 页面乱：Room 空 part 炸弹回灌 merge 主线程 HANG（2026-08-26，用户报）

用户：「我在 V2 点击当前这个会话的时候会加载很久，然后加载出来的页面也很乱」。

### 诊断（系统性，三层证据闭环）

- **现象取证**：MIUIScout 主线程 APP_SCOUT_WARNING/HANG（2.5s/5s）完整栈：`String.contains → isNewPartId → dedupOverlappingTextParts → mergePartsList → upsertSsePriority → upsertMessages ← MessagePaginationDelegate.loadMessagesForSession(L132/L155)`——merge 全程跑在主线程。
- **数据取证**（Room 直查）：该会话一条 assistant 消息挂 **4488 个空 reasoning part**（另两条 139/211），全库空 Text/Reasoning part 共 **5714** 个——正是 #223 当时登记为「inert 不自动删」的 legacy 炸弹行，**并非 inert**。
- **机制定音**：#223 的空 part 过滤只作用 existing 侧（preserved）；Room 炸弹行作为 incoming 二次种子回灌热视图时长驱直入 `dedupOverlappingTextParts` 的 O(N²) 双层循环（每对两次 isNewPartId 短串扫描）→ 2000 万+迭代 × 数轮加载 = 主线程数十秒 HANG。「乱」= 炸弹 part 涌入 UI + 加载多轮互相打断的次生现象。

### 修复（三层防御，commit 见 git log）

1. **入口对称过滤**（MessageEventHandler.mergePartsList）：incoming 侧同样滤空 Text/Reasoning（sanitized 全空时对 existing 也滤空再返回——该路径原本直通让炸弹永生）；热视图炸弹随每次 merge 逐步清除。
2. **合并下沉后台线程**（MessagePaginationDelegate）：两处 `upsertMessages` 包 `withContext(mergeDispatcher)`（默认 Default，可注入测试调度器）；StateFlow CAS 写线程安全，同步等待保持调用方顺序语义。
3. **存量一次性清扫**（启动时）：`MessageDao.deleteEmptyStreamParts()`（DELETE 空 text/reasoning）+ `MessageStore.sweepEmptyStreamParts()` + OpenCodeApp onCreate appScope 触发（幂等，后续运行为 0 删）。

### 验证

- 单测：+2 回归（4488-part 炸弹 incoming 滤除 + 全空 incoming 清扫 existing；后者含 <1s 守时断言）；MessagePaginationDelegateTest 注入 UnconfinedTestDispatcher；全量 **1952 绿**。
- 真机：启动日志 `#228 swept 5714 empty stream parts`（与修复前 DB 计数分毫不差）；复打开原慢会话——**MIUIScout 停顿 0 次**（原 6+ 次）；REST 响应→merge 完成 **1ms**（原 ~30s）；页面 vision 裁决 CLEAN（用户泡+助手泡+分割线，无重叠/乱码/空白转圈）。

### 备注

- #226 journal 遗留的「DB legacy bomb rows 可选清理任务」即本卡层 3，已一并完成。
- 三轮加载循环（入口种子×2 + modelConfig 解析×1）为次生放大（用户卡死后重试），主因消除后不再触发停顿；未单独改动加载编排。


## #232 「叠在一起」三审定音：system 消息 11KB 工具目录墙折叠为一行通知（2026-08-26，用户令「截图查看！多打日志」）

用户批评正确：我前两轮（#230/#231）依赖「语义树 bounds 交集=0」判定无重叠——但语义树只报告**应许位置**，且低分辨率视觉判定反复误读，两轮都没找到用户看到的真东西。本轮以当前实况截图 + 全分辨率逐带裁剪 + Room/服务器双源对照重审，真凶落网：

### 根因（三审定音）

**`system` 角色消息被当普通消息全量渲染**。本会话有一条 zhipu 构建的 system 消息（4sqe4MbR，11235 字符）——内容是「The Code Mode tool catalog has changed」+ **全量工具目录 schema**（Context7 query 说明、各工具签名、JSDoc 注释……）。V2Mappers 把 system 映射为 `Message.User(role="system")` + Part.Text，UI 无任何特判 → **1340px 无气泡英文等宽文本墙**直接插在中文对话中间。用户看到的「很多消息叠在一起」= 这面墙与正常对话消息视觉混排（无容器、无边界、语言跳变），并非 z 序叠加。

证据链：屏幕英文墙 ↔ 服务器该 assistant turn 无任何英文/tool part ↔ Room 定位到 system 消息 text=11235 字符；内容逐字匹配（tools.context7['resolve-library-id']…）。

### 修复（#232）

ChatMessageList isUser 分支前置特判：`role == "system"` → 折叠单行通知（Info 图标 + 首句 60 字截断 + 展开 chevron，labelSmall/muted——与转后台通知同视觉语言）；点击展开为可滚动全文（verticalScroll + heightIn(300dp)，AnimatedVisibility 无参默认）。展开态屏幕级表（systemNoticeExpandedStates，#227 同模式）。

### 验证（真机实拍，非推断）

- 折叠态：截图裁决——巨型 schema 墙消失，顶部为一行通知（图标+截断文本+chevron）✓
- 展开态：点击后为有界滚动块（~300dp）✓
- 全量单测 1956 绿；其余消息渲染不受影响（助手 turn/用户气泡/分割线原样）。

### 教训（写给下轮自己）

1. 「语义树无交集」不能证伪视觉问题——Compose 语义只覆盖导出节点，布局混乱/内容混排类问题它天然看不见。
2. 视觉模型判定必须全分辨率人读式复核，低分辨率批量初筛的 OVERLAP 判定两次把追凶带偏。
3. 用户说「还是这样」时，第一动作是截图看**当前实况**，而不是复跑上一轮的验证矩阵。
## #232 勘误二（同夜续）：system 消息绕道用户长文分片——UserChunk 路径拦截缺失（SysMsgDiag 定音）

用户四报「没有解决，最底端 agent 回复还是叠在一起」，并要求「截图查看！多打日志」。

### 按要求加日志后的定音过程

1. 在 #232 分支与 Turn 分支头部加 SysMsgDiag/ItemDiag 日志 → 复现时 **SysMsgDiag 不触发**——system 消息根本没走 Turn 分支！
2. 全分辨率裁剪确认屏幕上是**深蓝底等宽代码块**（Markdown fenced code 样式）——#232 的折叠通知是纯文本行，不可能产生此样式 → 存在第三条渲染路径。
3. 路径定音：11235 字符的 system 消息（Message.User role=system）满足「用户长文」条件 → `buildChatEntries` 的 `userChunkPlanFor` 把它切成 **ChatEntry.UserChunk** 分片条目 → `ChunkedUserMessage` 逐分片按用户 Markdown 渲染（工具目录里的 fenced schema = 深蓝代码块）→ 一面包文本墙。#232 的拦截只写在 `ChatEntry.Turn` 分支，UserChunk 路径完全绕过。

（用户看到「最底端 agent 回复叠在一起」= 该墙与其后用户消息/agent 回复在视觉上无边界混排。）

### 修复

`MarkdownChunking.buildChatEntries`：用户长文分片判定追加 `role != "system"` 门——system 消息不再进 UserChunk，回落 ChatEntry.Turn → #232 通知拦截生效。

### 验证（日志 + 实拍双证）

- SysMsgDiag：`system branch RENDER id=4sqe4MbR textLen=11235` + `id=p6hCqirN textLen=86`——两条 system 消息均走通知分支 ✓
- 截图裁决：深蓝代码块墙消失，两条紧凑单行通知（图标+截断+chevron），对话读作 通知行+中文用户泡+中文 agent 回复，边界清晰 ✓
- 全量单测 1956 绿。

### 过程勘误

上一轮验证时点开展开态未收回，给用户造成了「没修好」的观感——验证收尾必须还原现场。诊断日志（SysMsgDiag/ItemDiag，DEBUG-only）保留供后续取证。
## #231 「还是叠在一起」二审：全分辨率勘误 + 跨 item 越界构造性防御（2026-08-26 凌晨）

用户再报「现在还是会叠在一起」。系统性重审：

### 取证矩阵（多测量手段交叉）

- **语义树 bounds 交集扫描**（重叠硬判据）：稳定态 0 交集。
- **边界带全分辨率人读式裁决**：用户报的 y 区间实为 Markdown 行内代码片段（中文段落中的 inline SQL code span）——低分辨率视觉模型把「行内代码+邻接文本」误读为「两层叠加」。多个「OVERLAP」判定在放大复核后全部翻案。
- **真重叠存档**（用户截图 + 当晚 still_overlap.png）：英文工具 schema 与中文思考文本互压——确认为 **#230 之前炸弹数据期**的布局混乱（单 turn 209 垃圾 part 渐进测量的越界绘制），#230 清源后未再捕获。
- **录屏逐帧**（慢滚双向/冷启动进会话高帧率/进会话即狂 fling 三场景）：早前批量判定 OVERLAP 的帧复核为截图过渡黑帧/半绘制行误读。

### 机制结论

跨 item 重叠的唯一物理通道 = item 内容异步增长（reasoning 展开/Markdown 迟到解析/分片裂变）重排窗口内的**越界绘制**。流式 item 有 clipToBounds（COMP-MSG 链），**非流式 Turn/Chunk item 此前没有**——补齐即构造性封死。

### 修复（#231）

- ChatMessageList：非流式 Turn item 与 Chunk/UserChunk item 的 Box 补 `clipToBounds()`——越界绘制转为「暂时裁掉」（内容在界内时零视觉差异），跨 item 叠加从构造上不可能。
- 全量单测 1956 绿；真机 stress fling（进会话 12 连 fling 双向）+ 稳定态复核无重叠。

### 遗留

- 进会话单次 RESIZE +914（reasoning 展开/异步首测的正常重排，一次到位）仍在——非重叠，观感若扰人另立卡。
- 视觉模型对「行内代码」的叠层误读已两次浪费追凶——后续取证以语义树 bounds 交集 + 全分辨率放大人读式裁决为准，低分辨率批量判定只做初筛。
## #230 消息重叠（叠在一起）根因根治：REST 源头滤空 + 全通道封堵 + 两处自查误判勘误（2026-08-26 深夜，用户报「先于本次优化存在」）

用户：「这个会话中很多消息似乎叠在了一起……是本次优化前就出现的问题，请系统性分析根因并根治」。

### 证据链（系统性）

- 慢滚全程录屏帧抽查（10 帧×双向）+ 冷启动进会话高帧率捕获：当前构建未复现重叠。
- **ScrollDiag RESIZE**：`t_msg_038cd8c4f001 h 376→1290 (+914px)` 确定性复现——item 初次测量后大幅增长=渐进测量实锤；邻居按旧高度排布的增长窗口=重叠的物理机制。
- **Room 直查**：该消息 210 reasoning part（209 空）——**服务器 content 本身携带 SSE started 残留**（#223「服务器无此数据」结论对该会话不成立）。
- **机制定音**：turn 渲染 211 part（209 垃圾）渐进组合/异步测量 → +914px 增长 → 增长窗口内文本压邻居=「叠在一起」。次生：prefetch replaceSessionMessages 把服务器全量原样写 Room，绕过 #228 的 merge 侧过滤 → 每次开会在 Room 重植 4872 行（清扫打不赢的仗）。

### 修复（五通道全闭）

1. **V2Mappers 源头滤空**（真根因层）：content item 空 text/reasoning 在 REST 映射时丢弃——ordinal 照常计数保 id 契约与 SSE 派生对齐（非空 part id 不变，Room merge 幂等）；REST 不收 delta，丢弃零副作用。
2. **V1ApiClient 对称防御**：listMessages 解码后滤空 Text/Reasoning。
3. **注册点封堵**（MessageEventHandler.handleMessagePartUpdated）：派生契约 id 的空 started 一律不注册（#223 原语义「同 kind 已有空才丢」升级为零注册）。
4. **重建判型修复**（连带真 bug，测试逮出）：delta idx<0 重建此前硬编码 Part.Text——reasoning delta 丢失注册后以正文 kind 复活（渲染进正文块+dedup 分桶错乱）。两处修正（缓冲时按 id 契约判型 + flush 按型重建）。
5. **flush 零信息守卫**：appendPartTexts 过滤 blank delta（防 text 空串行）。

### 自查误判勘误（诚实记录）

- **误判一（诊断 SQL）**：`text IS NULL` 当空判据——但落库映射 `text=(p as? Part.Text)?.text` 使 **Reasoning 行 text 列恒 NULL（内容住 payload，#79 截断设计）**→ 把 61 条健康行误读为「顽固残留」，追了一圈幻影。
- **误判二（#228 清扫谓词）**：`DELETE … OR text IS NULL` 每次开机**误删全部 reasoning 缓存**（服务器可重拉掩盖了错误）。修正谓词：`text='' OR (reasoning AND text IS NULL AND payload 空文本)。
- 方法教训：跨进程拉 WAL 三件套分段拷贝可能不一致；读数必须 db/wal 同源重放，且对「恒真谓词」先做 schema 对账。

### 验证（终态）

- true_empties（text='' 垃圾）= **0** 且跨重启保持 0；payload 空 reasoning = 0；健康 reasoning 61 条保留；本次开机清扫 0 删（无垃圾可删，也不再误删）。
- 单测：+2 例（mapper 源头滤空保 ordinal / 空 started 不注册+delta 按型重建）；#223 两例断言升级到零注册语义；全量 **1956 绿**。
- 慢滚+快速 fling 录屏帧抽查无重叠；MIUIScout 0 停顿。
- 残留观察：单次 `RESIZE +914px`（t_msg_038cd8c4 turn，每进会话一次）——parts 已净（1 reasoning+1 text），为 reasoning 展开/Markdown 异步首测的正常渐进布局，量级一次到位非重叠源；待用户日常观感裁决，若扰人另立卡片。

### 根因补完（#229，2026-08-26 01:5x，用户质询「修复的是根因吗」）

诚实盘点 #228 的因果树完成度：①炸弹存量（清扫）✓根治 ②回灌通道（对称滤空）✓根治 ③主线程执行（mergeDispatcher）=缓解非根治 ④**dedup 算法 O(N²) 残留**=未根治——#223 的前置跳过只省了 overlaps 文本比较，pair 枚举本身仍二次方（两条新版契约 id 各一次 contains，N=4488 时 2000 万次迭代照样秒级，MIUIScout 栈里 contains 即热点帧）⑤#223 创建封堵未做过正向验证。

**⑤ 验证**（V2 探针会话真实流式轮次 + App 内 SSE 管线跑完后 Room 直查）：空 part 计数 = 1（#223 设计上限「每消息每类 ≤1」，非泄漏），下次启动清扫顺带清除——增量侧有界封死，无累积通道。

**④ 根治**（dedupOverlappingTextParts 结构性改造）：
- isNewPartId 记忆化（HashMap，每 id 至多 2 次扫描）；
- 同类「legacy-id 子集桶」——新版契约 p 只可能与 legacy-id 同类 part 重叠，只扫 legacy 桶（炸弹全为新版契约 → 桶空 → 每元素 O(1)）；legacy p 仍扫全桶，语义与 #223/#109 完全一致；
- 复杂度 O(N + M×N)，M=legacy 条数（迁移期遗留，现实 ≤2/消息）；胜者替换时同步桶成员。

**回归**：+2 例——5000 条互异非空新版契约 part 守时 <2s（二次方时约 2500 万 pair 必红）+ legacy×新版前缀重叠合并语义保持；全量 **1954 绿**；真机复打开原会话 0 停顿、内容 2.5s 内可见。探针会话已 DELETE。

结论：①②④根治 + ③纵深缓解 + ⑤有界验证——「加载慢/乱」因果链各环节均有处置，且无复发通道（创建有界 ≤1、回灌双滤、算法线性、残留计算离主线程）。
## #227 压缩分割线展开态滚出视口即丢（2026-08-26，用户反馈）

用户问：「为啥压缩内容展开之后，一拉到其他地方，就会让展开的内容自动合上？」

根因一行定位：CompactionCard 的 `expanded` 是 item 内 `remember`——LazyColumn 视口外 item 会被整个丢弃（组合销毁、remember 清零），滚回视口即全新组合、默认收起。这不是事件，是 Compose Lazy 容器的生命周期语义。

### 修复（受控组件 + 屏幕级展开表）

- CompactionCard 签名加 `expanded: Boolean` / `onExpandedChange`（受控化，内部 onToggle 保留 canExpand 守卫与 latchedText 逻辑）。
- ChatMessageList 新增 `compactionExpandedStates: mutableStateMapOf<String, Boolean>()`（屏幕级 remember）——滚出视口不丢；离开会话（本组合销毁）即清，Q10「展开态不跨会话记忆」仍成立（刻意不用 rememberSaveable：那会跨导航/进程恢复，语义过头）。
- 三个渲染站点接线：尾部兜底（V2=真实 messageId，与消息流对位线同键——尾部→消息流交接零丢失；V1 空串用固定键 COMPACTION_TAIL_EXPANSION_KEY）、V1 摘要认领分支（msg.message.id）、消息流对位分支（chatMessage.message.id）。
- V1 交接桥（LaunchedEffect(v1CompactionSummaryInList)）：尾部线让位给摘要消息时把展开态搬到真实 id 键再清源键——「完成不收起」（#221 裁决）在 V1 交接路径同样成立。

### 验证（真机 Greeting 会话，V1 通道）

- 展开分割线（要点文本入屏）→ 连划 8 屏滚远（视口外）→ 滚回：**摘要仍展开**（「用户尚未提出任何具体任务…」要点原样在屏），分割线在树 ✓
- 再点一次 → 正常收起（toggle 双向都通）✓
- 全量单测 1950 绿

### 热修（2026-08-26 00:05 用户即报：V1 用户气泡全消失）

初版触发消息隐藏条件踩 Kotlin 空安全惯用陷阱：`firstOrNull()?.summary.isNullOrBlank()` 在**没有** Compaction part 的普通用户消息上求值 = `null.isNullOrBlank()` = **true** → 全部用户消息（V1/V2 通杀）被误判为 V1 压缩触发消息而隐藏。用户在 V1 通道当场发现「看不到用户发送的气泡」，截图 + 语义树实证（助手气泡在、分割线在、唯用户气泡消失）。

修复：显式要求 part 存在——`v1TriggerCompactionPart != null && v1TriggerCompactionPart.summary.isNullOrBlank()`。真机复验：Greeting 会话用户消息（「嗯嗯嗯」/「你好 👋」）恢复渲染，「Context compacted」分割线原位不受影响；全量单测 1950 绿（+2 为 mapper 回归例）。教训：Compose 渲染分支无单测 seam，E2E 验证清单必须显式包含「用户气泡在场」基线项（本次生命周期录屏只盯了压缩元素，漏了全局基线）。
## #224 V1/V2 压缩形态统一（2026-08-25，用户指令）

用户问「能否将 V1、V2 的形态做成一致」。差异根因在服务器语义：V1 compact 产物 = 常规 assistant(agent=compaction) 消息（摘要 text part + step 噪声，渲染为普通气泡）；V2 = 独立 compaction 消息 → Part.Compaction → 分割线。客户端归一化即可统一。

实现（CompactionNormalizer，data/mapper 纯函数）：完结的 assistant(agent=compaction)（completed 非空或 error 存在）且 text 摘要非空 → 整条 parts 折叠为单个 Part.Compaction（summary=全部 text part 拼接、failed=error 存在）。识别条件仅 V1 线形可满足（V2 assistant 不带 agent=compaction），对 V2 零操作直通。完结守卫：V1 SSE 先发 info 后发 text delta，未完结即折叠会把流式半文固化为完成态——与 V2 deltaText 流式语义冲突，故未完结保持原样。

接入双路径：EventDispatcher.upsertMessages（REST/恢复/刷新，normalizeAll）+ MessageEventHandler.handleMessageUpdated（SSE 单条实时，含 parts 重写）。

验证：单测 5 例（成功折叠/失败映射/未完结直通/非压缩直通/空文本直通）+ 全量绿；V1 真机 E2E（7970 字符上下文压缩）：完成态渲染 `Context compacted` 分割线（原气泡消失）+ 点击分割线展开摘要全文正常（协程章节内容正确）。V1/V2 压缩形态至此完全一致：进行中骑线进度分割线 → 完成分割线 + 可展开摘要。

## #225 压缩流式来回跳动根治：消费/揭示失配（2026-08-25 七报，真机像素取证）

用户报「压缩时内容输出来回跳动，像文字输出后做的补偿」。取证设计：V1 服务器（压缩消息=常规 assistant 流式轮次，走 COMP-MSG 通道）+ 96 帧 ×0.125s 高频截屏 + ScrollDiag 同步日志 + 逐帧垂直位移相关分析。

**证据**：修复前 5 次跳动（≥10px）集中流式早期；帧 4 的 **+66px 与单次注入单位完全相等**（defer 日志 inject=66）——铁证指向失配而非方向错误；后期稳定（链式揭示稳态正确）。

**根因（消费/揭示失配）**：scrollToBeConsumed 在 LazyList 测量遍首消费；但 poke 只失效列表测量作用域——若 item 内容与约束未变，Compose **复用其缓存测量**（本节点 layout 块不重跑）→ 该遍消费位移生效、揭示（report 高度）不更新 → 视口下跳一个注入单位；下次内容刷新（deltaText）重测时才揭示 → 回弹。用户看到的「文字输出后补偿」即此：下跳在前、文字+回弹在后。

**修复（版本号订阅，构造性配对）**：DeferredRevealCompensator 新增 `version: mutableStateOf`，注入分支自增；deferredRevealCompensation 的 layout 块内读取 version（require 消费读取）——快照订阅使**本节点**随注入失效，消费遍必重测本节点 → 揭示与消费严格同遍。这比「poke 列表」更强：失效精确落在需要重测的节点上。

**对照实验**：修复前 5 跳/96 帧（38 defer）；修复后 **0 跳/96 帧、168 defer**（上下文 20k、流式窗口更长更重）且视口残差 0.00（像素级冻结——滚离 1/3 阅读旧文期间，锚=item 巨型增长+offset 消费全配对，可见带纹丝不动）。全量单测绿。

### 调研副产物（对 §6.1 开放问题的补充）

本轮实证澄清了调研文档 §1.5 的通道竞争问题：request-position 通道的「间歇丢注入」与本轮的「消费/揭示失配」是**两类不同的时序病**——前者是回写覆盖，后者是节点级测量缓存。scrollToBeConsumed 通道 + 版本订阅配对后，两类均已根治（本轮 168 次注入零丢失零失配）。







