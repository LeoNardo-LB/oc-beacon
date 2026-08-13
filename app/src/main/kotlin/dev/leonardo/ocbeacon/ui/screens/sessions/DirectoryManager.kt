package dev.leonardo.ocbeacon.ui.screens.sessions

import dev.leonardo.ocbeacon.logging.AppLogger

import dev.leonardo.ocbeacon.BuildConfig
import dev.leonardo.ocbeacon.domain.model.FileNode
import dev.leonardo.ocbeacon.domain.model.FileType
import dev.leonardo.ocbeacon.domain.model.ServerPaths
import dev.leonardo.ocbeacon.domain.model.isDirectory
import dev.leonardo.ocbeacon.domain.repository.FileRepository
import dev.leonardo.ocbeacon.domain.usecase.CreateDirectoryUseCase
import dev.leonardo.ocbeacon.domain.usecase.GetServerPathsUseCase
import dev.leonardo.ocbeacon.domain.usecase.ProbeDirectoryUseCase
import dev.leonardo.ocbeacon.domain.usecase.SearchDirectoriesUseCase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withTimeoutOrNull

private const val TAG = "DirectoryManager"

/** 单个盘符探测的最长等待时间。避免不存在的盘符/网络盘拖慢整体。 */
private const val DRIVE_PROBE_TIMEOUT_MS = 2_000L

/** 盘符列表缓存时长。盘符变化极罕见，短时间内重复打开不应重新探测。 */
private const val DRIVES_CACHE_TTL_MS = 30_000L

/**
 * 从 SessionListViewModel 抽取的目录浏览委托。
 *
 * 持有所有服务器文件系统操作：列表、搜索、探测盘符、
 * 创建目录。在委托生命周期内缓存 [ServerPaths]。
 *
 * 所有底层网络访问经 domain UseCase/Repository，不再直调 Api。
 */
class DirectoryManager(
    private val serverId: String,
    private val getServerPathsUseCase: GetServerPathsUseCase,
    private val probeDirectoryUseCase: ProbeDirectoryUseCase,
    private val searchDirectoriesUseCase: SearchDirectoriesUseCase,
    private val createDirectoryUseCase: CreateDirectoryUseCase,
    private val fileRepository: FileRepository,
) {

    private var cachedServerPaths: ServerPaths? = null

    @Volatile
    private var cachedDrives: List<FileNode>? = null

    @Volatile
    private var cachedDrivesAt: Long = 0L

    /** 目录列表缓存（2026-08-13 性能优化）：已浏览目录秒开，消除 500ms 网络往返感知延迟。 */
    private data class DirCacheEntry(val items: List<FileNode>, val at: Long)

    private val dirCache = java.util.concurrent.ConcurrentHashMap<String, DirCacheEntry>()

    /** 目录列表缓存时长：目录内容变化不频繁，30s 内重复浏览直接命中。 */
    private companion object {
        const val DIR_CACHE_TTL_MS = 30_000L
        /** 缓存条目上限：防止无限增长（2026-08-13 用户报告内存问题——
         *  旧实现只 put 不清理，浏览大量目录时条目永驻 → 内存泄漏） */
        const val DIR_CACHE_MAX_ENTRIES = 200
    }

    /** 写入缓存时清理过期/超限条目（LRU 近似：先清过期，仍超限清最旧）。 */
    private fun putDirCache(key: String, entry: DirCacheEntry) {
        if (dirCache.size >= DIR_CACHE_MAX_ENTRIES) {
            val now = System.currentTimeMillis()
            val expired = dirCache.entries.filter { now - it.value.at >= DIR_CACHE_TTL_MS }
            if (expired.isNotEmpty()) {
                expired.forEach { dirCache.remove(it.key) }
            } else {
                // 全为新鲜条目且超限：移除最旧的（近似 LRU）
                val oldest = dirCache.entries.minByOrNull { it.value.at }
                oldest?.let { dirCache.remove(it.key) }
            }
        }
        dirCache[key] = entry
    }

    /** 获取服务器路径，结果在委托生命周期内缓存。 */
    suspend fun getServerPaths(): ServerPaths {
        if (cachedServerPaths == null) {
            cachedServerPaths = try {
                getServerPathsUseCase(serverId).getOrThrow()
            } catch (e: Exception) {
                AppLogger.e(TAG, "Failed to get server paths", e)
                ServerPaths()
            }
            if (BuildConfig.DEBUG) AppLogger.d(TAG, "Server home directory: ${cachedServerPaths!!.home}")
        }
        return cachedServerPaths!!
    }

    /** 服务器是否运行在 Windows 上（通过主目录路径中的反斜杠检测）。 */
    val isWindowsServer: Boolean
        get() = cachedServerPaths?.home?.contains("\\") == true

    /** 获取服务器主目录。委托给已缓存的 getServerPaths()。 */
    suspend fun getHomeDirectory(): String = getServerPaths().home.ifBlank { "/" }

    /**
     * 通过并行探测盘符列出可用的 Windows 盘符。
     *
     * 返回 [Flow]：每个盘符探测完成即发射，UI 可边收集边显示（先看到 C:/D: 等常用盘符），
     * 无需等最慢的请求。单盘符探测超时 [DRIVE_PROBE_TIMEOUT_MS]，结果缓存 [DRIVES_CACHE_TTL_MS]。
     */
    suspend fun listWindowsDrives(): Flow<FileNode> = flow {
        val cached = cachedDrives
        if (cached != null && System.currentTimeMillis() - cachedDrivesAt < DRIVES_CACHE_TTL_MS) {
            cached.forEach { emit(it) }
            return@flow
        }

        val collected = mutableListOf<FileNode>()
        coroutineScope {
            ('C'..'Z').map { letter ->
                async {
                    val drivePath = "$letter:\\"
                    try {
                        val node = withTimeoutOrNull(DRIVE_PROBE_TIMEOUT_MS) {
                            val exists = probeDirectoryUseCase(serverId, drivePath).getOrDefault(false)
                            if (exists) {
                                FileNode(
                                    name = "$letter:",
                                    path = drivePath,
                                    absolute = drivePath,
                                    type = FileType.DIRECTORY,
                                    ignored = false,
                                )
                            } else {
                                null
                            }
                        }
                        if (node != null) {
                            synchronized(collected) { collected += node }
                            emit(node)
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        // 单个盘符探测失败忽略
                    }
                }
            }.awaitAll()
        }

        cachedDrives = collected
        cachedDrivesAt = System.currentTimeMillis()
    }

    /** 列出服务器上指定路径中的目录（30s 内存缓存，已浏览目录秒开）。 */
    suspend fun listDirectories(directory: String): List<FileNode> {
        val normalized = directory.replace('\\', '/').trimEnd('/').ifBlank { "/" }
        // 缓存命中（30s 内）→ 直接返回，避免 500ms 网络往返感知延迟
        val cached = dirCache[normalized]
        val now = System.currentTimeMillis()
        if (cached != null && now - cached.at < DIR_CACHE_TTL_MS) {
            if (BuildConfig.DEBUG) AppLogger.d(TAG, "listDirectories CACHE HIT: dir=$normalized items=${cached.items.size}")
            return cached.items
        }
        // 性能监控（2026-08-13 用户反馈目录点击卡顿）：记录耗时与条目数，
        // 阈值 >500ms 打 warn（慢目录/网络），正常打 debug
        val start = System.currentTimeMillis()
        return try {
            val result = fileRepository.listDirectory(serverId, directory, "").getOrThrow().filter { it.isDirectory() }
            putDirCache(normalized, DirCacheEntry(result, System.currentTimeMillis()))
            val elapsed = System.currentTimeMillis() - start
            if (elapsed > 500) {
                AppLogger.w(TAG, "listDirectories SLOW: dir=$directory items=${result.size} took=${elapsed}ms")
            } else if (BuildConfig.DEBUG) {
                AppLogger.d(TAG, "listDirectories: dir=$directory items=${result.size} took=${elapsed}ms")
            }
            result
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to list directory: $directory took=${System.currentTimeMillis() - start}ms", e)
            emptyList()
        }
    }

    /** 在基础目录范围内搜索匹配查询的目录。 */
    suspend fun searchDirectories(query: String, directory: String): List<String> {
        return try {
            searchDirectoriesUseCase(serverId, directory, query, 50).getOrDefault(emptyList())
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to search directories", e)
            emptyList()
        }
    }

    /** 在当前浏览的路径下创建目录。 */
    suspend fun createDirectory(parentDirectory: String, folderName: String): Result<String> =
        createDirectoryUseCase(serverId, parentDirectory, folderName)
}
