package lab.arl.target.service

import android.content.pm.ServiceInfo
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Test

class ForegroundSessionTypesTest {
    @Test
    fun connectedWithoutProjectionIsDataSyncOnly() {
        val types = ForegroundSessionTypes.forStart(projectionReady = false, sdkInt = 35)
        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC, types)
    }

    @Test
    fun captureReadyAddsMediaProjectionType() {
        val types = ForegroundSessionTypes.forStart(projectionReady = true, sdkInt = 35)
        assertTrue(types and ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION != 0)
        assertTrue(types and ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC != 0)
    }
}
