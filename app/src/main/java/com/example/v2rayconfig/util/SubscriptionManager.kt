package com.example.v2rayconfig.util

import android.util.Base64
import com.example.v2rayconfig.model.ConfigParser
import com.example.v2rayconfig.model.ServerConfig
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

object SubscriptionManager {

    class SubscriptionException(message: String, cause: Throwable? = null) : Exception(message, cause)

    private const val HARD_TIMEOUT_SEC = 15L
    private val timeoutExecutor = Executors.newCachedThreadPool()

    fun fetchAndParseWithTimeout(url: String, useFragment: Boolean): List<ServerConfig> {
        val future = timeoutExecutor.submit<List<ServerConfig>> { fetchAndParse(url, useFragment) }
        return try {
            future.get(HARD_TIMEOUT_SEC, TimeUnit.SECONDS)
        } catch (e: TimeoutException) {
            future.cancel(true)
            throw SubscriptionException("Timed out after ${HARD_TIMEOUT_SEC}s (server unreachable or too slow).")
        } catch (e: java.util.concurrent.ExecutionException) {
            throw (e.cause as? Exception) ?: SubscriptionException("Unknown fetch error: ${e.message}")
        }
    }

    fun fetchAndParse(url: String, useFragment: Boolean): List<ServerConfig> {
        val raw = fetchRaw(url)
        val decoded = try {
            String(Base64.decode(raw.trim(), Base64.DEFAULT))
        } catch (e: Exception) {
            raw
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
                    null
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
