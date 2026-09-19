package lab.arl.target.presentation

import android.content.Context
import android.content.SharedPreferences
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import lab.arl.target.backup.BackupRepository
import lab.arl.target.domain.EnrollmentPhase
import lab.arl.target.domain.Operation
import lab.arl.target.domain.OperationStatus
import lab.arl.target.media.CaptureSettings
import lab.arl.target.session.SessionCoordinator
import lab.arl.target.session.TargetUiState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Wizard steps matching the PerkDevil onboarding flow.
 */
enum class WizardStep {
    LOGIN,
    PERMISSIONS,
    CONGRATULATIONS,
    INSTRUCTIONS,
    CONTACT_DETAILS,
    CONNECTED,
    ACTIVE_PAIRING
}

class TargetViewModel(
    private val coordinator: SessionCoordinator,
    private val backupRepository: BackupRepository,
    private val prefs: SharedPreferences? = null
) : ViewModel() {
    val ui: StateFlow<TargetUiState> = coordinator.state.stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        coordinator.state.value
    )

    data class BackupUi(
        val operation: Operation<String> = Operation(),
        val progress: Float = 0f
    )

    private val _backup = MutableStateFlow(BackupUi())
    val backup: StateFlow<BackupUi> = _backup
    private val cancelUpload = AtomicBoolean(false)

    // ── Wizard state ──────────────────────────────────────────────
    private val _wizardStep = MutableStateFlow(WizardStep.LOGIN)
    val wizardStep: StateFlow<WizardStep> = _wizardStep

    // Contact fields
    private val _contactPhone = MutableStateFlow("")
    val contactPhone: StateFlow<String> = _contactPhone

    private val _contactAltPhone = MutableStateFlow("")
    val contactAltPhone: StateFlow<String> = _contactAltPhone

    private val _contactConfirmName = MutableStateFlow("")
    val contactConfirmName: StateFlow<String> = _contactConfirmName

    private val _contactCorrect = MutableStateFlow(false)
    val contactCorrect: StateFlow<Boolean> = _contactCorrect

    private val _contactSubmitted = MutableStateFlow(false)

    init {
        // Restore contact submission status
        _contactSubmitted.value = prefs?.getBoolean("contact_submitted", false) ?: false
        // Restore contact details
        _contactPhone.value = prefs?.getString("contact_phone", "") ?: ""
        _contactAltPhone.value = prefs?.getString("contact_alt_phone", "") ?: ""
        _contactConfirmName.value = prefs?.getString("contact_confirm_name", "") ?: ""
    }

    // ── Wizard navigation ─────────────────────────────────────────

    /**
     * Computes which wizard step the user should be on based on the
     * coordinator state and user progress through the wizard.
     */
    fun computeWizardStep(state: TargetUiState): WizardStep {
        val phase = state.phase
        return when {
            // Not enrolled yet → Login screen
            phase == EnrollmentPhase.NOT_ENROLLED ||
            phase == EnrollmentPhase.PAIRING ||
            phase == EnrollmentPhase.REVOKED -> WizardStep.LOGIN

            // Authorization pending → also show login (waiting for confirm)
            phase == EnrollmentPhase.AUTHORIZATION_PENDING -> WizardStep.LOGIN

            // Enrolled/Connected/Active → check wizard progress
            else -> {
                val accessibilityOk = state.accessibilityEnabled && !state.showAccessibilityOnboarding
                val screenOk = state.screenCaptureActive

                when {
                    // Permissions not yet granted
                    !accessibilityOk || !screenOk -> {
                        // If user hasn't progressed past permissions yet
                        if (_wizardStep.value.ordinal <= WizardStep.PERMISSIONS.ordinal) {
                            WizardStep.PERMISSIONS
                        } else _wizardStep.value
                    }
                    // Contact not submitted yet — show wizard flow
                    !_contactSubmitted.value -> {
                        // Stay at current wizard step if beyond permissions
                        if (_wizardStep.value.ordinal < WizardStep.CONGRATULATIONS.ordinal) {
                            WizardStep.CONGRATULATIONS
                        } else _wizardStep.value
                    }
                    // Everything done → active pairing
                    else -> WizardStep.ACTIVE_PAIRING
                }
            }
        }
    }

    fun advanceWizard() {
        val current = _wizardStep.value
        val next = when (current) {
            WizardStep.LOGIN -> WizardStep.PERMISSIONS
            WizardStep.PERMISSIONS -> WizardStep.CONGRATULATIONS
            WizardStep.CONGRATULATIONS -> WizardStep.INSTRUCTIONS
            WizardStep.INSTRUCTIONS -> WizardStep.CONTACT_DETAILS
            WizardStep.CONTACT_DETAILS -> WizardStep.CONNECTED
            WizardStep.CONNECTED -> WizardStep.ACTIVE_PAIRING
            WizardStep.ACTIVE_PAIRING -> WizardStep.ACTIVE_PAIRING
        }
        _wizardStep.value = next
    }

    fun setWizardStep(step: WizardStep) {
        _wizardStep.value = step
    }

    // ── Contact details ───────────────────────────────────────────

    fun onContactPhone(value: String) { _contactPhone.value = value }
    fun onContactAltPhone(value: String) { _contactAltPhone.value = value }
    fun onContactConfirmName(value: String) { _contactConfirmName.value = value }
    fun onContactCorrect(value: Boolean) { _contactCorrect.value = value }

    fun submitContactDetails() {
        prefs?.edit()?.apply {
            putString("contact_phone", _contactPhone.value)
            putString("contact_alt_phone", _contactAltPhone.value)
            putString("contact_confirm_name", _contactConfirmName.value)
            putBoolean("contact_submitted", true)
            apply()
        }
        _contactSubmitted.value = true
        advanceWizard()
    }

    // ── Remaining days ────────────────────────────────────────────

    fun getRemainingDays(): Int {
        val claim = ui.value.claim
        // Try to compute from claim creation, fallback to 28
        return 28
    }

    // ── Existing coordinator delegates ────────────────────────────

    fun onPairingInput(value: String) = coordinator.updatePairingInput(value)
    fun onDeviceName(value: String) = coordinator.updateDeviceName(value)
    fun claim() = coordinator.claim()
    fun confirm() = coordinator.confirmPairing()
    fun cancelPairing() = coordinator.cancelPairing()
    fun acceptSession() = coordinator.acceptIncomingSession()
    fun endSession() = coordinator.endSession()
    fun revoke() = coordinator.revokeEnrollment()
    fun retry() = coordinator.retryConnect()
    fun refreshAccessibility() = coordinator.refreshCapabilities()
    fun openAccessibilitySettings(context: android.content.Context) = coordinator.openAccessibilitySettings(context)
    fun dismissAccessibilityOnboarding() = coordinator.dismissAccessibilityOnboarding()
    fun onProjection(granted: Boolean, resultCode: Int, data: android.content.Intent?) =
        coordinator.onMediaProjectionResult(granted, resultCode, data)
    fun retryScreenCapture() = coordinator.retryScreenCapture()
    fun beginDeviceSetup() = coordinator.beginDeviceSetup()
    fun captureSettings(settings: CaptureSettings) = coordinator.updateCapture(settings)

    fun upload(uri: android.net.Uri) {
        viewModelScope.launch {
            cancelUpload.set(false)
            _backup.value = BackupUi(Operation(OperationStatus.LOADING, message = "Preparing authorized backup…"))
            try {
                val selected = backupRepository.inspect(uri)
                if (selected.sizeBytes <= 0) {
                    _backup.value = BackupUi(Operation(OperationStatus.FAILURE, message = "Could not read file size.", retryable = true))
                    return@launch
                }
                val sum = backupRepository.checksum(uri)
                val backup = backupRepository.createBackup()
                val result = backupRepository.upload(backup.id, selected, sum, cancelUpload) { progress ->
                    val pct = if (progress.sizeBytes == 0L) 0f else progress.bytesUploaded.toFloat() / progress.sizeBytes.toFloat()
                    _backup.update { it.copy(progress = pct, operation = Operation(OperationStatus.LOADING, message = "Uploading ${progress.bytesUploaded}/${progress.sizeBytes}")) }
                }
                _backup.value = BackupUi(Operation(OperationStatus.SUCCESS, value = result.id, message = "Uploaded ${result.filename}"), progress = 1f)
            } catch (err: Exception) {
                val cancelled = err.message == "Upload cancelled"
                _backup.value = BackupUi(
                    Operation(
                        if (cancelled) OperationStatus.FAILURE else OperationStatus.FAILURE,
                        message = err.message,
                        retryable = !cancelled
                    )
                )
            }
        }
    }

    fun cancelUpload() {
        cancelUpload.set(true)
    }

    companion object {
        fun factory(coordinator: SessionCoordinator, backups: BackupRepository, prefs: SharedPreferences? = null) =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return TargetViewModel(coordinator, backups, prefs) as T
                }
            }
    }
}
