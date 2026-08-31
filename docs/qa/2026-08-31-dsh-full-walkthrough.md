# DSH 全按钮系统性走查报告（终版）

- 走查窗口：2026-08-31 ~ 09-01（goal-a2953002，23 轮）；构建基线 master@ba2c664f → 修复链至 459025b3（终态全量测试全绿）
- 方法：四维证据（代码链路/截图+a11y/带 tag 日志/服务器探针+Room 拉取）；基准序=已裁决记录>DSH Web 行为>鲁棒性常识；修复模式=根因修复（用户令，目标 rev4）
- 证据根：/tmp/dsh-wt/（verify/01-52 截屏+XML、jump-rerun.log、jump-dual.log、jump-fixed.log、finding1_round21.md）；前批证据 /tmp/dsh-model-e2e|dsh-tree-e2e|dsh-feat2

## A. 按钮清单
→ docs/qa/2026-08-31-dsh-walkthrough-inventory.md（五面全量，81addaa3）

## B. 已验通过（真机实证）
| 项 | 证据 |
|---|---|
| 发送失败展示（欠费 live：#2 修复后转录内行） | 07-dock2 + d5bf5898 |
| 权限切换全链（下拉/切换/服务器三事件/恢复） | 前批 V-perm 1-4 |
| 设置页两新行（展开/三档/回显/与既有区块同构） | 14-permrow/15-collapse + f9298c89 |
| goal 全生命周期（create 落地/sheet active 翻转/clear 三维证据） | 41-goal2/45-cleared3 + 25e2a60c |
| FAB 五入口+树化（根/L2 懒加载/直达裸 UUID） | 前批 + 19-fab2 |
| token 环（contextPressure 驱动跨会话 5%/18%/28%） | 8b47a40d + 32-s |
| 快速定位跳转（点最早消息→落点=目标本体 seq-10） | jump-fixed.log + 52-after-fix + 459025b3 |
| 斜杠命令面（加载 + 建议以服务器面为准） | logcat 01:14:28 + 9a384fda |
| 多级树/预设卡/详情标签/默认行（前批联验） | 前批证据目录 |
| V2 零外溢（agent 循环器原样/无权限 chip/无排队条/环走窗口路径） | 35-v2chat |
| 通用卡渲染（Run code 折叠/任务状态/思考行） | 30-mine |

## C. 发现与修复（全部根因模式闭环）
| # | 现象 | 根因 | 修复 |
|---|---|---|---|
| 1 | 快速定位点最早消息落最新区、蒙版后无反应 | 程序化 scrollToItem 不置 isScrollInProgress→下跳守卫误判漂移→GUARD 重锚与跳转互搏永不收敛 | 视口所有权模型：jumpLockActive 期间 GUARD 重锚+MSGEFFECT 锚底让位（459025b3） |
| 2 | 错误信息悬浮不散 | 弹窗+悬浮卡 vs DSH Web=转录内错误行 | 对齐 Web：转录内渲染、去悬浮/弹窗（d5bf5898） |
| 3 | 斜杠建议显示 OpenCode 命令集 | 静态 clientCommands 恒并入淹没服务器面 | 服务器面已加载即为准、静态表降兜底（9a384fda） |
| 5 | goal 创建后 sheet 不翻转、FAB 角标不亮 | EventDispatcher 漏注册 SessionGoalChanged→折叠成孤岛 | 补 bind 一行，一修双愈（25e2a60c） |
| — | #4 撤销：测试误触发送键（坐标漂移），非缺陷 | — | — |

## D. 外部阻塞（欠费，非本目标可解）→ 2026-09-01 已全部补测闭环
需 LLM 回合的写 case 挂起待充值后补测：QueueDock 实造排队+编辑/删除/steer 交互｜goal 轮注入（round 消息渲染+wrapup）｜workflow/file/Shell→jobs 卡 live 造数｜预设锁定竞态。Room DB 证据采集方法已定型（暂停 app 后 run-as 拉三件套）。

**补测结果**（glm-5.3-flash 解锁写路径；详见 [2026-09-01-post-walkthrough-fix-batch.md](2026-09-01-post-walkthrough-fix-batch.md)）：
- goal 轮注入活体验证 ✅；预设锁定竞态抽验 ✅（防御测试 ea24dda0）；卡片联动 RB-EXP ✅（前轮）
- QueueDock 交互补测**牵出 404 根因**：wire 方法名 updateQueue → session.updateQueue（11bcbc17），edit/remove/steer 此前全部静默失效
- workflow 卡 live 造数 → **定性为服务器数据面缺口**（tool-workflow 事件在 mux WS/history journal/projection/jobs 四面均不暴露，app 映射链休眠）→ backlog #290；走查期「重开丢卡」= 结构性无数据源，非 app 缺陷
- 附带捕获并根治 FK 787 落盘竞态（SSE 双写事务化 aa9bae68，真机 logs 表堆栈定音）

## E. 遗留登记
- backlog #282-288（重构群/权限动态渲染/小项集/命令列表懒建/附件拉取/workflow 阶段卡）
- WT-* instrumentation tag 批未全面铺开（#1 诊断走了既有 DEBUG tag 即足够）——按需再铺
- 卡片联动 bug（run code×tool 同消息展开联动）：fa303349 静态排查无共享键路径，需 RB-EXP 活体打点取证——欠费解除后随写 case 批复验
