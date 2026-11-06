# Web transition ownership and audio-clock lifecycle

Status: **Complete for reproduced scheduling defects; broader integration remains Partial. Local only.**

The actual player host changes active deck, queue/index and current-track metadata during AutoDJ handoff. The previous one-second planning tick also delivered this notification, producing observed delays of approximately 731 ms in fallback and 917 ms in the loop graph. This establishes late ownership notification, not sample-clock drift or a lyric-renderer defect. Loop ownership uses the actual ramp handoff time, which can differ from half the transition duration at unequal tempos.

The same investigation reproduces reentrant host cancellation dereferencing a cleared plan, completion overriding host Stop, and an unowned wall-clock cleanup resetting EQ/gain on a reused deck after cancellation. These are supported failures, not assumed consequences of CPU speed.

## Implementation

- AutoDJ retains its one-second planning cadence and owns one cancellable next-boundary wake driven by AudioContext time. Handoff, cosmetic EQ/gain cleanup and completion share that ownership. Cancellation retires old work. Host callbacks are followed by identity checks so a newer plan or Stop wins.
- The new audioClock helper suspends deadline timers while the context is suspended, resumes against remaining audio time, retires on context closure, and backs off for a stalled clock. Already-closed contexts settle asynchronously so cancellation ownership is installed first.
- The transition scheduler delegates cosmetic cleanup to AutoDJ when provided, or uses the state-aware clock helper otherwise. Existing pre-roll gain correction, gain curves, rate ramps, completion guard, preparation lease and fallback remain intact.

Exact implementation/test files: `web/app/src/playback/automix/engine/autodj.ts`, `audioClock.ts`, `timing.test.ts`, `autodj.test.ts`, `transition-scheduler.ts`, `transition-scheduler.test.ts`. Browser evidence files: `web/app/tests/browser-audio/main.ts`, `gain-check.ts`, `README.md`, and `web/app/scripts/audio-browser-probe.mjs`. The helper is included in source fingerprints. No dependency, Gradle, Java, AGP, native platform or lockfile change.

## Behavioral verification actually executed

Initial timing tests reproduced nine failures. A further stale-cleanup regression failed before cleanup ownership was added. All 16 final timing/lifecycle cases pass: exact fallback midpoint, unequal-tempo loop handoff, cancellation/replacement, suspension/resume, closure, stalled-clock backoff, forced completion, late-clock catchup and reentrant Stop/cancellation. Existing assertions were retained; old context stubs now implement statechange lifecycle.

The full recursive Web run passes **195 app tests and nine shared tests (204 total)**. Recursive type checks and app/Chrome/Firefox production builds exit zero. The lockfile is unchanged. Warnings about existing bundle size and mixed static/dynamic imports remain; checks were not disabled.

Both reusable browser commands execute to terminal exit zero. The actual HTMLAudioElement/Web Audio/Signalsmith WASM graph runs three generated-stereo scenarios at 44100 Hz: normal, no-grid fallback with pause/cancel/restart and context suspension, and loop rendering/promotion. Fallback notification arrives 31.315 ms after its audio-time midpoint; loop notification arrives 0.272 ms after its actual ramp boundary in this run. Neither is early. Each handoff/completion occurs once; all captured samples are finite and within range; the context closes. During 350 ms suspension the audio clock and handoff count remain unchanged; media element positions settle by approximately 2.4 ms, so no claim of perfectly frozen media position is made.

Real OfflineAudioContext checks pass all three styles: zero wrong early frames, outgoing-only peak 0.400000006, intended fade peak 0.499992818, unchanged from the previous verified envelope. Generated PCM is captured upstream of a zero-gain output. No provider audio, account, physical route or audible quality is tested.

## Preservation and remaining limits

The phase retains independent staged/unstaged patches, both original indexes/HEADs and before-source archives in outer `.work/web-handoff-clock-20261026`. Guarded exact-file projection preserves candidate before content and leaves both indexes unchanged. No new commit, upload, push or pull request.

JavaScript deadlines are not sample-accurate and can be delayed by browser/background throttling. Full production lyric/seek synchronization, installed extension/provider playback, mixed-client behavior, representative musical listening and sustained resource/hardware profiles remain incomplete. These Web-only changes do not rebuild or change the verified native ZIP/APK or broaden the accepted Surface Thai result.

- [x] Reproduce timing, reentrancy and stale cleanup failures before correction.
- [x] Execute 16 focused cases, 204 full tests, type checks and production builds.
- [x] Execute real graph and three offline gain cases, preserving source fingerprints and JSON.
- [x] Reconcile reviewed source with candidate using before-content guards and unchanged indexes.
- [ ] Complete remaining broad integration/runtime/physical gates and final upload manifest review.
