# 2026-08-18 模拟器验证批次
> 状态：全部完结
> 迁移：2026-08-20 自 backlog.md 原文迁入（spec/journal 分离批次；原文逐字保留，未压缩）


> 环境：Pixel6_Android36 模拟器（API 36，dev 0.3.1-dev.15 @ master 8d65a387）+ V2 服务器 0.0.0-beta-17595（10.0.2.2:4199）。
> 证据目录：/tmp/verify-0818/（截图 00-57 编号序列 + logcat + dumpsys + gfxinfo + Room DB 副本）。
> 修复 commit：32765cf6（question 轮询）+ 6023bd5f（androidTest Fake）。

- [x] **V2 长会话历史分页不可达（501 条只见 40 条，NETWORK 首翻恒 0 条）——已修复 53cfea68** `data` `sse`
  - 现象（2026-08-18 模拟器复验新增F 时发现）：进入 501 条消息的测试会话 → 上滑到顶触发 auto-load（触发机制正常：`auto-load probe → triggered → loadOlder START`）→ **NETWORK 返回 0 msgs → hasOlder=false 误判读尽** → 461 条更早消息永久不可达
  - 根因（curl 双盲区实证）：**两处修复打架**——MessagePaginationDelegate:216（2026-08-12 补丁）在 HotStart+V2 时本地构造 `encodeV2(hotOldestId)` cursor，而 MessagePaginationUseCase:200（2026-08-16 根治）已明确 V2 首翻**不传 cursor**（依赖响应的 cursor.next）。热表最老是中部历史 id → 服务器**窗口外 id 返回空页**（curl 复现：构造 cursor=历史id → count=0 且 next=null；cursor=近期id → 30 条 next 正常）——08-16 根治被 08-12 补丁旁路
  - 服务器行为补充：beta-17595 窗口语义 = 仅近期 id 的 cursor 有效；与 #73（窗口外空页）同族，服务器升级后窗口收紧
  - **2026-08-18 修复完成（53cfea68）+ 模拟器闭环 ✅**：删除 Delegate 本地 encodeV2 构造（HotStart 首翻走 use case null-cursor 路径拿原生 cursor.next，归档优先顺序恢复）+ PaginationFSM.LoadSucceeded 加固（hasOlder = nextCursor != null || pageSize >= limit，与 LoadNewerSucceeded 对称；顺带勘误既有测试自相矛盾参数）。验证：回归测试 ×3；模拟器 501 条会话游标链逐页前进（created 递减至 8月12日）、total 137→226 持续加载、Room 热表 **501 条全量落库**（08-05→08-18 14:23 与服务器一致）、深翻回滚消息连续渲染无丢失；61 分页单测 + 1694 全量单测全绿

- [x] **SSE 冷却死循环（连续超时后永不真正重连）——已修复 bd04d060** `sse`
  - 现象（2026-08-18 修复轮询验证时发现）：beta-17595 服务器无心跳帧 → SSE 每 40s 读超时 → 连续 5 次后进入 cooldown → 日志每 30s 打 `SSE in cooldown, waiting 30000ms` **但从不发起连接尝试**（观察 3 轮 waiting 零连接）——SSE 通道假活（REST 正常），直到进程重启
  - 证据：21:33-21:35 logcat（`Reconnecting in 30000ms (attempt #6)` 后只有 waiting 无 attempt）
  - **2026-08-18 根因修正（比登记定性更深一层）**：不是"waiting 不重连"——冷却到期后**会**重连，但 0 事件连接（无心跳服务器 40s 内零事件）在 collect 首事件前超时 → `consecutiveTimeouts` 从未清零仍 ≥ 阈值 → 立即再次 enterCooldown → 「5min 冷却 → 40s 尝试 → 5min 冷却」永续（SSE 仅 ~12% 时间在线）
  - **2026-08-18 修复完成（bd04d060）+ 模拟器全周期实证 ✅**：enterCooldown() 清零 consecutiveTimeouts（冷却代价付清后重新计数）+ 两处冷却日志先读计数再 enter。验证：飞行模式 5 连败 → 冷却 → 网络恢复 → 冷却到期**立即真正重连**（Pre-load 200 + Recover 50/50 + Connected）→ 后续 40s 周期正常循环无再进冷却；1694 全量单测全绿
  - 关联：#142 修复引入的 hasConnectedOnce/recoverMessages 链路（8bbcb216）；#108 的 40s 超时防护

- [x] **beta-17595 服务器契约适配批次——4 子项全部闭环（00fbdda3 心跳修复 + 32765cf6 + 排查定性）** `compat` `sse`
  - 服务器从 next-17403 → beta-17595，本次验证实证的缺口：
    1. ✅ **SSE 心跳（勘误+已修 00fbdda3）**：原定性「无心跳帧」错误——服务器每 15s 发标准 `: heartbeat` 注释帧（curl 100s 实测 7 条）；真根因是 SseClientV2.readSseFrame 帧级聚合把纯注释帧在函数内吞掉永不返回，外层 40s 计时器看不到进展 → 空闲必断连循环。修复：注释帧边界返回空帧标记（外层既有 isEmpty 分支刷新计时）。验证：空闲 150s 零断连 + 事件流正常接收
    2. ✅ **/api/project 只返回 canonical** → 轮询已修（32765cf6）；Project.displayName getter 本就有 canonical 回退链（name→worktree→canonical→path→id，SseEvent.kt）——UI 无需改
    3. ✅ **prompt modern 契约 400** → App 已有 legacy body retry（`modern 400 -> legacy body retry status=200`）
    4. ✅ **消息 content 内联格式**（{type,text}）→ V2Mappers.contentArray.mapNotNull 按类型计数派生 part id（#109 契约），天然兼容；501 条会话历史连续渲染实证（分页深翻+回滚全程无丢失）
  - 工时：实际 ~2h | 涉及：SseClientV2 / V2Mappers / V2ApiClient

- [x] **提问卡三态模型 + E2E-H 结案 + 双端同步复验（本次验证通过项归档）** `ui` `sse`
  - 三态模型（勾选/parked/删除）✅：Q1 自定义 Mango 保存勾选（像素 221,222,237 accent wash）→ 提交载荷 [[Mango],[Red,Green]] 恒 ≤1 互斥 → agent 复述确认收到
  - E2E-H ✅ 结案（假象确认，见上文章目）
  - 新增B 双端同步 ✅（curl 模拟设备 A → B 端 6s 内卡片消失）
  - E2E-D 404 探测 ✅ 精确重现（行为与定性一致，P2 待修不变）
  - 新增A SSE 路径 ✅ + REST 兜底两缺陷修复闭环（32765cf6，12 分钟 0 请求 → 22s 8 请求 + 列表标记出现）

### 遗留观察项（非本次修复引入）

- [x] **#143 V1 发送后"用户消息不显示"——2026-08-15 判定为误报（验证方法缺陷）** `v1`
  - 现象：V1 E2E 中发送 v1_regression_e2e_final_check 后 UI dump 找不到该文本 → 误判"消息不显示"
  - 真相（三重误判）：① Markdown 将 `_regression_e2e_` 等下划线段渲染为斜体 → 文本变 "V1 regression e2e final check"，grep 原文落空；② agent 回复本身调用了 question 工具（SINGLE 卡片 Pass/Fail），气泡形态与预期文本回复不同；③ 视口采样偏差（uiautomator 单点 dump 未覆盖消息位置）
  - 复核证据：快速导航（Room 全量 user 消息）中该消息在列；点击跳转后消息与回复完整渲染（10:57 时间标签 + question 卡片 turn）；Room/seed/NetTrace 28 条消息全链路一致
  - 结论：V1 全链路正常，无 bug；教训：E2E 文本断言需考虑 Markdown 转换（下划线→斜体）与工具卡片形态
