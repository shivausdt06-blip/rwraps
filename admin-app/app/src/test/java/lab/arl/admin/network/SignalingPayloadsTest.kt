package lab.arl.admin.network

import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.Test

class SignalingPayloadsTest {
    @Test
    fun parsesOfferFieldsWithoutSdpDump() {
        val sid = "11111111-1111-4111-8111-111111111111"
        val payload = SignalingPayloads.offer(sid, "v=0")
        assertEquals(sid, SignalingPayloads.parseSessionId(payload))
        assertEquals("v=0", SignalingPayloads.parseSdp(payload))
    }

    @Test
    fun missingSdpIsNull() {
        val payload = buildJsonObject {
            put("sessionId", JsonPrimitive("11111111-1111-4111-8111-111111111111"))
        }
        assertEquals("11111111-1111-4111-8111-111111111111", SignalingPayloads.parseSessionId(payload))
        assertNull(SignalingPayloads.parseSdp(payload))
    }
}
