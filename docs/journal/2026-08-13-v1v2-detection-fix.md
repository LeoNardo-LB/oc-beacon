# 2026-08-13 V1/V2 版本探测修复批次
> 状态：全部完结
> 迁移：2026-08-20 自 backlog.md 原文迁入（spec/journal 分离批次；原文逐字保留，未压缩）


- [x] **#83 V1 1.18.18 过渡形态被误判 V2 → 会话界面 JSON 解析崩溃（HTML fallback）** `data` `bug`
  - 问题：反馈者 opencode V1 1.18.18（V1/V2 双套端点过渡形态）——`GET /api/health` 返回 200 `{"healthy":true}`（无 version 字段）→ `ApiVersionDetector.tryV2` 只看 healthy → **误判 V2** → App 用 V2ApiClient 请求不存在的 `/api/*` 路径（rename/shell/todo/mcp/config/vcs/project/fork/import 等实测 16+ 端点）→ 服务器 SPA fallback 返回 `<!doctype html>`（HTTP 200）→ `parseToJsonElement` 崩溃：`Unexpected JSON token at offset 11: Expected EOF after parsing, but had h instead`（与反馈者截图完全一致，offset 11 = `<!doctype html>` 的 `h`）
  - 实测证据（本机 1.18.18 隔离运行）：V2 路径正常 JSON 的仅 16 个（session CRUD/message/active/provider/model/agent/command/skill/permission/question/location/fs/pty），**返回 HTML 的 16+ 个**（background/rename/shell/command/children/todo/mcp/config/vcs×3/project×2/service/stop/fork/import）
  - 修复（三层防御）：
    1. **根因**：ApiVersionDetector.tryV2 增加**版本交叉验证**——`ApiVersion.fromVersionString(version) == V2` 才判 V2（1.18.18 version 缺失或 1.x → 回退 tryV1 → `/global/health` 返回 version=1.18.18 → 正确判 V1；V1 路径在 1.18.18 上全部存在，实测通过）
    2. **content-type 防御**：tryV2/tryV1 校验响应 content-type 必须为 JSON（HTML 页面不算健康）
    3. **解析层防御**：V2ApiClient.parseRoot + V2ResponseWrapper.flexibleList/flexibleObject 检测 HTML 特征 → 抛 `NonJsonResponseException`（可读信息 + AppLogger.e），不再裸抛 JsonDecodingException
  - **2026-08-13 补充修复（反向回归）**：真实 V2 服务器版本号为 `0.0.0-next-17403`（npm next 预发布，major=0）→ `fromVersionString` 解析为 V1 → 修复 1 会把真 V2 误判 V1！补充判定规则：**version 解析为 2.x 或响应含 pid 字段（V2 特征，实测必有）→ V2**；version 缺失且无 pid → 过渡形态 → V1。新增测试 2 个（V2 预发布 pid 识别、V2 无 version 有 pid），全量 11 个探测测试通过
  - 测试：ApiVersionDetectorTest +5（版本矛盾/无 version/HTML/content-type/不可解析）；V2MappersTest +6（HTML 防御×2 + flexible 正常×4）；V2ApiClientTest +1（HTML → NonJsonResponseException）——全量 1562 单测通过
  - 工时：~0.5d | 难度：中 | 涉及：ApiVersionDetector、V2ApiClient、V2Mappers、NonJsonResponseException（新建）
  - 来源：反馈者复现 + 本机 1.18.18 隔离实测 + 双 deep-explore 调研
  - **验证状态**：编译 ✅ 单测 ✅；模拟器走查待执行（V1 连接 → 会话界面无报错）

- [x] **#84 V1/V2 功能差异适配清单——结案（全清单适配完毕；V2 OAuth 多步流关闭不做，2026-08-19 用户决策）** `compat` `refactor`
  - 问题：深度调研确认 V1(1.18.x) 与 V2(2.x) 是**三重断裂**（路径前缀 / 核心机制 / SSE 格式），客户端需按 apiVersion 区别处理以下功能（详见 docs/v1-v2-differences.md）：
    - **发送消息**：V1 `POST /session/{id}/prompt_async`（204 fire-and-forget）vs V2 `POST /api/session/{id}/prompt`（200 返回 Inbox 条目）——App 已适配 [确认]
    - **中断**：V1 `abort`（boolean）vs V2 `interrupt`（204 + `?continue=true`）——App 已适配 [确认]
    - **后台任务**：V1 仅实验性 `/experimental/session/{id}/background`（需 flag）vs V2 正式 `/api/session/{id}/background`（204）——**V1 下后台化入口应隐藏或降级** [待办]
    - **配置**：V1 `GET/PATCH /config` 可写 vs V2 `GET /api/config` **只读**（无 PATCH）——App 配置编辑在 V2 应禁用 [待办]
    - **Todo**：V1 `GET /session/{id}/todo` vs V2 **移除**（form/question 替代）——V2 下 Todo 入口应隐藏 [待办]
    - **Provider 认证**：V1 oauth authorize/callback 两步 vs V2 integration connect 多步异步——设置页认证流程 [→ V2 OAuth 部分关闭不做，见下方终局决策]
    - **Revert**：V1 直接 revert/unrevert vs V2 staged（stage/commit/clear）——App 回退功能 [待办]
    - **SSE 格式**：V1 `{id,type,properties}` vs V2 `{id,event,data}`（data 二次 JSON）——App 已适配 [确认]
    - **TUI 控制**：V1 13 个 `/tui/*` 端点 V2 移除——App 无依赖 [确认]
    - **session/status**：V1 `GET /session/status` vs V2 无直接等价（active 替代）——App V2 用 activeSessions [确认]
    - **配置格式**：V1 `config.json` 可读 vs V2 只读 `opencode.json(c)`；mcp 配置 `mcp.{name}` vs `mcp.servers.{name}`；权限模型工具分组 vs 有序数组——服务端侧差异，客户端只读展示 [评估中]
  - 工时：需逐项评估 | 难度：中 | 涉及：多处 UI + API 客户端
  - 来源：2026-08-13 网络 deep-explore（92% 充分度）+ 本地 1.18.18 实测
  - **2026-08-19 盘点核实（代码证据）**：① V1 后台化降级 → 已随 #85 完成（Background 菜单 V1 隐藏）；② V2 配置只读 → 已随 #85 完成（V2 PATCH guard）；③ Revert staged → **已实现**（V2ApiClient:954 `revert/stage` + :963 `revert/clear`，含 commit 后 revert 立即清空的时间差注释——只 stage 策略）；④ Todo → #85 确认无独立 UI 入口无需处理；⑤ SSE 格式/中断/发送/session status → 已适配。**仅剩 Provider 认证流程**（V1 oauth 两步 vs V2 integration connect 多步异步）[待办]——条目收敛为该单项
  - **2026-08-19 Provider 认证落地（e0bc781c）**：key 连接修复——curl 契约实测 beta-17595 的 PATCH /api/credential 要求 **label 必填**，App 原发 {type,key} 恒 400（**API k
  - **2026-08-19 终局决策：V2 OAuth 多步流关闭不做（用户拍板）**——理由：① **零在用场景**：服务器 credential 库实测 3 条凭据全部为 API key 型（zhipuai-coding-plan/deepseek/opencode-go），无任何 OAuth 型 Provider 在用；② **完整替代路径**：OAuth Provider 在服务器主机跑 `opencode auth login` 即完成，App 直连现成连接——App 内 OAuth 只省'碰一次服务器终端'；③ **验证不可完整**：真实 OAuth 回调需 Provider 账号，模拟器 E2E 无法闭环；④ 不增加任何新能力（V1 时代就有的功能，V2 只是协议变形）。触发重启条件：真要用 OAuth-only Provider（如 GitHub Copilot）且不便碰服务器时重新立项（0.5-1d：V2ApiClient 换 integration connect 端点 + 等待/轮询 UI + 设备码 chip 串联）ey 连接在此部署版完全不可用**，盘点发现的隐藏断裂）；补 label="oc-beacon" 后 204。单测 +1（body 断言）+ 全量绿；探针凭证已 DELETE 清理。**残留：V2 OAuth 多步流未实现**（getProviderAuthMethods/authorizeProviderOauth 返回空）——API 全貌已摸清：194 集成中 4 个支持 OAuth（github-copilot/openai/opencode/xai），流程 = POST connect/oauth {methodID} → attemptID+URL → 用户授权 → POST .../complete {code}；属独立功能开发（~0.5-1d），非适配缺口，待用户需要时实施

- [x] **#85 V1 连接下应隐藏/降级的功能 UI（根据 #84 清单落地）** `ui` `compat`
  - **2026-08-13 用户验收 ✅**：V1 下任务面板入口/Running/History 隐藏正常；V2 Todo/配置编辑降级确认
  - 问题：#84 调研结论中部分功能在 V1 下不可用/无意义，但当前 UI 未按 apiVersion 区分（参考 #78 已实现的 V2 隐藏 Share 模式）
  - 待落地清单（V1 下）：任务面板入口（V1 无正式后台系统）[评估中]；V2 下：Todo 入口（V2 移除 todo）、配置编辑（V2 只读）
  - 工时：~0.5d | 难度：低 | 涉及：ChatTopBar / 工具栏 / 设置页
  - 来源：#84 调研产出
  - **2026-08-13 完成**：
    1. **Background 菜单 V1 隐藏** ✅——ChatTopBar 新增 `isBackgroundSupported` 参数，Background 菜单项包条件；ChatScreen 传 `serverApiVersion != V1`；模拟器验证：V1 菜单 6 项无 Background、V2 菜单 6 项有 Background、无崩溃
    2. **配置编辑 V2 只读 guard** ✅——ServerSettingsViewModel 新增 `serverApiVersion` 字段（init 读取）；`setProviderEnabled`/`updateConfigPatch` V2 下直接提示失败（实测 V2 PATCH /api/config → 404）；`connectProviderApi`/`completeProviderOauth` 成功后 V2 跳过 disabledProviders PATCH（本地乐观更新，Provider 连接主操作不受影响）
    3. **Todo 无需处理** ✅——补充走查确认 Todo 无独立 UI 入口（SSE 事件驱动渲染，`SseEvent.TodoUpdated`），非用户可触发
  - 单测 1564 全通过；待用户验收

- [x] **#86 V1 连接下抽屉不显示 API 版本号（V2 显示 API v2 · 版本，V1 仅 Connected）** `ui` `compat`
  - **2026-08-13 用户验收 ✅**：V1 抽屉显示 API v1 · 1.18.18，观感确认通过
  - 问题：2026-08-13 三轮走查发现——V2 服务器抽屉显示 `API v2 · 0.0.0-next-17403`，V1 服务器仅显示 `Connected` 无版本号。版本检测实际正确（logcat 证实 1.18.18），但用户无法从 UI 直观看到 V1 版本
  - 建议：抽屉中对 V1 也显示 `API v1 · 1.18.18`（数据已有：ServerConfig.serverVersion）
  - 工时：~0.5h | 难度：低 | 涉及：ServerCard/抽屉组件
  - 来源：2026-08-13 补充走查（B7 项观察）
  - **2026-08-13 完成**：ServerCard.kt 移除 `apiVersion != V1` 显示条件，V1/UNKNOWN 均显示版本徽章（颜色沿用 else 分支）；模拟器验证：V1 卡片显示 `API v1 · 1.18.18`、V2 显示 `API v2 · 0.0.0-next-17403`、logcat 判定正确、无崩溃——待用户验收

- [x] **#83 补充验证记录（2026-08-13 三轮模拟器走查全部通过）**
  - V1 走查（旧 APK）：`Detected V1 API (version=1.18.18)`；会话界面无 JSON 报错；发送/接收链路正常
  - V2 走查（新 APK）：`Detected V2 API (version=0.0.0-next-17403, pid 特征识别)`；200 会话/4 项目加载；发送→SSE 回复；Share 菜单隐藏（#78 生效）；无崩溃
  - 补充走查：V1 菜单含 Share（与 V2 隐藏对比成立）、Fork 成功无幽灵会话、重命名生效、新建会话成功、模型列表加载、设置页 logcat 证实 1.18.18、全程零 FATAL
  - 走查清单：docs/simulator-walkthrough-v1v2.md（执行记录已填）

- [x] **#87 V1 长会话压测发现：/message 轮询 JsonConvertException ×302（非致命）+ 回复偶发重复渲染** `data` `sse`
  - **2026-08-13 模拟器复验 ✅（Agent 代测）**：长会话无 JsonConvertException、无重复渲染（每条消息单气泡）；附注：listMessages 打开会话 2 秒内冗余调用 ~7 次 + V2 分页 before 游标返回 400 后回退重头拉取（不崩溃、浪费网络）→ 登记 #91
  - 问题：2026-08-13 V1 长会话 40 条消息压测（全部通过、零崩溃）发现两个非阻塞观察项：
    1. **JsonConvertException ×302（已修复）**：logcat 显示 App 以 **5 秒周期轮询** `GET /session/ses_0051ddbbdffed3UmOqzX8SamAC/message?limit=50`（该会话为压测 subagent 的服务器会话，**不存在于本地 V1 1.18.18 服务器**）→ 404 → 错误体 `{"name":"NotFoundError",...}`（对象）被按 `List` 解析 → JsonConvertException。根因：`V1ApiClient.listMessages`/`V2ApiClient.listMessages` **无状态码检查**（404 错误体直接当数组解析）。**修复（2026-08-13）**：两处 listMessages 非 2xx 返回空页 + AppLogger.w；新增 V1ApiClientTest 3 个（404/5xx/正常）；L2 stale 轮询源为压测环境外部会话（已删除会话的遗留轮询，非 App 常规路径）
    2. **回复内容偶发重复渲染（已修复）**：部分回复出现重复文本（如 "Got it. Message 1 received.Got it. Message 1 received."）——根因：**REST 快照 text part `id=""` vs SSE part `id="prt_xxx"`**（part ID 契约差异）→ `handleMessagePartUpdated` 按 id 找不到 → 新增第二条 part → 同消息两条文本 part。**修复（2026-08-13）**：空 id 的 Text part 按**内容级匹配**（相等/前缀）合并而非新增；新增 MessageEventHandlerTest 3 个（内容合并/更长替换/内容不同仍新增）
  - 验证：单测 1575 全通过；模拟器复测待执行（长会话重复渲染观察 + logcat 无 JsonConvertException）
  - 工时：~0.5d | 难度：中 | 涉及：V1ApiClient/V2ApiClient.listMessages、MessageEventHandler

- [x] **#88 目录浏览性能：每次导航 >500ms（V1/V2）+ V2 大目录 53 秒 ANR** `perf` `data`
  - **2026-08-13 用户验收 ✅**：目录浏览流畅（缓存秒开），.opencode ANR 消除（234ms），性能复测全通过
  - 问题：2026-08-13 用户反馈"各类目录点击卡卡的"→ 性能测试确认：OpenProjectDialog 目录浏览**每次前进导航 >500ms**（V1 一致 SLOW 506-763ms；V2 537-799ms + 极端 .opencode 目录 53 秒 ANR"not responding"）。会话列表目录树 toggle 正常（<50ms）
  - 根因（两处）：
    1. **ANR**：`FileRepositoryImpl.listDirectory` 无 `withContext(IO)`，OpenProjectDialog 的 LaunchedEffect 在 Main 调度器 → V2 大目录（node_modules）的 JSON decode + map 在主线程 → 阻塞 → ANR
    2. **500ms 感知延迟**：每次目录导航无缓存，模拟器→宿主机网络往返 ~500ms 固有延迟（items 0-1 个也 >500ms）
  - 修复（2026-08-13）：
    1. FileRepositoryImpl.listDirectory 包 `withContext(Dispatchers.IO)`（网络+解析移出主线程）
    2. DirectoryManager 增加 **30s 目录列表缓存**（ConcurrentHashMap：路径→{items, at}）——已浏览目录返回/重复浏览秒开（CACHE HIT <100ms）
    3. 保留性能监控日志（listDirectories >500ms warn、buildTreeNodes >50ms warn、CACHE HIT debug）
  - 验证：单测 1575 全通过；模拟器复测待执行（V1/V2 缓存命中 + .opencode ANR 消除）
  - 工时：~0.5d | 难度：中 | 涉及：FileRepositoryImpl、DirectoryManager、OpenProjectDialog 链路
  - 来源：2026-08-13 用户反馈 + 性能测试（V1/V2 全量数据）

- [x] **#89 内存泄漏修复批次：Singleton keyed 状态会话切换后不清理** `data` `refactor`
  - **2026-08-13 确认完成 ✅（Agent 代确认，用户授权）**：①目录窗口 30 轮开关内存增长减速趋平（5.3→4.1MB/10轮，GC 回收 14MB）；②缓存 LRU 生效（CACHE HIT 39/fetch 15）；③会话退出清理链路 logcat 铁证（releaseSessionData + clearForSession 精确清理 50/90 条）；④1575 单测全通过
  - 问题：2026-08-13 用户反馈模拟器长时间运行后系统卡死（宿主机 swap 15Gi 满）→ 排查发现 App 内多处 **@Singleton 持有按 sessionId/serverId keyed 的可变集合**，正常切换会话（非 SessionDeleted/SSE 断开）不触发清理 → 数据永驻内存：
    1. **DirectoryManager.dirCache**（目录浏览缓存）：只 put 不清理，浏览大量目录（含 node_modules 大列表）条目永驻 → 已修：上限 200 + 过期清理（近似 LRU）
    2. **MessageEventHandler._messages/_parts**（按 sessionId）：ChatViewModel.onCleared 不清理 → 已修：EventDispatcher.releaseSessionData + ChatViewModel.onCleared 调用
    3. **SessionEventHandler._sessionDiffs/_lastUserMessageTime**：无 clearForSession → 已补
    4. **ShellJobsStore._jobsBySession**：有 clearForSession 但无调用点 → 已接入 releaseSessionData（经 ShellJobsHandler 委托）
    5. **StreamingOwnershipRegistry.owners**：仅 SessionDeleted 释放 → 已接入 releaseSessionData
    6. **AppNotificationManager 去重缓存 ×3**（(server, session) keyed）：仅断开/用户取消通知清理 → 已补 clearForSession（ChatViewModel.onCleared 直调，避免 EventDispatcher↔AppNotificationManager Dagger 循环）
    7. **SessionEventHandler.locallyClearedReverts**：已补 clearForSession 清理（防御）
    8. **ChatRepositoryImpl.toolExpandedStates**（toolId keyed，仅 UI 展开状态）→ 登记低优先级（#90）
  - 修复（2026-08-13）：
    - EventDispatcher 新增 `releaseSessionData(sessionId)`：级联清理 sessionHandler/messageHandler/permissionHandler/questionHandler/miscHandler/sessionNextHandler/sessionStateService/ownershipRegistry/shellJobsHandler
    - ChatViewModel.onCleared 调用 releaseSessionData（runCatching 防异常）
    - SessionEventHandler 新增 clearForSession；ShellJobsHandler 新增 clearForSession 委托
    - DirectoryManager.dirCache 上限 200 + 过期清理
    - 测试构造更新：5 个 ChatViewModel 测试加 mockk eventDispatcher
  - 验证：编译 ✅ 单测 1575 全通过 ✅；模拟器长时间运行内存曲线待测（dumpsys meminfo）
  - 工时：~0.5d | 难度：中 | 涉及：EventDispatcher、ChatViewModel、SessionEventHandler、ShellJobsHandler、DirectoryManager
  - 来源：2026-08-13 用户反馈系统卡死 + 全局 Singleton keyed 状态扫描

- [x] **#90 ChatRepositoryImpl.toolExpandedStates 无上限（低优先级）** `refactor`
  - 问题：2026-08-13 全局 keyed 状态扫描发现——`ChatRepositoryImpl.toolExpandedStates`（ConcurrentHashMap<toolId, Boolean>）只增不减（工具卡片展开状态记忆），toolId 随消息/工具调用增长 → 长期使用后无界
  - 影响：低（单条 Boolean 值，千条工具调用才 KB 级）；且 UI 展开状态跨会话记忆有产品价值
  - 方案：定期清理已结束消息的 toolId（需按消息关联）或 LRU 上限（如 1000 条）
  - 工时：~0.5h | 难度：低 | 涉及：ChatRepositoryImpl
  - 来源：2026-08-13 全局 Singleton keyed 状态扫描（#89 附属）

- [x] **#91 listMessages 冗余调用 + V2 分页游标 400——2026-08-18 主体修复（去重 fcbffbb6 + 心跳 00fbdda3 组合），残留串行对 P3；2026-08-19 复验收 ✅** `data` `performance`
  - 问题：2026-08-13 #87 模拟器复验发现——打开会话后 2 秒内 listMessages 冗余调用 ~7 次；V2 分页 `before=eyJp...` 游标返回 400 Bad Request 后回退重头拉取。不崩溃但浪费网络（长会话/慢网络下明显）
  - **2026-08-18 模拟器重现（加重）→ 主体修复**：进入 501 条会话 22ms 内 8 次重复（同 cursor 精确成对）/ 20s 内 30 次。归因：多链并发（初始加载 + SSE 重连 backfill + L3 校验）+ 40s 断连循环持续触发 recover。**修复组合**：① 心跳修复（00fbdda3）消除空闲断连循环 → recover 触发从每 40s 降至仅会话进入一次；② 在途去重（fcbffbb6，SessionRepositoryImpl 同参并发共享单一请求 + 3 单测）消除真并发重复。修后：仅进入时一次 burst（~29 次/1.2s）其后 18s+ 零请求
  - **残留（P3）**：相邻串行对（首请求完成后 31ms 跟随者再发同参——去重窗口已关的边缘竞态，burst 内 ~10 对）；根治需上游三链协调（backfill/L3/初始加载），涉 SSE 状态链风险高暂缓；form/request 双调用同族待顺带
  - **2026-08-19 模拟器复验收 ✅**：501 条会话（ses_0115b9cc）进入 8s 窗口 30 次请求（并发初始化 burst，与 08-18 记录的 ~29 一致）→ **稳态 20s 零请求**（无断连循环、无冗余轮询）——修复效果保持。残留串行对维持 P3 暂缓（性价比低 + 风险高），主条目结案
  - 关联：可能与本条目 #73（V2 cursor 格式 {"id","order","direction"} vs 本地 CursorCodec {"id","time"}）同源——需先核对游标编解码
  - 工时：~1-2h | 难度：中 | 涉及：V1ApiClient/V2ApiClient.listMessages、分页管线
  - 来源：2026-08-13 综合验收（#87 复验附注）

- [x] **#92 session.tool.progress 事件未处理（工具实时进度缺口）** `sse` `ui`
  - 问题：2026-08-13 #71 数据正确性确认发现——日志反复 `W SessionNextEventHandler: Unhandled session.next event: session.tool.progress`；shell 生命周期（created/exited/deleted）与内联展示数据正确，但工具实时进度事件被忽略 → Tasks 面板无法显示进行中工具进度
  - 影响：中（工具调用长任务时用户看不到实时进度；任务完成仍正常显示）
  - 方案：SessionNextEventHandler 处理 tool.progress 事件 → 进度流接入 Task 面板/消息内联展示
  - 工时：~2h | 难度：中 | 涉及：SessionNextEventHandler、TaskDelegate/TaskSheet
  - 来源：2026-08-13 综合验收（#71 附注）

- [x] **#93 WebView 销毁三件套（C-1+H-1+H-2，审计 Critical+High 泄漏）** `crash` `leak`
  - 来源：docs/research/audit-2026-08-13-memory-perf/REPORT.md §4.1-4.2（基线 3bdd7990，2026-08-13 静态审计）
  - ✅ **2026-08-13 修复完成（c0c74a4c）**：WebViewScreen 加 DisposableEffect onDispose 完整销毁（stopLoading→about:blank→clearHistory→removeView→destroy）；ErrorPayloadContent 加 AndroidView onRelease（滚出视口即销毁）；RenderWebView 加 DisposableEffect 销毁 + lastHtml/lastJsCommand 去重（消除无条件整文档重载）。grep 验证三处销毁齐全 ✅
  - 问题（✅ 2026-08-13 Agent 代码验证确认）：
    1. `ui/screens/webview/WebViewScreen.kt:149-292` 全屏 WebView **从不 destroy()**——无 onRelease/DisposableEffect，每次进出导航累积一个渲染进程（10-100MB）+ Activity 引用；Basic Auth 明文凭据随闭包驻留（91-99 行）
    2. `ui/screens/chat/components/ErrorPayloadContent.kt:79-101` HTML 错误气泡 WebView **无 onRelease**——滚出 LazyColumn 视口不销毁
    3. `ui/screens/viewer/RenderWebView.kt:55-99` 渲染面板 WebView **永不销毁**——切 SOURCE↔RENDER 反复累积
  - 对比：同项目 CodeWebView.kt:202-215 / PdfViewer.kt:83-94 均有完整销毁序列，此三处是遗漏
  - 方案：`AndroidView(onRelease = { wv -> wv.stopLoading(); wv.loadUrl("about:blank"); wv.destroy() })` 或 DisposableEffect 销毁（照抄 CodeWebView 模式）；考虑 LeakCanary 集成（debug）
  - 工时：~0.5d | 难度：低 | 涉及：WebViewScreen/ErrorPayloadContent/RenderWebView
  - 优先级：**P0**（每次操作累积，OOM/LMK 风险）

- [x] **#94 图片解码降采样（H-3+M-9，审计 High/Medium 性能）** `performance` `crash`
  - 来源：audit-2026-08-13-memory-perf/REPORT.md §4.2 H-3 + §4.3 M-9
  - ✅ **2026-08-13 修复完成（c0c74a4c）**：ImagePreviewDialog 加 inJustDecodeBounds + inSampleSize 降采样（缩略图 256px ~750KB / 预览 2048px ~12MB）；MediaUtils 压缩前降采样解码 + JPEG RGB_565（省 50%）+ token 估算用原始尺寸保证准确。grep 验证降采样齐全 ✅
  - 问题（✅ Agent 代码验证确认）：
    - `ImagePreviewDialog.kt:64-75,110-113` 主线程 `BitmapFactory.decodeByteArray` **全分辨率解码**（4000×3000 ≈ 48MB）只为 80dp 缩略图——滚入视口即掉帧/ANR；多图瞬时数百 MB → OOM
    - `MediaUtils.kt:174-211` 发送压缩前同样全分辨率解码（无 inSampleSize 预降采样）；非压缩路径原始字节 base64 dataUrl 常驻（1.33× 膨胀）
  - 方案：inJustDecodeBounds → 按目标尺寸算 inSampleSize → 再解码；inPreferredConfig=RGB_565；解码移 Dispatchers.IO；或改用 Coil3 AsyncImage（项目已引入）
  - 工时：~0.5d | 难度：低 | 涉及：ImagePreviewDialog/MediaUtils
  - 优先级：**P0**

- [x] **#95 消息热视图活跃会话无上限（H-4）——已修复 92418445（方案①）** `leak` `data`
  - 来源：audit-2026-08-13-memory-perf/REPORT.md §4.2 H-4
  - ✅ **2026-08-13 代码验证确认**：清理链路已修（#89），活跃会话热视图无 LRU 仍存在（Agent 复核）
  - 问题（✅ 部分确认）：MessageEventHandler `_messages/_parts`（@Singleton）清理链路已在 #89 修复（onCleared/SessionDeleted 清理 + clearForServer 已清 assistantMessageIds）✅；但**活跃会话期间热视图无 LRU/上限**——Room 侧有 1000 条/会话上限，内存侧没有；重连时 recoverMessages 为所有活跃会话批量拉消息；长会话单条消息（工具输出/大 diff）可达 MB 级
  - 方案：① 内存侧按会话保留最近 N 条（与 Room 1000 对齐）；② 单 Part 文本长度上限（如 512KB）截断/懒加载
  - 工时：~1d | 难度：中 | 涉及：MessageEventHandler.kt:42-58 ✅ 2026-08-14 完结（方案①：MEMORY_SESSION_MESSAGE_LIMIT=1000 与 Room 对齐；upsertMessages/handleMessageUpdated 写入路径应用上限，裁剪最旧段并同步清 parts/assistantMessageIds；未超限 O(1)；MessageEventHandlerMemoryCapTest 3 用例）
  - 备注：方案②（单 Part 文本长度上限）未做——涉及 UI 截断展示设计，如有 MB 级工具输出需求再立项
  - 优先级：P1（长期运行 + 多活跃会话可达数百 MB）

- [x] **#96 SessionDeleted 漏清 _lastUserMessageTime/locallyClearedReverts——已修复 6c29b8b6** `leak` `data`
  - 来源：audit-2026-08-13-memory-perf/REPORT.md §4.4 L-2
  - ✅ **2026-08-13 代码验证确认**：handleSessionDeleted:119-123 仅清 _sessions/_sessionDiffs（Agent 复核）
  - 问题（✅ **2026-08-13 Agent 代码验证确认**）：`SessionEventHandler.handleSessionDeleted`（:119-123）只清 `_sessions/_sessionDiffs`，**漏清 `_lastUserMessageTime` 与 `locallyClearedReverts`**——#89 修复的 clearForSession 只在 onCleared 调用，**服务器端 SessionDeleted 事件路径未接入** → 删除会话后条目残留
  - 方案：handleSessionDeleted 内补 `_lastUserMessageTime.update { it - sessionId }` + `locallyClearedReverts.remove(sessionId)`（或直接调 clearForSession）
  - 工时：~0.5h | 难度：低 | 涉及：SessionEventHandler.kt:119-123 ✅ 2026-08-14 完结（TDD 红→绿：handleSessionDeleted 补 _lastUserMessageTime/locallyClearedReverts 清理）
  - 优先级：P1（#89 验收后发现的补漏）

- [x] **#97 SSE 热路径优化批次（H-5/H-6/M-6/M-15 全完成）——已修复 ddfc683c + 98b90e34** `performance` `sse`
  - 来源：audit-2026-08-13-memory-perf/REPORT.md §4.2 H-5/H-6 + §4.3 M-6/M-15
  - ✅ **2026-08-13 代码验证确认**（Agent 分区复核）：H-5 三子项全确认（SseClient:44-51 逐字节装箱 / SessionNextEventParser:34-35 多遍 / SseClientV2:171,181 双重转换）；H-6 全量重写确认（MessageEventHandler:235-240 + MessageStore:69）；M-6 prettyPrint 确认（NetworkModule:34 且被 MessageStore 共用）；M-15 O(N×M) 确认（:147 Map.plus 每 delta 拷贝）
  - 问题（✅ 部分确认）：
    1. **H-5 解析层分配风暴**：`SseClient.kt:42-72` readRawLineBytes 逐字节装箱 + `SessionNextEventParser.kt:34-35` V1 树→toString→decodeFromString 三遍 + `SseClientV2.kt:171-181` 双重 ByteArray 转换——流式 20-60 事件/s 持续制造 KB-MB 垃圾
    2. **H-6 双写写放大**：flush 后对整条增长中消息全量 JSON 编码 + Room 全行重写（~20 次/s）——**#52 2026-08-11 已评估"频率不可降、无进一步收益"，但 H-6 是新角度：单次写入量（全量重写）+ prettyPrint 放大 + trySend 静默丢写（N-1）**——需增量写（append delta）或节流合并（500ms/1s）
    3. **M-6 prettyPrint=true**（✅ NetworkModule.kt:34 确认）：全局 Json 带缩进——所有序列化 +30-50% 体积与编码 CPU，与 H-6 叠加
    4. **M-15 flushPendingDeltas O(N×M)**：批内每 delta 整份 Map 拷贝（`updated + (messageId to ...)`）——单次 toMutableMap 可消除
  - 方案：增长型 ByteArray 分块读；decodeFromJsonElement 单遍解析；双写增量/节流；prettyPrint=false；M-15 单次拷贝
  - ✅ 2026-08-14 进展：H-5 三子项全修（readRawLineBytes→ByteArrayOutputStream 无装箱管线 + V1/V2 data: 行字节切片 + SessionNextEventParser decodeFromJsonElement 单遍）；M-6 prettyPrint=false；M-15 flush 单次 toMutableMap 就地聚合。1610 单测全绿 + 模拟器流式实测正常（"Thought for 210ms" 渲染正确）
  - ✅ 2026-08-14 H-6 完成（98b90e34）：SSE 双写增量落盘——flush 时按 part 追加文本（O(delta) 写，DAO appendPartText SQL 拼接），消息事件仍全量 upsert（ended 覆盖防漂移）；MessageEventHandlerIncrementalPersistTest 验证跨批累积与全量覆盖一致性
  - 工时：2-3d | 难度：中-高 | 涉及：SseClient/SseClientV2/SessionNextEventParser/MessageEventHandler/NetworkModule
  - 优先级：P1（流式体验卡顿主要嫌疑）

- [x] **#98 无界容器治理批次 2（H-7+M-1+M-7+M-13 全完成）——已修复 4da3fe60** `leak` `refactor`
  - 来源：audit-2026-08-13-memory-perf/REPORT.md §4.2 H-7 + §4.3 M-1/M-7/M-13
  - ✅ **2026-08-13 代码验证确认**（Agent 分区复核）：H-7 ToolSnapshotCache:23 无上限无 TTL；M-1 pendingInputs:77 无 clear 且仅 promoted 消费；M-7 mdRegistry:395/RenderReadiness:63 无 remove（grep 0 匹配）；M-13 dirCache:43 无 LRU + loadJobs:44 无 finally remove
  - 问题（✅ Agent 代码验证确认，全部无上限/LRU/TTL）：
    1. **H-7 ToolSnapshotCache**（domain/repository/ToolSnapshotCache.kt:23）：ConcurrentHashMap 无界，写入（ChatViewModel put）与清理（FileViewerViewModel.onCleared）生命周期分离——导航取消/失败条目（含整文件内容数 MB）永驻
    2. **M-1 SseClientV2.pendingInputs**（:77,296,300）：HashMap 无界，仅 promoted 时消费；admitted 后断连丢失 → 条目永驻
    3. **M-7 mdRegistry/RenderReadinessRegistry**（ChatMessageList.kt:129,395 / RenderReadiness.kt:63-67）：组合级注册表无 remove——滚出视口条目保留 MarkdownState（AST 为原文数倍）
    4. **M-13 WorkspaceViewModel dirCache/loadJobs**（:43-44）：dirCache 无 LRU（仅 refreshRoot 清）；loadJobs 完成 Job 引用永不清理
  - 方案：参照 DirectoryManager.dirCache 200 条 LRU 标杆统一治理；mdRegistry 加 DisposableEffect onDispose remove
  - ✅ 2026-08-14 完成（4da3fe60）：ToolSnapshotCache LRU 200 + 访问同步化；pendingInputs ConcurrentHashMap + 有界 64 + 每连接清空（兼修 D2-02）；mdRegistry/RenderReadiness onDispose 注销；workspace dirCache LRU 200 + Job 完成自清理。ToolSnapshotCacheBoundedTest 2 用例
  - 工时：~2d | 难度：中 | 涉及：ToolSnapshotCache/SseClientV2/ChatMessageList/RenderReadiness/WorkspaceViewModel
  - 优先级：P1

- [x] **#99 TaskDelegate 每 5s 无条件轮询（M-10，审计 Medium 性能）** `performance`
  - 来源：audit-2026-08-13-memory-perf/REPORT.md §4.3 M-10
  - ✅ **2026-08-13 代码验证确认**：TaskDelegate:88-90 while(true) delay(5_000)（Agent 复核）
  - 问题（✅ Agent 代码验证确认）：`TaskDelegate.kt:88-93` while(true) { refreshActiveSessions(); delay(5_000) }——ChatScreen 打开期间即使完全空闲也每 5s 一次 HTTP `/api/session/active`（12 次网络唤醒/分钟）
  - 方案：空闲降频（无子会话且全 idle 退避 30s+）；V1 走 SSE 事件驱动，仅 V2 轮询兜底
  - 工时：~0.5d | 难度：低 | 涉及：TaskDelegate.kt:84-93
  - 优先级：P2

- [x] **#100 SessionListViewModel 主线程全量状态重建 + 搜索无防抖（M-11，审计 Medium 性能）** `performance` `ui`
  - 来源：audit-2026-08-13-memory-perf/REPORT.md §4.3 M-11
  - ✅ **2026-08-13 代码验证确认**：combine:350 无 flowOn；上游 5 Flow 无 distinctUntilChanged；搜索逐键 loadSessions 网络重取（Agent 复核）
  - 问题：combine 在主线程 buildContentState（过滤+排序+搜索+分类+树构建+未读判定全量）；上游 6 源无 distinctUntilChanged；搜索逐键全量网络重取
  - 方案：上游 distinctUntilChanged；_searchQuery.debounce(300)；buildContentState 移 Dispatchers.Default；搜索改纯客户端过滤
  - 工时：~1d | 难度：中 | 涉及：SessionListViewModel/SessionListStateBuilder
  - 优先级：P2

- [x] **#101 FileViewer/RenderWebView 性能批次（M-12+M-14，审计 Medium 性能）** `performance`
  - 来源：audit-2026-08-13-memory-perf/REPORT.md §4.3 M-12/M-14
  - ✅ **2026-08-13 代码验证确认**：FileViewerViewModel:45,167-178 整文件驻留 + 逐字符重扫 + AnnotationManager:17 额外拷贝 + PDF Base64；RenderWebView:91-98 update 无条件重载无 last* 比较（Agent 复核）
  - 问题：FileViewerViewModel 大文件整读多份拷贝 + 分页 O(k·n) 逐字符重扫（20 万行翻 10 页 = 10 次全扫）+ \r\n 归一化拷贝 + PDF Base64 整段塞 JS；RenderWebView update 每次重组无条件 loadDataWithBaseURL 整文档重载（丢滚动位置/图片重解码）
  - 方案：lineOffsets 索引切片；remember 比较"上次已应用"值跳过
  - 工时：~1d | 难度：中 | 涉及：FileViewerViewModel/AnnotationManager/RenderWebView
  - 优先级：P2

- [x] **#102 日志系统性能批次（M-2+M-3+M-4，审计 Medium 性能）** `performance` `logging`
  - 来源：audit-2026-08-13-memory-perf/REPORT.md §4.3 M-2/M-3/M-4
  - ✅ **2026-08-13 代码验证确认**：M-2 DebugLogger:33 无界 StringBuilder + reset 0 调用 + 同步全量写 + 无线程同步；M-3 sanitize:155-171 内联 10 Regex + recordBatch 每批 refresh 1000 条；M-4 **部分确认**：rawJson 副本存在（V2EventParser:114-118），但日志为 AppLogger.d（DEBUG-only）非报告所称 WARN——影响降级（Agent 复核）
  - 问题：DebugLogger 无界 StringBuilder + 主线程同步全量写文件 + O(n²) 累计 I/O + 无线程同步（WebView JavaBridge 并发）；DiagnosticLogRepository.sanitize 每字段新建 ~10 Regex + 每批全量 refresh；V2 未识别事件每事件构造整 JSON 副本 + WARN 持久化（叠加 M-3）
  - 方案：append 增量写 + 锁 + 512KB 限容；Regex companion 预编译 + refresh 1s debounce；rawJson 截断/降级 DEBUG
  - 工时：~1d | 难度：中 | 涉及：DebugLogger/DiagnosticLogRepository/V2EventParser
  - 优先级：P2

- [x] **#103 审计 Medium 其余（M-5+M-8+M-16）** `performance` `ui`
  - 来源：audit-2026-08-13-memory-perf/REPORT.md §4.3
  - ✅ **2026-08-13 代码验证确认**：M-5 ChatRepositoryImpl:79-92 sortedBy+upsertMessages 在 IO 块外（Main）；M-8 ChatMessageList:769-770 "t_head" key 确认；M-16 WorkspaceScreen:138-142 组合体直接 filter + VM filterGitChanges 无调用方（Agent 复核）
  - M-5：ChatRepositoryImpl.getMessagesFlow 种子合并在主线程（sortedBy+upsertMessages 移入 withContext(Default)）
  - M-8：ChatMessageList 最新 turn 的 LazyColumn key 不稳定（"t_head"）——每轮边界整气泡销毁重建（含 rememberMarkdownState 重解析）→ key 改 turn 组首条消息 id
  - M-16：WorkspaceScreen git 过滤每次重组全量执行（无 remember/derivedStateOf；与 VM 逻辑重复）
  - 工时：~0.5d | 难度：低 | 涉及：ChatRepositoryImpl/ChatMessageList/WorkspaceScreen
  - 优先级：P2

- [x] **#104 审计 Low 批量（L-3~L-18，审计 Low——除 L-1=#90、L-2=#96）** `refactor`
  - 来源：audit-2026-08-13-memory-perf/REPORT.md §4.4
  - ✅ **2026-08-13 代码验证确认**（Agent 分区复核）：L-3 persistJob?.cancel 模式（:101）；L-4 V1:331-334/V2:846-849 新建 client；L-5 getParts flatten + 生产 0 调用方；L-6 PdfViewer:120 addInterface 无 remove（对比 CodeWebView:207 有）；L-7 :126 onValueChange 内 Regex；L-8 :105-109 4s 永久轮换；L-9 :203 无 remember；L-10 :68-74 delay(100)；L-11 :310 timestamp_index key；L-12 FileTreeUtils:22-31 + 递归拼接；L-13 DiffView:119 现场 Regex；L-14 NavGraph:424-429 整文件下载判非空；L-15 :60-68 无 remember + :143 forEach 非虚拟化；L-16 :154-189 无 TTL/去重；L-17 :37 只增不减无 sweep；L-18 ChatViewModel:428-458 主线程全量扫描无 distinctUntilChanged
  - L-3 UnreadBadgeService.persistAsync 每次取消上一个写 → 改合并写（Mutex/Channel 单消费者）
  - ✅ 2026-08-15 修复：persistAsync 改 Channel(CONFLATED) 单消费者合并写（写前取最新快照，不再取消进行中的 DataStore 写）
  - L-4 exportSessionToStream 每次新建 OkHttpClient（线程池/连接池泄漏）→ 复用共享 client
  - ⚠️ 2026-08-15 保留：#121 正在处理 V1/V2ApiClient；复用共享 client（NetworkModule 长超时单例）需协调
  - L-5 ChatRepositoryImpl.getParts 全量 flatten（当前无调用方）→ 接入前改索引或删除
  - ⚠️ 2026-08-15 保留：#103 正在处理 ChatRepositoryImpl（getParts 所在文件）
  - L-6 PdfViewer JS 桥未 removeJavascriptInterface（CodeWebView 有）
  - ✅ 2026-08-15 修复：onDispose 先 removeJavascriptInterface("PdfViewerInterface") 再 destroy（与 CodeWebView 一致）
  - L-7 ChatScreenBottomBar 每按键编译新 Regex → companion 预编译
  - ✅ 2026-08-15 修复：AT_MENTION_REGEX / WHITESPACE_SPLIT_REGEX 顶层预编译（3 处现场编译清零）
  - L-8 ChatInputBar 占位符 4s 永久轮换 → 仅焦点+空文本时轮换
  - ✅ 2026-08-15 修复：占位符轮换仅聚焦+空文本时进行（ChatTextField 增 onFocusChange 上报）
  - L-9 ChatMessageList getActiveToolProgressForSession 每次重组新建 Flow → remember 提升
  - ⚠️ 2026-08-15 保留：#103 正在处理 ChatMessageList
  - L-10 ReasoningBlock 100ms ticker 常驻重组 → 降 1000ms
  - ✅ 2026-08-15 修复：ticker delay 100ms→1000ms（与 StreamingElapsedText 一致）
  - L-11 DiagnosticsScreen key 用 timestamp_index 拼接 → 队列头淘汰全 key 失效 → 内容派生稳定键
  - ✅ 2026-08-15 修复：key 改内容派生稳定键（timestamp+category+message hash）
  - L-12 FileTreeUtils.flattenTree 用 + 递归拼接 O(n²) → buildList 累积
  - ✅ 2026-08-15 修复：flattenTree 改 buildList+addAll 累积（O(n²)→O(n)）
  - L-13 DiffView 每候选行现场编译正则 → companion 预编译
  - ✅ 2026-08-15 修复：INDEX_LINE_REGEX 顶层预编译
  - L-14 NavGraph.checkFileExists 整文件下载只为判非空 → HEAD/大小
  - ⚠️ 2026-08-15 保留：FileRepository/FileApi 无 HEAD/stat 端点，需新增服务器 API（超出清理范围）
  - L-15 ServerModelFilterScreen 过滤无 remember + 组内非虚拟化渲染
  - ⚠️ 2026-08-15 部分修复：过滤已加 remember(search, groups)；组内非虚拟化→拍平独立 lazy items 为 UI 重构（需类型结构设计），保留
  - L-16 HomeViewModel 连接状态变化重启全部 providers 网络检查 → 进行中去重 + TTL
  - ✅ 2026-08-15 修复：进行中不重启（同 key 去重）+ 30s TTL（lastProvidersCheckAt，断开时清除）
  - L-17 UnreadBadgeService._lastCompletedReplyTime 只增不减无 sweep → 复用 staleness 循环清理
  - ⚠️ 2026-08-15 保留：复用 SessionStateService staleness 循环（#122 正在处理该文件）+ sweep 策略需设计
  - L-18 ChatViewModel token 统计主线程全量扫描（2000 条×20 次/s）→ map 派生 + distinctUntilChanged
  - ✅ 2026-08-15 修复：map 派生 TokenStats + distinctUntilChanged + flowOn(Default)（扫描移出主线程）
  - 工时：~1-2d | 难度：低 | 涉及：见各条 | 优先级：P3（顺手修复）

- [x] **#105 审计备注批量（N-1~N-15 重点项）** `refactor` `security`
  - 来源：audit-2026-08-13-memory-perf/REPORT.md §4.5
  - ✅ **2026-08-13 代码验证确认**（Agent 分区复核）：N-1 trySend 返回值未检查（:240）；N-2 rawSseEvents 全工程仅 3 匹配零订阅；N-3 JumpBubbleObserve settled 0 读写；N-4 ScrollCompensation:50 反射（有 try-catch 降级）；N-5 WebViewScreen:91-92 闭包捕获明文凭据；N-6 CodeSourceView 2 match 无调用方；N-7 TerminalDelegate:121-123 空实现；N-9 cancelScope 0 调用；N-12 SessionTreeList:56-67 key 不变不续载；N-14 MainActivity:79 replay=1；N-15 OpenCodeApp:57 双 scope 并存。**路径修正**：N-10 QuestionParser 实际在 ui/screens/chat/util/（非 data/repository/parser/）。**N-11 修正**：SessionActionsDelegate:323,339 与 MessagePaginationDelegate:248 共 3 处 AppLogger.d 无 BuildConfig.DEBUG 门控（AppLogger.shouldPersist 层面阻止 DB 写入，影响低）
  - N-1（数据一致性）：persistQueue trySend 满时静默丢写 → 失败计数/降级
  - ✅ 2026-08-15 修复：trySend 失败计数 + 周期性 WARN（可观测性；完整"降级"策略待评估）
  - N-4（维护风险）：ScrollCompensation 反射访问 Compose 私有 API → BOM 升级前必须验证
  - ⚠️ 2026-08-15 保留：BOM 升级前验证（记录性条目，已有 try-catch 降级）
  - N-5（安全）：WebViewScreen Basic Auth 明文凭据闭包驻留（叠加 #93）
  - ⚠️ 2026-08-15 保留：叠加 #93；WebViewScreen 为 #121 涉及文件（D2-L7 删除待协调）
  - N-2/N-3/N-6/N-9（死代码）：rawSseEvents 无订阅者、JumpBubbleObserve、CodeSourceView 无调用方、cancelScope → 清理
  - ✅ 2026-08-15 N-2 核实：已过时——rawSseEvents 全库 grep 0 匹配（早已删除）；N-6 修复：CodeSourceView.kt 整文件删除（grep 仅自引用+HighlightBuilder 文档注释）；N-9 修复：cancelScope() 删除（grep 无调用方）；N-3 ⚠️ 保留：#103/#120 正在处理 ChatMessageList/MessageCardUser（bubbleTopY 写入点）
  - N-7：TerminalDelegate.closeTerminalSession 空实现（设计取舍，评估）
  - ⚠️ 2026-08-15 保留：设计取舍（终端跨屏幕常驻），需产品决策
  - N-12（功能缺陷）：SessionTreeList 分页加载完成停靠底部不自动续载
  - ⚠️ 2026-08-15 保留：功能性缺陷（shouldLoadMore key 不自动续载），非清理类，需功能改动
  - N-14（功能隐患）：_deepLinkFlow replay=1 配置变更后重放旧 deep-link
  - ⚠️ 2026-08-15 保留：功能性隐患（加已消费标记属功能改动）
  - N-15（架构）：OpenCodeApp 自建 appScope 与 DI @ApplicationScope 双套并存 → 统一
  - ⚠️ 2026-08-15 保留：架构统一需协调（OpenCodeApp，#115 曾涉及）
  - N-8/N-10/N-11/N-13（报告判定"可接受/可忽略"，仅记录备查）：SettingsViewModel 22 个 Eagerly 映射（单字段提取开销极小）；SyntheticNotificationCard/QuestionParser Regex 未预编译（低频）；SessionActionsDelegate 等 Debug 日志较多（已 DEBUG 门控，Release 无影响）；SessionRow 每行 remember SimpleDateFormat（可接受）
  - ✅ 2026-08-15 核实：报告判定"可接受/可忽略"，仅记录备查，无需处理（未改）
  - 工时：~1d | 难度：低-中 | 优先级：P3

- [x] **#107 V2 交互式提问链路不通（question 工具调用后无 SSE 事件、REST 空）——已修复（与 #130 同根因，form API 适配）** `sse` `compat`
  - 问题：2026-08-13 构造提问验收场景时发现（Agent 实测）——V2 服务器（0.0.0-next-17403）上 agent 成功调用 question 工具（含单选+多选两个问题，state=running），但 V2 **既不发出 question.asked SSE 事件**，`GET /api/question/request` 也返回空；App 每 30s 轮询均无果，仅显示工具调用头 "Question"。V1（1.18.18）完全正常（GET /question 正确返回待处理问题）
  - 根因（2026-08-14 官方确认 issue #42541）：非缺陷而是**协议迁移**——V2 question 工具由 form 服务驱动（`form.created` SSE + /api/form/* 端点），旧 question.asked + /api/question/request 是 stale surface
  - 修复：#130 form API 适配（commit 5993c1a9/547bb204）——form.created → QuestionAsked 映射 + reply/cancel + /api/form/request 轮询兜底；真机 E2E 验证通过（卡片渲染/回答/取消/agent 续答全链路）
  - 优先级：P1 ✅ 2026-08-14 完结（随 #130）

- [x] **#106 工具链治理建议（审计 §7）——4/6 实现，1 延后（需真机），1 已失效** `tooling`
  - 来源：audit-2026-08-13-memory-perf/REPORT.md §7
  - 1. **LeakCanary** ✅ **2026-08-19 已修 493f0c07**：debugImplementation leakcanary-android:2.14（3.0 尚 alpha 选稳定线）。模拟器 E2E 五重证据：就绪日志 "LeakCanary is running and ready to detect memory leaks." + manifest 合并组件（LeakActivity/LeakLauncherActivity + 3 Provider）+ Leaks 桌面入口可开 + About 页 "About LeakCanary 2.14" + DEX 含库类（证据 /tmp/verify-leakcanary/）。已知行为变化（仅 debug）：桌面多一个 Leaks 图标；monkey 启动可能误开 Leaks——E2E 工具链须用显式组件名启动主 App
  - 2. **StrictMode** ✅ **2026-08-19 已修 c3078b41**：OpenCodeApp onCreate（BuildConfig.DEBUG 守卫）ThreadPolicy detectAll+penaltyLog / VmPolicy activityLeaks+closable+sqlLite+penaltyLog（不检测 cleartext——LAN http 是合法场景；不用 death penalty 防误杀）。首轮真实走查即捕获 **165 条主线程违规**（76% 为 SecretCipher 周期性 Keystore 解密，会话界面存活期每 ~5s 爆发）→ 登记为新条目「StrictMode 首轮发现」（见下）
  - 3. **Baseline Profile** ⏸ **延后（需用户真机）**：macrobenchmark/profileuron 生成需真实设备（官方指引：模拟器生成结果不代表真机性能分布，模拟器上"验证通过"无意义）；且需新建 benchmark 模块（~1d+ 基建）。触发条件：用户提供真机做 profile 采集时再立项
  - 4. **Regex 预编译规范** ✅ **2026-08-19 已修 d3e97478**：全库排查实际内联调用点 24 处（远超审计的 5 处，多数在 #135 批次已治理），13 文件等价重构提升为顶层/伴生预编译常量（含 ChatScreen 导出 slug——遵循编辑协议）。grep 复查内联清零 + 全量单测绿 + 模拟器冒烟 21 截图（工具卡/文件浏览器多级导航/长按菜单/markdown 滚动/synthetic 卡）零 FATAL（证据 /tmp/verify-regex/）
  - 5. **内存上限规范化** ❌ **已失效（2026-08-19 验证）**：指向的 #89（Singleton keyed 状态清理）/#90（toolExpandedStates）/#98（无界容器批次 2，4da3fe60）全部已修复关闭——无剩余同类容器，无需治理
  - 6. **CI 门禁** ✅ **2026-08-19 已修 aa551535**：lint { baseline + abortOnError=true } + release.yml 发版前 lint 步骤（此前 assemble* 从不跑 lint）。存量 59 errors 入 baseline（新 error 卡发版）；DebugLogger NewApi 误报以 @RequiresApi(Q) 消除（60→59）。存量清偿登记为新条目（见下）。**Compose 稳定性报告评估为不启用**：Kotlin 2.x 需 composeCompiler DSL 常开（每次编译产出报告拖慢构建），且无 CI 消费方——需要时一行 DSL 临时开启（app/build.gradle.kts composeCompiler { reportsDestination }），不设为默认
  - 工时：~1d（实际） | 难度：低 | 优先级：P3 ✅ 2026-08-19 完结（4 实现 + 1 延后 + 1 失效）

- [x] **#129 opencode 服务器僵尸 running（会话结束 drain 不释放）——App 已兜底+主动解除** `sse` `session`
  - 问题：2026-08-14 用户反馈"会话已结束但列表仍显示进行中"（网盘MCP与CLI工具调研 ses_00223cbb1ffeG2e92AziDs0e5E）——curl 实证：会话 30+ 分钟无新消息、无子会话、无后台任务，但 `/api/session/active` 持续返回 running；App L3 校验服务器也回复 Busy。**服务器端 session runner/drain 不释放**（opencode next-17403 行为）
  - 升级症状（2026-08-14 二次实测）：僵尸会话内**发消息无回复**——POST /prompt 返回 200+admission+SSE admitted 事件，但僵尸 runner 永不消费 inbox → 无执行事件 → UI 一直转圈（showBusy）+ 消息永远无回复（3 分钟后兜底 Idle 转圈才停）
  - App 兜底（2026-08-14 已修复）：FSM restValidation 不再刷新 lastEventAt（校验≠会话活动）+ L3 校验僵尸判定（服务器 Busy + 3 分钟无真实 SSE 事件 → 强制 Idle）。模拟器实证：网盘MCP 259s 无事件 → 转 idle 列表恢复；真实活跃会话不误判
  - App 根因修复（2026-08-14 commit 1bfa3f85）：僵尸判定时**主动调用服务器 interrupt** 解除僵尸（V1 abortSession / V2 interruptSession，SessionRepository.abort 已按 apiVersion 分流）——不再只本地装 Idle。实测：interrupt 204 → /active 中会话消失 → 后续发消息正常执行并回复
  - 服务器侧待办：升级 opencode 或向上游反馈（drain 泄漏）；App 兜底已覆盖显示正确性 + 僵尸解除
  - 工时：App 侧已完成 | 难度：低（App 侧） | 涉及：SessionStateService/SessionStateFSM | 优先级：P2（已兜底）
