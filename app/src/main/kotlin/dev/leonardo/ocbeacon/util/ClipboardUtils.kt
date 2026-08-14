package dev.leonardo.ocbeacon.util

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.ClipEntry

/** 将 [text] 写入系统剪贴板（Compose LocalClipboard 版，D2-L16 统一入口；setClipEntry 为 suspend）。 */
suspend fun Clipboard.copyToClipboard(label: String, text: String) {
    setClipEntry(ClipEntry(ClipData.newPlainText(label, text)))
}

/** 将 [text] 写入系统剪贴板（Android ClipboardManager 版，D2-L16 统一入口）。 */
fun ClipboardManager.copyToClipboard(label: String, text: String) {
    setPrimaryClip(ClipData.newPlainText(label, text))
}
