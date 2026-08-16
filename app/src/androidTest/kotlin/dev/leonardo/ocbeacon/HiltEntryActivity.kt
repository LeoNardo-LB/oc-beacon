package dev.leonardo.ocbeacon

import androidx.activity.ComponentActivity
import dagger.hilt.android.AndroidEntryPoint

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
 */
@AndroidEntryPoint
class HiltEntryActivity : ComponentActivity()
