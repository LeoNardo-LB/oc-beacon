package dev.leonardo.ocbeacon.domain.model

/**
 * DSH 新会话默认权限档（settings.describe ns=permission 的 defaultPreset 投影）。
 *
 * [currentValue] 即当前默认档名（read-only / workspace-write / danger-full-access，
 * 档数由部署预设表决定）；[revision] 为 settings 文档修订号，写回 settings.mutate 时
 * 作为 expectedRevision 做乐观并发（陈旧的 revision 会得到 settings-conflict）。
 */
data class DshPermissionDefault(
    val currentValue: String,
    val revision: Long = 0L,
)
