# 2026-08-12 菜单走查批次（fork/share 实测发现）
> 状态：全部完结
> 迁移：2026-08-20 自 backlog.md 原文迁入（spec/journal 分离批次；原文逐字保留，未压缩）


- [x] **#77 fork 请求 400 被吞 → 空 id 幽灵会话（客户端已修，服务器待上游）** `session` `bug`
  - 问题：2026-08-12 菜单走查（模拟器）发现——点 Fork session 后服务器实际返回 **400 Bad Request**，但 `V2ApiClient.forkSession` 不检查响应状态 → 错误体被 `flexibleObject` 解析为空对象 → `Session.id=""` → 导航进空 id"幽灵会话"，后续操作（Share 等）打到 `/api/session/` 列表端点 → unwrap 崩溃（"Failed to share session"）
  - 服务器侧：fork 端点 `handle("fork")` + `handleRaw("fork")` 同路径注册冲突——curl 实测任何请求方式（JSON `{}` / 空 body / text/plain / multipart）均 400/415（"Missing key at [\"boundary\"]" / "Expected object, got undefined"）
  - 客户端修复（3211e95c 之后补丁）：forkSession 检查 `response.status.isSuccess()`，非 2xx 抛 `IllegalStateException` → UI 显示 "Failed to fork session" Snackbar，不再进入幽灵会话（模拟器验证 PASS）
  - 待办：服务器修复 fork 端点后（handle/handleRaw 冲突），App fork 即可正常；**1.0.0 前应复测 fork 全流程**
  - 工时：~0.5h | 难度：低 | 涉及：V2ApiClient.forkSession

- [x] **#78 V2 下 Share session 永远失败（服务器无 share 端点，UI 提示"Failed to share session"）** `session` `compat`
  - 问题：2026-08-12 菜单走查（模拟器）发现——V2 服务器**无 share 端点**（V2ApiClient.shareSession 注释 no-op getSession），且 `V2SessionMapper.toSession` 不映射 share 字段 → `session.share?.url` 恒为 null → Snackbar "Failed to share session"
  - 修复方向：V2 连接下隐藏 Share 菜单项（需将 apiVersion 传入 ChatTopBar）；或服务器提供 share 功能后适配
  - 工时：~0.5h | 难度：低 | 涉及：ChatTopBar / SessionActionsDelegate
  - **2026-08-12 完成**：V2 下隐藏 Share/Unshare 菜单项——ChatViewModel 暴露 serverApiVersion StateFlow；ChatTopBar 加 isShareSupported 参数包裹 Share 菜单组；ChatScreen 按 `serverApiVersion != ApiVersion.V2` 传参（V1 保留 Share）。注意：运行中的 V2 服务器（旧版）share 端点 404；新版 opencode 源码已有 `POST/DELETE /api/session/:id/share` 端点，且新版 Session.Info **无 share 字段**（分享链接由服务器内部维护）——服务器升级后需重新适配 share 协议再恢复菜单

---
