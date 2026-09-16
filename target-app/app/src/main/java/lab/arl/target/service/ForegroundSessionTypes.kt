package lab.arl.target.service

import android.content.pm.ServiceInfo
import android.os.Build

object ForegroundSessionTypes {
    fun forStart(projectionReady: Boolean, sdkInt: Int = Build.VERSION.SDK_INT): Int {
        if (sdkInt < 29) return 0
        var types = ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        if (projectionReady && sdkInt >= 29) {
            types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        }
        return types
    }
}
