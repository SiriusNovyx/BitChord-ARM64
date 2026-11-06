package com.music.bitchord.desktop

import com.music.bitchord.ui.player.BackgroundFrameRate
import com.music.bitchord.ui.player.backgroundFramesPerSecond
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

internal object DesktopPerformance {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private const val KEY = "desktop_performance_mode"
    private val _mode = MutableStateFlow(
        runCatching {
            DesktopPerformanceMode.valueOf(
                (System.getProperty("bitchord.performance") ?: DesktopPersistence().string(KEY, "BALANCED")).uppercase(),
            )
        }.getOrDefault(DesktopPerformanceMode.BALANCED),
    )
    val mode: StateFlow<DesktopPerformanceMode> = _mode
    private const val FPS_KEY = "background_animation_fps"
    private val rateSetting = DesktopBackgroundRateSetting(
        { DesktopPersistence().string(FPS_KEY, "dynamic") },
        { DesktopPersistence().saveString(FPS_KEY, it) },
    )
    val backgroundRate = rateSetting.rate
    val metricsEnabled = MutableStateFlow(DesktopPersistence().boolean("performance_metrics", false))
    init { com.music.bitchord.ui.player.DesktopVisualMetrics.collector.setEnabled(metricsEnabled.value) }
    private val playing = MutableStateFlow(false)
    private val onBattery = MutableStateFlow(false)
    val backgroundFramesPerSecond = combine(_mode, backgroundRate, DesktopWindowVisibility.visualActive, playing, onBattery) {
            mode, selected, visible, active, constrained ->
        backgroundFramesPerSecond(if (mode == DesktopPerformanceMode.PERFORMANCE) selected else BackgroundFrameRate.DYNAMIC,
            visible, active, constrained)
    }.stateIn(scope, SharingStarted.Eagerly, 15)
    val policy: StateFlow<DesktopVisualPolicy> = combine(_mode, DesktopWindowVisibility.visualActive) { mode, active ->
        DesktopVisualPolicy(mode, visible = active, minimized = false)
    }.stateIn(scope, SharingStarted.Eagerly, DesktopVisualPolicy(_mode.value, true, false))

    val reduceMotion = combine(policy, DesktopAppearanceSettings.reduceAnimation) { visual, preferred ->
        visual.reduceMotion || preferred
    }.stateIn(scope, SharingStarted.Eagerly, policy.value.reduceMotion || DesktopAppearanceSettings.reduceAnimation.value)
    val reduceBlur = combine(policy, DesktopAppearanceSettings.reduceDynamicBlur) { visual, preferred ->
        visual.reduceBlur || preferred
    }.stateIn(scope, SharingStarted.Eagerly, policy.value.reduceBlur || DesktopAppearanceSettings.reduceDynamicBlur.value)

    fun setMode(value: DesktopPerformanceMode) {
        DesktopPersistence().saveString(KEY, value.name)
        _mode.value = value
    }

    fun setBackgroundRate(value: BackgroundFrameRate) = rateSetting.set(value)
    fun setPlaying(value: Boolean) { playing.value = value }
    fun setMetricsEnabled(value: Boolean) {
        DesktopPersistence().saveBoolean("performance_metrics", value)
        com.music.bitchord.ui.player.DesktopVisualMetrics.collector.setEnabled(value)
        metricsEnabled.value = value
    }

    fun samplePower() {
        if (!DesktopPlatform.isWindows) return
        runCatching {
            val status = com.sun.jna.Memory(12)
            val query = com.sun.jna.NativeLibrary.getInstance("kernel32")
                .getFunction("GetSystemPowerStatus", com.sun.jna.Function.ALT_CONVENTION)
            status.use {
                if (query.invokeInt(arrayOf(status)) != 0) {
                    onBattery.value = status.getByte(0).toInt() == 0 || status.getByte(3).toInt() == 1
                }
            }
        }
    }
}
