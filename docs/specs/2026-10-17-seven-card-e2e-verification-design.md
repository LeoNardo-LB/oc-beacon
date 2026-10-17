# 七卡端到端验证套件设计（#540/#547/#549/#551/#554/#556/#557）

> 状态：设计 + 装置落地（2026-10-17）。被测对象：dev debug 构建（探针全开）。
> 装置位置：`e2e/harness/`（mock_v2 / probes / device / runner / prefs）。运行记录：`e2e/runs/`。
> 关联：`docs/verification.md`（V1-V6 框架）、`docs/device-testing.md`（环境 runbook）、
> `docs/research/sse-scroll-stability-iron-laws.md`（高度参照系铁律）、
> `docs/journal/2026-10-10-540-dsh-expand-degrade-rca.md`（#540 RCA）。

---

## 0. 为什么需要这套东西

七张卡当前全部处于 `[~]`（代码完成、待验收）。历史验证的共同弱点不是"没测"，而是：

1. **装置碎片化**：每卡一套临时脚本散落在 `~/.cache/540r5`、`~/.cache/547e2e`、`~/.cache/540emu`，
   依赖手工拼接坐标/日志口径，跨会话不可复跑；
2. **复现不可证明**：多数轮次只证明"修复后没再见"，没有把**当时的触发条件**写成可执行前置；
3. **判据口径漂移**：同一现象在不同轮次用不同阈值（如"Δ=0"、"≤10px"、"no slam"），
   无法横向汇总；
4. **A/B 缺口**：除 #551/#557 少数轮次，多数卡没有"旧包必红、新包必绿"的同装置对照。

本套件把七卡的**原始场景 → 确定性前置 → 逐帧探针 → 机器判据**固化成一条流水线，
目标是：同一命令、同一 mock、同一判据，在任何一次 checkout 上给出可复核的 PASS/FAIL。

非目标：替代真机自然使用观察（#554 垫通道产品侧捕获、#549 低频自然触发仍保留观察面）；
替代 V1 代码层验证（编译/单测/lint 仍按 `docs/verification.md` 执行）。

---

## 1. 七卡原始场景与复现策略

| 卡 | 原始报告形态（用户可见） | 触发条件（当时定谳） | 复现策略（本套件） | 主判据 |
|----|--------------------------|----------------------|--------------------|--------|
| **#540** | DSH 会话输出中/刚输出完展开折叠卡：内容先被上推，动画结束一次性跳回 | 流式 turn item → `LocalInStreamingTurn=true` → 降级裸 AV 零配对；`#526` 事后回锚；DSH `dsh-call-*` 宿主 `completed` 恒 null 延长降级窗 | A 腿：V2 mock 流式 turn + `expand_reasoning=OFF`，流中手动展开思考卡（确定性 1.1s 窗）。B 腿（规划）：DSH mock，输出后工具卡 | 窗内增长全 `set(...)` 配对；`nodis` 增长帧=0；无窗后一次性反向回锚；`windows` 非负 |
| **#547** | 展开的思考/工具卡在轮次重组时"自己收起来" | 宿主消息 id 换名 → 派生 partId 前缀变 → 展开态 map 脱靶（r13，79ms） | V2 mock `rename`：流中第 8 行后以新 `assistantMessageID` 重播同内容（同宿主换名形态） | 同一内容指纹 key 跨两个宿主 id；`expanded=true` 保持；`mapHit` 不 true→false；无同键无 toggle 的 true→false |
| **#549** | 随机操作下偶发：重向猛砸 / 贴底连点不累积 / 动画尾段往返 | 帽-驱动通道动画边界清算净错（三脸同源） | V2 mock 长流 + 60s 随机扰动（toggle burst/滚动/回底） | 脸① 250ms 内 ±>300 反向；脸② 窗内连续 ≥3 帧正步进 nodis；脸③ 120ms 内 ±>200 往返；存量 FLASH/FIGHT/OSC/FATAL |
| **#551** | 巨卡第二次展开，视口"飞顶"（+11.6k px ≈5 屏） | 种子 max 棘轮钉住首次展开动态高 + 离底全高未按视口截断 | V2 mock `giant`（240 行×0.12s），展开→长高→收起→二次展开 | 二次窗 `tgt` ≤ 窗内 contentH 峰值×1.25+200；窗内 fiso 极差 ≤ 1.15×视口 |
| **#554** | SSE 流式输出中收起已展开卡：卡片上下一起动，上缘不锚 | 负向步进走 driveScrollShift containment；贴底时无骑行房间→钳制；修复=吸收垫 | V2 mock 大卡流中手动展开→上滑阅读（锚移到揭示缘上方）→收起 | 收起前后卡头 `top` Δ≤10px；负向 `release-ref` 存在；`ABSORB decompose` 记录（观察） |
| **#556** | `expandReasoning=ON` 时流式思考卡只有头部+spinner，正文全程不可见 | ①mock 行首 `[` 触发 SafePrefixGate 硬停全额扣留；②Reasoning 分支未穿 `contentTerminal` → 完结后永冻 | A 腿 `plain`：自然段落流；B 腿 `bracket`：行首 `[` 流中扣留 | plain：流中 `gate release>0` 且 UI 文本可见；bracket：流中 `release=0/held>0`，完结 `terminal reveal` 总量>0 且 UI 全量可见 |
| **#557** | 流式思考卡展开后只显示首段，后续内容不涨高，完结才一次性砸出 | 窗关 settle 时注销驱动；`hProvider` 闭包捕获 `driveRegistered=true` 读冻结 h（59.4s 实证） | V2 mock 慢流（1.2s/行）+ 8 行后 6s 停顿（强制窗关）→ 继续流 | `win-close remove=false`；窗关后 contentH 增长 ≥3 帧且 `reg=true`；`release h` 随增长上移（非冻结） |

### 1.1 场景与历史证据的对应

| 卡 | 历史证据 | 本套件复现的同一性 |
|----|----------|--------------------|
| #540 | journal §9 E1-E6 / §23 腿A/B/C；`[DEBUG-526] reason=in-streaming-turn`；绿签名"窗内全 set 零 nodis" | 同判据字符串、同窗口语义；区别仅服务器由 DSH 换 V2 mock（根因①）；根因② 的 DSH 腿见 §7 缺口 |
| #547 | r13 79ms 双宿主（`t_msg_12b91c58a` 出现 + RESIZE -4159）；`ExpandStateKeysTest` r13 原始 id 用例 | 以 `rename` 场景强制"同内容换宿主 id"；断言与 9 轮 E2E 同红线（R1/R1'/R2） |
| #549 | r400 +1018→−1343 40ms；r427 步进 83/56/38 蒸发；emu-r11 ±627px 0.1s | 同一 `release` 序列 + `ip=true` 手势窗排除；阈值取历史定罪轮的最小值 |
| #551 | p7_900：`tgt=13807` vs `itemH=4672`；修后 `tgt=5546`/位移 +283px | 同 `pairing-window open tgt` 字段；把"棘轮"判据表达为 tgt vs contentH 峰值比 |
| #554 | nine1_full/burst（窗内负向钳制）；C/F/G 三轮 fiso 恒=span（全骑行） | 同负向 `release-ref` 口径；顶缘量测改为 uiautomator 卡头 bounds（Δ≤10） |
| #556 | ph16 `gate release=0 held=57 releasedTotal=0`；修后 plain `release=99`×9；bracket `terminal reveal +513ch` | 同 `MDPilot gate/terminal` 日志；mock 两种行模板原样保留 |
| #557 | D 轮探针 `win-close remove h=116 tgt=116 contentH=116` 后 contentH 116→2900 而 measure 冻 253px 59.4s | 同 `[DEBUG-557]` 探针；6s 停顿复刻 1.2s/行慢节奏下的 settle 窗 |

---

## 2. 装置架构

```
┌────────────────────────── runner.py ──────────────────────────┐
│ 场景编排：清 logcat → 起 mock → 建设置 → 冷启 → 开会话 → 发 prompt │
│          → UI 动作（tap/scroll）→ 收 logcat → oracle → report    │
└───────┬───────────────────────┬───────────────────────┬────────┘
        │                       │                       │
   mock_v2.py              device.py                probes.py
   （V2 SSE 场景流）       （adb/uiautomator）       （逐帧探针判据）
   plain/bracket/giant     dump/tap/swipe/          card540/547/549/
   rename/hold             prefs(DataStore pb)      551/554/556/557
```

### 2.1 mock_v2 场景参数

| 参数 | 含义 | 典型值 |
|------|------|--------|
| `MOCK_SCENARIO` | `plain`/`bracket`/`giant`/`rename`/`hold` | 见 §3 矩阵 |
| `MOCK_LINES` / `MOCK_INTERVAL` | 思考行数 / 行间隔 | 10×0.6 / 24×1.2 / 240×0.12 |
| `MOCK_PAUSE_EVERY` / `MOCK_PAUSE_SECS` | 每 N 行停顿 M 秒（强制 settle 窗） | 8 / 6 |
| `MOCK_TEXT_CHUNKS` / `MOCK_TEXT_INTERVAL` | 答案块数 / 间隔 | 1–4 / 0.2–0.4 |
| `MOCK_RENAME_AT` | `rename` 场景换宿主行号 | 8 |
| `MOCK_HOLD_OPEN` | 思考/答案后不结束 turn（#540 输出后窗） | 0（A 腿）/1（规划） |

### 2.2 设置注入（不走 UI）

`expand_reasoning` 等开关直接读写 DataStore protobuf（`prefs.py`，wire-level 解析，
保留未知键），流程：force-stop → pull → 改 bool → push → run-as cp。
好处：无 IME/导航抖动，跨轮确定性；代价：仅对 debug 包（`run-as`）有效。

### 2.3 探针口径（全部来自生产埋点，不改产品代码）

| 探针 | 来源 | 用途 |
|------|------|------|
| `[DEBUG-526] degrade toggle reason=` | CardExpandReveal | 证明走降级路径 |
| `[DEBUG-540] pairing-window open/close` | CardExpandReveal | 窗边界、tgt/span/itemH/boxY/stickTop |
| `[DEBUG-557] contentH a->b / win-close` | CardExpandReveal | 内容高链、注销裁决、驱动注册态 |
| `SGR-435 release/release-ref set(fii,fiso)` | ScrollCompensation | 配对位移、nodis/noop、视口轨迹 |
| `SGR-435 flush ip=` | ScrollCompensation | 用户手势窗（排除） |
| `ABSORB decompose` | ScrollCompensation | 骑行/吸收分解（#554） |
| `MDPilot gate/terminal reveal` | StreamingMarkdownPilot | #556 放行/扣留/终态揭示 |
| `RB-EXP RB id/key/expanded/mapHit` | PartContent / MessageDataDelegate | #547 展开态键与宿主 |

---

## 3. 场景矩阵（可执行用例）

> 每条用例的 `pass` 由 `probes.py` 的对应 `cardXXX` 函数给出；runner 只负责前置与动作。
> 所有阈值均为**绝对像素/毫秒**，避免不同设备密度换算歧义（模拟器 1080×2400）。

### T-540-A 流式 turn 展开折叠卡（V2 mock）

- **前置**：`expand_reasoning=false`；mock plain 40×0.9s + 每 15 行 4s 停顿。
- **动作**：发送 → 3s 后点折叠思考卡头 → 观察 2.5s。
- **PASS**：
  1. 出现 `degrade toggle visible=true reason=in-streaming-turn`；
  2. 至少 1 个 `pairing-window open` 且 close 配对，`windows` 全程非负；
  3. 窗内 `drv/capd>0` 的增长帧**无** `nodis`（初始 h≤itemH 的零帧除外）；
  4. （2026-10-17 冒烟修正）**不按窗后 set 方向判回锚**：窗净位移为负后继续的正向
     set 是流式稳态增长（本轮 39/39 set 后仍增长）。旧 #526 回锚在当前实现已无独立
     日志签名；若窗内增长未配对，第 3 条 `bad_nod` 会先命中。
- **场景成型门槛**：`reveal-geo stickTop>=0` 或 `itemH>0`（否则是空卡，判 INVALID 而非 PASS）。

### T-547-A 宿主换名后展开态存活（V2 mock rename）

- **前置**：`expand_reasoning=true`；mock rename 24×0.7s，第 8 行后换 `assistantMessageID` 并重播内容。
- **动作**：发送 → 8s 后采样 → 再 6s → 判定。
- **PASS**：
  1. 同 `key` 出现在 ≥2 个不同 `id`（HOST-SWAP-SURVIVED）；
  2. 任一 key 的 `expanded` 不得在无 toggle（±2s）时 true→false（SILENT-COLLAPSE=0）；
  3. `mapHit` 不得 true→false；
  4. UI 思考正文可见（防止"键对了但卡没渲染"假绿）。

### T-549-A 随机扰动三脸扫描（V2 mock long stream）

- **前置**：`expand_reasoning=true`；mock plain 220×0.25s。
- **动作**：60s 内循环：卡头三连点 → 上滑 → 单点 → 下滑 → 回底双滑；每动作间隔 0.4s。
- **PASS**：
  - 脸①：同 fii 250ms 内 d1>+300 且 d2<−300（无 toggle/手势窗）=0；
  - 脸②：窗内连续 ≥3 帧 `drv>0 && nodis` =0；
  - 脸③：同 fii 120ms 内 |Δ|>200 的双向往返（无手势）=0；
  - FLASH（窗后 1.5s 内 MDResize>150 紧接 <−50）=0；`windows` 非负；无 FATAL/`release-dis`。

### T-551-A 巨卡二次展开（V2 mock giant）

- **前置**：`expand_reasoning=false`；mock giant 240×0.12s。
- **动作**：发送 → 2.5s 点卡头第一次展开 → 9s 后滚回卡头 → 收起 → 1.5s 后第二次展开 → 2s 采样。
- **PASS**：
  1. 展开窗 ≥2；
  2. 末窗 `tgt ≤ max(contentH)×1.25+200`（无 max 棘轮虚高）；
  3. 末窗 `set` 目标 fiso 极差 ≤ 1.15×视口（2400 → 2760）。
- **场景成型门槛**：第一次展开后 contentH ≥ 4000（否则"巨卡"不成立，判 INVALID）。

### T-554-A 阅读位上移后收起（V2 mock 大卡）

- **前置**：`expand_reasoning=false`；mock plain 60×0.8s。
- **动作**：发送 → 2.5s 展开 → 6s 长高 → 小步上滑直到「用户气泡在卡头上方且两者同屏」→ 记录卡头 top → 收起 → 2s 后记录 top。
- **PASS**：
  1. 卡头 top Δ≤10px（V3 顶缘不变式）；
  2. 存在负向 `release-ref`（收起真的走了配对通道）；
  3. `ABSORB decompose` 出现=加分观察项（不单独构成 FAIL）。
- **场景成型门槛**：定位成功（用户气泡与卡头同屏且 y 有序），否则 INVALID。

### T-556-A 流式思考文本可见（plain）

- **前置**：`expand_reasoning=true`；mock plain 10×0.6s。
- **动作**：发送 → 8s 采样 → 等完结 → 终态采样。
- **PASS**：流中 `gate release>0` 且 UI 出现思考正文（"thought"）；终态正文仍可见。

### T-556-B 扣留行的终态揭示（bracket）

- **前置**：`expand_reasoning=true`；mock bracket 10×0.6s（行首 `[`）。
- **动作**：发送 → 5s 采样（应扣留）→ 等完结 → 终态采样。
- **PASS**：流中 `release=0` 且 `held>0`；完结出现 `terminal reveal` 且总量>0；UI 终态正文可见。

### T-557-A 挂载窗关闭后的内容增长（慢流 + 停顿）

- **前置**：`expand_reasoning=true`；mock plain 24×1.2s + 每 8 行 6s 停顿。
- **动作**：发送 → 22s 采样 → 再 20s → 判定。
- **PASS**：
  1. `win-close vis=true remove=false`（展开态留册）；
  2. 窗关后 contentH 增长 ≥3 帧且全部 `reg=true`；
  3. 窗关后 `release h` 极差 ≥50（reveal 高度随内容增长，非冻结）；
  4. UI 思考正文可见。

---

## 4. A/B 策略（红→绿）

历史修复证据多为"修后未见"，本套件预留 **同装置 A/B**：

1. **基线选择**：每卡在 backlog/journal 里记录修复 commit（#540 `fc0710453`+后续、
   #547 `67f1cb867`、#549 `8f87083d9`、#551 `c2b590f8f`、#554 `c8b181963`、
   #556 `97f320953`、#557 `954a3097b`）；
2. **旧包构建**：`git worktree add /tmp/ocb-prefix <fix^>` → `./gradlew :app:assembleDevDebug`
   → `runner.py run --card X --apk <old.apk>`；
3. **期望**：旧包在对应场景 **FAIL**（且失败签名与历史一致），新包 **PASS**；
4. **成本控制**：A/B 只对"判据可稳定触发"的卡执行（#556/#557/#540 优先）；
   #547/#549/#551/#554 的低频面以"新包 PASS + 历史证据链"为主，A/B 作为可选加固。

> 说明：七卡修复相互叠加（#540 的引擎是 #554/#557 的底座），旧包回退单卡时
> 可能连带别卡判据失败；A/B 只判**本卡主判据**，其余卡判据标注"环境不适用"。

---

## 5. 执行协议

1. **设备**：模拟器 `emulator-5554` 为主（1080×2400，几何常量固定）；真机 `e69a99d8` 为复核腿。
2. **串行纪律**：同一 checkout 禁并发 Gradle；同一设备禁并发场景（mock 端口/设置互斥）。
3. **每轮清理**：force-stop → 清 logcat → 起独立 mock 进程 → 唯一会话标题 → 结束 kill mock。
4. **证据落盘**：`e2e/runs/<ts>/<card>/{logcat.txt,dump-*.xml,result.json,mock.log}`；
   汇总 `report.md/json`。
5. **失败处置**：先区分 INVALID（场景未成型：无卡/无窗/定位失败）与 FAIL（判据违例）；
   INVALID 必须修装置而不是改判据；FAIL 回填卡面并附日志行号。
6. **关闭门槛**：每卡主判据 PASS + 至少一个独立维度（UI dump/截图/另一条日志链）交叉；
   与 `docs/verification.md` §2 的"≥2 独立维度"一致。

---

## 6. 已知限制与不覆盖项

| 项 | 限制 | 处置 |
|----|------|------|
| #540 根因②（DSH 工具宿主 `completed` 恒 null） | V2 mock 无法产生 DSH 帧；A 腿只覆盖根因① | 规划 `mock_dsh.py`（V011 dot + 双 WS，`tool/call` 不预完结）；当前以 A 腿 + 单测 `assertNull(completed)` 交叉 |
| #547 宿主换名 | `rename` 用第二 `step.started` 强制换 id，与 r13 的"多消息轮次重组"不完全同形 | 断言只依赖"同内容跨宿主 id 同 key"这一不变量；r13 原始双宿主 id 由单测钉死 |
| #551 内容收缩 | mock 流内容单调增长，未复刻"首次展开暂态高→收敛变矮" | 判据改为 tgt vs 窗内 contentH 峰值比 + 让位 ≤ 一屏；复刻收缩需 markdown 分期/表格重排，留作增强 |
| #554 ABSORB 产品侧捕获 | 贴底几何下骑行房间结构性覆盖塌缩量（C/F/G 三轮定谳） | 主判据=顶缘 Δ≤10；ABSORB 仅观察；上滑场景提高钳制概率但不保证 |
| #549 低频自然触发 | mock 时序比真实模型规整 | 60s 随机扰动 + 历史阈值；低频面仍靠 soak 观察 |
| mock 与真实服务器事件差异 | mock 行模板/时序自造 | 关键判据只用生产探针（不依赖 mock 语义）；#556 已用真实服务器九报前提反证 |

---

## 7. 交付物

1. **本设计文档**（场景矩阵/判据/AB/限制）。
2. **可执行装置** `e2e/harness/`：
   - `mock_v2.py`：V2 确定性流（5 场景）；
   - `device.py`：adb/uiautomator/DataStore 设置；
   - `probes.py`：七卡逐帧判据；
   - `runner.py`：`list` / `run --card` / `run-all`，产出 JSON/Markdown 报告；
   - `prefs.py`：DataStore protobuf 读写。
3. **运行证据** `e2e/runs/<ts>/`（每卡 logcat + dump + result.json）。
4. **卡面回填**：每卡 PASS/FAIL 与关键证据行写回 backlog 注记（经 `./scripts/backlog.sh note`）。

---

## 8. 后续增强（按优先级）

1. `mock_dsh.py`：补齐 #540 根因②（V011 双 WS + `tool/call` 未完结 + 输出后展开）。
2. #551 收缩形态：在 mock 中引入 staged markdown/表格重排，复刻 `tgt` 棘轮原始触发。
3. #554 ABSORB 强制场景：多部件几何（答案部件在卡下）+ 视口变化交织。
4. 真机腿：`--device e69a99d8` 跑同一矩阵，对比模拟器判据。
5. CI 化：模拟器 headless + 固定 mock，夜间跑七卡矩阵。

---

## 9. 执行记录（2026-10-17，模拟器 emulator-5554）

**被测构建**：dev debug `app-dev-debug.apk`（2026-10-17 21:15，versionCode 1792242886），
探针全开；`expand_reasoning` 由 DataStore 注入；服务器为 `mock_v2.py` / `mock_dsh.py`。

| 用例 | 结果 | 关键证据（run 目录） |
|------|------|----------------------|
| T-557-A 慢流窗关后增长 | **PASS** | `runs/20261017-222707/557`：`win-close vis=true remove=false h=116`；窗关后 contentH 增长 23 帧、`release h` 253→3106；UI 正文可见 |
| T-556-A plain 流中可见 | **PASS** | `runs/20261017-222707/556_plain`：`gate release=991`；UI 含 "thought" |
| T-556-B bracket 终态揭示 | **PASS** | `runs/20261017-222707/556_bracket`：流中 held=571/release=0；`terminal reveal` ×3 共 571；终态 UI 可见 |
| T-540-A V2 流中展开 | **PASS** | `runs/20261017-222707/540`：degrade toggle ×1；窗内 growth=39/set=39/nodis=0 |
| T-547-A 宿主换名 | **PASS** | `runs/20261017-222707/547`：`HOST-SWAP-SURVIVED key=xr:39ba7837 ids=2`；无静默塌缩 |
| T-554-A 阅读位收起 | **PASS** | `runs/20261017-223916/554`：卡头 top 677→677（Δ=0）；neg release 23 行；`ABSORB decompose` 20 行 |
| T-549-A 随机扰动 | **PASS** | `runs/20261017-231233/549`：degrade toggle 3 次、windows=4、sets=184；slam/evap/tail=0 |
| T-540-DSH-A 流中展开 | **PASS** | `runs/20261017-231422/540_dsh_stream`：DSH V011 帧；窗内 growth=35/set=35/nodis=0 |
| T-540-DSH-B 输出后工具卡 | **PASS** | `runs/20261017-231520/540_dsh_tool`：`episode-start`+`episode done`+RB toggle=true；degrade=0、pairing-windows=0（机件路径） |
| 汇总复跑（#558 修复后） | **11/11 PASS** | `runs/20261018-000249/report.md`：557/556×2/540/547/554/549/540_dsh×2/551/551_static 全 PASS |
| T-551-A 巨卡二次展开 | **PASS（#558 修复后）** | `runs/20261018-000249/551`：3 窗，二次窗 tgt=3,828=prev_settle、fiso 位移 418px（≤2400）；`551_static` 同轮 PASS（tgt=4,640、位移 0） |

### 9.1 #551 装置阻塞（新发现，需单独立项）

巨卡场景出现**展开态思考内容重复渲染**：mock 30 行 / 0.3s 流完结后，UI 出现多份
`thought 1` 段落叠加，`[DEBUG-557] contentH` 涨到 **38,164 px**（单行 UI 实测 116px，
30 行应约 3.5k）；而 Room `cached_parts.text` 仅 2,891 字符（模型层未重复）。
即：**part 文本正确，渲染层把全文重复 append**，导致：

1. 卡头被推到数万 px 之外，回滚交互窗不可用（25 划 + y 扫描仍无法命中）；
2. 触发 #552 OOM 风险区（历史 275k px 崩溃同族）；
3. uiautomator 在大卡组合期等 idle 卡死（`UiAutomationService already registered`），
   dump 路线不可用。

**装置侧缓解**：mock 在 `reasoning.ended` 前留 1s 排空窗（已内置）；仍复现。
进一步隔离（`MOCK_NO_END`，不发 ended）显示：冻结后**无新 delta 仍持续增长**
（21,160→21,228/帧）；再做「全部 delta 到达 + 冻结 + 仅首次展开」(`551_static`)：
首次展开 contentH=2,832，随后仍涨到 28,120；改用唯一随机行（`MOCK_UNIQUE_LINES`）
仍从 9,560 涨到 12,702。说明是**展开组合 + 高度反馈自身维持的渲染侧持续 append
循环**，与流式更新/完结/内容重复度均无关；#551 四类变体（慢流/NO_END/静态首展/
唯一行）全部无法绕过，#551 巨卡 E2E 在 #558 修复前无法 staging。慢流变体（40×1.0s + 每 10 行 3s 停顿）不复现重复渲染，
`551` 可跑通种子不变量（tgt=1044=上一结算 contentH、fiso 位移 0），但内容高仅
~1.2k，未构成「巨卡离底让位」原场景；**因此 #551 只算部分覆盖，不冒充 PASS**。
**oracle 已就绪**（`probes.card551`，本轮修正为「tgt 对照上一结算 contentH」而非
窗内峰值，避免合法种子假阳性），待 #558 修复后复跑快流巨卡。

> 该现象与 #552（展开态流式 OOM）同族，已登记为 **#558**（P2，`ui`/`memory`/`sse`）。

**#558 已根修（2026-10-17 深夜，`StreamingMarkdownPilot.kt`）**：`StreamingMarkdownPilot` 首跑铺开循环只在**循环结束**
写 `released/prev`；`LaunchedEffect` 在 `withFrameNanos`/限速 `delay` 处可被
`holding`/`terminal` 变化取消重启，重启时 `released=0 / prev=null` → 整串再次
append，内容按重启次数叠加。修法：每批 `append` 后同步推进 `released` 与差分基准
`prev`，重启从已放行前缀续账（追加单调、幂等）。验证：`551_static` 首展
contentH=2,832（旧 28,120），二次窗 tgt=3,828=prev_settle、fiso 位移 418px；
`551` 巨卡 3 窗 PASS（tgt=3,828、位移 418）。全矩阵 11/11 PASS（`runs/20261018-000249/report.md`），
557/556/540/547/554/549/DSH 均无回归；全量单测 `testDevDebugUnitTest --rerun` BUILD SUCCESSFUL。
> 历史 #551 A/B（p7_900：tgt=13807→修后 5546、位移 +11,658→+283）仍是本卡的主证据链，
> 本套件提供的是可复跑的回归门。

### 9.2 装置修正记录（本轮踩坑与修复）

1. **mock 孤儿进程占端口**：`runner` 被 kill 时 finally 未执行 → 下一轮连到旧 mock
   （错误场景/暂停失效）。修复：`Mock.start()` 先清 `harness/mock_*.py`，端口占用即失败。
2. **SSE 多订阅者重复投递**：app 重连残留 socket → delta 双份；修复：mock 单客户端语义
   （新连接清空订阅表）。
3. **admission 与回显用户消息双 id**：app 本地播种 + SSE 回显各一条 → turn item 重复；
   修复：`session.prompt` 返回的 admission id 与 `inbox.enqueued.inboxID` 同源。
4. **`reasoning.ended` 紧贴末 delta**：渲染未排空时全文重复 append；修复：ended 前
   1s 排空窗（缓解项，根因见 §9.1）。
5. **uiautomator 卡死**：dump 超时后 `killall uiautomator` 重试；仍失败则重启模拟器。
   巨卡场景改用日志计数（`degrade toggle`）做命中反馈，不用 dump。
6. **reverseLayout 滚动方向**：手指下滑=看更早内容；早期装置方向写反导致定位全错。

### 9.3 结论

- 七卡中 **六卡（#540/#547/#549/#554/#556/#557）** 在本装置上给出可复跑的 PASS 与
  逐帧证据；#540 额外覆盖 DSH 协议腿（流中 + 输出后工具卡）。
- **#551** 的 oracle/场景已落地：快流巨卡被 #558 阻塞（未伪造 PASS），慢流变体
  只覆盖种子不变量、未构成原「巨卡离底让位」场景，故整体记 PARTIAL；已记录阻塞
  条件与复跑入口。
- #549 首轮曾因随机动作全落空（toggles=0）产生空洞 PASS，已改为“短流多轮 + 命中
  反馈计数 + 最少 2 次 degrade toggle”门槛后复跑通过（3 次 toggle/4 窗）。
