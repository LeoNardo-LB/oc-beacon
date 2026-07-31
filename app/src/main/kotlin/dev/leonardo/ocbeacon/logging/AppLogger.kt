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
 * Global application logger — bridges [android.util.Log] (logcat) with a persistent
 * diagnostic database via [DiagnosticLogRepository].
 *
 * Writes are enqueued into a bounded [Channel] (capacity 500, [BufferOverflow.DROP_OLDEST])
 * and drained by a single consumer coroutine that batches up to 50 entries per flush.
 * The [minimumLevel] controls which levels are persisted; entries below the threshold
 * are still sent to logcat but skipped for persistence.
 *
 * Crash capture: [recordCrash] enqueues a `FATAL` entry and synchronously flushes
 * via [runBlocking] so the crash is persisted before the process dies.
 */
object AppLogger {
    private sealed interface WriterCommand {
        data class Entry(val value: DiagnosticLogEntry) : WriterCommand
        data class Flush(val completion: CompletableDeferred<Boolean>) : WriterCommand
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val droppedEntries = AtomicLong(0)
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
     * Bind the persistent [repository] and start the background consumer.
     * Also subscribes to [DiagnosticLogRepository.logLevel] so that [minimumLevel]
     * tracks the user-selected persistence threshold.
     *
     * Safe to call once; subsequent calls are no-ops.
     */
    fun initialize(repository: DiagnosticLogRepository) {
        if (this.repository != null) return
        this.repository = repository
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
     * Record an uncaught exception as a `FATAL` entry and synchronously flush
     * so it is persisted before the process terminates.
     */
    fun recordCrash(thread: Thread, error: Throwable) {
        val details = throwableDetails(error) + ("thread" to thread.name)
        val entry = DiagnosticLogEntry(
            timestamp = System.currentTimeMillis(),
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

    /** Flush pending entries, waiting up to [timeoutMillis] for completion. */
    suspend fun flush(timeoutMillis: Long = 2_000L): Boolean {
        val completion = CompletableDeferred<Boolean>()
        queue.send(WriterCommand.Flush(completion))
        return withTimeoutOrNull(timeoutMillis) { completion.await() } ?: false
    }

    fun droppedEntryCount(): Long = droppedEntries.get()

    // ---- Internals --------------------------------------------------------

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
                    timestamp = System.currentTimeMillis(),
                    level = level,
                    category = tag,
                    message = message,
                    details = error?.let(::throwableDetails).orEmpty(),
                ),
            ),
        )
        return result
    }

    private fun shouldPersist(level: String): Boolean {
        val priorities = mapOf("ERROR" to 0, "WARN" to 1, "INFO" to 2, "DEBUG" to 3)
        return (priorities[level] ?: 0) <= (priorities[minimumLevel] ?: 2)
    }

    private fun throwableDetails(error: Throwable): Map<String, String> {
        return buildMap {
            put("exception", error::class.java.name)
            error.cause?.let { put("cause", "${it::class.java.name}: ${it.message.orEmpty()}") }
            put("stack", AndroidLog.getStackTraceString(error).lineSequence().take(12).joinToString("\n"))
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
