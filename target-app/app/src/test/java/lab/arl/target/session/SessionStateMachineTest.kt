package lab.arl.target.session

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test

class SessionStateMachineTest {
    @Test
    fun authenticatesFromCreated() {
        assertTrue(SessionStateMachine.canAuthenticate("CREATED"))
        assertEquals("AUTHENTICATED", SessionStateMachine.apply("CREATED", "authenticate"))
        assertEquals("ACTIVE", SessionStateMachine.apply("AUTHENTICATED", "activate"))
    }

    @Test
    fun rejectsActivateFromCreated() {
        assertFalse(SessionStateMachine.canActivate("CREATED"))
        assertEquals("CREATED", SessionStateMachine.apply("CREATED", "activate"))
    }

    @Test
    fun activatePolicySkipsActiveSession() {
        assertFalse(SessionActivatePolicy.shouldCallActivate("ACTIVE"))
        assertTrue(SessionActivatePolicy.shouldCallActivate("AUTHENTICATED"))
        assertTrue(SessionActivatePolicy.shouldCallActivate("RECONNECTING"))
        assertFalse(SessionActivatePolicy.shouldCallActivate("CREATED"))
    }

    @Test
    fun terminalStatesBlockReconnect() {
        assertFalse(SessionStateMachine.canReconnect("TERMINATED"))
        assertEquals("REVOKED", SessionStateMachine.apply("ACTIVE", "revoke"))
        assertEquals("TIMED_OUT", SessionStateMachine.apply("ACTIVE", "timeout"))
    }
}
