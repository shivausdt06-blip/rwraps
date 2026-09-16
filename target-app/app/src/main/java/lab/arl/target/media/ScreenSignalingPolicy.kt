package lab.arl.target.media

/**
 * Admin SCREEN is the SDP offerer. Target only answers so both sides cannot
 * set local offers at once (glare), which previously left Admin with no remote track.
 */
object ScreenSignalingPolicy {
    fun shouldAnswerRemoteOffer(captureRunning: Boolean, sessionMatches: Boolean): Boolean =
        captureRunning && sessionMatches

    fun shouldCreateLocalOffer(): Boolean = false

    fun shouldApplyRemoteAnswer(hasLocalOffer: Boolean): Boolean = hasLocalOffer
}
