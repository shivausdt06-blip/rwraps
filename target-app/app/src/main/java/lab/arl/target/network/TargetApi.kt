package lab.arl.target.network

import okhttp3.RequestBody
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query

interface TargetApi {
    @POST("v1/pairing/claim")
    suspend fun claimPairing(@Body body: PairingClaimRequest): PairingClaimResponse

    @POST("v1/pairing/confirm")
    suspend fun confirmPairing(@Body body: PairingConfirmRequest): ConfirmPairingResponse

    @GET("v1/devices/me")
    suspend fun deviceMe(): DeviceMeResponse

    @POST("v1/devices/me/heartbeat")
    suspend fun heartbeat(@Body body: HeartbeatRequest = HeartbeatRequest()): HeartbeatResponse

    @POST("v1/devices/me/refresh")
    suspend fun refresh(@Body body: RefreshRequest): TokensResponse

    @POST("v1/devices/me/revoke")
    suspend fun revokeEnrollment()

    @GET("v1/sessions/{id}")
    suspend fun getSession(@Path("id") id: String): SessionResponse

    @POST("v1/sessions/{id}/authenticate")
    suspend fun authenticateSession(@Path("id") id: String): SessionResponse

    @POST("v1/sessions/{id}/activate")
    suspend fun activateSession(@Path("id") id: String): SessionResponse

    @POST("v1/sessions/{id}/reconnect")
    suspend fun reconnectSession(@Path("id") id: String): SessionResponse

    @POST("v1/sessions/{id}/terminate")
    suspend fun terminateSession(@Path("id") id: String): SessionResponse

    @POST("v1/sessions/{id}/telemetry")
    suspend fun sessionTelemetry(@Path("id") id: String, @Body body: TelemetryRequest): SessionResponse

    @GET("v1/ice-servers")
    suspend fun iceServers(): IceServersResponse

    @POST("v1/backups")
    suspend fun createBackup(): BackupResponse

    @POST("v1/backups/{id}/files")
    suspend fun createBackupFile(
        @Path("id") backupId: String,
        @Body body: CreateBackupFileRequest
    ): BackupFileResponse

    @PUT("v1/backups/{id}/files/{fileId}/chunk")
    suspend fun uploadChunk(
        @Path("id") backupId: String,
        @Path("fileId") fileId: String,
        @Query("offset") offset: Long,
        @Header("Content-Type") contentType: String = "application/octet-stream",
        @Body body: RequestBody
    ): BackupFileResponse

    @POST("v1/backups/{id}/files/{fileId}/complete")
    suspend fun completeBackupFile(
        @Path("id") backupId: String,
        @Path("fileId") fileId: String
    ): BackupFileResponse

    @POST("v1/backups/{id}/cancel")
    suspend fun cancelBackup(@Path("id") backupId: String): BackupResponse
}

class ApiException(
    val statusCode: Int,
    val code: String,
    override val message: String
) : java.io.IOException(message)
