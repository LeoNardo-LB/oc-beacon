plugins {
    id("java-library")
    // 版本由 AGP 9.3.2 引入的 Kotlin 插件 classpath 提供（此处不指定版本）
    id("org.jetbrains.kotlin.jvm")
}

// 工具链模块（非应用层拆分）：只承载自定义 Android Lint 规则，不进入 APK。
// #391 切片8 / backlog #397：服务器类型引用白名单门禁。
java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    // lint 运行时提供（随 AGP 9.3.2 的 lint 32.3.2），不打包进规则模块
    compileOnly("com.android.tools.lint:lint-api:32.3.2")
}
