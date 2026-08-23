# ch08 · Compose 思维模型 —— 从命令式 DOM 到声明式函数

> 状态：⬜ 待生成 ｜ 前置：ch05
> 本章解决：@Composable 为什么能"画"出界面；重组机制；对照 React 组件模型与你了解的前端。
> 填充方式：对 AI 说「填充 docs/learning 的 ch08」，规范见 [../AGENTS.md](../AGENTS.md)

## 提纲要点
- 声明式 UI：UI = f(state)，不再 findViewById/setView（对照 XML View 体系与 React JSX）
- @Composable 只是普通函数 + 位置记忆（Slot Table 直觉版）
- 重组：什么时候重跑、什么会丢、remember 保住的是什么
- Modifier 链式参数（对照 CSS 组合）
- Material 3 组件起步：Scaffold/Surface/Button/Text

## 本项目锚点（待填充时重新验证）
- `ui/screens/sessions/SessionListRoute.kt` — 24 行最短入门样本
- `ui/screens/home/HomeRoute.kt` — 29 行，含窗口大小类参数
- `ui/theme/` — 主题令牌系统
- 远期目标样本：`ChatScreen.kt`（1015 行，学完阶段二再啃）

## 观察任务预告
- 通读 SessionListRoute.kt 全部 24 行，逐行标注每个符号的来源
- 找出项目里 3 个 remember 用法并说明各自"记住"了什么
