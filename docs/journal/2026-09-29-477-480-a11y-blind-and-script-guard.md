# #477+#480：a11y 语义盲区定罪 + 脚本参数防御（2026-09-29）

> 状态：#480 已修复待验收；#477 定罪深化、修复方向待裁决
> 关联：backlog #477 / #480
> 来源：用户开工指令「开始 #477+#480 …先演示…修复与自测…call 我验收」

## #480 backlog-new-batch.sh 参数防御（已修复）

**现象（现场复现）**：`./scripts/backlog-new-batch.sh "docs/journal/2026-09-29-closure-audit.md"` 静默创建畸形文件 `docs/journal/2026-09-29-docsjournal2026-09-29-closure-auditmd.md`（kebab 化剔除 `/` 与 `.` 后拼接），零警告。实害：#476 批 fa91c578 清理的 mangled 文件即此因。

**修复**：参数含 `/` 或以 `.md` 结尾 → die 并提示只接批次名。

**自测三连**：
- 传路径 → 拒绝并报用法 ✓
- `'closure audit'` → kebab 化命中既有文件报「已存在 勿重复创建」✓
- 纯中文 → 兜底 batch ✓（测试残留已清理）

**顺手修复**：`scripts/miui-install.sh` 的 `adb -s $SERIAL` 未加引号——带空格的 mDNS serial（`adb-e69a99d8-yzT17Y (2)._adb-tls-connect._tcp`）被词法分裂为 `unknown command`。全部改为 `adb -s "$SERIAL"` 后，本批用它点穿 MIUI 弹窗装 androidTest 包成功（顺手件实证）。

## #477 长文 a11y 语义零暴露——定罪链与机制边界

### 现象（双源干净取证）
- **视觉**（zai 双路交叉 + 像素）：长文 turn（Yangtze essay，3585 chars）30-31 行连续英文正文满屏渲染完整、无空白（/tmp/d477_eyes.png、/tmp/d477_lw2.png）
- **语义**（uiautomator dump，同刻）：正文区 0 文本节点（连空 TextView 都没有，仅 1 个 h≈1104px 空 View = chat-message-list 视口壳）；同屏工具卡（Build/glm-5.3-flash/50.3s）、顶栏、输入条全部正常暴露
- **对照**：同屏表格 turn 单元格全暴露（39 节点）；同 turn 的短 part（993 chars）暴露；短回复（<2048）全暴露；user prompt（纯 Text 路径）暴露

### 分叉实证（A11yDiag 探针，DEBUG-only，已留树）
| 场景 | 路径（探针实录） | a11y |
|---|---|---|
| 表格 turn（巨型，分段） | `preParsed chunked=true`（Giant 多 LazyColumn item） | ✅ 暴露 |
| 短 part（993，全量） | `preParsed chunked=false stateType=Success` | ✅ 暴露 |
| 短回复（312/222） | `syncSmall` | ✅ 暴露 |
| **长文（3585，全量）** | `asyncTerminal Loading` → 161ms 后 `preParsed chunked=false Success` | ❌ **盲** |

时间线（真机 logcat 21:47:51）：`src=asyncTerminal len=3585 stateType=Loading` → `preParsed chunked=false len=3585 stateType=Success`——**最终稳态走全量 preParsed 且 Success，视觉完整而语义零暴露**。

### 已排除的假设（全部实证/源码级）
1. **覆写组件层**：`MarkdownBasicText`（库）= `BasicText` 纯委托；表格单元格同款组件+同款 `clickableMarkdown` modifier 却暴露
2. **库重载层**：`Markdown(markdownState=)` 仅 collectAsState 后委托 `Markdown(state=)`，两路径完全同构
3. **success 槽层**：默认 `MarkdownSuccess`（Column+forEach MarkdownElement）与项目 `chunkSuccessSlot` 逐行同构
4. **clearAndSetSemantics/语义清除**：全项目 grep 无
5. **流式完结态特有**：重进会话（进程重启）后历史长文仍盲
6. **item 高度超视口**（假设 H）：21:22 首日取证中**未分段**表格 turn（单 item，高 3674px > 视口 2594px）单元格照常暴露——超视口单 item 不必然盲
7. **裂变带/recent 集**：这解释了长文为何未分段（视口内 turn 受 F2 裂变带保护不建分段计划+recentStreamedTurnKeys 冷却），但不是盲的直接原因（未分段表格 turn 暴露）

### 剩余最强嫌疑（未定罪）
- **变量收窄到「单 part 纯文本长度」**：同 turn 内 993 chars part 暴露、3585 chars part 盲（同 item 同路径同组件）；表格 part（>3000 但结构为 GFM TABLE）不受影响
- `SPLIT_PARAGRAPH_THRESHOLD_CHARS = 3000`（normalizeForRender 拆段线）是最强分界候选：993 < 3000 不拆（暴露）、3585 > 3000 拆段（盲）；表格 part 拆段只动普通行、TABLE 结构不动（不受影响）。2500 chars 对照实验因真机滚动/dump 陈旧摩擦未完成
- 桥侧机制（Compose semantics→AccessibilityNodeInfo）黑盒：真机 androidTest 语义断言已写好（`LongMarkdownA11yTest` 三用例），但被 MIUI 安装拦截+Espresso idle 同步挂死（app 周期 ticker）双重阻塞，待环境解法

### 修复候选（需裁决，未盲改）
1. **A. 完结即分段**：流式完结帧（#472 换装重建同帧）把超阈值 turn 直接以分段形态进入 entries——裂变发生在本来就要重建的帧，理论上无感；但触碰 #300 裂变禁令边缘+高度引擎交互，深水区需专项验证
2. **B. 到达即分段兜底**：buildChatEntries 对「首次进入组合且 registry 已 Parsed 的 ≥3000 part」即时构建 segPlan——只保护历史 turn 首进，不解决流式完结的当屏盲窗口
3. **C. 拆段线对照定罪后修 normalize**：若 3000 拆段线定罪（2500 暴露/3585 盲的分界），修 `splitParagraphs` 的产出形态可能从根上解决——成本最低但需先完成对照实验
4. **D. 平台上报+文档化**：若定罪为 Compose/平台桥缺陷，app 层只留绕行（加速分段覆盖）+已知限制文档

### 影响面重申
- 自动化验证可靠性（dump 判读污染，#467 已注记）
- **TalkBack/无障碍**：盲窗口期内屏幕阅读器用户读不到长文正文（真 a11y 缺陷，主诉级）
- 视觉渲染、滚动、流式全部无恙（多轮 vision+像素+SGR 日志交叉）

## 环境债（本批撞上的摩擦，留档）
1. MIUI 全新安装（test APK）连 pm install 都拦——`miui-install.sh`（引号修复后）可点穿
2. connectedDevDebugAndroidTest 的编排会被 MIUI 拦+卸主包；绕行：手装两包+am instrument 直跑（文档 device-testing.md L90 已载）
3. ComposeTestRule waitForIdle/断言在 app 周期 ticker 下挂死——轮询 fetchSemanticsNodes 也同步等 idle；需 mainClock.autoAdvance=false 方案或瘦身 test Application
4. 真机滚动后 dump 三连同值=陈旧陷阱再现（收 IME+大滚动+rm 旧 dump 三件套才可靠）

<!-- 过程中的取证/验证证据直接写本文件；backlog.md 只留 ≤3 行卡片。 -->

## 已完结卡片迁入（2026-09-29）

### **#480 backlog-new-batch.sh 误传路径静默产生畸形文件名——加参数防御** `chore`
  - 2026-09-29 发掘审计:#476 批实害(fa91c578 清理的 mangled 文件即此因):脚本 kebab 化不拒绝含 / 或 .md 的参数,传路径=拼出畸形名而非报错
  - 修法:参数含 / 或以 .md 结尾即 die 提示只接批次名;3 行改动
  - 2026-09-29 修复:参数含/或.md结尾即die只接批次名;三例自测过(路径拒/kebab命中/中文兜底);顺手修miui-install.sh serial引号(带空格mDNS名分裂);journal 2026-09-29-477-480-a11y-blind-and-script-guard
  - 迁入依据：用户演示验收 ok(2026-09-30 修复前后对比演示:误传路径拒绝/正常名不误伤/仓库零畸形残留);同批用户裁决:工作流脚本类修复此后不立卡直接修(#480 末代先例)（backlog.sh migrate 2026-09-29）

## 裁决记录（2026-09-30）

- **#480 验收+关卡**：用户看过修复前后对比演示（误传路径拒绝/正常名不误伤/仓库零畸形残留），验收 ok，迁移本 journal。
- **新定规**：项目工作流脚本类修复（scripts/ 流程脚本等不进 APK 的项目设施）**此后不立卡**——发现即直接修+自测，证据记入当批 journal；#480 为末代先例。规则已回写 backlog.md 操作纪律段。

## 已完结卡片迁入（2026-09-30）

### **#481 architecture-debt.md 过期两月未刷新——god files 表失真+引擎域未入册** `refactor`
  - 登记册止于 2026-08-07:ChatMessageList 表记 ~674 行,实际经 #42x-#47x 战役已 ~2700+;ChatScrollController/CardExpandReveal/ScrollCompensation 等引擎域新文件未入册
  - 随册并入两测试缺口:分页 androidTest @Ignore(ChatInteractionTest:399,sessionId 空壳致 hasOlderMessages 恒 false,三方案任一可解)+终端 tab 管理无单测(旧表遗留,待复核)
  - 迁入依据：项目设施类按 2026-09-30 定规直接修不立卡(本卡为已立卡存量按原流程迁移):god files 表 2026-09-30 wc -l 全量复测(ChatMessageList 674→2756 等十文件双列对照)+引擎域 11 文件入册(scroll/渲染供给/流式稳定三域,标注铁律前置)+分页 @Ignore 测试缺口并入 §4（backlog.sh migrate 2026-09-30）

## #481 顺带直修（2026-09-30，项目设施类不立卡定规首批）

- **god files 表刷新**：2026-09-30 wc -l 全量复测，双列对照（08-07 vs 09-30）——ChatMessageList ~674→**2756**、ChatViewModel 493→**1574**、ChatScreen ~770→**1360**、MessageEventHandler 857→**1289**；新入册 DshApiClient 2550 / DshEventMapper 2150 / V2ApiClient 1874 / CardExpandReveal 1861 / MessageCardAssistant 1387；700-984 行段 15 文件点名。
- **§3.1 滚动/渲染引擎域入册**：#42x-#47x 战役产物 11 文件（ChatScrollController 488 / ScrollCompensation 708 / PreRenderCoordinator 198 / SafeFlingBehavior 132 / CardExpandReveal 1861 / RenderSupplyCoordinator 602 / TurnSegmenting 208 / MarkdownChunking 413 / SafePrefixGate 516 / StreamingMarkdownPilot 318 / HeldTailReveal+Aging 284），标注「改动前必读铁律」。
- **§4 测试缺口并入**：分页 androidTest @Ignore（pagination_triggersOnScrollUp，sessionId 空壳三解法注释在测试现场）；终端 tab 管理无单测复核仍缺。
- 卡片 #481 迁移关闭（末代先例之二）。

## 2026-09-30 定罪实验（方案 C）全程与定性翻转（用户裁决 B：降级观察）

### 实验与假设推翻链
1. **拆段线假设推翻**：DB 取 3585（盲样本）原文——**标准 markdown 段落格式**（9 行 4 空行，5 自然段），SPLIT_PARAGRAPH_THRESHOLD_CHARS=3000 拆段分支根本未触发；993（暴露样本）实为**表格 part**（其"段落"只是表格后 ~100 字符收尾段）——"长段落从未暴露"的前提数据错位。
2. **Loading→Success 换帧假设（Q）推翻**：「App verification steps」会话 7523 混合 part 同样经历 asyncTerminal Loading→preParsed 换帧（logcat 探针实录），其 >150 chars 段落完整暴露。
3. **「长文=盲」推翻**：v1 API 注入生成 4077 chars 五段纯散文（rowid 902，会话 ses_f12f31d2），冷启进程组合——**段段完整暴露**（"Every river begins with distance..."等）。
4. **「路径=盲」推翻**：17-20k 巨型列表 turn（asyncTerminal Success 终态）列表项段落暴露。
5. **密集 dump 自伤（R2）推翻**：对暴露中的 4077 段落 10 连无间隔 uiautomator dump，复检语义仍完整。

### 今日零盲例 + 新假设 R
两个会话、十几个 turn、0~20k chars、全渲染路径：**零盲例**。重读昨日定罪屏：21:22 同屏**老表格 turn（21:0x 生成）39 节点暴露 vs 21:35 新生成的 essay 正文盲**——分野=内容创建时刻，非内容属性。

**假设 R（降级为观察定性）**：盲=长跑进程（4h+、多轮 20k 生成、反复滚动、多次装包）的状态性退化——新组合内容语义创建失败、已附着老语义保留。盲的全部取证集中于昨日单一进程晚期（21:22-21:49），与 R 自洽；今日冷启进程完全正常。

**再撞上时的抓取清单**：①logcat a11y/AccessibilityNodeInfo 错误 ②dumpsys meminfo（native 压力）③进程 uptime 与近期操作史 ④同屏新老内容暴露分野验证。A11yDiag 探针（DEBUG-only）留树备用。

### 实验过程副产物
- **UI 发送通道第三次撞 #464 活体**（tap 发送命中无业务响应，DB 无新 part）→ 改走 **v1 API POST /session/{id}/message 注入通道**（阻塞式，落库可靠）——长文生成类实验的标准通道。
- 真机滚动注入间歇丢弃 + dump 陈旧墙再现：慢速长拖（duration 1000ms）+ rm 旧 dump + 状态机分步校验（md5 变化才算动）是可靠组合。
- 探针导航法：A11yDiag len 序列可指示视口 turn（仅跨组合窗时打点）。

## 已完结卡片迁入（2026-10-23）

### **#477 长文 markdown 块 uiautomator 语义零暴露——dump 全盲致渲染正常被误判空白** `ui`
  - 2026-09-29 收口审计真机定罪:末轮长文(async parse 路径)可见区 dump 零文本节点(仅 1 个空 text 可点击 TextView h≈1921px),同期旧 turn 正常暴露;vision(三路交叉)+像素双验证内容完整渲染(连续正文/无空白/无圆点)——渲染层无恙,纯语义暴露缺陷
  - 影响面=自动化验证可靠性+#467 dump 实证段污染(核心 SGR 日志证据不受影响)+a11y;待定位暴露分叉(疑 rememberAsyncMarkdownState 路径)
  - 证据:/tmp/ui_b1.xml(底,盲) vs /tmp/ui_top.xml(顶,正常);#475「近空白/折叠态」原判即此 artifact
  - 2026-09-29 定罪深化(见journal):分叉=单part纯文本长度(同turn 993暴露/3585盲,表格组件不受影响);渲染三层同构排除(组件/库重载/success槽);超视口单item假设被未分段表格turn(3674px)暴露推翻;最强嫌疑=SPLIT_PARAGRAPH 3000拆段线;androidTest断言已写但被MIUI+idle挂死双阻塞;修复候选A完结即分段/B到达即分段/C拆段线定罪后修normalize/D平台上报——待用户裁决,不盲改
  - 2026-09-30 定性翻转(用户裁决B降级观察):今日冷启进程两会话十几个turn(0-20k chars全路径)零盲例——五假设全推翻(拆段线:3585原文标准段落格式未触发/Q换帧:7523混合part同经换帧暴露/长文=盲:4077五段纯散文注入段段暴露/路径=盲/密集dump自伤:10连dump后仍暴露);原定罪(长文语义零暴露)重定性=疑似长跑进程状态性退化(R假设:昨21:22同屏老表格turn暴露vs新essay盲分野=内容创建时刻;盲取证全部集中于4h+单一进程晚期);再撞上(长跑进程新组合内容dump全盲)即抓:logcat a11y错误+dumpsys meminfo+uptime;A11yDiag探针留树备用
  - 2026-10-09 用户裁决：转 #535 soak 主动狩猎（周期盲 dump 检测器：dump vs mock 语料/Room 直查 ground truth；命中即抓 a11y 错误+dumpsys accessibility+meminfo+uptime；R 假设=同语料进程早晚期对比可同批验证）
  - 2026-10-10 B2评估：两晚5起blind捕获全良性（短回复/鉴权失败错误轮的稀疏但有效dump；像素var 800+证实内容丰富）。新增判读工具沉淀：①像素统计（mean/var）快速分盲屏与暗色内容屏；②uiautomator bounds为视口裁剪值——钉在视口顶的块其上方元素整棵不出树（#488批3①勘误），裁剪线以下缺席≠未渲染。无真盲渲染线索→维持观察。
  - 2026-10-21 评估收口：①现状复验（模拟器 mock 90 行长文流完成后）——9-29 定罪的最坏形态「可见区仅 1 空节点全盲」不再现（视口 71 节点 8 文本，思考卡标题/reasoning 片段/prompt 均暴露），但 mock 正文行文本仍未见暴露（滚屏采样被贴底自愈干扰，正文区盲否未定证）；A11yDiag 探针在流式大流量下被 logcat 滚掉（判读须及时收割或加缓冲）②改进方向评估：A=app 侧 semantics 注入（真实收益=读屏无障碍+E2E 断言可靠，但动 markdown 承重组件域需完整回归窗口）B=测试侧双通道（#535 已沉淀 dump+截图，零成本）C=断言规约（E2E 避开纯 dump 文本依赖）③判定：测试影响已缓解（B/C 既有实践），a11y 面收益真实但本应用户群需求低——**维持观察，若修走 A 且与 B4 同窗口（ChatScreen 稳定窗），触发条件=读屏需求或 E2E 再踩盲**
  - 迁入依据：观察收口（用户 2026-10-23 批准）：缺陷面真实（长文 markdown 语义零暴露）但关闭条件明确化——①测试影响已缓解（dump+截图双通道为既有实践+断言规约避开纯 dump 文本依赖）②用户群读屏需求未出现③修复路径已评估（A=app 侧 semantics 注入，与 B4 ChatScreen 稳定窗同窗口，动承重组件域需完整回归窗）。触发重启条件：读屏需求出现或 E2E 再踩盲；届时按 2026-10-21 评估收口节的 A 方案窗口约束开工（backlog.sh migrate 2026-10-23）
