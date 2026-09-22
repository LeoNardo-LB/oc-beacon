# OC Beacon — 需求与问题总览

本文档是唯一的**未决工作项清单**：只保留尚未完结的需求与问题卡片。条目完结（用户验收 `[x]`）后**当场迁出**——记录连同证据移入 `docs/journal/` 对应批次文件，本文件不保留完结记录；历史查询走 journal 与 git。

**卡片格式**：标题（含全局编号）+ Tag + 状态 checkbox + **≤3 行**摘要 + 链接。需求全文、实现要点、验证证据一律写在链接目标（spec / journal）中，不内联。登记新批次用 `./scripts/backlog-new-batch.sh "<批次名>"`（自动建 journal 文件）；改动后跑 `./scripts/backlog-check.sh` 校验机械不变量。**放置规则（check 脚本强制）**：卡片一律写在下方对应 **Pn 节内**（按优先级定义归位；一节内新卡置顶）；头部编号行与优先级定义表之间**不放任何卡片**（仅允许编号勘误等注释）。**P4 格式增补**：P4 卡必含「**前提**：…」行——说清实现前提是什么、当前为何不可实现（外部硬阻碍所在）。**术语句**：卡片标题与摘要用词遵循 [CONTEXT.md](CONTEXT.md) 术语表（堆积消息/子智能体/轮次/撤销/中断…）；「待处理」保留给权限/问题（状态词待验证/待办/待裁决不受影响）；Tag 英文与 #N 编号不受中文术语约束；API 英文原词（cursor/fork）合法，_Avoid_ 仅限中文对应词。

**编号**：全局递增，不回收。下一编号：**#427**（2026-09-22 #426 结构裂变死代码清理(批次十三退役遗留)）。

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

- [ ] **#423 PreRenderCoordinator 集中式渲染前计算模块** `render` `architecture` `stability`
  - 集中式渲染前计算/视口配对模块:意图层并行声明+底层单写者串行帧事务,统一全部卡片展开/收起稳定性(十一轮竞态根因收拢)
  - 已裁决:全量收编(D1)/统一动画契约含大组(D2)/流式入队分两步(D3)/每步人工手感验收(D4)/反射炸弹 Phase0 护栏(D5);五不变量 I1-I5
  - 六路调研已归档+spec v1 已写,待用户签收后按 Phase0-4 小步迁移
  - → docs/research/pre-render-coordinator/00-synthesis.md
  - 实施开工(2026-09-22):P0 完成——八环仪器固化 scripts/prerender+基线留档 07(红环4 RED:展开落地+198px 瞬态=用户否决闪现,帧级证据);Phase0 完成(26a94b3f 反射探针可测缝+BOM 冒烟单测 3/3 绿,当前 BOM 2026.08.00 未装箱巧合仍成立);Phase1a 视口租约接线中(withEpisode+A3/A4/A5/A6/A9 让位,新增 8 单测全绿;fb3478f6 P0)
  - 批次一完成(2026-09-22):Phase1a 视口租约已合入(f6a4a314)——episode 全程持租约,A3/A4/A5/A6/A9 五点让位,单测 +8 全绿,全量套件绿,真机同场景零回归(红环1 GREEN 保持/账本数学逐帧一致)。+198px 展开落地瞬态已在 07 基线留档,属 Phase1 主体(引擎迁入)消灭对象。待用户 D4 手感验收。journal: docs/journal/2026-09-22-423-prerendercoordinator.md
  - 双轴评审收口:Standards 阻断项(MSGEFFECT 复查漏租约)已修复合入;Spec 轴三范围判定忠实。评审建议登记:reanchorWhenSettledOffBottom 六参谓词束 Data Clumps——Phase1 主体引擎迁入时随协调器统一收编,不单独重构。红环2 补跑 GREEN(FLICKER none)。D4 手感验收待用户。
  - 批次二(闪现根修,用户手感报告驱动):pre-pair 同遍合并+FLUSH 拒绘单点+PRD 帧级观测体系。802ms 未配对窗口→7ms;topY 逸出 0;episode 355ms;像素单帧零过冲。反射定论:无需(相位纪律即可)。commit 634a3e11。待用户手感复验(含 M2:下方内容单帧跳变是否可接受)。
  - 批次三(步骤组顶开根修,用户三报告驱动):FLUSH 实测钉位统一契约(预测配对退役——LEAP+回收双雷,帧级实证 LeftCompositionCancellationException);组尾收起行(小组+大组);重内容首帧占位。折叠行 1100→1100 分毫不动;思考卡同路径回归;全量绿。待用户手感复验。commit 805c0e84。

## P2 — 优化与锦上添花

- [ ] **#425 预热错峰:滚动停止后多卡同帧预热风暴** `perf` `render`
  - 实测 Skipped 53 帧(~880ms 空闲停顿):多张折叠卡同一空闲窗并发 ε 组合
  - 方案:PreRenderCoordinator 队列化,一卡一窗串行预热

- [ ] **#424 步组内容后台解析预取池(L0)** `perf` `render`
  - 用户提案:守护线程池(如2线程)后台预取 Markdown 解析——Compose 组合/测量是主线程铁律不可搬,但解析(最重CPU段)可并行;卡片可见即预取解析模型,ε 组合直接命中缓存
  - 依赖:与 CardExpandReveal PREWARM 衔接(解析预热→组合预热两层)

- [~] **#422 step 自动折叠:turn 内非最后 step 折叠为计数行(DSH 同款时机)** `ui` `chat`
  - 用户裁决(2026-09-20):每 turn 最后 step(最终回答)恒展开,之前 step 自动折叠计数行;流式恒平铺,完结生效
  - 关键发现:StepStart/StepFinish 在 UI 过滤层(RenderableTurn.kt:193 filterRenderableParts)被丢弃——第一步=装配层保留边界标记
  - 方案+调研:docs/research/2026-09-20-code-step-grouping.md;影响面:RenderableTurn/ChunkAssistantItems/折叠组件/i18n(复用统计词);#420:整组单 LazyItem 不拆
  - 三轮收口(2026-09-21 凌晨):
  - 1) CardExpandGeometryNode(ModifierNodeElement+LayoutModifierNode)落地:tween 窗口内复用 settle 末真测 placeable(逐帧只重算 report=f·H,子树零重测);窗口外恒真测保内容失效传播;epoch 变化清缓存双保险。展开前 settleUntilContentStable(2 帧判稳/600ms 上限)吸收表格 containerWidth 两拍收敛与 asyncParse 迟到;episode 末 epoch 强制真测+迟到增量 δ 补偿。单测+5(时钟新语义)。
  - 2) 真机(小米14)验证:重型 turn(10193 字符双表格,H=20292px)10s 冻结 stall 消除——settle 即刻判稳、tween 全程 H 恒定(缓存命中)、收展均为钳制限速平滑动画(展开总 ~2.6s:首测 ~1s 内容固有组合成本+渐进揭示;普通卡片远快于此)。多模态走查:四元素顺序排列无叠压。
  - 3) 顺带修复两处折叠组渲染 bug:a) #258 Stage A MdChunkPlan 对多消息轮次的巨型 part 分片会绕过 StepGroup(Chunk 条目按 part 直渲染,折叠行+末消息内容双丢失)——产侧协调器+装配侧 buildChatEntries 双端封堵,巨型末消息归 Stage B;b) StepGroupCard 展开体缺 Column 包裹(ChunkAssistantItems 为裸 for)致全部 part 堆叠 (0,0) 互相叠压——补 Column(XS 间距,同 Segmented 路径)。
  - 4) DSH 折叠时机核实:研究档案 §1.3 定案 DSH=turnClosed 后折叠(流式恒平铺)——与现有实现(装配层 turn 完结重组+渲染层 isStreaming 平铺)一致,无需改动。
  - 5) code-review 双轴:Spec 6 项全忠实;Standards 8 条修 5(谓词抽取/术语/类KDoc/import序),余 3 低severity(settle 帧循环单测缺口留待)。
  - 待用户验收:视觉终判(尤其巨型卡展开的 2.6s 钳制限速揭示节奏是否可接受——若嫌慢可裁决 MAX_FRAME_DELTA_PX 按 H 自适应放宽)。
  - 四轮收口(2026-09-21 01:15,懒加载实施):
  - 1) 用户报「点了卡死」+ 实测取证:历史大组展开 = 单 LazyItem 一帧组合全部内容(10193 字符双表格,H=20292px,400+ SelectableText 单元格)→ Choreographer Skipped 434 帧 = 3.6s 冻结,MIUIScout 栈顶 MultiParagraphLayoutCache(文本断行)。首轮 Node 缓存只消了逐帧重测风暴,首组合一次性成本仍在。
  - 2) 用户裁决:仅历史展开路径懒加载(流式输出路径零改动——实测流式最差 37 帧微跳,健康);大组直出+淡入,小组(<3 屏)保留动画。
  - 3) 实施(14c3aa01+da5833d0):LARGE_STEP_GROUP_WEIGHT=6000 阈值;展开态大组拆条目发射——尾 Turn(skipStepGroupItem)+StepGroupBody×N(权重 2200 切片,独立 LazyItem,220ms 淡入)+StepGroupHead(共享 StepGroupFoldRow);键序号=文档序、发射逆序(#246 语义);displayEntryStart 钉头部;流式恒不拆(单点门控+单测);stepGroupStateKey() 前缀收口。
  - 4) 真机验证:同重型组冷展开 434 帧→0 帧冻结(全程仅 1 次 37 帧级微跳);折叠行→内容→末消息+统计栏结构正确;收起即时;小组动画保留(713ms);流式冒烟正常;单测 +3(切片/发射序/流式豁免),全量套件过;双轴 review:Spec 10/10 忠实。
  - 已知权衡(用户已裁决接受):大组收起为硬切无动画;二次展开无淡入(rememberSaveable 残留);流式防护单点在 buildChatEntries。
  - 待用户真机验收。
  - 五轮收口(2026-09-21 01:45,用户验收发现的第三层根因):
  - 1) 用户真机验收:点折叠行两次后卡死(logcat 证:两次 ~2.5s 主线程阻塞,栈顶 SimpleMarkdownTable placement;插桩复现:Skipped 440 帧)。
  - 2) 插桩定音(已撤):懒加载框架完全生效——entries 拆分成功、仅组合视口内 2 个 body、组合仅 21ms;冻结在 body 组合之后的测量阶段。
  - 3) 第三层根因:sliceStepGroupBodies 按 part 边界切片,而真实场景大组常为**单个巨型 text part**(60 行表格=10193 字符一个 part)——单 part 不可分,一个 body 条目仍装整表,LazyList 测量该条目时 360 单元格全量断行=3.6s。多层 part 的大组已验证有效(组合快、零跳帧),单 part 巨物未解。
  - 4) 已交付有效的部分(14c3aa01+da5833d0):多 part 大组懒加载+小组动画保留+单测;另发现折叠行可点击区域仅文字宽度(fillMaxWidth 未生效于触摸区)的存量 bug——用户中排点击无反应即此,待修。
  - 5) 下一步方案(待实施):body 内巨型 text part 复用 #258 computeChunkPlan 做 AST 块级切片(协调器预解析→区间条目),即 Stage B Giant 段机制接入展开态拆分;折叠行触摸区修复(Row 可点击区域全宽化)。
  - 教训:本轮『零跳帧验证』实际测的是一次未命中的点击(坐标又落在行边)——仪器验证必须先确认动作确实生效再读数。
  - 6) 2026-09-21 内容漂移(diagnosing-bugs 全程)——已修复 ede8ac05
  - - 症状:小组(动画路径)一个展开+收起循环视口净漂 -366px,展开末折叠行 934→836,收起后整个列表上移(uiautomator 三 dump 逐行对账;截图证实标题栏不动=非滚动错觉)
  - - 根因:#420 δ 配对是开环账本,只记指令不记实际消费。两类误差:(a) dispatchRawDelta 列表边缘残量(逐帧 residual 8+17+35+12+15+11=98px,展开末一次性显形);(b) LazyList 锚点翻转会计误差(收起过程 -268px,账本完全无感知)
  - - 修复:CardExpandReveal reveal 盒顶缘(=折叠行底缘)onGloballyPositioned 实测窗口 Y;episode 正常完成后 episodeEndCorrection(纯函数,5 单测)判定偏差,单次 dispatchRawDelta 修正回本集起点;用户滚动取消/协程取消(反向 toggle)跳过——阅读位置优先权铁律
  - - 真机验证(小米14,同测试位):展开 err=98 consumed=98(钉回 934);收起 err=268 consumed=268;循环后 dump 与点击前逐字节一致,净漂 0px;连测 2 循环守恒;PSNR 首尾帧 32dB(同布局)。证据:docs/acceptance/2026-09-21-422-evidence/drift-fixed-cycle.mp4
  - - 遗留:大组(硬切换条目路径)无任何锚定——展开时折叠行飞出屏(插入高度无补偿),收起时锚点条目被删视口任意落位;与 L3(单巨型 part 测量冻结)同批处理。展开态折叠行 a11y 可见高度 6px(与 reveal 盒 6px 重叠,疑 #231 clip 链)顺带记录
  - 7) 2026-09-21 渲染前反馈闭环重写(用户裁决弃事后补偿)——899d74a2
  - - 用户观看压测后裁决:ede8ac05 的 episode 末补偿"先漂再拽回"不可接受;要求渲染前完成计算(#420 同帧配对严格化)
  - - 逐帧插桩([DEBUG-425] topY/anchor/fii/fiso/rep/abs)定位三类断点:①组合滞后残量永久丢失(指令账本只记指令);②LazyList 锚点翻转会计误差(消费满额但视觉说谎,收起 −60→−328 阶跃);③收起中段 anchor 稳定时 dev=0 证明配对数学本身正确
  - - 新机制:每帧指令 = 缓动增量 + (锚−上帧实测Y) 死拍反馈;吸收账本观测化;循环收敛条件 = 缓动走完且指令≈0
  - - 四个真机迭代坑(全部插桩实证后修复):死锁(上报做消费奴隶→dispatch 恒 0,改乐观上报)、双计振荡((目标−已吸收)+偏差 极限环 ±154 交替,改增量+偏差)、崩溃(fraction→0 后缓存 placeable 放置 detached 节点,关窗口+守卫)、连点链式漂移(重定向取消以漂后位置起新锚 −73px,carriedAnchor 携带)
  - - 压测(用户令系统性设计):A1 十循环 934 守恒;A2 24 连点@300ms / A3 30 连点@150ms 风暴后 934 精确守恒;1244 风暴帧 17 帧(1.4%)瞬时偏差≤2 帧自愈;63 episode 仅 1 次 end-restore 82px;无 ANR/崩溃;JVM 竞态单测 28 个
  - - 证据:docs/acceptance/2026-09-21-422-evidence/drift-fixed-tap-storm.mp4
  - - 注:风暴中偶发单次点击丢失(input 投递层面,非状态机;恢复点击均正常翻转);大组硬切路径漂移与 L3 冻结仍在案(与本卡分批)
  - 8) 2026-09-21 震荡根治·两阶段架构(用户两轮否决后)——a7e7d2bd
  - - 用户裁决链:ede8ac05 事后补偿=否("先漂再拽回");899d74a2 逐帧反馈=否("来回震荡")
  - - 取证定案(录屏条带追踪±24-136px + [DEBUG-425] 逐帧):①展开方向 LazyList 布局多 pass 不稳定(dispatch 时刻 topY 逐 pass 振荡±60px)——动画期间布局/滚动参与即震荡;②逐帧实测反馈的信号(布局坐标)对滚动位移盲且滞后,本身成为扰动源;③自然锚定证伪(禁 dispatch 折叠行飞出屏 2852px,dispatch 必需);④收起方向布局稳定(consumed==d 逐帧,topY 恒定)
  - - 终局架构:展开=A 阶段一次性布局落位(内容不可见,稳定窗全额重试)+B 阶段纯绘制揭示(drawWithContent clipRect,零布局零滚动);收起=原逐帧路径;指令=目标−已吸收账本
  - - 验证:展开/收起终态 934 精确;5 循环+12 连点守恒;零 end-restore;无崩溃。余量:展开 A 阶段一次 ~130px 单向瞬时沉降(无往复);备选=DSH 式硬切无动画(结构完美,待用户裁决)
  - - 工具教训:uiautomator dump 对被裁节点报可见高度(6px 假象);positionInRoot 对滚动 draw-offset 盲——测量指标必须与像素级录屏条带交叉验证;screenrecord 变帧率使帧号≠墙钟
  - 六轮收口(2026-09-21 午后,空白卡死追修):
  - - 1) 用户报「为啥会这样」:折叠行下方 ~1200px 空白、内容全消。取证:fraction=1(布局占位2852)+drawFraction=0(内容隐形)的卡死态;恢复实验(再点一次)内容即回,H 恒在。
  - - 2) 根因:cancel-on-scroll 处理器(LaunchedEffect(listState),不随 visible 重启)闭包捕获**过期 visible**——收起期首次组合的实例在展开后遇用户滚动,snap(0f) 把 fraction 打 0(内容离树、H=0),收尾 vt 循环又把 fraction 拉回 1,终态「占位+隐形」。日志铁证:cancel 后 rep/abs/H 全 0。
  - - 3) 修复:①rememberUpdatedState(visible) 读当下值;②finally 不变量「fraction>0 ⇒ drawFraction=1」兜底一切退出路径。
  - - 4) 击杀链复现验证(修复后):展开→淡入中 fling→cancel-on-scroll 命中(snap f=1.000)→post-cancel 帧恒 rep=2852/H=2852(旧版此处归 0)→滚回 T1 内容完整可见(7.6s 行+18 行表格标题俱在),无任何空白。
  - - 5) 教训:长生命周期 effect 闭包捕获可变参数必须 rememberUpdatedState;「占位必显示」应为组件级不变量(纯绘制分数只能由正常动画路径收敛,退出路径须强制归位)。
  - 七轮收口（release 首装冒烟，2026-09-21）：用户要求换 release 版体验（debug 卡顿）。跨签名切换（pinnedDebug→release.jks）卸载重装 devRelease 后，adb tap 在折叠行上 8/8 无效 → 一度误判"release 上 toggle 失效"。插桩版复测 6/6 全对（click→toggle→episode 展开 f=1.000 620ms/收起 f=0.000 385ms，同帧配对 2852/2852 在 release 同样成立），还原重建干净版复测：tap1 miss、tap2 正常。真相：(a) 已知 tap flakiness（折叠行触摸目标窄）在 release 依旧 ~50%，非回归；(b) 自制检测器 isexp.py 被 6px sliver 命中 fold934 正则 → 永远报 NOT_EXPANDED，放大成幻影 bug——sliver 陷阱复发，检测器必须用"展开内容标志行 presence + 折叠行全高"双条件；(c) strip 跟踪器在 miss tap 的整行按压高亮/收起过渡态上会产出 d≈11 弱匹配的假位移（-116px/-84px 假 RED），判定必须加 d 阈值（<5 才可信）并以组上方参考条带为验收主判据。release 像素级验证：上方参考条带全程 +0px 零 UNMATCHED（收起+展开双 episode 213 帧）。
  - 八轮收口（release 体验两 bug + 幻影哑火，2026-09-21，22f25b53）：用户报①大组展开加载延迟高②思考卡等小卡展开"闪烁跳一下像补偿"。插桩 release 三点取证定音：(1) 幻影哑火根因=StepGroupFoldRow 把 !expanded 当 defaultExpanded 传——委托语义 !(map[id]?:default) 使收起态首点写 false=静默无效,每组每冷启首点必哑(观感即"点了没反应/加载慢"),改传 expanded;(2) 小卡顿挫=两阶段设计在小卡呈四拍(摘要瞬消→+146px 刚性跳留空白一拍→内容瞬现→底纹渐隐,逐帧视觉取证),≤600px 改逐帧几何 tween+同帧增量配对(drain 基准 lastMeasuredH→lastReportedH 两路径统一)+alpha 随 fraction 淡入;(3) 大组冻结=60 行表格单体 part(h=20226px)首组合 598ms 屏幕零反馈,StepGroupBody 重组合延后一帧,首帧画 loading 占位。验证(真机 release,22f25b53):T1/大组冷启首点均即展开;思考卡墨水轨迹 4-5 帧渐变无凹陷无闪烁,上方条带 +0;大组展开涟漪→占位可见 0.7s(冻结分段)→内容流入;T1 两阶段守恒 ref -2px。教训:检测器 sliver 陷阱二次复发后,状态判定一律用"展开内容标志行 presence+折叠行全高"双条件;条带跟踪器在按压高亮/内容替换窗的弱匹配(d≥11)产假位移,验收主判据=组上方参考条带。L3(AST 级切片根治 598ms 冻结)仍待做,占位是体验缓解。
  - 九轮收口（浅灰 pill 定性，2026-09-21）：八轮收口后视觉终审在录屏 f0020+ 报「展开态标题行残留浅灰 pill(x237-1024,灰226)」。数值复核定案为视频编码伪影：pill 区域在 mp4 帧内 95-98% 均匀 226 且无形状结构，而同状态无损 screencap PNG 该区域 98% 为真背景 249、灰带仅 0.2%；折叠/展开/滚走回/再收起四态 PNG 扫描均无任何背景块（先前扫到的 215-245 灰带实为 FAINT 半透明摘要文字墨水及其淡出过程）。结论：应用渲染干净，无残留样式，非 22f25b53 回归；教训入档：录屏取证对「平坦背景上的低对比(~8%)色块」结论必须用无损截图交叉验证——H.264 在邻近运动内容旁会把背景量化漂移 20+ 灰阶且长时间不收敛。
  - 十轮收口（用户再报震荡/上顶，2026-09-21，fbc91c21+a98e5b76）：release 验收再报①过程展开内容上顶②其他卡片先震荡后展开。插桩三轮定案:②根因=八轮收口的清理事故——git checkout 回滚插桩时连带吞掉了 drain 配对基准修改(lastMeasuredH→lastReportedH),该编辑从未随 22f25b53 发布!逐帧路径首拍在布局未增长时全额 dispatch H(f=0.004 即推 146px),账本实测 +146,+146,-146,+146,-146(fii 8↔7)=震荡,战后缓动走完=「先震荡然后展开」;另有 else 悬挂(小卡分支后 A/B 代码无条件坠落=内容闪灭再淡入)同期未发布。两者重应用(fbc91c21)。①大组上顶=权重≥6000 硬切换无配对(已知#4),锚定尝试五轮(scrollToItem 偏移/反馈环/键匹配/两步跳拉)均因反向布局滚动语义落错位——实测视口被甩到会话底端比不锚更糟,已撤(a98e5b76),回归 #4 随 L3 以 layoutInfo 键匹配重做;教训:反向布局滚动语义必须先写校准单测再上真机。验证:小卡贴底武装态墨水 75→89 单调无凹陷无闪烁;T1 守恒 ref -2px;单测全绿。流程教训入档:清理插桩禁止整文件 checkout——必须按 diff 块回退,发布前 grep 目标修改是否在树(本次两处修复双双被吞,验证却因靶位盲区+视觉终审窗口未覆盖闪灭拍而放行)。
  - 十一轮收口（小卡位置漂移+回跳，2026-09-21，7dbb4189）：用户 subagent 点击思考卡实测「内容往上推,动画结束卡片突然跳回原位」——违反卡片视口位置不变量。图标条(∞图标区域,不受摘要淡化污染的位置特征)轨迹实测复现:动画期卡片被推 0→-8→-40→-110→-194px,episode 末 end-restore 一次性跳回 0。根因=十轮收口上线的逐帧配对路径:drainPhaseA 首拍 pending<1(f=0.004·H≈0.6px)即执行「phaseADrain=false」解除排干武装,后续每帧增长全部无配对,推力逐帧累积;end-restore 在 episode 末一次性纠偏=可见回跳。架构裁决:撤逐帧路径,小卡回归两阶段(一次落地+同帧全额配对,位置钉死);空白拍(四拍顿挫残余)改由 PHASE_B_JUMP_START=0.3 消除——A 落地帧排干完成时(pre-draw 同帧)drawFraction 直接跳 0.3,B 从 0.3 续坡至 1,高度与首批内容同帧出现,无空白无瞬现;展开向闭环尾循环(零指令空转)一并跳过。验证:展开/收起双向图标条全程 dy=0(位置不变量成立),墨水无凹陷无闪烁,落地帧起内容即部分可见;单测全绿。方法论入档:卡片位置不变量的判定特征必须选「不随动画变化的稳定像素」(∞图标条),摘要淡化区/高亮区会让模板匹配器跟丢或误报;逐帧布局参与反向列表=漂移与震荡二象性,两阶段一次落地+同帧配对是唯一被反复验证稳定的位置钉死方案。

- [~] **#421 消息流全量单行形态(DSH化):思考/工具/通知卡去容器** `ui` `chat`
  - 已实施完成(commit 5022b81a..7851eee2):ToolCardScaffold 透明收口16卡/ReasoningBlock去容器去色条+∞图标+尾部摘要/展开左竖线/通知四类+三横幅透明化;豁免:统计栏/问题/权限/错误行
  - 调研:docs/research/2026-09-20-single-line-cards/(B视觉/C业界/D改造地图);验证:容器色块像素消失+多模态审查成型+全量单测绿;#420契约零改动
  - 待用户真机观感验收

## P3 — 观察与低价值改进

- [ ] **#426 结构裂变死代码清理(批次十三退役遗留)** `cleanup`
  - buildChatEntries StepGroupHead/Body 发射机+sliceStepGroupBodies+SGB 探针+LARGE_STEP_GROUP_WEIGHT 常量已休眠

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
