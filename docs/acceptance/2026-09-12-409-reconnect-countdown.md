# #409 断连横幅「N 秒后重试」倒计时——实现与设备复验（2026-09-12）

- 关联：#409（本卡）/ #408 / #401 / #267
- 用户 2026-09-12 反馈：确认服务器恢复是自动重连，并希望能显示下次重试倒计时。
- 设备：emulator-5554（1080x2400 @420dpi zh-CN）；APK md5 `e84868574c2df04240808ba3dd9cfd7b`；V1 `127.0.0.1:4198`。
- 证据目录：`docs/acceptance/2026-09-12-409-reconnect-countdown/`

## 结论：PASS（倒计时实时刷新；恢复后横幅消失）

| 观测 | 横幅文案 |
|---|---|
| 停 V1 + 5s（banner_a） | 服务器已断开，正在重连…（**1** 秒后重试） |
| +3s（banner_b） | 服务器已断开，正在重连…（**2** 秒后重试） |
| +2s（banner_c） | 服务器已断开，正在重连…（**1** 秒后重试） |
| V1 恢复 + reverse 重建 + 12s（recovered） | 无横幅节点（恢复 Connected，符合 #267 Q12a 不弹恢复提示） |
| crash buffer | 0 字节 |

## 实现

1. **自动重连确认**：`SseConnectionManager` 的流循环失败后走 `calculateBackoff(attempt)` 指数退避（aggressive 5s / normal 30s / conservative 60s 上限）并无条件重试——服务器恢复即自动重连（既有行为，无需改动）。
2. **排程暴露**：新增 `_reconnectAt: MutableStateFlow<Map<String, Long>>`（serverId → 下次尝试墙钟时间），在每次退避 `delay` 前经 `backoffWithSchedule(serverId, attempt)` 登记；连接成功、连接销毁（`stopConnection`）、全停（`stopAllConnections`）时清除。公开 `reconnectAt` 快照 + `observeReconnectAt(serverId)` 流。
3. **ViewModel**：`ChatViewModel` / `SessionListViewModel` 各暴露 `serverReconnectAt: StateFlow<Long?>`（`stateIn`，初值 null —— 不在构造期访问 mock 的 `.value`）。
4. **UI**：`ServerLinkBanner(retryAtEpochMs: Long?)` —— 非空时文案用 `server_link_disconnected_banner_countdown`（`%1$d`），`rememberRetrySeconds` 每秒 tick 重算剩余秒（向上取整、最小 0）。
5. **i18n**：新增键 ×15 语言（`i18n-check.sh` PASSED：885 keys × 14 languages 全一致）。

## 未做与限制

- 未逐秒录屏；以 3 帧 dump 证明数值在变（1→2→1 反映退避重排程）。
- 未验证 aggressive/conservative 重连模式下的倒计时（同一 code path）。
- DSH token 横幅（`DshTokenNeededBanner`）不走退避排程，无倒计时（token 等待语义不同）。