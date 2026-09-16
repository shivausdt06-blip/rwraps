package lab.arl.admin.session

import lab.arl.admin.domain.RemoteSession

data class ScreenSharingSwitch(
    val buttonLabel: String,
    val enabled: Boolean
)

object ScreenSharingSwitchUi {
    /** Screen on/off control whenever the remote session is live. */
    fun forSession(session: RemoteSession?, screenSharingEnabled: Boolean): ScreenSharingSwitch? {
        if (session == null || !session.isLive) return null
        return if (screenSharingEnabled) {
            ScreenSharingSwitch(buttonLabel = "SCREEN OFF", enabled = true)
        } else {
            ScreenSharingSwitch(buttonLabel = "SCREEN ON", enabled = false)
        }
    }
}
