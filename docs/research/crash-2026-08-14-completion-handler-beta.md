# 崩溃报告：CompletionHandlerException（beta 0.3.0-beta.8 真机）

> 登记时间：2026-08-14 · 来源：用户反馈（OnePlus PLK110 真机）
> 状态：待排查（backlog #128）

## 基本信息

| 项 | 值 |
|----|----|
| App | dev.leonardo.ocbeacon.beta (0.3.0-beta.8) |
| Android | 16 (SDK 36) |
| 设备 | OnePlus PLK110 |
| 时间 | 2026-08-14 09:12:10.953 |
| 线程 | Thread[main,5,main] |
| 异常 | kotlinx.coroutines.CompletionHandlerException |

## 异常信息

```
Exception in completion handler InvokeOnCancelling@c520a61[job@61e2986]
for StandaloneCoroutine{Cancelling}@61e2986
```

## 栈轨迹（R8 混淆后）

```
kotlinx.coroutines.CompletionHandlerException: Exception in completion handler InvokeOnCancelling@c520a61[job@61e2986] for StandaloneCoroutine{Cancelling}@61e2986
	at kotlinx.coroutines.JobSupport.notifyCancelling(...)
	at kotlinx.coroutines.JobSupport.tryMakeCancelling(...)
	at kotlinx.coroutines.JobSupport.makeCancelling(...)
	at kotlinx.coroutines.JobSupport.cancelImpl$(...)
	at kotlinx.coroutines.JobSupport.cancelInternal(...)
	at kotlinx.coroutines.JobSupport.cancel(...)
	at kotlinx.coroutines.Job.cancel$default(...)
	at ci1.e(...)          ← 混淆类：flow 相关（可能 Channel/flow 内部）
	at yh1.emit(...)       ← 混淆类：flow emit
	at j20.emit(...)       ← 混淆类：flow emit
	at kotlinx.coroutines.flow.internal.SafeCollectorKt$emitFun$1.invoke(...)
	at kotlinx.coroutines.flow.internal.SafeCollector.emit(...)
	at mz.emit(...)        ← 混淆类
	at kotlinx.coroutines.flow.FlowKt__LimitKt$dropWhile$1$1.emit(...)
	at kotlinx.coroutines.flow.FlowKt__LimitKt$takeWhile$lambda$0$$inlined$collectWhile$1.emit(...)
	at kotlinx.coroutines.flow.StateFlowImpl.collect(...)
	at kotlinx.coroutines.flow.StateFlowImpl$collect$1.invokeSuspend(...)
	at to.resumeWith(...)
	at kotlinx.coroutines.DispatchedTask.run(...)
	at android.os.Handler.handleCallback(...)
	at android.os.Looper.loopOnce(...)
	at android.os.Looper.loop(...)
	at android.app.ActivityThread.main(...)
	...（主线程）
Suppressed: kotlinx.coroutines.internal.DiagnosticCoroutineContextException: [StandaloneCoroutine{Cancelling}@61e2986, Dispatchers.Main.immediate]
Caused by: kotlinx.coroutines.CompletionHandlerException: Exception in completion handler InvokeOnCompletion@ab2da6b[job@675c9c8] for JobImpl{Cancelled}@675c9c8
	at kotlinx.coroutines.JobSupport.notifyCompletion(...)
	...（同源嵌套）
```

## 初步分析（待深入排查）

- **直接原因**：某个 `StandaloneCoroutine`（Dispatchers.Main.immediate）取消时，其 `invokeOnCancelling` 完成回调**内部抛异常** → 被 kotlinx.coroutines 包装为 `CompletionHandlerException` → 主线程崩溃。
- **特征**：栈中出现 `StateFlowImpl.collect → dropWhile → takeWhile → SafeCollector.emit`——**协程取消回调链里执行了 flow emit**（取消时向 StateFlow emit，触发下游 collect 协程 cancel，cancel 又触发另一 handler 异常）——疑似某个 `LaunchedEffect`/`rememberCoroutineScope` 的取消处理或 `invokeOnCompletion` 回调中访问了已释放资源/在取消中 emit。
- **R8 混淆**：`ci1/yh1/j20/mz/to` 等无法直接映射源码类——需用 release 构建的 mapping 文件（`app/build/outputs/mapping/betaRelease/mapping.txt`）反混淆定位；或复现后抓未混淆的 dev 版栈。
- **关联候选**（低置信，需验证）：
  - 会话切换/退出时的 `releaseSessionData`/协程取消链（今日 #124 改动涉及 onCleared 清理）
  - `MessageEventHandler` batchScope / persistQueue（#57 actor）
  - `StateFlowImpl.collect + dropWhile/takeWhile` 模式——项目内搜 dropWhile/takeWhile 使用点
- **崩溃时刻**：09:12:10（用户真机 beta 包）——与模拟器测试时段重叠，但 beta 包 = 0.3.0-beta.8（不含今日 dev 修复）

## 后续行动

1. 用 betaRelease mapping 文件反混淆定位源码类（`ci1.e`/`yh1.emit`/`mz.emit`）
2. 全库搜 `invokeOnCompletion`/`invokeOnCancelling` 回调内做 flow emit/UI 操作的代码
3. 模拟器复现（若可）
4. 修复后验证 + beta 发版
