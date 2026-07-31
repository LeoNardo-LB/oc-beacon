package dev.leonardo.octether.ui.screens.chat.input

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import dev.leonardo.octether.R
import dev.leonardo.octether.ui.screens.chat.dialog.ImagePreviewDialog
import dev.leonardo.octether.ui.screens.chat.util.ImageAttachment
import dev.leonardo.octether.ui.screens.chat.util.decodeDataUrlBytes
import dev.leonardo.octether.ui.screens.chat.util.imageThumbnailModel
import dev.leonardo.octether.ui.theme.AlphaTokens
import dev.leonardo.octether.ui.theme.ShapeTokens
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme

/**
 * Image attachment thumbnail row with preview dialog support.
 */
@Composable
internal fun ImageAttachmentRow(
    attachments: List<ImageAttachment>,
    onRemoveAttachment: (Int) -> Unit,
    onSaveAttachment: (bytes: ByteArray, mime: String, filename: String?) -> Unit
) {
    if (attachments.isEmpty()) return

    var previewAttachmentIndex by remember { mutableStateOf(-1) }

    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(attachments.size, key = { index -> attachments[index].uri }) { index ->
            val attachment = attachments[index]
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(ShapeTokens.mediumSmall)
            ) {
                AsyncImage(
                    model = imageThumbnailModel(attachment),
                    contentDescription = attachment.filename,
                    modifier = Modifier
                        .fillMaxSize()
                        .clickable { previewAttachmentIndex = index },
                    contentScale = ContentScale.Crop
                )
                Surface(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(2.dp)
                        .size(18.dp)
                        .clickable { onRemoveAttachment(index) },
                    shape = ShapeTokens.mediumSmall,
                    color = MaterialTheme.colorScheme.error.copy(alpha = AlphaTokens.AMOLED)
                ) {
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = stringResource(R.string.chat_remove),
                            modifier = Modifier.size(12.dp),
                            tint = MaterialTheme.colorScheme.onError
                        )
                    }
                }
            }
        }
    }

    if (previewAttachmentIndex >= 0 && previewAttachmentIndex < attachments.size) {
        val attachment = attachments[previewAttachmentIndex]
        val imageBytes = remember(attachment.dataUrl) { decodeDataUrlBytes(attachment.dataUrl) }
        val bitmap = remember(imageBytes) {
            imageBytes?.let { bytes -> BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }
        }

        if (bitmap != null) {
            ImagePreviewDialog(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = attachment.filename,
                onDismiss = { previewAttachmentIndex = -1 },
                onSave = {
                    if (imageBytes != null) {
                        onSaveAttachment(imageBytes, attachment.mime, attachment.filename)
                    }
                },
            )
        }
    }
}
