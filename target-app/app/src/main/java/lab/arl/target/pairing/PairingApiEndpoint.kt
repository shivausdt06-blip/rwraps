package lab.arl.target.pairing

import java.net.URI

/**
 * DEBUG/local pairing links may carry `api=` so a physical Target can reach the
 * host LAN address. Release builds always keep [compiledBase] (production HTTPS).
 */
object PairingApiEndpoint {
    fun resolve(compiledBase: String, fromUri: String?, debug: Boolean): String {
        val compiled = compiledBase.trim().trimEnd('/')
        if (!debug) {
            return compiled
        }
        val candidate = fromUri?.trim()?.trimEnd('/') ?: return compiled
        return if (isAllowedDebugOverride(candidate)) candidate else compiled
    }

    fun isAllowedDebugOverride(raw: String): Boolean {
        val uri = runCatching { URI(raw) }.getOrNull() ?: return false
        if (uri.userInfo != null) return false
        val scheme = uri.scheme?.lowercase() ?: return false
        if (scheme != "http" && scheme != "https") return false
        val host = uri.host?.lowercase() ?: return false
        if (uri.query != null || uri.fragment != null) return false
        val path = uri.path.orEmpty()
        if (path.isNotEmpty() && path != "/") return false
        val port = uri.port
        if (port != -1 && (port < 1 || port > 65535)) return false
        return host == "localhost" ||
            host == "127.0.0.1" ||
            host == "10.0.2.2" ||
            isPrivateIpv4(host)
    }

    private fun isPrivateIpv4(host: String): Boolean {
        val parts = host.split('.')
        if (parts.size != 4) return false
        val nums = parts.map { it.toIntOrNull() ?: return false }
        if (nums.any { it !in 0..255 }) return false
        val a = nums[0]
        val b = nums[1]
        return a == 10 ||
            (a == 192 && b == 168) ||
            (a == 172 && b in 16..31)
    }
}
