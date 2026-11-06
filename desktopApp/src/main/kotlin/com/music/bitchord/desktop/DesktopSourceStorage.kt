package com.music.bitchord.desktop

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.UUID

internal object DesktopSourceStorage {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    internal data class Snapshot(
        val configs: List<DesktopSourceConfig>,
        val originals: Map<String, JsonObject>,
        val opaque: List<JsonElement>,
    )

    fun decode(raw: String): Snapshot {
        val elements = json.parseToJsonElement(raw) as JsonArray
        val originals = mutableMapOf<String, JsonObject>()
        val opaque = mutableListOf<JsonElement>()
        val configs = elements.mapIndexedNotNull { index, entry ->
            runCatching {
                val fields = entry.jsonObject
                val saved = fields["id"]?.jsonPrimitive?.contentOrNull
                val id = saved?.takeIf { it.isNotBlank() && it !in originals }
                    ?: UUID.nameUUIDFromBytes("$index:$entry".toByteArray(Charsets.UTF_8)).toString()
                json.decodeFromJsonElement(DesktopSourceConfig.serializer(), JsonObject(fields + ("id" to JsonPrimitive(id))))
                    .also { originals[id] = fields }
            }.onFailure { opaque += entry }.getOrNull()
        }
        return Snapshot(configs, originals, opaque)
    }

    fun encode(configs: List<DesktopSourceConfig>, previous: Snapshot?): String = JsonArray(
        configs.map { JsonObject(previous?.originals?.get(it.id).orEmpty() + json.encodeToJsonElement(it).jsonObject) } +
            previous?.opaque.orEmpty(),
    ).toString()

    fun normalize(configs: List<DesktopSourceConfig>, preserveOrder: Boolean, legacyJioEnabled: Boolean = false): List<DesktopSourceConfig> {
        val migrated = configs.map { if (it.kind == DesktopSourceKind.MODULE) it.copy(kind = DesktopSourceKind.CUSTOM_MODULE) else it }
        val builtins = listOf(DesktopSourceKind.JIOSAAVN, DesktopSourceKind.SPOTIFY, DesktopSourceKind.YOUTUBE)
        val used = migrated.mapTo(mutableSetOf()) { it.id }
        val seeded = migrated + builtins.filter { kind -> migrated.none { it.kind == kind } }.map { kind ->
            val base = kind.name.lowercase()
            var id = base
            var suffix = 1
            while (!used.add(id)) id = "$base-${suffix++}"
            DesktopSourceConfig(id, kind, enabled = if (kind == DesktopSourceKind.JIOSAAVN) legacyJioEnabled else kind == DesktopSourceKind.YOUTUBE)
        }
        return seeded.sortedBy { if (it.isUserAdded) 0 else if (preserveOrder) 1 else it.kind.rank }
    }
}
