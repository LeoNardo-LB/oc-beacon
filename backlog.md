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

