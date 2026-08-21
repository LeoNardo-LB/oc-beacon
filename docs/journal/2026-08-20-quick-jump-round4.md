# 2026-08-20 第四轮：快速定位渲染缺陷根因修复 + 三性能项
> 状态：无未决条目
> 迁移：2026-08-20 自 backlog.md 原文迁入（spec/journal 分离批次；原文逐字保留，未压缩）
> 子条目「overlay HUD 真机授权走查」提升为卡片 #167


- **快速定位渲染缺陷（用户真机报告：气泡不完整/非从头回复 + 『未找到任务』弹窗）——四处根因全修**：
  - `25a20535` ① pendingJumpTarget 回调路径漏分片适配（三条跳转入口唯一漏网——display 粒度 index 直传 scrollToItem，窗口内有分片 turn 时落点错位=截图主源）；② 状态机 `it.key == targetKey` 精确匹配对分片 key（t_xxx#cN）必失败→5s 超时（findJumpTargetItem 前缀匹配取首 chunk）；③ loadAround 未命中直接报错（重试一次再判）
  - `8347acd0` ④ 跳转期间 B-F2 分片提交无门控——跳转窗口扫过触发 key 裂变使已算好的 index 失效（Q33 复现落进文章 chunk 中间；补 !jumpLockActive 门控与 auto-load 同款）
  - 验证：真机连跳序列（Q25/Q26/Q33/Q35）全部落点精准，目标气泡完整置顶；FindJumpTargetItemTest 6 用例 + 全量单测绿
  - 方法论教训：视觉模型提问会被引导性措辞污染（先问『有没有问题』三个落点全报有问题，改中性事实描述后 J2/J3 实为完美）——截图取证必须用中性提问
- **Baseline Profile（`5b284b4c`）**：手工规则圈 chat UI 热路径 + Compose lazy/runtime/text + mikepenz + 协程；APK 含 assets/dexopt/baseline.prof、真机 ProfileInstaller 安装日志确认。收益为官方 ~30% 口径（本 App 实测增量需 macrobenchmark 基建，未建——诚实边界）
- **PerfMon 观察者效应（`dc57cba0`）**：FrameMetrics 按 VSYNC 去重（b/206956036）+ dropCount 记账入 HUD + PerfHudOverlay 独立悬浮窗（纯 View 直绘、独立帧流零污染；无授权回退同窗口 HUD）
- **遗留登记**：
  - [x] **P3：慢拖 ~18ms 偶发尖刺 A/B** `perf` ✅ 2026-08-21 完成——**结论：预取窗口 ahead=0 vs 1 无显著差异，假设否证，常量定 0**。三轮真机数据（houji devDebug + gfxinfo framestats，验收测试会话AB 12 次慢拖 ×3700 帧/轮）：ahead=1 → p50/p90/p95 = 7/12/14ms、≥17ms 帧 2.32%；ahead=0 → 7/12/14ms、2.22%（重复轮 2.0%）——差异在轮间噪声（±5%）内，百分位完全一致。与 08-20 PerfMon 初评（anim 相位爆发与预取无关）互证；`PREFETCH_AHEAD_SLOW_DRAG` 已定 0（分片后 edge 预取组合对慢拖帧预算是净负担）。证据 /tmp/ab18/（armA/armB/armB_repeat + 聚合直方图）。**基建坑位**：① devDebug 装包弹窗已由 `scripts/miui-install.sh` 无人值守解决；② 慢拖方向必须手指向下（500→1600）——见 real-device-testing.md E2E 纪律新增条目（曾致 0 帧误判两轮）
  - [ ] **P3：overlay HUD 真机授权走查** `dev-infra`——悬浮窗权限授予 + overlay 显示/dropCount 读数验证（代码已交付 dc57cba0，未真机走查）

> **✅ overlay HUD 真机授权走查完成（2026-08-22，#167 结案）**：临时让 devDebug 复用 release 签名（与装机 devRelease 同指纹 8fbc13…，install -r 覆盖不卸载保数据；走查后已还原构建配置并重建 devRelease 装回）。授权：MIUI 忽略首次 `appops set`（回退 default 且 app 自动弹系统授权页），装机后重发一次 `appops set … SYSTEM_ALERT_WINDOW allow` 生效——`canDrawOverlays` 翻真走 overlay 分支。验证：①dumpsys 实证独立窗口（ty=APPLICATION_OVERLAY、appop=SYSTEM_ALERT_WINDOW、TOP/START 定位与代码一致）；②服务器列表静止读数 `~4fps p50 240.2 p95 390.8ms over 100% jank 5 (5f)`——无 drop 段=droppedReports=0 正确省略；③进会话滚动（中速×3+激进 fling×3）读数全维度刷新为 `~117fps p50 8.5 p95 258.4ms over 50.6% jank 39 (77f drop119)`——**drop119 = 丢样计数 >0 显示分支实证**（高负载 FrameMetrics 丢 119 报告）。HUD 数据链（FrameMetrics→HudData→overlay.update）端到端工作。
