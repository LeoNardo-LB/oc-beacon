# ch04 · 协程入门 —— suspend 到底是不是线程？

> 状态：⬜ 待生成 ｜ 前置：ch01
> 本章解决：协程心智模型、CoroutineScope 生命周期绑定、调度器线程切换；对照 CompletableFuture/ExecutorService。
> 填充方式：对 AI 说「填充 docs/learning 的 ch04」，规范见 [../AGENTS.md](../AGENTS.md)

## 提纲要点
- suspend 挂起 vs 线程阻塞的本质区别（CPS 变换直觉版）
- CoroutineScope 家族：viewModelScope / lifecycleScope / 自定义 scope
- Dispatchers.Main/IO/Default 与 withContext（对照线程池提交）
- 结构化并发：取消传播、异常边界、SupervisorJob
- runBlocking 为什么只该出现在测试里

## 本项目锚点（待填充时重新验证）
- `di/CoroutinesModule.kt` — 项目级 dispatcher 提供
- `ui/screens/home/HomeViewModel.kt` — viewModelScope.launch 使用现场

## 观察任务预告
- 找出项目里所有 Dispatchers 切换点并说明各自原因
- 找一处取消安全的挂起循环
