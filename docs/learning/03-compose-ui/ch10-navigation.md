# ch10 · 导航与屏幕组装 —— NavGraph 路由表与防崩溃参数

> 状态：⬜ 待生成 ｜ 前置：ch08
> 本章解决：单 Activity 多屏幕的路由机制；参数传递与编码陷阱；对照前端 Router。
> 填充方式：对 AI 说「填充 docs/learning 的 ch10」，规范见 [../AGENTS.md](../AGENTS.md)

## 提纲要点
- 单 Activity 架构：为什么 Android 现代开发只有一个 MainActivity（对照多 Activity 旧时代）
- Navigation Compose：NavHost/composable(route)/NavController 三件套（对照 React Router）
- 路由参数与深链；**必须用 NavUtils.safeDecodeParam()**——畸形 `%` 序列（如密码含 %NR）裸 URLDecoder 会崩溃的实战教训
- 底部栏/折叠屏窗口大小类与导航的配合

## 本项目锚点（待填充时重新验证）
- `ui/navigation/NavGraph.kt:225` — NavHost 定义，startDestination = Screen.Home.route
- `ui/navigation/Screen.kt` + `ui/navigation/routes/` 子目录 — 路由表组织方式
- `util/` 下 NavUtils.safeDecodeParam — 参数安全解码

## 观察任务预告
- 列出全部路由并画出导航关系图（谁可以回到谁）
- 找到 safeDecodeParam 实现并解释它比裸 decode 多防了什么
