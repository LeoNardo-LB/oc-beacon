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

  - **2026-08-21 架构批次后锚点核对（P1 spec 修订步骤）**：病灶全部仍在、无新增——①双日志：SseConnectionManager catch 块 "SSE stream error"(AppLogger.e 带 throwable) + "SSE connection failed: message"(:384，**无 throwable**——7 处缺 throwable 之一实证) 同一失败两连发；②per-event INFO 遗漏网：SessionEventHandler.handleSessionUpdated:106 SessionUpdated 无条件 AppLogger.i 仍在；③行号漂移：SseConnectionManager ~+2（#170/#160 改动）、OpenCodeConnectionService SSE 路由区 588→533（协调器抽取）；④本批次（#171-#175）对 AppLogger 调用零增删（EventDispatcher 12 处/OpenCodeConnectionService 30 处/SseConnectionManager 25 处密度不变）。审计 file:line 清单按 +2/−55 漂移校正后继续有效。

- [ ] **#153 前置：release CI 留存 R8 mapping.txt artifact** `refactor`
  - 问题：release.yml 只上传 APK，mapping.txt 随临时 runner 销毁——用户上报的混淆堆栈永久无法还原
  - 方案：workflow 加 mapping.txt artifact 上传（与 APK 同批，90 天保留）

- [ ] **#154 上报增强：崩溃后自动提示 + secret gist 全量日志附件** `ui` `data`
  - spec §Out of Scope 明确后置项：①下次启动检测未上报 FATAL → 主动提示（需跨启动记账状态机）②全量日志 secret gist 链接（需额外 gist scope）
  - 触发：#151 落地并稳定后评估
