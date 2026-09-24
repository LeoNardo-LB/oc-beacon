# #436 DSH 断连→重连路径测绘（只侦察，零修改）

实测现象：DSH web 服务器重启后，横幅「服务器已断开，正在重连…（2 秒后重试）」>1min 不恢复，force-stop 才恢复（token 未变、服务器已健康）。

## 1. 连接生命周期全图（DSH = WireKind.MUX 分支）

| 环节 | 位置 |
|---|---|
| 连接入口 / 状态集合 | SseConnectionManager.kt:233-261（startConnection，入 connectingServerIds:258） |
| 连接主循环（重连退避） | SseConnectionManager.kt:439-635（runSseConnectionLoop） |
| DSH 握手（双形态探测） | SseConnectionManager.kt:484 → DshConnectionStrategy.kt:26-49 → DshConnectionRegistry.ensureProbed（DshConnectionRegistry.kt:130-150，双 401=TokenNeeded:285，transport null=Unreachable:284） |
| TokenNeeded 分支 | SseConnectionManager.kt:490-499：markTokenNeeded(true) → **awaitCookie 无限轮询**（DshConnectionRegistry.kt:114-120）→ cookie 就位后 continue 回环重探 |
| Unreachable 分支 | SseConnectionManager.kt:500-506：置 false → backoffWithSchedule → continue |
| 事件循环（挂起不返回） | SseConnectionManager.kt:403-426 runDshEventLoop → DshConnectionOrchestrator.kt:303-371 run（「engine 自重连不返回」:300） |
| 帧源选择/启动 | DshConnectionOrchestrator.kt:143-197（V012 → DshRemoteMuxEngine:175） |
| WS 重连引擎（muxLoop） | DshRemoteMuxEngine.kt:143-259：连→等断(:242)→退避重连(:255-257)；**401 特判(:248-254)：markAuthFailure + awaitCookie 挂起** |
| 0.1.1 双流引擎（同构） | DshWsEventClient.kt:183-226 streamLoop（退避 500ms×2ⁿ cap 10s :96-116） |
| 连接态上报 | DshRemoteMuxEngine.kt:191 onOpen→Connected；:247 断→Disconnected；聚合 SseConnectionManager.kt:514-520 onConnected → updateServerConnected(:753-774，写 connected/connecting 集合) |
| 传输失败 kick | SseConnectionManager.kt:158-182（reportTransportFailure，5s 冷却 :54,:171-176）→ reconnectServer(:330-364) |
| 重连成功信号 | onOpen:191-193 → state=Connected → updateServerConnected(true)（SseConnectionManager.kt:764-768，清 reconnectAt:767） |

## 2. 重连调度细节

- **外层循环**（probe 失败/事件循环异常退出时才走）：base 1s ×2ⁿ，cap 按 reconnectMode：aggressive 5s / normal 30s / conservative 60s（SseConnectionManager.kt:40-42,:789-797）；排程写入 _reconnectAt 供横幅倒计时（:783-787）。
- **引擎内自重连**（常态路径）：DshBackoff 500ms×2ⁿ cap 10s 带抖动（DshWsEventClient.kt:96-116；DshRemoteMuxEngine.kt:255-257）——**无最大次数，无限重试**（while(true)）。
- **静默退出/挂起点**：循环本体不会退出，但两条 awaitCookie 路径会**无限挂起**（见 §3）。外部 kick（reconnectServer）会 cancelAndJoin 后重开循环、attempt 归零（:444 attempt 是循环局部变量）——频繁 kick 时退避永远停在 1-2s，与横幅「2 秒后重试」常驻吻合。

## 3. 鉴权处理（重灾区）

- token **不持久化**，只有 cookie 持久化（SecretCipher+DataStore，DshConnectionRegistry.kt:232-255）；exchangeToken 仅由用户输入触发（MainActivity.kt:399,:475；DshTokenEntryViewModel.kt:69）。
- RPC 401 → markAuthFailure（DshRpcClient.kt:242-245）；WS 握手 401 → markAuthFailure + awaitCookie（DshRemoteMuxEngine.kt:248-254）。
- markAuthFailure 清内存 cookie **且 persistSoon 会把「已删空」的 map 落盘——持久化 cookie 被一并抹掉**（DshConnectionRegistry.kt:104-108,:214-240）。
- **awaitCookie（:114-120）是无限轮询，唯一的 cookie 供给方是用户重输 token**。且 **muxLoop 的 401 分支不 markTokenNeeded(true)**（只有外层 probe 的 AUTH_REQUIRED 分支会，SseConnectionManager.kt:492）→ 该挂起对 UI 完全不可见，无 token 输入入口，横幅停在 Connecting。

## 4. 横幅状态机

- 三态派生：ServerLinkState.kt:26-45（connected × connecting 集合，Connecting 含退避期）。
- 显示：ChatScreen.kt:706-714（!= Connected 即显示）+ SessionListScreen.kt:202-204；倒计时来自 SseConnectionManager.reconnectAt（:208-210）→ ServerLinkBanner。
- 清除：唯一清除点 = updateServerConnected(true)（SseConnectionManager.kt:765-767），它由引擎 onOpen→Connected 驱动。
- **「循环静默退出/挂起」路径确认存在**：engine 卡在 awaitCookie（§3）时 state 恒 Disconnected、无任何事件流动、无 TokenNeeded UI——横幅永挂。另：engine 内部自重连不写 reconnectAt，倒计时数据源只在「外层循环退避」时存在。

## 5. 根因候选排序

1. **【最高】WS 401 → markAuthFailure → awaitCookie 永久挂起 + 不置 TokenNeeded**（DshRemoteMuxEngine.kt:248-254；DshConnectionRegistry.kt:104-120）。服务器重启瞬间（或 cookie 服务器侧失效一次）触发一次 401 即锁死；重连引擎从此不再尝试连接。判别：logcat 查 `remote.mux 401 — 等待 token`（DshRemoteMuxEngine.kt:251）与 `probe .*slash=401 dot=401`（DshConnectionRegistry.kt:148）；若出现即坐实。
2. **【次高】外层 probe TokenNeeded 挂起**（SseConnectionManager.kt:490-498）：同款 awaitCookie 死等，但此路径会置 TokenNeeded（应出现 DshTokenNeededBanner）。判别：logcat `DSH 0.1.2 token required`（:493）+ UI 是否有 token 入口。
3. **【中】probe 判定漂移**：重启窗口内 rawProbe 返回非预期组合（如 503/404）→ Unreachable「unexpected probe statuses」（DshConnectionRegistry.kt:295）退避循环，而 cookie 实际仍有效；叠加 kick 反复重置 attempt → 永远 1-2s 间隔的小退避（解释「2 秒后重试」文案常驻）。判别：logcat `probe <base>: slash=… dot=…` 连续窗口内的状态码序列。
4. **【低】半开 TCP 感知滞后**：pingInterval 25s（DshRemoteMuxEngine.kt:26）最多拖 ~25-50s 才 onFailure，加退避 ~1min 恢复——与「超过 1 分钟不恢复」量级临界，若 logcat 只有 `remote.mux 断开（连续失败 N）` 刷屏且最终恢复，则只是慢不是死。判别：失败计数是否持续增长、是否最终 onOpen。

force-stop 能恢复这一事实对候选 1/2 构成一个张力：两条 401 路径都会把持久化 cookie 抹掉（§3），force-stop 后应需要重输 token——除非本次事件的 401 发生在**内存态判定**而持久层未被触碰，或 DSH cookie 对重启免疫。取证时优先核对 DataStore 中 dsh_cookies 是否仍含该 authority。

## 6. 服务器重启后的状态漂移

- **session seq 水位**：dshSeqTrackers 按服务器跨重连存活（SseConnectionManager.kt:115,:409），stopConnection 才清（:270）。重连后服务器重推 subscribed 基线（lastSeq=follow snapshot cursor，DshRemoteMuxEngine.kt:537-545）→ DshReconciler.plan 对比本地水位算缺口回填（DshConnectionOrchestrator.kt:375-403）——若服务器持久存储保留 seq，参数正确；若服务器 seq 归零/漂移，reconciler 按基线重拉，不会阻塞连接本身。
- **clientId**（$events 应答凭据）：每代 ready 帧覆写（DshRemoteMuxEngine.kt:178,:373-376），无漂移问题。
- **cookie**：唯一漂移敏感项——若 DSH cookie 签名密钥随进程重启轮换，旧 cookie 必 401 → 直接落入候选 1/2。
- **WS 重连不带 lastEventId/cursor 参数**（Request 仅 Cookie，DshRemoteMuxEngine.kt:236-239）——续传靠 subscribed 基线对账，设计上无陈旧参数问题。
