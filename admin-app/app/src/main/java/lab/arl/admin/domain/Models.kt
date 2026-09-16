package lab.arl.admin.domain

data class TokenPair(val accessToken: String, val refreshToken: String, val expiresIn: Int)

data class AdminProfile(val id: String, val email: String, val displayName: String)

data class DeviceCapabilities(
    val screenCapture: Boolean = false,
    val fileBackup: Boolean = false,
    val remoteInput: Boolean = false,
    val accessibilityControl: Boolean = false,
    val backgroundSession: Boolean = false,
    val states: Map<String, String> = emptyMap()
) {
    fun state(key: String): String = states[key]
        ?: when (key) {
            "SCREEN_CAPTURE" -> if (screenCapture) "AVAILABLE" else "NOT_GRANTED"
            "FILE_BACKUP" -> if (fileBackup) "AVAILABLE" else "NOT_GRANTED"
            "REMOTE_INTERACTION" -> if (remoteInput) "AVAILABLE" else "NOT_GRANTED"
            "ACCESSIBILITY_CONTROL" -> if (accessibilityControl) "AVAILABLE" else "NOT_GRANTED"
            "BACKGROUND_SESSION" -> if (backgroundSession) "AVAILABLE" else "NOT_GRANTED"
            else -> "UNAVAILABLE"
        }

    val remoteInteractionLabel: String
        get() = when (state("REMOTE_INTERACTION")) {
            "AVAILABLE" -> "CONTROL AVAILABLE"
            "NOT_GRANTED" -> "CONTROL REQUIRES ACCESSIBILITY PERMISSION"
            "RESTRICTED" -> "CONTROL RESTRICTED BY ANDROID"
            else -> "CONTROL UNAVAILABLE"
        }

    val canInteract: Boolean get() = state("REMOTE_INTERACTION") == "AVAILABLE"
}

data class CommandEvent(
    val commandId: String,
    val operation: String,
    val status: String,
    val latencyMs: Long? = null,
    val reason: String? = null
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
) {
    val online: Boolean get() = connectionState == "ONLINE"
    val label: String get() = name.ifBlank { "TARGET" }.uppercase()
    val osLine: String get() = if (androidVersion.isNullOrBlank()) "Android" else "Android $androidVersion"
}

enum class SessionMode { MONITOR, MANAGED }

data class RemoteSession(
    val id: String,
    val deviceId: String,
    val adminId: String,
    val status: String,
    val mode: SessionMode = SessionMode.MONITOR,
    val reconnectCount: Int = 0
) {
    val isLive: Boolean get() = status == "ACTIVE" || status == "AUTHENTICATED" || status == "RECONNECTING"
    val isTerminal: Boolean get() = status == "TERMINATED" || status == "REVOKED" || status == "TIMED_OUT"
    val isManaged: Boolean get() = mode == SessionMode.MANAGED
}

data class PairingTicket(val id: String, val pairingCode: String, val qrPayload: String, val expiresAt: String)

data class IceServer(val urls: List<String>, val username: String? = null, val credential: String? = null)

data class BackupRecord(
    val id: String,
    val deviceId: String,
    val status: String,
    val storageProvider: String = "LOCAL_ADMIN",
    val payloadState: String = "PENDING",
    val createdAt: String = "",
    val files: List<BackupFileRecord> = emptyList()
)

data class BackupFileRecord(
    val id: String,
    val backupId: String,
    val filename: String,
    val sizeBytes: String,
    val checksumSha256: String,
    val uploadState: String,
    val payloadState: String = "PENDING"
)

data class BackupFileView(
    val backupId: String,
    val fileId: String,
    val deviceId: String,
    val filename: String,
    val sizeBytes: Long,
    val bytesStored: Long,
    val status: String,
    val checksumState: String,
    val createdAt: String,
    val speedBps: Long? = null,
    val error: String? = null,
    val targetName: String = ""
) {
    val percent: Int get() = if (sizeBytes <= 0L) 0 else ((bytesStored * 100L) / sizeBytes).toInt().coerceIn(0, 100)
}

data class LogLine(val time: String, val message: String)

enum class Panel { NONE, BACKUP, PAIR }
