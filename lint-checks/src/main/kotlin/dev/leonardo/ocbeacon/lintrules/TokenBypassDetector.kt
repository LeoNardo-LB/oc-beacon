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
 * #391 切片8/9 / backlog #397：令牌绕过门禁（窄化版）。
 *
 * 只拦高信号、无语义歧义的两类：
 * - 硬编码色值字面量 Color(0x…)——应走 MaterialTheme.colorScheme / 主题令牌；
 * - 动画时长字面量 tween(n) / durationMillis = n——应走 Motion 令牌。
 *
 * 明确不纳入：
 * - 命名色 Color.White 等（可能是绘制语义）；
 * - interval 间距 dp 常量（ui-conventions 明文允许「dp 常量或 Material token」，全量拦截会大面积误报，另立迁移批次）；
 * - delay(n)（协程逻辑等待，非动画时长）。
 * 主题目录与分类调色板（SessionCategoryStyle）是令牌/调色板定义处，豁免。
 */
class TokenBypassDetector : Detector(), SourceCodeScanner {

    override fun getApplicableFiles(): EnumSet<Scope> = Scope.JAVA_FILE_SCOPE

    override fun beforeCheckFile(context: Context) {
        val path = context.file.path.replace('\\', '/')
        if (!path.contains(UI_ROOT) || path.contains(THEME_DIR)) return
        if (path.endsWith(COLOR_PALETTE)) return
        val contents = context.getContents() ?: return
        val code = contents
            .replace(BLOCK_COMMENT, " ")
            .replace(LINE_COMMENT, " ")
        val offender = firstOffender(code) ?: return
        context.report(
            ISSUE,
            Location.create(context.file),
            "硬编码令牌绕过（" + offender + "）：色值请用 MaterialTheme.colorScheme / 主题令牌，" +
                "动画时长请用 Motion 令牌（见 docs/ui-conventions.md）。",
        )
    }

    private fun firstOffender(code: String): String? {
        for (raw in code.lineSequence()) {
            val line = raw.trim()
            if (COLOR_RE.containsMatchIn(line) || DURATION_RE.containsMatchIn(line)) return line
        }
        return null
    }

    companion object {
        private const val UI_ROOT = "/dev/leonardo/ocbeacon/ui/"
        private const val THEME_DIR = "/dev/leonardo/ocbeacon/ui/theme/"
        private const val COLOR_PALETTE = "/ui/screens/sessions/components/SessionCategoryStyle.kt"
        private val BLOCK_COMMENT = Regex("/\\*[\\s\\S]*?\\*/")
        private val LINE_COMMENT = Regex("//[^\\n]*")
        private val COLOR_RE = Regex("\\bColor\\(0x[0-9A-Fa-f]{3,8}\\)")
        private val DURATION_RE = Regex("\\btween\\(\\s*[0-9]+\\s*\\)|\\bdurationMillis\\s*=\\s*[0-9]+")

        val ISSUE: Issue = Issue.create(
            id = "TokenBypass",
            briefDescription = "硬编码色值 / 动画时长绕过主题令牌",
            explanation = "色值请用 MaterialTheme.colorScheme / 主题令牌；动画时长请用 Motion 令牌。",
            category = Category.CORRECTNESS,
            priority = 6,
            severity = Severity.ERROR,
            implementation = Implementation(
                TokenBypassDetector::class.java,
                Scope.JAVA_FILE_SCOPE,
            ),
        )
    }
}
