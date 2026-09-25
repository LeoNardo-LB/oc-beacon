# 437-streaming-md-stable-reveal（2026-09-25）

> 状态：进行中
> 关联：（spec 路径，若有）·（issue 编号，若有）
> 来源：用户反馈 / grilling / E2E / 顺带发现

<!-- 过程中的取证/验证证据直接写本文件；backlog.md 只留 ≤3 行卡片。 -->

## 阶段 A-D 实施与真机验证（2026-09-25 下午批次）

**交付**（5 commits，含 cd0a879b）：
- 阶段A：SafePrefixGate 纯函数两级放行（空行毕业/标记回退/setext 扣末行）+ 27 判定用例 + pilot 接线 + STABLE_REVEAL_PILOT 开关（dev先行）
- 阶段B：HeldTailAgingState 超龄状态机（300ms/500ms 节流，8用例）+ HeldTailReveal 锁高降亮区（呼吸光标+min(natural,locked)裁剪）
- 阶段C：gate 归一化增量前移——表格粘边空行注入（与 ensureBlankLineBeforeGfmTables 同判定，幂等）+ 完结变换字符扣留（tasklist ☐☑✅ 与 $$）
- 阶段D：FlapDetector 非前缀风暴探测+重建限频（5用例）+ MDPilot 观测日志（gate放行量/扣留量/超龄时长）
- 真机崩溃修复：流式 snapshot 失配帧（AST[3,29] vs content len0，#432 同族）——heading2-6 越界兜底组件（SafeHeading+safeHeadingText）+ pilot 分支 key(state) 强制实例重建消帧；heading1 的 ATX 提取同护
- 降亮分级：纯文字扣留尾全亮（转正字面无缝=V6 体感），含标记才 alpha 0.5
- 补修 5 处令牌门禁遗漏（Motion/SpacingTokens，含 #435 引入的 2 处）

**真机证据**（小米 houji，DSH-3080 通道）：
1. 流式管线全链路：gate 按批放行（9/36/99/1051/5857ch）→ 库 stable AST 增长（3→6→9→12块）→ 库内 unstable tail≈0（第一道闸生效铁证）→ 降亮区 300ms 超龄触发（heldMs 305-380）
2. markdown 混排回复：H2/粗体/表格/代码块成型完整，held 20ch 完结 flush 上屏（EOF 一字不丢）
3. 贴底构型：drop(fii=0∧fiso=0) 免派发；尾段钉固构型（swipe 300px）：36 次增长全部 reading-away 免派发，视口零扰动
4. 帧差录屏（22s@10fps，220帧）：无基线两态往返翻转对（~350px@2-5Hz 签名消失）；尖峰 7-10 均为单调增长事件（500ms 锁高刷新/空行毕业），尖峰后 0.1-0.4 静默带=新内容稳定停留
5. 全程 0 FATAL（修复前 heading 路径必崩——重放历史即复现）
6. 全量单测绿（SafePrefixGate 27 + HeldTailAging 8 + FlapDetector 5 + 既有回归）；lint 5 error 清零

**环境备忘**：opencode serve 49374 密码随机生成（/tmp/service-test.json + OCBEACON_SERVICE_JSON 覆盖）；DSH 通道=debug_server_type dsh + debug_token(~/.dsh/token)；reverse 4199→49374（debug-entry.sh 默认 4199→4199 需手改）

**V6 人工项**：流式观感（纯文字全亮流出/标记内容按块成型+降亮区过渡）；完结切换瞬间（阶段C 高度差吸收实测）

## 验收三 bug 诊断与修复（2026-09-25 晚批次）

**Bug 2（表格不能复制）——根修 22f3e68d**：
- 定罪链（/diagnosing-bugs 全流程）：dump long-clickable=true（可达性在）→ 探针（事件到达 cell 但 combinedClickable 零触发）→ 双 pass 探针（Main 相 Press consumed=true）→ 移除法（去掉 cell 内 clickableMarkdown 后长按立即复活）→ **根因：clickableMarkdown 的 detectTapGestures 在等待 tap 期间消费 down，同树外层手势（表格 cell combinedClickable 长按菜单 + SelectionContainer 长按选择）全部失效**
- 考古定罪：#429 交付版（d3462892）装回实测长按同样死——菜单**从未在真机工作过**（#429 只验证了 TSV 单测）；用户此前的复制能力=逐字选择，被 #429 的 DisableSelection 移除——「不能复制」实为能力真空
- 修复：clickableMarkdown 手势改为等价轻量实现（awaitEachGesture + awaitFirstDown(requireUnconsumed=false) + waitForUpOrCancellation，仅在命中链接时消费 up）——down 透传，长按/选择语义在外层复活
- 真机双实证：长按表格 → 「复制单元格/复制表格（TSV）」菜单 ✓；长按正文 → 选区把手 + 复制/全选/朗读工具条 ✓
- 注入有效性对照：launcher 图标长按菜单可弹（motionevent 注入有效，排除测试伪影）

**Bug 1（钉固构型闪烁后回位）——修复 8bd5ead1**：
- 根因：转正（空行毕业放行）时降亮区收缩（held 同帧）与 Markdown 正文扩张（snapshot collectAsState 下一帧）两帧错位 → item 净高先减一帧再长回 = 视口单次往返
- 修复：pilot 中 held 收缩侧延一帧（withFrameNanos 等扩张帧落地），增长侧不延——净高度从此单调
- 真机实证（6fps 帧差+方向判定）：钉固后序列无往返对；可疑连续对（11.2/10.3）经净差判定=持续增长（40→43=14.9 增大非归零）✓；fling 后规律单帧增长（500ms 锁高量子）+静止带
- 用户补充确认场景：流式非贴底（上拉阅读）时闪烁——正是此构型

**Bug 3（fling 中视窗跟随 SSE 下移）——未复现，登记观察**：
- 引擎排查清白：flush 任务 isScrollInProgress 每帧 rebaseAll（弃配）✓；GUARD reanchor 0 次；MSGEFFECT 竞态窗口分析（fling 等待+复验 autoScroll）均安全
- 合成复现两轮（80-120ms 快 fling + 流式）未现持续拖动（帧差无连续大差串）
- 推测：可能为 bug1 转正跳变在 fling 中的感知（已修），或需真手指特定时序——待用户验收观察，若复现按 [DEBUG-drift] 日志路径深挖

**顺带登记（未定位）**：真机两次内容区黑屏（渲染层卡死，force-stop 恢复，低频，出现在长操作序列后）——与 #437 改动关联未证实，观察中

**验证**：全量单测绿；0 FATAL；SGR-435 引擎工作正常（39-55 drop 贴底/读历史免派发）

## 验收四~六轮：用户高度固定模型落地与引擎底层重构（2026-09-25 晚）

**用户数学模型（定案）**：reverseLayout 不变量 `scrollPos(t) − ΔH(t) = S₀`——settle 后视口相对「settle 时刻内容底」钉死。绝对位置固定（LazyList 默认）=跟随的充要条件，非不变量。

**落地链（8596515b + b0c90d27）**：
1. 配对规则全域化：增长源在锚之下（itemIndex ≤ anchorIndex）即 +Δ，仅贴底原点（fii=0∧fiso=0）物理免派发。撤销两轮错误修复（深处免派发/视口 offset 锁——都把绝对位置固定当不变量）
2. gate 放行量子化 ≤400ch/批（单帧 1830px 暴涨→≤330px，防 LazyList 锚定校正的吸底-弹回 LEAP）
3. 派发改渲染前反射 requestPosition（对齐 #427 引擎先例：待定位由下一遍 measure 原子消费+拒绘一帧），替代 dispatchRawDelta（渲染后修正=先画增长态再跳位=闪烁，用户定罪「像做补偿而非计算好 set 进去」）
4. **引擎底层重构（用户授权）**：ChatScrollController 流式期间（sessionMeta.isStreaming）MSGEFFECT/GUARD/rearm 全面静默——原为非流式异步增长设计，流式期间运行=每-part requestScrollToItem(0) 拉底（上滑瞬间被拽回）+守卫把配对位移当离底漂移重锚（拉锯震荡）+rearm 在流式结束瞬间补拉（「输出完成后拉一段」）

**测试**：Ledger 用例更新至公式语义（贴底原点/深处/读历史/不在布局四分支+量子化截断用例），全量绿；0 FATAL

**验证局限登记**：adb 注入 swipe 在流式高频重组（48ms/批重排）中被手势取消，始终无法脱离贴底完成非贴底机内验证（真手指不受影响——连续压力事件流）；链路各环节（配对计算/反射 set/引擎静默）逻辑闭环+单测覆盖，**待用户真手指验收**：发送→10s→上滑脱离→静置阅读，视窗应纹丝不动；logcat SGR-435 pair d=.. set(fii, fiso) 行可见每次补偿

## 验收七~九轮（2026-09-25 深夜）：自测闭环——真机像素证伪全域+Δ，两根因修复

**背景**：用户裁决「你要自己测呀，不能只靠我来测」。建立全自动验收管线：DSH 自会话作流式信号（本 agent 输出即手机 SSE 源）+ screenrecord 45s + 定时注入上滑 + ffmpeg 抽帧 + Python 块匹配位移场 + logcat 仪表。

**复现与证伪链**：
- R1（视窗停历史区+下方28kpx增长,零派发）：24s 逐像素冻结（diff=0.0）——LazyList reverseLayout 锚定默认已保持画面。
- R2（贴底+上滑入流式item）：swipe 生效(-526px)后持续漂移/混沌=用户bug复现；SGR 配对零触发。
- R3（仪表化 cb87c077）：note→flush→pair→反射set→下一帧读数回读 全链路工作；但每批 +Δ 派发后像素恰以 Δpx/batch 上拖，且 LEAP off 31252→8480 dOff=-22772（=累计派发精确回吐）→ 乒乓震荡本体。
- 判决：itemIndex ≤ anchorIndex（验收四轮全域+Δ）是双重补偿；spec 注释自己的等式 anchorIndex == itemIndex 才正确——锚下方增长免派发（默认锚定已稳定），仅锚内增长配对。
- 另一根因（R2/R3 sid 漂移）：streamingMsgId（最后一条 completed==null）随在飞 call id 漂移，承载文本增长的 item 组不含 sid → 修饰符脱落 → 裸增长拖走视窗。

**修复（75e602b9）**：① pairedDelta 回归等式；② isStreamingMsg 放宽为组内任一 completed==null；单测同步改语义全绿（全量回归过）。

**R5（等式规则验证）**：fresh entry 直落直播区；贴底原点跟随段像素冻结（f12→f21 diff≈0）；swipe 瞬间 ip=true 正确弃配。遗留：swipe 被同刻 34016px 单帧爆发增长（tail-hold 整体释放打穿 400ch gate 量子化）引发的重排取消——「注入被取消」现象的机制至此定位；锚内配对路径尚未在真机阳性确认（被爆发增长阻断手势）。

**登记待办**：① tail-hold 释放需按批量子化（防单帧 3.4 万 px 爆发）；② 锚内配对真机阳性确认（修①后复测）；③ 会话内分页窗口距最新差数页时入口落点仍偏旧（观察项）。

## 验收十轮（2026-09-25 深夜续）：方向修正与超龄量子化——小批场景满分，突发残差定位

**滑动方向勘误（R5/R6 之谜解）**：reverseLayout 物理模型核对——手指上滑=朝最新方向=贴底时被钳制（R5/R6 swipe 无效果、fii=0 fiso=0 不变的真因，非「注入被取消」）；**脱离贴底的正确注入是手指下滑**（内容下移露出上方旧内容）。历史轮「注入被取消」结论作废。

**R7（正确方向+等式规则）**：swipe 生效（LEAP idx 0→7, 像素 +420px 干净位移），锚落增长 item 内部（fii=7 fiso=222），此后每批精确配对：pair d=66/132 set fiso 354→420→…→2862 锁步无回吐=用户公式逐批成立；**f12→f17 等多段像素 diff=0.0 完美冻结**。残差：停顿冲刷后单帧 pair d=7378（MDPilot aged reveal chars=1091 实证=扣留区超龄一次性落地）。

**修复（b54650c3）**：HeldTailAging 超龄首亮 ONSET_REVEAL_CAP_PX=800、步进 STEP_REVEAL_CAP_PX=1600/500ms 铺开；单测四用例全绿+全量回归过。

**R9（量子化验证+人为停顿）**：阶梯现身（pair d=1600=步进上限精确出现）；小批段 f12→f17 **逐像素零漂**。**残差登记**：① catch-up 期 gate 仍按 400ch/48ms 释放而 measure 滞后聚合（观测 442ms 聚合 7 批=单 note d=6236）；② 大额 set 用 requestPositionAndForgetLastKnownKey 核销锚 key，突发期新 item 插入+重排后 LazyList 按字面 index 重锚（LEAP -7562 视觉大跳，f17→f26 混沌）。两处为下一卡片：「流式突发路径收尾——gate 时间限速与配对 set 保 key」。

**净结论**：用户三症状的主链（全域+Δ 双重补偿拖拽/震荡、sid 漂移裸增长、超龄单帧倾泻）均已修复且有真机证据；稳态小批场景（贴底跟随/历史锚定/锚内深读）三类像素冻结达标。突发路径残差已定位到两个具体机制，待下一轮。

## 验收十一轮（2026-09-26 凌晨）：三问题根修（压缩卡堆积 / 震荡残留 / 块级展示）

用户报三问题：①压缩卡一堆堆在一起；②仍上下震荡闪烁；③块展示应「未闭合零输出、闭合整体出、表格按行」。

### ① 压缩卡堆积（27642dac）
- 真机 Transcript378 实证 `compactions=8 extras=1`：全部卡塞进 display0 after 桶。
- 根因 A：displaySeqs null → Long.MIN_VALUE 哨兵使「比最新消息还新」恒真（流式/本地 id 解不出 seq 常驻 index0）。
- 根因 B（DSH 0.1.7 源码调研 docs/research/dsh-compaction-binding.md）：压缩绑定是 shadowedRange 区间而非信封 seq；卡按信封 seq 排恒落日志尾部。
- 修：TranscriptPlan 只在已知 seq 项中找锚（贴尾语义保持）；CompactionEntry/Summary 事件增 shadowStartSeq（mapper 解析 shadowedRange.start，min 防 start>end），卡片 sortSeq 优先取之。
- 真机复验（新 pid 4688）：`cardSeqs=[cmp:1717/9, cmp:5402/1723, ..., cmp:19904/18321]` shadowStart 全解析、随页加载渐次重锚；底部堆积消失（视觉子代理两屏 0 卡片于中下部）。

### ③ 块级展示（a421410a）
- SafePrefixGate 第二级逐行状态机重写：围栏块开栏整体扣留、闭栏整块放行（跨批 fenceStateAt O(n) 恢复状态）；表格表头+分隔行整体放行后逐行渐显；普通完整行放行；超龄降亮区（HeldTailAging reveal）整体关闭。
- 3582→3506 单测全绿（表格/围栏/aging 用例按新语义重钉）。

### ② 震荡残留（817607b4）
- R9 LEAP -7562 根因落锤：requestPositionAndForgetLastKnownKey 丢 lastKnownFirstItemKey（javap 钉死实名），插入/重排按字面 index 重锚。
- 修：反射探针增可选字段，flush 相溢出换算落点可见时同帧回写 key；缺失自动降级。LazyListReflectionTest 冒烟钉死。
- 真机冒烟：贴底流式全部走 drop（免派发物理跟随）分支，零 LEAP、零大额 set。

### 环境备注
- gradle 需 LANG=en_US.utf8（默认 locale 使 kotlinc 中文类名乱码 → NoClassDefFoundError 假失败）；JAVA_HOME=/home/linuxbrew/.linuxbrew/opt/openjdk@21。
- ADB=/home/linuxbrew/.linuxbrew/bin/adb；启动 Activity 实名 dev.leonardo.ocbeacon.MainActivity（非 ui.MainActivity）。

待用户真手指验收：震荡体感、压缩卡随消息上推、代码块/表格展示节奏。

## 验收十二轮（2026-09-26 深夜）：步间空窗门控 + 纯文字增量直出 + VTRACE

用户复测反馈：仍有明显来回跳动（像补偿逻辑）；纯文字被整段扣放而非流式直出。用户裁决：精细化分析用日志而非录屏（瞬态闪烁录屏易漏采）；深改期间确认高度配对必须是反射 set 而非补偿。

### 审计确认（用户问题：是反射还是补偿？）
- 流式文本增长通道确证为反射 set：flush 日志 pair d=.. set(fii,fiso) 即 requestPositionAndForgetLastKnownKey 预定位 + 拒绘一帧 + 下一遍 measure 原子消费（PreRenderCoordinator FLUSH 契约）——非渲染后补偿。
- 但审计揪出三条漏网/旁路：①MSGEFFECT/GUARD 门控直读 sessionMeta.isStreaming，步间 SseStatus force-complete 闪断（logcat 01:04 实录 Busy/Streaming→Idle [force-complete] → [meta] streaming true→false）放行锚底；②LaunchedEffect(revealBannerCount) 的 requestScrollToItem(0) 完全无流式门控；③ChatMessageList 七处横幅揭示门控用 streamingMsgId!=null，步间闪断会触发 CardExpandReveal episode（dispatchRawDelta 配对族）。

### 三笔根修
1. **629ce9fa 回合级 turnActive**：ChatViewModel.turnActiveState = isStreaming OR 存在未完结 assistant 消息，下降沿 3s 宽限防抖（flatMapLatest+debounce）。ChatScreen 滚动门控 + 七处横幅门控 + revealBannerCount 锚底全部换用。
2. **8c1c3b2a 纯文字增量直出**：旧语义（#437 首版行扫描继承）对无换行中文长段落=整段收完才放=整段跳出（用户投诉点）。重写：纯文字行完整行整放、未完行增量放行至快照尾（字面=最终，库第二道防线）；400 预算内联扣减；行首守卫防增量截断后的行续段误判表头/围栏；fenceStateAt 整行读取。31 例用例重钉，3508 全绿。
3. **3eaf1ac0 VTRACE**：flush 头部变化探针，fii/fiso 每变化打一行，与 pair/drop/MSGEFFECT/GUARD/BANNER 交错可读成因。

### 自测取证（VTRACE 时间线）
- 拖拽轨迹逐帧单调（526→1926、1926→112），无跳变；
- 8s 停留（fii=7 fiso=1926）流式期间 VTRACE 全静默=视口冻结无漂移；
- 全窗口零 MSGEFFECT anchor / GUARD reanchor / BANNER fire（新门控生效）；
- 单批量子观测：d=888、d=842 ≈ 400 字符预算上限的 CJK 批（~15 行/850px/帧）——relay 突发+预算上限共同决定；若后续仍觉跳感可下调预算或改 px 基。
- 用户可见的来回跳动在两次合成复现（远滑/轻滑）中未重现；VTRACE 已就位，下一轮用户复测日志可直接定位任何一帧跳动的成因。

### 环境注记
- 装机会杀 app 进程——录屏期间禁止 install（jump_audit.mp4 白录教训）；日志法为主（用户裁决）。

## 验收十三轮（2026-09-26 02:0x—02:2x）——日志实锤：闪烁=可见条目高度振荡，非视口通道

**用户裁决与方法论**：视觉仍见闪烁；用日志捕捉"重复性高度变化"；在可疑位置/字段打仪器。

### 证据链（用户复现会话 /tmp/flicker-logcat-2026-09-25T17-56-54.txt，01:54:35–01:55:22）
- VTRACE 527 帧仅 1 次 1px 回弹签名 → **视口滚动位置全程稳定**，闪烁不在滚动通道；流式中段（01:54:57.6–01:55:07.7）pair d=66/74 锁步零异常。
- **ScrollDiag RESIZE 异常 5 条**（全 session 仅 5 条非流式项）：
  - `01:54:35.923 t_dsh-call-call_77 4958→774 (−4184) dispIdx=2`（可见位置塌缩）
  - `01:54:36.202 t_dsh-call-call_77 774→5648 (+4874)`（280ms 后弹回，**塌缩-弹开对**）
  - `01:54:36.247 t_dsh-call-call_a0 294→2020 (+1726)`；`01:54:40.666 t_seq-… 3063→7505 (+4442) dispIdx=0`（滚动中单帧暴涨）
- 触发链：`listMessages 113/237 分页` → `Capped 2719→1000 msgs (dropped 1719)` → 计划重建 `ENTRIES 43→58` → turn 组重派生（grp=42）→ SliceHost `warm=false` 多帧重测（1558→1186→1294→1798）→ `Choreographer Skipped 35 frames` + GC 100MB → `RB-EXP mapHit=false ×16`（思考块展开态集体失配）。
- 回合结束（01:55:20.175–22.3）：`dsh-t27s1` 被 `MessageRemoved`+换装 `seq-…-275`（条目身份交换）→ L3 REST 双刷新（51→63 msgs，ENTRIES 51→56）继续插条目。

### 根因（代码定位）
1. `StepGroupCard.heavyComposed = remember(step.msgId){false}`（MessageCardAssistant.kt:1286）——组重派生改组首消息 id 时把数千 px 组体打回 24dp 桩一帧；
2. 片高账本 `rememberSaveable("sg_ledger_"+msgId)`——key(item.msgId) 子树重建/流式平铺↔折叠分支互换时整本蒸发（saveable 不跨分支互换）→ Σ 桩缺失+多帧重测爬升；
3. 分支结构不同构（isStreaming 平铺 vs 折叠切片）——互换即整树重测量；
4. 序数 part id（`{msg}_{kind}_ord_{n}`，PartIdContract）——重排后指纹/展开态全失配（放大 1/2）。

### 修复（本批，均根因向）
- **根修一**：heavyComposed 撤除 msgId 键（子树存续期恒保持已组合）；
- **根修二**：桩帧高度=账本 Σ+片间距（stubHeightPx，全暖时逐像素对齐窗口宿主总高式；全冷退 24dp——真首组合通常屏外）；
- **根修三**：账本改挂进程级 LRU 店 StepGroupLedgerStore（128 键，分支互换/回收/重派生全存活）。

### 仪器（grep HFLICK 一键清理；全部 DEBUG 门控）
- `[DEBUG-hflick] STEP`：StepGroupCard 身份漂移/子树重建（msgId old→new、grp/slices/warm/heavy/ledgerTotal）；
- `[DEBUG-hflick] BRANCH(+chunk)`：流式平铺↔折叠分支翻转；
- `[DEBUG-hflick] PLAN`：计划锚键序列 diff（n、firstDiff@、add/rem——插拔/位移定位）；
- 既有 ScrollDiag RESIZE（条目高度变化，含正负）与 VTRACE（视口）继续在线。

### 验证
- 单测 3513 全绿（新增 5：店同实例跨分支互换/按键隔离/LRU 逐出/桩高 Σ 对齐/冷与失配 null）；
- 装机自测（pid 25343）：翻历史即捕获 `(d=+12928)` 单帧暴涨、`PLAN n3->3 firstDiff@0 add/rem`（底部身份交换，恰在其后 1s）、`t_dsh-call-call_01 +8112→−420→−326→+392` **可见条目反复高度振荡**——仪器与修复均在真机生效；进程零崩溃零 ANR（后台被系统杀为电池限制，与改动无关）。

### 遗留（下一轮仪器收割后定位）
- 流式大项（平铺路径不分片）在步边界/分页时 ±数百 px 内容级振荡（RESIZE −420/−326/+392 族）——非本批三修覆盖面，属异步解析/内容重派生不稳定；
- 回合结束 dsh→seq 身份交换的底部整泡换装闪烁；
- 消息表 Capped 2719→1000 截断时机（分页+截断叠加触发重建风暴）。

## 验收十四轮（2026-09-26 02:2x—02:4x）——二次复现归因：主线程冻结风暴（预热+归档），非几何通道

**用户复现**（flicker2 日志，pid 28752 新包）：VTRACE 58 帧零回弹、RESIZE 41 条全正增长且全在流式消息（十三轮三修生效——塌缩-弹开对消失）、MDPilot stable 零回退。**闪烁仍在，但几何/解析通道全净——归因转移到主线程冻结-追帧**：

- `Skipped 80/68 帧` + `Davey! duration=1199ms`（02:21:39.3-40.0，发消息后）；
- **WebViewWarmer Warm-up**（39.240-40.074）：进会话 500ms 后在主线程装载整个 Chromium（WebViewFactory/nativeloader/vulkan/Adreno/编解码枚举 830ms）；
- **MessageStore 归档风暴**：`upsert n=2 tx=7072ms archive=102039ms`（1629 条单事务 102s 写锁，并行 replace txTotal=101040ms）→ 主线程 Room 读被饿死；
- JIT 首开编译 ChatMessageList 16MB + ChatScreen 6.5MB（debug 无 AOT）；
- 期间 InsetsController show(ime()) 反复——键盘 insets 动画与冻结-追帧叠加 = 用户"来回闪烁"体感。

### 修复
1. **WebViewWarmer 空闲窗启动**：内部改为「初次延迟 4s + 主线程队列空闲点（addIdleHandler）」才装载；8s 无空闲兜底强制。自测：启动于空闲点、102ms 完成（原 830ms 中途风暴）。
2. **归档分块短事务**（ARCHIVE_CHUNK_MSGS=200）：每块独立事务（upsertAll+精确 pruneToLimit(LIMIT+剩余)），块间让出写锁；终块回 LIMIT 终态不变。单测：overflow 450 → 3 块、目标 1250/1050/1000 递减（状态化桩与调用序解耦）。

### 验证
- MessageStoreTest 34 绿（+1 分块排水）；全量 3514 绿 + assemble；
- 真机自测（pid 30521）：二次进入会话 **0 帧跳过**（稳态全净）；首开仍有 30-57 帧（新进程 JIT 一次性，debug 特有；baseline profile 另行登记）。

### 遗留
- 首开 JIT 风暴（baseline profile/PGO 批次）；
- 流式大项内容级 ±300-400px 重测振荡（十三轮遗留，HFLICK/RESIZE 探针持续收割）；
- 回合结束 dsh→seq 底部整泡换装。
