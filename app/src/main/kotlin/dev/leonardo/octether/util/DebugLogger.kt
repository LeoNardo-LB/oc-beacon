package dev.leonardo.octether.util

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
 * Debug logger that writes to BOTH logcat AND a file in the public Downloads directory.
 *
 * File location:
 *   - API 29+: MediaStore.Downloads → /sdcard/Download/annotate_debug.log
 *   - API < 29: getExternalFilesDir → /sdcard/Android/data/<pkg>/files/annotate_debug.log
 *
 * Each [log] call appends to an in-memory buffer then flushes the FULL buffer to file
 * (MediaStore doesn't support append mode). Buffer is small (debug only), so cost is negligible.
 *
 * Call [reset] at session start to clear the previous run's logs.
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

    /** Clear the buffer and delete the file — call when a fresh capture session begins. */
    fun reset() {
        buffer.setLength(0)
        cachedUri = null
        deleteFile()
    }

    fun log(tag: String, message: String) {
        val line = "${timeFmt.format(Date())} [$tag] $message\n"

        // 1. logcat
        Log.d(tag, message)

        // 2. in-memory buffer
        buffer.append(line)

        // 3. flush full buffer to Downloads file
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

        // Find or create the file once, cache its Uri
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
