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

    private const val BASE_URL = "http://10.0.2.2:8080/"

    private lateinit var tokenManager: TokenManager
    private var retrofit: Retrofit? = null
    private var api: SportDayApi? = null

    fun initialize(context: Context) {
        tokenManager = TokenManager(context)
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
                .baseUrl(BASE_URL)
                .client(client)
                .addConverterFactory(GsonConverterFactory.create())
                .build()
        }
        return retrofit!!
    }
}
