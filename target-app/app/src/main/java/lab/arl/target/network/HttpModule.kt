package lab.arl.target.network

import kotlinx.serialization.json.Json
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.concurrent.TimeUnit

fun createJson(): Json = appJson

fun createOkHttp(
    tokenProvider: () -> String?,
    authenticator: okhttp3.Authenticator,
    debug: Boolean,
    extraInterceptor: Interceptor? = null
): OkHttpClient {
    val logging = HttpLoggingInterceptor().apply {
        level = if (debug) HttpLoggingInterceptor.Level.BASIC else HttpLoggingInterceptor.Level.NONE
        redactHeader("Authorization")
        redactHeader("Cookie")
    }
    return OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .apply { extraInterceptor?.let { addInterceptor(it) } }
        .addInterceptor { chain ->
            val original = chain.request()
            val websocket = original.header("Upgrade").equals("websocket", ignoreCase = true)
            val builder = original.newBuilder()
            if (!websocket) {
                builder.header("Accept", "application/json")
                val token = tokenProvider()
                if (!token.isNullOrBlank() && original.header("Authorization") == null) {
                    builder.header("Authorization", "Bearer $token")
                }
            }
            chain.proceed(builder.build())
        }
        .addInterceptor(ErrorBodyInterceptor())
        .authenticator(authenticator)
        .addInterceptor(logging)
        .build()
}

fun createApi(baseUrl: String, client: OkHttpClient): TargetApi {
    val root = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"
    return Retrofit.Builder()
        .baseUrl(root)
        .client(client)
        .addConverterFactory(appJson.asConverterFactory("application/json".toMediaType()))
        .build()
        .create(TargetApi::class.java)
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
        val code = parsed?.error?.code ?: "HTTP_${response.code}"
        val message = parsed?.error?.message ?: response.message.ifBlank { "Request failed (${response.code})" }
        throw ApiException(response.code, code, message)
    }
}
