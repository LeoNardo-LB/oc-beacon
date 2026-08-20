package dev.leonardo.ocbeacon.service

import dev.leonardo.ocbeacon.data.api.NetworkMonitor
import dev.leonardo.ocbeacon.data.api.SseClient
import dev.leonardo.ocbeacon.data.api.file.FileApi
import dev.leonardo.ocbeacon.data.api.message.MessageApi
import dev.leonardo.ocbeacon.data.api.session.SessionApi
import dev.leonardo.ocbeacon.data.api.v2.SseClientV2
import dev.leonardo.ocbeacon.data.repository.EventDispatcher
import dev.leonardo.ocbeacon.data.repository.SessionStateService
import dev.leonardo.ocbeacon.data.repository.SettingsDataStore
import dev.leonardo.ocbeacon.domain.model.ServerConfig
import dev.leonardo.ocbeacon.domain.model.SseEvent
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

class SseConnectionManagerTest {
    @Test
    fun `connections map is ConcurrentHashMap`() {
        val field = SseConnectionManager::class.java.getDeclaredField("connections")
        assertTrue(
            "connections should be ConcurrentHashMap",
            ConcurrentHashMap::class.java.isAssignableFrom(field.type)
        )
    }

    @Test
    fun `timeoutTrackers map is ConcurrentHashMap`() {
        val field = SseConnectionManager::class.java.getDeclaredField("timeoutTrackers")
        assertTrue(
            "timeoutTrackers should be ConcurrentHashMap",
            ConcurrentHashMap::class.java.isAssignableFrom(field.type)
        )
    }

    // ============ 泄漏修复（路径 1a）：reconnect 孤儿 job 守卫 ============

    /**
     * 场景（泄漏路径 1a）：reconnectServer 在 cancelAndJoin 挂起期间，
     * Service onDestroy → stopAllConnections() 清空 connections。恢复后
     * startSseConnection 启动的新 job 因 computeIfPresent 未命中成为孤儿——
     * 守卫必须将其取消，否则其闭包持有已销毁 Service 的 onEvent
     * （::processEvent）回调，SSE 流永不退出（僵尸协程）。
     *
     * 观测点：真正开始收集 SSE 流的存活协程会递增 [AtomicInteger] 计数
     *（sseCollectCount / onEventCount）；被守卫取消的 job 在 flow 体首个
     * ensureActive 处终止，两个计数恒为 0。
     */
    @Test
    fun `reconnect cancels orphaned SSE job when server removed during cancelAndJoin`() {
        val fileApi = mockk<FileApi>()
        val sseClient = mockk<SseClient>()
        val settingsRepository = mockk<SettingsDataStore>()

        // job1 进入 preLoad 后在 NonCancellable 窗口内抵抗取消，
        // 制造出 reconnectServer.cancelAndJoin 的挂起窗口
        val enteredPreLoad = CountDownLatch(1)
        coEvery { fileApi.listProjects(any()) } coAnswers {
            enteredPreLoad.countDown()
            withContext(NonCancellable) { delay(400) }
            emptyList()
        }

        // 无限事件流：真实 SSE 流永不自行完成——未被取消的孤儿会持续收集
        val sseCollectCount = AtomicInteger(0)
        every { sseClient.connectToGlobalEvents(any()) } returns flow {
            // 真正开始收集处做取消检查：被取消的协程在此终止、不递增计数；
            // 未被取消的孤儿则开始消费事件（守卫失效时测试变红）
            currentCoroutineContext().ensureActive()
            sseCollectCount.incrementAndGet()
            while (true) {
                emit(SseEvent.ServerHeartbeat)
                delay(50)
            }
        }
        every { settingsRepository.reconnectMode } returns flowOf("normal")

        val manager = SseConnectionManager(
            sessionApi = mockk(relaxed = true),
            messageApi = mockk(relaxed = true),
            fileApi = fileApi,
            sseClient = sseClient,
            sseClientV2 = mockk(relaxed = true),
            eventDispatcher = mockk(relaxed = true),
            settingsRepository = settingsRepository,
            networkMonitor = mockk(relaxed = true),
            sessionStateService = mockk(relaxed = true),
        )

        val onEventCount = AtomicInteger(0)
        manager.startConnection(testServer()) { _, _ -> onEventCount.incrementAndGet() }

        // 1. 等 job1 进入 preLoad 的 NonCancellable 窗口
        assertTrue("job1 should reach preLoadSessions", enteredPreLoad.await(5, TimeUnit.SECONDS))

        // 2. 后台触发 reconnectAll（cancelAndJoin 将挂起至窗口结束）
        val reconnectDone = AtomicBoolean(false)
        thread(isDaemon = true) {
            runBlocking { manager.reconnectAll() }
            reconnectDone.set(true)
        }

        // 3. 在挂起窗口内模拟 onDestroy：清空单例 connections
        Thread.sleep(100)
        manager.stopAllConnections()

        // 4. 等 reconnectAll 结束（cancelAndJoin 窗口 400ms + 余量）
        val deadline = System.currentTimeMillis() + 5_000
        while (!reconnectDone.get() && System.currentTimeMillis() < deadline) Thread.sleep(20)
        assertTrue("reconnectAll should complete", reconnectDone.get())

        // 5. 若守卫缺失：孤儿 job 会在 preLoad（≤400ms）后开始消费无限 SSE 流。
        //    留足观察窗口后断言：从未真正收集、从未经死回调投递事件。
        Thread.sleep(900)
        assertEquals("orphaned SSE job must never start collecting the stream", 0, sseCollectCount.get())
        assertEquals("orphaned SSE job must never deliver events via dead onEvent callback", 0, onEventCount.get())
        assertTrue("connections must stay empty after stopAllConnections", manager.connections.isEmpty())
    }

    private fun testServer() = ServerConfig(
        id = "server-1",
        url = "http://127.0.0.1:4199",
        name = "Test",
    )
}
