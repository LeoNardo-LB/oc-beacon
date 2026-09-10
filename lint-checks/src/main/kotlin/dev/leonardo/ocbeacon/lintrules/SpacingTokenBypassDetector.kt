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
 * #391 切片8/9 / backlog #397：间距令牌绕过门禁（窄化版）。
 *
 * 只拦「标准间距值出现在间距位」这一高信号子集：padding / spacedBy / PaddingValues
 * 调用实参里出现 4 / 8 / 12 / 16 / 24 / 32 .dp（= SpacingTokens 的 XS…XXL），
 * 应改用 SpacingTokens.X.dp。非标准值（3.dp / 20.dp）、尺寸位（size）与命名色不在此列。
 */
class SpacingTokenBypassDetector : Detector(), SourceCodeScanner {

    override fun getApplicableFiles(): EnumSet<Scope> = Scope.JAVA_FILE_SCOPE

    override fun beforeCheckFile(context: Context) {
        val path = context.file.path.replace('\\', '/')
        if (!path.contains(UI_ROOT) || path.contains(THEME_DIR)) return
        val contents = context.getContents() ?: return
        val code = contents
            .replace(BLOCK_COMMENT, " ")
            .replace(LINE_COMMENT, " ")
        val offender = firstOffender(code) ?: return
        context.report(
            ISSUE,
            Location.create(context.file),
            "标准间距绕过令牌（" + offender + "）：请改用 SpacingTokens 常量" +
                "（XS=4 / SM=8 / MD=12 / LG=16 / XL=24 / XXL=32，见 docs/ui-conventions.md）。",
        )
    }

    private fun firstOffender(code: String): String? {
        for (raw in code.lineSequence()) {
            val line = raw.trim()
            if (SPACING_RE.containsMatchIn(line)) return line
        }
        return null
    }

    companion object {
        private const val UI_ROOT = "/dev/leonardo/ocbeacon/ui/"
        private const val THEME_DIR = "/dev/leonardo/ocbeacon/ui/theme/"
        private val BLOCK_COMMENT = Regex("/\\*[\\s\\S]*?\\*/")
        private val LINE_COMMENT = Regex("//[^\\n]*")
        private val SPACING_RE = Regex(
            "\\b(padding|spacedBy|PaddingValues)\\([^)]*\\b(4|8|12|16|24|32)\\.dp"
        )

        val ISSUE: Issue = Issue.create(
            id = "SpacingTokenBypass",
            briefDescription = "标准间距硬编码绕过 SpacingTokens",
            explanation = "标准间距（4/8/12/16/24/32）请用 SpacingTokens 常量而非硬编码 .dp。",
            category = Category.CORRECTNESS,
            priority = 5,
            severity = Severity.ERROR,
            implementation = Implementation(
                SpacingTokenBypassDetector::class.java,
                Scope.JAVA_FILE_SCOPE,
            ),
        )
    }
}
