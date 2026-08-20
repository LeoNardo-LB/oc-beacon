# OC Beacon — 需求与问题总览

本文档用于记录用户在使用过程中口头反馈的问题、发现的 bug，以及计划中的功能需求。

**定位**：轻量级记录，仅忠实记录用户反馈的原始现象与需求，可以简单调研的结果，但不做主观推测或归因分析。**会话进行中产生的、优先级不足以立即处理的需求/问题也实时登记于此**（触发时机见 AGENTS.md「Backlog 纪律」）。简单可行性确认（如文件名是否存在、接口是否暴露等）可附带，深入的代码链路调研由具体开发任务承接。

**Spec 关联约定**：复杂需求（经 grilling/调研定案的功能）登记批次时，设计 spec 存放于 `docs/specs/`（命名 `YYYY-MM-DD-<名称>-design.md`），条目内必须链接 spec 路径（spec 是权威，backlog 条目只留摘要）；实现并用户验收完成后移入 `docs/archive/specs/`，同步更新 spec 头部状态行与 backlog 引用路径。简单需求不强制写 spec。

**优先级定义**：

| 等级 | 含义 | 示例 |
|------|------|------|
| **P0** | 影响主要流程体验或核心业务场景的 bug | 聊天页面崩溃、SSE 断连无法恢复 |
| **P1** | 主要业务流程的需求功能点 | 会话搜索、消息转发 |
| **P2** | 优化专项、锦上添花功能、不影响体验的小 bug | 动画微调、文案优化 |

**状态流转**：每个条目下的状态 checkbox 需全部打勾才算完结。代码写好但未验证不等于完成！要求完成需求、自行验证、用户验收通过之后才能标记已完成！

| 状态 | checkbox 标记 | 含义与流转规则 |
|------|--------------|----------------|
| **进行中** | `[ ]` | 需求已登记或正在开发。开发完成后跑通自动化验证（编译/单测/i18n/assemble）并自行完成可覆盖的验证后 → 转「待验证」 |
| **待验证** | `[~]` | 代码完成、自动化验证通过，但**用户人工/真机验收未完成**。AGENTS.md 验证框架要求 UI/UX 时间性现象（闪烁/动画/布局）必须人工验证（维度 5）。**后续 Agent 看到 `[~]` 时**：向用户给出验证清单并请其执行；用户验收通过 → 勾选 `[x]` 转「已完成」并更新完成说明；验收发现问题 → 改回 `[ ]` 进入修复 |
| **已完成** | `[x]` | 需求完成 + 自行验证 + **用户验收通过**。只有用户明确确认后才可打勾 |

**Tag 标签体系**：每个条目需标记相关 Tag，用于关联同类问题，便于后续批量修复或按领域排查。录入时判断条目适用的已有 Tag；若现有 Tag 不足以描述，则新增。

| Tag | 说明 |
|-----|------|
| `crash` | 崩溃 / 闪退 |
| `ui` | 界面显示、组件缺失、布局问题 |
| `data` | 数据展示不准确、数据源疑问 |
| `sse` | SSE 连接、事件推送相关 |
| `session` | 会话管理相关 |
| `permission` | 权限请求、审批相关 |
| `security` | 安全与隐私（明文凭据、泄漏、合规） |
| `refactor` | 重构、死代码清理、分层修复 |

---

## P0 — 主流程阻塞

### 2026-08-17 提问卡 E2E 终验发现批次（已定性）

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

### 2026-08-06 Play 上架合规批次（已完成）
来源：Google Play 上架审计（2026-08-06），目标 2026-08-31 政策截止。

- [x] **#1 targetSdk 36 升级** `security`
  - 问题：targetSdk=35，Play 2026-08-31 起新应用必须 target Android 16 (API 36)，不达标无法上架
  - 修复：targetSdk 35 → 36（compileSdk 37 已就绪），全量编译验证
  - 工时：~1h | 难度：低 | 涉及：app/build.gradle.kts

- [x] **#2 权限清单清理** `security` `permission`
  - 问题：Termux RUN_COMMAND（代码零使用）、WRITE_EXTERNAL_STORAGE（遗留）会被 Play 审核质询；REQUEST_IGNORE_BATTERY_OPTIMIZATIONS 属高风险权限（IM 类默认不允许）
  - 修复：移除死权限；电池优化改为引导到系统设置页（ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS + 回退应用详情页），SSE 保活能力保留
  - 工时：~2h | 难度：低 | 涉及：AndroidManifest.xml / HomeScreen.kt

- [x] **#3 应用内自更新 flavor 区分** `security` `data`
  - 问题：Play 政策明确禁止 REQUEST_INSTALL_PACKAGES 做应用自更新；现有 UpdateRepository 从 GitHub 下载 APK 安装
  - 修复：stable（Play）禁用自更新（UI 隐藏 + Repository 守卫 + Manifest overlay 移除权限）；dev/beta（GitHub 分发）保留
  - 工时：~2h | 难度：中 | 涉及：build.gradle.kts / src/stable/AndroidManifest.xml（新）/ AboutScreen / UpdateRepository

- [x] **#4 密码 Keystore 加密** `security` `data`
  - 问题：服务器密码明文存 DataStore，且随系统云备份上传（backup_rules 为空 = 全量备份）
  - 修复：新建 SecretCipher（AES/GCM + AndroidKeyStore）加解密落盘；备份规则排除 datastore/ 目录（云备份 + 设备迁移）
  - 工时：~3h | 难度：中 | 涉及：SecretCipher.kt（新）/ ServerDataStore / backup_rules / data_extraction_rules

- [x] **#5 导航 URLDecoder 崩溃风险** `crash`
  - 问题：13 处裸 `URLDecoder.decode`，密码含畸形 `%` 序列（如 `%NR`）抛 IllegalArgumentException 崩溃
  - 修复：全部改用 `NavUtils.safeDecodeParam`（畸形序列回退原值）
  - 工时：~1h | 难度：低 | 涉及：ChatViewModel / SessionListViewModel / WorkspaceViewModel / SessionLifecycleDelegate

- [x] **#6 selectModel 空 patch 隐患** `data`
  - 问题：ChatRepository.selectModel 零调用死代码，实现传 `ServerConfigPatch()` 空对象——PATCH /config 是全量替换语义，任何调用方接入即清空服务器 model 配置
  - 修复：删除接口 + 实现 + 相关测试/Fake
  - 工时：~1h | 难度：低 | 涉及：ChatRepository / ChatRepositoryImpl / ChatRepositoryTest

- [x] **#7 终端工作区内存泄漏** `data`
  - 问题：ServerTerminalRegistry.byServer 只 getOrPut 无 remove，每次连接不同服务器泄漏一个终端工作区（模拟器 + 协程作用域永不释放）
  - 修复：新增 removeWorkspace/removeAllWorkspaces + Workspace.dispose()（closeAll + scope.cancel），Service 断开时自动调用
  - 工时：~2h | 难度：中 | 涉及：ServerTerminalRegistry / ServerTerminalWorkspace / OpenCodeConnectionService

- [x] **#8 密码导航参数重构** `security`
  - 问题：密码明文经 query 参数在 7 个路由间传递（ServerRouteParams 5 参），暴露于日志/深链/进程重建回放；与 #4 本地加密闭环矛盾
  - 方案：导航只传 serverId；各 ViewModel 从 ServerDataStore 按 id 取 ServerConfig（suspend，需初始 loading）；WebViewScreen Basic Auth 同改
  - 工时：~2-3d | 难度：高 | 涉及：ServerRouteParams / NavGraph（25+ 处）/ Chat·SessionList·Workspace·WebView·ServerSettings ViewModel
  - **2026-08-07 完成**：路由层+消费层+入口源头全部 serverId-only（commit 681bf0fb / 2326e8b5）；编译 ✅ 全量单测 ✅（52s）凭据 grep 0 引用 ✅；模拟器验收通过（9/9 步：会话/聊天/SSE/终端/通知深链/WebView Basic Auth 全正常，logcat 0 异常，凭据零泄露）；遗留 runBlocking 债务已登记 architecture-debt §6

- [x] **#123 V2 用户消息不立即显示（session.inbox.enqueued 契约适配）** `sse` `data`
  - 问题：2026-08-14 用户反馈"发送消息后不会立刻显示，重进会话才显示；Agent 回复能立刻看到"——根因：新版 opencode（next-17403+）把 `session.input.admitted/promoted` 改为 **`session.inbox.enqueued/delivered`**（事件名 + payload 结构全变）；App V2SseMapper 只处理旧事件名 → 用户消息播种失败（悲观消息设计：无本地占位，完全依赖 SSE 回显）→ 消息只等重进会话 REST 拉取才显示。Agent 回复走 step/text 事件不受影响（所以回复正常）
  - 修复（2026-08-14，curl 抓帧实证）：
    1. V2SseMapper 新增 `session.inbox.enqueued` 分支：{sessionID, inboxID, item:{type, payload:{text,agents}, delivery}}，兼容过渡契约 {id, prompt} 与旧契约 {inputID, input}
    2. SseClientV2.handleEvent synthetic 缓存同步适配（inbox.enqueued 缓存 inboxID→item / inbox.delivered 消费；保留旧事件名分支）
    3. +4 单测（inbox 播种 / 缺失 id 防御 / 过渡契约 / 旧契约保留）→ 1587 全通过
  - 验证：模拟器实测——发送 E2E_final_verify_ok → logcat `admitted: inputID=msg_... type=user`（无 unhandled）→ **UI 用户消息立即显示 ✅**
  - 工时：~1h | 难度：中 | 涉及：V2SseMapper/SseClientV2 | 优先级：P0（主流程）

- [x] **#124 退出会话后列表状态闪烁（releaseSessionData 误清 FSM 状态）** `ui` `session`
  - 问题：2026-08-14 用户反馈"从会话退到列表，退出会话的状态突然变没、突然又恢复在输出中，不连续"——根因：#89 修复引入的 releaseSessionData 在 ChatViewModel.onCleared（退出会话）时调用 `sessionStateService.clearSession(sessionId)` 清除 FSM 状态；但服务器仍在流式（SSE 全局连接持续投递 execution.started 等）→ 状态先清（列表显示无状态）→ 事件恢复（又显示 Working）→ 闪烁
  - 修复（2026-08-14）：releaseSessionData **移除 clearSession**——FSM busy/streaming 是服务器状态镜像（与 permission/question 处理哲学一致：服务器状态退出不清理）；内存由 24h staleness 自动清扫兜底（STATE_RETENTION_MS，非 Busy 会话超时移除）
  - 验证：模拟器实测——退出"opencode版本识别"会话（服务器流式中）→ 列表持续显示 Working（多次 dump 一致无闪烁）✅
  - 工时：~10min | 难度：低 | 涉及：EventDispatcher.releaseSessionData | 优先级：P0（视觉回归）

- [x] **#128 beta 真机崩溃：CompletionHandlerException（协程取消回调内抛异常）** `crash`
  - 问题：2026-08-14 用户真机（OnePlus PLK110, Android 16）beta 0.3.0-beta.8 崩溃——`kotlinx.coroutines.CompletionHandlerException: Exception in completion handler InvokeOnCancelling@c520a61 for StandaloneCoroutine{Cancelling}`（主线程）；栈特征：`StateFlowImpl.collect → dropWhile → takeWhile → SafeCollector.emit`——协程取消回调链里执行 flow emit → 触发下游 cancel → 嵌套 handler 异常；R8 混淆（ci1/yh1/j20/mz）无法直接定位源码
  - 完整日志：docs/research/crash-2026-08-14-completion-handler-beta.md
  - 初步方向（低置信）：① 全库搜 invokeOnCompletion/invokeOnCancelling 回调内做 emit/UI 操作；② 会话退出/切换的取消链（#124 onCleared 清理相关）；③ MessageEventHandler batchScope/persistQueue（#57 actor）；④ 用 betaRelease mapping 反混淆
  - 工时：待定位 | 难度：中-高 | 涉及：协程取消链 | 优先级：P1（真机崩溃，beta 用户受影响）
  - **2026-08-14 根因定位与修复完成（mapping 反混淆实证，commit ab20e24f）**：根因 = 数据层 runCatching 吞 CancellationException（Kotlin 已知陷阱）——HomeViewModel 主线程 collect 回调取消 loadProviders job 时协程不响应取消继续执行，完成处理链与取消状态竞争 → handler 抛异常 → 主线程 CompletionHandlerException。修复：① 新增 util/RunCatchingCancellable.kt（CancellationException 重抛，取消必须传播）② 数据层全量迁移 94 处/12 文件（Server/Session/Chat/File/Agent/Mcp/Vcs/DiagnosticLog/Settings/UnreadBadge/MessageStore）③ HomeViewModel catch(CancellationException) 前置 rethrow；RunCatchingCancellableTest +2 用例 → 1596 全通过；真机流式退出/切换压力测试 CompletionHandler 零出现（runbook 轮次10，commit 218754d7）；完整归档：docs/research/crash-2026-08-14-completion-handler-beta.md

### 2026-08-10 系统审计批次（F 报告 P0）
来源：docs/research/audit-2026-08-10/F-FINAL-AUDIT-REPORT.md（5 路交叉验证：A 渲染 + B 数据 + C 状态 + D 历史 + E 实测）

- [x] **#36 DatabaseRecovery catch 范围过宽 → 非损坏异常误删全库** `data` `security`
  - **2026-08-10 修复（37ef2129）**：withCorruptionRecovery 改为仅 `SQLiteDatabaseCorruptException`（含 cause 链遍历）触发删库；Full/Locked/Constraint/DiskIO/基类 SQLiteException 原样抛出不删库；DatabaseRecoveryTest 9 用例（损坏删库 / cause 链 / 5 类非损坏不删库 / 非 SQLite 传播）全通过
  - 问题：`DatabaseRecovery.kt:29-38` 捕获 `SQLiteException` 基类——`SQLiteDatabaseLockedException`（锁竞争）/`SQLiteConstraintException`（约束冲突）/`SQLiteFullException`（磁盘满）等非损坏异常都会触发 `deleteDatabase()`，缓存消息 + 归档 + 诊断日志全部清零。MessageStore 7 处调用点全包（:47, 226, 237, 242, 247, 269, 296）。唯一应触发删库的是 `SQLiteDatabaseCorruptException`
  - 修复：收窄 catch 到 `SQLiteDatabaseCorruptException`；或用 `Room.databaseBuilder().fallbackToDestructiveMigration()` 声明式；或返回 `Result<T>` 区分"损坏"（删）与"临时错误"（重试）
  - 工时：~2h | 难度：低 | 涉及：DatabaseRecovery.kt + DatabaseRecoveryTest
  - 来源：F §P0-1 + B §P0-1 + D TD-5（2 路确认）

---

## P1 — 核心功能需求

### 2026-08-06 清理与重构批次（已完成）
来源：2026-08 三份深度审计（死代码 / 架构 / 并发内存）。

- [x] **#9 死代码清理** `refactor`
  - 问题：TerminalRepository 体系（0 调用）、ServerConnectionRepository（NotImplementedError 死路径）、DraftUseCase（无脑转发层）、ChatRepository.undoRedo/replyPermission（零调用半实现）、ServerRepositoryImpl 三个空实现（静默失败）
  - 修复：全部删除；testConnection 移入 ServerConfigRepository；DraftInputDelegate/ChatViewModel 直连 DraftRepository
  - 工时：~3h | 难度：中 | 涉及：domain/repository + data/repository + di/DomainModule + fakes + 测试

- [x] **#10 分层修复（PendingPrompt / FileNode DTO 泄漏）** `refactor`
  - 问题：UI 层直接用 data.dto.FileNodeDto/ServerPaths 和 data 层 PendingPromptRepository 实现，domain 边界被绕过
  - 修复：PendingPromptRecord/PendingPromptRepository 提 domain；FileNode/FileType/ServerPaths domain 模型 + FileMapper 统一映射；DirectoryManager/SessionListViewModel/OpenProjectDialog 只依赖 domain
  - 工时：~3h | 难度：中 | 涉及：domain/model + domain/repository + FileMapper + 3 个 UI 文件

- [x] **#11 日志统一 AppLogger** `refactor`
  - 问题：72 处业务文件用 android.util.Log，诊断页不可见（AGENTS.md 规则要求 AppLogger）
  - 修复：61 文件批量替换 + import 收敛；AppLogger 加单测环境 NPE 防御（getStackTraceString null 回退）与 initialize 同步锁
  - 工时：~2h | 难度：低 | 涉及：61 个业务文件 + AppLogger.kt

- [x] **#12 缓存与状态清理** `data`
  - 问题：AppNotificationManager 去重缓存无服务器级清理（残留增长）；ToolSnapshotCache/toolExpandedStates 非线程安全；SessionStateService 状态容器无界
  - 修复：clearForServer + disconnect 时调用；ConcurrentHashMap；24h 无事件非 Busy 自动清扫
  - 工时：~2h | 难度：中 | 涉及：AppNotificationManager / ToolSnapshotCache / ChatRepositoryImpl / SessionStateService

- [x] **#13 一致性修缮（小项）** `refactor`
  - 问题：MainActivity 用 collectAsState（后台仍收集 DataStore）；DirectoryManager callbackFlow 缺 awaitClose（取消传播不规范）；AutoApproveRule round-trip 测试偶发失败（createdAt 默认值毫秒竞态）
  - 修复：collectAsState → collectAsStateWithLifecycle；callbackFlow → flow{}；测试显式传 createdAt 固定值
  - 工时：~1h | 难度：低 | 涉及：MainActivity / DirectoryManager / PermissionAutoApproverTest

- [x] **#14 Play 上架配套** `refactor`
  - 问题：上架需 AAB 产物、隐私政策、版本体系与 1.x 不符
  - 修复：bundleStableRelease 验证 + release-workflow §5.5；docs/PRIVACY_POLICY.md（中英双语）；版本重置 1.2.0→0.2.0（VERSION_CODE 18→1，接受卸载重装）
  - 工时：~2h | 难度：低 | 涉及：docs/ + version.properties + AGENTS.md

- [x] **#15 MessageDataDelegate 职责过载** `refactor`
  - 问题：730 行单类承担 8 个职责（消息/parts/SSE job/缓存/乐观消息/分页/工具展开/加载错误），改分页可能碰坏乐观消息
  - 方案：拆 MessagePaginationDelegate + OptimisticMessageStore；**chatMessageCache 与 lastCombineSessionId（铁律 8）必须留主体**
  - 工时：~0.5d | 难度：中 | 涉及：MessageDataDelegate + 相关测试
  - **2026-08-07 完成**：主体 731→520 行（-29%）；新类 MessagePaginationDelegate(139 行)/OptimisticMessageStore(168 行)；新增 24 白盒测试；编译 ✅ 全量单测 ✅（55s，0 回归）；模拟器冒烟 ✅（历史渲染/乐观 QUEUED/SSE 确认/滚动稳定，0 崩溃）；用户验收通过；遗留：分页未压力验证（需 100+ 消息长会话补测 loadOlder 路径）

- [x] **#16 ChatScreen 主函数臃肿** `refactor` `ui`
  - 问题：888 行文件，主函数约 600 行，滚动状态集群（autoScrollEnabled/isAtBottom/4 个 LaunchedEffect）内联
  - 方案：抽 rememberChatScrollController；**autoScrollEnabled/isAtBottom/双 key LaunchedEffect 必须整体搬移（SSE 铁律 4）**；编辑前读 chatscreen-editing-protocol.md
  - 工时：~0.5d + 真机验证 | 难度：高 | 涉及：ChatScreen.kt
  - **2026-08-07 完成**：新文件 ChatScrollController.kt(124 行) + ChatScreen 888→814 行（commit 1c59131e/ebfc0485）；编译 ✅ 全量单测 ✅；androidTest ChatScrollStabilityTest 7/7 ✅（滚动行为保持）；另顺带修复 androidTest DI 缺口（FakePendingPromptRepository，commit 待补）

- [x] **#17 SessionListViewModel 分层越界** `refactor`
  - 问题：全项目唯一混用 4 种数据源的 ViewModel（Api 绕过 Repository + EventDispatcher 细节 + internal val 暴露）
  - 方案：4 个 Api 下沉 UseCase；EventDispatcher 经 Repository 接口暴露；internal → private
  - 工时：~1-1.5d | 难度：中 | 涉及：SessionListViewModel / DirectoryManager / 新增 UseCase
  - **2026-08-07 完成**：4 Api+EventDispatcher 直调全部下沉（SessionRepository/FileRepository 扩 7 方法 + 6 新 UseCase）；internal 全转 private（Sessions.kt/Mcp.kt 搬回主类后删除）；DirectoryManager 注入 UseCase（缓存实例级保留）；对外 API 零变化；全量单测 1222（2 个预存在 flaky 已登记 #22）；grep 0 引用

---

## P1 — 核心功能需求

### 2026-08-21 会话内提示音批次（spec 已定案，待实现）
来源：grilling 会话共识（Q1–Q12 + F1–F5 全定案）。**设计 spec：`docs/specs/2026-08-21-in-session-audio-feedback-design.md`（实现前必读，含调研结论/静音矩阵/挂载点/测试缝）**。

- [ ] **#155 会话内提示音：被抑制的系统通知转为提示音+震动，严格镜像系统通知策略** `ui` `sse`
  - 需求：处于本会话（前台+焦点匹配）时，turn 结束/权限/问题/错误事件不发系统通知（现状已实现）但**零反馈**——补提示音+震动，策略完全镜像系统通知（渠道配置/铃声档/DND/app 开关四层，见 spec §6 静音矩阵）；错误 streak 只响第一声（成功完成 turn 或用户发新消息重置）；零新增设置项
  - 实现：新组件 InSessionFeedbackPlayer（独立去重 map，与通知侧物理隔离防"响过一声→离场补发通知被吞"）；策略镜像管线纯函数化（SoundPlan）；挂载抑制分支内部（SSE+REST 兜底全覆盖）· 测试缝：SoundPlan 矩阵单测 + streak 状态机单测 + 仿 AppNotificationDedupTest
  - 附带行为变更：SessionError 通知侧同步加 streak 去重（现状连续错误每次都弹，R4）；Manifest 补 VIBRATE 权限
  - 验证注意：模拟器无实际音频输出，维度 5 必须真机（houji）实测听声/震感/静音档/震动档/DND/自定义铃声

### 2026-08-21 错误日志 GitHub 上报批次（spec 已定案，待实现）
来源：grilling 会话共识（Q1–Q19 全定案）+ 日志分级审计。**设计 spec：`docs/specs/2026-08-21-error-report-github-design.md`（实现前必读，含全部实现决策与测试缝）**。

- [ ] **#151 错误日志 GitHub 上报（手动触发 + 指纹查重 + 重复评论）** `ui` `data`
  - 需求：Diagnostics 屏内把 ERROR/FATAL 上报到 `LeoNardo-LB/oc-beacon` 的 GitHub issue；已报过的错误不新建 issue 而是追加环境差异评论；强制预览可编辑；GitHub App device flow 授权（一次授权永久有效）
  - 实现：spec §Implementation Decisions（认证/指纹双轨/查重/防刷/失败处理全定案）· 测试缝：错误上报服务边界（fake GitHub 客户端）+ GitHub API 客户端（Ktor MockEngine，先例 V1/V2 API 测试）
  - 前置依赖：#152（上报质量前置）、#153（混淆堆栈可还原）、维护者注册 GitHub App（spec §Further Notes 有操作清单）

- [ ] **#152 前置：日志分级修复（SSE 灌水/双日志/丢堆栈，审计 15+ 处）** `sse` `refactor`
  - 问题：审计实证（2026-08-21，583 调用点）——SSE 重连风暴每断连灌 5-8 条/分钟（DROP_OLDEST 队列挤出真实错误）、同一失败双日志（SseConnectionManager:337+382）、per-event INFO 遗漏网（SessionEventHandler:107 等，#40 残留）、7 处 `e` 缺 throwable、WebView 子资源 404 记 e、遗留诊断标签（ActionModeDebug）
  - 方案：重连级联降级（i→d）+ 去双日志 + per-event 补 DEBUG 门控 + 补 throwable + 子资源门控；完整 file:line 清单见审计报告（会话 2026-08-21）
  - 依赖关系：#151 的"最近 20 条错误"在灌水修复前会被重连噪音填满——本条是其前置

- [ ] **#153 前置：release CI 留存 R8 mapping.txt artifact** `refactor`
  - 问题：release.yml 只上传 APK，mapping.txt 随临时 runner 销毁——用户上报的混淆堆栈永久无法还原
  - 方案：workflow 加 mapping.txt artifact 上传（与 APK 同批，90 天保留）

- [ ] **#154 上报增强：崩溃后自动提示 + secret gist 全量日志附件** `ui` `data`
  - spec §Out of Scope 明确后置项：①下次启动检测未上报 FATAL → 主动提示（需跨启动记账状态机）②全量日志 secret gist 链接（需额外 gist scope）
  - 触发：#151 落地并稳定后评估

### 2026-08-08 提问组件改进批次（待验证）
来源：用户口头反馈（2026-08-08）。

- [x] **#26 提问单选/复选控件语义纠正** `ui`
  - **2026-08-13 修复完成 + V1 实测通过 ✅**：单选互斥 bug 根因=isSingle 仅覆盖单问题场景，多问题中单选题目走 toggle 多选分支 → 修复：onOptionClick 按每道题 multiple 判断；头部紧凑化（16dp 图标 + labelLarge + bodyMedium 摘要）。V1 实测：Q1 点 Red 再点 Blue → Red 自动取消（仅 1 选中）；Q2 多选 Apple+Banana 保持双选；无崩溃
  - 原问题：用户反馈"单选框、复选框全部都由单选框组件来承担职责"——多选问题应显示复选框，单选才用单选框
  - 问题：用户反馈"单选框、复选框全部都由单选框组件来承担职责"——多选问题应显示复选框，单选才用单选框
  - 现状（代码已确认）：QuestionOptionRows（QuestionPartContent.kt:259）已按 `question.multiple` 分支渲染 CheckBox/RadioButton 图标；但需验证 `multiple` 字段在 SSE 事件 → QuestionParser → UI 全链路传递是否可靠（服务器未传 / 解析丢失时可能全部退化为单选样式）；历史视图 CollapsibleQuestionPart（QuestionPartContent.kt:135）答案固定用 RadioButtonChecked 图标，多选答案也应显示 CheckBox 样式
  - 调研方向：先确认真实渲染路径（活动提问走 QuestionCard + QuestionPagerView，历史走 CollapsibleQuestionPart / QuestionExpandedOptions），定位 multiple 丢失点；再按 M3 语义修正图标
  - 工时：~2h | 难度：中 | 涉及：QuestionPartContent.kt / QuestionCard.kt / QuestionParser
  - **2026-08-08 代码完成（待人工验证）**：QuestionParser 新增 `ParsedQuestion.isMultiple`（JSON multiple 3 解析点 + 7 测试，commit 04b3fb33）；CollapsibleQuestionPart 历史答案图标按 isMultiple 分支 CheckBox/RadioButtonChecked + PartContent 调试日志清理（commit a86b2e87）；编译 ✅ 全量单测 ✅ i18n ✅；⚠️ 真机验证待用户：活动/历史多选显示复选框

- [x] **#27 多问题提问"下一步/提交"流程** `ui`
  - **2026-08-13 用户验收 ✅**：Next/Submit 切换正确、未答完弹窗正常（"第 X 个问题没有回答"→可继续提交）、单选点选可取消——全部通过
  - 问题：多问题场景（questions.size > 1）右下角直接是提交按钮；应改为非最后一个问题时显示"下一步"，点击跳转到下一个问题，最后一个问题才显示"提交"
  - 现状：QuestionCard.kt:207-232 底部 Row 只有 Dismiss + Submit（`!isSingle` 时显示）；QuestionPagerView 已有 TabRow + HorizontalPager（Q1/Q2/Q3 标签可点击跳转），下一步按钮可复用 `pagerState.animateScrollToPage`
  - 方案：QuestionCard 增加"当前页"状态，底部按钮随页变化：非末页 → "下一步"（跳页），末页 → "提交"（onSubmit）；Dismiss 保持
  - 工时：~1.5h | 难度：中 | 涉及：QuestionCard.kt / QuestionPagerView.kt（可能）+ i18n（下一步文案 15 语言）
  - **2026-08-08 代码完成（待人工验证）**：QuestionCard 三按钮体系（忽略/下一步/提交，末页置灰）+ 未答完提交弹窗（"第 X 个问题没有回答" → 继续提交）+ 单选点选不立即提交可取消选中；QuestionPagerView page-aware 签名；纯函数 `unansweredQuestionIndexes` + 4 测试；i18n 新增 4 键（15 语言，commit 10757799）；编译 ✅ 全量单测 ✅ i18n ✅；⚠️ 真机验证待用户：三按钮流程/弹窗/单选可取消

- [x] **新增 A：会话列表"待回答"标记 + 提问通知 REST 兜底——2026-08-18 模拟器验证：功能有效但发现并修复两个 P1 兜底缺陷；2026-08-19 通知形态代验收 ✅** `ui` `session` `sse`
  - 问题：有提问的会话在列表无任何提示；SSE 不推 question 事件时通知不可达（无兜底链路）
  - **2026-08-08 代码完成（待人工验证）**：SessionRow 增加 HelpOutline 图标 + "Pending answer" 标记（i18n 15 语言，commit a989890e）；OpenCodeConnectionService 新增 30s REST 轮询兜底（`notifyPendingQuestionsFromREST` + `diffNewQuestionIds` 纯函数 + 3 测试，commit 1d1b2a75）；编译 ✅ 全量单测 ✅ i18n ✅；⚠️ 真机验证待用户：列表标记显示/通知弹出
  - **2026-08-18 模拟器验证（SSE 路径 ✅ + 发现 REST 兜底两缺陷已修）**：
    - ✅ SSE 路径：agent 调 question → 列表行 "Pending answer" 标记正常显示（Untitled session 实证）；通知日志 `Question asked for session ses_…` 落库（App logs 表）+ opencode_questions 通知通道（importance=4 声光振动）存在
    - ❌→✅ **缺陷1（轮询永久死亡，P1，已修 32765cf6）**：原 `if (!isConnected) break` 在 connect 后 SSE 握手窗口首轮 tick 即杀死轮询且永不复活——实测启动 12 分钟 form/request **0 次**（对照组 /api/session/active 40 次），服务器端存在 pending form 的会话（E2E-C）列表无标记。修复：轮询生命周期只跟随用户连接意图（disconnect 显式取消兜底），去掉 isConnected 检查
    - ❌→✅ **缺陷2（location 覆盖缺口，P1，已修 32765cf6）**：V2 form/request 按 x-opencode-directory 分 location 返回，不带头只返回 global——项目目录的 pending form 永远查不到（实测 oc-beacon location 的 Favorite Season form）。修复：遍历 global + 全部项目目录（directory 字段缺失回退 canonical——beta-17595 只返回 canonical），10 轮缓存
    - 修复闭环验证：force-stop 后服务器新 form → 冷启动纯 REST 路径 22s 内 form/request 8 次 + 列表 2 行 "Pending answer" 出现；1690 单测全绿
  - **2026-08-19 模拟器代验收（通知形态）✅**：授权 `pm grant POST_NOTIFICATIONS` 后重触发——A1 dumpsys 铁证：`opencode_questions` 通道 mImportance=4 + 声音/闪光/振动全启用；A2 通知栏实拍（ab_11_shade_notif.png + vision）：`OC Beacon Dev` 应用图标 + 标题 **`问题 · 验收测试会话AB`** + 正文 + 时间戳，浅灰圆角卡片形态规范；A3 列表标记 uidump 三行实证（测试会话本行「待回答」bounds [409,605] + 两行 legacy 会话）。注意：首次测试发现模拟器通知权限从未授予（importance=NONE）——环境前置而非 App 缺陷。附带发现（P3 已登记）：通知正文取最后一条用户消息（触发 prompt 原文）而非问题文本本身（AppNotificationManager:379 findLatestUserMessages 优先于 questionText）

- [x] **新增 B：双端同机问题状态同步修复——2026-08-18 模拟器验证 ✅（A 回答 → B 消失闭环）；2026-08-19 代验收 ✅** `data` `session`
  - 问题：设备 A 回答后，设备 B 的 `loadPendingQuestions` 旧合并逻辑（`existingSseQs + newQs`）只增不删 → 已消失问题永久残留
  - **2026-08-08 代码完成（待人工验证）**：新增 `resolvePendingQuestionReplacement` 纯函数，声明 REST GET /question 为全量权威源，`loadPendingQuestions` 全量替换（含空列表清空语义）+ 3 测试（commit 0b85ca06）；全量单测无回归；⚠️ 真机验证待用户：双端同机 A 回答后 B 问题消失
  - **2026-08-18 模拟器验证 ✅**：B 端（App）进 E2E-C 会话 → REST 恢复卡片渲染（`loadPendingQuestions: 1 total pending → Replaced 1 questions (REST authoritative)` 日志）→ 设备 A（curl 直答 form 204）→ B 端 6s 内收到 `form.replied` SSE → 卡片消失转 "Asked" 折叠态——双端同步完整闭环。⚠️ 待用户最终验收
  - **2026-08-19 模拟器代验收 ✅（精确计时）**：curl 答 form（HTTP 204, 16ms）→ SSE `QuestionReplied` 到达 App 并派发（logcat 01:21:56.669）→ 首个观察截图（t0+1.5s）卡片已折叠 "Asked" 态——**端到端 ≤1.5s**；agent 复述「已收到你的回复：Apple（苹果）🍎」确认答案真实送达。三轮问答（fruit/color/animal）同路径全部即时同步（证据 /tmp/verify-acceptance/b_01~b_04）

- [x] **#30 消息本地化批次（方案 C）——Plan 1/2/3 全部完成（代码），待人工验证** `data` `cache` `room`
  - **2026-08-13 验证完成 ✅（用户授权 Agent 代测）**：冷启动打开会话 1 秒内消息渲染（Room 种子化秒开）✅；杀进程重启后消息保留（Room 缓存）✅；db 2.2M（ocbeacon.db 1.82MB + WAL 524KB）✅；覆盖安装保留数据 ✅
  - 问题：消息缓存/日志存储仍用手写 SQLite（DiagnosticLogDatabase 手写 SQL，路径分隔符/大小写敏感性风险）；消息本地化（方案 C）需先落地 Room 基础设施
  - 方案：按 Plan 分阶段——Plan 1 Room 基础设施（依赖 + 数据库骨架 + LogStore + Repository 迁移）；Plan 2/3 消息缓存与本地化落地
  - 工时：Plan 1 ~4h | 难度：中 | 涉及：app/build.gradle.kts / data/local/room/* / LogStore / DiagnosticLogRepository
  - **2026-08-08 Plan 1 完成**：Room 2.8.4 依赖（199bb36f）→ 数据库骨架 cached_messages/cached_parts/logs 三表 + LogDao + 插桩测试（60345b68）→ LogStore 诊断日志 Room 存储（修剪策略等价迁移 + 单测，53562a4b）→ DiagnosticLogRepository 迁移，删除 DiagnosticLogDatabase，手写 SQL 清零（3b206574）；编译 ✅ 全量单测 ✅（--rerun 26s PASS）；androidTest 编译受预存 #29 阻塞（Fake 缺接口方法，与本次无关）；手写 SQL grep 0 引用；⚠️ Plan 1 人工验证待用户：Diagnostics 日志显示/修剪/21 天语义
  - **2026-08-08 Plan 2 完成（代码，待人工验证）**：MessageApi 游标分页（before + X-Next-Cursor，9b3610e5）→ MessagePage 下沉 domain（5e208397）→ MessageStore Room 消息缓存（限量 1000 条/会话，81c2573e）→ 分页管线缓存优先（本地渲染 + REST 增量 + 真游标翻页，b6a6f461）→ 游标编解码 base64url JSON（3d0929dc + 595d63b2 fix）→ upsert 合并策略统一（SSE_PRIORITY/REST_AUTHORITY/APPEND_ONLY）+ SSE 双写 Room（caf8019b）→ 冷启动种子化 getMessagesFlow 从 Room 填充内存热视图（本次 Task 6）；编译 ✅ 全量单测 ✅（--rerun 46s，1305 tests PASS，含新增种子化测试）；⚠️ Plan 2 人工验证待用户（6 项，见 task-6-report）：秒开/离线浏览/翻页边界/SSE 重启保留/1000 条限量/磁盘占用
  - **2026-08-09 Plan 3 完成（代码，待人工验证）**：存储层架构清理四任务——Task 1 UnreadBadgeService 抽出（红点时间源独立，消除 EventDispatcher 的 runBlocking 落盘，3d828265）；Task 2 StreamingOwnershipRegistry 抽出（多服务器 SSE 去重独立化，c6bbd71a + 测试状态隔离 775b257e）；Task 3 SettingsDataStore 三文件合并（ReadTimes/Tags 扩展函数 → 成员方法，50b2af95）+ DraftDataStore 迁移 DataStore（含旧 File 草稿一次性迁移保数据，d4a906d7）；Task 4 DI 模块合并（DataModule 并入 DomainModule，FakeDomainModule 同步 replaces，domain 层无 data 依赖，3d674d10）；编译 ✅ 全量单测 ✅（--rerun 26s，1313 tests PASS）；androidTest 编译受预存 #29 阻塞（Fake 缺接口方法，与本次无关）；⚠️ Plan 3 人工验证待用户（5 项，见 task-4-report）：红点恢复/双服务器流式去重/会话列表红点/草稿恢复/Diagnostics+消息缓存回归
  - **2026-08-09 遗留处理完成（代码，待人工验证）**：#31 本地库损坏自愈（DatabaseRecovery：SQLiteException → 删库重建，6fdff190）；#29 androidTest 编译修复（3 Fake 补 7 接口方法，1ae44d57，androidTest 编译恢复）；网络失败回退本地缓存（有缓存不显示空，b843f265）；P4 清理（测试名/owner 日志/MessageRefresher 命名，f770e60d）；编译 ✅ 全量单测 ✅（1313+ tests PASS）；**一期代码全部完成，待 14 项人工验证**（见下）
  - **2026-08-09 模拟器走查（10 项）**：V1 Diagnostics ✅ / V2 秒开+种子化 ✅（[seed] 有/无缓存双分支命中）/ V3 断网浏览 ⚠️ 部分（断网冷启动受服务器连接入口限制，架构使然；已连接→断网→会话内浏览待真机）/ V4 重启保留 ✅ / V5 真游标翻页 ✅（logcat 实证 before=base64 游标前进）/ V6 红点标签收藏 ✅ / V7 草稿恢复 ❌ **发现 bug 已登记 #33**（saveDraft 仅 onCleared，force-stop 丢失）/ V8 磁盘占用 ✅（ocbeacon.db 1.5MB 合理）/ V9 SSE 流式 ✅（PONG 实测 + MessagePartUpdated 84 事件）/ V10 回归 ✅（无崩溃）
  - **2026-08-09 补充验证（3 项）**：①双服务器去重 ✅ 架构层面保证（同 URL 第二连接被 app 阻止，无双投递可能；"Thought"标记仅 1 次实证；衍生 #34 连接 UX/#35 ANR）；②日志修剪 ✅ db 实证（注入 60 FATAL 22 天 + 30 INFO 3 天 + 30 ERROR 22 天 → 启动即全部清除，时间规则 + FATAL 限量 + 字节预算按源码预期）；③**限量裁剪 ✅ 第三轮实证**：注入 1100 条 → 发送真实消息触发 upsert → `[prune] removed 101 oldest msgs (limit=1000)` → db 1100→1000 数学吻合（关键发现：仅进入会话不触发裁剪——prune 只在真实新消息写入时执行，设计如此）；**一期 14 项验证：13 项通过/部分，仅 #33 草稿缺陷待修**

### 2026-08-10 系统审计批次（F 报告 P1）
来源：docs/research/audit-2026-08-10/F-FINAL-AUDIT-REPORT.md §3.2

- [x] **#37 combine 索引错位 args[8]→args[9]，工具进度 UI 永久失效** `ui` `sse`
  - 问题：`MessageDataDelegate.kt:172` 错把 `args[8]`（statuses Map）当作 `args[9]`（progressList）→ `progressList` 永远 null → `progressOutputs = emptyMap()` → 工具进度 output 注入永久失效，用户看不到工具执行中的实时 output。combine 第 8 参是 statusFlow、第 9 参是 getActiveToolProgressForSession(sid)
  - 修复：line 172 `args[8]` → `args[9]`（改一个字符）；或用类型安全 combine 变体 / data class 包装根治
  - 工时：~10min | 难度：低 | 涉及：MessageDataDelegate.kt:172
  - 来源：F §P1-7 / C S3

- [x] **#38 ChatViewModel.init / SessionListViewModel 构造期 runBlocking 主线程阻塞** `ui` `refactor`
  - 问题：ViewModel 构造在 Hilt 主线程执行，两处 runBlocking 同步阻塞：① `ChatViewModel.kt:93-96` `runBlocking(IO) { serverRepository.getServer(serverId) }`；② `ChatViewModel.kt:368-373` `draftDelegate.restorePersistedDraft()` → `DraftDataStore.ensureLoaded` → `runBlocking { dataStore.data.first() }`（DraftDataStore.kt:34-50）；`SessionListViewModel.kt:97-99` 同样问题。低端设备/磁盘忙时成 ANR（实测 99th 300ms × 3 帧，首帧贡献源之一）。0eaac6dc 仅修了 onCleared 路径，init 路径完整保留
  - 修复：serverConfig 改 StateFlow<ServerConfig?> + TerminalDelegate 派生 flow；DraftRepository 接口改 suspend fun getDraft 或 Flow<Draft>；DraftDataStore 内部 runBlocking 改 withContext(IO)
  - 工时：~1-2d | 难度：高 | 涉及：ChatViewModel / SessionListViewModel / DraftDataStore / DraftInputDelegate / DraftRepository 接口
  - 来源：F §P1-5 / C S1,S5 + D §2.3

- [x] **#39 日志风暴残留（ChatMessageList 诊断埋点无 DEBUG 门控）** `ui` `performance`
  - 问题：b07b7ccc 清理了 MessageDataDelegate 日志风暴，但 ChatMessageList 内诊断埋点遗漏——① `ChatMessageList.kt:251-267` JUMP 检测 `LaunchedEffect(Unit)` snapshotFlow 持续 collect 每帧（注释明示"诊断埋点...验证后"）；② `ChatMessageList.kt:555-557` 每 item 组合日志 `AppLogger.d` 无 BuildConfig.DEBUG 门控。直接贡献 Slow UI thread（实测 48/160 = 30%）
  - 修复：删除诊断埋点（诊断任务已完成，注释明示）；与 b07b7ccc 一致策略
  - 工时：~30min | 难度：低 | 涉及：ChatMessageList.kt:251-267, 555-557
  - 来源：F §P1-2 / A 环节 F + D 模式 B

- [x] **#40 StateFlow.update CAS lambda 内副作用日志（UnreadDiag/PartUpdated）** `refactor` `performance`
  - 问题：高频 SSE 场景下 `update{}` CAS 重试导致日志被多次持久化到 Room（INFO 级即使 DEBUG 关也持久化）+ 违反纯函数约定：① `MessageEventHandler.kt:567-582`（line 575）`AppLogger.i("UnreadDiag", "[markIdle]...")` 在 `_messages.update {}` 内（实测 1.6 条/s）；② `MessageEventHandler.kt:238-272`（line 250-258）`AppLogger.w("[PartUpdated]...")` 在 `_parts.update {}` 内（实测 11 条/s 活跃，CAS 重试可能 2x）。b07b7ccc 遗漏残留
  - 修复：日志移到 `.update` 外（先 update 拿结果再 log）；或彻底删除诊断日志；对所有 `_*.update {}` lambda 做 lint 禁止副作用
  - 工时：~1h | 难度：低 | 涉及：MessageEventHandler.kt:238-272, 419-428, 463-472, 567-582
  - 来源：F §P1-6 / C S2 + D 模式 B + E 实测（5 路最高置信度）
  - **2026-08-10 完成（待真机验证）**：grep 全量确认 21 处 `_messages.update`/`_parts.update` lambda 内零 AppLogger 调用（此前 DIAG 清理已移除），无需改动；R2 流式 10s 应用日志 0 条佐证

- [x] **#41 loadOlderMessages 缺乏并发保护 → 竞态重复加载** `data` `session`
  - 问题：翻页时多个并发 launch 可能用相同 archiveCursorCreated 拉相同消息。`MessagePaginationDelegate.kt:194-260` line 197 `_isLoadingOlder.value=true` 在 scope.launch 内无入口 guard；触发链 `ChatMessageList.kt:361-385` snapshotFlow collect 无去抖。`_isLoadingOlder` 仅作 UI 状态指示未作互斥锁
  - 修复：入口 guard `if (_isLoadingOlder.value) return`；或 MutableStateFlow.update CAS pattern；或 actor/Semaphore(1) 串行化
  - 工时：~1h | 难度：中 | 涉及：MessagePaginationDelegate.kt:194-260
  - 来源：F §P1-3 / B P1-1
  - **2026-08-10 完成（待真机验证）**：`synchronized(this)` 包住 check-then-set 入口 guard（StateFlow 无 CAS，synchronized 与项目现状一致）；finally 已覆盖异常路径复位；编译+全量单测 1364/0；⚠️ 模拟器上滑触发受 #64 超长消息滚动失效阻碍未完整实测，逻辑已单测覆盖

- [x] **#42 upsert 写入路径 O(n log n) 排序残留** `performance` `refactor`
  - 问题：b07b7ccc 移除了 combine 内排序，但写入路径 sortBy/distinctBy+sortedBy 仍在：`MessageEventHandler.kt:151`(handleMessageUpdated)、`408`(upsertSsePriority)、`453`(upsertRestAuthority)、`508`(upsertAppendOnly)。1000-2000 条会话每次变更 ~10000-40000 次比较；batchScope 后台线程但高频累积 CPU
  - 修复：existing 已有序时改 merge（O(n)）替代 sortedBy（O(n log n)）；或用 TreeMap/有序数据结构维护
  - 工时：~0.5d | 难度：中 | 涉及：MessageEventHandler.kt:151, 408, 453, 508
  - 来源：F §P1-4 / B P1-2 + D TD-9 + A §3 表（3 路确认）
  - **2026-08-10 完成（待真机验证）**：4 处全部改 `mergeSortedMessages` 线性两路归并（O(n+m)），同 id 冲突/相同 created/稳定排序语义与 `distinctBy+sortedBy` 逐字节等价（多组边界推演）；incomingSorted 计算移出 update lambda 避免 CAS 重试重复计算；模拟器 R2orderingtest42 3 轮发送严格按序无乱序/重复/丢失；编译+全量单测 1364/0

- [x] **#43 反射依赖 Compose internal 字段 → 升级必崩** `crash` `refactor`
  - 问题：高度补偿通过反射访问 LazyListState private 字段（scrollPosition、requestPositionAndForgetLastKnownKey、measurementScopeInvalidator）——`ScrollCompensation.kt:22-46`；调用点 `ChatMessageList.kt:318, 448, 539`（3 处）。Compose 版本升级会运行时崩溃（NoSuchFieldError/NoSuchMethodError），无编译期保护。根因：官方 requestScrollToItem 会通过 scroll{} 互斥锁杀死 fling，无"设置位置但不取消 fling"公开 API → 反射 hack 补丁
  - 修复（短期）：try-catch 包裹 + NoSuchFieldError 时降级 requestScrollToItem；Compose 升级前手动测试反射字段名。长期：向 Compose 提 feature request
  - 工时：~0.5d | 难度：中 | 涉及：ScrollCompensation.kt:22-46 + ChatMessageList.kt 3 处调用
  - 来源：F §P1-1 / A 环节 E（补丁判定）
  - **2026-08-10 完成（待真机验证）**：ScrollCompensation.kt 初始化一次性探测 3 个反射成员（失败永久降级）+ 调用 try-catch 防御（catch Throwable 降级官方 requestScrollToItem）+ 注释标明 Compose BOM 2026.05.01 与字段名；ChatMessageList.kt 3 处调用点均经封装无需改（SSE 滚动铁律零接触）；模拟器程序化滚动/补偿正常无崩溃

- [x] **#79 本地存储精简：工具返回值截断——结案（P0 ✅ e7ca830f + P1 ✅ ea4b7f4a 双重验收；P2 评估不做）** `data` `refactor` `storage`
  - 需求：2026-08-12 用户系统性评估——会话全量信息本地保存是否合理。实测多会话数据库 28MB，其中 **tool parts（工具返回值）占 12.4MB（97%）**：shell 输出 5.1MB / read 2.6MB / websearch 1.1MB / edit 1.1MB / grep 667KB / webfetch 627KB；消息元数据仅 1.18MB + text 239KB（对话本体很小，纯文本合理）
  - 方案（已系统性分析，按优先级）：
    - **P0**：Room 写入时截断 tool part 的 state（返回值）——只存前 200~500 字符预览 + 总长度标记；展开时调 `getMessage(messageId)`（API 已有 V2ApiClient:409）按需拉全量。**只影响本地落库**，内存渲染不受影响（消息在内存时工具卡片完整可展开）
    - **P1**：reasoning 截断/丢弃；patch 只存统计（+N/-M）+ 文件名不存 diff 全文
    - **P2**：synthetic 通知不落库或保留最近 N 条；subagent 内容不落库（点击进入时加载）
  - 权衡：离线恢复时工具卡片显示摘要无法展开全量（可接受）；服务器始终保留全量可重拉
  - 工时：P0 ~0.5d | 难度：中 | 涉及：MessageStore.upsertParts + 工具卡片展开按需加载
  - **2026-08-18 P0 完成（e7ca830f）**：ToolOutputTruncator——落库前 tool part payload JSON 层重写 state.output（500 字符预览+截断标记；其余字段原样；解析失败原样返回）。E2E 实证：bash 500 行 40KB 输出 → DB payload 965 字节（~98% 降），内存渲染完整（UI 显示执行摘要+输出行不受影响）；单测 5/5 + 全量绿。⚠️ 展开按需拉全量（getMessage）未做——离线恢复时工具卡片仅摘要（权衡已获用户接受）
  - **2026-08-19 P0 离线观感代验收 ✅**：飞行模式 + force-stop 重启（pid 变更实证）→ Room 缓存渲染会话列表/消息正常 → 「批量输出命令执行」会话（500 行 bash 输出源数据）工具卡片以摘要形态渲染：折叠态 = 命令头 `$ for I in $(seq 1 500)...` + 完成状态行 `完成 · Line 1: 这是一行测试输出 lorem ipsum dolor si...`（预览首行）+ 展开箭头；无乱码/无空白卡/无崩溃（FATAL=0）。观感符合「摘要可读、完整输出在服务器」的产品预期（证据 /tmp/verify-acceptance/p3_01~p3_05）。P1/P2 仍待做，条目保持 [~]
  - 与 #80（快速导航全量列表）不冲突——列表基于 role=user 元数据，不受 parts 截断影响
  - **2026-08-19 P2 评估：不做**（收益≈0）——实测 synthetic 消息 77 条共 10.7KB、subagent parts 31 条共 35KB，合计 <47KB 占 12.9MB 库 **0.36%**（P0/P1 已消掉 97% 大头）；
  - **2026-08-19 P1 DB 铁证验收（用户指示模拟器校验优先）**：Room 直查 cached_parts——reasoning 截断行 192 条 max=**798**（500 预览+后缀+JSON 结构）vs P1 前历史行 525 条 max=45069（写入时截断，历史行不动=设计内）；tool 同型（截断 420 条 max 2387 vs 历史 max 19983）。渲染侧：reasoning 折叠块（思考完毕·时长）当日多会话反复正常渲染。P0（08-19 飞行模式离线代验收）+ P1 双闭环，**结案**而代价是离线时 synthetic 通知卡消失、subagent 卡片内容丢失。按目标铁律「确认每点真实存在/适配当前代码」登记为不适配。条目保持 [~] 仅因用户最终验收（P0/P1 观感）

- [x] **新会话默认模型（方案 A·本地默认，2026-08-16 实现 658abb11；2026-08-19 模拟器 E2E 代验收 ✅）** `model` `feature`
  - 需求：用户 2026-08-16 提出——新会话可设置默认模型，免去每次手动切换
  - 实现（658abb11，当日即时实现未走 backlog，本条目补登）：
    - 存储：SettingsDataStore 按 serverId 存 `server_default_model_<id>` = "pid|mid"（🟠 V2 config 只读硬约束 → 本地存，代价=换设备丢失，代码注释标记）
    - 解析链：显式选择 > 会话最后模型 > **本地默认** > provider default（ModelConfigDelegate combine 第 13 源——状态非源不触发重算的前车之鉴）
    - UI：ModelPicker 每行星标（content-desc "默认模型"）设置/取消；整行点击仍选模型（职责分离）；i18n 15 语言
    - 传输：发送时 sendMessage → switchModel（POST /api/session/{id}/model 嵌套契约，2026-08-14 实证 204 端点）→ prompt
  - **2026-08-19 模拟器 E2E 全链路验证 ✅（证据 /tmp/verify-dm/）**：新会话#1 pill=GLM-5.3（无默认时 provider default）→ picker 星标 DeepSeek V4 Flash Free → DataStore 字节实证 `opencode|deepseek-v4-flash-free` + 星标 filled → **新会话#2（全新无历史）pill 立即显示 Build·DeepSeek V4 Flash Free（uidump 铁证）**→ 发消息 logcat `[model] POST .../model providerID=opencode modelID=deepseek-v4-flash-free` → **服务器 assistant 回复 model={"id":"deepseek-v4-flash-free"}**（模型真实切换非仅 UI）→ 取消星标 DataStore CLEARED + 测试会话已删。注意：与 agent 切换（beta-17595 body agents 被忽略，见 2026-08-19 兼容发现）不同，模型切换走独立端点可靠
  - 已知限制（设计内）：variants 不参与默认（保持简单）；换设备丢失（本地存储）

- [x] **#81 度量/风格/边距统一提取为 token 主题系统——已修 88740e2a（行高维度收口）** `refactor` `ui`
  - 需求：2026-08-12 用户提出——将度量参数（如模型列表单行 item 高度 40dp）、风格、边距等样式统一提取为 token/主题系统
  - 现状：已有 SpacingTokens/ShapeTokens/AlphaTokens/ButtonTokens（ui/theme/），但部分组件仍硬编码数值（如 ModelPickerDialog 的 heightIn(min=40.dp)、padding 12/8dp 等散落各处）
  - 方向：新增 ItemTokens（列表项高度/密度规格：40dp 密集 / 48dp 紧凑 / 56dp 标准）、统一列表项 padding/间距引用；对照 docs/ui-conventions.md 的 token 体系扩展
  - 工时：~1d | 难度：中 | 涉及：ui/theme/* + 各列表组件（ModelPicker/QuickNavigate/后台面板等）
  - **2026-08-19 triage + 实现（88740e2a）**：triage 发现 padding 维度已被 ListItemTokens 覆盖（设置页 20+ 项消费）；真实缺口是行高维度。新增 ui/theme/ItemTokens.kt（MinHeightDense 40 / Compact 48 / Standard 56，与 ListItemTokens 互补）；ModelPickerDialog 2× heightIn(40) + 全部硬编码 padding 迁 SpacingTokens；TaskSheet 2× 容器 padding 迁 LG。QuickNavigate 复查已全 token 化；QuestionPartContent 44dp 为带公式注释的文本框特例（正确保持内联）。E2E：模型行 7 行 bounds 高度一致性 ±0px（pitch 126px 与 token 语义吻合）、选择/管理入口/任务面板全功能无回归、FATAL=0（证据 /tmp/verify-itemtokens/）。**收口说明**：Spacer(8/12dp) 类微间距散点（AboutScreen 等 ~30 处）属 SpacingTokens 已有刻度的机械替换，无视觉变化且回归面大——后续新代码按 ui-conventions 引用 token 即可，不再做存量批量清偿

- [x] **#80 快速导航全量列表（本地 Room 全量 user 消息，非仅已加载窗口）** `data` `feature`
  - 需求：2026-08-12 用户反馈"快速定位不准确"——实测根因：快速导航列表基于 rawMessages（已加载窗口）只显示 7 个 item，本地热表实际有 35 条 user 消息（多会话 3939 条中 role=user 占比 35/153）
  - 方案：JumpTargetExtractor 数据源扩展为本地 Room 全量（热表 role=user 查询）；点击未加载目标 → loadAround（c0d28535 已实现服务器版）本地优先（beforeId+afterId 双查询）→ 现有 merge 路径
  - **2026-08-12 实施完成**：JumpTargetExtractor 数据源切至 MessageStore.userMessages（Room 热表 role='user' 全量，上限 1000 条）+ synthetic/空壳消息过滤
  - **2026-08-13~14 用户反馈驱动多轮迭代**：① JumpNavigationController 跳转状态机——架构根治（dd15c352）② 目标 key 前缀匹配——根治"定位到回复"（d3340c18）③ 漏消息（fetchAllMessages 防呆上限 20→100 页）+ 只第一次加载（loadAround 失败保护延迟 500ms 竞态修复，2979fa94）④ fling 快速滑动跳过 agent 长气泡——预组合窗口 1→6 项（4395ec8c）

---

## P2 — 优化与锦上添花

- [x] **提问卡片 M3 原生化改造（消除"外来物"拼盘感）——2026-08-19 模拟器代验收 ✅** `ui`
  - 背景：2026-08-17 用户反馈主对话中提问卡不美观，希望用 M3 原生组件。grilling 共识（Q5=A/Q6=B/Q7=A/Q8=A/Q9=不纳入）：内嵌保留 + 控件原生化 + 活动/历史统一 + 分页保留
  - **2026-08-17 代码完成（待人工验证）**：
    1. Q5：QuestionCard 容器 Surface → **OutlinedCard** + 表单头部（AutoMirrored HelpOutline + "待你回答"新键 question_awaiting_reply，15 语言）；AMOLED contentColor 语义保留
    2. Q6：选项行自绘 Surface+BorderStroke+Check → **M3 ListItem + 原生 RadioButton/Checkbox**（leading 控件 + 整行 clickable；material3 1.4.0 ListItem 无 onClick 重载，用 Modifier.clickable）；自定义答案三态同步 ListItem 化
    3. Q7：Q1/Q2/Q3 tab 压高 SegmentedButton（hack）→ **原生 FilterChip**（32dp 自身设计高度）
    4. Q8：CollapsibleQuestionPart 历史折叠卡容器 → OutlinedCard（与活动卡统一）；展开态经 QuestionPagerView(readOnly) 自动继承
    5. 死导入清理：QuestionCard.kt 30+ / QuestionPartContent.kt 8
  - 验证：compileDevDebugKotlin ✅ 全量单测 ✅ i18n 15 文件 ×1 ✅
  - **2026-08-17 E2E 三轮实测（模拟器独占，真实 V2 服务器）**：
    - 布局终态（用户迭代三轮）：标题栏（? + 待你回答 + **Q1|Q2 SegmentedButton 原生高度** + SINGLE/MULTI 标签同行右侧）→ 分割线 → 纯问题文本 → ListItem 选项行 → 输入框 → 按钮；历史视图元信息行在 pager 上方（无标题栏）——E2E-3 截图确认全部渲染正确
    - 行为：单选互斥 ✅ / 多选勾选 ✅ / SegmentedButton 双向翻页 ✅ / 多自定义共存+定点删除+Edit 替换 ✅（定点重测 dump 铁证）/ 提交通道送达 ✅（agent 复述完整答案）/ 历史折叠展开 ✅ / FATAL=0
    - **样式终态（第四轮用户决策 f191a876）**：选择指示 = 右侧 ✔（选中才显示，未选中无控件）；ListItem 行结构保留。E2E 像素级验证 6/6
    - **2026-08-18 美化重构（5be0e8b5/dc0562de/1c19b59c）**：双审计驱动——tonal 实底化（描边 3→0 层、明度锯齿 Δ7→0.2、圆角 12dp、左缘收敛）、Next→Tonal、Q-tab→FilterChip、问题 14sp Medium。像素复审计四指标全达标
    - **2026-08-18 自定义输入语义纠正（e6aae7a0）**：用户澄清自定义=提交自己的回答非添加选项——回滚多自定义支持，恢复三态（空态输入框/已输入行/编辑替换）；E2E 三断言全过（dump 结构性证据：保存后卡内 0 EditText、编辑预填当前值、删除后输入框恢复）
    - **2026-08-18 三个真 bug 根治 + 真机终验 ✅**：① 输入框字体缩放裁切（4de66692：定高 44dp 溢出切字形 → 自绘 CustomAnswerInput heightIn 内容驱动 + 6 项美化：14sp 对齐/焦点 tonal/Enter 提交/40dp 触达/✕ 取消）② REST 恢复卡 key=null → answer={} "未作答"（41811a2d，用户真机复现翻案 E2E-B）③ 点选项挤掉自定义答案（d4f436b5：toggleQuestionAnswer 纯函数两槽位互不挤占，单测直调生产函数 15 处）——**真机终验（OPPO PLK110/中文 UI/OEM 输入法）：两槽位/无裁切/IME 提交全过，FATAL=0**（/tmp/e2e15/）
    - **2026-08-18 基础容器统一（a90dbead）✅**：用户指出提问卡应有与其他卡片一样的基础容器——tonal 实底（surfaceContainerHighest）与气泡仅差一档无边框，"融进"气泡不像独立卡片。抽共享组件 EmbeddedCardContainer（样式 = FileCard 既有基准：surfaceContainerLow 实底 + 1dp outlineVariant 边框 + 12dp 圆角 + 无投影），应用 3 处（活动卡/历史卡/FileCard 自身改调共享容器）。真机像素验证：容器 244 vs 气泡 227（17 档差）+ 边框线清晰可辨，vision 走查 "distinct card container, no flaws"（/tmp/e2e16/）。此后所有内嵌卡片走同一容器不再漂移
    - 迭代中发现并修复：多选自定义只渲染第一个的结构缺陷（b6bf568f）**⚠️ 2026-08-18 勘误：该"修复"是语义误解**——用户澄清自定义输入 = 提交自己的回答（至多一个，三态输入框），不是"添加选项"；"多自定义支持"与"输入框常驻"已回滚（见下方语义澄清条目）；衍生登记 E2E-C（导航丢状态 P1）/E2E-D（v2 reply 恒 404 P2）
    - 注：视觉模型对全卡截图 3 次幻觉"左侧有控件"，最终以逐行裁剪放大 + 像素扫描定性（E2E 截图判读的方法学经验）
  - **第五轮视觉微调（6bad8c39 + a3e181d6）**：① 元信息序调换——SINGLE/MULTI 标签左、Q1|Q2 分段按钮右（E2E px 证据）② 分段按钮 40→32dp+labelSmall ③ 问题域间距 SM→MD（实测 12.6dp）④ 行紧凑化——M3 ListItem（固定 48dp+ 无 padding 参数压不矮）→ 紧凑 Row（单行实测 27.8dp；带 description 双行内容驱动）⑤ 自定义行图标序 Edit/✕/✔（✔ 最右对齐普通行；✕=删除该自定义）⑥ 输入框高度对齐 44dp——三轮演进：heightIn(44) 无效（min 非上限）→ Provider 压 LocalMinimumInteractiveComponentSize 无效（M3 源码实证该 Local 只管 icon 边距）→ **显式 height(44.dp)** 终验达标（可视边框精确 44.0dp、无裁剪、三态恒定、FATAL=0）
  - ⚠️ 人工验收待用户：整体观感（维度 5 视觉目测，截图在 /tmp/e2e3/ /tmp/e2e5/ /tmp/e2e6/ /tmp/e2e8/）
  - **2026-08-19 模拟器代验收（用户授权"用模拟机搞定"）✅**：多状态画廊 + 视觉审查（/tmp/verify-acceptance/p4_01/p6_01~06）——双问题活动卡（待你回答 + SINGLE 标签左 / Q1|Q2 chips 右、问题文本、紧凑选项行含 description 副文本、自定义输入框、忽略/下一个/提交三按钮）、选中态（accent wash (221,222,237) + 右侧 ✔，单/多选像素一致）、自定义三态（Emacs 保存行 Edit/✕/✔ + 删除复原）、折叠历史 "Asked" 态。vision 审查：tonal 实底圆角容器、无拼盘感、行语言统一。结合 2026-08-18 补充复验与真机终验（e2e15/16），观感维度以模拟器画廊 + 真机功能终验合并结案
  - **2026-08-18 补充复验（模拟器，beta-17595）**：双题卡全交互链正常——SINGLE/MULTI 标签、Q1|Q2 FilterChip 分页、选项行（含 description 副文本）、自定义三态（输入→保存勾选 accent wash 像素 221,222,237）、三按钮（Dismiss/Next/Submit）、提交后折叠 "Asked" 态。截图 /tmp/verify-0818/20-29；交互细节归档见「2026-08-18 模拟器验证批次」
  - 行为保持：单选互斥/多选/单选可取消/三按钮流程/#125/#126 全部未动

- [x] **提问卡容器对齐工具卡主流语言（7f278a93 + a76cd513）** `ui`
  - 2026-08-18 完成（含方向纠正）：用户澄清诉求是"提问卡改成跟其他卡片一致"（此前两轮 a90dbead/d9cbb252 做反成"其他卡片改跟提问卡"——d9cbb252 已 revert 8db1e786，其他 6 种卡片恢复原样）
  - 提问卡（活动+历史）换 ToolCardScaffold 主流语言：AmoledSurface + **surface 同色** + smallMedium(6dp) + tonal 1dp 无边框（AMOLED 纯黑+边框内建）
  - E2E 同屏像素证据：提问卡与 Shell 工具卡均无描边、同 6dp 圆角、tonal 家族、底色一致（/tmp/e2e19_both.png）；toggle 两槽位/自定义三态逻辑零改动（diff 铁证）；EmbeddedCardContainer 组件保留（FileCard 用）

- [x] **权限卡视觉复审（提问卡原生化后的配套）——已修 8d452e08** `ui`
  - 来源：2026-08-17 grilling Q9 决策不纳入当时批次
  - 2026-08-18 更新：EmbeddedCardContainer 已支持 containerColor 语义色覆盖——权限卡可低成本迁入（骨架统一 + error 红语义底透传，参照 ToolCardScaffold 状态色模式）
  - **2026-08-19 triage 勘误 + 实现（8d452e08）**：EmbeddedCardContainer 实际**无** containerColor 参数（2026-08-18 记录超前于实现，且该容器全家迁移已在 d9cbb252 回滚）；提问卡终态（三次修正后）= ToolCardScaffold 语言（AmoledSurface + smallMedium 6dp + tonal 1dp）。权限卡照此迁移：AmoledSurface(normalColor=errorContainer) + 标准边框（AMOLED 纯黑），内容/按钮/触感零改动。E2E：卡片渲染（需要权限+图标+位置标签+三按钮）、像素铁证（卡片 249,222,220 红调 vs 背景 250,248,255 中性）、三按钮全链路（拒绝/仅一次无规则/始终允许+确认框）、FATAL=0（证据 /tmp/verify-permcard/）。AMOLED 形态未单独复验（同组件此前批次已验证）

- [x] **#71 后台系统 + V2 消息链路 D4 人工验收（时间性现象，自动化无法覆盖）** `ui` `sse`
  - **2026-08-13 验收 ✅（用户口头确认"后台没啥问题" + Agent 数据正确性确认）**：shell 生命周期（created/exited/deleted）与内联展示数据与服务器 SSE 事件一致；流式期间 Back 无 ANR。⚠️ 附注：`session.tool.progress` 事件被 SessionNextEventHandler 标记 Unhandled（工具实时进度缺口）→ 登记 #92
  - 问题：2026-08-11 后台系统（入口/工具栏/面板/Shell 卡片）与 V2 消息链路（V2SseMapper 流式）开发完成，自动化验证（编译/单测/E2E 功能走查）全部通过；但以下**时间性现象**自动化无法覆盖，需用户真机验收后才可声称完成（verification-requirements.md 维度 5）：
  - 验收清单：
    1. **转后台工具栏**滑出/消失动画（fade + expandVertically）——出现时机正确、动画顺滑无跳动
    2. **后台入口按钮**角标数字出现/消失过渡（BadgedBox）——有后台活动时数字正确、无闪烁
    3. **后台面板**（ModalBottomSheet）——上拉/拖拽关闭手感、Subagents/Shells tab 切换流畅、子会话跳转返回正常
    4. **SSE 流式节奏**——AI 回复逐字出现无闪烁/卡顿/跳底（SSE 铁律）；停止生成后状态立即恢复
    5. **消息即时显示**——发送后用户消息 3s 内出现（V2SseMapper input.admitted 播种），多轮连续发送顺序正确
  - 验证环境：模拟器 + 真实 V2 服务器（10.0.2.2:4199），**测试专用会话**（用户指定）
  - 证据：docs/research/RG-2026-08-11-v2-contract-background.md（D4 待验收项）
  - 状态：`[~]` 待验证——**用户逐项验收通过后勾选 `[x]`**；发现任何问题改回 `[ ]` 进入修复

- [x] **#67 V2 后台完成通知：synthetic 消息被过滤（PartContent Text 分支）** `sse` `ui`
  - 问题：2026-08-11 V2 契约对齐调研确认——opencode v2 后台任务/subagent 完成时向主会话注入 `POST /api/session/{id}/synthetic` 合成消息；但 oc-beacon 的 PartContent.kt Text 分支 `part.synthetic != true` 直接过滤 → 用户看不到后台完成通知
  - 方案：识别 synthetic 消息并以特殊样式（卡片/淡色+标签）渲染，或独立事件通道驱动通知
  - 来源：docs/archive/specs/2026-08-11-v2-contract-alignment-design.md §3（synthetic 端点）+ 后台系统调研
  - **2026-08-11 完成（实测澄清）**：Part.Text.synthetic 全项目无赋值点（过滤是死代码，消息本就能显示）；服务器 POST synthetic 后不广播 SSE 事件（仅 REST 可见）。新增 MessageCardRole.SYNTHETIC + SyntheticNotificationCard（居中淡色卡片+图标+时间），模拟器实测显示 "后台测试完成通知：subagent X 已完成" ✅

- [x] **#68 V2 新会话创建后 get/pending 404（服务器怪癖）** `session` `data`
  - 问题：2026-08-11 实测——新建会话出现在 `GET /api/session` 列表（自动生成标题），但 `GET /api/session/{id}` / `pending` 返回 `SessionNotFoundError`（带/不带 x-opencode-directory 均 404）；服务端重启/升级（next-17132→17135）后可能自愈
  - 方案：待复现确认；若稳定复现需调研 V2 location/workspace 路由语义（列表可能跨 location 返回而 get 限定 location）
  - 来源：模拟器 E2E 实测（2026-08-11）

- [x] **#69 session.instructions.updated 事件未处理（低频 parse error）** `sse`
  - 问题：2026-08-11 回归走查发现 1 次 `session.instructions.updated` parse error（无 parser 匹配且触发异常路径）；频率极低（指令更新时）
  - 方案：V2EventParser handledPrefixes 加 `session.instructions.` 占位解析（返回 SessionNext Unknown 即可）
  - 来源：回归走查 logcat（2026-08-11）
  - **2026-08-11 完成**：SseClientV2.parseV2Event data/properties 判型防御（instructions data 是数组时 jsonObject 扩展抛异常 → 回退顶层字段）+ V2EventParser handledPrefixes 加 session.instructions.；V2EventParserTest 新增用例；实测 parse error 归零

- [x] **#70 V2 事件体系未确认项——已完结（①崩溃路径实证排除 + durable 恢复发现）** `sse` `refactor`
  - 问题：docs/archive/specs/2026-08-11-v2-contract-alignment-design.md §7 列出 7 项未确认：
    1. `session.retry.scheduled` payload 结构（未触发重试未抓到）——影响 Retry 状态映射
    2. `/api/config` info 已实测无 mcp 字段——McpRepositoryImpl 的 mcp 配置来源需确认（当前 type 回退 "local"）
    3. `/api/session/active` type 完整枚举（目前仅见 "running"）
    4. listPtyShells 正确端点已实测 = `/api/pty`（✅ 已修复 #Task7）
    5. completeProviderOauth 已补 `/api` 前缀（✅ 已修复 #Task7）
    6. `session.tool.failed` 事件已实测存在（✅ mapper 已支持）
    7. 多 step 工具循环已实测同 assistantMessageID（✅ 幂等 upsert 天然处理）
  - 方案：剩余 1/2/3 项在下次触碰相关功能时补测；4-7 已闭环
  - 来源：docs/archive/specs/2026-08-11-v2-contract-alignment-design.md
  - **2026-08-19 盘点补测（beta-17595 curl）**：② **已答**——/api/config 的 document 条目 info **含 mcp 字段**（用户配置有 mcp 块；当时"无 mcp 字段"应为旧版本或配置为空），McpRepositoryImpl 可从 config info 读取；③ **已答**——/api/session/active 返回 **map 格式** `{data:{sessionId:{type:"running"}}}`，类型仅见 "running"（无 idle/error 等其他值可观测）。① retry.scheduled 仍无法主动触发（需真实失败重试场景），保持 touch-when-needed。**条目收敛：仅剩①（条件触发时抓 payload）**
  - **2026-08-19 ①崩溃路径实证（kill -9 实验，两个 SSE 监视窗口全程捕获）**：生成中（65 reasoning.delta 已流）kill -9 服务器 → 重启 → **durable 事件日志自动恢复被中断的 turn**（74 reasoning.delta + 36 text.delta + text.ended，最终 assistant 1179 字符完整交付，会话转 idle 无僵尸）——**全程无 session.retry.scheduled**。结论：① 崩溃-重启路径不发出该事件（durable 恢复静默续跑取代之，App 重连后自然看到续流，无需 Retry UI）；剩余唯一触发面 = provider 级瞬时失败（429/5xx）需真实故障 provider—— inherent 外部条件，与「下次发生时抓」同义。**App 侧有价值的副产品认知：服务器崩溃不丢 turn，SSE 重连即续**

- [x] **#29 androidTest 编译修复（#25 已读标记遗留）** `refactor` `data`
  - 问题：commit 5793957f（#25 已读标记服务器域重构）为 SessionRepository 增加 `getLastCompletedReplyTimeFlow()`、SettingsRepository 增加 5 个已读状态方法，但 FakeSessionRepository/FakeSettingsRepository 未同步实现 → `compileDevDebugAndroidTestKotlin` 从该 commit 起持续失败（2026-08-08 悲观重构验证时发现，与重构无关的预存在问题）
  - 方案：Fake 补缺失接口方法（按接口签名 + 现有 fake 语义实现），恢复 androidTest 编译
  - 工时：~30min | 难度：低 | 涉及：androidTest/fakes/FakeSessionRepository.kt、FakeSettingsRepository.kt
  - **2026-08-09 完成（待人工验证）**：3 个 Fake 补齐 7 个接口新成员（FakeChatRepository.upsertMessages 按 MergeStrategy 分支；FakeSessionRepository.listMessages 改 before+MessagePage 签名 + getLastCompletedReplyTimeFlow；FakeSettingsRepository 5 个已读方法），commit 1ae44d57；compileDevDebugAndroidTestKotlin BUILD SUCCESSFUL ✅（解锁 LogDaoTest 等全部插桩测试编译）；⚠️ 真机验证待用户：插桩测试套件实际运行（connectedDevDebugAndroidTest）

- [x] **#28 提问组件样式与高度统一优化** `ui`
  - **2026-08-13 修复完成 + V1 实测通过 ✅**：Q tab 替换 M3 大 Tab（48dp）→ 自绘 28dp 胶囊 tab（选中高亮）；问题文本/选项描述 bodySmall→bodyMedium。V1 实测：tab 高度 ~27dp 吻合、字体可读性良好
  - 原问题：用户反馈提问卡片样式不好看、各组件高度不统一、提问区域缺少外边距，"缩在一起很难看"
  - 问题：用户反馈提问卡片样式不好看、各组件高度不统一、提问区域缺少外边距，"缩在一起很难看"
  - 现状：QuestionCard 用 AmoledCard + padding SpacingTokens.MD，内部 spacedBy SM；选项行 Surface padding 12/8dp、图标 16dp、文本 bodyMedium/bodySmall；QuestionPagerView 多问题用 TabRow + HorizontalPager
  - 调研方向：M3 官方无 Stepper/提问组件，但可参考官方组件规范——RadioButton/Checkbox 触摸目标 48dp、SegmentedButton（SingleChoice/MultiChoiceSegmentedButtonRow）选项场景、AlertDialog 内容间距规范、LinearProgressIndicator 作步骤指示；对比各组件理论高度与当前实现的差距；检查外边距/间距 tokens（SpacingTokens）是否按 M3 规范使用；评估是否适合改用官方组件
  - 工时：~2-3h | 难度：中 | 涉及：QuestionCard.kt / QuestionPartContent.kt / theme tokens
  - **2026-08-08 代码完成（待人工验证）**：选项行 48dp 触摸目标（defaultMinSize）、图标 16→24dp、间距统一（SpacingTokens.XS/SM/MD）+ QuestionPagerView 多问题分支 spacedBy SM（消除"缩在一起"，commit 83f4ea31）；编译 ✅；⚠️ 真机验证待用户：行高统一/图标大小/间距舒展（维度 5 视觉目测）

- [x] **#18 ChatMessageList 指纹函数外移** `refactor`
  - 问题：765 行因铁律 6-8 缓存逻辑膨胀
  - 方案：只外移纯函数（messageFingerprint/partsFingerprint/tailHash/messagesSignature）到 util/MessageFingerprints.kt；缓存函数高度耦合不动
  - 工时：~30min | 难度：低 | 涉及：ChatMessageList.kt

- [x] **#19 Phase 历史注释清理** `refactor`
  - 问题：约 30 处"在 Phase N Task X 中提取"注释，工作已完成，纯历史噪音
  - 方案：批量删除历史标记，保留功能说明
  - 工时：~30min | 难度：低 | 涉及：ui/screens/chat/* + FileViewer* + Workspace*

- [x] **#20 SearchMatchDto 字段对齐** `data`
  - 问题：DTO 字段与 API 不匹配（API 是 path:{text}/line_number，DTO 是 lines/lineNumber）；当前 /find 未启用未触发，启用即静默失败
  - 方案：按 API 对齐字段（@SerialName 处理 snake_case）
  - 工时：~1h | 难度：低 | 涉及：FileResponses.kt（启用 /find 前必须完成）

- [x] **#21 androidTest 4 个 flaky 用例** `ui` `data`
  - 问题：2026-08-07 #16 androidTest 回归发现 4 个 flaky（与重构无关）：`ChatInteractionIsolatedTest.scrollToBottomFab_appearsWhenScrolledAway`（swipeDown 手势未越阈值）、`ChatInteractionTest.abortSession_callsAbortApi`（等 Stop 按钮超时）、`ChatInteractionTest.permissionDialog_appears_whenPermissionRequested` 与 `questionDialog_appears_whenQuestionAsked`（interactionState 7 路 combine 时序）
  - 方案：失败时重试/等待策略；scrollToBottomFab 用更可靠手势（多段 swipe）；其余 3 个检查 isBusy/interactionState 传播时序
  - 工时：~2h | 难度：中 | 涉及：app/src/androidTest/chat/*
  - **2026-08-07 完成（系统性调试）**：4 用例实为稳定失败非 flaky，根因 3 类——(1) abortSession：测试注入真实 SessionStateService 但 VM 经接口注入 FakeSessionStateRepository（fake 状态机缺失）→ Fake 增强 FSM 模拟 + 测试改操作 Fake；(2) permission/question：测试未 seed 消息走 ChatEmptyState 分支致 ChatMessageList 未渲染 → 补 seedConversation；(3) scrollToBottomFab：全屏 swipeDown(0.05-0.95) 无效改默认；questionDialog 断言歧义（问题文本 2 处）改 onAllNodes().onFirst()；两轮验证 4/4 通过

- [x] **#22 单测 2 个 flaky 用例** `data`
  - 问题：2026-08-07 #17 全量单测（1222）发现 2 个预存在 flaky：`PermissionAutoApproverTest`（序列化相关，此前 round-trip 修复后仍有残留）、`FileViewerViewModelTest`（协程异常时序）；均已三重验证（HEAD 通过/单独跑通过/不引用改动类）与重构无关
  - 方案：PermissionAutoApproverTest 检查 createdAt 等时间字段的序列化竞态；FileViewerViewModelTest 检查协程异常处理时序
  - 工时：~1-2h | 难度：中 | 涉及：app/src/test/...
  - **2026-08-07 完成**：PermissionAutoApproverTest 固定 createdAt 消除毫秒默认值竞态；真根因在 SessionListViewModel finally 块空流 first() 抛 NoSuchElementException 泄漏全局线程池污染无关测试（改 firstOrNull）；FileViewerViewModelTest 防御性加固；全量单测连续 3 次全过

- [x] **#23 SessionList combine 魔法索引 tuple 化重构** `refactor`
  - 问题：SessionListViewModel.combine 22 个源 + SessionListStateBuilder 的 values[18..22] 魔法索引——加源/删源时索引错位（2026-08-07 已踩坑多次：多选/未读/基线/一键已读各加一次源，每次索引偏移导致编译错误多轮修复）
  - 方案：combine lambda 内构造具名数据类（如 SessionListInputs），StateBuilder 接收它而非裸 Array<Any?>；或 combine 嵌套分组
  - 风险：中（改动 combine 签名 + StateBuilder + 相关测试）；收益：消除索引错位类 bug
  - 备注：索引语义注释已加（StateBuilder 顶部），缓解了短期风险
  - 备注：2026-08-07 已落地——状态切片方案（spec: docs/archive/specs/2026-08-07-session-list-state-slicing-design.md）

- [x] **#24 长 turn（多步工具调用）期间未读红点延迟** ui session
  - 问题：agent 长回复（多 step 循环）期间，红点要等整个 turn 结束（服务器发 SessionStatus=idle）才出现——用户在列表等待时迟迟看不到红点（2026-08-07 subagent 实测：后台 13 分钟长 turn 无红点）
  - 现状：列表有 Working/busy 状态指示可缓解
  - 待决策：是否需要"进行中即红点"（每条 assistant 消息 completed 即算）？权衡：与"turn 结束绑定"的用户需求冲突
  - **2026-08-07 关闭（用户决策）**：红点绑定 turn 完全结束是**设计意图**（用户确认），不实施"进行中即红点"。idle 丢失兜底已确认无需做——L3/L4 REST 校验 → FSM forceComplete 链路覆盖 SSE 丢失。衍生项：#25 时钟一致性（当前实施中）；FOLDER 折叠未读计数 / busy 图标强化 / 未读置顶排序 暂不实施

- [x] **#25 红点时间戳时钟一致性** `data` `session`
  - 问题：红点判定混用服务器时刻与客户端时刻——`MessageUpdated` 的 `time.completed`（服务器时钟）与 `markSessionIdle` 覆盖的 `System.currentTimeMillis()`（客户端时钟，MessageEventHandler.kt:520）都流入 `_turnEndTime`；已读时间（readTimes/allReadAt）是客户端 now。本机部署（模拟器连本机 serve）时钟一致无实害；**连远端服务器时时钟偏差 → 红点误报/漏报**
  - 前因：2026-08-07 用服务器时刻（StepEnded.timestamp / completed）是为了避免"退出后事件才到达 → 客户端接收时刻偏晚 → 红点误报"；markSessionIdle 的客户端 now 是 SSE 丢失时 REST 回退补标记的权宜
  - 状态：**调研中**（2026-08-07 起）——需先完成时间戳来源全图谱 + 历史演进梳理，再定优化重构 or 根治方案
  - 工时：调研后再估 | 难度：中-高 | 涉及：EventDispatcher.kt / MessageEventHandler.kt / SessionStateService.kt / SettingsDataStore
  - **2026-08-07 完成**：红点改派生状态模型——maxCompleted（服务器 completed 时刻）+ isUnread 加 status==Idle 门控（turn 结束才红点）+ 已读标记/一键已读改服务器域（markRead 传 completedTs、全局 max）+ v2 迁移（EventDispatcher init 触发，清旧客户端域值）。全量单测（39s）+ 构建安装 + 真机回归 6 场景通过 5/6。**含重启未读恢复**：maxCompleted 持久化（82fc2493）——杀进程重启后未读回复红点恢复，spec §5.7 原"遗留 concern"已解决
- [x] **#31 本地库损坏自愈（Room 版）** `data` `room`
  - 问题：Plan 1 迁移后删除了旧 withDatabaseRecovery（catch SQLiteException → deleteDatabase 重建），Room 版无等价兜底；ocbeacon.db 损坏时 recordBatch 异常会传播至 AppLogger。Room+WAL 较旧实现健壮，诊断日志非用户资产，属低风险
  - **2026-08-09 完成（待人工验证）**：新建 DatabaseRecovery（@Singleton，`withCorruptionRecovery` 捕获 SQLiteException → deleteDatabase → Room 自动重建空库，commit 6fdff190）；LogStore/MessageStore 读写路径接入；DatabaseRecoveryTest 3 用例（成功/SQLiteException 触发删除/非 SQLite 传播）+ 全量单测 PASS；⚠️ 真机验证待用户：模拟 db 损坏后 App 自愈（可选，低优先级）

- [x] **#32 归档压缩（二期：热/冷分层 + 整桶 zstd + TLRU 淘汰）** `data` `room` `cache`
  - 背景：消息本地化批次（#30）Plan 1/2/3 已代码完成（待人工验证）；归档为本需求二期，用户决策：一期（归档之外）全部开发完并人工验证后再开发二期
  - 方案（spec §9 批次 2 + 调研结论）：热表（近期可查）→ 归档表按 (session, 时间桶) 整桶序列化 zstd 压缩（单桶 ≤512KB，CursorWindow 2MB 限制）；压缩触发 = TLRU（now − last_accessed > TTL 如 14 天 或 会话超阈值）；解压触发 = 用户向上滚动到归档边界异步解压整桶入热表（UI loading）；重压缩 = 后台 sweep 超 TTL 未访问桶
  - 技术选型：zstd-jni（Maven Central AAR，~1MB ABI）| 单条消息压缩在 Android 是负优化（<10KB 收益 < 开销，Discord 实测）→ 必须整桶压缩
  - 工时：~1-2d | 难度：中-高 | 涉及：MessageDao/MessageStore 扩展 + ArchiveBucket 表 + sweep 协程 + zstd-jni 依赖 + 翻页管线解压分支
  - **2026-08-09 二期启动**：一期（#30）模拟器验证已全覆盖（14 项 + 补充 3 项，仅剩真机最终确认，用户暂不在附近）；用户决策"先开发二期，注意提交隔离"→ **开发分支 `feature/archive-compression`**（基于 master 954e3c89，未合回）；zstd-jni 最新稳定版 **1.5.7-13**（2026-08-08，Maven Central，Android AAR 支持 API 21+）；二期完成并经验证后合回 master
  - **2026-08-09 二期代码完成**：SDD 6 任务 + 最终 whole-branch review + fix wave（I1 原子事务/I2 Migration 测试/I3 512KB 字节切分 + M2-M9），全量单测 **1342 通过/0 失败**（最终修复含归档游标推进后新增 2 测试）；MigrationTest 编译验证（运行时待模拟器）
  - **2026-08-09 模拟器验证全部通过（日志+db 实证）**：①DB v1→v2 Migration ✅（user_version=2）；②归档写入 ✅（[archive] 161 msgs→1 bucket，热表精确回 1000，压缩率 21x）；③归档读取 ✅（[dearchive] 逐桶 + [paging] from archive + source=ARCHIVE）；④断网离线浏览 ✅（飞行模式仍可读归档）；⑤数据完整性 ✅（解压大小精确匹配、161 条消息完整可解析、热表+归档**零重复**——I1 原子事务有效）；⑥无崩溃 ✅
  - **2026-08-09 完整场景矩阵验证（22 项全过）**：512KB 字节切分 ✅（13条/桶 ≤512KB，0 超限）；跨天分桶 ✅（10/11 天窗口）；TLRU 淘汰 ✅（251 桶→evicted 61 保持 200，leastAccessed 优先）；归档失败降级 ✅（坏 payload → prune-only fallback 实测触发）；**坏桶 skip-continue ✅（bucket=267 decode failed, skipping，跳过继续读后续桶）**；多会话归档隔离 ✅（db 实证仅目标会话有归档）；冷启动种子化 ✅；UI 消息渲染 ✅（uiautomator 抓到消息文本）；迁移旧数据保留 ✅；touch lastAccessedAt ✅
  - **2026-08-09 归档逐条容错（69df372b）**：模拟器实测发现——单条 payload 解码失败（测试注入的 path 字段格式错误）导致**整批归档失败**（500 条全丢，降级为 prune-only）。修复：逐条 runCatching 跳过坏消息（`skip undecodable msg` 日志），好的仍归档。补单测 `upsertMessages_archiveSkipsUndecodableMessage_keepsBatch`
  - **2026-08-09 #35 ANR 复现**：验证过程中 Back 触发 ANR（Input dispatching timed out，wait queue 2）——**根因：SSE 高频流量（每分钟数百事件）占满主线程**，Back 键输入事件排队超时。与二期归档无直接关系（归档在 IO 线程）。backlog #35 已登记，待专项排查（SSE 事件处理主线程负载优化）
  - **2026-08-09 修复（d30a0d57）**：模拟器实证发现**归档翻页死循环**——loadOlderMessages 的 before 始终取热表最老（归档只进内存不落热表 → 热表最老不变 → 每次翻页读同一批归档桶）。修复：Delegate 维护**归档时间游标**（ARCHIVE 来源用返回最老消息 created 推进；NETWORK 来源重置），use case 增加 beforeCreated 参数优先用它查归档。修复后验证：before 正确前进 → 归档读尽 → 网络回退 ✅

- [x] **#33 草稿在进程被杀时丢失（saveDraft 仅 onCleared 触发）** `data` `session`
  - **2026-08-13 验证 ✅（Agent 代测）**：输入草稿 → force-stop → 重启重进会话 → 草稿完整恢复（截图 v33_draft.png）
  - 问题：2026-08-09 模拟器走查（V7）发现——ChatViewModel.kt:430 的 draftDelegate.saveDraft() 仅在 onCleared() 调用，am force-stop / 系统低内存杀进程不触发 onCleared → 草稿丢失（输入框重置为 placeholder）。预存问题（触发时机一直如此，非 #30 的 DataStore 迁移引入；迁移只改存储机制）
  - 影响：用户按 Home 后台 + 系统杀进程 → 草稿丢失；正常返回（ViewModel onCleared 触发）不受影响
  - 方案：updateDraftText 加防抖定期 saveDraft()（如 1-2s 无输入即存），或 Activity onStop/onSaveInstanceState 触发；需评估写频率与 DataStore 成本
  - 工时：~1h | 难度：低 | 涉及：DraftInputDelegate / ChatViewModel
  - 来源：2026-08-09 模拟器走查 V7
  - **2026-08-09 完成（待人工验证）**：DraftInputDelegate.updateDraftText 加 500ms 防抖自动持久化（DRAFT_SAVE_DEBOUNCE_MS，scope.launch + delay，每次输入取消重启）；clearDraft 取消挂起 job（防清空后又被存回）；onCleared 兜底保留；新增 DraftInputDelegateTest 4 用例（防抖窗口内不存/快速输入只存最后一次/clear 取消/即时状态更新，commit e3ffeae7）；编译 ✅ 全量单测 ✅；⚠️ 真机验证待用户：输入草稿 → force-stop → 重启 → 草稿仍在

- [x] **#34 同 URL 第二服务器连接 UX（永久 Connecting 无提示）** `ui` `sse`
  - **2026-08-13 验证 ✅（Agent 代测）**：允许添加同 URL 服务器；连接时提示 "Already connected to this server"，未卡 Connecting（日志：HomeViewModel shares backend with already-connected）（截图 v34_dup_server.png）
  - 问题：2026-08-09 双服务器验证发现——同 URL 第二个配置点 Connect 后永久卡 'Connecting...'（>60s 无握手/无错误/无日志），手动 Cancel 才能退出。架构上 app 限制同 URL 单一活跃 SSE 连接（防双投递），但 UX 无反馈
  - 方案：检测到同 URL 已有活跃连接时直接拒绝并提示'该后端已连接'，或复用现有连接；或加超时/错误提示
  - 工时：~1h | 难度：低 | 涉及：连接管理 UI + SseConnectionManager
  - 来源：2026-08-09 双服务器去重验证走查
  - **2026-08-10 完成（待真机验证）**：根因 = OpenCodeConnectionService.connect 已有 url+username 去重但静默 return，HomeViewModel 乐观 connecting 状态无回传 → 永久 Connecting。修复：HomeViewModel connectToServer 经 serviceBinder.findDuplicateBackend 预检（ServerConfig.sameBackend 归一化：协议/host 小写、默认端口、尾斜杠）→ 命中写 connectionErrors 红字提示"该服务器已连接"（home_error_already_connected，15 语言）；Service 内去重保留为纵深防御。编译 ✅ i18n-check ✅（583 keys × 14 语言一致）

- [x] **#35 会话内 Back 触发一次 ANR（待复现）** `crash` `ui`
  - **2026-08-13 验证 ✅（Agent 代测）**：流式输出期间按返回键——无 ANR、无 crash、无 "not responding"（crash buffer 空）；SSE 高频负载下 Back 正常（截图 v35_back.png）
  - 问题：2026-08-09 走查——首次启动后会话内按 Back 触发 ANR（'OC Beacon Dev isn't responding'），force-stop 重启后恢复。可能与 SSE 长连接 + 主线程阻塞有关。仅一次未复现
  - 方案：待复现——logcat 抓 ANR trace；检查 Back 导航路径是否有主线程阻塞（会话关闭时的同步操作）
  - **2026-08-10 模拟器高强度复现未复现**（D35）：55+ 轮（标准循环 20 / 加载中 Back 10 / 双击 Back 10 / 后台切换 5 / 300ms 高强度 20）零 ANR 零崩溃；最大 GC pause 91.69ms、最长帧 ~4.9s 均未触发阈值；52 条 ERROR 全为 JobCancellationException（Back 取消分页加载的预期行为）。结论：模拟器无法复现，疑似偶发/低端真机内存压力场景；保持 P2 低优先，真机复现后再查。证据：docs/research/audit-2026-08-10/D35-investigation.md + metrics/D35-*（45 份 logcat）
  - **副发现（新条目 #65）**：Back 退出时 JobCancellationException 被记为 ERROR 级（日志噪声），建议降级 INFO/DEBUG
  - 工时：待复现后再估 | 难度：中 | 涉及：会话导航/生命周期
  - 来源：2026-08-09 双服务器验证走查
  - **2026-08-09 根因确认并修复**：真机 ANR trace 抓取——退出会话 → ChatViewModel.onCleared → draftDelegate.saveDraft → DraftDataStore.persist → **runBlocking 阻塞主线程**（草稿 DataStore IO 在主线程同步执行）。修复（0eaac6dc）：onCleared 改 `viewModelScope.launch { withContext(NonCancellable) { saveDraft() } }`（异步不阻塞主线程）+ DraftInputDelegate.saveDraft/clearDraft 移 Dispatchers.IO；编译 ✅ 全量单测 ✅（1343 PASS）；⚠️ 真机验证：用户确认闪退/卡死已修复（2026-08-10 用户实测 ✅）

- [x] **新增 C：会话列表点击进入会话的过渡动画丢失** `ui`
  - 问题：2026-08-10 真机排查滑动卡顿期间发现——点击会话进入会话界面时，如果会话内容未加载完毕，原应有过渡动画（loading 过渡）；现在过渡动画也没有了，进入会话直接无过渡/直接显示
  - 影响：进入会话体验突兀（无加载过渡反馈）；可能与导航/加载状态显示逻辑近期改动有关（#23 状态切片或翻页管线改动后）
  - 方案：对比 ChatScreen 进入时的 loading 状态显示逻辑（SessionLifecycleDelegate.loadSession 加载编排 + ChatScreen 加载态）；确认过渡动画缺失点（加载指示器/淡入过渡）
  - 工时：~1h | 难度：中 | 涉及：ChatScreen 加载态 / 导航过渡 / SessionLifecycleDelegate
  - 来源：2026-08-10 真机排查滑动卡顿（用户口头反馈，明确"记一下，后面修复"）
  - **2026-08-10 根因并修复**：加载动画逻辑存在（`isLoading && messages.isEmpty()` 显示 PulsingDots），但**消息本地化（一期）后缓存秒开** → 加载太快 → PulsingDots 一闪而过不可见 → 用户感知"过渡没了"。修复（ec875ff7）：ChatScreen 加 `showLoadingTransition` 状态 + `MIN_LOADING_VISIBLE_MS=400`——即使消息立即到达，PulsingDots 也至少显示 400ms 再消失（仅首次进入且内容未加载完时显示；返回已有会话不显示；不遮挡内容/不拦截触摸，区别于已移除的"加载蒙版"）。验证：模拟器实证——进入"系统优化"/"生成对话标题"会话，150-250ms 加载指示器清晰可见 ✅；全量单测 1343 PASS ✅；i18n PASS ✅；⚠️ 真机复测待用户

- [x] **新增 D：会话列表滑动卡顿/掉帧——结案（3 轮模拟器验证 + 环境校准排除假回归；2026-08-19 用户指示模拟器优先）** `ui` `performance`
  - 问题：2026-08-10 真机排查——会话列表滑动"卡手"（拉伸动画中无法反向滑动）+ 掉帧（SSE 活跃时 90th 17ms / 99th 30ms+ / slowUI 40-50 次）
  - **根因 1（卡手）**：Android 12+ 默认 Stretch overscroll 拉伸动画拦截输入 → 全局禁用（`LocalOverscrollFactory provides null`，MainActivity）
  - **根因 2（掉帧）**：日志风暴——MessageDataDelegate combine 每 48ms 打 4 条 MsgDiag（每秒 ~80 条 logcat 写入）→ 彻底删除
  - **根因 3**：MessageEventHandler.handleMessageUpdated 每次 O(n) 全量 filter（1896 条消息仅用于诊断日志）→ 删除
  - **根因 4**：combine 每 48ms 冗余 O(n log n) 排序（数据源已有序）→ 移除
  - **根因 5**：SQLite IN 999 变量上限——大会话（1896 条）partsForMessages 抛 SQLiteException → 分块查询（≤900/块，新增回归测试）
  - **根因 6**：L3 REST 校验 limit=0 全量拉取（1989 条）→ 最新 50 条补漏
  - **根因 7**：上滑分页失效——reverseLayout 下 lastVisibleItemIndex 语义错误（恒等于底部 → 无限翻页/不触发）→ firstVisibleItemIndex + isScrollInProgress
  - **根因 8**：ANR——onCleared 主线程 runBlocking（已在 #35 修复）
  - 验证：模拟器实证——上滑翻页归档加载 ✅（`Loaded older: 20 msgs source=ARCHIVE`）；SQLite 错误 0 ✅；L3 refresh 50 msgs ✅；slowUI 26→0 ✅；全量单测 1343 PASS ✅；i18n PASS ✅
  - **2026-08-18 模拟器帧数据基线（软渲染参考，非真机结论）**：8 轮快速 fling（上下交替）gfxinfo——106 帧渲染 / jank 24.5%（legacy 82%）/ p50=30ms p90=40ms p99=69ms / 慢 UI 线程 19 / **无 >300ms 卡死帧、无 ANR**。模拟器 swiftshader 软渲染天花板明显（p50 30ms 即超 16.7ms 预算），8 项根因修复无劣化证据；真机基线仍待用户复测（数据 /tmp/verify-0818/49_gfxinfo.txt）
  - ⚠️ **待真机复测**：用户拿回手机后验证——滑动跟手度（无拉伸）/上滑翻页/掉帧（SSE 活跃时）
  - **2026-08-19 第三轮模拟器复测 + 结案（用户指示模拟器校验优先减少人工）**：① 功能全过——双向 fling ×8 到达两端、零 ANR/零 FATAL、深滚无卡死，overscroll 禁用代码在位（根因1）；② 帧数据三测（86%/74%/75% jank）高于 08-18 基线 → **环境校准实验**：同窗口原生系统设置应用滚动 jank 78.8%/p50=65——与 App（75%/61）同水位，App 略优 → 判定今日软渲染环境整体偏慢（宿主负载），**非 App 回归**；③ 滚动窗口 StrictMode 违规 0 条（排除 c3078b41 penaltyLog 干扰）。真机主观跟手度仍可选复测，不阻塞

- [x] **新增 E：上滑分页后底部最新消息消失——结案（3 轮模拟器实证，2026-08-19 用户指示模拟器优先）** `data` `session`
  - 问题：2026-08-10 用户实测——进入主对话界面后，上滑（加载更早消息）再下滑，**无法回到最底部**；"最底部的消息像是直接从整个主对话流中没有了一样"
  - **根因**：`MessageEventHandler.upsertAppendOnly`（APPEND_ONLY 合并策略）的 `_messages.update` 用 `incomingMsgs.map { existingById[newMsg.id] ?: newMsg }`——**把整个 _messages 替换为分页加载的"更早消息"**（incoming 只含更早，不含现有最新）→ **现有最新消息（底部）全部丢失**。二期 caf8019b（upsert 合并策略统一）引入；注释语义"仅补充缺失"与实现不符
  - **修复（ff192fd5）**：改为 `(existing + incomingMsgs).distinctBy { it.id }.sortedBy { it.time.created }`——existing 保留 + incoming 补充缺失 + 按 created 排序（combine 依赖写入路径有序）。同时修正 EventDispatcherTest 旧断言（固化 bug 的 size=1 → size=2），新增 2 回归测试（APPEND_ONLY 保留最新 + 分页场景）
  - 验证：模拟器实证——上滑分页 18 次（540 条更早消息）后下滑，底部最新消息仍保留 ✅；全量单测 1345 PASS ✅
  - **2026-08-18 模拟器复验受阻→修复后完整验证 ✅**：首轮受阻于新 P1（V2 长会话历史 0 条，已修 53cfea68）；修复后 501 条会话深翻（total 226+ 项、游标深入 8月12日历史）→ 回滚穿越全程消息连续渲染，Q174 跳转定位正常、新消息（01:57→10:20 区间）全部保留在流中——**底部最新消息不消失** ✅；Room 热表 501 条全量
  - ⚠️ **待真机复测**：上滑加载更早后下滑能回到最底部，最新消息不消失
  - **2026-08-19 第三轮模拟器复测 + 结案**：127 条会话深翻多轮（最新消息 → 劳务派遣 → 劳动合同 → 追缴 → 027 电话多段更早内容连续渲染，无断裂）→ 滑回绝对底部 → **最新消息（「模板已创建：劳动保障监察投诉书」）完整渲染在底**，服务器侧最新消息与 App 渲染一致（REST 双侧对照）。分页内容完整性 + 底部保留双验收达成

- [x] **新增 F：上滑自动加载更多失效——结案（3 轮模拟器实证，2026-08-19 用户指示模拟器优先）** `ui` `session`
  - 问题：2026-08-10 用户实测——主对话界面上滑"看似滑到顶"但不再加载更多（有更多内容却加载不出来）
  - **根因 1（不触发）**：`shouldPaginate` 依赖 `listState.isScrollInProgress`——用户滑到顶**停住**时 =false → 不触发。修复：改 `LaunchedEffect(hasOlderMessages, isLoadingOlder, autoLoadPaused)` + `snapshotFlow { listState.layoutInfo }` 持续监听——距顶 ≤8 即触发（无论是否滚动中）；`isLoadingOlder` 作 key → 加载完成重启监听 → 停在阈值内自动续载
  - **根因 2（死循环）**：NETWORK 分页游标不前进——热表最老不变（窗口外消息不落热表）+ use case 的 before 编码依赖 `messageCreatedAt(beforeId)`（游标消息不在热表 → null → before 不编码 → 服务器返回最新 → 游标 A→B 交替循环，模拟器实证每 ~100ms 拉同一批）。修复：Delegate 新增 `networkCursorId/Created` 独立游标 + use case 新增 `networkBeforeCreated` 参数（跳过归档直接 `CursorCodec.encode(id, created)`）
  - **根因 3（防风暴）**：自动续载无保护——连续失败会无限重试。修复：失败指数退避（500ms→8s）+ 3 次失败暂停（autoLoadPaused，UI 停止自动续载）+ 成功恢复清零
  - 日志：ChatPaging（auto-load triggered/backoff wait）+ loadOlder START/END/NETWORK/ARCHIVE/退避/暂停/恢复全链路
  - 验证：模拟器实证——停在顶部 8s 自动续载、游标 fe0c5862→fe0b9e6e→fe0b4438 前进、读尽 hasOlder=false 自动停止 ✅；全量单测 1350 PASS ✅（新增 5 回归测试：游标前进/网络游标跳过归档/退避/暂停/恢复）；i18n PASS ✅
  - **2026-08-18 模拟器复验：首轮断裂→修复后完整闭环 ✅**：首轮 NETWORK 首翻 0 条误判读尽（P1，根因 08-12 补丁旁路 08-16 根治，已修 53cfea68）；修复后 auto-load 全链工作——probe 触发（`topVisible=75 total=137`）→ loadOlder NETWORK 30 msgs → 游标链逐页前进（serverCursor 透传）→ 自动续载不停（hasOlder 恒 true 至读尽）、无重复无风暴（failures=0 paused=false 恒定）
  - ⚠️ **待真机复测**：上滑到顶停住 → 自动加载更早直到读尽，不重复加载、不风暴
  - **2026-08-19 第三轮模拟器复测 + 结案**：深翻全程内容连续加载渲染（无卡死/无重复/无风暴——零 ChatPaging 报错）；今日 127 条会话 < 初始加载窗口（hasOlder=false 不触发自动续载 = 正确行为，热表全量命中）；触发路径（probe/游标前进/退避/读尽停止）已有 08-10 首验 + 08-18 完整闭环（501 条会话游标深入历史）两轮实证。真机如遇长会话可选抽查，不阻塞

### 2026-08-10 系统审计批次（F 报告 P2 + 补丁债 + 模式）
来源：docs/research/audit-2026-08-10/F-FINAL-AUDIT-REPORT.md §3.3 + §6.2 补丁债根因修复 + §6.3 模式发现

- [x] **#44 sseJob + messageListState 双订阅同源（2x combine 重组）** `performance` `refactor`
  - 问题：每个 SSE 事件触发两个独立 combine 同时重组，CPU 翻倍。`MessageDataDelegate.kt:142-143`（messageListState）vs `319-333`（sseJob）观察相同 getMessagesFlow + getParts；1896 条消息场景每 48ms 2x O(n) 扫描
  - 修复：让 messageListState 同时暴露 rawMessages 字段，消除 sseJob 独立 combine
  - 工时：~0.5d | 难度：中 | 涉及：MessageDataDelegate.kt:142-143, 319-333
  - 来源：F §P2-1 / C S4 + A 间接 + E janky 贡献（3 路确认）
  - **2026-08-15 核实完成**：startObservingMessages 已改为 messageListState 投影（MessageDataDelegate.kt:320-342 注释明示 #44 消除双订阅），每 SSE 事件仅一次扫描

- [x] **#45 AppLogger 字符串拼接未门控** `refactor` `performance`
  - 问题：高频路径调用方未加 `if (BuildConfig.DEBUG)` 门控，字符串模板在传参前已拼接，即使 shouldPersist 返回 false 也已付出成本。`AppLogger.kt:154-175`；EventDispatcher:249、MessageEventHandler:575/255 无门控（对比 :157 有门控）
  - 修复：高频路径调用方强制 BuildConfig.DEBUG 门控；或 AppLogger 内部 lazy 拼接
  - 工时：~1h | 难度：低 | 涉及：AppLogger.kt + EventDispatcher/MessageEventHandler 调用点
  - 来源：F §P2-2 / C S8 + A 环节 F + D 模式 B（3 路确认）
  - **2026-08-11 完成**：EventDispatcher CommandExecuted（每命令事件）加 DEBUG 门控；扫描确认其余每事件级日志均已门控（219/231、SseClientV2 V2 event、MessageEventHandler 235）；#40 已清理 update lambda 内日志

- [x] **#46 combine 上游无 distinctUntilChanged 兜底** `refactor`
  - 问题：派生 flow 无 distinctUntilChanged 兜底，每次上游 emission（即使内容相同）触发 combine 重组。`ChatRepositoryImpl.kt:92-98, 461-462`
  - 修复：派生 flow 加 distinctUntilChanged
  - 工时：~30min | 难度：低 | 涉及：ChatRepositoryImpl.kt:92-98, 461-462
  - 来源：F §P2-3 / C S9
  - **2026-08-11 完成**：10 处派生 flow 加 distinctUntilChanged（getParts/getPermissionsFlow/getQuestionsFlow/getActiveToolProgress/getStepProgress/getCompactionState + 4×ForSession）；getMessagesFlow 不加（List equals O(n) 反效果）；全量单测通过

- [x] **#47 100ms ticker 叠加 48ms flush（流式 footer 重组 ~30 次/s）** `ui` `performance`
  - 问题：流式消息 footer 重组约 30 次/s（48ms flush ~20 + ticker ~10）。`MessageCardAssistant.kt:155-163`
  - 修复：移除 ticker 或与 flush 对齐单一更新源
  - 工时：~1h | 难度：中 | 涉及：MessageCardAssistant.kt:155-163
  - 来源：F §P2-4 / A 环节 G
  - **2026-08-11 完成**：ticker state 抽为 StreamingElapsedText 独立子 composable（重组仅限单个 Text，保留 0.1s 精度）；footer 重组 ~30→~10 次/s；全量单测通过

- [x] **#48 长会话无消息窗口裁剪——关闭不做（2026-08-19 用户决策）** `performance` `data`
  - 问题：LazyColumn 回收视图但数据层全量驻留；长会话（>2000 条）GC 压力 + combine 开销。`MessageDataDelegate.kt:179-189`；全库无窗口化
  - 修复：数据层窗口化（保留可视区 + 缓冲区，远端裁剪）
  - 工时：~1-2d | 难度：高 | 涉及：MessageDataDelegate.kt + 翻页管线
  - 来源：F §P2-5 / A 环节 H
  - **2026-08-11 调研结论（暂缓，保留记录）**：
    - **收益现状**：combine 开销大头已由 #44（双订阅合一）+ #46（distinctUntilChanged）+ #42（O(n) 归并）+ ChatMessage 缓存解决；剩余纯内存驻留（几十 MB）在现代设备影响有限
    - **结构性约束**：列表降序 + reverseLayout（index 0=最新在底部），固定窗口裁剪要么裁掉刚加载的更早消息、要么裁掉最新消息；自动分页触发 `totalItemsCount - firstVisibleItemIndex <= 8` 依赖列表总长——简单窗口化不可行，完整方案需 UI offset 模型（ChatMessageList 承重改造）
    - **业界调研**（2026-08-11，Telegram + AI Agent 工具）：
      - Telegram Android：**滚动翻历史不释放**——`dialogMessage` 全局单例按会话缓存全部已加载 MessageObject；内存可控靠"轻量元数据驻留（媒体字节外置 ImageLoader LruCache 字节上限 memoryClass/7）+ SQLite 分页 + messages_holes 空洞表"
      - AI Agent 类（OpenCode/Cline/Continue/LobeChat）：**文本消息业界主流 = 数据层全量驻留 + 渲染层虚拟化**（LobeChat react-virtuoso 最成熟但数据仍全量）；无"滚动懒加载文本"先例
      - OpenHands：唯一明确推进"数据层双向分页 + cap 内存"的案例（RFC #12705/#12707/#12616，进行中未完全落地）——印证完整窗口化复杂度确实高
      - 折叠展开（Cline tool 输出折叠）可作将来低风险优化（省渲染内存，不省数据层）
    - **决策**：暂缓（用户确认）。将来内存吃紧时的优先级建议：折叠展开（低风险）→ 非活跃会话裁剪（#48A 保守）→ OpenHands 式双向分页（高成本）
  - **2026-08-19 终局决策：关闭不做（用户拍板）**——理由：① 收益太低（性能大头已被 #44/#46/#42 拿走，剩余纯内存驻留几十 MB 现代设备无感，实测无 GC/卡顿投诉）；② 需要数据层整体重构（reverseLayout 降序列表的固定窗口裁剪在结构上必然裁错——要么裁掉刚加载的更早历史、要么裁掉最新消息，且破坏自动分页触发条件）；③ 风险太大（翻页/快速导航/流式追加三链路全部耦合消息列表全量语义，回归面 = 聊天核心）。如未来真机出现实际内存压力，再按上方三档优先级重新立项（届时是全新条目，带实测内存数据）

- [x] **#49 loadArchivedRange N+1 查询 + 写模式** `data` `performance`
  - 问题：每桶 1 查询 + 1 写；桶被字节上限切小时多次循环。`MessageStore.kt:264-292`
  - 修复：批量查询 + 批量写入
  - 工时：~0.5d | 难度：中 | 涉及：MessageStore.kt:264-292
  - 来源：F §P2-6 / B P2-1
  - **2026-08-11 完成**：一次查询 limit 桶 + 按需解码（原每桶 1 查询 + 1 touch）；touch 仅对实际解码桶

- [x] **#50 loadArchivedRange 解压整桶浪费** `data` `performance`
  - 问题：解压整个桶（最多 200 条/512KB + 桶内排序），只需 30 条。`MessageStore.kt:302-307`
  - 修复：桶内索引或分页解压；或减小桶粒度
  - 工时：~0.5d | 难度：中 | 涉及：MessageStore.kt:302-307
  - 来源：F §P2-7 / B P2-2
  - **2026-08-11 完成**：每桶 takeLast(need) 只取窗口内最新 need 条；结果显式升序（SQL 保证桶全在窗口内，filter 冗余）

- [x] **#51 messagesForSession 的 OR 子句可能放弃复合索引** `data` `performance`
  - 问题：`(:beforeId IS NULL OR id < :beforeId)` 可能放弃复合索引；ORDER BY 与索引不完全匹配。`MessageDao.kt:19-24`；热表限 1000 条缓解
  - 修复：拆分为两条查询（有/无 beforeId），或调整索引覆盖
  - 工时：~2h | 难度：中 | 涉及：MessageDao.kt:19-24
  - 来源：F §P2-8 / B P2-3
  - **2026-08-11 完成**：拆两条查询（messagesForSession 无条件 / messagesBefore 带 beforeId），MessageStore.loadRange 按游标分支；测试适配

- [x] **#52 SSE 双写高频落盘——2026-08-19 盘点结案（评估=无进一步收益，保持现状）** `data` `performance`
  - 问题：每 48ms flush → upsertMessages 3 查询 + 写 + 可能归档；活跃流式 ~20 次/s 落盘。`MessageEventHandler.kt:86-129, 194-204`；WAL 缓解
  - 修复：合并写入 / 降低 flush 频率 / 批量 upsert
  - 工时：~0.5d | 难度：中 | 涉及：MessageEventHandler.kt:86-129, 194-204
  - 来源：F §P2-9 / B P2-4
  - **2026-08-11 评估结论（#57 actor 已闭环）**：写频率受 48ms flush 铁律约束（不可降）；flushPendingDeltas 已按会话聚合（单会话每 48ms 仅 1 次 upsert）；actor 单写协程无堆积——无进一步收益，保持现状

- [x] **#53 过渡动画 400ms 反模式（补丁债，故意延迟加载态）** `ui` `refactor`
  - 问题：**当前实现是补丁**。`ChatScreen.kt:255-256, 433-447, 675-683`（MIN_LOADING_VISIBLE_MS=400）故意延迟显示加载态（反模式，欺骗用户感知）；魔法常量 400 无 A/B 依据。ec875ff7 引入（"新增 C"修复过渡动画丢失）
  - 根因修复（D TD-2）：移除常量；会话路由加 enterTransition/exitTransition；loading 指示器回归"仅在真正加载时显示"
  - 工时：~0.5d | 难度：中 | 涉及：ChatScreen.kt + 导航路由
  - 来源：F §P2-10 + D §2.2/TD-2
  - **2026-08-11 完成**：实测 NavHost 全局 fadeIn(tween(AppMotion.MEDIUM)) 已提供进入过渡（双过渡叠加）→ 移除 MIN_LOADING_VISIBLE_MS + showLoadingTransition，PulsingDots 回归纯加载态；ChatScreen 按编辑协议编译+提交+全量测试

- [x] **#54 草稿持久化补丁链（补丁债，防抖窗口内杀进程仍丢）** `data` `session`
  - 问题：**当前实现是补丁链**（0eaac6dc → e3ffeae7 双补丁）。`DraftInputDelegate.kt:127-145` 每次 updateDraftText launch+cancel job（高频输入大量 Job 创建销毁）；500ms 防抖窗口内 force-stop 杀进程仍丢；onCleared 用 viewModelScope（页面销毁时 scope 取消可能不执行）
  - 根因修复（D TD-3）：DraftRepository 暴露 `draftFlow: Flow<Draft>`，UI collectAsState + onValueChange 写 DataStore（原子合并写）；移除防抖 job；onCleared 用独立 scope（NonCancellable）
  - 工时：~0.5d | 难度：中 | 涉及：DraftRepository / DraftDataStore / DraftInputDelegate
  - 来源：F §P2-11 + D §2.3/TD-3 + C S6
  - **2026-08-11 完成**：updateDraftText 去防抖 Job 直写 DataStore（DataStore.edit 内部串行合并）+ saveDraft persistMutex 串行保序（防乱序覆盖）；测试适配直写语义（立即持久化/顺序保存/clear 不恢复）

- [x] **#55 L3 校验 limit=50 魔法常量（补丁债，长时间离线仍漏消息）** `data` `session`
  - 问题：**当前实现是补丁**。`SessionStateService.kt:34, 276-282`（REST_REFRESH_LIMIT=50）魔法常量无 A/B 依据；长时间离线陈旧窗口 >50 条仍丢消息。a7aec358 引入（limit=0→50）
  - 根因修复（D TD-4）：`lastSyncCursorPerSession` Map，L3 校验用 `before=encode(lastSyncCursor)` 增量同步；同步成功后推进游标
  - 工时：~0.5d | 难度：中 | 涉及：SessionStateService.kt
  - 来源：F §P2-12 + D §2.1/TD-4
  - **2026-08-15 根因修复完成**：V2 游标增量补漏（SessionStateService.latestMessageIdProvider + CursorCodec.encodeV2 NEWER 方向游标，EventDispatcher 接线）；V1 无 cursor 能力保持 limit=50（协议限制不更差）
  - **2026-08-11 调研结论（暂缓）**：~~V2 listMessages 不支持 before~~ **2026-08-11 实测修正**：V2 服务器**支持**分页（参数名 `cursor`，值=响应体 cursor.next，base64url {"id","order","direction"}；before 参数被忽略）；App 端 V2ApiClient 原缺失 cursor 参数（#56 联动已修复 dfdc116d）；增量游标方案仍待实测（#70）；现状 limit=50 + 进入会话 loadMessagesForSession 全量兜底已覆盖绝大多数场景

- [x] **#56 分页状态散落重构（TD-1，高严重度技术债）** `refactor` `session`
  - 问题：`MessagePaginationDelegate` 9 个可变状态成员（currentMessageLimit, archiveCursorCreated, networkCursorId, networkCursorCreated, _hasOlderMessages, _isLoadingOlder, autoLoadFailures, autoLoadPausedUntil, _autoLoadPaused），职责膨胀。D 报告标记"高严重度"——同一根因（游标抽象缺失）导致 3 次复发（d30a0d57/c5e0ea56）。与 AGENTS.md"SessionStateService 单一真相源"原则相悖
  - 修复（D TD-1）：抽 PaginationCursor sealed class + PaginationFSM（参照 SessionStateFSM 纯函数）；9 个状态 → ≤3 个；修复后可一并消除 #41（loadOlder 竞态）温床
  - 工时：~1-2d | 难度：高 | 涉及：MessagePaginationDelegate / MessagePaginationUseCase / MessageStore
  - 来源：F §P2-13 + D TD-1/模式 A + B §4 图
  - **2026-08-11 完成（6d3118a2）**：PaginationCursor sealed class（HotStart/Archive/Network）+ PaginationFSM 纯函数状态机；9 个散落成员 → 3 个（limit 配置 + FSM State + isLoadingOlder 互斥）；applyTransition 同步投影（synchronized 串行化）；PaginationFSMTest 12 + DelegateTest 18；⚠️ 修 stateIn(Eagerly) 常驻协程卡 runTest 问题（改同步投影）；全量单测 1465/0/0
  - **2026-08-11 模拟器实测联动**：发现 V2 网络翻页死循环（90s 250 次请求）——修复（dfdc116d）：V2ApiClient 透传服务器 cursor + PaginationCursor.Network.serverCursor + FSM.LoadSucceeded.nextCursor 透传链；回归测试 v2 network pagination passes server cursor；复测：循环终止（2 次即停）✅；另发现 #72（归档桶内分页缺陷）+ #73（首次网络 cursor 格式不兼容）

- [x] **#57 batchScope 无生命周期管理** `refactor`
  - 问题：App 级 SupervisorJob scope，App 退出时不取消；多会话同时活跃时 fire-and-forget 协程数无上限。`MessageEventHandler.kt:71, 194-204`
  - 修复：绑定生命周期（ViewModel/process scope）；或限流
  - 工时：~2h | 难度：中 | 涉及：MessageEventHandler.kt:71, 194-204
  - 来源：F §P2-14 / C S7
  - **2026-08-11 完成**：SSE 双写改 actor 模式（Channel BUFFERED 队列 + 单写协程串行处理，持久化协程数恒 1；背压排队不丢）

- [x] **#58 NetTrace 日志 hot path（删 MsgDiag 又加 NetTrace，配套补丁债）** `performance` `refactor`
  - 问题：b07b7ccc 删 MsgDiag 又加 NetTrace——hot path DEBUG 级日志模式不一致；实测 8 条/10s。D 模式 B（DIAG 残留反复）的典型案例
  - 修复（D TD-8）：采样 + 强制 BuildConfig.DEBUG 门控 + CI lint 禁止 DebugLogger 在 main 分支
  - 工时：~1h | 难度：低 | 涉及：NetTrace 日志点 + CI lint 配置
  - 来源：F §6.2 TD-8 + D 模式 B + E 实测
  - **2026-08-11 完成（确认现状已达标）**：NetTrace 2 处（SessionRepositoryImpl:221/223）均已带 BuildConfig.DEBUG 门控；8 条/10s 属 DEBUG 构建正常量。detekt CI lint 引入需新工具链，单独成项（见 #70 补充）

- [x] **#59 SQLite IN 分块下沉 DAO（TD-7，逻辑散落业务层）** `refactor` `data`
  - 问题：b07b7ccc 解决 Room IN 999 变量上限，但分块逻辑散落 MessageStore（业务层）而非 DAO 层
  - 修复（D TD-7）：下沉 DAO 层封装 @Query 内部分块
  - 工时：~0.5d | 难度：中 | 涉及：MessageDao + MessageStore
  - 来源：F §6.2 TD-7 + D §4 模式
  - **2026-08-11 完成**：MessageDao default 方法 partsForMessagesChunked（SQLITE_IN_VARIABLE_LIMIT=900 移入 DAO companion）；MessageStore 委托；分块回归测试改匿名 DAO 实现（断言 [900,600]）

- [x] **#60 catch(Exception) 吞 CancellationException 模式守护（TD-6 已修需持续守护）** `refactor`
  - 问题：协程反模式反复出现（≥2 次）。TD-6 已被 61e4107a 修复（先重抛 CancellationException），但模式需持续守护防止复发
  - 修复（D 模式 C）：safeCatch 工具函数（先重抛 CancellationException）+ detekt SwallowedException 规则
  - 工时：~2h | 难度：低 | 涉及：detekt 配置 + safeCatch 工具
  - 来源：F §6.2 TD-6 + §6.3 模式 C
  - **2026-08-11 完成（工具落地）**：SafeCatch.kt（suspend safeCatch：CancellationException 重抛传播）+ SafeCatchTest 3 用例；DraftDataStore 3 处典型模式迁移示范；剩余 41 文件 123 处逐步迁移（登记 #70）

- [x] **#61 多 commit 打包修复（流程改进，降低可审计性）** `refactor`
  - 问题：一个 commit 打包多项修复（b07b7ccc/1beb846b/16c7a15c/c5e0ea56），降低可审计性。D §4 模式 E
  - 修复（D 模式 E）：fix commit 一事一 commit；PR review 检查打包项
  - 工时：流程改进 | 难度：低 | 涉及：提交流程规范
  - 来源：F §6.3 模式 E
  - **2026-08-15 完成**：后续 fix 提交均一事一 commit（#136/#134/#133/#135/#137/#55 各自独立 commit，可审计性达标）

- [x] **#62 Ktor Client HTTP 引擎日志量偏大（实测 90 条/10s，当前最大日志源）** `refactor` `performance`
  - 问题：2026-08-10 模拟器复测（#39 修复后）发现——应用诊断日志已降至 20 条/10s，但 Ktor Client HTTP 引擎日志仍 90 条/10s（响应头/请求元数据逐条打印），成为当前最大日志源。证据：docs/research/audit-2026-08-10/metrics/R39-stream-10s.log
  - 修复：调低 Ktor Client 日志级别（LogLevel.HEADERS → NONE/仅错误）或改 INFO 级别过滤；保留请求失败时的错误日志
  - 工时：~0.5h | 难度：低 | 涉及：Ktor HttpClient 配置
  - 来源：R-revalidation.md §发现的问题 1
  - **2026-08-11 完成**：LogLevel.HEADERS → INFO（只保留请求方法/URL + 状态行）；release 保持 NONE

- [x] **#63 SseClient 256KB 单行边界截断超长 SSE 帧** `sse`
  - 问题：2026-08-10 功能回归走查发现（预有问题，非回归）——流式期间 logcat 出现 `E SseClient: SSE line exceeds 262144 bytes, aborting read` ~14 次，单行超 256KB 即 abort 读取；实测流式均最终完成，但超长单帧（超大 code block/token 批次）存在被截断风险
  - 证据：docs/research/audit-2026-08-10/RG-regression.md
  - 修复：评估提高上限（512KB/1MB）或改分片读取（按事件边界重组）；需验证内存影响
  - **2026-08-10 完成**：readRawLineBytes 超长行行为改为**丢弃该行并继续读下一行**（不再 abort 读循环触发断连重连——原实现超大 payload 批次会造成无谓断连与丢帧窗口）；单行上限 256KB→512KB（与事件级 1MB 上限配合）；内部循环跳过，调用方零感知；catch 保留部分行容错语义。R4 验证：流式完整 ✅、E 级 abort 日志 0 条 ✅、crash 空 ✅

- [x] **#64 超长消息会话手动滚动失效（fling/swipe/PAGE_UP 全无效）** `ui` `performance`
  - 问题：2026-08-10 第二批回归（R2）发现——进入"最后一条消息为超长内容（代码块+flowchart）"的会话后，fling/swipe/PAGE_UP 滚动疑似全失效（截图哈希相同）
  - 证据：docs/research/audit-2026-08-10/R2-regression.md + metrics/R2-10a/b、R2-12a/b
  - **2026-08-10 关闭：误判（非 bug）**。系统性二分排查（D64-bisect：CURRENT/NO42/NO43 三 APK）+ 决定性对照（D64-conclusive：4c416fb1 完整旧版）证明：
    - 根因 = 测试方法学缺陷——进入会话 auto-scroll 到底后，在**底部边界**测"上滑看更下方"（无内容可滚）→ bounds 零变化被误读为滚动失效
    - CURRENT（含 #40-#43 全部改动）**双向滚动完全正常**：下滑 10 个历史节点滚入、上滑回底部正常，与旧版行为一致 → #41/#42/#43 全部排除
    - 教训已写入 docs/regression-guide.md §3.8：滚动类验证必须**双向测试 + 避开边界**；logcat 抓取须按 PID/tag 过滤（D64 首次 logcat 全为 input 噪音属无效采集）
  - 证据（排查）：docs/research/audit-2026-08-10/D64-investigation.md、D64-bisect.md、D64-conclusive.md + metrics/D64-*

- [x] **#65 Back 退出时 JobCancellationException 被记为 ERROR 级（日志噪声）** `logging`
  - 问题：2026-08-10 #35 复现排查（D35）发现——Back 退出会话取消分页加载时，JobCancellationException 以 ERROR 级写入（52 条/55 轮），属预期异步行为非错误，污染诊断日志（应用内 Diagnostics + logcat）
  - 修复：取消异常（CancellationException 类）统一降级 INFO/DEBUG 或过滤；需确认 catch 点（分页加载协程取消处理）
  - 工时：~0.5h | 难度：低 | 涉及：MessagePaginationDelegate / 日志写入点
  - 证据：docs/research/audit-2026-08-10/D35-investigation.md + metrics/D35-log-*
  - **2026-08-10 完成**：8 文件 35 处 catch + 1 onFailure 统一修复——协程上下文取消异常重新抛出（throw e）、非协程/onFailure 过滤不记录；实测源头 MessageDataDelegate 3 处 + ChatViewModel 7 处等；编译 ✅ 相关单测 ✅；无行为变更

- [x] **#66 其他屏幕同类取消异常日志模式（未触发 Back 路径）** `logging`
  - 问题：2026-08-10 #65 修复时扫描发现——SessionListViewModel(9 处)、ServerSettingsViewModel(10 处)、ServerTerminalWorkspace(11 处)、FileViewerViewModel、WorkspaceViewModel、PtyToTermlibAdapter 存在同类 `catch (e: Exception) { AppLogger.e }` 模式，退出**对应屏幕**时同样会喷取消异常 ERROR（当前场景未触发）
  - 修复：同 #65 模式统一处理（协程上下文 throw、非协程过滤）
  - 工时：~1h | 难度：低 | 涉及：上述 6 文件
  - **2026-08-10 完成**：5 文件 23 处统一修复（SessionListViewModel 7 / ServerSettingsViewModel 10 / ServerTerminalWorkspace 2 / PtyToTermlibAdapter 3 / FileViewerViewModel 1 onFailure）；WorkspaceViewModel 无需修改（onFailure 无 AppLogger.e）；顺带修正 PtyToTermlibAdapter line 187 注释与代码不一致（注释声明取消异常传播但 catch 吞掉 → 按注释意图补 throw）；未动 AppLogger.w 级与无日志 onFailure；编译 ✅ 全量单测 ✅

### 2026-08-11 模拟器实测批次（#56 联动发现）

- [x] **#72 归档桶内分页缺陷（桶级游标 vs 消息级游标，桶内剩余消息永久读不出）** `data` `performance`
  - 问题：2026-08-11 #56 复测（模拟器，归档 88 条/1 桶 + 热表 30 条）发现——`MessageStore.loadArchivedRange`（MessageStore.kt:269-300）按 `bucketEnd < beforeCreated` 查桶（桶级比较），但游标推进到**消息级** created；第 2 次翻页用消息级 created 查桶 → 桶 bucketEnd > 游标 → 判读尽 → **桶内剩余 58 条永久读不出**（数据证据：翻页只释放了 30/88 条归档）
  - 修复：游标推进到**桶边界**（bucketEnd）而非桶内消息 created；或 loadArchivedRange 支持桶内消息级游标（beforeCreated 内再过滤桶内消息）
  - 工时：~0.5d | 难度：中 | 涉及：MessageStore.loadArchivedRange + MessagePaginationDelegate 游标推进（PaginationFSM.Archive）
  - 来源：模拟器实测（#56 复测报告）

- [x] **#73 首次网络翻页 cursor 格式不兼容（CursorCodec {"id","time"} vs 服务器 {"id","order","direction"}）** `data` `sse`
  - 问题：2026-08-11 #56 复测发现——首次网络翻页（无 serverCursor）回落 CursorCodec 格式，V2 服务器**返回 0 条**（非注释预期的"忽略返回最新"）；服务器 195 条消息中更早的 ~77 条未被加载（热表 30 + 归档 88 = 118，服务器 195 → 差 77 条读不到）
  - 修复：进入会话时保存服务器首次响应的 cursor.next（loadMessagesForSession 的 MessagePage.nextCursor）作为首翻游标；或首次网络翻页不带 cursor（拿最新 30 条 + cursor.next）建立边界后再透传
  - 工时：~0.5d | 难度：中 | 涉及：MessagePaginationUseCase + MessagePaginationDelegate
  - 来源：模拟器实测（#56 复测报告）

- [x] **#74 V2 SSE 连接不稳定（Software caused connection abort 反复断连）** `sse` `stability`
  - 问题：2026-08-11 Diagnostics 持久化日志（logs 表）显示 16:21-16:33 期间 3 次 `[TestServer] SSE connection failed: Software caused connection abort` + `SSE stream error`——App SSE 连接反复断连；断连窗口内的 admitted/step 事件丢失 → 用户消息/流式更新延迟（"刷新才显示"的深层关联因素之一）；本次启动（17:03，新 APK）后未复发，但断连重连机制无日志记录断连原因/重连间隔
  - 修复方向：SseConnectionManager 记录断连原因 + 重连间隔日志；区分服务器主动断开（正常）/网络异常；断连期间消息播种兜底（REST 增量）
  - 工时：~0.5d | 难度：中 | 涉及：SseConnectionManager / SseClientV2

- [x] **#75 V2 session.instructions.updated 解析失败（data 为数组的事件类型解析缺口）** `sse` `compat`
  - 问题：2026-08-11 Diagnostics 日志 5 次 `V2 parse error: session.instructions.updated`（15:42-16:07）——parseV2Event 对 `data` 为数组的事件回退顶层字段路径，但 instructions.updated 顶层只有 metadata（无 type 所需字段）→ 后续解析抛异常被记为 ERROR；同时 `session.created` 解析失败（16:03，Kotlin reflection 序列化异常）
  - 修复方向：instructions.updated 显式处理（metadata 提取或忽略）；session.created 序列化调查（Kotlin reflection 异常——可能与 Json 配置/多态有关）
  - 工时：~0.5d | 难度：低 | 涉及：V2SseMapper / SseClientV2.parseV2Event

- [x] **#82 跨页跳转 loadAround 后最新消息丢失（UI 与服务器不同步）** `data` `sse`
  - 问题：2026-08-13 跳转定位全面验证（模拟器）发现——发送消息（11:13 hello，服务器端确认存在：`GET /api/session/{id}/message` 最后一条 assistant 回复 11:15，会话 updated=11:13）后执行跨页跳转（Q5 → loadAround older=30 newer=30）→ 跳转完成后滚回列表最新位置，UI 仅显示 10:55 的消息（hello 及回复消失）——客户端内存/数据库窗口与服务器不一致；SSE 连接正常（V2 event 持续收到，含其他会话事件）
  - 影响：跨页跳转（loadAround 重载窗口）后最新消息可能丢失显示——用户看不到刚发的消息/回复（重启应用或重新进入会话可能恢复）；与 #76 冷启动 seed 顺序问题同属"窗口/归并"类
  - **2026-08-13 修复（根因 + 代码完成）**：与 #76 同类——`loadAroundFromLocal` 的 older（`messagesBefore` 查询 `ORDER BY created DESC`——降序）与 newer（ASC）混合后破坏 `mergeSortedMessages` 升序前提（MessageEventHandlerMergeSortedTest 声明的合法输入约束）→ 归并游标错乱 → 内存热视图丢消息。修复：loadAround 两分支（本地/服务器）合并前统一 `sortedBy { it.info.time.created }` 升序化（commit 3cb55ad8）。**验证状态**：assembleDevDebug 编译通过；单测受 replicant 环境 flavor 歧义限制未本地跑；模拟器（无 DISPLAY）无法行为复测——待环境恢复补跑单测 + 模拟器复现（跨页跳转 → 滚到底 → 最新消息在）
  - 工时：~0.5d | 难度：中 | 涉及：MessagePaginationDelegate（loadAround 两分支）
  - 来源：2026-08-13 跳转定位全面验证（模拟器，dev 最新代码）

- [x] **#76 冷启动 seed 消息顺序降序 vs mergeSortedMessages 升序前提（REST refresh 丢本地独有消息）** `data` `bug`
  - 问题：2026-08-11 synthetic 卡片实测发现——`MessageDao.observeMessages` 返回 `ORDER BY created DESC`（降序），而 `ChatRepositoryImpl.getMessagesFlow` 冷启动 seed 直接喂给 `upsertMessages(APPEND_ONLY)` → `mergeSortedMessages` 两路归并**前提要求升序**（MessageEventHandler.kt:408-410）→ 合并结果乱序/异常；随后 L3 REST refresh（REST_AUTHORITY）再次用降序 existing 归并 → **服务器上不存在的本地独有消息（如本地注入/服务器已删除）被丢弃**（实测：seed 14 条 → REST refresh 后 UI 仅 12 条，2 条注入 synthetic 消失）
  - 影响：低概率但真实——本地缓存与服务器不一致（服务器删除/回滚、本地注入）时消息丢失；日常场景（服务器权威数据）被掩盖
  - 修复：seed 前 `sortedBy { it.time.created }` 升序化（或 MessageDao 提供升序查询）；合并后断言有序
  - 工时：~0.5h | 难度：低 | 涉及：ChatRepositoryImpl.getMessagesFlow（seed 路径）
  - 来源：2026-08-11 synthetic 卡片实测
  - **2026-08-11 完成**：ChatRepositoryImpl.getMessagesFlow seed 前升序化（2e326ff1）；实测数据库 completed 全量持久化、UI 正常

### 2026-08-12 菜单走查批次（fork/share 实测发现）

- [x] **#77 fork 请求 400 被吞 → 空 id 幽灵会话（客户端已修，服务器待上游）** `session` `bug`
  - 问题：2026-08-12 菜单走查（模拟器）发现——点 Fork session 后服务器实际返回 **400 Bad Request**，但 `V2ApiClient.forkSession` 不检查响应状态 → 错误体被 `flexibleObject` 解析为空对象 → `Session.id=""` → 导航进空 id"幽灵会话"，后续操作（Share 等）打到 `/api/session/` 列表端点 → unwrap 崩溃（"Failed to share session"）
  - 服务器侧：fork 端点 `handle("fork")` + `handleRaw("fork")` 同路径注册冲突——curl 实测任何请求方式（JSON `{}` / 空 body / text/plain / multipart）均 400/415（"Missing key at [\"boundary\"]" / "Expected object, got undefined"）
  - 客户端修复（3211e95c 之后补丁）：forkSession 检查 `response.status.isSuccess()`，非 2xx 抛 `IllegalStateException` → UI 显示 "Failed to fork session" Snackbar，不再进入幽灵会话（模拟器验证 PASS）
  - 待办：服务器修复 fork 端点后（handle/handleRaw 冲突），App fork 即可正常；**1.0.0 前应复测 fork 全流程**
  - 工时：~0.5h | 难度：低 | 涉及：V2ApiClient.forkSession

- [x] **#78 V2 下 Share session 永远失败（服务器无 share 端点，UI 提示"Failed to share session"）** `session` `compat`
  - 问题：2026-08-12 菜单走查（模拟器）发现——V2 服务器**无 share 端点**（V2ApiClient.shareSession 注释 no-op getSession），且 `V2SessionMapper.toSession` 不映射 share 字段 → `session.share?.url` 恒为 null → Snackbar "Failed to share session"
  - 修复方向：V2 连接下隐藏 Share 菜单项（需将 apiVersion 传入 ChatTopBar）；或服务器提供 share 功能后适配
  - 工时：~0.5h | 难度：低 | 涉及：ChatTopBar / SessionActionsDelegate
  - **2026-08-12 完成**：V2 下隐藏 Share/Unshare 菜单项——ChatViewModel 暴露 serverApiVersion StateFlow；ChatTopBar 加 isShareSupported 参数包裹 Share 菜单组；ChatScreen 按 `serverApiVersion != ApiVersion.V2` 传参（V1 保留 Share）。注意：运行中的 V2 服务器（旧版）share 端点 404；新版 opencode 源码已有 `POST/DELETE /api/session/:id/share` 端点，且新版 Session.Info **无 share 字段**（分享链接由服务器内部维护）——服务器升级后需重新适配 share 协议再恢复菜单

---

### 2026-08-14 问题模块分支审计批次（question-module-audit-2026-08-14.md）
来源：docs/research/question-module-audit-2026-08-14.md（46 分支：43 ✅ / 1 ❌ / 2 ⚠️，静态审查 + 单测审查）

- [x] **#125 多选模式下自定义答案提交后无法取消（唯一中等问题）** `ui` `question`
  - 问题：多选（multiple=true）问题中，用户通过输入框提交自定义答案后无法直接取消/删除——③态（行+Edit+✔）无取消按钮；修改态清空输入框后飞机按钮 disabled（`editText.isNotBlank()` 为 false）无法提交空值清除；**间接副作用**：修改态输入已有选项标签（如 "A"）会触发 onOptionClick("C") + onOptionClick("A")，后者因 "A" 已在 selected 中而 toggle off → 选项 A 被意外取消
  - 修复（2026-08-14 commit 77074c05）：③态新增 ✕ 删除按钮（toggle off 自定义值）；修改态提交防副作用（新值匹配已有选项标签时只移除旧自定义值，不 toggle on）；②态提交同样防副作用
  - 验证：代码检查（D1）✅；模拟器 E2E 实测待执行（需 agent 发多选问题，见 docs/dialogue-e2e-test-runbook.md）
  - 工时：~1h | 难度：中 | 涉及：QuestionPartContent.kt | 优先级：P1

- [x] **#126 4+ 问题时远页自定义草稿丢失** `ui` `question`
  - 修复（2026-08-14 commit 77074c05）：customDraft 提升到 QuestionPagerView 层 mutableStateMapOf<Int,String> 按页存取
  - 问题：多问题（4+）场景，Q1 输入未提交草稿后翻到 Q4 再翻回 Q1——草稿丢失（customDraft 重置为空）
  - 根因：QuestionPartContent.kt:326——customDraft 用无 key remember，状态绑定页面级 composition；HorizontalPager beyondViewportPageCount=1，距离超 1 页的页面销毁后重新组合
  - 方案：customDraft 按 pageIndex 提升到 QuestionCard 顶层（如 Map<Int, String>）；或增大 beyondViewportPageCount（内存换体验）
  - 工时：~0.5h | 难度：低 | 涉及：QuestionPartContent.kt/QuestionCard.kt | 优先级：P2

- [x] **#127 单选/多选 toggle 边界保护不对称** `ui` `question`
  - 修复（2026-08-14 commit 77074c05）：单选分支补 pageIndex 越界保护（与多选对称）
  - 问题：onOptionClick 单选分支（QuestionCard.kt:171）无 pageIndex 越界保护，多选分支（:174）有 `pageIndex < size` 保护——代码健壮性不一致（实际不触发，pageIndex 来自 pager）
  - 方案：单选分支补同款越界保护
  - 工时：~5min | 难度：低 | 涉及：QuestionCard.kt | 优先级：P2

---

### 2026-08-13 V1/V2 版本探测修复批次（反馈者复现 + 系统性调研）

- [x] **#83 V1 1.18.18 过渡形态被误判 V2 → 会话界面 JSON 解析崩溃（HTML fallback）** `data` `bug`
  - 问题：反馈者 opencode V1 1.18.18（V1/V2 双套端点过渡形态）——`GET /api/health` 返回 200 `{"healthy":true}`（无 version 字段）→ `ApiVersionDetector.tryV2` 只看 healthy → **误判 V2** → App 用 V2ApiClient 请求不存在的 `/api/*` 路径（rename/shell/todo/mcp/config/vcs/project/fork/import 等实测 16+ 端点）→ 服务器 SPA fallback 返回 `<!doctype html>`（HTTP 200）→ `parseToJsonElement` 崩溃：`Unexpected JSON token at offset 11: Expected EOF after parsing, but had h instead`（与反馈者截图完全一致，offset 11 = `<!doctype html>` 的 `h`）
  - 实测证据（本机 1.18.18 隔离运行）：V2 路径正常 JSON 的仅 16 个（session CRUD/message/active/provider/model/agent/command/skill/permission/question/location/fs/pty），**返回 HTML 的 16+ 个**（background/rename/shell/command/children/todo/mcp/config/vcs×3/project×2/service/stop/fork/import）
  - 修复（三层防御）：
    1. **根因**：ApiVersionDetector.tryV2 增加**版本交叉验证**——`ApiVersion.fromVersionString(version) == V2` 才判 V2（1.18.18 version 缺失或 1.x → 回退 tryV1 → `/global/health` 返回 version=1.18.18 → 正确判 V1；V1 路径在 1.18.18 上全部存在，实测通过）
    2. **content-type 防御**：tryV2/tryV1 校验响应 content-type 必须为 JSON（HTML 页面不算健康）
    3. **解析层防御**：V2ApiClient.parseRoot + V2ResponseWrapper.flexibleList/flexibleObject 检测 HTML 特征 → 抛 `NonJsonResponseException`（可读信息 + AppLogger.e），不再裸抛 JsonDecodingException
  - **2026-08-13 补充修复（反向回归）**：真实 V2 服务器版本号为 `0.0.0-next-17403`（npm next 预发布，major=0）→ `fromVersionString` 解析为 V1 → 修复 1 会把真 V2 误判 V1！补充判定规则：**version 解析为 2.x 或响应含 pid 字段（V2 特征，实测必有）→ V2**；version 缺失且无 pid → 过渡形态 → V1。新增测试 2 个（V2 预发布 pid 识别、V2 无 version 有 pid），全量 11 个探测测试通过
  - 测试：ApiVersionDetectorTest +5（版本矛盾/无 version/HTML/content-type/不可解析）；V2MappersTest +6（HTML 防御×2 + flexible 正常×4）；V2ApiClientTest +1（HTML → NonJsonResponseException）——全量 1562 单测通过
  - 工时：~0.5d | 难度：中 | 涉及：ApiVersionDetector、V2ApiClient、V2Mappers、NonJsonResponseException（新建）
  - 来源：反馈者复现 + 本机 1.18.18 隔离实测 + 双 deep-explore 调研
  - **验证状态**：编译 ✅ 单测 ✅；模拟器走查待执行（V1 连接 → 会话界面无报错）

- [x] **#84 V1/V2 功能差异适配清单——结案（全清单适配完毕；V2 OAuth 多步流关闭不做，2026-08-19 用户决策）** `compat` `refactor`
  - 问题：深度调研确认 V1(1.18.x) 与 V2(2.x) 是**三重断裂**（路径前缀 / 核心机制 / SSE 格式），客户端需按 apiVersion 区别处理以下功能（详见 docs/v1-v2-differences.md）：
    - **发送消息**：V1 `POST /session/{id}/prompt_async`（204 fire-and-forget）vs V2 `POST /api/session/{id}/prompt`（200 返回 Inbox 条目）——App 已适配 [确认]
    - **中断**：V1 `abort`（boolean）vs V2 `interrupt`（204 + `?continue=true`）——App 已适配 [确认]
    - **后台任务**：V1 仅实验性 `/experimental/session/{id}/background`（需 flag）vs V2 正式 `/api/session/{id}/background`（204）——**V1 下后台化入口应隐藏或降级** [待办]
    - **配置**：V1 `GET/PATCH /config` 可写 vs V2 `GET /api/config` **只读**（无 PATCH）——App 配置编辑在 V2 应禁用 [待办]
    - **Todo**：V1 `GET /session/{id}/todo` vs V2 **移除**（form/question 替代）——V2 下 Todo 入口应隐藏 [待办]
    - **Provider 认证**：V1 oauth authorize/callback 两步 vs V2 integration connect 多步异步——设置页认证流程 [→ V2 OAuth 部分关闭不做，见下方终局决策]
    - **Revert**：V1 直接 revert/unrevert vs V2 staged（stage/commit/clear）——App 回退功能 [待办]
    - **SSE 格式**：V1 `{id,type,properties}` vs V2 `{id,event,data}`（data 二次 JSON）——App 已适配 [确认]
    - **TUI 控制**：V1 13 个 `/tui/*` 端点 V2 移除——App 无依赖 [确认]
    - **session/status**：V1 `GET /session/status` vs V2 无直接等价（active 替代）——App V2 用 activeSessions [确认]
    - **配置格式**：V1 `config.json` 可读 vs V2 只读 `opencode.json(c)`；mcp 配置 `mcp.{name}` vs `mcp.servers.{name}`；权限模型工具分组 vs 有序数组——服务端侧差异，客户端只读展示 [评估中]
  - 工时：需逐项评估 | 难度：中 | 涉及：多处 UI + API 客户端
  - 来源：2026-08-13 网络 deep-explore（92% 充分度）+ 本地 1.18.18 实测
  - **2026-08-19 盘点核实（代码证据）**：① V1 后台化降级 → 已随 #85 完成（Background 菜单 V1 隐藏）；② V2 配置只读 → 已随 #85 完成（V2 PATCH guard）；③ Revert staged → **已实现**（V2ApiClient:954 `revert/stage` + :963 `revert/clear`，含 commit 后 revert 立即清空的时间差注释——只 stage 策略）；④ Todo → #85 确认无独立 UI 入口无需处理；⑤ SSE 格式/中断/发送/session status → 已适配。**仅剩 Provider 认证流程**（V1 oauth 两步 vs V2 integration connect 多步异步）[待办]——条目收敛为该单项
  - **2026-08-19 Provider 认证落地（e0bc781c）**：key 连接修复——curl 契约实测 beta-17595 的 PATCH /api/credential 要求 **label 必填**，App 原发 {type,key} 恒 400（**API k
  - **2026-08-19 终局决策：V2 OAuth 多步流关闭不做（用户拍板）**——理由：① **零在用场景**：服务器 credential 库实测 3 条凭据全部为 API key 型（zhipuai-coding-plan/deepseek/opencode-go），无任何 OAuth 型 Provider 在用；② **完整替代路径**：OAuth Provider 在服务器主机跑 `opencode auth login` 即完成，App 直连现成连接——App 内 OAuth 只省'碰一次服务器终端'；③ **验证不可完整**：真实 OAuth 回调需 Provider 账号，模拟器 E2E 无法闭环；④ 不增加任何新能力（V1 时代就有的功能，V2 只是协议变形）。触发重启条件：真要用 OAuth-only Provider（如 GitHub Copilot）且不便碰服务器时重新立项（0.5-1d：V2ApiClient 换 integration connect 端点 + 等待/轮询 UI + 设备码 chip 串联）ey 连接在此部署版完全不可用**，盘点发现的隐藏断裂）；补 label="oc-beacon" 后 204。单测 +1（body 断言）+ 全量绿；探针凭证已 DELETE 清理。**残留：V2 OAuth 多步流未实现**（getProviderAuthMethods/authorizeProviderOauth 返回空）——API 全貌已摸清：194 集成中 4 个支持 OAuth（github-copilot/openai/opencode/xai），流程 = POST connect/oauth {methodID} → attemptID+URL → 用户授权 → POST .../complete {code}；属独立功能开发（~0.5-1d），非适配缺口，待用户需要时实施

- [x] **#85 V1 连接下应隐藏/降级的功能 UI（根据 #84 清单落地）** `ui` `compat`
  - **2026-08-13 用户验收 ✅**：V1 下任务面板入口/Running/History 隐藏正常；V2 Todo/配置编辑降级确认
  - 问题：#84 调研结论中部分功能在 V1 下不可用/无意义，但当前 UI 未按 apiVersion 区分（参考 #78 已实现的 V2 隐藏 Share 模式）
  - 待落地清单（V1 下）：任务面板入口（V1 无正式后台系统）[评估中]；V2 下：Todo 入口（V2 移除 todo）、配置编辑（V2 只读）
  - 工时：~0.5d | 难度：低 | 涉及：ChatTopBar / 工具栏 / 设置页
  - 来源：#84 调研产出
  - **2026-08-13 完成**：
    1. **Background 菜单 V1 隐藏** ✅——ChatTopBar 新增 `isBackgroundSupported` 参数，Background 菜单项包条件；ChatScreen 传 `serverApiVersion != V1`；模拟器验证：V1 菜单 6 项无 Background、V2 菜单 6 项有 Background、无崩溃
    2. **配置编辑 V2 只读 guard** ✅——ServerSettingsViewModel 新增 `serverApiVersion` 字段（init 读取）；`setProviderEnabled`/`updateConfigPatch` V2 下直接提示失败（实测 V2 PATCH /api/config → 404）；`connectProviderApi`/`completeProviderOauth` 成功后 V2 跳过 disabledProviders PATCH（本地乐观更新，Provider 连接主操作不受影响）
    3. **Todo 无需处理** ✅——补充走查确认 Todo 无独立 UI 入口（SSE 事件驱动渲染，`SseEvent.TodoUpdated`），非用户可触发
  - 单测 1564 全通过；待用户验收

- [x] **#86 V1 连接下抽屉不显示 API 版本号（V2 显示 API v2 · 版本，V1 仅 Connected）** `ui` `compat`
  - **2026-08-13 用户验收 ✅**：V1 抽屉显示 API v1 · 1.18.18，观感确认通过
  - 问题：2026-08-13 三轮走查发现——V2 服务器抽屉显示 `API v2 · 0.0.0-next-17403`，V1 服务器仅显示 `Connected` 无版本号。版本检测实际正确（logcat 证实 1.18.18），但用户无法从 UI 直观看到 V1 版本
  - 建议：抽屉中对 V1 也显示 `API v1 · 1.18.18`（数据已有：ServerConfig.serverVersion）
  - 工时：~0.5h | 难度：低 | 涉及：ServerCard/抽屉组件
  - 来源：2026-08-13 补充走查（B7 项观察）
  - **2026-08-13 完成**：ServerCard.kt 移除 `apiVersion != V1` 显示条件，V1/UNKNOWN 均显示版本徽章（颜色沿用 else 分支）；模拟器验证：V1 卡片显示 `API v1 · 1.18.18`、V2 显示 `API v2 · 0.0.0-next-17403`、logcat 判定正确、无崩溃——待用户验收

- [x] **#83 补充验证记录（2026-08-13 三轮模拟器走查全部通过）**
  - V1 走查（旧 APK）：`Detected V1 API (version=1.18.18)`；会话界面无 JSON 报错；发送/接收链路正常
  - V2 走查（新 APK）：`Detected V2 API (version=0.0.0-next-17403, pid 特征识别)`；200 会话/4 项目加载；发送→SSE 回复；Share 菜单隐藏（#78 生效）；无崩溃
  - 补充走查：V1 菜单含 Share（与 V2 隐藏对比成立）、Fork 成功无幽灵会话、重命名生效、新建会话成功、模型列表加载、设置页 logcat 证实 1.18.18、全程零 FATAL
  - 走查清单：docs/simulator-walkthrough-v1v2.md（执行记录已填）

- [x] **#87 V1 长会话压测发现：/message 轮询 JsonConvertException ×302（非致命）+ 回复偶发重复渲染** `data` `sse`
  - **2026-08-13 模拟器复验 ✅（Agent 代测）**：长会话无 JsonConvertException、无重复渲染（每条消息单气泡）；附注：listMessages 打开会话 2 秒内冗余调用 ~7 次 + V2 分页 before 游标返回 400 后回退重头拉取（不崩溃、浪费网络）→ 登记 #91
  - 问题：2026-08-13 V1 长会话 40 条消息压测（全部通过、零崩溃）发现两个非阻塞观察项：
    1. **JsonConvertException ×302（已修复）**：logcat 显示 App 以 **5 秒周期轮询** `GET /session/ses_0051ddbbdffed3UmOqzX8SamAC/message?limit=50`（该会话为压测 subagent 的服务器会话，**不存在于本地 V1 1.18.18 服务器**）→ 404 → 错误体 `{"name":"NotFoundError",...}`（对象）被按 `List` 解析 → JsonConvertException。根因：`V1ApiClient.listMessages`/`V2ApiClient.listMessages` **无状态码检查**（404 错误体直接当数组解析）。**修复（2026-08-13）**：两处 listMessages 非 2xx 返回空页 + AppLogger.w；新增 V1ApiClientTest 3 个（404/5xx/正常）；L2 stale 轮询源为压测环境外部会话（已删除会话的遗留轮询，非 App 常规路径）
    2. **回复内容偶发重复渲染（已修复）**：部分回复出现重复文本（如 "Got it. Message 1 received.Got it. Message 1 received."）——根因：**REST 快照 text part `id=""` vs SSE part `id="prt_xxx"`**（part ID 契约差异）→ `handleMessagePartUpdated` 按 id 找不到 → 新增第二条 part → 同消息两条文本 part。**修复（2026-08-13）**：空 id 的 Text part 按**内容级匹配**（相等/前缀）合并而非新增；新增 MessageEventHandlerTest 3 个（内容合并/更长替换/内容不同仍新增）
  - 验证：单测 1575 全通过；模拟器复测待执行（长会话重复渲染观察 + logcat 无 JsonConvertException）
  - 工时：~0.5d | 难度：中 | 涉及：V1ApiClient/V2ApiClient.listMessages、MessageEventHandler

- [x] **#88 目录浏览性能：每次导航 >500ms（V1/V2）+ V2 大目录 53 秒 ANR** `perf` `data`
  - **2026-08-13 用户验收 ✅**：目录浏览流畅（缓存秒开），.opencode ANR 消除（234ms），性能复测全通过
  - 问题：2026-08-13 用户反馈"各类目录点击卡卡的"→ 性能测试确认：OpenProjectDialog 目录浏览**每次前进导航 >500ms**（V1 一致 SLOW 506-763ms；V2 537-799ms + 极端 .opencode 目录 53 秒 ANR"not responding"）。会话列表目录树 toggle 正常（<50ms）
  - 根因（两处）：
    1. **ANR**：`FileRepositoryImpl.listDirectory` 无 `withContext(IO)`，OpenProjectDialog 的 LaunchedEffect 在 Main 调度器 → V2 大目录（node_modules）的 JSON decode + map 在主线程 → 阻塞 → ANR
    2. **500ms 感知延迟**：每次目录导航无缓存，模拟器→宿主机网络往返 ~500ms 固有延迟（items 0-1 个也 >500ms）
  - 修复（2026-08-13）：
    1. FileRepositoryImpl.listDirectory 包 `withContext(Dispatchers.IO)`（网络+解析移出主线程）
    2. DirectoryManager 增加 **30s 目录列表缓存**（ConcurrentHashMap：路径→{items, at}）——已浏览目录返回/重复浏览秒开（CACHE HIT <100ms）
    3. 保留性能监控日志（listDirectories >500ms warn、buildTreeNodes >50ms warn、CACHE HIT debug）
  - 验证：单测 1575 全通过；模拟器复测待执行（V1/V2 缓存命中 + .opencode ANR 消除）
  - 工时：~0.5d | 难度：中 | 涉及：FileRepositoryImpl、DirectoryManager、OpenProjectDialog 链路
  - 来源：2026-08-13 用户反馈 + 性能测试（V1/V2 全量数据）

- [x] **#89 内存泄漏修复批次：Singleton keyed 状态会话切换后不清理** `data` `refactor`
  - **2026-08-13 确认完成 ✅（Agent 代确认，用户授权）**：①目录窗口 30 轮开关内存增长减速趋平（5.3→4.1MB/10轮，GC 回收 14MB）；②缓存 LRU 生效（CACHE HIT 39/fetch 15）；③会话退出清理链路 logcat 铁证（releaseSessionData + clearForSession 精确清理 50/90 条）；④1575 单测全通过
  - 问题：2026-08-13 用户反馈模拟器长时间运行后系统卡死（宿主机 swap 15Gi 满）→ 排查发现 App 内多处 **@Singleton 持有按 sessionId/serverId keyed 的可变集合**，正常切换会话（非 SessionDeleted/SSE 断开）不触发清理 → 数据永驻内存：
    1. **DirectoryManager.dirCache**（目录浏览缓存）：只 put 不清理，浏览大量目录（含 node_modules 大列表）条目永驻 → 已修：上限 200 + 过期清理（近似 LRU）
    2. **MessageEventHandler._messages/_parts**（按 sessionId）：ChatViewModel.onCleared 不清理 → 已修：EventDispatcher.releaseSessionData + ChatViewModel.onCleared 调用
    3. **SessionEventHandler._sessionDiffs/_lastUserMessageTime**：无 clearForSession → 已补
    4. **ShellJobsStore._jobsBySession**：有 clearForSession 但无调用点 → 已接入 releaseSessionData（经 ShellJobsHandler 委托）
    5. **StreamingOwnershipRegistry.owners**：仅 SessionDeleted 释放 → 已接入 releaseSessionData
    6. **AppNotificationManager 去重缓存 ×3**（(server, session) keyed）：仅断开/用户取消通知清理 → 已补 clearForSession（ChatViewModel.onCleared 直调，避免 EventDispatcher↔AppNotificationManager Dagger 循环）
    7. **SessionEventHandler.locallyClearedReverts**：已补 clearForSession 清理（防御）
    8. **ChatRepositoryImpl.toolExpandedStates**（toolId keyed，仅 UI 展开状态）→ 登记低优先级（#90）
  - 修复（2026-08-13）：
    - EventDispatcher 新增 `releaseSessionData(sessionId)`：级联清理 sessionHandler/messageHandler/permissionHandler/questionHandler/miscHandler/sessionNextHandler/sessionStateService/ownershipRegistry/shellJobsHandler
    - ChatViewModel.onCleared 调用 releaseSessionData（runCatching 防异常）
    - SessionEventHandler 新增 clearForSession；ShellJobsHandler 新增 clearForSession 委托
    - DirectoryManager.dirCache 上限 200 + 过期清理
    - 测试构造更新：5 个 ChatViewModel 测试加 mockk eventDispatcher
  - 验证：编译 ✅ 单测 1575 全通过 ✅；模拟器长时间运行内存曲线待测（dumpsys meminfo）
  - 工时：~0.5d | 难度：中 | 涉及：EventDispatcher、ChatViewModel、SessionEventHandler、ShellJobsHandler、DirectoryManager
  - 来源：2026-08-13 用户反馈系统卡死 + 全局 Singleton keyed 状态扫描

- [x] **#90 ChatRepositoryImpl.toolExpandedStates 无上限（低优先级）** `refactor`
  - 问题：2026-08-13 全局 keyed 状态扫描发现——`ChatRepositoryImpl.toolExpandedStates`（ConcurrentHashMap<toolId, Boolean>）只增不减（工具卡片展开状态记忆），toolId 随消息/工具调用增长 → 长期使用后无界
  - 影响：低（单条 Boolean 值，千条工具调用才 KB 级）；且 UI 展开状态跨会话记忆有产品价值
  - 方案：定期清理已结束消息的 toolId（需按消息关联）或 LRU 上限（如 1000 条）
  - 工时：~0.5h | 难度：低 | 涉及：ChatRepositoryImpl
  - 来源：2026-08-13 全局 Singleton keyed 状态扫描（#89 附属）

- [x] **#91 listMessages 冗余调用 + V2 分页游标 400——2026-08-18 主体修复（去重 fcbffbb6 + 心跳 00fbdda3 组合），残留串行对 P3；2026-08-19 复验收 ✅** `data` `performance`
  - 问题：2026-08-13 #87 模拟器复验发现——打开会话后 2 秒内 listMessages 冗余调用 ~7 次；V2 分页 `before=eyJp...` 游标返回 400 Bad Request 后回退重头拉取。不崩溃但浪费网络（长会话/慢网络下明显）
  - **2026-08-18 模拟器重现（加重）→ 主体修复**：进入 501 条会话 22ms 内 8 次重复（同 cursor 精确成对）/ 20s 内 30 次。归因：多链并发（初始加载 + SSE 重连 backfill + L3 校验）+ 40s 断连循环持续触发 recover。**修复组合**：① 心跳修复（00fbdda3）消除空闲断连循环 → recover 触发从每 40s 降至仅会话进入一次；② 在途去重（fcbffbb6，SessionRepositoryImpl 同参并发共享单一请求 + 3 单测）消除真并发重复。修后：仅进入时一次 burst（~29 次/1.2s）其后 18s+ 零请求
  - **残留（P3）**：相邻串行对（首请求完成后 31ms 跟随者再发同参——去重窗口已关的边缘竞态，burst 内 ~10 对）；根治需上游三链协调（backfill/L3/初始加载），涉 SSE 状态链风险高暂缓；form/request 双调用同族待顺带
  - **2026-08-19 模拟器复验收 ✅**：501 条会话（ses_0115b9cc）进入 8s 窗口 30 次请求（并发初始化 burst，与 08-18 记录的 ~29 一致）→ **稳态 20s 零请求**（无断连循环、无冗余轮询）——修复效果保持。残留串行对维持 P3 暂缓（性价比低 + 风险高），主条目结案
  - 关联：可能与本条目 #73（V2 cursor 格式 {"id","order","direction"} vs 本地 CursorCodec {"id","time"}）同源——需先核对游标编解码
  - 工时：~1-2h | 难度：中 | 涉及：V1ApiClient/V2ApiClient.listMessages、分页管线
  - 来源：2026-08-13 综合验收（#87 复验附注）

- [x] **#92 session.tool.progress 事件未处理（工具实时进度缺口）** `sse` `ui`
  - 问题：2026-08-13 #71 数据正确性确认发现——日志反复 `W SessionNextEventHandler: Unhandled session.next event: session.tool.progress`；shell 生命周期（created/exited/deleted）与内联展示数据正确，但工具实时进度事件被忽略 → Tasks 面板无法显示进行中工具进度
  - 影响：中（工具调用长任务时用户看不到实时进度；任务完成仍正常显示）
  - 方案：SessionNextEventHandler 处理 tool.progress 事件 → 进度流接入 Task 面板/消息内联展示
  - 工时：~2h | 难度：中 | 涉及：SessionNextEventHandler、TaskDelegate/TaskSheet
  - 来源：2026-08-13 综合验收（#71 附注）

- [x] **#93 WebView 销毁三件套（C-1+H-1+H-2，审计 Critical+High 泄漏）** `crash` `leak`
  - 来源：docs/research/audit-2026-08-13-memory-perf/REPORT.md §4.1-4.2（基线 3bdd7990，2026-08-13 静态审计）
  - ✅ **2026-08-13 修复完成（c0c74a4c）**：WebViewScreen 加 DisposableEffect onDispose 完整销毁（stopLoading→about:blank→clearHistory→removeView→destroy）；ErrorPayloadContent 加 AndroidView onRelease（滚出视口即销毁）；RenderWebView 加 DisposableEffect 销毁 + lastHtml/lastJsCommand 去重（消除无条件整文档重载）。grep 验证三处销毁齐全 ✅
  - 问题（✅ 2026-08-13 Agent 代码验证确认）：
    1. `ui/screens/webview/WebViewScreen.kt:149-292` 全屏 WebView **从不 destroy()**——无 onRelease/DisposableEffect，每次进出导航累积一个渲染进程（10-100MB）+ Activity 引用；Basic Auth 明文凭据随闭包驻留（91-99 行）
    2. `ui/screens/chat/components/ErrorPayloadContent.kt:79-101` HTML 错误气泡 WebView **无 onRelease**——滚出 LazyColumn 视口不销毁
    3. `ui/screens/viewer/RenderWebView.kt:55-99` 渲染面板 WebView **永不销毁**——切 SOURCE↔RENDER 反复累积
  - 对比：同项目 CodeWebView.kt:202-215 / PdfViewer.kt:83-94 均有完整销毁序列，此三处是遗漏
  - 方案：`AndroidView(onRelease = { wv -> wv.stopLoading(); wv.loadUrl("about:blank"); wv.destroy() })` 或 DisposableEffect 销毁（照抄 CodeWebView 模式）；考虑 LeakCanary 集成（debug）
  - 工时：~0.5d | 难度：低 | 涉及：WebViewScreen/ErrorPayloadContent/RenderWebView
  - 优先级：**P0**（每次操作累积，OOM/LMK 风险）

- [x] **#94 图片解码降采样（H-3+M-9，审计 High/Medium 性能）** `performance` `crash`
  - 来源：audit-2026-08-13-memory-perf/REPORT.md §4.2 H-3 + §4.3 M-9
  - ✅ **2026-08-13 修复完成（c0c74a4c）**：ImagePreviewDialog 加 inJustDecodeBounds + inSampleSize 降采样（缩略图 256px ~750KB / 预览 2048px ~12MB）；MediaUtils 压缩前降采样解码 + JPEG RGB_565（省 50%）+ token 估算用原始尺寸保证准确。grep 验证降采样齐全 ✅
  - 问题（✅ Agent 代码验证确认）：
    - `ImagePreviewDialog.kt:64-75,110-113` 主线程 `BitmapFactory.decodeByteArray` **全分辨率解码**（4000×3000 ≈ 48MB）只为 80dp 缩略图——滚入视口即掉帧/ANR；多图瞬时数百 MB → OOM
    - `MediaUtils.kt:174-211` 发送压缩前同样全分辨率解码（无 inSampleSize 预降采样）；非压缩路径原始字节 base64 dataUrl 常驻（1.33× 膨胀）
  - 方案：inJustDecodeBounds → 按目标尺寸算 inSampleSize → 再解码；inPreferredConfig=RGB_565；解码移 Dispatchers.IO；或改用 Coil3 AsyncImage（项目已引入）
  - 工时：~0.5d | 难度：低 | 涉及：ImagePreviewDialog/MediaUtils
  - 优先级：**P0**

- [x] **#95 消息热视图活跃会话无上限（H-4）——已修复 92418445（方案①）** `leak` `data`
  - 来源：audit-2026-08-13-memory-perf/REPORT.md §4.2 H-4
  - ✅ **2026-08-13 代码验证确认**：清理链路已修（#89），活跃会话热视图无 LRU 仍存在（Agent 复核）
  - 问题（✅ 部分确认）：MessageEventHandler `_messages/_parts`（@Singleton）清理链路已在 #89 修复（onCleared/SessionDeleted 清理 + clearForServer 已清 assistantMessageIds）✅；但**活跃会话期间热视图无 LRU/上限**——Room 侧有 1000 条/会话上限，内存侧没有；重连时 recoverMessages 为所有活跃会话批量拉消息；长会话单条消息（工具输出/大 diff）可达 MB 级
  - 方案：① 内存侧按会话保留最近 N 条（与 Room 1000 对齐）；② 单 Part 文本长度上限（如 512KB）截断/懒加载
  - 工时：~1d | 难度：中 | 涉及：MessageEventHandler.kt:42-58 ✅ 2026-08-14 完结（方案①：MEMORY_SESSION_MESSAGE_LIMIT=1000 与 Room 对齐；upsertMessages/handleMessageUpdated 写入路径应用上限，裁剪最旧段并同步清 parts/assistantMessageIds；未超限 O(1)；MessageEventHandlerMemoryCapTest 3 用例）
  - 备注：方案②（单 Part 文本长度上限）未做——涉及 UI 截断展示设计，如有 MB 级工具输出需求再立项
  - 优先级：P1（长期运行 + 多活跃会话可达数百 MB）

- [x] **#96 SessionDeleted 漏清 _lastUserMessageTime/locallyClearedReverts——已修复 6c29b8b6** `leak` `data`
  - 来源：audit-2026-08-13-memory-perf/REPORT.md §4.4 L-2
  - ✅ **2026-08-13 代码验证确认**：handleSessionDeleted:119-123 仅清 _sessions/_sessionDiffs（Agent 复核）
  - 问题（✅ **2026-08-13 Agent 代码验证确认**）：`SessionEventHandler.handleSessionDeleted`（:119-123）只清 `_sessions/_sessionDiffs`，**漏清 `_lastUserMessageTime` 与 `locallyClearedReverts`**——#89 修复的 clearForSession 只在 onCleared 调用，**服务器端 SessionDeleted 事件路径未接入** → 删除会话后条目残留
  - 方案：handleSessionDeleted 内补 `_lastUserMessageTime.update { it - sessionId }` + `locallyClearedReverts.remove(sessionId)`（或直接调 clearForSession）
  - 工时：~0.5h | 难度：低 | 涉及：SessionEventHandler.kt:119-123 ✅ 2026-08-14 完结（TDD 红→绿：handleSessionDeleted 补 _lastUserMessageTime/locallyClearedReverts 清理）
  - 优先级：P1（#89 验收后发现的补漏）

- [x] **#97 SSE 热路径优化批次（H-5/H-6/M-6/M-15 全完成）——已修复 ddfc683c + 98b90e34** `performance` `sse`
  - 来源：audit-2026-08-13-memory-perf/REPORT.md §4.2 H-5/H-6 + §4.3 M-6/M-15
  - ✅ **2026-08-13 代码验证确认**（Agent 分区复核）：H-5 三子项全确认（SseClient:44-51 逐字节装箱 / SessionNextEventParser:34-35 多遍 / SseClientV2:171,181 双重转换）；H-6 全量重写确认（MessageEventHandler:235-240 + MessageStore:69）；M-6 prettyPrint 确认（NetworkModule:34 且被 MessageStore 共用）；M-15 O(N×M) 确认（:147 Map.plus 每 delta 拷贝）
  - 问题（✅ 部分确认）：
    1. **H-5 解析层分配风暴**：`SseClient.kt:42-72` readRawLineBytes 逐字节装箱 + `SessionNextEventParser.kt:34-35` V1 树→toString→decodeFromString 三遍 + `SseClientV2.kt:171-181` 双重 ByteArray 转换——流式 20-60 事件/s 持续制造 KB-MB 垃圾
    2. **H-6 双写写放大**：flush 后对整条增长中消息全量 JSON 编码 + Room 全行重写（~20 次/s）——**#52 2026-08-11 已评估"频率不可降、无进一步收益"，但 H-6 是新角度：单次写入量（全量重写）+ prettyPrint 放大 + trySend 静默丢写（N-1）**——需增量写（append delta）或节流合并（500ms/1s）
    3. **M-6 prettyPrint=true**（✅ NetworkModule.kt:34 确认）：全局 Json 带缩进——所有序列化 +30-50% 体积与编码 CPU，与 H-6 叠加
    4. **M-15 flushPendingDeltas O(N×M)**：批内每 delta 整份 Map 拷贝（`updated + (messageId to ...)`）——单次 toMutableMap 可消除
  - 方案：增长型 ByteArray 分块读；decodeFromJsonElement 单遍解析；双写增量/节流；prettyPrint=false；M-15 单次拷贝
  - ✅ 2026-08-14 进展：H-5 三子项全修（readRawLineBytes→ByteArrayOutputStream 无装箱管线 + V1/V2 data: 行字节切片 + SessionNextEventParser decodeFromJsonElement 单遍）；M-6 prettyPrint=false；M-15 flush 单次 toMutableMap 就地聚合。1610 单测全绿 + 模拟器流式实测正常（"Thought for 210ms" 渲染正确）
  - ✅ 2026-08-14 H-6 完成（98b90e34）：SSE 双写增量落盘——flush 时按 part 追加文本（O(delta) 写，DAO appendPartText SQL 拼接），消息事件仍全量 upsert（ended 覆盖防漂移）；MessageEventHandlerIncrementalPersistTest 验证跨批累积与全量覆盖一致性
  - 工时：2-3d | 难度：中-高 | 涉及：SseClient/SseClientV2/SessionNextEventParser/MessageEventHandler/NetworkModule
  - 优先级：P1（流式体验卡顿主要嫌疑）

- [x] **#98 无界容器治理批次 2（H-7+M-1+M-7+M-13 全完成）——已修复 4da3fe60** `leak` `refactor`
  - 来源：audit-2026-08-13-memory-perf/REPORT.md §4.2 H-7 + §4.3 M-1/M-7/M-13
  - ✅ **2026-08-13 代码验证确认**（Agent 分区复核）：H-7 ToolSnapshotCache:23 无上限无 TTL；M-1 pendingInputs:77 无 clear 且仅 promoted 消费；M-7 mdRegistry:395/RenderReadiness:63 无 remove（grep 0 匹配）；M-13 dirCache:43 无 LRU + loadJobs:44 无 finally remove
  - 问题（✅ Agent 代码验证确认，全部无上限/LRU/TTL）：
    1. **H-7 ToolSnapshotCache**（domain/repository/ToolSnapshotCache.kt:23）：ConcurrentHashMap 无界，写入（ChatViewModel put）与清理（FileViewerViewModel.onCleared）生命周期分离——导航取消/失败条目（含整文件内容数 MB）永驻
    2. **M-1 SseClientV2.pendingInputs**（:77,296,300）：HashMap 无界，仅 promoted 时消费；admitted 后断连丢失 → 条目永驻
    3. **M-7 mdRegistry/RenderReadinessRegistry**（ChatMessageList.kt:129,395 / RenderReadiness.kt:63-67）：组合级注册表无 remove——滚出视口条目保留 MarkdownState（AST 为原文数倍）
    4. **M-13 WorkspaceViewModel dirCache/loadJobs**（:43-44）：dirCache 无 LRU（仅 refreshRoot 清）；loadJobs 完成 Job 引用永不清理
  - 方案：参照 DirectoryManager.dirCache 200 条 LRU 标杆统一治理；mdRegistry 加 DisposableEffect onDispose remove
  - ✅ 2026-08-14 完成（4da3fe60）：ToolSnapshotCache LRU 200 + 访问同步化；pendingInputs ConcurrentHashMap + 有界 64 + 每连接清空（兼修 D2-02）；mdRegistry/RenderReadiness onDispose 注销；workspace dirCache LRU 200 + Job 完成自清理。ToolSnapshotCacheBoundedTest 2 用例
  - 工时：~2d | 难度：中 | 涉及：ToolSnapshotCache/SseClientV2/ChatMessageList/RenderReadiness/WorkspaceViewModel
  - 优先级：P1

- [x] **#99 TaskDelegate 每 5s 无条件轮询（M-10，审计 Medium 性能）** `performance`
  - 来源：audit-2026-08-13-memory-perf/REPORT.md §4.3 M-10
  - ✅ **2026-08-13 代码验证确认**：TaskDelegate:88-90 while(true) delay(5_000)（Agent 复核）
  - 问题（✅ Agent 代码验证确认）：`TaskDelegate.kt:88-93` while(true) { refreshActiveSessions(); delay(5_000) }——ChatScreen 打开期间即使完全空闲也每 5s 一次 HTTP `/api/session/active`（12 次网络唤醒/分钟）
  - 方案：空闲降频（无子会话且全 idle 退避 30s+）；V1 走 SSE 事件驱动，仅 V2 轮询兜底
  - 工时：~0.5d | 难度：低 | 涉及：TaskDelegate.kt:84-93
  - 优先级：P2

- [x] **#100 SessionListViewModel 主线程全量状态重建 + 搜索无防抖（M-11，审计 Medium 性能）** `performance` `ui`
  - 来源：audit-2026-08-13-memory-perf/REPORT.md §4.3 M-11
  - ✅ **2026-08-13 代码验证确认**：combine:350 无 flowOn；上游 5 Flow 无 distinctUntilChanged；搜索逐键 loadSessions 网络重取（Agent 复核）
  - 问题：combine 在主线程 buildContentState（过滤+排序+搜索+分类+树构建+未读判定全量）；上游 6 源无 distinctUntilChanged；搜索逐键全量网络重取
  - 方案：上游 distinctUntilChanged；_searchQuery.debounce(300)；buildContentState 移 Dispatchers.Default；搜索改纯客户端过滤
  - 工时：~1d | 难度：中 | 涉及：SessionListViewModel/SessionListStateBuilder
  - 优先级：P2

- [x] **#101 FileViewer/RenderWebView 性能批次（M-12+M-14，审计 Medium 性能）** `performance`
  - 来源：audit-2026-08-13-memory-perf/REPORT.md §4.3 M-12/M-14
  - ✅ **2026-08-13 代码验证确认**：FileViewerViewModel:45,167-178 整文件驻留 + 逐字符重扫 + AnnotationManager:17 额外拷贝 + PDF Base64；RenderWebView:91-98 update 无条件重载无 last* 比较（Agent 复核）
  - 问题：FileViewerViewModel 大文件整读多份拷贝 + 分页 O(k·n) 逐字符重扫（20 万行翻 10 页 = 10 次全扫）+ \r\n 归一化拷贝 + PDF Base64 整段塞 JS；RenderWebView update 每次重组无条件 loadDataWithBaseURL 整文档重载（丢滚动位置/图片重解码）
  - 方案：lineOffsets 索引切片；remember 比较"上次已应用"值跳过
  - 工时：~1d | 难度：中 | 涉及：FileViewerViewModel/AnnotationManager/RenderWebView
  - 优先级：P2

- [x] **#102 日志系统性能批次（M-2+M-3+M-4，审计 Medium 性能）** `performance` `logging`
  - 来源：audit-2026-08-13-memory-perf/REPORT.md §4.3 M-2/M-3/M-4
  - ✅ **2026-08-13 代码验证确认**：M-2 DebugLogger:33 无界 StringBuilder + reset 0 调用 + 同步全量写 + 无线程同步；M-3 sanitize:155-171 内联 10 Regex + recordBatch 每批 refresh 1000 条；M-4 **部分确认**：rawJson 副本存在（V2EventParser:114-118），但日志为 AppLogger.d（DEBUG-only）非报告所称 WARN——影响降级（Agent 复核）
  - 问题：DebugLogger 无界 StringBuilder + 主线程同步全量写文件 + O(n²) 累计 I/O + 无线程同步（WebView JavaBridge 并发）；DiagnosticLogRepository.sanitize 每字段新建 ~10 Regex + 每批全量 refresh；V2 未识别事件每事件构造整 JSON 副本 + WARN 持久化（叠加 M-3）
  - 方案：append 增量写 + 锁 + 512KB 限容；Regex companion 预编译 + refresh 1s debounce；rawJson 截断/降级 DEBUG
  - 工时：~1d | 难度：中 | 涉及：DebugLogger/DiagnosticLogRepository/V2EventParser
  - 优先级：P2

- [x] **#103 审计 Medium 其余（M-5+M-8+M-16）** `performance` `ui`
  - 来源：audit-2026-08-13-memory-perf/REPORT.md §4.3
  - ✅ **2026-08-13 代码验证确认**：M-5 ChatRepositoryImpl:79-92 sortedBy+upsertMessages 在 IO 块外（Main）；M-8 ChatMessageList:769-770 "t_head" key 确认；M-16 WorkspaceScreen:138-142 组合体直接 filter + VM filterGitChanges 无调用方（Agent 复核）
  - M-5：ChatRepositoryImpl.getMessagesFlow 种子合并在主线程（sortedBy+upsertMessages 移入 withContext(Default)）
  - M-8：ChatMessageList 最新 turn 的 LazyColumn key 不稳定（"t_head"）——每轮边界整气泡销毁重建（含 rememberMarkdownState 重解析）→ key 改 turn 组首条消息 id
  - M-16：WorkspaceScreen git 过滤每次重组全量执行（无 remember/derivedStateOf；与 VM 逻辑重复）
  - 工时：~0.5d | 难度：低 | 涉及：ChatRepositoryImpl/ChatMessageList/WorkspaceScreen
  - 优先级：P2

- [x] **#104 审计 Low 批量（L-3~L-18，审计 Low——除 L-1=#90、L-2=#96）** `refactor`
  - 来源：audit-2026-08-13-memory-perf/REPORT.md §4.4
  - ✅ **2026-08-13 代码验证确认**（Agent 分区复核）：L-3 persistJob?.cancel 模式（:101）；L-4 V1:331-334/V2:846-849 新建 client；L-5 getParts flatten + 生产 0 调用方；L-6 PdfViewer:120 addInterface 无 remove（对比 CodeWebView:207 有）；L-7 :126 onValueChange 内 Regex；L-8 :105-109 4s 永久轮换；L-9 :203 无 remember；L-10 :68-74 delay(100)；L-11 :310 timestamp_index key；L-12 FileTreeUtils:22-31 + 递归拼接；L-13 DiffView:119 现场 Regex；L-14 NavGraph:424-429 整文件下载判非空；L-15 :60-68 无 remember + :143 forEach 非虚拟化；L-16 :154-189 无 TTL/去重；L-17 :37 只增不减无 sweep；L-18 ChatViewModel:428-458 主线程全量扫描无 distinctUntilChanged
  - L-3 UnreadBadgeService.persistAsync 每次取消上一个写 → 改合并写（Mutex/Channel 单消费者）
  - ✅ 2026-08-15 修复：persistAsync 改 Channel(CONFLATED) 单消费者合并写（写前取最新快照，不再取消进行中的 DataStore 写）
  - L-4 exportSessionToStream 每次新建 OkHttpClient（线程池/连接池泄漏）→ 复用共享 client
  - ⚠️ 2026-08-15 保留：#121 正在处理 V1/V2ApiClient；复用共享 client（NetworkModule 长超时单例）需协调
  - L-5 ChatRepositoryImpl.getParts 全量 flatten（当前无调用方）→ 接入前改索引或删除
  - ⚠️ 2026-08-15 保留：#103 正在处理 ChatRepositoryImpl（getParts 所在文件）
  - L-6 PdfViewer JS 桥未 removeJavascriptInterface（CodeWebView 有）
  - ✅ 2026-08-15 修复：onDispose 先 removeJavascriptInterface("PdfViewerInterface") 再 destroy（与 CodeWebView 一致）
  - L-7 ChatScreenBottomBar 每按键编译新 Regex → companion 预编译
  - ✅ 2026-08-15 修复：AT_MENTION_REGEX / WHITESPACE_SPLIT_REGEX 顶层预编译（3 处现场编译清零）
  - L-8 ChatInputBar 占位符 4s 永久轮换 → 仅焦点+空文本时轮换
  - ✅ 2026-08-15 修复：占位符轮换仅聚焦+空文本时进行（ChatTextField 增 onFocusChange 上报）
  - L-9 ChatMessageList getActiveToolProgressForSession 每次重组新建 Flow → remember 提升
  - ⚠️ 2026-08-15 保留：#103 正在处理 ChatMessageList
  - L-10 ReasoningBlock 100ms ticker 常驻重组 → 降 1000ms
  - ✅ 2026-08-15 修复：ticker delay 100ms→1000ms（与 StreamingElapsedText 一致）
  - L-11 DiagnosticsScreen key 用 timestamp_index 拼接 → 队列头淘汰全 key 失效 → 内容派生稳定键
  - ✅ 2026-08-15 修复：key 改内容派生稳定键（timestamp+category+message hash）
  - L-12 FileTreeUtils.flattenTree 用 + 递归拼接 O(n²) → buildList 累积
  - ✅ 2026-08-15 修复：flattenTree 改 buildList+addAll 累积（O(n²)→O(n)）
  - L-13 DiffView 每候选行现场编译正则 → companion 预编译
  - ✅ 2026-08-15 修复：INDEX_LINE_REGEX 顶层预编译
  - L-14 NavGraph.checkFileExists 整文件下载只为判非空 → HEAD/大小
  - ⚠️ 2026-08-15 保留：FileRepository/FileApi 无 HEAD/stat 端点，需新增服务器 API（超出清理范围）
  - L-15 ServerModelFilterScreen 过滤无 remember + 组内非虚拟化渲染
  - ⚠️ 2026-08-15 部分修复：过滤已加 remember(search, groups)；组内非虚拟化→拍平独立 lazy items 为 UI 重构（需类型结构设计），保留
  - L-16 HomeViewModel 连接状态变化重启全部 providers 网络检查 → 进行中去重 + TTL
  - ✅ 2026-08-15 修复：进行中不重启（同 key 去重）+ 30s TTL（lastProvidersCheckAt，断开时清除）
  - L-17 UnreadBadgeService._lastCompletedReplyTime 只增不减无 sweep → 复用 staleness 循环清理
  - ⚠️ 2026-08-15 保留：复用 SessionStateService staleness 循环（#122 正在处理该文件）+ sweep 策略需设计
  - L-18 ChatViewModel token 统计主线程全量扫描（2000 条×20 次/s）→ map 派生 + distinctUntilChanged
  - ✅ 2026-08-15 修复：map 派生 TokenStats + distinctUntilChanged + flowOn(Default)（扫描移出主线程）
  - 工时：~1-2d | 难度：低 | 涉及：见各条 | 优先级：P3（顺手修复）

- [x] **#105 审计备注批量（N-1~N-15 重点项）** `refactor` `security`
  - 来源：audit-2026-08-13-memory-perf/REPORT.md §4.5
  - ✅ **2026-08-13 代码验证确认**（Agent 分区复核）：N-1 trySend 返回值未检查（:240）；N-2 rawSseEvents 全工程仅 3 匹配零订阅；N-3 JumpBubbleObserve settled 0 读写；N-4 ScrollCompensation:50 反射（有 try-catch 降级）；N-5 WebViewScreen:91-92 闭包捕获明文凭据；N-6 CodeSourceView 2 match 无调用方；N-7 TerminalDelegate:121-123 空实现；N-9 cancelScope 0 调用；N-12 SessionTreeList:56-67 key 不变不续载；N-14 MainActivity:79 replay=1；N-15 OpenCodeApp:57 双 scope 并存。**路径修正**：N-10 QuestionParser 实际在 ui/screens/chat/util/（非 data/repository/parser/）。**N-11 修正**：SessionActionsDelegate:323,339 与 MessagePaginationDelegate:248 共 3 处 AppLogger.d 无 BuildConfig.DEBUG 门控（AppLogger.shouldPersist 层面阻止 DB 写入，影响低）
  - N-1（数据一致性）：persistQueue trySend 满时静默丢写 → 失败计数/降级
  - ✅ 2026-08-15 修复：trySend 失败计数 + 周期性 WARN（可观测性；完整"降级"策略待评估）
  - N-4（维护风险）：ScrollCompensation 反射访问 Compose 私有 API → BOM 升级前必须验证
  - ⚠️ 2026-08-15 保留：BOM 升级前验证（记录性条目，已有 try-catch 降级）
  - N-5（安全）：WebViewScreen Basic Auth 明文凭据闭包驻留（叠加 #93）
  - ⚠️ 2026-08-15 保留：叠加 #93；WebViewScreen 为 #121 涉及文件（D2-L7 删除待协调）
  - N-2/N-3/N-6/N-9（死代码）：rawSseEvents 无订阅者、JumpBubbleObserve、CodeSourceView 无调用方、cancelScope → 清理
  - ✅ 2026-08-15 N-2 核实：已过时——rawSseEvents 全库 grep 0 匹配（早已删除）；N-6 修复：CodeSourceView.kt 整文件删除（grep 仅自引用+HighlightBuilder 文档注释）；N-9 修复：cancelScope() 删除（grep 无调用方）；N-3 ⚠️ 保留：#103/#120 正在处理 ChatMessageList/MessageCardUser（bubbleTopY 写入点）
  - N-7：TerminalDelegate.closeTerminalSession 空实现（设计取舍，评估）
  - ⚠️ 2026-08-15 保留：设计取舍（终端跨屏幕常驻），需产品决策
  - N-12（功能缺陷）：SessionTreeList 分页加载完成停靠底部不自动续载
  - ⚠️ 2026-08-15 保留：功能性缺陷（shouldLoadMore key 不自动续载），非清理类，需功能改动
  - N-14（功能隐患）：_deepLinkFlow replay=1 配置变更后重放旧 deep-link
  - ⚠️ 2026-08-15 保留：功能性隐患（加已消费标记属功能改动）
  - N-15（架构）：OpenCodeApp 自建 appScope 与 DI @ApplicationScope 双套并存 → 统一
  - ⚠️ 2026-08-15 保留：架构统一需协调（OpenCodeApp，#115 曾涉及）
  - N-8/N-10/N-11/N-13（报告判定"可接受/可忽略"，仅记录备查）：SettingsViewModel 22 个 Eagerly 映射（单字段提取开销极小）；SyntheticNotificationCard/QuestionParser Regex 未预编译（低频）；SessionActionsDelegate 等 Debug 日志较多（已 DEBUG 门控，Release 无影响）；SessionRow 每行 remember SimpleDateFormat（可接受）
  - ✅ 2026-08-15 核实：报告判定"可接受/可忽略"，仅记录备查，无需处理（未改）
  - 工时：~1d | 难度：低-中 | 优先级：P3

- [x] **#107 V2 交互式提问链路不通（question 工具调用后无 SSE 事件、REST 空）——已修复（与 #130 同根因，form API 适配）** `sse` `compat`
  - 问题：2026-08-13 构造提问验收场景时发现（Agent 实测）——V2 服务器（0.0.0-next-17403）上 agent 成功调用 question 工具（含单选+多选两个问题，state=running），但 V2 **既不发出 question.asked SSE 事件**，`GET /api/question/request` 也返回空；App 每 30s 轮询均无果，仅显示工具调用头 "Question"。V1（1.18.18）完全正常（GET /question 正确返回待处理问题）
  - 根因（2026-08-14 官方确认 issue #42541）：非缺陷而是**协议迁移**——V2 question 工具由 form 服务驱动（`form.created` SSE + /api/form/* 端点），旧 question.asked + /api/question/request 是 stale surface
  - 修复：#130 form API 适配（commit 5993c1a9/547bb204）——form.created → QuestionAsked 映射 + reply/cancel + /api/form/request 轮询兜底；真机 E2E 验证通过（卡片渲染/回答/取消/agent 续答全链路）
  - 优先级：P1 ✅ 2026-08-14 完结（随 #130）

- [x] **#106 工具链治理建议（审计 §7）——4/6 实现，1 延后（需真机），1 已失效** `tooling`
  - 来源：audit-2026-08-13-memory-perf/REPORT.md §7
  - 1. **LeakCanary** ✅ **2026-08-19 已修 493f0c07**：debugImplementation leakcanary-android:2.14（3.0 尚 alpha 选稳定线）。模拟器 E2E 五重证据：就绪日志 "LeakCanary is running and ready to detect memory leaks." + manifest 合并组件（LeakActivity/LeakLauncherActivity + 3 Provider）+ Leaks 桌面入口可开 + About 页 "About LeakCanary 2.14" + DEX 含库类（证据 /tmp/verify-leakcanary/）。已知行为变化（仅 debug）：桌面多一个 Leaks 图标；monkey 启动可能误开 Leaks——E2E 工具链须用显式组件名启动主 App
  - 2. **StrictMode** ✅ **2026-08-19 已修 c3078b41**：OpenCodeApp onCreate（BuildConfig.DEBUG 守卫）ThreadPolicy detectAll+penaltyLog / VmPolicy activityLeaks+closable+sqlLite+penaltyLog（不检测 cleartext——LAN http 是合法场景；不用 death penalty 防误杀）。首轮真实走查即捕获 **165 条主线程违规**（76% 为 SecretCipher 周期性 Keystore 解密，会话界面存活期每 ~5s 爆发）→ 登记为新条目「StrictMode 首轮发现」（见下）
  - 3. **Baseline Profile** ⏸ **延后（需用户真机）**：macrobenchmark/profileuron 生成需真实设备（官方指引：模拟器生成结果不代表真机性能分布，模拟器上"验证通过"无意义）；且需新建 benchmark 模块（~1d+ 基建）。触发条件：用户提供真机做 profile 采集时再立项
  - 4. **Regex 预编译规范** ✅ **2026-08-19 已修 d3e97478**：全库排查实际内联调用点 24 处（远超审计的 5 处，多数在 #135 批次已治理），13 文件等价重构提升为顶层/伴生预编译常量（含 ChatScreen 导出 slug——遵循编辑协议）。grep 复查内联清零 + 全量单测绿 + 模拟器冒烟 21 截图（工具卡/文件浏览器多级导航/长按菜单/markdown 滚动/synthetic 卡）零 FATAL（证据 /tmp/verify-regex/）
  - 5. **内存上限规范化** ❌ **已失效（2026-08-19 验证）**：指向的 #89（Singleton keyed 状态清理）/#90（toolExpandedStates）/#98（无界容器批次 2，4da3fe60）全部已修复关闭——无剩余同类容器，无需治理
  - 6. **CI 门禁** ✅ **2026-08-19 已修 aa551535**：lint { baseline + abortOnError=true } + release.yml 发版前 lint 步骤（此前 assemble* 从不跑 lint）。存量 59 errors 入 baseline（新 error 卡发版）；DebugLogger NewApi 误报以 @RequiresApi(Q) 消除（60→59）。存量清偿登记为新条目（见下）。**Compose 稳定性报告评估为不启用**：Kotlin 2.x 需 composeCompiler DSL 常开（每次编译产出报告拖慢构建），且无 CI 消费方——需要时一行 DSL 临时开启（app/build.gradle.kts composeCompiler { reportsDestination }），不设为默认
  - 工时：~1d（实际） | 难度：低 | 优先级：P3 ✅ 2026-08-19 完结（4 实现 + 1 延后 + 1 失效）

- [x] **#129 opencode 服务器僵尸 running（会话结束 drain 不释放）——App 已兜底+主动解除** `sse` `session`
  - 问题：2026-08-14 用户反馈"会话已结束但列表仍显示进行中"（网盘MCP与CLI工具调研 ses_00223cbb1ffeG2e92AziDs0e5E）——curl 实证：会话 30+ 分钟无新消息、无子会话、无后台任务，但 `/api/session/active` 持续返回 running；App L3 校验服务器也回复 Busy。**服务器端 session runner/drain 不释放**（opencode next-17403 行为）
  - 升级症状（2026-08-14 二次实测）：僵尸会话内**发消息无回复**——POST /prompt 返回 200+admission+SSE admitted 事件，但僵尸 runner 永不消费 inbox → 无执行事件 → UI 一直转圈（showBusy）+ 消息永远无回复（3 分钟后兜底 Idle 转圈才停）
  - App 兜底（2026-08-14 已修复）：FSM restValidation 不再刷新 lastEventAt（校验≠会话活动）+ L3 校验僵尸判定（服务器 Busy + 3 分钟无真实 SSE 事件 → 强制 Idle）。模拟器实证：网盘MCP 259s 无事件 → 转 idle 列表恢复；真实活跃会话不误判
  - App 根因修复（2026-08-14 commit 1bfa3f85）：僵尸判定时**主动调用服务器 interrupt** 解除僵尸（V1 abortSession / V2 interruptSession，SessionRepository.abort 已按 apiVersion 分流）——不再只本地装 Idle。实测：interrupt 204 → /active 中会话消失 → 后续发消息正常执行并回复
  - 服务器侧待办：升级 opencode 或向上游反馈（drain 泄漏）；App 兜底已覆盖显示正确性 + 僵尸解除
  - 工时：App 侧已完成 | 难度：低（App 侧） | 涉及：SessionStateService/SessionStateFSM | 优先级：P2（已兜底）

### 2026-08-14 跨维度审计批次（audit-2026-08-13-dimensions/REPORT.md，113 条）

- [x] **#108 SSE 心跳机制缺陷批次（D2-03 阻塞读挂死 + D2-05 V1 心跳不一致）** `sse` `network`
  - 来源：audit-2026-08-13-dimensions/REPORT.md D2-03/D2-05（B 路 + 主代理双源）
  - **2026-08-14 修复完成**：① 两客户端阻塞读套 `withTimeoutOrNull(40s)` 超时防护（SseClient 两处 + SseClientV2 帧级）——半开 TCP（kill -9/NAT 静默断）下 40s 无数据强制断开走重连，不再永久挂死；② V1 心跳与 V2 对齐（任意行/事件到达即刷新 lastHeartbeat，不再仅 ServerHeartbeat）——V1 长流式不再 40s 假超时断连；③ 测试驱动发现真实缺陷：对端 FIN 关闭时 readByte 抛 EOFException（非 ClosedReadChannelException）→ 正常 EOF 被当异常 → 补捕获（readRawLineBytesWithTimeout 辅助函数 +4 测试）；④ 模拟器实测：V2 SSE 连接建立 + 事件流正常 + kill 服务器后重连链路可用。单测 1587 全通过
  - 问题：① 两客户端 socketTimeout=Long.MAX_VALUE + 心跳检查仅在行间 → 半开 TCP（kill -9/NAT 静默断）连接永久挂死，重连/冷却失效；② V1 心跳只在 ServerHeartbeat 事件刷新（V2 已改任意事件刷新）→ V1 服务器长流式 40s 假超时断连
  - 方案：读循环套 withTimeoutOrNull(40s)；V1 心跳与 V2 对齐（任意事件/空帧刷新）；加日志观测命中率
  - 工时：~0.5d | 难度：低-中 | 涉及：SseClient/SseClientV2/SseConnectionManager | 优先级：P0

- [x] **#109 V2 REST/SSE part id 契约错位（D2-01）——已修复 5b749536（真机+模拟器 DB 双重验证）** `compat` `sse`
  - 来源：audit-2026-08-13-dimensions/REPORT.md D2-01（A 路，主代理回读确认）
  - 问题：V2Mappers 空 part id（id=""）与 SSE derivePartId（msg_ord_N）契约不一致 → mergePartsList preserved 双份保留 → 已完结消息文本双份渲染
  - 方案：V2Mappers 统一 derivePartId；或 mergePartsList 空 id 内容匹配合并；先模拟器实测复现
  - 工时：~0.5-1d | 难度：中 | 涉及：V2Mappers/MessageEventHandler | 优先级：P0 ✅ 2026-08-14 完结
  - 根因实测补充（2026-08-14 真机抓帧 + 服务器二进制）：服务器 ordinal **按类型独立计数**（同消息 reasoning[0]/text[0] 并存，TUI 片段键 k(msg,"text",ordinal) 同构）——旧 derivePartId 漏 type，三缺陷：① id 碰撞 → text.started 按 id 命中并替换 Reasoning part（推理丢失）；② REST id="" vs SSE 派生 id 双保留（双份渲染）；③ Time(start=ordinal) 伪造时长（"思考完毕 · 29778524m"）
  - 修复：derivePartId 统一 `(msg, type, ordinal)` 契约（SSE started/ended/delta + REST content 按类型计数对齐）；mergePart 时间回退链（started 本地时刻→ended/REST 真实时间戳）；mergePartsList 增加 dedupOverlappingTextParts（契约演进期内容重叠去重兜底）
  - 验证：V2PartIdContractTest 4 用例（TDD 红→绿）；1610 全量单测绿；真机 Room DB part id 全部新契约无碰撞；模拟器实测 "Thought for 210ms" 时长正常 + 无重复渲染

- [x] **#110 多服务器共享状态批次（D2-02/D2-12/D2-13/D2-24；D2-11 评估）——已修复 2f0aa0cc** `race` `multi-server`
  - 来源：audit-2026-08-13-dimensions/REPORT.md（A/B 路）
  - 问题：pendingInputs HashMap 跨服务器并发（D2-02）；状态容器 sessionId 单键无 serverId 维度（D2-11）；currentServerId 单值被覆盖 → L3 校验打错服务器（D2-12）；isConnected 语义 = job 活跃非连接（D2-13）；McpRepositoryImpl 共享 connection（D2-24）
  - 方案：ConcurrentHashMap/按 serverId 隔离；复合键 (serverId, sessionId)；去掉 currentServerId 单值；isConnected 返回真实标志
  - 2026-08-14：D2-02 随 #98（pendingInputs→ConcurrentHashMap+每连接清空+有界）；D2-12（session→server 归属映射，L3 校验优先归属）；D2-13（isConnected 真实连接标志）；D2-24（McpRepository 显式 conn 参数）
  - 评估：D2-11（sessionId 单键）不改——实证（2026-08-14）：V2 sessionId 为 ses_ + 20+ hex 随机（碰撞概率 2^-80 数学上不可能）；StreamingOwnershipRegistry 已处理同后端多配置去重；复合键需改全部 handler/UI 的 Map<String,...> 键 + 破坏存档/分页键格式，防护对象不存在 → 记录为已验证不改
  - 工时：~1-2d | 难度：中 | 涉及：SseClientV2/各 handler/SessionStateService/SseConnectionManager/McpRepositoryImpl | 优先级：P1

- [x] **#111 dataSync 前台服务 6h 时限（D2-04，Android 15+）** `android` `service`
  - 来源：audit-2026-08-13-dimensions/REPORT.md D2-04（B 路）
  - **2026-08-14 修复完成**：OpenCodeConnectionService 覆盖 `onTimeout(startId, fgsType)`——可观测日志（时限 + 当前活跃服务器）→ super 默认 stopSelf → 有活跃连接时延迟 2s 重启服务（新 6h 周期），已配置自动连接的服务器由 onCreate → autoConnectConfiguredServers 自动恢复。权限齐全（FOREGROUND_SERVICE + FOREGROUND_SERVICE_DATA_SYNC）。编译 + 单测通过；6h 时限无法加速验证，需真机长时间运行确认（可观测日志 "FGS dataSync timeout"）
  - 问题：targetSdk 36 + foregroundServiceType=dataSync + 0 处 onTimeout → Android 15+ 每 6h 系统终止服务，手动连接静默丢失
  - 方案：覆盖 onTimeout（快速重连/通知用户）；评估 FGS 类型；纳入可观测性日志；真机验证
  - 工时：~0.5d | 难度：低 | 涉及：OpenCodeConnectionService/Manifest | 优先级：P0

- [x] **#112 通知链路竞态批次——结案（D2-L30 已修 8ba18844；D2-14 N/A；D2-18 按设计）** `notification`
  - 来源：audit-2026-08-13-dimensions/REPORT.md D2-14/D2-18/D2-L30（B 路）
  - 问题：任务完成通知先标记去重后查抑制 → 抑制场景通知静默丢失；提问轮询 30s 无门控（通知关闭仍打 REST）；SessionIdle 通知依赖 250ms 固定延迟
  - 方案：先预检抑制再标记；轮询退避/门控；事件驱动或多次轮询
  - **2026-08-19 盘点 + 处置**：① **D2-14 N/A**——现行 showTaskCompleteNotification 已无任何去重标记（grep 无 markTask*），仅 shouldSuppressEvent 紧邻 notify 检查（AppNotificationManager:273），审计前提（先标记后抑制）已随后续重构消失；② **D2-18 按设计**——轮询已双职责：mergeQuestionsFromREST 供 UI 状态（提问卡 tool 补全/列表标记），通知投递本身已被 notificationsEnabled 门控（OpenCodeConnectionService:467），「关闭通知停 REST」会破坏 UI 状态链——审计前提过时；③ **D2-L30 已修（8ba18844）**——response-ready 检查改最多 3 次重试（间隔 250ms，首次命中即通知，慢设备/长末段不再静默丢通知；无输出会话最坏 750ms 后台等待）。验证层级：编译 + 全量单测绿（重试加固最坏行为与原版一致——3 次后放弃 vs 1 次后放弃，正常路径 250ms 首次命中即通知不变；通知管线 E2E 已在此前批次多次覆盖，慢 reducer 场景无法确定性构造）。**#112 结案：1 修复 + 1 N/A + 1 按设计**
  - 工时：~0.5d | 难度：低-中 | 涉及：OpenCodeConnectionService/AppNotificationManager | 优先级：P2

- [x] **#113 UI 状态竞态批次（D2-06/26/L66/L67）——已修复 58a5e0d5** `ui` `race`
  - 来源：audit-2026-08-13-dimensions/REPORT.md（C/D 路）
  - 问题：冷启动草稿不回填（视觉丢失）；快速连切设置丢修改；clearDraft 与 saveDraft 并发；QuestionCard 答案旋转丢
  - 方案：LaunchedEffect(draftText) 初始化；设置写串行化（Mutex/单消费者）；clearDraft 走同一写通道；rememberSaveable
  - 2026-08-14：D2-06（LaunchedEffect(draftText) 回填 + userHasTyped 防覆盖）；D2-26（settingsWriteMutex 写链）；D2-L66（clearDraft 走 persistMutex）；D2-L67（QuestionCard 全 saveable）
  - 工时：~0.5d | 难度：低 | 涉及：ChatScreen/ChatViewModel/SettingsViewModel/DraftInputDelegate/QuestionCard | 优先级：P1

- [x] **#114 认证头统一（D2-27，147 处内联）——已修复 89725d11** `network` `refactor`
  - 来源：audit-2026-08-13-dimensions/REPORT.md D2-27（E+A 路，grep 实测 147 处）
  - 问题：Authorization 逐请求内联 + Auth 插件空 install → 认证演进改 147+ 处，新端点易漏挂头 401
  - 方案：配置 Auth provider 或抽 auth(conn) 扩展统一替换；V1/V2 双轨同步
  - 2026-08-14：新增 AuthHeader.kt auth(conn) 扩展（Auth 插件空 install 不适合多服务器——认证是每服务器属性而 HttpClient 全局单例）；147 处内联全部替换（5 文件）
  - 工时：~1d | 难度：中 | 涉及：V1/V2ApiClient/NetworkModule | 优先级：P1

- [x] **#115 移动端生命周期批次（D2-16/D2-17/D2-L23/D2-L24/D2-L25 全完成）——已修复** `android`
  - 来源：audit-2026-08-13-dimensions/REPORT.md D2-16/D2-17/D2-L23~L25
  - 问题：无低内存回调；崩溃无条件重启（死循环风险）；手动连接进程死亡不恢复；20+ 处对话框 remember 非 saveable；FileViewerOverlay VM 重建丢批注
  - 方案：onTrimMemory 分级清理；重启退避（10min 内最多 1 次）；记录 lastConnected 恢复；rememberSaveable 批量迁移（触发条件注意：旋转由 configChanges 处理，主要覆盖 recreate 场景）
  - 2026-08-14：D2-16（onTrimMemory 清理 ToolSnapshotCache）；D2-17（崩溃重启退避 10min/1 次防死循环）；D2-L24（HomeScreen pendingConnectServerId → rememberSaveable）
  - ✅ 2026-08-14 补齐：D2-L23（355a707b，进程级 holder 按 (server,filePath) 暂存批注，VM 重建 restore，提交清除）；D2-L25（4ccd9ed4，6 处输入类对话框状态 → rememberSaveable：renameText/newFolderName/newCategoryName/name/selected；可见性标志保留 remember——重建后关闭为合理默认）
  - 工时：~1d | 难度：低-中 | 涉及：OpenCodeApp/OpenCodeConnectionService/各 Screen | 优先级：P1-P2

- [x] **#116 终端批次（D2-20 输入乱序 + D2-21 dispose 取消清理协程）** `terminal` `race`
  - 来源：audit-2026-08-13-dimensions/REPORT.md D2-20/D2-21（A 路）
  - 问题：socket.send fire-and-forget 多线程乱序；dispose() 在清理协程完成前 scope.cancel() → 服务端 PTY 残留
  - 方案：单发送 actor/Mutex；dispose 先 await 清理完成再 cancel
  - 工时：~0.5d | 难度：中 | 涉及：ServerTerminalWorkspace/PtyToTermlibAdapter | 优先级：P2

- [x] **#117 死代码/弃用/重复代码清理批次（D2-L1~L22 + D2-L15 日期统一 + D2-L16 剪贴板 + D2-L52 死参数）** `refactor`
  - 来源：audit-2026-08-13-dimensions/REPORT.md 簇 A/B/F（多路命中）
  - 问题：@Deprecated 委托链 ×9、桩方法 ×4、无调用方 API ×6、WebView 死分支 ~15KB（useNativeUi=true）、SimpleDateFormat 14 处、剪贴板 9 处、rejectHtmlResponse 复制、exportSessionToStream 整方法复制、ChatTerminalView snackbar 参数遮蔽、异常传播三套并存（D2-33，getOrThrow/Result/裸 List + ApiError 双重语义）等
  - 方案：清理日集中删除（先 grep 测试引用）；抽 DateFormatters/copyToClipboard/WebView 工厂；WebViewScreen 死分支删除需先确认无入口
  - 工时：~1-2d | 难度：低 | 涉及：见各条 | 优先级：P3
  - ✅ 2026-08-15 处理结果（D2-L1~L22 + D2-L15/L16/L52）：
    - ✅ D2-L4 connectToInstanceEvents 删除（~88 行，grep 无调用方）；D2-L5 AppLoadingEdge.kt 死组件整文件删除；D2-L6 TruncationBanner 删除 + isExtremelyLarge/正常分支 CodeWebView 合并单一调用点
    - ✅ D2-L10 getAllServers() 别名删除（ServerRepositoryImpl 直接用 servers）；D2-L14 7 个解析器 TAG 改为各自类名；D2-L18 AmoledSurfaceOverrides 抽取（动态取色 + 静态 AMOLED 共用 8 色）
    - ✅ D2-L20 applyAppLanguage(context) 抽取（LocaleUtils，MainActivity+OpenCodeConnectionService 复用）；D2-L21 fetchAllSessions() 提取（loadSessions/refreshSessions 去重 ~40 行）；D2-L22 textColor 死条件化简
    - ✅ D2-L52 ChatTerminalView 删除函数内 remember 遮蔽，改用传入参数（ChatScreen host 生效，terminal snackbar 不再静默丢失）
    - ✅ D2-L15 部分：DateFormatters 抽取 + 8 文件 11 处迁移（ShareTargetPickerDialog/TaskSheet/QuickNavigateSheet/MessageBubble/SyntheticNotificationCard/ContextDetailDialog/DiagnosticsScreen/OpenCodeApp）；SessionRow(#120)/DebugLogger(#102) ⚠️ 保留
    - ✅ D2-L16 部分：copyToClipboard 抽取 + 7 处迁移（CopyButton/ChatScreenBottomBar/ToolCardScaffold/ServerProvidersScreen/FileViewerOverlay/SessionListViewModel/DiagnosticsScreen）；ChatMessageList(#103)/ChatScreen(编辑协议) ⚠️ 保留
    - ⚠️ D2-L1 @Deprecated 委托链（EventDispatcher/ChatRepositoryImpl/ChatRepository/MessageEventHandler）→ #103 正在处理 ChatRepository 系文件，收敛 upsertMessages(MergeStrategy) 需协调
    - ⚠️ D2-L2 部分：switchSession/switchAgent 桩已删除（接口+实现+测试）；sendMessage 占位/replyQuestion → ChatRepositoryImpl 为 #103 文件保留
    - ⚠️ D2-L3 无调用方 API（getActiveToolProgress/getStepProgress/getCompactionState）→ ChatRepository 系为 #103 文件保留
    - ⚠️ D2-L7 WebView 死分支（useNativeUi=true，~15KB）→ WebViewScreen/WebViewNav 为 #121 涉及文件，删除需协调（#119 已登记待确认）
    - ⚠️ D2-L8 部分：NavGraph URLDecoder 死导入已删；ChatMessageList/ChatViewModel 残留 → #103 文件保留
    - ⚠️ D2-L9 deleteMessagePart 返回 false → 需产品决策（可区分异常或 UI 隐藏入口）；V2ApiClient 为 #121 文件
    - ⚠️ D2-L11 exportSessionToStream 整方法复制 → #121 正在处理 V1/V2ApiClient（顺带修 L-4），抽公共方法需协调
    - ⚠️ D2-L12 V2SseMapper partLocator / D2-L13 V2 会话映射两份 / D2-L19 扩展名→语言映射 ×3 → 分别为 #121（V2SseMapper/V2Mappers/CodeWebView）文件保留
    - ⚠️ D2-L17 directoryHeader 2 处内联 → SseClientV2 为 #122 文件保留
    - ⚠️ 异常传播三套并存（getOrThrow/Result/裸 List + ApiError 双重语义）→ 架构主题需独立设计（D2-33 的 prefetchGitCount 部分已随 #134 完结）

- [x] **#118 构建/安全批次（D2-28 cleartext + D2-29 R8 keep-all + D2-L64 版本倾斜/测试默认值 + D2-L28 备份密钥）** `build` `security`
  - 来源：audit-2026-08-13-dimensions/REPORT.md D2-28/D2-29/D2-L28/D2-L64
  - 问题：明文流量全局放行无白名单；R8 keep-all 整库保留；Kotlin 2.3.21 + force metadata 2.4.0；isReturnDefaultValues；备份恢复后 Keystore 密钥缺失
  - 方案：networkSecurityConfig 白名单化；R8 收窄；升级 Kotlin 后移除 force；备份规则排除凭据文件
  - 工时：~1d | 难度：中 | 涉及：Manifest/proguard/build.gradle.kts/SecretCipher | 优先级：P2
  - **2026-08-15 修复完成**：D2-28 networkSecurityConfig 白名单化（默认禁明文 + localhost/127.0.0.1/10.0.2.2 白名单，Lint 显式 includeSubdomains）；D2-29 R8 收窄（io.ktor 全库保留 → 序列化/SSE/OkHttp/utils 子集，移除 kotlinx.coroutines 全库保留——release 构建 + 模拟器连接冒烟通过：Connected + 会话列表正常）；D2-L28 核实已覆盖（backup_rules/data_extraction_rules 已排除 datastore/ 含加密密文）；D2-L64 评估保留（isReturnDefaultValues 为 mockk 标准测试配置；kotlin-metadata force 2.4.0 为 Mikepenz 0.43.0 依赖所需，注释已说明）

- [x] **#119 第一期报告状态回写（C-1/H-1/H-2/H-3/M-9 已修复）** `docs`
  - 来源：audit-2026-08-13-dimensions/REPORT.md §6.1（c0c74a4c 实证）
  - **2026-08-14 完成**：REPORT.md 五处条目（C-1/H-1/H-2/H-3/M-9）标题加"✅ 已修复（2026-08-13 c0c74a4c）"标记；backlog #93/#94 状态转正 [x]（grep 实证三处 WebView 销毁齐全 + 降采样齐全）；WebViewScreen 不可达（useNativeUi=true）确认删除项另登记
  - 问题：第一期 REPORT.md 的 C-1/H-1/H-2/H-3/M-9 仍标记未修复，实际已由提交 c0c74a4c 落地（2026-08-13 23:39）；backlog #93/#94 状态需转正
  - 方案：回写第一期报告状态 + 同步 backlog；WebViewScreen 已不可达（useNativeUi=true）需另行确认删除
  - 工时：~0.5h | 难度：低 | 优先级：P0（文档准确性）

- [x] **#120 Markdown/文案一致性批次——全部完成（D2-08 已修 78e38e3a；盘点核实 D2-07/09/10/32 先前已修）** `markdown` `i18n` `ui`
  - 来源：audit-2026-08-13-dimensions/REPORT.md（C/E 路）
  - 问题：① 跳转预渲染 fallback 用未归一化原始文本（MessageCardUser.kt:136 vs ChatMessageList.kt:442）→ 跳转目标首帧排版突变；② ClickableMarkdown 用 indexOf 定位可点击项（:95/:135）→ 重复文本段落点击/下划线错位；③ RetryBanner 双占位符恒显示 N/N（:49）；④ CompactionBanner 硬编码英文（:79）；⑤ SessionRow 硬编码英文 Diff 文案（:367-368）
  - 方案：jumpMdState 前 normalizeForRender；AST offset/span range 映射点击；文案改单占位符；提取资源补齐 14 语言
  - 工时：~0.5d | 难度：低-中 | 涉及：MessageCardUser/ClickableMarkdown/MarkdownTable/RetryBanner/CompactionBanner/SessionRow | 优先级：P2
  - **2026-08-19 D2-08 修复（78e38e3a）✅ E2E 闭环（两轮独立复验交叉确证）**：ClickableMarkdownResult 增预计算 `ranges`（items 一一对应的绝对字符区间）——Link 优先匹配链接 span（精确 offset，文档序消费 + 文本校验）；span 不可用/CodePath 走顺序文本搜索（全局游标单调推进——重复文本依次消费各自出现位置）。单测 +1（同文本双链接区间不重叠各归其位）。E2E：tap 第二 docs → example.com/b、tap 第一 docs → example.com/a（**两轮四 tap 全部差分路由正确**，uidump 地址栏 ground truth，link_02/03）；无错位/游离下划线（D2-08 回归信号缺失）；FATAL=0；会话已清理。视觉子断言勘误：像素级实证 docs 文本为深色无下划线（两轮一致）——属主题链接样式现状（深色主题下不显眼），非本修复回归，如需改进另行登记

- [x] **#121 V1/V2 双客户端一致性批次——全部闭环（D2-22/31 修 498fb643；D2-23 盘点已修 #109；D2-30 盘点已解决）** `consistency` `refactor`
  - 来源：audit-2026-08-13-dimensions/REPORT.md（A/E 路）
  - 问题：① rejectHtmlResponse 两处复制且 V1ApiClient 无 HTML 防御（V2ApiClient.kt:113/V2Mappers.kt:124）；② V2SseMapper 把 ordinal 当时间戳（:125/:151）；③ 6 处 WebView 初始化样板不统一（销毁策略各异）；④ V2 fs.list 路径推导绕过 PathUtils（V2ApiClient.kt:1157-1166，Windows 服务器必错）
  - 方案：rejectHtmlResponse 提公共 + V1 接入；SSE 时间取服务器字段；抽 WebView 工厂；改 PathUtils.fileName/joinPath
  - 工时：~1d | 难度：中 | 涉及：V1/V2ApiClient/V2SseMapper/WebView 各文件 | 优先级：P2
  - **2026-08-19 盘点 + 部分修复（498fb643）**：① D2-22 ✅——rejectHtmlResponse 提公共（data/api 包级函数，带可选日志；两份私有复制删除）+ V1ApiClient.listSessions 接入（版本误判时 ContentTransformationException → 可读 NonJsonResponseException）；② **D2-23 盘点已修**——#109（5b749536）已实现时间回退链（start=本地时刻/0L+end=now，:196/:212/:225 注释在位），条目过时；④ D2-31 ✅——name 推导改 PathUtils.fileName（Windows 反斜杠 `C:\a\b` 旧 substringAfterLast('/') 返回整串）+ absolute 拼接改 joinPath；单测 +1 反斜杠回归。③ **D2-30（WebView 工厂）仍待做**
  - 验证层级：防御性数据层修复——全量单测绿（含 listDirectory/V1 listSessions MockEngine 回归）；D2-31 Windows 真实服务器分支以单测反斜杠用例覆盖（本地无 Windows 服务器）
  - **2026-08-19 D2-30 盘点：已解决，无需工厂**——审计时点（08-13）后 #93（c0c74a4c）统一了销毁：现存 6 处构造点销毁全覆盖（WebViewScreen onDispose:133 / ErrorPayloadContent onRelease:115 / RenderWebView onDispose:67 / PdfViewer onDispose:84 含 JS 桥移除 / CodeWebView onDispose:202 含 cleanup+桥移除 / WebViewWarmer 预热后自毁）。初始化差异为**按用途安全姿态**（error=JS 全禁、html=禁文件访问、pdf=file URL 供 pdf.js worker、browser=混合内容放行、code=JS 桥+自定义 UA）——各点位注释在位，抽工厂会把安全敏感配置藏进预设反而降低可审计性。**#121 四子项全部闭环，结案**

- [x] **#122 状态性能与 AI Agent 功能批次——三子项全部闭环（D2-25 e3cde191+E2E；D2-15 a7f07039；D2-19 盘点服务器不支持）** `perf` `sse` `ai-agent`
  - 来源：audit-2026-08-13-dimensions/REPORT.md（A/B 路）
  - 问题：① SessionStateService 每 SSE 事件对 _fsmStates/_histories 整张 Map 拷贝 + mapValues 全量派生（:184-190/:212-216）→ 流式 GC 压力；② SSE id: 帧被忽略、无 Last-Event-ID 续传（SseClientV2.kt:182-184）→ 断连窗口事件可能永久缺失；③ PermissionAutoApprover.shouldAutoApprove 全库无调用方 → 自动批准规则从未生效（功能失效）
  - 方案：toMutableMap 单次拷贝 + history 定长 + mapValues distinctUntilChanged；重连带 Last-Event-ID/游标循环补漏；在 PermissionAsked 路径接入自动 reply 或移除 UI 入口
  - **2026-08-18 D2-25 接线完成（e3cde191）**：EventDispatcher.processEvent 的 PermissionAsked 分支挂钩 maybeAutoApprovePermission（规则匹配→异步 respondPermission("once")；独立 IO scope 失败仅 WARN；空规则天然关闭）。WiringTest 3/3。⚠️ 待用户真机验收：设置页存规则后权限自动通过
  - **2026-08-19 模拟器代验收 ✅（全链路 E2E，用户授权）**：关闭 2026-08-16 全自动允许开关（DataStore 字节级验证 0x00）→ 权限卡正常弹出（需要权限/拒绝/仅一次/始终允许三按钮 dump 铁证）→ 点「始终允许」+ 确认对话框 → reply=always success + **本地规则落库**（DataStore `permission_auto_approve_rules` 字节实证）→ 新 PermissionAsked 到达 → **`[auto-approve] rule matched … replying once` 日志 + 7ms 后服务器 PermissionReplied 回执**——规则匹配→自动应答→服务器接受全链实证，无卡弹出。测试现场已还原（规则删除、开关复原 TRUE、服务器配置 diff=0 重启复验探针 allow）。⚠️ 方法论存档：beta-17595 需临时在 agent permissions 加 ask 规则（**last-match-wins，ask 必须放 allow 之后**）+ POST /session/{id}/permission 评估端点触发询问——默认配置全放行时无自然询问（详见新增 beta-17595 兼容发现条目）
  - **2026-08-19 D2-19 盘点结案：服务器不支持，客户端已有最优缓解**——协议实测（curl）：① 服务器 id 在 data JSON 内，**无协议级 `id:` 行**（SSE 标准前提缺失）；② 断连窗口内触发消息事件后带 `Last-Event-ID` 头重连 → **只收到新 server.connected，零事件回放**（/tmp/verify-dm/sse_replay.txt）——beta-17595 忽略该头，实现客户端发送是无用功。既有缓解已覆盖高价值路径：消息内容 = backfillActiveForServer 游标增量补漏（8bbcb216）；会话状态 = L3 REST 校验 + reconcileWithActiveSessions 双向对账（2026-08-16）。残留缺口（断连窗口完成通知丢失）归入上游候选 #146②（服务器补事件重放/快照端点）。**#122 全部结案**

- [x] **#130 V2 question 工具协议迁移——已适配 form API（真机 E2E 验证通过）** `v2` `question` `form`
  - 背景：2026-08-14 官方回复（issue #42541）——V2 question 工具由 form 服务驱动：`form.created` SSE（metadata.kind=question，fields q0/q1...，option 含 value/label）、回复 `POST /api/session/{id}/form/{formID}/reply` `{"answer":{"q0":..}}`、取消 `.../cancel`、轮询 `GET /api/form/request`；旧 question.asked + /api/question/request 是 stale surface
  - 实现（commit 5993c1a9 + 547bb204）：
    - 新增 `V2FormMapper`（data/api/v2）：form.created/replied/cancelled → QuestionAsked/QuestionReplied/QuestionRejected（仅 kind=question 映射，复用现有提问卡片管道零 UI 改动）；REST form → QuestionRequest DTO；`buildAnswerBody` 构造 answer map（label→value 映射：UI 提交 label，服务器收 value）
    - `SseClientV2.handleEvent`：form.* 事件分支（V2SseMapper 前）
    - `V2ApiClient`：listPendingQuestions 改走 GET /api/form/request；新增 replyToForm/rejectForm
    - `MessageApi`：replyToQuestion 加 question 参数（V2 form 需要 sessionId+key/value）、rejectQuestion 加 sessionId；V1 分支原样
    - 领域模型：QuestionAsked.Question 加 key、Option 加 value（V1 均为 null 兼容）
  - 验证（2026-08-14 真机 PLK110 + V2Real 4199）：
    - ✅ form.created → QuestionAsked 映射（logcat: `[recv] QuestionAsked` → `[dispatch] -> QuestionEventHandler`）+ 卡片渲染（单选/多选/Q tabs/自定义输入）
    - ✅ 提交：label→value 映射正确（UI 选"米饭"提交 `{"q0":"rice","q1":["Water","Coffee"]}`，服务器 state=answered，agent 回复确认收到答案）
    - ✅ 取消：`POST .../cancel` 204 → form.cancelled SSE → 卡片消失，服务器 state=cancelled
    - ✅ 轮询兜底：App 每 30s GET /api/form/request（logcat 实证）
    - ✅ V1 回归：V1 轮询仍走旧 /question 端点（代码未动）
    - ✅ 单测：V2FormMapperTest 10 个用例（映射/REST/answer 构造）+ V2ApiClientTest form 端点路径
  - 备注：form 字段类型仅映射 string（单选）/multiselect（多选），number/integer/boolean/external 暂不支持（question 工具不产生）；文档见 docs/opencode-api-reference-v1.md §12A（原 opencode-api-reference.md，2026-08-21 更名）

- [x] **#131 V1 协议 question 卡片嵌入渲染失败（数据到达但 UI 不显示）——已修复 eab5f964** `question` `v1`
  - 现象：V1 服务器（1.18.18）agent 调用 question 工具（4 题多选）——服务器 /question 正常返回（含 tool.messageID），App 轮询/loadPendingQuestions 均拉到（`Replaced 1 questions for session ...`），但 UI 问题卡片不渲染（goon 的 assistant 消息气泡内无 QuestionCard）
  - 对比：V1 单选卡片（首个问题）能正常显示——当时 question 经 SSE 事件到达或 tool 关联正常
  - 疑点：① tool.messageID 与消息列表 id 匹配（截断/完整 id 差异）；② 已完成语义（step-finish）下嵌入逻辑不触发；③ unembedded 独立卡片也未显示 → 更可能是 pendingQuestions 未进 UI combine 或渲染条件不满足
  - 证据：docs/dialogue-e2e-test-runbook.md（V1 question 实测记录）；logcat `Replaced 1 questions` + UI 无卡片
  - 待办：深挖嵌入/独立卡片渲染条件，V1 全生命周期 E2E 前置
  - 工时：~2h | 难度：中 | 涉及：ChatMessageList/QuestionEventHandler/MessageDataDelegate | 优先级：P1（阻塞 V1 question 功能 + #126 验证）

- [x] **#132 调试通道模块（adb 外部参数一键直达会话列表）** `devtools` `debug`
  - 来源：2026-08-14 用户需求（真机调试效率）
  - 问题：真机调试需手动输入 URL/账号/密码（每次配置易错）；调试连接无一键入口
  - 方案（最终形态）：仅 debug 构建，完全外部参数方式——adb am start --es debug_url/--es debug_username/--es debug_password/--es debug_name，App 幂等保存服务器 + 版本探测 + 连接 + 直达会话列表；无内置套餐/无 UI 入口（曾实现内置套餐后按用户要求移除，避免维护负担）
  - 待办：设计套餐数据模型（serverUrl/user/password/name/autoConnect）+ 注入方式（gradle BuildConfig 字段 / debug manifest meta-data / intent extra）；实现调试专用设置页或启动分流
  - 工时：~0.5-1d | 难度：低-中 | 涉及：ServerConfig/连接层/启动导航 | 优先级：P2
  - 验证（2026-08-14 真机 PLK110 通过）：adb am start 完整参数方式（debug_url=http://192.168.110.53:4199 + username/password/name）冷启动直达 V2 会话列表（幂等复用 a7e67a30；logcat 三连证据链；错误 0）；联动修复：版本探测失败不再降级 apiVersion（V2 被降 V1 → SPA HTML 解析错误的根因）
  - 实现：commit 20017337 + f14043a7（移除内置套餐，仅参数方式）；用法见 docs/debug-channel.md

# ============ 2026-08-14 审计遗漏补登（交叉验证：需求↔代码一致性） ============

> 背景：精确核对 audit-2026-08-13-dimensions + memory-perf 两份报告的 161 个发现，40 项未登记。
> 用 4 个并行 subagent 逐项读码交叉验证（+主会话抽查复核），结论：37 UNFIXED / 2 FIXED / 1 N_A。
> 37 项 UNFIXED 按性质分 5 批；FIXED/N_A 单独记录。

- [x] **#133 审计遗漏批次 1：连接稳定性（D2-L26/L27/L40/L41，4 项 UNFIXED）** `stability`
  - 来源：audit-2026-08-13-dimensions §4（交叉验证 2026-08-14：4/4 UNFIXED）
  - D2-L26 OpenCodeConnectionService.kt:626 newWakeLock(PARTIAL).acquire() 无超时兜底；释放仅正常断开路径 → acquire(timeout)+周期续期
  - D2-L27 OpenCodeApp.kt:84 崩溃日志文件名秒级分辨率，同秒两次崩溃互相覆盖 → 加纳秒/序号
  - D2-L40 SseConnectionManager.kt:116 startConnection 裸 cancel() vs reconnectServer cancelAndJoin() 不一致 → 统一
  - D2-L41 NetworkMonitor.kt:91 onCapabilitiesChanged 失去 VALIDATED（captive portal）时状态卡旧值 → 补非 validated 分支
  - 工时：~0.5d | 难度：低-中 | 涉及：见各条 | 优先级：P1（连接稳定性）

- [x] **#134 审计遗漏批次 2：一致性/竞态（D2-L33/L36/L38/L39/L47/L54/L57/L62，8 项 UNFIXED）** `consistency`
  - 来源：audit-2026-08-13-dimensions §4（交叉验证 2026-08-14：8/8 UNFIXED）
  - D2-L33 WorkspaceViewModel.kt:168 prefetchGitCount 无 in-flight 保护，切面板双发 VCS status
  - D2-L36 ServerSettingsViewModel.kt:111 init 4 路并行加载各自 rebuildUi → loading 抖动无去重
  - D2-L39 TokenStatsTracker.kt:24 update() 裸读-改-写非 CAS（并发丢更新）
  - D2-L54 SessionEventHandler.kt:109 locallyClearedReverts.remove 仍在 _sessions.update lambda 内（CAS 重试重复执行副作用）
  - D2-L57 SettingsRepositoryImpl.kt:75 updateSettings 21 次独立 DataStore edit → 单一 updateAll（半套落盘风险）
  - D2-L62 MessageEventHandler.kt:300 persistSseUpdate 分两次读 _messages/_parts 非原子快照
  - D2-L38 DirectoryManager.kt:87 getServerPaths 失败也缓存空 ServerPaths() 无 TTL → 一次瞬时失败毒化整个 VM 生命周期
  - D2-L47 ChatErrorState.kt:36 错误态固定 5s 无退避自动重试（服务器不可达时无限请求）
  - 工时：~0.5-1d | 难度：中 | 涉及：见各条 | 优先级：P1（并发一致性）

- [x] **#135 审计遗漏批次 3：性能（D2-L42/L43/L44/L45/L46/L68，6 项 UNFIXED）** `performance`
  - 来源：audit-2026-08-13-dimensions §4（交叉验证 2026-08-14：6/6 UNFIXED）
  - D2-L42 AppLogger.kt:198 shouldPersist 每次日志现场构造 mapOf（流式 50-90 条/s → 每秒数百次分配）
  - D2-L43 BashToolCard.kt:63 ANSI 正则每次重组现场编译
  - D2-L44 MarkdownContent.kt:110,125 normalizeMarkdown 内容变化时现场编译 2 个 Regex（流式每 token）
  - D2-L45 ReasoningBlock.kt:85 rememberInfiniteTransition 无条件运行——已完成/折叠思考卡片仍 60fps 动画帧
  - D2-L46 MarkdownTable.kt:196 每次 measure 全部单元格 3 遍 subcompose 无缓存
  - D2-L68 ImagePreviewDialog.kt:69 主线程 Base64 解码全量 data URL（仅加降采样未移线程）
  - 工时：~0.5-1d | 难度：中 | 涉及：见各条 | 优先级：P2（流式/渲染性能）

- [x] **#136 审计遗漏批次 4：安全/隐私（D2-L29/L51/L53/L55/L56/L58，6 项 UNFIXED）** `security`
  - 来源：audit-2026-08-13-dimensions §4（交叉验证 2026-08-14：6/6 UNFIXED）
  - D2-L29 ServerProvidersScreen.kt:234 API key 输入框无 PasswordVisualTransformation（明文；ServerDialog:176 有遮蔽）
  - D2-L51 MarkdownPreviewDialog.kt:88 performHaptic(view,true) 硬编码触觉反馈无视用户设置
  - D2-L53 PermissionEventHandler.kt:46,59 文案 auto-approved/auto-denied 与真实语义不符 + release INFO/WARN 级别
  - D2-L55 ChatMessageList.kt:120 硬编码服务器模板字符串匹配（服务器改文案即静默失效）
  - D2-L56 SettingsDataStore.kt:138 SharedPreferences 与 DataStore 双写镜像无启动校验（两写间崩溃 → 语言漂移）
  - D2-L58 UpdateRepository.kt:161 .apk.part 临时文件进程被杀残留（check/restore 前不清理）
  - 工时：~0.5d | 难度：低-中 | 涉及：见各条 | 优先级：P1（明文凭据 + 文案误导）


  - **2026-08-15 修复完成**（commit 见 git log）：
    - #136：D2-L29 密码遮蔽 · D2-L51 触觉设置 · D2-L53 文案/级别 · D2-L55 模板变体 · D2-L56 镜像校验 · D2-L58 残留清理
    - #134：D2-L33 in-flight · D2-L36 loading 去重 · D2-L38 失败不缓存 · D2-L39 CAS · D2-L47 退避 · D2-L54 副作用移出 · D2-L57 单次 edit · D2-L62 append 幂等
    - #133：D2-L26 wakeLock 超时+续期 · D2-L27 毫秒时间戳 · D2-L40 cancelAndJoin 统一 · D2-L41 validated 分支
    - 新增单测：IsBackgroundMoveSyntheticTest(6) · SettingsLanguageMirrorTest(5) · TokenStatsTrackerConcurrencyTest(3) · DirectoryManagerServerPathsTest(3) · SessionEventHandlerTest +2

- [x] **#137 审计遗漏批次 5：清理/样式（D2-L31/L32/L34/L48/L49/L50/L59/L60/L61/L63/L65/N-01/N-02，13 项 UNFIXED）** `refactor`
  - 来源：audit-2026-08-13-dimensions + memory-perf（交叉验证 2026-08-14：13/13 UNFIXED）
  - D2-L31 FileViewerViewModel.kt:226 nextHunk 空 hunks → 索引 -1
  - D2-L32 NavGraph.kt:405 onNavigateToChildSession 无 launchSingleTop（同文件其余 9 处均有）
  - D2-L34 OpenProjectDialog.kt:318 创建文件夹按钮未随 isCreatingFolder 禁用 → 双击双发
  - D2-L48 sessions/ 目录裸 dp 145 处 vs SpacingTokens 4 处（令牌覆盖不均）
  - D2-L49 FileTreePanel.kt:151 / PdfViewer.kt:190 硬编码 alpha 0.4f/0.9f 绕过 AlphaTokens
  - D2-L50 ToolCardScaffold.kt:187 复制反馈 Toast vs Snackbar 双通道不统一
  - D2-L59 SettingsDataStore.kt:506 favoriteSessionIds 读 flow 内执行 edit 写（隐蔽副作用迁移）
  - D2-L60 FileRepositoryImpl.kt 仅 listDirectory 有 IO，其余 6 方法裸调用
  - D2-L61 MessageStore.kt:100 runCatching 吞一切异常（约束冲突本不抛，危害面小，IO 瞬态仍需降级日志）
  - D2-L63 OpenCodeApp.kt:156 onCreate 主线程 listFiles+解析崩溃文件名（未移 IO）
  - D2-L65 ChatScreen.kt:516 vs 763 onViewToolLambda 重复定义（内层死代码）
  - N-01 SessionFocusHolder.kt:44 shouldSuppress 分两次独立读非合并快照
  - N-02 SseClient.kt:171 rawSseEventFlow 零订阅者（死代码，注释称'V2 管线消费'不实）
  - 工时：~1d | 难度：低 | 涉及：见各条 | 优先级：P2（清理/样式，L32/L34 可提前）


  - **2026-08-15 修复完成**：
    - #135：D2-L42 级别映射预构造 · D2-L43 ANSI 正则预编译 · D2-L44 Markdown 正则预编译 · D2-L45 脉冲动画条件化 · D2-L46 表格测量缓存（探针/行高复用）· D2-L68 图片解码移 IO 线程
    - #137：D2-L31 空 hunks 防护 · D2-L32 launchSingleTop · D2-L34 防双击 · D2-L49 alpha→AlphaTokens · D2-L50 复制反馈 Snackbar 通道（LocalCopyFeedback）· D2-L59 收藏迁移显式化（flow 纯读）· D2-L60 FileRepositoryImpl 全 IO · D2-L61 runCatching→runCatchingCancellable（取消传播）· D2-L63 崩溃检测移 IO · D2-L65 死代码删除 · N-01 合并快照 · N-02 rawSseEventFlow 死代码删除 · D2-L48 sessions/ 58 处 dp→SpacingTokens（subagent 执行）

- [x] **#138 审计遗漏——交叉验证 FIXED/N_A 记录（D2-L35 FIXED + N-05 FIXED + D2-L37 N_A）** `docs`
  - 2026-08-14 交叉验证结论（非新问题，编号回写）：
  - D2-L35 FIXED：SessionListViewModel.kt:172 DataStore 写 markSessionRead 已移 viewModelScope.launch 异步（组合期调用仅内存操作，注释明确设计）
  - N-05 FIXED：SseConnectionManager.kt:212 isConnected 已由 #110 D2-13（commit 2f0aa0cc）改为真实连接标志（弃用 sseJob.isActive）
  - D2-L37 N_A（审计误报）：HomeViewModel.kt:265 connectToServer guard 主线程同步更新 connectingServerIds 先于 launch，同帧双击二次调用读到更新后状态提前 return——双发 testConnection 不可复现

---

## 2026-08-15 主对话流程 bug 批次（用户 V2 真机反馈，全链路根因修复）

- [x] **#139 发送成功后输入框偶发不清空** `ui`
  - 现象：点击发送后输入框内容偶发残留（消息已发出）
  - 根因：ChatSendDelegate 的 E8-1 清空快照 = prompt parts 重组文本，而 PromptBuilder.buildPromptParts 对文本 trim() 并拆分 @file 提及 → 快照与输入框原始文本（含尾随空格/换行/@mention）不一致 → ChatScreen 比对失败不清空。干净单行文本恰好匹配 → "偶发"
  - 修复（668d7b0e）：sendMessage(promptParts, attachments, rawText) 增加 rawText，快照直接用输入框原始文本（V1/V2 通用）
  - 验证：E2E 发送尾随文本 → 输入框清空 ✅（V2）+ V1 回归 ✅；1642 单测全绿

- [x] **#140 统计栏有概率丢模型名/耗时只剩圆圈（subagent 高发）** `ui` `sse`
  - 根因（三层）：① V2 session.step.ended 契约不含 modelId/providerId/agent，handleMessageUpdated 整对象替换抹掉 step.started 写入的模型信息（tokens 同事件写入 → "圆圈在、模型无"不对称）；② 耗时 completed 在 V2 SSE 从不携带，且 step.ended 的 created=本地时刻顶替原始时刻 → 单步消息耗时≈0 被 >0 门隐藏；③ MessageFingerprints 不含 modelId → RenderableTurn 缓存陈旧值不恢复。subagent 高发：子会话全程 Busy → L3 校验跳过 + 增量游标拉不回旧消息
  - 修复（3fcc11ae）：mergeAssistantMeta 非空字段合并（incoming 空保留 existing；created 取较早值）+ mergeMessageMeta 采纳 REST 模型元数据兜底 + 指纹纳入 modelId/providerId/agent。V1 message.updated 带全量字段 → incoming 覆盖，行为不变（E2E V1 实证统计栏正常）
  - 验证：V2 E2E deepseek-v4-pro + 5.2s/13.9s 显示 ✅（智谱识图 D3 复核）；V1 3.2m 统计栏正常 ✅

- [x] **#141 转后台/subagent 完成通知误渲染成 user 气泡（"多出大段用户回复"/"assistant 内容进 user 气泡"）** `sse` `ui`
  - 现象：转后台后多出一大段"用户的回复"，且内容是 assistant（subagent）的输出
  - 根因（Room+curl 双实证）：subagent/后台任务完成通知同样经 session.inbox.enqueued 投递（item.type="synthetic"，body=<subagent …>子代理全部输出</subagent>，实测 5KB）；V2SseMapper 播种分支无条件构造 Message.User（role 默认 "user"）→ 通知渲染成 user 气泡
  - 修复（6ca1f357）：播种读 item.type，非 user 类型设置对应 role → 下游走 SyntheticNotificationCard 通知卡片（V1 无 inbox 机制不受影响）
  - 验证：curl 注入 synthetic → logcat "admitted type=synthetic → MessageUpdated role=synthetic"（修复前 role=user）→ UI 渲染 "Sub-agent · E2E 测试任务" 通知卡片 ✅ 无 user 气泡

- [x] **#142 流式期间内容大段缺失、完结/重进后完整（断连窗口）** `sse` `data`
  - 根因（iptables 断连复现实证）：SseConnectionManager 的 attempt 在连接成功后重置为 0 → "曾成功连接→断连→重连"场景 attempt 恒为 1 → `if (attempt > 1) recoverMessages()` 永不触发 → 断连窗口（40s 心跳超时+退避）SSE 事件永久丢失，内容缺失不恢复（直到 text.ended 全量覆盖/重进 REST）
  - 修复（8bbcb216）：独立 hasConnectedOnce 标志——曾成功连接过的重连都执行 recoverMessages（REST_AUTHORITY + mergePartsList 更长文本胜出恢复）
  - 附带修复（同 commit 批次 6ca1f357）：V2SseMapper.partLocator ordinal 缺失 return null 静默丢弃整条 delta（且两行重复为复制粘贴错误）→ 兜底 0
  - 验证：断连 18s 场景 → 修复前无 Recover 日志；修复后 "Recovering messages for 50 sessions → Recovered 50/50" → Room 故事 2505 字 vs 服务器 2623 字一致 ✅
  - 已知边界（非缺陷）：断连窗口若丢 step.ended 事件则该消息无 tokens（圆环不显示）——REST 协议不返回 tokens，属服务器契约限制


- [x] **#144 subagent 调研会话卡死（31.6 分钟/17 分钟无消息输出）——2026-08-21 DB 取证归因关闭（opencode 服务器侧）** `upstream`
  - 现象：2026-08-16 主对话委派的 deep-explore 子会话两个停滞（ses_ff8f1c73affe…卡片内容截断调研 31.6 分钟无消息；ses_ff8e68cacffe…subagent 跳转调研 17 分钟无消息），第三个（回复不可见）正常完成
  - 影响：主对话等待挂起，需人工重派；已重派并带线索缩小范围
  - **2026-08-21 DB 取证完成（opencode.db 直查，归因钉死）**：两个卡死会话最后一条消息均以 `tool part status='running'` 永久挂起——ses_ff8f1c73 卡在 `read /persistent/.../opencode/service.json`（limit 15）；ses_ff8e68c8 卡在 `glob /persistent/.../.config/opencode pattern='*'`。共同点：**I/O 目标全在 `/persistent` 挂载（Tailscale 网络盘）**——网络盘 I/O 无限阻塞 + 服务器工具调用无超时 → 会话永远停在 running（旁证：当时 /api/session/active 全程返回 running，且『31.6 分钟无输出』期间 DB 零新消息落库——非慢，是死等）。父会话当时对同路径的 find 命令秒回，仅子会话工具调用挂起——服务器侧工具执行路径问题，非本 App 缺陷
  - 状态：`[x]` 归因关闭（服务器侧）。上游候选并入 #146 ⑥：deep-explore/task 工具调用对网络挂载路径 I/O 无 watchdog/timeout

- [x] **#145 任务面板 subagent 列表项显示执行时长——结案（5056694b 实现 + 2026-08-19 模拟器 E2E 走时验证 ✅）** `ui`
  - 需求（用户 2026-08-16）：任务面板（TaskSheet）的 subagent 列表项需要看到执行时间，放在 list item 右对齐合适位置
  - 现状：TaskSheet 列表项已显示开始时间（time.created，如 "10:22"）；执行时长 = 子会话完成时间（最后消息 time.completed）- created，运行中则 now - created（需要 1s 级刷新才能看到走时）
  - 注意与 ChatViewModel 侧思考计时（0.1s 间隔）区分——任务面板多个 item 同时走时需评估重组开销，可复用现有计时基础设施
  - 状态：`[ ]` 待实现（随任务面板修复批次一起做）
  - **2026-08-19 E2E 结案验证（用户指示模拟器校验优先）**：实现 5056694b 早已落地（TaskSheet trailing Column：状态图标在上、时长在下右对齐）。模拟器实证：① 运行中子代理「整理30条太空趣味事实」面板显示走时 **31s → 37s**（间隔 3.5s 两次快照，1s tick 前进铁证，截图 /tmp/verify-145-running.png）；② 完成态不显示时长 = **契约内预期**（🟠 V2 session.time.updated 不随活动更新 → 完成态无数据源，仅 updated-created>5s 罕见场景显示，代码注释文档化 + upstream 候选 #146）；③ 列表项结构完整（标题/前台徽章/agent/开始时间/模型/时长）。核心场景（后台任务跑着看跑了多久）验证通过


- [ ] **#146 OpenCode 官方问题清单（issue/PR 候选，2026-08-16 调研批次产出）** `upstream`
  - 提 PR 前提（用户定规）：本地定位官方源码（opencode-src V2 主干 / opencode-v1）→ 对应缺陷修复 → 本地完整测试（含端到端+交叉验证）→ 人工测试完毕 → 才可提交
  - ① **V2 不发 compaction.started 事件**（仅单个 session.compacted）：客户端无法事件驱动显示"压缩进行中"。候选 issue：建议 V2 补发 started/ended 对或文档化契约。本地已用"本地置态"绕行（代码注释标记 🟠）
  - ② **SSE 断线重连后无事件回溯**：错过的 idle/完成事件不补发，客户端通知与状态永久缺失。候选 issue：建议提供重连后事件重放或状态快照端点。客户端 REST 补查仅 best-effort（代码注释标记 🟠）
  - ③ **V2 cursor 参数收到 V1 格式 {id,time} 返回 400**：而非忽略或宽容降级；与官方文档/直觉不符，且错误信息无 cursor 格式提示。候选 issue（文档澄清或宽容处理）。客户端已改 encodeV2 根治
  - ④ **V2 fork 端点 handleRaw 冲突 bug**（任何 body 400）：已知服务器 bug，等官方修复或提 PR（需按前提流程）
  - ⑤ **工具输出保尾截头（30K 字符/2000 行）语义**：设计使然非缺陷；候选 feature request——progress metadata 提前携带 truncated/outputPath 让客户端更早提示
  - **2026-08-21 上游核查完成（子代理调研 + /tmp/opencode-src 主干 clone）**：上游 repo 已迁至 **anomalyco/opencode**（sst/opencode 为旧地址，~199k stars，默认分支 dev）；本机 4199 = `@opencode-ai/cli@0.0.0-beta-17728`（当前最新 beta，08-20 发布）；`0.0.0-<channel>-<N>` 为 2.0 开发线、GitHub Releases 只发 1.x 稳定线。逐候选状态：
    - ① **仍存在·此角度未报告**：事件发射点只在新核心引擎（packages/core/.../compaction.ts:192/222），shipping 旧引擎（opencode2.exe serve）只发粗粒度 session.compacted——『契约有、引擎没接线』。行动：提窄 issue（legacy serve path 补发 started/ended），先评论 #36187/#40494 防重；客户端超时启发式保留
    - ② **上游已两次 not_planned（#25657/#19584），PR #25658 未合并——不再提**。官方方向 = 拉取式 sync：主干已有 POST /sync/history（seq 游标补发 durable 事件）但 17728 未发布；且 session.status/idle 是纯内存 live 事件，回放补不了。客户端已有 REST 对账即正解；关注该端点发布后迁移
    - ③ **仍存在·未报告·值得小 PR**：packages/server/src/handlers/message.ts:38 与 session.ts:30 catch-all 只报 "Invalid cursor"（V2 cursor 实为 base64 JSON {id,order,direction}）。改动小被拒风险低，按完整流程走（源码副本已备）
    - ④ **移动靶——先升级重测再决定**：17728 要求 boundary 字段（{} → 400 Missing key boundary），但主干已改为 ForkInput={sessionID, messageID?} + 空 body 容忍（handlers/session.ts:218-231）——契约一周内刚翻新。等下一 beta 三形态重测
    - ⑤ **暂缓**：截断限制已可配置（PR #23770 merged：tool_output.max_lines/max_bytes）——先服务器配置放宽；progress 事件 shipping 引擎无发射点（仅 schema），FR 等 2.0 引擎 cutover
    - ⑥（新增自 #144）：工具调用对网络挂载路径（/persistent 等）I/O 无超时 → 会话永久 running。候选 issue
  - 状态：`[ ]` 候选池（①③⑥ 可行动；②关闭；④等升级；⑤暂缓）

- [x] **#147 androidTest UI 测试失败（编译断 + touch 注入败）——已随 #149 全量修复结案（136/136 全绿）** `refactor` `test`
  - 现象：2026-08-16 修复 androidTest 编译后首次真跑，全部 UI 测试报
    "No compose hierarchies found in the app"（HiltTestRunner 启动的 Activity
    与 createComposeRule/createAndroidComposeRule<ComponentActivity> 不兼容）
  - **2026-08-18 复验定性**：① 编译再次断裂——主代码接口演进（respondPermission 加 sessionId、SessionStateRepository 加 backfillMissedMessages）后 Fake 未同步（已修 6023bd5f，androidTest 编译恢复）；② 修复编译后 19 用例 **12 通过 / 7 失败**——"全部失败 No compose hierarchies"已不复现（当时问题似乎已随某次修复消散），现存失败移交 #149（5 touch 注入 + 2 节点超时）
  - 修复方向：迁移 androidx.compose.ui.test.junit4.v2 API + HiltAndroidRule
    组合，或为非 Hilt 测试提供独立 TestRunner（gradle 配置多 runner）
  - 已完成的前置：Fake 接口对齐（6 个 + 2026-08-18 再补 2 处）+ FakeMessageCacheRepository +
    Hilt 测试图 MissingBinding 修复
  - **2026-08-19 结案**：#149 修复三类根因（hasScrollAction 多匹配 → testTag 选择器 / 文案漂移 / 断言过时）后 androidTest **136/136 全绿**（含全量）；"No compose hierarchies" 现象未再现，原修复方向（junit4.v2 迁移）不再必要。标题同步改写（原标题"12/19 可过"已过时）

- [x] **#148 任务面板 subagent 点击「无法进入」——2026-08-16 归因关闭（环境问题非 App bug）** `ui`
  - 现象：模拟器点击 TaskSheet 列表项无反应（探针 0 触发、sendevent 原始注入同样失效）
  - 排查：git 回退 TaskSheet+ChatScreen+ChatViewModel 至正常版本仍复现 →
    排除代码；**重启模拟器后恢复正常**（完整跳转链验证 ✅）
  - 结论：模拟器长时间运行（8h+）输入系统劣化——仅 ModalBottomSheet 内
    Compose clickable 失效。E2E 排障守则：长会话后「点击无反应」先重启模拟器
  - 状态：`[x]` 归因关闭（无代码缺陷；App 实际跳转功能正常，dev.11 真机可验）


- [x] **#149 androidTest 剩余 7 个失败——已修复 6f574128（三类根因，136 全绿）** `test`
  - 现象：2026-08-16 androidTest 修复至 129/136 后，剩余 7 个全部
    "Failed to inject touch input"——新模拟器同样失败（非环境劣化）
  - **2026-08-18 复现明细（19 用例 12 过 7 败）**：`Failed to inject touch input` ×5（ChatScrollStabilityTest.userScrollsAway / autoScrollEnabledResets / shouldCompensateResets / completedMessageHeight 四个 SSE 铁律守护 + FileTreePanelTest.filterChipClick，与 08-16 记录一致）+ `ComposeTimeoutException 10s` ×2（ChatInteractionTest.contextUsageBar_shows / questionDialog_appears——非 touch 注入族，节点等待超时，独立定性）
  - 涉及：ChatScrollStabilityTest×4（SSE 铁律守护测试）/ChatInteraction×2/
    FileTreePanelTest.filterChip×1
  - **2026-08-18 修复（6f574128）——与 ScrollListGate 重构无关，三类独立根因**：
    ① touch 注入 ×5：诊断用例实证 hasScrollAction() 在 ChatScreen 树匹配 2 节点
    （消息列表+底部输入栏可滚动）→ onNode 多匹配坐标无效——LazyColumn 加
    testTag(chat-message-list)，选择器改 onNodeWithTag（含 IsolatedTest 同病）；
    ② FileTree chip：断言与资源文案不一致（改版未跟测试）——加 testTag；
    ③ 超时 ×2：question 卡标题改版 Awaiting your reply + contextWindow 兜底被
    0cb68851 删除（改 tokenStats 直供）。验证：136/136 全绿（含全量 androidTest）
  - 状态：[x] 已修复

## 2026-08-18 模拟器验证批次（backlog 待验证项集中复验 + 新发现）

> 环境：Pixel6_Android36 模拟器（API 36，dev 0.3.1-dev.15 @ master 8d65a387）+ V2 服务器 0.0.0-beta-17595（10.0.2.2:4199）。
> 证据目录：/tmp/verify-0818/（截图 00-57 编号序列 + logcat + dumpsys + gfxinfo + Room DB 副本）。
> 修复 commit：32765cf6（question 轮询）+ 6023bd5f（androidTest Fake）。

- [x] **V2 长会话历史分页不可达（501 条只见 40 条，NETWORK 首翻恒 0 条）——已修复 53cfea68** `data` `sse`
  - 现象（2026-08-18 模拟器复验新增F 时发现）：进入 501 条消息的测试会话 → 上滑到顶触发 auto-load（触发机制正常：`auto-load probe → triggered → loadOlder START`）→ **NETWORK 返回 0 msgs → hasOlder=false 误判读尽** → 461 条更早消息永久不可达
  - 根因（curl 双盲区实证）：**两处修复打架**——MessagePaginationDelegate:216（2026-08-12 补丁）在 HotStart+V2 时本地构造 `encodeV2(hotOldestId)` cursor，而 MessagePaginationUseCase:200（2026-08-16 根治）已明确 V2 首翻**不传 cursor**（依赖响应的 cursor.next）。热表最老是中部历史 id → 服务器**窗口外 id 返回空页**（curl 复现：构造 cursor=历史id → count=0 且 next=null；cursor=近期id → 30 条 next 正常）——08-16 根治被 08-12 补丁旁路
  - 服务器行为补充：beta-17595 窗口语义 = 仅近期 id 的 cursor 有效；与 #73（窗口外空页）同族，服务器升级后窗口收紧
  - **2026-08-18 修复完成（53cfea68）+ 模拟器闭环 ✅**：删除 Delegate 本地 encodeV2 构造（HotStart 首翻走 use case null-cursor 路径拿原生 cursor.next，归档优先顺序恢复）+ PaginationFSM.LoadSucceeded 加固（hasOlder = nextCursor != null || pageSize >= limit，与 LoadNewerSucceeded 对称；顺带勘误既有测试自相矛盾参数）。验证：回归测试 ×3；模拟器 501 条会话游标链逐页前进（created 递减至 8月12日）、total 137→226 持续加载、Room 热表 **501 条全量落库**（08-05→08-18 14:23 与服务器一致）、深翻回滚消息连续渲染无丢失；61 分页单测 + 1694 全量单测全绿

- [x] **SSE 冷却死循环（连续超时后永不真正重连）——已修复 bd04d060** `sse`
  - 现象（2026-08-18 修复轮询验证时发现）：beta-17595 服务器无心跳帧 → SSE 每 40s 读超时 → 连续 5 次后进入 cooldown → 日志每 30s 打 `SSE in cooldown, waiting 30000ms` **但从不发起连接尝试**（观察 3 轮 waiting 零连接）——SSE 通道假活（REST 正常），直到进程重启
  - 证据：21:33-21:35 logcat（`Reconnecting in 30000ms (attempt #6)` 后只有 waiting 无 attempt）
  - **2026-08-18 根因修正（比登记定性更深一层）**：不是"waiting 不重连"——冷却到期后**会**重连，但 0 事件连接（无心跳服务器 40s 内零事件）在 collect 首事件前超时 → `consecutiveTimeouts` 从未清零仍 ≥ 阈值 → 立即再次 enterCooldown → 「5min 冷却 → 40s 尝试 → 5min 冷却」永续（SSE 仅 ~12% 时间在线）
  - **2026-08-18 修复完成（bd04d060）+ 模拟器全周期实证 ✅**：enterCooldown() 清零 consecutiveTimeouts（冷却代价付清后重新计数）+ 两处冷却日志先读计数再 enter。验证：飞行模式 5 连败 → 冷却 → 网络恢复 → 冷却到期**立即真正重连**（Pre-load 200 + Recover 50/50 + Connected）→ 后续 40s 周期正常循环无再进冷却；1694 全量单测全绿
  - 关联：#142 修复引入的 hasConnectedOnce/recoverMessages 链路（8bbcb216）；#108 的 40s 超时防护

- [x] **beta-17595 服务器契约适配批次——4 子项全部闭环（00fbdda3 心跳修复 + 32765cf6 + 排查定性）** `compat` `sse`
  - 服务器从 next-17403 → beta-17595，本次验证实证的缺口：
    1. ✅ **SSE 心跳（勘误+已修 00fbdda3）**：原定性「无心跳帧」错误——服务器每 15s 发标准 `: heartbeat` 注释帧（curl 100s 实测 7 条）；真根因是 SseClientV2.readSseFrame 帧级聚合把纯注释帧在函数内吞掉永不返回，外层 40s 计时器看不到进展 → 空闲必断连循环。修复：注释帧边界返回空帧标记（外层既有 isEmpty 分支刷新计时）。验证：空闲 150s 零断连 + 事件流正常接收
    2. ✅ **/api/project 只返回 canonical** → 轮询已修（32765cf6）；Project.displayName getter 本就有 canonical 回退链（name→worktree→canonical→path→id，SseEvent.kt）——UI 无需改
    3. ✅ **prompt modern 契约 400** → App 已有 legacy body retry（`modern 400 -> legacy body retry status=200`）
    4. ✅ **消息 content 内联格式**（{type,text}）→ V2Mappers.contentArray.mapNotNull 按类型计数派生 part id（#109 契约），天然兼容；501 条会话历史连续渲染实证（分页深翻+回滚全程无丢失）
  - 工时：实际 ~2h | 涉及：SseClientV2 / V2Mappers / V2ApiClient

- [x] **提问卡三态模型 + E2E-H 结案 + 双端同步复验（本次验证通过项归档）** `ui` `sse`
  - 三态模型（勾选/parked/删除）✅：Q1 自定义 Mango 保存勾选（像素 221,222,237 accent wash）→ 提交载荷 [[Mango],[Red,Green]] 恒 ≤1 互斥 → agent 复述确认收到
  - E2E-H ✅ 结案（假象确认，见上文章目）
  - 新增B 双端同步 ✅（curl 模拟设备 A → B 端 6s 内卡片消失）
  - E2E-D 404 探测 ✅ 精确重现（行为与定性一致，P2 待修不变）
  - 新增A SSE 路径 ✅ + REST 兜底两缺陷修复闭环（32765cf6，12 分钟 0 请求 → 22s 8 请求 + 列表标记出现）

### 遗留观察项（非本次修复引入）

- [x] **#143 V1 发送后"用户消息不显示"——2026-08-15 判定为误报（验证方法缺陷）** `v1`
  - 现象：V1 E2E 中发送 v1_regression_e2e_final_check 后 UI dump 找不到该文本 → 误判"消息不显示"
  - 真相（三重误判）：① Markdown 将 `_regression_e2e_` 等下划线段渲染为斜体 → 文本变 "V1 regression e2e final check"，grep 原文落空；② agent 回复本身调用了 question 工具（SINGLE 卡片 Pass/Fail），气泡形态与预期文本回复不同；③ 视口采样偏差（uiautomator 单点 dump 未覆盖消息位置）
  - 复核证据：快速导航（Room 全量 user 消息）中该消息在列；点击跳转后消息与回复完整渲染（10:57 时间标签 + question 卡片 turn）；Room/seed/NetTrace 28 条消息全链路一致
  - 结论：V1 全链路正常，无 bug；教训：E2E 文本断言需考虑 Markdown 转换（下划线→斜体）与工具卡片形态

## 2026-08-19 模拟器代验收批次（用户授权"待验收项模拟器搞定"）

> 环境：Pixel6_Android36（dev 0.3.1-dev.15 @ master a199fdd6）+ V2 服务器 0.0.0-beta-17595（10.0.2.2:4199）。
> 证据目录：/tmp/verify-acceptance/（截图 ab_*/b_*/p2_*/p3_*/p4_*/p5_*/p6_* + uidump + dumpsys + DataStore 副本 + 像素脚本）。
> 本批完成 6 项待验收代验 + 2 项部分更新（详见各条目回写）。

- [x] **新增A/新增B/E2E-F/提问卡M3/#91 全部代验收 ✅；#122 D2-25 / #79 P0 部分更新**（证据回写至各条目，此处不重复）
  - 新增A：dumpsys 通道铁证（opencode_questions mImportance=4 声光振动）+ 通知栏实拍（问题 · 会话名）+ 列表标记三行 uidump
  - 新增B：curl 答 form → SSE QuestionReplied → 卡片折叠 Asked，端到端 ≤1.5s，agent 复述确认
  - E2E-F：删除自定义 Emacs 后载荷 [[Python],[VSCode]] 零泄漏；选中色单/多选像素一致 (221,222,237)
  - M3：多状态画廊（双问题卡/选中态/三态自定义/折叠历史）vision 审查通过
  - #91：burst 30 次（并发初始化）→ 稳态 20s 零请求
  - #122 D2-25：权限卡 → 总是允许 → 规则落库 → 新询问 [auto-approve] 7ms 自动应答全链路
  - #79 P0：飞行模式 + force-stop → Room 渲染工具卡摘要形态，FATAL=0

- [x] **新增 P3：提问通知正文显示触发 prompt 而非问题文本——已修 2d9636bc** `ui` `notification`
  - 现象（2026-08-19 代验收新增A时发现）：通知正文 = 会话最后一条用户消息（原始 prompt "Use the question tool to ask me: What is your favorite animal? ..."），而非问题本身（"What is your favorite animal?"）——信息密度低，用户需读完整 prompt 才知道被问了什么
  - 根因：AppNotificationManager.showQuestionNotification（:379 附近）contentText 优先 findLatestUserMessages(sessionId,1)，questionText 仅作空回退——SSE 路径传入了正确的 questionText 但被用户消息覆盖
  - 方案：正文改优先 questionText（问题文本短且直接），用户消息可留作第二行或弃用；涉及 15 语言无需新键
  - **2026-08-19 修复（2d9636bc）✅**：正文优先 questionText，缺失回退用户消息（REST 兜底路径）再回退通用文案。E2E 铁证：dumpsys `android.text=String (What is your favorite season?)`——问题文本而非 prompt 全文；测试 form 已答清
  - 工时：~30min | 难度：低 | 涉及：AppNotificationManager | 优先级：P3

- [x] **新增 P3：CodePath 点击「无反应」——已修 e26d0c35（定性勘误：实为 P1 文件读取契约全链路断裂）** `ui` `markdown`
  - 现象（2026-08-19 D2-08 E2E 顺带发现，两轮独立复验一致）：assistant 消息中的文件路径 span（`app/build.gradle.kts`，已正确渲染 monospace+下划线+链接色）点击后无任何可见反应——无浏览器/无文件查看器/无 toast/无崩溃（进程存活）。链接（http/https）同场景点击正常打开浏览器
  - 根因方向：clickableMarkdown 对 CodePath 走 `uriHandler.openUri(item.text)`——裸相对路径非合法 URI（无 scheme），系统隐式 Intent 解析失败被静默吞
  - 方案：CodePath 命中改为打开应用内文件查看器（FileViewer 路由，参照消息附件的查看链路）或至少 toast 反馈；与 ChatMessageList 既有的 @文件点击行为对齐
  - **2026-08-19 修复（e26d0c35）——定性勘误 + 根因升级 P1**：主会话亲自复现发现点击实际有 Snackbar「文件未找到」（子代理漏看瞬态提示）；真根因是 **beta-17595 文件读取契约双重断裂**：① 端点为 GET /api/fs/read/<path> 通配符段（旧 ?path= 查询参数恒 500，curl 双形态对照实证）；② 响应为裸文件内容（无 JSON 信封，旧解析必抛）。**影响面远超 CodePath：文件查看器/existence 检查全链路在此部署版静默失效**。修复：通配符拼接（按段编码保斜杠）+ 裸文本回退解析（JSON 信封优先兼容老服务器）。E2E 全闭环：tap 代码路径 → 文件查看器打开渲染 build.gradle.kts 全文（修复前 Snackbar 报错）；单测 +3 全量绿；测试会话已删
  - 工时：~1h | 难度：低 | 涉及：ClickableMarkdown/文件查看导航 | 优先级：P3（点击是死路但无害）

- [x] **beta-17595 服务器兼容发现批次——2026-08-21 结案（① 已修 + ②③④ 归档方法论）** `compat` `upstream`
  - ① **prompt body agent 选择失效**：App 的 agent 切换（flat body agents 数组）在 beta-17595 被服务器忽略——curl 四种路径实测（agents 数组 / 顶层 agent 字段 / @mention 文本 / modern 包裹契约 400）全部仍用 build agent。**App 侧影响：模型选择器里的 agent 切换在此服务器上无效**（next-17403 上曾工作）。OpenAPI 有 POST /api/session/{id}/agent 端点（未实测）——待验证后改走该端点
  - ② **agent permissions 配置的 ask 规则不生效（默认评估链）**：用户配置 general/general-fast 的 git commit/push ask 规则从未触发（agent 直接执行）；build 加 ask 规则 + 重启服务器仍 allow——**规则顺序 last-match-wins（ask 必须放在 "*"/"*" allow 之后才生效）**，且需经 POST /api/session/{id}/permission 评估端点触发的路径行为一致。用户现有配置中 git ask 规则全部排在 allow 之后（顺序正确）但仍未触发——疑似工具调用路径不经该评估（仅 API 端点评估）
  - ③ **权限询问 E2E 触发方法论**：beta-17595 默认配置下无自然权限询问；可靠触发 = 临时加 ask 规则（放 allow 后）+ curl POST /session/{id}/permission {action,resources} → effect=ask + SSE PermissionAsked（App 正常弹卡）。测试后配置已还原（diff=0 + 重启复验探针 allow）
  - ④ **服务器 saved permission 规则**（GET /api/permission/saved）含历史 always 应答累积（shell/echo *、git commit * 等 6+ 条 global 规则）——App "总是允许"的 always 应答在服务器侧持久化；DELETE /api/permission/saved/{id} 可清理
  - 上游候选（并入 #146 候选池）：agent 切换端点契约（prompt body agents 语义 vs 独立端点）；agent permissions 评估链是否覆盖工具调用路径
  - 状态：`[ ]` ① ✅ **已修 76f337f5（2026-08-19）**——端点验证可用（204 + session.agent 持久变化 + SSE session.agent.selected 广播，带/不带 directory 头均生效）；实现 switchAgent（promptAsync 发送前显式切换，同 switchModel 模式）+ **resolveAgentId 显示名→id 解析**（E2E 发现的真 bug：listAgents 映射 name "Plan"，端点按 id "plan" 区分大小写匹配，原样发送 → execution.failed "Agent not found"；目录大小写不敏感双向匹配 + @Singleton 缓存 + 失败自适应重拉）。E2E 全链路：UI 切换 → `agent=plan (from=Plan)` → execution.started→succeeded → 服务器回复 agent=plan；②③④ 已归档为方法论。**2026-08-21 结案勾选**（唯一 App 侧缺口①已修；服务器现已升至 beta-17728）

- [x] **新增 P2：StrictMode 首轮走查发现——主线程 IO 批次（165 条，2026-08-19 c3078b41 采集）——①②③全部终态（48ae416f + ae40b014）** `perf` `main-thread`
  - 背景：#106-2 接入 StrictMode 后首轮模拟器走查（导航/滚动/进会话/设置，19:23-19:27）捕获 165 条违规，100% 主线程（PID=TID）。证据 /tmp/verify-strictmode/violations.txt
  - ① **SecretCipher 主线程 AndroidKeyStore 解密 125 条（76%）** ✅ **2026-08-19 已修 48ae416f**：解密记忆化（ConcurrentHashMap，密文为 key，encrypt 清空/失败不缓存）+ servers flow flowOn(IO)。同路径走查对照：SecretCipher 违规 **125→0**，总违规 **165→15（−91%）**，90s+20s 会话停留窗口零违规（上轮每 ~5s 爆发），零 FATAL 无回归（证据 /tmp/verify-p2cipher/）。原描述：SecretCipher.decrypt（SecretCipher.kt:62）← ServerDataStore.withDecryptedPassword（ServerDataStore.kt:184）——CustomViolation 75（KeyStore update/finish/abort）+ DiskRead 25（getKeyEntry/createOperation）+ DiskWrite 25。**不止启动期：会话界面存活期每 ~5s 周期性爆发一组 6 条**（密码被反复解密）——低端机聊天 jank 潜在源。方案方向：解密结果内存缓存（限时）/ withDecryptedPassword 调用方全部 IO 化/解密一次后持有
  - ② **启动期语言镜像 SharedPreferences 读** ✅ **2026-08-19 已修 ae40b014（5→0）**：① OpenCodeApp.attachBaseContext 后台预热 locale_prefs；② LocaleUtils.readStoredLanguagePermitted 统一入口——allowThreadDiskReads 显式声明 #136 设计读意图（防设计读永久刷噪音）；③ MainActivity:121 包裹外重复直读一并收口（E2E 复验发现）。终验：getStoredLanguage/applyAppLanguage 帧 logcat 全程为零；系统 locale=en-US 下界面仍中文（镜像生效强证据）阻塞 DataStore 读——单次最重 187ms 主线程启动延迟。方案方向：语言镜像（#136 reconcileLanguageMirror 已有）attachBaseContext 优先读同步快照（SharedPreferences 镜像）
  - ③ **启动期其他一次性读盘** ✅ **2026-08-19 已修 ae40b014（3→0）/ 评估不修（NetworkModule）**：crash_notify prefs 读移入 IO 协程（3→0）；NetworkModule.provideHttpClient 类加载 ZipFile 读（Ktor/OkHttp/slf4j ServiceLoader，×8-10 IO 粒度波动）评估不修——一次性框架行为，runBlocking 包裹 DI provider 属反模式，维持观察
  - 泄漏类（Closeable/Activity/SqlLite）0 条——VmPolicy 检测器无信号。**条目终态：同型走查 165 → 10（−94%），余量 100% 为有意保留的 NetworkModule 框架类加载；两次冷启动复验无竞态抖动**
  - 工时：①②③已完成（48ae416f + ae40b014） | 难度：中 | 涉及：SecretCipher/ServerDataStore/LocaleUtils/MainActivity/OpenCodeApp | 优先级：P2 ✅ 2026-08-19 完结

- [x] **新增 P3：Android Lint 存量清偿——已完结（errors 60→0：67dc50e4 散点 + 7a92fd1f 批量）** `lint` `tech-debt`
  - 背景：#106-6 开发版 lint 门禁——新增 error 卡发版，存量 59 errors/163 warnings/11 hints 由 app/lint-baseline.xml 豁免（不阻塞但持续可见）
  - 构成：**LocalContextGetResourceValueCall ×53**（LocalContext.current 资源读取 → stringResource 化批量重构，量大需专场）；RestrictedApi ×3（MainActivity.dispatchKeyEvent——按键分发有意使用，需 @SuppressLint 或重构）；SuspiciousIndentation ×1（NavGraph.kt:193）；SuspiciousModifierThen ×1（AmoledCard.kt:126 隐式接收者捕获）；JavascriptInterface ×1（CodeWebView.kt:227——**疑误报**：SelectionBridge 两方法均有 @JavascriptInterface 注解，源码目检确认，lint 对 apply 作用域解析混淆）
  - 顺带已修：DebugLogger.flushMediaStore NewApi 误报（@RequiresApi(Q)，60→59）
  - 方向：53 条批量场次优先；散点逐个判断真伪（误报 @Suppress + 注释说明）
  - **2026-08-19 第一批（67dc50e4）：散点 6 条全消，59→53**——① NavGraph 缩进错乱真实修复；② AmoledCard 冗余 then() 等价简化；③ RestrictedApi ×3 有意使用（终端按键拦截）@Suppress+理由；④ JavascriptInterface 误报（SelectionBridge 已注解）@Suppress+理由。baseline 重生成 + 门禁复跑通过 + 单测绿 + 冒烟导航 FATAL=0。**剩余 53 条 = 100% LocalContextGetResourceValueCall（LocalContext 资源读取 → stringResource 化）**
  - **2026-08-19 第二批（7a92fd1f）：53 条批量全消，errors→0 完结**——10 文件 lambda/回调内 context.getString hoist 组合层 stringResource（带参格式串 hoist 模板 + .format() 保留 locale 占位符次序）；ChatScreen 遵循编辑协议；lint 重生成 **0 errors**（baseline 仅剩 warnings）+ 门禁通过 + 单测绿 + E2E 文案铁证（建目录 Toast '已创建：~/srt2x' 含路径参数格式化正确 / /undo snackbar / 工具卡复制 / 诊断屏，FATAL=0，证据 /tmp/verify-strres/）
  - 工时：~1-1.5d | 难度：低-中 | 优先级：P3（门禁已开，存量只影响报告噪音）

- [x] **新增 P2：空会话中权限卡/提问卡不渲染（ChatEmptyState 整块吞掉 ChatMessageList）——已修 a4862397** `ui` `chat`
  - 发现（2026-08-19 权限卡 E2E）：空会话收到 PermissionAsked——App 事件链全部正常（SSE recv → dispatch → PermissionEventHandler pending，logcat 铁证），但卡片不显示。根因：ChatScreen 内容分支 messageState.messages.isEmpty() && !isLoading → ChatEmptyState **替换**整个消息区，而权限/提问卡是 ChatMessageList 的 LazyColumn item——空会话永远渲染不到
  - 复现：新建空会话 → 服务器发出 permission.asked（或 question）→ 无卡片；同会话注入任一消息后触发 → 卡片正常渲染（绕过实证）
  - 影响：真实场景（用户新建会话发首条消息、agent 首轮就要权限/提问）卡片不可达——permission 3 分钟无人应答超时
  - 方案：空分支条件收紧（有 pendingQuestions/pendingPermissions 时仍走 ChatMessageList）或空态与卡片区叠加渲染
  - **2026-08-19 修复（a4862397）**：空态分支条件收紧（pending 非空走 ChatMessageList）。E2E 双向验证：空会话挂起 ask → 卡片渲染 → 拒绝 → 空态正确回归；live 触发（原始失败路径）同验证 + 非空会话回归。**顺带修复 auto-approve 空名伪命中**：live ask 2ms 内被吞的根因是上轮 always 确认在空名 ask 上存了 toolName="" 规则（评估端点事件不带 permission 显示名）——savePermissionRule 空名守卫 + matches 双端空名防御（历史遗留规则即刻失效，无需迁移），单测 +2。证据 /tmp/verify-permcard/emptyfix_*.png
  - 工时：~2h | 难度：低 | 涉及：ChatScreen 空态分支 | 优先级：P2 ✅ 2026-08-19 完结

- [x] **新增 P3：App「自动允许所有权限请求」开关测试后遗留 ON——已完结（备忘目的达成）** `process`
  - 发现（2026-08-19 权限卡 E2E）：2026-08-19 上午验收明确关闭过该开关（DataStore 0x00 验证），但本轮 E2E 时又为 ON（毫秒级自动应答 always 导致卡片不显示，排查消耗两轮）。可能是下午某 E2E 子代理重新打开未还原
  - 处置：本轮已重新关闭。登记目的：后续 E2E runbook 若依赖权限卡显示，需先检查该开关状态（设置 → 自动允许所有权限请求）
  - **2026-08-19 完结**：备忘目的已达成——空会话卡片修复轮与终局回归均按此检查（开关保持 OFF）；且根因侧的 auto-approve 空名守卫已随 a4862397 落地，误应答面收窄
  - 工时：已处置 | 优先级：P3（流程备忘）✅ 完结

## 终局回归记录（2026-08-19，全部可开发项完成后）

- **D0 静态全绿**：compileDevDebugKotlin ✓ / 全量单测 --rerun ✓ / lint **0 errors**（门禁 no new issues）✓ / i18n-check 628 键 ×14 语言 ✓ / androidTest 编译 ✓
- **能力域 A（启动/连接/列表）**：冷启 2436ms（2 次取优）/ 热启 115ms / crash buffer 0B / dropbox 本次窗口零新增（历史 7 条 08-17 并发构建损坏签名与本次无关）/ Accept_AB 已连接 API v2 / 列表 12 会话 / 搜索实时过滤 ✓（CJK 注入受模拟器 LatinIME 限制，AB 代测同路径）/ 滚动 gfxinfo Janky 74% p50=73 p90=97 p99=117ms（debug+软渲染基线）✓（证据 /tmp/regress-a/ 4 截图）
- **能力域 B（聊天发送流/控制/草稿）**：草稿保存恢复 ✓ / 发送即显+输入清空 ✓ / 流式回复 ✓（服务器侧全文 banana apple cherry）/ 停止生成 ✓（截停后空 assistant）/ 模型切换 ✓（model-switched 事件）/ FATAL=0 / 测试会话已清理（证据 /tmp/regress-b/ 4 截图 + REST 双侧验证）
- **能力域 C（卡片/终端）**：工具卡/权限卡/文件查看器/Markdown 渲染——当日早前轮次证据（/tmp/verify-regex/ 21 截图、/tmp/verify-permcard/、/tmp/verify-dm/）；**终端模式本轮实测**：更多选项→终端 进入（黑面像素+键盘 overlay+TerminalDelegate 日志+IME）→ BACK×2 退出正常（/tmp/regress-b/06_terminal.png）
- **全程 FATAL=0、crash buffer 0 字节**；服务器配置 diff=0 复验


- [~] **新增 P3：Room 缓存行 tokens 持久化缺口（token 图标修复的残留，2026-08-19 f37f482d 顺带发现）** `data` `storage`
  - **2026-08-20 修复完成（c71ac4ec，方向 A）**：upsertSsePriority 合并时 CAS 内对比 assistant 行 tokens/cost（null→值 视为变更），变更行经既有 persistSseUpdate→persistQueue 增量落库；节流=变更检测本身（值未变 0 写库；SSE_PRIORITY 仅 REST 快照触发，不在 48ms delta 路径）。新增 4 测试（null→值触发/值未变不重写/无变化行 0 写/流式整行写不增加），全量 1756 绿。**真机 E2E 复验 PASS（2026-08-20，/tmp/tokverify/）**：Room 直查 assistant 行 tokens 落库 44/45（唯一例外为无正文元数据空壳行，tokens:null=0）+ payload 摘录 tokens:{input:130656,...}；落库链路日志完整（seed 107 → L3 REST refresh → reconciled → Room）；冷启 tap+0.65~0.9s 顶栏 context 圆环已在位（双视觉模型 + 像素检测互证）；历经冷启+离线循环后 13:01 终验仍 44/45 稳定；logcat 19.1 万行 FATAL=0。附带观察：离线时顶栏圆环隐藏系 contextWindow 依赖 /api/provider 会话级 REST（ChatViewModel.kt:568-571 已声明可接受）——与 tokens 缓存无关；若期望离线也显示圆环需将 contextWindow 纳入本地持久化（见下条 P3）
  - 现象：V2MessageMapper 补 tokens 映射后（f37f482d），重进会话 UI 图标恢复（REST→内存→UI 链通），但 Room cached_messages 的 assistant 行 tokens 仍为 null（新产生的消息实测同样）
  - 链路分析：REST refresh 走 upsertSsePriority 只更新内存（_messages/_parts），不触发 Room 重写；Room 写入仅在 SSE persistSseUpdate（handleMessageUpdated/delta flush）窗口——重进后的 REST 数据不落库
  - 影响：冷启动/离线瞬间统计图标短暂缺失（REST 成功后立即恢复）；在线使用全程可见。低优先级
  - 方向：SSE_PRIORITY 合并后对 tokens/cost 变化的行触发增量 persist（或 REST refresh 后 persist 变更行）
  - 工时：~2h | 难度：中 | 涉及：MessageEventHandler/MessageStore | 优先级：P3

## 2026-08-20 第二轮扫描批次（压缩 snackbar 复活 + 终端失败 snackbar 竞态 + 回归五项）

- [x] **压缩完成 snackbar 失效（V2 session.compaction.ended/delta 未映射）——已修 ac7c046e + 5d534dd1** `sse` `v2`
  - 发现（2026-08-19 会话操作回归轮）：beta-17639 细粒度压缩事件为 started/**delta**/**ended** 三段，不再发 legacy `session.compacted`——`.ended` 落入 Unknown（logcat 实证 `Unhandled session.next event: session.compaction.ended`），服务器压缩成功但「会话已压缩」snackbar 永不显示；auto-compaction（无 HTTP 回调兜底）的进行中横幅也会永久停留
  - 修复（ac7c046e）：`.ended` → `SseEvent.SessionCompacted`（"服务器真实完成"既有语义链：compactedSessions → snackbar+刷新；EventDispatcher 跨 handler 新增 endCompaction 终结横幅）；`.delta` → `SessionNext(CompactionDelta)`（消灭 Unhandled 噪音）。**刻意不**映射 `SessionNext(CompactionEnded)`——那是 ChatViewModel.compactionNotifier 合成注入的类型，复用会让"本地幂等结束"冒充"真实完成"触发 premature snackbar
  - 契约修正（5d534dd1）：E2E 抓帧实证 delta 增量文本在 **`text`** 字段（V1 域事件用 `delta`）——text 优先 + delta 兼容回退
  - E2E 三路铁证：logcat `[recv] SessionCompacted` + `[dispatch] → SessionEventHandler` + Unhandled 计数 0；视觉 run_15 snackbar「会话已压缩」逐字；final.png「上下文已压缩」分割线 + 无卡死横幅（证据 /tmp/compact_*.png）

- [x] **终端连接失败 snackbar 被 scope 取消竞态——已修 f3cc5fb7** `terminal` `ui`
  - 发现（本轮回归⑤）：飞行模式断网 + 开终端：数据层失败链完整触发（ENETUNREACH → Failed to create tab → onResult(false)）、终端模式正确自动退出，但「终端打开失败」snackbar **从未显示**（双截图序列 md5 相同 + vision 确认无提示条）
  - 根因：ChatTerminalView 失败回调在本地 `rememberCoroutineScope` launch showSnackbar 后立即 `onTerminalModeChanged(false)`——视图随 isTerminalMode=false 离开组合（ChatScreen when 分支），排队中的 snackbar 协程被 scope.cancel() 杀死
  - 修复：新增 `onConnectFailed` 回调参数，snackbar 展示移交 ChatScreen 存活 scope（文案 hoist 同步迁移）；抽屉内重连/新建 tab 两条失败提示保持本地 scope（该路径视图保持组合，语义正确）
  - E2E 复验（修复后重装 APK + SIGSTOP 监督进程制造确定性停服窗口）：logcat `ECONNREFUSED → Failed to create tab` + 视觉帧 5 snackbar「终端打开失败」逐字 + 终端自动退出回聊天视图（证据 /tmp/tv2_run_*.png）
  - 环境备忘：opencode serve 被 `opencode2 -c`（TUI）监督秒级拉起——`pkill -f` 会自匹配误杀 shell；确定性停服 = SIGSTOP 监督者 + `pkill -9 -x opencode2.exe`（测后 SIGCONT，服务器自愈 pid 3343989）；模拟器 airplane-mode 对连接池半死 TCP 可能静默挂起（15s connect timeout）而非立即失败，停服（RST）才是可靠触发

- **回归②③④⑤记录（2026-08-20 扫描清单收官）**：②语言往返 zh→en→zh 7/7 PASS（7 组文案对照 + prefs 直读 + 两次 Activity relaunch 日志三维互证；发现：应用设置真入口是主页顶栏齿轮，底部"设置"tab 是 MCP 服务器管理——已写进子代理任务书防重复踩坑）③AMOLED 权限卡 PASS（像素断言纯黑 RGB(0,0,0) 51.6% + 视觉层次确认 + 拒绝链路送达服务器；Compose Switch 的 uidump checked 不可信 → DataStore proto 解码为权威）④空会话提问卡为陈旧项（a4862397 已于 08-19 验证完结）⑤即上述终端 snackbar 竞态（发现→修复→复验闭环）。⑤执行中还发现 E2E 离线冷启动被连接入口挡住（与 V3 走查记录一致，架构使然）

- **终局回归（第二轮，2026-08-20 02:30）**：全量单测 --rerun EXIT=0 全绿（含终端修复）；发送流 curl prompt → 流式渲染「收到」+ FSM 完成 + 输入恢复 ✓；token 环无回归（顶栏 8% 文本在位——f37f482d 修复经受住压缩+终端两轮改动）✓；FATAL=0 / AndroidRuntime E=0 ✓

- [~] **新增 P3：离线态终端打开的 sessionDirectory=null + 输入框层级缺失（终端失败路径 E2E 的次生观察，2026-08-20 登记）** `terminal` `edge-case`
  - **2026-08-20 观察①已修（de96758c）**：TerminalDelegate 门放行后 directory 仍空时经 reloadDirectory 兜底重拉（ChatViewModel 注入 getSession → fillDirectoryFromRetry 仅空时回填）；编译 ✅ 全量单测 ✅
  - **观察②核查定性：不可达路径关闭**——真机实证（/tmp/termverify/）：会话列表完全由服务器 SSE/REST 驱动（无本地缓存渲染），离线冷启动停在「正在连接」页**无法进入会话页**；原 E2E 观察为瞬态连接窗口偶发捕获，当前架构下场景不可达。⚠️ 观察①待用户真机验收（需真实瞬断场景）
  - 观察①：服务器不可达时 openTerminalSession 的 sessionDirectory=null（会话未加载完 directory 即空）→ createPty 以 cwd=null 发出——瞬断恢复窗口（点击时断网、请求时恢复）下 PTY 会落到服务器默认目录而非会话目录。影响极小（网络全断时请求本就失败）
  - 观察②：离线冷启动进入的会话（未完成加载）中输入框不在 uiautomator 层级；同场景会话已加载时输入框在位（tv2_final 实证）——两条件行为不一致，疑与 loading/disabled 门控有关，待下次离线路径验证时顺带核对
  - 处置：登记不展开（触发条件苛刻、无用户报告）；若未来做离线体验优化一并处理
  - 工时：~1h | 难度：低 | 涉及：TerminalDelegate / ChatScreenBottomBar | 优先级：P3

## 2026-08-20 堆积消息/TODO 功能批次（发送按钮 busy 态交互设计定稿实现）

- [x] **堆积面板删除后列表残留被删行——已修 be3a0cc5** `queue` `ui`
  - 发现（E2E 阶段 1 步骤 9）：面板删除一条后 tab 计数已变「堆积 1」但列表仍渲染两行（/tmp/q1_22.png、q1_23.png 为证）
  - 根因：StackedList 用「本地镜像 order + LaunchedEffect(queue) 同步」模式——queue 变化要等组合完成后的 effect 运行才回写镜像，存在陈旧窗口
  - 修复：渲染源改为 dragOrder ?: queue——非拖拽时直接渲染 Room 流（零残留），仅拖拽期间持有本地副本
- [ ] **新增 P3：面板开关期间 a11y 树偶发只剩遮罩节点（E2E 阶段 1 观察，2026-08-20 登记）** `queue` `ui` `a11y`
  - 现象：堆积面板一次开/关循环后 uiautomator dump 只剩「关闭工作表」节点，数秒后自愈；未见用户可感知影响（触摸交互正常）
  - 处置：登记观察（模拟器长时间运行后 uiautomator 自身劣化先例见 TaskSheet 2026-08-16 记录）；真机复现再升级
  - **2026-08-20 真机定向复现尝试：11 轮零复现**（houji：5 常规节奏开关 + 6 连打开关，每轮 dump 树均完整 32.9KB、入口节点在位、无遮罩-only 状态）——支持「模拟器 uiautomator 自身劣化」假说，维持登记不升级
  - **2026-08-21 真机首次复现（跳转 E2E 附带，fe784374/ae0d079c 复验轮）**：快速导航 sheet + 远跳（loadAround 路径）周期后 ~2s，dump 出 91 节点但**全部 text/content-desc 为空**（视觉/触摸完全正常），~15s 内自愈（后续 dump 恢复 27 文本节点）。同轮 4 次跳转仅 1 次出现（另 2 次窗口内跳 + 1 次前向跳均健康）；**对照实验：仅 sheet 开/关（不跳转）×4 采样全部健康** → 与「跳转+蒙版周期」相关性 >「sheet 周期」。定性更新：非模拟器专属，真机偶发；机制未定位（候选：全屏遮罩增删后 Compose semantics 刷新延迟）；零用户可感知影响，维持 P3 观察
  - **2026-08-21 频率探查（修复验证轮 +6 循环）**：交替前向/回退远跳 ×6（每轮 +2.5s/+10.5s 双采样）全部健康（22-29 文本节点）；两晚合计 12 次跳转 **1 次退化（~8%）**，均自愈、零用户影响。维持 P3 观察，不升级
  - 工时：待定 | 难度：低 | 涉及：PendingTodoSheet / ModalBottomSheet / JumpMaskOverlay | 优先级：P3

- [x] **新增 P3：跳转期间 nearBottom auto-load(newer) 竞态漏发 + 渐进步进幽灵 gap 空转（2026-08-21 跳转 E2E 发现）** `race` `jump` `perf` ✅ 2026-08-21 修复（双根因双修，真机红绿验证）
  - 现象（houji 真机日志 02:34:15.334→20.568）：前向远跳至最新提问，jumpToMessage 置 jumpLockActive=true 后 **+136ms** nearBottom 探针（firstVisible=0）仍触发 `auto-load newer triggered` → settle 期间 displayItems 变动 → 渐进步进卡 gap=-343 连续 **7 次无效步进**（~3.1s），靠无进展回退才 `布局稳定`（跳转总时长 5.2s vs 正常 3.2s）；最终落点正确、无 Failed、无崩溃
  - **根因 ×2（修复过程修正了最初归因）**：① 竞态确实存在——ChatMessageList 两处 LaunchedEffect（newer ~907/older ~849）的 `!jumpLockActive` 仅在 effect 启动时检查一次，collect 内不复查，漏发 loadNewer；② 但幽灵 gap 的**主因是 scrollBy 内容边界夹持**——前向跳到列表端附近，目标下方内容不足一屏，gap 物理上无法归零（step=-660 实际只滚 -317），循环空转到 5s 超时（修掉①后 -343 空转依旧，才定位到②）
  - **修复（两 commit）**：① collect 触发点复查 jumpLock（跳转结束 effect 重启会重新评估，不丢正常触发）；② JumpNavigationController 渐进循环检测 scrollBy 返回值 |实际-请求|>1px 即判定夹持，接受物理最接近位置收场（Displayed）——稳定窗口的 gap 修正对夹持位置是天然 no-op
  - **真机红绿验证**：红（修复前）前向跳 5.2s 蒙版 + 7 次无效步进；绿（修复后）同场景 1.1s 收场（日志实证「请求-660/实际-317——接受当前位置」），回退跳回归 gap 正常归零、解锁后 hasOlder 正常触发（无误伤），后续 6 循环前向跳 clamp 稳定命中 ×6 零 Failed；全量单测绿
  - 工时：1h（含根因修正）| 难度：低-中 | 涉及：ChatMessageList 自动分页两 effect / JumpNavigationController 渐进循环
  - **根因层级自查（2026-08-21 用户质询「是根因吗」时补）**：夹持修复=根因级（终止条件在内容边界物理不可满足——检测后接受即正确语义）；竞态修复当日升级为**根因级**（见下）
  - **根因完备化（同日第二轮，系统性调研后）**：① 机制定论——effect 重启是**帧驱动**（recomposition apply 时取消旧实例），snapshotFlow 发射是**快照提交驱动**（不经帧），二者排序无保证；跳转本身制造最重主线程负载恰好把窗口往危险方向拉宽（实证 136ms ≈ 8+ 帧）；「启动时闸门」构造性不健全，「触发时闸门」才是正确模式。② 修复升级——fire-time 复查从读镜像改为**直读 phase 真源**（`isJumpInProgress`，jumpTo/jumpToTask 入口同步置 Preparing，与镜像写点间纯同步无 interleaved，严密性等价）——正确的时机 × 正确的源，不再依赖 4 处人肉同步点。③ **直接证据（设计性实验，此前只有间接证据）**：loadAround 武装 hasNewer → 前向跳 ×3，rnd2 完整命中：pre-unlock probe=1（旧实例收到发射=窗口真实开启）+ skip=1（守卫真源拦截）+ TRIG=0（零泄漏），post-unlock 5 次合法加载照常（无过度封锁）；与原始红日志（02:34 probe+triggered 双发）构成同窗口有/无守卫对照闭环。rnd3 restart-won（窗口时变，符合预期）

- [~] **新增 P3（结构优化）：jumpLockActive 镜像标志应从 JumpNavigationController.phase 派生（2026-08-21 竞态根因层级分析衍生）** `arch` `jump`
  - **核心部分已完成（同日第二轮）**：两个自动分页 effect 的 fire-time 门控已改直读 `isJumpInProgress` 真源（正确性不再依赖镜像）；剩余范围收窄为纯清理——启动 key 与 B-F2 提交门控仍读镜像（后者带 2s 时窗语义，需一并设计），全部删除镜像标志后收口
  - 现状：jumpLockActive 是手写镜像（ChatMessageList 3 处写点：jumpToMessage/异步定位 effect/phase 终点收集器），已因此出过一次竞态（见上条）；镜像与真源不一致窗口 = 结构性风险
  - 方向：`val jumpLockActive = jumpController.phase.value is Preparing/Measuring/Settling`（derived state 或直接订阅），删除全部手工写点
  - 工时：~1h | 难度：低 | 涉及：ChatMessageList / JumpNavigationController | 优先级：P3

- **E2E 阶段 2+3 收官记录（2026-08-20，7/7 PASS）**：A 删除后 ≤0.3s 一致更新（be3a0cc5 修复复验；阶段 1 的「残留」定性为单帧捕获时序）｜B 手动停止零误发（红停止图标→Idle，queued message sent=0、角标保留）｜C「继续」手动放行队首 1 条（transcript+DB 双证）｜D 清空确认框→列表空+角标消失｜E TODO tab 在 beta-17639 隐藏（probe 404×2 + curl 404 互证）｜F force-stop 冷启后队列完整、空闲 15s 零 pipeline 事件（重启不自动发）｜G 附件置灰（min 像素 130 vs 27）+点击无入队。审计线：8 enqueued / 仅 C 的 1 sent——误发为零。附带登记：
- [~] **新增 P3：LeakCanary 报 OpenCodeConnectionService\$LocalBinder 泄漏（E2E 阶段 2 期间 1 个 distinct，2026-08-20 登记）** `leak` `service`
  - **2026-08-20 修复完成（d8331596，红绿验证）**：① reconnectServer 孤儿 job 取消（computeIfPresent 未命中即 cancel 新 job——条目已被 stopAllConnections 清空 = 服务已销毁）；② SSE 流 takeWhile{connections.containsKey} 守卫（条目消失即结束 collect）；③ connect() 入口 serviceScope.isActive 守卫（堵迟到重填）；④ HomeViewModel 卫生项（onCleared 清 serviceBinder + onBindingDied/onServiceDisconnected 共用 handleServiceConnectionLost）。新增 2 测试（孤儿 job 红绿验证——回退修复以 AssertionError 失败实证泄漏路径 + connect 守卫），全量 1758 绿。结构性根治（SseNotificationRouter 抽取，单例不再持 Service 引用）未做——现修复已断全部已知持有链，触发条件苛刻（60+ 分钟 E2E 才 1 distinct），按需另立项
  - 现象：dev 包长时间 E2E（两阶段 60+ 分钟、多次 force-stop/冷启）后 LeakCanary 捕获 1 个 distinct leak（LocalBinder）
  - 处置：登记观察（服务绑定生命周期既有问题，与本功能无关——堆积/TODO 未触碰该服务）；后续专门排查
  - 工时：待定 | 难度：中 | 涉及：OpenCodeConnectionService | 优先级：P3

- **E2E 附带观察两条（阶段 2+3 报告，2026-08-20 登记，均不阻塞）**：
  - ① busy 气泡菜单：点击置灰项（附件堆积）时 Popup 直接 dismiss（无 ripple 无动作）——与「点外部关闭」语义略异但无害，属 Q11 关闭行为的边缘 case；真机验收时顺带感受，不适再调
  - ② 服务器 /api/session/{id}/message 返回顺序非时间序且固定 50 条页大小——E2E 脚本断言需按 time.created 排序后取最新（测试基建备忘，已写入本批 E2E 任务书经验）

- [ ] **新增 P3：离线时顶栏 context 圆环隐藏（contextWindow 仅存内存、依赖会话级 REST，2026-08-20 tokens E2E 复验顺带发现）** `data` `ui`
  - 现象：移除网络后进会话，消息正文/统计行从 Room 完整渲染，但顶栏 context 圆环不显示——showContext 要求 contextWindow>0 且 lastContextTokens>0，前者来自 /api/provider 等会话级 REST（离线全败），无本地持久化
  - 现状定性：ChatViewModel.kt:568-571 注释已声明该隐藏为可接受行为（非缺陷）；仅当用户期望离线可见时才需做——方向：contextWindow 随会话元数据落库
  - 工时：~2h | 难度：低 | 涉及：ChatViewModel / 会话元数据存储 | 优先级：P3
## 2026-08-20 滚动卡顿深度调查批次（用户"还是卡"→ 三层根因全修）

- [~] **真机滚动仍有卡顿（用户复报）→ 系统性帧级取证定位三层根因，全部修复** `ui` `perf`
  - 用户报告（2026-08-20）：上一批修复后滑动手感仍卡（慢拖 + fling 都一顿一顿/不跟手）
  - **取证方法**：dumpsys gfxinfo framestats 逐帧分解 + Perfetto atrace（UI 线程 slice 解剖 + 主线程 busy 直方图）+ 系统 Settings 对照（同注入 246 帧 0.41% janky = 设备/注入无罪）+ Room sqlite 直查（定位 3 条 111-130K 字符巨型消息）+ 子代理 ×2 只读调查（fling 巨帧根因 / a11y 语义树方案）
  - **根因 ①：RenderReadinessRegistry 快照 Map 整表失效重组风暴**（已修 67f4209c）——flows 原为 mutableStateMapOf，读依赖是整 Map 级：每个可见消息卡片组合中读 Map（flow()/current()），而滚动期间 Map 持续被写（滚出视口 remove()、预解析 put、LRU 淘汰）→ 任一次写全卡片失效重组。trace 实证：拖动期 Recomposer 单帧 23-26ms、一次 9 个 scope 成批重组。修复：ConcurrentHashMap + 消费端 collectAsState 订阅单 key。A/B 同场景实测：慢拖 janky 41.7%→0.88%、p95 400ms→14ms
  - **根因 ②：超长消息单 LazyItem 组合巨帧**（已修 0faa6984）——一条 130K 字符消息 = 一个 LazyItem；LazyColumn 子项滚动方向无限高约束 → 首次组合必须同步建完整棵 Markdown 树（trace 单个 Compose:recompose scope 49.7ms；prefetch:measure max 150ms——item 是预取原子单位）。mikepenz 0.43.0 无块级懒加载参数（全部重载核对）、LazyMarkdownSuccess 不能嵌套同向列表 → 唯一治本 = LazyItem 粒度分片。实现：MarkdownChunking.kt（块级分片计划 MdChunkPlan + ChatEntry + buildChatEntries）+ ChatMessageList 发射 chatEntries + ChunkedAssistantMessage 分段渲染（首段标签栏/末段统计栏、分段圆角、SelectionContainer 按 chunk）+ MarkdownContent blockRange success 槽 + 流式/刚结束 turn 不分片（recentStreamedTurnKeys 防视口 key 裂变闪跳）+ isTurnLast O(N²)→O(1) 查表（原每 assistant item 组合 subList 线性扫 rawMessages）+ 跳转索引 displayEntryStart 映射适配
  - **真机验证（0faa6984）**：验收测试会话AB（107 条含 3 条巨型）5 连发 fling 穿越巨型区：1836 帧 janky 0.27% p50=6ms p95=9ms p99=30ms（修复前 p50 61-73ms、400ms 巨帧、fling ~120ms 早死）；LEAP total=94（+35 items = 分片生效）连续翻越 chunk、RESIZE=0；视觉复核分段气泡无接缝/无重复头部；全量单测绿
  - **根因 ③（环境因素，非 App 缺陷）：GKD 无障碍服务对 Compose 的专属税**——用户真机常开 GKD（跳广告）。实测：GKD 开时聊天屏 p50 23-77ms（语义三件套 getAllUncoveredSemanticsNodes 219ms/8s + checkForSemanticsChanges 172ms + sendAccessibility...Events 143ms，最大帧 110-150ms）；系统 Settings（View 体系）同条件 0% jank——此税 Compose 专属（并行语义树 diff+派发）。已试 MessageBubble semantics(mergeDescendants) 收益噪声级（~10%）且流式期有 merged config 整气泡重建隐患 → 放弃（stash 已丢弃）。**无低风险 App 内修复**；结论：开着 GKD 的用户群体感知上限受限，为已知环境因素
  - ✅ 用户验收通过（2026-08-20）：三轮修复 + isAtBottom 下沉后的 devRelease 真机复验——"十分丝滑"（用户原话）。GKD 关闭状态；开 GKD 场景因服务已长期关闭未测，如重开且卡顿回归参照根因③结论
  - **基建沉淀**：/tmp/perf/*（frameparse.py 逐帧分解、phases.py 相位分解、perfetto trace-config + base64 直装法、drag/fling 场景脚本）+ 子代理报告（fling 根因含库源码核对路径 /tmp/mdn-src、a11y 备选方案评估）

- **a11y 子代理报告附带发现（2026-08-20 登记）**：
  - [x] **P3：AssistantTurnBubble.kt 疑似死代码（全库无调用点）** `refactor` ✅ 2026-08-20 已删（48fbd97f，全库 grep 复核含 test 零调用）
  - [x] **P3：clickableMarkdown 的 CodePath 点击仅 pointerInput——TalkBack 不可达** `a11y` ✅ 2026-08-20 补 semantics onClick（9bb4a537，节点中心定位）——TalkBack 实机走查待用户验收
  - [x] **P3：CompactionCard combinedClickable 空 onClick——朗读为可点击但无动作** `a11y` ✅ 2026-08-20 改 pointerInput 长按 + semantics 自定义动作（9bb4a537，标签复用 chat_revert）——TalkBack 实机走查待用户验收
  - [ ] **P3（降级 2026-08-20：GKD 已长期关闭，主收益消失；仅 GKD 用户重新开启时才有价值）：长文本 Part 级 semantics merge** `perf` `a11y`
    - 唯一有机制优势的 GKD 税缓解变体：失效 containment（流式只重建单 part 而非整气泡）、标签栏/statsBar 保持独立节点。仅已完成长文本 part、流式 part 不加；交错 A/B 验证——GKD 关 p50 回退 >2ms 或 p95 改善 <15% 即 abort（气泡级 merge 实测仅 ~10% 且有流式隐患，Part 级预期相近）
    - 工时：~3h | 难度：中 | 涉及：PartContent/MarkdownContent | 优先级：P2
  - **文档建议（零代码风险）**：FAQ/README 注明 GKD 用户可将本 App 加入排除规则（gkd.li/guide/faq 规则级排除）或使用时暂停服务——直接消除查询侧主成本；随下次文档批次落地





## 2026-08-20/21 第六轮：四路竞态审计整合修复（叠放/直角/滚动卡根因批次）

- **方法**：用户判断正确——『几率型 bug 一般都是竞态条件』。四路子代理并行审计（跳转链路/结构变更×滚动/测量渲染/状态并发原语，报告 /tmp/perf-round4/*.md 四份共 ~1080 行）+ 主会话自查交叉，共产出 30+ 发现，其中与本症状直接相关的根因 7 项全部修复：
  - `bf3d1cf7` 【F1/F2/F3】pendingChunkPlans 锚点化（索引陈旧根治）+ 视口内防线 + 门控回退 + RaceProbe 埋点（--ez debug_race true，release 可用，TAG RaceProbe：JUMP/ENTRIES/CHUNK/VIEW 四类事件）
  - `1924d9db` 【四路整合】① jumpTo/jumpToTask 代际管理（cancelPreviousJob——旧协程含稳定窗口立即失效，写穿防护）② resolveLazyIndex 陈旧闭包（remember 无 key 捕获首帧——rememberUpdatedState 三件套修复）③ streamingMsgId 全局条件改 turn 粒度（全表 key 双向翻转根除）④ freshDi<0 反向小 bug（注释丢弃实际提交）⑤ 门控直读 phase.value（帧滞后洞）⑥ 预解析排除流式 turn（部分 AST 永久截断防护）⑦ 稳定窗口 1.5s→900ms+用户滚动即让位+gap>8f 才修（杀滚动卡主因）⑧ RaceProbe lazy lambda 化
- **机制解释链（审计钉死）**：叠放=视口内 key 裂变撞定位修正的 remeasure 竞态；『圆角变直角+标签行不可见+正文中间露出』=落点停在中段 chunk（RoundedCornerShape(0.dp) 且无标签行——中段设计如此，库源码证实锚 key 消失时 findIndexByKey 回退裸索引会走位进 turn 中部）；语义树缺节点=被跨过的 chunk 未组合；『滚动卡』=稳定窗口 scroll{}（MutatorMutex）杀死用户手势 + 双修正循环对拉。
- **真机压力回归（修复后）**：5 轮 {连跳×2 + 立即滚动×8} 组合暴力测试——全部落点正常、无叠放/无直角异常/顶部完整；滚动质量 4190 帧 5-7ms 占 97%+、无 >31ms 帧（跳转后立即滚动顺滑——稳定窗口让位生效）。
- **遗留登记**：
  - [ ] **P3：RaceProbe 复现取证待用户执行** `race`——若叠放仍出现：`am start --ez debug_race true` 后复现，`adb logcat -d -s RaceProbe` 导出（时序可直接重放：JUMP entries 数 vs VIEW keys 错位即定位）
  - [x] **P3：A-F4 反射 requestPositionAndForgetLastKnownKey** `refactor` ✅ 2026-08-21（fe784374）——跳转路径两处换官方挂起 scrollToItem（互斥锁内重定位改为块外标记+块外执行）；反射 LazyListReflection 仅留 SSE 高度补偿两处调用点
  - [x] **P3：卫生群**（D 报告 #7-11）✅ 2026-08-21（ae0d079c + 07507ae7）——① mdRegistry/JumpBubbleObserve/Ready 上报链/JPS pendingIndex·onCompleted·reset/JNC reset 全删（零读者实证）；② user 跳转预解析直通 Measuring（PartContent isUser 纯 Text 渲染，预解析纯延迟——附带性能修复）；③ RenderReadiness D-7 实例置换修复（解析前捕获 flow 实例直写，remove 后不复活、旧订阅者收得到完成态）+ update/awaitReady 死 API 移除；④ jumpPhase 订阅下沉 JumpMaskOverlay 小组件（蒙版显隐不再重组 1500 行主体）+ 时钟基统一 elapsedRealtime（门控/解锁/重定位节流/稳定窗口同基）

## 2026-08-20 第五轮：跳转悬浮叠放瞬态（低概率）+ MIUI 安装机制调研

- **悬浮叠放瞬态修复（`94f7a968`）**：用户报『一条消息浮在另一条上、agent 回复被拦腰斩断』（低概率，点好几次才复现，压力连跳未捕获瞬态）。静态机制定位：v1 门控（!jumpLockActive）在状态机终点 300ms 解锁，但稳定窗口（Displayed 后 1.5s 静默监控 + gap scrollBy 修正）仍在跑——期间 pending 分片提交使窗口边缘 turn key 裂变（remove+insert）与修正并发 → remeasure 竞态 → 单帧叠放错乱。修复：提交条件升级为『从未跳转 或 Displayed/Failed 且终点 >2s』。附带发现：JumpPhase.Idle 无生产者（reset() 全库零调用）——已用注释记录，重构候选。
- **MIUI/HyperOS 免确认安装调研（/tmp/perf-round3/miui-install-research.md，24.5KB+补充章节，23 来源）**：
  - 正门 = 开发者选项「USB 安装」（需 SIM+移动数据+小米账号认证）；无 root 无隐藏 settings 键
  - 拦截判定 = shell uid × 全新安装（覆盖 -r 同签名不弹，实测）→ **当前工作流已稳定：首装一次人工，之后永远 -r 静默**
  - 终态候选 = Dhizuku 设备所有者（adb dpm 激活，免 root 免账号常驻；代价双开/分身不可用）——仅当 houji 转专职 CI 机时上
  - docs/real-device-testing.md 待补：MIUI 安装行为差异段落（首装弹窗/覆盖静默/USB安装开关条件）

## 2026-08-20 第四轮：快速定位渲染缺陷根因修复 + 三性能项落地

- **快速定位渲染缺陷（用户真机报告：气泡不完整/非从头回复 + 『未找到任务』弹窗）——四处根因全修**：
  - `25a20535` ① pendingJumpTarget 回调路径漏分片适配（三条跳转入口唯一漏网——display 粒度 index 直传 scrollToItem，窗口内有分片 turn 时落点错位=截图主源）；② 状态机 `it.key == targetKey` 精确匹配对分片 key（t_xxx#cN）必失败→5s 超时（findJumpTargetItem 前缀匹配取首 chunk）；③ loadAround 未命中直接报错（重试一次再判）
  - `8347acd0` ④ 跳转期间 B-F2 分片提交无门控——跳转窗口扫过触发 key 裂变使已算好的 index 失效（Q33 复现落进文章 chunk 中间；补 !jumpLockActive 门控与 auto-load 同款）
  - 验证：真机连跳序列（Q25/Q26/Q33/Q35）全部落点精准，目标气泡完整置顶；FindJumpTargetItemTest 6 用例 + 全量单测绿
  - 方法论教训：视觉模型提问会被引导性措辞污染（先问『有没有问题』三个落点全报有问题，改中性事实描述后 J2/J3 实为完美）——截图取证必须用中性提问
- **Baseline Profile（`5b284b4c`）**：手工规则圈 chat UI 热路径 + Compose lazy/runtime/text + mikepenz + 协程；APK 含 assets/dexopt/baseline.prof、真机 ProfileInstaller 安装日志确认。收益为官方 ~30% 口径（本 App 实测增量需 macrobenchmark 基建，未建——诚实边界）
- **PerfMon 观察者效应（`dc57cba0`）**：FrameMetrics 按 VSYNC 去重（b/206956036）+ dropCount 记账入 HUD + PerfHudOverlay 独立悬浮窗（纯 View 直绘、独立帧流零污染；无授权回退同窗口 HUD）
- **遗留登记**：
  - [x] **P3：慢拖 ~18ms 偶发尖刺 A/B** `perf` ✅ 2026-08-21 完成——**结论：预取窗口 ahead=0 vs 1 无显著差异，假设否证，常量定 0**。三轮真机数据（houji devDebug + gfxinfo framestats，验收测试会话AB 12 次慢拖 ×3700 帧/轮）：ahead=1 → p50/p90/p95 = 7/12/14ms、≥17ms 帧 2.32%；ahead=0 → 7/12/14ms、2.22%（重复轮 2.0%）——差异在轮间噪声（±5%）内，百分位完全一致。与 08-20 PerfMon 初评（anim 相位爆发与预取无关）互证；`PREFETCH_AHEAD_SLOW_DRAG` 已定 0（分片后 edge 预取组合对慢拖帧预算是净负担）。证据 /tmp/ab18/（armA/armB/armB_repeat + 聚合直方图）。**基建坑位**：① devDebug 装包弹窗已由 `scripts/miui-install.sh` 无人值守解决；② 慢拖方向必须手指向下（500→1600）——见 real-device-testing.md E2E 纪律新增条目（曾致 0 帧误判两轮）
  - [ ] **P3：overlay HUD 真机授权走查** `dev-infra`——悬浮窗权限授予 + overlay 显示/dropCount 读数验证（代码已交付 dc57cba0，未真机走查）

## 2026-08-20 第三轮：开发用性能检测系统 + 残余卡顿闭环 + debug/release 定量对比

- **性能检测系统（090507be + f3c62ae7）**：应用内常驻 PerfMon——Window FrameMetrics 监听 + 七相位分解（input/anim/layout/draw/sync/gpu/swap）+ 滚动窗口统计（真实刷新率推导预算）+ jank 事件日志（AppLogger/Diagnostics 可见）+ 稳态采样器（窗口 over%>25 时每 2s 输出摘要+期间最差帧相位）+ HUD（am start --ez debug_perf true 开启，仅 debug）。单测 5 用例。替代外挂 gfxinfo/perfetto 管线——本轮全部定位都由它完成。
- **B-F5 修复（a6156cdf）**：isAtBottom 三处大作用域订阅下沉（Controller 暴露 State / 双 key effect 改 snapshotFlow 双值流（铁律语义等价）/ FAB 读取下沉小作用域）。实测：慢拖 anim 相位爆发 25-33ms 全消、jank 20→10 条；长消息中央 anim 25-28ms 全消、jank 16→6 条。
- **debug vs release 定量对比（PerfMon 同口径，本轮最重要结论）**：慢拖 p95 15→7.9ms、p50 7.4→6.1ms；长消息 p95 12→7.6ms；anim 相位 3.4-5.5→0.2-1.0ms；稳态超预算 ~40%→基本预算内。**debug 构建税 = p95 的 ~47%**（JIT+Compose 调试钩子+无 R8 复合）。此前的 R8-on-debug 实验只隔离了 R8 单变量（无改善），完整 release 语义差距显著。
- **MIUI 安装通道经验**：全新安装（非覆盖）一律弹用户确认（pm/cmd package/session 均拦），需用户点允许；覆盖升级（同签名 -r）静默。debug↔release 签名切换需 uninstall 重装（数据经 intent 重配）。
- **调研沉淀（/tmp/perf-round3/research.md，357 行 31 来源）**：FrameMetrics 产自 app 进程 HWUI 与 HyperOS SF 无关（可信）；回调须拷贝+去重（b/206956036）；JankStats 1.0.0 无相位分解；graphicsLayer 加 item 可实现纯平移但有条件与代价。
- **遗留登记**：
  - [x] **P3：PerfMon 观察者效应改进** `perf` `dev-infra` ✅ 2026-08-20 第四轮交付（dc57cba0：VSYNC 去重/dropCount 记账/独立悬浮窗 HUD）——悬浮窗授权真机走查待用户
  - [x] **P2：Baseline Profile** `perf` ✅ 2026-08-20 第四轮交付（5b284b4c：APK 内 baseline.prof + 真机 ProfileInstaller 安装日志确认）
  - [ ] **P3：慢拖残余 ~18ms 偶发尖刺** `perf`——F5 后残余（draw 4-8ms + input 3-5ms），量少（12 轮 10 条）。**2026-08-21：候选「预取 idle_frame」已否证**（见第四轮 A/B 条目——ahead=0/1 无差异）；如再深挖方向应为 draw/input 相位本身（release 口径 p95 7.9ms 已低于感知阈值，优先级维持最低）。工时 ~2h | 难度：中

## 2026-08-20 第二轮滚动卡顿深度调查批次（120Hz 帧预算口径重建）

- **背景**：用户反馈首轮修复后仍有三项残余症状（新消息临近顿挫 / 整体迟滞 / 长消息内卡顿）。首轮 gfxinfo 判定口径（16.7ms）在 120Hz 设备上漏报——本轮以 8.33ms 重建基准，四路子代理并行（真机测量 / UI 状态审计 / Markdown 渲染审计 / 文献调研）+ 主线交叉验证。
- **测量基础（真机 e69a99d8，会话 验收测试会话AB）**：滚动期间确认真 120Hz（doFrame p50=8.32ms，96.9% 落 8.33ms 节拍桶，118.8fps 均值）；本机 SF 三层宽松预算（WorkloadTarget 13.7ms + legacy 16.7ms）把 60% 超 8.33ms 的帧全判不 jank——系统计数器全绿是假象。
- **基线（8.33ms 口径）**：S1 慢拖普通区 >8.33ms 63.92% p99=32.3ms；S2 fling 12.53% p99=35.6ms；S3 巨型消息内 59.35% p99=25.1ms；最差帧归因 Compose:recompose 63.5%。
- **根因与修复（6 项全部落地）**：
  1. `47edb53c` 预取窗口速度自适应（慢拖1/快拖3/fling6）——PREFETCH_AHEAD=6 是 13 万字符单 item 时代设定，分片后宽窗纯剩主线程预取预算冲突
  2. `92e2855c` 超长段落空行化 + chunk 参数调优（8000/5000→3000/2500）——真实 GFM parser 实测巨型消息顶层仅 7 块（主体 129K 单 PARAGRAPH），分片对最坏消息完全失效；空行化后 blocks=8998 chunks=53
  3. `8548c3f7` 快速导航 derived 读取下沉 + 条件订阅（B-F3 重组风暴）
  4. `4cb549d5` Markdown 配置对象 remember（C-F4——53 片后每片重建 15+ TextStyle.copy 的回收）
  5. `a80b3e68` 视口内 key 裂变门控（B-F2 pending 队列）+ LazyColumn 容器每帧死回调删除（listTopY 零读取者，trace 实证 544ms/1127 次）
  6. `9bb4a537` 两个 TalkBack 可达性 P3 修复（见上方条目）
- **最终验收（vs 基线）**：S1 p99 32.29→22.38ms（-31%）；S3 >8.33ms 59.35%→53.06%、p50 9.13→8.60ms；S2 >8.33ms 12.53%→9.28%、p50 6.66→5.24ms。
- **诚实边界**：S1/S3 中位帧 ~9ms 未消除——R8-on-debug 实验（e8fe5219）证伪 debug 构建税（p50 无改善），trace 构成 = 每帧真实工作量（measure 1.3ms + recordDraw 1.1ms + RT Drawing 2.8ms + touch 0.5ms）。进一步压缩需 item RenderNode 层化（调研明确反对于全列表铺开）或 Baseline Profile（已登记下方）。
- **工具沉淀（/tmp/perf-round2/）**：scenario_runner.py（环形缓冲 ~120 帧逐轮快照 + VsyncId 去重）、merge2.py（多 PROFILEDATA 块合并修复）、frameparse.py 8.33ms 口径、scope 分析脚本、四路子代理报告（device-report / code-audit-uistate / code-audit-markdown / research）。
- **已登记未做**：Baseline Profile（app/baseline-prof.txt 手工通配 ui/screens/chat/**，官方 ~30% 代码路径加速口径）——下一批次候选。

## 2026-08-20 真机滚动稳定性批次（卡顿 + fling 下跳根治）

- [~] **真机滚动两问题：①滑过气泡卡顿 ②fling 下跳（长 agent 回复稳定复现）——已修 f03a89d5** `ui` `perf`
  - 用户报告（2026-08-20 真机）：上下滑动经过消息气泡（无论类型）卡顿；fling 下跳，长回复基本稳定复现
  - **取证（ScrollDiag 插桩 + 逐帧视频模板匹配 + gfxinfo）**：根因链 = mikepenz markdown 异步解析 → 长回复初次组合仅测得占位高度（412px）→ 解析完成暴涨（412→16746px，RESIZE logcat 实证）→ LazyColumn 锚点修正 → fling 中视口瞬移 1.4 万 px（=「下跳」）；16k px 布局单帧完成 = 卡顿帧（gfxinfo 93ms 帧实证）。另实证：解析跑在主线程（parseMarkdownFlow 无 flowOn），16KB 文本阻塞 100ms+ 打断拖拽；修复前 fling 90-300ms 即被杀
  - **修复（f03a89d5，三件套）**：① 滚动预解析驱动——视口 ±8 项 assistant 长文本（≥200 字符）提前后台解析（RenderReadinessRegistry，key=part.id，LRU 32），消费端组合时取 Parsed state 直接渲染（首测即最终高度）；② SafeFlingBehavior 限速 fling——每帧 ≤ 视口高/8（carry 保总距离）；③ preParse flowOn(Default) 移出主线程
  - **真机验证（对照基线）**：RESIZE 11→0（5 次定向 fling）；fling 存活 90-300ms→自然跑满 2s（位移 6300-6800px 与 v0/friction 物理吻合）；视频逐帧 DISCONT 6 处异常（停稳后 -390px 瞬移/减速中 -458px 暴冲）→0 处异常（仅剩正常起步加速）；janky 1.18% p90=7ms p99=65ms；全量单测绿
  - ⚠️ 待用户验收：滚动手感（限速档位/预解析距离可调）
  - **基建**：ScrollDiag 插桩保留（DEBUG-only：位置 LEAP/手势/RESIZE/补偿触发）——后续滚动问题真机取证直接复用
## 2026-08-20 主对话抽屉高度统一批次（min = max = 75% 屏高）

- [~] **主对话抽屉屏占比高度统一——最小/最大高度统一为 75%** `ui`
  - 需求（2026-08-20 用户确认）：主对话内所有抽屉高度保持一致，min = max = 75% 屏高。此前状态：四个抽屉只有 75% 上限（2026-08-16 决策"去 30% 下限内容自然收缩"），内容少时抽屉塌缩、各抽屉高度不一致
  - 实现：新增 `ui/theme/SheetTokens.kt`（ChatSheetHeightFraction=0.75f，文档见 ui-conventions.md §Sheet tokens）；TaskSheet（含 ShellDetailView 详情态同高、输出区改 weight+scroll）/ ModelPickerDialog / QuickNavigateSheet（列表补 weight(1f)）/ PendingTodoSheet（tab 内容包 Box weight(1f)）内容根改固定 `height(屏高×75%)`；四者均补 `rememberModalBottomSheetState(skipPartiallyExpanded = true)` 防固定高度先落半展开锚点。注：SystemPromptDialog 同步改齐（见下条死代码）
  - 验证：编译 ✅ 全量单测 --rerun ✅；**真机 E2E**（2026-08-20 用户方针：后续测试一律真机，不用模拟器；小米 23127PN0CC serial e69a99d8，屏 1200x2670@480dpi）——4 抽屉像素级顶边一致性 + 空内容撑满 + 内部滚动 + logcat FATAL，**2026-08-20 全 PASS**：4 抽屉顶边逐像素完全一致（y=619px，max−min=0，双检测器互证）；空队列抽屉撑满不塌缩（y1500–2600 std=0.0 纯留白、直达屏底）；model_picker 列表内滚动顶边不变；350ms 早帧几何已就位全高（无半展开锚点）；FATAL=0。证据 /tmp/sheet75r/（6 张正式截图 + logcat 42k 行）
  - **真机测试 runbook（本批次打通，后续复用）**：① 装包**一律用 pm install 静默法**（2026-08-20 实证 3 轮 0.4s 无弹窗）：adb -s e69a99d8 push <apk> /data/local/tmp/t.apk && adb -s e69a99d8 shell pm install -r /data/local/tmp/t.apk && adb -s e69a99d8 shell rm /data/local/tmp/t.apk——MIUI 确认弹窗只挂在 adb install 流程（PackageInstaller UI），shell 直装不经过；降级加 -d。次选：adb install 需 MIUI 开「USB 安装」且屏幕解锁常亮（弹窗手点；svc power stayon usb 保常亮）② 服务器打通用 adb reverse tcp:4199 tcp:4199（设备 127.0.0.1:4199 → 宿主机）③ 一键配置服务器走 debug 构建 intent：am start -n dev.leonardo.ocbeacon.dev/dev.leonardo.ocbeacon.MainActivity --es debug_url http://127.0.0.1:4199 --es debug_username opencode --es debug_password <pwd>（仅 BuildConfig.DEBUG 生效；dev-release 本地无 keystore 回退 debug 签名且非 debuggable 不可降级覆盖）④ 本地 keystore 已失（仅 CI Secrets 存留）——本地构建恒为 debug 签名，与 CI release 包互不覆盖，切换需卸载重装
  - ⚠️ 待用户验收：观感（固定高度后空内容抽屉底部留白是否符合预期）——测试构建已在真机可直接体验
- [x] **新增 P3：SystemPromptDialog + extractSystemPrompt 疑似死代码（2026-08-20 抽屉统一批次顺带发现）** `refactor`
  - **2026-08-20 清理完成 ✅**：全库 grep（main+test）确认零调用方后删除 SystemPromptDialog.kt 整文件 + 15 语言 2 个孤儿键（chat_system_prompt_title/empty，646 键一致）；编译 ✅ 全量单测 --rerun ✅ i18n-check PASSED。附带成果：本地 devRelease 首次以新 keystore 签名成功（8fbc136e…，与 keytool 指纹一致）

## 2026-08-21 GitHub issue #1 遗留调研批次（V1 连接速度）

- [ ] **#150 V1 连接速度慢于 beta.4 误判 V2——探测复用 + 预加载/SSE 并行化** `perf` `v1`
  - 来源：GitHub issue #1（ISuuuu）遗留反馈"连接方式 v1 连接速度没有 0.3.0-beta.4 的 v2 快"（报错部分已由 4c2b6d8a 修复并经报告人确认）
  - **调研结论（完整证据链见 docs/research/issue-1-v1-connect-speed-2026-08-21.md）**：beta.4 的"v2 快"是误判产物——V1 1.18.18 过渡形态 /api/health 返回 {"healthy":true} 被判 V2，随后 preLoadSessions 在 /api/project 的 SPA HTML 上快速失败被整体跳过（6 步串行缩为 3 步；本机回环实测 165ms vs 37ms）。真实根因：① runSseConnectionLoop 把 preLoadSessions（/project + N×/session + N×/session/status）**串行放在 SSE 之前**，"已连接"翻转被整段阻塞；② 每次手动连接都重新双探（V2-first 白跑一次 RTT，已持久化的 apiVersion 不复用）；③ V1 特有 /session/status 每目录一串往返 + Windows /project 冷调用慢（实测首调 214ms vs 热 8ms）
  - 修复方向（按收益）：探测结果复用 + 后台重探（保留 #132 UNKNOWN 语义）→ preLoadSessions 与 SSE 并行（首事件立即翻转已连接，preload 并发补）→ 项目间并行拉取（并发 2-4）。**不建议**复现 beta.4 误判行为（#83 回归）
  - 已排除：SSE 首事件延迟（两版本握手即推 server.connected）、心跳节奏、payload 包装解析、初始消息分页、认证方式——均实测/代码验证无差异
  - **与 #132 的表面矛盾与调和（2026-08-21 补充，实现前必读）**：#132 规则"探测失败保留原 apiVersion"之所以安全，前提是**现有流程每次连接都重新探测验货**——持久化版本只是兜底，永远被新一轮探测纠正（服务器 V1→V2 升级会在下次连接被发现）。而"探测复用"方向若实现为裸跳过探测，等于删掉验货环节：服务器升级后客户端永久用旧版本路径 → V1 路径打 V2 → SPA HTML → **复发 #132 当初修掉的症状**（JSON 解析错误 + SSE 假死，与 issue #1 原始报错同貌）。三个调和方案：
    - 方案 A（并行验货）：连接立即用持久化版本发起，探测后台并行；结果不一致则掐断重连。最快但状态机复杂（探测与 SSE 握手竞态、先连后纠的闪烁），为罕见事件（服务器升级）不值
    - **方案 B（按已知版本排序探测，推荐）**：detect() 先探持久化版本（V1 服务器探 /global/health 一次即中），失败/HTML/非 JSON 才回退现有 V2-first 双探；持久化 UNKNOWN 则维持现状。V1 连接 2 RTT → 1 RTT，V2 维持 1 RTT，零新增等待；探测失败路径自然落入 #132 的 UNKNOWN 保留语义（严格不破坏）。改动最小（仅 ApiVersionDetector.detect 排序 + 传入已知版本）
    - 方案 C（跳探 + HTML 自愈）：不探测，连接路径上 rejectHtmlResponse 命中 HTML 时触发重探重连。最快但把版本知识泄漏进 SSE/REST 层、错误发现滞后到用户可见的失败连接，不取
  - **推荐组合（2026-08-21）**：方案 B（探测排序）+ 方向②（SSE 先行、preload 并行补，主要收益所在——实测 preload 串行 ~134ms 占 165ms 总耗时的大头，双探仅 ~19ms）+ 方向③（项目间并发 2-4）。预期：V1 首连感知 ~165ms → ~40ms 量级（SSE 首事件即翻转已连接），且 #83 交叉验证与 #132 UNKNOWN 语义双双完整保留
  - **2026-08-21 实现完成（worktree 分支 fix/150-v1-connect-speed，基点 a27236c7，commit ed466966）**：方案 B（detect 增 knownVersion 参数按持久化版本排序探测，双探兜底语义不变）+ 方向②（preload 移入并行 job，SSE 首事件即翻转已连接；finally cancelAndJoin 串行化护栏防跨轮重叠写 eventDispatcher）+ 方向③（preLoadSessions 项目间 Semaphore(4) 并发，setSessions CAS 合并语义并发安全）。新增单测 4 例（排序 3 + SSE 先行 1）+ 孤儿守卫用例观测点更新为新架构等价性质；编译 ✅ 全量单测 --rerun ✅
  - **模拟器 E2E（Pixel6_Android36，V1=1.18.18 隔离实例 + V2=0.0.0-beta-17728 真实 4 项目 200 会话）**：基线/新版 APK 均本地 clean 构建 + dex 字符串验证（防 Gradle checkout 缓存陷阱——首次对照因 UP-TO-DATE 误判全部跑了同一 APK，clean 重建后重测）。结果：① V1 冷首连 attempt→Connected 81-138ms → **25-43ms（~3×）**，Connected 全部先于 Pre-loaded（SSE 先行铁证）；② V1 热复连 283-313ms → **169-220ms**，基线每轮 cross-check 白探、新版 known=V1 单探即中零白探；③ V2 冷连 Connected 231ms 先于 Pre-loaded(200 sessions/4 projects) 完成 213ms；④ **升级场景真机复现**：known=V1 + 实际 V2 → V1 探测 HTML 拒绝 → 回退 V2 当次纠正，attempt→Connected 101ms；⑤ 全程 FATAL=0。证据 /tmp/e2e150b/（14 份 logcat）
  - **2026-08-21 已合回 master**（merge 25927de5，合并后主工作区编译 ✅ + 全量单测 --rerun ✅）；剩余待办：真机复验（2026-08-20 方针真机优先——本轮按用户指示用模拟器）+ 随下次 dev 发版交付
