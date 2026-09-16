package lab.arl.target.device

import android.content.Context
import android.os.Build
import android.provider.Settings
import lab.arl.target.domain.DeviceCapabilities
import lab.arl.target.domain.DeviceProfile
import lab.arl.target.domain.DeviceRecord
import lab.arl.target.interaction.AccessibilityInspector
import lab.arl.target.interaction.CapabilityReporter
import lab.arl.target.interaction.RemoteInteractionService
import lab.arl.target.network.HeartbeatRequest
import lab.arl.target.network.TargetApi
import lab.arl.target.network.toDomain
import lab.arl.target.network.toDto

fun currentCapabilities(context: Context): DeviceCapabilities {
    val enabled = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
    val listed = AccessibilityInspector.isComponentListed(enabled, context.packageName)
    return CapabilityReporter.report(
        accessibilityEnabled = listed,
        accessibilityConnected = RemoteInteractionService.connected
    )
}

class DeviceProfileFactory(private val context: Context) {
    fun create(customName: String? = null): DeviceProfile {
        val name = customName?.takeIf { it.isNotBlank() }
            ?: Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME)
            ?: Build.MODEL
        return DeviceProfile(
            name = name.take(80),
            androidVersion = Build.VERSION.RELEASE,
            manufacturer = Build.MANUFACTURER.take(80),
            model = Build.MODEL.take(80),
            sdkInt = Build.VERSION.SDK_INT,
            capabilities = currentCapabilities(context)
        )
    }
}

class DeviceRepository(private val api: TargetApi) {
    suspend fun me(): DeviceRecord = api.deviceMe().device.toDomain()

    suspend fun heartbeat(profile: DeviceProfile): DeviceRecord {
        return api.heartbeat(
            HeartbeatRequest(
                capabilities = profile.capabilities.toDto(),
                androidVersion = profile.androidVersion
            )
        ).device.toDomain()
    }

    suspend fun revoke() {
        api.revokeEnrollment()
    }
}
