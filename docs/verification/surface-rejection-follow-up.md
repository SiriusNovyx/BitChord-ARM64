# Surface build rejection: follow-up verification

24 October 2026. **Work in progress. The previously delivered build remains rejected.** A new local diagnostic candidate is verified below; it is not user-accepted and does not establish physical touchscreen, Atmos or GPU behavior. Do not use the earlier ZIP as evidence for these corrections. No GitHub publication is authorized.

## Confirmed failures and corrections under verification

| Area | Actual defect and old test gap | Correction and observed behavior | Remaining acceptance |
| --- | --- | --- | --- |
| Background FPS | A displayed production mesh drew at about120Hz for all caps. Compose frame timestamps had a relative origin; pacing compared those with System.nanoTime, producing negative waits. Old clock tests used identical origins. Outer draw instrumentation also missed redraws inside cached graphics layers. | Deadlines now use the wait clock's origin, while animation receives the original Compose timestamp. A relative-origin regression fails before and passes after. Short displayed production tests recorded about15/30/59/60 Canvas draws/sec at15/30/60/Dynamic, with runtime changes in one renderer. Draw instrumentation is inside the graphics layers. Full-art opacity updates have a separate layer-update counter. | Full application selection/reopening, individual renderer hiding and actual Surface power/GPU/frame-time measurements. Static backgrounds intentionally remain still. These draw counts do not establish GPU savings. |
| Full lyrics | The production clock stayed at0 while reported playback advanced about1.2sec with a visible STARTED lifecycle. The old foreground check required RESUMED; the old visibility test never checked a visible STARTED window. | Desktop rendering accepts STARTED and still honors LocalVisualActivity for hidden/minimized hosts. The production clock/drawing regression advances. An actual displayed LyricsPanel changed pixels at every background cap while an adjacent animated background remained capped. Pause, paused seek, resume and track change assertions passed with reported playback positions. Android's sharedUi foreground policy was not changed. | Real audio/lyric alignment, AutoMix adoption, recreation, long sessions and the user's reported full-app scenario. Programmatically reported positions are not audible synchronization proof. |
| Touch | The bridge registered SunAwtFrame rather than the receiving SunAwtCanvas. AWT also processes promoted mouse messages before the canvas WndProc; mixed touch/mouse delivery interrupted gesture handling. Old tests exercised a hidden window or direct scene events. Detach removed AWT's pre-existing touch registration. | Uses the actual displayed heavyweight canvas HWND. A thread-scoped WH_GETMESSAGE filter suppresses only touch-promoted mouse messages for registered canvases before AWT translation; ordinary mouse, pen signatures and wheels remain eligible. Prior registration flags are restored. The filter retires on final detach/destruction. Mouse capture changes no longer cancel independent native touch, and same-window keyboard focus transfer is preserved. Windows injection reaches native receipt, JNI, scene dispatch, Compose input and actual scroll state, including momentum. | **User confirmation of physical finger scrolling is mandatory.** Injection is not a digitizer test. Popup/modality, pen, physical mouse, DPI/monitor transitions and the complete application still require verification. |
| Home refresh | Three ordinary upward AWT wheel notches at the top caused a refresh. The previous test explicitly accepted one normalized delta-3 event. | The first at-top burst only prepares the gesture after idle. A separate overscroll must span at least300ms, include at least3events and cross the existing distance threshold;240ms idle completes an armed gesture. Reversal, leaving the top and pointer presses cancel it. A2sec cooldown supplements controller deduplication. Final-pass handling respects child consumption. Actual AWT tests reject the quick burst, accept deliberate completion and reject cooldown repetition. Existing touch-release/cancellation checks pass. | Human gesture feel, automatic-refresh/remount feedback, cache/source/profile lifecycle, offline presentation and broader navigation checks. The distance constant was not simply increased. |

Main code paths: `desktopUi/.../BackgroundFrameClock.kt`, `MeshGradient.kt`, `ArtworkMeshBackdrop.kt`, `ForegroundState.kt`, `RefreshPullGesture.kt`; `desktopApp/.../DesktopHomeRefreshPane.kt`, `DesktopTouchAwtAdapter.kt`, `DesktopTouchInput.kt`, `DesktopTouchTrace.kt`, `Main.kt`; `desktopApp/native/window/window_jni.cpp`.

Further production-path regressions exposed and corrected:

- The outer portrait artwork crossfade used an unrestricted `animateFloatAsState`, independently of the inner renderer cap. At 15 FPS it applied 32 opacity changes during 360 ms. `BackgroundAlpha.kt` now uses the background-only clock: the same displayed layer applied 6/11/22 changes at 15/30/60 FPS. The corresponding Gradle test measured 5/11/22. Hidden, settled and recreated artwork does not replay redundant fades. Retained mesh/artwork rendering is scoped by contributing opacity; lyrics remain outside those scopes.
- A performance measurement begun before reset or disable/reenable could contaminate the new Startup session. Measurement tickets now carry session ownership. Both regressions failed before correction and pass afterward. Layer counters are counts; callback intervals are not represented as draw FPS.
- Home's identity omitted the channel and session renewal. Disabling its source also retained freshness and allowed old work to remain owned. Identity now includes profile/session revision, retirement clears content/freshness and cancels obsolete requests, and availability-dependent effects/pagination/publication are gated. Seven controller tests pass, including both new regressions.
- Finished Home feedback replayed when the page reopened and held its content 48 dp lower for four seconds. Two actual layout tests observed this exact offset before correction. Result feedback now uses a brief snackbar, does not shift the content or replay on remount, and does not claim saved content exists when offline. All four pane tests pass, including touch release/cancel and deliberate wheel completion.
- An addon could return its cached search-row URL after `/stream` refused HTTP 401. Provider-wide refusals/outages now retire that provider and let the ordered resolver try the next eligible source. Real local HTTP fixtures verify 401, 403 and 503 fallback/bypass, while an ordinary 404 retains the legitimate row-URL fallback. The eight-test adapter integration suite passes. Spotify behavior is unchanged.

## Test evidence and limits

- Focused current-source standalone compilation/JUnit execution:12core project tests passed. This is not a full Gradle build.
- Displayed project tests: the initial mixed suite had1failure of7, despite isolated touch passing. The queue-stage suppression and cancellation corrections were then exercised in the same mixed suite:7passed. Preserve the failing run as regression evidence.
- Current native ARM64 and x64 touch fixtures: PE machines 0xaa64 and 0x8664, no failures, including registration flags, multiple-canvas ownership, cleanup and input signature/cancellation rules. The x64 executable ran under emulation.
- Reflected-art900ms fades recorded16/29/56 Canvas draws at15/30/60. Full-art320ms fades applied6/11/20 opacity updates. The subsequent400ms settled observation recorded no background draws/layer updates.
- Full displayed lyrics/background probe uses synthetic lyric content and programmatically reported playback positions. It checks actual rendered lyric pixels, not just timer callbacks; it does not test decoded audio or real AutoMix adoption.
- The subsequent combined displayed-window suite passed 18 tests: actual draw cadence, artwork-layer pacing/visibility/recreation, OS-injected HWND touch scrolling, adapter ownership, bounded tracing, wheel input, Home layout and metric-session ownership. Injected touch moved actual scroll state 485 px and delivered all 26 contacts through JNI, scene dispatch and Compose input. This does not establish physical finger scrolling.
- Full ARM64 Gradle checks after the crossfade/metrics correction passed: desktopApp 562 executed, 6 headless window skips; desktopUi 78 executed, no skips; no failures/errors. This snapshot precedes the latest Home/addon changes, whose complete Gradle verification is pending.
- Full x64 checks initially had eight native-analysis failures because Code Integrity refused the exact x64 analyzer DLL. After the user reported temporarily disabling Application Control, a serialized retry passed: desktopApp 562 executed/6 headless skips, desktopUi 74 executed/no skips. Native inference actually ran. No OS policy, signing, file blocking or test requirements were changed by the agent. This x64 snapshot precedes the crossfade/metrics/Home/addon follow-ups; it does not certify their current revision.
- Corrected package contents and package smoke tests remain pending. No current-source APK/ZIP is claimed here.
- A further complete x64 run including the current crossfade, measurement ownership, Home and addon corrections passed in 256.92 seconds: desktopApp 568 executed/6 headless skips; desktopUi 78 executed/no skips; no failures/errors. The matching current ARM64 run is pending. Saved result summaries retain architecture and snapshot distinctions.
- The matching current ARM64 run also passed, in 149.08 seconds, with the same 568 desktopApp and 78 desktopUi tests executed, six desktopApp window skips and no failures/errors. Home feedback component images were inspected in light mode and a narrow dark layout at 130% font size: feedback stayed readable and above the fixture's playback-control boundary. This is not full-app visual or physical-device acceptance.
- Android source/download follow-up: 255 tests matching `*Source*` and `*Download*` passed under the existing modern build's `testDevDebugUnitTest` task, with no failures/errors/skips. This includes the focused 72-test adapter/routing suite. Downloads now walks actual saved priority slots sequentially, bypasses known unavailable providers, tries YouTube at its position and continues below it on failure. Initial and retry YouTube requests check current availability. Matching, known-container/export checks and premium format preference remain in the existing resolver. A new Lossless regression caught an early lossy floor displacing later FLAC; the corrected loop retains ordered lossy fallback while continuing for the requested premium copy. High mode still declines an oversized lossless file.
- Android HTTP refusal/outage handling now stops query variants and does not return cached row URLs after 401/403/503. The existing nullable gateway-miss contract is retained; cancellation still propagates. No provider enforcement, Spotify audio or authorization path was bypassed.
- One download test fixture initially caused repeated heap OOMs in its 512 MiB worker after a throwing proxy-selection guard entered route planning. Thread/heap diagnostics were captured; the process ended without agent termination. A test-only application interceptor now blocks external requests before connection planning. Reruns completed without OOM and exposed the actual routing assertions. No application memory-leak finding or heap-limit change is claimed.

## Optional touch trace

Use these steps **only with the corrected diagnostic candidate once its location and hash have been supplied**. Close other BitChord instances first. The earlier rejected build does not contain this trace.

Launch the portable executable from PowerShell with a process-local JVM option. Replace the package path with the supplied extracted candidate directory:

```powershell
$packageDirectory = 'C:\Path\To\Corrected-BitChord'
$previousJavaOptions = $env:JAVA_TOOL_OPTIONS
try {
    $env:JAVA_TOOL_OPTIONS = ($previousJavaOptions + ' -Dbitchord.touch.trace=true').Trim()
    & (Join-Path $packageDirectory 'BitChord.exe')
} finally {
    $env:JAVA_TOOL_OPTIONS = $previousJavaOptions
}
```

For each problem page, note the page and Windows scaling percentage separately, then perform one short finger drag and release. Try Home, Library, Search, Settings, full lyrics and a playlist; include a horizontal row, a popup and a modal. Check wheel, hover, text selection, keyboard navigation, pen and source-row mouse reordering separately. Avoid mixing physical mouse input into the short digitizer trace unless testing that interaction intentionally.

The portable trace is `data\logs\touch-trace.csv`. It saves every5sec and on graceful application exit. Close BitChord normally after the short trace, then preserve that file. Do not share the entire data directory, preferences, credentials or provider logs. Launch normally without the option to disable tracing.

The file contains stage counts and at most512recent numeric events: elapsed time, stage, HWND/contact IDs, coordinates and flags. It has no titles, song metadata, URLs, credentials or provider responses. `native_message` records Win32 message numbers; a message OR-ed with0x10000 denotes a suppressed touch-promoted mouse packet. `awt_mouse` records numeric AWT mouse event IDs. The file describes software receipt, not proof of physical device identity.

Interpret the earliest missing stage before drawing a conclusion:

1. Missing `attach_ok`: bridge discovery/registration or the wrong build must be investigated.
2. Registered canvas but no native messages during a finger drag: examine HWND targeting, digitizer/Windows routing and scaling with the actual device. This is not a successful touch test.
3. Native receipt without `jni_contact`: investigate native decoding/JNI delivery.
4. JNI receipt without `scene_dispatch`: inspect binding, modal/layer blocking, cancellation or adapter failure counters.
5. Scene dispatch without Compose input/scroll consumption: inspect scene routing, concurrent AWT input, gesture boundaries and scrollability. A few press/move events alone are insufficient.
6. Scroll consumed but poor movement: inspect actual list state, nested scrolling, coordinate transforms, momentum and cancellation.

Main-window final-pass/scroll observation does not automatically cover every separately composed popup. Native/binding/scene stages identify the canvas; popup-specific interaction tests are still required. Absence of a later stage must be interpreted with that boundary in mind.

## Surface acceptance scenarios

- **FPS and lyrics:** In Performance mode, select Dynamic/15/30/60 while the current animated background and full timed lyrics are visible. Change focus while keeping the player visible; lyrics must continue. Pause, seek while paused, resume, skip with AutoMix, change tracks and reopen the player. Minimize and restore to the current playhead. Repeat with ordinary still artwork; still backgrounds must not redraw solely to meet a cap.
- **Home:** Navigate from down the page to the top and stop. A quick upward burst must not refresh. After the top has settled, deliberately overscroll while watching the approaching/armed feedback, then stop. Reverse before completion, navigate away, repeat during loading and repeat during cooldown. Check touch pull/release, cancellation, offline/error and old-content retention without interrupting playback.
- **Measurements:** Separate startup from warmed operation. Record lyrics on/off, cap, active background type, visible/minimized state, AC/battery, GPU usage, frame-time distribution, dropped frames and JVM heap/GC at fixed intervals. Compare equivalent tracks/scenes and retain raw observations. No GPU improvement is established by the functional draw-cadence probes.

## Diagnostic candidate verification

24 October local verification after the diagnostic, analysis-retirement and Classic-font
corrections: full Windows ARM64 and x64 invocations each ran 572 desktop tests and 81 UI
tests with zero failures/errors, plus six headless skips. XML was archived separately
before switching architecture. The x64 run actually executed native model inference
under emulation with the user's current Application Control state; the agent did not
change Windows security settings. ARM64 took 80.36 seconds and x64 took 239.94 seconds for these
invocations. Those elapsed times are build/test observations, not controlled benchmarks.

The current Android devDebug unit suite and APK assembly succeeded in 424.15 seconds:
1,146 tests executed, zero failures/errors and one existing skip. Raw XML was archived.
This verifies JVM behavior and compilation/packaging, not Android device interaction.
Windows portable assembly subsequently succeeded in 200.54 seconds. The verified candidate is:

- `BitChord-surface-diagnostic-windows-arm64.zip`, 399,661,310 bytes, SHA-256 `e869a703bec61c0949000982aa405ab503b97bd25e29766215f20bcc6ba1abc4`.
- `BitChord-surface-diagnostic-android-arm64-debug.apk`, 98,556,450 bytes, SHA-256 `b5ef74ed067d4a2e00807383e464c3101ac5521198108d160e452e9f54faa544`.

Both archives passed CRC checks. The Windows package has 80 standalone ARM64 PE files
and 34 selected ARM64 native resources in jars; 1,343 app classes and both UI/shared jars
match the current build. The existing ARM64 Spotify helper and its notices are preserved.
The APK passed `apksigner verify --verbose --print-certs`, has only `arm64-v8a` native
libraries (ten ELF AArch64 binaries), and contains the new Android observation class.
Its package is `com.dev.bitchord`, version 1.8.1 (code 29); this is devDebug, not release signing.

A fresh ZIP extraction launched the actual visible 1830x1170 AWT main window and recorded
native bridge `attach_ok`. The startup harness originally watched only the launcher parent,
then incorrectly expected the default close-to-tray action to terminate the process; those
failed harness runs are retained. Inspection identified the child JVM/window owner and
confirmed the normal tray log. A subsequent isolated test seeded only its own new portable
preferences with `close_to_tray=false`, observed the window, and closed with exit 0 without
forced cleanup. No real user preferences or package defaults were changed. This is startup/
close/bridge-attachment verification, not playback, physical finger input or complete UI acceptance.

Quit an existing instance from its tray before testing. Extract the candidate into a new
folder and preserve the previous folder/data. To test saved settings, copy the old `data`
folder locally into the new extraction while keeping the original intact. The ZIP contains
no user data or credentials. Use the short Surface scenarios and opt-in trace instructions
above; share only `touch-trace.csv`, not the data directory or credentials.

Repository metadata was checked locally: origin `https://github.com/SiriusNovyx/BitChord.git`,
existing branch `integration/upstream-untouched-files`, HEAD
`efc2079d18847efe06798ecca4878fd3731d6e09`. No new branch, commit, upload, push, merge,
PR creation or PR update was performed. All implementation changes remain local/uncommitted.
Internal work metadata, caches, credentials, runtime data, generated packages/build outputs
and unrelated files remain excluded from any future source upload; that upload still needs
its own exact repository-relative manifest review and explicit approval.

## Additional diagnostic corrections under verification

The actual beat-model cancellation regressions reproduced two failures: already interrupted
work still initialized the model, and the production inference loop still ran its remaining
chunks. BeatTracker now checks interruption before expensive stages and between/after
native inference calls, and closes SessionOptions after creating a session. An already
executing native call remains cooperative only after it returns. A separate regression
opened a real ONNX session and confirmed analyzer release left it open. Desktop retirement
now queues initialized-model cleanup on the existing owning worker after cancelled work
returns; it does not initialize an unused tracker or wait on the playback-control caller.
Eleven beat/model/preparation/retirement tests executed without failures/errors/skips in
`rejected-analyzer-release-green`. These do not establish audible transition quality,
physical-device latency or a long-session memory/GPU improvement.

The compact stats control previously crowded the scrubber and its details omitted bitrate,
sample rate, bit depth and source. Both UI forks now reserve a 48dp control between the
timestamps, use an information icon when scaled text cannot fit, and open a scrollable
dialog with the measured format and source. The six concise Atmos/output fields remain
visible there; supplementary context is expandable. A snapshot belonging to another track
cannot label the selected track. Three production-scrubber rendering/input regressions
failed before the correction and passed afterward. A separate narrow-layout visual fixture
also passed; screenshots at normal and 200% text size were inspected. These are desktop
Compose fixtures, not Android hardware layout acceptance.

Android prepared-player decoder events could arrive before the standby player acquired
the active listener. A per-player/per-track observer now attaches during player creation,
before preparation, retains bounded observed input/decoder evidence for handoff, and retires
released players. Decoder names require a matching initialization event or an explicit
Media3 reuse evaluation. Null reuse evidence cannot inherit another track's decoder name.
Nine real Media3 callback-contract tests plus 13 existing Nerd Stats regressions executed
with zero failures/errors/skips in `android-observation-reuse-green`. The tests use proxy
players to deliver actual typed callbacks; they do not establish device decoder behavior,
audible synchronization, actual output routing or Atmos object rendering. Android output
remains Unknown where only a preferred/connected device is available as evidence.

The current shared Classic player also clipped the artist line at 200% text size because
its credits area had a fixed 60dp height. That space now follows text scaling and participates
in artwork sizing, collapsed-header placement and lyrics/queue offsets. A production JVM
Classic fixture failed on the clipped 12dp artist line before this change, then passed twelve
phone portrait/landscape/tablet cases at normal and 200% font with stats off/on, 36 actual
pointer-driven transport callbacks and six diagnostic-dialog openings. Images were inspected.
Synthetic cover markers retained square proportions; measured covers occupied 61.8-83.3%
of the shorter fixture dimension. This fixture loads the shared UI fork with an
Android-theme/font host adapter; Android system insets, actual touch, Canvas, media-session
and audible playback still require device verification. The Windows Modern selector is retained.

## AutoMix follow-up after the diagnostic candidate

**These source changes are not included in the diagnostic ZIP/APK identified above.**
Those verified archives and their copied reports remain fixed snapshots. This follow-up
is local work under verification, not a claim of user acceptance or improved sound quality.

Production-engine captures with synthetic PCM reproduced overrange summation before the
native output clamp. Per-deck EQ protection could not catch later mixing/effect peaks.
The existing fade curve and overlap trim are retained. A linked-channel output catcher now
runs after stretching and app volume, before a processed block enters partial-write handling.
The engine-owned sink uses unity gain so volume is neither applied twice nor omitted. This
ordering preserves headroom already supplied by lower app volume; output retries retain
the same processed PCM, including its gain, until that block completes. Volume updates
apply to the next processed block; already queued audio is unaffected. The shared limiter's
existing EQ default remains 0.985, with an
explicit full-scale ceiling for this final stage. Its public no-argument constructor remains.

| Synthetic fixture at full app volume | Before peak / overrange frames | After peak / overrange frames |
| --- | --- | --- |
| Correlated 0.9-peak tones | 1.070286 / 83,508 | 1.0 / 0 |
| Different loud frequencies | 1.070117 / 13,884 | 1.0 / 0 |
| One loud stereo side | 1.129747 / 150,151 | 1.0 / 0 |
| Coincident transients | 1.177315 / 7,032 | 1.0 / 0 |

Each measurement covers 720,000 stereo frames from the production manual-skip path;
the configured fade is 15 seconds. A fake output captures accepted PCM, not a physical
endpoint. Quadrature, opposite-phase, low-level and unequal-level captures retain their
complete baseline PCM hashes. Half-volume peak is 0.535143, mute emits zero PCM, and
whole/partial output writes have identical capture hashes, including at half volume.
Release survives promotion and is reset for a seek or output discontinuity. These are
waveform/control-path results; representative-music listening and device behavior remain pending.

The same tests exposed an independent paused-playhead defect. The 250 ms publication
bucket could leave the incoming position stale indefinitely after pause, and the paused
pump did not refresh it. Deterministic pauses at 9,160 ms during a blend and 15,160 ms after
promotion both failed before correction. The pump now samples an open paused output,
uses exact stopped-position comparison and starts neither transitions nor analysis from
that paused refresh. The idle test waits for actual pump pause calls and five further idle
iterations, then verifies the position and timestamp remain unchanged. It does not infer
GPU behavior from stable timestamps.

| Modified source/test file | Purpose |
| --- | --- |
| `desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopAudioEngine.kt` | Final gain/protection placement, discontinuity resets and paused position publication. |
| `desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopOutputLimiter.kt` | Linked-channel final PCM processing using the existing limiter. |
| `desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopEqualizer.kt` | Update the now-obsolete claim that nothing follows per-deck protection; DSP unchanged. |
| `shared/src/commonMain/kotlin/com/music/bitchord/playback/PeakLimiter.kt` | Configurable ceiling with the existing default and API retained. |
| `desktopApp/src/test/kotlin/com/music/bitchord/desktop/DesktopMixHeadroomTest.kt` | Actual engine capture, volume/mute, partial writes, handoff/seek and paused clock/idle regressions. |
| `desktopApp/src/test/kotlin/com/music/bitchord/desktop/DesktopOutputLimiterTest.kt` | Bit-exact pass-through, channel linking, release, reset, volume and block-size behavior. |
| `shared/src/commonTest/kotlin/com/music/bitchord/playback/PeakLimiterTest.kt` | Existing default, full-scale behavior and invalid ceiling rejection. |

Full ARM64 verification executed 594 desktop tests (six headless skips), 81 UI tests and
104 shared tests without failures/errors; a subsequently added idle test also passed in
focused ARM64 verification. Full x64 verification then executed 595 desktop tests (six
headless skips), 81 UI tests and 104 shared tests without failures/errors. XML was archived
per architecture. Android's current full devDebug unit suite passed with 1,146 executed
tests, no failures/errors and one existing live-network skip. Included processor suites
passed 14 EQ, six loudness and 13 Nerd Stats regressions; these are part of that total.
The APK was not rebuilt in this follow-up. No new package or publication is claimed yet.

## Eight-workstream disposition

| Workstream | Current status | Outstanding work |
| --- | --- | --- |
| Source ordering / Spotify | Partial; native exclusive-mode activation permission-blocked | Android download routing and HTTP refusal regressions now verified; broader module/reorder/full-app quality checks and real provider behavior remain. Existing Spotify PCM/auth/queue retained; priority row unavailable. The isolated-mode and official-client-handoff designs remain technically feasible proposals, not implemented modes or compliance findings. Code313/unspecified UserViolation is not mixed-service attribution. |
| Touchscreen | Partial; user-rejected pending acceptance | Corrected candidate, physical finger/mouse/pen/DPI/modal verification. |
| Atmos | Partial; hardware output unverified | Prepared-decoder observation ownership and stats presentation corrections now have local regression evidence; current full-platform checks and actual Android handoff/output verification remain. Preference/decoder existence does not establish JOC or object rendering. |
| Android Classic player | Partial; device layout acceptance pending | Current production Classic JVM layout, optional-stats/dialog and pointer-transport fixtures passed; Android device interactions remain. Windows Modern selector retained. |
| Home refresh | Partial; UX remains pending acceptance | Current full-app wiring and broader navigation/offline/gesture acceptance. Core publication still awaits optional supplementary endpoints, bounded at 12 seconds; incremental publication has not been implemented. |
| Background FPS | Partial; corrected rendering path locally verified | Current app/restart/visibility verification and Surface acceptance. |
| Performance instrumentation / GPU | Partial; physical measurements pending | Session ownership and primary hidden-layer scopes corrected; full-app visibility/instrumentation overhead and repeatable startup/warmed GPU measurements remain. No invented benchmark or sound-quality claims. |
| AutoMix reliability | Partial; device/listening acceptance pending | Native chunk cancellation, desktop model retirement, post-gain headroom protection and paused-playhead corrections have regression evidence, including Android compatibility. Current-package verification, representative-music listening and actual lyric/endpoint timing remain. The 15-second setting remains fade duration, not an assumed preparation deadline. |

All local source changes remain uncommitted/unpublished. Preserve the staging index and historical unpublished work. A reviewed destination, new branch and exact upload manifest require separate approval before any publication.
