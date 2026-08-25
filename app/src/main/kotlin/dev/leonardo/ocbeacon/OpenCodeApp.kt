package dev.leonardo.ocbeacon

import android.app.Application
import android.content.ComponentCallbacks2
import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.StrictMode
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
import dev.leonardo.ocbeacon.data.repository.SettingsDataStore
import kotlin.concurrent.thread
import dev.leonardo.ocbeacon.logging.AppLogger
import dev.leonardo.ocbeacon.service.SessionFocusHolder
import dev.leonardo.ocbeacon.util.DebugLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import java.io.StringWriter
import dev.leonardo.ocbeacon.util.DateFormatters
import java.util.Date

private const val TAG = "CrashLogger"
private const val CRASH_DIR = "oc_beacon_crash"
private const val MAX_LOG_FILES = 10
/** #115（D2-17）：崩溃重启退避窗口（10 分钟内最多重启 1 次，防确定性崩溃死循环）。 */
private const val CRASH_RESTART_BACKOFF_MS = 10 * 60 * 1000L

/**
 * 崩溃日志目录：优先外部 Download（用户可直接访问）；不可访问时
 *（旧 uid 遗留目录权限锁死 / 外部存储不可用）fallback 应用私有目录。
 * 崩溃写入与启动提示统一走此函数，保证日志不丢失、提示可检测。
 */
private fun Application.crashLogDir(): File {
    val external = File(
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
        CRASH_DIR
    )
    return if (external.canWrite() || external.mkdirs()) {
        external
    } else {
        File(filesDir, CRASH_DIR)
    }
}

/**
 * OC Beacon Application
 * Hilt 依赖注入的入口
 */
@HiltAndroidApp
class OpenCodeApp : Application() {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * StrictMode ②（2026-08-19）：预热语言镜像 SharedPreferences。
     * MainActivity/OpenCodeConnectionService 的 attachBaseContext 需同步读
     * locale_prefs（#136 有意设计）；冷启动首读会加载 XML——在最早的生命周期
     * 点（早于 Application.onCreate / ContentProvider / Activity attach）用
     * 后台线程触发加载，让后续主线程同步读命中已加载内存。
     */
    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        thread(name = "locale-prefs-warmup", isDaemon = true) {
            runCatching { SettingsDataStore.getStoredLanguage(base) }
        }
    }

    override fun onCreate() {
        super.onCreate()

        DebugLogger.init(this)

        // ---- #106-2 工具链治理：StrictMode（仅 debug 构建启用）----
        // 自动捕获主线程 IO（#102 M-2 / #103 M-5 类）与未关闭资源/Activity 泄漏，
        // 违规仅记录 logcat（tag=StrictMode），不启用 death penalty 防误杀开发构建。
        // 注：不检测 cleartext 网络——本应用连接 LAN http 服务器是合法使用场景，
        // 检测只会对每个请求刷无意义违规日志。
        if (BuildConfig.DEBUG) {
            StrictMode.setThreadPolicy(
                StrictMode.ThreadPolicy.Builder()
                    .detectAll()
                    .penaltyLog()
                    .build()
            )
            StrictMode.setVmPolicy(
                StrictMode.VmPolicy.Builder()
                    .detectActivityLeaks()
                    .detectLeakedClosableObjects()
                    .detectLeakedSqlLiteObjects()
                    .penaltyLog()
                    .build()
            )
            AppLogger.i("App", "StrictMode enabled (debug): thread=detectAll+log, vm=activityLeaks/closable/sqlLite+log")
        }

        // ---- 初始化持久化诊断日志 ----
        val diagnosticRepo = EntryPointAccessors.fromApplication(
            this,
            DiagnosticLogEntryPoint::class.java,
        ).diagnosticLogRepository()
        AppLogger.initialize(diagnosticRepo)
        appScope.launch { diagnosticRepo.initialize() }
        AppLogger.i("App", "OC Beacon ${BuildConfig.VERSION_NAME} (code ${BuildConfig.VERSION_CODE}) started")

        // ---- #136（D2-L56）：语言镜像启动校验（双写窗口崩溃 → 语言漂移收敛）----
        appScope.launch {
            runCatching {
                EntryPointAccessors.fromApplication(this@OpenCodeApp, SettingsEntryPoint::class.java)
                    .settingsDataStore().reconcileLanguageMirror()
            }.onFailure { AppLogger.e(TAG, "Language mirror reconciliation failed", it) }
        }

        // ---- #228（炸弹清扫）：全库删除 SSE started 残留的空 Text/Reasoning part ----
        // #223 时代的 Room 历史炸弹行（实测单消息 4488 个空 reasoning part）随会话
        // 种子回灌热视图 → dedup O(N²) 主线程 HANG（用户报「点会话加载很久+页面乱」）。
        // 增殖源头已在 merge 入口双向堵住；此处一次性清扫存量，幂等（后续运行为 0 删）。
        appScope.launch {
            runCatching {
                val deleted = EntryPointAccessors.fromApplication(
                    this@OpenCodeApp,
                    MessageCacheEntryPoint::class.java,
                ).messageCacheRepository().sweepEmptyStreamParts()
                if (deleted > 0) {
                    AppLogger.i("App", "#228 swept $deleted empty stream parts (SSE started residue)")
                }
            }.onFailure { AppLogger.e(TAG, "Empty stream part sweep failed", it) }
        }

        // ---- 全局未捕获异常处理器 ----
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            // 在进程死亡前将崩溃持久化到诊断数据库
            runCatching { AppLogger.recordCrash(thread, throwable) }
            try {
                val crashDir = crashLogDir()
                crashDir.mkdirs()
                // #133（D2-L27）：毫秒级时间戳——原秒级分辨率同秒两次崩溃互相覆盖
                // （崩溃文件名唯一性；通知/清理解析端 yyyyMMdd_HHmmss 宽松解析前缀，兼容）
                val timestamp = DateFormatters.crashFileName().format(Date())
                val logFile = File(crashDir, "crash_${timestamp}.txt")
                logFile.writeText(buildString {
                    append("App: ${packageName} (${BuildConfig.VERSION_NAME})\n")
                    append("Android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})\n")
                    append("Device: ${Build.MANUFACTURER} ${Build.MODEL}\n")
                    append("Time: ${DateFormatters.crashDetail().format(Date())}\n")
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
                AppLogger.e(TAG, "Failed to write crash log", e)
            }

            // 携带崩溃信息重启主 Activity
            // #115（D2-17）：重启退避——确定性崩溃（如坏数据/环境）会立刻再次
            // 崩溃 → 无限重启死循环（07:26 先例只修了提示）。10 分钟内最多重启
            // 一次：首次崩溃尝试恢复，复崩溃只记录日志交给系统退出，防死循环。
            val now = System.currentTimeMillis()
            val crashPrefsForBackoff = getSharedPreferences("crash_restart", MODE_PRIVATE)
            val lastRestartTs = crashPrefsForBackoff.getLong("last_restart_ts", 0L)
            val canRestart = now - lastRestartTs > CRASH_RESTART_BACKOFF_MS
            if (canRestart) {
                crashPrefsForBackoff.edit().putLong("last_restart_ts", now).apply()
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
                    AppLogger.e(TAG, "Failed to restart activity after crash", e)
                }
            } else {
                AppLogger.e(TAG, "Crash within ${CRASH_RESTART_BACKOFF_MS}ms of last restart - backing off (no restart loop)", throwable)
            }

            defaultHandler?.uncaughtException(thread, throwable)
        }

        // ---- 若存在新崩溃日志（上次提示之后），则通知用户（2026-08-11 重构）----
        // 旧逻辑：只要有 crash 文件（含历史残留）每次启动都提示——07:26 崩溃循环
        // 遗留 14 个文件（其中 10 个属主为旧 uid，重装后 App 无权限删除/读取）导致
        // 用户每次启动都看到报错 Toast。改为：① SharedPreferences 记录上次提示时间，
        // 仅对更新的崩溃文件提示（"仅在真正发生崩溃时提示"，旧残留不再打扰）；
        // ② 崩溃目录 Download 不可访问时 fallback 应用私有目录（crashLogDir()）——
        // 旧 uid 遗留目录权限锁死场景下崩溃日志仍可写入与检测。
        // #137（D2-L63）：listFiles + 崩溃文件名解析（含 SimpleDateFormat 解析）移出
        // 主线程——外部目录（Download）在崩溃残留多时可致启动卡顿；IO 线程执行，
        // Toast（需主线程）在检测完成后切回主线程。
        appScope.launch {
            // StrictMode ③（2026-08-19）：prefs 读移入后台协程（原 onCreate 主线程
            // 读盘——该值仅在 IO 过滤崩溃文件时使用，无需主线程预读）
            val crashPrefs = getSharedPreferences("crash_notify", MODE_PRIVATE)
            val lastNotifiedCrashTs = crashPrefs.getLong("last_notified_ts", 0L)
            val newCrashFiles = withContext(Dispatchers.IO) {
                crashLogDir().listFiles()
                    ?.filter { it.name.startsWith("crash_") && it.name.endsWith(".txt") }
                    ?.filter { file ->
                        val name = file.name.removePrefix("crash_").removeSuffix(".txt")
                        runCatching {
                            DateFormatters.crashFileNameParse().parse(name)?.time ?: 0L
                        }.getOrDefault(0L) > lastNotifiedCrashTs
                    }
            }
            if (newCrashFiles?.isNotEmpty() == true) {
                // 2026-08-16 修复：Toast 必须主线程——原实现在 appScope（IO 调度器）
                // 直接 show → "Can't toast on a thread that has not called
                // Looper.prepare()" NPE 二次崩溃（migration 崩溃后重启链实测）。
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@OpenCodeApp, getString(R.string.crash_logs_dir, CRASH_DIR), Toast.LENGTH_LONG).show()
                }
                crashPrefs.edit().putLong("last_notified_ts", System.currentTimeMillis()).apply()
            }
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

    /**
     * #115（D2-16）：低内存分级清理——进程级可重建缓存（工具快照含整文件
     * 内容 MB 级）在系统内存压力时释放，降低 LMK 杀进程概率。
     * 仅清可重建缓存（ToolSnapshotCache 导航失败可重读）；不触碰持久状态。
     */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
            runCatching {
                EntryPointAccessors.fromApplication(this, CacheEntryPoint::class.java)
                    .toolSnapshotCache().clear()
                AppLogger.i("App", "onTrimMemory level=$level - cleared ToolSnapshotCache")
            }.onFailure { AppLogger.e("App", "onTrimMemory cleanup failed", it) }
        }
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

@EntryPoint
@InstallIn(SingletonComponent::class)
interface SettingsEntryPoint {
    /** #136（D2-L56）：语言镜像启动校验入口。 */
    fun settingsDataStore(): SettingsDataStore
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface MessageCacheEntryPoint {
    /** #228（炸弹清扫）：空 Text/Reasoning part 存量清扫入口。 */
    fun messageCacheRepository(): dev.leonardo.ocbeacon.domain.repository.MessageCacheRepository
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface CacheEntryPoint {
    /** #115（D2-16）：可重建缓存的低内存清理入口。 */
    fun toolSnapshotCache(): dev.leonardo.ocbeacon.domain.repository.ToolSnapshotCache
}
