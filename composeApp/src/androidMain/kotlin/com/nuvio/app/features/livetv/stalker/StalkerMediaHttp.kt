package com.nuvio.app.features.livetv.stalker

import com.nuvio.vpn.android.VpnHttpClient
import com.nuvio.vpn.android.VpnPlaybackProtection
import okhttp3.Call
import okhttp3.OkHttpClient
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Strict TLS, no URL breadcrumbs, and account headers selected separately for every hop. */
internal object StalkerMediaHttp {
    fun calls(policy: StalkerMediaPolicy): Call.Factory {
        (policy.platformTransport.value as? Call.Factory)?.let { return it }
        val created = VpnHttpClient.wrap(client(policy))
        return if (policy.platformTransport.compareAndSet(null, created)) created
        else policy.platformTransport.value as Call.Factory
    }

    fun client(policy: StalkerMediaPolicy): OkHttpClient {
        // A redirect may upgrade to HTTPS, but that individual call must never downgrade.
        // Independent HTTPS key requests do not change an HTTP master's segment transport.
        val secureCalls = java.util.Collections.synchronizedMap(java.util.WeakHashMap<Call, Boolean>())
        return OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .addNetworkInterceptor { chain ->
            val request = chain.request()
            synchronized(secureCalls) {
                if (request.url.isHttps) secureCalls[chain.call()] = true
                else if (secureCalls[chain.call()] == true) throw IOException("A Stalker media redirect cannot downgrade HTTPS.")
            }
            VpnPlaybackProtection.beforeRequest(request.url.toString()) { chain.call().isCanceled() || Thread.currentThread().isInterrupted }
            val headers = try { policy.headersFor(request.url.toString()) }
            catch (_: Exception) { throw IOException("The Stalker source or VPN route changed. Reopen the stream.") }
            val safe = request.newBuilder().removeHeader("Authorization").removeHeader("Cookie")
                .removeHeader("SN").removeHeader("X-User-Agent")
                .removeHeader("Origin").removeHeader("Referer")
                .header("User-Agent", "Mozilla/5.0 (QtEmbedded; U; Linux; C) AppleWebKit/533.3 (KHTML, like Gecko) MAG250 stbapp ver: 2 rev: 250 Safari/533.3")
            headers.forEach { (name, value) -> safe.header(name, value) }
            chain.proceed(safe.build())
        }
        .build()
    }
}
