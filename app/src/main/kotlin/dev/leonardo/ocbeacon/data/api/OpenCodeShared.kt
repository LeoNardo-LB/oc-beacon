package dev.leonardo.ocbeacon.data.api

import dev.leonardo.ocbeacon.data.dto.response.PtyInfo
import dev.leonardo.ocbeacon.domain.model.ServerConnection
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * V1/V2 客户端共享基座（2026-08-26 架构走查 C1 批次，Q5-a 裁决）。
 *
 * [dev.leonardo.ocbeacon.data.api.v1.V1ApiClient] 与
 * [dev.leonardo.ocbeacon.data.api.v2.V2ApiClient] 之间逐字重复的适配逻辑
 * 收编于此：PTY 创建响应解析三件套、会话导出流式传输主体。两处差异
 * 仅在 URL /api 前缀——统一经 [ServerConnection.apiBase] 抽象（V1 =
 * baseUrl，V2 = baseUrl + "/api"）。
 */

/**
 * 导出流式传输专用 OkHttp 客户端（连接 15s / 读 120s，配置与原逐次构建值一致）。
 *
 * 性能修复（C1-1）：旧实现在每次导出调用内 `OkHttpClient.Builder().build()`——
 * 每个实例自带独立连接池/线程池且闲置 5 分钟才回收，反复导出即反复重建。
 * 改为进程级共享单例；OkHttp 天然支持并发复用。
 */
internal val ExportOkHttpClient: okhttp3.OkHttpClient by lazy {
    okhttp3.OkHttpClient.Builder()
        .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(120, java.util.concurrent.TimeUnit.SECONDS)
        .build()
}

/**
 * 将会话导出 JSON 直接流式写入 [outputStream]：
 * `{"info":<session>,"messages":<messages>}`。
 *
 * 会话信息较小，经 [httpClient]（Ktor）取回后缓存在内存；messages 体量大，
 * 用原始 OkHttp（[ExportOkHttpClient]）逐字节流式读取——Ktor 的
 * ContentNegotiation 插件会缓冲整个响应，无法真正流式。
 *
 * V1/V2 差异仅 URL 前缀，经 [ServerConnection.apiBase] 统一：
 * V1 = `baseUrl/session/...`，V2 = `baseUrl/api/session/...`。
 */
internal suspend fun exportSessionToStream(
    httpClient: HttpClient,
    conn: ServerConnection,
    sessionId: String,
    outputStream: java.io.OutputStream,
    onProgress: (Long) -> Unit = {}
) {
    var bytesWritten = 0L
    val sessionPath = "${conn.apiBase}/session/$sessionId"
    val messagePath = "${conn.apiBase}/session/$sessionId/message"
    // 写入会话信息（较小，可安全保存在内存中）
    val sessionJson = httpClient.get(sessionPath) {
        auth(conn)
    }.bodyAsText()
    val header = """{"info":$sessionJson,"messages":"""
    outputStream.write(header.toByteArray())
    bytesWritten += header.toByteArray().size
    outputStream.flush()
    onProgress(bytesWritten)

    // 通过原始 OkHttp 流式传输 messages 以获得真正的字节级流式传输
    val request = okhttp3.Request.Builder()
        .url(messagePath)
        .apply { conn.authHeader?.let { addHeader("Authorization", it) } }
        .build()

    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        ExportOkHttpClient.newCall(request).execute().use { response ->
            val body = response.body
            val source = body.source()
            val buffer = ByteArray(8192)
            while (true) {
                val read = source.read(buffer)
                if (read == -1) break
                outputStream.write(buffer, 0, read)
                bytesWritten += read
                onProgress(bytesWritten)
            }
        }
    }

    outputStream.write("}".toByteArray())
    bytesWritten += 1
    outputStream.flush()
    onProgress(bytesWritten)
}

/**
 * 解析 PTY 创建端点响应为 [PtyInfo]（V1/V2 逐字共享）。
 *
 * 大多数服务器返回完整的 PtyInfo 对象；某些本地构建仅返回 id
 * 或将其包装在 data/pty 中（见 [extractPtyIdFromResponse]）。
 */
internal fun parsePtyInfoFromCreateResponse(
    json: Json,
    body: String,
    title: String?,
    cwd: String?
): PtyInfo {
    val trimmed = body.trim()

    runCatching { return json.decodeFromString(PtyInfo.serializer(), trimmed) }

    val id = extractPtyIdFromResponse(json, trimmed)
        ?: throw java.io.IOException("createPty: could not parse PTY id from response: $trimmed")

    return PtyInfo(
        id = id,
        title = title ?: "Tab",
        command = "/bin/sh",
        args = emptyList(),
        cwd = cwd ?: "/",
        status = "running",
        pid = 0,
    )
}

/** 原始字符串 id（"pty_xxx"）或 data/pty/result 包裹形态的递归提取。 */
internal fun extractPtyIdFromResponse(json: Json, responseBody: String): String? {
    // 原始字符串 id："pty_xxx" 或 pty_xxx
    val plain = responseBody.removeSurrounding("\"").trim()
    if (plain.startsWith("pty_")) return plain

    return runCatching {
        val root = json.parseToJsonElement(responseBody)
        findPtyId(root)
    }.getOrNull()
}

private fun findPtyId(element: JsonElement): String? {
    val obj = element as? JsonObject ?: return null

    obj["id"]?.jsonPrimitive?.contentOrNull?.let {
        if (it.startsWith("pty_")) return it
    }

    obj["pty"]?.let { nested ->
        findPtyId(nested)?.let { return it }
    }
    obj["data"]?.let { nested ->
        findPtyId(nested)?.let { return it }
    }
    obj["result"]?.let { nested ->
        findPtyId(nested)?.let { return it }
    }

    return null
}
