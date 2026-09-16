package lab.arl.admin.network

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.webrtc.IceCandidate

object SignalingPayloads {
    fun offer(sessionId: String, sdp: String) = buildJsonObject {
        put("sessionId", JsonPrimitive(sessionId))
        put("sdp", JsonPrimitive(sdp))
    }

    fun answer(sessionId: String, sdp: String) = offer(sessionId, sdp)

    fun ice(sessionId: String, sdpMid: String?, sdpMLineIndex: Int, candidate: String) = buildJsonObject {
        put("sessionId", JsonPrimitive(sessionId))
        put(
            "candidate",
            buildJsonObject {
                put("sdpMid", JsonPrimitive(sdpMid ?: ""))
                put("sdpMLineIndex", JsonPrimitive(sdpMLineIndex))
                put("candidate", JsonPrimitive(candidate))
            }
        )
    }

    fun parseSessionId(payload: JsonObject?): String? =
        payload?.get("sessionId")?.jsonPrimitive?.contentOrNull

    fun parseSdp(payload: JsonObject?): String? =
        payload?.get("sdp")?.jsonPrimitive?.contentOrNull

    fun parseIce(payload: JsonObject?): IceCandidate? {
        val obj = payload?.get("candidate")?.jsonObject ?: return null
        val sdp = obj["candidate"]?.jsonPrimitive?.contentOrNull ?: return null
        val mid = obj["sdpMid"]?.jsonPrimitive?.contentOrNull
        val index = obj["sdpMLineIndex"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
        return IceCandidate(mid, index, sdp)
    }
}
