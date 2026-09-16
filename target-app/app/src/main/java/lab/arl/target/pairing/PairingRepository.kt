package lab.arl.target.pairing

import lab.arl.target.auth.StoredCredentials
import lab.arl.target.auth.TokenStore
import lab.arl.target.domain.DeviceProfile
import lab.arl.target.domain.DeviceRecord
import lab.arl.target.domain.EnrollmentRecord
import lab.arl.target.domain.PairingClaim
import lab.arl.target.network.ConfirmPairingResponse
import lab.arl.target.network.DeviceInfoDto
import lab.arl.target.network.PairingClaimRequest
import lab.arl.target.network.PairingConfirmRequest
import lab.arl.target.network.TargetApi
import lab.arl.target.network.toDomain
import lab.arl.target.network.toDto

class PairingRepository(
    private val api: TargetApi,
    private val tokenStore: TokenStore
) {
    suspend fun claim(code: String, profile: DeviceProfile): PairingClaim {
        val request = PairingClaimRequest(
            pairingCode = code,
            device = DeviceInfoDto(
                name = profile.name,
                platform = profile.platform,
                androidVersion = profile.androidVersion,
                manufacturer = profile.manufacturer,
                model = profile.model,
                sdkInt = profile.sdkInt,
                capabilities = profile.capabilities.toDto()
            )
        )
        return api.claimPairing(request).toDomain()
    }

    suspend fun confirm(claimToken: String): Triple<DeviceRecord, EnrollmentRecord, StoredCredentials> {
        val response: ConfirmPairingResponse = api.confirmPairing(PairingConfirmRequest(claimToken))
        val device = response.device.toDomain()
        val enrollment = response.enrollment.toDomain()
        val stored = StoredCredentials.from(device.id, enrollment.id, response.tokens.toDomain())
        tokenStore.save(stored)
        return Triple(device, enrollment, stored)
    }
}
