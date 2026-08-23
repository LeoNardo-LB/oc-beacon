# ch02 · 项目全景导览 —— 一次点击的完整旅程

> 状态：⬜ 待生成 ｜ 前置：ch01
> 本章解决：三层架构怎么映射到包目录；从用户点击到数据返回的全链路；对照 Spring 的 Controller/Service/Repository 分层。
> 填充方式：对 AI 说「填充 docs/learning 的 ch02」，规范见 [../AGENTS.md](../AGENTS.md)

## 提纲要点
- Clean Architecture 三层与依赖方向：UI → Domain ← Data（对照 Controller→Service→Repository）
- 包树导览：data/（api/dto/local/repository）、domain/（model/repository接口/usecase）、ui/（screens/navigation/components）
- 入口链路：Application → MainActivity → NavGraph → 首屏
- UseCase 层的存在意义（28 个用例类，何时该建何时不该）

## 本项目锚点（待填充时重新验证）
- `OpenCodeApp.kt:61` — @HiltAndroidApp 应用入口
- `MainActivity.kt` — setContent → NavGraph
- `ui/navigation/NavGraph.kt:225` — NavHost，起点 Screen.Home.route
- `ui/screens/home/HomeRoute.kt` — 29 行的最短 Route 样本
- `domain/usecase/` — 28 个用例类清单

## 观察任务预告
- 挑一次「刷新会话列表」交互，手画出它穿越的所有文件
- 找出一个你认为"没必要经过 UseCase"的调用并说明理由
