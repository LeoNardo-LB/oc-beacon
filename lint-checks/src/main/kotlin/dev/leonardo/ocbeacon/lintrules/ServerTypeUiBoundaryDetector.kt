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
 * #391 切片8/9 / backlog #397：通用界面不得 import 或声明服务器类型私有 UI 组件。
 *
 * 规则边界：只扫 ui 目录，跳过类型私有包（`/dsh/`、`/opencode/`）与主题目录
 * （`ui/theme/` 的 `OpenCodeTheme` 是全应用主题，不是类型私有组件）。
 * 命中两类：`import dev.leonardo.ocbeacon.ui….` 后接 Dsh / OpenCode 前缀符号，
 * 或通用 UI 文件顶层声明 Dsh / OpenCode 前缀符号。类型私有界面必须落在自己的包内并经界面插槽贡献。
 */
class ServerTypeUiBoundaryDetector : Detector(), SourceCodeScanner {

    override fun getApplicableFiles(): EnumSet<Scope> = Scope.JAVA_FILE_SCOPE

    override fun beforeCheckFile(context: Context) {
        val path = context.file.path.replace('\\', '/')
        if (!path.contains(UI_ROOT)) return
        if (path.contains(TYPE_PRIVATE_DSH) || path.contains(TYPE_PRIVATE_OC)) return
        if (path.contains(THEME_DIR)) return
        val contents = context.getContents() ?: return
        val code = contents
            .replace(BLOCK_COMMENT, " ")
            .replace(LINE_COMMENT, " ")
        val offender = firstOffender(code) ?: return
        context.report(
            ISSUE,
            Location.create(context.file),
            "通用界面不得 import / 声明服务器类型私有 UI 组件（" + offender +
                "）；请把该组件移入类型私有包并以界面插槽贡献（见 docs/architecture.md）。",
        )
    }

    private fun firstOffender(code: String): String? {
        for (raw in code.lineSequence()) {
            val line = raw.trim()
            if (line.startsWith("import ") && IMPORT_RE.containsMatchIn(line)) return line
            if (DECL_RE.containsMatchIn(line)) return line
        }
        return null
    }

    companion object {
        private const val UI_ROOT = "/dev/leonardo/ocbeacon/ui/"
        private const val THEME_DIR = "/dev/leonardo/ocbeacon/ui/theme/"
        private const val TYPE_PRIVATE_DSH = "/dsh/"
        private const val TYPE_PRIVATE_OC = "/opencode/"
        private val BLOCK_COMMENT = Regex("/\\*[\\s\\S]*?\\*/")
        private val LINE_COMMENT = Regex("//[^\\n]*")
        private val IMPORT_RE = Regex("^import dev\\.leonardo\\.ocbeacon\\.ui\\..*\\.(Dsh|OpenCode)[A-Z][A-Za-z0-9_]*$")
        private val DECL_RE = Regex(
            "(^|\\s)(fun|val|var|class|object|interface) (Dsh|OpenCode)[A-Z][A-Za-z0-9_]*"
        )

        val ISSUE: Issue = Issue.create(
            id = "ServerTypeUiBoundary",
            briefDescription = "通用界面引用了服务器类型私有 UI 组件",
            explanation = "类型私有界面必须落在类型私有包（/dsh/、/opencode/）内并经界面插槽" +
                "贡献；通用界面不得 import 或声明 Dsh*/OpenCode* UI 符号。",
            category = Category.CORRECTNESS,
            priority = 8,
            severity = Severity.ERROR,
            implementation = Implementation(
                ServerTypeUiBoundaryDetector::class.java,
                Scope.JAVA_FILE_SCOPE,
            ),
        )
    }
}
