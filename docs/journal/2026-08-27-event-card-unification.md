# event-card-unification（2026-08-27）

> 状态：待验证（代码+自动化+真机走查完成，V6 用户人工验收 pending）
> 关联：docs/archive/specs/2026-08-26-event-card-unification-design.md · backlog #234
> 来源：用户 2026-08-26「主对话流中出现新元素（SSE）的通知样式统一」→ #234 卡

## 开工快照（2026-08-27）

- **前提确认**：#233 架构重构已验收（docs/journal/2026-08-26-arch-campaign-acceptance.md，master dc98a823）；重构后代码现状复核——
  - task/shell 卡：ui/screens/chat/components/SyntheticNotificationCard.kt（composable + 解析函数同居），渲染挂点 MessageCardAssistant.kt 两处 RenderItem.SyntheticNotice（其一注明「本渲染项已无生产者——防御保留」）
  - system 单行通知（#232）：ChatMessageList.kt isUser 分支内联 ~1307–1379，展开表 systemNoticeExpandedStates（屏幕级 mutableStateMapOf，#227 模式）
- **用户开工裁决**（ask_user 实录）：
  - Q15 描述行：「如果有命令预览（实际的描述）那就也激活 shell，其他的卡片同理」→ 数据在则激活
  - Q16 批次节奏：两批连做（独立 commit），合并一次真机验收
- **实施映射（重构后现状落点）**：
  - 新组件 EventCard.kt 落 components/（与 MessageBubble 同目录——契约要求容器与其同构，直接复用 MessageBubble 作外层容器）
  - 三类展开记忆统一走单一屏幕级表 eventCardExpandedStates（替代 systemNoticeExpandedStates；compaction 表不动——另一族）
  - 解析层零改动：parseSyntheticTask/SyntheticTaskInfo/extractTaskDescription 原样保留（测试 SyntheticTaskParserTest/ParseSyntheticTaskTest 同包不迁移）
  - i18n：新增 chat_event_task_completed/task_failed/shell_completed/shell_failed/tool_catalog_changed + chat_event_generic（降级态标签，§3 清单外补一处——旧卡 fallback 行为迁入新卡的必要承载）；退役 chat_background_agent_completed/failed、chat_background_shell_completed/failed、chat_label_tasks、chat_system_notification（长期死 key 一并清）

## 批一（EventCard 组件 + system 迁入）

- **组件层**（commit e70b74d1）：
  - `MessageBubble` 增加可选 `onCardClick: (() -> Unit)? = null`——null 时行为零变化（user/assistant 气泡不受影响）；clickable 挂在内层内容 Column（padding 之内），展开区滚动/子元素点击自然消歧，无手势冲突
  - 新建 `EventCard.kt`：严格同构模子（参数表 §2）。失败态 ErrorOutline+AgentError 描边；跳转箭头经 labelTrailing 进标签行（常驻两态，#216 守恒）；chevron 仅 hasBody 时显示；展开两段式 HorizontalDivider 包夹（heightIn(max=300dp) 在 verticalScroll **之外**——#232 勘误铁律落代码注释）；动作区 End 对齐 TextButton 行
  - 设计决策：布局骨架复用 MessageBubble（契约「容器同构」的直接兑现——shadow `spacer` 死变量笔误当场修正，未入编译态）
- **迁入**：`ChatMessageList.kt` isUser 分支内联块（原 ~1307–1379）替换为 EventCard 调用——body=Text(schema 全文)（旧通知 bodySmall 样式守恒）、label=`chat_event_tool_catalog_changed`、图标 Info、描述行不激活（Q15：system 无描述数据）；`SysMsgDiag` DEBUG 取证日志保留并改标记为 `#234 event-card branch`
- **展开记忆**：`systemNoticeExpandedStates` → 更名 `eventCardExpandedStates`（单一屏幕级表服务三事件卡家族；compaction 表不动）
- **i18n**：`chat_event_tool_catalog_changed` 15 语言全插入（锚 chat_system_notification 后）；`scripts/i18n-check.sh` PASSED（672 keys × 14 languages，全程 81s——注意：此脚本是慢非死，默认短超时会静默截断输出，需 ≥120s 超时或后台跑）

## 批二（task/shell 迁入 + 旧卡退役）

- **适配器化**：`SyntheticNotificationCard.kt` 整体重写——composable 缩为 EventCard 薄适配器（解析函数/正则/SyntheticTaskInfo 原样保留在文件底部，「解析层零改动」守恒项；单测 SyntheticTaskParserTest/ParseSyntheticTaskTest 同包直引不受影响）。参数表映射：task=CheckCircle+任务描述行+定位动作钮 / shell=Terminal+命令预览行 / 解析失败降级=Info+generic 标签+原文截断作描述行；navTargetId 经显式 lambda 参数传入规避 smart-cast 问题；agent 输出截断 2000 / shell 全量守恒
- **形态翻案落档**：本组件头注声明 #67「独立气泡方案 A」翻案链指向 spec §6/§7
- **传参链**：MessageCard 增加 `eventExpandedStates: MutableMap<String, Boolean>` 必填参 → SYNTHETIC/ASSISTANT 双分支转发；MessageCardAssistant 主签名/ChunkedAssistantMessage/ChunkAssistantItems 三层贯通（防御性 SyntheticNotice 分支同步换签名）；ChatMessageList 四个调用点全部接 `eventCardExpandedStates`
- **编译教训**：一次工具调用中断吞掉后续 edit 导致 4 处连锁报错（helper 签名与其分支调用缺失）+ 一处漏网 ChunkAssistantItems 调用点（subList(0,targetIdx) 首段）——grep 参数分布后逐一修复，未用 git checkout 回滚（错误均可定位）
- **i18n**：新增 `chat_event_task_completed/task_failed/shell_completed/shell_failed/generic/locate_task` ×15 语言；退役删除 `chat_background_agent_completed/failed`、`chat_background_shell_completed/failed`、`chat_label_tasks`、`chat_system_notification` ×15（删前 grep 证零引用；`a11y_locate_task`/`tool_terminal`/`tool_sub_agent` 因 ToolCardRegistry/TaskToolCard/新卡仍引用保留）
- **自动化验证**：compileDevDebugKotlin 绿 · testDevDebugUnitTest --rerun BUILD SUCCESSFUL（1m04s，含 SyntheticTaskParserTest/ParseSyntheticTaskTest）· assembleDevDebug 出包成功（app-dev-debug.apk 03:31）
- **commit 策略说明**：Q16 用户拍板「两批连做合并验收」——批一迁入与批二改造在同文件（ChatMessageList）交织，无法机械化拆分为两个纯净 commit；实施为连续推进、验证分步全绿，最终单 commit 落地（journal 分段记录批次边界）

## 二轮：V6 首轮用户反馈修复（2026-08-27）

用户验收首轮后 4 条反馈 + 走查发现，全部当轮修复（spec §7 追加裁决 Q17–Q19）：

1. **箭头收窄（Q17 用户定规）**：「子智能体完成才有跳转箭头；shell 与其他的不需要」——navTargetId 收窄为 `source=="agent"`；同时 `call_` 前缀拦截（工具调用 id 非会话 id，#240 悬空跳转防御）。shell/system 卡不再显示箭头
2. **展示不完全（Q18）**：取消 agent 输出 2000 字符截断——展开正文全量渲染（shell 本就全量），300dp 滚动区承载长度
3. **Markdown 字号大（Q19）**：EventCard 新增 bodyFontScale 参数（LocalDensity 密度级缩放——字号与间距同缩），task/shell 正文传 0.85f 小一档；system 的 schema 正文维持原字号（等宽 schema 文本缩档反而伤对齐）
4. **偶发重叠**：主嫌疑=展开滚动区越界绘制（Compose 滚动容器默认不裁剪溢出内容；「偶发+位置不定」与视口停靠相位吻合——与既有调研指向一致）。clipToBounds 三处落防：① EventCard 展开区 ② ToolCardRenderer 共享输出容器（**全部工具卡共用**——把防御面推广到同款写法全家族）③ ReasoningBlock 思考块。用户确认「之前的调研似乎也是指向事件展开处」
5. **标题行不贴边**：用户观察到 chevron 距卡右缘 ~20dp——根因=标签行继承气泡内容的 16dp 水平缩进。MessageBubble 重构：整段 Column padding 下沉为节级（渲染几何等价拆分），新增 labelRowHorizontalPadding 参数（null=默认路径几何不变）；事件卡设 8dp 左右对称贴边（保留与圆角描边的呼吸空间不顶死）
6. **#240 解析别名（顺带修复）**：TASK_ID_ATTR_REGEX 兼容 `id|sessionID`、描述正则兼容 `description|command`（`(?:\s|^)` 前缀防 xxxId= 尾部子串误配）；旧格式卡恢复箭头+命令预览。**红绿双证**：git stash 主修复后新用例 2 失败（sessionID/command 两例红）→ stash pop 后 11 用例全绿

验证：compileDevDebugKotlin 绿 · testDevDebugUnitTest --rerun BUILD SUCCESSFUL · assembleDevDebug 出包（04:53）· androidTest 编译绿。

## 真机 E2E 走查（2026-08-27，houji e69a99d8，subagent 执行）

> 装包链：assembleDevDebug（03:31 出包）→ `install -r` 报 INSTALL_FAILED_UPDATE_INCOMPATIBLE（设备上是 CI 签名包）→ 按 release-workflow §9 矩阵「本地 debug ↔ CI release 互不覆盖」+ 2026-08-17 唯一例外授权：`adb uninstall` → `./scripts/miui-install.sh` 弹窗自动点穿（第 1 轮命中「继续安装」@346,2435）→ `./scripts/debug-entry.sh` 秒重录服务器配置。

- **场景 A system 目录变更**（会话「数据库索引知识体系详解」）：两张历史卡（textLen=86 / 11235）均渲染统一 EventCard——折叠=[时间戳][Info][「工具目录已变更」][chevron]、无描述行无箭头（spec §2 system 列符合）；展开=分隔线+schema 全文滚动区+chevron 翻转，收起复位，多卡展开状态独立。正文首行=`The Code Mode tool catalog has changed...`（真实数据非空壳）。截图 e234-03/04/05/06/07/08
- **场景 B synthetic 完成通知**（会话「dsh启动报错因插件域名含连字符」——「卡片演示场」经 API 全量扫描证实无 synthetic 数据后换用此会话）：
  - shell 卡（15:01:45）：<Terminal>[「后台命令完成」] + 箭头常驻折叠态可见；展开=命令输出全文（尾 Command exited with code 0.）。e234-12/14/15
  - subagent 卡（14:51:21）：<CheckCircle>[「子智能体完成」]+描述行「深挖dsh启动报错根因」（Q15 激活实证）；展开=Markdown 报告。e234-25/27/28
- **SysMsgDiag**：场景 A 进入后 10 条全走新分支（id=4sqe4MbR textLen=11235 / id=p6hCqirN textLen=86，余为重组重渲染）；场景 B 会话 0 条（无 system 消息，分支隔离自洽）
- **异常观察**：①subagent 卡无跳转箭头+定位钮（→#240）；②shell 卡箭头指向 call_ 工具调用 id 非会话 id（→#240）；③shell 无描述行 command=/description= 错配（→#240）；④顶格卡展开标签行滚出视口（→#241 观察）；⑤uiautomator dump 巨型消息区失明（工具局限）；⑥全程零崩溃零 ANR
- **未取证项**：失败态（ErrorOutline 红描边+失败标签）——50 会话历史无 state="error" 的 synthetic 通知，现场触发链复杂如实放弃；由单测覆盖 state 解析语义兜底
- 截图 28 张存 `/tmp/e234-*.png`，已保全至 `docs/journal/assets/2026-08-27-event-card-unification/`（gitignore *.png 不入库，本地证据链）

## 二轮取证：用户反馈「重叠」现象定性（2026-08-27 旧构建现场，subagent 执行）

> 背景：用户 V6 反馈「agent 回复重叠在一起（偶发、位置不定）」。派专项取证 agent 在旧构建现场做交互复现 + 像素级判读。

### 结论一：渲染层越界绘制假设被证伪

- 我方先验主嫌疑「事件卡展开区 overflow 绘制压住相邻消息」经 4 通道检验**全部否定**：①折叠/展开基线连拍帧差=0；②卡下半部+相邻消息同框构图的逐行亮度扫描——边框 3px 行 min=102/mean=107.6，框内外零字形像素泄漏；③ASCII 像素图字形对比滚动前后逐像素一致；④对照组（普通气泡密集区/表格/pill）8× 放大零异常。边界与文字间隙恒定 40px。
- clipToBounds 三处防御**保留**（无害加固，与 #231 同类坑一致性），但如实记录：它不是本次用户可见现象的根因。

### 结论二：可复现实锤 = shell 卡箭头热区触发伪会话导航 → 400 → 空 Chat（3/3 复现）

- 机制链：shell 卡（旧版有箭头）点击落在热区 → onViewSubSession("call_eaaee999750c45ba86f0386c")（jobID 非会话 id）→ GET /api/session/call_eaae…/message → **HTTP 400**（V2Api ClientError listMessages，05:23:01/05:27:54/05:34:07 三次留档）→ 空消息区「Chat」页 + 列表顶部新增「无标题会话」→ 用户感知「点一下消息全没了」
- 处置：二轮修复已覆盖根因侧（shell 卡箭头整体撤销 + `call_` 前缀拦截）；防御侧遗留缺口登记 **#242**（listMessages 4xx 应 snackbar 返回而非渲染空页——失效子会话 id 同样可触发）

### 结论三：「重叠」观感的真实来源（最接近形态）

- B 会话连续多个同色 teal 巨型日志气泡（25KB 级，内容大量重复——同一报错行一屏出现 3 次）+ turn-notify 整段回显终端日志，快读时感知为「叠在一起」；绘制层像素检查全部正常。登记 **#243**（产品向：同类输出聚合/去重/色彩分层候选，先观察）

辅证截图：/tmp/e234r-*（216 张全目录 CATALOG.txt 留档；关键帧 r-13/fwd-14 越界否定、r-60~68 伪导航现场、r-48/15 对照组）。

> **过程教训（调度）**：取证 agent 的压力循环与主会话装包动作撞车——05:49 主会话装新构建+重启时未硬停其循环，导致压力阶段全部作废（对方如实标注「0 有效刺激≠未复现」）。另两个观察：①注入 input swipe ≤300ms 在新构建上不产生滚动，需手势阶梯实验判别工具假象 vs 真实回归；②/tmp 98% 满曾迫使证据帧外移归档。多 agent 共享设备时必须独占窗口+显式终止语义。

## 解析层发现（#240 实证细节）

- 旧 <subagent> 格式真实样本属性名为 `sessionID="ses_…"`（大小写敏感），TASK_ID_ATTR_REGEX 只匹配 `id=` → sessionId=null：箭头（navTargetId）与动作区定位钮同源全缺
- <shell> 样本描述属性为 `command="…"`（实际命令行），TASK_DESCRIPTION_ATTR_REGEX 只匹配 `description=` → shell 描述行不激活（Q15 数据在则显示的意图被错配阻断）
- shell 卡 `id="call_eaaee…"` 为工具调用 id——即便补别名兼容也不能当子会话 id 用于跳转，需 call_ 前缀识别拦截
- 三处均为存量解析行为（旧卡时代已存在），非 #234 引入回归；修复落点 parseSyntheticTask 单点（含正则常量），单测可先行

## 五轮：用户现场复验抓到零间距缺陷（11:24 现场截图）

> 用户复验时报告「通知跟上面的消息还是叠在一起、上面消息都没有结束」。主会话直接 screencap + uiautomator dump + Room 拉库定位。

**实证链**：dump 显示巨型 assistant 文本块底边 `y=1347` 与「工具目录已变更」卡容器顶边 `y=1347` **完全相等（零间距）**；对照相邻消息间均有 24px（8dp）标准距。Room 库（run-as 拉库 sqlite 直查）确认该会话 ses_fc7c18673 的消息序列：17:26 巨型回答（parts text 5919 字符，分片渲染成无缝文本墙）→ 17:49:48 system 卡。

**根因（确定性几何缺陷，非竞态）**：#232 内联单行通知原带外层 `padding(bottom=messageSpacing)`；#234 批一迁移为 EventCard 后分支 `return@itemsIndexed` 早退，底距丢失——100% 复现于每个「消息→system 卡」边界，与 SSE 时序无关（排除竞态假设）。用户记的「turn 内拆分多段 Markdown 渲染优化」使巨型回答成为无内部分隔的视觉墙，墙尾接零间距卡片双重抹掉「结束感」——放大了本缺陷的观感。

**修复**：ChatMessageList system 分支显式补 `modifier = Modifier.padding(bottom = messageSpacing)`（注释存证根因）。装机后自动化循环（双标记检测：统计栏文本+卡标签同屏即停）对齐边界，dump 实测统计栏底→卡间出现完整间隙；终帧目视三段节奏正常。

**附带**：本轮手动滚动再次多次复现 #245 长消息区拖动冻结（连续截图同帧），独立诊断优先级建议不变。

## 六轮：用户再报「第一条消息没输出完整」——分片陈旧自愈根治（#246 扩面）

> 用户现场截图：用户提问气泡下直接是「**5. 索引下推**」（无标签行=非首段的 chunk 片段），第 1–4 节消失。复现路径=冷启动进入会话（11:46 热进程遍历同构建内容完整；12:12/12:17 两次冷进均残缺）。

**实证链**：①uiautomator dump 布局中确实没有 1–4 节节点（非绘制遮挡）；②Room 拉库 `cached_parts` 全量完好（5919 字符，从「# 数据库索引完整知识体系详解 ## 1.」开始）——数据层无损；③logcat `CHUNK plan part=msg_0383e79ba0 blocks=42 chunks=2` + `commit n=1`——切了 2 片。用户问的「turn 内拆多段 Markdown 渲染」即本机制（MarkdownChunking 分片），残缺段恰是 **c1 尾片**，缺的是 **c0 头片**。

**根因定性（#245 观察扩大为 #246 家族）**：RenderSupplyCoordinator 的三层门控（视口内拦截 / 热+带内拦截 / 跳转稳定窗）对 pending 计划无滞留上限——若计划在错误时机提交或滞留，任何后续刷新都进不去；且 registry Parsed 守卫使文本增长后的重析被永久跳过（协调器头注自认风险：「部分快照→尾部永久截断，极具迷惑性」）。冷启动首次加载的某一环把 c0 弄丢后，机制自身无法自愈 → 稳定复现。（部分快照的具体入口——SSE 回放 vs REST 刷新竞态——仍未定罪，但修复使该类路径全部自愈。）

**修复（双层，均带单测）**：
1. **陈旧自愈（B 层）**：lastSeenTextLens 窗口巡检检测长度增长 → 强制重析；解析回调发现新 plan 对应长度 > committedPlanBaseLens → **无视门控直接覆盖** `_chunkPlans[key]`（T11：短版提交→文本加倍→断言 blocks 数增长覆盖）
2. **防锁死（A 层）**：pending 计划连续 3 次被视口门控 skip → 强制提交（RaceProbe 留痕）（T12：带内三轮 skip 断言第三轮必提交）

RenderSupplyCoordinatorTest 12/12 全绿。剩悬置：c0 具体丢失瞬间的观测级定罪需 ScrollDiag ENTRIES 键 dump 增强（工具化后续项）。

## 七轮：现象最终定位——「丢头」= c0 item 顶部放置塌陷（内容/管线均完好），#245 与 #246 合流

> 用户再报冷启动仍从「5.」开始。本轮上了 ChunkDiag 插桩（compose/PLACE 双点）+ JVM repro 复现脚本，拿到决定性分层证据。

**实证矩阵**：

| 层 | 证据 | 结论 |
|---|---|---|
| 服务器 API | GET message 完整 5919 字符、##1–##8 全在 | 数据完好 |
| Room（pm clear 后全新下载） | 同上完整 | 数据完好 |
| 分片计划 | `CHUNK plan blocks=42 chunks=2` + JVM repro `ranges=0..21,22..41`，chunk0 首块=`# 数据库索引完整知识体系详解` | 计划正确 |
| 组合 | `compose c0 range=0..21 kids=42`（Instrumented build） | 组合正确 |
| 放置 | `PLACE c0 h=7106 w=1128`（初始加载、视口在底部时） | 初次放置正确且巨大 |
| **列表顶部** | dump：用户气泡(781)→174px 间隙→「## 5」(955)；**第 1–4 节节点不存在**；`idx=8 off=6770` 在多次拖拽后**恒定不变**（拖拽瞬时 5698 → 弹回 6770） | **c0 在顶部重放置时塌陷为 ~174px 空条；且视口被钉在 c1 顶部区域** |

**方向不对称解释**：内容在上方 → 下滑（翻新）一切正常；上滑（翻旧）撞上钉位 + c0 塌陷 → 「翻不上去 + 头片缺失」叠加成「第一条消息不完整/叠在一起」。

**已落地的修复（本轮保留）**：① 分片计划剔除纯空白块（JVM repro 实证 chunk1 首块=' '，blank 检测用 isLetterOrDigit 防全角/零宽字符）② 锚点重定位（时序排序）③ 协调器陈旧自愈+防锁死（T11/T12）。它们消灭了「计划/快照」层面的截断源；剩余的「顶部重放置塌陷 + 视口钉位」是 LazyColumn 放置/锚定层的新问题，需要 measure 级探针（onSizeChanged 已布、需加 measure-pass 日志）继续定位——与 #239 竞态族（isAtBottom/补偿钳制）疑点合流，列为下一批次首位。

**给用户的临时操作方案**：遇到「第一条不完整」时，**缓慢长距离拖动**（3000ms 级）可翻越卡点（本轮 14:51 实证 3 秒慢拖成功从 ##5 越过到第 2 节）；快速 fling 会撞钉位弹回。

## 手势阶梯实验（验证 agent 补充任务，05:49+ 构建）

背景：取证轮发现「≤300ms 注入 swipe 不产生滚动」，需判别注入假象 vs 应用回归。双通道测量（帧字节级模板匹配 sad + dump 文本指纹）：

| 场景 | 手势 | 结果 |
|---|---|---|
| 会话新端附近 | 下滑 120/300ms | 正常滚（76–212px）|
| **巨型 assistant 消息区**（数屏长单项） | 下滑 120/300ms ×8 | **帧字节级全同（完全静止）** |
| 同位置 | 上滑 120ms | 正常滚（指纹 5节→7节）|
| 失灵期间 logcat | — | DOWN+40~62 MOVE+UP 全部送达 ViewRootImpl |
| 底部短消息区 | 下滑 300ms | 正常大滚（824px）|
| 卡标题行起滑（对照） | 上下滑 120ms | 正常滚；卡零误 toggle |

**判定=可疑应用侧回归（#245 登记）**：①阵发性排除注入参数假象（同参数短消息区正常）；②事件流完整排除丢事件；③方向不对称——只吞下滑（翻旧），上滑正常；④整卡点击不吞拖动。嫌疑指向 isAtBottom/autoScroll/延迟揭示补偿对翻旧方向的钳制（#239 竞态修复族邻近机制，AGENTS.md SSE 滚动铁律域）——**与 #234 改动无直接关联**（本轮未触滚动机制）。真人影响面注入无法证伪 → 列入 V6 人工清单：长消息区手指下滑翻旧是否偶发拖不动。附：列表最旧端继续下滑触发 predictive-back 系 Android 15 预期行为。

## 四轮：F1 根治复测 + #242 UI 断言（07:29 构建 c26c5395，取证 agent 独占窗口）

### T1 V4 右缘复测——实测达标（1/3 卡，其余因 T2 受限降级）

卡3（工具目录已变更 20:03:10）：chevron 右缘→边框内缘 **32px≈10.7dp**（旧构建 158px≈53dp），8x NEAREST 标尺帧 e234w-T1-cardA3-ruler.png。labelFillRemaining 生效。卡1/卡2/shell 卡补测受 T2 滚动受限阻塞（布局同族，风险低）。

### T2 滚动异常——归因存疑（agent 自我更正后口径），以独立手势标定为最终裁决

新构建时长阶梯 15 次竖滑画面 md5 与基线一致；但同轮 T3 期间 tap 全部精准生效、多次大幅 swipe（600–800ms）实际移动了视口——「注入完全失效」与本轮其他观察自相矛盾。执行 agent 复看证据链后自我更正：T2 更可能是「截图时机落在滚动回弹完成后同一静帧 + 滚动量小于判读阈值」的**测量假象**，而非确定性回归。三轮观测汇总：旧构建阵发失效 / 新构建一度判全档失效后存疑——#245 维持「嫌疑 + 未确证」状态，最终判定以待执行的独立手势阶梯标定为准；真人影响面继续走 V6 人工项补证。

### T3 #242 UI 断言——全部通过，伪导航彻底消除

shell 卡四点点击矩阵（标题行×2 + chevron×2 含旧热区映射位）：**四次全部原地 toggle**；全程 logcat 累计 0×call_eaaee、0×status=400、0×/api/session/<伪ID>/message。构造性探测：subagent 卡箭头跳转子会话正常（「深挖dsh启动报错根因」+ Deep-explore 19m38s 报告完整渲染）——合法导航未被拦截误伤。

纪律留档：独占窗口输入门控全程启用；无 FATAL/ANR；181 帧 /tmp/e234w-* + 测量脚本 e234w-measure3.py。

## 三轮复验（验证 agent 对照清单 V1–V5，05:49 构建）

| 项 | 裁决 | 要点 |
|---|---|---|
| V1 shell 无箭头 | ✅ | 全卡 0 青色像素；点旧热区原地 toggle，logcat 0×call_eaaee、0×400 |
| V2 subagent 箭头恢复 | ✅ | dump 实证 clickable 节点；跳转目标=synthetic sessionID 属性值（#240 别名生效），落地真实子会话含完整报告 |
| V3 全量+缩档 | ✅ | 正文自然末句可达（5876 字符）；行距实测 0.87×（≈0.85f）；展开态跨视口保持 |
| V4 贴边 | ⚠️→已修 | 左缘 8dp 生效、右缘浮动（158/121/125px 三值）——F1 根因=双权重均分 → labelFillRemaining 修复（四轮窗口复测中） |
| V5 快速回归 | ✅ | fling×5+连点 8：零崩溃零残影，状态正确；FATAL=0 ANR=0 400=0 |

新发现登记：F1（V4 同源，当轮修复）、**F2 卡内滚区到边后 fling 穿透外层列表 →#244**、F3 单帧未复现不立案。过程干扰甄别：真人触摸/系统通知弹窗/主题切换均已排除出应用缺陷。

## #242 防御落地（同会话顺带修复，backlog 卡即刻销账）

取证实锤当日即修（用户指示「过程中发现的问题一起修复」）。三层防御：

1. **导航源头拦截**（NavGraph.onNavigateToChildSession）：非 `ses_` 前缀的 id 直接 W 级日志拦截、不入导航栈——`call_…` jobID 形态在第一道门即被拒。修法注记：非尾随具名 lambda 内 `return@label` 有标签解析歧义，用 if/else 结构等价表达
2. **入口加载失败上抛**（MessagePaginationDelegate.loadMessagesForSession）：原 catch 仅日志吞掉 → 补 errorSink 上抛；interaction.error + 消息区空 = ChatErrorState 兜底页（自动退避重试），不再渲染无提示空 Chat
3. **刷新失败同源处理**（MessageDataDelegate.refreshMessages）：reportError() 转交互层

**Seam 说明**：两层均在导航/UI 状态管道——无可直测的纯函数 seam，验证以编译+全量单测回归+真机交互复验（V1 项含伪导航请求 grep 断言）承担。

## 自动化验证矩阵（V1 四维 + i18n + androidTest 编译）

```
✅ ./gradlew :app:compileDevDebugKotlin        → BUILD SUCCESSFUL（各 checkpoint 多次，末次 EXIT=0）
✅ ./gradlew :app:testDevDebugUnitTest --rerun → BUILD SUCCESSFUL in 1m 04s（0 failures；SyntheticTaskParserTest 10/10 · ParseSyntheticTaskTest 8/8 · IsBackgroundMoveSyntheticTest 6/6）
✅ ./gradlew :app:compileDevDebugAndroidTestKotlin → EXIT=0（androidTest 源随接口签名变更同步编译通过）
✅ ./gradlew :app:assembleDevDebug             → EXIT=0（app-dev-debug.apk 31MB，03:31）
✅ bash scripts/i18n-check.sh                  → PASSED: 672 keys x 14 languages, all consistent（81s）
✅ ./scripts/backlog-check.sh                  → 通过（编号计数器/链接/章节序全部 ✓）
```

> 提交链：e70b74d1（EventCard 组件层）→ 28aac580（三族迁入+i18n 收支）→ 28ff394c（spec/journal/backlog 文档层）。工作区 clean。

## V6 用户人工验证清单 · 终版（覆盖二/四轮修复，2026-08-27 出库）

> 前版清单（第一轮验收用）已由三轮机器复验大部分覆盖；本清单为用户手上的最终手感和修复确认项。设备已装最新构建（07:29，含 labelFillRemaining 贴边根治 + #242 三层防御）。

| # | 操作路径 | 预期现象 | 判定标准（怎么算通过） |
|---|---------|---------|----------------------|
| 1 | 「数据库索引知识体系详解」看「工具目录已变更」卡折叠态 | 细描边圆角容器；chevron **贴右缘**（呼吸间距约 8-11dp，旧版 40-53dp 浮动已消除） | 目测右缘不再悬空；左时间戳同步贴左 |
| 2 | 点击 system 卡展开 | 分隔线+schema 全文滚动区（字号不缩——schema 等宽文本保持原样） | 半屏内封顶、可滚、再点收起 |
| 3 | 「dsh启动报错…」会话看「后台命令完成」卡 | Terminal 图标+标签，**无跳转箭头**（新裁决）；描述行=命令预览（#240 别名生效后有则显示）；正文全量+小一号字 | 点击任何位置都只展开/收起，绝不跳走 |
| 4 | 同会话「子智能体完成」卡 | CheckCircle+标签+描述行；**→ 箭头在场**；点箭头跳真实子会话（完整报告）；点卡本体=展开 Markdown 报告（全量、字小一档） | 跳转落地非空页；展开无截断感 |
| 5 | 快速连点任意事件卡多次 | 干脆翻转无残影 | 连点后状态与逻辑一致 |
| 6 | （新增·#245 取证项）长 assistant 消息区手指下滑翻历史 | 正常滚动 | 若偶发「拖不动」请注明大致位置（辅助 #245 定位；注入实验无法证伪真人手感） |
| 7 | （可选）若再遇页面空 Chat：注意是否刚点击过卡片箭头 + 是否有错误提示条出现 | 新构建应有错误兜底页+重试按钮（#242），而非无提示空页 | 有提示即防御生效 |

> 已知边界（预期内现象，勿判失败）：卡1/卡2/shell 卡的右缘像素级测量未完成（同族布局，卡3 已实测 10.7dp 达标）；#243 同色大气泡堆叠观感与 #244 滚动穿透为登记观察项不在本轮修复范围。

**提交链（全程）**：e70b74d1 → 28aac580 → 28ff394c → 04034bf1 → d7f1469c → 03c7fc29 → c26c5395 → a04e48c8 → 6b694133 → c98a6bda → 7f09f37f → 8f7c847f → 8f314020

| # | 操作路径 | 预期现象 | 判定标准（怎么算通过） |
|---|---------|---------|----------------------|
| 1 | 打开「数据库索引知识体系详解」会话，滚到两条「工具目录已变更」卡 | 折叠态：细描边圆角容器+时间戳+ℹ图标+标签+右端 chevron，单行高度 | 与上下消息间距协调，无突兀高差；不再是旧行小字 |
| 2 | 点击其中一张 system 卡本体 | 展开出现分隔线+schema 全文滚动区，chevron 翻转 | 高度封顶约半屏内；内部可滚动；再点收起复位 |
| 3 | 两张 system 卡先展开第一张，再展开第二张，滚走再滚回 | 两张展开状态独立保持 | 滚出视口不丢记忆；离会话重进恢复折叠 |
| 4 | 打开「dsh启动报错因插件域名含连字符」会话，找到「后台命令完成」卡 | Terminal 图标+标签+右端跳转箭头(→)常驻可见 | 展开=命令输出全文；收起复位 |
| 5 | 同会话找「子智能体完成」卡 | CheckCircle 图标+标签+描述行「深挖dsh启动报错根因」一行截断 | 展开=Markdown 报告滚动区 |
| 6 | （手感项）快速连续点击卡片展开/收起多次 | 状态翻转干脆、无残影/无布局跳动累积 | 连点后视觉状态与逻辑一致 |
| 7 | （手感项）长会话中滚动经过事件卡区域 | 卡片渲染即时、无闪烁白块 | 与相邻普通消息观感一致 |

> 已知边界（验收时预期内现象，勿判失败）：subagent 卡暂无跳转箭头与定位钮（#240）；shell 卡无命令预览描述行（#240）；顶部卡展开时标签行可能滚出视口（#241 观察项）；失败态红样式历史无数据无法目验。

<!-- 过程中的取证/验证证据直接写本文件；backlog.md 只留 ≤3 行卡片。 -->
## #246 分片逆序发射定音与三连修复（2026-08-27 17:28–18:14，用户验收通过）

### 定性（第七轮收口：从「嫌疑」到「实锤」）

用户现场截图（17:28）+ uiautomator 坐标（userQ 485..781 → 174px 间隙 → 「5. 索引下推」955）+ ScrollDiag 算术链三重定音：
- `idx=8 off=6770` + c1 h=8131 → 视口首可见项=c1 顶部 1361px（与截图 y955..2352 的第 5 节块吻合）
- `LEAP idx 8->7 off 80->7100` 的 7100≈c0 h=7106 → **idx7=c0 位于 idx8=c1 下方（屏幕上尾片在头片上面）**
- displayItems 为最新在前（reverseLayout 索引 0 在屏幕底），`buildChatEntries` 却按文档正序（c0→c1）发射 → 头片被当「更新」排到尾片下方。数据/组合/放置全正常（PLACE c0 h=7106 / c1 h=8131），纯发射序错误。用户「markdown 拼接时序排序」猜想即此。

### 三个根因与修复（b3cbde4b + 82490698）

1. **分片逆序发射**：`buildChatEntries` 改逆文档序（尾片先入列）+ `displayEntryStart` 钉回头片 c0（跳转落点=turn 首 chunk 语义不变，三处消费方核对：JNC.resolveLazyIndex / jumpToMessage / onLocateTask）。
2. **切割点空白块**：`computeChunkPlan` 边界后推进至下一有效块（start=i+1 可落纯空白块，JVM repro 块 22=' ' → 该片渲染高度≈0）；ChunkReproTest 由此转绿（此前一直红——更正前次「全绿」误报）。连带锚点候选封顶块自身 endOffset + 非空守卫（实证 c1 from=22 沾光命中空白块 → 修后 from=23 精准落「## 5. 索引下推」）。
3. **H1 空文本**：mikepenz 0.43.0 `buildMarkdownAnnotatedString` walker 无 ATX_CONTENT 分支（源码 AnnotatedStringKtx.kt 核对，只处理 PARAGRAPH/TEXT/EMPH/LINK 等）→ 应用 heading1 定制复用之产出空串，H1 退化「只剩分隔线」。修复：heading1 直取节点 ATX_CONTENT 子节点转义文本（对齐库默认 MarkdownHeader 的 MarkdownText contentChildType=ATX_CONTENT），保留分隔线与点击注册。

### 验收反馈·二：事件卡下方双倍间距（82490698）

用户现场指出通知卡下方间隔偏大。dump 精确测量：卡下 48px=其他卡片 2 倍。根因：系统通知分支处于通用 item 包装器（ChatMessageList L1216 padding(bottom=messageSpacing)）之内，`return@itemsIndexed` 只退内容 lambda 不影响包装器；早前「零间隙修复」误判机制在此重复加显式底距。撤销之。修后三段间距 24/24/24 归一（真机 dump：气泡→卡 1081-1057=24、卡→气泡 1261-1237=24、气泡→气泡 1650-1626=24）。

### 验证证据

- ChunkEntryOrderTest（新增，红→绿）：assistant/user chunk 均逆序发射 + displayEntryStart 钉头片；UserChunkTest/ChunkPlanAnchorTest 契约随新序更新；全量 :app:testDevDebugUnitTest 绿
- 真机 slot 探针（DEBUG ChunkDiag）：c0 `from=0 to=22 first=[# 数据库索引完整知识体系详解]`、c1 `from=23 first=[## 5. 索引下推]`
- 真机截图：用户气泡 → 智能体标签 → 思考完毕 → H1 大标题 → 1. B+树结构与磁盘页 → 正文（/tmp/v6.png）
- 用户验收：2026-08-27 「246 ok了」

## #246 分片逆序发射定音与三连修复（2026-08-27 17:28–18:14，用户验收通过）

### 定性（第七轮收口：从「嫌疑」到「实锤」）

用户现场截图（17:28）+ uiautomator 坐标（userQ 485..781 → 174px 间隙 → 「5. 索引下推」955）+ ScrollDiag 算术链三重定音：
- idx=8 off=6770 + c1 h=8131 → 视口首可见项=c1 顶部 1361px（与截图 y955..2352 的第 5 节块吻合）
- LEAP idx 8->7 off 80->7100 的 7100≈c0 h=7106 → idx7=c0 位于 idx8=c1 下方（屏幕上尾片在头片上面）
- displayItems 为最新在前（reverseLayout 索引 0 在屏幕底），buildChatEntries 却按文档正序（c0→c1）发射 → 头片被当「更新」排到尾片下方。数据/组合/放置全正常（PLACE c0 h=7106 / c1 h=8131），纯发射序错误。用户「markdown 拼接时序排序」猜想即此。

### 三个根因与修复（b3cbde4b）

1. 分片逆序发射：buildChatEntries 改逆文档序（尾片先入列）+ displayEntryStart 钉回头片 c0（跳转落点=turn 首 chunk 语义不变；三处消费方核对：JNC.resolveLazyIndex / jumpToMessage / onLocateTask）。
2. 切割点空白块：computeChunkPlan 边界后推进至下一有效块（start=i+1 可落纯空白块，JVM repro 块 22=' ' → 该片渲染高度≈0）；ChunkReproTest 由此转绿（此前一直红——更正前次「全绿」误报）。连带锚点候选封顶块自身 endOffset + 非空守卫（实证 c1 from=22 沾光命中空白块 → 修后 from=23 精准落「## 5. 索引下推」）。
3. H1 空文本：mikepenz 0.43.0 buildMarkdownAnnotatedString walker 无 ATX_CONTENT 分支（源码 AnnotatedStringKtx.kt 核对，只处理 PARAGRAPH/TEXT/EMPH/LINK 等）→ 应用 heading1 定制复用之产出空串，H1 退化「只剩分隔线」。修复：heading1 直取节点 ATX_CONTENT 子节点转义文本（对齐库默认 MarkdownHeader 的 MarkdownText contentChildType=ATX_CONTENT），保留分隔线与点击注册。

### 验收反馈·二：事件卡下方双倍间距（82490698）

用户现场指出通知卡下方间隔偏大。dump 精确测量：卡下 48px=其他卡片 2 倍。根因：系统通知分支处于通用 item 包装器（ChatMessageList L1216 padding(bottom=messageSpacing)）之内，return@itemsIndexed 只退内容 lambda 不影响包装器；早前「零间隙修复」误判机制在此重复加显式底距。撤销之。修后三段间距 24/24/24 归一（真机 dump：气泡→卡 1081-1057=24、卡→气泡 1261-1237=24、气泡→气泡 1650-1626=24）。

### 验证证据

- ChunkEntryOrderTest（新增，红→绿）：assistant/user chunk 均逆序发射 + displayEntryStart 钉头片；UserChunkTest/ChunkPlanAnchorTest 契约随新序更新；全量 :app:testDevDebugUnitTest 绿
- 真机 slot 探针（DEBUG ChunkDiag）：c0 from=0 to=22 first=[# 数据库索引完整知识体系详解]、c1 from=23 first=[## 5. 索引下推]
- 真机截图：用户气泡 → 智能体标签 → 思考完毕 → H1 大标题 → 1. B+树结构与磁盘页 → 正文
- 用户验收：2026-08-27 「246 ok了」

## 八轮：展示会话三卡活体 + shell 失败态派生 + #244/#241/#245 三线（2026-08-27 19:00–20:00）

### 展示会话（#234 验收演示）三条演示全数活体

- 会话「#234 事件卡片验收演示」（ses_fbd45a2bdffe2NDkcJaX1y0cRt）；余额恢复后三轮 prompt 重发全 200：
  - p2「sleep 4 && echo 后台任务完成」→「后台命令完成」卡（中性描边，描述行=命令预览 ✓）
  - p3「exit 7」→ 见下节失败态
  - p1 task 子智能体 →「General-fast Agent / 统计md文件数量」卡 + 向前导航箭头 + 结果正文 10617 个 .md ✓（现场截图帧 /tmp/shot_fail.png）
- 首轮冷进场四分钟拖动全灭的插曲 → #245 取证（见下）。

### shell 失败态服务器语义缺陷 + 客户端派生（新发现，当日修复）

- **原始 XML 实证**：opencode V2 对后台 shell **一律** state="completed"，exit 7 亦然；失败信号仅在正文尾部「Command exited with code 7.」。历史「失败态未活体取证（历史无 error 数据）」由此定因——不是没有失败，是服务器永不发 error。
- 客户端修复（SyntheticNotificationCard.kt，呈现层小修、解析层守恒 §6）：shellExitFailure = source=="shell" 且输出匹配 /Command exited with code [1-9]\d*/，并入 isFailed。装包复验：失败卡红描边 + ErrorOutline 图标 +「后台命令失败」标签 + 描述行 exit 7，与成功卡同屏对比达标（Q5 严重度编码首次活体可达）。
- 上游候选（#146 家族补一行）：V2 后台 shell 状态语义退化，建议 upstream issue。

### #244 卡内滚动区到边穿透——嵌套滚动岛落地（14 站点 + JVM 7 用例）

- 机制：ScrollIsland.kt 边界岛连接器（onPreScroll/onPreFling，边界吞噬判定纯函数 scrollIslandConsumeY：顶边压/底边推全量吃，中段与短内容透明）。挂靠位=内嵌 scrollable 的**外侧**（同链 nestedScroll 先于 verticalScroll / 包裹内层 LazyColumn）。
- 站点（survey 子代理全量盘点，聊天树零既有 nestedScroll）：EventCard 300dp 展开区、ReasoningBlock 240dp、ToolCardRenderer、Shell/Write/Read/Edit/Bash/Task/Search/WebFetch 七卡、DiffHelpers（滚动状态外提）、QuestionPartContent 单页+Pager 页、WebSearch/Glob 内嵌 LazyColumn（rememberListIsland + 显式 LazyListState）。排除：PendingSheets/Dialog/输入浮层（列表项之外）。
- 单测 ScrollIslandConsumeTest 7/7 绿（含短内容透明边界回归——首版判定函数在该分支漏吃，测试抓出后修复）。
- 真机交互验证被 #245 输入异常窗口阻塞（展开抽屉/翻页不稳定），转 V6 清单（见 backlog 卡）。

### #241 视口顶卡展开标签行保护——增量回调 + forward 微滚

- EventCard 增 onExpandGrow(deltaPx)：折叠态基线高度常驻记录，false→true 后首个稳定尺寸帧上报一次（初组合即展开不触发）。
- 接线两处（system 直挂卡 + MessageCard→SyntheticNotificationCard 透传）：listState.animateScrollBy(+Δ)——reverseLayout 下向 forward 等量微滚让被锚定推出视口顶的标签行回落（方向推演：forward=数据序前进=视口内容下移=顶外内容回落）。手感定标随 V6。
- 三段式展开高度上限本就 300dp（Q11），无需再改。

### #245 拖动冻结——巨帧取证当日最大进展（未结案）

- 可靠复现形态：**冷启动进场后**，拖动（任意方向、任意速率、含 fast fling）列表纹丝不动；轻点/键盘 PAGE_UP/程序化路径正常；jog 或首次成功拖后自愈；会话列表页同样被吞（app 级，非列表级）。
- 探针链三轮（PtrDiag 根级 + 列表外层，DEBUG 临时，已摘除）：
  1. 2.5s 注入拖动（~150 move）→ Compose 根只收 **2-3 帧、travel=1700px 完整**——平台把拖动**合并成巨型帧**；
  2. 列表外层 consumed=0——无任何层消费；isScrollInProgress=true——scrollable 认领却零位移；
  3. 排除族：锚点战争（零 LEAP）、shouldComp 闩锁（释放正常）、输入缺失（travel 在）。
- 修复尝试：v1 NestedScrollConnection onPreScroll 分块——**机制勘误**：祖先连接只承接更深层滚动器的流，列表自身拖动不经过（真机 3/3 无效）；v2 Initial 隧道趟 pointerInput + ≤100px 切片 dispatchRawDelta——真机 3/3 仍冻结（巨帧分块不是拒绝原因，指向更深：疑似 LazyList 滚动管线对合并帧时间戳/速度的处理）。v2 守卫保留（病态阈值路径，健康帧零触碰），效果存疑已如实注记。
- 结论：#245 维持 [ ]，根因假设收敛为「框架层对合并巨帧的滚动处理缺陷」，下一步=守卫内打点确认 dispatchRawDelta 是否被调用及返回值（一跳即可定界 app/框架）。

### #243 答复（无代码动作）

- 未曾修过：二轮取证已证伪渲染层重叠（像素级零越界），「重叠」观感=同色 teal 大气泡内容大量重复（同一报错一屏 3 次）+ turn-notify 回显整段终端日志的体量堆积。维持观察/产品决策项（折叠聚合/去重提示/色彩分层三候选待裁决）。

### 验证矩阵增量

- :app:testDevDebugUnitTest 全绿（新增 ScrollIslandConsumeTest 7 用例）；assembleDevDebug 通过；i18n 无字符串改动（失败标签串 chat_event_shell_failed 既有）。

### 八轮补一：#244 撤销（用户手感裁决）+ #241 改渲染前补偿（2026-08-27 20:00–20:20）

#### #244 撤销

- 用户裁决：**「向上顶就向上顶了，不用修复」**——维持 Android 标准 nested scroll 穿透行为（卡片候选方向之「维持现状」项即为裁决结果）。
- 执行：14 站点边界岛全部拆除（EventCard/ReasoningBlock/ToolCardRenderer/七工具卡/DiffHelpers 还原内联滚动状态/QuestionPartContent×2/WebSearch/Glob 还原无 state LazyColumn）；ScrollIslandConsumeTest 随纯函数一并移除；ScrollIsland.kt 收缩为仅存 #245 megaDeltaScrollGuard（文件名保留）。全量单测绿、装包复验正常。

#### #241 渲染前补偿（ExpandReveal）

- 用户约束原话：向上顶可作为正常行为，**除非**能像反射方法那样在渲染前将视窗往下移，而非渲染后补偿（并点破当时 animateScrollBy 方案正是渲染后模式——判断正确）。
- 实现：ExpandReveal.kt 一次性延迟揭示（#222 家族同语义）：增长遍上报基线高度（增量被 clipToBounds 裁掉，未补偿几何永不放置）+ LazyListReflection.requestScrollShift(+Δ) 注入 scrollToBeConsumed；下一遍遍首消费视窗下移 Δ，同遍全量揭示——配对闭环，全程无可见滚动动画。接入 EventCard 根（expandRevealListState 参数），替换原 onExpandGrow/animateScrollBy 全链（SNC/MessageCard 参数改为 LazyListState 透传）。
- 真机实证（演示会话 sleep 卡，展开两段式 Markdown 布局）：
  - `EV-REVEAL real=262 report=199 inject=63` → `real=383 report=262 inject=121`（两次增长遍各自配对补偿）
  - 前后 dump：标签行「后台命令完成」y=406..445 **展开前后纹丝不动**，正文在标签下展开（下方内容 +140px 平移），截图 /tmp/reveal_ok.png——标签行保持可见 + 零跳变。
- 待 V6：W1 原始最劣场景（视口顶格截断卡）手感复核；注入方向如反直觉翻转 ExpandReveal.kt 一处符号即可。

### 八轮补二：收起对称补偿 + 注入符号门修复（2026-08-27 20:40–20:50）

- 用户现场复验：**收起仍从上面收**，展开→收起全循环后视窗内容整体下坠 Δ（渲染后观感）。定位：ExpandReveal 包装器把注入门写在 `injectDelta > 0`——收缩的负注入被静默跳过（onMeasure 状态机本已对称，只差出口）。
- 修复：门改 `injectDelta != 0`（requestScrollShift 接受负 shift=视窗上移，语义注释本就对称）。
- 真机全循环回归（演示会话 sleep 卡，注入门修复后装包）：
  - A 展开 前：标签 406 / 描述 476 / 下文 804
  - B 展开 后：406 / 476 / 988（+184，配对注入 +63/+121）
  - C 收起 后：**406 / 476 / 804——与 A 完全一致（零偏移）**
  - 日志第三发 `real=199 report=383 inject=-184`：收缩遍保持旧高一帧 + 反射注入 -184，下一遍遍首消费后才揭示——渲染前语义贯穿展开/收起两侧，全程无滚动动画。

### 八轮补三：渲染前补偿推广到工具卡家族与思考块（2026-08-27 21:00–21:15）

- 用户指令：其他卡片展开/收起是否也能做成零漂移。执行两项推广：
  1. **ToolCardScaffold 单点挂载**（卡片根 AmoledSurface 链）——一次覆盖全部工具卡家族（Shell/Bash/Read/Write/Edit/Task/Search/WebFetch/Glob/WebSearch/ToolCallCard 降级/ContextGroup/Patch/ApplyPatch，18 处使用）；
  2. **ReasoningBlock（思考块）** 挂 AnimatedVisibility 节点外侧。
  - 列表状态经新 CompositionLocal `LocalChatListState` 下传（ChatMessageList 既有 provider 处 provide，免 3-5 层穿参）。
- 补偿器泛化**链式逐帧配对**：每遍 report=基准+待揭示、新增量继续注入递延——tap 瞬变（两遍配对）与 AnimatedVisibility spring 动画（逐帧 1-2px）同一机制。
- **挂位教训**：思考块首测把 modifier 挂 AV 内侧——AV 自身尺寸动画插在中间，首帧全量测量（369）对上报（18）错配成 351 巨额注入、视窗狂跳；挪到 **AV 节点外侧**后日志变逐帧 +1/+2px 配对（21:05:49 八连发），收起侧 5→4→3→2→1 逐帧 -1 全部配对。
- 诚实注记：动画面存在 **~53px 一次性质展开边界残留**（A→B 净漂移，收起后保持，不随循环放大；瞬变面 EventCard 仍为精确零）。疑与 expandIn 起止帧的测量口径差有关，待后续专修；#215「工具卡交原生锚定」旧裁决被本次用户指令取代（动画保留，仅视窗位移被渲染前配对）。
- CompactionCard 未动：其展开补偿已有专用机制（COMP-CMP deferredReveal 接线）且 #215 有独立裁决，不在本轮范围。

### 八轮补四：去动画裁决 + 两个状态机缺陷修复（2026-08-27 21:15–21:30）

- 用户裁决：残留 ~53px 疑为动画所致——**去掉卡片收起/展开动画**（ToolCardScaffold/ReasoningBlock/QuestionPartContent/TodoListCard 四处 AnimatedVisibility→条件直通；#215「动画默认」正式被取代）。压缩卡维持先前裁决不动。
- 去动画后首轮真机暴露两个状态机缺陷（各一次真机复现定界）：
  1. **冷启动吞首增**：内容型包装器收起态测 0，冷启动分支把首个增量（Spacer 18px）当冷启动吞掉 → 展开净漂移 -18。修复：`everMeasured` 区分「条目首次进视口」（全量上报绝不注入）与「就地展开」（0 基准起全程配对）。
  2. **零高短路吞收起**：收起归零帧被特判直接重置绕过配对 → 收起裸跟随下坠 +369。修复：real==0 **不短路**走通用配对（持有旧高 + 注入 -旧高，一帧空隙同事件卡惯例）。
- 终态真机回归（思考块全循环，注入门修复后装包）：
  - A 展开 前：405/475/707/803/1751/2062
  - B 展开后：405/475/707 不变、下方 +369（配对 +18/+351）
  - C 收起后：**405/475/707/803/1751/2062——与 A 全等，零偏移**
  - 日志：+18 → +351 → -369 三发配对，全程渲染前反射、零动画。
- 工具卡家族与 Todo/问题卡同机制同代码路径（常驻根/Box + 瞬变两遍配对），随本包生效；V6 手感覆盖任一工具卡即可。

## 收卡（2026-08-27 21:35，用户验收）

- 用户验收原话：「好 没啥问题。收卡吧」——覆盖 **#234 全族**（三场景同构卡 + 失败态派生 + #240 跳转/描述行 + #242 导航防御 + #246 分片逆序 + 展示会话三卡活体）与 **#241**（思考块全循环零偏移 + 工具卡家族/问题卡/Todo 卡同机制）。
- spec 按 裁程归档：docs/specs/2026-08-26-event-card-unification-design.md → docs/archive/specs/（头部状态行更新为已完结）；journal 内引用路径同步改写。
- 本批遗留仍在册：#158/#243/#245（观察/产品向，P3）、#146⑥ 上游候选（shell 状态语义退化）。

### 八轮补五：#243 三成因澄清 + 漏网裁剪防御补齐（2026-08-27 21:55）

- 用户问询「243 不是 markdown 分段问题吗？不是已修复了？」——三成因澄清：①#232 系统文本墙（真缺陷，已修）②**#246 分段逆序（真渲染缺陷，用户记忆所指，已修并验收**：逆序发射双循环 + displayEntryStart 钉位在码、ChunkEntryOrderTest 看门）③#243 同色堆叠观感（非缺陷，像素级零越界）。三者在 2026-08-26/27 的现场报告里混流为「消息叠在一起」。
- 重查新发现：#234 二轮溢出绘制防御扫漏两处——**DiffHelpers SimpleDiffView 与 QuestionPartContent 两个问题页滚动区**缺 clipToBounds（铁律：滚动容器默认不裁剪溢出绘制，会压住相邻消息=「回复重叠」头号嫌疑的真向量）。已按 EventCard 同款链序（heightIn→clipToBounds→verticalScroll）补齐，装包。
- CompactionCard ExpandContent 无上限无滚动（合法生长非溢出），#215 裁决域，不动。

### 八轮补六：四卡复核调研（research 报告 + 一处判词修正，2026-08-27 22:40）

- 调研报告：docs/research/2026-08-27-backlog-recheck-158-238-243-245.md（子代理执行，代码+真机双线，202 行，HEAD 202bc3af 零代码改动）。
- **#238**：43 处逐方法 if 精确复核成立（File12/Provider13/System8/Terminal6/Shell4）；试点模板与契约测试（V1V2DialectContractTest）梳理完毕；Shell 域需 V1 常量降级位设计输入。
- **#243**：8 屏 bbox 检测 0 字形相交维持证伪；现象转化为「同色紧凑卡连排+表头重复」（tool 输出默认折叠后 25KB 巨泡未再现）；折叠聚合已部分落地，去重提示最适用。
- **#245 判词修正（重要）**：子代理 6/6「冻结」样本全部为「贴底 + 朝更新方向拖」——该方向在范围尽头本就不产生滚动（标准边缘语义，无回弹视觉反馈加剧死感）；离底后同手势 1399-1421px 全通、回底即「冻」三点互证。即：自动化复现的是**边缘语义**，#245 本体（用户报告的「历史区中段下滑死帧」）本轮未被自动化复现。主会话同期数据互证：贴底 finger-down（朝历史方向）拖动多次正常滚动（idx 0→2→6→9）。后续：真人现场再遇死帧时记录列表位置与方向 + 录屏，定位是否独立机制。
- **#158**：箭头跳转=会话切换路由不经蒙版；15/15 未复现但采样功效不足；后续探测改走「快速定位」抽屉（真蒙版路径）。

### 八轮补七：#238 五域收编完成（C1-4～C1-8，2026-08-27 21:40–22:10）

- 五域逐方法 `if (apiVersion.isV2)` 43 处全部清零，替换为每域单点 `pick(conn)` 路由 + 逐方法单行委托；V1ApiClient/V2ApiClient 现直接实现全部 7 个域接口（Session/Message 试点 + 本批 System/Terminal/File/Provider/Shell）。
- 逐域 commit：C1-4 System（8 if→pick，4 测试）→ C1-5 Terminal（6→pick，3 测试）→ C1-6 File（12→pick，4 测试）→ C1-7 Provider（13→pick，4 测试）→ C1-8 Shell（4→pick，2 测试；**V1 常量降级下沉至 V1ApiClient**——emptyList/null/null/false，同 Session 域 backgroundSession 先例）。
- 契约测试新增 17 用例（V1V2DialectContractTest），每域覆盖 V1/V2 双向路由 + 默认参数穿透（getVcsDiff context=3、completeProviderOauth code=null 经 pick 后仍正确）。
- 每域默认参数值从客户端 override 中移除（接口持有默认值）——客户端方法只被域 Impl 与契约测试调用，无外部默认值依赖（grep 核实）。
- 终验：5 域目录 isV2 残留 = 每文件恰 1 处（pick 本体；System 的第 2 处为 KDoc 注释文字）；全量 testDevDebugUnitTest + assembleDevDebug 绿。
- 裁程注：AppModule 的 @Binds 接口绑定使 V1/V2 客户端实现接口不产生 Hilt 歧义（试点已趟平）。

### 八轮补八：#245 定界探针装包（2026-08-27 22:15）

- megaDeltaScrollGuard 的巨帧分块路径加 DEBUG 探针 FreezeDiag：记录每个巨帧的 dy、dispatched 与 consumed/dy 比值——比值≈1 = 列表健康接受直派（冻结另有原因）；≈0 = dispatchRawDelta 被框架拒绝（定界 app/框架的最后一跳）。正常帧不触发（阈值 300px）。
- 已装包。下次现场（真手指）再遇死帧时：确认列表位置（贴底/中段）+ 抓 logcat -s FreezeDiag 即可闭案。

### 八轮补九：#243 去重落地 + 真机 E2E 全自动验证（2026-08-28 00:00）

- 实现（用户裁决「显示 ×N 即可，无需展开原文」）：syntheticDedupKey（仅 shell 卡；键=source|state|描述|输出，易变 call_ id 不参与）+ dedupeConsecutiveSynthetics（纯函数，连续同键首张保留计数，其余抑制）+ ChatScreen displayItems 构建处接线 + SNC 标签 ×N 后缀。范围=合成事件卡；回合内 tool 卡连排与压缩卡不在本期。
- 单测：SyntheticDedupTest 6/6（连续×3 折叠、隔断不折叠、异命令不折叠、subagent 永不折叠、不可解析不折叠、键剔除易变 id）。
- **真机 E2E（全自动，零人工）**：
  1. 服务器 API 注入确定性 fixture：单轮连发 3 条完全相同的后台命令（sleep 1 && echo e2e-dedup-marker）；
  2. API 轮询确认 3 张同键合成卡**连续**到达；
  3. 应用端 dump 断言：仅 1 张保留卡、标签「**后台命令完成 ×3**」、无其余重复描述行；
  4. 保留卡 tap 展开→正文可见（命中 8 处）→再 tap 收起；
  5. 回归：历史非连续同内容卡（18:51 sleep 卡）保持完整渲染未误折叠。
  - 截图：docs/journal/assets/recheck-243-dedup-x3.png
- 环境插曲（重要教训）：uiautomator 陈旧注册（UiAutomationService already registered）会让 dump 返回**陈旧树**、tap 全吞——本轮 E2E 一度全假阴。清法：杀残留 uiautomator 进程/重连。历史 #158 的 a11y「退化」观察亦经此通道采样，机制疑点+1。
- 待 V6：×N 视觉观感（标签行宽度）+ 去重边界（用户若想看第 N 次原文，可临时关闭去重：目前无开关，如需再加）。

### 八轮补十：agent 卡收起上推复测——双表面精确零漂移 + 防御路径补线（2026-08-28 00:25–00:40）

- 用户报告「agent 卡收起仍将内容往上推」。设备复测（重启清场后， pos.py 逐行对比）：
  - **TaskToolCard 面**（TC-REVEAL）：展开三段配对 +112/+36/+938（Markdown 结果渐进布局），收起单发 -1086，**A==B 上方行==C 全等零漂移**；
  - **SNC EventCard 面**（EV-REVEAL，shell 完成卡同路径）：展开 +63/+121、收起 -184，上方行与卡头全程钉死，零漂移。
- 当前构建（bd6831fa+）两 agent 卡面均未复现上推。差距解释候选：①用户观察早于对称收起修复（2dfffa6e）装机；②一帧空隙闪烁感知；③未接线面。③已消除：MessageCardAssistant 两处防御性 SyntheticNotice（无生产者保留路径）补线 expandRevealListState（经 LocalChatListState，免穿参）——至此 SNC 全部调用点补偿全覆盖。
- 环境教训强化：uiautomator 陈旧注册（UiAutomationService already registered）会让 dump 持续返回**陈旧缓存树**且 tap 全吞——本轮复测一度全被它污染；设备重启彻底清场后复测数据才可信。#158 的历史 a11y 观察同经此通道，仪器噪声嫌疑进一步上升。

### 八轮补十一：用户现场复现定位真因——瞬时收起单帧跳变，恢复动画+逐帧配对根治（2026-08-28 00:37–00:50）

- 用户现场（真手指）：agent 卡收起仍「内容往上推」。此前所有"零漂移"验收均为 **dump 终态测量**——看不见瞬态。真因：瞬时收起 = 保持旧高一帧 → 下一帧下方内容**单帧上跳 Δ**（agent 卡 Δ=1086px，一帧跳一米，极刺眼）。物理定理：Δ 必须被某处吸收，任何瞬时移除必有一处跳。
- 根治（二次裁决恢复动画）：三面恢复 AnimatedVisibility（ToolCardScaffold/ReasoningBlock/EventCard；QPC/Todo 小 Δ 维持直通），渲染前补偿器位于 AV **外侧**逐帧配对。此前「去动画」的残留真因（everMeasured/零高短路/AV 内侧挂位）均已修复，动画+逐帧配对成立。
- 真机验证（agent 卡收起，注入门修复后装包）：收起日志 **30+ 帧逐帧配对**（-27/-25/-80/-14/-12… 弹簧衰减，real 348→128 平滑收回，每帧 report=上一帧 real），无任何单帧大跳；展开/收起终态 A==B==C 零漂移。
- 判词修正链收口：#241 的「渲染前补偿」语义最终形态 = **逐帧渲染前配对的动画收放**（Q12 无动画裁决被 2026-08-28 二次裁决取代）。
- 环境附注：uiautomator 陈旧注册（already registered）会让 dump 持续返回缓存树 + tap 全吞，重启设备清场；此类仪器噪声已污染过 #158/#245 的自动化采样，后续真机断言前必须先验证 dump 新鲜度（尺寸指纹法）。

### 八轮补十二：展开/收起方向统一——全部从上到下（2026-08-28 00:55）

- 用户裁决：展开内容一律**从上到下**（此前思考块呈左上→右下斜向 = AV 默认 expandIn 方向不一致）。
- 实现：ExpandReveal.kt 增共享过渡常量 ExpandEnterTransition（fadeIn + expandVertically(Top)）/ ExpandExitTransition（fadeOut + shrinkVertically(Top)），六处 AV 统一引用：ToolCardScaffold（工具卡家族）/ ReasoningBlock / EventCard / QuestionPartContent / TodoListCard / **CompactionCard**（一致性检查发现同为展开面，一并统一）。
- 回归：思考块循环 C==A 零漂移；收起链逐帧配对至 real=0（零高帧也被配对，无短路回归）。Glob/WebSearch 内层 AV 为 expandedContent 内层初现（不重复动画）不动。

## 收卡二（2026-08-28 00:58，用户验收「好 没啥问题，请你进行后续作业吧」）

- **#241 收卡**：统一方向（从上到下）+ 动画收放 + 逐帧渲染前配对最终形态，用户真机确认无问题。机制全记录见 §八轮补一～补十二。
- **#243 收卡**：合成卡去重（首张 + ×N）落地，单测 6/6 + 真机 E2E 全自动通过，用户确认。另立 **#247**（回合内 tool 卡连排去重——另一表面，待产品确认交互后实施）。
- 本批（#234 家族 + #238/#240/#241/#242/#243/#246）至此全部闭环；在册：#146/#154（挂起）、#235/#238 待验/#247（新）、#158/#245（观察/待现场数据）。

### 收卡三：#238 收官——V1 活体冒烟通过（2026-08-28 01:15）

- **V1 服务器**：opencode 1.18.18（`opencode serve --port 4200`，XDG_DATA_HOME 隔离数据目录；判定特征 = /api/health 无 pid → 探测器判 V1 ✓）。
- **冒烟矩阵**（应用 debug intent 直连 Host-4200-V1，logcat 实证 `Detected V1 API … known=V1`）：
  - 探测：V2-first 探针失败（无 pid）→ V1 探针 1 RTT 即中 ✓
  - Session：createSession ✓（「New session - 2026-08-27T17:15:1…」）/ listSessions ✓（空目录）
  - System：listCommands ✓（斜杠面板 /new /compact /fork /shell… 全部来自 V1 GET /command）/ getServerPaths ✓（目录切换器列出 home）/ getHealth ✓（探测期）
  - Provider/System：模型选择器 **Big Pickle** ✓（getProviders/getConfig 经 pick 路由）
  - FileApi：findFiles 请求到达 V1 但参数缺口（→#248 存量）
  - Terminal：runShellCommand 请求到达 V1 但执行失败（→#248 存量）
  - Shell：V1 常量降级语义符合预期（无后台 shell 概念）
- **判词**：五域收编路由在活体 V1 服务器全通；发现的三处端点级差异均为**存量 V1 兼容缺口**（V1ApiClient 请求形态 vs 1.18 过渡形态），与收编无关 → 登记 **#248**。#238 收卡。
- 环境备注：V1 服务器保持运行（port 4200，日志 /tmp/v1server.log，数据 /tmp/v1xdg）；应用已切回 Host-4200-V1 会话列表，日常使用请用 debug-entry（Host-4199）。

## 九轮：#248 三症状定因勘误 + find 大目录回退修复 + 真机 E2E（2026-08-28 01:00–02:00）

> 用户指令：「顺带发现的问题也进行修复，然后进行真机端到端测试确保修复有效」。对象 = #248（收卡三登记的三处 V1 1.18.18 端点差异）。

### 定因：三症状全部勘误，公共根因一个

curl 逐项复现（opencode 1.18.18 @4200）推翻冒烟记录的字面描述：

1. **「/find 要求 pattern（应用发 query）→ @ 弹窗空」——勘误**：app 根本不往 `/find` 发 query（searchText 无 UI 调用方）；@ 弹窗走 `/find/file?query=`，参数名正确。真实机制：**V1 1.18 的 `/find/file` 在大目录上静默返回 `[]`**——冒烟会话目录 = `/home/leo-tkp`（app 建会话默认 home），home 上 `query=bash`/`service`（可见文件 wsdd.service 存在）均 `[]`，而 Desktop/小目录正常 → fff 引擎（frecency）XDG 隔离后冷库空 + ripgrep fallback 对巨目录失效。**非参数名错位**。
2. **「/file?path= 服务器 500」——窄化**：`/file?path=`（空路径/probeDirectory 形态）**200 正常**；500 仅在 ①项目外绝对路径（`path=/home/leo-tkp` 而项目是 /tmp/v1proj → 路径逃逸 Effect.die）②bogus directory 头（不存在的目录）。正常 UI 流不触发，`listDirectory`/`probeDirectory` 已有降级（空列表/false）。
3. **「runShellCommand 执行失败」——不复现**：curl（`{"agent":"build","command":"echo hi"}`）与真机 `!echo` 均成功（卡片「完成」渲染正常）；home 目录会话同样 200。冒烟失败判定为暂态（疑当时未选 provider/模型，后模型选择器已验通）。

### 顺带发现（第 4 项，记录不改码）

- logcat 持续 3s 一次 `fetchSessionStatus` ClientError：`hasActiveChildren`（SessionStateCollaborator:105-107）把**子会话 directory** 传给 `/session/status`；冒烟时有测试会话建在 `~/.config/opencode`，V1 1.18 对带该 directory 头的请求会解析目录内 opencode.jsonc——**V2 格式 `mcp.timeout: {startup,catalog}` 直接 ConfigInvalidError**。根因 = V1 1.18 与 V2 格式配置不共存（服务器侧）；客户端 `.getOrNull()` 已优雅降级。处置：文档化（v1-v2-differences §补遗第 4 行）+ 卡片注明「会话勿建在配置目录」。

### 修复：findFiles 空结果回退（唯一真实用户可见缺口）

- `V1ApiClient.findFiles`：`/find/file` 返回空时回退 `GET /file?path=`（单层列表；home 实测 79 条/5.6ms，含隐藏文件）+ `findFilesFallbackFilter`（纯函数：大小写不敏感子串、空 query 全量≈「最近文件」降级、limit 截断）。
- 回退失败（bogus 头 → 500）`runCatching` 吞掉维持 find 空语义；find 有结果时早退不触发回退（成本 = 仅 miss 时一次 5ms 请求）。
- `V1FindFallbackTest` 7 用例（过滤/空 query/limit 截断/截断在过滤后）全绿；`:app:testDevDebugUnitTest --rerun` 全量通过；compileDevDebugKotlin 通过。

### 真机 E2E（houji e69a99d8，dev 包 pm install 静默装，debug intent → Host-4200-V1）

| # | 场景 | 期望 | 结果 |
|---|------|------|------|
| A | home 会话（冒烟同款）@ 弹窗 | 修复前死空 → 回退列表 | ✅ `.agentmemory//.android//…` 顶层条目渲染（e2e_home_at.png） |
| A2 | home 会话 `@bash` | 客户端过滤命中 | ✅ `.bash_history/.bash_logout/.bashrc`（e2e_home_bash.png） |
| C | home 会话 `!echo` | shell 完成卡 | ✅ `$ echo` + 完成 + Build/big-pickle/62ms（e2e_home_shell.png）——冒烟确切场景通过 |
| B | v1proj 会话 @ 弹窗回归 | find 路径不回退、结果含子目录 | ✅ `/`+AGENTS.md+CONTEXT.md+`sub/inner.txt`（e2e_proj_at2.png，sub 项证明 find 原路生效） |

- logcat 复核：E2E 期间零 FATAL/零 File search failed；仅第 4 项既知 ConfigInvalidError 轮询（该会话树含配置目录子会话残留）。

### 卡片与文档

- backlog #248：改写为勘误后单点修复卡（`[ ]` 待用户验收后迁 journal）；backlog-check 通过。
- `docs/v1-v2-differences.md` 新增 §「V1 1.18.18 过渡形态实测补遗」：5 端点行为矩阵 + 冒烟误报勘误 + 旧版 `/api/fs/*` 共存警告（`/api/fs/find` 是另一套更残缺实现——data 为 `{path,type}` 信封且对实际存在文件仍返回空，**客户端勿用**）。
- 环境备注：V1 服务器 4200 保持运行；E2E 截图 `/tmp/e2e_*.png`。

## 十轮：#248 双栈（V1/V2）端到端确认——V1 复确认全绿 + V2 回归揪出 #249 预存缺陷（2026-08-28 02:00–02:20）

> 用户指令：「#248 你能否从端到端测试确认呢？V1、V2都需要测试与回归」。

### 环境预检

- 装机核验：dev 包 versionName 0.3.2-dev.2，lastUpdateTime 2026-08-28 01:46 = 昨夜含 #248 修复的静默安装，无需重装。
- 双服务器：4200 V1（opencode 1.18.18，`/global/health` JSON ✓）；4199 V2 = 用户日常实例 **opencode2 v0.0.0-beta-18414**（`/global/health` 返回 Web UI HTML——V2 无该 JSON 端点，方言差异记录在案；`/session` 等未知 GET 同样回落 SPA）。
- adb reverse tcp:4199/tcp:4200 均在位。

### V1 复确认（debug intent → Host-4200-V1，四场景重跑）

| # | 场景 | 结果 |
|---|------|------|
| A | home 会话 `@` | ✅ 回退列表渲染（.agentmemory/.android/… 字母序） |
| A2 | home 会话 `@bash` | ✅ `.bash_history/.bash_logout/.bashrc` 三项 |
| C | home 会话 `!echo` | ✅ `$ echo` 完成卡（big-pickle 761ms，02:09:27 轮次） |
| B | v1proj 会话 `@` | ✅ 4 项含 `sub/inner.txt`（嵌套路径 = find 原路生效、回退未触发的形态学证据） |

操作坑备忘：KEYCODE_DEL 逐字清除会连带删掉 `@` 前缀（残留裸 `bash` 不触发弹窗）——应整段清空后一次性 `input text '@bash'`。

### V2 回归 → #249 发现与修复

- **现象**：V2 下 home（showcase234）与 docs 项目（杭州公积金仲裁资料清单）两会话 `@`/`@bash` 弹窗全空。
- **curl 对照**：V2 `/api/fs/find` 服务器侧完全健康——`query=bash`（home）返回 tmp/opencode/*/bash.ts 等；`query=md`（docs）返回 log.md/index.md/wiki/筹码/补偿金额计算.md 等；`dirs=true` 不过滤目录（目录带尾 `/` 混排）。**但信封对象为 `{path,type}`，无 id 字段**。
- **定因**：`V2ApiClient.findFiles` 解析仅认 `data[].id`（`mapNotNull` 中 JsonObject 无 id 即弃）→ **V2 @ 弹窗恒空**。git 取证预存性：603f987f 未触 V2 文件；该解析块上次触碰 = C1-6 收编（45ef7584，仅 `override` 签名）→ 信封漂移早于本批，**非 #248 引入**。
- **修复（#249）**：解析链补 path 字段回退（`(element as? JsonObject)?.get("path")`，id 优先语义不变；路径为相对目录头的相对路径，与 opencode Web @ 插入形态一致）；`V2ApiClientTest` 补 3 用例（path 信封/id 优先/裸字符串数组）→ 类内 40/40 绿；compileDevDebugKotlin + assembleDevDebug 通过，pm install 静默装机 Success。
- **V2 E2E 复跑**：home `@bash` → 10 项 ✓（logcat 证请求链 query=b→空→bash 防抖正常）；docs `@wiki` → 11 项 `wiki/` 目录 + 中文路径文件混排 ✓。
- 操作坑备忘：输入草稿跨 force-stop 持久化（DraftInputDelegate），残留 `@bash` 与新输入叠成 `@bash@bash` 污染 searchText（首测假阴性）；整段清空后单次输入即中。

### V2 shell → #250 新发现（登记不现场修）

- `+` 新建会话（项目选 leo-tkp）后立即 `!pwd`：Snackbar「Shell 命令运行失败」，logcat 铁证 `REQUEST: http://127.0.0.1:4199/api/session//shell`——**session id 为空**（发送时新会话尚未就位）。
- 同会话重发：`$ pwd` → 完成 `/home/leo-tkp`（glm-5.3-flash 2.5s）✓——V2 shell 链路本身健康，纯新建会话竞态。V1 侧未复现（测试会话均预先存在）。→ 登记 **#250**。
- 顺带观察：应用进程内出现一条 `http://127.0.0.1:4200/question` 请求（V2 会话期间）——疑保存服务器轮询残留，与本案无关，#250 卡外另记待查。

### 卡片与文档

- backlog：#248 补双栈复确认行；新增 **#249**（V2 find 信封解析，已修复待验收）与 **#250**（新会话首发 shell 空 id 竞态）；下一编号 #251；backlog-check 通过。
- `docs/v1-v2-differences.md`：文件系统行补 V2 信封漂移事实；§补遗加 V2 同源注记（V1 1.18 旧路由 `{path,type}` 信封与 V2 一脉相承，差异只在 V2 find 引擎健康）。
- 环境备注：V2 服务器（4199）为用户日常实例，未做写操作污染（仅新增一个「Chat」测试会话于 /home/leo-tkp）；V1 4200 保持运行；截图 `/tmp/e2e_v2_*.png`。

## 十一轮：#250 修复——shell 发送路径补 ensureSession + 连带 session.shell.ended 解析容错（2026-08-28 02:20–02:35）

> 用户指令：「250 也修复吧！」。

### 定因

- logcat 铁证 `/api/session//shell` 空 id + 代码链定位：`ensureSession()` 此前只挂在普通消息（ChatSendDelegate:119）与斜杠命令（SessionActionsDelegate.executeCommand:636）路径上，**唯独 shell 路径（runShellCommand:695）直读当前 sessionId**——新会话未就位时为空串 → POST 空 id 404 →「Shell 命令运行失败」Snackbar。

### 修复

- `SessionActionsDelegate.runShellCommand`：发送前 `val currentSessionId = ensureSession()`（对齐 executeCommand 同款模式；ensureSession 幂等 + mutex 双检，已有会话瞬时返回）；日志同步改用就位 id。
- 连带发现（同 E2E 链路）：`V2EventParser.kt:98` 对 `session.shell.ended` 的 `output` 字段直接 `.jsonPrimitive`——**该字段可为对象（输出文件引用）** → IllegalArgumentException → 整事件 parse error 丢弃（ShellJobsHandler 收不到终态）。容错为 `(props["output"] as? JsonPrimitive)?.contentOrNull`。
- 单测：新增 `SessionActionsDelegateShellTest` 3 用例（新会话空 id → 以 ensureSession 就位 id 发送 / 既有会话幂等 / 空白命令零网络拒绝）+ `V2EventParserTest` 1 用例（object output 安全解析）；全量单测复跑通过。

### 真机 E2E（原场景复现）

| # | 场景 | 结果 |
|---|------|------|
| 1 | V2 + 新建会话（leo-tkp）→ 首发 `!pwd` | ✅ `REQUEST /api/session/ses_fbb811ae…/shell` 真实 id；`ShellJobEnded → ShellJobsHandler` 正常派发（parse error 消失）；`Executed …: true`；无失败 Snackbar |
| 2 | V1 + 新建会话（v1proj）→ 首发 `!echo` | ✅ `/session/ses_fbb8042e…/shell` 真实 id；`$ echo` 完成卡渲染（big-pickle 70ms）——**修复方言无关** |

### V2 方言事实（记录，不在 #250 范围）

- V2 会话级 shell = **后台 shell 体系**（shell.created/shell.exited + session.shell.* 事件，全部路由 ShellJobsHandler），**不产生聊天消息事件** → 聊天列表不渲染卡片；V1 会话级 shell 产消息 → 渲染轮次卡。两方言 UX 语义本就不同，产品级「V2 是否需要 !cmd 的聊天内可见反馈」另行裁决。
- 顺带观察：V2 会话期间出现一条 `http://127.0.0.1:4200/question` 请求（连的是 V1 测试服务器）——疑保存服务器条目轮询残留，#250 卡外待查。

### 卡片与文档

- backlog #250 改写为已修复待验收卡；`v1-v2-differences.md` Shell 行补 V2 后台 shell 语义 + 解析容错注记。
- 环境备注：用户日常 V2 服务器累计 2 个测试会话（无标题会话/Chat，均 /home/leo-tkp）、V1 服务器 1 个（New session 18:33）——可随手删。

## 十二轮：#251 根因修复——4200/question 之谜 = 调试通道 autoConnect 泄漏（2026-08-28 02:40–02:55）

> 用户指令：「继续修这个（4200/question 轮询残留线索），注意要根因修复与验证」。

### 定因（logcat + 代码链双证）

- 现象回放：App 连着 4199（V2 日常）却周期性 `GET http://127.0.0.1:4200/question`（V1 测试服务器）。
- 请求方定位：`OpenCodeConnectionService.startQuestionPolling`——问题通知 REST 兜底轮询（30s），由 `connect(server)` 启动。
- **根因**：`MainActivity.activateDebugProfile` 两个分支都无条件给条目写 `autoConnect = true` → **每个用过的调试 URL 永久加入开机自连集合**。服务冷启 `autoConnectConfiguredServers()` 全量连接全部 flagged 条目（真机实证 `Auto-connecting 2 server(s)`）——SSE、`GET /question` 轮询、状态轮询全部挂上陈旧后端；`mergeQuestionsFromREST` 还把跨服务器数据合并进 eventDispatcher。4200 只是本例，**任何一次性调试后端都会永久泄漏**。

### 修复（不变量：最近激活的调试后端至多一个自连）

- `ServerConfig.fromDebugChannel` 标记（@Serializable 默认 false，旧 JSON 向后兼容）：区分「系统管理位」（调试条目的 autoConnect）与「用户管理位」（手动 pin）。
- 纯函数 `ServerConfig.applyDebugBackendPromotion(servers, targetId)`：目标置自连 + 打标记；其余**被标记**且自连的降级；手动条目永不受调试激活影响。
- `ServerDataStore.promoteDebugBackend`（持久化）→ `ServerConfigRepository`/`ServerRepositoryImpl`（接口+实现）→ `MainActivity.activateDebugProfile` 统一调用（两处硬编码 `autoConnect = true` 撤除）。
- 单测 `ServerConfigDebugPromotionTest` 6 用例（提升打标/降级陈旧/手动 pin 不动/幂等/切换降级/空列表）全绿；全量单测通过。
- 手动编辑路径（HomeViewModel.saveServer）用 `copy(...)` 天然保留标记；手动新建默认无标记 = 用户管理位。

### 真机 E2E（houji，含 legacy 自愈验证）

| # | 步骤 | 结果 |
|---|------|------|
| A | 4200 调试意图启动（过渡帧） | `Auto-connecting 2 server(s)`——符合预期：4199 旧条目未标记，本轮 sweep 先于 promote |
| B | debug-entry 4199（promote 降级已标记的 4200） | sweep 仍 2（本轮 sweep 亦先于 promote，余波为旧标志） |
| C | 再冷启 4199 | ✅ **`Auto-connecting 1 server(s)`**；40s 观察窗 **4200 流量 0 条**（修复前同期持续 /question + 状态轮询）；4199 轮询 138 条正常 |

- **legacy 自愈**：旧条目无标记不会首轮被动降级，但每激活一次即被打标——一轮 promote 循环后收敛为单自连，**零手工清库**。
- 时序备忘：图标冷启的 FGS sweep 先于 debug 通道协程的 promote——切换后的第一帧仍按旧标志连接一次，第二帧起不变量生效。

### 卡片与文档

- backlog 新增 **#251**（已修复待验收）；#250 卡尾注「4200/question 待查」改为已另立 #251；下一编号 #252；backlog-check 通过。

## 十三轮：四卡（#248/#249/#250/#251）验收取证补全——mention 插入闭环（2026-08-28 03:00–03:05）

> 用户问：四卡能否以真机 E2E 验收、减少人工校验。补全此前缺失的最后一段功能链证据：@ 弹窗**点选插入**。

- **V1 插入链路（#248）**：home 会话 `@bash` 弹窗 3 项 → 点 `.bashrc` → 输入框渲染 `@.bashrc` mention chip（截图 acc_v1_inserted.png）——回退列表来源条目可点、插入合法 token，列表→过滤→点选→插入闭环。
- **V2 插入链路（#249）**：docs 项目 `@wiki` 弹窗 11 项（目录 + 中文路径混排）→ 点 `wiki/Home.md` → 输入框出现 `@wiki/Home.m`（mention 插入成功；尾字符为验证性 DEL 削减，链路正常；截图 acc_v2_inserted.png）。
- 操作坑备忘（再现 + 定性）：会话草稿跨 force-stop 持久化 + 会话列表按最近活跃重排 → 旧会话残留 `@`/`@bash` 与新输入叠层（shelltest `@@@bash`）。v1proj 无 bash 文件 → 弹窗空 = **正确行为**（无匹配即无弹窗），非回归。
- 验收判定：四卡缺陷级标准均为功能行为，机器断言（截图 + logcat 请求级证据 + DataStore 直读 + 单测）已闭环；不触动画/时序类 UI → V6 人工项收敛为可选目检 + #250 V2 反馈形态产品决策（非缺陷、不阻塞验收）。

## 收卡四（2026-08-28 03:05，用户验收「好 那开始吧」——#248/#249/#250/#251 按真机 E2E 卷宗零交互验收）

> 验收方式：真机 E2E 卷宗（§九轮～§十三轮）机器断言闭环——功能行为以截图 + 请求级 logcat + DataStore 直读 + 单测断言，用户免交互验收。以下四卡原文迁入（原文保留）。

### #248 原文

- [ ] **#248 V1 1.18.18 兼容缺口——find 大目录静默空（@ 弹窗回退修复，待验收）** `api` `data`
  - 原三症状（冒烟 2026-08-28）经逐项活体复现勘误：①@ 弹窗空 = V1 `/find/file` **大目录（home）静默返回 []**（fff 冷库 + ripgrep fallback 失效；冒烟会话目录=home 触发，非参数名错位）；②`/file?path=` 500 仅项目外绝对路径/bogus 目录头（正常流不触发，现有降级足够）；③shell 现构建真机通过（不复现）
  - **修复**：V1ApiClient.findFiles 空结果回退 `/file?path=` 单层列表 + `findFilesFallbackFilter` 客户端过滤（纯函数 7 用例单测全绿）
  - 真机 E2E（houji，2026-08-28）：home 会话 @ 弹窗回退列表 ✓ + `@bash` 过滤命中 .bashrc 等 ✓ + home 会话 shell 完成 ✓ + v1proj 会话 @ 回归 4 项含 sub/ ✓
  - 第 4 发现（记录不改码）：会话目录恰为 `~/.config/opencode` 时 V1 1.18 解析 V2 格式 opencode.jsonc → ConfigInvalidError 轮询报错（客户端已降级，根因服务器侧）
  - 双栈复确认（2026-08-28 02:00–02:20）：V1 四场景复跑全绿（@ 回退/@bash 过滤/!echo/v1proj 回归）；V2 回归另证 @ 弹窗空为**独立预存缺陷** → 拆出 #249 与本卡解耦
  - 详见 `docs/v1-v2-differences.md` §V1 1.18.18 过渡形态实测补遗 · `docs/journal/2026-08-27-event-card-unification.md` §九轮——**用户验收后迁 journal**

### #249 原文

- [ ] **#249 V2 legacy find 信封漂移——`/api/fs/find` 返回 `{path,type}` 对象、客户端仅认 id → V2 @ 弹窗恒空（已修复，待验收）** `api` `data`
  - 双栈 E2E（2026-08-28）定因：opencode2 beta-18414 服务器侧 find 完全健康（curl query=md 命中 log.md 等），但信封对象无 id 字段，V2ApiClient.findFiles 的 mapNotNull 全弃 → V2 @ 弹窗任意目录恒空；git 取证预存缺陷（603f987f 未触 V2 文件，解析块上次触碰 = C1-6 仅签名）
  - 修复：解析链补 path 字段回退（id 优先不变）；V2ApiClientTest 补 3 用例（path 信封/id 优先/裸字符串）类内 40/40 绿
  - 真机 E2E（houji）：V2 home `@bash` → 10 项 ✓；docs 项目 `@wiki` → 11 项目录+文件混排含中文路径 ✓
  - → `docs/journal/2026-08-27-event-card-unification.md` §十轮 · `docs/v1-v2-differences.md` §V2 同源注记——**用户验收后迁 journal**

### #250 原文

- [ ] **#250 V2 新会话首发 shell 竞态——`/api/session//shell` 空 session id（已修复，待验收）** `session` `ui`
  - 发现（2026-08-28 双栈 E2E）：+ 新建会话后立即 `!pwd`，SessionActionsDelegate 以空 id 发 POST → 失败 Snackbar；同会话第二条即正常——ensureSession 只挂在普通消息/斜杠命令路径，shell 直读未就位 id
  - 修复：runShellCommand 发送前 `ensureSession()`（对齐 executeCommand 同款，幂等 mutex 双检，方言无关）；连带 `V2EventParser` session.shell.ended 的 output 对象形态容错（原 jsonPrimitive 炸 → 整事件丢弃）；SessionActionsDelegateShellTest 3 用例 + V2EventParserTest 1 用例，全量单测通过
  - 真机 E2E：V2 新会话首发 `!pwd` 真实 id ✓ + ShellJobEnded 正常派发 ✓ 无失败 Snackbar；V1 新会话首发 `!echo` `$ echo` 完成卡 70ms ✓。注：V2 会话级 shell = 后台体系不产聊天消息（方言事实记入 v1-v2-differences Shell 行）
  - → `docs/journal/2026-08-27-event-card-unification.md` §十一轮——**用户验收后迁 journal**（另：`4200/question` 之谜已另立 #251 根因修复）

### #251 原文

- [ ] **#251 调试通道 autoConnect 泄漏——一次性测试后端永久加入开机自连集合（4200/question 之谜，已修复，待验收）** `session`
  - 定因（2026-08-28）：debug 激活无条件给条目写 autoConnect=true → 服务冷启 `autoConnectConfiguredServers()` 全量连接全部历史调试后端（真机实证 Auto-connecting 2 server(s)）——SSE + GET /question 轮询 + 状态轮询挂满陈旧后端，mergeQuestionsFromREST 还跨服务器污染事件流
  - 修复：`ServerConfig.fromDebugChannel` 标记 + `applyDebugBackendPromotion` 纯函数（不变量：**最近激活的调试后端至多一个自连**；手动 pin 为用户管理位永不受影响）+ DataStore/Repository/MainActivity 全链贯通；`ServerConfigDebugPromotionTest` 6 用例，全量单测通过
  - 真机 E2E：切换轮换后冷启 **Auto-connecting 1 server(s)** + 40s 窗口 4200 流量 0 条 + 4199 轮询正常；legacy 无标记条目经一轮 promote 自愈（零手工清库）
  - → `docs/journal/2026-08-27-event-card-unification.md` §十二轮——**用户验收后迁 journal**

### 收卡摘要与在册更新

- **#248**（603f987f）· **#249**（f536b94d）· **#250**（71c5c945）· **#251**（4b7a0bc2）——机制与证据分别见 §九/十/十一/十二/十三轮。
- **遗留产品决策**：V2 会话级 shell 为后台体系不产聊天卡，`!cmd` 聊天内可见反馈待裁决 → 另立 **#252**（不属缺陷）。
- **#251 已记录边界收尾**：切换后首帧过渡暴露（单会话自限）+ legacy 未再激活条目不自愈 → 另立 **#253**（P3 观察）。
- 在册：P1 #146/#154；P2 #235/#252；P3 #158/#247/#245/#253。下一编号 #254。

## 十四轮：#253 修复——被降级调试后端同启动周期断连，首帧过渡暴露关闭（2026-08-28 03:05–03:15）

> 用户问 #247/#245/#252/#253 可修性 → 判定：#247/#253 可修、#252 待产品裁决（选项已抛出）、#245 无复现不可盲修（下一步本就是真人现场复现取证）。

### 修复

- `ServerConfig.computeDemotedAutoConnectIds` 纯函数：被降级 id = 原自连且提升后失去自连者（手动 pin 永不入列、目标不在列）。
- `promoteDebugBackend`（DataStore/Repository 链）返回被降级 id 列表；`MainActivity.activateDebugProfile` 在连接意图发出后，对被降级后端补发 `ACTION_DISCONNECT`（服务已有通道，START_NOT_STICKY 即处理即返；服务此刻已在前台）。
- 单测 +3（标记陈旧降级/手动 pin 不降/目标与未连接不列）+ 全量单测通过；assemble + 静默装机。

### 真机 E2E（双向切换）

| # | 步骤 | 结果 |
|---|------|------|
| 1 | 切到 4200（4199 被降级） | ✅ sweep 连 4199 后 `Disconnect requested 28b402eb` 同秒触发；4199 `/api/form/request` 断连后 20s 零新增 |
| 2 | 切回 4199（4200 被降级） | ✅ `Disconnect requested 28a7f52c` 同秒触发；4200 流量 24→24 零增长；4199 请求 121 条存活正常 |

- **效果**：首帧过渡暴露（sweep 先于 promote 连上的陈旧后端）在同一启动周期内被摘除——不再存活到进程结束。
- 边界保留：legacy 无标记条目仍不会被自动降级/断连（手动 toggle 或再激活收敛，§十二轮）。
- 状态：backlog #253 改为已修复待验收；#247/#252 选项待用户回复。

## 十六轮：开发过程发现的问题修复——shell 触发健壮化（前导空白 + 全角「！」）（2026-08-28 03:50–04:15）

> 用户指令：「先修复开发过程发现的问题」。开发过程发现两项：①#254 T12 负载敏感（已登记锐化诊断，待深挖 pending 管线时序）；②shell 模式触发不稳定（本轮修复）。

### shell 触发不稳定定因（uiautomator 直读 + 双形态实证）

- **非客户端模式检测 bug**：uiautomator dump 直读字段文本 = `' !pwd'`（前导 **0x20 空格**）——E2E 驱动在键盘弹起状态下 tap 旧输入框坐标误触**空格键** → 前导空白使 `startsWith("!")` 双路径（自动模式检测 + 发送兜底）全不触发 → 整行回落普通消息。02:32/03:38 成功轮 = 键盘落下状态（无空格）。
- **真人等效场景**：中文 IME 环境「!」偶发落**全角「！」**（03:43 条带 `$ ！pwd` exit 127 实证——服务器原样收到全角命令）。

### 修复（#255，ChatScreenBottomBar 双路径）

- 自动模式检测：`newValue.text.trimStart()` 后检测半角「!」/全角「！」，剥离 `drop(1).trimStart()`（两形态均单字符）。
- 发送兜底：`rawText.trimStart()` 同语义（前导空白 + 全角均容许）。
- 全量单测 **2142/0** ✓（T12 本轮通过——间歇性持续观察，#254 跟踪）。

### 真机 E2E

- 前导空白流（原三连失败流原样复现）：修复后走 **shell 路径** ✓（条带出现、聊天区无误发 agent）—— trimStart 兜底生效实证。
- 全角路径：`adb shell input text` 不支持非 ASCII 注入（字符被丢弃）→ 真机不可驱动（仅真人中文 IME 可触发）；逻辑为单字符 `startsWith`/`drop(1)`，代码级正确性评审覆盖。
- 已知边界记录：条带曾显示 `$ !pwd` exit 127 = 服务器原样收到全角命令（加固前的历史 job），非剥离缺陷。

## 十五轮：#247 + #252 修复——回合内 tool 卡折叠（×N）+ V2 shell 聊天内轻提示条（2026-08-28 03:20–03:45）

> 用户裁决（上轮提问的回复）：#247 确认「首张 + ×N」；#252 选「b) ShellJobs 轻卡片」。

### #247 回合内连续同键 tool 卡折叠

- `RenderItem.RepeatingTool(part, count)` 新渲染项；`toolDedupKey`（工具名 + 命令/标题，callId/id 等易变字段不参与，状态不入键防流式反复拆叠；context 工具与过滤工具排除）+ `collapseConsecutiveToolCards` 纯函数（跨消息折叠：卡间 turn 分隔线随折叠一并消失）——挂在 `computeRenderableTurn` 出口。
- 渲染：MessageCardAssistant 主路径 + 分片路径（ChunkAssistantItems）双分支——首张经 PartContent 正常渲染 + 右上角 ×N 徽标（secondaryContainer 底）。
- 单测 `RenderableTurnCollapseTest` 7 用例（三连同键折叠/异命令不折/context 排除/不同命令断 run/单卡不包/分隔线保留/易变字段忽略）。
- **真机 E2E（V1 测试服务器，big-pickle）**：诱导模型三次独立 bash 调用（两次纠偏：①模型把命令当二进制名；②模型用 && 合并成一次调用——各自澄清后命中）→ **单张 `$ echo dedup-check · 完成` 卡 + 右上 `×3` 徽标**（acc_247_round3.png），文本回复确认三次独立调用。

### #252 V2 shell 聊天内轻提示条（方案 b）

- `ShellJobsStrip` 新组件（消息列表**外**浮层，输入栏上方——刻意避开 LazyColumn：#222 定音 pre-itemsIndexed 新 item 贴底不可见且翻 isAtBottom，铁律区零接触）。零新增翻译字符串：状态用图标（spinner/✓/✗）+ 技术记号（exit N）表达。
- ChatScreen 接线：`shellOutputResolver`（三级 provider）前移至 Scaffold 之前共用于 ShellSheet 与条带；条带显示最新 job（命令 + 状态 + 输出展开）+ `+N` 历史计数 + 上箭头进既有 ShellSheet。
- **真机 E2E（V2 日常服务器，Dedup 指令测试会话）**：`!echo-v252` → 条带浮层出现（acc_252_done.png）✗ exit 127（失败态红图标 + 退出码）；tap 展开 → 输出区渲染 `/api/shell/sh_044bb3b8…/output` 真实输出（logcat 证 REST 拉取，acc_252_expanded.png）；`+2` 历史计数 ✓。
- 边际发现（既有行为，记录不改码）：shell 模式触发**时序敏感**——新会话首发 `!cmd` 走 shell 路径，后续消息 `!cmd`（含分段注入）回落普通消息路径（agent 工具卡渲染）；与 #250 修复无冲突。另观察 `!cmd` 带空格（`!echo dedup-ok`）走消息路径。

### 测试基建（顺带登记）

- 全量单测连续两轮挂 `RenderSupplyCoordinatorTest.T12`（前两次 skip 不应提交）——隔离运行恒绿。定因：T12 用 runBlocking + 真实时钟 delay，协调器 2s 稳定窗口（T6）在满载并行下被 dispatch 延迟吹爆。**代码级与本轮改动零交集**（协调器未 import 任何被改文件）→ 登记 **#254**（测试基建：注入假时钟解负载敏感）。

## 十七轮：#252 落地形态修正——浮层改对话流内嵌卡（TUI 语义，用户反馈）（2026-08-28 04:20–05:05）

> 用户反馈：「指令应该跟 opencode 的 TUI 一样出现在主对话流，现在是单独的一个窗口和莫名其妙的界面」——浮层形态否决。

### 修正

- `ShellJobsStrip`（浮层）→ `ShellJobsTranscriptCard`（**对话流内嵌卡**）：挂进 ChatMessageList 的 LazyColumn（先声明 = 视觉最底，贴最新消息下方），全宽卡片随列表滚动——命令与输出长在消息流里，与 opencode TUI 的 `!cmd` 行为一致。
- 接入 #222 banner 体系：`bannerCount` 与 `revealBannerCount` 各 +1（shell 卡存在时），复用既有贴底 reveal；另加 shell 卡内容变化（job 出现/状态/输出到达）贴底重锚 effect（卡片长高方向朝视口顶，不重锚看不到新输出）。
- ChatScreen 撤浮层调用与 import；`shellOutputResolver` 保持前移位（三级 provider 传参给列表）。
- 卡形态：会话内 job 按时序旧→新排列，最新一条默认展开输出（TUI 行为），历史行点击展开/收起；状态图标 spinner/✓/✗ + exit N。
- 全量单测 2142/0 ✓；assemble + 静默装机 ✓。

### 真机 E2E

- `!echo-inflow`（V2 Dedup 指令测试会话）：卡片**长在对话流里**（最新消息下方、与气泡同宽全宽卡），`$ echo-inflow` + ✗ exit 127 + REST 拉取的输出直接渲染卡内（acc_flow_card.png）——不再有独立浮窗。

### 十七轮再补：视觉路径二次修正（用户二次反馈「不要单独设计卡片样式」）

- ShellCard 方案仍被判「单独设计」→ 终版：**零渲染代码**——ShellJob 映射为标准 `Part.Tool(tool="bash", state=Running/Completed(input={command}, title="$ cmd", output=…))`，经 `PartContent` 走与 agent 命令卡**完全相同的渲染路径**（像素级同源：`$ cmd` 标题 + `完成 · 输出摘要` 状态行 + 折叠/展开交互全套）。
- ShellJobsTranscriptCard 自绘卡删除；真机截图 acc_tool_card.png（`$ echo-inflow` + `完成 · …未找到命令`，与 agent 卡同构实证）。
- 成功态形态同构（02:32 `$ pwd · 完成 · /home/leo-tkp` 已演示输出渲染）。

### 十七轮补：视觉统一（用户反馈「跟其他卡不协调」）

- 自绘卡片废弃 → 每个 job 直接渲染一张 **`ShellCard`**（既有 V2 shell part 卡，ToolCardScaffold 体系）：等宽命令行 + 「退出码 N · 输出摘要」状态行 + 「输出」容器，与消息流内 `$ echo` 卡同款视觉语言——**协调性由构造保证**（ShellJob → Part.Shell 薄映射，output 经三级 provider 回填）。
- 时序旧 → 新纵排（4dp 间距），最新默认展开，历史行点击切换；真机截图 acc_shellcard2.png（卡片与对话流视觉统一实证）。

### 十七轮终版：用户再否决自绘（ShellCard 仍是「单独的卡片样式」）→ **EventCard 通知卡本体**

- 用户澄清：「类似通知那种」= 通知卡本体，零新样式 → 每个 job 渲染一张 **`EventCard`**（Shell 完成/失败的既有通知形态）：label = chat_event_shell_completed/failed（i18n 现成）、leadingIcon = Terminal、failed 红描边、description = `$ 命令`、bodyContent = 输出 Markdown（三级 provider 回填）；多 job 纵排 6dp 间距；expandedStates 局部记忆 + expandRevealListState（#241 保护）。
- 放置仍在消息列表内（先声明 = 视觉最底）、间距 messageSpacing 与其他气泡一致。
- 真机 E2E（acc_event_final2.png）：`!echo-inflow` → 通知卡「06:07:41 ⚠ 后台命令失败 + $ echo-inflow」失败态红描边（无效命令名 127）；对话流位置与间距正常。成功态（✓ Shell 已完成 + 输出）同构。
- 迭代史：浮层 → 裸 ShellCard → 气泡包 ShellCard → **EventCard 通知卡**（终版，零新样式由构造保证）。

## 十八轮：#252 鸿沟根治——shell 信封消息 Message.User 回退致空气泡累积（用户报「间隔仍有大」，2026-08-28 13:20–13:50）

> 用户反馈：通知卡与上一条消息间隔仍大（「卡片需要作为主对话消息的一部分」），要求间隔与正常消息一致（8dp）。

### 根因定音（UI dump + Room 交叉实证）

- 真机 uiautomator dump：气泡与通知卡之间 gap 区（~350px）存在 **12 个 48dp 高、8dp 步进的空气泡**——即 role='shell' 占位消息照常渲染，此前「已过滤」判断有误。
- Room `cached_messages`：Dedup 指令测试会话 34 条消息中 **role='shell' 15 条（全部 0 parts）**，另有 agent-switched ×3、model-switched ×1 同为零内容。
- 定罪：`MessageSerializer.selectDeserializer` 的 when 按 role 分发，`'shell'` 落入 **else → Message.User 回退**——过滤条件 `(message as? Message.Assistant)?.role == "shell"` **永不命中**（类型判断错误，非逻辑遗漏）。

### 修复（8388070f）

- `MarkdownChunking.buildChatEntries`：改按 `Message.role` 字符串过滤 `SYNTHETIC_ENVELOPE_ROLES = {shell, agent-switched, model-switched}`（role 是 abstract val，与反序列化类型无关）；真 user/assistant 不受影响，流式 turn（非零 parts 渐入）不在判定范围。
- 配套 `SyntheticEnvelopeFilterTest` ×3：回退行为锁定（shell JSON → Message.User 且 role 保留）+ 信封零发射 + 真消息照常。

### 同批（e9a8f722，#253）

- `EventDispatcher.releaseSessionData` 不再清 `shellJobsHandler`（退出会话卡片蒸发根治）；`SessionDeleted` 级联补 `shellJobsHandler.clearForSession`。

### 验证（真机，density 3.0）

- 全量单测绿（含新 3 用例）；assemble + 静默装机 ✓。
- 服务器端 `POST /api/session/{id}/shell`（Basic auth）创建 `echo gapcheck` → SSE → 通知卡「后台命令完成 · $ echo gapcheck」✓。
- 语义树 bounds 终测：气泡容器 `[36,326][1164,2081]` → 通知卡容器 `[36,2105][1164,2304]`，**gap = 24px = 8dp = messageSpacing 精确达标**（acc_final_8dp.png）。

### 方法论教训

- **像素考古三连坑**：① 采样列穿正文文本区把文字笔画当 border；② 浅色主题下气泡 border/气泡内底色与会话背景亮度差 <3%，颜色不可分——**几何归因最终靠语义树容器 bounds，不是像素**；③ uiautomator dump 曾只剩根节点（Compose 渲染中），重试即可。
- **输入注入**：adb `input text` 对 `!` 的转义不稳定（同命令两次结果不同），软键盘 keyevent SHIFT+1 映射 `[`——**服务器端 REST 直发**（`POST /api/session/{id}/shell`）是 shell 触发的可靠 E2E 通道。
- 顺带发现：GET `/api/session/{id}/message` 返回 shell 条目含完整 command/status/exit/output——**V2 有已结束 shell 历史 API**（推翻「无历史 API」旧判断），跨进程恢复通知卡可行（未实施）。

## 十九轮：#252 时间线化——shell 卡作为主对话内容按消息时间序渲染（2026-08-28 14:00–14:15）

> 用户两问：①「我发了一条消息，shell 卡片为啥没有被顶上去？」②「opencode 中这种指令执行是否是对话数据的一部分？分配 subagent 拉源码看！」

### 官方语义源码定音（用户实际运行的 beta-18414 二进制，Bun bundle 内嵌 JS 明文直接提取）

- `"session.shell.started": (l) => e.appendMessage(Le.Shell.make({type:"shell", shellID, command, status, time}))` —— shell 执行**通过 appendMessage 进会话消息历史**（与 synthetic/skill/assistant 同通道）。
- `"session.shell.ended" → e.updateShell(...)` 更新同一条消息的 status/exit/output/time.completed。
- TUI 消息流渲染分支：`if(t==="shell") return {icon:"#", title:"Shell command", lines:["$ <命令>"]}`。
- **LLM 上下文注入**：`case"shell": return [Et.make({role:"user", content:"The following shell command was executed by the user: Command: … Output: …"})]` —— shell 命令+输出作为对话上下文喂给 agent（opencode `!cmd` 的核心语义）。
- 包仓库 repository 字段：github.com/anomalyco/opencode（V2 源码仓库；本地 npm 全局包 @opencode-ai/cli@0.0.0-beta-18414 即用户服务器本体）。

### 客户端对齐实现（83ab0ae5）

- V2Mappers：`type='shell'` 条目 → `Part.Shell` 载荷（shellID/command/status/exit/output）入库——此前只读 text/summary，载荷全丢成空壳。
- buildChatEntries：带载荷 shell 消息**按时间线发射**；零载荷空壳（SSE 窗口/历史）仍跳过。
- ChatMessageList：Turn isUser 分支 role='shell' 特判渲染 EventCard（数据 Part.Shell，输出三级兜底 REST→store）；**钉底横幅退役**（item/bannerCount/reveal 项/贴底重锚 effect 移除）。
- ChatViewModel：runShellCommand 成功后延迟刷新消息列表（UI 发送路径）+ 观察 ShellJobsStore 变化去抖 800ms 刷新（覆盖服务器端/TUI 直发的实时性）。
- 测试：V2MappersTest 载荷映射 ×2 + SyntheticEnvelopeFilterTest 时间线发射用例。

### 真机 E2E（tl_*.png 全链）

- 重进会话：历史 shell 信封 REST 刷新后按时间线渲染（13:18 失败红卡 + 13:32 成功卡插在 13:50 消息上方）✓
- 服务器端 POST shell：store 观察触发刷新，14:07:47 卡实时出现 ✓（补测发现 UI-only 刷新覆盖不了外部创建——观察器修复）
- 发普通消息「msgaftershell」：**卡被顶上去**，新消息+agent 回复在其下方（时间序正确）✓
- force-stop 重启：卡保留（Room 承载，跨进程持久化达成——此前「进程死卡消失」限制解除）✓
- agent 回复佐证上下文注入时序：「echo tlline → echo tlline → msgaftershell，先后关系保持完整」✓