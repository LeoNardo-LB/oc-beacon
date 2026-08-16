package dev.leonardo.ocbeacon.ui.screens.chat.util

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 已准备好发送的图片附件。 */
internal data class ImageAttachment(
    val uri: Uri,
    val mime: String,
    val filename: String,
    val dataUrl: String // "data:<mime>;base64,..."
)

internal data class PreparedAttachment(
    val attachment: ImageAttachment,
    val comparison: AttachmentComparison? = null
)

internal data class AttachmentComparison(
    val originalBytes: Int,
    val optimizedBytes: Int,
    val originalEstimatedTokens: Int,
    val optimizedEstimatedTokens: Int
)

// ============ 附件校验 ============

/** 本地附件在加入编辑器之前的校验结果。 */
internal enum class LocalAttachmentValidation { ACCEPTED, UNSUPPORTED, TOO_LARGE }

internal const val MAX_DOCUMENT_ATTACHMENT_BYTES = 10L * 1024 * 1024
internal const val MAX_TEXT_ATTACHMENT_BYTES = 2L * 1024 * 1024

internal val TEXT_FILE_EXTENSIONS = setOf(
    "txt", "md", "markdown", "json", "jsonl", "xml", "yaml", "yml", "toml", "csv", "tsv",
    "kt", "kts", "java", "js", "jsx", "ts", "tsx", "py", "rb", "go", "rs", "c", "h", "cpp", "hpp",
    "cs", "swift", "sh", "bash", "zsh", "fish", "sql", "html", "css", "scss", "gradle", "properties",
    "ini", "conf", "config", "log", "env", "gitignore",
)

private val TEXT_MIME_TYPES = setOf(
    "application/json", "application/xml", "application/javascript",
    "application/x-yaml", "application/yaml",
)

/**
 * 按 MIME 类型、文件扩展名和字节大小校验本地附件。
 *
 * 文本文件上限为 [MAX_TEXT_ATTACHMENT_BYTES]；图片和 PDF 上限为
 * [MAX_DOCUMENT_ATTACHMENT_BYTES]。其他类型均为 [UNSUPPORTED]。
 */
internal fun validateLocalAttachment(
    mime: String,
    filename: String,
    sizeBytes: Long,
): LocalAttachmentValidation {
    val extension = filename.substringAfterLast('.', "").lowercase()
    val isText = mime.startsWith("text/") || extension in TEXT_FILE_EXTENSIONS || mime in TEXT_MIME_TYPES
    val supported = mime.startsWith("image/") || mime == "application/pdf" || isText
    if (!supported) return LocalAttachmentValidation.UNSUPPORTED
    val limit = if (isText) MAX_TEXT_ATTACHMENT_BYTES else MAX_DOCUMENT_ATTACHMENT_BYTES
    return if (sizeBytes > limit) LocalAttachmentValidation.TOO_LARGE else LocalAttachmentValidation.ACCEPTED
}

/** 解析 content URI 的显示名称和声明大小。 */
internal fun attachmentMetadata(
    contentResolver: ContentResolver,
    uri: Uri,
): Pair<String, Long?> {
    var name: String? = null
    var size: Long? = null
    contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
        ?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (nameIndex >= 0) name = cursor.getString(nameIndex)
                if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) size = cursor.getLong(sizeIndex)
            }
        }
    return (name ?: uri.lastPathSegment?.substringAfterLast('/') ?: "attachment") to size
}

internal fun decodeDataUrlBytes(dataUrl: String): ByteArray? {
    val encoded = dataUrl.substringAfter(',', missingDelimiterValue = "")
    if (encoded.isBlank()) return null
    return try {
        Base64.decode(encoded, Base64.DEFAULT)
    } catch (_: Exception) {
        null
    }
}

internal fun decodePartFileBytes(file: dev.leonardo.ocbeacon.domain.model.Part.File): ByteArray? {
    val url = file.url ?: return null
    val encoded = if (url.contains(',')) url.substringAfter(',') else url
    if (encoded.isBlank()) return null
    return try {
        Base64.decode(encoded, Base64.DEFAULT)
    } catch (_: Exception) {
        null
    }
}

internal fun extensionForMime(mime: String): String {
    return when (mime.lowercase()) {
        "image/jpeg", "image/jpg" -> "jpg"
        "image/png" -> "png"
        "image/webp" -> "webp"
        "image/gif" -> "gif"
        else -> "img"
    }
}

internal fun imageThumbnailModel(attachment: ImageAttachment): Any {
    if (attachment.uri.scheme.equals("data", ignoreCase = true)) {
        val encoded = attachment.dataUrl.substringAfter(',', missingDelimiterValue = "")
        if (encoded.isNotBlank()) {
            return try {
                Base64.decode(encoded, Base64.DEFAULT)
            } catch (_: Exception) {
                attachment.dataUrl
            }
        }
    }
    return attachment.uri
}

internal fun estimateVisionTokens(width: Int, height: Int): Int {
    if (width <= 0 || height <= 0) return 0
    return ((width.toLong() * height.toLong()) / 750.0).toInt()
}

internal suspend fun buildAttachmentFromUri(
    contentResolver: ContentResolver,
    uri: Uri,
    compressImages: Boolean,
    maxLongSidePx: Int = 1440,
    webpQuality: Int = 60
): PreparedAttachment? = withContext(Dispatchers.IO) {
    val (originalFilename, declaredSize) = attachmentMetadata(contentResolver, uri)
    var mimeType = contentResolver.getType(uri) ?: "application/octet-stream"
    // 嗅探通用 octet-stream 中的 text/plain，使文本文件可被接受。
    val extension = originalFilename.substringAfterLast('.', "").lowercase()
    if (mimeType == "application/octet-stream" && extension in TEXT_FILE_EXTENSIONS) {
        mimeType = "text/plain"
    }
    if (validateLocalAttachment(mimeType, originalFilename, declaredSize ?: 0) != LocalAttachmentValidation.ACCEPTED) {
        return@withContext null
    }

    val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return@withContext null

    // 2026-08-16（大小防御补全）：SAF 元数据 size=null 时按 0 过检（:155），
    // 实际字节数在读取后才可知——读后二次校验防绕过（超限拒绝，不给
    // base64 放大 3.2× 的机会进内存二次峰值）。
    run {
        val extension2 = originalFilename.substringAfterLast('.', "").lowercase()
        val isText2 = mimeType.startsWith("text/") || extension2 in TEXT_FILE_EXTENSIONS || mimeType in TEXT_MIME_TYPES
        if (validateLocalAttachment(mimeType, originalFilename, bytes.size.toLong()) != LocalAttachmentValidation.ACCEPTED) {
            return@withContext null
        }
        if (isText2 && bytes.size > MAX_TEXT_ATTACHMENT_BYTES) return@withContext null
    }

    val shouldOptimize = compressImages && (mimeType == "image/png" || mimeType == "image/jpeg")
    if (!shouldOptimize) {
        val base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
        return@withContext PreparedAttachment(
            attachment = ImageAttachment(
                uri = uri,
                mime = mimeType,
                filename = originalFilename,
                dataUrl = "data:$mimeType;base64,$base64"
            )
        )
    }

    // 先读尺寸再降采样解码，避免全分辨率位图占用过多内存
    val boundsOptions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, boundsOptions)
    val origWidth = boundsOptions.outWidth
    val origHeight = boundsOptions.outHeight
    val sampleSize = if (maxLongSidePx > 0) {
        calcInSampleSize(origWidth, origHeight, maxLongSidePx, maxLongSidePx)
    } else {
        1
    }
    val decodeOptions = BitmapFactory.Options().apply {
        inSampleSize = sampleSize
        // JPEG 无透明通道，用 RGB_565 节省 50% 内存
        if (mimeType == "image/jpeg") inPreferredConfig = Bitmap.Config.RGB_565
    }
    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decodeOptions)
    if (bitmap == null) {
        val base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
        return@withContext PreparedAttachment(
            attachment = ImageAttachment(
                uri = uri,
                mime = mimeType,
                filename = originalFilename,
                dataUrl = "data:$mimeType;base64,$base64"
            )
        )
    }

    val srcWidth = bitmap.width
    val srcHeight = bitmap.height
    val longSide = maxOf(srcWidth, srcHeight)
    val resizeEnabled = maxLongSidePx > 0
    val scale = if (resizeEnabled && longSide > maxLongSidePx) {
        maxLongSidePx.toFloat() / longSide.toFloat()
    } else {
        1f
    }
    val outWidth = (srcWidth * scale).toInt().coerceAtLeast(1)
    val outHeight = (srcHeight * scale).toInt().coerceAtLeast(1)
    val resizedBitmap = if (scale < 1f) Bitmap.createScaledBitmap(bitmap, outWidth, outHeight, true) else bitmap

    val output = java.io.ByteArrayOutputStream()
    @Suppress("DEPRECATION")
    val format = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        Bitmap.CompressFormat.WEBP_LOSSY
    } else {
        Bitmap.CompressFormat.WEBP
    }
    val compressed = resizedBitmap.compress(format, webpQuality.coerceIn(1, 100), output)
    if (resizedBitmap !== bitmap) {
        resizedBitmap.recycle()
    }
    bitmap.recycle()

    if (!compressed) {
        val base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
        return@withContext PreparedAttachment(
            attachment = ImageAttachment(
                uri = uri,
                mime = mimeType,
                filename = originalFilename,
                dataUrl = "data:$mimeType;base64,$base64"
            )
        )
    }

    val webpBytes = output.toByteArray()
    if (scale >= 0.999f && webpBytes.size >= bytes.size) {
        val base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
        return@withContext PreparedAttachment(
            attachment = ImageAttachment(
                uri = uri,
                mime = mimeType,
                filename = originalFilename,
                dataUrl = "data:$mimeType;base64,$base64"
            )
        )
    }
    val base64 = Base64.encodeToString(webpBytes, Base64.NO_WRAP)
    val optimizedFilename = originalFilename.substringBeforeLast('.', originalFilename) + ".webp"
    return@withContext PreparedAttachment(
        attachment = ImageAttachment(
            uri = uri,
            mime = "image/webp",
            filename = optimizedFilename,
            dataUrl = "data:image/webp;base64,$base64"
        ),
        comparison = AttachmentComparison(
            originalBytes = bytes.size,
            optimizedBytes = webpBytes.size,
            originalEstimatedTokens = estimateVisionTokens(origWidth, origHeight),
            optimizedEstimatedTokens = estimateVisionTokens(outWidth, outHeight)
        )
    )
}

/**
 * 计算降采样率（2 的幂），使解码后图片略大于目标尺寸。
 * 标准 Android 算法：取最大 2 的幂，使 halfDim/sampleSize >= reqDim。
 */
private fun calcInSampleSize(outWidth: Int, outHeight: Int, reqWidth: Int, reqHeight: Int): Int {
    var inSampleSize = 1
    if (outHeight > reqHeight || outWidth > reqWidth) {
        val halfHeight = outHeight / 2
        val halfWidth = outWidth / 2
        while (halfHeight / inSampleSize >= reqHeight && halfWidth / inSampleSize >= reqWidth) {
            inSampleSize *= 2
        }
    }
    return inSampleSize
}
