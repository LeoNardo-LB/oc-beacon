# p3-quad-research（2026-08-24）

> 状态：执行完结（#161 闭卡 / #168 release 实测证伪闭卡 / #184 修复待验证 / #185 闭卡 / #209 修复待验证（插桩补跑已于 #210 修复后完成）/ #210 已修复转待验证——见文末「#210 修复执行」节）
> 来源：用户指令「开始调研 161 185 184 168，委派 subagent 去调研，详尽调研」
> 方式：4 个独立 subagent 并行深调（每卡一 agent），主会话交叉汇总；调研期间仓库零改动（只读）

## 调研范围与委派

| 卡片 | 主旨 | subagent | 关键核对点 |
|------|------|----------|------------|
| #161 | 离线顶栏 context 圆环隐藏 | 已委派 | showContext 判定链现状；落库方案与新鲜度权衡；~2h 复估 |
| #168 | 慢拖残余 ~18ms 偶发尖刺 | 已委派 | 测量口径考古；draw/input 相位静态分析；只读复测（禁装包）；debug 伪影裁决 |
| #184 | 未读水位线 globalMax 跨服务器混合 | 已委派 | ⚠️ #171 后 markAllSessionsRead 已迁 UnreadBadgeService 且持久化已 per-server——病灶是否仍在需重查 |
| #185 | V1/V2 god-client 拆解（显式不做） | 已委派 | 72/84 方法数与 78 决策点全量复测漂移；重开条件清单 |

## 主会话先期事实（委派前锚定）

- 设备 e69a99d8 在线；dev flavor 已装含 #206/#207 修复（用户待验收 #207）→ #168 复测硬约束：禁 install/uninstall（perf-session-scroll.sh 会构建+装包，禁用）、禁发消息/建删会话。
- #184 现状锚点：`UnreadBadgeService.kt:188-195` `globalMax = _lastCompletedReplyTime.value.values.maxOrNull()`；`SettingsDataStore.kt:463-466` `allReadKey(serverId)` per-server 持久化 + maxOf 单调保护——卡片所述「SessionListViewModel:423-430」已过时，存储层已带 server 维度。
- #185 现状锚点：门面 7 个（journal 行 113：SessionApiImpl 23 / ProviderApi 13 / MessageApi 12 / FileApi 12 / SystemApi 8 / TerminalApi 6 / 第 7 个待 agent 补全）。

## 调研结果

（待 4 个 subagent 报告回收后填写）

## #161

> subagent 报告全文已回收（2026-08-24）；结论：**维持登记，现在不做**。

### 主张核对（全部成立，无证伪点）

| 主张 | 现状 | 证据 |
|------|------|------|
| showContext 要求 contextWindow>0 且 lastContextTokens>0 | ✅ 判定在 Composable（非 ChatViewModel） | ChatTopBar.kt:105-107 |
| contextWindow 依赖服务器级 provider catalog REST，离线全败 | ✅ **比登记更彻底**：生产代码从不写 tracker.contextWindow（全库仅 2 处 tokenStatsTracker.update：ChatViewModel.kt:619/698，均不含该字段）→ 唯一生产分母来源是 catalog 查表；_allProviders 是实例级 StateFlow（每 ChatViewModel 重建、初始空），仅 loadProviders() REST 成功才填充（进会话必调 ChatViewModel.kt:755）。REST：V1 GET /config/providers（V1ApiClient:543-547）/ V2 GET /api/provider + /api/model（V2ApiClient:728-745）→ ServerRepositoryImpl.loadProviderCatalog（:89-111，contextWindow = model.limit?.context ?: 0 @:102）→ ModelConfigDelegate.loadProviders（:246-260） | ModelConfigDelegate.kt:58/90/218-222/246-260 |
| 无本地持久化 | ✅ Room 仅 5 表（cached_messages/cached_parts/logs/archive_buckets/pending_messages）无会话元数据表；DataStore 无 contextWindow 键 | OcBeaconDatabase.kt:10-19 |
| 「568-571 注释声明可接受」 | ✅ 注释漂移至 608-612，逐字未变（git d4601004 证实登记时点 565-569） | ChatViewModel.kt:608-612 |
| 消息/统计行离线完好 | ✅ cached_messages.payload 存完整 Message JSON（含 tokens），冷启动 seed :77-96 | CachedMessageEntity.kt:8-27；ChatRepositoryImpl.kt:72-96 |

补充机制事实（卡片未展开）：**离线时分子可用、唯分母缺失**——lastContextTokens 走 Room 消息流（ChatViewModel.kt:676-712 快照 collect，:690-692 input+cache.read），非 SSE/REST 直连。

### 工作量复估：3.5-4.5h（卡片 ~2h 低估约一倍）

推荐方案 A（DataStore per-server map，成熟先例 sessionReadTimes）8 步明细：SettingsRepository +2 方法 15min / SettingsDataStore 键+读写 25min / Impl 委托 5min / **ModelConfigDelegate 14 源扩展（combine 加 persisted map 为源——:102-103 注释明示缺位会重蹈任务面板 R1 覆辙；链尾 ?: persisted[sid] ?: 0；contextWindow>0 时 fire-and-forget 去重写入）40-60min** / 单测 60-90min / i18n **零改动**（圆环纯数字）/ 编译+全量单测 20min / 真机离线红绿（在线落库→杀 server→force-stop 冷启→圆环+详情弹窗→恢复在线验证优先级）45-60min。ChatViewModel/ChatScreen/ChatTopBar 零改动。

方案 B（Room session_meta 表 v4→v5 + Migration）不推荐：4.5-5.5h 无额外收益。

### 新鲜度权衡（关键结论）

- 分子不陈旧：离线时 Room 实时计算=离线时刻真实值。
- 分母基本不陈旧：contextWindow 是模型常数，离线不可能换模型；唯一陈旧窗口=在线换模型写点竞态漏写，下次在线自愈。
- 「离线显示 40% 实际 90%」基本不成立——反而现状「消息全可见但圆环消失」才是语义不一致（此为若做时的论证依据，非现在做的理由）。

### 处置（建议，待用户裁决）

**维持登记**。理由：条件卡（仅当用户期望离线可见才做）当前无诉求信号；收益边界小；实际成本约卡片 2 倍。

**顺带发现（只登记不现场修）**：TokenStatsTracker.TokenStats.contextWindow（TokenStatsTracker.kt:18）是生产死字段——全库无写点恒 0，仅测试写入；ChatStateAggregator.kt:124 映射进 TokenStatsState.contextWindow 但 ChatScreen 消费的是 modelConfig.contextWindow（ChatScreen.kt:624）。若做本卡可顺手清理；否则可另立微小清理卡。

### 闭卡（2026-08-24 用户裁决）

> 用户裁决：「应该不是什么问题，不用修复了，直接关闭」——机制主张经调研全部成立（非证伪），但用户判定该离线隐藏为可接受行为，条件卡条件（期望离线可见）不成立，不修复，闭卡迁移。

- [x] **#161 离线时顶栏 context 圆环隐藏** `data` `ui` ——**已闭卡（用户裁决可接受，不修复）**
  - 机制结论（调研确认）：showContext 双条件判定（ChatTopBar.kt:105-107）；contextWindow 唯一生产来源=provider catalog 查表（tracker.contextWindow 生产死字段→已登记 #209 独立清理）；Room 无会话元数据表；代码注释（ChatViewModel.kt:608-612）立场即「可接受」——与用户裁决一致
  - 若未来需要离线可见：DataStore per-server map 方案草图与新鲜度权衡见本节上文（3.5-4.5h 估）

## #168

> subagent 报告全文已回收（2026-08-24）；结论：**维持挂起，不现在花 2h；把 release 侧 5 分钟抽查搭到下一次例行 devRelease 真机验收，据其结果直接闭卡或升级**。真机只读复测已完成（两轮，硬约束全守：零装包、零消息、零会话变更）。

### 测量口径考古

- 四通道分工：dumpsys gfxinfo framestats（120 帧环形缓冲逐帧全相位）/ Perfetto atrace（slice 级归因）/ **PerfMon 应用内**（Window.addOnFrameMetricsAvailableListener，ChatPerfMonitor.kt:109-115 七相位分解；JANK=total>2×预算(16.7ms)+250ms 限频 :133-138）/ A/B gfxinfo（idle_frame 假设检验，已否证 ahead=0 定案 ScrollSpeedPrefetchStrategy.kt:52-55）。
- 卡片数值口径：「~18ms」=PerfMon JANK 门槛 2×8.33ms 的 17-20ms 档事件；「draw 4-8ms + input 3-5ms，12 轮 10 条」=JANK 事件相位分解（a6156cdf commit msg）；**250ms 限频 → 真实 ≥17ms 帧远多于 10 条**（gfxinfo 同期 ≥17ms 占比 2.2-2.3%，~80 帧/3700）。
- PerfMon 是 BuildConfig.DEBUG 门控（MainActivity.kt:174），release 侧数值只能来自 gfxinfo——「release p95 7.9ms」是 gfxinfo 口径，**release 侧 ≥17ms 帧率从未实测过**（「release 无感知」是推断）。
- 系统 janky% 不可信（本机 SF 宽松预算 13.7ms 三层）；GKD 关闭前提三期一致。

### 相位机制静态分析

- **input 3-5ms 主源**：拖动跨 item 边界时下一个 chunk 的同步组合+测量发生在 LazyListState.scrollBy 调用栈内 → 计入 INPUT 桶（机制性，release 同在但被 R8/AOT 压缩）。
- **draw 4-8ms 主源**：Compose 推迟 measureAndLayout（AndroidComposeView.dispatchDraw，research §1.3）→ 「draw 桶」实为「新 chunk 测量+布局+display list 首录」合桶——**直接证据：复测 gfxinfo layout 桶 p99 仅 0.2ms 而 draw p50 1.9ms**。overdraw 非主因（GPU p50 2ms 很低）；PerfHud 观察者效应可忽略（Run A/B 差 0.3ms）。
- **debug 税 47% 已实证**（p95 15→7.9 等）；devDebug ~18ms 按税折算 release ≈10-12ms（超预算但 <2×，不构成 JANK 级感知）。

### 复测结果（Kotlin 130 条长会话，swipe 600 500→600 1600 800 ×12 交替，对齐 runbook 铁律）

| 指标（8.33ms 口径） | Run A 无 PerfMon（982 帧） | Run B PerfMon+HUD（964 帧） |
|---|---|---|
| p50/p90/p95/p99 | 9.4/14.5/19.6/32.9 | 9.7/14.9/19.0/34.9 |
| ≥17ms | **6.52%（64 帧）** | **6.85%** |
| input p50/p99 | 1.83/6.32 | 1.80/6.16 |
| draw p50/p99/max | 1.91/5.83/12.75 | 1.88/6.33/16.47 |
| PerfMon JANK | — | **22 条/12 拖**（卡片时代 10 条同口径） |

双通道互证一致（差异 ≤0.5ms 噪声级）。**三个新发现（修正卡片前提）**：
1. **anim 桶主导最差帧**（≥8ms anim 帧 26 个，最差 41-51ms；PerfMon JANK 里 anim=20-51 反复出现）——F5 消掉的是 ChatScreen 全屏重组，**per-chunk 重组仍在**且当前是最大单相 → 卡片「残余只剩 draw/input」前提过时，深挖优先级应改 ①anim chunk 重组 ②迟启动帧。
2. **迟启动帧类仍存**：18-19 帧 unknown-delay 20-50ms 而帧内各相位≈0（08-22 c7ffbfa9 归一化后台化修掉一类后仍有别的帧外阻塞源：注入节奏/binder/GC/Room 候选）。
3. **尖刺率强会话相关**：本会话（教程型，代码块+表格多）≥17ms 6.5-6.9%，约为 AB 会话期 2.2% 的 **3 倍**——「偶发、量少」的描述依赖会话内容，单会话数据不可外推。

### 深挖方案（若未来做，~2h，前提修正后）

Step1 Perfetto 相位标注 trace 30min（设备内置 perfetto 零装包，debuggable Compose trace section 可见；≥17ms 帧逐个归到 slice）→ Step2 同法测 AB 会话消内容混杂 30min → **Step3 release 口径尖刺率抽查 30min（唯一能直接裁决「debug 伪影」的证据；需装 devRelease=用户授权项，或搭下次例行 devRelease 验收顺带跑）**：release ≥17ms <1% 且 p99<16ms 即证伪闭卡；率 ≥1% 或感知复报 → 升级 P2（归因起点现成）→ Step4（可选）chunk 首组录 A/B：lambda 版 graphicsLayer + 慢拖档预组合 0→1 重评。不建议 Layout Inspector（开销大无相位归因）与 ChatPerfMonitor 加打点（缺 slice 级非桶级）。

### 处置（建议，待用户裁决）

维持 P3 挂起；release 抽查搭车下次例行 devRelease 验收（成本≈0），据结果二选一闭卡/升级。**不满足现在直接证伪闭卡**（anim 主导+迟启动帧+会话相关性三发现使「debug-only 伪影」仍是推断非实测）；也不构成升级（用户日常跑 release/beta，感知侧无证据）。

原始数据已归档：/persistent/home/leo-tkp/perf-evidence/r168-20260824/（runA/runB framestats + perfmon.log，1.1MB）。

## #184

> subagent 报告全文已回收（2026-08-24）；结论：**病灶仍在（两个纯内存/值域缺陷）+ 场景可达 → 不可证伪；修复在 #171 后已变便宜（~1h）→ 建议「便宜修复值得现在做」，待用户裁决**。

### 背景事实纠正（先于结论）

- 主会话委派时判断「#171 后持久化已 per-server，存储 schema 已变」——**部分误判**：①per-server **已读**持久化（allReadKey/readTimesKey）2026-08-09 commit 50b2af95 就有，非 #171 引入；②#171（a048b1ea/941f17f8，2026-08-21 落地）做的是读写收编单点+事件类型化，未动 SettingsDataStore；③水位线持久化至今仍是**全局单键** LAST_REPLY_TIME_KEY（SettingsDataStore.kt:492-507）——#171 Q6「不动存储 schema」前提仍成立。
- 卡片锚点过时：markAllSessionsRead 已从 SessionListViewModel:423-430 迁至 **UnreadBadgeService.kt:188-197**（SessionListViewModel:428-431 只剩一行转发）。

### 病灶定位（比卡片更精确：两个缺陷，都在写入侧）

_lastCompletedReplyTime = Map<sessionId, Long>（key 无服务器前缀，value=服务器域 completed 毫秒戳，唯一例外 SessionError=客户端 now）。多服务器条目**会共存且不可逆**（同时连接各写各的；曾用过的第二台服务器条目持久化在全局键，每次重启 seed 全量载回；唯一删减是 removeSession；clearForServer/clearAll 均不触本服务）。

判定链读取侧已隔离（mergedReadTimes/allReadAt 均 per-server，展示只显示本服务器会话）——**污染只经 markAllSessionsRead 的写入进来，两处**：

1. **错杀（内存广播溢出）**：globalMax = values.maxOrNull() 取全 map 最大值（:189）→ _justRead 广播写入**本服务器全部会话 + 别服务器会话的已读位**（:190-192；mergedReadTimes 合并全局 _justRead → 溢出可见）。进程存续期错杀别服务器红点，重启复活（持久侧未被写）→ 不一致。
2. **漏杀（allReadAt 值域污染）**：别服务器域大值写进本服务器 allReadAt（:194 + SettingsDataStore.kt:464-468），maxOf 单调保护使污染值不可回退——本服务器后续完成消息需域戳超过污染值才亮红点，形成「未来窗口漏报」。

### 双场景数字推演（A 快 5min / B 慢 5min，map={a1→10:03, b1→09:57}）

- **停在 B 点一键已读**：globalMax=10:03（来自 A 的条目）→ a1 被错杀（切回 A 红点灭，重启复活）+ B 侧 allReadAt=10:03 → **真实 10:08 前约 8 分钟 B 新完成消息红点全吞**（B 域时钟到 10:03 前）。
- **停在 A 点一键已读（B 有未读）**：错杀对称（b1 广播错杀）；A 侧 allReadAt=10:03=A 域正确值 → 无持久污染。
- 规律：**错杀方向永远对称**（map 混有别服务器条目即触发）；**持久化污染只在「快钟条目混入慢钟 allReadAt」时发生**。「点了没反应」型漏杀数学上不可达（globalMax ≥ 本服务器全部条目）。

### 场景可达性：多服务器同时连接是明确设计功能

ServerDataStore 存任意份配置（含 autoConnect 开关）；autoConnectConfiguredServers 连接**所有** autoConnect=true 服务器（OpenCodeConnectionService.kt:352-357）；activeServerIds 是 Set（ConnectionLifecycleCoordinator.kt:76-77）；EventDispatcher 专为多服务器 SSE 并发做会话所有权去重（:226-228）。**触发条件 = ≥2 台服务器 ever 使用 + 时钟偏差 + 点击一键已读**（不要求同时使用；NTP 同步 <1s 不可感知，自建/裸机分钟级可见）。

### 修复成本（#171 结构性红利：单点后改动极小）

**方案 1（推荐）：调用方作用域化 globalMax——零 schema 零迁移，0.5-1.5h**
- UnreadBadgeService.kt:188-197 签名加 sessionIds: Set<String>，globalMax 改 filterKeys 后取 max，_justRead 只遍历过滤后键集（~5 行）
- SessionListViewModel.kt:428-431 传参（serverSessionMap 已在 dataFlow 手里 :319；照 :180-181 Eagerly stateIn 模式缓存本服务器 sessionIds 快照，~5 行）
- SessionListUnreadTest 补双服务器互不污染用例（30-45min）
- 同时消灭两个缺陷；#171 收编单点正是「便宜」的来源（否则要改 EventDispatcher/SessionListViewModel/SettingsRepository 三处）

方案 2（水位线存储加 server 维度）不推荐：历史条目无法归属服务器（serverSessions 不持久化），schema+归属重建，正是 arch-review journal:273 估的贵价。

**附带发现**：SessionErrorOccurred 客户端 now（EventDispatcher.kt:84-89）也进 globalMax——设备时钟是第三个时钟域，单服务器用户也可能被 allReadAt 污染（程度=设备与服务器偏差）。方案 1 的 filterKeys 顺带把它限制在本服务器会话集内；同服务器内错误戳语义是 research/11 P1 故意例外，不动。

### 处置（建议，待用户裁决）

**便宜修复值得现在做（方案 1，~1h）**；若维持登记则更新卡片描述（锚点迁移 + 机制改写为两缺陷表述）。**不建议证伪闭卡**。

## #185

> subagent 报告全文已回收（2026-08-24）；结论：**维持显式不做**（登记数字全部零漂移复现，决策前提经 3 天 267 commits 考验反而更扎实）。

### 数字复测对照（登记 2026-08-21 @fc251f41 → HEAD 715ee119）

| 指标 | 登记 | 实测 | 漂移 |
|---|---|---|---|
| V1ApiClient 方法 | 72 | 72 | 0 |
| V2ApiClient 方法 | 84 | 84 宽松 / 82 严格公开（1 private+1 局部函数） | 0 |
| 7 门面分发 if | 78（SessionApi 23/Provider 13/Message 12/File 12/System 8/Terminal 6/**Shell 4**） | 78 逐门面完全一致 | 0 |
| SSE 第 79 点 | SseConnectionManager:323 | :324（纯改名提交致行号+1，逻辑零变更） | 内容 0 |
| main 全域 conn.apiVersion.isV2 | 79 | 79（与门面+SSE 精确互证） | 0 |

方法集换血 4 组（净 0）：abortSession→interruptSession、summarizeSession 并入 compactSession、updateSession→renameSession、removeProviderAuth→removeProviderCredential——全为术语/单入口统一，非功能增长。#206 V2 abort 合成（f73621c1）落在 V2Mappers，未触碰 god-client、未新增分发点。

**「22 测试文件」核实**：直接引用 V1/V2ApiClient 的测试仅 **5** 个（1 个纯注释）；构造门面 Impl 2 个；宽松文本引用 10/199（登记日同口径 9/184）。「~22」是 #172 方案 B（seam 翻转）的**行为影响面估算**非文本可复现口径；其最大成分（PaginationDelegate 25 处版本引用）已被 #172 收编坍缩为 1 处注释。**纯拆轴今天真实重写面 ≈ 4-5 文件**——数字口径澄清但不改变决策方向（成本论据比登记时更弱，即更没必要拆）。

### 架构现状证据

- 版本决策点全量：79（门面+SSE）+ PaginationCursorPolicy:91 工厂读点（#172 设计内唯一收编点）+ ServerCard:101 展示徽章（豁免）。**UI/Domain 数据路径 isV2 绝迹**（ServerCapabilities 能力位替代；isV2Server 已删）。
- 门面消费面：ApiModule @Binds×7 + 8 个 repository 文件，conn 逐调用参数传入——seam 唯一。
- 9 文件 3 天仅 12 commits（全改名），非热点。
- god-client 内部：V1 6 域段；V2 14 段含 4 个 supplementary 追加段（局部性退化，纯组织问题）；V1∩V2 同名 70/72=97% 镜像；V2 独有公开方法 12 个全部 08-19 前引入。
- 78 处中 76 单行纯分发，仅 MessageApi:191/215 为 block（#130 form 语义映射=真行为适配点）。

### 风险还原（缓存式适配器版本竞态）

当前逐调用 resolveConnection 重读版本 → 决策与 URL 用同一快照，无第二真相源。缓存适配器捕获版本后遇服务器升级/探测翻转即复现 #132 已实证事故形态（降级→SPA fallback HTML 崩溃+SSE 假死，且藏内存更难查），修复需 keyed 失效重建=新竞态面。SSE 是唯一「连接时选定一次」的正当例外。未来若拆：**轴 B partial interface（类实现域接口，门面注窄接口，重写 4-5 文件）< 轴 A 拆文件 < 轴 C 缓存适配器（否决形态）**。

### 重开条件清单（建议追加进 #185 卡片 3-5 行作触发器）

1. V2ApiClient 突破 ~100 方法/2200 行（V2 增长再启动：差异清单 4 项「评估中」能力 ≥2 立项）
2. V3 出现（两轴变三轴）
3. V1 EOL（届时是删不是拆——V1 真机 E2E 08-23 刚复验通过）
4. upstream #146 PR 批量改端点形状（预计走 mapper/policy 层，不直接触发）
5. supplementary 段增殖致多 agent 编辑冲突现实化
6. 测试基建改 Hilt 注入后轴 B 成本趋零

### 处置（建议，待用户裁决）

**维持显式不做**。可选小活（不违反定案）：V2 supplementary 段回填主段；把重开条件清单 3-5 行追加进 #185 卡片。

### 闭卡（2026-08-24 用户规则裁决：「做了后对后续维护与开发是否更有利？如果是的话则做」）

按用户规则做利弊评估（今日实测指标：V1 942 行 / V2 1778 行均低于 ~2200 触发线；domain/ui 层 god-client 引用 **0 处**——seam 无泄漏；测试直引 5 文件）：

| 维度 | 不拆（现状） | 拆解（轴 B partial interface） |
|------|--------------|--------------------------------|
| 新增端点（最高频维护动作） | 3 处改动同层：V1 方法 + V2 方法 + 门面 1 分支 | 4 处跨文件：domain 窄接口 + V1 impl + V2 impl + 门面接线 |
| 认知/导航 | 域分段清晰（V1 6 段/V2 14 段），IDE 直达 | 跨文件跳转增多 |
| 测试 | 0 | 重写 4-5 文件 |
| 运行时 | 无差异 | 无差异（轴 C 缓存适配器有 #132 实证竞态形态，否决） |
| 收益 | — | 仅文件变小（当前未达触发线） |

**结论：不满足「更有利」条件——日常开发成本反而上升（3 处→4 处），无泄漏要堵、无运行时收益。按用户规则：不做。**

- [x] **#185 V1/V2 god-client 拆解（终局债务，显式不做）** `refactor` ——**已闭卡（用户规则评估：不更有利 → 不做）**
  - #172 定案维持；零漂移复审 + 6 项重开触发器见本节上文；可选微活（supplementary 段回填）评估为纯 churn 无收益，谢绝

## 执行记录（2026-08-24 用户裁决后）

### #184 修复（commit 7bd04c11）

- `UnreadBadgeService.markAllSessionsRead(serverId, sessionIds)`：签名加 `sessionIds: Set<String>`，`globalMax = filterKeys { it in sessionIds }.values.maxOrNull()`，广播只遍历过滤后键集——错杀（广播溢出）与漏杀（allReadAt 值域污染）双修复；SessionError 客户端 now 第三时钟域顺带被限制在本集内
- `SessionListViewModel`：新增 `serverSessionIds`（getServerSessionsFlow map 本服务器集，Eagerly stateIn——一键已读为事件驱动调用需随时可用）传参
- 单测 +3：模块级「scopes max and broadcast to server session set」（双服务器 a1=10_000/b1=4_000，停在 B 一键已读 → 广播仅 b1=4_000、持久化收到 4_000 拒绝 10_000）+「no-op on empty set or empty watermark」；链路级「scoped broadcast keeps other-server session unread」（mergedReadTimes + isUnread 断言 a1 仍未读）
- 定向测试类全绿；全套件 1919 绿（见 #209 节）

### #209 修复（commit caea2b30）

- 删 `TokenStatsTracker.TokenStats.contextWindow` 字段 + `ModelConfigDelegate` 恒假优先分支（解析链只剩 session.model → catalog 查表 → ?: 0）+ `TokenStatsState.contextWindow` 与 `ChatStateAggregator:124` 映射死链（ModelConfigState.contextWindow 保留——那是真实消费路径 ChatScreen.kt:624）
- androidTest test3 改造：fake 会话挂 `model=SessionModel("ctx-model","ctx-provider")` 走真实 catalog 路径（原用 tracker 死字段注入捷径）
- **存量发现①**：FakeDomainModule 缺 `bindPendingMessageRepository`（主图 08-18 后新增）→ androidTest 源集自 08-18 起编译破损从未被发现——补绑真实 Impl（Room DAO/clock 由未替换的 DataModule 提供）
- delegate 级单测 ×4（`ModelConfigContextWindowTest`：catalog 命中 128000 / 无 model 0 / catalog 查不到 0 / 仅分子不伪造分母）；全套件 --rerun 1919+4=1923 绿
- **存量发现②→#210**：编译修复后设备插桩挂死（详见 #210 节）——#209 的插桩级验证被阻塞，以 delegate 单测 + 编译 + 全量单测代偿，待 #210 修复后补跑 test3

### #210 登记：ChatScreen 渲染类插桩 waitForIdle 挂死（存量）

- 现象：ChatInteractionTest 全类运行（11:39 起，run started 7 tests）卡在 interruptSession_callsInterruptApi 无进展；单跑 contextUsageBar test（12:02 起）同样无进展——两者均在 renderChatScreen 后 waitForIdle 阶段
- 形态：进程活着、CPU 0.0%、24 线程 sleeping——安静型挂死，非动画饿死（PerfHud 已排除：需 debug_perf extra，测试未传）
- 取证受阻：debuggerd -j 需 root（HyperOS 无 root）；kill -3 SIGQUIT 痕迹不落 dropbox（仅 08-21 旧 ANR）——需 Android Studio 连进程取主线程栈
- 时间线：androidTest 最后成功产物 08-18 21:29；源集 08-18 起编译破损掩盖至今 → 回归窗口 08-19..08-24（ChatScreen 大改期：F5 滚动、堆积管线心跳 #176/#177、思考计时 #207 等）未二分
- 设备偶发干扰记录：11:35 测试运行中被 com.miui.home UninstallController 桌面卸载 dev 包（用户否认本人操作；11:29 另有一次 UTP 清理卸载）→ dev 本地数据被清（服务器配置/已读/收藏/标签；服务器数据无损），已用 debug intent 恢复配置路径验证、表单弹层正常

### 残留测试会话清理（2026-08-24，等待解锁窗口期间完成）

- `ses_fd01e1c9affe7ysrOnU6lH5sTa`（黎曼假设背景与证明——#208 取证期 Riemann 中断轮次残留）与 `ses_fd0274870ffemZ7FfBoBgGZum2`（伽罗瓦理论五次方程不可解性——E2E throwaway，此前记录称已删但服务器实测仍在）：双双 DELETE 204，直接 GET 404 确认，会话列表 id 消失 ✅
- 测试会话污染披露（真实会话，不删除，向用户报告）：`ses_fdeec5901ffe`（分片E2E 验收）被测试追加 Galois/Dedekind/Lebesgue 轮次；`ses_fd02eb5a7ffe`（两车相遇）被测试追加 cubic/spectral 定理轮次——均为 #208 取证期间为复现中断/历史场景向真实会话发的测试消息

### #168 解析器列映射校准（2026-08-24，等待解锁期间完成——裁决可信性前提）

- 本机 framestats 为 HyperOS 24 列扩展格式且首行为表头（实测 runA/snap1：0=Flags…17=FrameCompleted…），初版解析器按旧 16 列取列全错（6 帧 + 66e6ms 荒谬值）
- 修正后相位口径与 subagent 逐位对齐（input p50 1.83/p99 6.32、anim p99 20.55、draw p50 1.91 全部精确一致）；total 口径二选一实证：**FrameCompleted−IntendedVsync**（A）vs FrameCompleted−FrameStartTime（B）——A 精确复现基线（982 帧/p50 9.4/p90 14.5/p95 19.6/≥15ms 8.78%/≥17ms 6.53%·64 帧），B 系统性偏低（≥17ms 4.39%）→ 固化 A
- 解锁脚本 `/tmp/r168rel/unlocked-run.sh` 同步两修正：①输入法改 input text（type.sh 仅 a-z0-9,.，URL 的 :/ 与密码符号会被静默丢弃；禁令场景=手势返回流伪影，表单填充无返回手势，release 无 debug intent 替代——偏差特此披露）②脚本其余段语法校验通过

### #168 解锁自动执行武装（2026-08-24 Round 3）

- 解锁探测升级为自动执行 watcher：解锁事件本身触发 unlocked-run.sh（装 devRelease→配置→12 慢拖测量→parse.py 裁决→恢复 devDebug），EXIT trap 保证任何失败路径都装回 devDebug + debug intent 恢复配置
- 测量产物落 `/tmp/r168rel/RESULT.txt` + `/persistent/home/leo-tkp/perf-evidence/r168rel/`（parse.py/unlocked-run.sh 已持久化防重启丢失）；watcher 窗口 12h
- 脚本 SYNTAX_OK；三层防呆：锁屏中止（恢复 devDebug）、会话未找到中止（截图取证+恢复）、INSTALL 失败中止（恢复）

### #168 release 实测与闭卡裁决（2026-08-24 15:48-16:0x，用户在场配合）

**表单自动化三轮踩坑全记录**（release 无 debug intent，配置必须表单）：
1. ESC（keyevent 111）收键盘会把对话框一起关掉 → 去掉 ESC
2. 软键盘盖住保存按钮（tap 落键盘上）→ 单次 BACK 只收键盘再点保存
3. input text 的 URL 反斜杠转义在单引号内成字面量 → 不转义直接传
4. EditText 定位：label TextView 不可点；实际 4 框（0=名称空 1=URL 2=用户名预填 3=密码）——regex 需抓完整 node（text 与 bounds 同在 node 属性里）

**执行链**：devRelease 安装（miui-install 自动点穿）→ 表单分步填（URL/密码 8 位掩码逐步 dump 验证）→ 保存 → 连接 → 通知「始终允许」→ 服务器页点「会话」标签 → Kotlin安卓学习教程规划（130 条）→ reset gfxinfo → 12 次交替慢拖（600,500↔1600，800ms）→ 6 快照。

**裁决数据（720 帧，8.33ms@120Hz 口径，与 devDebug 基线同解析器）：**

| 指标 | devDebug 基线 | devRelease 实测 | 裁决线 | 判定 |
|---|---|---|---|---|
| ≥17ms 率 | 6.53% | 0.00%（0/720） | <1% | 通过 |
| p99 | 34.3ms | 8.5ms | <16ms | 通过 |
| p95 | 19.6ms | 8.0ms | — | — |
| p50 | 9.4ms | 6.0ms | — | debug 税复证（约 47%） |
| anim p99 | 20.6ms | 0.82ms | — | chunk 重组在 R8 下消失 |
| draw p99 | 5.83ms | 1.03ms | — | — |

**裁决：#168 证伪闭卡**（devDebug 尖刺系 debug 构建税放大，release 口径零 jank、p99 低于预算——用户日常 release/beta 完全无感知；「偶发 ~18ms 尖刺」在 release 不存在）。原始数据归档 /persistent/home/leo-tkp/perf-evidence/r168-release-20260824/（6 快照+RESULT）。测毕 devDebug 已恢复 + debug intent 配置还原（会话列表实证）。

### #168 执行记录（历史：解锁窗口前）

- devRelease APK 已构建（assembleDevRelease 3m33s，R8 minify）；解析脚本 `/tmp/r168rel/parse.py` 就绪（120Hz 8.33ms 预算，framestats 24 列，IntendedVsync 去重）
- **阻塞**：设备 PIN 锁——用户离开期间锁屏，MIUI 锁屏下任何 pm install 拒绝（USER_RESTRICTED）；且跨签名切换（devDebug→devRelease 需卸载重装，release keystore ≠ debug 签名）已执行卸载一步，设备当前无 dev 包
- **解锁窗口协议（~5 分钟）**：①唤醒+解锁（wm dismiss-keyguard 可过）②miui-install.sh 装 devRelease ③服务器配置：无 debug intent（BuildConfig.DEBUG=false）→ 首页「添加服务器」表单 + type.sh 输 URL/密码（脚本已验证纯 keyevent 可用）④进 Kotlin 会话 12 次交替慢拖 + 每 2 拖 framestats 快照 ⑤parse.py 汇总 ⑥≥17ms<1% 且 p99<16ms → 证伪闭卡；≥1% → 升 P2 ⑦pm uninstall + miui-install devDebug + debug intent 恢复配置（password 于 /tmp/ocbpw.txt，重启即失需重读 service.json）
- 裁决规则（用户已定）：真机测试后评估是否升 P2

### 四卡处置建议表（2026-08-24，待用户裁决）

| 卡片 | 调研判定 | 建议 | 若做的成本 |
|------|----------|------|------------|
| #161 context 圆环离线隐藏 | 机制主张全成立且比登记更彻底（tracker.contextWindow 生产死字段）；无用户诉求信号 | **维持登记** | 3.5-4.5h（DataStore per-server map 方案草图已备，零 i18n） |
| #168 慢拖残余尖刺 | 复测三新发现：anim 桶主导最差帧（per-chunk 重组仍在）、迟启动帧类仍存、尖刺率强会话相关（6.5%≈基线 3 倍）；release 侧尖刺率从未实测 | **维持挂起**；release 5 分钟抽查搭下次例行 devRelease 真机验收，<1% 即证伪闭卡、≥1% 升 P2 | 深挖 ~2h（Perfetto slice 归因 + release 抽查，前提方向已修正为 anim/迟启动） |
| #184 水位线跨服务器 | 病灶仍在但收窄为两纯内存缺陷（_justRead 广播溢出错杀 + allReadAt 值域污染漏杀）；场景可达不可证伪；#171 收编单点后修复大幅变便宜 | **便宜修复值得现在做**（方案 1 filterKeys 作用域化，零 schema 零迁移）——唯一建议动代码的卡片 | ~1h（UnreadBadgeService + SessionListViewModel 各 ~5 行 + 双服务器互不污染单测） |
| #185 god-client 拆解 | 数字零漂移复现（72/84/78，267 commits 考验）；「22 测试文件」口径澄清为 seam 翻转影响面估算，纯拆轴真实重写面 ≈4-5 文件 | **维持显式不做**；6 项重开触发器已录 | 若重开选轴 B partial interface（4-5 文件），非现在 |

### 顺带发现登记

- **#209（新登记）**：TokenStatsTracker.TokenStats.contextWindow 生产死字段——全库无写点恒 0（仅测试写入），ChatStateAggregator.kt:124 映射死链；若做 #161 可顺手清理，否则独立微小清理卡。
- #184 附带发现（SessionError 客户端 now 第三时钟域污染 globalMax）由方案 1 顺带缓解，不另立卡。

### 用户裁决点

1. **#184**：现在做 ~1h 修复（方案 1），还是维持登记？（两缺陷实测可达：曾配置过第二台服务器即永久满足前置条件）
2. **#168**：是否同意「release 抽查搭下次 devRelease 例行验收」的处置？
3. **#161 / #185**：维持登记/显式不做，是否认可？（卡片描述已按调研事实更新，状态未动）


### #210 修复执行：根因三连（环境×2 + 测试勘误×1）——插桩全绿（2026-08-24 下午批次）

**取证路径（jdb 活体取栈，无 root 可行）**：debug 构建进程 debuggable → JDWP 可用——`adb jdwp` 找 pid → `adb forward tcp:8700 jdwp:<pid>` → `jdb -attach localhost:8700`（linuxbrew openjdk@21 自带）。jdb 交互注意：`thread <id>` 在本进程报无效，用 `suspend` + `where all` 一次取全线程栈（会冻结进程，测毕 resume）。

**根因①（挂死本体）：MIUI DeviceGuard 拦截插桩 Activity 启动，startActivitySync 永久等待**

- jdb 全线程栈：main 线程空闲 nativePollOnce；**instrumentation 线程挂在 `Instrumentation.startActivitySync`（Instrumentation.java:633 Object.wait）← ActivityScenarioRule.before() ← AndroidComposeTestRule**——测试体（renderChatScreen/waitForIdle）从未执行，「挂点在 waitForIdle」的先验假设不成立
- 系统侧铁证（logcat 15:58:40.088-094）：`checkDeviceGuardStartActivityPermission: request is null` → `MIUILOG- Permission Denied Activity: cmp=…/HiltEntryActivity` → `Abort background activity starts from 10445` → `START u0 … HiltEntryActivity … result code=102`（START_ABORTED）；startActivitySync 对 aborted launch 无超时 → 永久 Object.wait = 安静挂死（0% CPU 全 sleeping 完全吻合）
- 触发链：am instrument 杀旧进程起新进程（无前台窗口）→ 首个 Activity 启动被 MIUI 判为后台启动 → 「后台弹出界面」权限未授予即拦。**权限随 08-24 #168 解锁窗口的 dev 包卸载重置**（journal 上文 283 行：pm uninstall + miui-install devDebug 跨签名切换）——非代码回归，08-19..08-24 窗口二分假设证伪
- adb 绕过全不可行：`appops set … android:bg_activity_start` 报 Unknown operation（HyperOS 无此 op）；无 localeConfig + `pm set-app-locales` 命令不存在——唯一通路是设置 UI 授权
- 修复（环境）：应用设置 → 权限管理 → 其他权限 → 后台弹出界面 → 始终允许（uiautomator 自动化完成；固化为 `scripts/miui-grant-bal.sh`，卸载重装后必跑）

**根因②（4/6 ComposeTimeoutException）：HiltEntryActivity 无语言覆盖，断言随系统 locale 漂移**

- 授权后首跑：interruptSession 挂死变失败——「No compose hierarchies」→ 再跑稳定为 waitUntil("Stop") 10s 超时；全类 4 失败（Stop/Permission Required/Awaiting your reply/contextUsageBar）
- 中途截屏 OCR：ChatScreen 渲染正常（"Chat"/"Generating…"/输入提示「提问…」中英混排）——输入提示是 zh 资源 → **HiltEntryActivity（测试 Activity）locale 完全跟随系统**
- 史实佐证：08-19 journal 44 行「系统 locale=en-US 下界面仍中文（镜像生效强证据）」——08-18 21:29 最后全绿时系统 locale=en-US，英文断言恰好成立；其后系统切回 zh-CN（androidTest 同期编译破损未跑）→ 今日修复编译后集中暴露
- 修复（代码，androidTest 源集零生产影响）：`HiltEntryActivity.attachBaseContext` 包装 en-US 配置（镜像生产 `LocaleUtils.applyAppLanguage` 模式），断言与设备语言解耦
- 顺带发现→**#211**：其余 androidTest 类（createComposeRule 族）同样依赖系统 locale=英文，未在本批修——见 backlog

**根因③（contextUsageBar 残留失败）：#209 test3 重写的 seed 错误（设备首跑勘误）**

- locale 修复后 interruptSession 通过，contextUsageBar 仍超时——该测试 #209 重写后首次上设备（此前被 #210 阻塞）
- 根因：`ModelConfigDelegate.kt:211` contextWindow 解析 = `allSessions.find { it.id == sid }`，sid 来自 sessionIdFlow（无导航参数 → ""），测试 seed 的会话 id=TEST_SESSION("test-session") → find 必空 → contextWindow=0 → ChatTopBar.kt:105 `showContext=false` → "50" 永不渲染
- 修复（一行）：seed 会话 id 改 ""（KDoc 注明勘误链）——catalog 路径（FakeServerRepository.catalogResult → SelectModelUseCase.loadProviders → combine 源 _providers）设备实证命中，128000 分母 + 64000 分子 → "50" 显示

**修复内容**：①环境授予（脚本化）②`HiltEntryActivity.kt:29-35` attachBaseContext en-US③`ChatInteractionTest.kt:216-224` seed id=""

**验证证据（e69a99d8 真机，am instrument 单方法/全类）**：

- 两挂死测试单跑：`OK (2 tests)` Time: 2.77（interruptSession_callsInterruptApi + contextUsageBar_shows_whenTokenStatsAvailable）
- 全类：`OK (6 tests)` Time: 8.126（6 pass + 1 @Ignore pagination，合计 7）
- **#209 插桩补跑完成**：test3（catalog 真实路径）设备通过——#209 的插桩级验证缺口就此补上（此前以 delegate 单测×4 代偿）
- 单测全套件：见下节验证记录

**遗留登记**：#211（androidTest createComposeRule 族 locale 依赖）；MIUI 权限随卸载重置已写入 docs/real-device-testing.md「插桩测试」节

**补记（复验环境坑）**：修复提交后的最终复验一轮被设备自动锁屏打断——logcat 为 `MIUILOG- Permission Denied Activity KeyguardLocked`（与根因①同栈不同触发条件：**任何**锁屏状态下插桩 Activity 启动一律被拒，权限已授予也不例外）。解锁后 force-stop 冷启动复验 OK (6 tests) Time 8.534。结论文档化于 real-device-testing.md §插桩测试；运行长插桩批次须保持设备解锁亮屏（`svc power stayon usb` 在 USB 供电下保持亮屏，但锁屏策略独立——需关闭自动锁屏或人工注意）。

## 完结迁移（2026-08-24 用户验收）

**#184 未读水位线 globalMax 跨服务器混合——多服务器时钟偏差场景** `data` —— 已完结验收

- 用户验收通过（2026-08-24「184 ok」），验收口径：四步回归清单（造未读→一键已读全灭→强杀重启不复活→新消息红点重现）+ 日常使用，单服务器场景无行为变化（触发场景为双服务器时钟偏差，用户环境不可达，验收为回归性质）
- 修复内容（commit 7bd04c11）：`UnreadBadgeService.markAllSessionsRead(serverId, sessionIds: Set<String>)` 作用域化——`_lastCompletedReplyTime` filterKeys 限定本服务器会话集，作用域内取 max + 作用域化广播；`SessionListViewModel` 新增 `serverSessionIds`（Eagerly stateIn，源自 getServerSessionsFlow）传入
- 双缺陷同修：错杀广播溢出（allReadAt 判定混入他服务器更晚 reply）+ allReadAt 值域污染（水位线被跨服务器时间抬高致漏杀）；SessionError 客户端 now 第三时钟域顺带收编
- 验证：单测 +3（作用域 max/广播、空集 no-op、跨服务器未读保留），全套件 1919 绿；#210 修复后插桩链路恢复无回归

**#209 TokenStatsTracker.contextWindow 生产死字段清理** `refactor` —— 已完结验收

- 用户验收通过（2026-08-24「209 ok」），验收口径：日常使用在线 context 圆环正常显示
- 修复内容（commit caea2b30）：删 `TokenStats.contextWindow` 死字段（全库无写点恒 0）+ 删 `ModelConfigDelegate` 恒假 tracker 优先分支 + 删聚合映射死链（`ModelConfigState.contextWindow` 保留——真实消费方）；contextWindow = session.model→catalog 查表 ?: 0；FakeDomainModule 补存量缺位 PendingMessageRepository 绑定
- 验证：delegate 级单测 ×4（catalog 命中 128000 / 无 model 0 / unknown 0 / 仅分子不伪造分母），全套件 1923 绿；插桩设备验证经 #210 根因③勘误（test3 seed id=""）后真机 OK——catalog 真实路径 128000 分母 + 64000 分子 → "50" 渲染（#209 的插桩级验证缺口就此补上）

## #211 修复执行：androidTest locale 解耦（2026-08-24 晚批次）

**基线取证（系统 locale=zh-CN 保持不动，e69a99d8 真机）**

- 环境：persist.sys.locale=zh-CN；assembleDevDebug + assembleDevDebugAndroidTest（1m40s）→ 双 APK adb install -r（零卸载，MIUI 权限/用户数据无损）；runner pm list instrumentation 确认 dev.leonardo.ocbeacon.dev.test/dev.leonardo.ocbeacon.HiltTestRunner
- 全量 am instrument -w（stay-on 亮屏解锁，输出 /tmp/211-baseline.txt）：**Tests run: 135, Failures: 30**，无挂死（#210 的 MIUI 后台弹出界面权限授权生效）
- 失败分类：**27/30 为 locale 族**（10 类，全部宿主 HiltComponentActivity：CompactionBannerTest/Branch ×7、ConnectionErrorScreenTest ×2、MessageMetaInfoTest/Branch ×5、SessionRetryCardTest ×1、StepProgressIndicatorTest/Branch ×8、TokenUsageCardBranchTest ×3、SessionListScreenTest ×1）——英文断言（"Compressing context…" / "Retry" / "Step 1" / "Input" / "Archived"…）在 zh-CN 渲染下 not displayed；**3/30 非 locale 残留**（见下）
- ChatInteractionTest 基线保持绿（#210 修复无回归）✅

**方案取舍**

- 入口面盘点（grep createAndroidComposeRule/ActivityScenario 全量）：测试 Activity 仅两个宿主——HiltEntryActivity（chat.* 族 + Diagnostics + ChatSmoke，#210 已 en-US）与 HiltComponentActivity（其余 19 类直接引用 + ComposeTestRule 接口）；无裸 createComposeRule/ActivityScenarioRule 漏网
- 选型：**HiltComponentActivity.attachBaseContext 强制 en-US**（与 HiltEntryActivity 完全同构，镜像生产 LocaleUtils.applyAppLanguage 模式）——单点覆盖全部剩余入口，i18n 资源零改动，零生产代码；否决共享 TestRule/基类方案（需逐类加 @Rule 或改 19 处基类，纯增改面）；否决写生产 in-app 语言偏好（污染 DataStore 持久状态，需恢复逻辑）
- 恢复/清理：无持久状态（locale 仅包在 Activity base context + Locale.setDefault 进程级，插桩进程用毕即销毁），无需 teardown；androidTest 内 CJK 字面量仅 AgentSheetClickTest 测试数据（非资源断言），en-US 锁定无破坏

**改动文件**：仅 app/src/androidTest/kotlin/dev/leonardo/ocbeacon/HiltComponentActivity.kt（attachBaseContext + import + KDoc #211 注记）

**验证证据（zh-CN 真机不变）**

- 重建 assembleDevDebugAndroidTest（42s）+ install -r → 重跑基线 13 个失败类 + ChatInteractionTest（回归）共 14 类：**Tests run: 72, Failures: 3**——27 个 locale 族失败全灭；剩余 3 个与基线完全同款非 locale 残留（#212/#213/#214，不在本卡范围）；ChatInteractionTest 6/6 绿（......）
- 复跑技术注记：am instrument 多类过滤须 -e class a,b,c 逗号单值——重复 -e class 标志 bundle 覆盖只剩最后一个（首跑只执行了 ChatInteractionTest 6 测，已纠正重跑）
- :app:compileDevDebugKotlin BUILD SUCCESSFUL（26s，androidTest-only 改动无生产编译影响）

**非 locale 残留三例（新登记 #212/#213/#214，均 08-18 末次全绿后窗口内的存量回归，不属本卡）**

1. MigrationTest.migrate1To2：builder 只挂 MIGRATION_1_2/2_3 而 DB 已 v4（MIGRATION_3_4 08-20 eefe0942 落地）→ "migration from 1 to 4 was required but not found" IllegalStateException（MigrationTest.kt:111）→ #212
2. ChatMessageRenderingTest.empty_session：断言 "Start a conversation with OpenCode"，chat_empty EN 源 08-23 4ed11ed5（KT10a conversation→session）已改 "Start a session with OpenCode"——en-US 下也必败的失实断言 → #213
3. DiagnosticsScreenDuplicateTimestampTest.duplicate_timestamp：注入两条同毫秒 ERROR 日志后 "first duplicate entry" assertIsDisplayed 失败（无崩溃；宿主 HiltEntryActivity 已 en-US 排除语言）；DiagnosticLogRepository 为真实 Room 实现（FakeDomainModule 未替换），候选=Room 流发射晚于 waitForIdle / 条目过滤路径，待定因 → #214

## 完结迁移·二批（2026-08-24 用户裁决「没问题标记 ok 然后清理」）

**#211 androidTest createComposeRule 族测试依赖系统 locale=英文** `refactor` —— 已完结验收

- 修复（commit 9b44f84f，subagent 执行）：`HiltComponentActivity.attachBaseContext` 强制 en-US（镜像 #210 HiltEntryActivity 修法/生产 applyAppLanguage 模式）——单点覆盖其余 19 组件/屏幕测试类 + ComposeTestRule 接口；零生产代码、零 i18n 改动。否决 TestRule/基类方案（改面大）与写生产语言偏好（污染 DataStore）
- 验收证据（主会话补全量复验，e69a99d8 真机 zh-CN 不变）：**全量 am instrument 135 测 3 败**（116.6s）——locale 族 27 败全灭、无挂死、未在其他类暴露新 locale 依赖；剩余 3 败即 #212/#213/#214（全非 locale）。同事 14 类 72 测复验 + 本次 135 全量双证据闭环
- 技术注记：am instrument 多类过滤必须 `-e class a,b,c` 逗号单值（重复 -e class 标志被 bundle 覆盖只剩最后一个）

**#212 MigrationTest 缺 MIGRATION_3_4——DB v4 落地时测试未跟** `refactor` —— 已完结验收

- 修复（主会话执行）：MigrationTest.kt builder addMigrations 补挂 `Migrations.MIGRATION_3_4`，与生产 DatabaseModule 对齐（v1 库打开要求完整 1→4 路径）；归档表 DDL 断言逻辑不变
- 验证（真机）：MigrationTest + ChatMessageRenderingTest 两类 `OK (9 tests)` 8.786s

**#213 ChatMessageRenderingTest 空态断言文案失实——KT10a 术语批改 EN 源未跟测试** `refactor` —— 已完结验收

- 修复（主会话执行）：断言 "Start a conversation with OpenCode" → "Start a session with OpenCode"（对齐 chat_empty EN 源 08-23 4ed11ed5 现值），注释同步勘误
- 验证：同上批次 `OK (9 tests)`；#212/#213 均为「代码先行、测试未跟」的机械勘误，无生产影响

## #214 修复执行：DiagnosticsScreenDuplicateTimestampTest 硬编码 timestamp 时间炸弹（2026-08-24 晚批次）

**定因链（假设→实验→排除，e69a99d8 真机）**

- 登记时候选三选一：①真实 DiagnosticLogRepository（FakeDomainModule 未替换）Room 流异步发射晚于 waitForIdle ②条目过滤/去重路径丢弃条目 ③注入方式本身问题。读生产链路（DiagnosticLogRepository→LogStore→LogDao）发现第四嫌疑：`LogStore.insert` 内联 `prune(now)` → `deleteErrorBefore(now-21d)`，而测试硬编码 ts=1_785_566_688_405（崩溃报告 key，2026-08-01T06:44:48Z）+level=ERROR——设备时钟 2026-08-24 已越过 21d 边界（2026-08-22T06:44:48Z），**插入即被 retention 清除**
- 时间线佐证时间炸弹而非回归：测试 08-04 创建（f26c3923，ts 当时 3 天龄，绿）→ 08-16 #147 批次过（6c41d0a2，15 天龄，绿）→ 08-24 #211 全量首败（23 天龄）
- 最小实验（临时实验版测试，注入后直查双源：logStore.latest vs repository.entries）：hist ts → **db=0 flow=0**（落库即空，插入被 prune 吞）vs fresh ts → **db=2 flow=2** 且原两条断言全过 OK(1 test)——同一实验同时排除①（fresh 数据流/UI 即刻可见，waitForIdle 足够；repository.entries 本就是 StateFlow 非 Room flow）与②（无过滤丢条目，丢失发生在 DB retention 层，by design）
- 次要嫌疑排除：recordBatch 的 refreshThrottled 1s 节流——插桩进程 HiltTestApplication 不走 App.initialize → AppLogger 未 initialize，无第三方 recordBatch 抢占刷新窗口；实验中 hist flow=0 与 db=0 一致亦证 refresh 本身正常执行

**修复**：仅 `DiagnosticsScreenDuplicateTimestampTest.kt`——sharedTimestamp 改取 `System.currentTimeMillis()`（两条共享同一值，保留「同毫秒重复 key」回归语义且永在 retention 窗口内）；类 KDoc 补 #214 注记并勘误过时的「index 拼接 key」描述（现行 L-11 为内容派生 key）。**零生产改动**：retention 语义正确且有 LogStoreTest/LogDaoTest 锚定；诊断屏插桩测的正是真实 Room 存储，保留真实 DiagnosticLogRepository、不补 Fake（候选①的「未替换」本就是有意设计）

**验证（真机 e69a99d8，插桩）**：本类 `OK (1 test)` 1.358s；全量 am instrument `OK (135 tests)` 115.3s——**#212/#213 修复后首次全绿**（08-18 末次全绿以来的完整回归基线恢复），无新失败

## 完结迁移·三批（2026-08-24 主会话代验收，依据 #212/#213 同性质先例：零生产改动的测试修复、机械判据全量绿已达成）

**#214 DiagnosticsScreenDuplicateTimestampTest 断言失败——硬编码 timestamp 越 21 天 retention 边界（时间炸弹，非回归）** `data` `ui` —— 已完结验收

- 根因（subagent 定因链）：测试硬编码崩溃报告 key（ts=1_785_566_688_405=2026-08-01）+ERROR，`LogStore.insert` 内联 `prune → deleteErrorBefore(now-21d)` 在设备时钟过 2026-08-22 边界后**插入即删除** → UI 空态断言失败。时间线佐证：08-04 创建（3 天龄绿）→ 08-16 过（15 天龄绿）→ 08-24 首败（23 天龄）——典型时间炸弹，非生产回归
- 定因实验（真机最小实验版测试，注入后直查双源）：hist ts → db=0 flow=0（落库即空）；fresh ts → db=2 flow=2 且原断言全过——同时排除「Room 流发射晚于 waitForIdle」（repository.entries 为手动刷新 StateFlow 非 Room flow，机制上不成立）与条目过滤路径
- 修复（commit 26dbf550，零生产代码）：sharedTimestamp 改取 System.currentTimeMillis()（两条共享同值，保留同毫秒重复 key 回归语义且永在 retention 窗口内）；KDoc 勘误过时描述。取舍：保留真实 Room 的 DiagnosticLogRepository 不补 Fake（诊断屏测的正是真实存储链路，未替换为有意设计）；retention by design 有 LogStoreTest/LogDaoTest 锚定
- 验证：真机本类 OK(1) 1.358s + 全量 am instrument **OK(135) 115.3s——#212/#213 修复后首次全绿**，08-18 以来完整回归基线恢复（#215 批2 的前置条件达成）
