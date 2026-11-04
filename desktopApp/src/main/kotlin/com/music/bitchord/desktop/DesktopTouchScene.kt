package com.music.bitchord.desktop

import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.scene.ComposeScene
import androidx.compose.ui.scene.ComposeScenePointer

@OptIn(InternalComposeUiApi::class, ExperimentalComposeUiApi::class)
internal class DesktopTouchScene(private val scene: ComposeScene) {
    private var active = false

    fun dispatch(frame: DesktopTouchFrame) {
        if (frame.phase == 3) {
            cancel()
            return
        }
        active = frame.pointers.any { it.pressed }
        scene.sendPointerEvent(
            eventType = when (frame.phase) {
                0 -> PointerEventType.Press
                2 -> PointerEventType.Release
                else -> PointerEventType.Move
            },
            pointers = frame.pointers.map {
                ComposeScenePointer(
                    id = PointerId(it.id),
                    position = Offset(it.x, it.y),
                    pressed = it.pressed,
                    type = PointerType.Touch,
                )
            },
            timeMillis = frame.timeMillis,
        )
    }

    fun cancel() {
        if (active) scene.cancelPointerInput()
        active = false
    }
}
