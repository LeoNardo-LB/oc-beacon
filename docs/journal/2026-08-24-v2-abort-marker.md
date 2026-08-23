# v2-abort-marker（2026-08-24）

> 状态：已完结（用户验收通过 2026-08-24：历史会话中断标记正常显示）
> 关联：#206（backlog P2）· 前序发现：docs/journal/2026-08-24-tier-c-contract-renames.md（Tier C 探针）
> 来源：Tier C 一次性会话探针顺带发现 → 用户指令开工（2026-08-24「先检查 #207，如果没问题就解决 #206」）

## 需求

V2 服务器把中断表示为 assistant 消息 `finish:"error"` + `error:{type:"aborted", message}`（**无 abort part**，Tier C 探针全历史 0 个）。app 侧 `V2MessageMapper` 不读这两个字段 → 中断的助手消息只剩 reasoning part，无任何「已中断」提示。

## 缺口取证（修复前）

- `V2MessageMapper.toMessageWithParts` assistant 分支：不读 `finish`/`error` 顶层字段（V2Mappers.kt 修复前行 255-308）
- `Part.Abort(` 构造点全工程 = 0（仅 PartSerializer:30 分发分支 + PartContent.kt:315 渲染分支 + 测试 builder）
- 渲染链四层全部就绪：`Part.Abort` 模型（Part.kt:262）/ `"abort"` 反序列化分支（PartSerializer:30）/ `isBubbleRenderablePart` 放行（ChatParts.kt:22）/ `chat_interrupted` 渲染（PartContent.kt:315-321，zh「已中断：%1$s」）
- 持久化就绪：MessageStore.kt:509 `is Part.Abort -> "abort"` typeName
- 合并就绪：mergePartsList 按 part id 去重（MessageEventHandler.kt:627-645）

## 实现（TDD）

**测试先行**（V2MappersTest 追加 5 测）：红 → 实现 → 绿。红测阶段反例 `"error":null` 抓到真 bug——`?.jsonObject` 扩展在 JsonNull 上抛 IllegalStateException，改 `as? JsonObject`（服务器可空字段合法形态）。

**V2Mappers.kt assistant 分支**（#206 注释块）：

- 触发条件：`finish == "error" && error.type == "aborted"`（双条件——非 aborted 的 provider 错误不冒充中断）
- 动作：合成 `Part.Abort(id = "${id}_abort", reason = error.message)` 追加 parts 尾部
- **不**填 `Message.Assistant.error`：turn 级 errorText（RenderableTurn:78-81 formatError 通道）会双显——单一中断标记原则
- 稳定 id：REST 重取 mergePartsList 按 id 去重，幂等不双显（真机实证：同一消息多次刷新仅一条标记）

V1 不受影响（V1 原生有 abort part，走 PartSerializer 正常分发）。

## 验证证据

### V1 自动化
- `V2MappersTest` 30/30（新增 5：正向合成×2 / 反例×2 / id 稳定性×1）
- 全单测套件 BUILD SUCCESSFUL（:app:testDevDebugUnitTest，2026-08-24）

### V2 服务器契约实测（curl，部署版 beta-17963）
- 平铺契约 prompt 200（包裹 400——与 app 内降级逻辑一致；注意 shell 引号陷阱：`-d '{\\"text…}'` 会把反斜杠字面传进 JSON → 400 "Expected a valid JSON body"）
- 中断后消息状态：`finish="error"` + `error={"type":"aborted","message":"Step interrupted"}` + content 1 part（reasoning）——与 Tier C 探针 payload 一致

### V3 真机 E2E（houji e69a99d8，devDebug versionCode 1787507726，adb reverse tcp:4199）
两条路径均验证：
1. **REST 加载路径**：丢弃会话「从算盘到AI」（API 建会话+prompt+interrupt 制造中断态）→ app 打开会话 → uiautomator 文本节点 `已中断：Step interrupted`（bounds [84,2093]）✅
2. **活体中断路径**：app 内输入长文 prompt → 发送 → 流式中（输入键变「停止」）→ 点停止 → **不退出会话** → 新中断消息立即渲染 `已中断：Step interrupted`（bounds [84,388]）；同屏旧中断消息仍各一条标记，无重复 ✅
- 环境清理：丢弃会话已删（DELETE 204 → 复查 404）

## 用户验证清单（V6）

真机打开任意含中断历史的会话（或现场发长文+停止）：
1. 中断的助手消息底部应出现红色小字「已中断：Step interrupted」（15 语言各自文案）
2. 正常完成的消息不受影响（无标记）
3. 中断消息反复进出会话不出现重复标记

## 完结迁移

- [x] **#206 V2 中断消息无中断标记渲染（error.type=aborted 未映射）** `ui` `sse`
  - 服务器中断表示为 finish:error+error.type:"aborted"（无 abort part，历史 0 个）；V2Mappers 不合成 Part.Abort → 中断的助手消息只剩 reasoning、无任何「已中断」提示
  - → docs/journal/2026-08-24-tier-c-contract-renames.md（Tier C 一次性会话探针发现）

**验收记录**（2026-08-24）：用户真机验收通过——「历史会话中已中断正常显示」。验收时顺带报告新问题（思考计时器滚动归零）→ 登记 #207。
