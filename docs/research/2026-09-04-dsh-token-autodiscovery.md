# DSH web token 自动发现调研(2026-09-04)

> 问题:App 能否不靠用户手填,自动从宿主机拿到 DSH web 认证 token?方法:读 dsh 安装包真实实现(dsh-client-connection / dsh-web-app / dsh-host-frontend-static / dsh-host-webserver,node_modules 子包)+ 本机配置实证 + 无凭据 LAN 只读探测(curl,未重启任何服务)。执行:token 自动发现专项 subagent,证据链完整。

## 1. 认证模型(结论)

- **launch token(?token=)**:`randomBytes(32)` base64url(43 字符),存进程内 WeakMap——**每次重启轮换、不落盘、不可配置**;唯一出口是启动行 stdout(web.log)。无固定 token / 关认证逃生门(CLI --host 0.0.0.0 被明确拒绝"would expose RCE to the network";配置绕过是本机特例)。
- **cookie(dsh-auth-*)**:HMAC-SHA256 签名,秘密持久化于 `~/.dsh/.credentials.yaml`(600,记录 client-connection/browser-session;⚠️ 同文件存 LLM API key——SSH 通道禁止读此文件)。payload={authority,issuedAt,expiresAt};**与 launch token 完全无关**——这解释了「URL token 重启轮换、cookie 却存活」。无滑动续期,只有 token 交换铸 cookie。
- **本机配置**:`~/.dsh/profiles/web/cordis.patch.yml` 设 host 0.0.0.0 / cookieMaxAgeDays **365** / trustedHosts [192.168.110.248:3080]。实测 Set-Cookie Max-Age=31536000。
- **authority 绑定实测**:token 交换所得 cookie 换 Host 访问 → 401。App 必须用与交换时完全相同的 authority 连接(127.0.0.1↔LAN IP 切换会失效)。

## 2. 无凭据 LAN 探测(全部只读)

- / 、/api/*(含 remote.mux WS upgrade)、无效 token/cookie → 401;跨站 Origin/sec-fetch → 403(双闸门:先 fence 后 auth)。
- 公开面仅 /assets/*.js、manifest、favicon(前端 bundle,无秘密;token 从不进前端)。
- **无 token 签发/泄露端点、无 mDNS、无 Unix socket、无查询运行中服务器 token 的 CLI**——包内不存在静默自动发现途径(设计使然:token=RCE 等价物)。

## 3. 渠道可行性矩阵

| 渠道 | 自动化 | 安全 | 成本 | 适用 |
|---|---|---|---|---|
| a. adb 注入(grep web.log → debug_token extra) | ★★★ | 高 | **极低,现成**(MainActivity 已支持;activateDebugProfile 幂等复用既有服务器) | 开发流程 |
| b. SSH(sshj,grep web.log/调 dsh-url,命令白名单) | ★★ | 高(凭据一次) | 中(~1.5MB 依赖+凭据 UI+SecretCipher) | 高级用户/无 USB |
| c. QR/粘贴配对(宿主 `~/.local/bin/dsh-url` 已存在且带 qrencode QR) | ★(一次) | 高(带外) | 极低(粘贴框现成;扫码需 CameraX 新权限) | 普通用户首选 |
| d. LAN token bridge 常驻 | ★★★ | **低-中** | 中 | 不推荐(若做仅短窗口按需启动) |
| e. mDNS / f. 代码内其他 | — | — | — | 排除(不存在) |

## 4. 何时才需要重新入场(关键洞察)

**自动发现基本只是「首次配对」问题**:交换一次 token 后 cookie 365 天/authority 有效;app 更新/重启保留(DataStore+SecretCipher),**卸载重装才丢**;日常 dsh web 重启完全无感(token 轮换只影响新设备入场)。提前失效:authority 变化、cookieMaxAgeDays 调小、.credentials.yaml browser-session 记录删除、app 数据清除。

## 5. 落地建议(→ backlog #325)

① dev 流:debug-entry.sh 变体并进 token 注入(`debug_token=$(grep -o 'token=[A-Za-z0-9_-]*' ~/.local/state/dsh-web/web.log | tail -1 | cut -d= -f2)`;sameBackend 键含 username,DSH 条目固定 debug_username 幂等命中);② QR 扫码(CameraX);③ SSH 通道(命令白名单:仅 grep web.log / dsh-url);④ sameBackend 对 DSH 条目忽略 username(现状不同 username 裂两条)。风险备注:web.log 664 组可读(token 随之)——建议收紧 640/600。
