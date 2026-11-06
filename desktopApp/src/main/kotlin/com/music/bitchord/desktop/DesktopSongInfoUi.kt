package com.music.bitchord.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.music.bitchord.data.SongInfo
import com.music.bitchord.data.compactCount
import com.music.bitchord.data.model.Song
import kotlinx.coroutines.CancellationException

@Composable
internal fun DesktopSongInfoContent(song: Song, load: suspend (Song) -> SongInfo, onDismiss: () -> Unit) {
    var retry by remember(song) { mutableIntStateOf(0) }
    val info by produceState<SongInfo?>(null, song, retry) {
        value = null
        value = try {
            load(song)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            SongInfo(album = song.albumName, failed = true)
        }
    }
    Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(song.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (song.artist.isNotBlank()) Text(song.artist, style = MaterialTheme.typography.bodySmall, color = DesktopSecondary)
        if (info == null) CircularProgressIndicator()
        val values = listOf(
            DesktopStrings["song_info_album", "Album"] to (info?.album ?: song.albumName),
            DesktopStrings["song_info_release_date", "Release date"] to (info?.releaseDate ?: info?.releaseYear),
            DesktopStrings["song_info_views", "Views"] to info?.views?.let(::compactCount),
            DesktopStrings["song_info_likes", "Likes"] to info?.likes,
            DesktopStrings["song_info_comments", "Comments"] to info?.comments,
        )
        for ((label, value) in values) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(label, style = MaterialTheme.typography.bodySmall, color = DesktopSecondary, modifier = Modifier.weight(1f))
                Text(value ?: if (info == null) DesktopStrings["loading", "Loading…"]
                    else DesktopStrings["song_info_unavailable", "Unavailable"],
                    style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.End, modifier = Modifier.weight(1.4f))
            }
        }
        if (info?.failed == true) {
            Text(DesktopStrings["song_info_error", "Some information could not be loaded."],
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            TextButton(onClick = { retry++ }, modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp)) {
                Text(DesktopStrings["song_info_retry", "Retry"])
            }
        }
        TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp)) {
            Text(DesktopStrings["done", "Done"])
        }
    }
}
