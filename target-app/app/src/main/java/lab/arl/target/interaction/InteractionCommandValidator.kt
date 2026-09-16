package lab.arl.target.interaction

import java.util.UUID

data class InteractionParams(
    val nx: Double? = null,
    val ny: Double? = null,
    val nx2: Double? = null,
    val ny2: Double? = null,
    val durationMs: Long? = null,
    val direction: String? = null,
    val viewId: String? = null,
    val text: String? = null
)

data class InteractionCommand(
    val commandId: String,
    val sessionId: String,
    val timestamp: Long,
    val operation: String,
    val capability: String,
    val params: InteractionParams = InteractionParams()
)

data class InteractionRejection(
    val code: String,
    val message: String
)

object InteractionCommandValidator {
    private val operations = setOf(
        "TAP", "LONG_PRESS", "SWIPE", "SCROLL", "BACK", "HOME", "RECENTS",
        "NODE_CLICK", "NODE_FOCUS", "TEXT_ENTRY"
    )
    const val SKEW_MS = 30_000L
    const val MAX_TEXT = 500
    const val MAX_VIEW_ID = 256
    private val liveStatuses = setOf("AUTHENTICATED", "ACTIVE", "RECONNECTING")

    fun parse(commandId: String?, sessionId: String?, timestamp: Long?, operation: String?, capability: String?, params: InteractionParams): Pair<InteractionCommand?, InteractionRejection?> {
        val id = commandId
        val sid = sessionId
        if (id.isNullOrBlank() || runCatching { UUID.fromString(id) }.isFailure) {
            return null to InteractionRejection("MALFORMED_COMMAND", "commandId must be a UUID.")
        }
        if (sid.isNullOrBlank() || runCatching { UUID.fromString(sid) }.isFailure) {
            return null to InteractionRejection("MALFORMED_COMMAND", "sessionId must be a UUID.")
        }
        if (timestamp == null) {
            return null to InteractionRejection("MALFORMED_COMMAND", "timestamp is required.")
        }
        val op = operation
        val cap = capability
        if (op == null || op !in operations) {
            return null to InteractionRejection("MALFORMED_COMMAND", "Unknown operation.")
        }
        if (cap != "REMOTE_INTERACTION") {
            return null to InteractionRejection("MALFORMED_COMMAND", "Required capability must be REMOTE_INTERACTION.")
        }
        when (op) {
            "TAP", "LONG_PRESS" -> if (params.nx == null || params.ny == null) {
                return null to InteractionRejection("MALFORMED_COMMAND", "TAP/LONG_PRESS require nx and ny.")
            }
            "SWIPE" -> if (params.nx == null || params.ny == null || params.nx2 == null || params.ny2 == null) {
                return null to InteractionRejection("MALFORMED_COMMAND", "SWIPE requires nx, ny, nx2, ny2.")
            }
            "SCROLL" -> if (params.direction !in setOf("UP", "DOWN", "LEFT", "RIGHT")) {
                return null to InteractionRejection("MALFORMED_COMMAND", "SCROLL requires direction.")
            }
            "NODE_CLICK", "NODE_FOCUS" -> {
                val viewId = params.viewId
                if (viewId.isNullOrBlank() || viewId.length > MAX_VIEW_ID) {
                    return null to InteractionRejection("MALFORMED_COMMAND", "Node operations require viewId.")
                }
            }
            "TEXT_ENTRY" -> {
                val text = params.text
                if (text.isNullOrBlank() || text.length > MAX_TEXT) {
                    return null to InteractionRejection("MALFORMED_COMMAND", "TEXT_ENTRY requires text.")
                }
            }
        }
        if (listOfNotNull(params.nx, params.ny, params.nx2, params.ny2).any { it < 0.0 || it > 1.0 }) {
            return null to InteractionRejection("MALFORMED_COMMAND", "Coordinates must be normalized 0..1.")
        }
        return InteractionCommand(id, sid, timestamp, op, cap, params) to null
    }

    fun authorize(
        command: InteractionCommand,
        expectedSessionId: String?,
        sessionStatus: String?,
        sessionMode: String?,
        capabilityAvailable: Boolean,
        now: Long,
        seenCommandIds: MutableSet<String>
    ): InteractionRejection? {
        if (expectedSessionId == null || command.sessionId != expectedSessionId) {
            return InteractionRejection("FORBIDDEN", "Command is not scoped to the active session.")
        }
        if (sessionStatus in setOf("TERMINATED", "REVOKED", "TIMED_OUT")) {
            return InteractionRejection("SESSION_ENDED", "Session is not active.")
        }
        if (sessionStatus !in liveStatuses) {
            return InteractionRejection("SESSION_NOT_READY", "Session has not been authenticated.")
        }
        if (sessionMode == "MONITOR") {
            return InteractionRejection("MODE_VIEW_ONLY", "Remote interaction is not allowed in MONITOR mode.")
        }
        if (!capabilityAvailable) {
            return InteractionRejection("CAPABILITY_DISABLED", "Accessibility remote interaction is not enabled.")
        }
        if (kotlin.math.abs(now - command.timestamp) > SKEW_MS) {
            return InteractionRejection("STALE_COMMAND", "Command timestamp is outside the allowed window.")
        }
        if (!seenCommandIds.add(command.commandId)) {
            return InteractionRejection("REPLAYED_COMMAND", "Command ID has already been used.")
        }
        return null
    }
}
