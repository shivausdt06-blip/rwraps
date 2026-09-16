package lab.arl.target.media

import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.Looper
import lab.arl.target.service.TargetForegroundService
import org.webrtc.CapturerObserver
import org.webrtc.EglBase
import org.webrtc.ScreenCapturerAndroid
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoCapturer
import org.webrtc.VideoFrame
import org.webrtc.VideoSource
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class ScreenCaptureController(
    private val appContext: Context,
    private val eglBase: EglBase
) {
    private var capturer: VideoCapturer? = null
    private var surfaceHelper: SurfaceTextureHelper? = null
    private val running = AtomicBoolean(false)
    private val frames = AtomicInteger(0)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            mainHandler.post { stop() }
        }
    }

    val isRunning: Boolean get() = running.get()

    fun start(
        resultCode: Int,
        permissionData: Intent,
        videoSource: VideoSource,
        settings: CaptureSettings
    ) {
        stop()
        TargetForegroundService.start(appContext, sessionActive = true, projectionReady = true)
        WebrtcDiag.log("capture_started", extra = "${settings.width}x${settings.height}@${settings.fps} result=$resultCode")
        val helper = SurfaceTextureHelper.create("ARLScreenCapture", eglBase.eglBaseContext)
        surfaceHelper = helper
        val observer = videoSource.capturerObserver
        val wrapped = object : CapturerObserver {
            override fun onCapturerStarted(success: Boolean) {
                WebrtcDiag.log("capturer_started", extra = "ok=$success")
                observer.onCapturerStarted(success)
            }

            override fun onCapturerStopped() {
                WebrtcDiag.log("capturer_stopped")
                observer.onCapturerStopped()
            }

            override fun onFrameCaptured(frame: VideoFrame?) {
                if (frame == null) return
                val n = frames.incrementAndGet()
                if (n == 1) {
                    WebrtcDiag.log("first_frame_captured", extra = "${frame.buffer.width}x${frame.buffer.height}")
                    WebrtcDiag.log("video_track_frames_started", extra = "${frame.buffer.width}x${frame.buffer.height}")
                } else if (n % 90 == 0) {
                    WebrtcDiag.log("frames_captured", extra = "n=$n")
                }
                observer.onFrameCaptured(frame)
            }
        }
        val screen = ScreenCapturerAndroid(permissionData, projectionCallback)
        capturer = screen
        WebrtcDiag.log("screen_capturer_created")
        WebrtcDiag.log("projection_created")
        screen.initialize(helper, appContext, wrapped)
        WebrtcDiag.log("surface_texture_helper_created")
        screen.startCapture(settings.width, settings.height, settings.fps)
        running.set(true)
        WebrtcDiag.log("screen_capturer_started")
        WebrtcDiag.log("screencapturer_started")
    }

    fun stop() {
        val wasRunning = running.getAndSet(false)
        if (!wasRunning && capturer == null && surfaceHelper == null) {
            return
        }
        WebrtcDiag.log("capture_stop", extra = "frames=${frames.get()}")
        frames.set(0)
        runCatching { capturer?.stopCapture() }
        runCatching { capturer?.dispose() }
        capturer = null
        runCatching { surfaceHelper?.dispose() }
        surfaceHelper = null
    }
}
