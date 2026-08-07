package dev.leonardo.ocbeacon.logging

import android.util.Log as AndroidLog
import dev.leonardo.ocbeacon.data.repository.DiagnosticLogEntry
import dev.leonardo.ocbeacon.data.repository.DiagnosticLogRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicLong

/**
 * 全局应用日志器——桥接 [android.util.Log]（logcat）与通过 [DiagnosticLogRepository]
 * 持久化的诊断数据库。
 *
 * 写入操作入队到有界 [Channel]（容量 500，[BufferOverflow.DROP_OLDEST]），
 * 由单消费者协程排空，每次 flush 批量写入最多 50 条。
 * [minimumLevel] 控制哪些级别会被持久化；低于阈值的条目仍会发送到 logcat，
 * 但跳过持久化。
 *
 * 崩溃捕获：[recordCrash] 入队一条 `FATAL` 条目，并通过 [runBlocking]
 * 同步 flush，以便在进程死亡前持久化崩溃信息。
 */
object AppLogger {
    private sealed interface WriterCommand {
        data class Entry(val value: DiagnosticLogEntry) : WriterCommand
        data class Flush(val completion: CompletableDeferred<Boolean>) : WriterCommand
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val droppedEntries = AtomicLong(0)
    private val lastTimestamp = AtomicLong(0L)
    private val queue = Channel<WriterCommand>(
        capacity = 500,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
        onUndeliveredElement = { command ->
            when (command) {
                is WriterCommand.Entry -> droppedEntries.incrementAndGet()
                is WriterCommand.Flush -> command.completion.complete(false)
            }
        },
    )
    @Volatile private var repository: DiagnosticLogRepository? = null
    @Volatile private var minimumLevel = "INFO"

    /**
     * 绑定持久化 [repository] 并启动后台消费者。
     * 同时订阅 [DiagnosticLogRepository.logLevel]，使 [minimumLevel]
     * 跟踪用户选择的持久化阈值。
     *
     * 仅可调用一次；后续调用为空操作。
     */
    fun initialize(repository: DiagnosticLogRepository) {
        // 防重复初始化：check-then-act 非原子，多线程并发调用会启动两份消费者
        synchronized(this) {
            if (this.repository != null) return
            this.repository = repository
        }
        scope.launch {
            repository.logLevel.collect { level -> minimumLevel = level }
        }
        scope.launch {
            while (true) {
                val command = queue.receive()
                val batch = mutableListOf<DiagnosticLogEntry>()
                when (command) {
                    is WriterCommand.Entry -> {
                        batch += command.value
                        delay(150)
                        while (batch.size < 50) {
                            when (val next = queue.tryReceive().getOrNull() ?: break) {
                                is WriterCommand.Entry -> batch += next.value
                                is WriterCommand.Flush -> {
                                    val persisted = persistBatch(batch)
                                    batch.clear()
                                    next.completion.complete(persisted)
                                    break
                                }
                            }
                        }
                        if (batch.isNotEmpty()) persistBatch(batch)
                    }
                    is WriterCommand.Flush -> command.completion.complete(true)
                }
            }
        }
    }

    fun d(tag: String, message: String): Int = write("DEBUG", tag, message, null) {
        AndroidLog.d(tag, message)
    }

    fun d(tag: String, message: String, error: Throwable): Int = write("DEBUG", tag, message, error) {
        AndroidLog.d(tag, message, error)
    }

    fun i(tag: String, message: String): Int = write("INFO", tag, message, null) {
        AndroidLog.i(tag, message)
    }

    fun w(tag: String, message: String): Int = write("WARN", tag, message, null) {
        AndroidLog.w(tag, message)
    }

    fun w(tag: String, message: String, error: Throwable): Int = write("WARN", tag, message, error) {
        AndroidLog.w(tag, message, error)
    }

    fun e(tag: String, message: String): Int = write("ERROR", tag, message, null) {
        AndroidLog.e(tag, message)
    }

    fun e(tag: String, message: String, error: Throwable): Int = write("ERROR", tag, message, error) {
        AndroidLog.e(tag, message, error)
    }

    /**
     * 将未捕获异常记录为 `FATAL` 条目并同步 flush，
     * 以便在进程终止前持久化。
     */
    fun recordCrash(thread: Thread, error: Throwable) {
        val details = throwableDetails(error) + ("thread" to thread.name)
        val entry = DiagnosticLogEntry(
            timestamp = nextTimestamp(),
            level = "FATAL",
            category = "Uncaught exception",
            message = error.message ?: error::class.java.simpleName,
            details = details,
        )
        queue.trySend(WriterCommand.Entry(entry))
        runBlocking(Dispatchers.IO) {
            flush(CRASH_FLUSH_TIMEOUT_MS)
        }
    }

    /** flush 待处理条目，最多等待 [timeoutMillis] 完成。 */
    suspend fun flush(timeoutMillis: Long = 2_000L): Boolean {
        val completion = CompletableDeferred<Boolean>()
        queue.send(WriterCommand.Flush(completion))
        return withTimeoutOrNull(timeoutMillis) { completion.await() } ?: false
    }

    fun droppedEntryCount(): Long = droppedEntries.get()

    // ---- 内部实现 --------------------------------------------------------

    private inline fun write(
        level: String,
        tag: String,
        message: String,
        error: Throwable?,
        androidWrite: () -> Int,
    ): Int {
        val result = androidWrite()
        if (!shouldPersist(level)) return result
        queue.trySend(
            WriterCommand.Entry(
                DiagnosticLogEntry(
                    timestamp = nextTimestamp(),
                    level = level,
                    category = tag,
                    message = message,
                    details = error?.let(::throwableDetails).orEmpty(),
                ),
            ),
        )
        return result
    }

    /**
     * 生成单调递增的毫秒时间戳。
     *
     * 同一毫秒内连续写入多条日志（崩溃捕获、catch 块连续错误）时，
     * `System.currentTimeMillis()` 可能返回相同值；诊断页列表以 timestamp
     * 参与 LazyColumn key 计算，重复会导致 "Key was already used" 崩溃。
     * 通过 CAS 保证本进程内时间戳严格递增（相同时刻的日志 +1ms 错开）。
     */
    internal fun nextTimestamp(): Long {
        while (true) {
            val now = System.currentTimeMillis()
            val previous = lastTimestamp.get()
            if (now > previous) {
                if (lastTimestamp.compareAndSet(previous, now)) return now
            } else {
                if (lastTimestamp.compareAndSet(previous, previous + 1)) return previous + 1
            }
        }
    }

    private fun shouldPersist(level: String): Boolean {
        val priorities = mapOf("ERROR" to 0, "WARN" to 1, "INFO" to 2, "DEBUG" to 3)
        return (priorities[level] ?: 0) <= (priorities[minimumLevel] ?: 2)
    }

    private fun throwableDetails(error: Throwable): Map<String, String> {
        return buildMap {
            put("exception", error::class.java.name)
            error.cause?.let { put("cause", "${it::class.java.name}: ${it.message.orEmpty()}") }
            // JVM 单元测试环境下 android.jar stub 的 getStackTraceString 返回 null，
            // 回退到 Kotlin 标准库实现（不依赖 Android）。
            // 平台类型 String! 显式声明为可空——保留运行时 null 防御且消除
            // "Elvis always returns left operand" 编译警告。
            val raw: String? = AndroidLog.getStackTraceString(error)
            val stack = raw ?: error.stackTraceToString()
            put("stack", stack.lineSequence().take(12).joinToString("\n"))
        }
    }

    private suspend fun persistBatch(batch: List<DiagnosticLogEntry>): Boolean {
        return try {
            repository?.recordBatch(batch)
            true
        } catch (error: Exception) {
            AndroidLog.e("AppLogger", "Persistent diagnostic write failed", error)
            false
        }
    }

    private const val CRASH_FLUSH_TIMEOUT_MS = 750L
}
