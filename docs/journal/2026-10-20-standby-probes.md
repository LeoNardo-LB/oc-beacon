# standby-probes（2026-10-20）

> 状态：进行中
> 关联：（spec 路径，若有）·（issue 编号，若有）
> 来源：用户反馈 / grilling / E2E / 顺带发现

<!-- 过程中的取证/验证证据直接写本文件；backlog.md 只留 ≤3 行卡片。 -->

## ## 备货轮总表（2026-10-20 凌晨 02:28-03:00，用户授权「剩余卡片备实验/探针/探测装置」）

| 卡 | 装置 | 状态 |
|---|---|---|
| #545 | sentinel state 判别探针（FreezeWatchdog.sentinel+MainActivity 组合根 snapshotFlow 观察者，广播 --ez bump 触发） | 链路模拟器验证 ✅（bump→reacted 1ms）；判别逻辑：reacted=Recomposer 活→dispose 竞态；无=Recomposer 死→协程域异常。08:00 soak 结束后真机即战 |
| #553 | 模拟器主动复测（长答案 60s 流+FSM 流收割，/tmp/553/probe2.py 借 harness Device 原语） | **实测残留 ~9s 非 ~2 分钟**；FSM 干净（SseIdle→Idle force-complete 一刀切）无 Title busy 迹象——疑已被后续批次顺带治愈；阈值更新 >15s=复发信号 |
| #550 | scripts/watch7-freeze.sh（v7 监视器：零帧检测+FreezeWatchdog 广播黑匣子+/proc syscall 采样+自动取证包） | bash -n 过；整合 #545 定谱判据（空树 2627B=组合内容真空签名） |
| #546 | scripts/so-align-check.py（ELF PT_LOAD p_align 解析） | 实测双 APK：四库三绿，唯 libsqlite3x 全 ABI 4096——修向聚焦 requery sqlite-android 升级 |
| #486 | scripts/probe_486_sse_rebuild.py（三联构造器：pause 断流+滚出视口+恢复） | **2 轮实跑全家族探针触发**（RESETKEY rebuild/nonPrefix survived grace window 快照在档）；「0 试验」盲区现有即战装置 |

工具坑位入档（复用价值）：模拟器 app 英文 UI 发送键需双语匹配；mock 场景须 create_session 后 open_session 按标题找；probe_486 与 e2e 战役共享 4399 端口仍需串行。

