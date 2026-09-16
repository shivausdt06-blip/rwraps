package lab.arl.admin.network

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

enum class WsState { DISCONNECTED, CONNECTING, CONNECTED, FAILED }

class AdminSocket(private val http: OkHttpClient, private val scope: CoroutineScope) {
    private var socket: WebSocket? = null
    private var beat: Job? = null
    private val closing = AtomicBoolean(false)
    private val _state = MutableStateFlow(WsState.DISCONNECTED)
    val state: StateFlow<WsState> = _state
    private val _messages = MutableSharedFlow<WsEnvelopeDto>(extraBufferCapacity = 64)
    val messages: SharedFlow<WsEnvelopeDto> = _messages

    fun connect(baseHttp: String, accessToken: String) {
        disconnect()
        closing.set(false)
        _state.value = WsState.CONNECTING
        val url = toWs(baseHttp) + "/v1/ws"
        socket = http.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                val env = WsEnvelopeDto(
                    type = "auth",
                    id = UUID.randomUUID().toString(),
                    payload = buildJsonObject {
                        put("role", "admin")
                        put("accessToken", accessToken)
                    }
                )
                webSocket.send(appJson.encodeToString(WsEnvelopeDto.serializer(), env))
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val parsed = runCatching { appJson.decodeFromString(WsEnvelopeDto.serializer(), text) }.getOrNull()
                    ?: return
                if (parsed.type == "auth.ok") {
                    _state.value = WsState.CONNECTED
                    startBeat(webSocket)
                }
                scope.launch { _messages.emit(parsed) }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                _state.value = WsState.FAILED
                beat?.cancel()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                beat?.cancel()
                _state.value = if (closing.get()) WsState.DISCONNECTED else WsState.FAILED
            }
        })
    }

    fun send(type: String, payload: JsonObject) {
        val env = WsEnvelopeDto(type = type, payload = payload, id = UUID.randomUUID().toString())
        socket?.send(appJson.encodeToString(WsEnvelopeDto.serializer(), env))
    }

    fun disconnect() {
        closing.set(true)
        beat?.cancel()
        socket?.close(1000, "bye")
        socket = null
        _state.value = WsState.DISCONNECTED
    }

    private fun startBeat(ws: WebSocket) {
        beat?.cancel()
        beat = scope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(20_000)
                val env = WsEnvelopeDto(type = "heartbeat", payload = JsonObject(emptyMap()))
                ws.send(appJson.encodeToString(WsEnvelopeDto.serializer(), env))
            }
        }
    }

    companion object {
        fun toWs(http: String): String {
            val t = http.trimEnd('/')
            return when {
                t.startsWith("https://") -> "wss://" + t.removePrefix("https://")
                t.startsWith("http://") -> "ws://" + t.removePrefix("http://")
                else -> t
            }
        }
    }
}
