package dev.leonardo.ocbeacon.lintrules

import com.android.tools.lint.detector.api.Category
import com.android.tools.lint.detector.api.Context
import com.android.tools.lint.detector.api.Detector
import com.android.tools.lint.detector.api.Implementation
import com.android.tools.lint.detector.api.Issue
import com.android.tools.lint.detector.api.Location
import com.android.tools.lint.detector.api.Scope
import com.android.tools.lint.detector.api.Severity
import com.android.tools.lint.detector.api.SourceCodeScanner
import java.util.EnumSet

/**
 * #391 切片8 / backlog #397：服务器类型引用白名单门禁。
 *
 * 承重规则：服务器类型判断只允许出现在白名单——类型定义、持久化身份、用户选择、
 * 调试入口（见 docs/architecture.md「服务器适配层」）。其余位置（通用界面 / 通用壳 /
 * 仓库）一律经 ServerCapabilities 能力位或 ServerPorts 端口，新增服务器类型不改共享代码。
 *
 * 文本级实现：命中 `ServerType` 词元且文件不属白名单前缀即报 error。白名单外的任何
 * 新增引用都会被 lint 门禁拦下（abortOnError=true）。
 */
class ServerTypeWhitelistDetector : Detector(), SourceCodeScanner {

    override fun getApplicableFiles(): EnumSet<Scope> = Scope.JAVA_FILE_SCOPE

    override fun beforeCheckFile(context: Context) {
        val contents = context.getContents() ?: return
        // 注释里的提及（KDoc 说明白名单本身）不算引用：剥离块注释与行注释后再判
        val code = contents
            .replace(BLOCK_COMMENT, " ")
            .replace(LINE_COMMENT, " ")
        if (!code.contains("ServerType")) return
        val path = context.file.path.replace('\\', '/')
        if (isWhitelisted(path)) return
        context.report(
            ISSUE,
            Location.create(context.file),
            "服务器类型引用越出白名单：请改用 ServerCapabilities 能力位或 ServerPorts 端口" +
                "（白名单 = 类型定义 / 持久化身份 / 用户选择 / 调试入口，见 docs/architecture.md）。",
        )
    }

    private fun isWhitelisted(path: String): Boolean =
        WHITELIST_PREFIXES.any { path.contains(it) } || path.endsWith(MAIN_ACTIVITY_SUFFIX)

    companion object {
        private const val MAIN_ACTIVITY_SUFFIX = "/dev/leonardo/ocbeacon/MainActivity.kt"
        private val BLOCK_COMMENT = Regex("/\\*[\\s\\S]*?\\*/")
        private val LINE_COMMENT = Regex("//[^\\n]*")

        /** 白名单路径前缀（相对仓库根的包路径片段）。 */
        private val WHITELIST_PREFIXES = listOf(
            // 类型定义 / 持久化身份 / 连接对象
            "/dev/leonardo/ocbeacon/domain/model/",
            // 领域解析器契约与按 transportKind 的世代投影
            "/dev/leonardo/ocbeacon/domain/adapter/",
            // 分页游标策略：只经 transportKind 投影，类型仅作默认参数
            "/dev/leonardo/ocbeacon/domain/usecase/PaginationCursorPolicy.kt",
            // 适配器实现与注册表（类型的唯一落点）
            "/dev/leonardo/ocbeacon/data/adapter/",
            // 服务器存储默认值（持久化身份）
            "/dev/leonardo/ocbeacon/data/repository/ServerDataStore.kt",
            // 连接监督层默认参数（壳内 plumbing）
            "/dev/leonardo/ocbeacon/service/",
            // 用户选择面（服务器对话框 / 卡片身份徽标 / home 默认参数）
            "/dev/leonardo/ocbeacon/ui/screens/home/",
        )

        val ISSUE: Issue = Issue.create(
            id = "ServerTypeWhitelist",
            briefDescription = "服务器类型引用越出白名单",
            explanation = "服务器类型判断只允许出现在类型定义 / 持久化身份 / 用户选择 / " +
                "调试入口；通用界面与通用壳请改用 ServerCapabilities 能力位或 ServerPorts 端口。",
            category = Category.CORRECTNESS,
            priority = 8,
            severity = Severity.ERROR,
            implementation = Implementation(
                ServerTypeWhitelistDetector::class.java,
                Scope.JAVA_FILE_SCOPE,
            ),
        )
    }
}
