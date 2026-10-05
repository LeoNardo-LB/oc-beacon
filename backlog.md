# OC Beacon — 需求与问题总览

本文档是唯一的**未决工作项清单**：只保留尚未完结的需求与问题卡片。条目完结（用户验收 `[x]`）后**当场迁出**——记录连同证据移入 `docs/journal/` 对应批次文件，本文件不保留完结记录；历史查询走 journal 与 git。

**卡片格式**：标题（含全局编号）+ Tag + 状态 checkbox + **≤3 行**摘要 + 链接。需求全文、实现要点、验证证据一律写在链接目标（spec / journal）中，不内联。登记新批次用 `./scripts/backlog-new-batch.sh "<批次名>"`（自动建 journal 文件）；改动后跑 `./scripts/backlog-check.sh` 校验机械不变量。**放置规则（check 脚本强制）**：卡片一律写在下方对应 **Pn 节内**（按优先级定义归位；一节内新卡置顶）；头部编号行与优先级定义表之间**不放任何卡片**（仅允许编号勘误等注释）。**P4 格式增补**：P4 卡必含「**前提**：…」行——说清实现前提是什么、当前为何不可实现（外部硬阻碍所在）。**术语句**：卡片标题与摘要用词遵循 [CONTEXT.md](CONTEXT.md) 术语表（堆积消息/子智能体/轮次/撤销/中断…）；「待处理」保留给权限/问题（状态词待验证/待办/待裁决不受影响）；Tag 英文与 #N 编号不受中文术语约束；API 英文原词（cursor/fork）合法，_Avoid_ 仅限中文对应词。

**编号**：全局递增，不回收。下一编号：**#514**（2026-10-05 #513 流式渲染全程冻结完结砸出——live 消费链断裂）。

**操作纪律（2026-09-09 用户定规，账本事故后）**：卡片区**禁止手工直编**——登记/明细追加/状态流转/完结迁移一律经 `./scripts/backlog.sh`（add/note/status/migrate；真实 backlog 变更后自动跑 check）；journal 新节追加用 `backlog.sh journal append`（append-only）或编辑工具定位插入，**禁止全量覆写重写 journal**（2026-09-09 演示批覆写丢章事故定规）。**裁决优先级（2026-09-09 用户定规）**：同一问题域存在多项历史裁决时**以最新裁决为准**；新裁决落地时须回写旧裁决域卡片的注记（#350 为先例）。**反馈归卡（2026-09-12 用户定规）**：用户对某张卡片的反馈/裁决一律经 `backlog.sh note <N>` 记入**该卡片**明细，**不另开新卡**承载反馈；仅当反馈引出**新的独立缺陷**时才另立卡片，并在两卡明细互相引用（#401→#408 为先例）。**工作流脚本类直接修（2026-09-29 用户定规）**：项目工作流/脚本层的修复（`scripts/` 流程脚本等不进 APK 的项目设施）**不立卡**——发现即直接修+自测，证据记入当批 journal；#480 为末代先例（已立卡的按原流程走完迁移）。

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

- [~] **#513 流式渲染全程冻结完结砸出——live 消费链断裂（桶A验收卡1 用户定罪+仪器三层坐实）** `streaming` `render` `regression`
  - 真机 V1@4101 docker(deepseek)：服务器 SSE 渐进发射(1492 delta/11s)→app 实时接收(dispatch 同步)→flush/publish 流转→但渲染卡片冻在 96px 占位直到完结一次性砸出(6738px/46ms)；中英文同形；400 字轮亦末段 0.2s 才长
  - soak 存档复核：全部卡片 d=h 单步出生满高（无渐进小步）——回归在 10-04 soak 构建已在场，#484 签名集（零负向）对爆发形态失明；#503 t36(10-02 渐进 fires)/#442 A4(渐进) 在嫌疑窗口之前
  - 嫌疑窗口=10-03~04：#509 方案B(45c9d9bb)+二期 pilot即终态(28154286)+#510 五补偿族退役(f3b4f886)；现场探针签名=流中零 MDPilot append/零 B3 live override（完结才 fallback），B2-bus publish live=1 正常
  - → docs/journal/2026-10-05-a.md §1
  - 根修落地（0f2c78dc）：flushPendingDeltas first-text 过桥（空种子注册族渲染条目存在性桥）；单测 3877/0/0 + 真机渐进渲染恢复（MDResize +66/+132 连续至完结、零负向）；嫌疑窗口（#509二期/#510）洗清——回归自 B案 V1 主路径即存在；verify 态待用户重演卡1

- [~] **#507 流式毕业内容消失——turnGroups 结构缓存空 parts 阻断 #g 条目发射** `streaming` `render` `shard-pilot` `regression`
  - 用户报告：流式中所有内容突然没掉又恢复、前文全部消失；卡片展开收起不稳定。真机定罪（连拍+语义树+探针三轮）：fire 后尾卡塌至 96px 且冻结 StreamChunk/StreamPrefix 条目从未组合——毕业的 2000+ 字无处渲染。
  - 根因：#g 条目生成的发布查找走 turnGroups 的 cm.parts——turnGroups 是结构缓存（id 生命周期签名），流式宿主 ChatMessage 捕获于创建时刻（parts 尚空，出生在后续 delta 批），签名不变缓存永不刷新 → 查找恒 miss。渲染管道（renderableTurns miss 分支修正）看得见 parts——两管道视野分裂。
  - 修复：发布查找改 turnKey 直查（PublishedShards.turnKey 同源注册期条目键），组遍历降兜底——绕开整类 parts 引用陈旧性。回归测试 StreamShardEntryEmissionTest（结构缓存空 parts 场景红→绿）；真机 E2E 毕业①len2179/h5065px 毕业②len2093/h4796px 冻结条目全高组合，视觉满屏连续正文零消失。508-shard 探针（组合+实测高度）按 keep-probes 裁决保留 DEBUG-only。
  - 展开收起不稳定主根因同源（内容消失+整视口条目churn）；修复后毕业重排仍在（冻结条目插入+尾块收缩=引擎配对合法转移）——待用户验收确认，若残余另立卡。
  - 复杂交互矩阵验收（2026-10-03 晨，8 场景全过）：T1 毕业时上滚阅读（读位历经完结换装纹丝不动）/T2 流式中展开思考卡保到完结（3 毕业穿过展开态，冻结块 5380/5084/3960px 全高）/T3 六连toggle 展开收起（终态正确零破损）/T4 排队第二问（双轮交接 fires 单调）/T5 Home 后台 6s 回前台（无缝，4 fires 单调）/T6 毕业后远滚驱逐+回滚（冷启 adopt 续账，冻结条目同长重组，继续毕业 6779→9062）/T7 9500+字压力（6 fires 单调 2047→12363，每刻内容在场）/T8 退出重进冷启（8 章+结语零缺口）。全程 20+ fires 全单调、零 reset、零 dropped、零换装 forensic miss。
  - 验收演示（2026-10-05 卡1）：用户判「无流式输出」完全正确——三层仪器坐实渲染冻结-完结砸出（服务器渐进✓/接收✓/渲染✗），独立立案 #513；本卡 verify 冻结至 #513 根修

- [~] **#506 思考卡计时拖满全程+展开内容困在 240dp 隐形滚动窗（#506）** `streaming` `dsh` `render`
  - 真机 t33/t34 定罪（用户报告）：①DSH 把 reasoning 的 block-end 压到整流结束才发（t32 抓包：思考 22:10:56 完，block-end 22:12:03.9 才到）→ time.end 迟到 67s → 计时跑满正文流式全程；②展开思考卡内容完整可达但锁在 240dp 内滚窗（无滚动条提示/流式不跟随），用户感知「展示不全」。
  - 修复：①DshEventMapper block-start(N) 顺手给前驱 N-1 发 TimePatch（块严格顺序抓包零交错实证；TimePatch 端 end==null first-write-wins，晚到的真实 block-end 自然让位）；②ReasoningBlock 展开区撤 240dp 帽+内部滚动=全内容高度（展开即看全意图；supersede 2026-08-16 240dp 裁决——同域最新用户投诉为准）。
  - 残余（登记待裁决）：完结换代（删合成+上权威）后 DSH 卡时长消失——转写块无 time 字段，跨消息转移需 temporal join，未在本卡实施。
  - 真机 t35 终验双证：①TimePatch 22:40:52.343 流中（正文起步即 reasoning 终态化）——计时不再拖满全程；②展开卡 768 字首段至末行一整块连续呈现零滚动。全量单测绿。等用户自然使用验收。
  - 残余已修（同批）：定罪修正——权威 part 带 time 但 start=end=事件时刻（恒 0 时长），非转写缺 time。mapper blockStart/blockEndTimes 记账（block-start 记起始+推前驱结束），整装结算真块时长。真机 t36 完结卡显示 5.2s 真时长（修复前恒空）。

- [~] **#505 流式重复短语误杀致换装门断裂——applyDelta endsWith 去重中段内容丢失（#505）** `streaming` `dsh` `render`
  - 真机 turn 30 定罪：模型 10 字重复短语恰等于累积尾部 → applyDelta endsWith 去重误杀 → 累积 3724 vs 终态 3734 中段缺口 → #504 换装门前缀断裂 miss → 200px 占位闪塌（h=200→9597）。
  - WS 抓包 t31 证 DSH delta 流==转写逐字节（无重复投递/无缺口）；journal 考古：endsWith 去重在两族服务器已文档化场景零保护价值（#266 案例它自己也没接住），纯防御遗产。
  - 修复：①applyDelta 撤 fuzzy endsWith 去重（Text/Reasoning，终态守卫保留）；②换装门加头尾锚容错（|gap|≤512 + 前缀锚≥256 + 后缀锚≥256 命中）；③forensic 探针升级（分叉位置+上下文采样）。
  - 真机 E2E turn 32 双证：①累积==终态 4658 逐字节相等（本轮 11 个 tail-equal 真重复——连续空格/数字重复位，旧代码把 100 吃成 10 的潜伏腐蚀——全保留）；②换装门命中 src=asyncInline + h=11866 d=11866 首测全高零闪灭。全量单测绿。等待用户自然使用验收。

- [~] **#503 流式尾段重建循环——A2 冻结分片毕业 fire 回卷（#503，已旗标稳定化待根修）** `streaming` `scroll` `regression` `shard-pilot`
  - 真机定罪（2026-10-02 用户验收：「输出到快结束的时候一直在重建循环」）：单轮尾段 9 次 MDPilot shard fire/9 秒，origin 回滚重冻结（2313×2、2629×2）+ turn 条目回收重组对（shard-unreg→shard-reg）——fire→条目churn（StreamChunk/StreamPrefix 插入+尾块重切）→条目回收→pilot 冷启重播→再 fire 的回卷循环；每循环=一次全量条目重建=用户所见重建循环。
  - 稳定化（已落地）：STREAM_SHARD_PILOT=false（dev+beta 下一构建；性能优化开关，回退单容器流式）。#501（part-birth，DELTA_BUS 旗标）与 #502（静止采纳）均不受影响——真机 E2E 验证 fire/回收全零、B3 2.3s 即燃、MDResize 全程连续增长、3856 测试 0 失败（6 skip=分片旗标语义臂）。
  - 根修方向（待做）：pilot 毕业的 resetKey/onRebuild 回卷链——fire 后条目churn 触发条目回收→coldStartPlan 重播已发布区间，需把毕业发布与条目生命周期解耦（如 fire 幂等去重/回收期 graduation 冻结）。附：同轮另见视口 0↔7 弹跳（24 LEAP，MSGEFFECT/GUARD 双确认静默、引擎 set 只指向阅读锚）——疑为条目churn 副作用，pilot 关闭后待复测；若仍在则另立卡片。
  - 根修落地（2026-10-02 深夜三段）：①Machine 回退宽限窗 300ms（瞬时陈旧快照不再单批 Reset 销毁已发布集——回滚环燃料掐断）；②fire 事务性（Broker.fire 回 Boolean + confirmFire 落账，未注册竞态下批重试，账本与发布态不脱钩）。真机 t36（6000字，旗标重开）：三次毕业 origin 严格递增 2050→4177→6185、零 Reset 零 dropped、reg=1/unreg=0——回卷消灭。STREAM_SHARD_PILOT dev 已重开交自然使用验收（beta/stable 仍 false）。

- [~] **#502 流式消息被裁剪在视口小块——配对让位永久化死锁冻结高度帽（#502）** `scroll` `engine` `streaming` `regression`
  - 真机定罪（2026-10-02，用户流式中有机复现）：流式上翻阅读期间引擎在阅读位配对 set(7,393) → 用户甩回底 (0,0) → shouldYieldPairing 判「外部 pending 未消费」永久让位（yield×1448/12s）——用户手势是已完成的外部滚动而非待消费 pending，读位永 ≠ lastSet 且只有引擎 apply 才写 lastSet → 鸡生蛋死锁。
  - 后果：每帧作废帽释放计划 → reserved 冻结 685 而真高涨至 4303 → item 按 min(真高,685)+clipToBounds 底对齐上报 = 流式消息被裁剪在视口一小块固定区域（最新文本在框内滚动），完结换装 reset 才全量展示（用户主诉）。#501 修复后 DSH 流式期有真实增量可卡 + 用户滚动测试凑齐配方才首次暴露。
  - 修复：divergence 静止采纳——读位连续两帧静止且 lastSet 陈旧（≥2 帧引擎无 set，防误毁 pending 保护）⇒ 采纳为配对基线恢复正常求值（帽释放当帧生效）。相位级测试确定性复现死锁三帧转换（帧3 旧行为永久 yield 处 → 采纳+帽 685→1128）；pending 未消费窗口回归锚钉住。单测 3856/0/0；三轮真机 E2E 无回归、无误触发 adopt。

- [~] **#501 DSH 流式正文结构性不可见——#230 空种子 × B案结构静默，完结整段砸出（#501）** `dsh` `streaming` `engine` `regression`
  - 真机验收 #442 时用户定罪（2026-10-02）：DSH 服务器流式正常（自建 WS 抓帧 940 帧实证：block-start 空种子→reasoning-delta 145→text-delta 778），app 正文整段流式期不可见、完结 assistant/message 才 +5675px 砸出。
  - 根因链：DSH block-start 空种子被 #230 零信息丢弃（防线本身正确）→ part 仅由 flush 的 applyDelta idx<0 兜底在热视图出生 → B案 UI 读 structuralParts（只在结构事件过桥），DSH 流式期零结构事件（纯 delta 线面）→ 出生永不过桥。SSE 不受影响（part.updated 携累积文本=结构事件不断过桥）；reasoning 可见是 text block-start 自身结构事件捎带过桥的巧合。
  - 修复：flushPendingDeltas part 出生过桥（cause=part-birth，每 part 一次；纯文本增长仍走 bus，delta 批结构性静默不变量不破）。真机验证：B3 探针 5.4s 即燃（原完结才燃）、MDResize ~66px 步长持续增长、shard-reg 流式期注册（A2.5 首次在 DSH 线生效）、CML-tick=0、完结无重复无脏行。单测 3850/0/0 双臂绿（新增 DSH 形态出生/单次发射 2 用例）。

## P1 — 核心功能需求


- [ ] **#512 DSH 密码登录插件（dsh-password-login）——免捞 token 的会话铸造/密码生命周期/轮换全员下线** `dsh` `auth` `infra`
  - 独立仓库纯宿主插件（照 keepalive 骨架，GitHub 分发，零客户端 bundle）：未设密码态回环免密铸票 + 密码登录书签页 + 轮换即撤销全体会话（admit 包装 epoch）；oc-beacon 与浏览器均为消费者
  - app 侧回落链（有效 Cookie→免密铸→带密铸→legacy token），ServerConfig.password 双语义零新 UI；四批实施（骨架/密码/撤销/legacy 退役）
  - 设计经五轮 grill 全裁决收敛；环境事实锚定 dsh 0.2.0-rc.2 源码实证
  - → [spec](docs/specs/2026-10-05-512-dsh-password-login-design.md)
  - 2026-10-05 追加裁决（卡片摘要中「零客户端 bundle」以此为准）：①web 登录改用浏览器原生 HTTP Basic 单密码弹窗（用户名忽略），自绘登录表单废弃；②密码管理面板回归 scope——客户端 bundle 注入 dsh 设置页，UI 一律消费 dsh 宿主 UI 原语（keepalive primitives.ts 先例），禁止引入第三方 UI 组件库；③自绘「记住我」废弃，记忆职责归浏览器密码管理器。spec 已同步（§A/§B/§E/§G/§K/Out of Scope）。

- [~] **#511 10h 人类行为模拟 + 高度栈覆盖审计收口** `streaming` `height` `soak`
  - mock LLM(12语料×7画像+5%断流)→v1-e2e:4299→真机随机化驱动器（10h 挂钟，2026-10-04 09:30 发车）；review.sh 每2h签名台账+死亡复活+过deadline终盘自动收割（cron automation-3e10101d）
  - 覆盖审计补口五组单测已落：Part.rekeyed 19子类契约/GrowLedger.relearnBaseline+伪增量对照/broker fallback缓存移除+clearAll/preParseStreamedTurnParts四门/swap遮蔽守卫；全量绿
  - 判读基线：#484 健康线（10h 零负向d 零RESETKEY）；终盘产物 human-sim-10h/reports/final_report.md（review.sh --final 自动生成台账节）
  - 终盘 PASS（21:00 收割）：10h 挂钟/有效 8.7h/全部巡检节签名全零/201 轮/app 零真实崩溃；两起崩溃风暴均为驱动基建陷阱已根修存档（journal §6 + review_log 两事故节）——留 verify 待用户验收
  - 验收演示反证（2026-10-05）：soak 判读对「爆发式正向 d」失明——MDResize d=h 单步出生满高=渲染冻结形态未被 #484 签名集覆盖（#513）；soak 的引擎域结论（零负向/零崩溃）仍有效，但「流式渲染健康」维度需根修后补验

- [~] **#510 审计驱动清理批次：pilot 即终态 + 三补偿族退役（#509 二期五项裁决）——渲染栈 −948 行** `chat` `refactor`
  - 用户质询只增不减触发全面审计：D2 根修=pilot 即终态（毕业不切渲染器，shardHold 语义泛化）；CompletionHandoff/pilotTerminalHold+freeze/replayHold/HeldTailReveal/swapStableKey 五族退役；bus 两 Gap 补口；13 处注释更正。真机三协议负向 RESIZE=0、短轮 104ms 残余构造性消失（28154286）
  - 收尾批（f3b4f886）：协调器键统一锚定式（§31 待察项根修）+ 死参数/broker 身份/账本换键三修复——审计可执行项全部清零，journal §32
  - 验收演示反证（2026-10-05）：卡1 #507 演示暴露流式渲染全程冻结-完结砸出（#513），soak 存档同为爆发形态——本卡「pilot 即终态+五族退役」落在嫌疑窗口（10-03~04），verify 态冻结待 #513 根修归因后重验

- [~] **#509 表格轮毕业换装塌缩窗——TurnFin 时宿主 item 满高→182px 存根→430ms 后弹回（贴底可见内容消失再出现）** `chat` `scroll`
  - 用户主诉面：「一会儿没输出、一会儿大段内容突然出现」。真机 2/2 复现（B2: h2197→182(d=−2015)→2221(d=+2039) 间隔 430ms；R2: h2670→182(d=−2488)），同一 182px 存根=条目壳高；纯文本轮（S6 持续 +66/+90 无塌缩）与 60 行代码块轮（B3 无塌缩）均免疫——表格解析路径专属
  - 机制链（B2 12:21:38.5-39.2 全取证）：Idle 后 pilot 卸载（len=1178→4）→ 塌缩 → path=preParsed chunked=false ×8+（len 620-2672）+ entries n=142 全量重组合 + 表格同步重解析 430ms → 弹回；#472 preParsed 快路径未覆盖表格条目（表格需分段渲染态重建）
  - 缓冲区疑虑已排除（用户问题正面回答）：数据级到达-渲染深度纯文本 max=26 字/p95=8、表格轮 max=65 字——管线无积压；爆发感全部来自本毕业塌缩窗（表格中流分段渲染正常：RESIZE p50 +66px 平滑）
  - 修复方向候选：a) 流式期预解析冻结形态表格（复用流式已解析态）；b) 交换保持——旧内容挂至新条目量测就绪（防塌缩窗，跨淡入）；c) #429 式 loading 过渡兜底。另需修 journal §26 的 TurnFin 全称表述（文本轮正当位移≠表格轮无缺陷）
  - 修复 v1+v2 已落地（单测绿+真机路径验证）：①replayHold——重灌中间态（4→5→15…前缀形态）期 pilot 分支保持+freeze 冻结旧内容（CompletionHandoff.replayHoldCandidate 纯函数+5 单测，LRU 防污 guard）；②换装桥长度门移除——takeIfMatches 命中轮任意长度走 asyncTerminal（Default 线程解析）+holdPilotTerminal 保持，主线程零解析（消除 syncSmall 的 350ms parseBlocking）。真机 v2a 实证 asyncTerminal 路径启用且零异常
  - 残余（未全愈，需深度裁决）：表格轮毕业仍有 350-700ms 空槽——v2a 定罪链：TurnFin(39.57)→h 2571→0(39.61)→asyncTerminal 渲染(39.96)→h 120→2595(40.28)；ItemP 证据=u_seq/t_seq 条目在毕业时刻 enter(39.61)/leave(40.31) 双重建——条目子树整体销毁使一切组合内保持机制（pilotEverRendered/remember 态）失效。属 #485(REST 快照归并)+#504(id 换代)身份翻覆族：合成→权威→REST 刷新链上消息 id/键翻覆引发 LazyColumn 重排湍流，锚链（#440 槽位锚→user id）随翻覆漂移。候选深修=内容指纹锚（user 文本 hash 抗 id 翻覆，需防碰撞）或数据层身份稳定化——重大手术另启批次裁决
  - 深修裁决（2026-10-03 用户）：选方案 B 数据层身份稳定化；方案 A 内容指纹锚暂缓。完整 handoff 见 docs/specs/2026-10-03-509-identity-stabilization-handoff.md（自含三层前因后果/主战场文件地图/设计约束/风险清单/验收标准/环境备忘）——压缩/新会话从该文档直接开工
  - 方案B 落地（45c9d9bb 已推送）：MessageIdSwapped 原地换名+part id 宿主前缀+流末预解析暖场；真机两轮表格零塌缩零空白零 ItemP、Room 重入零孤儿、V1 抽测过——verify 态待用户日常复验（关注点：表格/长文轮完结一瞬是否还有任何空白或弹跳）
  - 二期（28154286）：pilot 即终态落地——毕业不切渲染器，第一波 replayHold 已作为死码随批退役；卡片 verify 关注点升级为「完结一瞬零空白 + 滚动回收后重看无闪烁」

- [~] **#508 展开反射锚定归一分支丢一个 item 高度——中位小 item 构型展开恒 −1024px 视口跳变（违「卡钉住」裁决）** `chat` `scroll`
  - #466 normalizeExpandAnchor 链尽 fall-through 丢弃越界折算的整 item 高度：逆布局可见链只含锚 item 自身（新侧已滚过不可见），rawTarget=fiso+H 超锚 item 旧尺寸时返回 (锚idx, fiso+H−旧尺寸)，目标位恒短一个 item 高；实测展开位移 = H−锚item尺寸（与 fiso 无关，本例 1335−2359=−1024 两次复现同值），卡头 y1995→1074、上方条目逐出、~790px 空白带不自愈
  - 触发构型：中位 fii>0 且锚 item 尺寸 < fiso+H（T9 真机矩阵 2359px 分片条目首踩）；作者原设计场景 fii==0 半贴底时 item0 为流式巨轮恒大于 fiso+H，折叠分支从未真跑过——现有单测 normalizeCrossesIntoNewwardItem 把 (0,273) 错值钉成预期（注释称数学等价，实丢 747px）
  - 修复须辨卡片宿主 item：宿主=锚 item 时增长同遍落地 (fii,fiso+H) 恒合法（fiso≤尺寸 ⇔ fiso+H≤尺寸+H），无需预折；宿主在上方 item 时才需向旧侧链折算且用增长后尺寸——方向/尺寸双修正，BottomPinnedExpandSkipTest 三用例需重写
  - 根修完成（2026-10-03 07:46 真机三连试验）：normalizeExpandAnchor 重写为宿主感知——折叠方向转旧侧(idx 递增，逆布局折叠正方向)+宿主容量+H+宿主未知按锚兜底；CML itemsIndexed 逐 item 提供 LocalCardExpandHostKey；单测重写（旧 normalizeCrossesIntoNewwardItem 把 (0,273) 错值钉成预期已纠正，新增 T9 数值化回归钉子与 host>锚 AP 不变量双例）。真机验证：触发构型（item15 增长前 2359 < rawTarget 2716/3586 两档深度）下 host=15=锚 → LRef 原样写 off=2716/3586（旧码会写 357/1227 造成 2359px 跳变），卡头 Y 三例 1307/2177/1930 全纹丝不动，零空白带；收起镜像 1381=2716−1335 精确不变。commit 待推

- [~] **#504 DSH 完结换装闪塌——seq 换装重键 Markdown 状态致 200px 占位 260ms（#504）** `dsh` `streaming` `completion` `regression`
  - 真机定罪（19:02:33 帧级）：流式卡 8754px → MessageRemoved+seq 换装 → 新 part id（dsh-tXs1_text_ord_1→seq-N_text_ord_1）重键 Markdown 记忆 → 8KB 文本异步解析先以 200px 占位合成 → 260ms 后回弹 8778px。用户所见「结束时出现一次」的塌-弹。SSE 路径 part id 恒定无此症——DSH 合成 id→权威 id 换装特有，#485 完结闪灭家族的 DSH 变种。
  - 修法：PartContent 文本分支完结桥接——bus live 清除瞬间不立即跌回 part.text（异步占位），保留渲染上一帧 live 全文（与终态文本一致）至终态 Markdown 解析完成原子交接。零闪塌零额外解析。
  - 修复落地（2026-10-02 深夜，五轮真机取证迭代）：①Compose 派发次序定罪（旧节点 onDispose 恒晚于新节点 remember）→dispose-stash 改活跃指纹登记；②库 StreamingMarkdownState 对新收集器零重放定罪（hold 渲染空态 200px）→状态实例交接改指纹门+换装帧同步解析（rememberSyncMarkdownState，normalizeForRender 同源视觉恒等，一次性 ~10ms 主线程）；③严格相等恒 miss 定罪（pilot 终帧落后终态 2 字符）→尾差容错 512；④prefix=false 定罪（完结对尾部区域 ~16 字符改写非纯追加）→公共前缀+尾部重写松弛 256（分叉点须落两串末 256 内，中段分叉恒 miss）；⑤单槽 last-writer-wins 定罪（推理块终态 1165 抢占正文 3988 指纹）→多槽 LRU×4 全槽遍历。全量 3863/0/0（+7 交接门用例）；[504-forensic] 取证探针 DEBUG-only 永久保留（keep-probes 裁决），miss 自动吐槽况。最终换装 E2E 被当夜无线闪断阻断——留用户自然使用验收，探针自证。
  - 真机终验通过（21:33，K8s 2500字长答）：换装帧  首测即全高——零 200px 桩、零回弹（修复前形态 h=200 d=200→d=10330 回弹）；换装时刻零 forensic miss（指纹桥命中）；同步解析成本无感知帧损。#501-#504 四连修全部真机闭环。
  - 深层根修补充（同批）：part 组合键归一化（PartIdContract.swapStableKey——派生 id 取 kind+ordinal 后缀，消息级 t_ 已有 #440 槽位锚）→ 换代子树存活，#472 hold 机制 DSH 首次可用，指纹桥降为二线。真机 t36 换装帧首测全高 h=17554 零闪。

## P2 — 优化与锦上添花

- [ ] **#495 TODO 入口空态泄漏（V2/DSH）——todoCapable 死状态与 #85 登记矛盾** `dsh`
  - FAB TODO 入口无能力位门控；ChatViewModel.todoCapable 探测全仓无消费（死状态）；V2/DSH 入口常驻但 TodoSheet 恒空态（DSH getSessionTodos 恒空表=探测成功）。
  - v1-v2-differences #85 明文要求 V2 隐藏 Todo 入口未落地——2026-09-07 审计 E-1 漏网项；修法=入口挂能力位。
  - → docs/research/2026-09-30-server-type-uiux-consistency-audit.md §四D1

- [ ] **#494 DSH 权限预设下拉夺焦点收键盘——与 #361「菜单不得收键盘」裁决冲突** `keyboard`
  - PermissionPresetSelector 用默认 DropdownMenu（focusable=true）→ DSH 输入框聚焦时点权限 pill 键盘收起；同屏 busy 气泡已按 #361 显式 focusable=false 保键盘。
  - 仅 DSH 渲染该控件 → 同类轻量选择两套键盘策略按端不同（用户键盘不一致感知的真正近亲）；修法=同款焦点策略或非夺焦点容器。
  - → docs/research/2026-09-30-server-type-uiux-consistency-audit.md §四D5

- [ ] **#493 模型抽屉键盘保持——切模型不收键盘（三端共享改造）** `keyboard`
  - 真机实测三端一致为「收起→抽屉→回弹」（ModalBottomSheet 独立窗口必夺焦点）；2026-09-30 用户裁决基准=不收键盘。
  - 实现方向：换主窗口内非模态呈现（Popup(focusable=false) 容器或布局内 BottomSheet），保留返回键/点外关闭、scrim、75% 固定高、#379/#405 手势隔离语义；共享组件=三端同改（影响面已裁决）。
  - → docs/research/2026-09-30-server-type-uiux-consistency-audit.md §一

- [ ] **#489 FileViewer 语法高亮 span 多染一字符（PhraseLocation.end exclusive 语义误用 end+1）** `ui` `bug`
  - 高亮影响面调研副产物（2026-09-30）：highlights 库 PhraseLocation.end 为 exclusive 语义（官方 README emphasis(13,25)→ExampleClass 占 13..24 + NumericLiteralLocator 测试双证），HighlightBuilder.kt:39 的 end+1 使每个高亮短语尾部多染 1 字符
  - 证据链与修复建议见 docs/research/2026-09-30-code-syntax-highlighting-impact-analysis.md §1.1.d；修复=去掉 +1（一行）+ 对照既有单测；注意与聊天域高亮组件（#488）的防御写法保持同语义

## P3 — 观察与低价值改进

- [ ] **#500 debug 通道热启动失效——app 前台时 am start 携 debug_url 不生效** `debug-channel` `dsh` `tooling`
  - 真机实证（2026-10-02，dev debug 包）：app 已前台时 am start --es debug_url http://127.0.0.1:3080 --es debug_server_type dsh，am 提示 intent delivered to top-most instance，但 handleDebugProfileIntent 未执行——无 Debug channel requested 日志、服务器不切换；force-stop 后冷启动同参数立即生效。
  - MainActivity launchMode=standard（manifest 实证）；疑点：standard 模式下复用顶层实例不回调 onNewIntent，而新实例路径亦未处理——需源码定位送达路径。
  - 影响面：自动化脚本未先 force-stop 时静默失效；debug-entry.sh 冷启动已规避。附：adbd 会把 ShellService 命令行（含 --es debug_token）回显进 logcat，注 token 后须 logcat -c 清洗。
  - 勘误（2026-09-30 盘点）：「新实例路径亦未处理」疑点过时——MainActivity:291 onNewIntent 已实现且 :298 调 handleDebugProfileIntent（onCreate 路径 :184 亦有）；真因待定罪=manifest 默认 standard 复用顶层实例不回调 onNewIntent 与实证吻合，为何未走新实例 onCreate 需真机送达路径取证；修法候选=singleTop（动 manifest 需评估返回栈语义）

- [ ] **#499 cleanup**
  - 摘要补全（2026-09-30 盘点，正文原落 journal §7.7）：过渡装备清扫——JankHold/STREAMING_MD_PILOT 等 B案后死代码整体退役（probe 按 keep-probes 裁决保留）；前置=#442 验收+旗标提升后执行；#510 已退役五族不含此二者（五文件仍有引用）

- [ ] **#498 render**
  - 摘要补全（2026-09-30 盘点，正文原落 journal）：CJK 粗体闭界——闭界星号后紧跟 CJK 字符（如 **……。**学）渲染为字面星号；服务端原文+markdown 库 flanking 行为，旧路径同渲染非回归（journal 2026-10-01-442 §7）

- [ ] **#497 子会话底栏三形态分裂 + DSH composer 异步挂载焦点扰动** `subagent`
  - 子会话底栏：V1/V2 整块空白（无任何说明）vs DSH 只读提示行/可输入 composer——同意图三形态；readOnlyHintVisible 现要求 subagentsSupported，V1/V2 不给提示行。
  - DSH subagentMode 异步解析期 composer 先缺席后出现（ChatTextField 无 FocusRequester）→ 焦点/键盘重置——键盘行为按类型不同的真实结构源；对齐机会=V1/V2 也给只读提示行。
  - → docs/research/2026-09-30-server-type-uiux-consistency-audit.md §四D3/D4

- [ ] **#496 空闲长按发送键三端语义分裂——DSH 死手势无反馈** `interaction`
  - 空闲长按发送键：V1/V2=切 shell 模式；DSH=静默 no-op（连提示都没有，比能力位隐藏更差）；2026-09-07 审计 A-4 挂 ASK 至今未裁决。
  - 需裁决统一语义：能力位分派维持（DSH 给 toast 说明/换 steer 入口）或全面重设计。
  - → docs/research/2026-09-30-server-type-uiux-consistency-audit.md §四D2

- [ ] **#488 markdown 能力补齐批次：块内HTML隐形+代码语法高亮+真数学渲染+setext stage-2（远期清单正式立卡）** `markdown` `render`
  - 2026-09-30 #487 后续深调定罪三项能力缺口：①块内 HTML 渲染为空（mikepenz v0.45.0 基础+m3 AAR 零 HTML 组件，二进制 grep 实证；整消息 HTML 走 looksLikeHtmlPayload 预览通道但混排块隐形）②代码块无语法高亮（走库默认 code renderer，build.gradle.kts:249 注释明示）③\begin/\[/712321 数学均降级为文本或围栏（transformMathFallback 文本降级 + \begin 无变换字面流）
  - setext stage-2（升格行级定案）维持 accepted-gap 备查（spec 2026-09-30-471-3 §3.6）；此前散落 handoff 未立卡，2026-09-30 正式收编
  - 实现方向：覆写 markdownComponents html 钩子（原文呈现或接预览同款）；高亮评估 league/ prism 类纯 Kotlin 方案（M3 禁引入额外 UI 依赖库红线内评估）；真数学需渲染层 KaTeX 级能力或维持降级+标注
  - 2026-09-30 代码高亮子项可行性调研完结（docs/research/2026-09-30-code-syntax-highlighting-feasibility.md）：有条件可行，推荐引入 mikepenz 官方同族 multiplatform-markdown-renderer-code:0.45.0——版本零升级（pin 的 0.45.0 即最新，-code POM 与现状逐项一致）、零新第三方实体（语法引擎 dev.snipme:highlights 1.1.0 已因 FileViewer 在 APK，R8 keep 就位）、净增 17KB；fence 语言 v0.45.0 已解析只差消费（markdownComponents codeFence 正门接入，「league/prism 类纯 Kotlin 方案」评估答案即此库）
  - 落地条件与风险：user 气泡豁免（primary 背景预设色板不可读）+自建 M3 令牌 SyntaxTheme（键控动态色/AMOLED）+span 区间防御（官方 issue #415 反向区间崩溃先例，移植 HighlightBuilder 写法）；流式期大块高亮作业整块重启→快速流下停留纯色到 EOF（降级优雅不卡主线程）+完结换装一次纯色→着色 pop（#472 语义需评估）；json/yaml/html/sql 不在引擎语言表→静默纯色（不劣于现状）
  - 2026-09-30 影响面分析完结（docs/research/2026-09-30-code-syntax-highlighting-impact-analysis.md）：路线修正——不引 -code 依赖（span 防御/M3 主题/打点三处定制全落其 v0.45.0 private 区，可见性直证），改仓库内自建组件（core public API MarkdownCodeFence + 已在依赖树的 highlights，~120 行 fork + CodeSyntaxTheme 映射纯函数 ~50 行）→ 零新依赖，UI 依赖禁令裁决面消失；user 气泡豁免是伪问题（用户消息走纯 Text 不进 MarkdownContent，PartContent.kt:144-154）
  - 影响面总评：小切口多波及——必改 2 文件（build.gradle.kts 注释 + MarkdownContent.kt 三点位 ~40 行）+ 新增 2-3 文件 + 测试 2（~120 行），但 components 单例使高亮一次性波及 8 调用面（assistant 双路径/Reasoning 流式/工具卡×2/通知卡/压缩卡/预览对话框），与流式重启节律/高度引擎 Bold advance/主题 remember 键三套承重机制交叉；实施三批（S/S-M/M），批 1 真机判据复用 #487「50 行 Kotlin」高度单调性；顺带发现 FileViewer HighlightBuilder.kt:39 end+1 多染一字符存量偏差（#489 另立）
  - 2026-09-30 阶段三处置：①块内 HTML 原文呈现已落地（custom 钩子覆写+真机 E2E 实证原文可见）④setext 维持 accepted-gap（卡原文语义，技术论证入 journal）②③方案文档 docs/research/2026-09-30-488-syntax-math-options.md——两裁决点待用户：②高亮走官方 renderer-code+highlights（新增两依赖）还是自写 top-5 lexer；③数学走降级+着色/标注升级还是维持现状
  - 2026-09-30 ①块内 HTML 原文呈现用户验收通过（模块 C 演示『这算是对代码块的修复吧…没啥问题』——效果确认：HTML 内容以代码块样式展示原文，修复前整块隐形）；①就此收口，卡整体待 ②③两裁决点拍板后一并收尾
  - 2026-09-30 用户裁决（②③双双拍板）：②代码高亮走仓库自建渲染壳——零新依赖（core 公共 API MarkdownCodeFence + 已在依赖树的 highlights 引擎，~170 行自建 + CodeSyntaxTheme M3 映射纯函数）；官方 -code 库因三处必需定制（span 越界防御/M3 令牌主题/流式观测打点）全落 v0.45.0 private 区被否，澄清要点=两路线同一引擎只差渲染壳
  - ③数学渲染走方案 c：降级+标注升级（「公式」徽标明示局限 + 可选 \命令/花括号/上下标轻着色），零新依赖零架构变更，真排版留远期观察 KMM 生态；②③裁决既定，卡转回 [ ] 开工——实施按影响面分析三批（S/S-M/M），③可并入批 1 或随后小步
  - 2026-10-01 批 1（最小着色）落地：自建壳 HighlightedCode.kt（fork -code v0.45.0，theme 入 produceState 键/每作业新建 Builder/区间守卫/CodeHL 打点四差异）+ CodeSyntaxTheme.kt（M3→SyntaxTheme 9 角色）+ MarkdownContent 三点位 + 单测 14 例；全量 3764 绿；真机 E2E 全判据过（流式逐批着色 ms≤27/MDResize 全正单调/守卫零触发/主题三态无残留/json 静默 spans=0）——journal 2026-10-01-488-syntax-highlight-shell 批 1 节；②余批 2（观察精修）/批 3（可选），③（数学标注）未开工
  - 2026-10-01 批 1 用户验收通过（「ok 可以」）——②最小着色收口；同会话续开 ③数学降级+标注升级（方案 c：降级块「公式」徽标+可选轻着色）
  - 2026-10-01 ③数学降级+标注升级落地：transform 产物围栏 tex→math（专属识别位，不误伤手写 tex 围栏）+ SafeHighlightedMathBlock（「公式」徽标 math_block_badge×15 语言 + \命令tertiary/花括号onSurfaceVariant/上下标secondary 手写轻着色，静态完结内容 remember 同步构建）+ 单测 12 例新增/transform 断言改标，全量绿+i18n 917 keys PASSED；真机 E2E（big-pickle 会话）：Code block, math 徽标块+dual族像素+vision 三色判读+亮色重建无残留+单美元守卫——journal 2026-10-01-488-syntax-highlight-shell ③节；环境坑：v1 无 zhipuai 凭据（新会话默认模型 401，改用既有 big-pickle 会话）；③ 就此收口待用户验收
  - 2026-10-01 ③用户验收通过（「可以 我觉得可以暂时用这样的效果」）——降级+标注形态就此定局；批 2（观察精修）/批 3（可选语言标签+复制）维持挂卡随观察窗推进，不阻塞

- [ ] **#486 SSE retry 重建帧 watch（原#471④）：断连续传+滚出视口弃树+ever=false 理论盲区，复发再战勿主动开工** `scroll` `chat`
  - 原 #471④ 用户裁决 c 降级观察（v1+v2 四场景不可复现，331k 行取证 Loading=0，#472 hold/registry/preParsed 防御栈有效）；#471 整卡迁移后观察线独立成卡（spec §8：watch 不关）
  - 理论盲区：断连续传+滚出视口弃树重建+ever=false 帧（0 触发）；若复发，残余只剩 staged/换装差（-1330px ③族已消失）——spec §8 残余收窄注记
  - 判读取证：MDResize 负 d / RESETKEY / nonPrefix / rawTail 探针均保留 DEBUG-only（2026-09-30 用户裁决）

- [ ] **#442 高度引擎根修二期：R2分片增量化(滑动p90 12ms)+cadence收编+flush深拆+终审待复核项** `perf` `refactor`
  - 终审判定：R1批次已锁 A2 贴底5ms/A1回归/A4全项；滑动p90 19-27 未达12——R2分片(稳定块缓存/尾块单测)是 O(内容)→O(尾块) 唯一路径。
  - Medium欠账：cadence结构收编(spec裁决3)、flush八职责深拆(终审S2)、StreamingPairingRule缝退役迁移。
  - 待复核：ScrollQuiescence单例假设、HeldTail锁高裁剪视觉等价、diffDisplayItemsInto边界、SSE铁律逐条。
  - 2026-09-29 用户裁决:吸收 #445 并入——R2 深水区含流式 markdown 稳定/活跃双容器状态管理方案(append-only+前缀吸收,固化解迁移帧问题);随卡迁入用户观感记录:完结窗会小跳一下(2026-09-27,限速节奏参数 BIG_RELEASE_MIN_INTERVAL_MS 可调,视情况修)
  - 2026-09-30 吸收 #470 域（用户裁决）：帽不回改+ledger 负向不配对两路径随 R2 一并设计（ScrollCompensation.kt:338/:157）；当前实证 4h 重度使用零负向高度事件，不阻塞
  - 2026-10-01 二期开工（journal 2026-10-01-442 §1）：销账——cadence 收编(5d061df2)/缝退役(12513603)/待复核四项(四十六世轮)已完成；调研 §3.8 失活防御已被 #438 R-2 修复(FlushTaskMemoryTest 在案)
  - 2026-10-01 批次 C 落地：flush 八职责深拆(FlushTaskMemory/reservePhase/vtraceTick/applyPairedShift，表征测试 7 例先行钉行为+引擎域 47/47+全量 3787/0/0，日志签名集字节不变)+cadence 文档漂移修正(48ms→100ms)；主体 R2 分片唤醒(批次 A1 毕业计划纯函数→A2 装配层多 item→A3 帽/配对适配→A4 真机 p90)进行中
  - 2026-10-01 批次 A1+A2 内核落地：毕业计划纯函数(空行块边界贪心打包/冻结append-only/单调/门槛上限重置，8例) + 武装/触发状态机(影子态毕业时机决策件，6例) + 施工 spec(docs/specs/2026-10-01-442-r2-shard-awakening-design.md：影子态零闪烁换装/键族/帽reset/切割高度恒等假设/完结持续性)；全量 3801/0/0。下一实施批 A2 装配层接线→A3 换装收口→A4 真机 p90
  - 2026-10-01 批次 A2 装配落地（journal §4）：STREAM_SHARD_PILOT dev 开关+broker 单例(partId 注册/发布/完结兜底)+StreamChunk 逆文档序发射(尾块原键零迁移)+StreamShardContent 同步解析渲染+pilot 切尾坐标(Fire=帽hardReset→发布→重建快灌同协程步,冷续单帧,完结持续 shardHold)；测试 9 新例+全量 3810/0/0
  - 已知边界：Fire 重建窗待 A3 影子态、非 text-leading turn 不分片、真机 A4 未验
  - 2026-10-02 A4 烟测两轮（journal §5）：16/14 次 shard fire 全链活、tail 恒小(≤800ch 单帧重建)、视觉完整性三视口过、混合窗 p90=12ms；P1 全文预解析双渲染(96→49390+完结踢飞 pilot)热修(已发布 part 抑制预解析，EOF flush/回收冷续复测过，3810/0/0)；P2 覆盖缺口：text-leading 资格排除推理先行轮次(37K 正文零分片实证)——A2.5 泛化待办(renderItem 级拆分)；正式 p90 判定未做
  - 2026-10-02 用户对齐（重组风暴缓解/根修分界，防账面混淆）：JankHold+签名缓存=缓解（只盖滚动窗+单链，二十五世轮根因二原文）；全链重组彻底根修=B案节奏收编（SSE 原始 delta 直入引擎，绕开数据层 StateFlow→ChatMessageList 快照重组链，spec 2026-09-26 架构1+2/预留高度表）。A案分片只修根因一（帽全量测量）+item 级重组面（冻结块零重组零重测），不消除每批顶层重跑（ChatMessageList god file 拆分另在 #481 在册）。既定次序不变：A2.5 资格泛化→A4 正式复测 p90→不达标升 B 案（#445 载体）；用户保留将 B 案提前的选项（先根治重组链而非先拿帧率数字）
  - 2026-10-02 B案适配性系统分析完成（用户指令：只分析不改动；journal §6 全文）：**结论=适配且接缝成熟度高于 spec 时点**——实质是「JankHold 已验证的滚动窗终态（快照静止+item 级增长）常态化+增量喂送搬到冻结点前」，非新架构。关键实测：每文本 flush 唯一发射源=_parts（getMessagesFlow 纯内存热视图，Room 不回流）；chatEntries 已不随纯文本增长重建（签名缓存生效）；残余=combine O(n)+ChatScreen 投影 O(n)+ChatMessageList 函数体重跑（根因二）。九项接缝就位（单一咽喉/三分离/broker 先例/pilot 机器/完结换装三件套/cadence 常量已归引擎域等）。架构2 预留高度表建议维持 parked（A案冻结分片已等价达成其测量面目标）——B案提前范围裁为架构1 节奏收编单干。R1-R9 风险均为 spec 批裁决点（R1 reasoning 必须进范围：glm 系推理先行是常态）。批次草案 B1-B6+根修判据（流式稳态函数体重执行 ~10/s→≈0）已录 journal §6.7；次序裁决（A2.5/A4 与 B 正交可并行）留用户
  - 2026-10-02 B案节奏收编+A2.5 全量落地（六 commit d9f128b4..8a9eff20，journal §7 全证据）：数据层 structuralParts+StreamingDeltaBus 双出口（_parts 热视图零变更）、UI 五消费面切 structural、PartContent 两分支 live 覆盖、A2.5 推理先行轮 renderItem 级拆分（StreamPrefix#p+尾块切片+提问分工）。**根修判据达成**：CML-tick 探针关关基线流式稳态 ~4.8-6/s → A+B 开（含两真机定罪热修：①dispatch 尾 Delta 例外发布——flush 后首 delta 击穿结构性静默；②jkHold 按旗标退役——holding 直读驱动 ChatScreen 重组）轮次启动结构性窗后 ≈0/s。B5 烟测全链活（text/reasoning 双模型 fire+EOF+prefix 渲染实证+零重复零 FATAL）。A3 影子态裁决=放弃（tail 恒≤800ch）；架构2 维持 parked；A4 正式 p90 专项方法学仍开放（gfx 配对窗噪声主导，帧级收益被光标动画/铺开期掩蔽）。全量单测 3824/0/0。过渡装备清扫（JankHold 等）留独立批次
  - 2026-10-02 A4 正式判定补做完毕（journal §7.5）：降噪方法学=framestats 120 帧环分块（消除光标长窗稀释）+稳态期分段（append 活跃验证）+双臂同协议 8 循环取中位。**A+B 臂混合窗 p90=7.6ms ≤12ms 目标达标**；关关基线 p90=10.4/p95=14.3ms（改善 -27%/-40%，尾部块收敛 7.1-8.0）。先前摘要百分率配对结论作废（方法受污染）。#442 A 线（A2.5 落地+A4 达标+A3 裁决放弃）与 B 线（B1-B6 全量+根修 CML-tick 实证）至此全闭合，待验收
  - 2026-10-02 全面覆盖批（f7ca309d）+重进场景族真机实证（journal §7.6）：+24 分支测例（非终态保留/REST 后重发布连续性/no-op 去重/资格 7 分支/锚定 10 分支），全量 3848/0/0；全路径动机埋点（[MOTIVE] 测试行 + [B2-struct]/[B2-bus]/[A2.5]/[B3]/[B4] 生产探针带动机文案）。**重进三场景实证**：①流中退出重进（推理期最对抗路径）——REST 刷新清 bus→同帧 structural 兜底→下 flush 重建→override 探针闭环可见，流零中断轮次正常完结；②完结重进完整；③深滚回收零异常。输出消息重进问题确认解决
  - 2026-10-02 终审完整性审计（journal §7.7）：揭出并修复 P0 级缺陷——beta/stable 自 A2 起编译失败（旗标字段 dev-only 但无条件引用），三 flavor 编译任务验证过；双臂单测纪律补全（Assume 守卫后开臂 3848/0/0、关臂 3848/24skip/0 均机器可判）。需求终审表逐条闭合：仅 #470 B/A（用户裁决挂起）与「完结小跳复验」（#472 机制性已修）两项非代码残留；beta/stable 旗标提升为发版裁决。清扫独立卡已立
  - 2026-10-02 #470 帽回改 B/A 用户裁决落章：选 A（维持空白——帽不回改+视口不跟随；4h 零负向实证为据，遇「回缩后大片空白」不适再立卡升 B）。同轮补测流式中点停止 E2E（journal §7.8.1）：中断链全绿（abort 请求→interrupted/step.failed→FSM Idle→终态过桥→bus 清除→「已中断」状态行+部分正文保留+零 FATAL）；观察项=session.execution.interrupted 事件未映射（无 UX 影响，可随 #459 顺带）。至此 #442 唯一用户裁决类残留=旗标提升 beta/stable（发版裁决）
  - 2026-10-02 旗标提升 beta 执行完毕（用户裁决）：STREAM_SHARD_PILOT+STREAM_DELTA_BUS beta=true（ad9cb70a）；发 v0.4.0-beta（force-bump=minor 开新线避 v0.3.1-beta 历史 tag 碰撞；release.sh python 派发器顺手修复 4bded7f9）。#442 至此仅剩用户验收（dev 日常使用）→ migrate；stable 旗标随下一批
  - 2026-10-02 v0.4.0-beta 发布完成（§6 验证全绿：APK/版本/签名/说明）：首次 CI 撞 lint 门禁四错（间距令牌×3/remember Unit/组合期 StateFlow.value——本地漏跑 lintDevDebug 预检教训），aae6b576 清零后 tag 重指发布。beta 渠道自此带 R2 分片+B案重组根修+A2.5；#442 仅剩用户验收→migrate；stable 旗标下一批
  - 旗标现状注记（2026-09-30 盘点）：beta STREAM_SHARD_PILOT 随 #503 稳定化自 v0.4.0-beta 发布态回退 false；现 dev=true/beta=false/stable=false（DELTA_BUS dev+beta=true）。beta 重提升+stable 首提=同一发版裁决，门控 #503 根修验收

- [ ] **#464 UI 暖态下列表/卡片点击偶发失效(冷启可靠)** `chat-ui`
  - 2026-09-29 #461/#462 取证副产物:force-stop 冷启后输入 tap 可靠命中(会话行/卡标题),同一 app 暖运行数分钟后点击同坐标零效果(无日志无 UI 变化,vibrator 反馈存在=命中可点击元素但未触发业务);两次独立取证会话复现,冷启后恢复。疑点:点击消费被某 overlay/焦点态拦截或状态门;影响面=自动化测试可靠性,人工使用未报告。待真机复现窗定罪(diagnosing-bugs 流程),暂无用户主诉不阻塞。
  - 2026-09-29 审计顺带实证:一次拖动送达零响应不再现(见 #465 注记);#477 dump 全盲为坐标类判读新增干扰源——本卡与 #465 高度重复,建议合并为单卡观察项待裁决
  - 2026-09-29 用户裁决:吸收 #465 合并,本卡为该域唯一观察卡(暖态间歇 input 送达零业务响应;今日一次拖动零响应不再现实证在案;#477 dump 全盲为坐标判读新干扰源)——再撞上即现场抓 input dispatcher+app 双侧日志

- [ ] **#477 长文 markdown 块 uiautomator 语义零暴露——dump 全盲致渲染正常被误判空白** `ui`
  - 2026-09-29 收口审计真机定罪:末轮长文(async parse 路径)可见区 dump 零文本节点(仅 1 个空 text 可点击 TextView h≈1921px),同期旧 turn 正常暴露;vision(三路交叉)+像素双验证内容完整渲染(连续正文/无空白/无圆点)——渲染层无恙,纯语义暴露缺陷
  - 影响面=自动化验证可靠性+#467 dump 实证段污染(核心 SGR 日志证据不受影响)+a11y;待定位暴露分叉(疑 rememberAsyncMarkdownState 路径)
  - 证据:/tmp/ui_b1.xml(底,盲) vs /tmp/ui_top.xml(顶,正常);#475「近空白/折叠态」原判即此 artifact
  - 2026-09-29 定罪深化(见journal):分叉=单part纯文本长度(同turn 993暴露/3585盲,表格组件不受影响);渲染三层同构排除(组件/库重载/success槽);超视口单item假设被未分段表格turn(3674px)暴露推翻;最强嫌疑=SPLIT_PARAGRAPH 3000拆段线;androidTest断言已写但被MIUI+idle挂死双阻塞;修复候选A完结即分段/B到达即分段/C拆段线定罪后修normalize/D平台上报——待用户裁决,不盲改
  - 2026-09-30 定性翻转(用户裁决B降级观察):今日冷启进程两会话十几个turn(0-20k chars全路径)零盲例——五假设全推翻(拆段线:3585原文标准段落格式未触发/Q换帧:7523混合part同经换帧暴露/长文=盲:4077五段纯散文注入段段暴露/路径=盲/密集dump自伤:10连dump后仍暴露);原定罪(长文语义零暴露)重定性=疑似长跑进程状态性退化(R假设:昨21:22同屏老表格turn暴露vs新essay盲分野=内容创建时刻;盲取证全部集中于4h+单一进程晚期);再撞上(长跑进程新组合内容dump全盲)即抓:logcat a11y错误+dumpsys meminfo+uptime;A11yDiag探针留树备用

- [ ] **#473 调试探针族清扫——历代 campaign 遗留 DEBUG 打点归档** `chore`
  - grep \\\[DEBUG- 盘点:CardExpandReveal(#420-427/466)/ChatMessageList+MessageCardAssistant(hflick/jk)/SafeFlingBehavior(flng)/ChatScrollController(drift)/rbexp 等几十处打点,均 BuildConfig.DEBUG 门控、release 零影响,但污染调试 logcat(472 闪烁定罪时曾混入噪音)。逐族清理+保留关键结构注释;涉及文件多有编辑协议约束,单独批次执行。
  - 2026-09-30 精化+新增三条方法教训:①「retained-subtree 盲区」实为 A11yDiag 语义=组合事件(内容经 pilot/preParsed state 对象流动时外层组合体跳过),内容级探针 MDPilot/ChunkDiag 一直覆盖,r3 实证 243/38 条——无真盲路径,改判读口径即可;②device 侧 grep 命令文本被 adbd in ShellService 记入 logcat→下轮自匹配假增长,监控必须 pull 到宿主 grep;③设备 nohup logcat 的 pkill -f 自匹配自杀(pkill 按进程名);宿主 nohup 不挺过 run_code 退出
  - 2026-09-30 用户裁决：由于特殊情况先保留（不关闭，与探针永久保留裁决的冲突挂起由用户自持）

- [ ] **#454 v1 真机 IME 换行注入后 prompt 未发出** `chat` `device` `v1`
  - 真机 IME keyevent 66 发送路径:消息含注入换行(Run\n\n)时 prompt POST 未发出,乐观气泡悬挂;二次干净发送正常(prompt_async 202)。发送链路疑有 IME 竞态边角,#453 验证时顺带观察,未复现第二次

## P4 — 外部前提阻塞

- [ ] **#381 192.168.110.248:248 重配——凭据宿主零痕迹不可探查（2026-09-09 用户裁决入 P4）** `infra`
  - **前提**：上游提供远程凭据探知能力——用户原话「后续看看 dsh 官方是否会支持远程探知 token 或其他认证手段」（DSH 官方远程 token 发现，或 OAuth/设备码流等替代认证）；前提满足后 `cred-probe.sh opencode 248 <名称>` 即可接管（/proc 探针现成）；若上游确认不会支持，再议弃用或人工一次性重配

- [ ] **#352 长按菜单「取消归档」——wire 层无恢复动词（2026-09-07 用户裁决要求，服务器阻塞）** `dsh` `archive` `ui`
  - 裁决原文:「归档单向契约同删除一样在长按弹出框中增加即可」——用户要求已归档行长按菜单加「取消归档」
  - **前提**：上游 dsh 服务器提供恢复动词——实测证据（2026-09-07 深夜，当前部署源码 dsh-api-workspace-controller typert）：WorkspaceArchiveSessionRequest={sessionId} **add-only**，全 API 面仅 archiveSession 一个归档动词，官方 web 客户端同无恢复入口（SessionRowMenu 2026-09-05 四重取证注释仍有效）；动词就位后：菜单项+RPC+已归档折叠区行刷新一步到位（#351 能力位先例同款）
  - 2026-09-29 前提重验:dsh 部署版=0.1.7-rc.2(=npm 最新) dist 全仓仍无 unarchive/恢复动词——前提不变,维持 P4

- [ ] **#332 spill 提示行——服务器无结构化信号（工具结果溢出 notice 内嵌纯文本）** `dsh` `sse`
  - **前提**：dsh-spill-policy 全链查实——溢出替换为有界 head/tail 预览+locator 提示全部内嵌工具结果 output 文本,transcript 无 spill 事件（session 事件枚举/types/实现三路 grep 0）;文案模式匹配脆弱（#136 先例:服务器改文案即静默失效）。待服务器暴露结构化字段再实现。→ `docs/research/2026-09-05-audit-309-313.md` #312③ + 实现 agent 取证（暂不可实现）

- [ ] **#288 workflow 阶段卡（tool-workflow agent-start/end 聚合渲染）** `dsh` `ui`
  - **前提**：dsh 服务器在任何客户端面暴露 tool-workflow 运行事件——events.mux 实况 / session.history journal / session.projection / session/jobs 四面实测皆无（Web 端 workflow 树为 client-ui 本地组件，同一事件源）；服务器升级暴露后重验再启聚合器
  - Task 3b 落地 workflow run-start/run-end 降级单卡（同 runId 原位更新 running→终态）；agent-start/end 维持 Ignored（防逐成员刷卡）
  - 方向：run 级聚合器（成员 label/outcome/phase 折叠进阶段卡，参照官方 tool-workflow 装配）；验证=真机 workflow 运行会话卡片分阶段展示
  - **2026-09-01 活体四面包夹（走查 #9 定性）**：events.mux 实况帧（两次 WS tap + 现跑 workflow 对照，仅 tool/code-dispatch* 渲染伴生）、session.history journal（39 页全翻 0 行，fresh run 亦不入）、session/projection（仅 permissions）、session/jobs（仅 bash 后台任务）四面皆无 → app 侧映射链（DshEventMapper:469 + DshMessageAssembler）为休眠代码路径，非缺陷；走查期「18 事件在 a6c4」不复现（疑当时另有来源/版本窗口）。重开丢卡=结构性（无服务器数据源），DSH synthetic 消息零持久化同因
  - **2026-09-02 复验（差距调研独立交叉确认）**：`docs/research/dsh-gap-2026-09-01/` 四路证据（fe 源码/Android 清点/Web 实测/服务端 api-gap）再次确认服务器事件面无 tool-workflow 运行事件——门维持关闭；聚合器设计参照 fe-inventory §2.17 client-ui-workflow-run
  - 2026-09-29 前提重验:0.1.7-rc.2 dist 仍无 tool-workflow 运行事件——前提不变,维持 P4

- [ ] **#146 OpenCode 官方问题清单（issue/PR 候选）** `upstream`
  - **前提**：上游 anomalyco/opencode 合入变更——需先过用户流程门槛（本地定位官方源码→修复→完整测试含 E2E+交叉验证→人工测试→用户放行才可提交 PR；源码已就位）；2026-09-03 用户裁决长期挂起，上游不提不影响本 app（客户端防御均已落地）
  - ①V2 不发 compaction.started（引擎没接线）②SSE 重连无事件回溯 ③cursor V1 格式返回 400 ④fork handleRaw bug ⑤工具输出截断语义——上游核查完成（repo 已迁 anomalyco/opencode），逐项行动方案已定
  - ⑥候选（2026-08-27 八轮实证）：V2 后台 shell 状态恒 completed（exit 7 亦然），失败信号仅正文文本——上游语义退化，客户端已防御性派生
  - **2026-09-02 逐项复现取证完结（journal 258-stage-b §十）**：源码浅克隆 `~/Documents/code/opencode-upstream`@69c172e + Host-4199 live 复验——①②③⑥ HEAD 仍成立（①连 schema 都无 Started；②端点零回溯处理；③400 已类型化 `_tag` 但无降级；⑥exitCode 从不映射 status）；**④上游已修/改版**（空 body 分支 + payload 改 `{messageID?}`，运行版未跟上，app 现发形状已匹配 HEAD）；⑤不变（FR 开放）。PR 候选排序 ③>⑥>①>②
  - → `docs/journal/2026-08-15-chat-flow-bugs.md`
  - 2026-09-29 前提复核:上游浅克隆 ~/Documents/code/opencode-upstream 已不在本机——重验/提 PR 前需重新浅克隆
