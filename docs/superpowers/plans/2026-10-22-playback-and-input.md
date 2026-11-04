# Playback and input implementation plan

> **For agentic workers:** Use test-driven development and scoped independent implementation/review. Do not commit, stage, publish or create another worktree. The user selected this existing authoritative tree.

**Goal:** Implement the eight approved local workstreams with behavioral evidence and truthful hardware/provider limits.

**Architecture:** Keep desktop in desktopApp/desktopUi and Android in app/sharedUi. Preserve existing source matching, playback engine ownership, protected preferences and native adapters. Apply scheduling at the specific background/analysis boundary, not through a global UI or audio clock.

**Tech stack:** Existing Kotlin/Compose Multiplatform, Android Media3, FFmpeg, Windows JNI/WASAPI. Keep all existing build and dependency versions.

**Spec:** The user requests and approved read-only audit/checklist of 22 October 2026 in this chat. The approval is local only.

## Global constraints

- Authoritative source: E:/Development/BitChord-main/.work/upstream-integration-20261018.
- Preserve HEAD, staging index and all historical unpublished work. No git add/reset/checkout/commit/push or GitHub mutation.
- Recovery baseline: E:/Development/BitChord-main/.work/implementation-20261022/baseline; verified bundle, original index, separate diffs and source ZIP.
- No unrelated Gradle, Java, AGP, Kotlin or dependency version changes. Do not remove tests or disable checks.
- Preserve Android, Windows x64 and Windows ARM64. Preserve the Windows Modern/Classic selector.
- Preserve credentials, configured addons, source enabled states, JioSaavn consent, matching strictness and functioning native Spotify playback.
- Spotify's unofficial native helper is not an official SDK. Do not add DRM bypasses, borrowed identities, unsupported claims or fake audio adapters.
- Touch and object-audio hardware acceptance cannot be established by compilation or policy tests.
- Keep antislop review in the user-selected after mode; retain established UI style.
- Build/test execution uses the serialized runner under implementation-20261022; no concurrent Gradle invocations or unbounded logs.

## 1. Sources and Spotify

Files: app data/sources/{SourceKind,SourceRegistry,SourceResolver}.kt; app ui/screens/SourcesScreen.kt; desktop {DesktopSourceConfig,DesktopSourceRegistry,DesktopPersistence}.kt; new focused source policy tests. The coordinator integrates desktop source UI in DesktopApp.kt.

- [ ] Add migration and resolution-order tests against existing saved configurations, including disabled YouTube/JioSaavn, legacy modules, credentials in URLs, duplicate/missing IDs and unknown entries.
- [ ] Run tests red; preserve config IDs and opaque fields when normalizing built-ins.
- [ ] Represent persisted custom/built-in groups with default built-in order JioSaavn, Spotify, YouTube; dedicated handles must alter the actual playback/fallback walk.
- [ ] Gate disabled, unauthenticated, unsupported and cooling-down unavailable sources before resolution requests; do not classify a normal catalogue miss as a provider outage.
- [ ] Investigate existing DesktopSpotifyStreams/Runtime/Playback/Session and exact matching. Preserve this working path. Record the official/unofficial and provider limitations; if priority integration is not compliant and verifiable, leave Spotify unavailable in the ordered group and report that integration incomplete.
- [ ] Test strict identity and fallback without real service credentials. Run relevant existing Android/desktop source and Spotify routing suites.

## 2. Desktop touchscreen input

Files: desktop native/window/window_jni.cpp and existing window tests; DesktopWindowsFrame.kt; new DesktopTouchInput.kt and pure gesture policy tests; desktopUi scroll surfaces and registered touch-target modifier.

- [ ] Add deterministic touch-versus-mouse, axis slop, velocity/fling, cancellation, bounds, DPI and exclusive reorder/control ownership cases.
- [ ] Observe actual pinned Compose/AWT input APIs and add a Windows touch bridge only at their missing touch boundary; preserve real mouse events and hover.
- [ ] Connect the bridge to Home/Library/Search/Settings/lyrics/queue/playlists/dialog scroll states in both axes. Own one gesture target until release/cancellation and stop momentum when disposed or touched again.
- [ ] Audit target size and nested click/drag ownership. Never turn ordinary mouse drags into scrolling or lose text selection.
- [ ] Run native ARM64/x64 fixtures and desktop policies. Document physical Surface fling/capture/cancellation/rotation/DPI/manual controls checklist separately.

## 3. Atmos evidence and diagnostics

Files: shared NerdStats/StreamFormat; desktop DesktopPlayback/Codecs/Addon/AudioDecoder; Android DeviceCodecs/addon format parsing; both UI PlayerControls; regression tests. Coordinator owns engine/settings wiring in DesktopAudioEngine/DesktopApp and Android PlaybackService only if needed.

- [ ] Add red tests: ordinary E-AC-3 is DD+, explicit JOC declaration differs from observed input and confirmed output; stereo/unknown/unsupported/fallback clear stale evidence.
- [ ] Separate selection capability from verified output. Never infer object rendering from an E-AC-3 decoder or addon label.
- [ ] Carry observed codec/profile, decoder and output route into compact diagnostics, keeping details expandable and out of artwork/control regions.
- [ ] Trace stored preference through query/response/cache/open/recovery; safely re-resolve active playback or clearly explain subsequent-stream semantics.
- [ ] Run source, decoder, diagnostics, cache and fallback tests. Physical output and listening evidence remain explicitly unverified.

## 4. Android classic only

Files: AppSettings.kt, AndroidPlayerHost.kt, SettingsSheet.kt, PlayerLayoutPreference.kt, sharedUi LandscapePlayer/NowPlayingScreen and focused tests. Do not edit desktopUi/desktop selector or shared PlayerLayout parsing default used by desktop.

- [ ] Add red migration/default/layout constraints tests.
- [ ] Force the Android host to Classic, safely consume/migrate old Android player_layout values and remove Android-only selection UI/configuration.
- [ ] Preserve responsive square classic artwork with controls visible on small/large portrait/landscape screens; do not remove TV/Wear/shared consumers.
- [ ] Test migration, sizing and pane behavior; compile Android and run unit tests; document visual/device checks pending.

## 5. Home freshness and refresh interaction

Files: new desktop Home refresh coordinator/policy; DesktopApp.kt; desktopUi HomeScreen/Common; Android MainViewModel/Home lifecycle where stale; virtual-clock tests.

- [ ] Add red freshness, identity, failure-backoff, in-flight deduplication and wheel/touch threshold-release tests.
- [ ] Use monotonic freshness with account identity; revalidate on Home return/resume, preserving successful content. Refresh core/recent/supplement endpoints and continuation coherently.
- [ ] Render a compact top indicator. Wheel overscroll triggers after threshold plus idle; touch triggers on release; cancellation must not refresh. Prevent concurrent requests and preserve music playback.
- [ ] Publish loading/success/offline/error feedback and test automatic/manual behavior with controlled fetches.

## 6. Background FPS

Files: new BackgroundFrameRate/FramePacing policy; DesktopPerformance/VisualPolicy/PlayerHost; desktopUi PlayerHost/MeshGradient/ArtworkMeshBackdrop; DesktopApp setting; tests.

- [ ] Add red persistence/fallback/cadence/hidden/power/activity tests.
- [ ] Add Dynamic (default), 15, 30 and 60 FPS using the existing chips only in Performance mode.
- [ ] Supply a frame clock only inside background renderer composition. Use suspension/display frames, no busy loop; pause hidden renderers and adapt Dynamic to power/activity.
- [ ] Verify lyrics/input/audio/AutoMix clocks are unaffected and frame timers cancel with composition.

## 7. Optional performance instrumentation

Files: new bounded desktop metrics collector and diagnostics view; selected desktopUi frame/recomposition/image preparation boundaries; tests.

- [ ] Add red tests for disabled no-op, bounded retention, reset, startup/warm phase separation and measured frame intervals.
- [ ] Sample recomposition/draw counts, frame timing, image preparation, analysis timing and JVM memory/GC only when enabled; redact track IDs/URLs/tokens.
- [ ] Keep bounded aggregated counters and a user-enabled detailed view/export. Do not fabricate GPU/shader compilation measurements the API cannot observe.
- [ ] Run instrumentation regression and collect reproducible synthetic startup/warm timing, reporting actual workload and limits. Physical Surface GPU/driver/battery comparisons remain separate.

## 8. AutoMix scheduling and ownership

Files: DesktopTrackAnalyzer.kt, DesktopAudioEngine.kt and new preparation ownership policy; deterministic scheduler tests and existing recovery/automix/lyric clock tests.

- [ ] Before scheduling changes, reproduce obsolete analysis queue work and stale transition opportunities using latches/manual schedulers, slow source/analysis, stalled decoding and rapid skip requests.
- [ ] Treat the existing 15 seconds as fade duration. Derive preparation horizon from next-track selection and transition arming, and prioritize current/upcoming work with cancellation of obsolete queued/running work.
- [ ] Bind MixNow/preparation results to generation plus current/next identities; no stale same-ID/ABA handoff. Bound simultaneous native analysis and avoid blocking playback controls.
- [ ] Use the available horizon without reducing analysis features or intentionally waiting idle; retain fallback when a deadline cannot be met.
- [ ] Run delayed/contended/stalled scheduling tests, audio recovery/position/lyrics tests and real synthetic decoder fixtures.

## Verification and delivery

- [ ] Run focused tests first and keep expected red and actual green evidence under implementation-20261022.
- [ ] Run shared, desktopUi/sharedUi, desktop ARM64/x64 and Android checks using existing toolchains; report prerequisite failures separately.
- [ ] Review each task's delta against baseline ZIP, then whole implementation. No comparison against HEAD alone may attribute historical edits to this task.
- [ ] Verify original index byte-for-byte and historical files preserved; produce per-workstream status/files/behavior/tests/limits/hardware checklist.
- [ ] Generate proposed repository-relative upload manifest and secret/generated-file exclusions. Destination/branch/files remain pending user approval. No upload or PR mutation.
