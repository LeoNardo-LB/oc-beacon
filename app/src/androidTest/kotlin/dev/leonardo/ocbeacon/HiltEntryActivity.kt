package dev.leonardo.ocbeacon

import android.content.Context
import android.content.res.Configuration
import androidx.activity.ComponentActivity
import dagger.hilt.android.AndroidEntryPoint
import java.util.Locale

/**
 * 2026-08-16（#147）：androidTest 的 Hilt 注入宿主 Activity。
 *
 * 用于 ViewModel 级集成测试（chat.* 族——setContent 内 ChatScreen 调
 * hiltViewModel()，需要 Hilt entry point 提供ViewModelFactory）。
 * 使用方必须同时声明 HiltAndroidRule 并在 @Before 中 inject()。
 *
 * 与 [HiltComponentActivity]（非 entrypoint，纯组件测试）分工——
 * @AndroidEntryPoint Activity 若无 HiltAndroidRule 会报
 * "The component was not created"，纯组件测试不需要这个负担。
 *
 * #210（2026-08-24）：强制 en-US 测试 locale。BaseChatTest 族断言英文资源串
 * （"Stop" / "Permission Required" / "Awaiting your reply"…），而本 Activity 不经过
 * MainActivity.attachBaseContext 的 applyAppLanguage，locale 完全跟随系统——
 * 2026-08-18 通过时系统 locale 恰为 en-US，2026-08-24 系统为 zh-CN 时整类
 * ComposeTimeoutException。包装 base context 使断言与设备语言解耦。
 */
@AndroidEntryPoint
class HiltEntryActivity : ComponentActivity() {

    override fun attachBaseContext(newBase: Context) {
        val locale = Locale("en", "US")
        Locale.setDefault(locale)
        val config = Configuration(newBase.resources.configuration)
        config.setLocale(locale)
        super.attachBaseContext(newBase.createConfigurationContext(config))
    }
}
