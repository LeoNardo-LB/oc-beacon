# DSH web 前端实测走查记录（主 agent 浏览器实操）

- 时间：2026-09-01 · 目标 http://127.0.0.1:3080/ · DSH 0.1.1-rc.2 · 视口 1280x720
- 约束：真实生产会话库，走查只读——不发送消息、不删除/重命名，弹窗枚举后即关闭
- 截图存 /tmp/dsh-research/shots/

## 1. 主页（空会话）
- 侧栏：新建会话 / 收起侧边栏 / 工作区分组树（外包维权工作区、ai-dev-guide、workspace、oc-beacon、未分组）/ 搜索会话输入框 / 视图选项 / 添加工作区 / 展开其余 N 个会话 / 设置
- 会话树项带相对时间戳（1小时/5小时/18小时/1天）
- 主区空态：选择工作区 chip + PTC 模式 chip + 输入框（占位“描述你想要构建的内容”）+ 底部工具行：命令 / 访问模式(Full access) / 模型(glm-5.3-flash, 推理等级 Max) / 发送（空输入禁用）
- 右侧“详情”面板：提示“点击消息流中的工具行查看详情”
- 品牌角标：“探索未至之境”+“预览版”

## 2. 斜杠命令菜单（composer 输入 "/" 触发，模糊搜索）
- 宿主命令 7 个：
  - /compact — 压缩较早的会话历史
  - /export — 导出本会话日志为 ZIP（dsh-session-log-export 插件）
  - /feedback — 记录对本会话的反馈
  - /goal — 设置/查看长任务的 goal
  - /permission — 切换权限档（sandbox 模式 + 审批策略）
  - /plan — 进入/退出 plan 模式
  - /model — 选择本会话模型
- 技能清单：~33 个用户技能（ask-matt/code-review/.../xlsx），带描述，部分标「仅用户」（仅用户级技能，非项目级）
- 交互：大小写不敏感子序列模糊匹配；Space 精确补全、Enter 执行；带图附件提交仅 input.images 声明的命令允许
- 注意：命令行与结果渲染不进会话日志（客户端呈现）；经 command.execute RPC
- 截图：02-slash-commands.png
- 环境注：IAB 内 Playwright 合成 click 持续 actionability 超时、CUA 真实事件落在 composer 会把标签页带去 about:blank（IAB guest 怪癖）；fill() 路径正常，后续输入交互一律走 fill

## 3. 访问模式（权限档）菜单
- 三档 menuitem：Read Only / Workspace Write / Full access（当前 Full access）
- 对应 /permission 命令同一能力；README 语义 = sandbox 模式 + 审批策略

## 4. 模型选择（选择模型按钮）
- 两级子菜单：模型 + 推理等级
- 模型列表按提供商分组：
  - DeepSeek 官方：DeepSeek-V4-Flash / V4-Pro / V4-Flash-Vision(Expo) / opencode-go
  - 远程 remotes：MiniMax-M3 / Qwen3.7 Max / Qwen3.7 Plus / GLM-5.1 / GLM-5.2 / glm-5.3 / glm-5.3-flash / Hy3 / Kimi K2.6 / Kimi K2.7 Code / Kimi K3 (2x usage) / MiMo V2.5 / MiMo V2.5 Pro / MiniMax-M2.7 / Qwen3.6 Plus / Grok 4.5 / zai-coding-cn
- 推理等级：menuitemradio Low / High / Max
- 截图：03-model-menu.png

## 5. Agent Preset 选择器（「PTC 模式」按钮，显示当前档名）
- 四档 menuitem：
  - 标准模式：功能完整的编码 Agent（文件编辑、Shell、文件与网页检索、Skills、计划、目标、子代理、工作流）
  - PTC 模式：标准全能力 + Code Mode SDK 呈现工具（模型用一个 TypeScript 程序组合多步操作）
  - 极简模式：仅持久 bash + str_replace_editor 双工具
  - 创造模式：创建自定义 Agent preset（运行时检查、插件实验、preset 创作指导）
- 截图：04-agent-preset.png

## 6. 会话页（对话视图）
- 头部：会话标题按钮 + Session log 按钮 + 对话/轨迹 双标签
- 消息级操作（每条消息悬停行）：复制 / **好的回答** / **有问题的回答**（message-feedback）/ **在新对话中分支**（branch/分叉）
- 工具行 = 可点文件引用（wiki/案卷/log.md/AGENTS.md…，dsh-client-ui-reference）；点击进右侧「详情」面板
- composer 工具行：命令 / 访问模式 / 模型（本会话 glm-5.3 + Max）/**上下文已用 20%**（上下文用量指示） / 发送
- 截图：06-message-actions.png

## 7. 轨迹（trajectory）视图
- 会话检查器：Duration（Use actual duration 切换）/ Turns ⊟ / Calls ⊟（折叠）
- 指标列：Input / Model / Tools
- 请求级还原：SYSTEM（Initial System Prompt）、CONTEXT（<system-reminder> 工作区指令注入）、逐 Turn 逐 Call
- 截图：07-trajectory.png

## 8. 上下文用量指示器（composer 工具行）
- 点击弹出：上下文已用 20% ~198K / 1M；分桶：系统提示词 ~9.1K / 工具 ~240 / 对话消息 ~102K
- 截图：10-context-usage.png

## 9. Session log 导出
- 点击 → 对话框「Session 导出已开始下载，浏览器正在下载 Session ZIP 文件」（dsh-session-log-export）

## 10. 会话操作菜单（侧栏会话行悬停「操作」）
- 重命名 / 分叉会话 / 归档会话
- 截图：08-session-actions.png

## 11. 工作区操作菜单
- 重命名 / 删除工作区

## 12. 视图选项（侧栏）
- 分组方式：按工作区 / 单列表
- 排序方式：手动排序 / 最近更新

## 13. 添加工作区 = 服务器端目录浏览器（directory-picker-browse）
- 主目录为根的文件夹列表（Apps/Desktop/Documents/…）
- 新建文件夹按钮 / 显示隐藏文件开关 / 编辑路径（手输路径）/ 取消 / 打开
- 截图：09-add-workspace-dirpicker.png

## 14. 设置（对话框，四标签）
- 通用设置：默认 Agent 预设（新会话生效，运行中会话保持原预设）/ 默认权限模式 / 语言（中文|English）/ 外观（浅色|深色|跟随系统）/ 繁忙时 Enter 键行为（插话|发送，Cmd/Ctrl+Enter 用另一行为）/ 打开配置文件（loopback 特权）
- 模型：提供方 CRUD——编辑（API 密钥[只写]、API 地址、模型目录[模型 ID+显示名称+容量+删除+添加]、默认模型、取消/保存）、删除、添加提供方、添加自定义提供方
- 插件：三子页——插件配置（终端/Agent 循环/网页搜索可展开编辑卡）/ 插件列表（全量只读清单）/ 提供商保活（本地 dsh-keepalive 插件）
- Agent 预设：roster（标准/PTC/极简/创造，内置标记 + 当前使用标记），每预设三操作：设为默认/查看/**复制**（复制创建自定义）；「用创造模式创作自定义预设」入口
- 截图：11-settings-models.png / 12-settings-presets.png（本次归档编号 11/12）

## 15. composer 触发器补充
- `/` 命令源：模糊菜单（已实测，见 §2）；`+` 命令启动器（源码结论）
- `@` 文件/目录/会话引用：实测 fill("@案") 触发「触发候选建议」listbox，候选需真实按键序列/加载 RPC（fill 模拟下停「正在加载…」，语义以源码走查为准：fileReferences + sessionReferenceResolver 两 Remote 服务）
- 图片附件：粘贴/拖入（imageLimits 预检）；引用 chip 对齐渲染（源码结论）

## 16. 走查环境注记（IAB guest 限制，非 DSH 缺陷）
- Playwright 合成 click 在本页持续 actionability 超时 → 一律 evaluate 直调 click
- CUA 真实事件落在 composer 区会把标签页带去 about:blank（IAB guest 怪癖）→ 输入交互用 fill()
- 大体量会话（如 18小时 github 轮询会话）打开即崩 guest（渲染进程疑似 OOM）→ 换小会话验证成功
- 截图目录：/tmp/dsh-research/shots/（01-12）
