package lab.arl.target.domain

enum class EnrollmentPhase {
    NOT_ENROLLED,
    PAIRING,
    AUTHORIZATION_PENDING,
    ENROLLED,
    CONNECTED,
    ACTIVE_SESSION,
    REVOKED
}

enum class OperationStatus {
    IDLE,
    LOADING,
    SUCCESS,
    FAILURE,
    RETRYING,
    OFFLINE,
    UNAUTHORIZED,
    PERMISSION_DENIED,
    SESSION_ENDED
}

data class Operation<T>(
    val status: OperationStatus = OperationStatus.IDLE,
    val value: T? = null,
    val message: String? = null,
    val retryable: Boolean = false
)

data class DeviceCapabilities(
    val screenCapture: Boolean = true,
    val fileBackup: Boolean = true,
    val remoteInput: Boolean = false,
    val accessibilityControl: Boolean = false,
    val backgroundSession: Boolean = true,
    val states: Map<String, String> = emptyMap()
) {
    fun state(key: String): String = states[key] ?: "UNAVAILABLE"
}

data class DeviceProfile(
    val name: String,
    val platform: String = "android",
    val androidVersion: String,
    val manufacturer: String,
    val model: String,
    val sdkInt: Int,
    val capabilities: DeviceCapabilities
)

data class TokenPair(
    val accessToken: String,
    val refreshToken: String,
    val expiresIn: Int
)

data class DeviceRecord(
    val id: String,
    val name: String,
    val enrollmentState: String,
    val authorizationState: String,
    val platform: String,
    val androidVersion: String?,
    val manufacturer: String?,
    val model: String?,
    val sdkInt: Int?,
    val lastSeenAt: String?,
    val connectionState: String,
    val capabilities: DeviceCapabilities,
    val createdAt: String,
    val updatedAt: String
)

data class EnrollmentRecord(
    val id: String,
    val status: String
)

data class PairingClaim(
    val pairingSessionId: String,
    val claimToken: String,
    val expiresAt: String
)

data class PairingInput(
    val code: String,
    val sessionId: String? = null
)

enum class SessionMode { MONITOR, MANAGED }

data class RemoteSession(
    val id: String,
    val deviceId: String,
    val adminId: String,
    val status: String,
    val mode: SessionMode = SessionMode.MONITOR,
    val startedAt: String?,
    val endedAt: String?,
    val timeoutAt: String?,
    val reconnectCount: Int,
    val quality: SessionQuality?,
    val createdAt: String,
    val updatedAt: String
) {
    val isTerminal: Boolean
        get() = status == "TERMINATED" || status == "REVOKED" || status == "TIMED_OUT"
    val isManaged: Boolean get() = mode == SessionMode.MANAGED
}

data class SessionQuality(
    val rttMs: Double,
    val packetLoss: Double,
    val bitrateKbps: Double
)

data class IceServer(
    val urls: List<String>,
    val username: String? = null,
    val credential: String? = null
)

data class BackupRecord(
    val id: String,
    val deviceId: String,
    val adminId: String,
    val status: String
)

data class BackupFileRecord(
    val id: String,
    val backupId: String,
    val filename: String,
    val sizeBytes: Long,
    val mimeType: String,
    val checksumSha256: String,
    val uploadState: String,
    val bytesUploaded: Long
)

data class BackupInitResult(
    val file: BackupFileRecord,
    val chunkSize: Int
)

data class TelemetrySnapshot(
    val model: String,
    val manufacturer: String,
    val androidVersion: String,
    val sdkInt: Int,
    val batteryPercent: Int?,
    val charging: Boolean?,
    val networkType: String,
    val screenWidth: Int,
    val screenHeight: Int,
    val appVersion: String,
    val connectionState: String
)
