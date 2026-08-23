# ch14 · 独立开发路标 —— 脱离本项目还需要什么

> 状态：⬜ 待生成 ｜ 前置：阶段二全部 + ch12/ch13 可选
> 本章解决：从"读懂这个项目"到"能从零开发自己的 App"的知识缺口清单与学习资源地图。
> 填充方式：对 AI 说「填充 docs/learning 的 ch14」，规范见 [../AGENTS.md](../AGENTS.md)

## 提纲要点
- 本项目刻意绕开/未覆盖的主题盘点：
  - Activity 生命周期全解（配置变更、进程死亡恢复）
  - Service/前台服务深入（项目 service/ 目录只用了子集）
  - 权限模型、多模块架构（本项目单 :app 模块）
  - DataStore/Preferences、WorkManager 后台任务
  - 测试体系实战（JUnit4/MockK/Turbine/Maestro E2E）
- 从零建 App 实战路径建议（官方 Codelab 路线）
- 发版知识：签名体系、version 管理、CI（对照本项目 release-workflow）

## 本项目锚点（待填充时重新验证）
- `app/src/main/AndroidManifest.xml` — :18 Application 注册、:32 MainActivity
- `docs/release-workflow.md` — 发版唯一权威指南
- `app/build.gradle.kts` — 依赖版本单一真相源

## 观察任务预告
- 通读 AndroidManifest.xml 全文，逐标签解释作用
- 用官方模板新建一个空白 Compose 工程并跑通（本任务在独立项目中做）
