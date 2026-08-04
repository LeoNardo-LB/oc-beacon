package dev.leonardo.ocbeacon.util

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 调试日志器，同时写入 logcat 和公共 Downloads 目录下的文件。
 *
 * 文件位置：
 *   - API 29+：MediaStore.Downloads → /sdcard/Download/annotate_debug.log
 *   - API < 29：getExternalFilesDir → /sdcard/Android/data/<pkg>/files/annotate_debug.log
 *
 * 每次 [log] 调用会追加到内存缓冲区，然后将完整缓冲区 flush 到文件
 * （MediaStore 不支持追加模式）。缓冲区很小（仅用于调试），开销可忽略。
 *
 * 在会话开始时调用 [reset] 以清除上一次运行的日志。
 */
object DebugLogger {
    private const val TAG = "DebugLogger"
    private const val FILE_NAME = "annotate_debug.log"

    private val buffer = StringBuilder()
    private val timeFmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private var ctx: Context? = null
    private var cachedUri: Uri? = null

    fun init(context: Context) {
        ctx = context.applicationContext
    }

    /** 清空缓冲区并删除文件——在开始新的采集会话时调用。 */
    fun reset() {
        buffer.setLength(0)
        cachedUri = null
        deleteFile()
    }

    fun log(tag: String, message: String) {
        val line = "${timeFmt.format(Date())} [$tag] $message\n"

        // 1. logcat
        Log.d(tag, message)

        // 2. 内存缓冲区
        buffer.append(line)

        // 3. 将完整缓冲区 flush 到 Downloads 文件
        flush()
    }

    private fun flush() {
        val context = ctx ?: return
        val content = buffer.toString()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                flushMediaStore(context, content)
            } else {
                flushLegacy(context, content)
            }
        } catch (e: Exception) {
            Log.e(TAG, "flush failed", e)
        }
    }

    private fun flushMediaStore(context: Context, content: String) {
        val resolver = context.contentResolver
        val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI

        // 查找或创建文件一次，缓存其 Uri
        if (cachedUri == null) {
            val proj = arrayOf(MediaStore.MediaColumns._ID)
            val sel = "${MediaStore.MediaColumns.DISPLAY_NAME} = ?"
            resolver.query(collection, proj, sel, arrayOf(FILE_NAME), null)?.use { c ->
                if (c.moveToFirst()) {
                    val id = c.getLong(0)
                    cachedUri = Uri.withAppendedPath(collection, id.toString())
                }
            }
            if (cachedUri == null) {
                val v = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, FILE_NAME)
                    put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                }
                cachedUri = resolver.insert(collection, v)
            }
        }

        cachedUri?.let { uri ->
            resolver.openOutputStream(uri, "w")?.use { os ->
                os.write(content.toByteArray())
            }
        }
    }

    private fun flushLegacy(context: Context, content: String) {
        val dir = context.getExternalFilesDir(null) ?: return
        FileOutputStream(File(dir, FILE_NAME)).use { it.write(content.toByteArray()) }
    }

    private fun deleteFile() {
        val context = ctx ?: return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val sel = "${MediaStore.MediaColumns.DISPLAY_NAME} = ?"
                context.contentResolver.delete(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    sel,
                    arrayOf(FILE_NAME)
                )
            } else {
                File(context.getExternalFilesDir(null), FILE_NAME).delete()
            }
        } catch (e: Exception) {
            Log.e(TAG, "delete failed", e)
        }
    }
}
