# 2026-08-21 GitHub issue #1 遗留调研批次（V1 连接速度）
> 状态：部分完结（活跃 #150）
> 迁移：2026-08-20 自 backlog.md 原文迁入（spec/journal 分离批次；原文逐字保留，未压缩）


- [ ] **#150 V1 连接速度慢于 beta.4 误判 V2——探测复用 + 预加载/SSE 并行化** `perf` `v1`
  - 来源：GitHub issue #1（ISuuuu）遗留反馈"连接方式 v1 连接速度没有 0.3.0-beta.4 的 v2 快"（报错部分已由 4c2b6d8a 修复并经报告人确认）
  - **调研结论（完整证据链见 docs/research/issue-1-v1-connect-speed-2026-08-21.md）**：beta.4 的"v2 快"是误判产物——V1 1.18.18 过渡形态 /api/health 返回 {"healthy":true} 被判 V2，随后 preLoadSessions 在 /api/project 的 SPA HTML 上快速失败被整体跳过（6 步串行缩为 3 步；本机回环实测 165ms vs 37ms）。真实根因：① runSseConnectionLoop 把 preLoadSessions（/project + N×/session + N×/session/status）**串行放在 SSE 之前**，"已连接"翻转被整段阻塞；② 每次手动连接都重新双探（V2-first 白跑一次 RTT，已持久化的 apiVersion 不复用）；③ V1 特有 /session/status 每目录一串往返 + Windows /project 冷调用慢（实测首调 214ms vs 热 8ms）
  - 修复方向（按收益）：探测结果复用 + 后台重探（保留 #132 UNKNOWN 语义）→ preLoadSessions 与 SSE 并行（首事件立即翻转已连接，preload 并发补）→ 项目间并行拉取（并发 2-4）。**不建议**复现 beta.4 误判行为（#83 回归）
  - 已排除：SSE 首事件延迟（两版本握手即推 server.connected）、心跳节奏、payload 包装解析、初始消息分页、认证方式——均实测/代码验证无差异
  - **与 #132 的表面矛盾与调和（2026-08-21 补充，实现前必读）**：#132 规则"探测失败保留原 apiVersion"之所以安全，前提是**现有流程每次连接都重新探测验货**——持久化版本只是兜底，永远被新一轮探测纠正（服务器 V1→V2 升级会在下次连接被发现）。而"探测复用"方向若实现为裸跳过探测，等于删掉验货环节：服务器升级后客户端永久用旧版本路径 → V1 路径打 V2 → SPA HTML → **复发 #132 当初修掉的症状**（JSON 解析错误 + SSE 假死，与 issue #1 原始报错同貌）。三个调和方案：
    - 方案 A（并行验货）：连接立即用持久化版本发起，探测后台并行；结果不一致则掐断重连。最快但状态机复杂（探测与 SSE 握手竞态、先连后纠的闪烁），为罕见事件（服务器升级）不值
    - **方案 B（按已知版本排序探测，推荐）**：detect() 先探持久化版本（V1 服务器探 /global/health 一次即中），失败/HTML/非 JSON 才回退现有 V2-first 双探；持久化 UNKNOWN 则维持现状。V1 连接 2 RTT → 1 RTT，V2 维持 1 RTT，零新增等待；探测失败路径自然落入 #132 的 UNKNOWN 保留语义（严格不破坏）。改动最小（仅 ApiVersionDetector.detect 排序 + 传入已知版本）
    - 方案 C（跳探 + HTML 自愈）：不探测，连接路径上 rejectHtmlResponse 命中 HTML 时触发重探重连。最快但把版本知识泄漏进 SSE/REST 层、错误发现滞后到用户可见的失败连接，不取
  - **推荐组合（2026-08-21）**：方案 B（探测排序）+ 方向②（SSE 先行、preload 并行补，主要收益所在——实测 preload 串行 ~134ms 占 165ms 总耗时的大头，双探仅 ~19ms）+ 方向③（项目间并发 2-4）。预期：V1 首连感知 ~165ms → ~40ms 量级（SSE 首事件即翻转已连接），且 #83 交叉验证与 #132 UNKNOWN 语义双双完整保留
  - **2026-08-21 实现完成（worktree 分支 fix/150-v1-connect-speed，基点 a27236c7，commit ed466966）**：方案 B（detect 增 knownVersion 参数按持久化版本排序探测，双探兜底语义不变）+ 方向②（preload 移入并行 job，SSE 首事件即翻转已连接；finally cancelAndJoin 串行化护栏防跨轮重叠写 eventDispatcher）+ 方向③（preLoadSessions 项目间 Semaphore(4) 并发，setSessions CAS 合并语义并发安全）。新增单测 4 例（排序 3 + SSE 先行 1）+ 孤儿守卫用例观测点更新为新架构等价性质；编译 ✅ 全量单测 --rerun ✅
  - **模拟器 E2E（Pixel6_Android36，V1=1.18.18 隔离实例 + V2=0.0.0-beta-17728 真实 4 项目 200 会话）**：基线/新版 APK 均本地 clean 构建 + dex 字符串验证（防 Gradle checkout 缓存陷阱——首次对照因 UP-TO-DATE 误判全部跑了同一 APK，clean 重建后重测）。结果：① V1 冷首连 attempt→Connected 81-138ms → **25-43ms（~3×）**，Connected 全部先于 Pre-loaded（SSE 先行铁证）；② V1 热复连 283-313ms → **169-220ms**，基线每轮 cross-check 白探、新版 known=V1 单探即中零白探；③ V2 冷连 Connected 231ms 先于 Pre-loaded(200 sessions/4 projects) 完成 213ms；④ **升级场景真机复现**：known=V1 + 实际 V2 → V1 探测 HTML 拒绝 → 回退 V2 当次纠正，attempt→Connected 101ms；⑤ 全程 FATAL=0。证据 /tmp/e2e150b/（14 份 logcat）
  - **2026-08-21 已合回 master**（merge 25927de5，合并后主工作区编译 ✅ + 全量单测 --rerun ✅）；剩余待办：真机复验（2026-08-20 方针真机优先——本轮按用户指示用模拟器）+ 随下次 dev 发版交付
