package dev.leonardo.octether.ui

import kotlinx.coroutines.flow.SharingStarted

/**
 * Standard sharing started policy for all ViewModel → UI StateFlows.
 *
 * 5-second grace period keeps the upstream flow active through configuration changes
 * (rotation, dark mode toggle) and brief background excursions, avoiding reconnection
 * storms on the SSE pipeline.
 */
val WhileSubscribed5s = SharingStarted.WhileSubscribed(5000)
