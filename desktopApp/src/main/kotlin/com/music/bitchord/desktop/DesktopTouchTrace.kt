package com.music.bitchord.desktop

import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Opt-in numeric trace. No titles, content, credentials or provider responses enter this buffer. */
internal object DesktopTouchTrace {
    val enabled = java.lang.Boolean.getBoolean("bitchord.touch.trace")
    private val started = System.nanoTime()
    private val events = ArrayDeque<String>()
    private val counts = sortedMapOf<String, Long>()
    private val lock = Any()
    private val writer by lazy {
        Executors.newSingleThreadScheduledExecutor { task ->
            Thread(task, "BitChord touch trace").apply { isDaemon = true }
        }.also { executor ->
            executor.scheduleWithFixedDelay({ save() }, 1, 5, TimeUnit.SECONDS)
            Runtime.getRuntime().addShutdownHook(Thread({
                executor.shutdown()
                save()
            }, "BitChord touch trace shutdown"))
        }
    }

    fun record(stage: String, handle: Long = 0, id: Int = 0, x: Float = 0f, y: Float = 0f, detail: Int = 0) {
        if (!enabled) return
        writer
        val elapsed = (System.nanoTime() - started) / 1_000_000
        synchronized(lock) {
            counts[stage] = (counts[stage] ?: 0) + 1
            if (events.size == 512) events.removeFirst()
            events.addLast("$elapsed,$stage,$handle,$id,${x.toInt()},${y.toInt()},$detail")
        }
    }

    internal fun snapshot(): String = synchronized(lock) {
        buildString {
            appendLine("BitChord touch trace v1; last 512 events; numeric coordinates in physical scene pixels")
            counts.forEach { (stage, count) -> appendLine("count,$stage,$count") }
            appendLine("elapsed_ms,stage,hwnd,contact,x,y,detail")
            events.forEach(::appendLine)
        }
    }

    @Synchronized private fun save() {
        runCatching {
            val directory = DesktopStorage.directory("logs") {
                Path.of(System.getProperty("user.home"), ".local", "state", "bitchord")
            }
            Files.createDirectories(directory)
            val temp = directory.resolve("touch-trace.pending")
            Files.writeString(temp, snapshot())
            Files.move(temp, directory.resolve("touch-trace.csv"), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private val scroll = object : NestedScrollConnection {
        override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
            record("scroll_consumed", x = consumed.x, y = consumed.y)
            return Offset.Zero
        }
    }

    fun observe(modifier: Modifier): Modifier {
        if (!enabled) return modifier
        return modifier.nestedScroll(scroll).pointerInput(Unit) {
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Final)
                    event.changes.filter { it.type == PointerType.Touch }.forEach {
                        record("compose_final", id = it.id.value.toInt(), x = it.position.x, y = it.position.y,
                            detail = (if (it.pressed) 1 else 0) or (if (it.isConsumed) 2 else 0))
                    }
                }
            }
        }
    }
}
