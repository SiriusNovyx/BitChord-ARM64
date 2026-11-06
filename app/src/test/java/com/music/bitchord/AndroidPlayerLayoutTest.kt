package com.music.bitchord

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import com.music.bitchord.auth.AuthStore
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.data.settings.PlayerLayout
import com.music.bitchord.data.settings.LastPlayerScreen
import com.music.bitchord.ui.player.AndroidPlayerHost
import java.lang.reflect.Proxy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class AndroidPlayerLayoutTest {
    @Test fun freshHostShowsClassicBeforePreferencesLoad() {
        assertEquals(PlayerLayout.Classic, AndroidPlayerHost(PreferenceContext()).settings.playerLayout.value)
    }

    @Test fun legacyLayoutValuesAreRetiredWithoutChangingOtherPreferences() {
        listOf<Any?>("modern", "classic", "invalid", "", 7, null).forEach { saved ->
            val context = PreferenceContext()
            val values = context.settings.values
            if (saved != null) values["player_layout"] = saved
            values["last_player_screen"] = "QUEUE"
            values["animated_canvas"] = false
            values["source_modules"] = "opaque-source-configuration"
            // Exercise the production preference loader without starting Android-only stores
            // whose resource/filesystem APIs are absent from the local JVM framework stub.
            AppSettings::class.java.getDeclaredField("prefs").apply { isAccessible = true }
                .set(AppSettings, context.settings.preferences)
            AppSettings::class.java.getDeclaredField("authStore").apply { isAccessible = true }
                .set(AppSettings, AuthStore(context))
            AppSettings.reload()

            assertEquals("saved=$saved", PlayerLayout.Classic,
                AndroidPlayerHost(context).settings.playerLayout.value)
            assertFalse("obsolete preference survived: $saved", values.containsKey("player_layout"))
            assertEquals("QUEUE", values["last_player_screen"])
            assertEquals(LastPlayerScreen.QUEUE, AppSettings.lastPlayerScreen.value)
            assertFalse(AppSettings.animatedCanvas.value)
            assertEquals(false, values["animated_canvas"])
            assertEquals("opaque-source-configuration", values["source_modules"])

            values["player_layout"] = "modern"
            AppSettings.reload()
            assertFalse(values.containsKey("player_layout"))
            assertEquals(PlayerLayout.Classic, AndroidPlayerHost(context).settings.playerLayout.value)
        }
    }

    private class PreferenceContext : ContextWrapper(null) {
        val settings = MemoryPreferences()
        private val stores = mutableMapOf<String, MemoryPreferences>()
        override fun getApplicationContext(): Context = this
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
            if (name == "bitchord_settings") settings.preferences
            else stores.getOrPut(name) { MemoryPreferences() }.preferences
        override fun getSystemService(name: String): Any? = null
    }

    private class MemoryPreferences {
        val values = mutableMapOf<String, Any?>()
        private val pending = mutableMapOf<String, Any?>()
        private val editor: SharedPreferences.Editor = Proxy.newProxyInstance(
            SharedPreferences.Editor::class.java.classLoader,
            arrayOf(SharedPreferences.Editor::class.java),
        ) { proxy, method, args ->
            when (method.name) {
                "remove" -> { pending[args!![0] as String] = null; proxy }
                "apply", "commit" -> {
                    pending.forEach { (key, value) ->
                        if (value == null) values.remove(key) else values[key] = value
                    }
                    pending.clear()
                    if (method.name == "commit") true else null
                }
                else -> if (method.name.startsWith("put")) {
                    pending[args!![0] as String] = args[1]
                    proxy
                } else error("Unexpected editor call: ${method.name}")
            }
        } as SharedPreferences.Editor
        val preferences: SharedPreferences = Proxy.newProxyInstance(
            SharedPreferences::class.java.classLoader,
            arrayOf(SharedPreferences::class.java),
        ) { _, method, args ->
            when (method.name) {
                "edit" -> editor
                "getAll" -> values.toMap()
                "contains" -> values.containsKey(args!![0])
                else -> if (method.name.startsWith("get")) {
                    values[args!![0]] ?: args[1]
                } else error("Unexpected preference call: ${method.name}")
            }
        } as SharedPreferences
    }
}
