# Android UI 元素精确抓取手册（E2E 操作指南）

> 供 AI / 自动化在真机上**精确定位、判定、操作** UI 元素。2026-08-23 定稿。
> 依据：调研报告（docs/research/2026-08-23-android-ui-probing-tools.md，含 8 工具评估与源码级引用）+ #192 E2E 实战（5 个根因排查全程实证）。
> 配套：docs/real-device-testing.md（装包/连通/intent）、docs/observability-verification-guide.md（Logcat）。

## 0. 三层路由（先读这个）

| 判定类型 | 首选通道 | 兜底 |
|---|---|---|
| 文本/描述可定位元素（按钮、行、输入框） | uiautomator dump（text/content-desc + bounds） | Maestro |
| FAB / 自绘小控件（dump 失明区） | **像素探针**（颜色锚点）→ 功能探测（tap 后看副作用） | vision 模型（仅仲裁） |
| 布局/观感/颜色/图标形态 | vision 模型（描述性提问） | 截图 diff 像素统计 |
| 等待/轮询密集流程 | Maestro flow（7s/17s 自动等待） | sleep+dump 门卫循环 |

**铁律：单一通道不做最终判定。** dump 说没有 ≠ 屏幕上没有（Compose FAB 实证全失明）；vision 说有 ≠ 真有（小圆钮 ~50% 幻觉率，含引导性提问时更高）。

## 1. uiautomator dump 健壮化配方

```bash
adb -s <serial> shell rm -f /sdcard/ui.xml          # 旧文件残留是最大坑（失败时 dump 命令不报错）
adb -s <serial> shell uiautomator dump /sdcard/ui.xml
adb -s <serial> shell cat /sdcard/ui.xml > /tmp/ui.xml
grep -o 'text="目标"[^>]*bounds="\[[0-9]*,[0-9]*\]\[[0-9]*,[0-9]*\]"' /tmp/ui.xml | grep -o '\[[0-9]*,[0-9]*\]\[[0-9]*,[0-9]*\]' | head -1
```

- **bounds 校验必做**：Popup/离屏节点会给出 y>2670 之类的坐标（实证 y=776713）。tap 前判 `0<y<屏高`。
- 一行文本可能出现多次（列表行 + Popup），取 bounds 在视口内的第一个。
- Compose 已全局开 `testTagsAsResourceId`：新增 UI 加 `Modifier.testTag("xx")` 后 dump 里就是 resource-id（每控件 1 行成本）。
- **已知失明区**（本仓实证）：FloatingActionButton/ToggleFloatingActionButton 收起态、FAB 菜单展开药丸、部分自绘控件。**自定义 semantics（contentDescription）的组件可见**——#192 边缘拉杆（BadgedBox+semantics）在 dump 有 bounds。
- Android 12+ 可试 `--windows` 多窗口；必要时 `dumpsys accessibility` 交叉验证。

## 2. 像素探针（dump 失明区的硬证据）

Python 纯标准库 PNG 解码（免 ImageMagick；脚本在手册附录或 /tmp/probe.py 模式）：探针点颜色 + 区域色彩统计（top-colors 直方图）。判据：

- **颜色锚点**：先在「确定在场」状态采一次基准色（如 FAB 容器 #394d56 / 图标 #5b87c3），之后同点探针对比变化 = 状态翻转的硬证据。
- 区域扫描看 top-colors 分布是否突变（隐藏/出现）。
- 暗色主题下别用「亮度」启发式（secondaryContainer 暗蓝与背景亮度接近，曾因此误判「FAB 不渲染」3 轮）。

## 3. vision 模型使用纪律

- 只做**描述性/仲裁性**提问（「这是什么屏」「展开菜单是否有药丸堆栈」）。
- **禁止**依赖它给小目标的坐标（<10% 屏幕面积的圆钮幻觉率高）；坐标一律 dump bounds 或像素探针。
- 提问要具体（区域+特征），不给引导性预期（「应该有个按钮吧」→ 必幻觉）。
- 并发调用会静默失败——串行使用。

## 4. 手势注入（input swipe/tap）已知坑

| 坑 | 对策 |
|---|---|
| **reverseLayout 列表**（本仓聊天列表）翻旧消息 = **下滑**（1000→2000），上滑是回底 | 看不到滚动效果先换方向 |
| 慢速 input swipe（>400ms/短距离）输给触摸 slop 方向竞争（拖拽类手势吃不到 delta，实证 50px 只到 2.4px） | 手势测试用**快滑 100-250ms** |
| MIUI 返回手势区 ~24dp：拖拽终点落在区内被系统截断（手势 cancel、无 settle） | 终点留在离缘 ≥28dp |
| tap 命中判定要「tap→dump/像素→验证副作用」，坐标 tap 本身无回执 | 门卫式每步验证 |
| adb input text 纯 ASCII 可靠（冒号/斜杠正常）；标点 >;/: 在部分输入法丢失 | 命令组合用 base64 或分多段 |

## 5. 构建/签名速查（本仓）

- 装机 flavor=devRelease，本地 keystore 8fbc136e（**debug 构建签名不匹配装不上**，INSTALL_FAILED_UPDATE_INCOMPATIBLE 被 `| tail -1` 吞过——install 必看 exit code + lastUpdateTime）。
- release 构建里 `android.util.Log.d` **不被 R8 剥离**（实证 FAB192 探针日志可见）——排查 UI 可临时加 Log 探针（记得移除）。
- `onGloballyPositioned { Log }` 是定位「组件在哪/是否组合」的最强探针（positionInRoot+size）。

## 6. Maestro（等待/断言密集场景）

- devRelease 非 debuggable **完全可用**（其 instrumentation 用自家 dev.mobile.maestro APK，与目标包解耦——源码级核验过）。
- 与 dump 同一棵 a11y 树（同样受失明区限制）+ 自动等待 7s/17s + assertScreenshot(cropOn)。
- 现有 flow 在 maestro/。新 flow 优先用于「导航链长、中间态多」的场景。

## 7. 决策树速查

```
元素操作目标
├─ dump 里有（text/desc/id）→ bounds 校验 → tap → 门卫验证
├─ dump 里没有 → 像素基准色对比 → 在场？
│   ├─ 是 → 坐标 tap/快滑 → 副作用验证（dump 变化/像素翻转/logcat）
│   └─ 否 → 截图 + vision 描述性提问 → 仍不确定 → 加 Log/onGloballyPositioned 探针重建
└─ 需要等待 → Maestro flow 或 dump 轮询（≤5 次退避）
```

## 附录：实战案例索引

- #192 双 FAB 滑动隐藏 E2E（本手册全部条款的实证来源）：docs/journal/2026-08-23-acceptance-closeout.md §六
- 调研全文（8 工具评分矩阵、a11y 失明 8 类根因、源码引用）：docs/research/2026-08-23-android-ui-probing-tools.md
