package com.music.bitchord.desktop

import com.music.bitchord.data.listentogether.PartyMembership
import com.music.bitchord.data.listentogether.PartyTrack
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.WebSocket
import java.nio.file.Path
import java.nio.file.Files
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import java.util.prefs.Preferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assume.assumeTrue
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class DesktopPartyNetworkTest {
    @Test(timeout = 40_000)
    fun `actual CIO party transport uses isolated synthetic membership against local Go`() = runBlocking {
        val base = System.getenv("BITCHORD_PARTY_PROBE_URL")
        assumeTrue("Requires the opt-in loopback Go probe", !base.isNullOrBlank())
        val address = URI(requireNotNull(base))
        require(address.scheme == "http" && address.host == "127.0.0.1" && address.rawUserInfo == null)
        val data = requireNotNull(System.getProperty("bitchord.dataDir")) { "A fresh isolated probe directory is required" }
        require(System.getProperty("java.util.prefs.PreferencesFactory") == DesktopPortablePreferencesFactory::class.java.name)
        System.setProperty("bitchord.listentogether.server", base)
        assertEquals(Path.of(data).toAbsolutePath().normalize(), DesktopStorage.root)
        assertTrue(Preferences.userRoot() is DesktopPortablePreferences)
        val json = Json { ignoreUnknownKeys = true }
        val rest = HttpClient.newHttpClient()
        fun post(path: String, body: String): PartyMembership {
            val response = rest.send(HttpRequest.newBuilder(URI("$base$path"))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString())
            check(response.statusCode() in 200..299)
            return json.decodeFromString(PartyMembership.serializer(), response.body())
        }
        val membership = post("/api/parties", """{"userId":"native-local-probe","deviceId":"native-local-probe","displayName":"Native fixture","autoplayEnabled":true}""")
        val peerMembership = post("/api/parties/${membership.code}/join", """{"userId":"native-protocol-peer","deviceId":"native-protocol-peer","displayName":"Protocol peer"}""")
        val peerFrames = ConcurrentLinkedQueue<JsonObject>()
        val text = StringBuilder()
        val peer = rest.newWebSocketBuilder().header("Authorization", "Bearer ${peerMembership.token}")
            .buildAsync(URI("${base.replace("http:", "ws:")}/ws/parties/${membership.code}"), object : WebSocket.Listener {
                override fun onText(socket: WebSocket, data: CharSequence, last: Boolean): CompletionStage<*> {
                    text.append(data)
                    if (last) { peerFrames.add(json.parseToJsonElement(text.toString()).jsonObject); text.setLength(0) }
                    socket.request(1)
                    return CompletableFuture.completedFuture(null)
                }
            }).get(5, TimeUnit.SECONDS)
        val native = DesktopListenTogether
        fun field(name: String) = native.javaClass.getDeclaredField(name).also { it.isAccessible = true }
        // Only the local identity-adoption boundary is injected; transport, frames and controls are production code.
        field("token").set(native, membership.token)
        field("activePartyServerBase").set(native, base)
        @Suppress("UNCHECKED_CAST")
        val state = field("_state").get(native) as MutableStateFlow<DesktopListenTogether.State>
        state.value = DesktopListenTogether.State(code = membership.code, you = membership.you,
            members = membership.party.members, playback = membership.party.playback, queue = membership.party.queue)
        val connect = native.javaClass.getDeclaredMethod("connect").also { it.isAccessible = true }
        suspend fun until(stage: String, check: () -> Boolean) {
            println("NATIVE_PARTY_WAIT $stage")
            try { withTimeout(8_000) { while (!check()) delay(20) } }
            catch (failure: Exception) {
                println("NATIVE_PARTY_TIMEOUT $stage connection=${state.value.connection} synced=${state.value.clockSynced} " +
                    "sessionPresent=${field("session").get(native) != null} seq=${state.value.playback.seq}")
                throw failure
            }
        }
        try {
            connect.invoke(native)
            until("welcome-clock") { state.value.connection == DesktopListenTogether.Connection.LIVE && state.value.clockSynced }
            assertTrue(state.value.you?.isHost == true)
            assertTrue(state.value.playback.autoplayEnabled)
            val tracks = listOf("a", "b", "a").mapIndexed { index, id -> PartyTrack(videoId = id,
                title = "Fixture $id", artist = "Synthetic fixture", durationMs = 180_000, fromAutoplay = index == 2) }
            native.setQueue(tracks, 1)
            native.setTrack(tracks[1], 2500, false)
            until("queue-track") { state.value.queue.items.size == 3 && state.value.playback.track?.videoId == "b" }
            assertEquals(1, state.value.playback.queueIndex)
            assertEquals(2500, native.partyPositionMs())
            peer.sendText("""{"type":"control","action":"seek","positionMs":5456}""", true).get(2, TimeUnit.SECONDS)
            until("peer-seek") { state.value.playback.positionMs == 5456L }
            native.setHostOnlyControl(true)
            until("host-lock") { state.value.hostOnlyControl }
            peer.sendText("""{"type":"control","action":"seek","positionMs":9999}""", true).get(2, TimeUnit.SECONDS)
            until("peer-refusal") { peerFrames.any { it["error"]?.jsonPrimitive?.content == "host_only" } }
            assertEquals(5456, state.value.playback.positionMs)
            native.setHostOnlyControl(false)
            until("host-unlock") { !state.value.hostOnlyControl }
            native.next()
            until("next-duplicate") { state.value.playback.queueIndex == 2 }
            assertTrue(state.value.playback.track?.fromAutoplay == true)
            repeat(8) { attempt ->
                val oldSession = field("session").get(native)
                connect.invoke(native)
                until("reconnect-clock-$attempt") { state.value.connection == DesktopListenTogether.Connection.LIVE && state.value.clockSynced &&
                    field("session").get(native) != null && field("session").get(native) !== oldSession }
                val sequence = state.value.playback.seq
                native.pause(1111)
                until("reconnect-control-$attempt") { state.value.playback.seq > sequence && state.value.playback.positionMs == 1111L && !state.value.playback.isPlaying }
                assertNotEquals(oldSession, field("session").get(native))
            }
            assertEquals(tracks, state.value.queue.items)
            assertEquals(1111, native.partyPositionMs())
            System.getenv("BITCHORD_NATIVE_PROBE_CODE_FILE")?.let { codeFile ->
                peer.abort()
                val release = rest.send(HttpRequest.newBuilder(URI("$base/api/parties/${membership.code}/leave"))
                    .header("Authorization", "Bearer ${peerMembership.token}")
                    .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.discarding())
                assertEquals(200, release.statusCode())
                until("protocol-peer-leave") { state.value.members.size == 1 }
                Files.writeString(Path.of(codeFile), membership.code)
                until("web-peer-seek") { state.value.members.size == 2 && state.value.playback.positionMs == 8123L }
                native.seek(9123)
                native.setHostOnlyControl(true)
                until("web-peer-leave") { state.value.members.size == 1 }
                assertEquals(9123, state.value.playback.positionMs)
                assertTrue(state.value.hostOnlyControl)
            }
            native.leaveParty()
            assertEquals(null, state.value.code)
            assertEquals(null, field("session").get(native))
            assertEquals(DesktopListenTogether.Connection.OFFLINE, state.value.connection)
        } finally {
            native.leaveParty()
            (field("scope").get(native) as CoroutineScope).cancel()
            (field("http").get(native) as io.ktor.client.HttpClient).close()
            peer.abort()
            rest.close()
        }
    }
}
