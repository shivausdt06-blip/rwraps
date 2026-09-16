package lab.arl.admin.di

import android.content.Context
import lab.arl.admin.BuildConfig
import lab.arl.admin.auth.AdminAuthenticator
import lab.arl.admin.auth.EncryptedAdminStore
import lab.arl.admin.media.ViewerWebRtc
import lab.arl.admin.network.AdminSocket
import lab.arl.admin.network.createApi
import lab.arl.admin.network.createOkHttp
import lab.arl.admin.backup.BackupTransfer
import lab.arl.admin.backup.LocalBackupStore
import lab.arl.admin.session.LabCoordinator
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

class AppContainer(context: Context) {
    private val app = context.applicationContext
    val store = EncryptedAdminStore(app)
    val apiBase = BuildConfig.API_BASE_URL.trimEnd('/')
    private val refreshHttp = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()
    private val refreshApi = createApi(apiBase, refreshHttp)
    val authenticator = AdminAuthenticator(store) { refreshApi }
    val http = createOkHttp({ store.load()?.accessToken }, authenticator, BuildConfig.DEBUG)
    val api = createApi(apiBase, http)
    val webrtc = ViewerWebRtc(app)
    private val wsScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val socket = AdminSocket(http, wsScope)
    val backupStore = LocalBackupStore(File(app.filesDir, "backups"))
    val backupTransfer = BackupTransfer(api, backupStore)
    val lab = LabCoordinator(app, apiBase, api, store, socket, webrtc, backupStore, backupTransfer)
}
