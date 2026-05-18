package com.torikomi.browser

import okhttp3.ConnectionSpec
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.TlsVersion
import org.brotli.dec.BrotliInputStream
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.UnknownHostException
import java.security.KeyStore
import java.util.concurrent.ConcurrentHashMap
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
 *
 * It also installs a [FallbackDns] resolver that retries DNS lookups via
 * DNS-over-HTTPS when the system resolver fails (common when ISPs block
 * downloader hostnames at the DNS level, e.g. Internet Positif in Indonesia).
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

        // Install DoH-fallback DNS resolver. The bootstrap client used internally
        // is shared (lazy singleton) so we don't create a new client per extension call.
        builder.dns(FallbackDns)

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

/**
 * DNS resolver that first asks the system resolver, then falls back to
 * DNS-over-HTTPS using public resolvers (Cloudflare, Google) addressed by
 * literal IP. Using literal IPs means the DoH lookup itself never triggers
 * a system DNS query, so it works even when ISP DNS is fully blocked.
 *
 * Resolved addresses are cached briefly (5 min) to keep request latency low.
 */
private object FallbackDns : Dns {

    private const val CACHE_TTL_MS = 5L * 60L * 1000L
    private const val DOH_TIMEOUT_MS = 5_000L

    private data class Entry(val addresses: List<InetAddress>, val expiresAt: Long)

    private val cache = ConcurrentHashMap<String, Entry>()

    // Cloudflare and Google JSON DoH endpoints. Both serve their TLS cert with
    // the IP listed in the SAN, so HTTPS works when accessed by IP directly.
    private val dohEndpoints = listOf(
        "https://1.1.1.1/dns-query",
        "https://1.0.0.1/dns-query",
        "https://8.8.8.8/resolve",
        "https://8.8.4.4/resolve"
    )

    private val bootstrapClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(DOH_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .readTimeout(DOH_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .writeTimeout(DOH_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            // Use the system DNS for bootstrap (it only ever resolves IP literals,
            // which the system DNS handles without a network query, so no recursion).
            .dns(Dns.SYSTEM)
            .build()
    }

    override fun lookup(hostname: String): List<InetAddress> {
        // Cached?
        val now = System.currentTimeMillis()
        cache[hostname]?.let { entry ->
            if (entry.expiresAt > now && entry.addresses.isNotEmpty()) {
                return entry.addresses
            }
        }

        // Try system resolver first
        val systemError: UnknownHostException = try {
            val sys = Dns.SYSTEM.lookup(hostname)
            if (sys.isNotEmpty()) {
                cache[hostname] = Entry(sys, now + CACHE_TTL_MS)
                return sys
            }
            UnknownHostException("System DNS returned empty result for $hostname")
        } catch (e: UnknownHostException) {
            e
        } catch (e: Exception) {
            // Wrap other failures so we still attempt the DoH fallback below
            UnknownHostException("System DNS failed for $hostname: ${e.message}")
        }

        // Fallback: DoH lookup via public resolvers
        val doh = runCatching { dohLookup(hostname) }.getOrDefault(emptyList())
        if (doh.isNotEmpty()) {
            cache[hostname] = Entry(doh, now + CACHE_TTL_MS)
            return doh
        }

        throw systemError
    }

    private fun dohLookup(hostname: String): List<InetAddress> {
        // Query A and AAAA in sequence, stop early when we have answers.
        val merged = mutableListOf<InetAddress>()
        for (endpoint in dohEndpoints) {
            for (type in listOf("A", "AAAA")) {
                val url = "$endpoint?name=${hostname}&type=$type"
                val req = Request.Builder()
                    .url(url)
                    .header("Accept", "application/dns-json")
                    .header("User-Agent", "Torikomi/1.0")
                    .build()

                runCatching {
                    bootstrapClient.newCall(req).execute().use { resp ->
                        if (!resp.isSuccessful) return@use
                        val body = resp.body?.string() ?: return@use
                        val json = JSONObject(body)
                        val answers = json.optJSONArray("Answer") ?: return@use
                        for (i in 0 until answers.length()) {
                            val a = answers.optJSONObject(i) ?: continue
                            // RFC1035 record types: 1 = A, 28 = AAAA, 5 = CNAME
                            val recType = a.optInt("type")
                            if (recType != 1 && recType != 28) continue
                            val data = a.optString("data").orEmpty().trim()
                            if (data.isEmpty()) continue
                            // data is an IP literal; InetAddress.getByName won't trigger DNS for it
                            runCatching { InetAddress.getByName(data) }
                                .getOrNull()
                                ?.let { merged.add(it) }
                        }
                    }
                }
            }
            if (merged.isNotEmpty()) return merged
        }
        return merged
    }
}
