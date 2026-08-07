# 架构文档 — OC Beacon

> 本文件是架构知识的详细参考，由 AGENTS.md 索引。改动架构时更新此文档；AGENTS.md 中的架构概览保持精简，不在此重复。

## 分层与依赖方向

Clean Architecture, 3 layers. **Dependency direction: UI → Domain ← Data.**

```
domain/          Pure Kotlin, 无 Android 依赖
  model/         40+ 数据类与值类型（SseEvent, Message, Part, Session, AppSettings, SessionCategory, FavoriteSessionSnapshot 等）
  repository/    14 个接口（Agent, Chat, Draft, File, Mcp, Provider, Server, ServerConfig, ServerConnection, Session, SessionState, Settings, Terminal, Vcs）
  usecase/       25 个 UseCase — ViewModel 调用它们，而非直接调 API

data/            Android 相关实现
  api/           ApiClient.kt + 按域拆分的 SessionApi/MessageApi/FileApi/TerminalApi/ProviderApi/SystemApi (Ktor HTTP), SseClient.kt
  dto/           API 数据传输对象（request/ response/ common/）
  mapper/        DTO ↔ 领域模型转换器
  repository/    Impl 类 + EventDispatcher + EventHandler 策略模式
    handler/     10 个事件处理器（Session, Message×4, SessionNext, Permission, Question, Misc）
                 + DiagnosticLogDatabase/Repository (SQLite, 自动清理, 隐私脱敏)
                 + PendingPromptRepository（基于文件的 JSON, 乐观消息持久化）
                 + CrossServerSessionsAggregator（基于 REST 的按服务器会话聚合）
  update/        应用内 GitHub Release 更新检查（UpdateRepository, 3 级回退）

logging/         AppLogger — 全局持久化日志（Channel→SQLite, 崩溃捕获, 脱敏）

service/         Android 前台服务
  OpenCodeConnectionService.kt  服务生命周期 + WakeLock
  SseConnectionManager.kt       连接/重连（指数退避）
  AppNotificationManager.kt     通知渠道与事件通知
  SessionNotificationCoordinator.kt  抑制当前活跃会话的通知

ui/
  theme/              设计令牌系统（详见 docs/ui-conventions.md）
  screens/chat/      ChatScreen（核心聊天 UI）+ 7 个子包
    components/      聊天 UI 组件
    dialog/          图片预览、markdown 预览对话框
    input/           消息输入栏
    markdown/        Markdown 渲染
    terminal/        WebSocket 上的 PTY 终端视图（TerminalTabState: 5 态枚举，非 Boolean）
    tools/           工具调用可展开卡片
    util/            聊天专用工具
  screens/home/      HomeScreen + 服务器卡片
  screens/sessions/  SessionListScreen + CrossServerSessionsScreen + 组件
  screens/settings/  SettingsScreen + 选择器对话框 + DiagnosticsScreen
  screens/server/    服务器设置/提供商/模型过滤
  screens/about/     关于页面
  screens/webview/   WebView 回退（OAuth, HTML 错误）
  navigation/        NavGraph.kt + routes/ 中的 11 个类型安全 Route 对象（URL 参数用 NavUtils.safeDecodeParam）
  components/        共享组件（PulsingDotsIndicator, ProviderIcon）

di/                Hilt 模块（NetworkModule, DomainModule）
```

## 关键模式

- ViewModel 委托给 UseCase；UseCase 目前壳式委托给 OpenCodeApi。
- Repository 实现桥接 EventDispatcher（状态）+ API（网络）。
- DI 使用 **KSP**（非 kapt）处理 Hilt 注解。
- 终端用 WebSocket 传输 PTY 流；事件走 SSE。

## 承重架构规则（违反会引入回归，勿破坏）

### SessionStateService 是会话状态与流式活动的单一真相源

`SessionStateService`（idle/busy/retry + Waiting/Streaming/ToolCalling）驱动所有 UI 的 `statusFlow`/`activityFlow`；所有状态写入都经过其纯函数 FSM（`SessionStateFSM`），含穷举转移矩阵 + 自驱动 staleness/REST 恢复循环。**不要重新引入按 handler 维护的状态**——`SessionStatusManager` 和 `SessionEventHandler._sessionStatuses` 正是为此被移除。设计缘由见 `docs/research/session-status-sync-investigation.md`。

### AppLogger 是持久化日志入口

新代码应使用 `AppLogger.i/w/e` 而非 `android.util.Log`，这样日志会出现在应用内 Diagnostics 屏幕。**存量代码已全部迁移**（2026-08-07，61 个文件批量替换完成）。

### 导航参数必须安全解码

必须使用 `NavUtils.safeDecodeParam()`（不要用裸 `URLDecoder.decode()`）——裸解码遇到畸形 `%` 序列（如密码中的 `%NR`）会崩溃。
