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
