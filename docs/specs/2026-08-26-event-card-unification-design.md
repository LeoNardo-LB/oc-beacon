# 对话流事件卡片统一（Event Card Unification）设计 spec

> 状态：**设计定稿（2026-08-26，14 问拷问闭环）——待用户确认后实施**
> 需求来源：用户 2026-08-26「主对话流中出现新元素（SSE）的通知样式统一」
> Backlog：#233（两批实施，同一卡片）

## 0. 问题

主对话流内的 SSE 事件元素（非对话消息）有三套互相不像的视觉语言：

| 元素 | 当前形态 | 出处裁决 |
|------|---------|---------|
| task/subagent 完成 | 气泡卡：Notifications 图标+时间+Tasks+状态文案+✓/✗+描述+摘要预览+动作钮 | #67（2026-08-12 独立气泡方案 A） |
| shell 后台完成 | 同上（shell 变体） | #67 |
| system 目录变更 | 单行折叠通知：ℹ+60 字截断+chevron，展开 300dp 滚动 | #232（紧急止血） |

分裂：三种身份、三种容器、图标词汇混乱、对齐不一。

**不在范围**（Q1=A）：压缩三态分割线（#217「分割线包揽一切」裁决不动）、转后台分割线、Snackbar、电池横幅、agent/model-switched（恒空文本不渲染）。

## 1. 统一规格（严格同构，Q2=B Q3=B）

新组件 **EventCard**，三种事件共用一个模子：

### 折叠态（全事件统一）

```
╭──────────────────────────────────────────╮
│ HH:mm  [图标] 事件标签            [→] ⌄ │   ← 标签行
│ 任务描述（可选槽位，仅 task 激活）        │   ← 描述行（Q10=B）
╰──────────────────────────────────────────╯
```

- **容器**：MessageBubble 同构——左对齐、透明底、1dp outline 描边（失败=错误色描边）、ShapeTokens.medium 圆角
- **标签行**（单行）：时间戳（Q9=A）· 类型图标（13dp FAINT）· 事件标签（labelSmall）· 弹性空隙 · 跳转箭头（可选常驻）· chevron
- **描述行**（可选槽位）：仅 task 卡激活——任务描述一行截断（身份信息，非内容预览）；system/shell 不激活
- **严重度编码**（Q5=C）：成功/信息中性灰（图标+描边全中性）；**只有失败**用 AgentError 色+ErrorOutline 图标

### 展开态（统一两段式，Q11=A）

```
│ 标签行（同上，chevron 翻转）                │
│ ─────────────────────────────────────── │
│ 正文区：Markdown 渲染，heightIn(max=300dp) │
│          内部 verticalScroll               │
│ ─────────────────────────────────────── │
│ 动作区：按钮行（有则显示）                  │
╰──────────────────────────────────────────╯
```

- 修饰符顺序铁律：**heightIn 在 verticalScroll 之外**（#232 勘误三教训——反序即崩）
- 动作区按钮：task=「定位发起卡片」；跳子会话箭头不在此（常驻折叠态）

## 2. 三种事件的参数表

| 参数 | task 完成 | shell 完成 | system 目录变更 |
|------|----------|-----------|----------------|
| 标签（Q7=B，i18n） | 子智能体完成 | 后台命令完成 | 工具目录已变更 |
| 失败标签 | 子智能体失败 | 后台命令失败 | —（无失败态） |
| 图标（Q8=A） | CheckCircle | Terminal | Info |
| 失败图标 | ErrorOutline 错误色 | ErrorOutline 错误色 | — |
| 描述行 | 任务描述（extractTaskDescription） | 命令预览 | 不激活 |
| 展开正文 | task_result 输出 | 命令输出 | schema 全文 |
| 跳转箭头（Q4） | 常驻（sessionId 存在时） | 常驻（同） | 无 |
| 展开区动作 | 定位发起卡片 | 无 | 无 |
| 时间戳 | 显示 | 显示 | 显示 |

## 3. i18n（Q7=B）

新增 string keys（英文源→14 语言翻译，按 i18n-guide 工作流）：
- `chat_event_task_completed` / `chat_event_task_failed`
- `chat_event_shell_completed` / `chat_event_shell_failed`
- `chat_event_tool_catalog_changed`

现有 `chat_background_agent_completed` 等旧 key 随旧卡退役删除（含翻译）。

## 4. 行为决策

- **动画**（Q12=A）：无弹入动画，靠列表锚定稳定（#215/#217 基调延续）
- **存量兼容**（Q13=A）：历史消息全走新卡——渲染是客户端职责，同消息不因日期变样；无迁移成本（纯渲染层）
- **展开态记忆**：屏幕级 messageId→expanded 表（#227 模式），滚出视口不丢、离会话即清
- **W1 疣不继承**：system 旧单行通知的「展开把行推出视口」问题在卡片上自然消解（卡有容器高度，倒序列表锚定行为与 task 卡一致）

## 5. 实施切分（Q14=B，同一 backlog 卡 #233 两步）

**批一：EventCard 组件建立 + system 迁入**
- 新建 EventCard.kt（严格同构折叠/展开）
- system 分支（#232 通知）替换为 EventCard
- i18n 新 key（tool_catalog_changed）全语言
- 删除旧单行通知代码
- 单测 + 真机验收（数据库索引详解会话：双 system 消息渲染/展开/收起/无崩溃）

**批二：task/shell 迁入 + 旧卡退役**
- SyntheticNotificationCard 解析逻辑（parseSyntheticTask/extractTaskDescription）平移进 EventCard 参数化
- task/shell 分支替换为 EventCard
- i18n 新 key（task/shell 标签）全语言 + 旧 key 清理
- SyntheticNotificationCard.kt 删除
- 单测 + 真机验收（卡片演示场：task 完成+失败卡渲染/展开/跳子会话/定位发起卡）

## 6. 风险与守恒

- **#216 跳子会话入口**：箭头常驻折叠态——行为等价，位置不变，守恒
- **#67 裁决翻案声明**：task 卡「独立气泡方案 A」形态变更——本 spec 即新裁决，journal 记录翻案链
- **解析层零改动**：parseSyntheticTask 等纯函数平移，不动 SSE/数据层
- **验收基线**：单测全量绿 + 真机双会话（数据库索引/system + 卡片演示场/task）E2E + 用户 V6 手感验收

## 7. 决策记录（拷问链）

14 问全闭环：Q1 范围=族一 / Q2 卡片系 / Q3 严格同构 / Q4 箭头常驻+定位进展开 / Q5 只失败破色 / Q6 system 保留展开 / Q7 i18n 标签 / Q8 来源图标 / Q9 全显时间戳 / Q10 描述行槽位仅 task 激活 / Q11 两段式展开 / Q12 无动画 / Q13 存量全走新卡 / Q14 两批一卡。
