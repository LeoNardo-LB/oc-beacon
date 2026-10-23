# #550 根修取证与根因定谳（2026-10-23 凌晨）

> 关联：backlog #550（升格后根修腿）· 前置证据 /tmp/soak-550/（五证链 HIT）+ /tmp/550-lab/（实验复现三件套）

## 一、soak HIT 现场证据重审（推翻「组合内容真空」叙事）

- **「纯色屏」是判读方法误判**：HIT-1 截图实际渲染完整 UI（bytes/pixel=0.012 只是低信息量 UI 的高压缩比）。像素分析：94.58% surface 底色 + 2.45% 断连横幅区 + 0.82% 用户气泡——视觉判读（远程视觉）确认顶栏/横幅/warmup 对话/思考卡/回复页脚**全在**，唯独**输入栏缺失**。
- **contentTree 的 View 骨架正常**是常态非异常：Compose 内容本就不在 View 树（FrameLayout→ComposeView→AndroidComposeView 全屏活；AndroidViewsHandler children=0 是无 AndroidView 互操作的常态；RippleHostView 996×168 是涟漪残留）。
- **「渐进真空」实为装置断言盲区链**：①`edit=['']` 真实语义=send_prompt 的 no-post 分支诊断（EditText 节点**在树**、text 空=IME 输入未落字；`btn=(980,2263)`=发送按钮找到且被 tap）②20:55 后 dump=桌面 launcher（BACK 弹栈过头）/permissioncontroller 权限对话框抢窗 ③21:05+ 模拟器 a11y 层挂死（dump 全空）。
- **mock 20:31 死亡与 T1 完成的时间耦合**是巧合根因链的起点（见二）：mock 死→断线→一切下游。

## 二、实验复现与根因定谳（/tmp/550-lab/，2026-10-23 01:43-02:00）

### 复现实验设计
- 模拟器无头 + dev 包（14:33 快照）+ mock_v2 giant（120 行×0.12s，对齐 soak 配方）。
- 探针：debuggerd -j 主线程栈采样（adb root 后可用）+ uiautomator dump + 截图三件套 + V2Api tag 的 POST status 行判据（window.py 的 `-s 'Ktor Client'` 过滤 miss 教训——判据必须用 `POST /prompt status=` 行）。

### HIT 链（两层定谳）

**第一层：tap 无 POST=断线本地拒绝+AlertDialog 焦点劫持**
- strict.py R0：tap 发送(980,2263)→4s 无 POST→HIT。取证：**主线程完全空闲**（8×debuggerd 栈全为 epoll 等 Looper——「主线程阻塞/渲染链死」假设双双排除）。
- 视觉判读定罪：屏幕中央「发送失败/服务器已断开，操作未发送」AlertDialog+断连横幅。**tap 触发了 send→断线快速失败（#267 哨兵）→本地拒绝（无 POST 是正确行为）→弹窗**。
- **13 节点「空树」=AlertDialog 焦点劫持**：模态对话框窗口抢走 a11y 焦点，dump 只暴露对话框节点——**soak「真空」的 a11y 侧真身**。
- （实验环境 mock 死因=probe.py 退出未 detach 杀子进程；soak 环境 mock 20:31 死因待查但同链。）

**第二层：断线重进→输入栏整块消失+error 永久滞留（真 app 缺陷）**
- 断线态 force-stop 重启+重进会话：**精确复现 soak 21:08 窗**——消息列表完整（Room 缓存 2 轮对话渲染）、断连横幅在、**输入栏（editor+发送按钮+模型选择器）整块缺失**、无对话框。a11y 树 30367B app 树但 EditText=0。
- **mock 复活+SSE 重连后 50s+ 输入栏不恢复**（a11y 树活着——横幅倒计时在更新——但 EditText 恒 0）：**永久锁死**。

### 根因链（代码定罪）
1. 断线窗口期（mock 死/网络切换/服务器重启）任意时刻：
   - 进会话 `loadMessages()`/`refreshMessages()` REST 直连失败（**无缓存回退**——loadMessagesForSession 的 recoverCatching 只覆盖它自己那条腿）→ `errorSink/reportError` → `_error` 置非空；
   - 或 SSE 重连成功 → resync（SessionActionsDelegate.refreshAndSync）→ refreshMessages() 若 REST 仍失败 → 同上。
2. `interactionState.error != null` → **ChatScreenBottomBar:454 `interaction.error == null` 条件**→输入栏整块不组合（#310①「加载失败保守隐藏——防误发」的过宽策略）。
3. **error 无清除路径**：唯一 errorSink(null) 在 loadMessages() 开头；重连成功边沿不触发重载/清除 → **error 恒留 → 输入栏永不恢复**。
4. 消息区走 Room 缓存分支正常渲染（messages 非空不进 ChatErrorState）——「消息能看、没法回复」的不对称形态。

### 与六报②（用户原始报告）拟合
- 「各种原本可点击的地方变得无法点击」= 输入栏整块消失（发送/附件/模型选择器全失）✓
- 「莫名其妙（随机）」= 断线窗口随机 + 恢复后不自愈 ✓
- 「展开后标题没了」不完全拟合（可能为复合症状/另一子缺陷——beta-2 真机域）
- 真机 10h+beta-12 零复发与 mock 复现的矛盾消解：mock 域「真空」大部分=断线下游装置盲区（非缺陷）+断线输入栏消失（真缺陷但浸泡走真实服务器不触发 mock 死亡路径；真机日常断线才触发）。

### mock↔真实流形态等价性（取证①结论）
- 事件族序列**等价**（inbox.enqueued→execution.started→step.started→reasoning.delta×N→reasoning.ended→text.*→step.ended→execution.succeeded，对齐 V2SseMapper 契约注释与 2026-08-30 实测 22 事件序列）。
- delta 粒度不等价（mock 69 字符/条整行 vs 真实 token 级碎片）但**保守方向**（每步重组工作量更大）；总量同量级（8290 字符/轮 reasoning）。
- `ended.text` 全文回显：真实 V2 现网实录（#539 compaction.ended 携带全文）+ 2026-08-30 序列实测（「ended 全量值同 id 覆盖自愈」）支持等价。
- **结论：流形态等价性不构成复现障碍**——本案根因（断线链）与流形态无关，「giant 配方」的真实作用=拉长流时间窗提高装置 send 撞断线窗的概率。

## 三、根修方案（两腿）

1. **输入栏条件收窄**（ChatScreenBottomBar.kt:454）：`interaction.error == null` → 仅在 `interaction.error != null && messageState.messages.isEmpty()`（=ChatErrorState 显示条件）时隐藏——有消息可看时输入栏恒在。
2. **error 滞留清除**（ChatViewModel）：ServerLinkState→Connected 边沿 + error 非空 → 重试 loadMessages()（其开头 errorSink(null)+成功保持 null）——重连成功即自愈。

## 四、判读边界与遗留

- soak「真空」的 a11y 挂死段（21:05+ 模拟器级）为环境层（dump 风暴），非 app 缺陷。
- GPU 4950ms×15 帧=软件渲染+dump 风暴负载产物，非独立缺陷。
- 「标题没了」子症状未在本链闭合——挂 note 继续观察（beta 真机域）。
- 实验证据目录：/tmp/550-lab/（strict.log/HIT-sendstage-r0 8 栈+dump+截图/disconnected-reenter 三件套/重连不恢复轮询记录）。

## 已完结卡片迁入（2026-10-23）

### **#550 六报②展开后标题消失+全面不可点击——已复现（非空栈型组合内容真空，mock 配方 3/3）待根修（2026-10-22 用户裁决升格）** `ui` `race`
  - 用户报告（beta-2/0.4.0-beta/42）：卡片、思考展开后标题没了，各种原本可点击的地方变得无法点击；莫名其妙（随机）
  - 已排除：ReasoningBlock 标题行无条件常驻（非设计显隐）；dev 包 23 探针轮+beta 包 40 探针轮（focus/dump 响应性）零死 UI；logcat 缓冲无 FATAL/ANR/输入超时；graphicsLayer alpha=0+clipToBounds 零高盒的命中域不构成持久盾（瞬态窗口）
  - 在查假设：#545 BLANK-RENDER-FREEZE 同族（渲染冻结仅 force-stop 可恢复——待用户差分：是否 force-stop 才恢复/离开重进屏幕是否自愈）；beta-2 无四刀的闪光脸下游（巨型内容单帧全量组合=长主线程停顿——beta-3 复测差分）；#547 组合期写流的后向写面
  - 监视器已布防（watch6b.sh：beta 前台时 gfxinfo 10s 零新帧=冻结嫌疑自动抓 dump/截图/logcat，~2.7h 窗口）；用户在 beta-3 复测报告①时若②复发即有现场
  - 复验轮（subagent 22:30-23:27，REVERIFY-REPORT.md）：B3/B4 fresh 复验被上游 provider 凭据失效阻塞（22:41 起免费+付费层全线 401/零输出，用户域凭据问题）——同构建间接证据（probe6 23×focus 全绿/p6b 40×全绿/s5+fuzz 无真命中）支持未见跳跃/死 UI；补跑脚本就绪 repro6c_dev.sh/repro6c_beta.sh（含前台包校验——勘误：此前 p6c beta 轮实为 dev 前台误测，两包同代码结论不变）
  - B1（高度偏移）同构建历史 5 轮全绿+B2（贴底上向）fresh+历史双证 PASS；新发现扫描器误报类：IME inset 动画窗（键盘收起 notifyFinished/onReady 产生 −838/−753 单次外部位移，非手势非 toggle）——scan5r5 需补 IME 窗排除（登记入 #549 修向附带）
  - 环境修复+补验收官（2026-10-13 凌晨）：所谓凭据全线失效实为双源——zhipuai-coding-plan 余额耗尽（curl 实证 error 1113，充值属用户域）+opencode 免费中继账号凭据失效（401，重登录属用户域）；deepseek key 活着（余额 91 元）但模型 id 被误用（deepseek-reasoner 不在册，正确为 deepseek-flash/v4-pro）——改走 deepseek-flash 后测试链全恢复（出流+reasoning）
  - B3/B4 补验（用户原话协议：空起步展开×12+ 轮，dev 探针版）：零真命中——全部 TITLE-GONE 经 dump 定罪=思考卡被流式答案顶出 a11y 视口（屏幕实为答案正文+完成栏，正常贴底 UX）；唯一 OSC=markdown 表格重排凹陷（数据驱动合法补偿）；零 DEAD-UI/FOCUS-0。判定：四刀修复版上 B3/B4 未复现
  - 工具教训：TITLE 存活探针须带滚动感知（a11y 视口裁剪伪影）；scan5r5 需补 IME-inset 窗排除与重排凹陷容忍（本轮三类伪影全记录在案）
  - 2026-10-20 傍晚 AI 验收处置（用户委托）：①机制关联分析——#545 当日定谳（空栈→组合内容真空→a11y 空树）与卡内第一备查假设「#545 同族」高度相关：『全面不可点击』拟合 a11y 空树、『标题没了』拟合内容消失；但 #545 signature=整屏空白 vs 六报②=部分元素消失（卡片在标题没了）——不完全吻合，不能强判同族；关键差分（force-stop 才恢复？/离开重进自愈？）仍待用户日常观察。②装置升级——beta-12/52（0.4.0-beta-12，含 #545 空栈守护+#543 唯一所有权+全部近期根修）已装真机；watch7 冻结监视器挂起（30s 间隔 gfxinfo 零新帧检测+自动取证包；beta 包无 FreezeWatchdog 黑匣子=DEBUG 门控不进 release，取证退化为外部四件套——守护日志 #545 empty backStack guard 在 logcat/Diagnostics 可查=同族复发的仪器判据）。③判定：维持 P1 观察（低频未复现本性）——若守护日志触发+死 UI 症状同现=同族实锤可关联收口；零触发零症状=继续观察。监视跑至明晨，用户日常使用 beta-12 即为自然观察窗
  - 2026-10-21 10h 浸泡（subagent 监督，00:25-10:23）：1067 轮程序化交互（六报②三断言+25s 流式窗）零命中零守护零冻结签名（watch7 133 事件全定罪静止页误报）——beta-12 稳定性数据大幅增厚（累计 4 手动+1067 浸泡零异常）；维持 P1 观察（程序化交互未覆盖真实多卡展开/长会话场景，用户日常 beta-12 为最高价值窗）；附带装置沉淀：mDNS 解析 grep -oE 定规（三连踩家族）+watch7 判据改进挂账（静止页误报）。证据 /tmp/550-soak/FINAL-REPORT.md + journal 2026-10-21-550-soak-10h
  - 2026-10-22 模拟器 soak 5h（队列串行腿2，/tmp/soak-550/FINAL-REPORT.md 终版）：**真 HIT=非空栈型组合内容真空**（五证链：RESUMED+焦点在/组合树子节点全零尺寸/纯色屏≥2min/invalidate 不可救/#545 空栈守护 0 触发）——与六报②症状高度拟合（a11y 空树=全面不可点击/内容空=标题没了），#545 姐妹形态谱系（守护盲区互补）；**配方复现 3/3=100%**（giant 流完成→下一 turn ≤40s 渐进真空 editor→标题→全树→纯色，含模拟器重启后干净样本 R#35——程序化诱因怀疑削弱，mock↔真实流形态等价性未证）；后段 2h 慢性准真空（send 恒败导航部分可用，无自然恢复）。执行代理判定建议：观察升「已复现待根修（mock 配方域）」，根修前取证=流形态等价性+真空起点定位（流完成后释放路径嫌疑域）——**升格待用户裁决**。真机 10h+beta-12 日常零复发与 mock 复现不矛盾（流形态差异+每轮 force-stop 消灭真空窗）。装置沉淀：soak.py 五版终态+8 坑位+a11y 挂死队列级预警（dump 空≠屏幕空）+harness 修复三处回灌宿主
  - 2026-10-22 23:08 用户裁决升格：观察→**已复现待根修（mock 配方域）**。根修前取证项：①流形态等价性（mock giant 流↔真实服务器事件序列对比）②真空起点定位（流完成后释放路径嫌疑域：giant 完成的 step.ended/execution.succeeded 处理链→组合树子节点零尺寸的传播链）——判读边界见 journal 五卡总收节
  - 2026-10-23 凌晨根修腿收官：根因链实验定谳（journal 2026-10-23-550-root-cause-rca）——①soak「组合内容真空」叙事推翻：HIT 截图实为完整 UI（纯色=低信息量高压缩误判），「渐进真空」=断线下游装置断言盲区链（AlertDialog 焦点劫持→dump 剪枝/桌面与权限对话框抢窗/a11y 层挂死）+tap 无 POST=断线本地拒绝（#267 哨兵正确行为）+主线程空闲（8×debuggerd 栈实证，阻塞假设排除）
  - ②真缺陷=断线重进输入栏整块消失+error 永久滞留（interaction.error 无清除路径→ChatScreenBottomBar error 条件永久隐藏 composer，「消息能看没法回复」锁死，重连 50s 不恢复实验实证）——与六报②「各种原本可点击的地方变得无法点击/莫名其妙」拟合
  - ③根修两腿=error 隐藏条件收窄（SubagentComposerGate.composerVisibleOnError 纯函数，仅 ChatErrorState 兜底态隐藏）+重连 Connected 边沿自愈（ChatViewModel 重试 loadMessages 清 error）
  - ④验证=Gate 单测 3 用例四象限+ChatViewModelSendTest 边沿自愈用例 11/11+模拟器断线重进输入栏保持（修复前 EditText=0→修复后=1）+恢复后 send 链路健康
  - ⑤判读边界：「展开后标题没了」子症状未在本链闭合（挂 note 真机观察）；mock↔真实流形态等价性已证不构成复现障碍（事件族序列等证/delta 粒度保守方向/ended 全文回显有实录——本案根因与流形态无关）
  - 迁入依据：AI 自行验收关卡（用户 2026-10-23 委托验收）：①单测=Gate 四象限 3 用例+ChatViewModelSendTest 边沿自愈 11/11（全类 fresh）+全量单测+lint exit 0；②模拟器验收腿 v3=断线重进×3 重复（冷启×2+导航×1）输入栏保持全 PASS（et=1 btn=1 断连横幅在场——修复前同场景 et=0 btn=0）+回归 giant 流完整+恢复域 send+流 PASS（核心腿 6/6；「净环境 open」一项为装置导航时序伪影豁免——同时窗 composer 在场+send/流全过反证）；③昨夜腿=断线重进双路径 PASS+恢复 send POST 200；残余观察=真机日常断线场景输入栏表现与「展开后标题没了」子症状（真机 beta 域，复发按卡内 note 定界）——判读边界全文 journal 2026-10-23-550-root-cause-rca（backlog.sh migrate 2026-10-23）
