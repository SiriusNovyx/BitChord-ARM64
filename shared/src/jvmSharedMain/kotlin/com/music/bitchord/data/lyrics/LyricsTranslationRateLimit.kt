package com.music.bitchord.data.lyrics

import java.io.IOException
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import okhttp3.Response
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

internal class LyricsTranslationHttpException(val status: Int, val retryAfterMillis: Long? = null) : IOException("Translation HTTP $status")

internal class LyricsTranslationCooldown(val retryAfterMillis: Long, val rateLimited: Boolean, val status: Int? = null) : IOException("Translation provider cooldown")

internal fun readLyricsTranslationResponse(response: Response): String = response.use {
    if (!it.isSuccessful) throw LyricsTranslationHttpException(it.code, LyricsTranslationRateLimit.retryAfterMillis(it.header("Retry-After")))
    it.body?.string() ?: throw IOException("Empty translation response")
}

internal class LyricsTranslationRateLimit(
    private val nowMillis: () -> Long = { System.nanoTime() / 1_000_000 },
    private val jitter: () -> Double = { kotlin.random.Random.nextDouble() },
) {
    private val requests = LyricsRequestCoalescer<String>()
    private val permits = Semaphore(2)
    private val lock = Any()
    private var failures = 0
    private var until = Long.MIN_VALUE
    private var rateLimited = false
    private var status: Int? = null
    private var probe = false
    private var generation = 0L

    fun cooldown(): LyricsTranslationCooldown? = synchronized(lock) {
        if (failures > 0 && (probe || nowMillis() < until))
            LyricsTranslationCooldown(maxOf(1_000L, until - nowMillis()), rateLimited, status)
        else null
    }

    suspend fun execute(key: String, request: suspend () -> String): String = requests.run(key) {
        permits.withPermit {
            val epoch = synchronized(lock) {
                cooldown()?.let { throw it }
                if (failures > 0) probe = true
                generation
            }
            try {
                request().also { synchronized(lock) {
                    // An older in-flight success cannot close a newer rejection's circuit.
                    if (generation == epoch) { failures = 0; probe = false; rateLimited = false }
                } }
            } catch (cancelled: CancellationException) {
                synchronized(lock) { if (generation == epoch) probe = false }
                throw cancelled
            } catch (error: IOException) {
                val http = error as? LyricsTranslationHttpException
                if (http != null && http.status != 429 && http.status != 408 && http.status !in 500..599) {
                    synchronized(lock) { if (generation == epoch) { probe = false; failures = 0; rateLimited = false } }
                    throw error
                }
                throw synchronized(lock) {
                    failures = minOf(16, failures + 1)
                    val withoutProviderHint = http?.status == 429 && http.retryAfterMillis == null
                    val base = if (withoutProviderHint) 60_000L else 2_000L
                    val cap = if (withoutProviderHint) 900_000L else 60_000L
                    val exponential = minOf(cap, base shl minOf(5, failures - 1))
                    val backoff = (exponential * (0.75 + 0.25 * jitter().coerceIn(0.0, 1.0))).toLong()
                    val delay = maxOf(backoff, http?.retryAfterMillis ?: 0L)
                    val priorRestriction = rateLimited && nowMillis() < until
                    until = maxOf(until, nowMillis() + delay.coerceAtMost(Long.MAX_VALUE - maxOf(0, nowMillis())))
                    rateLimited = priorRestriction || http?.status == 429
                    status = http?.status
                    probe = false
                    generation++
                    LyricsTranslationCooldown(maxOf(1L, until - nowMillis()), rateLimited, status)
                }
            } catch (error: Throwable) {
                synchronized(lock) { if (generation == epoch) probe = false }
                throw error
            }
        }
    }

    companion object {
        fun retryAfterMillis(header: String?, wallClockMillis: Long = System.currentTimeMillis()): Long? {
            val value = header?.trim() ?: return null
            value.toLongOrNull()?.takeIf { it >= 0 }?.let { return it.coerceAtMost(Long.MAX_VALUE / 1000) * 1000 }
            return runCatching {
                maxOf(0L, ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() - wallClockMillis)
            }.getOrNull()
        }
    }
}
