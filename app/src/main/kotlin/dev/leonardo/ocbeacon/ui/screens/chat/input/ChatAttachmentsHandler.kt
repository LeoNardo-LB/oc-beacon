package dev.leonardo.ocbeacon.ui.screens.chat.input

import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.LocalContext
import dev.leonardo.ocbeacon.R
import dev.leonardo.ocbeacon.ui.screens.chat.util.ImageAttachment
import dev.leonardo.ocbeacon.ui.screens.chat.util.AttachmentComparison
import dev.leonardo.ocbeacon.ui.screens.chat.util.buildAttachmentFromUri
import dev.leonardo.ocbeacon.ui.screens.chat.util.extensionForMime
import dev.leonardo.ocbeacon.ui.screens.chat.util.formatFileSize
import kotlinx.coroutines.launch

/**
 * Holds attachment state and launcher triggers for the chat screen.
 * Produced by [rememberAttachmentHandler].
 */
internal class ChatAttachmentsHandler(
    val attachments: MutableList<ImageAttachment>,
    val pickImages: () -> Unit,
    val requestSaveImage: (ByteArray, String, String?) -> Unit,
    val launchExport: (String) -> Unit,
) {
    fun removeAttachment(index: Int) {
        if (index in attachments.indices) {
            attachments.removeAt(index)
        }
    }

    fun clearAttachments() {
        attachments.clear()
    }
}

/**
 * Remember-based factory that creates image picker, SAF export, and image save launchers,
 * plus draft-restore and shared-image consumption side effects.
 *
 * All [ActivityResultLauncher] registrations happen inside this @Composable, satisfying
 * the framework requirement that launchers be declared in composition context.
 */
@Composable
internal fun rememberAttachmentHandler(
    draftAttachmentUris: List<String>,
    compressImages: Boolean,
    imageMaxLongSide: Int,
    imageWebpQuality: Int,
    initialSharedImages: List<Uri> = emptyList(),
    onSharedImagesConsumed: () -> Unit = {},
    onAddDraftAttachment: (String) -> Unit = {},
    onRemoveDraftAttachment: (Int) -> Unit = {},
    onExportSession: (android.content.Context, Uri, (Boolean) -> Unit) -> Unit = { _, _, _ -> },
    onShowSnackbar: suspend (String) -> Unit = {},
): ChatAttachmentsHandler {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    // -- Mutable attachment list --------------------------------------------------
    val attachments = remember { mutableStateListOf<ImageAttachment>() }

    // -- Rebuild attachments from persisted draft URIs on first composition ----------
    LaunchedEffect(draftAttachmentUris, compressImages, imageMaxLongSide, imageWebpQuality) {
        val currentUris = attachments.map { it.uri.toString() }.toSet()
        val draftUriSet = draftAttachmentUris.toSet()
        if (currentUris == draftUriSet) return@LaunchedEffect

        val restored = mutableListOf<ImageAttachment>()
        for (uriStr in draftAttachmentUris) {
            if (uriStr in currentUris) {
                val existing = attachments.first { it.uri.toString() == uriStr }
                restored.add(existing)
                continue
            }
            try {
                val uri = Uri.parse(uriStr)
                if (uriStr.startsWith("data:image/", ignoreCase = true)) {
                    val mime = uriStr.substringAfter("data:").substringBefore(';').ifBlank { "image/png" }
                    val syntheticName = "image.${mime.substringAfter('/', "png")}".lowercase()
                    restored.add(
                        ImageAttachment(
                            uri = uri,
                            mime = mime,
                            filename = syntheticName,
                            dataUrl = uriStr,
                        )
                    )
                    continue
                }
                val prepared = buildAttachmentFromUri(
                    contentResolver = context.contentResolver,
                    uri = uri,
                    compressImages = compressImages,
                    maxLongSidePx = imageMaxLongSide,
                    webpQuality = imageWebpQuality
                )
                if (prepared != null) {
                    restored.add(prepared.attachment)
                }
            } catch (e: Exception) {
                Log.w("ChatScreen", "Failed to restore attachment $uriStr: ${e.message}")
                // Remove invalid URI from draft
                onRemoveDraftAttachment(draftAttachmentUris.indexOf(uriStr))
            }
        }
        attachments.clear()
        attachments.addAll(restored)
    }

    // -- Image picker launcher -----------------------------------------------------
    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetMultipleContents()
    ) { uris: List<Uri> ->
        coroutineScope.launch {
            val optimizedComparisons = mutableListOf<AttachmentComparison>()
            for (uri in uris) {
                try {
                    try {
                        context.contentResolver.takePersistableUriPermission(
                            uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                        )
                    } catch (e: Exception) {
                        // Not all URIs support persistable permissions
                        Log.w("ChatAttachmentsHandler", "takePersistableUriPermission failed: ${e.message}", e)
                    }

                    val prepared = buildAttachmentFromUri(
                        contentResolver = context.contentResolver,
                        uri = uri,
                        compressImages = compressImages,
                        maxLongSidePx = imageMaxLongSide,
                        webpQuality = imageWebpQuality
                    ) ?: continue

                    attachments.add(prepared.attachment)
                    onAddDraftAttachment(uri.toString())
                    prepared.comparison?.let { optimizedComparisons.add(it) }
                } catch (e: Exception) {
                    // Skip files that fail to read
                    Log.w("ChatAttachmentsHandler", "buildAttachmentFromUri failed: ${e.message}", e)
                }
            }
            if (optimizedComparisons.isNotEmpty()) {
                val totalOriginal = optimizedComparisons.sumOf { it.originalBytes }
                val totalOptimized = optimizedComparisons.sumOf { it.optimizedBytes }
                val totalTokensBefore = optimizedComparisons.sumOf { it.originalEstimatedTokens }
                val totalTokensAfter = optimizedComparisons.sumOf { it.optimizedEstimatedTokens }
                onShowSnackbar(
                    context.getString(
                        R.string.chat_images_optimized_summary,
                        optimizedComparisons.size,
                        formatFileSize(totalOriginal),
                        formatFileSize(totalOptimized),
                        totalTokensBefore,
                        totalTokensAfter
                    )
                )
            }
        }
    }

    // -- SAF session export launcher -----------------------------------------------
    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri: Uri? ->
        if (uri != null) {
            onExportSession(context, uri) { success ->
                coroutineScope.launch {
                    if (success) {
                        onShowSnackbar(context.getString(R.string.chat_session_exported))
                    } else {
                        onShowSnackbar(context.getString(R.string.chat_session_export_failed))
                    }
                }
            }
        }
    }

    // -- Image save via SAF -------------------------------------------------------
    var pendingImageSave by remember { mutableStateOf<ImageSaveRequest?>(null) }
    val saveImageLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("image/*")
    ) { uri: Uri? ->
        val request = pendingImageSave
        pendingImageSave = null
        if (uri == null || request == null) return@rememberLauncherForActivityResult

        coroutineScope.launch {
            runCatching {
                context.contentResolver.openOutputStream(uri)?.use { it.write(request.bytes) }
                    ?: error("Unable to open output stream")
            }.onSuccess {
                onShowSnackbar(context.getString(R.string.chat_image_saved))
            }.onFailure {
                onShowSnackbar(context.getString(R.string.chat_image_save_failed))
            }
        }
    }

    val requestSaveImage: (ByteArray, String, String?) -> Unit = { bytes, mime, filenameHint ->
        val baseName = filenameHint
            ?.substringAfterLast('/')
            ?.substringBeforeLast('.')
            ?.takeIf { it.isNotBlank() }
            ?: "image_${System.currentTimeMillis()}"
        val fileName = "$baseName.${extensionForMime(mime)}"
        pendingImageSave = ImageSaveRequest(bytes = bytes, mime = mime, filename = fileName)
        saveImageLauncher.launch(fileName)
    }

    // -- Consume images shared from other apps via ACTION_SEND ----------------------
    LaunchedEffect(initialSharedImages) {
        if (initialSharedImages.isEmpty()) return@LaunchedEffect
        val optimizedComparisons = mutableListOf<AttachmentComparison>()
        for (uri in initialSharedImages) {
            try {
                try {
                    context.contentResolver.takePersistableUriPermission(
                        uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                } catch (e: Exception) {
                    // Not all URIs support persistable permissions
                    Log.w("ChatAttachmentsHandler", "takePersistableUriPermission failed: ${e.message}", e)
                }

                val prepared = buildAttachmentFromUri(
                    contentResolver = context.contentResolver,
                    uri = uri,
                    compressImages = compressImages,
                    maxLongSidePx = imageMaxLongSide,
                    webpQuality = imageWebpQuality
                ) ?: continue

                attachments.add(prepared.attachment)
                prepared.comparison?.let { optimizedComparisons.add(it) }
                onAddDraftAttachment(uri.toString())
            } catch (e: Exception) {
                Log.w("ChatScreen", "Failed to read shared image: ${e.message}")
            }
        }
        if (optimizedComparisons.isNotEmpty()) {
            val totalOriginal = optimizedComparisons.sumOf { it.originalBytes }
            val totalOptimized = optimizedComparisons.sumOf { it.optimizedBytes }
            val totalTokensBefore = optimizedComparisons.sumOf { it.originalEstimatedTokens }
            val totalTokensAfter = optimizedComparisons.sumOf { it.optimizedEstimatedTokens }
            onShowSnackbar(
                context.getString(
                    R.string.chat_images_optimized_summary,
                    optimizedComparisons.size,
                    formatFileSize(totalOriginal),
                    formatFileSize(totalOptimized),
                    totalTokensBefore,
                    totalTokensAfter
                )
            )
        }
        onSharedImagesConsumed()
    }

    return ChatAttachmentsHandler(
        attachments = attachments,
        pickImages = { imagePickerLauncher.launch("image/*") },
        requestSaveImage = requestSaveImage,
        launchExport = exportLauncher::launch,
    )
}

/** Internal payload for deferred image save. */
private data class ImageSaveRequest(
    val bytes: ByteArray,
    val mime: String,
    val filename: String,
)
