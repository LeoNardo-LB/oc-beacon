## OC Beacon 0.3.0-beta.9 — 2026-08-14

> 版本摘要：（待填写——本版主题一句话）

### Added

- #132 调试通道——预置套餐一键直达会话列表 + am start extra 启动直达
- #129 方案 C——转圈指示器可点击立即中断（不等 3 分钟僵尸兜底）

### Changed

- #132 移除内置套餐——仅保留 adb 完整参数方式 + 版本探测修复（UNKNOWN 保留 apiVersion 防 V2 降级 V1）+ 文档
- 僵尸误杀防护改为结构化 if/else——去除 return@onSuccess 非局部跳出

### Fixed

- E8-1 快速连发输入清空竞态——发送成功仅当输入框仍是已发送文本时才清空（防静默丢失新输入）
- #131 V1 question 卡片嵌入渲染失败——tool part 消息不渲染待处理问题卡片  根因（模拟器 V1 实测完整证据链）： - pendingQuestions 非空（4 题，输入框 enabled=false 佐证） - embeddedQuestionByMsgId 按 tool.messageId 匹配成功（msg_fff32fe2d001 存在）   → question 被算作已嵌入 → unembeddedQuestions 排除它（独立卡片不显示） - 但嵌入渲染条件 MessageCardAssistant:243 part is Part.Reasoning 不满足——   V1 question 工具调用消息是 Part.Tool（非 Reasoning）→ 卡片不渲染 - 结果：问题卡片凭空消失 + 输入框禁用（UI 卡死，用户无法回答问题）  修复：嵌入条件放宽为 Reasoning 或 Tool（question/permission 工具调用消息） 都渲染待处理问题卡片。  验证：模拟器 V1 实测——4 题卡片（1/4 分页 Alpha/Beta + Enter answer + Dismiss/Next/Submit）正常渲染；V1 会话列表显示 Pending answer 徽标
- 走查发现的三处修复——僵尸 interrupt 误杀防护 + 草稿静默丢失 + 计数器原子性
- V2 switchModel 契约修复 + 僵尸会话主动 interrupt 解除 + 关键节点 debug 日志
- #128 beta 真机 CompletionHandlerException——runCatching 吞取消根因修复
- 会话状态卡'进行中'——僵尸 Busy 兜底（服务器 drain 不释放）
- fling 快速滑动跳过 agent 长气泡——预组合 1 项 → 6 项
- 问题模块审计 3 bug（#125 多选自定义取消 + #126 远页草稿 + #127 越界保护）
- 统计栏模型展示恢复 + token 占比圆环（V2 契约适配）
- 用户消息气泡根治——prompt 响应体即 Inbox 条目，发送后立即本地播种
- #124 退出会话列表状态闪烁——releaseSessionData 不再清 FSM
- #123 synthetic 缓存分支适配 inbox 事件契约（随 V2SseMapper 同步）
- #123 V2 用户消息不显示——session.inbox.enqueued 契约适配
- #111 dataSync 前台服务 6h 时限——onTimeout 覆盖自动重启
- #108 SSE 心跳——阻塞读 40s 超时防护 + V1 心跳对齐 + EOFException 捕获


---
完整变更记录：[Full Changelog](https://github.com/LeoNardo-LB/oc-beacon/compare/v0.3.0-beta.8...v0.3.0-beta.9)