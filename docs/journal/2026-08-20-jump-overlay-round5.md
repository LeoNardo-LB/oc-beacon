# 2026-08-20 第五轮：跳转悬浮叠放瞬态 + MIUI 安装调研
> 状态：全部完结
> 迁移：2026-08-20 自 backlog.md 原文迁入（spec/journal 分离批次；原文逐字保留，未压缩）


- **悬浮叠放瞬态修复（`94f7a968`）**：用户报『一条消息浮在另一条上、agent 回复被拦腰斩断』（低概率，点好几次才复现，压力连跳未捕获瞬态）。静态机制定位：v1 门控（!jumpLockActive）在状态机终点 300ms 解锁，但稳定窗口（Displayed 后 1.5s 静默监控 + gap scrollBy 修正）仍在跑——期间 pending 分片提交使窗口边缘 turn key 裂变（remove+insert）与修正并发 → remeasure 竞态 → 单帧叠放错乱。修复：提交条件升级为『从未跳转 或 Displayed/Failed 且终点 >2s』。附带发现：JumpPhase.Idle 无生产者（reset() 全库零调用）——已用注释记录，重构候选。
- **MIUI/HyperOS 免确认安装调研（/tmp/perf-round3/miui-install-research.md，24.5KB+补充章节，23 来源）**：
  - 正门 = 开发者选项「USB 安装」（需 SIM+移动数据+小米账号认证）；无 root 无隐藏 settings 键
  - 拦截判定 = shell uid × 全新安装（覆盖 -r 同签名不弹，实测）→ **当前工作流已稳定：首装一次人工，之后永远 -r 静默**
  - 终态候选 = Dhizuku 设备所有者（adb dpm 激活，免 root 免账号常驻；代价双开/分身不可用）——仅当 houji 转专职 CI 机时上
  - docs/real-device-testing.md 待补：MIUI 安装行为差异段落（首装弹窗/覆盖静默/USB安装开关条件）
