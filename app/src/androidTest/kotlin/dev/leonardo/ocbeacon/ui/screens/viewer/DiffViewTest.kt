package dev.leonardo.ocbeacon.ui.screens.viewer

import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.leonardo.ocbeacon.R
import dev.leonardo.ocbeacon.domain.model.VcsFileDiff
import dev.leonardo.ocbeacon.domain.model.VcsStatus
import org.junit.Rule
import org.junit.Test

/**
 * Instrumented tests for [DiffView]. Verifies hunk rendering and the
 * hunk navigator counter progression on next/prev taps.
 *
 * Uses realistic sample data (D7-003): a real-looking 3-hunk patch.
 */
class DiffViewTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun diffView_rendersHunksAndNavigatesOnNextTap() {
        val hunkIndex = mutableStateOf(0)
        composeTestRule.setContent {
            DiffView(
                uiState = FileViewerUiState(
                    mode = FileViewerMode.DIFF,
                    isLoading = false,
                    diff = VcsFileDiff(
                        file = "app/src/main/kotlin/dev/minios/ocremote/data/api/OpenCodeApi.kt",
                        patch = SAMPLE_PATCH,
                        additions = 4,
                        deletions = 1,
                        status = VcsStatus.MODIFIED
                    ),
                    hunks = sampleHunks(),
                    currentHunkIndex = hunkIndex.value
                ),
                onNextHunk = { hunkIndex.value = hunkIndex.value + 1 },
                onPrevHunk = {}
            )
        }
        composeTestRule.onNodeWithText("@@ -1,3 +1,4 @@").assertIsDisplayed()
        composeTestRule.onNodeWithText("[1/3]").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription(
            composeTestRule.activity.getString(R.string.a11y_icon_hunk_next)
        ).performClick()
        composeTestRule.onNodeWithText("[2/3]").assertIsDisplayed()
    }

    @Test
    fun diffView_showsNoNavigatorWhenNoHunks() {
        composeTestRule.setContent {
            DiffView(
                uiState = FileViewerUiState(
                    mode = FileViewerMode.DIFF,
                    isLoading = false,
                    diff = VcsFileDiff(
                        file = "README.md",
                        patch = SAMPLE_PATCH,
                        additions = 4,
                        deletions = 1,
                        status = VcsStatus.MODIFIED
                    ),
                    hunks = emptyList()
                ),
                onNextHunk = {},
                onPrevHunk = {}
            )
        }
        composeTestRule.onAllNodesWithText("[1/1]").assertCountEquals(0)
        composeTestRule.onAllNodesWithContentDescription(
            composeTestRule.activity.getString(R.string.a11y_icon_hunk_next)
        ).assertCountEquals(0)
    }

    private fun sampleHunks(): List<DiffHunk> = listOf(
        DiffHunk(startLine = 1, patchStartLineIndex = 0, type = DiffHunkType.MODIFIED, rawPatch = ""),
        DiffHunk(startLine = 11, patchStartLineIndex = 5, type = DiffHunkType.MODIFIED, rawPatch = ""),
        DiffHunk(startLine = 22, patchStartLineIndex = 10, type = DiffHunkType.MODIFIED, rawPatch = "")
    )

    private companion object {
        const val SAMPLE_PATCH = """@@ -1,3 +1,4 @@
 package dev.leonardo.ocbeacon.data.api
 
 import io.ktor.client.HttpClient
+import io.ktor.client.engine.okhttp.OkHttp
@@ -10,2 +11,3 @@
-class OpenCodeApi(client: HttpClient)
+class OpenCodeApi(
+    private val client: HttpClient
+)
@@ -20,1 +22,1 @@
-    val port = 8080
+    val port = 4096"""
    }
}
