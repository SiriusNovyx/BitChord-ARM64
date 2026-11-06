# Modern and Classic player validation

Status: the described Windows ARM64/Android dual-layout and current local Discord checks passed. Broader platform/service and remaining performance scenarios stay explicitly unverified. No commit, push or release published.

## Candidate4 after workspace relocation

Current workspace is `E:\Development\BitChord-main`, with development source in `.work/upstream-integration-20261018`. Integrity checks preserved packaged source and Git staging; repaired worktree links and backed-up CMake metadata use the new location. Fresh web119/typecheck/app/Chrome/Firefox builds pass after restoring generated dependencies from the unchanged frozen lockfile. No renewed old-folder access was needed.

Candidate4 adds a desktop-only decoder lifetime correction. `DesktopCanvasResource.kt` owns decoder opening and playback, with non-cancellable IO close; both canvas surfaces use it and `DesktopCanvasDecoder` implements `AutoCloseable`. Four new ownership tests and a real FFmpeg cancellation fixture detect cleanup failures. Removing only close caused the expected four ownership failures and native `Cancelled opening left a live native decoder` failure. Correct source was restored before full verification and packaging. Independent review found no actionable issue. Live Compose cancellation timing and native allocation accounting remain outside this evidence.

Full desktop suites pass498 each on ARM64 Java21/25 and x64 Java21 under ARM emulation, with0 failures/errors/skips. The original Java21/Gradle8.11.1 path passes shared101/sharedUi58/desktopUi58/desktop498. Hashed XML and logs carry `candidate4-native-*` and `candidate4-baseline8111` labels in the audit directory. Android source/APK remains unchanged from its earlier accepted build.

Both candidate4 ZIPs pass80 standalone PE and34 selected JAR-native ARM64 checks,159 selected class comparisons and Java21 application-bytecode inspection. All116 JARs match across the two runtime packages; their source-manifest SHA256 is `ee312c7565bd5967e0e3659278d563d0229767882e985daf8f2f60860d10b041`. The new handoff check matches1682 packaged non-Markdown files and both ZIP/current APK hashes. Package paths and hashes are in [the current delivery record](local-delivery-20261021.md).

The exact Java25 candidate4 ZIP launched at E: using its bundled JVM31324, Skiko and FFmpeg/native modules. Search and Modern synchronized lyrics were observed; the user confirmed audible Sweater Weather. Pause and seek to1:50 worked; Classic selected in Appearance opened with the same song, paused state and1:50 position, and its Resume control advanced playback. The user then used Quit BitChord, and a passive exact-workspace process check found no remaining app process before the Java21 fallback was opened. This is narrower than repeating every earlier Bluetooth/Discord/AutoMix acceptance, and no fresh GPU/power improvement is claimed for this cleanup fix. Candidate3 performance and earlier acceptance records below retain their original scope.

The exact Java21 fallback then used bundled JVM26360 and loaded the expected ARM64 Skiko/FFmpeg/native modules. Search, Modern lyrics, pause, seek to1:50 and resume were directly observed. The user confirmed sound after the seek and used tray Quit. The final candidate-specific process check found both candidate4 launcher/JVM trees absent, without force termination; record `candidate4-runtime-final-environment.json` is in the audit directory. This completes the new ZIP startup/basic-playback gate while retaining the broader limitations above.

A subsequent exact candidate4 Java25 launch retained Classic. Public Apple motion-artwork decoding produced60 distinct540×540 frames using the delivered decoder, and a separate25-second profile of the actual Opalite player found24 canvas sample events including7 `nextFrame` stacks. Profiling stopped before12-sample visible/minimized/restored-visible runs: app3D7.0776/0.4421/7.1554%, whole3D57.7780/43.1075/57.9656%, all window/power/GPU gates valid. Restore without Play showed the same track advancing from about1:07 before minimization to2:13 and then2:22. This establishes live artwork and one real minimize/restore lifecycle without duplicating an audio session. Acoustic playback had prior acceptance on this ZIP and was not re-confirmed during these samples. User Quit and a final process check confirmed normal exit. A post-Quit system-only observation still measured whole3D42.6588%, DWM40.5573%, with other apps open. Raw evidence, per-engine results and limits are in `.work/dual-player-20261020/artwork-followup-20261021/REPORT.md` in the workspace. No code/package change or calibrated energy/original-x64 comparison is claimed.

## Implementation and recovery

The two supplied screenshots were inspected. Classic's original wide arrangement was recovered from fork tree `3a741066a29be3d2c6e2a213f09fda266d354eac`, with the recovered blobs and checksums retained in `.work/dual-player-20261020` in the original workspace. It is adapted to the current player slots rather than restoring old playback logic.

`shared/.../data/settings/PlayerLayout.kt` defines stable `modern` and `classic` preference values. Fresh, missing or invalid values select Modern. Android's existing `AppSettings` persists the selection in SharedPreferences; desktop's `DesktopPlayerLayoutSetting` uses portable-aware `DesktopPersistence`. Both hosts expose the same typed flow through `PlayerSettingsSource`.

`LandscapePlayer.kt` in each UI module selects exactly one arrangement. `NowPlayingScreen.kt` retains song, scrub, queue, lyrics/translation and output state above that choice. The selection does not call playback commands, recreate the engine or key the player by layout. Modern native presentation is retained; Classic restores the large left cover, right-side origin/credits/lyric strip/transport/volume and bottom lyrics/output/queue actions. Current quality, DSP, AutoMix, anchored menus and playback adapters remain shared.

Both Appearance screens use an inline radio group with descriptions, keyboard/touch targets and immediately published selection. Six labels were added to all 19 Android language catalogs, also consumed by desktop's strings generator. Android portrait remains the existing responsive player as explicitly requested. Its Modern wide arrangement implicitly displays lyrics; the actual displayed pane also drives screen-on behavior without changing the saved pane or holding an empty placeholder awake.

TV retains its separate D-pad player, deferred scrub commits, focus ownership and transport timeout. It does not consume this full-player host. A TV-specific dual layout is not implemented; replacing its player with these slots would remove those navigation contracts. Wear is a remote control, and web has a separate responsive interface. Native Linux/macOS share desktop UI source but have not been run on those operating systems.

## Automated verification

| Current dual-layout source | Result |
| --- | --- |
| Shared JVM | 101 tests, zero failures/errors |
| Shared UI JVM | 58 tests, zero failures/errors |
| Desktop UI JVM | 58 tests, zero failures/errors |
| Desktop ARM64, Java 21 | 493 tests, zero failures/errors, including paused-presence and scheduling regressions |
| Desktop ARM64, Java 25 | 493 tests, zero failures/errors |
| Desktop x64, Java 21 under ARM emulation | 493 tests, zero failures/errors; physical x64 hardware untested |
| Android Dev unit tests | 1,113 tests, zero failures/errors, one existing live-service test skipped |
| Shared Android host | 50 tests, zero failures/errors |
| TV Dev unit tests | 16 tests, zero failures/errors |
| Phone, TV, Wear Dev packages | Build successful, including Wear lint/R8 and embedded watch APK |
| APK inspection | All nine APKs: signatures, ZIP integrity, 16 KB alignment and ELF architecture checks pass; embedded watch bytes/certificate match |
| Java 21 / Gradle 8.11.1 baseline, candidate3 | Shared101, sharedUi58, desktopUi58 and desktop493 pass |

New tests cover default/invalid storage keys, stable round trips, desktop reconstruction after saving without changing volume/output precision, and the actual wide pane for both layouts. Missing-API failures were recorded before implementation. An initial settings-search argument-count compilation failure and a startup probe parameter/local-variable naming collision were corrected; the subsequent relevant build/probe passed. Independent source review found the Android Modern screen-on gap; the correction and four pane tests in each UI module passed rereview.

## Exact candidate packages and runtime

Candidate 1 Java 25 portable: `outputs/BitChord-dual-player-20261020/candidate1/java25/BitChord-1.8.1-dual-player-candidate1-windows-arm64-java25-portable.zip` in the original workspace.

SHA256: `03009e3b80d4d95cef85ab42ea63bf9643cd225693d4d8e0d9ed0c05633474d1` (408,326,482 bytes). All 80 standalone PE files and 34 selected natives inside JARs are ARM64. Current player/setting class bytes match the build outputs, and application bytecode remains Java 21. The archive contains no account profile or portable data directory. It was extracted into a new candidate-specific directory and launched with its exact bundled ARM64 Java 25 runtime; loaded JVM/graphics/native modules and a responsive window were verified.

Candidate 1 ARM64 phone Dev APK: `outputs/BitChord-dual-player-20261020/candidate1/android/BitChord-1.8.1-dev-arm64-v8a-debug.apk`.

SHA256: `d6fc65e60d06fbbb740eeaa5f8de00539fdc67c1c6df24ac04b900cced405884`. Signed update installed and launched on SM-S721B / Android API 37. Existing Dev data was preserved; the regular `com.music.bitchord` installation's version/update metadata stayed unchanged. The app's minSdk26/targetSdk36 contract was preserved. Exact-candidate physical playback/layout/restart confirmation is pending.

## Visual and interaction observations

- Fresh native candidate selected Modern. Its artwork/transport-left and synchronized-lyrics-right arrangement was visually checked against screenshot 1.
- Appearance search exposed both options and subtitles. Selecting Classic while “Sweater Weather” played retained the same song and continued position. Classic's large cover, right-side details/controls/volume and bottom actions were visually checked against screenshot 2.
- In Classic, pause and seeking to 1:59 worked. Switching back to Modern retained the paused state and 1:59 position. Resuming advanced both playhead and synchronized lyrics. A changed Classic volume was reflected in the main desktop volume control after returning to settings.
- Remaining exact-candidate checks: repeated queue/output/lyrics interactions, normal quit/relaunch preference restoration, Android wide/portrait visual checks and physical sound/Bluetooth/Discord acceptance. Earlier accepted playback and recovery checks are recorded separately and are not substituted for these gates.

### Candidate 1 feedback and corrections

The user confirms Windows sound, controls and Bluetooth recovery, and Android playback plus persisted selection after reopening. Android's initial comparison was portrait, where the user explicitly requested an identical responsive player. A subsequent 2340×1080 landscape check verified Classic's right-side transport versus Modern's left-side transport with right-side lyrics. The actual saved value was checked through the two allowlisted preference keys only; no account credentials were inspected. The mobile selector remains because the wide arrangements are different.

The new Android preference's title and option labels inherited an unsuitable default content color; a screenshot confirmed black text on the dark surface. Those labels now explicitly use the theme's `onBackground`, matching neighboring settings. The full 1,113-test Android suite passed with the correction. After the user unlocked and reconnected the phone, the installed candidate2 APK was visually inspected in landscape: Player Layout, Modern and Classic have light text on the dark surface. An unobscured screenshot and UI tree are recorded as `.work/dual-player-20261020/android-settings-contrast-candidate2.png` and `.xml` in the original workspace.

Rapid repeated seeks still made Discord inaccurate in the user's check. The publisher previously sent each seek/buffering transition without a send budget. Discord documents [five activity updates per 20 seconds](https://github.com/discord/discord-api-docs/blob/main/developers/developer-tools/game-sdk.mdx). The session now spaces publication attempts by at least 4.1 seconds, retains the latest frame and original clock anchors, and re-reads the frame after waiting. Local and gateway completion anchor the cooldown; gateway failure retains its separate retry backoff. Local takeover waits for gateway cancellation before reading the current frame. Normal teardown still clears presence promptly.

Six scheduling regressions cover burst coalescing and final timestamps, disabling a pending seek, gateway coalescing, delayed gateway completion, updates during takeover cleanup, and failure retry without throttling. Behavioral red runs exposed burst delivery, delayed completion and retry defects; the complete ARM64 Java21 desktop suite passes 489 tests on candidate2. Independent follow-up source review found no remaining concrete issue in this correction. Discord can still lag rapid input by its publication interval and client/network delivery; instantaneous external updates are not promised. The user tested rapid seeks in the exact rebuilt candidate2 and confirmed that Discord settles correctly.

Normal candidate1 native quit removed both launcher/JVM processes and its window without force-stopping. Relaunching the same portable data root restored Classic in Appearance. No song/queue restoration across application shutdown is claimed by this preference check.

### Candidate 2 packages

The corrected Java25 portable was rebuilt and frozen at `outputs/BitChord-dual-player-20261020/candidate2/java25/BitChord-1.8.1-dual-player-candidate2-windows-arm64-java25-portable.zip` in the original workspace. SHA256 `0603d285fd651bdb1e7c5726bc5649042892ecc9f0e2b3aa6aeaec76aa753db3`, 408,329,766 bytes. The verifier also compares nested coroutine/lambda class bytes for the current player and Discord session with build outputs. All80 standalone PEs and34 selected JAR natives are ARM64; application bytecode remains Java21. This exact ZIP was extracted into a fresh data root and launched with its bundled ARM64 Java25 runtime; JVM35776 was responsive and loaded the expected graphics/native modules. The user confirmed rapid-seek/Discord acceptance.

The corrected phone APK is `outputs/BitChord-dual-player-20261020/candidate2/android/BitChord-1.8.1-dev-arm64-v8a-debug.apk`, SHA256 `72c9f89924df08804df001b7d0327e1a2da460a60310c5d6b4fb69af95b47fe8`, 98,531,658 bytes. All nine phone/TV/Wear APKs passed signature/ZIP/alignment/native-architecture inspection; phone/watch embedded bytes and certificate match. The signed phone update installed successfully with Dev data and regular-app metadata preserved. Corrected labels were visually verified after the user unlocked and reconnected the phone; no lock-screen automation was attempted.

The aligned Java21 fallback is `outputs/BitChord-dual-player-20261020/candidate2/java21/BitChord-1.8.1-dual-player-candidate2-windows-arm64-java21-portable.zip`, SHA256 `414ac7d91086d5c9f2b97ad499316d09d48436622761f635a25d291a2e812c93`, 399,447,967 bytes. It passes the same architecture/class checks. All116 application/dependency JARs are byte-identical to the Java25 package;139 selected outer and nested class files match current build outputs. Exact-fallback live execution remains unverified.

No new power or battery-life improvement is claimed from the selector. One exploratory candidate2 Modern synchronized-lyrics sample on AC, without concurrent builds, recorded 30 foreground observations: mean process CPU 0.3225 cores, private memory 1762.8 MiB and summed process 3D-engine utilization 20.9885%; other recorded GPU engine categories were zero. The sample lasted about 93 seconds including collection overhead. It does not establish a comparison with the original x64 release or measure energy. Source: `.work/dual-player-20261020/performance/modern-lyrics/lyrics-20261020-164901.json` in the original workspace. Equivalent paired workloads remain outstanding.

### Follow-up: retain paused Discord presence

The user requested that pausing or stopping retain the last song until BitChord quits. The desktop publisher now retains an existing track when playback clears it, marks paused activity with the localized paused label, and omits running timestamps while paused or awaiting audio. Resume uses a fresh clock anchor from the current player. Both local IPC and the existing gateway path use the same state/timeline policy. A fresh session without a track stays empty; explicitly disabling presence still clears it. Normal process exit closes the local IPC connection. No account token or playback-engine changes are involved.

Three policy regressions first failed because the new APIs were absent, then all four timeline tests passed. A transport regression exercises playing, retained paused track, resume and explicit disable. ARM64 Java21, ARM64 Java25 and x64 Java21 full suites each pass all493 tests. Independent source review found no actionable issues in this delta. Rebuilt candidate3's actual Discord pause/resume/quit behavior remains pending. The user quit candidate2 normally; launcher24652, JVM35776 and its window were all absent afterward.

## Reproduce locally

### Candidate 3 desktop packages

The paused-presence correction is packaged separately, preserving earlier candidates:

- Java25: `outputs/BitChord-dual-player-20261020/candidate3/java25/BitChord-1.8.1-dual-player-candidate3-windows-arm64-java25-portable.zip`, 408,329,969 bytes, SHA256 `ba6cd2fddd88c3980dfc2dccfed5c10d9c814c942d8623366814f2a966e413d8`.
- Java21 fallback: `outputs/BitChord-dual-player-20261020/candidate3/java21/BitChord-1.8.1-dual-player-candidate3-windows-arm64-java21-portable.zip`, 399,448,170 bytes, SHA256 `05347e4ce0626fc45fb9b4e186e4ddae1a891c8dbae02283a875b3058dcf615b`.

Both packages pass80 standalone PE and34 selected JAR-native ARM64 checks, Java21 application-bytecode inspection and139 selected current outer/nested class checks, now explicitly including `DesktopDiscordRpcKt`. Their116 dependency/application JARs and source-manifest SHA256 `3da52d38bc797d4e421cb48d01aee2f26670120a4194caa2b6dcb776ead2a4d1` match. Both exact ZIPs were extracted into new candidate-specific data roots; neither archive contains portable user/account data.

Java25 launched with bundled JVM32128, a responsive native window and expected ARM64 graphics/window modules. This initial health probe is not a playback or acoustic verification. Computer Use then reported a physical Escape stop; no subsequent Windows UI automation was attempted, pending explicit user restoration. Actual paused Discord presence, exact Java21 fallback live execution and remaining panel interactions remain open. The unchanged candidate2 phone APK remains the latest Android package.

The user subsequently explicitly restored test-window automation and confirmed Windows was ready. Computer Use still reported `foreground window did not report a process id` on a refreshed, uniquely selected candidate3 window. No unseen input was sent. Non-UI checks continued: the original Java21/Gradle8.11.1 baseline passed shared101, sharedUi58, desktopUi58 and desktop493 with no failures/errors. Archived XML is under `test-results/candidate3-baseline8111` in the audit directory.

The user manually tested candidate3: Discord retained the paused track without a running timer and restored correct timing on resume. Pausing again and using Quit BitChord removed that activity. A process-path check verified the Java25 copy had no remaining owned executable before opening the exact Java21 fallback in its separate data root. Its bundled JVM39160 and responsive window were verified; the user confirmed audible playback, pause/resume and seeking on this exact fallback. These observations supersede the earlier pending fallback/paused-presence gates, without implying gateway-account or secondary-platform manual acceptance.

In the exact fallback, the user also switched Modern and Classic, opened each layout's queue, lyrics and audio-output controls, and confirmed those panels work while song, position and volume survive switching. This supersedes the repeated-panel gate. Candidate1 supplied the normal quit/relaunch Classic-persistence and Bluetooth acceptance; candidate2 supplied corrected Android contrast and rapid-seek acceptance. Current candidate3 audio/graphics/native modules were observed loaded after playback.

### Current rendering comparison

The exact Java21 candidate3 ran all three observations in the same process39160, without builds or cleanup. The user prepared Modern synchronized lyrics for Sweater Weather, starting each run around1:00, with unchanged window and AC. All15 samples in each row were foreground, not minimized, on AC and had valid GPU counters. Each run spanned about53 seconds. The same Adreno physical3D engine was busiest throughout.

| Mode | App GPU3D mean | Whole busiest-engine mean | DWM on that engine | CPU cores mean | Private MiB mean |
| --- | ---: | ---: | ---: | ---: | ---: |
| Balanced A1 | 21.2883% | 37.4004% | 14.7208% | 0.3654 | 1770.9 |
| Efficient B | 14.0130% | 29.7093% | 14.3455% | 0.1431 | 1633.2 |
| Balanced A2 | 21.6194% | 37.1265% | 14.3302% | 0.3524 | 1668.1 |

Returning to Balanced restored the higher app GPU/CPU workload. This supports the retained Efficient visual policy on the integrated player. It does not isolate individual effects, compare architectures or establish battery savings. DWM is shared composition work, and memory readings include warm-cache/GC history. Saved Efficient and restored Balanced preference keys were checked through an allowlist; no account fields were inspected. Balanced remains restored. No hardware acceleration setting, frame cap, playback quality or engine was changed for this comparison.

Raw reports are under `.work/dual-player-20261020/performance/candidate3-balanced-A1`, `candidate3-efficient-B` and `candidate3-balanced-A2` in the original workspace; the derived summary is `candidate3-mode-comparison.json`. These AC observations have `energyMeasured=false`; occasional driver discharge while AC is present is not whole-device power consumption.

A separate Balanced minimized-playback run recorded15/15 minimized,0/15 foreground and15/15 AC samples, no GPU errors, mean app3D2.8342%, private1618.3MiB and CPU0.0000 cores at the collector's resolution. Residual GPU work remains; zero CPU at this resolution does not mean no processing occurred. The user restored the window without pressing Play and confirmed music had continued, with working sound, playhead and lyrics. Source: `performance/candidate3-minimized/minimized-20261021-012600.json`. The exposed desktop differs when minimized, so its whole-GPU behavior is not attributed solely to BitChord.

### Battery observations on current code

The user unplugged the charger and prepared the same Modern/Sweater Weather synchronized-lyrics segment for a Balanced/Efficient/Balanced sequence. Each row contains15 foreground samples with AC absent, WMI reporting discharge, valid GPU counters and valid absolute native battery status. All runs used the same Java21 process without builds or cleanup.

| Mode | App3D mean | Whole busiest-engine mean | CPU cores mean | Driver whole-device discharge mean |
| --- | ---: | ---: | ---: | ---: |
| Balanced A1 | 20.9704% | 40.0878% | 0.5322 | 14.1394 W |
| Efficient B | 13.3233% | 34.0117% | 0.4175 | 11.5890 W |
| Balanced A2 | 21.1146% | 39.5635% | 0.4925 | 14.1447 W |

The lower Efficient reading and return of both controls support reduced rendering work and lower driver-reported discharge in this short workload. They do not establish calibrated app energy or a battery-life percentage: readings cover the entire device, other processes remain active, and the three sequential runs are short. No hardware-output fidelity claim follows from these measurements.

The cached probe used during collection predated its explicit `shortTerm` JSON field. The collector correctly left normalized rates null, while preserving the complete raw capability mask, status and signed rate. Post-analysis derives the missing flag from those immutable raw records: SYSTEM_BATTERY0x80000000 set, CAPACITY_RELATIVE0x40000000 and IS_SHORT_TERM0x20000000 clear, valid offline/discharging status and a non-sentinel negative rate. Constants were checked in installed Windows SDK10.0.26100.0/shared/poclass.h; no raw report was modified. The current probe source was rebuilt with MSVC ARM64, PE-checked and run with the current converter to verify explicit schema/normalization. Existing battery and GPU counter regression scripts pass. These reconstructed native-driver means supersede the earlier preliminary WMI averages in commentary.

Evidence: `.work/dual-player-20261020/performance/candidate3-battery-{balanced-A1,efficient-B,balanced-A2}` and `candidate3-battery-mode-comparison.json` in the original workspace. Balanced was restored. After the user reconnected the charger and used tray Quit, both candidate3 process trees were absent; Windows reported AC/charging. Current-helper and final-environment records are under `.work/dual-player-20261020`.

Run from the original workspace, one Gradle process at a time:

```powershell
rtk proxy powershell -NoProfile -File .work/verify_v181_integration.ps1 -Stage dual-layout-regressions -RunId dual-layout
rtk proxy powershell -NoProfile -File .work/verify_v181_integration.ps1 -Stage desktop-java25 -RunId dual-layout
rtk proxy powershell -NoProfile -File .work/verify_v181_integration.ps1 -Stage desktop -Architecture x64 -RunId dual-layout
rtk proxy powershell -NoProfile -File .work/verify_v181_integration.ps1 -Stage android-platforms -UseVerifiedGoogleStaging -RunId dual-layout
rtk proxy powershell -NoProfile -File .work/package_v181_portable.ps1 -Runtime 25 -RunId dual-layout
rtk proxy python -B -X utf8 .work/freeze_v181_portable.py 25 candidate4
```

The packaging task is `:desktopApp:windowsPortable`, with explicit Windows/ARM64 target, matching runtime and verified local native inputs as documented in [Windows ARM64 setup](windows-arm64.md). The local freeze helper refuses existing candidate destinations; increment the candidate number for a new package. Runtime 21 packaging remains supported with `-Runtime 21`. Build JVM modernization does not raise the application's minimum Java runtime.

## Changed source areas

- Shared preference enum and its tests.
- Both UI modules: `PlayerHost`, `LandscapePlayer`, `NowPlayingScreen`, and new displayed-pane tests.
- Desktop: persisted setting, Appearance radio preference, host wiring and persistence regression.
- Android: `AppSettings`, `AndroidPlayerHost`, Appearance radio preference, SettingsSheet, and 19 localized string catalogs.
- Discord: `DesktopDiscordSession` and its tests pace and coalesce rapid seek publications while retaining cancellation and retry ownership. `DesktopDiscordRpc` and timeline tests retain paused tracks without running timestamps and restore current timing on resume.
- Design/implementation/validation records. Build infrastructure and upstream integration changes are documented separately in [the integration record](upstream-v181-integration.md).
