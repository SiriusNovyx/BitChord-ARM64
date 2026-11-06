# Audio tail and transition-clock audit

Status: **Partial**. EOF/seek and buffered-rate defects are reproduced and corrected locally, with ARM64/x64 regressions verified. The subsequently implemented render-boundary, cue-preparation and promotion corrections are tracked in the [transition scheduling report](transition-render-scheduling-2026-10-26.md). Measurements below retain this phase's original checkpoint; they do not describe every later correction. No new audible improvement, physical-device latency result or complete AutoMix acceptance is claimed.

The previous goal turn made verified progress: the Thai effects-buffer defect was reproduced, corrected, rendered through current compiled classes and tested on ARM64 and emulated x64. New local ZIP/APK candidates are preserved separately. The larger upstream/AutoMix goal remains active.

## Findings before implementation

- `DesktopAudioDecoder.read` returns immediately at codec EOF. Its `SwrContext` is used by `convert` but is not flushed there. `seek` flushes codec buffers without reinitializing the resampler. Retained resampling frames and seek contamination are concrete paths to reproduce; their size and audible significance are not established yet.
- `DesktopPlaybackEngine.readSource` returns immediately on `DesktopDecodedRead.End`. `DesktopRateConverter` retains a windowed-sinc kernel's future input and currently has no end-of-input operation. Its actual missing output must be measured through the production engine.
- `readCurrent` and `readIncoming` can reach EOF while a tempo processor retains audio. The listener's `DesktopAudioSpeed` has `drain`, but the engine's ordinary EOF path calls `finishTrack` directly. `DesktopTempoBuffer.finish` already provides an unstretched continuation when a beatmatch eases out; EOF and promotion need their own verified lifecycle handling.
- `publishPosition` gates `maybeStartCrossfade` behind a 250 ms UI publication bucket. It plans from device-heard time while new mixed audio is queued ahead. This remains a timing hypothesis until planned source-frame positions are compared with rendered PCM and queued latency. The 15-second setting remains fade duration, not an analysis deadline.

## Implementation checklist

- [x] Preserve the exact decoder/converter/engine/tempo/speed sources and protected staging-index hash before editing production behavior. New test paths are separately added; historical tests remain intact.
- [x] Reproduce real FFmpeg EOF frame loss and seek-after-read/EOF behavior using deterministic PCM WAV files at equal, downsampled and upsampled rates.
- [x] Capture production-engine EOF output for mixed sample rates, listener speed and inherited promoted tempo state. Waiting/failure remain separate branches; the complete active incoming-EOF/promotion matrix remains open.
- [x] Correct the reproduced EOF/seek and queued-rate paths. Existing partial-write, cancellation, fallback, pause/seek, gain and clock regressions pass; this is not proof of complete transition-clock correctness.
- [x] Verify repeated EOF, seek/reset, sub-filter fragments, partial output writes and finite durations. Further replacement/promotion combinations remain in the broader scenario matrix.
- [x] Measure planned versus rendered transition onset and queued output latency before changing scheduling.
- [x] Run focused and full ARM64/x64 desktop regressions. Shared JVM results are reused where Gradle reports up-to-date. This phase modifies only desktop production/test sources; no Android/shared production API changes require an Android rebuild here.
- [x] Reconcile the nine reviewed production/test/report files into the separate integration candidate. Both staging indexes remain byte-identical; no commit or publication is performed.

Physical Surface listening, output-device latency, GPU/thermal behavior and long-session stability remain separate verification. Deterministic captures cannot establish subjective musical quality or nine-hour reliability.

## Confirmed defects and implementation

1. **FFmpeg tail and seek history.** The original seven-case regression has six failures. At 48 → 24 kHz, a 100 ms stereo PCM fixture loses 16 output frames; 24 → 48 kHz loses 32. Seeking after EOF replays a negative terminal marker before the positive beginning (`-0.3051888` instead of `0.24414061`). `DesktopAudioDecoder` now drains the resampler, resets it after a successful seek, accounts for its delay in output timestamps and clears the reported sample count at EOF.
2. **Very short FFmpeg fragments.** The first correction passes seven of eight cases but still returns no samples after seeking into the final 0.5 ms. Pinned FFmpeg 7.1.1's [initial-filter handling](https://github.com/FFmpeg/FFmpeg/blob/n7.1.1/libswresample/resample.c) waits for enough input to initialize the reflected filter. Its ordinary flush can leave such tiny fragments unrendered. A bounded zero extension through the existing supported `swr_inject_silence` API supplies filter support only when ordinary draining cannot complete. Output is capped to the real decoded input's rational duration; extension frames never lengthen playback. Tests cover 1, 2, 8, 24 and 65 input frames across three conversion ratios, repeated EOF and a half-millisecond terminal seek. Fixture input bytes and dependency versions are unchanged.
3. **Engine rate-converter tail.** The engine loses 28 frames in a 100 ms 44.1 → 48 kHz capture with seven-frame short writes. `DesktopRateConverter.finish` now renders the kernel's retained support with zero extension and an exact rational frame limit. `readSource` returns that output before true EOF. Repeated completion emits no additional samples. The tests cover 28 rate/length combinations, twelve complete-waveform block-size comparisons and independent opposite-polarity channels.
4. **Buffered tempo/listener EOF.** A 1,000-frame clip can disappear completely at non-default listener speed or from an inherited tempo processor. True EOF now drains these buffers before ending the track. The existing `DesktopAudioSpeed.drain` deliberately returns its source continuation unstretched; this behavior is retained, and the internal source clock advances at 1x for that terminal buffer. These fixtures now retain all 1,000 frames and end at 20,833 microseconds on the internal source clock. Stalled input and failed reads do not enter the EOF drain branch.
5. **Queued rate ownership.** Two additional failing tests show that a future 1x request relabels already rendered 1.1x PCM as 1x, and that a FIFO containing stretched output plus its raw tail is counted entirely at 1x (expected weighted rate `1.0988235294`). `DesktopAudioSpeed` now reports the nominal rate of its produced output. `DesktopTempoBuffer` retains compact rate spans alongside queued samples, merges adjacent equal-rate spans and calculates the consumed block's weighted source-time rate. The engine uses that consumed rate rather than the next request or a drained flag. The metadata follows the bounded PCM FIFO; no timer, analysis worker or extra audio effect is introduced.

The queued-rate correction concerns nominal time accounting at block boundaries. It does not establish per-sample WSOLA provenance, exact device latency, global-stretcher continuity through every promotion, or complete lyric alignment. Those remain explicit timing work.

## Exact files changed in this phase

- `desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopAudioDecoder.kt`
- `desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopAudioEngine.kt`
- `desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopAudioSpeed.kt`
- `desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopRateConverter.kt`
- `desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopTempoBuffer.kt`
- `desktopApp/src/test/kotlin/com/music/bitchord/desktop/DesktopAudioTailTest.kt` (new, eleven tests)
- `desktopApp/src/test/kotlin/com/music/bitchord/desktop/DesktopRateConverterTailTest.kt` (new, three tests)
- This report and the linked audio-quality follow-up report.

No font/UI, Android production, Web, backend, dependency-version, provider/authentication, EQ, spatial-effect or limiter-strength change is introduced by this phase. Existing user preferences and source matching remain intact.

## Actual verification

| Run | Actual result |
| --- | --- |
| `audio-tail-red` | Seven tests execute; six fail, reproducing missing frames, lost clips and stale seek data. |
| `audio-tail-first-fix` | Eight execute; seven pass, terminal short-fragment seek still fails. |
| `audio-tail-subfilter-fix` | Nine pass, including the fifteen sub-filter rate/length cases. |
| `audio-tempo-clock-red` | Eleven execute; two rate-ownership assertions fail. |
| `audio-tempo-clock-fix` | Selected tail/tempo/speed/playhead suites pass. |
| `audio-tail-clock-final-arm64` | Exit 0, 113.57 s. **624 desktop passes/six skips** on each of Java 21 and Java 25; both desktop tasks actually execute. Shared JVM **104 passes** are the unchanged up-to-date results from the preceding executed run, not fresh execution here. |
| `audio-tail-clock-final-x64` | Exit 0, 133.88 s. **624 desktop passes/six skips**, actually executed under x64 Java 21 emulation with the verified ARM64 Java 25 Gradle driver. Shared JVM is up-to-date. This is not physical x64-hardware verification or a performance comparison. |

Timestamp-checked raw XML, run records, log/source hashes and protected-index checks are retained separately in `audio-tail-20261026/final-{arm64,x64}-results`. The fourteen added tests pass in all three desktop executions. Full ARM64 testing before the rate-metadata change also passes; its older counts are retained separately rather than substituted for the final source.

The six existing skips are display-dependent cases: background rendering, top-of-Home wheel input, three AWT touch-adapter/window cases and native Windows touch injection. Their existing `GraphicsEnvironment.isHeadless()` assumptions skip them in these Gradle JVMs. This is a display-environment limitation, not an audio-test pass; no skip or disabled check is added by this phase.

The production automatic-pair replay still produces exactly 864,000 stereo frames, one handoff and zero overrange samples. Its generated source class has the same SHA-256 `7186e3e8372eb704ae71f44bcf12bdad7481c1c00d5ff1c79007bb1965c5609e`. The complete before/current WAV files are byte-identical, SHA-256 `dff4fa2e2e0443015be63e411f9d7ff834eb74aa30f241fd1713016db49c8307`, peak `0.8868080974`. This establishes preserved PCM for that generated fixture and saved plan. It is not a new listening test of the reported recordings or evidence of general musical quality.

## Original onset measurements before the subsequent correction

The following is the retained failing baseline. The [scheduling follow-up](transition-render-scheduling-2026-10-26.md) records the actual correction and its verification limits.

The actual production controller is driven with an outgoing silent PCM track and an incoming constant marker. Track length is 10.333 s; a fixed four-second automatic crossfade plans to start at 6.333 s. The deterministic sink models 0, 100 and 250 ms of queued output. The source markers are inspected in rendered PCM, not inferred from UI labels.

| Queued output | First blend frame at 48 kHz | First nonzero incoming frame | First nonzero minus planned start |
| --- | ---: | ---: | ---: |
| 0 ms | 312,000 | 312,064 | 168.333 ms |
| 100 ms | 316,800 | 316,864 | 268.333 ms |
| 250 ms | 324,480 | 324,544 | 428.333 ms |

The intentional existing fader/filter ramp accounts for 64 frames (1.333 ms) between the first blend block and the first nonzero marker. The scheduling error itself is therefore 167, 267 and 427 ms in these cases. The source shows the causal boundaries: the planner is behind the 250 ms UI publication gate, it compares device-heard time, and its later mixed blocks enter the output queue. Moving a UI label would not correct this.

The first standalone probe inherits an x64 native resource after the successful x64 build and logs an ARM64 load refusal. That is retained as a fixture limitation, with no inference result claimed. The corrected standalone fixture prepends the existing verified ARM64 analysis DLL (`0xaa64`, SHA-256 `f5dc042b0584b365dae5c5a6ed262a3d9c8cd4b25da04d0ad85c1b40e79b197a`), changes no project source/package and reproduces the same three measurements without a native-load error. These are deterministic queue models, not physical WASAPI latency measurements. A completed diagnostic probe is not a passing onset-accuracy test.

### Scheduling checklist at this original checkpoint

Current completion and remaining work for these items are recorded in the scheduling follow-up rather than inferred from this historical list.

- [ ] Add failing production-engine onset regressions independent of the diagnostic fixture, covering non-quarter-second plan boundaries and several decoder block sizes.
- [ ] Separate sample rendering/arming from throttled UI publication, retaining the device-heard clock for diagnostics and lyrics.
- [ ] Use an explicit rate-aware render/input position, account for queued output and retained stretch input, and split a block at the planned source-frame boundary rather than accepting another block of delay.
- [ ] Preserve suffix ownership and single DSP/gain application during partial writes and incoming waits; prepare cue/seek work early under the existing generation ticket.
- [ ] Test listener speed, mixed source rates, pause/seek, stale/ABA preparation, stalled decoding and source failure at the scheduling boundary.
- [ ] Verify global stretcher state and clock continuity through promotion, then repeat the automatic-pair capture and full regressions.

The fifteen-second skip value remains a fade duration. No preparation deadline, reduced analysis quality, disabled AutoMix feature or provider-restriction workaround is introduced.

The 26 October Thai ZIP/APK predates these subsequent desktop audio changes. It remains immutable and verified for its recorded source; it must not be presented as containing the later tail/rate fixes. The full integration mission and publication gate remain open. No commit, push, upload or PR has occurred, and the authoritative staging-index SHA-256 remains `e06ebe4e519c5aea6049c651642786a80d8cad8815b8cf3b895aab346b079a7a`.
