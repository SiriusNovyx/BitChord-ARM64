# Surface audio follow-up — investigation in progress

Local implementation only. No publication, staging, commits, branch changes, or Git index writes are authorized. The source root is `E:\Development\BitChord-main\.work\upstream-integration-20261018`.

## Acceptance and package provenance

The user confirms touchscreen scrolling, background FPS controls, and Home Refresh work on the Surface Pro 11. Preserve those implementations. After testing the new 25 October candidate, the user also confirms Audio Details now shows the playing format and has the compact target, and the reported automatic pair no longer has bass distortion. Broader AutoMix listening quality remains under investigation; sample-peak protection is not evidence of transparent limiting or good musical transitions.

The user identified the tested Windows ZIP by SHA-256 prefix `e869a703`. A fresh full-file hash matches `e869a703bec61c0949000982aa405ab503b97bd25e29766215f20bcc6ba1abc4`. The ZIP is in `.work/rejected-surface-20261023/deliverables/2026-10-24-surface-diagnostic`. Its `DesktopStreamFormat.class` and `PlayerControlsKt.class` exactly match the current compiled classes before this follow-up. Inspection of the packaged bytecode confirms the snapshot construction defaults the track ID to null. These diagnostics defects are present in the tested candidate and current source, rather than explained by an outdated package. Later output-headroom and paused-clock changes are not in that immutable ZIP.

HEAD is `efc2079d18847efe06798ecca4878fd3731d6e09`; this alone does not identify the uncommitted build. Recoverable original baselines plus `quality-20261023/audio-details-pre-fix-20261024.patch` capture the local changes preceding this follow-up. The checkpoint verifies unchanged HEAD and exact index bytes.

## Confirmed diagnostic defects

1. `DesktopApp.kt` converts the observed format into a snapshot without a media ID. `DesktopStreamFormat.nerdSnapshot` has no ownership parameter. Both production UI forks require `snapshot.mediaId == song.videoId`; every desktop snapshot is consequently rejected, regardless of AutoMix support. The prior UI tests injected a correct media ID and therefore missed the production boundary.
2. Both `PlayerControls.kt` implementations attach `clickable` to a full-width parent. Prior rendering tests checked only a minimum target size and never rejected an excessive hit region.
3. Current/upcoming AutoMix analysis states are concatenated with a slash. Unsupported-source detection is accurate for the user's test source. Only its presentation should change for this feedback.

## Implementation and verification checklist

- [x] Read the updated objective; identify the authoritative checkout, tested ZIP, actual producer and UI guard; verify Git protection checkpoint and absence of active Java builds.
- [x] Reproduce production diagnostic ownership failure and oversized hit region in tests before corrections.
- [ ] Bind measured format, source, and owner to the same playback observation; preserve stale-event rejection, partial evidence, startup/recovery, seek and track/source changes, and player recreation.
- [ ] Compact accessible diagnostics target; verify actual pointer/semantics/drawing bounds, inside/outside clicks, seek behavior, hover/focus and 100%/200% fonts in Windows Classic/Modern and Android Classic.
- [ ] Retain available codec/rate/depth/channels/source/decoder/route evidence independently of AutoMix; label unavailable fields honestly and current/upcoming AutoMix states separately.
- [ ] Audit the entire AutoMix production signal path: decode, sample/channel conversion, resampling, analysis, beat/tempo alignment, stretching, per-deck EQ/normalization, crossfade sum, spatial effects, final limiter/volume, partial output writes and WASAPI.
- [ ] Audit planning and ownership: preparation window, confidence, tempo ambiguity, entry/exit and phrase/energy evidence, promotion, rendered sample positions, output latency, seeks, pause/resume, speed and device changes.
- [ ] Reproduce defects using representative deterministic source fixtures and production captures, including bass, transients, stereo imbalance, loudness and tempo differences. Separate source distortion from application distortion.
- [ ] Measure sample and reconstructed true peaks where appropriate, discontinuities, gain modulation, gaps, transition-position error and processing cost. Preserve same inputs/settings for before/after comparison. Objective metrics do not replace listening.
- [ ] Implement targeted corrections only for reproduced defects or a clear causal analysis. Do not hide distortion by globally lowering gain, weakening clipping protection or disabling effects.
- [ ] Stress cancellation, ABA/reselection, queue changes, delayed decode/network/inference, fallback, cache ownership and bounded preparation. Fifteen seconds is fade duration, not an analysis deadline.
- [ ] Exercise repeated transitions/resource retirement and document actual duration, allocations/memory and timing evidence; do not extrapolate to nine hours or battery/thermal behavior.
- [ ] Run relevant Android, Windows ARM64 and x64 checks serially; retain listening captures where permitted; create a fresh local candidate with verified provenance and Surface listening instructions.
- [x] User verifies the new binary's audio-format display and compact target, and reports the identified automatic pair no longer distorts. This acceptance does not close the broader AutoMix quality and scenario matrix.

## Initial limiter review: hypotheses requiring measurements

The current final stage applies volume once, then a linked-channel instantaneous-attack, 100 ms release peak catcher. Its previous tests establish bounded sample peaks, partial-write consistency and safe-signal preservation for those fixtures. They do not establish true-peak bounds, low harmonic distortion, transparent transient handling, or smooth volume changes. Volume changes currently occur at processed-block boundaries; abrupt gain steps are a candidate click mechanism to reproduce. These observations are not a claim that this mechanism explains the user's song-specific distortion.

All broader AutoMix acceptance items remain open until the production audit, reproduction and verification above provide evidence.

## Production path audit, first pass

The desktop engine opens ordinary sources with FFmpeg and passes decoded floating-point samples through read-ahead. Native Spotify enters at the existing helper's 44.1 kHz stereo float boundary; that boundary does not establish the original encoded codec or losslessness. Decoder observations are published by `publishTrack` independently of analysis eligibility.

For desktop playback, `readSource` converts sample rates when needed; `readCurrent`/`readIncoming` feed the tempo buffer; `blend` applies per-deck spatial processing and EQ, transition filters, gains and optional effect sends; silence trimming and listener speed follow; `play` applies volume/final limiting once before `DesktopAudioWrite` retains partial writes. `writePending` advances submitted-frame accounting; device-played frames drive the reported clock.

The following findings require separate reproductions, rather than assuming the final limiter covers them:

- **Confirmed join-scoring defect:** `DesktopAudioSpeed.score` summed the channels before calculating correlation. Opposite-polarity stereo cancels that sum, so every join scores zero. A deterministic one-second multi-partial/rhythmic fixture at 1.04× reproduced a maximum left-channel difference of 0.8168305 merely from inverting the right channel, while six existing speed tests passed. The correction accumulates channel-pair correlation and energy, keeping one shared join for all channels. Test speeds also include 1.1× and 0.8×. This is processor evidence; full-engine captures and listening remain required.
- **Timing hypothesis:** `publishPosition` returns within an unchanged 250 ms UI bucket before calling `maybeStartCrossfade`; the plan compares the heard position, while subsequent mixed samples enter the buffered output. Measure transition onset against planned source-frame positions and queued latency before changing scheduling.
- **Tail-loss hypothesis:** the resampler holds a sinc-kernel tail; `readSource` currently returns EOF directly. The speed/tempo paths also have buffered samples and explicit drain methods that are not invoked on every EOF path. Quantify lost frames and discontinuities through the engine.
- **Effect attribution:** smart DJ transitions can automatically add beat-repeat, reverb and a texture. Fast low-vocal-probability pairs can select `DesktopTransitionCrush`, which intentionally quantizes to eight levels and holds samples. Such designed distortion must be distinguished from clipping or decoder errors. It has not been disabled; suitability and channel handling need controlled captures.
- **Gain evidence:** the inspected desktop blend path has equal-power/style gains and overlap trim, but no measured inter-track loudness normalization was found in that path. `TrackAnalysis.energyCurve` is not an integrated loudness measurement. Different master levels need measured transition captures rather than a claimed normalization fix.

## First diagnostic reproduction

`audio-details-production-red` ran five tests: the production FFmpeg/engine ownership test and excessive-target-size test failed as expected; three existing UI behaviors passed. The real decoded WAV reached state with sample rate/depth/channels, but the snapshot owner was null. The clickable semantics spanned 205 px in a 320 px scene. The standalone fixture also reported an incompatible stale x64 analysis DLL; no analysis result from that run is claimed. Native-resource verification is required in the Gradle follow-up.

The first Gradle follow-up stopped at compilation because the newly nested Box obscured the outer constraints receiver. Capturing the compact-layout decision before entering the child Box corrects that source error; tests from that failed Gradle invocation were not executed. No dependency, compiler or heap settings were changed.

## Verified follow-up, 25 October

The user now identifies **the automatic Amazing Grace → Walls Could Talk transition** as a bass-distortion case. This supersedes the earlier tentative report that distortion had stopped. Artist identity and the historical EQ, route, spatial processing and volume settings are not fully established. The installed application's compiled playback classes match the confirmed 24 October diagnostic ZIP despite the older extraction-folder name.

Read-only log inspection finds a 5.1-second `DJ_FILTER`, 16-beat plan, incoming cue 17.9 seconds, followed by a reported 8.6 seconds blended including effect tails. A separate 15-second manual skip exists in the log but is not the transition the user identified. Exact copies of the two probable tracks' old analysis entries, hashes, selected numeric evidence and redacted relevant log lines are retained in local audit storage. User data and the running application were not modified.

### Confirmed analysis-clock defect

`DesktopAudioDecoder.open` retained the input's sample rate even when `DesktopTrackAnalyzer.decode` explicitly requested 11,025 Hz or 22,050 Hz mono. The analyzer interpreted the returned samples on the requested clock. Real FFmpeg regression fixtures reproduced two seconds of 48 kHz input interpreted as 8.707 seconds at 11,025 Hz and two seconds of 44.1 kHz input interpreted as four seconds at 22,050 Hz. This invalidates timing, pitch and derived musical evidence; it is not yet proof of the reported bass distortion's cause.

The decoder now has an explicit rate-preservation option, defaulting to existing playback behavior. Only analysis requests resampling to its actual consumer clock. Corrected desktop analyses use `analysis/pcm-clock-v2/smart_analysis`; legacy files remain intact and are not used for transition planning. Android's analysis cache/schema is unchanged. Existing legacy cached results cannot be repaired by rescaling a few fields because features and inference already processed the wrong signal.

Saved evidence for the reported pair is consistent with this defect: Amazing Grace has catalog duration 214 seconds, downbeats through 450.78 seconds and an energy curve through 928 seconds; Walls Could Talk has duration 102 seconds, downbeats through 213.16 seconds and energy through 442 seconds. IDs were inferred from adjacent resolution logs; these figures do not independently identify the artist or source recording.

`analysis-clock-production-arm64` completed in 218.25 seconds: **30 tests, zero failures, errors or skips**, using the normal Gradle task and actual ARM64 FFmpeg. Coverage includes both analysis rates and pitch, legacy-cache isolation, correctly namespaced valid-cache restoration, playback preserving a differing native sample rate, preparation policy, analysis status, stereo stretching and engine diagnostics. The earlier standalone red run failed all three new clock/cache cases; the standalone green run passed eight tests. Logs and raw XML are retained outside source.

### Confirmed transition-effect defects

Four deterministic production-processor regressions failed before correction:

- Vibrato's delay was rounded in interleaved samples, allowing a right-channel silence input to acquire peak 0.56 from the left channel.
- Vibrato and phaser advanced their modulation phase per channel, doubling their nominal modulation frequency for stereo. Adding a silent right channel changed the left output by maximum 0.6910707 and 0.2896423 respectively in the fixtures.
- Bit reduction advanced its hold clock between channels, changing a centered input into unequal left/right outputs.

The correction rounds delay length in whole frames, advances modulation after the last channel of a frame, and captures bit-reduction samples on a shared frame clock. All four standalone regressions pass afterward. Effects remain enabled with the same nominal modulation frequencies, depths and wet levels. Full-engine captures and listening are still required before attributing subjective quality improvement or claiming the reported pair fixed.

### Diagnostics and stereo-join evidence

The engine now emits decoder measurements with the observed track ID and source from one state observation, augments output evidence independently, and retires diagnostics after release. The UI limits its clickable child to 48–192 dp, preserves observed sample rate if codec is unavailable, and labels current/upcoming analysis separately. A real FFmpeg/engine test covers owner, replacement, seek and retirement; production UI pointer/semantics tests cover bounds, outside clicks, partial evidence and stale-track rejection at normal and 200% font scale. The combined production binding-to-UI lifecycle and complete player/device matrix remain open.

`audio-details-lifetime-arm64` completed successfully in 68.91 seconds after a failing retirement regression was corrected: three engine tests passed, and six UI tests were up-to-date from the preceding successful UI task. `audio-details-stereo-join-arm64` previously executed 57 tests without failures or skips. Actual rendered scenes were inspected at both font scales; this is not physical touchscreen or user acceptance.

The stereo time-stretch join correction has seven passing processor regressions, including polarity invariance at 1.04×, 1.1× and 0.8×. Before/after generated listening files share input SHA-256 `129ecc466553f6828c13a9aded60bc32dafe99c9eedda3f7850808ca1c8ff448`. In the side-only fixture, inverting the right channel previously changed the left output; the corrected left output exactly matches the linked reference. This demonstrates the tested invariant, not perceptual transparency or full-engine musical quality.

No new candidate has yet been delivered containing all these follow-up corrections. Audio Details awaits user acceptance of a newly verified binary. The broader AutoMix pipeline, timing, stress, long-session and Surface listening checklist remains incomplete.

### Flanger and actual automatic-controller replay

The captured legacy pair analysis reproduces the reported production plan: 5.0776-second `DJ_FILTER`, incoming cue 17.8702 seconds and incoming rate 1.081×. That plan selects the flanger; the earlier vibrato/phaser/crush defects must not be presented as its specific effect attribution.

The flanger had the same interleaved-delay and per-channel-clock defects. Independent regressions reproduced a silent right channel acquiring peak 1.2594532 from its left neighbour and a left-channel change of 1.4622455 merely from adding a silent channel. Its delay now stays on the same channel, and its modulation clock advances once per frame. Six texture/channel regressions pass after correction, with before/after failures retained.

`ReportedAutomaticPairProbe` drives the actual automatic controller, queue handoff, read-ahead, stretching, effects, limiter and output-write path using generated bass/transient stereo material and the saved legacy analysis in an isolated test cache. It does not decode the reported copyrighted recordings or change the installed application's cache. Before/after runs each produce exactly 864,000 frames (18 seconds at 48 kHz), one handoff and zero overrange samples. Source-generation class bytes match (SHA-256 `7186e3e8372eb704ae71f44bcf12bdad7481c1c00d5ff1c79007bb1965c5609e`).

Before/after sample peaks are 0.8876401 / 0.8868081; finite four-phase Kaiser-windowed sinc reconstruction estimates are 0.8880584 / 0.8868489. The estimator is not an ITU-certified true-peak meter, DAC observation or proof of sound quality. Numerical differences and bounded peaks do not establish that the real pair's distortion is fixed. WAV files, exact logs, comparison script and metrics remain local listening/investigation evidence.

### Midpoint publication defect and full-suite results

A full-suite run exposed an incoming-position race: `blend` published the incoming owner after counting 600 ms of rendered material but before `play` installed its pending output write. During that interval the clock treated unheard samples as played. A new deterministic test observes playback state inside the first blocked mixed write and reproduces 600 ms instead of zero.

Midpoint and beat-anchor publication now occurs after pending-write accounting is installed. The existing partial-write test also waits explicitly for the first accepted slice and following blocked retry; its 10 ms assertion remains intact. The new regression separately verifies the earlier pre-acceptance state. All 23 standalone recovery tests pass after correction.

The final `audio-midpoint-full-arm64` Gradle invocation completes successfully in 155.28 seconds: desktop 616 cases (610 passed, six skipped), desktop UI 84 passing results and shared 104 passing results. The unchanged UI/shared tasks were up-to-date from the preceding successful executions. Raw XML is archived independently. Earlier full runs are retained as failures: one outdated valid-cache fixture, then the midpoint race. No test was removed or disabled, and no dependency/heap setting was altered. Current x64/Android verification and a new packaged candidate are still pending.

`audio-midpoint-full-x64` subsequently succeeds in 231.52 seconds with 610 desktop passes and six skips; UI/shared remain green. It uses x64 Java 21 under ARM64 Windows emulation, not a physical x64 computer. `audio-followup-android` succeeds in 259.28 seconds: app 1,146 passed, one skipped; shared UI 60 passed. Exact raw XML and command records are retained. A separate Java 25 ARM64 desktop test/package and Android APK assembly run is now in progress; no fresh package has yet been claimed verified or delivered.

### Verified local candidates and recording identity

Targeted read-only queue/history inspection subsequently confirms `pL-Iy0dc_qA` is **Susan Boyle — Amazing Grace**, 3:34, album *I Dreamed A Dream*; `ispL0Q74iCc` is **Halsey — Walls Could Talk**, 1:42, album *hopeless fountain kingdom (Deluxe Plus)*. Only matching metadata was retained; other history and credentials were not exported. Historical EQ, volume/spatial and output-route settings remain unconfirmed.

`audio-followup-java25-and-packages-helper` succeeds in 507.19 seconds after the omitted helper-path correction. The Java 25 task actually executes this time: 610 passes, six skips, zero failures/errors. Old XML from the earlier failed attempt is not counted. Fresh raw XML is independently archived.

Local artifacts are copied and verified under `deliverables/2026-10-25-audio-followup` outside source. Windows ZIP: 408,602,056 bytes, SHA-256 `8156dc00112e34b1829b2758c88472aaa182009de987016054d3d1df7ca122e6`. ARM64 Dev APK: 98,556,446 bytes, SHA-256 `8c8df03841e1531ba2d093401eb9c7cbba588da169565c8d09e4b870e55046a1`.

Verification passes for ZIP/APK CRCs, 80 standalone ARM64 PE files, 34 selected native JAR resources, all 1,353 current app classes without duplicate class entries, exact current UI/shared JARs, the preserved Spotify helper/notices, ten APK AArch64 ELF libraries, Dev package identity and APK signature. The Android signing certificate matches the 24 October Dev APK. Three DEX files changed, and the new diagnostic labels exist in the new DEX and not the old one. These are actual packaging/behavioral regression results, not full-app or user acceptance. The candidate README provides profile-preserving installation and the exact automatic-pair Surface comparison.

The source history merge still has eleven open code conflicts, and broader model/runtime/timing/stress/long-session requirements remain open.

### User acceptance of the new Surface candidate

In direct replies to the question identifying the 25 October ZIP by hash prefix `8156dc00`, the user reports the automatic Susan Boyle → Halsey bass distortion is **“not anymore.”** A separate diagnostics question receives **“Yes, both now work”**, confirming the playing audio-format display and compact click target. Record these as user-reported Surface acceptance of those concrete behaviors in the new candidate.

The feedback does not establish blind level-matched listening, every transition pair/style, every output route/layout, verified Atmos rendering, a nine-hour run or measured GPU improvement. Those claims are not made. The specific reported distortion case and Audio Details rejection are closed by the user's acceptance; broader engineering verification continues.

## 26 October: EOF, queued-rate and measured onset follow-up

The [tail and timing report](audio-tail-and-timing-2026-10-26.md) supersedes the earlier unverified tail-loss lead with real reproductions and local fixes. It records FFmpeg resampler frame loss/stale seek history, engine converter/stretcher EOF loss, and queued-rate ownership errors, with fourteen new regression tests. Final desktop suites execute 624 passes/six skips on ARM64 Java 21, ARM64 Java 25 and emulated x64 Java 21. Shared JVM results remain up-to-date where specified. The generated reported-pair replay is byte-identical to the previously corrected capture; no new listening acceptance is inferred.

The subsequent [transition scheduling report](transition-render-scheduling-2026-10-26.md) records independent onset, cue-seek, promotion continuity/clock and fade-end reproductions, 17 added engine regressions and the corrected historical recovery fixture. Its current-source results and newly packaged candidate are distinct from the tail phase's preserved waveform and from the user's October 25 listening acceptance. SMART plan stability and comprehensive musical/long-session acceptance remain open.

The earlier onset observation reproduced PCM markers 168.333, 268.333 and 428.333 ms after the planned start with deterministic 0/100/250 ms output queues, including the intentional 1.333 ms fader/filter ramp. That observation is historical: the linked transition scheduling follow-up subsequently implements and verifies the render-boundary, cue, promotion and SMART-plan corrections. The [prepared-source recovery follow-up](prepared-source-recovery-2026-10-26.md) then verifies early failed-deck recovery without restarting healthy current audio. Broader musical/extended-session acceptance remains open.

The [beat-model/peak follow-up](beat-model-and-peak-picking-2026-10-26.md) evaluates both actual shipped models on identical generated PCM and compares the native/Web front ends numerically. The existing native model returns beats; the newer model is not blindly adopted. Independently reproduced flat-peak duplication is corrected in shared Kotlin and Web, with cache revisions, unversioned-grid rejection and Web user-override preservation. Current ARM64 and Web verification is recorded there; no new audible-quality claim is made.
