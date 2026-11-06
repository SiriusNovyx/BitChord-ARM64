package com.music.bitchord.data

enum class AtmosAvailability { AVAILABLE, UNAVAILABLE, UNKNOWN }
enum class AtmosPlayback { CONFIRMED, NOT_CONFIRMED, UNKNOWN }

/** Source declarations, input observations and output evidence must not substitute for each other. */
data class AudioDiagnostics(
    val preferenceEnabled: Boolean? = null,
    val sourceDeclaredAtmos: Boolean? = null,
    val observedCodec: String? = null,
    val observedProfile: String? = null,
    val decoderAvailable: Boolean? = null,
    val decoderName: String? = null,
    val outputRoute: String? = null,
    val outputChannels: Int? = null,
    /** Set only by a backend that can verify object rendering for the active stream and route. */
    val objectOutputConfirmed: Boolean? = null,
) {
    val availability: AtmosAvailability
        get() = when {
            decoderAvailable == false -> AtmosAvailability.UNAVAILABLE
            sourceDeclaredAtmos == false && !observedJoc -> AtmosAvailability.UNAVAILABLE
            decoderAvailable == true && (observedJoc || sourceDeclaredAtmos == true) -> AtmosAvailability.AVAILABLE
            else -> AtmosAvailability.UNKNOWN
        }

    val observedJoc: Boolean
        get() = NerdStats.isDolbyAtmosMime(observedCodec) ||
            observedProfile?.let { JOC_PROFILE.containsMatchIn(it) } == true

    val playback: AtmosPlayback
        get() = when {
            observedJoc && objectOutputConfirmed == true -> AtmosPlayback.CONFIRMED
            objectOutputConfirmed == false -> AtmosPlayback.NOT_CONFIRMED
            decoderAvailable == false -> AtmosPlayback.NOT_CONFIRMED
            outputChannels != null && outputChannels <= 2 -> AtmosPlayback.NOT_CONFIRMED
            !observedJoc && normalizedCodec in NON_OBJECT_CODECS -> AtmosPlayback.NOT_CONFIRMED
            else -> AtmosPlayback.UNKNOWN
        }

    val actualFormat: String
        get() = listOfNotNull(NerdStats.codecLabel(observedCodec), observedProfile)
            .joinToString(" · ").ifEmpty { "Unknown" }

    val playbackLabel: String
        get() = when (playback) {
            AtmosPlayback.CONFIRMED -> "Confirmed"
            AtmosPlayback.NOT_CONFIRMED -> "Not confirmed"
            AtmosPlayback.UNKNOWN -> "Unknown"
        }

    val details: String
        get() = listOf(
            "Atmos preference: ${when (preferenceEnabled) { true -> "On"; false -> "Off"; null -> "Unknown" }}",
            "Atmos availability: ${when (availability) { AtmosAvailability.AVAILABLE -> "Available"; AtmosAvailability.UNAVAILABLE -> "Unavailable"; AtmosAvailability.UNKNOWN -> "Unknown" }}",
            "E-AC-3 core decoder: ${when (decoderAvailable) { true -> "Available"; false -> "Unavailable"; null -> "Unknown" }}",
            "Source declares Atmos: ${when (sourceDeclaredAtmos) { true -> "Yes"; false -> "No"; null -> "Unknown" }}",
            "Actual input: $actualFormat",
            "Atmos playback: $playbackLabel",
            "Decoder: ${decoderName ?: "Unknown"}",
            "Output route: ${outputRoute ?: "Unknown"}",
            "Output channels: ${outputChannels ?: "Unknown"}",
            "Decoder availability permits core playback; it does not verify Atmos rendering.",
            "Preference changes apply when the next stream is resolved. Already playing or cached streams may retain their format.",
        ).joinToString("\n\n")

    val summary: String
        get() = listOf(
            "Atmos preference: ${when (preferenceEnabled) { true -> "On"; false -> "Off"; null -> "Unknown" }}",
            "Atmos availability: ${when (availability) { AtmosAvailability.AVAILABLE -> "Available"; AtmosAvailability.UNAVAILABLE -> "Unavailable"; AtmosAvailability.UNKNOWN -> "Unknown" }}",
            "Actual input: $actualFormat",
            "Atmos playback: $playbackLabel",
            "Decoder: ${decoderName ?: "Unknown"}",
            "Output: ${outputRoute ?: "Unknown"}",
        ).joinToString("\n")

    private val normalizedCodec: String?
        get() = observedCodec?.substringBefore(';')?.substringAfterLast('/')?.trim()?.lowercase()

    private companion object {
        val JOC_PROFILE = Regex("joc|atmos", RegexOption.IGNORE_CASE)
        val NON_OBJECT_CODECS = setOf("eac3", "ec-3", "aac", "mp4a-latm", "opus", "mp3", "mpeg", "flac", "alac", "vorbis", "pcm_s16le", "pcm_f32le", "raw", "ac3")
    }
}
