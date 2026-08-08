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

    flavorDimensions += "channel"

    productFlavors {
        create("dev") {
            dimension = "channel"
            applicationIdSuffix = ".dev"
            manifestPlaceholders["appLabel"] = "OC Beacon Dev"
            // GitHub 分发渠道保留应用内自更新
            buildConfigField("boolean", "ENABLE_AUTO_UPDATE", "true")
        }
        create("beta") {
            dimension = "channel"
            applicationIdSuffix = ".beta"
            manifestPlaceholders["appLabel"] = "OC Beacon Beta"
            // GitHub 分发渠道保留应用内自更新
            buildConfigField("boolean", "ENABLE_AUTO_UPDATE", "true")
        }
        create("stable") {
            dimension = "channel"
            manifestPlaceholders["appLabel"] = "@string/app_name"
            // Google Play 渠道：政策禁止 REQUEST_INSTALL_PACKAGES 自更新，禁用
            buildConfigField("boolean", "ENABLE_AUTO_UPDATE", "false")
        }
    }

    buildTypes {
        debug {
        }
        release {
            isMinifyEnabled = true
            // 签名修复：signing.properties 存在时使用 release keystore（由上方 if 块设置），
            // 仅在不存在时回退 debug 签名。此前无条件覆盖为 debug 导致
            // release keystore 永不生效、CI 每次构建 debug 签名不同 → 用户升级签名冲突。
            if (!hasPropertiesFile) {
                signingConfig = signingConfigs.getByName("debug")
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
    implementation("androidx.core:core-ktx:1.18.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.10.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    implementation("androidx.lifecycle:lifecycle-process:2.10.0")
    implementation("androidx.activity:activity-compose:1.13.0")

    // Compose
    val composeBom = platform("androidx.compose:compose-bom:2026.05.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3-window-size-class")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    // 导航
    implementation("androidx.navigation:navigation-compose:2.9.8")

    // Hilt 依赖注入
    implementation("com.google.dagger:hilt-android:2.59.2")
    ksp("com.google.dagger:hilt-compiler:2.59.2")
    // 提供 androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel（代码已全部迁移，无需 navigation 专用 API）
    implementation("androidx.hilt:hilt-lifecycle-viewmodel-compose:1.3.0")

    // Ktor 客户端（OkHttp 引擎，确保 SSE 流式传输的正确支持）
    val ktorVersion = "3.5.0"
    implementation("io.ktor:ktor-client-core:$ktorVersion")
    implementation("io.ktor:ktor-client-okhttp:$ktorVersion")
    implementation("io.ktor:ktor-client-content-negotiation:$ktorVersion")
    implementation("io.ktor:ktor-client-websockets:$ktorVersion")
    implementation("io.ktor:ktor-serialization-kotlinx-json:$ktorVersion")
    implementation("io.ktor:ktor-client-logging:$ktorVersion")
    implementation("io.ktor:ktor-client-auth:$ktorVersion")

    // ConnectBot 终端 —— 基于 libvterm 的终端模拟器（替代手写 ANSI 解析器）
    implementation("org.connectbot:termlib:0.1.0")

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

    // 用于图片加载的 Coil
    implementation("io.coil-kt.coil3:coil-compose:3.4.0")
    implementation("io.coil-kt.coil3:coil-network-okhttp:3.4.0")

    // 测试
    testImplementation("junit:junit:4.13.2")
    testImplementation("androidx.appcompat:appcompat:1.7.1") // 测试中模拟 Activity 用
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
    testImplementation("io.mockk:mockk:1.14.9")
    testImplementation("app.cash.turbine:turbine:1.2.1")
    testImplementation("io.ktor:ktor-client-mock:$ktorVersion")
    testImplementation("org.json:json:20240303")

    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    androidTestImplementation(composeBom)
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("com.google.dagger:hilt-android-testing:2.59.2")
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
    }
}

