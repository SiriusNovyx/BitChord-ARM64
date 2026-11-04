# Beat-model comparison, peak picking and analysis-cache revision

Status: **Local peak/cache corrections verified on ARM64/x64, Android and Web; broader model/musical/long-session acceptance remains Partial.** Full AutoMix quality and upstream capability acceptance remain incomplete.

## Audit and checklist before production changes

The actual native/Desktop analyzer, shared BeatTracker/mel front end, upstream Web model implementation and both shipped model files were inspected before choosing a change. Seven implementation sources were preserved with hashes in the outer `.work/model-comparison-20261026` audit. Cache/type/config sources were separately preserved before their related corrections. The protected index digest remains `e06ebe4e519c5aea6049c651642786a80d8cad8815b8cf3b895aab346b079a7a`; no staging or publication is performed.

- [x] Generate identical PCM by invoking the actual upstream synthesized-loop implementation, retaining provenance.
- [x] Compare actual native and Web mel arrays and run each shipped model through the production native tracker.
- [x] Separate the upstream model allegation from observed behavior; inspect actual logits rather than attributing failures by filename.
- [x] Reproduce flat-peak duplication and add failing native/Web regressions before correction.
- [x] Verify cache revision, unversioned-input rejection, current-cache restoration and Web manual-override preservation.
- [x] Run full ARM64/shared/UI/Android and Web checks without changing dependency versions or disabling checks.
- [x] Finish actual x64 verification, post-correction production captures and immutable package checks.
- [x] Reconcile the reviewed source/test/config/report files into the pending complete-history checkout with both indexes preserved.
- [ ] Complete broader musical fixtures, real-recording/phase comparison and extended-session acceptance.

## Actual model/front-end findings

The existing Desktop/Android asset is `beat_this_int8.onnx`, 4,533,252 bytes, SHA-256 `9dc29f1fcd713d18f48a2755109fce01429ba6d1639607af8ae5c7449b47070f`. Web uses `beat_this_final0_q8.onnx`, 23,138,816 bytes, SHA-256 `7801f9c1adfd5ff193f37ececa3320bf1fda1ddee2bf7de341cea4c25ffd0acd`. Both accept float `[1,time,128]` and expose beat/downbeat float outputs. Both assets remain unchanged.

The generated fixture invokes upstream `testtones.ts` at 22,050 Hz, 124 BPM, 16 bars, bass 110 Hz: 682,839 mono samples / 30.9677551 seconds. Its PCM SHA-256 is `a91aaeaf777ed002d8bc9f435d7b5bd50a00e39ce9f46f2d96a380103060094b`. The actual TypeScript front end is evaluated after type stripping, with only unused ONNX imports stubbed; no Web inference runs in that Node step. Native inference uses the actual shared BeatTracker, packaged ARM64 JNI front end and ARM64 Java 21, in separate JVMs for each model.

The native/Web mel arrays have the same 1,549 × 128 shape. Maximum absolute difference is `3.637978807091713e-12`; RMSE is `2.2836234090609898e-14`. This fixture provides no evidence of a meaningful front-end mismatch.

| Observation before peak correction | Existing native model | New Web model through native tracker |
|---|---:|---:|
| Model grid returned | Yes | Yes |
| Beats / downbeats | 68 / 8 | 64 / 17 |
| Median-gap BPM | 124.0158 | 124.2275 |
| Tracker confidence | 0.7812 | 0.95 |
| Mean nearest-quarter phase error | 66.88 ms | 239.01 ms |
| First tracker pass, one observation | 2,215.49 ms | 3,350.30 ms |
| Reused-session pass, one observation | 1,358.62 ms | 2,280.84 ms |

The newer model follows the offbeat hi-hats closely in this loop (half a 124-BPM beat is 241.94 ms). This is an intentionally ambiguous generated-rhythm challenge, not a universal real-music ranking. The existing model does return beats in this native runtime, so upstream's blanket “finds no beats” comment is not established for this build. Neither result justifies replacing the native/Android weights solely on that comment. The newer model remains in Web; native and Android retain their existing functional model pending representative validation. Reported confidence is not proof of correct musical phase.

Timings are individual first-tracker/reused-session observations, not a calibrated benchmark, process startup profile or CPU/GPU/battery improvement. Sampled JVM heap excludes native allocations. No physical Android model result is inferred from native JVM execution.

The existing source retains Orchard/AutoMix AGPL notices and model MIT provenance. No model weights or third-party algorithm are newly imported into another platform in this correction; no new ML dependency is added. This technical provenance review is not a general legal-compliance conclusion.

## Confirmed peak-picking defect

Both implementations compare the next peak's index against the **running mean** when collapsing adjacent equal maxima. After two adjacent frames, the mean lies half a frame behind the last frame, so a third adjacent maximum appears too far away and becomes another event. A three-frame plateau returns `[5,5.5]` instead of `[5]`; a boundary plateau returns `[1,1.5]` instead of `[1]`. This is an independently reproduced algorithm defect, not an attribution of the user's prior bass distortion.

The corrected loop tests adjacency against the last accepted index and retains the unrounded plateau centre. Fractional centres remain intact; isolated integer peaks keep their parabolic subframe interpolation. Separate equal maxima with lower samples between them remain distinct. Both Kotlin and TypeScript receive the same correction.

Actual model logits for this particular loop contain **zero positive plateaus** in either head of either model. The algorithm defect therefore does not explain the model comparison above. It is exercised by explicit deterministic plateau fixtures, including lengths 2–9 and boundaries; real-recording incidence remains unmeasured.

## Cache migration and state preservation

Shared native/Android cache schema advances from 1 to 2; Web analysis version advances from 8 to 9. The existing load policy rejects older derived analyses so affected tracks are remeasured on demand. This does not schedule a whole-library analysis sweep or change credentials, source order, enabled state, playback settings or user volume. The existing native store removes invalid derived entries on load; unrelated files are untouched.

Missing cache provenance previously inherited the current version through a serializer default. Reads now default an absent version to zero, while new writes explicitly stamp version 2. Tests reproduce the unsafe unversioned acceptance before that correction and verify current round-trip behavior afterward.

Web rereads the stored record before replacing a derived analysis and retains its manual BPM/grid-offset overrides when the new analysis does not supply overrides. The migration test exercises the real request/cache/publication loop while isolating audio computation; it does not claim provider download/decoding verification. Current revision-9 entries restore before audio is requested. The storage-read failure path retains the existing error/cooldown behavior instead of overwriting unknown user metadata.

## Changed files

- `shared/src/jvmSharedMain/kotlin/com/music/bitchord/playback/smart/BeatTracker.kt`
- `shared/src/jvmSharedMain/kotlin/com/music/bitchord/playback/smart/AnalysisStore.kt`
- `shared/src/jvmTest/kotlin/com/music/bitchord/playback/smart/BeatPeakPickingTest.kt`
- `shared/src/jvmTest/kotlin/com/music/bitchord/playback/smart/AnalysisStoreRevisionTest.kt`
- `web/app/src/playback/automix/analysis/models.ts`
- `web/app/src/playback/automix/analysis/types.ts`
- `web/app/src/playback/automix/analysis/analyzer.ts`
- `web/app/src/playback/automix/analysis/models.test.ts`
- `web/app/src/playback/automix/analysis/analyzer.test.ts`
- `web/app/tsconfig.node.json`
- This report and the upstream/audio verification summaries.

The TypeScript test project now includes the existing `src/**/*.d.ts` declarations. New real-pipeline imports exposed its omission of Vite environment/extension ID, Essentia and WASM declarations. The initial type-check failure is retained. The correction includes those declarations rather than suppressing errors, weakening types or changing dependency pins.

## Verification so far

Native peak tests first fail two of four cases; Web peak tests fail the same two cases. The initial native revision tests fail two cases, and an added missing-version case separately fails before correction. Web migration tests first fail all three cases. The final focused Shared JVM suite executes 114 passes with zero skips (`beat-cache-provenance-final-green-20261026`, exit 0, 43.97 s).

`beat-peak-cache-final-arm64-20261026` exits 0 in 287.62 s: Desktop Java 21 and 25 each execute 648 passes/six existing display skips; Desktop UI 98, Shared UI 74 and Android 1,147 execute with zero skips. Shared 114 is up-to-date from the preceding executed current-source suite. Android uses the existing `LIVE_TESTS=1` opt-in; raw XML containing Genius lyric previews remains private. Windows ARM64 plus Android/TV/Wear packaging succeeds. An archive-label parser initially matched similarly prefixed task names; the retained summary is corrected against exact task-name lines, distinguishing execution from up-to-date reuse.

Web executes 151 app tests across 14 files, all passing; the shared Web package reports no tests and is not counted as tested. Following the declaration inclusion, recursive type checks and app/Chrome/Firefox extension production builds succeed. The lockfile is unchanged; the build's party-server URL is a CI fixture, not a deployed service or provider-authentication acceptance.

No limiter, gain, DSP, source policy, UI/font, lyrics clock, skip preparation, fade duration or AutoMix mode is disabled or retuned. The user's `6be6a26b…` Surface acceptance remains recorded for active/blurred/opening translations at 150%, and the previous automatic-pair listening result is retained in its own package context. Broader real-recording, device-route/Atmos, GPU, thermal and nine-hour results remain unverified.

## Final platform and production-runtime evidence

`beat-peak-cache-final-x64-20261026` exits 0 in 201.85 s. The actual x64 Java 21 worker paths are verified in the log: Desktop executes 648 passes/six existing display skips, Desktop UI 98 and Shared UI 74 pass with no skips. Shared 114 is reused/up-to-date; it is not falsely counted as an executed x64 suite. A separate direct x64 Java 21 invocation executes **all seven new shared peak/cache cases**, passes them, and records `os.arch = amd64` in that same JVM.

Both shipped models are replayed after correction in new native JVMs. Their captured beat/downbeat lists, BPM, confidence and phase-error fields are exactly unchanged on this non-plateau fixture. This checks preservation of those results, not universal analysis equivalence or superiority.

The first healthy-pair production replay stops at setup because its deliberately preserved revision-1 fixture is now correctly rejected by the production revision-2 store. That failure is retained. The retry uses an external, explicitly renamed copy of the preserved old serializer solely to decode historical fixture values, then writes them through the current production store. No historical values or production engine/cache-rejection code are relaxed. The actual engine emits byte-identical generated PCM: WAV SHA-256 `d59c1203c73f74f296086f1302394e894614e5aee28d1ff2d0ba787e6e0f434b`, 864,000 stereo frames, one handoff, peak `0.8756049`, zero overrange samples.

The separate accelerated lifecycle probe completes 24 SMART transitions with eight failed preparations, 33 opens/33 closes, maximum registered decoders two, 21,772,800 frames, peak `0.38654914`, zero overrange, empty registry and terminated audio thread after shutdown. This run takes 6,868 ms; sampled JVM heap is 33,188,480 first / 29,522,560 last / 42,161,544 maximum bytes. These are bounded generated-fixture observations, not a native-memory leak proof or long-session benchmark.

The newly built production Web worker `analysis.worker--BmZ7eM8.js` (SHA-256 `ce34d9b06693903e9ec3208a12a7798553523d88271905b27359ce22dbc1d160`) runs in the actual in-app browser, served from the production `dist` tree. It executes both shipped ONNX models and WASM on the actual upstream generated 44.1-kHz loop, with browser resampling for the beat input. The result has 64 beats, 16 downbeats, 345 vocal frames, global fitted BPM `123.98751999` and A minor/8A. Its raw model BPM is `124.26899076`, and its first model beat is `0.24051629` seconds, again illustrating why cadence/confidence alone cannot prove correct musical phase. The single observed wall time is 8,208.3 ms. The result, worker hash, localhost-only request log and actual screenshot are retained; no provider audio, account login, audio output or deployed backend is involved.

A fresh read-only upstream `ls-remote` still returns HEAD/main `1189e6a566c9181111205f26a983f7984f9393ea`. The complete-history merge remains uncommitted and publication-gated.

## Verified local candidates

Folder: outer `.work/rejected-surface-20261023/deliverables/2026-10-26-peak-grid-revision`.

| Artifact | Bytes | SHA-256 |
|---|---:|---|
| `BitChord-peak-grid-revision-windows-arm64.zip` | 408,641,362 | `62d69b5391601e9a92e5c3ba9caa5d9560baf514bdb60578784a5d65f6336ab2` |
| `BitChord-peak-grid-revision-android-arm64-debug.apk` | 99,298,435 | `2b8a795fe8a94c94109de34a56b404f3020342ff64467c9fcf64f8ed0654b50d` |

Verification checks CRC, 80 standalone ARM64 PE files, 34 native JAR resources, all 1,355 current application classes, current UI/shared JARs, unchanged original Bangna font, preserved Spotify helper/notices and unchanged existing beat-model hashes in both Windows and APK. The APK has only arm64-v8a native libraries, ten AArch64 ELF files and a valid Dev signature matching the previous update certificate (`com.dev.bitchord`, 1.8.1/code 29). The newer 23-MB Web model is not smuggled into native/Android packages. The three accepted Thai renderer source bodies remain byte-identical; this does not extend physical acceptance to untested new audio scenarios or devices.

Updating to these packages causes affected old derived analyses to be recomputed on demand. A transition may use the existing conservative fallback until fresh analysis is ready; no new analysis-quality reduction, feature disablement or preparation deadline is introduced. All prior candidates are preserved. No commit, upload, push, PR or release is performed.


The guarded thirteen-file peak/cache follow-up is reconciled into the pending complete-history checkout. Final comparison counts **1,860 authoritative paths / 1,857 candidate project paths**: 734 byte-identical and 1,123 line-ending-only, with no source/test/report content difference. The three deliberate local-only environment entries remain `.claude/launch.json`, `.serena/.gitignore` and `.serena/project.yml`. Coverage accounts for all 1,241 upstream paths (1,147 exact, 18 line-ending-only, 76 reviewed adaptations), 528 native restoration/adaptation paths and 88 fork/verification paths. All 110 original merge conflicts remain resolved, zero unmerged paths; both staging indexes, original HEAD and complete upstream merge parent remain unchanged. This establishes source reconciliation/provenance, not full capability, musical, physical-device or publication acceptance.
