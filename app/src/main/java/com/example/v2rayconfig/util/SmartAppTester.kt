package com.example.v2rayconfig.util

import android.content.Context
import com.example.v2rayconfig.model.ServerConfig
import com.example.v2rayconfig.model.TargetApp
import libv2ray.Libv2ray
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger

object SmartAppTester {

    private const val PER_TEST_TIMEOUT_SEC = 8L
    private const val MAX_PARALLEL_TESTS = 5

    private val nativeCallExecutor = Executors.newCachedThreadPool()

    private data class TestPair(val config: ServerConfig, val app: TargetApp)

    private fun measureOne(config: ServerConfig, url: String): Long {
        val testJson = com.example.v2rayconfig.model.ConfigParser.toTestConfigJson(config)
        val future = nativeCallExecutor.submit<Long> {
            try {
                Libv2ray.measureOutboundDelay(testJson, url)
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

    fun testAllConfigs(
        context: Context,
        configs: List<ServerConfig>,
        apps: List<TargetApp>,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }
    ): Map<String, Map<String, Long>> {
        XrayEnv.ensureInitialized(context)

        val allPairs = configs.flatMap { c -> apps.map { a -> TestPair(c, a) } }
        val total = allPairs.size
        if (total == 0) return emptyMap()

        val doneCount = AtomicInteger(0)
        val resultsByPair = ConcurrentHashMap<TestPair, Long>()
        val pool = Executors.newFixedThreadPool(MAX_PARALLEL_TESTS)

        try {
            val futures = allPairs.map { pair ->
                pool.submit {
                    resultsByPair[pair] = measureOne(pair.config, pair.app.testUrl)
                    onProgress(doneCount.incrementAndGet(), total)
                }
            }
            futures.forEach { it.get() }
        } finally {
            pool.shutdown()
        }

        val grouped = mutableMapOf<String, MutableMap<String, Long>>()
        allPairs.forEach { pair ->
            val latency = resultsByPair[pair] ?: -1L
            grouped.getOrPut(pair.config.id) { mutableMapOf() }[pair.app.id] = latency
        }
        return grouped
    }

    fun bestConfigPerApp(
        configs: List<ServerConfig>,
        apps: List<TargetApp>,
        results: Map<String, Map<String, Long>>
    ): Map<String, Pair<ServerConfig, Long>?> {
        return apps.associate { app ->
            val best = configs
                .mapNotNull { c -> results[c.id]?.get(app.id)?.takeIf { it >= 0 }?.let { c to it } }
                .minByOrNull { it.second }
            app.id to best
        }
    }
}
