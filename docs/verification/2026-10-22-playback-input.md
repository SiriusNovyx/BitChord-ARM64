# Local playback and input implementation: 22 and 23 October 2026

Historical report for the previous build, which the user rejected after Surface testing.
Its test results are retained as history, not acceptance evidence for a corrected build.
See [the rejection follow-up](surface-rejection-follow-up.md) for current defects,
corrections, verification scope and outstanding acceptance.

Local implementation only. This report distinguishes code/automated verification from physical hardware and provider acceptance. The project is not declared fully complete.

## Requirements checklist

| Workstream | Status |
| --- | --- |
| 1. Sources and Spotify | Partial |
| 2. Desktop touchscreen scrolling | Partial |
| 3. Atmos pipeline and diagnostics | Partial |
| 4. Android Classic-only player | Partial |
| 5. Desktop Home freshness and gestures | Complete |
| 6. Background FPS controls | Complete |
| 7. Optional performance instrumentation | Partial |
| 8. AutoMix preparation and skip ownership | Complete |

## 1. Sources and Spotify

**Status: Partial.**

**Root cause/evidence:** Persistence forced YouTube back on, legacy JioSaavn migration could override a saved choice, built-ins were rank-sorted and desktop foreground playback raced providers. URL canonicalization also collapsed credential-bearing endpoints. Native Spotify opening failures entered a generic source retry, while midstream provider refusal lost its diagnostic.

**Implemented behavior:** Custom sources retain their group ahead of JioSaavn, Spotify and YouTube Music. Both groups can be reordered with a dedicated mouse/touch handle and existing keyboard arrows. Stored ordering drives the sequential foreground/fallback walk. Unknown stored entries/fields, IDs, credentials, disabled states, Jio consent and download policy survive migration. Disabled/incomplete/rejected/cooling sources are bypassed before requests; catalogue misses do not count as outages. Unavailable explicitly selected addons retain the existing YouTube substitution restriction.

**Verification:** Desktop source/Spotify focused suite: 68 passed. Ownership/drag/auth/catalogue follow-up: 24 passed. Android source suite: 210 passed. Migration, strict-match, enabled-state, credential identity, unavailable-source request count and fallback cases are included.

**Limitations/device verification:** Ordered Spotify playback and the proposed Spotify-exclusive mode remain BLOCKED by unestablished application permission. Spotifast authentication, protected grants and session/player lifecycle were inspected; restart-separated isolation is technically feasible but no published exception establishes permission for this multi-service product. The existing unofficial librespot PCM helper, authentication, identity checks and native queue path remain intact. Code 313 reports restriction without suggesting reconnection or attributing it to other services. Native opening refusal now stops without generic retry/substitution; midstream refusal retains its provider message and does not advance the queue. Two real engine regressions reproduced both defects and passed after the fix, in addition to 6 PCM protocol tests. Comprehensive UserViolation attribution and a durable enforcement latch remain incomplete because the pinned helper lacks a universal structured reason. See spotify-exclusive-feasibility.md for exact boundaries, provider references and the approved-integration alternative. The ordered Spotify row remains unavailable, without a fake adapter or toggle. Cached byte/rendition promises remain protected; settings apply to subsequent unresolved streams. Live authorized provider playback and source editing still need device acceptance.

**Exact changed files (relative to this source root):**

- `app/src/main/java/com/music/bitchord/data/sources/AddonSource.kt`
- `app/src/main/java/com/music/bitchord/data/sources/ModuleSource.kt`
- `app/src/main/java/com/music/bitchord/data/sources/SourceKind.kt`
- `app/src/main/java/com/music/bitchord/data/sources/SourceRegistry.kt`
- `app/src/main/java/com/music/bitchord/data/sources/SourceResolver.kt`
- `app/src/main/java/com/music/bitchord/data/sources/addon/AddonModels.kt`
- `app/src/main/java/com/music/bitchord/playback/AudioCache.kt`
- `app/src/main/java/com/music/bitchord/playback/PlaybackService.kt`
- `app/src/main/java/com/music/bitchord/ui/screens/SourcesScreen.kt`
- `app/src/test/java/com/music/bitchord/AddonSourceTest.kt`
- `app/src/test/java/com/music/bitchord/SourcePriorityTest.kt`
- `desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopAddon.kt`
- `desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopApp.kt`
- `desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopAudioEngine.kt`
- `desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopPersistence.kt`
- `desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopPlayback.kt`
- `desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopSourceConfig.kt`
- `desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopSourcePriorityUi.kt`
- `desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopSourceRegistry.kt`
- `desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopSourceStorage.kt`
- `desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopSourceWalk.kt`
- `desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopSpotifyPcm.kt`
- `desktopApp/src/test/kotlin/com/music/bitchord/desktop/DesktopAddonServerTest.kt`
- `desktopApp/src/test/kotlin/com/music/bitchord/desktop/DesktopAddonTest.kt`
- `desktopApp/src/test/kotlin/com/music/bitchord/desktop/DesktopAudioRecoveryTest.kt`
- `desktopApp/src/test/kotlin/com/music/bitchord/desktop/DesktopSourceDragTest.kt`
- `desktopApp/src/test/kotlin/com/music/bitchord/desktop/DesktopSourceOwnershipTest.kt`
- `desktopApp/src/test/kotlin/com/music/bitchord/desktop/DesktopSourceParityTest.kt`
- `desktopApp/src/test/kotlin/com/music/bitchord/desktop/DesktopSourcePriorityTest.kt`
- `desktopApp/src/test/kotlin/com/music/bitchord/desktop/DesktopSpotifyPcmTest.kt`
- `docs/verification/spotify-exclusive-feasibility.md`
- `docs/verification/spotify-only-mode-design.md`

Existing explicitly stored ordering is retained; new/missing built-ins use the JioSaavn, Spotify, YouTube Music default. An explicitly selected row owned by a disabled source is refused instead of substituted. An enabled but unavailable owner can use the tested strict fallback path, which excludes YouTube under an addon-owned row. This preserves the existing recording-ownership policy.

## 2. Desktop touchscreen scrolling

**Status: Partial.**

**Root cause/evidence:** The pinned Compose Desktop AWT mediator translates mouse/wheel input into Mouse pointer events; it does not deliver native Windows touchscreen contacts to foundation scrolling.

**Implemented behavior:** The Windows bridge registers only discovered Compose canvases, suppresses duplicate touch-promoted mouse events and sends genuine Touch contacts into the pinned scene on the AWT event thread. Existing Compose handlers supply vertical/horizontal drag, slop, momentum, retouch cancellation and child gesture ownership across audited scroll surfaces. Focus/capture loss, palm cancellation, DPI, modal/popup filtering and detach are handled. Queue/playlist handles now offer 44dp hit regions with 20dp glyphs; source handles/arrows have 48dp targets. Mouse wheel, mouse drag, text selection and keyboard routes remain in their existing handlers.

**Verification:** Pinned Compose/offscreen/AWT adapter smoke: 16 passed in touch-compose-awt.log. Separate Home pane tests exercise native Touch and wheel callbacks. Native window/touch/audio-route/audio-recovery fixtures: 4/4 passed on ARM64 and 4/4 on x64, including the existing stalled-output recovery check. The full desktop suite skips the real AWT-window test when headless; its separate non-headless run passed.

**Limitations/device verification:** Physical Surface digitizer acceptance is INCOMPLETE. Test Home, Library, Search, Settings, lyrics, queue, playlists and dialogs in both axes, fling/retouch, diagonal movement, cancellation, outside-dialog touches, scaling/rotation and drag handles. The reflective adapter intentionally depends on the pinned Compose implementation and disables native interception if discovery fails. Pen/multitouch beyond supported contact routing is not advertised as verified.

**Exact changed files (relative to this source root):**

- `desktopApp/native/window/CMakeLists.txt`
- `desktopApp/native/window/audio_route_test.cpp`
- `desktopApp/native/window/window_jni.cpp`
- `desktopApp/native/window/window_touch_test.cpp`
- `desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopApp.kt`
- `desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopPlaylistUi.kt`
- `desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopTouchAwtAdapter.kt`
- `desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopTouchInput.kt`
- `desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopTouchLayerPolicy.kt`
- `desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopTouchScene.kt`
- `desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopTouchStream.kt`
- `desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopWindowsFrame.kt`
- `desktopApp/src/test/kotlin/com/music/bitchord/desktop/DesktopTouchAwtAdapterTest.kt`
- `desktopApp/src/test/kotlin/com/music/bitchord/desktop/DesktopTouchLayerPolicyTest.kt`
- `desktopApp/src/test/kotlin/com/music/bitchord/desktop/DesktopTouchSceneTest.kt`
- `desktopApp/src/test/kotlin/com/music/bitchord/desktop/DesktopTouchStreamTest.kt`
- `desktopUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/player/PlayerQueue.kt`
- `docs/verification/surface-pro-11-scenarios.md`

## 3. Atmos pipeline and diagnostics

**Status: Partial.**

**Root cause/evidence:** Ordinary E-AC-3 had been classified as Atmos. An E-AC-3 core decoder does not establish JOC input or object rendering. The Windows sink sends shared-mode PCM, and preference changes did not reopen an already resolved/cached stream. The reported lack of audible difference has not been reproduced or attributed to one cause.

**Implemented behavior:** Source declaration, observed decoder codec/profile, core decoder availability and output evidence are separate. Plain DD+ stays DD+; an Atmos label does not invent a JOC codec. Unsupported DD+ is refused independently of the Atmos preference. Desktop FFmpeg decoder identity/profile and active native endpoint category are reported. Android diagnostics clear stale track/decoder evidence and publish observed input; its selected route is not sufficient to establish the sink route, so the new observed-output field stays Unknown. Stats occupy the existing playback-quality slot, with a scrollable details dialog instead of an artwork overlay. The preference explicitly applies on subsequent stream resolution; cached/playing streams can retain their format.

**Verification:** Classification/fallback/unsupported/stereo/JOC regressions are in DesktopAtmosDiagnosticsTest, DesktopStreamFormatTest, DesktopCodecParityTest and Android NerdStatsRegressionTest/AddonSourceTest. Direct pre-fix probes reproduced ordinary-EAC3 misclassification and the decoder-only availability error. Native endpoint mapping fixtures pass on ARM64/x64. Relevant tests also run in the full suites recorded below.

**Limitations/device verification:** Confirmed Atmos object rendering is NOT implemented or verified. JOC input without verified object output remains Unknown or Not confirmed, never Active/Enabled. Windows PCM output does not prove preservation of JOC objects. Exact Android sink-route observation remains incomplete; existing selected-device inference is deliberately not reported as actual output. Actual licensed JOC media, Android decoder/passthrough routes, Surface speakers, Bluetooth and USB/HDMI endpoints require device testing. No sound-quality improvement is claimed.

**Exact changed files (relative to this source root):**

- `app/src/main/java/com/music/bitchord/data/sources/AddonSource.kt`
- `app/src/main/java/com/music/bitchord/data/sources/DeviceCodecs.kt`
- `app/src/main/java/com/music/bitchord/data/sources/addon/AddonModels.kt`
- `app/src/main/java/com/music/bitchord/playback/PlaybackService.kt`
- `app/src/main/res/values/strings.xml`
- `app/src/test/java/com/music/bitchord/AddonSourceTest.kt`
- `app/src/test/java/com/music/bitchord/playback/audio/NerdStatsRegressionTest.kt`
- `desktopApp/native/window/CMakeLists.txt`
- `desktopApp/native/window/audio_route_test.cpp`
- `desktopApp/native/window/window_jni.cpp`
- `desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopAddon.kt`
- `desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopApp.kt`
- `desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopAudioDecoder.kt`
- `desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopAudioEngine.kt`
- `desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopAudioSink.kt`
- `desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopCodecs.kt`
- `desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopPlayback.kt`
- `desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopWindowsAudio.kt`
- `desktopApp/src/test/kotlin/com/music/bitchord/desktop/DesktopAddonServerTest.kt`
- `desktopApp/src/test/kotlin/com/music/bitchord/desktop/DesktopAddonTest.kt`
- `desktopApp/src/test/kotlin/com/music/bitchord/desktop/DesktopAtmosDiagnosticsTest.kt`
- `desktopApp/src/test/kotlin/com/music/bitchord/desktop/DesktopAudioRecoveryTest.kt`
- `desktopApp/src/test/kotlin/com/music/bitchord/desktop/DesktopCodecParityTest.kt`
- `desktopApp/src/test/kotlin/com/music/bitchord/desktop/DesktopStreamFormatTest.kt`
- `desktopUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/player/NowPlayingScreen.kt`
- `desktopUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/player/PlayerControls.kt`
- `docs/verification/surface-pro-11-scenarios.md`
- `shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/AudioDiagnostics.kt`
- `shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/NerdStats.kt`
- `shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/sources/StreamFormat.kt`
- `sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/player/NowPlayingScreen.kt`
- `sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/player/PlayerControls.kt`

**Pipeline audit:**

| Boundary | Observed implementation and limit |
| --- | --- |
| Stored preference | Android AppSettings and desktop persistence retain the existing preference. Its value is shown separately from stream and output evidence. |
| Source selection/request | Saved source order and availability govern resolution. Addon requests can carry an Atmos auto hint only when preference and decoder eligibility allow it; declared addon settings and explicit stereo-row selection still matter. A request hint does not establish the response format. |
| Resolved rendition | Addon/module response metadata is retained as source declaration. Unsupported DD+ and disallowed Atmos renditions are rejected so the resolver can use its tested strict fallback path. Plain E-AC-3 is not JOC. |
| Decoder/input | Android observed Media3 input/decoder events and desktop FFmpeg opened codec/profile provide the active evidence. Decoder existence and requested metadata do not establish object rendering. Native Spotify exposes float PCM with an unknown original compressed encoding. |
| Output | Desktop reports the active native endpoint category, with Unknown for insufficient evidence. Exact Android sink-route observation remains incomplete and is reported Unknown. Neither backend supplies verified object-output confirmation. |
| Change/recovery | Changing the preference affects subsequent stream resolution. Playing/cached renditions can retain their format; this is communicated rather than forcing a disruptive restart. Decoder failure/fallback regressions are retained; Spotify refusals stop without substitution. |

## 4. Android Classic-only player

**Status: Partial.**

**Root cause/evidence:** The Android host defaulted to Modern and forwarded the persisted layout choice. The Modern layout was the cause of selecting the small-cover design; existing Classic layout already measures responsive square artwork.

**Implemented behavior:** Android host emits Classic from construction; obsolete player_layout values are retired safely without reading their stored type, including values reintroduced by backup import. Android selector/configuration and six obsolete labels in 19 locales are removed. Other settings, queue pane and Canvas preferences are retained. Shared Modern code and Windows Classic/Modern selection remain. Classic artwork geometry was retained rather than replaced with an unverified sizing rewrite.

**Verification:** Two real Android host/AppSettings initialization/reload tests passed, covering fresh/Modern/Classic/invalid/missing/wrong-type preferences and unrelated settings. Eight pane/layout regression tests passed. All 19 modified locale XML files parsed successfully. Final Android build/tests are listed below.

**Limitations/device verification:** Selection/migration is implemented and behaviorally tested. Physical visual sizing, larger font scales, portrait/landscape, lyrics, queue, Canvas, rotation and media-session acceptance remain required; automated policy tests are not rendered device verification. APK assembly status is recorded independently from unit-test success below.

**Exact changed files (relative to this source root):**

- `app/src/main/java/com/music/bitchord/data/settings/AppSettings.kt`
- `app/src/main/java/com/music/bitchord/ui/player/AndroidPlayerHost.kt`
- `app/src/main/java/com/music/bitchord/ui/screens/PlayerLayoutPreference.kt`
- `app/src/main/java/com/music/bitchord/ui/screens/SettingsSheet.kt`
- `app/src/main/res/values-b+zh+Hant/strings.xml`
- `app/src/main/res/values-de/strings.xml`
- `app/src/main/res/values-es/strings.xml`
- `app/src/main/res/values-fr/strings.xml`
- `app/src/main/res/values-he/strings.xml`
- `app/src/main/res/values-hi/strings.xml`
- `app/src/main/res/values-id/strings.xml`
- `app/src/main/res/values-it/strings.xml`
- `app/src/main/res/values-ja/strings.xml`
- `app/src/main/res/values-pl/strings.xml`
- `app/src/main/res/values-pt/strings.xml`
- `app/src/main/res/values-ru/strings.xml`
- `app/src/main/res/values-th/strings.xml`
- `app/src/main/res/values-tr/strings.xml`
- `app/src/main/res/values-vi/strings.xml`
- `app/src/main/res/values-zh-rHK/strings.xml`
- `app/src/main/res/values-zh-rTW/strings.xml`
- `app/src/main/res/values-zh/strings.xml`
- `app/src/main/res/values/strings.xml`
- `app/src/test/java/com/music/bitchord/AndroidPlayerLayoutTest.kt`
- `docs/verification/surface-pro-11-scenarios.md`
- `sharedUi/src/jvmTest/kotlin/com/music/bitchord/ui/player/WidePlayerPaneTest.kt`

## 5. Desktop Home freshness and gestures

**Status: Complete.**

**Root cause/evidence:** Desktop Home fetched on initial/account load but had no monotonic freshness/resume revalidation; its pull refresh was disabled and its refreshing flag was hardcoded false. No Home response cache requiring audio-cache invalidation was found.

**Implemented behavior:** Home refresh deduplicates in-flight requests, uses a five-minute freshness interval and failure backoff, revalidates on Home return/focus/resume and periodically while Home is visible. Core/recent/supplement endpoints are fetched again and continuation state is replaced coherently. Existing content remains until replacement; loading/success/offline/error feedback is visible. Wheel overscroll at the top triggers after threshold plus 180ms idle; Touch requires release. A real pane test caught Material3 refreshing on cancellation; the callback now requires an observed Touch UP. Playback and decoder/audio caches are independent.

**Verification:** Eight controller/gesture policy tests passed. DesktopHomeFeedTest verifies endpoint revalidation and changing content/continuation with controlled fetches. Actual Compose Home pane tests verify wheel idle, one refresh, pull-release and cancellation using the Touch bridge. Both gesture and automatic-refresh paths run in full desktop tests.

**Limitations/device verification:** Live account-dependent recommendations can remain server-controlled. Supplement requests are optional and bounded; failed optional sections can be omitted while the core succeeds. Physical Surface gesture feel and real offline/reconnect presentation still need acceptance testing.

**Exact changed files (relative to this source root):**

- `desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopApp.kt`
- `desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopHomeFeed.kt`
- `desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopHomeRefresh.kt`
- `desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopHomeRefreshPane.kt`
- `desktopApp/src/test/kotlin/com/music/bitchord/desktop/DesktopHomeFeedTest.kt`
- `desktopApp/src/test/kotlin/com/music/bitchord/desktop/DesktopHomePaneTest.kt`
- `desktopApp/src/test/kotlin/com/music/bitchord/desktop/DesktopHomeRefreshTest.kt`
- `desktopUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/components/RefreshPullGesture.kt`
- `desktopUi/src/jvmTest/kotlin/com/music/bitchord/HomeRefreshGestureTest.kt`

## 6. Background FPS controls

**Status: Complete.**

**Root cause/evidence:** The existing visual mode did not expose a persisted background cadence. Review also reproduced a timer-plus-vsync pacing error: rounding the wait upward could miss the target pulse and accumulate display rounding drift.

**Implemented behavior:** Performance mode offers Dynamic (default), 15, 30 and 60 FPS using existing selection chips. Dynamic accounts for playback, window visibility and native battery/battery-saver state. Only background animation effects receive the pacing clock. Monotonic deadlines, suspended waits and display pulses pace frames without busy loops; hidden/inactive renderer effects stop or suspend. Color samples are read during draw instead of composing every animation tick. Lyric, audio, AutoMix and input clocks are unchanged.

**Verification:** Cadence/persistence pure suite: 4 passed. BackgroundFrameClockTest: 4 passed, including a behavioral red/green for simulated 60/120/144Hz displays, hidden suspension/cancellation and sibling lyric/input clocks. Final platform test runs are recorded below.

**Limitations/device verification:** The selector is a renderer cadence, not a guarantee of measured GPU percentage or exact physical display timing. Validate Surface 60/120Hz, battery saver, minimize/restore and actual animation quality; GPU/battery improvements have not been benchmarked.

**Exact changed files (relative to this source root):**

- `desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopApp.kt`
- `desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopBackgroundRateSetting.kt`
- `desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopPerformance.kt`
- `desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopPerformanceDiagnostics.kt`
- `desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopPlayerHost.kt`
- `desktopApp/src/test/kotlin/com/music/bitchord/desktop/DesktopBackgroundRateSettingTest.kt`
- `desktopUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/player/ArtworkMeshBackdrop.kt`
- `desktopUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/player/BackgroundFrameClock.kt`
- `desktopUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/player/BackgroundFrameRate.kt`
- `desktopUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/player/MeshGradient.kt`
- `desktopUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/player/PlayerHost.kt`
- `desktopUi/src/jvmTest/kotlin/com/music/bitchord/BackgroundFrameClockTest.kt`
- `desktopUi/src/jvmTest/kotlin/com/music/bitchord/BackgroundFrameRateTest.kt`
- `docs/verification/surface-pro-11-scenarios.md`

## 7. Optional performance instrumentation

**Status: Partial.**

**Root cause/evidence:** The nine-hour GPU/smoothness observations do not establish a root cause. Background color animation reads could invalidate composition; the change moves those reads into draw. Model initialization, shader warm-up, image/texture work and GC remain distinct hypotheses.

**Implemented behavior:** An opt-in bounded collector records background frame gaps/CPU draw submission, player/lyrics composition counts, lyric frame counts, image read/preparation and analysis durations. Startup (first 120s after enable/reset) and warmed samples remain separate; buffers retain at most 256 samples per stage/phase. Settings expose reset/update/selectable/copyable diagnostics, elapsed recording duration and JVM heap/lifetime-GC snapshots. No track identities, URLs or images are retained. Disabled collection avoids clock reads/storage; it does not create an animation timer.

**Verification:** Three collector tests passed: disabled no-op, bounded storage/reset/phase separation and frame duplicate/gap handling. Full UI/desktop compilation/tests validate hooks. Synthetic scheduling/frame tests are regression evidence, not GPU benchmarks.

**Limitations/device verification:** A live startup-versus-warmed GPU/driver/texture/shader profile and a long Surface session remain INCOMPLETE. CPU submission timing is not GPU execution time; background gaps include intentional FPS pacing and are not dropped-frame counts. Shader compilation and GPU texture allocation require external graphics profiling. No nine-hour or sound-quality benchmark is invented.

**Exact changed files (relative to this source root):**

- `desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopApp.kt`
- `desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopPerformance.kt`
- `desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopPerformanceDiagnostics.kt`
- `desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopTrackAnalyzer.kt`
- `desktopUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/player/ArtworkMeshBackdrop.kt`
- `desktopUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/player/BackgroundFrameClock.kt`
- `desktopUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/player/MeshGradient.kt`
- `desktopUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/player/NowPlayingScreen.kt`
- `desktopUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/player/PerformanceDraw.kt`
- `desktopUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/player/PerformanceMetrics.kt`
- `desktopUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/player/PlayerLyrics.kt`
- `desktopUi/src/jvmTest/kotlin/com/music/bitchord/BackgroundFrameClockTest.kt`
- `desktopUi/src/jvmTest/kotlin/com/music/bitchord/PerformanceMetricsTest.kt`
- `docs/verification/surface-pro-11-scenarios.md`

## 8. AutoMix preparation and skip ownership

**Status: Complete.**

**Root cause/evidence:** Each analysis request could add an executor runnable, retaining obsolete queued tracks. MixNow carried only fade duration and could be accepted twice before the audio pump consumed it. A later x64 regression exposed ready results being observable before the restart cache write settled. Preparation begins at next-track selection; the 15-second skip value is fade duration, while a separate addon lead time concerns stream opening.

**Implemented behavior:** One bounded drain retains current/upcoming priority work and promptly cancels obsolete queued/source/decoder work. Generation changes cancel same-ID obsolete passes; no stale failed cooldown/result is published. Current analysis starts promptly and the existing full decode/model passes and configured inference thread policy remain. Preparation/Upcoming/ReplaceUpcoming/MixNow tickets include generation, selection serial and current/next IDs; one manual reservation owns a handoff and ABA reselection cannot revive it. Cache save now precedes ready publication, outside the cancellation lock so disk IO cannot hold controls. Playback controls keep their separate thread/queue and fallback remains.

**Verification:** Two deterministic pre-change failures reproduced executor backlog and duplicate pending handoff. Preparation policy tests cover 10,000 replacements, noncooperative slow analysis, no overlap, ABA and cancellation/publication. Five scheduling tests cover gated audio pump, rapid selection, stalled real HTTP/FFmpeg open, slow source and same-ID reload. Existing recovery/position/lyric/crossfade/tempo/native click-track suites pass in the full desktop suite. A fixture returned fixed 4-byte output after format negotiation; corrected to negotiated bytes without weakening handoff assertions. One x64 run timed out during native HTTP setup before cancellation began; setup now has a bounded 20-second readiness wait with status diagnostics, while the two-second cancellation assertion remains unchanged. Final rerun evidence follows.

**Limitations/device verification:** A native inference call cannot safely be forcibly terminated mid-call; it remains the only analysis worker, its obsolete result is rejected and subsequent work waits for it to return. No hard analysis deadline or invented 15-second budget is imposed. Slower physical CPUs and audible transitions still merit device acceptance, but no feature is disabled or deliberately reduced.

**Exact changed files (relative to this source root):**

- `desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopAudioEngine.kt`
- `desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopPreparationPolicy.kt`
- `desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopTrackAnalyzer.kt`
- `desktopApp/src/test/kotlin/com/music/bitchord/desktop/DesktopAudioRecoveryTest.kt`
- `desktopApp/src/test/kotlin/com/music/bitchord/desktop/DesktopAutomixSchedulingTest.kt`
- `desktopApp/src/test/kotlin/com/music/bitchord/desktop/DesktopPreparationPolicyTest.kt`
- `docs/verification/surface-pro-11-scenarios.md`

## Final verification runs

Latest completed checks: Android 1,128 tests with zero failures/errors and one existing live-network skip, plus successful debug APK assembly; Windows x64 556 desktop tests with zero failures/errors and one headless skip, plus 71 UI tests with no skips/failures/errors; final ARM64 playback/refusal/format subset 37 tests with no failures/errors/skips. Earlier full ARM64 and focused runs are recorded below rather than summed as unique tests.

The x64 invocation completed without interruption in 20m 3s. Process ancestry, two daemon/compiler thread snapshots, compiler logs and an actual test-worker stack confirmed compilation followed by localization XML tests. This was not a permanently stalled configuration. The compiler shut down normally. Memory pressure was observed; SSD thermal throttling, deadlock, heap failure and the isolated cost of x64 emulation were not established. No heap/dependency/build-configuration change or retry was used. Detailed read-only diagnostics remain in the local x64-runtime-diagnostics/diagnosis.md review artifact. ARM64-hosted Gradle with an explicit x64 test launcher is technically feasible but was not substituted or benchmarked.

- `arm64-final`: {"desktopApp":{"tests":552,"failures":0,"errors":0,"skipped":1,"suites":106},"desktopUi":{"tests":70,"failures":0,"errors":0,"skipped":0,"suites":18},"shared":{"tests":101,"failures":0,"errors":0,"skipped":0,"suites":19},"sharedUi":{"tests":60,"failures":0,"errors":0,"skipped":0,"suites":14}}
- `arm64-current`: {"desktopApp":{"tests":87,"failures":0,"errors":0,"skipped":1,"suites":17},"desktopUi":{"tests":71,"failures":0,"errors":0,"skipped":0,"suites":18}}
- `arm64-current` command result: exit 0. A passing unit-test summary does not imply successful APK assembly.
- `arm64-spotify-final`: {"desktopApp":{"tests":37,"failures":0,"errors":0,"skipped":0,"suites":3}}
- `arm64-spotify-final` command result: exit 0. A passing unit-test summary does not imply successful APK assembly.
- `x64-native-current`: {"desktopApp":{"tests":54,"failures":1,"errors":0,"skipped":1,"suites":9}}
- `x64-native-current` command result: exit 1. A passing unit-test summary does not imply successful APK assembly.
- `x64-final-verified` command result: exit 1073807364. A passing unit-test summary does not imply successful APK assembly.
- `x64-final-rerun`: {"desktopApp":{"tests":556,"failures":0,"errors":0,"skipped":1,"suites":106},"desktopUi":{"tests":71,"failures":0,"errors":0,"skipped":0,"suites":18}}
- `x64-final-rerun` command result: exit 0. A passing unit-test summary does not imply successful APK assembly.
- `android-complete`: {"app":{"tests":1128,"failures":0,"errors":0,"skipped":1,"suites":102}}
- `android-complete` command result: exit 1. A passing unit-test summary does not imply successful APK assembly.
- `android-rebuilt-transforms`: {"app":{"tests":1128,"failures":1,"errors":0,"skipped":1,"suites":102}}
- `android-rebuilt-transforms` command result: exit 1. A passing unit-test summary does not imply successful APK assembly.
- `android-final-isolated` command result: exit 3221226091. A passing unit-test summary does not imply successful APK assembly.
- `android-final-isolated-retry`: {"app":{"tests":1128,"failures":0,"errors":0,"skipped":1,"suites":102}}
- `android-final-isolated-retry` command result: exit 0. A passing unit-test summary does not imply successful APK assembly.
- Native MSVC fixtures: `native-all-arm64.log` and `native-all-x64.log`, 4/4 tests passed on each architecture. x64 ran under Windows emulation on the ARM host.
- `spotify-refusal-red.log` reproduced the misleading application-ban reconnect advice; `spotify-refusal-green.log` passed all 6 PCM tests.
- `spotify-refusal-protocol-green.log` passed all 6 PCM tests with the application-ban case fed through an actual bounded native protocol error frame.
- `spotify-engine-refusal-red.log` reproduced two real engine failures: a second source/open attempt and lost midstream provider diagnostic. `spotify-engine-refusal-green.log` passed both regressions against the patched production engine using the captured x64 runtime classpath and synthetic grants/PCM sources.
- `spotify-engine-refusal-final.log` passed both refusal regressions after cancellation of pending native playback preparation was added.
- These native-refusal tests use synthetic grants and do not verify live Spotify authorization or account entitlement. DesktopStreamFormatTest also covers decoded float PCM with an unknown original codec: it must not be labelled lossless, hi-res or Atmos.
- Android uses the existing modernization wrapper/candidate selected by the repository for the installed SDK. No Gradle, Java target, AGP, Kotlin or dependency version file was changed for these tasks.
- Existing live-network Genius test remains opt-in and was skipped; no check or test was removed or disabled to obtain the recorded results.
- `android-complete` compiled and passed its unit tests but APK dex merging failed on cached former C: checkout paths versus the current E: root. The two affected generated transform workspaces were preserved outside the source tree before retrying; no source/index reset or dependency change was used. The final serialized retry passed both unit tests and APK assembly, verifying that the old-path dex failure no longer occurs. APK SHA-256 hashes, sizes and packaged ABIs are retained in android-final-apk.json; installation/device execution was not performed.
- `android-rebuilt-transforms` passed the feature regressions but failed the unchanged QueueShuffleTierTest absolute 50ms wall-clock benchmark. It ran concurrently with an isolated compiler; CPU contention is a hypothesis, not a measured root cause. The benchmark was not changed, removed or disabled. A serialized final retry is recorded separately.
- Packaging verifier/source-preparation Python suite: 13 tests passed using `python -B -m unittest discover -s desktopApp/packaging -p test_*.py`.
- Earlier x64 runs exposed the analysis cache publication race and a native HTTP setup timeout; those failures remain in the evidence directory and are not represented as passing runs.
- `x64-native-current` passed 52 tests, skipped 1 headless test and failed 1 fixture readiness timeout before its output-error assertions. Its incoming deck completed setup in 3.346 seconds, beyond the old 3-second setup wait. Setup now waits up to 20 seconds; actual error/recovery and single-attempt assertions are unchanged. This does not measure or claim a playback performance improvement.
- Production ARM64 and x64 window DLL PE machine types and touch/audio-route exports were checked in `production-native-architecture.json`. Windows x64 tests execute under emulation on the ARM host; no physical x64 acceptance or final Windows installer/portable package is claimed. The existing x64 WebView native task is skipped in this verification configuration, so its runtime is not covered by the native-bridge checks.
- `x64-final-verified` ended before completing verification with Windows process status 0x40010004; `android-final-isolated` did not start Gradle and returned 0xc000026b. Both JDK version probes subsequently succeeded. These attempts are incomplete, not feature passes or an established source defect; retries are listed independently.

## Repository and publication

- Authoritative tree: `E:/Development/BitChord-main/.work/upstream-integration-20261018`; eventual repository paths start at that directory.
- Existing branch: `integration/upstream-untouched-files`; preserved HEAD: `efc2079d18847efe06798ecca4878fd3731d6e09`.
- Proposed GitHub destination: `SiriusNovyx/BitChord-ARM64`. Proposed new branch: `feature/playback-input-reliability`. Neither is approved for publication.
- No implementation commit, new publication branch, upload, push, PR creation/update, merge or publishing was performed. PR #1 is already merged; no replacement PR URL exists.
- All historical and implementation changes remain local/unpublished. The initial index, staged/unstaged patches, HEAD bundle and working-source ZIP are retained outside the source tree.
- The raw index metadata hash changed during the session; canonical staged entries and the complete staged binary diff remain byte-for-byte identical to the captured baseline. The current and original index copies are both preserved; no reset/restoration/staging was performed.
- Proposed upload manifest and exact exclusions are separate review artifacts under `implementation-20261022`. They include historical unpublished source and require review before any future staging/commit. Six paths present in the separate clone snapshot are absent from the authoritative tree and were already absent in the pre-implementation ZIP: two Fanart banners, old ReorderPlaylistSheet.kt, LocalMusicScreen.kt and two widget-heart drawables. They are listed as candidate base deletions for review, not approved deletions or implementation removals. ReorderPlaylistPage.kt remains present.
- Exclude internal work/agent/Git metadata, module outputs, caches, credentials/session stores/logs and compiled runtime outputs. Keep legitimate source packages named `build`, wrapper bootstrap JARs, project assets, native sources and tests. Do not exclude source merely because the authoritative directory physically resides inside `.work`.
- The three existing Android `src/main/jniLibs/*/libffmpegJNI.so` dependencies are already tracked at identical blob IDs in the separate clone snapshot; they are preserved project inputs, not newly generated uploads. Required ONNX model assets and both wrapper bootstrap JARs are also retained. Internal `.claude/launch.json` is excluded from the selected upload manifest.
- Pattern scanning is a limited credential check, not proof of absence of every secret. No publication is authorized until destination, branch and exact selected files are approved.

## Surface acceptance and profiling procedure

1. Keep the same tracks, output route, window size, display refresh rate, power mode and visual settings. Record no-lyrics, synced/full-lyrics and AutoMix cases separately.
2. Enable measurements before startup comparison, copy the first-120-second results, then collect warmed samples separately. Record GPU/frame traces with a graphics profiler and heap/GC observations; do not treat CPU draw-submission duration as GPU time.
3. Test touch vertical/horizontal scrolling on each audited screen, fling then retouch, focus/capture loss, dialog boundaries, scaling/rotation, source/queue/playlist handles, mouse wheel/selection/hover and keyboard controls.
4. Check small/large Android Classic portrait/landscape at larger font sizes, artwork square/proportions, lyric/queue gestures, Canvas, rotation and media-session controls.
5. Use authorized stereo, DD+ and actual JOC samples. Compare preference, resolved input, decoder and endpoint route. Require backend object-output evidence before calling Atmos playback confirmed; do not infer it from a decoder or audible difference.

## Provider references

- [Spotify Developer Policy](https://developer.spotify.com/policy): restrictions on integrated other-service streaming and segueing/mixing/overlap.
- [Spotify Web Playback SDK](https://developer.spotify.com/documentation/web-playback-sdk): browser playback SDK, not this engine’s native PCM contract.
- [librespot](https://github.com/librespot-org/librespot): unofficial alternative client, preserved existing helper.

See [Spotify-exclusive feasibility](spotify-exclusive-feasibility.md) for the reference comparison and unresolved permission/enforcement requirements, and [Surface scenarios](surface-pro-11-scenarios.md) for the repeatable physical profiling and acceptance matrix.

## Spotify design follow-up: 23 October 2026

The [conditional implementation-ready design](spotify-only-mode-design.md) clarifies that Spotify-only mode is technically feasible and held for application permission, not technically impossible. It specifies permission gates, proposed contracts, startup/restart ownership, queue/session isolation, migration, refusal handling, exact future file boundaries and a behavioral test matrix. The new behavior remains proposed, not implemented or newly verified.

| Follow-up request | Disposition |
| --- | --- |
| Preserve native helper/authentication/queue and respect restrictions | Existing implementation and refusal fixes retained; no playback source changes. |
| Implementation-ready isolated-mode design | Provided, conditional on the explicit permission and access gates. |
| Standalone versus restart-separated application | Compared; neither is claimed compliant. Standalone reduces coupling but does not establish access/client/product permission. |
| Official Spotify application fallback | Explicit resource-handoff UX and technical contract provided; no Spotify audio would pass through BitChord. Not implemented or launch-tested. |
| Code 313 / unspecified UserViolation attribution | Neither proves multi-source use. Only an explicit provider reason with provenance can establish that attribution. |
| Spotify priority row | Retained as unavailable; an exclusive engine is not a resolver adapter. |

The user accepts the need for physical Surface touchscreen, Atmos output and GPU testing. That acceptance does not constitute device-test results or upgrade the reported verification status. Other tested workstreams are retained. Previously delivered binaries are unchanged by this documentation follow-up. GitHub publication remains separately gated and unapproved; no commit, branch, upload or PR update is performed.
