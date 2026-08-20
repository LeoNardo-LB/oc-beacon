# 2026-08-17 提问卡 E2E 终验发现批次
> 状态：全部完结
> 迁移：2026-08-20 自 backlog.md 原文迁入（spec/journal 分离批次；原文逐字保留，未压缩）


- [x] **E2E-A 多选第二自定义答案不渲染** `ui` `sse`
  - **2026-08-17 定点重测定性：不可复现，系上轮现场污染**。干净会话（全程不退出）中 Mango+Peach 双自定义行同屏渲染（dump 双行 bounds 铁证）、删除 Mango 后 Peach 保留——b6bf568f 修复行为正确
  - **⚠️ 2026-08-18 终极勘误：E2E 观察的"只渲染第一个"本就是正确语义**——自定义输入是"提交自己的回答"（一个），不是添加选项；据此做的多自定义支持已整体回滚
  - 上轮 E2E-3 的"复现"实为 E2E-C（中途退出会话重置状态）污染所致
  - 附：上轮"APK 不含修复（构建早 commit 64 秒）"的时间线推断不成立——dex 实证 CustomAnswerRow 在包内（commit 时间 ≠ 代码定稿时间）

- [x] **E2E-B question 提交后 agent 收到"未作答"** `ui` `sse`
  - **2026-08-17 定点重测定性：功能正常**。干净提交：orderedAnswers=[["Apple","Peach","Banana"]] 完整送达，fallback form {"q0":[...]} success=true，agent 明确复述收到全部答案（含自定义 Peach）
  - **2026-08-18 翻案：此定性有误——真根因是 REST 恢复路径 key=null**（41811a2d 修复）。用户真机复现（00:39 logcat）：orderedAnswers 完整但 fallback answer={} 空体。真正区分变量不是"现场污染"而是**卡来源**：SSE 直达（V2FormMapper 合成 q0/q1）✅ vs REST 恢复（轮询兜底/loadPendingQuestions key=null 直传 → buildJsonAnswerMap 全跳过）❌。08-17 定点重测成功恰好用了 SSE 直达卡，E2E-3 失败恰好是重装后 REST 恢复卡——同根因不同表象
  - **2026-08-18 修复验证 ✅（精确复现用户场景）**：触发卡 → force-stop 杀进程 → 重启（REST 恢复路径实证：loadPendingQuestions 日志 + 新 pid）→ 提交 → fallback `answer={"q0":["Apple","Banana"]}` 非空 + success=true + agent 复述收到答案（同会话旧轮对照"未作答"）+ FATAL=0（/tmp/e2e10/）
  - **v2 主路径恒 404 是结构性**（衍生登记见 E2E-D）：POST /api/session/{sid}/question/{formId}/reply 端点在 V2 服务器不存在（API 文档 §12：V2 只有 /form/{formId}/reply；question reply 是 V1 app 级端点）——v2-first 探测恒失败后 fallback 是实际工作路径

- [x] **E2E-F [已定性：测试操作污染为主 + 数据流单测排除代码 bug；2026-08-19 模拟器代验收结案]** `ui` `sse`
  - 现象（2026-08-18 像素复审计两次独立复现）：多选卡加 Mango（自动选中）→ ✕ 删除（像素验证 UI 零选中）→ Submit → 服务器收到 **"Mango + Cherry"**（Cherry 全程未被点）；单选卡同法干净复现：删 Mango 后 Q1 零选中 → Submit → 回传 Q1="Cherry"
  - 严重性：P1 数据正确性——UI 可见状态与提交载荷不一致，用户以为删了实际没删、还带上了无关选项
  - 疑点方向：① answersPerQuestion（mutableStateListOf）与渲染 selected 集合的同步链断裂 ② HorizontalPager page 索引错位 ③ 删除路径 onOptionClick toggle off 未生效但行消失（渲染源与状态源不同）④ Cherry= options 末位，疑似 stale index 映射
  - 附带：✕ 删除的文本"退回输入框"现象（子代理观察，与删除语义冲突）；待答卡选中态进程被杀后不恢复（同 E2E-C 家族）
  - 另：多选选中行明度 Δ15.9 微超阈值 0.9 + 单/多选选中色不一致（(217,226,255) 蓝 vs (208,210,223) 灰紫）——待查渲染源，P2 顺带修
  - **2026-08-19 模拟器代验收 ✅ 结案**：干净复现路径（双问题卡 Q1 单选/Q2 多选+自定义）——Q2 加自定义 Emacs（保存行三图标 Edit/✕/✔ 实证）→ ✕ 删除（行消失+输入框复原）→ 选 VSCode → Submit 载荷 `answers=[[Python],[VSCode]]` **不含 Emacs**——删除项零泄漏，原污染不复现（当时系 E2E-C 家族现场污染）。附带 P2 一并消除：选中色像素实测单/多选同为 (221,222,237) accent wash、未选同为 (241,240,249)——M3 改造后已统一（证据 /tmp/verify-acceptance/p6_01/p6_06 + pixel_check）

- [x] **E2E-G [P1→已修复] 待答卡 + BACK → 全屏空白（根因：FSM Busy↔Idle 抖动机）** `ui` `session`
  - **2026-08-18 诊断完成（子代理取证 + 因果链证伪）**：原报"主输入框打字→空白"归因错误——tap/打字是旁观者（disabled 实现正确，5 状态 uiautomator 均 false；E2E 的 input text 实际落入卡内自定义输入框）
  - 真因果链：pending 卡 → SSE 静默 → 40s 超时重连周期 → 僵尸判定 ≥3min 进入 **Busy↔Idle 抖动循环**（SessionStateService.kt:535-550：pending 走 skip zombie interrupt 但 :550 仍强转 Idle，10s 后 :150 校验又复活 Busy）→ **BACK 落在 status 翻转 1-2s 窗口** → onCleared 清数据+重连回填重灌 + pop 过渡（NavGraph.kt:225 fade tween）竞态 → 目的地卡 alpha≈0 → 全屏空白（顶栏底栏全失，进程活无 crash；再 BACK 或重启恢复）
  - 排除：无限测量死循环（主线程响应+a11y 可查）/ OOM（RSS 405MB 稳定）/ disabled 失效
  - **2026-08-18 修复验证通过（7bfc2d0c）**：pending/子会话路径保持 FSM Busy 跟随服务器（消除抖动机）。E2E 实证：等待窗口 137 次 "keep Busy (waiting)" 零翻转（原版此窗口每 10s 抖）；原 bug 场景（pending+周期中 BACK）3/3 无空白；正常提交不受影响（Busy(streaming)→Idle 自然转换）；FATAL=0（/tmp/e2e20/）
  - H-A（NavGraph fade 与 onCleared 时序竞态）随抖动机消除后无实际触发路径，降级为理论性防御优化——若未来再出现状态突变+BACK 组合空白，再动 NavGraph.kt:225

- [x] **E2E-H 自定义答案未随提交发送——2026-08-18 模拟器复验结案（假象确认）** `ui` `sse`
  - 现象（2026-08-18 e2e22 终验）：卡上 Mango 行显示但提交载荷仅 [Apple, Banana]；自定义行点击无法勾选
  - 疑点：该轮正值 E2E-C 修复失败版本（VM 缓存）——Mango 行是 pop 丢状态前的残留渲染还是真选中存疑；**e2e23 终版终验中同样场景 Mango 已正常入载荷**（[[Apple,Banana,Mango]]），矛盾未解
  - **2026-08-18 模拟器全链路复验 ✅ 结案（假象确认）**：干净会话双题卡（Q1 单选+自定义 / Q2 多选）——Q1 输入 Mango 保存（像素验证勾选态底色 221,222,237 accent wash）→ Submit → logcat 载荷 `answers=[[Mango], [Red, Green]]` → fallback `answer={"q0":"Mango","q1":["Red","Green"]}` 200 → agent 复述"您选择了自填答案 Mango（芒果）+ Red/Green"——**自定义答案完整送达，无丢失**。当时矛盾观察确系 E2E-C 修复中间版本（VM 缓存）的残留渲染假象（证据链 /tmp/verify-0818/，截图 28-35）
  - 附：三态模型语义同时验证——Mango 勾选自动让位 Q1 选项（载荷仅 1 项互斥正确）

- [x] **E2E-I 整屏空白——结案（2026-08-19 instrument 定位：非 App 缺陷，模拟器 adb 注入伪影触发系统预测性 back）** `ui`
  - 现象（2026-08-18 e2e22 终验中）：聊天输入框 tap + input text 组合后整屏空白（Compose 树空、无 FATAL、surface 存活），force-stop 恢复——与 E2E-G 症状同族但触发描述不同（E2E-G 已修：BACK+抖动竞态）
  - **2026-08-18 模拟器复现 + 完整取证**：输入 prompt 后点 Send 前的 UI dump 骤缩（36k→2.6k 字节）——Compose 树仅剩宿主 View 链（0 内容节点）；dumpsys：MainActivity topResumed + mCurrentFocus + task visible（Activity 前台正常）；进程活（无 FATAL，"FATAL"匹配 60 条全系 uiautomator 自身日志）；截图 vision 确认纯白屏（仅状态栏+手势条）；BACK 恢复（回桌面，App 退后台）→ 热启动恢复完整 UI。触发上下文：输入框聚焦+键盘弹出+模型切换（GLM→DeepSeek）组合后。证据：/tmp/verify-0818/10_*.png|xml|txt、11-12 恢复序列
  - **2026-08-18 深夜根因分析（毫秒级时间线）**：21:07:39.692 键盘弹出（tap 输入框）→ 21:07:48.163 **QuestionAsked SSE 到达**（form 创建）→ 输入框 disabled（pendingQuestions 非空 → ChatScreenBottomBar inputEnabled=false）→ 21:07:48.332 IME hide（HIDE_SOFT_INPUT_BY_INSETS_API）→ **21:07:48.333 `InsetsController: Setting requestedVisibleTypes to -9 (was -1)`**（IME insets 类型从窗口协商中移除——insets 动画通道关闭，此前 21:07:30 第一次 hide 已有 IME_INSETS_HIDE_ANIMATION CUJ 丢帧警告）→ 此后 Compose 重组静默（dump 2.6KB 空树）。**候选机制**：pending 状态切换 + IME insets 协商的交叉窗口，AbstractComposeView composition 放弃/停止（无异常无 crash——异常被吞或 recomposer 停摆待查）；非 NavGraph 路由（无导航日志，排除 E2E-G 老根因直接复发）
  - 深挖方向（下次触发时）：① ChatScreen 输入 disabled 分支与 windowInsets/imePadding 协商链 ② Recomposer 状态（composition retained?）③ requestedVis
  - **2026-08-19 实验性二分定位（4 触发/3 对照）**：原「QuestionAsked → 输入 disabled」归因**证伪**——复现完全无需 question/prompt/服务器事件：
    - 触发组 4/4（R1 prompt+tap+text、R2 同、B 仅 tap+text、D 仅 tap+text）：**聊天输入框 tap + adb input text 注入后 ~1.5-2s，MainActivity 无故 finish/recreate**——窗口从 dumpsys window 消失、releaseSessionData（ChatViewModel 清理）、R1 变体还连带 Service destroyed + 50 会话状态全清 + SSE 取消（`SSE job cancelled, not reconnecting`）→ 整屏空白（2627B 坍缩 dump 签名）/ IME-only 残窗（8944B）
    - 对照组 0/3（A 仅 prompt、C 仅 tap、E 会话列表搜索框注入同文本）：一切正常——**锁定聊天输入管线独有**（草稿直写 DataStore 链是最显著差异变量；搜索框无持久化）
    - 全程无 FATAL、crash buffer 空、进程存活；LeakCanary 堆转储 + WebView renderer crash 为重建的**后果**非原因；语言镜像值一致（prefs zh-CN == DataStore zh-CN）排除语言监视器 recreate 直觉路径
    - 已修（c1e06e61）：取证链上的独立契约缺陷——session.instructions.updated 的 delta 是 JsonObject（指令哈希表），mapV2DeltaEvent 硬转换抛异常（每个首 prompt 必现 E 级日志），as? 安全转型修复
    - **2026-08-19 深夜 instrument 决定性定位（诊断构建已回滚）**：① 语言监视器 recreate 排除（instrument 零输出——无 emission 无 recreate）② MainActivity.onDestroy 未执行（排除 finish/recreate 全家）③ NavGraph popBackStack instrument 抓到真凶：`popBackStack from=graph-direct dest=home`——**BACK 按键事件驱动**。④ dumpsys input RecentQueue 铁证：注入字符以 DOWN+UP 同 eventTime 对到达（`source=KEYBOARD, displayId=-1, scanCode=0` 合成事件），系统 `KEY_GESTURE_TYPE_BACK ×9` 与注入字符数对应——**模拟器 adb 注入伪影：瞬时合成键盘事件对触发预测性 back 手势**，被预测 back 系统拦截后 App 收到 BACK → ChatScreen popBackStack → 回列表/面板（多次 pop 连击时窗口耗尽 → 整屏白 2627B 签名；R1 连击达服务销毁）
    - 对照实验矩阵（8 组）：慢速逐字符/纯字母/IME 预先收起再注入**全部触发**（排除打字节奏、空格、IME 因素）；仅 tap 不注入/搜索框注入同文本/仅 prompt **全部不触发**（锁定聊天屏 KEYBOARD 事件）；真实用户路径不受影响（人类敲键盘走物理输入层，无 displayId=-1 合成对）
    - **结论定性：非 App 缺陷——模拟器 E2E 工具链伪影**（adb input text 在该 API 36 镜像 + GMS 键盘组合下的系统级预测 back 误触发）。App 侧行为（收 BACK → popBackStack）完全正确。**收口**：① E2E 自动化改用 IME 直达通道（ADBKeyboard 广播/unicode 直塞）或 `input keyevent KEYCODE_*` 逐键替代 `input text` 突发串 ② 本条目关闭，G/H-A fade 竞态理论另案（从未有复现证据）
    - 影响面备注：疑似 adb 注入突发特有（人类逐键输入未见普遍报告），但 R1 变体的服务销毁+状态全清用户可见ibleTypes=-9 的调用源（AndroidX core insets API 使用点全库 grep）
  - 待查：是否同一 fade 竞态的另一触发路径（H-A 理论性防御未做），或独立问题；已按待办要求抓齐 dumpsys activity top + logcat 全量（10_logcat_full.txt）
  - 优先级：P2 ✅ 2026-08-19 完结（工具链备忘归 E2E runbook：自动化注入改 IME 直达通道）

- [x] **SSE 长时间无事件不自动重连（8 分钟+）——两轮勘误后结案：真根因是心跳帧被吞（00fbdda3 已修）** `sse`
  - 现象（E2E 顺带观察）：SSE 流停滞 8 分钟+ 无自动重连，仅靠 REST 校验兜底
  - **2026-08-18 模拟器实证（服务器 0.0.0-beta-17595）**：该服务器 SSE 流**不发心跳帧** → App 40s 读超时（`V2 SSE read timed out after 40000ms`）每 40s 必断一次 → 重连 → Recover 51 会话 → 再 40s 断——完整周期日志实证（21:22:55 等 4 次）。原"8 分钟不重连"现象属旧服务器（next-17403 有心跳）；现为相反方向的兼容问题
  - **2026-08-18 二次勘误（同日深夜）**：上段「服务器无心跳帧」结论错误——curl 挂 100s 实测每 15s 一条 `: heartbeat` 注释帧；真根因是 App 帧聚合吞掉注释帧（见 beta-17595 契约批次子项 1）。**已修（00fbdda3）**：空闲 150s 零断连实证
  - 衍生发现：**SSE 冷却永续循环**（连续超时后 5min 冷却到期即再进）——同日已修（bd04d060）
  - 结论：旧服务器"8 分钟不重连"原始观察未在本轮复现（该服务器已下线无法回测）；当前 master 空闲保持/断连恢复/冷却退出三链路全部实证正常，结案

- [x] **E2E-C 提问卡丢已选答案（终版修复 137c8c7a，双向量终验全 PASS）** `ui` `sse`
  - 现象：聊天页返回会话列表再进入，卡内已选答案重置（rememberSaveable 未在该导航路径生效）
  - 2026-08-18 双输入框验证中第三次独立复现：误按 BACK 退列表重进，未 Submit 的自定义草稿（Mango pie 行）消失（服务端卡仍 pending）——非单一测试现场特例
  - 2026-08-18 输入框美化验证中第四个复现向量：font_scale 切换（1.0↔1.15/1.3，Activity recreate）同样丢——已选 Apple（像素验证 wash 消失）与已保存自定义行（"Mango grape pie" dump 不再出现）全部清空、输入框回空态；#113 的 rememberSaveable(question.id)+SideEffect 同步链（QuestionCard.kt:89-109）在配置变更路径未生效，疑 SideEffect 时序或 question.id 重建变化，待查
  - **2026-08-18 根因定位 + 向量 2 修复闭环（7f15f0c5）**：原 rememberSaveable 直接存 List<List<String>>——autoSaver canBeSaved=false **静默不保存**（#113 的"旋转恢复"从未生效）。改 JSON 字符串后：**recreate 向量已修好**（E2E 实证 font_scale 1.3 真实 recreate 后 Apple+Mango 完整保留，且 REST 替换竞争无害——挂载点稳定时不冲突）
  - **2026-08-18 终版修复（137c8c7a）终验全 PASS**：三次迭代定位正确抽象层级——rememberSaveable List 静默不存（从未生效）→ JSON saveable（recreate 假阳性）→ VM 缓存（9f66bacd 终验双 FAIL：hiltViewModel 作用域=NavBackStackEntry，pop 销毁/recreate 重建，宿主活不过去）→ **QuestionAnswerStore @Singleton 应用级单例**（同 SessionScrollSignal 模式）。终验：向量 1（pop×2）/向量 2（recreate×2）全保留（像素 tint 判定）；消费清理后新卡从空开始；最强交叉验证——pop/recreate 存活后的终态直接提交 answers=[[Apple,Banana,Mango]] 全量送达（/tmp/e2e23/）
  - 勘误：REST 轮询周期 30s（QUESTION_POLL_INTERVAL_MS）；50a055ba 的"向量2实证有效"为假阳性（重进组合非真实 recreate）

- [x] **单选保存自定义后再选其他选项，自定义仍恒勾选（用户反馈 2026-08-18 晚，已修复）** `ui`
  - 用户场景：单选 + 自定义输入保存（勾选态）→ 再选别的选项 → 自定义"还是处于勾选状态"；期望**保留内容，但取消勾选**
  - 根因：answersPerQuestion 扁平 List<String>「存在即勾选」——"内容保留"与"勾选状态"没有分离表达；且旧行为单选双勾选会提交双答案（载荷语义错误）
  - 修复（三态模型：勾选 / parked 保留未勾选 / 不存在）：每题拆 **selected（提交载荷）+ parkedCustom（保留未勾选）** 两槽位
    - 单选点选项：已勾选自定义 → 取消勾选入 parked（内容保留；✕ 才彻底删除）
    - 单选勾选自定义：选项槽位让位（选项行仍可见可再选，真·互斥，载荷恒 ≤1）
    - 重按已勾选自定义行 = 取消勾选入 parked；parked 行点击 = 重新勾选；多选取消勾选同样入 parked
    - ✕ = 彻底删除回空输入框（不再走 toggle）
    - UI：新增 ParkedCustomRow（弱化文字 + ✕，行点击重勾选）；已勾选行整行可点击（tap-toggle 与选项行同语言）
    - 持久化：QuestionAnswersSnapshot（answers + parkedCustoms）贯通 QuestionAnswerStore 与 saveable JSON
  - 验证 ✅：单测 14/14（CustomAnswerToggleFlowTest 重写覆盖三态矩阵）；E2E 六断言全 PASS（/tmp/e2e-parked/，dump+像素+logcat 三维交叉）：保存勾选(accent 80,100,151) → 点 Red 后 Mango parked(弱灰 99,100,105，与未选项 50,51,56 可区分) → parked 重勾选+Red 让位(互斥) → 提交载荷 [["Red"]] 不含 parked → ✕ 删除回输入框(dump 无节点) → BACK 重进 parked+勾选双保留(store 恢复)；三问载荷 [[Red]]/[[Banana]]/[[Blue]] 全部正确
  - 附带：E2E-H 的"自定义行点击无法勾选"疑点已消除——行现在整行可点击（勾选⇄取消勾选）

- [x] **E2E-E 多问题 pager 固定高度裁剪输入框底边——已修复 7b3362c4（页限高+页内滚动）** `ui`
  - 现象（2026-08-17 第五版 E2E 发现）：双行问题文本时页内容 642px > pager 插值高度 630px，自定义输入框底边被裁 12px（135px vs 正常 147px）
  - **2026-08-18 模拟器加重（6 选项页实测）**：Q2 多选 6 选项（Blue/Green/Red/Yellow/Black/White）+custom=true——视口只见前 3 项，Yellow/Black/White 与自定义输入框**无论何种手势（swipe/swipe 短/fling × 多角度）均不可达**：提问卡内无独立滚动机制（HorizontalPager 页内容不可滚），外层消息列表 swipe 又被 pager 消费为翻页/无效——**功能性缺失**（6+ 选项题无法完整作答），比"裁 12px 视觉瑕疵"严重。证据：/tmp/verify-0818/22-25（4 次 dump 均无 Yellow+，vision 复核）
  - 根因方向：QuestionPagerView 高度线性插值按 onGloballyPositioned 记录的页高计算，键盘态/裁剪态测量偏小或 pageSpacing 未计入；需页内容可滚动（ColumnScrollable）或高度按最高页计算
  - **2026-08-18 修复完成（7b3362c4）+ 模拟器 E2E 全闭环 ✅**：页内容限高（屏高 40%）+ 页内 verticalScroll；高度记录移至滚动内容内层（无界测量，防键盘态测量偏小复发）；插值抽纯函数 lerpCappedPageHeight（单测 5 用例）。E2E：10 选项卡初始视口截断 → 卡片内上滑 → 底部选项 + Enter answer 全部可达 → 滚动位置选 Swift 提交 success；双题短页卡插值回归正常 + [[Tea],[Morning]] 闭环。⚠️ 剩余待用户真机验收：滚动观感（维度 5）
  - 优先级：P1（已修）

- [x] **E2E-D question.v2 reply 探测恒 404——已修 852b7681（404 缓存直达 form）** `ui` `sse`
  - 现象：每次提问回复先 POST /api/session/{sid}/question/{formId}/reply 恒 404（端点结构性不存在，见 E2E-B 定性），再 fallback form 路径——每次多一次无效往返
  - **2026-08-18 模拟器精确重现**（beta-17595）：Submit → `replyToForm: POST question.v2 reply → 404 Not Found（25ms）→ fallback form path → 200 success` 完整日志链——行为与定性一致，未修
  - 背景：2026-08-15 research/09 时该端点曾实测 200（next-17430 中间契约）；现服务器已无此端点
  - 方向：按服务器版本/连接缓存探测结果（首 404 后跳过），或按 V1/V2 探测结果直选路径；注意未来服务器可能重新引入
  - **2026-08-19 修复（852b7681）**：V2ApiClient(@Singleton) 按 baseUrl 记忆「404 已探明」——后续提交跳过探测直达 form 路径；仅 404 标记（400 保留重试）；进程重启清空自动重探（未来端点回归无需发版）；reply/reject 双通道同修。E2E 两问两答：首提 404→`cached absent` 标记→form 200；次提 `known absent — direct form path` 零探测直达（logcat 铁证）；全量单测绿
  - 优先级：P2（功能无损，仅性能/噪音）——✅ 已修
