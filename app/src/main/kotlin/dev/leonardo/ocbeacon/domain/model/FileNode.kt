package dev.leonardo.ocbeacon.domain.model
data class FileNode(val name: String, val path: String, val absolute: String,
    val type: FileType, val ignored: Boolean, val size: Long? = null, val modified: Long? = null)
enum class FileType { FILE, DIRECTORY }
fun FileNode.isDirectory() = type == FileType.DIRECTORY
