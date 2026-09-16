package lab.arl.target.network

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

    fun ice(sessionId: String, candidate: IceCandidate) = buildJsonObject {
        put("sessionId", JsonPrimitive(sessionId))
        put(
            "candidate",
            buildJsonObject {
                put("sdpMid", JsonPrimitive(candidate.sdpMid ?: ""))
                put("sdpMLineIndex", JsonPrimitive(candidate.sdpMLineIndex))
                put("candidate", JsonPrimitive(candidate.sdp))
            }
        )
    }

    fun sessionOnly(sessionId: String) = buildJsonObject {
        put("sessionId", JsonPrimitive(sessionId))
    }

    fun telemetry(sessionId: String, rttMs: Double, packetLoss: Double, bitrateKbps: Double) = buildJsonObject {
        put("sessionId", JsonPrimitive(sessionId))
        put("rttMs", JsonPrimitive(rttMs))
        put("packetLoss", JsonPrimitive(packetLoss))
        put("bitrateKbps", JsonPrimitive(bitrateKbps))
    }

    fun parseSessionId(payload: JsonObject?): String? =
        payload?.get("sessionId")?.jsonPrimitive?.contentOrNull

    fun parseSdp(payload: JsonObject?): String? =
        payload?.get("sdp")?.jsonPrimitive?.contentOrNull

    fun parseIce(payload: JsonObject?): IceCandidate? {
        val candidateEl = payload?.get("candidate") ?: return null
        val obj = candidateEl.jsonObject
        val sdp = obj["candidate"]?.jsonPrimitive?.contentOrNull ?: return null
        val mid = obj["sdpMid"]?.jsonPrimitive?.contentOrNull
        val index = obj["sdpMLineIndex"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
        return IceCandidate(mid, index, sdp)
    }
}
