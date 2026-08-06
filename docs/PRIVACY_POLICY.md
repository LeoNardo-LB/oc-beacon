# Privacy Policy

**Last updated**: August 6, 2026

This Privacy Policy describes how OC Beacon ("the App") handles information. OC Beacon is an unofficial Android client for OpenCode servers. It is a standalone community fork and is not affiliated with, endorsed by, or sponsored by the OC Remote project, its author, or the OpenCode team.

By using the App, you agree to the practices described below.

## 1. Information You Provide

- **Server configuration**: To connect to an OpenCode server, you provide the server URL, username, and password. These are stored **locally on your device** only. The password is encrypted using the Android Keystore system (AES-GCM) before being written to local storage.
- **Chat messages and prompts**: When you use the App, your messages, prompts, and related content are transmitted directly between your device and the OpenCode server you configured. The App does not route this traffic through any third-party service.

## 2. Information Processed on Your Device

- **Session and chat content**: Chat sessions, tool call results, file contents opened in the workspace viewer, and terminal output are transmitted between your device and your own server. This data may be cached locally on your device to provide offline access and is not transmitted anywhere else.
- **Diagnostic logs**: The App can record diagnostic logs for troubleshooting. These logs are stored locally on your device, are capped at 1000 entries / 10 MB, and are automatically pruned. Sensitive values (passwords, tokens, authorization headers, API keys) are **redacted** before being written to logs.

## 3. Information Automatically Collected

The App does **not** include analytics SDKs, advertising SDKs, or crash-reporting SDKs. No device identifiers, usage statistics, or behavioral data are collected by us.

## 4. Network Connections

- **Your OpenCode server**: The App connects only to the server addresses you configure. Depending on how you set up your server, this connection may use HTTP (unencrypted) or HTTPS. The App displays a warning when connecting over unencrypted HTTP.
- **GitHub (dev/beta builds only)**: Development and beta builds may check for app updates by querying GitHub releases (`github.com/LeoNardo-LB/oc-beacon`). The stable build distributed through Google Play does not perform update checks.
- **Images in chat**: Images referenced in chat messages are loaded directly from their source URLs.

## 5. Permissions

The App requests the following permissions, used solely for its core functionality:

| Permission | Purpose |
|---|---|
| Internet / Network state | Connecting to your OpenCode server |
| Foreground service (data sync) | Keeping the SSE/streaming connection alive while in use |
| Notifications | Notifying you about assistant replies and pending permission requests |
| Wake lock | Maintaining the streaming connection while the screen is off (user-controlled) |
| Install packages (dev/beta builds only) | Installing downloaded updates from GitHub releases |

The App does **not** request access to your contacts, location, microphone, camera, SMS, or call history.

## 6. Data Storage and Backup

All app data is stored locally on your device. Server configuration (including passwords) is **excluded from Android cloud backup and device transfer** to protect your credentials.

## 7. Data Sharing

The App does not sell, rent, or share your personal information with any third party. Data you enter is exchanged only with the server you explicitly configure.

## 8. Data Security

- Passwords are encrypted at rest using the Android Keystore (hardware-backed where available).
- Diagnostic logs redact credentials before writing.
- Cloud backup of credential-bearing data is disabled.

## 9. Children's Privacy

The App is not directed at children under 13 (or the applicable minimum age in your jurisdiction), and we do not knowingly collect information from children.

## 10. Changes to This Policy

We may update this Privacy Policy from time to time. Material changes will be reflected by updating the "Last updated" date above.

## 11. Contact

For questions about this Privacy Policy, please open an issue at:
https://github.com/LeoNardo-LB/oc-beacon/issues

---

# 隐私政策（中文）

**更新日期**：2026年8月6日

OC Beacon 是 OpenCode 服务器的非官方 Android 客户端，为独立社区分支，与 OC Remote 原项目、其作者及 OpenCode 团队无关联、未获其背书或赞助。

## 1. 您提供的信息

- **服务器配置**：您需提供服务器 URL、用户名和密码以连接 OpenCode 服务器。这些信息**仅存储在您的设备本地**。密码在写入本地存储前会使用 Android Keystore 系统（AES-GCM）加密。
- **聊天消息与提示词**：使用应用时，您的消息、提示词及相关内容仅在您的设备与您配置的 OpenCode 服务器之间直接传输，不经过任何第三方服务。

## 2. 在您设备上处理的信息

- **会话与聊天内容**：聊天会话、工具调用结果、工作区查看器中打开的文件内容及终端输出在您的设备与您自己的服务器之间传输。这些数据可能缓存在设备本地以提供离线访问，不会被传输到任何其他地方。
- **诊断日志**：应用可记录诊断日志用于故障排查。日志仅存储在设备本地，上限 1000 条 / 10 MB，自动修剪。敏感值（密码、令牌、授权头、API 密钥）在写入日志前会**脱敏**。

## 3. 自动收集的信息

本应用**不包含**分析 SDK、广告 SDK 或崩溃上报 SDK。我们不收集任何设备标识符、使用统计或行为数据。

## 4. 网络连接

- **您的 OpenCode 服务器**：应用仅连接您配置的服务器地址。根据您的服务器配置，连接可能使用 HTTP（明文）或 HTTPS。应用在使用明文 HTTP 连接时会显示警告。
- **GitHub（仅 dev/beta 构建）**：开发版和测试版可能通过查询 GitHub Releases（`github.com/LeoNardo-LB/oc-beacon`）检查应用更新。通过 Google Play 分发的正式版不执行更新检查。
- **聊天中的图片**：聊天消息中引用的图片直接从其来源 URL 加载。

## 5. 权限

应用仅为核心功能请求以下权限：互联网/网络状态（连接服务器）、前台服务（保持流式连接）、通知（回复与权限请求提醒）、唤醒锁（息屏时保持连接，用户可控）、安装应用（仅 dev/beta 版，用于安装从 GitHub 下载的更新）。

应用**不**请求通讯录、定位、麦克风、相机、短信或通话记录权限。

## 6. 数据存储与备份

所有应用数据仅存储在设备本地。服务器配置（含密码）**已排除在 Android 云备份和设备迁移之外**，以保护您的凭据。

## 7. 数据共享

应用不向任何第三方出售、出租或共享您的个人信息。您输入的数据仅与您明确配置的服务器交换。

## 8. 数据安全

- 密码使用 Android Keystore（可用时硬件级）加密存储。
- 诊断日志在写入前脱敏凭据。
- 禁用含凭据数据的云备份。

## 9. 儿童隐私

本应用不面向 13 岁以下儿童（或您所在司法辖区的适用最低年龄），我们不会故意收集儿童信息。

## 10. 政策变更

我们可能不时更新本隐私政策。重大变更将反映在上方"更新日期"中。

## 11. 联系方式

如有疑问，请在以下地址提交 Issue：
https://github.com/LeoNardo-LB/oc-beacon/issues
