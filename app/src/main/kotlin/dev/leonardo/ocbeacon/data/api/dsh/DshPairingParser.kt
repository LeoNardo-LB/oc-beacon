package dev.leonardo.ocbeacon.data.api.dsh

/**
 * #325：DSH 首次配对载荷——baseUrl（authority）+ launch token。
 *
 * baseUrl 必须**恰好**是后续连接所用的 authority（协议+host+端口，无 query/
 * 尾斜杠）——token 交换铸出的 cookie 与 authority 绑定（调研 2026-09-04 §1：
 * 127.0.0.1 ↔ LAN IP 互换即 401），归一化时剥离一切多余成分。
 */
data class DshPairPayload(
    val baseUrl: String,
    val token: String,
)

/**
 * #325②：配对 URI 纯函数解析器（QR/深链降级通道的 app 侧入口）。
 *
 * 接受三形态输入（宿主 `scripts/dsh-pair.sh` 产出深链；用户从 `dsh-url`/
 * web.log 手动复制亦可）：
 * 1. 深链 `ocbeacon://pair?url=<服务器地址>&token=<launch token>`
 *    （url/token 值可 URL 编码也可裸写；token 校验同 [extractDshToken]：
 *    base64url 字符集 + 下限 20）；
 * 2. dsh web 原始 URL `http://host:port/?token=…`；
 * 3. web.log 启动行原样粘贴（`dsh web: 本机 … LAN …`——取**首个** URL，
 *    即本机 127.0.0.1 authority，与 adb reverse 用法对齐）。
 *
 * 拒绝面：非 `pair` 主机的 ocbeacon:// 深链、缺 url/token、短/脏 token、
 * 无 host 或 host 含畸形字符（如未解码 `%NR`）的 url、空/垃圾输入。
 * 解码失败（畸形百分号序列）不抛异常——[safeDecode] 回退原串后续一概被
 * host 白名单拒绝（NavUtils.safeDecodeParam 同款风险面，AGENTS 铁律）。
 *
 * 纯 JVM 无 Android 依赖——单测缝（DshPairingParserTest）。
 */
object DshPairingParser {

    private const val SCHEME_PREFIX = "ocbeacon://"
    private const val PAIR_HOST = "pair"

    /** http(s) URL 采样：host 限 hostname/IP 字符集（IPv6 字面量暂不支持——DSH 场景为 IPv4 LAN/localhost）。 */
    private val URL_SAMPLE_REGEX = Regex("https?://[A-Za-z0-9._\\-]+(?::\\d{1,5})?")

    /** token 校验：与 [extractDshToken] 裸 token 分支同规（base64url，下限 20 防误截断）。 */
    private val BARE_TOKEN_REGEX = Regex("[A-Za-z0-9_-]{20,200}")

    /** host 白名单：hostname/IP 常规字符（拒绝 %、空格等畸形解码残余）。 */
    private val HOST_REGEX = Regex("^[A-Za-z0-9._\\-]+$")

    fun parsePairUri(raw: String): DshPairPayload? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        return if (trimmed.startsWith(SCHEME_PREFIX)) {
            parseDeepLink(trimmed.removePrefix(SCHEME_PREFIX))
        } else {
            parsePastedText(trimmed)
        }
    }

    // ---- 深链形态：ocbeacon://pair?url=…&token=… ----------------------------

    private fun parseDeepLink(rest: String): DshPairPayload? {
        if (rest.substringBefore('?') != PAIR_HOST) return null
        val params = queryParams(rest.substringAfter('?', ""))
        val url = params["url"] ?: return null
        val token = params["token"] ?: return null
        if (!BARE_TOKEN_REGEX.matches(token)) return null
        val base = normalizeBaseUrl(url) ?: return null
        return DshPairPayload(base, token)
    }

    // ---- 粘贴形态：dsh web URL / web.log 启动行 ------------------------------

    private fun parsePastedText(text: String): DshPairPayload? {
        val token = extractDshToken(text) ?: return null
        val urlSample = URL_SAMPLE_REGEX.find(text)?.value ?: return null
        val base = normalizeBaseUrl(urlSample) ?: return null
        return DshPairPayload(base, token)
    }

    // ---- 归一化与解码 --------------------------------------------------------

    /**
     * 归一化为 authority：补 http://（缺 scheme）、剥 query/fragment/尾斜杠、
     * host 白名单校验。任一环节失败返回 null（不得产出半残 authority——
     * cookie 绑定不容近似匹配）。
     */
    private fun normalizeBaseUrl(input: String): String? {
        var candidate = input.trim()
        if (candidate.isEmpty()) return null
        if (!candidate.startsWith("http://") && !candidate.startsWith("https://")) {
            candidate = "http://$candidate"
        }
        return try {
            val url = java.net.URL(candidate)
            val host = url.host
            if (host.isBlank() || !HOST_REGEX.matches(host)) return null
            if (url.port != -1 && url.port !in 1..65535) return null
            val sb = StringBuilder(url.protocol.lowercase())
                .append("://").append(host)
            if (url.port != -1) sb.append(':').append(url.port)
            sb.toString()
        } catch (_: Exception) {
            null
        }
    }

    private fun queryParams(query: String): Map<String, String> {
        if (query.isEmpty()) return emptyMap()
        val result = mutableMapOf<String, String>()
        for (pair in query.split('&')) {
            if (pair.isEmpty()) continue
            result[safeDecode(pair.substringBefore('='))] = safeDecode(pair.substringAfter('=', ""))
        }
        return result
    }

    /** 畸形百分号序列（如 %NR）回退原串——不抛异常，后续 host 白名单兜底拒绝。 */
    private fun safeDecode(value: String): String = try {
        java.net.URLDecoder.decode(value, "UTF-8")
    } catch (_: IllegalArgumentException) {
        value
    }
}
