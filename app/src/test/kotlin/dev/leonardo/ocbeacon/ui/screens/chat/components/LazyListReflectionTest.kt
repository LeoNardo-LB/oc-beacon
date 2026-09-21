package dev.leonardo.ocbeacon.ui.screens.chat.components

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * #423 Phase 0(D5):SSE 反射炸弹护栏单测。
 *
 * K9(调研 06):requestPositionAndForgetLastKnownKey(Int, Int) 的双 Int 签名是
 * value-class **未装箱巧合**——Compose BOM 升级随时可能改为装箱/其他形态。
 * 冒烟测试把「当前 classpath(foundation classes.jar)上的真实签名」钉死:
 * 升级后若成员消失/签名漂移,本测试先红,发版前核销(javap 纪律的自动化兜底)。
 * 降级测试:类解析/成员探测失败 → 探针为 null → 运行时必然走官方
 * requestScrollToItem 降级路径(ScrollCompensation 内 if(probes!=null) 分支)。
 */
class LazyListReflectionTest {

    private val stateClassName = "androidx.compose.foundation.lazy.LazyListState"

    /** 冒烟:当前 BOM 上三个反射成员全部可解析(炸弹未爆)。 */
    @Test
    fun `reflection probes resolve at current compose BOM`() {
        val probes = resolveLazyListProbes()
        assertNotNull(
            "反射成员缺失=Compose 升级破坏了未装箱巧合(K9);" +
                "修复=按 ScrollCompensation.kt 反射头注释核销新签名",
            probes,
        )
    }

    /** 降级①:类解析失败(NoClassDefFound/改名) → null → 官方 API 降级。 */
    @Test
    fun `probes null when class cannot be resolved`() {
        assertNull(resolveLazyListProbes(resolveClass = { null }))
    }

    /** 降级②:类在但成员缺失/签名漂移(装箱巧合失效) → null → 官方 API 降级。 */
    @Test
    fun `probes null when members missing or signature drifted`() {
        assertNull(
            resolveLazyListProbes(resolveClass = { String::class.java }),
        )
    }
}
