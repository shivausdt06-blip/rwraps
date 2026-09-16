package lab.arl.admin.pairing

object PairingLink {
    /**
     * Keeps backend `arl://pair?code=&session=` and ensures Target-facing `api=` is present
     * when a debug pairing API base is configured. Release builds pass a blank override.
     */
    fun forTarget(qrPayload: String, debugPairingApiBase: String?): String {
        val override = debugPairingApiBase?.trim()?.trimEnd('/').orEmpty()
        if (override.isEmpty()) {
            return qrPayload
        }
        val prefix = "arl://pair"
        if (!qrPayload.startsWith(prefix, ignoreCase = true)) {
            return qrPayload
        }
        val query = qrPayload.substringAfter('?', missingDelimiterValue = "")
        val kept = query.split("&").filter { part ->
            part.isNotBlank() && !part.startsWith("api=", ignoreCase = true)
        }
        val api = "api=" + encode(override)
        val joined = (kept + api).joinToString("&")
        return "$prefix?$joined"
    }

    private fun encode(value: String): String {
        val bytes = value.toByteArray(Charsets.UTF_8)
        val out = StringBuilder()
        for (b in bytes) {
            val u = b.toInt() and 0xFF
            val ch = u.toChar()
            if (ch.isLetterOrDigit() || ch == '-' || ch == '_' || ch == '.' || ch == '~') {
                out.append(ch)
            } else {
                out.append('%')
                out.append("%02X".format(u))
            }
        }
        return out.toString()
    }
}
