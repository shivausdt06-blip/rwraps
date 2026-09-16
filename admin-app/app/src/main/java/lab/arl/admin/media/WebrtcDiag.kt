package lab.arl.admin.media

import android.util.Log

object WebrtcDiag {
    private const val TAG = "ARL-WebRTC"

    fun log(event: String, sessionId: String? = null, extra: String? = null) {
        Log.i(
            TAG,
            buildString {
                append("admin ")
                append(event)
                if (!sessionId.isNullOrBlank()) {
                    append(" session=")
                    append(sessionId.take(8))
                }
                if (!extra.isNullOrBlank()) {
                    append(' ')
                    append(extra)
                }
            }
        )
    }

    fun iceKind(candidateSdp: String): String {
        val typ = Regex("""\btyp (\w+)""").find(candidateSdp)?.groupValues?.getOrNull(1) ?: "unknown"
        val proto = if (Regex("""(?i)\btcp\b""").containsMatchIn(candidateSdp)) "tcp" else "udp"
        return "typ=$typ proto=$proto"
    }

    fun closeRequested(reason: String) {
        log("close_requested", extra = "reason=$reason")
    }

    fun relayCandidateFound(proto: String) {
        log("relay_candidate_found", extra = "proto=$proto")
    }

    fun iceServerSummary(urls: List<List<String>>): String {
        var stun = 0
        var turn = 0
        for (group in urls) {
            for (url in group) {
                when {
                    url.startsWith("turn", ignoreCase = true) -> turn++
                    url.startsWith("stun", ignoreCase = true) -> stun++
                }
            }
        }
        return "stun=$stun turn=$turn"
    }
}
