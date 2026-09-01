# 258-perfetto-stage-a（2026-09-02）

> 状态：阶段完结（Stage A 取证+归因完成；Stage B 修复另批启动）
> 关联：backlog #258 · `docs/journal/2026-08-27-event-card-unification.md` §二十八轮（测量矩阵）
> 来源：#258 卡既定方向（Tracing.enable + perfetto 定位组合热点 → chunk 组合瘦身）

## §〇 战役定位

#258 残项 = fast fling 下重 item 首组合帧 p95 65/p99 129ms（2026-08-29 矩阵；2026-09-02 质量门复测 p99 32/40/53ms 无回归）。本批 = 战役 Stage A：**取证工具链打通 + 组合热点归因**，产出 Stage B 的靶点清单。

## §一 工具链勘误链（四坑定音，全部实证）

1. **设备 perfetto 配置形态旧**：`atrace_config`/`android_atrace_config` 字段均不存在（v58 之前形态）→ atrace 走 `linux.ftrace` 的 `ftrace_config { atrace_categories / atrace_apps }` 旧路径。
2. **输出路径**：`/data/local/tmp` 被拒（SELinux）→ 只能写 `/data/misc/perfetto-traces`（shell 可 pull）。
3. **SDK 细粒度 CC: 段不可达（关键勘误）**：runtime-tracing 1.11.2/1.12 的 CompositionTracer 均走 `androidx.tracing.perfetto.PerfettoSdkTrace`，受 `isTraceInProgress()` 门控——启用依赖系统 traced 对 app 广播 `androidx.tracing.perfetto.action.ENABLE_TRACING`（tracing-perfetto 1.0.1 的 TracingReceiver 机制，且公开 API 无 `Trace.enable()`）。本设备（MIUI user 版）traced 不发该广播：track_event 会话里只见 coarse `Compose:recompose`，无逐调用点 CC: 段。logcat 中 `org.chromium-<包名>` producer 是 WebView 内嵌 producer，非 androidx SDK（识别陷阱）。**结论：CC: 路线在本设备死路，勘误注记入 OpenCodeApp.kt。**
4. **Compose 编译器禁 try/catch 包 composable 调用**——自插桩改直包 begin/end，`?: return@Box` 类早退需先提出段外。

**替代方案（本批落地，commit e3f0de61）**：自插桩 atrace 段（`android.os.Trace`，DEBUG 门控）：
- item 级 `flng:it:*`（chunk/uchunk/turn-a/turn-u，ChatMessageList 四处包裹点）
- 块级 `flng:pt:*`（PartContent 汇聚点 wrapper → PartContentInner，按 part 形态命名：text/reason/tool:<名>/file/patch/…）

采集工具固化：`scripts/perfetto-fling-capture.sh` + `scripts/perfetto-fling-config.txt`（60s 窗，双趟 12 记甩 + 趟间回底，输出 /data/misc/perfetto-traces）；分析用 trace_processor v58.2（`/tmp/298-tools/trace_processor_shell`，get.perfetto.dev 下载）。

## §二 取证结果（真机 houji WiFi ADB，devDebug e3f0de61）

目标会话选型勘误：debug-entry 固定入口（Host-4199 首位会话）历史太短——一趟 fling 到顶，仅 1 个 turn item 入窗（219ms）；Host-4199 侧测试会话（showcase234/Dedup指令测试）均 <10 条。最终目标 = DSH 服务器 `StreamingMarkdownState优化与自动验证`（oc-beacon 自有长会话，多 part 交替 + 长 markdown 段）。

trace2.pftr（60MB）flng 段汇总：

| 段 | n | total | max | avg |
|---|---|---|---|---|
| flng:it:turn-a（assistant 整 turn item） | 4 | **658ms** | **334ms** | 165ms |
| flng:pt:text（markdown 文本 part） | 97 | 373ms | 32.8ms | 3.8ms |
| flng:pt:reason | 30 | 76ms | 3.2ms | 2.5ms |
| flng:pt:tool:*（含 run_code 35×1.5ms） | 38 | 61ms | 2.1ms | 1.6ms |
| flng:it:turn-u / uchunk | 11 | 30ms | 5.7ms | 2.7ms |

333ms 样例 turn（t=451814.524，depth 8）内部结构：约 55 个小 text（~1.3ms/个）与 25 个 tool（~1.6ms/个）交替 + 6 个长 text（**11-33ms/个**）+ 少量 reason。子项合计 ≈ 280-300ms → 气泡 chrome（统计栏/分割线/包装）占比小，**成本在 parts 本身**。

## §三 归因结论（Stage B 靶点清单，按证据强度排序）

1. **历史长 turn 未分片 = 结构性主因**：`buildChatEntries`（MarkdownChunking.kt:289）只对 text.id ∈ chunkPlans 的 turn 发 Chunk 条目——chunkPlans 是流式期（#265 前缀差分试点）产物，**历史/REST 载入的长 turn 恒走 ChatEntry.Turn 整 turn 单 item**。一个 LazyColumn item 首组合 = 整 turn 全 parts 同帧（165-334ms 实测）——这就是 p99 帧冻结本体。方向：为历史长文本 part 构建等价 chunk plan（复用 Chunk 条目/键序/跳转锚点机制），流式 turn 维持不分片（C-R3 语义不变）。
2. **单 part 固定开销 ~1.3-1.8ms × part 数**：text 与 tool 同量级（SelectionContainer 假设未单独证实——两者固定成本相近，更像通用 per-part 包装成本）。若 turn 分片落地，此项随 item 粒度缩小自然稀释；独立优化（SelectionContainer 提升/包装精简）收益二阶。
3. **长 markdown 段 11-33ms/个**：非分片路径已有 `asyncParse = !isStreaming` + 滚动预解析（readinessRegistry）——残余成本是**已解析块树的组合渲染**（几十 block 的 Compose 子树），turn 分片同样是对症解。
4. reasoning/tool 卡单卡成本健康（1.6-2.5ms），非靶点。

## §四 本批验证

- 编译 ✅（compileDevDebugKotlin + assembleDevDebug）；插桩 commit e3f0de61；装真机覆盖安装 ✅
- 两轮采集（trace.pftr 74MB / trace2.pftr 60MB）+ trace_processor 查询全通（§二数据）
- 插桩为 DEBUG 门控直包段，release 零开销；质量门脚本不受影响
- 观察备注：DSH 会话进入时分页加载 ~1 页/s × ~10 页（58 msgs 会话 ~10s），终态正常——暂不立卡，后续若用户感知慢再查

## §五 待办移交（Stage B 另批）

1. **历史长 turn 分片**（靶点 1，结构修复）——涉及 chunkPlans 构建、键序/跳转锚点、流式互斥语义，需独立 spec 级设计
2. SelectionContainer per-part 开销计量（若 Stage B 后仍需二阶优化）
3. 未立卡观察顺带项：事件 ×2 双投递确认（10min WS tap）——本批未做（上下文预算），下批取证窗口补
