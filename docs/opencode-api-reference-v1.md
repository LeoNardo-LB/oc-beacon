# OpenCode Server V1 API Reference

> **范围声明（2026-08-21 明确）**：本文档是 OpenCode **V1**（1.x）服务器的 API 参考。V2（2.0 beta）服务器已移除/变更部分端点——例：`DELETE /session/{id}` 在本机 V2（beta-17728）实测 405。V2 差异与客户端适配状态见 [`v1-v2-differences.md`](v1-v2-differences.md)；个别标注「V2 实测」的小节（如 §12A Form）为后续在 V2 环境的补充验证，保留原注。
>
> 基于 opencode 源码（`packages/opencode` + `packages/core`，2026-06 版本）深度调研验证。
> 覆盖 **129 个 HTTP/WebSocket 端点**（128 REST + 1 WebSocket）+ **89 种 SSE 事件类型**。
> 每个描述均有源码证据；不确定的标注 `[待确认]`。
>
> 配套深度调研报告（按功能组）：`docs/opencode-api-deep-research/1~5-*.md`。

---

## 目录

- [通用机制](#通用机制)
- [1. Global 端点](#1-global-端点)（6 个）
- [2. Config 端点](#2-config-端点)（3 个）
- [3. Provider 端点](#3-provider-端点)（4 个）
- [4. MCP 端点](#4-mcp-端点)（8 个）
- [5. Project 端点](#5-project-端点)（5 个）
- [6. ProjectCopy 端点（实验性）](#6-projectcopy-端点实验性)（3 个）
- [7. Workspace 端点（实验性）](#7-workspace-端点实验性)（7 个）
- [8. Reference 端点](#8-reference-端点)（1 个）
- [9. Session 端点](#9-session-端点)（20 个）
- [10. Message 端点](#10-message-端点)（7 个）
- [11. Permission 端点](#11-permission-端点)（3 个）
- [12. Question 端点](#12-question-端点)（3 个）
- [12A. Form 端点（V2 form 服务，#130 实测）](#12a-form-端点v2-form-服务130-实测)（5 个）
- [13. PTY 端点](#13-pty-端点)（8 个）
- [14. TUI 端点](#14-tui-端点)（13 个）
- [15. 控制平面基础端点](#15-控制平面基础端点)（3 个）
- [16. Sync 端点](#16-sync-端点)（4 个）
- [17. Experimental 端点（实验性）](#17-experimental-端点实验性)（12 个）
- [18. Instance VCS 元信息端点](#18-instance-vcs-元信息端点)（12 个）
- [19. 跨项目控制平面端点](#19-跨项目控制平面端点)（1 个）
- [20. File Find 端点](#20-file-find-端点)（6 个）
- [21. SSE 事件体系](#21-sse-事件体系)（89 种）
- [22. 数据模型](#22-数据模型)
- [23. Token Context Usage](#23-token-context-usage)
- [端点总览](#端点总览)

---

## 通用机制

### Base URL

```
http://{host}:{port}
```

### 认证

- **方式**: HTTP Basic Auth
- **Header**: `Authorization: Basic base64(username:password)`
- **默认用户名**: `opencode`
- **密码**: 环境变量 `OPENCODE_SERVER_PASSWORD` 配置
- **Query 参数替代**: `?auth_token=base64(user:pass)`（优先级高于 Header，用于 WebSocket 等无法设置 Header 的场景）
- **⚠️ Global 端点无认证**: `GET/PATCH /global/*`、`POST /global/dispose`、`POST /global/upgrade` 注册在 `RootHttpApi`，**不经过 Authorization 中间件**。生产环境应通过反向代理加认证层或限制监听地址（仅 localhost）。

### 目录作用域 Header

- **Header**: `x-opencode-directory: <URL-encoded-path>`
- 用于指定项目工作目录上下文

<a id="workspaceroutingquery"></a>

### Workspace Routing Query（公共参数）

几乎所有实例级端点都接受这两个可选 query 参数（`middleware/workspace-routing.ts`）：

| 参数 | 类型 | 说明 |
|------|------|------|
| `directory` | `string?` | 工作目录。缺省取 `x-opencode-directory` 头或 `process.cwd()` |
| `workspace` | `string?` | 工作区 ID。用于路由到远程工作区（通过 sync 连接代理） |

> **重要**: 这两个参数虽不在各端点的业务 schema 中声明，但中间件会读取。HttpApi 会拒绝未声明却携带的参数（返回 400），所以端点 schema 需展开 `WorkspaceRoutingQueryFields`。

**路由逻辑**:
1. `workspace` 指定远程工作区 → 请求被**代理转发**到远程实例
2. `workspace` 指定本地工作区 → 使用该工作区的 `directory`
3. 未指定 → 使用 session 的 directory 或 `directory` 参数

### 通用请求/响应格式

- **Content-Type**: `application/json`
- 字段命名约定: camelCase，ID 后缀大写（如 `sessionID`, `providerID`）
- **DELETE 可带 body**: 部分 DELETE 端点（如 `/experimental/project/:id/copy`）要求 JSON body，需显式设置 `Content-Type`

### 错误码体系

| HTTP | 错误类 | 触发场景 |
|------|--------|---------|
| 400 | `InvalidRequestError` / `HttpApiError.BadRequest` | 参数校验失败 |
| 401 | `UnauthorizedError` | 未认证 |
| 403 | `ForbiddenError` / `PtyForbiddenError` | 无权限 / Origin 校验失败 |
| 404 | `ApiNotFoundError` / `SessionNotFoundError` / `MessageNotFoundError` / `ProjectNotFoundError` / `PtyNotFoundError` / `McpServerNotFoundError` / `PermissionNotFoundError` / `QuestionNotFoundError` | 资源不存在 |
| 409 | `SessionBusyError` / `ConflictError` | 会话忙碌（shell/revert/unrevert/deleteMessage）/ 资源冲突 |
| 500 | `UnknownError` / `HttpApiError.InternalServerError` | 未知内部错误（含路径逃逸检查 `Effect.die`） |
| 502 | `UpstreamError` | 上游服务错误 |
| 503 | `ServiceUnavailableError` | 服务不可用（如 sync 断开） |
| 504 | `TimeoutError` | 超时 |

---

## 1. Global 端点

> 路由：`groups/global.ts` · Handler：`handlers/global.ts` · 详细分析见 [调研报告 2](opencode-api-deep-research/2-config-provider.md#24-global-路由组6-个)。
> **⚠️ 全部端点无认证**：注册在 `RootHttpApi`（非实例级 `InstanceHttpApi`），**不经过** Instance/Workspace/Authorization 中间件。路径常量：`GlobalPaths`（`groups/global.ts:67-73`）。共 6 个端点。

### GET `/global/health`

**用途**：健康探针，用于负载均衡器/k8s 健康检查。

**返回** `200` —— `GlobalHealth`（`groups/global.ts:11-14`）：

| 字段 | 类型 | 说明 |
|------|------|------|
| `healthy` | `true`（字面量） | 恒为 `true`（unhealthy 时服务本身已不可达，无法响应） |
| `version` | `string` | OpenCode 版本（`InstallationVersion` 常量） |

**认证**：**无**（公开端点）。
**建议用法**：k8s liveness/readiness probe、LB 健康检查。注意此端点只能验证"进程存活"，无法反映内部状态（DB/上游服务等），不适合做深度健康检查。

---

### GET `/global/event`

**用途**：订阅**跨实例/跨项目**的全局事件流（SSE 长连接），推送所有目录的所有事件。

**Headers**: `Accept: text/event-stream`，可选 `x-opencode-directory`。
**认证**：**无**（公开端点）。
**行为**：使用 `handleRaw` 手动构造 SSE 流（`handlers/global.ts:33-66`）。

**SSE 编码**响应头：

| Header | 值 | 作用 |
|--------|----|------|
| `Content-Type` | `text/event-stream` | SSE 标识 |
| `Cache-Control` | `no-cache, no-transform` | 禁用缓存 |
| `X-Accel-Buffering` | `no` | 禁用 nginx 缓冲 |
| `X-Content-Type-Options` | `nosniff` | 安全头 |

**事件结构**（`GlobalEventSchema`，`groups/global.ts:36-50`，用 `payload` 包装）：

```typescript
{
  directory: string,                  // 实例目录
  project?: string,                   // 项目 ID
  workspace?: string,                 // 工作区 ID
  payload: {
    id: EventV2.ID,
    type: string,                     // 事件类型
    properties: unknown               // 事件数据
  }
}
```

**连接生命周期**（`handlers/global.ts:33-66`）：
1. **首个事件**：`{ type: "server.connected", properties: {} }`
2. **后续事件**：所有发布到 `GlobalBus` 的事件（实例级事件会通过 `EventV2Bridge` 桥接到 GlobalBus）
3. **心跳**：每 10 秒发送 `{ type: "server.heartbeat", properties: {} }`
4. **不会因实例销毁关闭**：监听全局 bus，实例销毁后仍能接收新实例的事件

**payload 特殊形式 — sync 事件**：当事件类型在 registry 中标记为 `sync: true` 时，会额外发布一个 `payload.type = "sync"` 的事件，包裹原始事件和 `seq`/`aggregateID` 信息（用于跨工作区事件溯源同步）。

**建议用法**：需要监听多个项目目录事件的客户端（如多项目仪表盘）。完整事件类型清单见 [21. SSE 事件体系](#21-sse-事件体系) 与 [调研报告 5](opencode-api-deep-research/5-sse-events.md)。

---

### GET `/global/config`

**用途**：获取全局配置（位于 `~/.config/opencode/opencode.json`，跨所有项目共享）。

**返回** `200`: [`ConfigInfo`](#configinfo)（`ConfigV1.Info`）
**认证**：**无**。
**数据来源**：`config.getGlobal()`（`handlers/global.ts`）。

**与 `GET /config` 的区别**：`/global/config` 返回全局配置文件原貌（`~/.config/opencode/opencode.json`），而 `/config` 返回全局配置 + 当前项目级 `opencode.json` 的**合并结果**。编辑全局默认值用本端点；查看某项目实际生效配置用 `/config`。

**建议用法**：PATCH 全局配置前先 GET 获取基线（全量替换语义下不能盲改）；客户端展示"全局配置"独立页时使用。

---

### PATCH `/global/config`

**用途**：更新全局配置（跨项目共享的 `opencode.json`）。

**请求体**: [`ConfigInfo`](#configinfo) —— 完整配置对象，**全量替换**语义（未提供的字段会被清除）。
**返回** `200`: [`ConfigInfo`](#configinfo)（更新后的配置 `result.info`）。
**认证**：**无**。
**错误**：`400 BadRequest`（配置无效）。

**关键行为**（`handlers/global.ts:86-90`）：
1. `config.updateGlobal(ctx.payload)` 写入全局配置文件
2. 若 `result.changed === true`：通过 `EffectBridge` **异步**触发 `disposeAllInstancesAndEmitGlobalDisposed({ swallowErrors: true })` —— **销毁所有实例**让配置生效
3. 返回 `result.info`（重新读取后的配置，与 `/config` 的 PATCH 不同）

**⚠️ 关键副作用**：此端点会**销毁所有运行中的实例**，包括进行中的会话！客户端必须：
- 监听 `global.disposed` 事件
- 销毁后重新建立连接（可能需要重新创建实例）
- **反模式**：在用户编辑配置时频繁 PATCH；应让用户在 UI 中编辑，确认后再一次性提交。

---

### POST `/global/dispose`

**用途**：清理并销毁所有 OpenCode 实例，释放资源。

**请求体**: 无（Path 无参数，Query 仅通用 `directory`/`workspace`）
**返回** `200`: `boolean`（恒 `true`）。
**认证**：**无**。
**原理**：**同步**执行 `disposeAllInstancesAndEmitGlobalDisposed()`，阻塞直到完成（与 `PATCH /global/config` 触发的异步销毁不同，这里是显式同步调用）。

**建议用法**：
- **优雅关闭**：服务停止前调用，确保所有实例清理资源（flush 日志、断开上游连接）。
- **强制配置生效**：修改了配置文件（非通过 API）后，调用此端点让所有实例重新加载。
- **⚠️ 阻塞调用**：同步执行，若实例多或有长任务，响应会延迟；客户端应设合理超时。
- 调用后客户端需监听 `global.disposed` 事件并重建实例。

---

### POST `/global/upgrade`

**用途**：升级 OpenCode 到指定或最新版本。

**请求体**（`GlobalUpgradeInput`，`groups/global.ts:52-54`，**允许空 body**）：

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `target` | `string` | 否 | 目标版本号，缺省取最新 |

**返回** `200` —— `GlobalUpgradeResult`（联合类型）：

| 变体 | 字段 |
|------|------|
| 成功 | `{ success: true, version: string }` |
| 失败 | `{ success: false, error: string }` |

**认证**：**无**。
**错误**：`400 BadRequest`。

**⚠️ Raw Handler 行为**（`handlers/global.ts:129-146`）：
- 使用 `handleRaw` 手动解析 body，支持空 body（升级到最新版）
- 空 body 或非法 JSON → `{ success: false, error: "Invalid request body" }`（400）
- 升级成功后**发布全局事件** `installation.updated`（携带新版本号）

**失败场景**：
- `installation.method() === "unknown"`（无法识别的安装方式，如手动编译）→ `{ success: false, error: "Unknown installation method" }`
- 升级过程抛错 → 错误消息回传到 `error` 字段

**建议用法**：
- 升级成功后监听 `installation.updated` 事件（携带新版本号），触发客户端提示用户重启。
- 升级是"下载 + 替换二进制"，**不会自动重启当前进程**；需配合 `POST /global/dispose` 或手动重启。
- 客户端应展示 `result.version` 让用户确认目标版本。
- **反模式**：在会话进行中静默升级——会打断用户工作。

---

## 2. Config 端点

> 路由：`groups/config.ts` · Handler：`handlers/config.ts` · 核心：`@/config/config` 的 `Config.Service` · 详细分析见 [调研报告 2](opencode-api-deep-research/2-config-provider.md#21-config-路由组3-个)。
> 中间件栈：Instance → Workspace → Authorization。共 3 个端点。

### GET `/config`

**用途**：获取当前实例（项目目录）的 OpenCode 配置（合并全局配置 + 项目级 `opencode.json`）。

**Query 参数**：`WorkspaceRoutingQuery`（`directory?`, `workspace?`）。
**返回** `200`: [`ConfigInfo`](#configinfo)（`ConfigV1.Info`，完整配置对象）。
**认证**：Basic Auth（通过 Authorization 中间件）。
**数据来源**：`configSvc.get()` 返回当前实例的配置（`handlers/config.ts`）。

**建议用法**：
- **PATCH 前必读**：由于 PATCH 是全量替换语义，修改任何字段前必须先 GET 获取完整配置作为基线。
- **启动加载**：客户端初始化时 GET 一次，缓存 `model`/`small_model`/`agent` 等关键字段。
- **配置对比**：与 `GET /global/config` 对比可得知项目级覆盖了哪些字段。

---

### PATCH `/config`

**用途**：更新当前实例的配置。

**Query 参数**：`WorkspaceRoutingQuery`。
**请求体**: [`ConfigInfo`](#configinfo) —— 完整配置对象，**全量替换**语义（未提供的字段会被清除）。
**返回** `200`: [`ConfigInfo`](#configinfo)。
**认证**：Basic Auth。
**错误**：`400 BadRequest`（配置无效）。

**⚠️ 关键行为**（`handlers/config.ts:18-22`）：
1. `configSvc.update(ctx.payload)` 写入项目级配置文件（`opencode.json`）
2. **`markInstanceForDisposal()` 标记当前实例待销毁** —— 配置变更需要重启实例才生效，SSE/WS 连接会断开
3. 直接返回 `ctx.payload`（**⚠️ 不重新读取磁盘**，返回的是输入而非文件实际内容，可能不反映 JSON 规范化/字段补全结果）

**边界情况**：
- 与 `PATCH /global/config` 的差异：本端点只销毁**当前**实例，后者销毁**所有**实例；本端点返回输入 payload，后者返回磁盘重读结果。
- 若客户端依赖返回值显示配置，可能与下次 GET 不一致——应以 GET 为准。

**建议用法**：客户端必须**先 GET 当前配置，修改后再 PATCH**（全量替换语义下，部分更新会丢失字段）。

---

### GET `/config/providers`

**用途**：列出**配置文件中显式定义**的 provider（仅来自配置，不含 models.dev 内置）。

**Query 参数**：`WorkspaceRoutingQuery`。
**返回** `200` —— `Provider.ConfigProvidersResult`（`provider.ts:1041-1045`）：

```typescript
{
  providers: Provider.Info[],          // 配置中定义的 provider 列表
  default: Record<string, string>      // providerID → 默认 modelID 映射
}
```

**与 `/provider` 端点的区别**（核心语义差异）：

| 端点 | 数据来源 | 用途 |
|------|---------|------|
| `GET /config/providers` | 仅配置文件，通过 `Provider.toPublicInfo()` 序列化 | 查看/编辑用户自定义的 provider 配置 |
| `GET /provider` | models.dev + 已连接 provider，经 disabled/enabled 过滤 | 选择可用 provider 创建会话 |

**建议用法**：UI 显示 provider 列表用 `/provider`；编辑 provider 配置用 `/config/providers`（或直接读 `/config` 的 `provider` 字段）。

---

## 3. Provider 端点

> 路由：`groups/provider.ts` · Handler：`handlers/provider.ts` · 核心：`@/provider/provider` 的 `Provider.Service` + `@/provider/auth` 的 `ProviderAuth.Service` · 详细分析见 [调研报告 2](opencode-api-deep-research/2-config-provider.md#22-provider-路由组4-个)。
> 中间件栈：Instance → Workspace → Authorization。共 4 个端点。

### GET `/provider`

**用途**：获取所有可用的 AI provider（models.dev 内置 + 已连接），含连接状态。

**Query 参数**：`WorkspaceRoutingQuery`。
**返回** `200` —— `Provider.ListResult`（`provider.ts:1034-1039`）：

| 字段 | 类型 | 说明 |
|------|------|------|
| `all` | [`ProviderInfo`](#providerinfo)`[]` | 所有可用 provider（内置 + 已连接） |
| `default` | `Record<string, string>` | 每个 provider 的默认模型 ID（`providerID → modelID`） |
| `connected` | `string[]` | 已连接（已认证）的 provider ID 列表 |

**数据来源与过滤逻辑**（`handlers/provider.ts:40-58`）：
1. `cfg.get()` 读取 `disabled_providers` 和 `enabled_providers`
2. `ModelsDev.Service.use((s) => s.get())` 获取 models.dev 全量 provider
3. **过滤**：若 `enabled_providers` 存在则仅保留白名单内；排除 `disabled_providers` 中的 provider
4. `provider.list()` 获取本地已认证 provider
5. 合并：`mapValues(filtered, fromModelsDev)` + `connected`（已连接覆盖同名）
6. 转换为 `Provider.toPublicInfo()` 后返回

**建议用法**：UI 渲染 provider 选择列表、标记已连接状态。

---

### GET `/provider/auth`

**用途**：查询每个 provider 支持的认证方式（OAuth/API Key）。

**Query 参数**：`WorkspaceRoutingQuery`。
**返回** `200`: `Record<providerId, `[`ProviderAuthMethod`](#providerauthmethod)`[]>`（即 `ProviderAuth.Methods`）。
**原理**：`svc.methods()`（`auth.ts`）收集所有 provider 插件声明的认证方法。

**字段说明** —— [`ProviderAuthMethod`](#providerauthmethod)：

| 字段 | 类型 | 说明 |
|------|------|------|
| `type` | `"oauth" \| "api"` | 认证类型 |
| `label` | `string` | 显示标签 |
| `prompts?` | `Prompt[]` | 用户需填写的字段（文本/选择，按 `type` 判别联合） |

**Prompt 结构**（联合类型）：
- **TextPrompt**：`{ type: "text", key, message, placeholder?, when? }`
- **SelectPrompt**：`{ type: "select", key, message, options: SelectOption[], when? }`

**条件显示**（`When`）：`{ key, op: "eq"|"neq", value }` —— 根据 provider 已有状态决定是否显示该 prompt。

**建议用法**：渲染 provider 认证表单；`method` 字段的索引（数组下标）将传给 `oauth/authorize` 和 `oauth/callback`。

---

### POST `/provider/{providerID}/oauth/authorize`

**用途**：启动指定 provider 的 OAuth 认证流程。

**Path 参数**：`providerID: ProviderV2.ID`。
**Query 参数**：`WorkspaceRoutingQuery`。
**请求体**（`ProviderAuth.AuthorizeInput`，`auth.ts:55-59`）：

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `method` | `Finite`（整数） | 是 | 认证方法索引（来自 `Methods[providerID][index]`） |
| `inputs` | `Record<string, string>` | 否 | 用户填写的 prompt 答案 |

**返回** `200`: [`ProviderOauthAuthorization`](#provideroauthauthorization)` | null`（`ProviderAuth.Authorization | undefined`）。

**返回字段** —— [`ProviderOauthAuthorization`](#provideroauthauthorization)（`auth.ts:49-53`）：

| 字段 | 类型 | 说明 |
|------|------|------|
| `url` | `string` | 浏览器需要访问的认证 URL |
| `method` | `"auto" \| "code"` | auto=自动回调；code=手动粘贴授权码 |
| `instructions` | `string` | 给用户的指引文本 |

**错误**：`ProviderAuthApiError`（400）。

**⚠️ Raw Handler 行为**（`handlers/provider.ts:78-91`）：
- 使用 `handleRaw` 手动解析 body，便于处理空结果
- 当 `authorize()` 解析为 `undefined`（如已认证、无需进一步重定向），HTTP 响应体是 JSON `null`（非空 body），保证客户端可 `.json()` 解析
- 错误映射到 `ProviderAuthApiError`（400）

**边界情况**：客户端若直接访问 `result.url` 会抛错，**应先检查 `result === null`**。

---

### POST `/provider/{providerID}/oauth/callback`

**用途**：处理 OAuth 回调，使用授权码换取 token，完成认证。

**Path 参数**：`providerID`。
**Query 参数**：`WorkspaceRoutingQuery`。
**请求体**（`ProviderAuth.CallbackInput`，`auth.ts:61-65`）：

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `method` | `Finite` | 是 | 认证方法索引（必须与 authorize 时一致） |
| `code` | `string` | 条件 | OAuth 授权码（`code` method 时必填；`auto` method 时缺省） |

**返回** `200`: `boolean`（恒 `true`）。
**错误**：`ProviderAuthApiError`（400）。

**错误子类型**（统一 `ProviderAuthApiError` HTTP 400，通过 `name` 区分，`groups/provider.ts:14-19`）：

| name | data 关键字段 | 触发场景 |
|------|--------------|---------|
| `ProviderAuthOauthMissing` | `providerID` | provider 不支持 OAuth |
| `ProviderAuthOauthCodeMissing` | `providerID` | `code` method 但未提供 code |
| `ProviderAuthOauthCallbackFailed` | — | token 交换失败 |
| `ProviderAuthValidationFailed` | `field`, `message` | 输入字段校验失败 |
| `BadRequest` | — | 兜底错误 |

**建议用法**：完整 OAuth 流程编排——
1. `GET /provider/auth` 获取 `methods[providerID]` 数组，渲染认证表单
2. 用户选择 method 索引 `i` 并填写 prompts → `POST /provider/{id}/oauth/authorize` `{ method: i, inputs }`
3. 根据 `result.method`：
   - `"auto"` → 无需用户操作，服务端自动接收回调（此 callback 端点可省略或由服务端内部完成）
   - `"code"` → 引导用户访问 `result.url`，拿到授权码后调用本端点 `POST /provider/{id}/oauth/callback` `{ method: i, code }`
4. 返回 `true` 后，`GET /provider` 确认该 provider 已出现在 `connected` 列表

**⚠️ method 一致性**：callback 的 `method` 索引必须与 authorize 时相同，否则校验失败。

---

> 路由：`groups/mcp.ts` · Handler：`handlers/mcp.ts` · 核心：`@/mcp` 的 `MCP.Service` · 详细分析见 [调研报告 2](opencode-api-deep-research/2-config-provider.md#23-mcp-路由组8-个)。
> 路径常量：`McpPaths`（`groups/mcp.ts:32-39`）。中间件栈：Instance → Workspace → Authorization。共 8 个端点。

**核心数据模型** —— [`MCPStatus`](#mcpstatus)（`mcp/index.ts:75-99`，5 种状态判别联合）：

| status | 额外字段 | 说明 |
|--------|---------|------|
| `connected` | — | 已连接成功 |
| `disabled` | — | 配置中禁用 |
| `failed` | `error: string` | 连接失败 |
| `needs_auth` | — | 需要完成 OAuth 认证 |
| `needs_client_registration` | `error: string` | 需要动态客户端注册（RFC 7591） |

<a id="4-mcp-端点"></a>

### GET `/mcp`

**用途**：查询所有 MCP 服务器的当前状态。

**Query 参数**：`WorkspaceRoutingQuery`。
**返回** `200`: `Record<string, `[`MCPStatus`](#mcpstatus)`>` —— key 为 MCP 名称。
**数据来源**：`mcp.status()`（`handlers/mcp.ts`）返回所有已注册 MCP 服务器的实时状态。

**建议用法**：
- **启动时加载**：客户端初始化时 GET 一次，渲染 MCP 状态面板（标记 `connected`/`failed`/`needs_auth` 等）。
- **操作后验证**：`POST /mcp`（添加）、`POST /mcp/{name}/connect`、`POST /mcp/{name}/auth/callback` 后轮询此端点确认最终状态。
- **故障排查**：`failed` 状态的 `error` 字段含失败原因；`needs_auth` 提示用户走 OAuth 流程；`needs_client_registration` 提示需要动态注册。

---

**用途**：动态添加新的 MCP 服务器到系统。

**Query 参数**：`WorkspaceRoutingQuery`。
**请求体**（`AddPayload`，`groups/mcp.ts:11-14`）：

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `name` | `string` | 是 | MCP 服务器名称 |
| `config` | [`ConfigMCPInfo`](#configmcpinfo) | 是 | 配置（local/remote 联合） |

**返回** `200`: `Record<string, `[`MCPStatus`](#mcpstatus)`>`（仅含新添加的服务器）。
**错误**：`400 BadRequest`（payload 校验失败或状态解码失败）。

**关键行为**（`handlers/mcp.ts:16-21`）：
1. `mcp.add(name, config)` 返回 `{ status }` 结果
2. **特殊处理**：若 `result` 自身就是 status 对象（含 `status` 字段），包装为 `{ [name]: result }`；否则假设 result 已是 map，直接使用
3. 通过 `Schema.decodeUnknownEffect(StatusMap)` 解码，失败映射到 `400 BadRequest`

**边界情况**：`mcp.add()` 的返回可能是单个 status 对象或已经是 status map（鸭子类型判断）。由于 schema 强制解码为 `StatusMap`，最终返回始终是 map。

**建议用法**：
- 动态添加 MCP 服务器（无需重启实例，与编辑 `opencode.json` 后 PATCH `/config` 不同）。
- 添加后立即检查返回的 status：若为 `needs_auth`，需走 OAuth 流程；若为 `failed`，检查 `config` 中的 `command`/`url` 是否正确。
- **持久化**：此端点添加的配置会写入项目级 `opencode.json`，实例销毁后依然生效。

---

**用途**：启动指定 MCP 服务器的 OAuth 认证流程（两步式第一步）。

**Path 参数**：`name: string`。
**Query 参数**：`WorkspaceRoutingQuery`。
**返回** `200` —— `AuthStartResponse`（`groups/mcp.ts:17-20`）：

| 字段 | 类型 | 说明 |
|------|------|------|
| `authorizationUrl` | `string` | 浏览器需要访问的授权 URL |
| `oauthState` | `string` | OAuth state 参数，用于回调验证 |

**错误**：`UnsupportedOAuthError`（400，MCP 不支持 OAuth）、`McpServerNotFoundError`（404）。

**前置校验**（`handlers/mcp.ts:23-34`）：
1. `mcp.supportsOAuth(name)` 检查是否支持 OAuth，不支持 → `UnsupportedOAuthError`
2. `mcp.startAuth(name)` 启动认证流程
3. `MCP.NotFoundError` 映射为 `McpServerNotFoundError`

**建议用法**：两步式认证第一步——
1. 获取 `authorizationUrl` 和 `oauthState`
2. 引导用户（或前端浏览器）访问该 URL 完成 OAuth 授权
3. 拿到回调的 `code` 后调用 `POST /mcp/{name}/auth/callback` 完成认证
- 适用 Web/远程客户端（用户在浏览器中完成认证）；本地 TUI 可直接用 `/auth/authenticate` 一键式。

---

**用途**：使用授权码完成 MCP OAuth 认证（两步式第二步）。

**Path 参数**：`name`。
**Query 参数**：`WorkspaceRoutingQuery`。
**请求体**（`AuthCallbackPayload`，`groups/mcp.ts:21-23`）：`{ code: string }` —— OAuth 授权码。
**返回** `200`: [`MCPStatus`](#mcpstatus)（认证完成后的新状态，通常为 `connected`）。
**错误**：`400 BadRequest`、`McpServerNotFoundError`（404）。
**原理**：`mcp.finishAuth(name, code)` 使用持久化的 transport（`pendingOAuthTransports` Map）完成 token 交换。

**建议用法**：两步式认证第二步——接收 `/auth` 返回的授权流程回调的 `code`，提交完成 token 交换。返回的 `MCPStatus` 通常为 `{ status: "connected" }`；若仍为 `needs_auth` 或 `failed`，检查 `code` 是否过期或已被使用。

---

**用途**：**一键式** MCP OAuth 认证 —— 启动 OAuth 流程并**阻塞等待回调**完成（服务端自动打开浏览器）。

**Path 参数**：`name`。
**Query 参数**：`WorkspaceRoutingQuery`。
**返回** `200`: [`MCPStatus`](#mcpstatus)（认证完成后的状态）。
**错误**：`UnsupportedOAuthError`、`McpServerNotFoundError`。

**与 `/auth` 的区别**（MCP OAuth 双模式）：

| 端点 | 模式 | 适用场景 |
|------|------|---------|
| `/auth` + `/auth/callback` | 两步式（先获取 URL，客户端引导用户访问，拿到 code 后交回） | Web/远程客户端，用户在另一设备完成认证 |
| `/auth/authenticate` | 一键式（服务端自动开浏览器，阻塞等待回调） | 本地 TUI/桌面客户端 |

**⚠️ 边界情况**：此端点**阻塞**等待回调，若浏览器无法打开（如无头/远程服务器）会一直挂起。**不适用于无头/远程场景**。

---

### DELETE `/mcp/{name}/auth`

**用途**：删除指定 MCP 服务器的 OAuth 凭据。

**Path 参数**：`name`。
**Query 参数**：`WorkspaceRoutingQuery`。
**返回** `200`: `AuthRemoveResponse`（`{ success: true }`）。
**错误**：`McpServerNotFoundError`（404）。
**前置校验**（`handlers/mcp.ts:64-73`）：先 `mcp.status()` 检查 `name` 是否存在，不存在 → `McpServerNotFoundError`；然后 `mcp.removeAuth(name)` 删除凭据。

**建议用法**：
- **切换账号**：删除旧凭据后重新走 OAuth 流程绑定新账号。
- **故障排查**：token 过期或失效时，清除后重新认证。
- 删除后 MCP 状态会变为 `needs_auth`，客户端应提示用户重新认证。

---

**用途**：显式触发指定 MCP 服务器的连接。

**Path 参数**：`name`。
**Query 参数**：`WorkspaceRoutingQuery`。
**返回** `200`: `boolean`（恒 `true`）。
**错误**：`McpServerNotFoundError`。
**原理**：`mcp.connect(name)` 启动连接流程。通常配置启用时会自动连接，此端点用于手动重连。

**建议用法**：
- **故障恢复**：`failed` 状态后修复配置/网络，手动重连。
- **断开后重连**：`disconnect` 后需要恢复连接时调用。
- 返回 `true` 仅表示连接流程已启动，**不代表已连接成功**；需轮询 `GET /mcp` 确认最终状态（可能仍为 `failed`）。

---

### POST `/mcp/{name}/disconnect`

**用途**：显式断开指定 MCP 服务器的连接。

**Path 参数**：`name`。
**Query 参数**：`WorkspaceRoutingQuery`。
**返回** `200`: `boolean`（恒 `true`）。
**错误**：`McpServerNotFoundError`。
**原理**：`mcp.disconnect(name)` 关闭 transport 并清理状态。

**建议用法**：
- **临时禁用**：不删除配置，仅断开连接（区别于在配置中设 `enabled: false`）。
- **资源释放**：长时间不用的 MCP 服务器可断开以释放进程/连接。
- 断开后状态变为 `disabled`（区别于 `failed`），可随时通过 `connect` 恢复。

---

> **🔴 跨章节关键发现**（详见 [调研报告 2](opencode-api-deep-research/2-config-provider.md#关键发现)）：
> 1. **配置更新必然销毁实例** — `PATCH /config` 销毁当前实例，`PATCH /global/config` 销毁所有实例。客户端必须监听 `server.instance.disposed` / `global.disposed` 并重连。
> 2. **`PATCH /config` 不重读磁盘** — 返回输入 payload 而非文件内容，可能与下次 GET 不一致；`PATCH /global/config` 则返回重读结果。
> 3. **Global 端点全部无认证** — `POST /global/dispose`、`POST /global/upgrade`、`PATCH /global/config` 可被任意客户端调用。生产环境应通过反向代理加认证层或仅监听 localhost。
> 4. **OAuth `authorize` 返回 `null`** — 客户端必须先检查 `result === null` 再访问字段。

---

## 5. Project 端点

> 路由：`groups/project.ts` · Handler：`handlers/project.ts`
> 核心：`@/project/project` 的 `Project.Service` + `ProjectV2.Service`
> 中间件栈：Instance → Workspace → Authorization

### Project 核心数据模型

**`Project.Info`** —— 项目主对象（定义在 `@/project/project.ts`）：

| 字段 | 类型 | 说明 |
|------|------|------|
| `id` | `ProjectV2.ID` | 项目 ID（`prj_` 前缀） |
| `name` | `string` | 项目显示名 |
| `icon?` | `string` | 图标（emoji 或 URL） |
| `directory` | `string` | 主目录绝对路径 |
| `worktree` | `string` | worktree 目录 |
| `vcs?` | `VcsInfo` | VCS 信息（`{ branch?, default_branch? }`） |
| `commands?` | `Record<string, string>` | 自定义命令 |

### GET `/project`

**用途**：获取所有曾经用 OpenCode 打开过的项目。

**请求参数**：

| 参数 | 位置 | 类型 | 必填 | 说明 |
|------|------|------|------|------|
| `workspace`/`directory` | Query | 公共参数 | 否 | 工作区路由 |

**返回数据结构**：`Project.Info[]`

**字段说明**：见 [Project 核心数据模型](#project-核心数据模型)。

**边界情况**：
- 无项目时返回空数组 `[]`，非 `null`
- 不含业务错误返回（除非实例/认证失败）

**数据来源/原理**：
- 源码：`handlers/project.ts`、`groups/project.ts`
- `svc.list()` 从项目元数据数据库查询所有记录

**错误码**：无业务错误码（除非实例/认证失败 → 500/401）。

**建议用法**：
- 用于项目选择器、历史列表
- 客户端可自行按访问时间排序（数据库记录顺序不保证）

### GET `/project/current`

**用途**：获取当前实例对应的项目信息。

**请求参数**：`WorkspaceRoutingQuery`（公共参数）。

**返回数据结构**：`Project.Info`

**字段说明**：见 [Project 核心数据模型](#project-核心数据模型)。

**边界情况**：
- 实例上下文中始终存在 project，不会返回 null/404（除非实例已销毁）

**数据来源/原理**：
- 源码：`handlers/project.ts`
- `(yield* InstanceState.context).project` —— 直接从实例上下文获取，**无数据库查询**，效率最高

**错误码**：无业务错误码（实例上下文始终存在 project，不会主动返回 404/null；实例已销毁时由中间件返回相应错误）。

**建议用法**：
- 优先用此端点获取当前项目（比 `/project` 过滤效率高）
- 客户端启动时立即调用，获取 `vcs`/`worktree` 等元信息

### POST `/project/git/init`

**用途**：为当前项目初始化 git 仓库。

**请求参数**：`WorkspaceRoutingQuery`（无 body）。

**返回数据结构**：`Project.Info`（初始化后的项目信息，含 `vcs` 字段）。

**字段说明**：见 [Project 核心数据模型](#project-核心数据模型)。

**边界情况**：
- ⚠️ **触发实例 reload（非 disposal）**：保留实例进程和进行中会话，但 LSP/文件监听可能需重新初始化
- 已是 git 仓库时：返回当前 Project，不重复 init

**数据来源/原理**：
- 源码：`handlers/project.ts:23-34`
- `svc.initGit({ directory, project })` 执行 `git init`
- **reload 决策逻辑**：比较新旧 `id` / `vcs` / `worktree` 三个字段，任一变化即 `markInstanceForReload(ctx, { directory, worktree, project })`；三者均不变则直接返回
- **reload vs disposal**：`markInstanceForReload` 更新实例上下文但保留进程；`markInstanceForDisposal` 销毁实例。initGit 后通常需要 reload 因为 worktree/vcs 信息变了

**错误码**：500（`git init` 执行失败 → defect，无错误 body）；无 400/404；401（认证缺失）。

**建议用法**：
- 调用后客户端应监听实例 reload 事件，重新初始化依赖 vcs 的视图
- 已知是 git 项目时不要调用（避免无谓 reload）

### PATCH `/project/{projectID}`

**用途**：更新项目元数据（名称、图标、命令）。

**请求参数**：

| 参数 | 位置 | 类型 | 必填 | 说明 |
|------|------|------|------|------|
| `projectID` | Path | `ProjectV2.ID` | 是 | 项目 ID（`prj_` 前缀） |
| `workspace`/`directory` | Query | 公共参数 | 否 | 工作区路由 |
| `name` | Body | `string` | 否 | 新名称 |
| `icon` | Body | `string` | 否 | 新图标（emoji 或 URL） |
| `commands` | Body | `Record<string, string>` | 否 | 新命令（**全量替换**，非合并） |

**返回数据结构**：`Project.Info`（更新后的项目）。

**字段说明**：见 [Project 核心数据模型](#project-核心数据模型)。Body 定义在 `groups/project.ts:12-16`（`UpdatePayload`）。

**边界情况**：
- ⚠️ `commands` 是**全量替换**语义，未提供的字段会丢失（必须传入完整的命令映射）
- 不传任何可选字段：等于无操作，但仍返回更新后的 Project
- `Project.NotFoundError` → HTTP 404（含 `projectID` + `message`）
- 参数格式错误 → `HttpApiError.BadRequest`（400）

**数据来源/原理**：
- 源码：`groups/project.ts:12-16`（`UpdatePayload`）、`handlers/project.ts`
- `svc.update()` 写入元数据数据库
- 错误处理：`Project.NotFoundError` 映射为 `ProjectNotFoundError`（404）

**错误码**：400（`HttpApiError.BadRequest`，参数格式错误）；404（`ProjectNotFoundError`，body 含 `projectID` + `message`）；401（认证缺失）。详见上"边界情况"。

**建议用法**：
- 更新前先 GET 当前 `commands`，合并后再 PATCH（避免全量替换丢失）
- 仅修改单个命令时也需传完整 `commands` 映射

### GET `/project/{projectID}/directories`

**用途**：列出指定项目在本地已知的所有目录（含 worktree）。

**请求参数**：

| 参数 | 位置 | 类型 | 必填 | 说明 |
|------|------|------|------|------|
| `projectID` | Path | `ProjectV2.ID` | 是 | 项目 ID |
| `workspace`/`directory` | Query | 公共参数 | 否 | 工作区路由 |

**返回数据结构**：`ProjectV2.Directories`

**字段说明**：`ProjectV2.Directories` 通常为字符串数组（绝对路径列表），包含主目录和所有 worktree 目录。

**边界情况**：
- 项目无 worktree：仅返回主 `directory`
- 项目不存在：`ProjectNotFoundError`（404）

**数据来源/原理**：
- 源码：`handlers/project.ts`
- `project.directories({ projectID })` 查询项目元数据 + 扫描本地 worktree

**错误码**：404（`ProjectNotFoundError`，项目不存在）；401（认证缺失）。

**建议用法**：
- 用于 worktree 选择器、目录切换 UI
- 与 `GET /experimental/worktree` 配合获取更详细的 worktree 信息

---

## 6. ProjectCopy 端点（实验性）

> 路由：`groups/project-copy.ts` · Handler：`handlers/project-copy.ts`
> 核心：`@opencode-ai/core/project/copy` 的 `ProjectCopy.Service`
> 路径前缀：`/experimental/project/:projectID/copy`
> 中间件栈：Instance → Workspace → Authorization

### ProjectCopy 核心概念

**项目副本**是项目的物理拷贝，用于在不影响原项目的情况下进行实验/分支工作。通过 **strategy**（策略）机制支持不同的复制方式（如 git worktree、文件复制等）。

**核心数据模型 `ProjectCopy.Copy`**（见 [数据模型 §ProjectCopyInfo](#projectcopyinfo)）：

| 字段 | 类型 | 说明 |
|------|------|------|
| `projectID` | `ProjectV2.ID` | 父项目 ID |
| `strategy` | `ProjectCopy.StrategyID` | 使用的策略（如 `git-worktree`） |
| `directory` | `string` | 副本绝对目录路径 |
| `name` | `string` | 副本显示名 |
| `time.created` | `number` | 创建时间戳（ms） |

### POST `/experimental/project/{projectID}/copy`

**用途**：使用指定策略创建项目副本。

**请求参数**：

| 参数 | 位置 | 类型 | 必填 | 说明 |
|------|------|------|------|------|
| `projectID` | Path | `ProjectV2.ID` | 是 | 父项目 ID |
| `workspace` | Query | string | 否 | 工作区路由 |
| `strategy` | Body | `ProjectCopy.StrategyID` | 是 | 策略 ID（如 `git-worktree`） |
| `directory` | Body | `string` | 是 | 目标目录绝对路径 |
| `name` | Body | `string` | 否 | 副本名（缺省由 LLM 生成） |
| `context` | Body | `string` | 否 | 任务描述（用于 AI 生成名称） |

**返回数据结构**：`ProjectCopy.Copy`（创建的副本信息）

**字段说明**：见 [ProjectCopy 核心概念](#projectcopy-核心概念)。Payload 定义在 `groups/project-copy.ts:19-24`（`CreatePayload`）。

**边界情况**：
- ⚠️ **AI 名称生成可能静默失败**：`name` 缺省但提供 `context` 时，调用 `title` agent（small model）生成 3-4 词名称；LLM 失败或无可用模型 → 静默 fallback 到 `Slug.create()`（随机短 ID）。客户端**无法感知**是否使用了 AI 名称
- 名称 slugify：转小写 + 非字母数字替换为 `-`
- 目标目录已存在 → `DestinationExistsError`（400）
- 源目录不存在 → `SourceDirectoryNotFoundError`（400）
- 策略不支持 → `StrategyNotFoundError`（400）
- 目录不可用（权限等）→ `DirectoryUnavailableError`（400）
- `Git.WorktreeError` 且 `forceRequired: true` 时，可通过 remove 端点的 `force: true` 强制

**数据来源/原理**：
- 源码：`groups/project-copy.ts:19-24`（`CreatePayload`）、`handlers/project-copy.ts:48-95`（AI 名称生成）、`handlers/project-copy.ts:147-156`（错误子类型）
- AI 名称生成：`title` agent + small model
- 物理拷贝：由 `ProjectCopy.Service` 委托给对应 strategy 实现
- 错误子类型 message 模板：

| Error 类 | message 模板 |
|----------|-------------|
| `SourceDirectoryNotFoundError` | `Project copy source not found: ${directory}` |
| `DestinationExistsError` | `Project copy destination already exists: ${directory}` |
| `DirectoryUnavailableError` | `Project copy directory unavailable: ${directory}` |
| `StrategyNotFoundError` | `Project copy strategy not found for: ${directory}` |

**错误码**：统一 400（`ApiProjectCopyError`，按 `message` 区分 4 种子类型——见上"边界情况"；含可选 `forceRequired: true`，仅 `Git.WorktreeError` 时出现）；401（认证缺失）。

**建议用法**：
- 名称可读性重要时，**显式传入 `name`** 字段，避免依赖 LLM
- 或检查返回的 `name` 是否像 slug（短随机字符串）判断是否为 fallback
- 创建后用返回的 `directory` 作为新实例的 directory 启动会话

### DELETE `/experimental/project/{projectID}/copy`

**用途**：移除指定的项目副本。

**请求参数**：

| 参数 | 位置 | 类型 | 必填 | 说明 |
|------|------|------|------|------|
| `projectID` | Path | `ProjectV2.ID` | 是 | 父项目 ID |
| `workspace` | Query | string | 否 | 工作区路由 |
| `directory` | Body | `string` | 是 | 待移除副本的目录 |
| `force` | Body | `boolean` | 否 | 强制移除（覆盖未提交变更） |

**返回数据结构**：`HttpApiSchema.NoContent`（HTTP 204，无 body）

**字段说明**：无返回 body。Body 定义在 `groups/project-copy.ts:25-28`（`RemovePayload`）。

**边界情况**：
- ⚠️ **DELETE 请求带 body**：HTTP DELETE 通常不带 body，但此端点要求 `RemovePayload`。某些 HTTP 代理/网关可能剥离 DELETE body → 服务端收到空 body → 400
- fetch API 原生支持 DELETE body
- 未提交变更且 `force: false` → 错误（含 `forceRequired: true` 提示）

**数据来源/原理**：
- 源码：`groups/project-copy.ts:25-28`（`RemovePayload`）、`handlers/project-copy.ts`
- 由 `ProjectCopy.Service` 委托 strategy 执行清理（如 `git worktree remove`）

**错误码**：400（`ApiProjectCopyError`：含 `forceRequired: true` 时需重试 `force: true`；body 被 HTTP 代理剥离时也走 400）；401（认证缺失）。

**建议用法**：
- 客户端需显式设置 body：
  ```javascript
  fetch(url, {
    method: "DELETE",
    body: JSON.stringify({ directory: "/path", force: false }),
    headers: { "Content-Type": "application/json" }
  })
  ```
- 若服务端返回 400 且提示 `forceRequired`，重试时传 `force: true`

### POST `/experimental/project/{projectID}/copy/refresh`

**用途**：扫描本地，发现并注册未被系统知晓的项目副本。

**请求参数**：

| 参数 | 位置 | 类型 | 必填 | 说明 |
|------|------|------|------|------|
| `projectID` | Path | `ProjectV2.ID` | 是 | 父项目 ID |
| `workspace`/`directory` | Query | 公共参数 | 否 | 工作区路由 |
| — | Body | `NoContent` | 是 | **空 body 必需**（Content-Type: application/json，body 为 `null` 或空） |

**返回数据结构**：`HttpApiSchema.NoContent`（HTTP 204）

**字段说明**：无返回 body。

**边界情况**：
- 必须发送 body（即使是空），否则可能触发 400
- 不返回发现的具体副本列表（需调用 GET 列表查看结果）

**数据来源/原理**：
- 源码：`handlers/project-copy.ts`
- `service.refresh({ projectID })` 遍历所有 strategy，每个 strategy 自扫描本地后向数据库注册遗漏的副本

**错误码**：400（`ApiProjectCopyError`，body 缺失或非 JSON；尽管 body 为空仍要求 `Content-Type: application/json`）；401（认证缺失）。

**建议用法**：
- 用户外部创建了 worktree（如手动 `git worktree add`）后调用此端点同步到系统
- 调用后再 GET `/project/{id}/directories` 或列表端点查看结果

---

## 7. Workspace 端点（实验性）

> 路由：`groups/workspace.ts` · Handler：`handlers/workspace.ts`
> 核心：`@/control-plane/workspace` 的 `Workspace.Service` + `listAdapters`
> 路径前缀：`/experimental/workspace`
> 中间件栈：Instance → Workspace → Authorization

### Workspace 核心概念

**工作区（Workspace）** 是项目下的并行工作单元，可理解为项目的"分支"。一个项目可有多个工作区，每个工作区有自己的目录、连接状态和会话历史。工作区通过 **adapter**（适配器）机制支持不同类型（本地目录、远程 SSH、Docker 等）。

**核心数据模型**（见 [数据模型 §WorkspaceInfo](#workspaceinfo) / [§WorkspaceConnectionStatus](#workspaceconnectionstatus) / [§WorkspaceAdapterEntry](#workspaceadapterentry)）：

**`Workspace.Info`**：

| 字段 | 类型 | 说明 |
|------|------|------|
| `id` | `WorkspaceID` | 工作区 ID |
| `projectID` | `ProjectV2.ID` | 所属项目 ID |
| `name` | `string` | 显示名 |
| `directory` | `string` | 工作区目录 |
| `adapter` | `string?` | 适配器类型（`local`/`ssh`/`docker` 等） |
| `extra` | `unknown?` | 适配器特定数据 |

**`Workspace.ConnectionStatus`**：

| 字段 | 类型 | 说明 |
|------|------|------|
| `workspaceID` | `WorkspaceID` | 工作区 ID |
| `connected` | `boolean` | 是否已连接 |
| `error` | `string?` | 连接错误（失败时） |

**`WorkspaceAdapterEntry`**：

| 字段 | 类型 | 说明 |
|------|------|------|
| `id` | `string` | 适配器 ID（`local`/`ssh`/`docker`） |
| `name` | `string` | 显示名 |
| `available` | `boolean` | 当前是否可用 |

### GET `/experimental/workspace/adapter`

**用途**：列出当前项目可用的所有工作区适配器类型。

**请求参数**：`WorkspaceRoutingQuery`（公共参数）。

**返回数据结构**：`WorkspaceAdapterEntry[]`

**字段说明**：见 [Workspace 核心概念](#workspace-核心概念) 中的 `WorkspaceAdapterEntry`。

**边界情况**：
- 至少返回 `local` adapter（始终可用）
- 远程 adapter（ssh/docker）的 `available` 取决于配置和运行环境

**数据来源/原理**：
- 源码：`handlers/workspace.ts`
- `listAdapters(instance.project.id)` 同步函数，从 adapter 注册表查询

**错误码**：无业务错误码（除非 401 认证、实例/中间件错误）。

**建议用法**：
- 创建工作区前先调用此端点，UI 上展示可用 adapter
- 灰显 `available: false` 的 adapter

### GET `/experimental/workspace`

**用途**：列出当前项目的所有工作区。

**请求参数**：`WorkspaceRoutingQuery`。

**返回数据结构**：`Workspace.Info[]`

**字段说明**：见 [Workspace 核心概念](#workspace-核心概念) 中的 `Workspace.Info`。

**边界情况**：
- 无工作区时返回空数组 `[]`
- 仅返回当前项目的工作区（按 `projectID` 过滤）

**数据来源/原理**：
- 源码：`handlers/workspace.ts`
- `workspace.list(instance.project)` 从数据库查询

**错误码**：无业务错误码（除非 401 认证、实例/中间件错误）。

**建议用法**：
- 工作区选择器、切换 UI
- 与 `GET /experimental/workspace/status` 配合展示连接状态

### POST `/experimental/workspace`

**用途**：为当前项目创建新工作区。

**请求参数**：

| 参数 | 位置 | 类型 | 必填 | 说明 |
|------|------|------|------|------|
| `workspace`/`directory` | Query | 公共参数 | 否 | 工作区路由 |
| (body) | Body | `Workspace.CreateInput` | 是 | 创建参数（不含 `projectID`，自动从实例上下文获取） |

**返回数据结构**：`Workspace.Info`

**字段说明**：见 [Workspace 核心概念](#workspace-核心概念) 中的 `Workspace.Info`。Payload 定义在 `groups/workspace.ts:13`（`CreatePayload`，即 `Workspace.CreateInput` 去除 `projectID`）。

**边界情况**：
- ⚠️ **插件错误以 defect 形式穿透**：绕过 `Effect.mapError`，handler 通过 `Effect.catchCause` + 遍历 cause 提取真实 error 消息
- 失败 → `ApiWorkspaceCreateError`（400，含 `message`）
- 参数错误 → `HttpApiError.BadRequest`（400）

**数据来源/原理**：
- 源码：`groups/workspace.ts:13`、`handlers/workspace.ts:33-48`
- 由 adapter 执行实际创建（如 ssh adapter 在远程主机创建目录）

**错误码**：400（`ApiWorkspaceCreateError`，含 `message`；`message` 可能来自 defect cause，需解析；或 `HttpApiError.BadRequest`，参数错误）；401（认证缺失）。

**建议用法**：
- 创建前确保 adapter 可用（参考 `GET /experimental/workspace/adapter`）
- 捕获 400 错误时，`message` 可能来自 defect cause，需要解析

### POST `/experimental/workspace/sync-list`

**用途**：注册 adapter 中存在但本地数据库中缺失的工作区。

**请求参数**：`WorkspaceRoutingQuery`（无 body）。

**返回数据结构**：`HttpApiSchema.NoContent`（HTTP 204）

**字段说明**：无返回 body。

**边界情况**：
- 不返回具体同步的工作区列表，需调用 GET 列表查看

**数据来源/原理**：
- 源码：`handlers/workspace.ts`
- `workspace.syncList(instance.project)` 遍历所有 adapter，对比数据库注册遗漏的工作区

**错误码**：无业务错误码（adapter 故障一般以 defect 形式穿透为 500）；401（认证缺失）。

**建议用法**：
- 外部修改工作区后调用，确保本地数据库一致
- 类似 ProjectCopy 的 refresh 机制

### GET `/experimental/workspace/status`

**用途**：获取当前项目所有工作区的连接状态。

**请求参数**：`WorkspaceRoutingQuery`。

**返回数据结构**：`Workspace.ConnectionStatus[]`

**字段说明**：见 [Workspace 核心概念](#workspace-核心概念) 中的 `Workspace.ConnectionStatus`。

**边界情况**：
- 仅返回当前项目的状态（过滤掉其他项目）
- 远程工作区连接失败时 `connected: false` 且 `error` 有值

**数据来源/原理**：
- 源码：`handlers/workspace.ts:55-58`
- **过滤逻辑**：
  1. `workspace.list(project)` 获取当前项目的 workspace ID 集合
  2. `workspace.status()` 返回所有 workspace 状态
  3. 过滤：`ids.has(item.workspaceID)` —— 只返回当前项目的状态

**错误码**：无业务错误码（远程工作区连接失败体现在 `connected: false` + `error` 字段，非 HTTP 错误）；401（认证缺失）。

**建议用法**：
- 工作区列表 UI 中实时展示连接状态
- 远程工作区断连时提示用户重连

### DELETE `/experimental/workspace/{id}`

**用途**：移除一个工作区。

**请求参数**：

| 参数 | 位置 | 类型 | 必填 | 说明 |
|------|------|------|------|------|
| `id` | Path | `Workspace.Info["id"]` | 是 | 工作区 ID |
| `workspace`/`directory` | Query | 公共参数 | 否 | 工作区路由 |

**返回数据结构**：`Workspace.Info | undefined`（被移除的工作区信息，若不存在则 undefined）

**字段说明**：见 [Workspace 核心概念](#workspace-核心概念) 中的 `Workspace.Info`。

**边界情况**：
- 工作区不存在：返回 undefined（**非 404**）
- 参数错误 → `HttpApiError.BadRequest`（400）

**数据来源/原理**：
- 源码：`handlers/workspace.ts`
- `workspace.remove(id)` 从数据库删除记录

**错误码**：400（`HttpApiError.BadRequest`，参数校验）；**不返回 404**——工作区不存在时返回 `undefined`；401（认证缺失）。

**建议用法**：
- 删除前可选 GET 确认存在
- 删除后客户端应刷新本地缓存

### POST `/experimental/workspace/warp`

**用途**：将会话的同步历史迁移到目标工作区，或从工作区脱离到本地项目。

**请求参数**：

| 参数 | 位置 | 类型 | 必填 | 说明 |
|------|------|------|------|------|
| `workspace`/`directory` | Query | 公共参数 | 否 | 工作区路由 |
| `id` | Body | `WorkspaceID \| null` | 是 | 目标工作区 ID（`null` 表示脱离到本地项目） |
| `sessionID` | Body | `SessionID` | 是 | 待迁移的会话 ID |
| `copyChanges` | Body | `boolean` | 是 | 是否复制本地未提交变更（通过 git patch） |

**返回数据结构**：`HttpApiSchema.NoContent`（HTTP 204）

**字段说明**：无返回 body。Payload 定义在 `groups/workspace.ts:14-18`（`WarpPayload`，`id` 使用 `Schema.NullOr(Workspace.Info.fields.id)` 允许 null）。

**边界情况**：
- ⚠️ **`id: null` 不是错误**，而是显式"脱离工作区"语义：将 session 从任何工作区迁出，回归到本地项目。这种"nullable 而非 optional"的设计是为了区分"未提供"和"显式脱离"
- 工作区不存在 → `ApiNotFoundError`（404）
- VCS patch 应用失败 → `ApiVcsApplyError`（400，`reason: "non-git"` 或 `"not-clean"`）
- 其他错误 → `ApiWorkspaceWarpError`（400）

**数据来源/原理**：
- 源码：`groups/workspace.ts:14-18`、`handlers/workspace.ts:71-90`
- **错误映射**：

| 内部错误 | API 错误 | HTTP |
|---------|---------|------|
| `Workspace.WorkspaceNotFoundError` | `ApiNotFoundError` | 404 |
| `Vcs.PatchApplyError` | `ApiVcsApplyError` | 400（含 `reason`） |
| 其他 | `ApiWorkspaceWarpError` | 400 |

**错误码**：404（`ApiNotFoundError`，工作区不存在）；400（`ApiVcsApplyError`：`reason: "non-git" | "not-clean"`；`ApiWorkspaceWarpError`：其他业务错误）；401（认证缺失）。完整映射见上表。

**建议用法**：
- "切换工作区"功能：传入新的 `id` 和 `sessionID`
- "脱离工作区"功能：传入 `id: null`
- `copyChanges: true` 时确保工作目录已 git 化且干净（否则 `non-git`/`not-clean` 错误）

---

## 8. Reference 端点

> 路由：`groups/reference.ts` · Handler：`handlers/reference.ts`
> 核心：`@/reference/reference` 的 `Reference.Service`
> 中间件栈：Instance → Workspace → Authorization

### Reference 核心概念

**引用（Reference）** 是用户在配置中定义的命名快捷方式，可以是本地路径或 git 仓库。在 OpenCode 中通过 `@alias` 或 `@alias/path` 引用，让 AI 快速访问常用代码/文档。

**返回类型 `ReferenceDescriptor`** 是按 `kind` 判别的联合类型（定义在 `groups/reference.ts:8-27`）：

**Local 引用**（`kind: "local"`）：

| 字段 | 类型 | 说明 |
|------|------|------|
| `name` | `string` | 引用别名 |
| `kind` | `"local"` | 类型判别 |
| `path` | `string` | 本地绝对路径 |

**Git 引用**（`kind: "git"`）：

| 字段 | 类型 | 说明 |
|------|------|------|
| `name` | `string` | 引用别名 |
| `kind` | `"git"` | 类型判别 |
| `repository` | `string` | git 仓库 URL |
| `path` | `string` | 仓库内路径 |
| `branch` | `string?` | 分支（仅当非默认分支时出现） |

**Invalid 引用**（`kind: "invalid"`，解析失败）：

| 字段 | 类型 | 说明 |
|------|------|------|
| `name` | `string` | 引用别名 |
| `kind` | `"invalid"` | 类型判别 |
| `repository` | `string?` | 仓库 URL（若可解析） |
| `message` | `string` | 错误信息（如"仓库不存在"、"路径无效"） |

### GET `/reference`

**用途**：列出当前工作区已解析的配置引用。

**请求参数**：`WorkspaceRoutingQuery`（公共参数）。

**返回数据结构**：`ReferenceDescriptor[]`（按 `kind` 判别联合）

**字段说明**：见 [Reference 核心概念](#reference-核心概念)。

**边界情况**：
- ⚠️ **`branch` 字段是条件性序列化的**：handler 显式地**仅当 `branch !== undefined` 时**才输出该字段（`handlers/reference.ts:13-22`，使用 `...(item.branch !== undefined ? { branch: item.branch } : {})`）。客户端**不能用 `branch === undefined` 区分"主分支"和"未指定"**——两者都不出现 `branch` 字段
- 无配置引用时返回空数组 `[]`
- 解析失败的引用以 `kind: "invalid"` 返回（非整体错误，单条引用失败不影响其他）

**数据来源/原理**：
- 源码：`groups/reference.ts:8-27`（`ReferenceDescriptor` schema）、`handlers/reference.ts:13-22`
- `reference.list()` 解析配置中的 `reference` 字段，对每条引用执行解析（本地路径校验 / git 仓库 probe）
- handler 对非 git 引用原样返回，对 git 引用做 `branch` 条件序列化以避免输出 `branch: null`

**错误码**：无业务错误码（解析失败的引用以 `kind: "invalid"` 返回，**非整体错误**，单条失败不影响其他）；401（认证缺失）。

**建议用法**：
- 客户端 `@` 补全：输入 `@` 时调用此端点列出可用引用
- 对 `kind: "invalid"` 的引用，UI 标红并展示 `message` 提示用户修复配置
- 不要依赖 `branch` 字段判断是否为默认分支

---

## 9. Session 端点

> 路由：`groups/session.ts` · Handler：`handlers/session.ts` · 详细分析见 [调研报告 1](opencode-api-deep-research/1-session-message.md)。
> 共 20 个端点（19 个来自调研报告 1，1 个 `/session/import` 保留原说明）。

### GET `/session`

**用途**：获取所有 OpenCode 会话列表，按最近更新排序。

**Query 参数**（`ListQuery`）：

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `directory` | `string?` | 否 | 工作目录（公共参数） |
| `workspace` | `string?` | 否 | 工作区 ID（公共参数） |
| `scope` | `"project"?` | 否 | 限定为当前 project scope（传 `project` 时 directory 传 undefined） |
| `path` | `string?` | 否 | 路径过滤 |
| `roots` | `boolean?`（`"true"/"false"` 字符串） | 否 | 是否只返回根会话 |
| `start` | `number?`（从字符串解析） | 否 | 起始时间戳过滤 |
| `search` | `string?` | 否 | 搜索关键词 |
| `limit` | `number?`（从字符串解析） | 否 | 数量限制 |

**返回**：`Session.Info[]`

**数据来源**：`Session.Service.list()` → 数据库查询。  
**注意**：`roots` 参数使用自定义 `QueryBoolean`（接受 `"true"`/`"false"` 字符串），而非原生 boolean。

**错误码**：400（参数校验失败，如 `limit`/`start` 非数字、`scope` 非 `"project"`）；认证缺失 → 401。

**字段说明**：`Session.Info[]` 按 `time.updated` 倒序。关键字段：`id`（`ses_` 前缀）、`parentID`（fork 关系，根会话为 `undefined`）、`time.archived`（归档时间戳，未归档则无）、`share.url`（已分享时有值）。完整 JSON 示例见 [§ Session](#session)。

**建议用法**：列表页用 `limit` + `search` 过滤；构建 fork 树时按 `parentID` 组装父子关系；归档过滤看 `time.archived` 是否存在。

---

---

### GET `/session/status`

**用途**：获取所有会话的当前状态（idle/busy/retry）。

**Query 参数**：仅 `WorkspaceRoutingQuery`（directory + workspace）。

**返回**：`Record<string, SessionStatus.Info>` —— 键为 sessionID，值为状态对象。

**数据来源**：`SessionStatus.Service.list()` → 内存中的 `InstanceState`（Map）。  
**原理**：状态不持久化，纯内存维护。idle 状态的会话不会出现在 map 中（handler 用 `Object.fromEntries` 转换，缺失的会话默认为 idle）。

**错误码**：400（参数校验）；认证缺失 → 401。

**字段说明**：`Record<sessionID, SessionStatus.Info>`，`type` 三种判别：`idle`/`busy`（无额外字段）、`retry`（含 `attempt`、`next` 时间戳、`action.{reason,provider,title,message,link?}`）。响应中**缺失的 sessionID 视为 idle**。

**建议用法**：UI 状态指示器轮询此端点（建议 1-2s 间隔）；`busy` 时禁用发送按钮；`retry` 时显示倒计时（`next - now`）与 `action.reason`；不要假定响应包含所有会话。

---

---

### GET `/session/{sessionID}`

**Path 参数**：`sessionID: SessionID`  
**Query 参数**：`WorkspaceRoutingQuery`  
**返回**：`Session.Info`  
**错误**：400（参数错误）、404（`ApiNotFoundError`，会话不存在）

**数据来源**：`Session.Service.get(sessionID)` → 数据库。  
**原理**：handler 通过 `mapStorageNotFound` 将底层 `NotFoundError` 映射为 `ApiNotFoundError`（404）。

**字段说明**：单个 `Session.Info`。注意 `revert` 字段仅在此会话处于回滚状态时存在（`{ messageID, partID?, snapshot?, diff? }`）；`summary` 字段含累计 add/delete 行数与文件列表。

**建议用法**：进入会话详情页时获取；若 `revert` 有值，UI 应提示"当前处于回滚状态"并提供 unrevert 入口；`time.archived` 有值时显示归档标识。

---

---

### GET `/session/{sessionID}/children`

**用途**：获取从指定会话 fork 出来的所有子智能体会话（旧称子会话）。

**Path 参数**：`sessionID`  
**Query 参数**：`WorkspaceRoutingQuery`（公共参数）  
**返回**：`Session.Info[]`  
**错误**：400、404（父会话不存在）  
**注意**：先 `requireSession` 验证父会话存在，再调用 `session.children()`。

**字段说明**：返回的每个 `Session.Info` 其 `parentID` 等于当前 sessionID。

**建议用法**：渲染 fork 历史树；显示"分叉自 #N 条消息"；与 `GET /session/{id}` 配合还原完整 fork 链。

---

---

### GET `/session/{sessionID}/todo`

**用途**：获取 AI 在会话中维护的 Todo 任务清单（由 AI 通过工具调用增删改）。

**Path 参数**：`sessionID`  
**Query 参数**：`WorkspaceRoutingQuery`  
**返回**：`Todo.Info[]`  
**错误**：400（参数校验）、404（会话不存在）  
**数据来源**：`Todo.Service.get()` → 数据库 `TodoTable` 查询（drizzle ORM）。

**字段说明**：每个 `Todo.Info` 含 `content`（任务描述）、`status`（`pending`/`in_progress`/`completed`/`cancelled`）、`priority`（`high`/`medium`/`low`）。

**建议用法**：渲染 TodoList 卡片；用 `status` 控制勾选状态与样式；按 `priority` 排序（high > medium > low）；此端点为只读——增删改由 AI 通过工具自动维护。

---

---

### GET `/session/{sessionID}/diff`

**用途**：获取指定消息产生的文件变更。

**Path 参数**：`sessionID`  
**Query 参数**（`DiffQuery`）：

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `directory` / `workspace` | 公共参数 | 否 | |
| `messageID` | `MessageID?` | 否 | 指定消息 ID，不传则计算整个会话的 diff |

**返回**：`Snapshot.FileDiff[]`  
**数据来源**：`SessionSummary.Service.diff()` → git diff 计算。  
**注意**：此端点**不验证 sessionID 是否存在**（无 `requireSession` 调用），直接传给 summary 服务。

**错误码**：400（参数校验）；**不返回 404**——即使 sessionID 不存在也返回空数组或当前 git diff（见上述注意）；认证缺失 → 401。

**字段说明**：每个 `Snapshot.FileDiff` 含 `file`（相对路径）、`patch`（unified diff 文本）、`additions`/`deletions`（行数）、`status`（`added`/`deleted`/`modified`）。`file`/`patch` 可能为 `null`（legacy 数据）。

**建议用法**：渲染 Diff 卡片；按 `status` 配色（added=绿、deleted=红、modified=黄）；总变更行数 = Σ(additions + deletions)；`messageID` 指定时返回单条消息的增量 diff，不指定则返回整个会话的累计 diff。

---

---

### POST `/session`

**用途**：创建新的 OpenCode 会话。

**请求体**（`Session.CreateInput`，可选 —— **空 body 也能创建**）：

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `parentID` | `SessionID?` | 否 | 父会话 ID |
| `title` | `string?` | 否 | 标题 |
| `agent` | `string?` | 否 | 默认 agent |
| `model` | `Model?` | 否 | 默认模型 `{ id, providerID, variant? }` |
| `metadata` | `Record<string, any>?` | 否 | 元数据 |
| `permission` | `PermissionV1.Ruleset?` | 否 | 权限规则（数组） |
| `workspaceID` | `WorkspaceV2.ID?` | 否 | 工作区 ID |

**返回**：`Session.Info`

**⚠️ Raw handler 行为**：此端点使用 `handleRaw`（`createRaw`），手动解析 body：
- 空 body → `create({})`（使用全部默认值）
- 非 JSON body → 400
- JSON body → `Schema.decodeUnknownEffect` 解码，失败 → 400
- **特殊处理**：`permission` 字段会被 `[...decoded.permission]` 展开为数组（兼容单对象传入？）

**数据来源**：`SessionShare.Service.create()`（注意：是 share 服务，非直接 Session.create）。

**错误码**：400（请求体非 JSON、Schema 解码失败，见上述 Raw handler 行为）；认证缺失 → 401。

**字段说明**：返回新建的 `Session.Info`。`id` 服务端生成（`ses_` 前缀）；`time.created`/`time.updated` 为当前时间戳；`version` 为当前 opencode 版本；`slug` 由标题自动派生。

**建议用法**：开新会话时调用——空 body 即创建默认会话；指定 `parentID` 配合 fork 流程；通过 `metadata` 存储自定义业务字段（如来源、标签）；指定 `agent`/`model` 覆盖用户默认配置。

---

---

### POST `/session/import`

从分享 URL 导入会话。

**请求体**: `{ "url": "string" }`

**响应** `200`: [`Session`](#session)

> ⚠️ **`[待确认]`**：此端点不在调研报告 1 中，且 D3 外部事实核查未在源码 `groups/session.ts` 中找到对应的 `HttpApiEndpoint.post("import", ...)` 注册。保留原说明以待二次核查——可能已废弃、或在其他路由文件中注册。

---

### DELETE `/session/{sessionID}`

**用途**：永久删除会话及其所有关联数据（消息、历史、Todo、快照）。

**Path 参数**：`sessionID`（必填）  
**Query 参数**：`WorkspaceRoutingQuery`  
**返回**：`boolean`（true）  
**行为**：永久删除会话及所有关联数据（消息、历史）。  
**注意**：不检查会话是否忙碌。

**错误码**：400（参数校验）；404（会话不存在）；**不返回 409**——不检查忙碌状态（见上述注意）；认证缺失 → 401。

**建议用法**：UI 删除按钮二次确认后调用；删除后从本地缓存清理该 sessionID；删除处于 `busy` 状态的会话前建议先 `abort`（非强制，但避免遗留内存状态）。

---

---

### PATCH `/session/{sessionID}`

**请求体**（`UpdatePayload`，所有字段可选）：

| 字段 | 类型 | 说明 |
|------|------|------|
| `title` | `string?` | 新标题 |
| `metadata` | `Record<string, any>?` | 新元数据（**替换**） |
| `permission` | `PermissionV1.Ruleset?` | 权限规则（**合并**，非替换） |
| `time.archived` | `ArchivedTimestamp?` | 归档时间戳 |

**返回**：更新后的 `Session.Info`

**⚠️ 关键行为**：
- `metadata`：直接调用 `setMetadata`，**完全替换**
- `permission`：调用 `Permission.merge(current.permission ?? [], newPermission)` —— **合并而非替换**！这是隐藏行为，客户端如果期望替换会踩坑
- 执行顺序：title → metadata → permission → archived，最后重新查询返回

**错误码**：400（参数校验、请求体非法）；404（会话不存在）；认证缺失 → 401。

**建议用法**：归档/取消归档传 `time.archived: Date.now()` / `null`；**修改权限时务必先 GET 当前权限再合并提交**（permission 是合并语义）；改 title/metadata 可直接覆盖；触发条件：用户改标题、归档会话、调整工具权限。

---

---

### POST `/session/{sessionID}/fork`

**用途**：在指定消息处分叉出新会话。

**请求体**（`ForkPayload`，可选 —— 空 body 也能 fork）：

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `messageID` | `MessageID?` | 否 | fork 点消息 ID（不传则在最新消息处 fork） |

**返回**：新的 `Session.Info`  
**行为**：Raw handler（`forkRaw`），空 body 等同不传 messageID。  
**标题规则**：fork 出的会话标题为 `原标题 (fork #N)`，N 递增。

**错误码**：400（请求体非法）；404（源会话不存在）；认证缺失 → 401。

**字段说明**：新会话 `Session.Info` 的 `parentID` 指向源 sessionID；`time.created` 为 fork 时刻；消息历史从源会话复制到 `messageID`（含）为止。

**建议用法**：用户在某条消息处"分叉探索新方向"时调用；fork 后跳转到新 sessionID；fork 不修改源会话，源会话仍可继续；构建 fork 树时通过 `parentID` 关联。

---

---

### POST `/session/{sessionID}/abort`

**用途**：中止正在进行的 AI 处理。  
**请求**: Path `sessionID`（必填）· Body 无  
**返回**：`boolean`（true）  
**实现**：`promptSvc.cancel(sessionID)`。  
**注意**：不检查会话是否存在，直接调用 cancel（cancel 可能是幂等的）。

**错误码**：400（参数校验）；**不返回 404**——不检查会话存在；**不返回 409**——cancel 是幂等操作，对 idle 会话调用也返回 `true`。

**建议用法**：用户点"停止"按钮时调用；调用后通过 SSE 监听 `session.status` 确认状态变为 `idle`；可安全重复调用（幂等）；即使会话已完成，调用也无副作用。

---

---

### POST `/session/{sessionID}/init`

**用途**：分析当前应用并创建 AGENTS.md 文件。

**请求体**（`InitPayload`，必填）：

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `modelID` | `ModelV2.ID` | 是 | 模型 ID |
| `providerID` | `ProviderV2.ID` | 是 | 提供商 ID |
| `messageID` | `MessageID` | 是 | 消息 ID |

**返回**：`boolean`（true）  
**原理**：内部调用 `promptSvc.command()`，command 为 `Command.Default.INIT`（值为 `"init"`），使用 `initialize.txt` 模板。任何错误映射为 400。

**错误码**：400（参数缺失、provider/model 无效、会话不存在时也映射为 400 而非 404——见上述原理）；认证缺失 → 401。

**建议用法**：UI"初始化 AGENTS.md"按钮触发；调用前需让用户选好 `providerID`/`modelID`；调用后通过 SSE 监听 `message.part.updated` 等待 AI 生成完成；生成的 AGENTS.md 文件会写入会话工作目录。

---

---

### POST `/session/{sessionID}/share`

**用途**：生成分享链接，将会话发布到 opencode 分享服务（公开可访问）。  
**请求**: Path `sessionID`（必填）· Body 无  
**返回**：`Session.Info`（含 `share.url`）  
**错误**：500（`InternalServerError`）、404  
**原理**：`SessionShare.Service.share()`，失败映射为 **500**（非 400），注释说明这是因为 share 失败可能是存储/网络问题。

**字段说明**：返回更新后的 `Session.Info`，新增 `share.url` 字段（公开访问 URL）。

**建议用法**：用户点"分享"按钮调用；成功后将 `share.url` 复制到剪贴板并展示；失败时区分 500（服务端存储/网络问题，建议重试）与 404（会话不存在）。

---

---

### DELETE `/session/{sessionID}/share`

**用途**：取消分享，撤回公开访问链接。  
**返回**：`Session.Info`  
**错误**：500、404  
**原理**：`SessionShare.Service.unshare()`，同样映射为 500。

**字段说明**：返回更新后的 `Session.Info`，`share` 字段被清除（变为 `undefined`）。

**建议用法**：用户点"取消分享"按钮调用；调用后从 UI 移除分享链接；幂等——对未分享的会话调用也返回 200；外部已缓存的 URL 可能在一段时间内仍可访问（取决于分享服务 TTL）。

---

---

### POST `/session/{sessionID}/summarize`

**用途**：使用 AI 压缩保留关键信息，生成简洁摘要。

**请求体**（`SummarizePayload`，必填）：

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `providerID` | `ProviderV2.ID` | 是 | 提供商 |
| `modelID` | `ModelV2.ID` | 是 | 模型 |
| `auto` | `boolean?` | 否 | 是否自动压缩（默认 `false`） |

**返回**：`boolean`（true）  
**原理**：
1. `revertSvc.cleanup()` 清理回滚状态
2. 获取所有消息
3. **agent 选择逻辑**：找最后一条 `role === "user"` 的消息的 `info.agent`，找不到用 `defaultAgent`
4. `compactSvc.create()` 创建压缩任务
5. `promptSvc.loop()` 执行压缩循环
6. 阻塞直到完成

**错误码**：400（参数缺失、provider/model 不存在）；404（会话不存在）；**注意阻塞调用**——长会话可能触发客户端超时（504 由代理层返回，非服务端主动）。

**建议用法**：会话上下文接近 token 上限时手动触发；`auto: true` 用于服务端自动压缩流程（客户端通常传 `false`）；调用期间 UI 显示 loading；完成后通过 SSE 接收 `session.compaction` 事件感知压缩结果。

---

---

### POST `/session/{sessionID}/command`

**用途**：发送预定义命令（如 init/review）给 AI。

**请求体**（`CommandPayload` = `SessionPrompt.CommandInput` 去除 `sessionID`）：

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `messageID` | `MessageID?` | 否 | 消息 ID |
| `agent` | `string?` | 否 | agent |
| `model` | `string?` | 否 | 模型（格式：`providerID/modelID`） |
| `arguments` | `string` | 是 | 命令参数 |
| `command` | `string` | 是 | 命令名（如 `"init"`、`"review"`） |
| `variant` | `string?` | 否 | 变体 |
| `parts` | `FilePartInput[]?` | 否 | 文件附件（仅 file 类型） |

**返回**：`SessionV1.WithParts`  
**注意**：`model` 是字符串格式（`"provider/model"`），与 PromptInput 的 `{ providerID, modelID }` 对象格式不同！

**错误码**：400（参数校验、未知 `command` 值）；404（会话不存在）；409（会话忙碌，与 prompt 不同——command 会检查 busy 状态）；认证缺失 → 401。

**字段说明**：返回 `SessionV1.WithParts`（含 user 消息和 AI 响应消息）；`command` 字段值如 `"init"`（初始化 AGENTS.md）、`"review"`（代码审查），具体可用值取决于服务端配置。

**建议用法**：实现"自定义斜杠命令"菜单时调用；**注意 `model` 用 `"providerID/modelID"` 字符串格式**（与 prompt 的对象格式不同，易踩坑）；`arguments` 是必填的命令参数字符串；调用后建议监听 SSE 获取实时进度。

---

---

### POST `/session/{sessionID}/shell`

**用途**：在 AI 上下文中执行 shell 命令（AI 解析命令输出并响应），等同于用户输入 `/sh <cmd>`。

**Path 参数**：`sessionID`（必填）  
**请求体**（`ShellPayload` = `SessionPrompt.ShellInput` 去除 `sessionID`）：

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `messageID` | `MessageID?` | 否 | 消息 ID |
| `agent` | `string` | **是** | agent（必填，注意与 prompt 的可选不同） |
| `model` | `{ providerID, modelID }?` | 否 | 模型 |
| `command` | `string` | 是 | shell 命令 |

**返回**：`SessionV1.WithParts`  
**错误**：400、404、**409（`SessionBusyError`）** —— shell 会检查会话是否忙碌。

**建议用法**：用户输入 `/sh <cmd>` 时调用；调用前检查会话状态（busy 时返回 409，需先等待或 abort）；**`agent` 字段必填**——与 prompt 的可选 agent 不同；执行结果通过 AI 解析后返回，非原始 stdout。

---

---

### POST `/session/{sessionID}/revert`

**用途**：回滚指定消息，**撤销文件变更**并恢复先前状态。

**请求体**（`RevertPayload` = `SessionRevert.RevertInput` 去除 `sessionID`）：

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `messageID` | `MessageID` | 是 | 回滚到此消息 |
| `partID` | `PartID?` | 否 | 精确到 part 级别 |

**返回**：`Session.Info`  
**错误**：400、404、**409（忙碌）**

**字段说明**：返回更新后的 `Session.Info`，`revert` 字段被填充：`{ messageID, partID?, snapshot?, diff? }`，记录回滚点信息。

**建议用法**：用户右键某条消息选"回滚到此"时调用；调用后 UI 标记 `messageID` 之后的消息为灰色（已回滚）；可通过 `unrevert` 恢复；**回滚会实际撤销文件变更**（git 层面），非仅视觉隐藏；若指定 `partID` 则精确到 part 级别回滚。

---

---

### POST `/session/{sessionID}/unrevert`

**用途**：恢复所有之前被回滚的消息。  
**无请求体**  
**返回**：`Session.Info`  
**错误**：400、404、**409（忙碌）**

**字段说明**：返回更新后的 `Session.Info`，`revert` 字段被清除（变为 `undefined`）。

**建议用法**：用户点"撤销回滚"按钮时调用；前提是会话当前有 revert 状态（可通过 `GET /session/{id}` 检查 `revert` 字段是否存在）；unrevert 后**文件变更恢复**（git 层面）；UI 应同步取消灰色标记。

---


---

## 10. Message 端点

> 涵盖消息列表/详情/删除/发送（同步+异步）/Part 操作。详细分析见 [调研报告 1](opencode-api-deep-research/1-session-message.md)。
> 共 7 个端点（全部来自调研报告 1）。

<a id="get-sessionsessionidmessage"></a>

### GET `/session/{sessionID}/message`

**用途**：获取会话中的所有消息（含 user 和 assistant）。

**Path 参数**：`sessionID`  
**Query 参数**（`MessagesQuery`）：

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `directory` / `workspace` | 公共参数 | 否 | |
| `limit` | `integer ≥ 0?` | 否 | 分页大小 |
| `before` | `string?` | 否 | 游标（base64url 编码的 `{id, time}`） |

**返回**：`SessionV1.WithParts[]`

**⚠️ 分页行为（关键隐藏行为）**：

1. 如果传了 `before` 但没传 `limit` → **400 BadRequest**
2. 如果传了 `before` → 先尝试 `MessageV2.cursor.decode(before)`，解码失败 → **400**
3. 如果 `limit` 未传或为 0 → 返回全部消息（无分页），普通 JSON 响应
4. 如果 `limit > 0` → 分页模式：
   - 查询 `limit + 1` 条，按 `time_created DESC, id DESC` 排序
   - 结果 `reverse()` 恢复为时间正序
   - 如果有更多数据（`rows.length > limit`），响应包含额外 header：
     - `Link: <完整URL>; rel="next"`（URL 带 `limit` 和 `before=cursor` 参数）
     - `X-Next-Cursor: <cursor>`（base64url 编码）
     - `Access-Control-Expose-Headers: Link, X-Next-Cursor`
   - 如果无更多数据 → 普通 JSON 响应（无额外 header）
5. sessionID 不存在 → `NotFoundError` 映射为 404

**游标格式**：`base64url(JSON.stringify({ id: MessageID, time: number }))`  
**排序逻辑**：`older(cursor)` = `time < cursor.time OR (time == cursor.time AND id < cursor.id)`

**错误码**：400（`before` 无 `limit`、`before` 解码失败）；404（sessionID 不存在）；认证缺失 → 401。

**字段说明**：返回 `SessionV1.WithParts[]`，每个元素 `{ info, parts }`。`info.role` 为 `"user"` 或 `"assistant"`；按时间正序排列；assistant 消息的 `info.error` 可能含 7 种错误类型之一。

**建议用法**：首次加载不传 `limit` 获取全部消息；分页加载用 `limit=50` + 上次响应的 `X-Next-Cursor` 作为下次请求的 `before`；**必须解析 `Link`/`X-Next-Cursor` 响应头**而非 body 字段判断是否有更多数据；旧消息在前，新消息在后。

---

---

### GET `/session/{sessionID}/message/{messageID}`

**用途**：获取单条消息（含所有 part）。

**Path 参数**：`sessionID`、`messageID`  
**Query 参数**：`WorkspaceRoutingQuery`  
**返回**：`SessionV1.WithParts`  
**错误**：400、404（消息或会话不存在）  
**数据来源**：`MessageV2.get()` → 数据库，WHERE `id = messageID AND session_id = sessionID`。

**字段说明**：单个 `SessionV1.WithParts`。若消息是 assistant 类型，`info.error` 可能含 7 种错误类型之一（`abort`/`length`/`content_filter`/`context`/`price`/`network`/`unknown`）；`info.tokens`/`info.cost` 仅 assistant 消息有。

**建议用法**：从消息列表跳转到详情页时调用；调试失败消息时检查 `info.error` 字段定位失败原因；消息已删除时返回 404。

---

---

### POST `/session/{sessionID}/message`

> ⚠️ **路径冲突警告**：此端点（POST）与 [消息列表](#get-sessionsessionidmessage)（GET）共享 `/session/{sessionID}/message` 路径，仅 HTTP 方法不同。

**用途**：创建并发送新消息，**阻塞等待 AI 响应完成**后返回。

**请求体**（`PromptPayload` = `SessionPrompt.PromptInput` 去除 `sessionID`）：

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `messageID` | `MessageID?` | 否 | 预生成的消息 ID |
| `model` | `{ providerID, modelID }?` | 否 | 覆盖模型 |
| `agent` | `string?` | 否 | 覆盖 agent |
| `noReply` | `boolean?` | 否 | 不生成回复 |
| `tools` | `Record<string, boolean>?` | 否 | **@deprecated** 工具开关（已合并到 permissions） |
| `format` | `Format?` | 否 | 输出格式（text / json_schema） |
| `system` | `string?` | 否 | 系统 prompt 覆盖 |
| `variant` | `string?` | 否 | 模型变体 |
| `parts` | `PartInput[]` | 是 | 消息内容（text/file/agent/subtask） |

**parts 元素类型**（4 种联合，按 `type` 判别）：
- `text`：`{ id?, type: "text", text, synthetic?, ignored?, time?, metadata? }`
- `file`：`{ id?, type: "file", mime, url, filename?, source? }`
- `agent`：`{ id?, type: "agent", name, source? }`
- `subtask`：`{ id?, type: "subtask", prompt, description, agent, model?, command? }`

**返回**：`SessionV1.WithParts`（`application/json`，包装在 Stream 中但**单次输出**）

**⚠️ 关键行为**：
- handler 先 `requireSession` 验证会话存在
- 调用 `promptSvc.prompt()` —— **阻塞直到整个 AI 响应循环完成**
- 返回值用 `HttpServerResponse.stream(Stream.make(JSON.stringify(message)))` 包装
- 虽然是 stream 响应，但实际只推送**一个 JSON chunk**（最终消息），**不是流式响应**
- 真正的实时流式响应通过 **SSE 事件**（`session.*` / `message.*` 事件）实现
- 任何 prompt 错误映射为 **400 BadRequest**

**错误码**：400（参数校验、prompt 内部错误）；404（会话不存在，`requireSession` 失败）；认证缺失 → 401。

**字段说明**：返回最终的 `SessionV1.WithParts`（**单次 JSON 输出**，非流式）。

**建议用法**：**需要实时反馈的场景改用 `POST /prompt_async` + SSE**（见关键行为第 5 条）；本端点适合"发送后等待完整结果"的批处理场景；长任务可能触发客户端超时——超时后 AI 仍在服务端继续执行，需通过 SSE 或 `GET /session/{id}/message` 拉取最终结果。

---

---

### POST `/session/{sessionID}/prompt_async`

**用途**：异步发送消息，立即返回，AI 处理在后台进行。

**请求体**：同 `PromptPayload`  
**返回**：`204 No Content`

**⚠️ 关键行为**：
- 调用 `promptSvc.prompt()` 并用 `Effect.forkIn(scope, { startImmediately: true })` 在后台 fork
- **错误处理**：fork 的 effect 用 `Effect.catchCause` 捕获所有错误：
  - 记录日志 `Effect.logError`
  - 发布 `Session.Event.Error` 事件（type: `"session.error"`），error 为 `NamedError.Unknown`
  - **HTTP 响应始终是 204**，即使后台任务最终失败
- 客户端必须通过 SSE 监听 `session.error` 事件来感知异步失败

**错误码**：400（参数校验、会话不存在）；**HTTP 层始终返回 204**——业务错误通过 `session.error` SSE 事件通知（见上述关键行为）。

**建议用法**：**推荐用法**——立即返回 204 后通过 SSE 监听 `message.part.updated`、`session.status`、`tool.*` 等事件实时渲染 UI；**必须监听 `session.error` 捕获异步失败**（不监听则完全无感知）；适合所有需要实时反馈的聊天场景。

---

---

### DELETE `/session/{sessionID}/message/{messageID}`

**用途**：永久删除消息及其所有 part，**不撤销文件变更**。

**Path 参数**：`sessionID`、`messageID`（均必填）  
**返回**：`boolean`  
**错误**：400、404、**409（忙碌）**  
**原理**：先 `requireSession`，再 `runState.assertNotBusy`（忙碌 → 409），再 `session.removeMessage()`。  
**与 revert 的区别**：deleteMessage 不回滚文件，revert 会回滚。

**建议用法**：用户删除单条消息时调用；**不回滚文件变更**——若需撤销文件改动用 `POST /session/{id}/revert`；忙碌会话返回 409，需先 `abort` 或等待 idle；删除后消息从列表消失，但 AI 已写入的文件改动保留。

---

---

### DELETE `/session/{sessionID}/message/{messageID}/part/{partID}`

**用途**：删除消息中的单个 Part（如某段文本、工具调用结果、文件附件）。

**Path 参数**：`sessionID`、`messageID`、`partID`（均必填）  
**返回**：`boolean`  
**错误**：400、404  
**注意**：**不检查忙碌状态**（与 deleteMessage 不同）。

**建议用法**：用户编辑已发送消息时删除某个 part；**比 deleteMessage 宽松**——不检查 busy 状态，可在 AI 处理中调用；删除后消息剩余 parts 仍可正常渲染；适合"消息编辑"高级功能。

---

---

### PATCH `/session/{sessionID}/message/{messageID}/part/{partID}`

**用途**：更新消息中的单个 Part（如编辑文本内容、修改工具调用元数据）。

**Path 参数**：`sessionID`、`messageID`、`partID`（均必填）  
**请求体**：`SessionV1.Part`（完整的 Part 对象）  
**返回**：`SessionV1.Part`

**⚠️ ID 一致性校验**：handler 验证三重 ID 匹配，任一不符返回 400：
- `payload.id === params.partID`
- `payload.messageID === params.messageID`
- `payload.sessionID === params.sessionID`

**错误码**：400（ID 不一致、Part schema 校验失败）；404（消息或 part 不存在）；认证缺失 → 401。

**建议用法**：用户编辑已发送消息文本时调用；**body 中的 `id`/`messageID`/`sessionID` 必须与 path 参数一致**，否则返回 400；典型流程：先 `GET /message/{id}` 拿到 part → 修改 `text` 字段 → PATCH 整体回写；仅修改 `text`/`metadata` 等可变字段，`type` 不应改变。

---

## 11. Permission 端点

### GET `/permission`

列出**所有会话**的待处理权限请求。

**响应** `200`: `List<`[`PermissionRequest`](#permissionrequest)`>`

### POST `/permission/{requestID}/reply`

回复权限请求。

**请求体**:
```json
{
  "reply": "once | always | reject",
  "message": "string?"
}
```

**响应** `200`: `boolean`

**错误**: 400 / `PermissionNotFoundError`（404）

### POST `/session/{sessionID}/permissions/{permissionID}`（**已废弃**）

> ⚠️ **DEPRECATED**: OpenAPI 标注 `deprecated: true`。新接口为上面的 `POST /permission/:requestID/reply`。

**请求体**: `{ "response": "once | always | reject" }`

**响应** `200`: `boolean`

---

## 12. Question 端点

### GET `/question`

列出**所有会话**的待处理问题请求。

**响应** `200`: `List<`[`QuestionRequest`](#questionrequest)`>`

### POST `/question/{requestID}/reply`

回复问题。

**请求体**:
```json
{
  "answers": [
    ["option1", "option2"],    // 第一个问题的选中项（label 数组）
    ["optionA"]                // 第二个问题的选中项
  ]
}
```

> 外层数组对应每个 question，内层数组是对应 question 的选项（支持多选）。

**响应** `200`: `boolean`

**错误**: 400 / `QuestionNotFoundError`（404）

### POST `/question/{requestID}/reject`

拒绝问题。**无请求体**。

**响应** `200`: `boolean`

---

## 12A. Form 端点（V2 form 服务，#130 实测）

> **背景（2026-08-14 官方确认 + 真机抓帧）**：V2 的 question 工具已迁移到 **form 服务**。
> 服务器发 `form.created` SSE（metadata.kind=question），客户端通过下列端点回复/取消。
> 旧 `/api/question/request` + `question.asked` 是 stale surface（未来移除）——**App 已适配
> form API，V1 服务器不受影响（仍走 /question 端点）**。

### GET `/api/form/request`

列出**所有会话**的待处理表单（仅未回复/未取消的）。

**响应** `200`:
```json
{
  "location": { "directory": "/home/leo-tkp", "project": { "id": "global" } },
  "data": [ /* Form.Info 数组（与 form.created 事件同构） */ ]
}
```

**错误**: 401 / 400

### GET `/api/session/{sessionID}/form`

列出某会话的表单（含已结算的）。**响应** `200`: `{ "data": [Form.Info, ...] }`

### GET `/api/session/{sessionID}/form/{formID}`

获取单个表单（含已结算）。**响应** `200`: `{ "data": Form.Info }`

### GET `/api/session/{sessionID}/form/{formID}/state`

查询表单状态。**响应** `200`:
```json
{ "data": { "status": "pending" | "answered" | "cancelled", "answer": { "q0": "..." } } }
```

### POST `/api/session/{sessionID}/form/{formID}/reply`

回复表单。

**请求体**:
```json
{
  "answer": {
    "q0": "米饭",                 // 单选（string 类型）：标量
    "q1": ["Kotlin", "Rust"]      // 多选（multiselect 类型）：数组
  }
}
```

**响应** `204`（无 body）。**错误**: 404 / 409（`FormAlreadySettledError`）/ 400 / 401

### POST `/api/session/{sessionID}/form/{formID}/cancel`

取消表单。**无请求体**。**响应** `204`。**错误**: 404 / 409 / 400 / 401

### Form.Info 结构（实测 next-17430）

```json
{
  "id": "frm_...",
  "sessionID": "ses_...",
  "title": "Questions",
  "metadata": {
    "kind": "question",
    "tool": { "messageID": "msg_...", "id": "call_..." }
  },
  "fields": [
    {
      "key": "q0",
      "title": "今天吃什么",
      "description": "问题1：今天想吃什么？",
      "type": "string",
      "options": [ { "value": "米饭", "label": "米饭", "description": "..." } ],
      "custom": true
    }
  ]
}
```

> field.type 语义：`string`（+options）= 单选；`multiselect` = 多选；`custom=true` 允许自由输入。
> 单选 answer 提交 `option.value`（标量字符串），多选提交 value 数组。
> 字段类型：string / multiselect / number / integer / boolean / external。

---

## 13. PTY 端点

> 路由：`groups/pty.ts` · Handler：`handlers/pty.ts`
> 核心：`packages/core/src/pty.ts`（Service/Schema）+ `packages/core/src/pty/ticket.ts`（票据）+ `server/shared/pty-ticket.ts`（路径识别）
> **中间件栈**：Instance → Workspace → Authorization（WebSocket 连接用专用 `PtyConnectAuthorization`）
> **端点数**：8（7 个 HTTP + 1 个 WebSocket）

### 核心机制：WebSocket 票据认证（三步流程）

浏览器原生 `new WebSocket(url)` 无法设置 `Authorization` 头，OpenCode 通过一次性短时票据桥接认证：

```
客户端                                服务端
  │                                     │
  │── 1. POST /pty/:id/connect-token ──→│  需 Basic Auth + Origin 校验 + x-opencode-ticket:1 头
  │                                     │  tickets.issue() → crypto.randomUUID(), 60s TTL
  │←────── { ticket, expires_in:60 } ───│
  │                                     │
  │── 2. ws://.../pty/:id/connect ──────→│  URL 带 ?ticket=<uuid> → 跳过 Basic Auth
  │         ?ticket=<uuid>&cursor=N      │  tickets.consume() → 原子校验+删除
  │←────── HTTP 101 Switching ──────────│
  │                                     │
  │←── 3a. 历史缓冲回放 (≤64KB/帧) ──────│
  │←── 3b. 控制帧 [0x00 + JSON{cursor}] │
  │←──→ 4. 双向 PTY 数据流 ─────────────│
```

**票据生命周期**（来源：`core/pty/ticket.ts:40-54`）：

| 属性 | 值 | 说明 |
|------|-----|------|
| 格式 | `crypto.randomUUID()` | UUID v4 |
| 默认 TTL | **60 秒** | `DEFAULT_TTL = Duration.seconds(60)`（ticket.ts:8） |
| 容量上限 | **10,000** | `CAPACITY = 10_000`（ticket.ts:9），基于 effect `Cache` 的 LRU |
| 绑定维度 | `(ptyID, directory, workspaceID)` | 消费时三元组必须完全匹配（ticket.ts:29-33） |
| 消费模式 | **一次性** | `Cache.invalidateWhen` 原子删除，匹配后立即失效 |
| 过期 | 自动 | effect Cache 内置 TTL 过期清理 |

> **为什么需要票据**：WebSocket 升级请求由浏览器发起，`Authorization` 头无法在 `new WebSocket(url)` 中设置。OpenCode 让客户端先用普通 HTTP 获取一次性票据，再把票据放在 WebSocket URL 的 query string 中完成认证（`server/shared/pty-ticket.ts:13-15` 的 `hasPtyConnectTicketURL` 识别此模式，Auth 中间件据此跳过 Basic 校验）。
>
> **额外的 connect-token 请求头**：`POST /connect-token` 除了 Basic Auth 外，还要求请求头 `x-opencode-ticket: 1`（`PTY_CONNECT_TOKEN_HEADER`，pty-ticket.ts:2）+ 通过 CORS Origin 校验（`validOrigin()`，handlers/pty.ts:129）。两者缺一不可，否则 `403 PtyForbiddenError`。

### PTY 数据模型

**`Pty.Info`**（来源：`core/pty.ts:45-54`）— 所有返回 PTY 信息的端点共用，规范化定义见 [PtyInfo](#ptyinfo)：

| 字段 | 类型 | 说明 |
|------|------|------|
| `id` | `PtyID`（`"pty"` 前缀 branded 字符串） | 会话 ID，格式如 `pty_01HZ...` |
| `title` | `string` | 显示标题，默认 `Terminal <id后4位>` |
| `command` | `string` | 实际执行的命令（如 `/bin/bash`） |
| `args` | `string[]` | 命令参数数组 |
| `cwd` | `string` | 工作目录 |
| `status` | `"running" \| "exited"` | 进程状态 |
| `pid` | `NonNegativeInt` | 子进程 PID。Windows ConPTY 异步分配，spawn 时可能为 `0` |

**`Pty.CreateInput`**（`core/pty.ts:58-64`）— 所有字段可选，规范化定义见 [PtyCreateRequest](#ptycreaterequest)：

| 字段 | 类型 | 说明 |
|------|------|------|
| `command` | `string?` | 命令，缺省取 `Shell.preferred(config.shell)` |
| `args` | `string[]?` | 参数，若 shell 支持 login 模式自动追加 `-l` |
| `cwd` | `string?` | 工作目录，缺省取实例目录 |
| `title` | `string?` | 显示标题 |
| `env` | `Record<string,string>?` | 额外环境变量（与 `process.env` + 插件 `shell.env` 合并） |

**`Pty.UpdateInput`**（`core/pty.ts:76-84`），规范化定义见 [PtyUpdateRequest](#ptyupdaterequest)：

| 字段 | 类型 | 说明 |
|------|------|------|
| `title` | `string?` | 新标题 |
| `size` | `{ rows: PositiveInt, cols: PositiveInt }?` | 终端尺寸，触发 `process.resize(cols, rows)` |

### PTY WebSocket 协议详解

**连接后服务端先发送的内容**（`core/pty.ts:262-303` 的 `connect` 实现）：

1. **历史缓冲回放**（如果 `cursor` 有效）：
   - 每个 PTY 会话维护 **2MB 环形缓冲区**（`BUFFER_LIMIT = 1024 * 1024 * 2`，pty.ts:11）
   - 按 **64KB 分块**发送（`BUFFER_CHUNK = 64 * 1024`，pty.ts:12），避免单帧过大
   - `cursor` 语义：缺省/`0` → 回放全部历史；正整数 → 从该字节偏移开始（`Math.max(0, cursor - start)`）；`-1` → 跳过历史（`from = end`）

2. **控制帧（meta frame）**（pty.ts:36-43）：
   - 格式：`[0x00, ...UTF-8 JSON bytes]`
   - 内容：`{ "cursor": <当前总字节数> }`
   - 用途：告知客户端当前游标位置，用于断线重连时传入新的 `cursor`。**客户端据此判断历史回放结束**，后续都是实时 PTY 输出

3. **双向数据流**：
   - 服务端 → 客户端：PTY 子进程 stdout/stderr 原始字节流（字符串 chunk）
   - 客户端 → 服务端：键盘输入，支持 **string 或 ArrayBuffer/Uint8Array**（`input.ts:5-23`）。string 直接转发 `process.write(message)`；binary 经 UTF-8 解码后转发，解码失败静默丢弃（非致命）

**连接生命周期事件**（`handlers/pty.ts:171-256`）：

| 事件 | 行为 |
|------|------|
| PTY 不存在 | 返回 `404`（升级前检查，pty.ts:177-181） |
| cursor query 非法 | 返回 `400`（pty.ts:183-184） |
| 票据无效/Origin 不匹配 | 返回 `403`（pty.ts:186-190） |
| 服务端正在关闭 | 发送 `CloseEvent(1001, "server closing")` 后断开（websocket-tracker.ts:4） |
| PTY 会话在连接后消失 | 发送 `CloseEvent(4404, "session not found")`（pty.ts:234-236） |
| 客户端断开 | `closed = true`，调用 `handler.onClose()` 清理订阅 |
| 进程退出 | 自动触发 `Event.Exited`，清理会话，关闭所有订阅者 WebSocket |

> **错误响应顺序是刻意的**（pty.ts:142-143 注释）：存在性检查（404）→ cursor 合法性（400）→ 票据校验（403），为保持既有的 empty-404 响应顺序。
>
> **服务端优雅关闭**（`websocket-tracker.ts`）：`WebSocketTracker` 维护所有活跃连接的 `Set`，`closeAll()` 在关闭时标记 `closing = true`，新连接注册返回 `false` → 立即关闭；关闭时并发向所有连接发送 `1001`，每连接超时 1 秒。
>
> **技术细节**：此端点用 `handleRaw` 绕过 Effect HttpApi 的编解码，手动处理 WebSocket 升级；用 `EffectBridge` 管理异步写入，避免 socket 写竞态。

### GET `/pty/shells`

列出系统上可用的 shell，供用户选择创建 PTY 时使用。

**请求**: Query [`WorkspaceRoutingQuery`](#workspaceroutingquery)（`directory?`, `workspace?`）

**响应** `200`: `List<`[`ShellInfo`](#shellinfo)`>`（即 `ShellItem[]`）

**字段说明**（groups/pty.ts:23-27）：

| 字段 | 类型 | 说明 |
|------|------|------|
| `path` | `string` | shell 完整路径（Windows 可能是 git-bash 路径） |
| `name` | `string` | shell 基名小写（如 `bash`, `zsh`, `pwsh`） |
| `acceptable` | `boolean` | 是否可接受。`fish`/`nu` 被标记为 `deny: true`（shell.ts:10-20） |

**边界情况**：Windows 检测 `pwsh` → `powershell` → `git-bash` → `cmd.exe`；Unix 读 `/etc/shells`，失败回退 `["/bin/bash", "/bin/zsh", "/bin/sh"]`。每个候选经 `resolve()` 验证文件存在。

**数据来源/原理**（`shell/shell.ts:210-213`）：`Shell.preferred()` 按平台枚举候选并验证。

**建议用法**：创建 PTY 前调用此端点填充 shell 选择器；`acceptable: false` 的项应置灰或过滤。

### GET `/pty`

列出当前实例管理的所有活跃 PTY 会话。

**请求**: Query [`WorkspaceRoutingQuery`](#workspaceroutingquery)

**响应** `200`: `List<`[`PtyInfo`](#ptyinfo)`>`

**数据来源/原理**（`core/pty.ts`）：`Pty.Service.list()` 遍历内存中 `sessions: Map<PtyID, Active>` 返回所有 `info`。**仅含未退出的会话**，进程退出后自动移除。

**边界情况**：无活跃会话时返回空数组；跨工作区的会话通过 `directory`/`workspace` 路由隔离。

**建议用法**：用于填充 PTY 会话列表 UI；建立 WebSocket 连接前可先调用此端点确认目标会话存在并获取最新 `id`。

### POST `/pty`

创建新的伪终端会话，启动 shell 进程。

**请求**: Query [`WorkspaceRoutingQuery`](#workspaceroutingquery)；Body [`PtyCreateRequest`](#ptycreaterequest)（即 `Pty.CreateInput`，所有字段可选）

**响应** `200`: [`PtyInfo`](#ptyinfo)（创建后的会话信息，含生成的 `id` 和 `pid`）

**边界情况**：`400 BadRequest`（参数校验失败）；Windows ConPTY 异步分配 PID，`pid` 可能为 `0`。

**创建流程**（`handlers/pty.ts:62-75` + `pty-preparation.ts`）：
1. `PtyPreparation.prepareCreate()` 准备参数：
   - `command`：取 `input.command` 或配置 shell 或系统默认
   - `args`：若 shell 支持 login（`META[shell].login === true`），自动追加 `-l`
   - `cwd`：取 `input.cwd` 或实例目录
   - 触发插件钩子 `"shell.env"` 获取额外环境变量
   - 合并 env：`process.env` + `input.env` + 插件 env + `TERM=xterm-256color` + `OPENCODE_TERMINAL=1`；Windows 额外设置 `LC_ALL`/`LC_CTYPE`/`LANG = "C.UTF-8"`
2. `Pty.Service.create()`：生成 `PtyID.ascending()`（`pty_` 前缀升序 ULID）→ 底层 `spawn()`（Bun 用 `bun-pty`，Node 用 `@lydell/node-pty`）→ 注册 `onData`（广播 + 追加缓冲区）→ 注册 `onExit`（标记 exited → 发布 `Event.Exited` → 自动移除）→ 发布 `Event.Created`

**建议用法**：默认空 body 创建；移动端建议立即传 `size` 同步终端尺寸避免首帧错位。

### GET `/pty/{ptyId}`

查询单个 PTY 会话的当前状态。

**请求**: Path `ptyId`；Query [`WorkspaceRoutingQuery`](#workspaceroutingquery)

**响应** `200`: [`PtyInfo`](#ptyinfo)

**边界情况**：`404 PtyNotFoundError`（`{ ptyID, message }`）— 会话不存在或已退出移除。

**建议用法**：用于在建立 WebSocket 连接前校验会话存活，或获取 `status`/`pid` 刷新 UI 状态（如检测进程退出）。

### PUT `/pty/{ptyId}`

更新标题或调整终端尺寸。

**请求**: Path `ptyId`；Query [`WorkspaceRoutingQuery`](#workspaceroutingquery)；Body [`PtyUpdateRequest`](#ptyupdaterequest)（即 `Pty.UpdateInput`）

**响应** `200`: [`PtyInfo`](#ptyinfo)（更新后的信息）

**边界情况**：`404 PtyNotFoundError` / `400 BadRequest`。

**数据来源/原理**（`core/pty.ts:244-250`）：`title` 非空 → 更新 `info.title`；`size` 提供 → 调用 `process.resize(cols, rows)`；发布 `Event.Updated`。

**建议用法**：客户端窗口/方向变化时调用此端点同步 `size`，避免终端渲染错位。

### DELETE `/pty/{ptyId}`

终止并移除 PTY 会话。

**请求**: Path `ptyId`；Query [`WorkspaceRoutingQuery`](#workspaceroutingquery)

**响应** `200`: `boolean`（恒为 `true`）

**边界情况**：`404 PtyNotFoundError`。

**数据来源/原理**（`core/pty.ts:155-168`）：`teardown()` → dispose 所有监听器 → `process.kill()` → 关闭所有 WebSocket 订阅者 → 发布 `Event.Deleted`。

**建议用法**：用户关闭终端标签时调用；服务端会自动关闭该会话的所有 WebSocket 订阅者，客户端无需逐个断开连接。

### POST `/pty/{ptyId}/connect-token`

为后续 WebSocket 连接申请一次性票据（**三步流程的第 1 步**）。

**请求**: Path `ptyId`；Query [`WorkspaceRoutingQuery`](#workspaceroutingquery)

**Headers**（**必需**）：`x-opencode-ticket: 1`（`PTY_CONNECT_TOKEN_HEADER`）+ Basic Auth + 合法 CORS Origin

**响应** `200`:
```json
{ "ticket": "uuid-v4", "expires_in": 60 }
```

**`ConnectToken` 字段**（`core/pty/ticket.ts:11-14`）：

| 字段 | 类型 | 说明 |
|------|------|------|
| `ticket` | `string` | UUID v4 票据 |
| `expires_in` | `PositiveInt` | 过期秒数（默认 60），由 TTL 计算得出：`Math.max(1, round(TTL秒))` |

**边界情况**：`403 PtyForbiddenError`（Origin 不匹配或缺 `x-opencode-ticket` 头）；`404 PtyNotFoundError`。

**建议用法**：
```typescript
// 1. 获取票据（必须带 x-opencode-ticket 头）
const { ticket } = await fetch(`/pty/${ptyID}/connect-token`, {
  method: "POST",
  headers: { "x-opencode-ticket": "1", "Authorization": "Basic ..." }
}).then(r => r.json())

// 2. 建立 WebSocket（无需 Auth 头，60s 内有效、一次性）
const ws = new WebSocket(`/pty/${ptyID}/connect?ticket=${ticket}&cursor=-1`)
```

### GET `/pty/{ptyId}/connect`（WebSocket）

建立 WebSocket 连接，实时交互 PTY 输入输出（**三步流程的第 2 步起**）。

**URL**: `ws(s)://host:port/pty/{ptyId}/connect?ticket={uuid}&cursor={int}`

| Query 参数 | 类型 | 默认 | 说明 |
|-----------|------|------|------|
| `ticket` | string | — | connect-token 获取的票据。提供时跳过 Basic Auth（Origin 仍需校验） |
| `cursor` | int | — | 历史回放起始字节偏移。`-1` = 跳过历史；缺省/`0` = 回放全部；非整数或 `< -1` → `400` |
| `directory` | string | — | 工作区路由 |
| `workspace` | string | — | 工作区 ID |

**认证**：特殊 `PtyConnectAuthorization` 中间件 — 有 ticket 跳过 Basic Auth，无 ticket 则需 Basic Auth。

**返回**：HTTP 101（WebSocket 升级），无 JSON body。

**连接后数据流**（详见 [PTY WebSocket 协议详解](#pty-websocket-协议详解)）：历史缓冲回放（≤64KB/帧）→ 控制帧 `[0x00 + JSON{cursor}]` → 双向 PTY 数据流。

**关闭事件**：

| 事件 | 行为 |
|------|------|
| PTY 不存在 | `404`（升级前检查） |
| cursor 非法 | `400` |
| 票据无效/Origin 不匹配 | `403` |
| 服务端正在关闭 | `CloseEvent(1001, "server closing")` |
| PTY 会话连接后消失 | `CloseEvent(4404, "session not found")` |

**客户端实现要点**：
1. 收到的第一类数据是**历史回放**（0 或多个 binary/text 帧）
2. 收到的 `[0x00 + JSON]` 帧是**控制帧**，解析 `cursor` 值并保存
3. 之后的所有数据都是**实时 PTY 输出**
4. 发送数据时用 `string` 或 `ArrayBuffer`（UTF-8 编码）；binary 解码失败静默丢弃
5. 断线重连时用上次保存的 `cursor` 值作为新连接的 `cursor` 参数，避免重复回放

**建议用法**：首次连接传 `cursor=-1` 跳过历史（移动端省流量）；重连时传上次控制帧的 `cursor` 做增量恢复。

---

## 14. TUI 端点

> 路由：`groups/tui.ts` · Handler：`handlers/tui.ts`
> 核心：`server/tui-event.ts`（事件定义）+ `server/shared/tui-control.ts`（请求-响应队列）
> **中间件栈**：Instance → Workspace → Authorization
> **端点数**：13（通道 A 事件推送 10 个 + 通道 B 请求-响应队列 2 个 + 1 个 select-session 归入通道 A）

### 核心机制：双通道控制架构

OpenCode 的 TUI（Terminal User Interface）控制有**两条独立通道**，分别适配 fire-and-forget 推送与同步请求-响应两种交互模式：

```
┌─────────────────────────────┐         ┌──────────────────┐
│  外部进程（如 oc-remote App）│         │   OpenCode TUI   │
└──────────────┬──────────────┘         └────────┬─────────┘
               │                                 │
    ┌──────────┴──────────┐          ┌───────────┴────────────┐
    │ 通道 A：事件推送     │          │ 通道 B：请求-响应队列   │
    │ （单向，fire-and-    │          │ （双向，同步等待）      │
    │  forget）            │          │                        │
    │                      │  EventV2 │                        │
    │ POST /tui/append-    │──Bus──→  │ GET  /tui/control/next │
    │   prompt             │          │   ← TUI 消费请求        │
    │ POST /tui/open-help  │          │                        │
    │ POST /tui/show-toast │          │ POST /tui/control/     │
    │ POST /tui/publish    │          │   response             │
    │ POST /tui/select-    │          │   → 外部提交响应        │
    │   session            │          │                        │
    │ ... (10 个端点)      │          │ AsyncQueue<TuiRequest> │
    └─────────────────────┘          └────────────────────────┘
```

**通道 A：事件推送**（`handlers/tui.ts:27-105`）— 通过 `EventV2Bridge.Service.publish()` 发布事件到内部事件总线，TUI 订阅执行对应 UI 操作。**fire-and-forget**：不等待 TUI 响应，立即返回 `true`。

**通道 B：请求-响应队列**（`server/shared/tui-control.ts`）— 两个全局 `AsyncQueue`：`request`（外部→TUI）和 `response`（TUI→外部）。`GET /tui/control/next` 让 TUI 端长轮询阻塞等待请求；`POST /tui/control/response` 让 TUI 提交处理结果。用途：让 TUI 作为"执行器"，处理外部进程通过 HTTP 发来的命令并返回结果。

> ⚠️ **通道 B 并发限制**：`request` 和 `response` 是两个独立的全局单例 AsyncQueue（tui-control.ts:11-12），**无请求 ID 关联**。多个并发请求-响应对可能交叉错配，仅适合单请求-单响应的串行场景。

### TuiEvent 事件定义

所有事件通过 `EventV2.define()` 定义（`server/tui-event.ts`）：

| 事件类型 | type 字符串 | data schema |
|----------|-------------|-------------|
| `PromptAppend` | `tui.prompt.append` | `{ text: string }` |
| `CommandExecute` | `tui.command.execute` | `{ command: string \| known-literal }` |
| `ToastShow` | `tui.toast.show` | `{ title?: string, message: string, variant: "info"\|"success"\|"warning"\|"error", duration: PositiveInt }` |
| `SessionSelect` | `tui.session.select` | `{ sessionID: SessionID }` |

**`CommandExecute` 的已知命令字面量**（tui-event.ts:13-31）：
```
session.list, session.new, session.share, session.interrupt, session.compact,
session.page.up, session.page.down, session.line.up, session.line.down,
session.half.page.up, session.half.page.down, session.first, session.last,
prompt.clear, prompt.submit, agent.cycle
```

### 通道 A：事件推送端点（10 个）

所有 POST 端点返回 `boolean`（恒 `true`），通过 `EventV2Bridge.Service.publish()` 发布事件到内部总线，TUI 订阅执行。

#### POST `/tui/append-prompt`

追加提示文本到 TUI 输入框（**追加非替换**，不自动提交）。

**请求体**: `{ "text": "string" }`

**响应** `200`: `boolean`

**边界情况**：`400 BadRequest`（缺 `text`）。

**数据来源/原理**（`handlers/tui.ts`）：发布 `TuiEvent.PromptAppend`，TUI 收到后将 `text` 追加到输入框。

#### POST `/tui/open-help`

打开帮助对话框。

**响应** `200`: `boolean` · **请求体**: 无

**数据来源/原理**：发布 `CommandExecute { command: "help.show" }`。

#### POST `/tui/open-sessions`

打开会话列表对话框。

**响应** `200`: `boolean` · **请求体**: 无

**数据来源/原理**：发布 `CommandExecute { command: "session.list" }`。

#### POST `/tui/open-themes`

打开主题对话框。

**响应** `200`: `boolean` · **请求体**: 无

> ⚠️ **源码 bug**（`handlers/tui.ts:51-54`）：发布的是 `session.list`（与 `open-sessions` 相同），而非 `theme.list`。主题对话框和会话对话框会触发相同行为，疑似源码笔误。

#### POST `/tui/open-models`

打开模型选择对话框。

**响应** `200`: `boolean` · **请求体**: 无

**数据来源/原理**：发布 `CommandExecute { command: "model.list" }`。

#### POST `/tui/submit-prompt`

提交当前输入框内容。

**响应** `200`: `boolean` · **请求体**: 无

**数据来源/原理**：发布 `CommandExecute { command: "prompt.submit" }`。

#### POST `/tui/clear-prompt`

清空提示输入。

**响应** `200`: `boolean` · **请求体**: 无

**数据来源/原理**：发布 `CommandExecute { command: "prompt.clear" }`。

#### POST `/tui/execute-command`

执行 TUI 命令（**遗留别名映射**）。

**请求体**: `{ "command": "string" }`

**响应** `200`: `boolean`

**边界情况**：`400 BadRequest`；**未知命令映射为 `undefined` 静默失败**（不报错）。

**别名映射表**（`handlers/tui.ts:11-25`）：

| 输入（旧名） | 映射到（新名） |
|-------------|---------------|
| `session_new` | `session.new` |
| `session_share` | `session.share` |
| `session_interrupt` | `session.interrupt` |
| `session_compact` | `session.compact` |
| `messages_page_up` | `session.page.up` |
| `messages_page_down` | `session.page.down` |
| `messages_line_up` | `session.line.up` |
| `messages_line_down` | `session.line.down` |
| `messages_half_page_up` | `session.half.page.up` |
| `messages_half_page_down` | `session.half.page.down` |
| `messages_first` | `session.first` |
| `messages_last` | `session.last` |
| `agent_cycle` | `agent.cycle` |

**建议用法**：新代码应直接使用 `/tui/publish` 端点发送 `CommandExecute` 事件，避免别名层的限制。

#### POST `/tui/show-toast`

显示 Toast 通知。

**请求体**（`ToastShow.data`）:
```json
{
  "title": "string?",                             // 可选标题
  "message": "string",                            // 必填正文
  "variant": "info | success | warning | error",  // 样式变体
  "duration": 5000                                // PositiveInt，默认 5000ms
}
```

**响应** `200`: `boolean`

**字段说明**：

| 字段 | 类型 | 默认 | 说明 |
|------|------|------|------|
| `title` | `string?` | — | 标题（可选） |
| `message` | `string` | 必填 | 正文 |
| `variant` | `"info"\|"success"\|"warning"\|"error"` | 必填 | 样式变体 |
| `duration` | `PositiveInt` | **5000** | 显示毫秒数 |

#### POST `/tui/publish`

**最通用的 TUI 控制端点**，一个请求可触发任何类型的 TUI 事件。

**请求体**（`TuiPublishPayload`，4 种事件判别联合，按 `type` 区分，`groups/tui.ts:29-34`）:
```jsonc
// 类型 1：追加提示
{ "type": "tui.prompt.append", "properties": { "text": "..." } }
// 类型 2：执行命令（支持任意 string 命令，不受别名限制）
{ "type": "tui.command.execute", "properties": { "command": "session.new" } }
// 类型 3：显示 Toast
{ "type": "tui.toast.show", "properties": { "message": "...", "variant": "info" } }
// 类型 4：选择会话
{ "type": "tui.session.select", "properties": { "sessionID": "ses_..." } }
```

**响应** `200`: `boolean`

**边界情况**：`400 BadRequest`（`type` 或 `properties` 不匹配联合类型）。

**建议用法**：优先用此端点替代多个专用端点，减少请求往返；尤其推荐用于发送自定义命令（避开 `execute-command` 的别名限制）。

#### POST `/tui/select-session`

导航到指定会话。

**请求体**: `{ "sessionID": "ses_..." }`

**响应** `200`: `boolean`

**边界情况**：`400 BadRequest`（sessionID 不以 `"ses"` 开头）/ `404 NotFound`（会话不存在）。

**校验逻辑**（`handlers/tui.ts:98-105`）：`sessionID` 必须以 `"ses"` 开头（SessionID 前缀）→ 通过 `Session.Service.get()` 验证会话存在 → 发布 `TuiEvent.SessionSelect`，TUI 导航到该会话。

### 通道 B：请求-响应队列端点（2 个）

> 两个**全局单例** AsyncQueue（`request` 和 `response`），**无请求 ID 关联**，并发场景可能错配，适合串行场景。

#### GET `/tui/control/next`

TUI 端长轮询，阻塞等待外部进程通过队列发来的请求。

**响应** `200`: `TuiRequest`（阻塞直到有请求）

```json
{ "path": "/sync/replay", "body": {} }
```

**`TuiRequest` 字段**（`tui-control.ts:4-7`）：

| 字段 | 类型 | 说明 |
|------|------|------|
| `path` | `string` | 请求路径（如 `/sync/replay`） |
| `body` | `unknown` | 请求体（任意 JSON） |

**数据来源/原理**：调用 `nextTuiRequest()` → `request.next()`（AsyncQueue 出队）。

**建议用法**：TUI 进程在事件循环中反复调用此端点，获取外部通过通道 B 发来的操作请求，本地执行后通过 `/tui/control/response` 返回结果。

#### POST `/tui/control/response`

TUI 处理完请求后提交响应。

**请求体**: 任意 JSON（`Schema.Unknown`）

**响应** `200`: `boolean`（恒 `true`）

**数据来源/原理**：调用 `submitTuiResponse(payload)` → `response.push(payload)`（入队）。

**配对用法**：外部进程发请求 → TUI 通过 `next` 取出 → 本地处理 → 通过 `response` 提交结果 → 外部进程从 `response` 队列取出结果。

---

## 15. 控制平面基础端点

> 路由：`groups/control.ts` · Handler：`handlers/control.ts`
> **重要**：这组端点注册在 `RootHttpApi`（非实例级 `InstanceHttpApi`），**不经过** Instance/Workspace/Authorization 中间件。
> **端点数**：3

### PUT `/auth/{providerID}`

设置认证凭据。

**请求**: Path `providerID`（`ProviderV2.ID`）

**请求体**: `AuthInfo`（`Auth.Info`，OAuth/API Key/WellKnown 三种认证方式联合类型，`auth/index.ts:34`）
```jsonc
// OAuth
{ "type": "oauth", "client_id": "...", "client_secret": "...", "authorization_url": "...", "token_url": "..." }
// API Key
{ "type": "api_key", "api_key": "...", "headers": {} }
// WellKnown (OpenID Connect)
{ "type": "well_known", "url": "..." }
```

**字段说明**：

| 变体 | `type` | 关键字段 |
|------|--------|----------|
| OAuth | `"oauth"` | `client_id`, `client_secret?`, `authorization_url`, `token_url`, ... |
| API Key | `"api_key"` | `api_key`, `headers?` |
| WellKnown | `"well_known"` | `url`（OpenID Connect well-known 配置端点） |

**响应** `200`: `boolean`

**边界情况**：`400 BadRequest`（payload 不匹配联合类型）。

**数据来源/原理**（`handlers/control.ts`）：`Auth.Service.set(providerID, credentials)` 持久化到本地认证存储。

### DELETE `/auth/{providerID}`

移除认证凭据。

**请求**: Path `providerID`（`ProviderV2.ID`）

**响应** `200`: `boolean`

**边界情况**：`400 BadRequest`。

**数据来源/原理**：`Auth.Service.remove(providerID)` 从本地存储删除。

### POST `/log`

写入服务端日志。让客户端（如 SDK、IDE 插件）把自身日志写入 OpenCode 服务端统一日志流，便于调试。

**请求**: Query `LogQuery { directory?: string, workspace?: string }`

**请求体**（`LogInput`，`groups/control.ts:17-29`）:
```json
{
  "service": "string",                          // 服务名（标注用）
  "level": "debug | info | warn | error",
  "message": "string",
  "extra": { "key": "value" }                   // 额外元数据（作为 Effect 日志标注）
}
```

**字段说明**：

| 字段 | 类型 | 说明 |
|------|------|------|
| `service` | `string` | 服务名称（标注用） |
| `level` | `"debug"\|"info"\|"warn"\|"error"` | 日志级别 |
| `message` | `string` | 日志消息 |
| `extra` | `Record<string, unknown>?` | 额外元数据（作为 Effect 日志标注） |

**响应** `200`: `boolean`

**数据来源/原理**（`handlers/control.ts:28-39`）：映射到 Effect 的日志函数（`Effect.logDebug/logInfo/logWarning/logError`），`extra` 通过 `Effect.annotateLogs()` 附加为结构化标注。

**建议用法**：客户端发生异常时，将自身日志通过此端点汇入服务端日志流，与服务端日志一并排查。

---

## 16. Sync 端点

> 路由：`groups/sync.ts` · Handler：`handlers/sync.ts`
> 核心：`EventV2` 事件系统 + `EventTable`（SQLite 持久化）
> **中间件栈**：Instance → Workspace → Authorization
> **端点数**：4

### 核心机制：EventV2 事件溯源同步

OpenCode 的多工作区协作基于**事件溯源（Event Sourcing）**模式：

```
工作区 A（本地）                     工作区 B（远程）
    │                                    │
    │  用户操作产生 EventV2 事件          │
    │  → 写入本地 SQLite EventTable       │
    │  → 通过 sync 连接转发给 B           │
    │                                    │
    │                    ┌───────────────┤
    │                    │ /sync/replay   │ ← B 接收事件序列
    │                    │ 验证+回放到本地 │
    │                    │ EventTable     │
    │                    └───────────────┤
    │                                    │
    │  /sync/history ← B 查询增量事件     │
    │  /sync/start  ← B 启动持续同步      │
    │  /sync/steal  ← B 将会话纳入本工作区 │
```

**EventV2 结构**（`core/event`）：每个事件有 `id`（唯一）、`aggregateID`（聚合根，如 session ID）、`seq`（单调递增序列号）、`type`（事件类型字符串）、`data`（事件负载）。

**同步语义**：
- **增量同步**：客户端记录每个 aggregate 的最后已知 `seq`，只请求 `seq > 已知值` 的事件
- **全量回放**：`/sync/replay` 接收完整事件序列，验证后应用到本地
- **所有权校验**：replay 时设置 `strictOwner: true`，确保事件归属正确的工作区

### POST `/sync/start`

为当前项目中所有有活跃会话的工作区启动同步循环。

**请求**: Query [`WorkspaceRoutingQuery`](#workspaceroutingquery)

**响应** `200`: `boolean`（恒 `true`）

**数据来源/原理**（`handlers/sync.ts:27-32`）：`Workspace.Service.startWorkspaceSyncing(projectID)` — fork 一个后台 Effect 持续运行同步循环。使用 `Effect.ignore` + `Effect.forkIn(scope)` 在请求作用域内 fork 后台任务，即使同步循环失败也不影响 HTTP 响应。**同步循环的生命周期绑定到实例 scope**。

**边界情况**：无活跃会话时静默返回 `true`（无可同步内容）。

**建议用法**：客户端连接工作区后调用一次启动后台同步循环，随后配合 `/sync/history` 做增量事件拉取。

### POST `/sync/replay`

验证并回放完整的事件历史到本地 EventTable。

**请求**: Query [`WorkspaceRoutingQuery`](#workspaceroutingquery)

**请求体**（`ReplayPayload`，`groups/sync.ts:19-22`）:
```json
{
  "directory": "/source/workspace",
  "events": [
    {
      "id": "evt_...",
      "aggregateID": "ses_...",
      "seq": 0,
      "type": "session.updated",
      "data": {}
    }
  ]
}
```

**`ReplayPayload` 字段**：

| 字段 | 类型 | 说明 |
|------|------|------|
| `directory` | `string` | 源工作区目录 |
| `events` | `NonEmptyArray<ReplayEvent>` | 至少 1 个事件，按 seq 升序 |

**`ReplayEvent` 字段**（注意 `aggregateID` 为**驼峰**）：

| 字段 | 类型 | 说明 |
|------|------|------|
| `id` | `EventV2.ID` | 事件唯一 ID |
| `aggregateID` | `string` | 聚合根 ID（驼峰命名） |
| `seq` | `NonNegativeInt` | 序列号 |
| `type` | `string` | 事件类型 |
| `data` | `Record<string, unknown>` | 事件负载 |

**响应** `200`: `{ "sessionID": "string" }`（以第一个事件的 `aggregateID` 作为会话标识）

**边界情况**：`400 BadRequest`（`events` 为空或非升序）。

**处理流程**（`handlers/sync.ts:34-59`）：
1. 提取 `source = events[0].aggregateID`（以第一个事件的 aggregateID 作为会话标识）
2. 获取当前工作区的 `ownerID`
3. 调用 `events.replayAll(payload, { ownerID, strictOwner: true })` — 逐个验证并写入
4. 返回 `{ sessionID: source }`

> 请求开始和完成时各记录一条 `logInfo`，包含事件数、首尾 seq、目录。

### POST `/sync/steal`

将会话"窃取"到当前工作区（通过事件系统更新会话归属）。

**请求**: Query [`WorkspaceRoutingQuery`](#workspaceroutingquery)；Body `{ "sessionID": "ses_..." }`（`SessionPayload`）

**响应** `200`: `{ "sessionID": "ses_..." }`（原样返回）

**边界情况**：`400 BadRequest`（无 workspaceID 时）。

**数据来源/原理**（`handlers/sync.ts:61-70`）：获取当前工作区的 `workspaceID` → `Session.Service.setWorkspace({ sessionID, workspaceID })` 更新会话的工作区归属。这会通过事件系统传播，让其他工作区感知到归属变更。

**"窃取"语义**：当一个会话需要在多个工作区间转移时，目标工作区调用此端点声明所有权。配合 `/sync/replay` 可以迁移完整会话历史。

### POST `/sync/history`

增量查询事件历史，获取客户端未知的新事件。

**请求**: Query [`WorkspaceRoutingQuery`](#workspaceroutingquery)

**请求体**（`HistoryPayload`，`groups/sync.ts:29`）: `Record<aggregateID, lastKnownSeq>`
- **key**：aggregateID（客户端已知）
- **value**：该 aggregate 客户端最后已知的 seq（`NonNegativeInt`）

**响应** `200`: `List<HistoryEvent>`（按 seq 升序）

```json
{
  "id": "evt_...",
  "aggregate_id": "ses_...",    // ⚠️ 蛇形命名（与 ReplayEvent 的 aggregateID 不同！）
  "seq": 0,
  "type": "session.updated",
  "data": {}
}
```

**`HistoryEvent` 字段**（注意 `aggregate_id` 为**蛇形**）：

| 字段 | 类型 | 说明 |
|------|------|------|
| `id` | `EventV2.ID` | 事件 ID |
| `aggregate_id` | `string` | 聚合根 ID（蛇形命名，映射自 SQLite `EventTable` 列名） |
| `seq` | `NonNegativeInt` | 序列号 |
| `type` | `string` | 事件类型 |
| `data` | `Record<string, unknown>` | 负载 |

**边界情况**：`400 BadRequest`。

**查询逻辑**（`handlers/sync.ts:72-85`）：排除所有 `(aggregate_id = key AND seq <= value)` 的事件，返回所有其他事件（包括未在 payload 中列出的 aggregate 的全部历史）。SQL 等价：`WHERE NOT (aggregate_id IN (...) AND seq <= ...)`，`ORDER BY seq ASC`。

> ⚠️ **命名不一致**：`ReplayEvent.aggregateID`（驼峰，API 输入）vs `HistoryEvent.aggregate_id`（蛇形，SQLite 列映射）。客户端需做字段名映射。

**建议用法**（增量同步循环）：
```typescript
// 1. 维护本地已知状态：{ [aggregateID]: lastSeq }
// 2. 轮询获取增量（注意用蛇形 aggregate_id 读结果）
const newEvents = await POST("/sync/history", localKnownState)
// 3. 应用 newEvents 到本地，更新 localKnownState
for (const ev of newEvents) {
  applyEvent(ev)
  localKnownState[ev.aggregate_id] = Math.max(localKnownState[ev.aggregate_id] ?? 0, ev.seq)
}
```

---

## 17. Experimental 端点（实验性）

> 路由：`groups/experimental.ts` · Handler：`handlers/experimental.ts` · 路径前缀：`/experimental`
> **中间件栈**：Instance → Workspace → Authorization
> **端点数**：12

> ⚠️ **实验性警告**：本组端点均为实验性质，API 契约可能在未来版本变更或移除，生产环境慎用。部分端点受运行时标志门控（见各端点说明）。

### GET `/experimental/console`（实验性）

获取当前活跃的 Console 组织名及其管理的 provider 集合。

**请求**: Query [`WorkspaceRoutingQuery`](#workspaceroutingquery)

**响应** `200`（`ConsoleStateResponse`，`groups/experimental.ts:22-26`）:
```json
{
  "consoleManagedProviders": ["providerId"],
  "activeOrgName": "string?",
  "switchableOrgCount": 0
}
```

**字段说明**：

| 字段 | 类型 | 说明 |
|------|------|------|
| `consoleManagedProviders` | `string[]` | 当前 Console 组织管理的 provider ID 列表 |
| `activeOrgName` | `string?` | 活跃组织名（可选，无活跃组织时不出现） |
| `switchableOrgCount` | `NonNegativeInt` | 可切换的组织总数（所有 account 的 org 数之和） |

**边界情况**：`500 InternalServerError`（获取组织列表失败时）。

**建议用法**：配合 `/experimental/console/orgs` 列出可切换组织，再通过 `/experimental/console/switch` 切换活跃组织；`consoleManagedProviders` 用于判断当前组织是否管理目标 provider。

### GET `/experimental/console/orgs`（实验性）

列出可切换的 Console 组织。

**响应** `200`: `{ "orgs": List<ConsoleOrgOption> }`

**`ConsoleOrgOption` 字段**（`groups/experimental.ts:28-35`）：

| 字段 | 类型 | 说明 |
|------|------|------|
| `accountID` | `string` | 账户 ID |
| `accountEmail` | `string` | 账户邮箱 |
| `accountUrl` | `string` | 账户 URL |
| `orgID` | `string` | 组织 ID |
| `orgName` | `string` | 组织名 |
| `active` | `boolean` | 是否为当前活跃组织 |

**边界情况**：`500 InternalServerError`。

**建议用法**：先调用 `/experimental/console` 获取 `switchableOrgCount` 判断是否有可切换组织，再调用此端点获取完整列表；`active: true` 的项用于在 UI 中标记当前活跃组织。

### POST `/experimental/console/switch`（实验性）

切换活跃 Console 组织。

**请求体**（`ConsoleSwitchPayload`）: `{ "accountID": "string", "orgID": "string" }`

**响应** `200`: `boolean`

**边界情况**：`400 BadRequest`（切换失败时）。

**数据来源/原理**（`handlers/experimental.ts`）：`Account.Service.use(accountID, Some(orgID))` 持久化新的活跃选择。

### GET `/experimental/tool`（实验性）

列出工具（含参数 schema）。

**请求**: Query `ToolListQuery`（`groups/experimental.ts:53-57`）

| Query 参数 | 类型 | 说明 |
|-----------|------|------|
| `provider` | `ProviderV2.ID` | **必填** provider ID |
| `model` | `ModelV2.ID` | **必填** model ID |
| `directory?`, `workspace?` | — | 工作区路由 |

**响应** `200`: `List<ToolListItem>`（`groups/experimental.ts:47-51`）

| 字段 | 类型 | 说明 |
|------|------|------|
| `id` | `string` | 工具 ID |
| `description` | `string` | 工具描述 |
| `parameters` | `unknown` | JSON Schema 格式的参数定义（`ToolJsonSchema.fromTool()`） |

**边界情况**：`400 BadRequest`（缺必填 query）。

**数据来源/原理**：根据 provider + model 组合获取可用工具列表（不同模型可能暴露不同工具集）。

**建议用法**：模型切换时调用此端点获取该模型可用工具集，用于工具调用 UI 渲染；`parameters` 可直接喂给 JSON Schema 表单渲染器生成参数输入界面。

### GET `/experimental/tool/ids`（实验性）

列出所有已注册工具 ID（内置 + 动态注册）。

**请求**: Query [`WorkspaceRoutingQuery`](#workspaceroutingquery)

**响应** `200`: `string[]`

**数据来源/原理**（`handlers/experimental.ts`）：`ToolRegistry.Service.ids()` 返回所有已注册工具的 ID。

### GET `/experimental/worktree`（实验性）

列出当前项目的所有工作树目录。

**请求**: Query [`WorkspaceRoutingQuery`](#workspaceroutingquery)

**响应** `200`: `string[]`（工作树目录绝对路径列表）

**边界情况**：非 git 仓库 → `WorktreeNotGitError`（HTTP 400）；列表获取失败 → `WorktreeListFailedError`。

**建议用法**：用于在 UI 中展示当前项目的所有沙箱工作树，配合 `POST /experimental/worktree` 创建新工作树。

### POST `/experimental/worktree`（实验性）

创建 git worktree 并运行启动脚本（**允许空 body**）。

**请求体**（`Worktree.CreateInput`，`worktree/index.ts:44-49`，可空）:

| 字段 | 类型 | 说明 |
|------|------|------|
| `name` | `string?` | 工作树名称（缺省自动生成） |
| `startCommand` | `string?` | 创建后执行的启动命令 |

**响应** `200`: [`WorktreeInfo`](#worktreeinfo)

**边界情况**：非 git 仓库 → `WorktreeNotGitError`；名称生成失败 → `WorktreeNameGenerationFailedError`；worktree 创建失败 → `WorktreeCreateFailedError`；启动命令执行失败 → `WorktreeStartCommandFailedError`（均 HTTP 400，属 `WorktreeApiError`）。

**特殊设计**（`groups/experimental.ts:172-184`）：`disableCodecs: true` + payload 联合 `[NoContent, Worktree.CreateInput]`，允许客户端发空 body 创建默认工作树。

### DELETE `/experimental/worktree`（实验性）

删除 git worktree 及其分支。

**请求体**（`Worktree.RemoveInput`）: `{ "directory": "string" }`（待删除的工作树目录绝对路径）

**响应** `200`: `boolean`

**边界情况**：删除失败 → `WorktreeRemoveFailedError`（HTTP 400，属 `WorktreeApiError`）。

**附加行为**（`handlers/experimental.ts:118-125`）：删除后还调用 `project.removeSandbox(projectID, directory)` 清理项目元数据。

### POST `/experimental/worktree/reset`（实验性）

重置 worktree 分支到主分支。

**请求体**（`Worktree.ResetInput`）: `{ "directory": "string" }`

**响应** `200`: `boolean`

**Worktree 错误**（`WorktreeApiError`，`groups/experimental.ts:69-75`，HTTP 400）: `WorktreeNotGitError` / `WorktreeNameGenerationFailedError` / `WorktreeCreateFailedError` / `WorktreeStartCommandFailedError` / `WorktreeRemoveFailedError` / `WorktreeResetFailedError` / `WorktreeListFailedError`

**`Worktree.Info` 字段**（`worktree/index.ts:37-41`，规范化定义见 [WorktreeInfo](#worktreeinfo)）：

| 字段 | 类型 | 说明 |
|------|------|------|
| `name` | `string` | 工作树名称 |
| `branch` | `string?` | 分支名 |
| `directory` | `string` | 目录路径 |

### GET `/experimental/session`（实验性）

跨项目列出所有 OpenCode 会话（按更新时间排序）。

**请求**: Query `SessionListQuery`（`groups/experimental.ts:76-84`）

| Query 参数 | 类型 | 默认 | 说明 |
|-----------|------|------|------|
| `roots` | `boolean?` | — | 只返回根会话 |
| `start` | `number?` | — | 起始偏移 |
| `cursor` | `number?` | — | 分页游标（时间戳） |
| `search` | `string?` | — | 搜索关键词 |
| `limit` | `number?` | **100** | 每页数量（实际请求 `limit+1` 判断是否有更多） |
| `archived` | `boolean?` | — | 是否包含归档会话（默认排除） |
| `directory?`, `workspace?` | — | — | 工作区路由 |

**响应** `200`: `List<Session.GlobalInfo>` + 可选 `x-next-cursor` 响应头

**分页机制**（`handlers/experimental.ts:134-152`）：请求 `limit + 1` 条，若返回超过 `limit` 条则截断并通过 `x-next-cursor` 响应头返回下一页游标（最后一条的 `time.updated` 值）。

### POST `/experimental/session/{sessionID}/background`（实验性）

将阻塞当前会话的同步子代理转为后台运行。

**请求**: Path `sessionId`（`SessionID`）

**响应** `200`: `boolean`（是否有任务被提升为后台）

**边界情况**：`400 BadRequest`。

**门控**：需运行时标志 `experimentalBackgroundSubagents` 开启，否则恒返回 `false`。

**数据来源/原理**（`handlers/experimental.ts:154-167`）：
1. 过滤 `BackgroundJob` 列表：`type === "task"` && `status === "running"` && `parentSessionId === sessionID` && `background !== true`
2. 对每个匹配任务调用 `background.promote(jobID)` 提升为后台
3. 返回是否有至少一个任务被成功提升

### GET `/experimental/resource`（实验性）

获取所有已连接 MCP 服务器的资源列表。

**请求**: Query [`WorkspaceRoutingQuery`](#workspaceroutingquery)

**响应** `200`: `Record<string, MCP.Resource>`（key 为资源名）

**数据来源/原理**（`handlers/experimental.ts`）：`MCP.Service.resources()` 汇总所有 MCP 服务器的资源。

---

## 18. Instance VCS 元信息端点

> 路由：`groups/instance.ts`

### POST `/instance/dispose`

标记当前实例为待销毁（非阻塞，实际清理由生命周期管理器异步执行）。

**请求**: Body 无
**响应** `200`: `boolean`

### GET `/path`

获取服务器路径信息。

**响应** `200`: [`ServerPaths`](#serverpaths)

### GET `/vcs`

获取 VCS 分支信息。

**响应** `200`: `{ "branch": "string?", "default_branch": "string?" }`

### GET `/vcs/status`

获取变更文件列表（不含 patch）。

**响应** `200`: `List<{ file, additions, deletions, status: "added"|"deleted"|"modified" }>`

### GET `/vcs/diff`

获取 diff（含 patch）。

| Query 参数 | 类型 | 说明 |
|-----------|------|------|
| `mode` | `"git" | "branch"` | **必填**。`git` = 工作树差异；`branch` = 与默认分支的差异 |
| `context` | number | diff 上下文行数（≥0） |

**响应** `200`: `List<{ file, patch?, additions, deletions, status? }>`

### GET `/vcs/diff/raw`

获取原始 patch 文本。

**响应** `200`: `text/x-diff` 文本

### POST `/vcs/apply`

应用 patch。

**请求体**: patch 文本

**响应** `200`: `{ "applied": true }`

**错误**: `ApiVcsApplyError`（400，`reason: "non-git" | "not-clean"`）

### GET `/agent`

列出所有可用 AI agent。

**响应** `200`: `List<`[`AgentInfo`](#agentinfo)`>`

### GET `/command`

列出所有可用斜杠命令。

**响应** `200`: `List<`[`CommandInfo`](#commandinfo)`>`

### GET `/skill`

列出所有可用 skill。

**响应** `200`: `List<`[`SkillInfo`](#skillinfo)`>`

### GET `/lsp`

获取 LSP 服务器状态。

**响应** `200`: `List<LSP.Status>`

### GET `/formatter`

获取格式化器状态。

**响应** `200`: `List<Format.Status>`

---

## 19. 跨项目控制平面端点

> 路由：`groups/control-plane.ts` · 注册在 `RootHttpApi`，无实例中间件

### POST `/experimental/control-plane/move-session`

跨目录迁移会话（可选转移本地代码变更）。

**请求体**:
```json
{
  "sessionID": "ses_...",
  "destination": { "directory": "/abs/path" },
  "moveChanges": true                // 是否转移本地未提交变更（通过 git patch）
}
```

**响应** `204`

**迁移流程**:
1. 获取会话当前位置，若与目标相同则直接返回
2. 校验源/目标目录属于**同一项目**（`projectID` 必须匹配，否则 `DestinationProjectMismatchError`）
3. 若 `moveChanges` 且目录不同: `git.patch(sourceDir)` → `git.applyPatch(destDir)`
4. 发布 `SessionEvent.Moved`（含新 location 和 subdirectory）
5. 若有 patch: `git.softResetChanges(sourceDir)` 清理源目录

**错误消息**（`ApiMoveSessionError` HTTP 400）:
- `Session not found: <sessionID>`
- `Destination directory belongs to another project`
- `Unable to apply your changes in the destination directory. The files may conflict with existing changes.`

---

## 20. File Find 端点

> 路由：`groups/file.ts` · Handler：`handlers/file.ts`
> 核心：`@opencode-ai/core/filesystem` + `ripgrep` + `Search` + `LSP`
> 中间件栈：Instance → Workspace → Authorization
> 路径常量：`FilePaths`（`groups/file.ts:82-89`）

### GET `/find`

**用途**：使用 ripgrep 在项目文件中搜索文本模式。

**请求参数**：

| 参数 | 位置 | 类型 | 必填 | 说明 |
|------|------|------|------|------|
| `pattern` | Query | `string` | 是 | ripgrep 兼容的正则/字面量模式 |
| `directory`/`workspace` | Query | 公共参数 | 否 | 工作区路由 |

**返回数据结构**：`Ripgrep.SearchMatch[]`（最多 10 条）

**字段说明**（`Ripgrep.SearchMatch`，来自 `@opencode-ai/core/filesystem/ripgrep`，见 [数据模型 §SearchMatch](#searchmatch)）：

| 字段 | 类型 | 说明 |
|------|------|------|
| `path` | `{ text: string }` | 文件路径（对象形式以兼容二进制路径） |
| `lines` | `{ text: string }` | 匹配行内容 |
| `line_number` | `number` | 行号 |
| `absolute_offset` | `number` | 文件内绝对字节偏移 |
| `submatches` | `Array<{ match: { text: string }, start: number, end: number }>` | 子匹配列表 |

**边界情况**：
- ⚠️ **硬编码 limit: 10**（`handlers/file.ts:27`），客户端无法调整结果数量
- 搜索失败用 `Effect.orDie` 转化为 defect → **HTTP 500**（非可恢复错误，无错误 body）
- ripgrep 二进制不可用时：500

**数据来源/原理**：
- 源码：`groups/file.ts:21-24`（`FindTextQuery`）、`handlers/file.ts:27`
- 调用 `ripgrep.search({ cwd, pattern, limit: 10 })`，spawn 本地 `rg` 二进制

**错误码**：500（ripgrep 不可用/搜索失败 → defect，无错误 body，**不可恢复**）；**无 400**——`pattern` 即使非法也走 ripgrep 路径；401（认证缺失）。

**建议用法**：
- 全文搜索 / 关键字定位
- 需要更多结果时，建议通过 PTY 直接运行 `rg` 命令（可控制 limit 和输出格式）
- `pattern` 是 ripgrep 兼容的正则，特殊字符需转义

### GET `/find/file`

**用途**：通过文件名/路径模糊匹配查找文件。

**请求参数**：

| 参数 | 位置 | 类型 | 必填 | 默认 | 说明 |
|------|------|------|------|------|------|
| `query` | Query | `string` | 是 | — | 搜索关键词 |
| `dirs` | Query | `"true" \| "false"` | 否 | — | 是否包含目录（`"false"` 等价 `type=file`） |
| `type` | Query | `"file" \| "directory"` | 否 | — | 限定类型（覆盖 `dirs`） |
| `limit` | Query | `integer 1-200` | 否 | `10` | 结果数量上限 |
| `directory`/`workspace` | Query | 公共参数 | 否 | — | 工作区路由 |

**返回数据结构**：`string[]`（文件路径列表）

**字段说明**：字符串数组，每项为文件路径（具体格式取决于 adapter 实现）。

**边界情况**：
- ⚠️ **双引擎结果不一致**：fff 基于 frecency + 模糊评分，ripgrep `FileSystem.find` 基于字面匹配。两次相同查询顺序可能不同
- **`kind` 解析逻辑**（`handlers/file.ts:36`）：`type` 优先；否则 `dirs === "false"` → `"file"`；否则 `"all"`
- **降级策略**（`handlers/file.ts:40-71`）：
  1. `search.file({ cwd, query, limit, kind })` —— fff 引擎
  2. fff 返回 `undefined`（不可用）→ 调用 `FileSystem.find` 作为 fallback
  3. 两者都记录日志（`engine` / `results` / `duration`）

**数据来源/原理**：
- 源码：`groups/file.ts:26-34`（`FindFileQuery`）、`handlers/file.ts:36-71`
- fff 优先（frecency 排序），降级到 ripgrep 字面搜索

**错误码**：500（fff/ripgrep 均不可用或失败 → defect）；`limit` 越界（>200 或 <1）由 schema 校验 → 400；401（认证缺失）。

**建议用法**：
- 快速跳转（类似 VSCode Cmd+P）使用 fff 的 frecency 优势
- 不要假设结果顺序稳定（依赖 fff 是否可用）
- 通过服务端日志的 `engine` 字段诊断实际使用了哪个引擎

### GET `/find/symbol`

**用途**：通过 LSP workspace symbols 查找函数/类/变量。

**请求参数**：

| 参数 | 位置 | 类型 | 必填 | 说明 |
|------|------|------|------|------|
| `query` | Query | `string` | 是 | 搜索查询 |
| `directory`/`workspace` | Query | 公共参数 | 否 | 工作区路由 |

**返回数据结构**：`LSP.Symbol[]`

**字段说明**（`LSP.Symbol`，来自 `@/lsp/lsp`，见 [数据模型 §SymbolInfo](#symbolinfo)）：

| 字段 | 类型 | 说明 |
|------|------|------|
| `name` | `string` | 符号名 |
| `kind` | `number` | LSP `SymbolKind` 枚举值 |
| `location` | `{ uri: string, range: { start, end } }` | 符号位置 |
| `containerName` | `string?` | 包含此符号的父符号名 |

**边界情况**：
- 🔴 **桩实现**：当前实现恒返回空数组 `[]`（`handlers/file.ts:74-76`）：
  ```typescript
  const findSymbol = Effect.fn(function* () {
    return []   // 未实现
  })
  ```

**数据来源/原理**：
- 源码：`handlers/file.ts:74-76`
- 设计意图是 LSP workspace/symbol 请求，但尚未接入

**错误码**：无（🔴 **桩实现恒返回 200 + `[]`**，不会失败）；401（认证缺失）。

**建议用法**：
- ⚠️ **不要依赖此端点**，目前永远返回 `[]`
- 替代方案：直接调用 LSP，或通过 PTY 运行 `ctags` / `grep`

### GET `/file`

**用途**：列出指定路径下的文件和目录。

**请求参数**：

| 参数 | 位置 | 类型 | 必填 | 说明 |
|------|------|------|------|------|
| `path` | Query | `string` | 是 | 相对路径（相对于实例 directory） |
| `directory`/`workspace` | Query | 公共参数 | 否 | 工作区路由 |

**返回数据结构**：`LegacyEntry[]`（identifier: `"FileNode"`，见 [数据模型 §FileNode](#filenode)）

**字段说明**（`LegacyEntry`，源码 `groups/file.ts:41-47`）：

| 字段 | 类型 | 说明 |
|------|------|------|
| `name` | `string` | 文件/目录名（basename） |
| `path` | `string` | 相对路径 |
| `absolute` | `string` | 绝对路径（`directory + path`） |
| `type` | `"file" \| "directory"` | 类型 |
| `ignored` | `boolean` | 是否被 gitignore 忽略 |

**边界情况**：
- 路径不存在：行为未明确（可能返回空数组或 500）
- 无权限：500（defect）

**数据来源/原理**：
- 源码：`groups/file.ts:16-19`（`FileQuery`）、`groups/file.ts:41-47`（`LegacyEntry`）、`handlers/file.ts`
- `fs.list({ path: RelativePath })` 列出目录，对每个条目调用 `fs.isIgnored(path, type)` 判断忽略状态

**错误码**：500（路径不存在/无权限 → defect，**无明确 400/404**）；401（认证缺失）。

**建议用法**：
- 文件浏览器 UI
- 利用 `ignored` 字段灰显 gitignore 项

### GET `/file/content`

**用途**：读取文件内容（文本或 base64 二进制）。

**请求参数**：

| 参数 | 位置 | 类型 | 必填 | 说明 |
|------|------|------|------|------|
| `path` | Query | `string` | 是 | 文件路径 |
| `directory`/`workspace` | Query | 公共参数 | 否 | 工作区路由 |

**返回数据结构**：`LegacyContent`（identifier: `"FileContent"`，见 [数据模型 §FileContent](#filecontent)）

**字段说明**（`LegacyContent`，源码 `groups/file.ts:49-73`）：

| 字段 | 类型 | 说明 |
|------|------|------|
| `type` | `"text" \| "binary"` | 内容类型 |
| `content` | `string` | 文本内容 或 base64 编码的二进制 |
| `diff` | `string?` | git diff（可选） |
| `patch` | `Patch?` | 结构化 patch（含 hunks） |
| `encoding` | `"base64"?` | 仅 binary 时出现 |
| `mimeType` | `string?` | 仅 binary 时出现 |

**边界情况**：
- 🔴 **路径逃逸检查返回 500 而非 400**：尝试读取 `../../etc/passwd` 等逃逸路径 → `Effect.die(new Error("Path escapes the location"))` → HTTP 500（非 400）
- **文件不存在 → 返回 `{ type: "text", content: "" }`**（空字符串，**非 404**）
- 文本内容 `trim()` 去除首尾空白（**会修改原始内容**，影响字节精确匹配）

**数据来源/原理**：
- 源码：`groups/file.ts:49-73`（`LegacyContent`）、`handlers/file.ts:97-110`
- **安全流程**：
  1. `path.resolve(directory, ctx.query.path)` 解析为绝对路径
  2. **路径逃逸检查**：`FSUtil.contains(directory, file)` —— 若解析后的路径在 directory 之外 → `Effect.die`（500）
  3. **存在性检查**：`fs.existsSafe(file)` —— 不存在 → 返回空 text 内容
  4. 文本内容 `item.content.trim()`

**错误码**：500（🔴 路径逃逸 → `Effect.die`，**非 400**，无错误 body；详见上"边界情况"）；文件不存在 → **200 + `content: ""`**（非 404）；401（认证缺失）。

**建议用法**：
- 客户端应**自行验证路径**，避免触发服务端逃逸检查（500 难以与其他服务端错误区分）
- 区分"文件不存在"和"空文件"：两者都返回 `content: ""`，需先 `GET /file` 验证存在
- 需要字节精确内容时，二进制走 base64（不被 trim 影响）

### GET `/file/status`

**用途**：获取项目中所有文件的 git 变更状态。

**请求参数**：`WorkspaceRoutingQuery`（无 `path` 参数）

**返回数据结构**：`LegacyStatus[]`（identifier: `"File"`，见 [数据模型 §FileStatusInfo](#filestatusinfo)）

**字段说明**：`LegacyStatus` 通常包含文件路径和变更类型（added/modified/deleted 等），但**当前实现永远为空**。

**边界情况**：
- 🔴 **桩实现**：当前实现恒返回空数组 `[]`（`handlers/file.ts:113-115`）：
  ```typescript
  const status = Effect.fn(function* () {
    return []   // 未实现
  })
  ```

**数据来源/原理**：
- 源码：`handlers/file.ts:113-115`
- 设计意图是 git status，但未接入

**错误码**：无（🔴 **桩实现恒返回 200 + `[]`**）；401（认证缺失）。

**建议用法**：
- ⚠️ **不要依赖此端点**，目前永远返回 `[]`
- 替代方案：使用 `GET /vcs/status`（完整实现）

---

## 21. SSE 事件体系

> 来源：`packages/core/src/event/` + `packages/opencode/src/server/` + `packages/core/src/session/event.ts`
> 事件类型总数：**89 个**（v1 遗留 + v2 细粒度 + 系统/服务）
> 事件框架：基于 `effect` 的类型安全 **EventV2** 系统

### 21.1 连接方式

| 端点 | 范围 | 认证 | 用途 |
|------|------|------|------|
| `GET /event` | **实例级**（当前 directory + workspace） | Basic Auth | 推送当前实例的事件 |
| `GET /global/event` | **全局**（跨项目） | 无 | 推送所有实例的事件 |

**`/event` 行为**（`handlers/event.ts:25-87`）:
1. **首个事件**: `{ type: "server.connected", properties: {} }`
2. **过滤**: `event.location?.directory === instance.directory` && workspace 匹配（`location.workspaceID === undefined || === workspaceID`，无 workspace 限制的事件放行）
3. **心跳**: 每 10 秒 `server.heartbeat`（`Stream.tick("10 seconds")` + `Stream.drop(1)` 丢弃首个 tick，避免与 `server.connected` 重叠）
4. **实例销毁检测**: 监听 GlobalBus 的 `server.instance.disposed`（directory 匹配），收到后推送并**关闭 SSE 流**（`Stream.takeUntil`）

**`/global/event` 行为**（`handlers/global.ts:33-66`）:
1. 首个事件: `server.connected`
2. 后续: 所有 GlobalBus 事件（实例级事件通过 `EventV2Bridge` 桥接 + 全局原生事件）
3. 心跳: 每 10 秒
4. **不自动关闭**（监听全局 bus，不因单实例销毁而关闭）

### 21.2 事件格式

**`/event` 端点**（无包装，`Sse.encode()` 编码）:
```
event: message
data: {"id":"...","type":"session.updated","properties":{...}}
```

**`/global/event` 端点**（`payload` 包装 + 来源信息）:
```
event: message
data: {"directory":"/path","project":"...","workspace":"...","payload":{"id":"...","type":"session.updated","properties":{...}}}
```

**⚠️ 关键 — SSE `event` 字段固定为 `"message"`**:
- 标准 SSE 支持 `event: <name>` 区分类型，但 OpenCode 所有事件的 `event` 字段固定为 `"message"`，**类型信息在 `data` JSON 的 `type` 字段中**
- 客户端**不要**使用 `es.addEventListener("session.next.text.delta", ...)` —— 不会触发
- 正确做法:
  ```javascript
  const es = new EventSource("/event")
  es.addEventListener("message", (e) => {
    const data = JSON.parse(e.data)
    switch (data.type) {
      case "session.next.text.delta": handleDelta(data.properties)
      // ...
    }
  })
  ```

**`/global/event` 解析差异**: 全局事件多一层 `payload` 包装，包含来源信息（directory/project/workspace）。客户端需根据订阅端点使用不同解析逻辑（先解包 `payload` 再读 `type`）。

**响应头**:
```
Content-Type: text/event-stream
Cache-Control: no-cache, no-transform
X-Accel-Buffering: no          # 禁用 nginx 缓冲，确保实时推送
X-Content-Type-Options: nosniff # 防 MIME 嗅探
```

### 21.3 事件分类总览（89 种）

| 分类 | 事件数 | 同步 | 说明 |
|------|--------|------|------|
| 系统与服务 | 4 | ❌ | 连接、心跳、销毁 |
| Session v1 遗留 | 7 | ✅ | 会话/消息 CRUD（粗粒度） |
| Message v1 流式 | 1 | ❌ | `message.part.delta`（瞬时） |
| **Session.next v2 细粒度** | **31** | 27✅/4❌ | AI 推理流程的核心事件 |
| Session 状态/生命周期 | 5 | ❌ | status、idle、diff、error、compacted |
| Todo | 1 | ❌ | 任务列表更新 |
| Permission v1 + v2 | 4 | ❌ | 权限请求（双轨迁移） |
| Question v1 + v2 | 6 | ❌ | 问题请求（双轨迁移） |
| PTY | 4 | ❌ | 终端会话生命周期 |
| MCP | 2 | ❌ | 工具变更、浏览器打开失败 |
| Project/VCS | 3 | ❌ | 项目更新、目录更新、分支更新 |
| LSP/IDE/Command | 3 | ❌ | LSP/IDE 状态、命令执行 |
| Account/Catalog/Plugin | 5 | ❌ | 账户/模型目录/插件管理 |
| Filesystem | 2 | ❌ | 文件编辑、watcher |
| Installation | 2 | ❌ | 版本更新 |
| Workspace/Worktree | 5 | ❌ | 工作区状态/就绪/失败 |
| TUI | 4 | ❌ | TUI 交互（仅 TUI 进程消费） |
| **合计** | **89** | | |

### 21.4 系统与服务事件（4 个）

> 来源：`packages/opencode/src/server/event.ts` + `handlers/event.ts`

**`server.connected`** — 连接建立
- **触发**：客户端建立 SSE 连接时，服务端主动发送的**首个事件**
- **properties**：`{}`
- **同步**：❌ 不持久化
- **客户端建议**：用作连接就绪信号，可在此触发初始数据拉取（会话列表、配置等）

**`server.heartbeat`** — 心跳
- **触发**：每 10 秒（`Stream.tick("10 seconds").pipe(Stream.drop(1))`，`drop(1)` 丢弃首个 tick 避免与 `server.connected` 重叠，真正首次心跳在连接后 10 秒）
- **properties**：`{}`
- **同步**：❌
- **客户端建议**：用于保活检测，超时未收到应判定断线并重连

**`server.instance.disposed`** — 实例销毁
- **触发**：当前实例被销毁时（配置变更、显式 dispose、升级等）
- **properties**：`{ directory: string }`（被销毁实例的目录）
- **来源**：`groups/global.ts:9-13` 的 `InstanceDisposed` schema
- **同步**：❌（**不在 EventV2 registry**，由 `/event` handler 手动监听 GlobalBus 构造）
- **客户端建议**：收到后应**关闭当前 SSE 并重建连接**（实例已失效）；在 `/global/event` 上以原始 GlobalBus 事件形式出现

**`global.disposed`** — 全局销毁
- **触发**：所有实例被销毁时（`POST /global/dispose`、全局配置更新）
- **properties**：`{}`
- **来源**：`packages/opencode/src/server/event.ts:6`
- **同步**：❌（不在 EventV2 registry）
- **客户端建议**：整个 OpenCode 服务正在关闭，应提示用户并停止重连

### 21.5 Session v1 遗留事件（7 个，✅ 同步）

> 来源：`packages/core/src/v1/session.ts:569-627`
> v1 风格：粗粒度，传递完整 `SessionInfo` 或 `Info`（消息）对象
> 通用字段：所有事件 properties 含 `sessionID`

**`session.created`**
- **触发**：`Session.create()` 后
- **properties**：`{ sessionID, info: Session }`
- **同步**：✅ `{ aggregate: "sessionID", version: 1 }`

**`session.updated`**
- **触发**：会话**元数据**变更（标题、权限、归档等）
- **properties**：`{ sessionID, info: Session }`
- **同步**：✅
- ⚠️ **Token 累加不触发** `session.updated`（详见 [§23 Token](#23-token-context-usage)）

**`session.deleted`**
- **触发**：`Session.remove()` 后
- **properties**：`{ sessionID, info: Session }`（含被删除会话的最后状态）
- **同步**：✅

**`message.updated`**
- **触发**：消息内容/状态/Part 变更（粗粒度全量更新）
- **properties**：`{ sessionID, info: Message }`（`Message` 是 User | Assistant 判别联合）
- **同步**：✅

**`message.removed`**
- **触发**：`session.removeMessage()` 后
- **properties**：`{ sessionID, messageID }`
- **同步**：✅

**`message.part.updated`**
- **触发**：Part 内容变更（工具调用完成、文本更新等）
- **properties**：`{ sessionID, part: Part, time: number }`（`Part` 是 12 种联合类型）
- **同步**：✅

**`message.part.removed`**
- **触发**：`DELETE .../part/:id` 后
- **properties**：`{ sessionID, messageID, partID }`
- **同步**：✅

### 21.6 Message v1 流式事件（1 个，❌ 瞬时）

**`message.part.delta`**
- **触发**：Part 字段的流式增量更新
- **来源**：`packages/opencode/src/session/message-v2.ts:61-70`
- **properties**：`{ sessionID, messageID, partID, field: string, delta: string }`
- **字段**：`field` —— `"output"`（工具输出追加）/ `"text"`（文本追加）/ 自定义字段名
- **同步**：❌ **瞬时事件**，不持久化，断线丢失
- **客户端建议**：维护 Part 本地副本，收到 delta 时 append 到对应字段；这是 v1 流式遗留，v2 提供了更细粒度的 `session.next.*.delta`

### 21.7 Session.next v2 细粒度事件（31 个，核心）

> 来源：`packages/core/src/session/event.ts`
> v2 风格：**生命周期 + delta 模式**（`*.started` → `*.delta` → `*.ended`）
> 这是 OpenCode 事件体系的核心，AI 推理流程的细粒度事件

#### 21.7.1 通用字段与同步语义

所有 `session.next.*` 事件包含：
```typescript
{
  timestamp: string,    // ISO 8601 UTC
  sessionID: string,
  ...事件特定字段
}
```

**同步语义**：
- **Durable（持久化）**：27 个事件可同步（`sync: { aggregate: "sessionID", version: 1 或 2 }`）
- **Ephemeral（瞬时）**：4 个 `.delta` 事件不同步（仅实时流，不持久化，断线永久丢失）

**v2 设计哲学（生命周期 + delta 模式）**：

v1 的 `message.part.updated` 传递**完整 Part 对象**，每次更新全量替换，流式场景下效率低下（每次 delta 都序列化整个 Part）。v2 采用：
- `*.started` — 开始（告知客户端创建新对象）
- `*.delta` — 增量（**瞬时**，仅推送一次，客户端累积）
- `*.ended` — 结束（传递完整值，作为权威状态）

**客户端实现要点**：
1. 收到 `started` → 创建本地对象
2. 收到 `delta` → append 到本地对象（**必须实时处理，不能依赖回放**）
3. 收到 `ended` → 用完整值覆盖本地对象（权威）
4. 回放时只有 `started` 和 `ended`，没有 `delta` → 用 `ended` 的完整值重建

**子分类计数**（合计 31）：

| 子分类 | 数量 | 同步 |
|--------|------|------|
| 会话控制 | 9 | 全部 ✅ v1 |
| Shell 命令 | 2 | 全部 ✅ v1 |
| Step（推理步骤） | 3 | started ✅v1 / ended+failed ✅v2 |
| Text（文本输出） | 3 | started+ended ✅v1 / delta ❌ |
| Reasoning（推理过程） | 3 | started+ended ✅v1 / delta ❌ |
| Tool（工具调用） | 7 | input.started+ended+called+progress+success+failed ✅v1 / delta ❌ |
| Retry（重试） | 1 | ✅ v1 |
| Compaction（压缩） | 3 | started+ended ✅（ended 有 v1/v2 双 schema） / delta ❌ |

#### 21.7.2 会话控制事件（9 个）

**`session.next.agent.switched`** — Agent 切换
- **触发**：会话切换到不同 agent（如从 build 切到 plan）
- **properties**：`{ timestamp, sessionID, messageID, agent: string }`
- **同步**：✅ v1

**`session.next.model.switched`** — 模型切换
- **触发**：会话切换到不同模型
- **properties**：`{ timestamp, sessionID, messageID, model: { id: string, providerID: string } }`
- **同步**：✅ v1
- **客户端建议**：更新 UI 显示的当前模型

**`session.next.moved`** — 会话迁移
- **触发**：会话被迁移到新目录（`POST /experimental/control-plane/move-session`）
- **properties**：`{ timestamp, sessionID, location: Location.Ref, subdirectory?: RelativePath }`
- **同步**：✅ v1

**`session.next.prompted`** — 收到 Prompt
- **触发**：用户发送消息（prompt 被创建）
- **properties**：`{ timestamp, sessionID, messageID, prompt: Prompt, delivery: "steer" | "queue" }`
- **同步**：✅ v1
- **`delivery` 字段**：`"steer"`（引导当前对话）/ `"queue"`（排队等待处理）

**`session.next.prompt.admitted`** — Prompt 被接纳
- **触发**：Prompt 通过验证，被加入处理队列
- **properties**：同 `prompted`
- **同步**：✅ v1

**`session.next.prompt.promoted`** — Prompt 被提升
- **触发**：排队的 Prompt 开始被处理
- **properties**：`{ timestamp, sessionID, messageID, prompt, timeCreated }`
- **同步**：✅ v1

**`session.next.interrupt.requested`** — 请求中断
- **触发**：`POST /session/:id/abort` 后
- **properties**：`{ timestamp, sessionID }`
- **同步**：✅ v1

**`session.next.context.updated`** — 上下文更新
- **触发**：会话上下文（系统 prompt、工具配置等）变更
- **properties**：`{ timestamp, sessionID, messageID, text: string }`
- **同步**：✅ v1

**`session.next.synthetic`** — 合成消息
- **触发**：系统注入的合成消息（非用户/AI 自然产生）
- **properties**：`{ timestamp, sessionID, messageID, text: string }`
- **同步**：✅ v1

#### 21.7.3 Shell 命令事件（2 个）

**`session.next.shell.started`** — Shell 命令开始
- **触发**：工具执行 shell 命令开始
- **properties**：`{ timestamp, sessionID, messageID, callID, command: string }`
- **同步**：✅ v1

**`session.next.shell.ended`** — Shell 命令结束
- **触发**：shell 命令执行完成
- **properties**：`{ timestamp, sessionID, callID, output: string }`
- **同步**：✅ v1

#### 21.7.4 Step（推理步骤）事件（3 个）

> 每个 step 对应一次 LLM 调用。`snapshot` 字段用于 revert 时恢复文件状态。

**`session.next.step.started`** — 步骤开始
- **触发**：AI 推理的一个 step 开始（一次 LLM 调用）
- **properties**：`{ timestamp, sessionID, assistantMessageID, agent: string, model: { id, providerID }, snapshot?: string }`
- **`snapshot`**：文件系统快照 ID，用于 revert 时恢复文件状态
- **同步**：✅ v1

**`session.next.step.ended`** — 步骤结束（**Token 相关事件 payload 核心**）
- **触发**：AI 推理 step 完成
- **properties**：`{ timestamp, sessionID, assistantMessageID, finish: string, cost: number, tokens: Tokens, snapshot?: string }`
- **同步**：✅ **v2**（`stepSettlementOptions`，version: 2）
- **`finish` 值**：`"stop"`（正常停止）/ `"length"`（达到长度限制）/ `"tool-calls"`（调用工具）/ `"content-filter"`（内容过滤）等
- **`tokens` 结构**：
  ```typescript
  {
    input: number,        // 输入 token（不含 cache，已扣除 cacheRead/cacheWrite）
    output: number,       // 输出 token（不含 reasoning，已扣除：outputTokens - reasoningTokens）
    reasoning: number,    // 推理 token（独立于 output，不重叠）
    cache: { read: number, write: number }  // 缓存读写（独立于 input）
  }
  ```
- **客户端建议**：累加所有 `step.ended` 的 `tokens` 和 `cost` 获取会话级实时消耗（详见 [§23 Token](#23-token-context-usage)）

**`session.next.step.failed`** — 步骤失败
- **触发**：AI 推理 step 抛错
- **properties**：`{ timestamp, sessionID, assistantMessageID, error: { type: "unknown", message: string } }`
- **同步**：✅ **v2**

#### 21.7.5 Text（文本输出）事件（3 个）

**`session.next.text.started`** — 文本开始
- **触发**：AI 开始输出文本内容（一段新的文本块）
- **properties**：`{ timestamp, sessionID, assistantMessageID, textID: string }`
- **同步**：✅ v1
- **客户端建议**：创建本地文本缓冲区

**`session.next.text.delta`** — 文本增量（**瞬时**）
- **触发**：AI 流式输出文本片段
- **properties**：`{ timestamp, sessionID, assistantMessageID, textID, delta: string }`
- **同步**：❌ **瞬时事件**，不持久化
- ⚠️ **重要**：仅推送一次，不存入事件溯源系统。客户端必须实时监听并累积。回放时只能拿到 `text.ended` 的完整文本

**`session.next.text.ended`** — 文本结束
- **触发**：一段文本输出完成
- **properties**：`{ timestamp, sessionID, assistantMessageID, textID, text: string }`（`text` 是完整内容）
- **同步**：✅ v1
- **客户端建议**：用完整 `text` 覆盖本地缓冲区（权威值）；断线重连后用此重建

#### 21.7.6 Reasoning（推理过程）事件（3 个）

**`session.next.reasoning.started`** — 推理开始
- **触发**：AI 开始输出 reasoning（如 Claude 的 thinking）
- **properties**：`{ timestamp, sessionID, assistantMessageID, reasoningID: string, providerMetadata?: object }`
- **`providerMetadata`**：provider 特定元数据（如 OpenAI 的 `reasoning_effort`）
- **同步**：✅ v1

**`session.next.reasoning.delta`** — 推理增量（**瞬时**）
- **触发**：reasoning 流式输出片段
- **properties**：`{ timestamp, sessionID, assistantMessageID, reasoningID, delta: string }`
- **同步**：❌ **瞬时事件**

**`session.next.reasoning.ended`** — 推理结束
- **触发**：reasoning 输出完成
- **properties**：`{ timestamp, sessionID, assistantMessageID, reasoningID, text: string, providerMetadata?: object }`
- **同步**：✅ v1

#### 21.7.7 Tool（工具调用）事件（7 个）

**`session.next.tool.input.started`** — 工具输入开始
- **触发**：AI 开始生成工具调用的参数
- **properties**：`{ timestamp, sessionID, assistantMessageID, callID, name: string }`
- **同步**：✅ v1

**`session.next.tool.input.delta`** — 工具输入增量（**瞬时**）
- **触发**：工具参数流式生成片段
- **properties**：`{ timestamp, sessionID, assistantMessageID, callID, delta: string }`
- **同步**：❌ **瞬时事件**

**`session.next.tool.input.ended`** — 工具输入结束
- **触发**：工具参数生成完成
- **properties**：`{ timestamp, sessionID, assistantMessageID, callID, text: string }`（`text` 是完整参数 JSON）
- **同步**：✅ v1

**`session.next.tool.called`** — 工具被调用
- **触发**：工具被实际调用执行（与 `input.ended` 不同，这是执行点）
- **properties**：`{ timestamp, sessionID, assistantMessageID, callID, tool: string, input: Record<string, unknown>, provider: { executed: boolean, metadata?: object } }`
- **`provider.executed`**：是否由 provider 端执行（如 OpenAI 内置工具）vs 本地执行
- **同步**：✅ v1

**`session.next.tool.progress`** — 工具执行进度
- **触发**：工具执行过程中的中间状态更新（长时间运行的工具）
- **properties**：`{ timestamp, sessionID, assistantMessageID, callID, structured: ToolOutput.Structured, content: ToolOutput.Content[] }`
- **同步**：✅ v1
- ⚠️ **有界更新**（bounded cadence）：不是每个 stdout 行都发事件，应 checkpoint 语义转换点或按有界频率发送

**`session.next.tool.success`** — 工具执行成功
- **触发**：工具执行成功完成
- **properties**：`{ timestamp, sessionID, assistantMessageID, callID, structured, content, outputPaths?: string[], result?: unknown, provider: { executed, metadata? } }`
- **`outputPaths`**：工具输出保存到磁盘的文件路径列表（当输出超过阈值时）
- **同步**：✅ v1

**`session.next.tool.failed`** — 工具执行失败
- **触发**：工具执行抛错
- **properties**：`{ timestamp, sessionID, assistantMessageID, callID, error: { type: "unknown", message: string }, result?: unknown, provider: { executed, metadata? } }`
- **同步**：✅ v1

#### 21.7.8 Retry（重试）事件（1 个）

**`session.next.retried`** — 推理重试
- **触发**：LLM 调用失败后自动重试
- **properties**：`{ timestamp, sessionID, attempt: number, error: RetryError }`
- **`RetryError` 结构**：
  ```typescript
  {
    message: string,
    statusCode?: number,
    isRetryable: boolean,
    responseHeaders?: Record<string, string>,
    responseBody?: string,
    metadata?: Record<string, string>
  }
  ```
- **同步**：✅ v1

#### 21.7.9 Compaction（压缩）事件（3 个）

**`session.next.compaction.started`** — 压缩开始
- **触发**：会话上下文压缩开始
- **properties**：`{ timestamp, sessionID, messageID, reason: "auto" | "manual" }`
- **同步**：✅ v1

**`session.next.compaction.delta`** — 压缩增量（**瞬时**）
- **触发**：压缩过程的流式输出（AI 生成压缩摘要时）
- **properties**：`{ timestamp, sessionID, messageID, text: string }`
- **同步**：❌ **瞬时事件**

**`session.next.compaction.ended`** — 压缩结束（v1 + v2 双 schema 版本）
- **触发**：压缩完成
- **v1 properties**：`{ timestamp, sessionID, text: string, include?: string }`
- **v2 properties**：`{ timestamp, sessionID, messageID, reason, text: string, recent: string }`
- **同步**：v1 → ✅ v1；v2 → ✅ **v2**
- ⚠️ **双版本**：同一 type 字符串有两个 schema 版本。**当前发布使用 v2**（`{ aggregate: "sessionID", version: 2 }`），v1 decoder（`Compaction.EndedV1`）保留 unpublished 用于回放存储的 beta 事件。客户端解码时需**尝试两种 schema**

### 21.8 Session 状态与生命周期事件（5 个，❌ 不同步）

> 来源：`packages/opencode/src/session/status.ts` + `session.ts` + `compaction.ts`
> 纯内存状态，不进入事件溯源系统

**`session.status`** — 会话状态变更
- **触发**：状态从 idle/busy/retry 转换时
- **properties**：`{ sessionID, status: SessionStatus.Info }`
- **同步**：❌
- **`SessionStatus.Info`**（3 种判别联合）：
  | type | 额外字段 | 说明 |
  |------|---------|------|
  | `idle` | — | 空闲 |
  | `busy` | — | 处理中 |
  | `retry` | `attempt`, `message`, `action?: { reason, provider, title, message, label, link? }`, `next` | 重试中（`next` 是下次重试 Unix 时间戳，可显示倒计时；`action` 提供用户可执行操作，`link` 是操作链接） |

**`session.idle`** — 会话空闲（**DEPRECATED**）
- **触发**：会话变 idle 时，**与 `session.status` 同时发布**
- **properties**：`{ sessionID }`
- **同步**：❌
- ⚠️ 源码标注 `// deprecated`，是 `session.status` 的历史遗留子集，**新代码不应依赖**

**`session.diff`** — 文件 diff 更新
- **触发**：会话产生文件变更，diff 重新计算后
- **properties**：`{ sessionID, diff: FileDiff[] }`
- **同步**：❌

**`session.error`** — 会话错误
- **触发**：会话执行错误（如 `prompt_async` 后台失败）
- **properties**：`{ sessionID?, error? }`
- **`sessionID` 可选**：全局错误可能不绑定特定会话
- **`error`**：7 种错误类型联合（`api_error`/`aborted`/`auth`/`output_length`/`context_overflow`/`structured_output` 等，来自 `SessionV1.Assistant.fields.error`）
- **同步**：❌

**`session.compacted`** — 会话已压缩
- **触发**：会话压缩完成（粗粒度通知，与 `session.next.compaction.ended` 不同）
- **properties**：`{ sessionID }`
- **同步**：❌

### 21.9 Todo 事件（1 个）

**`todo.updated`** — Todo 列表更新
- **触发**：会话的 Todo 列表变更
- **来源**：`packages/opencode/src/session/todo.ts:20-26`
- **properties**：`{ sessionID, todos: Todo.Info[] }`
- **`Todo.Info` 结构**：`{ content: string, status: "pending" | "in_progress" | "completed" | "cancelled", priority: "high" | "medium" | "low" }`
- **同步**：❌

### 21.10 Permission 事件（4 个，v1 + v2 双轨）

> v1 和 v2 **并行发布**（同时收到两套，信息重复）。客户端建议选择 v2，忽略 v1。v1 不会移除（向后兼容）。

**`permission.asked`**（v1）— 权限请求
- **触发**：工具需要权限时
- **来源**：`packages/opencode/src/permission/index.ts:11`
- **properties**：`{ id, sessionID, permission, patterns, metadata, always, tool? }`
- **同步**：❌

**`permission.replied`**（v1）— 权限回复
- **触发**：用户回复权限请求后
- **properties**：`{ sessionID, requestID, reply: "once" | "always" | "reject" }`
- **同步**：❌

**`permission.v2.asked`**（v2）— 权限请求
- **触发**：工具需要权限时（v2 风格）
- **来源**：`packages/core/src/permission.ts:75`
- **properties**：`Request.fields`（v2 schema，更严格的类型）
- **同步**：❌

**`permission.v2.replied`**（v2）— 权限回复
- **触发**：用户回复权限请求后（v2）
- **properties**：`{ sessionID, requestID, reply }`
- **同步**：❌

### 21.11 Question 事件（v1 遗留 + V2 form 服务，#130）

> **2026-08-14 变更**：V2 的 question 工具已迁移到 form 服务——服务器不再发
> `question.v2.*`，而是发 `form.created`（metadata.kind=question）+ `form.replied` /
> `form.cancelled`。App 将 form.created 映射为 QuestionAsked、form.replied/cancelled 映射为
> QuestionReplied/QuestionRejected 复用现有卡片管道。以下 `question.*` 事件为 V1 遗留（1.18.x 仍正常）。

**`question.asked`**（v1）— 问题请求
- **触发**：AI 需要向用户提问（澄清需求、选择方案等）
- **来源**：`packages/opencode/src/question/index.ts:87`
- **properties**：`Request.fields`
- **同步**：❌

**`question.replied`**（v1）— 问题回复
- **触发**：用户回答问题后
- **properties**：`Replied.fields`
- **同步**：❌

**`question.rejected`**（v1）— 问题拒绝
- **触发**：用户拒绝回答问题
- **properties**：`Rejected.fields`
- **同步**：❌

**`question.v2.asked`**（v2）— 问题提出
- **触发**：AI 需要向用户提问（v2 风格）
- **properties**：`Request.fields`（v2 schema）
- **同步**：❌

**`question.v2.replied`**（v2）— 问题回复
- **触发**：用户回答问题后（v2）
- **properties**：`{ sessionID, requestID, answers: Answer[] }`
- **同步**：❌

**`question.v2.rejected`**（v2）— 问题拒绝
- **触发**：用户拒绝回答问题（v2）
- **properties**：`{ sessionID, requestID }`
- **同步**：❌

### 21.12 PTY 事件（4 个）

> 来源：`packages/core/src/pty.ts:93-96`

**`pty.created`** — PTY 创建
- **触发**：`POST /pty` 创建新 PTY 后
- **properties**：`{ info: PtyInfo }`（含 id、title、command、pid 等）
- **同步**：❌

**`pty.updated`** — PTY 更新
- **触发**：`PUT /pty/:id` 更新标题/尺寸后
- **properties**：`{ info: PtyInfo }`
- **同步**：❌

**`pty.exited`** — PTY 退出
- **触发**：PTY 进程退出
- **properties**：`{ id, exitCode: number }`
- **同步**：❌

**`pty.deleted`** — PTY 删除
- **触发**：`DELETE /pty/:id` 后
- **properties**：`{ id }`
- **同步**：❌

### 21.13 MCP 事件（2 个）

> 来源：`packages/opencode/src/mcp/index.ts:50-63`

**`mcp.tools.changed`** — MCP 工具变更
- **触发**：MCP 服务器的工具列表变更（连接/断开/刷新）
- **properties**：`{ server: string }`（变更的 MCP 服务器名）
- **同步**：❌
- **客户端建议**：收到后重新获取工具列表（`GET /experimental/tool`）

**`mcp.browser.open.failed`** — 浏览器打开失败
- **触发**：MCP OAuth 流程中尝试打开浏览器失败（如远程服务器无 GUI）
- **properties**：`{ mcpName: string, url: string }`
- **同步**：❌
- **客户端建议**：向用户显示 URL，让用户手动在浏览器中打开

### 21.14 Project 与 VCS 事件（3 个）

**`project.updated`** — 项目更新
- **来源**：`packages/opencode/src/project/project.ts:57`
- **触发**：项目元数据变更
- **properties**：`Project.Info` 所有字段
- **同步**：❌

**`project.directories.updated`** — 项目目录更新
- **来源**：`packages/core/src/project/copy.ts:95`
- **触发**：项目目录列表变更
- **properties**：`ProjectCopy.Updated` schema
- **同步**：❌

**`vcs.branch.updated`** — VCS 分支更新
- **来源**：`packages/opencode/src/project/vcs.ts:237`
- **触发**：Git 分支切换
- **properties**：`{ branch?: string, default_branch?: string }`
- **同步**：❌

### 21.15 LSP / IDE / Command 事件（3 个）

**`lsp.updated`** — LSP 状态更新
- **来源**：`packages/opencode/src/lsp/lsp.ts:17`
- **触发**：LSP 索引变更
- **properties**：`{}`（空对象，仅通知信号）
- **同步**：❌
- **客户端建议**：收到后重新获取 LSP 状态（`GET /lsp`）

**`ide.installed`** — IDE 插件安装
- **来源**：`packages/opencode/src/ide/index.ts:15`
- **触发**：IDE 插件安装
- **properties**：`IDE.Installed` schema
- **同步**：❌

**`command.executed`** — 命令执行
- **来源**：`packages/opencode/src/command/index.ts:18`
- **触发**：命令执行完成
- **properties**：`{ name, sessionID, arguments, messageID }`
- **同步**：❌

### 21.16 Account / Catalog / Plugin 事件（5 个）

> 来源：`packages/core/src/auth.ts` + `catalog.ts` + `plugin.ts`

**`account.added`** — 账户添加
- **触发**：新 provider 账户被添加
- **properties**：`{ accountID, ... }`
- **同步**：❌

**`account.removed`** — 账户移除
- **触发**：provider 账户被移除（用户登出、token 失效等）
- **properties**：`{ accountID, ... }`
- **同步**：❌

**`account.switched`** — 账户切换
- **触发**：活跃账户切换（如 Console 组织切换）
- **properties**：`{ accountID, ... }`
- **同步**：❌

**`catalog.model.updated`** — 模型目录更新
- **来源**：`packages/core/src/catalog.ts:36`
- **触发**：models.dev 数据刷新后
- **properties**：`Catalog.ModelUpdated` schema
- **同步**：❌

**`plugin.added`** — 插件添加
- **来源**：`packages/core/src/plugin.ts:15`
- **触发**：新插件加载后
- **properties**：`Plugin.Added` schema
- **同步**：❌

### 21.17 Filesystem 事件（2 个）

**`file.edited`** — 文件编辑
- **来源**：`packages/core/src/filesystem.ts:207`
- **触发**：文件被编辑（通过 opencode 的工具）
- **properties**：`Filesystem.Edited` schema
- **同步**：❌

**`file.watcher.updated`** — 文件监听更新
- **来源**：`packages/core/src/filesystem/watcher.ts:23`
- **触发**：文件系统 watcher 检测到外部变更
- **properties**：`Filesystem.Watcher.Updated` schema
- **同步**：❌

### 21.18 Installation 事件（2 个）

> 来源：`packages/opencode/src/installation/index.ts:20-26`

**`installation.updated`** — 版本更新完成
- **触发**：OpenCode 升级完成后
- **properties**：`{ version: string }`
- **同步**：❌

**`installation.update-available`** — 有可用更新
- **触发**：检测到新版本可用
- **properties**：`{ version: string, ... }`
- **同步**：❌

### 21.19 Workspace / Worktree 事件（5 个）

**`workspace.ready`** — 工作区就绪
- **触发**：工作区初始化完成，可用于路由请求
- **来源**：`packages/opencode/src/control-plane/workspace.ts:48`
- **properties**：`{ workspaceID, ... }`
- **同步**：❌

**`workspace.failed`** — 工作区失败
- **触发**：工作区初始化失败（连接错误、配置无效等）
- **来源**：`packages/opencode/src/control-plane/workspace.ts:54`
- **properties**：`{ workspaceID, error, ... }`
- **同步**：❌

**`workspace.status`** — 工作区状态
- **触发**：工作区连接状态变更（连上/断开/错误）
- **来源**：`packages/opencode/src/control-plane/workspace.ts:60`
- **properties**：`{ workspaceID, connected: boolean, error?: string }`
- **同步**：❌

**`worktree.ready`** — Worktree 就绪
- **触发**：worktree 创建完成（`POST /worktree` 后）
- **来源**：`packages/opencode/src/worktree/index.ts:22`
- **properties**：`{ name, directory, ... }`
- **同步**：❌

**`worktree.failed`** — Worktree 失败
- **触发**：worktree 创建失败（git 冲突、权限不足等）
- **来源**：`packages/opencode/src/worktree/index.ts:29`
- **properties**：`{ name, error, ... }`
- **同步**：❌

### 21.20 TUI 事件（4 个）

> 来源：`packages/opencode/src/server/tui-event.ts`
> ⚠️ 主要由 TUI 进程消费，外部客户端通常不需要处理

**`tui.prompt.append`** — 追加提示文本
- **触发**：需要向 TUI 输入框追加文本（如 `/` 触发命令补全）
- **properties**：`{ text: string }`
- **同步**：❌

**`tui.command.execute`** — 执行 TUI 命令
- **触发**：TUI 需要执行一个内置命令
- **properties**：`{ command: string | known-literal }`
- **已知命令字面量**（22 个）：`session.list`、`session.new`、`session.share`、`session.interrupt`、`session.compact`、`session.page.up`、`session.page.down`、`session.line.up`、`session.line.down`、`session.half.page.up`、`session.half.page.down`、`session.first`、`session.last`、`prompt.clear`、`prompt.submit`、`agent.cycle`、`help.show`、`model.list`、`theme.list`
- **同步**：❌

**`tui.toast.show`** — 显示 Toast
- **触发**：需要向用户显示一个临时通知
- **properties**：`{ title?, message, variant: "info" | "success" | "warning" | "error", duration: number }`
- **同步**：❌

**`tui.session.select`** — 选择会话
- **触发**：需要切换到指定会话
- **properties**：`{ sessionID }`
- **同步**：❌

### 21.21 v1/v2 迁移状态

OpenCode 事件体系正在从 v1（粗粒度）向 v2（细粒度）迁移。**v1 和 v2 并行发布**（同时收到两套事件，信息重复），不是替换关系。当前状态：

| 体系 | 事件前缀 | 状态 | 客户端建议 |
|------|---------|------|-----------|
| Session v1 | `session.created/updated/deleted` | ✅ 活跃 | 使用 |
| Message v1 | `message.updated/removed/part.*` | ✅ 活跃 | 使用 |
| **Session.next v2** | `session.next.*` | ✅ **推荐** | **优先使用** |
| Permission v1 | `permission.asked/replied` | ⚠️ 维护 | 兼容 |
| Permission v2 | `permission.v2.*` | ✅ 推荐 | 优先使用 |
| Question v1 | `question.asked/replied/rejected` | ⚠️ 维护 | 兼容 |
| Question v2 | `question.v2.*` | ✅ 推荐 | 优先使用 |

> **建议**：选择一套体系（推荐 v2），忽略另一套。v1 不会移除（向后兼容）。详见 [§21.7.1 v2 设计哲学](#2171-通用字段与同步语义)。

### 21.22 Sync 同步机制

部分事件标记为 `sync: { aggregate: "sessionID", version: N }`，支持**跨工作区事件溯源同步**。

**工作原理**：
1. **事件发布**：服务端发布事件时，如果该事件在 registry 中标记为 `sync: true`，会额外发布一个**包装事件**到 GlobalBus
2. **事件存储**：所有 sync 事件持久化到 SQLite 的 `EventTable`，按 `(aggregateID, seq)` 索引
3. **增量同步**：客户端通过 `POST /sync/history` 查询增量事件，传入 `{ aggregateID: lastKnownSeq }`
4. **回放**：`POST /sync/replay` 接收完整事件序列，设置 `strictOwner: true` 验证归属后应用到本地

**支持同步的事件分类**：

| 分类 | 是否同步 | aggregate | version |
|------|---------|-----------|---------|
| Session v1 (created/updated/deleted) | ✅ | `sessionID` | 1 |
| Message v1 (updated/removed/part.*) | ✅ | `sessionID` | 1 |
| **Session.next 大部分** | ✅ | `sessionID` | 1 或 2 |
| Session.next `.delta` 系列（4 个） | ❌ | — | — |
| Session.status/idle/diff/error/compacted | ❌ | — | — |
| Permission/Question | ❌ | — | — |
| PTY/MCP/Project/VCS/其余 | ❌ | — | — |

**sync 包装事件**（在 `/global/event` 上额外发布）：
```typescript
{
  type: "sync",
  syncEvent: {
    id: EventV2.ID,
    type: "<原始 type>@v<N>",          // 版本化 type，如 session.updated@v1 / session.next.step.ended@v2
    seq: number,                        // 单调递增序列号
    aggregateID: string,                // 聚合根 ID（如 sessionID）
    data: <原始 properties>
  }
}
```

> ⚠️ **客户端解码**：sync 事件的 `syncEvent.type` 带 `@vN` 后缀（与原始 type 不同）。需根据后缀选择正确的 schema 版本解码。同一 type 的不同 schema 版本可共存于事件流（如 `session.next.compaction.ended@v1` 与 `@v2`）。

### 21.23 关键发现与实现陷阱

**🔴 发现 1：v1 和 v2 事件并行发布，不是替换**
- 客户端同时监听两套会收到**重复信息**
- **策略**：选择一套（推荐 v2），忽略另一套

**🔴 发现 2：4 个 `.delta` 事件是瞬时的，无法通过回放获取**
- `session.next.text.delta`、`reasoning.delta`、`tool.input.delta`、`compaction.delta` **不持久化**
- SSE 断线期间错过的 delta → **永久丢失**
- sync/history 回放 → 只有 `*.ended` 的完整值
- **客户端策略**：UI 展示用 delta（实时流式效果）；状态管理用 `ended`（权威完整值）；断线重连后用 `ended` 重建，不依赖 delta

**🟡 发现 3：`session.next.compaction.ended` 存在双 schema 版本**
- 同一 type 字符串，v1 `{ text, include? }` 与 v2 `{ messageID, reason, text, recent }` 共存
- 当前发布使用 v2，v1 decoder 保留用于回放历史 beta 事件
- **客户端解码需尝试两种 schema**

**🟡 发现 4：`session.idle` 是 `session.status` 的废弃子集**
- 每次会话变 idle，会**同时**发布 `session.status`（含 `status: { type: "idle" }`）和 `session.idle`
- 后者历史遗留，**不应在新代码中依赖**

**🟡 发现 5：全局事件的 payload 结构不同于实例事件**
- `/event`：`{ id, type, properties }`
- `/global/event`：`{ directory, project?, workspace?, payload: { id, type, properties } }`
- 客户端需根据端点使用不同解析逻辑

**🟢 发现 6：心跳的 `drop(1)` 设计**
- `Stream.tick("10 seconds")` 订阅时立即产生第一个值，`drop(1)` 避免连接后立刻发送心跳（`server.connected` 已是首个事件）
- 真正首次心跳在连接后 10 秒

**🟢 发现 7：`session.status` 的 `retry` 状态含未来重试时间**
- `retry.next` 是下次重试的 Unix 时间戳，客户端可据此显示倒计时
- `retry.action` 提供用户可执行操作（如"添加付款方式"，`link` 是操作链接）

**🟡 发现 8：sync 包装事件的 `type` 带版本后缀**
- `/global/event` 上收到的 sync 包装事件，其 `syncEvent.type` 是 `<原始 type>@v<N>`（如 `session.updated@v1`、`session.next.step.ended@v2`、`session.next.compaction.ended@v1`/`@v2`）
- 与原始事件 type 不同，客户端解码时需**去除 `@vN` 后缀**还原原始 type，再**按版本号路由 schema**
- 同一原始 type 的不同 schema 版本可共存于事件流（详见 §21.22 Sync 机制）

---

## 22. 数据模型

### Session

```json
{
  "id": "ses_...",
  "slug": "string",
  "projectID": "prj_...",
  "workspaceID": "string?",
  "directory": "/abs/path",
  "path": "string?",
  "parentID": "ses_...?",
  "summary": {
    "additions": 0, "deletions": 0, "files": 0,
    "diffs": [{ "file", "before", "after", "additions", "deletions", "status?" }]
  }?,
  "share": { "url": "string" }?,
  "title": "string",
  "agent": "string?",
  "model": { "id": "string", "providerID": "string", "variant": "string?" }?,
  "version": "string",
  "metadata": { "key": "value" }?,
  "time": {
    "created": 1234567890,
    "updated": 1234567890,
    "compacting": 1234567890?,
    "archived": 1234567890?
  },
  "permission": [{ "permission", "pattern", "action": "allow|deny|ask" }]*?,
  "revert": { "messageID", "partID?", "snapshot?", "diff?" }?,
  // --- V2 新增 ---
  "cost": 0.0?,
  "tokens": {                       // ⚠️ Session 级无 total 字段！
    "input": 0,
    "output": 0,
    "reasoning": 0,
    "cache": { "read": 0, "write": 0 }
  }?
}
```

> **⚠️ Session.tokens 无 `total` 字段**（`total` 只在 Message/StepFinish 级存在）。

### Message（多态，由 `role` 字段区分）

#### User Message

```json
{
  "id": "msg_...",
  "sessionID": "ses_...",
  "role": "user",
  "time": { "created": 1234567890 },
  "agent": "string",
  "model": { "providerID": "string", "modelID": "string", "variant": "string?" }?,
  "format": { "type": "string", "schema": "json?", "retryCount": 0? }?,
  "summary": { "title?", "body?", "diffs?" }?,
  "system": "string?",
  "tools": { "string": bool }?,
  "variant": "string?"
}
```

#### Assistant Message

```json
{
  "id": "msg_...",
  "sessionID": "ses_...",
  "role": "assistant",
  "time": { "created": 1234567890, "completed": 1234567890? },
  "parentID": "msg_...",
  "modelID": "string?",
  "providerID": "string?",
  "agent": "string?",
  "mode": "string?",
  "path": { "cwd": "string", "root": "string" }?,
  "cost": 0.0?,                      // ⚠️ 累加（+=）：每个 step 的 cost 累加到 message.cost
  "tokens": {                        // ⚠️ 覆盖（=）：每个 step 覆盖为最新值（非累加）
    "input": 0,
    "output": 0,
    "total": 0?,                     // 可选，= input + output + reasoning + cache.read + cache.write
    "reasoning": 0,
    "cache": { "read": 0, "write": 0 }
  }?,
  "finish": "string?",
  "error": { "name": "string", "data": { "message": "..." }? }?,
  "structured": "json?",
  "variant": "string?",
  "summary": true?
}
```

> **⚠️ 关键语义**: `Message.tokens` 用 `=` 覆盖（`processor.ts:717`），代表**最后一个 step** 的消耗；`Message.cost` 用 `+=` 累加（`processor.ts:716`），代表所有 step 的累计费用。详见 [§23](#23-token-context-usage)。

### MessageWithParts

```json
{ "info": "<Message>", "parts": ["<Part>"] }
```

### Part（多态，由 `type` 字段区分）

| type | 说明 | 特有字段 |
|------|------|---------|
| `text` | 文本内容 | `text`, `synthetic?`, `ignored?`, `time?: {start, end?}`, `metadata?` |
| `reasoning` | 推理内容 | `text`, `time?: {start, end?}`, `metadata?` |
| `tool` | 工具调用 | `callID`, `tool`, `state: ToolState`, `metadata?` |
| `step-start` | 步骤开始 | `snapshot?` |
| `step-finish` | 步骤结束 | `reason`, `cost`, `tokens: {input, output, total?, reasoning, cache}`, `snapshot?` |
| `file` | 文件附件 | `mime`, `filename?`, `url?`, `source?` |
| `snapshot` | 代码快照 | `snapshot` |
| `patch` | 代码补丁 | `hash`, `files: string[]` |
| `subtask` | 子任务 | `prompt`, `description?`, `agent?`, `model?: {providerID, modelID}`, `command?` |
| `compaction` | 压缩标记 | `auto: bool`, `overflow?`, `tail_start_id?` |
| `retry` | 重试 | `attempt`, `error?`, `time?: {created}` |
| `abort` | 中止 | `reason` |
| `agent` | Agent 切换 | `name`, `source?` |

**公共字段**（所有 Part 都有）: `id`, `sessionID`, `messageID`

### ToolState（多态，由 `status` 字段区分）

| status | 说明 | 字段 |
|--------|------|------|
| `pending` | 待执行 | `input: Map`, `raw?` |
| `running` | 执行中 | `input`, `title?`, `metadata?`, `time?: {start}` |
| `completed` | 完成 | `input`, `output`, `title?`, `metadata?`, `time?: {start, end, compacted?}`, `attachments?: [{type, data?}]` |
| `error` | 出错 | `input`, `error`, `metadata?`, `time?: {start, end}` |

### Project

```json
{
  "id": "prj_...",
  "name": "string",
  "icon": "string?",
  "directory": "/abs/path",
  "worktree": "/worktree/path",
  "vcs": { "branch": "string?", "default_branch": "string?" }?,
  "commands": { "key": "value" }?
}
```

### FileDiff

```json
{
  "file": "string?",
  "patch": "string?",
  "additions": 0,
  "deletions": 0,
  "status": "added | deleted | modified?"
}
```

### PromptRequest

```json
{
  "parts": [
    { "type": "text", "text": "string", "id?": "...", "synthetic?": false },
    { "type": "file", "mime": "string", "url": "string", "filename?": "...", "source?": "..." },
    { "type": "agent", "name": "string", "source?": "..." },
    { "type": "subtask", "prompt": "...", "description": "...", "agent": "...", "model?": {...}, "command?": "..." }
  ],
  "messageID": "msg_...?",
  "model": { "providerID": "string", "modelID": "string" }?,
  "agent": "string?",
  "variant": "string?",
  "format": { "type": "string", "schema": "string?" }?,
  "system": "string?",
  "noReply": true?,
  "tools": { "string": bool }?        // @deprecated（已合并到 permissions）
}
```

### ShellRequest

```json
{
  "agent": "string",                  // 必填
  "model": { "providerID": "string", "modelID": "string" }?,
  "command": "string",
  "messageID": "msg_...?"
}
```

### ConfigInfo

完整配置对象（`packages/core/src/v1/config/config.ts`），所有字段**可选**。关键字段:

| 字段 | 类型 | 说明 |
|------|------|------|
| `$schema` | string? | JSON schema 引用 |
| `shell` | string? | 默认 shell |
| `logLevel` | `"DEBUG"\|"INFO"\|"WARN"\|"ERROR"?` | 日志级别 |
| `server` | object? | 服务器配置 |
| `model` | string? | 默认模型（`provider/model` 格式） |
| `small_model` | string? | 小模型（标题生成等） |
| `default_agent` | string? | 默认 agent（缺省 `build`） |
| `username` | string? | 显示用户名 |
| `agent` | object? | agent 配置（`build`/`plan`/`general`/`explore`/`title`/`summary`/`compaction` + 自定义） |
| `provider` | `Record<string, ConfigProviderV1.Info>?` | provider 配置覆盖 |
| `mcp` | `Record<string, ConfigMCPV1.Info \| { enabled: boolean }>?` | MCP 服务器配置 |
| `disabled_providers` | string[]? | 禁用的 provider |
| `enabled_providers` | string[]? | 仅启用的 provider（白名单） |
| `lsp` | object? | LSP 配置 |
| `formatter` | object? | 格式化器配置 |
| `permission` | object? | 权限规则 |
| `instructions` | string[]? | 额外指令文件 |
| `compaction` | `{ auto?, prune?, tail_turns?, preserve_recent_tokens?, reserved? }?` | 压缩配置 |
| `experimental` | object? | 实验性选项（`batch_tool`、`openTelemetry`、`mcp_timeout` 等） |

> `PATCH /config` 和 `PATCH /global/config` 都是**全量替换**语义。

### ServerPaths

```json
{
  "home": "string",
  "state": "string",         // DB/日志目录
  "config": "string",        // 配置目录
  "worktree": "string",
  "directory": "string"      // 当前工作目录
}
```

### ProviderInfo

```json
{
  "id": "string",
  "name": "string",
  "source": "env | config | custom | api",     // Provider 来源
  "env": ["ANTHROPIC_API_KEY"],
  "key": "string?",                            // API key（已脱敏占位）
  "options": {},
  "models": {
    "modelId": {
      "id": "string",
      "name": "string",
      "toolCallType": "string",
      "attachment": false,
      "reasoning": false,
      "cost": { "input": 0.0, "output": 0.0, "cache": { "read": 0.0, "write": 0.0 }? }?,
      "limit": { "context": 0, "input": 0?, "output": 0 }?,
      "status": "stable | beta | deprecated",
      "release_date": "string"
    }
  }
}
```

### ProviderAuthMethod

```json
{
  "type": "oauth | api",
  "label": "string",
  "prompts": [
    { "type": "text", "key": "...", "message": "...", "placeholder?": "...", "when?": {...} },
    { "type": "select", "key": "...", "message": "...", "options": [{...}], "when?": {...} }
  ]?
}
```

`when` 条件显示: `{ key, op: "eq"|"neq", value }`（根据 provider 已有状态决定是否显示）

### ProviderOauthAuthorization

```json
{
  "url": "string",
  "method": "auto | code",
  "instructions": "string"
}
```

<a id="mcpstatus"></a>

### MCPStatus（5 种状态判别联合）

| status | 额外字段 | 说明 |
|--------|---------|------|
| `connected` | — | 已连接成功 |
| `disabled` | — | 配置中禁用 |
| `failed` | `error: string` | 连接失败 |
| `needs_auth` | — | 需要完成 OAuth 认证 |
| `needs_client_registration` | `error: string` | 需要动态客户端注册（RFC 7591） |

<a id="configmcpinfo"></a>

### ConfigMCPInfo（MCP 配置，local/remote 联合）

**Local MCP**（`type: "local"`）:
```json
{
  "type": "local",
  "command": ["cmd", "arg1"],
  "environment": { "key": "value" }?,
  "enabled": true?,
  "timeout": 5000?              // PositiveInt，ms，默认 5000
}
```

**Remote MCP**（`type: "remote"`）:
```json
{
  "type": "remote",
  "url": "https://...",
  "enabled": true?,
  "headers": { "key": "value" }?,
  "oauth": { "clientId?": "...", "clientSecret?": "...", "scope?": "...", "callbackPort?": 19876, "redirectUri?": "..." } | false?,
  "timeout": 5000?
}
```

> `oauth: false` 禁用自动 OAuth 检测。`clientId` 缺省触发动态注册 RFC 7591。

### AgentInfo

```json
{
  "name": "string",
  "description": "string?",
  "mode": "string (默认 primary)",
  "hidden": false,
  "color": "string?"
}
```

### CommandInfo

```json
{
  "name": "string",
  "description": "string?",
  "source": "string?",
  "hints": ["string"]
}
```

### SkillInfo

```json
{
  "name": "string",
  "description": "string?",
  "location": "string",
  "content": "string"
}
```

### PtyInfo

```json
{
  "id": "pty_01HZ...",
  "title": "Terminal XXXX",
  "command": "/bin/bash",
  "args": ["-l"],
  "cwd": "/abs/path",
  "status": "running | exited",
  "pid": 12345                    // Windows ConPTY 异步分配，spawn 时可能为 0
}
```

### PtyCreateRequest

```json
{
  "command": "string?",
  "args": ["string"]?,
  "cwd": "string?",
  "title": "string?",
  "env": { "key": "value" }?
}
```

### PtyUpdateRequest

```json
{
  "title": "string?",
  "size": { "rows": 24, "cols": 80 }?
}
```

### ShellInfo

```json
{
  "path": "/bin/bash",
  "name": "bash",
  "acceptable": true
}
```

### FileNode

```json
{
  "name": "basename",
  "path": "relative/path",
  "absolute": "/abs/path",
  "type": "file | directory",
  "ignored": false
}
```

### FileContent

```json
{
  "type": "text | binary",
  "content": "string",            // 文本内容 或 base64 编码
  "diff": "string?",              // git diff（可选）
  "patch": {}?,                   // 结构化 patch
  "encoding": "base64"?,          // 仅 binary
  "mimeType": "string?"           // 仅 binary
}
```

### SearchMatch

```json
{
  "path": { "text": "string" },
  "lines": { "text": "string" },
  "line_number": 0,
  "absolute_offset": 0,
  "submatches": [{ "match": { "text": "string" }, "start": 0, "end": 0 }]
}
```

### SymbolInfo

```json
{
  "name": "string",
  "kind": 0,                      // LSP SymbolKind 枚举值
  "location": { "uri": "string", "range": { "start": {}, "end": {} } },
  "containerName": "string?"
}
```

### FileStatusInfo

```json
{ "file": "string", "additions": 0, "deletions": 0, "status": "added | deleted | modified" }
```

### PermissionRequest

```json
{
  "id": "per_...",
  "sessionID": "ses_...",
  "permission": "string",
  "patterns": ["string"],
  "metadata": {}?,
  "always": true?,
  "tool": { "messageID": "msg_...", "callID": "call_..." }?
}
```

### QuestionRequest

```json
{
  "id": "que_...",
  "sessionID": "ses_...",
  "questions": [
    {
      "question": "string",
      "header": "string",
      "options": [{ "label": "string", "description": "string" }],
      "multiple": false?,
      "custom": true?
    }
  ],
  "tool": { "messageID": "msg_...", "callID": "call_..." }?
}
```

### TodoItem

```json
{
  "content": "string",
  "status": "pending | in_progress | completed | cancelled",
  "priority": "high | medium | low"
}
```

### RestSessionStatusInfo

```json
{
  "type": "idle | busy | retry",
  "attempt": 0?,
  "message": "string?",
  "action": { "reason": "...", "provider": "...", "title": "...", "message": "...", "label": "...", "link": "..." }?,
  "next": 0?        // retry 时：下次重试时间戳 (unix ms)
}
```

### WorkspaceInfo

```json
{
  "id": "string",
  "projectID": "prj_...",
  "name": "string",
  "directory": "/abs/path",
  "adapter": "local | ssh | docker"?,
  "extra": {}?
}
```

### WorkspaceConnectionStatus

```json
{ "workspaceID": "string", "connected": true, "error": "string?" }
```

### WorkspaceAdapterEntry

```json
{ "id": "local | ssh | docker", "name": "string", "available": true }
```

### ProjectCopyInfo

```json
{
  "projectID": "prj_...",
  "strategy": "git-worktree",
  "directory": "/abs/path",
  "name": "string",
  "time": { "created": 1234567890 }
}
```

### WorktreeInfo

```json
{ "name": "string", "branch": "string?", "directory": "/abs/path" }
```

---

## 23. Token Context Usage

### 概述

OpenCode 的 token 体系分为**两层**:

| 层级 | 来源 | 说明 |
|------|------|------|
| **消息级** | `Message.Assistant.tokens` | **最后一个 step** 的 token 消耗（覆盖语义，非累加） |
| **Session 级** | `Session.tokens` | 整个 session 的累计 token（数据库 SQL 累加） |

**`Tokens` 结构**（两层共用，**Session 级无 `total` 字段**）:
```json
{
  "input": 0,                    // non-cached input tokens（不含 cache，已扣除 cacheRead/cacheWrite）
  "output": 0,                   // output tokens（不含 reasoning，已扣除：outputTokens - reasoningTokens）
  "reasoning": 0,                // 推理 token（独立于 output，两者不重叠）
  "cache": { "read": 0, "write": 0 }   // cached input tokens（独立于 input）
}
```

### 消息级 Token 语义

**关键语义（源码验证）**:
- `input` **不包含** `cacheRead` / `cacheWrite`，各字段是独立的非重叠值（源码 `session.ts:417`: `adjustedInputTokens = inputTokens - cacheRead - cacheWrite`）
- `output` **不包含** `reasoning`（源码 `session.ts:418`: `output = outputTokens - reasoningTokens`，已扣除推理部分，与 `reasoning` 字段不重叠）
- `total`（Message 级可选）= `input + output + reasoning + cache.read + cache.write`（5 项不重叠，直接相加）

**⚠️ 覆盖 vs 累加（关键区别）**:
- `Message.tokens` 用 `=` **覆盖**（源码 `processor.ts:717`: `ctx.assistantMessage.tokens = usage.tokens`）—— 每个 step 结束时**覆盖为最新值**，代表最后一个 step 的消耗
- `Message.cost` 用 `+=` **累加**（源码 `processor.ts:716`: `ctx.assistantMessage.cost += usage.cost`）—— 每个 step 累加，代表所有 step 的累计费用

> **客户端实现陷阱**: 想获取消息的**总 token** 不能直接读 `Message.tokens`（它只是最后一个 step），应通过累加所有 `step-finish` Part 的 tokens 或监听 `session.next.step.ended` 累加。

**实际 API 返回示例**（两轮对话）:
```json
// 第 1 轮 assistant message:
{ "total": 37498, "input": 37490, "output": 4, "reasoning": 4, "cache": { "read": 0, "write": 0 } }
// 验证: 37490 + 4 + 4 + 0 + 0 = 37498 ✓

// 第 2 轮 assistant message（有 cache 命中）:
{ "total": 37545, "input": 94, "output": 3, "reasoning": 8, "cache": { "read": 37440, "write": 0 } }
// 验证: 94 + 3 + 8 + 37440 + 0 = 37545 ✓
// 注意: input=94 不包含 cacheRead=37440
```

### Session 级 Token 语义

**更新机制（源码 `projector.ts:96-107`）**:

Session.tokens 通过 SQL UPDATE 累加（不是事件累加器）:
```sql
UPDATE session_table SET
  cost = cost + ?,
  tokens_input = tokens_input + ?,
  tokens_output = tokens_output + ?,
  tokens_reasoning = tokens_reasoning + ?,
  tokens_cache_read = tokens_cache_read + ?,
  tokens_cache_write = tokens_cache_write + ?,
  time_updated = ?
WHERE id = ?
```

- 每个 `step-finish` Part 到达时触发累加（sign=1）
- 消息/part 被删除或 revert 时执行减法（sign=-1）

**⚠️ 静默更新，不广播 SSE**:
- `applyUsage` 只更新数据库，**不发布 `session.updated` SSE 事件**
- `session.updated` 仅在**元数据变更**（标题、权限、归档）时触发
- 因此: 客户端通过 REST `GET /session/:id` 拿到的 `Session.tokens` 是最新的（DB 已更新），但通过 SSE `session.updated` 拿到的可能是**过期值**

**客户端实时获取 token 的正确方式**:
1. **监听 `session.next.step.ended`**: 携带单步 `tokens` 和 `cost`，客户端累加
2. **或监听 `message.part.updated`**: 含 `step-finish` Part 的 `tokens`
3. **不要依赖 `session.updated`**: 它的 `Session.tokens` 不反映最新累加

**实际 API 返回示例**:
```json
// 上述两轮对话后，session.tokens:
{ "input": 37584, "output": 7, "reasoning": 12, "cache": { "read": 37440, "write": 0 } }
// 验证: input = 37490 + 94 = 37584 ✓
//        cacheRead = 0 + 37440 = 37440 ✓
// 注意: Session 级无 total 字段
```

**Revert 后的 tokens 变化**:
```json
// revert 到第 1 条消息后，发送新消息:
// session.tokens:
{ "input": 52, "output": 10, "reasoning": 26, "cache": { "read": 37440, "write": 0 } }
// 注意: revert 后 session.tokens 被减去了被 revert 消息的 tokens（sign=-1）
// session.revert 被清除（新消息发送后变为 undefined）
```

### Context Window 使用率计算

Context window 限制的是 **input tokens**（LLM 接收的 prompt 大小）。output 和 reasoning 是 LLM 生成的，不占 input context window。

**⚠️ 正确的计算方式（基于源码 `acp/usage.ts:207`）**:
```
usedContext = input + cacheRead     // 不含 cacheWrite
usage% = usedContext / model.limit.context * 100
```

> **源码证据**（`acp/usage.ts:207`）: `used: message.tokens.input + message.tokens.cache.read`
>
> **为什么不含 cacheWrite**: cacheWrite 是本次写入缓存的 input tokens，下次调用会从 cacheRead 读出。从 context window 占用角度，cacheRead 已经代表了缓存命中部分，cacheWrite 是其中的写入子集（会计入下次的 cacheRead）。ACP 协议采用 `input + cacheRead` 作为 used 口径。

**三种参考口径对比**:

| 口径 | 计算 | 用途 |
|------|------|------|
| **ACP used（推荐）** | `input + cacheRead` | LLM 实际"看到"的 prompt 大小 |
| **WebUI 总量估算** | `input + output + reasoning + cache.read + cache.write` | 显示"总 token 消耗"（粗略，含生成部分） |
| **严格 input context** | `input + cacheRead + cacheWrite` | LLM 接收的原始 prompt 字节数（含本次写入 cache 的部分） |

> **注意**: WebUI 把 output/reasoning 也算进 context usage 是不精确的（output 是生成的，不占 input window）。

### Context Window Limit 获取

```
GET /config/providers → providers[].models[].limit.context
```

**三级 fallback（oc-remote 实现）**:
1. `tokenStats.contextWindow`（之前计算的缓存值）
2. `session.model` → 从 provider 列表中查找对应 model 的 `limit.context`
3. 当前选中 model 的 `contextWindow`

### 推荐的客户端实现

1. **Session 级 Token 聚合（REST）**: 优先使用 `GET /session/:id` 返回的 `Session.tokens`（DB 累加值，准确）
2. **Session 级 Token 实时（SSE）**: **不要**依赖 `session.updated`，应累加 `session.next.step.ended` 事件的 `tokens`
3. **消息级 Token**: `Message.tokens` 是最后一个 step（覆盖），需要总 token 应累加所有 `step-finish` Part
4. **Cost**: `Message.cost` 和 `Session.cost` 都是累加（+=）；reasoning 按 output 价计费
5. **Context Usage 进度条**: `(input + cacheRead) / model.limit.context * 100`（ACP 口径）
6. **Total Tokens 显示**: `input + output + reasoning + cacheRead + cacheWrite`（全部 token 消耗）

### WebUI 会话详情页实现参考

> 以下为 OpenCode 官方 WebUI 的会话详情页（Context Tab）实现方式，供客户端开发者参考。
> 源码位置：`packages/app/src/components/session/session-context-tab.tsx`、`session-context-metrics.ts`、`session-context-breakdown.ts`

**数据来源**：
- `GET /session/:id` → `Session.Info`（title, time.created）
- `GET /session/:id/message` → `Message[]`（token, cost, 消息数）
- `GET /provider` → Provider 列表（context limit）

**Token 计算（取最后一条带 token 的 assistant 消息）**:
- WebUI **不使用** `Session.Info.tokens`（会话级累加），而是取**最后一条带 token 的 assistant 消息**的 `tokens` 作为"当前上下文占用"快照
- `total = input + output + reasoning + cache.read + cache.write`
- `usage% = Math.round((total / limit) * 100)`（limit 来自 provider 的 model 配置）
- provider/model 也取自该消息的 `providerID` / `modelID`

> **为什么取最后一条 assistant 消息**: LLM 每次调用的 input 就是完整上下文，所以最后一条 assistant 消息的 token 代表了最新的上下文占用快照。

**成本计算**:
- 前端累加**所有 assistant 消息**的 `msg.cost`（user 消息无 cost）
- 后端单条 cost（源码 `session.ts:435-449`）: `(input*cost.input + output*cost.output + cache.read*cost.cache.read + cache.write*cost.cache.write + reasoning*cost.output) / 1e6`
- **reasoning 按 output 价计费**

**上下文拆分（纯前端估算，非 API 返回）**:
- `estimateTokens = Math.ceil(chars / 4)`（4 字符 ≈ 1 token）
- 按角色累加字符数：
  - **user**: text/file/agent part 的字符数
  - **assistant**: text + reasoning part 的字符数
  - **tool**: tool part 的 `Object.keys(input).length * 16 + (output|raw|error)` 长度
  - **system**: systemPrompt 长度
- 若估算总和 ≤ input：剩余归 "other"
- 若 > input：按 `input/estimated` 比例缩放

**最后活动时间**:
- 取**最后一条 assistant 消息**的 `time.created`（非 `Session.Info.time.updated`）

**消息数统计**:
- `user messages = messages.filter(m => m.role === "user").length`
- `assistant messages = messages.filter(m => m.role === "assistant").length`

**WebUI 字段与 API 映射表**:

| WebUI 显示字段 | API 来源 | 计算方式 |
|---------------|---------|---------|
| 会话名称 | `Session.Info.title` | 直接返回 |
| 消息数 | `GET /session/:id/message` | `messages.length` |
| 提供商 | `AssistantMessage.providerID` | 最后一条带 token 的 assistant 消息 |
| 模型 | `AssistantMessage.modelID` | 同上 |
| 上下文限制 | `GET /provider` → `model.limit.context` | provider 配置 |
| 总 token | `AssistantMessage.tokens` | 5 项相加（最后一条 assistant） |
| 使用率 | 前端计算 | `total / limit * 100` |
| 输入 token | `AssistantMessage.tokens.input` | 已扣除 cache |
| 输出 token | `AssistantMessage.tokens.output` | 已扣除 reasoning |
| 推理 token | `AssistantMessage.tokens.reasoning` | 独立于 output |
| 缓存 token（读/写）| `tokens.cache.read / cache.write` | 多 provider fallback |
| 用户消息数 | 前端计算 | `filter(role==="user").length` |
| 助手消息数 | 前端计算 | `filter(role==="assistant").length` |
| 总成本 | `AssistantMessage.cost` 累加 | 所有 assistant 消息 |
| 创建时间 | `Session.Info.time.created` | 直接返回 |
| 最后活动 | `AssistantMessage.time.created` | 最后一条 assistant 消息 |
| 上下文拆分 | 前端估算 | 字符/4，非 API 返回 |

### Provider Model Context Limit 实际数据

```
GET /config/providers → providers[].models[].limit.context

Provider: zhipuai-coding-plan (Zhipu AI Coding Plan)
  glm-5.1          — context=200000
  glm-5v-turbo     — context=200000
  glm-5-turbo      — context=200000
  glm-4.5-air      — context=131072
  glm-4.6v         — context=128000

Provider: deepseek (DeepSeek)
  deepseek-chat         — context=1000000
  deepseek-reasoner     — context=1000000
  deepseek-v4-flash     — context=1000000
  deepseek-v4-pro       — context=1000000

Provider: opencode-go (OpenCode Go)
  minimax-m3       — context=512000
  kimi-k2.6        — context=262144
  qwen3.7-max      — context=1000000
  minimax-m2.7     — context=204800
```

---

## 端点总览

> 共 **129 个 HTTP/WebSocket 端点**（128 REST + 1 WebSocket，含实验性）

### 1. Global（6 个）

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/global/health` | 健康检查（无认证） |
| GET | `/global/config` | 全局配置（无认证） |
| PATCH | `/global/config` | 更新全局配置（无认证，销毁所有实例） |
| GET | `/global/event` | 全局 SSE 事件流（无认证） |
| POST | `/global/dispose` | 销毁所有实例（无认证） |
| POST | `/global/upgrade` | 升级 OpenCode（无认证） |

### 2. Config（3 个）

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/config` | 实例配置 |
| PATCH | `/config` | 更新实例配置（销毁当前实例） |
| GET | `/config/providers` | 配置中的 Provider |

### 3. Provider（4 个）

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/provider` | 所有 Provider（含连接状态） |
| GET | `/provider/auth` | Provider 认证方式 |
| POST | `/provider/{id}/oauth/authorize` | OAuth 授权（返回可能 null） |
| POST | `/provider/{id}/oauth/callback` | OAuth 回调 |

### 4. MCP（8 个）

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/mcp` | MCP 服务器状态 |
| POST | `/mcp` | 添加 MCP 服务器 |
| POST | `/mcp/{name}/auth` | MCP OAuth 启动 |
| POST | `/mcp/{name}/auth/callback` | MCP OAuth 回调 |
| POST | `/mcp/{name}/auth/authenticate` | MCP OAuth 一键式 |
| DELETE | `/mcp/{name}/auth` | 移除 MCP 凭据 |
| POST | `/mcp/{name}/connect` | 连接 MCP |
| POST | `/mcp/{name}/disconnect` | 断开 MCP |

### 5. Project（5 个）

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/project` | 列出项目 |
| GET | `/project/current` | 当前项目 |
| POST | `/project/git/init` | 初始化 Git（触发 reload） |
| PATCH | `/project/{id}` | 更新项目 |
| GET | `/project/{id}/directories` | 项目目录列表 |

### 6. ProjectCopy（3 个，experimental）

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/experimental/project/{id}/copy` | 创建副本（AI 名称生成） |
| DELETE | `/experimental/project/{id}/copy` | 移除副本（**带 body**） |
| POST | `/experimental/project/{id}/copy/refresh` | 刷新副本列表 |

### 7. Workspace（7 个，experimental）

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/experimental/workspace/adapter` | 工作区适配器 |
| GET | `/experimental/workspace` | 列出工作区 |
| POST | `/experimental/workspace` | 创建工作区 |
| POST | `/experimental/workspace/sync-list` | 同步工作区列表 |
| GET | `/experimental/workspace/status` | 工作区连接状态 |
| DELETE | `/experimental/workspace/{id}` | 移除工作区 |
| POST | `/experimental/workspace/warp` | 迁移会话到工作区（id:null 脱离） |

### 8. Reference（1 个）

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/reference` | 配置的引用（local/git/invalid 联合） |

### 9. Session（20 个）

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/session` | 列出会话 |
| GET | `/session/status` | 会话状态批量查询 |
| POST | `/session` | 创建会话（空 body 也可） |
| POST | `/session/import` | 导入分享会话 |
| GET | `/session/{id}` | 获取会话 |
| PATCH | `/session/{id}` | 更新会话（permission 合并 / metadata 替换） |
| DELETE | `/session/{id}` | 删除会话 |
| POST | `/session/{id}/abort` | 中止（不检查存在性） |
| GET | `/session/{id}/diff` | 文件差异（不检查存在性） |
| POST | `/session/{id}/summarize` | 压缩总结 |
| POST | `/session/{id}/revert` | 回退消息（撤销文件变更） |
| POST | `/session/{id}/unrevert` | 撤销回退 |
| POST | `/session/{id}/fork` | 分叉（空 body 也可） |
| POST | `/session/{id}/share` | 分享（失败 500） |
| DELETE | `/session/{id}/share` | 取消分享 |
| GET | `/session/{id}/children` | 子会话列表 |
| GET | `/session/{id}/todo` | Todo 列表 |
| POST | `/session/{id}/command` | 执行命令（model 字符串格式） |
| POST | `/session/{id}/shell` | Shell 命令（检查忙碌） |
| POST | `/session/{id}/init` | 初始化 AGENTS.md |

### 10. Message（7 个）

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/session/{id}/message` | 消息列表（非标准分页 Header） |
| GET | `/session/{id}/message/{mid}` | 消息详情 |
| POST | `/session/{id}/message` | **同步发送消息**（非流式，阻塞等完整响应） |
| POST | `/session/{id}/prompt_async` | 异步发送（fire-and-forget） |
| DELETE | `/session/{id}/message/{mid}` | 删除消息（检查忙碌，不撤销文件） |
| DELETE | `/session/{id}/message/{mid}/part/{pid}` | 删除 Part（不检查忙碌） |
| PATCH | `/session/{id}/message/{mid}/part/{pid}` | 更新 Part（三重 ID 校验） |

### 11. Permission（3 个）

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/permission` | 待处理权限 |
| POST | `/permission/{id}/reply` | 回复权限 |
| POST | `/session/{id}/permissions/{pid}` | **已废弃** |

### 12. Question（3 个）

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/question` | 待处理问题 |
| POST | `/question/{id}/reply` | 回复问题 |
| POST | `/question/{id}/reject` | 拒绝问题 |

### 13. PTY（8 个）

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/pty/shells` | 可用 Shell（fish/nu 不可接受） |
| GET | `/pty` | PTY 会话列表 |
| POST | `/pty` | 创建 PTY |
| GET | `/pty/{id}` | PTY 详情 |
| PUT | `/pty/{id}` | 更新 PTY |
| DELETE | `/pty/{id}` | 删除 PTY |
| POST | `/pty/{id}/connect-token` | WebSocket 票据（x-opencode-ticket:1 + Origin） |
| WS | `/pty/{id}/connect` | WebSocket（ticket + cursor） |

### 14. TUI（13 个）

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/tui/append-prompt` | 追加提示 |
| POST | `/tui/open-help` | 打开帮助 |
| POST | `/tui/open-sessions` | 打开会话列表 |
| POST | `/tui/open-themes` | 打开主题（⚠️ 源码 bug：同 open-sessions） |
| POST | `/tui/open-models` | 打开模型选择 |
| POST | `/tui/submit-prompt` | 提交输入 |
| POST | `/tui/clear-prompt` | 清空输入 |
| POST | `/tui/execute-command` | 执行命令（遗留别名） |
| POST | `/tui/show-toast` | 显示 Toast |
| POST | `/tui/publish` | **通用 TUI 事件发布**（4 种联合） |
| POST | `/tui/select-session` | 选择会话 |
| GET | `/tui/control/next` | 获取下一个请求（通道 B，阻塞） |
| POST | `/tui/control/response` | 提交响应（通道 B） |

### 15. 控制平面基础（3 个，无认证）

| 方法 | 路径 | 说明 |
|------|------|------|
| PUT | `/auth/{id}` | 设置认证凭据（OAuth/API/WellKnown 联合） |
| DELETE | `/auth/{id}` | 删除认证凭据 |
| POST | `/log` | 写入服务端日志 |

### 16. Sync（4 个）

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/sync/start` | 启动工作区同步 |
| POST | `/sync/replay` | 回放事件（aggregateID 驼峰） |
| POST | `/sync/steal` | 窃取会话到当前工作区 |
| POST | `/sync/history` | 增量查询（aggregate_id 蛇形） |

### 17. Experimental（12 个）

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/experimental/console` | Console 组织状态 |
| GET | `/experimental/console/orgs` | 可切换组织 |
| POST | `/experimental/console/switch` | 切换组织 |
| GET | `/experimental/tool` | 工具列表（含参数 schema） |
| GET | `/experimental/tool/ids` | 工具 ID |
| GET | `/experimental/worktree` | 工作树列表 |
| POST | `/experimental/worktree` | 创建工作树（允许空 body） |
| DELETE | `/experimental/worktree` | 删除工作树 |
| POST | `/experimental/worktree/reset` | 重置工作树 |
| GET | `/experimental/session` | 全局会话列表（x-next-cursor 分页） |
| POST | `/experimental/session/{id}/background` | 后台化子代理（需 feature flag） |
| GET | `/experimental/resource` | MCP 资源 |

### 18. Instance VCS 元信息（12 个）

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/instance/dispose` | 销毁实例 |
| GET | `/path` | 路径信息 |
| GET | `/vcs` | VCS 分支信息 |
| GET | `/vcs/status` | 变更文件列表 |
| GET | `/vcs/diff` | diff（mode: git/branch） |
| GET | `/vcs/diff/raw` | 原始 patch 文本 |
| POST | `/vcs/apply` | 应用 patch |
| GET | `/agent` | Agent 列表 |
| GET | `/command` | 命令列表 |
| GET | `/skill` | Skill 列表 |
| GET | `/lsp` | LSP 状态 |
| GET | `/formatter` | 格式化器状态 |

### 19. 跨项目控制平面（1 个）

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/experimental/control-plane/move-session` | 跨目录迁移会话（可选转移变更） |

### 20. File Find（6 个）

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/find` | 文本搜索（ripgrep，硬编码 limit 10） |
| GET | `/find/file` | 模糊文件搜索（fff + ripgrep 降级） |
| GET | `/find/symbol` | **桩实现**，恒返回 [] |
| GET | `/file` | 目录列表 |
| GET | `/file/content` | 文件内容（逃逸 500，不存在返回空） |
| GET | `/file/status` | **桩实现**，恒返回 [] |

---

## SSE 事件总览

> 共 **89 种 SSE 事件类型**

| 分类 | 数量 | type 前缀 |
|------|------|----------|
| 系统与服务 | 4 | `server.*` / `global.disposed` |
| Session v1 | 7 | `session.created/updated/deleted` / `message.*` |
| Message v1 流式 | 1 | `message.part.delta` |
| Session.next v2 | 31 | `session.next.*` |
| Session 状态 | 5 | `session.status/idle/diff/error/compacted` |
| Todo | 1 | `todo.updated` |
| Permission | 4 | `permission.asked/replied` / `permission.v2.*` |
| Question | 6 | `question.*` / `question.v2.*` |
| PTY | 4 | `pty.*` |
| MCP | 2 | `mcp.*` |
| Project/VCS | 3 | `project.*` / `vcs.branch.updated` |
| LSP/IDE/Command | 3 | `lsp.updated` / `ide.installed` / `command.executed` |
| Account/Catalog/Plugin | 5 | `account.*` / `catalog.*` / `plugin.added` |
| Filesystem | 2 | `file.edited` / `file.watcher.updated` |
| Installation | 2 | `installation.*` |
| Workspace/Worktree | 5 | `workspace.*` / `worktree.*` |
| TUI | 4 | `tui.*` |

**5 个瞬时事件**（不持久化，断线丢失）: `message.part.delta`（v1）、`session.next.text.delta`、`session.next.reasoning.delta`、`session.next.tool.input.delta`、`session.next.compaction.delta`（其中 session.next.* 的 4 个 delta 是 v2 核心瞬时事件，详见 [§21.7.1](#2171-通用字段与同步语义)）

**关键事件订阅建议**:
- 实时 token/cost: `session.next.step.ended`（携带 tokens + cost）
- 实时文本流: `session.next.text.started/delta/ended`
- 实时工具调用: `session.next.tool.*` 系列
- 状态变更: `session.status` + `session.error`
- 权限/问题: 优先 v2（`permission.v2.*` / `question.v2.*`）
