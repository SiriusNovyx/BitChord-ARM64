# Dual Player Layout Implementation Plan

> **For agentic workers:** Execute inline using executing-plans; use independent source review after implementation. Do not commit or publish.

**Goal:** Offer persistent, immediately switchable Modern and Classic wide players on the current playback infrastructure.

**Architecture:** One current NowPlayingScreen state owner supplies both arrangements. Shared typed preference, platform persistence adapters and existing Appearance controls select only the presentation.

**Tech Stack:** Kotlin Multiplatform, existing Compose layouts/controls, Android SharedPreferences, desktop portable-aware persistence. No new dependency.

**Spec:** `docs/superpowers/specs/2026-10-20-dual-player-design.md`.

## Constraints

- Modern defaults on fresh/invalid preferences; stable keys `modern` and `classic`.
- Keep current engine, recovery, source, queue, lyrics/translation, audio quality, output, AutoMix and media/Discord infrastructure.
- Restore Classic from recorded historical source; preserve current bug fixes when adapting slots.
- Narrow Android portrait remains the current responsive player, as explicitly chosen by the user.
- Preserve native ARM64/x64 and application bytecode targets; no publication.

## 1. Preference and persistence

- [x] Add `shared/.../data/settings/PlayerLayoutTest.kt`: missing/unknown keys default Modern, known keys round-trip without using ordinal positions.
- [x] Add desktop persistence regression next to DesktopLyricsDisplaySettingTest; construct a new owner after switching and verify stored selection, restoring the original test preference in finally.
- [x] Run focused suites and preserve failing missing-API output before production implementation.
- [x] Add PlayerLayout enum and the desktop setting adapter; wire Android AppSettings initialization/setter and both PlayerSettingsSource contracts/host adapters. No audio/controller reference is allowed in the setting.
- [x] Run shared and desktop focused tests; inspect Android adapter initialization ordering.

## 2. Existing player arrangements and settings controls

- [x] Reuse historical LandscapePlayerLayout for Classic in desktopUi, retaining current safe insets, artwork callbacks and anchored menus. Keep Modern's arrangement unchanged.
- [x] Add the Modern arrangement to sharedUi using current artwork/main/lyrics/queue slots. Keep the existing Classic arrangement available.
- [x] Collect layout at NowPlayingScreen scope; select arrangement without keying or recreating the host, song state or engine. Restore Classic caption/volume/lyric strip and bottom actions; retain modern queue/translation controls.
- [x] Add localized Player Layout labels/description/options in the existing catalogs and native Appearance settings using explicit radio choices. Both choices remain visible; saving immediately publishes the flow.
- [x] Compile/test sharedUi, desktopUi, desktopApp and Android; correct actual interface/slot failures and run regressions once source is stable.

Source review found Android Modern implicit lyrics were missing the existing screen-on behavior; the displayed-pane policy now drives rendering and screen-on without changing saved pane state. Four pane policy regressions pass in each UI module. First full run: shared101, sharedUi58, desktopUi58, desktop483, Android1113 (one skipped), zero failures/errors. Source review found no remaining concrete issue. Runtime and exact-package acceptance remain open below.

## 3. Runtime, packages and report

- [x] Rebuild an isolated ARM64 candidate and Dev APK with the new code. Retain all previous archives.
- [x] Test Modern and Classic with the same song/pane/window; compare playback state across switches, then restart to verify saved layout. Exercise queue/lyrics/output/volume, media/Discord controls and current recovery behavior.
- [x] Run native x64/Java21 and ARM64/Java21/25 tests, Android tests/builds and appropriate shared tests. Preserve exact XML/package architecture evidence.
- [x] Independent source review, resolve findings, update the integration difference ledger and report changed files, passing/failing/untested gates. No commit/push/release.

Candidate3 desktop suites pass493 tests each on ARM64 Java21/25 and emulated x64 Java21; the original Gradle8.11.1/Java21 baseline also passes shared101, both UI58 suites and desktop493. Candidate2's unchanged phone code passes1113 unit tests (one existing skip), with Dev phone/TV/Wear packages and nine-APK checks complete. Corrected phone labels were visually verified after unlock/reconnection. Manual candidate3 Discord pause/resume and quit clearing passed; exact Java21 fallback sound/controls also passed. The user additionally checked both layouts' queue, lyrics and output controls, and confirmed switching preserved song, position and volume. See the validation record for exact package hashes, earlier restart/Bluetooth checks and platform limits.

User-authorized storage cleanup subsequently removed only hash-verified duplicate runtime/library files from stopped older extractions. Their ZIPs, profiles, unique historical JARs and test evidence remain; restore exact allowlisted members before rerunning those old installations. Current candidate3 extractions remain complete. Cleanup evidence is in the original workspace's `docs/cleanup-20261021/REPORT.md`.
