package dev.leonardo.ocbeacon.data.dto.response

import kotlinx.serialization.Serializable

@Serializable
data class PtyInfo(
    val id: String,
    val title: String,
    val command: String,
    val args: List<String>,
    val cwd: String,
    val status: String,
    val pid: Int
)
