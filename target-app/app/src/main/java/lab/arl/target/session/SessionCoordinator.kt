package lab.arl.target.session

import android.content.Context
import android.content.Intent
import android.provider.Settings
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import lab.arl.target.auth.TokenStore
import lab.arl.target.device.DeviceProfileFactory
import lab.arl.target.device.DeviceRepository
import lab.arl.target.device.currentCapabilities
import lab.arl.target.domain.DeviceRecord
import lab.arl.target.domain.EnrollmentPhase
import lab.arl.target.domain.Operation
import lab.arl.target.domain.OperationStatus
import lab.arl.target.domain.PairingClaim
import lab.arl.target.domain.RemoteSession
import lab.arl.target.interaction.AccessibilityInspector
import lab.arl.target.interaction.AccessibilityOnboarding
import lab.arl.target.interaction.CapabilityReporter
import lab.arl.target.interaction.InteractionCommandValidator
import lab.arl.target.interaction.InteractionDiag
import lab.arl.target.interaction.InteractionParams
import lab.arl.target.interaction.RemoteInteractionService
import lab.arl.target.media.CaptureSettings
import lab.arl.target.media.ScreenCaptureController
import lab.arl.target.media.ScreenSignalingPolicy
import lab.arl.target.media.WebRtcClient
import lab.arl.target.media.WebrtcDiag
import lab.arl.target.network.ApiException
import lab.arl.target.network.LabWebSocket
import lab.arl.target.network.RemoteSessionDto
import lab.arl.target.network.SignalingPayloads
import lab.arl.target.network.WsConnectionState
import lab.arl.target.network.appJson
import lab.arl.target.network.toDomain
import lab.arl.target.pairing.PairingCodeParser
import lab.arl.target.pairing.PairingRepository
import lab.arl.target.service.TargetForegroundService
import lab.arl.target.telemetry.TelemetryCollector
import org.webrtc.PeerConnection

data class TargetUiState(
    val phase: EnrollmentPhase = EnrollmentPhase.NOT_ENROLLED,
    val operation: Operation<Unit> = Operation(),
    val deviceNameInput: String = "",
    val pairingInput: String = "",
    val claim: PairingClaim? = null,
    val device: DeviceRecord? = null,
    val session: RemoteSession? = null,
    val telemetryText: String = "",
    val captureSettings: CaptureSettings = CaptureSettings(),
    val wsState: WsConnectionState = WsConnectionState.DISCONNECTED,
    val webrtcState: String = "NEW",
    val needsMediaProjection: Boolean = false,
    val captureDenied: Boolean = false,
    val screenCaptureActive: Boolean = false,
    val accessibilityEnabled: Boolean = false,
    val accessibilityConnected: Boolean = false,
    val capabilityStates: Map<String, String> = emptyMap(),
    val lastInteraction: String? = null,
    val lastRemoteEvent: String? = null,
    val lastRemoteCoordinates: String? = null,
    val lastRemoteEventAt: Long? = null,
    val apiBaseUrl: String = "",
    val showAccessibilityOnboarding: Boolean = false
)

class SessionCoordinator(
    private val appContext: Context,
    private var apiBaseUrl: String,
    private val applyPairingApi: (String?) -> String,
    private val persistDebugApi: () -> Unit,
    private val resetDebugApi: () -> Unit,
    private val tokenStore: TokenStore,
    private val pairingRepository: PairingRepository,
    private val deviceRepository: DeviceRepository,
    private val sessionRepository: SessionRepository,
    private val profileFactory: DeviceProfileFactory,
    private val ws: LabWebSocket,
    private val webRtc: WebRtcClient,
    private val capture: ScreenCaptureController,
    private val telemetry: TelemetryCollector
) {
    private val job = SupervisorJob()
    val scope = CoroutineScope(job + Dispatchers.Main.immediate)
    private val _state = MutableStateFlow(TargetUiState(apiBaseUrl = apiBaseUrl))
    val state: StateFlow<TargetUiState> = _state
    private var heartbeatJob: Job? = null
    private var reconnectJob: Job? = null
    private val pendingProjection = AtomicReference<Intent?>(null)
    private var pendingProjectionData: Pair<Int, Intent>? = null
    private var createdOffer = false
    private val pendingRemoteOffer = AtomicReference<String?>(null)
    private val seenCommands = linkedSetOf<String>()
    private val accessibilityListener: (Boolean) -> Unit = { refreshCapabilities() }
    private var accessibilityPromptDismissed = false

    init {
        RemoteInteractionService.addListener(accessibilityListener)
        refreshCapabilities()
        scope.launch {
            ws.messages.collect { envelope -> handleSocket(envelope.type, envelope.payload?.jsonObject) }
        }
        scope.launch {
            ws.state.collect { wsState ->
                _state.update { it.copy(wsState = wsState) }
                if (wsState == WsConnectionState.CONNECTED) {
                    val enrolled = _state.value.device != null || tokenStore.load() != null
                    if (enrolled && _state.value.phase != EnrollmentPhase.ACTIVE_SESSION &&
                        _state.value.phase != EnrollmentPhase.REVOKED
                    ) {
                        _state.update { it.copy(phase = EnrollmentPhase.CONNECTED) }
                    }
                }
                if (wsState == WsConnectionState.FAILED) {
                    scheduleSocketRetry()
                }
            }
        }
        webRtc.onIceCandidate = { candidate ->
            val sessionId = _state.value.session?.id
            if (sessionId != null) {
                WebrtcDiag.log("ice_sent", sessionId, "dir=target->admin")
                ws.sendJson("signaling.ice", SignalingPayloads.ice(sessionId, candidate))
            }
        }
        webRtc.onConnectionChange = { pcState ->
            _state.update { it.copy(webrtcState = pcState.name) }
            if (pcState == PeerConnection.PeerConnectionState.FAILED ||
                pcState == PeerConnection.PeerConnectionState.DISCONNECTED
            ) {
                scope.launch { reconnectMedia() }
            }
        }
        _state.update { it.copy(captureSettings = CaptureSettings.forDisplay(appContext)) }
        restoreEnrollment()
    }

    fun updatePairingInput(value: String) {
        _state.update { it.copy(pairingInput = value) }
    }

    fun updateDeviceName(value: String) {
        _state.update { it.copy(deviceNameInput = value) }
    }

    fun updateCapture(settings: CaptureSettings) {
        _state.update { it.copy(captureSettings = settings) }
    }

    fun claim() {
        scope.launch {
            val parsed = PairingCodeParser.parse(_state.value.pairingInput)
            if (parsed == null) {
                fail("Enter a valid pairing code or QR payload.", retryable = false)
                return@launch
            }
            setLoading("Claiming pairing session…")
            _state.update { it.copy(phase = EnrollmentPhase.PAIRING) }
            try {
                apiBaseUrl = applyPairingApi(parsed.apiBaseUrl)
                _state.update { it.copy(apiBaseUrl = apiBaseUrl) }
                val profile = profileFactory.create(_state.value.deviceNameInput.ifBlank { null })
                val claim = pairingRepository.claim(parsed.code, profile)
                _state.update {
                    it.copy(
                        claim = claim,
                        phase = EnrollmentPhase.AUTHORIZATION_PENDING,
                        operation = Operation(OperationStatus.SUCCESS)
                    )
                }
            } catch (err: Exception) {
                _state.update { it.copy(phase = EnrollmentPhase.NOT_ENROLLED) }
                fail(err)
            }
        }
    }

    fun confirmPairing() {
        val token = _state.value.claim?.claimToken ?: return
        scope.launch {
            setLoading("Confirming enrollment…")
            try {
                val (device, _, _) = pairingRepository.confirm(token)
                persistDebugApi()
                accessibilityPromptDismissed = false
                _state.update {
                    it.copy(
                        device = device,
                        claim = null,
                        phase = EnrollmentPhase.ENROLLED,
                        operation = Operation(OperationStatus.SUCCESS),
                        needsMediaProjection = true
                    )
                }
                connectSockets()
                refreshCapabilities()
            } catch (err: Exception) {
                fail(err)
            }
        }
    }

    fun cancelPairing() {
        _state.update {
            it.copy(claim = null, phase = EnrollmentPhase.NOT_ENROLLED, operation = Operation())
        }
    }

    fun acceptIncomingSession() {
        val session = _state.value.session ?: return
        scope.launch {
            setLoading("Accepting remote-support session…")
            try {
                val authed = sessionRepository.authenticate(session.id)
                WebrtcDiag.log("session_accepted", session.id)
                if (capture.isRunning) {
                    capture.resume()
                    runCatching { webRtc.getOrCreateVideoTrack().setEnabled(true) }
                    val ice = sessionRepository.iceServers()
                    webRtc.ensurePeerConnection(ice)
                    val activated = if (SessionActivatePolicy.shouldCallActivate(authed.status)) {
                        sessionRepository.activate(authed.id).also {
                            WebrtcDiag.log("session_activated", authed.id, "http=200")
                        }
                    } else {
                        authed
                    }
                    applyPendingRemoteOffer(authed.id)
                    _state.update {
                        it.copy(
                            session = activated,
                            phase = EnrollmentPhase.ACTIVE_SESSION,
                            needsMediaProjection = false,
                            captureDenied = false,
                            operation = Operation(OperationStatus.SUCCESS)
                        )
                    }
                    TargetForegroundService.start(appContext, sessionActive = true, projectionReady = true)
                    refreshCapabilities()
                } else if (pendingProjectionData != null) {
                    val (resCode, permData) = pendingProjectionData!!
                    _state.update { it.copy(session = authed, needsMediaProjection = false, captureDenied = false) }
                    startMedia(resCode, permData)
                } else {
                    _state.update { it.copy(session = authed, needsMediaProjection = true, captureDenied = false) }
                    TargetForegroundService.start(appContext, sessionActive = true, projectionReady = false)
                }
            } catch (err: Exception) {
                fail(err)
            }
        }
    }

    fun onMediaProjectionResult(granted: Boolean, resultCode: Int, data: Intent?) {
        _state.update { it.copy(needsMediaProjection = false) }
        WebrtcDiag.log("capture_permission_result", extra = "granted=$granted")
        if (granted && data != null) {
            WebrtcDiag.log("capture_permission_granted")
        }
        if (!granted || data == null) {
            _state.update {
                it.copy(
                    captureDenied = true,
                    operation = Operation(OperationStatus.PERMISSION_DENIED, message = null)
                )
            }
            refreshCapabilities()
            TargetForegroundService.start(appContext, sessionActive = _state.value.session != null, projectionReady = false)
            return
        }
        pendingProjection.set(data)
        pendingProjectionData = Pair(resultCode, data)
        scope.launch {
            val session = _state.value.session
            if (session != null && !session.isTerminal) {
                startMedia(resultCode, data)
            } else {
                try {
                    val source = webRtc.getOrCreateVideoSource()
                    val settings = if (_state.value.captureSettings.width == 1280 && _state.value.captureSettings.height == 720) {
                        CaptureSettings.forDisplay(appContext)
                    } else {
                        _state.value.captureSettings
                    }
                    capture.start(resultCode, data, source, settings)
                    capture.pause()
                    _state.update { it.copy(screenCaptureActive = true, captureDenied = false) }
                    TargetForegroundService.start(appContext, sessionActive = false, projectionReady = true)
                    refreshCapabilities()
                    WebrtcDiag.log("capture_pre_initialized")
                } catch (e: Exception) {
                    WebrtcDiag.log("capture_pre_init_failed", extra = e.message)
                }
            }
        }
    }

    fun retryScreenCapture() {
        if (_state.value.session == null || _state.value.session?.isTerminal == true) return
        _state.update { it.copy(needsMediaProjection = true, captureDenied = false) }
    }

    /** Walk through missing capabilities using legitimate Android consent flows. */
    fun beginDeviceSetup() {
        refreshCapabilities()
        val st = _state.value
        val caps = st.capabilityStates
        val screenOk = caps["SCREEN_CAPTURE"] == "AVAILABLE"
        val remoteOk = caps["REMOTE_INTERACTION"] == "AVAILABLE" && st.accessibilityConnected
        when {
            st.session != null && !st.session!!.isTerminal && !screenOk -> retryScreenCapture()
            !remoteOk -> {
                accessibilityPromptDismissed = false
                _state.update {
                    it.copy(showAccessibilityOnboarding = AccessibilityOnboarding.shouldPrompt(
                        phase = it.phase,
                        remoteInteractionState = caps["REMOTE_INTERACTION"],
                        dismissed = false
                    ))
                }
                if (!_state.value.showAccessibilityOnboarding) {
                    openAccessibilitySettings()
                }
            }
            else -> _state.update {
                it.copy(operation = Operation(OperationStatus.SUCCESS, message = "Device setup is complete."))
            }
        }
    }

    fun endSession() {
        scope.launch {
            val id = _state.value.session?.id
            setLoading("Ending session…")
            try {
                if (id != null) sessionRepository.terminate(id)
            } catch (err: Exception) {
                fail(err)
            } finally {
                stopMedia()
                _state.update {
                    it.copy(
                        session = null,
                        phase = EnrollmentPhase.CONNECTED,
                        captureDenied = false,
                        needsMediaProjection = false,
                        operation = Operation(OperationStatus.SESSION_ENDED, message = "Session ended.")
                    )
                }
                refreshCapabilities()
                TargetForegroundService.start(appContext, sessionActive = false, projectionReady = false)
            }
        }
    }

    fun revokeEnrollment() {
        scope.launch {
            setLoading("Revoking enrollment…")
            try {
                runCatching { _state.value.session?.id?.let { sessionRepository.terminate(it) } }
                deviceRepository.revoke()
            } catch (err: Exception) {
                fail(err)
            } finally {
                stopMedia()
                ws.disconnect()
                reconnectJob?.cancel()
                tokenStore.clear()
                resetDebugApi()
                apiBaseUrl = applyPairingApi(null)
                accessibilityPromptDismissed = false
                _state.update {
                    TargetUiState(
                        phase = EnrollmentPhase.REVOKED,
                        operation = Operation(OperationStatus.SUCCESS, message = "Enrollment revoked."),
                        apiBaseUrl = apiBaseUrl
                    )
                }
                TargetForegroundService.stop(appContext)
            }
        }
    }

    fun retryConnect() {
        connectSockets()
    }

    private fun restoreEnrollment() {
        val stored = tokenStore.load() ?: return
        scope.launch {
            setLoading("Restoring enrollment…")
            try {
                val device = deviceRepository.me()
                if (device.enrollmentState == "REVOKED" || device.authorizationState == "REVOKED") {
                    tokenStore.clear()
                    resetDebugApi()
                    apiBaseUrl = applyPairingApi(null)
                    _state.update { TargetUiState(phase = EnrollmentPhase.REVOKED, apiBaseUrl = apiBaseUrl) }
                    return@launch
                }
                _state.update { it.copy(device = device, phase = EnrollmentPhase.ENROLLED, deviceNameInput = device.name) }
                connectSockets()
                refreshCapabilities()
            } catch (err: ApiException) {
                if (err.statusCode == 401) {
                    tokenStore.clear()
                    _state.update { TargetUiState(phase = EnrollmentPhase.NOT_ENROLLED, operation = Operation(OperationStatus.UNAUTHORIZED, message = err.message)) }
                } else {
                    _state.update { it.copy(deviceNameInput = stored.deviceId) }
                    fail(err)
                    connectSockets()
                }
            } catch (err: Exception) {
                fail(err)
            }
        }
    }

    private fun connectSockets() {
        val token = tokenStore.load()?.accessToken ?: return
        ws.connect(apiBaseUrl, token)
        TargetForegroundService.start(
            appContext,
            sessionActive = _state.value.session?.isTerminal == false && _state.value.session != null,
            projectionReady = capture.isRunning
        )
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                try {
                    val profile = profileFactory.create(_state.value.deviceNameInput.ifBlank { null })
                    val device = deviceRepository.heartbeat(profile)
                    val snap = telemetry.snapshot(device.connectionState)
                    _state.update {
                        it.copy(
                            device = device,
                            telemetryText = "${snap.manufacturer} ${snap.model} · API ${snap.sdkInt} · ${snap.networkType} · battery ${snap.batteryPercent ?: "?"}%"
                        )
                    }
                } catch (_: Exception) {
                }
                delay(20_000)
            }
        }
    }

    private fun scheduleSocketRetry() {
        if (reconnectJob?.isActive == true) return
        if (tokenStore.load()?.accessToken.isNullOrBlank()) return
        val phase = _state.value.phase
        if (phase == EnrollmentPhase.REVOKED || phase == EnrollmentPhase.NOT_ENROLLED) return
        reconnectJob = scope.launch {
            delay(3_000)
            val state = ws.state.value
            if (state == WsConnectionState.CONNECTED ||
                state == WsConnectionState.CONNECTING ||
                state == WsConnectionState.AUTHENTICATING
            ) {
                return@launch
            }
            if (tokenStore.load()?.accessToken.isNullOrBlank()) return@launch
            connectSockets()
        }
    }

    private suspend fun startMedia(resultCode: Int, permissionData: Intent) {
        val session = _state.value.session ?: return
        try {
            WebrtcDiag.log("capture_start", session.id)
            val ice = sessionRepository.iceServers()
            val source = webRtc.createVideoSource()
            val settings = if (_state.value.captureSettings.width == 1280 && _state.value.captureSettings.height == 720) {
                CaptureSettings.forDisplay(appContext)
            } else {
                _state.value.captureSettings
            }
            capture.start(resultCode, permissionData, source, settings)
            WebrtcDiag.log("capture_started", session.id)
            webRtc.ensurePeerConnection(ice)
            val activated = if (SessionActivatePolicy.shouldCallActivate(session.status)) {
                sessionRepository.activate(session.id).also {
                    WebrtcDiag.log("session_activated", session.id, "http=200")
                }
            } else {
                WebrtcDiag.log("session_activate_skipped", session.id, "status=${session.status}")
                session
            }
            applyPendingRemoteOffer(session.id)
            _state.update {
                it.copy(
                    session = activated,
                    phase = EnrollmentPhase.ACTIVE_SESSION,
                    captureDenied = false,
                    operation = Operation(OperationStatus.SUCCESS)
                )
            }
            refreshCapabilities()
        } catch (err: Exception) {
            WebrtcDiag.log("capture_failed", session.id, err.message)
            stopMedia()
            TargetForegroundService.start(appContext, sessionActive = true, projectionReady = false)
            _state.update { it.copy(captureDenied = true) }
            refreshCapabilities()
            fail(err)
        }
    }

    private suspend fun reconnectMedia() {
        val session = _state.value.session ?: return
        if (session.isTerminal) return
        try {
            val updated = sessionRepository.reconnect(session.id)
            _state.update { it.copy(session = updated, phase = EnrollmentPhase.ACTIVE_SESSION) }
            ws.sendJson("session.reconnect", SignalingPayloads.sessionOnly(session.id))
        } catch (_: Exception) {
        }
    }

    private fun stopMedia(reason: String = "session_media_stop") {
        WebrtcDiag.log("session_media_stop", _state.value.session?.id, "reason=$reason")
        capture.stop()
        webRtc.close(reason)
        createdOffer = false
        pendingProjection.set(null)
        pendingProjectionData = null
        _state.update { it.copy(webrtcState = "CLOSED") }
        refreshCapabilities()
    }

    private suspend fun handleSocket(type: String, payload: kotlinx.serialization.json.JsonObject?) {
        when (type) {
            "session.updated" -> {
                val sessionJson = payload?.get("session")?.jsonObject ?: return
                val parsed = appJson.decodeFromJsonElement(
                    RemoteSessionDto.serializer(),
                    sessionJson
                ).toDomain()
                val currentSession = _state.value.session
                if (currentSession != null && currentSession.id != parsed.id && !currentSession.isTerminal) {
                    WebrtcDiag.log("session_replaced", parsed.id, "old=${currentSession.id}")
                    stopMedia("session_replaced")
                }
                onSessionUpdated(parsed)
            }
            "signaling.offer" -> {
                val sessionId = SignalingPayloads.parseSessionId(payload) ?: return
                val sdp = SignalingPayloads.parseSdp(payload) ?: return
                WebrtcDiag.log("signaling.offer_received", sessionId, "dir=admin->target")
                if (sessionId != _state.value.session?.id) {
                    WebrtcDiag.log("signaling.offer_ignored", sessionId, "reason=session_mismatch")
                    return
                }
                pendingRemoteOffer.set(sdp)
                applyPendingRemoteOffer(sessionId)
            }
            "signaling.answer" -> {
                val sessionId = SignalingPayloads.parseSessionId(payload)
                WebrtcDiag.log("signaling.answer_received", sessionId, "dir=admin->target")
                if (!ScreenSignalingPolicy.shouldApplyRemoteAnswer(webRtc.hasLocalOffer())) {
                    WebrtcDiag.log("signaling.answer_ignored", sessionId, "reason=not_offerer")
                    return
                }
                val sdp = SignalingPayloads.parseSdp(payload) ?: return
                webRtc.setRemoteAnswer(sdp)
            }
            "signaling.ice" -> {
                val sessionId = SignalingPayloads.parseSessionId(payload)
                if (sessionId != null && sessionId != _state.value.session?.id) {
                    WebrtcDiag.log("ice_ignored", sessionId, "reason=session_mismatch")
                    return
                }
                WebrtcDiag.log("ice_received", sessionId, "dir=admin->target")
                SignalingPayloads.parseIce(payload)?.let { webRtc.addIce(it) }
            }
            "error" -> {
                val message = payload?.get("message")?.toString() ?: "Signaling error"
                fail(message, retryable = true)
            }
            "interaction.command" -> handleInteraction(payload)
            "screen.control" -> handleScreenControl(payload)
        }
    }

    private fun handleScreenControl(payload: kotlinx.serialization.json.JsonObject?) {
        val sessionId = SignalingPayloads.parseSessionId(payload) ?: _state.value.session?.id
        if (sessionId != null && sessionId != _state.value.session?.id) {
            WebrtcDiag.log("screen_control_ignored", sessionId, "reason=session_mismatch")
            return
        }
        val enabled = payload?.get("enabled")?.jsonPrimitive?.booleanOrNull ?: return
        if (!enabled) {
            WebrtcDiag.log("screen_control_off", sessionId)
            capture.pause()
            runCatching { webRtc.getOrCreateVideoTrack().setEnabled(false) }
            _state.update { it.copy(needsMediaProjection = false) }
            TargetForegroundService.start(appContext, sessionActive = true, projectionReady = capture.isRunning)
            refreshCapabilities()
            return
        }
        WebrtcDiag.log("screen_control_on", sessionId)
        if (capture.isRunning) {
            capture.resume()
            runCatching { webRtc.getOrCreateVideoTrack().setEnabled(true) }
            _state.update { it.copy(needsMediaProjection = false, captureDenied = false) }
            WebrtcDiag.log("screen_control_resumed", sessionId)
        } else {
            _state.update { it.copy(needsMediaProjection = true, captureDenied = false) }
        }
        TargetForegroundService.start(appContext, sessionActive = true, projectionReady = capture.isRunning)
        refreshCapabilities()
    }

    private suspend fun handleInteraction(payload: kotlinx.serialization.json.JsonObject?) {
        val started = System.currentTimeMillis()
        val raw = payload ?: kotlinx.serialization.json.JsonObject(emptyMap())
        fun str(key: String) = raw[key]?.jsonPrimitive?.contentOrNull
        fun num(key: String): Long? {
            val p = raw[key]?.jsonPrimitive ?: return null
            return p.longOrNull ?: p.doubleOrNull?.toLong() ?: p.content.toLongOrNull()
        }
        val paramsObj = raw["params"]?.jsonObject
        val params = InteractionParams(
            nx = paramsObj?.get("nx")?.jsonPrimitive?.doubleOrNull,
            ny = paramsObj?.get("ny")?.jsonPrimitive?.doubleOrNull,
            nx2 = paramsObj?.get("nx2")?.jsonPrimitive?.doubleOrNull,
            ny2 = paramsObj?.get("ny2")?.jsonPrimitive?.doubleOrNull,
            durationMs = paramsObj?.get("durationMs")?.jsonPrimitive?.longOrNull,
            direction = paramsObj?.get("direction")?.jsonPrimitive?.contentOrNull,
            viewId = paramsObj?.get("viewId")?.jsonPrimitive?.contentOrNull,
            text = paramsObj?.get("text")?.jsonPrimitive?.contentOrNull
        )
        val (parsed, parseError) = InteractionCommandValidator.parse(
            commandId = str("commandId"),
            sessionId = str("sessionId"),
            timestamp = num("timestamp"),
            operation = str("operation"),
            capability = str("capability"),
            params = params
        )
        val session = _state.value.session
        if (parseError != null || parsed == null) {
            InteractionDiag.log(
                "interaction_received",
                str("sessionId"),
                "op=${str("operation")} parse_error=${parseError?.code ?: "MALFORMED_COMMAND"} mode=${session?.mode?.name}"
            )
            InteractionDiag.log("interaction_rejected", str("sessionId"), "reason=${parseError?.code ?: "MALFORMED_COMMAND"}")
            replyInteraction(str("commandId"), str("sessionId") ?: session?.id, false, parseError?.code ?: "MALFORMED_COMMAND", parseError?.message, started)
            return
        }
        InteractionDiag.log(
            "interaction_received",
            parsed.sessionId,
            "op=${parsed.operation} id=${parsed.commandId.take(8)} mode=${session?.mode?.name}"
        )
        val rejection = InteractionCommandValidator.authorize(
            command = parsed,
            expectedSessionId = session?.id,
            sessionStatus = session?.status,
            sessionMode = session?.mode?.name,
            capabilityAvailable = RemoteInteractionService.instance != null,
            now = System.currentTimeMillis(),
            seenCommandIds = seenCommands
        )
        if (rejection != null) {
            InteractionDiag.log("interaction_rejected", parsed.sessionId, "reason=${rejection.code}")
            replyInteraction(parsed.commandId, parsed.sessionId, false, rejection.code, rejection.message, started)
            return
        }
        InteractionDiag.log("interaction_authorized", parsed.sessionId, "op=${parsed.operation}")
        val service = RemoteInteractionService.instance
        if (service == null) {
            InteractionDiag.log("interaction_rejected", parsed.sessionId, "reason=ACCESSIBILITY_SERVICE")
            replyInteraction(parsed.commandId, parsed.sessionId, false, "CAPABILITY_DISABLED", "Accessibility service is not connected.", started)
            return
        }
        InteractionDiag.log("accessibility_dispatch_attempt", parsed.sessionId, "op=${parsed.operation}")
        val (ok, message) = runCatching { service.execute(parsed) }.getOrElse { false to (it.message ?: "Execution failed") }
        if (ok) {
            InteractionDiag.log("gesture_dispatch_success", parsed.sessionId, "op=${parsed.operation}")
        } else {
            InteractionDiag.log("gesture_dispatch_failure", parsed.sessionId, "op=${parsed.operation} reason=${message ?: "unknown"}")
        }
        val coordLabel = when (parsed.operation) {
            "TAP", "LONG_PRESS" -> "${parsed.params.nx}, ${parsed.params.ny}"
            "SWIPE" -> "${parsed.params.nx},${parsed.params.ny} -> ${parsed.params.nx2},${parsed.params.ny2}"
            else -> null
        }
        _state.update {
            it.copy(
                lastRemoteEvent = parsed.operation,
                lastRemoteCoordinates = coordLabel,
                lastRemoteEventAt = System.currentTimeMillis()
            )
        }
        replyInteraction(parsed.commandId, parsed.sessionId, ok, if (ok) null else "EXECUTION_FAILED", message, started)
    }

    private fun replyInteraction(commandId: String?, sessionId: String?, ok: Boolean, code: String?, message: String?, started: Long) {
        val latency = (System.currentTimeMillis() - started).coerceAtLeast(0)
        _state.update {
            it.copy(lastInteraction = "${if (ok) "OK" else code ?: "FAIL"} · ${message ?: "executed"} · ${latency}ms")
        }
        if (commandId == null || sessionId == null) return
        ws.sendJson(
            "interaction.result",
            buildJsonObject {
                put("commandId", JsonPrimitive(commandId))
                put("sessionId", JsonPrimitive(sessionId))
                put("ok", JsonPrimitive(ok))
                if (code != null) put("code", JsonPrimitive(code))
                if (message != null) put("message", JsonPrimitive(message))
                put("latencyMs", JsonPrimitive(latency))
            }
        )
    }

    fun refreshCapabilities() {
        val st = _state.value
        val isSettingEnabled = Settings.Secure.getString(
            appContext.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        )?.let { AccessibilityInspector.isComponentListed(it, appContext.packageName) } == true
        val isAccessibilityOn = isSettingEnabled && (RemoteInteractionService.connected || RemoteInteractionService.instance != null)
        val caps = CapabilityReporter.report(
            accessibilityEnabled = isSettingEnabled,
            accessibilityConnected = RemoteInteractionService.connected,
            sdkInt = android.os.Build.VERSION.SDK_INT,
            captureActive = capture.isRunning
        )
        _state.update { current ->
            current.copy(
                screenCaptureActive = capture.isRunning,
                accessibilityEnabled = isAccessibilityOn,
                accessibilityConnected = RemoteInteractionService.connected,
                capabilityStates = caps.states,
                showAccessibilityOnboarding = !isAccessibilityOn
            )
        }
        if (itEnabled()) {
            scope.launch {
                runCatching {
                    val profile = profileFactory.create(_state.value.deviceNameInput.ifBlank { null })
                    val device = deviceRepository.heartbeat(profile)
                    val states = device.capabilities.states.ifEmpty { caps.states }.toMutableMap()
                    caps.states["ACCESSIBILITY_CONTROL"]?.let { states["ACCESSIBILITY_CONTROL"] = it }
                    caps.states["REMOTE_INTERACTION"]?.let { states["REMOTE_INTERACTION"] = it }
                    caps.states["SCREEN_CAPTURE"]?.let { states["SCREEN_CAPTURE"] = it }
                    _state.update { st ->
                        st.copy(
                            device = device,
                            capabilityStates = states,
                            showAccessibilityOnboarding = !isAccessibilityOn
                        )
                    }
                }
            }
        }
    }

    fun dismissAccessibilityOnboarding() {
        _state.update { it.copy(showAccessibilityOnboarding = false) }
    }

    private fun itEnabled(): Boolean = tokenStore.load() != null

    fun openAccessibilitySettings(activityContext: android.content.Context? = null) {
        val ctx = activityContext ?: appContext
        runCatching {
            ctx.startActivity(AccessibilityOnboarding.settingsIntent(ctx))
        }.onFailure {
            fail("Could not open Accessibility settings. Open Android Settings → Accessibility → PerkDevil.", retryable = false)
        }
    }

    private fun onSessionUpdated(session: RemoteSession) {
        if (session.isTerminal) {
            stopMedia()
            _state.update {
                it.copy(
                    session = null,
                    phase = EnrollmentPhase.CONNECTED,
                    captureDenied = false,
                    needsMediaProjection = false,
                    operation = Operation(OperationStatus.SESSION_ENDED, message = "Session ended.")
                )
            }
            refreshCapabilities()
            TargetForegroundService.start(appContext, sessionActive = false)
            return
        }
        _state.update { st ->
            val phase = when (session.status) {
                "ACTIVE", "AUTHENTICATED" -> EnrollmentPhase.ACTIVE_SESSION
                "CREATED" -> EnrollmentPhase.CONNECTED
                else -> st.phase
            }
            st.copy(
                session = session,
                phase = phase,
                captureDenied = if (capture.isRunning) false else st.captureDenied
            )
        }
        InteractionDiag.log("session_mode_sync", session.id, "mode=${session.mode.name} status=${session.status}")
        refreshCapabilities()
        if (session.status == "ACTIVE") {
            WebrtcDiag.log("session_active", session.id, "awaiting_admin_offer=${!createdOffer}")
        }
        if (session.status == "CREATED") {
            WebrtcDiag.log("auto_accepting_incoming_session", session.id)
            acceptIncomingSession()
        }
        refreshCapabilities()
    }

    private suspend fun applyPendingRemoteOffer(sessionId: String) {
        val sdp = pendingRemoteOffer.get() ?: return
        if (!ScreenSignalingPolicy.shouldAnswerRemoteOffer(capture.isRunning, sessionId == _state.value.session?.id)) {
            WebrtcDiag.log("signaling.offer_deferred", sessionId, "capture=${capture.isRunning}")
            return
        }
        createdOffer = true
        pendingRemoteOffer.set(null)
        WebrtcDiag.log("answer_create", sessionId)
        runCatching {
            val ice = sessionRepository.iceServers()
            webRtc.ensurePeerConnection(ice)
            val answer = webRtc.setRemoteOfferAndAnswer(sdp)
            WebrtcDiag.log("signaling.answer_sent", sessionId, "dir=target->admin")
            ws.sendJson("signaling.answer", SignalingPayloads.answer(sessionId, answer.description))
        }.onFailure {
            createdOffer = false
            pendingRemoteOffer.compareAndSet(null, sdp)
            WebrtcDiag.log("answer_failed", sessionId, it.message)
            fail(it as? Exception ?: Exception(it))
        }
    }

    private fun setLoading(message: String) {
        _state.update { it.copy(operation = Operation(OperationStatus.LOADING, message = message)) }
    }

    private fun fail(err: Exception) {
        val offline = err is java.io.IOException
        val unauthorized = err is ApiException && err.statusCode == 401
        val status = when {
            unauthorized -> OperationStatus.UNAUTHORIZED
            offline -> OperationStatus.OFFLINE
            else -> OperationStatus.FAILURE
        }
        _state.update {
            it.copy(operation = Operation(status = status, message = err.message ?: "Request failed", retryable = !unauthorized))
        }
    }

    private fun fail(message: String, retryable: Boolean) {
        _state.update { it.copy(operation = Operation(OperationStatus.FAILURE, message = message, retryable = retryable)) }
    }

    fun shutdown() {
        heartbeatJob?.cancel()
        reconnectJob?.cancel()
        stopMedia()
        ws.disconnect()
        RemoteInteractionService.removeListener(accessibilityListener)
        job.cancel()
    }
}
