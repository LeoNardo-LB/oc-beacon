package dev.leonardo.ocbeacon.ui.navigation.routes

import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Test
import java.net.URI

class WorkspaceNavTest {

    private val serverId = "srv-a1b2c3d4"

    /** 根据路由字符串构建 mock 的 NavBackStackEntry，以便 fromEntry 能解码参数。 */
    private fun buildEntry(route: String): androidx.navigation.NavBackStackEntry {
        // 使用 java.net.URI（JVM 可用）而非 android.net.Uri（单元测试中被 stub）
        val uri = URI("http://dummy/$route")
        val query = uri.rawQuery ?: ""
        val paramMap = query.split("&").associate { part ->
            val idx = part.indexOf('=')
            if (idx >= 0) part.substring(0, idx) to part.substring(idx + 1) else part to ""
        }

        val bundle = mockk<android.os.Bundle>(relaxed = true)
        every { bundle.getString(any()) } answers { paramMap[firstArg<String>()] }

        val entry = mockk<androidx.navigation.NavBackStackEntry>(relaxed = true)
        every { entry.arguments } returns bundle
        return entry
    }

    @Test
    fun `createRoute URL-encodes sessionId and directory`() {
        val sessionId = "01H2X3YZ/space=test"
        val directory = "/home/user/project with spaces"

        val route = WorkspaceNav.createRoute(
            serverId = serverId,
            sessionId = sessionId,
            directory = directory
        )

        // 特殊字符必须被编码
        assert(route.contains("sessionId=01H2X3YZ%2Fspace%3Dtest")) {
            "sessionId should be URL-encoded, got: $route"
        }
        assert(route.contains("directory=%2Fhome%2Fuser%2Fproject+with+spaces")) {
            "directory should be URL-encoded, got: $route"
        }
    }

    @Test
    fun `routePattern matches expected format`() {
        val pattern = WorkspaceNav.routePattern

        assertEquals(
            "workspace?serverId={serverId}&sessionId={sessionId}&directory={directory}",
            pattern
        )
    }

    @Test
    fun `fromEntry round-trips createRoute values`() {
        val sessionId = "01H2X3YZ9ABCDEF"
        val directory = "/home/user/project"

        val route = WorkspaceNav.createRoute(
            serverId = serverId,
            sessionId = sessionId,
            directory = directory
        )

        val entry = buildEntry(route)
        val params = WorkspaceNav.fromEntry(entry)

        assertEquals(serverId, params.server.serverId)
        assertEquals(sessionId, params.sessionId)
        assertEquals(directory, params.directory)
    }

    @Test
    fun `routePattern contains no credential params`() {
        val pattern = WorkspaceNav.routePattern

        // 密码/用户名/服务器 URL 不得出现在路由模式中
        assert(!pattern.contains("password")) { "routePattern must not contain password: $pattern" }
        assert(!pattern.contains("username")) { "routePattern must not contain username: $pattern" }
        assert(!pattern.contains("serverUrl")) { "routePattern must not contain serverUrl: $pattern" }
        assert(!pattern.contains("serverName")) { "routePattern must not contain serverName: $pattern" }
    }
}
