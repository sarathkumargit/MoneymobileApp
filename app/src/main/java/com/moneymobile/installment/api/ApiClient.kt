package com.moneymobile.installment.api

import android.content.Context
import com.google.gson.GsonBuilder
import com.google.gson.JsonDeserializer
import com.moneymobile.installment.BuildConfig
import com.moneymobile.installment.model.CommandType
import com.moneymobile.installment.util.AppPreferences
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

object ApiClient {

    private val loggingInterceptor = HttpLoggingInterceptor().apply {
        level = if (BuildConfig.DEBUG)
            HttpLoggingInterceptor.Level.BODY
        else
            HttpLoggingInterceptor.Level.NONE
    }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .addInterceptor(loggingInterceptor)
        .addInterceptor { chain ->
            val request = chain.request().newBuilder()
                .addHeader("X-App-Version", BuildConfig.VERSION_NAME)
                .addHeader("X-Platform", "android")
                .build()
            chain.proceed(request)
        }
        .build()

    // Any command this app build doesn't recognise (e.g. a newer server command) becomes NONE
    // instead of null, so the phone safely ignores it rather than crashing.
    private val gson = GsonBuilder()
        .registerTypeAdapter(CommandType::class.java, JsonDeserializer { json, _, _ ->
            try { CommandType.valueOf(json.asString) } catch (e: Exception) { CommandType.NONE }
        })
        .create()

    @Volatile private var appContext: Context? = null
    @Volatile private var currentUrl: String? = null
    @Volatile private var cached: ApiService? = null

    /** Called once from MoneyMobileApp so the client can read the saved server address. */
    fun init(context: Context) { appContext = context.applicationContext }

    /** The server address in use: the one saved by the setup file, else the one built into the app. */
    fun baseUrl(): String {
        val saved = appContext?.let { runCatching { AppPreferences.getServerUrl(it) }.getOrNull() }
        return (saved?.takeIf { it.isNotBlank() } ?: BuildConfig.BASE_URL).trimEnd('/') + "/"
    }

    // Rebuilt automatically if the setup file hands over a new server address.
    val service: ApiService
        get() {
            val url = baseUrl()
            cached?.let { if (currentUrl == url) return it }
            synchronized(this) {
                cached?.let { if (currentUrl == url) return it }
                val svc = Retrofit.Builder()
                    .baseUrl(url)
                    .client(httpClient)
                    .addConverterFactory(GsonConverterFactory.create(gson))
                    .build()
                    .create(ApiService::class.java)
                currentUrl = url
                cached = svc
                return svc
            }
        }
}
