package dev.leonardo.ocbeacon.domain.model

import kotlinx.serialization.Serializable

@Serializable
enum class TagType { USER, FAVORITE }

/** 内置收藏标签 id —— 每个服务器固定一个，承载收藏星标。 */
const val FAVORITE_TAG_ID = "builtin:favorite"

@Serializable
data class Tag(
    val id: String,
    val name: String,
    val color: String = "blue",
    val icon: String = "folder",
    val type: TagType = TagType.USER,
    val createdAt: Long = 0,
)
