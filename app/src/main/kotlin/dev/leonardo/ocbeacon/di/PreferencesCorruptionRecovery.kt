package dev.leonardo.ocbeacon.di

import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import dev.leonardo.ocbeacon.logging.AppLogger
import java.io.File

/**
 * opencode_prefs DataStore 损坏处置策略（#335——2026-09-06 真机事故：多次
 * force-stop 窗口内写事务被打断，preferences_pb 半写损坏；默认
 * ReplaceFileCorruptionHandler 静默 emptyPreferences → servers key 全丢、
 * preferences_pb 仅剩 65B 且无可取证痕迹）。
 *
 * 契约：损坏**仍重置**（防损坏→崩溃循环，与默认行为一致）但三补强——
 * ① [AppLogger.e] 错误日志（Diagnostics 屏可观测）；
 * ② 损坏文件副本留档 `<name>.corrupt-<timestamp>`（同目录，取证/事后恢复输入）；
 * ③ 留档失败不阻断重置（runCatching 吞并记 WARN）。
 *
 * 纯函数提取（DI 委托无单测基建）——[PreferencesCorruptionRecoveryTest] 钉死。
 */
internal object PreferencesCorruptionRecovery {

    private const val TAG = "PrefsCorruption"

    /** 损坏处置：错误日志 + 留档副本 + 返回空 preferences（重置由 DataStore 完成）。 */
    fun recover(corruptFile: File?, error: Throwable): Preferences {
        AppLogger.e(TAG, "opencode_prefs DataStore 损坏——重置并留档取证（servers 等配置将回退默认）", error)
        if (corruptFile == null) return emptyPreferences()
        runCatching {
            val archived = File(
                corruptFile.parentFile,
                corruptFile.name + ".corrupt-" + System.currentTimeMillis(),
            )
            corruptFile.copyTo(archived, overwrite = false)
            archived
        }.onSuccess { archived ->
            AppLogger.w(TAG, "损坏文件已留档: " + archived.path)
        }.onFailure {
            AppLogger.w(TAG, "损坏文件留档失败（不影响重置流程）: " + it.message)
        }
        return emptyPreferences()
    }
}
