package com.cloudchat.utils

import android.util.Log
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

object NetworkUtils {
    @Volatile
    var currentAuth: String? = null

    fun getUnsafeOkHttpClient(): OkHttpClient.Builder {
        try {
            val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
            })

            val sslContext = SSLContext.getInstance("TLS")
            sslContext.init(null, trustAllCerts, SecureRandom())

            val authInterceptor = Interceptor { chain ->
                val request = chain.request()
                val url = request.url.toString()
                
                val authenticatedRequest = if (currentAuth != null) {
                    Log.d("NetworkUtils", "Adding Auth to request: $url")
                    request.newBuilder()
                        .header("Authorization", currentAuth!!)
                        .build()
                } else {
                    Log.d("NetworkUtils", "No Auth available for request: $url")
                    request
                }
                
                val response = chain.proceed(authenticatedRequest)
                Log.d("NetworkUtils", "Response for $url -> Code: ${response.code}")
                response
            }

            return OkHttpClient.Builder()
                .sslSocketFactory(sslContext.socketFactory, trustAllCerts[0] as X509TrustManager)
                .hostnameVerifier { _, _ -> true }
                .addInterceptor(authInterceptor)
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
        } catch (e: Exception) {
            Log.e("NetworkUtils", "Failed to create unsafe client", e)
            return OkHttpClient.Builder()
        }
    }
}
