package dev.leonardo.ocbeacon

import android.content.Context
import android.content.res.Configuration
import androidx.activity.ComponentActivity
import java.util.Locale

/**
 * 2026-08-16（#147）：androidTest 的 Compose 宿主 Activity。
 *
 * 背景：HiltTestRunner（HiltTestApplication）下裸 ComponentActivity 经
 * v1 createComposeRule 启动失败 → "No compose hierarchies"。本类提供稳定
 * 宿主；**不加 @AndroidEntryPoint**——纯 UI 组件测试无需注入，加注解则
 * 要求每个测试声明 HiltAndroidRule（对 20+ 组件测试是负担）。
 * 需要注入的测试（ViewModel 级）继续用 HiltAndroidRule + 专属 Test Activity。
 *
 * #211（2026-08-24）：强制 en-US 测试 locale（与 [HiltEntryActivity] 的 #210
 * 修法一致，镜像生产 LocaleUtils.applyAppLanguage 的 attachBaseContext 模式）。
 * 组件/屏幕测试大量断言英文资源串（"Search sessions..." / "Archived" /
 * "Unable to connect" / "Compressing context…" / "Input"/"Output"…），而测试
 * Activity 的资源解析完全跟随系统——2026-08-18 全绿时系统恰为 en-US，
 * 系统回 zh-CN 后英文断言整类 ComposeTimeout。包装 base context 使断言与
 * 设备语言解耦；无持久状态，无需恢复/清理。
 */
class HiltComponentActivity : ComponentActivity() {
    // 不 setContent——内容由测试规则（composeTestRule.setContent { }）注入
    //（Activity 预置内容会与规则冲突：has already set content）

    override fun attachBaseContext(newBase: Context) {
        val locale = Locale("en", "US")
        Locale.setDefault(locale)
        val config = Configuration(newBase.resources.configuration)
        config.setLocale(locale)
        super.attachBaseContext(newBase.createConfigurationContext(config))
    }
}
