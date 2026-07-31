import java.io.FileInputStream
import java.util.Properties

// Single source of truth for version — read from version.properties
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
        targetSdk = 35
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
        }
        create("beta") {
            dimension = "channel"
            applicationIdSuffix = ".beta"
            manifestPlaceholders["appLabel"] = "OC Beacon Beta"
        }
        create("stable") {
            dimension = "channel"
            manifestPlaceholders["appLabel"] = "@string/app_name"
        }
    }

    buildTypes {
        debug {
        }
        release {
            isMinifyEnabled = true
            signingConfig = signingConfigs.getByName("debug")
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
    // Android Core
    implementation("androidx.core:core-ktx:1.18.0")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.10.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    implementation("androidx.lifecycle:lifecycle-process:2.10.0")
    implementation("androidx.activity:activity-compose:1.13.0")

    // Compose
    val composeBom = platform("androidx.compose:compose-bom:2026.05.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3-window-size-class")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    // Navigation
    implementation("androidx.navigation:navigation-compose:2.9.8")
    implementation("androidx.hilt:hilt-navigation-compose:1.3.0")

    // Hilt DI
    implementation("com.google.dagger:hilt-android:2.59.2")
    ksp("com.google.dagger:hilt-compiler:2.59.2")

    // Ktor Client (OkHttp engine for proper SSE streaming support)
    val ktorVersion = "3.5.0"
    implementation("io.ktor:ktor-client-core:$ktorVersion")
    implementation("io.ktor:ktor-client-okhttp:$ktorVersion")
    implementation("io.ktor:ktor-client-content-negotiation:$ktorVersion")
    implementation("io.ktor:ktor-client-websockets:$ktorVersion")
    implementation("io.ktor:ktor-serialization-kotlinx-json:$ktorVersion")
    implementation("io.ktor:ktor-client-logging:$ktorVersion")
    implementation("io.ktor:ktor-client-auth:$ktorVersion")

    // ConnectBot Terminal — libvterm-backed terminal emulator (replaces hand-rolled ANSI parser)
    implementation("org.connectbot:termlib:0.1.0")

    // Kotlinx Serialization
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")

    // Markdown Rendering (mikepenz/multiplatform-markdown-renderer)
    val markdownRendererVersion = "0.43.0"
    implementation("com.mikepenz:multiplatform-markdown-renderer:$markdownRendererVersion")
    implementation("com.mikepenz:multiplatform-markdown-renderer-m3:$markdownRendererVersion")
    implementation("com.mikepenz:multiplatform-markdown-renderer-coil3:$markdownRendererVersion")

    // Syntax highlighting for FileViewer source view (dev.snipme/highlights).
    // Note: Markdown code blocks use mikepenz's built-in default renderer, NOT this library.
    implementation("dev.snipme:highlights:1.1.0")

    // WebView fallback (kept for legacy)
    implementation("androidx.webkit:webkit:1.16.0")

    // DataStore for preferences
    implementation("androidx.datastore:datastore-preferences:1.2.1")

    // Coil for image loading
    implementation("io.coil-kt.coil3:coil-compose:3.4.0")
    implementation("io.coil-kt.coil3:coil-network-okhttp:3.4.0")

    // Testing
    testImplementation("junit:junit:4.13.2")
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

// Force-upgrade kotlin-metadata-jvm so Hilt can read Kotlin 2.4.0 bytecode
// (Mikepenz 0.43.0 is compiled with Kotlin 2.4.0)
configurations.all {
    resolutionStrategy {
        force("org.jetbrains.kotlin:kotlin-metadata-jvm:2.4.0")
    }
}

