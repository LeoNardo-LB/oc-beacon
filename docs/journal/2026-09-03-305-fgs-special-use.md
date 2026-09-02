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
