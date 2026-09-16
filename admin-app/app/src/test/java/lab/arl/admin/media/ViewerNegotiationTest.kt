package lab.arl.admin.media

import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test

class ViewerNegotiationTest {
    @Test
    fun adminSendsASingleOffer() {
        assertTrue(ViewerNegotiation.shouldSendLocalOffer(localOfferSent = false, remoteDescriptionSet = false))
        assertFalse(ViewerNegotiation.shouldSendLocalOffer(localOfferSent = true, remoteDescriptionSet = false))
        assertFalse(ViewerNegotiation.shouldSendLocalOffer(localOfferSent = false, remoteDescriptionSet = true))
    }

    @Test
    fun ignoresRemoteOfferAfterLocalOfferToAvoidGlare() {
        assertTrue(ViewerNegotiation.shouldApplyRemoteOffer(localOfferSent = false))
        assertFalse(ViewerNegotiation.shouldApplyRemoteOffer(localOfferSent = true))
    }

    @Test
    fun remoteTrackAttachesOnlyWhenRendererAndTrackExist() {
        assertFalse(ViewerNegotiation.shouldAttachRemoteTrack(hasRenderer = false, hasTrack = true))
        assertFalse(ViewerNegotiation.shouldAttachRemoteTrack(hasRenderer = true, hasTrack = false))
        assertTrue(ViewerNegotiation.shouldAttachRemoteTrack(hasRenderer = true, hasTrack = true))
    }

    @Test
    fun iceKindOmitsAddressesAndCredentials() {
        val sdp = "candidate:1 1 UDP 2122260223 10.0.2.15 54321 typ host generation 0"
        val kind = WebrtcDiag.iceKind(sdp)
        assertTrue(kind.contains("typ=host"))
        assertTrue(kind.contains("proto=udp"))
        assertFalse(kind.contains("10.0.2.15"))
        assertFalse(kind.contains("54321"))
    }

    @Test
    fun relayKindDetectedWithoutAddresses() {
        val kind = WebrtcDiag.iceKind("candidate:842163049 1 udp 41885439 10.59.57.35 49152 typ relay raddr 0.0.0.0 rport 0")
        assertTrue(kind.contains("typ=relay"))
        assertFalse(kind.contains("49152"))
        assertFalse(kind.contains("10.59"))
    }

    @Test
    fun iceServerSummaryCountsStunAndTurn() {
        val summary = WebrtcDiag.iceServerSummary(
            listOf(
                listOf("stun:stun.l.google.com:19302"),
                listOf("turn:10.59.57.35:3478?transport=udp", "turn:10.0.2.2:3478?transport=tcp")
            )
        )
        assertTrue(summary.contains("stun=1"))
        assertTrue(summary.contains("turn=2"))
        assertFalse(summary.contains("arl-local"))
    }
}
