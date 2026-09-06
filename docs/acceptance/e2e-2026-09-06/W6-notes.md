# W6 波次摘要(通用回归域 G1-G6 + 安全与终局清点 H1-H3)——2026-09-06 21:19-22:0x

- 执行者:纯净上下文 W6 wave agent(只观测记录,不分析不修复)
- 设备 192.168.110.239:5555 · dev.leonardo.ocbeacon.dev 0.3.0(构建 2026-09-06 18:39:55 与 W3/W4/W5 同,dumpsys 核对;versionCode 1788688910)
- logcat:全程 PID 过滤采集 /tmp/e2e-full/W6-logcat.log(327,648 行;PID 4146(W5 续)→7568(G4 debug-entry 冷启 21:34)→7491(G4 复位冷启 21:37),采集随两次重启换 PID 续接);crash buffer /tmp/e2e-full/W6-crash.log(0 字节);FATAL EXCEPTION=0,ANR=0
- 开工准备(§0-9):reverse tcp:3080+tcp:4199 双条目在;POST_NOTIFICATIONS granted;stayon usb ✓;开工时 app 停服务器管理页(prod-3080 已连接,W5 留下)
- 执行顺序:G1→G2→G3→G4→G5→G6→H1→H2→H3 严格顺序(G5 诊断屏在 G1 导航时顺路开过一次,正式检索在 G4 后按序执行)

## G/H 组终态统计

| item | 域/卡 | 终态 | 一句话 |
|---|---|---|---|
| G1 导航回归 | 3.1 | **✔** | 列表↔会话↔设置↔诊断全链往返无死路;ChatScreen BACK 回列表/诊断 BACK 回设置逐级 ✓;服务器域设置 BACK 直落服务器页(跳过列表,返回栈观察) |
| G2 主题切换 | 3.1 | **✔** | 深 (16,20,23)→浅 (247,250,253) 全量翻转;浅色下重进会话渲染完整(气泡 198,232,253);浅→深反向 ✓;复原系统默认零残留 |
| G3 i18n | 3.11★1 | **✔** | ①应用内语言切换存在且生效(15 语言,English 重建后本批文案英译全在场无 key 裸奔,复原);②i18n-check.sh PASSED 867 keys×14 语言;dsh_provider* 23 键+queue_* 8 键 EN/zh-rCN 双在场且与 UI 实测互证 |
| G4 Host-4199 冒烟 | V1V2 | **✘(连接+发送✔/回复✘ 服务端模型通道)** | debug-entry OK;新会话一轮消息发出后 assistant 轮 session.step.failed(provider.transport,4 次 retry 后);错误卡渲染;复位冷启条目=2 ✓;按「只发一轮」纪律止步 |
| G5 诊断屏日志 | 通用 | **✔** | queue=9 命中(Room 持久化背压告警族);notification 首查 0/1000(缓冲被 resync 洪流冲刷)→app 自带「发送测试通知」后 1/1000 命中;测试通知事后撤除 |
| G6 未读红点 | 3.3★4/3.10★4 | **✔** | b2 行「有未读消息」点进场即清除(a1-api/b8 不受累);e5 素材腿:待回答徽章进场前后保持(tea/coffee 未应答,E4② 人工复验素材已保全) |
| H1 崩溃清点 | 3.1★2★5 | **✔** | 六波 logcat FATAL=0/ANR=0(合计 ~189 万行);crash buffer 六件全 0 字节;dropbox 9 条 app crash 全在窗口前(最新 09-06 05:20,旧构建) |
| H2 存储直查 | #335/3.9★1 | **✔** | datastore 无 *.corrupt-*;pull-app-db.sh 首拉 integrity=ok;cached_sessions=2/cached_messages=12,764/archive_buckets=684/logs=44,566;E2E06 全会话在库 |
| H3 数据安全终检 | §0-3 | **✔(含 1 跨 wave 分歧)** | 条目=2 双连接终态;三工作区零变更;归档区 6 行枚举(W4 所记「无标题会话已归档」未复现=分歧移交);/sdcard 本轮残留已清(mp4 宿主核验后删);测试通知撤除 |

## 环境异常与处置

1. **run_code 内嵌多行命令静默失败**(约前 3 次):nohup 后台启动/复杂引号命令无输出无执行(与 W1-W3 记录的 1/6~1/3 解析错误同族,本 wave 表现为整条静默丢弃)。处置:所有非平凡命令改写为 /tmp/e2e-full/w6-*.sh 脚本文件后 bash 执行,全部成功;W6 证据链无缺口。
2. **JS 模板字面量吞 bash 语法**:${...} 与 grep 交替转义在模板内被 JS 解释 → 假阴性(曾误判 dsh_provider 键缺失)。处置:改 grep -E/脚本文件;G3 结论已用三路(键集合 comm/grep -E/UI 实测)交叉验证。
3. **MIUI shade 测试通知撤除方向**:右滑无效,左滑 (900,500)→(100,500) 成功(posted 10→9)。
4. **深滚列表定位归档区困难**:列表 140+ 行,fling 过头会误触会话行(误入 e2e06-d6 与一 9月4 旧会话各一次,均只读即退,顶栏返回恢复);最终以 header 精确 bounds 展开+分段 dump 完成 6 行枚举。
5. G4 冷启两次(debug-entry+复位)均伴随「电池限制已启用」横幅(W1/W5 已知 MIUI 噪音);复位后 Host-4199 成为活动连接(上次激活档案),prod-3080 需手动重连——为 G6 前置已处理,终态双已连接。

## 意外观测(非缺陷结论,供主 agent/后续裁量)

1. **【G5 重点】Room 持久化背压告警族**:重同步期「persist queue full, dropped N write requests (Room slower than SSE production)」WARN 连发(N=1150→1500 按 50 递增,诊断屏可见 9 条)——SSE 生产速率超 Room 写入时丢弃持久化写请求(消息仍在内存/转录)。与 H2 互补:cached_messages 12,764+桶 684 完好,但 resync 期可能有消息未落库。建议与 #335/存储域联合裁量。
2. **【G4 重点】4199 服务器 opencode-go 模型通道故障**:默认模型 Build·opencode-go·Omen Alpha 对简单问答 provider.transport 失败(4×retry.scheduled 后 step.failed)。连接/SSE/发送全正常——服务端模型侧问题。app 侧错误呈现(dump 失明区错误卡+台账 26.5s)像素级可辨但无文案 dump 暴露,可访问性可裁量。
3. **【H3 分歧】W4「无标题会话(20:05)已归档」未复现**:归档区实为 6 行(mkdir/e2e06-d6/hi 9月5 22:20/count 1-50/handoff/安装插件),无该 blank 行;主列表预期时间位(20:40-19:19 之间)亦无。W4 归档动作可能未生效或服务器侧已清理——W6 只记录。
4. **4199 V2 协议边角**:Sequence gap(missed 2 events)+「V2 unhandled event: session.retry.scheduled」+「Unhandled session.next event: session.step.failed」(失败轮 UI 状态靠 REST 兜底回 idle);「MessageStore: all 1 msgs outside window, skip persist」。均为 4199 域观察,prod-3080(DSH)无此形态。
5. **语言切换重建时滞**:选 English 后首帧 dump 仍中文(~4s 后 activity 重建完成全英文);重建同刻 logcat 有 MIUI SettingTrigger 反射异常(系统噪音)。
6. **未读点构造路径存档**:当前带「有未读消息」的行=a1-api(宿主 API prompt 时 app 在别处)/b2/b8(W5 resync 洪流把旧轮标记未读)——resync 产生未读态本身是 W5 观测 5 族的延伸表现。
7. e5 会话标题栏数字徽标「1」(与列表「待回答」并存,疑待答计数);诊断屏 1000/1000 环形缓冲在 resync 期翻滚极快(2 分钟内 21:38 的 notification 行被逐出)。
8. G6 e5 会话内 composer=「停止」钮(pending 期 C1⑤ 语义一致);未应答保持。

## E2E06 测试会话总清单(H3;全留存未删)

| 波次 | 会话(行标题/识别) | session id | 终态 |
|---|---|---|---|
| W1 | hi(workspace 旧会话,A2 用) | session-9c6eb7d3(hi 系) | 在列表 |
| W1 | hi(fork,A5/A6/A7 用) | session-7ccdfe0f | 在列表 |
| W-main | e2e06-a1-api 顶位测试(补测) | session-e2e06a1top01 | 在列表(未读点在场) |
| W2 | Apple or Banana Question Tool Test | session-67b5c44d | 在列表 |
| W2 | e2e06-b3… | session-455df04a | 在列表 |
| W2 | e2e06-b2…(B2/B6/B11-B15/G6 用) | session-dae91814 | 在列表 |
| W2 | e2e06-b4… | session-88b6d0b8 | 在列表 |
| W2 | e2e06-b8… | session-3bb683ff | 在列表(未读点在场) |
| W3 | e2e06-c1…(C1/C2/C4/C5) | session-89a20513 | 在列表 |
| W3 | e2e06-f1…(F1/F2/F3/G1/G2 用) | session-f9aa17ae | 在列表 |
| W4 | e2e06-d6(归档靶) | — | 已归档(归档区第 2 行) |
| W4 | mkdir(D8 泄漏副产物) | — | 已归档(归档区第 1 行) |
| W5 | e2e06-e3… | session-fbc96c4e | 在列表 |
| W5 | e2e06-e4… | session-6a746eb5 | 在列表 |
| W5 | e2e06-e5…(tea/coffee pending,勿应答) | session-564060d3 | 在列表(待回答徽章) |
| W5 | E5 子代理会话(设备端模型自派) | 6f39c58a | 树内 |
| W6 | e2e06-g4(G4 4199 域冒烟,回复失败) | ses_f89117f6fffe70y7Chr4jSE72r | 4199 域留存 |

## 宿主派生会话留存清单(H3;红线⑤,全留存未删)

| 会话 | 创建 | 身份 | 备注 |
|---|---|---|---|
| probe-a1-r1-1788665567(fc307231) | 09-06 11:32 | W1 子代理 | W1-notes 详表;A4 续聊回执在案 |
| (孙代)Count to five(a3048811) | 11:32 | r1 派生 | cached_messages=1 在库 |
| probe-a1-r2-1788665757(6e91ed51) | 11:35 | W1 子代理 | |
| (孙代)数到 5(37b3d8c9) | 11:36 | r2 派生 | |
| probe-a1-r3-1788665795(9b4b6d65) | 11:36 | W1 子代理 | |
| (孙代)数到 5(ad08176e) | 11:36 | r3 派生 | |
| e2e06-a1-api 顶位测试(session-e2e06a1top01) | 09-06 17:45 | W-main API 直建顶层会话 | A1 补测合法向量产物;列表在场+未读点(18:42 prompt 至今未读=构造路径) |

- W2-W6 各 wave 宿主侧均未 spawn 子代理(W2/W3/W4/W5 notes 同记);W6 零宿主派生。
- W6 设备端新建会话仅 G4 一会话(4199 域,e2e06-g4 前缀);prod-3080 域零新建。

## 证据索引(/tmp/e2e-full/,W6 新增 ~50 项,择要)

- G1:G1-list/session/after-back/settings/appsettings*/diagnostics/diag-back/close-settings.xml+png
- G2:G2-before/light(-list/-session)/dark/restore-dialog/restored.png+G2-light.xml
- G3:G3-lang-dialog(-restore-pos*)/english*/en-list/en-fabmenu/en-queuesheet/en-rowmenu2/restored.*(png+xml);宿主 keys-en/pt/zh.txt+w6-keydiff.sh
- G4:G4-4199-list/newdialog/session/typed/sent/reply*/failed/reset-home/reconnect.*(png+xml)
- G5:G5-diag-open/search-queue/search-notif{,2,3,4}.*(png+xml)
- G6:G6-list-before/e5-entered/e5-after/b2-entered/b2-after.*(png+xml)
- H1:W6-dropbox-full.txt+w6-h1.sh;H2:W6-db.db(+wal/shm)
- H3:H3-shade/archive*/workspaces/final-serverpage/notif-final.txt+w6-sdcard-clean.sh
- 日志:W6-logcat.log(327,648 行,PID 三代)· W6-crash.log(0 字节)· W6-collector.pid

## 遗留给主 agent 的裁量点

1. **Room 持久化背压(意外观测 1)**:resync 期丢持久化写请求(dropped 1150→1500)是否可接受/需限流或补写——建议与 #335 存储/冷存桶域联合裁量。
2. **G4 ✘ 定性**:4199 opencode-go 模型 provider.transport 失败为服务端环境问题;若需补「基础收发通」正向证据,待 4199 模型修复后单轮重测(app 侧链路本轮已证连接/发送/SSE/错误呈现全通)。
3. **无标题会话归档分歧(意外观测 3)**:核对 W4 D8 清理是否真落归档;若服务器侧有清理路径需澄清。
4. **V2(4199)失败轮的 unhandled 事件族**(retry.scheduled/step.failed 未处理,靠 REST 兜底)是否补 wire 处理——V2 域技术债裁量。
5. resync 洪流副作用三联:通知 flood(W5 已记)+未读标记扩散(b2/b8,本轮观测 6)+诊断缓冲逐出(G5 首查 0 命中)——同根因不同面,建议合并裁量。
