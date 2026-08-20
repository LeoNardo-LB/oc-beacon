# 2026-08-19 模拟器代验收批次
> 状态：全部完结
> 迁移：2026-08-20 自 backlog.md 原文迁入（spec/journal 分离批次；原文逐字保留，未压缩）


> 环境：Pixel6_Android36（dev 0.3.1-dev.15 @ master a199fdd6）+ V2 服务器 0.0.0-beta-17595（10.0.2.2:4199）。
> 证据目录：/tmp/verify-acceptance/（截图 ab_*/b_*/p2_*/p3_*/p4_*/p5_*/p6_* + uidump + dumpsys + DataStore 副本 + 像素脚本）。
> 本批完成 6 项待验收代验 + 2 项部分更新（详见各条目回写）。

- [x] **新增A/新增B/E2E-F/提问卡M3/#91 全部代验收 ✅；#122 D2-25 / #79 P0 部分更新**（证据回写至各条目，此处不重复）
  - 新增A：dumpsys 通道铁证（opencode_questions mImportance=4 声光振动）+ 通知栏实拍（问题 · 会话名）+ 列表标记三行 uidump
  - 新增B：curl 答 form → SSE QuestionReplied → 卡片折叠 Asked，端到端 ≤1.5s，agent 复述确认
  - E2E-F：删除自定义 Emacs 后载荷 [[Python],[VSCode]] 零泄漏；选中色单/多选像素一致 (221,222,237)
  - M3：多状态画廊（双问题卡/选中态/三态自定义/折叠历史）vision 审查通过
  - #91：burst 30 次（并发初始化）→ 稳态 20s 零请求
  - #122 D2-25：权限卡 → 总是允许 → 规则落库 → 新询问 [auto-approve] 7ms 自动应答全链路
  - #79 P0：飞行模式 + force-stop → Room 渲染工具卡摘要形态，FATAL=0

- [x] **新增 P3：提问通知正文显示触发 prompt 而非问题文本——已修 2d9636bc** `ui` `notification`
  - 现象（2026-08-19 代验收新增A时发现）：通知正文 = 会话最后一条用户消息（原始 prompt "Use the question tool to ask me: What is your favorite animal? ..."），而非问题本身（"What is your favorite animal?"）——信息密度低，用户需读完整 prompt 才知道被问了什么
  - 根因：AppNotificationManager.showQuestionNotification（:379 附近）contentText 优先 findLatestUserMessages(sessionId,1)，questionText 仅作空回退——SSE 路径传入了正确的 questionText 但被用户消息覆盖
  - 方案：正文改优先 questionText（问题文本短且直接），用户消息可留作第二行或弃用；涉及 15 语言无需新键
  - **2026-08-19 修复（2d9636bc）✅**：正文优先 questionText，缺失回退用户消息（REST 兜底路径）再回退通用文案。E2E 铁证：dumpsys `android.text=String (What is your favorite season?)`——问题文本而非 prompt 全文；测试 form 已答清
  - 工时：~30min | 难度：低 | 涉及：AppNotificationManager | 优先级：P3

- [x] **新增 P3：CodePath 点击「无反应」——已修 e26d0c35（定性勘误：实为 P1 文件读取契约全链路断裂）** `ui` `markdown`
  - 现象（2026-08-19 D2-08 E2E 顺带发现，两轮独立复验一致）：assistant 消息中的文件路径 span（`app/build.gradle.kts`，已正确渲染 monospace+下划线+链接色）点击后无任何可见反应——无浏览器/无文件查看器/无 toast/无崩溃（进程存活）。链接（http/https）同场景点击正常打开浏览器
  - 根因方向：clickableMarkdown 对 CodePath 走 `uriHandler.openUri(item.text)`——裸相对路径非合法 URI（无 scheme），系统隐式 Intent 解析失败被静默吞
  - 方案：CodePath 命中改为打开应用内文件查看器（FileViewer 路由，参照消息附件的查看链路）或至少 toast 反馈；与 ChatMessageList 既有的 @文件点击行为对齐
  - **2026-08-19 修复（e26d0c35）——定性勘误 + 根因升级 P1**：主会话亲自复现发现点击实际有 Snackbar「文件未找到」（子代理漏看瞬态提示）；真根因是 **beta-17595 文件读取契约双重断裂**：① 端点为 GET /api/fs/read/<path> 通配符段（旧 ?path= 查询参数恒 500，curl 双形态对照实证）；② 响应为裸文件内容（无 JSON 信封，旧解析必抛）。**影响面远超 CodePath：文件查看器/existence 检查全链路在此部署版静默失效**。修复：通配符拼接（按段编码保斜杠）+ 裸文本回退解析（JSON 信封优先兼容老服务器）。E2E 全闭环：tap 代码路径 → 文件查看器打开渲染 build.gradle.kts 全文（修复前 Snackbar 报错）；单测 +3 全量绿；测试会话已删
  - 工时：~1h | 难度：低 | 涉及：ClickableMarkdown/文件查看导航 | 优先级：P3（点击是死路但无害）

- [x] **beta-17595 服务器兼容发现批次——2026-08-21 结案（① 已修 + ②③④ 归档方法论）** `compat` `upstream`
  - ① **prompt body agent 选择失效**：App 的 agent 切换（flat body agents 数组）在 beta-17595 被服务器忽略——curl 四种路径实测（agents 数组 / 顶层 agent 字段 / @mention 文本 / modern 包裹契约 400）全部仍用 build agent。**App 侧影响：模型选择器里的 agent 切换在此服务器上无效**（next-17403 上曾工作）。OpenAPI 有 POST /api/session/{id}/agent 端点（未实测）——待验证后改走该端点
  - ② **agent permissions 配置的 ask 规则不生效（默认评估链）**：用户配置 general/general-fast 的 git commit/push ask 规则从未触发（agent 直接执行）；build 加 ask 规则 + 重启服务器仍 allow——**规则顺序 last-match-wins（ask 必须放在 "*"/"*" allow 之后才生效）**，且需经 POST /api/session/{id}/permission 评估端点触发的路径行为一致。用户现有配置中 git ask 规则全部排在 allow 之后（顺序正确）但仍未触发——疑似工具调用路径不经该评估（仅 API 端点评估）
  - ③ **权限询问 E2E 触发方法论**：beta-17595 默认配置下无自然权限询问；可靠触发 = 临时加 ask 规则（放 allow 后）+ curl POST /session/{id}/permission {action,resources} → effect=ask + SSE PermissionAsked（App 正常弹卡）。测试后配置已还原（diff=0 + 重启复验探针 allow）
  - ④ **服务器 saved permission 规则**（GET /api/permission/saved）含历史 always 应答累积（shell/echo *、git commit * 等 6+ 条 global 规则）——App "总是允许"的 always 应答在服务器侧持久化；DELETE /api/permission/saved/{id} 可清理
  - 上游候选（并入 #146 候选池）：agent 切换端点契约（prompt body agents 语义 vs 独立端点）；agent permissions 评估链是否覆盖工具调用路径
  - 状态：`[ ]` ① ✅ **已修 76f337f5（2026-08-19）**——端点验证可用（204 + session.agent 持久变化 + SSE session.agent.selected 广播，带/不带 directory 头均生效）；实现 switchAgent（promptAsync 发送前显式切换，同 switchModel 模式）+ **resolveAgentId 显示名→id 解析**（E2E 发现的真 bug：listAgents 映射 name "Plan"，端点按 id "plan" 区分大小写匹配，原样发送 → execution.failed "Agent not found"；目录大小写不敏感双向匹配 + @Singleton 缓存 + 失败自适应重拉）。E2E 全链路：UI 切换 → `agent=plan (from=Plan)` → execution.started→succeeded → 服务器回复 agent=plan；②③④ 已归档为方法论。**2026-08-21 结案勾选**（唯一 App 侧缺口①已修；服务器现已升至 beta-17728）

- [x] **新增 P2：StrictMode 首轮走查发现——主线程 IO 批次（165 条，2026-08-19 c3078b41 采集）——①②③全部终态（48ae416f + ae40b014）** `perf` `main-thread`
  - 背景：#106-2 接入 StrictMode 后首轮模拟器走查（导航/滚动/进会话/设置，19:23-19:27）捕获 165 条违规，100% 主线程（PID=TID）。证据 /tmp/verify-strictmode/violations.txt
  - ① **SecretCipher 主线程 AndroidKeyStore 解密 125 条（76%）** ✅ **2026-08-19 已修 48ae416f**：解密记忆化（ConcurrentHashMap，密文为 key，encrypt 清空/失败不缓存）+ servers flow flowOn(IO)。同路径走查对照：SecretCipher 违规 **125→0**，总违规 **165→15（−91%）**，90s+20s 会话停留窗口零违规（上轮每 ~5s 爆发），零 FATAL 无回归（证据 /tmp/verify-p2cipher/）。原描述：SecretCipher.decrypt（SecretCipher.kt:62）← ServerDataStore.withDecryptedPassword（ServerDataStore.kt:184）——CustomViolation 75（KeyStore update/finish/abort）+ DiskRead 25（getKeyEntry/createOperation）+ DiskWrite 25。**不止启动期：会话界面存活期每 ~5s 周期性爆发一组 6 条**（密码被反复解密）——低端机聊天 jank 潜在源。方案方向：解密结果内存缓存（限时）/ withDecryptedPassword 调用方全部 IO 化/解密一次后持有
  - ② **启动期语言镜像 SharedPreferences 读** ✅ **2026-08-19 已修 ae40b014（5→0）**：① OpenCodeApp.attachBaseContext 后台预热 locale_prefs；② LocaleUtils.readStoredLanguagePermitted 统一入口——allowThreadDiskReads 显式声明 #136 设计读意图（防设计读永久刷噪音）；③ MainActivity:121 包裹外重复直读一并收口（E2E 复验发现）。终验：getStoredLanguage/applyAppLanguage 帧 logcat 全程为零；系统 locale=en-US 下界面仍中文（镜像生效强证据）阻塞 DataStore 读——单次最重 187ms 主线程启动延迟。方案方向：语言镜像（#136 reconcileLanguageMirror 已有）attachBaseContext 优先读同步快照（SharedPreferences 镜像）
  - ③ **启动期其他一次性读盘** ✅ **2026-08-19 已修 ae40b014（3→0）/ 评估不修（NetworkModule）**：crash_notify prefs 读移入 IO 协程（3→0）；NetworkModule.provideHttpClient 类加载 ZipFile 读（Ktor/OkHttp/slf4j ServiceLoader，×8-10 IO 粒度波动）评估不修——一次性框架行为，runBlocking 包裹 DI provider 属反模式，维持观察
  - 泄漏类（Closeable/Activity/SqlLite）0 条——VmPolicy 检测器无信号。**条目终态：同型走查 165 → 10（−94%），余量 100% 为有意保留的 NetworkModule 框架类加载；两次冷启动复验无竞态抖动**
  - 工时：①②③已完成（48ae416f + ae40b014） | 难度：中 | 涉及：SecretCipher/ServerDataStore/LocaleUtils/MainActivity/OpenCodeApp | 优先级：P2 ✅ 2026-08-19 完结

- [x] **新增 P3：Android Lint 存量清偿——已完结（errors 60→0：67dc50e4 散点 + 7a92fd1f 批量）** `lint` `tech-debt`
  - 背景：#106-6 开发版 lint 门禁——新增 error 卡发版，存量 59 errors/163 warnings/11 hints 由 app/lint-baseline.xml 豁免（不阻塞但持续可见）
  - 构成：**LocalContextGetResourceValueCall ×53**（LocalContext.current 资源读取 → stringResource 化批量重构，量大需专场）；RestrictedApi ×3（MainActivity.dispatchKeyEvent——按键分发有意使用，需 @SuppressLint 或重构）；SuspiciousIndentation ×1（NavGraph.kt:193）；SuspiciousModifierThen ×1（AmoledCard.kt:126 隐式接收者捕获）；JavascriptInterface ×1（CodeWebView.kt:227——**疑误报**：SelectionBridge 两方法均有 @JavascriptInterface 注解，源码目检确认，lint 对 apply 作用域解析混淆）
  - 顺带已修：DebugLogger.flushMediaStore NewApi 误报（@RequiresApi(Q)，60→59）
  - 方向：53 条批量场次优先；散点逐个判断真伪（误报 @Suppress + 注释说明）
  - **2026-08-19 第一批（67dc50e4）：散点 6 条全消，59→53**——① NavGraph 缩进错乱真实修复；② AmoledCard 冗余 then() 等价简化；③ RestrictedApi ×3 有意使用（终端按键拦截）@Suppress+理由；④ JavascriptInterface 误报（SelectionBridge 已注解）@Suppress+理由。baseline 重生成 + 门禁复跑通过 + 单测绿 + 冒烟导航 FATAL=0。**剩余 53 条 = 100% LocalContextGetResourceValueCall（LocalContext 资源读取 → stringResource 化）**
  - **2026-08-19 第二批（7a92fd1f）：53 条批量全消，errors→0 完结**——10 文件 lambda/回调内 context.getString hoist 组合层 stringResource（带参格式串 hoist 模板 + .format() 保留 locale 占位符次序）；ChatScreen 遵循编辑协议；lint 重生成 **0 errors**（baseline 仅剩 warnings）+ 门禁通过 + 单测绿 + E2E 文案铁证（建目录 Toast '已创建：~/srt2x' 含路径参数格式化正确 / /undo snackbar / 工具卡复制 / 诊断屏，FATAL=0，证据 /tmp/verify-strres/）
  - 工时：~1-1.5d | 难度：低-中 | 优先级：P3（门禁已开，存量只影响报告噪音）

- [x] **新增 P2：空会话中权限卡/提问卡不渲染（ChatEmptyState 整块吞掉 ChatMessageList）——已修 a4862397** `ui` `chat`
  - 发现（2026-08-19 权限卡 E2E）：空会话收到 PermissionAsked——App 事件链全部正常（SSE recv → dispatch → PermissionEventHandler pending，logcat 铁证），但卡片不显示。根因：ChatScreen 内容分支 messageState.messages.isEmpty() && !isLoading → ChatEmptyState **替换**整个消息区，而权限/提问卡是 ChatMessageList 的 LazyColumn item——空会话永远渲染不到
  - 复现：新建空会话 → 服务器发出 permission.asked（或 question）→ 无卡片；同会话注入任一消息后触发 → 卡片正常渲染（绕过实证）
  - 影响：真实场景（用户新建会话发首条消息、agent 首轮就要权限/提问）卡片不可达——permission 3 分钟无人应答超时
  - 方案：空分支条件收紧（有 pendingQuestions/pendingPermissions 时仍走 ChatMessageList）或空态与卡片区叠加渲染
  - **2026-08-19 修复（a4862397）**：空态分支条件收紧（pending 非空走 ChatMessageList）。E2E 双向验证：空会话挂起 ask → 卡片渲染 → 拒绝 → 空态正确回归；live 触发（原始失败路径）同验证 + 非空会话回归。**顺带修复 auto-approve 空名伪命中**：live ask 2ms 内被吞的根因是上轮 always 确认在空名 ask 上存了 toolName="" 规则（评估端点事件不带 permission 显示名）——savePermissionRule 空名守卫 + matches 双端空名防御（历史遗留规则即刻失效，无需迁移），单测 +2。证据 /tmp/verify-permcard/emptyfix_*.png
  - 工时：~2h | 难度：低 | 涉及：ChatScreen 空态分支 | 优先级：P2 ✅ 2026-08-19 完结

- [x] **新增 P3：App「自动允许所有权限请求」开关测试后遗留 ON——已完结（备忘目的达成）** `process`
  - 发现（2026-08-19 权限卡 E2E）：2026-08-19 上午验收明确关闭过该开关（DataStore 0x00 验证），但本轮 E2E 时又为 ON（毫秒级自动应答 always 导致卡片不显示，排查消耗两轮）。可能是下午某 E2E 子代理重新打开未还原
  - 处置：本轮已重新关闭。登记目的：后续 E2E runbook 若依赖权限卡显示，需先检查该开关状态（设置 → 自动允许所有权限请求）
  - **2026-08-19 完结**：备忘目的已达成——空会话卡片修复轮与终局回归均按此检查（开关保持 OFF）；且根因侧的 auto-approve 空名守卫已随 a4862397 落地，误应答面收窄
  - 工时：已处置 | 优先级：P3（流程备忘）✅ 完结
