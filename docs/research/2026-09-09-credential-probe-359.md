# 服务器凭据探查与自动输入调研（#359，2026-09-09）

> 问题（走查反馈⑨）：「是否有方案探查到凭据然后自动输入？」——2026-09-04 设备数据事故后三条服务器条目（192.168.110.248:248 / V1-4198 / dsh012-a5）需重配，凭据不在宿主素材。
> 交付：scripts/cred-probe.sh（可行路径的探查+注入）+ 本文档边界报告。app 侧零改动——debug intent 通道（debug_url/debug_username/debug_password/debug_name/debug_server_type/debug_token，MainActivity.handleDebugProfileIntent）与 dsh-pair.sh 机制已全覆盖。

## 1. 宿主可探查面清点（2026-09-09 实测）

| 服务器 | 现状 | 凭据可探查面 | 结论 |
|---|---|---|---|
| Host-4199（V2 生产） | opencode2.exe serve --service 监听 0.0.0.0:4199 | ~/.config/opencode/service.json password 字段（service.json.bak 同） | ✅ 已有（debug-entry.sh） |
| V1-4198 | **未运行**（无监听/无进程；/tmp/v1srv 空） | ①运行中：/proc/<pid>/environ 的 OPENCODE_SERVER_PASSWORD（同用户可读）；②未运行：凭据从未落盘——但该服务是 runbook 配方测试服（我们是凭据权威），重启时**定义**口令并落盘 /tmp/v1srv/.pass（600） | ✅ 可行（重启即重定义——与 DSH token 重启轮换同语义） |
| dsh012-a5（DSH 0.1.2-alpha.5 探针） | **容器已删**（docker ps -a 无） | 镜像 dsh-keepalive-e2e:0.1.2-alpha.5 本地在（2.67GB）——可重建容器；launch token 仅存进程内存（#325 调研），**原 token 不可恢复**，但重建容器的启动行 stdout 会打印新 token（docker logs 提取，dsh-pair.sh 同款 grep） | ✅ 可行（token 重生成语义） |
| 192.168.110.248:248 | **未运行**（无 :248 监听） | 宿主全清点无痕迹：~/.config/opencode/（service.json×2 均为 4199）/ opencode.jsonc 无 248 / 无 docker 容器与镜像线索 / bash·zsh history 无口令记录 / 无 systemd 单元 | ❌ **边界：不可探查**。需用户裁定：重启该服务并告知口令（届时 /proc 探针即可接管），或弃用该条目 |

## 2. 密钥链/其他面（完整性）

- Android 密钥链（SecretCipher/Keystore）：设备侧加密存储，宿主不可读——与「宿主探查」正交，app 侧已有（DSH cookie 365 天）。
- 桌面密钥链（secret-tool/gnome-keyring）：本机无 opencode 相关条目（headless 环境无 keyring daemon）。
- 环境变量：V2 serve 进程 environ 无 OPENCODE_SERVER_PASSWORD（口令从 service.json 读）；V1 未运行无 env 可读。

## 3. 自动输入通道（全部现成）

| 目标类型 | 通道 | 前提 |
|---|---|---|
| OpenCode V1/V2 | debug intent：am start --es debug_url http://127.0.0.1:<port> --es debug_username opencode --es debug_password <pw> --es debug_name <name>（前置 adb reverse tcp:<port>） | dev flavor（debug 构建） |
| DSH | dsh-pair.sh USB 通道（--es debug_server_type dsh --es debug_token <token>）或 LAN 深链 ocbeacon://pair?url=…&token=… | 同上；token 从启动行 stdout 提取 |

## 4. 交付物用法

scripts/cred-probe.sh：
- status —— 全目标探查报告（运行态/凭据源/可否注入）
- v1 [serial] —— V1-4198：确保运行（未运行则按 runbook 配方起服，口令落盘）→ reverse → 注入
- dsh012 [serial] —— dsh012-a5：确保容器（docker start/run 0.0.0.0:3081）→ docker logs 提取 token → reverse → 注入
- opencode <port> <name> [serial] —— 通用 OpenCode 面：/proc environ 口令探针 → 注入（服务须在运行）
- boundary-248 —— 打印 248 边界报告

## 5. 安全备注

- 落盘口令文件 600 权限；token=RCE 等价物仅经 adb/本机通道传递（#325 同款纪律）。
- /proc/<pid>/environ 仅同用户可读——探查面=自己拥有的进程，无提权。