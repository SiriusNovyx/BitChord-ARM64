package com.music.bitchord.desktop

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

@Composable
internal fun DesktopOutputPillPreference() {
    val shown by DesktopPlayerSettings.showListenTogetherButton.collectAsState()
    Row(
        Modifier.fillMaxWidth().toggleable(
            value = shown,
            role = Role.Switch,
            onValueChange = DesktopPlayerSettings::setShowListenTogetherButton,
        ).padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(DesktopStrings["show_listen_together_button", "Show Listen Together button"], style = MaterialTheme.typography.bodyLarge)
            Text(
                DesktopStrings["show_listen_together_button_subtitle", "When hidden, audio output stays available. The button remains visible while in a party."],
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = shown, onCheckedChange = null, colors = desktopSwitchColors())
    }
}
