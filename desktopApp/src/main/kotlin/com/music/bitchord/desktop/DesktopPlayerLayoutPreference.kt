package com.music.bitchord.desktop

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.music.bitchord.data.settings.PlayerLayout

@Composable
internal fun DesktopPlayerLayoutPreference(title: String, subtitle: String) {
    val selected by DesktopPlayerSettings.playerLayout.collectAsState()
    Column(Modifier.fillMaxWidth().padding(vertical = 12.dp).selectableGroup()) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        PlayerLayout.entries.forEach { layout ->
            Row(
                Modifier.fillMaxWidth().selectable(
                    selected = selected == layout,
                    role = Role.RadioButton,
                    onClick = { DesktopPlayerSettings.setPlayerLayout(layout) },
                ).padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = selected == layout, onClick = null)
                Column(Modifier.padding(start = 12.dp)) {
                    Text(when (layout) {
                        PlayerLayout.Modern -> DesktopStrings["player_layout_modern", "Modern"]
                        PlayerLayout.Classic -> DesktopStrings["player_layout_classic", "Classic"]
                    })
                    Text(
                        when (layout) {
                            PlayerLayout.Modern -> DesktopStrings["player_layout_modern_subtitle", "Redesigned BitChord v1.8.1 interface."]
                            PlayerLayout.Classic -> DesktopStrings["player_layout_classic_subtitle", "Original BitChord player interface."]
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
