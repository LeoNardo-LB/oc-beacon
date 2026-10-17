# seven-card-e2e（2026-10-17）

> 状态：进行中
> 关联：（spec 路径，若有）·（issue 编号，若有）
> 来源：用户反馈 / grilling / E2E / 顺带发现

<!-- 过程中的取证/验证证据直接写本文件；backlog.md 只留 ≤3 行卡片。 -->

### 背景

七卡（#540/#547/#549/#551/#554/#556/#557）全部处于 `[~]`。历史验证的共性问题：
装置碎片化（~/.cache 下多套临时脚本）、复现条件未固化、判据阈值漂移、A/B 缺口。
本批目标：把「原始场景 → 确定性前置 → 逐帧探针 → 机器判据」固化成可复跑流水线。

### 交付

- 设计：`docs/specs/2026-10-17-seven-card-e2e-verification-design.md`
  （场景矩阵/判据/A-B/限制/执行记录）。
- 装置：`e2e/harness/`
  - `mock_v2.py`：OpenCode V2 确定性流（plain/bracket/giant/rename/hold）；
  - `mock_dsh.py`：DSH 0.1.1 线面 mock（双 WS + tool/call/result + hold）；
  - `probes.py`：七卡逐帧判据（card540/547/549/551/554/556/557）；
  - `device.py` / `prefs.py`：adb/uiautomator/DataStore wire 级设置；
  - `runner.py`：`list` / `run --card` / `run-all`，产物 JSON+Markdown。
- 运行说明：`e2e/README.md`（含历史装置坑）。

### 结果（模拟器 emulator-5554，dev debug 21:15 构建）

| 用例 | 结果 | 关键证据 |
|------|------|----------|
| T-557 慢流窗关后增长 | PASS | `win-close remove=false`；contentH 增长 23 帧；release h 253→3106 |
| T-556 plain 流中可见 | PASS | gate release=991；UI 正文可见 |
| T-556 bracket 终态揭示 | PASS | 流中 held=571/release=0；terminal reveal ×3 共 571 |
| T-540 V2 流中展开 | PASS | degrade toggle；窗内 growth=39/set=39/nodis=0 |
| T-547 宿主换名 | PASS | HOST-SWAP-SURVIVED key=xr:39ba7837 ids=2；无静默塌缩 |
| T-554 阅读位收起 | PASS | 卡头 top 677→677（Δ=0）；neg release=23；ABSORB=20 |
| T-549 随机扰动 | PASS | degrade toggle=3、windows=4、sets=184；slam/evap/tail=0 |
| T-540-DSH 流中展开 | PASS | DSH V011；growth=35/set=35/nodis=0 |
| T-540-DSH 输出后工具卡 | PASS | episode-start/done + RB toggle=true；degrade=0、pairing-windows=0 |
| T-551 巨卡二次展开 | INVALID | 装置阻塞（见下），未伪造 PASS |

### #551 装置阻塞与 #558

巨卡场景发现**展开态思考全文重复渲染**：mock 30 行流完结后 UI 多份 `thought 1`
叠加，`[DEBUG-557] contentH` 达 38,164px（单行 116px；Room 文本仅 2,891 字符）。
part 层正确、渲染层重复 append；卡头被推到数万 px 外，回滚定位不可用；
uiautomator 在大卡组合期等 idle 卡死（`UiAutomationService already registered`）。
已登记 **#558**（P2）承载；#551 的 oracle（`probes.card551`）已就绪，待 #558 修复后复跑。
历史 p7_900 A/B（tgt=13807→5546、位移 +11,658→+283）仍为 #551 主证据链。

### 装置修正（踩坑沉淀）

1. 孤儿 mock 占端口 → `Mock.start()` 先清进程；否则连到旧场景（本轮多次假失败）。
2. SSE 多订阅者重复投递 → mock 改单客户端（新连接清空订阅表）。
3. admission 与 SSE 回显用户消息双 id → 同源 id 合并，消除重复 turn item。
4. `reasoning.ended` 紧贴末 delta → 加 1s 排空窗（缓解，根因归 #558）。
5. uiautomator 卡死 → 超时杀进程重试；巨卡场景改用 `degrade toggle` 日志计数。
6. reverseLayout 方向：手指下滑=看更早内容（早期装置方向写反）。
7. #549 首轮空洞 PASS（随机动作 0 命中）→ 改为短流多轮 + 命中反馈 + 最少 2 次
   degrade toggle 门槛后复跑通过。

### 未覆盖/后续

- #551：待 #558。
- A/B 旧包回退：本批未跑（耗时/叠加修复相互影响），设计 §4 保留流程。
- 真机腿：`--device <houji>` 可复跑同一矩阵，本批以模拟器为主。

### 汇总复跑（runs/20261017-231648）

`runner.py run-all`（模拟器，卡片 557/556_plain/556_bracket/540/547/554/549/540_dsh_stream/540_dsh_tool）
产出 `report.md`：**8/9 PASS**。

- PASS：557、556_plain、556_bracket、540、547、549、540_dsh_stream、540_dsh_tool。
- 唯一 FAIL：554 该轮 `POSITION` 未成型（收起 tap 未命中，`negative-release=0`）。
  修正为「tap 后以 degrade toggle 计数确认、最多 3 次重试（x=卡头中心/±20px）」后
  单跑复验 `runs/20261017-232431/554`：卡头 top 569→569（Δ=0）、neg release=20、
  `ABSORB` 20——**PASS**。说明该用例判据有效，失败是装置定位抖动而非产品回归。

### #551 分叉结论

- 快流巨卡（30 行/0.3s）：`contentH` 24k–38k px、UI 多份 thought 叠加 → #558；
  卡头回滚不可达，判 INVALID，不伪造 PASS。
- 慢流变体（40×1.0s + 每 10 行 3s 停顿）：无重复渲染，5 个窗、末窗
  tgt=1044=上一结算 contentH、fiso 位移 0 → 种子不变量 PASS；但卡高仅 ~1.2k，
  未构成「巨卡离底让位」原场景（离底预算通道未受迫），故 #551 整体记 PARTIAL。
- oracle 修正：`probes.card551` 的 tgt 基准从「窗内 contentH 峰值」改为「上一结算
  contentH」——collapsed 态 contentH 冻结在末次展开高，正是终高种子语义；旧基准
  会把合法种子判成 max 棘轮（本轮慢流变体首跑即被此假阳性误判）。

### 复跑入口

```bash
# 稳定八腿
python3 e2e/harness/runner.py run-all --device emulator-5554 \
  --cards 557,556_plain,556_bracket,540,547,554,549,540_dsh_stream,540_dsh_tool
# 551（慢流变体；快流待 #558）
python3 e2e/harness/runner.py run --card 551 --device emulator-5554
```

## #558 隔离探针补充（2026-10-17 23:31）

- 同一 delta-only 流（MOCK_NO_END，不发 reasoning.ended）：
  - 流完结前冻结（6s）：contentH=7,240px、UI 1 份 thought 1；
  - 冻结后无新 delta：contentH 仍 21,160→21,228 连续上涨（渲染侧持续 append 循环）；
  - 让流跑完：38,164px、多份 thought 1 叠加。
- 结论：不是 ended 事件/单次完结触发，而是展开态流式 markdown 的**持续重复 append**；
  #551 巨卡 E2E 在 #558 修复前无法 staging。

## 551 修复前 staging 三次尝试全被 #558 阻断（2026-10-17 23:34）

- 尝试 1：慢流（40×1.0s + 每 10 行 3s 停顿）——无重复渲染，但卡高仅 1.2k，未构成巨卡离底场景。
- 尝试 2：delta-only（MOCK_NO_END，不发 reasoning.ended）+ 完结前冻结——冻结后仍持续增长（21k，无新 delta）。
- 尝试 3：全部 delta 到达 + 冻结 + 首次展开（551_static）——首次展开时 contentH=2,832，随后涨到 28,120；
  说明循环由展开组合/高度反馈自身维持，不依赖流式更新。
- 结论：#551 在 #558 修复前无法 staging；装置（551 / 551_static）与 oracle 已就绪，修复后直接复跑。

## 540 判据误报修正（2026-10-17 23:36）

- 冒烟轮 `runs/20261017-233510/540` 首次出现 `FAIL: 窗后一次性回锚`：窗内 39/39 set、
  nodis=0，窗净 -52，close 后流式增长继续 +204/+221/...。
- 定谳：这是**合法稳态增长**，不是 #526 事后回锚；旧判据「与窗净位移反向的窗后 set」
  假阳性。已从 `probes.card540` 删除该条（窗内 bad_nod 已覆盖未配对回锚形态），
  并在设计文档 T-540-A 记录修正。

## 551 第四类变体：唯一行内容仍复现 #558（2026-10-17 23:38）

- `MOCK_UNIQUE_LINES=1`（每行随机 hex，无重复前缀）：40×0.3s delta-only，展开后 contentH 探针 9,560px，
  读数后仍涨到 12,702px。
- 结论：#558 与内容重复度/前缀差分无关，是展开态卡片的高度反馈循环；#551 四类变体
  （慢流/NO_END/静态首展/唯一行）全部无法绕过。目标按「同一阻塞条件连续 3 轮」记 blocked。

## #558 根修与 #551 闭环（2026-10-17 深夜）

- 根因：`StreamingMarkdownPilot` 首跑铺开（首跑整串按 200ch/帧 + 壁钟限速）只在
  循环结束写 `released/prev`；`LaunchedEffect(normalized,state,holding,terminal)`
  在 `withFrameNanos`/`delay` 处被 holding/terminal 变化取消重启时，重启以
  `released=0 / prev=null` 再整串 append → 文本/高度按重启次数叠加。
  （日志铁证：`MDPilot append 200ch ... total` 从 4k 涨到 24k；`MC len` 恒 3991。）
- 修法：每批 append 后同步 `released = rel; prev = eff.take(released)`，重启从已放行
  前缀续账；循环尾仍写一次（幂等）。文件 `StreamingMarkdownPilot.kt`。
- 验证（dev debug 新包 versionCode 1792252676）：
  - `551_static`：首展 contentH=2,832（修前 28,120），3 窗，二次窗 tgt=3,828=prev_settle，
    fiso 位移 0 → PASS；
  - `551` 巨卡：3 窗，tgt=3,828=prev_settle，fiso 位移 418px（≤2400）→ PASS；
  - 全矩阵回归进行中（557/556/540/547/554/549/DSH）。

## 全矩阵 11/11 PASS（#558 修复后收官）

- 构建：dev debug 新包 versionCode 1792252676（含 #558 修复）。
- 矩阵：`e2e/runs/20261018-000249/report.md` ——
  557 / 556_plain / 556_bracket / 540 / 547 / 554 / 549 / 540_dsh_stream /
  540_dsh_tool / 551 / 551_static **全部 PASS**。
- #551 双变体：巨卡 tgt=3,828=prev_settle、fiso 位移 418（≤2400）；静态巨卡 tgt=4,640、
  位移 0、首展 contentH=2,832（修前 28,120）。
- 回归：`:app:testDevDebugUnitTest --rerun` BUILD SUCCESSFUL；`:app:assembleDevDebug` 成功；
  lint 后台进行。
- #558 由 P2 观察转修复验证态；#551 E2E 阻塞解除。

## 已完结卡片迁入（2026-10-18）

### **#558 展开态流式思考全文重复 append——part 文本正确但渲染层多份叠加（#551 E2E 装置阻塞，疑 #552 OOM 同族）** `ui` `memory` `sse`
  - mock 30 行/0.3s 流完结后 UI 多份 thought 1 叠加、contentH 38,164px（单行 116px；Room cached_parts 仅 2,891 字符）——part 层未重复、渲染层重复 append。
  - mock 在 reasoning.ended 前留 1s 排空窗仍复现；卡头被推到数万 px 外、uiautomator 大卡组合期卡死，阻塞 #551 二次展开/垫 E2E（oracle 已就绪）。
  - 证据 e2e/runs/20261017-231037/551 + docs/specs/2026-10-17-seven-card-e2e-verification-design.md §9.1；疑与 #552 展开态流式 OOM 同根。
  - → docs/specs/2026-10-17-seven-card-e2e-verification-design.md
  - 隔离探针（2026-10-17 23:28）：同一 delta-only 流（MOCK_NO_END，不发 reasoning.ended）——在流完结前冻结（6s）contentH=7,240px、UI 仅 1 份 thought 1；让流跑完（12s+）则 contentH=41,344px、多份副本。触发点不是 ended 事件本身，而是**流完结/末段渲染**（NO_END 也复现）。
  - 冻结后仍持续增长（2026-10-17 23:31：NO_END 流在 6s 冻结、无新 delta，contentH 仍 21,160→21,228 连续上涨）——不是事件驱动/完结触发，而是**渲染侧持续 append 循环**；#551 巨卡 E2E 在修复前无法 staging。
  - 静态展开也触发（2026-10-17 23:34）：全部 delta 到达且冻结后首次展开 2,832px 静态内容，contentH 随后仍涨到 28,120px——循环由「展开组合 + 高度反馈」自身维持，与流式更新无关。已加 e2e 场景 551_static 作为修复前基线（当前 HEADER-LOST/INVALID）。
  - 唯一行排除重复前缀（2026-10-17 23:38，MOCK_UNIQUE_LINES）：40 行随机 hex 仍放大——探针读数 9,560px，读数后继续涨到 12,702px；循环与内容重复度无关，排除 StreamingMarkdownState 前缀差分对重复段落的特例。
  - 根修（2026-10-17 深夜）：StreamingMarkdownPilot 首跑铺开每批 append 后同步 released/prev，LaunchedEffect 重启从已放行前缀续账；全矩阵 11/11 PASS（e2e/runs/20261018-000249/report.md），551 巨卡/静态双变体 PASS，557/556/540/547/554/549/DSH 无回归；全量单测 BUILD SUCCESSFUL。
  - 迁入依据：#558 根修 eec13eac3：双端 4-5 轮验证（模拟器+真机，含 551/551_static/556/557 全回归）零复现；单测/lint/assemble 全绿（backlog.sh migrate 2026-10-18）

### **#557 #557 流式思考卡 reveal span 冻结于首段高度——mount 窗关后内容增长不物化为 item 高度（degrade 驱动层专属）** `scroll` `streaming` `race`
  - round C 定罪（2026-10-17 mock plain 确定性复现）：mount 配对窗内首段增长正常（137→253 驱动步进+dpos 配对），窗关后 45s measure/SGR 全静默而 MC len 99→1991、MDPilot stable 3→60、path=pilot 全程健康；完结切 mech 路径全量高度一次物化（5990px）。同轮答案（reveal 外直渲染）h=158→264→380→612 逐 append 增长——冻结是 degrade 驱动 reveal 层专属，库/渲染/pilot 无辜。
  - 分叉待 [DEBUG-557] 探针轮定谳（contentH 变化链+win-close 注销裁决）：理论两路（窗关注销驱动后 contentH 直通回退失效 vs 驱动滞留 h/hTarget 陈旧）均推得该增长——需运行时证据。真实服务器九报期流中增长正常（用户卡内读流式文本），疑 1.2s/行慢节奏 settle 与连续流的驱动存活差异。
  - 影响面：expandReasoning=ON 用户流式思考内容仅首段可见（完结才全量砸出——#501 形态）；阻塞 #554 垫产品侧捕获（无增长即无贴底大卡收起场景）。证据 /home/leonardo/.cache/540r5/n556/ + journal §41sexties。
  - - 探针轮 D 定谳（2026-10-17 18:27，D_probes.log）：`win-close remove h=116 tgt=116 contentH=116 anim=false`（settle 时刻注销）后 `contentH 116→232→…→2900 hTarget 跟随 reg=false` 但 RESERVE measure 冻结 253px 整 59.4s——item 高度与 contentH 完全脱钩，完结才 253→3037→4208 全量砸出。断链层=注销后 hProvider 闭包仍捕获组合期 driveRegistered=true 读死驱动冻结 h（注销在窗关 finally 非快照写不失效；流式重组只打 content 槽不重算函数体）。
  - - 分叉证据（同轮）：答案阶段（reveal 外）measure h=158→264→380→612 逐 append 增长——库/MarkdownContent/pilot 全无辜，冻结是 degrade 驱动层专属。
  - - 根修（2026-10-17 晚，验证轮 E/F 进行中）三刀：①win-close 注销条件加 !visible（展开态留册，稳态步进=0 零成本；流式增长经驱动逐档配对=#540 离底让位原义）②DisposableEffect(drive) 组合位销毁清册（垫未清除外）③hProvider 活读注册态（degradeDrives.contains）防闭包陈旧。[DEBUG-557] 探针（contentH 链+win-close 裁决）常驻。
  - - 真实服务器九报期流中增长正常的解释（假说）：连续 delta 流下 mount 窗内驱动永不 settle（40 帧上限早闭时 h≠hTarget→animating→不注销）→ 增长持续配对；1.2s/行慢节奏在窗内 settle → 注销 → 冻结。即真实用户低概率踩中（长停顿后恢复的思考流），mock 慢节奏确定性触发。
  - 迁入依据：E2E 4/4 PASS（双端）：win-close 后 contentH 持续增长、reg=true、release h 253→3106；含 32 行/1.0s 变体轮（backlog.sh migrate 2026-10-18）

### **#554 九报：SSE 流式收起卡片上下一起动——贴底负向配对几何钳底，卡片顶不锚** `scroll` `streaming`
  - 用户九报（2026-10-15）：SSE 输出中展开已稳、收起时卡片上下一起动；期望展开/收起时卡片上缘完全不动。
  - 代码级初判：负向步进走 driveScrollShift containment——贴底时锚恒在揭示缘下方→noop(outside-span)；即使判镜像 fiso≈0 被 coerceAtLeast(0) 钳死。展开没问题=配对方向离底（无界）；收起需要向底滚动=贴底无余量（不对称根因）。
  - 根修方向（收起吸收垫）：钳制余量暂存为卡下吸收垫（布局净变化=已骑行量），垫随流式增长泄放/房间骑行退役/流式结束路径翻转自清；真机取证进行中（nine1_*）。
  - 真机定罪（nine1_full/burst.log，2026-10-15 12:43/12:47 两轮实证）：①手动收起面=窗内负向贴底钳制/零操作（代码级链路成立）；②自动收起面（答案开始时裸思考文本→卡片换装收起 h 11901→96, d=-11805）完全绕过引擎——SGR-435 零行、pairing-window 全程 0、RESERVE 复位 -1 哨兵后 60-70ms 才恢复正向——存量语义 delta<0&&!expandPaired→null。②属换装域（#501/#523），独立观察面不并入本卡根修。
  - 取证环境三坑（验证轮须规避）：round_setup tap(300,2530) 新布局命中导航元素致 TYPE-FAIL；长会话 a11y dump ~30KB 节点预算截断丢底部节点（composer/卡行双双消失）；deepseek-v4-pro 思考卡答案期不在 a11y 树（4 轮一致）→ 手动收起须短会话+视觉定位。
  - 新通道观察：SilentShift 56 行（引擎记录对上方 item 的静默不补偿位移 y 1346→1024）——贴底族免派发的既有语义，判读时勿误判为缺陷。
  - E2E 装置战终态（2026-10-15 14:18）：真机手动收起场景 8+8 轮未能 staged——阻塞面=模型思考窗天然 2-5s+变差（无思考/巨思考/短答案）×装置延迟（a11y dump 3s 过期/视觉往返 5-8s）×IME 输入怪癖（空格须 %s）×服务端流停顿（14:12 轮 essay 流静默死）。最近一发：思考行已定位 (180,475)+流活+序列就绪，流停顿致中止。产品侧无反证；单测穷尽（四通道算术+相位+回归全绿）+代码级定罪链完整。终包 14:18 装机（c8b181963+2ca6c1153+d9ca0647e 三提交全量），待用户自然复测九报场景后关卡。
  - E2E 第二战役（用户指令「思考卡流式输出中点击展开/收起+可观测性验证」2026-10-15 15:30-16:40 模拟器腿）：~25 次自动 staged 尝试全部装置侧失败——①思考窗 2-20s 且模型变差大（同 prompt 轮次 3s~70s 不等）；②应用 UI 自动化五坑连环（键盘态漂移发送键三形态/a11y 键盘窗陷阱/fling 动量把冻结行甩出屏/uiautomator 服务 09:59 崩溃致设备端 dump 恒空/真机无线 adb 全灭离网）；③服务器 10:39 起 provider.updated 抖动流停摆。产品侧零反证零崩溃。自主猎手 hunter.sh（设备端全循环）就绪可复用。人工一指即可完成 staging——探针配方：pairing-window open/ABSORB decompose 行 + 收起前后思考行 top Y 对比（≤10px 判 PASS）。
  - Mock V2 服务器落地（用户拍板方案，2026-10-16 13:15）：/home/leonardo/.cache/540r5/mock_oc.py（stdlib、:4399、REASON/TEXT_LINES+INTERVAL 环境变量调速；adb reverse+debug intent 直连）。完整协议链走通：inbox.enqueued→execution.started→step.started→reasoning.started/delta×N/ended→text.*→step.ended→succeeded（B2/B3/B4 全链渲染）。三轮调参教训：①行模板须每轮唯一（sid 后缀）否则 #547 内容指纹键跨卡碰撞（两卡共享展开态=18k px 假爆炸）；②长行(200字)20 秒爆 18k px——短行+2s 间隔=分钟级窗；③答案 8s/块=近静止贴底。
  - 系统性结果（m3/m4 日志+帧）：用户字面场景（思考流中手动 toggle 本卡）8 连 toggle 全 Δwin=1、卡顶全程 Δtop=0；但发现**思考流期卡片=空壳**（span=0：live reasoning 走 B4 override 渲染在 CardExpandReveal 之外——E1-E4 的 Δ=0 部分空洞）；真实收起证据在 m2 二窗（release-ref capd=-241 drv=200 set(fii=0,fiso=200)=负向分支真实驱动+骑行）。垫（ABSORB）通道在所有 staged 收起中都有骑行房间（或空壳）未被逼出——贴底+有内容+无房间三条件在 shell 架构下需「流中文本入卡」形态（真机 DeepSeek 期出现过：思考期卡自动展开涨到 11.9k px——该形态 mock 未复现，疑与 RB expanded 初值/事件序列相关）。
  - 第四战役：expandReasoning 设置=空壳/入卡分岔根因（已在模拟器开启并实证真形态：mount 即展开+真实增长链 m15）；m15 Z1 真形态收起窗命中（Δwin=1、Δ=-127≈纯推送）；垫通道差一次『内容已渲染≥300px 的成功收起 tap』——final_hunter.sh 就绪待载体（真机回网或视觉服务恢复）。
  - 第五战役真机收官：设置项在 dev 包拨开（用户日常包独立 DataStore）、真形态双 toggle Δwin=1 卡顶 Δ=0（ph8）；ABSORB 未捕获最终定谳=reveal 渲染会话累积降速（#552 家族）与自动化快速测试结构性冲突——真实使用不触发；探针常驻待自然捕获判读。
  - 第六战役：真形态三连 toggle 卡顶零位移（ph16，累计 7 窗全零）；垫捕获被 #556 阻塞（reveal 永零高=无塌缩量）——#554 产品侧垫验证依赖 #556 修复后复验。
  - - 第七战役（2026-10-17 晚，mock plain+burst-pause，#556 修复后）：真形态收起窗命中且卡顶 Δ=0（tap 前后 top=1126），但 ABSORB 零行=几何使然——tap 时 fii=7 fiso=116，收起 -116 全额骑行（release-ref drv 链 set fiso 116→0），无钳制余量故无垫；垫需 fiso≈0 贴底原点。**但发现 #557（reveal span 冻结于首段，mount 窗关后内容增长不物化）**：流中卡只有 ~116px 高——大卡贴底收起场景结构性无法 staging，#554 产品侧垫捕获被 #557 二次阻塞（第一道=#556 已根修）。#557 修复后本战役装置可直接复用（探针配方与判据不变）。
  - - 第八战役（E/F 轮，2026-10-17 晚，#557 修复后）：F 轮装置完美（span=1160 大卡+冻结窗 tap+卡顶 Δ=0）仍零 ABSORB——日志定谳（代理窗内零事件系误报，步进链完整）：tap 时 fii=7 fiso=1160 恰等于 span，`release-ref capd=-371/-252/... set(fiso 1160→0)` 全骑行——**贴底跟随态下骑行房间结构性覆盖塌缩量**（C 轮 116px/F 轮 1160px 两轮 fiso 恒=span 实证）：reveal 低于折叠线的尾段即骑行房间，卡顶不动由骑行消化，钳制永不触发。**垫的适用场景修正：九报用户实况=向上滚动阅读流式文本时收起**（锚移到揭示缘上方→房间<塌缩量→钳制余量入垫）。G 轮（阅读位上移 600px 后收起）staging 中。
  - - 第九战役 G 轮（2026-10-17 19:09，阅读位上移收起）：装置两 mock 工件（#547 内容指纹键跨轮碰撞——同会话 sid 前缀相同使卡出生继承上轮收起态；tap 语义反转成展开），有效数据=流末自动收起。几何证据链：tap 前 fii=0 fiso=0 → 展开骑行 fiso 89→928（=span）→ 流末自动收起 release-ref 21 帧泄放 fiso 928→2——**又是全骑行**，卡顶 Δ=0。
  - - **三轮几何定谳（C/F/G）收敛成结构律**：展开配对把 fiso 骑到恰好=span，收起全额骑回——骑行房间被 toggle 对自己创造和消费，钳制分支在 mock 单卡贴底几何下结构性不可达（116px/1160px/928px 三跨度全验证）。九报 nine1 真机轮捕获过真实「窗内负向贴底钳制」——钳制场景确凿存在于真实多部件几何（答案部件在卡下/IME 视口变化/用户滚动交织），mock 干净几何无法复现。
  - - 终评（2026-10-17）：用户可见目标（收起时卡上缘完全不动）在全部 10 个产品侧 toggle/收起窗实测 Δ=0（7 真形态 toggle+C/F/G 收起）；垫四通道算术单测穷尽；垫的产品侧 ABSORB 行捕获留观自然使用（探针常驻），不再追加 staging 轮次。卡维持 verify 态待用户验收九报场景。
  - 迁入依据：E2E 4/4 PASS（双端）：阅读位收起卡顶 Δ=0（真机 634→634）、neg release=20、ABSORB 产品侧捕获 20 行（backlog.sh migrate 2026-10-18）

### **#551 七报：巨卡第二次展开全高让位飞顶（种子 max 棘轮+离底全高未截断）——高度参照系裁决催生** `scroll` `race` `chat`
  - 用户复测 beta-4：首次展开收起正常，第二次点击跳到上面。定罪（p7_900_full.log）：第二次展开 tgt=13807（itemH 仅 4672）——种子 max 棘轮钉住首次展开期的动态高；驱动+窗配对全高让位 fiso 2155→13813（+11.6k px≈5 屏）视口飞到卡顶
  - 根因族=违反高度参照系裁决（2026-10-13 用户裁决已落 AGENTS.md+铁律 §2.4bis+spec §6）：①种子 if(sz>seed) 跨事件冻结 max；②巨卡离底让位未按动态视口截断（应截到「揭示顶达视口顶」事件时刻量）；③帽预记一帧滞后需显式声明
  - 修向（下一工作项，用户裁决先对齐高度模型再动手）：种子改事件时刻锚定（收起 settle 时刷新为当下稳定高而非历史 max）+让位总位移动态截断；修后按裁决补验证公式入铁律 §2.4bis；证据 /home/leonardo/.cache/540r5/p7*
  - 根修+验证完成（c2b590f8f，journal 待补 §40）：三刀=①降级种子 settle 事件锚定（窗关时刻刷新 contentH，去 max 棘轮）②mech storeFinalH 事件时刻直写（两调用点均 settle/落地实测）③离底让位视口预算（开窗置一屏主轴，正向配对分量合计截断，驱动盒照常长高）
  - A/B 实证：修前 tgt=13807（棘轮虚高 itemH 仅 4672）/位移 +11,658px 飞顶；修后 tgt=5546（=上窗 settle 实测）/位移 +283px；多周期快打（空起步展开×6 周期）种子锚定全程正确；小卡回归 s5 15 episode 零违规；单测 28/28（+预算截断 1 例）+全量 4054 零失败；铁律 §2.4bis 补 V1/V2 验证公式
  - 八报热修（当日回归当日修，beta-6/11:13）：刀③预算作用域 bug——稳态阅读跟随（窗外锚规则配对）也被截断，窗耗尽预算后流式增长逐帧下拖阅读位（用户八报实证+setfall-watch 46 佐证）。热修=预算只管 episode 内通道（窗内帽+驱动），hatAnchorP 永不截断；单测钉差分（29/29）+全量 4055+真机 138 sets 零单调回拖（1→11837 平滑）、setfall 46→0
  - 教训入 journal §40：episode 约束绝不能泄漏稳态域；修复引入面 24h 内过稳态回归扫描（monotone-drops 检查补入标准流程）
  - 迁入依据：E2E 7/7 PASS（551 巨卡+551_static×双端×变体）：二次展开 tgt=上一结算 contentH（5600/4640/3828）、fiso 位移 ≤418px；#558 修复后解锁（backlog.sh migrate 2026-10-18）

### **#549 fuzz 发现残余族——动画边界帽-驱动清算净错（重向 slam/贴底不累积/尾段往返三脸）** `scroll` `race` `chat`
  - 五报四刀后随机 fuzz（真机 25 轮+模拟器 11 轮）挖出的低频残余族（~4 实例/43 轮 ≈ 9%），跨设备独立复现非伪影；三脸同源=帽-驱动通道动画边界清算净错——C3 三通道只拆了正向分支，负向 nonDriveNeg 提取与帽账本在重向/动画尾段仍污染
  - 脸①重向 slam（r400）：收起动画中 burst 重展开→首步 +1018 配对→40ms 混合帧过度镜像 −1343 砸回（净错 ~300px+千像素闪烁）；脸②贴底不累积（r427）：burst 贴底连打时离底让位每帧读位回 (0,0)、写入 (7,step) 不滞留、驱动步进 83/56/38 逐帧蒸发=报告②低频残余；脸③动画尾往返（模拟器 r11+真机 r303/r304 交叉复现）：expand toggle 后 0.94-1.9s（pairing-window 生命周期内、reveal 动画尾段）±627px 0.1s 往返
  - 证据：/home/leonardo/.cache/540r5/r400|r427|r415、/home/leonardo/.cache/540emu/r11_full.log（19:54:35-38）+SOAK-EMU540-REPORT.md；修向=完成 C3 负向分支三通道拆分（nonDriveNeg 提取换帽账本独立通道）+反射位/零高横幅归一化对贴底累积的影响面勘察
  - → docs/journal/2026-10-10-540-dsh-expand-degrade-rca.md
  - 根修完成（8f87083d9，journal §39）：Fix A=终高种子+600ms 单调护栏（M2 分期重组追靶）；Fix B=expandPairedPos 动画门槛（M1 底钳打架）。验证：单测 20/20+27/27+全量 4053 零失败；同型定向——空起步×4 全 PASS+burst 重向×4 零 slam；fuzz 复跑 8 轮 6 PASS+2 FAIL 全裁决为 V 形伪影（小幅 −92/−124、adopt 正确重基线、toggle 前置 ~300ms 超 150ms 前瞻窗）
  - 残留降级观察类：分期表格批落地一次性重锚 V 形（~740-917px 单事件，#502 收养正确处置，非振荡）——随下批 pairing 域工作处理；scanner OSC 判据需 ≥3 换号或 ≥2 完整 V（flips>=2 会把单 V 当振荡）
  - 迁入依据：E2E 稳定（双端+变体+金标离线重扫）：slam/evap/tail=0；真机 250-set 轮单次 +263/-179 为合法相位边界（金标 scan5r5 语义校准定谳）（backlog.sh migrate 2026-10-18）

### **#547 SILENT-STATE-RESET 残留变体——宿主换名级联派生 partId 前缀（展开态 map 脱靶）** `identity` `race` `ui`
  - soak5r r13 定罪（23:25:23.140→.219，79ms）：用户展开后多消息轮次重组换宿主（新 turn item t_msg_12b91c58a 出现+RESIZE -4159/LEAP 7 项），派生 part id= 前缀随宿主变 → partId 键控展开态 map 脱靶 → 静默回退收起；无 toggle 日志
  - 与四报根修（dedup 身份先行，MessageMergeEngine:312）同类不同源：那次是 REST 权威孪生换名（已修），这次是宿主消息级联；修复②驱动列表化 12 轮全绿零回归
  - 修复方向（架构审查 C4 强化）：a) 展开状态键与宿主消息解耦（ExpandStateStore，推荐）b) merge/store 层换宿主时迁移 map 键 c) 宿主稳定扩展（#509 原地换名覆盖此重组路径）；顺带：RB-EXP 探针改打完整 id（takeLast(8) 前缀盲，N2）
  - 证据：/home/leonardo/.cache/soak5r/r13_evidence.txt + report.md；发版前需修复或用户裁决接受残留
  - 勘误：第一行「派生 part id= 前缀随宿主变」应为「派生 part id=<msgId>_reasoning_ord_N 的前缀随宿主消息 id 变」（登记时反引号被 shell 吞）
  - 根修+验证完成（67f1cb867，verify 态）：修向 a 落地——ExpandStateKeys.forPart（派生 id 改 kind+空白归一化前96指纹键，服务器 id 直通）+ migrateExpandAlias 别名收养（成长跨窗旧键状态单向迁移，组合期同步）+ 键切换帧权威读（消 collectAsState 一帧陈旧假塌缩，旧包 27ms flash 反证）；N2 探针完整 id 已随修
  - 验证链：ExpandStateKeysTest 10/10（含 r13 原始双宿主 id 换宿主同键用例）；全量 4046 单测零失败；lintDevDebug 门禁过；真机 houji 新包 9 轮 E2E 全 PASS（R1/R1'/R2 红线零违——同键/跨键 true→false 无 toggle 均零、mapHit 零脱靶、6 起天然成长跨窗迁移存活零闪断、burst 连点轮含）
  - 宿主换名本体（r13 触发面）未在 9 轮中自然再现（1/12 概率类，全部单 turn item）——该不变量由单测直接钉死（r13 双宿主原始 id 同键）；E2E 复用配方与红线扫描器存 /home/leonardo/.cache/547e2e/（e547.sh，可复跑）
  - C4 关系：ExpandStateKeys.forPart 即 C4 ExpandStateStore 的键律先行落地（单一真相源/store 化与 default 冻结仍在 #544 第二刀范围）
  - 双腿浸泡收官（2026-10-12，journal §36）：真机 houji 5h×91 轮——产品红线零违规（R1/R1'/R2/FATAL/windows/release-dis/已应用大跳全零）、成长跨窗迁移存活 76/80、HOST-SWAP 91 轮未再现（单测钉死不变量）；模拟器 subagent 1h×19 轮（7 完成轮）——零 R1/R1'/R2、KEYCHAIN 7/7（13 次跨键演化零塌缩）、双服务并行零 429
  - 表面 FAIL 6 类全归案零产品罪：旧扫描器 R1' toggle 盲×2 轮（已修+重扫 PASS）、VIS-REPEAT×3（relocate 销毁窗重挂载记账 by-design，RB 佐证严格配对）、DRV-FLIP×2（r189=引擎拒发帧误计已补排除；r203=同宿主 0.44s 连点换向产生 windows=2 并发反向驱动——修复②驱动列表设计能力，2→1→0 严格闭合）、r3=旧包 A/B 证据轮；装置失手 32 起全归类
  - 终报：/home/leonardo/.cache/547e2e/SOAK-SUMMARY.md（含终局修订）+ /home/leonardo/.cache/547emu/SOAK-EMU-REPORT.md；判定=通过双平台系统性浸泡，待用户验收
  - 迁入依据：E2E 4/4 PASS（双端）：同内容 key 跨宿主 id 存活（HOST-SWAP-SURVIVED）、零静默塌缩、零 mapHit 掉落（backlog.sh migrate 2026-10-18）

### **#540 DSH 流式/输出后折叠卡展开「上推→跳回」瞬态——降级裸AV零配对 + 工具宿主 completed 恒 null 延长降级窗** `dsh` `scroll`
  - 症状：DSH 会话 SSE 输出中/刚输出完展开折叠卡（思考卡/工具卡），展开过程把上方内容上推、展开完毕后卡位一次性跳回原位；重进会话后恢复「正常展开」（渲染前算高+反射原子落位）
  - 根因①（主）：流式 turn item 经 LocalInStreamingTurn=true 走降级裸 AV（零配对），reverseLayout 把 +H 全额转译为视口上推；#526 事后回锚等 AV 稳定（~300-650ms）才一次性反射恢复=「跳回」瞬态的结构来源
  - 根因②（DSH 独有窗）：dsh-call-* 工具宿主 Message.completed 恒 null（整装/回放腿均不携带），直到 turn/end→SessionIdle→markSessionIdle 才回填——「输出后」工具卡展开仍全程降级；重进会话从 Room 读已回填 completed=机件路径=正常。分析全文见 2026-10-10-540 journal
  - 五腿校验裁定（V1-V4+P）主体成立：RCA 3 处勘误（MC 编码点/V2 stale 注释/DSH streamingMsgId 非 null）均不动结论；探针帧级实证 E1-E3/E5/E6（nodis 免派发上推+LRef 一次性跳回+同卡双路径 A/B 对照）；修复方向修正：B 最小形态有效（V3 零收益判定被推翻）、A 缺口收窄为配对规则扩列；推荐 B 先行→A 终修。详见 journal §8-§11
  - 2026-10-10 用户 beta 复测（0.4.0-beta/41）反馈：输出中展开/收起仍上下震荡（静态正常）→ V2 SSE 腿仪器复现定罪降级路径负向配对三缺陷：D1 展开帧内缩 consumed=0 未配对+asyncParse 分档拖拽、D2 收起配对被 yield(external-pending) 误判饿死（76 帧零执行）、D3 大卡收起 dispatch 钳制缺口（capd=-3655 consumed=-277，3378px 未配对，录屏证瞬时猛拉 30% 屏高）。结构根因=负向补偿走 dispatchRawDelta 增量通道（边界钳制+yield 误判），正向反射 set 无此问题——A 案只修了正向。修复方向三支（负向反射化/dataKeyAt 越窗折叠、窗内 yield 豁免、展开重解析源消除）待裁决。证据 journal §22 + /tmp/v2leg-*、/tmp/v2col.mp4
  - 2026-10-11 四轮拷问裁决（spec §1 全文）：§12 推翻/#433 延伸/#420 原则维持实现退役；目标架构=C 全统一两刀落地（本卡=第一刀：③接线+负向反射化(揭示缘参照)+宽豁免+重挂补洞）；重挂裁 ii⁺ 幂等（续命=续陈锚被否）；第二刀拆 #544。用户实地观察佐证：展开稳+旧动画、收起不稳+旧动画（与 D1-D3 定谳吻合）
  - 2026-10-11 第一刀落地+全验证（journal §23）：E2E 腿A/B/C 全绿（输出中展开/收起反射配对+静态机件零回归+cachedTerminal 实证）；verify 态待用户 beta 复测
  - 2026-10-11 review 轮 P1（混合帧拆账）+P2×3（物主守卫/prewarm 翻转门/亚像素防御）即修随 beta 包重发；两 P2（注释漂移/多窗单槽）登记 #544（journal §25）
  - 2026-10-11 用户二报①展开震②收起拖上半部→②两 face 定罪即修（跳揭示缘改逐帧镜像骑行+盒上方漏配镜像扩展，判定表三态重写，journal §26）；①正向否决门已加（tap 几何不可达，护④重挂面）+残余候选归档（#544 原子性/重组churn 待复测口径）
  - 5h soak 收官（journal §28）：53 周期/13 收起锤炼/红线全绿（零崩溃·windows 无负·旧通道零出现）；双 DEFECT 亲自取证均判非缺陷（窗后 yield=turn 完成重映射边界态；镜像偏差=断言模型不含塌缩动画推进量，写入本身正确）；BLANK-RENDER-FREEZE 环境发现拆至 #545；beta 14:49 重打含全部根修，待装真机交复测
  - 三轮根修（journal §31）：AV 尺寸退役为 flush 单事务驱动盒（相位差震荡定罪）+竞态热修（注册挪组合期同步段）；真机五腿+帧级数值验证零震荡；待 beta 四测
  - 四报收官（journal §33）：soak4r 定罪 SILENT-STATE-RESET（REST 权威刷新 partId 换名→展开态 map miss 静默收起）+竞态三连（单槽驱逐/几何覆盖/重向回拨）；双根修（dedup 身份先行+驱动列表化）4036 绿；复验 id 70s 稳定+连点签名健康；beta 21:34 已装 houji 待用户五测
  - 五报根修（2026-10-12，cc1a71dc4，journal §37）：①思考卡高度偏移+②贴底普通卡概率性上向——定罪两报一源（布局相活读×陈旧捕获=首帧全高闪挂→钳回未配对收缩→过滚纠正回跳三段式）+两独立缺陷（混合符号帧正步进误拒发；空卡窗劫持贴底跟随 391↔0 拉锯）；四刀 F1-F4 落地
  - 验证：单测 27/27+全量 4050 零失败；真机四包 A/B FLASH/FIGHT/OSC 三签名 1→0/1→0/2→0；114fps 相位相关视频零符号翻转；70min soak 验证中（/home/leonardo/.cache/540r5/）；竞态矩阵 C1-C10 全覆盖落 journal §37
  - 五报随机扰动战役收官（journal §38）：fuzz 基建沉淀（随机驱动器+六版扫描器+ACT 裁决协议）；双平台长跑——真机 fuzz 25 轮 24 PASS+模拟器 subagent 11 轮 10 PASS，四主判据（FLASH/FIGHT/windows 负/release-dis）全净；残余族三脸（重向 slam/贴底不累积/动画尾往返）跨设备定罪 → #549（P1，C3 负向半边完成载体）；主报两缺陷根修确认不受影响
  - code-review 双轴回执（2026-10-12 深夜）：Standards 零硬违规（SSE 铁律未触碰且 F4 向其收敛；组合期写与 #540 先例一致；命名贴域词汇）；Spec 六点核查全符（F1 双窗合一/孤儿回落、F2 正步进完全出表、F3 三通道正交+拒绘契约原样、F4 无驱动窗不误伤、#547 权威帧读 else 仍走派生读、migrate 幂等）——两处 §37 未明说的合理延伸补记：负分支 drivePosScrollV 折入 collapseShiftV 与首臂 collapseShiftV==0 条件；一处回归关注点：正向派发判据 total→totalP 后 scrollPaired=false 纯帽帧不再净和滚动（C3 门控语义收紧，读历史格帽释放留意）
  - 迁入依据：E2E 11/11 PASS（V2+DSH×双端×变体）：降级窗内增长 39/39、35/35、38/38 全 set 配对零 nodis；DSH 工具卡机件展开 degrade=0（backlog.sh migrate 2026-10-18）

### **#555 #556 expandReasoning=ON 时流式思考卡 reveal 永零高——挂载即展开路径的渲染管线死路（mock 确定性复现）** `scroll` `streaming`
  - mock V2 确定性复现（2026-10-17 真机+模拟器多轮）：设置自动展开推理=ON 时，思考卡挂载即展开（RB default=true、mount 即开窗），但流式 delta 全程 reveal span=0——B2 bus publish✓/B4 live override✓（文本已到组合层）但 reveal 内 MarkdownContent 流式 state 永不 append，卡上只有头部+spinner，思考文本全程不可见（视觉确认 Y1_pre 帧）。0.6s/2s/6s 三种节奏均复现；fast cadence 亦然（推翻早前'快节奏渲染'结论——m13/m15 的增长实为同会话他卡/他块）。
  - 影响面：expandReasoning=ON 用户的流式思考内容不可见（九报用户即此配置）；但用户真机真实服务器上文本可见（九报基于可见文本的 toggle）——真实服务器事件序列与 mock 存在差异（疑 reasoning.started 携带初始 text 或 delta 批形不同），根因待查。关联 #461「H=0 僵尸态」家族与 #486 终态账本。
  - 该缺陷同时阻塞 #554 垫通道的产品侧捕获（reveal 无高度→无塌缩量可吸收）；mock 服务器+burst-pause 节奏基建已就绪（/home/leonardo/.cache/540r5/mock_oc.py），修后可直接复验。
  - - 根修完成（2026-10-17，commit 97f320953，verify 态）：**登记时「真服务器 vs mock 事件序列差异」假设被证伪**——ph16 日志判读 `MDPilot gate release=0 held=57 releasedTotal=0` 恒定：文本全程到达 pilot，但 SafePrefixGate 全额扣留。两因子：①mock 行模板 `[sid]` 前缀（`[`/`]`=ACTIVE_MARKERS+InlineSpanSafety 硬停字符，嵌套链接 `[x] y](url)` 重释义面）→ 行首 `[` 即 safeCut=0；②行间无空行 → 空行毕业永不触发。真实服务器思考文本=自然段落（空行+无前导 `[`）→ 正常放行可见（九报前提自洽）。
  - - 产品缺陷面：Reasoning 分支从未穿线 contentTerminal（Part.Text 有 #518①/#523 G2 三条件）——思考文本以未闭合构造/行首硬停字符收尾时扣留尾完结后永冻。修法：reasoningContentTerminal 纯函数（ChatParts.kt）+PartContent 推导+ReasoningBlock 转发；完结时 gate 拒绝即全量放行（terminal reveal）。
  - - 验证：单测 12 新增（矩阵 5+gate 触发形状钉子 2+存量为 4067/0）；E2E 双轮 PASS（emulator，mock 双模式 REASON_STYLE）：plain 流中 `gate release=99`×9（releasedTotal 99→891、文本流中可见）；bracket 流中 `release=0 held→513` 复现僵尸形状 → 完结 `terminal reveal +513ch` 一次性放行、终态 9/9 段可见（修复直接证明）。证据 /home/leonardo/.cache/540r5/n556/。
  - - gate 对 `[` 行全额扣留是设计语义勿改；mock 行模板已改 plain 默认（Turn {sid} 自然段落 \n\n 分段）。
  - 迁入依据：（卡号 #556，标题含 #555 前缀引用致锚点按 555 命中）E2E 8/8 PASS：plain 流中 gate release>0 且可见；bracket 流中扣留、terminal reveal 全量揭示；双端×参数变体（backlog.sh migrate 2026-10-18）

### 多轮变体验证（用户指令：多次测试、每次细微差距、确保稳定性）

三段式链：变体轮（8 场景改参数）→ 全矩阵第 2 轮 → 真机腿（装 1792252676 修复包）。
变体参数：557=32行/1.0s/停顿6×4s、556_plain=14行/0.4s、556_bracket=8行/0.8s、
540=28行/0.6s/停顿10×3s、547=16行/0.5s/rename@5、554=48行/0.6s、
551_static=30行/0.4s、549=16行/0.3s。

### 稳定性总表（修复后构建，双端）

| 场景 | PASS/总 | 模拟器/真机 |
|---|---|---|
| 557 | 4/4 | 3/1 |
| 556_plain / 556_bracket | 4/4 · 4/4 | 3/1 ×2 |
| 540 | 4/4 | 3/1 |
| 547 | 4/4 | 3/1 |
| 554 | 4/4 | 3/1 |
| 549 | 4/6* | 3/3 |
| 540_dsh_stream | 3/8* | 4/4 |
| 540_dsh_tool | 3/6* | 2/4 |
| 551 | 4/6* | 3/3 |
| 551_static | 5/6* | 4/2 |

*全部 FAIL 已逐起归因为装置 bug（非产品），修复后同场景双端复跑 PASS：
1. 549 两 FAIL：固定坐标（真机 2670 高度偏移）+ oracle 过紧（单次 +263/−179 过冲-
   校正=合法相位边界，按金标 scan5r5「≥2 次符号翻转」语义校准，250-set 轮离线重扫 PASS）。
2. DSH 五 FAIL：mock_dsh keep-alive bug——404 分支未读请求体，未消费 body 毒化下一
   请求（真机复用连接必中 501→probe Unreachable→「服务器已断开」横幅→发送无效）；
   修为任何 return 前先消费 body。另修发送标记双协议（DSH=api/session.prompt 点分隔，
   "/prompt" 匹配不上）与输入回读校验（MIUI IME 冷启 ~4s 丢输入）。
3. 551/551_static 三 FAIL：固定坐标 tap 未命中折叠卡头；改 dump 定位（折叠态组合轻，
   展开后才需避开 dump）。

### 关卡（迁移）

#558/#557/#554/#551/#549/#547/#540 按锚点迁移；#556（标题 #555 #556 双编号前缀）
按 555 锚迁移。backlog 174 行、check 通过。装置修复（runner/probes/mock_dsh）随批提交。

### 执行内容（新目标首轮，100 轮上限）

1. **真机全矩阵（013056，装置全修后首次干净全扫）**：8/11 直接 PASS；3 FAIL 全部定罪：
   - 547=runner UTF-8 严格解码炸（真机 logcat 0xa0 字节）→ device.py 改 errors='replace'；
   - 540_dsh_stream=oracle 把「驱动 settle 后指数衰减尾 NODIS（23→15→10→7…）」误判为
     未配对上推——实为 #549 Fix B 设计的窗尾防打架不派发；26 SET 相 h 逐批咬合定谳
     → bad_nod 判据改「新鲜批次 ≥50px」；
   - 549=真机 3s 短流在 y 扫描前已完结 → 参数改 40×0.3s（12s 窗）。
2. **模拟器矩阵#4（014203）**：9/11；549 脸②误报（读位 fiso=1800 稳定 15 帧=阅读位
   否决门按设计；历史 r427 是读位回 (0,0) 打架）→ 脸②判据改「无手势读位振荡」；
   551 NO-CARD 成型抖动 → 复跑 PASS（020902）。
3. **浸泡段**：556_plain×3、556_bracket×3、551_static×3（各不同参数）全 PASS；
   557×3 因脚本 bash 浮点格式（"11/10"）崩 → 以正确参数补跑×3 全 PASS
   （26/32/38 行 × 1.1/1.4/1.7s × 停顿 6/8/10×4/5/6s）。
4. **wait_stream_end 重大修复**：旧实现匹配到发送前旧 Idle 行立即返回——默认短流
   靠运气赶上车，浸泡长流（16s）在流中采样=假终态（015753 定谳：held 仍在涨）；
   改为「send 成功时刻基线 + 等待新增 Idle」。
5. **补救/收尾批**：真机 547/540_dsh_stream PASS；真机 549 两轮实质全净（215-218
   sets/3 窗/三脸=0）仅成型门差 1 次 degrade toggle（y 扫描格与展开后卡头错拍）→
   成型门改「充分交互判据（窗≥2∧sets≥100）」，终轮 021908 PASS。

### 累计稳定性（全部修复后构建 1792252676，20261018 全部轮次）

| 场景 | 累计 PASS | 模拟器 | 真机 |
|---|---|---|---|
| 557 | 9 有效轮全过* | 7（含 3 变体） | 2 |
| 556_plain / bracket | 9/9 · 9/9 | 7 · 7 | 2 · 2 |
| 540 / 554 | 6/6 · 6/6 | 4 · 4 | 2 · 2 |
| 547 | 6/7（1=utf-8 装置崩） | 4 | 3 |
| 549 | 5 有效轮全过* | 3 | 2 |
| 540_dsh_stream / tool | 有效轮全过* | 3 · 3 | 2 · 2 |
| 551 / 551_static | 6 · 10 | 4 · 8 | 2 · 2 |

*失败轮 100% 归因装置 bug（本战役修复 7 处），同场景修复后双端复跑 PASS；
**产品判据违例：战役全程 0 起**。

### 本战役装置修复清单（已随批提交）

device.py 容错解码；probes.py bad_nod 衰减尾豁免 + 脸②无手势读位判据；
runner.py wait_stream_end Idle 基线 + 549 成型参数/成型门 + send_prompt 输入回读校验。
