package lab.arl.target.interaction

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.provider.Settings
import lab.arl.target.domain.EnrollmentPhase

object AccessibilityOnboarding {
    const val SETTINGS_ACTION = Settings.ACTION_ACCESSIBILITY_SETTINGS

    fun shouldPrompt(
        phase: EnrollmentPhase,
        remoteInteractionState: String?,
        dismissed: Boolean
    ): Boolean {
        val enrolled = phase == EnrollmentPhase.ENROLLED ||
            phase == EnrollmentPhase.CONNECTED ||
            phase == EnrollmentPhase.ACTIVE_SESSION
        if (!enrolled || dismissed) {
            return false
        }
        val state = remoteInteractionState ?: "NOT_GRANTED"
        return state == "NOT_GRANTED"
    }

    fun settingsIntent(context: Context): Intent {
        val intent = Intent(SETTINGS_ACTION)
        if (context !is Activity) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return intent
    }
}
