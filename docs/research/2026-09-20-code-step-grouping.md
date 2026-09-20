# 调研:DSH Web「代码步骤行 + 工具子行」聚合形态的机制还原与 oc-beacon 支持评估

- **日期**:2026-09-20
- **性质**:只读调研(oc-beacon 仓库零代码改动;本文件为唯一新增产物)
- **素材**:
  - A) DSH Web 外壳 bundle:`~/.linuxbrew/.../dsh-web-frontend/dist/assets/index-BKQ_L1z6.js`(555KB)
  - B) DSH 服务端/客户端插件(编译后 js,未混淆,含 `//#region` 源文件路径):`~/.dsh/profiles/node_modules/@deepseek-ai/dsh-client-ui-chat`、`dsh-client-ui-tool`、`dsh-client-ui-conversation`、`dsh-client-ui-trajectory`(均 v0.1.5-rc.2)
  - C) 真实会话:`~/.dsh/sessions/**/session.v3.jsonl.zstd`(zstd -d 解码)
  - D) oc-beacon 源码(master 工作区)

---

## 0. 结论先行(TL;DR)

1. **用户观察的「# 代码 · <步骤摘要>」父行在 DSH 0.1.5-rc.2 前端中不存在对应的"多工具聚合父行"组件**。「代码」(`tool.title.code`,英文 "Code")是 **run_code 单个工具行的标题词条**;「· <摘要>」是该工具行的参数派生摘要(灰色小字)。每个工具调用在 DSH Web 端恒为**独立一行**,而非挂在公共父行下。
2. DSH 真实的"聚合"是**轮级 TurnProcess 折叠条**:一轮(turn)完结后,若用户开启「紧凑」转录设置,该轮全部过程行(工具行、中间 assistant 步骤)默认**隐藏**,由一条「N 次工具调用 · M 条消息 · K 个 subagent」(无内容则「已思考」)折叠条代替,点击展开。**摘要 = 计数式,不含任何文本/LLM 摘要**。
3. 「缩进工具子行」的出处是 **PTC 嵌套子调用树**(`run_code` 内部再发起的 bash/read 等,经 `tool/ptc-dispatch` 事件挂在 rootCallId 下),以左缩进 22px + 左边框线的 `div.subCalls` 递归渲染——这是 run_code 工具行的**展开体内部结构**,不是轮级分组。
4. oc-beacon 三端数据可达性:**V1 ✅**(step-start/step-finish part 官方下发且已解析)、**V2 ✅**(session.next.step.started/ended 事件已消费;step-start part 面解析兼容)、**DSH ✅**(step/start 已接收仅用于 TTFT,step/end 判 LIFECYCLE_NOISE 忽略;turn/step 编号随 assistant/message 整装到达)。
5. 建议:**分阶段支持(P2)**。复用 #247/#243 聚合基建扩展一个「按 step 分组的工具父行」(PartGroup 新组 + 新 RenderItem),折叠交互走 #420 CardExpandReveal 同款同帧配对;**不做** LLM 文本摘要与 PTC 嵌套树。MVP 见 §4。

---

## 1. DSH 机制还原(逐条标来源)

### 1.1 先澄清:三层结构,不是一个父行

DSH Web 聊天区由客户端插件树在浏览器内装配(外壳 bundle 不含聊天渲染——`index-BKQ_L1z6.js` 中 `assistant`/`chat`/`toolCall` 命中数为 0;聊天 UI 来自 `dsh-client-ui-*` 插件经 `window.__ModuleLoader__.load` 注入,证据:dsh-thinking-fix 插件 `lib/client.js` 头注释点名 `dsh-client-ui-trajectory`)。聊天区与消息行相关的三层:

| 层 | 组件 | 形态 | 来源(B 区包文件) |
|---|---|---|---|
| 行 | `ToolRow`(+`GenericToolCard` 及各专门 toolview) | `<图标><标题> · <摘要>` 单行,可展开体 | dsh-client-ui-tool `lib/client.js`:1187-1388(ToolRow)、1389-1429(GenericToolCard) |
| 轮 | `TurnProcessNodeView` | 折叠条「N 次工具调用 · M 条消息 · K 个 subagent」 | dsh-client-ui-chat `lib/client.js`:3277-3304 |
| 嵌套 | `ToolCallBranch.subCalls` | PTC 子调用左缩进树 | dsh-client-ui-tool `lib/client.js`:1488-1515 + CSS:1432 |

### 1.2 「代码」标题与「· 摘要」的来源(工具行层)

- **标题词条表**(dsh-client-ui-conversation `lib/client.js`:13730-13746,中文 locale):
  ```
  "tool.title.search": "搜索"    "tool.title.read": "读取"     "tool.title.bash": "Bash"
  "tool.title.write": "写入"     "tool.title.edit": "编辑"     "tool.title.code": "代码"
  "tool.title.generic": "工具调用"
  ```
  variant→icon 映射:dsh-client-ui-tool `client.js`:1391-1399(code → `IconCodeOutline16`)。
- **行结构**(ToolRow:1254-1293):`DisclosureRow`(共享 UI kit 组件,即外壳 bundle 中的 `_row_luwio_16` class,`Yp` 函数,offset 326255,导出名 `DisclosureRow`——注意:o3BgMG_* 前缀在整个 dist assets 中不存在,应为旧版构建产物)包:
  - `title` = 词条标题(「代码」等);
  - `collapsedContent` = `sep`(2×2px 圆点,CSS `_5OnbHa_separator`,dsh-client-ui-tool:3082)+ `summary`(单行截断灰色小字)+ `suffix`(如 diff ±N 统计);
  - 展开体按 toolview 分发:code variant → `CodeBlock(lang=typescript)`,bash → `TerminalBlock`,edit/write → `DiffBlock`,read → `ReadBlock`,search/web/image/ask-question 各有专卡;兜底 Input/Output 双段 ioCard + Inspect 按钮(1294-1384)。
- **摘要文本来源**:工具参数/结果派生(`toolRowModel`:`tool-call-model.js` 794-973;文件路径、命令、检索词等),**不是** LLM 生成。
- **分发机制**:`renderSlot("tool.call.toolview", owner, { entryKey: toolName, fallback: GenericToolCard })`(ToolCall:1479-1485)——按工具名 keyed 插槽,插件可注册自定义行。

> **勘误**:任务线索中「# 代码 · <步骤摘要>」的「#」无字面来源(前端无 "#" 前缀模板);「步骤摘要」即该工具行自身 summary。若用户看到多行工具共用一个「代码」标题,那是**一轮内多个 run_code 调用各占一行**,每行同形(代码 · 摘要),视觉上像一组。

### 1.3 TurnProcess:轮级折叠(真正的"聚合父行")

- **spec 投影**(`turnProcessDefinition`,dsh-client-ui-chat:6823-6886;`processSpec`:6756-6786):逐 turn 累计:
  - `toolCallCount`:`tool/call` 事件计数,subagent 类工具(subagent*)单独计 `subagentCount`(6798-6804);
  - `messageCount`:assistant/message(append)计数,按 step 记账(6789-6796);
  - `answerStep` = **最后一个**含非空可见回复且**不含 tool-call 块**的 assistant-step(`latestAnswer`:6751-6755);
  - `processStartSeq` = turn 起始 seq;`answerAnchorSeq` = answerStep 整装 seq——两者划出「过程区」。
- **归属条件**(`ChatNodeSeat`:1556):
  ```js
  processMember = 非独立kind && processWindowReady && anchorSeq ∈ [processStartSeq, answerAnchorSeq)
  ```
  独立 kind(`TURN_PROCESS_INDEPENDENT_KINDS`:1415-1423):system-prompt/user/steering/turn-process/turn-error/turn-max-tokens/turn-tail。
- **折叠前置门**(`processWindowReady`:1555):`compactTranscript`(用户设置「对话显示→紧凑」)`&&` turn 已完结(`turnClosed`)`&&` answerAnchorSeq 已定 `&&` 历史未截断。**标准模式(默认)永不折叠,全程平铺**。
- **渲染行为**(1571-1622):折叠态下过程行 `hidden="until-found"`(浏览器查找可自动展开,1574-1576);最终回答不隐藏,仅当回答内联 reasoning 时藏其 reasoning(3065,`reasoningHidden`)。
- **展开交互**:点击折叠条 → `setOpen` 写入 chat store(`setTurnProcessOpen(turn, answerStep, open)`,1461-1473)——**展开态是会话内易失 UI 态,不持久化**;标签 = 计数词条拼接(3282-3285):
  ```
  "{count} 次工具调用" · "{count} 条消息" · "{count} 个 subagent"   // 全空 → "已思考"
  ```
  (词条:2629-2680 locale 区;分隔符 `message.turnProcess.separator` = " · ")
- **流式期间**:turn 未完结 → 永不折叠,过程行实时平铺(与 oc-beacon「流式 turn 不分段」守则同哲学)。

### 1.4 PTC 嵌套子调用树(「缩进子行」真身)

- 事件对:`tool/ptc-dispatch-start`/`tool/ptc-dispatch` 带 `rootCallId`,被 tool-call Definition 收进 `children/parents` Map(dsh-client-ui-chat:6494-6516)。
- 渲染:`ToolCallBranch` 递归,子调用包在 `div.subCalls`(CSS:`margin:4px 0 2px 22px; padding-left:8px; border-left:.5px solid var(--dsw-alias-border-l2); gap:4px`)——**左缩进 + 竖线**的树形视觉(dsh-client-ui-tool:1500-1513 + 1432)。
- 即:只有 run_code(PTC)的内部子调用才有「父子行+缩进子行」;普通工具调用(read/bash/edit…)彼此平级。

### 1.5 数据侧:真实会话样本(session.v3.jsonl.zstd,bab5c3c3 会话)

事件平铺且**每条自带 turn/step 编号**,无独立「代码步骤行」条目类型:
```json
{"type":"turn/start","seq":6,"data":{"turn":1}}
{"type":"step/start","seq":8,"data":{"turn":1,"step":1}}
{"type":"assistant/message","seq":17,"data":{"turn":1,"step":1,"message":{"content":[{"type":"reasoning",...},{"type":"tool-call","id":"call_...","name":"run_code",...}]}}}
{"type":"tool/call","seq":18,"data":{"turn":1,"step":1,"callId":"call_...","name":"run_code","arguments":"..."}}
{"type":"tool/result","seq":27,"data":{"turn":1,"step":1,"message":{"source":{"kind":"tool","callId":"call_..."},...}}}
{"type":"step/end","seq":33,"data":{"turn":1,"step":1}}
```
「父行」(折叠条)纯前端投影,**服务器不产生任何聚合条目**;tool-call 块虽在 assistant/message.content 中,但渲染层跳过(`blockIsVisible` 对 tool-call 返回 false,dsh-client-ui-chat:4370;AssistantNodeView `case "tool-call": break`:3015),工具行独立成节点(kind:"tool-call")。

### 1.6 外壳 bundle 线索的归位

- `_row_luwio_16`:共享 kit `DisclosureRow` 的 row class(`Ut={root:qc,row:Gc,leading:Qc,iconIdle:Yc,chevronHover:Kc,title:Xc}`),被 ToolRow/GenericCommandCard 等复用——「行组件」线索成立,但它只是单行折叠原语。
- `o3BgMG_row`/`o3BgMG_errorSummary`:在当前 0.1.5-rc.2 全部 assets(index/vendor js+css)中 **0 命中**——旧版本构建的 class,不构成现状依据。
- 「代码」文案:外壳 bundle 0 命中;真身在 dsh-client-ui-conversation locale(1.2 节)。

---

## 2. oc-beacon 数据可达性(三端)

| 端 | step 边界载体 | 下发实证 | oc-beacon 现状 |
|---|---|---|---|
| **V1** | `step-start` / `step-finish` **message part**(官方 part 类型,附 snapshot/cost/tokens) | `docs/opencode-api-reference-v1.md`:4305-4306;官方 PR #12470、issue #16749(`docs/terminology/survey/A1-official-api-audit.md`:71) | ✅ 解析:`MessageEventParser.kt`:161-162、`Part.kt`:23-24/146-151;**UI 渲染为空**(`PartContent.kt`:380-385,注释「WebUI 不显示这些」) |
| **V2** | `session.next.step.started/ended` **SSE 事件**(started 带 agent/model,ended 带 finish/timestamp/tokens/cost) | `V2SseMapper.kt`:150/177;`SessionNextEventHandler.kt`:249/257 | ✅ 已消费:FSM 活动(`SessionStateService.kt`:382-389)、assistant 骨架播种与 tokens(`MessageEventHandler.kt`:422-425/504-558);step-start **part** 解析兼容保留(MessageEventParser),V2 事件流面不产生该 part |
| **DSH** | `step/start`/`step/end` 平铺事件(带 {turn,step});`assistant/message` 整装同带 turn/step | 实测 session.v3.jsonl(§1.5) | ✅ `step/start` 已收——仅记 stepStartTimes 派生 TTFT(`DshEventMapper.kt`:662-669,#411),不产 UI 行;`step/end` 判 **LIFECYCLE_NOISE** 忽略(`DshEventVocabulary.kt`:54);工具行来自 tool/call 映射 |

**结论**:三端都有 step 边界信号,但**形态不一**:V1=part、V2=事件(part 兼容)、DSH=事件。任何「按 step 分组」的聚合器必须三路取锚,或在 RenderableTurn fold 层归一。

### 2.1 oc-beacon 已有聚合基建(复用面)

- `RenderItem` 四型:`TurnDivider`/`GroupedParts`/`SyntheticNotice`/`RepeatingTool`(`RenderableTurn.kt`:70-85);
- `PartGroup.Context`(read/glob/grep 连续 ≥2 → `ContextToolGroupCard`)/`PartGroup.Single`(`PartGrouper.kt`:8-38);
- #247 同键工具 ×N 折叠(`toolDedupKey` = 工具名+命令,RenderableTurn.kt:118-123;分片路径 `ChunkAssistantItems` 同款,MessageCardAssistant.kt:678-708);
- 双渲染路径已并存:主循环 + #258 分片(`ChunkAssistantItems`)——任何新 RenderItem **必须两处同步**。

---

## 3. 支持建议:分阶段(P2),先「工具组父行」后「轮级折叠」

### 3.1 为什么不做 DSH 全量复刻

1. **DSH 默认不折叠**(紧凑是 opt-in 设置)——Android 端若默认折叠,反而劣化「工具实时流」的观测体验,违背本仓 SSE 滚动铁律的流式平铺哲学。
2. oc-beacon 已有 #243/#247 两级降噪(同键 ×N、context 组);轮级「N 次工具调用」的边际收益集中在**超长 turn 历史回看**场景——这恰是 #258 分片路径的地盘,聚合应先落在分片/历史渲染,不动流式。
3. TurnProcess 的「计数摘要」信息量低;Android 用户已可用 TurnDivider+统计栏获取轮粒度信息。

### 3.2 值得做的形态:MVP = 「step 工具组父行」(折叠态默认收起,仅历史 turn)

形态对齐用户预期(父行 + 子行),语义对齐 DSH(计数摘要),范围收敛在渲染层:

```
▼ 代码步骤 · 6 次工具调用        ← 父行(计数摘要,点击展开)
    [现有 ToolCard:run_code/bash/edit/...]   ← 子行,原样复用
```

### 3.3 改造路径(组件级)

1. **聚合器**(`RenderableTurn.kt` fold 处,与 #247 同层):
   - 新 `PartGroup.StepTools(val parts: List<Part.Tool>, val stepIndex: Int)`;
   - 分段锚点:V1/V2 用 `Part.StepStart` part 位置切组(现被 PartContent 忽略,恰可作纯锚点);DSH 端在 DshEventMapper 映射 tool/call 时顺带记 step 号(或按 assistant/message 边界近似切);
   - 准入:**非流式 turn**(与 #258 分片同一资格审查)+ 组内 ≥2 且未被 #247 吞掉的工具;ask_user_question/skill 等 NON_DEDUP 同样排除。
2. **渲染项**:新 `RenderItem.StepToolGroup(group)` → 新 `StepToolGroupCard`:
   - 父行 = 标题(「代码步骤」或复用 i18n 新词条,15 语言走 i18n-guide)+ 计数摘要(「N 次工具调用 · M 次读取」式,对齐 DSH 词条措辞);
   - 子行 = `Column { parts.map { PartContent(it) } }`,**整组一个 LazyItem**。
3. **两路同步**:主循环与 `ChunkAssistantItems`(MessageCardAssistant.kt:709-737 的 when)各加一分支。

### 3.4 与 #420 CardExpandReveal、B 类恒驻 banner 的交互注意

- **#420 CardExpandReveal**(ToolCardScaffold.kt:248 等 8 处;backlog #420 单一时钟同帧配对):父行展开/收起的高度变化**必须**走同款「尺寸+淡入同帧配对」,否则贴底场景重现 ±644px 跳动;子行列表整体包进 Reveal content,修饰符必须在 Reveal 内部(ToolCardScaffold.kt:252「硬地板教训」)。
- **展开不增删 LazyItem**:整组渲染为单个 item(内部 if(open) 组合子行),列表结构恒定——这是规避 ChatMessageList B 类问题的关键。若走「拆多 item + 隐藏」路线(DSH 的 hidden=until-found 等价物),会改变 itemsIndexed 的 key/index 序列,与恒驻 banner(retry/tool/step_progress 等七项,ChatMessageList.kt:629-660)的恒驻声明冲突,禁止。
- **贴底守卫**:展开越界离开跟随模式的守卫(ChatScreen.kt:1009,#420)对父行点击同样生效,无需新逻辑,但验收必须含「贴底四连击」用例(参照 #420 修复验证)。
- **流式安全**:折叠只发生在非流式 turn;流式中 step 组恒平铺,不触碰 48ms 批处理/高度补偿管线。
- **状态存活**:展开态用 remember(组 key)会话内存活即可(DSH 同款不持久化);跨 TurnSegmentPlan 分片的组应避免跨 item 边界(fold 时整组落同一分段,分片计划按 renderItem 索引切)。

---

## 4. 最小可行版本(MVP)范围

**做**:
1. `PartGroup.StepTools` + `RenderItem.StepToolGroup`(聚合器 ~60 行,准入:非流式 && ≥2 工具 && 非 NON_DEDUP);
2. `StepToolGroupCard`(父行计数摘要 + CardExpandReveal 折叠,子行复用 PartContent);
3. 主循环 + ChunkAssistantItems 双路接入;
4. 单元测试:三端锚点切组(V1 part 锚 / V2 事件近似 / DSH step 号)与 #247 互斥;
5. 真机验收:贴底四连击(#420 用例)、分片长 turn 展开、E2E 会话生命周期(dialogue-e2e-test-plan)。

**不做(本期)**:
- LLM/文本「步骤摘要」(DSH 亦无;计数式足矣);
- PTC 嵌套子调用树(oc-beacon 工具模型无 subCalls 结构,DSH 特有 run_code 形态);
- 「紧凑模式」全局设置(先只对历史 turn 生效,设置项待用户反馈);
- TurnProcessNodeView 全量对齐(轮级折叠条与 TurnDivider+统计栏功能重叠)。

**回滚面**:纯渲染层新增,聚合器开关一处处回退;不触碰 MessageStore/FSM/SSE 管线。

---

## 附:关键文件索引

| 主题 | 文件 |
|---|---|
| DSH 工具行 | ~/.dsh/profiles/node_modules/@deepseek-ai/dsh-client-ui-tool/lib/client.js(ToolRow:1187,GenericToolCard:1389,ToolCallTree:1446,apply:2362) |
| DSH 轮级折叠 | ~/.dsh/profiles/node_modules/@deepseek-ai/dsh-client-ui-chat/lib/client.js(turnProcessDefinition:6823,processSpec:6756,ChatNodeSeat:1544,TurnProcessNodeView:3277,locale:2618-2835) |
| DSH 标题词条 | ~/.dsh/profiles/node_modules/@deepseek-ai/dsh-client-ui-conversation/lib/client.js:13730-13746 |
| DSH 折叠行原语 | dsh-web-frontend/dist/assets/index-BKQ_L1z6.js(DisclosureRow=Yp,offset 326255) |
| 会话样本 | ~/.dsh/sessions/--home-leo-tkp-Documents-code-mine-oc-beacon--/bab5c3c3-*/session.v3.jsonl.zstd |
| oc-beacon part 解析 | app/src/main/kotlin/dev/leonardo/ocbeacon/data/api/sse/parsers/MessageEventParser.kt:161 |
| oc-beacon 渲染空分支 | app/src/main/kotlin/dev/leonardo/ocbeacon/ui/screens/chat/components/PartContent.kt:380-385 |
| oc-beacon 聚合基建 | app/src/main/kotlin/dev/leonardo/ocbeacon/ui/screens/chat/tools/RenderableTurn.kt / PartGrouper.kt |
| oc-beacon DSH 映射 | app/src/main/kotlin/dev/leonardo/ocbeacon/data/api/dsh/DshEventMapper.kt:662-669 / DshEventVocabulary.kt:54 |
