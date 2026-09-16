package lab.arl.target.session

/**
 * Pure session status transitions used by the target client.
 * Backend remains the source of truth; this validates local sequencing.
 */
object SessionStateMachine {
    private val terminal = setOf("TERMINATED", "REVOKED", "TIMED_OUT")

    fun canAuthenticate(status: String): Boolean = status == "CREATED" || status == "RECONNECTING"

    fun canActivate(status: String): Boolean = status == "AUTHENTICATED" || status == "RECONNECTING"

    fun canReconnect(status: String): Boolean = status !in terminal

    fun canTerminate(status: String): Boolean = status !in terminal

    fun apply(current: String, event: String): String {
        return when (event) {
            "authenticate" -> if (canAuthenticate(current)) "AUTHENTICATED" else current
            "activate" -> if (canActivate(current)) "ACTIVE" else current
            "reconnect" -> if (canReconnect(current)) "RECONNECTING" else current
            "terminate" -> if (canTerminate(current)) "TERMINATED" else current
            "revoke" -> "REVOKED"
            "timeout" -> "TIMED_OUT"
            else -> current
        }
    }
}
