# DSH 密码登录插件（dsh-password-login）设计（backlog #512）

> 状态：**设计定稿，未开工**。经五轮 grill 裁决收敛（2026-10-05），环境事实全部锚定 dsh 0.2.0-rc.2 源码实证（brew 安装树直读）。
> 定位：独立仓库通用 dsh 插件——**oc-beacon 与桌面浏览器都只是它的消费者**；本 spec 同时是 app 侧集成的契约来源。

## Problem Statement

DSH 的认证面只有一条路：每进程随机铸 launch token、只打印进 stdout/journal，浏览器（或任何客户端）经 `GET /?token=` 换 30 天签名 Cookie。对非浏览器客户端（oc-beacon）这意味着一整套妥协基建：捞日志脚本、token 镜像、adb 注入、深链解析；对操作者本人，每次会话失效后的恢复通道都是"去 journal 翻 token"。轮换凭据、远程形态（LAN/Tailscale）、密码化加固均无官方入口（`Authorization` 头不解析、无账号密码、无 API key）。

## Solution

在 dsh 服务端加装一个"宿主路由 + 设置面板"插件，提供三层能力：**会话铸造端点**（回环免密[未设密码态]或密码验证后直接发合法 Cookie）、**Basic 弹窗书签入口**（浏览器原生单密码对话框，从此不再碰 token）、**密码生命周期管理**（设置面板首设/改密热生效，轮换即全员下线含浏览器）。oc-beacon 集成为 app 连接回落链的一环，零新 UI；旧 token 流全量保留为无插件服务器的兼容层。

## User Stories

1. 作为 dsh 操作者，我想装完插件后手机 app 填 URL 即连，所以不用再维护捞 token 的脚本链。
2. 作为 dsh 操作者，我想在浏览器里收藏一个登录页书签，所以会话失效后不用去 journal 翻 token。
3. 作为 dsh 操作者，我想给服务器设一个密码（无用户名），所以非本机位置的客户端必须凭密码进入。
4. 作为 dsh 操作者，我想改密码后所有已发会话（含浏览器、含手机）立即失效，所以凭据泄露的止损动作只有一个且立刻生效。
5. 作为 dsh 操作者，我想让浏览器密码管理器记住登录凭据，所以日常使用零摩擦（Cookie 到期重访书签无感重铸）。
6. 作为 dsh 操作者，我想改密码时必须提供当前密码，所以拿到会话的一方不能悄悄换锁。
7. 作为 dsh 操作者，我想首次设密码只能在本机进行，所以局域网里别人不能抢注。
8. 作为 dsh 操作者，我忘了密码时可以改配置文件+重启恢复，所以有明确的物理恢复通道。
9. 作为 dsh 操作者，我想服务器纯重启（密码没动）不撤任何会话，所以日常维护零扰动。
10. 作为 oc-beacon 用户，我想连没装插件的服务器时一切照旧，所以插件不是前置依赖。
11. 作为 oc-beacon 用户，我想插件损坏/格式漂移时 app 自动退回旧 token 流，所以上游变更不会把我锁死。
12. 作为远程用户，我想经 Tailscale 从任何地方连家里的 dsh，所以不需要开路由器端口。
13. 作为远程用户，我想在局域网直连形态下用密码进入，所以不建隧道也有受控通道。
14. 作为 dsh 操作者，我想密码验证有失败限速，所以局域网低成本爆破不划算。
15. 作为 dsh 操作者，我想用 `enabled: false` 一键停用插件，所以行为与"未安装"完全一致。
16. 作为第三方客户端作者，我想按一页纸协议消费 `/session` 端点，所以任何 HTTP 客户端都能接入。
17. 作为 dsh 操作者，我想在 dsh 设置页里管理登录密码且观感与原生一致，所以不出现风格突兀的第三方界面。

## Implementation Decisions

### A. 形态与分发（最终）

- 插件名 `dsh-password-login`；**独立仓库**，工程骨架照 dsh-keepalive 模板（src/tests/e2e/scripts/AGENTS.md + 客户端构建管线）。
- **宿主路由 + 客户端设置面板**：面板经 `dsh.client` 注入 dsh 设置页；**UI 一律消费宿主模块表供给的 dsh UI 原语**（Button/Input/Pill/StateDot 等，类型 vendored，keepalive 先例），**禁止引入任何第三方 UI 组件库**（用户裁决 2026-10-05）。
- 浏览器登录入口 = **HTTP Basic 认证弹窗**（浏览器原生单密码对话框，**用户名忽略**；无自绘登录表单。用户裁决 2026-10-05）。
- 分发：GitHub 仓库（`dsh plugin add "github:<owner>/dsh-password-login#vX"`）或本地路径（开发期）；**不发 npm**。`dsh plugin add` 原样透传 pnpm，`package.json` 的 `dsh.bundle.patch` 字段实现一条命令自动挂载。
- 版本基线：**只保 dsh 0.2.0 线**（与既有裁决一致），宿主 E2E 锚定 0.2.0-rc.2；不做 0.1.x 兼容层。

### B. 对外接口契约（版本 v1；`v` 字段协商，响应只增不改）

| 端点 | 面 | 语义 |
|---|---|---|
| `GET /plugins/dsh-password-login/session` | 机器 | 铸票。未设密码：仅回环对端免密；已设密码：任何对端须 `Authorization: Bearer <密码>`。成功 `200 {ok:true, v:1, stale?:true}` + Set-Cookie；金丝雀漂移时带 `stale:true`（消费者应直接走 legacy） |
| `GET /plugins/dsh-password-login/login` | 浏览器 | 书签入口。已设密码：`401 + WWW-Authenticate: Basic realm="…"` → 浏览器原生弹窗 → 凭据重试验密（用户名忽略）→ 铸票 303 回 `/`；未设密码：回环铸票 303（零配置起步），非回环 403 |
| `GET /plugins/dsh-password-login/status` | 面板 | 已认证会话 → `{configured, stale, v}`（存在性标记，不回显值） |
| `POST /plugins/dsh-password-login/rotate` | 面板 | JSON `{current?, next}`。已设密码：须 `current`（任何对端）；未设密码：仅回环（首设）。成功 `200 {ok, v}` + **自动铸新票**（改密者凭当前密码知识保持在线，其余全体下线） |
| `POST /plugins/dsh-password-login/revoke` | 面板 | 已认证会话（admit 门）发起 → **回收所有已发会话而不改密码**（epoch 推进、指纹不动）；**绝对全员下线——不自动换票，点击者本人同样失效**，经书签重登（用户裁决 2026-10-05 二次修订；与 rotate 的"改密者存活"语义区分）；未设密码态同样生效且撤销跨纯重启保持（unset→unset 重启沿用 epoch）。**修订 2026-10-05**：恢复被裁决 #21 砍掉的按钮（用户裁决）——动机：yml 热改密码不撤已发会话的实测缺口需要"只踢人不改密"通道 |

- 面板（客户端 bundle）注入 dsh 设置页，调用 `status`/`rotate`/`revoke`；UI 仅用 dsh 宿主 UI 原语（见 §A）。
- 浏览器加固（与官方栅栏同规，**全部端点统一**）：带 `Origin` 头时必须等于 Host；`sec-fetch-site: cross-site` 一律拒绝（兼防跨站点戳 `/login` 触发 Basic 弹窗）。app 原生请求不带这些头，不受影响；面板同源天然满足。
- Basic 验密失败与 rotate 失败**共用限速器**（计入 5 次阈值）。
- `enabled: false` 时全部端点 404（装了跟没装一样，app 回落链无需理解中间态）。

### C. 密码生命周期状态机（核心模型，取代早期"两扇门"草案）

| 状态 | 铸票行为 | 说明 |
|---|---|---|
| **未设密码** | 回环对端免密；非回环 403 | 零配置起步："装完填 URL 即连" |
| **已设密码** | **任何对端一律验密**（回环也包括） | 密码是唯一用户面凭据；浏览器靠记住我，app 靠存储的密码 |

- 轮换：当前密码闸通过 → 写新密码 + epoch 推进 → **全员下线**（一切 `issuedAt < epoch` 的 Cookie 作废，含浏览器与 app）。
- 忘密恢复：yml/env 改配置 + 重启（启动时指纹不匹配 → 以启动时间为 epoch，新密码生效 + 全员下线一次）。本机文件权限即物理权威。
- 密码规约：无用户名；最小长度 4；不 trim（精确字节）；比较为 SHA-256 后 `timingSafeEqual`（防长度/时序侧信道）。
- 限速：进程内计数，连续失败 5 次后每次尝试强制 1 秒延迟；无永久锁死。

### D. 信任模型与门禁

- **唯一门禁锚点是 `req.socket.remoteAddress`**（内核报告的 TCP 对端，不可伪造），回环 = `127/8` 与 `::1`。**不用 Host 头做信任判定**（应用层字符串可伪造；官方也只拿它防 DNS rebinding）。
- 三种实际部署形态全部呈现为回环对端：直连回环（本机进程）、`adb reverse`（宿主侧 adb 代发）、`tailscale serve`（本机 tailscaled 代理）——前置特权分别为本机权限/USB 调试授权/tailnet 成员身份。
- LAN 直连形态（绑 0.0.0.0）：未设密码 → 非回环 403（fail-closed）；已设密码 → 凭密码进入。明文 HTTP 下密码可被同网段抓包——该形态已知代价，推荐 tailscale（HTTPS）。
- DNS-rebinding 推演结论：恶意页面即便骗铸 Cookie，其 authority 是攻击者域名，下游 `/api` 栅栏（Host ∈ loopback ∪ trustedHosts）会 403 拒绝——既有防线兜住最坏情况，本插件不弱化它。
- 无设备身份、无选择性撤销、无 logout：撤权 = 轮换密码或卸载插件/关隧道（与 dsh 单操作者模型同构）。

### E. Cookie 铸造规约（0.2.0-rc.2 源码逐行核对）

- 密钥：`ctx.credentials.readRecord(credentialKey('client-connection','browser-session'))` → `{kind:'grant', payload:{version:1, secret: base64url(32B)}}`。
- authority：`new URL('http://' + hostHeader).host`（WHATWG 规范化）。
- Cookie 名：`dsh-auth-` + base64url(sha256(authority))。
- 值：`v1.` + base64url(JSON) + `.` + base64url(HMAC-SHA256(secret, body))；载荷 `{version:1, authority, issuedAt, expiresAt}`（毫秒 epoch）。
- 属性：`Path=/; HttpOnly; SameSite=Strict`（无 Secure，与官方回环 HTTP 一致）。
- **寿命约束（承重）**：载荷寿命必须 ≤ 服务端配置的 `cookieMaxAgeDays`（默认 30 天），否则服务端恒拒。插件自带 `cookieDays`（默认 7）。
- **无自绘"记住我"**（用户裁决 2026-10-05）：记忆职责归**浏览器密码管理器**；所有路径（含 Basic 登录）恒按 `cookieDays` 铸。Cookie 到期后重访书签：已存凭据 → 浏览器自动重发 Basic → 无感重铸；未存 → 再弹一次窗。

### F. 撤销机制（admit 包装）

- 挂点：公开入口 `ctx.connection.admit(req)`——`/api` HTTP 路由与 remote.mux **WS 升级共用**它。包装逻辑：原始判定放行后，若请求 Cookie 的 `issuedAt < epoch` → 401。
- 三重保险：**fail-open**（包装层任何异常放行原始结果，绝不锁死操作者）；**链式安全**（启动时捕获原函数引用再包、dispose 时还原、支持他人再包）；**启动金丝雀**（自铸测试票调原始 admit 验证格式未漂移，漂移则端点响应带 `stale:true`，且撤销功能自禁用并记日志）。
- **已知边界（接受）**：admit 只在 WS 升级时执行，轮换不掐已建立的长连接（活到自然断开）；app 重连频繁实际无感。
- epoch 持久化：`{epoch, passwordFingerprint: sha256(password)}` 存 storage domain。启动时指纹匹配当前密码 → 沿用旧 epoch（纯重启永不误撤）；不匹配（yml/env 通道改密）→ epoch = 启动时间。轮换的写序为 新密码→epoch→指纹，中途崩溃落到"指纹不匹配"分支，失效方向安全。

### G. 密码存储与热更新

- 存储：插件 config 的 **volatile + secret 角色**字段（settings 服务持久化到 profile patch、热生效、读方向永不回显只报存在性）。每次请求现读活配置引用。
- 编辑通道：**只有**本插件设置面板（dsh UI 原语渲染）→ `POST /rotate`（宿主侧写 settings 服务）。dsh 自带 Settings 页不为第三方插件**自动**渲染表单（0.2.0 实证："目前没有已发布的客户端这样做"），面板是我们自己的客户端代码。
- 环境变量 `DSH_PASSWORD_LOGIN_PASSWORD` 为**初始默认**（schema default）；手改 yml = 仅启动期生效（profile 是启动期装配），文档明示"要热改走书签页"。
- volatile 字段集：`password`、`cookieDays`；`enabled` 为启动期字段（路由动态注销不值得，重启生效即可）。

### H. app 侧集成（影响面：仅 DSH 适配器；v1/v2 零改动）

- 回落链（连接前/重连/401 时逐级尝试，每级一次廉价 HTTP 往返）：有效缓存 Cookie → `GET /session` 免密 → `GET /session` 带 `ServerConfig.password` → legacy token 流（同字段当 launch token 用）→ 现有 AUTH_REQUIRED 界面。
- `ServerConfig.password` **双语义**：服务器有插件 = 配对密码；无插件 = launch token。403/401 不区分，统一落入 AUTH_REQUIRED（现有 token/密码手输 UI 兼作两者入口）。零新 UI。
- 版本协商：路径常量 + 响应 `v` 字段；app JSON 解析容忍未知字段（只增不改约定）。

### I. 兼容性预留（现在就做）

- 双侧"只增不改"：JSON 响应与解析、zod config（新字段带 `.default()`）。
- 破坏性变更走**并行路由**（如 `/v2/session`），不改旧路由语义。
- 金丝雀 + fail-open + 链式安全（见 F）。
- epoch 指纹持久化（跨重启/跨配置通道行为一致，见 F）。

### J. 环境事实锚（0.2.0-rc.2 代码实证，实现前的复核清单）

| 事实 | 位置 |
|---|---|
| cookie v1 格式/名/载荷/属性 | dsh-client-connection `lib/index.js`（encodeCookie/cookieName/requestAuthority/sessionCookie） |
| 载荷寿命 ≤ `cookieMaxAgeDays` 校验 | 同上 isAuthenticated |
| 签名密钥持久化于 credentials 记录 | 同上 initializeSecret（`~/.dsh/.credentials.yaml`） |
| `/api` 与 WS 升级共用 `connection.admit` | client-connection apply() / dsh-api-gateway upgrade |
| 插件路由认证自选（requestRejection 为 opt-in） | dsh-host-open-in-app routes |
| `readRecord` 无 scope 隔离 | dsh-credentials types |
| `dsh plugin add` = pnpm 原样透传 | dsh `lib/plugin-*.js` runProfilePnpm |
| settings volatile 热更新 + secret 脱敏 | dsh-settings README/实现 |

### K. 实施切分（四批，各自独立可验收）

| 批次 | 内容 | 验收口径 |
|---|---|---|
| ① | 插件骨架 + `/session`（未设密码态回环免密）+ app 回落链 | 真机"填 URL 即连"；宿主 E2E：铸票→带票探测 authenticated |
| ② | 密码全生命周期（Basic 登录/设置面板（dsh UI 原语）/rotate/限速/首设回环限/热更新） | E2E：设密→错拒对→改密→旧票 401；curl Basic 链（`-u :密码` 挑战→验密→铸票）；面板 Web UI 手验 |
| ③ | 撤销（admit 包装 + epoch 指纹持久化 + 金丝雀） | E2E：改密→浏览器+app 全下线→重登；重启无误撤 |
| ④ | legacy 基建退役（独立批次，用顺后另裁范围） | 捞日志/adb 注入/deep-link 清退清单过一遍 |

## Testing Decisions

- **测试哲学**：只测外部行为（HTTP 面/状态机迁移），不测内部结构；黄金判据是"真实 dsh 接受我们铸的票"。
- **宿主端 E2E（无手机，最高缝）**：真实 `dsh web` 0.2.0-rc.2 + 插件 → curl 铸票 → 带 Cookie 探测已认证 `/api` RPC → `POST /rotate` 改密 → 旧票 401。此一链锚定全部漂移依赖。
- **单测**：铸造规约期望向量（固定 secret/authority → 固定 Cookie 值）；对端回环判定（mock remoteAddress）；epoch 指纹三态（匹配沿用/不匹配重置/纯重启不动）。
- **app 侧**：DshConnectionRegistry 现有测试缝加三态用例（插件在/不在/stale）；真机走 `debug-entry.sh` 标准路径验收回落链。
- **LAN 形态**：绑 0.0.0.0 手工验证一次（非回环 403→设密后凭密进入），不为它搭容器化网络隔离自动化。
- 先例：dsh-keepalive 的 e2e/ 目录模式、oc-beacon 的 DshPairingParserTest 纯函数单测模式。

## Out of Scope

- 自绘登录/改密 HTML 表单页（Basic 弹窗 + 设置面板已覆盖；早前"宿主直出 HTML 书签页"方案废弃）。
- 第三方 UI 组件库（用户裁决明令禁止——面板仅消费 dsh 宿主 UI 原语，类型 vendored）。
- 设备身份/多设备注册表/选择性撤销/操作者分类。
- logout 操作；"仅撤销不改密"独立按钮——**裁决 #21 原砍掉，2026-10-05 用户裁决推翻恢复**（`POST /revoke`，见 §B 表；动机：yml 热改密码不撤已发会话的实测缺口）。
- 非回环免密白名单（peerAllowlist，LAN 直连已由密码覆盖）。
- 远程热安装插件（pluginInventory 只读、无安装 RPC、profile 启动期装配——三重不可行且 RCE-by-design，早期否决）。
- npm 发布、dsh 0.1.x 兼容、relay/公网中继（april-jk 形态）。
- 官方化对接（向 deepseek-harness 提 PR/issue——issue 区关闭，无通道）。

## Further Notes

- **失败哲学**：一切故障终点 = 退化回今天的 legacy 体验，无死锁路径（端点 404/503/恒 401 均回落）。
- 漂移面总账（按风险升序）：路由/密钥库 API（公开，稳）→ browser-session 记录格式（version 位）→ cookie v1 格式（版本位+金丝雀）→ admit 签名演进（fail-open）→ `cookieMaxAgeDays` 联动（双侧默认安全+文档）。
- 生态背景：官方移动端为零足迹（apps/ 无移动壳、CSS 零响应式断点、issue 区关闭）；社区第三方客户端已有 3+（april-jk relay 套件等）。本插件对浏览器操作者独立成立（书签页价值不依赖任何移动客户端）。
- 实现自由度（非承重，实现者可自定）：面板文案语言与布局细节、storage domain 键名、限速计数窗口细节、日志格式、rotate 的 HTTP 边角（400/403 细分）。
- 关联调研：认证机制代码实证与远程路线分析存于 2026-10-05 会话记忆（dsh-020-drift / dsh-mobile-ecosystem-signals）；DSH 集成全貌见 `2026-08-31-dsh-integration-design.md`（#269）。
