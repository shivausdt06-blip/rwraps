package lab.arl.admin.interaction

/**
 * Maps touch coordinates on the Admin renderer to normalized Target screen coordinates (0..1).
 * Assumes SCALE_ASPECT_FIT letterboxing in the view.
 */
object VideoCoordinateMapper {
    data class VideoRect(
        val left: Float,
        val top: Float,
        val width: Float,
        val height: Float
    )

    fun videoRect(viewWidth: Int, viewHeight: Int, videoWidth: Int, videoHeight: Int): VideoRect? {
        if (viewWidth <= 0 || viewHeight <= 0 || videoWidth <= 0 || videoHeight <= 0) return null
        val scale = minOf(viewWidth.toFloat() / videoWidth, viewHeight.toFloat() / videoHeight)
        val w = videoWidth * scale
        val h = videoHeight * scale
        return VideoRect(
            left = (viewWidth - w) / 2f,
            top = (viewHeight - h) / 2f,
            width = w,
            height = h
        )
    }

    /**
     * @return normalized (nx, ny) in 0..1 on the video frame, or null if outside the video area.
     */
    fun mapTouchToNormalized(
        touchX: Float,
        touchY: Float,
        viewWidth: Int,
        viewHeight: Int,
        videoWidth: Int,
        videoHeight: Int
    ): Pair<Float, Float>? {
        val rect = videoRect(viewWidth, viewHeight, videoWidth, videoHeight) ?: return null
        if (touchX < rect.left || touchY < rect.top ||
            touchX > rect.left + rect.width || touchY > rect.top + rect.height
        ) {
            return null
        }
        val nx = ((touchX - rect.left) / rect.width).coerceIn(0f, 1f)
        val ny = ((touchY - rect.top) / rect.height).coerceIn(0f, 1f)
        return nx to ny
    }
}
