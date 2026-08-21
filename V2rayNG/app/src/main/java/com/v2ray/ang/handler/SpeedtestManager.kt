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

object SpeedtestManager {

    data class RemoteEndpointInfo(
        val country: String?,
        val ipAddress: String?,
    )

    /** Result of a download speed test. */
    data class SpeedTestResult(
        val mbps: Double,
        val bytesTransferred: Long,
        val elapsedMs: Long,
    )

    /**
     * Measures the time taken to establish a TCP connection to a given URL and port.
     *
     * @param url The URL to connect to.
     * @param port The port to connect to.
     * @return The connection time in milliseconds, or -1 if the connection failed.
     */
    fun socketConnectTime(url: String, port: Int, timeoutMs: Int = 1500): Long {
        var socket: Socket? = null
        val start = System.currentTimeMillis()

        try {
            socket = Socket()
            socket.connect(InetSocketAddress(url, port), timeoutMs)

            return System.currentTimeMillis() - start
        } catch (e: UnknownHostException) {
            LogUtil.e(AppConfig.TAG, "Unknown host: $url", e)
        } catch (e: IOException) {
            LogUtil.e(AppConfig.TAG, "socketConnectTime IOException: ${e.message}")
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Failed to establish socket connection to $url:$port", e)
        } finally {
            socket?.let { s ->
                try {
                    if (!s.isClosed) {
                        s.close()
                    }
                } catch (closeEx: IOException) {
                }
            }
        }
        return -1
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

        val ip = listOf(
            ipInfo.ip,
            ipInfo.clientIp,
            ipInfo.ip_addr,
            ipInfo.query
        ).firstOrNull { !it.isNullOrBlank() }

        val country = listOf(
            ipInfo.country_code,
            ipInfo.country,
            ipInfo.countryCode,
            ipInfo.location?.country_code
        ).firstOrNull { !it.isNullOrBlank() }

        return RemoteEndpointInfo(
            country = country,
            ipAddress = ip,
        )
    }

    /**
     * Downloads [AppConfig.SPEED_TEST_URL] (a fixed-size ~20MB test file) through the
     * local SOCKS inbound (i.e. the currently running VPN connection) and measures the
     * effective throughput.
     *
     * Uses the SOCKS proxy (not HTTP) because the app's core only exposes a local HTTP
     * inbound when running the v2fly core; with Xray core (the common case) only the
     * SOCKS inbound is guaranteed to exist. See [SettingsManager.getSocksPort].
     *
     * Requires the VPN/proxy service to already be running.
     *
     * @param timeoutMs overall timeout for the transfer; the transfer is cut short at this
     *  point even if the full file hasn't downloaded yet, so speed is still computed from
     *  whatever was actually transferred.
     * @return the measured result, or null if not connected / on failure.
     */
    fun testDownloadSpeed(timeoutMs: Int = 20_000): SpeedTestResult? {
        val socksPort = SettingsManager.getSocksPort()
        if (socksPort == 0) return null

        val proxyUsername = SettingsManager.getSocksUsername()
        val proxyPassword = SettingsManager.getSocksPassword()
        val hasAuth = !proxyUsername.isNullOrBlank()

        if (hasAuth) {
            Authenticator.setDefault(object : Authenticator() {
                override fun getPasswordAuthentication(): PasswordAuthentication {
                    return PasswordAuthentication(proxyUsername, (proxyPassword ?: "").toCharArray())
                }
            })
        }

        var conn: HttpURLConnection? = null
        return try {
            val proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", socksPort))
            conn = (URL(AppConfig.SPEED_TEST_URL).openConnection(proxy) as HttpURLConnection).apply {
                connectTimeout = 6000
                readTimeout = timeoutMs
                requestMethod = "GET"
            }
            conn.connect()
            if (conn.responseCode !in 200..299) {
                LogUtil.e(AppConfig.TAG, "testDownloadSpeed http ${conn.responseCode}")
                return null
            }

            val buffer = ByteArray(64 * 1024)
            var total = 0L
            val start = System.currentTimeMillis()
            conn.inputStream.use { input ->
                while (true) {
                    if (System.currentTimeMillis() - start > timeoutMs) break
                    val n = input.read(buffer)
                    if (n <= 0) break
                    total += n
                }
            }
            val elapsed = System.currentTimeMillis() - start
            if (elapsed <= 0 || total <= 0) return null

            val mbps = (total * 8.0 / (elapsed / 1000.0)) / 1_000_000.0
            SpeedTestResult(mbps = mbps, bytesTransferred = total, elapsedMs = elapsed)
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "testDownloadSpeed failed", e)
            null
        } finally {
            conn?.disconnect()
            if (hasAuth) Authenticator.setDefault(null)
        }
    }
}
