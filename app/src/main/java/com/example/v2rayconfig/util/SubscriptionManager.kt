package com.example.v2rayconfig.util

import android.util.Base64
import com.example.v2rayconfig.model.ConfigParser
import com.example.v2rayconfig.model.ServerConfig
import java.net.HttpURLConnection
import java.net.URL

/**
 * Fetches a "subscription" — a plain-text or base64-encoded list of
 * vmess://, vless://, ss:// links, one per line. This is the standard
 * format used by most public config lists on GitHub (raw.githubusercontent.com
 * links to a .txt file). The user supplies the URL themselves; this class
 * does not hardcode or auto-pick any specific GitHub repo, since the
 * trustworthiness of a given list is something the user must judge —
 * a malicious or compromised list could point you at a hostile proxy
 * server capable of seeing your traffic.
 */
object SubscriptionManager {

    class SubscriptionException(message: String, cause: Throwable? = null) : Exception(message, cause)

    fun fetchAndParse(url: String, useFragment: Boolean): List<ServerConfig> {
        val raw = fetchRaw(url)
        val decoded = try {
            String(Base64.decode(raw.trim(), Base64.DEFAULT))
        } catch (e: Exception) {
            raw // list wasn't base64-encoded, treat as already-plain
        }

        val configs = decoded.lines()
            .map { it.trim() }
            .filter {
                it.startsWith("vmess://") || it.startsWith("vless://") || it.startsWith("ss://")
            }
            .mapNotNull { line ->
                try {
                    ConfigParser.parse(line, useFragment).copy(source = "subscription")
                } catch (e: Exception) {
                    null // skip malformed individual entries rather than failing the whole batch
                }
            }

        if (configs.isEmpty()) {
            throw SubscriptionException("No valid vmess/vless/ss links found at that URL.")
        }
        return configs
    }

    private fun fetchRaw(urlStr: String): String {
        val conn = try {
            URL(urlStr).openConnection() as HttpURLConnection
        } catch (e: Exception) {
            throw SubscriptionException("Invalid URL: ${e.message}", e)
        }
        try {
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000
            conn.requestMethod = "GET"
            conn.setRequestProperty("User-Agent", "V2RayConfigApp/1.0")

            if (conn.responseCode !in 200..299) {
                throw SubscriptionException("Server returned HTTP ${conn.responseCode}")
            }
            return conn.inputStream.bufferedReader().use { it.readText() }
        } catch (e: SubscriptionException) {
            throw e
        } catch (e: Exception) {
            throw SubscriptionException("Failed to fetch subscription: ${e.message}", e)
        } finally {
            conn.disconnect()
        }
    }
}
