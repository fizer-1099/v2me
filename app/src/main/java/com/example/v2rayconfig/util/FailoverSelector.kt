package com.example.v2rayconfig.util

import android.content.Context
import com.example.v2rayconfig.model.ServerConfig

/**
 * Decides which server to fail over to. Prefers real per-app latency data
 * from the last Smart Test run (for whichever app the user marked as
 * priority) over generic ping, since a server that's fast in general
 * isn't necessarily the one that actually reaches a specific filtered
 * service well. Falls back gracefully when that data isn't available or
 * is stale.
 */
object FailoverSelector {

    /** Smart Test data older than this is considered too stale to trust for failover decisions. */
    private const val MAX_SMART_TEST_AGE_MS = 24 * 60 * 60 * 1000L // 24h

    /**
     * Picks the best candidate from [candidates].
     * 1. If a priority app is set AND we have Smart-Test data for it that's
     *    not stale AND at least one candidate has a successful result for
     *    it, use that (lowest latency for that specific app).
     * 2. Otherwise, fall back to real proxied-latency testing (via
     *    PingTester, which itself uses the core's measureOutboundDelay) —
     *    using [precomputedLatencies] if the caller already has fresh
     *    ones, or running a fresh test itself if not.
     */
    fun pickBestCandidate(
        context: Context,
        candidates: List<ServerConfig>,
        repo: ConfigRepository,
        precomputedLatencies: Map<String, Long>? = null
    ): ServerConfig? {
        if (candidates.isEmpty()) return null

        val priorityAppId = repo.getPriorityAppId()
        val smartResults = repo.getSmartTestResults()
        val smartTestFresh = (System.currentTimeMillis() - repo.getSmartTestTimestamp()) < MAX_SMART_TEST_AGE_MS

        if (priorityAppId != null && smartTestFresh) {
            val best = candidates
                .mapNotNull { c ->
                    smartResults[c.id]?.get(priorityAppId)?.takeIf { it >= 0 }?.let { c to it }
                }
                .minByOrNull { it.second }
            if (best != null) return best.first
            // No candidate has usable data for the priority app — fall through to generic ping.
        }

        val latencies = precomputedLatencies ?: PingTester.testAll(context, candidates)
        return PingTester.pickBest(candidates, latencies)
    }
}
