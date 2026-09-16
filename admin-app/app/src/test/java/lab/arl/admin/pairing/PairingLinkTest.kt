package lab.arl.admin.pairing

import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Test

class PairingLinkTest {
    @Test
    fun leavesBackendPayloadWhenNoOverride() {
        val qr = "arl://pair?code=8DA2TPGY&session=abc&api=http%3A%2F%2F10.59.57.35%3A8080"
        assertEquals(qr, PairingLink.forTarget(qr, null))
        assertEquals(qr, PairingLink.forTarget(qr, "  "))
    }

    @Test
    fun injectsDebugPairingApi() {
        val qr = "arl://pair?code=8DA2TPGY&session=abc"
        val out = PairingLink.forTarget(qr, "http://10.59.57.35:8080")
        assertTrue(out.contains("code=8DA2TPGY"))
        assertTrue(out.contains("session=abc"))
        assertTrue(out.contains("api=http%3A%2F%2F10.59.57.35%3A8080"))
    }

    @Test
    fun replacesExistingApiParam() {
        val qr = "arl://pair?code=8DA2TPGY&session=abc&api=http%3A%2F%2F10.0.2.2%3A8080"
        val out = PairingLink.forTarget(qr, "http://10.59.57.35:8080")
        assertTrue(out.contains("api=http%3A%2F%2F10.59.57.35%3A8080"))
        assertTrue(!out.contains("10.0.2.2"))
    }
}
