package com.music.bitchord.data.lyrics

import kotlinx.coroutines.*
import kotlin.test.*

class LyricsTranslationRateLimitTest {
    @Test fun expiredRateLimitFollowedByServiceFailureIsUnavailableRatherThanANew429() = runBlocking {
        var now = 0L
        val gate = LyricsTranslationRateLimit({ now }, { 0.0 })
        val first = assertFailsWith<LyricsTranslationCooldown> { gate.execute("limited") { throw LyricsTranslationHttpException(429, 1000) } }
        assertTrue(first.rateLimited)
        now += first.retryAfterMillis
        val second = assertFailsWith<LyricsTranslationCooldown> { gate.execute("service-down") { throw LyricsTranslationHttpException(503) } }
        assertFalse(second.rateLimited)
        assertEquals(503, second.status)
    }

    @Test fun concurrencyIsBoundedAcrossDifferentKeysAndJitterChangesBackoff() = runBlocking {
        val gate = LyricsTranslationRateLimit()
        val active = java.util.concurrent.atomic.AtomicInteger()
        val peak = java.util.concurrent.atomic.AtomicInteger()
        val twoStarted = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val calls = (0..7).map { i -> async {
            gate.execute("key-$i") {
                val count = active.incrementAndGet(); peak.accumulateAndGet(count, ::maxOf)
                if (count == 2) twoStarted.complete(Unit)
                try { release.await(); "done" } finally { active.decrementAndGet() }
            }
        } }
        withTimeout(3000) { twoStarted.await() }
        release.complete(Unit); calls.awaitAll()
        assertEquals(2, peak.get()); assertEquals(0, active.get())
        val low = LyricsTranslationRateLimit({ 0L }, { 0.0 })
        val high = LyricsTranslationRateLimit({ 0L }, { 1.0 })
        val a = assertFailsWith<LyricsTranslationCooldown> { low.execute("retry") { throw LyricsTranslationHttpException(429) } }
        val b = assertFailsWith<LyricsTranslationCooldown> { high.execute("retry") { throw LyricsTranslationHttpException(429) } }
        assertEquals(45_000L, a.retryAfterMillis); assertEquals(60_000L, b.retryAfterMillis)
    }

    @Test fun parsesRetryAfterSecondsAndHttpDateWithoutShorteningProviderPause() {
        assertEquals(120_000L, LyricsTranslationRateLimit.retryAfterMillis("120"))
        assertEquals(30_000L, LyricsTranslationRateLimit.retryAfterMillis("Thu, 01 Jan 1970 00:00:30 GMT", 0))
        assertNull(LyricsTranslationRateLimit.retryAfterMillis("nonsense"))
        assertNull(LyricsTranslationRateLimit.retryAfterMillis("-1"))
    }

    @Test fun cancelledRecoveryProbeCanBeReplacedAndOldSuccessCannotCloseNewRejection() = runBlocking {
        var now = 0L
        val gate = LyricsTranslationRateLimit({ now }, { 0.0 })
        val slowStarted = CompletableDeferred<Unit>()
        val slowRelease = CompletableDeferred<Unit>()
        val old = async { gate.execute("old") { slowStarted.complete(Unit); slowRelease.await(); "old" } }
        slowStarted.await()
        try {
            assertFailsWith<LyricsTranslationCooldown> { gate.execute("reject") { throw LyricsTranslationHttpException(429, 10_000) } }
            slowRelease.complete(Unit); assertEquals("old", old.await())
            assertFailsWith<LyricsTranslationCooldown> { gate.execute("still-closed") { "bad" } }
            now = 10_000
            val probeStarted = CompletableDeferred<Unit>()
            val probeCancelled = CompletableDeferred<Unit>()
            val probe = async { gate.execute("cancelled-probe") {
                try { probeStarted.complete(Unit); awaitCancellation() } finally { probeCancelled.complete(Unit) }
            } }
            probeStarted.await(); probe.cancelAndJoin(); probeCancelled.await()
            assertEquals("recovered", gate.execute("replacement") { "recovered" })
        } finally { slowRelease.complete(Unit); old.cancelAndJoin() }
    }

    @Test fun providerRetryAfterBlocksAllKeysThenAllowsOneRecoveryProbe() = runBlocking {
        var now = 0L
        val gate = LyricsTranslationRateLimit({ now }, { 0.0 })
        var requests = 0
        assertFailsWith<LyricsTranslationCooldown> { gate.execute("first") { requests++; throw LyricsTranslationHttpException(429, 30_000) } }
        repeat(10) { index -> assertFailsWith<LyricsTranslationCooldown> { gate.execute("other$index") { requests++; "unexpected" } } }
        assertEquals(1, requests)
        now = 29_999
        assertFailsWith<LyricsTranslationCooldown> { gate.execute("early") { requests++; "unexpected" } }
        now = 30_000
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val probe = async { gate.execute("probe") { requests++; started.complete(Unit); release.await(); "ok" } }
        started.await()
        try {
            assertFailsWith<LyricsTranslationCooldown> { gate.execute("concurrent") { requests++; "unexpected" } }
        } finally { release.complete(Unit) }
        assertEquals("ok", probe.await())
        assertEquals("recovered", gate.execute("new") { requests++; "recovered" })
        assertEquals(3, requests)
    }

    @Test fun identicalRequestsCoalesceAndLastWaiterCancellationReleasesProbe() = runBlocking {
        val gate = LyricsTranslationRateLimit()
        var calls = 0
        val start = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val first = async(start = CoroutineStart.UNDISPATCHED) { gate.execute("same") { calls++; start.complete(Unit); release.await(); "answer" } }
        start.await()
        val second = async(start = CoroutineStart.UNDISPATCHED) { gate.execute("same") { calls++; "duplicate" } }
        first.cancelAndJoin()
        release.complete(Unit)
        assertEquals("answer", second.await())
        assertEquals(1, calls)
    }

    @Test fun recoverableFailuresUseBoundedExponentialJitterButAuthorizationDoesNotRetry() = runBlocking {
        var now = 0L
        val gate = LyricsTranslationRateLimit({ now }, { 0.5 })
        val delays = mutableListOf<Long>()
        repeat(12) {
            val error = assertFailsWith<LyricsTranslationCooldown> { gate.execute("retry") { throw LyricsTranslationHttpException(503) } }
            assertFalse(error.rateLimited)
            delays += error.retryAfterMillis
            now += error.retryAfterMillis
        }
        assertTrue(delays.first() >= 1000)
        assertTrue(delays[1] > delays[0])
        assertTrue(delays.all { it in 1..60_000 })
        now += 60_000
        assertFailsWith<LyricsTranslationHttpException> { gate.execute("auth") { throw LyricsTranslationHttpException(403) } }
        assertEquals("ok", gate.execute("after-auth") { "ok" })
    }
}
