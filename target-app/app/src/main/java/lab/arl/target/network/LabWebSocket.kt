package lab.arl.target.network

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
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

enum class WsConnectionState { DISCONNECTED, CONNECTING, AUTHENTICATING, CONNECTED, FAILED }

class LabWebSocket(
    private val http: OkHttpClient,
    private val scope: CoroutineScope
) {
    private var socket: WebSocket? = null
    private var heartbeatJob: Job? = null
    private val intentionalClose = AtomicBoolean(false)

    private val _state = MutableStateFlow(WsConnectionState.DISCONNECTED)
    val state: StateFlow<WsConnectionState> = _state

    private val _messages = MutableSharedFlow<WsEnvelopeDto>(extraBufferCapacity = 32)
    val messages: SharedFlow<WsEnvelopeDto> = _messages

    fun connect(baseHttpUrl: String, accessToken: String) {
        disconnect()
        intentionalClose.set(false)
        _state.value = WsConnectionState.CONNECTING
        val wsUrl = toWsUrl(baseHttpUrl) + "/v1/ws"
        val request = Request.Builder().url(wsUrl).build()
        socket = http.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                _state.value = WsConnectionState.AUTHENTICATING
                val envelope = WsEnvelopeDto(
                    v = 1,
                    id = UUID.randomUUID().toString(),
                    type = "auth",
                    payload = buildJsonObject {
                        put("role", "device")
                        put("accessToken", accessToken)
                    }
                )
                webSocket.send(appJson.encodeToString(WsEnvelopeDto.serializer(), envelope))
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val parsed = runCatching { appJson.decodeFromString(WsEnvelopeDto.serializer(), text) }.getOrNull()
                    ?: return
                if (parsed.type == "auth.ok") {
                    _state.value = WsConnectionState.CONNECTED
                    startHeartbeat(webSocket)
                }
                scope.launch { _messages.emit(parsed) }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                _state.value = WsConnectionState.FAILED
                stopHeartbeat()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                stopHeartbeat()
                if (!intentionalClose.get()) {
                    _state.value = WsConnectionState.FAILED
                } else {
                    _state.value = WsConnectionState.DISCONNECTED
                }
            }
        })
    }

    fun send(type: String, payload: JsonElement? = null, id: String? = UUID.randomUUID().toString()) {
        val envelope = WsEnvelopeDto(v = 1, id = id, type = type, payload = payload)
        socket?.send(appJson.encodeToString(WsEnvelopeDto.serializer(), envelope))
    }

    fun sendJson(type: String, payload: JsonObject) = send(type, payload)

    fun disconnect() {
        intentionalClose.set(true)
        stopHeartbeat()
        socket?.close(1000, "client close")
        socket = null
        _state.value = WsConnectionState.DISCONNECTED
    }

    private fun startHeartbeat(webSocket: WebSocket) {
        stopHeartbeat()
        heartbeatJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(20_000)
                val envelope = WsEnvelopeDto(v = 1, type = "heartbeat", payload = JsonObject(emptyMap()))
                webSocket.send(appJson.encodeToString(WsEnvelopeDto.serializer(), envelope))
            }
        }
    }

    private fun stopHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = null
    }

    companion object {
        fun toWsUrl(httpUrl: String): String {
            val trimmed = httpUrl.trimEnd('/')
            return when {
                trimmed.startsWith("https://") -> "wss://" + trimmed.removePrefix("https://")
                trimmed.startsWith("http://") -> "ws://" + trimmed.removePrefix("http://")
                else -> trimmed
            }
        }
    }
}

fun jsonString(value: String) = JsonPrimitive(value)
