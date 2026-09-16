package lab.arl.admin.session

import lab.arl.admin.domain.RemoteSession
import lab.arl.admin.domain.SessionMode

enum class SessionModeAction {
    ENTER_MANAGED,
    EXIT_MANAGED
}

data class SessionModeSwitch(
    val statusLabel: String,
    val buttonLabel: String,
    val action: SessionModeAction
)

object SessionModeSwitchUi {
    /** One mode-switch control whenever the remote session is live (video may still be starting). */
    fun forSession(session: RemoteSession?): SessionModeSwitch? {
        if (session == null || !session.isLive) return null
        return when (session.mode) {
            SessionMode.MONITOR -> SessionModeSwitch(
                statusLabel = "MONITOR",
                buttonLabel = "SWITCH TO MANAGED",
                action = SessionModeAction.ENTER_MANAGED
            )
            SessionMode.MANAGED -> SessionModeSwitch(
                statusLabel = "MANAGED",
                buttonLabel = "SWITCH TO MONITOR",
                action = SessionModeAction.EXIT_MANAGED
            )
        }
    }
}
