package lab.arl.target.interaction

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import lab.arl.target.domain.EnrollmentPhase
import org.junit.Test

class ScreenCaptureApiStateTest {
    @Test
    fun activeCaptureIsAvailable() {
        assertEquals("AVAILABLE", CapabilityReporter.resolveScreenCaptureApiState(true))
    }

    @Test
    fun idleCaptureIsNotGranted() {
        assertEquals("NOT_GRANTED", CapabilityReporter.resolveScreenCaptureApiState(false))
    }
}

class CapabilityContractTest {
    @Test
    fun reportNeverEmitsUnsupportedCapabilityStates() {
        val combos = listOf(
            Triple(false, false, false),
            Triple(true, false, false),
            Triple(false, true, true),
            Triple(true, true, false),
        )
        for ((enabled, connected, active) in combos) {
            val caps = CapabilityReporter.report(
                accessibilityEnabled = enabled,
                accessibilityConnected = connected,
                captureActive = active
            )
            caps.states.values.forEach { state ->
                assertTrue(
                    state in CapabilityReporter.API_CAPABILITY_STATES,
                    "Unsupported capability state on wire: $state"
                )
            }
        }
    }

    @Test
    fun idleScreenCaptureIsNotAvailableOnWire() {
        val caps = CapabilityReporter.report(false, false, captureActive = false)
        assertEquals("NOT_GRANTED", caps.states["SCREEN_CAPTURE"])
        assertFalse(caps.screenCapture)
    }
}

class CapabilityReporterTest {
    @Test
    fun accessibilityOffIsNotGranted() {
        val caps = CapabilityReporter.report(accessibilityEnabled = false, accessibilityConnected = false, sdkInt = 34)
        assertEquals("NOT_GRANTED", caps.states["ACCESSIBILITY_CONTROL"])
        assertEquals("NOT_GRANTED", caps.states["REMOTE_INTERACTION"])
        assertFalse(caps.remoteInput)
        assertEquals("NOT_GRANTED", caps.states["SCREEN_CAPTURE"])
        assertEquals("AVAILABLE", caps.states["FILE_BACKUP"])
    }

    @Test
    fun connectedServiceReportsAvailable() {
        val caps = CapabilityReporter.report(accessibilityEnabled = true, accessibilityConnected = true, sdkInt = 34)
        assertEquals("AVAILABLE", caps.states["ACCESSIBILITY_CONTROL"])
        assertEquals("AVAILABLE", caps.states["REMOTE_INTERACTION"])
        assertTrue(caps.remoteInput)
    }

    @Test
    fun enabledButUnboundIsRestricted() {
        val caps = CapabilityReporter.report(accessibilityEnabled = true, accessibilityConnected = false, sdkInt = 34)
        assertEquals("RESTRICTED", caps.states["ACCESSIBILITY_CONTROL"])
        assertEquals("RESTRICTED", caps.states["REMOTE_INTERACTION"])
        assertFalse(caps.remoteInput)
    }

    @Test
    fun api35BackgroundSessionIsRestricted() {
        val caps = CapabilityReporter.report(false, false, sdkInt = 35)
        assertEquals("RESTRICTED", caps.states["BACKGROUND_SESSION"])
    }
}

class AccessibilityOnboardingTest {
    @Test
    fun promptsAfterEnrollmentWhenServiceNotGranted() {
        assertTrue(
            AccessibilityOnboarding.shouldPrompt(EnrollmentPhase.ENROLLED, "NOT_GRANTED", dismissed = false)
        )
        assertTrue(
            AccessibilityOnboarding.shouldPrompt(EnrollmentPhase.CONNECTED, null, dismissed = false)
        )
        assertTrue(
            AccessibilityOnboarding.shouldPrompt(EnrollmentPhase.ACTIVE_SESSION, "NOT_GRANTED", dismissed = false)
        )
    }

    @Test
    fun hidesWhenNotEnrolledDismissedOrAlreadyEnabled() {
        assertFalse(
            AccessibilityOnboarding.shouldPrompt(EnrollmentPhase.AUTHORIZATION_PENDING, "NOT_GRANTED", dismissed = false)
        )
        assertFalse(
            AccessibilityOnboarding.shouldPrompt(EnrollmentPhase.NOT_ENROLLED, "NOT_GRANTED", dismissed = false)
        )
        assertFalse(
            AccessibilityOnboarding.shouldPrompt(EnrollmentPhase.ENROLLED, "NOT_GRANTED", dismissed = true)
        )
        assertFalse(
            AccessibilityOnboarding.shouldPrompt(EnrollmentPhase.ENROLLED, "AVAILABLE", dismissed = false)
        )
        assertFalse(
            AccessibilityOnboarding.shouldPrompt(EnrollmentPhase.CONNECTED, "RESTRICTED", dismissed = false)
        )
        assertFalse(
            AccessibilityOnboarding.shouldPrompt(EnrollmentPhase.ENROLLED, "UNAVAILABLE", dismissed = false)
        )
        assertFalse(
            AccessibilityOnboarding.shouldPrompt(EnrollmentPhase.REVOKED, "NOT_GRANTED", dismissed = false)
        )
        assertEquals(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS, AccessibilityOnboarding.SETTINGS_ACTION)
    }
}

class AccessibilityInspectorTest {
    @Test
    fun detectsFlattenedAndShortComponentNames() {
        val pkg = "lab.arl.target"
        val cls = AccessibilityInspector.SERVICE_CLASS
        assertTrue(AccessibilityInspector.isComponentListed("$pkg/$cls", pkg))
        assertTrue(AccessibilityInspector.isComponentListed("other/.Foo:$pkg/.interaction.RemoteInteractionService", pkg))
        assertFalse(AccessibilityInspector.isComponentListed("com.other/.Service", pkg))
        assertFalse(AccessibilityInspector.isComponentListed(null, pkg))
    }
}

class InteractionCommandValidatorTest {
    private val session = "22222222-2222-4222-8222-222222222222"
    private val cmdId = "11111111-1111-4111-8111-111111111111"

    @Test
    fun rejectsMalformedAndAuthorizesLiveSession() {
        val bad = InteractionCommandValidator.parse(null, session, 1L, "TAP", "REMOTE_INTERACTION", InteractionParams())
        assertNotNull(bad.second)

        val (tap, err) = InteractionCommandValidator.parse(
            cmdId, session, 1_000L, "TAP", "REMOTE_INTERACTION", InteractionParams(nx = 0.2, ny = 0.3)
        )
        assertNull(err)
        assertNotNull(tap)
        val seen = linkedSetOf<String>()
        assertNotNull(InteractionCommandValidator.authorize(tap!!, session, "CREATED", "MANAGED", true, 1_000L, seen))
        assertNull(InteractionCommandValidator.authorize(tap, session, "ACTIVE", "MANAGED", true, 1_000L, seen))
        assertEquals("REPLAYED_COMMAND", InteractionCommandValidator.authorize(tap, session, "ACTIVE", "MANAGED", true, 1_000L, seen)?.code)
        assertEquals(
            "MODE_VIEW_ONLY",
            InteractionCommandValidator.authorize(
                tap.copy(commandId = "66666666-6666-4666-8666-666666666666"),
                session,
                "ACTIVE",
                "MONITOR",
                true,
                1_000L,
                linkedSetOf()
            )?.code
        )
        assertEquals(
            "SESSION_ENDED",
            InteractionCommandValidator.authorize(
                tap.copy(commandId = "33333333-3333-4333-8333-333333333333"),
                session,
                "TERMINATED",
                "MANAGED",
                true,
                1_000L,
                linkedSetOf()
            )?.code
        )
        assertEquals(
            "CAPABILITY_DISABLED",
            InteractionCommandValidator.authorize(
                tap.copy(commandId = "44444444-4444-4444-8444-444444444444"),
                session,
                "ACTIVE",
                "MANAGED",
                false,
                1_000L,
                linkedSetOf()
            )?.code
        )
        assertEquals(
            "FORBIDDEN",
            InteractionCommandValidator.authorize(tap.copy(commandId = "55555555-5555-4555-8555-555555555555"), "other", "ACTIVE", "MANAGED", true, 1_000L, linkedSetOf())?.code
        )
    }
}
