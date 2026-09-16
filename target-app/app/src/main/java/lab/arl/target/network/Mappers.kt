package lab.arl.target.network

import kotlinx.serialization.json.Json
import lab.arl.target.domain.BackupFileRecord
import lab.arl.target.domain.BackupInitResult
import lab.arl.target.domain.BackupRecord
import lab.arl.target.domain.DeviceCapabilities
import lab.arl.target.domain.DeviceRecord
import lab.arl.target.domain.EnrollmentRecord
import lab.arl.target.domain.IceServer
import lab.arl.target.domain.PairingClaim
import lab.arl.target.domain.RemoteSession
import lab.arl.target.domain.SessionMode
import lab.arl.target.domain.SessionQuality
import lab.arl.target.domain.TokenPair

val appJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
}

fun TokenPairDto.toDomain() = TokenPair(accessToken, refreshToken, expiresIn)

fun CapabilitiesDto.toDomain() = DeviceCapabilities(
    screenCapture = screenCapture,
    fileBackup = fileBackup,
    remoteInput = remoteInput,
    accessibilityControl = accessibilityControl,
    backgroundSession = backgroundSession,
    states = states
)

fun DeviceCapabilities.toDto() = CapabilitiesDto(
    screenCapture = screenCapture,
    fileBackup = fileBackup,
    remoteInput = remoteInput,
    accessibilityControl = accessibilityControl,
    backgroundSession = backgroundSession,
    states = states
)

fun DeviceDto.toDomain() = DeviceRecord(
    id = id,
    name = name,
    enrollmentState = enrollmentState,
    authorizationState = authorizationState,
    platform = platform,
    androidVersion = androidVersion,
    manufacturer = manufacturer,
    model = model,
    sdkInt = sdkInt,
    lastSeenAt = lastSeenAt,
    connectionState = connectionState,
    capabilities = capabilities.toDomain(),
    createdAt = createdAt,
    updatedAt = updatedAt
)

fun EnrollmentDto.toDomain() = EnrollmentRecord(id, status)

fun PairingClaimResponse.toDomain() = PairingClaim(pairingSessionId, claimToken, expiresAt)

fun RemoteSessionDto.toDomain(): RemoteSession {
    val quality = quality?.let { element ->
        runCatching { appJson.decodeFromJsonElement(TelemetryRequest.serializer(), element) }.getOrNull()
    }
    return RemoteSession(
        id = id,
        deviceId = deviceId,
        adminId = adminId,
        status = status,
        mode = if (mode == "MANAGED") SessionMode.MANAGED else SessionMode.MONITOR,
        startedAt = startedAt,
        endedAt = endedAt,
        timeoutAt = timeoutAt,
        reconnectCount = reconnectCount,
        quality = quality?.let { SessionQuality(it.rttMs, it.packetLoss, it.bitrateKbps) },
        createdAt = createdAt,
        updatedAt = updatedAt
    )
}

fun IceServerDto.toDomain() = IceServer(urls, username, credential)

fun BackupDto.toDomain() = BackupRecord(id, deviceId, adminId, status)

fun BackupFileDto.toDomain() = BackupFileRecord(
    id = id,
    backupId = backupId,
    filename = filename,
    sizeBytes = sizeBytes.toLongOrNull() ?: 0L,
    mimeType = mimeType,
    checksumSha256 = checksumSha256,
    uploadState = uploadState,
    bytesUploaded = bytesUploaded.toLongOrNull() ?: 0L
)

fun BackupFileResponse.toInit() = BackupInitResult(file.toDomain(), upload?.chunkSize ?: 1_048_576)
