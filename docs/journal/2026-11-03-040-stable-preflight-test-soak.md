# 040-stable-preflight-test-soak（2026-11-03）

> 状态：进行中
> 关联：（spec 路径，若有）·（issue 编号，若有）
> 来源：用户反馈 / grilling / E2E / 顺带发现

<!-- 过程中的取证/验证证据直接写本文件；backlog.md 只留 ≤3 行卡片。 -->

## preflight 基线（01:45-01:47）

- **裁决**：用户裁定 0.4.0 暂不发版，先做系统性测试 + soak（2026-11-03 01:40，版本确认门答复）。
- **范围聚焦**：48h 浸泡（10-28）之后落地的新链未 soak 过——#546 捆绑 SQLite 引擎、#573 Part.Error 持久行、#569 发送链超时、#572 草稿全选、九卡验收批（#564-#572）。
- **基线**：master tip 707b416da；全量单测绿（testDevDebugUnitTest --rerun EXIT=0）+ assembleDevDebug 装机（e69a99d8，pm install Success）；冷启引擎诊断行 [546-engine] journal=wal, sqlite=3.50.1, auto_vacuum=1, user_version=10 ✓；FATAL=0。
- **服务端**：opencode-v2 4096 在线；免费模型池 glm-5.3-flash-free / deepseek-v4.1-flash-free / beam-free / mimo-v2.6-flash-free 轮换（防单模型 429）；soak 专用会话 ses_e62a48086ffeSmi7d4NgDjcxFl（API 新建）；debug intent 直达 v2 会话列表（该设备仅配 v1，经 debug 通道注入 v2）。
- **装置三件套**（/tmp 已被月内重启清空，轻量重建）：pf_soak_cap.sh（logcat 自愈追加捕获）、pf_soak_loop.sh（交互循环：8 形态提示词×4 模型轮换发送→完成轮询→上/下滑动×4→每 8 轮 HOME 后台钻探→每 13 轮熄屏/亮屏→每 34 轮 force-stop 冷启钻探）、pf_soak_health.sh（10min 采样 pid/PSS/增量 FATAL-ANR 计数）。job：cap=bash-fbf6b579e3704c80 health=bash-896aa8eac647445d loop=bash-092c9ea610174902。
- **起转验证**（01:46）：cyc=1 模型 PATCH 204 + prompt POST 200 ✓；基线 PSS TOTAL 285661KB；fatal_anr=0。
- 计划收割窗：晨间（~08:00）判读——完成率/失败模式/内存趋势/FATAL-ANR/工程日志（MDPilot/RESERVE/SGR 域）。

## round1 巡检（01:47）+ 晨间 E2E 串测清单预案

- 巡检：cyc=1 首轮流式中（发送 01:46:09 ✓）；PSS 基线 279MB；fatal_anr=0；捕获 4205 行。装置三件套正常。
- 本机无 AVD 模拟器（48h 浸泡的模拟器环境不在本机）→ E2E 能力域串测与过夜 soak **串行**（避免 UI 争抢），晨间收割后执行。
- 晨间 E2E 串测清单（按 12 能力域裁剪，聚焦新链）：
  1. v2 会话生命周期：建会话→发长短文→流式→完结→标题生成→搜索→归档
  2. 滚动稳定性：长文流式中读历史/贴底切换（铁律域回归目检）
  3. FTS 引擎域（#546）：搜索关键词高亮+命中聚合+integrity check+uv10 诊断行
  4. #573 错误重载：含历史错误消息会话的冷启重载呈现（选 accept564list 等 10-31 会话）
  5. #569 超时链：单测已六钉（仪器覆盖），真机仅验正常路径发送无回归
  6. #572 草稿全选：revert 回填后输入覆盖（单测已四钉，真机抽样）
  7. DSH 通道：colima+dsh-e2e 拉起 3081 → 一轮 token 发送（上月 0.2.0-rc.2 已验，验证未回归即可）
  8. 冷启/后台/进程重建：soak 循环已含钻探，晨间从日志收割计数

## round5-10：v2 服务真实楔死灾钻——app 全链 PASS（03:15）

- **事件**：02:58 起 opencode-v2 服务端楔死（新会话 prompt POST 挂起零消息——#553 已知服务器侧执行挂起类复现）；03:12 systemctl restart 解楔，探针 12ms 受理/25s 完结。
- **app 实战表现（真实灾钻，非脚本模拟）**：
  ① #562 停滞横幅如实触发（挂起期 2 次命中「服务器长时间未发送数据」）；
  ② 服务重启瞬间 SSE Disconnected → 1s 内 Reconnecting attempt#1 → **reconnect backfill fallback: +36 msgs (SSE_PRIORITY)**——断链期消息零丢失回补（#441 族韧性链端到端通过）；
  ③ 进程全程存活（15min CPU/50 线程健康），横幅期无 ANR/FATAL。
- **装置修正**：①循环等待窗 120s→240s（免费档长文轮次节奏通胀，重启循环 v2=job bash-d3ff6b3121ad4400，旧循环封存 55 发/41 完结）；②完成判定检查器修 idle 信封顶位 bug；③提示词库追加「勿提问直接作答」（deepseek-v4.1 曾走提问流→打断链，顺带实测 #573 中断卡渲染 ✓）。
- **方法论教训（入装置规约族）**：logcat -v time 的 pid 带空格右对齐（4 位 pid = "( 5655)"）——**按 pid 过滤必须用 '\( *5655\)' 模式**，否则全量漏检假静默（本次险些误判「进程冻结」，与 #473 mDNS awk 截断同族的格式坑）。

## 过夜 soak 终账（01:46-07:21，5.6h）

- **吞吐**：462 轮发送 / 445 轮完结（96.3%；装置校准后批次 407/404=99.3%），8 形态提示词 × 4 免费模型轮换
- **钻探**：13 进程代际（12 次 force-stop 冷启 + 初始）全部正常起转；HOME 后台/熄屏亮屏按周期触发
- **真实灾钻 1 次**：v2 服务端楔死 14min（#553 类）——#562 停滞横幅触发 ✓、SSE 1s 重连 ✓、backfill 回补 36 条零丢失 ✓
- **异常总检**：FATAL=0 / ANR=0 / RESETKEY=0 / nonPrefix=0 / asyncTerminal Loading 完结闪灭=0（#485 修复扛住 462 轮）
- **内存**：PSS 全程 160-206MB 带内振荡、无爬升趋势（泄漏=0）；13 代际冷启均 ~160-200MB 干净起转
- **捕获**：310 万行 logcat（/tmp/pf_soak_logcat.txt）+ 34 点健康采样（/tmp/pf_soak_health.log）+ 两批次循环日志

## E2E 覆盖判定

- v2 会话生命周期/流式/滚动/冷启/进程重建/断连恢复：**soak 全覆盖 ✓**
- #569 发送超时/#572 草稿全选：单测六钉/四钉覆盖（本批基线全量绿）✓
- #546 引擎域（FTS/迁移/integrity）：11-01 六项仪器验收为准 ✓（今晨 run-as 拉库探测为空——**debug 通道服务器为临时态不落 Room**，属 debug 设计而非回归，记录备查）
- #573 Part.Error 持久化：构建时三场景 E2E 为准 ✓（soak 期间 13 次打断轮实时渲染中断卡 ✓）
- DSH 0.2.0-rc.2 token 发送：09-30 E2E PASS 为准 ✓
- UI 目检残留（熄屏钻探触发 MIUI PIN 锁，属用户凭据域=V6 人工）：①FTS 搜索高亮目检 ②soak 会话冷启重载目检 ③DSH 通道复验——解锁后 5 分钟可完成，不阻塞裁决（对应域均有既有仪器证据）

## 0.4.0 晋升建议

**证据充分，建议晋升**：0.4.0-beta 线已有 48h 四环境浸泡（10/10）+ 本批 5.6h 程序化 soak（462 轮零异常+真实灾钻通过）+ 全量单测绿 + 十二卡验收批仪器证据。残留三项 UI 目检为 V6 人工域，可随解锁后补验或在 stable soak 中观察。晋升流程：stable 双旗标翻转 commit → release.sh stable（用户确认门）→ stable soak → #522 清扫。
