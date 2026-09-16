package lab.arl.admin.session

import kotlin.test.assertEquals
import kotlin.test.assertNull
import lab.arl.admin.domain.RemoteSession
import lab.arl.admin.domain.SessionMode
import org.junit.Test

class ScreenSharingSwitchUiTest {
    private fun liveSession() = RemoteSession(
        id = "s1",
        deviceId = "d1",
        adminId = "a1",
        status = "ACTIVE",
        mode = SessionMode.MONITOR
    )

    @Test
    fun screenOnShowsOffAction() {
        val switch = ScreenSharingSwitchUi.forSession(liveSession(), screenSharingEnabled = true)
        assertEquals("SCREEN OFF", switch?.buttonLabel)
        assertEquals(true, switch?.enabled)
    }

    @Test
    fun screenOffShowsOnAction() {
        val switch = ScreenSharingSwitchUi.forSession(liveSession(), screenSharingEnabled = false)
        assertEquals("SCREEN ON", switch?.buttonLabel)
        assertEquals(false, switch?.enabled)
    }

    @Test
    fun noSwitchWhenSessionNotLive() {
        assertNull(ScreenSharingSwitchUi.forSession(liveSession().copy(status = "CREATED"), true))
        assertNull(ScreenSharingSwitchUi.forSession(null, true))
    }
}
