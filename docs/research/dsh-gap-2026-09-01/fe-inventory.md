# DSH Web 前端（dashboard）用户可见功能穷尽式清点

> 生成日期：2026-09-01 · DSH 版本 0.1.1-rc.2
> 证据基线：`/home/linuxbrew/.linuxbrew/lib/node_modules/@deepseek-ai/dsh/node_modules/@deepseek-ai/` 下各包 `README.md`（随包发布的权威设计文档）+ `lib/client.js`（未混淆构建产物，直接取证）
> 用途：与 Android 客户端 OC Beacon 做差距分析的「web 端有什么」基准清单

---

## 0. 架构速览（理解功能挂载点）

- 页面外壳：`dsh-web-frontend/dist/index.html`（vite 构建），PWA manifest（`display: fullscreen`，名 "DeepSeek Harness"/"DSH"，可安装）。外壳通过 `window.__ModuleLoader__` + `window.__DSH_BOOT__` 从 `/plugins/@deepseek-ai/<模块>/client.js` 逐个加载插件包，全部激活后由 `ui-renderer` 挂 React root（`dsh-web-frontend/dist/assets/index-ClqxG24t.js` 内 `web boot: window.__ModuleLoader__ bootstrap facade`）。
- 三栏布局（ui-layout）：侧栏（可折叠成 56px rail）+ 会话列 + 详情列（可拖宽、拥挤时让位并自动关闭）。几何为会话级瞬态，刷新还原。
- 一切功能 = slot（插槽）注入。核心 slot 家族：`sidebar.*`、`conversation.*`（hero/header/composer/input.dock/input.overlay/chat.node/chat.turnTail/view/…）、`settings.*`、`tool.call.toolview`、`shell.overlay`。
- 传输（client-connection）：单播 RPC = HTTP POST `/api`；双下行 WebSocket `/api/events.mux` + `/api/events.host`；问题/审批应答走 mux respond 通道（`PendingWait.respond` → `api.respond`，dsh-client-runtime/lib/client.js:6988、7476-7486）。loopback（127.0.0.1）与否决定一整批「特权功能」可见性（设置行、配置文件打开、agent-preset 授权面、凭据写入等）。
- 浏览器 Remote 服务（api-remotes client.js 内 `service:` 声明）：`commands`、`dynamicCordisRunner`、`fileReferences`、`goals`、`messageFeedback`、`pluginInventory`、`sessionReferenceResolver`。
- 宿主 RPC 全集（dsh-client-connection/lib/client.js 方法表 + 分发 switch）：`session.{list,create,history,prompt,cancel,fork,rename,search,models,selectModel,updateQueue,attachment}`、`subagent.{list,history,prompt,interrupt}`、`workspace.{list,create,rename,delete,insertBefore,insertSessionBefore,archiveSession}`、`host.{describe,openPath,pickDirectory,listDirectory,createDirectory}`、`settings.{describe,openDocument,update,replace,mutate}`、`credentials.{describe,set,unset}`、`llm.{providers,models,discoverModels}`、`skill.list`、`agentPreset.{list,read,copy,remove,openDocument,select}`、`goal.{create,edit,pause,resume,complete,clear}`（connection client.js:10040-10059 一带）。loopback 钉死集：`host.pickDirectory/openPath`、全部 settings/credentials、`agentPreset.read/copy/openDocument/remove`。

---

## 1. 总览表

| 模块（boot 名） | 用户可见功能（一句话） | 主要交互 | 关键 RPC/事件 |
|---|---|---|---|
| client-layout | 三栏框架：侧栏+会话+详情，拖宽/折叠/自动收起 | 拖拽手柄、折叠钮 | 无（本地几何） |
| client-locale | 中英文切换（Settings→General→Language 行） | 下拉选 zh/en | `settings.*`（`locale.preference`） |
| client-ui-theme | 浅色/深色/跟随系统主题 | 设置行三选 | `settings.mutate`（`ui-theme.preference`） |
| client-ui-sidebar | 侧栏壳：品牌行、新会话钮、折叠控制、底部 Settings | 点击/150ms 折叠动画 | 本地 |
| client-ui-workspace | 工作区/会话浏览器：分组/平铺、手动/最近排序、拖拽排序、会话搜索（标题+内容）、行菜单（重命名/分叉/归档）、工作区增删改名、悬停卡片复制 | 拖拽、菜单、搜索框、hover | `workspace.*`、`session.search`、`session.fork/rename` |
| client-ui-conversation | 会话主体：聊天气泡流、composer（输入机）、队列 dock、todo 条、goal 条槽、审批面板、统计条、上下文环、图片附件、压缩检查点、重试行、turn-tail 交付物孔、分支/复制 | Enter/Shift+Enter/Cmd+Enter、Cmd+Z/Y、粘贴/拖入图片、点击文件路径 | `session.prompt/cancel/fork/updateQueue/attachment`、mux respond |
| client-ui-input-trigger | `/` 和 `@` 触发管线 + 候选菜单（分组、键盘仲裁、fuzzy） | 键入触发、↑↓ 导航、Esc 关闭、mousedown 选中 | （由源消费） |
| client-ui-commands | `/` 命令源：命令目录缓存、三路分发（execute/popupSelect/leadingInput）、popupSelect 壳 | `/`+空格补全、Enter 执行 | `command.list`、`command.execute` |
| client-ui-skill | `/` 技能源 + `skill` 工具行（展开看指令） | 菜单选择 → 落字面 `/name ` | `skill.list` |
| client-ui-reference | `@` 统一引用源：@文件/@目录/@会话 | `@` 触发、`@"带空格"`、目录可继续下钻 | `fileReferences.list`、`sessionReferenceResolver.candidates` |
| client-ui-subagent | 子代理谱系导航（面包屑+目录树）、只读/续聊 composer、`@` 运行中子会话源 | hover 150ms 开目录、方向键/Home/End/Esc、点祖先上行 | `subagent.list/history`（经 sessions hook）、`subagent.prompt/interrupt` |
| client-ui-model-selection | composer 模型座 + `/model` 弹选：两级菜单（模型→推理力度） | 点击开菜单、选模型/effort | `session.models`、`session.selectModel` |
| client-ui-plan | Plan 模式状态 chip（黄字 Plan ×，点按执行 /plan off） | 点击 | `command.execute`（`/plan off`） |
| client-ui-permission-presets | 权限预设：Settings General 行（新会话默认）+ `/permission` 裸调弹出（当前会话）+ composer 权限 chip | 下拉选择；Full access 需勾选风险确认 | `settings.mutate`、`command.execute`（`/permission <preset>`） |
| client-ui-goal | GoalBar：编辑/暂停/恢复/清除当前目标 | 图标按钮、内联错误 | `goals.{edit,pause,resume,clear}`（创建走 `/goal` 命令） |
| client-ui-jobs | 会话头「后台任务」入口 + popover（运行中/已结束、秒表） | 点击开 popover、Esc/外点关闭 | 纯投影（`session/jobs` 帧），无 RPC |
| client-ui-tool | 工具调用呈现：terminal/read/diff/search/web/todo/question/code-dispatch 卡片 + 详情面板 slot | 行点击展开/收起、路径点击打开文件 | `host.openPath`（打开文件） |
| client-ui-skill(tool row) | skill 工具行 | 整行展开 | 无 |
| client-ui-deliverables | 轮次尾部「产出文件」行 + 结尾散文内联代码文件提及可点 | chip 点击打开文件、Show in folder | `host.openPath` |
| client-ui-workflow-run | 工作流运行节点：阶段/成员可折叠树、运行成员跳转子会话 | Enter/Space/整行切换、点下划线成员 | `sessions.open`（本地） |
| client-ui-trajectory | 「轨迹」视图 tab：事件台账+检查器（token/时长/输入输出/时序）、总览时间轴（滚轮缩放、右键拖平移、拖选区间） | 点击行选检查、滚轮缩放、右键交互、搜索 | 纯投影（共享事件窗） |
| client-ui-message-feedback | 消息级 👍/👎 + 备注（portaled 弹层），再点撤销 | 点击、写备注保存 | `messageFeedback.{list,put,delete}` |
| client-ui-user-questions | 问题 composer 接管：单选/多选/自定义答案、跳过；plan-review 专用卡（Chat about it/Refuse/Approve） | 点选、Enter 提交、Shift+Enter 换行 | mux respond（`question`） |
| client-ui-cordis | Cordis 动态插件面板（侧栏底部入口+徽标）：全库定义列表、approve/decline 运行请求、run/stop/load | 徽标点击、行内按钮 | `dynamicCordisRunner.{inventory,stopFromPanel,undefineFromPanel}`、`resolveRequestRun/settleUserRun` |
| client-ui-attachment | 草稿图片轨（64px 缩略图、横向滚动、箭头翻页）、历史图片画廊、原图 lightbox、全屏拖放遮罩 | 点击缩略图开原图、hover 出删除、拖文件入窗 | （数据由 conversation 提供） |
| client-ui-directory-picker-native | 目录选择：宿主 OS 原生选择器（renderless） | 触发 add workspace 流 | `host.pickDirectory` |
| client-ui-directory-picker-browse | 目录选择：应用内 Miller 双列浏览器（680×500、新建文件夹、显示隐藏项开关、路径可编辑） | 面包屑、路径输入、New folder、Open | `host.listDirectory`、`host.createDirectory` |
| client-ui-agent-preset | 4 个面：General 默认预设行、新会话页预设 chip、会话头只读标签、Settings「Agent Presets」管理页（复制建/删/设默认/查看器/打开目录） | 复制对话框（id+名称）、卡片操作 | `agentPreset.{list,read,copy,remove,openDocument}`、`settings.mutate`（default） |
| client-ui-settings(-general) | Settings 面板壳 + General 页（语言/主题/权限/回车行为/预设行）+「打开配置文件」（loopback）+ onboarding 编排 | 侧栏底部触发、导航、行交互 | `settings.*`、`settings.openDocument` |
| client-ui-settings-models | Models 设置页：provider 行/编辑卡（API key 写入、baseURL、模型表、上下文窗/输出上限）、拉取可用模型、添加自定义 provider、删除行；两个首跑引导（内测须知 + DeepSeek key） | 表单、Fetch available models、Select all、确认删除 | `llm.{providers,discoverModels}`、`settings.mutate`、`credentials.set/unset/describe` |
| client-ui-settings-plugins | Plugins 设置页：「Plugin configuration」tab（bash/agent-loop/web-search-deepseek 卡片：暂存/保存/丢弃/重置） | 展开卡片、Save/Discard/Reset | `settings.*`（各命名空间） |
| client-ui-settings-plugin-inventory | Plugins 页「Plugin list」tab：只读全插件清单（搜索、展开看 entry id/配置/Cordis 状态） | 搜索、展开 | `pluginInventory.list` |
| client-ui-brand-official | 官方品牌标（仅 official 构建档） | — | — |
| client-ui-renderer | React 挂载器（无直接 UI） | — | — |
| client-runtime / client-connection / client-modules / client-hmr / api-remotes / api-gateway / typert-registry / cordis-client-runner / session-log-export | 无直接用户 UI 的基础设施（见 §3 隐藏面） | — | — |
| dsh-turn-notify / dsh-keepalive | 用户本地自有插件（非 DSH 发行），未深入 | — | — |

---

## 2. 每模块详情（含证据）

### 2.1 client-ui-layout — 三栏外壳
- **可见面**：整页三栏。侧栏拖拽边界为隐形命中条；详情列边界为浮动 pill。拥挤时仅详情列收缩并自动关闭。侧栏关闭保留 56px rail；详情关闭为 0 宽。会话列常驻；切不同会话时详情列先关再绘。
- **交互**：拖宽（侧栏/详情）、折叠/展开；`prefers-reduced-motion` 禁用过渡。主题 presenter 把主题令牌写到 `<html>`/`<body>` 与 `<meta name="theme-color">`。
- **限制**：几何不落 localStorage，刷新重置（README「Panel geometry is transient」）。
- 证据：`dsh-client-ui-layout/README.md` 全文；包内 `AppFrame`。

### 2.2 client-ui-sidebar — 侧栏壳
- **可见面**：品牌行（mark+name，无占用者时为 fish mark + "DSH Local Build" + 7 位 commit hash 徽标——`DSH_CLIENT_COMMIT_HASH` 构建注入）；「新会话」按钮；工作区/会话区（`sidebar.workspaces`，ui-workspace 提供）；底部 Settings 座（`sidebar.settings`）。
- **交互**：折叠动画（150ms 淡出 + 49px 左移进 rail，列 300ms 滑动；reduced-motion 关闭）；滚动条指针离开 2s 后隐藏（scrollbar 重绑 transparent）。
- **新会话语义**：目标工作区 = 显式作用域 > 当前会话所属 > 最近活跃；无工作区则进空白新会话页。
- 证据：`dsh-client-ui-sidebar/README.md`；zh 字典 `"session.new": "新会话"`。

### 2.3 client-ui-workspace — 工作区/会话浏览器 + 搜索（功能最重的侧栏模块）
- **可见面**：侧栏主区（分组「按工作区」或「单列表」两种浏览模式）；新会话页 hero 的 Workspace 选择器（`conversation.hero.workspace`）；每工作区默认展开 5 条会话，「展开其余 N 个会话/收起」。
- **视图选项**：分组方式（按工作区/单列表）×排序方式（手动排序/最近更新）；拖拽改序（真实工作区下 Manual 拖序持久化到宿主；Ungrouped/平铺仅本地）。
- **搜索**：折叠态是头部图标；展开后输入；即时标题/工作区子串匹配 + 250ms 防抖的宿主内容搜索（ranked 片段，上限 20 条，`search.hasMore` 提示收窄；失败降级仅名称匹配 + 警告）。选中结果打开会话。查询去 NUL、500 UTF-16 上限。
- **行状态**：`进行中`（蓝点）/`等待审批`/`计划待审`/`等待回答`（琥珀点，优先级最高）/子代理运行中 `N 个子代理运行中`/`已完成`（未读完成绿点提醒）；悬停卡：创建时间、复制路径（工作区卡）/复制标题（会话卡）、`已复制` 确认。
- **行菜单（会话）**：重命名（宿主规范化，可拒 `title-invalid`；确认未变标题=固定当前自动标题）、分叉会话（最后完成轮次，标题 `(N)` 自增，开子会话）、归档会话（无确认，非破坏，从所有分组表面消失；**无取消归档面**）。空白新会话行是占位（无菜单无时间）。
- **工作区操作**：添加工作区…（经 directoryFlow 孔）、重命名、删除（确认框说明保留边界；会话落到「未分组」）。
- **隐藏**：subagent 来源会话行不显示（从父会话头进入）；普通 fork 保留。
- **RPC**：`workspace.{list,create,rename,delete,insertBefore,insertSessionBefore,archiveSession}`、`session.search`、`session.fork/rename`、`host.listDirectory/createDirectory`（browse 流）。
- 证据：README 全文；zh 字典（`group.ungrouped`、`viewOptions.*`、`orderBy.*`、`search.*`、`menu.fork`、`menu.archiveSession`、`delete.desc` 等）；拖拽逻辑 client.js:1283 一带。

### 2.4 client-ui-conversation — 会话主体（最大模块，447KB client.js，619 个文案 key）
分域列出：
- **无会话 hero**：虚线 composer 卡整体即工作区选择器触发（textarea 只读、Enter/Space 可开）；品牌 mark。
- **会话头**：标题（可折叠工作区?否——标题+可选 lineage 控件+视图 tab）；`conversation.session.header.lineage`（子代理面包屑，ui-subagent）、`...header.actions`（后台任务，ui-jobs）、`...header.utilities`（当前无注册者——保留座）。
- **视图 tab**（`conversation.view` 环）：Chat（自带）+ Trajectory（ui-trajectory）。
- **聊气流**：
  - 用户气泡（时钟+复制；**不可编辑**，无分支）；steering 气泡同款呈现于轮中。
  - 助手气泡：markdown（GFM、KaTeX 数学、Shiki 代码高亮——dist assets/fonts/KaTeX_*、assets/langs/{c,cpp,csharp,css,go,html,ini,java,kotlin,less,lua,markdown,mdx,php,python,ruby,rust,scss,sql,swift,toml,xml,yaml}.js）；IconActions 行（复制/时钟/分支——分支仅「已完成轮次的最后一条消息」可用，超时提示 `仅可从已完成轮次的最后一条消息分支`）；hover 显 `TTFT Xs · Y tok/s`。
  - Think 行：默认折叠，流式时摘要行跟随最后非空行滚动；展开后整段推理进正文流。
  - 上下文注入/跨会话召回：默认折叠 disclosure，头标 `上下文注入`/`跨会话召回`+生产者名；body 高度上限 141px 内滚。
  - 压缩检查点行：一条折叠行（`已压缩 N 条历史记录（约 X tokens）`），hover/聚焦展开披露，点击看摘要；手动 `/compact` 运行行折叠进同一 key。
  - 重试行：静音状态行 + 客户端锚定的倒计时（1s 下限）、文本 shimmer（进行中）；激活显最新延迟与失败信息；always 策略显 `∞`。终态失败在轮边界内联展示（AUTH 拷贝固定为 `API key is invalid`）；`max-tokens` 轮显专门警告节点 + 「发送 continue 可续」提示。
  - 工具行（ui-tool 呈现）、turn-tail 产出文件行（ui-deliverables）。
- **composer（InputHub 输入机）**：
  - 文本域 + 对齐 backdrop（引用 chip 彩色渲染、原生度量管宽窄换行选区）。
  - 键盘：Enter=排队发送（默认）或插话（busyEnter 设置）；Shift+Enter 换行；Cmd/Ctrl+Enter=另一行为；**空草稿 Cmd/Ctrl+Enter=把全部排队消息依次插话**（placeholder 会宣传 `Cmd/Ctrl+Enter 插话发送全部排队消息`）；Cmd/Ctrl+Z 撤销 / Cmd/Ctrl+Y(或 Shift+Z) 重做草稿；↑/↓ 菜单导航；Esc 关弹层；Space 命令裁决；Backspace/Delete 整体删除引用 occurrence；IME 合成期全部让路（keyCode 229）。证据 client.js:3716-3795。
  - 复制/剪切带引用序列化（clipboard 投影）；粘贴文件=图片 intake（受 `imageLimits` 投影预检：数量/单张/总量，超限 toast）。
  - `+` 号按钮 = 命令启动器（打开 `/` 触发 command 源，非附件钮）。
  - composer 座位：`conversation.input.model`（模型选择，最靠右前）、`conversation.input.plan`（plan chip，权限 chip 右侧）、Access 座=权限 chip（`/permission`）、pending 指示点、发送/停止圆钮。
  - 阻塞机制 `ctx.conversation.blocks`：无 adapter 路由时整个输入惰化（模型座保持可用）。
- **输入 dock 栈**（`conversation.input.dock`，自上而下）：TodoDock（order 0，todo 条：标题+`1 completed · 2 in progress · 1 pending` 计数头，折叠）→ GoalDock（order 10）→ QueueDock（order 20）。
  - QueueDock：1 行直显；≥2 行折叠 `N 条排队消息`；行内 编辑/删除/插话发送（strict steer，原子转移进当前 next-step 窗口；输了不报错）；编辑仅限纯文本行（Enter 保存 Esc 取消）；addressed 子代理只读。
- **审批面板**（composer 接管）：琥珀条 + 正当性标题 + 命令行对 + `拒绝`/`允许一次`（无持久授权）。
- **统计条（stats dock，随 composer 粘滞）**：轮/步计数、LLM/工具墙钟、token 计费（cache 命中率精度自适应——只在真 100% 才显 100%）、`TTFT avg · tok/s` 组；溢出省略号+hover 全文。
- **ContextMeter**：composer 尾部 14px 占用环（`contextPressure`/`contextBreakdown` 投影），点击开面板：`percent used` 头 + `~used / capacity` + 分色条 + 启发式构成行（system prompt/tools/messages，`~` 前缀）。
- **详情列**：`conversation.details.tool`（ui-tool 填）——**README 明示当前无入口**（`ChatViewInjected.openDetails` 实现未接线）。
- **RPC**：`session.prompt`（普通/steer，附带浏览器时区采样）、`session.cancel`（停止）、`session.fork`（消息分支）、`session.updateQueue`、`session.attachment`（图片提交）、mux respond（审批）。
- 证据：README 全文（本节几乎逐句有源）；zh 字典 `approval.*`、`queue.*`、`settings.enter.queue/steer`、`message.compaction.*`、`placeholder.steerQueue` 等。

### 2.5 client-ui-input-trigger — `/`、`@` 触发管线
- 菜单列表挂 `conversation.input.overlay`；分组排序（order）+ 分组标题行（可关）；列表高度贴 composer 上方空间；composer 卡外 pointerdown 关闭；combobox 模式（焦点留 textarea，`aria-activedescendant`）。**无自持 RPC**。

### 2.6 client-ui-commands — 斜杠命令
- `/` 触发 `command` 源：目录来自 `command.list({sessionId})`（per-session 缓存，scope 出生预热；`commands/change`、`agent-preset/selected`、`connection/reset` 失效）。
- 三路分发：`execute`（普通）、`popupSelect`（业务注册，如 `/model`）、`leadingInput`（宿主声明 `input`，如带参命令）。
- 菜单 fuzzy：有序大小写不敏感子序列；前缀优先、分隔边界/相邻字符/短间隙加分（只影响发现，Space/Enter 仍需精确名）。
- 图片信封：仅声明 `input.images` 的命令可带图执行；否则整单拒绝 toast（草稿+图保留）。
- 本地事件 `command/executed` 广播。
- **RPC**：`command.list`、`command.execute`。

### 2.7 client-ui-skill — 技能
- `/` 源 `skill`：候选 `skill.list({sessionId})`，`startsWith` 过滤；选中落字面 `/name `；`modelInvocable: false` 项带「仅用户可调」前缀标记。宿主 pre-step 对消息内任意空白边界 `/name` 同样注入（菜单/手打等价）。
- skill 工具行：折叠=图标+Skill+名称；展开=Instructions 卡（durable 输出）+ 检查器入口。

### 2.8 client-ui-reference — @ 引用
- `@` 源：文件/目录/会话 三段（文件区在前、会话区在后，区头不可选）。`@"…` 开引号仅搜文件。
- 文件选中→原子内联引用（文件 glyph+业务色名）；目录→普通可编辑路径文本（folder glyph），尾斜杠保持菜单继续下钻；带空格路径 `@"path with spaces"`。
- 会话选中→`@[label](dsh-session:…)` 规范 mention（隐藏 ref，发送经 `session.prompt` 携带；宿主 `agent/pre-step` 捕获上下文）。
- **RPC**：`fileReferences.list`、`sessionReferenceResolver.candidates`。

### 2.9 client-ui-subagent — 子代理
- 头部 lineage：当前面包屑（双尖号+标题）+后代计数下拉；hover 150ms 或 ↓ 开直系目录；点击祖先直接上行；目录支持兄弟切换（选中行加粗）；树内 ←→ 展开/收起、↑↓/Home/End/Esc 导航；每行=模式（continuable/one-shot）+`running/inactive`+标题+token 四桶合计+活跃轮时长（<1 天精确到秒，hover 保精确值）。
- composer 链：one-shot 子会话=只读执行记录；continuable 且父不可用=只读+恢复路径文案；continuable 父活着=普通 chrome（输入+FIFO 发送，Stop 独立走 `subagent.interrupt`）。
- `@` 源：仅零 RPC 的运行中子会话（`sessions.list`），选中插字面 `@label `。
- 导航：`SessionRuntime.openSubagent({parentSessionId, childSessionId, mode})`。
- 会话行不出侧栏（见 2.3）。

### 2.10 client-ui-model-selection — 模型选择
- composer 模型座（命名座 `conversation.input.model`）+ `/model` popupSelect：同一 per-session `ModelDirectory`。两级菜单：provider 分组模型 → 该模型 adapter 广告的 effort 档（`/model` 落默认 effort，座可再改）。
- 无可路由 adapter：composer 阻塞（`routable` 才阻塞，null 不阻塞）；触发器兜底 `Select model`。
- 事件刷新：`llm/adapters-updated`、`settings/document-updated`、重连。
- **RPC**：`session.models`、`session.selectModel`。

### 2.11 client-ui-plan — Plan 模式 chip
- 有效目标为 plan 时渲染黄字 `Plan ×` 状态钮（描述「Plan mode on, press to turn off」），点击=`command.execute('/plan off')`；placeholder 切换为 plan-task 提示。入口=`+` 命令菜单或手打 `/plan`。失败内联报错，投影确认才消 chip。

### 2.12 client-ui-permission-presets — 权限预设
- 三个面共享 `permissions` 投影：composer Access chip（kebab→Title Case；`danger-full-access` 显示 `Full access` 且先弹风险确认 Modal，勾选才激活允许）、`/permission` 裸调弹出选择器（decoration）、Settings General 行（未来会话默认；选项来自宿主动态枚举）。
- **RPC**：`settings.mutate`（descriptor revision 围栏）、`command.execute('/permission <preset>')`。

### 2.13 client-ui-goal — GoalBar
- `conversation.input.dock` 第 2 卡：活动目标条。动作=编辑/暂停/恢复/清除（CAS ref 读自投影；失败内联；clear 后立即抑制该 id）。创建在 `/goal` 命令；`/goal` 的 command 输入投影为右对齐等宽 user 风气泡「命令输入」。加载中/无/已完成/已清除=不渲染。

### 2.14 client-ui-jobs — 后台任务
- 会话头 actions 座：徽标=running+stopping 计数（0 隐藏）；popover 平铺列表（活跃按 startedAt 升序在前，已结束按 finishedAt 降序），行=生产者类型/标签/状态/详情/时长（活跃每秒走，>1h 用小时）；Esc/外点关闭并还焦点。**只读**（无取消/输出面）。数据纯 `session/jobs` 帧。

### 2.15 client-ui-tool — 工具呈现
- Chat 内每个 `tool-call` 节点 → `conversation.chat.node` 派发 → 键控 `tool.call.toolview`。内置：shell/pwsh（terminal 卡）、read、write/edit（diff 卡）、grep/glob（search 卡）、web（结果卡）、todo、question、Code Dispatch（递归子调用树）；generic 兜底（按工具名分类 search/read/shell/write/edit/code/generic）。生命态=running（shimmer）/success/failed/interrupted（警告态）。
- 路径点击→`openFile`→`host.openPath`（相对路径按会话 cwd 解析）；拒绝弹页内对话框（原因+重试/取消/Esc/遮罩）。
- `conversation.details.tool`（详情面，当前无入口）。
- 大结果：terminal 卡 spill 提示（`Full grep result stored at: …` 类文案模式见 fixture 样本）。

### 2.16 client-ui-deliverables — 产出文件
- 完成轮尾部（`conversation.chat.turnTail`）：安静标签 + 文件 lane（≤6 chip 实测宽度截断 + `+ N files`）；chip 点击 `openFile`；隐藏时次行 `Show in folder`（仅 loopback 且 `canOpenPath`）。
- 结尾散文内联代码提及：精确路径或唯一 basename 命中→link 蓝样式可点开文件（歧义 basename 不猜）。
- 宿主半注册系统提示段（`ui:deliverable-file-references`，order 190）要求模型在总结中用内联代码点名产出文件。

### 2.17 client-ui-workflow-run — 工作流运行节点
- 独立 Chat 节点：32px 运行行（chevron+状态点+状态文本）→ 阶段行（32px，标题+成员数+聚合状态尾）→ 成员行（16px 点+名称+64px 状态列）。
- 折叠控制：整行/Enter/Space；挂载时异常态展开、完成态折叠；新运行成员会把已完成阶段下的外层重新打开。
- 运行中成员（五条件全符：running+在普通列表+origin subagent+parentId=当前+行仍 running）下划线可点→`sessions.open(childId)`。

### 2.18 client-ui-trajectory — 轨迹视图
- 会话视图 tab 之一（`view.trajectory`）。事件台账：User/Assistant/Tool/嵌套 Subtool 记录行；粗规则=Turn 边界、行内标记=Step；主台账只显 index/event/content，选中→本地检查器（token 用量、时长、Input、Output、Timing）。
- 总览时间轴（Overview）：左→右真实计时投影；**滚轮缩放**、**右键拖平移**、**右键单击清除选区**、**左键拖区间=聚焦该区间活跃记录**；500ms hover 显精确时钟/时长；Assistant span 分 TTFT/decoding。
- 工具栏（zh 键）：Duration/Use actual duration/Use equal-width operations/实际时间、Turns 展开/收起、Calls 展开/收起、搜索轨迹。请求合计覆盖已加载窗口。
- 长账虚拟滚动：初始在尾部；到顶自动/点击加载更早页（`Between turns` 段容纳独立压缩请求）；向上滚动暂停跟随。
- Summary 区滚动条 hover/聚焦才显。Composer 悬浮于全高台账之上。

### 2.19 client-ui-message-feedback — 消息反馈
- 完成轮最后一条助手消息 IconActions 行的 `feedback` 条目（复制与分支之间）：👍/👎 + 备注。备注编辑器=portaled dialog（锚在触发器下）；失败内联（备注失败弹层保留草稿）；再点已记录评分=撤回；换边保留备注；per-Session 单次 `messageFeedback.list` 懒加载（首次 hover/聚焦才拉）。
- CAS：每次 put/delete 带 version，`version-conflict` 回执自带权威项就地和解。轨迹/瀑布视图无此控件。

### 2.20 client-ui-user-questions — 问答 composer
- 一次一题+进度导航；单选（点选即前进）/多选（草稿保留，可与自定义答案并存）/推荐徽章（label 后缀）/自定义答案 textarea（高度镜随长到 6 行封顶内滚）；Enter 继续/提交、Shift+Enter 换行、IME 合成期 Enter 只确认候选；「跳过此题」保留其他草稿发空 `{selected:[]}`；关闭=整单拒收 `ASK_CANCELLED`。
- **plan-review 意图卡**：`Plan review` 条+计划 markdown 滚动体+决策行 `Chat about it`（拒收回 composer）/`Refuse`/`Approve`（label 由意图点名，不依赖选项顺序）。
- 草稿不持久（重挂载丢本地选项/文本；宿主请求权威）。一次一个请求占 composer。

### 2.21 client-ui-cordis — 动态插件面板
- 侧栏底部 `sidebar.footer.action`（id `cordis-panel`）入口 + 徽标（运行中+待答计数）→ 全局面板：宿主 inventory 全量定义行（当前会话组在前，其余列后；阻塞模型的行组内置顶）。
- 行动作：approve/decline 运行请求（任何 tab 可答，先答赢）、Run（idle，携带 hasClientHalf）、本页 Load（重载恢复）、全局 Stop（`stopFromPanel`）、Undefine（`undefineFromPanel`）。行内联显示本页 load/render 失败原因。
- 聊天内 `cordis_define` 只读卡（名称/用途/来源/运行态，`cordis_undefine` 终态优先）。
- 打开时重读 inventory；无公告的注册变化需关开面板刷新。

### 2.22 client-ui-attachment — 图片附件呈现
- 草稿轨：64px 缩略图横排行（滚动条隐藏，边角圆形箭头按视口翻页，200px 下限保留 1 卡上下文）；竖向滚轮转横滚（≤60px/拍，对角保横意图）；新加滚到末尾；hover/聚焦显右上删除（触屏常显）；单击缩略图开原图。
- 历史图：单图 240px 长边（比例钳 [0.25,4]，cover 裁剪）、多图 64px 方块；失败显重试；单击开 lightbox（Esc/遮罩/关闭钮；无缩放无下载；焦点还原但不陷阱）。
- 拖放遮罩：全窗邀请层（禁用态换阻断插画）。
- 仅图片（文件卡/上传进度未做）。

### 2.23 目录选择器（-native / -browse 互斥组合）
- native：renderless，`host.pickDirectory` 驱动宿主 OS 对话框；无中途取消。
- browse：680×500 Miller 列（窄/矮屏钳制）；头=标题+路径面包屑+可点击编辑路径区；选行后双列（本级/子级）；导航静默锚定；**New folder** 嵌套创建并选中新目录；**Open** 采纳（回退当前列级）；隐藏项宿主始终列出、客户端过滤，footer 开关揭示；错误留对话框内 alert。无搜索/多选/改名/删除。

### 2.24 client-ui-agent-preset — 代理预设（4 面）
- General 行（新会话组合默认，`agent-presets` 命名空间 default 字段）；新会话页 chip（与工作区选择器并排，staged 一次性）；会话头只读标签；Settings「Agent Presets」页（order 20，Models 后）。
- 管理页：卡片 roster（user 行标 `user`，broken 行红框+加载失败徽标+禁用）；复制对话框=唯一创建路径（收 id[a-z0-9][a-z0-9-]* 与可选显示名，`{from,id,name?}` 上行）；复制完成打开新目录（`agentPreset.openDocument`，无桌面 opener 时目录以文本显示在行上）；删除=删目录（会话不受影响）；shipped 预设（standard/code/minimal/cordis）只读查看器；dashed 添加卡（roster 含 `cordis` 时）→staged 并开新会话（Creator 模式起草）；`authorable: false` 部署=纯只读浏览器。
- broken 预设在两个选择器中整体隐藏。
- 事件：`agent-preset/selected`、`settings/changed`、`connection/reset` 重读。

### 2.25 Settings 体系（ui-settings + ui-settings-general + 三内容页）
- 面板：侧栏底部触发 → 模态 Settings 面板；导航投影 `settings.section` 台账；chrome（trigger/header/close/action 座）+ General 页（`settings.general.item` 行：语言 order 0、主题、权限、composer-enter order 20、agent-preset 默认）+「打开配置文件」（仅 loopback 且 `hasDocument`；`settings.openDocument` 宿主用原生编辑器打开/物化 settings.yaml）。
- **Models 页**（order 10）：provider 行（绿点=已配置引用凭据，红点=具名引用缺失）；编辑卡（API key 单输入、写走 `credentials.set`（write-only，派生 `<ROUTE>_API_KEY`，settings.yaml 永不落值）、自定义设置折层：baseURL/模型表(id/name/contextWindow/maxTokens，K/M 后缀)/显示名/API 协议）；Fetch available models（`llm.discoverModels`，按当前表单（含未保存 baseURL/key）询问端点；选择器 Select all/Deselect all/Add selected，已配置项默认不勾）；Add a custom provider 卡（唯一 Provider ID 小写字母开头/端点/协议/≥1 模型才可建）；删除行（确认框点名 provider；仅清派生引用）；Custom 标签=目录说 adapter 无此 key；并发写 `settings-conflict` 拒绝。首跑：内测版本须知（仅显式 Continue 记版本）→ DeepSeek 官方 key 引导（任何可达 provider 已配则跳过；Configure later 完成）。
- **Plugins 页**（order 15）：tab「Plugin configuration」= bash（执行器，PowerShell 家族加 pwshPath）/agent-loop（工具并行度）/web-search-deepseek 三张内置卡 + 服务命名空间派发的第三方卡；卡片=展开+暂存式编辑，Save（每字段 revision 围栏，写后回读核实）/Discard/Reset（staged 组合默认）；字段 override 以 user 层 presence 判定；密钥字段空白不写。tab「Plugin list」（`all`，ui-settings-plugin-inventory）= 可搜索只读全插件目录卡（短名+启用标记+root-fiber 状态点；展开=entry id+有效配置+Cordis 状态）。
- onboarding 步骤（`settings.onboarding`）：一次挂载一步，步骤自管 chrome 与完成/跳过。

### 2.26 client-locale / ui-theme 设置行
- 语言：Settings General 行（id `language`，order 0）；`locale.preference` 持久（loopback）；无显式值时按 navigator 主子标签临时匹配，en 兜底；`<html lang>` 跟随；已知限制：注册表持有文案（如 `/model` 描述）注册后不随切换变。
- 主题：`light/dark/system`；`ui-theme.preference`；服务器注入 body 后同步 bootstrap 防 FOUC。远程浏览器进程内。

### 2.27 client-ui-brand-official
- 仅 `DSH_CLIENT_BUILD_PROFILE=official` 填 `sidebar.brand.mark/name` + hero mark；否则 shell 兜底（fish + "DSH Local Build" + commit 徽标）。浏览器标题=构建期 `DSH_CLIENT_TITLE`。

### 2.28 基础设施模块（无直接 UI，但有用户可感知行为）
- **client-connection**：HTTP+WS 载体；连接代际重连；`hostDescription`（握手 `host.describe`，供 canOpenPath 等能力判断）；`connection/reset` 事件驱动全前端缓存重建；`/api` 浏览器信任围栏（Host/trustedHosts、Origin、sec-fetch-site）。
- **client-runtime**：Session/Workspace 对象层、事件窗+历史分页、投影存储（`session/projection` 帧）、queue 镜像（`session/queue`）、jobs 镜像、pendingInteraction 分类（approval/plan-review/question）、blank 复用（connectWorkspace）、fork 标题自增、模型选择快照、重试/turn-error/max-tokens 投影、时区采样随 prompt。**`session.search` 限 20 条结果**。
- **client-modules / client-hmr**：插件包加载与热更（dev watcher；`/plugins/events` SSE `rebuilt` 帧逐插件重载）。
- **cordis-client-runner**：浏览器半侧动态插件执行器（cordis 面板的后端对端）。
- **api-remotes / api-gateway / typert-registry**：RPC 契约与路由，无 UI。
- **session-log-export**：宿主侧会话日志导出（boot 清单内；前端未见对应 UI——存疑项见 §4）。

---

## 3. 隐藏功能 / 非显性入口专节

### 3.1 URL 参数（dsh-client-connection/lib/client.js:10092-10098、10264-10266）
任何带 `?fixture` 查询的页面把整个传输换成**内存 fixture 世界**（demo 模式，不连宿主）：
- `?fixture=empty` — 空世界
- `?fixturePrompt=reject` — 首个 prompt 被拒
- `?fixtureAttach=fail` — 工作区挂载失败
- `?fixtureSessionCreate=drop-response` — 丢 create 响应
- `?fixtureFrames=workspace-first|session-first` — 基线帧序
- fixture 世界含完整样本数据（markdown/终端/工具卡/图片/上下文注入样例，client.js:6377 起）及控制台可驱动的 `startReasoningChunkStorm(id, chunkCount, chunksPerInterval, intervalMs)` 推理流压力源（client.js:8686，无 UI 调用者——浏览器实测确认入口形态）。

### 3.2 键盘快捷键全集
| 位置 | 键 | 效果 |
|---|---|---|
| composer | Enter | 队列发送（默认）或插话（busyEnter 设置项可对调） |
| composer | Shift+Enter | 换行 |
| composer | Cmd/Ctrl+Enter | 与 Enter 对调的另一行为 |
| composer（空草稿） | Cmd/Ctrl+Enter | 全队列依次插话（placeholder 宣传） |
| composer | Cmd/Ctrl+Z / Cmd/Ctrl+Y(或 +Shift+Z) | 草稿撤销/重做 |
| composer | ↑/↓ | 候选菜单导航（经仲裁） |
| composer | Space | 命令名裁决补全（matchSpace） |
| composer | Esc | 关弹层/菜单 |
| composer | Backspace/Delete | 引用 occurrence 整体删除 |
| 无会话 hero textarea | Enter/Space | 打开工作区选择器 |
| 问题 composer | Enter / Shift+Enter | 前进提交 / 换行（IME 期 Enter 只确认候选） |
| 子代理目录 | ←/→/↑/↓/Home/End/Esc/Enter | 树导航/展开/关闭还焦 |
| jobs popover | Esc / 外点 | 关闭还焦触发器 |
| 工作流行 | Enter/Space | 切换折叠 |
| 轨迹总览 | 滚轮=缩放；右键拖=平移；右键单击=清选区；左键拖=选区间 | client.js `event.key`/`contextmenu`/`onDoubleClick` 命中 dsh-client-ui-trajectory |
| lightbox/对话框 | Esc/遮罩 | 关闭 |

### 3.3 页面全局/构建期开关
- `window.__DSH_TRANSPORT__`（替换传输载体，worker 预览用；served web 不设）。
- `window.__ModuleLoader__` / `window.__DSH_BOOT__`（模块系统/boot 图）。
- `DSH_CLIENT_BUILD_PROFILE=official`（品牌）、`DSH_CLIENT_TITLE`（标题）、`DSH_CLIENT_COMMIT_HASH`（侧栏徽标）——构建期注入。
- `/plugins/events` SSE（HMR，仅 dev watcher 存在时活跃）。
- `/plugins/@deepseek-ai/<pkg>/client.js.map` — 每包带 sourcemap 下发（`//# sourceMappingURL=client.js.map`）。
- PWA：`/manifest.webmanifest`（fullscreen，可安装为应用）。

### 3.4 loopback 特权面（远程浏览器自动消失）
Settings 全部持久行（语言/主题/权限/预设/Models/Plugins 写入）、「打开配置文件」、agentPreset 复制/删除/打开目录、native 目录选择器、凭据探测。远程浏览器=设置只读/进程内，composer 功能不受影响。

### 3.5 其他非显性
- 悬停复制：工作区/会话 hover 卡点击=复制（路径/标题），`已复制` 反馈。
- 会话行「未读完成」绿点=完成时间>上次查看（纯本地）。
- 无 localStorage/sessionStorage 使用——所有持久偏好走宿主 settings.yaml（loopback）。
- 命令菜单 fuzzy 搜索（子序列匹配）。
- `+` 按钮=命令启动器（非附件）。
- 轨迹/工作流/子代理树的展开状态细节策略（异常自动展开等）。

---

## 4. 不确定点 / 留给浏览器实测的问题清单
1. **详情列入口**：README 称 `openDetails` 未接线（无入口）。实测：点击工具行/任何位置是否真的无法打开 details 面板？`conversation.session.header.utilities` 座当前是否有任何占用者？
2. **session-log-export** 在 boot 清单里但前端未见任何对应 UI（下载/导出按钮？）。实测宿主是否有导出入口（也许仅 CLI/宿主侧）。
3. **`?fixture` demo 世界**在真实 server URL 上是否完整可用（boot 图仍从 /plugins 下发？）；`startReasoningChunkStorm` 是否可从 DevTools 触发（暴露在哪个全局）。
4. **视图 tab**：是否只有 Chat + Trajectory 两个；轨迹 tab 是否需要某条件（如空会话时隐藏）。
5. **queue 编辑**：多行折叠头 aria/交互实测；strict steer 在真实运行的轮上命中感。
6. **消息分支按钮**：仅完成轮最后一条消息亮起——实测多步轮/中断轮的按钮态。
7. **ContextMeter**：无 token-meter 组合时是否完全不渲染（自建 server 组合可能缺该单元）。
8. **压缩检查点行**：摘要不可用（窗口外）时 `压缩摘要不可用` 的实际形态。
9. **图片限制**：`imageLimits` 投影缺省时 paste 是否直接交给宿主拒绝（toast 文案 `attachment-error` 映射）。
10. **子代理**：本组合（preset=standard?）是否含 subagent 工具；目录树实际可达深度；`@` 运行中源是否出现。
11. **cordis 面板**：`sidebar.footer.action` 是否与 Settings 同排在底部；徽标计数形态。
12. **onboarding**：首跑是否真的弹「内测须知」（版本阈值）与 DeepSeek key 步骤的顺序与样式。
13. **语言切换**：切换后哪些文案立即变、哪些（注册表持有，如 `/model` 描述）保持旧语言——README 已知限制，实测范围。
14. **归档会话**：确认无任何 unarchive/查看归档入口；archive 后当前选中切到新会话页。
15. **权限 chip**：`permissions` 投影缺失（无权限组合）时 chip 是否整体消失。
16. **会话搜索**：内容搜索命中形态（片段高亮？）；`仅显示前 20 条` 提示。
17. **模型 effort 二级菜单**：无 reasoning 元数据的模型是否整行缺席 Effort。
18. **工作区 hover 卡**：Windows 路径原样、POSIX home 缩 `~` 的实际显示。
19. **轨迹视图键盘**：台账行是否有键盘导航（代码只见选择/折叠，未见专门快捷键）。
20. **主题 FOUC 防护**：深色偏好下刷新页面首帧是否已深色（宿主注入 bootstrap）。

---

## 附：证据文件索引
- 各包 README（EN）：`…/node_modules/@deepseek-ai/<pkg>/README.md`（每个均含功能全文+已知限制）
- 各包 `lib/client.js`（可读构建产物；conversation 3716-3795=composer 键盘、9903=locale 注册、9910=busyEnter 行；connection 10040-10099=RPC 分发表、10092=fixture URL 参数、10264=载体选择；runtime 6949-6996=PendingWait/respond、7476-7486=approval/question mint）
- zh 文案抽取汇总：`/tmp/dsh-research/all-zh-dicts.txt`（774 行，各包 zh 字典全文）
- README 批量收集：`/tmp/dsh-research/readme-batch{1,2,3}.txt`
