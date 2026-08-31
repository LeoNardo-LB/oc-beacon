# DSH 全按钮系统性走查报告（进行中）

- 走查窗口：2026-08-31 起（goal-a2953002）；构建基线 master@ba2c664f（2499 测试全绿）
- 证据根：/tmp/dsh-wt/verify/（截屏+a11y XML+logcat+服务器探针）·方法=四维证据（代码链路/截图/数据库/日志）
- 基准序：已裁决记录 > DSH Web 行为 > 鲁棒性常识

## 已验通过
| 项 | 证据 |
|---|---|
| 发送失败弹窗（欠费 live） | 07-dock2「发送失败/Insufficient Balance」+ 会话干净回落 |
| 设置页两新行（展开/收起/三档/回显） | 14-permrow/15-collapse |
| goal.create RPC 全链（服务器落地） | 23-created2 + goal_probe（goal/change create） |
| 快速定位不再卡死（蒙版状态机走完解锁） | 05-after-jump ChatPaging 日志 |
| 斜杠命令面加载（DSH 6 命令） | logcat 01:14:28 ModelConfigDelegate |
| 多级树/权限/token 环/预设/详情/设置默认行（前批） | 前批各证据目录 |

## 发现清单（D 批待修）
| # | 现象 | 根因 | 严重度 |
|---|---|---|---|
| 1 | 快速定位点最早消息落点在 idx12 近底部（布局稳定超时放弃） | 目标解析/占位链待下钻（ChatPaging null=2→3） | P1 |
| 2 | 错误信息悬浮不散 | 弹窗+持久卡双通道 vs DSH 转录内行——修复代理 18e5cb55 在途 | P1 |
| 3 | 斜杠建议显示 OpenCode 命令集（无 /goal /permission） | 命令面 01:14 已加载正确；建议 UI 源/加载空窗待核 | P1 |
| 5 | goal 创建后 sheet 不翻转 active 视图；FAB 角标不亮（连带） | EventDispatcher 未注册 SessionGoalChanged（日志实证 No handler registered；SessionEventHandler :76 折叠已成孤岛）——补一行 bind | P0（一修双愈） |
| — | #4 已撤销（测试误触发送键，非 app 缺陷） | — | — |

## 阻塞
- DSH 账户欠费：一切需 LLM 回合的写 case（QueueDock 实造/goal 轮/预设锁定竞态）挂起待充值；只读 case 全部完成或进行中

## 待走查面
FAB 四+1 面板细目（堆积/TODO 已开面验）、workflow 降级卡、file 块、Shell→jobs 卡、Room 数据库证据采集、V2 对照零外溢复核、instrumentation tag 批（并入 D 批）

（报告随轮次滚动更新；D 批修复完成后出终版）
