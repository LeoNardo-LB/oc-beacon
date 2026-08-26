# 2026-08-27 流式卡顿收官——Compose 混搭矩阵定因（用户验收：好很多）

> 承接 08-26 深夜三轮修复（regex 哨兵 c33a4868 / foundation 单点强回 ac12cf93 / fling 防御 v2 903e2612）。
> 用户 A/B 无限速 fling 实验后仍报「还是会卡」——限速假设证伪，本批彻查定因。

## 调查链与证据

### ① 架构重构回归排除（用户指示「对比架构重构之前的代码」）
- 基线 bd92d58f（#234 spec 落定）..HEAD 全 29 commit 逐文件审查
- #234 MessageMergeEngine：applyDelta/mergePart/mergeSortedMessages 纯函数迁移**逐行等价**（endsWith 去重/idx<0 重建/#223/#230 分支语义原样）
- ChatMessageList 重写：被删 easeInOutCubic 基线已死码；分页/补偿/铁律四条挂载点全部原位
- C7/C9/C5 均为启动期编排迁移；flush 机制（48ms 不取消 + 增量落盘）完整未动
- **结论：重构无罪，卡顿在依赖矩阵**

### ② 混搭矩阵实锤（dependencyInsight）
- 08-22 765501a8 钉 material3 1.5.0-alpha26 → 原子组约束（"ui is in atomic group androidx.compose.ui"）把 runtime/ui/**ui-text**/animation 整组拉 1.12.0-beta01
- **08-20 丝滑基线（全家 1.11.x）自此从未运行过**；ui-text（SSE 流式重排热路径）一直跑 beta
- ac12cf93 仅强回 foundation = 「1.11.2 + 其余 beta」从未存在过的混搭

### ③ alpha26 二进制冲突（真机 FATAL 栈）
- 全组对齐 1.11.2 后滚动即崩：`NoSuchMethodError: graphicsLayer-56HxDYs$default(...LayerOutsets...)` @ material3 Surface → FloatingActionButton → ChatScrollBottomFab
- alpha26 按 ui 1.12-beta 编译，调 1.12 独有签名——与稳定 ui **结构性不兼容**，非版本选择问题

## 修复（0775582d / 58f2b953 / 7f45ccc6）
- material3 回 BOM 1.4.0；eachDependency 将 ui/runtime/foundation/animation 四组对齐 1.11.2（防传递漂移）
- ChatFabMenu 稳定 API 复刻：FloatingActionButtonMenu/ToggleFAB/MenuItem 三件替换为 Column+AnimatedVisibility+Surface 药丸；#194 溢出几何常量精确对齐；贴边滑动/外点收起/BackHandler/角标全保留；仅 morph 动画简化
- debug channel 门禁放开 dev flavor 全构建类型（devRelease 全新安装可配置服务器；beta/stable 仍禁）

## 验证
- 2074 单测绿（首次 3 失败 = 自踩「Gradle 并发禁令」竞写假失败——单测与装机并行，串行重跑实证；纪律再确认）
- 真机 E2E：滚动 FAB 无崩溃/菜单展开四入口/Queued 导航/返回；release 渲染完好（R8 无破坏）
- atrace 帧基线（流式+滚动混合）：doFrame p50=5.71ms p99=8.41ms（120Hz 预算内）；间隔直方图 8.3ms 正常帧 95%+
- **用户验收（08-27 深夜）：「虽然还有很细微的卡顿，但现在已经好很多了」**

## 残留「细微卡顿」已知嫌疑（#239 跟踪）
1. **服务器 token 爆发节奏**（已实证）：ox-alpha-free flush gap p50=14157ms、deltas=1——14 秒攒一坨全发，视觉蹦字步进是服务器侧行为；换快模型可分离验证
2. **补偿/fling 竞态零位移帧**：devRelease 上游实测 5 次/会话（SafeFling survived 日志）——每次一帧顿挫；根治需动铁律③区域（compensation-yields-to-fling），专项批次
3. **48ms flush 固有步进**：20Hz 内容更新 vs 120Hz 显示——理论上限平滑度，属设计权衡

## 工具链沉淀
- atrace 解析坑：`E|pid` 结束行不带 section 名——深度计数配对，勿按名字匹配
- 帧分析必须先做间隔直方图定刷新率（8.3=120Hz/16.7=60Hz/33=skip-1），再看 doFrame 时长分位——直接看分位会重蹈 gfxinfo 误读
- MIUI 全新安装拦截（INSTALL_FAILED_USER_RESTRICTED）：pm install 也被拦，需弹窗自动点「继续安装」（/tmp/miui-install.py 模式）