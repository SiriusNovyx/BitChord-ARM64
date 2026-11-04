# Web extension lifecycle and AutoMix scheduling ownership

Status: **Two reproduced Web defects corrected and locally verified. The broader integration is Partial.** Native Desktop/Android binaries and their accepted behavior are unchanged by this phase.

## Read-only findings and implementation checklist

The actual page-side bridge, Firefox relay, Chromium background handlers, Web playback request path, AutoDJ, deck clock and loop scheduler were inspected. The full goal and prior conflict inventory remain authoritative. The companion [capability matrix](upstream-capability-matrix-2026-10-26.md) separates source integration, supporting automated assertions and remaining runtime acceptance.

Before edits, the audit saved both HEADs and exact staging indexes, independent staged/unstaged binary patches and seven selected source/configuration files. The actual Web scheduler/deck/loop implementations were separately preserved before their follow-up tests. Evidence is local under outer `.work/capability-audit-20261026`, outside the eventual source upload.

- [x] Reproduce synchronous dispatch failure and reentrant cancellation in the actual bridge.
- [x] Correct exception/cleanup ownership without changing provider authorization or protocol semantics.
- [x] Compare baseline and corrected bridge using actual browser message events and a real `DataCloneError`.
- [x] Reproduce delayed loop scheduling after Stop, same-pair restart, queue/current changes, deck replacement and plan invalidation.
- [x] Revalidate transition ownership after the awaited clock anchor; retain successful scheduling.
- [x] Run all Web application/shared tests, type checks and app/Chrome/Firefox production builds.
- [x] Reconcile the reviewed changes into the pending history candidate with both staging indexes preserved.

## Confirmed defect: dispatch failure retains resources

`web/shared/src/bridge/client.ts` creates a deadline timer and an abort listener, then calls a transport starter. A synchronous transport exception rejects the Promise through its constructor instead of its normal settlement path. The timer and abort listener remain. On the relay, the message listener is installed before `postMessage`; a real structured-clone failure leaves that listener installed too. Cancellation delivered during startup can also settle before the returned cleanup has been assigned.

The bridge now routes startup exceptions through its existing settlement/cleanup path and invokes the returned cleanup if settlement already occurred during startup. The relay removes its listener if posting throws. Errors and provider refusals retain their original meaning; no authorization, origin permission, account session or protocol version is weakened. This is an exception-path resource correction, not evidence that the user's GPU/memory observations were caused by this code.

`web/shared/src/bridge/client.test.ts` has nine cases. Three fail against the preserved implementation before the correction; all nine pass afterward. Coverage includes synchronous Chromium dispatch failure, relay posting failure, reentrant cancellation, pre-cancellation, timeout/late reply, same-window/origin/request matching, provider refusal, asynchronous success and Chromium `lastError`.

An isolated browser harness type-erases both actual source versions with Node's built-in TypeScript support and changes only the local protocol import's `.js` suffix. In a real browser, the baseline retains one listener and one deadline after actual `window.postMessage` raises `DataCloneError`: six of seven scenarios pass. The corrected source passes all seven, including twenty unanswered calls, cancellation, refusal and cross-origin rejection. The source hashes, JSON result and unedited screenshots are retained. This executes the relay/message boundary, not an installed Firefox extension or a real provider account. The temporary localhost-only server terminates after saving the result, and its agent-created tab is closed.

## Confirmed defect: cancelled loop scheduling can resume

`web/app/src/playback/automix/engine/autodj.ts::scheduleLoop` captures a valid plan and decks, then awaits `from.anchorPrecise()`. Stop/cancel, a same-pair restart, a queue/current replacement or deck/plan replacement can occur during that wait. The old continuation previously scheduled both stretch decks, changed gains/transport, asserted the host hold and set state to `scheduled` despite lost ownership.

The correction captures the existing load generation and checks it after the await, together with active state, the same plan, current/upcoming identities and both deck instances. An obsolete continuation returns before audio automation or transport mutation. The valid plan still uses the existing loop renderer, tempo ramp, gains, handoff and fallback; no mode, fade duration or analysis quality is reduced.

`web/app/src/playback/automix/engine/autodj.test.ts` exercises the actual scheduling method and real `scheduleLoopMix` algorithm with deterministic deck/audio-parameter boundaries and a delayed anchor. Seven stale-ownership cases fail before correction; all eight cases, including unchanged valid scheduling, pass afterward. The first test attempt also exposed an incomplete fixture's cleanup path; its log is preserved. Adding the fixture's normal audio element behavior makes the healthy control pass before changing production scheduling, leaving the seven actual failures.

These tests prove control/automation ownership at that awaited boundary. They do not prove browser PCM quality, worklet latency, provider playback or every preparation race. In particular, the separate asynchronous loop-preparation path still uses pair-string identity and requires an ABA/concurrency reproduction before any further change.

## Actual verification

| Check | Result |
| --- | --- |
| Bridge red regression | 3 failed / 6 passed; expected cleanup failures |
| Bridge focused green | 9 passed |
| Actual browser baseline/current | 6/7 baseline, 7/7 corrected; real clone failure reproduced |
| Scheduler corrected-fixture red | 7 failed / 1 passed; stopped/stale work scheduled |
| Scheduler focused green | 8 passed |
| Final recursive Web tests | 159 application tests across 15 files, plus 9 shared bridge tests; no failures |
| Final recursive type checks | Shared, extension and app pass; exit 0 |
| Final production builds | App and Chrome/Firefox extensions pass; exit 0 |
| Dependencies/build settings | Lockfile, package/dependency and project toolchain settings unchanged |

Final command: existing pinned pnpm 12.11.2 `test`, `typecheck`, `build`, under existing Node 24 ARM64. Exact commands, durations and full output are in `final-web-results.json` and separate stage logs. `VITE_PARTY_SERVER_URL` is the same CI fixture URL, not a deployed backend. Existing bundle/WASM warnings remain; Firefox-only entrypoints are intentionally excluded from the Chrome variant. No tests/checks are removed or disabled.

## Exact project files changed in this phase

- `web/shared/src/bridge/client.ts`
- `web/shared/src/bridge/client.test.ts` (new)
- `web/app/src/playback/automix/engine/autodj.ts`
- `web/app/src/playback/automix/engine/autodj.test.ts` (new)
- `docs/verification/web-lifecycle-and-automix-ownership-2026-10-26.md` (new)
- `docs/verification/upstream-capability-matrix-2026-10-26.md` (new)
- `docs/verification/upstream-convergence-2026-10-25.md`

No Kotlin/native implementation, Android source, font/model, existing ZIP/APK, credential, user setting or staging index is modified. The prior Windows ZIP `62d69b53…` and Android APK `2b8a795f…` remain the latest verified native packages; these Web-only changes do not require relabeling or rebuilding them.

## Remaining limits

The capability inventory is not full feature acceptance. Installed extension authentication, actual Web audio/video/loop playback, browser seek/pause/recovery and mixed-client party behavior need stronger runtime coverage. Representative musical listening, long-session allocation/thermal/battery behavior, physical Android/TV/Wear and Atmos object output are still partial/unverified. Accepted Surface touch/Home/FPS/Audio Details/Thai behavior and the specified no-distortion pair are retained. Spotify-exclusive playback is technically feasible but permission remains unresolved; the existing helper/auth/queue and provider refusals remain intact, and the resolver row remains unavailable.

All work is local and uncommitted. GitHub upload, push, PR creation, merge and publication have not occurred. The full history merge remains pending; final capability acceptance, selected-source manifest and publication approval are separate gates.

## Subsequent preparation and real audio evidence

The previously open preparation ABA/concurrency lead is subsequently reproduced and corrected in the [preparation/gain follow-up](web-preparation-and-gain-2026-10-26.md). It adds eleven preparation cases and nine gain regressions, executes the actual HTML audio/Signalsmith graph against generated inputs, and retains runnable browser regressions in project source. The later full Web suite executes 179 app plus nine shared tests, all passing, with successful type checks and both extension builds. A separate observed metadata-handoff timing delay remains open. These later results supersede the corresponding missing-evidence notes above without changing their historical scope or establishing full provider/physical/musical acceptance.
