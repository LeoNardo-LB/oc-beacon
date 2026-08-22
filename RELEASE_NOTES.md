## OC Beacon 0.3.1-dev.20 — 2026-08-22

> 版本摘要：主聊天界面入口重做——M3 官方 FAB Menu 定稿（21 轮迭代）+ 滚动到底部 FAB 回归；另含终端栈更换（Termux）、GitHub 错误上报、ModelPicker 重做与大批架构收敛。

### Added

- **FAB Menu 任务入口（本版主线）**：右下角悬浮按钮收纳排队/TODO/智能体/Shell 四入口（Material 3 官方 FloatingActionButtonMenu 组件，Secondary 配色变体 + 1dp 描边，44dp 紧凑档）；总数角标实时显示，外点/返回键收起
- **滚动到底部 FAB 回归**：底部左侧独立按钮，与菜单按钮严格同规格镜像对称；点击即时吸附回底部
- ModelPicker 二级面板重做：variant 分组折叠 + 默认开关 + 反应式星标 (#187 #188)
- subagent 运行卡片增强：运行期可跳转子会话、展开查看全量输出 (#180 #181 #182)
- 会话内提示音/振动跟随系统通知设置 (#155)
- 终端栈更换为 Termux terminal-view/emulator（本地 vendored，Apache-2.0）(#189)
- GitHub 错误上报（诊断屏）：设备码授权流程 + 指纹去重 + 报告预览（#151，需注册 GitHub App 后生效）
- 通知静默通道自检入口（验收问题 1 整改）

### Changed

- **「堆积消息」更名「排队消息」**：busy 菜单、面板、确认框、空态等 8 处文案 ×15 语言全量统一
- 排队队列状态补偿升级：空闲且非空即发送（原边沿触发）(#176 #177)
- 架构收敛（22 项，开发者视角详见 changelog）：ChatViewModel 集群化 (#173)、版本泄漏收编为能力位 (#172)、uiState 退役、连接/渲染协调器外移 (#169 #170)、未读服务单源化等

### Fixed

- **滚动到底部 FAB 点击无效**：原事件路径死等新消息增长 5s 才滚（日志实锤），改为即时吸附
- 双 FAB 规格不一致（48 vs 44dp）：普通 FAB 内部强制 48dp 最小触达所致，已 provision 关闭对齐
- busy 气泡不再抢焦点，键盘全程保持；turn 分割线留空减半 (#178 #183)
- 分享诊断报告崩溃：FileProvider 路径声明补 diagnostics 目录，真机验证零崩溃
- WebView 图片 404 噪声移出错误队列；SSE 重连风暴日志降级（网络中断非程序错误）


---
完整变更记录：[Full Changelog](https://github.com/LeoNardo-LB/oc-beacon/compare/v0.3.1-dev.19...v0.3.1-dev.20)
