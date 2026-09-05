package dev.leonardo.ocbeacon.data.api.dsh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * #325：DSH 首次配对 URI 解析（深链/粘贴回收通道的纯函数缝）。
 *
 * 覆盖三形态输入（对齐 [extractDshToken] 的三形态约定 + 深链形态）：
 * - ocbeacon://pair?url=<服务器地址>&token=<launch token>（dsh-pair.sh --lan 产出）；
 * - dsh web 原始 URL（http://host:port/?token=…，用户从 dsh-url 复制）；
 * - web.log 启动行原样粘贴（dsh web: 本机 … LAN …）。
 *
 * token 校验：base64url 字符集 + 下限 20（与手动粘贴通道同规——短串必拒）。
 * URL 校验：必须有 host；缺 scheme 补 http://；query/fragment 剥离（cookie
 * authority 绑定要求 baseUrl 与后续连接完全一致）。
 */
class DshPairingParserTest {

    private val token = "tK3n_abc123XYZ-456def789ghi012" // 29 字符 base64url，>20 下限

    // ---- 深链形态 ----

    @Test
    fun `P1_深链标准形态解析出baseUrl与token`() {
        val payload = DshPairingParser.parsePairUri(
            "ocbeacon://pair?url=http%3A%2F%2F192.168.110.248%3A3080&token=$token",
        )
        assertEquals("http://192.168.110.248:3080", payload?.baseUrl)
        assertEquals(token, payload?.token)
    }

    @Test
    fun `P2_深链url未编码也容忍`() {
        val payload = DshPairingParser.parsePairUri(
            "ocbeacon://pair?url=http://192.168.110.248:3080&token=$token",
        )
        assertEquals("http://192.168.110.248:3080", payload?.baseUrl)
        assertEquals(token, payload?.token)
    }

    @Test
    fun `P3_深链url缺scheme补http`() {
        val payload = DshPairingParser.parsePairUri(
            "ocbeacon://pair?url=192.168.1.5:3080&token=$token",
        )
        assertEquals("http://192.168.1.5:3080", payload?.baseUrl)
    }

    // ---- 粘贴形态（复用用户现有习惯） ----

    @Test
    fun `P4_dsh_web原始URL剥离query得baseUrl`() {
        val payload = DshPairingParser.parsePairUri("http://127.0.0.1:3080/?token=$token")
        assertEquals("http://127.0.0.1:3080", payload?.baseUrl)
        assertEquals(token, payload?.token)
    }

    @Test
    fun `P5_启动行原样粘贴取首个URL为本机authority`() {
        val payload = DshPairingParser.parsePairUri(
            "dsh web: 本机 http://127.0.0.1:3080/?token=$token LAN http://192.168.110.248:3080/?token=$token",
        )
        assertEquals("http://127.0.0.1:3080", payload?.baseUrl)
        assertEquals(token, payload?.token)
    }

    // ---- token 校验 ----

    @Test
    fun `P6_短token拒绝`() {
        assertNull(DshPairingParser.parsePairUri("ocbeacon://pair?url=http://10.0.0.5:3080&token=short"))
    }

    @Test
    fun `P7_非base64url字符token拒绝`() {
        assertNull(
            DshPairingParser.parsePairUri(
                "ocbeacon://pair?url=http://10.0.0.5:3080&token=${"a".repeat(30)}!@#",
            ),
        )
    }

    // ---- URL 校验与非法输入 ----

    @Test
    fun `P8_缺url参数拒绝`() {
        assertNull(DshPairingParser.parsePairUri("ocbeacon://pair?token=$token"))
    }

    @Test
    fun `P9_无host的url拒绝`() {
        assertNull(DshPairingParser.parsePairUri("ocbeacon://pair?url=http://&token=$token"))
    }

    @Test
    fun `P10_非pair主机的ocbeacon深链拒绝`() {
        assertNull(DshPairingParser.parsePairUri("ocbeacon://other?url=http://10.0.0.5:3080&token=$token"))
    }

    @Test
    fun `P11_无token的普通URL拒绝`() {
        assertNull(DshPairingParser.parsePairUri("http://10.0.0.5:3080/"))
    }

    @Test
    fun `P12_空与垃圾输入拒绝`() {
        assertNull(DshPairingParser.parsePairUri(""))
        assertNull(DshPairingParser.parsePairUri("   "))
        assertNull(DshPairingParser.parsePairUri("hello world"))
    }

    @Test
    fun `P13_畸形百分号序列不崩溃且拒绝`() {
        // 畸形 %NR（NavUtils.safeDecodeParam 同款风险面）——不得抛异常
        assertNull(DshPairingParser.parsePairUri("ocbeacon://pair?url=http%3A%2F%2Fh%NR:3080&token=$token"))
    }

    @Test
    fun `P14_尾斜杠baseUrl归一化`() {
        val payload = DshPairingParser.parsePairUri("http://10.0.0.5:3080/?token=$token")
        assertEquals("http://10.0.0.5:3080", payload?.baseUrl)
    }
}
