# e2e/ — 七卡端到端验证套件（#540/#547/#549/#551/#554/#556/#557）

设计文档：[`docs/specs/2026-10-17-seven-card-e2e-verification-design.md`](../docs/specs/2026-10-17-seven-card-e2e-verification-design.md)

## 快速开始

```bash
# 列出卡片
python3 e2e/harness/runner.py list

# 模拟器（默认 emulator-5554）跑单卡
python3 e2e/harness/runner.py run --card 557 --device emulator-5554

# 全矩阵（产物在 e2e/runs/<时间戳>/，含 report.md/json）
python3 e2e/harness/runner.py run-all --device emulator-5554

# 真机（houji，IP 传输形态）
python3 e2e/harness/runner.py run --card 556_plain --device 192.168.1.110:41969

# 先装包再跑（默认使用设备上已装的 dev debug 包）
python3 e2e/harness/runner.py run --card 540 --apk app/build/outputs/apk/dev/debug/app-dev-debug.apk
```

前置：`adb` 在 `/home/leonardo/android-sdk/platform-tools`；dev debug 包已安装；
设备已解锁（`adb shell input keyevent 82`）。

## 装置组成

| 文件 | 职责 |
|------|------|
| `harness/mock_v2.py` | OpenCode V2 确定性流（plain/bracket/giant/rename/hold 五场景） |
| `harness/mock_dsh.py` | DSH 0.1.1 线面 mock（#540 DSH 腿：stream/tool/toolhold） |
| `harness/device.py` | adb / uiautomator / DataStore 设置 / 日志计数 |
| `harness/probes.py` | 生产探针解析与逐卡判据（card540/547/549/551/554/556/557） |
| `harness/runner.py` | 场景编排、产物落盘、报告 |
| `harness/prefs.py` | DataStore preferences_pb wire 级读写（`expand_reasoning` 等） |

## 场景与判定

| card id | 前置 | mock | 主判据 |
|---------|------|------|--------|
| `540` | expand_reasoning=OFF | plain 40×0.9s | 流中展开走 degrade；窗内增长帧全 set 配对、0 nodis；无窗后一次性回锚 |
| `540_dsh_stream` | expand_reasoning=OFF | DSH stream | 同上（DSH 0.1.1 双 WS 帧） |
| `540_dsh_tool` | expand_reasoning=OFF | DSH tool | tool/result 回填 completed 后展开工具卡不走降级（degrade=0） |
| `554` | OFF | plain 60×0.8s | 上滑阅读后收起，卡头顶缘 Δ≤10px；负向 release-ref 存在 |
| `551` | OFF | giant 40×0.3s（delta-only） | 二次展开 tgt=上一结算 contentH；窗内 fiso 极差 ≤1.15×视口。**被 #558 渲染循环阻塞，当前 INVALID** |
| `551_static` | OFF | giant 40×0.3s，全量到达后冻结再首展 | 同上；用于 #558 修复后验证「静态巨卡二次展开」 |
| `547` | ON | rename 24×0.7s | 同内容 key 跨宿主 id 存活；无静默塌缩/mapHit 掉落 |
| `549` | OFF | plain 220×0.25s | 三脸扫描 + FLASH/FIGHT/OSC/FATAL/windows 负值 |
| `556_plain` | ON | plain 10×0.6s | 流中 gate release>0 且正文可见 |
| `556_bracket` | ON | bracket 10×0.6s | 流中扣留、完结 terminal reveal 且终态可见 |
| `557` | ON | plain 24×1.2s + 8 行/6s 停顿 | win-close 后内容增长、reg=true、reveal h 跟随 |

## 产物

```
e2e/runs/20261017-222707/
├── report.md / report.json
├── 557/{logcat.txt,result.json,mock.log,dump-*.xml}
├── 556_plain/...
└── ...
```

`result.json` 的 `checks` 是判据行的原文，`pass` 为总判定；失败时先看
`check` 是 `INVALID:`（场景未成型，必须修装置）还是 `FAIL:`（判据违例，回填卡面）。

## 已知装置坑（历史事故沉淀）

1. **uiautomator 等 idle 卡死**：流式/巨卡组合期 `uiautomator dump` 会挂起并持
   UiAutomation 注册，后续 dump 报 `already registered`。对策：巨卡场景不用 dump，
   以 `degrade toggle`/`pairing-window` 日志计数做命中反馈；dump 失败时
   `killall uiautomator` 并等待，仍失败则重启模拟器。
2. **reverseLayout 方向**：手指下滑=看更早内容（内容下移）；装置注释以此为准。
3. **流式 auto-follow**：滚动后每次 dump 期间流式追加会把视口拉回底部。对策：
   定位前 `POST /api/_pause?ms=...` 冻结 mock 流（turn 不结束，降级路径保持）。
4. **IME**：输入后 `keyevent 4` 收键盘，再按当前 dump 的 `发送` bounds 点击；
   不要用固定底部坐标（键盘把发送键顶起）。
5. **会话标题唯一**：每次 run 用 `E2E-<card>-<HHMMSS>`，避免 mock 重启后旧行混入。
