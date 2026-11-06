package com.music.bitchord.ui.player

/** Uses the same Arabic-script ranges as the shared player, including Persian and Urdu. */
fun usesArabicScript(text: String): Boolean = text.any { ch ->
    val code = ch.code
    code in 0x0600..0x06FF || code in 0x0750..0x077F ||
        code in 0x08A0..0x08FF || code in 0xFB50..0xFDFF ||
        code in 0xFE70..0xFEFF
}
