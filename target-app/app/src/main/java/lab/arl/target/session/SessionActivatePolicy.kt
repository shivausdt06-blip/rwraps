package lab.arl.target.session

/**
 * Only the Target may transition AUTHENTICATED → ACTIVE when screen capture is ready.
 * If Admin already activated the session, Target must not fail media startup.
 */
object SessionActivatePolicy {
    fun shouldCallActivate(status: String): Boolean =
        status == "AUTHENTICATED" || status == "RECONNECTING"
}
