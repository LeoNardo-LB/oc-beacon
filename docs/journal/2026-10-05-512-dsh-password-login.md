# #512 dsh-password-login 插件实现（批①②③）（2026-10-05）

> 状态：进行中
> 关联：（spec 路径，若有）·（issue 编号，若有）
> 来源：用户反馈 / grilling / E2E / 顺带发现

<!-- 过程中的取证/验证证据直接写本文件；backlog.md 只留 ≤3 行卡片。 -->

## 实现批次①②③全量落地——验证证据（2026-10-05）

**实现**：独立仓库 `~/文档/code/mine/dsh-password-login`（照 dsh-keepalive 模板：宿主半 Cordis 插件 + 客户端半 slots 设置面板，lib/ 预提交）。
- 批①：`src/cookie.ts` 官方 v1 铸票逐字节复实现（HMAC 密钥 = credentials 记录 base64url **解码后**的 32B Buffer；payload 键序 version/authority/issuedAt/expiresAt；寿命 ≤ cookieMaxAgeDays 钳制）+ `GET /session`（未设密码态回环免密）。
- 批②：`GET /login`（Basic challenge→验密→303；用户名忽略）、`GET /status`（admit 门 + 存在性）、`POST /rotate`（首设回环限/改密须 current/成功自动换票）、限速（5 连败后每尝试强制 1s，Basic 与 rotate 共用计数）、schemastery volatile+secret 配置（env 种子默认）、设置面板（vendored dsh UI 原语，`data-pl` 钩子，禁止第三方组件）。
- 批③：admit 实例属性包装（/api 与 WS 升级共用；fail-open + 链式还原）+ epoch/指纹持久化（storage domain `dsh_password_login`）+ 启动金丝雀（自铸票交官方 isAuthenticated 判漂移）。

**app 侧**（oc-beacon，b8cda845）：`DshConnectionRegistry` 加 `mintSession`（OkHttp 裸客户端读 303/200 Set-Cookie 同源依赖）+ `planRecovery`/`parseSessionMint` 纯函数（回落链：免密铸票→密码铸票→legacy token[持久化优先、password 字段兜底、同值去重]）+ `setPasswordHint` 接线（SseConnectionManager DSH 分支每轮刷新）。recoverAuth 两处调用点（AUTH_REQUIRED/WS 401）语义不变。

**验证**（仪器可断言面全过；未发版）：
1. **单测**：插件 56/56（铸造期望向量/键序/钳制、回环矩阵含 ::ffff: 映射、epoch 三态、限速阈值/重置、admit 包装六态、路由契约 26 例、客户端 store）；app 侧 DshConnectionRegistryTest +7 组（三态判读/回落链顺序/去重）+ 全量 `testDevDebugUnitTest --rerun` BUILD SUCCESSFUL。
2. **本地 E2E**（`e2e/run-e2e.sh`，真实 dsh 0.2.0-rc.2 + 隔离 DSH_HOME，五阶段 40 断言 PASS）：A 生命周期全链（金丝雀 cookie 被 `GET /` 200 接受；轮换→旧票 /api 401、WS 升级 401；改密者新票存活；Basic 挑战/验密/重挑战；跨站 Origin/sec-fetch 403 且 /login 不泄漏 challenge；限速第 6 次尝试 ≥1s）；B 纯重启零误撤（指纹沿用 epoch）；C 配置通道改密全员下线一次；D enabled:false 与未装无异（GET 404/POST 与不存在路径同码）；E env 种子（DSH_PASSWORD_LOGIN_PASSWORD）boot 即密码态。
3. **docker E2E**（`e2e/docker/run.sh`，12/12 PASS）：非回环对端 fail-closed（免密 403/首设 403）+ 回环免密/首设 200 + 密码态跨对端全通（Bearer/Basic/金丝雀 cookie//api 准入）。
4. **浏览器面板手验**（browser-use 对真实宿主）：「登录密码」页签出现在设置-内置插件 tablist；未配置态文案（/status 实时）；首设 browser-pass 提交后状态翻转「已配置」+ 成功消息 + 表单切轮换模式；**面板自身经 /rotate 自动换票在 epoch 推进后存活**。视觉证据：截图（设置面板轮换模式态）。
5. **宿主侧关键环境事实修正**：0.2.0-rc.2 CLI 对 `--host 0.0.0.0` 有守卫（"intentionally not supported yet for safety"）；LAN 直连形态实际部署方式 = webserver 条目 config patch（`- id: webserver, config: {host: 0.0.0.0}`）绕 CLI 守卫——docker E2E 即此形态。spec §D 的 LAN 描述按此勘误。

**已知边界（与 spec 一致）**：撤销作用于 admit 面（/api+WS）；静态 index 壳官方设计即公开。活 WS 长连接轮换后存活到自然断开。金丝雀漂移的 stale 态无诚实模拟途径（单测层面覆盖判读），依赖宿主真实演进时 E2E 兜底。

## 批①验收补全——模拟器×双宿主端到端（2026-10-05，真机被另一需求占用，用户裁决走模拟器/docker）

**环境**：本机 AVD ocbeacon-e2e（headless swiftshader，API36-ext19）+ devDebug APK（oc-beacon 签名）+ debug intent 通道（`debug_server_type=dsh` 免手工输表）。

**宿主腿**（brew dsh 0.2.0-rc.2 + 插件，隔离 DSH_HOME，`adb reverse tcp:8180` = 真机部署同构形态）：
1. 批①「填 URL 即连」零配置：probe 401 TokenNeeded → **auth recovered via FreeMint** → 复探 `Online(V012, authenticated=true)`——插件铸票被真实宿主 /api 接受，全程 ~800ms。
2. live 轮换踢线：宿主回环 rotate 设密 → app 活 WS 按 spec 边界存活；force-stop 冷启后旧票 401（epoch 撤销）→ FreeMint 401 → PasswordMint(错密码) 401 → 全链落空 AUTH_REQUIRED。
3. 凭密自愈：intent 换 devpass → **auth recovered via PasswordMint** → Online。

**docker 腿**（容器内真实 dsh + 插件，webserver 0.0.0.0 patch + `--trusted-host 10.0.2.2:8181` 放行官方 /api 栅栏；模拟器经 10.0.2.2 = 非回环对端）：
4. 负对照（未设密码）：FreeMint **403** + PasswordMint **403**（未设态拒绝一切非回环）→ AUTH_REQUIRED——fail-closed 在 app 面实证。
5. 密码态：容器内回环 rotate devpass → app PasswordMint → `Online(authenticated=true)`；UI dump 目检：Sessions 列表页、服务器 PW-Docker-Password 在列、无 token 横幅、Empty directory（新 home 零会话）。

**证据**：`dsh-password-login/docs/emu-evidence/`（01 免密即连截图、02 宿主密码态截图、03 docker 密码态截图、logcat-chain.txt）；app 侧判读行 `DshConnRegistry: auth recovered ... via FreeMint/PasswordMint`、`session mint not taken: HTTP 403 (free/bearer)` 全部在案。

**工具坑（备查）**：软渲染下模拟器 System UI 会 ANR 挡前台（input keyevent 方向键+回车选 Wait 消掉再 dump）；logcat grep "mint" 会误中 "mainline"。真机腿（e69a99d8）保留为可选复验——仪器面已被模拟器形态全覆盖。

## 全链补测轮——web 三链 + 模拟器两腿（2026-10-05）

用户追问"模拟器、web 各个链路都测了么"——盘点出五条空白并全部补齐：

**WEB-A 浏览器真实 Basic 登录**：内联凭据 URL（`http://u:pw@host/plugins/dsh-password-login/login`）触发浏览器 Basic 机制 → 验密 → 303 → `/` 完整认证 UI。**现象定位**：内联凭据加载的页面其 fetch 子资源被浏览器拦截（宿主自身 /api 也瘫）——纯自动化捷径产物；真实用户书签是干净 URL+原生弹窗，303 后落干净页面不受影响（干净页 fetch /status 200 实证）。原生弹窗本体无法被 Playwright 驱动，为唯一未自动化的UI壳（curl -u 等价覆盖逻辑）。
**WEB-B 面板轮换 UI 流**：设置→内置插件→登录密码→填 current/next/confirm→轮换→成功消息+表单重置+面板存活（自动换票）。
**WEB-C 外部踢线→书签重进**：curl 轮换（外部对端）→ 浏览器 cookie 处死（/status 导航 unauthorized）→ 书签凭新密码重进（303→/）→ 新 cookie admit 放行（{configured:true}）。
**EMU-D 持久化直连**：app force-stop 冷启 → probe 直接 Online(authenticated=true)，零 recoverAuth——DataStore+SecretCipher 持久化的铸票跨进程存活。
**EMU-E 真实对话流**：mock provider（OpenAI SSE 兼容，9201）+ dsh llm overlay → 模拟器 app 发送 "HelloFromEmulator" → mock 流式回复渲染「连接链路验证成功（mock 回复）」+ 模型栏 Mock Chat——app→dsh(插件票认证)→provider→SSE→UI 全环。**三证合一**：UI dump 渲染文本 + 截图（04-conversation-flow.png）+ Room WAL 直查 4 处命中（主 db 未 checkpoint 属正常）。

工具坑补录：IAB 内联凭据页 fetch 污染（见上）；dsh web 每次导航重弹预览说明/API-key 引导（坐标随状态漂移，截图定位法可靠）。

## 鲁棒性轮（2026-10-05，用户追问「直接改配置文件/web 改密/并发/不按正常操作」）

**发现并修复一个真缺陷**：路由处理器未校验 HTTP 方法（POST /session 也会走完整门禁后铸票——无鉴权绕过但违反契约）。硬化：requireMethod 守卫（GET session/login/status、POST rotate，其余 405）+ 单测 2 例（58/58 绿），本地 E2E 五阶段回归 40/40 绿。

**鲁棒性矩阵 23 断言全 PASS**（宿主真实 dsh + docker）：
- 方法滥用四组 405；非 JSON/1MB 垃圾体 400 不崩（后续请求仍活）。
- 伪认证方案：Token 方案/裸 Bearer/垃圾 Basic/空 Authorization 全 401；Bearer 带空格密码 200。
- 栅栏变体：同源放行/异名 Origin 拒/https 同 host 放行（仅比 host，与官方一致）。
- 跨权威 cookie 重放：127.0.0.1 权威票在 localhost 权威被官方 isAuthenticated 拒（authority 绑定兜底）。
- 并发：10 并发铸票全 200；双 rotate 竞态恰一生效无崩；current==next 轮换 200 且旧票处死。
- 限速后正确密码恢复（无永久锁死）。
- 信任锚欺骗（docker 非回环对端）：XFF 伪造/Host 伪造/双伪造叠加均不获免密（403）；非回环 rotate 凭正确 current 放行（设计语义）。

**yml 直改链路定性**（用户点名场景；修正 spec §G「手改 yml 仅启动期生效」假设）：0.2.0-rc.2 的 user-patch watcher **热重载**条目配置——运行中改 yml 密码：新密码即时可登、旧密码即时失效，但**已发会话不撤**（epoch 只经 /rotate 与 boot 推进）；重启后指纹不匹配 → epoch=启动时间 → 全员下线一次。操作语义：热踢人走面板轮换，yml 改密踢人须重启。README 已补勘误。

**测试脚本坑**：curl -w '%{http_code}' 并发追加无换行 → grep -c 按行误计 1（改 \n + grep -cx 200 修复）；"R6 失败"为脚本假阳性，服务端 10/10。
