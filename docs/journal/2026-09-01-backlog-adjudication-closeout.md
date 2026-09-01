# backlog adjudication closeout（2026-09-01）

> 状态：已完成（待用户验收 [~] 五卡）
> 关联：backlog #278/#279/#283/#285/#287/#290 · 前批 docs/journal/2026-09-01-dsh-refactor-closeout.md · 2026-09-01-291281-stash-v6.md
> 来源：用户指令「设置一个目标一次性做完 backlog 遗留项（依次处理，不并行）」——承接同日全量陈卡裁决报告

## 一、裁决轮发现（本批起点）

对 backlog 全部 13 张待决卡逐卡对代码/测试/commit 取证，结论：**5 张「已修未翻卡」**
（与 2026-09-01 早些时候 #281 同型——修复带卡号 commit 落了 master，卡面未同步）：

| 卡 | 落地 commit（时间） | 代码证据（file:line 为裁决时点） |
|----|---------------------|--------------------------------|
| #287 | d252eab4（09-01 13:29） | `DshApiClient.readAttachment`（:1234）→ `ChatViewModel.collectPendingAttachmentFetches`（:1029）→ `fetchAttachmentDataUrl`（ChatRepositoryImpl:348）→ `patchFileUrl` |
| #285 | 0a925220（06:57） | `sessionIdFlow.collect` 就绪重载（ChatViewModel:1011）+ `commands/change`→`CommandsChanged`（DshEventMapper:162）→ MiscEventHandler→`commandsChanged` flow |
| #278 | 87238a1c | `fetchSessionStatus` 改 session.list `running` 播种 busy/idle（DshApiClient:437-448，注释带 #278 标注） |
| #279+#290 | 6cb3c8a7（09:24） | `CreateDocument` MIME 按能力位（ChatAttachmentsHandler:187）+ `scripts/pull-app-db.sh` + observability guide 附章 |
| #283 | a1=7d6761ef（09:39）；a2 未做 | `PermissionDefaultRow` options 动态渲染（:53-57）；投影 key=permissions 仍 Ignored（DshEventMapper:271） |

维持项（有明确理由，非陈卡）：#288（服务器四面无 tool-workflow 事件，结构性阻塞）、
#154（触发条件=beta 线上报告未到；`crash_occurred` extra 无消费者、无 gists 端点，
与卡面一致）、#146（上游外部流程）、#277/#254/#245/#158（等复现/功效不足；
#254 T12 仍活跃无 @Ignore）、#258（已按条款收口，alpha flag 实验可选项）、
#292（归档分支裁决留用户）。

卫生发现两处：backlog.md P2 尾部 **#263 孤儿行**（卡已迁、尾行漏删，git -S 考证
原属 #263 思考卡时长卡的「待验证」尾行）；SessionActionsDelegate:445 注释仍描述
「MIME 固定 application/json（ChatScreen 冻结）」与 #279 现状不符。

## 二、本批代码（依次完成，全串行）

1. **a93aca95** `chore:` SessionActionsDelegate 导出注释对齐 #279 现状（MIME/扩展名
   已按 exportIsArchive 切换，renameDocument 仅落盘后兜底）。
2. **705d889c** `test:` #287 readAttachment 专测补齐——成功形态断言
   method/payload/路径与 `(mediaType, base64)` 返回对、缺 `data` 字段降级 null、
   HTTP 500 传输失败经 Result.failure 降级 null（三态）。
3. **5c2d44cc** `feat:` #283-a2 session/projection permissions 键消费——
   `DshSessionMapper.parsePermissionsValue` 单源解析（帧与 list 基线同形
   `{options,currentValue}`，research doc 2026-08-31 帧契约）；DshEventMapper 增
   `permissions` 分支（JsonObject→`SessionPermissionsChanged` 整值事件 /
   JsonNull→clear tombstone 同 goal 键语义 / 余者 MALFORMED）；EventDispatcher
   绑定+sessionId 提取；SessionEventHandler 整值替换 `Session.permissions`
   （与三 knob 事件字段级合并互补）。测试：mapper 三例 + handler 两例。

**验证**：compileDevDebugKotlin 绿 → 定向（DshApiClient/DshEventMapper/
SessionEventHandler）绿且确认 8 个新用例真实执行 → 全量
`testDevDebugUnitTest --rerun`：**255 套件 / 2519 用例 / 0 失败 0 错误 0 跳过**
（基线 2511 + 8）。

## 三、真机 V6 代跑（小米 houji，WiFi ADB 192.168.110.239:5555）

装机：assembleDevDebug（17:33）→ `adb install -r` + force-stop；隧道
reverse tcp:4199 + tcp:3080；服务器：opencode 4199（opencode2.exe）+
DSH 3080（node，本机即 192.168.110.248）。

### 踩坑链（先错后正，如实记录）

1. **连错服务器（最大弯路）**：服务器列表第二条目**标签**「192.168.110.248:248」
   实为 URL `http://192.168.110.248:4199` 的 opencode LAN 条目（用户手输标签误导）；
   DSH 真条目是第三条「127.0.0.1:3080」。前半程所有「V6 证据」产生在 opencode 上
   ——被 DataStore 取证揭穿（`run-as ... strings datastore` 读 servers JSON）后全部作废重做。
   定性：**服务器条目标签≠URL，跨服务器 V6 前必须先核 DataStore**。
2. 分析链 Read 截图→CDN→analyze_image 多次**输出截断/坐标换算错/一次整屏误读**
   （把桌面读成服务器列表）；对策=关键结论要求二次证据（服务器 RPC 直查、
   logcat、Room 库）交叉，不单独信视觉分析。
3. `am force-stop` 一次静默未生效（&&链断裂且输出被吞）→ pid 未变、后续「重启验证」
   全部失真；对策=force-stop 后必须 `pidof` 核对 pid 变化。
4. uiautomator dump 踩已知坑（会话页 a11y 树退化零 clickable 节点；另一次 dump 出
   桌面）——截图+像素/分析链为准。
5. 空 `--es debug_password ''` 会被 am 丢弃且**破坏后续 extra 解析**
   （debug_name 未送达→条目被改名 "Debug External"）；debug channel 设计上
   密码为空=保留已有（护凭据），无法借此清空密码。

### 各卡证据（重做后，均在真 DSH 条目 127.0.0.1:3080 上）

- **#279 ✓**：Test Lab 会话 ⋮→导出→SAF 对话框预填
  `test-lab-initialization-20260901.zip`（analyze 高置信；/tmp/v6-20-filename.png）。
  代码位 exportIsArchive=true→application/zip 双证。取消未落盘。
- **#287 ✓**：服务端 probe（session.history 计 attachment 块）定位
  session-320c59「Test Lab Initialization」含 2 图片附件块；真机打开该会话
  消息流渲染 **2 处图片缩略图**（/tmp/v6-18-attach-session.png）。注：未区分
  当次 readAttachment 拉取 vs Room 缓存路径（cached_parts 可能已存 data URL）。
- **#285 ✓**：既有 DSH 会话输入 `/` 弹出**服务端命令**：/permission /sandbox
  /approval /model…（/tmp/v6-29-bottom.png）——与 opencode 的 /init /new 命令集
  完全不同，排除内置默认；另有 opencode 侧早前一次弹层（作废轮次残留观察，
  机制同路径）。**懒建首连全链未代跑**：新会话发送三次均停留「时钟等待」
  （无 session.create/prompt Ktor 请求发出，app 内挂起）——环境定性：该 DSH 服务器
  470 会话连接期 SSE 洪泛 +「persist queue full, dropped 750 write requests
  (Room slower than SSE production)」+ L3 校验协程取消（app logs 表实据），
  发送通道被 churn 挡住。sessionId 就绪重载逻辑由 0a925220 测试覆盖。
- **#278 受阻**：同因无法造出 busy 现场发送；且不可向用户真实工作会话
  （仲裁申请书）注入测试消息。fetchSessionStatus 播种语义有
  DshApiClientTest`fetchSessionStatus probes session list for liveness` 专测；
  真机收敛场景留用户验收（任一会话 running 中强杀重开观察）。
- **#283（a2）**：真机投影帧目验未做（需外部客户端中途切档触发）；整值替换语义
  由单测五例覆盖。

### 工具实战

`scripts/pull-app-db.sh`（#290 产物）本批两次实战：`pull#1 integrity=[ok]` ×2，
用于 cached_messages 与 logs 表取证（发现 logs 表止于 17:59:48、无发送错误、
连接洪泛 WARN 链）——正是 #290 卡描述的观测场景。

## 四、backlog 收口

- **#290 迁出**（本 journal，原文如下，不压缩不删改）：

> - [ ] **#290 会话重开期「 Room 撕裂副本」取证假象——run-as 活库拉取需 WAL 三件套** `qa`
>   - 2026-09-01 #9/#10 取证两次踩坑：adb exec-out run-as 单拉主 db 报 database disk image is malformed（01:21 版）；补 -wal/-shm 三件套后 integrity ok，但活写中拉取仍可能撕裂（多次拉取取首个 integrity ok 副本）
>   - 方向：观测 runbook 补标准拉取脚本（三件套 + integrity 循环校验）；低优先（仅影响取证效率不影响产品）

  完结依据：6cb3c8a7 已入册 `scripts/pull-app-db.sh`（头注 #290，三件套+integrity
  循环）+ observability-verification-guide §附；本批两次实战通过。

- **翻 [~]（5 卡）**：#287/#285/#278/#279/#283——代码+自动化全绿，V6 见上，
  用户验收清单见各卡「待用户验收」行。
- **删 #263 孤儿行**（P2 尾部悬空「待验证」注记，原属已迁出的 #263）。
- `./scripts/backlog-check.sh` 结果：通过（127 行）。

## 五、环境残留与注记（用户需知）

1. **DSH 条目密码残留**：debug intent 占位密码 `x` 写入 127.0.0.1:3080 条目
   （原为空）。DSH 忽略 Authorization（携此密码读/导出全通，实证），功能无害；
   debug channel 设计上不清空密码，如需还原请在 Settings 手动清空。条目
   name/url/serverType 已还原核对（127.0.0.1:3080 · Dsh）。
2. 手机侧遗留：LAN-4199 条目曾有一条发送失败的本地草稿
   （TEST_285_verify_lazy_session，进程已死随内存清除）；DSH 新会话同样
   （V6_285_retry2 时钟态，随 force-stop 清除）。
3. **连接洪泛观察**（未登记新卡，供 #278 验收与后续排查参考）：DSH 470 会话
   服务器连接期事件风暴 → Room 持久化队列溢出丢写 + L3 校验协程取消 +
   新会话发送挂起。若复现可按此线索立案。
4. 截图证据在 /tmp/v6-*.png（重启即失）；关键结论已转录上文。
