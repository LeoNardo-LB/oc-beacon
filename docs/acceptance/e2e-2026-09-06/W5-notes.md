# W5 波次摘要(配对与通知域 E1-E5+E3c)——2026-09-06 20:29-21:08

- 执行者:纯净上下文 W5 wave agent(只观测记录,不分析不修复)
- 设备 192.168.110.239:5555 · dev.leonardo.ocbeacon.dev 0.3.0(**构建 2026-09-06 18:39:55,含 A2 修复 60a44edf**,与 W3/W4 同构建;dumpsys 核对)
- logcat:全程 PID 过滤采集 /tmp/e2e-full/W5-logcat.log(**368,172 行;PID 2108(20:29-20:56)→4146(20:56 冷启后)**,采集随重启换 PID 续接);crash buffer /tmp/e2e-full/W5-crash.log(**0 字节**);**FATAL EXCEPTION=0**
- 开工准备(§0-9):reverse tcp:3080+tcp:4199 双条目在;POST_NOTIFICATIONS granted;stayon usb ✓;开工时 app 停服务器管理页(prod-3080 已连接,条目=2)
- 执行顺序:E1→E2→E3(+E3b 观测)→E4→E5→**E3c(最后,断开期间无其他 3080 item)**;新会话一律手选 zai-coding-cn·GLM-5.3-Flash(默认 deepseek-official 欠费,环境事实同 W2/W3)

## E 组终态统计

| item | 卡 | 终态 | 一句话 |
|---|---|---|---|
| E1 深链配对预填 | #325② | **✔(含模板勘误)** | checklist 模板 endpoint=+短 token 两处错→MISSING_URL/BAD_TOKEN;契约格式(url=+23 字符假 token)重跑:添加服务器对话框首现+URL 预填可见+假 token 后台交换 401(预期)+取消后条目仍=2 |
| E2 sameBackend 共存 | #325④ | **✔** | Host-4199(127.0.0.1:4199,未连接可连)+prod-3080(127.0.0.1:3080,已连接,DSH)双条目共存零覆盖;附:原生 UI 域另有独立注册表(192.168.110.95:4199)互不干扰 |
| E3 通知发布+直达 | #320 | **✔(发布/标题/直达/撤除)** | HOME→3.6s 轮完→「就绪 · e2e06-e3…」发布(通道 opencode_tasks,首轮 poll 4.5s 内)→shade 卡体 tap(600,700)直达会话→tap 后 dumpsys 撤除;a 径与 E4 互证 ✔;b 径=观测记录(队列类通知形态不存在):后台轮末通知不自动撤/不因查看撤/仅 tap 撤;d=BLOCKED-environment(注¹) |
| E4 退后台补发三段链 | #336 | **①✔ ②✘ ③✔** | 前台抑制基线(卡在场 dumpsys 无问题通知)→HOME+3s「问题 · e2e06-e4…」补发(opencode_questions,importance=4);②tap 直达 ✘=MIUI shade 组子卡 tap 不触发 contentIntent(多坐标尝试+独立卡对照,录屏 4 段在档);③Apple→提交→replyToQuestion success+Revoked QUESTION+dumpsys 0+复述 "You chose apple." |
| E5 子会话父槽撤除 | #337 | **撤除 ✔/tap 直达 ✘(同 E4②)/模型派子代理 ✔** | 提示派子代理→转录 "Dispatched ✅"+Subagent id;子会话提问冒泡父槽(Question asked for 子 target=父);HOME→「问题 · e2e06-e5」父槽通知发布;Neither 应答→Revoked QUESTION(target=父槽,session 6f39c58a)+dumpsys 0+无误撤他人 |
| E3c 断开撤除+重连 | #320 | **撤除 ✘/重连 ✔** | 未处理通知(tea/coffee 卡 HOME)在场→断开 prod-3080→**断开后 4/14/17s 三采样 9 条通知全部仍在(源码:disconnect() 无任何 cancel 调用)**→重连 3s 内「已连接」恢复+条目=2 复原;重连期重放+buildSessionPath 未找到→Revoker 反撤未处理问题通知(问题仍 pending) |

## 环境异常与处置

1. **E1 命令模板两处错误(任务书给定)**:①参数名 `endpoint=` 与 app 契约不符(DshPairingParser.kt:102 取 `params["url"]`,dsh-pair.sh 同款)→ `Pair deep-link rejected: MISSING_URL`(20:29:56.823,URL len=71 完整到达);②假 token `e2e-dummy-token-06` 18 字符 < BARE_TOKEN_REGEX 下限 20(即便 url= 也 BAD_TOKEN)。处置:按契约重构(url=+e2e-dummy-token-06-abcd)重跑通过。**后续 wave 复用模板前需修正**。
2. **IME 空格本 wave 不存活**(W2 记录 %s 存活、本 wave 全部被吞——豆包 IME 状态差异):全部消息无空格落地(逗号分隔,前缀完整);**下划线经 keycombination SHIFT+MINUS 可靠落地**(自备 /tmp/e2e-full/w5type.sh,扩展 type4.sh)。
3. **MIUI shade 组通知子卡 tap 不触发 contentIntent**(E4②/E5 反复实证):标题条带/卡体/图标区均无效(无 activity 启动、无 autoCancel、通知仍 posted);**独立卡(连接通知)tap 有效**=input tap 本身可用;E3 成功坐标 (600,700) 在后续布局命中淘宝广告卡([665,911] 覆盖组卡下段)→**误开淘宝一次**(已退出,录屏在档)。shade 组栈形态随通知数量/新增动态变化,无稳定可点区。
4. **4199 原生域意外进入+导航复原**:诊断性 tap 18:26 存量连接通知(「已连接到 192.168.110.95:4199」)→ app deep-link 进**原生 opencode UI 域**会话列表(旧 smoke 会话,未触碰)→ BACK 链:原生列表→诊断屏→设置页→原生服务器页(条目 192.168.110.95:4199→http://192.168.110.248:4199,**独立于 DSH Home 注册表**)→ BACK→桌面;最终 force-stop+am start 冷启(**COLD,TotalTime=873ms**)落 DSH Home 复位(条目=2)。logcat 采集随 PID 变更重启续接。
5. **通知 resync flood(两次)**:app 恢复(连接通知 tap 20:52/冷启 20:56)触发 prod-3080 全量重同步——**「错误 · hi」×7-8+「错误 · 用 bash 执行 echo g2-once」等错误通知群发**(旧错误轮被重新通知;Skip stale idle ×数十=正确跳过);**原有问题/就绪通知(含 18:42 遗留就绪·e2e06-a1-api)在 resync 期全部撤除**,问题通知随后按事件重发(20:53:46/20:56:58/20:57:04 三次重发记录)。
6. 执行器无图片输入(read_image 拒绝,W1/W3 同款);shade 视觉复盘走录屏存档;video-analyzer 技能视觉通道因 ZHIPU_API_KEY 未设不可用(15 帧已抽取至 ~/workspace/output/video-frames/ 备人工查看)。
7. E3b 第二轮消息首跳因输入框坐标盲 tap 落空未发出(logcat 无 Sent prompt)——重新精确定位 composer(IME 抬升后 EditText bounds)后成功;操作侧失误非 app 缺陷。

## 意外观测(非缺陷结论,供主 agent/后续 wave 裁量)

1. **【重点】断开不撤通知(E3c ✘ 根因)**:disconnect() 路径无任何通知撤除调用(cancelSessionNotifications 仅 ChatViewModel:382 进会话;cancelInteractionNotifications 仅 Revoker:111 应答/忽略)——断开后 9 条通知(含未处理问题通知)全部滞留;「断开撤除」若为 #320 契约一部分则为缺失。
2. **【重点】就绪通知生命周期=仅 tap 撤(autoCancel)**:后台轮末完成的通知不自动撤(+30s/+60s 保留)、launcher 重进+前台查看会话 ≥30s 不撤;遗留 18:42 就绪通知(从未被 tap)持续在场一致。通知无超时/TTL。
3. **重连期误撤未处理问题通知(重放竞态)**:重连→QuestionAsked SSE 重放→buildSessionPath: session not found(注册表未水化,warn)→Revoker 撤除该问题通知(21:06:59.723)——**问题本身仍 pending 但通知没了**(用户失去提醒);另 resync 新发「就绪 · This is an end-to-end test」+「错误 · e2e06-d6」(旧 Insufficient Balance 轮被翻出通知)。
4. **通知 text 字段取样含系统注入消息**:问题/错误通知 text 常为「<system-reminder> A skill is a reusable set…」(skill catalog 片段)而非用户可读摘要;就绪通知 text=transcript 尾片段且随轮次更新(e2e06-e3 第二轮后 text=secondprobe,replyok)。
5. **错误通知同题多条**:dumpsys 去重键(channel+title)下「错误 · hi」达 7-8 条同题并存——错误通知无会话级去重/抑制。
6. **原生 UI 域与 DSH Home 双注册表并存**:192.168.110.95:4199(原生域,http://192.168.110.248:4199)与 Host-4199(127.0.0.1:4199)为不同 authority 独立条目;sameBackend 去重以 type+url 为界——跨 UI 域不合并(记录,E2 断言面之外)。
7. **断开后残留请求**:断开 ~17s 后仍有 L2 stale re-confirm 触发的 session/list POST 发往 3080(观测记录,未定位)。
8. **shade「更多通知」面板与组栈动态**:被 tap 的组子卡从 shade 视觉隐藏但仍 posted;组栈子卡数/排布随新增通知动态变化;低重要性通知落入「更多通知」折叠区(SystemUI 行为,影响 adb 定位稳定性)。
9. E4 会话转录可见「思考完毕 · 0ms」思考行(内容异常引用 "create a file named made_by_acc311.txt"——模型 thinking 混入 skill catalog 语料;0ms 家族=B10/F3 同族)。
10. 新会话默认模型仍=deepseek-official(欠费),三个新会话均手选 zai(W2/W3 同款);GLM-5.3-Flash 简单问答轮 3.4-8.9s(无思考或短思考),提问卡到达 4-5s。

## 设备端测试会话(W5 创建,供 H3)

| 会话(行标题) | 首条消息前缀 | 创建 | session id | 用途/终态 |
|---|---|---|---|---|
| e2e06-e3,replywithoneshortsentence | e2e06-e3 | 20:33 | session-fbc96c4e-48da-4e9c-9c8b-63c21e060810 | E3 发布+直达+两轮+b 保留观测;两轮均已回复 |
| e2e06-e4,pleasecalltheask_user_questiont | e2e06-e4 | 20:43 | session-6a746eb5-e70a-4586-80e1-59234c439803 | E4 三段链;已应答(Apple)+复述完成 |
| e2e06-e5,pleasedispatchonesubagentandtel | e2e06-e5 | 20:58 | session-564060d3-8734-402d-aa8f-35840f84a8d9 | E5 父槽链(已应答 Neither)+E3c 未处理通知载体;**tea/coffee 问题仍 pending 未应答(E3c 断开撤除 ✘ 的在案载体,行「待回答」徽章在场)** |
| (E5 子代理会话) | —(模型派生) | 21:00 | 6f39c58a-e6aa-4fba-80b2-c97b3e2f707e | E5 提问源(已应答);设备端会话内模型所派,非宿主 spawn |

- 未归档/删除任何会话;**宿主侧未 spawn 任何子代理**(E5 子代理为设备端会话内模型自派);服务器条目终态=**2**(DSH Home:Host-4199 未连接+prod-3080 已连接,E3c-after-reconnect.png);工作区零变更;app 终态=服务器管理页(prod-3080 已连接);设备 /sdcard W5 录屏已拉取后清理。

## 证据索引(/tmp/e2e-full/,W5 新增 ~40 项,择要)

- E1/E2:E1-before-serverpage/E1-pair-dialog/E1-after-cancel.png · E2-samebackend.png+W5-serverpage.xml
- E3:E3-newsession/model-set/session-entered/after-tap.png · E3b-after-reentry.png · W5-notif-baseline/poll/retain-t0/t30/t60/viewed.txt
- E4:E4-card-present.xml · E4-card-foreground/card-before-answer/after-answer/final-transcript.png · E4-shade*.png · W5-notif-e4-fg-baseline/after-home/answered.txt
- E5:E5-card-present.xml · E5-card-foreground/after-answer.png · E5-shade.png · W5-notif-e5-fg/after-home/answered.txt
- E3c:E3c-before-disconnect/after-disconnect/after-reconnect.png · W5-notif-e3c-pre/post.txt · W5-notif-final.txt
- 录屏:W5-E4-seg1~4.mp4(E4 全程含 shade 交互挣扎)/W5-E5-seg1.mp4/W5-E3c-seg1.mp4
- 日志:W5-logcat.log(368,172 行,PID 2108→4146)· W5-crash.log(0 字节)
- 工具:w5type.sh(下划线扩展 typer)

## 遗留给主 agent 的裁量点

1. **E3c 断开撤除缺失定性**:disconnect() 无通知撤除调用是缺陷还是设计(若 #320 契约含断开撤除则为缺失,建议 backlog 卡);连带:就绪/错误类通知无 TTL/无清理路径,仅在 tap/进会话/应答三径撤除。
2. **重连期误撤未处理问题通知**(重放+buildSessionPath 未水化竞态):问题仍 pending 但通知被撤——建议与 #337/#336 联合裁量(重连重评估时序)。
3. **E4②/E5 tap 直达失败的复核**:MIUI shade 组子卡 adb input tap 不触发 contentIntent——需人工真机手指复核(录屏 4 段在档)分辨「adb 注入特有」vs「组通知可点性缺陷」;若是后者,通知组(setGroup+summary)在 MIUI 上的直达路径需适配。
4. **E1 命令模板修正**:checklist/任务书模板 endpoint=→url=、token ≥20 字符,防后续复踩(本 notes 环境异常 1 已留证)。
5. **错误通知 flood 与同题多条**(错误·hi×7-8):错误通知的会话级去重/抑制与 resync 重发面裁量。
6. **通知 text 取样含 <system-reminder>**:通知摘要抽取应跳过系统注入消息(展示层小瑕疵,可与 #320 联合)。
7. E4 转录思考行 0ms 异态(B10 -207ms/F3 0ms/probe r1 0ms 家族再+1)——合并归 #312① 裁量。
