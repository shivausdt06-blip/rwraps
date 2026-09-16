package lab.arl.target.interaction

import android.os.Build
import android.view.Display
import lab.arl.target.domain.DeviceCapabilities

object CapabilityReporter {
    const val SCREEN_CAPTURE = "SCREEN_CAPTURE"
    const val ACCESSIBILITY_CONTROL = "ACCESSIBILITY_CONTROL"
    const val REMOTE_INTERACTION = "REMOTE_INTERACTION"
    const val FILE_BACKUP = "FILE_BACKUP"
    const val BACKGROUND_SESSION = "BACKGROUND_SESSION"

    /** Must match backend [capabilityStateSchema] and shared CapabilityState. */
    val API_CAPABILITY_STATES: Set<String> = setOf(
        "AVAILABLE",
        "NOT_GRANTED",
        "RESTRICTED",
        "UNAVAILABLE"
    )

    /**
     * Wire/API screen-capture state. Only [API_CAPABILITY_STATES] values are emitted.
     * Idle or consent-pending sessions report NOT_GRANTED — not AVAILABLE — so consumers
     * do not treat MediaProjection as currently authorized.
     */
    fun resolveScreenCaptureApiState(captureActive: Boolean): String =
        if (captureActive) "AVAILABLE" else "NOT_GRANTED"

    fun report(
        accessibilityEnabled: Boolean,
        accessibilityConnected: Boolean,
        sdkInt: Int = Build.VERSION.SDK_INT,
        gesturesSupported: Boolean = sdkInt >= 24,
        captureActive: Boolean = false
    ): DeviceCapabilities {
        val screen = resolveScreenCaptureApiState(captureActive)
        val backup = "AVAILABLE"
        val background = if (sdkInt >= 35) "RESTRICTED" else "AVAILABLE"
        val accessibility = when {
            accessibilityEnabled && accessibilityConnected -> "AVAILABLE"
            accessibilityEnabled && !accessibilityConnected -> "RESTRICTED"
            else -> "NOT_GRANTED"
        }
        val remote = when {
            !gesturesSupported -> "UNAVAILABLE"
            accessibility == "AVAILABLE" -> "AVAILABLE"
            accessibility == "RESTRICTED" -> "RESTRICTED"
            else -> "NOT_GRANTED"
        }
        return DeviceCapabilities(
            screenCapture = screen == "AVAILABLE",
            fileBackup = backup == "AVAILABLE",
            remoteInput = remote == "AVAILABLE",
            accessibilityControl = accessibility == "AVAILABLE",
            backgroundSession = background == "AVAILABLE",
            states = mapOf(
                SCREEN_CAPTURE to screen,
                ACCESSIBILITY_CONTROL to accessibility,
                REMOTE_INTERACTION to remote,
                FILE_BACKUP to backup,
                BACKGROUND_SESSION to background
            )
        )
    }

    fun displayMetricsFallback(display: Display?): Pair<Int, Int> {
        val metrics = android.util.DisplayMetrics()
        @Suppress("DEPRECATION")
        display?.getRealMetrics(metrics)
        val w = if (metrics.widthPixels > 0) metrics.widthPixels else 1080
        val h = if (metrics.heightPixels > 0) metrics.heightPixels else 1920
        return w to h
    }
}
