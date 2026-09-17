package lab.arl.target.media

import android.content.Context
import lab.arl.target.domain.IceServer
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStreamTrack
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

class WebRtcClient(
    context: Context
) {
    val eglBase: EglBase = runCatching {
        EglBase.create(null, EglBase.CONFIG_RECORDABLE)
    }.getOrElse { EglBase.create() }
    private val factory: PeerConnectionFactory
    private var peerConnection: PeerConnection? = null
    private var videoSource: VideoSource? = null
    private var videoTrack: VideoTrack? = null
    private val pendingIce = CopyOnWriteArrayList<IceCandidate>()
    private var remoteDescriptionSet = false

    fun isRemoteDescriptionSet(): Boolean = remoteDescriptionSet
    fun pendingIceCount(): Int = pendingIce.size

    var onIceCandidate: ((IceCandidate) -> Unit)? = null
    var onConnectionChange: ((PeerConnection.PeerConnectionState) -> Unit)? = null
    var onStats: ((rttMs: Double, packetLoss: Double, bitrateKbps: Double) -> Unit)? = null

    init {
        val options = PeerConnectionFactory.InitializationOptions.builder(context.applicationContext)
            .setEnableInternalTracer(false)
            .createInitializationOptions()
        PeerConnectionFactory.initialize(options)
        runCatching {
            org.webrtc.Logging.enableLogToDebugOutput(org.webrtc.Logging.Severity.LS_INFO)
        }
        val encoder = DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, false)
        val decoder = DefaultVideoDecoderFactory(eglBase.eglBaseContext)
        factory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(encoder)
            .setVideoDecoderFactory(decoder)
            .createPeerConnectionFactory()
        WebrtcDiag.log(
            "codecs_supported",
            extra = "encoders=${encoder.supportedCodecs.joinToString { it.name }} decoders=${decoder.supportedCodecs.joinToString { it.name }}"
        )
    }

    fun getOrCreateVideoSource(): VideoSource {
        val existing = videoSource
        if (existing != null) return existing
        val source = factory.createVideoSource(true)
        videoSource = source
        WebrtcDiag.log("video_source_created")
        return source
    }

    fun createVideoSource(): VideoSource = getOrCreateVideoSource()

    fun getOrCreateVideoTrack(): VideoTrack {
        val existing = videoTrack
        if (existing != null) return existing
        val source = getOrCreateVideoSource()
        val track = factory.createVideoTrack("arl_screen", source)
        track.setEnabled(true)
        videoTrack = track
        WebrtcDiag.log("video_track_created", extra = "enabled=${track.enabled()} state=${track.state()}")
        return track
    }

    private fun startOutboundStatsPolling(pc: PeerConnection) {
        fun poll(delayMs: Long, attempt: Int) {
            if (attempt > 15 || peerConnection !== pc) return
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                if (peerConnection !== pc) return@postDelayed
                pc.getStats { report ->
                    var bytes = 0L
                    var packets = 0L
                    var framesEncoded = 0L
                    var framesSent = 0L
                    var codecId = ""
                    var limitation = ""
                    var encoderImpl = ""
                    for (stat in report.statsMap.values) {
                        if (stat.type == "outbound-rtp") {
                            val b = stat.members["bytesSent"] as? Number
                            val p = stat.members["packetsSent"] as? Number
                            val fe = stat.members["framesEncoded"] as? Number
                            val fs = stat.members["framesSent"] as? Number
                            val q = stat.members["qualityLimitationReason"] as? String
                            val c = stat.members["codecId"] as? String
                            val enc = stat.members["encoderImplementation"] as? String
                            if (b != null) bytes += b.toLong()
                            if (p != null) packets += p.toLong()
                            if (fe != null) framesEncoded += fe.toLong()
                            if (fs != null) framesSent += fs.toLong()
                            if (q != null) limitation = q
                            if (c != null) codecId = c
                            if (enc != null) encoderImpl = enc
                        }
                    }
                    WebrtcDiag.log(
                        "rtp_outbound",
                        extra = "bytes=$bytes packets=$packets framesEncoded=$framesEncoded framesSent=$framesSent encoder=$encoderImpl codec=$codecId limit=$limitation attempt=$attempt"
                    )
                }
                poll(2000, attempt + 1)
            }, delayMs)
        }
        poll(1000, 1)
    }

    fun ensurePeerConnection(iceServers: List<IceServer>): PeerConnection {
        val existing = peerConnection
        if (existing != null) {
            val state = existing.connectionState()
            if (state != PeerConnection.PeerConnectionState.FAILED &&
                state != PeerConnection.PeerConnectionState.CLOSED &&
                state != PeerConnection.PeerConnectionState.DISCONNECTED
            ) {
                val track = getOrCreateVideoTrack()
                existing.senders.firstOrNull { it.track()?.kind() == "video" }?.let { sender ->
                    if (sender.track() !== track) {
                        sender.setTrack(track, false)
                    }
                }
                return existing
            }
            WebrtcDiag.log("pc_recycle", extra = state.name)
            WebrtcDiag.closeRequested("pc_recycle_${state.name}")
            closePeerConnectionOnly()
            peerConnection = null
            videoTrack?.dispose()
            videoTrack = null
            remoteDescriptionSet = false
            pendingIce.clear()
        }
        WebrtcDiag.log("ice_config", extra = WebrtcDiag.iceServerSummary(iceServers.map { it.urls }))
        val rtcIce = iceServers.map { server ->
            val builder = PeerConnection.IceServer.builder(server.urls)
            if (!server.username.isNullOrBlank() && !server.credential.isNullOrBlank()) {
                builder.setUsername(server.username).setPassword(server.credential)
            }
            builder.createIceServer()
        }
        val config = PeerConnection.RTCConfiguration(rtcIce).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
            tcpCandidatePolicy = PeerConnection.TcpCandidatePolicy.ENABLED
        }
        val observer = object : PeerConnection.Observer {
            override fun onSignalingChange(state: PeerConnection.SignalingState?) {
                WebrtcDiag.log("signaling_state", extra = state?.name)
            }
            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
                WebrtcDiag.log("ice_state", extra = state?.name)
                if (state == PeerConnection.IceConnectionState.CONNECTED ||
                    state == PeerConnection.IceConnectionState.COMPLETED
                ) {
                    WebrtcDiag.log("ice_connected")
                    peerConnection?.let { startOutboundStatsPolling(it) }
                }
            }
            override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {
                WebrtcDiag.log("ice_gathering", extra = state?.name)
            }
            override fun onIceCandidate(candidate: IceCandidate?) {
                if (candidate != null) {
                    val kind = WebrtcDiag.iceKind(candidate.sdp)
                    WebrtcDiag.log(
                        "ice_generated",
                        extra = "mid=${candidate.sdpMid} mline=${candidate.sdpMLineIndex} $kind"
                    )
                    if (kind.contains("typ=relay")) {
                        WebrtcDiag.relayCandidateFound(if (kind.contains("proto=tcp")) "tcp" else "udp")
                    }
                    onIceCandidate?.invoke(candidate)
                }
            }
            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) = Unit
            override fun onAddStream(stream: org.webrtc.MediaStream?) = Unit
            override fun onRemoveStream(stream: org.webrtc.MediaStream?) = Unit
            override fun onDataChannel(channel: org.webrtc.DataChannel?) = Unit
            override fun onRenegotiationNeeded() = Unit
            override fun onAddTrack(receiver: org.webrtc.RtpReceiver?, streams: Array<out org.webrtc.MediaStream>?) = Unit
            override fun onConnectionChange(newState: PeerConnection.PeerConnectionState?) {
                if (newState != null) {
                    WebrtcDiag.log("connection_state", extra = newState.name)
                    WebrtcDiag.log("pc_state", extra = newState.name)
                    if (newState == PeerConnection.PeerConnectionState.CLOSED) {
                        WebrtcDiag.log("closed_callback")
                    }
                    onConnectionChange?.invoke(newState)
                }
            }
        }
        val pc = factory.createPeerConnection(config, observer)
            ?: error("Unable to create PeerConnection")
        peerConnection = pc
        WebrtcDiag.log("peer_connection_created")
        WebrtcDiag.log("pc_created")
        val track = getOrCreateVideoTrack()
        val sender = pc.addTrack(track, listOf("arl_screen_stream"))
        WebrtcDiag.log("video_sender_attached", extra = "id=${sender.id()} senders=${pc.senders.size}")
        pc.transceivers
            .filter { it.mediaType == MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO }
            .forEach {
                it.direction = RtpTransceiver.RtpTransceiverDirection.SEND_ONLY
                WebrtcDiag.log("video_transceiver", extra = "dir=${it.direction} mid=${it.mid}")
            }
        pc.addTransceiver(
            MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO,
            RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.INACTIVE)
        )
        return pc
    }

    suspend fun setRemoteOfferAndAnswer(sdp: String): SessionDescription {
        val pc = peerConnection ?: error("PeerConnection missing")
        WebrtcDiag.log("sdp_offer_received", extra = "\n$sdp")
        val offer = SessionDescription(SessionDescription.Type.OFFER, sdp)
        awaitSdp { pc.setRemoteDescription(it, offer) }
        remoteDescriptionSet = true
        flushIce(pc)
        pc.transceivers
            .filter { it.mediaType == MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO }
            .forEach {
                it.direction = RtpTransceiver.RtpTransceiverDirection.SEND_ONLY
                WebrtcDiag.log("video_transceiver_direction_ensured", extra = "dir=${it.direction} currentDir=${it.currentDirection} mid=${it.mid}")
            }
        val constraints = MediaConstraints()
        WebrtcDiag.log("offer_received")
        val answer = awaitCreate { pc.createAnswer(it, constraints) }
        val preferredAnswer = SessionDescription(answer.type, preferCodec(answer.description, "VP8"))
        awaitSdp { pc.setLocalDescription(it, preferredAnswer) }
        WebrtcDiag.log("answer_created", extra = "type=${preferredAnswer.type.canonicalForm()}")
        WebrtcDiag.log("answer_sent", extra = "type=${preferredAnswer.type.canonicalForm()}")
        WebrtcDiag.log("sdp_answer_sent", extra = "\n${preferredAnswer.description}")
        pc.transceivers.forEach {
            WebrtcDiag.log("transceiver_state", extra = "mid=${it.mid} type=${it.mediaType} dir=${it.direction} currentDir=${it.currentDirection}")
        }
        return preferredAnswer
    }

    suspend fun createOffer(): SessionDescription {
        val pc = peerConnection ?: error("PeerConnection missing")
        val constraints = MediaConstraints()
        val offer = awaitCreate { pc.createOffer(it, constraints) }
        val preferredOffer = SessionDescription(offer.type, preferCodec(offer.description, "VP8"))
        awaitSdp { pc.setLocalDescription(it, preferredOffer) }
        WebrtcDiag.log("local_offer_set", extra = "type=${preferredOffer.type.canonicalForm()}")
        WebrtcDiag.log("sdp_offer_sent", extra = "\n${preferredOffer.description}")
        return preferredOffer
    }

    suspend fun setRemoteAnswer(sdp: String) {
        val pc = peerConnection ?: error("PeerConnection missing")
        val answer = SessionDescription(SessionDescription.Type.ANSWER, sdp)
        awaitSdp { pc.setRemoteDescription(it, answer) }
        remoteDescriptionSet = true
        flushIce(pc)
        WebrtcDiag.log("remote_answer_set")
    }

    fun addIce(candidate: IceCandidate) {
        val pc = peerConnection
        val kind = WebrtcDiag.iceKind(candidate.sdp)
        if (pc == null || !remoteDescriptionSet) {
            pendingIce += candidate
            WebrtcDiag.log("ice_buffered", extra = "queued=${pendingIce.size} remoteDesc=$remoteDescriptionSet $kind")
        } else {
            WebrtcDiag.log("ice_applied", extra = "mid=${candidate.sdpMid} mline=${candidate.sdpMLineIndex} $kind")
            pc.addIceCandidate(candidate)
        }
    }

    private fun flushIce(pc: PeerConnection) {
        if (pendingIce.isEmpty()) return
        WebrtcDiag.log("ice_flush", extra = "count=${pendingIce.size}")
        pendingIce.forEach { pc.addIceCandidate(it) }
        pendingIce.clear()
    }

    fun hasLocalOffer(): Boolean =
        peerConnection?.signalingState() == PeerConnection.SignalingState.HAVE_LOCAL_OFFER

    fun close(reason: String = "unspecified") {
        WebrtcDiag.closeRequested(reason)
        WebrtcDiag.log("pc_close", extra = "reason=$reason")
        remoteDescriptionSet = false
        pendingIce.clear()
        videoTrack?.setEnabled(false)
        videoTrack?.dispose()
        videoTrack = null
        videoSource?.dispose()
        videoSource = null
        closePeerConnectionOnly()
    }

    fun release() {
        close("release")
        WebrtcDiag.closeRequested("factory_dispose")
        factory.dispose()
        eglBase.release()
    }

    private fun closePeerConnectionOnly() {
        if (peerConnection == null) return
        peerConnection?.close()
        peerConnection?.dispose()
        peerConnection = null
    }

    private suspend fun awaitSdp(block: (SdpObserver) -> Unit) = suspendCancellableCoroutine { cont ->
        block(object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription?) = Unit
            override fun onSetSuccess() {
                if (cont.isActive) cont.resume(Unit)
            }
            override fun onCreateFailure(error: String?) {
                if (cont.isActive) cont.resumeWithException(IllegalStateException(error ?: "SDP create failed"))
            }
            override fun onSetFailure(error: String?) {
                if (cont.isActive) cont.resumeWithException(IllegalStateException(error ?: "SDP set failed"))
            }
        })
    }

    private suspend fun awaitCreate(block: (SdpObserver) -> Unit): SessionDescription =
        suspendCancellableCoroutine { cont ->
            block(object : SdpObserver {
                override fun onCreateSuccess(sdp: SessionDescription?) {
                    if (sdp != null && cont.isActive) cont.resume(sdp)
                    else if (cont.isActive) cont.resumeWithException(IllegalStateException("Empty SDP"))
                }
                override fun onSetSuccess() = Unit
                override fun onCreateFailure(error: String?) {
                    if (cont.isActive) cont.resumeWithException(IllegalStateException(error ?: "SDP create failed"))
                }
                override fun onSetFailure(error: String?) {
                    if (cont.isActive) cont.resumeWithException(IllegalStateException(error ?: "SDP set failed"))
                }
            })
        }

    companion object {
        fun preferCodec(sdp: String, codecName: String): String {
            val lines = sdp.split("\r\n", "\n").toMutableList()
            val mVideoIndex = lines.indexOfFirst { it.startsWith("m=video ") }
            if (mVideoIndex == -1) return sdp

            val rtpmapRegex = Regex("""^a=rtpmap:(\d+)\s+$codecName/""", RegexOption.IGNORE_CASE)
            val targetPt = lines.mapNotNull { line ->
                rtpmapRegex.find(line)?.groupValues?.get(1)
            }.firstOrNull() ?: return sdp

            val rtxRegex = Regex("""^a=fmtp:(\d+)\s+apt=$targetPt""")
            val rtxPt = lines.mapNotNull { line ->
                rtxRegex.find(line)?.groupValues?.get(1)
            }.firstOrNull()

            val mVideoLine = lines[mVideoIndex]
            val parts = mVideoLine.split(" ").filter { it.isNotBlank() }.toMutableList()
            if (parts.size < 4) return sdp

            val prefix = parts.subList(0, 3)
            val payloadTypes = parts.subList(3, parts.size)

            val prioritized = mutableListOf<String>()
            prioritized.add(targetPt)
            if (rtxPt != null) prioritized.add(rtxPt)

            val remaining = payloadTypes.filter { it != targetPt && it != rtxPt }
            val newPayloadTypes = prioritized + remaining

            lines[mVideoIndex] = (prefix + newPayloadTypes).joinToString(" ")
            val filtered = if (lines.lastOrNull() == "") lines.dropLast(1) else lines
            return filtered.joinToString("\r\n") + "\r\n"
        }
    }
}
