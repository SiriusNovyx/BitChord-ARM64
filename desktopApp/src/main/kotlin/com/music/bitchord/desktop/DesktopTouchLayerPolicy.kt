package com.music.bitchord.desktop

internal data class DesktopTouchLayerDecision(val allow: Boolean, val outsideLayers: List<Int>)
internal fun desktopTouchLayerDecision(ownerIndex: Int, focusable: List<Boolean>, insideOwner: Boolean): DesktopTouchLayerDecision {
    val outside = mutableListOf<Int>()
    for (index in focusable.lastIndex downTo ownerIndex + 1) {
        outside += index
        if (focusable[index]) return DesktopTouchLayerDecision(false, outside)
    }
    if (!insideOwner && ownerIndex >= 0) outside += ownerIndex
    return DesktopTouchLayerDecision(insideOwner, outside)
}
