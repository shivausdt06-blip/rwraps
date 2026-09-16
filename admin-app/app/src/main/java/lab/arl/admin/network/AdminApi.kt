package lab.arl.admin.network

import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query
import retrofit2.http.Streaming
import okhttp3.ResponseBody

interface AdminApi {
    @GET("health")
    suspend fun health(): HealthResponse

    @POST("v1/auth/login")
    suspend fun login(@Body body: LoginRequest): AuthResponse

    @POST("v1/auth/register")
    suspend fun register(@Body body: RegisterRequest): AuthResponse

    @POST("v1/auth/refresh")
    suspend fun refresh(@Body body: RefreshRequest): TokensResponse

    @POST("v1/auth/logout")
    suspend fun logout(@Body body: LogoutRequest = LogoutRequest())

    @GET("v1/auth/me")
    suspend fun me(): MeResponse

    @GET("v1/devices")
    suspend fun devices(): DevicesResponse

    @POST("v1/pairing-sessions")
    suspend fun createPairing(): PairingCreatedResponse

    @POST("v1/sessions")
    suspend fun createSession(@Body body: CreateSessionRequest): SessionResponse

    @GET("v1/sessions")
    suspend fun sessions(@Query("deviceId") deviceId: String? = null): SessionsResponse

    @POST("v1/sessions/{id}/activate")
    suspend fun activate(@Path("id") id: String): SessionResponse

    @POST("v1/sessions/{id}/mode")
    suspend fun setMode(@Path("id") id: String, @Body body: SetSessionModeRequest): SessionResponse

    @POST("v1/sessions/{id}/reconnect")
    suspend fun reconnect(@Path("id") id: String): SessionResponse

    @POST("v1/sessions/{id}/terminate")
    suspend fun terminate(@Path("id") id: String): SessionResponse

    @GET("v1/ice-servers")
    suspend fun iceServers(): IceServersResponse

    @GET("v1/backups")
    suspend fun backups(@Query("deviceId") deviceId: String? = null): BackupsResponse

    @GET("v1/backups/{id}")
    suspend fun backup(@Path("id") id: String): BackupResponse

    @POST("v1/backups")
    suspend fun createBackup(@Body body: CreateSessionRequest): BackupResponse

    @Streaming
    @GET("v1/backups/{id}/files/{fileId}/chunk")
    suspend fun downloadChunk(
        @Path("id") id: String,
        @Path("fileId") fileId: String,
        @Query("offset") offset: Long,
        @Query("length") length: Int
    ): ResponseBody

    @POST("v1/backups/{id}/files/{fileId}/ingest")
    suspend fun ingestFile(
        @Path("id") id: String,
        @Path("fileId") fileId: String,
        @Body body: IngestFileRequest
    ): BackupFileResponse

    @POST("v1/backups/{id}/cancel")
    suspend fun cancelBackup(@Path("id") id: String): BackupResponse

    @DELETE("v1/backups/{id}")
    suspend fun deleteBackup(@Path("id") id: String)
}

class ApiException(
    val statusCode: Int,
    val code: String,
    override val message: String
) : java.io.IOException(message)
