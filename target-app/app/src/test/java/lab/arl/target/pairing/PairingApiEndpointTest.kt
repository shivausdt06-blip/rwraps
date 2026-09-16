package lab.arl.target.pairing

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test

class PairingApiEndpointTest {
    private val compiled = "https://api.example.invalid"

    @Test
    fun releaseIgnoresUriApi() {
        val resolved = PairingApiEndpoint.resolve(
            compiled,
            "http://10.59.57.35:8080",
            debug = false
        )
        assertEquals(compiled, resolved)
    }

    @Test
    fun debugAcceptsPrivateLanHttp() {
        val resolved = PairingApiEndpoint.resolve(
            "http://10.0.2.2:8080",
            "http://10.59.57.35:8080",
            debug = true
        )
        assertEquals("http://10.59.57.35:8080", resolved)
        assertTrue(PairingApiEndpoint.isAllowedDebugOverride("http://192.168.1.10:8080"))
        assertTrue(PairingApiEndpoint.isAllowedDebugOverride("http://10.0.2.2:8080"))
    }

    @Test
    fun debugRejectsPublicOrUnsafeHosts() {
        assertFalse(PairingApiEndpoint.isAllowedDebugOverride("https://evil.example.com"))
        assertFalse(PairingApiEndpoint.isAllowedDebugOverride("http://8.8.8.8:8080"))
        assertFalse(PairingApiEndpoint.isAllowedDebugOverride("http://user:pass@10.59.57.35:8080"))
        assertFalse(PairingApiEndpoint.isAllowedDebugOverride("file:///tmp"))
        val resolved = PairingApiEndpoint.resolve("http://10.0.2.2:8080", "https://evil.example.com", debug = true)
        assertEquals("http://10.0.2.2:8080", resolved)
    }
}
