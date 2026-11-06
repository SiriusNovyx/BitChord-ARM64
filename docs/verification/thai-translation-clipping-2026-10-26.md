# Thai translated-subline clipping at 150% display scaling

Status: **Local correction verified; reported Surface translation regression accepted at 150% scaling.** Android and other physical scaling/text-size cases remain unverified.

## Accepted and rejected Surface results

The user identifies the tested Windows ARM64 ZIP as `add8811a65213bd6d6913371b4cf5011f2090a8797530bbf23819cf2457f3c12`. Its compiled application/UI classes, font and runtime had been verified against the source. The user reports **no distortion** on the automatic Susan Boyle “Amazing Grace” → Halsey “Walls Could Talk” transition. In the same binary the remaining Thai clipping occurs in **translation rows only**, at **150% Windows display scaling**. This is a current rendering defect, not an outdated-package explanation. The earlier main-lyric fixes and accepted playback behavior are preserved.

## Read-only audit and checklist

Before production changes, the two actual `PlayerLyrics.kt` implementations, shared `ShapedLyricLine.kt`, existing rendering tests and prior report were inspected and archived with hashes. The original HEAD and staging-index SHA were checked; the index remains protected. Recoverable files and diagnostic controls live outside the source root in `.work/thai-translation-20261026`.

- [x] Reproduce an English main lyric with an untimed Thai translation through the actual production panel at density 1.5.
- [x] Isolate the translation's constant-alpha layer and partially open reveal layer without modifying production sources.
- [x] Add a failing full-panel regression, then a minimal buffer correction and a partial-opacity/blur regression.
- [x] Finish module/platform checks, compiled-panel captures and immutable package verification.
- [x] Reconcile the reviewed source/test/report changes into the pending complete-history merge, preserving both indexes.
- [x] Obtain physical Surface verification of translations and transitions at 150% scaling (user: “All intact”).

## Confirmed root cause and correction

The translated subline has its own `graphicsLayer` at alpha 0.85 **inside** its existing horizontal/bottom padding and upward 6-dp tuck. Its default offscreen buffer ends at the fixed text line box, cropping Bangna's actual ascent/left overhang. The primary row's expanded effect buffer cannot recover ink already lost in this child buffer. Untimed translations use plain `Text`; fixing only the main word-sweep renderer therefore does not fix this path.

A baseline full-panel regression loses 17/113, 18/114 and 31/135 bright reference pixels for the three leading vowels at normal text size; at doubled text size it loses 65/433, 76/446 and 126/510. An empty preceding line in the reference keeps the same shaped glyph inside the same production alpha buffer. Both versions use the identical font, style and panel; comparison aligns their lower ink, rather than replacing the glyph or changing its metrics. A diagnostic alpha-one control and a measured-buffer control isolate this boundary. Only the buffer control retains the original opacity while restoring the heads.

The partially open reveal introduces another alpha buffer. A control that widens only the inner buffer still loses upper ink while opening. The correction reuses the measured font outsets for the inner effect layer and the reveal buffer, with the existing upward tuck included in the latter's paint extent. Both platform panels use one shared reveal implementation. Its reported width/animated height, slide distance and squared-opacity curve remain the same; only the buffer and child placement allow the existing overhang. The translated answering-vocal subline uses the same correction at its existing smaller style. Non-Thai text receives no extra tuck outset.

No font asset, font registration, global typography, lyric-size constant, text content, synchronization clock, scroll behavior, playback/DSP or dependency setting is changed. Intentional standby blur and translation opacity remain in effect.

## Files in this correction

- `desktopUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/player/PlayerLyrics.kt`
- `sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/player/PlayerLyrics.kt`
- `sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/player/shaped/ShapedLyricLine.kt`
- `sharedUi/src/jvmTest/kotlin/com/music/bitchord/ui/player/shaped/ThaiTranslationRenderingTest.kt`
- `sharedUi/build.gradle.kts`: JVM test-runtime-only Swing Main dispatcher, using the already pinned `1.9.0` version from Desktop UI. The first full run exposed its absence when the new test rendered the full panel; application/Android dependencies and all versions remain unchanged.
- This report and the prior Thai/upstream verification reports.

## Verification and limits

The baseline production-panel regression fails before the correction in all six normal/enlarged leading-vowel cases. The diagnostic inner-buffer control passes the fully open regression. The corrected production bodies pass the focused full-panel and partial-reveal comparisons. The latter compares the complete shaped ink against a large-buffer reference at 25/50/75/100% reveal, normal/doubled text size, density 1.5, and blur 0/2 dp. The module test also checks the reported animated height. Final Gradle/platform/package results are pending below and must not be inferred from these focused probes.

The independent original-reveal control retains the inner-buffer correction but restores the old reveal implementation in an external diagnostic copy. The full-panel test passes, while the partial-reveal test fails at 25% reveal with 4,919/12,235 missing intensity (40.2%). This confirms that the second regression detects a separate boundary rather than merely repeating the fully open test.

The final ARM64 Java 21 UI run (`thai-translation-ui-arm64-main-dispatcher-20261026`) exits 0 in 141.15 seconds: Desktop UI 98 passes and Shared UI 74 passes, no errors/skips. The initial run failed because Shared UI's JVM test runtime lacked a Main dispatcher; its XML is preserved. The test-only runtime addition above addresses that actual prerequisite.

The first x64 Java 21 run passes both new Thai cases but fails an existing real-window background-cadence check: at 60 FPS it counts 11 updates, below the test's minimum 12; 15/30 FPS count 6/11. No production pacing code or assertion is changed. A fully preserved unchanged-source retry, driven by ARM64 Java 25 with actual x64 Java 21 test-worker paths verified, exits 0 in 69.87 seconds: Desktop UI 98 passes and Shared UI 74 passes, zero skips. The initial result is a timing-sensitive emulated-window observation; the retry alone does not establish its root cause or a GPU improvement.

Physical acceptance remains required on the Surface with 150% Windows scaling: verify untimed translations, translated answering vocals, active and blurred standby rows, opening/closing and language switching, enlarged text, and the accepted automatic track pair. Automated Skia scenes do not establish touchscreen, actual Atmos output, GPU improvement or general device correctness.

## Final local candidates and verification

`thai-translation-packages-arm64-20261026` exits 0 in 255.20 seconds. The current Android unit suite actually executes **1,147 passes, zero failures/errors/skips**, with the existing `LIVE_TESTS=1` opt-in enabled; all five `GeniusTest` cases execute, including the live search/scrape case. Android, TV and Wear OS Dev Debug packaging succeeds. Raw XML containing lyric previews stays in the private local audit and is excluded from publication summaries.

The actual compiled Desktop UI JAR produces both normal/enlarged full-panel captures with an English main lyric and Thai translation at density 1.5 (`thai-translation-compiled-panel-20261026`). Those captures were visually inspected: upper heads are present in active and blurred standby sublines. A separate 16-sp/21-sp smaller-style comparison executes both regression cases against the compiled renderer and passes; it checks the translated backing style's buffer behavior without claiming a complete physical answering-vocal interaction test.

The immutable local deliverable folder is `.work/rejected-surface-20261023/deliverables/2026-10-26-thai-translations` in the outer development workspace:

| Artifact | Bytes | SHA-256 |
|---|---:|---|
| `BitChord-thai-translations-windows-arm64.zip` | 408,641,338 | `6be6a26bbaf6545499e96850f2f03339fb39f7d9e118496855c7d2e99be2271d` |
| `BitChord-thai-translations-android-arm64-debug.apk` | 99,298,435 | `62d70d6a9f02df5e3535e72ec00620a2e79decab83c2868f2b292eb8b9e72040` |

Verification checks ZIP/APK CRC, all 80 standalone Windows ARM64 PE files, 34 selected native JAR resources, all 1,355 current application classes, current Desktop UI/shared JAR bytes, the unchanged original Bangna font and Spotify helper/notices. APK verification checks only `arm64-v8a` native ABI, ten AArch64 ELF files, presence of the new shared reveal implementation in DEX, a valid Dev signature and the unchanged update certificate/package identity (`com.dev.bitchord`, version 1.8.1/code 29). These are structural/source-identity checks, not a physical acceptance claim. Both packages were rebuilt for this correction; the earlier `add8811a…` checkpoint remains unchanged.

Review confirms that this correction adds no timer, playback-clock read, change to background pacing, global typography or new pointer handler. The measured-height regression and existing row-bound/hitbox tests pass. Both constant-opacity grouping and the reveal's squared-opacity curve remain; blur is retained. The only dependency addition is the same existing Swing dispatcher version on Shared UI's JVM **test** runtime.

No commit, upload, push, publishing or pull request is performed. Broader upstream capability, model/phase, long-session and physical-device acceptance remain open.


The eight reviewed code/test/build/report files were reconciled into the pending full-history checkout with before-copy archives and content/hash guards. Both staging indexes remain byte-identical. A current path comparison counts 1,855 authoritative paths and 1,852 candidate project paths, with no content difference and only three deliberately local environment entries excluded. All 110 original merge conflicts remain resolved. These are reconciliation checks, not feature-completeness acceptance or publication authorization.


## Surface acceptance — verified package 6be6a26b

The user answers **“All intact”** for the Windows ARM64 ZIP with SHA-256 `6be6a26bbaf6545499e96850f2f03339fb39f7d9e118496855c7d2e99be2271d`: Thai translation heads/overhang at **150% Windows display scaling**, in **active, blurred standby, and opening/closing states**. This closes that reported Surface regression. The earlier `add8811a…` rejection remains valid historical evidence. This acceptance does not extend to Android, unasked lyric-size settings, other physical scaling values, Atmos, GPU usage or general AutoMix quality.
