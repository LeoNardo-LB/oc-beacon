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

## #224 V1/V2 压缩形态统一（2026-08-25，用户指令）

用户问「能否将 V1、V2 的形态做成一致」。差异根因在服务器语义：V1 compact 产物 = 常规 assistant(agent=compaction) 消息（摘要 text part + step 噪声，渲染为普通气泡）；V2 = 独立 compaction 消息 → Part.Compaction → 分割线。客户端归一化即可统一。

实现（CompactionNormalizer，data/mapper 纯函数）：完结的 assistant(agent=compaction)（completed 非空或 error 存在）且 text 摘要非空 → 整条 parts 折叠为单个 Part.Compaction（summary=全部 text part 拼接、failed=error 存在）。识别条件仅 V1 线形可满足（V2 assistant 不带 agent=compaction），对 V2 零操作直通。完结守卫：V1 SSE 先发 info 后发 text delta，未完结即折叠会把流式半文固化为完成态——与 V2 deltaText 流式语义冲突，故未完结保持原样。

接入双路径：EventDispatcher.upsertMessages（REST/恢复/刷新，normalizeAll）+ MessageEventHandler.handleMessageUpdated（SSE 单条实时，含 parts 重写）。

验证：单测 5 例（成功折叠/失败映射/未完结直通/非压缩直通/空文本直通）+ 全量绿；V1 真机 E2E（7970 字符上下文压缩）：完成态渲染 `Context compacted` 分割线（原气泡消失）+ 点击分割线展开摘要全文正常（协程章节内容正确）。V1/V2 压缩形态至此完全一致：进行中骑线进度分割线 → 完成分割线 + 可展开摘要。






