package lab.arl.admin.network

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import lab.arl.admin.domain.AdminProfile
import lab.arl.admin.domain.BackupFileRecord
import lab.arl.admin.domain.BackupRecord
import lab.arl.admin.domain.DeviceCapabilities
import lab.arl.admin.domain.DeviceRecord
import lab.arl.admin.domain.IceServer
import lab.arl.admin.domain.PairingTicket
import lab.arl.admin.domain.RemoteSession
import lab.arl.admin.domain.SessionMode
import lab.arl.admin.domain.TokenPair

val appJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
}

@Serializable data class ApiErrorBody(val error: ApiErrorDetail? = null)
@Serializable data class ApiErrorDetail(val code: String? = null, val message: String? = null)
@Serializable data class TokenPairDto(val accessToken: String, val refreshToken: String, val expiresIn: Int)
@Serializable data class AdminDto(val id: String, val email: String, val displayName: String)
@Serializable data class AuthResponse(val admin: AdminDto? = null, val tokens: TokenPairDto)
@Serializable data class MeResponse(val admin: AdminDto)
@Serializable data class LoginRequest(val email: String, val password: String)
@Serializable data class RegisterRequest(val email: String, val password: String, val displayName: String)
@Serializable data class RefreshRequest(val refreshToken: String)
@Serializable data class TokensResponse(val tokens: TokenPairDto)
@Serializable data class CapabilitiesDto(
    val screenCapture: Boolean = false,
    val fileBackup: Boolean = false,
    val remoteInput: Boolean = false,
    val accessibilityControl: Boolean = false,
    val backgroundSession: Boolean = false,
    val states: Map<String, String> = emptyMap()
)
@Serializable data class DeviceDto(
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
@Serializable data class DevicesResponse(val devices: List<DeviceDto>)
@Serializable data class CreateSessionRequest(val deviceId: String, val mode: String = "MONITOR")
@Serializable data class SetSessionModeRequest(val mode: String)
@Serializable data class RemoteSessionDto(
    val id: String,
    val deviceId: String,
    val adminId: String,
    val status: String,
    val mode: String = "MONITOR",
    val reconnectCount: Int = 0
)
@Serializable data class SessionResponse(val session: RemoteSessionDto)
@Serializable data class SessionsResponse(val sessions: List<RemoteSessionDto> = emptyList())
@Serializable data class PairingCreatedDto(
    val id: String,
    val expiresAt: String,
    val pairingCode: String,
    val qrPayload: String
)
@Serializable data class PairingCreatedResponse(val pairingSession: PairingCreatedDto)
@Serializable data class IceServerDto(val urls: List<String>, val username: String? = null, val credential: String? = null)
@Serializable data class IceServersResponse(val iceServers: List<IceServerDto>)
@Serializable data class BackupFileDto(
    val id: String,
    val backupId: String,
    val filename: String,
    val sizeBytes: String = "0",
    val mimeType: String = "application/octet-stream",
    val checksumSha256: String = "",
    val uploadState: String = "PENDING",
    val payloadState: String = "PENDING",
    val createdAt: String = ""
)
@Serializable data class BackupDto(
    val id: String,
    val deviceId: String,
    val adminId: String? = null,
    val status: String,
    val storageProvider: String = "LOCAL_ADMIN",
    val payloadState: String = "PENDING",
    val createdAt: String = "",
    val updatedAt: String = "",
    val files: List<BackupFileDto> = emptyList()
)
@Serializable data class BackupsResponse(val backups: List<BackupDto> = emptyList())
@Serializable data class BackupResponse(val backup: BackupDto, val files: List<BackupFileDto> = emptyList())
@Serializable data class BackupFileResponse(val file: BackupFileDto, val backup: BackupDto? = null)
@Serializable data class IngestFileRequest(val checksumSha256: String, val bytesStored: Long)
@Serializable data class HealthResponse(val status: String? = null)
@Serializable data class WsEnvelopeDto(val v: Int = 1, val id: String? = null, val type: String, val payload: JsonElement? = null)
@Serializable data class LogoutRequest(val refreshToken: String? = null)

fun TokenPairDto.toDomain() = TokenPair(accessToken, refreshToken, expiresIn)
fun AdminDto.toDomain() = AdminProfile(id, email, displayName)
fun CapabilitiesDto.toDomain() = DeviceCapabilities(
    screenCapture, fileBackup, remoteInput, accessibilityControl, backgroundSession, states
)
fun DeviceDto.toDomain() = DeviceRecord(
    id, name, enrollmentState, authorizationState, platform, androidVersion,
    manufacturer, model, sdkInt, lastSeenAt, connectionState, capabilities.toDomain(), createdAt, updatedAt
)
fun RemoteSessionDto.toDomain() = RemoteSession(
    id,
    deviceId,
    adminId,
    status,
    if (mode == "MANAGED") SessionMode.MANAGED else SessionMode.MONITOR,
    reconnectCount
)
fun PairingCreatedDto.toDomain() = PairingTicket(id, pairingCode, qrPayload, expiresAt)
fun IceServerDto.toDomain() = IceServer(urls, username, credential)
fun BackupFileDto.toDomain() = BackupFileRecord(id, backupId, filename, sizeBytes, checksumSha256, uploadState, payloadState)
fun BackupDto.toDomain() = BackupRecord(id, deviceId, status, storageProvider, payloadState, createdAt, files.map { it.toDomain() })
