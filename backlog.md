# OC Beacon — 需求与问题总览

本文档是唯一的**未决工作项清单**：只保留尚未完结的需求与问题卡片。条目完结（用户验收 `[x]`）后**当场迁出**——记录连同证据移入 `docs/journal/` 对应批次文件，本文件不保留完结记录；历史查询走 journal 与 git。

**卡片格式**：标题（含全局编号）+ Tag + 状态 checkbox + **≤3 行**摘要 + 链接。需求全文、实现要点、验证证据一律写在链接目标（spec / journal）中，不内联。登记新批次用 `./scripts/backlog-new-batch.sh "<批次名>"`（自动建 journal 文件）；改动后跑 `./scripts/backlog-check.sh` 校验机械不变量。**放置规则（check 脚本强制）**：卡片一律写在下方对应 **Pn 节内**（按优先级定义归位；一节内新卡置顶）；头部编号行与优先级定义表之间**不放任何卡片**（仅允许编号勘误等注释）。**P4 格式增补**：P4 卡必含「**前提**：…」行——说清实现前提是什么、当前为何不可实现（外部硬阻碍所在）。**术语句**：卡片标题与摘要用词遵循 [CONTEXT.md](CONTEXT.md) 术语表（堆积消息/子智能体/轮次/撤销/中断…）；「待处理」保留给权限/问题（状态词待验证/待办/待裁决不受影响）；Tag 英文与 #N 编号不受中文术语约束；API 英文原词（cursor/fork）合法，_Avoid_ 仅限中文对应词。

**编号**：全局递增，不回收。下一编号：**#564**（2026-10-21 #563 mock_v2 compaction 剧本装置（）。

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


## P2 — 优化与锦上添花

- [ ] **#522 B案升 stable 后余留装备清扫——JankHoldGate A/B 开关 + STREAM_DELTA_BUS 旗标退役（#499 余留范围）** `chore` `refactor`
  - 前置：STREAM_SHARD_PILOT / STREAM_DELTA_BUS 升 stable 并观察一批——当前 stable 均 false，JankHold 冻结链在 stable 仍是承重活体（B案开=旁路），现退役会回归 stable 流式滚动组合风暴。
  - 清扫面：JankHoldGate system-prop A/B、ChatScreen jkHold 冻结链（含 B3 埋点）、STREAM_DELTA_BUS 旗标与旁路分支；probe 按 keep-probes 裁决保留。#499 于 2026-10-06 完成范围内部迁移，余留拆本卡。
  - 2026-10-06 承接评估：前置未达维持——stable 双旗标（STREAM_SHARD_PILOT/STREAM_DELTA_BUS）仍 false（build.gradle.kts stable 块），JankHold 冻结链在 stable 仍是承重活体，现清扫=回归 stable 流式滚动组合风暴。beta 侧两旗标已升 true（2026-10-06 裁决），观察窗进行中；待 stable 随批提升后再开工本卡。
  - 2026-10-09 R1 排程裁决 ok：stable 随批发版带双旗标（STREAM_SHARD_PILOT/STREAM_DELTA_BUS，build.gradle.kts stable 块现值 false 实证）提升→stable soak 观察一批→B4 清扫本卡；若 B2 狩猎批修了 #486（同 scroll 域）则 B4 必须隔批串行

## P3 — 观察项

- [ ] **#553 V2 长答案完成后「停止」按钮残留 ~2 分钟（疑标题生成保持 busy）——终验观察项** `v2` `ui` `sse`
  - 模拟器终验 r81 遗留观察：p2 长答案完成后停止按钮残留约 2 分钟才消失，非滚动/展开域违规；疑 V2 会话标题生成阶段持续置 busy 状态所致；复发时抓 SessionState FSM 状态流取证再定修向
  - 证据：/home/leonardo/.cache/540emu/FINAL-EMU-REPORT.md 遗留观察节
  - 2026-10-20 凌晨主动复测（模拟器+4096 v2 长答案 60s 流）：停止按钮残留实测 ~9s（t+2s 在场→t+9s 消失），非 ~2 分钟——疑已被后续批次顺带治愈（r81 观察后 ChatScreen 域动过 #547/#544）；FSM 时间线干净（SseIdle→Idle [force-complete] 一刀切），无 Title agent 保持 busy 迹象（服务器标题生成独立于会话 FSM）。观察阈值更新：残留 >15s 即复发信号，届时抓 FSM 流+dump 复测。取证 /tmp/553/{full,fsm,title}.log
  - 2026-10-21 真机复测腿+根因归属修正：本轮「停止按钮 42s+ 在场」定罪为**服务器侧执行挂起**（/api/session/active 显 running 7 分钟无推进无事件；app 侧 FSM 干净——L3 校验发现 server-Busy/无 SSE 矛盾后按 zombie 策略保守等待，停止按钮=Busy 正确反映；app 点停止即恢复，服务器 active 清空）。结合模拟器 9s 干净样本：**#553 现象主体=服务器侧 turn 状态收敛慢/挂起（provider 链无超时兜底，上游域），app 域无可修**（可选体验优化=无事件 N 分钟 UI 降级提示，另议）。观察维持，复发以 [SessionStateService] server says Busy but no SSE events 日志+curl /api/session/active 双证定界
  - 2026-10-22 模拟器 soak 5.3h（队列串行腿5，/tmp/soak-553/FINAL-REPORT.md）：giant 150/150 CLEAN **残留全部 <2s**（比 10-20 干净谱 ~9s 更快，分布退化单点零在场采样）+hold 对照 36 轮满 75s 驻留+tap-stop→gone 36/36——**HIT=0 未复现，维持 P3 观察**（与根因归属一致：现象主体=服务器侧收敛慢，app FSM 干净；mock 腿为 app 侧负证增厚，复发定界仍按卡内双证）。装包事故=INSTALL_FAILED_VERSION_DOWNGRADE（-d 降级装修复+坚持旧快照防 #562 停滞提示代码污染判读）；装置沉淀七条（hold 天然对照裁定法/MOCK_HOLD_OPEN 真实驱动变量/hold 轮恢复路径缺失等）

- [ ] **#546 16KB 页对齐构建改进（MIUI 安装告警）** `build` `infra`
  - dev-debug 安装时 MIUI 弹「应用兼容性」告警：libdatastore_shared_counter/libandroidx.graphics.path/libsqlite3x/libzstd-jni 四库 .so 未 16KB 对齐（面向未来 16KB 页设备的前瞻提示，当前 4KB 页设备不影响运行）
  - 处置：升级 AGP/NDK 或依赖版本使打包对齐；release/beta 包是否同样触发待观察
  - 发现源：2026-10-11 #540 三轮验证装机（journal §31 测试侧沉淀）
  - 2026-10-20 检测装置落地+现状复测：scripts/so-align-check.py（ELF PT_LOAD p_align 解析，多 APK 多 ABI 汇总）——devDebug+betaRelease 实测四库三绿一红：libandroidx.graphics.path/libdatastore_shared_counter/libzstd-jni-1.5.7-16 已 16384 对齐（较 10-11 卡记录的四库全告警已部分解决），唯 libsqlite3x.so 全 ABI 仍 4096——修向聚焦 requery sqlite-android 依赖升级（上游 3.4x+ 已带 16KB 对齐构建），AGP/NDK 全局升级可豁免
  - 2026-10-21 上游调研修正：requery/sqlite-android 的 16KB 对齐是 OPEN 状态（issue #195 未解决——最新 3.50.4 亦未对齐，需 NDK 构建链重做 ELF segment 对齐，上游明确待有能力者接手）——前 note「修向聚焦升级」无升级门，修正定性：等上游修复后升级（外部前提性质）；自编译/换库工程量大且 app 非 Play 发行（GitHub 侧载），4KB 页设备零运行影响，16KB 页设备暂罕见。观察维持，前置=上游 #195
  - 2026-10-23 前提重验：**阻塞维持**——requery/sqlite-android #195 仍 OPEN 且被上游文档列为「最紧迫的未解决问题」（zread 知识库明言「需要熟悉 NDK 构建配置和 ELF 段要求的人接手，正等待具备专业知识和动力的人」）；最新版 SQLite 3.5x 线均未带 16KB 对齐

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
  - 2026-10-20 三联构造器备货+首跑（scripts/probe_486_sse_rebuild.py，借 harness 成熟原语 Mock/launch/open_session）：断流 pause 2.5s+流式卡滚出视口+恢复编排——2 轮实跑全家族探针触发（R2: MDResize×8/RESETKEY×1/nonPrefix×4/rawTail×1/452-display×2，证据 /tmp/486/20261020-025903/）——2026-09-30 勘误的「0 试验」盲区现有即战装置；复发时跑该脚本对照 #471 spec §8 判读（探针命中组合 vs 正常流式基线差分）。坑位入档：模拟器英文 UI 需双语言发送键匹配、mock 场景须 create_session
  - 2026-10-22 模拟器 soak 5h（队列串行腿3，/tmp/soak-486/FINAL-REPORT.md 终版七节）：360 轮 81 变体全覆盖×4+遍（断流 pause 2.5/8/20s×滚出 half/full/double×恢复 still/rollback/nextflow 三联矩阵+32 基线轮）**零 HIT**——机器判据五条全零（负 d 谱 112 个全部=-280px 单值零方差全来自 rollback 变体/RESETKEY 最大 1 每轮/无 452 崩塌/无 FATAL/无进程死）；探针谱与 2026-10-20 首跑基线同族（MDResize 8.6 每轮 vs 基线 8）。spec §8 判读：三联窗内重建帧确实发生（RESETKEY 112 次）但无异常组合，2026-09-30 勘误的「0 试验」盲区现有 328 轮三联实扫数据；-1330px ③族未回归。**判定：维持 watch，盲区收窄实证落袋**；新判读锚点沉淀（复发时对照谱形：MDResize≈8.6/轮、RESETKEY≤1/轮、负d=-280 单值、452 零崩塌——谱形偏离即升级信号）。事故链（误仲裁→无效收口→互污强杀→重跑）完整入报告 §1

- [ ] **#464 UI 暖态下列表/卡片点击偶发失效(冷启可靠)** `chat-ui`
  - 2026-09-29 #461/#462 取证副产物:force-stop 冷启后输入 tap 可靠命中(会话行/卡标题),同一 app 暖运行数分钟后点击同坐标零效果(无日志无 UI 变化,vibrator 反馈存在=命中可点击元素但未触发业务);两次独立取证会话复现,冷启后恢复。疑点:点击消费被某 overlay/焦点态拦截或状态门;影响面=自动化测试可靠性,人工使用未报告。待真机复现窗定罪(diagnosing-bugs 流程),暂无用户主诉不阻塞。
  - 2026-09-29 审计顺带实证:一次拖动送达零响应不再现(见 #465 注记);#477 dump 全盲为坐标类判读新增干扰源——本卡与 #465 高度重复,建议合并为单卡观察项待裁决
  - 2026-09-29 用户裁决:吸收 #465 合并,本卡为该域唯一观察卡(暖态间歇 input 送达零业务响应;今日一次拖动零响应不再现实证在案;#477 dump 全盲为坐标判读新干扰源)——再撞上即现场抓 input dispatcher+app 双侧日志
  - 2026-10-09 用户裁决：转 #535 soak 主动狩猎（tap 效果断言+失配现场抓取 dumpsys input/logcat/meminfo；dump+截图双通道防 #477 dump 全盲假阳性）
  - 2026-10-10 B2评估：两晚soak探索tap共9起可疑全裁定良性（目标均为clickable语义但无动作的列表文本项——'7×15=105'等TextView，属harness注释预期的合法no-op误捕；InputDispatcher无吞证据）。原症状（暖态偶发点击失效）未再复现，无根因线索→维持观察；判读工具沉淀：dumpsys input消费记录+前后签名对比可复跑。
  - 2026-10-22 模拟器 soak 5.1h（队列串行腿4，/tmp/soak-464/FINAL-REPORT.md）：487 轮巡检 159 次 tap 断言全 ok **零 MISS**（T1 进会话/T2 思考卡展开收起翻转/T4 BACK+busy 窗 41 轮机会 tap 其中有动作 19）——原症状（暖态偶发点击零业务响应）未复现，观察维持（与 B2 评估两晚 soak 结论一致）。假阴性源全排除（态守卫仅 idle 断罪/新鲜 dump 坐标/失败重试 1 次再定罪）；Compose clickable=false 语义陷阱入坑位（会话行实为可点但 dump 语义否——no-op 防御须按业务动作断言非 clickable 语义，B2 教训实质化）。无效首跑勘误节+门谓词 break 缺失根因入报告

- [ ] **#477 长文 markdown 块 uiautomator 语义零暴露——dump 全盲致渲染正常被误判空白** `ui`
  - 2026-09-29 收口审计真机定罪:末轮长文(async parse 路径)可见区 dump 零文本节点(仅 1 个空 text 可点击 TextView h≈1921px),同期旧 turn 正常暴露;vision(三路交叉)+像素双验证内容完整渲染(连续正文/无空白/无圆点)——渲染层无恙,纯语义暴露缺陷
  - 影响面=自动化验证可靠性+#467 dump 实证段污染(核心 SGR 日志证据不受影响)+a11y;待定位暴露分叉(疑 rememberAsyncMarkdownState 路径)
  - 证据:/tmp/ui_b1.xml(底,盲) vs /tmp/ui_top.xml(顶,正常);#475「近空白/折叠态」原判即此 artifact
  - 2026-09-29 定罪深化(见journal):分叉=单part纯文本长度(同turn 993暴露/3585盲,表格组件不受影响);渲染三层同构排除(组件/库重载/success槽);超视口单item假设被未分段表格turn(3674px)暴露推翻;最强嫌疑=SPLIT_PARAGRAPH 3000拆段线;androidTest断言已写但被MIUI+idle挂死双阻塞;修复候选A完结即分段/B到达即分段/C拆段线定罪后修normalize/D平台上报——待用户裁决,不盲改
  - 2026-09-30 定性翻转(用户裁决B降级观察):今日冷启进程两会话十几个turn(0-20k chars全路径)零盲例——五假设全推翻(拆段线:3585原文标准段落格式未触发/Q换帧:7523混合part同经换帧暴露/长文=盲:4077五段纯散文注入段段暴露/路径=盲/密集dump自伤:10连dump后仍暴露);原定罪(长文语义零暴露)重定性=疑似长跑进程状态性退化(R假设:昨21:22同屏老表格turn暴露vs新essay盲分野=内容创建时刻;盲取证全部集中于4h+单一进程晚期);再撞上(长跑进程新组合内容dump全盲)即抓:logcat a11y错误+dumpsys meminfo+uptime;A11yDiag探针留树备用
  - 2026-10-09 用户裁决：转 #535 soak 主动狩猎（周期盲 dump 检测器：dump vs mock 语料/Room 直查 ground truth；命中即抓 a11y 错误+dumpsys accessibility+meminfo+uptime；R 假设=同语料进程早晚期对比可同批验证）
  - 2026-10-10 B2评估：两晚5起blind捕获全良性（短回复/鉴权失败错误轮的稀疏但有效dump；像素var 800+证实内容丰富）。新增判读工具沉淀：①像素统计（mean/var）快速分盲屏与暗色内容屏；②uiautomator bounds为视口裁剪值——钉在视口顶的块其上方元素整棵不出树（#488批3①勘误），裁剪线以下缺席≠未渲染。无真盲渲染线索→维持观察。
  - 2026-10-21 评估收口：①现状复验（模拟器 mock 90 行长文流完成后）——9-29 定罪的最坏形态「可见区仅 1 空节点全盲」不再现（视口 71 节点 8 文本，思考卡标题/reasoning 片段/prompt 均暴露），但 mock 正文行文本仍未见暴露（滚屏采样被贴底自愈干扰，正文区盲否未定证）；A11yDiag 探针在流式大流量下被 logcat 滚掉（判读须及时收割或加缓冲）②改进方向评估：A=app 侧 semantics 注入（真实收益=读屏无障碍+E2E 断言可靠，但动 markdown 承重组件域需完整回归窗口）B=测试侧双通道（#535 已沉淀 dump+截图，零成本）C=断言规约（E2E 避开纯 dump 文本依赖）③判定：测试影响已缓解（B/C 既有实践），a11y 面收益真实但本应用户群需求低——**维持观察，若修走 A 且与 B4 同窗口（ChatScreen 稳定窗），触发条件=读屏需求或 E2E 再踩盲**

- [ ] **#454 v1 真机 IME 换行注入后 prompt 未发出** `chat` `device` `v1`
  - 真机 IME keyevent 66 发送路径:消息含注入换行(Run\n\n)时 prompt POST 未发出,乐观气泡悬挂;二次干净发送正常(prompt_async 202)。发送链路疑有 IME 竞态边角,#453 验证时顺带观察,未复现第二次
  - 2026-10-09 用户裁决：转 #535 狩猎（两层：单元红用例 sendMessage("Run\n\n") 断言 sendPrompt/parts 形状先行排除纯逻辑面；E2E 注入循环 50-100 次扫时序面）
  - 2026-10-10 B2评估：纯逻辑面单测早已排除（sendMessage('Run\n\n')正确POST raw text）；仪器腿最终3/3零MISS（态守卫=仅idle发送键时注入后，前两晚4起MISS全归因忙窗/导航竞态/通道陷阱——LogcatCapture缺席假阴性+IME动画坐标陈旧）。无缺陷线索→维持观察；h454双通道断言（屏上回显or POST计数）沉淀为可复跑资产。
  - 2026-10-21 模拟器 soak 5h（队列串行腿1，/tmp/soak-454/）：720 轮双通道断言零 MISS（Run\n\n 121 轮+换行变体 479 轮全 SENT，无乐观气泡悬挂）——症状在模拟器域未复现，观察维持。装置局限四点如实入档：①keyevent 66 硬键流未覆盖真机软键盘 IME action 分发（原症状路径）②busy 时序面未真实构造（mock 8 行流 3s<注入耗时，103 busy 轮全降级 settle）③AOSP vs MIUI 输入栈差④单日窗。附两起工程事故已定罪归档：oomd 杀 gnome-shell（19.6min 空窗自愈）+watchdog 收口竞态（GATE epoch/相对秒错配误降级 DONE，零数据影响）

## P4 — 外部前提阻塞

- [ ] **#381 192.168.110.248:248 重配——凭据宿主零痕迹不可探查（2026-09-09 用户裁决入 P4）** `infra`
  - **前提**：上游提供远程凭据探知能力——用户原话「后续看看 dsh 官方是否会支持远程探知 token 或其他认证手段」（DSH 官方远程 token 发现，或 OAuth/设备码流等替代认证）；前提满足后 `cred-probe.sh opencode 248 <名称>` 即可接管（/proc 探针现成）；若上游确认不会支持，再议弃用或人工一次性重配
  - 2026-10-09 前提重验：未满足维持——oauth/device_code/mdns/discovery 全包 0 命中、CLI 无 token 子命令、README 明文 no query token outside root exchange / --host 0.0.0.0 unsupported。边际变化：cookie 签名 secret 已持久化 .credentials.yaml（跨重启有效默认 30 天），官方文档化「token 一次性兑换长期 cookie」——未来可探索兑换复用。证据：docs/research/2026-10-09-p4-upstream-preconditions-check.md
  - 2026-10-23 0.2.1 前提重验（用户裁决基准=dsh-v0.2.1-rc.2，2026-10-17 最新）：**阻塞维持**——连接 token 远程探知仍无：①credentials 域设计红线=secret 单向不可读（ctx.remote.credentials 仅 describe/set/unset 跨 remote wire，无读路径；describe 视图无值槽位是显式安全设计）②新增 dsh-authorization 包（人工引导授权流：OAuth/一次性码/账号选择——插件注册 flow+surface 发起）与 deepseek-account（浏览器 PKCE 登录，startSignIn 要求 callbackOrigin=loopback 本地回环）均**本机限定**（0.2.1-rc.1 changelog 明言「Web 版支持在本机 HTTP 访问时登录」）。边际变化备查：远程凭据**写入**面已开（config surface 可经 remote 写引用值）——若上游未来把 authorization begin() 的 interaction 暴露给 remote wire（远程发起授权流）即达成「替代认证」前提，届时重验

- [ ] **#332 spill 提示行——服务器无结构化信号（工具结果溢出 notice 内嵌纯文本）** `dsh` `sse`
  - **前提**：dsh-spill-policy 全链查实——溢出替换为有界 head/tail 预览+locator 提示全部内嵌工具结果 output 文本,transcript 无 spill 事件（session 事件枚举/types/实现三路 grep 0）;文案模式匹配脆弱（#136 先例:服务器改文案即静默失效）。待服务器暴露结构化字段再实现。→ `docs/research/2026-09-05-audit-309-313.md` #312③ + 实现 agent 取证（暂不可实现）
  - 2026-10-09 前提重验：未满足维持——61 事件枚举/ToolResultMessage 载荷零 spill 结构化字段，上游自认文本约定不可认证（hasSpillNotice 注释「not authenticated tool-output origin」+ README Known Limitations 首条）。可参考上游固定拼写 formatSpillNotice 实现替代自造正则。证据：docs/research/2026-10-09-p4-upstream-preconditions-check.md
  - 2026-10-09 用户裁决：授权降级实现（上游固定拼写文案匹配，实现处须详细注释约定来源与不可认证性）→新卡 #536；本卡维持 P4 挂结构化信号长期观察，上游升级时复查后切换
  - 2026-10-23 0.2.1 前提重验：**阻塞维持**——spill 仍纯文本约定（0.2.1-rc.2 与 master 的 spill-policy/notice.ts 同构：formatSpillNotice/hasSpillNotice 文本拼接与识别，注释自认「identifies the text convention, not authenticated tool-output origin」）。利好备查：hasSpillNotice 为上游官方导出的权威识别函数（与 #536 降级实现的文案匹配同构判定）——若上游改拼写，函数同步演进，客户端 #536 实现的判据可参照其源码校准

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
  - 2026-10-19 拆卡：消费腿落三卡——#559（取证腿，R1 前真机顺带）/#560（①A/B 三缺口实现，B4 窗口）/#561（⑤A outputPaths 消费，B4 窗口）；⑤B/C 视 #559 结论为 B5 候选；①C（DSH 式转录实体）不排期（收益与现有 Part.Compaction 分割线形态重叠）；本卡余 PR 候选线（③>⑥>①>②）维持 P4 用户流程门槛
  - 2026-10-19 深夜勘误：前 note 所记「拆卡落三卡 #559/#560/#561」为重复登记——#539（10-10 11:54，调研报告后 2 小时）已把 ①A/B+⑤A/B 全部消费（现网取证+实现+验证齐备）；三卡已迁移关闭，今晚独立复测交叉验证一致（见 2026-10-10-539 journal 追加节）。本卡维持 P4 余 PR 候选线（③>⑥>①>②）不变
  - 2026-10-23 上游复查（PR 候选线收窄）：**⑥上游已重做**——anomalyco/opencode 8-26 合入 #44844（后台任务 durable 化：kv 持久记录/重启恢复/持久终态）+10-18 #56321（shell 状态延迟到输出捕获完成）+10-19 #56982（killed 状态启用+事件序稳定化 exited(killed)→deleted）——原症状「后台 shell 状态恒 completed（exit 7 亦然）」的 status 域已三态化+持久化，⑥的 PR 候选资格消失。触发本卡登记的「后台 shell 重建后复查」：客户端防御性派生（正文文本判失败）可否退役=下次真机窗口顺带实测（连 4096 触发后台 shell 看 shell.exited 状态分布）。②③维持 wontfix、①已修已消费（#539）——**本卡 PR 候选线收窄为仅③**（V2 cursor 400 降级，官方 NOT_PLANNED 已定局，客户端只产 V2 cursor 自救为终态——③实际也无 PR 可提，卡实质=上游问题归档观察）
