# 服务器类型交互统一审计（2026-09-07，用户裁决触发）

> 铁律（ui-conventions「服务器类型交互统一铁律」）：DSH 面与 OpenCode 面 UIUX 高度统一——服务器类型只产生**能力位差异**（功能有无，ServerCapabilities 门控），不产生**交互模式差异**；**参照系 = 本 app 的 OpenCode 服务器面**（oc-beacon 既有交互语言）；实现层各自最优（DSH 有原生接口用 DSH 的，opencode 没有则 oc-beacon 自实现），对用户不可见。
>
> 审计方法：共享组件的 serverType/能力位门控 grep + git 史考古（#326/#313/#311/#309 演变链）+ 发送链源码通读（ChatSendDelegate）。符号：OK=已一致；CAP=能力位合理差异；BAD=违反统一待修；ASK=待用户裁决。

## 一、差异矩阵

### A. Composer / 发送域

| 交互 | opencode 面现状 | DSH 面现状 | 判定 |
|---|---|---|---|
| 发送键形态（单键/双键） | 单键（共享组件） | 单键 | OK 一致（但系 #326 以 dsh web primaryStops 校准的产物——参照系错位，行为本身待 #348 重定） |
| busy+发送 | 静默入服务器队列（V2 服务端 followup） | 静默入服务器队列（session.prompt mode=queue） | ASK #348：用户裁决要有「气泡选择框/可见排队」——app 史上从未有过选择框（演变链：双键并存 18ae1a3d → 单键 555c2ba4；QueueDock f2df106c 已退役）。统一设计待裁决（见 §三-1） |
| steer 插话 | 无此概念（V2 wire 无 mode） | 长按发送键=mode:steer 直发 | CAP 能力位差异（形态同入口） |
| 空闲长按发送键 | shell 模式切换（V2 shell 域） | steer 长按（#309④；DSH 无 shell） | ASK 同手势两面不同语义——按能力位分派算合理？还是给 steer 换入口？ |
| 停止/中断 | 停止键 | 停止键 | OK |
| 等待提问/权限输入禁用 | inputBlocked 单键停止形态 | 同 | OK |
| 斜杠命令 | V2 command 域共享面板 | DSH commands 域共享面板 | OK 能力位同构（#276） |

### B. 队列容器

| 交互 | opencode 面现状 | DSH 面现状 | 判定 |
|---|---|---|---|
| FAB QUEUE 入口 | 入口在，QueueSheet 恒空（数据源=DshQueueStore 仅 DSH） | 入口在，有数据 | BAD 入口无能力位门控（空态泄漏）→ 统一方案见 §三-1（自实现队列 vs 隐藏） |
| 排队消息可见性 | 不可见（服务器端静默排队） | FAB QueueSheet（编辑/移除/steer） | ASK 并入 #348 统一设计 |
| QueueSheet 三动作 | — | 编辑/移除/插话 | ASK 同上 |

### C. 会话列表域

| 交互 | opencode 面现状 | DSH 面现状 | 判定 |
|---|---|---|---|
| 左划归档 | 无（archiveSupported=false） | 有（#311 Task2；#342 修过背景） | →#347 已裁决：手势整体下线，归档仅长按菜单（两面统一按能力位出菜单项） |
| 长按菜单 | 共享 sessionRowMenuActions(archiveSupported) | 同（归档项在场） | OK 能力位门控统一 |
| 归档能力本身 | V2ApiClient 无 archive（官方 web 有 archiveHomeSession——上游存在，app 未接） | V012 workspace/archiveSession | CAP 未来 V2 接 archive 时自动统一（长按菜单+已归档折叠区同款） |
| 多 workspace 分组/已归档折叠区 | 无此能力 | 有 | CAP 能力位合理 |
| 状态点/待回答指示 | PendingInteractionStore 全局域（V1/V2 PermissionAsked/QuestionAsked 事件同记录） | 同 | OK（D5 仪器验证在 DSH 面；V2 面同组件） |
| 搜索 | 共享双区（消息匹配+会话） | 同 | OK |
| 新建会话 | 快速对话框（最近目录）→ 直接进 chat composer | 快速对话框（workspace 真建模）→ 进 chat 后「开始会话」向导（preset+模型，agentPresetSupported 门控） | ASK 向导步=DSH 服务器契约（session.create 需 agent）→ 能力驱动合理，但向导形态是否统一化（如并入快速对话框/首条消息前 inline 选择）待裁决（§三-3） |

### D. Chat 表面组件

| 交互 | opencode 面 | DSH 面 | 判定 |
|---|---|---|---|
| 轮次台账/产出行/反馈钮/PlanChip/@补全/相对时间戳 | 共享渲染（RenderableTurn 等服务器无关投影） | 同 | OK |
| deliverables 产出行 | 无数据（V2 无对应域） | 有 | CAP 能力位 |
| subagent 工具卡 | AgentSheet 入口在 | 不可点击 | →#349 已裁决：卡片点击直达子会话（两面统一加） |
| 命令反馈卡 | V2 command 域 | DSH commands 域 | OK 同构（#323） |

### E. FAB / 面板容器

| 交互 | opencode 面 | DSH 面 | 判定 |
|---|---|---|---|
| FAB 五入口（TODO/AGENT/SHELL/GOAL/QUEUE） | 五入口硬编码常驻（ChatFabMenu 无能力位参数） | 同 | BAD 违反：SHELL 在 DSH=空态；GOAL/QUEUE/TODO 在 V2=空态或无数据 → 入口应按能力位隐藏（GOAL=goalSupported、SHELL=terminalSupported、QUEUE 见 §三-1） |
| AGENT（子代理）面板 | V2 后台任务同容器 | 子代理列表 | OK 同容器异数据（能力位语义） |

### F. 设置 / 服务器管理

| 交互 | opencode 面 | DSH 面 | 判定 |
|---|---|---|---|
| provider 目录/preset 管理/配对深链 | 无此能力 | 有（标准组件形态） | OK 能力位+统一组件 |
| ServerDialog | 共享 | 共享+DSH 首次配对辅助区 | OK 能力位 |
| 模型选择器 | 共享 picker（V2 模型域） | 共享 picker（modelCatalog 分组） | OK |

### G. 通知

共享 SessionNotificationCoordinator/AppNotificationManager，无 per-face 分支 | OK（#346 刚修可见性）

## 二、已裁决项（本审计确认落位）

1. #347 左划归档下线 → 长按菜单唯一入口（矩阵 C-1）。
2. #348 busy+发送统一重设计（矩阵 A-2/B-2/B-3 并案）——形态待 §三-1 裁决。
3. #349 subagent 卡片点击直达（矩阵 D-3，两面统一）。
4. 发送前确认=off（设备设置已关，默认 false）。

## 三、待裁决项（按影响排序）

### 1. busy+发送与队列可见性（#348 并案）——已裁决(2026-09-07)：恢复 8-20 气泡菜单（详见 backlog #348 定案）

事实勘误（用户记忆确证）：ce8cbc1e(2026-08-20) 曾有 busy+点发送→气泡菜单（立即发送[服务端排队]/堆积消息[本地轮末自动发]），9-01 双键取代删除、#289 拆除本地堆积链、#326 单键——审计初版「从未有过选择框」漏挖一层（只考到 18ae1a3d 父提交），已修正。
- 方案 a（选择框）：busy+点发送 → 弹气泡选项（排队/插话[DSH]/中断）→ 选择后执行。中断从此有显式入口；但多一步交互。
- 方案 b（可见排队 dock）：busy+点发送 → 消息变成 composer 上方排队气泡（可见/可编辑/可移除，轮末自动发——复刻已退役 QueueDock 的交互但作为统一面）；opencode 面=oc-beacon 自实现本地队列，DSH 面=server queue 域镜像。长按 steer（DSH）保留为直发旁路。
- 方案 c（a+b 混合）：busy+点发送 → 小气泡二选一「排队/插话」；排队项落 dock 气泡。
- 建议：b（排队可见性是用户原始痛点「直接归位队列消息」；中断已有停止键；steer 有长按旁路）；FAB QUEUE 入口随后可退役（dock 即队列面）或保留为全量列表。

### 2. FAB 入口能力位门控（矩阵 E-1，纯修不需裁决）

GOAL/SHELL（及 TODO 若 V2 无域）按 ServerCapabilities 隐藏；QUEUE 随 §三-1 定。可直接做。

### 3. DSH 新建会话向导形态（矩阵 C-7）——已实施(2026-09-07 批 3)

裁决（用户倾向）：压进快速对话框一步完成。落地：NewSessionQuickDialog 在 DSH roster 非空时于标题下渲染**内联预设选择行**（「智能体预设 | 默认 ▼」DropdownMenu，标准组件）——workspace 条目与预设同屏，选条目即连接（默认=不预选=现行为）；connectWorkspaceEntry 增 presetId 腿（创建/复用后 selectAgentPreset，软失败不阻断导航）；会话内空态预设卡保留为改选通道；模型仍由 composer chip 承载（两面同构，audit F-3 OK 位）。opencode 面 roster 恒空表→行不渲染（结构保证：默认参 emptyList + isNotEmpty 门控 + 仓库契约）。真机：预设行在场、下拉四档、选「标准模式」+连接 workspace → 服务器侧新会话 agentPreset=standard 落位。

### 4. 空闲长按发送键的语义分裂（矩阵 A-4）

V2=shell / DSH=steer。维持（能力位分派）还是给 steer 独立入口（如队列 dock 的「立即发送」钮）？随 §三-1 一并定。

## 四、实施批次建议

- 批 1（无裁决依赖）：FAB 能力位门控（§三-2）；#347 左划下线；#349 subagent 卡点击。
- 批 2（§三-1 裁决后）：#348 busy-send+队列统一面（含 QUEUE 入口去留、opencode 自实现队列、DSH 走 server queue）。
- 批 3（§三-3 裁决后）：新建会话向导统一化。
- 批 4：V2 archive 接入（上游已有，顺带统一归档面）——低优先。
