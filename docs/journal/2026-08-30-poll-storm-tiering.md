# poll-storm-tiering（2026-08-30）

> 状态：已完结（用户验收收卡 2026-08-30）
> 关联：backlog #260（已迁出）
> 来源：E2E 顺带发现（0.3.0 验收轮 logcat 取证）→ 用户指令修复

<!-- 过程中的取证/验证证据直接写本文件；backlog.md 只留 ≤3 行卡片。 -->

## 取证与根因（Phase 1-3，/diagnosing-bugs）

### 反馈回路
- 脚本：/tmp/rel030/pollstorm.py（吃 logcat 文本，聚类 REQUEST 成轮次，断言「轮内 ≤3 + 轮间隔 ≥25s」）
- 证据输入：/tmp/rel030/verify2.txt（2026-08-29 验收轮 logcat 快照）

### 量化结构（红）

    total=83 span=242s rounds=9
    burst sizes: [11, 9, 9, 9, 9, 9, 9, 9, 9]
    round gaps(s): [26.7, 30.0 x7]
    intra-round gap ms: min=7 median=16 max=2813
    RED: BURST> 3

### 假设裁决

| 假设 | 裁决 | 依据 |
|------|------|------|
| H1 节拍式 fan-out（每轮全扫全部 location） | 坐实 | 轮间隔恒 30.0s；burst=9-11 = 1 默认 location + 8 项目目录；轮内中位 16ms 即「15-20ms 密度」 |
| H2 状态驱动反馈环（响应 dispatch 反馈驱动源） | 排除 | 轮间隔严格 30s 无加速；无状态耦合迹象 |
| H3 多轮询协程并发 | 排除 | 242s 恰 9 轮 = 单 job metronomic |

- 「4 分钟 166 次」系 REQUEST+FROM 成对计数，实际 83 次。
- 「与底部 item 高度抖动窗口重合」为伴发现象：30s 节拍 vs 分钟级流式窗口，必然交叠，非因果。

### 服务器语义实测（2026-08-30，真机 4199，curl 矩阵）
- GET /api/form/request **单 location 严格过滤**（OpenAPI spec 原文 "Retrieve pending forms for a location"；query 参数 location 为 object 型，无 all-locations 口子）。
- headerless = 服务器默认 location（server cwd project，本机 /home/leo-tkp），**非 global**——2026-08-08「headerless=global」结论在当前部署已不成立。
- 差分实验：在 oc-beacon 项目会话造 pending form（question 工具，frm_04e781409001d8p4kzJMVGGJIs，验后已 cancel）→ form 落在会话创建时的 location（本例 /home/leo-tkp 默认 location）；带 oc-beacon 目录头查询返回空，headerless 返回该 form。
- 项目清单 /api/project 返回 10 项（含 2 个 canonical=/），过滤后 8 目录 → 9 请求/轮，与 burst=9 吻合；**fan-out 宽度随服务器项目历史无界增长**——设计缺口本体。

### 修复设计（02a6ea55）
- 分层节拍：默认 location 每 30s 轮必查（SSE 新 form 最高频落点）；项目目录 fan-out 按 QuestionPollPlanner.FANOUT_ROUNDS=10（5min）一次。
- round 0 恒全扫——保 2026-08-08 冷启动纯 REST 路径 E2E-C 不变量（首轮可见 pending form）。
- **不触碰** 2026-08-18 铁律（轮询生命周期只跟随用户连接意图、永不因连接状态自停）——纯轮次分层，无 isConnected 耦合。
- 附带简化：PROJECT_LIST_CACHE_ROUNDS 轮数缓存废除（项目列表仅在 fan-out 轮拉取，~5min 一次，轮数缓存失去意义）。
- 稳态请求量：9/30s → 约 1.9/30s（-79%）；30s 级 burst 窗口消除。

### 单元验证
- QuestionPollPlannerTest（4 测试）：round0 全扫 / 稳态轮 1-9 无 fan-out / 周期节拍维持 / 100 轮频率上限。全绿。
- 全量 testDevDebugUnitTest：2168 completed，唯一失败 ChunkReproTest（/tmp/giant.md 夹具缺失，环境性预存问题，与本改动无关 → 登记 #261）。

## 实机验证（Phase 5）——GREEN

- 构建：02a6ea55 assembleDevDebug（pinned 8f7a 签名）→ adb install -r 静默覆盖 → debug-entry.sh 启动（直达会话列表）。
- 采样：adb logcat 420s（01:33:36-01:40:07），脚本 /tmp/rel030/pollstorm2.py（分层断言）。

    total=22 span=391s rounds=14
    rounds 0-8  single size=1 每 30s（仅默认 location）
    round 9(内部 round10) FANOUT size=9 at 01:38:07 —— 距连接恰 300s
    rounds 10-13 single size=1
    GREEN: tiering OK — singles=1req/30s, fanout spacing>=240s

- 对比：83 请求/242s（0.343/s，每 30s 一个 9 连发 burst）→ 22 请求/391s（0.056/s，**-84%**）；单轮 30s 间隔精确无抖动，fan-out 仅出现在 5min 节拍点。
- 结论：15-20ms 密集轮询风暴消除；REST 兜底语义（默认 location 30s + 全目录 5min + 冷启动全扫）保持。

## 验收收卡（2026-08-30）

- 验证闭环复盘：单测 4 绿 + 全量 2168（唯一红 #261 环境项）+ 实机 420s logcat 分层断言 GREEN（singles=1req/30s、fanout 5min 节拍、总量 -84%）——V1-V3 自验齐；本项无可感知 UI 变化，无 V6 时间性/主观验证项
- 验收：用户在 #266 收卡轮统一指令「清理可以结束的卡片」（本卡验证已闭环、无剩余工作）→ 当场迁移入册
- 遗留：无。fan-out 宽度随项目历史增长的根因已由分层节拍消解（5min 一次）
