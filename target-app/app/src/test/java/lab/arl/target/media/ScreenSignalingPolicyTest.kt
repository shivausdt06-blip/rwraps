package lab.arl.target.media

import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test

class ScreenSignalingPolicyTest {
    @Test
    fun targetAnswersOnlyWhenCaptureMatchesSession() {
        assertTrue(ScreenSignalingPolicy.shouldAnswerRemoteOffer(true, true))
        assertFalse(ScreenSignalingPolicy.shouldAnswerRemoteOffer(false, true))
        assertFalse(ScreenSignalingPolicy.shouldAnswerRemoteOffer(true, false))
        assertFalse(ScreenSignalingPolicy.shouldCreateLocalOffer())
    }

    @Test
    fun ignoresAdminAnswerUnlessTargetWasOfferer() {
        assertFalse(ScreenSignalingPolicy.shouldApplyRemoteAnswer(hasLocalOffer = false))
        assertTrue(ScreenSignalingPolicy.shouldApplyRemoteAnswer(hasLocalOffer = true))
    }

    @Test
    fun iceKindOmitsAddresses() {
        val kind = WebrtcDiag.iceKind("candidate:1 1 UDP 2122260223 192.168.1.8 9 typ srflx raddr 0.0.0.0 rport 0")
        assertTrue(kind.contains("typ=srflx"))
        assertFalse(kind.contains("192.168"))
    }
}
