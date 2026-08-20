package dev.leonardo.ocbeacon.service

import dev.leonardo.ocbeacon.domain.model.ServerConfig
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * 泄漏修复（路径 1b）：onStartCommand 的 serviceScope.launch 挂起（DB 读取）
 * 恢复后若 onDestroy 已执行（serviceScope.cancel + stopAllConnections），
 * 迟到的 connect() 必须是空操作——否则用已销毁 Service 的 ::processEvent
 * 重填单例 connections map，连接永久滞留。
 */
class OpenCodeConnectionServiceConnectGuardTest {

    @Test
    fun `connect is no-op when serviceScope already cancelled`() {
        val service = OpenCodeConnectionService()
        val connectionManager = mockk<SseConnectionManager>(relaxed = true)
        setField(service, "connectionManager", connectionManager)

        // 模拟 onDestroy 已执行：serviceScope.cancel()
        val scope = getField(service, "serviceScope") as CoroutineScope
        (scope.coroutineContext[Job])!!.cancel()
        assertFalse("scope should be inactive after cancel", scope.isActive)

        service.connect(ServerConfig(id = "s1", url = "http://127.0.0.1:4199"))

        // 守卫生效：不重填单例 map、不启动任何连接
        verify(exactly = 0) { connectionManager.startConnection(any(), any()) }
    }

    private fun setField(target: Any, name: String, value: Any?) {
        val field = OpenCodeConnectionService::class.java.getDeclaredField(name)
        field.isAccessible = true
        field.set(target, value)
    }

    private fun getField(target: Any, name: String): Any? {
        val field = OpenCodeConnectionService::class.java.getDeclaredField(name)
        field.isAccessible = true
        return field.get(target)
    }
}
