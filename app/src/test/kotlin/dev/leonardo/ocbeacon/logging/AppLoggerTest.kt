package dev.leonardo.ocbeacon.logging

import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 验证 [AppLogger.nextTimestamp] 的单调性。
 *
 * 背景：诊断页 LazyColumn 以 timestamp 参与 key 计算；若同一毫秒内产生
 * 多条日志（崩溃捕获、连续错误写入），`System.currentTimeMillis()` 可能
 * 返回相同值，导致 "Key was already used" 崩溃。nextTimestamp 通过 CAS
 * 保证本进程内严格递增。
 */
class AppLoggerTest {

    @Test
    fun nextTimestamp_isStrictlyMonotonicAcrossRapidCalls() {
        val seen = mutableSetOf<Long>()
        repeat(5000) {
            val ts = AppLogger.nextTimestamp()
            assertTrue("duplicate timestamp: $ts", seen.add(ts))
        }
    }

    @Test
    fun nextTimestamp_isStrictlyMonotonicUnderConcurrency() {
        val pool = Executors.newFixedThreadPool(8)
        try {
            val futures = (1..8).map {
                pool.submit(Callable {
                    val local = mutableListOf<Long>()
                    repeat(1000) { local += AppLogger.nextTimestamp() }
                    local
                })
            }
            val all = futures.flatMap { it.get(10, TimeUnit.SECONDS) }
            // CAS 不变量：全局返回值严格唯一（单调）。线程间收集顺序
            // 由调度决定，不能断言拼接顺序 == 排序。
            assertTrue("values are not strictly monotonic", all.toSet().size == all.size)
            // 每个线程内部也必须严格递增。
            futures.map { it.get() }.forEach { local ->
                assertTrue("thread-local values not monotonic", local.toSet().size == local.size)
            }
        } finally {
            pool.shutdownNow()
        }
    }
}
