package com.sportday.mobile.data.api

import android.content.Context
import com.sportday.mobile.data.repository.TokenManager
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking

object ApiClient {

    private lateinit var tokenManager: TokenManager
    private var retrofit: Retrofit? = null
    private var api: SportDayApi? = null
    private var currentBaseUrl: String = TokenManager.DEFAULT_BASE_URL

    fun initialize(context: Context) {
        tokenManager = TokenManager(context)
    }

    fun getBaseUrl(): String = currentBaseUrl

    fun resetRetrofit() {
        retrofit = null
        api = null
    }

    suspend fun setBaseUrl(url: String) {
        currentBaseUrl = url
        tokenManager.saveBaseUrl(url)
        resetRetrofit()
    }

    suspend fun loadBaseUrl() {
        currentBaseUrl = tokenManager.getBaseUrl()
        resetRetrofit()
    }

    fun getApi(): SportDayApi {
        if (api == null) {
            api = getRetrofit().create(SportDayApi::class.java)
        }
        return api!!
    }

    private fun getRetrofit(): Retrofit {
        if (retrofit == null) {
            val authInterceptor = Interceptor { chain ->
                val request = chain.request().newBuilder()
                val token = runBlocking { tokenManager.getToken() }
                if (token != null) {
                    request.addHeader("Authorization", "Bearer $token")
                }
                chain.proceed(request.build())
            }

            val loggingInterceptor = HttpLoggingInterceptor().apply {
                level = HttpLoggingInterceptor.Level.BODY
            }

            val client = OkHttpClient.Builder()
                .addInterceptor(authInterceptor)
                .addInterceptor(loggingInterceptor)
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .build()

            retrofit = Retrofit.Builder()
                .baseUrl(currentBaseUrl)
                .client(client)
                .addConverterFactory(GsonConverterFactory.create())
                .build()
        }
        return retrofit!!
    }
}
