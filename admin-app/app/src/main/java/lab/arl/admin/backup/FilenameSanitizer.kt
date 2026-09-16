package lab.arl.admin.backup

object FilenameSanitizer {
    private val allowed = Regex("[^\\w.\\-+ ()\\[\\]]+")

    fun sanitize(raw: String): String {
        val base = raw.replace('\\', '/').substringAfterLast('/')
        val cleaned = allowed.replace(base, "_").replace(Regex("^\\.+"), "_").take(180).trim()
        return cleaned.ifBlank { "file" }
    }
}

object BackupIds {
    private val uuid = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")

    fun requireId(id: String, label: String = "id"): String {
        require(uuid.matches(id)) { "Invalid $label" }
        return id
    }
}
