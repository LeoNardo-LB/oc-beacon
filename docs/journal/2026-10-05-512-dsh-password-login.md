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
