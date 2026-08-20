# 2026-08-06 Play 上架合规批次
> 状态：全部完结
> 迁移：2026-08-20 自 backlog.md 原文迁入（spec/journal 分离批次；原文逐字保留，未压缩）

来源：Google Play 上架审计（2026-08-06），目标 2026-08-31 政策截止。

- [x] **#1 targetSdk 36 升级** `security`
  - 问题：targetSdk=35，Play 2026-08-31 起新应用必须 target Android 16 (API 36)，不达标无法上架
  - 修复：targetSdk 35 → 36（compileSdk 37 已就绪），全量编译验证
  - 工时：~1h | 难度：低 | 涉及：app/build.gradle.kts

- [x] **#2 权限清单清理** `security` `permission`
  - 问题：Termux RUN_COMMAND（代码零使用）、WRITE_EXTERNAL_STORAGE（遗留）会被 Play 审核质询；REQUEST_IGNORE_BATTERY_OPTIMIZATIONS 属高风险权限（IM 类默认不允许）
  - 修复：移除死权限；电池优化改为引导到系统设置页（ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS + 回退应用详情页），SSE 保活能力保留
  - 工时：~2h | 难度：低 | 涉及：AndroidManifest.xml / HomeScreen.kt

- [x] **#3 应用内自更新 flavor 区分** `security` `data`
  - 问题：Play 政策明确禁止 REQUEST_INSTALL_PACKAGES 做应用自更新；现有 UpdateRepository 从 GitHub 下载 APK 安装
  - 修复：stable（Play）禁用自更新（UI 隐藏 + Repository 守卫 + Manifest overlay 移除权限）；dev/beta（GitHub 分发）保留
  - 工时：~2h | 难度：中 | 涉及：build.gradle.kts / src/stable/AndroidManifest.xml（新）/ AboutScreen / UpdateRepository

- [x] **#4 密码 Keystore 加密** `security` `data`
  - 问题：服务器密码明文存 DataStore，且随系统云备份上传（backup_rules 为空 = 全量备份）
  - 修复：新建 SecretCipher（AES/GCM + AndroidKeyStore）加解密落盘；备份规则排除 datastore/ 目录（云备份 + 设备迁移）
  - 工时：~3h | 难度：中 | 涉及：SecretCipher.kt（新）/ ServerDataStore / backup_rules / data_extraction_rules

- [x] **#5 导航 URLDecoder 崩溃风险** `crash`
  - 问题：13 处裸 `URLDecoder.decode`，密码含畸形 `%` 序列（如 `%NR`）抛 IllegalArgumentException 崩溃
  - 修复：全部改用 `NavUtils.safeDecodeParam`（畸形序列回退原值）
  - 工时：~1h | 难度：低 | 涉及：ChatViewModel / SessionListViewModel / WorkspaceViewModel / SessionLifecycleDelegate

- [x] **#6 selectModel 空 patch 隐患** `data`
  - 问题：ChatRepository.selectModel 零调用死代码，实现传 `ServerConfigPatch()` 空对象——PATCH /config 是全量替换语义，任何调用方接入即清空服务器 model 配置
  - 修复：删除接口 + 实现 + 相关测试/Fake
  - 工时：~1h | 难度：低 | 涉及：ChatRepository / ChatRepositoryImpl / ChatRepositoryTest

- [x] **#7 终端工作区内存泄漏** `data`
  - 问题：ServerTerminalRegistry.byServer 只 getOrPut 无 remove，每次连接不同服务器泄漏一个终端工作区（模拟器 + 协程作用域永不释放）
  - 修复：新增 removeWorkspace/removeAllWorkspaces + Workspace.dispose()（closeAll + scope.cancel），Service 断开时自动调用
  - 工时：~2h | 难度：中 | 涉及：ServerTerminalRegistry / ServerTerminalWorkspace / OpenCodeConnectionService

- [x] **#8 密码导航参数重构** `security`
  - 问题：密码明文经 query 参数在 7 个路由间传递（ServerRouteParams 5 参），暴露于日志/深链/进程重建回放；与 #4 本地加密闭环矛盾
  - 方案：导航只传 serverId；各 ViewModel 从 ServerDataStore 按 id 取 ServerConfig（suspend，需初始 loading）；WebViewScreen Basic Auth 同改
  - 工时：~2-3d | 难度：高 | 涉及：ServerRouteParams / NavGraph（25+ 处）/ Chat·SessionList·Workspace·WebView·ServerSettings ViewModel
  - **2026-08-07 完成**：路由层+消费层+入口源头全部 serverId-only（commit 681bf0fb / 2326e8b5）；编译 ✅ 全量单测 ✅（52s）凭据 grep 0 引用 ✅；模拟器验收通过（9/9 步：会话/聊天/SSE/终端/通知深链/WebView Basic Auth 全正常，logcat 0 异常，凭据零泄露）；遗留 runBlocking 债务已登记 architecture-debt §6

- [x] **#123 V2 用户消息不立即显示（session.inbox.enqueued 契约适配）** `sse` `data`
  - 问题：2026-08-14 用户反馈"发送消息后不会立刻显示，重进会话才显示；Agent 回复能立刻看到"——根因：新版 opencode（next-17403+）把 `session.input.admitted/promoted` 改为 **`session.inbox.enqueued/delivered`**（事件名 + payload 结构全变）；App V2SseMapper 只处理旧事件名 → 用户消息播种失败（悲观消息设计：无本地占位，完全依赖 SSE 回显）→ 消息只等重进会话 REST 拉取才显示。Agent 回复走 step/text 事件不受影响（所以回复正常）
  - 修复（2026-08-14，curl 抓帧实证）：
    1. V2SseMapper 新增 `session.inbox.enqueued` 分支：{sessionID, inboxID, item:{type, payload:{text,agents}, delivery}}，兼容过渡契约 {id, prompt} 与旧契约 {inputID, input}
    2. SseClientV2.handleEvent synthetic 缓存同步适配（inbox.enqueued 缓存 inboxID→item / inbox.delivered 消费；保留旧事件名分支）
    3. +4 单测（inbox 播种 / 缺失 id 防御 / 过渡契约 / 旧契约保留）→ 1587 全通过
  - 验证：模拟器实测——发送 E2E_final_verify_ok → logcat `admitted: inputID=msg_... type=user`（无 unhandled）→ **UI 用户消息立即显示 ✅**
  - 工时：~1h | 难度：中 | 涉及：V2SseMapper/SseClientV2 | 优先级：P0（主流程）

- [x] **#124 退出会话后列表状态闪烁（releaseSessionData 误清 FSM 状态）** `ui` `session`
  - 问题：2026-08-14 用户反馈"从会话退到列表，退出会话的状态突然变没、突然又恢复在输出中，不连续"——根因：#89 修复引入的 releaseSessionData 在 ChatViewModel.onCleared（退出会话）时调用 `sessionStateService.clearSession(sessionId)` 清除 FSM 状态；但服务器仍在流式（SSE 全局连接持续投递 execution.started 等）→ 状态先清（列表显示无状态）→ 事件恢复（又显示 Working）→ 闪烁
  - 修复（2026-08-14）：releaseSessionData **移除 clearSession**——FSM busy/streaming 是服务器状态镜像（与 permission/question 处理哲学一致：服务器状态退出不清理）；内存由 24h staleness 自动清扫兜底（STATE_RETENTION_MS，非 Busy 会话超时移除）
  - 验证：模拟器实测——退出"opencode版本识别"会话（服务器流式中）→ 列表持续显示 Working（多次 dump 一致无闪烁）✅
  - 工时：~10min | 难度：低 | 涉及：EventDispatcher.releaseSessionData | 优先级：P0（视觉回归）

- [x] **#128 beta 真机崩溃：CompletionHandlerException（协程取消回调内抛异常）** `crash`
  - 问题：2026-08-14 用户真机（OnePlus PLK110, Android 16）beta 0.3.0-beta.8 崩溃——`kotlinx.coroutines.CompletionHandlerException: Exception in completion handler InvokeOnCancelling@c520a61 for StandaloneCoroutine{Cancelling}`（主线程）；栈特征：`StateFlowImpl.collect → dropWhile → takeWhile → SafeCollector.emit`——协程取消回调链里执行 flow emit → 触发下游 cancel → 嵌套 handler 异常；R8 混淆（ci1/yh1/j20/mz）无法直接定位源码
  - 完整日志：docs/research/crash-2026-08-14-completion-handler-beta.md
  - 初步方向（低置信）：① 全库搜 invokeOnCompletion/invokeOnCancelling 回调内做 emit/UI 操作；② 会话退出/切换的取消链（#124 onCleared 清理相关）；③ MessageEventHandler batchScope/persistQueue（#57 actor）；④ 用 betaRelease mapping 反混淆
  - 工时：待定位 | 难度：中-高 | 涉及：协程取消链 | 优先级：P1（真机崩溃，beta 用户受影响）
  - **2026-08-14 根因定位与修复完成（mapping 反混淆实证，commit ab20e24f）**：根因 = 数据层 runCatching 吞 CancellationException（Kotlin 已知陷阱）——HomeViewModel 主线程 collect 回调取消 loadProviders job 时协程不响应取消继续执行，完成处理链与取消状态竞争 → handler 抛异常 → 主线程 CompletionHandlerException。修复：① 新增 util/RunCatchingCancellable.kt（CancellationException 重抛，取消必须传播）② 数据层全量迁移 94 处/12 文件（Server/Session/Chat/File/Agent/Mcp/Vcs/DiagnosticLog/Settings/UnreadBadge/MessageStore）③ HomeViewModel catch(CancellationException) 前置 rethrow；RunCatchingCancellableTest +2 用例 → 1596 全通过；真机流式退出/切换压力测试 CompletionHandler 零出现（runbook 轮次10，commit 218754d7）；完整归档：docs/research/crash-2026-08-14-completion-handler-beta.md
