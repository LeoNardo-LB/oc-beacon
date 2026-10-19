# 544-c1-unified-choreography（2026-10-19）

> 状态：进行中
> 关联：（spec 路径，若有）·（issue 编号，若有）
> 来源：用户反馈 / grilling / E2E / 顺带发现

<!-- 过程中的取证/验证证据直接写本文件；backlog.md 只留 ≤3 行卡片。 -->

# §1 勘察与执行计划（2026-10-19 12:00，本批开工）

## 双路径现状图（CardExpandReveal.kt 1943 行 + ScrollCompensation.kt 1473 行）

### 降级/流式路径（CER :553-856）
- 入口分叉：`:553 if (listState == null || LocalInStreamingTurn.current)`。
- 状态面（≥13 处）：degradeH/revealTopRootY/contentH/drive(DegradeDriveState)/finalHSeed(rememberSaveable)/lastExpandAtMs/toggled/prevVis/toggleSeq + reserve 侧 expandPairingWindows/expandDepartureBudgetLeft/expandCollapseStickTop/expandCollapseSpanDepth/hostItemTopY/trueHeight。
- 编舞：组合期同步注册（:618-682 翻转检测+驱动注册+开窗+表述乙几何捕获+终高种子取靶）→ LaunchedEffect(toggleSeq) 关窗协程（:684-750，「AV 稳定 3 帧」sawChange/40 帧上限 → finally 关窗+种子 settle 锚定+注销判定）→ 驱动盒渲染（:790-855 absorbPadHeight+degradeRevealHeight+contentH onSizeChanged+600ms 单调护栏）。
- 未驱动回落（:752-769）：裸 AV+弹簧（listState==null 或无 reserve——横幅/预览/单测）。

### 机件/静态路径（MECH :859-1943）
- CardExpandClock（:859-921 记忆态：fraction/revealTopY/revealBoxH/drawnTopY/finalHCache(rememberSaveable)/curtain/carriedAnchor/drawFraction/episodeSeq）。
- LaunchedEffect(visible,listState)（:936-）→ PreRenderCoordinator.withEpisode 租约 → 展开集：onExpandComputing+让帧 → warmup+settleUntilContentStable+awaitAnchorQuiescence(#529 静止门) → H=lastMeasuredH 入 finalHCache → 反射原子帧 requestScrollToItemNoCancel(norm(fii,fiso+H)) + withMutableSnapshot{driveTo(1f)+帽同快照抬}（:1011-1091）→ steadyRebase → departure → 幕布 CURTAIN_MS 纯 draw 揭示。
- 收起集（:1122-）：幕布收拢 → awaitAnchorQuiescence → 增量镜像闭合（rep=lastReportedH）。
- 其余：预热（:1324 PREWARM_IDLE_MS）、#430 稳态配对（:1357+）、CardExpandGeometryNode/finalH floor、settle/静止门实现、CardExpandClock 类。

### flush 驱动腿（ScrollCompensation :780-1052）+ HeightReserveState（:300-500）
- 四报已逐卡化 stick/span（drive.stickTop/spanDepth 随卡走）；reserve 级镜像 expandCollapseStickTop/SpanDepth 只剩正向否决门消费（先遣包已 min(逐卡)回退）。
- DegradeDriveState 列表制（degradeDrives）+ absorbPx（#554 垫）+ hTarget（#549 种子）。

## C1 手术设计（spec §1.2/§3 落地）

### Phase 1——结构统一（行为逐字节不变）
1. 分叉条件改 `listState == null`（LocalInStreamingTurn 从 if 中移除）；删除两处 provides（ChatMessageList:220/:2954）与定义（CardExpandReveal:92）。
2. 降级路径的「driven」子分支（:601-855）整体迁入机件路径 episode 结构内=「终高未知→动画相」分支（代码 verbatim 搬家：注册/开窗/关窗协程/驱动盒/垫/种子）；driven=false（无 reserve 的列表卡——现仅存理论面）走裸 AV 回落。
3. #420 头注释矩阵第一条改写：『流式 turn 内的卡片降级裸 AV』→『终高未知（流式/移动目标）走动画相帽通道；listState==null（横幅/预览/单测）走裸 AV』。

### Phase 2——终高谓词成为唯一内部分支（spec 语义）
统一 episode 顶部：`finalH 已知（finalHCache/#533 店/#486 终态/coldReveal）→ 原子帧（保留 warmup 让内容入树；**退役 settle+静止门**——反射靶=已知 H 直用）`；`未知且流式 → 动画相（Phase 1 搬入的驱动盒分支）`；`未知且静态 → settle 解未知（现状）→ 原子帧`。
- 幂等重挂不变量（§1.5）：缓存命中→coldReveal 单帧原子落位（现状已近似）；无缓存→重挂帧开窗配对（二轮④同值守卫，现状）+ 起于当下几何新动画相；探针门=slot-compose/episode-start 打点测流式期重挂率（DEBUG 探针加计数）。

### Phase 3——C5 单写者化 + C6 快赢包
- C5：HeightReserveState 字段私有化，写点收敛 flush 单事务意图协议（toggle/几何捕获组合侧只表达意图）；守卫（防误清/防双写）随相位合并删除。
- C6：streamingGrowPairing 5 挂载点（ChatMessageList:2060/2441/2692/2778+ScrollCompensation:301 定义）收编为类型强制；探针单点开关。

## 验证计划（验收门=spec §3）
1. 逐级编译绿 + 全量单测（FlushTaskPhasesTest 随相位合并收窄；新增终高谓词判定表测试）+ lint。
2. 第一刀全绿签名当基线（七卡 E2E 11 场景矩阵=现成签名网——557/556/540/547/554/549/540_dsh×2/551×2 恰好覆盖流式展开/收起/降级/巨卡/吸垫全谱）。
3. R1-R9 级真机矩阵复跑（runner 11 卡 run-all 双端 campaign 模式）+ 重挂探针门读数。
4. scroll 域隔批串行纪律（#522 裁决维持）。

## 风险登记
- 流式路径行为是五报+三轮+#549/#554/#557 五层定罪硬化产物——Phase 1 必须逐字节搬家（签名 DEBUG-540/557/549 日志序列为对照基线）。
- 静止门退役面（Phase 2 已知分支）有 #529 脏基历史——若 R1-R9 出现反射双计样本即回退该分支保留静止门（数据说话）。
- 帽×机件单写者快照（:1071-1081）是 #540 竞态修复本体——搬家时保持与反射写同快照不变量。

# §2 设计精化（2026-10-19 12:15，§1 计划的形态修正）

§1 Phase 2 写了三分支（已知→原子／未知+流式→动画／未知+静态→settle 解未知）——**重读 spec §1.2+语义墙后修正为两分支**（spec 本义）：

- **已知**（finalH 店/#486 终态缓存/coldReveal/种子命中）→ warmup（内容入树，:980-982 保留）→ 反射原子帧（(fii,fiso+H)+driveTo(1f)+帽同快照）→ 幕布揭示 → 关集存 finalH。**settle+awaitAnchorQuiescence（#529 静止门）整体退役**（原子帧捕获与写入同快照零间隙，快照内重读校验收口）。
- **未知**（首次展开，不分静态/流式——语义墙：H 未知则原子落位无定义）→ **跳过 settle** → 动画相=驱动盒：hTarget 活跟踪 contentH（600ms 单调护栏）→ flush 帽通道逐档步进 drive.h（恒配对）→ contentH 稳定+驱动收敛 → 关集 → finalHSeed settle 事件锚定（#551）。
- 收起：幕布收拢 → 原子闭合（塌缩目标恒 0=可测，rep 镜像）；垫/否决门/离底预算逐卡语义照搬。

**推论（搬家面比 §1 估的小、退役面比 §1 估的大）**：
1. 静态首次展开从「settle→原子帧」改走「动画相（驱动盒）」——这是行为变化点（观感：静态首展从单帧幕布变为逐档展开动画）。签名对照基线须区分：机件路径探针（DEBUG-466 expand-anchor）只在**已知分支**出现；未知分支签名=DEBUG-540/557 序列。七卡矩阵中 551_static（静态巨卡首展+再展）是关键对照腿。
2. settleUntilContentStable/awaitAnchorQuiescence/CardExpandClock 的 settle 相关字段与测试段随两分支落地退役（C2 已清死机制，这是活机制退役——须以 R1-R9 数据放行）。
3. 关窗判据统一=集生命周期（驱动收敛+contentH 稳定），「3 帧稳定 sawChange」逻辑并入集完结判定。

执行序修正：Phase 1（分叉条件+搬家，行为不变）→ Phase 2a（已知分支 retire 静止门——单独 commit+单腿验证）→ Phase 2b（未知分支统一到驱动盒——静态首展行为变化，551_static/551/554 腿重点）→ Phase 3（C5/C6）。每 Phase 间跑 11 卡矩阵签名对照。

# §3 Phase 2b 设计定稿（2026-10-19 12:00，动刀前登记）

## 核心洞察：终高店双角色
- 流中窗关写入的 contentH 是部分高（#549 种子=驱动提示层：防分期矮化反拽，r400 slam 修复本体）；机件原子帧派发的 H 必须终态真值。统一为一店 + 终态信号参数：
- 统一店：finalH = rememberSaveable(cacheKey)（两分支上方提升）；驱动分支窗关写（提示角色）；终态时驱动分支 onSizeChanged 活刷新（contentTerminal && expanded → finalH=contentH）；机件 settle/2a 对账/预热写真值。
- 终态信号：CardExpandReveal 新参 contentTerminal: Boolean = true（静态卡默认终态；ReasoningBlock 传其 contentTerminal 参数[#556/#518① 派生]；PartContent 同）——取代 LocalInStreamingTurn 的信息位（活动 turn=内容移动=店不可信），local 本体删除。

## 谓词与生命周期归属
- viaDrive = remember { mutableStateOf(false) }：本展开集机器归属（收起镜像依赖各自账本：机件读 clock.lastReportedH，驱动盒读 drive.h——跨机收起=读垃圾）。
- 进入驱动盒 ⇔ viaDrive || (visible && (!contentTerminal || finalH<=0))：流中/首展未知 → 动画相；预热(1.2s 空闲)填充店后 → 机件原子帧。
- viaDrive 置位：驱动分支展开 toggle；复位：窗关 finally 且 !visible && drive.h==0（收起集落幕）。

## 行为变化面（对照今日）
1. 静态首展（预热未命中）：settle+原子 → 驱动盒渐进（spec §1.2；预热命中则不变）。
2. 终态后重展开（曾流式的卡）：驱动盒+种子 → 机件原子帧（终态店已活刷新为真值）。
3. 其余全同（流中谱=驱动盒 verbatim；静态已知=2a）。

## 施工序
①统一店提升+引用改写 ②viaDrive+谓词 ③终态活刷新 ④contentTerminal 参数+穿线 ⑤删 LocalInStreamingTurn ⑥#420 头注释矩阵改写 ⑦编译/单测/腿

# §4 Phase 1-2b 完成战报（2026-10-19 13:10）

| Phase | 内容 | commit | 验证 |
|---|---|---|---|
| 1 | 分叉重构统一入口（裸AV回落+流式过渡谓词入动画相分支，逐字节搬家） | a5b422414 | 编译/全量单测/540+557+551_static 三腿 PASS |
| 2a | 终高谓词已知分支：finalH 命中直用已知 H 原子帧，settle+静止门该分支退役；幕布后店值对账 | a441d447c | 单测+551_static/551/554/540 四腿 PASS |
| 2b | 终高谓词取代 LocalInStreamingTurn：统一终高店+contentTerminal 终态信号+viaDrive 生命周期+终态活刷新+local 删除+头矩阵改写 | 14892ee2d | 全矩阵 11/11 PASS |

## 2b 两处装置/产品硬化（矩阵迭代定罪）
1. 探针晚初始化（1208 矩阵 540/554/dsh 三卡 FAIL）：prevVis 条件 remember 在 useDrive=false 挂载期不初始化 → 首入=已翻转后吃掉翻转事件。修复=无条件 remember、仅日志门控。
2. **垫塌回归**（123710 定罪，真产品回归）：viaDrive 复位条件漏垫——收起 h=0 但 absorbPx≠0 时跳分支 → 垫的渲染载体（驱动盒 absorbPadHeight）消失 → 垫量瞬塌=卡顶 1377px。修复=复位需垫清空（垫随泄放/耗除/流终自清后自然复位）。

## 行为变化面（已落地并验证）
- 静态首展（预热未命中）：settle+原子 → 驱动盒渐进（spec §1.2 未知→动画相；预热 1.2s 命中则仍机件原子帧）。
- 终态后重展开（曾流式卡）：驱动盒+种子 → 机件原子帧（终态活刷新店值）。
- 流中谱/静态已知谱/收起谱：全同今日（矩阵全绿佐证）。

## 剩余（Phase 3）
- C5 HeightReserveState 单写者化（字段私有化+flush 单事务意图协议）。
- C6 快赢包（streamingGrowPairing 5 挂载点收编+探针单点开关+重挂率探针门）。
- R1-R9 级真机矩阵验收（11 卡双端 campaign）+ 全量单测/lint 终扫。

# §5 战役25 验收 + C5/C6 + 探针门（2026-10-19 13:50）

## R1-R9 验收矩阵（战役25，2b+探针构建）
| 段 | 结果 |
|---|---|
| 模拟器 11 卡矩阵 | **11/11 PASS** |
| 模拟器变体集XVI（547×29/0.62@12、551_static×72/0.44、554×64/0.4、549×68/0.22） | **4/4 PASS** |
| 真机 11 卡矩阵 | **11/11 PASS** |
| 真机变体集XVII（547×27/0.68@14、551×66/0.32、557×54/1.1 停顿 11×9s） | **3/3 PASS** |

## C5+C6（commit 499765e7d，编译/全量单测/lint 绿）
- C5：stick/span/budget/windows/hostTopY 五字段 private set；意图 API（onExpandPairingWindowOpen/Close、captureCollapseGeometry、onHostPlacement、consumeDepartureBudget）收编卡侧 8 写点+flush 预算写点；测试缝 arrangeForTest（null 增量语义）——FlushTaskPhasesTest 21 处+PairingWindowResetTest 2 处改写。
- C6：streamingGrowPairing 收编 clipToBounds（注释契约→类型强制），4 调用点去重（链序 fill→clip→element 逐字节等价）。
- C1 重挂率探针门（spec §1.5）：[C1-remount] slot-compose 打点。

## 重挂率探针门读数（战役25 矩阵日志）
- 按宿主分桶：**每 turn 宿主流式期 slot-compose 恰 1 次=零重挂**（540/551/549/557 全场景一致；host=null 计数为无宿主横幅/装饰卡首组，非重挂）。
- 结论：幂等重挂不变量成立；**续命机制（rememberSaveable fraction/锚）无需引入**（spec §1.5「超标再议，不预付费」——数据不超标）。

## stability 账面
- 全仓 590/658 PASS、**未归因 0**（2b 迭代轮 7 条+演示彩排 1 条归因入表：探针晚初始化/未装机重跑/垫塌回归定罪复跑全绿）。

## 待收尾
- C5/C6 构建确认矩阵（模拟器 11 卡+真机 3 腿，进行中）→ 绿即卡片转 [~]。

# §6 收官总账（2026-10-19 14:08）

## 验收门对照（spec §3）
| 门 | 证据 |
|---|---|
| 第一刀全绿签名当基线 | 2b 矩阵 11/11 + 战役25 双端矩阵 22/22（11 卡签名 oracle 全 PASS） |
| 重挂探针门 | 每宿主流式期 slot-compose 恰 1 次=零重挂（§5）——续命不引入 |
| R1-R9 级真机矩阵复跑 | 战役25 [3/4] 真机 11/11 + XVII 变体 3/3 + C5/C6 确认真机 3/3 |
| 全量单测/lint | 单测 BUILD SUCCESSFUL（含 C5 测试缝改写）；lint 绿 |

## commit 链（本批 7 commit）
54df0e0c1 勘察 → a5b422414 Phase1 → a441d447c Phase2a → 14892ee2d Phase2b → 5d3d2eb5b §4 战报 → 499765e7d C5+C6+探针门 → 本节。

## #544 交付语义总账
- 单一编舞：终高谓词（已知且终态→机件原子帧/未知→动画相驱动盒）取代 LocalInStreamingTurn 双路径——local 删除。
- 统一终高店（finalHCache×finalHSeed 合流）+contentTerminal 终态信号参数化。
- viaDrive 机器归属生命周期（垫未清不跳分支）。
- C2（前批）/C3（前批）/C1（本批）/C5/C6 全落地——spec §5.2 六候选全部执行完毕。
- 今日 #544 相关 E2E 54 跑全 PASS；全仓 stability 590/658、未归因 0。

## 观感变化面（验收须知）
- 静态首展（预热 1.2s 未命中时）：单帧原子+幕布 → 驱动盒渐进展开（预热命中则不变）。
- 流式完结后重展开：驱动盒种子追靶 → 机件原子帧（更快）。
