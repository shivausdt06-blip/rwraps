package lab.arl.target.network

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test

class WebSocketHandshakeInterceptorTest {
    private lateinit var server: MockWebServer

    @Before
    fun setup() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun errorInterceptorDoesNotFailWebsocketUpgrade() {
        val opened = CountDownLatch(1)
        server.enqueue(
            MockResponse().withWebSocketUpgrade(
                object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        opened.countDown()
                    }
                }
            )
        )
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val builder = chain.request().newBuilder().header("Accept", "application/json")
                chain.proceed(builder.build())
            }
            .addInterceptor(ErrorBodyInterceptor())
            .build()
        val wsUrl = server.url("/v1/ws").toString().replaceFirst("http", "ws")
        val done = CountDownLatch(1)
        var failure: Throwable? = null
        client.newWebSocket(
            Request.Builder().url(wsUrl).build(),
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    done.countDown()
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    failure = t
                    done.countDown()
                }
            }
        )
        assertTrue(done.await(5, TimeUnit.SECONDS), "websocket handshake timed out")
        assertNull(failure, "upgrade must not be treated as an HTTP error: $failure")
        assertTrue(opened.await(5, TimeUnit.SECONDS))
    }

    @Test
    fun httpUnauthorizedStillReturnedForAuthenticator() {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":{"code":"UNAUTHORIZED","message":"Authentication is required."}}"""))
        val client = OkHttpClient.Builder().addInterceptor(ErrorBodyInterceptor()).build()
        val response = client.newCall(Request.Builder().url(server.url("/v1/devices/me")).build()).execute()
        assertEquals(401, response.code)
        response.close()
    }
}
