# Comprehensive BitChord v1.8.1 integration

The user explicitly authorized comprehensive conflict resolution, including replacement or refactoring of customized files. File overlap is investigation work, not an exclusion rule. No commits, pushes, releases or publication are authorized for this phase.

## Target and starting state

- Official repository: https://github.com/kushagrasinghx/BitChord.
- Target: v1.8.1, `7d956f211b8a46a87184fd3ff87432b51a111dfa`, verified against the remote tag.
- Common ancestor: `fe95bb60aa9cd205cf4ab8bd25a5bec71a595b4f`.
- Published fork head: `8f2a2727d989d1038a5d30337699450cdc88a0f1`.
- Current local tree, including unpublished presentation work and the working `.gitignore`: `3a741066a29be3d2c6e2a213f09fda266d354eac`.
- Baseline source ZIP, hashes, staged/unstaged patches and complete path inventory: `.work/upstream-v181-audit-20261019/` in the original workspace. Delivered portable archives remain immutable.
- Baseline desktop tests: 473 passing each on ARM64 Java21, ARM64 Java25 and x64 Java21 under ARM emulation. Exact Java25 and aligned Java21 portable copies have user-confirmed sound and seek controls; Java25 also has explicit Discord seek-timer confirmation.

## Integration architecture

Perform three-way comparisons of ancestor, actual local source and v1.8.1. Apply compatible changes from both sides; resolve overlapping behavior through source review and tests. Keep an explicit path/feature ledger that distinguishes imported source, adapted functionality, intentionally retained capabilities and unresolved work. A clean textual merge is a candidate requiring semantic and build validation.

The tag intentionally removes native desktop in commit `e4dd63d863e3df67cf6c4e2ebd1385907d2f474d`, replacing it upstream with the web player. Deleting the fork's native product would violate the user's Windows ARM64 requirement. Use the final pre-removal revision, `0a3931e5e6033859f341571365f241d9bab18069`, as an additional desktop adaptation reference. It contains the `desktopUi` separation, desktop player redesign, audio-quality reporting, AutoMix transition effects, MPRIS and Windows media changes. This reference supplements, and never substitutes for, the v1.8.1 target in surviving modules.

Adopt the upstream `desktopUi` separation where it preserves the desktop redesign and Android independence. Desktop must depend on `desktopUi` rather than simultaneously loading it and `sharedUi`, because both generate the same resource class and UI packages. Port the fork's translation persistence, Thai typography, visual policy and hidden-work fixes into the appropriate desktop/shared UI implementations. Retain shared data contracts where practical.

Integrate Android, shared, sharedUi, TV, Wear OS, web, backend and build/dependency changes. Include TV and Wear OS in SDK-equipped builds; adapt their build scripts to the existing opt-in modern AGP path. Preserve normal Android minimum/runtime targets, existing signing boundaries and separate development package IDs. Avoid platform-native Windows dependencies in common code.

Keep architecture-aware Windows native selection, ARM64 Java25 packaging, Java21 bytecode/fallback, x64 builds, FFmpeg/JNI, ONNX, WebView2 and Spotify native playback. Native internal implementations may change; the capability contract and architecture verification may not silently disappear. Retain desktop CI/packaging functionality through adaptation of removed upstream desktop workflows, rather than unreviewed deletion.

## Feature and conflict accounting

Investigate every differing path, including local additions and previous deferrals. Feature accounting covers the redesigned player and quality/output claims, Song Info, signatures, album/playlist/local/download views, icons, Replay, bounded queues, shuffle/repeat/radio behavior, search gestures/history/privacy, lyrics/RTL, Spotify/import/downloads, caches/offline playback, audio/DSP/output routing, profile security/sign-out, navigation, Listen Together, TV profiles/PINs, Wear integration and translations.

Each retained difference must identify its concrete capability or adaptation, relevant upstream history and verification. Architecture differences require an attempted adaptation; they do not by themselves justify deferral. Report remaining missing functionality without substituting file counts for parity.

## Validation and delivery

Validate shared data tests and source contracts first, then Android/TV/Wear and desktop compilation/tests, native binaries and packaging. Recheck Windows ARM64 startup/audio/seek/lyrics/output recovery, Discord timing and minimize/restore; preserve x64 coverage. Build new Android development APKs and ARM64 portable packages only after integration checks pass. Do not relabel baseline archives as v1.8.1.

Use available devices for runtime checks and explicitly distinguish compilation/unit tests from physical TV/Wear/x64/Linux/macOS execution. Re-measure graphics changes in repeatable workloads; never infer battery savings from GPU activity alone. No global frame cap, blanket software-rendering switch or quality reduction is an acceptable shortcut.

Completion requires a reviewed feature/difference ledger with every difference resolved or technically justified, passing supported regression/build checks, rebuilt packages and a report distinguishing verified behavior from remaining platform/environment limits. A selected-file import alone is incomplete.
