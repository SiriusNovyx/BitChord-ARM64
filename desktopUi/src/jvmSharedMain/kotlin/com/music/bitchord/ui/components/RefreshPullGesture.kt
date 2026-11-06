package com.music.bitchord.ui.components

/** Positive deltas pull content down. Wheel bursts have an idle boundary instead of a release. */
class RefreshPullGesture(
    val threshold: Float,
    private val idleMs: Long = 240L,
) {
    init { require(threshold > 0 && idleMs > 0) }
    var distance: Float = 0f
        private set
    private var lastWheelAt: Long? = null
    private var burstStartedAt = 0L
    private var wheelEligible = false
    private var positiveEvents = 0
    private var cooldownUntil = 0L
    var wheelReady = false
        private set
    val wheelArmed: Boolean get() = wheelEligible && distance >= threshold && positiveEvents >= 3 &&
        (lastWheelAt ?: burstStartedAt) - burstStartedAt >= 300L
    val wheelIdleDelayMillis: Long get() = idleMs

    fun pull(delta: Float, atTop: Boolean) {
        if (!atTop && distance == 0f) return
        distance = (distance + delta).coerceIn(0f, threshold * 2f)
    }

    fun wheel(delta: Float, atTop: Boolean, nowMs: Long) {
        if (!atTop || !delta.isFinite() || nowMs < cooldownUntil) { cancel(); return }
        val last = lastWheelAt
        if (last != null && (nowMs < last || nowMs - last >= idleMs)) {
            // A delayed UI callback must not implicitly complete an old gesture on fresh input.
            cancel()
        }
        if (delta <= 0f) { cancel(); return }
        if (lastWheelAt == null) {
            burstStartedAt = nowMs
            wheelEligible = wheelReady
        }
        positiveEvents++
        if (wheelEligible) pull(delta, atTop)
        lastWheelAt = nowMs
    }

    fun wheelIdle(nowMs: Long): Boolean {
        val last = lastWheelAt ?: return false
        if (nowMs - last < idleMs) return false
        val trigger = wheelArmed
        cancel()
        if (trigger) cooldownUntil = nowMs + 2_000L else wheelReady = true
        return trigger
    }

    fun release(): Boolean = (distance >= threshold).also { cancel() }
    fun cancel() {
        distance = 0f
        lastWheelAt = null
        wheelReady = false
        wheelEligible = false
        positiveEvents = 0
    }
}
