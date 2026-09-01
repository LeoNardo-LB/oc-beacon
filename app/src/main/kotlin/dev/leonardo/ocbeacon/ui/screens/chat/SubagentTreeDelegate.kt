package dev.leonardo.ocbeacon.ui.screens.chat

import dev.leonardo.ocbeacon.domain.model.SubagentChild
import dev.leonardo.ocbeacon.logging.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private const val TAG = "SubagentTree"

/** AgentSheet 多级树可见行（深度缩进 + 展开态 + 状态点数据）。 */
data class SubagentTreeRow(
    val sessionId: String,
    /** 缩进深度（根层 0，逐层 +1；UI 按深度缩进）。 */
    val depth: Int,
    /** 主标签：DSH label（缺失回退 title 投影）/ V2 现有 title / 兜底 id。 */
    val label: String,
    val isRunning: Boolean,
    /** 仅有子代的行显示展开箭头（DSH=hasChildren 字段；V2=本地子代数）。 */
    val hasChildren: Boolean,
    val isExpanded: Boolean = false,
    /** DSH diagnostic 行（corrupt/unsupported/unavailable）：灰显不可点。 */
    val isDiagnostic: Boolean = false,
    /** diagnostic 原因原串（UI 本地化显示；非 diagnostic 行 null）。 */
    val reason: String? = null,
)

/** 树交互态快照（行 + 懒加载中节点集合）。 */
data class SubagentTreeUiState(
    val rows: List<SubagentTreeRow> = emptyList(),
    val loadingIds: Set<String> = emptySet(),
)

/** 本地 session 镜像快照（OpenCode 递归子树 + DSH 降级/label 回退数据源）。 */
data class SubagentLocalSnapshot(
    val rootSessionId: String,
    /** parentId → 直接子代（创建时间倒序；TaskAggregator 派生）。 */
    val childrenByParent: Map<String, List<SubagentChild>> = emptyMap(),
    /** sessionId → title（DSH label 缺失时的回退投影源）。 */
    val titleById: Map<String, String> = emptyMap(),
)

/** AgentSheet 树交互入口（面板打开刷新根层 / 展开收起节点）。 */
interface SubagentTreeController {
    /** 面板打开时拉取根层——DSH 发 subagent.list；OpenCode no-op 语义（无域）。 */
    fun refreshRoot()

    /** 展开（DSH 未缓存层先懒加载）/ 收起节点。 */
    fun toggle(sessionId: String)
}

/**
 * AgentSheet 多级树状态机（2026-09 树化，已裁决双轨数据源）：
 *
 * - **DSH**（fetcher 命中）：subagent.list 权威域——面板打开 [refreshRoot] 拉根层、
 *   展开未缓存层时逐层懒加载；失败软降级本地递归（AppLogger.w 告警，
 *   仓库既有降级风格），refreshRoot 重试成功即恢复权威域。diagnostic 行灰显不可点。
 * - **OpenCode V1/V2**（fetcher 返回 null）：本地 session 镜像按 parentId 递归
 *   子树（visited 防环），展开/收起纯本地重算（localMode 锁定后不再逐层探测）。
 *
 * 行内容 MVP（用户裁决）：状态点 + 主标签 + 深度缩进；不放 token/时长指标。
 */
internal class SubagentTreeHolder(
    private val scope: CoroutineScope,
    /** DSH 权威目录拉取（ChatViewModel 注入 sessionRepository.listSubagentChildren）；null = OpenCode 本地模式。 */
    private val fetcher: (suspend (parentSessionId: String) -> Result<List<SubagentChild>?>)?,
    snapshots: Flow<SubagentLocalSnapshot>,
) : SubagentTreeController {

    private val expandedIds = MutableStateFlow<Set<String>>(emptySet())
    private val loadingIds = MutableStateFlow<Set<String>>(emptySet())
    /** DSH 逐层缓存：parentSessionId → 目录行（展开过即缓存）。 */
    private val catalog = MutableStateFlow<Map<String, List<SubagentChild>>>(emptyMap())
    /** DSH 域故障软降级标记（本地递归顶上；refreshRoot 成功后复位）。 */
    private val degraded = MutableStateFlow(false)
    /** OpenCode 无权威域标记（根探测 null）——本地模式，toggle 不再发请求。 */
    private val localMode = MutableStateFlow(false)
    private val rootId = MutableStateFlow("")
    private var rootFetchJob: Job? = null

    init {
        // 会话切换 → 清空树状态（展开集/缓存/降级标记）；根层拉取由面板打开
        // （refreshRoot）触发——面板未开时本地镜像行即可兜底渲染。
        scope.launch {
            snapshots.map { it.rootSessionId }.distinctUntilChanged().collect { root ->
                rootId.value = root
                expandedIds.value = emptySet()
                loadingIds.value = emptySet()
                catalog.value = emptyMap()
                degraded.value = false
                localMode.value = false
            }
        }
    }

    val state: StateFlow<SubagentTreeUiState> = combine(
        snapshots,
        expandedIds,
        loadingIds,
        catalog,
        degraded,
    ) { snapshot, expanded, loading, catalogMap, isDegraded ->
        val rows = if (!isDegraded && catalogMap.containsKey(snapshot.rootSessionId)) {
            buildCatalogTreeRows(snapshot.rootSessionId, catalogMap, expanded, snapshot.titleById)
        } else {
            buildLocalTreeRows(snapshot, expanded)
        }
        SubagentTreeUiState(rows = rows, loadingIds = loading)
    }.stateIn(scope, SharingStarted.Eagerly, SubagentTreeUiState())

    override fun refreshRoot() {
        val fetch = fetcher ?: return
        val root = rootId.value.takeIf { it.isNotBlank() } ?: return
        rootFetchJob?.cancel()
        rootFetchJob = scope.launch {
            loadingIds.update { it + root }
            val result = fetch(root)
            val entries = result.getOrNull()
            if (result.isSuccess) {
                if (entries != null) {
                    catalog.update { it + (root to entries) }
                    localMode.value = false
                } else {
                    // OpenCode：无权威域（探测一次即锁定本地模式）
                    localMode.value = true
                }
                degraded.value = false
            } else {
                AppLogger.w(
                    TAG,
                    "subagent.list failed for root (falling back to local mirror): " +
                        (result.exceptionOrNull()?.message ?: "unknown"),
                )
                degraded.value = true
            }
            loadingIds.update { it - root }
        }
    }

    override fun toggle(sessionId: String) {
        if (sessionId in expandedIds.value) {
            expandedIds.update { it - sessionId }
            return
        }
        val fetch = fetcher
        if (fetch != null && !degraded.value && !localMode.value && sessionId !in catalog.value) {
            // DSH 逐层懒加载：展开未缓存层时才请求该层（点箭头=发请求）。
            scope.launch {
                loadingIds.update { it + sessionId }
                val result = fetch(sessionId)
                val entries = result.getOrNull()
                if (result.isSuccess) {
                    entries?.let { list -> catalog.update { it + (sessionId to list) } }
                } else {
                    AppLogger.w(
                        TAG,
                        "subagent.list failed for $sessionId (falling back to local mirror): " +
                            (result.exceptionOrNull()?.message ?: "unknown"),
                    )
                    degraded.value = true
                }
                loadingIds.update { it - sessionId }
                expandedIds.update { it + sessionId }
            }
        } else {
            expandedIds.update { it + sessionId }
        }
    }
}

/**
 * 树展平共用骨架（#282-c：本地镜像/DSH 目录双 DFS 同形提取）。
 * 防环：visited 集合——查表出现 parentId 环时截断，不无限递归。
 * 调用方提供三差异点：子代查表 [childrenOf]、行映射 [rowOf]、
 * 下钻谓词 [shouldDescend]（骨架统一叠加"已展开才深入"条件）。
 */
private fun flattenTreeRows(
    rootId: String,
    expanded: Set<String>,
    childrenOf: (String) -> List<SubagentChild>,
    rowOf: (SubagentChild, Int) -> SubagentTreeRow,
    shouldDescend: (SubagentTreeRow) -> Boolean,
): List<SubagentTreeRow> {
    val rows = mutableListOf<SubagentTreeRow>()
    fun visit(parentId: String, depth: Int, visited: Set<String>) {
        for (child in childrenOf(parentId)) {
            if (child.sessionId in visited) continue // 防环
            val row = rowOf(child, depth)
            rows += row
            if (shouldDescend(row) && row.sessionId in expanded) {
                visit(row.sessionId, depth + 1, visited + row.sessionId)
            }
        }
    }
    visit(rootId, 0, setOf(rootId))
    return rows
}

/**
 * 本地镜像递归子树展平（OpenCode 权威路径 + DSH 软降级路径）。
 * hasChildren 由本地子代表推导；label 缺失兜底裸 id。
 */
internal fun buildLocalTreeRows(
    snapshot: SubagentLocalSnapshot,
    expanded: Set<String>,
): List<SubagentTreeRow> = flattenTreeRows(
    rootId = snapshot.rootSessionId,
    expanded = expanded,
    childrenOf = { parent -> snapshot.childrenByParent[parent].orEmpty() },
    rowOf = { child, depth ->
        SubagentTreeRow(
            sessionId = child.sessionId,
            depth = depth,
            label = child.label ?: child.sessionId,
            isRunning = child.isRunning,
            hasChildren = !snapshot.childrenByParent[child.sessionId].isNullOrEmpty(),
            isExpanded = child.sessionId in expanded,
        )
    },
    shouldDescend = { it.hasChildren },
)

/**
 * DSH subagent.list 缓存展平：hasChildren 权威来自条目字段；label 缺失
 * （one-shot 可选）回退本地镜像 title 投影，再兜底裸 id。diagnostic 行原样
 * 透传（灰显不可点）且不下钻（无子代语义）。
 */
internal fun buildCatalogTreeRows(
    rootSessionId: String,
    catalog: Map<String, List<SubagentChild>>,
    expanded: Set<String>,
    titleById: Map<String, String>,
): List<SubagentTreeRow> = flattenTreeRows(
    rootId = rootSessionId,
    expanded = expanded,
    childrenOf = { parent -> catalog[parent].orEmpty() },
    rowOf = { entry, depth ->
        SubagentTreeRow(
            sessionId = entry.sessionId,
            depth = depth,
            label = entry.label
                ?: titleById[entry.sessionId]
                ?: entry.sessionId,
            isRunning = entry.isRunning,
            hasChildren = entry.hasChildren,
            isExpanded = entry.sessionId in expanded,
            isDiagnostic = entry.isDiagnostic,
            reason = entry.diagnosticReason,
        )
    },
    shouldDescend = { row -> !row.isDiagnostic && row.hasChildren },
)
