package dev.leonardo.ocbeacon.data.dto.request

import kotlinx.serialization.Serializable

@Serializable
data class PtyCreateRequest(
    val title: String? = null,
    val cwd: String? = null
)

@Serializable
data class PtyUpdateRequest(
    val title: String? = null,
    val size: PtySize? = null
)

@Serializable
data class PtySize(
    val rows: Int,
    val cols: Int
)
