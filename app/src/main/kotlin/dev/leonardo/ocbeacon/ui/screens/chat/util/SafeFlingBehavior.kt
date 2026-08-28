package dev.leonardo.ocbeacon.ui.screens.chat.util

import androidx.compose.foundation.gestures.FlingBehavior
import androidx.compose.foundation.gestures.ScrollScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import dev.leonardo.ocbeacon.logging.AppLogger
import kotlinx.coroutines.CancellationException
import kotlin.math.abs
import kotlin.math.exp

/**
 * 限速 fling（2026-08-20 真机滚动稳定性修复）。
 *
 * 根因（ScrollDiag 真机取证，Pixel-class 120Hz 设备）：高速 fling 以
 * 30k+ px/s 冲入未组合的长 assistant 消息区——item 首测仅占位高度
 * （412px），markdown 解析完成后暴涨（+16334px）→ LazyColumn 锚点修正
 * → 视口瞬移（用户报"fling 下跳"，长回复稳定复现）。
 *
 * 方案：接管 fling 动画——每帧位移限制在视口高 1/8 以内（carry 保留
 * 总距离，手感与原生一致），保证视口穿越未组合区域耗时 ≥ 8 帧，
 * 让滚动预解析（RenderReadinessRegistry 驱动）+ 预组合（ScrollSpeedPrefetch
 * 策略）有机会在 item 进入视口前完成——高度正确，无锚点修正。
 *
 * 历史：e651daf1 首次引入切块 fling（修 fling 跳过长消息）；cd1ae6ee
 * v1 迭代移除（当时以 cacheWindow 对称窗口替代）；2026-08-13 起窗口被
 * ScrollSpeedPrefetchStrategy 取代后高速段保护缺失——本文件以视口自适应限速
 * 回归，并与渲染供给协调器配合（当年两者未同时存在）。
 */
private const val TAG = "SafeFling"

@Composable
internal fun rememberSafeFlingBehavior(listState: LazyListState): FlingBehavior {
    return remember(listState) {
        object : FlingBehavior {
            override suspend fun ScrollScope.performFling(initialVelocity: Float): Float {
                val absVel = abs(initialVelocity)
                if (absVel < 1f) return initialVelocity
                var velocity = initialVelocity
                val friction = 3f          // 指数衰减系数（对齐系统 ScrollView 量级）
                val minVelocity = 50f      // 低于此速度视为停止
                var carry = 0f             // 限幅截留的位移（保总距离）
                var lastFrame = withFrameNanos { it }
                while (abs(velocity) > minVelocity) {
                    val frame = withFrameNanos { it }
                    val dt = (frame - lastFrame) / 1_000_000_000f
                    lastFrame = frame
                    if (dt <= 0f || dt > 0.1f) continue
                    // 视口自适应：每帧 ≤ 视口高/8（小屏兜底 180px）。
                    // 保证任一方向穿越视口量级内容 ≥ 8 帧（60Hz ≈ 133ms、
                    // 120Hz ≈ 67ms）——预解析/预组合的完成窗口。
                    val viewport = (listState.layoutInfo.viewportEndOffset -
                        listState.layoutInfo.viewportStartOffset).coerceAtLeast(1)
                    val maxPerFrame = (viewport / 8f).coerceAtLeast(180f)
                    val rawDelta = velocity * dt + carry
                    val delta = rawDelta.coerceIn(-maxPerFrame, maxPerFrame)
                    carry = rawDelta - delta
                    // 2026-08-26 崩溃防御 v2（真机取证：流式期间补偿/自动滚动
                    // 让 scrollToBeConsumed 残量成为常态——v1 的静默中止会把
                    // fling 直接掐死，用户失去惯性「松手卡在原地」）：契约违规
                    // 时等待一帧让测量通道消费残量后重试，惯性穿过瞬态违规；
                    // 连续 retries 超限才降级中止（保底防 FATAL）。
                    // CancellationException 必须放行（触摸打断 fling 的正常路径）。
                    var consumed: Float
                    var retries = 0
                    while (true) {
                        consumed = try {
                            scrollBy(delta)
                        } catch (e: CancellationException) {
                            // [DEBUG-flng] 触摸打断 fling——记录打断时内速（>2000px/s 被掐=异常）
                            AppLogger.d(
                                TAG,
                                "[DEBUG-flng] fling CANCELLED by touch: velocity=" + velocity.toInt() +
                                    " idx=" + listState.firstVisibleItemIndex +
                                    " off=" + listState.firstVisibleItemScrollOffset
                            )
                            throw e
                        } catch (e: IllegalStateException) {
                            if (++retries > 3) {
                                AppLogger.w(TAG, "fling aborted: " + e.message +
                                    " (velocity=" + velocity.toInt() + "px/s retries=" + retries + ")")
                                return velocity
                            }
                            // 等一帧：残量由下一次 measure/applyMeasureResult 消费
                            withFrameNanos { it }
                            continue
                        }
                        break
                    }
                    if (retries in 1..3) {
                        AppLogger.d(TAG, "fling survived scroll contract violation after " + retries + " frame(s)")
                    }
                    if (abs(consumed) < 0.5f) {
                        // [DEBUG-flng] 停住取证：mid-fling 零消费帧即终止——记录终速与位置
                        AppLogger.w(
                            TAG,
                            "[DEBUG-flng] fling STOPPED zero-consume: velocity=" + velocity.toInt() +
                                "px/s carry=" + carry.toInt() + " idx=" + listState.firstVisibleItemIndex +
                                " off=" + listState.firstVisibleItemScrollOffset
                        )
                        return velocity
                    }
                    velocity *= exp(-friction * dt)
                }
                // [DEBUG-flng] 自然衰减出口带内速（判定「提前停住」的基准）
                AppLogger.d(
                    TAG,
                    "[DEBUG-flng] fling decayed out: velocity=" + velocity.toInt() +
                        " idx=" + listState.firstVisibleItemIndex +
                        " off=" + listState.firstVisibleItemScrollOffset
                )
                return velocity
            }
        }
    }
}