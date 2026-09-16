package lab.arl.target.interaction

object AccessibilityInspector {
    const val SERVICE_CLASS = "lab.arl.target.interaction.RemoteInteractionService"

    fun isComponentListed(enabledSetting: String?, packageName: String, className: String = SERVICE_CLASS): Boolean {
        if (enabledSetting.isNullOrBlank()) return false
        val flattened = "$packageName/$className"
        val relative = "$packageName/${className.removePrefix(packageName)}"
        val simple = "$packageName/.${className.substringAfterLast('.')}"
        return enabledSetting.split(':').any { entry ->
            val item = entry.trim()
            item.equals(flattened, ignoreCase = true) ||
                item.equals(relative, ignoreCase = true) ||
                item.equals(simple, ignoreCase = true) ||
                item.endsWith("/$className", ignoreCase = true)
        }
    }
}
