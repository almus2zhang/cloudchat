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

    /**
     * Safe client that honors system certificates and Network Security Config.
     * Prevents packet capture by only trusting system-provided CAs.
     */
    fun getSafeOkHttpClient(): OkHttpClient.Builder {
        val authInterceptor = createAuthInterceptor()
        return OkHttpClient.Builder()
            .addInterceptor(authInterceptor)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
    }

    /**
     * Unsafe client that bypasses SSL verification.
     * Use only for self-built versions with non-standard certs.
     */
    fun getUnsafeOkHttpClient(): OkHttpClient.Builder {
        try {
            val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
            })

            val sslContext = SSLContext.getInstance("TLS")
            sslContext.init(null, trustAllCerts, SecureRandom())

            val authInterceptor = createAuthInterceptor()

            return OkHttpClient.Builder()
                .sslSocketFactory(sslContext.socketFactory, trustAllCerts[0] as X509TrustManager)
                .hostnameVerifier { _, _ -> true }
                .addInterceptor(authInterceptor)
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
        } catch (e: Exception) {
            Log.e("NetworkUtils", "Failed to create unsafe client", e)
            return getSafeOkHttpClient()
        }
    }

    private fun createAuthInterceptor() = Interceptor { chain ->
        val request = chain.request()
        val url = request.url.toString()
        Log.d("NetworkUtils", "Request: ${request.method} $url")
        
        val authenticatedRequest = if (currentAuth != null && request.header("Authorization") == null) {
            request.newBuilder()
                .header("Authorization", currentAuth!!)
                .build()
        } else {
            request
        }
        
        try {
            val response = chain.proceed(authenticatedRequest)
            Log.d("NetworkUtils", "Response: ${response.code} for $url")
            response
        } catch (e: Exception) {
            Log.e("NetworkUtils", "Request failed for $url", e)
            throw e
        }
    }
}
