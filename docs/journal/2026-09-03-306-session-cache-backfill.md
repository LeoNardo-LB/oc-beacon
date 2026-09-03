# 306-session-cache-backfill（2026-09-03）

> 状态：**已完结**（2026-09-03 用户验收——依 E2E 证据拍板；实现 427b21e8 + 骨架 c9661ff1）
> 关联：backlog #306（P2）· 根因关联 #305（断开根因，已修待验收）· #304（preload 可被掐——本卡顺带缓解其表象）
> 来源：issue #6 深挖 + 用户症状（挂机久了→列表空→服务器断开→手动重连）诊断定界：会话列表纯内存单源缺陷

## 设计（骨架 commit c9661ff1，本批接线 427b21e8）

- **写入**：REST 全量基线（`SessionRepositoryImpl.setSessions`）→ `SessionCacheStore.cacheSessions`
  `replaceForServer` 事务全量替换（服务器权威快照）。SSE 增量**不写缓存**——兜底显示允许
  标题等字段陈旧到「上次 REST 拉取」（取舍：总比白屏好）。写失败仅 `AppLogger.w`，绝不阻塞基线链路。
- **读取**：`getSessionsFlow` 三路 combine（serverSessions 映射 ∥ 全局会话表 ∥ Room 兜底流）。
  判据（语义关键）：`mapping[serverId] == null` → 兜底（`clearForServer` 实现为**移除 key**——
  `SessionEventHandler:328/332` `it - serverId`，实证断连后必为 null）；映射存在（含空集）永不回退——
  防缓存盖住「服务器真的零会话」的内存语义。
- **渲染**：`SessionListStateBuilder:38-41` 原硬交集（`it.id in serverSessionIds`）在兜底态把缓存
  数据全过滤掉（白屏根因②）——改为 null 放行（兜底流已按 serverId 限定，单服务器流无串服务器风险）。
- **清理**：`ServerRepositoryImpl.removeServer` 清孤儿缓存；清理失败**不阻断**删除语义（DataStore 已删，
  若报失败 → 重试对不存在 id 行为不可靠 → 「已删却报失败」循环）。孤儿本身无害（serverId 不复用）。
- Room v6→v7（`cached_sessions` 表 + serverId/updatedAt 索引），`DatabaseModule` 注册 migration+DAO。

## 自动化验证（2026-09-03 07:00-07:07）

- `compileDevDebugKotlin` 绿（40s）。
- 目标测试 4 类绿：`SessionRepositoryImplTest`（+4：断连回退缓存/内存权威优先/落缓存 payload 无损
  roundtrip/坏 payload 跳过）、`SessionRepositoryImplDedupTest`（构造适配）、`ServerRepositoryImplCacheTest`
（新 +2：removeServer 清缓存/清理失败不阻断）、`SessionListCacheFallbackFilterTest`（新 +3：null 放行/
  空集仍过滤/正常交集回归护栏——首跑用例自身 bug：假 parent 未设 parentId，修正后绿）。
- 全量 `testDevDebugUnitTest --rerun` BUILD SUCCESSFUL（1m21s）。

## 真机 E2E（192.168.110.239:5555，dev 包）

### 阶段 1：基线+缓存写入 —— ✅ 绿（07:07）

- 新 APK `pm install -r` → `debug-entry.sh` 冷启 → `Debug channel → SessionList` 标志 OK。
- 列表 dump 满载：搜索框 + 8+ 会话条目（「排查zcode手机端连接故障」「showcase234」「沙漠与星空主题文档」
  「杭州公积金仲裁资料清单」…）+ Host-4199/会话/设置 tab——REST 基线链路完整（内存+缓存写入走通，
  无 SessionCache 失败日志）。

### 阶段 2：断连冷启动（用户症状等价复现）—— ✅ 绿（08:30 重做，subagent 完成让路后）

- 首跑（07:09）被 #302 subagent 撞车（dump 落在 device flow 对话框）——协调失误教训：
  **双 E2E 不得并发同一真机**。subagent 完成后重走干净流程。
- 流程：起点验证（app 在列表、连着、sock4199=9）→ `reverse --remove tcp:4199` → force-stop →
  无隧道 debug intent 冷启 → 12s 后 dump。
- **兜底显示实证**（核心验证点）：列表满载缓存会话——搜索框 + 8 条目与阶段 1 基线**完全一致**
  （回复生成工具使用/无标题会话×2/排查zcode手机端连接故障/showcase234/沙漠与星空主题文档/
  Dededup指令测试/查看当前工作目录路径/杭州公积金仲裁资料清单）+ **断连条幅
  「服务器已断开，正在重连…」**——旧版此处白屏（getSessionsFlow 内存空 + builder 硬交集双杀）。
- 断连态证据：logcat 08:30:45 `ConnectException: ECONNREFUSED` ×（event/project/form 多路）→
  `kicking reconnect`；无 FATAL。

### 阶段 3：恢复（reverse 恢复→自动重连→基线校正）—— ✅ 绿（08:31）

- `reverse tcp:4199 tcp:4199` 恢复 → **25 秒内自动重连**（08:31:10 `V2 SSE response: status=200` →
  `stream opened` → `Connected to server 617b2c29`），sock4199 回 9——**顺带实证恢复链路无需
  手动点连接**（用户症状第三步「得重新手动点连接」的对照：连接恢复是自动的，用户旧体验中
  「须手动」的根因在 #305 进程死亡链，不在重连器）。
- 重连后 dump：列表正常（内存权威态恢复）。

## E2E 结论

- 三阶段全绿：基线缓存写入 → 断连冷启动兜底显示（缓存+条幅）→ 恢复自动重连。
- 用户症状三步曲（挂机久了→列表空→须手动重连）的两个独立根因（#305 进程死亡 + #306 列表纯内存）
  均已修复且各有 E2E 实证。

## 附：soak（#305）影响记录与重启

- 06:52 首轮 soak（base 21218）记录了 07:12 装包 pid 变更（21218→25173，预期内）后停止于 08:02
  （多 E2E 交叉扰动窗口，样本已归档日志）。
- 08:33 以新基线 pid 27242 重启 8h soak（窗口 08:33–16:33，跨 14:33 的原 6h dataSync 边界），
  首样本绿：`isForeground=true types=0x40000000 sock4199=9`。
- 教训入档：pkill -f 模式串会匹配 bash -c 自身命令行（自杀）——分步 kill 精确 pid。

<!-- 过程中的取证/验证证据直接写本文件；backlog.md 只留 ≤3 行卡片。 -->

## 完结迁移记录（2026-09-03）

- **#306 会话列表纯内存态无持久化回填——断开/清空后白屏** `sessions` `architecture` `[x]`
  - 验收方式：用户依真机 E2E 三阶段证据拍板（断连冷启动兜底显示 8 会话+断连条幅、恢复 25s 自动重连）。
  - 交付：骨架 `c9661ff1`（Entity/DAO/Migration v7）+ 接线 `427b21e8`（三路 combine/宽容过滤/清孤儿，
    单测 +9 全绿、全量绿）。缓解面：#304 及 issue #6 家族的「列表空」表象。
