## OC Beacon 0.3.0-beta.8 — 2026-08-14

> 提问卡片全面重构：嵌入对话气泡、问题域/答案域/按钮域新架构、单选互斥修复；会话状态体系完善（提问中 Asking 状态）；修复返回列表会话消失与状态闪烁 bug；WebView 泄漏与图片解码 P0 修复。

### Added

- 提问卡片重构：嵌入 agent 回复气泡（思考卡片之后），问题域（SINGLE/MULTI 徽标 + Q1|Q2 分段切换）→ 答案域（选项边框行 + 选中高亮 + 自定义输入三态）→ 按钮域
- 会话"提问中"（Asking）状态：列表状态标签 + 图标高亮
- 提问/权限等待时输入框自动禁用
- 任务面板改名 Background → Tasks（15 语言）+ V1 下隐藏 Running/History
- V1 连接显示 API 版本徽章

### Fixed

- 返回会话列表后 item 消失（#89 过度清理误删会话元数据）
- 提问状态先清除后恢复的闪烁（退出会话不再清理服务器 pending 状态）
- 单选题目可多选 bug（按题目 multiple 判断互斥）
- question 工具卡片与提问卡片重复展示（工具名分流根因修复）
- V1 长会话 JsonConvertException（listMessages 非 2xx 空页）+ 回复重复渲染
- 目录浏览性能（IO 线程 + 30s 缓存 + .opencode ANR 消除）
- 内存泄漏全量清理（Singleton keyed 状态 + 目录缓存上限 + WebView 销毁 + 图片降采样）
- V1 误判 V2 崩溃（版本交叉验证 + HTML 防御）
- 会话目录浏览 V2 兼容（name/absolute/初始路径/空目录过滤）

---
完整变更记录：[Full Changelog](https://github.com/LeoNardo-LB/oc-beacon/compare/v0.3.0-beta.7...v0.3.0-beta.8)
