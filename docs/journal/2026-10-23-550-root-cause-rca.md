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
