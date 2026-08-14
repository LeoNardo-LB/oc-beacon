# 模拟器性能验证方案（#97/#95/#98/#115 量化观测）

> 目的：为已修复的性能类 issue 提供**可量化证据**（GC 计数/分配量/内存占用/事件落盘写量），
> 而非仅"正确性验证"。执行环境：模拟器 emulator-5554（API 36）+ V2 服务器 10.0.2.2:4199。

## 1. SSE 流式 GC/分配量观测（#97 H-5+M-6+M-15+H-6）

### 原理
- H-5：ByteArray 管线消除了逐字节装箱（List<Byte> → ByteArrayOutputStream）
- M-6：prettyPrint 关闭 → JSON 体积 -30-50% → 编码分配减少
- H-6：增量落盘 → Room 写量从"整条消息"降为"delta 文本"

### 观测方法（logcat GC 统计）
```bash
# 1. 触发一段长流式输出（模拟器上发送消息，内容约 500-1000 token）
# 2. 期间抓 GC 日志（Android Runtime 会打印 GC 事件）
adb -s emulator-5554 logcat -c
adb -s emulator-5554 logcat | grep -E "GC_|GcCause|dalvik" > /tmp/gc_streaming.log

# 3. 流式结束后统计 GC 次数与分配量（API 24+ 的 GC 日志格式）
grep -c "GC_" /tmp/gc_streaming.log
grep "GC_" /tmp/gc_streaming.log | grep -oE "freed [0-9]+K" | awk -F" " "{s+=\$2} END {print s "K freed"}"
```

### 基线对照
- 修复前基线：需要从 git 历史构建（ddfc683c 之前的父 commit）跑同场景
- 修复后：当前 master
- 对比指标：流式期间 GC 事件数、累计 freed 字节、卡顿（GC 暂停 ms）

### 预期结论
- GC 事件数下降（分配减少）
- 流式更平滑（无长 GC 暂停）

## 2. 长会话内存占用观测（#95 热视图上限 + #98 无界容器）

### 原理
- #95：每会话内存热视图上限 1000 条（Room 对齐）
- #98：ToolSnapshotCache LRU 200 / pendingInputs 有界 / 注册表 onDispose 清理

### 观测方法（dump 内存 + dumpsys）
```bash
# 1. 构建 2000+ 条消息的长会话（脚本批量发送或加载已存在长会话）
# 2. 观察内存稳定上限
adb -s emulator-5554 shell dumpsys meminfo dev.leonardo.ocbeacon.dev | grep -E "TOTAL|Java Heap|Native"

# 3. 反复滚动聊天（触发 mdRegistry/readiness 注册表增删）
adb -s emulator-5554 shell input swipe 500 1500 500 500 200
# 4. 再次 dump 对比——注册表清理后不应增长
```

### 预期结论
- 长会话内存不再随消息数线性增长（稳定在 1000 条热视图 + 存档）
- 滚动后注册表条目数稳定（无累积）

## 3. onTrimMemory 触发与清理观测（#115 D2-16）

### 原理
- OpenCodeApp.onTrimMemory 在 RUNNING_LOW/UI_HIDDEN 级别清理 ToolSnapshotCache

### 观测方法（logcat）
```bash
# 1. 打开 FileViewer 加载大文件（填充 ToolSnapshotCache）
# 2. 模拟低内存（设备压力）：
adb -s emulator-5554 shell am send-trim-memory dev.leonardo.ocbeacon.dev RUNNING_LOW

# 3. 验证清理日志
adb -s emulator-5554 logcat -d | grep "onTrimMemory"
# 预期：onTrimMemory level=... - cleared ToolSnapshotCache
```

## 4. 执行记录

| 日期 | 场景 | 观测项 | 结果 | 结论 |
|------|------|--------|------|------|
| - | - | - | - | - |
