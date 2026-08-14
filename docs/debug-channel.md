# 调试通道（Debug Channel）— #132

> 通过 **adb 外部参数**传入服务器地址/账号/密码，App 启动后自动保存（幂等）、
> 连接并**直达会话列表**。仅 **debug 构建**可用（dev flavor + 所有 debug buildType）；
> release 构建完全不含（BuildConfig.DEBUG 守卫）。

## 用法（adb 命令）

```bash
adb shell am start -n dev.leonardo.ocbeacon.dev/dev.leonardo.ocbeacon.MainActivity \
  --es debug_url http://192.168.110.53:4199 \
  --es debug_username opencode \
  --es debug_password '<password>' \
  --es debug_name 'V2 Real'     # 可选；建议英文（中文经 adb am 解析有歧义）
```

### 参数说明

| extra | 必填 | 默认值 | 说明 |
|-------|------|--------|------|
| `debug_url` | ✅ | - | 服务器地址，如 http://192.168.110.53:4199 |
| `debug_username` | 否 | opencode | 认证用户名 |
| `debug_password` | 否 | 空 | 认证密码；为空时幂等复用已有服务器密码（不覆盖） |
| `debug_name` | 否 | Debug External | 服务器显示名（建议英文且**不含空格**——adb am 会按空格拆分参数，如 `V2_Real`） |

### 冷启动 vs 热启动

- **App 未运行**：直接执行命令即可（冷启动，onCreate 处理）
- **App 已在运行**：同样执行命令（onNewIntent 处理）

## 行为

1. **幂等保存**：同后端（url+username 归一化）已存在 → 复用并更新凭据；否则新建
   （autoConnect=true）
2. **版本探测**：自动探测 V1/V2 API 版本并修正 apiVersion（避免 V1 路径请求 V2 服务器）
3. **连接 + 直达**：启动连接服务 → 直达该服务器**会话列表**

## 示例

```bash
# V2 真机
adb shell am start -n dev.leonardo.ocbeacon.dev/dev.leonardo.ocbeacon.MainActivity \
  --es debug_url http://192.168.110.53:4199 --es debug_username opencode \
  --es debug_password leo12321 --es debug_name 'V2 Real'

# V1 模拟器（10.0.2.2 = 模拟器访问宿主机）
adb shell am start -n dev.leonardo.ocbeacon.dev/dev.leonardo.ocbeacon.MainActivity \
  --es debug_url http://10.0.2.2:4096 --es debug_password leo12321 --es debug_name 'V1 Emu'
```

## 代码路径

- 模型：`domain/model/DebugProfile.kt`
- 解析与激活：`MainActivity.handleDebugProfileIntent` / `MainActivity.activateDebugProfile`
- 导航：`NavGraph`（debugChannelFlow 收集 → SessionList）
