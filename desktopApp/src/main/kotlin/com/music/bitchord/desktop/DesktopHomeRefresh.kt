package com.music.bitchord.desktop

import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.UnknownHostException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

internal enum class HomeRefreshResult { IDLE, LOADING, UPDATED, OFFLINE, ERROR }

internal fun desktopHomeIdentity(signedIn: Boolean, account: String?, profile: String?, sessionRevision: Int): String =
    "$signedIn/$account/$profile/$sessionRevision"

internal data class HomeRefreshSnapshot<T>(
    val identity: String? = null,
    val content: T? = null,
    val refreshing: Boolean = false,
    val result: HomeRefreshResult = HomeRefreshResult.IDLE,
)

/** Called on the UI dispatcher; identity changes retire both the request and its private content. */
internal class DesktopHomeRefresh<T>(
    private val scope: CoroutineScope,
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000 },
    private val freshForMs: Long = 5 * 60_000L,
    private val retryAfterMs: Long = 30_000L,
    private val fetch: suspend (String) -> T,
) {
    private val mutableState = MutableStateFlow(HomeRefreshSnapshot<T>())
    val state = mutableState.asStateFlow()
    private var job: Job? = null
    private var generation = 0L
    private var succeededAt: Long? = null
    private var attemptedAt: Long? = null

    fun request(identity: String, force: Boolean = false): Boolean {
        if (!scope.isActive) return false
        val changed = mutableState.value.identity != identity
        if (changed) {
            generation++
            job?.cancel()
            succeededAt = null
            attemptedAt = null
            mutableState.value = HomeRefreshSnapshot(identity = identity)
        } else if (job?.isActive == true) return false
        val now = nowMs()
        if (!force && !changed) {
            if (succeededAt?.let { now >= it && now - it < freshForMs } == true) return false
            if (attemptedAt?.let { now >= it && now - it < retryAfterMs } == true) return false
        }
        val ticket = ++generation
        attemptedAt = now
        mutableState.value = mutableState.value.copy(refreshing = true, result = HomeRefreshResult.LOADING)
        job = scope.launch {
            try {
                val content = fetch(identity)
                if (ticket == generation && isActive) {
                    succeededAt = nowMs()
                    mutableState.value = HomeRefreshSnapshot(identity, content, result = HomeRefreshResult.UPDATED)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (ticket == generation) {
                    val offline = generateSequence<Throwable>(failure) { it.cause }
                        .any { it is UnknownHostException || it is ConnectException || it is NoRouteToHostException }
                    mutableState.value = mutableState.value.copy(
                        result = if (offline) HomeRefreshResult.OFFLINE else HomeRefreshResult.ERROR,
                    )
                }
            } finally {
                if (ticket == generation) mutableState.value = mutableState.value.copy(refreshing = false)
            }
        }
        return true
    }

    fun close() {
        generation++
        job?.cancel()
        job = null
        succeededAt = null
        attemptedAt = null
        mutableState.value = HomeRefreshSnapshot()
    }
}
