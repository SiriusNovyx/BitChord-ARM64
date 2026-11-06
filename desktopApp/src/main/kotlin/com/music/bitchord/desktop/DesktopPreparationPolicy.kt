package com.music.bitchord.desktop

import java.util.concurrent.ExecutorService
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit

/** One drain owns native analysis; selection changes replace pending work, never add workers. */
internal class DesktopPreparationQueue<K>(private val worker: ExecutorService) {
    internal class Pass {
        private val lock = Any()
        @Volatile var cancelled = false
            private set
        private var thread: Thread? = null
        private var cancelRead: (() -> Unit)? = null

        fun onCancel(action: () -> Unit): () -> Unit {
            synchronized(lock) {
                cancelRead = action
                if (cancelled) action()
            }
            return { synchronized(lock) { if (cancelRead === action) cancelRead = null } }
        }

        fun start() = synchronized(lock) { thread = Thread.currentThread() }
        fun publish(action: () -> Unit): Boolean = synchronized(lock) {
            if (cancelled) false else { action(); true }
        }
        fun finish() = synchronized(lock) { thread = null; cancelRead = null; Thread.interrupted(); Unit }
        fun cancel() = synchronized(lock) {
            cancelled = true
            thread?.interrupt()
            cancelRead?.invoke()
        }
    }

    private data class Work<K>(val key: K, val pass: Pass, val run: (Pass) -> Unit)
    private val lock = Any()
    private var selected: List<K>? = null
    private val pending = LinkedHashMap<K, Work<K>>()
    private var active: Work<K>? = null
    private var dispatched = false
    private var closed = false

    fun retain(keys: List<K>) {
        val obsolete = synchronized(lock) {
            selected = keys.distinct()
            pending.keys.retainAll(keys.toSet())
            active?.takeUnless { it.key in keys }?.pass
        }
        obsolete?.cancel()
    }

    fun submit(key: K, run: (Pass) -> Unit): Boolean = synchronized(lock) {
        if (closed || selected?.let { key !in it } == true || key in pending ||
            active?.let { it.key == key && !it.pass.cancelled } == true) return@synchronized false
        pending[key] = Work(key, Pass(), run)
        if (!dispatched) {
            dispatched = true
            try { worker.execute(object : Runnable { override fun run() = drain() }) } catch (_: RejectedExecutionException) {
                pending.clear()
                dispatched = false
                return@synchronized false
            }
        }
        true
    }

    private fun drain() {
        while (true) {
            val work = synchronized(lock) {
                val key = selected?.firstOrNull { it in pending } ?: pending.keys.firstOrNull()
                if (closed || key == null) { dispatched = false; return }
                pending.remove(key)!!.also { active = it; it.pass.start() }
            }
            try { if (!work.pass.cancelled) work.run(work.pass) }
            finally {
                work.pass.finish()
                synchronized(lock) { if (active === work) active = null }
            }
        }
    }

    fun close(afterWork: (() -> Unit)? = null) {
        val pass = synchronized(lock) {
            if (closed) return
            closed = true
            pending.clear()
            active?.pass
        }
        pass?.cancel()
        // The cancelled native call can still be returning. Its resources must be
        // retired on this worker after the drain, never concurrently on the UI thread.
        try {
            if (afterWork != null) worker.execute { afterWork() }
        } finally {
            worker.shutdown()
        }
    }

    val isTerminated: Boolean get() = worker.isTerminated

    fun awaitTermination(timeoutNanos: Long): Boolean =
        worker.awaitTermination(timeoutNanos.coerceAtLeast(0L), TimeUnit.NANOSECONDS)
}

internal data class DesktopTransitionTicket(
    val generation: Long,
    val selection: Long,
    val currentId: String?,
    val nextId: String?,
)

/** A queue selection owns both preparation and a single accepted manual handoff. */
internal class DesktopTransitionOwnership {
    private var serial = 0L
    private var selected: DesktopTransitionTicket? = null
    private var reserved: DesktopTransitionTicket? = null
    private var consumed = false

    @Synchronized fun select(generation: Long, currentId: String?, nextId: String?): DesktopTransitionTicket =
        DesktopTransitionTicket(generation, ++serial, currentId, nextId).also { selected = it; reserved = null; consumed = false }

    @Synchronized fun isCurrent(ticket: DesktopTransitionTicket): Boolean = selected == ticket

    @Synchronized fun reserve(ticket: DesktopTransitionTicket): Boolean {
        if (selected != ticket || reserved != null || consumed) return false
        reserved = ticket
        return true
    }

    @Synchronized fun consume(ticket: DesktopTransitionTicket): Boolean {
        if (selected != ticket || reserved != ticket) return false
        reserved = null
        consumed = true
        return true
    }
}
