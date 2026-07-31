package dev.leonardo.octether.ui.screens.chat.tools

import dev.leonardo.octether.domain.model.Part

/**
 * Request to view a tool's file snapshot in the FileViewer (spec §5.1-5.4).
 *
 * Created by Read/Write/Edit tool cards when the user taps ↗.
 * [part] is carried directly so NavGraph can cache the snapshot without
 * looking up message state.
 */
data class ViewToolRequest(
    val filePath: String,
    val source: String,
    val part: Part.Tool
)
