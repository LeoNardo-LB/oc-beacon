package dev.leonardo.ocbeacon.domain.model

import kotlinx.serialization.Serializable

/**
 * DSH Workspace 注册项（backlog #311 Task1 数据层；契约 docs/research/2026-09-05-311-wire-contracts.md ①-d）。
 *
 * wire = WorkspaceView（workspace-controller types.d.ts）：{workspaceId, path,
 * title, sessionIds[], createdAt, updatedAt}——**名字键 = title**（服务器 create
 * 默认 basename(path)，重复允许）；归属关系 = sessionIds 显式数组（host 维护），
 * session 行自身无 workspaceId 仅 cwd；未入组会话 = stray 按 cwd/recency 兜底。
 * createdAt/updatedAt（ISO-8601）不进域模型（时间戳单位双态坑位 §5 同款裁决）。
 */
@Serializable
data class Workspace(
    val workspaceId: String,
    /** Canonical host 目录路径（fs.realpath，create 后不再改写）。 */
    val path: String,
    /** 用户可见标题（名字键——UI 展示取此字段，不取 path 尾段）。 */
    val title: String,
    /** 计入该 workspace 的会话（手动序；成员资格 = 数组包含 + cwd 匹配）。 */
    val sessionIds: List<String> = emptyList(),
)

/**
 * workspace 快照（#311 Task1）：完整注册表 + 归档会话集合。
 *
 * 数据源 = workspace/follow baseline（每代重连恰一帧，集合替换语义）；归档增量
 * {type:'archived'} 只替换 [archivedSessionIds]（workspaces 保持）。瞬态语义：
 * 不入 Room/历史/不重放（对齐 DshQueueStore 先例）。
 */
@Serializable
data class WorkspaceSnapshot(
    val workspaces: List<Workspace> = emptyList(),
    /** 归档会话 id 完整集合（集合替换式——回执/帧即新集合，非增量合并）。 */
    val archivedSessionIds: List<String> = emptyList(),
)
