package dev.leonardo.ocbeacon.util

import kotlinx.coroutines.CancellationException

/**
 * 协程安全的 runCatching（#128 根因修复：beta 真机 CompletionHandlerException 崩溃）。
 *
 * 反模式：runCatching { ... } 会捕获**所有** Throwable 包括 CancellationException——
 * 当协程被取消（作用域关闭、用户取消、Job.cancel）时，网络请求挂起点抛出的
 * CancellationException 被吞掉 → 协程不响应取消继续执行（取消后还在工作）：
 * 1. 被取消的 job 在已取消状态下继续跑完网络请求 + 后续状态更新；
 * 2. job 状态机进入取消中但协程体正常完成的不一致路径；
 * 3. 取消通知链（notifyCancelling / notifyCompletion）中 handler 抛异常 →
 *    CompletionHandlerException → 主线程崩溃（#128 实证：HomeViewModel
 *    refreshServerSettingsAvailability → serverSettingsCheckJobs.cancel() →
 *    被取消的 loadProviders job 内部 runCatching 吞掉取消 → 取消链 handler 异常）。
 *
 * 语义（与 safeCatch 一致）：
 * - block 正常返回 → 原样返回
 * - block 抛 CancellationException → **重新抛出**（取消必须传播，绝不吞）
 * - block 抛其他 Throwable → 包装为 Result.failure
 *
 * 用法：把数据层/仓库层包裹网络调用的 runCatching { ... } 替换为
 * runCatchingCancellable { ... }。
 */
inline fun <T> runCatchingCancellable(block: () -> T): Result<T> = try {
    Result.success(block())
} catch (e: CancellationException) {
    throw e
} catch (e: Throwable) {
    Result.failure(e)
}
