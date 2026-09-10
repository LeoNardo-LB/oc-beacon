pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "OC Beacon"
include(":app")
// #391 切片8 / #397：自定义 Android Lint 规则模块（工具链模块，非应用层拆分，不进入 APK）
include(":lint-checks")
