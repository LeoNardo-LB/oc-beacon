package dev.leonardo.ocbeacon.util

import kotlinx.coroutines.CancellationException

/**
 * 协程安全的 catch 包装（#60，防 TD-6 模式复发）。
 *
 * 反模式：`catch (e: Exception) { ... }` 会吞掉 [CancellationException]——
 * 协程取消（作用域关闭、超时、用户取消）时 fallback 仍执行，可能产生
 * 错误结果或二次异常（"取消后还在工作"）。
 *
 * 用法：
 * ```kotlin
 * val result = safeCatch(
 *     block = { repository.load() },
 *     fallback = { e -> AppLogger.e(TAG, "load failed", e); emptyList() }
 * )
 * ```
 *
 * 语义：
 * - block 正常返回 → 原样返回
 * - block 抛 [CancellationException] → **重新抛出**（取消必须传播，绝不吞）
 * - block 抛其他 [Exception] → fallback 接管
 *
 * 新代码请优先使用本工具；既有 `catch (e: Exception)` 逐步迁移。
 */
suspend fun <T> safeCatch(
    block: suspend () -> T,
    fallback: (Exception) -> T,
): T = try {
    block()
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    fallback(e)
}
