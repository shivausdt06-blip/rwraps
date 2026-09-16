package lab.arl.admin.auth

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import lab.arl.admin.domain.TokenPair
import lab.arl.admin.network.AdminApi
import lab.arl.admin.network.ApiException
import lab.arl.admin.network.RefreshRequest
import lab.arl.admin.network.toDomain
import okhttp3.Authenticator
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route

data class StoredAdmin(
    val adminId: String,
    val email: String,
    val accessToken: String,
    val refreshToken: String
)

interface TokenStore {
    fun load(): StoredAdmin?
    fun save(value: StoredAdmin)
    fun clear()
}

class EncryptedAdminStore(context: Context) : TokenStore {
    private val alias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
    private val prefs = EncryptedSharedPreferences.create(
        "arl_admin_credentials",
        alias,
        context.applicationContext,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    override fun load(): StoredAdmin? {
        val access = prefs.getString("access", null) ?: return null
        val refresh = prefs.getString("refresh", null) ?: return null
        return StoredAdmin(
            adminId = prefs.getString("id", "") ?: "",
            email = prefs.getString("email", "") ?: "",
            accessToken = access,
            refreshToken = refresh
        )
    }

    override fun save(value: StoredAdmin) {
        prefs.edit()
            .putString("id", value.adminId)
            .putString("email", value.email)
            .putString("access", value.accessToken)
            .putString("refresh", value.refreshToken)
            .apply()
    }

    override fun clear() = prefs.edit().clear().apply()
}

class InMemoryTokenStore : TokenStore {
    private var v: StoredAdmin? = null
    override fun load() = v
    override fun save(value: StoredAdmin) {
        v = value
    }
    override fun clear() {
        v = null
    }
}

class AdminAuthenticator(
    private val store: TokenStore,
    private val refreshApi: () -> AdminApi
) : Authenticator {
    private val mutex = Mutex()

    override fun authenticate(route: Route?, response: Response): Request? {
        if (responseCount(response) >= 2) return null
        val stored = store.load() ?: return null
        val tokens = try {
            runBlocking { refresh(stored.refreshToken) }
        } catch (_: Exception) {
            return null
        }
        return response.request.newBuilder().header("Authorization", "Bearer ${tokens.accessToken}").build()
    }

    suspend fun refresh(refreshToken: String): TokenPair = mutex.withLock {
        try {
            val tokens = refreshApi().refresh(RefreshRequest(refreshToken)).tokens.toDomain()
            val current = store.load()
            if (current != null) {
                store.save(current.copy(accessToken = tokens.accessToken, refreshToken = tokens.refreshToken))
            }
            tokens
        } catch (err: ApiException) {
            if (err.statusCode == 401) store.clear()
            throw err
        }
    }

    private fun responseCount(response: Response): Int {
        var n = 1
        var p = response.priorResponse
        while (p != null) {
            n++
            p = p.priorResponse
        }
        return n
    }
}
