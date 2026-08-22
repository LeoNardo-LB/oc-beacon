# 验收收口批次（acceptance closeout，2026-08-23）

> 状态：进行中（V1 真机 E2E 子代理执行中；#192 grilling 进行中）
> 关联：迁出 #151 #152 #153 #155 #156 #157 #160 #162 #164 #165 #170–#175 #189 · 新登记 #192
> 来源：用户验收对话（2026-08-23）

## 一、用户验收结果（原话摘录）

| 项 | 用户原话 | 判定 |
|----|---------|------|
| #189 终端手感 6 步（vim 插入/ESC/CTRL 锁存/缩放长按/中文 IME/tab 字号） | 「ok」 | ✅ 通过 |
| #155 提示音 5 项 | 「2.4 校验不了，其他ok」 | ✅ 通过——2.4（错误 streak 只响第一声听感）人工不可测；JVM 矩阵 12 例已覆盖该分支，接受 |
| #164 抽屉留白观感 | 「ok」 | ✅ 通过 |
| GKD 决策 | 「gkd 这个关闭掉吧，这是极端场景用户需要为自己的行为负责」 | #162 遗留条件（GKD 重开场景）作废收卡；#165（GKD 税缓解）价值消失收卡；原「FAQ 注明 GKD 排除规则」文档建议同步放弃 |
| V1 真机校验 | 「V1 真机校验请你启动V1服务器进行真机端到端测试」 | 交 agent 执行（§三，进行中） |
| 橡皮章（#151/#152/#153/#156/#157①/#160） | 「ok」 | ✅；#153 另有硬证据：gh api 实查 CI artifacts ocbeacon-mapping-0.3.1-dev.20 / dev.21 均存在未过期（2026-08-23） |

## 二、迁移卡片（原文逐字 + 收口行）

### P1

- [~] **#151 GitHub 上报——代码全量完成（a68263b5..6b623f51），1826 测试绿，真机禁用态验证过；待维护者注册 GitHub App 填凭据后激活 E2E** `ui` `data`
  - 四模块：device flow 认证（SecretCipher 加密存储）/ API 客户端（指纹查重/建/评）/ 上报服务（双轨指纹+24h 防刷）/ Diagnostics UI（六分支状态机+15 语言）；双缝测试 13 条
  - 激活清单：注册 GitHub App → BuildConfig 填 client_id/secret → 真机走授权/建 issue/命中评论 E2E
  - → \`docs/specs/2026-08-21-error-report-github-design.md\` · \`docs/journal/2026-08-21-error-report-github.md\`
- ✅ 收口：激活前置全部消除——用户本人 2026-08-22 走完全链路（App 4682795 注册+安装；手机授权 device flow；真实建 issue #2；同指纹复提命中「已追加到 issue #4」评论且 gh 验证 comments=1；CI 凭据注入 dev.21 实跑），证据 \`2026-08-22-ui-batch.md\` §#151 三节 + 查重两连修。橡皮章 ok。

- [~] **#152 前置：日志分级修复——已实现（f535e15d/f398b7f3/2a07ad74），真机风暴验证 PASS，待用户验收** `sse` `refactor`
  - 三组修复：风暴环 i→d 全降 + 双 e 记录消除 + 4 处补 throwable + per-event 门控 + WebView 主帧/子资源分流 + 遗留标签删除；真机实证断连窗口 6D+5I+0E（残留 I 均一次性里程碑）；附带 #186 测试脆弱当场根因修复（两次连续全量绿）
  - → \`docs/journal/2026-08-21-error-report-github.md\`
- ✅ 收口：真机风暴验证 PASS 在案；橡皮章 ok。

- [~] **#153 前置：release CI 留存 R8 mapping.txt artifact——已实现（cfeae270），待下次发版 CI 实跑验证** `refactor`
  - workflow 增 Upload R8 mapping（90 天 + if-no-files-found=error 防 minify 回归）；路径模式经本地 outputs/mapping/<variant>/mapping.txt 实证（devRelease 产物在）
  - → \`docs/journal/2026-08-21-error-report-github.md\`
- ✅ 收口：CI 实跑验证完成——dev.20 / dev.21 mapping artifacts 均存在未过期（if-no-files-found=error 未触发 = minify 正常）。

- [~] **#155 会话内提示音：被抑制的系统通知转为提示音+震动，严格镜像系统通知策略** `ui` `sse`
  - 前台会话 turn 结束/权限/问题/错误事件现状零反馈 → 补提示音+震动，策略完全镜像系统通知四层静音矩阵；错误 streak 只响第一声；零新增设置项（含 VIBRATE 权限与通知侧 streak 去重）
  - spec 已定案（grilling Q1–Q12 + F1–F5），实现前必读；模拟器无音频输出，维度 5 必须真机实测
  - 落地（2026-08-21，\`23e38a00\`）：策略管线纯函数 + streak 通知/提示音双侧 + 独立去重 + VIBRATE；测试 18 例全绿；真机双分支实证（聚焦=提示音零通知 / 非聚焦=通知照常）；待用户维度5听感验收
  - → \`docs/specs/2026-08-21-in-session-audio-feedback-design.md\` · \`docs/journal/2026-08-21-in-session-audio-feedback.md\`
- ✅ 收口：真机 1/2/3/5 过（正常档响+震 / 静音档 / DND / 渠道铃声镜像）；2.4 错误 streak 听感人工不可测，JVM 矩阵覆盖；用户 ok。

### P2

- [~] **#162 真机滚动「还是卡」→ 帧级取证三层根因全修——待用户验收（GKD 重开场景）** `ui` `perf`
  - 三根因全修：重组风暴（慢拖 janky 41.7%→0.88%）、巨型消息分片（p95 400ms→9ms 级）、GKD 无障碍税（环境因素，App 内无低风险修复）；GKD 关闭场景用户已验收「十分丝滑」
  - 遗留条件：GKD 重开且卡顿回归时按根因③结论处置
  - → \`docs/journal/2026-08-20-scroll-jank-investigation.md\`
- ✅ 收口：GKD 重开遗留条件作废（用户决策：极端场景用户自负）。

- [~] **#164 主对话抽屉高度统一（min = max = 75% 屏高）——待验收观感** `ui`
  - 四抽屉 + SystemPromptDialog 固定 75% 屏高；真机 E2E 像素级全 PASS（顶边逐像素一致、空内容撑满）
  - 待用户验收：空内容抽屉底部留白观感
  - → \`docs/journal/2026-08-20-drawer-height-75.md\`
- ✅ 收口：空内容抽屉底部留白观感 ok。

- [ ] **#165 长文本 Part 级 semantics merge（GKD 税缓解，条件性价值）** `perf` `a11y`
  - GKD 已长期关闭主收益消失；仅 GKD 用户重开才有价值。A/B 中止线已定：GKD 关 p50 回退 >2ms 或 p95 改善 <15% 即 abort（~3h）
  - → \`docs/journal/2026-08-20-scroll-jank-investigation.md\`（提升自该批子条目）
- ✅ 收口：GKD 缓解价值消失（同 #162 用户决策），不做。

### P3

- [~] **#156 Room 缓存行 tokens 持久化缺口——已修，待用户验收** `data` `storage`
  - c71ac4ec：SSE_PRIORITY 合并 CAS 检测 tokens 变更→增量落库；真机 E2E 复验 PASS（44/45 落库，19.1 万行 logcat FATAL=0）
  - → \`docs/journal/2026-08-19-final-regression.md\`
- ✅ 收口：橡皮章 ok。

- [~] **#157 离线态终端 sessionDirectory=null + 输入框层级缺失——观察①已修待验收** `terminal` `edge-case`
  - 观察① reloadDirectory 兜底已修（de96758c）；观察②定性为不可达路径关闭（离线冷启停在连接页无法进会话）
  - → \`docs/journal/2026-08-20-scan-round2.md\`
- ✅ 收口：橡皮章 ok（观察②不可达定性在案）。

- [~] **#160 LeakCanary 报 OpenCodeConnectionService$LocalBinder 泄漏——已修，待用户验收** `leak` `service`
  - d8331596：孤儿 job 取消 + SSE takeWhile 守卫 + connect 入口守卫 + HomeViewModel 卫生项（红绿验证，全量 1758 绿）；结构性根治（Router 抽取）按需另立项
  - → \`docs/journal/2026-08-20-queue-todo.md\`
- ✅ 收口：橡皮章 ok；结构性 Router 抽取维持按需另立项；浸泡增益证据随 V1 批次顺带（若设备构建含 LeakCanary，非阻塞）。

- [~] **#189 终端组件换件：termlib → Termux terminal-view/emulator（vendored）** `terminal` `ui` `arch`
  - 用户验收②+明确指令「bug 挺多，最好引入主流的终端组件」。真机取证：vim 插入模式打字被 IME 组合输入拦截（「全部」候选态）、ESC 无响应——termlib 0.1.0 早期版本键盘/IME 处理不成熟且依赖闭源不可修
  - 选型：Termux terminal-view + terminal-emulator（10+ 年亿级验证；仓库 GPLv3 但两模块为 Apache 2.0 明确例外，MIT 兼容）；vendored 源码引入（无 maven artifact）
  - 换件范围：VT 内核 + 渲染/键盘 View 层；PTY WebSocket 传输（ServerTerminalWorkspace）保留，adapter 换实现；KeyboardOverlay 保留
  - 落地（2026-08-21 真机闭环）：6bb577e0 spec → b76919c7 vendor → 53837c7b 桥接+UI+依赖切换 → 82559a26 六根因修复；vim 试金石过、完整回环实证（journal §验收问题②）；待用户维度5手体验收
  - → \`docs/specs/2026-08-21-terminal-component-swap-design.md\`
- ✅ 收口：用户 6 步手感全过（vim 插入/ESC / CTRL 锁存中断 / 双指缩放与长按复制 / 中文 IME / tab 切换 / 字号联动）。

### P0 架构评审批次 #170–#175（链条整体解除）

验收链：2026-08-22 批次末统一验收 17 项 15 过（\`2026-08-21-arch-review-deepening.md\` §批次末验收结果）；两问题闭环——①后台通知未弹 = MIUI 对旁装载应用渠道默认关悬浮/声音/振动（非 app bug；设备侧开启 + ac338b49 设置页「发送测试通知/系统通知设置」根治可见性）；②终端 bug 升级 #189 换件 Termux（2026-08-23 验收通过）。#189 通过即验收链条解除，六卡整体迁出：

- [~] **#170 架构评审候选 2：连接生命周期协调器 ConnectionLifecycleCoordinator——已实现，待用户真机验收** `refactor`
  - 三段式落地（d3baf95c/b297e47e/d21a45f5）：connect 七步/disconnect 四路单点化，双份 teardown 合一；registry 真相源 + FGS 回调派生；10 条 JVM 测试 + 全量单测过（1 例无关 flaky 已记录）
  - 真机 E2E 四场景过：连接（幂等真实触发）/断开/重连/飞行模式恢复（SSE 自愈）；待用户验收 UI 状态观感（维度 5）
  - → \`docs/journal/2026-08-21-arch-review-deepening.md\` · \`CONTEXT.md\`
- ✅ 已验收（批次末清单 A 组：断开/重连/飞行模式恢复 3 项过）。

- [~] **#171 架构评审候选 3：未读红点时钟域收进 interface——已实现，真机 E2E 全绿，待用户验收** `refactor` `data`
  - 三段式（a048b1ea/2231d301/941f17f8/a33d0d27）：UnreadEvent 事件化封死客户端时钟域泄漏（漏斗载荷提取 + DB 回环 seedCachedMessages 隔离）；已读侧全吸收（Signal 删除/判定入模块）；1808 单测 + 真机红点四态+双持久化全绿
  - ⏳ 维度 5（红点观感）待验收；错误红点真机无触发手段（JVM 覆盖）
  - → \`docs/journal/2026-08-21-arch-review-deepening.md\`
- ✅ 已验收（批次末清单 B 组：红点显示/消费消除/冷启动持久化/一键已读 4 项过）。

- [~] **#172 架构评审候选 4：V1/V2 seam 泄漏收编——已实现，真机 V2 E2E 全绿，待用户验收** `refactor`
  - 取证修正后落地（2a0bb5a6/f8521376/2de6889e）：PaginationCursorPolicy 收编 6 泄漏点（isV2 从 domain/UI 绝迹）+ ServerCapabilities 门控（god-client 显式不拆 #185）；真机实证服务器原生 cursor 续页 + V2 门控位
  - V1 无真机服务器（JVM 契约覆盖，与 #150 复验一并）；⏳ 维度 5 分页观感待验收
- ✅ 已验收（批次末清单 E 组：分页拼接/快速定位过）。V1 真机补验今日由 agent 执行（§三）。

- [~] **#173 架构评审候选 5：ChatViewModel 按状态簇重组——已实现，真机对话全生命周期 E2E 全绿，待用户验收** `refactor` `ui`
  - 四段串行（b511eef5/7c5f9cd9/55b803ba+22a4cff9+007bb527/d4601004）：Terminal 迁出 + 4 簇门面 + UI 三子组件按簇迁移（28 处）+ uiState 退役（生产零消费，ChatUiState 删除）；跨簇编排留薄 VM
  - 深化（2026-08-21，e9731b12）：25 个零调用死转发删除 + sessionOps 第 5 簇；公共成员 111→93；全量绿 + 真机冒烟
  - 真机实证 FSM 全链（Idle→Busy→Streaming→Idle force-complete）+ composer/conversation 簇路径；⏳ 维度 5 观感待验收
- ✅ 已验收（批次末清单 E 组 13–17：长会话分页/快速定位/草稿恢复/模型 agent 切换/终端进出过）。

- [~] **#174 架构评审候选 6：SessionStateService 8 回调旋钮 → 1 必需协作者——已实现，真机烟雾全绿，待用户验收** `refactor`
  - f179ad70+ab2c36c3：SessionStateCollaborator 构造注入（漏接=编译错误），EventDispatcher 接线块迁入 Impl，1808 单测全绿；真机 FSM 完整生命周期经新接线实证（含 force-complete×2）
  - ⏳ 维度 5（FSM 状态 UI 观感）待验收；僵尸场景（3min busy）JVM 覆盖
  - → \`docs/journal/2026-08-21-arch-review-deepening.md\`
- ✅ 已验收（批次末清单 C 组：busy 计时/流式/完成转 idle 2 项过）。

- [~] **#175 架构评审顺手清理四件 + bonus——已实现，真机烟雾全绿，待用户验收** `refactor`
  - 四件全落地（65a51723/67d496f3/276f2850/d757d499）：双调用点合一（子会话 else 分支真机实证）· 删三壳（Boolean 签名保留+契约测试）· 双胞胎合并 · ScrollPositionDelegate 死代码删除；bonus：repo deprecated trio 三层删除
  - 全量 1805/1805 绿（-3 死代码测试 +2 契约测试）；→ \`docs/journal/2026-08-21-arch-review-deepening.md\`
- ✅ 已验收（批次末清单 D 组：滚动/子会话过；D-12 通知问题①已定性 MIUI 渠道并闭环）。

## 三、V1 真机端到端（#150/#172 补验，已完成）

- 指令来源：用户「V1 真机校验请你启动V1服务器进行真机端到端测试」
- 关键约束（已查明）：装机 dev.21 非 debuggable（dumpsys pkgFlags 无 DEBUGGABLE，2026-08-23 实查）→ debug intent 不可用，V1 服务器须 UI 自动化添加；宿主 V2 服务器（4199 / opencode2.exe）不得干扰；**全程只读**（不发消息、不建/删会话）
- 验证面：版本探测落 V1（#150 排序与 UNKNOWN 不降级）· 会话列表加载 · 长会话滚顶「加载更早」V1 本地 {id,time} 锚点游标（#172）· 快速定位 loadAround（V1 单向）· FATAL 监控
- 证据 → `docs/journal/2026-08-23-v1-device-e2e.md`（子代理直写，100 行 8 节）

### 结论（2026-08-23 子代理三轮报告整合）

| 验证面 | 判定 | 要点 |
|--------|------|------|
| V1 服务器启动 | ✅ | opencode-ai 1.18.18 隔离 HOME pid 867383@4198；处置上批次遗留孤儿实例 3200018（EADDRINUSE） |
| 探测落 V1（#150） | ✅ | logcat `Detected V1 API (version=1.18.18, known=V1)`；known=V1 单探即中零白探；/api/health 过渡形态被交叉验证正确拒绝；SSE 先行 96ms（Connected 04.237 → Pre-loaded 04.774） |
| 会话列表（V1 REST） | ✅ 空态 | Pre-loaded 0 sessions / 1 project；「目录为空」正常渲染 |
| 加载更早（#172 V1 锚点） | ⬇️ 降级 | 真实库（322 会话/20387 消息）挂 V1 报 `no session table`（V1/V2 存储不兼容二次实锤）+ 只读纪律禁造历史 → JVM 契约测试仍是唯一覆盖 |
| 快速定位 loadAround | ⬇️ 降级 | 同上（无长会话可跳） |
| FATAL | ✅ 0 | 前后 crash buffer 均 0 |
| 收尾清理 | ✅ | UI 删条目（GLM-4V 核验无 4198 残留）/ reverse 清 / 进程杀；V2 服务器全程无恙（实为 LAN 192.168.110.68:4199 直连——任务背景 reverse 说法勘误存档） |
| LeakCanary 浸泡 | ⏭ skip | 装机为 release 构建，LC 仅 debugImplementation |

判定：**#150 V1 探测复验真机闭环 ✅**；#172 V1 分页维持 JVM 契约覆盖（存储不兼容属上游断代非客户端缺口）。遗留发现 3 条见 V1 journal §八（孤儿实例清理惯例 / known=V1 条目已删后续复验需 UI 重加 / 存储不兼容实锤）。

补充轮（§十）：UI 表单添加路径补做闭环——保存即探测（known=UNKNOWN 首探落 V1）、连接双轮验证、`input text` 纯 ASCII 在 release 构建可靠；额外正面实证：V1 遇 V2 格式 opencode.jsonc 触发 ConfigInvalidError → 客户端 W 日志跳过该项目零崩溃（异构配置优雅降级）。V1 E2E 全量收口。

## 四、#192 新登记（本批）

- [ ] **#192 双 FAB 会话级滑动隐藏/展示：左（跳到底部）左滑收起→左缘半透明拉杆；右（菜单）右滑收起，展开态先收拢成按钮** `ui`
  - 交互细节 grilling 定案中（持久化范围 / 与「滚离底部自动出现」的优先级 / 拉杆恢复手势与形态 / 菜单收拢编排 / 隐藏期角标保留）；定案后补 spec 链接
  - → \`docs/journal/2026-08-23-acceptance-closeout.md\`

## 五、机器批次剩余（主会话执行）

- #159 jumpLock 派生化收口（~1h，含 2s 时窗语义设计）
- V1 E2E 结果整合（完成后更新 §三与 #150/#172 相关表述）
- LeakCanary 浸泡增益（若设备构建含 LeakCanary；非阻塞）
