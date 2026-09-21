# prerender 红环仪器集(#423 P0)

> spec: docs/specs/2026-09-21-pre-render-coordinator-design.md §5 验证矩阵。
> 本目录把 #420-#422 十一轮收口沉淀的 /tmp 一次性脚本固化为可复跑资产;
> PreRenderCoordinator 每个 Phase 迁移前后必须复跑对应环并对比。

## 环 → 脚本对照

| 环 | 验证 | 脚本 | 判定 |
|---|---|---|---|
| 1 位置不变量 | 展开项头部 ∞ 图标条逐帧 dy | `icon_track.py` | 置信帧 abs(dy)≥8 = RED-DRIFT |
| 2 同帧闭合 | 墨水(内容暗像素)单调性 | `flick_track.py` + logcat 账本残差断言 | FLICKER_FRAMES 非空 = RED |
| 3 贴底武装态 | 展开全程守卫让位 | logcat grep GUARD/MSGEFFECT(ChatScrollController) | episode 期间出现 reanchor 行 = RED |
| 4 守恒 | 折叠行/参照条带绝对Y | `strip_track.py` | 峰值≥8px 后回稳 = RED(瞬态漂移) |
| 5 取消 | 动画中 fling → snap 落位 | 手动+`icon_track.py` | 无回拽 |
| 6 连击 | 短时连点思考+过程 | 脚本化 tap + `detect_state.py` 终态 | 终态正确、无战争日志 |
| 7 行为基线 | spec §4 七条冻结项回放 | `detect_state.py`/`dumprows.py` + journal | 与 07-baseline-freeze.md 一致 |
| 8 性能 | gfxinfo 帧时间 | `adb shell dumpsys gfxinfo <pkg>` | 迁移前后对比不劣化 |

## 标准流程

```bash
export ANDROID_ADB_SERVER_PORT=5038
ADB='adb -s 192.168.110.239:35087'   # 无线 serial,以当下连接为准
# 1) 拿 uiautomator dump 定位目标
$ADB shell uiautomator dump /sdcard/d.xml && $ADB pull /sdcard/d.xml /tmp/d.xml
python3 scripts/prerender/findthink.py /tmp/d.xml      # → tapy
python3 scripts/prerender/dumprows.py /tmp/d.xml       # → 选锚点条带
# 2) 录屏(capture.sh 内部裸 adb,先用 adb -s 或 alias;另终端驱动 tap)
# 3) 分析
python3 scripts/prerender/icon_track.py /tmp/case1 <图标y>
python3 scripts/prerender/strip_track.py /tmp/case1 <折叠行y> [参照y]
python3 scripts/prerender/flick_track.py /tmp/case1 <头部y>
```

## 仪器纪律(踩坑沉淀,违反=假证据)

1. **全窗口分析**:视觉窗口截断会切掉关键帧(#426 教训)——录制必须覆盖动画全程+2s。
2. **稳定像素特征**:锚点用 ∞ 图标(不受摘要淡入影响);条带匹配 d≥11 视为伪迹(按压高亮),不作漂移证据。
3. **状态双判据**:`detect_state.py` 用「展开内容标记+折叠行全高」,禁用 sliver 正则(曾把半展开误判为已折叠)。
4. **uiautomator 坐标时效**:dump 后立即 tap;页面变化后必须重新 dump(陈旧坐标是惯犯)。
5. **中屏探测**:锚点条带必须在视口中段(顶部/底部条带受系统栏干扰)。
6. **logcat 时机**:loop 3 需在 tap 前开始捕获,tap 后 5s 停,避免相邻操作污染。
