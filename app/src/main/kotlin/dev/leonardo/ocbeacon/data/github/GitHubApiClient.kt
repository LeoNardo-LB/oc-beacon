package dev.leonardo.ocbeacon.data.github

import dev.leonardo.ocbeacon.logging.AppLogger
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "GitHubApiClient"
/** spec §范围：目标仓库固定维护者 fork，不做用户可配置。 */
const val GITHUB_TARGET_REPO = "LeoNardo-LB/oc-beacon"

/** 查重命中的既有 issue。 */
data class GitHubIssueHit(val number: Int, val title: String, val body: String?)

/** API 三端点错误映射（spec §失败处理：401 重新授权 / 403 限流 / 网络错）。 */
sealed class GitHubApiError : Exception() {
    class Unauthorized : GitHubApiError() { override val message: String get() = "授权已失效（401）" }
    class RateLimited(val retryAfterSeconds: Long?) : GitHubApiError() { override val message: String get() = "GitHub 限流（403）" }
    class Network(val cause0: Throwable) : GitHubApiError() { override val cause: Throwable get() = cause0; override val message: String get() = "网络错误：" + (cause0.message ?: cause0::class.simpleName) }
    class Http(val status: Int, val body: String) : GitHubApiError() { override val message: String get() = "HTTP ${'$'}status：${'$'}{body.take(200)}" }
}

/**
 * GitHub Issues API 薄客户端（#151）——search / create issue / create comment，
 * 走 DI HttpClient（OkHttp 引擎，与更新检查同栈，TLS/代理一致）。
 */
@Singleton
class GitHubApiClient @Inject constructor(
    private val client: HttpClient,
    private val json: Json,
) {

    /**
     * 按指纹精确搜索既有 open issue（限定仓库 + [user-report] 前缀标题）。
     * search 失败/限流由调用方降级新建（spec：不阻塞上报）。
     *
     * 2026-08-23 修复：search/issues 仅支持 GET（原 POST 端点不存在恒 404，
     * 查重形同虚设 → 每次上报都新建 issue）；查询串 URL 编码进 query 参数。
     * 2026-08-23 修复二：指纹含冒号——GitHub 搜索把 key:value 解析成限定词
     * （实测 "fp:err" → 0 结果/报错）；冒号换空格的短语实测精确命中。故搜索时
     * 指纹做冒号→空格归一化，并去掉 in:title 限定（指纹短语足够唯一）。
     */
    suspend fun searchIssueByFingerprint(token: String, fingerprint: String): Result<GitHubIssueHit?> = runCatching {
        val searchableFp = fingerprint.replace(":", " ").replace(Regex(" +"), " ").trim()
        val query = "repo:$GITHUB_TARGET_REPO is:issue is:open \"$searchableFp\""
        val encoded = java.net.URLEncoder.encode(query, "UTF-8")
        val resp = client.get("${GitHubDeviceEndpoints.API_BASE}/search/issues?q=$encoded") {
            header(HttpHeaders.Authorization, "Bearer $token")
            header(HttpHeaders.Accept, "application/vnd.github+json")
        }
        mapErrors(resp.status.value, resp.bodyAsText())
        val obj = json.parseToJsonElement(resp.bodyAsText()).jsonObject
        val items = obj["total_count"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
        if (items == 0) null
        else {
            val itemsArr = obj["items"]?.jsonArray ?: return@runCatching null
            val issue = itemsArr.getOrNull(0)?.jsonObject ?: return@runCatching null
            GitHubIssueHit(
                number = issue["number"]?.jsonPrimitive?.content?.toIntOrNull() ?: return@runCatching null,
                title = issue["title"]?.jsonPrimitive?.content ?: "",
                body = issue["body"]?.jsonPrimitive?.content,
            )
        }
    }.recoverCatching { e -> throw e.asGitHubError() }

    suspend fun createIssue(token: String, title: String, body: String, labels: List<String>): Result<Int> = runCatching {
        val resp = client.post("${GitHubDeviceEndpoints.API_BASE}/repos/$GITHUB_TARGET_REPO/issues") {
            header(HttpHeaders.Authorization, "Bearer $token")
            header(HttpHeaders.Accept, "application/vnd.github+json")
            setBody(json.encodeToString(kotlinx.serialization.json.JsonObject.serializer(), buildJsonObject {
                put("title", title)
                put("body", body)
                put("labels", kotlinx.serialization.json.JsonArray(labels.map { kotlinx.serialization.json.JsonPrimitive(it) }))
            }))
        }
        mapErrors(resp.status.value, resp.bodyAsText())
        json.parseToJsonElement(resp.bodyAsText()).jsonObject["number"]!!.jsonPrimitive.content.toInt()
    }.recoverCatching { e -> throw e.asGitHubError() }

    suspend fun addComment(token: String, issueNumber: Int, body: String): Result<Unit> = runCatching {
        val resp = client.post("${GitHubDeviceEndpoints.API_BASE}/repos/$GITHUB_TARGET_REPO/issues/$issueNumber/comments") {
            header(HttpHeaders.Authorization, "Bearer $token")
            header(HttpHeaders.Accept, "application/vnd.github+json")
            setBody(json.encodeToString(kotlinx.serialization.json.JsonObject.serializer(), buildJsonObject {
                put("body", body)
            }))
        }
        mapErrors(resp.status.value, resp.bodyAsText())
    }.recoverCatching { e -> throw e.asGitHubError() }

    private fun mapErrors(status: Int, body: String) {
        when {
            status == 401 -> throw GitHubApiError.Unauthorized()
            status == 403 -> throw GitHubApiError.RateLimited(retryAfterSeconds = null)
            status !in 200..299 -> {
                AppLogger.e(TAG, "GitHub API HTTP $status body=" + body.take(300))
                throw GitHubApiError.Http(status, body.take(500))
            }
        }
    }
}

internal fun Throwable.asGitHubError(): GitHubApiError = when (this) {
    is GitHubApiError -> this
    else -> GitHubApiError.Network(this)
}
