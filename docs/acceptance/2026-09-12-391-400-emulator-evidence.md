# #391 / #398 / #399 / #400 模拟器端到端验收证据（emulator-5554）

- 卡片：backlog #391 / #398 / #399 / #400（对 `docs/acceptance/2026-09-12-391-398-399-400-real-device-dsh.md` 的模拟器复跑）
- 日期：2026-09-11（会话时间；checklist 标注 2026-09-12）
- 设备：`emulator-5554`（无头 Pixel6_Android36，Android 16 / SDK 36，1080x2400，宿主无 DISPLAY）
- 构建：devDebug（APK `app/build/outputs/apk/dev/debug/app-dev-debug.apk`）
  - APK md5 = `c90ba98a06878a31da73a62f101c0d98`（与任务给定值一致；未运行 Gradle 构建/安装）
  - 已装包 versionName 0.3.0 / versionCode 1789105081；包名 `dev.leonardo.ocbeacon.dev`
- 服务（均经 adb reverse 映射到设备侧 127.0.0.1）：DSH 3080、令牌门禁 authority 3081→3080、OpenCode V2 4199
- 观测通道：uiautomator dump（文本/content-desc + bounds）+ Room DB 直查 + logcat；截图仅作附加证据，不作唯一判据
- 说明：**未修改原 checklist 文件，也未修改产品代码**。本轮为「模拟器替代真机」复跑。

## 环境处理（可追溯）

1. **语言**：模拟器初始 locale = `en-US`，UI 为英文，与 checklist 的中文字面判定不匹配。经 `adb shell cmd locale set-app-locales dev.leonardo.ocbeacon.dev --user 0 --locales zh-CN` 设置**仅本 App** 为 zh-CN（不改系统语言），使 dump 出现 checklist 指定的中文文案；设置后 `cmd locale get-app-locales` = `[zh-CN]`。
2. **adb reverse**（含 A1 专用 3081）：
   ```
   host-21 tcp:3080 tcp:3080
   host-21 tcp:3081 tcp:3080
   host-21 tcp:4199 tcp:4199
   ```
3. `adb root` 用于 Room DB 直查；`run-as dev.leonardo.ocbeacon.dev sqlite3 databases/ocbeacon.db` 正常。
4. **D1/D2 新会话路由坑**：新会话默认路由 `deepseek-official / DeepSeek-V41-Flash` 首次发送返回 `Insufficient Balance`（会话 session-db2b1eb0…）；用模型选择器切到 **opencode-go / DeepSeek V4.1 Flash（多模态）** 后轮次正常执行（证据 `/tmp/acc-emu/d-model.xml`、`d-model2.xml`）。

---

## A. 连接 / 鉴权（模拟器复跑）

### A1 ✔ PASS
- 操作：force-stop → `am start … --es debug_url http://127.0.0.1:3081 --es debug_name E2E-DSH --es debug_server_type dsh`（不带 token），等待 16s。
- 观测：dump 同时含 `text="此服务器需要访问令牌"` 与 `text="输入令牌"`（errorContainer 细条幅出现）。
- 证据：`/tmp/acc-emu/a1.xml`、`/sdcard/a1.xml`、`/tmp/acc-emu/acc-A1.png`

### A2 ✔ PASS
- 操作：点「输入令牌」→ dump 确认标题「需要访问令牌」→ 聚焦 EditText → `input text` 注入 `invalid-token-xyz`（含 KEYCODE_MINUS 尝试，模拟器 IME 将连字符显示为全角 `－`，字段实际为 `invalid－token－xyz`，仍是无效 token）→ 按键盘弹出后的**动态 bounds** 点「连接」，等待 20s。
- 观测：对话框未关闭，出现 `text="服务器拒绝了令牌。请检查后重试。"`（字符串源 `values-zh-rCN/strings.xml:870 dsh_token_dialog_rejected`）。
- 证据：`/tmp/acc-emu/a2-step1.xml`、`a2-step2.xml`、`a2-step3.xml`、`/tmp/acc-emu/acc-A2.png`
- 附注：首次尝试因未按键盘弹出后重新定位「连接」坐标而误触空白关闭对话框，已定位并复跑（见 a2-step2 → a2-step3）。

### A3 ✔ PASS
- 操作：MOVE_END + 25×DEL 清空 → 注入 43 字符有效 token（与 `~/.dsh/token` 一致）→ 动态定位「连接」并点击 → 等待 25s。
- 观测：`此服务器需要访问令牌` 计数 = 0；列表含 `text="多服务器适配重构"`（含 workspace 路径 /home/leo-tkp/Documents/code/mine/oc-beacon）。
- 证据：`/tmp/acc-emu/a3-step1.xml`、`a3-step2.xml`、`a3-step3.xml`、`/tmp/acc-emu/acc-A3.png`

---

## B. 能力门控与界面插槽（模拟器复跑）

### B1 ✔ PASS
- 操作：进入「多服务器适配重构」→ tap `content-desc="打开任务菜单"`（预算 [986,1968][1049,2031]）→ dump。
- 观测：菜单含 `TODO` / `智能体` / `目标` / `排队队列`（均位于菜单列 x≈866-1043）；不含「终端」/「Shell」。
- 证据：`/tmp/acc-emu/b1-step1.xml`、`b1-step2.xml`、`/tmp/acc-emu/acc-B1.png`

### B2 ✔ PASS
- 操作：force-stop → `debug_url http://127.0.0.1:4199 --es debug_username opencode --es debug_password <service.json password> --es debug_name E2E-OC --es debug_server_type opencode` → 列表 dump → 设置页首屏 + 上滑 4 屏逐屏 dump。
- 观测：列表 dump `此服务器需要访问令牌` = 0；设置页 5 次 dump 均不含 `DSH provider 目录` / `服务器插件`（OpenCode 面设置仅 `MCP 服务器` / `标签管理 (0)`）。
- 证据：`/tmp/acc-emu/b2-list.xml`、`b2-settings.xml`、`b2-settings-1..4.xml`、`acc-B2-list.png`、`acc-B2-settings.png`、`acc-B2-settings-bottom.png`

### B3 ✔ PASS
- 操作：OpenCode 面进入会话「DSHWeb Token问题咨询」→ tap `打开任务菜单` → dump。
- 观测：菜单含 `TODO` / `智能体` / `排队队列`，**不含 `目标`**（OpenCode V2 无 GOALS 端口）；菜单另有 `Shell`（checklist 备注该项在 zh 文案下未确认、不做断言）。
- 证据：`/tmp/acc-emu/b3-list.xml`、`b3-step1.xml`、`b3-menu.xml`、`/tmp/acc-emu/acc-B3.png`

---

## C. V3 事件族渲染（#398）

### C1 ⚠️ 指定会话路线 FAIL（压缩边界），替代会话 PASS
- 指定路线（任务要求，无需模型轮次）：打开 DSH 面「多服务器适配重构」（session-94365bc9-728e-4684-9128-02db5b589745）→ 等待转录 → **从底部逐屏向上扫描共约 117 次 dump**（c1-scroll-1..12、c1u-1..45、c2s-1..60）。
  - 结果：**未出现** `text="产物"` 精确节点，也**未出现** `content-desc="打开 <path>"` 节点；也从未进入含该两条 deliverable 的转录区（转录取到的时间区间 ≈ 09-11 10:11 → 21:41，唯独缺 21:03）。
  - 原因观测（非修复）：渲染计划日志 `D/Transcript378: plan cmds=1 compactions=4 extras=1 trailing=0 displaySeqs=[null,11934,null,11920,null,11648]`，DSH V3 历史被 4 次压缩分割；该会话 2 条 deliverables 均落在压缩边界之前。
  - DB：`select count(*) from cached_parts where type='deliverables' and sessionId='session-94365bc9…'` = **2**（时间 1789092670792 / 1789131822635，即 09-11 10:11:10、21:03:42，均早于 21:23:47 的 compact）。
  - 归档：`zstd -dc ~/.dsh/sessions/--home-leo-tkp-Documents-code-mine-oc-beacon--/session-94365bc9-728e-4684-9128-02db5b589745/session.v3.jsonl.zstd | grep -c 'deliverables/presented'` = **377**（任务描述 367，与活动会话持续追加一致）。
- 替代路线（同样无需模型轮次）：打开另一 DSH 会话（session-a84edbf7-5247-4751-a52a-d70b1c028438，title 关键字「请检索一下是否有GIS、GEO的MCP」，workspace /home/leo-tkp/Documents/travel/2026秋-广西9日游）→ 从底部上滑 2 次 → dump 命中：
  - `text="产物"`，`content-desc="打开 广西沿海8日自驾攻略-湛江进南宁出.md"`、`content-desc="打开 images/攻略-路线总图.png"`、`content-desc="打开 images/攻略-南宁美食片区图.png"`
- 证据：指定路线 `/tmp/acc-emu/c1-transcript.xml`、`c1-scroll-1..12.xml`、`c1u-1..45.xml`、`c2s-1..60.xml`；替代路线 `/tmp/acc-emu/alt-2.xml`、`/tmp/acc-emu/acc-C1-alt.png`
- **判定**：功能（产物 chip 渲染）PASS；但任务指定的 session-94365bc9 路线未能产出 chip（压缩边界导致），如需以该会话为唯一判据则应记 FAIL。

### C2 ❌ FAIL（字面判定未满足）/ 注入卡机制 PASS
- 操作：94365bc9 会话滚到顶/中段 dump，并定位 `上下文注入` / `插件配置` 卡（clickable 祖先）点击展开。
- 观测：
  - 注入类精简卡**确实渲染**：dump 出现 `text="上下文注入"`（c2f-2、c2s-7/8/9/18/35、c1u-6、c1-scroll-7）、`text="插件配置"`（c2s-18、d5-5）；点击展开后卡内正文出现（c2f-2 → c2exp-1 展开体为子代理上下文消息；d5-5 → d5-exp）。
  - **但 checklist 指定片段 `You are an AI agent powered by DeepSeek Harness` 未作为注入卡正文出现在任何 dump**。对全部 `/tmp/acc-emu/*.xml` 做精确节点扫描：该字符串仅作为**父会话助手消息正文**出现（c1-scroll-3.xml、c2s-11.xml，节点文本为 "…C1/C2 素材确证…含 “You are an AI agent powered by Dee…"），非注入卡正文。
  - 归档确有 `system/message`：`zstd -dc … | grep -c 'system/message'` = **339**，首条 seq=9 的 text 即以该片段开头；但按 V3 压缩语义，它们在 94365bc9 的已加载转录之外。
- 证据：`/tmp/acc-emu/c2f-2.xml`、`c2exp-1.xml`、`d5-5.xml`、`d5-exp.xml`、`c2s-11.xml`、`c2s-7/8/9/18/35.xml`、`/tmp/acc-emu/d2-poll-1.xml`（该 dump 可见 system prompt 片段 "A user may also invoke a skill directly…"、"`pdf`: Use this skill…"，但非指定开头片段）。
- **判定**：注入卡机制 PASS；字面判定 FAIL（指定片段未在转录取到）。

### C3 ✔ PASS
- 操作：在 94365bc9 会话加载后 dump 转录、拉 DB、统计 logcat。
- 观测：
  - 转录非空（多屏文本节点）；`select count(*) from cached_messages where sessionId like '%94365bc9%'` = **1000**（>0）；`cached_parts` = **1022**。
  - `adb -s emulator-5554 logcat -d | grep -c 'STRUCTURAL_VIOLATION'` = **0**。
- 证据：dump 见 C1/C2 同批快照；DB/logcat 输出见本节。

---

## D. SSE 实时流式（#400）

### D1 ✔ PASS
- 操作：新建 DSH 会话（oc-beacon workspace）→ 模型选择器切到 **opencode-go / DeepSeek V4.1 Flash（多模态）** → `adb logcat -c` → 发送长输出提示 → 轮询 dump + logcat（第二转每 3s 一采）。
- 观测（新会话 session-db2b1eb0-4800-4be0-99e4-252caf089d88）：
  - logcat 含本次会话的 `SessionStateService: [session-db2b1eb0…] Busy/Streaming --TextDelta--> Busy/Streaming`：第一转 **443** 条，第二转 **3471** 条（`-c` 后必为本次）。示例行：
    `09-11 21:57:04.314 20466 20490 D SessionStateService: [session-db2b1eb0-4800-4be0-99e4-252caf089d88] Busy/Streaming --TextDelta--> Busy/Streaming`
  - 间隔 >5s 的两次 dump 中 assistant 转录文本长度递增：d3-3(≈13s)=164 → d3-4(≈24s)=5200 → d3-5(≈29s)=5672（字符数，按 y∈(200,1250) 文本节点求和）。
  - 轮次完成后转录含完整 assistant 文本（d2-poll-1 中文约 145 词；d3-40 英文 897 词）。
- 证据：`/tmp/acc-emu/d-model.xml`、`d-model2.xml`、`d-typed3.xml`、`d2-poll-1.xml`、`d3-1..5.xml`、`d3-40.xml`、`/tmp/acc-emu/acc-D1.png`，以及上述 logcat 行。

### D2 ✔ PASS（轮尾台账行含时长；literal minute-second 正则见附证）
- 观测（D1 同轮/次轮）：dump 出现轮尾台账行 `轮次 2 · 13.9s · 7 步 · 3 个工具`，并有独立时长 `13.9s`（allStepsCompleted 路径）。
- 附证（`[0-9]+m [0-9]+s` 字面正则命中）：
  - `b1-step2.xml` 含 `text="3m 2s"`；
  - `c1-transcript.xml` 含 `text="5m 20s"`；
  - `alt-2.xml` 含 `text="轮次 5 · 1m 18s · 7 步 · 3 个工具"`。
- 说明：本轮 D1 新会话的轮次仅 13.9s（<1min），故其台账时长显示为秒而非「Xm Ys」；台账行本身存在且含时长。
- 证据：`/tmp/acc-emu/d3-40.xml`、`b1-step2.xml`、`c1-transcript.xml`、`alt-2.xml`

---

## E. 回归冒烟

### E1 ✔ PASS
- 操作：OpenCode 面（4199）连接后列表 dump → 进入会话「DSHWeb Token问题咨询」→ 等待转录 → dump。
- 观测：列表非空且无令牌横幅；转录 dump 含 **25** 个文本节点（消息正文、加粗标题、项目符号等），正常渲染；无崩溃。
- 证据：`/tmp/acc-emu/e1-list.xml`、`e1-transcript.xml`、`b3-step1.xml`、`/tmp/acc-emu/acc-E1.png`

### E2 ✔ PASS
- 操作：全程 `adb -s emulator-5554 logcat -d -b crash`。
- 观测：crash buffer **0 行**，`grep -ic ocbeacon` = **0**（A/B/C/D/E 执行期间均为空）。

---

## 判定汇总

| item | 判定 | 一句话 |
|---|---|---|
| A1 | ✔ PASS | 3081 新 authority 冷启动 ≤20s 出现令牌横幅「此服务器需要访问令牌」+「输入令牌」 |
| A2 | ✔ PASS | 无效 token 提交后对话框不关闭，显示「服务器拒绝了令牌。请检查后重试。」 |
| A3 | ✔ PASS | 有效 token 后横幅消失，列表出现「多服务器适配重构」 |
| B1 | ✔ PASS | DSH FAB 菜单 = TODO/智能体/目标/排队队列，无终端/Shell |
| B2 | ✔ PASS | OpenCode 面无令牌横幅，设置页无「DSH provider 目录」/「服务器插件」 |
| B3 | ✔ PASS | OpenCode FAB 菜单含 TODO/智能体/排队队列，不含「目标」 |
| C1 | ⚠️ 指定路线 FAIL / 替代路线 PASS | 94365bc9 的 deliverables 落在压缩边界前，未渲染产物 chip；替代会话 a84edbf7 命中 `产物` + `打开 <path>` |
| C2 | ❌ FAIL（字面）/ 机制 PASS | 注入类精简卡可渲染可展开，但指定片段「You are an AI agent powered by DeepSeek Harness」未在任何 dump 中作为卡正文出现 |
| C3 | ✔ PASS | cached_messages=1000（>0），STRUCTURAL_VIOLATION=0 |
| D1 | ✔ PASS | 新会话 logcat TextDelta 443/3471 条；转录文本 164→5672 字符递增 |
| D2 | ✔ PASS | 轮尾台账行 `轮次 2 · 13.9s · 7 步 · 3 个工具`；另有 `3m 2s`/`5m 20s`/`1m 18s` 字面正则命中 |
| E1 | ✔ PASS | OpenCode 列表 + 转录（25 文本节点）正常，无崩溃 |
| E2 | ✔ PASS | crash buffer 全程为空 |

## 未执行 / 保留项

- 无 BLOCKED 项。
- C1/C2 为**保留判定**：功能机制在替代会话/新会话中得到仪器证据，但任务指定的 session-94365bc9 路线因 V3 压缩边界无法呈现 checklist 指定对象。若必须以该会话为唯一判据，则 C1、C2 均应记 FAIL。
- 全程未修改产品代码，未运行 Gradle 构建/安装，未修改原 checklist 文件。

## 关键证据文件清单（/tmp/acc-emu/）

- A：`a1.xml`、`a2-step1..3.xml`、`a3-step1..3.xml`、`acc-A1..A3.png`
- B：`b1-step1/2.xml`、`b2-list.xml`、`b2-settings*.xml`、`b3-list/step1/menu.xml`、`acc-B1..B3*.png`
- C：`c1-transcript.xml`、`c1-scroll-*.xml`、`c1u-*.xml`、`c2s-*.xml`、`alt-2.xml`、`c2f-2.xml`、`c2exp-1.xml`、`d5-5.xml`、`d5-exp.xml`、`acc-C1-alt.png`
- D：`d-model.xml`、`d-model2.xml`、`d-typed3.xml`、`d2-poll-1.xml`、`d3-*.xml`、`acc-D1.png`
- E：`e1-list.xml`、`e1-transcript.xml`、`acc-E1.png`

*（设备侧同名快照同步存于 `/sdcard/<name>.xml`。）*
