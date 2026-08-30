import java.io.FileInputStream
import java.util.Properties

// 版本号唯一来源 —— 从 version.properties 读取
val versionPropsFile = rootProject.file("version.properties")
val versionProps = Properties().apply { load(FileInputStream(versionPropsFile)) }
val vCode = (versionProps["VERSION_CODE"] as String).toInt()
val vName = versionProps["VERSION_NAME"] as String

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.dagger.hilt.android")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.devtools.ksp")
}

android {
    namespace = "dev.leonardo.ocbeacon"
    compileSdk = 37

    defaultConfig {
        applicationId = "dev.leonardo.ocbeacon"
        minSdk = 26
        // Google Play 政策：2026-08-31 起新应用必须 target Android 16 (API 36) 或更高
        targetSdk = 36
        versionCode = vCode
        versionName = vName
        // #151 GitHub App device flow 凭据：从 local.properties 读取（GITHUB_APP_CLIENT_ID /
        // GITHUB_APP_CLIENT_SECRET）——凭据不进 git；缺失时空串 = 上报功能禁用态引导
        val localProps = Properties().apply {
            val f = rootProject.file("local.properties")
            if (f.exists()) load(FileInputStream(f))
        }
        val githubClientId = localProps.getProperty("GITHUB_APP_CLIENT_ID") ?: ""
        val githubClientSecret = localProps.getProperty("GITHUB_APP_CLIENT_SECRET") ?: ""
        buildConfigField("String", "GITHUB_APP_CLIENT_ID", "\"$githubClientId\"")
        buildConfigField("String", "GITHUB_APP_CLIENT_SECRET", "\"$githubClientSecret\"")

        testInstrumentationRunner = "dev.leonardo.ocbeacon.HiltTestRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    val hasPropertiesFile = File("app/keystore/signing.properties").exists()
    if (hasPropertiesFile) {
        val props = Properties()
        props.load(FileInputStream(file("keystore/signing.properties")))
        val alias = props["keystore.alias"] as String
        signingConfigs {
            create("release") {
                storeFile = file(props["keystore"] as String)
                storePassword = props["keystore.password"] as String
                keyAlias = alias
                keyPassword = props["keystore.password"] as String
            }
        }
        println("[Signature] -> Build will be signed with: $alias")
        buildTypes.getByName("release").signingConfig = signingConfigs.getByName("release")
    }

    // #259 debug 签名身份钉死：debug keystore 入库（非机密——密码为公开惯例
    // "android"，与 AGP 自动生成物无异；release.jks 仍 gitignore + CI Secrets）。
    // 2026-08-29 实证：XDG_CONFIG_HOME 有无使 AGP 解析到两把不同 debug 钥匙
    // （~/.config/.android 8f7a vs ~/.android 3fdd），同一项目跨构建上下文
    // 签名身份漂移，覆盖安装互斥 INSTALL_FAILED_UPDATE_INCOMPATIBLE。
    // app/keystore/debug.jks = XDG 正典副本（8f7a，现机在装身份，零重装）。
    val pinnedDebugKeystore = file("keystore/debug.jks")
    if (pinnedDebugKeystore.exists()) {
        signingConfigs {
            create("pinnedDebug") {
                storeFile = pinnedDebugKeystore
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
        buildTypes.getByName("debug").signingConfig = signingConfigs.getByName("pinnedDebug")
    }

    flavorDimensions += "flavor"

    productFlavors {
        create("dev") {
            dimension = "flavor"
            applicationIdSuffix = ".dev"
            manifestPlaceholders["appLabel"] = "OC Beacon Dev"
            // GitHub 分发渠道保留应用内自更新
            buildConfigField("boolean", "ENABLE_AUTO_UPDATE", "true")
            // 2026-08-13 用户决策：dev 测试构建 versionCode 用 Unix 时间戳——
            // 每次构建自动递增，adb install -r 可覆盖安装（保留 App 数据/服务器配置，
            // 禁止卸载重装）；正式版本号（version.properties）仅 beta/stable 使用。
            // 时间戳秒数 ~17.8 亿 < Int.MAX（21.4 亿），单调递增（不回拨时钟即可）。
            versionCode = (System.currentTimeMillis() / 1000L).toInt()
        }
        create("beta") {
            dimension = "flavor"
            applicationIdSuffix = ".beta"
            manifestPlaceholders["appLabel"] = "OC Beacon Beta"
            // GitHub 分发渠道保留应用内自更新
            buildConfigField("boolean", "ENABLE_AUTO_UPDATE", "true")
        }
        create("stable") {
            dimension = "flavor"
            manifestPlaceholders["appLabel"] = "@string/app_name"
            // Google Play 渠道：政策禁止 REQUEST_INSTALL_PACKAGES 自更新，禁用
            buildConfigField("boolean", "ENABLE_AUTO_UPDATE", "false")
        }
    }

    // #106-6 工具链治理：Android Lint 门禁——存量问题入 baseline（只卡新增），
    // abortOnError 显式声明（AGP 默认 true，这里自文档化）
    lint {
        baseline = file("lint-baseline.xml")
        abortOnError = true
    }

    buildTypes {
        debug {
            // 2026-08-20 实验结论（已回退）：R8 on debug（+去 LeakCanary）实测
            // S1 p50 9.56ms vs 无 R8 9.18ms——JIT/混淆不是中位帧成本主因，
            // 真实工作量在每帧 measure+draw（见 /tmp/perf-round2/s1r8.pftrace）
        }
        release {
            isMinifyEnabled = true
            // 签名修复：signing.properties 存在时使用 release keystore（由上方 if 块设置），
            // 仅在不存在时回退 debug 签名。此前无条件覆盖为 debug 导致
            // release keystore 永不生效、CI 每次构建 debug 签名不同 → 用户升级签名冲突。
            if (!hasPropertiesFile) {
                // #259：无 properties 时同样优先钉死身份（若 keystore 在库），
                // 消除「CI 每次构建 debug 签名不同」的源头
                signingConfig = signingConfigs.getByName(
                    if (pinnedDebugKeystore.exists()) "pinnedDebug" else "debug"
                )
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    kotlin {
        jvmToolchain(21)
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    testOptions {
        unitTests {
            isReturnDefaultValues = true
        }
    }
}

dependencies {
    // Android 核心
    implementation("androidx.core:core-ktx:1.19.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    implementation("androidx.lifecycle:lifecycle-process:2.11.0")
    implementation("androidx.activity:activity-compose:1.13.0")

    // Compose
    val composeBom = platform("androidx.compose:compose-bom:2026.05.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    // 2026-08-27 回退 BOM 稳定版 1.4.0：1.5.0-alpha26 按 ui 1.12-beta 编译，
    // 其 Surface/FAB 调用 ui 1.12 才有的 graphicsLayer 新签名（带 LayerOutsets）——
    // 与下方稳定组强制（ui/foundation 1.11.2）二进制冲突，滚动到底 FAB 重组即
    // NoSuchMethodError 崩溃（真机实证）。FAB 菜单改稳定 API 自绘（ChatFabMenu.kt）。
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3-window-size-class")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    // 2026-08-20 第二轮滚动卡顿：Perfetto 重组追踪（debug 直用合法，BOM 免版本）
    // ——compose:recompose 节点带上可读 scope 名，定位 S1 慢拖 63.5% 重组热点
    debugImplementation("androidx.compose.runtime:runtime-tracing")

    // #106-1 工具链治理：LeakCanary 泄漏检测（仅 debug 变体打包，release 零依赖零开销）
    // WebView/Activity/Fragment 泄漏首捕工具——#93 类问题的持续防线
    debugImplementation("com.squareup.leakcanary:leakcanary-android:2.14")

    // 导航
    implementation("androidx.navigation:navigation-compose:2.10.0")

    // Hilt 依赖注入
    implementation("com.google.dagger:hilt-android:2.60.1")
    ksp("com.google.dagger:hilt-compiler:2.60.1")
    // 提供 androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel（代码已全部迁移，无需 navigation 专用 API）
    implementation("androidx.hilt:hilt-lifecycle-viewmodel-compose:1.4.0")

    // Ktor 客户端（OkHttp 引擎，确保 SSE 流式传输的正确支持）
    val ktorVersion = "3.5.2"
    implementation("io.ktor:ktor-client-core:$ktorVersion")
    implementation("io.ktor:ktor-client-okhttp:$ktorVersion")
    implementation("io.ktor:ktor-client-content-negotiation:$ktorVersion")
    implementation("io.ktor:ktor-client-websockets:$ktorVersion")
    implementation("io.ktor:ktor-serialization-kotlinx-json:$ktorVersion")
    implementation("io.ktor:ktor-client-logging:$ktorVersion")
    implementation("io.ktor:ktor-client-auth:$ktorVersion")

    // ConnectBot 终端 —— 基于 libvterm 的终端模拟器（替代手写 ANSI 解析器）

    // Kotlinx 序列化
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")

    // 协程
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")

    // Markdown 渲染（mikepenz/multiplatform-markdown-renderer）
    val markdownRendererVersion = "0.43.0"
    implementation("com.mikepenz:multiplatform-markdown-renderer:$markdownRendererVersion")
    implementation("com.mikepenz:multiplatform-markdown-renderer-m3:$markdownRendererVersion")
    implementation("com.mikepenz:multiplatform-markdown-renderer-coil3:$markdownRendererVersion")

    // FileViewer 源码视图的语法高亮（dev.snipme/highlights）。
    // 注意：Markdown 代码块使用 mikepenz 内置的默认渲染器，而非本库。
    implementation("dev.snipme:highlights:1.1.0")

    // WebView 回退（为兼容遗留场景保留）
    // （androidx.webkit 未使用，使用系统 android.webkit.WebView）

    // 用于偏好设置的 DataStore
    implementation("androidx.datastore:datastore-preferences:1.2.1")

    // Room 本地数据库（消息缓存 + 诊断日志）
    val roomVersion = "2.8.4"
    implementation("androidx.room:room-runtime:$roomVersion")
    implementation("androidx.room:room-ktx:$roomVersion")
    ksp("androidx.room:room-compiler:$roomVersion")

    // zstd 压缩（归档桶）
    implementation("com.github.luben:zstd-jni:1.5.7-16@aar")
    testImplementation("com.github.luben:zstd-jni:1.5.7-16")

    // 用于图片加载的 Coil
    implementation("io.coil-kt.coil3:coil-compose:3.4.0")
    implementation("io.coil-kt.coil3:coil-network-okhttp:3.4.0")

    // 测试
    testImplementation("junit:junit:4.13.2")
    testImplementation("androidx.appcompat:appcompat:1.8.0") // 测试中模拟 Activity 用
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
    testImplementation("io.mockk:mockk:1.14.11")
    testImplementation("app.cash.turbine:turbine:1.2.1")
    testImplementation("io.ktor:ktor-client-mock:$ktorVersion")
    testImplementation("org.json:json:20260814")

    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
    androidTestImplementation(composeBom)
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("com.google.dagger:hilt-android-testing:2.60.1")
}

tasks.withType<Test>().configureEach {
    jvmArgs = jvmArgs.orEmpty() + listOf("-Xmx4g", "-XX:+UseCompressedOops", "-XX:MaxMetaspaceSize=512m")
    forkEvery = 50
    maxParallelForks = 1
}

// 强制升级 kotlin-metadata-jvm，使 Hilt 能读取 Kotlin 2.4.0 字节码
// （Mikepenz 0.43.0 使用 Kotlin 2.4.0 编译）
configurations.all {
    resolutionStrategy {
        force("org.jetbrains.kotlin:kotlin-metadata-jvm:2.4.0")
        // 2026-08-26（晚）残余卡顿收口：material3 1.5.0-alpha26 经原子组约束
        // （"ui is in atomic group androidx.compose.ui"）把整个 Compose 家族——
        // runtime/ui/ui-text/animation 等——全部拉到 1.12.0-beta01。
        // 三个历史矩阵：08-20 丝滑基线 = 全家稳定 1.11.x（BOM 2026.05.01）；
        // 08-22 起全 beta（FATAL 契约违规 + 卡顿）；ac12cf93 仅强回 foundation
        // = 「foundation 1.11.2 + 其余 1.12-beta」从未存在过的混搭（卡顿仍在，
        // 且 ui-text 文本测量引擎——SSE 流式重排热路径——仍跑 beta）。
        // 修复：ui/runtime/foundation/animation 四组全部对齐 1.11.2，完整恢复
        // 丝滑时代的一致矩阵。material3 本体保留 alpha26（HorizontalFloatingToolbar
        // 唯一来源），其对稳定组的二进制兼容由编译 + 真机 E2E 验证把关。
        eachDependency {
            val g = requested.group
            val isComposeCore = (
                g == "androidx.compose.ui" || g.startsWith("androidx.compose.ui.") ||
                    g == "androidx.compose.runtime" || g.startsWith("androidx.compose.runtime.") ||
                    g == "androidx.compose.foundation" || g.startsWith("androidx.compose.foundation.") ||
                    g == "androidx.compose.animation" || g.startsWith("androidx.compose.animation.")
                )
            if (isComposeCore) {
                useVersion("1.11.2")
            }
        }
    }
}

