# 2026-08-20 第三轮：开发用性能检测系统 + 残余卡顿闭环
> 状态：无未决条目
> 迁移：2026-08-20 自 backlog.md 原文迁入（spec/journal 分离批次；原文逐字保留，未压缩）
> 子条目「慢拖残余尖刺」提升为卡片 #168


- **性能检测系统（090507be + f3c62ae7）**：应用内常驻 PerfMon——Window FrameMetrics 监听 + 七相位分解（input/anim/layout/draw/sync/gpu/swap）+ 滚动窗口统计（真实刷新率推导预算）+ jank 事件日志（AppLogger/Diagnostics 可见）+ 稳态采样器（窗口 over%>25 时每 2s 输出摘要+期间最差帧相位）+ HUD（am start --ez debug_perf true 开启，仅 debug）。单测 5 用例。替代外挂 gfxinfo/perfetto 管线——本轮全部定位都由它完成。
- **B-F5 修复（a6156cdf）**：isAtBottom 三处大作用域订阅下沉（Controller 暴露 State / 双 key effect 改 snapshotFlow 双值流（铁律语义等价）/ FAB 读取下沉小作用域）。实测：慢拖 anim 相位爆发 25-33ms 全消、jank 20→10 条；长消息中央 anim 25-28ms 全消、jank 16→6 条。
- **debug vs release 定量对比（PerfMon 同口径，本轮最重要结论）**：慢拖 p95 15→7.9ms、p50 7.4→6.1ms；长消息 p95 12→7.6ms；anim 相位 3.4-5.5→0.2-1.0ms；稳态超预算 ~40%→基本预算内。**debug 构建税 = p95 的 ~47%**（JIT+Compose 调试钩子+无 R8 复合）。此前的 R8-on-debug 实验只隔离了 R8 单变量（无改善），完整 release 语义差距显著。
- **MIUI 安装通道经验**：全新安装（非覆盖）一律弹用户确认（pm/cmd package/session 均拦），需用户点允许；覆盖升级（同签名 -r）静默。debug↔release 签名切换需 uninstall 重装（数据经 intent 重配）。
- **调研沉淀（/tmp/perf-round3/research.md，357 行 31 来源）**：FrameMetrics 产自 app 进程 HWUI 与 HyperOS SF 无关（可信）；回调须拷贝+去重（b/206956036）；JankStats 1.0.0 无相位分解；graphicsLayer 加 item 可实现纯平移但有条件与代价。
- **遗留登记**：
  - [x] **P3：PerfMon 观察者效应改进** `perf` `dev-infra` ✅ 2026-08-20 第四轮交付（dc57cba0：VSYNC 去重/dropCount 记账/独立悬浮窗 HUD）——悬浮窗授权真机走查待用户
  - [x] **P2：Baseline Profile** `perf` ✅ 2026-08-20 第四轮交付（5b284b4c：APK 内 baseline.prof + 真机 ProfileInstaller 安装日志确认）
  - [ ] **P3：慢拖残余 ~18ms 偶发尖刺** `perf`——F5 后残余（draw 4-8ms + input 3-5ms），量少（12 轮 10 条）。**2026-08-21：候选「预取 idle_frame」已否证**（见第四轮 A/B 条目——ahead=0/1 无差异）；如再深挖方向应为 draw/input 相位本身（release 口径 p95 7.9ms 已低于感知阈值，优先级维持最低）。工时 ~2h | 难度：中
