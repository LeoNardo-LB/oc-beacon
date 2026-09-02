# 300 残项：_rea 取证 + skeletons 上限 + SelectionContainer 二阶（2026-09-02）

> 状态：**完结（2026-09-03 用户验收通过，迁卡）**——②④ 落地（代码+单测+真机，
> 含 DSH 案发现场+旧归档活体验证）、③ 裁决不做、① 上批已落
> 关联：backlog #300 · spec `docs/specs/2026-09-02-258-stage-b-history-turn-chunking-design.md` §五
> 来源：#258 Stage B §五已知局限 2/3 + Stage A 靶点 2（分片落地后收益稀释）
> 验收备注：用户确认 OK；「思考卡应默认收起」核实为**设备设置**——代码默认
> 收起（expand_reasoning=false），该机开关为开（设置 → 聊天显示 → 自动展开
> 推理内容），非缺陷。

## §一 ② `_rea` 之谜定音：缓存回读 Reasoning→Text 类型翻转（已修复）

### 取证链（三层，全部实锤）

1. **原始观察复核**：Stage B「CHUNK plan ×14 全是 `_reasoning_ord_` 后缀的
   Part.Text」——日志打 `key.take(14)`，DSH `messageId(seq)`=`seq-<n>`（短 id），
   `seq-176_reasoning_ord_2`.take(14)=`seq-176_reason` ⇒ 观察真实有效。
2. **生产侧全查无恙**：`PartIdContract.derive` 全部 9 个调用点
   （DshEventMapper 整装/实况 chunk、V2SseMapper、V2Mappers）kind="reasoning"
   处构造的均为 `Part.Reasoning`；消费侧 `inferDeltaKind`/`applyDelta` 兜底
   重建亦按 kind 正确构造。**类型翻转不在生产，在回读。**
3. **真机 DB 直查**（2026-09-03 00:07，`cached_parts`）：
   - `type='reasoning'` 行 **3,876 条，id 100% 含 `_reasoning_ord_`**（V2 `msg_*`
     与 DSH `seq-*` 两协议皆中）；
   - 其中 **3,621 条 payload 非 NULL 且无一含 `"type"` 键**（其余 255 条为
     增量写路径骨架行 payload=NULL，解码丢弃属既有行为）；
   - `type='text' AND id LIKE '%_reasoning_ord_%'` = **0 行** ⇒ 损坏从未
     回流落库，边界钉死在回读渲染层。

### 根因

- `PartSerializer` 是 `JsonContentPolymorphicSerializer`——只定制**反序列化**
  分发，序列化按具体类进行而 Part 子类无 type 属性 ⇒ **落库 payload 恒无
  type 判别键**（写路径 `typeName()` 只写 type **列**）。
- 回读 `decodeFromString<Part>(payload)` 走兜底字段推断：
  `containsKey("text")` 排在 `containsKey("reasoning")` **之前**，而
  **`Part.Reasoning` 的内容字段名就是 `text`**（`"reasoning"` 键是任何
  Part 都不会序列化出的死分支）⇒ Reasoning 恒误判为 `Part.Text`，保住
  reasoning 派生 id。
- 既有测试盲区：`WireCompatMatrixTest` 注释明知「输出 JSON 不含 type 靠
  字段推断回读」，但只测过 Permission/Question 推断分支，从未测 Reasoning
  往返。

### 后果（被「行为无异常」掩盖的真实损坏面）

- **缓存重进场后思考内容以正文 Markdown 渲染**（挫败 #271「reasoning 全量
  保留，重进思考卡内容完整」裁决的目的——内容存了，形态丢了）；
- `RenderableTurn.copyText` 只拼 `Part.Text` ⇒ 重进后复制文本混入思考内容；
- 段化权重模型 Reasoning=700 vs Text=200+len ⇒ 首/重进分段形态漂移；
- `ContextStats` 计数口径漂移。

### 修复（三件）

1. **回读以 type 列为权威**（`MessageStore.decodePartPayload`，新增）：payload
   为 `{…}` 且列非空时字符串级注入 `"type":"<列>"` 再解码（一次拷贝无重解析；
   列值域为 typeName 固定字面量免转义）。**存量行零迁移即全量治愈**（列
   与实例同源写入）。调用点：`toMessageWithParts` + `archiveOverflow` 装配
   （防误判**固化**进归档 DTO——冷存无列可依）。
2. **PartSerializer 兜底加派生 id 契约检查**：无 type 载荷且 id 含
   `REASONING_MARKER` → Reasoning（救旧归档：归档 DTO 无列，且旧归档是在
   误判后写入的——保住的 reasoning 派生 id 成为唯一判型依据）。
3. 单测：`PartCacheRoundTripTest` ×5（生产 Json 同配置往返/无 type 载荷
   推断/wire 带型分发不变）+ `MessageStoreTest` 回读列权威用例（reasoning
   列→Reasoning、同形 text 行→Text）。

### 验证

- 红→绿：修复前 `reasoning_cacheRoundTrip_preservesType` /
  `legacyPayload_noType_reasoningDerivedId_decodesReasoning` 两测红（实锤
  误判），修复后全绿；全量 **2566/0/0**（+12 新用例）。
- **真机（小米 houji，Dedup指令测试 会话 ses_fbb811ae，204 msgs 缓存 /
  服务器页仅 54）**：上滚越过服务器页边界进入缓存区，UI dump 出现
  **「思考完毕 · 1.4s」思考卡（08-30 12:19:44 消息——时间戳远在服务器窗
  外，必为缓存回读）**；同一位置修复前会渲染为正文文本。trace 侧证：fling
  窗内 `flng:pt:reason`（ReasoningBlock 组合）×7 出现在缓存区。
- V6 用户复验建议：任一旧会话（含思考内容的）杀进程重进 → 上滚旧区，
  思考卡应如首次浏览时一致呈现。

## §二 ④ pendingSegmentSkeletons 上限 48（已落地）

- 问题：到达扫描入队的骨架 `GiantHole` 持巨型 part **全文引用**（≥3000 字符，
  实测数十 KB），装配依赖世界到达/视口巡检驱动——静止/单向深滚时旧骨架
  无界驻留（#258 spec §五.3）。
- 修复：`MAX_PENDING_SEGMENT_SKELETONS=48`（与 `PREPARSE_LRU` 同量级），
  插入超限逐出**最旧到达序**（LinkedHashMap FIFO）；被逐 turn 再入带外
  扫描范围会被重算（纯权重循环，成本可忽略）。
- 测试：`S5_pending骨架超过上限逐旧`——确定性设计：巨型 part 预播种
  `Parsing` 态（registry.flow 底座实例即 MutableStateFlow）⇒ 解析不启动
  （Pending 才启动）、装配永不触发（allTerminal 恒假），59 入队 → 48 驻留，
  第四轮重入队仍钉 48。探针 `pendingSkeletonCount`。

## §三 ③ SelectionContainer per-part 计量与裁决（不做包装精简）

两趟 atrace `flng:pt:*`（PartContent 汇聚点 DEBUG 段，text 带
SelectionContainer+Markdown、tool 不带、reason 为 ReasoningBlock 自有包装栈）：

| 形态 | SelectionContainer | 趟 | n | avg | min |
|---|---|---|---|---|---|
| text | ✓ | Stage A（分段前） | 11 | 3.80ms | 1.91ms |
| tool:shell | ✗ | Stage A（分段前） | 16 | 4.29ms | 3.45ms |
| text | ✓ | 本批（分段后） | 31 | 2.91ms | **1.19ms** |
| reason | （卡自有） | 本批 | 7 | 5.81ms | 4.66ms |

**判读**：若 SelectionContainer 携带显著每 part 成本，带包装的 text 必然
系统性慢于不带包装的 tool——两趟皆反（text min 1.19-1.91ms **低于** tool
min 3.45ms；分段后 text avg 还降至 2.9ms）。包装项被内容组合项（markdown
子树/卡片 chrome）淹没，上界远低于任何可行动阈值；reason 5-6ms 是思考卡
自身 UI（计时/展开）成本，非 SelectionContainer。

**裁决**：不做包装精简（Stage A「收益稀释」预判成立，现有数据两趟定音）。
理由补充：per-part 包装语义变更（如气泡级单 SelectionContainer）会改变跨
part 选择交互，风险>不可测收益。

## §四 环境备注（2026-09-03 勘误：非故障）

- ~~冷启后 DSH host 无连接流量~~ **勘误**：datastore 直查定音——DSH 条目
  `127.0.0.1:3080` 是 debug 通道注入的**手动连接**服务器（`autoConnect:false`，
  fromDebugChannel=true，lastConnected=09-02 22:32）——冷启不自动连是配置
  语义，非 app 故障。Home 屏 DSH 卡「连接」按钮手动复活后 3080 流量/事件
  折叠/会话列表全恢复。
- 自动化观察（不立卡）：会话列表**低区滑死**（起滑 y≥2000 连 800ms 慢拖
  亦零位移；y≤1600 正常，点按正常）——疑 MIUI 底部手势区吞注入拖动，
  真人手指不受影响；与 #245（会话内容区死帧）不同区不同形，仅记备考。

## §六 ② 补充：DSH 原始案发现场验证（2026-09-03）

Stage B ×14 观察的源会话 session-a6c4（「StreamingMarkdownState优化与自动
验证」，5,373 msgs / 1,198 reasoning / 32h37m 跨度）：

- **手动复活 DSH 连接**（见 §四勘误）→ 进会话 → 上滚历史。该会话超热表
  限额，历史区由 `[dearchive] bucket=4027: 94/200 msgs` **归档解档**供给。
- **旧归档活体验证（兜底修复）**：修复前烘焙的归档 parts 是误判后的
  Part.Text（带 reasoning 派生 id、无 type 判别键、归档无列可依）——现场
  深滚区 dump 出「思考完毕 · 0ms」思考卡 ×N（Run code ×14 工具卡邻域），
  即 `PartSerializer` 兜底派生 id 契约检查在**真实存量归档数据**上正确
  复原 Reasoning 类型（与单测 `legacyPayload_noType_...` 形态一致）。
- 时长 0ms 备注：DSH 整装路径 `Part.Reasoning.Time(start=end=事件时间)`
  → reasoningDurationMs 恒 0——mapper 既有语义（DSH 事件无思考时长信息），
  非本修复引入；V2 会话思考卡时长正常（Dedup 会话 1.4s/2.4s 实证）。
- `CHUNK plan` 零 `_reason` 签名断言**未达**：巨型 turn（2.2 万字）在 5,373
  条深处（~179 页 loadOlder），UI 自动化不可及；该断言由单测+DB 取证
  （§一）替代背书。当前窗口 ScrollDiag 仅有 gesture/LEAP 行（本区无
  ≥3000 巨型 part，属预期）。
- FATAL 0；归档解档链（#271 桶）顺带实测正常。

## §五 验证矩阵（V1-V6）

- V1 编译/单测：compileDevDebugKotlin ✅ · 全量 **2566/0/0**（+12）✅
- V2 真机功能：缓存区思考卡回归（服务器窗外 12:19 消息「思考完毕·1.4s」）✅
- V3 观测：真机 DB 直查（3,876 reasoning 行 payload 无 type）· atrace
  flng:pt:* 双趟 · logcat ✓
- V4 回归：FATAL 0 · MessageStoreTest/WireCompat 全族绿（既有缓存回环
  断言不受影响）✅
- V5 工具链：trace_processor flng:pt 查询复用 ✓
- V6 人工视觉：思考卡回归截图级取证（dump 文本）✅，用户真机复验待验收
  （重进旧会话上滚看思考卡）
