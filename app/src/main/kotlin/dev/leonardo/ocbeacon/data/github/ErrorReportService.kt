package dev.leonardo.ocbeacon.data.github

import dev.leonardo.ocbeacon.BuildConfig
import dev.leonardo.ocbeacon.logging.AppLogger
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "ErrorReportService"

/** 上报条目（已脱敏的日志片段）。 */
data class ReportLogEntry(
    val timestamp: Long,
    val level: String,
    val category: String,
    val message: String,
)

/** 上报时现采的环境块（spec：不改日志 schema）。 */
data class ReportEnvironment(
    val deviceModel: String,
    val androidVersion: String,
    val sdkInt: Int,
    val appVersion: String,
    val flavor: String,
    val locale: String,
    val runtimeMaxMemoryMb: Long,
)

/**
 * 错误上报服务（#151）：指纹计算（纯函数，双轨）+ 查重编排 + 正文构建 + 24h 防刷。
 */
@Singleton
class ErrorReportService @Inject constructor(
    private val apiClient: GitHubApiClient,
    private val tokenStore: GitHubTokenStore,
) {

    /** install-id × fingerprint → 上次评论时刻（24h 防刷本地记账）。 */
    private val lastCommentAt = ConcurrentHashMap<String, Long>()

    // ---- 指纹（纯函数，spec §指纹双轨） ----

    /**
     * 非崩溃：`fp:err:<category>:<归一化 message>`——数字/路径/ID 占位替换后参与，跨版本可查重。
     */
    fun fingerprintForError(category: String, message: String): String =
        "fp:err:" + category + ":" + normalize(message)

    /** 崩溃：`fp:crash:<VERSION_NAME>:<异常类名>`——同版本内去重，跨版本各建新 issue。 */
    fun fingerprintForCrash(exceptionClassName: String): String =
        "fp:crash:" + BuildConfig.VERSION_NAME + ":" + exceptionClassName

    /** 归一化：数字→N、十六进制 id→HEX、路径→PATH、quoted 字符串→STR。 */
    internal fun normalize(message: String): String = message
        .replace(Regex("\\b[0-9a-f]{8,}\\b"), "HEX")
        .replace(Regex("\\b\\d+(?:\\.\\d+)*\\b"), "N")
        .replace(Regex("(?:/[\\w.-]+)+"), "PATH")
        .replace(Regex("\\\\[\\w .-]+\\\\[\\w .-]+"), "PATH")
        .replace(Regex("\"[^\"]*\""), "STR")
        .replace(Regex(" +"), " ")
        .trim()

    // ---- 查重编排（spec §查重） ----

    sealed class Outcome {
        data class IssueCreated(val number: Int, val url: String) : Outcome()
        data class Commented(val number: Int) : Outcome()
        /** 24h 窗口内重复出现——静默跳过（防刷屏）。 */
        object SuppressedDuplicate : Outcome()
    }

    /**
     * 上报编排：查重命中→评论（24h 防刷）；未命中/search 失败降级→新建 issue。
     * token 401 由调用方引导重新授权（GitHubApiError.Unauthorized 上抛）。
     */
    suspend fun report(
        fingerprint: String,
        issueTitle: String,
        issueBody: String,
        commentBody: String,
    ): Result<Outcome> = runCatching {
        val token = tokenStore.loadToken() ?: throw GitHubApiError.Unauthorized()
        val installId = tokenStore.installId()
        val now = System.currentTimeMillis()
        val dedupeKey = installId + "|" + fingerprint

        val hit = apiClient.searchIssueByFingerprint(token, fingerprint).getOrNull()
        if (hit != null) {
            val last = lastCommentAt[dedupeKey] ?: 0L
            if (now - last < 24 * 3600_000L) {
                AppLogger.d(TAG, "suppressed duplicate within 24h: fp=" + fingerprint.take(48))
                return@runCatching Outcome.SuppressedDuplicate
            }
            apiClient.addComment(token, hit.number, commentBody).getOrThrow()
            lastCommentAt[dedupeKey] = now
            Outcome.Commented(hit.number)
        } else {
            val num = apiClient.createIssue(token, "[user-report] " + issueTitle, issueBody, listOf("needs-triage")).getOrThrow()
            lastCommentAt[dedupeKey] = now
            Outcome.IssueCreated(num, "https://github.com/" + GITHUB_TARGET_REPO + "/issues/" + num)
        }
    }

    // ---- 正文构建（spec §报告内容格式） ----

    /** 机器可读指纹+环境块（fenced code block，search 与 diff 的数据源）。 */
    fun machineBlock(fingerprint: String, env: ReportEnvironment, installId: String): String =
        buildString {
            appendLine("```json")
            appendLine(buildJsonObject {
                put("fingerprint", fingerprint)
                put("install_id", installId)
                put("device", env.deviceModel)
                put("android", env.androidVersion)
                put("sdk", env.sdkInt)
                put("app_version", env.appVersion)
                put("flavor", env.flavor)
                put("locale", env.locale)
                put("max_memory_mb", env.runtimeMaxMemoryMb)
            }.toString())
            appendLine("```")
        }

    /** 最近 N 条 ERROR/FATAL + 每条前后 K 条上下文（spec：20+3）。 */
    fun buildLogSection(
        entries: List<ReportLogEntry>,
        maxErrors: Int = 20,
        contextAround: Int = 3,
    ): String {
        val errorIdx = entries.indices.filter { entries[it].level in ERROR_LEVELS }
        val selected = errorIdx.takeLast(maxErrors).flatMap { i ->
            (i - contextAround..i + contextAround).filter { it in entries.indices }
        }.distinct().sorted()
        return selected.joinToString("\n") { i ->
            val e = entries[i]
            prefix(i in errorIdx) + e.level + "/" + e.category + ": " + e.message
        }
    }

    private fun prefix(isError: Boolean) = if (isError) "▸ " else "  "

    companion object {
        internal val ERROR_LEVELS = setOf("ERROR", "FATAL", "E", "F")
    }
}
