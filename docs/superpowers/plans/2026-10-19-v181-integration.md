# Comprehensive v1.8.1 Integration Implementation Plan

> **For agentic workers:** Execute inline using the executing-plans workflow, with source review and validation between batches. Do not commit or publish.

**Goal:** Integrate maximum practical official v1.8.1 functionality with the fork's working native desktop and useful local capabilities.

**Architecture:** Three-way merge from the verified common ancestor, followed by semantic adaptation. Official v1.8.1 is the target for surviving modules; its last pre-removal native desktop revision supplies additional `desktopApp`/`desktopUi` changes needed to preserve the native product.

**Tech Stack:** Kotlin Multiplatform, Compose, Android/TV/Wear, native Windows ARM64/x64, FFmpeg/JNI, Go and web TypeScript. Retain the opt-in Java25/Gradle9.5.1/AGP9.3.3 path and baseline compatibility.

**Spec:** `docs/superpowers/specs/2026-10-19-v181-integration-design.md`.

## Global constraints

- Official target is exactly `7d956f211b8a46a87184fd3ff87432b51a111dfa`.
- Overlap is not a skip rule; all differences need investigation and an outcome.
- Preserve native ARM64/x64, current audio/Spotify, translation/font, power policy, portable isolation and backend reliability capabilities, while allowing their implementations to change.
- Keep delivered baseline ZIPs and all user data/credentials untouched.
- No commit, push, release or publication without explicit user approval.
- A module build or file import is not proof of feature parity or runtime behavior.

## 1. Freeze and compare the entire source

- [x] Verify the remote tag and common ancestor.
- [x] Preserve all 1,342 current tracked source files, raw hashes and staged/unstaged patches.
- [x] Inventory every ancestor/local/upstream path and generate diff3 candidates for all overlaps.
- [x] Investigate native desktop deletion and preserve the last native source as an additional reference.
- [x] Prepare a native desktop three-way comparison, mapping moved sharedUi files to desktopUi through their source paths.
- [ ] Establish a complete feature/conflict ledger; every item remains open until source and validation support its outcome.

Evidence scripts: `.work/audit_v181_integration.py`, `.work/prepare_v181_comparisons.py`, `.work/prepare_v181_native_reference.py` in the original workspace. Their outputs are the exact path lists for subsequent batches.

## 2. Integrate surviving modules and build graph

- [x] Review clean merge candidates for root/build settings, Android build compatibility, shared parser/host contracts and existing fork analysis/DSP state.
- [x] Import upstream-only surviving-module paths exactly from the verified tag. Record every copied/deleted path and verify its pre-change hash. Preserve native-desktop deletions for the desktop adaptation task, not as skipped parity work.
- [x] Apply reviewed clean textual merges; record both parent hashes and the merged hash.
- [ ] Add `:tv` and `:wearos` to SDK builds while preserving `:desktopApp` and modern plugin selection.
- [ ] Adapt TV/Wear plugin application to AGP9 built-in Kotlin while retaining their Android targets and flavor/signing contract. Validate Gradle project configuration before tests.
- [x] Resolve Android cache/host conflicts and XML resources by semantic key, preserving fork additions while accepting upstream updates to existing translations.
- [x] Resolve backend protocol/deployment and web lyric-clock/provider conflicts using all three versions. Keep connection-identity and ordered-send regressions.
- [ ] Run shared/sharedUi tests and Android Dev tests, compile TV/Wear, then resolve any source or dependency failures at their boundary.

Validation commands are adapted from `.work/verify_upstream_integration.ps1`; new module tasks include `:tv:assembleDevDebug` and `:wearos:assembleDevDebug` after inspecting their actual variants. Backend validation uses `.work/verify_upstream_backend.ps1 -TestPattern .` without publishing a server.

## 3. Adapt native desktop history and UI separation

- [ ] Compare current desktopApp against the ancestor and `0a3931e5e6033859f341571365f241d9bab18069`; integrate upstream equalizer, quality reporting, FX, MPRIS, SMTC, playlist/menu and desktop layout changes.
- [x] Introduce upstream desktopUi with the mapped fork translation, typography, minimized presentation/animation policy and relevant tests. Switch desktopApp's UI dependency to this module exclusively.
- [ ] Resolve native CMake/window/SMTC changes while retaining MSVC ARM64 and x64 builds and correct native library selection.
- [x] Compile desktop/shared data; run all desktopUi and desktop regressions on native ARM64 Java21/25 and x64 Java21 under emulation. Add focused failing regressions for concrete adaptation bugs before fixing them.
- [ ] Verify architecture and initialization of the bundled runtime, launcher, Skiko, FFmpeg, ONNX and selected JNI/JNA resources.
- [ ] Adapt desktop packaging/docs/CI retained from the fork to the new graph and source version. Do not run release workflows or upload artifacts.

## 4. Close feature gaps and validate packages

- [ ] Recompare the entire resulting tree with v1.8.1, including retained and local-only files. Attach a concrete feature/capability reason to each remaining difference.
- [ ] Check release-note feature coverage against implementation and tests; investigate every missing behavior rather than treating an imported file as sufficient.
- [ ] Run Android/TV/Wear and desktop regression/build checks, plus backend and available web checks. Preserve source/architecture evidence for the exact build.
- [ ] Rebuild isolated Android Dev APKs and ARM64 portable packages with Java25 and an aligned Java21 fallback. Preserve earlier archives.
- [ ] Validate Windows and available Android device playback, queue, lyrics, output recovery, minimize/restore, Discord seek/reconnect and normal shutdown.
- [ ] Repeat affected graphics workloads and report measured regressions/improvements, with no invented energy results.
- [ ] Deliver a feature/adaptation/conflict/difference ledger and exact build commands. State unavailable physical platform checks and any remaining technical deferrals. Do not publish.

Detailed conflict resolutions and validation commands are recorded with their actual files and results in `.work/upstream-v181-audit-20261019/`; this plan is updated as each batch is completed.
