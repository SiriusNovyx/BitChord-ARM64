# Official v1.8.1 integration

Status: integration and validation in progress. No commit, push, release, or deployment has been performed for this integration.

The subsequent user-requested Classic/Modern player selector is implemented on top of this integration. Its Windows ARM64 and Android acceptance, latest packages and current498-test desktop suites are recorded in [current dual-layout validation](dual-player-validation.md), [design](superpowers/specs/2026-10-20-dual-player-design.md) and [implementation plan](superpowers/plans/2026-10-20-dual-player.md). Packages and test counts below remain the frozen **pre-selector** v1.8.1 baseline; no earlier package is presented as containing the selector.

The target is [official BitChord v1.8.1](https://github.com/kushagrasinghx/BitChord/releases/tag/v1.8.1), commit `7d956f211b8a46a87184fd3ff87432b51a111dfa`. The three-way comparison uses common ancestor `fe95bb60aa9cd205cf4ab8bd25a5bec71a595b4f` and the actual frozen fork working state, including its uncommitted fixes. Overlapping files were reviewed against both implementations.

Official v1.8.1 removed the native desktop application in commit `e4dd63d863e3df67cf6c4e2ebd1385907d2f474d`. This fork retains native desktop support and adapts the final native revision, `0a3931e5e6033859f341571365f241d9bab18069`, as an additional reference. It does not substitute that reference for the v1.8.1 Android, shared, TV, Wear OS, backend, or web target.

## Source coverage

Independent source review found all 1,150 official inventory paths under `app`, `shared`, `sharedUi`, `tv`, `wearos`, `native`, `gradle`, and `web` present. At that review, 37 official source/text paths differed after line-ending normalization. Those differences contain the adaptations below. Path presence alone does not establish runtime behavior.

After adding the requested dual-player feature and Android CI adaptation, the current audit contains 1,205 official paths: 1,134 normalized-equal, 71 adapted, zero missing. Within the 1,150 surviving platform paths, 1,091 match and 59 are adapted. The additional differences include player preference/host/layout wiring and localized settings labels; they do not represent omitted upstream functionality. A per-path decision ledger also records retained native files and fork additions. Current runtime and package acceptance is recorded separately in [dual-player validation](dual-player-validation.md).

The post-relocation candidate4 audit records523 retained/added paths and1728 decisions overall. Six canvas decoder/caller/test paths have an explicit resource-lifetime decision: cleanup owns opening as well as playback, with real FFmpeg cancellation and close-removal negative controls. Web119/typecheck/app/extension builds were rerun successfully from the new E: workspace after restoring generated dependencies from the unchanged lockfile. Source coverage remains separate from runtime acceptance and platform limitations.

| Upstream functionality | Integrated implementation and scope |
| --- | --- |
| Player controls, quality badge, star favorite, artwork colors and actual output | Shared player screens and controls; Android host and audio telemetry. Native desktop uses its separately adapted player. |
| Bounded album/playlist queues, history, repeat and shuffle corrections | `PlaybackService`, `QueueWindow`, `ContextRegistry`, `BoundedQueueEdits` and `QueueTimeline`. |
| Search gestures, row navigation and submission privacy | Search screens, swipe-to-queue, `MainViewModel.recordSearch`/`runSearch`, and shared Innertube searches. |
| RTL lyrics, centered landscape/TV focus and instrumental filtering | Shared lyrics/providers and player callers; Thai font and saved translation mode coexist with the new direction and tint behavior. |
| Song information, artist signatures and private uploads | Android song-info repository/dialog, signature repository/settings/Coil decoding, uploaded-track parser and authenticated stream resolution. |
| Cache survival, offline playback, download/remove controls | Android cache migration and sibling selection; watch namespace correction described below. |
| Spotify playlist import controls | Login/import fixes, language preference and saved-playlist download consumers. Native desktop additionally retains its separate OAuth library and native playback grants. |
| Local/download pages, album/playlist design, navigation and Replay | Imported Android screens, main navigation, reorder operations and daily/weekly statistics. |
| Peak limiting and output capability fixes | Shared limiter and Android DSP/output detection, including SSC UHQ handling. |
| Profile/security and account cleanup | TV salted PIN hashing, legacy migration and attempt throttling; account sign-out integration cleanup. |
| TV and Wear OS | TV client/profile/setup sources; watch installation, updates, protocol, controls and playback sources. Their modern build migration is being validated. |
| Web app and browser extension | Exact-tag app/shared/extension source, including player/video/artwork/artist/history/lyrics behavior. Tests, type checks and production builds pass; no service deployment performed. |

## Adaptations and conflicts

- `desktopApp` depends exclusively on `desktopUi`; Android/TV use `sharedUi`. Both UI modules retain matching packages and generated resource names, so combining them in one application would produce duplicate definitions.
- Native ARM64 runtime/launcher/native-library selection, MSVC guards and binary verification remain. The desktop build retains Java 21 bytecode, Java 25 runtime packaging and an aligned Java 21 fallback. The default desktop version now follows the root application version unless explicitly overridden.
- Upstream typed/DSP additions coexist with the fork's richer AutoMix eligibility/status model, error propagation, Bluetooth recovery and partial-write ownership. New phrase lengths and transition effects are adapted into the native engine.
- Lyrics retain translation request coalescing, persisted display mode, WDB Bangna Thai typography, frame scheduling and foreground activity gates. Upstream RTL sweeps, line wrapping and artwork tint are merged with those capabilities. New lyric-strip clocks receive the fork's schedule contract.
- Resource conflicts use official updated translations while retaining independent analysis labels. No language was removed because its file overlapped.
- Complete-cache sibling lookup now checks the requested phone/watch namespace before span, completeness and origin queries. Eleven focused tests exposed six failures in the old policy and pass after the fix; the full Android suite includes them.
- AGP 9 built-in Kotlin requires custom Kotlin roots to use `AndroidSourceSet.kotlin`. The phone/watch protocol and TV reused-phone roots are adapted accordingly, while Java/Kotlin bytecode targets remain unchanged. TV resource-value generation is explicitly enabled.
- Discord retains serialized session ownership, bounded IPC and seek-aware snapshots. Shared application identity, measured-quality tooltips, connection diagnostics and absent-artwork fallback are adapted from upstream.
- Backend connection-identity cleanup and ordered socket ownership coexist with upstream protocol documentation and peer-scoped proxy configuration. Go tests and vet pass; Caddy was not deployed.
- Web conflicts resolve to the exact tag: two source-reference comments and the KuGou instrumental-notice filter.
- Review found upstream native fullscreen topmost and paused skip-mix defects. Fullscreen now yields z-order to other applications when inactive; real Win32 activation regressions run on a private desktop. Paused skip-mix now falls back to the existing normal queue advance instead of waiting for a blend the paused pump cannot complete. Both fixes have failing-before/passing-after evidence and independent review.

## Validation recorded so far

| Check | Result |
| --- | --- |
| Shared JVM tests | 99, no failures/errors |
| Shared UI JVM tests | 54, no failures/errors |
| Native desktop UI JVM tests | 54, no failures/errors |
| Android Dev unit tests | 1,113, no failures/errors, one skipped |
| Desktop ARM64 Java 21 | 482, no failures/errors, including review follow-ups |
| Desktop ARM64 Java 25 | 482, no failures/errors, including review follow-ups |
| Desktop x64 Java 21 under Windows ARM emulation | 482, no failures/errors; physical x64 hardware not tested |
| Java 21 / Gradle 8.11.1 baseline | Shared, sharedUi, desktopUi and desktop test tasks pass on integrated source |
| Native ARM64 and x64 | Window-frame and audio-recovery CTest: 2/2 each architecture |
| Windows package verifier regressions | 13 pass |
| Backend | `go test ./...` and `go vet ./...` pass |
| Web, pnpm 12.11.2 | 119 tests pass; type checking and app/extension production builds pass |
| TV unit tests | 16, no failures/errors; mixed reused Java/Kotlin roots corrected |
| TV/Wear and Android packages | All three Dev debug builds pass, including required Wear lint/R8 and the phone's embedded watch APK |
| APK structure/signatures | All nine APKs pass signatures, ZIP integrity, 16 KB alignment and ELF architecture inspection. Phone/watch embedded APK bytes and signing identity match. Phone/TV minSdk26, Wear minSdk30, all targetSdk36 |
| Android 17 device | Exact ARM64 Dev APK installed and launched on SM-S721B/API37 with existing Dev data preserved; regular BitChord version/install timestamp unchanged. User confirms sound, pause/resume, seeking and Bluetooth recovery |
| Shared Android host tests | 48 pass; sharedUi Android host task has no tests, so its actual JVM UI suite is used in CI |
| New ARM64 Java 25 portable | Built, architecture checked and launched from delivered ZIP; user confirms sound, pause/resume, seek, advancing playhead and lyrics |
| New ARM64 Java 21 fallback portable | Built and architecture checked; exact-package live acceptance pending |

The original `.work/upstream-v181-audit-20261019` directory contains the frozen source archive, per-path three-way comparisons, resolution records, logs and hashed XML test results. The per-path decision ledger covers every official path and retained fork addition. New artifacts are under `outputs/BitChord-v1.8.1-integration-20261020`; their checksums and package-time source manifests are preserved. All 116 application/dependency JARs match between the Java 25 and Java 21 portable variants; only their bundled runtimes and launcher configuration differ. Each package passed inspection of 80 standalone ARM64 PE files and 34 selected native libraries within JARs. Application class files remain Java 21. Earlier portable ZIPs and runtime acceptance belong to the pre-v1.8.1 baseline.

## Remaining validation and differences

The formerly pending Android/native acceptance and performance checks have subsequently been exercised on the dual-player candidates: user-confirmed phone/Windows playback, both wide layouts, persistence, panels, Discord seek/pause/resume/quit and minimize/restore, with current AC/battery Balanced/Efficient/Balanced observations. See [the current validation record](dual-player-validation.md) for exact scope and values. Those checks do not imply physical native Linux/macOS, physical x64/TV/Wear, every external-service interaction, hosted CI or calibrated long-term battery-life verification. Earlier Java21 fallback rows above describe their frozen pre-selector archive; the current candidate3 fallback has separate successful live acceptance.

Phone-specific interfaces and platform integrations remain in their platform modules. Native desktop is an additional fork product after official removal, not a claimed official v1.8.1 desktop release. Its internal UI separation and native dependency/toolchain adaptations are documented rather than hidden as skipped files.

## Reproduce the local candidate

From the original workspace, the recorded helper commands are:

```powershell
rtk proxy powershell -NoProfile -File .work/verify_v181_integration.ps1 -Stage desktop
rtk proxy powershell -NoProfile -File .work/verify_v181_integration.ps1 -Stage desktop-java25
rtk proxy powershell -NoProfile -File .work/verify_v181_integration.ps1 -Stage desktop -Architecture x64
rtk proxy powershell -NoProfile -File .work/verify_v181_integration.ps1 -Stage android-tests
rtk proxy powershell -NoProfile -File .work/verify_v181_integration.ps1 -Stage android-platforms -AllowMavenDownloads -BoundNetworkWaits -UseVerifiedGoogleStaging
rtk proxy powershell -NoProfile -File .work/package_v181_portable.ps1 -Runtime 25
rtk proxy powershell -NoProfile -File .work/package_v181_portable.ps1 -Runtime 21
```

These audit helpers select the existing local toolchains and native inputs; they are not required project dependencies. `UseVerifiedGoogleStaging` supplies only the unchanged official coordinates listed in the hashed download evidence, through a temporary local Maven repository after direct downloads timed out. Normal networked builds do not require that option. Run one Gradle build at a time in this checkout. Portable packaging is `:desktopApp:windowsPortable` with `bitchord.target=windows`, `bitchord.arch=arm64`, an explicit matching `bitchord.windowsRuntime` and the native-input properties described in [Windows ARM64 setup](windows-arm64.md). The modern wrapper and `bitchord.modernBuild=true` select the candidate build infrastructure; the original wrapper remains available for the Java 21 desktop baseline.

The frozen Java 25 ZIP SHA256 is `5319c37fb5ed2cf590af4fd561615a67eab446ece16b6e5cb24f37bed69da5cf`; the aligned Java 21 fallback SHA256 is `82c95bd0312464d3088edbf41618d90f67238780738b376b3831ca7f4e48ccb1`. Later documentation edits do not change those archives or their package-time source manifests. Neither archive contains an account profile or portable data directory.
