package dev.leonardo.ocbeacon.debug

import dev.leonardo.ocbeacon.logging.AppLogger

/**
 * 竞态取证埋点（2026-08-20 第五轮叠放 bug）。
 *
 * 开关：am start --ez debug_race true（与 debug_perf 同模式；release 也生效，
 * 默认关闭零开销——isEnabled 为 volatile 布尔，关闭时各 probe 点为一次分支判断）。
 *
 * 抓取：adb -s e69a99d8 logcat -s RaceProbe
 * 场景：用户复现叠放时保持开关开启，出现异常后 adb logcat -d -s RaceProbe 导出，
 * 时序足以重放（哪次提交/什么索引/视口在哪/ENTRIES 何时重建）。
 */
object RaceProbe {
    @Volatile
    var isEnabled: Boolean = false

    fun probe(message: String) {
        if (!isEnabled) return
        AppLogger.w("RaceProbe", message)
    }
}