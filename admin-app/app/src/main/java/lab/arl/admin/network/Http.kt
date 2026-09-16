package lab.arl.admin.network

import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import okhttp3.Authenticator
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import java.util.concurrent.TimeUnit

fun createOkHttp(tokenProvider: () -> String?, authenticator: Authenticator, debug: Boolean): OkHttpClient {
    val logging = HttpLoggingInterceptor().apply {
        level = if (debug) HttpLoggingInterceptor.Level.BASIC else HttpLoggingInterceptor.Level.NONE
        redactHeader("Authorization")
        redactHeader("Cookie")
    }
    return OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            val req = chain.request()
            val websocket = req.header("Upgrade").equals("websocket", ignoreCase = true)
            val b = req.newBuilder()
            if (!websocket) {
                if (!(req.method == "GET" && req.url.encodedPath.contains("/chunk"))) {
                    b.header("Accept", "application/json")
                }
                val token = tokenProvider()
                if (!token.isNullOrBlank() && req.header("Authorization") == null) {
                    b.header("Authorization", "Bearer $token")
                }
            }
            chain.proceed(b.build())
        }
        .addInterceptor(ErrorBodyInterceptor())
        .authenticator(authenticator)
        .addInterceptor(logging)
        .build()
}

fun createApi(baseUrl: String, client: OkHttpClient): AdminApi {
    val root = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"
    return Retrofit.Builder()
        .baseUrl(root)
        .client(client)
        .addConverterFactory(appJson.asConverterFactory("application/json".toMediaType()))
        .build()
        .create(AdminApi::class.java)
}

class ErrorBodyInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): okhttp3.Response {
        val request = chain.request()
        val websocket = request.header("Upgrade").equals("websocket", ignoreCase = true)
        val response = chain.proceed(request)
        if (websocket || response.code == 101 || response.isSuccessful || response.code == 401) {
            return response
        }
        val raw = response.body?.string().orEmpty()
        val parsed = runCatching { appJson.decodeFromString(ApiErrorBody.serializer(), raw) }.getOrNull()
        throw ApiException(
            response.code,
            parsed?.error?.code ?: "HTTP_${response.code}",
            parsed?.error?.message ?: "Request failed (${response.code})"
        )
    }
}
