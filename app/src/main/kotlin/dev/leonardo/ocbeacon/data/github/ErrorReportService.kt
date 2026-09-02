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
 * #154b 全量日志附件：提交时上传为 secret gist，链接附于正文。
 * 内容走既有脱敏导出管道（DiagnosticLogRepository.export），此处不重复脱敏。
 */
data class GistAttachment(
    val description: String,
    val filename: String,
    val content: String,
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

    /**
     * issue 标题（spec §报告内容格式：`[user-report] <错误摘要>`——摘要即本函数产物）。
     * 2026-08-23 用户定规（标题区分度）：不同错误在 issue 列表必须一眼可分辨——
     * 三个手段：① category 作类目前缀；② 超长 message **中段截断**（保头尾——异常类在头、
     * 细节常在尾，纯头部截断会抹掉区分信息）；③ 尾缀指纹 8 位十六进制签名 `(#xxxxxxxx)`——
     * 指纹不同签名必不同（SHA-256），彻底杜绝不同错误共享同标题；同指纹（同一错误重复上报）
     * 签名相同，与查重归并语义一致。message 在日志写入时已经过脱敏管道
     *（DiagnosticLogRepository.sanitizeEntry），此处不再重复脱敏，只做形状规整。
     */
    fun issueTitleForError(category: String, message: String, fingerprint: String): String {
        val flat = message.replace(Regex("[\\s]+"), " ").trim()
        val core = if (flat.isBlank() || flat == category) category
        else {
            val body = if (flat.length <= MAX_MSG_LEN) flat
            else flat.take(MSG_HEAD_LEN) + "…" + flat.takeLast(MSG_TAIL_LEN)
            "$category: $body"
        }
        return "$core (#${titleSignature(fingerprint)})"
    }

    /** 指纹→8 位十六进制签名（SHA-256 前 4 字节）：标题唯一性保证 + 与正文机器块的关联线索。 */
    internal fun titleSignature(fingerprint: String): String =
        java.security.MessageDigest.getInstance("SHA-256")
            .digest(fingerprint.toByteArray(Charsets.UTF_8))
            .take(4).joinToString("") { "%02x".format(it) }

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
        /** gistUrl：附件上传成功的外链；null = 未请求附件或附件失败已降级。 */
        data class IssueCreated(val number: Int, val url: String, val gistUrl: String? = null) : Outcome()
        data class Commented(val number: Int, val gistUrl: String? = null) : Outcome()
        /** 24h 窗口内重复出现——静默跳过（防刷屏）。 */
        object SuppressedDuplicate : Outcome()
    }

    /**
     * 上报编排：查重命中→评论（24h 防刷）；未命中/search 失败降级→新建 issue。
     * token 401 由调用方引导重新授权（GitHubApiError.Unauthorized 上抛）。
     * #154b：attachment 非空时上传 secret gist 并把链接附于正文——**在 24h 防刷
     * 判定之后**才创建（被防抖掉的重复上报不得留下孤儿 gist）；任何附件失败一律
     * 降级为无附件继续上报，附件永不阻塞正文（spec §失败处理精神延伸）。
     */
    suspend fun report(
        fingerprint: String,
        issueTitle: String,
        issueBody: String,
        commentBody: String,
        attachment: GistAttachment? = null,
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
            val gistUrl = uploadAttachment(token, attachment)
            apiClient.addComment(token, hit.number, appendGistSection(commentBody, gistUrl)).getOrThrow()
            lastCommentAt[dedupeKey] = now
            Outcome.Commented(hit.number, gistUrl)
        } else {
            val gistUrl = uploadAttachment(token, attachment)
            val num = apiClient.createIssue(
                token,
                "[user-report] " + issueTitle,
                appendGistSection(issueBody, gistUrl),
                listOf("needs-triage"),
            ).getOrThrow()
            lastCommentAt[dedupeKey] = now
            Outcome.IssueCreated(num, "https://github.com/" + GITHUB_TARGET_REPO + "/issues/" + num, gistUrl)
        }
    }

    /**
     * 附件上传：失败（含 403 权限不足 / 限流 / 网络）一律返回 null 降级。
     * 401 不在此特殊处理——若 token 真失效，后续 issue/comment 步骤会以
     * Unauthorized 上抛并引导重新授权；仅 gist 401 的混合态按降级处理。
     */
    private suspend fun uploadAttachment(token: String, attachment: GistAttachment?): String? {
        if (attachment == null) return null
        return apiClient.createSecretGist(
            token,
            attachment.description,
            attachment.filename,
            truncateGistContent(attachment.content),
        ).onFailure { e ->
            AppLogger.w(TAG, "gist attachment failed — degrading to no attachment", e)
        }.getOrNull()
    }

    /** 正文附件段：secret gist 外链；无附件（失败/未勾选）时原样返回正文。 */
    fun appendGistSection(body: String, gistUrl: String?): String =
        if (gistUrl == null) body
        else body + "\n\n## 全量日志附件（secret gist）\n\n" + gistUrl + "\n"

    /**
     * gist 单文件上限 ~1MB（留余量 [MAX_GIST_CONTENT_CHARS]）——超出保尾截断：
     * 最新日志诊断价值最高，截头部并在首行标注。
     */
    internal fun truncateGistContent(content: String): String {
        if (content.length <= MAX_GIST_CONTENT_CHARS) return content
        return "…（前部已截断，保留最近 " + MAX_GIST_CONTENT_CHARS + " 字符）…\n" +
            content.takeLast(MAX_GIST_CONTENT_CHARS)
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

        /** 标题 message 段长度预算（2026-08-23 区分度定规）：中段截断保头尾。 */
        private const val MAX_MSG_LEN = 84
        private const val MSG_HEAD_LEN = 56
        private const val MSG_TAIL_LEN = 24

        /**
         * #154b：gist 单文件字符预算——上限 ~1MB 是**字节**，CJK 按 UTF-8 最坏
         * 3 字节/字符折算（300K × 3 = 900KB 留余量）；真实日志（脱敏后每字段
         * ≤1000 字符）通常远小于此。
         */
        internal const val MAX_GIST_CONTENT_CHARS = 300_000
    }
}
