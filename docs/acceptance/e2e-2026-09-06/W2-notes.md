# W2 波次摘要(消息卡片与交互域 B1-B15)——2026-09-06 17:49-18:36

- 执行者:纯净上下文 W2 wave agent(只观测记录,不分析不修复)
- 设备 192.168.110.239:5555 · dev.leonardo.ocbeacon.dev 0.3.0(构建 2026-09-06 11:14:11)
- logcat:全程 PID 过滤采集 /tmp/e2e-full/W2-logcat.log(**224,374 行,PID 17955 全程未变**——wave 内无 app 重启;收尾红线复查冷启换 PID 22089 在案);crash buffer /tmp/e2e-full/W2-crash.log(0 字节);**FATAL EXCEPTION=0,ANR=0**
- 开工准备(§0-9):reverse tcp:3080+tcp:4199 双条目在;POST_NOTIFICATIONS granted;stayon usb ✓
- 执行顺序:按依赖微调——B1→B3→B2→B6→**B13**(即于 B2/B6 后)→**B5**(B1 会话)→B4→B7→B8→B9→B10→B11→B12→B14→B15;报告注记如左

## B 组终态统计

| item | 卡 | 终态 | 一句话 |
|---|---|---|---|
| B1 提问卡全链 | #308 | **✔** | 卡渲染(两选项/SINGLE/忽略/提交)→选 Apple→replyToQuestion success→复述 "You chose apple."→卡消失;InSessionFeedback QUESTION 振动+通知撤除链齐 |
| B2 审批档位 | #308 | **✔** | 完全访问=无审批卡直接执行(PermissionAsked 全程 0);回复含 probe-e2e06;正向路径按注¹ BLOCKED-environment |
| B3 忽略→reject | #328 | **✔** | tap 忽略→卡消+rejectQuestion success(17:58:50)+代理转述 "prompt was cancelled on your side"+会话回 Idle |
| B4 goals 完成钮 | #309① | **✔** | 代理 goal 轮含反问卡+自行 complete;表单新建 goal→UI 卡(进行中/轮次 1/256/标记完成)→tap→卡移除+SessionGoalChanged;无独立代理确认轮(注记) |
| B5 /compact | #309② | **✔** | 命令卡 执行中→已完成 原位(6 items ~2206 tokens);旧转录折叠为 </compacted-summary>;续聊答 "Apple"(上下文保留) |
| B6 危险操作档位 | #309③ | **✔** | mkdir&&rm -rf 直接执行无确认;Exit code 0 双段成功;PermissionAsked=0 |
| B7 反馈 👍/👎 | #310② | **✔** | 👍 即时灰→浅蓝(154,206,236)+messageFeedback/put;重进 list 重取+蓝态持久;👎 恒灰 |
| B8 plan 面 | #310③ | **✘(半链)** | 计划面✔(SessionPlanChanged×2+5 步计划)+审批卡批准受理✔;**批准后未自动开跑**(代理轮末收尾 Idle,需新消息推动);代理自述 harness 拒绝实际 plan mode 进入 |
| B9 命令反馈卡 | #323 | **✔** | /help 不在 DSH 命令表(6 命令)→false 无卡;等价 /export=true→「/export 已完成」原位单卡;/foobar123 无卡无崩溃 composer 可用 |
| B10 相对时间戳 | #312① | **✔** | 消息四级联 &lt;1m/Nm/Nh/1d(37h 会话=1d)实证;列表恒绝对(>24h 加日期);无负值/未来;附:-207ms 负时长异常另记 |
| B11 数学块降级 | #312② | **✔** | 原始 $$E = mc^2$$(代码栅栏自证)→渲染干净 "E = mc^2",无 $$ 泄漏无溢出(bounds 在屏内) |
| B12 命令带图拦截 | #312④ | **✔** | 附件入口存在(系统文件选择器+图片过滤);图片优化 235.5KB→29.4KB(~4272→~1242 token);/compact+图→toast「斜杠命令不支持图片附件」+未派发 |
| B13 轮次台账 | #310④ | **✔** | B2/B6 双轮台账(6.1s/2.9s·各 3 步 1 工具)+Run code 卡同屏,布局完整 |
| B14 草稿保持 | #334 | **✔** | draft-e2e06-b14 切 Y 回 X 完整保持;Y 无串台;列表「草稿」徽章出现 |
| B15 Markdown 表格 | 3.4★6 | **✔** | 2×4 表格按单元格节点渲染;名称列/价格列 bounds 级对齐(x1 集合一致),非纯文本堆叠 |

## 环境异常与处置

1. **新会话默认模型=deepseek-official(欠费通道)**——父提示「新会话服务器默认=zai」未复现;每个新会话需手选 zai-coding-cn·GLM-5.3-Flash(选择器滚 2 屏,zai 组在第三屏)。全程未遇 Insufficient Balance 回复(切换后发送全部成功)。
2. **IME/输入环境(W1 经验全复用)**:中文 input text 完全不落地(仅 ASCII);%s 空格存活(与 W1「空格被吞」记录不同——本 wave 消息均带空格落地);豆包 TextToolbar dump 失明,全选法=长按+盲点 (210,1520)+单次 DEL(多次仅选中单词);&& 需双层引号包裹落地。
3. **发送键位置随 IME 抬升**:composer 提升后发送键 ~(1086,1616);首条 B1 消息曾以未提升坐标 tap 致未发出(操作侧失误,重定位后正常,非 app 缺陷)。
4. **IME 开启时滑动落键盘区误触**:起于 y≈2000 的 swipe 落入键盘数字行,误入「666」进 composer(已清除);IME 开启时手势应限于 y&lt;composer 顶。
5. **uiautomator dump 偶发丢 chat-input 节点/评价按钮行**(与消息块可见性相关)——按 §0-11 dump 失明不以 dump 判存在,以重试+像素法补偿。
6. 执行器 glm-5.3 无图片输入(read_image 被拒)——与 W1 同;视觉断言走 PIL/numpy 像素级(点赞色变/表格对齐均像素或 bounds 级取证)。
7. run_code 内嵌 bash 偶发 "invalid arguments: missing description"/"Unterminated template" 解析错(重试即过,无实质影响;与 W1 1/6 概率记录同族)。

## 意外观测(非缺陷结论,供主 agent/后续 wave 裁量)

1. **会话标题双机制**:B1 会话标题=「Apple or Banana Question Tool Test」(模型语义标题,非首条消息派生);B2/B3/B4/B8 行标题=首条消息截断。疑似:标题由服务器异步生成/更新,短会话或未及更新。影响红线④:识别不能只看行标题,需转录前缀。**与 A2 症状族同源疑**(列表反映滞后)。
2. **B8 批准不驱动续跑**:ask_user_question 答案在轮内到达(提交 18:21:09 &lt; 轮末 18:21:19)但代理仍以「I'll hold here until you answer」收尾——答案是否注入进行中轮次存疑;对照 B1/B3/B4 的轮内应答均正常驱动(同为 replyToQuestion)。差异点:B8 为多问题表单(Q1/Q2),Q2 未答触发守卫对话框延迟 ~25s,时序竞争疑因,只观测未定位。另:多问题守卫对话框(「第 2 个问题没有回答」+继续提交)本身工作正常。
3. **服务器拒绝实际 plan mode**:B8 代理自述 "this session isn't in actual plan mode (the harness rejected…"——exit_plan_mode 在非 plan 会话被服务端拒;PlanChip 专用 UI 未得见(计划以消息内 markdown+审批提问卡呈现)。
4. **-207ms 负时长**:B4 会话轮次 1 台账(18:10 dump)出现 -207ms 轮时长(非时间戳);同会话另有轮次显示 0ms。归 #312① 附注。
5. **列表 updatedAt 滞后(A2 族又一例)**:B4 会话 18:11-18:15 三轮活动后行时间戳仍 18:08;至 18:28 观测已更新(滞后 &gt;3min &lt;13min)。
6. **图片附件管线完整**(B12 附带实证):系统选择器→优化(webp 转换+大小/token 双降)→chip+移除;命令拦截 toast 文案「斜杠命令不支持图片附件」明确。
7. **feedback 状态 UI**:评价态=图标灰→浅蓝 accent;重进触发 messageFeedback/list 重水化;评价按钮仅消息块完整可见时在 dump 出现。
8. **/help 缺席**:DSH prod-3080 命令表=6(compact/export/feedback/goal/permission/plan);V1/V2 opencode 的 /help 不存在——C3/C9 等后续 item 若引用 /help 需换 /export 等价。
9. goal 表单提交即触发 agent 轮(与主 agent 预告一致);任务菜单入口清单实测=TODO/智能体/目标/Shell/排队队列(C3 对照用)。

## 设备端测试会话(W2 创建,供 H3)

| 会话(行标题) | 首条消息前缀 | 创建 | 活动 | 用途 |
|---|---|---|---|---|
| Apple or Banana Question Tool Test(标题=语义) | e2e06-b1 | 17:50 | B1/B5(/compact+续聊)/B9(/help、/foobar123、/export) | session-67b5c44d |
| e2e06-b3,please call the ask_user_questi | e2e06-b3 | 17:57 | B3 忽略链 | session-455df04a |
| e2e06-b2,please run this exact bash | e2e06-b2 | 17:59 | B2/B6/B11/B12/B13/B14(草稿在场)/B15 | session-dae91814 |
| e2e06-b4,please create a goal: organize | e2e06-b4 | 18:07 | B4(goal×2:代理建+表单建 e2e06-b4-g1)/B7(👍×2) | session-88b6d0b8 |
| e2e06-b8,please use plan mode first: | e2e06-b8 | 18:18 | B8 plan 半链+续跑轮次 2 | session-3bb683ff |

- 全部留存未删;未归档/删除任何会话;未触碰工作区与设置页;**服务器条目终态=2**(Host-4199 未连接+prod-3080 已连接,冷启复查 W2-serverpage.png);推送文件 /sdcard/Download/e2e06-b12.png 已删;B14 草稿留存于 B2 会话 composer(草稿徽章在场,属测试态)。
- 附:B4 表单建的 goal(e2e06-b4-g1)已标记完成(终态);B4 代理自建 goal 亦已 complete——无遗留 active goal。

## 证据索引(/tmp/e2e-full/,B 域 xml/png 共 220 项,择要)

- B1:B1-sent2/selected/submitted · B3-card/ignored/after-wait
- B2/B6:B2-reply2 · B6-reply(-typed 含 && 落地)
- B4:B4-goal-ui*/goal-created2/goal-created/completed/bottom · B4-unanswered 无(B8)
- B5:B5-running/done2/after-chat · B9:B9-help-card3/foobar/export
- B7:B7-round1/r3-tapped/bottom(像素采样脚本内嵌 run_code 记录)
- B8:B8-plan-full/plan-card3/approved/unanswered-dialog/nudge
- B10:B10-msg-ts/list-ts/old-msgs/37h-msg/list5
- B11:B11-math/raw-reveal · B12:B12-attach-menu/browge→browse/images/attached/intercept2/intercept-tap
- B13:B13-imeclosed/ledgers/round1 · B14:B14-draft-set/list/Y-entered/X-returned
- B15:B15-table · 收尾:W2-serverpage/final-list · 日志:W2-logcat.log(224,374 行)· W2-crash.log(空)

## 遗留给主 agent 的裁量点

1. **B8 ✘ 定性**:批准答案未驱动续跑——需服务端定位(replyToQuestion 后是否向进行中轮注入答案;多问题表单+守卫对话框时序);若定性为测试时序问题可复测(单问题审批卡+即时应答)。
2. **标题双机制**(语义标题 vs 首条派生)与 A2 滞后族是否同源(服务器 session 元数据异步更新)。
3. -207ms 负轮时长(#312① 附注)是否独立缺陷。
4. C3 的 FAB 入口清单可采本 notes 意外观测 9(TODO/智能体/目标/Shell/排队队列)。
