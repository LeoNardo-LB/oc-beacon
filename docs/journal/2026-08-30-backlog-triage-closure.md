# backlog-triage-closure（2026-08-30）

> 状态：已完成（#264/#235 过时·误判关闭迁 deps journal；#261/#263 修复完毕待用户一句话验收；#268 调研交付关闭立 #269）
> 关联：/tmp/handoff-backlog-triage-20260830.md（上一会话对账 handoff，工作区外）· `docs/journal/2026-08-30-deps-upgrade-2026-08.md` §对账关闭（#235/#264 迁入地）· `docs/journal/2026-08-30-dsh-integration-and-disconnect-design.md`（#268 关单轮）
> 来源：用户指令「看看这个 handoff，能关闭的就先关闭，以免债务越积越多」

<!-- 过程中的取证/验证证据直接写本文件；backlog.md 只留 ≤3 行卡片。 -->

## 零轮 · handoff 现状对齐（接手先看）

- handoff 生成于本会话两笔提交之前，两处已过时：**#265 已关单**（六项 V6 收口，commit 41a21eb9）、**#268 §3/§6 已填**（合成完成，commit 6912ce28）——handoff 的「待验收 2 张」「#268 重派决策」相应作废
- 挂起决策点 1（「关闭 #235/#264 + 顺手修 #261/#263」）由用户本会话指令授权执行
- 测试基线对齐：triage 会话实证 2173 用例唯一红 ChunkReproTest（#261）；本会话修复后 2177 用例 0 红（见三轮）

## 一轮 · #235/#264 关闭（迁 deps journal）

- #235 过时关闭：material3 1.4.0 稳定版已含 FAB 菜单稳定 API + compose 1.12.0 全家稳定 + deps journal 用户 V6 验收在案
- #264 误判关闭：A/B 实证移除 → QuestionParserTest 16 挂 5，恢复复绿；机制 = 单测类路径 android.jar 的 org.json 为 stub，testImplementation 提供真实实现。依赖保留
- 关闭记录全文 → `docs/journal/2026-08-30-deps-upgrade-2026-08.md` §对账关闭（2026-08-30 backlog triage 批次迁入）

## 二轮 · #261 ChunkReproTest 自造夹具

- 改动：`ChunkReproTest.kt` 重写——测试内自造 ~90KB 结构化文档（300 节标题/段落/代码块/列表，第 150 节含「索引下推」标记词），删除 /tmp/giant.md 外部依赖；新增断言「超过 CHUNK_MIN_CHARS 的巨文档应产生分块计划」（原版无此断言，plan 为 null 时静默跳过）
- 保留原回归意图：解析成功 → 标记词块定位 → 分块计划 → 分块不以空白块开头
- 证据：全量单测 --rerun BUILD SUCCESSFUL（1m32s）

## 三轮 · #263 思考卡天文时长守卫

- 真实根因（triage 会话定位，卡片原指向 ReasoningBlock.kt 有误——其对 null 回退正确）：`PartContent.kt` L122-124 `end - t.start` 未防 start=0 哨兵（V2 reasoning.started 无服务器时间戳时 0 占位）→ 差值 ≈ 当下 Unix 毫秒 → 「29800753m」
- 修复：提取纯函数 `reasoningDurationMs(start, end)`（start<=0 → null）+ 调用点接入；显示侧走 ReasoningBlock 既有 startTimeMs 降级链（流中计时/续计语义不变），完结时长留空
- 新增 `ReasoningDurationTest` 4 例：0 哨兵→null / 负值→null / 正常差值 / 零时长
- 证据：全量单测 --rerun **2177 用例 0 失败**（基线 2173 − ChunkReproTest 红 + ChunkReproTest 转绿 + 新增 4），1m32s

## 四轮 · #268 关单 + #269 立卡

- #268 调研交付关闭（记录 → dsh journal §调研完结）；新立 #269：探针 P-1..P-4 先行 → 按差距矩阵拆实现需求卡

## 遗留（本批次不做）

- #252：只差用户一句话正式验收（handoff：E2E 三项 ✓ + 修复后用户已评「好转」）
- #258：第一步应重测（compose 1.12.0 稳定后旧测量矩阵基线过时）
- #154/#146/#158/#245/#254：维持挂起/观察（handoff §3 判定不变）