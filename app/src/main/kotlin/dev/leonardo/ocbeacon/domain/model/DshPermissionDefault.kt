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
    /** #283：settings.describe schema enum 的档名集（部署权威）；空 = 描述缺席，
     *  UI 回退已知三档常量。 */
    val options: List<String> = emptyList(),
)
