package com.v2ray.ang.handler

import com.v2ray.ang.AppConfig
import com.v2ray.ang.dto.IPAPIInfo
import com.v2ray.ang.dto.UrlContentRequest
import com.v2ray.ang.util.HttpUtil
import com.v2ray.ang.util.JsonUtil
import com.v2ray.ang.util.LogUtil
import java.io.IOException
import java.net.Authenticator
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.PasswordAuthentication
import java.net.Proxy
import java.net.Socket
import java.net.URL
import java.net.UnknownHostException
import kotlin.random.Random

object SpeedtestManager {

    data class RemoteEndpointInfo(
        val country: String?,
        val ipAddress: String?,
    )

    /** Result of a speed test leg (download or upload). */
    data class SpeedTestResult(
        val mbps: Double,
        val bytesTransferred: Long,
        val elapsedMs: Long,
    )

    /**
     * Measures TCP connect time to [url]:[port].
     * Returns elapsed ms, or -1 on failure.
     */
    fun socketConnectTime(url: String, port: Int, timeoutMs: Int = 1500): Long {
        var socket: Socket? = null
        val start = System.currentTimeMillis()
        return try {
            socket = Socket()
            socket.connect(InetSocketAddress(url, port), timeoutMs)
            System.currentTimeMillis() - start
        } catch (e: UnknownHostException) {
            LogUtil.e(AppConfig.TAG, "Unknown host: $url", e)
            -1
        } catch (e: IOException) {
            LogUtil.e(AppConfig.TAG, "socketConnectTime IOException: ${e.message}")
            -1
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Failed to establish socket connection to $url:$port", e)
            -1
        } finally {
            try { if (socket?.isClosed == false) socket.close() } catch (_: IOException) {}
        }
    }

    fun getRemoteIPInfo(): RemoteEndpointInfo? {
        val url = MmkvManager.decodeSettingsString(AppConfig.PREF_IP_API_URL)
            .takeIf { !it.isNullOrBlank() } ?: AppConfig.IP_API_URL

        val proxyUsername = SettingsManager.getSocksUsername()
        val proxyPassword = SettingsManager.getSocksPassword()
        val httpPort = SettingsManager.getHttpPort()
        if (httpPort == 0) return null
        val content = HttpUtil.getUrlContent(
            UrlContentRequest(
                url = url,
                timeout = 5000,
                httpPort = httpPort,
                proxyUsername = proxyUsername,
                proxyPassword = proxyPassword
            )
        ) ?: return null
        val ipInfo = JsonUtil.fromJsonSafe(content, IPAPIInfo::class.java) ?: return null

        val ip = listOf(ipInfo.ip, ipInfo.clientIp, ipInfo.ip_addr, ipInfo.query)
            .firstOrNull { !it.isNullOrBlank() }
        val country = listOf(ipInfo.country_code, ipInfo.country, ipInfo.countryCode, ipInfo.location?.country_code)
            .firstOrNull { !it.isNullOrBlank() }

        return RemoteEndpointInfo(country = country, ipAddress = ip)
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private fun buildProxy(): Pair<Proxy, Boolean> {
        val socksPort = SettingsManager.getSocksPort()
        val proxy = if (socksPort != 0)
            Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", socksPort))
        else
            Proxy.NO_PROXY
        return Pair(proxy, socksPort != 0)
    }

    private fun setupAuth(proxyUsername: String?, proxyPassword: String?) {
        if (!proxyUsername.isNullOrBlank()) {
            Authenticator.setDefault(object : Authenticator() {
                override fun getPasswordAuthentication() =
                    PasswordAuthentication(proxyUsername, (proxyPassword ?: "").toCharArray())
            })
        }
    }

    /**
     * Opens an HTTP GET connection to [url] through [proxy].
     * Returns a connected [HttpURLConnection] with a 2xx response, or null on failure.
     * The caller is responsible for disconnecting.
     */
    private fun openGetConnection(url: String, proxy: Proxy): HttpURLConnection? {
        return try {
            val conn = (URL(url).openConnection(proxy) as HttpURLConnection).apply {
                connectTimeout = AppConfig.SPEED_TEST_CONNECT_TIMEOUT_MS
                readTimeout    = AppConfig.SPEED_TEST_READ_TIMEOUT_MS
                requestMethod  = "GET"
                // Without this, HttpURLConnection's keep-alive pooling can silently keep
                // draining/receiving the rest of the response body in the background after we
                // stop reading (to make the socket reusable) — which is exactly why data kept
                // moving after the test reported a result. "Connection: close" stops that.
                setRequestProperty("Connection", "close")
            }
            conn.connect()
            if (conn.responseCode !in 200..299) {
                LogUtil.e(AppConfig.TAG, "GET $url → ${conn.responseCode}")
                conn.disconnect()
                null
            } else conn
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "openGetConnection($url) failed: ${e.message}")
            null
        }
    }

    // ── Download ─────────────────────────────────────────────────────────────

    /**
     * Measures download throughput through the local SOCKS proxy.
     *
     * Tries [AppConfig.SPEED_TEST_DL_PRIMARY] first; if no data arrives within
     * [AppConfig.SPEED_TEST_FALLBACK_TRIGGER_MS] ms, switches to
     * [AppConfig.SPEED_TEST_DL_FALLBACK].
     *
     * Stops at whichever comes first:
     *  - [AppConfig.SPEED_TEST_MAX_BYTES] transferred
     *  - [AppConfig.SPEED_TEST_DURATION_MS] elapsed
     *  - connection ends
     */
    fun testDownloadSpeed(): SpeedTestResult? {
        val socksPort = SettingsManager.getSocksPort()
        if (socksPort == 0) return null

        val proxyUsername = SettingsManager.getSocksUsername()
        val proxyPassword = SettingsManager.getSocksPassword()
        setupAuth(proxyUsername, proxyPassword)

        val proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", socksPort))

        try {
            val urls = listOf(
                AppConfig.SPEED_TEST_DL_PRIMARY,
                "${AppConfig.SPEED_TEST_DL_FALLBACK}?bytes=${AppConfig.SPEED_TEST_MAX_BYTES}"
            )
            for ((index, url) in urls.withIndex()) {
                var conn: HttpURLConnection? = null
                try {
                    conn = openGetConnection(url, proxy) ?: continue

                    val buffer  = ByteArray(64 * 1024)
                    var total   = 0L
                    val start   = System.currentTimeMillis()
                    var gotFirstByte = false

                    conn.inputStream.use { input ->
                        while (true) {
                            val now = System.currentTimeMillis()
                            val elapsed = now - start

                            // Fallback trigger: no data in first 3 s → try next URL
                            if (!gotFirstByte && elapsed >= AppConfig.SPEED_TEST_FALLBACK_TRIGGER_MS) {
                                LogUtil.e(AppConfig.TAG, "DL primary timeout (no data in ${AppConfig.SPEED_TEST_FALLBACK_TRIGGER_MS}ms), switching to fallback")
                                break
                            }

                            // Hard caps
                            if (elapsed >= AppConfig.SPEED_TEST_DURATION_MS) break
                            if (total >= AppConfig.SPEED_TEST_MAX_BYTES) break

                            val n = input.read(buffer)
                            if (n <= 0) break
                            total += n
                            gotFirstByte = true
                        }
                    }

                    val elapsed = System.currentTimeMillis() - start
                    if (!gotFirstByte) continue   // no data — try fallback

                    if (elapsed <= 0 || total <= 0) return null
                    val mbps = (total * 8.0 / (elapsed / 1000.0)) / 1_000_000.0
                    return SpeedTestResult(mbps = mbps, bytesTransferred = total, elapsedMs = elapsed)

                } catch (e: Exception) {
                    LogUtil.e(AppConfig.TAG, "testDownloadSpeed[$index] failed: ${e.message}")
                    if (index == urls.lastIndex) return null
                } finally {
                    conn?.disconnect()
                }
            }
            return null
        } finally {
            if (!SettingsManager.getSocksUsername().isNullOrBlank()) Authenticator.setDefault(null)
        }
    }

    // ── Upload ───────────────────────────────────────────────────────────────

    /**
     * Measures upload throughput through the local SOCKS proxy.
     *
     * Streams a repeating random buffer as a chunked POST body until
     * [AppConfig.SPEED_TEST_MAX_BYTES] or [AppConfig.SPEED_TEST_DURATION_MS]
     * is reached — whichever comes first.
     *
     * Tries [AppConfig.SPEED_TEST_UL_PRIMARY] first; falls back to
     * [AppConfig.SPEED_TEST_UL_FALLBACK] if no bytes are acknowledged within
     * [AppConfig.SPEED_TEST_FALLBACK_TRIGGER_MS].
     */
    fun testUploadSpeed(): SpeedTestResult? {
        val socksPort = SettingsManager.getSocksPort()
        if (socksPort == 0) return null

        val proxyUsername = SettingsManager.getSocksUsername()
        val proxyPassword = SettingsManager.getSocksPassword()
        setupAuth(proxyUsername, proxyPassword)

        val proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", socksPort))

        try {
            val urls = listOf(AppConfig.SPEED_TEST_UL_PRIMARY, AppConfig.SPEED_TEST_UL_FALLBACK)
            for ((index, url) in urls.withIndex()) {
                var conn: HttpURLConnection? = null
                try {
                    conn = (URL(url).openConnection(proxy) as HttpURLConnection).apply {
                        connectTimeout = AppConfig.SPEED_TEST_CONNECT_TIMEOUT_MS
                        readTimeout    = AppConfig.SPEED_TEST_READ_TIMEOUT_MS
                        requestMethod  = "POST"
                        doOutput       = true
                        // Chunked transfer — no fixed Content-Length, so no 8 MB pre-allocation
                        setChunkedStreamingMode(64 * 1024)
                        setRequestProperty("Content-Type", "application/octet-stream")
                        // Disables connection reuse so nothing keeps pushing/draining bytes for
                        // this socket once we're done with it (see openGetConnection() comment).
                        setRequestProperty("Connection", "close")
                    }
                    conn.connect()

                    val buffer = ByteArray(64 * 1024).also { Random.nextBytes(it) }
                    var total     = 0L
                    val start     = System.currentTimeMillis()
                    var connected = false
                    var stalled   = false

                    val output = conn.outputStream
                    try {
                        while (true) {
                            val now     = System.currentTimeMillis()
                            val elapsed = now - start

                            // Fallback trigger: nothing accepted by the local socket in 3s
                            if (!connected && elapsed >= AppConfig.SPEED_TEST_FALLBACK_TRIGGER_MS) {
                                LogUtil.e(AppConfig.TAG, "UL primary stalled before first write, switching to fallback")
                                stalled = true
                                break
                            }
                            if (elapsed >= AppConfig.SPEED_TEST_DURATION_MS) break
                            if (total  >= AppConfig.SPEED_TEST_MAX_BYTES)    break

                            val chunk = (AppConfig.SPEED_TEST_MAX_BYTES - total)
                                .coerceAtMost(buffer.size.toLong()).toInt()
                            output.write(buffer, 0, chunk)
                            output.flush()
                            total    += chunk
                            connected = true
                        }

                        // output.write()/flush() only prove the bytes reached the *local* socket
                        // buffer, not that they crossed the link — that's why the reported speed
                        // was higher than the real line speed. Closing the stream sends the final
                        // chunk terminator, and reading the response code blocks until the server
                        // has actually received the entire body and answered. Only at that point
                        // is "total bytes in `elapsed` ms" a real, confirmed throughput figure.
                        output.close()

                        if (stalled || !connected) {
                            if (index == urls.lastIndex) return null else continue
                        }

                        val responseCode = conn.responseCode
                        val elapsed = System.currentTimeMillis() - start

                        if (responseCode !in 200..299) {
                            LogUtil.e(AppConfig.TAG, "UL $url ack → $responseCode")
                            if (index == urls.lastIndex) return null else continue
                        }
                        if (elapsed <= 0 || total <= 0) {
                            if (index == urls.lastIndex) return null else continue
                        }

                        val mbps = (total * 8.0 / (elapsed / 1000.0)) / 1_000_000.0
                        return SpeedTestResult(mbps = mbps, bytesTransferred = total, elapsedMs = elapsed)

                    } catch (e: IOException) {
                        // Server closed early, or the ack never arrived within readTimeout — we
                        // can't confirm real throughput, so this attempt is a failure rather than
                        // a guess. Try the fallback URL instead of reporting a made-up number.
                        LogUtil.e(AppConfig.TAG, "testUploadSpeed[$index] IO: ${e.message}")
                        if (index == urls.lastIndex) return null
                    }

                } catch (e: Exception) {
                    LogUtil.e(AppConfig.TAG, "testUploadSpeed[$index] failed: ${e.message}")
                    if (index == urls.lastIndex) return null
                } finally {
                    try { conn?.disconnect() } catch (_: Exception) {}
                }
            }
            return null
        } finally {
            if (!SettingsManager.getSocksUsername().isNullOrBlank()) Authenticator.setDefault(null)
        }
    }
}
