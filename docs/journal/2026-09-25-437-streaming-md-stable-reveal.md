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

## 验收十五轮（2026-09-26 02:5x—03:2x）——第三轮复现·四路子代理系统分析：发射隔离 + pending 幽灵写序竞态

**用户指令**：委派多 subagent 多方向系统分析；修复必须根因性质，拒绝打补丁。

### 四路并行分析结论（glm-5.3-flash ×4）
- **UI 失效链**：chatEntries remember 以 displayItems 实例为键 → 每批全量重建 entries → item lambda 全体重执行；叠加 lambda 内直读每批必变状态 + 未 memo 回调，击穿 renderableTurns 实例缓存（修复位置已登记卡）。
- **日志指纹**：SGB ENTRIES 每秒次数 == MDPilot append 每秒次数（逐秒恒等）；93% append 为 1-5ch；同 id 气泡 35s 重组 524 次；回合结束后 append=0 而重组仍 24-34/s（全局噪音路径）。
- **渲染幂等性**：流式 Markdown 逐帧像素幂等（前缀差分 append、stable 单调、无 Loading↔Success 翻转）——**闪烁不在渲染层**。
- **视觉元素枚举**：S1 QuestionCard 锚迁移（本会话无 question 事件，排除）；S2 推理脉冲/S3/S4 计时文本=设计内活动指示；**证伪注入卡/压缩卡展开态与 qEntered**。

### 本轮两个实锤根因与根修
1. **发射隔离（L1）**：`MessageListState.partsByMessageId` 原样携带全局 parts 映射（`getAllPartsMap()=裸 eventDispatcher.parts`，无过滤无 distinct）——后台会话流式落库 → 状态结构不等 → StateFlow equals 去重被击穿 → 可见会话以全局写库速率整体发射重组。**根修**：收窄为本会话消息稀疏投影（唯一消费者语义不变）。曾试 sample(48) 节流——破坏测试缝且属节流补丁，撤销（根因在作用域泄漏，内存 parts 本就 48ms 批处理）。
2. **pending 幽灵写序竞态（L5）**：播种 upsert（合并缓冲，250ms 时延批）与拆除 delete（旁路并行协程，batchScope=Dispatchers.Default 多线程）无顺序保证——真机铁证：delete 42:57.519 先行、upsert 事务 42:57.686 后到重插 → 幽灵行留存热表 → REST 刷新回灌复活（`u_pending-…f74` 挂屏 6 分钟，1956 行日志）。**根修**：删除并入单写协程 + 事件时间最后操作语义（拆除先从合并缓冲撤下未写行；同 id 再到达撤销待删）——写序竞态构造性消除。

### 验证
- 单测 +4（撤下未写行/删除后重到达复活/in-flight 后串行删除/既有异步删除保持）+ 既有 Delegate 投影测试按稀疏语义修正；3517 全绿。
- 真机装机（L1）：20s 空闲窗口 InjCard=0、ENTRIES=0、attachmentScan 0.15/s（风暴期 12-17/s → 归零量级）。

### 遗留（已登记/沿用）
- L3 重组隔离卡（P2，见 backlog）——性能债非闪烁源；
- 首开 JIT（baseline profile）；流式大项内容级 ±300-400px 重测振荡；回合结束 dsh→seq 底部换装。

## 验收十六轮：VPT 帧级取证——稳态配对原子性证实 + 回合结束换装跳变实锤

- 帧级探针落地（commit 13812110）：VPT 无阈值视口轨迹（含锚 item 身份）、RESIZE/SGR-435/VTRACE 统一 elapsedRealtime 时间戳、ChunkDiag 放开流式门控；补齐 LEAP 350px 阈值与 VTRACE flush 帧采样两个盲区。
- 环境修复：opencode2.service 因包名迁移（9/24）二进制路径断裂崩溃循环，且 2.0.16 已无 /api/health（应用 V1/V2 双探针皆败）；用户裁决切 3080 DSH 服务器，经 scripts/dsh-pair.sh（debug_server_type=dsh + debug_token）配对成功。服务文件已指向 linuxbrew bin 符号链接并停止（opencode 版本待议）。
- 协议执行：DSH 会话 session-8fcf8e25，glm-5.3-flash 纯文本，快拖无 fling 上滑 1/5 屏（T+10.9s），logcat /tmp/vpt-log.txt 共 1488 行。
- 稳态判读（I2' 契约逐事件核验）：手势后稳态窗 53 次 RESIZE 对 54 VPT 对 52 pair，全部 17ms 内同帧、d 值精确一致（66/74/62/147…），PAIR_vs_VPT 失配 0，fiso 单调。纯文本流式配对是原子的，先渲染后补偿在此场景被证伪。
- 回合结束换装跳变（唯一 I2' 违规，实锤）：13:32:03.307 流式临时 item t_dsh-t2s1 高度 4961->0（移除），PLAN add=[cf-...-23] rem=[t_dsh-t2s1]（终态键换装为 remove+add 非原子）；锚点在流式 item 上，LazyList lastKnownKey 重锚 idx7->9、fiso 4858->4466，可见位移 -392px 单次跳变（终态渲染与流式渲染高度差）。
- 附带发现：贴底跟随期 13:31:36 一记 230ms 动画滚动（off 18->134，GUARD/animateScrollToItem 族嫌疑）。用户主诉的每新行推-回弹在本轮（DSH+纯文本+当前构建）未复现；嫌疑收敛至：工具/推理卡高度行为、chunk 分裂期阅读、opencode 管线路径（服务器修复后测）。
- 固化 scripts/stream-flicker-test.sh（用户裁决）：发送消息->等10s->快拖无fling上滑1/5屏 一键执行，后续验收复用。

## 验收十七轮：高度引擎三件套全绿——阅读态泄漏清零+换装跳变归零

- 引擎三件套落码全绿：①一帧缓冲帽（增长当帧 clip、pre-draw 单事务 {帽+滚动} 同 pass 原子释放；VDRAW 实证 reject-draw 对 item 层重绘无效后弃用）②槽位锚键（turnKey 锚轮 user 消息，dsh 宿主/终态换装零漂移）③视口写入单点网关（GUARD/MSGEFFECT/BANNER 收编，SGR-GATE 打点）。
- 关键修复链（真机迭代）：DSH 流式轮被旧 isStreamingTurn 判定分片绕过帽（streamingMsgId 恒 null）→ 组内 completed==null 同源放宽；锚键 firstOrNull 与 fii 错位 → 按 index 反查；完成跳变 → 帽宽限保持至新流式项。
- 验收取证（stream-flicker-test.sh + VDRAW 绘制相位分类判决）：vd10 阅读态 H_ONLY/O_ONLY=0/0（基线 62/62）、paired=true 原子配对实证；vd13 全文 293 appends 至 2914px 自然收尾，回合末 PLAN/LEAP/RESIZE/杂释放全部为 0（修前 rem+add+塌0+LEAP-392）。贴底跟随全程健康。
- 单测 3529 全绿（新增 TurnSlotKeyStabilityTest 5 例 + ReserveReleasePlanTest 7 例；分片夹具补 completed 位）。commit：5537ebd2→(帽)→(补修+宽限)→(③网关)。

## 验收十八轮：锚 index 语义终修——浅滑/深滑双姿态泄漏清零

- 终局三连修（日志逐一定罪）：①流式判据排除 user 消息（completed 恒 null 致 user 项与流式项双帽共 state 互踩 reset，「附而不释」真因，RESERVE 双 measure 交替实锤）②贴底原点阈值 100->8px ③配对锚 index 语义（锚≤增长项即配对——横幅区浅滑位 fii=0 为 banner 条目键失配致漏配对；读历史位免配对防原生保持+配对双重修正下坠）。
- 双姿态终验清零：浅滑位（z3：READ 0/0，修前 66-69）＋深滑位（vd10：READ 0/0 + paired=true d=364 原子配对）；回合末（vd13）PLAN/LEAP/RESIZE 全零；贴底/手势帧全程正常。单测 3529 绿（ReserveReleasePlanTest 扩至 8 例锚语义）。
- commit 链：ddad975c（互搏根修）→ 阈值 8px → 锚 index 语义终修。

## 验收十九轮：壳层探针破幻影——三姿态泄漏全清零

- 深读位「泄漏」破案：VDRAW/RESIZE 探针原挂帽内层（padding 内侧），读到内容全高而非帽后壳高——被 clip 的不可见增长被误计为推帧（vd4th 70/70 实为幻影）。探针移至链首壳层后：深读位（半屏上滑）READ=0/0 且 ATOMIC=7（壳高与 offset 同帧落地，I1 契约直接可见）。
- 三姿态终态：浅滑（z3 0/0）、中滑（vd10 0/0+paired）、深读（vshell 0/0+ATOMIC）全清零；回合末（vd13）全零；贴底/手势正常。单测 3529 绿。

## 验收二十轮：所有权主张制终修——引擎复活、帧账全清

- 第五连修：帽所有权主张制——宽限项与新一轮流式项共主互抢（vr：measure 7907/8658 交替、releases 归零定罪），修复为仅物主项可写 trueHeight、非物主只读。vg 终验：引擎复活（releases=4、ATOMIC=5 壳高+offset 同帧），滑动窗口为纯手势轨迹、双探针交替记账为旧宽限/新流式两 item 各自稳定高度（delta48=padding），滑动停稳后零变化——无泄漏。
- 采样方法论沉淀：会话上下文饱和（模型停摆）与电池优化掐连接是采样空转两大外因；每轮用新会话（你好预热+改名精确导航）是可靠配方；用户侧建议关闭 app 电池优化。
- 二十轮至此：三件套+五连修全部落地，深/浅/中位与回合末均有清零实证；#440/#437 迁移待用户验收。

## 已完结卡片迁入（2026-09-26）

### **#440 回合结束换装跳变：流式临时键换终态键非原子致锚点重锚 -392px** `sse-scroll` `#437`
  - VPT 帧级实锤：t_dsh-t2s1 高度 4961->0（remove）与 cf 终态键 add 非同帧原子，锚点在流式 item 上时 LazyList 重锚 idx7->9 可见位移 -392px（2026-09-26 /tmp/vpt-log.txt 13:32:03.307-330）
  - 修法方向（对齐 I2 底原点预留契约）：换装须键稳定原位切换或帧前预留终态高度，禁止 remove+add 裸落地；与 #435 配对机器同域
  - 设计文档：docs/specs/2026-09-26-437-height-engine-redesign.md（VDRAW 绘制泄漏 83 帧证据基线）
  - 终验遗留边界（2026-09-26 vz/z2 取证）：浅滑阅读位（离底≈横幅区高度）时 fii=0 为 banner 条目，锚键比对失配、释放落 unpaired、可见推帧仍存；深滑位（fiso≈360，vd10）READ=0+paired=true 已达标。修法方向：锚语义按增长项 index==fii 或横幅区高度感知。会话 C（session-27446362）可复现。
  - 十八轮终修后双姿态清零（浅滑 z3 READ=0/0、深滑 vd10 READ=0/0+paired），遗留边界已消除——待用户验收迁移。
  - 迁入依据：仪器可断言类按 ai-acceptance-workflow §1 AI 真机验证收口：VDRAW 三姿态清零（浅/中/深）、回合末全零、贴底回归、3529 单测；人工体感清单见二十轮后注（backlog.sh migrate 2026-09-26）

## 收口：#440 迁入与人工验证清单


- 收口裁夺（ai-acceptance-workflow §1：验收按仪器可断言性分类）：本弧判据全部为仪器可断言面（VDRAW 帧级泄漏计数、回合末结构事件、单测、贴底回归），AI 真机验证即收——证据链见十六至二十轮。#440 依此迁入 journal。
- 人工验证清单（V6，主观体感残余项，供用户日常使用中复核，不阻塞收口）：①流式长文浅滑阅读的体感平滑度；②深滑读历史手感；③贴底跟随与 FAB 吸附手感；④跨日长会话稳定性。若有异样：报位置与时刻，探针（VDRAW/RESERVE/SGR-GATE）复检路径已固化在 scripts/stream-flicker-test.sh。
- 探针留存说明：VDRAW（壳层绘制相位）/RESERVE（帽 measure/flush）/SGR-GATE（网关派发）为 DEBUG-only 常驻，生产零开销。

## 验收二十一轮：用户反馈两回归——底对齐+滚动暂缓双修


- 用户验收反馈两回归定罪与修复：①贴底统计栏一跳一跳=帽 child 顶对齐致 clip 溢出朝屏底（统计栏恰在内容底，每 48ms 裁一帧弹回）——改底对齐 place(0, h-childHeight)，溢出朝上裁视口外旧文本、内容底钉死；②流式滑动 fling 卡顿=单体流式项 48ms 全量重排版与滚动帧抢主线程（输出完毕延迟分片后即顺）——ScrollHold 暂缓：isScrollInProgress 期间 pilot 不 append（prev 不动），settle 后 LaunchedEffect 复触发整段一次追平（一次重排版）。
- 仪器判决（会话H /tmp/vi.txt）：手势窗内 appends=0、settle 后即刻恢复 372 次、无积压异常；贴底帧序正常。统计栏体感待用户肉眼复核。
- commit：fix: 帽child底对齐(贴底统计栏弹跳)+流式滚动暂缓(fling卡顿——settle后整段追平)。全量单测 3529 绿。

## 验收二十二世：双修判据证据闭环（fling 帧间隔+底对齐机制）


- 双修判据证据闭环：①fling 帧间隔（vi 日志 VDRAW 帧序）：流式+手势窗 p50=22ms/p90=58ms/max=75ms、零 >100ms 帧——滚动暂缓后 fling 期无重排版长帧；稳态 p50=7ms（>100ms 间隔为变化触发探针的增长间隙无帧期，非掉帧）。②统计栏底对齐：机制性消除（clip 溢出方向反转），贴底帧序健康；主观体感归 V6 人工清单（用户肉眼复核）。
- 目标判据满足，收口。探针/脚本/档案链：vi.txt（419 appends+手势窗）、scripts/stream-flicker-test.sh、journal 十六至二十一轮。

## 验收二十三世：三问题闭环——随态对齐+思考计时即停+回合末双位判零


- 三问题闭环（用户验收反馈）：A 阅读态闪烁回归=底对齐引入（底钉增长每 append 全块上推，配对释放才补偿=中间帧推-回）——修为对齐随态切换（贴底=底对齐保统计栏钉死；阅读/读历史=顶对齐=二十轮验证几何），flush 逐帧按 fii==0&&fiso<8 置位。B 思考计时不停=DSH reasoning part time.end 等整轮 idle 才落——轮级 textUnderwayInTurn（任一 Text part 有内容且未完结）穿线 PartContent，正文接替即停计时。C 回合结束跳消息=累积修复链治愈（当前构建双位判零）。
- 终验证据：会话J（/tmp/vm.txt 贴底短回复回合末全零；/tmp/vn.txt 阅读位 READ 0/0+ATOMIC=52、settle 后零事件）。单测 3529 绿。B 视觉确认留 V6（下次思考回复肉眼核）。

## 二十四世轮：流式滚动卡顿归因取证（进行中）



## 二十四世轮（2026-09-26 晚）：流式中非贴底滑动卡顿——归因取证（进行中）

**用户报告**：闪烁已修复（对齐翻转零位移化验收通过）；新问题=assistant 输出期间非贴底滑动"有点卡顿"。

### 已确证（仪器证据，gfxinfo p90 / framestats 阶段分布）

| 条件 | p50 | p90 | p95 | janky(legacy) |
|---|---|---|---|---|
| 非流式·浅处 | 6ms | 8ms | 9ms | 6.78% |
| 非流式·深处（fii 同实验组） | 6ms | 8ms | 9ms | 6.32% |
| 流式（2500字·慢速） | 7ms | 24ms | 28ms | 36.7% |
| 流式（3000字·活跃） | 8-18ms | 46ms | 57ms | 47.3% |

- 位置排除（深处非流式与浅处一致）→ **流式状态是劣化必要条件**
- 慢帧锁定 SSE 48ms 批节奏（重帧占比 ≈ 1/6 帧 ≈ 更新节律）
- framestats：**input(帧开始延迟) 6-12ms + traversal 8-11ms 暴涨；draw/sync/swap 全部与基线持平 → 绘制/GPU 无辜**
- 滚动窗口内探针全静默：MDPilot append=0（ScrollHold 生效）、RESERVE measure=0、DEBUG-jk entries 重算=0（flag on 时）

### 已证伪假设（重要负结论）
1. ~~chatEntries 全量重建成本~~：探针实测 buildChatEntries 0-1ms（n=77, max=1ms）
2. ~~UI 快照重组链~~：滚动期快照冻结（rawMessages 冻结，commit f32e6a7e，A/B 开关 debug.ocbeacon.jankhold）机制完美生效（holding 期间 entries 重算=0）但**帧率无改善**（p50 反而 8→18，速率混淆下无差异）
3. ~~绘制/GPU 层~~：framestats 三阶段持平基线

### 剩余主嫌疑（下轮 Perfetto 定位）
- **SSE 48ms 批管线的主线程涟漪**：MessageEventHandler 批处理本身在 Dispatchers.Default（不占主线程），但 StateFlow 发射→collectAsStateWithLifecycle（主线程）→ messageState 新实例 → **ChatMessageList 因 messageState 参数未冻结每 48ms 重组**（3000 行函数体顶层重跑）——冻结实验只冻了 rawMessages/displayItems，messageState 旁路未冻
- Default 线程 CPU 竞争（input 段=帧开始延迟 6-12ms 最像调度让位）
- AppLogger 观测成本（flush task 滚动中每帧 1-3 行日志）

### 工具与固障
- framestats 解析器 /tmp/jk-fs.py（FrameTimeline 24 列格式）；滑动序列 /tmp/jank-seq.sh
- DSH 会话队列不稳：每会话第 3 turn 起 stall（续写类 prompt 必挂）；新会话首 turn 秒起——测试轮换用新会话
- debuggerd -j 权限拒；perfetto 配置文件 SELinux 拒（改 stdin 未及验证）

### 状态
- 冻结修复 f32e6a7e 保留（默认关，语义正确无害，诊断价值）；[DEBUG-jk] 探针 741a5dc1 保留待 Perfetto 轮
- 下轮：Perfetto trace（stdin 配置）采主线程占用源 → 定罪 → 修复 → 移除 DEBUG-jk

## 二十四世轮续：cadence裁决落地


### 二十四世轮·续（同晚）：用户假设确证 + cadence 裁决落地

**用户裁决性观察**：「像是流式输出导致高度变化的时候卡顿」——与仪器数据吻合：
- 伴随 append/measure 的帧间隙 p50=72ms（无伴随 12ms）——高度变化帧确为重帧
- 贴底跟随帧 p50=18ms（EXP2 gfxinfo）=「一顿一顿」的直接来源

**根因定性**：每 48ms 批的「append→markdown 排版→cap 全子树测量→布局→配对滚动」全链成本压在单帧主线程。

**落地**（spec 2026-09-26 已裁决「引擎接管 SSE cadence 48ms→100ms tunable」）：
MessageEventHandler.streamFlushIntervalMs()——默认 100ms（重帧频率减半），DEBUG 可调
setprop debug.ocbeacon.streamflush <ms>（16-500）。

**验证状态**：装机完成；append 间隔仪器验证与贴底跟随帧对比被 DSH 队列 stall 阻断
（今晚每会话第 3 turn 起必挂），待队列空闲补采或以用户体感验收。

**遗留（下批）**：单帧成本根修（cap 测量增量化/排版异步化）——登记 backlog。

## 二十四世轮终：冻结语义补全


### 二十四世轮·终（同晚）：用户裁决「要根因不要补丁」→ 冻结语义补全 + 观测去效应

**推理修正**（关键）：Exp-2「冻结 rawMessages 无效」并未证伪重组假设——冻结实验
漏了 messageState 传参旁路（每 flush 新实例 → ChatMessageList 整体重组，三千行
函数体顶层重跑）。滚动期快照静止语义此前只冻了 rawMessages/displayItems，
主通道 messageState 一直裸奔。贴底跟随帧 p50=18ms（holding=false 时冻结不生效）
与滚动帧 trav 8-11ms 同源于此通道 + append 渲染链。

**根修落地**（默认启用）：
1. messageState 纳入滚动期冻结（ChatScreen jkMsgState；settle 后原子追平）
2. JankHoldGate 默认开（回退通道 setprop debug.ocbeacon.jankhold 0）
3. VTRACE 观测者效应消除：滚动中每帧一条 logcat 写（主线程 I/O）→ fiso 变化
   限频 200ms（fii 跃迁即时保分析能力）
4. （前节）SSE flush cadence 48→100ms

**待验证**（DSH 队列今晚全线 stall，仪器验证顺延）：
- 贴底跟随帧 p50 期望 18ms → 显著回落
- 滚动帧 p90 期望 24-46ms → 8-12ms
- 若冻结补全后仍慢 → 主犯转入 append 渲染链本身（cap 测量/markdown 排版），
  届时 Perfetto 定罪后走「增量高度协议」重工程（稳定块高度缓存+尾块单测）

**装机**：完整根修版已 install；flag=1。

## 二十五世轮：双轴架构深审定罪


## 二十五世轮（2026-09-26 深夜）：双轴架构深审——根因架构级定罪

**用户裁决**：要求根修而非缓解；模块应优雅/简洁/自洽/扩展性好/稳定性高；DSH 服务正常
（app 长连接断连是独立 bug，登记 backlog；"队列 stall"实为断连误诊）。

**双代理审查**（架构深审 + Spec 符合度）与主线独立判断三方印证：

### 根因定罪（18-46ms 结构性来源，架构级）
1. **帽协议把测量堵死在全量档**（贴底 p50=18ms 主源）：一帧缓冲帽每批必须全子树重测
   真高——「用重测换原子性」的协议性代价，增量测量与帽的「当帧真高」需求结构性冲突。
2. **100ms 周期全链重组未收口**（非贴底滑动 p90=24-46ms 主源）：JankHold 冻结只盖
   messageState→rawMessages 一条链；ChatMessageList ≥6 个每 flush 变化的 collect 点 +
   displayItems 参数链使 2825 行组合体每 100ms 重跑；holding 在 pre-draw 置位漏滚动首帧。
3. **每帧常驻微成本长尾**：空账期 reserve 块 O(可见项) 扫描 + 49 个 DEBUG 日志点 +
   Default 线程 isStaleDelta O(文本) + 每 100ms SystemProperties 反射。

### 架构缺陷清单（择要，全文见两份审查报告存档）
- A1/A2 配对规则双轨语义矛盾 + 同帧双滚动 set 互相覆盖（架构级）
- A5 五机制交互矩阵不可穷举（二十轮补丁收敛的根由）
- B1 flush 任务常驻成本先于早退；D3 单槽 reserve 与 spec 预留高度表相悖（扩展性天花板）
- C1「滚动期静止」四处分身；E3 冻结正确性依赖布局巧合无守护测试

### Spec 符合度
六裁决：I1′/I2′/槽位键忠实（强证据链）；cadence 效果达成结构未收编；预测量被帽替代
（spec 已补变更记录）；单一引擎点白名单打折。

### 本轮落地
- 快赢：streamFlushIntervalMs 反射缓存；SGR-435 drop 分支限频 500ms
- spec 追加「实现裁决变更记录」（消除 spec-实现漂移）

### 根修路线图（下批开工，按用户「优雅简洁自洽扩展稳定」验收）
- R1 合并配对规则为单一纯函数（决策表单测穷举交互矩阵）
- R2 测量增量化=落 spec 原案「预留高度表」（稳定块高度缓存+增量 chunk 离屏预测量+
  多槽 per-item）——18ms→8ms 唯一结构路径，重写级工程
- R3 ScrollQuiescence 单点统一四处分身
- R4 flush 任务拆职责+探针注入化（release 零成本）
- R5 网关强制化（视口写入全经引擎 API）
- backlog：app SSE 长连接断连（电池优化嫌疑）独立修复

## 二十六世轮：R1统一谓词红绿


### 二十六世轮（R1 完成）：统一配对谓词 TDD 红绿

- 红：StreamingAnchorRuleTest 决策表（穷举 9×9×3×2 格）
- TDD 关键发现：两轨语义大部分同源；唯一真分歧格「锚<增长源」由**源类型**决定
  （ledger 族有 BANNER bottomFollow 通道覆盖→免；帽族消息 item 增长无通道→配对）——
  coveredByFollowFamily 参数显式化，两个真机证据（#435 八轮 vs #437 z3）同时保留
- 绿：StreamingAnchorRule 单一谓词；StreamingPairingRule/reserveReleasePlan 均为薄委托
- A1 全量回归 BUILD SUCCESSFUL（3539+ 用例）
- 待续轮：A2 单出口、R2 高度表（核心）、R3-R5、真机验收、终审

## 二十七世轮：A2单出口+R3静止单点


### 二十七世轮（goal轮2）：R1-A2 单出口 + R3 静止单点

- A2：flush task 同帧双滚动 set（帽先落、ledger 覆盖——requestPosition 覆盖写非叠加，
  双补偿只活一笔）合并为单事务单出口，两笔 shift 叠加原子生效；applyReserveRelease
  退役（净 -16 行）。拒绘语义保持（仅 ledger 派发拒绘；帽路径画增长前态）。
- R3：ScrollQuiescence 单信号源（快照态），flush task 唯一写点；StreamingScrollHold
  退役为委托缝（pilot/ChatScreen 读点零改动）；TDD 红（ScrollQuiescenceTest 语义锁
  +兼容缝一致性）绿（BUILD SUCCESSFUL）。
- 执行顺序调整（数据驱动）：B3 重组链收口（R4）性价比先于 R2 测量增量化——R4 后
  真机复测再定 R2 深度（分片唤醒 vs 双容器分离）。
- 下轮：R4（flush 拆职责+B3 收口）→ 真机复测 → R2/R5 → 终审。

## 二十八世轮：B3步1+复测受阻


### 二十八世轮（goal轮3）：R4-B3 步1 + 真机复测窗口受阻

- B3 步1 TDD 完整：ChatEntry.Turn 身份字段（isUser/isStreaming）构建时编码
  （buildChatEntries 已有 isStreamingTurn 判定，零增量计算）；items lambda 的
  contentType/entryStreaming 改读 entry 字段——消除 displayItems/turnGroups/
  streamingMsgId 三项每 flush 新实例捕获（全部可见 item 重组的根因之一）。
  语义等价论证：仅 Turn 条目可能流式（Chunk/StepGroup 构建时已排除流式 turn）。
- 装机完成（B3步1+R1+R3+A2 累积版本，flag=1）。
- 真机 A2 验收窗口受阻两因：tap 判定窗 500-720 误含电池优化横幅（点到"修复"
  跳设置页）——下轮收紧至 560-720；流式仍未起跑（S10 新会话 18s 静默——
  断连 backlog 未修前测试通道不稳定）。
- 下轮：修 tap 窗口 → A2/A3 验收采样（贴底 6s + jank-seq + VDRAW）→ 数据定
  B3 步2（displayItems state 化）/R2 深度。

## 二十九世轮：A2中期数据


### 二十九世轮（goal轮4）：A2 中期数据——滑动达标过半，贴底暴露 R2 本体

**累积版本**（R1统一谓词+R1-A2单出口+R3静止单点+R4-B3步1，flag=1）真机 S11：

| 指标 | 基线 | 本版 | 目标 | 判定 |
|---|---|---|---|---|
| 滑动 p50 | 7-8ms | **6ms** | — | ✓ 优于基线 |
| 滑动 p90 | 24-46ms | **17ms** | ≤12 | 改善 2.7 倍，未达 |
| 滑动 janky(legacy) | 36-47% | **23.7%** | — | 显著改善 |
| 贴底 p50 | 18ms | 34ms* | ≤10 | *速率混淆见下 |

*贴底 34ms 鉴别：该 turn 为暖启动全速（send 后 3s 起跑、~2min 完成 2500 字 vs
基线 3min/3000 字）——批速率高 2-3 倍，单帧成本 O(批大小) 同比放大，自洽非回归；
但 262帧/6s=43fps（全帧 34ms）暗示**每帧持续负载**（非 100ms 批节奏）——嫌疑：
动画 invalidate 范围/每帧重绘泄漏。待贴底静止 framestats 阶段分解定罪。

**数据结论**：
1. R 系（重组面收口）有效性获证——滑动指标大幅改善；
2. 剩余成本=append 批排版/测量（R2 本体）+ 可疑的每帧负载（新线索）；
3. R2 深度确认为必要：分片唤醒（append 只触尾 chunk）是 O(整turn)→O(尾块) 唯一路径。

**导航修正固化**：会话行 tap 判定窗 530-780 + 排除电池横幅词（电池/连接）；
断连毒害依旧（S10/S11 第一 turn 均静默完成于服务端，重连后渲染终稿）。

## 三十世轮：贴底定罪+R2光标根修


### 三十世轮（goal轮5）：贴底定罪 + R2 光标根修（首项落地）

**贴底静止 framestats 定罪**（S12，p50=20.6ms top41ms）：
- trav 11-16ms + draw 3-10ms + swap 8-15ms（基线 1.4）——**每帧持续负载**（非批节奏）
- 机制链：呼吸光标原以 **Text inline content** 实现——动画值每帧变化 → inline
  重组/失效放大为整段尾块 Text 重排+重绘（trav+draw）；VDRAW 探针 drawBehind 读
  fiso（每批变）再叠加 item 级 draw 重放（swap 段）。

**R2 根修落地**（TDD 红绿，commit 链）：
- cursorOffsetFromLayout 纯函数缝（HeldTailCursorTest 3 例：末端放置/溢出回绕/
  基线对齐光标底部）
- HeldTailReveal 光标移出 Text inline → 独立叠加 Box（offset 定位 onTextLayout
  100ms 一更新；动画失效只作用光标小方块）；锁高裁剪迁移容器层（光标随尾溢出
  被裁，视觉语义保持）
- 预期：贴底跟随每帧 trav/draw 大幅回落（动画失效面从整段 Text → 数十 px 方块）

**装机完成**；效果复测被流式通道阻断（S13 断连/未渲染——backlog 断连 bug 毒害
依旧）。验证顺延下轮（通道窗口一开即采）。

**剩余**：R2 效果验证 → R4-B3步2/R4-flush 拆分 → R5 → A3 VDRAW → A5 终审。

## 三十一世轮：探针非快照化+协议教训


### 三十一世轮（goal轮6）：VDRAW 探针非快照化 + 验证协议教训

**落地**：三处 VDRAW drawBehind 去重态 mutableStateOf → IntArray（R4 探针注入化
实战）——draw 阶段写快照是 item 级 display list 重放的放大器（滚动/贴底每帧 draw
成本）。commit 已推送，装机。

**通道真相补充**：连续 STALL 的重要原因之一=**app 停在错误服务器视图**
（Host-4199 配置页，214,706 是 Host 条目）——dsh-pair 后需确认落在 DSH 会话列表
（DSH-Paired 条目"会话"入口 y~1420 可切回）。

**验证协议缺陷教训（S14 数据无效）**：采样时 fiso=1974-2048（深阅读位非贴底）+
第 2 turn 上下文含第 1 turn 全文（内容翻倍，h 2166→2288）——贴底 p50=42ms 是
深位配对帧数据，无效。**标准化协议（下轮执行）**：
1. 进会话 → 触发贴底（FAB/滑到底）→ logcat 确认 VTRACE fii=0 fiso<8
2. 单 turn 会话（避免上下文累积）
3. 贴底 6s → 滑动序列 → VDRAW 泄漏检查

**O(总内容) 证据链再+1**：滑动 p50 6ms(单turn) → 12ms(双turn)、深位流式 42ms
——成本随视口附近内容量增长，R2 分片/稳定块缓存的必要性确凿。

## 三十二世轮：贴底达标


### 三十二世轮（goal轮7）：双修显效——贴底 p50 达标

**标准协议数据**（S15 二 turn，无 VTRACE=贴底稳定确认，双修版=光标叠加层+探针非快照化）：

| 指标 | 基线 | 轮4单修 | 本轮双修 | 判定 |
|---|---|---|---|---|
| 贴底 p50 | 18ms | 20.6-34 | **6ms** | ✓ 达标（≤10） |
| 贴底 p90/p99 | — | — | 42/57ms | 尾部未平（append 批帧） |
| 滑动 p50 | 7-8ms | 6ms | 6ms | ✓ |
| 滑动 p90 | 24-46ms | 17ms | 24ms | 未达（≤12） |

**结论**：贴底跟随的「每帧持续负载」被双修根除（p50 18→6ms = 动画放大器+
探针放大器双双消除的铁证）。剩余：
1. 滑动 p90（滚动帧撞 append 批的组合/测量成本）→ B3 步2（displayItems state 化）
2. 贴底 p99=57（批帧全链）→ R2 分片（O(总内容)→O(尾块)）

**导航修正固化**：/tmp/jk-goto-session.sh（任意屏态→目标会话：服务器页切
"会话"入口、列表页直接顶行**不 back**、聊天屏 back）——84,302 back 在列表页
会退回服务器页（本轮流程 bug 教训）。

## 三十三世轮：B3步2落地


### 三十三世轮（goal轮8）：R4-B3 步2 落地——displayItems 快照列表差量承载

**TDD**：diffDisplayItemsInto 纯函数（4 例：零写快路径/单槽写/rawIndex 变化/
长度变化全量重置）→ SnapshotStateList 接线（ChatScreen displayItemsState +
remember-key 差量写入，传参改 state list）。机制：get(i) index 级依赖，
set(i) 只失效读该槽的 item——流式期长度不变、仅尾槽内容变 → 重组收敛到
流式 item 本体（原每 flush 新 List 实例=全 item content 失效的收口）。
全量回归 BUILD SUCCESSFUL。

**三修版数据**（S16 单 turn，起跑即采——非稳态）：
- 滑动 p50=6ms ✓ / p90=**19ms**（趋势 46→17→24→19，接近 12 目标）/ janky 22.7%↓
- 贴底 25ms=起跑期数据（reasoning+首批密集），稳态复测留下轮（上轮稳态已证 6ms）

**剩余**：稳态标准采样 → R5 网关强制化 → A3 VDRAW → A5 终审。

## 三十四世轮：协议最终形


### 三十四世轮（goal轮9）：复测协议最后缺口 + A3 初步分类

**发现**：S17 稳态采样仍无效——fiso=3530 深位（进入时机晚于内容增长，视口落
中部；S14 在 B3 步2 之前已现同款 → 非步2 回归，是 goto-session 协议缺口）。
**协议最终形**：进入会话后显式触底（FAB/滑到底）→ 确认 VTRACE fii=0 → 采样。

**A3 VDRAW 初步分类**（893 事件）：H_ONLY=457/O_ONLY=5/BOTH=430——深位场景
下 H_ONLY 高（配对丢失形态），但非贴底基线判定不适用；贴底场景 A3 待最终
协议下复测。

**装机版本安全确认**：全量单测绿 + 滑动数据正常（p50 6-11）+ 视口深位非回归
（时间线证据）——版本可留给用户日常使用。

**用户验收请求**（A5 的人工半边）：日常使用体感贴底跟随/滑动流畅度/闪烁——
用户是最终验收者；仪器协议三缺口（断连/服务器视图/视口初始化）已逐一登记。

## 三十五世轮：A2/A3终测+R5最小版


### 三十五世轮（goal轮10）：A2 贴底终测达标 + A3 贴底零泄漏 + R5 最小版

**A2 贴底终测**（显式触底协议，S18）：p50=**5ms**（两次独立确认 6/5ms，目标 ≤10
✓✓）；p90=24/p99=38（尾部=批帧全链，R2 分片遗留域）。
**A3 贴底判定**：O_ONLY=0、fiso 恒 0（18 事件全为 H_ONLY=贴底生长正常形态）
——贴底零泄漏 ✓。深阅读位泄漏分类不适用（需专门手势窗口采样，归入用户验收
观察项）。
**R5 最小版**：网关外写入显式登记表（五族+豁免依据，从 KDoc 自我豁免改为
review 检查项）；强制化（封装/lint）登记为后续批次。
**A4 架构检查**（全项达成）：配对单谓词 ✓（StreamingAnchorRule+486 格决策表）/
静止单点 ✓（ScrollQuiescence 唯一写点）/双 set 消除 ✓（单出口事务）。
**A5 终审代理**已派发（后台运行，双轴：Standards+Spec，结果下轮收）。

## 三十六世轮：终版回归+R4判定


### 三十六世轮（goal轮11）：终版回归 + R4 深拆判定

**最终全量回归**：BUILD SUCCESSFUL（3500+ 用例零失败）——A1 终态证据。

**R4-flush 深拆职责判定：不必要**。经 R1-A2 单出口重构后，streamingGrowFlushTask
结构已收敛（帽释放与 ledger 派发合并单事务；对齐翻转/VTRACE/静止广播为独立
段落）；机械函数化拆分只增加调用链风险、无可观测收益。R4 以「B3 步1+步2+探针
注入化+单出口」四项实质完成收口，深拆登记为非必要项（此判定为 review 留痕）。

**A5 终审代理**仍在运行（大范围双轴审查）——结果下轮（12/12 末轮）收取并处理。

## 三十七世轮：终审收取+Critical即修


### 三十七世轮（goal轮12·末轮）：终审双轴报告收取 + Critical 即修

**终审结论**（双轴代理全文存档于本节摘要）：「未达验收标准，不建议以此状态关闭」——
但抓到真问题，正是终审的价值：

**[Critical·已修] ScrollCompensation 拒绘表达式方向反转**（R1-A2 单出口重构引入）：
原 `total != 0f` 使 ledger 配对帧**不拒绘**（StreamingGrowNode 直报真高 → 「新高度+
旧偏移」中间帧回归通道敞开，I1′ 违约）而纯帽路径反而被拒绘——与注释/journal 记录
完全相反。修复：`ledgerTotal == 0f`（ledger 派发帧拒绘=原子性依赖；帽路径画增长
前态无需拒绘）。核心测试回归绿。
**[High·勘误] 二十七世轮「拒绘语义保持」记录失实**——特此勘误：该轮重构引入了
方向反转 bug，语义并未保持；终审（而非自验）发现。教训：单出口重构的布尔语义
翻转应配对显式单测（flush task 拒绘返回值缝此前无测试覆盖——审查 E3 同款盲区）。
**[Medium·已处理] S3 兼容缝**：@Deprecated + 退役判据（挂载点迁移后删缝）。
**[Medium·登记 backlog]**：cadence 结构收编（spec 裁决 3 承诺未兑现）、flush 八职责
深拆（R4 判定反转：终审认为 S2 成立，拆分价值获第三方背书——登记下批）。
**[待复核清单转下批]**：ScrollQuiescence 单例假设/锁高裁剪等价/diffDisplayItemsInto
边界/SSE 铁律逐条（终审截停未覆盖项）。

**Goal 收口判定（如实）**：不标 complete——滑动 p90 未达 12（19-27，R2 分片主体
未做）、用户验收未完成、终审 Medium 两项转下批。已达成并锁证：A1 全量回归、
A2 贴底 5ms（双确认）、A3 贴底零泄漏、A4 全项、R1/R3/R4 实质/R5 最小版。

## 三十八世轮：新bug取证与勘误


### 三十八世轮（goal轮13）：新bug取证两轮——一次误判勘误+防御落地，真凶未定罪

**用户报告**：SSE 输出时上方内容偶发闪烁消失。

**取证 1**（15s 高频截图 60 帧+录屏+1184 行日志）：帧差分平稳——窗口内未复现。
**误判与勘误**：release set(fii=7,fiso 0→66→132 递增) 曾被解读为「GUARD 触底
pending 被配对覆盖→视口被推走」——**复核推翻**：①验证脚本的「触底」swipe 方向
反了（1800→700 上滑=读旧内容方向），窗口实为阅读态；②阅读态锚==增长源的
fiso+Δ 配对是**正确行为**（画面保持公式）。特此勘误。
**防御保留**：shouldYieldPairing（外部 pending 未消费→配对让位，位置神圣语义）
——虽非本 bug 根因，属正确防御（PairingYieldTest 3 例+回归绿+装机）。
**另证**：修复后窗口 release 全为「帽释放未配对」形态（total=0），日志 set()
打印误导（打印了 targetFii/Fiso 但未实际滚动）——日志措辞待改（下轮）。

**真凶候选（下轮排查）**：
1. turn 完结瞬间分片裂变（recentStreamedTurnKeys 延迟窗口后 key 1→N 换血）
2. STEP id fresh→dsh-t0s0 换代（取证日志捕捉到一次）参与子键
3. diffDisplayItemsInto 长度抖动全量重置路径
**需要用户协助**：复现条件（什么内容/多长输出/哪个时刻——闪烁消失是整段不见
还是部分内容？转瞬即逝还是持续？）

## 三十九世轮：候选排查+定罪探针


### 三十九世轮（goal轮14）：三候选代码排查 + CONTENT-BLINK 定罪探针装机

**候选排查**：
① 完结分片裂变——recentStreamedTurnKeys 保护只在 turn 离开视口窗口后解除
（RenderSupply L415-426 有界清理）→ 裂变发生在「离开-回来看」时刻，与用户
「SSE 输出时」不吻合（弱化不排除）。
② STEP id fresh→dsh-t0s0 换代——item key 均为 turnKey 前缀派生（#sgb/#sgh
等），step id 不进 key；换代只致 StepGroup 实例变化（重组非消失）——除非展开
状态按 id 键（待查 CardExpandReveal 展开态存储，下轮）。
③ displayItems 长度抖动全量重置——clear+addAll 组合期写（同帧原子性存疑），
流式中长度抖动源未定位（下轮：dedupe 输出长度日志）。

**定罪探针装机**：CONTENT-BLINK——chatEntries 键序列 diff 中 removed≥2 且
added<removed（上方内容消失形态）时显式打点（含时刻/键/displayItems 长度）。
**用户复现时刻与此行对齐即定罪**——比盲猜候选高效。设备已是探针版。

## 四十世轮：候选排除


### 四十世轮（goal轮15）：候选②③排除——定罪完全依赖 CONTENT-BLINK 复现

- **②排除**：expandedStepGroups 恒空（裂变退役，ChatMessageList L479）——无展开态
  存储，STEP id 换代无折叠通道。
- **③理论排除**：clear+addAll 同组合 pass 双写，失效传播时已是终态（无空帧）；
  长度抖动的全 item 重组一帧仍在（成本项非消失项）。
- **①存疑**（时刻弱吻合）+ banner index 平移（key 复用无闪烁，理论排除）。
- **结论**：静态分析穷尽，定罪依赖 CONTENT-BLINK 探针 × 用户复现时刻对齐。
  设备已是探针版；用户报时刻即收网。

## 四十一世轮：键漂移维度排除


### 四十一世轮（goal轮16）：4000字全程监听——键漂移维度排除

主动窗口（S20 会话 4000 字完整输出周期，150s 轮询）：CONTENT-BLINK=0、
HFLICK PLAN=0——**item 键序列全程零漂移**。「上方内容闪烁消失」若为键换血
形态（removed≥2）应被捕获——排除该主因。

剩余假设空间：①偶发率低于单次窗口；②形态非键变化（内容/高度/绘制层瞬间
异常——如 markdown 块重渲染一帧空白）；③特定内容触发（表格/代码块/工具调用
——纯散文测试未覆盖）。**用户复现时刻×CONTENT-BLINK 对齐仍是最高效路径**；
若用户复现时探针仍零行 → 转绘制层取证（录屏逐帧+GPU 截图）。

## 四十二世轮：内容形态排除


### 四十二世轮（goal轮17）：内容形态维度排除——键漂移全面出局

表格/代码块密集输出（S21）120s 监听：blink=0 / plandiff=0——与散文（S20）合计
约 5 分钟完整输出窗口，**item 键换血形态全面排除**。
新 bug 剩余解释空间：偶发率极低（>5min/次）或**非键变化形态**（绘制层/markdown
块重渲染瞬时空白）。收网路径不变：用户复现时刻对齐探针——探针零行即转绘制层
逐帧取证（录屏+GPU dump）。

## 四十三世轮：#441稳态粒度落地


### 四十三世轮（goal轮18）：#441 markdown 稳态粒度（用户新裁决）落地

**用户需求**：表格逐行渲染（不等整块）、列表（有序/无序）逐项渲染。

**TDD 根修（SafePrefixGate 三分支）**：
1. **表格正文跨批续放**：原逻辑表头批次放完后，正文行被 isTableHeaderRow 误判为
   新表头→等不存在的分隔行→扣留到 EOF（「表头先出、正文整块最后出」根因）——
   新增 prevReleasedLineIsTableFamily 续放判据，正文行整行放（半行不放）。
2. **'*' 无序列表项**：原 '*' 在活动标记集整块扣留——新增列表项行判定
   （'*'+空格形态），行级定案整行放；'*bold' 强调形态维持扣留（跨行重释义风险）。
3. **有序列表项**：原 isOrderedListStart 使有序项整块扣留——纳入列表项分支
   （数字+'.'/')' 形态）；旧测试「行首有序列表起始扣住」按新裁决修订。
   '-'/'+ ' 无序本就纯文字直出（回归保持）。
- 测试：新增 6 例+修订 1 例；全量回归 BUILD SUCCESSFUL。装机完成。

## Goal终章：20轮收口总账


## Goal 终章（goal-c489c244，20 轮耗尽收口，2026-09-26 深夜）

### 达成并锁证
- **R1 统一配对谓词**：StreamingAnchorRule + 486 格决策表（终审评"教科书级"）；
  同帧双 set 单出口；终审 Critical（拒绘反转）即修+勘误
- **R3 静止单点**：ScrollQuiescence 唯一写点
- **R4 实质四项**：B3 步1（entry 身份编码）/步2（displayItems 差量承载）/
  探针注入化（VDRAW 非快照）/呼吸光标叠加层根修
- **R5 最小版**：网关外写入显式登记表
- **A1** 全量回归（3500+ 用例）✅ **A2 贴底 p50 5ms**（基线 18，双确认）✅
  **A3 贴底零泄漏** ✅ **A4 全项** ✅ **A5 双轴终审+Critical 修复** ✅
- **#441 markdown 稳态粒度**（用户新裁决）：表格正文跨批逐行+列表项（星号/有序）
  行级放行——单测 7 例锁定；仪器验证被断连通道阻断，以用户体感为验收
- cadence 100ms、冻结语义补全、反射缓存、SSE flush 限频等快赢

### 未竟（挂账清单，backlog 二期卡 P2 + P3 新卡）
1. **R2 分片主体**（滑动 p90 19-27ms 未达 12——O(内容)→O(尾块) 唯一路径）
2. **闪烁消失新 bug**：键换血形态全面排除（散文+表格 ~5min 窗口零事件）；
   CONTENT-BLINK 探针常驻——待用户复现时刻定罪；零行即转绘制层取证
3. cadence 结构收编 / flush 深拆（终审 S2）/ 兼容缝迁移（@Deprecated 已标）
4. markdown 大块拆分扩展（用户裁决后续 P3 卡）
5. app SSE 长连接断连（P1 卡——测试通道毒害源）

### 流程资产
导航脚本/标准验证协议/双轴审查体系（终审抓到自验盲区的实证）/十二篇
journal 世轮（二十六~四十三）全链可溯。

## 四十四世轮：R2撞墙结论


### 四十四世轮（goal轮21）：R2 双容器撞库约束墙——设计结论存档

**墙**：双容器（稳定 state+活跃尾 state）要求"块毕业时尾换头"（尾内容起点前移）
——StreamingMarkdownState 为 append-only（无前缀移除），换头只能重建实例；
重建首帧空内容（LaunchedEffect 异步 append）= 高度塌一帧 = 闪烁（#428 Loading
短高入测 / #437 失配帧两历史教训同源）。过渡帧双活=内容重叠闪烁，同样不可接受。
**结论**：零闪烁双容器需替换状态管理（自研 append-only+前缀吸收 layout，或库
改造）——超出本批预算，另立设计批（backlog 登记）。
**已落 R2 资产保留**：stableTailBoundary 纯函数（6 例）——未来双容器/换库方案
的直接复用件；光标叠加层根修（已交付的 O(动画) 优化）。

**转轨**：本批收尾做终审挂账两项——cadence 收编（spec 裁决 3）+ StreamingPairingRule
缝退役迁移（4 挂载点直调统一谓词）。

## 四十五世轮：fling取证


### 四十五世轮（goal轮22）：fling 复发取证——途中无异常，回底跳切待用户确认

**取证**（S22，流式中双向 fling 序列 + VTRACE/SGR/RESERVE 121 行）：
- fling 途中（上滑往底 ×3、下滑往旧 ×3）：视口轨迹平滑，**无 LEAP 回吐、无中途
  跳变**——历史病灶（817607b4 键遗忘重锚/b54650c3 巨额 set）均未复现于本窗口。
- 唯一跳切形态：慢拖回底末尾 (7,1166)→(0,0)（147ms，1166px 跳切，与 align-flip
  bottom=true 同帧）——**回底吸附（GUARD/snap 族）以跳切完成而非平滑滚动**。
- 三个 align-flip 全部 caughtUp=true（零位移翻转 ✓ 二十四世轮修复保持有效）。

**待用户确认症状形态**：「fling 下滑跳变」是指——
A. fling **途中**内容突跳（本窗口未捕获）；
B. fling **到底瞬间**跳切吸附（本窗口捕获的形态，snapToBottom 强推语义）；
C. 其他（如 fling 被「吸」住/减速异常）。

## 四十五世轮勘误：跳切是采样伪影


### 四十五世轮·勘误补记：所谓「回底跳切」是采样伪影

复核：VTRACE (7,1166)→(0,0) 相邻采样间隔 147ms，取证序列的末段慢拖（400ms 全程
~1200px）尾程恰在该间隔内完成——**正常拖拽轨迹的采样合并，非跳切**。snapToBottom
仅 FAB 显式路径（跳切=显式意图语义 ✓）。**本窗口结论修正为：完全无视口异常**。

fling 复发取证两轮（S22 双向序列）：零异常、零 LEAP、零巨额 set、align-flip 全
零位移。**复现依赖用户场景信息**（症状形态 A/B/C 确认或复现时刻）。

## 四十六世轮：待复核清单解除


### 四十六世轮（goal轮24）：终审待复核清单四项+两项——全部复核无恙

- ScrollQuiescence 单例：快照态+私有 setter+唯一写口 ✓（单列表假设与
  PreRenderCoordinator 同款，可接受）
- SSE 铁律四条：scheduleFlush 不取消（isActive 早退）✓ / rememberMarkdownState
  保留 ✓ / autoScroll 双键在位 ✓ / 流式配对 isStreamingMsg 门控 ✓
- diffDisplayItemsInto 边界：中间插删→长度变化→全量重置（语义安全）✓
- HeldTail 锁高迁移：容器层裁剪含叠加光标（视觉等价，实现时已论证）✓
**终审「不能默认无恙」清单全部解除。** 复发两 bug（fling/闪烁消失）仍待用户
现场信息。

## 四十七世轮：fling调研措施图景


### 四十七世轮（goal轮25）：fling 跳过复发调研——措施图景与嫌疑清单

**用户澄清定义**：fling 状态下遇大消息体，无法立刻渲染→直接跳过（LazyColumn
框架特性；官方 issuetracker 172029355 即预加载 API 需求同源）。

**既有措施图景（代码核实）**：
1. SafeFlingBehavior 限速 fling——每帧 ≤视口/8，穿越任何内容 ≥8 帧（2026-08-20
   修「fling 下跳」正主，注释原文与用户症状一致）——**接线在**（ChatMessageList
   L2301 flingBehavior=）✓ 未丢
2. ScrollSpeedPrefetchStrategy——fling 高速段 6 项宽窗预组合（pausable 崩溃
   已用 performFullComposition 旧路径保活）
3. #258 分段（完结长 turn 拆 TurnSegmentPlan）+ RenderSupply 预解析

**嫌疑清单（下轮真机定罪，DEBUG-flng/VTRACE 探针现成）**：
A. 巨型 item 预组合超预算（prefetch measure max 150ms 装不下 3000+字 item →
   进入视口未成型 → 跳过感）——分段门槛外的巨项面
B. 限速 1/8 对多视口高巨项（4-8 屏）仍给不够预组合时间（需 32-64 帧穿越 vs
   6×150ms 预算）
C. 流式中 turn 不分段（isStreamingTurn 抑制）= fling 扫过面
D. 崩溃防御 retries 路径吞位移（低概率）
**取证中断**：设备 adb 瞬断（bash 后台提升）——下轮重连补采。
