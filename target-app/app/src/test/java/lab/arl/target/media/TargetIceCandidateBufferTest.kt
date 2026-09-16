package lab.arl.target.media

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test

class TargetIceCandidateBufferTest {

    @Test
    fun test1_candidateBeforePeerConnectionIsQueued() {
        val buffer = TargetIceCandidateBuffer()
        var applied = false

        val result = buffer.addCandidate(
            sdpMid = "0",
            sdpMLineIndex = 0,
            sdp = "candidate:1 1 UDP 2122260223 10.59.57.35 54321 typ host",
            hasPeerConnection = false,
            onApply = { applied = true }
        )

        assertFalse(result, "Candidate before PeerConnection must be buffered")
        assertFalse(applied, "Candidate must not be applied")
        assertEquals(1, buffer.queuedCount)
    }

    @Test
    fun test2_candidateAfterPeerConnectionButBeforeRemoteSdpIsQueued() {
        val buffer = TargetIceCandidateBuffer()
        buffer.onPeerConnectionCreated()
        var applied = false

        val result = buffer.addCandidate(
            sdpMid = "0",
            sdpMLineIndex = 0,
            sdp = "candidate:2 1 UDP 2122260223 10.59.57.35 54322 typ host",
            hasPeerConnection = true,
            onApply = { applied = true }
        )

        assertFalse(result, "Candidate after PeerConnection but before remote SDP must be buffered")
        assertFalse(applied, "Candidate must not be applied before remote SDP")
        assertEquals(1, buffer.queuedCount)
    }

    @Test
    fun test3_candidateAfterRemoteSdpIsAppliedImmediately() {
        val buffer = TargetIceCandidateBuffer()
        buffer.onPeerConnectionCreated()
        buffer.onRemoteDescriptionSet {}
        assertTrue(buffer.remoteDescriptionSet)

        var appliedCandidate: TargetIceCandidateBuffer.CandidateInfo? = null
        val result = buffer.addCandidate(
            sdpMid = "0",
            sdpMLineIndex = 0,
            sdp = "candidate:3 1 UDP 1686052863 10.59.57.35 3478 typ relay raddr 0.0.0.0 rport 0",
            hasPeerConnection = true,
            onApply = { appliedCandidate = it }
        )

        assertTrue(result, "Candidate after remote SDP must be applied immediately")
        assertEquals(0, buffer.queuedCount)
        assertEquals("0", appliedCandidate?.sdpMid)
        assertTrue(appliedCandidate?.sdp?.contains("typ relay") == true)
    }

    @Test
    fun test4_queuedCandidatesFlushedAfterRemoteSdpInFifoOrder() {
        val buffer = TargetIceCandidateBuffer()
        // Queue candidates before remote SDP
        buffer.addCandidate("0", 0, "cand-1-host", hasPeerConnection = false) {}
        buffer.addCandidate("0", 1, "cand-2-srflx", hasPeerConnection = true) {}
        buffer.addCandidate("0", 2, "cand-3-relay", hasPeerConnection = true) {}
        assertEquals(3, buffer.queuedCount)

        val flushed = mutableListOf<TargetIceCandidateBuffer.CandidateInfo>()
        buffer.onRemoteDescriptionSet { flushed.addAll(it) }

        assertTrue(buffer.remoteDescriptionSet)
        assertEquals(0, buffer.queuedCount, "Queue must be empty after flush")
        assertEquals(3, flushed.size, "All 3 candidates must be flushed")
        assertEquals("cand-1-host", flushed[0].sdp)
        assertEquals("cand-2-srflx", flushed[1].sdp)
        assertEquals("cand-3-relay", flushed[2].sdp)
    }

    @Test
    fun test5_closeResetsStateAndClearsQueue() {
        val buffer = TargetIceCandidateBuffer()
        buffer.onRemoteDescriptionSet {}
        assertTrue(buffer.remoteDescriptionSet)

        buffer.addCandidate("0", 0, "cand-1", hasPeerConnection = false) {}
        buffer.close()

        assertFalse(buffer.remoteDescriptionSet, "remoteDescriptionSet must be false after close")
        assertEquals(0, buffer.queuedCount, "Queue must be empty after close")
    }
}
