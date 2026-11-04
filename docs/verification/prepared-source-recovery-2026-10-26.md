# Incoming preparation failure and recovery

Status: **Partial overall; local preparation recovery verified**. The preparation and known-failed Skip defects are reproduced before correction. Full current-source ARM64 Java 21/25 and emulated x64 Java 21 regressions, native package verification and generated playback probes pass. The previous scheduling/planner packages remain immutable. No new sound-quality, hardware, complete AutoMix or full integration acceptance is claimed.

## Read-only audit

Before correction, `DesktopDecodeAhead` retains worker seek/read exceptions and exposes them only when `poll` consumes input. `DesktopPlaybackEngine` polls the next deck only during a blend. Its `handleDecodeFailure` handles either current or incoming failure by invalidating the playback generation, flushing output and closing both decks. Before the midpoint it can reopen the selected outgoing song. The five preparation tests reproduce the missing early refresh/retirement; a separate blocked-output test proves Skip can reserve a decoder whose failure is already known.

## Implementation checklist

- [x] Preserve the actual current engine/decoder and overlapping reports in a recoverable archive; verify the original staging-index SHA-256.
- [x] Reproduce failed cue preparation while the outgoing song is well before the transition.
- [x] Observe a terminal preparation failure without consuming PCM or waiting for the decoder.
- [x] Permit one fresh resolution/open for the selected remote next deck while retaining the current output, position and controls.
- [x] Use a fresh transition ticket to reject obsolete retry/upgrade results, including same-track reselection.
- [x] Retire a repeatedly failed or ineligible prepared deck without repeatedly issuing network requests or interrupting healthy audio.
- [x] Preserve Spotify/provider-refusal handling and existing audible-deck recovery behavior.
- [x] Verify pause, queue replacement, late opens, resource closure and current-source continuity.
- [x] Run relevant full ARM64/x64 regressions and document actual results.
- [x] Reconcile the six reviewed source/test/report files into the pending history merge with both staging indexes preserved.

The 15-second setting remains a fade duration. Recovery must use preparation time that is actually available, without inventing an analysis deadline, reducing analysis quality, adding workers or weakening source matching. Any refresh must go through the existing source registry and preserve configured source availability, ordering and authorization behavior.

Original protected index: `e06ebe4e519c5aea6049c651642786a80d8cad8815b8cf3b895aab346b079a7a`. Source changes are authorized only in the nested development tree. Commit, upload, push and PR publication remain separately gated.

## Functional correction and scope

The decode-ahead reader exposes its stored terminal failure through a lock-protected, non-consuming view. The render loop checks the prepared deck before starting new work; healthy playback returns before acquiring the global control lock. A failed, unheard remote deck may receive one fresh resolution/open through the existing registry. Fresh resolution preserves configured priorities, availability checks and strict recording matching. Completed add-on track-call responses are invalidated off the audio thread, using the existing refresh operation; credentials, source preferences and persisted caches/settings are unchanged.

Recovery creates a new preparation ticket while retaining the current playback generation. A failed deck's outstanding substitute request is cancelled. Old opens/upgrades cannot adopt a newer request for the same song. The next-deck ready flag and manual-transition ticket are cleared during recovery. Skip rejects a decoder that already has a terminal failure, even if the audio pump is busy writing current output. No current-source resolution, seek, output flush, gain change or decoder restart is performed by this path.

A repeated failure, local-file error or non-transport error retires the unusable prepared decoder without an automatic retry loop. The queue itself is not advanced or changed. Its next song may still be loaded through ordinary queue playback when appropriate. Analysis for that still-selected queue song remains running: decoder retirement does not make its musical analysis obsolete. Only actual queue/generation changes retain the existing analysis cancellation behavior.

This path excludes native Spotify songs on either deck and `SpotifyPcmException`. Existing provider-refusal diagnostics and no-substitution behavior remain covered by the original recovery tests. There is no new Spotify connection, entitlement inference, playback adapter or reason assigned to code 313. Once a blend has actually started, the existing audible-deck recovery path remains responsible; this change does not promise that arbitrary mid-playback transport failures can never interrupt output.

## Exact source and test files

- `desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopAudioEngine.kt`
- `desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopDecodeAhead.kt`
- `desktopApp/src/test/kotlin/com/music/bitchord/desktop/DesktopPreparedSourceRecoveryTest.kt` (seven cases)
- This report and current-state links in the scheduling/upstream reports.

No Android/shared production, Web/backend, UI/font, dependency, audio-processing algorithm, model, limiter, EQ, playback-speed or user-volume change is introduced in this phase. The earlier Android APK remains the exact verified SMART-plan candidate.

## Actual tests and retained checkpoints

| Run | Actual result | Scope |
| --- | --- | --- |
| `prepared-source-recovery-red` | Five execute; five fail, 70.55 seconds | Missing early recovery/retirement; pause/stale-open scenarios cannot reach their intended recovery stage. |
| `prepared-source-skip-reservation-red` | One executes and fails, 21.99 seconds | Known failed deck is incorrectly reserved while the pump is blocked in current output. |
| `prepared-source-recovery-first-fix` | 51 pass, 34.97 seconds | Six new failure/race cases plus 23 existing recovery, five cue, 12 onset and five decoder-poll cases. |
| `prepared-source-recovery-fastpath` | Same 51 pass, 35.88 seconds | Healthy fast path avoids the global control mutex; ticket identity is revalidated on failure. |
| `prepared-source-recovery-final-arm64` | Exit 0, 199.56 seconds; 648 desktop passes/six existing display skips per Java 21/25 JVM | First full checkpoint, including the seventh obsolete-upgrade cancellation case. Superseded by the analysis-retention review adjustment below. |
| `prepared-source-recovery-preserve-analysis-arm64` | Exit 0, 291.25 seconds; 648 desktop passes/six existing display skips per Java 21/25 JVM | Current source after removing unnecessary cancellation of analysis for the unchanged next queue item. Shared 107 results are unchanged/up-to-date. Native packaging succeeds. |
| `prepared-source-recovery-final-x64` | Exit 0, 208.73 seconds; 648 desktop passes/six existing display skips | Current desktop tests actually execute under x64 Java 21 emulation with ARM64 Java 25 driving Gradle. Shared 107 remains up-to-date. Executable paths, timestamps and current source hashes are verified in the archived results. |

All prior production, fixture, failing XML and passing checkpoints are retained under the outer `.work/prepared-source-recovery-20261026` audit directory. The first implementation cancelled the next song's analysis when retiring its decoder; review identified that as unnecessary because queue selection had not changed. That call was removed before final verification. This is a review correction, not a claimed defect of the prior delivered SMART-plan package.

The seven final cases cover early successful recovery and actual automatic handoff; a second failure with exactly two opens/resolutions; A/B/C/new-B ownership with a deliberately blocked late open; Pause during an open; non-transport/local retirement without network retries; known-failed Skip reservation while output is blocked; and cancellation of the failed deck's pending source upgrade. Healthy current audio is checked through source open/resolution counts, output flush count, selected song, error state, end/handoff callbacks and actual submitted PCM.

The six desktop skips remain the pre-existing headless-display cases. No skip, disabled check or test removal is introduced. Android's preceding full unit run has one separate opt-in live Genius test (`LIVE_TESTS=1`); it is not a display skip or a current desktop pass. The separate `genius-live-verification-20261026` attempt exits 0: all five cases in `GeniusTest` actually execute with zero skips, including the live noisy-title search/scrape case. XML timestamps, the executed task outcome and 934 unchanged Android/shared/TV/Wear source paths are checked. This is a live class follow-up, not another full Android-suite run or universal provider/device acceptance. Raw lyric previews remain only in the private audit and are excluded from publication summaries.

## Production playback probes

The actual compiled engine's generated reported-pair replay is byte-identical to the preceding SMART-plan candidate: WAV SHA-256 `d59c1203c73f74f296086f1302394e894614e5aee28d1ff2d0ba787e6e0f434b`, 864,000 stereo frames, one handoff, peak `0.8756049`, zero overrange samples. This checks that healthy transition PCM is unchanged; it does not establish subjective quality for the real recordings.

The repeated preparation-failure probe runs 24 SMART transitions with eight deliberately failed incoming read preparations. It observes 33 opens and 33 closes, peak registered decoder count two, 21,772,800 output frames, peak `0.38654914`, zero overrange samples, an empty registry and a terminated audio thread after shutdown. The fixture takes 6,927 ms; sampled heap starts at 33,714,192 bytes, ends at 67,130,448 and peaks at 88,188,480. These are one accelerated cached-analysis run's observations, not a performance improvement, leak-free lifetime proof, real HTTP outage test or nine-hour result.

## Current local candidates

The current candidate folder is the outer `.work/rejected-surface-20261023/deliverables/2026-10-26-prepared-source-recovery-final`.

- Windows ZIP: `BitChord-prepared-source-recovery-windows-arm64.zip`, 408,640,269 bytes, SHA-256 `add8811a65213bd6d6913371b4cf5011f2090a8797530bbf23819cf2457f3c12`.
- Reused Android Dev APK: `BitChord-smart-plan-android-arm64-debug.apk`, 99,297,669 bytes, SHA-256 `d9ab1f27c91b6a18daa4b1a461243b20144a3b55f2e4bb373335f133183f65b6`.

ZIP CRC, 80 standalone ARM64 PE files, 34 selected native JAR resources, all 1,355 current compiled application classes, UI/shared JARs, unchanged Bangna font, existing Spotify helper/notices and runtime inputs are verified. The unchanged APK retains only arm64-v8a native libraries, ten AArch64 ELF files, a valid Dev signature and the same update certificate/package identity as before. Android source and APK were not rebuilt or changed for this desktop-only recovery correction; the live lyrics test does not alter them.

The first recovery ZIP (`79fbd838…`) predates the analysis-retention review adjustment and is retained as a superseded checkpoint. The earlier SMART ZIP (`4a791d69…`) and Thai ZIP remain immutable. Use the current `add8811a…` ZIP to identify new Surface results rather than relying on the unchanged application version number.

The user tested the `add8811a…` Windows ZIP and reports no bass distortion during the automatic Susan Boyle “Amazing Grace” → Halsey “Walls Could Talk” transition. This is acceptance of that listening scenario, not a universal audio-quality claim. The same build still clips Thai translations at 150% Windows display scaling; the user narrows the remaining defect to translation rows. Main-lyric acceptance is separate from this rejected translation result.

The complete upstream capability audit, model/phase and longer-session work, other real-recording comparisons and physical Surface/Android acceptance remain open. No successful physical Atmos output, GPU improvement or nine-hour result is claimed. No commit, push, upload or pull request is performed.
