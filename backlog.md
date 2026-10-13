# OC Beacon — 需求与问题总览

本文档是唯一的**未决工作项清单**：只保留尚未完结的需求与问题卡片。条目完结（用户验收 `[x]`）后**当场迁出**——记录连同证据移入 `docs/journal/` 对应批次文件，本文件不保留完结记录；历史查询走 journal 与 git。

**卡片格式**：标题（含全局编号）+ Tag + 状态 checkbox + **≤3 行**摘要 + 链接。需求全文、实现要点、验证证据一律写在链接目标（spec / journal）中，不内联。登记新批次用 `./scripts/backlog-new-batch.sh "<批次名>"`（自动建 journal 文件）；改动后跑 `./scripts/backlog-check.sh` 校验机械不变量。**放置规则（check 脚本强制）**：卡片一律写在下方对应 **Pn 节内**（按优先级定义归位；一节内新卡置顶）；头部编号行与优先级定义表之间**不放任何卡片**（仅允许编号勘误等注释）。**P4 格式增补**：P4 卡必含「**前提**：…」行——说清实现前提是什么、当前为何不可实现（外部硬阻碍所在）。**术语句**：卡片标题与摘要用词遵循 [CONTEXT.md](CONTEXT.md) 术语表（堆积消息/子智能体/轮次/撤销/中断…）；「待处理」保留给权限/问题（状态词待验证/待办/待裁决不受影响）；Tag 英文与 #N 编号不受中文术语约束；API 英文原词（cursor/fork）合法，_Avoid_ 仅限中文对应词。

**编号**：全局递增，不回收。下一编号：**#552**（2026-10-13 #551 七报：巨卡第二次展开全高让位飞顶（种子 max）。

**操作纪律（2026-09-09 用户定规，账本事故后）**：卡片区**禁止手工直编**——登记/明细追加/状态流转/完结迁移一律经 `./scripts/backlog.sh`（add/note/status/migrate；真实 backlog 变更后自动跑 check）；journal 新节追加用 `backlog.sh journal append`（append-only）或编辑工具定位插入，**禁止全量覆写重写 journal**（2026-09-09 演示批覆写丢章事故定规）。**裁决优先级（2026-09-09 用户定规）**：同一问题域存在多项历史裁决时**以最新裁决为准**；新裁决落地时须回写旧裁决域卡片的注记（#350 为先例）。**反馈归卡（2026-09-12 用户定规）**：用户对某张卡片的反馈/裁决一律经 `backlog.sh note <N>` 记入**该卡片**明细，**不另开新卡**承载反馈；仅当反馈引出**新的独立缺陷**时才另立卡片，并在两卡明细互相引用（#401→#408 为先例）。**工作流脚本类直接修（2026-09-29 用户定规）**：项目工作流/脚本层的修复（`scripts/` 流程脚本等不进 APK 的项目设施）**不立卡**——发现即直接修+自测，证据记入当批 journal；#480 为末代先例（已立卡的按原流程走完迁移）。

> 编号勘误（2026-08-23 合并时）：terminology 分支先行占用的 #194–#199 与主工作区 #194（FAB）撞号，合并时 terminology 侧六卡顺移 +5 → #200–#205；文档内旧引用已同步改。

**优先级定义**：

| 等级 | 含义 | 示例 |
|------|------|------|
| **P0** | 影响主要流程体验或核心业务场景的 bug | 聊天页面崩溃、SSE 断连无法恢复 |
| **P1** | 主要业务流程的需求功能点 | 会话搜索、消息转发 |
| **P2** | 优化专项、锦上添花功能、不影响体验的小 bug | 动画微调、文案优化 |
| **P3** | **观察项**（2026-10-10 用户裁决归类）：终评无缺陷线索/无根因，但残余未知或复发可能仍在——挂网观察，不主动开工，复发再战 | 暖态点击失效 watch、DSH 1KB 截断上游复查 |
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


- [~] **#551 七报：巨卡第二次展开全高让位飞顶（种子 max 棘轮+离底全高未截断）——高度参照系裁决催生** `scroll` `race` `chat`
  - 用户复测 beta-4：首次展开收起正常，第二次点击跳到上面。定罪（p7_900_full.log）：第二次展开 tgt=13807（itemH 仅 4672）——种子 max 棘轮钉住首次展开期的动态高；驱动+窗配对全高让位 fiso 2155→13813（+11.6k px≈5 屏）视口飞到卡顶
  - 根因族=违反高度参照系裁决（2026-10-13 用户裁决已落 AGENTS.md+铁律 §2.4bis+spec §6）：①种子 if(sz>seed) 跨事件冻结 max；②巨卡离底让位未按动态视口截断（应截到「揭示顶达视口顶」事件时刻量）；③帽预记一帧滞后需显式声明
  - 修向（下一工作项，用户裁决先对齐高度模型再动手）：种子改事件时刻锚定（收起 settle 时刷新为当下稳定高而非历史 max）+让位总位移动态截断；修后按裁决补验证公式入铁律 §2.4bis；证据 /home/leonardo/.cache/540r5/p7*
  - 根修+验证完成（c2b590f8f，journal 待补 §40）：三刀=①降级种子 settle 事件锚定（窗关时刻刷新 contentH，去 max 棘轮）②mech storeFinalH 事件时刻直写（两调用点均 settle/落地实测）③离底让位视口预算（开窗置一屏主轴，正向配对分量合计截断，驱动盒照常长高）
  - A/B 实证：修前 tgt=13807（棘轮虚高 itemH 仅 4672）/位移 +11,658px 飞顶；修后 tgt=5546（=上窗 settle 实测）/位移 +283px；多周期快打（空起步展开×6 周期）种子锚定全程正确；小卡回归 s5 15 episode 零违规；单测 28/28（+预算截断 1 例）+全量 4054 零失败；铁律 §2.4bis 补 V1/V2 验证公式

- [ ] **#550 六报②展开后标题消失+全面不可点击——低频未复现，监视器待自然捕获** `ui` `race`
  - 用户报告（beta-2/0.4.0-beta/42）：卡片、思考展开后标题没了，各种原本可点击的地方变得无法点击；莫名其妙（随机）
  - 已排除：ReasoningBlock 标题行无条件常驻（非设计显隐）；dev 包 23 探针轮+beta 包 40 探针轮（focus/dump 响应性）零死 UI；logcat 缓冲无 FATAL/ANR/输入超时；graphicsLayer alpha=0+clipToBounds 零高盒的命中域不构成持久盾（瞬态窗口）
  - 在查假设：#545 BLANK-RENDER-FREEZE 同族（渲染冻结仅 force-stop 可恢复——待用户差分：是否 force-stop 才恢复/离开重进屏幕是否自愈）；beta-2 无四刀的闪光脸下游（巨型内容单帧全量组合=长主线程停顿——beta-3 复测差分）；#547 组合期写流的后向写面
  - 监视器已布防（watch6b.sh：beta 前台时 gfxinfo 10s 零新帧=冻结嫌疑自动抓 dump/截图/logcat，~2.7h 窗口）；用户在 beta-3 复测报告①时若②复发即有现场
  - 复验轮（subagent 22:30-23:27，REVERIFY-REPORT.md）：B3/B4 fresh 复验被上游 provider 凭据失效阻塞（22:41 起免费+付费层全线 401/零输出，用户域凭据问题）——同构建间接证据（probe6 23×focus 全绿/p6b 40×全绿/s5+fuzz 无真命中）支持未见跳跃/死 UI；补跑脚本就绪 repro6c_dev.sh/repro6c_beta.sh（含前台包校验——勘误：此前 p6c beta 轮实为 dev 前台误测，两包同代码结论不变）
  - B1（高度偏移）同构建历史 5 轮全绿+B2（贴底上向）fresh+历史双证 PASS；新发现扫描器误报类：IME inset 动画窗（键盘收起 notifyFinished/onReady 产生 −838/−753 单次外部位移，非手势非 toggle）——scan5r5 需补 IME 窗排除（登记入 #549 修向附带）
  - 环境修复+补验收官（2026-10-13 凌晨）：所谓凭据全线失效实为双源——zhipuai-coding-plan 余额耗尽（curl 实证 error 1113，充值属用户域）+opencode 免费中继账号凭据失效（401，重登录属用户域）；deepseek key 活着（余额 91 元）但模型 id 被误用（deepseek-reasoner 不在册，正确为 deepseek-flash/v4-pro）——改走 deepseek-flash 后测试链全恢复（出流+reasoning）
  - B3/B4 补验（用户原话协议：空起步展开×12+ 轮，dev 探针版）：零真命中——全部 TITLE-GONE 经 dump 定罪=思考卡被流式答案顶出 a11y 视口（屏幕实为答案正文+完成栏，正常贴底 UX）；唯一 OSC=markdown 表格重排凹陷（数据驱动合法补偿）；零 DEAD-UI/FOCUS-0。判定：四刀修复版上 B3/B4 未复现
  - 工具教训：TITLE 存活探针须带滚动感知（a11y 视口裁剪伪影）；scan5r5 需补 IME-inset 窗排除与重排凹陷容忍（本轮三类伪影全记录在案）

- [~] **#549 fuzz 发现残余族——动画边界帽-驱动清算净错（重向 slam/贴底不累积/尾段往返三脸）** `scroll` `race` `chat`
  - 五报四刀后随机 fuzz（真机 25 轮+模拟器 11 轮）挖出的低频残余族（~4 实例/43 轮 ≈ 9%），跨设备独立复现非伪影；三脸同源=帽-驱动通道动画边界清算净错——C3 三通道只拆了正向分支，负向 nonDriveNeg 提取与帽账本在重向/动画尾段仍污染
  - 脸①重向 slam（r400）：收起动画中 burst 重展开→首步 +1018 配对→40ms 混合帧过度镜像 −1343 砸回（净错 ~300px+千像素闪烁）；脸②贴底不累积（r427）：burst 贴底连打时离底让位每帧读位回 (0,0)、写入 (7,step) 不滞留、驱动步进 83/56/38 逐帧蒸发=报告②低频残余；脸③动画尾往返（模拟器 r11+真机 r303/r304 交叉复现）：expand toggle 后 0.94-1.9s（pairing-window 生命周期内、reveal 动画尾段）±627px 0.1s 往返
  - 证据：/home/leonardo/.cache/540r5/r400|r427|r415、/home/leonardo/.cache/540emu/r11_full.log（19:54:35-38）+SOAK-EMU540-REPORT.md；修向=完成 C3 负向分支三通道拆分（nonDriveNeg 提取换帽账本独立通道）+反射位/零高横幅归一化对贴底累积的影响面勘察
  - → docs/journal/2026-10-10-540-dsh-expand-degrade-rca.md
  - 根修完成（8f87083d9，journal §39）：Fix A=终高种子+600ms 单调护栏（M2 分期重组追靶）；Fix B=expandPairedPos 动画门槛（M1 底钳打架）。验证：单测 20/20+27/27+全量 4053 零失败；同型定向——空起步×4 全 PASS+burst 重向×4 零 slam；fuzz 复跑 8 轮 6 PASS+2 FAIL 全裁决为 V 形伪影（小幅 −92/−124、adopt 正确重基线、toggle 前置 ~300ms 超 150ms 前瞻窗）
  - 残留降级观察类：分期表格批落地一次性重锚 V 形（~740-917px 单事件，#502 收养正确处置，非振荡）——随下批 pairing 域工作处理；scanner OSC 判据需 ≥3 换号或 ≥2 完整 V（flips>=2 会把单 V 当振荡）

- [~] **#547 SILENT-STATE-RESET 残留变体——宿主换名级联派生 partId 前缀（展开态 map 脱靶）** `identity` `race` `ui`
  - soak5r r13 定罪（23:25:23.140→.219，79ms）：用户展开后多消息轮次重组换宿主（新 turn item t_msg_12b91c58a 出现+RESIZE -4159/LEAP 7 项），派生 part id= 前缀随宿主变 → partId 键控展开态 map 脱靶 → 静默回退收起；无 toggle 日志
  - 与四报根修（dedup 身份先行，MessageMergeEngine:312）同类不同源：那次是 REST 权威孪生换名（已修），这次是宿主消息级联；修复②驱动列表化 12 轮全绿零回归
  - 修复方向（架构审查 C4 强化）：a) 展开状态键与宿主消息解耦（ExpandStateStore，推荐）b) merge/store 层换宿主时迁移 map 键 c) 宿主稳定扩展（#509 原地换名覆盖此重组路径）；顺带：RB-EXP 探针改打完整 id（takeLast(8) 前缀盲，N2）
  - 证据：/home/leonardo/.cache/soak5r/r13_evidence.txt + report.md；发版前需修复或用户裁决接受残留
  - 勘误：第一行「派生 part id= 前缀随宿主变」应为「派生 part id=<msgId>_reasoning_ord_N 的前缀随宿主消息 id 变」（登记时反引号被 shell 吞）
  - 根修+验证完成（67f1cb867，verify 态）：修向 a 落地——ExpandStateKeys.forPart（派生 id 改 kind+空白归一化前96指纹键，服务器 id 直通）+ migrateExpandAlias 别名收养（成长跨窗旧键状态单向迁移，组合期同步）+ 键切换帧权威读（消 collectAsState 一帧陈旧假塌缩，旧包 27ms flash 反证）；N2 探针完整 id 已随修
  - 验证链：ExpandStateKeysTest 10/10（含 r13 原始双宿主 id 换宿主同键用例）；全量 4046 单测零失败；lintDevDebug 门禁过；真机 houji 新包 9 轮 E2E 全 PASS（R1/R1'/R2 红线零违——同键/跨键 true→false 无 toggle 均零、mapHit 零脱靶、6 起天然成长跨窗迁移存活零闪断、burst 连点轮含）
  - 宿主换名本体（r13 触发面）未在 9 轮中自然再现（1/12 概率类，全部单 turn item）——该不变量由单测直接钉死（r13 双宿主原始 id 同键）；E2E 复用配方与红线扫描器存 /home/leonardo/.cache/547e2e/（e547.sh，可复跑）
  - C4 关系：ExpandStateKeys.forPart 即 C4 ExpandStateStore 的键律先行落地（单一真相源/store 化与 default 冻结仍在 #544 第二刀范围）
  - 双腿浸泡收官（2026-10-12，journal §36）：真机 houji 5h×91 轮——产品红线零违规（R1/R1'/R2/FATAL/windows/release-dis/已应用大跳全零）、成长跨窗迁移存活 76/80、HOST-SWAP 91 轮未再现（单测钉死不变量）；模拟器 subagent 1h×19 轮（7 完成轮）——零 R1/R1'/R2、KEYCHAIN 7/7（13 次跨键演化零塌缩）、双服务并行零 429
  - 表面 FAIL 6 类全归案零产品罪：旧扫描器 R1' toggle 盲×2 轮（已修+重扫 PASS）、VIS-REPEAT×3（relocate 销毁窗重挂载记账 by-design，RB 佐证严格配对）、DRV-FLIP×2（r189=引擎拒发帧误计已补排除；r203=同宿主 0.44s 连点换向产生 windows=2 并发反向驱动——修复②驱动列表设计能力，2→1→0 严格闭合）、r3=旧包 A/B 证据轮；装置失手 32 起全归类
  - 终报：/home/leonardo/.cache/547e2e/SOAK-SUMMARY.md（含终局修订）+ /home/leonardo/.cache/547emu/SOAK-EMU-REPORT.md；判定=通过双平台系统性浸泡，待用户验收

## P2 — 优化与锦上添花

- [ ] **#548 beta 测试包版本序号自动递增脚本（testbuild.sh——免人工判定）** `build` `tooling`
  - 痛点：每次 beta 测试包装机需人工判读 release-workflow §2 定规（bare -beta 视为第1号→下一次是 -beta-2、VERSION_CODE 同步+1、先改 properties 再 assemble、bump 是否落豁免域）——2026-10-12 装 0.4.0-beta-2/42 即全程手工
  - 方案：scripts/testbuild.sh <flavor> [serial] [--no-install]——读 version.properties，仅同线纯序号递增（beta-N→beta-(N+1)/bare -beta→-beta-2）+VERSION_CODE+1 自动落盘；越域（开新线/通道晋升/stable 线）拒绝并指回 release.sh 确认门；assemble<Flavor>Release（dev 走时间戳免 bump）；可选无线装机（wake/unlock+MIUI 守卫）；bump 自动 chore commit
  - 复用面：release.sh 的 derive_bump/apply_bump/版本解析可抽取共享；装机段可复用 debug-entry/soak env 的 wake+滑锁与 serial 解析（含 mDNS 名内嵌空格 cut -f1 教训，journal §36）；AGENTS 构建纪律（禁并发/超时）继承

- [ ] **#545 IME 注入×历史会话加载竞态致主线程渲染冻结（BLANK-RENDER-FREEZE）** `ui` `ime` `race`
  - 5h soak ×5 复现：进入含历史会话后整串 input text 注入必现 Choreographer 跳帧后渲染冻结（a11y 树完整但空 2627B），无 FATAL/ANR，仅 force-stop 可恢复（空闲 3 分钟不自愈）
  - 手工几乎不可达（需程序化快速整串注入）；新空会话+分块注入（≤14 字符/块、0.6s 间歇）40+ 周期零复现——嫌疑面=IME 批量注入×历史加载/图片解码竞态
  - 证据：/home/leonardo/.cache/soak5h/（2026-10-11 发现；soak 终报［非缺陷·重大环境发现］节）

- [ ] **#544 #544 展开编舞统一（第二刀）——episode 吞降级分支、AV 弹簧退役、终高谓词取代双路径** `scroll` `refactor`
  - 终态谓词（终高已知→同快照原子帧 / 未知→动画相走帽通道）取代 LocalInStreamingTurn 双路径；AV enter/exit 退役；幂等重挂不变量（缓存命中 coldReveal 单帧/无缓存开窗配对）；#420 头注释矩阵第一条改写
  - 前置：第一刀（#540）全绿签名当基线；重挂探针门（slot-compose/episode-start 测流式期重挂率）
  - → spec: docs/specs/2026-10-11-unified-expand-placement-design.md
  - 2026-10-11 review 轮登记两项同批：①ScrollCompensation 877/962 头注释漂移（dispatchRawDelta 旧述）②stick 槽 entryKey 标签化（同 item 双卡并发收起单槽冲突）
  - 架构审查落账（2026-10-11 深夜，终包 soak5r 交叉对账后）：勘察 8 摩擦点合成六候选 C1-C6，递进 C2→C3→C1——C2 CardExpandClock 死机制清剿（advance/absorb/pinTargetFraction 等 100+ 行生产零调用+半测试文件，纯删零风险，可先行）；C3 applyPairedShift 三量分通道（plan.delta 污染和→帽/驱动/ledger 正交通道，~30 行反解蒸发，C1 前置）；C1=本卡主体（降级分支退役）。C4 ExpandStateStore 由 #547 实测强化（展开态键与宿主解耦为推荐修向）；C5 单写者化/C6 快赢包随 C1 顺势。报告存档 /tmp/architecture-review-20261011-height-engine.html（勘察全文+前后对比图）；正向否决门 reserve 级单槽（四报只修负向半边）并入 C1 范围

- [~] **#543 #543 压缩卡换装重建窗双宿主——双 episode 双锚定三跳瞬态（R9 系统性 E2E 发现，定向复现已定罪）** `scroll` `chat`
  - 真机 R9 + 定向复现 2/4 RED：displayItems 重建（upsert:MergeStrategy→[452-display]）窗内同一压缩消息以两个不同 item key 的条目并存 ~300ms（两 CardExpandReveal 并发完整 episode），共享屏幕级 expansionKey 一次 toggle 点亮两宿主→同 +H 双锚定（111/127ms 间隔）→旧宿主离场收缩修正（R9 变体 −H 第三跳）；净效≈单次展开但三跳瞬态可见
  - 定罪档案：Room/服务器零重复（纯 UI 组合层）；diffListInto 单组合内原子（排除）；itemsIndexed key 含 chunk #c<i> 后缀——嫌疑=chatEntries 分片/换装身份变更未走 #509 原地换名语义；阴性对照（idle tap 单 episode）成立；有效复现配方 TurnFin+1.35~1.41s（burst#1 吞 tap，脚本 /tmp/543-round.sh v15）；证据 journal §20 + /tmp/543-*、/tmp/540r-s9b.log
  - 修复方向（架构优先）：条目身份变更（重分片/换装）必须原地保持或原子交叠消除——先加 episode 级探针（hostItemKey+expansionKey+slot 身份）钉死双 key 具体形态，再在 chatEntries 派生域根修；禁 episode 层去重补丁（补偿味）
  - 根修+验证完成（af1e785a8 + journal §21）：真身=嵌套双 reveal（非双宿主，四假设判读定谳）；唯一所有权修复（CompactionCard+EventCard 同型）；houji 验证全绿（2/toggle→1/toggle、双锚→单锚、三跳/双取消→0、视觉无回归）；beta 0.4.0-beta/41 已含此修待用户真机验收

- [~] **#540 DSH 流式/输出后折叠卡展开「上推→跳回」瞬态——降级裸AV零配对 + 工具宿主 completed 恒 null 延长降级窗** `dsh` `scroll`
  - 症状：DSH 会话 SSE 输出中/刚输出完展开折叠卡（思考卡/工具卡），展开过程把上方内容上推、展开完毕后卡位一次性跳回原位；重进会话后恢复「正常展开」（渲染前算高+反射原子落位）
  - 根因①（主）：流式 turn item 经 LocalInStreamingTurn=true 走降级裸 AV（零配对），reverseLayout 把 +H 全额转译为视口上推；#526 事后回锚等 AV 稳定（~300-650ms）才一次性反射恢复=「跳回」瞬态的结构来源
  - 根因②（DSH 独有窗）：dsh-call-* 工具宿主 Message.completed 恒 null（整装/回放腿均不携带），直到 turn/end→SessionIdle→markSessionIdle 才回填——「输出后」工具卡展开仍全程降级；重进会话从 Room 读已回填 completed=机件路径=正常。分析全文见 2026-10-10-540 journal
  - 五腿校验裁定（V1-V4+P）主体成立：RCA 3 处勘误（MC 编码点/V2 stale 注释/DSH streamingMsgId 非 null）均不动结论；探针帧级实证 E1-E3/E5/E6（nodis 免派发上推+LRef 一次性跳回+同卡双路径 A/B 对照）；修复方向修正：B 最小形态有效（V3 零收益判定被推翻）、A 缺口收窄为配对规则扩列；推荐 B 先行→A 终修。详见 journal §8-§11
  - 2026-10-10 用户 beta 复测（0.4.0-beta/41）反馈：输出中展开/收起仍上下震荡（静态正常）→ V2 SSE 腿仪器复现定罪降级路径负向配对三缺陷：D1 展开帧内缩 consumed=0 未配对+asyncParse 分档拖拽、D2 收起配对被 yield(external-pending) 误判饿死（76 帧零执行）、D3 大卡收起 dispatch 钳制缺口（capd=-3655 consumed=-277，3378px 未配对，录屏证瞬时猛拉 30% 屏高）。结构根因=负向补偿走 dispatchRawDelta 增量通道（边界钳制+yield 误判），正向反射 set 无此问题——A 案只修了正向。修复方向三支（负向反射化/dataKeyAt 越窗折叠、窗内 yield 豁免、展开重解析源消除）待裁决。证据 journal §22 + /tmp/v2leg-*、/tmp/v2col.mp4
  - 2026-10-11 四轮拷问裁决（spec §1 全文）：§12 推翻/#433 延伸/#420 原则维持实现退役；目标架构=C 全统一两刀落地（本卡=第一刀：③接线+负向反射化(揭示缘参照)+宽豁免+重挂补洞）；重挂裁 ii⁺ 幂等（续命=续陈锚被否）；第二刀拆 #544。用户实地观察佐证：展开稳+旧动画、收起不稳+旧动画（与 D1-D3 定谳吻合）
  - 2026-10-11 第一刀落地+全验证（journal §23）：E2E 腿A/B/C 全绿（输出中展开/收起反射配对+静态机件零回归+cachedTerminal 实证）；verify 态待用户 beta 复测
  - 2026-10-11 review 轮 P1（混合帧拆账）+P2×3（物主守卫/prewarm 翻转门/亚像素防御）即修随 beta 包重发；两 P2（注释漂移/多窗单槽）登记 #544（journal §25）
  - 2026-10-11 用户二报①展开震②收起拖上半部→②两 face 定罪即修（跳揭示缘改逐帧镜像骑行+盒上方漏配镜像扩展，判定表三态重写，journal §26）；①正向否决门已加（tap 几何不可达，护④重挂面）+残余候选归档（#544 原子性/重组churn 待复测口径）
  - 5h soak 收官（journal §28）：53 周期/13 收起锤炼/红线全绿（零崩溃·windows 无负·旧通道零出现）；双 DEFECT 亲自取证均判非缺陷（窗后 yield=turn 完成重映射边界态；镜像偏差=断言模型不含塌缩动画推进量，写入本身正确）；BLANK-RENDER-FREEZE 环境发现拆至 #545；beta 14:49 重打含全部根修，待装真机交复测
  - 三轮根修（journal §31）：AV 尺寸退役为 flush 单事务驱动盒（相位差震荡定罪）+竞态热修（注册挪组合期同步段）；真机五腿+帧级数值验证零震荡；待 beta 四测
  - 四报收官（journal §33）：soak4r 定罪 SILENT-STATE-RESET（REST 权威刷新 partId 换名→展开态 map miss 静默收起）+竞态三连（单槽驱逐/几何覆盖/重向回拨）；双根修（dedup 身份先行+驱动列表化）4036 绿；复验 id 70s 稳定+连点签名健康；beta 21:34 已装 houji 待用户五测
  - 五报根修（2026-10-12，cc1a71dc4，journal §37）：①思考卡高度偏移+②贴底普通卡概率性上向——定罪两报一源（布局相活读×陈旧捕获=首帧全高闪挂→钳回未配对收缩→过滚纠正回跳三段式）+两独立缺陷（混合符号帧正步进误拒发；空卡窗劫持贴底跟随 391↔0 拉锯）；四刀 F1-F4 落地
  - 验证：单测 27/27+全量 4050 零失败；真机四包 A/B FLASH/FIGHT/OSC 三签名 1→0/1→0/2→0；114fps 相位相关视频零符号翻转；70min soak 验证中（/home/leonardo/.cache/540r5/）；竞态矩阵 C1-C10 全覆盖落 journal §37
  - 五报随机扰动战役收官（journal §38）：fuzz 基建沉淀（随机驱动器+六版扫描器+ACT 裁决协议）；双平台长跑——真机 fuzz 25 轮 24 PASS+模拟器 subagent 11 轮 10 PASS，四主判据（FLASH/FIGHT/windows 负/release-dis）全净；残余族三脸（重向 slam/贴底不累积/动画尾往返）跨设备定罪 → #549（P1，C3 负向半边完成载体）；主报两缺陷根修确认不受影响
  - code-review 双轴回执（2026-10-12 深夜）：Standards 零硬违规（SSE 铁律未触碰且 F4 向其收敛；组合期写与 #540 先例一致；命名贴域词汇）；Spec 六点核查全符（F1 双窗合一/孤儿回落、F2 正步进完全出表、F3 三通道正交+拒绘契约原样、F4 无驱动窗不误伤、#547 权威帧读 else 仍走派生读、migrate 幂等）——两处 §37 未明说的合理延伸补记：负分支 drivePosScrollV 折入 collapseShiftV 与首臂 collapseShiftV==0 条件；一处回归关注点：正向派发判据 total→totalP 后 scrollPaired=false 纯帽帧不再净和滚动（C3 门控语义收紧，读历史格帽释放留意）

- [ ] **#522 B案升 stable 后余留装备清扫——JankHoldGate A/B 开关 + STREAM_DELTA_BUS 旗标退役（#499 余留范围）** `chore` `refactor`
  - 前置：STREAM_SHARD_PILOT / STREAM_DELTA_BUS 升 stable 并观察一批——当前 stable 均 false，JankHold 冻结链在 stable 仍是承重活体（B案开=旁路），现退役会回归 stable 流式滚动组合风暴。
  - 清扫面：JankHoldGate system-prop A/B、ChatScreen jkHold 冻结链（含 B3 埋点）、STREAM_DELTA_BUS 旗标与旁路分支；probe 按 keep-probes 裁决保留。#499 于 2026-10-06 完成范围内部迁移，余留拆本卡。
  - 2026-10-06 承接评估：前置未达维持——stable 双旗标（STREAM_SHARD_PILOT/STREAM_DELTA_BUS）仍 false（build.gradle.kts stable 块），JankHold 冻结链在 stable 仍是承重活体，现清扫=回归 stable 流式滚动组合风暴。beta 侧两旗标已升 true（2026-10-06 裁决），观察窗进行中；待 stable 随批提升后再开工本卡。
  - 2026-10-09 R1 排程裁决 ok：stable 随批发版带双旗标（STREAM_SHARD_PILOT/STREAM_DELTA_BUS，build.gradle.kts stable 块现值 false 实证）提升→stable soak 观察一批→B4 清扫本卡；若 B2 狩猎批修了 #486（同 scroll 域）则 B4 必须隔批串行

## P3 — 观察项

- [ ] **#546 16KB 页对齐构建改进（MIUI 安装告警）** `build` `infra`
  - dev-debug 安装时 MIUI 弹「应用兼容性」告警：libdatastore_shared_counter/libandroidx.graphics.path/libsqlite3x/libzstd-jni 四库 .so 未 16KB 对齐（面向未来 16KB 页设备的前瞻提示，当前 4KB 页设备不影响运行）
  - 处置：升级 AGP/NDK 或依赖版本使打包对齐；release/beta 包是否同样触发待观察
  - 发现源：2026-10-11 #540 三轮验证装机（journal §31 测试侧沉淀）

- [ ] **#537 DSH 同步通道 1KB 截断——工具卡全文与 spill 通知不落 Room，离线重开降级** `dsh` `data`
  - 2026-10-09 #536 E2E 通道性发现：DSH 服务器同步/投影通道把工具输出截到 ~1KB（尾标 [truncated, full output on server] 为服务器原话）——spill 通知与全文只存在于打开会话时的实时拉取，cached_parts 恒短版
  - 影响：离线重开该会话→工具卡渲染 1KB 截断版且无横幅（降级可接受但不告知）；服务器侧语义非 app 缺陷
  - 观察项：上游若放宽同步通道预算或暴露全文端点再议（挂 dsh 升级复查）；证据 journal 2026-10-09 b1 批 #536 节判据 4

- [ ] **#486 SSE retry 重建帧 watch（原#471④）：断连续传+滚出视口弃树+ever=false 理论盲区，复发再战勿主动开工** `scroll` `chat`
  - 原 #471④ 用户裁决 c 降级观察（v1+v2 四场景不可复现，331k 行取证 Loading=0，#472 hold/registry/preParsed 防御栈有效）；#471 整卡迁移后观察线独立成卡（spec §8：watch 不关）
  - 理论盲区：断连续传+滚出视口弃树重建+ever=false 帧（0 触发）；若复发，残余只剩 staged/换装差（-1330px ③族已消失）——spec §8 残余收窄注记
  - 判读取证：MDResize 负 d / RESETKEY / nonPrefix / rawTail 探针均保留 DEBUG-only（2026-09-30 用户裁决）
  - 2026-10-09 用户裁决：改「等复现」为 soak 长时主动狩猎→转 #535；本卡保留判读上下文。关键勘误：2026-09-30 实验 A/B 均未含「滚动离屏」第三要素（longcat 限流未跑成三联场景）——「0 触发」实为「0 试验」，见 journal 2026-09-30-471-4 §4.2
  - 2026-10-10 B2评估：根修（LRU账本wasStreamed+终态缓存LRU32，coldReveal单帧揭示）已落地并全验证——同内容冷重入0空窗（仪器化mainClock 0帧+模拟器/真机交互面）；两晚soak（3.6h+1h）零复发。残余理论面收窄为：流式中途销毁的部分内容缓存miss→coldReveal 1帧（设计底线）与主题切换重着色（已由skip守卫同一性判定保全）。无新线索→维持watch（复发再战），勿主动开工语义不变。

- [ ] **#464 UI 暖态下列表/卡片点击偶发失效(冷启可靠)** `chat-ui`
  - 2026-09-29 #461/#462 取证副产物:force-stop 冷启后输入 tap 可靠命中(会话行/卡标题),同一 app 暖运行数分钟后点击同坐标零效果(无日志无 UI 变化,vibrator 反馈存在=命中可点击元素但未触发业务);两次独立取证会话复现,冷启后恢复。疑点:点击消费被某 overlay/焦点态拦截或状态门;影响面=自动化测试可靠性,人工使用未报告。待真机复现窗定罪(diagnosing-bugs 流程),暂无用户主诉不阻塞。
  - 2026-09-29 审计顺带实证:一次拖动送达零响应不再现(见 #465 注记);#477 dump 全盲为坐标类判读新增干扰源——本卡与 #465 高度重复,建议合并为单卡观察项待裁决
  - 2026-09-29 用户裁决:吸收 #465 合并,本卡为该域唯一观察卡(暖态间歇 input 送达零业务响应;今日一次拖动零响应不再现实证在案;#477 dump 全盲为坐标判读新干扰源)——再撞上即现场抓 input dispatcher+app 双侧日志
  - 2026-10-09 用户裁决：转 #535 soak 主动狩猎（tap 效果断言+失配现场抓取 dumpsys input/logcat/meminfo；dump+截图双通道防 #477 dump 全盲假阳性）
  - 2026-10-10 B2评估：两晚soak探索tap共9起可疑全裁定良性（目标均为clickable语义但无动作的列表文本项——'7×15=105'等TextView，属harness注释预期的合法no-op误捕；InputDispatcher无吞证据）。原症状（暖态偶发点击失效）未再复现，无根因线索→维持观察；判读工具沉淀：dumpsys input消费记录+前后签名对比可复跑。

- [ ] **#477 长文 markdown 块 uiautomator 语义零暴露——dump 全盲致渲染正常被误判空白** `ui`
  - 2026-09-29 收口审计真机定罪:末轮长文(async parse 路径)可见区 dump 零文本节点(仅 1 个空 text 可点击 TextView h≈1921px),同期旧 turn 正常暴露;vision(三路交叉)+像素双验证内容完整渲染(连续正文/无空白/无圆点)——渲染层无恙,纯语义暴露缺陷
  - 影响面=自动化验证可靠性+#467 dump 实证段污染(核心 SGR 日志证据不受影响)+a11y;待定位暴露分叉(疑 rememberAsyncMarkdownState 路径)
  - 证据:/tmp/ui_b1.xml(底,盲) vs /tmp/ui_top.xml(顶,正常);#475「近空白/折叠态」原判即此 artifact
  - 2026-09-29 定罪深化(见journal):分叉=单part纯文本长度(同turn 993暴露/3585盲,表格组件不受影响);渲染三层同构排除(组件/库重载/success槽);超视口单item假设被未分段表格turn(3674px)暴露推翻;最强嫌疑=SPLIT_PARAGRAPH 3000拆段线;androidTest断言已写但被MIUI+idle挂死双阻塞;修复候选A完结即分段/B到达即分段/C拆段线定罪后修normalize/D平台上报——待用户裁决,不盲改
  - 2026-09-30 定性翻转(用户裁决B降级观察):今日冷启进程两会话十几个turn(0-20k chars全路径)零盲例——五假设全推翻(拆段线:3585原文标准段落格式未触发/Q换帧:7523混合part同经换帧暴露/长文=盲:4077五段纯散文注入段段暴露/路径=盲/密集dump自伤:10连dump后仍暴露);原定罪(长文语义零暴露)重定性=疑似长跑进程状态性退化(R假设:昨21:22同屏老表格turn暴露vs新essay盲分野=内容创建时刻;盲取证全部集中于4h+单一进程晚期);再撞上(长跑进程新组合内容dump全盲)即抓:logcat a11y错误+dumpsys meminfo+uptime;A11yDiag探针留树备用
  - 2026-10-09 用户裁决：转 #535 soak 主动狩猎（周期盲 dump 检测器：dump vs mock 语料/Room 直查 ground truth；命中即抓 a11y 错误+dumpsys accessibility+meminfo+uptime；R 假设=同语料进程早晚期对比可同批验证）
  - 2026-10-10 B2评估：两晚5起blind捕获全良性（短回复/鉴权失败错误轮的稀疏但有效dump；像素var 800+证实内容丰富）。新增判读工具沉淀：①像素统计（mean/var）快速分盲屏与暗色内容屏；②uiautomator bounds为视口裁剪值——钉在视口顶的块其上方元素整棵不出树（#488批3①勘误），裁剪线以下缺席≠未渲染。无真盲渲染线索→维持观察。

- [ ] **#454 v1 真机 IME 换行注入后 prompt 未发出** `chat` `device` `v1`
  - 真机 IME keyevent 66 发送路径:消息含注入换行(Run\n\n)时 prompt POST 未发出,乐观气泡悬挂;二次干净发送正常(prompt_async 202)。发送链路疑有 IME 竞态边角,#453 验证时顺带观察,未复现第二次
  - 2026-10-09 用户裁决：转 #535 狩猎（两层：单元红用例 sendMessage("Run\n\n") 断言 sendPrompt/parts 形状先行排除纯逻辑面；E2E 注入循环 50-100 次扫时序面）
  - 2026-10-10 B2评估：纯逻辑面单测早已排除（sendMessage('Run\n\n')正确POST raw text）；仪器腿最终3/3零MISS（态守卫=仅idle发送键时注入后，前两晚4起MISS全归因忙窗/导航竞态/通道陷阱——LogcatCapture缺席假阴性+IME动画坐标陈旧）。无缺陷线索→维持观察；h454双通道断言（屏上回显or POST计数）沉淀为可复跑资产。

## P4 — 外部前提阻塞

- [ ] **#381 192.168.110.248:248 重配——凭据宿主零痕迹不可探查（2026-09-09 用户裁决入 P4）** `infra`
  - **前提**：上游提供远程凭据探知能力——用户原话「后续看看 dsh 官方是否会支持远程探知 token 或其他认证手段」（DSH 官方远程 token 发现，或 OAuth/设备码流等替代认证）；前提满足后 `cred-probe.sh opencode 248 <名称>` 即可接管（/proc 探针现成）；若上游确认不会支持，再议弃用或人工一次性重配
  - 2026-10-09 前提重验：未满足维持——oauth/device_code/mdns/discovery 全包 0 命中、CLI 无 token 子命令、README 明文 no query token outside root exchange / --host 0.0.0.0 unsupported。边际变化：cookie 签名 secret 已持久化 .credentials.yaml（跨重启有效默认 30 天），官方文档化「token 一次性兑换长期 cookie」——未来可探索兑换复用。证据：docs/research/2026-10-09-p4-upstream-preconditions-check.md

- [ ] **#332 spill 提示行——服务器无结构化信号（工具结果溢出 notice 内嵌纯文本）** `dsh` `sse`
  - **前提**：dsh-spill-policy 全链查实——溢出替换为有界 head/tail 预览+locator 提示全部内嵌工具结果 output 文本,transcript 无 spill 事件（session 事件枚举/types/实现三路 grep 0）;文案模式匹配脆弱（#136 先例:服务器改文案即静默失效）。待服务器暴露结构化字段再实现。→ `docs/research/2026-09-05-audit-309-313.md` #312③ + 实现 agent 取证（暂不可实现）
  - 2026-10-09 前提重验：未满足维持——61 事件枚举/ToolResultMessage 载荷零 spill 结构化字段，上游自认文本约定不可认证（hasSpillNotice 注释「not authenticated tool-output origin」+ README Known Limitations 首条）。可参考上游固定拼写 formatSpillNotice 实现替代自造正则。证据：docs/research/2026-10-09-p4-upstream-preconditions-check.md
  - 2026-10-09 用户裁决：授权降级实现（上游固定拼写文案匹配，实现处须详细注释约定来源与不可认证性）→新卡 #536；本卡维持 P4 挂结构化信号长期观察，上游升级时复查后切换

- [ ] **#146 OpenCode 官方问题清单（issue/PR 候选）** `upstream`
  - **前提**：上游 anomalyco/opencode 合入变更——需先过用户流程门槛（本地定位官方源码→修复→完整测试含 E2E+交叉验证→人工测试→用户放行才可提交 PR；源码已就位）；2026-09-03 用户裁决长期挂起，上游不提不影响本 app（客户端防御均已落地）
  - ①V2 不发 compaction.started（引擎没接线）②SSE 重连无事件回溯 ③cursor V1 格式返回 400 ④fork handleRaw bug ⑤工具输出截断语义——上游核查完成（repo 已迁 anomalyco/opencode），逐项行动方案已定
  - ⑥候选（2026-08-27 八轮实证）：V2 后台 shell 状态恒 completed（exit 7 亦然），失败信号仅正文文本——上游语义退化，客户端已防御性派生
  - **2026-09-02 逐项复现取证完结（journal 258-stage-b §十）**：源码浅克隆 `~/Documents/code/opencode-upstream`@69c172e + Host-4199 live 复验——①②③⑥ HEAD 仍成立（①连 schema 都无 Started；②端点零回溯处理；③400 已类型化 `_tag` 但无降级；⑥exitCode 从不映射 status）；**④上游已修/改版**（空 body 分支 + payload 改 `{messageID?}`，运行版未跟上，app 现发形状已匹配 HEAD）；⑤不变（FR 开放）。PR 候选排序 ③>⑥>①>②
  - → `docs/journal/2026-08-15-chat-flow-bugs.md`
  - 2026-09-29 前提复核:上游浅克隆 ~/Documents/code/opencode-upstream 已不在本机——重验/提 PR 前需重新浅克隆
  - 2026-10-09 逐项复查（HEAD 3884062 2026-10-08 + v2.0.26 tag 取证）：①已修（V2 core schema 三档 session.next.compaction.started/delta/ended+引擎发射；V1 兼容路径仍单事件）②③未修且官方定局不做（#25657/#29544/#19584 均 CLOSED NOT_PLANNED——客户端自救即终态，可标 wontfix 收口）⑥未修（v2.0.26 状态仍两档无条件 completed；dev HEAD 已拆除待重做 durable status）④已修仍在 ⑤部分进展（outputPaths/truncated 进 schema+core 统一限幅落盘；#45910 per-tool 未收口）。⑥登记「后台 shell 重建后复查」。证据：docs/research/2026-10-09-p4-upstream-preconditions-check.md
  - 2026-10-09 用户裁决：②③ wontfix 收口（上游三 issue 均 CLOSED NOT_PLANNED，客户端自救=重连快照重拉+只产 V2 cursor 即终态）；卡保留 ①（已修可消费 compaction.started 三档）/⑤（outputPaths 部分进展）/⑥（后台 shell 重做后复查）
  - 2026-10-10 系统调研完结（docs/research/2026-10-10-146-consumption-research.md）：①上游已修→客户端消费（G1/G2+⑤A）拆卡排期 B4 + R1 前取证腿；⑤B/C 视取证为 B5 候选；本卡余 PR 候选线（③>⑥>①>②）维持 P4 用户流程门槛
