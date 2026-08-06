# Backlog

> 待办事项登记。条目保持简洁：接手的人/AI 应能读懂"做什么、为什么、怎么做"。
> 完成一项删除一项；新发现的问题按此格式追加。

---

## 1. 密码导航参数重构（安全，优先级最高）

**现状**：`password` 明文作为 query 参数在 7 个路由间传递（NavGraph 25+ 处），会暴露于日志、深链、进程重建。密码已在本地 DataStore 加密存储（SecretCipher）。

**做法**：导航参数只传 `serverId`；各 ViewModel 从 `ServerDataStore`（或 ServerRepository）按 id 取 `ServerConfig`（含已解密密码）。涉及：`ServerRouteParams` 简化、`NavGraph`、7 个目标 ViewModel（Chat/SessionList/Workspace/WebView 等）、`WebViewScreen` 的 Basic Auth。注意 ViewModel 取配置是 suspend，需处理初始 loading。

**验证**：编译 + 单测 + 真机全流程（连接/会话/工作区/WebView）。

---

## 2. MessageDataDelegate 拆分（可维护性）

**现状**：`ui/screens/chat/MessageDataDelegate.kt` 730 行，承担消息列表、parts、SSE job、缓存、乐观消息、分页、工具展开、加载/错误共 8 个职责。

**做法**：拆出 `MessagePaginationDelegate`（消息分页/加载更早）与 `OptimisticMessageStore`（乐观消息）。**注意**：`chatMessageCache` 与 `lastCombineSessionId`（SSE 铁律 8）必须留在主体，不能跟分页走。

**验证**：编译 + 单测（ChatViewModel*Test 覆盖乐观消息/分页路径）。

---

## 3. ChatScreen 滚动控制器抽取（可维护性）

**现状**：`ui/screens/chat/ChatScreen.kt` 888 行，主函数内滚动状态集群（autoScrollEnabled/isAtBottom/4 个 LaunchedEffect）臃肿。

**做法**：抽 `rememberChatScrollController`。**SSE 铁律**：`autoScrollEnabled`、`isAtBottom`、双 key `LaunchedEffect(isScrollInProgress, isAtBottom)` 三者必须整体搬移，缺一即滚动回归。编辑前读 `docs/chatscreen-editing-protocol.md`（每次编辑后编译+提交）。

**验证**：编译 + 单测 + 真机 SSE 滚动稳定性（参考 `docs/research/sse-scroll-stability-iron-laws.md` §5.2/5.3）。

---

## 4. SessionListViewModel 分层归位（一致性）

**现状**：唯一混用 Api + UseCase + Repository + EventDispatcher 的 ViewModel（4 个 Api 绕过 Repository）。

**做法**：4 个 Api 操作下沉到 UseCase；`internal val` 改 `private`。

**验证**：编译 + 单测。

---

## 5. 小项

- **ChatMessageList 指纹函数外移**：`messageFingerprint`/`partsFingerprint`/`tailHash`/`messagesSignature` 移到 `util/MessageFingerprints.kt`（文件 765 行，缓存函数高度耦合，只外移纯函数）。
- **Phase 历史注释清理**：批量删除"在 Phase N Task X 中提取"类纯历史注释（约 30 处，已完成的工作标记）。
- **SearchMatchDto 字段对齐**：`data/dto/response/FileResponses.kt` 的 SearchMatchDto 与 API 字段不匹配（path:{text}/line_number），启用 `/find` 端点前必须对齐（@SerialName 处理 snake_case）。
