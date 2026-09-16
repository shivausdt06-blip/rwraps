package lab.arl.admin.interaction

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class VideoCoordinateMapperTest {
    @Test
    fun mapsCenterOfLetterboxedVideo() {
        // 1280x720 video in 1000x1000 view → pillarboxed with 140px side margins
        val mapped = VideoCoordinateMapper.mapTouchToNormalized(500f, 500f, 1000, 1000, 1280, 720)
        assertNotNull(mapped)
        assertEquals(0.5f, mapped!!.first, 0.01f)
        assertEquals(0.5f, mapped.second, 0.01f)
    }

    @Test
    fun rejectsTouchOutsideVideoArea() {
        // Letterbox bands above/below the 1280x720 content in a square view.
        assertNull(VideoCoordinateMapper.mapTouchToNormalized(500f, 50f, 1000, 1000, 1280, 720))
        assertNull(VideoCoordinateMapper.mapTouchToNormalized(500f, 950f, 1000, 1000, 1280, 720))
    }

    @Test
    fun mapsTopLeftOfVideoContent() {
        val rect = VideoCoordinateMapper.videoRect(1000, 1000, 1280, 720)!!
        val mapped = VideoCoordinateMapper.mapTouchToNormalized(
            rect.left + 1f,
            rect.top + 1f,
            1000,
            1000,
            1280,
            720
        )
        assertNotNull(mapped)
        assertEquals(0f, mapped!!.first, 0.05f)
        assertEquals(0f, mapped.second, 0.05f)
    }
}
