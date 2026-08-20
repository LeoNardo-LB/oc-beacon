# 2026-08-20 滚动卡顿深度调查批次（三层根因全修）
> 状态：部分完结（活跃 #162）
> 迁移：2026-08-20 自 backlog.md 原文迁入（spec/journal 分离批次；原文逐字保留，未压缩）
> 条目编号：滚动卡顿取证=#162；子条目「长文本 Part 级 semantics merge」提升为卡片 #165


- [~] **真机滚动仍有卡顿（用户复报）→ 系统性帧级取证定位三层根因，全部修复** `ui` `perf`
  - 用户报告（2026-08-20）：上一批修复后滑动手感仍卡（慢拖 + fling 都一顿一顿/不跟手）
  - **取证方法**：dumpsys gfxinfo framestats 逐帧分解 + Perfetto atrace（UI 线程 slice 解剖 + 主线程 busy 直方图）+ 系统 Settings 对照（同注入 246 帧 0.41% janky = 设备/注入无罪）+ Room sqlite 直查（定位 3 条 111-130K 字符巨型消息）+ 子代理 ×2 只读调查（fling 巨帧根因 / a11y 语义树方案）
  - **根因 ①：RenderReadinessRegistry 快照 Map 整表失效重组风暴**（已修 67f4209c）——flows 原为 mutableStateMapOf，读依赖是整 Map 级：每个可见消息卡片组合中读 Map（flow()/current()），而滚动期间 Map 持续被写（滚出视口 remove()、预解析 put、LRU 淘汰）→ 任一次写全卡片失效重组。trace 实证：拖动期 Recomposer 单帧 23-26ms、一次 9 个 scope 成批重组。修复：ConcurrentHashMap + 消费端 collectAsState 订阅单 key。A/B 同场景实测：慢拖 janky 41.7%→0.88%、p95 400ms→14ms
  - **根因 ②：超长消息单 LazyItem 组合巨帧**（已修 0faa6984）——一条 130K 字符消息 = 一个 LazyItem；LazyColumn 子项滚动方向无限高约束 → 首次组合必须同步建完整棵 Markdown 树（trace 单个 Compose:recompose scope 49.7ms；prefetch:measure max 150ms——item 是预取原子单位）。mikepenz 0.43.0 无块级懒加载参数（全部重载核对）、LazyMarkdownSuccess 不能嵌套同向列表 → 唯一治本 = LazyItem 粒度分片。实现：MarkdownChunking.kt（块级分片计划 MdChunkPlan + ChatEntry + buildChatEntries）+ ChatMessageList 发射 chatEntries + ChunkedAssistantMessage 分段渲染（首段标签栏/末段统计栏、分段圆角、SelectionContainer 按 chunk）+ MarkdownContent blockRange success 槽 + 流式/刚结束 turn 不分片（recentStreamedTurnKeys 防视口 key 裂变闪跳）+ isTurnLast O(N²)→O(1) 查表（原每 assistant item 组合 subList 线性扫 rawMessages）+ 跳转索引 displayEntryStart 映射适配
  - **真机验证（0faa6984）**：验收测试会话AB（107 条含 3 条巨型）5 连发 fling 穿越巨型区：1836 帧 janky 0.27% p50=6ms p95=9ms p99=30ms（修复前 p50 61-73ms、400ms 巨帧、fling ~120ms 早死）；LEAP total=94（+35 items = 分片生效）连续翻越 chunk、RESIZE=0；视觉复核分段气泡无接缝/无重复头部；全量单测绿
  - **根因 ③（环境因素，非 App 缺陷）：GKD 无障碍服务对 Compose 的专属税**——用户真机常开 GKD（跳广告）。实测：GKD 开时聊天屏 p50 23-77ms（语义三件套 getAllUncoveredSemanticsNodes 219ms/8s + checkForSemanticsChanges 172ms + sendAccessibility...Events 143ms，最大帧 110-150ms）；系统 Settings（View 体系）同条件 0% jank——此税 Compose 专属（并行语义树 diff+派发）。已试 MessageBubble semantics(mergeDescendants) 收益噪声级（~10%）且流式期有 merged config 整气泡重建隐患 → 放弃（stash 已丢弃）。**无低风险 App 内修复**；结论：开着 GKD 的用户群体感知上限受限，为已知环境因素
  - ✅ 用户验收通过（2026-08-20）：三轮修复 + isAtBottom 下沉后的 devRelease 真机复验——"十分丝滑"（用户原话）。GKD 关闭状态；开 GKD 场景因服务已长期关闭未测，如重开且卡顿回归参照根因③结论
  - **基建沉淀**：/tmp/perf/*（frameparse.py 逐帧分解、phases.py 相位分解、perfetto trace-config + base64 直装法、drag/fling 场景脚本）+ 子代理报告（fling 根因含库源码核对路径 /tmp/mdn-src、a11y 备选方案评估）

- **a11y 子代理报告附带发现（2026-08-20 登记）**：
  - [x] **P3：AssistantTurnBubble.kt 疑似死代码（全库无调用点）** `refactor` ✅ 2026-08-20 已删（48fbd97f，全库 grep 复核含 test 零调用）
  - [x] **P3：clickableMarkdown 的 CodePath 点击仅 pointerInput——TalkBack 不可达** `a11y` ✅ 2026-08-20 补 semantics onClick（9bb4a537，节点中心定位）——TalkBack 实机走查待用户验收
  - [x] **P3：CompactionCard combinedClickable 空 onClick——朗读为可点击但无动作** `a11y` ✅ 2026-08-20 改 pointerInput 长按 + semantics 自定义动作（9bb4a537，标签复用 chat_revert）——TalkBack 实机走查待用户验收
  - [ ] **P3（降级 2026-08-20：GKD 已长期关闭，主收益消失；仅 GKD 用户重新开启时才有价值）：长文本 Part 级 semantics merge** `perf` `a11y`
    - 唯一有机制优势的 GKD 税缓解变体：失效 containment（流式只重建单 part 而非整气泡）、标签栏/statsBar 保持独立节点。仅已完成长文本 part、流式 part 不加；交错 A/B 验证——GKD 关 p50 回退 >2ms 或 p95 改善 <15% 即 abort（气泡级 merge 实测仅 ~10% 且有流式隐患，Part 级预期相近）
    - 工时：~3h | 难度：中 | 涉及：PartContent/MarkdownContent | 优先级：P2
  - **文档建议（零代码风险）**：FAQ/README 注明 GKD 用户可将本 App 加入排除规则（gkd.li/guide/faq 规则级排除）或使用时暂停服务——直接消除查询侧主成本；随下次文档批次落地
