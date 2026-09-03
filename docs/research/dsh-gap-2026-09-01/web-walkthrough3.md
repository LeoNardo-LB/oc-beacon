# 第三轮 web 走查记录（补 §10.3 欠账，2026-09-02）

- 延续 fixture 标签页（iab-tab:03c9ac6b）+ 真实端标签页仍在。截图 shots2-fixture/27。

## 实测新证据
1. **Web 忙碌态发送语义**：忙碌时 composer 圆钮**整体变为「停止生成」**（无 Android 式停止+发送双键并存）；排队靠 Enter（fe 文档），按钮路径不可排队。空闲后按钮恢复「发送消息」。
2. **fixture 空闲发送**：停止生成 → 发送键回归 → 点击发送成功（消息入转录、草稿清空、demo agent 开跑并弹新问题——fixture 是交互式面试循环，问答循环持续）。
3. **轨迹 Turns/Calls 折叠实锤**：
   - Calls ⊟：工具行折叠为「… 1 tool call · echo」计数摘要
   - Turns ⊟：每轮折叠为「Turn 53 #53 USER … … 0 steps · 0 tool calls」步数/工具数摘要
4. **斜杠菜单技能源（fixture）**：命令组（compact[fixture 描述]/echo[fixture 专有]/goal/permission/plan/model）+ 技能组（fixture-demo；**fixture-user-only 带「仅用户 ·」前缀**）——「仅用户」标记上屏确认；命令目录确为 agent 作用域（fixture 与真实端 roster 不同）。
5. **设置语言切换全链生效（fixture）**：中文→English 对话框全量换文（Settings/General/Models/Plugins/Agent presets/Open configuration file/Appearance/Light/Dark/System/Enter behavior while busy）、`<html lang>=en`、侧栏同步；成功切回。fixture 默认：标准模式/浅色/Enter=排队发送（与真实端 PTC/Full access 不同——均会话/宿主级偏好）。
6. **模型菜单（fixture）**：模型带副标题（DeepSeek-V4-Flash 快速响应 / V4-Pro 复杂任务 / GPT-5）；effort 子菜单对 DeepSeek 系存在（当前 High）。
7. **演示循环行为**：fixture demo agent 对排队/发送的消息持续以新问题组回应（偏好→工作方式→信号 三题循环），会话状态点在 等待回答/已完成/进行中 间流转。

## 仍未验证（最终清单）
- 图片 lightbox 点击（fixture 图为占位色块且随流滚动，两次定点未命中）
- 消息反馈 portaled 备注编辑器（👍 点击执行但激活态未现身——疑提问接管层拦截；按钮激活/撤回语义以文档为准）
- 悬停卡、轨迹时间轴拖选/缩放可视效果、jobs popover（无 jobs 会话可安全触发）、Think 展开（单行内容无法区分折叠/展开态）
