package lab.arl.target.media

import android.content.Context
import android.os.Build
import android.view.WindowManager

data class CaptureSettings(
    val width: Int = 1280,
    val height: Int = 720,
    val fps: Int = 15
) {
    companion object {
        fun forDisplay(context: Context, fps: Int = 15, maxDimension: Int = 1280): CaptureSettings {
            val wm = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
            var screenW = 1080
            var screenH = 2400
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && wm != null) {
                val bounds = wm.maximumWindowMetrics.bounds
                screenW = bounds.width().coerceAtLeast(1)
                screenH = bounds.height().coerceAtLeast(1)
            } else if (wm != null) {
                val metrics = android.util.DisplayMetrics()
                @Suppress("DEPRECATION")
                wm.defaultDisplay.getRealMetrics(metrics)
                screenW = metrics.widthPixels.coerceAtLeast(1)
                screenH = metrics.heightPixels.coerceAtLeast(1)
            } else {
                val metrics = context.resources.displayMetrics
                screenW = metrics.widthPixels.coerceAtLeast(1)
                screenH = metrics.heightPixels.coerceAtLeast(1)
            }

            val scale = if (screenW > screenH) {
                minOf(1.0, maxDimension.toDouble() / screenW)
            } else {
                minOf(1.0, maxDimension.toDouble() / screenH)
            }
            val w = ((screenW * scale).toInt() / 16 * 16).coerceAtLeast(160)
            val h = ((screenH * scale).toInt() / 16 * 16).coerceAtLeast(160)
            return CaptureSettings(width = w, height = h, fps = fps)
        }
    }
}
