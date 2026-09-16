package lab.arl.target.presentation

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import lab.arl.target.TargetApplication
import lab.arl.target.domain.EnrollmentPhase
import lab.arl.target.domain.OperationStatus
import lab.arl.target.presentation.theme.ArlTargetTheme
import lab.arl.target.session.TargetUiState

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as TargetApplication
        val vm: TargetViewModel by viewModels {
            TargetViewModel.factory(app.container.coordinator, app.container.backupRepository)
        }
        intent?.data?.toString()?.let { vm.onPairingInput(it) }
        setContent {
            ArlTargetTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    TargetScreen(vm)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        (application as TargetApplication).container.coordinator.refreshCapabilities()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val app = application as TargetApplication
        intent.data?.toString()?.let { app.container.coordinator.updatePairingInput(it) }
    }
}

@Composable
fun TargetScreen(vm: TargetViewModel) {
    val state by vm.ui.collectAsState()
    val backup by vm.backup.collectAsState()
    val presentation = remember(state) { TargetPresentationMapper.map(state, state.screenCaptureActive) }
    val context = LocalContext.current
    val projectionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        vm.onProjection(result.resultCode == Activity.RESULT_OK, result.resultCode, result.data)
    }
    val notifyLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    val fileLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.upload(uri)
    }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33) {
            notifyLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    LaunchedEffect(state.needsMediaProjection) {
        if (state.needsMediaProjection) {
            val mpm = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            projectionLauncher.launch(mpm.createScreenCaptureIntent())
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("ARL Target", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text("Consent-based remote support client. The device owner must enroll and can revoke access.")

        when (state.phase) {
            EnrollmentPhase.NOT_ENROLLED, EnrollmentPhase.PAIRING, EnrollmentPhase.REVOKED -> {
                OutlinedTextField(
                    value = state.deviceNameInput,
                    onValueChange = vm::onDeviceName,
                    label = { Text("Device name") },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = state.pairingInput,
                    onValueChange = vm::onPairingInput,
                    label = { Text("Pairing code or arl://pair?code=…") },
                    modifier = Modifier.fillMaxWidth()
                )
                Button(onClick = vm::claim, modifier = Modifier.fillMaxWidth()) { Text("Claim pairing") }
                if (state.phase == EnrollmentPhase.REVOKED) {
                    Text("Previous enrollment was revoked. Pair again to authorize a new admin.")
                }
            }
            EnrollmentPhase.AUTHORIZATION_PENDING -> {
                Text("Review this pairing. Confirm only if you intend to authorize remote support from the lab administrator.")
                Text("Claim expires at ${state.claim?.expiresAt.orEmpty()}")
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = vm::confirm) { Text("I authorize enrollment") }
                    TextButton(onClick = vm::cancelPairing) { Text("Cancel") }
                }
            }
            EnrollmentPhase.ENROLLED, EnrollmentPhase.CONNECTED, EnrollmentPhase.ACTIVE_SESSION -> {
                ConnectionSummary(presentation, state)
                DeviceSetupCard(presentation, vm, expandedByDefault = !presentation.setupComplete)
                if (presentation.currentSession.visible) {
                    CurrentSessionCard(presentation.currentSession, vm)
                    if (presentation.currentSession.isLive) {
                        RemoteInteractionTestCard(state)
                    }
                } else {
                    Text(
                        "No active remote session. An administrator can start one when this device is online.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (state.telemetryText.isNotBlank()) {
                    Text(state.telemetryText, style = MaterialTheme.typography.bodySmall)
                }
                BackupSection(backup, fileLauncher, vm)
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = vm::revoke) { Text("Revoke enrollment") }
            }
        }

        if (presentation.globalLoading) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        presentation.globalMessage?.let { message ->
            val isError = state.operation.status == OperationStatus.FAILURE ||
                state.operation.status == OperationStatus.UNAUTHORIZED
            Text(
                message,
                color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
            )
        }
        if (presentation.showConnectionRetry) {
            TextButton(onClick = vm::retry) { Text("Retry connection") }
        }
        Text("API ${state.apiBaseUrl.ifBlank { lab.arl.target.BuildConfig.API_BASE_URL }}", style = MaterialTheme.typography.bodySmall)
    }

    if (state.showAccessibilityOnboarding) {
        AlertDialog(
            onDismissRequest = vm::dismissAccessibilityOnboarding,
            title = { Text("Enable Accessibility for remote support") },
            text = {
                Text(
                    "ARL Target needs the Accessibility service so an authorized lab administrator can send taps, swipes, and navigation actions during a consented remote-support session. " +
                        "Android will ask you to turn on “ARL Target” yourself. This app cannot enable the service silently. " +
                        "Password fields, the lock screen, and security dialogs stay blocked."
                )
            },
            confirmButton = {
                Button(onClick = { vm.openAccessibilitySettings(context) }) {
                    Text("Open Accessibility Settings")
                }
            },
            dismissButton = {
                TextButton(onClick = vm::dismissAccessibilityOnboarding) {
                    Text("Not Now")
                }
            }
        )
    }
}

@Composable
private fun ConnectionSummary(presentation: TargetPresentation, state: TargetUiState) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(state.device?.name ?: "This device", fontWeight = FontWeight.Bold)
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusDot(presentation.connectionOnline)
                Spacer(Modifier.width(8.dp))
                Text(presentation.connectionLabel, fontWeight = FontWeight.SemiBold)
            }
            Text("Device ID: ${state.device?.id.orEmpty()}", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun DeviceSetupCard(
    presentation: TargetPresentation,
    vm: TargetViewModel,
    expandedByDefault: Boolean
) {
    var expanded by remember(expandedByDefault) { mutableStateOf(expandedByDefault) }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    if (presentation.setupComplete) "DEVICE READY" else "SET UP THIS DEVICE",
                    fontWeight = FontWeight.Bold
                )
                if (presentation.setupComplete) {
                    TextButton(onClick = { expanded = !expanded }) {
                        Text(if (expanded) "Hide" else "Show")
                    }
                }
            }
            if (!presentation.setupComplete || expanded) {
                if (!presentation.setupComplete) {
                    Text(
                        "Complete setup through Android's legitimate consent flows.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                presentation.setupItems.forEach { item -> SetupLine(item) }
                if (!presentation.setupComplete) {
                    Button(onClick = vm::beginDeviceSetup, modifier = Modifier.fillMaxWidth()) {
                        Text("SET UP DEVICE")
                    }
                }
                TextButton(onClick = vm::refreshAccessibility) {
                    Text("Refresh capability status")
                }
            }
        }
    }
}

@Composable
private fun RemoteInteractionTestCard(state: TargetUiState) {
    var localTapCount by remember { mutableStateOf(0) }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("REMOTE TEST AREA", fontWeight = FontWeight.Bold)
            Text(
                "Use this area to verify Managed Mode from the Admin mirror.",
                style = MaterialTheme.typography.bodySmall
            )
            Button(
                onClick = { localTapCount += 1 },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("TAP TEST (local taps: $localTapCount)")
            }
            Text("Last remote event: ${state.lastRemoteEvent ?: "—"}")
            Text("Coordinates: ${state.lastRemoteCoordinates ?: "—"}")
            Text(
                "Last event time: ${
                    state.lastRemoteEventAt?.let { java.text.SimpleDateFormat.getTimeInstance().format(java.util.Date(it)) } ?: "—"
                }"
            )
            state.lastInteraction?.let { Text("Result: $it", style = MaterialTheme.typography.bodySmall) }
        }
    }
}

@Composable
private fun CurrentSessionCard(session: CurrentSessionUi, vm: TargetViewModel) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("CURRENT SESSION", fontWeight = FontWeight.Bold)
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusDot(session.isLive)
                Spacer(Modifier.width(8.dp))
                Text(session.modeLabel, fontWeight = FontWeight.SemiBold)
            }
            Text("Administrator session for ${session.deviceName}")
            Text("Screen sharing: ${session.screenStatus}")
            Text("WebRTC: ${session.webrtcLabel}")
            if (session.isLive) {
                Text("● LIVE", color = Color(0xFF2E7D32), fontWeight = FontWeight.Bold)
            }
            if (session.showAccept) {
                Button(onClick = vm::acceptSession, modifier = Modifier.fillMaxWidth()) {
                    Text("Accept session")
                }
            }
            if (session.showGrantCapture) {
                OutlinedButton(onClick = vm::retryScreenCapture, modifier = Modifier.fillMaxWidth()) {
                    Text("Grant screen capture")
                }
            }
            if (session.showEndSession) {
                OutlinedButton(onClick = vm::endSession, modifier = Modifier.fillMaxWidth()) {
                    Text("End session")
                }
            }
        }
    }
}

@Composable
private fun BackupSection(
    backup: TargetViewModel.BackupUi,
    fileLauncher: androidx.activity.result.ActivityResultLauncher<Array<String>>,
    vm: TargetViewModel
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Backup", fontWeight = FontWeight.SemiBold)
        Text("Authorized backup uses the system document picker only.", style = MaterialTheme.typography.bodySmall)
        OutlinedButton(onClick = { fileLauncher.launch(arrayOf("*/*")) }, modifier = Modifier.fillMaxWidth()) {
            Text("Select file to back up")
        }
        if (backup.operation.status == OperationStatus.LOADING) {
            LinearProgressIndicator(progress = { backup.progress }, modifier = Modifier.fillMaxWidth())
            TextButton(onClick = vm::cancelUpload) { Text("Cancel upload") }
        }
        backup.operation.message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable
private fun SetupLine(item: SetupItem) {
    val prefix = when (item.state) {
        SetupItemState.READY -> "✓"
        SetupItemState.WARNING -> "⚠"
        SetupItemState.PENDING -> "○"
    }
    Column {
        Text(
            "$prefix ${item.label}",
            fontWeight = if (item.state == SetupItemState.READY) FontWeight.SemiBold else FontWeight.Normal
        )
        item.detail?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun StatusDot(online: Boolean) {
    Text(
        "●",
        color = if (online) Color(0xFF2E7D32) else MaterialTheme.colorScheme.onSurfaceVariant,
        fontWeight = FontWeight.Bold
    )
}
