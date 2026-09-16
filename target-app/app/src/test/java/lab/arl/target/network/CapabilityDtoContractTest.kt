package lab.arl.target.network

import kotlin.test.Test
import kotlin.test.assertTrue
import lab.arl.target.interaction.CapabilityReporter

class CapabilityDtoContractTest {
    @Test
    fun capabilitiesToDtoNeverLeaksUnsupportedStates() {
        val caps = CapabilityReporter.report(
            accessibilityEnabled = true,
            accessibilityConnected = true,
            captureActive = false
        )
        val dto = caps.toDto()
        dto.states?.values?.forEach { state ->
            assertTrue(
                state in CapabilityReporter.API_CAPABILITY_STATES,
                "DTO leaked unsupported capability state: $state"
            )
        }
    }
}
