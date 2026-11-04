# Transition scheduling, cue preparation and promotion

Status: **Partial**. Render scheduling, cue preparation, promotion, fade-end and shared SMART-plan stability defects have deterministic reproductions and local corrections. Renewed ARM64 regression, actual Android unit execution, package verification, production capture and a bounded repeated-transition lifecycle probe pass; the final x64 run also passes. Broader musical analysis and extended-session acceptance remain separate gates. No physical latency, sound-quality improvement or complete AutoMix acceptance is claimed.

## Read-only findings and implementation checklist

The authoritative source remains `.work/upstream-integration-20261018` under the outer development folder. Original HEAD is `efc2079d18847efe06798ecca4878fd3731d6e09`; the original staging-index SHA-256 is `e06ebe4e519c5aea6049c651642786a80d8cad8815b8cf3b895aab346b079a7a`. Prior production files and the historical recovery fixture are preserved separately before editing. Full runs use a serial build lock, existing toolchains and unique evidence paths. No reset, staging, dependency change or publication is part of this phase.

- [x] Measure planned versus actual rendered onset using the production engine.
- [x] Reproduce onset lateness independently in regression tests before changing scheduling.
- [x] Move scheduling to the render clock and split raw blocks at the onset boundary.
- [x] Prepare known incoming cues asynchronously before the transition; retain ownership checks.
- [x] Test stalled preparation, controls, replacement and same-track reselection.
- [x] Reproduce listener-stretcher audio loss at promotion, retain continuous state and account for held input in the heard clock.
- [x] Reproduce incoming overpull at the fade endpoint and cap the request to the required whole frames.
- [x] Verify all 23 existing playback-recovery cases, five cue/preparation cases and 12 onset/promotion cases together.
- [x] Complete full ARM64 Java 21/25 and emulated x64 Java 21 regressions for the render/cue/promotion corrections.
- [x] Generate and structurally verify a separate checkpoint native Windows ARM64 portable package; retain older delivered candidates.
- [x] Repeat the generated automatic-pair capture and inspect peaks and actual timing; identify the independent SMART-plan selection defect.
- [x] Reproduce both SMART temporal-selection failures before changing the shared planner; retain the expired-window guard.
- [x] Correct eligibility through a candidate's active window; all 107 shared JVM tests pass.
- [x] Repeat full cross-platform regressions, capture and packaging after the shared planner correction.
- [ ] Complete broader analysis/transition strategy and extended-session verification.
- [x] Project the eleven reviewed scheduling/planner/test/report files into the pending history merge with both indexes preserved.

## Confirmed defects and corrections

### Render scheduling used the heard clock and a throttled UI update

The planner previously armed audio only after the position publisher crossed its 250 ms bucket. It also compared against device-heard position even though newly mixed samples would be queued ahead of the listener. A deterministic 48 kHz plan starts at frame 303,984 (6.333 seconds); first blended frames were 312,000, 316,800 and 324,480 with modeled output queues of 0, 100 and 250 ms. These are approximately 167, 267 and 427 ms late, before the existing fader/filter ramp.

The engine now tracks the next source position at the mixer input separately from the device-heard clock. A cached transition plan is invalidated for incoming identity/settings changes and refreshed at the existing UI cadence. Raw decoded blocks are split at the source-frame onset boundary; the untouched suffix is retained for the next render step. The production probe now starts the blend at frame 303,984 in all three queue models. First nonzero PCM remains 64 frames later because the existing 1.333 ms ramp is preserved.

This proves sample-boundary scheduling in the modeled engine cases, not physical WASAPI/device latency. Beat/downbeat analysis accuracy and WSOLA's local musical phase remain distinct questions.

### Known incoming cues were sought only when the fade began

Before the correction, a known silent intro cue was first sought when the blend was armed. Slow seek/decoder work therefore consumed transition time even if useful preparation time was available earlier. Preparing the cue now uses the existing asynchronous decoder worker as soon as the current plan is available. An unchanged cue retains buffered data, mode changes can restore the intro, and current preparation-ticket identity guards obsolete work.

The 15-second setting remains a fade duration. This change introduces no preparation deadline, worker, timer, reduced analysis quality or account/provider behavior. Tests deliberately stall cue seeking and verify that outgoing playback and Pause remain responsive; replacement and A/B/C/new-B cases cannot arm the obsolete incoming deck.

The first attempted cue fixture requested skipping unproven music. The existing planner correctly clamped that cue to zero. The valid red/green fixture contains an actual silent lead and matching analysis; planner safeguards were preserved.

### Promotion discarded the listener stretcher's retained audio

Resetting the global listener-speed processor at deck promotion discarded pending overlap/continuation state. A deterministic transient fixture differed from continuously stretched reference PCM across 4,704 samples. Removing only that promotion reset preserves the continuous reference waveform. Seek and output-chain reset behavior remains intact.

Retaining audio exposed a second clock accounting error: the promoted playhead omitted input still held by the stretcher. A ramp-encoded PCM test measured about 57 ms of advance. The processor now exposes its held source-frame continuation; queued-source accounting includes those input frames separately from output/device frames. The test checks the resulting heard clock within the existing nominal WSOLA mapping tolerance of 20 ms at zero and 250 ms modeled queue depth. This tolerance does not establish sample-accurate lyric phase through every tempo/pitch transformation.

### The fade requested incoming PCM beyond its endpoint

An outgoing 4.02-second block could ask the incoming decoder for 4.02 seconds even though only a four-second fade remained. Supplying the complete four seconds of incoming PCM was insufficient to advance that block. The engine now caps reads to the remaining fade's whole interleaved frames, including EOF padding. Outgoing samples beyond a completed fade are discarded; prefade raw suffixes remain retained. If the final blended input produces no output yet because it is held by the listener stretcher, pending promotion can proceed and feed the retained processor from the next deck. Partial writes still retain their rendered owner until accepted.

The independent end-boundary regression was red while all 23 corrected recovery fixtures passed, separating this engine defect from the historical fixture issue below.

### SMART vocal rerouting discarded its chosen window at an unrelated start

The generated reported-pair replay exposed another defect after the engine corrections. Read-only calls into the actual compiled engine's planner at 200.000, 206.900 and 207.000 seconds select a 208.922442-second start ending at 214.000. At 207.063, identical analysis instead selects an already-past 207.062442-second start ending at 212.140, with greater measured vocal overlap. The compiled class origin is the authoritative `desktopApp/build/classes/kotlin/main`, not a historical probe implementation.

The shared planner allowed vocal rerouting only before the rejected primary window's start and allowed an alternative only before its own start. The candidate therefore vanished at precisely the time the renderer needed to arm it. Cached render scheduling cannot anticipate a plan that later moves backward into the past.

A wholly synthetic common test reproduces both directions: the later vocal-safe 209.000-second plan jumps backward to 207.140 when playback reaches 207.150; an earlier 207.140-second choice disappears at its own start and jumps forward to 209.000. Two assertions fail before the correction; the expired-window safety case already passes. The correction selects among candidates until their transition ends, preserving candidate limits, vocal-conflict thresholds and score/ranking logic. No persistent planner state, analysis job, setting or new dependency is added. The three regressions and all existing common JVM tests pass (107 total). Native-engine capture and renewed full platform verification follow separately.

The correction is in the existing shared AGPL-covered planner; its license and provenance header remain intact. Android and native Desktop consume this shared timing behavior. The upstream Web TypeScript pipeline remains separate and is not claimed to have native parity.

## Exact files changed

- `desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopAudioEngine.kt`
- `desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopAudioSpeed.kt`
- `desktopApp/src/test/kotlin/com/music/bitchord/desktop/DesktopTransitionOnsetTest.kt` (12 new cases)
- `desktopApp/src/test/kotlin/com/music/bitchord/desktop/DesktopIncomingCuePreparationTest.kt` (five new cases)
- `desktopApp/src/test/kotlin/com/music/bitchord/desktop/DesktopAudioRecoveryTest.kt` (fixture timeline correction; all 23 original tests/checks retained)
- `shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/TransitionPlanner.kt` (vocal-reroute temporal eligibility)
- `shared/src/commonTest/kotlin/com/music/bitchord/playback/smart/TransitionPlanStabilityTest.kt` (three new cases)
- This report and the prior tail/integration reports' current-status links.

No limiter strength, gain curve, user volume, EQ, spatial effect, font, UI layout, source priority, authentication, platform-specific Android UI, Web/backend or dependency version is changed in this phase. The shared timing correction applies to both native Desktop and Android.

## Actual verification and retained failures

Raw evidence is under the outer `.work/transition-onset-20261026` and `.work/implementation-20261022` audit folders and is excluded from a future source upload.

| Run | Actual result | Interpretation |
| --- | --- | --- |
| `transition-onset-red` | Eight failures | Original onset defect reproduced. |
| `transition-onset-speed-matrix` | 10 passed | Onset boundaries, source/device rates, partial writes and listener-speed references. |
| `incoming-cue-preparation-valid-red` | Failed known-cue preparation | Reproduction uses proven silent lead. |
| `listener-stretcher-promotion-red` | Transient comparison failed | Promotion reset drops continuity. |
| `listener-stretcher-retained-clock-red` | Transient passed, heard-clock failed | Held stretcher input omitted from the clock. |
| `transition-render-full-arm64` | Failed: seven recovery cases | Historical fixture primer started exactly on the newly correct onset. Not a full passing regression. |
| `fade-end-boundary-valid-red` | End-boundary failed; all 23 recovery cases passed | Independent incoming-overpull reproduction. |
| `transition-boundaries-recovery-fix` | 40 passed, zero failures/errors/skips; 58.33 seconds | Combined selected production-engine regression, ARM64 Java 21 test JVM. |
| `transition-render-final-arm64` | Exit 0; 381.23 seconds | Desktop Java 21 and Java 25 each execute 641 passes/six existing display skips. Desktop UI executes 96 passes; Shared UI executes 72. Shared 104 and Android unit 1,146 passes/one skip are up-to-date from prior execution. Windows portable, Android, TV and Wear packaging succeed. This checkpoint predates the shared-plan correction. |
| `transition-render-final-x64` | Exit 0; 405.36 seconds | Desktop actually executes 641 passes/six existing display skips under x64 Java 21 emulation; ARM64 Java 25 drives Gradle. Shared 104 remains up-to-date. This checkpoint predates the shared-plan correction. |
| `plan-stability-synthetic-red` | One failure | An external synthetic test reproduces the later-window jump against the production shared JAR. |
| `transition-plan-stability-shared-red` | Three execute; two fail, one passes | Both temporal-selection defects reproduced; expired-window guard remains valid. |
| `transition-plan-stability-shared-fix` | 107 pass, zero failures/errors/skips; 65.45 seconds | All shared JVM tests execute after the planner correction. Cross-platform/package follow-up is separate. |
| `transition-plan-final-arm64` | Exit 0; 389.56 seconds | Desktop Java 21 and Java 25 each execute 641 passes/six existing display skips. Desktop UI executes 96 passes; Shared UI executes 72. Android unit tests actually execute 1,146 passes/one existing skip. Shared 107 remains up-to-date from the preceding full shared execution. Windows portable, Android, TV and Wear packaging succeed. |

## Current compiled capture and bounded lifecycle results

`transition-plan-pair-20261026` reuses the exact generated bass/transient source fixture and isolated saved analysis from the earlier reported-pair replay. The actual engine now retains the chosen vocal-safe plan: start `208.92244228545087`, end `214.0`. Starting playback at 200 seconds, its first blended output is frame 428,278 at 48 kHz, within one frame of the selected source boundary. The fixed capture has 864,000 stereo frames, one handoff, peak approximately `0.8756049` and zero overrange samples. Its waveform intentionally differs from the earlier captures because the selected timing and fade endpoint have changed. The analysis belongs to the saved pair, not to these generated tones; this is a production scheduling/DSP regression fixture, not proof of musical quality or a new listening test of the recordings.

`repeated-transition-lifecycle-20261026` runs the actual engine/decks for 24 SMART transitions through 25 unique generated tracks with cached synthetic analysis. It seeks to 40 seconds before each automatic transition, gates output after promotion, checks one queue handoff, pauses and advances to the next pair. It observes 21,772,800 output frames (453.6 seconds of accelerated 48 kHz PCM), 25 decoder opens, 25 source closes, peak registered decoder count two, zero overrange samples, an empty decoder registry after shutdown and a terminated audio thread. Peak sample value is `0.38654914`.

The lifecycle run takes 10,988 ms inside the fixture; JUnit reports 11.297 seconds. Sampled heap use is 31,818,288 bytes initially, 40,606,264 finally, and 40,612,888 maximum. These are one-run observations without a calibrated benchmark or forced collection. They do not measure native/process memory, prove no long-term leak, exercise uncached inference, simulate all CPU contention, or establish physical output timing. The probe is retained as an external audit tool, not silently counted among the 647 Gradle desktop cases. Longer playback, real sources and native-analysis/resource behavior remain separate acceptance work.

The historical recovery helper sought to 59.000 seconds, then emitted a supposedly unmixed 10 ms primer, exactly at its one-second fade boundary for a 60-second song. Correct onset scheduling legitimately began consuming incoming PCM during that primer. The fixture now seeks to 58.990 seconds and ends the primer at 59.000; matching position/recovery-offset expectations were shifted by 10 ms. All original ownership, partial-write, failure and cancellation assertions remain. Failed XML and the unmodified fixture are preserved. Other fixture races corrected in this phase published metadata before an atomic signal and released a test-only outgoing seek latch; those are not claimed as engine defects.

## Remaining acceptance limits

The generated fixtures establish the specific invariants they test. They do not prove pleasant musical transitions, physical Surface latency, object-based Atmos rendering, GPU improvements, native/Web musical parity or nine-hour stability. Physical listening must compare the same sources, gain/effect settings and fresh analysis, including the user-reported automatic Susan Boyle/Halsey pair. The previous user acceptance of bass distortion applies to the earlier October 25 package and must not be silently assigned to this new build.

The [prepared-source recovery follow-up](prepared-source-recovery-2026-10-26.md) now records reproduced early cue-seek/read failure handling, bounded owned refresh, cancellation, stale/ABA recovery and known-failed Skip reservation. Its current Native tests execute 648 passes/six existing display skips on ARM64 Java 21/25 and emulated x64 Java 21; generated healthy-pair PCM remains byte-identical to this scheduling checkpoint. Its 24-transition/eight-failure probe closes all 33 opened decoders. Further tempo-span provenance, musical phase analysis, output-device changes and broader real-session/resource behavior remain audit work. The complete integration mission remains active. No commit, push, upload, PR creation or publication is claimed.

## Final emulated x64 and package checkpoint

`transition-plan-final-x64` exits 0 in 183.35 seconds. The actual x64 Java 21 test JVM executes 641 desktop passes with six existing display-dependent skips, zero failures/errors. ARM64 Java 25 drives Gradle; shared 107 JVM results are up-to-date from their preceding executed full run. Archived XML timestamps, JVM executable paths, source hashes and original-index hash are checked separately for each architecture. The six desktop skips and one existing Android unit skip are retained environment/test assumptions, not newly disabled checks.

The corrected generated WAV SHA-256 is `d59c1203c73f74f296086f1302394e894614e5aee28d1ff2d0ba787e6e0f434b`; first blend is 0.770298 frames after the exact planned boundary. This is within one source frame and includes no claim about physical output latency or subjective quality.

- `BitChord-smart-plan-windows-arm64.zip`: 408,638,894 bytes; SHA-256 `4a791d699d48795baf73054021c36693e14af230cb27d7a4b0975a01d9a773af`.
- `BitChord-smart-plan-android-arm64-debug.apk`: 99,297,669 bytes; SHA-256 `d9ab1f27c91b6a18daa4b1a461243b20144a3b55f2e4bb373335f133183f65b6`.

These immutable candidates are in the outer `.work/rejected-surface-20261023/deliverables/2026-10-26-smart-plan-stability` folder. The prior Thai and render-only candidates remain untouched. ZIP CRC, 80 standalone ARM64 PE files, 34 selected native JAR resources, 1,355 current application classes, current UI/shared JARs, the unchanged Bangna font and existing Spotify helper/notices are verified. The APK has only arm64-v8a native libraries (ten AArch64 ELF files), valid Dev signature, package com.dev.bitchord/version 1.8.1/code 29, and the same update certificate as the October 25 Dev candidate. The shared planner correction is included in both packages. Android TV/Wear build outputs are checked by Gradle but are not represented as physical-device tests or delivered as these APK files.

The complete upstream capability audit, extended real-source listening, native model/phase comparisons and physical Surface/Android acceptance remain open. No commit or GitHub publication is performed. The staging index remains protected.
