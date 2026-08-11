# RG — V2 契约对齐 + 后台系统 回归走查报告

- 日期：2026-08-11
- 环境：模拟器 emulator-5554 + 真实 V2 服务器（10.0.2.2:4199，next-17135）
- 变更分类：状态机/数据流变更（SSE 事件处理/FSM/V2 契约）+ UI/渲染变更（后台系统）→ **完整回归**
- 被测 commit：`fix(v2): step.ended cost 双格式兼容`（含此前 10+ 个相关 commit）
- 计划：docs/superpowers/plans/2026-08-11-v2-contract-alignment.md（8 Task 全完成）

## 结论

**通过**（12 能力域相关项全部符合预期；1 项非阻塞登记 backlog #67-70）

- D0 编译+静态 ✅ / D1 全量单测 ✅（1444+ tests）/ D2 模拟器走查 ✅ / D4 部分项需用户验收（后台工具栏动画）
- 变更核心（V2SseMapper 消息链路 + FSM execution 驱动）E2E 实测通过：消息即时显示、流式渲染、状态流转

## 走查结果表

| 域 | 验证点 | 结果 | 关键证据 |
|----|--------|------|----------|
| §3.1 启动 | 冷启动多次；崩溃缓冲区 | ✅ 0 行 | `logcat -b crash -d` 空 |
| §3.2 连接 | Connect/断开重连；SSE 流式 | ✅ Connected；事件流正常 | UI "Connected"；SseClientV2 事件日志 |
| §3.3 会话列表 | 列表显示；进入会话；Working 标记 | ✅ 正确 | 多会话标题/目录/时间 |
| §3.4 发送流 | 输入/发送/即时显示/流式回复 | ✅ **核心修复验证** | 用户消息 3s 内出现；AI 表格回复完整 |
| §3.5 控制 | 停止生成 | ✅ Stop→Send 切换正常 | UI dump rg_3/rg_4 |
| §3.7 工具卡片 | 工具调用卡片（Skill/Sub-agent） | ✅ 卡片出现+跳转 | t10 系列截图 |
| §3.10 状态 | 状态流转（execution 驱动） | ✅ execution.started→Busy 解析正确 | logcat session.execution 事件 |
| §3.11 i18n | key 一致性 | ✅ 15 key × 14 语言 | python 校验 0 缺失 |
| 新增域 | 后台系统（入口/面板/工具栏/Shell 卡片） | ✅ 全部 PASS | bg_* 截图 + Subagents (1) + 工具栏出现 |
| 新增域 | parse error 清零 | ✅ 0（修复 cost 双格式后） | logcat 统计 |

## 发现的异常/注意事项

| 项 | 现象 | 定性 | 处置 |
|----|------|------|------|
| 1 | synthetic 消息被过滤（后台完成通知不可见） | 设计缺口（非本次引入） | backlog #67 |
| 2 | 新会话 get/pending 404（列表有） | V2 服务器怪癖 | backlog #68 |
| 3 | session.instructions.updated parse error（1 次） | 低频未处理事件 | backlog #69 |
| 4 | 设计文档 §7 未确认项 | 调研遗留 | backlog #70（4-7 已闭环） |

## 验证维度小结

- **D0**：compileDevDebugKotlin ✅ · testDevDebugUnitTest 全量 ✅ · compileDevDebugAndroidTestKotlin ✅（FakeChatRepository 补齐）· i18n ✅
- **D1**：全量 `--rerun` 多轮 ✅（含新增 V2SseMapperTest 9 / V2EventParserTest 6 / V2 事件链集成测试）
- **D2**：模拟器走查——消息链路/流式/停止/工具卡片/后台面板/状态流转 ✅
- **D3**：非性能优化变更；渲染变更轻量观察无异常（无 parse error 风暴）
- **D4**：后台工具栏 AnimatedVisibility 动画、SSE 流式节奏——**待用户人工验收**

## 证据清单

- 模拟器截图：/tmp/opencode/bg_*.png（后台系统）、t10_*.png（消息链路）、t8_stream.png、rg_*.xml（UI dump）
- logcat：parse error 统计（修复后 0）、session.execution/step 事件流

## 待用户验收项（D4）

1. 后台入口按钮角标动画与点击反馈
2. 转后台工具栏滑出/消失动画（fade + expand）
3. 后台面板 ModalBottomSheet 拖拽/滚动手感
4. SSE 流式回复节奏（无闪烁/卡顿/跳底）
