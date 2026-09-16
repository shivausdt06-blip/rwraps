package lab.arl.admin.network

import kotlin.test.assertEquals
import org.junit.Test

class AdminSocketUrlTest {
    @Test
    fun httpMapsToWs() {
        assertEquals("ws://10.0.2.2:8080", AdminSocket.toWs("http://10.0.2.2:8080/"))
    }
}

class DeviceLabelTest {
    @Test
    fun onlineFlag() {
        val d = lab.arl.admin.domain.DeviceRecord(
            id = "1",
            name = "Target 01",
            enrollmentState = "ACTIVE",
            authorizationState = "GRANTED",
            platform = "android",
            androidVersion = "15",
            manufacturer = "Google",
            model = "Pixel",
            sdkInt = 35,
            lastSeenAt = null,
            connectionState = "ONLINE",
            capabilities = lab.arl.admin.domain.DeviceCapabilities(true, true, false),
            createdAt = "t",
            updatedAt = "t"
        )
        assertEquals(true, d.online)
        assertEquals("TARGET 01", d.label)
        assertEquals("Android 15", d.osLine)
        assertEquals("CONTROL REQUIRES ACCESSIBILITY PERMISSION", d.capabilities.remoteInteractionLabel)
        val available = d.copy(
            capabilities = lab.arl.admin.domain.DeviceCapabilities(
                screenCapture = true,
                fileBackup = true,
                remoteInput = true,
                states = mapOf("REMOTE_INTERACTION" to "AVAILABLE")
            )
        )
        assertEquals("CONTROL AVAILABLE", available.capabilities.remoteInteractionLabel)
        assertEquals(true, available.capabilities.canInteract)
        val restricted = lab.arl.admin.domain.DeviceCapabilities(
            states = mapOf("REMOTE_INTERACTION" to "RESTRICTED")
        )
        assertEquals("CONTROL RESTRICTED BY ANDROID", restricted.remoteInteractionLabel)
    }
}
