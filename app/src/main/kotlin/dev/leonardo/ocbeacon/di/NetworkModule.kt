package dev.leonardo.ocbeacon.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.leonardo.ocbeacon.BuildConfig
import dagger.hilt.components.SingletonComponent
import io.ktor.client.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.auth.*
import io.ktor.client.plugins.auth.providers.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.logging.*
import io.ktor.client.plugins.websocket.*
import io.ktor.serialization.kotlinx.json.*
import dev.leonardo.ocbeacon.data.api.installTransportFailureTap
import kotlinx.serialization.json.Json
import javax.inject.Singleton

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "opencode_prefs")

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {
    
    @Provides
    @Singleton
    fun provideJson(): Json = Json {
        // #97（M-6）：prettyPrint 关闭——全局 Json 被 SSE 双写（MessageStore 落盘）
        // 共用，流式 ~20 次/s 全量编码时缩进使体积 +30-50% 且多耗编码 CPU。
        // 需要可读 JSON 的场景（导出/调试）用局部 Json 实例。
        prettyPrint = false
        isLenient = true
        ignoreUnknownKeys = true
        coerceInputValues = true
        encodeDefaults = true
        explicitNulls = false
    }
    
    @Provides
    @Singleton
    fun provideHttpClient(
        json: Json,
        // #267：传输层失败上拍（Send 管线拦截 → SseConnectionManager 接线消费）
        transportFailureTap: dev.leonardo.ocbeacon.data.api.TransportFailureTap,
    ): HttpClient = HttpClient(OkHttp) {
        install(ContentNegotiation) {
            json(json)
        }
        
        install(Logging) {
            logger = Logger.ANDROID
            // #62：HEADERS 逐条打印请求/响应头（实测 90 条/10s，当前最大日志源）
            // → INFO 只保留请求方法/URL + 响应状态行（每请求 2 行）；release 全关。
            level = if (BuildConfig.DEBUG) LogLevel.INFO else LogLevel.NONE
        }
        
        install(HttpTimeout) {
            requestTimeoutMillis = 120_000
            connectTimeoutMillis = 15_000
            socketTimeoutMillis = 120_000
        }

        install(WebSockets)
        
        install(Auth) {
            // Auth 将根据服务器配置按请求进行配置
        }
        
        engine {
            config {
                // OkHttp 专用：禁用响应体缓冲以支持流式传输
                retryOnConnectionFailure(true)
            }
        }
        
        // 默认头将在领域 API 实现中按请求设置
    }.also {
        // #267：Send 管线 IOException 上拍（构建后安装——扩展函数以便单测复用）
        it.installTransportFailureTap(transportFailureTap)
    }
    
    @Provides
    @Singleton
    fun provideDataStore(@ApplicationContext context: Context): DataStore<Preferences> {
        return context.dataStore
    }
}
