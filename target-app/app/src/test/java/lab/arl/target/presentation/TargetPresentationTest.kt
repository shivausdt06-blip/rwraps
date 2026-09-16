package lab.arl.target.presentation

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import lab.arl.target.domain.EnrollmentPhase
import lab.arl.target.domain.Operation
import lab.arl.target.domain.OperationStatus
import lab.arl.target.domain.RemoteSession
import lab.arl.target.domain.SessionMode
import lab.arl.target.network.WsConnectionState
import lab.arl.target.session.TargetUiState
import org.junit.Test

class ScreenSharingUiStateTest {
    @Test
    fun idleWithoutSessionIsIdleNotReady() {
        assertEquals(
            ScreenSharingUiState.IDLE,
            TargetPresentationMapper.resolveScreenSharingUi(false, false, null)
        )
    }

    @Test
    fun activeSessionWithoutCaptureNeedsConsent() {
        val session = RemoteSession(
            id = "s",
            deviceId = "d",
            adminId = "a",
            status = "ACTIVE",
            mode = SessionMode.MONITOR,
            startedAt = null,
            endedAt = null,
            timeoutAt = null,
            reconnectCount = 0,
            quality = null,
            createdAt = "",
            updatedAt = ""
        )
        assertEquals(
            ScreenSharingUiState.CONSENT_REQUIRED,
            TargetPresentationMapper.resolveScreenSharingUi(false, false, session)
        )
    }
}

class TargetPresentationTest {
    private val liveSession = RemoteSession(
        id = "sess-1",
        deviceId = "dev-1",
        adminId = "admin-1",
        status = "ACTIVE",
        mode = SessionMode.MONITOR,
        startedAt = null,
        endedAt = null,
        timeoutAt = null,
        reconnectCount = 0,
        quality = null,
        createdAt = "",
        updatedAt = ""
    )

    @Test
    fun noSessionShowsIdleWebRtc() {
        val ui = TargetPresentationMapper.map(
            TargetUiState(phase = EnrollmentPhase.CONNECTED, wsState = WsConnectionState.CONNECTED),
            captureActive = false
        )
        assertFalse(ui.currentSession.visible)
        assertEquals("Idle", TargetPresentationMapper.webrtcLabel(ui.currentSession.let {
            TargetUiState(phase = EnrollmentPhase.CONNECTED)
        }, false, null))
    }

    @Test
    fun captureDeniedMarksScreenSharingWarningNotReady() {
        val ui = TargetPresentationMapper.map(
            baseEnrolled().copy(
                session = liveSession,
                captureDenied = true,
                capabilityStates = mapOf(
                    "SCREEN_CAPTURE" to "AVAILABLE",
                    "REMOTE_INTERACTION" to "AVAILABLE"
                ),
                accessibilityConnected = true
            ),
            captureActive = false
        )
        val screen = ui.setupItems[2]
        assertEquals(SetupItemState.WARNING, screen.state)
        assertTrue(ui.currentSession.visible)
        assertEquals("Needs permission", ui.currentSession.screenStatus)
        assertEquals("Waiting for screen permission", ui.currentSession.webrtcLabel)
        assertNull(ui.globalMessage)
    }

    @Test
    fun activeCaptureShowsLiveMonitorSession() {
        val ui = TargetPresentationMapper.map(
            baseEnrolled().copy(
                session = liveSession,
                webrtcState = "CONNECTED",
                capabilityStates = mapOf("SCREEN_CAPTURE" to "AVAILABLE", "REMOTE_INTERACTION" to "AVAILABLE"),
                accessibilityConnected = true
            ),
            captureActive = true
        )
        assertEquals(CurrentSessionPhase.MONITOR_LIVE, ui.currentSession.phase)
        assertTrue(ui.currentSession.isLive)
        assertEquals("Connected", ui.currentSession.webrtcLabel)
        assertEquals(SetupItemState.READY, ui.setupItems[2].state)
    }

    @Test
    fun managedModeReflectedInSessionCard() {
        val ui = TargetPresentationMapper.map(
            baseEnrolled().copy(
                session = liveSession.copy(mode = SessionMode.MANAGED),
                webrtcState = "CONNECTED"
            ),
            captureActive = true
        )
        assertEquals(CurrentSessionPhase.MANAGED_LIVE, ui.currentSession.phase)
        assertEquals("MANAGED", ui.currentSession.modeLabel)
    }

    @Test
    fun incomingSessionShowsAcceptOnly() {
        val ui = TargetPresentationMapper.map(
            baseEnrolled().copy(session = liveSession.copy(status = "CREATED")),
            captureActive = false
        )
        assertTrue(ui.currentSession.showAccept)
        assertFalse(ui.currentSession.showGrantCapture)
    }

    private fun baseEnrolled() = TargetUiState(
        phase = EnrollmentPhase.CONNECTED,
        wsState = WsConnectionState.CONNECTED,
        device = lab.arl.target.domain.DeviceRecord(
            id = "dev-1",
            name = "YUHV",
            enrollmentState = "ACTIVE",
            authorizationState = "GRANTED",
            platform = "android",
            androidVersion = "15",
            manufacturer = "Test",
            model = "Phone",
            sdkInt = 35,
            lastSeenAt = null,
            connectionState = "ONLINE",
            capabilities = lab.arl.target.domain.DeviceCapabilities(),
            createdAt = "",
            updatedAt = ""
        ),
        operation = Operation(OperationStatus.FAILURE, message = "Screen capture was denied.")
    )
}
