# W3 波次摘要(队列与输入域 C1-C5 + SSE 流式稳定域 F1-F3)——2026-09-06 18:45-19:20

- 执行者:纯净上下文 W3 wave agent(只观测记录,不分析不修复)
- 设备 192.168.110.239:5555 · dev.leonardo.ocbeacon.dev 0.3.0(**构建 2026-09-06 18:39:55,含 A2 修复 60a44edf**,dumpsys 核对)
- logcat:全程 PID 过滤采集 /tmp/e2e-full/W3-logcat.log(**227,052 行,PID 2108 全程未变**——wave 内无 app 重启);crash buffer /tmp/e2e-full/W3-crash.log(**0 字节**);**FATAL EXCEPTION=0**
- 开工准备(§0-9):reverse tcp:3080+tcp:4199 双条目在;POST_NOTIFICATIONS granted;stayon usb ✓;开工时 app 停会话列表(prod-3080 已连接)
- 执行顺序:C1①②→C1③④+C2(共用 busy 窗 R1)→C1⑤(阻塞态在共用会话内自然构造,注记)→C2 补链(R2)→C3→C4①②+C5(共用 busy 窗 R3)→F1→F2(F1 流式中)→F3;共用会话统筹注记已写入各 item

## C/F 组终态统计

| item | 卡 | 终态 | 一句话 |
|---|---|---|---|
| C1 单键五态 | #326 | **✔** | ①SEND 在场+tap 无效(0 分发)②单发送键③busy+空=STOP 单键④busy+文本=「发送（排队）」单键⑤blocked(提问卡)=STOP+chat-input en=false;全程无双键并排 |
| C2 角标全生命周期 | #327 | **✔** | 1→2(dump 数字节点+像素字形双证)→移除 updateQueue→1→插话(引导至下一轮)→0+注入消费;R1 自然 drain 链(双条轮末消费+回复)在案;E3b 交叉=无队列通知发布/残留 |
| C3 FAB 入口+Sheet 三动作 | #313 | **✔** | 五入口=TODO/智能体/目标/Shell/排队队列(与 prior 清单一致);Sheet 行动作=编辑/移除/插话(移除✔插话✔);「跳转」动作不存在(实际第三动作=编辑),前置「会话列表」修正为会话内 FAB |
| C4 steer vs 排队 | #309④ | **✔** | 排队 M@19:03:01.318(hold)vs 长按发送 steer@19:03:28.698(即刻注入+STEERED 回复),wire 27.4s 可分;steer UI=长按发送键在场 |
| C5 hold→drain 完整 | #329 | **✔** | 流式中 M 不可见(平价记录);轮末 M 用户气泡恰 1 次+回复恰 1 次(8 视图全覆盖采集),无丢失无重复 |
| F1 流式渲染稳定 | 3.4★3★5 | **✔** | 录屏 180.4s 抽 11 帧+同步 13 screencap:连续增量 diff(1-7k/5-8s)非静默爆发,无整页白闪帧;两轮 essay 全文渲染完整 |
| F2 滚动自愈 | 3.8★3 | **✔** | 流式中上滚 2 屏停 3+s:顶部老内容+滚动到底部钮在场+busy=未被拉底;tap 回底后恢复跟随(钮消失+末段内容) |
| F3 中断流式 | 3.5★1 | **✔** | tap→abort <150ms+立即截图(0.6s);部分内容保留(两帧 diff=0);chat-send 恢复 idle;再发一轮往返 ok ✓ |

## 环境异常与处置

1. **新会话默认模型仍=deepseek-official**——两个新会话(e2e06-c1/e2e06-f1)均手选 zai-coding-cn·GLM-5.3-Flash(选择器滚 2 屏,zai 组在深屏),全程未遇 Insufficient Balance。
2. **模型行为侵入测试编排(两次)**:①800w essay 提示触发模型自发 ask_user_question 澄清提问(800 字写什么主题/什么文体,双问题卡)——意外提供 C1⑤ blocked 真实构造(应答后恢复);②DSH 代理倾向把 essay 写成工作区文件(R1-R3 共产生 3 个 md 文件)——已在收尾删除(红线③),F1 会话 prompt 加 no files 后改 inline 回复。
3. **角标数字节点 dump 间歇失明**(4 次 dump 仅 1 次捕获 digit 节点)——以像素探针为主锚(粉底(233,186,183)+暗红(88,27,20) 字形;字形面积分 1=58px/2=94px),与 dump 捕获互证;属 §0-11 失明族新增一例(小 Badge 数字)。
4. **执行器 run_code 内嵌调用解析错误频发**(约 1/3,missing description/JS parse)——全部即重试成功,无实质影响;录屏 job 曾因整 call 失败丢启动 2 次(C2 seg3/F1 seg1 补启,关键窗口均在录)。
5. IME 经验全复用(W1/W2):%s 空格存活、&& 未用、全选删除法清稿可用、发送键 IME 抬升位 (1086,1616)、手势避开 y≥1500 键盘区(开启时)。

## 意外观测(非缺陷结论,供主 agent/后续 wave 裁量)

1. **流式期发送=排队+角标(与 #327 时代记录相反)**:R1 两条均于 Busy/Streaming 中发出且角标=2——#329 平价后 mid-turn 发送进入队列(角标可见)而转录持留不可见,hold→drain 语义在当前构建完整呈现(prior #327「流式期发送走隐形 drain 不入队不显角标」为旧构建行为,已不复现)。
2. **QueueSheet 空态竞态**:R1 角标=2 dump 后 ~25s 开 Sheet 已 (0)——essay 轮结束 drain 在两次观测之间完成;非 UI 缺陷,重跑 R2 补全链。
3. **发送键排队语义显式化**:busy+文本时发送键 content-desc=「发送（排队）」——#326 单键的排队语义以 desc 明示(先前文档未记录该文案)。
4. **blocked 会话的草稿持留**:提问卡 pending 期间 chat-input en=false 且已输入文本持留为草稿(列表行「草稿」徽章同步);「待回答」行徽章=提问 pending 的列表态呈现(E4/W5 可复用)。
5. **遗留就绪通知**:dumpsys 现存 opencode_tasks 通知「就绪 · e2e06-a1-api 顶位测试」(18:42 事件,W-main A2 复验会话)+组摘要——非本 wave 产生,撤除路径归 E3c/W5。
6. **被中断轮台账 0ms**:F3 停止轮显示「轮次 3 · 0ms · 1 步 · 0 个工具」——与 B10 记录的 -207ms 负时长同族(中断轮计时),归 #312① 附注裁量。
7. **GLM-5.3-Flash essay 型任务思考期 19-22s**、流式速率高(600 词≈10s);DSH 代理对 essay 任务常带 2 工具轮(job registry/file 写)。F1 判定以连续增量 diff 为准,流式绝对时长受模型速率限制。
8. steer 落点与 essay 自然收尾重合(43.2s≈steer 时刻),「流式中断」独立窗口未获得;注入+转向回复链完整(#326 A4 有 force-complete 先例补)。

## 设备端测试会话(W3 创建,供 H3)

| 会话(行标题) | 首条消息前缀 | 创建 | 用途 | session id |
|---|---|---|---|---|
| e2e06-c1,please write an 800 word | e2e06-c1 | 18:46 | C1 五态+C2 角标链(R1/R2)+C4+C5 共用长任务会话(统筹注记在各 item) | session-89a20513-307b-40c1-8ce6-3b4b20104159 |
| e2e06-f1,please write a 600 word | e2e06-f1 | 19:11 | F1/F2/F3(三轮 essay) | session-f9aa17ae-e8ad-4fd9-99c1-5b02dc52148e |

- 两会话留存未删;未归档/删除任何会话;**宿主侧未 spawn 任何子代理**(无宿主派生会话);服务器条目未触碰=恒 2;工作区:测试会话代理自建 3 个 essay md 文件已删(in-defense-of-boredom.md/a-history-of-maps.md/a-history-of-lighthouses.md),终态 find(>18:45) 无残留。

## 证据索引(/tmp/e2e-full/,W3 新增 xml/png/mp4 ~60 项,择要)

- C1:C1-s1-idle-empty/aftertap · s2-idle-text · s3-busy(+busy-empty.png)· s4-busy-text · s5-blocked · C1-nsdialog/newsession-entered/modelpicker*/model-set/q2/answered
- C2:C2-q1-* · fab-badge2/xml+png · sheet-r2a/r2b-2items · sheet-after-remove · badge-after-remove · sheet-after-steer · badge-final0 · transcript-after-drain · fab-badge1/2b(像素证)
- C4/C5:C4-after-steer/steer-poll2 · C5-midstream-afterM · scrollup1 · mid2/mid3 · seam/seam2 · bottom-final
- F1/F2:F1-frame-t6…t62/a1…a6 · F1vid/f110…f178(抽帧 11)· F2-away1/away2(+xml)· returned1/returned2(+xml)· final-state · W3-F1F2-seg1.mp4
- F3:F3-streaming/prestop · after-stop-immediate · after-stop(+xml)· after-resend(+xml)
- 录屏:W3-C2-seg1/2/3.mp4 + W3-F1F2-seg1.mp4;日志:W3-logcat.log(227,052 行)· W3-crash.log(空)· W3-dumpsys-notif.txt
- 工具:w3dump.py(dump 解析)· w3probe.py(区域色彩)· w3frames.py(帧增长)· w3badge/w3notif.py

## 遗留给主 agent 的裁量点

1. **C3「跳转」缺席**:checksheet 预期 QueueSheet 三动作=移除/插话/跳转,实际=编辑/移除/插话(引导至下一轮),跳转不存在——若产品意图有跳转需求需另立卡;编辑动作本轮未执行(非断言面)。
2. **角标数字 dump 失明**:Badge digit a11y 节点间歇不暴露(像素可靠)——是否登记为可访问性小缺陷归主 agent 裁量。
3. **遗留就绪通知**(e2e06-a1-api 会话,18:42)在 dumpsys 常驻——E3 撤除径测试时注意该存量。
4. F3/C4 被中断轮 0ms 台账与 -207ms(B10)同族,建议合并归 #312①。
