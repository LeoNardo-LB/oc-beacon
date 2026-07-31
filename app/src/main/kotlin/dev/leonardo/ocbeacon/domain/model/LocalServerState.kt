package dev.leonardo.ocbeacon.domain.model

data class LocalServerState(
    val status: String = "unavailable",
    val message: String? = null,
    val fixCommand: String? = null,
    val requiresOverlaySettings: Boolean = false
)
