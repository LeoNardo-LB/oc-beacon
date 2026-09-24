package dev.leonardo.ocbeacon.ui.screens.chat.components

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #432 settle 判稳纯函数:settleFrameAdvances 的 H>0 门槛。
 *
 * 真机取证(prewarm 集体触发,同秒 9 卡):H=0/18/75/264 混杂——H=0 的卡
 * 内容尚未组合即「判稳」,早熟 H 进 dispatch=展开足迹偏小+steady 逐帧补
 * (=「展开后高度自己变」根源)。H>0 才允许计稳。
 */
class SettleFrameAdvancesTest {

    @Test
    fun zeroHeightNeverStable_evenIfMeasuresQuiet() {
        // 组合间隙:测量静止但内容零高度——不得计稳
        assertFalse(settleFrameAdvances(measures = 3, lastMeasures = 3, measuredH = 0))
    }

    @Test
    fun positiveHeightQuietMeasuresStable() {
        assertTrue(settleFrameAdvances(measures = 3, lastMeasures = 3, measuredH = 80))
    }

    @Test
    fun growingMeasuresUnstable_evenWithHeight() {
        // 测量仍在增长(内容还在落地)——不稳定
        assertFalse(settleFrameAdvances(measures = 4, lastMeasures = 3, measuredH = 264))
    }

    @Test
    fun noMeasuresYetUnstable() {
        assertFalse(settleFrameAdvances(measures = 0, lastMeasures = 0, measuredH = 0))
    }
}