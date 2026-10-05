package dev.leonardo.ocbeacon.data.api.dsh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * #317：token 三形态提取（URL / 宿主启动行 / 裸 token）——TokenNeeded UX 输入解析。
 */
class DshConnectionRegistryTest {

    @Test
    fun extract_fullUrlWithTokenQuery() {
        assertEquals(
            "abc123_-XYZ",
            extractDshToken("http://192.168.1.2:3080/?token=abc123_-XYZ"),
        )
    }

    @Test
    fun extract_startupLineFromWebLog() {
        assertEquals(
            "t0k3n",
            extractDshToken("dsh web: http://127.0.0.1:3080/?token=t0k3n&x=1"),
        )
    }

    @Test
    fun extract_bareToken() {
        assertEquals("a".repeat(43), extractDshToken("  " + "a".repeat(43) + " "))
    }

    @Test
    fun extract_rejectsGarbage() {
        assertNull(extractDshToken(""))
        assertNull(extractDshToken("   "))
        assertNull(extractDshToken("hello world token")) // 空白分段非单段
        assertNull(extractDshToken("short")) // 低于 20 字符下限
    }

    // ---- #512：dsh-password-login 铸票判读与回落链编排（纯函数三态/顺序） ----

    @Test
    fun mint_pluginPresentAndFresh_returnsCookie() {
        assertEquals(
            "dsh-auth-abc=v1.body.sig",
            parseSessionMint(200, "dsh-auth-abc=v1.body.sig; Max-Age=604800; Path=/; HttpOnly", """{"ok":true,"v":1}"""),
        )
    }

    @Test
    fun mint_staleDrift_rejected() {
        // 金丝雀漂移自报：消费者应直接走 legacy token 流
        assertNull(parseSessionMint(200, "dsh-auth-abc=v1.body.sig", """{"ok":true,"v":1,"stale":true}"""))
    }

    @Test
    fun mint_unexpectedStatusOrMissingCookie_rejected() {
        assertNull(parseSessionMint(404, null, null)) // 无插件
        assertNull(parseSessionMint(401, null, """{"ok":false,"error":"password required"}""")) // 密码错/未带
        assertNull(parseSessionMint(403, null, null)) // 非回环免密
        assertNull(parseSessionMint(503, null, """{"ok":false,"stale":true}""")) // 铸票不可用
        assertNull(parseSessionMint(200, null, """{"ok":true}""")) // 200 无 Set-Cookie
        assertNull(parseSessionMint(200, "   ", """{"ok":true}"""))
        assertNull(parseSessionMint(200, "garbage-without-eq", null))
        // 非法 JSON 体不炸：保守按未 stale 处理（cookie 仍需 200+Set-Cookie）
        assertEquals(
            "dsh-auth-x=v1.b.s",
            parseSessionMint(200, "dsh-auth-x=v1.b.s; Path=/", "not-json"),
        )
    }

    @Test
    fun recovery_fullChain_orderAndDedupe() {
        val steps = planRecovery(passwordHint = "pw1", persistedToken = "tok1")
        assertEquals(
            listOf(
                DshRecoveryStep.FreeMint,
                DshRecoveryStep.PasswordMint("pw1"),
                DshRecoveryStep.LegacyToken("tok1"),
                DshRecoveryStep.LegacyToken("pw1"),
            ),
            steps,
        )
    }

    @Test
    fun recovery_sameCredentialDeduped_acrossLegacyCandidates() {
        // password 字段既当配对密码又当 launch token 且持久化 token 同值 → legacy 只打一枪
        val steps = planRecovery(passwordHint = "shared", persistedToken = "shared")
        assertEquals(
            listOf(
                DshRecoveryStep.FreeMint,
                DshRecoveryStep.PasswordMint("shared"),
                DshRecoveryStep.LegacyToken("shared"),
            ),
            steps,
        )
    }

    @Test
    fun recovery_noCredentials_freeMintOnly() {
        assertEquals(listOf<DshRecoveryStep>(DshRecoveryStep.FreeMint), planRecovery(null, null))
        assertEquals(listOf<DshRecoveryStep>(DshRecoveryStep.FreeMint), planRecovery("  ", ""))
    }

    @Test
    fun recovery_tokenOnly_skipsPasswordMint() {
        assertEquals(
            listOf(DshRecoveryStep.FreeMint, DshRecoveryStep.LegacyToken("tok1")),
            planRecovery(passwordHint = null, persistedToken = "tok1"),
        )
    }
}
