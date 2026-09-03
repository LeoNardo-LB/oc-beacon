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
import org.junit.Assert.assertFalse
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
            // #317：0.1.2 探测/cookie 注册表（SSE 路径 relaxed mock 不触发）
            dshConnectionRegistry = mockk(relaxed = true),
            transportFailureTap = dev.leonardo.ocbeacon.data.api.TransportFailureTap(),
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
            // #317：0.1.2 探测/cookie 注册表（SSE 路径 relaxed mock 不触发）
            dshConnectionRegistry = mockk(relaxed = true),
            transportFailureTap = dev.leonardo.ocbeacon.data.api.TransportFailureTap(),
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

    // ============ #304：重连风暴不得掐死 session.list 基线 ============

    /**
     * 场景：SSE attempt 立即失败（重连风暴形态）→ 主循环 finally
     * cancelAndJoin(preloadJob) 掐向**正在拉取中**的 session.list——
     * 正文预载（listSessions+setSessions）必须像 #278 播种一样 NonCancellable
     * （否则连接成功后列表基线丢失=短暂空白，直到下一轮重跑自愈）。
     */
    @Test
    fun `reconnect storm does not kill in-flight session list baseline`() {
        val fileApi = mockk<FileApi>()
        val sessionApi = mockk<SessionApi>()
        val sseClientV2 = mockk<SseClientV2>(relaxUnitFun = true)
        val dispatcher = mockk<EventDispatcher>(relaxed = true)
        val settingsRepository = mockk<SettingsRepository>()

        // 1 个 project → 多项目分支；listSessions 挂 250ms 模拟在途网络
        coEvery { fileApi.listProjects(any()) } returns listOf(
            dev.leonardo.ocbeacon.domain.model.Project(id = "p1", worktree = "/w")
        )
        coEvery { sessionApi.listSessions(any(), any(), any(), any(), any()) } coAnswers {
            delay(250)
            listOf(
                dev.leonardo.ocbeacon.domain.model.Session(
                    id = "ses_baseline",
                    time = dev.leonardo.ocbeacon.domain.model.Session.Time(1L, 2L),
                )
            )
        }
        // V2 SSE 立即失败——风暴形态（触发 finally cancelAndJoin）
        every { sseClientV2.connectToEvents(any(), any(), any()) } returns
            kotlinx.coroutines.flow.flow<dev.leonardo.ocbeacon.domain.model.SseEvent> {
                throw RuntimeException("storm")
            }
        every { settingsRepository.reconnectMode() } returns flowOf("normal")

        val manager = SseConnectionManager(
            sessionApi = sessionApi,
            messageApi = mockk(relaxed = true),
            fileApi = fileApi,
            sseClient = mockk(relaxed = true),
            sseClientV2 = sseClientV2,
            eventDispatcher = dispatcher,
            settingsRepository = settingsRepository,
            networkMonitor = mockk(relaxed = true),
            sessionStateRepository = mockk(relaxed = true),
            dshConnectionOrchestrator = mockk(relaxed = true),
            dshFrameSourceFactory = mockk(relaxed = true),
            dshRpcClient = mockk(relaxed = true),
            // #317：0.1.2 探测/cookie 注册表（SSE 路径 relaxed mock 不触发）
            dshConnectionRegistry = mockk(relaxed = true),
            transportFailureTap = dev.leonardo.ocbeacon.data.api.TransportFailureTap(),
        )

        val server = ServerConfig(
            id = "server-storm", url = "http://127.0.0.1:4199", name = "Storm",
            apiVersion = dev.leonardo.ocbeacon.domain.model.ApiVersion.V2,
        )
        manager.startConnection(server) { _, _ -> }

        // SSE 立即失败 → cancelAndJoin 掐向 delay 中的 listSessions——
        // 修复前：被掐（红）；修复后：NonCancellable 跑完并 setSessions（绿）
        io.mockk.verify(timeout = 5_000) {
            dispatcher.setSessions(match { it == "server-storm" }, any())
        }
        manager.stopAllConnections()
    }

    // ============ #307：传输失败 kick 冷却节流 ============

    /**
     * 根因（真机 3/3 定罪）：reconnectServer 守卫 finally 即释放，「连接启动→毫秒级
     * 失败→tap→kick」正反馈 8ms/轮 ≈375 请求/s → 867 OkHttp Dispatch 线程 → pthread_create
     * OOM 崩溃。冷却窗打破环路（退避由 streamLoop backoff 接管）。
     */
    @Test
    fun `transport failure kick throttled within cooldown window`() {
        val manager = SseConnectionManager(
            sessionApi = mockk(relaxed = true),
            messageApi = mockk(relaxed = true),
            fileApi = mockk(relaxed = true),
            sseClient = mockk(relaxed = true),
            sseClientV2 = mockk(relaxed = true),
            eventDispatcher = mockk(relaxed = true),
            settingsRepository = mockk<SettingsRepository>(relaxed = true).also {
                every { it.reconnectMode() } returns flowOf("normal")
            },
            networkMonitor = mockk(relaxed = true),
            sessionStateRepository = mockk(relaxed = true),
            dshConnectionOrchestrator = mockk(relaxed = true),
            dshFrameSourceFactory = mockk(relaxed = true),
            dshRpcClient = mockk(relaxed = true),
            // #317：0.1.2 探测/cookie 注册表（SSE 路径 relaxed mock 不触发）
            dshConnectionRegistry = mockk(relaxed = true),
            transportFailureTap = dev.leonardo.ocbeacon.data.api.TransportFailureTap(),
        )
        // 首次 kick 放行；冷却窗内（4999ms）全部节流；窗外（5000ms+）再次放行
        assertTrue(manager.shouldKick("s1", nowMs = 10_000L))
        assertFalse(manager.shouldKick("s1", nowMs = 10_001L))
        assertFalse(manager.shouldKick("s1", nowMs = 14_999L))
        assertTrue(manager.shouldKick("s1", nowMs = 15_000L))
        // 服务器维度独立
        assertTrue(manager.shouldKick("s2", nowMs = 10_002L))
    }

    /**
     * 场景（#307 真机定罪的正反馈环路，端到端）：服务器不可达 → preload 的
     * REST 调用 IOException → 共享 client 拦截器上拍 origin（TransportFailureTap
     * 契约）→ kick → reconnectServer（守卫 finally 即释放）→ cancelAndJoin 掐掉
     * 退避 delay → 新 attempt 的 preload 立即再失败 → 再上拍……无冷却时毫秒级
     * 自旋（真机 6518 kick / 24s ≈ 260/s，OkHttp 线程爆炸 pthread OOM）。
     *
     * 断言：kick 冷却（[SseConnectionManager] TRANSPORT_KICK_COOLDOWN_MS）打破
     * 环路——观察窗内 listProjects 调用数有界（退避接管），风暴形态（数百次）变红。
     */
    @Test
    fun `transport failure feedback loop converges under kick cooldown`() {
        val tap = dev.leonardo.ocbeacon.data.api.TransportFailureTap()
        val fileApi = mockk<FileApi>()
        val sseClientV2 = mockk<SseClientV2>(relaxUnitFun = true)
        val settingsRepository = mockk<SettingsRepository>()
        val listProjectsCalls = AtomicInteger(0)

        // 复刻 Ktor 拦截器契约（TransportFailureTap.install）：传输层失败
        // 先上拍 origin（与 baseUrl 同串），再向调用方抛 IOException。
        coEvery { fileApi.listProjects(any()) } coAnswers {
            listProjectsCalls.incrementAndGet()
            tap.onTransportFailure(firstArg<dev.leonardo.ocbeacon.domain.model.ServerConnection>().baseUrl)
            throw java.io.IOException("connection refused")
        }
        every { sseClientV2.connectToEvents(any(), any(), any()) } returns
            kotlinx.coroutines.flow.flow<dev.leonardo.ocbeacon.domain.model.SseEvent> {
                throw java.io.IOException("connection refused")
            }
        every { settingsRepository.reconnectMode() } returns flowOf("normal")

        val manager = SseConnectionManager(
            sessionApi = mockk(relaxed = true),
            messageApi = mockk(relaxed = true),
            fileApi = fileApi,
            sseClient = mockk(relaxed = true),
            sseClientV2 = sseClientV2,
            eventDispatcher = mockk(relaxed = true),
            settingsRepository = settingsRepository,
            networkMonitor = mockk(relaxed = true),
            sessionStateRepository = mockk(relaxed = true),
            dshConnectionOrchestrator = mockk(relaxed = true),
            dshFrameSourceFactory = mockk(relaxed = true),
            dshRpcClient = mockk(relaxed = true),
            // #317：0.1.2 探测/cookie 注册表（SSE 路径 relaxed mock 不触发）
            dshConnectionRegistry = mockk(relaxed = true),
            transportFailureTap = tap,
        )

        val server = ServerConfig(
            id = "server-307",
            url = "http://127.0.0.1:3080",
            name = "Loop",
            apiVersion = dev.leonardo.ocbeacon.domain.model.ApiVersion.V2,
        )
        manager.startConnection(server) { _, _ -> }

        // 观察窗 7s：无冷却正反馈预期数百~数千次；冷却收敛后仅由退避驱动（~6 次）。
        Thread.sleep(7_000)
        val calls = listProjectsCalls.get()
        manager.stopAllConnections()

        assertTrue(
            "feedback loop must converge: listProjects calls=" + calls + " in 7s window " +
                "(>20 = kick storm shape, pre-#307 真机 260 kick/s 线程爆炸)",
            calls <= 20
        )
    }

    private fun testServer() = ServerConfig(
        id = "server-1",
        url = "http://127.0.0.1:4199",
        name = "Test",
    )
}
