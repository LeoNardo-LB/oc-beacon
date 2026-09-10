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
        path.endsWith(MAIN_ACTIVITY) ||
            path.contains(ADAPTER_DIR) ||
            WHITELIST_PATHS.any { path.endsWith(it) }

    companion object {
        private const val MAIN_ACTIVITY = "/dev/leonardo/ocbeacon/MainActivity.kt"
        /** 适配器实现与注册表——类型的唯一解析落点（整目录豁免）。 */
        private const val ADAPTER_DIR = "/dev/leonardo/ocbeacon/data/adapter/"
        private val BLOCK_COMMENT = Regex("/\\*[\\s\\S]*?\\*/")
        private val LINE_COMMENT = Regex("//[^\\n]*")

        /**
         * 白名单文件（精确路径）——逐条对应 docs/architecture.md 的四类合法落点：
         * 类型定义 / 持久化身份（含重复后端同一性比较）、用户选择面、调试入口。
         * 注释里的提及已剥离，不进白名单。
         */
        private val WHITELIST_PATHS = listOf(
            // 类型定义与持久化身份
            "/dev/leonardo/ocbeacon/domain/model/ServerType.kt",
            "/dev/leonardo/ocbeacon/domain/model/ServerConfig.kt",
            "/dev/leonardo/ocbeacon/domain/model/ServerConnection.kt",
            "/dev/leonardo/ocbeacon/domain/model/DebugProfile.kt",
            // 领域解析器契约（supportedTypes = 已注册类型集合）
            "/dev/leonardo/ocbeacon/domain/adapter/ServerAdapterResolver.kt",
            // 服务器存储（持久化身份缺省）
            "/dev/leonardo/ocbeacon/data/repository/ServerDataStore.kt",
            // 重复后端同一性比较（type+url+username；非能力分支）
            "/dev/leonardo/ocbeacon/service/ConnectionLifecycleCoordinator.kt",
            "/dev/leonardo/ocbeacon/service/OpenCodeConnectionService.kt",
            // 用户选择面：注册表驱动的类型集合 + 选择对话框 + 卡片身份徽标
            "/dev/leonardo/ocbeacon/ui/screens/home/HomeScreen.kt",
            "/dev/leonardo/ocbeacon/ui/screens/home/HomeViewModel.kt",
            "/dev/leonardo/ocbeacon/ui/screens/home/components/ServerCard.kt",
            "/dev/leonardo/ocbeacon/ui/screens/home/components/ServerDialog.kt",
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
