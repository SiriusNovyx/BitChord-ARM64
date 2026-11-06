package com.music.bitchord.ui.player

import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.music.bitchord.data.lyrics.LyricLine
import com.music.bitchord.data.settings.LyricsDisplayMode
import com.music.bitchord.ui.haptics.rememberHaptics
import java.lang.reflect.Proxy
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Test
import kotlin.test.*

class LyricsRateLimitUiTest {
    private class Harness(val frames: BroadcastFrameClock, val composition: Composition) {
        suspend fun settle() { repeat(30) { Snapshot.sendApplyNotifications(); frames.sendFrame(System.nanoTime()); yield() } }
    }
    private suspend fun harness(block: suspend Harness.() -> Unit) {
        val frames = BroadcastFrameClock()
        withContext(frames) {
            val recomposer = Recomposer(coroutineContext)
            val job = launch { recomposer.runRecomposeAndApplyChanges() }
            val composition = Composition(object : AbstractApplier<Unit>(Unit) {
                override fun insertTopDown(index: Int, instance: Unit) = Unit
                override fun insertBottomUp(index: Int, instance: Unit) = Unit
                override fun remove(index: Int, count: Int) = Unit
                override fun move(from: Int, to: Int, count: Int) = Unit
                override fun onClear() = Unit
            }, recomposer)
            try { Harness(frames, composition).block() }
            finally { composition.dispose(); recomposer.cancel(); job.cancelAndJoin() }
        }
    }

    @Test(timeout = 15_000) fun actualPlayerStatusDistinguishesRateLimitingAndKeepsPartialLinesVisible() = runBlocking {
        val installed = PlayerPlatform::class.java.getDeclaredField("installed").apply { isAccessible = true }
        val old = installed.get(null)
        val mode = MutableStateFlow(LyricsDisplayMode.Translated)
        val target = MutableStateFlow("th")
        val reduce = MutableStateFlow(true)
        val original = listOf(LyricLine(100, "The morning light"))
        val partial = original.map { it.copy(text = "แสงยามเช้า") }
        var requests = 0
        val settings = Proxy.newProxyInstance(PlayerSettingsSource::class.java.classLoader, arrayOf(PlayerSettingsSource::class.java)) { _, method, args ->
            when (method.name) {
                "getReduceAnimation" -> reduce
                "getTranslationLanguage" -> target
                "getLyricsDisplayMode" -> mode
                "setLyricsDisplayMode" -> { mode.value = args!![0] as LyricsDisplayMode; Unit }
                else -> error("Unexpected settings ${method.name}")
            }
        } as PlayerSettingsSource
        val host = Proxy.newProxyInstance(PlayerHost::class.java.classLoader, arrayOf(PlayerHost::class.java)) { _, method, args ->
            when (method.name) {
                "getSettings" -> settings
                "translateLyrics" -> {
                    assertEquals("th", args!![2])
                    if (++requests == 1) LyricsTranslationResult.RateLimited(30_000, partial)
                    else LyricsTranslationResult.Translated(partial)
                }
                else -> error("Unexpected host ${method.name}")
            }
        } as PlayerHost
        val owner = object : LifecycleOwner { override val lifecycle = LifecycleRegistry.createUnsafe(this).apply { currentState = Lifecycle.State.RESUMED } }
        PlayerPlatform.install(host)
        try { harness {
            var observed: LyricsTranslationUi? = null
            composition.setContent {
                CompositionLocalProvider(LocalLifecycleOwner provides owner, LocalDensity provides Density(1.5f)) {
                    val state = rememberLyricsTranslation("status-test", original, null, false, "Loading", rememberHaptics())
                    SideEffect { observed = state }
                }
            }
            settle()
            assertIs<LyricsTranslationUiState.RateLimited>(observed!!.translationState)
            assertTrue(observed!!.status.lowercase().contains("rate limited"), observed!!.status)
            assertEquals(partial, observed!!.displayedLyrics)
            assertEquals(partial, observed!!.subLines)
            repeat(5) { settle() }; assertEquals(1, requests)
            observed!!.toggleTranslation(); settle()
            assertEquals(original, observed!!.displayedLyrics)
            observed!!.toggleTranslation(); settle()
            assertIs<LyricsTranslationUiState.Ready>(observed!!.translationState)
            assertEquals(partial, observed!!.subLines)
            assertEquals(2, requests)
        } } finally { installed.set(null, old) }
    }

    @Test(timeout = 15_000) fun rapidTogglesAndTrackSwitchCannotPublishAnObsoleteTranslation() = runBlocking { harness {
        val mode = mutableStateOf(LyricsDisplayMode.Translated)
        val track = mutableStateOf("old")
        val releases = mutableListOf<CompletableDeferred<Unit>>()
        var observed: LyricsTranslationState? = null
        composition.setContent {
            val state = rememberLyricsTranslationState(track.value, listOf(LyricLine(0, track.value)), "th", mode.value,
                translate = { id, lines, _ ->
                    val release = CompletableDeferred<Unit>(); releases += release
                    withContext(NonCancellable) { release.await() }
                    LyricsTranslationResult.Translated(lines.map { it.copy(text = "translated-$id") })
                }, romanize = { _, _, _ -> LyricsRomanizationResult.Unavailable })
            SideEffect { observed = state }
        }
        try {
            settle()
            repeat(8) {
                mode.value = LyricsDisplayMode.Original; settle()
                mode.value = LyricsDisplayMode.Translated; settle()
            }
            track.value = "current"; settle()
            assertSame(LyricsTranslationUiState.Loading, observed!!.translation)
            releases.asReversed().forEach { it.complete(Unit) }
            settle()
            assertEquals("translated-current", assertIs<LyricsTranslationUiState.Ready>(observed!!.translation).lines.single().text)
        } finally { releases.forEach { it.complete(Unit) } }
    } }
}
