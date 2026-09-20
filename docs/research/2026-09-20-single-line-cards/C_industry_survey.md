# 业界 AI 聊天产品「思考/工具调用轻量单行」渲染调研

> 调研日期：2026-09-20 · 服务对象：oc-beacon（OpenCode Android 客户端）ChatScreen 改版
> 方法说明：`web_search` 端点 402（余额不足），按预案降级为 `web_fetch` 直抓已知 URL + GitHub 源码精读 + 模型知识。
> 来源标注：【实证·源码】= 抓取并阅读了仓库源码；【实证·链接】= 抓取了官方页面/文档；【模型知识】= 基于既有知识，未经本轮实证，置信度已单独标注。

---

## 1. 总对比表

| 产品 | 折叠态单行构成 | 展开态形态 | 与正文的层级处理 | 单行/卡片取舍 |
|---|---|---|---|---|
| **ChatGPT** (app/web)【模型知识,高置信】 | 「Thought for 6s / 2m 14s」灰色小字一行 + chevron；思考中为 shimmer「Thinking…」；搜索类为「Searched the web」等动词短语行 | 点开显示 reasoning **摘要**（非完整 CoT）流式灰字；工具行展开查询列表/来源卡片 | 无背景色块，作为正文前的轻量前导行；展开内容与正文同宽、颜色降级（灰字） | 思考=过程性内容默认折叠；工具（搜索/浏览）用单行+结果源卡；代码画布等富输出才用重卡片 |
| **Claude** (claude.ai)【模型知识,中高置信 + 公告页实证】 | 浅色圆角**面板**，标题「Thought for Xs」+ chevron；流式时面板内直接打字预览 | 面板内显示完整思考文本（灰字），再次点击折叠 | 有**背景色块**（浅灰圆角面板），与正文视觉分离明显 | extended thinking 开关开启才出现；research 步骤「Show research steps」同类折叠；正文=Markdown 常态流 |
| **Gemini** (app/web)【模型知识,中置信】 | 回答上方「Show thinking」pill/按钮；进行中显示「Thinking…」状态文字 | 点开显示思考摘要步骤（内部也是压缩过的），完成后收起为耗时行 | 轻量，无明显色块；答案主体独立呈现 | 思考默认折叠；谷歌搜索 grounding 用源卡（重卡片）展示 |
| **opencode web/session-ui**【实证·源码】 | 16px 语义图标 + i18n 动词标题（14px medium）+ 参数摘要副标题（14px regular muted，ellipsis）+ 可选 args chips + 计数摘要 + 尾部 Arrow；进行中=spinner 16px + 标题 TextShimmer | Collapsible spring 高度动画；read/glob/grep 展开输出 Markdown（可滚动）；shell 展开终端 pre+复制按钮；edit 展开 diff；上下文组展开为逐条工具行 | 普通工具行 **ghost（无背景无边框）**，与正文连续排布；仅 task 子代理行有 0.5px 边框+92% 背景；错误用专属 ToolErrorCard | 详见 §3 决策规则：连续 read/glob/grep/list **自动聚合为「Gathered context · 3 reads, 2 searches」一组**；todowrite 直接隐藏；question pending 不渲染；错误必出卡片 |
| **Cline**【实证·文档 + fork 同构推断】 | 每条消息独立 ChatRow；工具行头=图标+「Roo wants to …」式标题+目标路径+chevron（Roo 同构，见下） | 展开显示 diff/代码块/终端输出（CodeAccordion） | 工具内容块=圆角色块（editor 背景）+ 等宽小字头 | 一切操作需审批：工具行既是进度也是审批入口（approve/reject 按钮在行内） |
| **Roo Code**【实证·源码】 | ReasoningBlock：💡 Lightbulb(w-4) + 加粗「Thinking」+ 耗时秒数（流式时每秒 tick）+ chevron（**hover 才显形**，折叠态 -rotate-180）；工具行头=font-mono 14px 次级色 | ReasoningBlock 展开=**左边线 + pl-4 + 次级前景色 Markdown**（无色块）；工具展开=ToolUseBlock（rounded-md + editor 背景色块）内放 diff/输出 | 思考=左边线弱层级（无背景）；工具=淡背景圆角块；正文=常色 | reasoningBlockCollapsed 全局设置控制默认折叠；错误/警告独立 ErrorRow/WarningRow |
| **GitHub Copilot Chat** (VS Code agent mode)【实证·release notes + 模型知识】 | 工具执行行：图标 +「Ran terminal command `npm test`」/「Read file …」动词短语+目标名+chevron；进行中「Working…」 | 终端命令展开显示输出；编辑显示 diff 摘要与 accept/discard | 行为轻量无色块；diff 等富内容用编辑器内嵌卡片 | 只读工具=单行；有副作用的编辑/命令=需要审阅的块；agent mode 于 v1.99 转正，工具含 fetch/查找引用/deep thinking |

---

## 2. 分产品细节与证据

### 2.1 ChatGPT（官方 app/web）
- 折叠行：「Thought for Xs」——**耗时即摘要**，无图标时仅灰色小字+chevron；流式思考中显示 shimmer 动画文字，完成后定格为耗时。【模型知识,高置信】
- 多步工具同样轻量化：「Searched the web」「Read 3 files」「Browsed for 12s」等动词短语行；点击展开查询关键词与来源卡片。【模型知识,高置信】
- 展开的思考内容是**模型生成的推理摘要**（OpenAI 明确不输出原始 CoT），非完整思维链。【模型知识,高置信】
- 层级：无色块，展开内容灰字弱化，正文黑色常字。间距小，思考行紧贴正文顶部。【模型知识,高置信】

### 2.2 Claude（extended thinking）
- 官方公告确认 extended thinking 面向用户可见（2025-02 Claude 3.7 Sonnet 发布，「可见思考」是主打特性）：【实证·链接】https://www.anthropic.com/news/visible-extended-thinking
- claude.ai 形态：输入框有 thinking 开关；开启后回复顶部出现**浅色圆角面板**，流式时面板内直接预览思考文本，完成收为「Thought for Xs」+chevron 行；展开显示完整思考灰字。【模型知识,中高置信】
- 层级：与 ChatGPT 最大的差异是 **Claude 用背景色块、ChatGPT 用无色块灰字**。两派都把思考视为「可丢弃的次要内容」。
- API 侧：thinking block 需带 signature 原样回传（dev 层面约束了「思考块是一等公民数据结构」）。【模型知识,高置信】

### 2.3 Gemini
- 2.5 系列 Pro/Flash 带 thinking；Gemini app 内为「Show thinking」pill，流式「Thinking…」状态，完成后可展开查看思考摘要步骤。【模型知识,中置信】
- 搜索 grounding 结果用来源卡片（重），思考用轻量行——同一回答内轻重分明。【模型知识,中置信】

### 2.4 opencode web（session-ui）——本调研最硬证据（与 oc-beacon 同源）
仓库：anomalyco/opencode（原 sst/opencode），分支 dev，包 `packages/session-ui/src/components/`。【实证·源码】

**(a) 单行的语法**（basic-tool.tsx + basic-tool.css）：
```
[16px icon] [title 14px/medium/strong] [subtitle 14px/regular/muted, ellipsis] [args chips] … [chevron/spinner]
```
- TriggerTitle 结构体 = { title, subtitle, args[], action }；文本三层色彩：strong / muted / faint。
- 进行中：16px spinner + 标题 TextShimmer；完成后标题动画替换（见下）。
- Collapsible 高度 spring 动画（motion, visualDuration 0.35, bounce 0）。

**(b) 状态标题动画**（tool-status-title.tsx）：每个工具定义 activeText/doneText 两态文案（如「Gathering context…」→「Gathered context」），切换时做前后缀拆分 + 宽度过渡动画（600ms），进行中 shimmer、完成后静态。**单行标题本身就是进度指示器**。

**(c) 语义映射**（message-part.tsx getToolInfo）：read=glasses、grep/glob=放大镜、webfetch/search=window-cursor、shell=console、edit/write=code-lines、skill=brain、task=subagent 卡；标题用 i18n 动词短语而非工具名；副标题=文件名/pattern/URL/命令等**关键参数**。

**(d) 上下文自动聚合**（ContextToolGroup）：连续的 read/glob/grep/list 被合并为一组，折叠行=「Gathered context · 3 reads, 2 searches, 1 list」（AnimatedCountList 逐项计数动画）+ Arrow；展开才看到逐条。variant=`ghost`——无背景。

**(e) 默认展开白名单**（part-default-open.ts）：仅 shell（可配置）与 edit/write/patch（可配置且**非纯删除**）默认展开，其余全部折叠。

**(f) 隐藏与错误**：todowrite 直接 return null（零信息量不渲染）；question pending 不渲染；question 被忽略显示右对齐弱化文本「dismissed」；**status=error 一律渲染 ToolErrorCard**（专属错误卡片，可展开错误详情）。

**(g) 特例卡片**：task（子代理）行是唯一带 0.5px 边框 + 92% 背景的「行内卡」（basic-tool.css task-tool-card），因为它是可跳转的会话链接（外跳图标）。

### 2.5 Cline / Roo Code（VSCode 系）
- Roo Code 是 Cline 的 fork，聊天 UI 同构（连消息类型都仍叫 ClineMessage），以 Roo 源码实证为代表。【实证·源码】
- **ReasoningBlock.tsx**（完整实证）：
  - 折叠行 = `💡 Lightbulb(w-4)` + 加粗「Thinking」+ 耗时秒数（`isLast && isStreaming` 时 setInterval 每秒 tick）+ 右侧 `ChevronUp`（**opacity-0 → group-hover:opacity-100**，hover 才显形；折叠态 -rotate-180）。
  - 展开态 = `border-l + ml-2 + pl-4 + pb-1 + 次级前景色` 的 Markdown 正文——**左边线表达层级，零背景色块**。
  - 全局设置 `reasoningBlockCollapsed` 记住用户偏好。
- **ToolUseBlock.tsx**（完整实证）：工具内容块 = `rounded-md p-2 bg-vscode-editor-background`；行头 = `font-mono text-sm text-vscode-descriptionForeground`（等宽小字头）。
- **ChatRow.tsx**：每条消息独立一行（px-15px py-10px），`isExpanded/onToggleExpand(ts)` 由 ChatView 统一管理；CommandExecution/McpExecution/ReasoningBlock/ErrorRow 等专用行组件分发。【实证·源码】
- Cline 官方文档确认产品形态（编辑器+终端 agent、每步审批）：【实证·链接】https://docs.cline.bot/cline-overview.md

### 2.6 GitHub Copilot Chat（VS Code）
- v1.99 release notes（2025-04）：agent mode 进入 Stable，内置工具含 fetch 网页内容、查找符号引用、**deep thinking**；UI 承载为 chat 内的工具执行行。【实证·链接】https://code.visualstudio.com/updates/v1_99
- 形态【模型知识,中高置信】：工具行=图标+动词短语「Ran terminal command `npm test`」+chevron；进行中「Working…」+旋转指示；命令行展开显示终端输出；编辑类显示 diff 并附 accept/discard；只读工具行保持折叠不刷屏。

---

## 3. 单行 vs 卡片的取舍逻辑（跨产品归纳）

| # | 规则 | 证据 |
|---|---|---|
| 1 | **过程性内容（思考）→ 默认折叠的单行**，以「耗时」为摘要文本；展开是补贴性的摘要而非全文（ChatGPT）/全文灰字（Claude/Roo） | ChatGPT/Claude/Roo【实证+模型知识】 |
| 2 | **只读、低风险、高频工具 → ghost 单行**；且**连续同类自动聚合成一组**，组行显示计数摘要 | opencode ContextToolGroup【实证·源码】 |
| 3 | **有副作用、需审阅的工具（diff/命令）→ 内容块/卡片**（淡背景圆角或编辑器内嵌），行头等宽小字 | Roo ToolUseBlock、Copilot diff、opencode shell【实证】 |
| 4 | **错误永远升级为醒目错误卡**，不允许静默折叠 | opencode ToolErrorCard、Roo ErrorRow【实证】 |
| 5 | **零信息量的部件直接不渲染**（todowrite、pending 的 question） | opencode message-part【实证】 |
| 6 | **需要跳转的行升级为行内卡**（边框+背景+外跳图标），如子代理会话 | opencode task-tool-card【实证·源码】 |
| 7 | **默认展开走白名单**：只有用户必然关心的（编辑非纯删除、可配置的 shell）才默认开 | opencode part-default-open【实证·源码】 |
| 8 | **pending 期间锁展开**（内容未就绪），例外是可边跑边看的 shell；完成时标题做 activeText→doneText 动画翻转 | opencode BasicTool【实证·源码】 |
| 9 | **层级用「色彩降级 + 左边线/无色块」而非边框卡片**：strong 标题 > muted 摘要 > faint 元数据；只有需要「容器感」的富内容才给背景 | 全体【实证】 |
| 10 | chevron 是可省项：hover 显形（Roo）或常显（opencode）；耗时/计数等元数据放在弱化色，不与标题争夺注意力 | Roo/opencode【实证】 |

## 4. 对 oc-beacon（Android · Compose）的建议

1. **统一 ToolRow 语义组件**（对应 opencode BasicTool）：16dp 语义图标 + 14sp 标题（onSurface, Medium）+ 参数摘要（14sp onSurfaceVariant，单行 ellipsis）+ 尾部状态（spinner/耗时/chevron）。chevron 展开时 `animateFloatAsState` 旋转 180°。
2. **思考行**：复用 ToolRow，文案两态「思考中…（shimmer/省略号动画）」→「已思考 Xs」（tabular-nums）；点击展开。展开内容学 Roo：**2dp 左边线（outlineVariant）+ 8dp padding + onSurfaceVariant 正文**，不用背景块——省渲染层级、视觉更轻。
3. **工具行默认折叠**；流式 pending 时禁止展开点击（防误触+内容未就绪），仅 shell/命令类允许边跑边看。
4. **聚合**：连续 read/grep/glob 类 part 在渲染层合并为一行「已收集上下文 · 3 读 2 搜」（计数可做成逐项出现的微动画）；todowrite 等零信息 part 直接不进 UI。
5. **副作用的工具**（edit/patch）展开显示 diff，容器用 `Surface(shape = 8dp, color = surfaceContainerHighest)` 淡色块；**错误**用 `errorContainer` 专用卡片且不可折叠掉。
6. **子代理/task 行**是唯一「行内卡」：1dp 淡边框 + 淡背景 + 外跳图标，点击导航到子会话。
7. 动画预算：折叠展开用 `animateContentSize` 或自管 spring（视觉时长 ~350ms）；标题两态切换可省（移动端增益低），保留 spinner→静态文本的一次性翻转即可。
8. 与正文的间距：工具行组与正文之间 8–12dp，行间 2–4dp；不要给每行加分隔线——用色彩降级承担层级。

## 5. 来源清单

**实证·源码（本轮抓取阅读）**
- opencode session-ui: https://github.com/anomalyco/opencode/tree/dev/packages/session-ui/src/components （basic-tool.tsx / basic-tool.css / tool-status-title.tsx / tool-count-summary.tsx / message-part.tsx / part-default-open.ts / session-turn.tsx）
- Roo Code webview: https://github.com/RooCodeInc/Roo-Code/blob/main/webview-ui/src/components/chat/ReasoningBlock.tsx （及同目录 ChatRow.tsx、common/ToolUseBlock.tsx）

**实证·链接（本轮抓取）**
- Anthropic 可见思考公告: https://www.anthropic.com/news/visible-extended-thinking
- VS Code v1.99 Release Notes（agent mode/deep thinking 工具）: https://code.visualstudio.com/updates/v1_99
- Cline 官方文档: https://docs.cline.bot/cline-overview.md （及 https://docs.cline.bot/llms.txt ）
- opencode 文档站: https://opencode.ai/docs/

**模型知识（未逐条实证，附置信度）**
- ChatGPT「Thought for Xs」折叠行与 reasoning summary：高置信
- Claude claude.ai 思考面板（浅色块）形态：中高置信
- Gemini「Show thinking」pill：中置信
- Copilot Chat agent mode 工具行/终端展开细节：中高置信（行为框架有 v1.99 notes 佐证，视觉细节为模型知识）

### 附注
- web_search 本轮不可用（402 余额不足），已按预案降级；ChatGPT/Gemini/Copilot 的视觉细节无法从官方 help center（SPA/重定向）抓取，均标注为模型知识，采纳其设计结论时建议以真机截图复核。
- opencode 仓库已从 sst/opencode 迁移至 anomalyco/opencode；packages/web 现为文档站，聊天/分享 UI 在 packages/session-ui（oc-beacon 对接的 V2 API 与其 SDK 同源，可直接复用其 part 语义）。
