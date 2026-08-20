package dev.leonardo.ocbeacon.service

import dev.leonardo.ocbeacon.data.repository.ServerTerminalRegistry
import dev.leonardo.ocbeacon.domain.model.ApiVersion
import dev.leonardo.ocbeacon.domain.model.ServerConfig
import dev.leonardo.ocbeacon.domain.model.SseEvent
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 连接生命周期协调器单测（#170 阶段 3——Q4-B 用例集）。
 *
 * 用例对应历史竞态/泄漏根因：
 * - C1 connect 幂等（同 serverId 重复 → SSE 只启动一次——重复事件=流式翻倍根因）
 * - C2 同后端去重（url+username 归一化——backlog #34）
 * - C3 disconnect 四路清理序列（轮询/SSE/终端/通知——漏一路即泄漏）
 * - C4 disconnectAll ≡ 逐个 disconnect（teardown 合一等价性）
 * - C5 未知 id 断开 no-op
 * - C6 回调时序（active=true/false；最后断开 registry 空）
 * - C7 findDuplicateBackend（UI 预检）
 * - C8 轮询启停（工厂启动/断开取消）
 */
class ConnectionLifecycleCoordinatorTest {

    private lateinit var manager: SseConnectionManager
    private lateinit var terminalRegistry: ServerTerminalRegistry
    private lateinit var notificationManager: AppNotificationManager
    private lateinit var coordinator: ConnectionLifecycleCoordinator
    private val events = mutableListOf<Pair<String, Boolean>>()
    private val pollingJobs = mutableMapOf<String, Job>()
    private val pollScope = CoroutineScope(Dispatchers.Unconfined)

    private fun config(id: String, url: String = "http://192.168.1.10:4199") = ServerConfig(
        id = id, url = url, username = "opencode",
        password = "x", apiVersion = ApiVersion.V1,
    )

    @Before
    fun setup() {
        manager = mockk(relaxed = true)
        terminalRegistry = mockk(relaxed = true)
        notificationManager = mockk(relaxed = true)
        coordinator = ConnectionLifecycleCoordinator(manager, terminalRegistry, notificationManager)
        coordinator.onEvent = { _, _ -> }
        coordinator.questionPollingFactory = { server ->
            pollScope.launch { awaitCancellation() }.also { pollingJobs[server.id] = it }
        }
        coordinator.onLifecycleChanged = { serverId, active ->
            events += serverId to active
        }
    }

    @Test
    fun `C1_connect同id幂等只启动一次SSE`() {
        val cfg = config("s1")
        coordinator.connect(cfg)
        coordinator.connect(cfg)
        verify(exactly = 1) { manager.startConnection(cfg, any()) }
        assertEquals(setOf("s1"), coordinator.activeServerIds.value)
    }

    @Test
    fun `C2_connect同后端不同id去重`() {
        coordinator.connect(config("s1", url = "http://10.0.0.5:4199"))
        // 尾斜杠 + host 大小写差异 = 同一后端（#34 归一化）
        coordinator.connect(config("s2", url = "HTTP://10.0.0.5:4199/"))
        verify(exactly = 1) { manager.startConnection(any(), any()) }
        assertNull("重复后端不得入 registry", coordinator.findDuplicateBackend("http://10.0.0.5:4199", "opencode")?.let { it.takeIf { it.id == "s2" } })
        assertTrue(coordinator.findDuplicateBackend("http://10.0.0.5:4199", "opencode")?.id == "s1")
    }

    @Test
    fun `C3_disconnect四路清理完整调用`() {
        val cfg = config("s1")
        coordinator.connect(cfg)
        coordinator.disconnect("s1")
        verify(exactly = 1) { manager.stopConnection("s1") }
        verify(exactly = 1) { terminalRegistry.removeWorkspace("s1") }
        verify(exactly = 1) { notificationManager.clearForServer("s1") }
        assertTrue("轮询 job 应被取消", pollingJobs["s1"]!!.isCancelled)
        assertTrue(coordinator.activeServerIds.value.isEmpty())
    }

    @Test
    fun `C4_disconnectAll等价逐个disconnect`() {
        val cfgs = listOf(config("s1"), config("s2", url = "http://10.0.0.6:4199"), config("s3", url = "http://10.0.0.7:4199"))
        cfgs.forEach { coordinator.connect(it) }
        coordinator.disconnectAll()
        cfgs.forEach { cfg ->
            verify(exactly = 1) { manager.stopConnection(cfg.id) }
            verify(exactly = 1) { terminalRegistry.removeWorkspace(cfg.id) }
            verify(exactly = 1) { notificationManager.clearForServer(cfg.id) }
        }
        assertTrue(coordinator.activeServerIds.value.isEmpty())
        // 回调：每个服务器 active=true 一次 + active=false 一次
        assertEquals(cfgs.size, events.count { it.second })
        assertEquals(cfgs.size, events.count { !it.second })
    }

    @Test
    fun `C5_disconnect未知id为noOp`() {
        coordinator.connect(config("s1"))
        coordinator.disconnect("unknown")
        verify(exactly = 0) { manager.stopConnection(any()) }
        verify(exactly = 0) { terminalRegistry.removeWorkspace(any()) }
        assertEquals(setOf("s1"), coordinator.activeServerIds.value)
    }

    @Test
    fun `C6_回调时序与最后断开判定`() {
        coordinator.connect(config("s1"))
        coordinator.disconnect("s1")
        assertEquals(listOf("s1" to true, "s1" to false), events)
        // 最后断开后 registry 空——宿主据此 stopSelf（FGS 决策数据源）
        assertTrue(coordinator.activeServerIds.value.isEmpty())
    }

    @Test
    fun `C7_findDuplicateBackend命中与未命中`() {
        coordinator.connect(config("s1", url = "http://10.0.0.5:4199"))
        assertNotNull(coordinator.findDuplicateBackend("http://10.0.0.5:4199/", "opencode"))
        assertNull(coordinator.findDuplicateBackend("http://10.0.0.99:4199", "opencode"))
        assertNull(coordinator.findDuplicateBackend("http://10.0.0.5:4199", "other-user"))
    }

    @Test
    fun `C8_轮询随生命周期启停`() {
        val cfg = config("s1")
        coordinator.connect(cfg)
        assertTrue("connect 应启动轮询", pollingJobs.containsKey("s1"))
        coordinator.disconnect("s1")
        assertTrue("disconnect 应取消轮询", pollingJobs["s1"]!!.isCancelled)
        // 重连（registry 已清）可再次启动
        coordinator.connect(cfg)
        assertTrue(pollingJobs.containsKey("s1"))
    }

    @Test
    fun `C9_activeServerIds流即时反映成员变化`() = runBlocking {
        val collected = mutableListOf<Set<String>>()
        val job = launch(Dispatchers.Unconfined) {
            coordinator.activeServerIds.collect { collected += it }
        }
        coordinator.connect(config("s1"))
        coordinator.connect(config("s2", url = "http://10.0.0.6:4199"))
        coordinator.disconnect("s1")
        coordinator.disconnect("s2")
        Thread.sleep(100)
        job.cancel()
        assertTrue(collected.any { it == setOf("s1", "s2") })
        assertTrue(collected.any { it.isEmpty() })
    }

    @Test
    fun `C10_isManaged反映registry成员资格`() {
        assertFalse(coordinator.isManaged("s1"))
        coordinator.connect(config("s1"))
        assertTrue(coordinator.isManaged("s1"))
        coordinator.disconnect("s1")
        assertFalse(coordinator.isManaged("s1"))
    }
}
