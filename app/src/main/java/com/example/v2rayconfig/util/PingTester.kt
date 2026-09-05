package com.example.v2rayconfig.util

import android.content.Context
import com.example.v2rayconfig.model.ServerConfig
import libv2ray.Libv2ray
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

object PingTester {

    private const val DEFAULT_TEST_URL = "https://cp.cloudflare.com/generate_204"
    private const val PER_TEST_TIMEOUT_SEC = 8L

    private val nativeCallExecutor = Executors.newCachedThreadPool()

    fun testLatency(context: Context, config: ServerConfig, testUrl: String = DEFAULT_TEST_URL): Long {
        XrayEnv.ensureInitialized(context)
        val future = nativeCallExecutor.submit<Long> {
            try {
                Libv2ray.measureOutboundDelay(config.xrayConfigJson, testUrl)
            } catch (e: Exception) {
                -1L
            }
        }
        return try {
            future.get(PER_TEST_TIMEOUT_SEC, TimeUnit.SECONDS)
        } catch (e: TimeoutException) {
            future.cancel(true)
            -1L
        } catch (e: Exception) {
            -1L
        }
    }

    fun testAll(context: Context, configs: List<ServerConfig>, testUrl: String = DEFAULT_TEST_URL): Map<String, Long> {
        if (configs.isEmpty()) return emptyMap()
        XrayEnv.ensureInitialized(context)
        val results = ConcurrentHashMap<String, Long>()
        val pool = Executors.newFixedThreadPool(minOf(configs.size, 10))
        try {
            val futures = configs.map { config ->
                pool.submit { results[config.id] = testLatency(context, config, testUrl) }
            }
            futures.forEach { it.get() }
        } catch (e: Exception) {
        } finally {
            pool.shutdown()
        }
        return results
    }

    fun pickBest(configs: List<ServerConfig>, latencies: Map<String, Long>): ServerConfig? {
        return configs
            .mapNotNull { c -> latencies[c.id]?.takeIf { it >= 0 }?.let { c to it } }
            .minByOrNull { it.second }
            ?.first
    }
}
