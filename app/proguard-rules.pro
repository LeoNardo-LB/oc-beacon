# Add project specific ProGuard rules here.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt

# Kotlinx Serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}

-keep,includedescriptorclasses class dev.leonardo.ocbeacon.**$$serializer { *; }
-keepclassmembers class dev.leonardo.ocbeacon.** {
    *** Companion;
}
-keepclasseswithmembers class dev.leonardo.ocbeacon.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Ktor（#118 D2-29：原全库保留 → 收窄为反射/序列化必要部分；release 构建后
# 需连接冒烟验证——SSE/内容协商/OkHttp 引擎如出现 NoSuchMethodError 再补规则）
-keep class io.ktor.serialization.** { *; }
-keep class io.ktor.client.plugins.contentnegotiation.** { *; }
-keep class io.ktor.client.plugins.sse.** { *; }
-keep class io.ktor.client.engine.okhttp.** { *; }
-keep class io.ktor.utils.io.** { *; }
# kotlinx.coroutines：官方对 R8 完全支持，无需 keep（移除全库保留）
-dontwarn kotlinx.atomicfu.**
-dontwarn io.netty.**
-dontwarn com.typesafe.**
-dontwarn org.slf4j.**
-dontwarn java.lang.management.ManagementFactory
-dontwarn java.lang.management.RuntimeMXBean

# Markdown Renderer (mikepenz) — keep state/model classes to prevent R8 breaking async parsing
-keep class com.mikepenz.markdown.** { *; }
-keep class org.intellij.markdown.** { *; }

# Syntax Highlighting (dev.snipme/highlights) — model classes use kotlinx.serialization
-keep class dev.snipme.highlights.** { *; }

# Compose LazyListState — reflection access for SSE drift compensation
# (bypass requestScrollToItem's scroll{} mutex cancellation)
-keep class androidx.compose.foundation.lazy.LazyListState { *; }
-keep class androidx.compose.foundation.lazy.LazyListScrollPosition { *; }

# ConnectBot termlib — keep public API and native method signatures.
-keep public class org.connectbot.terminal.** { public *; }
-keepclasseswithmembernames class * { native <methods>; }

# zstd-jni: JNI 链接依赖类名，禁止混淆/重命名（luben/zstd-jni README）
-keep class com.github.luben.zstd.** { *; }
