# 服务器断连可感知设计（backlog #267）

> 状态：**设计定稿（2026-08-30 用户逐题裁决）待实现** · 来源：用户提出「对话界面/会话界面无法感知当前服务器是否断开」
> 决策记录：拷问轮次 Q2-Q13 的裁决全部内化于本文。

## 1. 问题

SSE 断连后，ChatScreen 与 SessionListScreen 完全不感知：界面照常可点，发送/fork/compact 等操作照常发起，直到请求失败才弹事后错误；OkHttp `retryOnConnectionFailure(true)` 还会让失败悬挂 20s+。

## 2. 现状盘点（源码实证 2026-08-30）

**已有的（不重建）**：

- **真相源已存在**：`SseConnectionManager.connectedServerIds / connectingServerIds: StateFlow<Set<String>>`——连上/断开由 `updateServerConnected()` 增删（L499-512），断连自动进入指数退避重连（`reconnectMode`：aggressive 5s / normal 30s / conservative 60s），网络恢复 `reconnectAll()`，重连成功 `recoverMessages()` REST 补漏。
- **唯一消费者是 Home**：`HomeViewModel → ServerCard.isConnected` 圆点。Chat/Session 界面零消费。
- `ConnectionErrorScreen` 组件存在但无调用方（本设计不启用它，另行登记清理）。

## 3. 设计（用户裁决：简单做——警示 + 写操作报错，不做任何 UI 禁用）

### 3.1 三态派生（新小模块，不改 SseConnectionManager）

```
sealed interface ServerLinkState {
    data object Connected : ServerLinkState
    data object Connecting : ServerLinkState   // 含重连退避期
    data object Disconnected : ServerLinkState
}
```

由 `connectedServerIds × connectingServerIds` combine 派生，按 serverId 键控（Q7a：每界面只看自己会话所属服务器，A 掉线不误伤 B）。落点：`OpenCodeConnectionService`（已转发 connectedServerIds，L156）暴露 `observeLinkState(serverId): StateFlow<ServerLinkState>`。

### 3.2 感知：常驻细条幅（Q2/Q12a）

- **ChatScreen 顶部 + SessionListScreen 顶部**各一条常驻细条幅：`Disconnected`（含 Connecting 期？见下）出现「服务器已断开，正在重连…」，恢复 `Connected` 后**直接消失，不弹「已恢复」提示**。
- 流式 turn 进行中断连（Q4）：已渲染内容保留不动，条幅照出，重连后走既有 REST 补漏——不弹窗、不回滚。
- 写操作失败仍走既有错误弹窗（发送失败 AlertDialog 等），文案区分「服务器已断开」。
- 范围（Q13）：一期仅此两界面；workspace 面板（文件树/git/terminal）已有各自局部错误态，二期另议。

### 3.3 写操作：报错即可，但快速失败（Q2/Q3/Q4/Q11a）

- **零 UI 禁用**：composer、fork/compact、新建会话、下拉刷新全部照常可点（Q11a：全局统一「写操作=报错」一个心智）。
- **薄守卫（实现细节，非门控）**：mutation 入口（ChatViewModel/SessionRepository 的 send/fork/compact/delete/新建）在 `Disconnected` 时**不发请求、快速失败**返回「服务器已断开」——否则 OkHttp 连接重试让用户干等 20s+，「报错」体验不成立。交互上仍是「点了→报错」。
- **草稿（Q3）**：断连时输入框草稿保留，发送失败不清理；重连后用户手动重发。不做离线队列（非目标）。
- **检测滞后补刀（Q6，一期）**：REST 请求 IOException/UnknownHost 时由 repository 回灌 `reportFailure(serverId)` 立即置 Disconnected——不等 SSE 读循环超时，条幅秒亮。

## 4. 非目标

- 不做离线队列/自动重发。
- 不区分「服务器进程挂」vs「网络断」（统一 Disconnected）。
- 不动 `SessionStateService` 的 idle/busy/retry（会话级活动状态，与连接级正交，勿混）。
- 不启用 `ConnectionErrorScreen` 整页错误屏（Q5 收口为缓存照常浏览，见 3.2）。

## 5. 验收草案

- 单测：三态派生矩阵；mutation 快速失败路径（Disconnected 下零网络请求，可 用 fake 验证）。
- V3 实机走查（Agent 自验）：`adb reverse --remove` 模拟断连 → 条幅出现、写操作秒报「服务器已断开」（logcat 佐证零请求发出）；恢复 reverse → 条幅消失、发送成功。
- V6（用户）：条幅观感、报错文案手感。

## 6. 工作量预估

小：真相源/重连/补漏全复用；改动 = 三态派生模块 + 两界面条幅消费 + mutation 入口薄守卫 + REST 回灌钩子。
