package com.example.v2rayconfig.util

import org.json.JSONObject
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL
import javax.net.ssl.HttpsURLConnection

/**
 * Looks up the public IP/country the traffic is actually exiting through,
 * by routing the lookup request itself through the local SOCKS proxy that
 * Xray listens on (127.0.0.1:10808). This matters because checking your
 * IP without going through the proxy would just show your phone's real
 * network IP, not the VPN server's.
 */
object IpGeoChecker {

    data class GeoResult(val ip: String, val countryName: String, val countryCode: String)

    class GeoException(message: String, cause: Throwable? = null) : Exception(message, cause)

    private const val SOCKS_HOST = "127.0.0.1"
    private const val SOCKS_PORT = 10808
    private const val ENDPOINT = "https://ip-api.com/json/?fields=status,message,country,countryCode,query"

    fun check(timeoutMs: Int = 8000): GeoResult {
        val proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress(SOCKS_HOST, SOCKS_PORT))
        val conn = try {
            URL(ENDPOINT).openConnection(proxy) as HttpsURLConnection
        } catch (e: Exception) {
            throw GeoException("Could not open connection: ${e.message}", e)
        }
        try {
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            conn.requestMethod = "GET"

            if (conn.responseCode !in 200..299) {
                throw GeoException("Geo lookup returned HTTP ${conn.responseCode}")
            }

            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(body)

            if (json.optString("status") != "success") {
                throw GeoException(json.optString("message", "Geo lookup failed"))
            }

            return GeoResult(
                ip = json.optString("query"),
                countryName = json.optString("country"),
                countryCode = json.optString("countryCode")
            )
        } catch (e: GeoException) {
            throw e
        } catch (e: Exception) {
            throw GeoException("Geo lookup failed: ${e.message}", e)
        } finally {
            conn.disconnect()
        }
    }

    /** Converts a 2-letter country code (e.g. "NL") to its flag emoji. */
    fun flagEmoji(countryCode: String): String {
        if (countryCode.length != 2) return ""
        val base = 0x1F1E6 // regional indicator symbol letter A
        return countryCode.uppercase().map { c ->
            String(Character.toChars(base + (c - 'A')))
        }.joinToString("")
    }
}
