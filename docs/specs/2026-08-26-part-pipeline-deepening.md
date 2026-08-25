# part 管线深化——MessageMergeEngine 抽取 + 注册策略归一（#234）

> 状态：**已定案待实现**（2026-08-26 架构走查候选 2→3 连续剧；主会话通读 MessageEventHandler.kt 1329 行全文定案）
> 关联 bug 史：#223 空 part 增殖冻结主线程 · #228 Room 回灌 merge 主线程 HANG · #229 dedup O(N²) · #230 五通道封堵

## 问题陈述

MessageEventHandler.kt（1329 行）是壳与代数的合金：协程壳（StateFlow 状态、48ms delta 批处理、落盘 actor、事件分发）里埋着 **~450 行与协程/Room/时钟无关的纯合并代数**（mergePart / mergePartsList / dedupOverlappingTextParts / mergeSortedMessages / mergeMessageMeta / mergeAssistantMeta / 谓词族），只能经 handler 公共 API 间接测试。

「零信息 part 不得进入系统」不变量的防御散布 5 文件 9 处；6 周 4 bug（#223/#228/#229/#230）即同一不变量被忘记 4 次的历史。**调研新发现活洞**：`upsertAppendOnly`（L1184-1189）对新 messageId 的 parts 直通 `_parts` 不经 `mergePartsList`——不变量在该路径结构性缺防（现靠上游 REST mapper 过滤兜底）。

part id 派生契约 `<msg>_<kind>_ord_N`：生产侧已单一（`V2SseMapper.derivePartId`，V2Mappers:453/464 复用），消费侧谓词（`isNewPartId`/delta kind 推断 L1003-1010）私有散居 handler。

## 走查结论的修正（读码后）

- ❌ 不做有状态 StreamingPartRegistry——它会与 `_messages/_parts` StateFlow 形成双真相源（新 bug 类）。正确形态：**纯函数决策 + 决策类型**，handler 保持唯一状态 owner。
- ⚠️「9 处防御」中 handler 内实际是 2 分支（#223 同 kind 空 started 丢弃 + #230 首个空丢弃）+ mergePartsList 双侧滤空；REST 侧过滤（V1ApiClient:310 / V2Mappers:304）本批**保留**（纵深防御零成本，不重开 #230 封堵面）。

## 设计（D1–D7）

### D1 · MessageMergeEngine 纯函数抽取（C2 本体）
新建 `data/mapper/MessageMergeEngine.kt`（internal object；对齐 CompactionNormalizer 的纯 domain→domain 变换先例、邻近唯一消费者）。迁入（原行号）：
- `mergePart`(613) + Tool 四助手(679-717) · `mergePartsList`(719) · `dedupOverlappingTextParts`(777)
- `mergeSortedMessages`(875, internal 保留) · `mergeMessageMeta`(962) · `mergeAssistantMeta`(414)
- 谓词 `isEmptyStreamPart`/`sameStreamKind`/`isNewPartId`(827-843)
- **新抽取**（现内联在非纯代码中）：`applyDelta(parts, partId, kind, delta): List<Part>`（自 flushPendingDeltas L178-209 的 when 块 + endsWith 去重；Map 累积留壳）；`inferDeltaKind(existingParts, partId)`（自 L1003-1010）

### D2 · 注册策略 + appendOnly 封洞 + id 契约单一权威（C3 修域）
- `resolvePartRegistration(existingParts, incoming): PartRegistration`——L540-598 决策树纯化（sealed：MergeAt / MergeByContent / DropZeroInfo×2(同 kind 空/首个空) / Add），handler 分支只消费决策
- `upsertAppendOnly` 新 messageId parts 过 `sanitized()`——封活洞，不变量对全写入路径结构成立
- `domain/model/PartIdContract.kt`：`derive(messageId, kind, ordinal)` / `isDerived(id)` / `kindOf(id)`——V2SseMapper 与引擎两侧委托，谓词更名 `isNewPartId→isDerivedOrdinalId`（名实对齐，私有零外溢）；MessageDao SQL LIKE 清扫保留为一次性迁移

### D3 · 不动清单（铁律保全）
48ms 批处理与 scheduleFlush 不取消语义（铁律②）· 落盘 actor · applyMessageCap · ensureAssistantSkeleton · markSessionIdle · StateFlow 唯一 owner = handler · `System.currentTimeMillis` 决策留壳 · CAS 重试语义（upsertSsePriority 的 update 外预排序保持）· CompactionNormalizer 调用点

### D4 · 测试迁移与新增
- 既有 8 文件 81 测（MessageEventHandlerTest 38T 等）**行为不变继续通过**（经 handler 公共 API）
- 新增 MessageMergeEngineTest 直测：注册决策表驱动（#223 同 kind 丢弃 / #223 自定义 id p1/p2 例外 / #230 首空丢弃 / #87b 内容匹配）；性质测试（sanitized 输出永无零信息 part ∀输入 · dedup 输出两两无文本重叠 · mergeSortedMessages ≡ distinctBy+稳定排序 随机 fixture 等价 · applyDelta endsWith 去重幂等）

### D5 · 死代码顺带
@Deprecated 三 shim（setMessages/mergeMessages/replaceMessages）主代码零调用 → 删除（测试如有引用随迁 upsertMessages）

### D6 · 明确不收编
REST 侧过滤两处（纵深防御保留）· MessageDao 启动清扫（一次性历史炸弹迁移）· EventDispatcher #216 拦截（跨 handler 协调，另案 EventDispatcher 瘦身处理）

### D7 · 批次与验证
1. Commit 1：引擎抽取（纯搬家）+ 直测 → `compileDevDebugKotlin` + 全量单测
2. Commit 2：注册策略 + appendOnly 封洞 + PartIdContract + 测试
3. Commit 3：删 shim + `docs/architecture-debt.md` 登记（空 part 防御分布更新）
验证：编译 ✓ 全量单测 ✓（V1/V2 契约测试既有）+ V6 轻量人工清单（V2 流式输出一次：无闪烁/无重复文本/压缩分割线正常）——数据层纯重构，行为等价由 81 测锁住

## 验收
① 1329 → ~880 行（handler 仅壳）② 引擎直测覆盖注册矩阵与四性质 ③ appendOnly 路径空 part 不变量成立（新测试证明）④ 既有 81 测全绿 ⑤ id 契约生产/消费单一权威（PartIdContract 双侧委托）
