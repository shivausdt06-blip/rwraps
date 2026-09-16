package lab.arl.target.di

import android.content.Context
import lab.arl.target.BuildConfig
import lab.arl.target.auth.DeviceAuthenticator
import lab.arl.target.auth.EncryptedTokenStore
import lab.arl.target.auth.TokenStore
import lab.arl.target.backup.BackupRepository
import lab.arl.target.device.DeviceProfileFactory
import lab.arl.target.device.DeviceRepository
import lab.arl.target.media.ScreenCaptureController
import lab.arl.target.media.WebRtcClient
import lab.arl.target.network.LabWebSocket
import lab.arl.target.network.createApi
import lab.arl.target.network.createOkHttp
import lab.arl.target.network.DebugApiOverrideStore
import lab.arl.target.network.DebugApiRewriteInterceptor
import lab.arl.target.network.initialDebugApiOverride
import lab.arl.target.network.parseOverrideHttpUrl
import lab.arl.target.pairing.PairingApiEndpoint
import lab.arl.target.pairing.PairingRepository
import lab.arl.target.session.SessionCoordinator
import lab.arl.target.session.SessionRepository
import lab.arl.target.telemetry.TelemetryCollector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class AppContainer(context: Context) {
    private val appContext = context.applicationContext
    val tokenStore: TokenStore = EncryptedTokenStore(appContext)
    val compiledApiBaseUrl: String = BuildConfig.API_BASE_URL.trimEnd('/')
    private val debugApiStore = DebugApiOverrideStore(appContext)
    private val debugOverrideUrl = AtomicReference<HttpUrl?>(null)
    var effectiveApiBaseUrl: String = initialDebugApiOverride(debugApiStore, compiledApiBaseUrl)
        private set

    init {
        if (BuildConfig.DEBUG && effectiveApiBaseUrl != compiledApiBaseUrl) {
            debugOverrideUrl.set(parseOverrideHttpUrl(effectiveApiBaseUrl))
        }
    }

    private val rewrite = DebugApiRewriteInterceptor(debugOverrideUrl)

    private val refreshClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .addInterceptor(rewrite)
        .build()
    private val refreshApi = createApi(compiledApiBaseUrl, refreshClient)
    val authenticator = DeviceAuthenticator(tokenStore) { refreshApi }

    private val okHttp = createOkHttp(
        tokenProvider = { tokenStore.load()?.accessToken },
        authenticator = authenticator,
        debug = BuildConfig.DEBUG,
        extraInterceptor = rewrite
    )
    val api = createApi(compiledApiBaseUrl, okHttp)
    val pairingRepository = PairingRepository(api, tokenStore)
    val deviceRepository = DeviceRepository(api)
    val sessionRepository = SessionRepository(api)
    val backupRepository = BackupRepository(appContext, api)
    val profileFactory = DeviceProfileFactory(appContext)
    val telemetry = TelemetryCollector(appContext)
    val webRtc = WebRtcClient(appContext)
    val capture = ScreenCaptureController(appContext, webRtc.eglBase)
    private val wsScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val webSocket = LabWebSocket(okHttp, wsScope)
    val coordinator = SessionCoordinator(
        appContext = appContext,
        apiBaseUrl = effectiveApiBaseUrl,
        applyPairingApi = ::applyPairingApi,
        persistDebugApi = ::persistDebugApi,
        resetDebugApi = ::resetDebugApi,
        tokenStore = tokenStore,
        pairingRepository = pairingRepository,
        deviceRepository = deviceRepository,
        sessionRepository = sessionRepository,
        profileFactory = profileFactory,
        ws = webSocket,
        webRtc = webRtc,
        capture = capture,
        telemetry = telemetry
    )

    private fun applyPairingApi(fromUri: String?): String {
        val next = PairingApiEndpoint.resolve(compiledApiBaseUrl, fromUri, BuildConfig.DEBUG)
        effectiveApiBaseUrl = next
        debugOverrideUrl.set(
            if (!BuildConfig.DEBUG || next.equals(compiledApiBaseUrl, ignoreCase = true)) {
                null
            } else {
                parseOverrideHttpUrl(next)
            }
        )
        return next
    }

    private fun persistDebugApi() {
        if (!BuildConfig.DEBUG) {
            return
        }
        if (effectiveApiBaseUrl.equals(compiledApiBaseUrl, ignoreCase = true)) {
            debugApiStore.clear()
        } else {
            debugApiStore.save(effectiveApiBaseUrl)
        }
    }

    private fun resetDebugApi() {
        debugApiStore.clear()
        effectiveApiBaseUrl = compiledApiBaseUrl
        debugOverrideUrl.set(null)
    }
}
