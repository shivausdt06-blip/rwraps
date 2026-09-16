package lab.arl.target.pairing

data class PairingInputParse(
    val code: String,
    val sessionId: String?,
    val apiBaseUrl: String? = null
)

object PairingCodeParser {
    private val codeRegex = Regex("^[A-HJ-NP-Z2-9]{6,16}$")

    fun parse(raw: String): PairingInputParse? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        val lower = trimmed.lowercase()
        if (lower.startsWith("arl://pair")) {
            val query = trimmed.substringAfter('?', missingDelimiterValue = "")
            val params = parseQuery(query)
            val code = params["code"]?.uppercase()
            val session = params["session"]
            val api = params["api"]?.trim()?.trimEnd('/')?.takeIf { it.isNotEmpty() }
            return if (code != null && isValidCode(code)) PairingInputParse(code, session, api) else null
        }
        val code = trimmed.uppercase().replace(" ", "")
        return if (isValidCode(code)) PairingInputParse(code, null, null) else null
    }

    fun isValidCode(code: String): Boolean = codeRegex.matches(code.uppercase())

    private fun parseQuery(query: String): Map<String, String> {
        if (query.isBlank()) return emptyMap()
        return query.split("&").mapNotNull { part ->
            val idx = part.indexOf('=')
            if (idx <= 0) return@mapNotNull null
            val key = part.substring(0, idx)
            val value = decode(part.substring(idx + 1))
            key to value
        }.toMap()
    }

    private fun decode(value: String): String {
        return value.replace("+", " ").let { encoded ->
            val bytes = ArrayList<Byte>()
            var i = 0
            while (i < encoded.length) {
                val ch = encoded[i]
                if (ch == '%' && i + 2 < encoded.length) {
                    val hex = encoded.substring(i + 1, i + 3)
                    val parsed = hex.toIntOrNull(16)
                    if (parsed != null) {
                        bytes.add(parsed.toByte())
                        i += 3
                        continue
                    }
                }
                bytes.add(ch.code.toByte())
                i += 1
            }
            String(bytes.toByteArray(), Charsets.UTF_8)
        }
    }
}
