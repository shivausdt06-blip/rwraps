package lab.arl.admin.network

import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking
import lab.arl.admin.auth.InMemoryTokenStore
import lab.arl.admin.auth.StoredAdmin
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test

class AdminApiTest {
    private lateinit var server: MockWebServer
    private lateinit var api: AdminApi

    @Before
    fun setup() {
        server = MockWebServer()
        server.start()
        api = createApi(
            server.url("/").toString(),
            OkHttpClient.Builder().readTimeout(5, TimeUnit.SECONDS).build()
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun loginAndListDevices() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """{"admin":{"id":"a1","email":"op@lab.local","displayName":"Op","createdAt":"t","updatedAt":"t"},"tokens":{"accessToken":"tok","refreshToken":"ref","expiresIn":900}}"""
            )
        )
        server.enqueue(
            MockResponse().setBody(
                """{"devices":[{"id":"d1","name":"Target 01","enrollmentState":"ACTIVE","authorizationState":"GRANTED","platform":"android","androidVersion":"15","manufacturer":"Google","model":"Pixel","sdkInt":35,"lastSeenAt":null,"connectionState":"ONLINE","capabilities":{"screenCapture":true,"fileBackup":true,"remoteInput":false},"createdAt":"t","updatedAt":"t"}]}"""
            )
        )
        val auth = api.login(LoginRequest("op@lab.local", "ChangeMe_LabOnly_12"))
        assertEquals("tok", auth.tokens.accessToken)
        val devices = api.devices().devices
        assertEquals(1, devices.size)
        assertEquals("ONLINE", devices[0].connectionState)
        assertEquals("/v1/auth/login", server.takeRequest().path)
        assertEquals("/v1/devices", server.takeRequest().path)
    }

    @Test
    fun createSessionBody() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """{"session":{"id":"s1","deviceId":"d1","adminId":"a1","status":"CREATED","reconnectCount":0}}"""
            )
        )
        val session = api.createSession(CreateSessionRequest("d1")).session
        assertEquals("CREATED", session.status)
        store(InMemoryTokenStore())
    }

    private fun store(s: InMemoryTokenStore) {
        s.save(StoredAdmin("a", "e", "t", "r"))
        assertEquals("t", s.load()?.accessToken)
    }
}
