package lab.arl.target.network

import kotlin.test.assertEquals
import org.junit.Test

class LabWebSocketUrlTest {
    @Test
    fun mapsHttpsToWss() {
        assertEquals("wss://lab.example.com", LabWebSocket.toWsUrl("https://lab.example.com/"))
    }

    @Test
    fun mapsHttpToWs() {
        assertEquals("ws://10.0.2.2:8080", LabWebSocket.toWsUrl("http://10.0.2.2:8080"))
    }
}
