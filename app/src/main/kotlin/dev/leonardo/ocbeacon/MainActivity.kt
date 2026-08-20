package dev.leonardo.ocbeacon

import dev.leonardo.ocbeacon.logging.AppLogger

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.LocalOverscrollFactory
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import dev.leonardo.ocbeacon.domain.repository.SettingsRepository
import dev.leonardo.ocbeacon.domain.repository.ServerRepository
import dev.leonardo.ocbeacon.domain.repository.SessionRepository
import dev.leonardo.ocbeacon.domain.model.AppSettings
import dev.leonardo.ocbeacon.domain.model.DebugProfile
import dev.leonardo.ocbeacon.domain.model.ServerConfig
import dev.leonardo.ocbeacon.service.OpenCodeConnectionService
import dev.leonardo.ocbeacon.util.applyAppLanguage
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first
import androidx.core.content.ContextCompat
import java.util.UUID
import dev.leonardo.ocbeacon.ui.navigation.NavGraph
import dev.leonardo.ocbeacon.ui.theme.OpenCodeTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import java.util.Locale
import javax.inject.Inject
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import androidx.compose.material3.windowsizeclass.WindowSizeClass

private const val TAG = "MainActivity"

/**
 * 来自通知点击的待处理 deep-link 信息。
 * NavGraph 读取此信息并导航到 WebView，带上正确的会话 URL。
 */
data class SessionDeepLink(
    val serverId: String = "",
    val sessionPath: String,  // 例如 /L2hvbWUv.../session/abc123
    val sessionId: String = "" // 原始会话 ID（当 sessionPath 为空时回退使用）
)

/**
 * 主 Activity —— 基于 Jetpack Compose 的单 Activity 架构
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    
    @Inject
    lateinit var settingsRepository: SettingsRepository

    @Inject
    lateinit var serverRepository: ServerRepository

    @Inject
    lateinit var sessionRepository: SessionRepository

    @Inject
    lateinit var fileRepository: dev.leonardo.ocbeacon.domain.repository.FileRepository
    
    /**
     * 用于通知点击产生的 deep-link 事件的 SharedFlow。
     * NavGraph 订阅并在发射值时导航到目标会话。
     * 使用 replay=1，确保在 NavGraph 开始收集之前的冷启动 deep-link 不会丢失。
     */
    private val _deepLinkFlow = MutableSharedFlow<SessionDeepLink>(replay = 1)

    /**
     * #132 调试通道：外部参数激活（am start --es debug_profile <id>）后携带
     * serverId 的导航事件。NavGraph 订阅并直达会话列表。
     * replay=1 保证冷启动（NavGraph 尚未收集）时不丢失。
     */
    private val _debugChannelNavFlow = MutableSharedFlow<String>(replay = 1)

    /**
     * 用于通过 ACTION_SEND / ACTION_SEND_MULTIPLE 接收图片的 SharedFlow。
     * NavGraph / ChatScreen 消费这些 URI 以预填附件。
     * 使用 replay=1，确保较晚的订阅者（分享后打开的 ChatScreen）仍能拿到 URI。
     */
    private val _sharedImagesFlow = MutableSharedFlow<List<Uri>>(replay = 1)
    val sharedImagesFlow = _sharedImagesFlow.asSharedFlow()

    /** 通过 attachBaseContext 为本 Activity 实例应用的语言代码。 */
    private var appliedLanguage: String = ""

    // 终端屏幕使用的可选按键拦截器（例如通过音量键实现的虚拟 CTRL/FN）。
    private var terminalKeyInterceptor: ((KeyEvent) -> Boolean)? = null

    // 2026-08-20 第三轮：开发用性能监测（am start --ez debug_perf true 开启，
    // 仅 debug 构建；release 恒 null——监测器代码虽打进包但永不 attach，零开销）。
    private var perfMonitor: dev.leonardo.ocbeacon.debug.ChatPerfMonitor? = null
    // 2026-08-20 观察者效应：优先独立 overlay window HUD（不污染被测窗口帧流）；
    // 无 SYSTEM_ALERT_WINDOW 授权时回退同窗口 Compose HUD 并引导授权一次
    private var perfHudOverlay: dev.leonardo.ocbeacon.debug.PerfHudOverlay? = null

    fun setTerminalKeyInterceptor(interceptor: ((KeyEvent) -> Boolean)?) {
        terminalKeyInterceptor = interceptor
    }

    // RestrictedApi（#106 lint 清偿）：ComponentActivity.dispatchKeyEvent 标记为
    // restricted（库组内 API）——此处是有意覆盖：终端模式音量键虚拟 CTRL/FN 拦截
    // 必须在 Activity 分发层截获（2026-08 终端按键方案），无公开替代 API。
    @Suppress("RestrictedApi")
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (terminalKeyInterceptor?.invoke(event) == true) {
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun attachBaseContext(newBase: Context) {
        // 同步从 SharedPreferences 读取存储的语言（无需 Hilt）。
        // D2-L20：语言应用逻辑与 OpenCodeConnectionService 共享 [applyAppLanguage]。
        // StrictMode ②：统一经 readStoredLanguagePermitted（设计读显式声明）。
        appliedLanguage = dev.leonardo.ocbeacon.util.readStoredLanguagePermitted(newBase)
        super.attachBaseContext(newBase.applyAppLanguage())
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }

        // 在初始值之后监听语言变化——drop(1) 跳过
        // attachBaseContext 已经应用的当前值，因此只有当用户
        // 在 Settings 中真正切换语言时才会 recreate。
        lifecycleScope.launch {
            settingsRepository.getSettingsFlow().map { it.appLanguage }.drop(1).collect { languageCode ->
                if (languageCode != appliedLanguage) {
                    recreate()
                }
            }
        }
        
        // 处理启动 Activity 的通知点击
        handleSessionIntent(intent)
        // 处理启动 Activity 的图片分享
        handleShareIntent(intent)
        // #132 调试通道：外部参数直达（debug 构建专用）
        handleDebugProfileIntent(intent)

        // 2026-08-20 竞态取证埋点（debug_race extra；release 也生效——概率 bug
        // 需在用户日常环境复现取证，故不设 BuildConfig.DEBUG 门）
        if (intent?.getBooleanExtra("debug_race", false) == true) {
            dev.leonardo.ocbeacon.debug.RaceProbe.isEnabled = true
            AppLogger.i(TAG, "RaceProbe enabled")
        }

        // 2026-08-20 性能监测 HUD（debug_perf extra；仅 debug）
        if (BuildConfig.DEBUG && intent?.getBooleanExtra("debug_perf", false) == true) {
            val refresh = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                window.context.display?.refreshRate ?: 120f
            } else {
                @Suppress("DEPRECATION") windowManager.defaultDisplay.refreshRate
            }
            perfMonitor = dev.leonardo.ocbeacon.debug.ChatPerfMonitor(refresh).also {
                it.attach(window)
                AppLogger.i(TAG, "PerfMon attached: refresh=${refresh}Hz budget=${1000f / refresh}ms")
            }
            val overlay = dev.leonardo.ocbeacon.debug.PerfHudOverlay(applicationContext)
            perfHudOverlay = overlay
            if (overlay.isAvailable()) {
                overlay.show()
                AppLogger.i(TAG, "PerfMon HUD: overlay window（隔离帧流）")
            } else {
                // 引导授权一次（MIUI 系统设置页）；本次回退同窗口 HUD
                runCatching {
                    startActivity(android.content.Intent(
                        android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        android.net.Uri.parse("package:$packageName"),
                    ))
                }
                AppLogger.i(TAG, "PerfMon HUD: 无悬浮窗权限，回退同窗口（授权后重启生效）")
            }
        }
        
        @OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
        setContent {
            val windowSizeClass = calculateWindowSizeClass(this)
            // 收集主题偏好
            val settings by settingsRepository.getSettingsFlow().collectAsStateWithLifecycle(initialValue = AppSettings())
            val appTheme = settings.appTheme
            val dynamicColor = settings.dynamicColor
            val amoledDark = settings.amoledDark
            
            // 判断是否应使用深色主题
            val systemDarkTheme = isSystemInDarkTheme()
            val darkTheme = when (appTheme) {
                "light" -> false
                "dark" -> true
                else -> systemDarkTheme
            }
            
            OpenCodeTheme(darkTheme = darkTheme, dynamicColor = dynamicColor, amoledDark = amoledDark) {
                
                // 全局禁用 Stretch overscroll 拉伸效果（Android 12+ 默认）。
                // 拉伸动画会拦截输入导致"拉伸中无法反向滑动"的卡手体感（2026-08-10 真机实证）。
                // 提供 null = 无 overscroll 效果（官方支持：LocalOverscrollFactory 为 null 时返回 null）。
                CompositionLocalProvider(LocalOverscrollFactory provides null) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    // 2026-08-20 性能 HUD：Box 叠加层（debug_perf 开启时才有），
                    // top-start 半透明两行小字，无 pointer 修饰符不拦截触摸
                    Box(modifier = Modifier.fillMaxSize()) {
                        NavGraph(
                            windowSizeClass = windowSizeClass,
                            deepLinkFlow = _deepLinkFlow,
                            debugChannelFlow = _debugChannelNavFlow,
                            sharedImagesFlow = sharedImagesFlow,
                            settingsRepository = settingsRepository,
                            serverRepository = serverRepository,
                            sessionRepository = sessionRepository,
                            fileRepository = fileRepository
                        )
                        perfMonitor?.let { mon ->
                            val overlay = perfHudOverlay
                            if (overlay != null && overlay.isAvailable()) {
                                // 独立窗口 HUD：组合层只挂数据桥（overlay 自身零 Compose 开销）
                                androidx.compose.runtime.LaunchedEffect(mon) {
                                    snapshotFlow { mon.hud.value }.collect { overlay.update(it) }
                                }
                            } else {
                                dev.leonardo.ocbeacon.debug.PerfHud(
                                    hud = mon.hud,
                                    modifier = Modifier
                                        .align(Alignment.TopStart)
                                        .statusBarsPadding(),
                                )
                            }
                        }
                    }
                }
                }
            }
        }
    }
    
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // 当 Activity 已在运行时处理通知点击
        handleSessionIntent(intent)
        // 当 Activity 已在运行时处理图片分享
        handleShareIntent(intent)
        // #132 调试通道：外部参数直达（debug 构建专用）
        handleDebugProfileIntent(intent)
    }
    
    private fun handleSessionIntent(intent: Intent?) {
        if (intent?.action != OpenCodeConnectionService.ACTION_OPEN_SESSION) return

        val serverId = intent.getStringExtra(OpenCodeConnectionService.EXTRA_SERVER_ID) ?: return
        val sessionPath = intent.getStringExtra(OpenCodeConnectionService.EXTRA_SESSION_PATH) ?: ""
        val sessionId = intent.getStringExtra(OpenCodeConnectionService.EXTRA_SESSION_ID) ?: ""

        AppLogger.i(TAG, "Session deep-link: serverId=$serverId sessionPath=$sessionPath (sessionId=$sessionId)")

        _deepLinkFlow.tryEmit(
            SessionDeepLink(
                serverId = serverId,
                sessionPath = sessionPath,
                sessionId = sessionId
            )
        )
    }

    /**
     * 处理带图片内容的 ACTION_SEND 和 ACTION_SEND_MULTIPLE。
     * 提取图片 URI 并通过 [sharedImagesFlow] 发射。
     * 这些 URI 是 content:// URI，只要 Activity 存活就可读。
     */
    private fun handleShareIntent(intent: Intent?) {
        if (intent == null) return

        val uris = mutableListOf<Uri>()

        when (intent.action) {
            Intent.ACTION_SEND -> {
                if (intent.type?.startsWith("image/") == true) {
                    val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(Intent.EXTRA_STREAM)
                    }
                    uri?.let { uris.add(it) }
                }
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                if (intent.type?.startsWith("image/") == true) {
                    val list = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM)
                    }
                    list?.let { uris.addAll(it) }
                }
            }
            else -> return
        }

        if (uris.isNotEmpty()) {
            // 获取可持久化的读权限，使 URI 能在配置变更后继续使用
            for (uri in uris) {
                try {
                    contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                } catch (e: SecurityException) {
                    // 并非所有 provider 都支持可持久化的权限——没关系，
                    // 分享 intent 授予的临时权限仍然有效。
                }
            }
            AppLogger.i(TAG, "Received \${uris.size} shared image(s)")
            _sharedImagesFlow.tryEmit(uris)
        }
    }

    /**
     * #132 调试通道：外部参数一键直达（仅 debug 构建）。
     *
     * 用法（完整参数方式，任意服务器无需改代码）：
     * adb shell am start -n <pkg>/.MainActivity \
     *   --es debug_url http://192.168.110.53:4199 \
     *   --es debug_username opencode \
     *   --es debug_password <pwd> \
     *   --es debug_name "V2 Real"
     *
     * 行为：幂等保存服务器 → 版本探测 → 连接 → 直达该服务器会话列表。
     */
    private fun handleDebugProfileIntent(intent: Intent?) {
        if (!BuildConfig.DEBUG) return
        val url = intent?.getStringExtra("debug_url") ?: return
        val profile = DebugProfile(
            id = "ext-" + url.hashCode().toString(16),
            label = intent.getStringExtra("debug_name") ?: "Debug External",
            url = url,
            username = intent.getStringExtra("debug_username") ?: "opencode",
            password = intent.getStringExtra("debug_password") ?: ""
        )
        AppLogger.i(TAG, "Debug channel requested via extra: " + profile.id + " (" + profile.url + ")")
        activateDebugProfile(profile)
    }

    /**
     * #132 调试通道激活：幂等保存 → 版本探测 → 连接 → 直达会话列表。
     * 供套餐方式与完整参数方式共用。
     */
    private fun activateDebugProfile(profile: DebugProfile) {
        lifecycleScope.launch {
            try {
                val existing = serverRepository.getServersFlow().first()
                    .firstOrNull {
                        ServerConfig.sameBackend(it.url, it.username, profile.url, profile.username)
                    }
                val serverId: String
                if (existing != null) {
                    serverId = existing.id
                    // 密码为空（构建时未注入 OCB_DEBUG_PWD）时保留已有密码——
                    // 幂等复用不得破坏用户已配置的正确凭据（2026-08-14 真机 401 根因）。
                    serverRepository.updateServer(
                        existing.copy(
                            name = profile.label,
                            url = profile.url.trimEnd('/'),
                            username = profile.username,
                            password = profile.password.ifEmpty { existing.password },
                            autoConnect = true
                        )
                    )
                } else {
                    serverId = UUID.randomUUID().toString()
                    serverRepository.addServer(
                        ServerConfig(
                            id = serverId,
                            url = profile.url.trimEnd('/'),
                            username = profile.username,
                            password = profile.password,
                            name = profile.label,
                            autoConnect = true,
                        )
                    )
                }
                // 版本探测（#132 联动）：激活前校验并修正 apiVersion——
                // 探测失败返回 UNKNOWN 时 checkHealth 保留原值，不会把 V2 降级 V1。
                // 不依赖结果（探测失败仍尝试连接，由服务/SSE 层兜底）。
                val refreshed = serverRepository.getServer(serverId)
                if (refreshed != null) {
                    serverRepository.testConnection(refreshed).getOrNull()
                }
                val serviceIntent = Intent(this@MainActivity, OpenCodeConnectionService::class.java).apply {
                    putExtra("server_id", serverId)
                }
                try {
                    ContextCompat.startForegroundService(this@MainActivity, serviceIntent)
                } catch (e: Exception) {
                    AppLogger.w(TAG, "Debug channel: FGS start failed (will auto-connect next launch): " + e.message)
                }
                AppLogger.i(TAG, "Debug channel activated: " + profile.id + " -> server " + serverId)
                _debugChannelNavFlow.tryEmit(serverId)
            } catch (ce: kotlinx.coroutines.CancellationException) {
                throw ce
            } catch (e: Exception) {
                AppLogger.e(TAG, "Debug channel activation failed: " + e.message, e)
            }
        }
    }

}
