package dev.leonardo.ocbeacon.domain.model

data class FileNode(
    val name: String,
    val path: String,
    val absolute: String,
    val type: FileType,
    val ignored: Boolean,
    val size: Long? = null,
    val modified: Long? = null
)

enum class FileType { FILE, DIRECTORY }

fun FileNode.isDirectory() = type == FileType.DIRECTORY

/**
 * 服务器路径集合（home/state/config/worktree/directory）。
 * 与 [dev.leonardo.ocbeacon.data.dto.response.ServerPaths] 对应的 domain 值类型。
 */
data class ServerPaths(
    val home: String = "",
    val state: String = "",
    val config: String = "",
    val worktree: String = "",
    val directory: String = ""
)
