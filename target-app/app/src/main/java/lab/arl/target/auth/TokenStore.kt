package lab.arl.target.auth

import lab.arl.target.domain.TokenPair

interface TokenStore {
    fun load(): StoredCredentials?
    fun save(credentials: StoredCredentials)
    fun clear()
}

data class StoredCredentials(
    val deviceId: String,
    val enrollmentId: String?,
    val accessToken: String,
    val refreshToken: String,
    val accessExpiresAtEpochMs: Long
) {
    companion object {
        fun from(deviceId: String, enrollmentId: String?, tokens: TokenPair, nowMs: Long = System.currentTimeMillis()): StoredCredentials {
            val ttl = tokens.expiresIn.coerceAtLeast(60) * 1000L
            return StoredCredentials(
                deviceId = deviceId,
                enrollmentId = enrollmentId,
                accessToken = tokens.accessToken,
                refreshToken = tokens.refreshToken,
                accessExpiresAtEpochMs = nowMs + ttl - 15_000L
            )
        }
    }
}

class InMemoryTokenStore : TokenStore {
    private var value: StoredCredentials? = null
    override fun load(): StoredCredentials? = value
    override fun save(credentials: StoredCredentials) {
        value = credentials
    }
    override fun clear() {
        value = null
    }
}
