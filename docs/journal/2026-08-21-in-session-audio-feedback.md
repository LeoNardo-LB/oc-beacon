# 2026-08-21 会话内提示音批次
> 状态：部分完结（活跃 #155）
> 迁移：2026-08-20 自 backlog.md 原文迁入（spec/journal 分离批次；原文逐字保留，未压缩）
> 关联：spec: docs/specs/2026-08-21-in-session-audio-feedback-design.md

来源：grilling 会话共识（Q1–Q12 + F1–F5 全定案）。**设计 spec：`docs/specs/2026-08-21-in-session-audio-feedback-design.md`（实现前必读，含调研结论/静音矩阵/挂载点/测试缝）**。

- [ ] **#155 会话内提示音：被抑制的系统通知转为提示音+震动，严格镜像系统通知策略** `ui` `sse`
  - 需求：处于本会话（前台+焦点匹配）时，turn 结束/权限/问题/错误事件不发系统通知（现状已实现）但**零反馈**——补提示音+震动，策略完全镜像系统通知（渠道配置/铃声档/DND/app 开关四层，见 spec §6 静音矩阵）；错误 streak 只响第一声（成功完成 turn 或用户发新消息重置）；零新增设置项
  - 实现：新组件 InSessionFeedbackPlayer（独立去重 map，与通知侧物理隔离防"响过一声→离场补发通知被吞"）；策略镜像管线纯函数化（SoundPlan）；挂载抑制分支内部（SSE+REST 兜底全覆盖）· 测试缝：SoundPlan 矩阵单测 + streak 状态机单测 + 仿 AppNotificationDedupTest
  - 附带行为变更：SessionError 通知侧同步加 streak 去重（现状连续错误每次都弹，R4）；Manifest 补 VIBRATE 权限
  - 验证注意：模拟器无实际音频输出，维度 5 必须真机（houji）实测听声/震感/静音档/震动档/DND/自定义铃声
