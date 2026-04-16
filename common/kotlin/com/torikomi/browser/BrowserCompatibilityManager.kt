package com.torikomi.browser

import okhttp3.ConnectionSpec
import okhttp3.OkHttpClient
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.TlsVersion
import org.brotli.dec.BrotliInputStream
import java.io.ByteArrayOutputStream
import java.security.KeyStore
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory

/**
 * Configures OkHttpClient to be compatible with modern browsers (Chrome/Firefox)
 * with proper TLS and cipher suite configuration.
 *
 * This is not a Cloudflare bypass — it only ensures requests look like they
 * come from a real browser with matching headers and TLS settings.
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

        // Configure SSLSocketFactory with proper certificate validation
        try {
            // Use system default TrustManagerFactory
            val trustManagerFactory = TrustManagerFactory.getInstance(
                TrustManagerFactory.getDefaultAlgorithm()
            )
            trustManagerFactory.init(null as KeyStore?)
            val trustManagers = trustManagerFactory.trustManagers

            // Build SSLContext with modern TLS versions
            val sslContext = SSLContext.getInstance("TLS")
            sslContext.init(null, trustManagers, null)

            // Restrict to cipher suites used by modern browsers
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
            // Fallback to default OkHttp TLS configuration
            e.printStackTrace()
        }

        // Application interceptor: set Accept-Encoding + common browser headers.
        // Setting Accept-Encoding here disables OkHttp's automatic gzip decompression,
        // so we handle gzip and brotli manually in the network interceptor below.
        builder.addInterceptor { chain ->
            val req = chain.request().newBuilder()
                .header("Accept-Encoding", "gzip, deflate, br")
                .header("Accept-Language", "en-US,en;q=0.9")
                .header("Cache-Control", "no-cache")
                .header("Pragma", "no-cache")
                .build()
            chain.proceed(req)
        }

        // Network interceptor: transparently decompress gzip and brotli responses.
        builder.addNetworkInterceptor { chain ->
            val response = chain.proceed(chain.request())
            val encoding = response.header("Content-Encoding").orEmpty()
            when {
                encoding.equals("br", ignoreCase = true) -> {
                    try {
                        val body = response.body ?: return@addNetworkInterceptor response
                        val compressed = body.bytes()
                        val out = ByteArrayOutputStream()
                        BrotliInputStream(compressed.inputStream()).use { it.copyTo(out) }
                        response.newBuilder()
                            .body(out.toByteArray().toResponseBody(body.contentType()))
                            .removeHeader("Content-Encoding")
                            .build()
                    } catch (e: Exception) {
                        response
                    }
                }
                encoding.equals("gzip", ignoreCase = true) -> {
                    try {
                        val body = response.body ?: return@addNetworkInterceptor response
                        val compressed = body.bytes()
                        val out = ByteArrayOutputStream()
                        GZIPInputStream(compressed.inputStream()).use { it.copyTo(out) }
                        response.newBuilder()
                            .body(out.toByteArray().toResponseBody(body.contentType()))
                            .removeHeader("Content-Encoding")
                            .build()
                    } catch (e: Exception) {
                        response
                    }
                }
                else -> response
            }
        }

        return builder
    }
}
