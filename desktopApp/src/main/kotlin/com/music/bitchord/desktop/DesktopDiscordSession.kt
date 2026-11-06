package com.music.bitchord.desktop

import java.io.Closeable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

internal interface DesktopDiscordConnection : Closeable {
    fun setActivity(activity: JsonObject?)

    /** Replays the anchored activity to detect a client that stopped accepting writes. */
    fun checkActivity(activity: JsonObject?) = setActivity(activity)
}

internal class DesktopDiscordSession(
    scope: CoroutineScope,
    private val connect: suspend () -> DesktopDiscordConnection?,
    private val onAvailabilityChanged: (Boolean) -> Unit,
    private val retryDelayMs: Long = 5_000,
    private val checkIntervalMs: Long = 15_000,
    private val onSessionStopped: () -> Unit = {},
    private val onLocalActivityPublished: () -> Unit = {},
    private val minPublishIntervalMs: Long = 4_100,
) {
    private data class Control(val enabled: Boolean = false, val generation: Long = 0, val probe: Boolean = false)
    private data class Frame(val revision: Long, val activity: JsonObject?, val fallback: suspend () -> Unit)
    private val control = MutableStateFlow(Control())
    private val frame = MutableStateFlow<Frame?>(null)
    private val changed = Channel<Unit>(Channel.CONFLATED)

    init {
        require(retryDelayMs > 0 && checkIntervalMs > 0 && minPublishIntervalMs >= 0)
        scope.launch {
            // collectLatest waits for the preceding owner's cleanup before opening another.
            control.collectLatest { desired ->
                if (desired.enabled) runSession(desired)
                else if (desired.probe) probeOnce(desired)
            }
        }
    }

    fun setEnabled(value: Boolean) {
        control.update { if (it.enabled == value) it else Control(value, it.generation + 1) }
    }

    fun restart() {
        control.update { it.copy(generation = it.generation + 1) }
    }

    fun probe() {
        if (control.value.enabled) changed.trySend(Unit)
        else control.update { it.copy(probe = true, generation = it.generation + 1) }
    }

    fun update(activity: JsonObject?, fallback: suspend () -> Unit = {}) {
        frame.update { Frame((it?.revision ?: 0) + 1, activity, fallback) }
        changed.trySend(Unit)
    }

    private suspend fun ensureCurrent(desired: Control) {
        currentCoroutineContext().ensureActive()
        if (control.value != desired) throw CancellationException("Discord session replaced")
    }

    private suspend fun probeOnce(desired: Control) {
        var connection: DesktopDiscordConnection? = null
        try {
            connection = connect()
            ensureCurrent(desired)
            onAvailabilityChanged(connection != null)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            onAvailabilityChanged(false)
        } finally {
            // A blocking connector can finish after cancellation; that handle still belongs here.
            runCatching { connection?.close() }
        }
    }

    private suspend fun runSession(desired: Control) = coroutineScope {
        var connection: DesktopDiscordConnection? = null
        var sentRevision = -1L
        var fallbackRevision = -1L
        var fallbackJob: Job? = null
        var fallbackSucceeded = AtomicBoolean(false)
        val fallbackCompletedAtNanos = AtomicReference<Long?>(null)
        var published = false
        var lastPublishAtNanos: Long? = null
        var fallbackFailedAtNanos: Long? = null
        try {
            while (true) {
                ensureCurrent(desired)
                if (frame.value?.revision != fallbackRevision) {
                    fallbackJob?.cancelAndJoin()
                    fallbackJob = null
                    fallbackRevision = -1L
                    fallbackFailedAtNanos = null
                }
                if (connection == null) {
                    connection = try { connect() } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) { null }
                    ensureCurrent(desired)
                    onAvailabilityChanged(connection != null)
                    if (connection != null) fallbackRevision = -1L
                    sentRevision = -1L
                }
                if (connection != null && fallbackJob != null) {
                    fallbackJob?.cancelAndJoin()
                    fallbackJob = null
                    fallbackRevision = -1L
                }
                fallbackCompletedAtNanos.getAndSet(null)?.let {
                    lastPublishAtNanos = it
                    if (!fallbackSucceeded.get() && frame.value?.revision == fallbackRevision) {
                        fallbackFailedAtNanos = it
                    }
                }
                val snapshot = frame.value
                val budgetWaitMs = lastPublishAtNanos?.let {
                    (minPublishIntervalMs - (System.nanoTime() - it) / 1_000_000).coerceAtLeast(0)
                } ?: 0
                val retryWaitMs = if (connection == null) fallbackFailedAtNanos?.let {
                    (retryDelayMs - (System.nanoTime() - it) / 1_000_000).coerceAtLeast(0)
                } ?: 0 else 0
                val waitMs = maxOf(budgetWaitMs, retryWaitMs)
                if (snapshot != null && waitMs > 0) {
                    // Re-read frame after waiting: rapid seeks replace the pending timestamps.
                    withTimeoutOrNull(waitMs) { changed.receive() }
                    continue
                }
                try {
                    val local = connection
                    if (local != null && snapshot != null) {
                        ensureCurrent(desired)
                        lastPublishAtNanos = System.nanoTime()
                        if (snapshot.revision != sentRevision) {
                            local.setActivity(snapshot.activity)
                            sentRevision = snapshot.revision
                            published = true
                            onLocalActivityPublished()
                        } else {
                            local.checkActivity(snapshot.activity)
                        }
                        lastPublishAtNanos = System.nanoTime()
                    } else if (local == null && snapshot != null &&
                        (snapshot.revision != fallbackRevision ||
                            (fallbackJob?.isCompleted == true && !fallbackSucceeded.get()))) {
                        fallbackRevision = snapshot.revision
                        lastPublishAtNanos = System.nanoTime()
                        val succeeded = AtomicBoolean(false)
                        fallbackSucceeded = succeeded
                        fallbackJob = launch {
                            try {
                                snapshot.fallback()
                                succeeded.set(true)
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (_: Exception) {
                                // The owner retries after its backoff; no stale send is retained.
                            } finally {
                                fallbackCompletedAtNanos.set(System.nanoTime())
                                changed.trySend(Unit)
                            }
                        }
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    runCatching { connection?.close() }
                    connection = null
                    published = false
                    onAvailabilityChanged(false)
                    // No revision is marked delivered on this route until its write succeeds.
                }
                val delayMs = if (connection == null) retryDelayMs else checkIntervalMs
                withTimeoutOrNull(delayMs) { changed.receive() }
            }
        } finally {
            withContext(NonCancellable) {
                fallbackJob?.cancelAndJoin()
                if (published) runCatching { connection?.setActivity(null) }
                runCatching { connection?.close() }
                onAvailabilityChanged(false)
                onSessionStopped()
            }
        }
    }
}
