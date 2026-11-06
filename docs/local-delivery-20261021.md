# BitChord local delivery, 21 October 2026

This record covers the local ARM64 modernization, official v1.8.1 integration and subsequent Classic/Modern player and Discord corrections. No new commit, push, release or deployment was performed. Physical and service limits are explicit below; source coverage alone is not treated as feature verification.

The workspace moved to `E:\Development\BitChord-main`; the development checkout remains its `.work/upstream-integration-20261018` directory. Source, Git and package integrity were checked there, worktree links repaired and generated CMake metadata backed up. Web dependencies were restored from the unchanged lockfile and fresh web checks pass. Candidate4 contains the tested desktop decoder-lifetime fix described below. See the outer workspace's `docs/relocation-20261021/REPORT.md`.

## Architecture and integration

The application remains Kotlin Multiplatform/Compose. Native desktop consumes `desktopUi`; Android/TV consume `sharedUi`, avoiding duplicate packages/resources. Desktop playback, native FFmpeg/analysis, WASAPI/output recovery, Windows media integration and Discord remain platform adapters rather than common Windows dependencies.

Official target: v1.8.1 `7d956f211b8a46a87184fd3ff87432b51a111dfa`, compared in three ways with ancestor `fe95bb60aa9cd205cf4ab8bd25a5bec71a595b4f` and the frozen actual fork. All1205 official paths are accounted for:1134 normalized equal,71 adapted,0 missing;523 retained/added paths have decisions recorded, including this delivery record and the new decoder resource guard/test. The feature map and conflict explanations are in [the integration record](upstream-v181-integration.md).

Official v1.8.1 removed native desktop. This fork retains it, using the last native parent as an additional reference while integrating the complete v1.8.1 Android/shared/TV/Wear/backend/web target. Overlaps were resolved by adapting both implementations: current queue/cache/DSP/output behavior, richer AutoMix status/recovery, saved translation and Thai fonts, native Spotify grants, and visual scheduling coexist.

## Native Windows delivery

Windows ARM64 has target-aware runtime/native dependency selection and binary gates. The ARM64 JavaFX gap is handled by the native WebView2 desktop adapter; JavaCPP/FFmpeg and ONNX/native analysis use verified compatible native inputs. MSVC2026 is used for ARM64 native builds. x64 remains a separate target with its own native inputs; x64 MinGW is not used to manufacture ARM64 binaries.

The optional modern build uses ARM64 Java25, Gradle9.5.1, AGP9.3.3 and Kotlin2.4.21. Desktop application bytecode/minimum runtime stays21; Android/shared bytecode17, minSdk and targetSdk are preserved. The original Java21/Gradle8.11.1 desktop baseline remains tested.

Latest local files are below `E:\Development\BitChord-main`:

| Artifact | Path below `outputs/BitChord-dual-player-20261020` | SHA256 |
| --- | --- | --- |
| ARM64 Java25 portable | `candidate4/java25/BitChord-1.8.1-dual-player-candidate4-windows-arm64-java25-portable.zip` | `332a6ade46e9fcf6b97b14d246b0c6e83f05a53b210dd0ff5c14526f960540f4` |
| ARM64 Java21 fallback | `candidate4/java21/BitChord-1.8.1-dual-player-candidate4-windows-arm64-java21-portable.zip` | `abc4a9d859a1026ff06a9bcba7336b1efd1653274a15b651bda7dabc11a519a7` |
| ARM64 phone Dev APK | `candidate2/android/BitChord-1.8.1-dev-arm64-v8a-debug.apk` | `72c9f89924df08804df001b7d0327e1a2da460a60310c5d6b4fb69af95b47fe8` |

Each portable passes80 standalone PE and34 selected JAR-native ARM64 checks and159 selected current class comparisons, including the decoder owner and its callers. All116 application/dependency JARs match between Java21/25; application classes remain Java21. No account profile is packaged. The unchanged phone code needs no rebuild for the desktop-only decoder-lifetime fix. Runtime scope for the exact new ZIPs is recorded separately below.

## Player and Discord changes

Classic reuses recovered historical source from fork tree `3a741066a29be3d2c6e2a213f09fda266d354eac`, adapted to current slots and controls. Modern retains the current desktop artwork/transport-left and lyrics-right design. Shared Android wide layout offers both arrangements; portrait intentionally keeps its responsive interface as the user requested.

`PlayerLayout` uses stable `modern`/`classic` keys, with Modern for missing/invalid values. Android AppSettings/SharedPreferences and desktop portable-aware persistence publish the selection immediately. One NowPlayingScreen state owner and one playback engine serve both layouts. Appearance radio controls are localized across19 catalogs. Android title/option colors explicitly follow the theme and were visually rechecked on the installed APK.

Discord sends are coalesced within the publication budget while preserving the latest seek anchor. Pausing/stopping retains the last track with a paused label and no running timestamps; resume recalculates the current clock. Explicit disable and real quit clear presence. Both existing transports share the timeline policy; local Discord behavior was manually confirmed on candidate3.

Changed areas: shared `PlayerLayout` and tests; both UI modules' PlayerHost/LandscapePlayer/NowPlayingScreen and pane tests; desktop persisted setting/Appearance preference/host; Android AppSettings/host/SettingsSheet/Appearance preference and catalogs; DesktopDiscordSession/Rpc and regression tests. Build/native and upstream changes are detailed in the linked integration and ARM64 guides.

## Build and regression results

| Check | Result |
| --- | --- |
| Desktop ARM64 Java21, ARM64 Java25, x64 Java21 under emulation | 498 tests each,0 failures/errors/skips |
| Original Java21/Gradle8.11.1 baseline | Shared101, sharedUi58, desktopUi58, desktop498;0 failures/errors/skips |
| Android Dev | 1113 total unit tests,0 failures/errors,1 existing live-service skip |
| Shared Android host / TV | 50 /16 tests,0 failures/errors |
| Phone/TV/Wear Dev packages | Successful, including Wear lint/R8 and embedded watch APK |
| APK inspection | Nine APKs pass signatures, ZIP integrity,16KB alignment and ELF architecture; embedded watch bytes/certificate match |
| Native window/audio recovery fixtures | CTest2/2 each on ARM64 and x64 |
| Windows packaging regressions | 13 pass |
| Backend / web | Go tests/vet;119 web tests, type checking and app/extension production builds pass |
| GPU/battery counter regression scripts | Pass; current battery probe rebuilt, ARM64 PE/schema verified |

The first missing-API tests, feedback failures and source-review findings were resolved before green reruns. Independent review covered the selector, background/pane ownership and Discord scheduling/paused-state deltas. Hashed XML, build logs and package manifests are preserved in `.work/upstream-v181-audit-20261019` in the original workspace.

The candidate4 reruns cover desktop498 across the three JVM/architecture variants and the original desktop/shared/UI baseline. Fresh post-move web checks pass119 tests, type checking and app/Chrome/Firefox production builds; backend contracts separately passed80 repeated executions plus vet. Unchanged Android/native fixtures retain their earlier results and were not rebuilt for the desktop-only decoder delta.

A final comparison found all1228 code/configuration files selected from the packaged source manifest still match, with no unexpected code changes after packaging. Latest ZIP/APK hashes were rechecked. Later reporting edits do not change those binaries or their package-time source snapshot.

The candidate4 handoff recheck compared all1682 packaged files outside Markdown/progress reports, with no differences, and freshly matched both portable and APK SHA256 values. It re-read the completed498-test result records for ARM64 Java21, ARM64 Java25 and x64 Java21; it did not rerun those suites for documentation-only changes. Evidence: `.work/dual-player-20261020/handoff-artifact-verification-candidate4.json`.

The new desktop change establishes ownership before opening artwork decoders, so cancellation on returning from IO cannot bypass native cleanup. Both current and legacy canvas surfaces use the same non-cancellable IO cleanup owner. Four ownership tests plus a real FFmpeg cancellation fixture failed when close was deliberately removed and passed with correct cleanup restored before all full tests and packaging. Independent review found no actionable issue. This is a resource-lifetime correction; no new GPU/power improvement is attributed to it. Live Compose disposal timings and a native allocation census remain unverified.

## Manual acceptance

After relocation, both exact candidate4 ZIPs launched from E: with their bundled ARM64 runtimes and initialized Skiko/FFmpeg/native modules. The user confirmed sound on Java25 and Java21. On Java25, Modern lyrics/search, pause/seek1:50, Classic switching with the same paused track/position and resume were observed. Java21 search, Modern lyrics, pause/seek1:50 and resume were observed. Both copies exited normally through user tray Quit; the final candidate-specific process check found no remaining owned launcher/JVM. Broader Bluetooth, Discord, AutoMix and power acceptance below retains its earlier candidate scope. Details are in [dual-player validation](dual-player-validation.md).

Windows checks include screenshot-reference comparison, playing/paused switching, a paused seek retained across layout change, shared volume, normal quit/relaunch Classic persistence and earlier Bluetooth recovery. Candidate3 user checks confirmed paused Discord display, accurate resume clock, removal on quit, exact Java21 fallback sound/transport/seek, and both layouts' queue/lyrics/output panels preserving song/position/volume. Minimize/restore continued sound, playhead and lyrics. Both current test copies exited normally after testing; the charger was reconnected.

That completed measurement session is recorded in `.work/dual-player-20261020/final-environment.json`. A subsequent covered-window investigation relaunched the existing Java21 installation at01:50UTC, after that shutdown. At the02:06UTC handoff, the original measurement launcher/JVM were absent in two observations five seconds apart, while the later responsive follow-up launcher28220/JVM24600 remained open. No force termination was performed. Do not confuse this later launch with a failure of the completed user Quit check. See `handoff-environment.json` beside the earlier record.

Android Dev wide layouts were visually distinguished in actual landscape; portrait similarity is intentional. The user confirmed playback and saved layout after reopening. Corrected labels were visually verified after updating the APK. The regular installed Android application was preserved.

## Performance and power

Current same-process Java21 Modern/Sweater Weather lyrics measurements used15 valid foreground observations per run, unchanged window and no builds/cleanup:

| Power/mode | App GPU3D | Whole busiest-engine GPU | CPU cores | Driver whole-device discharge |
| --- | ---: | ---: | ---: | ---: |
| AC Balanced A1 | 21.29% | 37.40% | 0.3654 | Not a consumption measurement on AC |
| AC Efficient B | 14.01% | 29.71% | 0.1431 | Not a consumption measurement on AC |
| AC Balanced A2 | 21.62% | 37.13% | 0.3524 | Not a consumption measurement on AC |
| Battery Balanced A1 | 20.97% | 40.09% | 0.5322 | 14.1394 W |
| Battery Efficient B | 13.32% | 34.01% | 0.4175 | 11.5890 W |
| Battery Balanced A2 | 21.11% | 39.56% | 0.4925 | 14.1447 W |

The controls returned to higher readings after restoring Balanced. This supports the retained Efficient visual policy, without changing audio quality, acceleration or imposing a global frame cap. A separate Balanced minimized run had15/15 minimized samples, app3D2.83% and CPU0.0000 at the collector's resolution; playback continued. Residual GPU work remains.

Battery means are post-analysis of immutable signed native-driver records with valid status and capability mask0x80000000. The cached probe omitted the later `shortTerm` field, so the collector correctly left normalized fields null. Analysis checks the recorded full mask, offline/discharging state and non-sentinel signed rates using installed SDK constants; the current probe/schema was independently rebuilt and verified. Full provenance is in [dual-player validation](dual-player-validation.md).

These are short, whole-device driver observations, not calibrated app energy or a battery-life percentage. DWM and other applications remain contributors. Historical controlled covered-animation and original-x64 observations are in [performance results](desktop-performance-results.md); they are not mixed with these new layout/runtime observations. Earlier matched Java21/25 idle tests found no established Java-version performance advantage. Native execution alone is not claimed to explain the original60%+ whole-GPU symptom.

The subsequent restored-window comparison used the same candidate3 Java21 JVM24600, Balanced/Modern Sweater Weather synchronized lyrics, unchanged size and AC. Fifteen samples per run gave app3D21.1690% visible,8.8751% fully covered by user-prepared maximized File Explorer without minimizing, then21.4368% visible again. Whole busiest-engine means were41.8528%,14.2719%,42.7088%. All flags matched the prepared conditions: visible/not minimized throughout, foreground15/0/15, AC15 each, no GPU read failures. The user confirmed continuing sound/playhead/lyrics before the return control. Backend checks finished before the covered sample; no build overlapped these samples. This confirms reduced work under cover with residual GPU activity, not complete suspension or app-specific power consumption. Raw records and `candidate3-covered-comparison.json` are under `.work/dual-player-20261020/performance` in the workspace. Pinned-renderer source limitations are recorded in `obscured-followup-20261021/occlusion-feasibility.md`; focus loss is not used to freeze partially visible windows.

The integration's backend socket contracts were also rechecked: replacement cleanup, nonblocking bounded sends, immutable ordered frames and graceful bye delivery passed20 repetitions each,80 test executions with no failures; `go vet ./...` passed. This was not a race-detector run: the installed Go toolchain's platform table supports the Windows race detector only on amd64. No backend production change was needed.

The later candidate4 Java25 Classic/Opalite motion-artwork follow-up directly established the live video path: a delivered-decoder public-provider probe decoded60 distinct frames at540×540, and a separate25-second profile found24 canvas sample events, including7 decoder `nextFrame` stacks in the real player. Profiling finished before collection. Visible/minimized/restored-visible runs then passed12 window/power/GPU observations each: app3D7.0776/0.4421/7.1554%, whole busiest engine57.7780/43.1075/57.9656%, CPU0.3616/0.0000/0.3190 cores. Same process, AC, visible geometry and track reset near1:00 were retained. Restore without Play showed the song/lyrics advancing. CPU zero is at collector resolution. User tray Quit left no owned process; a separate after-Quit system sample still showed whole3D42.6588%, DWM40.5573%, with other apps open. This establishes substantial compositor activity beyond this test copy, not its source or a driver defect. These are no calibrated energy or original-x64 comparisons. Full raw evidence, per-engine attribution and limits are in `.work/dual-player-20261020/artwork-followup-20261021/REPORT.md` in the workspace. No application code or package changed for this follow-up.

## Storage cleanup

At the user's request,5,811 individually verified duplicate runtime/library files were removed from stopped old test copies:5,051,601,016 bytes (4.70GiB). Every target was checked absent, ten retained ZIP hashes matched and both current installations remained intact. Measured free space after verification was15.29GB/14.24GiB. Profiles, source/Git, unique old JARs, toolchains/native builds and evidence were preserved. Original-workspace `docs/cleanup-20261021/REPORT.md` contains the allowlists and restoration mapping. Old depleted extractions require exact deleted ZIP members restored before reuse.

## Reproduce and limits

From `E:\Development\BitChord-main`, run one Gradle build at a time:

```powershell
rtk proxy powershell -NoProfile -File .work/verify_v181_integration.ps1 -Stage desktop
rtk proxy powershell -NoProfile -File .work/verify_v181_integration.ps1 -Stage desktop-java25
rtk proxy powershell -NoProfile -File .work/verify_v181_integration.ps1 -Stage desktop -Architecture x64
rtk proxy powershell -NoProfile -File .work/verify_v181_integration.ps1 -Stage android-platforms -UseVerifiedGoogleStaging
rtk proxy powershell -NoProfile -File .work/package_v181_portable.ps1 -Runtime 25 -RunId local-rebuild
rtk proxy powershell -NoProfile -File .work/package_v181_portable.ps1 -Runtime 21 -RunId local-rebuild
```

Packaging uses `:desktopApp:windowsPortable` with explicit Windows/ARM64, matching runtime and verified native inputs. See [the ARM64 guide](windows-arm64.md) for direct setup/measurement commands. Rebuild the battery helper from the current source before collection. The original wrapper remains the desktop Java21 fallback; the modern entry does not raise application runtime requirements.

The final runtime-default audit found no unintended x64 Java25 promotion: `.github/workflows/release.yml` selects JDK21, and Compose packaging obtains its runtime from the Java21 desktop toolchain. ARM64 portable packaging requires an explicitly selected, architecture-checked Java21 or25 runtime. The separate x64 `windowsPortable` batch stage still uses the user's installed Java21+; it is not the bundled Compose release image. Hosted release execution remains unverified.

Physical x64, native Linux/macOS, TV/Wear hardware, every external-service flow on this exact candidate, hosted CI, production signing/installers and long-term calibrated battery life remain unverified. Current comparisons cover synchronized lyric effects, minimized playback, the fully covered restored-window sequence and subsequently verified Classic motion artwork. x64 ran through Windows ARM emulation. Native Spotify playback/Bluetooth and account grants have earlier manual acceptance; they were not reauthorized in the final fresh profiles. TV retains its separate D-pad player and Wear its remote interface; their native interaction contracts are not replaced by the phone/desktop selector. Web retains the official separate UI. No bit-perfect hardware-output claim is made. The broader modernization goal remains open for the remaining scenarios and verification limits; this report does not declare universal feature or platform parity.
