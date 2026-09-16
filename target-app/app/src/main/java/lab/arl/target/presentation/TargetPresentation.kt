package lab.arl.target.presentation

import lab.arl.target.domain.EnrollmentPhase
import lab.arl.target.domain.OperationStatus
import lab.arl.target.domain.RemoteSession
import lab.arl.target.domain.SessionMode
import lab.arl.target.network.WsConnectionState
import lab.arl.target.session.TargetUiState

enum class SetupItemState { READY, PENDING, WARNING }

data class SetupItem(val label: String, val state: SetupItemState, val detail: String? = null)

/** UI-only screen-sharing semantics; not sent on the API wire. */
enum class ScreenSharingUiState { READY, CONSENT_REQUIRED, DENIED, IDLE }

enum class CurrentSessionPhase {
    NONE,
    INCOMING,
    AWAITING_CAPTURE,
    MONITOR_LIVE,
    MANAGED_LIVE
}

data class CurrentSessionUi(
    val visible: Boolean,
    val phase: CurrentSessionPhase,
    val modeLabel: String,
    val deviceName: String,
    val screenStatus: String,
    val webrtcLabel: String,
    val isLive: Boolean,
    val showAccept: Boolean,
    val showGrantCapture: Boolean,
    val showEndSession: Boolean
)

data class TargetPresentation(
    val connectionOnline: Boolean,
    val connectionLabel: String,
    val setupItems: List<SetupItem>,
    val setupComplete: Boolean,
    val currentSession: CurrentSessionUi,
    val globalMessage: String?,
    val globalLoading: Boolean,
    val showConnectionRetry: Boolean
)

object TargetPresentationMapper {
    fun map(state: TargetUiState, captureActive: Boolean): TargetPresentation {
        val enrolled = state.phase != EnrollmentPhase.NOT_ENROLLED &&
            state.phase != EnrollmentPhase.PAIRING &&
            state.phase != EnrollmentPhase.REVOKED
        val wsConnected = state.wsState == WsConnectionState.CONNECTED
        val session = state.session?.takeIf { !it.isTerminal }
        val screenUi = resolveScreenSharingUi(captureActive, state.captureDenied, session)

        val setupItems = listOf(
            SetupItem(
                label = "Device enrolled",
                state = if (enrolled) SetupItemState.READY else SetupItemState.PENDING
            ),
            SetupItem(
                label = "Server connected",
                state = if (wsConnected) SetupItemState.READY else SetupItemState.PENDING
            ),
            setupScreenSharingItem(screenUi, captureActive),
            setupRemoteInteractionItem(state)
        )
        val setupComplete = setupItems
            .filter { it.label != "Screen sharing" }
            .all { it.state == SetupItemState.READY } &&
            setupItems.first { it.label == "Screen sharing" }.state != SetupItemState.WARNING

        val currentSession = buildCurrentSession(state, session, captureActive, screenUi)
        val suppressGlobalMessage = currentSession.visible &&
            (state.captureDenied || state.operation.status == OperationStatus.FAILURE)

        return TargetPresentation(
            connectionOnline = wsConnected,
            connectionLabel = when {
                !enrolled -> "Offline"
                wsConnected -> "Online"
                state.wsState == WsConnectionState.CONNECTING ||
                    state.wsState == WsConnectionState.AUTHENTICATING -> "Connecting…"
                else -> "Offline"
            },
            setupItems = setupItems,
            setupComplete = setupComplete,
            currentSession = currentSession,
            globalMessage = if (suppressGlobalMessage) null else state.operation.message,
            globalLoading = state.operation.status == OperationStatus.LOADING,
            showConnectionRetry = state.operation.retryable &&
                state.wsState != WsConnectionState.CONNECTED &&
                session == null
        )
    }

    fun resolveScreenSharingUi(
        captureActive: Boolean,
        captureDenied: Boolean,
        session: RemoteSession?
    ): ScreenSharingUiState = when {
        captureActive -> ScreenSharingUiState.READY
        captureDenied -> ScreenSharingUiState.DENIED
        session != null && session.status != "CREATED" -> ScreenSharingUiState.CONSENT_REQUIRED
        else -> ScreenSharingUiState.IDLE
    }

    private fun setupScreenSharingItem(
        screenUi: ScreenSharingUiState,
        captureActive: Boolean
    ): SetupItem = when (screenUi) {
        ScreenSharingUiState.READY -> SetupItem("Screen sharing", SetupItemState.READY, if (captureActive) "Active" else null)
        ScreenSharingUiState.CONSENT_REQUIRED -> SetupItem(
            "Screen sharing",
            SetupItemState.WARNING,
            "Consent required for this session"
        )
        ScreenSharingUiState.DENIED -> SetupItem(
            "Screen sharing",
            SetupItemState.WARNING,
            "Permission denied — grant to continue"
        )
        ScreenSharingUiState.IDLE -> SetupItem(
            "Screen sharing",
            SetupItemState.PENDING,
            "Available when requested"
        )
    }

    private fun setupRemoteInteractionItem(state: TargetUiState): SetupItem {
        val remote = state.capabilityStates["REMOTE_INTERACTION"]
        val ready = remote == "AVAILABLE" && state.accessibilityConnected
        val detail = when {
            ready -> null
            remote == "RESTRICTED" -> "Enable ARL Target in Accessibility settings"
            else -> "Enable Accessibility for remote interaction"
        }
        return SetupItem(
            label = "Remote interaction",
            state = if (ready) SetupItemState.READY else SetupItemState.PENDING,
            detail = detail
        )
    }

    private fun buildCurrentSession(
        state: TargetUiState,
        session: RemoteSession?,
        captureActive: Boolean,
        screenUi: ScreenSharingUiState
    ): CurrentSessionUi {
        if (session == null) {
            return CurrentSessionUi(
                visible = false,
                phase = CurrentSessionPhase.NONE,
                modeLabel = "",
                deviceName = state.device?.name.orEmpty(),
                screenStatus = "",
                webrtcLabel = webrtcLabel(state, captureActive, null),
                isLive = false,
                showAccept = false,
                showGrantCapture = false,
                showEndSession = false
            )
        }

        val phase = when {
            session.status == "CREATED" -> CurrentSessionPhase.INCOMING
            !captureActive -> CurrentSessionPhase.AWAITING_CAPTURE
            session.isManaged -> CurrentSessionPhase.MANAGED_LIVE
            else -> CurrentSessionPhase.MONITOR_LIVE
        }

        val modeLabel = when (phase) {
            CurrentSessionPhase.INCOMING -> "Session requested"
            CurrentSessionPhase.AWAITING_CAPTURE -> when (session.mode) {
                SessionMode.MANAGED -> "MANAGED"
                SessionMode.MONITOR -> "MONITOR"
            }
            CurrentSessionPhase.MANAGED_LIVE -> "MANAGED"
            CurrentSessionPhase.MONITOR_LIVE -> "MONITOR"
            else -> session.mode.name
        }

        val screenStatus = when (screenUi) {
            ScreenSharingUiState.READY -> "Live"
            ScreenSharingUiState.DENIED -> "Needs permission"
            ScreenSharingUiState.CONSENT_REQUIRED -> "Needs permission"
            ScreenSharingUiState.IDLE -> "Waiting for screen capture"
        }

        val isLive = captureActive && isWebRtcConnected(state.webrtcState)

        return CurrentSessionUi(
            visible = true,
            phase = phase,
            modeLabel = modeLabel,
            deviceName = state.device?.name.orEmpty(),
            screenStatus = screenStatus,
            webrtcLabel = webrtcLabel(state, captureActive, session),
            isLive = isLive,
            showAccept = session.status == "CREATED",
            showGrantCapture = !captureActive && session.status != "CREATED",
            showEndSession = true
        )
    }

    fun webrtcLabel(state: TargetUiState, captureActive: Boolean, session: RemoteSession?): String {
        if (session == null) return "Idle"
        if (!captureActive) {
            return when {
                state.captureDenied -> "Waiting for screen permission"
                state.needsMediaProjection -> "Waiting for screen permission"
                session.status == "CREATED" -> "Idle"
                else -> "Waiting for screen permission"
            }
        }
        return when (state.webrtcState) {
            "CONNECTED", "COMPLETED" -> "Connected"
            "CONNECTING", "NEW", "CHECKING" -> "Connecting"
            "FAILED" -> "Connection failed"
            "DISCONNECTED" -> "Reconnecting"
            "CLOSED" -> "Connecting"
            else -> state.webrtcState
        }
    }

    private fun isWebRtcConnected(webrtcState: String): Boolean =
        webrtcState == "CONNECTED" || webrtcState == "COMPLETED"
}
