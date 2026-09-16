package lab.arl.target.auth

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys

class EncryptedTokenStore(context: Context) : TokenStore {
    private val masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
    private val prefs = EncryptedSharedPreferences.create(
        "arl_target_credentials",
        masterKeyAlias,
        context.applicationContext,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    override fun load(): StoredCredentials? {
        val deviceId = prefs.getString(KEY_DEVICE, null) ?: return null
        val access = prefs.getString(KEY_ACCESS, null) ?: return null
        val refresh = prefs.getString(KEY_REFRESH, null) ?: return null
        return StoredCredentials(
            deviceId = deviceId,
            enrollmentId = prefs.getString(KEY_ENROLLMENT, null),
            accessToken = access,
            refreshToken = refresh,
            accessExpiresAtEpochMs = prefs.getLong(KEY_EXPIRY, 0L)
        )
    }

    override fun save(credentials: StoredCredentials) {
        prefs.edit()
            .putString(KEY_DEVICE, credentials.deviceId)
            .putString(KEY_ENROLLMENT, credentials.enrollmentId)
            .putString(KEY_ACCESS, credentials.accessToken)
            .putString(KEY_REFRESH, credentials.refreshToken)
            .putLong(KEY_EXPIRY, credentials.accessExpiresAtEpochMs)
            .apply()
    }

    override fun clear() {
        prefs.edit().clear().apply()
    }

    private companion object {
        const val KEY_DEVICE = "device_id"
        const val KEY_ENROLLMENT = "enrollment_id"
        const val KEY_ACCESS = "access_token"
        const val KEY_REFRESH = "refresh_token"
        const val KEY_EXPIRY = "access_expiry"
    }
}
