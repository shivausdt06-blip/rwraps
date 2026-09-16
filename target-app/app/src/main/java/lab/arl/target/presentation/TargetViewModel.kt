package lab.arl.target.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import lab.arl.target.backup.BackupRepository
import lab.arl.target.domain.Operation
import lab.arl.target.domain.OperationStatus
import lab.arl.target.media.CaptureSettings
import lab.arl.target.session.SessionCoordinator
import lab.arl.target.session.TargetUiState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

class TargetViewModel(
    private val coordinator: SessionCoordinator,
    private val backupRepository: BackupRepository
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
        fun factory(coordinator: SessionCoordinator, backups: BackupRepository) =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return TargetViewModel(coordinator, backups) as T
                }
            }
    }
}
