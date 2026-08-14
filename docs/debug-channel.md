# 调试通道（Debug Channel）— #132

> 预置服务器连接套餐，一键直达会话列表。仅 **debug 构建**可用（dev flavor + 所有
> debug buildType）；release 构建完全不含（BuildConfig.DEBUG 守卫，profiles 恒空，
> 密码字段为空字符串）。

## 入口

### 1. Home 页入口卡片（UI 方式）

debug 构建下，Home 页（服务器列表底部）显示 **"Debug Channel (dev)"** 卡片：

1. 点入口卡片 → 套餐列表对话框
2. 点套餐 → 自动完成：幂等保存服务器（同后端复用）→ 连接 → **直达会话列表**

### 2. 外部参数启动直达（脚本方式）

```bash
# 冷启动直达（App 未运行）
adb shell am start -n dev.leonardo.ocbeacon.dev/dev.leonardo.ocbeacon.MainActivity \
  --es debug_profile v1real

# 热启动（App 已在运行，onNewIntent 处理）
adb shell am start -n dev.leonardo.ocbeacon.dev/dev.leonardo.ocbeacon.MainActivity \
  --es debug_profile v2real
```

## 套餐

| id | label | url | 说明 |
|----|-------|-----|------|
| v1real | V1 Real | http://192.168.110.53:4096 | V1 真机（opencode 1.18.x） |
| v2real | V2 Real | http://192.168.110.53:4199 | V2 真机（opencode 2.x） |
| v1emu | V1 Emulator | http://10.0.2.2:4096 | V1 模拟器 |
| v2emu | V2 Emulator | http://10.0.2.2:4199 | V2 模拟器 |

## 密码注入

套餐密码**不硬编码**在源码，经 buildConfigField 从环境变量注入（仅 debug 构建有值）：

```bash
export OCB_DEBUG_PWD='<password>'   # 构建前设置
./gradlew :app:assembleDevDebug
```

未设置时密码为空字符串（连接会失败，UI 通过 connectionErrors 呈现，不崩溃）。

## 扩展套餐

编辑 app/src/main/kotlin/dev/leonardo/ocbeacon/debug/DebugChannel.kt
的 builtinProfiles()，追加 DebugProfile(id, label, url, username, password) 即可。

## 代码路径

- 模型：domain/model/DebugProfile.kt
- 套餐：debug/DebugChannel.kt
- UI：ui/screens/home/components/DebugChannelComponents.kt + HomeScreen.kt
- 激活：HomeViewModel.activateDebugProfile / MainActivity.handleDebugProfileIntent
- 导航：NavGraph（debugChannelFlow 收集 → SessionList）
