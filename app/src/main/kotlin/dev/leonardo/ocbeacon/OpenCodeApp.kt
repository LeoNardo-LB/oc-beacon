package dev.leonardo.ocbeacon

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
import dev.leonardo.ocbeacon.data.repository.DiagnosticLogRepository
import dev.leonardo.ocbeacon.logging.AppLogger
import dev.leonardo.ocbeacon.service.SessionFocusHolder
import dev.leonardo.ocbeacon.util.DebugLogger
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
private const val CRASH_DIR = "oc_beacon_crash"
private const val MAX_LOG_FILES = 10

/**
 * OC Beacon Application
 * Hilt 依赖注入的入口
 */
@HiltAndroidApp
class OpenCodeApp : Application() {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()

        DebugLogger.init(this)

        // ---- 初始化持久化诊断日志 ----
        val diagnosticRepo = EntryPointAccessors.fromApplication(
            this,
            DiagnosticLogEntryPoint::class.java,
        ).diagnosticLogRepository()
        AppLogger.initialize(diagnosticRepo)
        appScope.launch { diagnosticRepo.initialize() }
        AppLogger.i("App", "OC Beacon ${BuildConfig.VERSION_NAME} (code ${BuildConfig.VERSION_CODE}) started")

        val crashDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), CRASH_DIR)

        // ---- 全局未捕获异常处理器 ----
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            // 在进程死亡前将崩溃持久化到诊断数据库
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

                // 清理旧日志，仅保留最新的 MAX_LOG_FILES 个
                crashDir.listFiles()
                    ?.filter { it.name.startsWith("crash_") && it.name.endsWith(".txt") }
                    ?.sortedByDescending { it.name }
                    ?.drop(MAX_LOG_FILES)
                    ?.forEach { it.delete() }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to write crash log", e)
            }

            // 携带崩溃信息重启主 Activity
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

        // ---- 若存在崩溃日志，则在下次启动时通知用户 ----
        val hasUnreadCrash = crashDir.listFiles()
            ?.any { it.name.startsWith("crash_") && it.name.endsWith(".txt") } == true
        if (hasUnreadCrash) {
            Toast.makeText(this, "崩溃日志在 Download/$CRASH_DIR/", Toast.LENGTH_LONG).show()
        }

        // 跟踪应用前台/后台状态，用于通知抑制
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
