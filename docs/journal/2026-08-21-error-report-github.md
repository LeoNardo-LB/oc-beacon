# 2026-08-21 错误日志 GitHub 上报批次
> 状态：部分完结（活跃 #151 #152 #153 #154）
> 迁移：2026-08-20 自 backlog.md 原文迁入（spec/journal 分离批次；原文逐字保留，未压缩）
> 关联：spec: docs/specs/2026-08-21-error-report-github-design.md

来源：grilling 会话共识（Q1–Q19 全定案）+ 日志分级审计。**设计 spec：`docs/specs/2026-08-21-error-report-github-design.md`（实现前必读，含全部实现决策与测试缝）**。

- [ ] **#151 错误日志 GitHub 上报（手动触发 + 指纹查重 + 重复评论）** `ui` `data`
  - 需求：Diagnostics 屏内把 ERROR/FATAL 上报到 `LeoNardo-LB/oc-beacon` 的 GitHub issue；已报过的错误不新建 issue 而是追加环境差异评论；强制预览可编辑；GitHub App device flow 授权（一次授权永久有效）
  - 实现：spec §Implementation Decisions（认证/指纹双轨/查重/防刷/失败处理全定案）· 测试缝：错误上报服务边界（fake GitHub 客户端）+ GitHub API 客户端（Ktor MockEngine，先例 V1/V2 API 测试）
  - 前置依赖：#152（上报质量前置）、#153（混淆堆栈可还原）、维护者注册 GitHub App（spec §Further Notes 有操作清单）

- [ ] **#152 前置：日志分级修复（SSE 灌水/双日志/丢堆栈，审计 15+ 处）** `sse` `refactor`
  - 问题：审计实证（2026-08-21，583 调用点）——SSE 重连风暴每断连灌 5-8 条/分钟（DROP_OLDEST 队列挤出真实错误）、同一失败双日志（SseConnectionManager:337+382）、per-event INFO 遗漏网（SessionEventHandler:107 等，#40 残留）、7 处 `e` 缺 throwable、WebView 子资源 404 记 e、遗留诊断标签（ActionModeDebug）
  - 方案：重连级联降级（i→d）+ 去双日志 + per-event 补 DEBUG 门控 + 补 throwable + 子资源门控；完整 file:line 清单见审计报告（会话 2026-08-21）
  - 依赖关系：#151 的"最近 20 条错误"在灌水修复前会被重连噪音填满——本条是其前置

  - **#153 实现记录（2026-08-21 cfeae170→cfeae270）**：release.yml 增 Upload R8 mapping 步骤（ocbeacon-mapping-<ver>，90 天保留，if-no-files-found: error——若 minify 意外关闭构建会产出 mapping 缺失即 CI 失败，双重守卫）；本地 outputs/mapping/devRelease/mapping.txt 实证 AGP 路径约定。验收：下次发版 tag 的 Actions 页应见 mapping artifact。


## 附：Diagnostics 分享崩溃修复（2026-08-21，用户报告当日修复）

用户实测报告：诊断页右上角分享按钮即崩。crash 取证：`IllegalArgumentException: Failed to find configured root that contains /data/.../cache/diagnostics/diagnostics.txt`——FileProvider 的 file_paths.xml 只声明了 `cache-path updates/`（更新检查 APK 用），诊断分享写的 `cache/diagnostics/` 无对应 root。根因修复：补 `cache-path diagnostics/` 声明（非 try-catch 吞异常）。真机复验：分享面板正常弹出（diagnostics.txt + 系统目标列表），crash 0。

## #151 GitHub 错误上报实现记录（2026-08-21 代码完成，功能待 GitHub App 注册后激活）

### 提交链（模块化）

| 模块 | commit | 内容 |
|------|--------|------|
| 1-3 数据层 | a68263b5 | GitHubDeviceFlowAuth（device code 请求/轮询 pending-slow_down 语义）+ GitHubTokenStore（SecretCipher AES/GCM v1: 密文 + install-id UUID）+ GitHubApiClient（search-by-fingerprint POST / create issue / comment，401→Unauthorized / 403→RateLimited 映射）+ ErrorReportService（双轨指纹归一化 HEX/N/PATH/STR、查重编排 24h 防刷、machine block、20+3 日志段）；BuildConfig client_id/secret 占位（空串=禁用态） |
| 测试 | 8a194bfb | 双缝 13 条全绿：服务边界 7（指纹等价性/命中→评论/未命中→建+前缀+label/24h 抑制 exactly=1/限流降级/日志段计数/机器块）+ MockEngine 6（search 命中/零、401/403 映射、create 解析、comment 201） |
| 4 UI | 241b8507 + bec73103 + 6b623f51 | DiagnosticsViewModel 状态机（授权编排/预览构建复用脱敏管道/提交/401 重授权引导/草稿保留重试）+ 菜单项与六分支对话框 + 11 文案 × 15 语言（i18n-check 656 键一致；tr 撇号 aapt 转义）+ Hilt @ApplicationContext |

### 验证证据

- **自动化**：全量 **1826/1826 绿**（+14 GitHub 测试）；compile 绿；i18n-check PASS
- **真机（houji）**：Diagnostics → 溢出菜单「举报到 GitHub」→ **NeedsGitHubAppConfig 对话框正确渲染**（「举报功能尚未配置（应用凭据待填入）」）——占位凭据的正确降级态；crash 0
- **激活前置（维护者操作，spec §Further Notes）**：注册 GitHub App（device flow + 关闭 token 过期 + Issues RW + 安装到目标仓库）→ 填入 BuildConfig 两字段 → 重走真机 E2E（授权 8 位码流/真实建 issue/重复命中评论/预览编辑/失败重试）
- 中途事故如实记录：i18n 首轮脚本矩阵错位（14 语言文件混入错语值）→ git checkout 全量恢复 + 正确矩阵重写；UI when 块一次插入失败（报错轮 edit 未达）→ 二次插入 + m3 AlertDialog confirmButton 重载约束修复

## #152 日志分级修复实现记录（2026-08-21 完成，真机验证 PASS）

### 提交链（三组）

| 组 | commit | 内容 |
|----|--------|------|
| 1 风暴降级 | f535e15d | SseConnectionManager：attempt/cooldown-wait/reconnect-in/V2-client/reconnecting-after-recovery 全 i→d（DEBUG 门控）；stream error e→w（网络中断是常态非程序错误）；connection-failed e→d + 补 throwable（与 stream-error 双记消除，cooldown w 为用户可见信号） |
| 2 门控+throwable | f398b7f3 | SessionEventHandler.handleSessionUpdated per-event INFO 补 DEBUG 门控（#40 残留漏网）；3 处 e 补 throwable（DraftDataStore 持久化/MessageStore 归档跳过/DatabaseRecovery 损坏恢复——原 `${e.message}` 内插却丢栈） |
| 3 WebView+遗留 | 2a07ad74 | WebView onReceivedError 主帧 w / 子资源 d 分流（图片 404 噪音出错误队列）；ActionModeDebug 遗留诊断标签删除 |

**附带**：#186 时序脆弱测试当场根因修复（1fefdb70）——真实 Dispatchers.IO 逃逸虚拟时间竞态，awaitTrackerValue 轮询助手加固 5 断言点；两次连续全量 1812 绿（此前 5 次间歇失败的阻塞源清除）。

### 验证证据（2026-08-21 真机 houji）

- **自动化**：全量 1812/1812 ×2 连续绿
- **真机风暴验证**（摘除 adb reverse 12s 制造真实断连 + 飞行模式切换）：SseConnManager 日志级别分布 **6D + 5I + 0W + 0E**——风暴环（attempt/reconnect/cooldown）全 D；残留 I 均为一次性成功里程碑（Connected/Pre-loaded 200 sessions/Recovered 50/50，运维有价值保留）；错误队列零污染（#151 的"最近 20 条错误"前置达成）
- TAG 实名 SseConnManager（取证时按此校正；审计清单其余 file:line 已在锚点核对段漂移校正）
  - **2026-08-21 架构批次后锚点核对（P1 spec 修订步骤）**：病灶全部仍在、无新增——①双日志：SseConnectionManager catch 块 "SSE stream error"(AppLogger.e 带 throwable) + "SSE connection failed: message"(:384，**无 throwable**——7 处缺 throwable 之一实证) 同一失败两连发；②per-event INFO 遗漏网：SessionEventHandler.handleSessionUpdated:106 SessionUpdated 无条件 AppLogger.i 仍在；③行号漂移：SseConnectionManager ~+2（#170/#160 改动）、OpenCodeConnectionService SSE 路由区 588→533（协调器抽取）；④本批次（#171-#175）对 AppLogger 调用零增删（EventDispatcher 12 处/OpenCodeConnectionService 30 处/SseConnectionManager 25 处密度不变）。审计 file:line 清单按 +2/−55 漂移校正后继续有效。

- [ ] **#153 前置：release CI 留存 R8 mapping.txt artifact** `refactor`
  - 问题：release.yml 只上传 APK，mapping.txt 随临时 runner 销毁——用户上报的混淆堆栈永久无法还原
  - 方案：workflow 加 mapping.txt artifact 上传（与 APK 同批，90 天保留）

- [ ] **#154 上报增强：崩溃后自动提示 + secret gist 全量日志附件** `ui` `data`
  - spec §Out of Scope 明确后置项：①下次启动检测未上报 FATAL → 主动提示（需跨启动记账状态机）②全量日志 secret gist 链接（需额外 gist scope）
  - 触发：#151 落地并稳定后评估
