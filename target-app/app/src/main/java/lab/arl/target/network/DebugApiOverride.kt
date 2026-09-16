package lab.arl.target.network

import android.content.Context
import lab.arl.target.BuildConfig
import lab.arl.target.pairing.PairingApiEndpoint
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.concurrent.atomic.AtomicReference

class DebugApiOverrideStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("arl_debug_api", Context.MODE_PRIVATE)

    fun load(): String? = prefs.getString(KEY, null)?.trim()?.trimEnd('/')?.takeIf { it.isNotEmpty() }

    fun save(url: String) {
        prefs.edit().putString(KEY, url.trim().trimEnd('/')).apply()
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    private companion object {
        const val KEY = "api_base"
    }
}

class DebugApiRewriteInterceptor(
    private val override: AtomicReference<HttpUrl?>
) : okhttp3.Interceptor {
    override fun intercept(chain: okhttp3.Interceptor.Chain): okhttp3.Response {
        val target = override.get() ?: return chain.proceed(chain.request())
        val url = chain.request().url.newBuilder()
            .scheme(target.scheme)
            .host(target.host)
            .port(target.port)
            .build()
        return chain.proceed(chain.request().newBuilder().url(url).build())
    }
}

fun parseOverrideHttpUrl(base: String): HttpUrl? {
    val withSlash = if (base.endsWith("/")) base else "$base/"
    return withSlash.toHttpUrlOrNull()
}

fun initialDebugApiOverride(store: DebugApiOverrideStore, compiledBase: String): String {
    if (!BuildConfig.DEBUG) return compiledBase
    val saved = store.load() ?: return compiledBase
    return PairingApiEndpoint.resolve(compiledBase, saved, debug = true)
}
