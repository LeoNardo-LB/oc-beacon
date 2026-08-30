package dev.leonardo.ocbeacon.service

import dev.leonardo.ocbeacon.data.api.NetworkMonitor
import dev.leonardo.ocbeacon.data.api.SseClient
import dev.leonardo.ocbeacon.data.api.file.FileApi
import dev.leonardo.ocbeacon.data.api.message.MessageApi
import dev.leonardo.ocbeacon.data.api.session.SessionApi
import dev.leonardo.ocbeacon.data.api.v2.SseClientV2
import dev.leonardo.ocbeacon.data.repository.EventDispatcher
import dev.leonardo.ocbeacon.data.repository.SessionStateService
import dev.leonardo.ocbeacon.domain.repository.SettingsRepository
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
     * 观测点（#150 方向② 后语义更新，2026-08-21）：SSE 先行架构下主循环不再被
     * preload 阻塞，流会立即开始收集——旧断言"两个计数恒为 0"依赖串行窗口已不成立。
     * 等价安全性质改为：**stopAllConnections 移除后**，所有 job 必须自愈终止
     *（takeWhile 在条目缺失处完成流）——观察窗口内收集计数与事件投递计数
     * 均不再增长（无僵尸持续消费、无死回调投递），connections 保持空。
     */
    @Test
    fun `reconnect cancels orphaned SSE job when server removed during cancelAndJoin`() {
        val fileApi = mockk<FileApi>()
        val sseClient = mockk<SseClient>()
        val settingsRepository = mockk<SettingsRepository>()

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
        every { settingsRepository.reconnectMode() } returns flowOf("normal")

        val manager = SseConnectionManager(
            sessionApi = mockk(relaxed = true),
            messageApi = mockk(relaxed = true),
            fileApi = fileApi,
            sseClient = sseClient,
            sseClientV2 = mockk(relaxed = true),
            eventDispatcher = mockk(relaxed = true),
            settingsRepository = settingsRepository,
            networkMonitor = mockk(relaxed = true),
            sessionStateRepository = mockk(relaxed = true),
            // #276：DSH 分支依赖（本测试仅走 SSE 路径——relaxed mock 不触发）
            dshConnectionOrchestrator = mockk(relaxed = true),
            dshFrameSourceFactory = mockk(relaxed = true),
            dshRpcClient = mockk(relaxed = true),
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

        // 5. 等一切尘埃落定（reconnect 的 job2 可能短暂收集后自愈退出），拍快照。
        Thread.sleep(900)
        val collectSnapshot = sseCollectCount.get()
        val eventsSnapshot = onEventCount.get()
        // 再观察一个窗口：计数必须稳定——若守卫/自愈失效，僵尸 job 会持续
        // 消费无限流（flow 体每 50ms emit，计数持续变化）。
        Thread.sleep(900)
        assertEquals(
            "no zombie may keep consuming the stream after removal (self-terminate)",
            collectSnapshot, sseCollectCount.get()
        )
        assertEquals(
            "no events may be delivered via dead onEvent callback after removal",
            eventsSnapshot, onEventCount.get()
        )
        assertTrue("connections must stay empty after stopAllConnections", manager.connections.isEmpty())
    }

    // ============ #150 方向②（2026-08-21）：SSE 先行，不被 preload 阻塞 ============

    /**
     * 场景（issue #1 遗留"V1 连接慢"主因）：preLoadSessions 阻塞（服务器慢/多项目）时，
     * 旧行为要等整个预加载跑完才建 SSE → "已连接"翻转被阻塞。并行化后 SSE 首事件
     * 到达即翻转 connectedServerIds——预加载仍在进行中。
     *
     * 观测点：listProjects 挂在 latch 上（模拟 ~500ms 慢预加载）；SSE 流立即发射
     * server.connected 并保持打开。若实现退回串行：断言点超时（connectedIds 恒空）。
     */
    @Test
    fun `connected flips on first SSE event while preload still in flight`() {
        val fileApi = mockk<FileApi>()
        val sseClient = mockk<SseClient>()
        val settingsRepository = mockk<SettingsRepository>()

        // 预加载阻塞在 listProjects（受 latch 控制，模拟慢服务器）
        val preloadEntered = CountDownLatch(1)
        val preloadRelease = CountDownLatch(1)
        coEvery { fileApi.listProjects(any()) } coAnswers {
            preloadEntered.countDown()
            preloadRelease.await(5, TimeUnit.SECONDS)
            emptyList()
        }
        every { settingsRepository.reconnectMode() } returns flowOf("normal")

        // SSE 流立即发射首事件并保持打开（长连接）
        every { sseClient.connectToGlobalEvents(any()) } returns flow {
            emit(SseEvent.ServerConnected)
            while (true) { delay(50) }
        }

        val manager = SseConnectionManager(
            sessionApi = mockk(relaxed = true),
            messageApi = mockk(relaxed = true),
            fileApi = fileApi,
            sseClient = sseClient,
            sseClientV2 = mockk(relaxed = true),
            eventDispatcher = mockk(relaxed = true),
            settingsRepository = settingsRepository,
            networkMonitor = mockk(relaxed = true),
            sessionStateRepository = mockk(relaxed = true),
            // #276：DSH 分支依赖（本测试仅走 SSE 路径——relaxed mock 不触发）
            dshConnectionOrchestrator = mockk(relaxed = true),
            dshFrameSourceFactory = mockk(relaxed = true),
            dshRpcClient = mockk(relaxed = true),
        )

        manager.startConnection(testServer()) { _, _ -> }

        // 等预加载进入阻塞窗口（证明它确实在跑且未完成）
        assertTrue("preload should be in flight", preloadEntered.await(5, TimeUnit.SECONDS))

        // 核心断言：预加载仍阻塞时，首个 SSE 事件已把服务器翻转为"已连接"
        val deadline = System.currentTimeMillis() + 5_000
        while (!manager.connectedServerIds.value.contains("server-1") && System.currentTimeMillis() < deadline) {
            Thread.sleep(20)
        }
        assertTrue(
            "connected should flip while preload still in flight (SSE-first)",
            manager.connectedServerIds.value.contains("server-1")
        )

        // 清理：释放预加载并断开，防泄漏干扰其他测试
        preloadRelease.countDown()
        manager.stopAllConnections()
    }

    private fun testServer() = ServerConfig(
        id = "server-1",
        url = "http://127.0.0.1:4199",
        name = "Test",
    )
}
