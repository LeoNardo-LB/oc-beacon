package dev.leonardo.ocbeacon.ui.screens.chat.dialog

import android.util.Base64
import android.util.Log
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BrokenImage
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.leonardo.ocbeacon.R
import dev.leonardo.ocbeacon.domain.model.Part
import dev.leonardo.ocbeacon.ui.components.amoledDialogParams
import dev.leonardo.ocbeacon.ui.screens.chat.util.LocalImageSaveRequest
import dev.leonardo.ocbeacon.ui.screens.chat.util.decodePartFileBytes
import dev.leonardo.ocbeacon.ui.screens.chat.util.isAmoledTheme
import dev.leonardo.ocbeacon.ui.theme.ShapeTokens
import dev.leonardo.ocbeacon.ui.theme.AlphaTokens

/**
 * Compact horizontal row of image thumbnails with tap-to-preview.
 */
@Composable
internal fun ImageThumbnailRow(
    imageFiles: List<Part.File>,
) {
    var previewIndex by remember { mutableStateOf(-1) }
    val requestSaveImage = LocalImageSaveRequest.current

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        for ((index, file) in imageFiles.withIndex()) {
            val bitmap = remember(file.url) {
                try {
                    val url = file.url ?: return@remember null
                    val base64Data = if (url.contains(",")) url.substringAfter(",") else url
                    val bytes = Base64.decode(base64Data, Base64.DEFAULT)
                    android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                } catch (e: Exception) {
                    Log.e("FileCard", "Failed to decode image: ${e.message}")
                    null
                }
            }

            if (bitmap != null) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = file.filename ?: stringResource(R.string.chat_image),
                    modifier = Modifier
                        .size(80.dp)
                        .clip(ShapeTokens.small)
                        .clickable { previewIndex = index },
                    contentScale = ContentScale.Crop
                )
            } else {
                // Fallback placeholder for failed decode
                Box(
                    modifier = Modifier
                        .size(80.dp)
                        .clip(ShapeTokens.small)
                        .background(MaterialTheme.colorScheme.surface),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.BrokenImage,
                        contentDescription = stringResource(R.string.a11y_icon_image),
                        modifier = Modifier.size(24.dp),
                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = AlphaTokens.FAINT)
                    )
                }
            }
        }
    }

    // Fullscreen image preview dialog
    if (previewIndex >= 0 && previewIndex < imageFiles.size) {
        val file = imageFiles[previewIndex]
        val imageBytes = remember(file.url) { decodePartFileBytes(file) }
        val bitmap = remember(imageBytes) {
            imageBytes?.let { bytes -> android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }
        }

        if (bitmap != null) {
            ImagePreviewDialog(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = file.filename ?: stringResource(R.string.chat_image),
                onDismiss = { previewIndex = -1 },
                onSave = {
                    if (imageBytes != null) {
                        requestSaveImage(imageBytes, file.mime, file.filename)
                    }
                },
            )
        }
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
internal fun ImagePreviewDialog(
    bitmap: androidx.compose.ui.graphics.ImageBitmap,
    contentDescription: String?,
    onDismiss: () -> Unit,
    onSave: () -> Unit,
) {
    val params = amoledDialogParams(shape = ShapeTokens.largeMedium)
    val isAmoled = isAmoledTheme()
    BasicAlertDialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp),
            shape = params.shape,
            color = params.containerColor,
            border = params.border,
            tonalElevation = params.tonalElevation,
        ) {
            Box(modifier = Modifier.padding(14.dp)) {
                Image(
                    bitmap = bitmap,
                    contentDescription = contentDescription,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 520.dp)
                        .clip(ShapeTokens.medium),
                    contentScale = ContentScale.Fit,
                )

                Row(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val actionContainerColor = MaterialTheme.colorScheme.surface.copy(alpha = AlphaTokens.AMOLED)
                    val actionBorderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = if (isAmoled) AlphaTokens.AMOLED else AlphaTokens.HIGH)
                    val actionTintColor = MaterialTheme.colorScheme.onSurface

                    Surface(
                        shape = ShapeTokens.mediumSmall,
                        color = actionContainerColor,
                        border = BorderStroke(1.dp, actionBorderColor),
                    ) {
                        IconButton(onClick = onSave, modifier = Modifier.size(36.dp)) {
                            Icon(
                                Icons.Default.Download,
                                contentDescription = stringResource(R.string.chat_save_image),
                                tint = actionTintColor,
                            )
                        }
                    }

                    Surface(
                        shape = ShapeTokens.mediumSmall,
                        color = actionContainerColor,
                        border = BorderStroke(1.dp, actionBorderColor),
                    ) {
                        IconButton(onClick = onDismiss, modifier = Modifier.size(36.dp)) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = stringResource(R.string.a11y_icon_dismiss),
                                tint = actionTintColor,
                            )
                        }
                    }
                }
            }
        }
    }
}
