package lab.arl.admin.session

import kotlin.test.assertEquals
import kotlin.test.assertNull
import lab.arl.admin.domain.RemoteSession
import lab.arl.admin.domain.SessionMode
import org.junit.Test

class SessionModeSwitchUiTest {
    private fun liveSession(mode: SessionMode = SessionMode.MONITOR) = RemoteSession(
        id = "s1",
        deviceId = "d1",
        adminId = "a1",
        status = "ACTIVE",
        mode = mode
    )

    @Test
    fun newSessionDefaultsToMonitorSwitch() {
        val switch = SessionModeSwitchUi.forSession(liveSession(SessionMode.MONITOR))
        assertEquals("MONITOR", switch?.statusLabel)
        assertEquals("SWITCH TO MANAGED", switch?.buttonLabel)
        assertEquals(SessionModeAction.ENTER_MANAGED, switch?.action)
    }

    @Test
    fun managedSessionShowsSwitchToMonitor() {
        val switch = SessionModeSwitchUi.forSession(liveSession(SessionMode.MANAGED))
        assertEquals("MANAGED", switch?.statusLabel)
        assertEquals("SWITCH TO MONITOR", switch?.buttonLabel)
        assertEquals(SessionModeAction.EXIT_MANAGED, switch?.action)
    }

    @Test
    fun noSwitchWhenSessionNotLive() {
        val created = liveSession().copy(status = "CREATED")
        assertNull(SessionModeSwitchUi.forSession(created))
        assertNull(SessionModeSwitchUi.forSession(null))
    }

    @Test
    fun switchVisibleBeforeFirstVideoFrame() {
        // Button must not depend on streamReceived — only session liveness.
        val authenticated = liveSession().copy(status = "AUTHENTICATED")
        assertEquals("SWITCH TO MANAGED", SessionModeSwitchUi.forSession(authenticated)?.buttonLabel)
    }
}
