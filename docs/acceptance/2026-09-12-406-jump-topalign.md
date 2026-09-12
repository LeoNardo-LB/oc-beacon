# #406 跳转落点复核：快速定位与关键词命中点击均为顶对齐（2026-09-12）

- 关联：#406（本卡）/ #394（跳转高亮）/ JumpNavigationController
- 用户 2026-09-12 裁决：**维持顶对齐**（不做居中），并要求「快速定位」与「关键词跳转点击」两条路径都保持顶对齐。
- 设备：emulator-5554（1080x2400 @420dpi zh-CN），APK md5 `d05e712f8510ef4e27c1530e76810fd8`；V1 `127.0.0.1:4198`；靶会话 `v1-sheet-test`。
- 证据目录：`docs/acceptance/2026-09-12-406-jump-topalign/`

## 结论：PASS（两条路径落点一致、均顶对齐）

| 路径 | 操作 | 落点（目标消息） | 其上方是否还有更早消息 | 判定 |
|---|---|---|---|---|
| 快速定位 | 抽屉内滚动出 Q1 → tap 第 1 轮行 | 「用户」头 top=333、正文 top=396（= 视口第一项） | 无 | PASS |
| 关键词命中 | 会话列表搜索 `charlie` → 「消息匹配」组内 tap 第 1 轮命中行 | 「用户」头 top=333、正文 top=396（与快速定位**逐像素一致**） | 无 | PASS |

- 「顶对齐」判据：目标消息是视口内第一条消息（其上方不存在任何更早消息）；内容区顶边 ≈333（顶栏下方）。
- 两条路径落点完全相同 → 关键词命中与快速定位共用同一 `JumpNavigationController.measureAndSettle` 收敛路径。
- 近列表末端的目标（下方内容不足一屏）会走**夹持收场**停在低位——这是物理不可达（顶对齐需要目标下方至少一屏），属设计内，非回归（详见 journal #406 侦查）。

## 机制（现状，不改动）

`JumpNavigationController`：初始按 reverseLayout 底部摆放 → 每 100ms 小步 `scrollBy(≤vh/2)` 驱动 `computeGap→0`（gap=0 ⇔ 目标顶边贴视口顶）→ `|gap|≤2` 判 settled；900ms 稳定窗口修 `|gap|>8`。JNC 落点语义自 2026-08-21 起未变更，服务层重构（#391 家族）未触及。

## 证据文件

- `session_list_before_search.xml`：会话列表搜索入口
- `q1_sheet_scrolled.xml`：抽屉滚动出 Q1 行（tap 前）
- `quicknav_q1_after.xml`：快速定位跳转后（第 1 轮 top=396、用户头 333）
- `keyword_charlie_results.xml`：关键词 `charlie` 的消息匹配结果（组内第 1 轮命中行 top=747）
- `keyword_q1_after.xml`：关键词命中点击后（第 1 轮 top=396、用户头 333）

## 未做与限制

- 注入 tap 非真手指；未测流式进行中跳转与近端夹持路径的观感（#406 侦查已给 logcat 判据）；
- 输入用 `adb input text`（IME 对含连字符字符串有干扰，故用 `charlie` 纯字母）。