package lab.arl.admin.interaction

import android.util.Log

object InteractionDiag {
    private const val TAG = "ARL-Interaction"

    fun log(event: String, sessionId: String? = null, extra: String? = null) {
        Log.i(
            TAG,
            buildString {
                append("admin ")
                append(event)
                if (!sessionId.isNullOrBlank()) {
                    append(" session=")
                    append(sessionId.take(8))
                }
                if (!extra.isNullOrBlank()) {
                    append(' ')
                    append(extra)
                }
            }
        )
    }
}
