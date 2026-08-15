## OC Beacon 0.3.1-dev.2 — 2026-08-15

> 版本摘要：紧急修复——cleartext 白名单化误伤自建服务器，Tailscale/LAN IP 明文连接被拦截。

### Fixed

- **安装 0.3.1-dev.1 后 Tailscale/LAN 服务器连不上**：上一版的安全加固把明文 HTTP 白名单收窄到 localhost/127.0.0.1/10.0.2.2（模拟器地址），真机经 Tailscale（100.x.x.x）或局域网 IP 连接自建服务器被 Android 直接拒绝。本应用是自建服务器客户端，明文 HTTP 是核心场景——已恢复全局放行。

---
完整变更记录：[Full Changelog](https://github.com/LeoNardo-LB/oc-beacon/compare/v0.3.1-dev.1...v0.3.1-dev.2)
