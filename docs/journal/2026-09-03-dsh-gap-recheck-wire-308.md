# dsh-gap-recheck-wire-308（2026-09-03）

> 状态：调研完结（纯登记批次，无代码变更；#308 修复另行开批）
> 关联：backlog **#308** · **#309-#312**（§四 正向差距落卡：批 1/批 2/批 3+零星 S 池） · `docs/research/2026-09-01-dsh-web-vs-android-gap.md`（§6.4/§11.3/§11.4/§12.4）· `docs/research/dsh-gap-2026-09-01/implementability-ui.md`
> 来源：用户两问（① DSH 服务端有接口而 Web 前端没有的反向差距；② Web 有、我们 DSH 面缺、但现有 UI/逻辑可承载的正向差距）→ 复核中发现 #308 wire 级 bug

---

## 一、任务与结论速览

对 `2026-09-01-dsh-web-vs-android-gap.md` 做双向复核：

1. **反向**（Web 没有、我们 DSH 面有、服务端接口/数据支撑）：核实成立 **5 项**——工作区文件树（`host.listDirectory`）、客户端全文搜索路线（本地 FTS5，不依赖被部署禁用的 `session.search`）、忙碌双键发送（`mode=queue`）、流内 jobs 时间线卡（`session/jobs`）、排队项三动作（`session.updateQueue`）。
2. **正向**（Web 有、我们缺、现有 UI/逻辑可承载）：**0 必做 L**。第一档 5 项（压缩呈现/goal.complete/Full access 确认/子智能体续聊/steer 长按直发）UI 侧已就绪或 ≈0；第二档 9 项骨架现成；路线沿用主文档 §11.4 三批（≈20-25 人日）。
3. **勘误**：主文档「审批 always 档 = Android 反向超集（服务器落持久规则）」判定**不成立**——DSH 服务端无 always 能力，且顺带牵出 #308。

## 二、#308 取证链（四重独立证据，2026-09-03 源码级复核）

服务端基线：dsh **0.1.1-rc.2**（`/home/linuxbrew/.linuxbrew/lib/node_modules/@deepseek-ai/dsh/`，与研究基准同版本）。

1. **服务端 schema**：`dsh-host-apiproxy/lib/index.js:678-681` `approvalResponsePayloadSchema = {sessionId, approvalId, outcome: union(literal("allowed-once"), literal("rejected"))}`——三键必填，outcome 枚举**无 allowed-always**；`approval/resolved` 帧枚举（:5038-5044）同款仅 allowed-once/rejected/cancelled/unavailable。
2. **官方 Web 客户端**：`dsh-client-connection/lib/client.js:5470` 同款 union；:9772/:9923 官方应答构造**带 approvalId+sessionId 完整三键**；:9915 显式拒绝两词之外的 outcome。
3. **全树零命中**：`allowed-always` 在整个 dsh 安装树 grep 零命中。
4. **本仓库 OpenAPI**：`docs/api/dsh-openapi.yaml` §/api/respond + ApprovalResponsePayload（:8155-8171）枚举一致；回执为 RpcReceipt `{accepted, reason}`「never a business envelope」（证据 E11/E11b）。

**Android 侧错位**（`DshApiClient.kt:757-772` / `DshRpcClient.kt:88-91,98-132` / `DshEnvelope.kt:206-227`）：

- `replyToPermission` 只发 `{outcome}`：缺 `sessionId`+`approvalId` 两必填键 → `safeParse` 必失败 → 服务端回 `{accepted:false, reason:"bad-response"}`；
- `allowed-always`（always 按钮映射词）双重非法（缺键 + 枚举外）；
- `/api/respond` 回执 RpcReceipt 无 `rpcId`/`type` → `DshEnvelope.decode` 返 null → `exchange()` 判 malformed → **三键应答恒 false**（HTTP 200 掩盖；审批实际靠服务端超时/unavailable 兜底，UI 无感知）；
- `replyToQuestion` 发 `{answers:{key:value}}`：缺 `sessionId` 且形状不符 `QuestionResponsePayload{sessionId, answer:{answers:[{id,selected,custom}]}}`；`rejectQuestion` 发 `{outcome:"cancelled"}` 同不符（取消应走 result.ok=false + error.code="cancelled" 信封）。

**E2E 出处勘误**：`DshApiClient.kt:752-754` 注释「allowed-once 见 P-4 fixture，其余两词 E2E 定音」——该 E2E（`docs/journal/2026-08-19-emulator-acceptance.md:37`，`GET /api/permission/saved` 持久规则验证）在 **OpenCode 后端**执行（DSH 适配 2026-08-30 才立项）；`once/always/reject` 三词是 OpenCode `POST /permission/{id}/reply` 词汇（`docs/opencode-api-reference-v1.md:1896`）。DSH 侧审批应答**从未活体验证过**。

**修复方向（根因层，登记于卡）**：respond 载荷补全三键 + `DshRpcClient` 增 RpcReceipt 解析分支 + 提问应答改 `{sessionId,answer:{answers[]}}` + 取消改 Err 信封 + always 改本地规则自动以 `allowed-once` 重答（`PermissionAutoApprover` 已有骨架）；验证=真机 DSH 会话触发审批三键 + logcat 回执 `accepted:true`。

## 三、反向差距核实明细（第一问结论）

**成立（服务端接口/数据直接支撑）**：

| # | 能力 | 服务端面 | 我们 | Web |
|---|------|---------|------|-----|
| 1 | 工作区文件树面板 | `host.listDirectory`（非特权远程可达） | `WorkspaceScreen`+`FileTreePanel` 懒展开（A-D2-03/04） | 仅「添加工作区」Miller 浏览器内使用，无常驻树 |
| 2 | 会话内容全文搜索 | `session.search` 在（本部署 openAt=never）+ `session.history` 数据面 | 本地 Room FTS5 BM25+过滤 chips+命中直达（A-D1-02/03/04） | 远程仅标题匹配+降级警告条 |
| 3 | 忙碌双键发送 | `session.prompt mode=queue` | busy 停止+发送双键（A-D3-08） | busy 单停止键，排队只能 Enter（走查3 §1） |
| 4 | 流内 jobs 时间线卡+完成事件卡 | `session/jobs`+事件流 | `DshJobTimelineCard`+`EventCard`（A-D5-11/13） | 仅头部徽标+只读 popover（D5-35） |
| 5 | 排队项三动作 | `session.updateQueue` edit/remove/steer | QueueDock 三动作全量消费（A-D3-09） | 源码有（D3-14）、实测悬停只露复制（源码-实测差异） |

**纯客户端能力（无需服务端接口，Web 可直接抄）**：标签/收藏+筛选/未读+一键已读/快速导航 Q 跳转/草稿跨重启持久化/发送前确认/历史同步管理/图片压缩/15 语言/AMOLED+动态取色/断连三态横幅。

**排除**：平台限定（通知/前台服务/深链/分享/多服务器）；OpenCode 后端限定（删除/撤销/分享/后台化/终端 PTY/Git 面板/MCP/OAuth/shell 模式——DSH 服务端本就无此面）；**审批 always 档移出此列**（见 §二）。

## 四、正向差距「现有 UI 可承载」分档（第二问结论）

- **第一档（UI 已就绪，纯接线/加按钮）**：压缩过程/摘要（`CompactionCard.kt:41-60` 双态 UI 完整，仅 `DshEventMapper.kt:473-476` Ignored 未接）；goal.complete（API 全链在位，`GoalSheet.kt:163-183` 第四钮）；Full access 确认（`PermissionPresetSelector`+现成 `ConfirmDialog`）；子智能体续聊（AgentSheet→子会话完整 ChatRoute 通道 100% 就绪，缺 `subagent.prompt/interrupt/history` 三方法）；steer 长按直发（wire mode 已在，双键/QueueDock 已是超集）。
- **第二档（骨架现成补源/补卡）**：重试倒计时+max-tokens continue 钮、@ 会话源、消息反馈 👍/👎、Plan 模式（提问卡意图变体）、deliverables、工具卡增补（question/skill 行）、轨迹台账+检查器（`RenderableTurn` 已预计算）、归档/行菜单/左滑、状态点分色。
- **零星 S 级**：相对时间戳、KaTeX、spill 提示、命令带图限制、消息级分支锚点。
- 挂点逐项明细以 `implementability-ui.md` 汇总表为准；路线 = 主文档 §11.4 三批（批1 ≈3 人日 → 批2 ≈8-10 → 批3 ≈6-8）。

## 五、研究文档勘误注记（未改动原文，留档待办）

- `2026-09-01-dsh-web-vs-android-gap.md` §6.4/§12.4 与 `app-feature-census.md` A-D7-02「审批 always 档=服务器落持久规则」应修正为「本地规则模拟；服务端枚举无此词」；§6.4「Android 更全」与 api-gap「应答环已接通」在 #308 修复并活体复验前**暂不可信**。
- `app-inventory.md`:57「outcome: allowed-once/allowed-always/rejected」与同目录 `api-gap.md`:74「allowed-once/rejected」自相矛盾——后者正确。

## 六、本批变更清单

- `backlog.md`：P0 节新增 **#308** 卡（编号计数器 #308→#309）；`./scripts/backlog-check.sh` 通过。
- `backlog.md` 追加（同日续批，用户指示「引用到 backlog」）：正向差距落卡 **#309-#312**（批 1 P1 / 批 2 P1 / 批 3 P2 / 零星 S 池 P2，均链回本文件 §四与主文档 §11.4/§12.3）；#308 链接行补「§五 勘误随修复回写」注记；计数器 #309→#313；check 复跑通过。
- 本 journal：创建并写入全部取证。
- 无代码变更、无研究文档改动（勘误以注记形式留档）。
