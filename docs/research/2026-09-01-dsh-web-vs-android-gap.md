# DSH Web 前端 × OC Beacon（Android）功能差距全景调研

> 日期：2026-09-01 · 方法：四方证据交叉验证（qa-methodology ≥2 维互证）
>
> | 证据路 | 产出 | 说明 |
> |---|---|---|
> | ① 前端源码清点（subagent） | `docs/research/dsh-gap-2026-09-01/fe-inventory.md` | 28 模块逐包 README + client.js 取证（DSH 0.1.1-rc.2 npm 包内） |
> | ② Android 端清点（subagent） | `docs/research/dsh-gap-2026-09-01/app-inventory.md` | DshApiClient 28 RPC 入口 + 49 型事件消费面 + UI 全量（file:line） |
> | ③ Web 实测走查（主 agent 浏览器） | `docs/research/dsh-gap-2026-09-01/web-walkthrough.md` + `shots/01-12*.png` | 127.0.0.1:3080 实机交互：点按钮/试输入/逐弹层枚举 |
> | ④ 服务端 RPC 能力面（subagent） | `docs/research/dsh-gap-2026-09-01/api-gap.md` | 服务器暴露方法全集 × 双端消费对比 |
>
> 差距标记：❌ 未实现 · ◐ 部分实现/形态差异 · ⏸ 已有 backlog 卡 · 🔒 协议限制（loopback-pinned 等，远程客户端不可达，非 app 可修） · ✓ 已对齐 · N/A 不适用（app 级自有能力）

---

## 0. 结论速览

Android 端对 DSH **核心会话链路**（列表/聊天/流式/工具卡/审批/提问/队列/目标/模型/权限/预设/子代理树/上下文环/导出/斜杠命令）已高度对齐：服务端 56 旧式路由 + 26 typert 端点（含 12 条 Web 专用 cordis）+ 2 非信封端点中，Android 已消费 27 方法 + 2 端点；事件帧消费 15/19。真正的差距集中在**九个点**：

0. **两个潜伏 bug（最高优先，修复而非新功能）**：
   - ⚠ **`host/remote-event` 解包缺失**：服务端把转发事件（commands/change、agent-preset/selected、llm/adapters-updated、settings/document-updated、credentials/reference-updated 等 11 个）一律包在 `host/remote-event` 帧里发送（api-remotes index.js:19-31 + apiproxy index.js:3691-3694），官方客户端在 client-runtime client.js:10518 解包分发；Android 无解包分支 → `DshEventMapper.kt:162` 的裸 `commands/change` 分支是**死代码**——#285 命令注册表刷新链路在 WS 上实际从不触发，另四类通知同样静默丢失。
   - ⚠ **`/compact` 通道存疑**：Android 把 `"/compact"` 文本当 session.prompt 文本发送（DshApiClient.kt:196-204），与 2026-08-31 permission 调研活体实证「prompt 不派发斜杠命令（leading-/ 变 user/message 进模型）」直接冲突——压缩可能实际发出的是普通用户消息，建议 E2E 复核（或改走 commands/execute）。
1. **`@` 引用系统**（文件/目录/会话 mention；fileReferences/list + sessionReferenceResolver/candidates）——Web composer 核心交互，Android 零落地 ❌
2. **轨迹视图**（请求级检查器 + 时间轴缩放平移）——Web 独立视图 tab，Android 无 ❌
3. **工作区/会话组织**（workspace.create/rename/delete/排序/归档 + 4 个 host/workspace-* 帧）——Android 仅只读 list ❌
4. **消息反馈**（👍/👎 + 备注，messageFeedback 三方法）与**产出文件行**（deliverables）❌
5. **Plan 模式**（chip/plan-mode 事件/plan-review 专卡）域缺失 ❌
6. **子代理续聊**（subagent.prompt/interrupt/history）——Android 走 session.prompt 被 agent-busy 拒 ❌
7. **steer 插话直发**（prompt mode=steer）——Android 恒 queue ◐

注：**会话内容搜索非差距**——`session.search` 端点虽在，但本部署 `openAt="never"` 禁用（④ 活体实证），Web 端同样退化为标题匹配。另有整批 **🔒 loopback 特权面**（Models/Plugins 设置 CRUD、agentPreset 复制/删除、host.openPath/pickDirectory、打开配置文件、凭据写入）——fe 源码判读为 loopback 钉死（远程不可达，Web 远程浏览器同样不可见），属协议设计而非 app 遗漏；④ 报告未远端验证可达性，若实测可远程调用则 credentials.set + llm.discoverModels（模型自助）可升格为实现项。
**〔2026-09-02 §11 更新〕**上述悬案已终判：特权面 15 方法 403 活体实证、不可升格（§11.2，且波及已消费的 settings.* → #298）；@ 引用/工具卡类型化/压缩 UI 三项旧判已勘误（§11.3）；量化总账与逐项工程量见 §11.1/§11.4。
**〔2026-09-02 §12 更新〕**前端功能面（用户可见功能点）双端普查与量化对账完成：Web 有效 144 点，Android 覆盖 80（56%）·缺 47（可实现 41）·反向超集 84 点——见 §12。

---

## 1. 会话管理域

| # | 功能 | Web 端（证据） | Android 现状 | 判定 |
|---|---|---|---|---|
| 1.1 | 会话搜索 | 标题即时匹配 + 250ms 防抖内容搜索（`session.search`，ranked 片段，上限 20 条；失败降级名称匹配）（fe §2.3；实测③搜索框） | 本地 title `contains` 全量拉回（app §1.1/§5.6） | ◐→✓ **本部署 `session.search` openAt="never" 禁用**（④ 活体实证），Web 同样退化为标题匹配；未来部署启用时 Android 需接入 |
| 1.2 | 会话归档 | 行菜单「归档会话」（`workspace.archiveSession`；非破坏、无 unarchive 面）（fe §2.3；实测③操作菜单） | 无任何归档面；`archived-sessions-changed` 帧 Ignored(HOST_WORKSPACE)（app §2.1/§5.7） | ❌ |
| 1.3 | 手动排序/拖拽 | 「手动排序/最近更新」×「按工作区/单列表」；拖拽持久化（`workspace.insertBefore/insertSessionBefore`）（fe §2.3；实测③视图选项） | 无排序设置、无拖拽（树按 parentSessionId 分组） | ❌（价值中） |
| 1.4 | 会话行状态点 | 运行/等待审批/计划待审/等待回答/子代理运行中/未读完成（绿点）（fe §2.3） | busy/idle（running 播种 #278）；无审批/提问/未读粒度状态点 | ◐ |
| 1.5 | 悬停卡（创建时间/复制路径/复制标题） | fe §2.3 | created 恒 "—"（协议无 created 时刻）；长按复制路径无 | ◐（🔒 created 缺席） |
| 1.6 | 会话重命名/分叉 | 行菜单：重命名/分叉会话（末完成轮，标题 (N) 自增）（实测③） | 重命名 ✓（详情对话框）；fork ✓（会话级复用卡）；消息级分支入口无（Web 也仅轮尾消息可用） | ✓（形态差异） |
| 1.7 | 会话删除 | Web 无此功能（52 方法面无 delete） | 无（能力位登记 unsupported） | N/A 双无 |

## 2. 工作区/目录域

| # | 功能 | Web 端 | Android 现状 | 判定 |
|---|---|---|---|---|
| 2.1 | 添加工作区 | 添加工作区… → native picker 或应用内 Miller 双列浏览器（新建文件夹=`host.createDirectory`、显示隐藏、路径可编辑）（fe §2.23；实测③截图 09） | 无添加面；workspace.list 只读 | ❌（`host.listDirectory` 已有，browse 形态可复用；native/pickDirectory 🔒 loopback） |
| 2.2 | 工作区重命名/删除 | 菜单：重命名/删除工作区（删除有确认框，会话落未分组）（实测③） | 无 | ❌ |
| 2.3 | 多 workspace 建模 | 全量并行；新会话 hero 有工作区选择器 | `workspace.list` 只取首个 path 做根；会话目录过滤按 cwd 全等——跨 workspace 场景未建模（app §5.15/16） | ◐ |
| 2.4 | 新建会话选 workspace | hero 选择器 + 预设 chip 并排（staged） | `createSession` 接受 cwd，但 UI 目录选择来自既有项目列表，无 server 端浏览 | ◐ |

## 3. Composer / 输入域

| # | 功能 | Web 端 | Android 现状 | 判定 |
|---|---|---|---|---|
| 3.1 | **`@` 引用** | `@文件/@目录/@会话` 三源（`fileReferences.list` + `sessionReferenceResolver.candidates`）；`@"带空格"`；目录下钻；会话 mention 进 `dsh-session:` 规范 ref（fe §2.8；实测③触发 listbox） | 零落地（无触发器、无引用 part） | ❌ **大功能** |
| 3.2 | 斜杠命令 | `/` 源：7 宿主命令 + 全部技能；fuzzy 菜单；三路分发（fe §2.6；实测③截图 02） | commands/list roster + commands/execute + input.hint 参数填充（#285 已接） | ✓ |
| 3.3 | `+` 命令启动器 | composer 加号打开 `/` 源（非附件钮）（fe §2.4） | 斜杠入口即命令面板（形态等价） | ✓ |
| 3.4 | **steer 插话直发** | `session.prompt` mode=steer（注入进行中轮次）；空草稿 Cmd+Enter=全队列依次插话（fe §2.4） | mode 恒 "queue"（busy 双键发送=排队）；steer 仅对**已排队项**经 updateQueue 生效（app §1.1/§5.2） | ◐ |
| 3.5 | busyEnter 行为对调设置 | Settings→General「繁忙时 Enter 键行为」（插话/发送）（实测③） | 无此设置 | ❌（小，依赖 3.4） |
| 3.6 | 草稿撤销/重做 | Cmd+Z / Cmd+Y（fe §2.4） | 无 | ❌（移动端价值低） |
| 3.7 | 图片附件（发） | 粘贴/拖入 + imageLimits 预检 + 64px 草稿轨 + lightbox（fe §2.22） | 发送侧 image content 块 ✓；接收侧 #287 回源 ✓（#295 跨进程丢失未决）；lightbox/草稿轨形态弱 | ◐ ⏸#295 |
| 3.8 | 非图片附件 | 仅图片（文件卡未做）（fe §2.22 自认） | 仅图片（app §5.4） | ✓ 双方都仅图片 |
| 3.9 | 引用 chip 对齐渲染、复制/剪切序列化 | fe §2.4 | 无（随 3.1） | ❌（随 3.1） |

## 4. 命令 / 技能 / 状态域

| # | 功能 | Web 端 | Android 现状 | 判定 |
|---|---|---|---|---|
| 4.1 | /compact 压缩 | 命令执行（command.execute）；压缩检查点行 + 摘要查看（fe §2.4/§2.6） | 「压缩」按钮把 `"/compact"` 文本当 **session.prompt 文本**发送（DshApiClient.kt:196-204）；与 2026-08-31 permission 调研活体结论「prompt 不派发斜杠命令（leading-/ 变 user/message 进模型）」**直接冲突** | ⚠ **疑似潜伏 bug，建议 E2E 复核**（或改走 commands/execute） |
| 4.2 | /goal 目标 | `/goal` 创建；GoalBar 编辑/暂停/恢复/清除（fe §2.13） | GoalSheet 全五动作 API 在位：create/edit/pause/resume/clear ✓；**complete 有 API 无 UI 按钮**（app §5.1） | ◐ |
| 4.3 | /plan 计划模式 | Plan× 黄字 chip（点按 /plan off）；plan/mode 事件；placeholder 切换；plan-review 问题专卡（Chat about it/Refuse/Approve）（fe §2.11/§2.20） | plan/mode 事件 Ignored(POLICY_STATE)；无 chip 无专卡（app §2.2/§5.11） | ❌ 域缺失 |
| 4.4 | /permission | 三档菜单 + Full access 风险确认 Modal（勾选才激活）（fe §2.12；实测③） | 选择器 ✓（动态档集 #283）；**无 Full access 二次确认**（app §4 调研定音） | ◐ |
| 4.5 | /model | 两级菜单：模型→推理档（fe §2.10；实测③截图 03：Low/High/Max） | 模型 + 思考档位 pill ✓（session.selectModel + reasoningEffort） | ✓ |
| 4.6 | /export | 头部 Session log 按钮 → ZIP 下载（实测③；纠正 fe 报告「无前端 UI」的存疑） | ZIP 流式导出 + 进度 ✓ | ✓ |
| 4.7 | /feedback + 消息反馈 | 消息级 👍/👎 + 备注（portaled 编辑器）；再点撤回；CAS version（fe §2.19；实测③按钮存在） | 无（feedback/record 事件亦 Ignored） | ❌ |
| 4.8 | 技能源 | `/` 含全部技能（仅用户标记）；skill 工具行展开看指令（fe §2.7；实测③菜单含 33 技能） | skill.list 需 attached 会话——冷会话 session-not-found 恒空（协议坑，app §4）；技能行无 | 🔒/❌（协议坑 + 无 UI） |
| 4.9 | Agent 预设 | 四档菜单（标准/PTC/极简/创造）+ 描述（实测③截图 04） | 空白页预设卡 ✓ + roster 只读标签 ✓ + 默认预设设置 ✓ | ✓（管理面见 7.3） |

## 5. 会话内容渲染域

| # | 功能 | Web 端 | Android 现状 | 判定 |
|---|---|---|---|---|
| 5.1 | **轨迹视图** | 独立 tab：事件台账+检查器（token/时长/Input/Output/Timing）、时间轴滚轮缩放/右键平移/拖选区间、搜索、虚拟滚动（fe §2.18；实测③截图 07） | 无 | ❌ **大功能** |
| 5.2 | Markdown/Math/代码高亮 | GFM + KaTeX + Shiki 24 语言（fe §2.4） | Markdown ✓（表格一致性铁律）；KaTeX/Shiki 覆盖度待查 | ◐（细节差异） |
| 5.3 | Think 行 | 折叠行流式跟随；展开整段入正文（fe §2.4） | reasoning part 渲染 ✓（流式桥） | ✓（细节差异待走查） |
| 5.4 | 上下文注入/跨会话召回折叠行 | disclosure 折叠 + 生产者名（fe §2.4） | request/context、agent/inbox/spliced Ignored | ❌ |
| 5.5 | 压缩检查点 | 「已压缩 N 条（约 X tokens）」折叠行 + 摘要（fe §2.4） | 仅 compaction/end → snackbar；start/summary/prune Ignored（app §5.12） | ◐ |
| 5.6 | 重试/错误节点 | 重试行（倒计时+shimmer）；turn-error 内联；**max-tokens 专卡 + 「发送 continue 可续」**（fe §2.4） | llm/retry(-started) Ignored；无 max-tokens 专卡（app §5.10） | ❌ |
| 5.7 | 工具卡类型化 | terminal/read/diff/search/web/todo/question/code-dispatch 递归子调用树 + generic 兜底；生命态 shimmer/警告（fe §2.15） | 通用工具卡（callId 键控；参数 raw+input、输出展平） | ◐ |
| 5.8 | 产出文件行 deliverables | 轮尾文件 chip（≤6 + N files）+ Show in folder + **结尾散文内联代码文件提及可点**（fe §2.16） | 无 | ❌ |
| 5.9 | 工具行路径点击打开文件 | `host.openPath`（loopback 特权）（fe §2.15） | 文件点击禁用（无 openPath 能力位） | 🔒 |
| 5.10 | 统计 | 消息 hover TTFT/tok/s；统计条（轮/步/墙钟/token 计费/cache 命中率）（fe §2.4） | ContextDetailDialog：tokens 四桶 + turns/steps/llmMs/toolMs/ttft/decode ✓ | ✓（形态不同，数据齐） |
| 5.11 | 上下文环+构成 | ContextMeter 14px 环 + 构成面板（fe §2.4；实测③截图 10） | ChatTopBar 上下文环 + 四桶 + 构成图例 ✓ | ✓ |
| 5.12 | 后台任务 | 会话头 jobs 徽标 + popover（只读）（fe §2.14） | DshJobSheet + 消息流时间线卡 ✓ | ✓ |
| 5.13 | 工作流运行节点 | 阶段/成员树 + 成员跳子会话（fe §2.17） | synthetic 降级卡——**服务器不暴露事件，休眠**（#288 四面包夹实证） | ⏸#288 |

## 6. 子代理 / 审批 / 问答域

| # | 功能 | Web 端 | Android 现状 | 判定 |
|---|---|---|---|---|
| 6.1 | 子代理谱系导航 | 头部面包屑 + 目录树（token 合计/活跃时长/键盘导航）（fe §2.9） | AgentSheet 树（subagent.list L2 懒加载 + 本地镜像降级 #284-a）✓；面包屑无 | ✓（形态差异） |
| 6.2 | **子代理续聊** | continuable 子会话普通 composer（FIFO 发送走 `subagent.prompt`；Stop 走 `subagent.interrupt`）（fe §2.9） | 子代理会话发送走 session.prompt → **agent-busy 拒绝**；`subagent.prompt/interrupt/history` 三方法零消费（app §1.2） | ❌ **通道存在未用** |
| 6.3 | @ 运行中子会话 | `@` 源插 `@label `（fe §2.9） | 随 3.1 缺失 | ❌（随 3.1） |
| 6.4 | 审批面板 | composer 接管：拒绝/允许一次（**无 always**）（fe §2.4） | 权限审批卡：once/**always**/reject + 本地自动批准规则 | ✓（Android 更全） |
| 6.5 | 问答表单 | 一次一题+进度导航；单选/多选/自定义/跳过；IME 处理（fe §2.20） | 提问卡：选项 + 自由文本 + 取消 ✓ | ✓ |
| 6.6 | plan-review 专卡 | Plan review 条 + Chat about it/Refuse/Approve（fe §2.20） | 通用提问卡呈现（意图专卡无） | ◐（随 4.3） |

## 7. 设置 / 管理域

| # | 功能 | Web 端 | Android 现状 | 判定 |
|---|---|---|---|---|
| 7.1 | Models 设置页 | provider CRUD、API key 写入（credentials.set）、discoverModels 拉取、自定义 provider、首跑引导（fe §2.25；实测③截图 11） | llm 域只读；无管理面 | 🔒 **loopback-pinned**（远程不可达，Web 远程浏览器同样不可见） |
| 7.2 | Plugins 设置页 | 配置卡（bash/agent-loop/web-search）+ 全插件清单 + 提供商保活（实测③） | 无 | 🔒 同上 |
| 7.3 | Agent 预设管理页 | 复制创建/删除/查看器/设默认/打开目录（实测③截图 12） | 设默认 ✓；copy/remove/openDocument/read 无 | 🔒 部分（管理面 loopback-pinned；设默认已实现） |
| 7.4 | 打开配置文件 | settings.openDocument（loopback）（实测③） | 无 | 🔒 N/A |
| 7.5 | 语言/主题 | 宿主 locale/theme 偏好（loopback 持久） | app 自有 15 语言 i18n + 自有主题 | N/A |
| 7.6 | Cordis 动态插件面板 | 侧栏底部入口+徽标；approve/decline/run/stop/load（fe §2.21） | 无 | ❌（进阶功能） |
| 7.7 | 会话头详情面板 | `conversation.details.tool`——README 自认无入口（fe §2.4）；实测见「关闭详情」残留位 | 无 | N/A（Web 实际不可用） |

## 8. 传输 / 协议层差距（Android 对齐注意点）

**服务端面**（④，证据 = apiproxy/client-connection/api-remotes 源码行号）：
- RPC 全集 **56 旧式点分路由 + 26 typert 斜杠端点 + 2 非信封端点**（respond / session.export）+ 2 WS 事件流。
- 事件帧：events.mux 10 型 + events.host 9 型；`host/remote-event` 内白名单转发 11 个事件名（commands/change、agent-preset/selected、credentials/reference-updated、cordis/* ×4、llm/adapters-updated、settings/document-updated 等）。

**Android 消费面**：27 方法 + 2 端点已接通；帧面 15/19（忽略 4 个 workspace 帧）；**11 个转发事件 0 解包**（见 §0 bug 0）。

**服务端有、Android 零接触的方法**（④ §3a 全表）：
- 工作区组织：`workspace.{create,rename,delete,insertBefore,insertSessionBefore,archiveSession}`
- 子代理交互：`subagent.{prompt,interrupt,history}`
- 消息评价：`messageFeedback/{list,put,delete}`（typert）
- 引用/交付物：`fileReferences/list`、`sessionReferenceResolver/candidates`（typert）
- 模型自助：`credentials.{describe,set,unset}`、`llm.discoverModels`、`session.models`（llm.models 已覆盖主用途）
- 技能：`skill.list`（需 attached 会话，冷会话 session-not-found 恒空——已知坑）
- 预设管理：`agentPreset.{read,copy,openDocument,remove}`（🔒 loopback）
- 杂项：`settings.{openDocument,update,replace}`（🔒）、`host.{pickDirectory,createDirectory,openPath}`（🔒/宿主动作）、`pluginInventory/list`、`dynamicCordisRunner/*` ×12（Web 专用架构，Android 不适用）

**非缺口确认**（④）：goal 双轨等价（Android 用旧式 goal.*，typert goals/* 并存）；session.export、/api/respond、commands/*、approval/question 应答环均已接通。

**loopback 钉死集**（fe §0/§3.4）：远程客户端（Android 永远是远程）天然不可达——差距清单登记但不排实现；④ 未远端验证，如实测可达再升格。

---

## 9. 建议（ backlog 候选，待用户裁决后登记）

**P0（bug 修复）——已登记 backlog**：
0a. **#296** `host/remote-event` 解包分支缺失——commands/change 死代码，#285 命令刷新 + agentPreset/llm/settings/credentials 五类转发通知全静默（修复=DEM 加一个解包分支）
0b. **#297** `/compact` 通道复核——prompt 文本通道疑似把命令文本发进模型（E2E 复核，或改走 commands/execute）

**P1 候选**（用户价值高、协议可达）：
1. `@` 引用系统（文件/目录/会话 mention；依赖 fileReferences/sessionReferenceResolver 两 typert 服务接入）
2. 轨迹视图（先做台账+检查器，时间轴缩放可后置）
3. 工作区/会话组织（归档 + 重命名/删除 + 排序；workspace 六方法 + 4 帧）
4. steer 插话直发（prompt mode=steer；顺带 busyEnter 设置）
5. 子代理续聊（subagent.prompt/interrupt 通道，AgentSheet 树已就绪）
6. goal.complete UI 按钮（API 全链在位，纯 UI 补齐）

**P2 候选**：
7. 消息反馈 👍/👎 + 备注（messageFeedback 三方法 + feedback/record 事件）
8. 产出文件行 deliverables（轮尾 chip + 内联提及可点）
9. Plan 模式域（chip + plan/mode 事件 + plan-review 专卡）
10. 重试/turn-error/max-tokens 呈现（含「发送 continue」入口）
11. 压缩过程/摘要呈现（compaction/start/summary）
12. 工具卡类型化细分（terminal/diff/search 分卡）
13. Full access 二次风险确认
14. 行状态点细化（等待审批/提问/未读完成）、新建会话 workspace picker、多 workspace 建模、会话搜索接入（本部署禁用，留部署开关探测）

**🔒 协议限制登记项**（不排实现）：Models/Plugins 管理面、agentPreset 复制/删除、openPath/pickDirectory、打开配置文件、credentials 写入（若实测可远程调用再升格）、pluginInventory、dynamicCordisRunner。

**已有卡（勿重复登记）**：#295 附件跨进程丢失 · #294 回放通知风暴 · #288 workflow 休眠 · #278 僵尸 Busy · #289 本地堆积入口。

---

## 10. 第二轮走查补章（2026-09-02，主 agent 执行）

> 委派 subagent 走查在内核层被拒（`Browser is not available in subagent`——Browser Use 仅主 agent 可用，架构限制与多模态无关）；改由主 agent 按同一任务书执行。期间 `dsh-web.service` 曾被停止，已按原 systemd unit 重启。记录全文见 `web-walkthrough2.md`，截图 `shots2-real/13-17` + `shots2-fixture/18-26`。

### 10.1 对已有结论的修正/实锤

| 项 | 第二轮结论 |
|---|---|
| `@` 引用（差距 1） | **前端链路健康**：fixture 小数据集上 `@` 菜单完全可用——「文件与文件夹」组（带路径副标题）+「Session 对话」组（候选带 id/cwd/ISO 时间戳）。真实端裸 `@` 停「正在加载…」属 **410 会话真实工作区的部署性能问题**，非前端缺口；`@"` 引号形态（仅搜文件）在真实端正常返回 16 项 |
| 消息反馈（P2-7） | 第一轮误点的「好的回答」**未留下记录**（6 按钮全部 aria-pressed=false；官方语义记录后呈激活态、再点撤回）。无需清理 |
| 详情面板（fe 自认无入口） | fixture 实测同样无法打开——「openDetails 未接线」**实锤成立**，Android 无需对齐 |
| chunk storm（fe 存疑#3） | `startReasoningChunkStorm` **非 window 全局**（typeof undefined），控制台入口不存在，仅 DevTools 断点可达 |
| onboarding（fe 存疑#12） | fixture 空世界实锤弹出「**内测声明**」首跑对话框（继续按钮；fixture 内无法持久化确认状态故报错） |
| Android 对齐新点 | 侧栏搜索降级警告条文案实锤：「**内容搜索暂不可用，仅显示名称匹配。**」（session.search openAt=never 时出现——Android 本地过滤目前无任何降级提示） |

### 10.2 新增 UI 形态取证（Android 实现候选的视觉基准）

- **提问 composer 接管全流程**（fixture）：单选（radio+**推荐**徽章+自定义 textarea，**点选即前进**）→ 多选（checkbox+辅助文案+提交）→ 进度 N/3 + 上一题/下一题/跳过本题 + 收起问题卡片/放弃整组问题；提交后面板关闭 composer 复位。
- **审批面板**：「等待审批 …（可答：批准/拒绝后消失）拒绝 / **允许一次**」——确认无 always 档（Android 的 always 是超集）。
- **TodoDock 计数头**：「任务 1 已完成 · 2 进行中 · 1 待处理」。
- **max-tokens 截断卡**：「已达到输出 token 上限回答被截断…发送"继续"可让模型接着输出。」（对应 P2-10）。
- **工具卡**：折叠行=图标+工具名+参数摘要+状态点（红点失败态）；终端卡展开=ANSI 彩色输出（✓/✗ 计时、红 FAIL、覆盖率表）；Read 卡=下划线可点路径；Search/web/Grep/Glob 行。
- **会话级权限/模型 chip**：fixture 会话显示 Workspace Write + DeepSeek-V4-Flash **High**——chip 状态是会话级而非部署默认（Android 已同构）。
- **发送入队**：agent 忙碌时新消息进排队 dock + 停止按钮现身。
- **侧栏状态点**：橙=等待、绿=进行中、虚线=停止。
- **聊天视图「加载更早」按钮**（真实端长会话）——聊天侧第三翻页面（Android 是自动分页，形态差异）。
- **谱系下拉**（真实端「11 个子代理」，hover 150ms 开）：行=状态点+标题+工作区路径/任务类型+标签（#红队对抗测试 · 角色 · 可继续）。
- **轮尾统计条与消息 meta**：「5 轮 · 40 步| LLM 30m19s · 工具调用 42.2s| 首 token 平均 3.7s · 58 tok/s| 缓存命中 91%| 输入 5M tok · 输出 96.9K tok」；消息尾「日期 · 用时 · 首 token · tok/s」。

### 10.3 仍未验证（记入限制）

- jobs popover（安全会话无徽标可点；github 会话 OOM 禁入）
- 悬停卡（hover 时效性未捕获）、轨迹时间轴拖选/缩放的可视效果（两轮均无明显变化）
- Think 行展开交互（行存在性确认，展开未确证）
- 图片 lightbox 开关（fixture 图片为占位色块，未触发点击）

### 10.4 第三轮补查（2026-09-02 续，`web-walkthrough3.md`）

- **忙碌态发送语义**：Web 忙碌时 composer 圆钮整体变为「停止生成」——**无 Android 式停止+发送双键并存**；排队只能靠 Enter（按钮路径不可排队）。Android 的双键设计是对 Web 交互的扩展而非对齐。
- **轨迹 Turns/Calls 折叠实锤**：Calls→「… 1 tool call · echo」计数摘要；Turns→「Turn N #N USER … N steps · N tool calls」轮摘要。
- **「仅用户」技能标记上屏**（fixture 斜杠菜单：fixture-user-only 仅用户 ·）；命令目录 agent 作用域再证（fixture roster 与真实端不同：compact 描述差异 + 专有 echo 命令）。
- **语言切换全链生效**（fixture：对话框/侧栏/`<html lang>` 同步，可切回）。
- **模型副标题**：DeepSeek-V4-Flash 快速响应 / V4-Pro 复杂任务 / GPT-5；effort 子菜单对 DeepSeek 系存在。
- 仍验证不能：lightbox 点击、反馈备注编辑器（👍 点击执行但激活态未现身）、悬停卡、时间轴拖选/缩放可视效果、jobs popover、Think 展开判别（fixture 单行内容无法区分两态）。

### 10.5 第四轮补查（2026-09-02 续，`web-walkthrough4.md`）

> 委派验证：双 subagent 浏览器探测**双双抛 `Browser is not available in subagent`**——运行时挂载架构未变（多模态无关），主 agent 执行。

- **子代理下钻 + 续聊 composer（本轮最高价值）**：真实端「11 个子代理」hover 目录行结构实锤——标题+任务类型+**可继续**标记+token 合计（1.1M~3.9M tok）+活跃时长；点击下钻后子会话头部=「父 / 子」标题+「切换子代理」控件，composer 为**正常可输入续聊框**（`subagent.prompt` 通道 UI 形态，对应 P1-5 缺口）；点父标题一键回父。截图 33-34。
- **/goal 命令链路**（fixture）：命令输入=右对齐等宽 user 风气泡 + detached 结果节点「Goal created: …」；GoalBar 被 fixture 提问循环遮挡未入镜（按钮组以源码文档为准）。
- **Plan 模式激活**（fixture）：占位符切「描述你的任务以生成计划」实锤；Plan× chip 被遮挡。
- **「加载更早」实锤**（真实端）：正文 23K→64K，手动翻页入口。
- **真实端 onboarding 卡死（新发现）**：远程浏览器首访弹「内测声明」，「继续」报「暂时无法保存确认状态，请重试」且模态无法关闭（确认态持久化疑似 loopback 限定）——DSH web 自身可用性问题；Android 不渲染该 onboarding，无对齐义务。
- 四轮后仍未实测（均有源码文档兜底）：QueueDock 行内控件实操、分支按钮、lightbox、反馈备注编辑器、Think 展开、`+` 启动器、拖放附件、时间轴缩放/拖选、jobs popover、悬停卡、重命名表单（onboarding 阻断）。

### 10.6 第五轮冲刺（2026-09-02 续，`web-walkthrough5.md`）——清欠收官

**新实测成功 5 项**：
- **Lightbox**：点历史图片 → 全屏 dialog → Esc 关闭 ✓（截图 37）。
- **QueueDock 单行直显**：忙碌时 Enter 排队 → dock 行（消息+时间戳）位于 todo 条与运行指示之间；行悬停仅露「复制」——编辑/删除/插话不显（Android QueueDock 三动作已是超集）。截图 39-40。
- **分支按钮**：点击后会话树变化+导航切换 ✓（截图 41；(N) 标题自增形态未锐化——fixture 标题异步）。
- **重命名表单**：输入框预填当前标题 + 取消/重命名双钮，取消零变更（DOM 记录）。附带：上轮 onboarding 卡死自愈（确认态最终持久化）。
- **仲裁会话安全打开**（第二谱系样本：25 个子代理；无 jobs 徽标）。

**终判自动化不可达 8 项**（源码文档兜底）：反馈备注编辑器（提问接管遮蔽 ×4）、Think 展开判别（样本单行）、`+` 启动器（实测不可见——源码-实测差异登记）、拖放草稿轨（合成事件不等价）、时间轴缩放/拖选（三轮无变化）、jobs popover（无安全触发点）、悬停卡（8 次尝试含事件派发）、QueueDock 三控件实操。

---

## 11. 实现可行性与量化总账（2026-09-02 第二次深调：双 agent 审计 + 特权面活体终判）

> 基准变更：审计基于 HEAD `52dd8752`——**§0-0 两 bug 已修复出清**（#296 `77231e26` host/remote-event 解包、#297 `52dd8752` compact 改走 commands/execute）。事件帧消费随之升至 **15/19**（host/remote-event 计入；内层 2/11 转发事件有消费端，9 项白名单余项无域对应、留痕忽略），**余 4 帧全部属于工作区组织域**（host/workspace-changed·removed·order-changed + archived-sessions-changed，即 §9-3 项内一并接）。

### 11.1 量化总账

服务端 wire 面（源码精确清点，**勘误：api-gap.md §1a 记 56 条旧式路由系多数，实为 52**——UNARY_ROUTES 顶层键逐一枚举）：

| 类别 | 数量 | Android 现状 |
|---|---|---|
| 旧式点分路由 | 52 | 消费 26 |
| typert 斜杠端点 | 26（含 Web 专用 dynamicCordisRunner ×12） | 消费 2（commands/list·execute） |
| 非信封端点 | 2（/api/respond、session.export） | 消费 2 |
| WS 事件流 | 2（mux 10 帧型 + host 9 帧型） | 消费 2（帧型 15/19） |

**方法面 78 中消费 28（36%）**。缺 50 个方法，五分类：

| 分类 | 数 | 明细 |
|---|---|---|
| ✅ **可实现且远程可达** | **18** | session.models；subagent.history·prompt·interrupt；host.createDirectory；workspace.create·rename·delete·insertBefore·insertSessionBefore·archiveSession（12 旧式）+ messageFeedback/list·put·delete、fileReferences/list、sessionReferenceResolver/candidates、**pluginInventory/list**（6 typert） |
| 🔒 特权钉死（见 §11.2） | 13 | settings.openDocument·update·replace；credentials.describe·set·unset；llm.discoverModels；agentPreset.read·copy·openDocument·remove；host.pickDirectory·openPath |
| ⏸ 部署禁用 | 1 | session.search（openAt="never"，④ 活体实证） |
| 🌐 Web 专用 | 12 | dynamicCordisRunner/*（Web 插件运行时，Android 架构不适用） |
| ⚖️ 双轨等价（非缺口） | 6 | goals/* typert 六态（Android 已用旧式 goal.*，语义相同） |

18 个可实现项中 3 个价值打折：skill.list 需 attached 会话前提（DAC:840 恒空自知）、session.models 主用途被已消费的 llm.models 覆盖、host.createDirectory 场景稀缺——**净有价值 ≈ 15 个方法**，正好支撑 §9 的 14 项 UI 级候选（多项共享方法组）。

### 11.2 loopback 特权面终判（源码 + 活体双证，出清 §0 尾注悬案）

**门禁机制**（dsh-client-connection index.js）：
- `PRIVILEGED_METHODS` 15 项硬名单（:504-520）：settings 全域 5 + credentials 全域 3 + llm.discoverModels + agentPreset.read/copy/openDocument/remove 4 + host.pickDirectory/openPath 2。源码注释言明：读设置/凭据与写同罪（信息侦察面）、discoverModels 携草稿凭据+让宿主对任意 URL 发 GET——整面钉死 loopback，「直到真正的认证层存在」。
- 判定 = `isTrustedApiRequest(request, [])`（:538，**恒空信任表**——trustedHosts 配置也不解锁特权面）：纯 **Host 头**栅栏（:184-205，loopback 主机名 + sec-fetch-site/origin 浏览器标记；无 socket 远端地址校验，自我定位为防 DNS rebinding 而非认证）。
- 非特权方法的 LAN 可达性来自部署自动派生：绑定 0.0.0.0 时本机非内部 IPv4 全部进 trustedHosts（dsh-web-app index.js:90-93）。

**活体实证 4 发**（2026-09-02，全只读）：settings.describe@127.0.0.1 → **200**；session.list@192.168.110.248 → **200**；settings.describe@192.168.110.248 → **403 forbidden**；pluginInventory/list@192.168.110.248 → **200**。

**判决**：
1. §0 尾注「若实测可远程调用则 credentials.set + llm.discoverModels 可升格」→ **终判不可升格**。13 个特权缺口方法在 LAN/Tailscale 连接下协议钉死 403，无配置逃生；唯一豁免是 adb reverse（app URL=127.0.0.1 → Host 头 loopback → 全通，调试场景）。
2. **勘误**：pluginInventory/list 不在特权名单 → 可实现（P3：设置页插件清单），此前 §9 🔒 归类有误。
3. **波及现有功能（登记 #298）**：settings.describe/mutate 本就是特权方法，而 #282-a/#283-a1 已在消费（DshApiClient.kt:1150-1203 权限/Agent 预设默认档）——真机 WiFi 场景下两读两写全 403，`settingsNamespace` 返 null → UI 静默回退已知三档；既有 E2E 均走 adb reverse 故从未暴露。方向：识别 403 形态后 UI 明示「默认档管理需 loopback 连接」，不做 Host 头伪造（那是绕安全栅栏）。

### 11.3 判定勘误三项（UI 审计推翻 §0/§9 旧判，Android 实况比文档判定更好）

| 旧判 | 实况（file:line） | 修正后差距 |
|---|---|---|
| §0-1「@ 引用 Android 零落地 ❌」 | **文件/目录源已存在**：DraftInputDelegate.kt:62-125 + FileMentionSuggestions + FileMentionVisualTransformation（chip 高亮+草稿持久化俱全） | ◐ 缺会话源（sessionReferenceResolver）+ 消息渲染 mention 可点 |
| §9-12「工具卡类型化细分 ❌」 | **已类型化**：DefaultToolCardResolver.kt:30-95 分发 13+ 张 DSH 专卡 | ◐ 剩余为个别卡增补（question/skill 行），非从零做 |
| §9-11「压缩过程/摘要呈现 ❌」 | **UI 已完整**：CompactionCard 双态骑线分割线（CompactionCard.kt:41-60） | ◐ 仅 DSH 事件面未接（DshEventMapper.kt:475-476 Ignored），接线即活 |

另两则对账：**steer 现状是 Web 超集**（busy 时 composer 可输入 + QueueDock 逐条 steer 三动作，ChatScreen.kt:779-808；Web dock 行悬停只有复制）——「直发插话」只是补长按发送键一个入口；**子代理续聊 UI 通道 100% 就绪**（AgentSheet 点行直达子会话完整 Chat，PendingSheets.kt:266→ChatScreen.kt:1088，sessionParentId 已进 UiState），纯缺数据层三方法。

### 11.4 可实现性矩阵（数据面 × UI 面合并评级；S≤半天 / M 1-3 天 / L>3 天）

| # | 项 | 数据面 | UI 面 | 综合 | 关键事实 / 风险 |
|---|---|---|---|---|---|
| 6 | goal.complete 按钮 | **零增量**（DAC:372+ChatRepositoryImpl:402 全链在位） | S | **S（2h 级，全场最便宜）** | GoalSheet 动作行第四钮 |
| 11 | 压缩呈现 | S（拆 compaction 两分支；Part.Compaction.summary 域模型已备） | **≈0（UI 现成）** | **S** | 纯事件接线 |
| 13 | Full access 二次确认 | — | S | **S** | PermissionPresetSelector + ConfirmDialog |
| 4 | steer 直发 | S（mode 字段已在 wire，硬编码 queue；**接口抉择**：MessageApi.promptAsync 无 mode 参） | S（长按发送键） | **S-M** | 双键排队为主路径的定位不变 |
| 10 | 重试/turn-error/max-tokens | S-M（llm/retry→Part.Retry 已备；max-tokens wire 待取样） | S-M | **S-M** | 错误卡加 action 参数 + continue 入口 |
| 14-部分 | 未读完成分色 | — | S | **S** | 与下述状态点拆开做 |
| 7 | 消息反馈 👍/👎+备注 | S（3 typert + feedback/record 分支） | S-M | **S-M** | **禁长按**（SelectionContainer 冲突）→ 气泡下动作行（isTurnLast 条件现成） |
| 12 | 工具卡增补 | M（按工具名客户端分类，服务端无子类型字段——Web 同款做法） | S-M | **S-M** | 判定已降级为增补（§11.3） |
| 5 | 子代理续聊 | M（3 方法 + history 复用 fold 管线 + 发送分派） | **S（通道全就绪）** | **M（性价比最高）** | Stop 键切 interrupt |
| 9 | Plan 模式 | S（plan/mode 拆分支；plan-review=QuestionAsked 特化） | M | **M** | chip + 专卡 |
| 2 | 轨迹视图（台账+检查器） | S（history 管线全通，view 字段有意丢弃无需补） | M | **M** | 轮次卡展开形态（RenderableTurn 已预计算时间戳）；**时间轴缩放 L 不建议做** |
| 1 | @ 会话源+可点 mention | S（2 typert 方法） | M | **M** | 文件源现成；deliverables 上游 |
| 8 | deliverables 产出文件行 | S（数据源复用 1） | M | **M** | **依赖 #1** |
| 3 | 工作区/会话组织 | M（6 方法 + 4 帧 + Workspace/归档域建模=最大项） | S×3/M/L | **M（归档+行菜单+左滑先行）** | 多 workspace 真建模 L 可缓行 |
| 14-部分 | 等待审批/提问状态点 | — | M | **M（谨慎）** | 要动 SessionStateFSM（单一真相源铁律域） |

**总量结论：14 项候选无一项判死刑，必做项里 0 个 L**（仅有的两个 L——时间轴缩放、多 workspace 真建模——均可不做或简化）。全部做完 ≈ 20-25 人日。

**推荐三批路线**：
- **批 1 快速胜利（≈3 人日）**：goal.complete → 压缩事件接线 → Full access 确认 → steer 长按直发 → 重试/continue 入口。全部低风险、不动 ChatScreen 协议文件或只轻触。
- **批 2 主价值（≈8-10 人日）**：子代理续聊（通道就绪，先做）→ 消息反馈 → Plan 模式 → 轨迹台账+检查器 → @ 会话源+可点。
- **批 3 组织面（≈6-8 人日）**：工作区组织（归档先行）→ deliverables（依赖批 2 的 @）→ 工具卡增补 → 状态点/picker。

**横切风险三条**（双审计共识）：① 新 SseEvent 必须「DEM 分支 + EventDispatcher bind + handler 折叠」三步全走，漏 bind 即静默丢弃（goal/change 曾中招，EventDispatcher.kt:148-152）；② composer/消息流改动触碰 ChatScreen 协议文件 → 严格按编辑协议 Read→编辑→编译→commit 串行循环；③ 状态点项动 SessionStateFSM 前先读架构文档承重规则。

### 11.5 与 §9 候选清单的对账

§9 的 P1×6 / P2×8 全部通过可实现性检验，仅三处改写：#9-11 压缩项降为「事件接线」、#9-12 工具卡项降为「增补」、#9-1 @ 项改写为「会话源+可点（文件源已有）」。新增登记：**#298**（LAN 设置面 403，§11.2）；pluginInventory/list 自 🔒 升格为 P3 实现项（插件清单页）。§0-0a/0b 两 bug 卡随修复出清（验证归属原卡流程）。

---

## 12. 前端功能面量化对账（2026-09-02 第三次深调：双端特征点普查 × 交叉配对）

> 回答的问题：**「OC Beacon 的前端功能相比 DSH web 前端还差多少」**——以用户可见功能点为单位，非 §11 的协议方法面。两份普查：`fe-feature-census.md`（Web 169 点）× `app-feature-census.md`（Android 149 点），配对由主会话完成，两处独立核实（相对时间戳 grep 全 UI 无对位 → ❌；compact 通道读源码 → #297 已修，普查行已勘误）。

### 12.0 口径

- **功能点** = 用户能感知/操作的一个独立能力，双端同一粒度（@ 文件/@ 会话分开；轨迹=台账/检查器/缩放分开）。
- **有效分母** = Web 端「普通」点（远程浏览器真实可用）**144**——🔒21（loopback 钉死，远程 Web 同样 403，不构成 Android 缺失）与 ⏸4（部署/服务器面残废，Web 自身不可用）剔除。
- **判定**：✓ 完整对齐（形态允许差异）· ◐ 部分对齐（子集/缺档位）· ❌ 缺失 · N/A 平台机制差异（Web 壳层布局/键盘/PWA 类，Android 有原生对等物或无意义，不构成落后）。
- 源码-实测矛盾点按实测口径（Web 端 `+` 启动器实测不可见→Android 斜杠入口判 ✓；详情面板 Web 自身不可用→N/A）。

### 12.1 总账

| 量 | 数 | 说明 |
|---|---|---|
| Web 功能点全集 | **169** | 普通 144 · 🔒21 · ⏸4（分域：D5 渲染 38 > D8 设置 27 > D3 输入 19 ≈ D4 命令 18） |
| Android 功能点全集 | **149** | DSH对齐 36 · DSH部分 29 · ➕独有 56 · 本地 28 |
| **有效分母（Web 普通）** | **144** | |
| ✓ 完整对齐 | **49**（34%） | |
| ◐ 部分对齐 | **31**（22%） | |
| **❌ 缺失** | **47**（33%） | 可实现 **41** + 平台/架构不合 6 |
| N/A 平台差异 | **17**（12%） | D9 壳层 8 + D10 键盘 5 + 4 散点 |
| **覆盖率（✓+◐）** | **80/144 ≈ 56%** | 剔除 N/A 后 80/127 ≈ **63%** |
| Android 反向超集 | **84 点 Web 无** | ➕56 + 本地28（其中 ~12 点 OpenCode 后端限定，DSH 场景不可见） |

### 12.2 分域矩阵（✓ 为余集，仅列 ◐/❌/N-A）

| 域 | Web普通 | ✓ | ◐ | ❌ | N/A | ◐/❌ 点号 |
|---|---|---|---|---|---|---|
| D1 会话管理 | 14 | 4 | 4 | 5 | 1 | ◐04,05,07,15 · ❌06,10,11,13,14 · N-A03 |
| D2 工作区 | 7 | 1 | 2 | 4 | 0 | ◐02,08 · ❌01,04,06,07 |
| D3 Composer | 19 | 6 | 9 | 3 | 1 | ◐01,02,05,06,09,10,12,16,17 · ❌03,04,19 · N-A07 |
| D4 命令模式 | 18 | 9 | 1 | 8 | 0 | ◐02 · ❌03,06,07,09,10,12,15,16 |
| D5 内容渲染 | 34 | 13 | 6 | 14 | 1 | ◐02,04,05,06,09,10 · ❌03,08,12,19,20,24,25,26,28,29,30,31,32,33 · N-A38 |
| D6 子代理 | 9 | 3 | 3 | 3 | 0 | ◐03,07,09 · ❌01,05,08 |
| D7 审批问答 | 9 | 6 | 2 | 1 | 0 | ◐08,09 · ❌07 |
| D8 设置管理 | 9 | 1 | 1 | 6 | 1 | ◐01 · ❌17,23,24,25,26,27 · N-A14 |
| D9 全局壳 | 11 | 3 | 0 | 0 | 8 | N-A01-04,07-10 |
| D10 键盘效率 | 14 | 3 | 3 | 3 | 5 | ◐08,12,14 · ❌03,04,13 · N-A05,06,09,10,11 |

（校验：✓49+◐31+❌47+N/A17 = 144。域级最痛：**D5 渲染缺 14 点（轨迹 6 + 呈现细节 8）**、**D4 命令缺 8 点（Plan 2+反馈 2+技能 2+杂项 2）**、**D2+D1 组织面缺 9 点**。）

### 12.3 ❌47 的可实现性分解

**平台/架构不合 6**（不排实现）：Cordis 动态插件域 ×4（D8-24~27，Web 专用 runner）· D8-23 dashed 添加卡（本部署 authorable:false 纯只读）· D10-04 草稿撤销/重做（移动端价值低）。

**可实现 41**，按 §11 候选聚合：
- **§11-2 轨迹域 6**：D5-29~33 + D10-13（台账/检查器/折叠/Duration+搜索/双tab/缩放——缩放属不建议档，净 5+1 缓做）
- **§11-1 @ 会话链 3**：D3-04 会话源 · D6-08 运行中子会话源 · D3-03 `@"` 引号形态
- **§11-9 Plan 2**：D4-09 /plan · D4-10 Plan chip（plan-review 专卡已计 ◐ D7-09）
- **§11-7 反馈 2**：D4-06 /feedback · D4-07 消息级反馈
- **§11-3 组织面 7**：D2-01 添加工作区 · D2-06/07 重命名/删除 · D1-10 归档 · D1-13/14 排序/拖序（Android 形态=行菜单排序）· D2-04 隐藏项开关
- **§11-5 子代理续聊 1**：D6-05（UI 通道全就绪）
- **§11-10 呈现 3**：D5-12 max-tokens+continue · D5-08 注入折叠行 · D5-24 spill 提示
- **§11-12 工具卡增补 2**：D5-19 question 行 · D5-20 Code Dispatch 树（+D5-25 skill 行，受 attached 会话协议坑半锁）
- **§11-8 deliverables 2**：D5-26 文件 chip 行 · D5-28 内联提及可点
- **§11-4 steer 域 2**：D10-03 对调/批量插话（Android 形态=长按直发）· D3-19 composer 阻断态
- **§11-13 + P3 3**：D4-12 Full access 确认 · D4-03 命令带图限制 · D8-17 插件清单
- **零星小点 6**（§11 矩阵未单列）：D1-06 相对时间戳 · D1-11 消息级分支（session.fork atSeq API 已消费、缺轮尾锚点 UI）· D5-03 KaTeX · D4-15/16 技能源两点（协议坑）· ——合计 6 点全部 S 级

### 12.4 反向超集（Android 有、Web 无）

**84 点**（➕独有 56 + 本地 28），四大类：①**系统级集成**（通知/前台服务/深链/分享接收/电池引导/应用内更新）；②**会话组织与检索超集**（本地 FTS5 全文搜索+过滤+直达、标签、收藏、一键已读、历史同步）；③**审批超集**（always 档、自动批准规则、拒绝附理由）；④**OpenCode 后端限定**（~12 点：远程终端 PTY 全家、Git 面板、MCP、revert、分享、后台化、provider OAuth——DSH 场景能力位隐藏，条件可见）。另有 FileViewer+批注回传完整工作流（普查注记：若拆条 Android 总数 163）。

### 12.5 合并结论（功能面 × 协议面双视图）

- **功能面**：Web 有效 144 点，Android 覆盖 80（56%；剔平台差异 63%），缺 47——其中 41 可实现（工程量由 §11 背书：14 候选聚合 + 6 个 S 级零星点，**0 必做 L，全部做完 ≈20-25 人日**），6 平台/架构不合。
- **协议面**（§11.1）：78 方法消费 28（36%）；缺 50 = 可实现 18 + 特权 13 + 禁用 1 + Web 专用 12 + 双轨 6。
- 两视图交叉一致：功能缺口的大头（轨迹/@会话/Plan/反馈/组织/续聊）全部落在协议可达面；唯二的功能-协议交叉限制是 skill.list 冷会话协议坑（D4-15/16、D5-25）与 🔒21 特权面（D8 为主，双端对等不可达）。
- 判定勘误链完整收敛：§11.3 三项（@ 文件源/工具卡/压缩 UI 已在）+ 本轮 compact 通道（#297 已修）+ A-D8-26 可达性（#298）。





- `docs/research/dsh-gap-2026-09-01/fe-inventory.md`（前端 28 模块清点 + 隐藏功能专节 + 快捷键全集）
- `docs/research/dsh-gap-2026-09-01/app-inventory.md`（Android RPC/事件/UI 清点 + 16 项新发现）
- `docs/research/dsh-gap-2026-09-01/web-walkthrough.md`（第一轮实测走查 16 节）+ `shots/01-12*.png`（12 张交互截图）
- `docs/research/dsh-gap-2026-09-01/web-walkthrough2.md`（第二轮补章走查）+ `shots2-real/13-17*.png` + `shots2-fixture/18-26*.png`（13 张）
- `docs/research/dsh-gap-2026-09-01/web-walkthrough3.md`（第三轮补查）+ `shots2-fixture/27*.png`
- `docs/research/dsh-gap-2026-09-01/web-walkthrough4.md`（第四轮补查：子代理下钻/goal/plan/加载更早/onboarding 卡死）+ `shots3-fixture/29-32*.png` + `shots3-real/33-36*.png`
- `docs/research/dsh-gap-2026-09-01/web-walkthrough5.md`（第五轮冲刺：lightbox/QueueDock/分支/重命名/仲裁 25 子代理）+ `shots3-fixture/37-41*.png`
- `docs/research/dsh-gap-2026-09-01/api-gap.md`（服务端 RPC 全集 × 双端对比；52/56 路由数勘误见主文档 §11.1）
- `docs/research/dsh-gap-2026-09-01/implementability-data.md`（2026-09-02 数据/传输层可实现性审计：14 项四问 + 汇总表，基线 52dd8752）
- `docs/research/dsh-gap-2026-09-01/implementability-ui.md`（2026-09-02 UI 层可实现性审计：14 项挂点/可复用/工程量/UX 形态 + 横切风险）
- `docs/research/dsh-gap-2026-09-01/fe-feature-census.md`（2026-09-02 Web 前端功能点全量普查：169 点 = 普通 144 · 🔒21 · ⏸4，D1-D10 分域）
- `docs/research/dsh-gap-2026-09-01/app-feature-census.md`（2026-09-02 Android 功能点全量普查：149 点 = 对齐 36 · 部分 29 · ➕56 · 本地 28，与 Web 普查 ID 对位）
- 走查环境注记：IAB guest 限制（合成 click 超时→evaluate 直调；CUA 事件落 composer 会 about:blank→fill 路径；大会话渲染崩溃 guest→换小会话），详见 web-walkthrough §16；**subagent 无浏览器工具**（Browser Use 仅主 agent），详见 web-walkthrough2 首注
