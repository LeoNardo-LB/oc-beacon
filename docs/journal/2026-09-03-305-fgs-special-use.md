# 2026-09-03：#305 6h dataSync FGS 断链根治——specialUse 迁移 + onTimeout 修复

## 背景（承接 issue #6 深挖）

用户报告：「对话呆久了 → 退回会话列表空 → 服务器列表显示断开 → 须手动连接」。
前轮诊断（实验 A/B/D + 单测）定音：列表空 = clearForServer 清内存（服务销毁/断开
路径），断开根因候选 = 6h dataSync FGS 时限（设备 Android 16 + targetSdk 36 适用）。

## 注入实证（先红后修）

### 注入工具（debug-only，保留作回归）

- OpenCodeConnectionService.ACTION_SIMULATE_FGS_TIMEOUT：onStartCommand 分支
  直接调 onTimeout(startId, FOREGROUND_SERVICE_TYPE_DATA_SYNC) 模拟系统回调。
- MainActivity debug intent：--ez debug_simulate_timeout true（+ --ez
  debug_background true 先 moveTaskToBack 再延迟 1.5s 触发，模拟挂机）。
  warm start 需 -f 0x20000000（FLAG_ACTIVITY_SINGLE_TOP）保证 onNewIntent。

### 实证发现（修复前）

| 观察 | 结论 |
|---|---|
| onTimeout 触发后 "Service destroyed" 从未出现 | stopSelf 缺失——原实现依赖误读的「super 默认 stopSelf」（AOSP Service.onTimeout 为空实现），真实 6h 场景系统等几秒后抛 ForegroundServiceDidNotStopInTimeException 强杀进程（比「重启被拦」更早更暴力——这才是挂机断链+列表空的真实形态） |
| 2s 后 "Restarting service" + "Service started, action=null" | 重启逻辑执行但对活着的单例 service 只是空转（onStartCommand 再调用） |
| stopSelf 补上后服务仍 isForeground=true | HomeViewModel bindService 长持绑定——裸 stopSelf 不销毁服务也不退前台；真实场景系统仍会强杀 |

## 修复（两层）

1. specialUse 迁移（根因层）：manifest 权限 FOREGROUND_SERVICE_SPECIAL_USE +
   foregroundServiceType="dataSync|specialUse" + PROPERTY_SPECIAL_USE_FGS_SUBTYPE；
   ensureForegroundStarted 改 ServiceCompat.startForeground，API ≥34 用
   specialUse（无 6h 时限），<34 用 dataSync（时限 35 才引入，无约束）。
   官方文档双源确认（developer.android.com fg-service-types/timeout）：时限仅适用
   dataSync/mediaProcessing（24h 窗口累计 6h，同 app 共享）。
2. onTimeout 修复（防御层）：显式 stopForeground(STOP_FOREGROUND_REMOVE) +
   复位 foregroundStarted（否则 2s 后重启的幂等标志挡住重新进前台）+
   stopSelf(startId)。注释同步修正 #111 时代的误读。

## 验证

- 类型确认：真机 dumpsys types=0x40000000（specialUse），MIUI 接受，通知正常
- 场景 A（前台注入）：onTimeout → 退前台 → 2s 重启 → 重新进前台（新周期）；
  pid 连续、服务未死（binding 持有）、SSE 全程保持——用户无感
- 场景 B（后台注入）：同链路后台执行——startForegroundService 对 binding 存活
  服务投递不被 BAL 拦截（无 ForegroundServiceStartNotAllowedException），
  FGS 恢复成功。原「后台重启被拦」疑点澄清：仅在「服务已死+app 后台」才可能，
  而迁移后 34+ 无 onTimeout、<34 无时限——系统路径下 onTimeout 永不触发，
  修复层为纯防御（OEM 定制/未来政策变化兜底）
- 全量单测绿（BUILD SUCCESSFUL）

## 剩余验证（V6）

- 用户验收：正常使用若干天观察「挂机断链+须手动重连」是否复发
- （可选）真实 6h+ 挂机长测：specialUse 无时限有文档保证，长测作经验证

## 关联

- 根因方针（backlog 头部「修复方针」）首次落地实践：注入实证 → 根因层修复
- #306（会话列表持久化回填）按裁决在本卡后实施——本卡消除非自愿断开根因后，
  #306 解决「断开/冷启动时白屏」的展示层架构缺陷

## 补篇（2026-09-03 下午）：网络切换路径根因——SSE 响应头等待死区（91af62b7）

### 用户新证词改写问题面

> 「没有放 6h 这么久也会出现……网络环境改变导致的问题」——6h onTimeout 只是触发路径之一；
> 网络黑洞/切换是独立触发路径，specialUse 迁移不覆盖。

### Phase 1 反馈回路：黑洞隧道

- 仪器：PC `nc -l 4299` 黑洞（accept 持有不响应）+ `adb reverse tcp:4199 tcp:4299` 重定向
  + `adb kill-server` 断既有隧道连接（reverse --remove 不断既有——实证）。
- 红信号：黑洞期后恢复隧道，观测 app 是否自动重连（用户症状「须手动」）。

### Phase 2 红（修复前，pid 27242 build e8686593）

- 黑洞下：SSE attempt 挂死 **9min+ 零新 attempt**（PC 侧 ESTAB 到黑洞实锤）；
- 恢复隧道后 **8min 不自动重连**、条幅「服务器已断开，正在重连…」永挂、真映射连接数 0；
- 同期 248 服务器路径全程健康（独立网络路径不受累）；#306 兜底列表显示正常（顺带实证）。
- 现场顺带捕获 #304：`Failed to pre-load sessions: Request timeout has expired [120000 ms]`。

### Phase 3/4 根因定界（打点 + 源码）

- 候选淘汰：5min 冷却（全程无 Entering cooldown——黑洞形态是 IOException 不计 timeout）、
  backoff 无限增长（attempt 根本没死，无 backoff 可言）。
- 定罪：**V1/V2 SSE 客户端 `socketTimeoutMillis = Long.MAX_VALUE`**（SseClient.kt:180 /
  SseClientV2.kt:118）在「等待 HTTP 响应头」阶段形成无超时死区——#108 流内 40s 心跳防护
  只覆盖**响应到达之后**；黑洞/半开隧道（FIN 不达）下 `execute` 永挂 → 重连协程挂死 →
  单飞门（`Reconnect already in progress, skipping`——日志实锤）被永久占用 → **永不自动重连**。
- JVM 诊断（SseTimeoutDiagTest，跑完即删）：缺省注入值 45s 生效（elapsed=45422ms），
  排除「per-request 配置不生效」疑点。

### Phase 5 修复（91af62b7）

- 两客户端 `connectToEvents/connectToGlobalEvents` 增 `socketTimeoutMs` 参数，
  缺省 `SSE_SOCKET_TIMEOUT_MS = HEARTBEAT_TIMEOUT_MS + 5s = 45s`（internal 可测）。
- 约束（注释入档）：必须 **大于** 流内心跳 40s——流中应用层 #108 防护先触发，本值不改流行为，
  仅封顶响应头等待。
- 回归测试 ×3（SseResponseHeaderTimeoutTest）：真 ServerSocket 黑洞 + 真 Ktor/OkHttp——
  V1/V2 注入 500ms 必须失败终结（挂死形态=外层 TimeoutCancellation 判假红）；缺省值
  防退化断言（40s < x ≤ 120s）。踩坑：runTest 虚拟时间令 withTimeout 瞬时触发——真时钟
  runBlocking。

### Phase 6 E2E 绿（pid 1423 build 91af62b7，打点 [305net] 已清理）

- 黑洞期：**45s 周期重试循环**（`Socket timeout expired [socket_timeout=45000]` → attempt →
  execute → 循环，打点铁证）；
- 恢复隧道后 **35s 自动 Connected**（`response headers arrived: 200` → `Connected to server`）
  + 条幅自动消失（banner_count=0）+ 列表正常。

### 对照矩阵（同场景修复前后）

| 信号 | 修复前 | 修复后 |
|---|---|---|
| 黑洞期 attempt | 1 次后永寂（挂死 9min+） | 45s 周期持续 |
| 恢复后自动重连 | 8min 不连（观测窗口内永不） | 35s Connected |
| UI 条幅 | 永挂「正在重连」 | 恢复即消 |
| 用户动作 | 须手动点连接 | 零操作 |

### 状态

- 网络路径 E2E 绿 + 6h 路径（specialUse+onTimeout）此前已绿——#305 两条触发路径均闭环。
- soak 以 pid 1423 重启（09:49–17:49，跨 15:49 六小时边界）作 6h 路径的加时长证据。
- 用户预授权关闭条件（「真机端到端测试可以确定修复了，就可以关闭」）已满足；
  完结迁移待 soak 收尾一并执行。

## 完结迁移记录（2026-09-03 11:40）

- **#305 FGS 断链根治（双路径）** `service` `sessions` `sse` `[x]`
  - 用户预授权关闭条件达成：6h 路径（注入 E2E：onTimeout→退前台→重启→FGS 恢复，pid 连续/
    SSE 保持；dumpsys types=0x40000000）+ 网络路径（黑洞 E2E：45s 周期重试、恢复 35s
    自动 Connected、条幅自消）双绿。
  - 交付：`e8686593`（specialUse 迁移+onTimeout 修复）+ `91af62b7`（SSE socketTimeout 死区）。
  - soak 观察哨继续至 17:49（跨 15:49 旧 6h 边界）——若出异常另开新卡（specialUse 无时限
    已有官方文档双源保证，预期绿）。
