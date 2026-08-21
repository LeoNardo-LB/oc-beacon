# 2026-08-20 堆积消息/TODO 功能批次
> 状态：部分完结（活跃 #158 #159 #160 #161）
> 迁移：2026-08-20 自 backlog.md 原文迁入（spec/journal 分离批次；原文逐字保留，未压缩）
> 条目编号：a11y 遮罩=#158、jumpLockActive=#159、LocalBinder=#160、离线 context 圆环=#161


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

- [x] **新增 P3（结构优化）：jumpLockActive 镜像标志应从 JumpNavigationController.phase 派生（2026-08-21 竞态根因层级分析衍生）** `arch` `jump` ✅ 2026-08-22 收口
  - **核心部分已完成（同日第二轮）**：两个自动分页 effect 的 fire-time 门控已改直读 `isJumpInProgress` 真源（正确性不再依赖镜像）；剩余范围收窄为纯清理——启动 key 与 B-F2 提交门控仍读镜像（后者带 2s 时窗语义，需一并设计），全部删除镜像标志后收口
  - 现状：jumpLockActive 是手写镜像（ChatMessageList 3 处写点：jumpToMessage/异步定位 effect/phase 终点收集器），已因此出过一次竞态（见上条）；镜像与真源不一致窗口 = 结构性风险
  - 方向：`val jumpLockActive = jumpController.phase.value is Preparing/Measuring/Settling`（derived state 或直接订阅），删除全部手工写点
  - 工时：~1h | 难度：低 | 涉及：ChatMessageList / JumpNavigationController | 优先级：P3
  - **✅ 收口（2026-08-22）**：锁收进 JumpNavigationController 派生 StateFlow（`jumpLockActive`）——锁定窗口 = markJumpPending 异步窗口 ∪ 进行中（Preparing/Measuring/Settling）∪ 终点后 300ms 缓冲（collectLatest：缓冲期内新跳转取消解锁，等价原解锁 effect 键重启）。B-F2 提交门控此前已直读 phase 真源（无需动）；autoLoad 两 effect 启动门控改读派生锁。ChatMessageList 全部 4 手工写点删除。**附带修掉一个现存 bug**：loadAround 失败路径（两轮未命中清 pendingJumpTarget）旧镜像漏复位 → 目标真不存在时 autoLoad 永久锁死到下次成功跳转——现在 clearPendingJumpLock() 显式解锁（phase 仍 Idle 才生效，与活跃跳转交错为 no-op）。JumpLockDerivationTest 8 例（虚拟时钟验证 300ms 缓冲边界/collectLatest 取消语义/失败解锁回归/交错 no-op）；全量 1880/1880 绿
  - **真机冒烟（2026-08-22，devRelease dd43ab13 装机）**：①进 AB 会话滚顶 auto-load older 正常放行（可见最早消息 15:00:45 → 滚后加载至 05:21-05:22）——锁默认 false 放行分支；②快速定位 Q36 异步跳转全链：蒙版出现（加载遮罩「正在加载...」= showMask 派生）→ 落点顶部 05:21:54 → 3.5s 内蒙版消失（Displayed→300ms 缓冲解锁）；③跳转后滚动正常（无锁卡死迹象）；④全程 0 FATAL。commit dd43ab13

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
