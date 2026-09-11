# #399 插槽迁移复验（钉底任务卡经 CHAT_MESSAGE_LIST 渲染）

- 卡片：backlog #399（通用壳 DshJobTimelineCard → 类型私有包 DshJobTimelineExtension，经新插槽 CHAT_MESSAGE_LIST 渲染，EventCard 同参同视觉）
- 日期：2026-09-12
- 复验员：#399 插槽迁移复验员（clean context，只读 + 设备操作；未改产品代码、未跑 Gradle）
- 设备：emulator-5554（Android 16 / sdk_gphone64_x86_64），设备独占
- 构建：devDebug，源码 HEAD `a3dc93ce`（"refactor(ui): #399 钉底任务时间线迁 CHAT_MESSAGE_LIST 插槽"）
  - APK：`app/build/outputs/apk/dev/debug/app-dev-debug.apk`
  - 本机 APK md5 = `75e89ad2cb15790ad28f04d640dcc6d8`（与要求一致）
  - 已装 APK md5 = `75e89ad2cb15790ad28f04d640dcc6d8`（`adb shell md5sum <base.apk>` 校验一致）
  - APK 确实含 #399 代码：21 个 dex 中命中 `DshJobTimelineExtension` 26 次 / `ChatMessageListSlotHost` 4 次 / `ServerUiSlot` 91 次
- 服务器：DSH `http://127.0.0.1:3080`（adb reverse），token 读 `~/.dsh/token`
- 会话：标题「多服务器适配重构」= `session-94365bc9-728e-4684-9128-02db5b589745`（oc-beacon 工作区）

## 前置（已完成）

```bash
adb -s emulator-5554 install -r app/build/outputs/apk/dev/debug/app-dev-debug.apk   # Success，未卸载
adb -s emulator-5554 reverse tcp:3080 tcp:3080
adb -s emulator-5554 shell getprop persist.sys.locale                                # zh-CN
adb -s emulator-5554 shell am force-stop dev.leonardo.ocbeacon.dev
adb -s emulator-5554 shell am start -n dev.leonardo.ocbeacon.dev/dev.leonardo.ocbeacon.MainActivity \
  --es debug_url http://127.0.0.1:3080 --es debug_name E2E-DSH --es debug_server_type dsh \
  --es debug_token <~/.dsh/token>
```

- token 交换：`MainActivity: debug_token exchange for http://127.0.0.1:3080: ok` → 会话列表加载，首项即目标会话。

## 服务端真相（会话确有 jobs）

不依赖 App，直接用 token 换 cookie 后连 `ws://127.0.0.1:3080/api/remote.mux`，开 `session/control` 取 baseline（证据 `mux-control-baseline.txt` / `mux-baseline-fresh.txt`）：

```
jobs["session-94365bc9-728e-4684-9128-02db5b589745"] = [
  {id:"bash-1", kind:"bash", label:"emulator -avd Pixel6_Android36 ... 2>&1", status:"completed", detail:"exit code: 127"},
  {id:"bash-2", kind:"bash", label:"/home/linuxbrew/.linuxbrew/share/android-commandlinetools/emulator/emulator -avd Pixel6_Android36 ...", status:"running"},
  {id:"bash-6", kind:"bash", label:"sleep 900", status:"running", ...}
]
```

- 宿主机 `ps` 实证存在 `sleep 900`（pid 648174，ppid 974）。**复验期间该 sleep 自然结束**（约 01:13:20），随后服务端推送 jobs 增量（`bash-6 → completed`）；`bash-2` 仍 running。
- 时序：App 每次进入会话收到多次 `EventDispatcher: [dispatch] JobsSnapshot -> DshJobsHandler sid=session-9436…`（证据 `logcat-jobs.txt`）。

## 复验结果

| # | 项 | 结论 | 证据 |
|---|----|------|------|
| 1 | 进入会话、转录加载、回到底部 | ✔ | 转录非空（103 msgs；压缩卡/Q20–Q29 等均可读）；「滚动到底部」按钮点击后消失 = 真·底部 |
| 2 | dump 查找后台任务卡 | ✔（推送后） | `dump-after-jobs-push.xml`：3 张任务卡，见下表 |
| 3 | 任务卡仍渲染 + 统一 EventCard + 无空白/无错位 | **PASS** | 标签行 + 时间 + 描述行 `kind · label · detail`；间距规整、无重叠 |
| 4 | 转录非空 / 无崩溃 | ✔ | `logcat -d -b crash | grep -i ocbeacon` 无输出；main 无 FATAL |
| 5 | 通用壳不再引用 Dsh 卡片符号 | ✔（静态） | `components/` 无 `import …\.dsh\.`；ChatMessageList 私有 `DshJobTimelineCard`/状态标签函数已删（见 `a3dc93ce` diff） |

### 任务卡节点文本与 bounds（`dump-after-jobs-push.xml`）

| 状态标签 | 时间 | 描述行（kind · label · detail） | 标签 bounds | 描述 bounds |
|---------|------|--------------------------------|-------------|-------------|
| 完成 | 00:58:44 | `bash · sleep 900 · exit code: 0` | [98,1544][153,1581] | [74,1607][1006,1641] |
| 运行中 | 2026-09-11 09:22:24 | `bash · /home/linuxbrew/.linuxbrew/share/android-commandlinetools/emulator/emulator -avd Pixel6_Android36 -no-window -no-audio -no-boot-anim -gpu swiftshader_indirect -no-snapshot 2>&1` | [98,1736][180,1773] | [74,1799][1006,1833] |
| 完成 | 2026-09-11 09:20:15 | `bash · emulator -avd Pixel6_Android36 … · exit code: 127` | [98,1928][153,1965] | [74,1991][954,2025] |

- 底部工具行 y≈2132、输入栏 y≈2232——任务卡位于输入栏上方（reverseLayout 先声明 = 视觉底部），顺序 = 快照序（bash-1 最底 → bash-2 → bash-6 最上），与 `DshJobTimelineExtension` 声明一致。
- 卡片外观 = 统一 EventCard：状态标签（`dsh_job_status_*` 文案）+ 时间 + 描述行 `kind · label · detail`；与既有 shell/压缩/注入卡同款（同屏压缩卡亦为同族卡，无错位）。

## BLOCKED/注意：冷进入的「暂缺」现象（非 #399 渲染缺陷）

- **观察**：冷启动 → debug intent → 点入该会话后，底部在 ≤30s 内**看不到任务卡**（`grep -c 'bash ·'`=0）；仅当服务端后续推送一次 jobs 增量（本次为 `sleep 900` 结束）后，任务卡才出现在底部（`dump-after-jobs-push.xml`，01:13:43 推送到后 dump）。复现：重新进入会话，t+6s/t+12s/t+20s 均无任务卡（`dump-cold-entry-bottom.xml`/`dump-fresh-bottom.xml`/`dump-bottom-2.xml`）。
- **推断根因（代码路径明确，未读 App 运行时内存）**：`DshEventMapper.mapFrameInner` 的 `session/subscribed` 分支会发出一条 **空** `SseEvent.JobsSnapshot`（`DshEventMapper.kt:118`），`DshJobsStore.applySnapshot` 空集删键；而控制流 baseline（含非空 jobs）在 WS `onOpen` 先于 follow（`DshRemoteMuxEngine.kt:195-206`），即「baseline 非空 → subscribed 清空 → 服务端仅在任务状态变化时再推」→ 会话停留在无 job 状态，直到下一次 jobs 推送。
- **归属**：#399 diff（8 文件）仅做插槽迁移，未触碰 `session/subscribed` 清空或 `DshJobsStore`/控制流时序；该现象为**既有行为**（与 #399 无关），但它使「进入会话即可见钉底卡」这一体验在无 jobs 变化时不成立。
- **建议**：另立卡片复验「进入会话后钉底任务卡应立即可见」（考虑 subscribed 后请求/重放 session/control jobs，或 baseline 与 subscribed 时序治理）。**不属于 #399 插槽迁移的判定范围。**

## 判定

- **#399 插槽迁移渲染面：PASS**——钉底任务卡经 `CHAT_MESSAGE_LIST` 插槽正常渲染，外观为统一 EventCard（标签 + `kind · label · detail`），位置正确、无空白/错位；转录、压缩卡、输入栏正常；无崩溃。
- **接线佐证**：#399 提交的契约测试（`ServerAdapterContractTest` 纳入 CHAT_MESSAGE_LIST 声明 + `DshJobTimelineExtension` 覆盖）；本次 UI 实证补充「真机渲染」证据。
- **BLOCKED 项（非 #399）**：冷进入会话时任务卡暂缺（数据源在 `subscribed` 后被清空、无即时重推），详见上节；已附 dump + logcat，不臆造。

## 证据文件

- `docs/acceptance/2026-09-12-399/dump-after-jobs-push.xml`（正证据：任务卡渲染）
- `docs/acceptance/2026-09-12-399/dump-cold-entry-bottom.xml`、`dump-fresh-bottom.xml`、`dump-bottom-2.xml`（冷进入无卡）
- `docs/acceptance/2026-09-12-399/mux-control-baseline.txt`、`mux-baseline-fresh.txt`（服务端 jobs baseline）
- `docs/acceptance/2026-09-12-399/logcat-jobs.txt`（debug_token exchange + JobsSnapshot 派发）
- `docs/acceptance/2026-09-12-399/screen-bottom-2.png`（冷进入底部截图）

## 复跑命令

```bash
adb -s emulator-5554 shell am force-stop dev.leonardo.ocbeacon.dev
adb -s emulator-5554 shell am start -n dev.leonardo.ocbeacon.dev/dev.leonardo.ocbeacon.MainActivity \
  --es debug_url http://127.0.0.1:3080 --es debug_name E2E-DSH --es debug_server_type dsh \
  --es debug_token "$(cat ~/.dsh/token)"
# 点会话「多服务器适配重构」→ 若底部无卡，等待/触发一次 session/jobs 推送后再 dump 底部
adb -s emulator-5554 shell uiautomator dump /sdcard/x.xml && adb -s emulator-5554 exec-out cat /sdcard/x.xml | grep -E 'bash ·|运行中|完成'
```
