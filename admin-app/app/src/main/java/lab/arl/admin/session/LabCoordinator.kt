package lab.arl.admin.session

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import lab.arl.admin.BuildConfig
import lab.arl.admin.auth.StoredAdmin
import lab.arl.admin.auth.TokenStore
import lab.arl.admin.backup.BackupTransfer
import lab.arl.admin.backup.LocalBackupStore
import lab.arl.admin.domain.BackupFileView
import lab.arl.admin.domain.BackupRecord
import lab.arl.admin.domain.CommandEvent
import lab.arl.admin.domain.DeviceRecord
import lab.arl.admin.domain.LogLine
import lab.arl.admin.pairing.PairingLink
import lab.arl.admin.domain.PairingTicket
import lab.arl.admin.domain.Panel
import lab.arl.admin.domain.RemoteSession
import lab.arl.admin.domain.SessionMode
import lab.arl.admin.interaction.InteractionDiag
import lab.arl.admin.media.ViewerNegotiation
import lab.arl.admin.media.ViewerWebRtc
import lab.arl.admin.media.WebrtcDiag
import lab.arl.admin.network.AdminApi
import lab.arl.admin.network.AdminSocket
import lab.arl.admin.network.CreateSessionRequest
import lab.arl.admin.network.SetSessionModeRequest
import lab.arl.admin.network.LoginRequest
import lab.arl.admin.network.LogoutRequest
import lab.arl.admin.network.RegisterRequest
import lab.arl.admin.network.RemoteSessionDto
import lab.arl.admin.network.SignalingPayloads
import lab.arl.admin.network.WsState
import lab.arl.admin.network.appJson
import lab.arl.admin.network.toDomain
import org.webrtc.PeerConnection

data class LabUiState(
    val email: String = "",
    val password: String = "",
    val displayName: String = "Lab Operator",
    val loggedIn: Boolean = false,
    val serverConnected: Boolean = false,
    val statusMessage: String? = null,
    val devices: List<DeviceRecord> = emptyList(),
    val selectedId: String? = null,
    val session: RemoteSession? = null,
    val webrtcState: String = "NEW",
    val streamReceived: Boolean = false,
    val logs: List<LogLine> = emptyList(),
    val pairing: PairingTicket? = null,
    val backups: List<BackupRecord> = emptyList(),
    val backupViews: List<BackupFileView> = emptyList(),
    val storageUsedBytes: Long = 0,
    val storageAvailableBytes: Long = 0,
    val panel: Panel = Panel.NONE,
    val busy: Boolean = false,
    val lastCommandStatus: String? = null,
    val lastCommandLatencyMs: Long? = null,
    val lastCommandReason: String? = null,
    val commandEvents: List<CommandEvent> = emptyList(),
    val videoWidth: Int = 0,
    val videoHeight: Int = 0,
    val screenSharingEnabled: Boolean = true
) {
    val selected: DeviceRecord? get() = devices.firstOrNull { it.id == selectedId } ?: devices.firstOrNull()
}

class LabCoordinator(
    appContext: Context,
    private val apiBase: String,
    private val api: AdminApi,
    private val store: TokenStore,
    val socket: AdminSocket,
    val webrtc: ViewerWebRtc,
    private val backupStore: LocalBackupStore,
    private val backupTransfer: BackupTransfer
) {
    private val job = SupervisorJob()
    val scope = CoroutineScope(job + Dispatchers.Main.immediate)
    private val _state = MutableStateFlow(LabUiState())
    val state: StateFlow<LabUiState> = _state
    private var poll: Job? = null
    private var offered = false
    private val pendingSentAt = linkedMapOf<String, Long>()
    private var lastOperation = ""
    private val backupCancel = AtomicBoolean(false)

    init {
        webrtc.onIce = { c ->
            val sid = _state.value.session?.id
            if (sid != null) {
                WebrtcDiag.log("ice_sent", sid, "dir=admin->target")
                socket.send("signaling.ice", SignalingPayloads.ice(sid, c.sdpMid, c.sdpMLineIndex, c.sdp))
            }
        }
        webrtc.onState = { st ->
            _state.update { it.copy(webrtcState = st.name) }
            if (st == PeerConnection.PeerConnectionState.CONNECTED) {
                log("WebRTC connection established")
            }
            if (st == PeerConnection.PeerConnectionState.FAILED) {
                log("WebRTC FAILED — ICE could not connect (check TURN/firewall)")
                WebrtcDiag.log("pc_failed_reset")
                offered = false
                webrtc.close("ice_failed")
            }
        }
        webrtc.onTrack = {
            log("Screen track received")
            WebrtcDiag.log("on_track_ui", _state.value.session?.id)
        }
        webrtc.onFirstFrame = {
            _state.update { it.copy(streamReceived = true) }
            log("First video frame rendered")
            WebrtcDiag.log("first_frame_ui", _state.value.session?.id)
        }
        webrtc.onFrameResolution = { w, h, _ ->
            _state.update { it.copy(videoWidth = w, videoHeight = h) }
        }
        scope.launch {
            socket.messages.collect { env ->
                runCatching { handleWs(env.type, env.payload?.jsonObject) }
                    .onFailure {
                        WebrtcDiag.log("ws_handler_failed", extra = "${env.type} ${it.message}")
                        log(it.message ?: env.type)
                    }
            }
        }
        scope.launch {
            socket.state.collect { ws ->
                val connected = ws == WsState.CONNECTED
                _state.update { it.copy(serverConnected = connected) }
                if (connected) log("Control channel authenticated")
            }
        }
        store.load()?.let { restore(it) }
    }

    fun setEmail(v: String) = _state.update { it.copy(email = v) }
    fun setPassword(v: String) = _state.update { it.copy(password = v) }
    fun setName(v: String) = _state.update { it.copy(displayName = v) }
    fun select(id: String) = _state.update { it.copy(selectedId = id, panel = Panel.NONE) }
    fun togglePanel(p: Panel) = _state.update { it.copy(panel = if (it.panel == p) Panel.NONE else p) }

    fun login() = scope.launch {
        busy("Signing in…")
        try {
            val res = api.login(LoginRequest(_state.value.email.trim(), _state.value.password))
            persist(res.admin!!.toDomain().let { it.id to it.email }, res.tokens.toDomain())
        } catch (err: Exception) {
            fail(err)
        }
    }

    fun register() = scope.launch {
        busy("Creating operator…")
        try {
            val res = api.register(
                RegisterRequest(_state.value.email.trim(), _state.value.password, _state.value.displayName)
            )
            persist(res.admin!!.toDomain().let { it.id to it.email }, res.tokens.toDomain())
        } catch (err: Exception) {
            fail(err)
        }
    }

    fun logout() = scope.launch {
        runCatching { api.logout(LogoutRequest(store.load()?.refreshToken)) }
        stopSessionMedia("logout")
        socket.disconnect()
        store.clear()
        poll?.cancel()
        _state.value = LabUiState()
    }

    fun refreshDevices() = scope.launch { loadDevices() }

    fun enroll() = scope.launch {
        busy("Issuing pairing ticket…")
        try {
            val ticket = api.createPairing().pairingSession.toDomain()
            val pairingApi = if (BuildConfig.DEBUG) BuildConfig.PAIRING_API_BASE_URL else ""
            val uri = PairingLink.forTarget(ticket.qrPayload, pairingApi)
            _state.update {
                it.copy(
                    pairing = ticket.copy(qrPayload = uri),
                    panel = Panel.PAIR,
                    busy = false,
                    statusMessage = null
                )
            }
            log("Pairing code issued (expires ${ticket.expiresAt})")
        } catch (err: Exception) {
            fail(err)
        }
    }

    fun connect() {
        val device = _state.value.selected ?: return fail(IllegalStateException("Select a target first."))
        if (!device.online) return fail(IllegalStateException("${device.name} is offline."))
        scope.launch {
            busy("Opening remote session…")
            try {
                val existing = _state.value.session
                if (existing != null && existing.isLive) {
                    runCatching { api.terminate(existing.id) }
                    stopSessionMedia("new_session")
                }
                offered = false
                val session = api.createSession(CreateSessionRequest(device.id, "MONITOR")).session.toDomain()
                _state.update {
                    it.copy(
                        session = session,
                        panel = Panel.NONE,
                        busy = false,
                        statusMessage = null,
                        streamReceived = false,
                        screenSharingEnabled = true
                    )
                }
                log("Monitor session ${session.status} → waiting for target consent")
            } catch (err: Exception) {
                fail(err)
            }
        }
    }

    fun enterManagedMode() = scope.launch {
        val session = _state.value.session ?: return@launch fail(IllegalStateException("No active session."))
        if (!session.isLive) return@launch fail(IllegalStateException("Session is not live."))
        val device = _state.value.selected
        if (device?.capabilities?.canInteract != true) {
            return@launch fail(IllegalStateException(device?.capabilities?.remoteInteractionLabel ?: "Managed mode requires Accessibility on the target."))
        }
        busy("Enabling managed mode…")
        try {
            val updated = api.setMode(session.id, SetSessionModeRequest("MANAGED")).session.toDomain()
            _state.update { it.copy(session = updated, busy = false, statusMessage = null) }
            InteractionDiag.log("mode_managed", updated.id, "status=${updated.status}")
            runCatching { loadDevices() }
            log("Managed mode enabled — interact directly on the live screen")
        } catch (err: Exception) {
            fail(err)
        }
    }

    fun exitManagedMode() = scope.launch {
        val session = _state.value.session ?: return@launch
        if (!session.isLive || !session.isManaged) return@launch
        try {
            val updated = api.setMode(session.id, SetSessionModeRequest("MONITOR")).session.toDomain()
            _state.update { it.copy(session = updated, statusMessage = null) }
            log("Returned to monitor mode — live view continues")
        } catch (err: Exception) {
            fail(err)
        }
    }

    fun backup() {
        val device = _state.value.selected ?: return
        scope.launch {
            _state.update { it.copy(panel = Panel.BACKUP) }
            runCatching { refreshBackupPanel() }
            pullBackups()
        }
    }

    fun createBackupRecord() {
        val device = _state.value.selected ?: return
        scope.launch {
            try {
                val created = api.createBackup(CreateSessionRequest(device.id)).backup.toDomain()
                log("Backup ${created.id.take(8)} created — target must select files")
                refreshBackupPanel()
                pullBackups()
            } catch (err: Exception) {
                fail(err)
            }
        }
    }

    fun pullBackups() {
        val device = _state.value.selected ?: return
        scope.launch {
            backupCancel.set(false)
            backupTransfer.pullReadyFiles(device.id, backupCancel) { view ->
                val named = view.copy(targetName = _state.value.selected?.name.orEmpty())
                _state.update { st ->
                    val rest = st.backupViews.filterNot { it.fileId == named.fileId && it.backupId == named.backupId }
                    st.copy(
                        backupViews = rest + named,
                        storageUsedBytes = backupStore.usedBytes(),
                        storageAvailableBytes = backupStore.availableBytes()
                    )
                }
            }
            runCatching { refreshBackupPanel() }
        }
    }

    fun cancelBackup(id: String) = scope.launch {
        val device = _state.value.selected ?: return@launch
        backupCancel.set(true)
        runCatching { api.cancelBackup(id) }
        backupStore.cancel(device.id, id)
        log("Backup ${id.take(8)} cancelled")
        refreshBackupPanel()
    }

    fun deleteBackup(id: String) = scope.launch {
        val device = _state.value.selected ?: return@launch
        runCatching { api.deleteBackup(id) }
        backupStore.deleteBackup(device.id, id)
        log("Backup ${id.take(8)} deleted")
        refreshBackupPanel()
    }

    private suspend fun refreshBackupPanel() {
        val device = _state.value.selected ?: return
        val list = api.backups(device.id).backups.map { it.toDomain() }
        val views = list.flatMap { backup ->
            backup.files.map { file ->
                val local = backupStore.fileMeta(device.id, backup.id, file.id)
                val size = file.sizeBytes.toLongOrNull() ?: 0L
                BackupFileView(
                    backupId = backup.id,
                    fileId = file.id,
                    deviceId = device.id,
                    filename = file.filename,
                    sizeBytes = size,
                    bytesStored = local?.bytesStored ?: 0L,
                    status = local?.status ?: backup.status,
                    checksumState = local?.checksumState ?: if (file.payloadState == "LOCAL_ADMIN") "VERIFIED" else file.uploadState,
                    createdAt = backup.createdAt,
                    error = null,
                    targetName = device.name
                )
            }
        }
        _state.update {
            it.copy(
                backups = list,
                backupViews = views,
                storageUsedBytes = backupStore.usedBytes(),
                storageAvailableBytes = backupStore.availableBytes(),
                panel = it.panel
            )
        }
    }

    fun setScreenSharing(enabled: Boolean) = scope.launch {
        val session = _state.value.session ?: return@launch
        if (!session.isLive) return@launch
        if (enabled == _state.value.screenSharingEnabled) return@launch
        socket.send(
            "screen.control",
            buildJsonObject {
                put("sessionId", session.id)
                put("enabled", enabled)
            }
        )
        if (enabled) {
            _state.update { it.copy(screenSharingEnabled = true, streamReceived = false) }
            log("Screen sharing enabled — waiting for target capture")
            startViewer(session)
        } else {
            stopSessionMedia("screen_off")
            _state.update { it.copy(screenSharingEnabled = false) }
            log("Screen sharing stopped — session remains active")
        }
    }

    fun endSession() = scope.launch {
        val id = _state.value.session?.id ?: return@launch
        runCatching { api.terminate(id) }
        stopSessionMedia("session_ended")
        _state.update { it.copy(session = it.session?.copy(status = "TERMINATED"), panel = Panel.NONE) }
        log("Session terminated")
    }

    fun reconnect() = scope.launch {
        val id = _state.value.session?.id ?: return@launch
        try {
            val s = api.reconnect(id).session.toDomain()
            _state.update { it.copy(session = s) }
            socket.send("session.reconnect", buildJsonObject { put("sessionId", id) })
            log("Reconnect requested")
        } catch (err: Exception) {
            fail(err)
        }
    }

    fun sendInteraction(operation: String, params: JsonObject? = null) {
        val session = _state.value.session
        if (session == null || !session.isLive) {
            fail(IllegalStateException("Connect an authenticated session first."))
            return
        }
        if (!session.isManaged) {
            fail(IllegalStateException("Remote interaction is disabled in Monitor mode."))
            return
        }
        if (_state.value.serverConnected.not()) {
            fail(IllegalStateException("Control channel is not connected."))
            return
        }
        val commandId = UUID.randomUUID().toString()
        val payload = buildJsonObject {
            put("commandId", commandId)
            put("sessionId", session.id)
            put("timestamp", System.currentTimeMillis())
            put("operation", operation)
            put("capability", "REMOTE_INTERACTION")
            if (params != null) put("params", params)
        }
        InteractionDiag.log(
            "interaction_command_created",
            session.id,
            "op=$operation id=${commandId.take(8)} mode=${session.mode.name}"
        )
        pendingSentAt[commandId] = System.currentTimeMillis()
        lastOperation = operation
        recordEvent(CommandEvent(commandId, operation, "PENDING"))
        socket.send("interaction.command", payload)
        InteractionDiag.log("interaction_sent", session.id, "op=$operation id=${commandId.take(8)}")
        log("Command $operation sent")
    }

    fun tap(nx: Float, ny: Float) = sendInteraction(
        "TAP",
        buildJsonObject {
            put("nx", nx.toDouble())
            put("ny", ny.toDouble())
            put("durationMs", 80)
        }
    )

    fun longPress(nx: Float, ny: Float) = sendInteraction(
        "LONG_PRESS",
        buildJsonObject {
            put("nx", nx.toDouble())
            put("ny", ny.toDouble())
            put("durationMs", 600)
        }
    )

    fun swipe(nx: Float, ny: Float, nx2: Float, ny2: Float) = sendInteraction(
        "SWIPE",
        buildJsonObject {
            put("nx", nx.toDouble())
            put("ny", ny.toDouble())
            put("nx2", nx2.toDouble())
            put("ny2", ny2.toDouble())
            put("durationMs", 280)
        }
    )

    fun scroll(direction: String) = sendInteraction("SCROLL", buildJsonObject { put("direction", direction) })
    fun back() = sendInteraction("BACK")
    fun home() = sendInteraction("HOME")
    fun recents() = sendInteraction("RECENTS")
    fun nodeClick(viewId: String) = sendInteraction("NODE_CLICK", buildJsonObject { put("viewId", viewId) })
    fun nodeFocus(viewId: String) = sendInteraction("NODE_FOCUS", buildJsonObject { put("viewId", viewId) })
    fun textEntry(text: String) = sendInteraction("TEXT_ENTRY", buildJsonObject { put("text", text) })

    private fun recordEvent(event: CommandEvent) {
        _state.update {
            val existing = it.commandEvents.filterNot { e -> e.commandId == event.commandId }
            it.copy(
                commandEvents = (existing + event).takeLast(12),
                lastCommandStatus = event.status,
                lastCommandLatencyMs = event.latencyMs,
                lastCommandReason = event.reason
            )
        }
    }

    private fun restore(stored: StoredAdmin) {
        _state.update { it.copy(loggedIn = true, email = stored.email) }
        afterAuth()
    }

    private fun persist(admin: Pair<String, String>, tokens: lab.arl.admin.domain.TokenPair) {
        store.save(StoredAdmin(admin.first, admin.second, tokens.accessToken, tokens.refreshToken))
        _state.update { it.copy(loggedIn = true, busy = false, statusMessage = null, password = "") }
        afterAuth()
    }

    private fun afterAuth() {
        val token = store.load()?.accessToken ?: return
        socket.connect(apiBase, token)
        scope.launch {
            runCatching { loadDevices() }.onFailure {
                if (it is retrofit2.HttpException && it.code() == 401) logout()
            }
        }
        poll?.cancel()
        poll = scope.launch {
            while (isActive) {
                delay(8_000)
                runCatching { loadDevices() }
                runCatching { api.health() }
                if (socket.state.value == WsState.FAILED || socket.state.value == WsState.DISCONNECTED) {
                    store.load()?.accessToken?.let { token ->
                        socket.connect(apiBase, token)
                    }
                }
                val current = _state.value.session
                if (current != null && !current.isTerminal) {
                    runCatching {
                        val updated = api.session(current.id).session.toDomain()
                        if (updated.status != current.status || updated.mode != current.mode) {
                            _state.update { it.copy(session = updated) }
                            log("Session ${updated.status} · ${updated.mode.name}")
                            if (
                                (updated.status == "AUTHENTICATED" || updated.status == "ACTIVE") &&
                                _state.value.screenSharingEnabled && !offered
                            ) {
                                log("Target telemetry synchronized")
                                scope.launch { startViewer(updated) }
                            }
                        }
                    }
                }
                if (_state.value.panel == Panel.BACKUP) {
                    runCatching { refreshBackupPanel() }
                    pullBackups()
                }
            }
        }
        log("Operator authenticated")
    }

    private suspend fun loadDevices() {
        val list = api.devices().devices.map { it.toDomain() }
        _state.update { st ->
            val selected = st.selectedId?.takeIf { id -> list.any { it.id == id } } ?: list.firstOrNull()?.id
            st.copy(devices = list, selectedId = selected)
        }
    }

    private suspend fun startViewer(session: RemoteSession) {
        busy("Negotiating live view…")
        try {
            val ice = api.iceServers().iceServers.map { it.toDomain() }
            webrtc.ensurePc(ice)
            WebrtcDiag.log("viewer_start", session.id, "status=${session.status} offered=$offered")
            // Target activates the session when MediaProjection capture is running.
            if (ViewerNegotiation.shouldSendLocalOffer(offered, webrtc.hasRemoteDescription())) {
                offered = true
                WebrtcDiag.log("offer_create", session.id)
                val offer = webrtc.createOffer()
                socket.send("signaling.offer", SignalingPayloads.offer(session.id, offer.description))
                WebrtcDiag.log("signaling.offer_sent", session.id, "dir=admin->target")
                WebrtcDiag.log("screen_offer_sent", session.id)
                log("Screen offer sent")
            } else {
                WebrtcDiag.log("offer_skipped", session.id, "alreadyOffered=$offered remoteDesc=${webrtc.hasRemoteDescription()}")
            }
            _state.update { it.copy(busy = false, statusMessage = null) }
        } catch (err: Exception) {
            offered = false
            WebrtcDiag.log("viewer_start_failed", session.id, err.message)
            fail(err)
        }
    }

    private fun stopSessionMedia(reason: String = "session_media_stop") {
        WebrtcDiag.log("session_media_stop", _state.value.session?.id, "reason=$reason")
        offered = false
        webrtc.close(reason)
        _state.update { it.copy(streamReceived = false, webrtcState = "CLOSED") }
    }

    private suspend fun handleWs(type: String, payload: kotlinx.serialization.json.JsonObject?) {
        when (type) {
            "device.presence" -> {
                val id = payload?.get("deviceId")?.jsonPrimitive?.contentOrNull ?: return
                val conn = payload["connectionState"]?.jsonPrimitive?.contentOrNull ?: return
                _state.update { st ->
                    st.copy(devices = st.devices.map { if (it.id == id) it.copy(connectionState = conn) else it })
                }
                log("Target presence $conn")
                runCatching { loadDevices() }
            }
            "session.updated" -> {
                val sessionEl = payload?.get("session") ?: return
                val session = appJson.decodeFromJsonElement(RemoteSessionDto.serializer(), sessionEl).toDomain()
                _state.update { it.copy(session = session) }
                log("Session ${session.status} · ${session.mode.name}")
                if (session.isManaged) {
                    runCatching { loadDevices() }
                }
                if (
                    (session.status == "AUTHENTICATED" || session.status == "ACTIVE") &&
                    _state.value.screenSharingEnabled
                ) {
                    log("Target telemetry synchronized")
                    scope.launch { startViewer(session) }
                }
                if (session.isTerminal) stopSessionMedia("session_terminal_${session.status}")
            }
            "backup.updated" -> {
                log("Backup ${payload?.get("status")?.jsonPrimitive?.contentOrNull ?: "update"}")
                runCatching { refreshBackupPanel() }
                pullBackups()
            }
            "device.capabilities" -> {
                runCatching { loadDevices() }
                log("Target capability update received")
            }
            "interaction.result" -> {
                val commandId = payload?.get("commandId")?.jsonPrimitive?.contentOrNull ?: return
                val ok = payload["ok"]?.jsonPrimitive?.booleanOrNull ?: false
                val code = payload["code"]?.jsonPrimitive?.contentOrNull
                val message = payload["message"]?.jsonPrimitive?.contentOrNull
                val latency = payload["latencyMs"]?.jsonPrimitive?.longOrNull
                    ?: pendingSentAt[commandId]?.let { System.currentTimeMillis() - it }
                pendingSentAt.remove(commandId)
                val status = if (ok) "OK" else (code ?: "REJECTED")
                InteractionDiag.log(
                    if (ok) "interaction_result_ok" else "interaction_result_rejected",
                    _state.value.session?.id,
                    "op=${lastOperation.ifBlank { "COMMAND" }} code=${code ?: status} ${message.orEmpty()}"
                )
                recordEvent(CommandEvent(commandId, lastOperation.ifBlank { "COMMAND" }, status, latency, message ?: code))
                log("Command $status${latency?.let { " ${it}ms" } ?: ""}${if (!ok && message != null) " — $message" else ""}")
            }
            "error" -> {
                val code = payload?.get("code")?.jsonPrimitive?.contentOrNull
                val message = payload?.get("message")?.jsonPrimitive?.contentOrNull
                if (code != null && (
                        code.contains("COMMAND") ||
                            code.contains("SESSION") ||
                            code.contains("CAPABILITY") ||
                            code == "FORBIDDEN" ||
                            code == "MODE_VIEW_ONLY" ||
                            code == "DEVICE_OFFLINE" ||
                            code == "PEER_OFFLINE" ||
                            code == "STALE_COMMAND"
                        )
                ) {
                    recordEvent(
                        CommandEvent(
                            commandId = UUID.randomUUID().toString(),
                            operation = lastOperation.ifBlank { "COMMAND" },
                            status = code,
                            reason = message
                        )
                    )
                    log("$code ${message.orEmpty()}")
                }
            }
            "signaling.answer" -> {
                val sid = SignalingPayloads.parseSessionId(payload) ?: _state.value.session?.id
                val sdp = SignalingPayloads.parseSdp(payload) ?: return
                WebrtcDiag.log("signaling.answer_received", sid, "dir=target->admin")
                webrtc.setRemoteAnswer(sdp)
                log("Screen answer received")
            }
            "signaling.offer" -> {
                val sdp = SignalingPayloads.parseSdp(payload) ?: return
                val sid = SignalingPayloads.parseSessionId(payload) ?: return
                WebrtcDiag.log("signaling.offer_received", sid, "dir=target->admin")
                if (!ViewerNegotiation.shouldApplyRemoteOffer(offered)) {
                    WebrtcDiag.log("signaling.offer_ignored", sid, "reason=glare_local_offer")
                    return
                }
                val ice = runCatching { api.iceServers().iceServers.map { it.toDomain() } }.getOrDefault(emptyList())
                webrtc.ensurePc(ice)
                val answer = webrtc.setRemoteOfferAndAnswer(sdp)
                socket.send("signaling.answer", SignalingPayloads.answer(sid, answer.description))
                WebrtcDiag.log("signaling.answer_sent", sid, "dir=admin->target")
            }
            "signaling.ice" -> {
                val sid = SignalingPayloads.parseSessionId(payload)
                if (sid != null && _state.value.session?.id != null && sid != _state.value.session?.id) {
                    WebrtcDiag.log("ice_ignored", sid, "reason=session_mismatch")
                    return
                }
                WebrtcDiag.log("ice_received", sid, "dir=target->admin")
                SignalingPayloads.parseIce(payload)?.let { webrtc.addIce(it) }
            }
        }
    }

    private fun log(message: String) {
        val time = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        _state.update { it.copy(logs = (it.logs + LogLine(time, message)).takeLast(40)) }
    }

    private fun busy(msg: String) = _state.update { it.copy(busy = true, statusMessage = msg) }

    private fun fail(err: Exception) {
        _state.update { it.copy(busy = false, statusMessage = err.message ?: "Request failed") }
        log(err.message ?: "error")
    }
}
