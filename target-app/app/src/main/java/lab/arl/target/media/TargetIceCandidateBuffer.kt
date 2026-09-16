package lab.arl.target.media

/**
 * Manages remote ICE candidate buffering for Target.
 * Candidates arriving before PeerConnection or before remote SDP is applied are queued.
 * They are flushed in FIFO order only after remote SDP is successfully set.
 */
class TargetIceCandidateBuffer {
    var remoteDescriptionSet: Boolean = false
        private set

    private val queued = mutableListOf<CandidateInfo>()

    val queuedCount: Int get() = queued.size

    data class CandidateInfo(
        val sdpMid: String,
        val sdpMLineIndex: Int,
        val sdp: String
    )

    fun onPeerConnectionCreated() {
        // Explicitly do NOT flush here! Candidates remain buffered until remoteDescription is set.
    }

    fun addCandidate(
        sdpMid: String,
        sdpMLineIndex: Int,
        sdp: String,
        hasPeerConnection: Boolean,
        onApply: (CandidateInfo) -> Unit
    ): Boolean {
        val candidate = CandidateInfo(sdpMid, sdpMLineIndex, sdp)
        return if (!hasPeerConnection || !remoteDescriptionSet) {
            queued.add(candidate)
            false // buffered
        } else {
            onApply(candidate)
            true // applied immediately
        }
    }

    fun onRemoteDescriptionSet(onFlush: (List<CandidateInfo>) -> Unit) {
        remoteDescriptionSet = true
        if (queued.isNotEmpty()) {
            val toFlush = queued.toList()
            queued.clear()
            onFlush(toFlush)
        }
    }

    fun close() {
        remoteDescriptionSet = false
        queued.clear()
    }
}
