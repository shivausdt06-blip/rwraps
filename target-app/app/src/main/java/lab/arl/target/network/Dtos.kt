package lab.arl.target.network

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class ApiErrorBody(
    val error: ApiErrorDetail? = null
)

@Serializable
data class ApiErrorDetail(
    val code: String? = null,
    val message: String? = null
)

@Serializable
data class TokenPairDto(
    val accessToken: String,
    val refreshToken: String,
    val expiresIn: Int
)

@Serializable
data class CapabilitiesDto(
    val screenCapture: Boolean = false,
    val fileBackup: Boolean = false,
    val remoteInput: Boolean = false,
    val accessibilityControl: Boolean = false,
    val backgroundSession: Boolean = false,
    val states: Map<String, String> = emptyMap()
)

@Serializable
data class DeviceInfoDto(
    val name: String,
    val platform: String = "android",
    val androidVersion: String? = null,
    val manufacturer: String? = null,
    val model: String? = null,
    val sdkInt: Int? = null,
    val capabilities: CapabilitiesDto = CapabilitiesDto()
)

@Serializable
data class PairingClaimRequest(
    val pairingCode: String,
    val device: DeviceInfoDto
)

@Serializable
data class PairingClaimResponse(
    val pairingSessionId: String,
    val claimToken: String,
    val expiresAt: String
)

@Serializable
data class PairingConfirmRequest(
    val claimToken: String
)

@Serializable
data class DeviceDto(
    val id: String,
    val name: String,
    val enrollmentState: String,
    val authorizationState: String,
    val platform: String,
    val androidVersion: String? = null,
    val manufacturer: String? = null,
    val model: String? = null,
    val sdkInt: Int? = null,
    val lastSeenAt: String? = null,
    val connectionState: String,
    val capabilities: CapabilitiesDto = CapabilitiesDto(),
    val createdAt: String,
    val updatedAt: String
)

@Serializable
data class EnrollmentDto(
    val id: String,
    val status: String,
    val deviceId: String? = null,
    val adminId: String? = null
)

@Serializable
data class ConfirmPairingResponse(
    val device: DeviceDto,
    val tokens: TokenPairDto,
    val enrollment: EnrollmentDto
)

@Serializable
data class DeviceMeResponse(
    val device: DeviceDto
)

@Serializable
data class HeartbeatRequest(
    val capabilities: CapabilitiesDto? = null,
    val androidVersion: String? = null
)

@Serializable
data class HeartbeatResponse(
    val device: DeviceDto,
    val serverTime: String
)

@Serializable
data class RefreshRequest(
    val refreshToken: String
)

@Serializable
data class TokensResponse(
    val tokens: TokenPairDto
)

@Serializable
data class RemoteSessionDto(
    val id: String,
    val deviceId: String,
    val adminId: String,
    val status: String,
    val mode: String = "MONITOR",
    val startedAt: String? = null,
    val endedAt: String? = null,
    val timeoutAt: String? = null,
    val reconnectCount: Int = 0,
    val quality: JsonElement? = null,
    val createdAt: String,
    val updatedAt: String
)

@Serializable
data class SessionResponse(
    val session: RemoteSessionDto
)

@Serializable
data class TelemetryRequest(
    val rttMs: Double,
    val packetLoss: Double,
    val bitrateKbps: Double
)

@Serializable
data class IceServerDto(
    val urls: List<String>,
    val username: String? = null,
    val credential: String? = null
)

@Serializable
data class IceServersResponse(
    val iceServers: List<IceServerDto>
)

@Serializable
data class BackupDto(
    val id: String,
    val deviceId: String,
    val adminId: String,
    val status: String,
    val createdAt: String? = null,
    val updatedAt: String? = null
)

@Serializable
data class BackupResponse(
    val backup: BackupDto
)

@Serializable
data class CreateBackupFileRequest(
    val filename: String,
    val sizeBytes: Long,
    val mimeType: String,
    val checksumSha256: String
)

@Serializable
data class BackupFileDto(
    val id: String,
    val backupId: String,
    val filename: String,
    val sizeBytes: String,
    val mimeType: String,
    val checksumSha256: String,
    val uploadState: String,
    val storageKey: String? = null,
    val bytesUploaded: String,
    val createdAt: String? = null,
    val updatedAt: String? = null
)

@Serializable
data class UploadHintDto(
    val chunkSize: Int
)

@Serializable
data class BackupFileResponse(
    val file: BackupFileDto,
    val upload: UploadHintDto? = null
)

@Serializable
data class WsEnvelopeDto(
    val v: Int = 1,
    val id: String? = null,
    val type: String,
    val payload: JsonElement? = null
)
