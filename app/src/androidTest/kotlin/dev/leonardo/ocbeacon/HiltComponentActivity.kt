package dev.leonardo.ocbeacon

import androidx.activity.ComponentActivity

/**
 * 2026-08-16（#147）：androidTest 的 Compose 宿主 Activity。
 *
 * 背景：HiltTestRunner（HiltTestApplication）下裸 ComponentActivity 经
 * v1 createComposeRule 启动失败 → "No compose hierarchies"。本类提供稳定
 * 宿主；**不加 @AndroidEntryPoint**——纯 UI 组件测试无需注入，加注解则
 * 要求每个测试声明 HiltAndroidRule（对 20+ 组件测试是负担）。
 * 需要注入的测试（ViewModel 级）继续用 HiltAndroidRule + 专属 Test Activity。
 */
class HiltComponentActivity : ComponentActivity() {
    // 不 setContent——内容由测试规则（composeTestRule.setContent { }）注入
    //（Activity 预置内容会与规则冲突：has already set content）
}
