package lab.arl.target.session

import lab.arl.target.domain.IceServer
import lab.arl.target.domain.RemoteSession
import lab.arl.target.domain.SessionQuality
import lab.arl.target.network.TargetApi
import lab.arl.target.network.TelemetryRequest
import lab.arl.target.network.toDomain

class SessionRepository(private val api: TargetApi) {
    suspend fun get(id: String): RemoteSession = api.getSession(id).session.toDomain()
    suspend fun authenticate(id: String): RemoteSession = api.authenticateSession(id).session.toDomain()
    suspend fun activate(id: String): RemoteSession = api.activateSession(id).session.toDomain()
    suspend fun reconnect(id: String): RemoteSession = api.reconnectSession(id).session.toDomain()
    suspend fun terminate(id: String): RemoteSession = api.terminateSession(id).session.toDomain()
    suspend fun telemetry(id: String, quality: SessionQuality): RemoteSession {
        return api.sessionTelemetry(
            id,
            TelemetryRequest(quality.rttMs, quality.packetLoss, quality.bitrateKbps)
        ).session.toDomain()
    }
    suspend fun iceServers(): List<IceServer> = api.iceServers().iceServers.map { it.toDomain() }
}
