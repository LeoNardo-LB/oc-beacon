package dev.leonardo.octether.domain.model
data class FileContent(val path: String, val type: ContentType, val content: String, val mimeType: String? = null)
enum class ContentType { TEXT, BINARY }
