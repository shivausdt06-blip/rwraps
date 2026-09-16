package lab.arl.target.auth

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.runBlocking
import lab.arl.target.domain.TokenPair
import lab.arl.target.network.ApiException
import lab.arl.target.network.RefreshRequest
import lab.arl.target.network.TargetApi
import lab.arl.target.network.toDomain
import okhttp3.Authenticator
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route
import java.util.concurrent.atomic.AtomicBoolean

class DeviceAuthenticator(
    private val tokenStore: TokenStore,
    private val refreshApi: () -> TargetApi
) : Authenticator {
    private val mutex = Mutex()
    private val refreshing = AtomicBoolean(false)

    override fun authenticate(route: Route?, response: Response): Request? {
        if (responseCount(response) >= 2) return null
        val stored = tokenStore.load() ?: return null
        val refreshed = try {
            kotlinx.coroutines.runBlocking {
                refresh(stored.refreshToken)
            }
        } catch (_: Exception) {
            null
        } ?: return null
        return response.request.newBuilder()
            .header("Authorization", "Bearer ${refreshed.accessToken}")
            .build()
    }

    suspend fun refresh(refreshToken: String): TokenPair {
        mutex.withLock {
            refreshing.set(true)
            try {
                val latest = tokenStore.load()
                if (latest != null && latest.refreshToken != refreshToken && latest.accessToken.isNotBlank()) {
                    return TokenPair(latest.accessToken, latest.refreshToken, 0)
                }
                val tokens = refreshApi().refresh(RefreshRequest(refreshToken)).tokens.toDomain()
                val current = tokenStore.load()
                if (current != null) {
                    tokenStore.save(StoredCredentials.from(current.deviceId, current.enrollmentId, tokens))
                }
                return tokens
            } catch (err: ApiException) {
                if (err.statusCode == 401) {
                    tokenStore.clear()
                }
                throw err
            } finally {
                refreshing.set(false)
            }
        }
    }

    fun currentAccessToken(): String? = tokenStore.load()?.accessToken

    private fun responseCount(response: Response): Int {
        var result = 1
        var prior = response.priorResponse
        while (prior != null) {
            result += 1
            prior = prior.priorResponse
        }
        return result
    }
}
