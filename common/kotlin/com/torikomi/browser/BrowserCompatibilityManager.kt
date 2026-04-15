package com.torikomi.browser

import okhttp3.ConnectionSpec
import okhttp3.OkHttpClient
import okhttp3.TlsVersion
import java.security.KeyStore
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory

/**
 * Manager untuk mengkonfigurasi OkHttpClient agar kompatibel dengan browser modern
 * (Chrome/Firefox) dengan proper TLS dan cipher suite configuration.
 * 
 * Bukan merupakan bypass Cloudflare, hanya untuk memastikan request terlihat
 * seperti dari browser asli dengan header dan TLS configuration yang sesuai.
 */
object BrowserCompatibilityManager {

    fun createBrowserCompatibleOkHttpClient(
        connectTimeoutMs: Long = 30_000,
        readTimeoutMs: Long = 60_000,
        writeTimeoutMs: Long = 60_000
    ): OkHttpClient.Builder {
        val builder = OkHttpClient.Builder()
            .connectTimeout(connectTimeoutMs, TimeUnit.MILLISECONDS)
            .readTimeout(readTimeoutMs, TimeUnit.MILLISECONDS)
            .writeTimeout(writeTimeoutMs, TimeUnit.MILLISECONDS)

        // Configure SSLSocketFactory dengan proper certificate validation
        try {
            // Gunakan default TrustManagerFactory dengan system certificates
            val trustManagerFactory = TrustManagerFactory.getInstance(
                TrustManagerFactory.getDefaultAlgorithm()
            )
            trustManagerFactory.init(null as KeyStore?)
            val trustManagers = trustManagerFactory.trustManagers
            
            // Buat SSLContext dengan modern TLS versions
            val sslContext = SSLContext.getInstance("TLS")
            sslContext.init(null, trustManagers, null)
            
            // Configure dengan explicit cipher suites yang mirip dengan modern browsers
            val modernTlsSpec = ConnectionSpec.Builder(ConnectionSpec.MODERN_TLS)
                .tlsVersions(TlsVersion.TLS_1_2, TlsVersion.TLS_1_3)
                .cipherSuites(
                    okhttp3.CipherSuite.TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256,
                    okhttp3.CipherSuite.TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384,
                    okhttp3.CipherSuite.TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256,
                    okhttp3.CipherSuite.TLS_AES_128_GCM_SHA256,
                    okhttp3.CipherSuite.TLS_AES_256_GCM_SHA384,
                    okhttp3.CipherSuite.TLS_RSA_WITH_AES_128_GCM_SHA256
                )
                .build()
            
            builder.sslSocketFactory(sslContext.socketFactory, trustManagers.first() as javax.net.ssl.X509TrustManager)
            builder.connectionSpecs(listOf(modernTlsSpec, ConnectionSpec.CLEARTEXT))
            
        } catch (e: Exception) {
            // Fallback: gunakan default OkHttp TLS configuration
            e.printStackTrace()
        }

        return builder
    }
}
