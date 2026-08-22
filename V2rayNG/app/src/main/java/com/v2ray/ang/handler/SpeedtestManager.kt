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
import java.util.Timer
import java.util.TimerTask
import kotlin.random.Random

object SpeedtestManager {

    // Coroutine Job.cancel() does NOT interrupt blocking java.net I/O (connect/read/write) —
    // it only takes effect at suspension points, and testDownloadSpeed()/testUploadSpeed() are
    // plain blocking calls. So if the VPN is disconnected or the config is switched mid-test,
    // cancelling the coroutine alone leaves the socket happily reading/writing in the
    // background. This reference lets the caller forcibly abort the *actual* connection.
    @Volatile
    private var activeConnection: HttpURLConnection? = null

    /**
     * Immediately aborts whatever download/upload speed test attempt is currently in flight,
     * by disconnecting its underlying connection. Safe to call when nothing is running.
     * Call this whenever the VPN disconnects or the active config changes mid speed-test.
     */
    fun cancelActiveTest() {
        try { activeConnection?.disconnect() } catch (_: Exception) {}
    }

    data class RemoteEndpointInfo(
        val country: String?,
        val ipAddress: String?,
    )

    /** Result of a speed test leg (download or upload). Only mbps is actually consumed by
     * callers today — this used to also carry bytesTransferred/elapsedMs, but nothing read
     * them, so they were dropped to keep this to what's actually used. */
    data class SpeedTestResult(
        val mbps: Double,
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

    /**
     * Fetches the exit IP address and country code as seen by the remote server, by requesting
     * [AppConfig.IP_API_URL] (or the user-configured [AppConfig.PREF_IP_API_URL] override)
     * through the local HTTP proxy — so the result reflects the active VPN config's exit, not
     * the device's real network. Returns null if the local proxy isn't up or the response can't
     * be parsed into a known IP-info JSON shape.
     */
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
            activeConnection = conn
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
     * Once data starts flowing, throughput is checked once against
     * [AppConfig.SPEED_TEST_MIN_DOWNLOAD_MBPS] after [AppConfig.SPEED_TEST_MIN_SPEED_CHECK_MS] —
     * if it's under the floor, the whole test aborts immediately and returns null (no further
     * URL fallback), since that's a real answer ("too slow"), not a dead link.
     *
     * Otherwise stops at whichever comes first:
     *  - [AppConfig.SPEED_TEST_MAX_BYTES] transferred
     *  - [AppConfig.SPEED_TEST_DURATION_MS] elapsed
     *  - connection ends
     */
    fun testDownloadSpeed(onConnected: (() -> Unit)? = null): SpeedTestResult? {
        val socksPort = SettingsManager.getSocksPort()
        if (socksPort == 0) return null

        val proxyUsername = SettingsManager.getSocksUsername()
        val proxyPassword = SettingsManager.getSocksPassword()
        setupAuth(proxyUsername, proxyPassword)

        val proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", socksPort))

        // Fires onConnected exactly once, the moment real data starts flowing — no matter which
        // of the fallback URLs ends up being the one that works. Until this fires, the caller is
        // still in the "trying to reach a server" phase (UI: "Connecting..."); after it fires,
        // bytes are actually moving (UI: "Downloading...").
        var reportedConnected = false

        try {
            // Exactly two attempts for "Connecting...": Hetzner first, then Cloudflare as the
            // one fallback — each gets AppConfig.SPEED_TEST_FALLBACK_TRIGGER_MS (3 s) before
            // moving on. If both fail to produce data, the leg stops immediately and reports a
            // dash, so "Connecting..." can never sit on screen for more than ~6 s total. (No
            // further mini-server tiers — that cascade used to let this run far longer.)
            val urls = listOf(
                AppConfig.SPEED_TEST_DL_PRIMARY,
                "${AppConfig.SPEED_TEST_DL_FALLBACK}?bytes=${AppConfig.SPEED_TEST_MAX_BYTES}"
            )
            for ((index, url) in urls.withIndex()) {
                var conn: HttpURLConnection? = null
                // Captured BEFORE opening the connection so the "Connecting..." budget below
                // covers the *whole* attempt (TCP/TLS connect + wait for first byte), not 3 s of
                // connect on top of a separate 3 s wait for data — each server gets ~3 s all-in.
                val attemptStart = System.currentTimeMillis()
                try {
                    conn = openGetConnection(url, proxy)
                    if (conn == null) {
                        LogUtil.e(AppConfig.TAG, "testDownloadSpeed[$index] $url → connection failed, trying next")
                        continue
                    }

                    val buffer  = ByteArray(64 * 1024)
                    var total   = 0L
                    val start   = System.currentTimeMillis()
                    var gotFirstByte = false
                    var downloadPhaseStart = -1L
                    var minSpeedChecked = false
                    var tooSlow = false

                    conn.inputStream.use { input ->
                        while (true) {
                            val now = System.currentTimeMillis()
                            val elapsed = now - start

                            // Fallback trigger: measured from attemptStart (before connect), not
                            // from start (after connect) — so the whole "Connecting..." attempt
                            // for this server is capped at 3 s total, not 3 s connect + 3 s more.
                            if (!gotFirstByte && (now - attemptStart) >= AppConfig.SPEED_TEST_FALLBACK_TRIGGER_MS) {
                                LogUtil.e(AppConfig.TAG, "DL primary timeout (no data in ${AppConfig.SPEED_TEST_FALLBACK_TRIGGER_MS}ms of attempt), switching to fallback")
                                break
                            }

                            // Hard caps
                            if (elapsed >= AppConfig.SPEED_TEST_DURATION_MS) break
                            if (total >= AppConfig.SPEED_TEST_MAX_BYTES) break

                            // Sustained minimum-speed watchdog: once real data has started flowing
                            // (UI is showing "Downloading..."), throughput measured over the first
                            // SPEED_TEST_MIN_SPEED_CHECK_MS of that phase must clear
                            // SPEED_TEST_MIN_DOWNLOAD_MBPS. Checked once, at that single checkpoint
                            // — distinct from the stall check above, which only catches a dead link.
                            // A link that connects but trickles below the floor is a real (if
                            // useless) result, so this aborts the whole test outright rather than
                            // falling through to another URL.
                            if (gotFirstByte && !minSpeedChecked) {
                                val sinceStart = now - downloadPhaseStart
                                if (sinceStart >= AppConfig.SPEED_TEST_MIN_SPEED_CHECK_MS) {
                                    minSpeedChecked = true
                                    val currentMbps = (total * 8.0 / (sinceStart / 1000.0)) / 1_000_000.0
                                    if (currentMbps < AppConfig.SPEED_TEST_MIN_DOWNLOAD_MBPS) {
                                        LogUtil.e(AppConfig.TAG, "testDownloadSpeed[$index] $url → below ${AppConfig.SPEED_TEST_MIN_DOWNLOAD_MBPS} Mbps floor (${"%.2f".format(currentMbps)} Mbps after ${sinceStart}ms), aborting")
                                        tooSlow = true
                                        break
                                    }
                                }
                            }

                            val n = input.read(buffer)
                            if (n <= 0) break
                            total += n
                            if (!gotFirstByte) {
                                gotFirstByte = true
                                downloadPhaseStart = now
                            }
                            if (!reportedConnected) {
                                reportedConnected = true
                                onConnected?.invoke()
                            }
                        }
                    }

                    if (tooSlow) {
                        // Hard stop: a genuinely too-slow link is a final result (dash), not a
                        // reason to try another URL. The finally{} below disconnects immediately.
                        return null
                    }

                    val elapsed = System.currentTimeMillis() - start
                    if (!gotFirstByte) {
                        LogUtil.e(AppConfig.TAG, "testDownloadSpeed[$index] $url → no data received, trying next")
                        continue   // no data — try fallback
                    }

                    if (elapsed <= 0 || total <= 0) return null
                    val mbps = (total * 8.0 / (elapsed / 1000.0)) / 1_000_000.0
                    LogUtil.e(AppConfig.TAG, "testDownloadSpeed[$index] $url → OK: ${"%.1f".format(mbps)} Mbps ($total bytes / ${elapsed}ms)")
                    return SpeedTestResult(mbps = mbps)

                } catch (e: Exception) {
                    LogUtil.e(AppConfig.TAG, "testDownloadSpeed[$index] $url failed: ${e.javaClass.simpleName}: ${e.message}")
                    if (index == urls.lastIndex) return null
                } finally {
                    conn?.disconnect()
                    activeConnection = null
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
     * is reached — whichever comes first. A watchdog guarantees the whole attempt
     * (connect + write + wait-for-ack) can never exceed
     * [AppConfig.SPEED_TEST_UPLOAD_HARD_DEADLINE_MS], so this can never sit on
     * stuck forever regardless of what the server does.
     *
     * Once bytes start being accepted, throughput is checked once against
     * [AppConfig.SPEED_TEST_MIN_UPLOAD_MBPS] after [AppConfig.SPEED_TEST_MIN_SPEED_CHECK_MS] —
     * if it's under the floor, the whole test aborts immediately and returns null (no further
     * URL fallback), since that's a real answer ("too slow"), not a dead link.
     */
    fun testUploadSpeed(onConnected: (() -> Unit)? = null): SpeedTestResult? {
        val socksPort = SettingsManager.getSocksPort()
        if (socksPort == 0) return null

        val proxyUsername = SettingsManager.getSocksUsername()
        val proxyPassword = SettingsManager.getSocksPassword()
        setupAuth(proxyUsername, proxyPassword)

        val proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", socksPort))

        // Same one-shot signal as testDownloadSpeed: fires the moment the first chunk is
        // actually accepted by the socket (real upload started), regardless of which fallback
        // URL ends up succeeding.
        var reportedConnected = false

        try {
            // Exactly two attempts for "Connecting...": Cloudflare first, then the Previder
            // mirror as the one fallback — each gets AppConfig.SPEED_TEST_UPLOAD_STALL_TRIGGER_MS
            // (3 s) before moving on. If both fail, the leg stops immediately and reports a dash,
            // so "Connecting..." can never sit on screen for more than ~6 s total. (No further
            // mini-server tiers — that cascade used to let this run far longer.)
            val urls = listOf(AppConfig.SPEED_TEST_UL_PRIMARY, AppConfig.SPEED_TEST_UL_FALLBACK)
            for ((index, url) in urls.withIndex()) {
                var conn: HttpURLConnection? = null
                var watchdog: Timer? = null
                // Captured BEFORE opening the connection so the "Connecting..." budget below
                // covers the *whole* attempt (TCP/TLS connect + wait for first accepted write),
                // not 3 s of connect on top of a separate 3 s stall wait — each server gets ~3 s
                // all-in.
                val attemptStart = System.currentTimeMillis()
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
                    activeConnection = conn
                    LogUtil.e(AppConfig.TAG, "testUploadSpeed[$index] $url → connected, streaming body")

                    // Hard backstop: disconnect() from another thread interrupts any blocked
                    // write()/getResponseCode() call, forcing it to throw immediately instead of
                    // hanging. This is what guarantees the "Uploading..." state has an absolute ceiling.
                    val watchedConn = conn
                    watchdog = Timer(true).apply {
                        schedule(object : TimerTask() {
                            override fun run() {
                                LogUtil.e(AppConfig.TAG, "UL watchdog: hard deadline hit, aborting")
                                try { watchedConn.disconnect() } catch (_: Exception) {}
                            }
                        }, AppConfig.SPEED_TEST_UPLOAD_HARD_DEADLINE_MS)
                    }

                    val buffer = ByteArray(64 * 1024).also { Random.nextBytes(it) }
                    var total     = 0L
                    val start     = System.currentTimeMillis()
                    var connected = false
                    var stalled   = false
                    var uploadPhaseStart = -1L
                    var minSpeedChecked  = false
                    var tooSlow = false

                    val output = conn.outputStream
                    try {
                        while (true) {
                            val now     = System.currentTimeMillis()
                            val elapsed = now - start

                            // Fallback trigger: measured from attemptStart (before connect), not
                            // from start (after connect) — so the whole "Connecting..." attempt
                            // for this server, TCP connect included, is capped at 3 s total.
                            if (!connected && (now - attemptStart) >= AppConfig.SPEED_TEST_UPLOAD_STALL_TRIGGER_MS) {
                                LogUtil.e(AppConfig.TAG, "UL primary stalled before first write, switching to fallback")
                                stalled = true
                                break
                            }
                            if (elapsed >= AppConfig.SPEED_TEST_DURATION_MS) break
                            if (total  >= AppConfig.SPEED_TEST_MAX_BYTES)    break

                            // Sustained minimum-speed watchdog: mirrors the download-side check.
                            // Once bytes are actually being accepted (UI is showing "Uploading..."),
                            // throughput over the first SPEED_TEST_MIN_SPEED_CHECK_MS of that phase
                            // must clear SPEED_TEST_MIN_UPLOAD_MBPS, checked once at that
                            // checkpoint. Below the floor, "Uploading..." would otherwise sit on
                            // screen without a real, useful upload behind it — abort outright and
                            // report a dash instead of trying another URL.
                            if (connected && !minSpeedChecked) {
                                val sinceStart = now - uploadPhaseStart
                                if (sinceStart >= AppConfig.SPEED_TEST_MIN_SPEED_CHECK_MS) {
                                    minSpeedChecked = true
                                    val currentMbps = (total * 8.0 / (sinceStart / 1000.0)) / 1_000_000.0
                                    if (currentMbps < AppConfig.SPEED_TEST_MIN_UPLOAD_MBPS) {
                                        LogUtil.e(AppConfig.TAG, "testUploadSpeed[$index] $url → below ${AppConfig.SPEED_TEST_MIN_UPLOAD_MBPS} Mbps floor (${"%.2f".format(currentMbps)} Mbps after ${sinceStart}ms), aborting")
                                        tooSlow = true
                                        break
                                    }
                                }
                            }

                            val chunk = (AppConfig.SPEED_TEST_MAX_BYTES - total)
                                .coerceAtMost(buffer.size.toLong()).toInt()
                            output.write(buffer, 0, chunk)
                            output.flush()
                            total    += chunk
                            if (!connected) {
                                connected = true
                                uploadPhaseStart = now
                            }
                            if (!reportedConnected) {
                                reportedConnected = true
                                onConnected?.invoke()
                            }
                        }

                        if (tooSlow) {
                            // Hard stop: skip the graceful close()/ack wait entirely — the
                            // outer finally{} disconnects immediately so nothing keeps uploading
                            // in the background. A genuinely too-slow link is a final result
                            // (dash), not a reason to try another URL.
                            return null
                        }

                        // output.write()/flush() only prove the bytes reached the *local* socket
                        // buffer, not that they crossed the link — that's why the reported speed
                        // was higher than the real line speed. Closing the stream sends the final
                        // chunk terminator, and reading the response code blocks until the server
                        // has actually received the entire body and answered. Only at that point
                        // is "total bytes in `elapsed` ms" a real, confirmed throughput figure.
                        output.close()

                        if (stalled || !connected) {
                            LogUtil.e(AppConfig.TAG, "testUploadSpeed[$index] $url → stalled before first write, trying next")
                            if (index == urls.lastIndex) return null else continue
                        }

                        val responseCode = conn.responseCode
                        val elapsed = System.currentTimeMillis() - start

                        if (responseCode !in 200..299) {
                            LogUtil.e(AppConfig.TAG, "testUploadSpeed[$index] $url ack → HTTP $responseCode, trying next")
                            if (index == urls.lastIndex) return null else continue
                        }
                        if (elapsed <= 0 || total <= 0) {
                            LogUtil.e(AppConfig.TAG, "testUploadSpeed[$index] $url → zero bytes/elapsed, trying next")
                            if (index == urls.lastIndex) return null else continue
                        }

                        val mbps = (total * 8.0 / (elapsed / 1000.0)) / 1_000_000.0
                        LogUtil.e(AppConfig.TAG, "testUploadSpeed[$index] $url → OK: ${"%.1f".format(mbps)} Mbps ($total bytes / ${elapsed}ms)")
                        return SpeedTestResult(mbps = mbps)

                    } catch (e: IOException) {
                        // Server closed early, watchdog fired, or the ack never arrived — we
                        // can't confirm real throughput, so this attempt is a failure rather than
                        // a guess. Try again instead of reporting a made-up number.
                        LogUtil.e(AppConfig.TAG, "testUploadSpeed[$index] $url IO: ${e.javaClass.simpleName}: ${e.message}")
                        if (index == urls.lastIndex) return null
                    }

                } catch (e: Exception) {
                    LogUtil.e(AppConfig.TAG, "testUploadSpeed[$index] $url failed: ${e.javaClass.simpleName}: ${e.message}")
                    if (index == urls.lastIndex) return null
                } finally {
                    watchdog?.cancel()
                    try { conn?.disconnect() } catch (_: Exception) {}
                    activeConnection = null
                }
            }
            return null
        } finally {
            if (!SettingsManager.getSocksUsername().isNullOrBlank()) Authenticator.setDefault(null)
        }
    }
}
