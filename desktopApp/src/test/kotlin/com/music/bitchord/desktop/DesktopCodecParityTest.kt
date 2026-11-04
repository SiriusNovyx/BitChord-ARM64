package com.music.bitchord.desktop

import kotlin.test.Test
import kotlin.test.assertTrue

/** Codec parity with Android, asserted rather than assumed. */
class DesktopCodecParityTest {

    @Test
    fun `every codec the android build plays is decodable here`() {
        val missing = DesktopCodecs.missing()
        assertTrue(
            missing.isEmpty(),
            "this FFmpeg build cannot decode ${missing.joinToString()}, so those tracks would be declined",
        )
    }

    @Test
    fun `EAC3 core decoding permits selecting JOC renditions without claiming object rendering`() {
        assertTrue(DesktopCodecs.canDecodeEac3Core, "no E-AC-3 core decoder")
    }

    @Test
    fun `an unknown codec is reported missing rather than assumed present`() {
        // The probe has to be capable of saying no, or the parity assertion above would pass on a
        // build with nothing in it at all.
        assertTrue(!DesktopCodecs.canDecode("definitely-not-a-codec"))
    }
}
