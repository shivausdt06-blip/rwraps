package lab.arl.target.pairing

import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test

class PairingCodeParserTest {
    @Test
    fun parsesBareCode() {
        val parsed = PairingCodeParser.parse("k7m2q9xa")
        assertEquals("K7M2Q9XA", parsed?.code)
        assertNull(parsed?.sessionId)
        assertNull(parsed?.apiBaseUrl)
    }

    @Test
    fun parsesQrPayload() {
        val parsed = PairingCodeParser.parse("arl://pair?code=K7M2Q9XA&session=11111111-2222-3333-4444-555555555555")
        assertNotNull(parsed)
        assertEquals("K7M2Q9XA", parsed.code)
        assertEquals("11111111-2222-3333-4444-555555555555", parsed.sessionId)
        assertNull(parsed.apiBaseUrl)
    }

    @Test
    fun parsesQrPayloadWithApi() {
        val parsed = PairingCodeParser.parse(
            "arl://pair?code=8DA2TPGY&session=11111111-2222-3333-4444-555555555555&api=http%3A%2F%2F10.59.57.35%3A8080"
        )
        assertNotNull(parsed)
        assertEquals("8DA2TPGY", parsed.code)
        assertEquals("http://10.59.57.35:8080", parsed.apiBaseUrl)
    }

    @Test
    fun rejectsAmbiguousOrShortCodes() {
        assertNull(PairingCodeParser.parse(""))
        assertNull(PairingCodeParser.parse("abc"))
        assertNull(PairingCodeParser.parse("IIIIIIII"))
    }

    @Test
    fun alphabetMatchesBackend() {
        assertTrue(PairingCodeParser.isValidCode("ABCDEFGH"))
        assertTrue(PairingCodeParser.isValidCode("23456789"))
    }
}
