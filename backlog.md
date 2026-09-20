# OC Beacon — 需求与问题总览

本文档是唯一的**未决工作项清单**：只保留尚未完结的需求与问题卡片。条目完结（用户验收 `[x]`）后**当场迁出**——记录连同证据移入 `docs/journal/` 对应批次文件，本文件不保留完结记录；历史查询走 journal 与 git。

**卡片格式**：标题（含全局编号）+ Tag + 状态 checkbox + **≤3 行**摘要 + 链接。需求全文、实现要点、验证证据一律写在链接目标（spec / journal）中，不内联。登记新批次用 `./scripts/backlog-new-batch.sh "<批次名>"`（自动建 journal 文件）；改动后跑 `./scripts/backlog-check.sh` 校验机械不变量。**放置规则（check 脚本强制）**：卡片一律写在下方对应 **Pn 节内**（按优先级定义归位；一节内新卡置顶）；头部编号行与优先级定义表之间**不放任何卡片**（仅允许编号勘误等注释）。**P4 格式增补**：P4 卡必含「**前提**：…」行——说清实现前提是什么、当前为何不可实现（外部硬阻碍所在）。**术语句**：卡片标题与摘要用词遵循 [CONTEXT.md](CONTEXT.md) 术语表（堆积消息/子智能体/轮次/撤销/中断…）；「待处理」保留给权限/问题（状态词待验证/待办/待裁决不受影响）；Tag 英文与 #N 编号不受中文术语约束；API 英文原词（cursor/fork）合法，_Avoid_ 仅限中文对应词。

**编号**：全局递增，不回收。下一编号：**#423**（2026-09-20 #422 step 自动折叠:turn 内非最后 step）。

**操作纪律（2026-09-09 用户定规，账本事故后）**：卡片区**禁止手工直编**——登记/明细追加/状态流转/完结迁移一律经 `./scripts/backlog.sh`（add/note/status/migrate；真实 backlog 变更后自动跑 check）；journal 新节追加用 `backlog.sh journal append`（append-only）或编辑工具定位插入，**禁止全量覆写重写 journal**（2026-09-09 演示批覆写丢章事故定规）。**裁决优先级（2026-09-09 用户定规）**：同一问题域存在多项历史裁决时**以最新裁决为准**；新裁决落地时须回写旧裁决域卡片的注记（#350 为先例）。**反馈归卡（2026-09-12 用户定规）**：用户对某张卡片的反馈/裁决一律经 `backlog.sh note <N>` 记入**该卡片**明细，**不另开新卡**承载反馈；仅当反馈引出**新的独立缺陷**时才另立卡片，并在两卡明细互相引用（#401→#408 为先例）。

> 编号勘误（2026-08-23 合并时）：terminology 分支先行占用的 #194–#199 与主工作区 #194（FAB）撞号，合并时 terminology 侧六卡顺移 +5 → #200–#205；文档内旧引用已同步改。

**优先级定义**：

| 等级 | 含义 | 示例 |
|------|------|------|
| **P0** | 影响主要流程体验或核心业务场景的 bug | 聊天页面崩溃、SSE 断连无法恢复 |
| **P1** | 主要业务流程的需求功能点 | 会话搜索、消息转发 |
| **P2** | 优化专项、锦上添花功能、不影响体验的小 bug | 动画微调、文案优化 |
| **P3** | 观察项 / 依赖外部条件的低价值改进 | 偶发自愈的异常观察、环境因素类缓解 |
| **P4** | **外部前提阻塞**：功能/工作方向明确，但实现前提在 app 之外（服务器能力缺失 / 上游未合 / 用户流程门槛），前提满足前不可动工——**卡内必含「前提」行**（前提是什么、现在为何做不了）；前提变化时重验归位 | 服务器未暴露的事件聚合、上游 PR 候选清单 |

**修复方针**（2026-09-03 用户定规）：bug 类条目**根因修复优先**——交付的修复必须消除触发链的根因层（架构 / 生命周期 / 状态机 / 协议缺陷），不以表象层兜底单独交差（「缓存兜底显示」「重试遮罩」「吞异常」等手段不得作为修复本体）。兜底类缓解仅在同时满足以下条件时接受：①对应根因修复卡已登记并被引用；②兜底卡摘要显式标注「过渡措施」并关联根因卡编号。分层不明确或拿不准时，先向用户呈现根因分析与分层方案，裁决后再落卡/动工。

**验证方针**（2026-09-03 用户定规）：**绝大多数情况（含 UIUX/交互类改动）都应通过真机端到端测试验证并关闭**，`[~]` 不按「是否 UIUX」划分，按「是否存在可注入/可观测的自动化仪器」划分。可用仪器（持续积累）：uiautomator dump/tap（导航+断言，注意 LazyColumn 视口冻结——dump 前先滚顶）、logcat/Room 日志直查、`/proc/net` socket 观测、debug 注入工具（#305 `--ez debug_simulate_timeout` 先例）、curl 模拟服务器侧事件（POST 建会话/订阅 SSE 抓帧）、网络扰动（nc 黑洞 + `adb reverse` 重定向 + `adb kill-server` 断既有连接）、`pm install` 静默装包 + `debug-entry.sh` 确定起点。**仅以下情形才需要人工介入**：①真手指连续手势/体感类——注入手势是平台批处理伪影，无法模拟真手指位移流（#245 两轮证伪）；②需用户凭据/跨设备操作（GitHub 密码/2FA、扫码等）；③数日级真实使用观察（不可加速，如挂机断链观察）；④主观体验拍板类（「好不好用」的最终确认）。判定次序：先穷举仪器→构造红回路→E2E 验证关闭；真找不到仪器才登记人工项并写明卡内为何仪器不可行。

**状态流转**：代码写好但未验证不等于完成！要求完成需求、自行验证、用户验收通过之后才算完结；完结即迁移（见首段）。

| 状态 | checkbox | 含义与流转规则 |
|------|----------|------|
| **进行中** | `[ ]` | 需求已登记或正在开发。开发完成后跑通自动化验证（编译/单测/i18n/assemble）并自行完成可覆盖的验证后 → 转「待验证」 |
| **待验证** | `[~]` | 代码完成、自动化验证通过，但**用户人工/真机验收未完成**。后续 Agent 看到 `[~]`：向用户给出验证清单并请其执行；通过 → 转「已完成」并**当场迁移**；发现问题 → 改回 `[ ]` 进入修复 |
| **已完成** | `[x]` | 仅迁移瞬间存在的过渡态——迁移完成后本文件不含任何 `[x]` 顶层条目（check 脚本强制） |

**Tag 标签体系**：标记相关领域便于批量排查；现有 Tag 不足以描述则新增。

| Tag | 说明 |
|-----|------|
| `crash` | 崩溃 / 闪退 |
| `ui` | 界面显示、组件缺失、布局问题 |
| `data` | 数据展示不准确、数据源疑问 |
| `sse` | SSE 连接、事件推送相关 |
| `session` | 会话管理相关 |
| `permission` | 权限请求、审批相关 |
| `security` | 安全与隐私（明文凭据、泄漏、合规） |
| `refactor` | 重构、死代码清理、分层修复 |

**Spec**：满足「有非显然取舍需留档」或「跨会话实现需完整上下文」其一 → 在 `docs/specs/` 写 `YYYY-MM-DD-<名称>-design.md`（spec 是权威，卡片只留摘要+链接）；实现并用户验收后移入 `docs/archive/specs/`，同步更新 spec 头部状态行与卡片引用路径。**归档 spec 定期清理零外部引用者**（git history 永久可找回）。简单需求不写 spec。

**Journal**：每个工作批次一个 `docs/journal/YYYY-MM-DD-<英文kebab名>.md`，**开工时创建**，过程中取证/验证证据直接写入 journal（卡片全程保持 ≤3 行）；完结条目当场迁入，原文保留不压缩不删改。可复用的蒸馏结论提炼进 `docs/research/`，journal 只记执行与证据。**新 journal 术语三原则**：①叙述段用 CONTEXT.md 规范名；②证据引用豁免（logcat 行、SSE 事件名、i18n key、标识符原样保留）；③规范名首现带英文原词，编号遵循 [numbering-charter](docs/numbering-charter.md)。

---

## P0 — 主流程阻塞

## P1 — 核心功能需求

- [ ] **#420 展开思考卡片闪烁跳动** `ui` `chat` `bug`
  - 症状:展开 ReasoningBlock 时界面闪烁+跳动;diagnosing-bugs 流程进行中(2026-09-20)
  - 结构:AnimatedVisibility(CardExpandEnter=fadeIn+expandVertically Top)→heightIn(240dp)+verticalScroll+MarkdownContent(small)
  - 根因(仪器定案):贴底时toggle卡片,item高度变化全额转译为视口位移(实测展开+644px/收起-608px,峰值220px/帧,~170ms);isAtBottom恒真,guard零参与(日志证实);mid-list同理(±444px,2026-08-30守卫注释记载)
  - 设计(方案A·单一时钟同帧配对):CardExpandReveal包装器替换8处AV——AV只留fadeIn/fadeOut(组合生命周期),尺寸由自有f时钟驱动:帧回调dispatchRawDelta(δ)先行+本帧measure上报f*H,严格同帧配对零滞后(击败#262残余的帧界一帧错位);δ取全导数(f变化+H实时变化如展开中markdown迟到解析)
  - 竞态矩阵:R1流式并发=toggle降级裸AV(LocalInStreamingTurn,item级补偿独占)+官方dispatchRawDelta主线程串行| R2守卫=累计+δ越100px时autoScroll=false(离底即离开跟随)| R3滚动中=取消时钟snap f| R4跳转导航=isScrollInProgress取消覆盖| R5双toggle=f可逆重定向| R6 FAB中段浮现=良性| R7中途回收=冷组合snap目标+仅转换时动画| R8贴底收起(flow b)=dispatchRawDelta负向不可消费,物理不可约(防尾部空白),文档化| R9反射=零接触,通道零改动
  - 历史对照:2026-08-30 #262退役因复杂度高而残余跳动(帧界一帧错位+AV边界30px台阶);本设计无subcompose/无状态机层级/无指针吞没,同帧配对根治错位;旧裁决『贴底展开上方上推为终态』由本日新诉求覆盖(最新裁决为准)
  - 修复验证(真机,commit 29ea9a38):CardExpandReveal 单一时钟同帧配对——Test1 贴底四连击 0跳/0闪(修前±644px),Σδ=737≈H=738 逐帧全额消费 | Test2 mid-list 双击 0跳 | Test3 SSE 流式 680帧 0跳(贴底跟随正常) | Test4 飞行中断 f 0.98 丝滑回摆无崩溃 | Test5 拖动取消 cancel-on-scroll snap f=0.96 | Test7 展开→⬇FAB出现→回底→FAB隐 | 全量单测 BUILD SUCCESSFUL
  - 收起侧实证:头部带模板跟踪全程 1px(钉死);全局分析器的-904px读数=答案尾部从折叠线下升入视野(物理必然,唯一移动量)——收起语义正确 | t4b tap2 无日志一例未复现(疑 adb 投递竞态),toggle 活性复验正常
  - 已知边界(文档化):①贴底收起(flow b:⬇回底后再收起)负位移不可消费→上方内容自然吸收(防尾部空白,物理不可约) ②流式turn内卡片降级裸AV(item级COMP-MSG独占) ③展开后⬇FAB出现=离开跟随模式(裁决语义) ④120Hz 下 dispatch 每8-16ms一次,30fps录屏每帧含3-4次(视觉平滑)
  - 证据:docs/acceptance/2026-09-20-420-evidence/(4段mp4+2截图);[DEBUG-420]日志标签全链路可查

## P2 — 优化与锦上添花

- [ ] **#422 step 自动折叠:turn 内非最后 step 折叠为计数行(DSH 同款时机)** `ui` `chat`
  - 用户裁决(2026-09-20):每 turn 最后 step(最终回答)恒展开,之前 step 自动折叠计数行;流式恒平铺,完结生效
  - 关键发现:StepStart/StepFinish 在 UI 过滤层(RenderableTurn.kt:193 filterRenderableParts)被丢弃——第一步=装配层保留边界标记
  - 方案+调研:docs/research/2026-09-20-code-step-grouping.md;影响面:RenderableTurn/ChunkAssistantItems/折叠组件/i18n(复用统计词);#420:整组单 LazyItem 不拆

- [~] **#421 消息流全量单行形态(DSH化):思考/工具/通知卡去容器** `ui` `chat`
  - 已实施完成(commit 5022b81a..7851eee2):ToolCardScaffold 透明收口16卡/ReasoningBlock去容器去色条+∞图标+尾部摘要/展开左竖线/通知四类+三横幅透明化;豁免:统计栏/问题/权限/错误行
  - 调研:docs/research/2026-09-20-single-line-cards/(B视觉/C业界/D改造地图);验证:容器色块像素消失+多模态审查成型+全量单测绿;#420契约零改动
  - 待用户真机观感验收

- [~] **#419 user 气泡统计栏外置(扁平化收尾)** `ui` `chat` `消息层扁平化`
  - 共识(2026-09-18 拷问定稿):插话徽标+撤销+复制+详情整栏移出气泡,气泡下方右对齐(右缘平齐列表缘),间隙4dp/紧凑2dp
  - 排布 [徽标][撤销][复制][详情] 右缘起向左;触达修复:视觉14/16dp不变,命中区28dp;净高+6dp,观感不佳时气泡下边距14→10dp
  - ChunkedUserMessage 末段同步;FAB重叠(#418)维持先这样;验收①-⑩+观感拍板,steer徽标走代码审查+用户目检
  - 真机验收(小米14 无线,192.168.110.123:4199):①图标行位于气泡bounds外,行右缘1164=气泡右缘,间隙4dp(图标视觉顶841 vs 气泡底808) ✓
  - ②还原→确认弹窗/详情→消息详情弹窗(时间+删除)/复制→已复制到剪贴板toast ✓ ③触摸节点96-144px宽×144px高(48dp强制触摸目标+相邻裁剪),远超28dp底线 ✓
  - ④多行3202字符消息触发UserChunk分片,末段(407字符)下方外置行 ✓ ⑤紧凑密度(对话字体=小)图标行同构渲染 ✓ ⑥AMOLED纯黑模式截图取证 ✓ ⑦⑧SSE流式全程正常(思考中→流式输出→渲染) ✓ ⑨content-desc(还原/复制/消息详情)全保留 ✓ ⑩全部历史user消息外置行生效 ✓
  - 插话徽标:本日两次发送均未走steer路径(徽标未出现),按共识Q8a留待用户下次自然插话目检;截图证据 docs/acceptance/2026-09-20-419-evidence/;净高度实测+6dp档(气泡下边距14dp未收)——观感拍板时若嫌高可收14→10dp
  - AMOLED 复验修正:浅色主题下 AMOLED 开关无效(设计如此)——补验深色主题+AMOLED:背景带像素亮度11.36/255(近纯黑),右缘带877/900行含图标内容,图标行清晰 ✓;截图 amoled_dark.png 归档

## P3 — 观察与低价值改进

## P4 — 外部前提阻塞

- [ ] **#381 192.168.110.248:248 重配——凭据宿主零痕迹不可探查（2026-09-09 用户裁决入 P4）** `infra`
  - **前提**：上游提供远程凭据探知能力——用户原话「后续看看 dsh 官方是否会支持远程探知 token 或其他认证手段」（DSH 官方远程 token 发现，或 OAuth/设备码流等替代认证）；前提满足后 `cred-probe.sh opencode 248 <名称>` 即可接管（/proc 探针现成）；若上游确认不会支持，再议弃用或人工一次性重配

- [ ] **#352 长按菜单「取消归档」——wire 层无恢复动词（2026-09-07 用户裁决要求，服务器阻塞）** `dsh` `archive` `ui`
  - 裁决原文:「归档单向契约同删除一样在长按弹出框中增加即可」——用户要求已归档行长按菜单加「取消归档」
  - **前提**：上游 dsh 服务器提供恢复动词——实测证据（2026-09-07 深夜，当前部署源码 dsh-api-workspace-controller typert）：WorkspaceArchiveSessionRequest={sessionId} **add-only**，全 API 面仅 archiveSession 一个归档动词，官方 web 客户端同无恢复入口（SessionRowMenu 2026-09-05 四重取证注释仍有效）；动词就位后：菜单项+RPC+已归档折叠区行刷新一步到位（#351 能力位先例同款）

- [ ] **#332 spill 提示行——服务器无结构化信号（工具结果溢出 notice 内嵌纯文本）** `dsh` `sse`
  - **前提**：dsh-spill-policy 全链查实——溢出替换为有界 head/tail 预览+locator 提示全部内嵌工具结果 output 文本,transcript 无 spill 事件（session 事件枚举/types/实现三路 grep 0）;文案模式匹配脆弱（#136 先例:服务器改文案即静默失效）。待服务器暴露结构化字段再实现。→ `docs/research/2026-09-05-audit-309-313.md` #312③ + 实现 agent 取证（暂不可实现）

- [ ] **#288 workflow 阶段卡（tool-workflow agent-start/end 聚合渲染）** `dsh` `ui`
  - **前提**：dsh 服务器在任何客户端面暴露 tool-workflow 运行事件——events.mux 实况 / session.history journal / session.projection / session/jobs 四面实测皆无（Web 端 workflow 树为 client-ui 本地组件，同一事件源）；服务器升级暴露后重验再启聚合器
  - Task 3b 落地 workflow run-start/run-end 降级单卡（同 runId 原位更新 running→终态）；agent-start/end 维持 Ignored（防逐成员刷卡）
  - 方向：run 级聚合器（成员 label/outcome/phase 折叠进阶段卡，参照官方 tool-workflow 装配）；验证=真机 workflow 运行会话卡片分阶段展示
  - **2026-09-01 活体四面包夹（走查 #9 定性）**：events.mux 实况帧（两次 WS tap + 现跑 workflow 对照，仅 tool/code-dispatch* 渲染伴生）、session.history journal（39 页全翻 0 行，fresh run 亦不入）、session/projection（仅 permissions）、session/jobs（仅 bash 后台任务）四面皆无 → app 侧映射链（DshEventMapper:469 + DshMessageAssembler）为休眠代码路径，非缺陷；走查期「18 事件在 a6c4」不复现（疑当时另有来源/版本窗口）。重开丢卡=结构性（无服务器数据源），DSH synthetic 消息零持久化同因
  - **2026-09-02 复验（差距调研独立交叉确认）**：`docs/research/dsh-gap-2026-09-01/` 四路证据（fe 源码/Android 清点/Web 实测/服务端 api-gap）再次确认服务器事件面无 tool-workflow 运行事件——门维持关闭；聚合器设计参照 fe-inventory §2.17 client-ui-workflow-run

- [ ] **#146 OpenCode 官方问题清单（issue/PR 候选）** `upstream`
  - **前提**：上游 anomalyco/opencode 合入变更——需先过用户流程门槛（本地定位官方源码→修复→完整测试含 E2E+交叉验证→人工测试→用户放行才可提交 PR；源码已就位）；2026-09-03 用户裁决长期挂起，上游不提不影响本 app（客户端防御均已落地）
  - ①V2 不发 compaction.started（引擎没接线）②SSE 重连无事件回溯 ③cursor V1 格式返回 400 ④fork handleRaw bug ⑤工具输出截断语义——上游核查完成（repo 已迁 anomalyco/opencode），逐项行动方案已定
  - ⑥候选（2026-08-27 八轮实证）：V2 后台 shell 状态恒 completed（exit 7 亦然），失败信号仅正文文本——上游语义退化，客户端已防御性派生
  - **2026-09-02 逐项复现取证完结（journal 258-stage-b §十）**：源码浅克隆 `~/Documents/code/opencode-upstream`@69c172e + Host-4199 live 复验——①②③⑥ HEAD 仍成立（①连 schema 都无 Started；②端点零回溯处理；③400 已类型化 `_tag` 但无降级；⑥exitCode 从不映射 status）；**④上游已修/改版**（空 body 分支 + payload 改 `{messageID?}`，运行版未跟上，app 现发形状已匹配 HEAD）；⑤不变（FR 开放）。PR 候选排序 ③>⑥>①>②
  - → `docs/journal/2026-08-15-chat-flow-bugs.md`
