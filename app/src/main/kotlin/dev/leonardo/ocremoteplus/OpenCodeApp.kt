package dev.leonardo.ocremoteplus

import android.app.Application
import android.os.Build
import android.os.Environment
import android.util.Log
import android.widget.Toast
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.HiltAndroidApp
import dagger.hilt.components.SingletonComponent
import dev.leonardo.ocremoteplus.data.repository.DiagnosticLogRepository
import dev.leonardo.ocremoteplus.logging.AppLogger
import dev.leonardo.ocremoteplus.service.SessionFocusHolder
import dev.leonardo.ocremoteplus.util.DebugLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val TAG = "CrashLogger"
private const val CRASH_DIR = "oc_remote_crash"
private const val MAX_LOG_FILES = 10

/**
 * OC Remote Application
 * Entry point for Hilt dependency injection
 */
@HiltAndroidApp
class OpenCodeApp : Application() {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()

        DebugLogger.init(this)

        // ---- Initialize persistent diagnostic logging ----
        val diagnosticRepo = EntryPointAccessors.fromApplication(
            this,
            DiagnosticLogEntryPoint::class.java,
        ).diagnosticLogRepository()
        AppLogger.initialize(diagnosticRepo)
        appScope.launch { diagnosticRepo.initialize() }

        val crashDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), CRASH_DIR)

        // ---- Global uncaught exception handler ----
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            // Persist crash to diagnostic database before the process dies
            runCatching { AppLogger.recordCrash(thread, throwable) }
            try {
                crashDir.mkdirs()
                val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                val logFile = File(crashDir, "crash_${timestamp}.txt")
                logFile.writeText(buildString {
                    append("App: ${packageName} (${BuildConfig.VERSION_NAME})\n")
                    append("Android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})\n")
                    append("Device: ${Build.MANUFACTURER} ${Build.MODEL}\n")
                    append("Time: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())}\n")
                    append("Thread: $thread\n")
                    append("Exception: ${throwable.javaClass.name}\n")
                    append("Message: ${throwable.message}\n\n")
                    append("--- Stack Trace ---\n")
                    append(StringWriter().also { throwable.printStackTrace(java.io.PrintWriter(it)) }.toString())

                    var cause = throwable.cause
                    var depth = 1
                    while (cause != null && depth < 5) {
                        append("\n--- Cause $depth ---\n")
                        append("Exception: ${cause.javaClass.name}\n")
                        append("Message: ${cause.message}\n")
                        append(StringWriter().also { cause.printStackTrace(java.io.PrintWriter(it)) }.toString())
                        cause = cause.cause
                        depth++
                    }
                })

                // Prune old logs, keep only the newest MAX_LOG_FILES
                crashDir.listFiles()
                    ?.filter { it.name.startsWith("crash_") && it.name.endsWith(".txt") }
                    ?.sortedByDescending { it.name }
                    ?.drop(MAX_LOG_FILES)
                    ?.forEach { it.delete() }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to write crash log", e)
            }

            // Restart main Activity with crash info
            try {
                val intent = packageManager.getLaunchIntentForPackage(packageName)?.apply {
                    addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK)
                    putExtra("crash_occurred", true)
                    putExtra("crash_message", throwable.message ?: "Unknown error")
                    putExtra("crash_exception", throwable.javaClass.simpleName)
                }
                if (intent != null) {
                    startActivity(intent)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to restart activity after crash", e)
            }

            defaultHandler?.uncaughtException(thread, throwable)
        }

        // ---- Notify user on next launch if crash logs exist ----
        val hasUnreadCrash = crashDir.listFiles()
            ?.any { it.name.startsWith("crash_") && it.name.endsWith(".txt") } == true
        if (hasUnreadCrash) {
            Toast.makeText(this, "崩溃日志在 Download/$CRASH_DIR/", Toast.LENGTH_LONG).show()
        }

        // Track app foreground/background for notification suppression
        val focusHolder = EntryPointAccessors.fromApplication(
            this,
            SessionFocusEntryPoint::class.java
        ).sessionFocusHolder()

        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                focusHolder.setAppInForeground(true)
            }

            override fun onStop(owner: LifecycleOwner) {
                focusHolder.setAppInForeground(false)
            }
        })
    }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface SessionFocusEntryPoint {
    fun sessionFocusHolder(): SessionFocusHolder
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface DiagnosticLogEntryPoint {
    fun diagnosticLogRepository(): DiagnosticLogRepository
}
