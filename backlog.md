# Backlog

> 待办事项登记。条目按 bug / 新特性的完整度记录：前因后果、解决方案、预期效果。
> 接手的人/AI 应能理解"问题从哪来、为什么这么改、改完是什么样"。
> 完成一项删除一项（或移入"已完成"区）；新发现的问题按此格式追加。

---

# ✅ 已完成（2026-08-06）

## A1. Google Play 上架合规准备

**类型**：合规/安全 ｜ **状态**：✅ 已完成

**前因**：计划上架 Google Play（2026-08 审计发现多项硬性不合规：targetSdk 不达标、自更新权限违反政策、密码明文备份、敏感权限冗余）。

**方案与效果**：
- **targetSdk 35 → 36**：满足 Play 2026-08-31 起新应用必须 target Android 16 的政策
- **权限清理**：移除 Termux 死权限（代码零使用）、WRITE_EXTERNAL_STORAGE 遗留声明；`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` 改引导式（ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS + 回退应用详情页），SSE 保活能力保留但零审核风险
- **自更新 flavor 区分**：Play（stable）禁用应用内自更新 + Manifest overlay 移除 `REQUEST_INSTALL_PACKAGES`（政策禁止自更新）；GitHub 分发（dev/beta）保留。`BuildConfig.ENABLE_AUTO_UPDATE` 三层守卫（UI 隐藏 + Repository 入口 + Manifest）
- **备份规则排除 DataStore**：密码不再随系统云备份/设备迁移上传（此前 `backup_rules.xml` 为空 = 全量备份）
- **密码 Keystore 加密**：新建 `SecretCipher`（AES/GCM + AndroidKeyStore），`ServerDataStore` 读写时加密/解密，旧明文数据透明兼容
- **AAB 构建流程**：`bundleStableRelease` 验证通过（13.6MB），release-workflow.md 新增 §5.5
- **隐私政策**：`docs/PRIVACY_POLICY.md`（中英双语，可直托 GitHub Pages）
- **版本体系重置**：VERSION_NAME 1.2.0 → **0.2.0**、VERSION_CODE 18 → **1**（未正式发版不配 1.x；用户接受卸载重装；AGENTS.md/release-workflow 同步更新）

**验证**：stable/dev 合并 Manifest 权限矩阵确认、AAB 构建成功、编译 + 1191 单测全绿。

---

## A2. 死代码清理（三批）

**类型**：清理 ｜ **状态**：✅ 已完成

**前因**：项目从上游 fork 后经 1400+ 提交演进，早期"Phase 4"规划留下的占位实现与已废弃路径未清理，部分还是**隐藏炸弹**（空实现静默失败、空 patch 清空服务器配置）。

**方案与效果**：
- **TerminalRepository 体系**（接口/实现/测试/Fake/DI 绑定 6 文件）：0 调用方死代码，真实终端走 `ServerTerminalRegistry → ServerTerminalWorkspace` 路径
- **ServerConnectionRepository**：`NotImplementedError("Phase 4")` 死接口；`testConnection` 移入 `ServerConfigRepository`（健康检查属配置管理）
- **DraftUseCase 转发层**：删除，`DraftInputDelegate`/`ChatViewModel` 直连 `DraftRepository`
- **ChatRepository.selectModel**：零调用死代码，且实现传 `ServerConfigPatch()` 空 patch——PATCH /config 是全量替换语义，**任何调用方接入即清空服务器 model 配置**，删除即拆弹
- **ChatRepository.undoRedo/replyPermission/findSessionForPermission**：零调用（undo 分支还抛 UnsupportedOperationException 半实现）
- **ServerRepositoryImpl 三个空实现**（setProviderEnabled/setModelVisible/saveServerConfig）：死路径，UI 已走 `updateGlobalConfig`/`setModelVisibility` 正确实现
- **孤立资源**：eng.traineddata（OCR 遗留 5MB）+ 根目录 116 张无引用截图

**验证**：编译 + 1191 单测全绿。

---

## A3. 分层修复

**类型**：架构 ｜ **状态**：✅ 已完成

**前因**：上架前审计发现 UI 层直接依赖 data 层 DTO（FileNodeDto/ServerPaths）与 data 层实现（PendingPromptRepository），domain 层边界被绕过。

**方案与效果**：
- **PendingPrompt 提 domain**：`PendingPromptRecord`/`PendingPromptRepository` 接口入 domain 层，data 实现改名 `PendingPromptRepositoryImpl` + DI 绑定；UI 只依赖接口
- **FileNode/ServerPaths DTO 泄漏修复**：统一 domain 模型（FileNode + FileType 枚举 + isDirectory() 扩展 + ServerPaths）+ `FileMapper` 集中映射；`DirectoryManager`/`SessionListViewModel`/`OpenProjectDialog` 不再泄漏 DTO

**验证**：编译 + 单测全绿。

---

## A4. 安全与内存泄漏修复

**类型**：安全/泄漏 ｜ **状态**：✅ 已完成

**前因**：2026-08 并发与内存专项审计（9 大审计点）发现 1 个高优先级内存泄漏 + 数个缓存增长与一致性瑕疵；上架审计另发现 13 处裸 URLDecoder 崩溃风险。

**方案与效果**：
- **ServerTerminalRegistry 内存泄漏**（P0）：`byServer` 只 getOrPut 无 remove，每次连接不同服务器都泄漏一个终端工作区（模拟器 + 协程作用域）。新增 `removeWorkspace`/`removeAllWorkspaces` + `Workspace.dispose()`（closeAll + scope.cancel），`OpenCodeConnectionService` 断开时自动调用
- **URLDecoder → safeDecodeParam 统一**（13 处）：密码含畸形 `%` 序列（如 `%NR`）会抛 IllegalArgumentException 崩溃；全部改用 `safeDecodeParam`（现成工具，AGENTS.md 承重规则）
- **AppNotificationManager.clearForServer**：3 个去重 ConcurrentHashMap 增加服务器级批量清理（此前只按会话单条清理，断开服务器后残留增长）
- **ToolSnapshotCache / toolExpandedStates**：mutableMapOf → ConcurrentHashMap（@Singleton 跨线程访问安全）
- **Log → AppLogger 统一**（61 文件）：业务代码全部走应用内诊断日志（AGENTS.md 规则），含 AppLogger 单测环境 NPE 防御（getStackTraceString null 回退）与 initialize 同步锁（防并发双消费者）
- **SessionStateService 防御性清理**：状态容器加 24h 无事件且非 Busy 的自动清扫（防孤儿会话状态无界增长）
- **一致性修缮**：MainActivity collectAsState → collectAsStateWithLifecycle；DirectoryManager callbackFlow → flow（取消传播规范）；AutoApproveRule round-trip flaky 测试修复（createdAt 毫秒竞态）

**验证**：编译 + 1191 单测全绿（修复了替换引入的 7 个单测 NPE 回归）。

---

# 📋 待办

## 1. 服务器密码明文经导航参数传递（安全缺陷）

**类型**：安全缺陷 ｜ **优先级**：P0（最高） ｜ **预估**：2-3 天

### 前因

项目早期为了简化路由，把服务器连接信息（URL/用户名/密码/名称/id）全部作为 query 参数随导航传递（`ServerRouteParams`，5 个参数）。密码虽经 URLEncoder 编码，但**不是加密**。当时的设计假设是"导航参数仅在进程内短期存在"，未考虑深链、进程重建回放、日志输出等暴露面。2026-08 上架审计时发现：密码已改用 Keystore 加密落盘（`SecretCipher`），但导航链路仍是明文——本地存储的加密努力被导航链路的明文传递抵消了一半。

### 问题

- 密码明文出现在：7 个路由的 query 参数、SavedStateHandle、进程重建时的导航回放、系统 Recents/深链可携带
- `WebViewScreen` 用 `"$username:$password"` 拼 Basic Auth 也存在 URL 里
- 审计确认 13 处 `URLDecoder.decode` 裸调用（已修复为 safeDecodeParam，但根因是"不该传密码"）

### 解决方案

1. `ServerRouteParams` 收敛为只传 `serverId`（删除 url/username/password/name 参数）
2. 各目标 ViewModel（Chat/SessionList/Workspace/WebView/ServerSettings 等）改为从 `ServerDataStore`（或 ServerRepository）按 id 查 `ServerConfig` 获取连接信息
3. `WebViewScreen` Basic Auth 同样改为按 serverId 取配置
4. ViewModel 取配置是 suspend 操作，需处理初始 loading 状态（UI 先显示加载，配置就绪后初始化）

### 预期效果

- 密码不再出现在任何导航/日志/深链暴露面，与本地加密存储闭环
- 导航参数减少，路由定义更简洁（NavGraph 25+ 处参数删除）
- 修复后密码只能通过两条路径存在：用户输入时、解密后的内存中

### 验证

编译 + 单测 + 真机全流程：连接服务器 → 会话列表 → 聊天 → 工作区 → WebView，确认各屏幕正常取到配置；杀进程恢复导航不崩溃、不丢密码。

---

## 2. MessageDataDelegate 职责过载（可维护性缺陷）

**类型**：重构 ｜ **优先级**：P1 ｜ **预估**：半天

### 前因

ChatViewModel 历史上是 1100+ 行的 God File，通过多轮 delegate 抽取瘦身（现 611 行）。`MessageDataDelegate` 是从中拆出的"消息数据中枢"，把消息列表、parts、SSE job、缓存、乐观消息、分页、工具展开、加载/错误全部收拢——抽取时图省事合并到一个类，导致它成了新的 God File（730 行，8 个职责）。

### 问题

- 730 行单类承担 8 个独立职责，难以阅读、难以单测（改分页可能碰坏乐观消息）
- 测试覆盖依赖 ChatViewModel 集成测试间接验证，缺少针对性单测

### 解决方案

1. 拆出 `MessagePaginationDelegate`：`currentMessageLimit`/`_hasOlderMessages`/`_isLoadingOlder`/`loadOlder` 等分页状态与逻辑
2. 拆出 `OptimisticMessageStore`：`_pendingMessageIds`/`_pendingMessages` 乐观消息集合与增删
3. **关键约束**：`chatMessageCache` 与 `lastCombineSessionId`（SSE 滚动铁律 8 的缓存机制）必须留在 MessageDataDelegate 主体，不能跟分页走——拆分时缓存引用断裂会重新引入列表闪烁/崩溃（历史 v4 教训）
4. 主体聚焦"消息 + parts + 缓存"（约 450 行）

### 预期效果

- 三个类各司其职，职责单一；分页/乐观消息可独立单测
- MessageDataDelegate 主体缩小到可维护规模

### 验证

编译 + 单测（现有 ChatViewModel*Test 覆盖乐观消息与分页路径，必须全绿）+ 真机 SSE 流式聊天回归（确认铁律 8 缓存行为不变）。

---

## 3. ChatScreen 主函数臃肿（可维护性缺陷）

**类型**：重构 ｜ **优先级**：P1 ｜ **预估**：半天 + 真机验证

### 前因

ChatScreen.kt 经过多轮子组件外移（components/input/tools 目录）已从 8000+ 行瘦到 888 行，但**主函数**仍约 600 行——滚动状态集群（autoScrollEnabled/isAtBottom/4 个 LaunchedEffect）和状态装配全部内联在 `ChatScreen` 单函数里。滚动相关逻辑是历史回归重灾区（beta.62-64 时代反复出问题），集中内联是为了可控，代价是可读性差。

### 问题

- 主函数 600 行难以导航；滚动/副作用逻辑与其他 UI 装配混杂
- 滚动逻辑修改需在 600 行函数里小心定位，易误伤

### 解决方案

抽取 `rememberChatScrollController(listState, messageCount, pendingCount): ChatScrollController`：
- **必须整体搬移**：`autoScrollEnabled`、`isAtBottom`、双 key `LaunchedEffect(isScrollInProgress, isAtBottom)` 三者一起（SSE 滚动铁律 4——`isAtBottom` 是自愈机制，拆散任一即回归闪烁/抖动）
- 编辑前必须读 `docs/chatscreen-editing-protocol.md`（每次编辑后编译 + 独立提交，失败 git checkout 重试）

### 预期效果

- ChatScreen 主函数 600 → ~350 行；滚动行为封装为可复用、可单测的控制器
- 后续滚动优化（如新锚定策略）有明确修改点

### 验证

编译 + 单测 + 真机 SSE 滚动稳定性回归（按 `docs/research/sse-scroll-stability-iron-laws.md` §5.2/5.3：流式输出不闪烁、不跳底、不卡顿）。

---

## 4. SessionListViewModel 分层越界（架构一致性缺陷）

**类型**：重构 ｜ **优先级**：P2 ｜ **预估**：1-1.5 天

### 前因

SessionList 页面是项目早期实现的，当时目录浏览逻辑直接在 ViewModel 里写。后来其他页面逐步规范为 ViewModel → UseCase → Repository 模式，但 SessionListViewModel 未同步改造——它成了全项目唯一一个混用 4 种数据源的 ViewModel。

### 问题

- 同时注入 `SessionApi`/`FileApi`/`SystemApi`/`TerminalApi`（绕过 Repository）+ `EventDispatcher`（data 层实现细节）+ UseCase + Repository，分层规则在此失效
- `internal val` 把 Api 暴露给同包 UI 文件，UI 可绕过 ViewModel 直接调 Api
- 后续迭代时新代码会模仿这个"坏榜样"

### 解决方案

1. 4 个 Api 操作下沉到 `SessionListUseCase`（或复用现有 UseCase）：会话 CRUD、目录浏览、健康检查
2. `EventDispatcher` 依赖改为经 Repository 接口暴露
3. `internal val` → `private`，UI 只经 ViewModel 方法

### 预期效果

- SessionListViewModel 与其他 ViewModel 模式一致（只依赖 UseCase/Repository）
- 目录浏览逻辑可单测（DirectoryManager 已存在，接线即可）

### 验证

编译 + 单测 + 真机会话列表/打开项目对话框回归。

---

## 5. 小项

### 5.1 ChatMessageList 指纹函数外移（P3，约 30 分钟）

`ChatMessageList.kt` 765 行，因 SSE 铁律 6-8 的缓存逻辑膨胀。**只外移纯函数**（`messageFingerprint`/`partsFingerprint`/`tailHash`/`messagesSignature`）到 `util/MessageFingerprints.kt`；缓存函数高度耦合，不动。验证：编译 + 单测 + 真机流式回归。

### 5.2 Phase 历史注释清理（P3，约 30 分钟）

约 30 处"在 Phase N Task X 中提取"类注释——工作早已完成，注释制造认知噪音。批量删除纯历史标记，保留功能说明部分。验证：编译。

### 5.3 SearchMatchDto 字段对齐（P3，启用 /find 前必须做）

`data/dto/response/FileResponses.kt` 的 `SearchMatchDto` 字段与 API 不匹配（注释自认：API 是 `path:{text}`/`line_number`，DTO 是 `lines`/`lineNumber`）。当前 `/find` 端点未启用所以未触发反序列化错误；**启用前必须按 API 对齐字段**（@SerialName 处理 snake_case），否则搜索功能会静默失败。验证：联调 /find 端点。
