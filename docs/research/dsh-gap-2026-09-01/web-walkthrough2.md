# 第二轮 web 走查记录（主 agent 执行；subagent 委派因 Browser Use 仅主 agent 可用而失败）

- 时间：2026-09-02 · DSH 0.1.1-rc.2 · 期间 dsh-web.service 曾被停止，已按原 unit 重启
- 截图：shots2-real/13-17 · shots2-fixture/18-26

## A. 真实端（只读）
1. **任务0 反馈清理**：6 个 好的/有问题的回答 按钮全部 aria-pressed=false——第一轮点击未留下已记录反馈（官方语义：记录后按钮呈激活态）。无需清理。
2. **@ 引用**：裸 `@` 停「正在加载…」（fileReferences/sessionReferenceResolver 在 410 会话真实工作区上疑似慢/挂）；`@"` 引号形态（仅搜文件）**正常返回 16 项**（docs/ inbox/ log-archive/ output/ raw/ templates/ tools/ wiki/ 案卷/ + AGENTS.md CONTEXT.md 等）。→ 前端链路健康，真实端是部署性能/数据量问题。截图13。
3. **侧栏搜索降级**：标题即时匹配 2 条 → 250ms 防抖后出现警告条「**内容搜索暂不可用，仅显示名称匹配。**」（session.search openAt=never 的退化文案实锤）。
4. **会话全量名单**：外包维权 10 会话（含 案卷目录放入raw目录是否合适/违法解除后社保未缴问题/识别带批注的docx文件/余杭劳动仲裁资料与流程咨询/语音转录易错术语检索）+ 3 分组 + 未分组。
5. **谱系下拉**（案卷目录会话头部「11 个子代理」，hover 150ms 开启）：行=状态点+标题+工作区路径/任务类型+标签（#红队对抗测试 · 第七轮 · 角色 · 可继续）。8 行可见。截图14。
6. **聊天视图「加载更早」按钮**：长会话聊天侧翻页入口（轨迹侧虚拟滚动之外的第三个翻页面）。
7. **TodoDock 疑似**（公积金/识别docx 会话文本含计数模式，未 DOM 确证）；queue/goal/plan chip 各会话均无（符合预期）。
8. **轮尾统计条全文**：「5 轮 · 40 步| LLM 30m19s · 工具调用 42.2s| 首 token 平均 3.7s · 58 tok/s| 缓存命中 91%| 输入 5M tok · 输出 96.9K tok」；消息尾 meta「8月31日 21:36 · 用时 5分31秒 · 首 token 9.1秒 · 50 tok/s」。
9. **新会话 hero**：工作区 chip + 预设 chip（PTC 模式）并排，textarea 可编辑（非只读——有已选工作区时）。截图17。
10. 轨迹时间轴拖选：无可见选中效果（弱/未触发）；轨迹台账 TOOL/SUBTOOL 行 + error 红色结果确认。
11. 未测：jobs popover（安全会话无 jobs 徽标；github 会话 OOM 禁入）、悬停卡（hover 时效未捕获）。

## B. fixture 沙盒（?fixture=empty / ?fixture）
1. **空世界**：侧栏「暂无会话」；hero=选择工作区+预设 chip（标准模式默认）；**onboarding「内测声明」对话框**弹出（继续按钮；fixture 内持久化确认状态失败报错「暂时无法保存确认状态」）。
2. **提问 composer 接管全流程**：
   - 收起问题卡片 / 放弃整组问题 双控制
   - Q1 单选（radio + **推荐**徽章 + 自定义答案 textarea）→ **点选即前进**
   - Q3 多选（checkbox：系统设计/代码质量/Agent 产品判断 + 辅助文案「跳过则视为不设偏好」+ 提交）
   - 进度「1 / 3」+ 上一题/下一题/跳过本题；提交后面板关闭、composer 复位「给智能体发消息」
3. **审批面板**：「等待审批 fixture 常驻审批（可答：批准/拒绝后消失）拒绝 / **允许一次**」（无 always）→ 点允许后面板消解。
4. **TodoDock 计数头**：「任务 1 已完成 · 2 进行中 · 1 待处理」。
5. **max-tokens 截断卡**：「已达到输出 token 上限回答被截断，已有输出保留在对话中。发送“继续”可让模型接着输出。」
6. **工具卡**：
   - 折叠行 = 图标+工具名+参数摘要；Bash 行红点失败态
   - 终端卡展开：命令行 + **ANSI 彩色输出**（✓/✗ 计时、红色 FAIL、覆盖率表格 NAME/LINES/BRANCHES/FUNCTIONS/UNCOVERED）
   - Read 卡：下划线可点文件路径；Search/web 卡（deepseek harness architecture）；Grep/Glob 行
7. **@ 菜单全貌**（fixture 小数据集上完全可用）：文件与文件夹 组（notes/ README.md demo.txt 带路径副标题）+ **Session 对话 组**（fx-beta/fx-gamma/fx-1，副标题=id · /tmp/fixture · ISO 时间戳）→ 真实端「正在加载」属部署性能问题而非前端缺口。
8. **发送消息**：agent 忙碌时消息入**排队 dock**（todo 条下方排队项）+ 停止按钮现身。
9. **状态点**：橙=等待、绿=进行中、虚线=已停/排队；侧栏会话时间随活动实时刷新。
10. **权限/模型 chip 会话级**：fixture 会话显示 Workspace Write + DeepSeek-V4-Flash **High**（非部署默认 Full access/Max——会话级状态）。
11. Think 行：「Think 思考过程 N：…」折叠行存在（展开交互未确证）。
12. **详情面板：无入口实锤**（fixture 同样无法打开；README「openDetails 未接线」成立）。
13. **chunk storm 非 window 全局**（startReasoningChunkStorm typeof undefined）——仅 DevTools 断点可达，控制台入口不存在。
14. 轨迹台账：Turn 分组 + USER/ASSISTANT/TOOL 彩色 chip + 工具行「{url:…} → 结果」箭头；时间轴拖选/缩放效果仍未可视化确认。
