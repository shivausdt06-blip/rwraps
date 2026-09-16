package lab.arl.target.network

import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking
import lab.arl.target.auth.InMemoryTokenStore
import lab.arl.target.auth.StoredCredentials
import lab.arl.target.domain.DeviceCapabilities
import lab.arl.target.domain.DeviceProfile
import lab.arl.target.pairing.PairingRepository
import lab.arl.target.session.SessionRepository
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test

class TargetApiIntegrationTest {
    private lateinit var server: MockWebServer
    private lateinit var api: TargetApi

    @Before
    fun setup() {
        server = MockWebServer()
        server.start()
        val client = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .build()
        api = createApi(server.url("/").toString(), client)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun claimAndConfirmPersistTokens() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """{"pairingSessionId":"ps1","claimToken":"claim-token-value-12345","expiresAt":"2026-01-01T00:00:00.000Z"}"""
            )
        )
        server.enqueue(
            MockResponse().setBody(
                """
                {
                  "device": {
                    "id":"11111111-1111-1111-1111-111111111111",
                    "name":"Pixel",
                    "enrollmentState":"ACTIVE",
                    "authorizationState":"GRANTED",
                    "platform":"android",
                    "androidVersion":"14",
                    "manufacturer":"Google",
                    "model":"Pixel",
                    "sdkInt":34,
                    "lastSeenAt":null,
                    "connectionState":"ONLINE",
                    "capabilities":{"screenCapture":true,"fileBackup":true,"remoteInput":false},
                    "createdAt":"2026-01-01T00:00:00.000Z",
                    "updatedAt":"2026-01-01T00:00:00.000Z"
                  },
                  "tokens":{"accessToken":"access-aaa","refreshToken":"refresh-bbb","expiresIn":900},
                  "enrollment":{"id":"enroll-1","status":"ACTIVE"}
                }
                """.trimIndent()
            )
        )
        val store = InMemoryTokenStore()
        val repo = PairingRepository(api, store)
        val profile = DeviceProfile(
            name = "Pixel",
            androidVersion = "14",
            manufacturer = "Google",
            model = "Pixel",
            sdkInt = 34,
            capabilities = DeviceCapabilities(true, true, false)
        )
        val claim = repo.claim("K7M2Q9XA", profile)
        assertEquals("ps1", claim.pairingSessionId)
        val confirmed = repo.confirm(claim.claimToken)
        assertEquals("ACTIVE", confirmed.second.status)
        assertEquals("access-aaa", store.load()?.accessToken)
        val claimReq = server.takeRequest()
        assertEquals("/v1/pairing/claim", claimReq.path)
        assertEquals("POST", claimReq.method)
    }

    @Test
    fun mapsApiErrorBody() = runBlocking {
        val client = createOkHttp({ null }, okhttp3.Authenticator.NONE, false)
        val errorApi = createApi(server.url("/").toString(), client)
        server.enqueue(
            MockResponse().setResponseCode(410).setBody("""{"error":{"code":"PAIRING_EXPIRED","message":"This pairing session is no longer valid."}}""")
        )
        try {
            errorApi.claimPairing(
                PairingClaimRequest("AAAAAAAA", DeviceInfoDto(name = "X"))
            )
            throw AssertionError("expected ApiException")
        } catch (err: Throwable) {
            val apiErr = generateSequence(err) { it.cause }.filterIsInstance<ApiException>().firstOrNull()
                ?: err as? ApiException
            assertEquals(true, apiErr != null, "expected ApiException, got ${err::class.qualifiedName}: ${err.message}")
            assertEquals(410, apiErr!!.statusCode)
            assertEquals("PAIRING_EXPIRED", apiErr.code)
        }
    }

    @Test
    fun sessionAuthenticatePath() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """
                {"session":{"id":"sess-1","deviceId":"dev-1","adminId":"adm-1","status":"AUTHENTICATED","startedAt":null,"endedAt":null,"timeoutAt":null,"reconnectCount":0,"quality":null,"createdAt":"2026-01-01T00:00:00.000Z","updatedAt":"2026-01-01T00:00:00.000Z"}}
                """.trimIndent()
            )
        )
        val repo = SessionRepository(api)
        val session = repo.authenticate("sess-1")
        assertEquals("AUTHENTICATED", session.status)
        assertEquals("/v1/sessions/sess-1/authenticate", server.takeRequest().path)
    }

    @Test
    fun backupInitUsesChecksum() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """{"backup":{"id":"b1","deviceId":"d1","adminId":"a1","status":"CREATED"}}"""
            )
        )
        server.enqueue(
            MockResponse().setBody(
                """{"file":{"id":"f1","backupId":"b1","filename":"notes.txt","sizeBytes":"4","mimeType":"text/plain","checksumSha256":"abc","uploadState":"PENDING","storageKey":"b1/f1","bytesUploaded":"0"},"upload":{"chunkSize":1048576}}"""
            )
        )
        val backup = api.createBackup().backup
        assertEquals("b1", backup.id)
        val file = api.createBackupFile(
            backup.id,
            CreateBackupFileRequest("notes.txt", 4, "text/plain", "abcd")
        )
        assertEquals("f1", file.file.id)
        assertEquals(1048576, file.upload?.chunkSize)
    }

    @Test
    fun refreshStoresRotatedTokens() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"tokens":{"accessToken":"n-access","refreshToken":"n-refresh","expiresIn":900}}"""))
        val store = InMemoryTokenStore()
        store.save(
            StoredCredentials(
                deviceId = "dev",
                enrollmentId = "en",
                accessToken = "old",
                refreshToken = "old-refresh",
                accessExpiresAtEpochMs = 0L
            )
        )
        val tokens = api.refresh(RefreshRequest("old-refresh")).tokens
        store.save(StoredCredentials.from("dev", "en", tokens.toDomain()))
        assertEquals("n-access", store.load()?.accessToken)
    }
}
