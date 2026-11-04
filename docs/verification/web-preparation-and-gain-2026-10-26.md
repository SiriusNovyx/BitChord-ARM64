# Web AutoMix preparation ownership, start gain and real graph verification

Status: **Reproduced preparation and gain defects corrected locally; full integration and AutoMix quality remain Partial.** No Native Desktop/Android implementation, existing ZIP/APK or accepted Surface behavior is changed in this phase.

## Read-only audit and preservation

The actual `AutoDJ`, `LoopDeck`, graph factory, timeline, transition scheduler and caller relationships were inspected before production edits. The graph factory already caches one pair of worklet decks, so rebuilding Spotify/native engines or adding another deck pool was unnecessary. The existing Signalsmith 1.3.2 source documentation confirms that `addBuffers` appends data and `dropBuffers` replaces its extent; overlapping preparation must not interleave those operations.

The local audit at outer `.work/web-loop-preparation-20261026` retains both HEADs, both exact staging indexes, independent staged/unstaged binary patches and pre-change source/report archives. The later gain scheduler and test-entry changes have their own before archives. The authoritative source remains the approved nested tree. No staging or publication is performed.

Implementation checklist:

- [x] Reproduce cancellation at deck initialization, byte fetch, decode and deck loading, including same-pair reselection and rapid cancellation.
- [x] Retain one active preparation until it retires; invalidate obsolete publication and avoid overlapping worklet replacement.
- [x] Verify healthy preparation, retry after failure, controls remaining independent and existing fallback.
- [x] Execute the actual Web graph with generated PCM and real HTML audio/WASM worklets.
- [x] Reproduce the observed pre-fade gain lead using real OfflineAudioContext/AudioParam rendering before correcting it.
- [x] Keep the configured fade, gain curves, analysis quality, master volume and renderer intact.
- [x] Run focused red/green regressions, all Web tests, type checks and app/Chrome/Firefox production builds.
- [x] Retain reusable browser test commands in project source, including their actual end-to-end execution.
- [x] Reconcile the reviewed files into the pending complete-history checkout with both indexes unchanged.

## Confirmed preparation defects

`AutoDJ.loopPreparing` previously held a string such as `out>in`. Cancel/stop cleared it even while asynchronous work continued. Reselecting the same pair installed the same string, so an older task could again satisfy `stillWanted()`. Different preparations could decode and load the shared worklets concurrently. Internal track IDs also lagged immediate host/deck changes, and there were no ownership checks after initialization/byte fetch or between the two deck loads.

Eleven tests exercise the actual preparation method and real loop-selection algorithm with controlled external waits. Nine fail before correction; healthy preparation and failure/retry already pass. A stalled same-pair stress case starts **42 decode calls** after twenty cancellations instead of retaining the original two. Other failures show unnecessary byte/decode work, stale deck loads and loading the incoming deck after cancellation during outgoing replacement.

Preparation now holds one unique cancellable lease until its asynchronous operation settles. Cancel/stop and transition completion invalidate the lease instead of making its occupied slot appear free. Ownership includes active state, current/upcoming IDs, host track identities and both deck instances. Checks after initialization, byte collection, decoding and each load prevent obsolete continuation. A newer request is retried by the existing tick after the obsolete task retires; no queue of decoded buffers or extra analysis workers is created. `finally` releases the slot on both failure and success.

All eleven cases pass after correction, including the bounded two-decode stress result, successful latest-pair preparation and failure/retry. Controls do not await preparation, and the existing no-analysis fallback still operates in the real graph. A worklet RPC that never settles retains this one occupied preparation slot; it does not block playback controls or permit unsafe concurrent buffer replacement. Full recovery from such a permanently stalled worklet is a remaining fault-injection/lifecycle check, not claimed solved here.

## Confirmed gain defect before the intended fade

The initial real graph capture reports a fallback sample peak near **0.30472** on the generated inputs. Inspection shows a causal failure: `Deck` starts a media element ahead of the AudioContext transition time to compensate for latency, but `applyEqualPowerCross` only zeroes incoming gain **20 ms before** that transition. The incoming element can therefore be heard at its previous/full gain during pre-roll.

This was independently reproduced for crossfade, beat blend and filter styles. A real OfflineAudioContext renders constant inputs of 0.4 and 0.3 through the production scheduler's real AudioParam automation. With the real deck's default 120 ms lead modeled at the source-start boundary, the pre-transition sum is **0.70000005**, with 3,359–3,360 incorrect frames in the inspected 70 ms interval. The desired pre-transition signal is only the outgoing **0.4**. Nine deterministic Vitest cases also fail, covering all three styles at 40/120/300 ms start leads.

The scheduler now cancels obsolete incoming gain automation at the current AudioContext time and holds incoming gain at zero immediately, before pre-roll. The existing fade starts at the same `t0` and keeps the same equal-power arrays and duration. Master volume, per-track normalization, loop rendering and clipping protection are not weakened or globally attenuated.

All nine unit cases and all three real offline rendering cases pass. The corrected pre-transition value is **0.400000006** with **zero incorrect early frames**. The during-fade maximum remains **0.499992818** before and after on these inputs. This provides sample-rendered evidence for the specific gain correction; it is not a general true-peak or audible-quality guarantee.

## Actual Web audio graph

The browser executes the actual `createAutomixGraph`, `Deck`, `AutoDJ`, `LoopDeck` and Signalsmith WASM worklets. Two locally generated 40-second stereo WAV inputs at 44.1 kHz and known analysis records replace provider/model requests in the fixture only. Capture occurs upstream of a final zero-gain output, so system volume and other playback are unaffected and no audible output is produced.

Verified scenarios:

1. Actual HTMLAudioElement decoding and normal stereo PCM through the Web Audio graph.
2. A genuine no-grid fallback, pause/cancel before midpoint, stationary paused media positions, restart, one handoff and one completion.
3. Actual buffer decoding/worklet loading, selected instrumental loop rendering, tempo-clock scheduling, incoming media-element takeover and cleanup of the host hold.

The initial and corrected isolated graph contexts run at **48 kHz**. Normal output peak is approximately 0.17486/0.17490; fallback peak changes from **0.30472 to 0.21458** after the gate correction. All inspected generated scenarios have zero overrange and non-finite samples. A subsequent execution through the reusable repository command runs at an observed **44.1 kHz**, also passing all three scenarios with fallback peak approximately 0.21278 and loop peak 0.22273. Do not compare different context sample rates as a performance or quality benchmark.

The generated WAV hashes in the corrected captures are:

- Outgoing: `6313b68caa92ca250e24942d4527ef3d24ef4cb6310e7e9a33276fb753ff6cee`
- Incoming: `02491c130e0f1c4ebaab09bbce558096a52f64c7af3037fb05793dc8b35e6a62`

The original generator/parameters are preserved, and the generator body is unchanged. These are repeatable generated-input observations, not representative licensed music, blind level-matched listening or a GPU/battery benchmark. The contexts reach `closed`, temporary servers exit, and the agent-created tabs are closed. `LoopDeck.latencySec` is the worklet's reported value, not a physical output-route latency measurement.

### Timing finding remains open

The real loop capture also exposes the expected consequence of checking handoff on a one-second tick: one 48 kHz run schedules the loop at 20.7211 seconds with a 20-second duration, but reports the handoff callback at 31.5813 seconds. This is about 0.86 seconds after the scheduled midpoint. The later 44.1 kHz run is about 0.92 seconds late. These are individual observed callback delays, not a benchmark distribution or proof of audio-render drift. A single handoff and valid later media position do not establish precise lyric-owner timing. Actual UI ownership and cancellation/suspension behavior require a focused follow-up before changing scheduling.

## Reusable regression tools

[Browser regression instructions](../../web/app/tests/browser-audio/README.md) provide:

```text
pnpm --filter @bitchord/app test:audio:browser --gain-only
pnpm --filter @bitchord/app test:audio:browser
```

Both commands are actually executed successfully. They build with the existing Vite toolchain, serve only generated files on a random `127.0.0.1` port, retain source hashes and result JSON in the already-ignored `web/app/build/` tree, then exit after the browser result. No new browser/test dependency or provider account is required.

The runner's first missing-favicon request incorrectly stopped its server with HTTP 500. That failed invocation is preserved. Correct missing-asset handling returns 404 while retaining the server; the corrected command exits 0 after the actual browser regression run. Seven boundary checks pass: missing asset, traversal, absent environment file, foreign-origin result refusal, oversized-body refusal, unsupported method and continued page availability. No credentials, library media or deployed service participate.

## Verification results

| Check | Actual result |
| --- | --- |
| Preparation red / green | 9 failed + 2 passed before; 11 passed after |
| Gain scheduling red / green | 9 failed before; 9 passed after |
| Real offline browser gain | 3 failed before; 3 passed after; during-fade envelope unchanged |
| Actual isolated graph | 3 scenarios pass before/after gain change; capture identifies and then verifies removal of the early-sum symptom |
| Reusable browser commands | Gain and full graph commands both execute, save results and exit 0 |
| Local runner boundaries | 7 checks pass; original missing-favicon failure retained |
| Full Web tests | 179 app tests in 17 files + 9 shared bridge tests, all pass |
| Type checks / builds | Recursive type checks and app/Chrome/Firefox production builds pass, exit 0 |
| Existing native packages | Native/Android sources unchanged; prior ZIP/APK remain the current verified packages |

Exact commands, source hashes, durations, full logs, result JSON, before sources and unedited screenshots remain in the local audit. The pinned pnpm 12.11.2/Node toolchain, dependency pins/lockfile and Gradle/Java/AGP configuration are unchanged. The only package-manifest edit adds the functioning browser-test script. No check/test is disabled or removed.

## Exact project changes

- `web/app/src/playback/automix/engine/autodj.ts`
- `web/app/src/playback/automix/engine/preparation.test.ts` (new)
- `web/app/src/playback/automix/engine/transition-scheduler.ts`
- `web/app/src/playback/automix/engine/transition-scheduler.test.ts` (new)
- `web/app/package.json` (test script only)
- `web/app/scripts/audio-browser-probe.mjs` (new)
- `web/app/tests/browser-audio/index.html` (new)
- `web/app/tests/browser-audio/main.ts` (new)
- `web/app/tests/browser-audio/gain.html` (new)
- `web/app/tests/browser-audio/gain-check.ts` (new)
- `web/app/tests/browser-audio/README.md` (new)
- `docs/verification/web-preparation-and-gain-2026-10-26.md` (new)
- `docs/verification/upstream-capability-matrix-2026-10-26.md`
- `docs/verification/upstream-convergence-2026-10-25.md`
- `docs/verification/web-lifecycle-and-automix-ownership-2026-10-26.md`

## Remaining limits and publication

Installed extension/provider playback and authentication, complete Web UI/lyrics synchronization, incompatible/weak-grid media transitions, permanently stalled worklet recovery, representative music listening and extended allocation/thermal/battery measurements remain Partial. Physical Android/TV/Wear and licensed Atmos object output are not established by these browser checks. Spotify permission remains unresolved; native Spotify helper/auth/queue and the unavailable resolver row are untouched. Accepted native Surface behavior remains intact.

The pending history merge is uncommitted. Both original staging indexes and historical unpublished work are preserved. No GitHub upload, push, PR, merge, deployment or publication occurs. The complete mission remains active; the final selected-source/destination/branch review and publication approval are separate gates.
