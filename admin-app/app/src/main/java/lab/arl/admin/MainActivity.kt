package lab.arl.admin

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.compose.ui.viewinterop.AndroidView
import lab.arl.admin.BuildConfig
import lab.arl.admin.domain.DeviceRecord
import lab.arl.admin.domain.Panel
import lab.arl.admin.domain.SessionMode
import lab.arl.admin.interaction.InteractionDiag
import lab.arl.admin.interaction.VideoCoordinateMapper
import lab.arl.admin.interaction.VideoInteractionLayout
import lab.arl.admin.session.LabCoordinator
import lab.arl.admin.session.LabUiState
import lab.arl.admin.session.ScreenSharingSwitchUi
import lab.arl.admin.session.SessionModeAction
import lab.arl.admin.session.SessionModeSwitchUi
import org.webrtc.SurfaceViewRenderer

private val Bg = Color(0xFF070708)
private val PanelBg = Color(0xFF121214)
private val Line = Color(0xFF3A2A2A)
private val Red = Color(0xFFC4453C)
private val TextMain = Color(0xFFEDE6E4)
private val Mute = Color(0xFF9A8B88)
private val Online = Color(0xFF3DDC84)
private val Offline = Color(0xFF6B6462)
private val Mono = FontFamily.Monospace

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val lab = (application as AdminApplication).container.lab
        val webrtc = (application as AdminApplication).container.webrtc
        setContent {
            val state by lab.state.collectAsState()
            Box(Modifier.fillMaxSize().background(Bg).padding(12.dp)) {
                if (!state.loggedIn) LoginPane(state, lab)
                else Dashboard(state, lab) { renderer ->
                    webrtc.attachRenderer(renderer)
                }
            }
        }
    }

    override fun onDestroy() {
        (application as AdminApplication).container.webrtc.detachRenderer()
        super.onDestroy()
    }
}

@Composable
private fun LoginPane(state: LabUiState, lab: LabCoordinator) {
    Column(
        Modifier
            .fillMaxSize()
            .border(1.dp, Line, RoundedCornerShape(4.dp))
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Header(connected = false, onEnroll = {}, onLogout = {})
        Spacer(Modifier.height(24.dp))
        Text("OPERATOR SIGN-IN", color = Red, fontFamily = Mono, fontWeight = FontWeight.Bold)
        Field("Email", state.email, lab::setEmail)
        Field("Password", state.password, lab::setPassword, password = true)
        Field("Display name (register)", state.displayName, lab::setName)
        state.statusMessage?.let { Text(it, color = Red, fontFamily = Mono, fontSize = 12.sp) }
        if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth(), color = Red)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ActionBtn("SIGN IN", onClick = lab::login)
            ActionBtn("REGISTER", onClick = lab::register, outline = true)
        }
        Text("API ${BuildConfig.API_BASE_URL}", color = Mute, fontFamily = Mono, fontSize = 11.sp)
    }
}

@Composable
private fun Dashboard(state: LabUiState, lab: LabCoordinator, onRenderer: (SurfaceViewRenderer) -> Unit) {
    var fullscreen by remember { mutableStateOf(false) }
    val live = state.session?.isLive == true
    if (!live) fullscreen = false

    BackHandler(enabled = fullscreen) { fullscreen = false }

    if (fullscreen && live) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Bg)
                .onPreviewKeyEvent { event ->
                    if (event.type == KeyEventType.KeyDown && event.key == Key.Escape) {
                        fullscreen = false
                        true
                    } else {
                        false
                    }
                }
        ) {
            LiveView(state, lab, onRenderer, fullscreen = true, onExitFullscreen = { fullscreen = false })
        }
        return
    }

    Column(
        Modifier
            .fillMaxSize()
            .border(1.dp, Line, RoundedCornerShape(2.dp))
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Header(state.serverConnected, onEnroll = lab::enroll, onLogout = lab::logout)
        Text("TARGETS", color = Mute, fontFamily = Mono, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (state.devices.isEmpty()) {
                Text("No enrolled targets. Issue a pairing code.", color = Mute, fontFamily = Mono, fontSize = 12.sp)
            }
            state.devices.forEach { device ->
                TargetCard(device, selected = device.id == state.selected?.id) { lab.select(device.id) }
            }
        }
        Box(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .border(1.dp, Line)
                .background(PanelBg)
        ) {
            LiveView(state, lab, onRenderer, fullscreen = false, onExitFullscreen = {})
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (live) {
                ScreenSharingSwitchUi.forSession(state.session, state.screenSharingEnabled)?.let { screen ->
                    ActionBtn(
                        screen.buttonLabel,
                        onClick = { lab.setScreenSharing(!screen.enabled) },
                        outline = true
                    )
                }
                ActionBtn("FULL SCREEN", onClick = { fullscreen = true }, outline = true)
                ActionBtn("END SESSION", onClick = lab::endSession)
            } else {
                ActionBtn("CONNECT", onClick = lab::connect)
            }
            ActionBtn("BACKUP", onClick = lab::backup, outline = state.panel != Panel.BACKUP)
        }
        if (state.panel != Panel.NONE) {
            SidePanel(state, lab)
        }
        SessionLog(state)
        state.statusMessage?.let { Text(it, color = Red, fontFamily = Mono, fontSize = 11.sp) }
        if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth(), color = Red)
    }
}

@Composable
private fun Header(connected: Boolean, onEnroll: () -> Unit, onLogout: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("ANDROID REMOTE LAB", color = TextMain, fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 16.sp)
        Spacer(Modifier.weight(1f))
        Box(
            Modifier
                .padding(end = 6.dp)
                .width(8.dp)
                .height(8.dp)
                .clip(CircleShape)
                .background(if (connected) Online else Offline)
        )
        Text(
            if (connected) "SERVER CONNECTED" else "SERVER OFFLINE",
            color = if (connected) Online else Mute,
            fontFamily = Mono,
            fontSize = 11.sp
        )
        Spacer(Modifier.width(8.dp))
        TextButton(onClick = onEnroll) { Text("ENROLL", color = Red, fontFamily = Mono, fontSize = 11.sp) }
        TextButton(onClick = onLogout) { Text("LOGOUT", color = Mute, fontFamily = Mono, fontSize = 11.sp) }
    }
}

@Composable
private fun TargetCard(device: DeviceRecord, selected: Boolean, onClick: () -> Unit) {
    Column(
        Modifier
            .width(132.dp)
            .border(1.dp, if (selected) Red else Line)
            .background(if (selected) Color(0xFF1A1010) else PanelBg)
            .clickable(onClick = onClick)
            .padding(10.dp)
    ) {
        Text(device.label.take(12), color = TextMain, fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 12.sp)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .width(7.dp)
                    .height(7.dp)
                    .clip(CircleShape)
                    .background(if (device.online) Online else Offline)
            )
            Spacer(Modifier.width(6.dp))
            Text(if (device.online) "ONLINE" else "OFFLINE", color = if (device.online) Online else Mute, fontFamily = Mono, fontSize = 10.sp)
        }
        Text(device.osLine, color = Mute, fontFamily = Mono, fontSize = 10.sp)
    }
}

@Composable
private fun LiveView(
    state: LabUiState,
    lab: LabCoordinator,
    onRenderer: (SurfaceViewRenderer) -> Unit,
    fullscreen: Boolean,
    onExitFullscreen: () -> Unit
) {
    val selected = state.selected
    val session = state.session
    val modeSwitch = SessionModeSwitchUi.forSession(session)
    val managed = session?.mode == SessionMode.MANAGED
    // Touch layer is enabled in MANAGED while live; sendInteraction enforces capability checks.
    val interactive = managed && session?.isLive == true && state.serverConnected
    var viewSize by remember { mutableStateOf(IntSize.Zero) }
    val videoW = state.videoWidth.takeIf { it > 0 } ?: 1280
    val videoH = state.videoHeight.takeIf { it > 0 } ?: 720

    Column(Modifier.fillMaxSize()) {
        if (!fullscreen) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        selected?.label ?: "NO TARGET SELECTED",
                        color = TextMain,
                        fontFamily = Mono,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp
                    )
                    Text(
                        when {
                            modeSwitch != null -> modeSwitch.statusLabel
                            session == null -> "Not connected"
                            else -> "Session ${session.status}"
                        },
                        color = if (managed) Online else Mute,
                        fontFamily = Mono,
                        fontSize = 10.sp
                    )
                }
            }
        }
        Box(
            Modifier
                .fillMaxWidth()
                .then(if (fullscreen) Modifier.fillMaxHeight() else Modifier.weight(1f))
                .onSizeChanged { viewSize = it },
            contentAlignment = Alignment.Center
        ) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    VideoInteractionLayout(ctx) { renderer -> onRenderer(renderer) }
                },
                update = { layout ->
                    layout.setInteractionEnabled(interactive)
                    val touchSize = IntSize(
                        layout.width.coerceAtLeast(1),
                        layout.height.coerceAtLeast(1)
                    )
                    layout.touchListener = if (interactive) {
                        object : VideoInteractionLayout.TouchListener {
                            override fun onTap(x: Float, y: Float) {
                                mapAndInteract(session?.id, x, y, touchSize, videoW, videoH) { nx, ny ->
                                    lab.tap(nx, ny)
                                }
                            }

                            override fun onLongPress(x: Float, y: Float) {
                                mapAndInteract(session?.id, x, y, touchSize, videoW, videoH) { nx, ny ->
                                    lab.longPress(nx, ny)
                                }
                            }

                            override fun onSwipe(x1: Float, y1: Float, x2: Float, y2: Float) {
                                mapAndInteract(session?.id, x1, y1, touchSize, videoW, videoH) { nx, ny ->
                                    mapAndInteract(session?.id, x2, y2, touchSize, videoW, videoH) { nx2, ny2 ->
                                        lab.swipe(nx, ny, nx2, ny2)
                                    }
                                }
                            }
                        }
                    } else {
                        null
                    }
                    if (layout.renderer.width > 0 && layout.renderer.height > 0) {
                        onRenderer(layout.renderer)
                    }
                }
            )
            if (!state.streamReceived) {
                Text(
                    when {
                        session?.isLive == true && !state.screenSharingEnabled -> "SCREEN SHARING OFF"
                        session?.isLive == true -> "STARTING LIVE VIEW…"
                        else -> "LIVE DEVICE VIEW"
                    },
                    color = Mute,
                    fontFamily = Mono,
                    fontWeight = FontWeight.Bold,
                    fontSize = if (fullscreen) 22.sp else 18.sp
                )
            }
            if (fullscreen) {
                Row(
                    Modifier
                        .align(Alignment.TopStart)
                        .fillMaxWidth()
                        .background(Color(0xCC070708))
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            selected?.label ?: "TARGET",
                            color = TextMain,
                            fontFamily = Mono,
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp
                        )
                        Text(
                            modeSwitch?.statusLabel ?: (session?.status ?: ""),
                            color = if (managed) Online else Mute,
                            fontFamily = Mono,
                            fontSize = 10.sp
                        )
                    }
                    ActionBtn("EXIT FULL SCREEN", onClick = onExitFullscreen, outline = true)
                }
            }
            modeSwitch?.let { switch ->
                Box(Modifier.align(Alignment.BottomEnd).padding(12.dp).zIndex(2f)) {
                    ActionBtn(
                        switch.buttonLabel,
                        onClick = {
                            when (switch.action) {
                                SessionModeAction.ENTER_MANAGED -> lab.enterManagedMode()
                                SessionModeAction.EXIT_MANAGED -> lab.exitManagedMode()
                            }
                        },
                        outline = true
                    )
                }
            }
        }
        if (!fullscreen) {
            val sessionLine = session?.let {
                val screen = if (state.screenSharingEnabled) "SCREEN ON" else "SCREEN OFF"
                "${modeSwitch?.statusLabel ?: it.mode.name} · $screen · ${it.status} · WebRTC ${state.webrtcState}"
            } ?: "idle"
            Text(sessionLine, color = Mute, fontFamily = Mono, fontSize = 10.sp, modifier = Modifier.padding(6.dp))
        }
    }
}

private inline fun mapAndInteract(
    sessionId: String?,
    x: Float,
    y: Float,
    viewSize: IntSize,
    videoW: Int,
    videoH: Int,
    block: (Float, Float) -> Unit
) {
    InteractionDiag.log("touch_received", sessionId, "x=${x.toInt()} y=${y.toInt()}")
    val mapped = VideoCoordinateMapper.mapTouchToNormalized(
        x, y, viewSize.width, viewSize.height, videoW, videoH
    )
    if (mapped == null) {
        InteractionDiag.log("mapped_coordinates", sessionId, "rejected outside_video")
        return
    }
    InteractionDiag.log(
        "mapped_coordinates",
        sessionId,
        "nx=${"%.4f".format(mapped.first)} ny=${"%.4f".format(mapped.second)}"
    )
    block(mapped.first, mapped.second)
}

@Composable
private fun SidePanel(state: LabUiState, lab: LabCoordinator) {
    Column(
        Modifier
            .fillMaxWidth()
            .border(1.dp, Line)
            .background(PanelBg)
            .padding(10.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        when (state.panel) {
            Panel.BACKUP -> {
                Text("BACKUP", color = Red, fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                Text("Payloads are stored on this admin device under per-target folders. The API keeps metadata only.", color = Mute, fontFamily = Mono, fontSize = 11.sp)
                Text("FREE ${formatBytes(state.storageAvailableBytes)}  USED ${formatBytes(state.storageUsedBytes)}", color = Mute, fontFamily = Mono, fontSize = 10.sp)
                ActionBtn("NEW BACKUP RECORD", onClick = lab::createBackupRecord, outline = true)
                ActionBtn("PULL TRANSFERS", onClick = lab::pullBackups, outline = true)
                if (state.backups.isEmpty() && state.backupViews.isEmpty()) {
                    Text("No backups yet.", color = Mute, fontFamily = Mono, fontSize = 11.sp)
                }
                state.backupViews.forEach { file ->
                    val speed = file.speedBps?.let { "${it / 1024} KiB/s" } ?: "—"
                    Text("${file.filename}  ${file.targetName.ifBlank { file.deviceId.take(8) }}", color = TextMain, fontFamily = Mono, fontSize = 11.sp)
                    Text("size ${formatBytes(file.sizeBytes)}  ${file.percent}%  $speed", color = Mute, fontFamily = Mono, fontSize = 10.sp)
                    Text("checksum ${file.checksumState}  status ${file.status}  ${file.createdAt}", color = Mute, fontFamily = Mono, fontSize = 10.sp)
                    file.error?.let { Text(it, color = Red, fontFamily = Mono, fontSize = 10.sp) }
                    LinearProgressIndicator(
                        progress = { file.percent / 100f },
                        modifier = Modifier.fillMaxWidth(),
                        color = Red
                    )
                }
                state.backups.forEach { backup ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("${backup.id.take(8)}  ${backup.status}  ${backup.payloadState}", color = TextMain, fontFamily = Mono, fontSize = 10.sp)
                        TextButton(onClick = { lab.cancelBackup(backup.id) }) { Text("CANCEL", color = Mute, fontFamily = Mono, fontSize = 10.sp) }
                        TextButton(onClick = { lab.deleteBackup(backup.id) }) { Text("DELETE", color = Red, fontFamily = Mono, fontSize = 10.sp) }
                    }
                }
            }
            Panel.PAIR -> {
                Text("ENROLL TARGET", color = Red, fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                val p = state.pairing
                if (p != null) {
                    val context = LocalContext.current
                    Text("CODE  ${p.pairingCode}", color = TextMain, fontFamily = Mono, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    Text("PAIRING URI", color = Mute, fontFamily = Mono, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    Text(p.qrPayload, color = TextMain, fontFamily = Mono, fontSize = 11.sp)
                    Text("Physical Target uses api= in this URI (LAN). This Admin emulator still uses ${BuildConfig.API_BASE_URL}.", color = Mute, fontFamily = Mono, fontSize = 10.sp)
                    Text("Expires ${p.expiresAt}", color = Mute, fontFamily = Mono, fontSize = 11.sp)
                    TextButton(onClick = {
                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText("ARL pairing", p.qrPayload))
                    }) { Text("COPY LINK", color = Red, fontFamily = Mono, fontSize = 12.sp, fontWeight = FontWeight.Bold) }
                }
            }
            Panel.NONE -> {}
        }
    }
}

@Composable
private fun SessionLog(state: LabUiState) {
    Column(
        Modifier
            .fillMaxWidth()
            .height(120.dp)
            .border(1.dp, Line)
            .background(Color(0xFF0C0C0E))
            .padding(8.dp)
    ) {
        Text("SESSION LOG", color = Mute, fontFamily = Mono, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        Column(Modifier.verticalScroll(rememberScrollState())) {
            state.logs.asReversed().forEach { line ->
                Text("${line.time}  ${line.message}", color = TextMain, fontFamily = Mono, fontSize = 11.sp)
            }
        }
    }
}

@Composable
private fun ActionBtn(label: String, onClick: () -> Unit, outline: Boolean = false) {
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(
            containerColor = if (outline) Color.Transparent else Red,
            contentColor = if (outline) TextMain else Color.White
        ),
        shape = RoundedCornerShape(2.dp)
    ) { Text(label, fontFamily = Mono, fontSize = 12.sp, fontWeight = FontWeight.Bold) }
}

@Composable
private fun Field(label: String, value: String, onChange: (String) -> Unit, password: Boolean = false) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label, fontFamily = Mono) },
        visualTransformation = if (password) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        modifier = Modifier.fillMaxWidth(),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = Red,
            unfocusedBorderColor = Line,
            focusedTextColor = TextMain,
            unfocusedTextColor = TextMain,
            focusedLabelColor = Mute,
            unfocusedLabelColor = Mute
        )
    )
}

private fun formatBytes(value: Long): String {
    if (value < 1024) return "${value}B"
    if (value < 1024 * 1024) return "${value / 1024}KiB"
    return "${value / (1024 * 1024)}MiB"
}
