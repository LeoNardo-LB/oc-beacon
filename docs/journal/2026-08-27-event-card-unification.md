# event-card-unification（2026-08-27）

> 状态：待验证（代码+自动化+真机走查完成，V6 用户人工验收 pending）
> 关联：docs/specs/2026-08-26-event-card-unification-design.md · backlog #234
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

## 解析层发现（#240 实证细节）

- 旧 <subagent> 格式真实样本属性名为 `sessionID="ses_…"`（大小写敏感），TASK_ID_ATTR_REGEX 只匹配 `id=` → sessionId=null：箭头（navTargetId）与动作区定位钮同源全缺
- <shell> 样本描述属性为 `command="…"`（实际命令行），TASK_DESCRIPTION_ATTR_REGEX 只匹配 `description=` → shell 描述行不激活（Q15 数据在则显示的意图被错配阻断）
- shell 卡 `id="call_eaaee…"` 为工具调用 id——即便补别名兼容也不能当子会话 id 用于跳转，需 call_ 前缀识别拦截
- 三处均为存量解析行为（旧卡时代已存在），非 #234 引入回归；修复落点 parseSyntheticTask 单点（含正则常量），单测可先行

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

## V6 用户人工验证清单（待用户执行）

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