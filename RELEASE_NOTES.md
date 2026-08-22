## OC Beacon 0.3.1-dev.21 — 2026-08-23

> 版本摘要：GitHub 错误上报功能全链路打穿——修复 5 个阻断性 bug（设备授权/提交/查重），CI 构建自动注入 App 凭据。

### Fixed

- **GitHub 错误上报全链路修复**（诊断屏 → 举报到 GitHub）：
  - 设备码请求缺 form 编码导致解析崩溃——现已正常弹出 8 位授权码
  - API 客户端模板转义错误：请求 URL 变乱码回落 localhost、令牌头失效——已修
  - 上报失败不再显示光杆「上报失败」：401/限流/HTTP/网络错误均有可读原因
  - 查重搜索使用了不存在的 POST 端点（恒 404）——改 GET；指纹中的冒号被 GitHub 搜索误解析为限定词——归一化处理。同一错误现在正确**评论追加**到既有 issue 而非重复开新
  - GitHub 专用网络栈强制 HTTP/1.1 + 超时（应对网络中间层对 h2 的干扰）

### Changed

- 凭据注入机制：GitHub App 凭据从 local.properties 读取（不进 git）；CI 构建自动从仓库 Secrets 注入——正式渠道 APK 开箱即用上报功能


---
完整变更记录：[Full Changelog](https://github.com/LeoNardo-LB/oc-beacon/compare/v0.3.1-dev.20...v0.3.1-dev.21)
