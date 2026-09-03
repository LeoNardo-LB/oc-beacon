# DSH Web 前端用户可见功能点全量普查（分母清单）

> 日期：2026-09-02 · DSH 0.1.1-rc.2 · 只读调研，未开新浏览器会话
> 证据：`fe-inventory.md` §0-§3.5（28 模块源码清点，主源）× `web-walkthrough.md` + `web-walkthrough2/3/4/5.md`（五轮活体走查）× `2026-09-01-dsh-web-vs-android-gap.md` §1-§7（粒度锚点）
> 用途：与 Android 端（OC Beacon）对账的「Web 端总共有多少功能点」分母。**已对齐的普通功能同样列内**，不做差距筛选。

## 口径

- **功能点** = 用户能感知/操作的一个独立能力。粒度对齐主文档 §1-§7 行粒度，过粗行已拆细（如轨迹视图拆台账/检查器/时间轴缩放；@ 引用拆文件/目录/引号形态/会话）。
- **标记**：`普通` = 远程浏览器可见可用 · `🔒` = 仅 loopback 特权（主文档 §11.2：15 方法 403 活体终判；fe §3.4）· `⏸` = 部署禁用/服务器不暴露（session.search、workflow 事件）。
- **不计入分母**：无 UI 基础设施（client-modules/hmr、api-gateway、typert-registry、sourcemap、`__DSH_TRANSPORT__`、dev 限定 `/plugins/events` HMR）、用户自有插件（dsh-turn-notify/dsh-keepalive）、仅 DevTools 断点可达的 `startReasoningChunkStorm`。`?fixture` demo 世界计入（隐藏 URL 入口，D9）。
- 源码-实测矛盾点保留并注明（详情面板、`+` 启动器、QueueDock 行控件），标记从实测口径。

---

## D1 会话管理（15）

| ID | 功能点 | 交互形态 | 证据 | 标记 |
|---|---|---|---|---|
| D1-01 | 会话搜索-标题即时匹配 | 侧栏搜索框（折叠态为头部图标）输入即过滤标题/工作区子串 | fe §2.3；走查1 §1、走查2 §A3 | 普通 |
| D1-02 | 会话搜索-内容全文搜索 | 同框 250ms 防抖发 `session.search`，ranked 片段上限 20 条 + hasMore 收窄提示 | fe §2.3；主文档 §11.1（openAt=never 活体实证） | ⏸ |
| D1-03 | 搜索降级警告条 | 内容搜索不可用时显「内容搜索暂不可用，仅显示名称匹配。」 | 走查2 §A3 | 普通 |
| D1-04 | 会话行状态点 | 行前状态点：进行中蓝/等待审批/计划待审/等待回答琥珀（优先级最高）/N 个子代理运行中/已完成 | fe §2.3；走查2 §B9（橙/绿/虚线） | 普通 |
| D1-05 | 未读完成绿点 | 完成时间晚于上次查看显绿点提醒（纯本地） | fe §2.3/§3.5 | 普通 |
| D1-06 | 会话行相对时间戳 | 树项显示 1小时/5小时/1天 级相对时间，随活动刷新 | 走查1 §1；走查2 §B9 | 普通 |
| D1-07 | 工作区会话折叠展开 | 每工作区默认展开 5 条 +「展开其余 N 个会话/收起」 | fe §2.3；走查1 §1 | 普通 |
| D1-08 | 会话重命名 | 行菜单→对话框（预填标题+取消/重命名双钮）；宿主规范化可拒 title-invalid；确认未变=固定当前自动标题 | fe §2.3；走查5 §4 | 普通 |
| D1-09 | 会话分叉 | 行菜单「分叉会话」：fork 末完成轮开新会话，标题 (N) 自增 | fe §2.3；走查1 §10 | 普通 |
| D1-10 | 会话归档 | 行菜单「归档会话」：无确认、非破坏、从所有分组表面消失；无任何 unarchive 面 | fe §2.3；走查1 §10 | 普通 |
| D1-11 | 消息级分支 | 助手气泡 IconActions「在新对话中分支」，仅完成轮末条可用（超时提示），点击后树变化+导航切换 | fe §2.4；走查5 §3 | 普通 |
| D1-12 | 分组方式切换 | 视图选项：按工作区 / 单列表 | fe §2.3；走查1 §12 | 普通 |
| D1-13 | 排序方式切换 | 视图选项：手动排序 / 最近更新 | fe §2.3；走查1 §12 | 普通 |
| D1-14 | 拖拽改序 | 真实工作区 Manual 拖序持久化宿主（insertBefore/insertSessionBefore）；Ungrouped 仅本地 | fe §2.3 | 普通 |
| D1-15 | 悬停卡 | hover 工作区/会话卡：创建时间 + 复制路径/复制标题 +「已复制」反馈 | fe §2.3/§3.5 | 普通（自动化 8 次不可达，源码兜底） |

## D2 工作区组织（8）

| ID | 功能点 | 交互形态 | 证据 | 标记 |
|---|---|---|---|---|
| D2-01 | 添加工作区入口 | 侧栏「添加工作区…」触发目录选择流 | fe §2.3；走查1 §1/§13 | 普通 |
| D2-02 | 应用内目录浏览器 | Miller 双列 680×500：面包屑+可点击编辑路径+选行双列+Open 采纳（`host.listDirectory`） | fe §2.23；走查1 §13 | 普通 |
| D2-03 | 浏览器内新建文件夹 | New folder 嵌套创建并选中新目录（`host.createDirectory`） | fe §2.23；走查1 §13 | 普通 |
| D2-04 | 显示隐藏项开关 | 宿主始终列出、客户端过滤，footer 开关揭示 | fe §2.23；走查1 §13 | 普通 |
| D2-05 | 原生 OS 目录选择器 | `host.pickDirectory` 宿主对话框（renderless、无中途取消） | fe §2.23；主文档 §11.2 | 🔒 |
| D2-06 | 工作区重命名 | 工作区菜单→重命名 | fe §2.3；走查1 §11 | 普通 |
| D2-07 | 工作区删除 | 确认框说明保留边界；会话落「未分组」 | fe §2.3；走查1 §11 | 普通 |
| D2-08 | 新会话 hero 工作区选择器 | 空会话页虚线 composer 卡整体即触发（Enter/Space 可开）；选中后 textarea 可编辑；与预设 chip 并排 | fe §2.3/§2.4；走查2 §A9/§B1 | 普通 |

## D3 Composer 输入（19）

| ID | 功能点 | 交互形态 | 证据 | 标记 |
|---|---|---|---|---|
| D3-01 | @ 文件引用 | `@` 触发文件源，选中插原子内联 chip（文件 glyph+业务色名）；带空格路径语法可用 | fe §2.8；走查2 §A2/§B7 | 普通 |
| D3-02 | @ 目录引用 | 目录插普通可编辑路径文本（folder glyph），尾斜杠保持菜单继续下钻 | fe §2.8 | 普通 |
| D3-03 | @ 引号形态 | `@"…` 开引号仅搜文件（真实端 16 项实测） | fe §2.8；走查2 §A2 | 普通 |
| D3-04 | @ 会话引用 | Session 组候选→插 `@[label](dsh-session:…)` 规范 mention，发送携 ref 宿主捕获 | fe §2.8；走查2 §B7 | 普通 |
| D3-05 | 引用 chip 渲染与剪贴板 | backdrop 彩色 chip 对齐渲染；复制/剪切带引用序列化 | fe §2.4 | 普通 |
| D3-06 | 图片粘贴 intake | 粘贴文件=图片 intake；imageLimits 预检（数量/单张/总量）超限 toast | fe §2.4/§2.22 | 普通 |
| D3-07 | 拖放附件 | 全窗拖放邀请遮罩（禁用态换阻断插画） | fe §2.22 | 普通（合成 DnD 不可实测） |
| D3-08 | 草稿图片轨 | 64px 缩略图横排、圆箭头翻页、hover 删除（触屏常显）、滚轮转横滚、单击开原图 | fe §2.22 | 普通 |
| D3-09 | 历史图片画廊 | 单图 240px cover 裁剪/多图 64px 方块/失败显重试 | fe §2.22；走查5 §1 | 普通 |
| D3-10 | 原图 lightbox | 全屏 dialog，Esc/遮罩/关闭钮关闭，焦点还原不陷阱 | fe §2.22；走查5 §1 | 普通 |
| D3-11 | `+` 命令启动器 | composer 加号打开 `/` 命令源（非附件钮） | fe §2.4 | 普通（走查5 实测不可见——源码-实测差异登记） |
| D3-12 | 发送/停止圆钮 | 忙碌时圆钮整体变「停止生成」（无双键并存），pending 指示点；空闲恢复发送 | 走查3 §1/§2；fe §2.4 | 普通 |
| D3-13 | 排队 dock 呈现 | 1 行直显 / ≥2 行折叠「N 条排队消息」；行带时间戳 | fe §2.4；走查5 §2 | 普通 |
| D3-14 | 队列行控件 | 行内编辑（仅纯文本行，Enter 存 Esc 取消）/删除/插话发送（strict steer 原子转移；输了不报错） | fe §2.4 | 普通（走查5：悬停仅露复制——源码-实测差异） |
| D3-15 | TodoDock todo 条 | 标题+计数头「1 已完成 · 2 进行中 · 1 待处理」，可折叠 | fe §2.4；走查2 §B4 | 普通 |
| D3-16 | GoalBar | 活动目标条：编辑/暂停/恢复/清除（CAS ref），内联错误；加载中/无/已完成/已清除不渲染 | fe §2.13 | 普通 |
| D3-17 | 统计条 | 随 composer 粘滞：轮/步/LLM/工具墙钟/token 计费/cache 命中率（真 100% 才显 100%）/TTFT·tok/s；溢出 hover 全文 | fe §2.4；走查2 §A8 | 普通 |
| D3-18 | ContextMeter 环+面板 | composer 尾 14px 占用环，点击开 percent used + ~used/capacity + 分色条 + 构成行（system/tools/messages） | fe §2.4；走查1 §8 | 普通 |
| D3-19 | composer 阻塞态 | 无可路由 adapter 时输入惰化、模型座保持可用 +「Select model」兜底 | fe §2.4/§2.10 | 普通 |

## D4 命令与模式（18）

| ID | 功能点 | 交互形态 | 证据 | 标记 |
|---|---|---|---|---|
| D4-01 | `/` 命令菜单 | 斜杠触发 command 源（per-session 目录缓存、三路分发 execute/popupSelect/leadingInput、分组菜单） | fe §2.5/§2.6；走查1 §2 | 普通 |
| D4-02 | 命令执行回显 | 命令输入=右对齐等宽 user 风气泡 + detached 结果节点；命令行与结果不进会话日志 | fe §2.13；走查4 §A1、走查3 §4 | 普通 |
| D4-03 | 命令带图限制 | 仅声明 input.images 的命令可带图执行，否则整单拒绝 toast（草稿+图保留） | fe §2.6；走查1 §2 | 普通 |
| D4-04 | /compact | 压缩较早会话历史（宿主命令 7 个之一） | 走查1 §2 | 普通 |
| D4-05 | /export | 头部 Session log 按钮/命令 → ZIP 下载确认对话框 | 走查1 §2/§9 | 普通 |
| D4-06 | /feedback | 记录对本会话的反馈（会话级） | 走查1 §2 | 普通 |
| D4-07 | 消息级反馈 👍/👎 | 完成轮末条助手消息动作行：👍/👎+备注（portaled 编辑器）；再点撤回；换边保留备注；CAS version 就地和解 | fe §2.19；走查1 §6 | 普通 |
| D4-08 | /goal | 创建目标（GoalBar 呈现见 D3-16） | fe §2.13；走查4 §A1 | 普通 |
| D4-09 | /plan | 进入/退出 plan 模式；激活后 placeholder 切「描述你的任务以生成计划」 | fe §2.11；走查4 §A2 | 普通 |
| D4-10 | Plan chip | 黄字 Plan × 状态钮，点按执行 `/plan off`；投影确认才消；失败内联报错 | fe §2.11 | 普通 |
| D4-11 | /permission 权限档切换 | composer Access chip + `/permission` 裸调弹出两面；三档 Read Only/Workspace Write/Full access；chip 会话级 | fe §2.12；走查1 §3、走查2 §B10 | 普通 |
| D4-12 | Full access 风险确认 | danger-full-access 先弹 Modal 勾选确认才激活 | fe §2.12 | 普通 |
| D4-13 | /model 模型选择 | composer 模型座 + `/model` 弹选同一目录：provider 分组+模型副标题（快速响应/复杂任务） | fe §2.10；走查1 §4、走查3 §6 | 普通 |
| D4-14 | 推理力度（effort）选择 | 两级菜单第二层 Low/High/Max（menuitemradio）；/model 落默认 effort，座可再改 | fe §2.10；走查1 §4 | 普通 |
| D4-15 | `/` 技能源 | skill.list 候选、startsWith 过滤、选中落字面 `/name `；宿主 pre-step 对消息内空白边界 /name 等价注入（~33 技能） | fe §2.7；走查1 §2 | 普通 |
| D4-16 | 技能「仅用户」标记 | modelInvocable:false 项带「仅用户 ·」前缀 | fe §2.7；走查3 §4 | 普通 |
| D4-17 | Agent 预设选择 | 新会话页预设 chip：标准/PTC/极简/创造四档带描述（staged 一次性） | fe §2.24；走查1 §5 | 普通 |
| D4-18 | 会话头预设只读标签 | 当前预设只读标签 | fe §2.24 | 普通 |

## D5 会话内容渲染（38）

| ID | 功能点 | 交互形态 | 证据 | 标记 |
|---|---|---|---|---|
| D5-01 | 用户气泡 | 时钟+复制；不可编辑无分支；steering 插话气泡同款呈现轮中 | fe §2.4 | 普通 |
| D5-02 | Markdown（GFM） | 助手气泡 markdown 渲染 | fe §2.4；走查1 §6 | 普通 |
| D5-03 | KaTeX 数学 | 数学公式渲染（内嵌 KaTeX 字体） | fe §2.4 | 普通 |
| D5-04 | Shiki 代码高亮 | 24 语言高亮 | fe §2.4 | 普通 |
| D5-05 | 消息 hover TTFT/tok/s | 助手消息 hover 显 `TTFT Xs · Y tok/s` | fe §2.4 | 普通 |
| D5-06 | 消息尾 meta | 日期 · 用时 · 首 token · tok/s | 走查2 §A8 | 普通 |
| D5-07 | Think 行 | 默认折叠；流式摘要跟随最后非空行滚动；展开整段推理入正文 | fe §2.4；走查2 §B11 | 普通 |
| D5-08 | 上下文注入/跨会话召回折叠行 | disclosure 头标+生产者名，body 141px 内滚 | fe §2.4 | 普通 |
| D5-09 | 压缩检查点行 | 「已压缩 N 条历史记录（约 X tokens）」hover/聚焦展开披露+摘要；/compact 运行行同 key | fe §2.4 | 普通 |
| D5-10 | 重试行 | 静音行+客户端锚定倒计时（1s 下限）+shimmer；激活显最新延迟/失败信息；always 策略显 ∞ | fe §2.4 | 普通 |
| D5-11 | 轮终态失败内联 | 终态失败在轮边界内联展示（AUTH 拷贝固定 API key is invalid） | fe §2.4 | 普通 |
| D5-12 | max-tokens 截断卡 | 专门警告节点+「发送 continue 可续」提示 | fe §2.4；走查2 §B5 | 普通 |
| D5-13 | 工具卡-terminal | 命令行+ANSI 彩色输出（✓/✗ 计时、覆盖率表） | fe §2.15；走查2 §B6 | 普通 |
| D5-14 | 工具卡-read | 下划线可点文件路径 | fe §2.15；走查2 §B6 | 普通 |
| D5-15 | 工具卡-write/edit | diff 卡 | fe §2.15 | 普通 |
| D5-16 | 工具卡-search | grep/glob 搜索卡 | fe §2.15；走查2 §B6 | 普通 |
| D5-17 | 工具卡-web | web 结果卡 | fe §2.15；走查2 §B6 | 普通 |
| D5-18 | 工具卡-todo | todo 卡 | fe §2.15 | 普通 |
| D5-19 | 工具卡-question | question 卡 | fe §2.15 | 普通 |
| D5-20 | Code Dispatch 卡 | 递归子调用树 | fe §2.15 | 普通 |
| D5-21 | generic 兜底分类 | 按工具名分 search/read/shell/write/edit/code/generic | fe §2.15 | 普通 |
| D5-22 | 工具行生命态 | running shimmer/success/failed 红点/interrupted 警告；行点击展开收起 | fe §2.15；走查2 §B6 | 普通 |
| D5-23 | 工具行路径点击打开文件 | `host.openPath`（相对路径按 cwd 解析）；拒绝弹页内对话框（原因+重试/取消） | fe §2.15；主文档 §11.2 | 🔒 |
| D5-24 | 大结果 spill 提示 | terminal 卡「Full … result stored at: …」提示 | fe §2.15 | 普通 |
| D5-25 | skill 工具行 | 折叠=图标+Skill+名称；展开=Instructions 卡（durable）+检查器入口 | fe §2.7 | 普通 |
| D5-26 | 产出文件行 deliverables | 完成轮尾安静标签+文件 chip（≤6 宽度截断 + N files），点击打开 | fe §2.16 | 普通 |
| D5-27 | Show in folder | 隐藏时次行入口（仅 loopback 且 canOpenPath） | fe §2.16；主文档 §11.2 | 🔒 |
| D5-28 | 内联文件提及可点 | 结尾散文精确路径/唯一 basename → link 蓝可点开文件（歧义不猜） | fe §2.16 | 普通 |
| D5-29 | 视图双 tab | 会话头 Chat / Trajectory 切换 | fe §2.4；走查1 §6 | 普通 |
| D5-30 | 轨迹事件台账 | User/Assistant/Tool/嵌套 Subtool 行；Turn 边界粗规则+行内 Step 标记；选中行检查 | fe §2.18；走查2 §B14 | 普通 |
| D5-31 | 轨迹检查器 | 选中记录本地面板：token 用量/时长/Input/Output/Timing | fe §2.18；走查1 §7 | 普通 |
| D5-32 | 轨迹 Turns/Calls 折叠 | Turns→轮摘要（N steps · N tool calls）；Calls→「1 tool call · echo」计数摘要 | fe §2.18；走查3 §3 | 普通 |
| D5-33 | 轨迹 Duration 切换与搜索 | Use actual duration/等宽切换 + Turns/Calls 展开收起钮 + 搜索轨迹 | fe §2.18；走查1 §7 | 普通 |
| D5-34 | 聊天「加载更早」翻页 | 长会话聊天侧手动翻页按钮（实测正文 23K→64K 追加） | 走查2 §A6、走查4 §B5 | 普通 |
| D5-35 | jobs 徽标+popover | 头部徽标（running+stopping 计数，0 隐藏）；popover 活跃升序/已结束降序+每秒秒表；只读 | fe §2.14 | 普通（popover 实操未捕获，源码兜底） |
| D5-36 | 工作流运行节点 | 运行行/阶段/成员三级树折叠（异常自动展开、完成折叠） | fe §2.17；主文档 §5.13（#288） | ⏸ |
| D5-37 | 工作流成员跳转 | 运行中成员（五条件）下划线可点跳子会话 | fe §2.17；主文档 §5.13（#288） | ⏸ |
| D5-38 | 详情面板 | 右侧详情列「点击消息流中的工具行查看详情」——openDetails 未接线，两轮实测均不可打开 | fe §2.4/§2.15；走查2 §B12 | 普通（有面板无入口——不可用实锤，对账勿采信） |

## D6 子代理（9）

| ID | 功能点 | 交互形态 | 证据 | 标记 |
|---|---|---|---|---|
| D6-01 | 谱系面包屑 | 头部「父标题/子标题」；点祖先/父标题一键上行 | fe §2.9；走查4 §B4 | 普通 |
| D6-02 | 子代理目录下拉 | 头部后代计数（「11/25 个子代理」），hover 150ms 或 ↓ 开启 | fe §2.9；走查2 §A5、走查5 §5 | 普通 |
| D6-03 | 目录行信息 | 模式（可继续/one-shot）+ running/inactive + 标题 + 任务类型/标签 + token 四桶合计 + 活跃时长精确到秒 | fe §2.9；走查4 §B4 | 普通 |
| D6-04 | 子会话下钻 | 点目录行 `openSubagent` 导航至子会话视图 | fe §2.9；走查4 §B4 | 普通 |
| D6-05 | 子代理续聊 composer | continuable 且父活着=普通输入框，FIFO 走 `subagent.prompt`；Stop 走 `subagent.interrupt` | fe §2.9；走查4 §B4 | 普通 |
| D6-06 | one-shot 只读记录 | one-shot 子会话 composer=只读执行记录 | fe §2.9 | 普通 |
| D6-07 | 父不可用只读态 | continuable 但父不可用=只读+恢复路径文案 | fe §2.9 | 普通 |
| D6-08 | @ 运行中子会话源 | @ 菜单 Session 组仅列运行中子会话（零 RPC），选中插 `@label ` 字面 | fe §2.9；走查2 §B7 | 普通 |
| D6-09 | 侧栏隐藏子代理行 | subagent 来源会话不出侧栏（从父会话头进入）；普通 fork 保留 | fe §2.3/§2.9 | 普通 |

## D7 审批与问答（9）

| ID | 功能点 | 交互形态 | 证据 | 标记 |
|---|---|---|---|---|
| D7-01 | 审批面板接管 | composer 琥珀条+正当性标题+命令行对 | fe §2.4；走查2 §B3 | 普通 |
| D7-02 | 允许一次/拒绝 | 应答仅 once（无 always），答后面板消解 | fe §2.4；走查2 §B3 | 普通 |
| D7-03 | 问答接管+进度导航 | 一次一题+「1 / 3」+上一题/下一题/跳过本题；草稿不持久（宿主请求权威） | fe §2.20；走查2 §B2 | 普通 |
| D7-04 | 单选题 | radio+推荐徽章+点选即前进 | fe §2.20；走查2 §B2 | 普通 |
| D7-05 | 多选题 | checkbox 草稿保留+辅助文案+提交钮 | fe §2.20；走查2 §B2 | 普通 |
| D7-06 | 自定义答案 | textarea 高度镜随至 6 行封顶内滚；可与选项并存 | fe §2.20；走查2 §B2 | 普通 |
| D7-07 | 跳过此题 | 保留其他草稿发空 `{selected:[]}` | fe §2.20；走查2 §B2 | 普通 |
| D7-08 | 收起/放弃整组 | 收起问题卡片；放弃=ASK_CANCELLED 整单拒收 | fe §2.20；走查2 §B2 | 普通 |
| D7-09 | plan-review 专卡 | Plan review 条+计划 markdown 滚动体+Chat about it / Refuse / Approve（按意图点名不依赖选项顺序） | fe §2.20 | 普通 |

## D8 设置与管理（27）

| ID | 功能点 | 交互形态 | 证据 | 标记 |
|---|---|---|---|---|
| D8-01 | Settings 面板壳 | 侧栏底部触发模态+分区导航（通用/模型/插件/Agent 预设） | fe §2.25；走查1 §14 | 普通（远程 settings.describe 403 → 行面降级，主文档 §11.2/#298） |
| D8-02 | 语言切换行 | General→Language zh/en；持久 locale.preference；`<html lang>` 跟随；注册表持有文案不随切换 | fe §2.26；走查3 §5 | 🔒（持久；远程进程内临时可用） |
| D8-03 | 主题行 | 浅色/深色/跟随系统；宿主注入防 FOUC | fe §2.26 | 🔒（同上） |
| D8-04 | 繁忙时 Enter 行为行 | 插话/发送对调（Cmd/Ctrl+Enter 用另一行为） | fe §2.4；走查1 §14 | 🔒 |
| D8-05 | 默认权限模式行 | 未来会话默认档；选项宿主动态枚举 | fe §2.12/§2.25 | 🔒 |
| D8-06 | 默认 Agent 预设行 | 新会话组合默认（agent-presets default 字段） | fe §2.24/§2.25 | 🔒 |
| D8-07 | 打开配置文件 | settings.openDocument 宿主原生编辑器打开/物化 settings.yaml（仅 loopback 且 hasDocument） | fe §2.25；走查1 §14 | 🔒 |
| D8-08 | provider 行状态 | 绿点=已配置引用凭据 / 红点=具名引用缺失 | fe §2.25 | 🔒（读 settings 特权） |
| D8-09 | API key 写入 | 单输入 write-only credentials.set（派生 `<ROUTE>_API_KEY`，yaml 永不落值） | fe §2.25 | 🔒 |
| D8-10 | provider 编辑卡 | baseURL/模型表（id/显示名/contextWindow/maxTokens，K·M 后缀）/显示名/API 协议折层 | fe §2.25 | 🔒 |
| D8-11 | Fetch available models | llm.discoverModels 按当前表单（含未保存值）询问端点；Select all/Deselect/Add selected | fe §2.25 | 🔒 |
| D8-12 | 添加自定义 provider | Provider ID 校验（小写字母开头）/端点/协议/≥1 模型才可建 | fe §2.25 | 🔒 |
| D8-13 | 删除 provider | 确认框点名；仅清派生引用；并发写 settings-conflict 拒绝 | fe §2.25 | 🔒 |
| D8-14 | onboarding 内测声明 | 首跑「内测声明」模态+继续按钮（远程持久化失败曾卡死，后自愈） | fe §2.25；走查2 §B1、走查4 §B6、走查5 §4 | 普通（模态远程可见；确认持久化 🔒） |
| D8-15 | onboarding DeepSeek key 引导 | 任何可达 provider 已配则跳过；Configure later 完成 | fe §2.25 | 🔒（完成需写 key） |
| D8-16 | Plugins 配置卡 | bash（pwshPath）/agent-loop（并行度）/web-search-deepseek+第三方卡；暂存式编辑+Save（revision 围栏+回读核实）/Discard/Reset | fe §2.25；走查1 §14 | 🔒 |
| D8-17 | Plugin list 清单 tab | 可搜索只读全插件目录（短名+启用标记+root-fiber 状态点；展开 entry id/有效配置/Cordis 状态） | fe §2.25；走查1 §14 | 普通（pluginInventory/list 远程 200 活体实证，主文档 §11.2） |
| D8-18 | 预设 roster 浏览 | 卡片列表：user 行标/broken 红框+失败徽标+禁用（broken 在选择器整体隐藏） | fe §2.24；走查1 §14 | 普通 |
| D8-19 | 预设查看器 | shipped 预设只读查看器（agentPreset.read） | fe §2.24 | 🔒 |
| D8-20 | 复制创建预设 | 唯一创建路径：复制对话框（id 校验+可选显示名）→完成打开新目录 | fe §2.24；走查1 §14 | 🔒 |
| D8-21 | 删除预设 | 删目录（会话不受影响） | fe §2.24 | 🔒 |
| D8-22 | 打开预设目录 | agentPreset.openDocument（无 opener 时目录文本显示行上） | fe §2.24 | 🔒 |
| D8-23 | dashed 添加卡 | 管理页虚线添加卡→staged+开新会话（创造模式起草）；authorable:false 部署=纯只读浏览器 | fe §2.24；走查1 §5 | 普通 |
| D8-24 | Cordis 面板入口 | 侧栏底部入口+徽标（运行中+待答计数）；全库定义列表（当前会话组在前、阻塞内置顶） | fe §2.21 | 普通（Web 专用 runner，Android 架构不适用） |
| D8-25 | Cordis approve/decline | 运行请求应答（任何 tab 可答，先答赢） | fe §2.21 | 普通 |
| D8-26 | Cordis Run/Load/Stop/Undefine | 行动作（Run 携 hasClientHalf；本页 Load 重载恢复；全局 Stop；Undefine）+内联失败原因 | fe §2.21 | 普通 |
| D8-27 | cordis_define 聊天卡 | 聊天内只读定义卡（名称/用途/来源/运行态；cordis_undefine 终态优先） | fe §2.21 | 普通 |

## D9 全局壳与导航（11）

| ID | 功能点 | 交互形态 | 证据 | 标记 |
|---|---|---|---|---|
| D9-01 | 三栏布局 | 侧栏+会话列+详情列框架 | fe §2.1；走查1 §1 | 普通 |
| D9-02 | 侧栏折叠 rail | 折叠保留 56px rail；150ms 淡出+49px 左移动画（reduced-motion 关闭） | fe §2.1/§2.2；走查1 §1 | 普通 |
| D9-03 | 面板拖宽 | 侧栏隐形命中条+详情列浮动 pill 拖拽调宽 | fe §2.1 | 普通 |
| D9-04 | 详情列自动收起 | 拥挤时仅详情列收缩并自动关闭；切会话先关再绘；几何瞬态刷新重置 | fe §2.1 | 普通 |
| D9-05 | 品牌行 | official mark/name；非 official=fish mark+DSH Local Build+commit 徽标；「探索未至之境 · 预览版」角标 | fe §2.2/§2.27；走查1 §1 | 普通 |
| D9-06 | 新会话按钮 | 作用域解析：显式作用域>当前会话所属>最近活跃；无工作区进空白新会话页 | fe §2.2 | 普通 |
| D9-07 | PWA 可安装 | manifest fullscreen「DeepSeek Harness/DSH」 | fe §3.3 | 普通 |
| D9-08 | 浏览器标题注入 | 构建期 DSH_CLIENT_TITLE | fe §2.27 | 普通 |
| D9-09 | 侧栏滚动条自动隐藏 | 指针离开 2s 后隐藏（重绑 transparent） | fe §2.2 | 普通 |
| D9-10 | ?fixture demo 世界 | `?fixture=empty` 等参数切内存样本世界（完整工具卡/图片样例+变体参数）；真实端可用性未实测（fe 存疑 #3） | fe §3.1 | 普通（隐藏 URL 入口） |
| D9-11 | 断线自动重连 | 连接代际重连+`connection/reset` 驱动全前端缓存重建 | fe §2.28 | 普通 |

## D10 键盘与效率（15）

| ID | 功能点 | 交互形态 | 证据 | 标记 |
|---|---|---|---|---|
| D10-01 | Enter 发送语义 | 默认排队发送；busyEnter 设置对调为插话 | fe §2.4/§3.2 | 普通 |
| D10-02 | Shift+Enter 换行 | composer 多行输入 | fe §3.2 | 普通 |
| D10-03 | Cmd/Ctrl+Enter 对调行为 | 与 Enter 对调的另一行为；空草稿=全队列依次插话（placeholder 宣传） | fe §2.4/§3.2 | 普通 |
| D10-04 | 草稿撤销/重做 | Cmd/Ctrl+Z / Y(或 Shift+Z) | fe §3.2 | 普通 |
| D10-05 | ↑/↓ 菜单导航 | 候选菜单键盘仲裁（combobox aria-activedescendant） | fe §2.5/§3.2 | 普通 |
| D10-06 | Space 命令裁决 | matchSpace 精确名补全 | fe §3.2 | 普通 |
| D10-07 | Esc/卡外点关闭 | 关弹层/菜单/lightbox/对话框；jobs popover 关闭还焦触发器 | fe §3.2 | 普通 |
| D10-08 | 引用整体删除 | Backspace/Delete 删除引用 occurrence（原子） | fe §3.2 | 普通 |
| D10-09 | IME 合成期让路 | keyCode 229 期全部快捷键让路；问答 Enter 只确认候选 | fe §2.4/§2.20 | 普通 |
| D10-10 | hero Enter/Space 开选择器 | 无会话 hero textarea 键盘开启工作区选择器 | fe §3.2 | 普通 |
| D10-11 | 子代理目录树键盘 | ←→ 展开/收起、↑↓ 兄弟切换、Home/End/Esc、Enter 选中 | fe §2.9/§3.2 | 普通 |
| D10-12 | fuzzy 子序列匹配 | 命令/技能菜单：大小写不敏感子序列、前缀优先/分隔边界/短间隙加分（只影响发现） | fe §2.6/§3.5 | 普通 |
| D10-13 | 轨迹时间轴缩放平移 | 滚轮缩放、右键拖平移、右键单击清选区、左键拖选区间聚焦、500ms hover 精确时钟/时长 | fe §2.18 | 普通（可视效果三轮未捕获，源码兜底） |
| D10-14 | 轨迹虚拟滚动 | 长账初始在尾、到顶自动/点击加载更早页（Between turns 段）、向上滚动暂停跟随 | fe §2.18 | 普通 |
| D10-15 | 工作流行 Enter/Space 折叠 | 工作流节点键盘切换 | fe §3.2 | ⏸（随 workflow 域） |

---

## 总账

- **功能点总数：169**
- **分布**：普通 144 · 🔒loopback 21 · ⏸ 部署禁用 4

| 域 | 数量 | 域 | 数量 |
|---|---|---|---|
| D1 会话管理 | 15 | D6 子代理 | 9 |
| D2 工作区组织 | 8 | D7 审批与问答 | 9 |
| D3 Composer 输入 | 19 | D8 设置与管理 | 27（其中 🔒 18） |
| D4 命令与模式 | 18 | D9 全局壳与导航 | 11 |
| D5 会话内容渲染 | 38（其中 ⏸ 2） | D10 键盘与效率 | 15（其中 ⏸ 1） |

🔒 21 = D2-05 + D5-23/27 + D8 全部特权行（读/写同罪，PRIVILEGED_METHODS 15 方法 403 活体终判，主文档 §11.2）。
⏸ 4 = D1-02（session.search openAt=never）+ D5-36/37 + D10-15（workflow 事件服务器不暴露，#288）。
