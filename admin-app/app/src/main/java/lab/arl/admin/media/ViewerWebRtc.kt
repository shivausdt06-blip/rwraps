package lab.arl.admin.media

import android.content.Context
import android.view.ViewGroup
import lab.arl.admin.domain.IceServer
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStreamTrack
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RendererCommon
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoTrack
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

class ViewerWebRtc(context: Context) {
    val egl: EglBase = EglBase.create()
    private val factory: PeerConnectionFactory
    private var pc: PeerConnection? = null
    private var renderer: SurfaceViewRenderer? = null
    private var rendererInitialized = false
    private var remoteTrack: VideoTrack? = null
    private val pendingIce = CopyOnWriteArrayList<IceCandidate>()
    private var remoteDescriptionSet = false
    var onIce: ((IceCandidate) -> Unit)? = null
    var onState: ((PeerConnection.PeerConnectionState) -> Unit)? = null
    var onTrack: (() -> Unit)? = null
    var onFirstFrame: (() -> Unit)? = null
    var onFrameResolution: ((Int, Int, Int) -> Unit)? = null
    var videoWidth: Int = 0
    var videoHeight: Int = 0
    var videoRotation: Int = 0

    init {
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context.applicationContext)
                .setEnableInternalTracer(false)
                .createInitializationOptions()
        )
        runCatching {
            org.webrtc.Logging.enableLogToDebugOutput(org.webrtc.Logging.Severity.LS_INFO)
        }
        val encoder = DefaultVideoEncoderFactory(egl.eglBaseContext, true, false)
        val decoder = DefaultVideoDecoderFactory(egl.eglBaseContext)
        factory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(encoder)
            .setVideoDecoderFactory(decoder)
            .createPeerConnectionFactory()
        WebrtcDiag.log(
            "codecs_supported",
            extra = "encoders=${encoder.supportedCodecs.joinToString { it.name }} decoders=${decoder.supportedCodecs.joinToString { it.name }}"
        )
    }

    private fun startInboundStatsPolling(peer: PeerConnection) {
        fun poll(delayMs: Long, attempt: Int) {
            if (attempt > 15 || pc !== peer) return
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                if (pc !== peer) return@postDelayed
                peer.getStats { report ->
                    var bytes = 0L
                    var packets = 0L
                    var framesDecoded = 0L
                    var framesReceived = 0L
                    var framesDropped = 0L
                    var decoderImpl = ""
                    var codecId = ""
                    for (stat in report.statsMap.values) {
                        if (stat.type == "inbound-rtp") {
                            val b = stat.members["bytesReceived"] as? Number
                            val p = stat.members["packetsReceived"] as? Number
                            val fd = stat.members["framesDecoded"] as? Number
                            val fr = stat.members["framesReceived"] as? Number
                            val dr = stat.members["framesDropped"] as? Number
                            val dec = stat.members["decoderImplementation"] as? String
                            val c = stat.members["codecId"] as? String
                            if (b != null) bytes += b.toLong()
                            if (p != null) packets += p.toLong()
                            if (fd != null) framesDecoded += fd.toLong()
                            if (fr != null) framesReceived += fr.toLong()
                            if (dr != null) framesDropped += dr.toLong()
                            if (dec != null) decoderImpl = dec
                            if (c != null) codecId = c
                        }
                    }
                    WebrtcDiag.log(
                        "rtp_inbound",
                        extra = "bytes=$bytes packets=$packets framesDecoded=$framesDecoded framesReceived=$framesReceived framesDropped=$framesDropped decoder=$decoderImpl codec=$codecId attempt=$attempt"
                    )
                }
                poll(2000, attempt + 1)
            }, delayMs)
        }
        poll(1000, 1)
    }

    fun attachRenderer(view: SurfaceViewRenderer) {
        if (renderer !== view) {
            remoteTrack?.let { track -> renderer?.let { track.removeSink(it) } }
            renderer = view
            rendererInitialized = false
        }
        if (!rendererInitialized) {
            try {
                view.init(
                    egl.eglBaseContext,
                    object : RendererCommon.RendererEvents {
                        override fun onFirstFrameRendered() {
                            WebrtcDiag.log("first_frame_received")
                            onFirstFrame?.invoke()
                        }

                        override fun onFrameResolutionChanged(videoWidth: Int, videoHeight: Int, rotation: Int) {
                            this@ViewerWebRtc.videoWidth = videoWidth
                            this@ViewerWebRtc.videoHeight = videoHeight
                            this@ViewerWebRtc.videoRotation = rotation
                            WebrtcDiag.log("frame_size", extra = "${videoWidth}x${videoHeight} rot=$rotation")
                            onFrameResolution?.invoke(videoWidth, videoHeight, rotation)
                        }
                    }
                )
                view.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)
                view.setEnableHardwareScaler(false)
                view.setMirror(false)
                view.setZOrderMediaOverlay(true)
                view.isClickable = false
                view.isFocusable = false
                view.isFocusableInTouchMode = false
                view.keepScreenOn = true
                rendererInitialized = true
                WebrtcDiag.log("renderer_initialized", extra = "${view.width}x${view.height}")
            } catch (err: RuntimeException) {
                WebrtcDiag.log("renderer_init_failed", extra = err.message)
            }
        }
        bindRemoteTrack("renderer_ready")
    }

    fun detachRenderer() {
        remoteTrack?.let { track -> renderer?.let { track.removeSink(it) } }
        renderer?.release()
        renderer = null
        rendererInitialized = false
        WebrtcDiag.log("renderer_release")
    }

    fun hasRemoteDescription(): Boolean = remoteDescriptionSet

    fun ensurePc(ice: List<IceServer>): PeerConnection {
        val existing = pc
        if (existing != null) {
            val state = existing.connectionState()
            if (state != PeerConnection.PeerConnectionState.FAILED &&
                state != PeerConnection.PeerConnectionState.CLOSED &&
                state != PeerConnection.PeerConnectionState.DISCONNECTED
            ) {
                return existing
            }
            WebrtcDiag.log("pc_recycle", extra = state.name)
            close("pc_recycle_${state.name}")
        }
        WebrtcDiag.log("ice_config", extra = WebrtcDiag.iceServerSummary(ice.map { it.urls }))
        val rtcIce = ice.map { s ->
            val b = PeerConnection.IceServer.builder(s.urls)
            if (!s.username.isNullOrBlank() && !s.credential.isNullOrBlank()) {
                b.setUsername(s.username).setPassword(s.credential)
            }
            b.createIceServer()
        }
        val cfg = PeerConnection.RTCConfiguration(rtcIce).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
            tcpCandidatePolicy = PeerConnection.TcpCandidatePolicy.ENABLED
        }
        val created = factory.createPeerConnection(cfg, object : PeerConnection.Observer {
            override fun onSignalingChange(state: PeerConnection.SignalingState?) {
                WebrtcDiag.log("signaling_state", extra = state?.name)
            }
            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
                WebrtcDiag.log("ice_state", extra = state?.name)
                if (state == PeerConnection.IceConnectionState.CONNECTED ||
                    state == PeerConnection.IceConnectionState.COMPLETED
                ) {
                    WebrtcDiag.log("ice_connected")
                    pc?.let { startInboundStatsPolling(it) }
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
                    onIce?.invoke(candidate)
                }
            }
            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) = Unit
            override fun onAddStream(stream: org.webrtc.MediaStream?) {
                stream?.videoTracks?.firstOrNull()?.let { attachIncomingTrack(it, "onAddStream") }
            }
            override fun onRemoveStream(stream: org.webrtc.MediaStream?) = Unit
            override fun onDataChannel(channel: org.webrtc.DataChannel?) = Unit
            override fun onRenegotiationNeeded() = Unit
            override fun onAddTrack(receiver: org.webrtc.RtpReceiver?, streams: Array<out org.webrtc.MediaStream>?) {
                val track = receiver?.track() as? VideoTrack ?: return
                attachIncomingTrack(track, "onAddTrack")
            }
            override fun onTrack(transceiver: RtpTransceiver?) {
                val track = transceiver?.receiver?.track() as? VideoTrack ?: return
                attachIncomingTrack(track, "onTrack")
            }
            override fun onConnectionChange(newState: PeerConnection.PeerConnectionState?) {
                if (newState != null) {
                    WebrtcDiag.log("connection_state", extra = newState.name)
                    WebrtcDiag.log("pc_state", extra = newState.name)
                    if (newState == PeerConnection.PeerConnectionState.CLOSED) {
                        WebrtcDiag.log("closed_callback")
                    }
                    onState?.invoke(newState)
                }
            }
        }) ?: error("PeerConnection failed")
        WebrtcDiag.log("peer_connection_created")
        WebrtcDiag.log("pc_created")
        created.addTransceiver(
            MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO,
            RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.RECV_ONLY)
        )
        WebrtcDiag.log("video_transceiver", extra = "dir=RECV_ONLY")
        created.addTransceiver(
            MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO,
            RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.INACTIVE)
        )
        pc = created
        return created
    }

    suspend fun createOffer(): SessionDescription {
        val peer = pc ?: error("no pc")
        val constraints = MediaConstraints()
        val offer = awaitCreate { peer.createOffer(it, constraints) }
        val preferredOffer = SessionDescription(offer.type, preferCodec(offer.description, "VP8"))
        awaitSet { peer.setLocalDescription(it, preferredOffer) }
        WebrtcDiag.log("offer_created", extra = "type=${preferredOffer.type.canonicalForm()}")
        WebrtcDiag.log("sdp_offer_sent", extra = "\n${preferredOffer.description}")
        peer.transceivers.forEach {
            WebrtcDiag.log("transceiver_state", extra = "mid=${it.mid} type=${it.mediaType} dir=${it.direction} currentDir=${it.currentDirection}")
        }
        return preferredOffer
    }

    suspend fun setRemoteAnswer(sdp: String) {
        val peer = pc ?: error("no pc")
        WebrtcDiag.log("sdp_answer_received", extra = "\n$sdp")
        awaitSet { peer.setRemoteDescription(it, SessionDescription(SessionDescription.Type.ANSWER, sdp)) }
        remoteDescriptionSet = true
        WebrtcDiag.log("answer_received")
        WebrtcDiag.log("remote_description_applied")
        peer.transceivers.forEach {
            WebrtcDiag.log("transceiver_state", extra = "mid=${it.mid} type=${it.mediaType} dir=${it.direction} currentDir=${it.currentDirection}")
        }
        flushIce(peer)
    }

    suspend fun setRemoteOfferAndAnswer(sdp: String): SessionDescription {
        val peer = pc ?: error("no pc")
        awaitSet { peer.setRemoteDescription(it, SessionDescription(SessionDescription.Type.OFFER, sdp)) }
        remoteDescriptionSet = true
        WebrtcDiag.log("remote_offer_set")
        flushIce(peer)
        val constraints = MediaConstraints()
        val answer = awaitCreate { peer.createAnswer(it, constraints) }
        awaitSet { peer.setLocalDescription(it, answer) }
        WebrtcDiag.log("local_answer_set", extra = "type=${answer.type.canonicalForm()}")
        return answer
    }

    fun addIce(c: IceCandidate) {
        val peer = pc
        if (peer == null || !remoteDescriptionSet) {
            pendingIce += c
            WebrtcDiag.log("ice_buffered", extra = "queued=${pendingIce.size} remoteDesc=$remoteDescriptionSet")
        } else {
            WebrtcDiag.log("ice_applied", extra = "mid=${c.sdpMid} mline=${c.sdpMLineIndex} ${WebrtcDiag.iceKind(c.sdp)}")
            peer.addIceCandidate(c)
        }
    }

    fun close(reason: String = "unspecified") {
        WebrtcDiag.closeRequested(reason)
        WebrtcDiag.log("pc_close", extra = "reason=$reason")
        remoteTrack?.let { track -> renderer?.let { track.removeSink(it) } }
        remoteTrack = null
        pendingIce.clear()
        remoteDescriptionSet = false
        pc?.close()
        pc?.dispose()
        pc = null
    }

    fun layoutParams() = ViewGroup.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.MATCH_PARENT
    )

    private fun attachIncomingTrack(track: VideoTrack, source: String) {
        if (remoteTrack === track) {
            bindRemoteTrack(source)
            return
        }
        remoteTrack?.let { existing -> renderer?.let { existing.removeSink(it) } }
        remoteTrack = track
        track.setEnabled(true)
        WebrtcDiag.log("remote_track", extra = "source=$source state=${track.state()} enabled=${track.enabled()}")
        WebrtcDiag.log("video_track_received", extra = "id=${track.id()}")
        bindRemoteTrack(source)
        onTrack?.invoke()
    }

    private fun bindRemoteTrack(reason: String) {
        val track = remoteTrack
        val view = renderer
        if (!ViewerNegotiation.shouldAttachRemoteTrack(view != null, track != null) || track == null || view == null) {
            WebrtcDiag.log("sink_deferred", extra = reason)
            return
        }
        runCatching { track.addSink(view) }
            .onFailure { WebrtcDiag.log("sink_attach_failed", extra = it.message) }
            .onSuccess { WebrtcDiag.log("sink_attached", extra = reason) }
    }

    private fun flushIce(peer: PeerConnection) {
        if (pendingIce.isEmpty()) return
        WebrtcDiag.log("ice_flush", extra = "count=${pendingIce.size}")
        pendingIce.forEach { peer.addIceCandidate(it) }
        pendingIce.clear()
    }

    private suspend fun awaitSet(block: (SdpObserver) -> Unit) = suspendCancellableCoroutine { cont ->
        block(object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription?) = Unit
            override fun onSetSuccess() {
                if (cont.isActive) cont.resume(Unit)
            }
            override fun onCreateFailure(error: String?) {
                if (cont.isActive) cont.resumeWithException(IllegalStateException(error ?: "sdp"))
            }
            override fun onSetFailure(error: String?) {
                if (cont.isActive) cont.resumeWithException(IllegalStateException(error ?: "sdp"))
            }
        })
    }

    private suspend fun awaitCreate(block: (SdpObserver) -> Unit): SessionDescription =
        suspendCancellableCoroutine { cont ->
            block(object : SdpObserver {
                override fun onCreateSuccess(sdp: SessionDescription?) {
                    if (sdp != null && cont.isActive) cont.resume(sdp)
                    else if (cont.isActive) cont.resumeWithException(IllegalStateException("empty sdp"))
                }
                override fun onSetSuccess() = Unit
                override fun onCreateFailure(error: String?) {
                    if (cont.isActive) cont.resumeWithException(IllegalStateException(error ?: "sdp"))
                }
                override fun onSetFailure(error: String?) {
                    if (cont.isActive) cont.resumeWithException(IllegalStateException(error ?: "sdp"))
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
