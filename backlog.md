# OC Tether — 需求与问题总览

本文档用于记录用户在使用过程中口头反馈的问题、发现的 bug，以及计划中的功能需求。

**定位**：轻量级记录，仅忠实记录用户反馈的原始现象与需求，不做主观推测或归因分析。简单可行性确认（如文件名是否存在、接口是否暴露等）可附带，深入的代码链路调研由具体开发任务承接。

**优先级定义**：

| 等级 | 含义 | 示例 |
|------|------|------|
| **P0** | 影响主要流程体验或核心业务场景的 bug | 聊天页面崩溃、SSE 断连无法恢复 |
| **P1** | 主要业务流程的需求功能点 | 会话搜索、消息转发 |
| **P2** | 优化专项、锦上添花功能、不影响体验的小 bug | 动画微调、文案优化 |

**状态流转**：每个条目下的状态 checkbox 需全部打勾才算完结。代码写好但未验证不等于完成。

**Tag 标签体系**：每个条目需标记相关 Tag，用于关联同类问题，便于后续批量修复或按领域排查。录入时判断条目适用的已有 Tag；若现有 Tag 不足以描述，则新增。

| Tag | 说明 |
|-----|------|
| `crash` | 崩溃 / 闪退 |
| `ui` | 界面显示、组件缺失、布局问题 |
| `data` | 数据展示不准确、数据源疑问 |
| `sse` | SSE 连接、事件推送相关 |
| `session` | 会话管理相关 |
| `permission` | 权限请求、审批相关 |

---

## P0 — 主流程阻塞

### upstream-v1.7.0-sync-P0 — 上游 v1.7.0 借鉴（P0 批次，~16h）

来源：crim50n/oc-remote v1.7.0 深度分析（2026-07-30）。13 项功能均不依赖 EventReducer，可独立移植。

- [x] **#1 percent-encoding 双重 decode 修复** `crash` `ui`
  - 问题：导航路由先 `URLEncoder.encode` 又 `URLDecoder.decode`，含 `%` 的密码/路径（如 `%NR`、`%25`）导航崩溃（上游 #28）
  - 修复：删除所有 `URLDecoder.decode` 调用（Navigation 框架对 StringType 已自动解码）
  - 工时：< 1h | 难度：🟢 | 涉及：ChatNav / ServerRouteParams / NavGraph / WorkspaceNav / WebViewNav

- [x] **#2 SessionNotificationCoordinator** `ui` `session`
  - 需求：前台活跃会话不弹通知，进入即清除该会话通知（上游 #25）
  - 方案：新建 `SessionNotificationCoordinator.kt`（单例跟踪活跃 sessionId），`AppNotificationManager` 的 show* 方法包裹 `postUnlessActive`，ChatScreen 进入/离开调 activate/deactivate
  - 工时：~2h | 难度：🟢 | ~70 行（通知 ID 公式我们已有，照搬协调器即可）

- [x] **#3 Project-aware session grouping** `session` `ui`
  - 需求：session 列表按 projectId 优先分组，fallback 按 directory 最长 worktree 前缀匹配
  - 方案：实现 `buildProjectSessionGroups()` 纯函数，替换现有纯 directory groupBy
  - 工时：~4h | 难度：🟢 | ~250 行（模型已具备 projectId/directory 字段）

- [x] **#4 Markdown normalizeTaskListMarkers** `ui`
  - 需求：unicode 任务标记（☐/☑/✅）规范为 `[ ]`/`[x]`，跳过 code fence 内部
  - 方案：渲染前字符串预处理 + 回归测试（tilde 删除线/~/path/email autolink 验证）
  - 工时：~3h | 难度：🟢 | ~80 行（wide table 横滚我们已有）

- [x] **#5 PDF/多文件附件泛化** `ui` `data`
  - 需求：支持 PDF + 文本文件附件（当前仅图片）；分级大小限制（文本 2MB / 文档 10MB）
  - 方案：泛化 `MediaUtils.acceptedTypes` + `ChatAttachmentsHandler` 命名 + 加 `validateLocalAttachment` + 文档选择器（`GetMultipleContents` 配 `*/*`）
  - 工时：~4h | 难度：🟢 | ~120 行（PDF 基础我们已有，需扩展校验+UI）

---

## P1 — 核心功能需求

### upstream-v1.7.0-sync-P1 — 上游 v1.7.0 借鉴（P1 批次，~35h）

- [x] **#6 Session Categories** `session` `ui`
  - 需求：会话分类（自定义名称/颜色/图标），per-session 分配，列表筛选，Animated Busy accent
  - 方案：`SessionCategory(id,name,color,icon)` model + SettingsRepository per-server assignment + SessionCategoryPickerDialog
  - 工时：~7h | 难度：🟢 | ~450 行（纯增量，复用现有 per-server 模式）

- [x] **#7 PendingPrompt 持久化** `data` `session`
  - 需求：App 重启后恢复未确认的 prompt（当前仅内存 optimistic，重启丢失）
  - 方案：文件级 JSON 持久化（`pending_prompts.json`）+ `missingPendingPromptIds` ID 范围对账纯函数
  - 工时：~4h | 难度：🟡 | ~120 行（需理解 combine 层注入时机）

- [x] **#8 AppLoadingEdge + LocalAmoledTheme** `ui`
  - 需求：脉动加载条（active/progress 双模）+ AMOLED 状态 CompositionLocal 统一传播
  - 注意：部分与我们 Theme Token System / ButtonTokens 重叠，选择性合并避免双轨制
  - 工时：~3h | 难度：🟢 | ~75 行

- [x] **#9 directory count 可配置** `ui` `session`
  - 需求：新建会话对话框的最近目录数量可配置（5-50，默认 20，上游 #23）
  - 工时：~1h | 难度：🟢 | ~40 行

- [x] **#10 In-app GitHub 更新检查** `data` `ui`
  - 需求：应用内检查新版本 + 下载 APK + SHA-256 校验 + 签名证书比对 + 系统安装器
  - 方案：UpdateRepository 三级 fallback（富 manifest → raw manifest → GitHub API）+ UpdateModels + UpdateInstallLauncher + AboutViewModel
  - 工时：~9h | 难度：🟡 | ~600 行（需适配多 flavor：dev/beta/stable 三 applicationId）

- [x] **#11 Diagnostics 诊断屏幕** `ui` `data`
  - 需求：集中日志查看（级别筛选/搜索/隐私脱敏/导出分享/崩溃捕获/确认清空）
  - 方案：AppLogger（全局单例）+ DiagnosticLogDatabase（SQLiteOpenHelper，非 Room）+ DiagnosticLogRepository + DiagnosticsScreen
  - 工时：~11h | 难度：🟡 | ~700 行

---

## P2 — 优化与锦上添花

### upstream-v1.7.0-sync-P2 — 上游 v1.7.0 借鉴（P2 批次，~28h）

- [x] **#12 CrossServer Favorites** `session` `data` `ui`
  - 需求：跨服务器收藏会话 + 离线快照（FavoriteSessionSnapshot）+ 全局排序 + category 筛选 + 拖拽重排
  - 难点：需 per-server sessions 聚合层（EventDispatcher 当前是单服务器导向），建议在 #6 Categories + #3 grouping 完成后做
  - 工时：~18h | 难度：🔴 | ~1100 行

- [x] **#13 Terminal 状态机+手势改进** `ui` `sse`
  - 需求：TerminalTabState（Starting/Connected/Reconnecting/Disconnected/Exited）+ recoveryAction 纯函数 + resize debounce（120ms）+ 惯性滚动（VelocityTracker）+ pinch zoom
  - 方案：改造 ServerTerminalWorkspace.kt + ChatTerminalView 加 awaitEachGesture + 抽纯函数测试
  - 工时：~10h | 难度：🟡 | ~350 行
