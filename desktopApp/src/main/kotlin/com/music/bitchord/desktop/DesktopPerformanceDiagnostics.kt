package com.music.bitchord.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.music.bitchord.ui.player.DesktopVisualMetrics
import com.music.bitchord.ui.player.PerformanceStage
import java.lang.management.ManagementFactory
import java.util.Locale

internal fun performanceReport(): String = buildString {
    appendLine("BitChord optional performance measurements")
    appendLine("Startup: first 120 seconds after enabling/resetting; Warmed: later samples.")
    appendLine("Warmed is a later recording bucket, not proof that shader/model warm-up has finished.")
    appendLine("Recorded for ${DesktopVisualMetrics.collector.elapsedNanos() / 1_000_000_000} seconds; counters split at 120 seconds.")
    appendLine("Frame gaps are animation-clock callback gaps, including deliberate FPS pacing; they are not measured draw FPS or dropped-frame counts.")
    appendLine("Draw timings measure CPU submission, not GPU execution or shader compilation.")
    appendLine("Visual mode: ${DesktopPerformance.mode.value}; background: ${DesktopPerformance.backgroundRate.value.label}")
    for (sample in DesktopVisualMetrics.collector.snapshot()) {
        fun millis(nanos: Long) = String.format(Locale.ROOT, "%.3f", nanos / 1_000_000.0)
        if (sample.stage in setOf(PerformanceStage.LYRICS_COMPOSE, PerformanceStage.LYRICS_FRAME,
                PerformanceStage.PLAYER_COMPOSE, PerformanceStage.BACKGROUND_LAYER_UPDATE,
                PerformanceStage.BACKGROUND_CROSSFADE_UPDATE)) {
            appendLine("${sample.phase} ${sample.stage}: ${sample.count} events")
        } else {
            appendLine("${sample.phase} ${sample.stage}: ${sample.count} events; mean ${millis(sample.meanNanos)} ms, recent p95 ${millis(sample.p95Nanos)} ms, max ${millis(sample.maxNanos)} ms (${sample.retained} retained)")
        }
    }
    val runtime = Runtime.getRuntime()
    appendLine("Heap used/committed: ${(runtime.totalMemory() - runtime.freeMemory()) / 1048576} / ${runtime.totalMemory() / 1048576} MiB")
    for (gc in ManagementFactory.getGarbageCollectorMXBeans()) {
        appendLine("JVM lifetime GC ${gc.name}: ${gc.collectionCount} collections, ${gc.collectionTime} ms")
    }
    appendLine("GPU load, texture allocation, shader warm-up and audible quality require separate device measurements.")
}

@Composable
internal fun DesktopPerformanceDiagnostics() {
    val enabled by DesktopPerformance.metricsEnabled.collectAsState()
    var report by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column(Modifier.weight(1f)) {
                Text("Performance measurements", style = MaterialTheme.typography.bodyLarge)
                Text("Optional bounded counters; no song details are collected", style = MaterialTheme.typography.bodySmall)
            }
            Switch(checked = enabled, onCheckedChange = DesktopPerformance::setMetricsEnabled,
                modifier = Modifier.semantics { contentDescription = "Performance measurements" })
        }
        if (enabled) TextButton(onClick = { report = performanceReport() }) { Text("View measurements") }
    }
    report?.let { content ->
        AlertDialog(
            onDismissRequest = { report = null },
            title = { Text("Performance measurements") },
            text = {
                SelectionContainer {
                    Text(content, Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState()),
                        style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = { TextButton(onClick = { report = null }) { Text("Done") } },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        runCatching {
                            java.awt.Toolkit.getDefaultToolkit().systemClipboard.setContents(
                                java.awt.datatransfer.StringSelection(content), null,
                            )
                        }.onFailure { report = content + "\nClipboard unavailable; select the report text to copy it." }
                    }) { Text("Copy") }
                    TextButton(onClick = { DesktopVisualMetrics.collector.reset(); report = performanceReport() }) { Text("Reset") }
                    TextButton(onClick = { report = performanceReport() }) { Text("Update") }
                }
            },
        )
    }
}
